package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.entity.PrivateProxy;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PrivateProxyMapper;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProtocolProbeService {
    static final String MIN_AGENT_VERSION = "2.51.11";
    private static final String DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down";
    private static final String DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up";
    private static final long MIN_BYTES = 1L * 1024 * 1024;
    private static final long MAX_BYTES = 128L * 1024 * 1024;

    private final JdbcTemplate jdbcTemplate;
    private final PrivateProxyMapper proxyMapper;
    private final NodeMapper nodeMapper;
    private final PrivateProxyService privateProxyService;
    private final AESCrypto crypto;

    public ProtocolProbeService(JdbcTemplate jdbcTemplate, PrivateProxyMapper proxyMapper, NodeMapper nodeMapper,
                                PrivateProxyService privateProxyService, @Value("${jwt-secret}") String secret) {
        this.jdbcTemplate = jdbcTemplate;
        this.proxyMapper = proxyMapper;
        this.nodeMapper = nodeMapper;
        this.privateProxyService = privateProxyService;
        this.crypto = new AESCrypto(secret + ":private-proxy");
    }

    public R overview() {
        @SuppressWarnings("unchecked")
        List<PrivateProxy> proxies = (List<PrivateProxy>) privateProxyService.list().getData();
        List<Map<String, Object>> items = new ArrayList<>();
        if (proxies == null || proxies.isEmpty()) {
            return R.ok(Map.of("minimumAgentVersion", MIN_AGENT_VERSION,
                    "downloadUrl", DEFAULT_DOWNLOAD_URL, "uploadUrl", DEFAULT_UPLOAD_URL, "items", items));
        }

        Set<Long> nodeIds = proxies.stream()
                .map(PrivateProxy::getNodeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, Node> nodes = nodeIds.isEmpty() ? Map.of()
                : nodeMapper.selectBatchIds(nodeIds).stream()
                .collect(Collectors.toMap(Node::getId, node -> node, (left, right) -> left));
        Map<Long, Map<String, Object>> latestRuns = latestByProxyIds(
                proxies.stream().map(PrivateProxy::getId).filter(Objects::nonNull).toList());

        for (PrivateProxy proxy : proxies) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("proxy", proxy);
            item.put("capability", capability(proxy.getProxyType(), nodes.get(proxy.getNodeId())));
            item.put("latest", latestRuns.get(proxy.getId()));
            items.add(item);
        }
        return R.ok(Map.of("minimumAgentVersion", MIN_AGENT_VERSION,
                "downloadUrl", DEFAULT_DOWNLOAD_URL, "uploadUrl", DEFAULT_UPLOAD_URL, "items", items));
    }

    public R history(Long proxyId, int limit) {
        PrivateProxy proxy = accessible(proxyId);
        if (proxy == null) return R.err("代理不存在或无权访问");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return R.ok(jdbcTemplate.queryForList("SELECT id,status,available,target_url AS targetUrl,download_bytes AS downloadBytes,"
                        + "upload_bytes AS uploadBytes,latency_ms AS latencyMs,handshake_ms AS handshakeMs,"
                        + "download_bytes_actual AS downloadBytesActual,download_mbps AS downloadMbps,"
                        + "upload_bytes_actual AS uploadBytesActual,upload_mbps AS uploadMbps,download_status AS downloadStatus,"
                        + "upload_status AS uploadStatus,error,agent_version AS agentVersion,started_at AS startedAt,finished_at AS finishedAt "
                        + "FROM protocol_probe_run WHERE proxy_id=? ORDER BY started_at DESC LIMIT " + safeLimit, proxyId));
    }

    public synchronized R run(Long proxyId, Map<String, Object> params) {
        PrivateProxy proxy = accessible(proxyId);
        if (proxy == null) return R.err("代理不存在或无权访问");
        Map<String, Object> capability = capability(proxy.getProxyType(), nodeMapper.selectById(proxy.getNodeId()));
        if (!"supported".equals(capability.get("status"))) {
            return R.err(String.valueOf(capability.get("message")));
        }
        Node node = nodeMapper.selectById(proxy.getNodeId());
        if (node == null || !WebSocketServer.isNodeOnline(node.getId())) return R.err("协议所在节点离线");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            return R.err("协议测速需要 Agent " + MIN_AGENT_VERSION + " 或更高版本");
        }
        JSONObject config;
        try {
            config = decryptConfig(proxy);
        } catch (Exception e) {
            return R.err("协议连接信息解密失败");
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
        Long runId = insertRun(proxy, node, downloadUrl, downloadBytes, uploadBytes, startedAt);
        String runtimeName = "protocol-probe-" + proxy.getId() + "-" + runId;
        try {
            Map<String, Object> probe = runProbe(proxy, node, config, runtimeName, downloadUrl, uploadUrl,
                    downloadBytes, uploadBytes);
            boolean success = Boolean.TRUE.equals(probe.get("success"));
            finishRun(runId, proxy, node, success ? "success" : "failed", probe, startedAt);
            return success ? R.ok(resultWithHistory(runId, proxyId, probe)) : R.err(String.valueOf(probe.getOrDefault("error", "协议探测失败")));
        } catch (Exception e) {
            String error = concise(e.getMessage());
            finishRun(runId, proxy, node, "failed", Map.of("error", error), startedAt);
            return R.err(error);
        } finally {
            if ("vless_reality".equals(proxy.getProxyType())) {
                GostUtil.DeleteRealityRuntime(node.getId(), runtimeName);
            }
        }
    }

    private Map<String, Object> runProbe(PrivateProxy proxy, Node node, JSONObject config, String runtimeName,
                                         String downloadUrl, String uploadUrl, long downloadBytes, long uploadBytes) {
        String proxyType = proxy.getProxyType();
        String probeType = proxyType;
        String proxyHost = localHost(proxy);
        int proxyPort = proxy.getListenPort();
        String username = config.getString("username");
        String password = config.getString("password");
        if ("vless_reality".equals(proxyType)) {
            GostDto runtime = GostUtil.AddNodeRealityClientRuntime(node.getId(), runtimeName, proxyHost, proxyPort,
                    config.getString("clientId"), config.getString("publicKey"), config.getString("shortId"),
                    config.getString("serverName"));
            if (runtime == null || !"OK".equals(runtime.getMsg()) || runtime.getData() == null) {
                throw new IllegalStateException("创建 VLESS+REALITY 临时客户端失败：" + message(runtime));
            }
            JSONObject runtimeData = JSONObject.parseObject(JSONObject.toJSONString(runtime.getData()));
            proxyHost = "127.0.0.1";
            proxyPort = runtimeData.getInteger("port");
            probeType = "socks5";
            username = "";
            password = "";
        }
        GostDto response = WebSocketServer.send_msg(node.getId(), Map.of(
                "proxyType", probeType, "proxyHost", proxyHost, "proxyPort", proxyPort,
                "username", StringUtils.defaultString(username), "password", StringUtils.defaultString(password),
                "downloadUrl", downloadUrl, "uploadUrl", uploadUrl, "downloadBytes", downloadBytes,
                "uploadBytes", uploadBytes, "timeoutMs", 120_000), "ProtocolProbe", 135);
        if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
            throw new IllegalStateException("Agent 无响应：" + message(response));
        }
        JSONObject data = JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("success", "available", "latencyMs", "handshakeMs", "downloadBytes",
                "downloadMbps", "uploadBytes", "uploadMbps", "downloadStatus", "uploadStatus", "error")) {
            if (data.containsKey(key)) result.put(key, data.get(key));
        }
        result.put("agentVersion", node.getVersion());
        return result;
    }

    private Long insertRun(PrivateProxy proxy, Node node, String url, long downloadBytes, long uploadBytes, long startedAt) {
        jdbcTemplate.update("INSERT INTO protocol_probe_run(proxy_id,user_id,node_id,proxy_type,probe_node_id,status,available,target_url,"
                        + "download_bytes,upload_bytes,started_at,finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                proxy.getId(), proxy.getUserId(), proxy.getNodeId(), proxy.getProxyType(), node.getId(), "running", 0, url,
                downloadBytes, uploadBytes, startedAt, startedAt);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void finishRun(Long runId, PrivateProxy proxy, Node node, String status, Map<String, Object> result, long startedAt) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE protocol_probe_run SET status=?,available=?,latency_ms=?,handshake_ms=?,download_bytes_actual=?,"
                        + "download_mbps=?,upload_bytes_actual=?,upload_mbps=?,download_status=?,upload_status=?,error=?,agent_version=?,finished_at=? WHERE id=?",
                status, Boolean.TRUE.equals(result.get("success")) ? 1 : 0, decimal(result.get("latencyMs")),
                decimal(result.get("handshakeMs")), longValue(result.get("downloadBytes")), decimal(result.get("downloadMbps")),
                longValue(result.get("uploadBytes")), decimal(result.get("uploadMbps")), intValue(result.get("downloadStatus")),
                intValue(result.get("uploadStatus")), concise(result.get("error")), node.getVersion(), now, runId);
    }

    private Map<String, Object> resultWithHistory(Long runId, Long proxyId, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("runId", runId);
        response.put("proxyId", proxyId);
        return response;
    }

    private Map<Long, Map<String, Object>> latestByProxyIds(List<Long> proxyIds) {
        if (proxyIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(proxyIds.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,proxy_id AS proxyId,status,available,latency_ms AS latencyMs,handshake_ms AS handshakeMs,"
                        + "download_mbps AS downloadMbps,upload_mbps AS uploadMbps,error,started_at AS startedAt,finished_at AS finishedAt "
                        + "FROM protocol_probe_run WHERE proxy_id IN (" + placeholders + ") ORDER BY started_at DESC",
                proxyIds.toArray());
        Map<Long, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object proxyId = row.get("proxyId");
            if (proxyId instanceof Number number) {
                latest.putIfAbsent(number.longValue(), row);
            }
        }
        return latest;
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

    private PrivateProxy accessible(Long id) {
        PrivateProxy proxy = proxyMapper.selectById(id);
        if (proxy == null || "deleted".equals(proxy.getState())) return null;
        return isAdmin() || Objects.equals(proxy.getUserId(), JwtUtil.getUserIdFromToken()) ? proxy : null;
    }

    private Map<String, Object> capability(String type, Node node) {
        String label = switch (StringUtils.defaultString(type)) {
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
        if (!List.of("socks5", "http", "vless_reality").contains(type)) {
            return Map.of("status", "pending", "label", label, "message", "等待对应 Agent 客户端探针，暂不显示伪造测速结果");
        }
        if (node == null) {
            return Map.of("status", "pending", "label", label, "message", "协议节点不存在");
        }
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            return Map.of("status", "pending", "label", label,
                    "message", "节点 Agent " + StringUtils.defaultIfBlank(node.getVersion(), "未知")
                            + " 低于 " + MIN_AGENT_VERSION + "，请先升级");
        }
        return Map.of("status", "supported", "label", label, "message", "Agent 可执行协议探针");
    }

    private String localHost(PrivateProxy proxy) {
        String bind = StringUtils.trimToEmpty(proxy.getBindIp());
        return bind.isEmpty() || "0.0.0.0".equals(bind) || "::".equals(bind) ? "127.0.0.1" : bind;
    }

    private long bytes(Object raw, long fallback) {
        long value;
        try { value = raw == null ? fallback : Long.parseLong(raw.toString()); }
        catch (NumberFormatException e) { value = fallback; }
        if (value < MIN_BYTES || value > MAX_BYTES) throw new IllegalArgumentException("单次测速大小应在 1-128 MiB 之间");
        return value;
    }

    private String normalizeUrl(Object raw, String fallback) {
        String value = StringUtils.defaultIfBlank(raw == null ? null : raw.toString(), fallback).trim();
        try {
            URI uri = URI.create(value);
            if (!List.of("http", "https").contains(StringUtils.lowerCase(uri.getScheme())) || uri.getHost() == null
                    || !"speed.cloudflare.com".equalsIgnoreCase(uri.getHost())
                    || value.length() > 500) throw new IllegalArgumentException();
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException("测速地址必须是合法的 HTTP/HTTPS URL");
        }
    }

    private boolean isAdmin() { return Objects.equals(JwtUtil.getRoleIdFromToken(), 0); }
    private String message(GostDto dto) { return dto == null ? "Agent 无响应" : StringUtils.defaultIfBlank(dto.getMsg(), "Agent 执行失败"); }
    private String concise(Object value) {
        String text = StringUtils.defaultIfBlank(value == null ? null : value.toString(), "未知错误").replace('\n', ' ');
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
    private Long longValue(Object value) { return value instanceof Number ? ((Number) value).longValue() : null; }
    private Integer intValue(Object value) { return value instanceof Number ? ((Number) value).intValue() : null; }
    private Double decimal(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : null; }
}
