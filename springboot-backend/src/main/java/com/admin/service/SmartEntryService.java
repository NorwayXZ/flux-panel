package com.admin.service;

import com.admin.common.dto.SmartEntrySaveDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    private final JdbcTemplate jdbcTemplate;
    private final DynamicDnsService dynamicDnsService;
    private final SchedulingConflictService schedulingConflictService;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> activityGroups = new ConcurrentHashMap<>();

    public SmartEntryService(JdbcTemplate jdbcTemplate, DynamicDnsService dynamicDnsService,
                             SchedulingConflictService schedulingConflictService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dynamicDnsService = dynamicDnsService;
        this.schedulingConflictService = schedulingConflictService;
    }

    public R overview() {
        List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                "SELECT g.id,g.name,g.provider_ref_id AS providerRefId,g.provider,p.name AS providerName,g.zone_name AS zoneName,"
                        + "g.domain,g.record_type AS recordType,g.ttl,g.public_port AS publicPort,g.probe_interval_ms AS probeIntervalMs,"
                        + "g.connect_timeout_ms AS connectTimeoutMs,g.failure_threshold AS failureThreshold,g.recovery_threshold AS recoveryThreshold,"
                        + "g.enabled,g.state,g.last_error AS lastError,g.last_checked_at AS lastCheckedAt,g.created_time AS createdTime "
                        + "FROM smart_entry_group g LEFT JOIN dynamic_dns_provider p ON p.id=g.provider_ref_id ORDER BY g.created_time DESC");
        for (Map<String, Object> group : groups) {
            long groupId = number(group.get("id"));
            group.put("routes", routes(groupId));
            group.put("activities", activities(groupId));
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
                        + "WHERE f.status=1 AND COALESCE(f.protocol_mode,'tcp') IN ('tcp','tcp_udp') ORDER BY f.created_time DESC");
        return R.ok(Map.of("providers", providers, "forwards", forwards));
    }

    public R domains(Long providerRefId) {
        return dynamicDnsService.lineRoutingDomains(providerRefId);
    }

    public R save(SmartEntrySaveDto dto) {
        try {
            Normalized normalized = normalize(dto);
            Object lock = dto.getId() == null ? new Object() : locks.computeIfAbsent(dto.getId(), ignored -> new Object());
            synchronized (lock) {
                return saveLocked(dto, normalized);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.err(e.getMessage());
        }
    }

    private R saveLocked(SmartEntrySaveDto dto, Normalized normalized) {
        long now = System.currentTimeMillis();
        Long id = dto.getId();
            List<Map<String, Object>> oldRoutes = id == null ? List.of() : routes(id);
            Map<String, Object> oldGroup = id == null ? null : one("SELECT * FROM smart_entry_group WHERE id=?", id);
            if (id != null && oldGroup == null) return R.err("三网优化策略不存在");

            Integer duplicate = id == null
                    ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM smart_entry_group WHERE provider_ref_id=? AND zone_name=? AND domain=? AND record_type=?",
                    Integer.class, normalized.providerId, normalized.zoneName, normalized.domain, normalized.recordType)
                    : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM smart_entry_group WHERE provider_ref_id=? AND zone_name=? AND domain=? AND record_type=? AND id<>?",
                    Integer.class, normalized.providerId, normalized.zoneName, normalized.domain, normalized.recordType, id);
            if (duplicate != null && duplicate > 0) return R.err("该业务域名已经配置三网优化");
            schedulingConflictService.assertDnsRecordAvailable("smart_entry", id, normalized.domain, normalized.recordType);
            schedulingConflictService.assertForwardSetAvailable("smart_entry", id,
                    normalized.routes.stream().map(route -> route.forwardId).collect(Collectors.toList()));
            schedulingConflictService.assertForwardBackedTunnelSetAvailable("smart_entry", id,
                    normalized.routes.stream().map(route -> route.forwardId).collect(Collectors.toList()));

            boolean sameIdentity = oldGroup != null
                    && normalized.providerId == number(oldGroup.get("provider_ref_id"))
                    && normalized.zoneName.equalsIgnoreCase(Objects.toString(oldGroup.get("zone_name")))
                    && normalized.domain.equalsIgnoreCase(Objects.toString(oldGroup.get("domain")))
                    && normalized.recordType.equalsIgnoreCase(Objects.toString(oldGroup.get("record_type")));
            Map<String, Map<String, Object>> retainedRecords = new HashMap<>();
            if (sameIdentity) {
                Set<String> requestedCarriers = normalized.routes.stream().map(item -> item.carrier).collect(Collectors.toSet());
                for (Map<String, Object> old : oldRoutes) {
                    String carrier = Objects.toString(old.get("carrier"));
                    if (requestedCarriers.contains(carrier)) retainedRecords.put(carrier, old);
                    else releaseRecord(oldGroup, old);
                }
            } else if (oldGroup != null) {
                for (Map<String, Object> old : oldRoutes) {
                    releaseRecord(oldGroup, old);
                }
            }

            if (id == null) {
                jdbcTemplate.update("INSERT INTO smart_entry_group (user_id,name,provider_ref_id,provider,zone_name,domain,record_type,ttl,public_port,"
                                + "probe_interval_ms,connect_timeout_ms,failure_threshold,recovery_threshold,enabled,state,created_time,updated_time) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'unknown',?,?)",
                        JwtUtil.getUserIdFromToken(), normalized.name, normalized.providerId, normalized.provider,
                        normalized.zoneName, normalized.domain, normalized.recordType, normalized.ttl, normalized.publicPort,
                        normalized.probeIntervalMs, normalized.connectTimeoutMs, normalized.failureThreshold,
                        normalized.recoveryThreshold, normalized.enabled, now, now);
                id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            } else {
                jdbcTemplate.update("UPDATE smart_entry_group SET name=?,provider_ref_id=?,provider=?,zone_name=?,domain=?,record_type=?,ttl=?,public_port=?,"
                                + "probe_interval_ms=?,connect_timeout_ms=?,failure_threshold=?,recovery_threshold=?,enabled=?,state='unknown',last_error=NULL,updated_time=? WHERE id=?",
                        normalized.name, normalized.providerId, normalized.provider, normalized.zoneName, normalized.domain,
                        normalized.recordType, normalized.ttl, normalized.publicPort, normalized.probeIntervalMs,
                        normalized.connectTimeoutMs, normalized.failureThreshold, normalized.recoveryThreshold,
                        normalized.enabled, now, id);
                jdbcTemplate.update("DELETE FROM smart_entry_route WHERE group_id=?", id);
            }

            for (NormalizedRoute route : normalized.routes) {
                Map<String, Object> retained = retainedRecords.get(route.carrier);
                jdbcTemplate.update("INSERT INTO smart_entry_route (group_id,carrier,forward_id,entry_node_id,entry_host,entry_address,entry_port,"
                                + "forward_name,node_name,record_id,managed_created,original_address,original_ttl,current_forward_id,current_address,status,created_time,updated_time) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'unknown',?,?)",
                        id, route.carrier, route.forwardId, route.nodeId, route.entryHost, route.entryAddress, route.entryPort,
                        route.forwardName, route.nodeName, retained == null ? null : retained.get("recordId"),
                        retained == null || truth(retained.get("managedCreated")), retained == null ? null : retained.get("originalAddress"),
                        retained == null ? null : retained.get("originalTtl"), route.forwardId, route.entryAddress, now, now);
                if (retained != null) {
                    jdbcTemplate.update("UPDATE smart_entry_route SET telemetry_ready=?,total_connections=?,current_connections=?,"
                                    + "reported_total_connections=?,pending_connections=?,pending_probe_connections=?,activity_in_flow=?,activity_out_flow=?,"
                                    + "last_activity_at=?,last_telemetry_at=? WHERE group_id=? AND carrier=?",
                            retained.get("telemetryReady"), retained.get("totalConnections"), retained.get("currentConnections"),
                            retained.get("reportedTotalConnections"), retained.get("pendingConnections"), retained.get("pendingProbeConnections"),
                            retained.get("activityInFlow"), retained.get("activityOutFlow"), retained.get("lastActivityAt"),
                            retained.get("lastTelemetryAt"), id, route.carrier);
                }
            }
            activityGroups.clear();
            try {
                syncRecords(id, "配置同步");
                return R.ok(Map.of("id", id));
            } catch (RuntimeException e) {
                String message = shorten(e.getMessage());
                jdbcTemplate.update("UPDATE smart_entry_group SET state='error',last_error=?,updated_time=? WHERE id=?", message, now, id);
                event(id, null, "dns_error", "failed", message);
                return R.err("策略已保存，但 DNS 同步失败：" + message);
            }
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
                List<DynamicDnsService.LineRoutingRecordState> effective = inherited
                        ? providerByCarrier.getOrDefault("default", List.of()) : direct;
                DynamicDnsService.LineRoutingRecordState providerRecord = effective.size() == 1 ? effective.get(0) : null;
                DynamicDnsService.PublicDnsProbe publicProbe = probes.get(recordType + ":" + carrier).join();
                boolean providerMatch = providerRecord != null && providerRecord.enabled()
                        && expected.equals(providerRecord.value()) && providerRecord.ttl() == intValue(group.get("ttl"));
                boolean publicMatch = publicProbe.successful() && publicProbe.answers().contains(expected);
                if (providerMatch) providerMatches++;
                if (publicMatch) publicMatches++;
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("carrier", carrier);
                line.put("inherited", inherited);
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
                    "sibling", sibling, "summary", summary));
        } catch (RuntimeException e) {
            return R.err("DNS 线路诊断失败：" + shorten(e.getMessage()));
        }
    }

    public R events(Long id) {
        return R.ok(jdbcTemplate.queryForList(
                "SELECT id,carrier,event_type AS eventType,status,detail,created_time AS createdTime FROM smart_entry_event "
                        + "WHERE group_id=? AND event_type IN ('route_switch','first_active','resumed','new_connections') "
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
        Map<String, Object> group = one("SELECT * FROM smart_entry_group WHERE id=?", id);
        if (group == null) return R.err("三网优化策略不存在");
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> route : routes(id)) {
            try {
                releaseRecord(group, route);
            } catch (RuntimeException e) {
                failures.add(carrierLabel(Objects.toString(route.get("carrier"))) + "记录释放失败");
            }
        }
        if (!failures.isEmpty()) return R.err(String.join("；", failures) + "，请先检查 DNS 凭据后重试");
        jdbcTemplate.update("DELETE FROM smart_entry_event WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM smart_entry_route WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM smart_entry_group WHERE id=?", id);
        activityGroups.clear();
        return R.ok();
    }

    @Scheduled(initialDelay = 20_000L, fixedDelay = 2_000L)
    public void poll() {
        if (!checking.compareAndSet(false, true)) return;
        try {
            long now = System.currentTimeMillis();
            List<Long> ids = jdbcTemplate.query(
                    "SELECT id FROM smart_entry_group WHERE enabled=1 AND (last_checked_at IS NULL OR last_checked_at+probe_interval_ms<=?) "
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
            if (group == null) return;
            List<Map<String, Object>> routes = routes(id);
            int timeout = intValue(group.get("connect_timeout_ms"));
            int failureThreshold = intValue(group.get("failure_threshold"));
            int recoveryThreshold = intValue(group.get("recovery_threshold"));
            long now = System.currentTimeMillis();
            Map<String, CompletableFuture<Probe>> futures = new LinkedHashMap<>();
            for (Map<String, Object> route : routes) {
                futures.computeIfAbsent(physicalRouteKey(route), ignored ->
                        CompletableFuture.supplyAsync(() -> probe(id, route, timeout)));
            }
            for (int index = 0; index < routes.size(); index++) {
                Map<String, Object> route = routes.get(index);
                Probe probe = futures.get(physicalRouteKey(route)).join();
                String oldStatus = Objects.toString(route.get("status"), "unknown");
                int failures = intValue(route.get("failCount"));
                int successes = intValue(route.get("successCount"));
                String newStatus = oldStatus;
                if (probe.healthy) {
                    failures = 0;
                    successes++;
                    if (!"healthy".equals(oldStatus) && successes >= recoveryThreshold) newStatus = "healthy";
                } else {
                    successes = 0;
                    failures++;
                    if (failures >= failureThreshold) newStatus = "unhealthy";
                }
                jdbcTemplate.update("UPDATE smart_entry_route SET status=?,fail_count=?,success_count=?,latency_ms=?,last_error=?,last_checked_at=?,updated_time=? WHERE id=?",
                        newStatus, failures, successes, probe.latencyMs, probe.error, now, now, number(route.get("id")));
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

    private Probe probe(Long groupId, Map<String, Object> route, int timeout) {
        long nodeId = number(route.get("entryNodeId"));
        if (!WebSocketServer.isNodeOnline(nodeId)) return new Probe(false, null, "入口 Agent 离线");
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(Objects.toString(route.get("entryAddress")), intValue(route.get("entryPort"))), timeout);
            jdbcTemplate.update("UPDATE smart_entry_route SET pending_probe_connections=pending_probe_connections+1 "
                            + "WHERE group_id=? AND forward_id=? AND entry_node_id=?",
                    groupId, number(route.get("forwardId")), nodeId);
            return new Probe(true, Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000)), null);
        } catch (Exception e) {
            return new Probe(false, null, "端口连接失败");
        }
    }

    private void syncRecords(Long groupId, String reason) {
        syncRecords(groupId, reason, false);
    }

    private void syncRecords(Long groupId, String reason, boolean forceVerify) {
        Map<String, Object> group = one("SELECT * FROM smart_entry_group WHERE id=?", groupId);
        if (group == null) return;
        List<Map<String, Object>> all = routes(groupId);
        if (all.isEmpty()) return;
        Map<String, Object> defaultRoute = all.stream().filter(item -> "default".equals(item.get("carrier"))).findFirst().orElse(all.get(0));
        List<Map<String, Object>> healthy = all.stream().filter(item -> !"unhealthy".equals(item.get("status"))).toList();
        int ttl = intValue(group.get("ttl"));
        long now = System.currentTimeMillis();
        boolean recordsChanged = false;
        Map<String, String> expectedAddresses = new LinkedHashMap<>();
        for (Map<String, Object> line : all) {
            Map<String, Object> desired = !"unhealthy".equals(line.get("status")) ? line
                    : (!"unhealthy".equals(defaultRoute.get("status")) ? defaultRoute : (healthy.isEmpty() ? line : healthy.get(0)));
            String desiredAddress = Objects.toString(desired.get("entryAddress"));
            long desiredForward = number(desired.get("forwardId"));
            boolean changed = !Objects.equals(desiredForward, nullableLong(line.get("currentForwardId")))
                    || !desiredAddress.equals(Objects.toString(line.get("currentAddress"), ""));
            Long lastDnsVerification = nullableLong(line.get("dnsVerifiedAt"));
            boolean needsWrite = shouldWriteDnsRecord(changed, truth(line.get("dnsDirty")), lastDnsVerification,
                    StringUtils.isBlank(Objects.toString(line.get("recordId"), null)),
                    intValue(line.get("appliedTtl")), ttl, now);
            expectedAddresses.put(Objects.toString(line.get("carrier")), desiredAddress);
            if (needsWrite) {
                DynamicDnsService.LineRoutingRecord record;
                try {
                    record = dynamicDnsService.ensureLineRoutingRecord(number(group.get("provider_ref_id")),
                            Objects.toString(group.get("zone_name")), Objects.toString(group.get("domain")),
                            Objects.toString(group.get("record_type")), Objects.toString(line.get("carrier")), desiredAddress,
                            ttl, Objects.toString(line.get("recordId"), null));
                } catch (RuntimeException e) {
                    String message = shorten(e.getMessage());
                    jdbcTemplate.update("UPDATE smart_entry_route SET dns_dirty=1,dns_state='error',dns_error=?,dns_verified_at=?,updated_time=? WHERE id=?",
                            message, now, now, number(line.get("id")));
                    jdbcTemplate.update("UPDATE smart_entry_route SET dns_verified_at=?,updated_time=? WHERE group_id=? AND dns_dirty=1",
                            now, now, groupId);
                    jdbcTemplate.update("UPDATE smart_entry_group SET state='error',last_error=?,updated_time=? WHERE id=?",
                            message, now, number(group.get("id")));
                    throw e;
                }
                jdbcTemplate.update("UPDATE smart_entry_route SET managed_created=IF(record_id IS NULL,?,managed_created),"
                                + "original_address=IF(record_id IS NULL,?,original_address),original_ttl=IF(record_id IS NULL,?,original_ttl),"
                                + "record_id=?,current_forward_id=?,current_address=?,applied_ttl=?,dns_dirty=1,dns_state='pending',"
                                + "dns_error=NULL,updated_time=? WHERE id=?",
                        record.created(), record.originalValue(), record.originalTtl(), record.recordId(), desiredForward,
                        desiredAddress, ttl, now, number(line.get("id")));
                recordsChanged = true;
            }
            if (changed) {
                event(groupId, Objects.toString(line.get("carrier")), "route_switch", "success",
                        reason + "：" + carrierLabel(Objects.toString(line.get("carrier"))) + "线路切换到 " + Objects.toString(desired.get("nodeName")));
            }
        }

        long oldestVerification = all.stream().map(item -> nullableLong(item.get("dnsVerifiedAt")))
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(0L);
        if (recordsChanged || forceVerify || oldestVerification == 0L || now - oldestVerification >= DNS_VERIFY_INTERVAL_MS) {
            verifyProviderRecords(group, all, expectedAddresses, ttl, now);
        }
        Integer dnsFailures = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_entry_route WHERE group_id=? AND (dns_dirty=1 OR dns_state='error')",
                Integer.class, groupId);
        if (dnsFailures != null && dnsFailures > 0) {
            String dnsError = jdbcTemplate.query(
                    "SELECT dns_error FROM smart_entry_route WHERE group_id=? AND dns_error IS NOT NULL ORDER BY updated_time DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : "DNS 线路等待重新同步", groupId);
            jdbcTemplate.update("UPDATE smart_entry_group SET state='error',last_error=?,updated_time=? WHERE id=?",
                    shorten(dnsError), now, groupId);
            return;
        }
        long unhealthy = all.stream().filter(item -> "unhealthy".equals(item.get("status"))).count();
        String state = unhealthy == 0 ? "healthy" : (unhealthy == all.size() ? "offline" : "degraded");
        jdbcTemplate.update("UPDATE smart_entry_group SET state=?,last_error=NULL,updated_time=? WHERE id=?", state, now, groupId);
    }

    private void verifyProviderRecords(Map<String, Object> group, List<Map<String, Object>> routes,
                                       Map<String, String> expectedAddresses, int ttl, long now) {
        List<DynamicDnsService.LineRoutingRecordState> states;
        try {
            states = dynamicDnsService.inspectLineRoutingRecords(number(group.get("provider_ref_id")),
                    Objects.toString(group.get("zone_name")), Objects.toString(group.get("domain")),
                    Objects.toString(group.get("record_type")));
        } catch (RuntimeException e) {
            jdbcTemplate.update("UPDATE smart_entry_route SET dns_dirty=1,dns_state='error',dns_error=?,dns_verified_at=?,updated_time=? WHERE group_id=?",
                    shorten(e.getMessage()), now, now, number(group.get("id")));
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
                    : !expected.equals(actual.value()) ? "服务商返回地址与目标入口不一致"
                    : actual.ttl() != ttl ? "服务商实际 TTL 与面板不一致"
                    : null;
            if (error == null) {
                jdbcTemplate.update("UPDATE smart_entry_route SET record_id=?,dns_dirty=0,dns_state='healthy',dns_error=NULL,dns_verified_at=?,updated_time=? WHERE id=?",
                        actual.recordId(), now, now, number(route.get("id")));
            } else {
                failures.add(carrierLabel(carrier) + "：" + error);
                jdbcTemplate.update("UPDATE smart_entry_route SET dns_dirty=1,dns_state='error',dns_error=?,dns_verified_at=?,updated_time=? WHERE id=?",
                        error, now, now, number(route.get("id")));
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
        if (dto.getProviderRefId() == null) throw new IllegalArgumentException("请选择 DNSPod 或阿里云 DNS 配置");
        Map<String, Object> provider = one("SELECT id,provider FROM dynamic_dns_provider WHERE id=? AND enabled=1", dto.getProviderRefId());
        if (provider == null || !List.of("dnspod", "aliyun").contains(Objects.toString(provider.get("provider")))) {
            throw new IllegalArgumentException("运营商线路解析仅支持已启用的 DNSPod 或阿里云 DNS 配置");
        }
        String zone = StringUtils.trimToEmpty(dto.getZoneName()).toLowerCase(Locale.ROOT);
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
                            + "FROM forward f JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id WHERE f.id=? AND f.status=1",
                    assignment.getForwardId());
            if (forward == null || !List.of("tcp", "tcp_udp").contains(Objects.toString(forward.get("protocolMode"), "tcp"))) {
                throw new IllegalArgumentException(carrierLabel(carrier) + "入口转发不存在、已暂停或不支持 TCP 检测");
            }
            String host = Objects.toString(forward.get("entryHost"), "");
            String address = resolve(host, type);
            routes.add(new NormalizedRoute(carrier, number(forward.get("id")), number(forward.get("inNodeId")), host, address,
                    intValue(forward.get("inPort")), Objects.toString(forward.get("name")), Objects.toString(forward.get("nodeName"))));
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
        int minimumTtl = DynamicDnsService.lineRoutingMinimumTtl(providerName);
        return new Normalized(StringUtils.trim(dto.getName()), dto.getProviderRefId(), providerName, zone,
                domain, type, clamp(dto.getTtl(), minimumTtl, 86400, minimumTtl), port,
                clamp(dto.getProbeIntervalMs(), 2000, 60000, 5000), clamp(dto.getConnectTimeoutMs(), 300, 10000, 1500),
                clamp(dto.getFailureThreshold(), 1, 10, 2), clamp(dto.getRecoveryThreshold(), 1, 10, 3),
                !Boolean.FALSE.equals(dto.getEnabled()), routes);
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
        return jdbcTemplate.queryForList(
                "SELECT id,group_id AS groupId,carrier,forward_id AS forwardId,entry_node_id AS entryNodeId,entry_host AS entryHost,"
                        + "entry_address AS entryAddress,entry_port AS entryPort,forward_name AS forwardName,node_name AS nodeName,record_id AS recordId,"
                        + "managed_created AS managedCreated,original_address AS originalAddress,original_ttl AS originalTtl,"
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
    }

    private List<Map<String, Object>> activities(Long groupId) {
        return jdbcTemplate.queryForList("SELECT r.forward_id AS forwardId,r.entry_node_id AS entryNodeId,MAX(r.node_name) AS nodeName,"
                        + "MAX(r.entry_address) AS entryAddress,MAX(n.version) AS agentVersion,"
                        + "GROUP_CONCAT(r.carrier ORDER BY FIELD(r.carrier,'default','telecom','unicom','mobile') SEPARATOR ',') AS carriers,"
                        + "MAX(r.telemetry_ready) AS telemetryReady,MAX(r.total_connections) AS totalConnections,"
                        + "MAX(r.current_connections) AS currentConnections,MAX(r.activity_in_flow) AS inFlow,"
                        + "MAX(r.activity_out_flow) AS outFlow,MAX(r.last_activity_at) AS lastActivityAt,MAX(r.last_telemetry_at) AS lastTelemetryAt "
                        + "FROM smart_entry_route r LEFT JOIN node n ON n.id=r.entry_node_id WHERE r.group_id=? "
                        + "GROUP BY r.forward_id,r.entry_node_id ORDER BY MIN(FIELD(r.carrier,'default','telecom','unicom','mobile'))", groupId);
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
        if (StringUtils.isBlank(recordId)) return;
        long providerId = number(group.get("provider_ref_id"));
        String zone = Objects.toString(group.get("zone_name"));
        if (truth(route.get("managedCreated"))) {
            dynamicDnsService.deleteLineRoutingRecord(providerId, zone, recordId);
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
                              int failureThreshold, int recoveryThreshold, boolean enabled, List<NormalizedRoute> routes) { }
    private record NormalizedRoute(String carrier, long forwardId, long nodeId, String entryHost, String entryAddress,
                                   int entryPort, String forwardName, String nodeName) { }
}
