package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.SourceIpEntrySaveDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.TunnelRouteUtil;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Direct source-IP routing for a single public TCP listener.
 *
 * <p>The listener is deliberately independent from DNS. The Agent sees the
 * accepted socket's real source address, chooses a routing chain, and forwards
 * the original bytes to an existing backend forward. This is useful for
 * VLESS/Reality and other protocols that must not be terminated at the
 * dispatch layer.</p>
 */
@Slf4j
@Service
public class SourceIpEntryService {
    public static final String MIN_AGENT_VERSION = "2.42.2";

    private static final List<String> CARRIERS = List.of("default", "telecom", "unicom", "mobile", "custom");
    private static final Map<String, List<String>> CARRIER_URLS = Map.of(
            "telecom", List.of(
                    "https://gaoyifan.github.io/china-operator-ip/chinanet.txt",
                    "https://gaoyifan.github.io/china-operator-ip/chinanet6.txt"),
            "unicom", List.of(
                    "https://gaoyifan.github.io/china-operator-ip/unicom.txt",
                    "https://gaoyifan.github.io/china-operator-ip/unicom6.txt"),
            "mobile", List.of(
                    "https://gaoyifan.github.io/china-operator-ip/cmcc.txt",
                    "https://gaoyifan.github.io/china-operator-ip/cmcc6.txt"));
    private static final int MAX_CIDRS_PER_ROUTE = 20_000;

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public SourceIpEntryService(JdbcTemplate jdbcTemplate, RestTemplate restTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    public R overview() {
        List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                "SELECT g.id,g.name,g.ingress_node_id AS ingressNodeId,n.name AS ingressNodeName,"
                        + "g.listen_host AS listenHost,g.listen_port AS listenPort,g.default_route_id AS defaultRouteId,"
                        + "g.enabled,g.state,g.last_error AS lastError,g.last_synced_at AS lastSyncedAt,"
                        + "g.created_time AS createdTime,g.updated_time AS updatedTime,n.version AS agentVersion "
                        + "FROM source_ip_entry_group g LEFT JOIN node n ON n.id=g.ingress_node_id "
                        + "WHERE g.state<>'deleted' ORDER BY g.created_time DESC");
        for (Map<String, Object> group : groups) {
            long id = number(group.get("id"));
            group.put("routes", loadRoutes(id));
            group.put("serviceName", serviceName(id));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        result.put("ingressNodes", loadIngressNodes());
        result.put("backendForwards", loadBackendForwards());
        result.put("carriers", loadCarrierDatabase());
        result.put("minimumAgentVersion", MIN_AGENT_VERSION);
        result.put("summary", Map.of(
                "total", groups.size(),
                "enabled", groups.stream().filter(item -> truth(item.get("enabled"))).count(),
                "healthy", groups.stream().filter(item -> "active".equals(item.get("state"))).count(),
                "errors", groups.stream().filter(item -> "error".equals(item.get("state"))).count()));
        return R.ok(result);
    }

    @Transactional
    public R save(SourceIpEntrySaveDto dto) {
        if (dto == null) return R.err("配置不能为空");
        Long lockId = dto.getId() == null ? 0L : dto.getId();
        Object lock = locks.computeIfAbsent(lockId, ignored -> new Object());
        synchronized (lock) {
            try {
                Normalized normalized = normalize(dto);
                return saveLocked(dto, normalized);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return R.err(e.getMessage());
            }
        }
    }

    public R delete(Long id) {
        if (id == null) return R.err("来源 IP 分流不存在");
        Object lock = locks.computeIfAbsent(id, ignored -> new Object());
        synchronized (lock) {
            Map<String, Object> group = one("SELECT * FROM source_ip_entry_group WHERE id=? AND state<>'deleted'", id);
            if (group == null) return R.err("来源 IP 分流不存在");
            List<Map<String, Object>> routes = loadRoutes(id);
            cleanupAgent(group, routes);
            long now = System.currentTimeMillis();
            jdbcTemplate.update("UPDATE source_ip_entry_group SET state='deleted',enabled=0,last_error=NULL,updated_time=? WHERE id=?", now, id);
            jdbcTemplate.update("DELETE FROM source_ip_entry_route WHERE group_id=?", id);
            return R.ok();
        }
    }

    /** Re-applies the persisted configuration and is useful after a manual Agent restart. */
    public R check(Long id) {
        if (id == null) return R.err("来源 IP 分流不存在");
        Object lock = locks.computeIfAbsent(id, ignored -> new Object());
        synchronized (lock) {
            Map<String, Object> group = one("SELECT * FROM source_ip_entry_group WHERE id=? AND state<>'deleted'", id);
            if (group == null) return R.err("来源 IP 分流不存在");
            List<Map<String, Object>> routes = loadRoutes(id);
            try {
                syncAgent(group, routes, routes);
                markState(id, truth(group.get("enabled")) ? "active" : "disabled", null);
                return R.ok(Map.of("id", id, "state", truth(group.get("enabled")) ? "active" : "disabled"));
            } catch (IllegalStateException e) {
                markState(id, "error", shorten(e.getMessage()));
                return R.err(e.getMessage());
            }
        }
    }

    public R refreshCarriers() {
        try {
            int updated = refreshCarrierDatabase();
            return R.ok(Map.of("updated", updated, "carriers", loadCarrierDatabase()));
        } catch (IllegalStateException e) {
            return R.err(e.getMessage());
        }
    }

    private R saveLocked(SourceIpEntrySaveDto dto, Normalized normalized) {
        long now = System.currentTimeMillis();
        Map<String, Object> oldGroup = dto.getId() == null ? null
                : one("SELECT * FROM source_ip_entry_group WHERE id=? AND state<>'deleted'", dto.getId());
        if (dto.getId() != null && oldGroup == null) return R.err("来源 IP 分流不存在");
        List<Map<String, Object>> oldRoutes = oldGroup == null ? List.of() : oldGroupRoutes(oldGroup);

        Integer duplicate = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_ip_entry_group WHERE ingress_node_id=? AND listen_port=? AND state<>'deleted' AND (? IS NULL OR id<>?)",
                Integer.class, normalized.ingressNodeId, normalized.listenPort, dto.getId(), dto.getId());
        if (duplicate != null && duplicate > 0) return R.err("统一入口节点的该 TCP 端口已被另一个来源 IP 分流占用");

        boolean listenerChanged = oldGroup == null
                || number(oldGroup.get("ingress_node_id")) != normalized.ingressNodeId
                || number(oldGroup.get("listen_port")) != normalized.listenPort;
        if (listenerChanged) {
            Integer forwardConflict = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM forward f JOIN tunnel t ON t.id=f.tunnel_id "
                            + "WHERE t.in_node_id=? AND f.in_port=? AND f.status=1",
                    Integer.class, normalized.ingressNodeId, normalized.listenPort);
            if (forwardConflict != null && forwardConflict > 0) {
                return R.err("统一入口端口已被转发占用，请更换端口");
            }
            Map<String, Object> node = one("SELECT id,version,status FROM node WHERE id=?", normalized.ingressNodeId);
            if (node == null || !truth(node.get("status"))) return R.err("统一入口节点不存在或已离线");
            if (!AgentVersionUtil.isAtLeast(Objects.toString(node.get("version"), ""), MIN_AGENT_VERSION)) {
                return R.err("统一入口 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
            }
            AgentPortCheckUtil.Result portCheck = AgentPortCheckUtil.check(
                    nodeEntity(normalized.ingressNodeId, node),
                    List.of(new AgentPortCheckUtil.Check("tcp", normalized.listenHost, normalized.listenPort)));
            if (!portCheck.isChecked()) return R.err("无法完成统一入口端口检查：Agent 不在线或未响应");
            if (!portCheck.isAvailable()) return R.err(portCheck.getMessage() + conflictSuffix(portCheck));
        }

        Long id = dto.getId();
        if (id == null) {
            jdbcTemplate.update("INSERT INTO source_ip_entry_group "
                            + "(user_id,name,ingress_node_id,listen_host,listen_port,enabled,state,created_time,updated_time) "
                            + "VALUES (?,?,?,?,?,?, 'provisioning',?,?)",
                    JwtUtil.getUserIdFromToken(), normalized.name, normalized.ingressNodeId, normalized.listenHost,
                    normalized.listenPort, normalized.enabled, now, now);
            id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            jdbcTemplate.update("UPDATE source_ip_entry_group SET name=?,ingress_node_id=?,listen_host=?,listen_port=?,"
                            + "enabled=?,state='provisioning',last_error=NULL,updated_time=? WHERE id=?",
                    normalized.name, normalized.ingressNodeId, normalized.listenHost, normalized.listenPort,
                    normalized.enabled, now, id);
            jdbcTemplate.update("DELETE FROM source_ip_entry_route WHERE group_id=?", id);
        }

        for (NormalizedRoute route : normalized.routes) {
            jdbcTemplate.update("INSERT INTO source_ip_entry_route "
                            + "(group_id,carrier,backend_forward_id,cidrs,enabled,created_time,updated_time) VALUES (?,?,?,?,?,?,?)",
                    id, route.carrier, route.backendForwardId, route.cidrs, route.enabled, now, now);
        }
        Long defaultRouteId = jdbcTemplate.queryForObject(
                "SELECT id FROM source_ip_entry_route WHERE group_id=? AND carrier='default' ORDER BY id DESC LIMIT 1", Long.class, id);
        jdbcTemplate.update("UPDATE source_ip_entry_group SET default_route_id=?,updated_time=? WHERE id=?", defaultRouteId, now, id);

        Map<String, Object> newGroup = one("SELECT * FROM source_ip_entry_group WHERE id=?", id);
        List<Map<String, Object>> newRoutes = loadRoutes(id);
        try {
            syncAgent(newGroup, oldRoutes, newRoutes);
            if (oldGroup != null && number(oldGroup.get("ingress_node_id")) != normalized.ingressNodeId) {
                cleanupAgent(oldGroup, oldRoutes);
            }
            markState(id, normalized.enabled ? "active" : "disabled", null);
            return R.ok(Map.of("id", id, "state", normalized.enabled ? "active" : "disabled"));
        } catch (IllegalStateException e) {
            markState(id, "error", shorten(e.getMessage()));
            return R.err("配置已保存，但 Agent 同步失败：" + e.getMessage());
        }
    }

    private List<Map<String, Object>> oldGroupRoutes(Map<String, Object> oldGroup) {
        Object id = oldGroup.get("id");
        return id == null ? List.of() : jdbcTemplate.queryForList(
                "SELECT id,carrier,cidrs,backend_forward_id AS backendForwardId FROM source_ip_entry_route WHERE group_id=?", id);
    }

    private void syncAgent(Map<String, Object> group, List<Map<String, Object>> oldRoutes,
                           List<Map<String, Object>> routes) {
        if (group == null) throw new IllegalStateException("来源 IP 分流记录不存在");
        long groupId = number(group.get("id"));
        Long nodeId = numberObject(group.get("ingress_node_id"));
        Map<String, Object> node = one("SELECT id,version,status FROM node WHERE id=?", nodeId);
        if (node == null || !truth(node.get("status"))) throw new IllegalStateException("统一入口节点已离线");
        if (!AgentVersionUtil.isAtLeast(Objects.toString(node.get("version"), ""), MIN_AGENT_VERSION)) {
            throw new IllegalStateException("统一入口 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }

        List<GostUtil.SourceIpRoute> sourceRoutes = new ArrayList<>();
        String defaultChain = null;
        Set<String> newChains = new LinkedHashSet<>();
        for (Map<String, Object> route : routes) {
            if (!truth(route.get("enabled"))) continue;
            long routeId = number(route.get("id"));
            String chainName = chainName(groupId, routeId);
            newChains.add(chainName);
            String carrier = Objects.toString(route.get("carrier"), "");
            if ("default".equals(carrier)) defaultChain = chainName;
            List<String> cidrs = splitCidrs(Objects.toString(route.get("cidrs"), ""));
            sourceRoutes.add(new GostUtil.SourceIpRoute(chainName,
                    "default".equals(carrier) ? List.of() : cidrs));

            Map<String, Object> backend = backendForward(numberObject(route.get("backendForwardId")));
            if (backend == null) throw new IllegalStateException("后端转发已不存在：" + route.get("backendForwardId"));
            String backendAddress = backendAddress(backend);
            GostDto chainResult = GostUtil.UpdateSourceIpChain(nodeId, chainName, backendAddress);
            if (!success(chainResult)) {
                chainResult = GostUtil.AddSourceIpChain(nodeId, chainName, backendAddress);
            }
            if (!success(chainResult)) {
                throw new IllegalStateException("同步后端线路失败：" + message(chainResult));
            }
        }
        if (defaultChain == null) throw new IllegalStateException("未找到默认回退线路");

        if (truth(group.get("enabled"))) {
            boolean update = !oldRoutes.isEmpty() || serviceMayExist(nodeId, serviceName(groupId));
            GostDto serviceResult = GostUtil.ConfigureSourceIpEntry(nodeId, serviceName(groupId),
                    Objects.toString(group.get("listen_host"), ""), ((Number) group.get("listen_port")).intValue(),
                    sourceRoutes, defaultChain, update);
            if (!success(serviceResult)) {
                serviceResult = GostUtil.ConfigureSourceIpEntry(nodeId, serviceName(groupId),
                        Objects.toString(group.get("listen_host"), ""), ((Number) group.get("listen_port")).intValue(),
                        sourceRoutes, defaultChain, !update);
            }
            if (!success(serviceResult)) throw new IllegalStateException("同步统一入口服务失败：" + message(serviceResult));
        } else {
            deleteAgentService(nodeId, serviceName(groupId));
        }

        for (Map<String, Object> oldRoute : oldRoutes) {
            String oldChain = chainName(groupId, number(oldRoute.get("id")));
            if (!newChains.contains(oldChain)) deleteAgentChain(nodeId, oldChain);
        }
        markState(groupId, truth(group.get("enabled")) ? "active" : "disabled", null);
    }

    private boolean serviceMayExist(Long nodeId, String serviceName) {
        // The Agent API intentionally has no cheap existence probe. Returning
        // true makes updates recreate the service safely; the Agent handles a
        // missing service through the add fallback above.
        return nodeId != null && StringUtils.isNotBlank(serviceName);
    }

    private void cleanupAgent(Map<String, Object> group, List<Map<String, Object>> routes) {
        Long nodeId = numberObject(group.get("ingress_node_id"));
        if (nodeId == null) return;
        deleteAgentService(nodeId, serviceName(number(group.get("id"))));
        for (Map<String, Object> route : routes) {
            deleteAgentChain(nodeId, chainName(number(group.get("id")), number(route.get("id"))));
        }
    }

    private void deleteAgentService(Long nodeId, String name) {
        GostDto result = GostUtil.DeleteSourceIpEntry(nodeId, name);
        if (!success(result) && !message(result).toLowerCase(Locale.ROOT).contains("not found")) {
            log.warn("删除来源 IP 入口服务 {} 失败：{}", name, message(result));
        }
    }

    private void deleteAgentChain(Long nodeId, String name) {
        GostDto result = GostUtil.DeleteSourceIpChain(nodeId, name);
        if (!success(result) && !message(result).toLowerCase(Locale.ROOT).contains("not found")) {
            log.warn("删除来源 IP 分流链 {} 失败：{}", name, message(result));
        }
    }

    private Normalized normalize(SourceIpEntrySaveDto dto) {
        String name = StringUtils.trimToEmpty(dto.getName());
        if (name.isEmpty()) throw new IllegalArgumentException("请输入来源 IP 分流名称");
        if (dto.getIngressNodeId() == null) throw new IllegalArgumentException("请选择统一入口节点");
        if (dto.getListenPort() == null || dto.getListenPort() < 1 || dto.getListenPort() > 65535) {
            throw new IllegalArgumentException("监听端口必须在 1-65535 之间");
        }
        String listenHost = StringUtils.trimToEmpty(dto.getListenHost());
        if (listenHost.startsWith("[") && listenHost.endsWith("]")) listenHost = listenHost.substring(1, listenHost.length() - 1);
        if (listenHost.contains(":") && !isIp(listenHost)) throw new IllegalArgumentException("监听地址必须是本机 IP 或留空");

        if (dto.getRoutes() == null || dto.getRoutes().isEmpty()) throw new IllegalArgumentException("至少配置一个默认回退线路");
        Set<String> carriers = new LinkedHashSet<>();
        List<NormalizedRoute> routes = new ArrayList<>();
        int defaultCount = 0;
        for (SourceIpEntrySaveDto.Route route : dto.getRoutes()) {
            if (route == null) continue;
            String carrier = StringUtils.lowerCase(StringUtils.trimToEmpty(route.getCarrier()));
            if (!CARRIERS.contains(carrier)) throw new IllegalArgumentException("不支持的线路类型：" + carrier);
            if (!carriers.add(carrier)) throw new IllegalArgumentException("线路类型不能重复：" + carrier);
            if (route.getBackendForwardId() == null) throw new IllegalArgumentException(carrier + " 未选择后端入口转发");
            Map<String, Object> backend = backendForward(route.getBackendForwardId());
            if (backend == null) throw new IllegalArgumentException("后端入口转发不存在或已停用：" + route.getBackendForwardId());
            String mode = StringUtils.defaultIfBlank(Objects.toString(backend.get("protocolMode"), "tcp"), "tcp").toLowerCase(Locale.ROOT);
            if ("udp".equals(mode)) throw new IllegalArgumentException("来源 IP 分流只支持 TCP，后端转发“" + backend.get("name") + "”仅支持 UDP");

            String cidrs = StringUtils.trimToEmpty(route.getCidrs());
            if ("default".equals(carrier)) {
                defaultCount++;
                if (Boolean.FALSE.equals(route.getEnabled())) {
                    throw new IllegalArgumentException("默认回退线路不能停用");
                }
                cidrs = "";
            } else if (cidrs.isBlank()) {
                if (!CARRIER_URLS.containsKey(carrier)) {
                    throw new IllegalArgumentException("自定义线路必须填写 CIDR，例如 203.0.113.0/24");
                }
                cidrs = carrierCidrs(carrier);
                if (cidrs.isBlank()) throw new IllegalArgumentException(carrierLabel(carrier) + " IP 库尚未同步，请先刷新运营商 IP 库或手动填写 CIDR");
            } else {
                cidrs = normalizeCidrs(cidrs);
            }
            routes.add(new NormalizedRoute(carrier, route.getBackendForwardId(), cidrs, !Boolean.FALSE.equals(route.getEnabled())));
        }
        if (defaultCount != 1) throw new IllegalArgumentException("必须且只能配置一条 default 默认回退线路");
        if (routes.stream().noneMatch(item -> item.enabled)) throw new IllegalArgumentException("至少启用一条来源 IP 线路");
        return new Normalized(name, dto.getIngressNodeId(), listenHost, dto.getListenPort(),
                !Boolean.FALSE.equals(dto.getEnabled()), routes);
    }

    private Map<String, Object> backendForward(Long id) {
        if (id == null) return null;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT f.id,f.name,f.in_port AS inPort,f.protocol_mode AS protocolMode,f.status,"
                        + "t.in_node_id AS inNodeId,COALESCE(NULLIF(n.server_ip,''),NULLIF(n.ip,''),t.in_ip) AS entryHost,"
                        + "t.name AS tunnelName,n.status AS nodeStatus,n.name AS nodeName "
                        + "FROM forward f JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id "
                        + "WHERE f.id=? AND f.status=1", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        if (row.get("entryHost") == null || StringUtils.isBlank(Objects.toString(row.get("entryHost")))) return null;
        return row;
    }

    private List<Map<String, Object>> loadBackendForwards() {
        return jdbcTemplate.queryForList(
                "SELECT f.id,f.name,f.in_port AS inPort,f.protocol_mode AS protocolMode,"
                        + "t.in_node_id AS inNodeId,COALESCE(NULLIF(n.server_ip,''),NULLIF(n.ip,''),t.in_ip) AS entryHost,"
                        + "t.name AS tunnelName,n.name AS nodeName,n.status AS nodeStatus "
                        + "FROM forward f JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id "
                        + "WHERE f.status=1 AND COALESCE(f.protocol_mode,'tcp') IN ('tcp','tcp_udp') "
                        + "ORDER BY f.created_time DESC");
    }

    private List<Map<String, Object>> loadIngressNodes() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,name,server_ip AS serverIp,ip,version,status FROM node ORDER BY status DESC,id DESC");
        for (Map<String, Object> row : rows) {
            boolean online = truth(row.get("status"));
            boolean compatible = AgentVersionUtil.isAtLeast(Objects.toString(row.get("version"), ""), MIN_AGENT_VERSION);
            row.put("online", online);
            row.put("compatible", compatible);
            row.put("available", online && compatible);
        }
        return rows;
    }

    private List<Map<String, Object>> loadRoutes(long groupId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.id,r.carrier,r.cidrs,r.enabled,r.backend_forward_id AS backendForwardId,"
                        + "f.name AS backendForwardName,f.in_port AS backendPort,f.protocol_mode AS protocolMode,"
                        + "t.in_node_id AS backendNodeId,COALESCE(NULLIF(n.server_ip,''),NULLIF(n.ip,''),t.in_ip) AS backendHost,"
                        + "n.name AS backendNodeName "
                        + "FROM source_ip_entry_route r LEFT JOIN forward f ON f.id=r.backend_forward_id "
                        + "LEFT JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id "
                        + "WHERE r.group_id=? ORDER BY CASE r.carrier WHEN 'default' THEN 0 WHEN 'telecom' THEN 1 WHEN 'unicom' THEN 2 WHEN 'mobile' THEN 3 ELSE 4 END,r.id",
                groupId);
        for (Map<String, Object> row : rows) {
            String cidrs = Objects.toString(row.get("cidrs"), "");
            row.put("cidrCount", splitCidrs(cidrs).size());
            row.put("chainName", chainName(groupId, number(row.get("id"))));
            if (!"custom".equals(row.get("carrier"))) row.remove("cidrs");
        }
        return rows;
    }

    private List<Map<String, Object>> loadCarrierDatabase() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT carrier,state,ipv4_count AS ipv4Count,ipv6_count AS ipv6Count,cidr_count AS cidrCount,"
                        + "source_urls AS sourceUrls,updated_time AS updatedTime,last_error AS lastError "
                        + "FROM source_ip_carrier_database ORDER BY FIELD(carrier,'telecom','unicom','mobile')");
        for (Map<String, Object> row : rows) row.put("label", carrierLabel(Objects.toString(row.get("carrier"), "")));
        return rows;
    }

    @Scheduled(initialDelay = 20_000L, fixedDelay = 24L * 60L * 60L * 1000L)
    public void scheduledCarrierRefresh() {
        try {
            refreshCarrierDatabase();
        } catch (Exception e) {
            log.warn("运营商 IP 库自动刷新失败：{}", e.getMessage());
        }
    }

    private int refreshCarrierDatabase() {
        int updated = 0;
        for (Map.Entry<String, List<String>> entry : CARRIER_URLS.entrySet()) {
            String carrier = entry.getKey();
            List<String> cidrs = new ArrayList<>();
            int ipv4 = 0;
            int ipv6 = 0;
            List<String> successfulUrls = new ArrayList<>();
            String lastError = null;
            for (String url : entry.getValue()) {
                try {
                    String body = restTemplate.getForObject(url, String.class);
                    List<String> parsed = parseCidrs(body);
                    if (parsed.isEmpty()) throw new IllegalStateException("返回内容没有有效 CIDR");
                    cidrs.addAll(parsed);
                    if (parsed.stream().anyMatch(item -> item.contains(":"))) ipv6 += parsed.size();
                    else ipv4 += parsed.size();
                    successfulUrls.add(url);
                } catch (RestClientException | IllegalStateException e) {
                    lastError = e.getMessage();
                    log.warn("下载 {} IP 库失败：{}", url, e.getMessage());
                }
            }
            cidrs = new ArrayList<>(new LinkedHashSet<>(cidrs));
            if (cidrs.size() > MAX_CIDRS_PER_ROUTE) {
                lastError = "下载的 IP 库包含 " + cidrs.size() + " 个 CIDR，超过单条线路上限 " + MAX_CIDRS_PER_ROUTE;
                jdbcTemplate.update("UPDATE source_ip_carrier_database SET state='error',last_error=?,updated_time=? WHERE carrier=?",
                        shorten(lastError), System.currentTimeMillis(), carrier);
                continue;
            }
            if (cidrs.isEmpty()) {
                jdbcTemplate.update("UPDATE source_ip_carrier_database SET state='error',last_error=?,updated_time=? WHERE carrier=?",
                        shorten(lastError), System.currentTimeMillis(), carrier);
                continue;
            }
            long now = System.currentTimeMillis();
            jdbcTemplate.update("INSERT INTO source_ip_carrier_database "
                            + "(carrier,cidrs,ipv4_count,ipv6_count,cidr_count,source_urls,state,last_error,updated_time) "
                            + "VALUES (?,?,?,?,?,?, 'ready',NULL,?) ON DUPLICATE KEY UPDATE cidrs=VALUES(cidrs),ipv4_count=VALUES(ipv4_count),"
                            + "ipv6_count=VALUES(ipv6_count),cidr_count=VALUES(cidr_count),source_urls=VALUES(source_urls),state='ready',last_error=NULL,updated_time=VALUES(updated_time)",
                    carrier, String.join("\n", cidrs), ipv4, ipv6, cidrs.size(), String.join("\n", successfulUrls), now);
            updated++;
        }
        return updated;
    }

    private String carrierCidrs(String carrier) {
        Map<String, Object> row = one("SELECT cidrs FROM source_ip_carrier_database WHERE carrier=? AND state='ready'", carrier);
        return row == null ? "" : Objects.toString(row.get("cidrs"), "");
    }

    private List<String> parseCidrs(String body) {
        if (body == null) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String line : body.split("\\R")) {
            String candidate = line.trim();
            int comment = candidate.indexOf('#');
            if (comment >= 0) candidate = candidate.substring(0, comment).trim();
            if (candidate.isEmpty()) continue;
            for (String item : candidate.split("[,\\s]+")) {
                if (isCidr(item)) values.add(item);
            }
        }
        return new ArrayList<>(values);
    }

    private String normalizeCidrs(String text) {
        List<String> values = parseCidrs(text);
        if (values.isEmpty()) throw new IllegalArgumentException("CIDR 为空或格式无效，例如 203.0.113.0/24");
        if (values.size() > MAX_CIDRS_PER_ROUTE) throw new IllegalArgumentException("单条线路最多支持 " + MAX_CIDRS_PER_ROUTE + " 个 CIDR");
        return String.join("\n", values);
    }

    private boolean isCidr(String value) {
        if (StringUtils.isBlank(value) || value.indexOf('/') <= 0) return false;
        String[] parts = value.split("/", 2);
        if (parts.length != 2 || parts[1].isBlank() || parts[0].contains("[")) return false;
        try {
            InetAddress address = InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            int max = address.getAddress().length == 4 ? 32 : 128;
            return prefix >= 0 && prefix <= max;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isIp(String value) {
        try {
            InetAddress.getByName(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String backendAddress(Map<String, Object> backend) {
        return TunnelRouteUtil.hostPort(Objects.toString(backend.get("entryHost"), ""),
                ((Number) backend.get("inPort")).intValue());
    }

    private void markState(long id, String state, String error) {
        jdbcTemplate.update("UPDATE source_ip_entry_group SET state=?,last_error=?,last_synced_at=?,updated_time=? WHERE id=?",
                state, error, System.currentTimeMillis(), System.currentTimeMillis(), id);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(Objects.toString(value, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Long numberObject(Object value) {
        return value == null ? null : number(value);
    }

    private boolean truth(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue() != 0 : "true".equalsIgnoreCase(Objects.toString(value, ""));
    }

    private List<String> splitCidrs(String text) {
        if (StringUtils.isBlank(text)) return List.of();
        return Arrays.stream(text.split("\\R"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    private String serviceName(long id) {
        return "source_ip_entry_" + id + "_tcp";
    }

    private String chainName(long groupId, long routeId) {
        return "source_ip_entry_" + groupId + "_route_" + routeId;
    }

    private String carrierLabel(String carrier) {
        return switch (carrier) {
            case "telecom" -> "电信";
            case "unicom" -> "联通";
            case "mobile" -> "移动";
            case "custom" -> "自定义";
            default -> "默认回退";
        };
    }

    private String conflictSuffix(AgentPortCheckUtil.Result result) {
        return result.getConflicts() == null || result.getConflicts().isEmpty()
                ? "" : "（" + String.join("；", result.getConflicts()) + "）";
    }

    private boolean success(GostDto result) {
        return result != null && "OK".equalsIgnoreCase(StringUtils.trimToEmpty(result.getMsg()));
    }

    private String message(GostDto result) {
        return result == null ? "Agent 无响应" : StringUtils.defaultIfBlank(result.getMsg(), "Agent 返回失败");
    }

    private String shorten(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private com.admin.entity.Node nodeEntity(Long id, Map<String, Object> row) {
        com.admin.entity.Node node = new com.admin.entity.Node();
        node.setId(id);
        node.setVersion(Objects.toString(row.get("version"), ""));
        node.setStatus(truth(row.get("status")) ? 1 : 0);
        return node;
    }

    private record Normalized(String name, Long ingressNodeId, String listenHost, Integer listenPort,
                              boolean enabled, List<NormalizedRoute> routes) {
    }

    private record NormalizedRoute(String carrier, Long backendForwardId, String cidrs, boolean enabled) {
    }
}
