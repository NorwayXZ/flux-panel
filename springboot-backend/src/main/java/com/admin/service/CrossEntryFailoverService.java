package com.admin.service;

import com.admin.common.dto.CrossEntryFailoverSaveDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.CrossEntryFailoverPolicy;
import com.admin.common.utils.CrossEntryQualityEvaluator;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PreDestroy;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CrossEntryFailoverService {
    private static final String CF_API = "https://api.cloudflare.com/client/v4";
    private static final int MAX_GROUPS_PER_TICK = 50;
    private static final String MIN_REMOTE_QUALITY_VERSION = "2.19.0";

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final TelegramNotificationService telegramNotificationService;
    private final DnsProviderService dnsProviderService;
    private final ExecutorService probeExecutor = boundedExecutor(8, 64, "cross-entry-probe");
    private final ExecutorService groupExecutor = boundedExecutor(4, 100, "cross-entry-group");
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final Map<Long, Object> groupLocks = new ConcurrentHashMap<>();
    private final Set<Long> inFlightGroups = ConcurrentHashMap.newKeySet();

    @Value("${jwt-secret}")
    private String encryptionSecret;

    public CrossEntryFailoverService(JdbcTemplate jdbcTemplate, RestTemplate restTemplate,
                                     TelegramNotificationService telegramNotificationService,
                                     DnsProviderService dnsProviderService) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.telegramNotificationService = telegramNotificationService;
        this.dnsProviderService = dnsProviderService;
    }

    public R listEligibleForwards() {
        return R.ok(jdbcTemplate.queryForList("SELECT f.id, f.name, f.in_port AS inPort, f.protocol_mode AS protocolMode, "
                + "f.status, t.in_node_id AS inNodeId, COALESCE(n.name, CONCAT('节点', t.in_node_id)) AS nodeName, "
                + "COALESCE(NULLIF(n.server_ip,''), n.ip, t.in_ip) AS entryHost, t.name AS tunnelName "
                + "FROM forward f JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id "
                + "WHERE f.status=1 AND COALESCE(f.protocol_mode,'tcp') IN ('tcp','tcp_udp') "
                + "ORDER BY f.created_time DESC"));
    }

    public R listProbeSources() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", jdbcTemplate.queryForList(
                "SELECT id,name,COALESCE(NULLIF(server_ip,''),ip) AS address,status,version "
                        + "FROM node ORDER BY status DESC,name,id"));
        result.put("connectors", jdbcTemplate.queryForList(
                "SELECT id,name,platform,version,remote_ip AS remoteIp,last_seen AS lastSeen,status "
                        + "FROM internal_connector WHERE status=1 ORDER BY last_seen DESC,name,id"));
        result.put("minimumRemoteVersion", MIN_REMOTE_QUALITY_VERSION);
        return R.ok(result);
    }

    public R listGroups() {
        List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                "SELECT g.id,g.name,g.domain,g.dns_zone_id AS dnsZoneId,g.zone_id AS zoneId,z.zone_name AS zoneName,g.record_id AS recordId,g.record_type AS recordType,g.ttl,"
                        + "probe_interval_ms AS probeIntervalMs,connect_timeout_ms AS connectTimeoutMs,"
                        + "failure_threshold AS failureThreshold,recovery_threshold AS recoveryThreshold,"
                        + "cooldown_seconds AS cooldownSeconds,auto_failback AS autoFailback,routing_mode AS routingMode,enabled,state,active_member_id AS activeMemberId,"
                        + "quality_enabled AS qualityEnabled,quality_probe_source_type AS qualityProbeSourceType,quality_probe_source_id AS qualityProbeSourceId,"
                        + "quality_probe_count AS qualityProbeCount,quality_degrade_threshold_ms AS qualityDegradeThresholdMs,"
                        + "quality_recover_threshold_ms AS qualityRecoverThresholdMs,quality_degrade_factor AS qualityDegradeFactor,quality_recover_factor AS qualityRecoverFactor,"
                        + "quality_degrade_samples AS qualityDegradeSamples,quality_recover_samples AS qualityRecoverSamples,"
                        + "quality_loss_threshold_percent AS qualityLossThresholdPercent,quality_probe_status AS qualityProbeStatus,"
                        + "quality_probe_error AS qualityProbeError,quality_probe_at AS qualityProbeAt,"
                        + "last_error AS lastError,last_checked_at AS lastCheckedAt,last_switch_at AS lastSwitchAt,g.created_time AS createdTime,"
                        + "CASE WHEN g.api_token IS NULL OR g.api_token='' THEN 0 ELSE 1 END AS apiTokenConfigured "
                        + "FROM cross_entry_failover_group g LEFT JOIN dns_zone z ON z.id=g.dns_zone_id ORDER BY g.created_time DESC");
        for (Map<String, Object> group : groups) {
            long id = number(group.get("id")).longValue();
            group.put("members", loadMembers(id));
            List<Map<String, Object>> latestSwitch = jdbcTemplate.queryForList(
                    "SELECT e.id,e.reason,e.status,e.detail,e.created_time AS createdTime,"
                            + "COALESCE(e.from_node_name,fm.node_name) AS fromNodeName,"
                            + "COALESCE(e.to_node_name,tm.node_name) AS toNodeName,"
                            + "fm.forward_name AS fromForwardName,fm.entry_address AS fromEntryAddress,fm.entry_port AS fromEntryPort,"
                            + "tm.forward_name AS toForwardName,tm.entry_address AS toEntryAddress,tm.entry_port AS toEntryPort "
                            + "FROM cross_entry_failover_event e "
                            + "LEFT JOIN cross_entry_failover_member fm ON fm.id=e.from_member_id "
                            + "LEFT JOIN cross_entry_failover_member tm ON tm.id=e.to_member_id "
                            + "WHERE e.group_id=? AND e.reason<>? ORDER BY e.created_time DESC,e.id DESC LIMIT 1",
                    id, "初始化主入口");
            group.put("lastSwitchEvent", latestSwitch.isEmpty() ? null : latestSwitch.get(0));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        result.put("summary", summary(groups));
        return R.ok(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public R save(CrossEntryFailoverSaveDto dto) {
        try {
            normalizeAndValidate(dto);
            Integer duplicate = dto.getId() == null
                    ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cross_entry_failover_group WHERE domain=? AND record_type=?",
                    Integer.class, dto.getDomain(), dto.getRecordType())
                    : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cross_entry_failover_group WHERE domain=? AND record_type=? AND id<>?",
                    Integer.class, dto.getDomain(), dto.getRecordType(), dto.getId());
            if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("该域名已配置跨入口容灾");
            List<Map<String, Object>> forwards = loadAndValidateForwards(dto.getMemberForwardIds(), dto.getRecordType());
            long now = System.currentTimeMillis();
            boolean managedDns = dto.getDnsZoneId() != null;
            DnsProviderService.ZoneAccess zoneAccess = managedDns ? dnsProviderService.loadZoneAccess(dto.getDnsZoneId()) : null;
            String encryptedToken = "";
            String providerZoneId = managedDns ? zoneAccess.providerZoneId() : StringUtils.trimToEmpty(dto.getZoneId());
            Long id = dto.getId();
            Long previousActiveForwardId = null;
            String previousActiveName = null;
            String requestedRecordId = dto.getRecordId();
            if (id == null) {
                if (!managedDns) return R.err("请选择已在 DNS 与域名中登记的 Cloudflare Zone");
            } else {
                List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                        "SELECT g.api_token,g.domain,g.dns_zone_id,g.zone_id,g.record_type,g.record_id,m.forward_id AS activeForwardId,m.node_name AS activeName "
                                + "FROM cross_entry_failover_group g LEFT JOIN cross_entry_failover_member m ON m.id=g.active_member_id WHERE g.id=?", id);
                if (existing.isEmpty()) return R.err("容灾组不存在");
                Map<String, Object> old = existing.get(0);
                previousActiveForwardId = nullableLong(old.get("activeForwardId"));
                previousActiveName = Objects.toString(old.get("activeName"), null);
                if (!managedDns) {
                    Long oldZoneRef = nullableLong(old.get("dns_zone_id"));
                    if (oldZoneRef != null) {
                        dto.setDnsZoneId(oldZoneRef);
                        managedDns = true;
                        zoneAccess = dnsProviderService.loadZoneAccess(oldZoneRef);
                        providerZoneId = zoneAccess.providerZoneId();
                    } else {
                        encryptedToken = StringUtils.isBlank(dto.getApiToken())
                                ? Objects.toString(old.get("api_token"), "")
                                : crypto().encrypt(dto.getApiToken().trim());
                        providerZoneId = Objects.toString(old.get("zone_id"), "");
                    }
                }
                boolean recordIdentityChanged = !dto.getDomain().equalsIgnoreCase(Objects.toString(old.get("domain"), ""))
                        || !Objects.equals(dto.getDnsZoneId(), nullableLong(old.get("dns_zone_id")))
                        || !dto.getRecordType().equalsIgnoreCase(Objects.toString(old.get("record_type"), ""));
                requestedRecordId = recordIdentityChanged ? null : Objects.toString(old.get("record_id"), null);
            }
            String recordId = StringUtils.defaultString(requestedRecordId);

            if (id == null) {
                jdbcTemplate.update("INSERT INTO cross_entry_failover_group "
                                + "(user_id,name,domain,dns_zone_id,zone_id,record_id,api_token,record_type,ttl,probe_interval_ms,connect_timeout_ms,"
                                + "failure_threshold,recovery_threshold,cooldown_seconds,auto_failback,routing_mode,quality_enabled,quality_probe_source_type,"
                                + "quality_probe_source_id,quality_probe_count,quality_degrade_threshold_ms,quality_recover_threshold_ms,quality_degrade_factor,"
                                + "quality_recover_factor,quality_degrade_samples,quality_recover_samples,quality_loss_threshold_percent,quality_probe_status,enabled,state,created_time,updated_time) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        JwtUtil.getUserIdFromToken(), dto.getName().trim(), dto.getDomain(), dto.getDnsZoneId(), providerZoneId, recordId,
                        encryptedToken, dto.getRecordType(), dto.getTtl(), dto.getProbeIntervalMs(), dto.getConnectTimeoutMs(),
                        dto.getFailureThreshold(), dto.getRecoveryThreshold(), dto.getCooldownSeconds(), dto.getAutoFailback(), dto.getRoutingMode(),
                        dto.getQualityEnabled(), dto.getQualityProbeSourceType(), dto.getQualityProbeSourceId(), dto.getQualityProbeCount(),
                        dto.getQualityDegradeThresholdMs(), dto.getQualityRecoverThresholdMs(), dto.getQualityDegradeFactor(), dto.getQualityRecoverFactor(),
                        dto.getQualityDegradeSamples(), dto.getQualityRecoverSamples(), dto.getQualityLossThresholdPercent(),
                        dto.getQualityEnabled() ? "pending" : "disabled", dto.getEnabled(), "unknown", now, now);
                id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            } else {
                jdbcTemplate.update("UPDATE cross_entry_failover_group SET name=?,domain=?,dns_zone_id=?,zone_id=?,record_id=?,api_token=?,record_type=?,ttl=?,"
                                + "probe_interval_ms=?,connect_timeout_ms=?,failure_threshold=?,recovery_threshold=?,cooldown_seconds=?,"
                                + "auto_failback=?,routing_mode=?,quality_enabled=?,quality_probe_source_type=?,quality_probe_source_id=?,"
                                + "quality_probe_count=?,quality_degrade_threshold_ms=?,quality_recover_threshold_ms=?,quality_degrade_factor=?,"
                                + "quality_recover_factor=?,quality_degrade_samples=?,quality_recover_samples=?,quality_loss_threshold_percent=?,"
                                + "quality_probe_status=?,quality_probe_error=NULL,enabled=?,state='unknown',last_error=NULL,updated_time=? WHERE id=?",
                        dto.getName().trim(), dto.getDomain(), dto.getDnsZoneId(), providerZoneId, recordId, encryptedToken, dto.getRecordType(), dto.getTtl(),
                        dto.getProbeIntervalMs(), dto.getConnectTimeoutMs(), dto.getFailureThreshold(), dto.getRecoveryThreshold(),
                        dto.getCooldownSeconds(), dto.getAutoFailback(), dto.getRoutingMode(), dto.getQualityEnabled(), dto.getQualityProbeSourceType(),
                        dto.getQualityProbeSourceId(), dto.getQualityProbeCount(), dto.getQualityDegradeThresholdMs(), dto.getQualityRecoverThresholdMs(),
                        dto.getQualityDegradeFactor(), dto.getQualityRecoverFactor(), dto.getQualityDegradeSamples(), dto.getQualityRecoverSamples(),
                        dto.getQualityLossThresholdPercent(), dto.getQualityEnabled() ? "pending" : "disabled", dto.getEnabled(), now, id);
                dnsProviderService.clearCrossEntryActiveRecords(dto.getDnsZoneId(), id);
                jdbcTemplate.update("DELETE FROM cross_entry_failover_member WHERE group_id=?", id);
            }

            Long primaryMemberId = null;
            Long retainedActiveMemberId = null;
            for (int priority = 0; priority < forwards.size(); priority++) {
                Map<String, Object> forward = forwards.get(priority);
                jdbcTemplate.update("INSERT INTO cross_entry_failover_member "
                        + "(group_id,forward_id,priority,weight,enabled,entry_node_id,entry_host,entry_address,entry_port,forward_name,node_name,status,created_time,updated_time) "
                                + "VALUES (?,?,?,?,?, ?,?,?,?,?,?,'unknown',?,?)",
                        id, number(forward.get("id")).longValue(), priority, memberWeight(dto, priority), true,
                        number(forward.get("inNodeId")).longValue(), forward.get("entryHost"), forward.get("entryAddress"), number(forward.get("inPort")).intValue(),
                        forward.get("name"), forward.get("nodeName"), now, now);
                Long memberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                if (priority == 0) primaryMemberId = memberId;
                if (previousActiveForwardId != null && previousActiveForwardId == number(forward.get("id")).longValue()) {
                    retainedActiveMemberId = memberId;
                }
            }
            Long activeMemberId = "active_active".equals(dto.getRoutingMode())
                    ? primaryMemberId
                    : (retainedActiveMemberId == null ? primaryMemberId : retainedActiveMemberId);
            boolean configuredEntryChanged = previousActiveForwardId != null && retainedActiveMemberId == null;
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET active_member_id=?,last_switch_at=CASE WHEN ? THEN ? ELSE last_switch_at END WHERE id=?",
                    activeMemberId, configuredEntryChanged, now, id);

            Map<String, Object> selectedEntry = loadMember(activeMemberId);
            if (managedDns) {
                dnsProviderService.releaseRecord(id);
                recordId = dnsProviderService.ensureManagedRecord(dto.getDnsZoneId(), requestedRecordId, dto.getDomain(), dto.getRecordType(),
                        Objects.toString(selectedEntry.get("entryAddress")), dto.getTtl(), id);
                jdbcTemplate.update("UPDATE cross_entry_failover_group SET record_id=? WHERE id=?", recordId, id);
            } else {
                String token;
                try {
                    token = crypto().decryptString(encryptedToken);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Cloudflare Token 无法解密，请重新填写 Token");
                }
                recordId = discoverRecord(token, providerZoneId, requestedRecordId, dto.getDomain(), dto.getRecordType());
                jdbcTemplate.update("UPDATE cross_entry_failover_group SET record_id=? WHERE id=?", recordId, id);
            }
            if (dto.getId() == null) {
                addEvent(id, null, activeMemberId, "初始化主入口", "success", "DNS 已指向 " + selectedEntry.get("entryAddress"));
            } else if (configuredEntryChanged) {
                addEvent(id, null, activeMemberId, "配置移除了当前入口", "success",
                        Objects.toString(previousActiveName, "原入口") + " -> " + selectedEntry.get("nodeName"));
            }
            Map<String, Object> savedGroup = loadGroup(id);
            if ("active_active".equals(dto.getRoutingMode())) {
                syncActiveEntries(savedGroup, loadMembers(id), "已发布全部入口");
            } else {
                updateCloudflareDns(savedGroup, selectedEntry);
            }
            return R.ok(Map.of("id", id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return R.err(e.getMessage());
        }
    }

    @Transactional
    public R delete(Long id) {
        if (!exists(id)) return R.err("容灾组不存在");
        Map<String, Object> group = loadGroup(id);
        dnsProviderService.clearCrossEntryActiveRecords(nullableLong(group.get("dnsZoneId")), id);
        dnsProviderService.releaseRecord(id);
        jdbcTemplate.update("DELETE FROM cross_entry_failover_event WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM cross_entry_failover_member WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM cross_entry_failover_group WHERE id=?", id);
        return R.ok();
    }

    public R checkNow(Long id) {
        if (!exists(id)) return R.err("容灾组不存在");
        try {
            probeGroup(id, true);
            return listGroups();
        } catch (RuntimeException e) {
            return R.err("检测失败：" + e.getMessage());
        }
    }

    public R listEvents(Long id) {
        return R.ok(jdbcTemplate.queryForList("SELECT e.id,e.reason,e.status,e.detail,e.created_time AS createdTime,"
                + "COALESCE(e.from_node_name,fm.node_name) AS fromNodeName,COALESCE(e.to_node_name,tm.node_name) AS toNodeName,"
                + "fm.forward_name AS fromForwardName,fm.entry_address AS fromEntryAddress,fm.entry_port AS fromEntryPort,"
                + "tm.forward_name AS toForwardName,tm.entry_address AS toEntryAddress,tm.entry_port AS toEntryPort FROM cross_entry_failover_event e "
                + "LEFT JOIN cross_entry_failover_member fm ON fm.id=e.from_member_id "
                + "LEFT JOIN cross_entry_failover_member tm ON tm.id=e.to_member_id "
                + "WHERE e.group_id=? ORDER BY e.created_time DESC LIMIT 100", id));
    }

    @Scheduled(initialDelay = 15000, fixedDelay = 1000)
    public void scheduledCheck() {
        if (!checking.compareAndSet(false, true)) return;
        try {
            long now = System.currentTimeMillis();
            List<Map<String, Object>> dueGroups = jdbcTemplate.queryForList(
                    "SELECT id FROM cross_entry_failover_group WHERE enabled=1 "
                            + "AND (last_checked_at IS NULL OR last_checked_at + probe_interval_ms <= ?) "
                            + "ORDER BY COALESCE(last_checked_at,0) ASC LIMIT " + MAX_GROUPS_PER_TICK, now);
            for (Map<String, Object> row : dueGroups) {
                long groupId = number(row.get("id")).longValue();
                if (!inFlightGroups.add(groupId)) continue;
                groupExecutor.execute(() -> {
                    try {
                        probeGroup(groupId, false);
                    } catch (RuntimeException e) {
                        log.warn("Cross-entry probe failed for group {}: {}", groupId, e.getMessage());
                    } finally {
                        inFlightGroups.remove(groupId);
                    }
                });
            }
        } catch (DataAccessException e) {
            log.debug("Cross-entry failover scheduler waiting for schema: {}", e.getMessage());
        } finally {
            checking.set(false);
        }
    }

    private void probeGroup(long groupId, boolean manual) {
        Object lock = groupLocks.computeIfAbsent(groupId, ignored -> new Object());
        synchronized (lock) {
            doProbeGroup(groupId, manual);
        }
    }

    private void doProbeGroup(long groupId, boolean manual) {
        Map<String, Object> group = loadGroup(groupId);
        if (!manual && !bool(group.get("enabled"))) return;
        List<Map<String, Object>> members = loadMembers(groupId);
        int timeout = number(group.get("connectTimeoutMs")).intValue();
        List<CompletableFuture<ProbeResult>> futures = members.stream()
                .map(member -> CompletableFuture.supplyAsync(() -> probe(member, timeout), probeExecutor))
                .collect(Collectors.toList());
        List<ProbeResult> results = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        long now = System.currentTimeMillis();
        int failureThreshold = number(group.get("failureThreshold")).intValue();
        for (ProbeResult result : results) updateMemberHealth(result, failureThreshold, now);

        members = loadMembers(groupId);
        boolean qualityDecisionEnabled = false;
        if (qualityEnabledForFailover(group)) {
            qualityDecisionEnabled = updateQuality(group, members, timeout, now);
            members = loadMembers(groupId);
        } else {
            markQualityDisabledIfNeeded(group, now);
        }
        Map<String, Object> active = memberById(members, nullableLong(group.get("activeMemberId")));
        if (active == null && !members.isEmpty()) active = members.get(0);
        if ("active_active".equals(Objects.toString(group.get("routingMode"), "failover"))) {
            updateActiveActiveGroup(group, members, now);
            return;
        }
        boolean activeFailed = active == null || "unhealthy".equals(active.get("status"));
        final boolean useQualityDecision = qualityDecisionEnabled;
        boolean activeQualityDegraded = useQualityDecision && isQualityDegraded(active);
        List<CrossEntryFailoverPolicy.Member> snapshots = members.stream()
                .map(member -> new CrossEntryFailoverPolicy.Member(
                        number(member.get("id")).longValue(), number(member.get("priority")).intValue(),
                        "healthy".equals(member.get("status")), number(member.get("successCount")).intValue(),
                        useQualityDecision && isQualityDegraded(member)))
                .collect(Collectors.toList());
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                snapshots, active == null ? null : number(active.get("id")).longValue(), bool(group.get("autoFailback")),
                number(group.get("recoveryThreshold")).intValue(), cooldownElapsed(group, now));
        Map<String, Object> target = decision.switchRequired() ? memberById(members, decision.targetId()) : null;

        if (target != null) {
            switchEntry(group, active, target, decision.reason(), now);
        } else {
            long healthy = members.stream().filter(member -> "healthy".equals(member.get("status"))).count();
            String state = activeFailed ? (healthy > 0 ? "degraded" : "offline")
                    : (activeQualityDegraded || healthy != members.size() ? "degraded" : "healthy");
            String error = activeFailed && healthy == 0 ? "所有入口均不可用"
                    : (activeQualityDegraded ? decision.reason() : null);
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET state=?,last_error=?,last_checked_at=?,updated_time=? WHERE id=?",
                    state, error, now, now, groupId);
        }
    }

    private boolean updateQuality(Map<String, Object> group, List<Map<String, Object>> members, int timeoutMs, long now) {
        long groupId = number(group.get("id")).longValue();
        String sourceError = qualitySourceError(group);
        if (sourceError != null) {
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET quality_probe_status='failed',quality_probe_error=?,quality_probe_at=?,updated_time=? WHERE id=?",
                    shorten(sourceError, 500), now, now, groupId);
            return false;
        }
        List<Map<String, Object>> enabledMembers = members.stream().filter(member -> bool(member.get("enabled"))).collect(Collectors.toList());
        if (enabledMembers.isEmpty()) {
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET quality_probe_status='failed',quality_probe_error='没有可探测的入口成员',quality_probe_at=?,updated_time=? WHERE id=?",
                    now, now, groupId);
            return false;
        }
        int count = number(group.get("qualityProbeCount")).intValue();
        List<CompletableFuture<QualityProbeResult>> futures = enabledMembers.stream()
                .map(member -> CompletableFuture.supplyAsync(() -> probeQuality(group, member, count, timeoutMs), probeExecutor))
                .collect(Collectors.toList());
        List<QualityProbeResult> results = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());

        int degraded = 0;
        int failures = 0;
        List<String> errors = new ArrayList<>();
        for (QualityProbeResult result : results) {
            String state = updateMemberQuality(group, result, now);
            if ("degraded".equals(state)) degraded++;
            if (!result.success()) failures++;
            if (StringUtils.isNotBlank(result.error())) {
                errors.add(Objects.toString(result.member().get("nodeName"), "入口") + "：" + result.error());
            }
        }
        String status = degraded > 0 || failures > 0 ? "warning" : "ok";
        String error = errors.isEmpty() ? null : shorten(errors.get(0) + (errors.size() > 1 ? " 等 " + errors.size() + " 条" : ""), 500);
        jdbcTemplate.update("UPDATE cross_entry_failover_group SET quality_probe_status=?,quality_probe_error=?,quality_probe_at=?,updated_time=? WHERE id=?",
                status, error, now, now, groupId);
        return true;
    }

    private QualityProbeResult probeQuality(Map<String, Object> group, Map<String, Object> member, int count, int timeoutMs) {
        String address = Objects.toString(member.get("entryAddress"));
        int port = number(member.get("entryPort")).intValue();
        String sourceType = Objects.toString(group.get("qualityProbeSourceType"), "panel");
        if ("panel".equals(sourceType)) return tcpPingLocal(member, address, port, count, timeoutMs);

        JSONObject request = new JSONObject();
        request.put("ip", address);
        request.put("port", port);
        request.put("count", count);
        request.put("timeout", timeoutMs);
        long timeoutSeconds = Math.max(5L, (long) Math.ceil((count * (timeoutMs + 150.0)) / 1000.0) + 3L);
        GostDto response = "node".equals(sourceType)
                ? WebSocketServer.send_msg(nullableLong(group.get("qualityProbeSourceId")), request, "TcpPing", timeoutSeconds)
                : WebSocketServer.sendConnectorMsg(nullableLong(group.get("qualityProbeSourceId")), request, "TcpPing", timeoutSeconds);
        if (response == null || !"OK".equals(response.getMsg())) {
            return new QualityProbeResult(member, false, null, 100.0,
                    response == null ? "探测源无响应" : response.getMsg());
        }
        JSONObject data = responseData(response.getData());
        if (data == null) return new QualityProbeResult(member, false, null, 100.0, "探测源返回为空");
        boolean success = data.getBooleanValue("success");
        double loss = data.containsKey("packetLoss") ? data.getDoubleValue("packetLoss") : (success ? 0.0 : 100.0);
        Integer latency = success && data.containsKey("averageTime")
                ? Math.max(1, (int) Math.round(data.getDoubleValue("averageTime")))
                : null;
        String error = success ? null : StringUtils.defaultIfBlank(data.getString("errorMessage"), "TCP 探测失败");
        return new QualityProbeResult(member, success, latency, loss, error);
    }

    private QualityProbeResult tcpPingLocal(Map<String, Object> member, String address, int port, int count, int timeoutMs) {
        int success = 0;
        long totalMs = 0;
        String lastError = null;
        for (int i = 0; i < count; i++) {
            long started = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), timeoutMs);
                totalMs += Math.max(1, (System.nanoTime() - started) / 1_000_000L);
                success++;
            } catch (Exception e) {
                lastError = "TCP 探测失败";
            }
            if (i < count - 1) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    lastError = "质量探测被中断";
                    break;
                }
            }
        }
        double loss = count <= 0 ? 100.0 : (count - success) * 100.0 / count;
        if (success == 0) return new QualityProbeResult(member, false, null, 100.0,
                StringUtils.defaultIfBlank(lastError, "所有 TCP 探测失败"));
        return new QualityProbeResult(member, true, Math.max(1, (int) Math.round(totalMs * 1.0 / success)), loss, null);
    }

    private String updateMemberQuality(Map<String, Object> group, QualityProbeResult result, long now) {
        Map<String, Object> member = result.member();
        String oldState = Objects.toString(member.get("qualityState"), "unknown");
        Integer latency = result.latencyMs();
        Double loss = result.lossPercent();
        CrossEntryQualityEvaluator.Decision decision = CrossEntryQualityEvaluator.evaluate(
                new CrossEntryQualityEvaluator.Snapshot(oldState, nullableInt(member.get("qualityBaselineMs")), latency, loss,
                        result.success(), number(member.get("qualityBadCount")).intValue(), number(member.get("qualityGoodCount")).intValue()),
                new CrossEntryQualityEvaluator.Settings(number(group.get("qualityDegradeThresholdMs")).intValue(),
                        number(group.get("qualityRecoverThresholdMs")).intValue(), doubleNumber(group.get("qualityDegradeFactor")),
                        doubleNumber(group.get("qualityRecoverFactor")), number(group.get("qualityDegradeSamples")).intValue(),
                        number(group.get("qualityRecoverSamples")).intValue(), doubleNumber(group.get("qualityLossThresholdPercent"))));
        if (loss == null) loss = result.success() ? 0.0 : 100.0;
        jdbcTemplate.update("UPDATE cross_entry_failover_member SET quality_latency_ms=?,quality_loss_percent=?,quality_baseline_ms=?,"
                        + "quality_state=?,quality_bad_count=?,quality_good_count=?,quality_last_error=?,quality_checked_at=?,updated_time=? WHERE id=?",
                latency, loss, decision.baselineMs(), decision.state(), decision.badCount(), decision.goodCount(),
                shorten(result.error(), 500), now, now, member.get("id"));
        return decision.state();
    }

    private String qualitySourceError(Map<String, Object> group) {
        String sourceType = Objects.toString(group.get("qualityProbeSourceType"), "panel");
        Long sourceId = nullableLong(group.get("qualityProbeSourceId"));
        if ("panel".equals(sourceType)) return null;
        if (sourceId == null) return "质量探测源未配置";
        if ("node".equals(sourceType)) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT name,version FROM node WHERE id=?", sourceId);
            if (rows.isEmpty()) return "质量探测节点不存在";
            if (!WebSocketServer.isNodeOnline(sourceId)) return "质量探测节点离线";
            String version = Objects.toString(rows.get(0).get("version"), "");
            if (!AgentVersionUtil.isAtLeast(version, MIN_REMOTE_QUALITY_VERSION)) {
                return "质量探测节点 Agent 需要升级到 " + MIN_REMOTE_QUALITY_VERSION;
            }
            return null;
        }
        if ("connector".equals(sourceType)) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT name,version FROM internal_connector WHERE id=? AND status=1", sourceId);
            if (rows.isEmpty()) return "质量探测 Connector 不存在";
            if (!WebSocketServer.isConnectorOnline(sourceId)) return "质量探测 Connector 离线";
            String version = Objects.toString(rows.get(0).get("version"), "");
            if (!AgentVersionUtil.isAtLeast(version, MIN_REMOTE_QUALITY_VERSION)) {
                return "质量探测 Connector 需要升级到 " + MIN_REMOTE_QUALITY_VERSION;
            }
            return null;
        }
        return "质量探测源类型不正确";
    }

    private void markQualityDisabledIfNeeded(Map<String, Object> group, long now) {
        if ("disabled".equals(Objects.toString(group.get("qualityProbeStatus"), "disabled"))) return;
        jdbcTemplate.update("UPDATE cross_entry_failover_group SET quality_probe_status='disabled',quality_probe_error=NULL,quality_probe_at=?,updated_time=? WHERE id=?",
                now, now, group.get("id"));
    }

    private ProbeResult probe(Map<String, Object> member, int timeoutMs) {
        long started = System.nanoTime();
        long nodeId = number(member.get("entryNodeId")).longValue();
        if (!WebSocketServer.isNodeOnline(nodeId)) {
            return new ProbeResult(member, false, null, "入口节点离线");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(Objects.toString(member.get("entryAddress")),
                    number(member.get("entryPort")).intValue()), timeoutMs);
            int latency = (int) Math.max(1, (System.nanoTime() - started) / 1_000_000L);
            return new ProbeResult(member, true, latency, null);
        } catch (Exception e) {
            return new ProbeResult(member, false, null, "公网入口连接失败");
        }
    }

    private void updateMemberHealth(ProbeResult result, int failureThreshold, long now) {
        int oldFails = number(result.member().get("failCount")).intValue();
        int oldSuccess = number(result.member().get("successCount")).intValue();
        if (result.healthy()) {
            jdbcTemplate.update("UPDATE cross_entry_failover_member SET status='healthy',fail_count=0,success_count=?,latency_ms=?,"
                            + "last_error=NULL,last_checked_at=?,last_healthy_at=?,updated_time=? WHERE id=?",
                    oldSuccess + 1, result.latencyMs(), now, now, now, result.member().get("id"));
        } else {
            int failures = oldFails + 1;
            String status = failures >= failureThreshold ? "unhealthy" : Objects.toString(result.member().get("status"), "unknown");
            jdbcTemplate.update("UPDATE cross_entry_failover_member SET status=?,fail_count=?,success_count=0,latency_ms=NULL,"
                            + "last_error=?,last_checked_at=?,last_failure_at=?,updated_time=? WHERE id=?",
                    status, failures, result.error(), now, now, now, result.member().get("id"));
        }
    }

    private void switchEntry(Map<String, Object> group, Map<String, Object> from, Map<String, Object> to, String reason, long now) {
        long groupId = number(group.get("id")).longValue();
        boolean dnsChanged = false;
        try {
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET state='switching',updated_time=? WHERE id=?", now, groupId);
            updateCloudflareDns(group, to);
            dnsChanged = true;
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET active_member_id=?,state='healthy',last_error=NULL,"
                            + "last_checked_at=?,last_switch_at=?,updated_time=? WHERE id=?",
                    to.get("id"), now, now, now, groupId);
            addEvent(groupId, from == null ? null : nullableLong(from.get("id")), nullableLong(to.get("id")), reason,
                    "success", "DNS 已切换至 " + to.get("nodeName") + " · " + to.get("entryAddress"));
            telegramNotificationService.notifyCrossEntrySwitch(groupId, Objects.toString(group.get("name")),
                    Objects.toString(group.get("domain")), entryLabel(from), entryLabel(to), reason, true, now);
        } catch (RuntimeException e) {
            String detail = shorten(e.getMessage(), 500);
            if (dnsChanged && from != null) {
                try {
                    updateCloudflareDns(group, from);
                    detail = shorten(detail + "；DNS 已回滚原入口", 500);
                } catch (RuntimeException rollbackError) {
                    detail = shorten(detail + "；DNS 回滚失败，请立即人工检查", 500);
                }
            }
            try {
                jdbcTemplate.update("UPDATE cross_entry_failover_group SET state='error',last_error=?,last_checked_at=?,updated_time=? WHERE id=?",
                        detail, now, now, groupId);
                addEvent(groupId, from == null ? null : nullableLong(from.get("id")), nullableLong(to.get("id")), reason,
                        "failed", detail);
            } catch (DataAccessException databaseError) {
                log.error("Unable to persist failed cross-entry switch for group {}", groupId, databaseError);
            }
            telegramNotificationService.notifyCrossEntrySwitch(groupId, Objects.toString(group.get("name")),
                    Objects.toString(group.get("domain")), entryLabel(from), entryLabel(to), detail, false, now);
        }
    }

    /** Active-active DNS is connection selection at resolver time, not a TCP connection migrator. */
    private void updateActiveActiveGroup(Map<String, Object> group, List<Map<String, Object>> members, long now) {
        List<Map<String, Object>> healthy = members.stream()
                .filter(member -> bool(member.get("enabled")) && "healthy".equals(member.get("status")))
                .collect(Collectors.toList());
        long groupId = number(group.get("id")).longValue();
        Map<String, Object> previous = memberById(members, nullableLong(group.get("activeMemberId")));
        if (healthy.isEmpty()) {
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET state='offline',last_error='所有入口均不可用，DNS 保持上一次可用记录',last_checked_at=?,updated_time=? WHERE id=?",
                    now, now, groupId);
            return;
        }
        Map<String, Object> representative = healthy.get(0);
        try {
            DnsProviderService.DnsPoolSyncResult result = syncActiveEntries(group, healthy, null);
            String state = healthy.size() == members.size() ? "healthy" : "degraded";
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET active_member_id=?,state=?,last_error=NULL,last_checked_at=?,updated_time=? WHERE id=?",
                    representative.get("id"), state, now, now, groupId);
            if (result.created() > 0 || result.removed() > 0) {
                addEvent(groupId, previous == null ? null : nullableLong(previous.get("id")), nullableLong(representative.get("id")),
                        "入口成员已更新", "success", "健康入口 " + result.active() + " 条；新增 " + result.created() + " 条 DNS 记录，移除 " + result.removed() + " 条");
            }
        } catch (RuntimeException e) {
            String error = shorten("DNS 同步失败：" + e.getMessage(), 500);
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET state='error',last_error=?,last_checked_at=?,updated_time=? WHERE id=?",
                    error, now, now, groupId);
            addEvent(groupId, previous == null ? null : nullableLong(previous.get("id")), nullableLong(representative.get("id")),
                    "入口成员同步失败", "failed", error);
        }
    }

    private DnsProviderService.DnsPoolSyncResult syncActiveEntries(Map<String, Object> group, List<Map<String, Object>> members, String ignoredReason) {
        List<DnsProviderService.CrossEntryDnsTarget> targets = members.stream()
                .filter(member -> bool(member.get("enabled")) && !"unhealthy".equals(member.get("status")))
                .map(member -> new DnsProviderService.CrossEntryDnsTarget(number(member.get("id")).longValue(),
                        Objects.toString(member.get("entryAddress"))))
                .collect(Collectors.toList());
        return dnsProviderService.syncCrossEntryActiveRecords(nullableLong(group.get("dnsZoneId")),
                Objects.toString(group.get("recordId")), Objects.toString(group.get("domain")),
                Objects.toString(group.get("recordType")), number(group.get("ttl")).intValue(),
                number(group.get("id")).longValue(), targets);
    }

    private void updateCloudflareDns(Map<String, Object> group, Map<String, Object> member) {
        Long dnsZoneId = nullableLong(group.get("dnsZoneId"));
        if (dnsZoneId != null) {
            dnsProviderService.updateManagedRecord(dnsZoneId, Objects.toString(group.get("recordId")), Objects.toString(group.get("domain")),
                    Objects.toString(group.get("recordType")), Objects.toString(member.get("entryAddress")),
                    number(group.get("ttl")).intValue(), number(group.get("id")).longValue());
            return;
        }
        String token = crypto().decryptString(Objects.toString(group.get("apiToken")));
        HttpHeaders headers = cloudflareHeaders(token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", group.get("recordType"));
        body.put("name", group.get("domain"));
        body.put("content", member.get("entryAddress"));
        body.put("ttl", number(group.get("ttl")).intValue());
        body.put("proxied", false);
        String url = CF_API + "/zones/" + group.get("zoneId") + "/dns_records/" + group.get("recordId");
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
            JSONObject json = JSON.parseObject(response.getBody());
            if (json == null || !json.getBooleanValue("success")) throw new IllegalStateException(cloudflareError(json));
        } catch (RestClientException e) {
            throw new IllegalStateException("Cloudflare DNS 更新失败");
        }
    }

    private String discoverRecord(String token, String zoneId, String recordId, String domain, String type) {
        if (StringUtils.isNotBlank(recordId)) return recordId.trim();
        URI uri = UriComponentsBuilder.fromHttpUrl(CF_API + "/zones/" + zoneId.trim() + "/dns_records")
                .queryParam("type", type).queryParam("name", domain).build(true).toUri();
        try {
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET,
                    new HttpEntity<>(cloudflareHeaders(token)), String.class);
            JSONObject json = JSON.parseObject(response.getBody());
            if (json == null || !json.getBooleanValue("success")) throw new IllegalStateException(cloudflareError(json));
            JSONArray result = json.getJSONArray("result");
            if (result == null || result.isEmpty()) throw new IllegalArgumentException("Cloudflare 中未找到该域名的 " + type + " 记录");
            return result.getJSONObject(0).getString("id");
        } catch (RestClientException e) {
            throw new IllegalStateException("Cloudflare 凭据或 Zone ID 验证失败");
        }
    }

    private List<Map<String, Object>> loadAndValidateForwards(List<Long> ids, String recordType) {
        List<Long> distinctIds = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinctIds.size() < 2) throw new IllegalArgumentException("跨入口容灾至少需要两个入口转发");
        if (distinctIds.size() > 10) throw new IllegalArgumentException("单个容灾组最多配置10个入口");
        String placeholders = distinctIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT f.id,f.name,f.in_port AS inPort,f.status,"
                        + "COALESCE(f.protocol_mode,'tcp') AS protocolMode,t.in_node_id AS inNodeId,"
                        + "COALESCE(NULLIF(n.server_ip,''),n.ip,t.in_ip) AS entryHost,COALESCE(n.name,CONCAT('节点',t.in_node_id)) AS nodeName "
                        + "FROM forward f JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id WHERE f.id IN (" + placeholders + ")",
                distinctIds.toArray());
        Map<Long, Map<String, Object>> byId = rows.stream().collect(Collectors.toMap(row -> number(row.get("id")).longValue(), row -> row));
        List<Map<String, Object>> ordered = new ArrayList<>();
        Set<Long> nodeIds = new HashSet<>();
        Set<String> entryAddresses = new HashSet<>();
        Integer port = null;
        for (Long id : distinctIds) {
            Map<String, Object> row = byId.get(id);
            if (row == null || number(row.get("status")).intValue() != 1) throw new IllegalArgumentException("所选转发不存在或已暂停");
            if (!Set.of("tcp", "tcp_udp").contains(Objects.toString(row.get("protocolMode"), "tcp"))) {
                throw new IllegalArgumentException("跨入口快速检测暂不支持仅 UDP 转发");
            }
            int currentPort = number(row.get("inPort")).intValue();
            if (port == null) port = currentPort;
            else if (port != currentPort) throw new IllegalArgumentException("所有入口必须使用相同的公网端口");
            long nodeId = number(row.get("inNodeId")).longValue();
            if (!nodeIds.add(nodeId)) throw new IllegalArgumentException("每个候选入口必须来自不同节点");
            String host = Objects.toString(row.get("entryHost"), "").trim();
            if (host.isEmpty()) throw new IllegalArgumentException("入口节点缺少公网地址：" + row.get("nodeName"));
            String address = resolveAddress(host, recordType);
            if (!entryAddresses.add(address)) throw new IllegalArgumentException("候选入口必须使用不同的公网 IP");
            row.put("entryAddress", address);
            ordered.add(row);
        }
        return ordered;
    }

    private String resolveAddress(String host, String recordType) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (("A".equals(recordType) && address instanceof Inet4Address)
                        || ("AAAA".equals(recordType) && address instanceof Inet6Address)) return address.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("入口地址无法解析为 " + recordType + " 记录：" + host);
    }

    private void normalizeAndValidate(CrossEntryFailoverSaveDto dto) {
        dto.setDomain(dto.getDnsZoneId() == null
                ? StringUtils.lowerCase(StringUtils.trim(dto.getDomain()), Locale.ROOT)
                : dnsProviderService.normalizeDomain(dto.getDnsZoneId(), dto.getDomain()));
        dto.setRecordType(StringUtils.upperCase(StringUtils.defaultIfBlank(dto.getRecordType(), "A"), Locale.ROOT));
        if (!dto.getDomain().matches("(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")) {
            throw new IllegalArgumentException("业务域名格式不正确");
        }
        if (!Set.of("A", "AAAA").contains(dto.getRecordType())) throw new IllegalArgumentException("记录类型仅支持 A 或 AAAA");
        dto.setTtl(clamp(dto.getTtl(), 60, 86400));
        dto.setProbeIntervalMs(clamp(dto.getProbeIntervalMs(), 1000, 60000));
        dto.setConnectTimeoutMs(clamp(dto.getConnectTimeoutMs(), 300, 10000));
        if (dto.getConnectTimeoutMs() >= dto.getProbeIntervalMs()) dto.setConnectTimeoutMs(Math.max(300, dto.getProbeIntervalMs() - 200));
        dto.setFailureThreshold(clamp(dto.getFailureThreshold(), 1, 10));
        dto.setRecoveryThreshold(clamp(dto.getRecoveryThreshold(), 2, 20));
        dto.setCooldownSeconds(clamp(dto.getCooldownSeconds(), 10, 3600));
        String routingMode = StringUtils.lowerCase(StringUtils.defaultIfBlank(dto.getRoutingMode(), "failover"), Locale.ROOT);
        if (!Set.of("failover", "active_active").contains(routingMode)) throw new IllegalArgumentException("入口模式不正确");
        if ("active_active".equals(routingMode) && dto.getDnsZoneId() == null) {
            throw new IllegalArgumentException("多入口同时运行需要使用面板管理的 Cloudflare Zone");
        }
        dto.setRoutingMode(routingMode);
        dto.setAutoFailback(Boolean.TRUE.equals(dto.getAutoFailback()));
        dto.setEnabled(!Boolean.FALSE.equals(dto.getEnabled()));
        dto.setQualityEnabled(Boolean.TRUE.equals(dto.getQualityEnabled()));
        if ("active_active".equals(routingMode)) dto.setQualityEnabled(false);
        dto.setQualityProbeSourceType(StringUtils.lowerCase(StringUtils.defaultIfBlank(dto.getQualityProbeSourceType(), "panel"), Locale.ROOT));
        if (!Set.of("panel", "node", "connector").contains(dto.getQualityProbeSourceType())) {
            throw new IllegalArgumentException("质量探测源类型不正确");
        }
        if (dto.getQualityEnabled() && !"panel".equals(dto.getQualityProbeSourceType()) && dto.getQualityProbeSourceId() == null) {
            throw new IllegalArgumentException("请选择质量探测源");
        }
        if (dto.getQualityEnabled() && "node".equals(dto.getQualityProbeSourceType())) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM node WHERE id=?", Integer.class, dto.getQualityProbeSourceId());
            if (count == null || count == 0) throw new IllegalArgumentException("质量探测节点不存在");
        }
        if (dto.getQualityEnabled() && "connector".equals(dto.getQualityProbeSourceType())) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM internal_connector WHERE id=? AND status=1", Integer.class, dto.getQualityProbeSourceId());
            if (count == null || count == 0) throw new IllegalArgumentException("质量探测 Connector 不存在");
        }
        if (!dto.getQualityEnabled() || "panel".equals(dto.getQualityProbeSourceType())) dto.setQualityProbeSourceId(null);
        dto.setQualityProbeCount(clamp(dto.getQualityProbeCount(), 2, 10));
        dto.setQualityDegradeThresholdMs(clamp(dto.getQualityDegradeThresholdMs(), 20, 30000));
        dto.setQualityRecoverThresholdMs(clamp(dto.getQualityRecoverThresholdMs(), 10, 30000));
        if (dto.getQualityRecoverThresholdMs() > dto.getQualityDegradeThresholdMs()) {
            dto.setQualityRecoverThresholdMs(dto.getQualityDegradeThresholdMs());
        }
        dto.setQualityDegradeFactor(clampDouble(dto.getQualityDegradeFactor(), 1.2, 20.0));
        dto.setQualityRecoverFactor(clampDouble(dto.getQualityRecoverFactor(), 1.0, dto.getQualityDegradeFactor()));
        dto.setQualityDegradeSamples(clamp(dto.getQualityDegradeSamples(), 1, 20));
        dto.setQualityRecoverSamples(clamp(dto.getQualityRecoverSamples(), 1, 20));
        dto.setQualityLossThresholdPercent(clampDouble(dto.getQualityLossThresholdPercent(), 1.0, 100.0));
    }

    private Map<String, Object> loadGroup(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,name,domain,dns_zone_id AS dnsZoneId,zone_id AS zoneId,record_id AS recordId,api_token AS apiToken,"
                + "record_type AS recordType,ttl,probe_interval_ms AS probeIntervalMs,connect_timeout_ms AS connectTimeoutMs,"
                + "failure_threshold AS failureThreshold,recovery_threshold AS recoveryThreshold,cooldown_seconds AS cooldownSeconds,"
                + "auto_failback AS autoFailback,routing_mode AS routingMode,quality_enabled AS qualityEnabled,quality_probe_source_type AS qualityProbeSourceType,"
                + "quality_probe_source_id AS qualityProbeSourceId,quality_probe_count AS qualityProbeCount,quality_degrade_threshold_ms AS qualityDegradeThresholdMs,"
                + "quality_recover_threshold_ms AS qualityRecoverThresholdMs,quality_degrade_factor AS qualityDegradeFactor,quality_recover_factor AS qualityRecoverFactor,"
                + "quality_degrade_samples AS qualityDegradeSamples,quality_recover_samples AS qualityRecoverSamples,"
                + "quality_loss_threshold_percent AS qualityLossThresholdPercent,quality_probe_status AS qualityProbeStatus,"
                + "quality_probe_error AS qualityProbeError,quality_probe_at AS qualityProbeAt,enabled,state,active_member_id AS activeMemberId,last_error AS lastError,"
                + "last_checked_at AS lastCheckedAt,last_switch_at AS lastSwitchAt FROM cross_entry_failover_group WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("容灾组不存在");
        return rows.get(0);
    }

    private List<Map<String, Object>> loadMembers(long groupId) {
        return jdbcTemplate.queryForList("SELECT id,group_id AS groupId,forward_id AS forwardId,priority,weight,enabled,entry_node_id AS entryNodeId,"
                + "entry_host AS entryHost,entry_address AS entryAddress,entry_port AS entryPort,forward_name AS forwardName,node_name AS nodeName,"
                + "status,fail_count AS failCount,success_count AS successCount,latency_ms AS latencyMs,quality_latency_ms AS qualityLatencyMs,"
                + "quality_loss_percent AS qualityLossPercent,quality_baseline_ms AS qualityBaselineMs,quality_state AS qualityState,"
                + "quality_bad_count AS qualityBadCount,quality_good_count AS qualityGoodCount,quality_last_error AS qualityLastError,"
                + "quality_checked_at AS qualityCheckedAt,last_error AS lastError,"
                + "last_checked_at AS lastCheckedAt,last_healthy_at AS lastHealthyAt,last_failure_at AS lastFailureAt "
                + "FROM cross_entry_failover_member WHERE group_id=? ORDER BY priority", groupId);
    }

    private Map<String, Object> loadMember(Long id) {
        if (id == null) return null;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,entry_address AS entryAddress,node_name AS nodeName FROM cross_entry_failover_member WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> memberById(List<Map<String, Object>> members, Long id) {
        if (id == null) return null;
        return members.stream().filter(member -> number(member.get("id")).longValue() == id).findFirst().orElse(null);
    }

    private boolean cooldownElapsed(Map<String, Object> group, long now) {
        Long lastSwitch = nullableLong(group.get("lastSwitchAt"));
        return lastSwitch == null || now - lastSwitch >= number(group.get("cooldownSeconds")).longValue() * 1000L;
    }

    private void addEvent(long groupId, Long from, Long to, String reason, String status, String detail) {
        String fromName = memberName(from);
        String toName = memberName(to);
        jdbcTemplate.update("INSERT INTO cross_entry_failover_event (group_id,from_member_id,to_member_id,from_node_name,to_node_name,reason,status,detail,created_time) VALUES (?,?,?,?,?,?,?,?,?)",
                groupId, from, to, fromName, toName, shorten(reason, 255), status, shorten(detail, 500), System.currentTimeMillis());
    }

    private String memberName(Long id) {
        if (id == null) return null;
        List<String> names = jdbcTemplate.query("SELECT node_name FROM cross_entry_failover_member WHERE id=?",
                (rs, rowNum) -> rs.getString(1), id);
        return names.isEmpty() ? null : names.get(0);
    }

    private String entryLabel(Map<String, Object> member) {
        if (member == null) return "无";
        return Objects.toString(member.get("nodeName"), "未知入口") + " " + Objects.toString(member.get("entryAddress"), "");
    }

    private boolean exists(long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cross_entry_failover_group WHERE id=?", Integer.class, id);
        return count != null && count > 0;
    }

    private Map<String, Object> summary(List<Map<String, Object>> groups) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", groups.size());
        summary.put("enabled", groups.stream().filter(group -> bool(group.get("enabled"))).count());
        summary.put("healthy", groups.stream().filter(group -> "healthy".equals(group.get("state"))).count());
        summary.put("degraded", groups.stream().filter(group -> Set.of("degraded", "offline", "error").contains(Objects.toString(group.get("state")))).count());
        Long switches = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cross_entry_failover_event WHERE status='success' AND reason<>'初始化主入口'", Long.class);
        summary.put("switches", switches == null ? 0 : switches);
        return summary;
    }

    private HttpHeaders cloudflareHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String cloudflareError(JSONObject json) {
        if (json != null && json.getJSONArray("errors") != null && !json.getJSONArray("errors").isEmpty()) {
            String message = json.getJSONArray("errors").getJSONObject(0).getString("message");
            if (StringUtils.isNotBlank(message)) return "Cloudflare：" + message;
        }
        return "Cloudflare API 操作失败";
    }

    private AESCrypto crypto() {
        return new AESCrypto(encryptionSecret);
    }

    private static ExecutorService boundedExecutor(int threads, int queueSize, String name) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), runnable -> {
            Thread thread = new Thread(runnable, name + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private Number number(Object value) {
        if (value instanceof Number) return (Number) value;
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    private double doubleNumber(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return value == null ? 0.0 : Double.parseDouble(value.toString());
    }

    private Integer nullableInt(Object value) {
        return value == null ? null : number(value).intValue();
    }

    private Long nullableLong(Object value) {
        return value == null ? null : number(value).longValue();
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private int clamp(Integer value, int min, int max) {
        return Math.max(min, Math.min(max, value == null ? min : value));
    }

    private double clampDouble(Double value, double min, double max) {
        double current = value == null || value.isNaN() || value.isInfinite() ? min : value;
        return Math.max(min, Math.min(max, current));
    }

    private boolean qualityEnabledForFailover(Map<String, Object> group) {
        return bool(group.get("qualityEnabled")) && "failover".equals(Objects.toString(group.get("routingMode"), "failover"));
    }

    private boolean isQualityDegraded(Map<String, Object> member) {
        return member != null && "degraded".equals(Objects.toString(member.get("qualityState"), "unknown"));
    }

    private JSONObject responseData(Object data) {
        if (data instanceof JSONObject) return (JSONObject) data;
        if (data instanceof Map<?, ?> map) return new JSONObject((Map<String, Object>) map);
        if (data instanceof String text && StringUtils.isNotBlank(text)) return JSON.parseObject(text);
        return null;
    }

    private int memberWeight(CrossEntryFailoverSaveDto dto, int index) {
        if (dto.getMemberWeights() == null || index >= dto.getMemberWeights().size()) return 100;
        Integer value = dto.getMemberWeights().get(index);
        return clamp(value, 1, 1000);
    }

    private String shorten(String value, int max) {
        String clean = StringUtils.defaultString(value);
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    @PreDestroy
    public void shutdown() {
        groupExecutor.shutdownNow();
        probeExecutor.shutdownNow();
    }

    private record ProbeResult(Map<String, Object> member, boolean healthy, Integer latencyMs, String error) {}

    private record QualityProbeResult(Map<String, Object> member, boolean success, Integer latencyMs,
                                      Double lossPercent, String error) {}
}
