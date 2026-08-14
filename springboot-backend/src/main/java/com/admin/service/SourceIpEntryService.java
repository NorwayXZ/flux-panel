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
    public static final String MIN_AGENT_VERSION = "2.42.3";

    private static final List<String> CARRIERS = List.of("default", "telecom", "unicom", "mobile", "custom");
    private static final List<String> SYSTEM_CARRIERS = List.of("telecom", "unicom", "mobile");
    private static final List<String> RULE_TYPES = List.of("default", "carrier", "cidr", "asn", "region", "vip", "customer", "gray", "risk");
    private static final List<String> QUALITY_POLICIES = List.of("static", "quality_aware", "prefer_primary", "quarantine");
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
    private final SchedulingConflictService schedulingConflictService;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public SourceIpEntryService(JdbcTemplate jdbcTemplate, RestTemplate restTemplate,
                                SchedulingConflictService schedulingConflictService) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.schedulingConflictService = schedulingConflictService;
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
        result.put("ruleTypes", ruleTypeOptions());
        result.put("qualityPolicies", qualityPolicyOptions());
        result.put("capabilities", List.of(
                Map.of("key", "carrier", "name", "运营商分流", "detail", "电信、联通、移动使用内置运营商 CIDR 库自动展开"),
                Map.of("key", "geo-asn", "name", "地区 / ASN / 客户规则", "detail", "面板按你维护的 CIDR 规则编排，Agent 按最长前缀实时命中"),
                Map.of("key", "debug", "name", "来源 IP 调试", "detail", "输入来源 IP 即可看到命中的规则、后端入口和回退原因"),
                Map.of("key", "quality", "name", "质量策略标记", "detail", "来源 IP 决定第一跳，链路质量容灾建议放在后端入口或入口容灾层处理")));
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

    public R debug(Map<String, Object> body) {
        String sourceIp = StringUtils.trimToEmpty(Objects.toString(body == null ? "" : body.get("sourceIp"), ""));
        if (!isIp(sourceIp)) return R.err("请输入有效的来源 IP");
        Long groupId = body == null || body.get("groupId") == null || StringUtils.isBlank(Objects.toString(body.get("groupId"), ""))
                ? null : numberObject(body.get("groupId"));
        List<Map<String, Object>> groups = groupId == null
                ? jdbcTemplate.queryForList("SELECT * FROM source_ip_entry_group WHERE state<>'deleted' ORDER BY enabled DESC,created_time DESC")
                : jdbcTemplate.queryForList("SELECT * FROM source_ip_entry_group WHERE id=? AND state<>'deleted'", groupId);
        if (groups.isEmpty()) return R.err("没有可调试的来源 IP 分流组");

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            List<Map<String, Object>> routes = loadRoutes(number(group.get("id")), true);
            results.add(explainMatch(sourceIp, group, routes));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceIp", sourceIp);
        result.put("ipVersion", sourceIp.contains(":") ? "IPv6" : "IPv4");
        result.put("inferredCarrier", inferCarrier(sourceIp));
        result.put("groups", results);
        return R.ok(result);
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
        schedulingConflictService.assertForwardSetAvailable("source_ip_entry", dto.getId(),
                normalized.routes.stream().map(route -> route.backendForwardId).collect(Collectors.toList()));
        schedulingConflictService.assertForwardBackedTunnelSetAvailable("source_ip_entry", dto.getId(),
                normalized.routes.stream().map(route -> route.backendForwardId).collect(Collectors.toList()));

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
                            + "(group_id,carrier,rule_type,rule_name,priority,backend_forward_id,cidrs,region,asn,tags,quality_policy,notes,enabled,created_time,updated_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, route.carrier, route.ruleType, route.ruleName, route.priority, route.backendForwardId, route.cidrs,
                    route.region, route.asn, route.tags, route.qualityPolicy, route.notes, route.enabled, now, now);
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
                "SELECT id,carrier,rule_type AS ruleType,cidrs,backend_forward_id AS backendForwardId FROM source_ip_entry_route WHERE group_id=?", id);
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
        Set<String> systemCarrierRoutes = new LinkedHashSet<>();
        List<NormalizedRoute> routes = new ArrayList<>();
        int defaultCount = 0;
        for (SourceIpEntrySaveDto.Route route : dto.getRoutes()) {
            if (route == null) continue;
            String carrier = StringUtils.lowerCase(StringUtils.trimToEmpty(route.getCarrier()));
            String ruleType = normalizeRuleType(route.getRuleType(), carrier);
            if ("default".equals(ruleType)) carrier = "default";
            if ("carrier".equals(ruleType) && !SYSTEM_CARRIERS.contains(carrier)) {
                throw new IllegalArgumentException("运营商规则只能选择电信、联通或移动");
            }
            if (!CARRIERS.contains(carrier)) carrier = "custom";
            if ("carrier".equals(ruleType) && !systemCarrierRoutes.add(carrier)) {
                throw new IllegalArgumentException("系统运营商规则不能重复：" + carrierLabel(carrier));
            }
            if (route.getBackendForwardId() == null) throw new IllegalArgumentException(carrier + " 未选择后端入口转发");
            Map<String, Object> backend = backendForward(route.getBackendForwardId());
            if (backend == null) throw new IllegalArgumentException("后端入口转发不存在或已停用：" + route.getBackendForwardId());
            String mode = StringUtils.defaultIfBlank(Objects.toString(backend.get("protocolMode"), "tcp"), "tcp").toLowerCase(Locale.ROOT);
            if ("udp".equals(mode)) throw new IllegalArgumentException("来源 IP 分流只支持 TCP，后端转发“" + backend.get("name") + "”仅支持 UDP");

            String cidrs = StringUtils.trimToEmpty(route.getCidrs());
            if ("default".equals(ruleType)) {
                defaultCount++;
                if (Boolean.FALSE.equals(route.getEnabled())) {
                    throw new IllegalArgumentException("默认回退线路不能停用");
                }
                carrier = "default";
                cidrs = "";
            } else if ("carrier".equals(ruleType) && cidrs.isBlank()) {
                cidrs = carrierCidrs(carrier);
                if (cidrs.isBlank()) throw new IllegalArgumentException(carrierLabel(carrier) + " IP 库尚未同步，请先刷新运营商 IP 库或手动填写 CIDR");
            } else if (cidrs.isBlank()) {
                throw new IllegalArgumentException(ruleTypeLabel(ruleType) + "规则必须填写 CIDR，例如 203.0.113.0/24");
            } else {
                cidrs = normalizeCidrs(cidrs);
            }
            routes.add(new NormalizedRoute(carrier, ruleType, defaultRuleName(route, carrier, ruleType),
                    clamp(numberObject(route.getPriority()), 1, 999), route.getBackendForwardId(), cidrs,
                    trim(route.getRegion(), 100), normalizeAsn(route.getAsn()), trim(route.getTags(), 255),
                    normalizeQualityPolicy(route.getQualityPolicy(), ruleType), trim(route.getNotes(), 500),
                    !Boolean.FALSE.equals(route.getEnabled())));
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
        return loadRoutes(groupId, false);
    }

    private List<Map<String, Object>> loadRoutes(long groupId, boolean includeManagedCidrs) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.id,r.carrier,r.rule_type AS ruleType,r.rule_name AS ruleName,r.priority,r.cidrs,"
                        + "r.region,r.asn,r.tags,r.quality_policy AS qualityPolicy,r.notes,r.enabled,r.backend_forward_id AS backendForwardId,"
                        + "f.name AS backendForwardName,f.in_port AS backendPort,f.protocol_mode AS protocolMode,"
                        + "t.in_node_id AS backendNodeId,COALESCE(NULLIF(n.server_ip,''),NULLIF(n.ip,''),t.in_ip) AS backendHost,"
                        + "n.name AS backendNodeName "
                        + "FROM source_ip_entry_route r LEFT JOIN forward f ON f.id=r.backend_forward_id "
                        + "LEFT JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id "
                        + "WHERE r.group_id=? ORDER BY CASE r.carrier WHEN 'default' THEN 0 WHEN 'telecom' THEN 1 WHEN 'unicom' THEN 2 WHEN 'mobile' THEN 3 ELSE 4 END,r.priority,r.id",
                groupId);
        for (Map<String, Object> row : rows) {
            String cidrs = Objects.toString(row.get("cidrs"), "");
            row.put("cidrCount", splitCidrs(cidrs).size());
            row.put("chainName", chainName(groupId, number(row.get("id"))));
            row.put("ruleTypeLabel", ruleTypeLabel(Objects.toString(row.get("ruleType"), "")));
            row.put("qualityPolicyLabel", qualityPolicyLabel(Objects.toString(row.get("qualityPolicy"), "")));
            if (!includeManagedCidrs && "carrier".equals(row.get("ruleType"))) row.remove("cidrs");
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

    private Map<String, Object> explainMatch(String sourceIp, Map<String, Object> group, List<Map<String, Object>> routes) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> defaultRoute = null;
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> route : routes) {
            if ("default".equals(route.get("carrier"))) defaultRoute = route;
            if (!truth(route.get("enabled")) || "default".equals(route.get("carrier"))) continue;
            Match match = bestMatch(sourceIp, Objects.toString(route.get("cidrs"), ""));
            if (match == null) continue;
            Map<String, Object> candidate = routeSummary(route);
            candidate.put("matchedCidr", match.cidr);
            candidate.put("prefixLength", match.prefixLength);
            candidates.add(candidate);
        }
        candidates.sort((left, right) -> {
            int prefix = Long.compare(number(right.get("prefixLength")), number(left.get("prefixLength")));
            if (prefix != 0) return prefix;
            int priority = Long.compare(number(left.get("priority")), number(right.get("priority")));
            if (priority != 0) return priority;
            return Long.compare(number(left.get("id")), number(right.get("id")));
        });
        Map<String, Object> selected = candidates.isEmpty() ? routeSummary(defaultRoute) : candidates.get(0);
        result.put("groupId", number(group.get("id")));
        result.put("groupName", Objects.toString(group.get("name"), ""));
        result.put("enabled", truth(group.get("enabled")));
        result.put("state", Objects.toString(group.get("state"), ""));
        result.put("listener", (StringUtils.defaultIfBlank(Objects.toString(group.get("listen_host"), ""), "全部地址"))
                + ":" + group.get("listen_port"));
        result.put("matched", !candidates.isEmpty());
        result.put("selectedRoute", selected);
        result.put("defaultRoute", routeSummary(defaultRoute));
        result.put("candidates", candidates);
        result.put("reason", candidates.isEmpty()
                ? "没有 CIDR 命中，运行时会走默认回退线路"
                : "命中 " + candidates.get(0).get("matchedCidr") + "，按最长前缀优先选择该规则");
        if (!truth(group.get("enabled"))) result.put("warning", "该分流组已停用，调试结果只代表保存的规则，不代表当前运行时");
        return result;
    }

    private Map<String, Object> routeSummary(Map<String, Object> route) {
        if (route == null) return null;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", number(route.get("id")));
        summary.put("carrier", Objects.toString(route.get("carrier"), ""));
        summary.put("carrierLabel", carrierLabel(Objects.toString(route.get("carrier"), "")));
        summary.put("ruleType", Objects.toString(route.get("ruleType"), ""));
        summary.put("ruleTypeLabel", ruleTypeLabel(Objects.toString(route.get("ruleType"), "")));
        summary.put("ruleName", StringUtils.defaultIfBlank(Objects.toString(route.get("ruleName"), ""), carrierLabel(Objects.toString(route.get("carrier"), ""))));
        summary.put("priority", number(route.get("priority")));
        summary.put("backendForwardId", number(route.get("backendForwardId")));
        summary.put("backendForwardName", Objects.toString(route.get("backendForwardName"), ""));
        summary.put("backendNodeName", Objects.toString(route.get("backendNodeName"), ""));
        summary.put("backendHost", Objects.toString(route.get("backendHost"), ""));
        summary.put("backendPort", number(route.get("backendPort")));
        summary.put("region", Objects.toString(route.get("region"), ""));
        summary.put("asn", Objects.toString(route.get("asn"), ""));
        summary.put("qualityPolicy", Objects.toString(route.get("qualityPolicy"), ""));
        summary.put("qualityPolicyLabel", qualityPolicyLabel(Objects.toString(route.get("qualityPolicy"), "")));
        summary.put("cidrCount", number(route.get("cidrCount")));
        return summary;
    }

    private Map<String, Object> inferCarrier(String sourceIp) {
        Map<String, Object> best = new LinkedHashMap<>();
        Match bestMatch = null;
        String bestCarrier = "";
        for (String carrier : SYSTEM_CARRIERS) {
            Match match = bestMatch(sourceIp, carrierCidrs(carrier));
            if (match == null) continue;
            if (bestMatch == null || match.prefixLength > bestMatch.prefixLength) {
                bestMatch = match;
                bestCarrier = carrier;
            }
        }
        best.put("carrier", StringUtils.defaultIfBlank(bestCarrier, "unknown"));
        best.put("label", bestCarrier.isEmpty() ? "未知/未命中运营商库" : carrierLabel(bestCarrier));
        if (bestMatch != null) {
            best.put("matchedCidr", bestMatch.cidr);
            best.put("prefixLength", bestMatch.prefixLength);
        }
        return best;
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

    private Match bestMatch(String sourceIp, String cidrs) {
        if (StringUtils.isBlank(sourceIp) || StringUtils.isBlank(cidrs)) return null;
        Match best = null;
        for (String cidr : splitCidrs(cidrs)) {
            Match match = matchCidr(sourceIp, cidr);
            if (match == null) continue;
            if (best == null || match.prefixLength > best.prefixLength) best = match;
        }
        return best;
    }

    private Match matchCidr(String sourceIp, String cidr) {
        if (!isCidr(cidr)) return null;
        try {
            String[] parts = cidr.split("/", 2);
            InetAddress source = InetAddress.getByName(stripIpLiteral(sourceIp));
            InetAddress network = InetAddress.getByName(parts[0]);
            byte[] sourceBytes = source.getAddress();
            byte[] networkBytes = network.getAddress();
            if (sourceBytes.length != networkBytes.length) return null;
            int prefix = Integer.parseInt(parts[1]);
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (sourceBytes[index] != networkBytes[index]) return null;
            }
            if (remainingBits > 0) {
                int mask = 0xff << (8 - remainingBits);
                if ((sourceBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) return null;
            }
            return new Match(cidr, prefix);
        } catch (Exception e) {
            return null;
        }
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
            String normalized = stripIpLiteral(value);
            if (!normalized.matches("[0-9a-fA-F:.]+")) return false;
            InetAddress.getByName(normalized);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String stripIpLiteral(String value) {
        String normalized = StringUtils.trimToEmpty(value);
        if (normalized.startsWith("[") && normalized.contains("]")) {
            normalized = normalized.substring(1, normalized.indexOf(']'));
        }
        int zoneIndex = normalized.indexOf('%');
        if (zoneIndex > 0) normalized = normalized.substring(0, zoneIndex);
        return normalized;
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

    private String normalizeRuleType(String requested, String carrier) {
        String value = StringUtils.lowerCase(StringUtils.trimToEmpty(requested));
        if (value.isEmpty()) {
            value = "default".equals(carrier) ? "default"
                    : SYSTEM_CARRIERS.contains(carrier) ? "carrier" : "cidr";
        }
        if (!RULE_TYPES.contains(value)) throw new IllegalArgumentException("不支持的规则类型：" + value);
        return value;
    }

    private String normalizeQualityPolicy(String requested, String ruleType) {
        String value = StringUtils.lowerCase(StringUtils.defaultIfBlank(requested, "static").trim());
        if (!QUALITY_POLICIES.contains(value)) value = "static";
        if ("risk".equals(ruleType) && "static".equals(value)) return "quarantine";
        return value;
    }

    private String normalizeAsn(String value) {
        String text = trim(value, 64);
        if (StringUtils.isBlank(text)) return "";
        text = text.toUpperCase(Locale.ROOT).replace("ASN", "AS");
        if (text.matches("\\d+")) text = "AS" + text;
        return text;
    }

    private String defaultRuleName(SourceIpEntrySaveDto.Route route, String carrier, String ruleType) {
        String requested = trim(route.getRuleName(), 100);
        if (StringUtils.isNotBlank(requested)) return requested;
        if ("carrier".equals(ruleType) || "default".equals(ruleType)) return carrierLabel(carrier);
        return ruleTypeLabel(ruleType);
    }

    private int clamp(Long value, int min, int max) {
        long raw = value == null ? 100 : value;
        return (int) Math.max(min, Math.min(max, raw));
    }

    private String trim(String value, int max) {
        String text = StringUtils.trimToEmpty(value);
        return text.length() > max ? text.substring(0, max) : text;
    }

    private List<Map<String, String>> ruleTypeOptions() {
        return List.of(
                Map.of("key", "default", "label", "默认回退", "description", "没有命中任何规则时使用"),
                Map.of("key", "carrier", "label", "运营商", "description", "使用电信/联通/移动 CIDR 库"),
                Map.of("key", "cidr", "label", "CIDR/IP 段", "description", "手动维护 IP 或网段"),
                Map.of("key", "asn", "label", "ASN", "description", "把某个 ASN 的 CIDR 粘贴进规则"),
                Map.of("key", "region", "label", "地区", "description", "把某个地区的 CIDR 归入同一线路"),
                Map.of("key", "vip", "label", "VIP 来源", "description", "VIP/精品客户来源 IP 走专线"),
                Map.of("key", "customer", "label", "专属客户", "description", "家庭/公司/客户固定出口 IP 绑定线路"),
                Map.of("key", "gray", "label", "灰度测试", "description", "指定来源先试新入口或新协议"),
                Map.of("key", "risk", "label", "风险隔离", "description", "异常来源导向隔离线路或低优线路"));
    }

    private List<Map<String, String>> qualityPolicyOptions() {
        return List.of(
                Map.of("key", "static", "label", "固定规则", "description", "只按来源 IP 命中，不额外改线路"),
                Map.of("key", "quality_aware", "label", "质量联动", "description", "建议后端选择入口容灾/质量调度托管的线路"),
                Map.of("key", "prefer_primary", "label", "主线优先", "description", "来源规则命中后仍以主线路稳定性为第一优先"),
                Map.of("key", "quarantine", "label", "隔离/降级", "description", "风险来源走隔离、低优或限速后端"));
    }

    private String ruleTypeLabel(String ruleType) {
        return switch (ruleType) {
            case "carrier" -> "运营商";
            case "cidr" -> "CIDR/IP 段";
            case "asn" -> "ASN";
            case "region" -> "地区";
            case "vip" -> "VIP 来源";
            case "customer" -> "专属客户";
            case "gray" -> "灰度测试";
            case "risk" -> "风险隔离";
            default -> "默认回退";
        };
    }

    private String qualityPolicyLabel(String policy) {
        return switch (policy) {
            case "quality_aware" -> "质量联动";
            case "prefer_primary" -> "主线优先";
            case "quarantine" -> "隔离/降级";
            default -> "固定规则";
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

    private record NormalizedRoute(String carrier, String ruleType, String ruleName, int priority,
                                   Long backendForwardId, String cidrs, String region, String asn,
                                   String tags, String qualityPolicy, String notes, boolean enabled) {
    }

    private record Match(String cidr, int prefixLength) {
    }
}
