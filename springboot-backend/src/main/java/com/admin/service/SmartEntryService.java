package com.admin.service;

import com.admin.common.dto.SmartEntrySaveDto;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.PreparedStatement;
import java.sql.Statement;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SmartEntryService {
    private static final List<String> CARRIERS = List.of("default", "telecom", "unicom", "mobile");
    private static final int MAX_GROUPS_PER_TICK = 30;
    private static final long ACTIVITY_RESUME_AFTER_MS = 30L * 60L * 1000L;
    private static final long DNS_RETRY_INTERVAL_MS = 60_000L;
    private static final long DNS_VERIFY_INTERVAL_MS = 10L * 60L * 1000L;
    private static final long TELEMETRY_LIVE_WINDOW_MS = 30_000L;
    private static final long RECENT_ACTIVITY_WINDOW_MS = 60_000L;

    private final JdbcTemplate jdbcTemplate;
    private final DynamicDnsService dynamicDnsService;
    private final SchedulingConflictService schedulingConflictService;
    private final SmartEntryMutationLocks mutationLocks;
    private final TransactionTemplate transactions;
    private final SmartEntryDnsBindingsService dnsBindings;
    private final Object creationLock = new Object();
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> activityGroups = new ConcurrentHashMap<>();

    public SmartEntryService(JdbcTemplate jdbcTemplate, DynamicDnsService dynamicDnsService,
                             SchedulingConflictService schedulingConflictService, SmartEntryMutationLocks mutationLocks,
                             PlatformTransactionManager transactionManager, SmartEntryDnsBindingsService dnsBindings) {
        this.jdbcTemplate = jdbcTemplate;
        this.dynamicDnsService = dynamicDnsService;
        this.schedulingConflictService = schedulingConflictService;
        this.mutationLocks = mutationLocks;
        this.transactions = new TransactionTemplate(transactionManager);
        this.dnsBindings = dnsBindings;
    }

    public R overview() {
        List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                "SELECT g.id,g.name,g.provider_ref_id AS providerRefId,g.provider,p.name AS providerName,g.zone_name AS zoneName,"
                        + "g.domain,g.dns_mode AS dnsMode,g.record_type AS recordType,g.ttl,g.public_port AS publicPort,g.probe_interval_ms AS probeIntervalMs,"
                        + "g.connect_timeout_ms AS connectTimeoutMs,g.failure_threshold AS failureThreshold,g.recovery_threshold AS recoveryThreshold,"
                        + "g.recovery_stable_ms AS recoveryStableMs,g.switch_cooldown_ms AS switchCooldownMs,g.probe_mode AS probeMode,g.probe_path AS probePath,"
                        + "g.sync_requested AS syncRequested,g.enabled,g.state,g.last_error AS lastError,g.last_checked_at AS lastCheckedAt,g.created_time AS createdTime "
                        + "FROM smart_entry_group g LEFT JOIN dynamic_dns_provider p ON p.id=g.provider_ref_id ORDER BY g.created_time DESC");
        Map<Long, List<Long>> bindings = dnsBindings.bindings();
        for (Map<String, Object> group : groups) {
            long groupId = number(group.get("id"));
            group.put("dnsAgentIds", bindings.getOrDefault(groupId, List.of()));
            group.put("routes", routes(groupId));
            group.put("activities", activities(groupId));
            group.put("pendingCleanup", pendingCleanup(groupId));
            group.put("cleanupError", one("SELECT last_error AS message FROM smart_entry_dns_cleanup WHERE group_id=? "
                    + "AND last_error IS NOT NULL ORDER BY attempted_at DESC LIMIT 1", groupId));
            group.put("archivedActivities", jdbcTemplate.queryForList("SELECT node_name AS nodeName,entry_address AS entryAddress,"
                    + "total_connections AS totalConnections,in_flow AS inFlow,out_flow AS outFlow,archived_at AS archivedAt "
                    + "FROM smart_entry_activity_archive WHERE group_id=? ORDER BY id DESC LIMIT 100", groupId));
            group.put("latestEvent", one("SELECT event_type AS eventType,detail,created_time AS createdTime FROM smart_entry_event "
                    + "WHERE group_id=? AND event_type NOT IN ('new_connections','first_active','resumed') ORDER BY id DESC LIMIT 1", groupId));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", groups.size());
        summary.put("enabled", groups.stream().filter(item -> truth(item.get("enabled"))).count());
        summary.put("healthy", groups.stream().filter(item -> "healthy".equals(item.get("state"))).count());
        summary.put("degraded", groups.stream().filter(item -> Set.of("degraded", "offline", "error").contains(Objects.toString(item.get("state")))).count());
        summary.put("lineRecords", groups.stream().mapToLong(item -> ((List<?>) item.get("routes")).size()).sum());
        return R.ok(Map.of("groups", groups, "summary", summary));
    }

    public R options() {
        List<Map<String, Object>> providers = jdbcTemplate.queryForList(
                "SELECT id,name,provider FROM dynamic_dns_provider WHERE enabled=1 AND provider IN ('dnspod','aliyun') ORDER BY provider,name");
        List<Map<String, Object>> forwards = jdbcTemplate.queryForList(
                "SELECT f.id,f.name,f.in_port AS inPort,f.protocol_mode AS protocolMode,t.in_node_id AS inNodeId,"
                        + "COALESCE(n.name,CONCAT('节点',t.in_node_id)) AS nodeName,COALESCE(NULLIF(n.server_ip,''),n.ip,t.in_ip) AS entryHost,"
                        + "t.name AS tunnelName FROM forward f JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id "
                        + "WHERE (f.status=1 OR f.id IN (SELECT forward_id FROM smart_entry_route)) AND COALESCE(f.protocol_mode,'tcp') IN ('tcp','tcp_udp') ORDER BY f.created_time DESC");
        return R.ok(Map.of("providers", providers, "forwards", forwards, "dnsAgents", dnsBindings.agents()));
    }

    public R domains(Long providerRefId) {
        return dynamicDnsService.lineRoutingDomains(providerRefId);
    }

    public R save(SmartEntrySaveDto dto) {
        try {
            return mutationLocks.withLock(() -> {
                Object lock = dto.getId() == null ? creationLock : locks.computeIfAbsent(dto.getId(), ignored -> new Object());
                synchronized (lock) {
                    Map<String, Object> existing = dto.getId() == null ? null : one("SELECT * FROM smart_entry_group WHERE id=?", dto.getId());
                    String mode = StringUtils.defaultIfBlank(dto.getDnsMode(), existing == null ? "public" : Objects.toString(existing.get("dns_mode"), "public"));
                    if (!List.of("local", "public").contains(mode)) throw new IllegalArgumentException("选路方式无效");
                    dto.setDnsMode(mode);
                    if ("local".equals(mode)) {
                        List<Long> selected = dto.getDnsAgentIds() == null && dto.getId() != null
                                ? dnsBindings.bindings().getOrDefault(dto.getId(), List.of()) : dto.getDnsAgentIds();
                        if (selected == null || selected.isEmpty()) throw new IllegalArgumentException("请选择至少一个 OpenWrt Agent");
                        dto.setDnsAgentIds(selected);
                    }
                    Normalized normalized = normalize(dto);
                    Long id = transactions.execute(status -> saveConfiguration(dto, normalized));
                    activityGroups.clear();
                    return R.ok(Map.of("id", id, "state", "pending",
                            "message", "local".equals(mode) ? "配置与 Agent 绑定已保存，后台将自动同步" : "配置已保存，后台正在检测入口并同步 DNS"));
                }
            });
        } catch (RuntimeException e) {
            log.warn("Smart entry save failed: {}", e.getMessage());
            return R.err(shorten(e.getMessage()));
        }
    }

    private Long saveConfiguration(SmartEntrySaveDto dto, Normalized normalized) {
        long now = System.currentTimeMillis();
        Long id = dto.getId();
        List<Map<String, Object>> oldRoutes = id == null ? List.of() : routes(id);
        Map<String, Object> oldGroup = id == null ? null : one("SELECT * FROM smart_entry_group WHERE id=?", id);
        if (id != null && oldGroup == null) throw new IllegalArgumentException("三网优化策略不存在");
        if (oldGroup != null && "deleting".equals(oldGroup.get("state")))
            throw new IllegalStateException("策略正在删除，请等待 DNS 清理完成");
        if (id != null && pendingCleanup(id) > 0)
            throw new IllegalStateException("旧 DNS 记录正在清理，请等待完成后再编辑");
        boolean local = "local".equals(dto.getDnsMode());
        boolean wasLocal = oldGroup != null && "local".equals(oldGroup.get("dns_mode"));
        if (wasLocal && !local) throw new IllegalArgumentException("本地策略改用公共 DNS 时请新建策略");
        boolean converting = oldGroup != null && local && !wasLocal;
        if (oldGroup != null && ((!local && (normalized.providerId != number(oldGroup.get("provider_ref_id"))
                || !normalized.zoneName.equalsIgnoreCase(Objects.toString(oldGroup.get("zone_name")))))
                || !normalized.domain.equalsIgnoreCase(Objects.toString(oldGroup.get("domain")))
                || !normalized.recordType.equalsIgnoreCase(Objects.toString(oldGroup.get("record_type"))))) {
            throw new IllegalArgumentException("已有策略的域名、DNS 账号和记录类型不能直接更换，请新建策略完成地址迁移");
        }
        if (converting) {
            for (Map<String, Object> old : oldRoutes) queueCleanup(id, oldGroup, old, now);
            jdbcTemplate.update("UPDATE smart_entry_route SET record_id=NULL,ownership_ready=0,original_address=NULL,original_ttl=NULL,dns_dirty=0,dns_error=NULL WHERE group_id=?", id);
        }
        Integer duplicate = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_entry_group WHERE domain=? AND record_type=? AND id<>?",
                Integer.class, normalized.domain, normalized.recordType, id == null ? 0L : id);
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("该业务域名已经配置三网优化");
        schedulingConflictService.assertDnsRecordAvailable("smart_entry", id, normalized.domain, normalized.recordType);
        List<Long> forwardIds = normalized.routes.stream().map(route -> route.forwardId).toList();
        schedulingConflictService.assertForwardSetAvailable("smart_entry", id, forwardIds);
        schedulingConflictService.assertForwardBackedTunnelSetAvailable("smart_entry", id, forwardIds);
        if (id == null) {
            KeyHolder key = new GeneratedKeyHolder();
            Object[] args = { JwtUtil.getUserIdFromToken(), normalized.name, normalized.providerId, normalized.provider,
                    normalized.zoneName, normalized.domain, normalized.recordType, normalized.ttl, normalized.publicPort,
                    normalized.probeIntervalMs, normalized.connectTimeoutMs, normalized.failureThreshold,
                    normalized.recoveryThreshold, normalized.enabled, now, now };
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO smart_entry_group (user_id,name,provider_ref_id,provider,zone_name,domain,record_type,ttl,public_port,"
                                + "probe_interval_ms,connect_timeout_ms,failure_threshold,recovery_threshold,enabled,state,created_time,updated_time) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'unknown',?,?)", Statement.RETURN_GENERATED_KEYS);
                for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                return statement;
            }, key);
            id = Objects.requireNonNull(key.getKey(), "无法读取新策略 ID").longValue();
        } else {
            jdbcTemplate.update("UPDATE smart_entry_group SET name=?,ttl=?,public_port=?,probe_interval_ms=?,connect_timeout_ms=?,"
                            + "failure_threshold=?,recovery_threshold=?,enabled=?,updated_time=? WHERE id=?",
                    normalized.name, normalized.ttl, normalized.publicPort, normalized.probeIntervalMs,
                    normalized.connectTimeoutMs, normalized.failureThreshold, normalized.recoveryThreshold,
                    normalized.enabled, now, id);
        }
        jdbcTemplate.update("UPDATE smart_entry_group SET recovery_stable_ms=?,switch_cooldown_ms=?,probe_mode=?,probe_path=?,sync_requested=1 WHERE id=?",
                normalized.recoveryStableMs, normalized.switchCooldownMs, normalized.probeMode, normalized.probePath, id);
        jdbcTemplate.update("UPDATE smart_entry_group SET dns_mode=?,provider_ref_id=?,provider=?,zone_name=? WHERE id=?",
                dto.getDnsMode(), normalized.providerId, normalized.provider, normalized.zoneName, id);
        Set<String> requested = normalized.routes.stream().map(route -> route.carrier).collect(Collectors.toSet());
        Set<String> archived = new HashSet<>();
        for (Map<String, Object> old : oldRoutes) {
            boolean stillUsed = normalized.routes.stream().anyMatch(route -> samePhysicalRoute(old, route));
            if (!stillUsed && archived.add(physicalRouteKey(old))) archiveActivity(id, old, now);
            if (!requested.contains(Objects.toString(old.get("carrier")))) {
                if (!converting) queueCleanup(id, oldGroup, old, now);
                jdbcTemplate.update("DELETE FROM smart_entry_route WHERE id=?", old.get("id"));
            }
        }
        for (NormalizedRoute route : normalized.routes) {
            Map<String, Object> old = oldRoutes.stream().filter(item -> route.carrier.equals(item.get("carrier"))).findFirst().orElse(null);
            Map<String, Object> physical = oldRoutes.stream().filter(item -> samePhysicalRoute(item, route)).findFirst().orElse(null);
            if (old == null) {
                jdbcTemplate.update("INSERT INTO smart_entry_route (group_id,carrier,forward_id,entry_node_id,entry_host,entry_address,entry_port,"
                                + "forward_name,node_name,status,created_time,updated_time) VALUES (?,?,?,?,?,?,?,?,?,'unknown',?,?)",
                        id, route.carrier, route.forwardId, route.nodeId, route.entryHost, route.entryAddress,
                        route.entryPort, route.forwardName, route.nodeName, now, now);
            } else {
                jdbcTemplate.update("UPDATE smart_entry_route SET forward_id=?,entry_node_id=?,entry_host=?,entry_address=?,entry_port=?,"
                                + "forward_name=?,node_name=?,updated_time=? WHERE id=?",
                        route.forwardId, route.nodeId, route.entryHost, route.entryAddress, route.entryPort,
                        route.forwardName, route.nodeName, now, old.get("id"));
            }
            jdbcTemplate.update("UPDATE smart_entry_route SET fallback_carriers=? WHERE group_id=? AND carrier=?",
                    route.fallbackCarriers == null ? null : JSON.toJSONString(route.fallbackCarriers), id, route.carrier);
            boolean probeChanged = oldGroup != null && (!normalized.probeMode.equals(Objects.toString(oldGroup.get("probe_mode"), "tcp"))
                    || !normalized.probePath.equals(Objects.toString(oldGroup.get("probe_path"), "/")));
            if (old == null || !samePhysicalRoute(old, route) || probeChanged) {
                jdbcTemplate.update("UPDATE smart_entry_route SET status='unknown',fail_count=0,success_count=0,healthy_since=NULL,"
                                + "latency_ms=NULL,last_error=NULL,last_checked_at=NULL,telemetry_ready=0,total_connections=0,current_connections=0,"
                                + "reported_total_connections=0,pending_connections=0,pending_probe_connections=0,activity_in_flow=0,activity_out_flow=0,"
                                + "last_activity_at=NULL,last_telemetry_at=NULL WHERE group_id=? AND carrier=?", id, route.carrier);
                if (physical != null) {
                    jdbcTemplate.update("UPDATE smart_entry_route SET telemetry_ready=?,total_connections=?,current_connections=?,reported_total_connections=?,"
                                    + "pending_connections=?,pending_probe_connections=?,activity_in_flow=?,activity_out_flow=?,last_activity_at=?,last_telemetry_at=? "
                                    + "WHERE group_id=? AND carrier=?",
                            physical.get("telemetryReady"), physical.get("totalConnections"), physical.get("currentConnections"),
                            physical.get("reportedTotalConnections"), physical.get("pendingConnections"), physical.get("pendingProbeConnections"),
                            physical.get("activityInFlow"), physical.get("activityOutFlow"), physical.get("lastActivityAt"), physical.get("lastTelemetryAt"), id, route.carrier);
                }
            }
        }
        dnsBindings.setBindings(id, dto.getDnsAgentIds());
        event(id, null, "configuration", "pending", local ? "配置已保存，等待入口检测及 OpenWrt 同步" : "配置已保存，等待入口检测及 DNS 同步");
        return id;
    }

    private boolean samePhysicalRoute(Map<String, Object> old, NormalizedRoute route) {
        return number(old.get("forwardId")) == route.forwardId && number(old.get("entryNodeId")) == route.nodeId
                && Objects.equals(old.get("entryAddress"), route.entryAddress) && intValue(old.get("entryPort")) == route.entryPort;
    }

    private void archiveActivity(Long id, Map<String, Object> route, long now) {
        jdbcTemplate.update("INSERT INTO smart_entry_activity_archive (group_id,forward_id,entry_node_id,node_name,entry_address,total_connections,in_flow,out_flow,last_activity_at,archived_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)", id, route.get("forwardId"), route.get("entryNodeId"), route.get("nodeName"),
                route.get("entryAddress"), number(route.get("totalConnections")), number(route.get("activityInFlow")),
                number(route.get("activityOutFlow")), route.get("lastActivityAt"), now);
    }

    private int pendingCleanup(Long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM smart_entry_dns_cleanup WHERE group_id=?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private void queueCleanup(Long id, Map<String, Object> group, Map<String, Object> route, long now) {
        if (!truth(route.get("ownershipReady")) && route.get("recordId") == null) return;
        jdbcTemplate.update("INSERT INTO smart_entry_dns_cleanup (group_id,payload,created_time) VALUES (?,?,?)",
                id, JSON.toJSONString(Map.of("group", group, "route", route)), now);
    }

    public R checkNow(Long id) {
        if (one("SELECT id FROM smart_entry_group WHERE id=?", id) == null) return R.err("三网优化策略不存在");
        try {
            checkGroup(id, true);
            return overview();
        } catch (RuntimeException e) {
            return R.err(shorten(e.getMessage()));
        }
    }

    public R diagnoseDns(Long id) {
        Map<String, Object> group = one("SELECT * FROM smart_entry_group WHERE id=?", id);
        if (group == null) return R.err("三网优化策略不存在");
        if ("local".equals(group.get("dns_mode"))) return R.err("此策略由 OpenWrt 本地解析，不使用公共 DNS 线路诊断");
        try {
            List<Map<String, Object>> configuredRoutes = routes(id);
            Map<String, Map<String, Object>> routeByCarrier = configuredRoutes.stream()
                    .collect(Collectors.toMap(item -> Objects.toString(item.get("carrier")), item -> item));
            Map<String, Object> defaultRoute = routeByCarrier.get("default");
            if (defaultRoute == null) return R.err("默认入口不存在");
            String zone = Objects.toString(group.get("zone_name"));
            String domain = Objects.toString(group.get("domain"));
            String recordType = Objects.toString(group.get("record_type"));
            String siblingType = "A".equals(recordType) ? "AAAA" : "A";
            long providerId = number(group.get("provider_ref_id"));

            List<DynamicDnsService.LineRoutingRecordState> providerRecords =
                    dynamicDnsService.inspectLineRoutingRecords(providerId, zone, domain, recordType);
            List<DynamicDnsService.LineRoutingRecordState> siblingRecords =
                    dynamicDnsService.inspectLineRoutingRecords(providerId, zone, domain, siblingType);
            Map<String, List<DynamicDnsService.LineRoutingRecordState>> providerByCarrier = providerRecords.stream()
                    .collect(Collectors.groupingBy(DynamicDnsService.LineRoutingRecordState::carrier));

            Map<String, CompletableFuture<DynamicDnsService.PublicDnsProbe>> probes = new LinkedHashMap<>();
            for (String carrier : CARRIERS) {
                probes.put(recordType + ":" + carrier, CompletableFuture.supplyAsync(
                        () -> dynamicDnsService.queryPublicLineAnswer(domain, recordType, carrier)));
                probes.put(siblingType + ":" + carrier, CompletableFuture.supplyAsync(
                        () -> dynamicDnsService.queryPublicLineAnswer(domain, siblingType, carrier)));
            }

            List<Map<String, Object>> lines = new ArrayList<>();
            int providerMatches = 0;
            int publicMatches = 0;
            for (String carrier : CARRIERS) {
                Map<String, Object> configured = routeByCarrier.get(carrier);
                boolean inherited = configured == null;
                Map<String, Object> expectedRoute = inherited ? defaultRoute : configured;
                String expected = Objects.toString(expectedRoute.get("currentAddress"),
                        Objects.toString(expectedRoute.get("entryAddress")));
                List<DynamicDnsService.LineRoutingRecordState> direct = providerByCarrier.getOrDefault(carrier, List.of());
                List<DynamicDnsService.LineRoutingRecordState> effective = inherited && direct.isEmpty()
                        ? providerByCarrier.getOrDefault("default", List.of()) : direct;
                DynamicDnsService.LineRoutingRecordState providerRecord = effective.size() == 1 ? effective.get(0) : null;
                DynamicDnsService.PublicDnsProbe publicProbe = probes.get(recordType + ":" + carrier).join();
                boolean providerMatch = providerRecord != null && providerRecord.enabled()
                        && CrossEntryFailoverService.dnsAddressMatches(expected, providerRecord.value())
                        && providerRecord.ttl() == intValue(group.get("ttl")) && !(inherited && !direct.isEmpty());
                boolean publicMatch = publicProbe.successful() && !publicProbe.answers().isEmpty()
                        && publicProbe.answers().stream().allMatch(answer -> CrossEntryFailoverService.dnsAddressMatches(expected, answer));
                if (providerMatch) providerMatches++;
                if (publicMatch) publicMatches++;
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("carrier", carrier);
                line.put("inherited", inherited);
                line.put("unexpectedOverride", inherited && !direct.isEmpty());
                line.put("expectedAddress", expected);
                line.put("providerRecord", providerRecord);
                line.put("providerRecords", effective);
                line.put("providerMatch", providerMatch);
                line.put("publicProbe", publicProbe);
                line.put("publicMatch", publicMatch);
                lines.add(line);
            }

            Integer siblingManagedCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM smart_entry_group WHERE provider_ref_id=? AND zone_name=? AND domain=? AND record_type=? AND id<>?",
                    Integer.class, providerId, zone, domain, siblingType, id);
            List<DynamicDnsService.PublicDnsProbe> siblingProbes = CARRIERS.stream()
                    .map(carrier -> probes.get(siblingType + ":" + carrier).join()).toList();
            boolean siblingVisible = !siblingRecords.isEmpty()
                    || siblingProbes.stream().anyMatch(probe -> !probe.answers().isEmpty());
            boolean siblingConflict = siblingVisible && (siblingManagedCount == null || siblingManagedCount == 0);

            Map<String, Object> summary = new LinkedHashMap<>();
            long queryFailures = lines.stream()
                    .filter(line -> !((DynamicDnsService.PublicDnsProbe) line.get("publicProbe")).successful()).count();
            summary.put("providerMatches", providerMatches);
            summary.put("publicMatches", publicMatches);
            summary.put("totalLines", CARRIERS.size());
            summary.put("queryFailures", queryFailures);
            summary.put("publicState", queryFailures > 0 ? "unknown" : publicMatches == CARRIERS.size() ? "matched" : "mismatch");
            summary.put("siblingConflict", siblingConflict);
            summary.put("healthy", providerMatches == CARRIERS.size() && publicMatches == CARRIERS.size() && !siblingConflict);
            Map<String, Object> sibling = new LinkedHashMap<>();
            sibling.put("recordType", siblingType);
            sibling.put("managed", siblingManagedCount != null && siblingManagedCount > 0);
            sibling.put("providerRecords", siblingRecords);
            sibling.put("publicProbes", siblingProbes);
            sibling.put("visible", siblingVisible);
            sibling.put("conflict", siblingConflict);
            return R.ok(Map.of("groupId", id, "domain", domain, "recordType", recordType,
                    "ttl", group.get("ttl"), "checkedAt", System.currentTimeMillis(), "lines", lines,
                    "sibling", sibling, "summary", summary,
                    "referenceProbe", dynamicDnsService.queryPublicDefaultAnswer(domain, recordType)));
        } catch (RuntimeException e) {
            return R.err("DNS 线路诊断失败：" + shorten(e.getMessage()));
        }
    }

    public R events(Long id) {
        return R.ok(jdbcTemplate.queryForList(
                "SELECT id,carrier,event_type AS eventType,status,detail,created_time AS createdTime FROM smart_entry_event "
                        + "WHERE group_id=? "
                        + "ORDER BY created_time DESC LIMIT 200", id));
    }

    public void recordActivity(Long forwardId, Long reportingNodeId, Long reportedTotalConnections,
                               Long reportedCurrentConnections, Long inbound, Long outbound) {
        if (forwardId == null || reportingNodeId == null) return;
        String routeKey = forwardId + ":" + reportingNodeId;
        List<Long> matches = activityGroups.computeIfAbsent(routeKey, ignored -> List.copyOf(jdbcTemplate.queryForList(
                "SELECT DISTINCT group_id FROM smart_entry_route WHERE forward_id=? AND entry_node_id=?",
                Long.class, forwardId, reportingNodeId)));
        for (Long groupId : matches) {
            synchronized (locks.computeIfAbsent(groupId, ignored -> new Object())) {
                Map<String, Object> state = one("SELECT * FROM smart_entry_route WHERE group_id=? AND forward_id=? AND entry_node_id=? ORDER BY id LIMIT 1",
                        groupId, forwardId, reportingNodeId);
                if (state == null) continue;
                long now = System.currentTimeMillis();
                long inputBytes = Math.max(0L, inbound == null ? 0L : inbound);
                long outputBytes = Math.max(0L, outbound == null ? 0L : outbound);
                boolean telemetryReady = truth(state.get("telemetry_ready"));
                long previousReported = number(state.get("reported_total_connections"));
                long rawConnectionDelta = reportedTotalConnections == null ? 0L
                        : connectionDelta(previousReported, Math.max(0L, reportedTotalConnections), telemetryReady);
                long pendingProbeConnections = number(state.get("pending_probe_connections"));
                long consumedProbeConnections = reportedTotalConnections == null ? 0L
                        : CrossEntryFailoverService.consumableProbeConnections(
                                rawConnectionDelta, pendingProbeConnections, telemetryReady);
                long connectionDelta = businessConnectionDelta(rawConnectionDelta, consumedProbeConnections);
                long currentConnections = reportedCurrentConnections == null
                        ? number(state.get("current_connections")) : Math.max(0L, reportedCurrentConnections);
                boolean active = inputBytes > 0 || outputBytes > 0 || connectionDelta > 0;
                Long previousActivity = nullableLong(state.get("last_activity_at"));
                jdbcTemplate.update("UPDATE smart_entry_route SET telemetry_ready=?,total_connections=total_connections+?,current_connections=?,"
                                + "reported_total_connections=?,pending_connections=pending_connections+?,"
                                + "pending_probe_connections=GREATEST(pending_probe_connections-?,0),"
                                + "activity_in_flow=activity_in_flow+?,activity_out_flow=activity_out_flow+?,"
                                + "last_activity_at=?,last_telemetry_at=?,updated_time=? WHERE group_id=? AND forward_id=? AND entry_node_id=?",
                        reportedTotalConnections == null ? (telemetryReady ? 1 : 0) : 1, connectionDelta, currentConnections,
                        reportedTotalConnections == null ? previousReported : Math.max(0L, reportedTotalConnections), connectionDelta,
                        consumedProbeConnections, inputBytes, outputBytes, active ? now : previousActivity, now, now,
                        groupId, forwardId, reportingNodeId);
                if (active && previousActivity == null) {
                    event(groupId, null, "first_active", "active", activityLabel(groupId, forwardId, reportingNodeId)
                            + "首次检测到连接或流量");
                } else if (active && now - previousActivity >= ACTIVITY_RESUME_AFTER_MS) {
                    event(groupId, null, "resumed", "active", activityLabel(groupId, forwardId, reportingNodeId)
                            + "空闲 30 分钟后重新活跃");
                }
            }
        }
    }

    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void flushConnectionActivity() {
        List<Map<String, Object>> pending = jdbcTemplate.queryForList(
                "SELECT group_id AS groupId,forward_id AS forwardId,entry_node_id AS entryNodeId "
                        + "FROM smart_entry_route WHERE pending_connections>0 GROUP BY group_id,forward_id,entry_node_id");
        for (Map<String, Object> item : pending) {
            long groupId = number(item.get("groupId"));
            long forwardId = number(item.get("forwardId"));
            long nodeId = number(item.get("entryNodeId"));
            synchronized (locks.computeIfAbsent(groupId, ignored -> new Object())) {
                Map<String, Object> state = one("SELECT pending_connections,current_connections FROM smart_entry_route "
                        + "WHERE group_id=? AND forward_id=? AND entry_node_id=? ORDER BY id LIMIT 1", groupId, forwardId, nodeId);
                if (state == null) continue;
                long count = number(state.get("pending_connections"));
                if (count <= 0) continue;
                jdbcTemplate.update("UPDATE smart_entry_route SET pending_connections=0 WHERE group_id=? AND forward_id=? AND entry_node_id=?",
                        groupId, forwardId, nodeId);
                event(groupId, null, "new_connections", "active", activityLabel(groupId, forwardId, nodeId)
                        + "最近一分钟新增 " + count + " 个连接，当前 " + number(state.get("current_connections")) + " 个");
            }
        }
    }

    public R delete(Long id) {
        return mutationLocks.withLock(() -> {
            synchronized (locks.computeIfAbsent(id, ignored -> new Object())) {
                return transactions.execute(status -> {
                    Map<String, Object> group = one("SELECT * FROM smart_entry_group WHERE id=?", id);
                    if (group == null) return R.ok();
                    if (!"deleting".equals(group.get("state"))) {
                        dnsBindings.setBindings(id, List.of());
                        for (Map<String, Object> route : routes(id)) queueCleanup(id, group, route, System.currentTimeMillis());
                        jdbcTemplate.update("UPDATE smart_entry_group SET enabled=0,state='deleting',sync_requested=0,last_error=NULL WHERE id=?", id);
                        event(id, null, "delete", "pending", "正在清理 DNS，完成后自动移除策略；原转发保留");
                    }
                    return R.ok(Map.of("state", "deleting", "message", "删除已受理，正在清理 DNS"));
                });
            }
        });
    }

    @Scheduled(initialDelay = 25_000L, fixedDelay = 10_000L)
    public void cleanupDns() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM smart_entry_group WHERE state='deleting' "
                + "OR id IN (SELECT group_id FROM smart_entry_dns_cleanup) ORDER BY updated_time LIMIT 30", Long.class);
        for (Long id : ids) {
            synchronized (locks.computeIfAbsent(id, ignored -> new Object())) {
                Map<String, Object> group = one("SELECT * FROM smart_entry_group WHERE id=?", id);
                if (group == null) continue;
                boolean deleting = "deleting".equals(group.get("state"));
                if (!deleting && truth(group.get("sync_requested"))) continue;
                for (Map<String, Object> task : jdbcTemplate.queryForList("SELECT * FROM smart_entry_dns_cleanup WHERE group_id=? "
                        + "AND (attempted_at IS NULL OR attempted_at<?)", id, System.currentTimeMillis() - DNS_RETRY_INTERVAL_MS)) {
                    try {
                        JSONObject payload = JSON.parseObject(Objects.toString(task.get("payload")));
                        releaseRecord(payload.getJSONObject("group"), payload.getJSONObject("route"));
                        jdbcTemplate.update("DELETE FROM smart_entry_dns_cleanup WHERE id=?", task.get("id"));
                        event(id, null, "cleanup", "success", "旧 DNS 线路已释放");
                    } catch (RuntimeException e) {
                        String message = shorten(e.getMessage());
                        jdbcTemplate.update("UPDATE smart_entry_dns_cleanup SET last_error=?,attempted_at=? WHERE id=?", message, System.currentTimeMillis(), task.get("id"));
                        jdbcTemplate.update("UPDATE smart_entry_group SET last_error=?,updated_time=? WHERE id=?", "DNS 清理失败，将重试：" + message, System.currentTimeMillis(), id);
                        event(id, null, "cleanup", "failed", message);
                    }
                }
                if (deleting && pendingCleanup(id) == 0) {
                    transactions.executeWithoutResult(status -> {
                        jdbcTemplate.update("DELETE FROM smart_entry_event WHERE group_id=?", id);
                        jdbcTemplate.update("DELETE FROM smart_entry_activity_archive WHERE group_id=?", id);
                        jdbcTemplate.update("DELETE FROM smart_entry_route WHERE group_id=?", id);
                        jdbcTemplate.update("DELETE FROM smart_entry_group WHERE id=?", id);
                    });
                    activityGroups.clear();
                }
            }
        }
    }

    @Scheduled(initialDelay = 20_000L, fixedDelay = 2_000L)
    public void poll() {
        if (!checking.compareAndSet(false, true)) return;
        try {
            long now = System.currentTimeMillis();
            List<Long> ids = jdbcTemplate.query(
                    "SELECT id FROM smart_entry_group WHERE state<>'deleting' AND (enabled=1 OR sync_requested=1) AND (last_checked_at IS NULL OR last_checked_at+probe_interval_ms<=?) "
                            + "ORDER BY last_checked_at LIMIT " + MAX_GROUPS_PER_TICK,
                    (rs, rowNum) -> rs.getLong(1), now);
            for (Long id : ids) {
                try { checkGroup(id, false); }
                catch (RuntimeException e) { log.warn("Smart entry check {} failed: {}", id, e.getMessage()); }
            }
        } finally {
            checking.set(false);
        }
    }

    private void checkGroup(Long id, boolean manual) {
        synchronized (locks.computeIfAbsent(id, ignored -> new Object())) {
            Map<String, Object> group = one("SELECT * FROM smart_entry_group WHERE id=?", id);
            if (group == null || "deleting".equals(group.get("state"))) return;
            List<Map<String, Object>> routes = routes(id);
            int timeout = intValue(group.get("connect_timeout_ms"));
            int failureThreshold = intValue(group.get("failure_threshold"));
            int recoveryThreshold = intValue(group.get("recovery_threshold"));
            long now = System.currentTimeMillis();
            Map<String, CompletableFuture<Probe>> futures = new LinkedHashMap<>();
            for (Map<String, Object> route : routes) {
                futures.computeIfAbsent(physicalRouteKey(route), ignored ->
                        CompletableFuture.supplyAsync(() -> probe(group, route, timeout)));
            }
            for (int index = 0; index < routes.size(); index++) {
                Map<String, Object> route = routes.get(index);
                Probe probe = futures.get(physicalRouteKey(route)).join();
                String oldStatus = Objects.toString(route.get("status"), "unknown");
                int failures = intValue(route.get("failCount"));
                int successes = intValue(route.get("successCount"));
                String newStatus = oldStatus;
                Long healthySince = nullableLong(route.get("healthySince"));
                if (probe.healthy) {
                    failures = 0;
                    successes++;
                    if (healthySince == null) healthySince = now;
                    if (!"healthy".equals(oldStatus) && successes >= recoveryThreshold
                            && now - healthySince >= intValue(group.get("recovery_stable_ms"))) newStatus = "healthy";
                } else {
                    healthySince = null;
                    successes = 0;
                    failures++;
                    if (failures >= failureThreshold) newStatus = "unhealthy";
                }
                jdbcTemplate.update("UPDATE smart_entry_route SET status=?,fail_count=?,success_count=?,latency_ms=?,last_error=?,last_checked_at=?,updated_time=?,healthy_since=? WHERE id=?",
                        newStatus, failures, successes, probe.latencyMs, probe.error, now, now, healthySince, number(route.get("id")));
                if (!oldStatus.equals(newStatus)) {
                    event(id, Objects.toString(route.get("carrier")), "health", "healthy".equals(newStatus) ? "recovered" : "failed",
                            carrierLabel(Objects.toString(route.get("carrier"))) + "入口" + ("healthy".equals(newStatus) ? "恢复" : "不可用")
                                    + "：" + Objects.toString(route.get("nodeName")));
                }
            }
            jdbcTemplate.update("UPDATE smart_entry_group SET last_checked_at=?,updated_time=? WHERE id=?", now, now, id);
            syncRecords(id, manual ? "手动检测" : "健康检测", manual);
        }
    }

    private Probe probe(Map<String, Object> group, Map<String, Object> route, int timeout) {
        Long groupId = number(group.get("id"));
        long nodeId = number(route.get("entryNodeId"));
        Integer active = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM forward f JOIN tunnel t ON t.id=f.tunnel_id "
                + "WHERE f.id=? AND f.status=1 AND t.in_node_id=? AND f.in_port=?", Integer.class,
                route.get("forwardId"), nodeId, route.get("entryPort"));
        if (active == null || active == 0) return new Probe(false, null, "转发已暂停、删除或入口配置已变化");
        if (!WebSocketServer.isNodeOnline(nodeId)) return new Probe(false, null, "入口 Agent 离线");
        long started = System.nanoTime();
        boolean connected = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(Objects.toString(route.get("entryAddress")), intValue(route.get("entryPort"))), timeout);
            connected = true;
            SmartEntryHealthProbe.verify(socket, Objects.toString(group.get("probe_mode"), "tcp"),
                    Objects.toString(group.get("domain")), Objects.toString(group.get("probe_path"), "/"), timeout);
            return new Probe(true, Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000)), null);
        } catch (Exception e) {
            return new Probe(false, null, "检测失败：" + shorten(e.getMessage()));
        } finally {
            // A successful TCP connection is counted by the Agent even if the application check fails.
            if (connected) jdbcTemplate.update("UPDATE smart_entry_route SET pending_probe_connections=pending_probe_connections+1 "
                            + "WHERE group_id=? AND forward_id=? AND entry_node_id=?",
                    groupId, number(route.get("forwardId")), nodeId);
        }
    }

    private void syncRecords(Long groupId, String reason) {
        syncRecords(groupId, reason, false);
    }

    private void syncRecords(Long groupId, String reason, boolean forceVerify) {
        Map<String, Object> group = one("SELECT * FROM smart_entry_group WHERE id=?", groupId);
        if (group == null || "deleting".equals(group.get("state"))) return;
        List<Map<String, Object>> all = routes(groupId);
        if (all.isEmpty()) return;
        if ("local".equals(group.get("dns_mode"))) {
            publishLocalRoutes(group, all);
            return;
        }
        int ttl = intValue(group.get("ttl"));
        long now = System.currentTimeMillis();
        boolean recordsChanged = false;
        boolean unresolved = false;
        boolean writeFailed = false;
        Map<String, String> expectedAddresses = new LinkedHashMap<>();
        for (Map<String, Object> line : all) {
            Map<String, Object> desired = chooseRoute(line, all, truth(group.get("enabled")), intValue(group.get("switch_cooldown_ms")), now);
            if (desired == null) { unresolved = true; continue; }
            String desiredAddress = Objects.toString(desired.get("entryAddress"));
            long desiredForward = number(desired.get("forwardId"));
            boolean changed = !Objects.equals(desiredForward, nullableLong(line.get("currentForwardId")))
                    || !desiredAddress.equals(Objects.toString(line.get("currentAddress"), ""));
            Long lastDnsAttempt = nullableLong(line.get("dnsAttemptedAt"));
            boolean needsWrite = shouldWriteDnsRecord(changed, truth(line.get("dnsDirty")), lastDnsAttempt,
                    StringUtils.isBlank(Objects.toString(line.get("recordId"), null)),
                    intValue(line.get("appliedTtl")), ttl, now);
            expectedAddresses.put(Objects.toString(line.get("carrier")), desiredAddress);
            if (needsWrite) {
                if ("error".equals(line.get("dnsState")) && desiredAddress.equals(line.get("dnsTargetAddress"))
                        && line.get("dnsAttemptedAt") instanceof Number attempt && now - attempt.longValue() < DNS_RETRY_INTERVAL_MS) {
                    writeFailed = true;
                    continue;
                }
                DynamicDnsService.LineRoutingRecord record;
                try {
                    jdbcTemplate.update("UPDATE smart_entry_route SET dns_target_address=?,dns_attempted_at=? WHERE id=?", desiredAddress, now, line.get("id"));
                    claimRecordOwnership(group, line);
                    record = dynamicDnsService.ensureLineRoutingRecord(number(group.get("provider_ref_id")),
                            Objects.toString(group.get("zone_name")), Objects.toString(group.get("domain")),
                            Objects.toString(group.get("record_type")), Objects.toString(line.get("carrier")), desiredAddress,
                            ttl, Objects.toString(line.get("recordId"), null));
                } catch (RuntimeException e) {
                    writeFailed = true;
                    String message = shorten(e.getMessage());
                    jdbcTemplate.update("UPDATE smart_entry_route SET dns_dirty=1,dns_state='error',dns_error=?,updated_time=? WHERE id=?",
                            message, now, number(line.get("id")));
                    jdbcTemplate.update("UPDATE smart_entry_group SET state='error',last_error=?,updated_time=? WHERE id=?",
                            message, now, number(group.get("id")));
                    event(groupId, Objects.toString(line.get("carrier")), "dns_error", "failed", message);
                    continue;
                }
                jdbcTemplate.update("UPDATE smart_entry_route SET record_id=?,current_forward_id=?,current_address=?,applied_ttl=?,dns_dirty=1,dns_state='pending',"
                                + "last_switched_at=IF(?, ?,last_switched_at),dns_error=NULL,updated_time=? WHERE id=?",
                        record.recordId(), desiredForward, desiredAddress, ttl, changed, now, now, number(line.get("id")));
                recordsChanged = true;
            }
            if (changed) {
                event(groupId, Objects.toString(line.get("carrier")), "route_switch", "success",
                        reason + "：" + carrierLabel(Objects.toString(line.get("carrier"))) + "线路切换到 " + Objects.toString(desired.get("nodeName")));
            }
        }

        long oldestVerification = all.stream().map(item -> nullableLong(item.get("dnsVerifiedAt")))
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(0L);
        if (!expectedAddresses.isEmpty() && (recordsChanged || forceVerify || oldestVerification == 0L || now - oldestVerification >= DNS_VERIFY_INTERVAL_MS)) {
                verifyProviderRecords(group, all.stream().filter(line -> expectedAddresses.containsKey(Objects.toString(line.get("carrier")))).toList(), expectedAddresses, ttl, now);
        }
        Integer dnsFailures = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_entry_route WHERE group_id=? AND (dns_state='error' OR (ownership_ready=1 AND dns_dirty=1))",
                Integer.class, groupId);
        if (writeFailed || (dnsFailures != null && dnsFailures > 0)) {
            String dnsError = jdbcTemplate.query(
                    "SELECT dns_error FROM smart_entry_route WHERE group_id=? AND dns_error IS NOT NULL ORDER BY updated_time DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : "DNS 线路等待重新同步", groupId);
            jdbcTemplate.update("UPDATE smart_entry_group SET state='error',last_error=?,updated_time=? WHERE id=?",
                    shorten(dnsError), now, groupId);
            return;
        }
        long healthy = all.stream().filter(item -> "healthy".equals(item.get("status"))).count();
        String state = unresolved ? (healthy == 0 && all.stream().allMatch(item -> "unhealthy".equals(item.get("status"))) ? "offline" : "unknown")
                : healthy == all.size() ? "healthy" : "degraded";
        jdbcTemplate.update("UPDATE smart_entry_group SET state=?,last_error=NULL,updated_time=? WHERE id=?", state, now, groupId);
        if (!unresolved || all.stream().allMatch(item -> "unhealthy".equals(item.get("status"))))
            jdbcTemplate.update("UPDATE smart_entry_group SET sync_requested=0 WHERE id=?", groupId);
    }

    static Map<String, Object> chooseRoute(Map<String, Object> line, List<Map<String, Object>> all,
                                           boolean enabled, int cooldown, long now) {
        Map<String, Object> current = all.stream().filter(item -> Objects.equals(item.get("forwardId"), line.get("currentForwardId"))
                && Objects.equals(item.get("entryAddress"), line.get("currentAddress"))).findFirst().orElse(null);
        if (!enabled && current != null) return current;
        List<String> order = new ArrayList<>();
        order.add(Objects.toString(line.get("carrier")));
        Object configured = line.get("fallbackCarriers");
        if (configured instanceof List<?> values) values.forEach(value -> order.add(value.toString()));
        else if (configured != null) order.addAll(JSON.parseArray(configured.toString(), String.class));
        else {
            order.add("default");
            CARRIERS.forEach(carrier -> { if (!order.contains(carrier)) order.add(carrier); });
        }
        Map<String, Object> desired = null;
        for (String carrier : order) {
            desired = all.stream().filter(item -> carrier.equals(item.get("carrier")) && "healthy".equals(item.get("status"))).findFirst().orElse(null);
            if (desired != null) break;
        }
        // Cooldown never keeps an unhealthy route in service.
        if (desired != null && current != null && "healthy".equals(current.get("status"))
                && line.get("lastSwitchedAt") instanceof Number switched && now - switched.longValue() < cooldown) return current;
        return desired;
    }

    private void publishLocalRoutes(Map<String, Object> group, List<Map<String, Object>> all) {
        long now = System.currentTimeMillis();
        boolean unresolved = false;
        for (Map<String, Object> route : all) {
            Map<String, Object> selected = chooseRoute(route, all, truth(group.get("enabled")), intValue(group.get("switch_cooldown_ms")), now);
            boolean changed = !Objects.equals(nullableLong(route.get("currentForwardId")), selected == null ? null : number(selected.get("forwardId")))
                    || !Objects.equals(route.get("currentAddress"), selected == null ? null : selected.get("entryAddress"));
            unresolved |= selected == null;
            jdbcTemplate.update("UPDATE smart_entry_route SET current_forward_id=?,current_address=?,applied_ttl=5,dns_dirty=0,dns_state=?,"
                            + "dns_error=NULL,last_switched_at=IF(?,?,last_switched_at),updated_time=? WHERE id=?",
                    selected == null ? null : selected.get("forwardId"), selected == null ? null : selected.get("entryAddress"),
                    selected == null ? "pending" : "healthy", changed, now, now, route.get("id"));
            if (changed) event(number(group.get("id")), Objects.toString(route.get("carrier")), "route_switch", selected == null ? "failed" : "success",
                    selected == null ? "暂无健康入口，本地解析将停止返回地址" : "本地入口选择：" + selected.get("nodeName"));
        }
        long healthy = all.stream().filter(route -> "healthy".equals(route.get("status"))).count();
        String state = unresolved ? (all.stream().allMatch(route -> "unhealthy".equals(route.get("status"))) ? "offline" : "unknown")
                : healthy == all.size() ? "healthy" : "degraded";
        jdbcTemplate.update("UPDATE smart_entry_group SET state=?,last_error=NULL,sync_requested=0,updated_time=? WHERE id=?", state, now, group.get("id"));
    }

    private void claimRecordOwnership(Map<String, Object> group, Map<String, Object> line) {
        List<DynamicDnsService.LineRoutingRecordState> existing = dynamicDnsService.inspectLineRoutingRecords(
                number(group.get("provider_ref_id")), Objects.toString(group.get("zone_name")), Objects.toString(group.get("domain")), Objects.toString(group.get("record_type")))
                .stream().filter(record -> record.carrier().equals(line.get("carrier"))).toList();
        if (existing.size() > 1) throw new IllegalStateException("该运营商存在重复 DNS 记录，请先整理");
        DynamicDnsService.LineRoutingRecordState previous = existing.isEmpty() ? null : existing.get(0);
        if (truth(line.get("ownershipReady"))) {
            if (previous != null && line.get("recordId") != null && !previous.recordId().equals(line.get("recordId")))
                throw new IllegalStateException("服务商线路记录已被替换，请先检查外部修改");
            line.put("recordId", previous == null ? null : previous.recordId());
            return;
        }
        if (previous != null && !previous.enabled()) throw new IllegalStateException("该运营商 DNS 记录已停用，请先在服务商确认用途");
        jdbcTemplate.update("UPDATE smart_entry_route SET ownership_ready=1,managed_created=?,record_id=?,original_address=?,original_ttl=? WHERE id=?",
                previous == null, previous == null ? null : previous.recordId(), previous == null ? null : previous.value(),
                previous == null ? null : previous.ttl(), line.get("id"));
        if (previous != null) line.put("recordId", previous.recordId());
    }

    private void verifyProviderRecords(Map<String, Object> group, List<Map<String, Object>> routes,
                                       Map<String, String> expectedAddresses, int ttl, long now) {
        List<DynamicDnsService.LineRoutingRecordState> states;
        try {
            states = dynamicDnsService.inspectLineRoutingRecords(number(group.get("provider_ref_id")),
                    Objects.toString(group.get("zone_name")), Objects.toString(group.get("domain")),
                    Objects.toString(group.get("record_type")));
        } catch (RuntimeException e) {
            jdbcTemplate.update("UPDATE smart_entry_route SET dns_dirty=1,dns_state='error',dns_error=?,updated_time=? WHERE group_id=?",
                    shorten(e.getMessage()), now, number(group.get("id")));
            jdbcTemplate.update("UPDATE smart_entry_group SET state='error',last_error=?,updated_time=? WHERE id=?",
                    shorten(e.getMessage()), now, number(group.get("id")));
            throw e;
        }
        Map<String, List<DynamicDnsService.LineRoutingRecordState>> byCarrier = states.stream()
                .collect(Collectors.groupingBy(DynamicDnsService.LineRoutingRecordState::carrier));
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> route : routes) {
            String carrier = Objects.toString(route.get("carrier"));
            String expected = expectedAddresses.get(carrier);
            List<DynamicDnsService.LineRoutingRecordState> matches = byCarrier.getOrDefault(carrier, List.of());
            DynamicDnsService.LineRoutingRecordState actual = matches.size() == 1 ? matches.get(0) : null;
            String error = actual == null ? (matches.isEmpty() ? "服务商中缺少该线路记录" : "服务商中存在重复线路记录")
                    : !actual.enabled() ? "服务商线路记录已停用"
                    : !CrossEntryFailoverService.dnsAddressMatches(expected, actual.value()) ? "服务商返回地址与目标入口不一致"
                    : actual.ttl() != ttl ? "服务商实际 TTL 与面板不一致"
                    : null;
            if (error == null) {
                jdbcTemplate.update("UPDATE smart_entry_route SET record_id=?,dns_dirty=0,dns_state='healthy',dns_error=NULL,dns_verified_at=?,updated_time=? WHERE id=?",
                        actual.recordId(), now, now, number(route.get("id")));
            } else {
                failures.add(carrierLabel(carrier) + "：" + error);
                jdbcTemplate.update("UPDATE smart_entry_route SET dns_dirty=1,dns_state='error',dns_error=?,updated_time=? WHERE id=?",
                        error, now, number(route.get("id")));
            }
        }
        if (!failures.isEmpty()) {
            String message = String.join("；", failures);
            jdbcTemplate.update("UPDATE smart_entry_group SET state='error',last_error=?,updated_time=? WHERE id=?",
                    shorten(message), now, number(group.get("id")));
            throw new IllegalStateException(message);
        }
    }

    private Normalized normalize(SmartEntrySaveDto dto) {
        boolean local = "local".equals(dto.getDnsMode());
        if (!local && dto.getProviderRefId() == null) throw new IllegalArgumentException("请选择 DNSPod 或阿里云 DNS 配置");
        Map<String, Object> provider = local ? Map.of("provider", "local") : one("SELECT id,provider FROM dynamic_dns_provider WHERE id=? AND enabled=1", dto.getProviderRefId());
        if (!local && (provider == null || !List.of("dnspod", "aliyun").contains(Objects.toString(provider.get("provider"))))) {
            throw new IllegalArgumentException("运营商线路解析仅支持已启用的 DNSPod 或阿里云 DNS 配置");
        }
        String zone = StringUtils.trimToEmpty(local ? dto.getDomain() : dto.getZoneName()).toLowerCase(Locale.ROOT);
        String domain = dynamicDnsService.normalizeLineRoutingDomain(zone, dto.getDomain());
        String type = StringUtils.defaultIfBlank(dto.getRecordType(), "A").toUpperCase(Locale.ROOT);
        if (!List.of("A", "AAAA").contains(type)) throw new IllegalArgumentException("仅支持 A 和 AAAA 记录");
        if (dto.getRoutes() == null) throw new IllegalArgumentException("请配置入口线路");
        Set<String> carriers = new HashSet<>();
        List<NormalizedRoute> routes = new ArrayList<>();
        for (SmartEntrySaveDto.Route assignment : dto.getRoutes()) {
            String carrier = StringUtils.defaultIfBlank(assignment.getCarrier(), "").toLowerCase(Locale.ROOT);
            if (!CARRIERS.contains(carrier) || !carriers.add(carrier)) throw new IllegalArgumentException("运营商入口配置重复或无效");
            if (assignment.getForwardId() == null) throw new IllegalArgumentException(carrierLabel(carrier) + "入口未选择转发");
            Map<String, Object> forward = one("SELECT f.id,f.name,f.in_port AS inPort,f.protocol_mode AS protocolMode,t.in_node_id AS inNodeId,"
                            + "COALESCE(n.name,CONCAT('节点',t.in_node_id)) AS nodeName,COALESCE(NULLIF(n.server_ip,''),n.ip,t.in_ip) AS entryHost "
                            + "FROM forward f JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id WHERE f.id=? "
                            + "AND (f.status=1 OR f.id IN (SELECT forward_id FROM smart_entry_route WHERE group_id=?))",
                    assignment.getForwardId(), dto.getId() == null ? 0L : dto.getId());
            if (forward == null || !List.of("tcp", "tcp_udp").contains(Objects.toString(forward.get("protocolMode"), "tcp"))) {
                throw new IllegalArgumentException(carrierLabel(carrier) + "入口转发不存在、已暂停或不支持 TCP 检测");
            }
            String host = Objects.toString(forward.get("entryHost"), "");
            String address = resolve(host, type);
            routes.add(new NormalizedRoute(carrier, number(forward.get("id")), number(forward.get("inNodeId")), host, address,
                    intValue(forward.get("inPort")), Objects.toString(forward.get("name")), Objects.toString(forward.get("nodeName")), assignment.getFallbackCarriers()));
        }
        if (!carriers.contains("default")) throw new IllegalArgumentException("必须配置默认入口");
        if (routes.size() < 2 || routes.stream().map(route -> route.forwardId).distinct().count() < 2) {
            throw new IllegalArgumentException("除默认入口外，至少配置一条不同的运营商入口");
        }
        if (routes.stream().map(route -> route.nodeId).distinct().count() < 2) {
            throw new IllegalArgumentException("三网优化至少需要两台不同的公网入口节点");
        }
        int port = routes.get(0).entryPort;
        if (routes.stream().anyMatch(route -> route.entryPort != port)) throw new IllegalArgumentException("所有入口转发必须使用相同公网端口");
        String providerName = Objects.toString(provider.get("provider"));
        for (NormalizedRoute route : routes) {
            if (route.fallbackCarriers == null) continue;
            if (route.fallbackCarriers.size() > 3 || new HashSet<>(route.fallbackCarriers).size() != route.fallbackCarriers.size()
                    || route.fallbackCarriers.contains(route.carrier) || !carriers.containsAll(route.fallbackCarriers))
                throw new IllegalArgumentException("备用顺序只能选择本策略中已配置的其他运营商入口，且不能重复");
        }
        String probeMode = StringUtils.defaultIfBlank(dto.getProbeMode(), "tcp");
        String probePath = StringUtils.defaultIfBlank(dto.getProbePath(), "/");
        SmartEntryHealthProbe.validate(probeMode, probePath);
        return new Normalized(StringUtils.trim(dto.getName()), local ? 0L : dto.getProviderRefId(), providerName, zone,
                domain, type, local ? 5 : clamp(dto.getTtl(), 1, 86400, DynamicDnsService.lineRoutingMinimumTtl(providerName)), port,
                clamp(dto.getProbeIntervalMs(), 2000, 60000, 5000), clamp(dto.getConnectTimeoutMs(), 300, 10000, 1500),
                clamp(dto.getFailureThreshold(), 1, 10, 2), clamp(dto.getRecoveryThreshold(), 1, 10, 3),
                !Boolean.FALSE.equals(dto.getEnabled()), clamp(dto.getRecoveryStableMs(), 0, 600000, 30000),
                clamp(dto.getSwitchCooldownMs(), 0, 600000, 60000), probeMode, probePath, routes);
    }

    private String resolve(String host, String type) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (("A".equals(type) && address instanceof Inet4Address) || ("AAAA".equals(type) && address instanceof Inet6Address)) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        throw new IllegalArgumentException("入口地址 " + host + " 无法解析为 " + type + " 记录");
    }

    private List<Map<String, Object>> routes(Long groupId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,group_id AS groupId,carrier,forward_id AS forwardId,entry_node_id AS entryNodeId,entry_host AS entryHost,"
                        + "entry_address AS entryAddress,entry_port AS entryPort,forward_name AS forwardName,node_name AS nodeName,record_id AS recordId,"
                        + "managed_created AS managedCreated,ownership_ready AS ownershipReady,original_address AS originalAddress,original_ttl AS originalTtl,"
                        + "fallback_carriers AS fallbackCarriers,healthy_since AS healthySince,last_switched_at AS lastSwitchedAt,"
                        + "dns_target_address AS dnsTargetAddress,dns_attempted_at AS dnsAttemptedAt,"
                        + "current_forward_id AS currentForwardId,current_address AS currentAddress,dns_dirty AS dnsDirty,"
                        + "applied_ttl AS appliedTtl,dns_state AS dnsState,dns_error AS dnsError,dns_verified_at AS dnsVerifiedAt,"
                        + "status,fail_count AS failCount,"
                        + "success_count AS successCount,latency_ms AS latencyMs,last_error AS lastError,last_checked_at AS lastCheckedAt,"
                        + "telemetry_ready AS telemetryReady,total_connections AS totalConnections,current_connections AS currentConnections,"
                        + "reported_total_connections AS reportedTotalConnections,pending_connections AS pendingConnections,"
                        + "pending_probe_connections AS pendingProbeConnections,"
                        + "activity_in_flow AS activityInFlow,activity_out_flow AS activityOutFlow,last_activity_at AS lastActivityAt,"
                        + "last_telemetry_at AS lastTelemetryAt "
                        + "FROM smart_entry_route WHERE group_id=? ORDER BY FIELD(carrier,'default','telecom','unicom','mobile')", groupId);
        for (Map<String, Object> row : rows) {
            if (row.get("fallbackCarriers") instanceof String value && !"null".equals(value))
                row.put("fallbackCarriers", JSON.parseArray(value, String.class));
        }
        return rows;
    }

    private List<Map<String, Object>> activities(Long groupId) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT r.forward_id AS forwardId,r.entry_node_id AS entryNodeId,MAX(r.node_name) AS nodeName,"
                        + "MAX(r.entry_address) AS entryAddress,MAX(n.version) AS agentVersion,"
                        + "GROUP_CONCAT(r.carrier ORDER BY FIELD(r.carrier,'default','telecom','unicom','mobile') SEPARATOR ',') AS carriers,"
                        + "MAX(r.telemetry_ready) AS telemetryReady,MAX(r.total_connections) AS totalConnections,"
                        + "MAX(r.current_connections) AS currentConnections,MAX(r.activity_in_flow) AS inFlow,"
                        + "MAX(r.activity_out_flow) AS outFlow,MAX(r.last_activity_at) AS lastActivityAt,MAX(r.last_telemetry_at) AS lastTelemetryAt "
                        + "FROM smart_entry_route r LEFT JOIN node n ON n.id=r.entry_node_id WHERE r.group_id=? "
                        + "GROUP BY r.forward_id,r.entry_node_id ORDER BY MIN(FIELD(r.carrier,'default','telecom','unicom','mobile'))", groupId);
        for (Map<String, Object> row : rows) {
            Long lastTelemetryAt = nullableLong(row.get("lastTelemetryAt"));
            Long lastActivityAt = nullableLong(row.get("lastActivityAt"));
            boolean telemetryReady = truth(row.get("telemetryReady"));
            boolean telemetryLive = lastTelemetryAt != null && now - lastTelemetryAt <= TELEMETRY_LIVE_WINDOW_MS;
            boolean recentlyActive = lastActivityAt != null && now - lastActivityAt <= RECENT_ACTIVITY_WINDOW_MS;
            long currentConnections = number(row.get("currentConnections"));
            row.put("telemetryLive", telemetryLive);
            row.put("recentlyActive", recentlyActive);
            row.put("activityState", activityState(telemetryReady, telemetryLive, recentlyActive, currentConnections));
            row.put("activityHint", activityHint(telemetryReady, telemetryLive, recentlyActive, currentConnections));
        }
        return rows;
    }

    static String activityState(boolean telemetryReady, boolean telemetryLive, boolean recentlyActive, long currentConnections) {
        if (!telemetryReady) return "waiting";
        if (!telemetryLive) return "stale";
        if (currentConnections > 0) return "connected";
        if (recentlyActive) return "active_without_tcp_current";
        return "idle";
    }

    static String activityHint(boolean telemetryReady, boolean telemetryLive, boolean recentlyActive, long currentConnections) {
        String state = activityState(telemetryReady, telemetryLive, recentlyActive, currentConnections);
        return switch (state) {
            case "connected" -> "Agent 正在上报 TCP 活跃连接";
            case "active_without_tcp_current" -> "最近有业务流量，但采样时没有持续 TCP 连接；常见于短连接、UDP 或客户端快速重连";
            case "idle" -> "Agent 上报正常，最近没有检测到业务流量";
            case "stale" -> "Agent 超过 30 秒没有上报实时连接数据";
            default -> "等待新版 Agent 或等待第一次业务流量上报";
        };
    }

    private String activityLabel(long groupId, long forwardId, long nodeId) {
        List<String> carrierKeys = jdbcTemplate.queryForList("SELECT carrier FROM smart_entry_route "
                + "WHERE group_id=? AND forward_id=? AND entry_node_id=? ORDER BY FIELD(carrier,'default','telecom','unicom','mobile')",
                String.class, groupId, forwardId, nodeId);
        String labels = carrierKeys.stream().map(this::carrierLabel).collect(Collectors.joining(" / "));
        Map<String, Object> route = one("SELECT node_name AS nodeName FROM smart_entry_route WHERE group_id=? AND forward_id=? AND entry_node_id=? LIMIT 1",
                groupId, forwardId, nodeId);
        String entryType = carrierKeys.size() > 1 ? "共用入口" : "入口";
        return labels + entryType + " " + Objects.toString(route == null ? null : route.get("nodeName"), "节点" + nodeId) + "：";
    }

    static long connectionDelta(long previous, long reported, boolean baselineReady) {
        if (!baselineReady) return 0L;
        return reported >= previous ? reported - previous : reported;
    }

    static long businessConnectionDelta(long rawConnections, long probeConnections) {
        return Math.max(0L, rawConnections - Math.max(0L, probeConnections));
    }

    static boolean shouldWriteDnsRecord(boolean routeChanged, boolean dirty, Long lastAttemptAt,
                                        boolean recordMissing, int appliedTtl, int expectedTtl, long now) {
        boolean retryDue = dirty && (lastAttemptAt == null || now - lastAttemptAt >= DNS_RETRY_INTERVAL_MS);
        return routeChanged || retryDue || recordMissing || appliedTtl != expectedTtl;
    }

    private String physicalRouteKey(Map<String, Object> route) {
        return number(route.get("forwardId")) + ":" + number(route.get("entryNodeId"));
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void event(Long groupId, String carrier, String type, String status, String detail) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO smart_entry_event (group_id,carrier,event_type,status,detail,created_time) VALUES (?,?,?,?,?,?)",
                groupId, carrier, type, status, shorten(detail), now);
        jdbcTemplate.update("DELETE FROM smart_entry_event WHERE group_id=? AND id NOT IN (SELECT id FROM (SELECT id FROM smart_entry_event WHERE group_id=? ORDER BY created_time DESC LIMIT 200) keep_rows)",
                groupId, groupId);
    }

    private void releaseRecord(Map<String, Object> group, Map<String, Object> route) {
        String recordId = Objects.toString(route.get("recordId"), null);
        long providerId = number(group.get("provider_ref_id"));
        String zone = Objects.toString(group.get("zone_name"));
        if (!truth(route.get("ownershipReady")) && StringUtils.isBlank(recordId)) return;
        List<DynamicDnsService.LineRoutingRecordState> records = dynamicDnsService.inspectLineRoutingRecords(providerId, zone,
                Objects.toString(group.get("domain")), Objects.toString(group.get("record_type"))).stream()
                .filter(record -> record.carrier().equals(route.get("carrier"))).toList();
        if (records.size() > 1) throw new IllegalStateException("清理时发现重复 DNS 线路，需管理员检查");
        if (!records.isEmpty()) {
            DynamicDnsService.LineRoutingRecordState existing = records.get(0);
            if (recordId != null && !recordId.equals(existing.recordId())) throw new IllegalStateException("DNS 记录已被外部替换，暂停自动清理");
            boolean expected = List.of("currentAddress", "entryAddress", "dnsTargetAddress", "originalAddress").stream()
                    .anyMatch(key -> route.get(key) != null && CrossEntryFailoverService.dnsAddressMatches(route.get(key).toString(), existing.value()));
            if (!expected) throw new IllegalStateException("DNS 地址已被外部修改，暂停自动清理");
            recordId = existing.recordId();
        } else recordId = null;
        if (truth(route.get("managedCreated"))) {
            if (recordId != null) dynamicDnsService.deleteLineRoutingRecord(providerId, zone, recordId);
            return;
        }
        String originalAddress = Objects.toString(route.get("originalAddress"), "");
        if (StringUtils.isBlank(originalAddress)) throw new IllegalStateException("无法恢复接管前的 DNS 记录");
        dynamicDnsService.ensureLineRoutingRecord(providerId, zone, Objects.toString(group.get("domain")),
                Objects.toString(group.get("record_type")), Objects.toString(route.get("carrier")), originalAddress,
                route.get("originalTtl") == null ? intValue(group.get("ttl")) : intValue(route.get("originalTtl")), recordId);
    }

    private String carrierLabel(String carrier) {
        return switch (carrier) {
            case "telecom" -> "电信";
            case "unicom" -> "联通";
            case "mobile" -> "移动";
            default -> "默认";
        };
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        return Math.max(min, Math.min(max, value == null ? fallback : value));
    }

    private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private int intValue(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
    private Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private boolean truth(Object value) { return value != null && ("1".equals(value.toString()) || Boolean.parseBoolean(value.toString())); }
    private String shorten(String value) { return StringUtils.abbreviate(StringUtils.defaultIfBlank(value, "操作失败"), 500); }

    private record Probe(boolean healthy, Integer latencyMs, String error) { }
    private record Normalized(String name, long providerId, String provider, String zoneName, String domain,
                              String recordType, int ttl, int publicPort, int probeIntervalMs, int connectTimeoutMs,
                              int failureThreshold, int recoveryThreshold, boolean enabled, int recoveryStableMs,
                              int switchCooldownMs, String probeMode, String probePath, List<NormalizedRoute> routes) { }
    private record NormalizedRoute(String carrier, long forwardId, long nodeId, String entryHost, String entryAddress,
                                   int entryPort, String forwardName, String nodeName, List<String> fallbackCarriers) { }
}
