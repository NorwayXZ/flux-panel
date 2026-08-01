package com.admin.common.interceptor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiRequestTimingRegistryTests {

    @Test
    void aggregatesRecentRequestsAndRanksByP95() {
        ApiRequestTimingRegistry registry = new ApiRequestTimingRegistry(300, 900_000, 256);

        registry.record("GET", "/api/fast", 200, 100, false, "req-fast-1");
        registry.record("GET", "/api/fast", 500, 500, false, "req-fast-2");
        registry.record("POST", "/api/slow", 200, 900, false, "req-slow-1");

        Map<String, Object> snapshot = registry.snapshot();
        Map<String, Object> summary = castMap(snapshot.get("summary"));
        List<Map<String, Object>> routes = castRoutes(snapshot.get("routes"));

        assertEquals(2, summary.get("routeCount"));
        assertEquals(3L, summary.get("totalRequests"));
        assertEquals(1L, summary.get("errorCount"));
        assertEquals(2L, summary.get("slowCount"));
        assertEquals("POST /api/slow", routes.get(0).get("route"));
        assertEquals(900L, routes.get(0).get("p95Ms"));
        assertEquals(2L, routes.get(1).get("requestCount"));
        assertEquals(500L, routes.get(1).get("p95Ms"));
        assertEquals("req-fast-2", routes.get(1).get("lastRequestId"));
    }

    @Test
    void removesSamplesOutsideTheRollingWindow() {
        ApiRequestTimingRegistry registry = new ApiRequestTimingRegistry(1000, 60_000, 256);
        registry.record("GET", "/api/temporary", 200, 20, false, "req-old");

        Map<String, Object> snapshot = registry.snapshotAt(System.currentTimeMillis() + 60_001);
        Map<String, Object> summary = castMap(snapshot.get("summary"));

        assertEquals(0, summary.get("routeCount"));
        assertEquals(0L, summary.get("totalRequests"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castRoutes(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
