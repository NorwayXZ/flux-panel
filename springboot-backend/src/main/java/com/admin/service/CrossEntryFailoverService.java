package com.admin.service;

import com.admin.common.dto.CrossEntryFailoverSaveDto;
import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.PortLedgerEntryDto;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.PortLedgerQueryDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.CrossEntryFailoverPolicy;
import com.admin.common.utils.CrossEntryQualityFlapGuard;
import com.admin.common.utils.CrossEntryQualityEvaluator;
import com.admin.common.utils.CrossEntryTopology;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.PortNamespaceUtil;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
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
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
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
    private final SchedulingConflictService schedulingConflictService;
    private final ForwardService forwardService;
    private final TunnelService tunnelService;
    private final NodeService nodeService;
    private final PortLedgerService portLedgerService;
    private final ExecutorService probeExecutor = boundedExecutor(8, 64, "cross-entry-probe");
    private final ExecutorService groupExecutor = boundedExecutor(4, 100, "cross-entry-group");
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final Map<Long, Object> groupLocks = new ConcurrentHashMap<>();
    private final Set<Long> inFlightGroups = ConcurrentHashMap.newKeySet();

    @Value("${jwt-secret}")
    private String encryptionSecret;

    public CrossEntryFailoverService(JdbcTemplate jdbcTemplate, RestTemplate restTemplate,
                                     TelegramNotificationService telegramNotificationService,
                                     DnsProviderService dnsProviderService,
                                     SchedulingConflictService schedulingConflictService,
                                     ForwardService forwardService,
                                     TunnelService tunnelService,
                                     NodeService nodeService,
                                     PortLedgerService portLedgerService) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.telegramNotificationService = telegramNotificationService;
        this.dnsProviderService = dnsProviderService;
        this.schedulingConflictService = schedulingConflictService;
        this.forwardService = forwardService;
        this.tunnelService = tunnelService;
        this.nodeService = nodeService;
        this.portLedgerService = portLedgerService;
    }

    private static final class ManagedResourceDraft {
        private Long forwardId;
        private final Long tunnelId;
        private final Long entryNodeId;
        private final boolean createdTunnel;
        private final String targetAddress;
        private final int publicPort;
        private final String portMode;
        private final String protocolMode;

        private ManagedResourceDraft(Long tunnelId, Long entryNodeId,
                                     boolean createdTunnel, String targetAddress, int publicPort,
                                     String portMode, String protocolMode) {
            this.tunnelId = tunnelId;
            this.entryNodeId = entryNodeId;
            this.createdTunnel = createdTunnel;
            this.targetAddress = targetAddress;
            this.publicPort = publicPort;
            this.portMode = portMode;
            this.protocolMode = protocolMode;
        }
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
                        + "quality_loss_threshold_percent AS qualityLossThresholdPercent,quality_p95_threshold_ms AS qualityP95ThresholdMs,"
                        + "quality_jitter_threshold_ms AS qualityJitterThresholdMs,quality_fixed_target_enabled AS qualityFixedTargetEnabled,"
                        + "quality_fixed_target_ms AS qualityFixedTargetMs,quality_fixed_target_strict AS qualityFixedTargetStrict,"
                        + "quality_flap_guard_enabled AS qualityFlapGuardEnabled,quality_flap_window_seconds AS qualityFlapWindowSeconds,"
                        + "quality_flap_threshold AS qualityFlapThreshold,quality_flap_suppress_seconds AS qualityFlapSuppressSeconds,"
                        + "quality_penalty_enabled AS qualityPenaltyEnabled,quality_penalty_reset_seconds AS qualityPenaltyResetSeconds,"
                        + "quality_penalty_observe_seconds AS qualityPenaltyObserveSeconds,"
                        + "smart_selection_enabled AS smartSelectionEnabled,tcp_latency_selection_enabled AS tcpLatencySelectionEnabled,"
                        + "tcp_latency_switch_threshold_ms AS tcpLatencySwitchThresholdMs,"
                        + "tcp_primary_preference_tolerance_ms AS tcpPrimaryPreferenceToleranceMs,degraded_fallback_enabled AS degradedFallbackEnabled,"
                        + "same_fault_avoidance_enabled AS sameFaultAvoidanceEnabled,topology_avoidance_enabled AS topologyAvoidanceEnabled,"
                        + "min_residency_seconds AS minResidencySeconds,failback_gain_ms AS failbackGainMs,"
                        + "failback_gain_percent AS failbackGainPercent,preheat_enabled AS preheatEnabled,preheat_backup_count AS preheatBackupCount,"
                        + "preheat_strict_isolation AS preheatStrictIsolation,"
                        + "post_switch_verify_enabled AS postSwitchVerifyEnabled,post_switch_reject_suppress_seconds AS postSwitchRejectSuppressSeconds,"
                        + "dns_verify_enabled AS dnsVerifyEnabled,"
                        + "manual_control_mode AS manualControlMode,locked_member_id AS lockedMemberId,manual_lock_until AS manualLockUntil,"
                        + "quality_probe_status AS qualityProbeStatus,"
                        + "quality_probe_error AS qualityProbeError,quality_probe_at AS qualityProbeAt,"
                        + "last_error AS lastError,last_checked_at AS lastCheckedAt,last_switch_at AS lastSwitchAt,g.created_time AS createdTime,"
                        + "CASE WHEN EXISTS (SELECT 1 FROM cross_entry_managed_resource mr WHERE mr.group_id=g.id) "
                        + "THEN 'managed_forward' ELSE 'existing_forward' END AS creationMode,"
                        + "(SELECT mr.target_address FROM cross_entry_managed_resource mr WHERE mr.group_id=g.id LIMIT 1) AS managedTargetAddress,"
                        + "(SELECT mr.public_port FROM cross_entry_managed_resource mr WHERE mr.group_id=g.id LIMIT 1) AS managedPublicPort,"
                        + "(SELECT mr.port_mode FROM cross_entry_managed_resource mr WHERE mr.group_id=g.id LIMIT 1) AS managedPortMode,"
                        + "(SELECT mr.protocol_mode FROM cross_entry_managed_resource mr WHERE mr.group_id=g.id LIMIT 1) AS managedProtocolMode,"
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
        List<ManagedResourceDraft> createdManagedResources = new ArrayList<>();
        Long createdGroupId = null;
        try {
            normalizeAndValidate(dto);
            if (isManagedCreate(dto)) {
                prepareManagedMembers(dto, createdManagedResources);
            } else if (dto.getId() != null && hasManagedResources(dto.getId())) {
                assertManagedMembersUnchanged(dto);
            }
            Integer duplicate = dto.getId() == null
                    ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cross_entry_failover_group WHERE domain=? AND record_type=?",
                    Integer.class, dto.getDomain(), dto.getRecordType())
                    : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cross_entry_failover_group WHERE domain=? AND record_type=? AND id<>?",
                    Integer.class, dto.getDomain(), dto.getRecordType(), dto.getId());
            if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("该域名已配置跨入口容灾");
            List<Map<String, Object>> forwards = loadAndValidateForwards(dto.getMemberForwardIds(), dto.getRecordType());
            schedulingConflictService.assertDnsRecordAvailable("cross_entry", dto.getId(), dto.getDomain(), dto.getRecordType());
            schedulingConflictService.assertForwardSetAvailable("cross_entry", dto.getId(), dto.getMemberForwardIds());
            schedulingConflictService.assertForwardBackedTunnelSetAvailable("cross_entry", dto.getId(), dto.getMemberForwardIds());
            long now = System.currentTimeMillis();
            boolean managedDns = dto.getDnsZoneId() != null;
            DnsProviderService.ZoneAccess zoneAccess = managedDns ? dnsProviderService.loadZoneAccess(dto.getDnsZoneId()) : null;
            String encryptedToken = "";
            String providerZoneId = managedDns ? zoneAccess.providerZoneId() : StringUtils.trimToEmpty(dto.getZoneId());
            Long id = dto.getId();
            Long previousActiveForwardId = null;
            String previousActiveName = null;
            Long requestedLockedForwardId = null;
            String requestedRecordId = dto.getRecordId();
            Map<Long, Map<String, Object>> previousMemberFaultStats = new LinkedHashMap<>();
            if (id == null) {
                if (!managedDns) return R.err("请选择已在 DNS 与域名中登记的 Cloudflare Zone");
            } else {
                List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                        "SELECT g.api_token,g.domain,g.dns_zone_id,g.zone_id,g.record_type,g.record_id,m.forward_id AS activeForwardId,m.node_name AS activeName "
                                + "FROM cross_entry_failover_group g LEFT JOIN cross_entry_failover_member m ON m.id=g.active_member_id WHERE g.id=?", id);
                if (existing.isEmpty()) return R.err("容灾组不存在");
                Map<String, Object> old = existing.get(0);
                jdbcTemplate.queryForList(
                        "SELECT forward_id AS forwardId,status,fail_count AS failCount,success_count AS successCount,latency_ms AS latencyMs,"
                                + "last_error AS lastError,last_checked_at AS lastCheckedAt,last_healthy_at AS lastHealthyAt,last_failure_at AS lastFailureAt,"
                                + "quality_latency_ms AS qualityLatencyMs,quality_p95_ms AS qualityP95Ms,quality_jitter_ms AS qualityJitterMs,"
                                + "quality_loss_percent AS qualityLossPercent,quality_baseline_ms AS qualityBaselineMs,quality_preheated AS qualityPreheated,"
                                + "quality_state AS qualityState,quality_bad_count AS qualityBadCount,quality_good_count AS qualityGoodCount,"
                                + "quality_flap_count AS qualityFlapCount,quality_flap_window_started_at AS qualityFlapWindowStartedAt,"
                                + "quality_suppressed_until AS qualitySuppressedUntil,quality_suppressed_reason AS qualitySuppressedReason,"
                                + "quality_penalty_level AS qualityPenaltyLevel,quality_penalty_episode_count AS qualityPenaltyEpisodeCount,"
                                + "quality_penalty_window_started_at AS qualityPenaltyWindowStartedAt,quality_penalty_last_at AS qualityPenaltyLastAt,"
                                + "quality_recovery_observe_until AS qualityRecoveryObserveUntil,"
                                + "switch_rejected_until AS switchRejectedUntil,switch_rejected_reason AS switchRejectedReason,switch_reject_count AS switchRejectCount,"
                                + "quality_last_error AS qualityLastError,quality_checked_at AS qualityCheckedAt,"
                                + "fault_episode_count AS faultEpisodeCount,connect_fault_count AS connectFaultCount,latency_fault_count AS latencyFaultCount,"
                                + "loss_fault_count AS lossFaultCount,p95_fault_count AS p95FaultCount,jitter_fault_count AS jitterFaultCount,"
                                + "flap_fault_count AS flapFaultCount,switch_trigger_count AS switchTriggerCount,last_fault_type AS lastFaultType,"
                                + "last_fault_reason AS lastFaultReason,last_fault_at AS lastFaultAt "
                                + "FROM cross_entry_failover_member WHERE group_id=?", id)
                        .forEach(row -> previousMemberFaultStats.put(number(row.get("forwardId")).longValue(), row));
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
                if ("lock".equals(dto.getManualControlMode())) {
                    List<Map<String, Object>> lockedRows = jdbcTemplate.queryForList(
                            "SELECT forward_id AS forwardId FROM cross_entry_failover_member WHERE group_id=? AND id=?",
                            id, dto.getLockedMemberId());
                    if (lockedRows.isEmpty()) throw new IllegalArgumentException("锁定入口不存在或已被移除");
                    requestedLockedForwardId = number(lockedRows.get(0).get("forwardId")).longValue();
                }
            }
            String recordId = StringUtils.defaultString(requestedRecordId);

            if (id == null) {
                jdbcTemplate.update("INSERT INTO cross_entry_failover_group "
                                + "(user_id,name,domain,dns_zone_id,zone_id,record_id,api_token,record_type,ttl,probe_interval_ms,connect_timeout_ms,"
                                + "failure_threshold,recovery_threshold,cooldown_seconds,auto_failback,routing_mode,quality_enabled,quality_probe_source_type,"
                                + "quality_probe_source_id,quality_probe_count,quality_degrade_threshold_ms,quality_recover_threshold_ms,quality_degrade_factor,"
                                + "quality_recover_factor,quality_degrade_samples,quality_recover_samples,quality_loss_threshold_percent,"
                                + "quality_p95_threshold_ms,quality_jitter_threshold_ms,quality_fixed_target_enabled,"
                                + "quality_fixed_target_ms,quality_fixed_target_strict,quality_flap_guard_enabled,quality_flap_window_seconds,"
                                + "quality_flap_threshold,quality_flap_suppress_seconds,quality_penalty_enabled,quality_penalty_reset_seconds,"
                                + "quality_penalty_observe_seconds,smart_selection_enabled,tcp_latency_selection_enabled,"
                                + "tcp_latency_switch_threshold_ms,degraded_fallback_enabled,"
                                + "same_fault_avoidance_enabled,topology_avoidance_enabled,min_residency_seconds,failback_gain_ms,"
                                + "failback_gain_percent,preheat_enabled,preheat_backup_count,preheat_strict_isolation,post_switch_verify_enabled,"
                                + "post_switch_reject_suppress_seconds,dns_verify_enabled,"
                                + "manual_control_mode,locked_member_id,manual_lock_until,quality_probe_status,enabled,state,created_time,updated_time) "
                                + "VALUES ("
                                + "?,?,?,?,?,?,?,?,?,?,"
                                + "?,?,?,?,?,?,?,?,?,?,"
                                + "?,?,?,?,?,?,?,?,?,?,"
                                + "?,?,?,?,?,?,?,?,?,?,"
                                + "?,?,?,?,?,?,?,?,?,?,"
                                + "?,?,?,?,?,?,?,?,?,?,"
                                + "?,?"
                                + ")",
                        JwtUtil.getUserIdFromToken(), dto.getName().trim(), dto.getDomain(), dto.getDnsZoneId(), providerZoneId, recordId,
                        encryptedToken, dto.getRecordType(), dto.getTtl(), dto.getProbeIntervalMs(), dto.getConnectTimeoutMs(),
                        dto.getFailureThreshold(), dto.getRecoveryThreshold(), dto.getCooldownSeconds(), dto.getAutoFailback(), dto.getRoutingMode(),
                        dto.getQualityEnabled(), dto.getQualityProbeSourceType(), dto.getQualityProbeSourceId(), dto.getQualityProbeCount(),
                        dto.getQualityDegradeThresholdMs(), dto.getQualityRecoverThresholdMs(), dto.getQualityDegradeFactor(), dto.getQualityRecoverFactor(),
                        dto.getQualityDegradeSamples(), dto.getQualityRecoverSamples(), dto.getQualityLossThresholdPercent(),
                        dto.getQualityP95ThresholdMs(), dto.getQualityJitterThresholdMs(),
                        dto.getQualityFixedTargetEnabled(), dto.getQualityFixedTargetMs(), dto.getQualityFixedTargetStrict(),
                        dto.getQualityFlapGuardEnabled(), dto.getQualityFlapWindowSeconds(), dto.getQualityFlapThreshold(),
                        dto.getQualityFlapSuppressSeconds(), dto.getQualityPenaltyEnabled(), dto.getQualityPenaltyResetSeconds(),
                        dto.getQualityPenaltyObserveSeconds(), dto.getSmartSelectionEnabled(), dto.getTcpLatencySelectionEnabled(),
                        dto.getTcpLatencySwitchThresholdMs(), dto.getDegradedFallbackEnabled(),
                        dto.getSameFaultAvoidanceEnabled(), dto.getTopologyAvoidanceEnabled(), dto.getMinResidencySeconds(),
                        dto.getFailbackGainMs(), dto.getFailbackGainPercent(), dto.getPreheatEnabled(), dto.getPreheatBackupCount(),
                        dto.getPreheatStrictIsolation(), dto.getPostSwitchVerifyEnabled(), dto.getPostSwitchRejectSuppressSeconds(),
                        dto.getDnsVerifyEnabled(), dto.getManualControlMode(), dto.getLockedMemberId(), dto.getManualLockUntil(),
                        dto.getQualityEnabled() ? "pending" : "disabled", dto.getEnabled(), "unknown", now, now);
                id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                createdGroupId = id;
            } else {
                jdbcTemplate.update("UPDATE cross_entry_failover_group SET name=?,domain=?,dns_zone_id=?,zone_id=?,record_id=?,api_token=?,record_type=?,ttl=?,"
                                + "probe_interval_ms=?,connect_timeout_ms=?,failure_threshold=?,recovery_threshold=?,cooldown_seconds=?,"
                                + "auto_failback=?,routing_mode=?,quality_enabled=?,quality_probe_source_type=?,quality_probe_source_id=?,"
                        + "quality_probe_count=?,quality_degrade_threshold_ms=?,quality_recover_threshold_ms=?,quality_degrade_factor=?,"
                                + "quality_recover_factor=?,quality_degrade_samples=?,quality_recover_samples=?,quality_loss_threshold_percent=?,"
                                + "quality_p95_threshold_ms=?,quality_jitter_threshold_ms=?,quality_fixed_target_enabled=?,quality_fixed_target_ms=?,quality_fixed_target_strict=?,"
                                + "quality_flap_guard_enabled=?,quality_flap_window_seconds=?,quality_flap_threshold=?,quality_flap_suppress_seconds=?,"
                                + "quality_penalty_enabled=?,quality_penalty_reset_seconds=?,quality_penalty_observe_seconds=?,"
                                + "smart_selection_enabled=?,tcp_latency_selection_enabled=?,tcp_latency_switch_threshold_ms=?,"
                                + "degraded_fallback_enabled=?,same_fault_avoidance_enabled=?,topology_avoidance_enabled=?,"
                                + "min_residency_seconds=?,failback_gain_ms=?,failback_gain_percent=?,preheat_enabled=?,preheat_backup_count=?,preheat_strict_isolation=?,"
                                + "post_switch_verify_enabled=?,post_switch_reject_suppress_seconds=?,dns_verify_enabled=?,manual_control_mode=?,locked_member_id=?,manual_lock_until=?,"
                                + "quality_probe_status=?,quality_probe_error=NULL,enabled=?,state='unknown',last_error=NULL,updated_time=? WHERE id=?",
                        dto.getName().trim(), dto.getDomain(), dto.getDnsZoneId(), providerZoneId, recordId, encryptedToken, dto.getRecordType(), dto.getTtl(),
                        dto.getProbeIntervalMs(), dto.getConnectTimeoutMs(), dto.getFailureThreshold(), dto.getRecoveryThreshold(),
                        dto.getCooldownSeconds(), dto.getAutoFailback(), dto.getRoutingMode(), dto.getQualityEnabled(), dto.getQualityProbeSourceType(),
                        dto.getQualityProbeSourceId(), dto.getQualityProbeCount(), dto.getQualityDegradeThresholdMs(), dto.getQualityRecoverThresholdMs(),
                        dto.getQualityDegradeFactor(), dto.getQualityRecoverFactor(), dto.getQualityDegradeSamples(), dto.getQualityRecoverSamples(),
                        dto.getQualityLossThresholdPercent(), dto.getQualityP95ThresholdMs(), dto.getQualityJitterThresholdMs(),
                        dto.getQualityFixedTargetEnabled(), dto.getQualityFixedTargetMs(), dto.getQualityFixedTargetStrict(),
                        dto.getQualityFlapGuardEnabled(), dto.getQualityFlapWindowSeconds(), dto.getQualityFlapThreshold(),
                        dto.getQualityFlapSuppressSeconds(), dto.getQualityPenaltyEnabled(), dto.getQualityPenaltyResetSeconds(),
                        dto.getQualityPenaltyObserveSeconds(), dto.getSmartSelectionEnabled(), dto.getTcpLatencySelectionEnabled(),
                        dto.getTcpLatencySwitchThresholdMs(), dto.getDegradedFallbackEnabled(),
                        dto.getSameFaultAvoidanceEnabled(), dto.getTopologyAvoidanceEnabled(), dto.getMinResidencySeconds(),
                        dto.getFailbackGainMs(), dto.getFailbackGainPercent(), dto.getPreheatEnabled(), dto.getPreheatBackupCount(),
                        dto.getPreheatStrictIsolation(), dto.getPostSwitchVerifyEnabled(), dto.getPostSwitchRejectSuppressSeconds(),
                        dto.getDnsVerifyEnabled(), dto.getManualControlMode(), dto.getLockedMemberId(), dto.getManualLockUntil(),
                        dto.getQualityEnabled() ? "pending" : "disabled", dto.getEnabled(), now, id);
                dnsProviderService.clearCrossEntryActiveRecords(dto.getDnsZoneId(), id);
                jdbcTemplate.update("DELETE FROM cross_entry_failover_member WHERE group_id=?", id);
            }
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET tcp_primary_preference_tolerance_ms=?,"
                            + "quality_probe_status=? WHERE id=?",
                    dto.getTcpPrimaryPreferenceToleranceMs(),
                    dto.getQualityEnabled() || dto.getTcpLatencySelectionEnabled() ? "pending" : "disabled", id);

            Long primaryMemberId = null;
            Long retainedActiveMemberId = null;
            Long retainedLockedMemberId = null;
            for (int priority = 0; priority < forwards.size(); priority++) {
                Map<String, Object> forward = forwards.get(priority);
                long forwardId = number(forward.get("id")).longValue();
                jdbcTemplate.update("INSERT INTO cross_entry_failover_member "
                        + "(group_id,forward_id,priority,weight,enabled,entry_node_id,entry_host,entry_address,topology_signature,entry_port,forward_name,node_name,status,created_time,updated_time) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'unknown',?,?)",
                        id, forwardId, priority, memberWeight(dto, priority), true,
                        number(forward.get("inNodeId")).longValue(), forward.get("entryHost"), forward.get("entryAddress"), forward.get("topologySignature"),
                        number(forward.get("inPort")).intValue(),
                        forward.get("name"), forward.get("nodeName"), now, now);
                Long memberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                Map<String, Object> oldFaultStats = previousMemberFaultStats.get(forwardId);
                if (oldFaultStats != null) {
                    jdbcTemplate.update("UPDATE cross_entry_failover_member SET status=?,fail_count=?,success_count=?,latency_ms=?,last_error=?,last_checked_at=?,"
                                    + "last_healthy_at=?,last_failure_at=?,quality_latency_ms=?,quality_p95_ms=?,quality_jitter_ms=?,quality_loss_percent=?,"
                                    + "quality_baseline_ms=?,quality_preheated=?,quality_state=?,quality_bad_count=?,quality_good_count=?,quality_flap_count=?,"
                                    + "quality_flap_window_started_at=?,quality_suppressed_until=?,quality_suppressed_reason=?,quality_penalty_level=?,"
                                    + "quality_penalty_episode_count=?,quality_penalty_window_started_at=?,quality_penalty_last_at=?,quality_recovery_observe_until=?,"
                                    + "switch_rejected_until=?,switch_rejected_reason=?,switch_reject_count=?,"
                                    + "quality_last_error=?,quality_checked_at=?,fault_episode_count=?,connect_fault_count=?,latency_fault_count=?,loss_fault_count=?,p95_fault_count=?,jitter_fault_count=?,"
                                    + "flap_fault_count=?,switch_trigger_count=?,last_fault_type=?,last_fault_reason=?,last_fault_at=? WHERE id=?",
                            oldFaultStats.get("status"), oldFaultStats.get("failCount"), oldFaultStats.get("successCount"),
                            oldFaultStats.get("latencyMs"), oldFaultStats.get("lastError"), oldFaultStats.get("lastCheckedAt"),
                            oldFaultStats.get("lastHealthyAt"), oldFaultStats.get("lastFailureAt"), oldFaultStats.get("qualityLatencyMs"),
                            oldFaultStats.get("qualityP95Ms"), oldFaultStats.get("qualityJitterMs"), oldFaultStats.get("qualityLossPercent"),
                            oldFaultStats.get("qualityBaselineMs"), oldFaultStats.get("qualityPreheated"), oldFaultStats.get("qualityState"),
                            oldFaultStats.get("qualityBadCount"), oldFaultStats.get("qualityGoodCount"), oldFaultStats.get("qualityFlapCount"),
                            oldFaultStats.get("qualityFlapWindowStartedAt"), oldFaultStats.get("qualitySuppressedUntil"),
                            oldFaultStats.get("qualitySuppressedReason"), oldFaultStats.get("qualityPenaltyLevel"), oldFaultStats.get("qualityPenaltyEpisodeCount"),
                            oldFaultStats.get("qualityPenaltyWindowStartedAt"), oldFaultStats.get("qualityPenaltyLastAt"),
                            oldFaultStats.get("qualityRecoveryObserveUntil"), oldFaultStats.get("switchRejectedUntil"),
                            oldFaultStats.get("switchRejectedReason"), oldFaultStats.get("switchRejectCount"), oldFaultStats.get("qualityLastError"),
                            oldFaultStats.get("qualityCheckedAt"), oldFaultStats.get("faultEpisodeCount"), oldFaultStats.get("connectFaultCount"),
                            oldFaultStats.get("latencyFaultCount"), oldFaultStats.get("lossFaultCount"), oldFaultStats.get("p95FaultCount"),
                            oldFaultStats.get("jitterFaultCount"), oldFaultStats.get("flapFaultCount"), oldFaultStats.get("switchTriggerCount"),
                            oldFaultStats.get("lastFaultType"), oldFaultStats.get("lastFaultReason"), oldFaultStats.get("lastFaultAt"), memberId);
                }
                if (priority == 0) primaryMemberId = memberId;
                if (previousActiveForwardId != null && previousActiveForwardId == forwardId) {
                    retainedActiveMemberId = memberId;
                }
                if (requestedLockedForwardId != null && requestedLockedForwardId == forwardId) {
                    retainedLockedMemberId = memberId;
                }
            }
            Long activeMemberId = "active_active".equals(dto.getRoutingMode())
                    ? primaryMemberId
                    : (retainedActiveMemberId == null ? primaryMemberId : retainedActiveMemberId);
            if ("lock".equals(dto.getManualControlMode()) && retainedLockedMemberId == null) {
                throw new IllegalArgumentException("锁定入口未包含在当前入口顺序中");
            }
            Long lockedMemberId = "lock".equals(dto.getManualControlMode()) ? retainedLockedMemberId : null;
            boolean configuredEntryChanged = previousActiveForwardId != null && retainedActiveMemberId == null;
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET active_member_id=?,locked_member_id=?,manual_lock_until=?,last_switch_at=CASE WHEN ? THEN ? ELSE last_switch_at END WHERE id=?",
                    activeMemberId, lockedMemberId, "lock".equals(dto.getManualControlMode()) ? dto.getManualLockUntil() : null,
                    configuredEntryChanged, now, id);

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
            if (!createdManagedResources.isEmpty()) {
                persistManagedResources(id, createdManagedResources);
            }
            if ("active_active".equals(dto.getRoutingMode())) {
                syncActiveEntries(savedGroup, loadMembers(id), "已发布全部入口");
            } else {
                updateCloudflareDns(savedGroup, selectedEntry);
            }
            return R.ok(Map.of("id", id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            int cleanupFailed = cleanupManagedResources(createdManagedResources);
            if (dto.getId() == null && isManagedCreate(dto)) {
                if (createdGroupId != null) {
                    jdbcTemplate.update("DELETE FROM cross_entry_managed_resource WHERE group_id=?", createdGroupId);
                    jdbcTemplate.update("DELETE FROM cross_entry_failover_event WHERE group_id=?", createdGroupId);
                    jdbcTemplate.update("DELETE FROM cross_entry_failover_member WHERE group_id=?", createdGroupId);
                    jdbcTemplate.update("DELETE FROM cross_entry_failover_group WHERE id=?", createdGroupId);
                }
                String suffix = cleanupFailed == 0
                        ? ""
                        : "；有 " + cleanupFailed + " 个自动资源清理失败，请检查转发管理";
                return R.err(StringUtils.defaultIfBlank(e.getMessage(), "托管入口容灾创建失败") + suffix);
            }
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return R.err(e.getMessage());
        }
    }

    @Transactional
    public R delete(Long id) {
        if (!exists(id)) return R.err("容灾组不存在");
        List<Map<String, Object>> managedResources = jdbcTemplate.queryForList(
                "SELECT forward_id AS forwardId,tunnel_id AS tunnelId,created_tunnel AS createdTunnel,"
                        + "port_mode AS portMode,protocol_mode AS protocolMode "
                        + "FROM cross_entry_managed_resource WHERE group_id=?", id);
        Map<String, Object> group = loadGroup(id);
        dnsProviderService.clearCrossEntryActiveRecords(nullableLong(group.get("dnsZoneId")), id);
        dnsProviderService.releaseRecord(id);
        jdbcTemplate.update("DELETE FROM cross_entry_failover_event WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM cross_entry_failover_member WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM cross_entry_failover_group WHERE id=?", id);
        int cleanupFailed = cleanupPersistedManagedResources(id, managedResources);
        if (cleanupFailed > 0) {
            return R.ok(Map.of("cleanupFailed", cleanupFailed,
                    "message", "容灾组已删除，但有 " + cleanupFailed + " 个托管资源清理失败，节点恢复后请在转发管理中检查"));
        }
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
        boolean tcpLatencySelection = bool(group.get("tcpLatencySelectionEnabled"));
        boolean qualityDecisionEnabled = false;
        boolean detailedLatencyMeasured = false;
        if (tcpLatencySelection) {
            detailedLatencyMeasured = updateTcpLatencyMeasurements(group, members, timeout, now);
            members = loadMembers(groupId);
        } else if (qualityEnabledForFailover(group)) {
            qualityDecisionEnabled = updateQuality(group, members, timeout, now);
            detailedLatencyMeasured = qualityDecisionEnabled;
            members = loadMembers(groupId);
        } else {
            markQualityDisabledIfNeeded(group, now);
        }
        Map<String, Object> active = memberById(members, nullableLong(group.get("activeMemberId")));
        if (active == null && !members.isEmpty()) active = members.get(0);
        expireManualLockIfNeeded(group, active, now);
        if ("active_active".equals(Objects.toString(group.get("routingMode"), "failover"))) {
            updateActiveActiveGroup(group, members, now);
            return;
        }
        boolean activeFailed = active == null || "unhealthy".equals(active.get("status"));
        final boolean useQualityDecision = qualityDecisionEnabled;
        final boolean useDetailedLatency = detailedLatencyMeasured;
        boolean activeQualityDegraded = useQualityDecision && isQualityDegraded(active);
        List<CrossEntryFailoverPolicy.Member> snapshots = members.stream()
                .map(member -> policyMember(group, member, useQualityDecision, useDetailedLatency, now))
                .collect(Collectors.toList());
        boolean smartSelection = useQualityDecision && bool(group.get("smartSelectionEnabled"));
        boolean adaptiveSelection = smartSelection || tcpLatencySelection;
        CrossEntryFailoverPolicy.Settings policySettings = new CrossEntryFailoverPolicy.Settings(
                bool(group.get("autoFailback")), number(group.get("recoveryThreshold")).intValue(),
                cooldownElapsed(group, now), !adaptiveSelection || minResidencyElapsed(group, now),
                smartSelection && bool(group.get("degradedFallbackEnabled")),
                smartSelection && bool(group.get("sameFaultAvoidanceEnabled")),
                smartSelection && bool(group.get("topologyAvoidanceEnabled")),
                smartSelection && bool(group.get("preheatEnabled")),
                tcpLatencySelection, number(group.get("tcpLatencySwitchThresholdMs")).intValue(),
                number(group.get("tcpPrimaryPreferenceToleranceMs")).intValue(),
                smartSelection ? number(group.get("failbackGainMs")).intValue() : 0,
                smartSelection ? doubleNumber(group.get("failbackGainPercent")) : 0.0,
                Objects.toString(group.get("manualControlMode"), "auto"), nullableLong(group.get("lockedMemberId")));
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                snapshots, active == null ? null : number(active.get("id")).longValue(), policySettings);
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

    private boolean updateTcpLatencyMeasurements(Map<String, Object> group, List<Map<String, Object>> members,
                                                 int timeoutMs, long now) {
        long groupId = number(group.get("id")).longValue();
        String sourceError = qualitySourceError(group);
        if (sourceError != null) {
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET quality_probe_status='failed',quality_probe_error=?,quality_probe_at=?,updated_time=? WHERE id=?",
                    shorten(sourceError, 500), now, now, groupId);
            return false;
        }
        int count = number(group.get("qualityProbeCount")).intValue();
        List<CompletableFuture<QualityProbeResult>> futures = members.stream()
                .filter(member -> bool(member.get("enabled")))
                .map(member -> CompletableFuture.supplyAsync(() -> probeQuality(group, member, count, timeoutMs), probeExecutor))
                .collect(Collectors.toList());
        List<QualityProbeResult> results = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        List<String> errors = new ArrayList<>();
        for (QualityProbeResult result : results) {
            jdbcTemplate.update("UPDATE cross_entry_failover_member SET quality_latency_ms=?,quality_p95_ms=?,quality_jitter_ms=?,"
                            + "quality_loss_percent=?,quality_last_error=?,quality_checked_at=?,updated_time=? WHERE id=?",
                    result.latencyMs(), result.p95Ms(), result.jitterMs(), result.lossPercent(),
                    shorten(result.error(), 500), now, now, result.member().get("id"));
            if (StringUtils.isNotBlank(result.error())) {
                errors.add(Objects.toString(result.member().get("nodeName"), "入口") + "：" + result.error());
            }
        }
        long successful = results.stream().filter(result -> result.success() && result.latencyMs() != null).count();
        String status = successful == 0 ? "failed" : (errors.isEmpty() ? "ok" : "warning");
        String error = errors.isEmpty() ? null : shorten(errors.get(0) + (errors.size() > 1 ? " 等 " + errors.size() + " 条" : ""), 500);
        jdbcTemplate.update("UPDATE cross_entry_failover_group SET quality_probe_status=?,quality_probe_error=?,quality_probe_at=?,updated_time=? WHERE id=?",
                status, error, now, now, groupId);
        return successful > 0;
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
        markPreheatedBackups(group, now);
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
            return new QualityProbeResult(member, false, null, null, null, 100.0,
                    response == null ? "探测源无响应" : response.getMsg());
        }
        JSONObject data = responseData(response.getData());
        if (data == null) return new QualityProbeResult(member, false, null, null, null, 100.0, "探测源返回为空");
        boolean success = data.getBooleanValue("success");
        double loss = data.containsKey("packetLoss") ? data.getDoubleValue("packetLoss") : (success ? 0.0 : 100.0);
        List<Integer> samples = numericSamples(data.getJSONArray("samples"));
        Integer latency = success ? roundedMetric(data, "averageTime", 1) : null;
        if (latency == null) latency = average(samples);
        Integer p95 = roundedMetric(data, "p95Time", 1);
        if (p95 == null) p95 = percentile(samples, 0.95);
        Integer jitter = roundedMetric(data, "jitter", 0);
        if (jitter == null) jitter = jitter(samples);
        if (success && latency == null) {
            return new QualityProbeResult(member, false, null, null, null, 100.0, "探测源没有返回有效延迟样本");
        }
        String error = success ? null : StringUtils.defaultIfBlank(data.getString("errorMessage"), "TCP 探测失败");
        return new QualityProbeResult(member, success, latency, p95, jitter, loss, error);
    }

    private QualityProbeResult tcpPingLocal(Map<String, Object> member, String address, int port, int count, int timeoutMs) {
        List<Integer> samples = new ArrayList<>();
        String lastError = null;
        for (int i = 0; i < count; i++) {
            long started = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), timeoutMs);
                samples.add(Math.max(1, (int) ((System.nanoTime() - started) / 1_000_000L)));
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
        int success = samples.size();
        double loss = count <= 0 ? 100.0 : (count - success) * 100.0 / count;
        if (success == 0) return new QualityProbeResult(member, false, null, null, null, 100.0,
                StringUtils.defaultIfBlank(lastError, "所有 TCP 探测失败"));
        return new QualityProbeResult(member, true, average(samples), percentile(samples, 0.95), jitter(samples), loss, null);
    }

    private String updateMemberQuality(Map<String, Object> group, QualityProbeResult result, long now) {
        Map<String, Object> member = result.member();
        String oldState = Objects.toString(member.get("qualityState"), "unknown");
        Integer latency = result.latencyMs();
        Integer p95 = result.p95Ms();
        Integer jitter = result.jitterMs();
        Double loss = result.lossPercent();
        CrossEntryQualityEvaluator.Decision decision = CrossEntryQualityEvaluator.evaluate(
                new CrossEntryQualityEvaluator.Snapshot(oldState, nullableInt(member.get("qualityBaselineMs")), latency, p95, jitter, loss,
                        result.success(), number(member.get("qualityBadCount")).intValue(), number(member.get("qualityGoodCount")).intValue()),
                new CrossEntryQualityEvaluator.Settings(number(group.get("qualityDegradeThresholdMs")).intValue(),
                        number(group.get("qualityRecoverThresholdMs")).intValue(), doubleNumber(group.get("qualityDegradeFactor")),
                        doubleNumber(group.get("qualityRecoverFactor")), number(group.get("qualityDegradeSamples")).intValue(),
                        number(group.get("qualityRecoverSamples")).intValue(), doubleNumber(group.get("qualityLossThresholdPercent")),
                        number(group.get("qualityP95ThresholdMs")).intValue(), number(group.get("qualityJitterThresholdMs")).intValue(),
                        bool(group.get("qualityFixedTargetEnabled")), number(group.get("qualityFixedTargetMs")).intValue()));
        CrossEntryQualityFlapGuard.Decision flapDecision = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot(oldState, decision.state(),
                        number(member.get("qualityFlapCount")).intValue(), nullableLong(member.get("qualityFlapWindowStartedAt")),
                        nullableLong(member.get("qualitySuppressedUntil")), Objects.toString(member.get("qualitySuppressedReason"), null),
                        number(member.get("qualityPenaltyLevel")).intValue(), number(member.get("qualityPenaltyEpisodeCount")).intValue(),
                        nullableLong(member.get("qualityPenaltyWindowStartedAt")), nullableLong(member.get("qualityPenaltyLastAt")),
                        nullableLong(member.get("qualityRecoveryObserveUntil")), decision.goodCount(), now),
                new CrossEntryQualityFlapGuard.Settings(bool(group.get("qualityFlapGuardEnabled")),
                        number(group.get("qualityFlapWindowSeconds")).intValue(), number(group.get("qualityFlapThreshold")).intValue(),
                        number(group.get("qualityFlapSuppressSeconds")).intValue(), bool(group.get("qualityPenaltyEnabled")),
                        number(group.get("qualityPenaltyResetSeconds")).intValue(), number(group.get("qualityPenaltyObserveSeconds")).intValue(),
                        number(group.get("qualityRecoverSamples")).intValue()));
        if (loss == null) loss = result.success() ? 0.0 : 100.0;
        FaultStatsUpdate faultStats = qualityFaultStatsUpdate(decision, result.success(), result.error(), oldState,
                "unhealthy".equals(member.get("status")), flapDecision, now);
        jdbcTemplate.update("UPDATE cross_entry_failover_member SET quality_latency_ms=?,quality_p95_ms=?,quality_jitter_ms=?,quality_loss_percent=?,quality_baseline_ms=?,"
                        + "quality_state=?,quality_bad_count=?,quality_good_count=?,quality_flap_count=?,quality_flap_window_started_at=?,"
                        + "quality_suppressed_until=?,quality_suppressed_reason=?,quality_penalty_level=?,quality_penalty_episode_count=?,"
                        + "quality_penalty_window_started_at=?,quality_penalty_last_at=?,quality_recovery_observe_until=?,quality_last_error=?,quality_checked_at=?,"
                        + "fault_episode_count=fault_episode_count+?,connect_fault_count=connect_fault_count+?,latency_fault_count=latency_fault_count+?,loss_fault_count=loss_fault_count+?,"
                        + "p95_fault_count=p95_fault_count+?,jitter_fault_count=jitter_fault_count+?,flap_fault_count=flap_fault_count+?,"
                        + "last_fault_type=COALESCE(?,last_fault_type),last_fault_reason=COALESCE(?,last_fault_reason),"
                        + "last_fault_at=COALESCE(?,last_fault_at),updated_time=? WHERE id=?",
                latency, p95, jitter, loss, decision.baselineMs(), decision.state(), decision.badCount(), decision.goodCount(),
                flapDecision.flapCount(), flapDecision.windowStartedAt(), flapDecision.suppressedUntil(),
                flapDecision.suppressedReason(), flapDecision.penaltyLevel(), flapDecision.penaltyEpisodeCount(),
                flapDecision.penaltyWindowStartedAt(), flapDecision.penaltyLastAt(), flapDecision.recoveryObserveUntil(),
                shorten(result.error(), 500), now,
                faultStats.episodeDelta(), faultStats.connectDelta(), faultStats.latencyDelta(), faultStats.lossDelta(),
                faultStats.p95Delta(), faultStats.jitterDelta(), faultStats.flapDelta(),
                faultStats.type(), faultStats.reason(), faultStats.at(), now, member.get("id"));
        return decision.state();
    }

    static FaultStatsUpdate qualityFaultStatsUpdate(CrossEntryQualityEvaluator.Decision decision,
                                                    boolean probeSuccess, String probeError,
                                                    String oldState, boolean healthFaultAlreadyCounted,
                                                    CrossEntryQualityFlapGuard.Decision flapDecision,
                                                    long now) {
        boolean newlyDegraded = decision != null
                && "degraded".equals(decision.state())
                && !"degraded".equals(oldState);
        int connect = newlyDegraded && !probeSuccess && !healthFaultAlreadyCounted ? 1 : 0;
        int latency = newlyDegraded && decision.latencyBad() ? 1 : 0;
        int loss = newlyDegraded && probeSuccess && decision.lossBad() ? 1 : 0;
        int p95 = newlyDegraded && decision.p95Bad() ? 1 : 0;
        int jitter = newlyDegraded && decision.jitterBad() ? 1 : 0;
        int flap = flapDecision != null && flapDecision.newlySuppressed() ? 1 : 0;
        String type = null;
        if (connect > 0) type = "connect";
        else if (loss > 0) type = "loss";
        else if (p95 > 0) type = "p95";
        else if (jitter > 0) type = "jitter";
        else if (latency > 0) type = "latency";
        else if (flap > 0) type = "flap";
        String reason = type == null ? null : qualityFaultReason(type, decision, probeError);
        int episode = newlyDegraded && !(connect == 0 && !probeSuccess && healthFaultAlreadyCounted) ? 1 : 0;
        return new FaultStatsUpdate(episode, connect, latency, loss, p95, jitter, flap, type, reason,
                type == null ? null : now);
    }

    private static String qualityFaultReason(String type, CrossEntryQualityEvaluator.Decision decision, String probeError) {
        if ("connect".equals(type)) return StringUtils.defaultIfBlank(probeError, "质量探测连接失败");
        if ("loss".equals(type)) return "丢包超过质量阈值";
        if ("p95".equals(type)) return "P95 延迟超过质量阈值";
        if ("jitter".equals(type)) return "抖动超过质量阈值";
        if ("latency".equals(type)) return "延迟超过质量阈值";
        if ("flap".equals(type)) return "质量抖动保护触发";
        return StringUtils.defaultIfBlank(probeError, "质量探测异常");
    }

    private void markPreheatedBackups(Map<String, Object> group, long now) {
        long groupId = number(group.get("id")).longValue();
        if (!bool(group.get("preheatEnabled"))) {
            jdbcTemplate.update("UPDATE cross_entry_failover_member SET quality_preheated=0 WHERE group_id=?", groupId);
            return;
        }
        int limit = Math.max(1, number(group.get("preheatBackupCount")).intValue());
        Long activeId = nullableLong(group.get("activeMemberId"));
        List<Map<String, Object>> currentMembers = loadMembers(groupId);
        Set<Long> selected = new HashSet<>();
        Set<Long> nodeIds = new HashSet<>();
        Set<String> topologyKeys = new HashSet<>();
        Map<String, Object> active = memberById(currentMembers, activeId);
        if (active != null) {
            nodeIds.add(number(active.get("entryNodeId")).longValue());
            topologyKeys.addAll(topologyKeys(active));
        }
        List<Map<String, Object>> candidates = currentMembers.stream()
                .filter(member -> bool(member.get("enabled")))
                .filter(member -> activeId == null || number(member.get("id")).longValue() != activeId)
                .filter(member -> "healthy".equals(member.get("status")))
                .filter(member -> "healthy".equals(member.get("qualityState")))
                .filter(member -> nullableInt(member.get("qualityLatencyMs")) != null)
                .filter(member -> !isQualitySuppressed(group, member, now))
                .collect(Collectors.toList());
        for (Map<String, Object> member : candidates) {
            long nodeId = number(member.get("entryNodeId")).longValue();
            Set<String> currentKeys = topologyKeys(member);
            if (nodeIds.contains(nodeId) || CrossEntryTopology.overlaps(topologyKeys, currentKeys)) {
                continue;
            }
            selected.add(number(member.get("id")).longValue());
            nodeIds.add(nodeId);
            topologyKeys.addAll(currentKeys);
            if (selected.size() >= limit) break;
        }
        if (!bool(group.get("preheatStrictIsolation")) && selected.size() < limit) {
            for (Map<String, Object> member : candidates) {
                long id = number(member.get("id")).longValue();
                if (selected.add(id) && selected.size() >= limit) break;
            }
        }
        jdbcTemplate.update("UPDATE cross_entry_failover_member SET quality_preheated=0 WHERE group_id=?", groupId);
        for (Long id : selected) {
            jdbcTemplate.update("UPDATE cross_entry_failover_member SET quality_preheated=1,updated_time=? WHERE id=?", now, id);
        }
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
        String oldStatus = Objects.toString(result.member().get("status"), "unknown");
        if (result.healthy()) {
            jdbcTemplate.update("UPDATE cross_entry_failover_member SET status='healthy',fail_count=0,success_count=?,latency_ms=?,"
                            + "last_error=NULL,last_checked_at=?,last_healthy_at=?,updated_time=? WHERE id=?",
                    oldSuccess + 1, result.latencyMs(), now, now, now, result.member().get("id"));
        } else {
            int failures = oldFails + 1;
            String status = failures >= failureThreshold ? "unhealthy" : Objects.toString(result.member().get("status"), "unknown");
            boolean newConnectFault = "unhealthy".equals(status) && !"unhealthy".equals(oldStatus);
            boolean qualityFaultActive = isQualityDegraded(result.member());
            boolean connectCauseAlreadyCounted = qualityFaultActive
                    && "connect".equals(Objects.toString(result.member().get("lastFaultType"), ""));
            int episodeDelta = newConnectFault && !qualityFaultActive ? 1 : 0;
            int connectDelta = newConnectFault && !connectCauseAlreadyCounted ? 1 : 0;
            jdbcTemplate.update("UPDATE cross_entry_failover_member SET status=?,fail_count=?,success_count=0,latency_ms=NULL,"
                            + "last_error=?,last_checked_at=?,last_failure_at=?,fault_episode_count=fault_episode_count+?,connect_fault_count=connect_fault_count+?,"
                            + "last_fault_type=COALESCE(?,last_fault_type),last_fault_reason=COALESCE(?,last_fault_reason),"
                            + "last_fault_at=COALESCE(?,last_fault_at),updated_time=? WHERE id=?",
                    status, failures, result.error(), now, now, episodeDelta, connectDelta,
                    connectDelta > 0 ? "connect" : null,
                    connectDelta > 0 ? shorten(StringUtils.defaultIfBlank(result.error(), "入口连接失败"), 255) : null,
                    connectDelta > 0 ? now : null, now, result.member().get("id"));
        }
    }

    private void switchEntry(Map<String, Object> group, Map<String, Object> from, Map<String, Object> to, String reason, long now) {
        long groupId = number(group.get("id")).longValue();
        boolean dnsChanged = false;
        try {
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET state='switching',updated_time=? WHERE id=?", now, groupId);
            if (from != null && ("unhealthy".equals(from.get("status")) || isQualityDegraded(from))) {
                recordSwitchTrigger(from, reason, now);
            }
            updateCloudflareDns(group, to);
            dnsChanged = true;
            if (bool(group.get("postSwitchVerifyEnabled"))) {
                ProbeResult targetProbe = probe(to, number(group.get("connectTimeoutMs")).intValue());
                if (!targetProbe.healthy()) {
                    markPostSwitchRejected(group, to, targetProbe.error(), now);
                    throw new IllegalStateException("切换后目标入口验证失败：" + targetProbe.error());
                }
            }
            String dnsDetail = "";
            if (bool(group.get("dnsVerifyEnabled"))) {
                DnsVerification dnsVerification = verifyDnsAfterSwitch(group, to);
                if (!dnsVerification.providerMatched()) {
                    throw new IllegalStateException(dnsVerification.message());
                }
                if (!dnsVerification.publicMatched()) {
                    dnsDetail = "；" + dnsVerification.message();
                }
            }
            jdbcTemplate.update("UPDATE cross_entry_failover_group SET active_member_id=?,state='healthy',last_error=NULL,"
                            + "last_checked_at=?,last_switch_at=?,updated_time=? WHERE id=?",
                    to.get("id"), now, now, now, groupId);
            addEvent(groupId, from == null ? null : nullableLong(from.get("id")), nullableLong(to.get("id")), reason,
                    "success", "DNS 已切换至 " + to.get("nodeName") + " · " + to.get("entryAddress") + dnsDetail);
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

    private void markPostSwitchRejected(Map<String, Object> group, Map<String, Object> member, String error, long now) {
        int suppressSeconds = Math.max(60, number(group.get("postSwitchRejectSuppressSeconds")).intValue());
        long rejectedUntil = now + suppressSeconds * 1000L;
        String reason = StringUtils.defaultIfBlank(error, "切换后目标入口验证失败");
        jdbcTemplate.update("UPDATE cross_entry_failover_member SET status='unhealthy',fail_count=fail_count+1,success_count=0,"
                        + "last_error=?,last_checked_at=?,last_failure_at=?,switch_rejected_until=?,switch_rejected_reason=?,switch_reject_count=switch_reject_count+1,"
                        + "quality_suppressed_until=CASE WHEN quality_suppressed_until IS NULL OR quality_suppressed_until < ? THEN ? ELSE quality_suppressed_until END,"
                        + "quality_suppressed_reason=?,fault_episode_count=fault_episode_count+1,connect_fault_count=connect_fault_count+1,"
                        + "last_fault_type='connect',last_fault_reason=?,last_fault_at=?,updated_time=? WHERE id=?",
                shorten(reason, 500), now, now, rejectedUntil,
                shorten(reason, 255), rejectedUntil, rejectedUntil, "切换验证失败黑名单",
                shorten(reason, 255),
                now, now, member.get("id"));
    }

    private void recordSwitchTrigger(Map<String, Object> member, String reason, long now) {
        jdbcTemplate.update("UPDATE cross_entry_failover_member SET switch_trigger_count=switch_trigger_count+1,updated_time=? WHERE id=?",
                now, member.get("id"));
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

    private DnsVerification verifyDnsAfterSwitch(Map<String, Object> group, Map<String, Object> member) {
        String expected = Objects.toString(member.get("entryAddress"), "");
        String type = Objects.toString(group.get("recordType"), "A");
        String domain = Objects.toString(group.get("domain"));
        String providerContent = readProviderRecordContent(group);
        if (!expected.equalsIgnoreCase(providerContent)) {
            return new DnsVerification(false, false,
                    "DNS 服务商记录确认失败：期望 " + expected + "，实际 " + StringUtils.defaultIfBlank(providerContent, "空"));
        }
        PublicDnsVerification publicDns = queryPublicDns(domain, type, expected);
        if (!publicDns.matched()) {
            return new DnsVerification(true, false, "公共 DNS 暂未全部生效：" + publicDns.detail());
        }
        return new DnsVerification(true, true, "DNS 已确认生效");
    }

    private String readProviderRecordContent(Map<String, Object> group) {
        Long dnsZoneId = nullableLong(group.get("dnsZoneId"));
        String zoneId;
        String token;
        if (dnsZoneId != null) {
            DnsProviderService.ZoneAccess access = dnsProviderService.loadZoneAccess(dnsZoneId);
            zoneId = access.providerZoneId();
            token = access.token();
        } else {
            zoneId = Objects.toString(group.get("zoneId"), "");
            token = crypto().decryptString(Objects.toString(group.get("apiToken")));
        }
        String url = CF_API + "/zones/" + zoneId + "/dns_records/" + Objects.toString(group.get("recordId"));
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(cloudflareHeaders(token)), String.class);
            JSONObject json = JSON.parseObject(response.getBody());
            if (json == null || !json.getBooleanValue("success")) throw new IllegalStateException(cloudflareError(json));
            JSONObject result = json.getJSONObject("result");
            return result == null ? "" : StringUtils.trimToEmpty(result.getString("content"));
        } catch (RestClientException e) {
            throw new IllegalStateException("Cloudflare DNS 记录确认失败");
        }
    }

    private PublicDnsVerification queryPublicDns(String domain, String type, String expected) {
        List<String> details = new ArrayList<>();
        int matched = 0;
        for (String endpoint : List.of("https://cloudflare-dns.com/dns-query", "https://dns.google/resolve")) {
            try {
                URI uri = UriComponentsBuilder.fromHttpUrl(endpoint)
                        .queryParam("name", domain).queryParam("type", type).build(true).toUri();
                HttpHeaders headers = new HttpHeaders();
                headers.setAccept(List.of(MediaType.valueOf("application/dns-json")));
                ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                List<String> answers = parseDnsAnswers(type, response.getBody());
                if (answers.stream().anyMatch(answer -> answer.equalsIgnoreCase(expected))) matched++;
                details.add(endpointHost(endpoint) + "=" + (answers.isEmpty() ? "空" : String.join(",", answers)));
            } catch (RuntimeException e) {
                details.add(endpointHost(endpoint) + "=查询失败");
            }
        }
        return new PublicDnsVerification(matched > 0, String.join("；", details));
    }

    private List<String> parseDnsAnswers(String type, String responseBody) {
        JSONObject body = JSON.parseObject(responseBody);
        if (body == null || body.getIntValue("Status") != 0) return List.of();
        int expectedType = "AAAA".equalsIgnoreCase(type) ? 28 : 1;
        JSONArray answers = body.getJSONArray("Answer");
        if (answers == null) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            JSONObject answer = answers.getJSONObject(i);
            if (answer.getIntValue("type") != expectedType) continue;
            String value = StringUtils.removeEnd(StringUtils.trimToEmpty(answer.getString("data")), ".");
            if (StringUtils.isNotBlank(value) && !result.contains(value)) result.add(value);
        }
        return result;
    }

    private String endpointHost(String endpoint) {
        try {
            return URI.create(endpoint).getHost();
        } catch (RuntimeException e) {
            return endpoint;
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

    private boolean isManagedCreate(CrossEntryFailoverSaveDto dto) {
        return dto.getId() == null && "managed_forward".equalsIgnoreCase(dto.getCreationMode());
    }

    private boolean hasManagedResources(Long groupId) {
        if (groupId == null) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_entry_managed_resource WHERE group_id=?", Integer.class, groupId);
        return count != null && count > 0;
    }

    private void assertManagedMembersUnchanged(CrossEntryFailoverSaveDto dto) {
        List<Map<String, Object>> stored = jdbcTemplate.queryForList(
                "SELECT mr.forward_id AS forwardId,mr.entry_node_id AS entryNodeId,mr.target_address AS targetAddress,"
                        + "mr.public_port AS publicPort,m.priority "
                        + "FROM cross_entry_managed_resource mr "
                        + "LEFT JOIN cross_entry_failover_member m ON m.group_id=mr.group_id AND m.forward_id=mr.forward_id "
                        + "WHERE mr.group_id=? ORDER BY COALESCE(m.priority,999),mr.id", dto.getId());
        if (stored.isEmpty()) return;

        List<Long> storedForwardIds = stored.stream()
                .map(row -> number(row.get("forwardId")).longValue())
                .collect(Collectors.toList());
        List<Long> requestedForwardIds = dto.getMemberForwardIds() == null
                ? List.of()
                : dto.getMemberForwardIds().stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (!requestedForwardIds.isEmpty() && !requestedForwardIds.equals(storedForwardIds)) {
            throw new IllegalArgumentException("托管入口的节点顺序不能在编辑时修改，请删除后重新创建");
        }
        if (requestedForwardIds.isEmpty()) dto.setMemberForwardIds(storedForwardIds);

        if (dto.getManagedEntryNodeIds() != null && !dto.getManagedEntryNodeIds().isEmpty()) {
            List<Long> storedNodeIds = stored.stream()
                    .map(row -> number(row.get("entryNodeId")).longValue())
                    .collect(Collectors.toList());
            if (!dto.getManagedEntryNodeIds().equals(storedNodeIds)) {
                throw new IllegalArgumentException("托管入口的节点顺序不能在编辑时修改，请删除后重新创建");
            }
        }
        String requestedTarget = StringUtils.trimToNull(dto.getManagedTargetAddress());
        if (requestedTarget != null
                && !requestedTarget.equalsIgnoreCase(Objects.toString(stored.get(0).get("targetAddress"), ""))) {
            throw new IllegalArgumentException("托管落地目标不能在编辑时修改，请删除后重新创建");
        }
        if (dto.getManagedPublicPort() != null
                && dto.getManagedPublicPort() != number(stored.get(0).get("publicPort")).intValue()) {
            throw new IllegalArgumentException("托管公共端口不能在编辑时修改，请删除后重新创建");
        }
    }

    private void prepareManagedMembers(CrossEntryFailoverSaveDto dto,
                                       List<ManagedResourceDraft> createdResources) {
        List<Long> nodeIds = dto.getManagedEntryNodeIds() == null
                ? List.of()
                : dto.getManagedEntryNodeIds().stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (nodeIds.size() < 2) throw new IllegalArgumentException("托管入口容灾至少需要两个入口节点");
        if (nodeIds.size() > 10) throw new IllegalArgumentException("单个容灾组最多配置10个入口节点");

        String target = normalizeManagedTargetAddress(dto.getManagedTargetAddress());
        dto.setManagedTargetAddress(target);
        List<Node> nodes = new ArrayList<>();
        Set<String> addresses = new HashSet<>();
        for (Long nodeId : nodeIds) {
            Node node = nodeService.getNodeById(nodeId);
            if (node == null) throw new IllegalArgumentException("托管入口节点不存在：" + nodeId);
            if (!WebSocketServer.isNodeOnline(nodeId)) {
                throw new IllegalArgumentException("托管入口节点离线：" + node.getName());
            }
            String host = nodeAddress(node);
            if (StringUtils.isBlank(host)) throw new IllegalArgumentException("入口节点缺少公网地址：" + node.getName());
            String address = resolveAddress(host, dto.getRecordType());
            if (!addresses.add(address)) throw new IllegalArgumentException("托管入口节点不能使用相同的公网 IP：" + address);
            nodes.add(node);
        }

        int publicPort = chooseManagedPublicPort(dto, nodes);
        dto.setManagedPublicPort(publicPort);
        List<Long> forwardIds = new ArrayList<>();
        for (Node node : nodes) {
            DirectTunnelResult tunnel = ensureManagedDirectTunnel(node);
            ManagedResourceDraft draft = new ManagedResourceDraft(
                    tunnel.tunnel().getId(), node.getId(), tunnel.created(), target, publicPort,
                    dto.getManagedPortMode(), dto.getManagedProtocolMode());
            createdResources.add(draft);

            ForwardDto forward = new ForwardDto();
            forward.setName(dto.getName().trim() + " · " + node.getName());
            forward.setTunnelId(tunnel.tunnel().getId().intValue());
            forward.setRemoteAddr(target);
            forward.setInPort(publicPort);
            forward.setProtocolMode(dto.getManagedProtocolMode());
            R result = forwardService.createForward(forward);
            if (result.getCode() != 0) {
                throw new IllegalStateException("节点 " + node.getName() + " 的托管转发创建失败：" + result.getMsg());
            }
            Long forwardId = extractId(result.getData());
            if (forwardId == null) {
                throw new IllegalStateException("节点 " + node.getName() + " 的托管转发已返回成功，但未能读取转发 ID");
            }
            draft.forwardId = forwardId;
            forwardIds.add(forwardId);
        }
        dto.setMemberForwardIds(forwardIds);
    }

    private int chooseManagedPublicPort(CrossEntryFailoverSaveDto dto, List<Node> nodes) {
        if ("custom".equals(dto.getManagedPortMode())) {
            int port = dto.getManagedPublicPort();
            if (!isManagedPortAvailable(nodes, port, dto.getManagedProtocolMode())) {
                throw new IllegalArgumentException("自定义公共端口 " + port + " 在至少一个入口节点上已被占用");
            }
            return port;
        }
        int start = dto.getManagedPortRangeStart();
        int end = dto.getManagedPortRangeEnd();
        for (int port = start; port <= end; port++) {
            if (isManagedPortAvailable(nodes, port, dto.getManagedProtocolMode())) return port;
        }
        throw new IllegalArgumentException("指定端口范围内没有找到所有入口都空闲的公共端口");
    }

    private boolean isManagedPortAvailable(List<Node> nodes, int port, String protocolMode) {
        for (Node node : nodes) {
            if (isLedgerPortOccupied(node, port)) return false;
            if (!isManagedPortAvailableByAgent(node, port, protocolMode)) return false;
        }
        return true;
    }

    private boolean isLedgerPortOccupied(Node node, int port) {
        try {
            PortLedgerQueryDto query = new PortLedgerQueryDto();
            query.setNodeId(node.getId());
            query.setPort(port);
            Object rawEntries = portLedgerService.list(query).get("entries");
            if (rawEntries instanceof List<?> entries) {
                for (Object raw : entries) {
                    if (!(raw instanceof PortLedgerEntryDto entry)) continue;
                    Integer start = entry.getPortStart();
                    Integer end = entry.getPortEnd();
                    if (start != null && end != null && port >= start && port <= end) return true;
                }
            }
            Integer managed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cross_entry_managed_resource WHERE entry_node_id=? AND public_port=?",
                    Integer.class, node.getId(), port);
            return managed != null && managed > 0;
        } catch (DataAccessException e) {
            throw new IllegalStateException("读取节点 " + node.getName() + " 端口账本失败");
        }
    }

    private boolean isManagedPortAvailableByAgent(Node node, int port, String protocolMode) {
        List<AgentPortCheckUtil.Check> checks = new ArrayList<>();
        if ("tcp".equals(protocolMode) || "tcp_udp".equals(protocolMode)) {
            checks.add(new AgentPortCheckUtil.Check("tcp", "", port));
        }
        if ("tcp_udp".equals(protocolMode)) {
            checks.add(new AgentPortCheckUtil.Check("udp", "", port));
        }
        return AgentPortCheckUtil.check(node, checks).isAvailable();
    }

    private DirectTunnelResult ensureManagedDirectTunnel(Node node) {
        Integer roleId = JwtUtil.getRoleIdFromToken();
        Integer userId = JwtUtil.getUserIdFromToken();
        List<Long> existingIds = jdbcTemplate.query(
                "SELECT id FROM tunnel WHERE type=1 AND status=1 AND in_node_id=? "
                        + "AND (owner_user_id=? OR ?=0) ORDER BY id",
                (rs, rowNum) -> rs.getLong(1), node.getId(), userId, roleId == null ? -1 : roleId);
        for (Long existingId : existingIds) {
            Tunnel existing = tunnelService.getById(existingId);
            if (existing != null) return new DirectTunnelResult(existing, false);
        }

        TunnelDto tunnelDto = new TunnelDto();
        tunnelDto.setName("托管入口-" + node.getName() + "-" + Long.toHexString(System.nanoTime()));
        tunnelDto.setInNodeId(node.getId());
        tunnelDto.setType(1);
        tunnelDto.setFlow(1);
        tunnelDto.setTrafficRatio(BigDecimal.ONE);
        tunnelDto.setTcpListenAddr("0.0.0.0");
        tunnelDto.setUdpListenAddr("0.0.0.0");
        R result = tunnelService.createTunnel(tunnelDto);
        if (result.getCode() != 0) {
            throw new IllegalStateException("节点 " + node.getName() + " 的内部直连隧道创建失败：" + result.getMsg());
        }
        Long tunnelId = extractId(result.getData());
        if (tunnelId == null) {
            tunnelId = jdbcTemplate.queryForObject("SELECT id FROM tunnel WHERE name=? ORDER BY id DESC LIMIT 1",
                    Long.class, tunnelDto.getName());
        }
        Tunnel created = tunnelId == null ? null : tunnelService.getById(tunnelId);
        if (created == null) throw new IllegalStateException("内部直连隧道创建成功，但未能读取隧道记录");
        return new DirectTunnelResult(created, true);
    }

    private String normalizeManagedTargetAddress(String raw) {
        String value = StringUtils.trimToEmpty(raw);
        if (value.isEmpty() || value.contains(",") || value.matches(".*\\s+.*")) {
            throw new IllegalArgumentException("落地目标必须填写单个 IP 或域名加端口");
        }
        String host;
        String portText;
        if (value.startsWith("[")) {
            int end = value.indexOf("]:");
            if (end <= 1) throw new IllegalArgumentException("IPv6 落地目标必须使用 [IPv6]:端口 格式");
            host = value.substring(1, end);
            portText = value.substring(end + 2);
            value = "[" + host + "]:" + portText;
        } else {
            int separator = value.lastIndexOf(':');
            if (separator <= 0 || separator != value.indexOf(':')) {
                throw new IllegalArgumentException("落地目标必须使用 IP:端口 或域名:端口格式；IPv6 请加方括号");
            }
            host = value.substring(0, separator);
            portText = value.substring(separator + 1);
        }
        if (StringUtils.isBlank(host) || host.contains("/") || !portText.matches("\\d+")) {
            throw new IllegalArgumentException("落地目标格式不正确");
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("落地端口必须是 1-65535");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("落地端口必须是 1-65535");
        return value;
    }

    private String nodeAddress(Node node) {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(node.getServerIp()), StringUtils.trimToNull(node.getIp()));
    }

    private Long extractId(Object data) {
        if (data instanceof Number number) return number.longValue();
        if (data instanceof Map<?, ?> map) {
            Object id = map.get("id");
            if (id == null) id = map.get("forwardId");
            if (id instanceof Number number) return number.longValue();
            if (id != null) {
                try {
                    return Long.parseLong(id.toString());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private void persistManagedResources(Long groupId, List<ManagedResourceDraft> resources) {
        long now = System.currentTimeMillis();
        for (ManagedResourceDraft resource : resources) {
            if (resource.forwardId == null) continue;
            jdbcTemplate.update("INSERT INTO cross_entry_managed_resource "
                            + "(group_id,forward_id,tunnel_id,entry_node_id,target_address,public_port,port_mode,protocol_mode,created_tunnel,created_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    groupId, resource.forwardId, resource.tunnelId, resource.entryNodeId, resource.targetAddress,
                    resource.publicPort, resource.portMode, resource.protocolMode, resource.createdTunnel, now);
        }
    }

    private int cleanupManagedResources(List<ManagedResourceDraft> resources) {
        int failures = 0;
        List<ManagedResourceDraft> reverse = new ArrayList<>(resources);
        Collections.reverse(reverse);
        for (ManagedResourceDraft resource : reverse) {
            boolean forwardRemoved = resource.forwardId == null;
            if (resource.forwardId != null) {
                jdbcTemplate.update("DELETE FROM cross_entry_failover_member WHERE forward_id=?", resource.forwardId);
                try {
                    R result = forwardService.deleteManagedForward(resource.forwardId);
                    forwardRemoved = result.getCode() == 0 || isMissingResource(result.getMsg());
                    if (!forwardRemoved) failures++;
                } catch (RuntimeException e) {
                    failures++;
                    log.warn("托管转发 {} 清理失败：{}", resource.forwardId, e.getMessage());
                }
            }
            if (resource.createdTunnel && resource.tunnelId != null && forwardRemoved) {
                try {
                    R result = tunnelService.deleteTunnel(resource.tunnelId);
                    if (result.getCode() != 0 && !isMissingResource(result.getMsg())) failures++;
                } catch (RuntimeException e) {
                    failures++;
                    log.warn("托管隧道 {} 清理失败：{}", resource.tunnelId, e.getMessage());
                }
            }
        }
        return failures;
    }

    private int cleanupPersistedManagedResources(Long groupId, List<Map<String, Object>> rows) {
        List<ManagedResourceDraft> resources = rows.stream()
                .map(row -> {
                    ManagedResourceDraft draft = new ManagedResourceDraft(
                            nullableLong(row.get("tunnelId")), null, bool(row.get("createdTunnel")), null, 0,
                            Objects.toString(row.get("portMode"), "auto"),
                            Objects.toString(row.get("protocolMode"), "tcp"));
                    draft.forwardId = nullableLong(row.get("forwardId"));
                    return draft;
                })
                .collect(Collectors.toList());
        int failures = cleanupManagedResources(resources);
        jdbcTemplate.update("DELETE FROM cross_entry_managed_resource WHERE group_id=?", groupId);
        return failures;
    }

    private boolean isMissingResource(String message) {
        String value = StringUtils.defaultString(message);
        return value.contains("不存在") || value.contains("not found");
    }

    private List<Map<String, Object>> loadAndValidateForwards(List<Long> ids, String recordType) {
        List<Long> distinctIds = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinctIds.size() < 2) throw new IllegalArgumentException("跨入口容灾至少需要两个入口转发");
        if (distinctIds.size() > 10) throw new IllegalArgumentException("单个容灾组最多配置10个入口");
        String placeholders = distinctIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT f.id,f.name,f.in_port AS inPort,f.status,"
                        + "COALESCE(f.protocol_mode,'tcp') AS protocolMode,t.in_node_id AS inNodeId,"
                        + "COALESCE(NULLIF(n.server_ip,''),n.ip,t.in_ip) AS entryHost,COALESCE(n.name,CONCAT('节点',t.in_node_id)) AS nodeName,"
                        + "a.provider AS assetProvider,a.asn AS assetAsn "
                        + "FROM forward f JOIN tunnel t ON t.id=f.tunnel_id LEFT JOIN node n ON n.id=t.in_node_id "
                        + "LEFT JOIN server_asset a ON a.node_id=t.in_node_id WHERE f.id IN (" + placeholders + ")",
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
            row.put("topologySignature", CrossEntryTopology.signature(address,
                    Objects.toString(row.get("assetProvider"), ""),
                    Objects.toString(row.get("assetAsn"), ""),
                    Objects.toString(row.get("nodeName"), "") + " " + Objects.toString(row.get("name"), "")));
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
        String creationMode = StringUtils.lowerCase(
                StringUtils.defaultIfBlank(dto.getCreationMode(), "existing_forward"), Locale.ROOT);
        if (!Set.of("existing_forward", "managed_forward").contains(creationMode)) {
            throw new IllegalArgumentException("入口创建方式不正确");
        }
        dto.setCreationMode(creationMode);
        if ("managed_forward".equals(creationMode)) {
            dto.setManagedTargetAddress(normalizeManagedTargetAddress(dto.getManagedTargetAddress()));
            String portMode = StringUtils.lowerCase(
                    StringUtils.defaultIfBlank(dto.getManagedPortMode(), "auto"), Locale.ROOT);
            if (!Set.of("auto", "custom").contains(portMode)) {
                throw new IllegalArgumentException("托管公共端口模式不正确");
            }
            dto.setManagedPortMode(portMode);
            String protocolMode = StringUtils.lowerCase(
                    StringUtils.defaultIfBlank(dto.getManagedProtocolMode(), "tcp"), Locale.ROOT);
            if (!Set.of("tcp", "tcp_udp").contains(protocolMode)) {
                throw new IllegalArgumentException("托管转发协议不正确");
            }
            dto.setManagedProtocolMode(protocolMode);
            dto.setManagedPortRangeStart(clamp(dto.getManagedPortRangeStart(), 1, 65535));
            dto.setManagedPortRangeEnd(clamp(dto.getManagedPortRangeEnd(), 1, 65535));
            if (dto.getManagedPortRangeEnd() < dto.getManagedPortRangeStart()) {
                throw new IllegalArgumentException("自动端口范围结束端口不能小于起始端口");
            }
            if ("custom".equals(portMode)) {
                if (dto.getManagedPublicPort() == null) {
                    throw new IllegalArgumentException("请填写自定义公共端口");
                }
                dto.setManagedPublicPort(clamp(dto.getManagedPublicPort(), 1, 65535));
            }
        }
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
        dto.setTcpLatencySelectionEnabled(Boolean.TRUE.equals(dto.getTcpLatencySelectionEnabled()));
        if ("active_active".equals(routingMode)) dto.setTcpLatencySelectionEnabled(false);
        dto.setQualityProbeSourceType(StringUtils.lowerCase(StringUtils.defaultIfBlank(dto.getQualityProbeSourceType(), "panel"), Locale.ROOT));
        if (!Set.of("panel", "node", "connector").contains(dto.getQualityProbeSourceType())) {
            throw new IllegalArgumentException("质量探测源类型不正确");
        }
        boolean detailedProbeEnabled = dto.getQualityEnabled() || dto.getTcpLatencySelectionEnabled();
        if (detailedProbeEnabled && !"panel".equals(dto.getQualityProbeSourceType()) && dto.getQualityProbeSourceId() == null) {
            throw new IllegalArgumentException("请选择质量探测源");
        }
        if (detailedProbeEnabled && "node".equals(dto.getQualityProbeSourceType())) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM node WHERE id=?", Integer.class, dto.getQualityProbeSourceId());
            if (count == null || count == 0) throw new IllegalArgumentException("质量探测节点不存在");
        }
        if (detailedProbeEnabled && "connector".equals(dto.getQualityProbeSourceType())) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM internal_connector WHERE id=? AND status=1", Integer.class, dto.getQualityProbeSourceId());
            if (count == null || count == 0) throw new IllegalArgumentException("质量探测 Connector 不存在");
        }
        if (!detailedProbeEnabled || "panel".equals(dto.getQualityProbeSourceType())) dto.setQualityProbeSourceId(null);
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
        dto.setQualityP95ThresholdMs(clamp(dto.getQualityP95ThresholdMs(), 20, 30000));
        dto.setQualityJitterThresholdMs(clamp(dto.getQualityJitterThresholdMs(), 1, 30000));
        dto.setQualityFixedTargetEnabled(Boolean.TRUE.equals(dto.getQualityFixedTargetEnabled()));
        dto.setQualityFixedTargetMs(clamp(dto.getQualityFixedTargetMs(), 1, 30000));
        dto.setQualityFixedTargetStrict(!Boolean.FALSE.equals(dto.getQualityFixedTargetStrict()));
        dto.setQualityFlapGuardEnabled(!Boolean.FALSE.equals(dto.getQualityFlapGuardEnabled()));
        dto.setQualityFlapWindowSeconds(clamp(dto.getQualityFlapWindowSeconds(), 60, 86400));
        dto.setQualityFlapThreshold(clamp(dto.getQualityFlapThreshold(), 2, 20));
        dto.setQualityFlapSuppressSeconds(clamp(dto.getQualityFlapSuppressSeconds(), 60, 86400));
        dto.setQualityPenaltyEnabled(!Boolean.FALSE.equals(dto.getQualityPenaltyEnabled()));
        dto.setQualityPenaltyResetSeconds(clamp(dto.getQualityPenaltyResetSeconds(), 3600, 604800));
        dto.setQualityPenaltyObserveSeconds(clamp(dto.getQualityPenaltyObserveSeconds(), 0, 86400));
        if (!dto.getQualityFlapGuardEnabled()) dto.setQualityPenaltyEnabled(false);
        dto.setSmartSelectionEnabled(!Boolean.FALSE.equals(dto.getSmartSelectionEnabled()));
        dto.setTcpLatencySwitchThresholdMs(clamp(dto.getTcpLatencySwitchThresholdMs(), 0, 30000));
        dto.setTcpPrimaryPreferenceToleranceMs(clamp(dto.getTcpPrimaryPreferenceToleranceMs(), 0, 30000));
        dto.setDegradedFallbackEnabled(!Boolean.FALSE.equals(dto.getDegradedFallbackEnabled()));
        dto.setSameFaultAvoidanceEnabled(!Boolean.FALSE.equals(dto.getSameFaultAvoidanceEnabled()));
        dto.setTopologyAvoidanceEnabled(!Boolean.FALSE.equals(dto.getTopologyAvoidanceEnabled()));
        dto.setMinResidencySeconds(clamp(dto.getMinResidencySeconds(), 0, 86400));
        dto.setFailbackGainMs(clamp(dto.getFailbackGainMs(), 0, 30000));
        dto.setFailbackGainPercent(clampDouble(dto.getFailbackGainPercent(), 0.0, 100.0));
        dto.setPreheatEnabled(!Boolean.FALSE.equals(dto.getPreheatEnabled()));
        dto.setPreheatBackupCount(clamp(dto.getPreheatBackupCount(), 1, 9));
        dto.setPreheatStrictIsolation(!Boolean.FALSE.equals(dto.getPreheatStrictIsolation()));
        dto.setPostSwitchVerifyEnabled(!Boolean.FALSE.equals(dto.getPostSwitchVerifyEnabled()));
        dto.setPostSwitchRejectSuppressSeconds(clamp(dto.getPostSwitchRejectSuppressSeconds(), 60, 86400));
        dto.setDnsVerifyEnabled(!Boolean.FALSE.equals(dto.getDnsVerifyEnabled()));
        String manualMode = StringUtils.lowerCase(StringUtils.defaultIfBlank(dto.getManualControlMode(), "auto"), Locale.ROOT);
        if (!Set.of("auto", "pause", "lock").contains(manualMode)) throw new IllegalArgumentException("手动控制模式不正确");
        if (dto.getTcpLatencySelectionEnabled()) {
            dto.setAutoFailback(false);
            dto.setQualityEnabled(false);
            dto.setQualityFixedTargetEnabled(false);
            dto.setQualityFlapGuardEnabled(false);
            dto.setQualityPenaltyEnabled(false);
            dto.setSmartSelectionEnabled(false);
            dto.setDegradedFallbackEnabled(false);
            dto.setSameFaultAvoidanceEnabled(false);
            dto.setTopologyAvoidanceEnabled(false);
            dto.setPreheatEnabled(false);
            if ("lock".equals(manualMode)) manualMode = "auto";
        }
        dto.setManualControlMode(manualMode);
        if ("lock".equals(manualMode)) {
            if (dto.getId() == null) throw new IllegalArgumentException("请先创建容灾组后再锁定入口");
            if (dto.getLockedMemberId() == null) throw new IllegalArgumentException("请选择要锁定的入口");
            if (dto.getManualLockUntil() != null && dto.getManualLockUntil() <= System.currentTimeMillis()) {
                throw new IllegalArgumentException("锁定到期时间必须晚于当前时间");
            }
        } else {
            dto.setLockedMemberId(null);
            dto.setManualLockUntil(null);
        }
    }

    private Map<String, Object> loadGroup(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,name,domain,dns_zone_id AS dnsZoneId,zone_id AS zoneId,record_id AS recordId,api_token AS apiToken,"
                + "record_type AS recordType,ttl,probe_interval_ms AS probeIntervalMs,connect_timeout_ms AS connectTimeoutMs,"
                + "failure_threshold AS failureThreshold,recovery_threshold AS recoveryThreshold,cooldown_seconds AS cooldownSeconds,"
                + "auto_failback AS autoFailback,routing_mode AS routingMode,quality_enabled AS qualityEnabled,quality_probe_source_type AS qualityProbeSourceType,"
                + "quality_probe_source_id AS qualityProbeSourceId,quality_probe_count AS qualityProbeCount,quality_degrade_threshold_ms AS qualityDegradeThresholdMs,"
                + "quality_recover_threshold_ms AS qualityRecoverThresholdMs,quality_degrade_factor AS qualityDegradeFactor,quality_recover_factor AS qualityRecoverFactor,"
                + "quality_degrade_samples AS qualityDegradeSamples,quality_recover_samples AS qualityRecoverSamples,"
                + "quality_loss_threshold_percent AS qualityLossThresholdPercent,quality_p95_threshold_ms AS qualityP95ThresholdMs,"
                + "quality_jitter_threshold_ms AS qualityJitterThresholdMs,quality_fixed_target_enabled AS qualityFixedTargetEnabled,"
                + "quality_fixed_target_ms AS qualityFixedTargetMs,quality_fixed_target_strict AS qualityFixedTargetStrict,"
                + "quality_flap_guard_enabled AS qualityFlapGuardEnabled,quality_flap_window_seconds AS qualityFlapWindowSeconds,"
                + "quality_flap_threshold AS qualityFlapThreshold,quality_flap_suppress_seconds AS qualityFlapSuppressSeconds,"
                + "quality_penalty_enabled AS qualityPenaltyEnabled,quality_penalty_reset_seconds AS qualityPenaltyResetSeconds,"
                + "quality_penalty_observe_seconds AS qualityPenaltyObserveSeconds,"
                + "smart_selection_enabled AS smartSelectionEnabled,tcp_latency_selection_enabled AS tcpLatencySelectionEnabled,"
                + "tcp_latency_switch_threshold_ms AS tcpLatencySwitchThresholdMs,"
                + "tcp_primary_preference_tolerance_ms AS tcpPrimaryPreferenceToleranceMs,degraded_fallback_enabled AS degradedFallbackEnabled,"
                + "same_fault_avoidance_enabled AS sameFaultAvoidanceEnabled,topology_avoidance_enabled AS topologyAvoidanceEnabled,"
                + "min_residency_seconds AS minResidencySeconds,failback_gain_ms AS failbackGainMs,"
                + "failback_gain_percent AS failbackGainPercent,preheat_enabled AS preheatEnabled,preheat_backup_count AS preheatBackupCount,"
                + "preheat_strict_isolation AS preheatStrictIsolation,"
                + "post_switch_verify_enabled AS postSwitchVerifyEnabled,post_switch_reject_suppress_seconds AS postSwitchRejectSuppressSeconds,"
                + "dns_verify_enabled AS dnsVerifyEnabled,"
                + "manual_control_mode AS manualControlMode,locked_member_id AS lockedMemberId,manual_lock_until AS manualLockUntil,"
                + "quality_probe_status AS qualityProbeStatus,"
                + "quality_probe_error AS qualityProbeError,quality_probe_at AS qualityProbeAt,enabled,state,active_member_id AS activeMemberId,last_error AS lastError,"
                + "last_checked_at AS lastCheckedAt,last_switch_at AS lastSwitchAt,"
                + "CASE WHEN EXISTS (SELECT 1 FROM cross_entry_managed_resource mr WHERE mr.group_id=cross_entry_failover_group.id) "
                + "THEN 'managed_forward' ELSE 'existing_forward' END AS creationMode,"
                + "(SELECT mr.target_address FROM cross_entry_managed_resource mr WHERE mr.group_id=cross_entry_failover_group.id LIMIT 1) AS managedTargetAddress,"
                + "(SELECT mr.public_port FROM cross_entry_managed_resource mr WHERE mr.group_id=cross_entry_failover_group.id LIMIT 1) AS managedPublicPort,"
                + "(SELECT mr.port_mode FROM cross_entry_managed_resource mr WHERE mr.group_id=cross_entry_failover_group.id LIMIT 1) AS managedPortMode,"
                + "(SELECT mr.protocol_mode FROM cross_entry_managed_resource mr WHERE mr.group_id=cross_entry_failover_group.id LIMIT 1) AS managedProtocolMode "
                + "FROM cross_entry_failover_group WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("容灾组不存在");
        return rows.get(0);
    }

    private List<Map<String, Object>> loadMembers(long groupId) {
        return jdbcTemplate.queryForList("SELECT id,group_id AS groupId,forward_id AS forwardId,priority,weight,enabled,entry_node_id AS entryNodeId,"
                + "entry_host AS entryHost,entry_address AS entryAddress,topology_signature AS topologySignature,entry_port AS entryPort,forward_name AS forwardName,node_name AS nodeName,"
                + "status,fail_count AS failCount,success_count AS successCount,latency_ms AS latencyMs,quality_latency_ms AS qualityLatencyMs,"
                + "quality_p95_ms AS qualityP95Ms,quality_jitter_ms AS qualityJitterMs,"
                + "quality_loss_percent AS qualityLossPercent,quality_baseline_ms AS qualityBaselineMs,quality_preheated AS qualityPreheated,quality_state AS qualityState,"
                + "quality_bad_count AS qualityBadCount,quality_good_count AS qualityGoodCount,quality_flap_count AS qualityFlapCount,"
                + "quality_flap_window_started_at AS qualityFlapWindowStartedAt,quality_suppressed_until AS qualitySuppressedUntil,"
                + "quality_suppressed_reason AS qualitySuppressedReason,quality_penalty_level AS qualityPenaltyLevel,"
                + "quality_penalty_episode_count AS qualityPenaltyEpisodeCount,quality_penalty_window_started_at AS qualityPenaltyWindowStartedAt,"
                + "quality_penalty_last_at AS qualityPenaltyLastAt,quality_recovery_observe_until AS qualityRecoveryObserveUntil,"
                + "switch_rejected_until AS switchRejectedUntil,switch_rejected_reason AS switchRejectedReason,switch_reject_count AS switchRejectCount,"
                + "quality_last_error AS qualityLastError,"
                + "quality_checked_at AS qualityCheckedAt,fault_episode_count AS faultEpisodeCount,connect_fault_count AS connectFaultCount,"
                + "latency_fault_count AS latencyFaultCount,loss_fault_count AS lossFaultCount,p95_fault_count AS p95FaultCount,"
                + "jitter_fault_count AS jitterFaultCount,flap_fault_count AS flapFaultCount,switch_trigger_count AS switchTriggerCount,"
                + "last_fault_type AS lastFaultType,last_fault_reason AS lastFaultReason,last_fault_at AS lastFaultAt,"
                + "last_error AS lastError,"
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

    private Set<String> topologyKeys(Map<String, Object> member) {
        return CrossEntryTopology.keysFromSignatureOrAddress(
                Objects.toString(member.get("topologySignature"), ""),
                Objects.toString(member.get("entryAddress"), ""));
    }

    private boolean cooldownElapsed(Map<String, Object> group, long now) {
        Long lastSwitch = nullableLong(group.get("lastSwitchAt"));
        return lastSwitch == null || now - lastSwitch >= number(group.get("cooldownSeconds")).longValue() * 1000L;
    }

    private void expireManualLockIfNeeded(Map<String, Object> group, Map<String, Object> active, long now) {
        if (!"lock".equals(Objects.toString(group.get("manualControlMode"), "auto"))) return;
        Long lockUntil = nullableLong(group.get("manualLockUntil"));
        if (lockUntil == null || lockUntil > now) return;
        long groupId = number(group.get("id")).longValue();
        jdbcTemplate.update("UPDATE cross_entry_failover_group SET manual_control_mode='auto',locked_member_id=NULL,manual_lock_until=NULL,updated_time=? WHERE id=?",
                now, groupId);
        group.put("manualControlMode", "auto");
        group.put("lockedMemberId", null);
        group.put("manualLockUntil", null);
        Long activeId = active == null ? null : nullableLong(active.get("id"));
        addEvent(groupId, activeId, activeId, "手动锁定到期，恢复自动选择", "success", "自动规则重新接管入口选择");
    }

    private boolean minResidencyElapsed(Map<String, Object> group, long now) {
        Long lastSwitch = nullableLong(group.get("lastSwitchAt"));
        return lastSwitch == null || now - lastSwitch >= number(group.get("minResidencySeconds")).longValue() * 1000L;
    }

    private CrossEntryFailoverPolicy.Member policyMember(Map<String, Object> group, Map<String, Object> member,
                                                        boolean useQualityDecision, boolean useDetailedLatency,
                                                        long now) {
        Integer qualityLatency = nullableInt(member.get("qualityLatencyMs"));
        Integer regularLatency = nullableInt(member.get("latencyMs"));
        return new CrossEntryFailoverPolicy.Member(
                number(member.get("id")).longValue(),
                number(member.get("priority")).intValue(),
                "healthy".equals(member.get("status")),
                number(member.get("successCount")).intValue(),
                useQualityDecision && isQualityDegraded(member),
                !useQualityDecision || acceptableForQualitySwitch(group, member),
                useQualityDecision && isQualitySuppressed(group, member, now),
                useDetailedLatency && qualityLatency != null
                        ? qualityLatency
                        : (bool(group.get("tcpLatencySelectionEnabled")) ? null : regularLatency),
                useDetailedLatency ? nullableDouble(member.get("qualityLossPercent")) : null,
                number(member.get("qualityFlapCount")).intValue(),
                number(member.get("failCount")).intValue(),
                number(member.get("entryNodeId")).longValue(),
                Objects.toString(member.get("entryAddress"), ""),
                Objects.toString(member.get("topologySignature"), ""),
                faultKind(group, member, useQualityDecision, now),
                !useQualityDecision || !bool(group.get("preheatEnabled")) || bool(member.get("qualityPreheated")),
                number(member.get("qualityPenaltyLevel")).intValue());
    }

    private String faultKind(Map<String, Object> group, Map<String, Object> member, boolean useQualityDecision, long now) {
        if (member == null) return "none";
        if ("unhealthy".equals(member.get("status"))) return "connect";
        if (!useQualityDecision) return "none";
        Double loss = nullableDouble(member.get("qualityLossPercent"));
        if (loss != null && loss >= doubleNumber(group.get("qualityLossThresholdPercent"))) return "loss";
        if (isQualitySuppressed(group, member, now)) return "flap";
        if (isQualityDegraded(member)) {
            return nullableInt(member.get("qualityLatencyMs")) == null ? "quality" : "latency";
        }
        return "none";
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

    private Double nullableDouble(Object value) {
        return value == null ? null : doubleNumber(value);
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

    private boolean isQualitySuppressed(Map<String, Object> member, long now) {
        return isQualitySuppressed(null, member, now);
    }

    private boolean isQualitySuppressed(Map<String, Object> group, Map<String, Object> member, long now) {
        if (member == null) return false;
        Long switchRejectedUntil = nullableLong(member.get("switchRejectedUntil"));
        if (switchRejectedUntil != null && switchRejectedUntil > now) return true;
        Long suppressedUntil = nullableLong(member.get("qualitySuppressedUntil"));
        if (suppressedUntil != null && suppressedUntil > now) return true;
        Long observeUntil = nullableLong(member.get("qualityRecoveryObserveUntil"));
        int recoverSamples = group == null ? 3 : Math.max(1, number(group.get("qualityRecoverSamples")).intValue());
        return observeUntil != null && observeUntil > now
                && (!"healthy".equals(Objects.toString(member.get("qualityState"), "unknown"))
                || number(member.get("qualityGoodCount")).intValue() < recoverSamples);
    }

    private boolean acceptableForQualitySwitch(Map<String, Object> group, Map<String, Object> member) {
        if (member == null || !bool(group.get("qualityFixedTargetEnabled")) || !bool(group.get("qualityFixedTargetStrict"))) {
            return true;
        }
        Integer latency = nullableInt(member.get("qualityLatencyMs"));
        return latency != null && latency <= number(group.get("qualityFixedTargetMs")).intValue();
    }

    private JSONObject responseData(Object data) {
        if (data instanceof JSONObject) return (JSONObject) data;
        if (data instanceof Map<?, ?> map) return new JSONObject((Map<String, Object>) map);
        if (data instanceof String text && StringUtils.isNotBlank(text)) return JSON.parseObject(text);
        return null;
    }

    private List<Integer> numericSamples(JSONArray values) {
        if (values == null || values.isEmpty()) return List.of();
        List<Integer> samples = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Object raw = values.get(i);
            Double value = null;
            if (raw instanceof Number number) value = number.doubleValue();
            else if (raw != null) {
                try {
                    value = Double.parseDouble(raw.toString());
                } catch (NumberFormatException ignored) {
                }
            }
            if (value != null && value >= 0) samples.add(Math.max(1, (int) Math.round(value)));
        }
        return samples;
    }

    private Integer average(List<Integer> samples) {
        if (samples == null || samples.isEmpty()) return null;
        long total = 0;
        for (Integer sample : samples) total += sample;
        return Math.max(1, (int) Math.round(total * 1.0 / samples.size()));
    }

    private Integer percentile(List<Integer> samples, double percentile) {
        if (samples == null || samples.isEmpty()) return null;
        List<Integer> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    static Integer roundedMetric(JSONObject data, String key, int min) {
        if (data == null || !data.containsKey(key)) return null;
        Object raw = data.get(key);
        if (raw == null) return null;
        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else {
            try {
                value = Double.parseDouble(raw.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) return null;
        return Math.max(min, (int) Math.round(value));
    }

    private Integer jitter(List<Integer> samples) {
        if (samples == null || samples.size() < 2) return 0;
        long total = 0;
        for (int i = 1; i < samples.size(); i++) total += Math.abs(samples.get(i) - samples.get(i - 1));
        return Math.max(0, (int) Math.round(total * 1.0 / (samples.size() - 1)));
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
                                      Integer p95Ms, Integer jitterMs, Double lossPercent, String error) {}
    private record DirectTunnelResult(Tunnel tunnel, boolean created) {}
    record FaultStatsUpdate(int episodeDelta, int connectDelta, int latencyDelta, int lossDelta, int p95Delta,
                            int jitterDelta, int flapDelta, String type, String reason, Long at) {}
    private record DnsVerification(boolean providerMatched, boolean publicMatched, String message) {}
    private record PublicDnsVerification(boolean matched, String detail) {}
}
