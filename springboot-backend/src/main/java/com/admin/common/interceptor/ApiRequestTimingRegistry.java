package com.admin.common.interceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps a bounded, in-memory view of recent API timings.
 * It is intentionally ephemeral: restarting the panel clears observations and
 * never touches the business database.
 */
@Component
public class ApiRequestTimingRegistry {
    private static final int MAX_ROUTE_BUCKETS = 500;

    private final long slowRequestThresholdMs;
    private final long windowMs;
    private final int maxSamplesPerRoute;
    private final ConcurrentMap<String, TimingBucket> buckets = new ConcurrentHashMap<>();

    public ApiRequestTimingRegistry(
            @Value("${observability.slow-api-threshold-ms:1000}") long slowRequestThresholdMs,
            @Value("${observability.api-stats-window-ms:900000}") long windowMs,
            @Value("${observability.api-stats-max-samples:256}") int maxSamplesPerRoute
    ) {
        this.slowRequestThresholdMs = Math.max(1, slowRequestThresholdMs);
        this.windowMs = Math.max(60_000, windowMs);
        this.maxSamplesPerRoute = Math.max(16, maxSamplesPerRoute);
    }

    public void record(String method, String path, int status, long durationMs, boolean error, String requestId) {
        String route = (method == null ? "UNKNOWN" : method) + " " + (path == null ? "" : path);
        TimingBucket bucket = buckets.get(route);
        if (bucket == null) {
            if (buckets.size() >= MAX_ROUTE_BUCKETS) return;
            TimingBucket candidate = new TimingBucket();
            TimingBucket existing = buckets.putIfAbsent(route, candidate);
            bucket = existing == null ? candidate : existing;
        }
        bucket.record(new TimingSample(
                System.currentTimeMillis(),
                Math.max(0, durationMs),
                status,
                error || status >= 400,
                requestId
        ), maxSamplesPerRoute);
    }

    public Map<String, Object> snapshot() {
        return snapshotAt(System.currentTimeMillis());
    }

    Map<String, Object> snapshotAt(long now) {
        List<Map<String, Object>> routes = new ArrayList<>();
        long totalRequests = 0;
        long totalErrors = 0;
        long totalSlowRequests = 0;

        for (Map.Entry<String, TimingBucket> entry : buckets.entrySet()) {
            Map<String, Object> routeSnapshot = entry.getValue().snapshot(now, windowMs, maxSamplesPerRoute, slowRequestThresholdMs);
            long requestCount = number(routeSnapshot.get("requestCount"));
            if (requestCount == 0) continue;
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("route", entry.getKey());
            route.put("requestCount", requestCount);
            route.put("errorCount", number(routeSnapshot.get("errorCount")));
            route.put("slowCount", number(routeSnapshot.get("slowCount")));
            route.put("avgMs", number(routeSnapshot.get("avgMs")));
            route.put("p50Ms", number(routeSnapshot.get("p50Ms")));
            route.put("p95Ms", number(routeSnapshot.get("p95Ms")));
            route.put("maxMs", number(routeSnapshot.get("maxMs")));
            route.put("lastMs", number(routeSnapshot.get("lastMs")));
            route.put("lastStatus", number(routeSnapshot.get("lastStatus")));
            route.put("lastAt", number(routeSnapshot.get("lastAt")));
            route.put("lastRequestId", String.valueOf(routeSnapshot.getOrDefault("lastRequestId", "")));
            routes.add(route);
            totalRequests += requestCount;
            totalErrors += number(routeSnapshot.get("errorCount"));
            totalSlowRequests += number(routeSnapshot.get("slowCount"));
        }

        routes.sort(Comparator
                .comparingLong((Map<String, Object> route) -> number(route.get("p95Ms"))).reversed()
                .thenComparing(Comparator.comparingLong((Map<String, Object> route) -> number(route.get("avgMs"))).reversed())
                .thenComparing(Comparator.comparingLong((Map<String, Object> route) -> number(route.get("requestCount"))).reversed()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capturedAt", now);
        result.put("windowMs", windowMs);
        result.put("thresholdMs", slowRequestThresholdMs);
        result.put("summary", Map.of(
                "routeCount", routes.size(),
                "totalRequests", totalRequests,
                "errorCount", totalErrors,
                "slowCount", totalSlowRequests
        ));
        result.put("routes", routes);
        return result;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static final class TimingBucket {
        private final Deque<TimingSample> samples = new ArrayDeque<>();

        private synchronized void record(TimingSample sample, int maxSamples) {
            samples.addLast(sample);
            while (samples.size() > maxSamples) samples.removeFirst();
        }

        private synchronized Map<String, Object> snapshot(long now, long windowMs, int maxSamples, long thresholdMs) {
            long cutoff = now - windowMs;
            while (!samples.isEmpty() && samples.peekFirst().timestamp() < cutoff) samples.removeFirst();

            List<TimingSample> recent = new ArrayList<>(Math.min(samples.size(), maxSamples));
            int skip = Math.max(0, samples.size() - maxSamples);
            int index = 0;
            for (TimingSample sample : samples) {
                if (index++ >= skip) recent.add(sample);
            }
            if (recent.isEmpty()) return Map.of("requestCount", 0);

            List<Long> durations = recent.stream().map(TimingSample::durationMs).sorted().toList();
            long total = durations.stream().mapToLong(Long::longValue).sum();
            long errorCount = recent.stream().filter(TimingSample::error).count();
            long slowCount = recent.stream().filter(sample -> sample.durationMs() >= thresholdMs).count();
            TimingSample last = recent.get(recent.size() - 1);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requestCount", recent.size());
            result.put("errorCount", errorCount);
            result.put("slowCount", slowCount);
            result.put("avgMs", Math.round((double) total / recent.size()));
            result.put("p50Ms", percentile(durations, 0.50));
            result.put("p95Ms", percentile(durations, 0.95));
            result.put("maxMs", durations.get(durations.size() - 1));
            result.put("lastMs", last.durationMs());
            result.put("lastStatus", last.status());
            result.put("lastAt", last.timestamp());
            result.put("lastRequestId", last.requestId());
            return result;
        }

        private long percentile(List<Long> sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
            return sorted.get(index);
        }
    }

    private record TimingSample(long timestamp, long durationMs, int status, boolean error, String requestId) {
    }
}
