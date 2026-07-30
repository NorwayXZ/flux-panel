package com.admin.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QualityStatistics {
    private QualityStatistics() { }

    public static double percentile(List<Double> input, double percentile) {
        if (input == null || input.isEmpty()) return 0;
        List<Double> values = input.stream().filter(value -> value != null && Double.isFinite(value) && value >= 0)
                .sorted(Comparator.naturalOrder()).toList();
        if (values.isEmpty()) return 0;
        double position = Math.max(0, Math.min(1, percentile)) * (values.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return values.get(lower);
        double weight = position - lower;
        return values.get(lower) * (1 - weight) + values.get(upper) * weight;
    }

    public static Map<String, Object> summary(List<Map<String, Object>> samples) {
        List<Double> totals = values(samples, "totalMs");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", samples.size());
        result.put("successCount", samples.stream().filter(row -> truth(row.get("success"))).count());
        result.put("tcpAvgMs", average(values(samples, "tcpMs")));
        result.put("tlsAvgMs", average(values(samples, "tlsMs")));
        result.put("ttfbAvgMs", average(values(samples, "ttfbMs")));
        result.put("p50Ms", percentile(totals, 0.50));
        result.put("p95Ms", percentile(totals, 0.95));
        result.put("p99Ms", percentile(totals, 0.99));
        return result;
    }

    private static List<Double> values(List<Map<String, Object>> rows, String key) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.containsKey("success") && !truth(row.get("success"))) continue;
            Object value = row.get(key);
            if (value instanceof Number && ((Number) value).doubleValue() > 0) values.add(((Number) value).doubleValue());
        }
        return values;
    }

    private static double average(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static boolean truth(Object value) {
        return value instanceof Boolean ? (Boolean) value : value instanceof Number && ((Number) value).intValue() == 1;
    }
}
