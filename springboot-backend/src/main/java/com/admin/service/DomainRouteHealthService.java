package com.admin.service;

import com.admin.entity.DomainRoute;
import com.admin.mapper.DomainRouteMapper;
import com.admin.mapper.NodeMapper;
import com.admin.entity.Node;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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
        route.setHealthCheckedAt(System.currentTimeMillis());
        route.setUpdatedTime(System.currentTimeMillis());
        try {
            domainRouteMapper.updateById(route);
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
