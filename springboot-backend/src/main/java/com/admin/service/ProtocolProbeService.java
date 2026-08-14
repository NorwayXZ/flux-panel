package com.admin.service;

import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.admin.entity.PrivateProxy;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PrivateProxyMapper;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class ProtocolProbeService {
    private static final String DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down";
    private static final String DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up";
    private static final long MIN_BYTES = 1L * 1024 * 1024;
    private static final long MAX_BYTES = 128L * 1024 * 1024;

    private final JdbcTemplate jdbcTemplate;
    private final PrivateProxyMapper proxyMapper;
    private final NodeMapper nodeMapper;
    private final PrivateProxyService privateProxyService;
    private final ProtocolClientProbeService clientProbeService;
    private final AESCrypto crypto;

    public ProtocolProbeService(JdbcTemplate jdbcTemplate, PrivateProxyMapper proxyMapper,
                                NodeMapper nodeMapper, PrivateProxyService privateProxyService,
                                ProtocolClientProbeService clientProbeService,
                                @Value("${jwt-secret}") String secret) {
        this.jdbcTemplate = jdbcTemplate;
        this.proxyMapper = proxyMapper;
        this.nodeMapper = nodeMapper;
        this.privateProxyService = privateProxyService;
        this.clientProbeService = clientProbeService;
        this.crypto = new AESCrypto(secret + ":private-proxy");
    }

    public R overview() {
        List<Map<String, Object>> items = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<PrivateProxy> proxies = (List<PrivateProxy>) privateProxyService.list().getData();
        if (proxies != null) {
            for (PrivateProxy proxy : proxies) {
                Node node = proxy.getNodeId() == null ? null : nodeMapper.selectById(proxy.getNodeId());
                items.add(targetView(createdTarget(proxy, node)));
            }
        }
        for (Map<String, Object> external : externalTargets()) {
            items.add(targetView(externalTarget(external)));
        }

        Map<String, Map<String, Object>> latest = latestForCurrentUser();
        for (Map<String, Object> item : items) {
            item.put("latest", latest.get(targetKey(
                    Objects.toString(item.get("targetType"), "created"),
                    ((Number) item.get("targetId")).longValue())));
        }
        return R.ok(Map.of(
                "downloadUrl", DEFAULT_DOWNLOAD_URL,
                "uploadUrl", DEFAULT_UPLOAD_URL,
                "probeSource", ProtocolClientProbeService.probeSource(),
                "clientEngine", ProtocolClientProbeService.clientEngine(),
                "clientEngineVersion", ProtocolClientProbeService.clientEngineVersion(),
                "items", items));
    }

    public R history(String targetType, Long targetId, int limit) {
        ProbeTarget target = accessibleTarget(targetType, targetId);
        if (target == null) return R.err("测速目标不存在或无权访问");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String normalizedType = normalizeTargetType(targetType);
        String typePredicate = "created".equals(normalizedType)
                ? "(target_type='created' OR target_type IS NULL)" : "target_type=?";
        String targetIdPredicate = "created".equals(normalizedType)
                ? "(target_id=? OR (target_id IS NULL AND proxy_id=?))" : "target_id=?";
        String sql = "SELECT id,status,available,target_url AS targetUrl,download_bytes AS downloadBytes,"
                + "upload_bytes AS uploadBytes,latency_ms AS latencyMs,handshake_ms AS handshakeMs,"
                + "download_bytes_actual AS downloadBytesActual,download_mbps AS downloadMbps,"
                + "upload_bytes_actual AS uploadBytesActual,upload_mbps AS uploadMbps,"
                + "download_status AS downloadStatus,upload_status AS uploadStatus,error,"
                + "agent_version AS agentVersion,probe_source AS probeSource,client_engine AS clientEngine,"
                + "client_engine_version AS clientEngineVersion,started_at AS startedAt,finished_at AS finishedAt "
                + "FROM protocol_probe_run WHERE " + typePredicate + " AND " + targetIdPredicate + " "
                + "AND (user_id=? OR ?=0) ORDER BY started_at DESC LIMIT " + safeLimit;
        Integer userId = currentUserId();
        Object[] args = "created".equals(normalizedType)
                ? new Object[]{targetId, targetId, userId, isAdmin() ? 0 : 1}
                : new Object[]{normalizedType, targetId, userId, isAdmin() ? 0 : 1};
        return R.ok(jdbcTemplate.queryForList(sql, args));
    }

    public synchronized R run(String targetType, Long targetId, Map<String, Object> params) {
        ProbeTarget target = accessibleTarget(targetType, targetId);
        if (target == null) return R.err("测速目标不存在或无权访问");
        Map<String, Object> capability = capability(target);
        if (!"supported".equals(capability.get("status"))) {
            return R.err(String.valueOf(capability.get("message")));
        }

        long downloadBytes;
        long uploadBytes;
        String downloadUrl;
        String uploadUrl;
        try {
            downloadBytes = bytes(params == null ? null : params.get("downloadBytes"), 32L * 1024 * 1024);
            uploadBytes = bytes(params == null ? null : params.get("uploadBytes"), 16L * 1024 * 1024);
            downloadUrl = normalizeUrl(params == null ? null : params.get("downloadUrl"), DEFAULT_DOWNLOAD_URL);
            uploadUrl = normalizeUrl(params == null ? null : params.get("uploadUrl"), DEFAULT_UPLOAD_URL);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }

        long startedAt = System.currentTimeMillis();
        Long runId = insertRun(target, downloadUrl, downloadBytes, uploadBytes, startedAt);
        try {
            Map<String, Object> result = clientProbeService.probe(
                    target.proxyType(), target.host(), target.port(), target.username(), target.password(),
                    downloadUrl, uploadUrl, downloadBytes, uploadBytes, 120_000);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            finishRun(runId, target, success ? "success" : "failed", result, startedAt);
            return success ? R.ok(resultWithHistory(runId, target, result))
                    : R.err(String.valueOf(result.getOrDefault("error", "协议探测失败")));
        } catch (Exception e) {
            String error = concise(e.getMessage());
            finishRun(runId, target, "failed", Map.of("error", error), startedAt);
            return R.err(error);
        }
    }

    public R externalList() {
        return R.ok(externalTargets());
    }

    public R saveExternal(Map<String, Object> body) {
        String proxyType = StringUtils.lowerCase(StringUtils.trimToEmpty(text(body, "proxyType")));
        if (!List.of("socks5", "http").contains(proxyType)) {
            return R.err("外部协议目前只支持 SOCKS5 和 HTTP");
        }
        String name = StringUtils.trimToEmpty(text(body, "name"));
        String host = StringUtils.trimToEmpty(text(body, "host"));
        String username = StringUtils.trimToEmpty(text(body, "username"));
        String password = StringUtils.defaultString(text(body, "password"));
        int port;
        try {
            port = Integer.parseInt(text(body, "port"));
        } catch (Exception e) {
            return R.err("协议端口必须是 1-65535 的数字");
        }
        if (name.isBlank()) name = host + ":" + port;
        if (name.length() > 120 || host.isBlank() || host.length() > 255 || host.contains(" ")) {
            return R.err("协议名称或地址格式不正确");
        }
        if (port < 1 || port > 65535) return R.err("协议端口必须在 1-65535 之间");
        if (username.length() > 255 || password.length() > 255) return R.err("认证信息过长");
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO protocol_probe_target(user_id,name,proxy_type,host,port,auth_username,"
                        + "auth_password,state,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                currentUserId(), name, proxyType, host, port,
                crypto.encrypt(username), crypto.encrypt(password), "active", now, now);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return R.ok(externalView(id));
    }

    public R deleteExternal(Long id) {
        if (id == null || externalTarget(id) == null) return R.err("外部协议不存在或无权访问");
        jdbcTemplate.update("DELETE FROM protocol_probe_target WHERE id=?", id);
        return R.ok();
    }

    private ProbeTarget accessibleTarget(String targetType, Long targetId) {
        if (targetId == null) return null;
        String normalizedType = normalizeTargetType(targetType);
        if ("external".equals(normalizedType)) {
            Map<String, Object> row = externalTarget(targetId);
            return row == null ? null : externalTarget(row);
        }
        PrivateProxy proxy = proxyMapper.selectById(targetId);
        if (proxy == null || "deleted".equals(proxy.getState())) return null;
        if (!isAdmin() && !Objects.equals(proxy.getUserId(), currentUserId())) return null;
        Node node = proxy.getNodeId() == null ? null : nodeMapper.selectById(proxy.getNodeId());
        return createdTarget(proxy, node);
    }

    private ProbeTarget createdTarget(PrivateProxy proxy, Node node) {
        String host = node == null ? null : StringUtils.defaultIfBlank(node.getServerIp(), node.getIp());
        String username = "";
        String password = "";
        try {
            JSONObject config = decryptConfig(proxy);
            username = StringUtils.defaultString(config.getString("username"));
            password = StringUtils.defaultString(config.getString("password"));
        } catch (Exception ignored) {
            // The capability will report an unusable target and run will not leak the error.
        }
        return new ProbeTarget("created", proxy.getId(), proxy.getName(), proxy.getProxyType(), proxy.getNodeId(),
                host, proxy.getListenPort() == null ? 0 : proxy.getListenPort(), username, password,
                proxy.getState(), node == null ? null : node.getName());
    }

    private ProbeTarget externalTarget(Map<String, Object> row) {
        String username = decrypt(row.get("auth_username"));
        String password = decrypt(row.get("auth_password"));
        return new ProbeTarget("external", number(row.get("id")).longValue(), Objects.toString(row.get("name"), ""),
                Objects.toString(row.get("proxy_type"), ""), null, Objects.toString(row.get("host"), ""),
                number(row.get("port")).intValue(), username, password,
                Objects.toString(row.get("state"), "active"), "外部协议");
    }

    private Map<String, Object> targetView(ProbeTarget target) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("targetType", target.targetType());
        view.put("targetId", target.targetId());
        view.put("name", target.name());
        view.put("proxyType", target.proxyType());
        view.put("host", target.host());
        view.put("port", target.port());
        view.put("nodeName", target.nodeName());
        view.put("source", "external".equals(target.targetType()) ? "外部协议" : "CloudNest 创建");
        view.put("capability", capability(target));
        return view;
    }

    private Map<String, Object> capability(ProbeTarget target) {
        String label = protocolLabel(target.proxyType());
        if (!List.of("socks5", "http").contains(target.proxyType())) {
            return Map.of("status", "pending", "label", label,
                    "message", "等待对应独立客户端引擎，暂不显示伪造测速结果");
        }
        if (target.host() == null || target.host().isBlank() || target.port() < 1 || target.port() > 65535) {
            return Map.of("status", "pending", "label", label, "message", "协议公网地址或端口不完整");
        }
        if (!"active".equalsIgnoreCase(target.state())) {
            return Map.of("status", "pending", "label", label,
                    "message", "协议当前状态为 " + target.state() + "，无法进行真实连接");
        }
        return Map.of("status", "supported", "label", label,
                "message", "面板协议客户端可通过该协议实际连接并测速");
    }

    private Long insertRun(ProbeTarget target, String url, long downloadBytes,
                           long uploadBytes, long startedAt) {
        jdbcTemplate.update("INSERT INTO protocol_probe_run(proxy_id,user_id,node_id,proxy_type,probe_node_id,"
                        + "target_type,target_id,target_name,status,available,target_url,download_bytes,upload_bytes,"
                        + "probe_source,client_engine,client_engine_version,started_at,finished_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "created".equals(target.targetType()) ? target.targetId() : null,
                currentUserId(), target.nodeId(),
                target.proxyType(), null, target.targetType(), target.targetId(), target.name(),
                "running", 0, url, downloadBytes, uploadBytes,
                ProtocolClientProbeService.probeSource(), ProtocolClientProbeService.clientEngine(),
                ProtocolClientProbeService.clientEngineVersion(), startedAt, startedAt);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void finishRun(Long runId, ProbeTarget target, String status,
                           Map<String, Object> result, long startedAt) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE protocol_probe_run SET status=?,available=?,latency_ms=?,handshake_ms=?,"
                        + "download_bytes_actual=?,download_mbps=?,upload_bytes_actual=?,upload_mbps=?,"
                        + "download_status=?,upload_status=?,error=?,agent_version=NULL,probe_source=?,"
                        + "client_engine=?,client_engine_version=?,finished_at=? WHERE id=?",
                status, Boolean.TRUE.equals(result.get("success")) ? 1 : 0,
                decimal(result.get("latencyMs")), decimal(result.get("handshakeMs")),
                longValue(result.get("downloadBytes")), decimal(result.get("downloadMbps")),
                longValue(result.get("uploadBytes")), decimal(result.get("uploadMbps")),
                intValue(result.get("downloadStatus")), intValue(result.get("uploadStatus")),
                concise(result.get("error")), ProtocolClientProbeService.probeSource(),
                ProtocolClientProbeService.clientEngine(), ProtocolClientProbeService.clientEngineVersion(),
                now, runId);
    }

    private Map<String, Object> resultWithHistory(Long runId, ProbeTarget target,
                                                   Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("runId", runId);
        response.put("targetType", target.targetType());
        response.put("targetId", target.targetId());
        response.put("probeSource", ProtocolClientProbeService.probeSource());
        response.put("clientEngine", ProtocolClientProbeService.clientEngine());
        response.put("clientEngineVersion", ProtocolClientProbeService.clientEngineVersion());
        return response;
    }

    private Map<String, Map<String, Object>> latestForCurrentUser() {
        Integer userId = currentUserId();
        String sql = "SELECT id,target_type AS targetType,COALESCE(target_id,proxy_id) AS targetId,status,available,"
                + "latency_ms AS latencyMs,handshake_ms AS handshakeMs,download_mbps AS downloadMbps,"
                + "upload_mbps AS uploadMbps,error,probe_source AS probeSource,client_engine AS clientEngine,"
                + "client_engine_version AS clientEngineVersion,started_at AS startedAt,finished_at AS finishedAt "
                + "FROM protocol_probe_run WHERE (user_id=? OR ?=0) ORDER BY started_at DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId, isAdmin() ? 0 : 1);
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String type = StringUtils.defaultIfBlank(Objects.toString(row.get("targetType"), null), "created");
            Number id = number(row.get("targetId"));
            if (id != null) latest.putIfAbsent(targetKey(type, id.longValue()), row);
        }
        return latest;
    }

    private List<Map<String, Object>> externalTargets() {
        String sql = "SELECT id,name,proxy_type,host,port,state,created_at AS createdAt,updated_at AS updatedAt "
                + "FROM protocol_probe_target WHERE state <> 'deleted' "
                + (isAdmin() ? "" : "AND user_id=? ") + "ORDER BY updated_at DESC,id DESC";
        return isAdmin() ? jdbcTemplate.queryForList(sql) : jdbcTemplate.queryForList(sql, currentUserId());
    }

    private Map<String, Object> externalTarget(Long id) {
        if (id == null) return null;
        String sql = "SELECT id,user_id,name,proxy_type,host,port,auth_username,auth_password,state "
                + "FROM protocol_probe_target WHERE id=?";
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(sql, id);
            if (!isAdmin() && (number(row.get("user_id")) == null
                    || !Objects.equals(number(row.get("user_id")).intValue(), currentUserId()))) return null;
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> externalView(Long id) {
        Map<String, Object> row = externalTarget(id);
        if (row == null) return Map.of();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.get("id"));
        view.put("name", row.get("name"));
        view.put("proxyType", row.get("proxy_type"));
        view.put("host", row.get("host"));
        view.put("port", row.get("port"));
        view.put("state", row.get("state"));
        return view;
    }

    private JSONObject decryptConfig(PrivateProxy proxy) {
        JSONObject config;
        if (StringUtils.isNotBlank(proxy.getClientConfig())) {
            config = JSONObject.parseObject(crypto.decryptString(proxy.getClientConfig()));
        } else {
            config = new JSONObject();
            if ("socks5".equals(proxy.getProxyType()) || "http".equals(proxy.getProxyType())) {
                config.put("username", proxy.getAuthUsername());
                config.put("password", crypto.decryptString(proxy.getAuthPassword()));
            }
        }
        return config == null ? new JSONObject() : config;
    }

    private String decrypt(Object value) {
        if (value == null) return "";
        try {
            return crypto.decryptString(String.valueOf(value));
        } catch (Exception e) {
            return "";
        }
    }

    private String normalizeTargetType(String value) {
        return "external".equalsIgnoreCase(value) ? "external" : "created";
    }

    private String targetKey(String type, long id) {
        return type + ":" + id;
    }

    private String protocolLabel(String type) {
        return switch (StringUtils.defaultString(type).toLowerCase(Locale.ROOT)) {
            case "socks5" -> "SOCKS5";
            case "http" -> "HTTP";
            case "vless_reality" -> "VLESS + REALITY";
            case "shadowsocks" -> "Shadowsocks";
            case "trojan" -> "Trojan";
            case "hysteria2" -> "Hysteria2";
            case "tuic" -> "TUIC v5";
            case "wireguard" -> "WireGuard";
            default -> type;
        };
    }

    private long bytes(Object raw, long fallback) {
        long value;
        try {
            value = raw == null ? fallback : Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            value = fallback;
        }
        if (value < MIN_BYTES || value > MAX_BYTES) {
            throw new IllegalArgumentException("单次测速大小应在 1-128 MiB 之间");
        }
        return value;
    }

    private String normalizeUrl(Object raw, String fallback) {
        String value = StringUtils.defaultIfBlank(raw == null ? null : raw.toString(), fallback).trim();
        try {
            URI uri = URI.create(value);
            if (!List.of("http", "https").contains(StringUtils.lowerCase(uri.getScheme()))
                    || uri.getHost() == null
                    || !"speed.cloudflare.com".equalsIgnoreCase(uri.getHost())
                    || value.length() > 500) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException("测速地址必须是合法的 HTTP/HTTPS URL");
        }
    }

    private Integer currentUserId() {
        return JwtUtil.getUserIdFromToken();
    }

    private boolean isAdmin() {
        return Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
    }

    private String text(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? "" : String.valueOf(body.get(key));
    }

    private Number number(Object value) {
        return value instanceof Number ? (Number) value : value == null ? null : Long.valueOf(value.toString());
    }

    private String concise(Object value) {
        String text = StringUtils.defaultIfBlank(value == null ? null : value.toString(), "未知错误")
                .replace('\n', ' ');
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private Long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private Integer intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private Double decimal(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private record ProbeTarget(String targetType, Long targetId, String name, String proxyType,
                               Long nodeId,
                               String host, int port, String username, String password,
                               String state, String nodeName) {
    }
}
