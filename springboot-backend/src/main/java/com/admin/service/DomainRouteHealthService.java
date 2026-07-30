package com.admin.service;

import com.admin.entity.DomainRoute;
import com.admin.mapper.DomainRouteMapper;
import com.admin.mapper.NodeMapper;
import com.admin.entity.Node;
import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class DomainRouteHealthService {
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Resource
    private DomainRouteMapper domainRouteMapper;

    @Resource
    private NodeMapper nodeMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ManagedCertificateService managedCertificateService;

    @Scheduled(initialDelay = 45_000L, fixedDelay = 60_000L)
    public void checkDueRoutes() {
        long now = System.currentTimeMillis();
        List<DomainRoute> routes = domainRouteMapper.selectList(new QueryWrapper<DomainRoute>()
                .eq("state", "active")
                .and(q -> q.isNull("health_checked_at").or().lt("health_checked_at", now - 60_000L))
                .orderByAsc("health_checked_at").last("LIMIT 20"));
        for (DomainRoute route : routes) {
            CompletableFuture.runAsync(() -> check(route), executor);
        }
        List<Map<String, Object>> backends = jdbcTemplate.queryForList(
                "SELECT b.id,b.route_id AS routeId,b.backend_type AS backendType,b.published_service_id AS publishedServiceId,"
                        + "b.backend_node_id AS backendNodeId,b.backend_host AS backendHost,b.backend_port AS backendPort,"
                        + "b.health_state AS healthState,b.fail_count AS failCount,b.success_count AS successCount,"
                        + "r.state AS routeState,p.state AS mappingState,p.connector_id AS connectorId "
                        + "FROM domain_route_backend b JOIN domain_route r ON r.id=b.route_id "
                        + "LEFT JOIN published_service p ON p.id=b.published_service_id "
                        + "WHERE b.enabled=1 AND r.state='active' AND (b.health_checked_at IS NULL OR b.health_checked_at<?) "
                        + "ORDER BY COALESCE(b.health_checked_at,0) LIMIT 40", now - 30_000L);
        for (Map<String, Object> backend : backends) CompletableFuture.runAsync(() -> checkBackend(backend), executor);
    }

    private void checkBackend(Map<String, Object> backend) {
        long started = System.currentTimeMillis();
        boolean healthy = false;
        String error = null;
        try {
            if ("mapping".equals(Objects.toString(backend.get("backendType")))) {
                healthy = "active".equals(Objects.toString(backend.get("mappingState")))
                        && backend.get("connectorId") != null
                        && WebSocketServer.isConnectorOnline(((Number) backend.get("connectorId")).longValue());
                if (!healthy) error = "内网映射或接入端不可用";
            } else {
                Node node = backend.get("backendNodeId") == null ? null
                        : nodeMapper.selectById(((Number) backend.get("backendNodeId")).longValue());
                if (node == null || !WebSocketServer.isNodeOnline(node.getId())) {
                    error = "后端节点离线";
                } else {
                    JSONObject request = new JSONObject();
                    String host = Objects.toString(backend.get("backendHost"), "127.0.0.1");
                    if ("0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host)) host = "127.0.0.1";
                    request.put("ip", host);
                    request.put("port", ((Number) backend.get("backendPort")).intValue());
                    request.put("count", 1);
                    request.put("timeout", 2000);
                    GostDto response = WebSocketServer.send_msg(node.getId(), request, "TcpPing");
                    if (response != null && "OK".equals(response.getMsg()) && response.getData() instanceof JSONObject) {
                        JSONObject data = (JSONObject) response.getData();
                        healthy = data.getBooleanValue("success");
                        if (!healthy) error = data.getString("errorMessage");
                    } else {
                        error = response == null ? "Agent 无响应" : response.getMsg();
                    }
                }
            }
        } catch (Exception e) {
            error = e.getMessage();
        }
        String previous = Objects.toString(backend.get("healthState"), "pending");
        int fails = ((Number) backend.getOrDefault("failCount", 0)).intValue();
        int successes = ((Number) backend.getOrDefault("successCount", 0)).intValue();
        String next = previous;
        if (healthy) {
            successes++;
            fails = 0;
            if (!"unhealthy".equals(previous) || successes >= 2) next = "healthy";
        } else {
            fails++;
            successes = 0;
            if (fails >= 2) next = "unhealthy";
        }
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE domain_route_backend SET health_state=?,fail_count=?,success_count=?,health_latency_ms=?,health_error=?,health_checked_at=?,updated_time=? WHERE id=?",
                next, fails, successes, now - started, healthy ? null : shorten(Objects.toString(error, "后端不可用")), now, now,
                ((Number) backend.get("id")).longValue());
        if (!next.equals(previous) && ("unhealthy".equals(next) || "unhealthy".equals(previous))) {
            try {
                managedCertificateService.deployForRoute(((Number) backend.get("routeId")).longValue());
            } catch (RuntimeException e) {
                log.warn("Could not refresh HTTPS backend pool for route {}: {}", backend.get("routeId"), e.getMessage());
            }
        }
    }

    private void check(DomainRoute route) {
        long started = System.currentTimeMillis();
        try {
            HealthResult result = "managed_https".equals(route.getIngressMode())
                    ? checkHttp(route)
                    : checkTcp(route);
            route.setHealthState(result.ok ? "healthy" : "unhealthy");
            route.setHealthStatusCode(result.statusCode);
            route.setHealthLatencyMs(result.latencyMs);
            route.setHealthError(result.ok ? null : result.error);
        } catch (Exception e) {
            route.setHealthState("unhealthy");
            route.setHealthStatusCode(null);
            route.setHealthLatencyMs(System.currentTimeMillis() - started);
            route.setHealthError(shorten(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        long checkedAt = System.currentTimeMillis();
        route.setHealthCheckedAt(checkedAt);
        route.setUpdatedTime(checkedAt);
        try {
            domainRouteMapper.update(null, new UpdateWrapper<DomainRoute>()
                    .eq("id", route.getId())
                    .set("health_state", route.getHealthState())
                    .set("health_status_code", route.getHealthStatusCode())
                    .set("health_latency_ms", route.getHealthLatencyMs())
                    .set("health_checked_at", checkedAt)
                    .set("health_error", route.getHealthError())
                    .set("updated_time", checkedAt));
        } catch (Exception e) {
            log.warn("Could not store health state for domain route {}: {}", route.getId(), e.getMessage());
        }
    }

    private HealthResult checkHttp(DomainRoute route) throws Exception {
        String path = StringUtils.defaultIfBlank(route.getPathPrefix(), "/");
        URI uri = URI.create("https://" + route.getDomain() + ":" + route.getListenPort() + normalizePath(path));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6))
                .header("User-Agent", "CloudNest-Health-Check").GET().build();
        long started = System.currentTimeMillis();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        return new HealthResult(status < 500, status, System.currentTimeMillis() - started,
                status < 500 ? null : "HTTP 返回 " + status);
    }

    private HealthResult checkTcp(DomainRoute route) throws Exception {
        Node entryNode = nodeMapper.selectById(route.getNodeId());
        String host = entryNode == null ? route.getDomain() : StringUtils.firstNonBlank(entryNode.getServerIp(), entryNode.getIp(), route.getDomain());
        long started = System.currentTimeMillis();
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress(host, route.getListenPort()), 4000);
            return new HealthResult(true, 0, System.currentTimeMillis() - started, null);
        }
    }

    private String normalizePath(String value) {
        String path = value == null || value.isBlank() ? "/" : value.trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    private String shorten(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record HealthResult(boolean ok, Integer statusCode, long latencyMs, String error) {
    }
}
