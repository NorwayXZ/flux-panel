package com.admin.common.utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AggregationWeightPolicy {
    public static final long METRIC_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int DEFAULT_WEIGHT = 100;

    private AggregationWeightPolicy() {
    }

    public record PathMetric(long tunnelId, Double bandwidthMbps, Double latencyMs,
                             Double lossPercent, Double jitterMs, Long measuredAt,
                             boolean healthy, Integer previousWeight) {
    }

    public static Map<Long, Integer> calculate(String mode, List<PathMetric> paths, long now) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        double maximum = 0;
        for (PathMetric path : paths) {
            double score = score(normalizeMode(mode), path, now);
            scores.put(path.tunnelId(), score);
            maximum = Math.max(maximum, score);
        }

        Map<Long, Integer> result = new LinkedHashMap<>();
        for (PathMetric path : paths) {
            double score = scores.get(path.tunnelId());
            if (!path.healthy() || score <= 0 || maximum <= 0) {
                result.put(path.tunnelId(), 0);
                continue;
            }
            int calculated = Math.max(10, Math.min(1000, (int) Math.round(score / maximum * 1000)));
            Integer previous = path.previousWeight();
            if (previous != null && previous > 0 && relativeChange(previous, calculated) < 0.10) {
                calculated = previous;
            }
            result.put(path.tunnelId(), calculated);
        }
        return result;
    }

    private static double score(String mode, PathMetric path, long now) {
        if (!path.healthy()) return 0;
        boolean fresh = path.measuredAt() != null && now - path.measuredAt() <= METRIC_MAX_AGE_MS;
        double bandwidth = positive(path.bandwidthMbps(), 100);
        double latency = positive(path.latencyMs(), 120);
        double loss = clamp(nonNegative(path.lossPercent(), 0), 0, 100);
        double jitter = nonNegative(path.jitterMs(), 15);
        double latencyQuality = 1.0 / (1.0 + latency / 100.0);
        double lossQuality = Math.pow(Math.max(0.01, 1.0 - loss / 100.0), "stability".equals(mode) ? 5 : 3);
        double jitterQuality = 1.0 / (1.0 + jitter / ("stability".equals(mode) ? 15.0 : 35.0));
        double score;
        if ("speed".equals(mode)) {
            score = bandwidth * Math.pow(latencyQuality, 0.35) * lossQuality * Math.pow(jitterQuality, 0.35);
        } else if ("stability".equals(mode)) {
            score = Math.sqrt(bandwidth) * Math.pow(latencyQuality, 1.3) * lossQuality * Math.pow(jitterQuality, 1.5);
        } else {
            score = Math.pow(bandwidth, 0.75) * latencyQuality * lossQuality * jitterQuality;
        }
        return fresh ? score : Math.min(score, DEFAULT_WEIGHT) * 0.45;
    }

    private static String normalizeMode(String mode) {
        return "speed".equals(mode) || "stability".equals(mode) ? mode : "balanced";
    }

    private static double relativeChange(int previous, int current) {
        return Math.abs(current - previous) / (double) Math.max(previous, 1);
    }

    private static double positive(Double value, double fallback) {
        return value == null || !Double.isFinite(value) || value <= 0 ? fallback : value;
    }

    private static double nonNegative(Double value, double fallback) {
        return value == null || !Double.isFinite(value) || value < 0 ? fallback : value;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
