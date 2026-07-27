package com.admin.service;

import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class MonitoringService {

    private static final int ADMIN_ROLE_ID = 0;
    private static final String HEALTHY = "healthy";
    private static final String DEGRADED = "degraded";
    private static final String OFFLINE = "offline";
    private static final String PAUSED = "paused";
    private static final String UNKNOWN = "unknown";
    private static final String ALERT_OPEN = "open";
    private static final String ALERT_RESOLVED = "resolved";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final TelegramNotificationService telegramNotificationService;
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    @Value("${monitoring.retention-days:90}")
    private int retentionDays;

    public MonitoringService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                             TelegramNotificationService telegramNotificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.telegramNotificationService = telegramNotificationService;
    }

    @Scheduled(
            initialDelayString = "${monitoring.initial-delay-ms:15000}",
            fixedDelayString = "${monitoring.scan-interval-ms:30000}"
    )
    public void scheduledScan() {
        scanSafely();
    }

    @Scheduled(cron = "${monitoring.cleanup-cron:0 20 3 * * ?}")
    public void cleanupHistory() {
        long cutoff = System.currentTimeMillis() - Math.max(retentionDays, 30) * 86_400_000L;
        jdbcTemplate.update("DELETE FROM monitoring_alert_read WHERE alert_id IN "
                + "(SELECT id FROM monitoring_alert WHERE status = ? AND resolved_at < ?)", ALERT_RESOLVED, cutoff);
        jdbcTemplate.update("DELETE FROM monitoring_alert WHERE status = ? AND resolved_at < ?", ALERT_RESOLVED, cutoff);
        jdbcTemplate.update("DELETE FROM monitoring_history WHERE ended_at IS NOT NULL AND ended_at < ?", cutoff);
    }

    public void scanSafely() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> scanResourceStates());
        } catch (Exception e) {
            log.warn("Resource monitoring scan failed: {}", e.getMessage());
        } finally {
            scanning.set(false);
        }
    }

    private void scanResourceStates() {
        long now = System.currentTimeMillis();
        Map<Long, ResourceState> nodeStates = evaluateNodes();
        Map<Long, ResourceState> tunnelStates = evaluateTunnels(nodeStates);
        Map<Long, ResourceState> forwardStates = evaluateForwards(tunnelStates);
        Map<Long, ResourceState> certificateStates = evaluateCertificates();

        List<ResourceState> states = new ArrayList<>();
        states.addAll(nodeStates.values());
        states.addAll(tunnelStates.values());
        states.addAll(forwardStates.values());
        states.addAll(certificateStates.values());

        Set<ResourceKey> seen = new HashSet<>();
        for (ResourceState state : states) {
            seen.add(new ResourceKey(state.type(), state.id()));
            persistState(state, now);
        }
        closeRemovedResources(seen, now);
    }

    public Map<String, Object> getOverview(Integer userId, Integer roleId, String requestedRange) {
        ensureMonitoringData();
        RangeWindow range = RangeWindow.from(requestedRange);
        long now = System.currentTimeMillis();
        long start = now - range.durationMs();
        Visibility visibility = visibility(userId, roleId, "c");

        List<Map<String, Object>> currentRows = jdbcTemplate.queryForList(
                "SELECT c.* FROM monitoring_current c WHERE " + visibility.sql() + " ORDER BY c.resource_type, c.resource_name",
                visibility.args().toArray()
        );
        Set<ResourceKey> visibleKeys = toResourceKeys(currentRows);
        List<Map<String, Object>> historyRows = loadVisibleHistory(userId, roleId, start, now);
        Map<ResourceKey, List<HistoryInterval>> histories = groupHistory(historyRows, now);
        Map<Integer, String> userNames = loadUserNames();
        Map<ResourceKey, Integer> incidentCounts = loadIncidentCounts(userId, roleId, start);

        int healthyCount = 0;
        int degradedCount = 0;
        int offlineCount = 0;
        int pausedCount = 0;
        int unknownCount = 0;
        long totalHealthyMs = 0;
        long totalCoveredMs = 0;
        long trackedFrom = now;
        List<Map<String, Object>> resources = new ArrayList<>();

        for (Map<String, Object> row : currentRows) {
            String type = stringValue(row.get("resource_type"));
            long resourceId = longValue(row.get("resource_id"));
            ResourceKey key = new ResourceKey(type, resourceId);
            String status = stringValue(row.get("status"));
            switch (status) {
                case HEALTHY -> healthyCount++;
                case DEGRADED -> degradedCount++;
                case OFFLINE -> offlineCount++;
                case PAUSED -> pausedCount++;
                default -> unknownCount++;
            }

            Availability availability = calculateAvailability(histories.getOrDefault(key, List.of()), start, now);
            totalHealthyMs += availability.healthyMs();
            totalCoveredMs += availability.coveredMs();
            if (availability.trackedFrom() > 0) {
                trackedFrom = Math.min(trackedFrom, availability.trackedFrom());
            }

            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", type);
            resource.put("id", resourceId);
            resource.put("name", row.get("resource_name"));
            resource.put("ownerUserId", row.get("owner_user_id"));
            resource.put("ownerUserName", userNames.getOrDefault(intValue(row.get("owner_user_id")), "未知用户"));
            resource.put("status", status);
            resource.put("detail", row.get("detail"));
            resource.put("changedAt", row.get("changed_at"));
            resource.put("checkedAt", row.get("checked_at"));
            resource.put("availability", availability.percentage());
            resource.put("trackedMs", availability.coveredMs());
            resource.put("incidentCount", incidentCounts.getOrDefault(key, 0));
            resources.add(resource);
        }

        resources.sort(Comparator
                .comparingInt((Map<String, Object> item) -> statusRank(stringValue(item.get("status"))))
                .thenComparing(item -> stringValue(item.get("type")))
                .thenComparing(item -> stringValue(item.get("name"))));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalResources", currentRows.size());
        summary.put("healthy", healthyCount);
        summary.put("degraded", degradedCount);
        summary.put("offline", offlineCount);
        summary.put("paused", pausedCount);
        summary.put("unknown", unknownCount);
        summary.put("openAlerts", countAlerts(userId, roleId, ALERT_OPEN, null));
        summary.put("criticalAlerts", countAlerts(userId, roleId, ALERT_OPEN, "critical"));
        summary.put("unreadAlerts", countUnreadAlerts(userId, roleId));
        summary.put("availability", percentage(totalHealthyMs, totalCoveredMs));
        summary.put("trackedFrom", trackedFrom == now ? 0 : trackedFrom);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", range.key());
        result.put("summary", summary);
        result.put("trend", buildTrend(visibleKeys, histories, start, now, range.bucketCount()));
        result.put("resources", resources);
        return result;
    }

    public Map<String, Object> listAlerts(
            Integer userId,
            Integer roleId,
            String status,
            String resourceType,
            String severity,
            String keyword,
            int page,
            int size
    ) {
        ensureMonitoringData();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(size, 10), 100);
        List<Object> args = new ArrayList<>();
        Visibility visibility = visibility(userId, roleId, "a");
        StringBuilder where = new StringBuilder(" WHERE ").append(visibility.sql());
        args.addAll(visibility.args());

        if (ALERT_OPEN.equals(status) || ALERT_RESOLVED.equals(status)) {
            where.append(" AND a.status = ?");
            args.add(status);
        }
        if (Set.of("node", "tunnel", "forward").contains(resourceType)) {
            where.append(" AND a.resource_type = ?");
            args.add(resourceType);
        }
        if (Set.of("critical", "warning").contains(severity)) {
            where.append(" AND a.severity = ?");
            args.add(severity);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (a.resource_name LIKE ? OR a.title LIKE ? OR a.detail LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert a" + where,
                Long.class,
                args.toArray()
        );

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT a.*, CASE WHEN r.alert_id IS NULL THEN 0 ELSE 1 END AS is_read "
                        + "FROM monitoring_alert a LEFT JOIN monitoring_alert_read r "
                        + "ON r.alert_id = a.id AND r.user_id = ?" + where
                        + " ORDER BY CASE WHEN a.status = 'open' THEN 0 ELSE 1 END, a.updated_at DESC LIMIT ? OFFSET ?",
                prepend(userId, pageArgs).toArray()
        );

        Map<Integer, String> userNames = loadUserNames();
        List<Map<String, Object>> items = rows.stream().map(row -> alertResponse(row, userNames)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("unread", countUnreadAlerts(userId, roleId));
        return result;
    }

    public int markAlertsRead(Integer userId, Integer roleId, List<Long> alertIds) {
        if (alertIds == null || alertIds.isEmpty()) {
            return 0;
        }
        List<Long> uniqueIds = alertIds.stream().filter(Objects::nonNull).distinct().limit(200).toList();
        if (uniqueIds.isEmpty()) {
            return 0;
        }
        Visibility visibility = visibility(userId, roleId, "a");
        String placeholders = String.join(",", java.util.Collections.nCopies(uniqueIds.size(), "?"));
        List<Object> args = new ArrayList<>(visibility.args());
        args.addAll(uniqueIds);
        List<Long> allowedIds = jdbcTemplate.queryForList(
                "SELECT a.id FROM monitoring_alert a WHERE " + visibility.sql() + " AND a.id IN (" + placeholders + ")",
                Long.class,
                args.toArray()
        );
        long now = System.currentTimeMillis();
        for (Long id : allowedIds) {
            jdbcTemplate.update("INSERT INTO monitoring_alert_read (alert_id, user_id, read_at) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE read_at = VALUES(read_at)", id, userId, now);
        }
        return allowedIds.size();
    }

    public int markAllAlertsRead(Integer userId, Integer roleId) {
        Visibility visibility = visibility(userId, roleId, "a");
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT a.id FROM monitoring_alert a LEFT JOIN monitoring_alert_read r "
                        + "ON r.alert_id = a.id AND r.user_id = ? WHERE r.alert_id IS NULL AND " + visibility.sql(),
                Long.class,
                prepend(userId, visibility.args()).toArray()
        );
        return markAlertsRead(userId, roleId, ids);
    }

    public long getUnreadCount(Integer userId, Integer roleId) {
        ensureMonitoringData();
        return countUnreadAlerts(userId, roleId);
    }

    private Map<Long, ResourceState> evaluateNodes() {
        Map<Long, ResourceState> states = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, owner_user_id FROM node ORDER BY id"
        );
        for (Map<String, Object> row : rows) {
            long id = longValue(row.get("id"));
            boolean online = WebSocketServer.isNodeOnline(id);
            states.put(id, new ResourceState(
                    "node",
                    id,
                    stringValue(row.get("name")),
                    intValue(row.get("owner_user_id")),
                    online ? HEALTHY : OFFLINE,
                    online ? "Agent 已连接" : "Agent 连接已断开"
            ));
        }
        return states;
    }

    private Map<Long, ResourceState> evaluateTunnels(Map<Long, ResourceState> nodeStates) {
        Map<Long, ResourceState> states = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, owner_user_id, in_node_id, out_node_id, node_path, type FROM tunnel ORDER BY id"
        );
        for (Map<String, Object> row : rows) {
            long id = longValue(row.get("id"));
            List<Long> path = parseNodePath(
                    stringValue(row.get("node_path")),
                    longValue(row.get("in_node_id")),
                    longValue(row.get("out_node_id")),
                    intValue(row.get("type"))
            );
            List<String> offlineNames = path.stream()
                    .map(nodeStates::get)
                    .filter(node -> node == null || !HEALTHY.equals(node.status()))
                    .map(node -> node == null ? "未知节点" : node.name())
                    .toList();
            boolean healthy = !path.isEmpty() && offlineNames.isEmpty();
            String detail = healthy
                    ? path.size() + " 级路径正常"
                    : (offlineNames.isEmpty() ? "路径中没有可用节点" : "离线节点：" + String.join("、", offlineNames));
            states.put(id, new ResourceState(
                    "tunnel",
                    id,
                    stringValue(row.get("name")),
                    intValue(row.get("owner_user_id")),
                    healthy ? HEALTHY : OFFLINE,
                    detail
            ));
        }
        return states;
    }

    private Map<Long, ResourceState> evaluateForwards(Map<Long, ResourceState> tunnelStates) {
        Map<Long, ResourceState> states = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, user_id, status, tunnel_id, active_tunnel_id, route_config, target_health FROM forward ORDER BY id"
        );
        for (Map<String, Object> row : rows) {
            long id = longValue(row.get("id"));
            if (intValue(row.get("status")) == 0) {
                states.put(id, new ResourceState(
                        "forward", id, stringValue(row.get("name")), intValue(row.get("user_id")), PAUSED, "转发已人工暂停"
                ));
                continue;
            }

            long primaryTunnelId = longValue(row.get("tunnel_id"));
            long activeTunnelId = nullableLong(row.get("active_tunnel_id"), primaryTunnelId);
            Map<Long, String> configuredRouteStatus = parseRouteStatuses(stringValue(row.get("route_config")));
            Set<Long> routeIds = new LinkedHashSet<>();
            routeIds.add(primaryTunnelId);
            routeIds.addAll(configuredRouteStatus.keySet());

            int healthyRoutes = 0;
            int unhealthyRoutes = 0;
            boolean activeHealthy = false;
            for (Long tunnelId : routeIds) {
                ResourceState tunnel = tunnelStates.get(tunnelId);
                boolean routeHealthy = tunnel != null
                        && HEALTHY.equals(tunnel.status())
                        && !OFFLINE.equals(configuredRouteStatus.get(tunnelId))
                        && !"unhealthy".equals(configuredRouteStatus.get(tunnelId));
                if (routeHealthy) {
                    healthyRoutes++;
                } else {
                    unhealthyRoutes++;
                }
                if (tunnelId == activeTunnelId) {
                    activeHealthy = routeHealthy;
                }
            }

            TargetHealth targets = parseTargetHealth(stringValue(row.get("target_health")));
            String status;
            String detail;
            if (healthyRoutes == 0) {
                status = OFFLINE;
                detail = "所有候选线路均不可用";
            } else if (!activeHealthy) {
                status = DEGRADED;
                detail = "当前线路异常，仍有 " + healthyRoutes + " 条备用线路可用";
            } else if (targets.total() > 0 && targets.healthy() == 0) {
                status = OFFLINE;
                detail = "所有目标地址均不可用";
            } else if (unhealthyRoutes > 0 || targets.unhealthy() > 0) {
                status = DEGRADED;
                List<String> reasons = new ArrayList<>();
                if (unhealthyRoutes > 0) {
                    reasons.add(unhealthyRoutes + " 条备用线路异常");
                }
                if (targets.unhealthy() > 0) {
                    reasons.add(targets.unhealthy() + " 个目标异常");
                }
                detail = String.join("，", reasons);
            } else {
                status = HEALTHY;
                detail = routeIds.size() > 1 ? healthyRoutes + " 条线路正常" : "线路和目标正常";
            }

            states.put(id, new ResourceState(
                    "forward", id, stringValue(row.get("name")), intValue(row.get("user_id")), status, detail
            ));
        }
        return states;
    }

    private Map<Long, ResourceState> evaluateCertificates() {
        Map<Long, ResourceState> states = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.id,c.domain,c.state,c.expires_at,c.last_error,COALESCE(MIN(r.user_id),1) AS owner_user_id "
                        + "FROM managed_certificate c LEFT JOIN domain_route r ON r.certificate_id=c.id AND r.state<>'deleted' "
                        + "GROUP BY c.id,c.domain,c.state,c.expires_at,c.last_error ORDER BY c.id");
        long now = System.currentTimeMillis();
        for (Map<String, Object> row : rows) {
            long id = longValue(row.get("id"));
            String certificateState = stringValue(row.get("state"));
            Long expiresAt = row.get("expires_at") == null ? null : longValue(row.get("expires_at"));
            String status;
            String detail;
            if (List.of("failed", "deployment_failed").contains(certificateState)) {
                status = OFFLINE;
                detail = stringValue(row.get("last_error"));
            } else if (expiresAt != null && expiresAt <= now) {
                status = OFFLINE;
                detail = "HTTPS 证书已经过期";
            } else if ("renewal_failed".equals(certificateState)) {
                status = DEGRADED;
                detail = "HTTPS 证书续签失败，当前证书仍继续使用";
            } else if (expiresAt != null && expiresAt - now <= 14L * 86_400_000L) {
                status = DEGRADED;
                detail = "HTTPS 证书将在 " + Math.max(0, (expiresAt - now) / 86_400_000L) + " 天内到期";
            } else if ("active".equals(certificateState)) {
                status = HEALTHY;
                detail = "HTTPS 证书有效，自动续期已启用";
            } else {
                status = UNKNOWN;
                detail = "HTTPS 证书正在申请或部署";
            }
            states.put(id, new ResourceState("certificate", id, stringValue(row.get("domain")),
                    intValue(row.get("owner_user_id")), status, detail));
        }
        return states;
    }

    private void persistState(ResourceState state, long now) {
        List<Map<String, Object>> existingRows = jdbcTemplate.queryForList(
                "SELECT status, changed_at FROM monitoring_current WHERE resource_type = ? AND resource_id = ?",
                state.type(), state.id()
        );
        if (existingRows.isEmpty()) {
            jdbcTemplate.update("INSERT INTO monitoring_current "
                            + "(resource_type, resource_id, resource_name, owner_user_id, status, detail, changed_at, checked_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    state.type(), state.id(), state.name(), state.ownerUserId(), state.status(), state.detail(), now, now);
            insertHistory(state, now);
            synchronizeAlert(state, now);
            return;
        }

        String previousStatus = stringValue(existingRows.get(0).get("status"));
        if (!Objects.equals(previousStatus, state.status())) {
            jdbcTemplate.update("UPDATE monitoring_history SET ended_at = ? "
                            + "WHERE resource_type = ? AND resource_id = ? AND ended_at IS NULL",
                    now, state.type(), state.id());
            insertHistory(state, now);
            jdbcTemplate.update("UPDATE monitoring_current SET resource_name = ?, owner_user_id = ?, status = ?, "
                            + "detail = ?, changed_at = ?, checked_at = ? WHERE resource_type = ? AND resource_id = ?",
                    state.name(), state.ownerUserId(), state.status(), state.detail(), now, now, state.type(), state.id());
        } else {
            jdbcTemplate.update("UPDATE monitoring_current SET resource_name = ?, owner_user_id = ?, detail = ?, checked_at = ? "
                            + "WHERE resource_type = ? AND resource_id = ?",
                    state.name(), state.ownerUserId(), state.detail(), now, state.type(), state.id());
            jdbcTemplate.update("UPDATE monitoring_history SET resource_name = ?, owner_user_id = ?, detail = ? "
                            + "WHERE resource_type = ? AND resource_id = ? AND ended_at IS NULL",
                    state.name(), state.ownerUserId(), state.detail(), state.type(), state.id());
        }
        synchronizeAlert(state, now);
    }

    private void insertHistory(ResourceState state, long now) {
        jdbcTemplate.update("INSERT INTO monitoring_history "
                        + "(resource_type, resource_id, resource_name, owner_user_id, status, detail, started_at, ended_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                state.type(), state.id(), state.name(), state.ownerUserId(), state.status(), state.detail(), now);
    }

    private void synchronizeAlert(ResourceState state, long now) {
        List<Map<String, Object>> openAlerts = jdbcTemplate.queryForList(
                "SELECT started_at FROM monitoring_alert WHERE resource_type = ? AND resource_id = ? AND status = ? "
                        + "ORDER BY started_at DESC LIMIT 1",
                state.type(), state.id(), ALERT_OPEN);
        boolean alertWorthy = OFFLINE.equals(state.status()) || DEGRADED.equals(state.status());
        if (!alertWorthy) {
            jdbcTemplate.update("UPDATE monitoring_alert SET status = ?, resolved_at = ?, updated_at = ? "
                            + "WHERE resource_type = ? AND resource_id = ? AND status = ?",
                    ALERT_RESOLVED, now, now, state.type(), state.id(), ALERT_OPEN);
            if (!openAlerts.isEmpty()) {
                telegramNotificationService.notifyResourceRecovery(
                        state.type(), state.id(), state.name(), longValue(openAlerts.get(0).get("started_at")));
            }
            return;
        }

        String severity = OFFLINE.equals(state.status()) ? "critical" : "warning";
        String title = resourceTypeLabel(state.type()) + "“" + state.name() + "”"
                + (OFFLINE.equals(state.status()) ? "异常" : "性能下降");
        int updated = jdbcTemplate.update("UPDATE monitoring_alert SET resource_name = ?, owner_user_id = ?, severity = ?, "
                        + "title = ?, detail = ?, updated_at = ? WHERE resource_type = ? AND resource_id = ? AND status = ?",
                state.name(), state.ownerUserId(), severity, title, state.detail(), now,
                state.type(), state.id(), ALERT_OPEN);
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO monitoring_alert "
                            + "(resource_type, resource_id, resource_name, owner_user_id, severity, status, title, detail, "
                            + "started_at, resolved_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)",
                    state.type(), state.id(), state.name(), state.ownerUserId(), severity, ALERT_OPEN,
                    title, state.detail(), now, now);
        }
        long incidentStartedAt = openAlerts.isEmpty() ? now : longValue(openAlerts.get(0).get("started_at"));
        telegramNotificationService.notifyResourceIncident(
                state.type(), state.id(), state.name(), state.status(), state.detail(), incidentStartedAt);
    }

    private void closeRemovedResources(Set<ResourceKey> seen, long now) {
        List<Map<String, Object>> current = jdbcTemplate.queryForList("SELECT resource_type, resource_id FROM monitoring_current");
        for (Map<String, Object> row : current) {
            ResourceKey key = new ResourceKey(stringValue(row.get("resource_type")), longValue(row.get("resource_id")));
            if (seen.contains(key)) {
                continue;
            }
            jdbcTemplate.update("UPDATE monitoring_history SET ended_at = ? WHERE resource_type = ? AND resource_id = ? AND ended_at IS NULL",
                    now, key.type(), key.id());
            jdbcTemplate.update("UPDATE monitoring_alert SET status = ?, resolved_at = ?, updated_at = ?, detail = ? "
                            + "WHERE resource_type = ? AND resource_id = ? AND status = ?",
                    ALERT_RESOLVED, now, now, "资源已删除", key.type(), key.id(), ALERT_OPEN);
            jdbcTemplate.update("DELETE FROM monitoring_current WHERE resource_type = ? AND resource_id = ?", key.type(), key.id());
        }
    }

    private List<Map<String, Object>> loadVisibleHistory(Integer userId, Integer roleId, long start, long now) {
        Visibility visibility = visibility(userId, roleId, "h");
        List<Object> args = new ArrayList<>(visibility.args());
        args.add(now);
        args.add(start);
        return jdbcTemplate.queryForList(
                "SELECT h.* FROM monitoring_history h WHERE " + visibility.sql()
                        + " AND h.started_at < ? AND (h.ended_at IS NULL OR h.ended_at > ?) ORDER BY h.started_at",
                args.toArray()
        );
    }

    private Map<ResourceKey, List<HistoryInterval>> groupHistory(List<Map<String, Object>> rows, long now) {
        Map<ResourceKey, List<HistoryInterval>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            ResourceKey key = new ResourceKey(stringValue(row.get("resource_type")), longValue(row.get("resource_id")));
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new HistoryInterval(
                    stringValue(row.get("status")),
                    longValue(row.get("started_at")),
                    row.get("ended_at") == null ? now : longValue(row.get("ended_at"))
            ));
        }
        return result;
    }

    private Availability calculateAvailability(List<HistoryInterval> intervals, long start, long end) {
        long healthyMs = 0;
        long coveredMs = 0;
        long trackedFrom = 0;
        for (HistoryInterval interval : intervals) {
            long intervalStart = Math.max(start, interval.startedAt());
            long intervalEnd = Math.min(end, interval.endedAt());
            if (intervalEnd <= intervalStart) {
                continue;
            }
            if (PAUSED.equals(interval.status()) || UNKNOWN.equals(interval.status())) {
                continue;
            }
            long duration = intervalEnd - intervalStart;
            coveredMs += duration;
            if (HEALTHY.equals(interval.status())) {
                healthyMs += duration;
            }
            trackedFrom = trackedFrom == 0 ? intervalStart : Math.min(trackedFrom, intervalStart);
        }
        return new Availability(healthyMs, coveredMs, trackedFrom, percentage(healthyMs, coveredMs));
    }

    private List<Map<String, Object>> buildTrend(
            Set<ResourceKey> visibleKeys,
            Map<ResourceKey, List<HistoryInterval>> histories,
            long start,
            long now,
            int bucketCount
    ) {
        List<Map<String, Object>> points = new ArrayList<>();
        long bucketSize = Math.max(1, (now - start) / bucketCount);
        for (int index = 0; index < bucketCount; index++) {
            long bucketStart = start + index * bucketSize;
            long bucketEnd = index == bucketCount - 1 ? now : bucketStart + bucketSize;
            long healthyMs = 0;
            long coveredMs = 0;
            int incidents = 0;
            for (ResourceKey key : visibleKeys) {
                List<HistoryInterval> intervals = histories.getOrDefault(key, List.of());
                Availability availability = calculateAvailability(intervals, bucketStart, bucketEnd);
                healthyMs += availability.healthyMs();
                coveredMs += availability.coveredMs();
                incidents += (int) intervals.stream()
                        .filter(interval -> !HEALTHY.equals(interval.status())
                                && !PAUSED.equals(interval.status())
                                && !UNKNOWN.equals(interval.status())
                                && interval.startedAt() >= bucketStart
                                && interval.startedAt() < bucketEnd)
                        .count();
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", bucketStart);
            point.put("availability", coveredMs > 0 ? percentage(healthyMs, coveredMs) : null);
            point.put("incidents", incidents);
            points.add(point);
        }
        return points;
    }

    private Map<ResourceKey, Integer> loadIncidentCounts(Integer userId, Integer roleId, long start) {
        Visibility visibility = visibility(userId, roleId, "a");
        List<Object> args = new ArrayList<>(visibility.args());
        args.add(start);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT a.resource_type, a.resource_id, COUNT(*) AS total FROM monitoring_alert a WHERE "
                        + visibility.sql() + " AND a.started_at >= ? GROUP BY a.resource_type, a.resource_id",
                args.toArray()
        );
        Map<ResourceKey, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(new ResourceKey(stringValue(row.get("resource_type")), longValue(row.get("resource_id"))),
                    intValue(row.get("total")));
        }
        return result;
    }

    private long countAlerts(Integer userId, Integer roleId, String status, String severity) {
        Visibility visibility = visibility(userId, roleId, "a");
        List<Object> args = new ArrayList<>(visibility.args());
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM monitoring_alert a WHERE ")
                .append(visibility.sql()).append(" AND a.status = ?");
        args.add(status);
        if (severity != null) {
            sql.append(" AND a.severity = ?");
            args.add(severity);
        }
        return jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
    }

    private long countUnreadAlerts(Integer userId, Integer roleId) {
        Visibility visibility = visibility(userId, roleId, "a");
        List<Object> args = prepend(userId, visibility.args());
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert a LEFT JOIN monitoring_alert_read r "
                        + "ON r.alert_id = a.id AND r.user_id = ? WHERE r.alert_id IS NULL AND " + visibility.sql(),
                Long.class,
                args.toArray()
        );
    }

    private Map<String, Object> alertResponse(Map<String, Object> row, Map<Integer, String> userNames) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("resourceType", row.get("resource_type"));
        result.put("resourceId", row.get("resource_id"));
        result.put("resourceName", row.get("resource_name"));
        result.put("ownerUserId", row.get("owner_user_id"));
        result.put("ownerUserName", userNames.getOrDefault(intValue(row.get("owner_user_id")), "未知用户"));
        result.put("severity", row.get("severity"));
        result.put("status", row.get("status"));
        result.put("title", row.get("title"));
        result.put("detail", row.get("detail"));
        result.put("startedAt", row.get("started_at"));
        result.put("resolvedAt", row.get("resolved_at"));
        result.put("updatedAt", row.get("updated_at"));
        result.put("read", intValue(row.get("is_read")) == 1);
        return result;
    }

    private Visibility visibility(Integer userId, Integer roleId, String alias) {
        if (roleId != null && roleId == ADMIN_ROLE_ID) {
            return new Visibility("1 = 1", List.of());
        }
        String prefix = alias + ".";
        String sql = "(" + prefix + "owner_user_id = ? "
                + "OR (" + prefix + "resource_type = 'node' AND EXISTS (SELECT 1 FROM user_node un "
                + "WHERE un.user_id = ? AND un.node_id = " + prefix + "resource_id)) "
                + "OR (" + prefix + "resource_type = 'tunnel' AND EXISTS (SELECT 1 FROM user_tunnel ut "
                + "WHERE ut.user_id = ? AND ut.tunnel_id = " + prefix + "resource_id)))";
        return new Visibility(sql, List.of(userId, userId, userId));
    }

    private Map<Integer, String> loadUserNames() {
        Map<Integer, String> result = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT id, user FROM user")) {
            result.put(intValue(row.get("id")), stringValue(row.get("user")));
        }
        return result;
    }

    private Set<ResourceKey> toResourceKeys(List<Map<String, Object>> rows) {
        Set<ResourceKey> result = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            result.add(new ResourceKey(stringValue(row.get("resource_type")), longValue(row.get("resource_id"))));
        }
        return result;
    }

    private List<Long> parseNodePath(String rawPath, long inNodeId, long outNodeId, int type) {
        LinkedHashSet<Long> path = new LinkedHashSet<>();
        if (type == 1) {
            if (inNodeId > 0) path.add(inNodeId);
            return new ArrayList<>(path);
        }
        if (rawPath != null && !rawPath.isBlank()) {
            for (String part : rawPath.split(",")) {
                try {
                    long id = Long.parseLong(part.trim());
                    if (id > 0) path.add(id);
                } catch (NumberFormatException ignored) {
                    // Invalid path elements are omitted and surface as a path failure below.
                }
            }
        }
        if (path.isEmpty()) {
            if (inNodeId > 0) path.add(inNodeId);
            if (outNodeId > 0) path.add(outNodeId);
        }
        return new ArrayList<>(path);
    }

    private Map<Long, String> parseRouteStatuses(String rawConfig) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (rawConfig == null || rawConfig.isBlank()) {
            return result;
        }
        try {
            JSONArray routes = JSON.parseArray(rawConfig);
            for (Object routeValue : routes) {
                JSONObject route = routeValue instanceof JSONObject
                        ? (JSONObject) routeValue
                        : JSON.parseObject(JSON.toJSONString(routeValue));
                Long tunnelId = route.getLong("tunnelId");
                if (tunnelId != null) {
                    result.put(tunnelId, stringValue(route.get("status")).toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception ignored) {
            log.debug("Ignoring invalid route configuration in monitoring scan");
        }
        return result;
    }

    private TargetHealth parseTargetHealth(String rawHealth) {
        if (rawHealth == null || rawHealth.isBlank()) {
            return new TargetHealth(0, 0);
        }
        try {
            JSONArray targets = JSON.parseArray(rawHealth);
            int healthy = 0;
            for (Object targetValue : targets) {
                JSONObject target = targetValue instanceof JSONObject
                        ? (JSONObject) targetValue
                        : JSON.parseObject(JSON.toJSONString(targetValue));
                if ("healthy".equalsIgnoreCase(stringValue(target.get("status")))) {
                    healthy++;
                }
            }
            return new TargetHealth(targets.size(), healthy);
        } catch (Exception ignored) {
            return new TargetHealth(0, 0);
        }
    }

    private void ensureMonitoringData() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM monitoring_current", Integer.class);
        if (count != null && count == 0) {
            scanSafely();
        }
    }

    private String resourceTypeLabel(String type) {
        return switch (type) {
            case "node" -> "节点";
            case "tunnel" -> "隧道";
            case "forward" -> "转发";
            case "certificate" -> "证书";
            default -> "资源";
        };
    }

    private int statusRank(String status) {
        return switch (status) {
            case OFFLINE -> 0;
            case DEGRADED -> 1;
            case UNKNOWN -> 2;
            case PAUSED -> 3;
            default -> 4;
        };
    }

    private double percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return 100.0;
        }
        return Math.round((numerator * 10000.0 / denominator)) / 100.0;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private long longValue(Object value) {
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    private long nullableLong(Object value, long fallback) {
        return value == null ? fallback : longValue(value);
    }

    private int intValue(Object value) {
        return value == null ? 0 : Integer.parseInt(value.toString());
    }

    private List<Object> prepend(Object first, List<?> values) {
        List<Object> result = new ArrayList<>();
        result.add(first);
        result.addAll(values);
        return result;
    }

    private record ResourceState(String type, long id, String name, int ownerUserId, String status, String detail) {}
    private record ResourceKey(String type, long id) {}
    private record HistoryInterval(String status, long startedAt, long endedAt) {}
    private record Availability(long healthyMs, long coveredMs, long trackedFrom, double percentage) {}
    private record TargetHealth(int total, int healthy) {
        private int unhealthy() { return Math.max(0, total - healthy); }
    }
    private record Visibility(String sql, List<Object> args) {}
    private record RangeWindow(String key, long durationMs, int bucketCount) {
        private static RangeWindow from(String requested) {
            return switch (requested == null ? "24h" : requested) {
                case "7d" -> new RangeWindow("7d", 7 * 86_400_000L, 14);
                case "30d" -> new RangeWindow("30d", 30 * 86_400_000L, 30);
                default -> new RangeWindow("24h", 86_400_000L, 24);
            };
        }
    }
}
