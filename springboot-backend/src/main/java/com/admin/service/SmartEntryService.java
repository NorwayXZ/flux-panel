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

    private final JdbcTemplate jdbcTemplate;
    private final DynamicDnsService dynamicDnsService;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public SmartEntryService(JdbcTemplate jdbcTemplate, DynamicDnsService dynamicDnsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dynamicDnsService = dynamicDnsService;
    }

    public R overview() {
        List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                "SELECT g.id,g.name,g.provider_ref_id AS providerRefId,g.provider,p.name AS providerName,g.zone_name AS zoneName,"
                        + "g.domain,g.record_type AS recordType,g.ttl,g.public_port AS publicPort,g.probe_interval_ms AS probeIntervalMs,"
                        + "g.connect_timeout_ms AS connectTimeoutMs,g.failure_threshold AS failureThreshold,g.recovery_threshold AS recoveryThreshold,"
                        + "g.enabled,g.state,g.last_error AS lastError,g.last_checked_at AS lastCheckedAt,g.created_time AS createdTime "
                        + "FROM smart_entry_group g LEFT JOIN dynamic_dns_provider p ON p.id=g.provider_ref_id ORDER BY g.created_time DESC");
        for (Map<String, Object> group : groups) {
            group.put("routes", routes(number(group.get("id"))));
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

    public R save(SmartEntrySaveDto dto) {
        try {
            Normalized normalized = normalize(dto);
            long now = System.currentTimeMillis();
            Long id = dto.getId();
            List<Map<String, Object>> oldRoutes = id == null ? List.of() : routes(id);
            Map<String, Object> oldGroup = id == null ? null : one("SELECT * FROM smart_entry_group WHERE id=?", id);
            if (id != null && oldGroup == null) return R.err("入口接入策略不存在");

            Integer duplicate = id == null
                    ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM smart_entry_group WHERE provider_ref_id=? AND zone_name=? AND domain=? AND record_type=?",
                    Integer.class, normalized.providerId, normalized.zoneName, normalized.domain, normalized.recordType)
                    : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM smart_entry_group WHERE provider_ref_id=? AND zone_name=? AND domain=? AND record_type=? AND id<>?",
                    Integer.class, normalized.providerId, normalized.zoneName, normalized.domain, normalized.recordType, id);
            if (duplicate != null && duplicate > 0) return R.err("该业务域名已经配置入口接入");

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
            }
            try {
                syncRecords(id, "配置同步");
                return R.ok(Map.of("id", id));
            } catch (RuntimeException e) {
                String message = shorten(e.getMessage());
                jdbcTemplate.update("UPDATE smart_entry_group SET state='error',last_error=?,updated_time=? WHERE id=?", message, now, id);
                event(id, null, "dns_error", "failed", message);
                return R.err("策略已保存，但 DNS 同步失败：" + message);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.err(e.getMessage());
        }
    }

    public R checkNow(Long id) {
        if (one("SELECT id FROM smart_entry_group WHERE id=?", id) == null) return R.err("入口接入策略不存在");
        try {
            checkGroup(id, true);
            return overview();
        } catch (RuntimeException e) {
            return R.err(shorten(e.getMessage()));
        }
    }

    public R events(Long id) {
        return R.ok(jdbcTemplate.queryForList(
                "SELECT id,carrier,event_type AS eventType,status,detail,created_time AS createdTime FROM smart_entry_event "
                        + "WHERE group_id=? ORDER BY created_time DESC LIMIT 200", id));
    }

    public R delete(Long id) {
        Map<String, Object> group = one("SELECT * FROM smart_entry_group WHERE id=?", id);
        if (group == null) return R.err("入口接入策略不存在");
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
            List<CompletableFuture<Probe>> futures = routes.stream()
                    .map(route -> CompletableFuture.supplyAsync(() -> probe(route, timeout)))
                    .toList();
            for (int index = 0; index < routes.size(); index++) {
                Map<String, Object> route = routes.get(index);
                Probe probe = futures.get(index).join();
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
            syncRecords(id, manual ? "手动检测" : "健康检测");
        }
    }

    private Probe probe(Map<String, Object> route, int timeout) {
        long nodeId = number(route.get("entryNodeId"));
        if (!WebSocketServer.isNodeOnline(nodeId)) return new Probe(false, null, "入口 Agent 离线");
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(Objects.toString(route.get("entryAddress")), intValue(route.get("entryPort"))), timeout);
            return new Probe(true, Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000)), null);
        } catch (Exception e) {
            return new Probe(false, null, "端口连接失败");
        }
    }

    private void syncRecords(Long groupId, String reason) {
        Map<String, Object> group = one("SELECT * FROM smart_entry_group WHERE id=?", groupId);
        if (group == null) return;
        List<Map<String, Object>> all = routes(groupId);
        if (all.isEmpty()) return;
        Map<String, Object> defaultRoute = all.stream().filter(item -> "default".equals(item.get("carrier"))).findFirst().orElse(all.get(0));
        List<Map<String, Object>> healthy = all.stream().filter(item -> !"unhealthy".equals(item.get("status"))).toList();
        for (Map<String, Object> line : all) {
            Map<String, Object> desired = !"unhealthy".equals(line.get("status")) ? line
                    : (!"unhealthy".equals(defaultRoute.get("status")) ? defaultRoute : (healthy.isEmpty() ? line : healthy.get(0)));
            String desiredAddress = Objects.toString(desired.get("entryAddress"));
            long desiredForward = number(desired.get("forwardId"));
            DynamicDnsService.LineRoutingRecord record = dynamicDnsService.ensureLineRoutingRecord(number(group.get("provider_ref_id")),
                    Objects.toString(group.get("zone_name")), Objects.toString(group.get("domain")),
                    Objects.toString(group.get("record_type")), Objects.toString(line.get("carrier")), desiredAddress,
                    intValue(group.get("ttl")), Objects.toString(line.get("recordId"), null));
            boolean changed = !Objects.equals(desiredForward, nullableLong(line.get("currentForwardId")))
                    || !desiredAddress.equals(Objects.toString(line.get("currentAddress"), ""));
            jdbcTemplate.update("UPDATE smart_entry_route SET managed_created=IF(record_id IS NULL,?,managed_created),"
                            + "original_address=IF(record_id IS NULL,?,original_address),original_ttl=IF(record_id IS NULL,?,original_ttl),"
                            + "record_id=?,current_forward_id=?,current_address=?,updated_time=? WHERE id=?",
                    record.created(), record.originalValue(), record.originalTtl(), record.recordId(), desiredForward,
                    desiredAddress, System.currentTimeMillis(), number(line.get("id")));
            if (changed) {
                event(groupId, Objects.toString(line.get("carrier")), "route_switch", "success",
                        reason + "：" + carrierLabel(Objects.toString(line.get("carrier"))) + "线路切换到 " + Objects.toString(desired.get("nodeName")));
            }
        }
        long unhealthy = all.stream().filter(item -> "unhealthy".equals(item.get("status"))).count();
        String state = unhealthy == 0 ? "healthy" : (unhealthy == all.size() ? "offline" : "degraded");
        jdbcTemplate.update("UPDATE smart_entry_group SET state=?,last_error=NULL,updated_time=? WHERE id=?", state, System.currentTimeMillis(), groupId);
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
            throw new IllegalArgumentException("入口接入至少需要两台不同的公网入口节点");
        }
        int port = routes.get(0).entryPort;
        if (routes.stream().anyMatch(route -> route.entryPort != port)) throw new IllegalArgumentException("所有入口转发必须使用相同公网端口");
        return new Normalized(StringUtils.trim(dto.getName()), dto.getProviderRefId(), Objects.toString(provider.get("provider")), zone,
                domain, type, clamp(dto.getTtl(), 60, 86400, 60), port,
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
                        + "current_forward_id AS currentForwardId,current_address AS currentAddress,status,fail_count AS failCount,"
                        + "success_count AS successCount,latency_ms AS latencyMs,last_error AS lastError,last_checked_at AS lastCheckedAt "
                        + "FROM smart_entry_route WHERE group_id=? ORDER BY FIELD(carrier,'default','telecom','unicom','mobile')", groupId);
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
