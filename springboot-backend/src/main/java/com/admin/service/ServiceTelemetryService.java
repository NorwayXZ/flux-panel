package com.admin.service;

import com.admin.common.dto.FlowDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ServiceTelemetryService {
    private static final ZoneId PANEL_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbcTemplate;

    public ServiceTelemetryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(Long nodeId, FlowDto flow) {
        if (nodeId == null || flow == null || StringUtils.isBlank(flow.getN())) return;
        long now = validTimestamp(flow.getA()) ? flow.getA() : System.currentTimeMillis();
        long upload = positive(flow.getU());
        long download = positive(flow.getD());
        try {
            List<Map<String, Object>> previous = jdbcTemplate.queryForList(
                    "SELECT sampled_at FROM service_telemetry_latest WHERE node_id=? AND service_name=?",
                    nodeId, flow.getN());
            long elapsed = previous.isEmpty() ? 0 : Math.max(0, now - number(previous.get(0).get("sampled_at")));
            long uploadSpeed = bytesPerSecond(upload, elapsed);
            long downloadSpeed = bytesPerSecond(download, elapsed);
            jdbcTemplate.update("INSERT INTO service_telemetry_latest "
                            + "(node_id,service_name,total_connections,current_connections,total_errors,upload_speed,download_speed,"
                            + "interval_upload,interval_download,sampled_at,updated_time) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE total_connections=VALUES(total_connections),current_connections=VALUES(current_connections),"
                            + "total_errors=VALUES(total_errors),upload_speed=VALUES(upload_speed),download_speed=VALUES(download_speed),"
                            + "interval_upload=VALUES(interval_upload),interval_download=VALUES(interval_download),"
                            + "sampled_at=VALUES(sampled_at),updated_time=VALUES(updated_time)",
                    nodeId, flow.getN(), positive(flow.getT()), positive(flow.getC()), positive(flow.getE()),
                    uploadSpeed, downloadSpeed, upload, download, now, System.currentTimeMillis());
            if (upload > 0 || download > 0) {
                jdbcTemplate.update("INSERT INTO service_traffic_daily "
                                + "(traffic_date,node_id,service_name,upload_bytes,download_bytes,updated_time) VALUES (?,?,?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE upload_bytes=upload_bytes+VALUES(upload_bytes),"
                                + "download_bytes=download_bytes+VALUES(download_bytes),updated_time=VALUES(updated_time)",
                        Date.valueOf(LocalDate.now(PANEL_ZONE)), nodeId, flow.getN(), upload, download, System.currentTimeMillis());
            }
            recordSamples(nodeId, flow.getN(), "source", flow.getS(), now);
            recordSamples(nodeId, flow.getN(), "domain", flow.getH(), now);
        } catch (DataAccessException e) {
            // Monitoring must never interrupt the existing quota and forwarding pipeline.
        }
    }

    public R summaries() {
        Integer userId = JwtUtil.getUserIdFromToken();
        boolean admin = Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Map<String, Object>> totalsByService = totalsByService();
        Map<String, Map<String, Object>> todayByService = todayByService();
        String serviceFilter = admin ? "" : " AND p.user_id=" + userId;
        List<Map<String, Object>> services = jdbcTemplate.queryForList(
                "SELECT p.id,p.user_id AS userId,p.name,p.service_name AS serviceName,u.user AS ownerName,p.created_time AS createdTime "
                        + "FROM published_service p LEFT JOIN user u ON u.id=p.user_id WHERE p.state NOT IN ('released','deleted')" + serviceFilter);
        for (Map<String, Object> service : services) {
            result.add(summary("service", number(service.get("id")), service, false, false,
                    totalsByService.get(service.get("serviceName")), todayByService.get(service.get("serviceName"))));
        }
        String domainFilter = admin ? "" : " AND r.user_id=" + userId;
        List<Map<String, Object>> domains = jdbcTemplate.queryForList(
                "SELECT r.id,r.user_id AS userId,r.name,r.domain,r.service_name AS serviceName,u.user AS ownerName,r.created_time AS createdTime,"
                        + "(SELECT COUNT(*) FROM domain_route r2 WHERE r2.service_name=r.service_name AND r2.state<>'deleted') AS sharedCount "
                        + "FROM domain_route r LEFT JOIN user u ON u.id=r.user_id WHERE r.state<>'deleted'" + domainFilter);
        for (Map<String, Object> route : domains) {
            boolean shared = number(route.get("sharedCount")) > 1;
            result.add(summary("domain", number(route.get("id")), route, shared, !admin && shared,
                    totalsByService.get(route.get("serviceName")), todayByService.get(route.get("serviceName"))));
        }
        return R.ok(result);
    }

    public R detail(String resourceType, Long resourceId) {
        Map<String, Object> resource = ownedResource(resourceType, resourceId);
        if (resource == null) return R.err("服务不存在或无权查看");
        boolean shared = "domain".equals(resourceType) && number(resource.get("sharedCount")) > 1;
        boolean hideSharedTotals = !Objects.equals(JwtUtil.getRoleIdFromToken(), 0) && shared;
        Map<String, Object> result = summary(resourceType, resourceId, resource, shared, hideSharedTotals);
        String serviceName = Objects.toString(resource.get("serviceName"), "");
        result.put("sources", hideSharedTotals ? List.of() : samples(serviceName, "source", null, true, 20));
        result.put("topSources", hideSharedTotals ? List.of() : samples(serviceName, "source", null, false, 10));
        result.put("domains", samples(serviceName, "domain",
                "domain".equals(resourceType) ? Objects.toString(resource.get("domain"), "") : null, false, 20));
        return R.ok(result);
    }

    private Map<String, Object> summary(String resourceType, long resourceId, Map<String, Object> resource, boolean sharedIngress) {
        return summary(resourceType, resourceId, resource, sharedIngress, false);
    }

    private Map<String, Object> summary(String resourceType, long resourceId, Map<String, Object> resource,
                                        boolean sharedIngress, boolean hideSharedTotals) {
        String serviceName = Objects.toString(resource.get("serviceName"), "");
        Map<String, Object> totals = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(current_connections),0) AS currentConnections,COALESCE(SUM(upload_speed),0) AS uploadSpeed,"
                        + "COALESCE(SUM(download_speed),0) AS downloadSpeed,COALESCE(SUM(total_errors),0) AS failedConnections,"
                        + "MAX(updated_time) AS updatedAt FROM service_telemetry_latest WHERE service_name=?", serviceName);
        Map<String, Object> today = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(upload_bytes),0) AS todayUpload,COALESCE(SUM(download_bytes),0) AS todayDownload "
                        + "FROM service_traffic_daily WHERE service_name=? AND traffic_date=CURRENT_DATE", serviceName);
        return summary(resourceType, resourceId, resource, sharedIngress, hideSharedTotals, totals, today);
    }

    private Map<String, Object> summary(String resourceType, long resourceId, Map<String, Object> resource,
                                        boolean sharedIngress, boolean hideSharedTotals,
                                        Map<String, Object> totals, Map<String, Object> today) {
        String serviceName = Objects.toString(resource.get("serviceName"), "");
        totals = totals == null ? Map.of() : totals;
        today = today == null ? Map.of() : today;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceType", resourceType);
        result.put("resourceId", resourceId);
        result.put("serviceName", serviceName);
        result.put("name", resource.get("name"));
        result.put("domain", resource.get("domain"));
        result.put("ownerUserId", resource.get("userId"));
        result.put("ownerUserName", resource.get("ownerName"));
        result.put("createdTime", resource.get("createdTime"));
        long todayUpload = hideSharedTotals ? 0 : number(today.get("todayUpload"));
        long todayDownload = hideSharedTotals ? 0 : number(today.get("todayDownload"));
        long updatedAt = number(totals.get("updatedAt"));
        boolean live = updatedAt > 0 && System.currentTimeMillis() - updatedAt < 20_000L;
        result.put("currentConnections", hideSharedTotals || !live ? 0 : number(totals.get("currentConnections")));
        result.put("uploadSpeed", hideSharedTotals || !live ? 0 : number(totals.get("uploadSpeed")));
        result.put("downloadSpeed", hideSharedTotals || !live ? 0 : number(totals.get("downloadSpeed")));
        result.put("failedConnections", hideSharedTotals ? 0 : number(totals.get("failedConnections")));
        result.put("todayUpload", todayUpload);
        result.put("todayDownload", todayDownload);
        result.put("todayTotal", todayUpload + todayDownload);
        result.put("updatedAt", updatedAt);
        result.put("live", live);
        result.put("sharedIngress", sharedIngress);
        result.put("sharedTotalsHidden", hideSharedTotals);
        return result;
    }

    private Map<String, Map<String, Object>> totalsByService() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT service_name AS serviceName,COALESCE(SUM(current_connections),0) AS currentConnections,"
                        + "COALESCE(SUM(upload_speed),0) AS uploadSpeed,COALESCE(SUM(download_speed),0) AS downloadSpeed,"
                        + "COALESCE(SUM(total_errors),0) AS failedConnections,MAX(updated_time) AS updatedAt "
                        + "FROM service_telemetry_latest GROUP BY service_name")) {
            result.put(Objects.toString(row.get("serviceName"), ""), row);
        }
        return result;
    }

    private Map<String, Map<String, Object>> todayByService() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT service_name AS serviceName,COALESCE(SUM(upload_bytes),0) AS todayUpload,"
                        + "COALESCE(SUM(download_bytes),0) AS todayDownload FROM service_traffic_daily "
                        + "WHERE traffic_date=CURRENT_DATE GROUP BY service_name")) {
            result.put(Objects.toString(row.get("serviceName"), ""), row);
        }
        return result;
    }

    private Map<String, Object> ownedResource(String type, Long id) {
        if (id == null || (!"service".equals(type) && !"domain".equals(type))) return null;
        boolean admin = Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
        int userId = JwtUtil.getUserIdFromToken();
        String table = "service".equals(type) ? "published_service" : "domain_route";
        String extra = "domain".equals(type)
                ? ",(SELECT COUNT(*) FROM domain_route r2 WHERE r2.service_name=t.service_name AND r2.state<>'deleted') AS sharedCount"
                : "";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.id,t.user_id AS userId,t.name,t.service_name AS serviceName,u.user AS ownerName,t.created_time AS createdTime"
                        + ("domain".equals(type) ? ",t.domain" : "") + extra
                        + " FROM " + table + " t LEFT JOIN user u ON u.id=t.user_id WHERE t.id=?"
                        + (admin ? "" : " AND t.user_id=" + userId), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> samples(String serviceName, String type, String exactValue,
                                              boolean recentFirst, int limit) {
        String sql = "SELECT sample_value AS value,source_kind AS sourceKind,SUM(seen_count) AS count,MAX(last_seen) AS lastSeen "
                + "FROM service_telemetry_sample WHERE service_name=? AND sample_type=?";
        List<Object> args = new ArrayList<>(List.of(serviceName, type));
        if (StringUtils.isNotBlank(exactValue)) {
            sql += " AND sample_value=?";
            args.add(exactValue.toLowerCase());
        }
        sql += " GROUP BY sample_value,source_kind ORDER BY "
                + (recentFirst ? "lastSeen DESC,count DESC" : "count DESC,lastSeen DESC")
                + " LIMIT " + Math.max(1, Math.min(limit, 20));
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private void recordSamples(Long nodeId, String serviceName, String type, List<FlowDto.TelemetrySample> samples, long now) {
        if (samples == null) return;
        for (FlowDto.TelemetrySample sample : samples.stream().limit(20).toList()) {
            if (sample == null || StringUtils.isBlank(sample.getV())) continue;
            String value = sample.getV().trim().toLowerCase();
            if (value.length() > 255) value = value.substring(0, 255);
            String kind = StringUtils.defaultString(sample.getK());
            jdbcTemplate.update("INSERT INTO service_telemetry_sample "
                            + "(node_id,service_name,sample_type,sample_value,source_kind,seen_count,last_seen,updated_time) VALUES (?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE seen_count=VALUES(seen_count),last_seen=GREATEST(last_seen,VALUES(last_seen)),updated_time=VALUES(updated_time)",
                    nodeId, serviceName, type, value, kind, positive(sample.getC()),
                    validTimestamp(sample.getL()) ? sample.getL() : now, System.currentTimeMillis());
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_telemetry_sample WHERE node_id=? AND service_name=? AND sample_type=?",
                Integer.class, nodeId, serviceName, type);
        int excess = count == null ? 0 : count - 100;
        if (excess > 0) {
            jdbcTemplate.update("DELETE FROM service_telemetry_sample WHERE node_id=? AND service_name=? AND sample_type=? "
                    + "ORDER BY last_seen ASC LIMIT " + excess, nodeId, serviceName, type);
        }
    }

    private static boolean validTimestamp(Long value) {
        return value != null && value > 1_500_000_000_000L && value < System.currentTimeMillis() + 86_400_000L;
    }

    private static long positive(Number value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    static long bytesPerSecond(long bytes, long elapsedMillis) {
        if (bytes <= 0 || elapsedMillis < 1000) return 0;
        return bytes * 1000 / elapsedMillis;
    }
}
