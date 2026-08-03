package com.admin.service;

import java.util.ArrayList;
import java.util.List;

public final class IpRiskScoring {
    private IpRiskScoring() { }

    public record Result(Integer score, String level, String confidence) { }

    public static Result calculate(Integer ipqs, Integer abuse, int blacklistHits) {
        List<Integer> values = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        if (ipqs != null) { values.add(clamp(ipqs)); weights.add(60); }
        if (abuse != null) { values.add(clamp(abuse)); weights.add(40); }
        if (values.isEmpty()) return new Result(null, "unknown", blacklistHits > 0 ? "low" : "none");
        int weighted = 0, total = 0;
        for (int i = 0; i < values.size(); i++) { weighted += values.get(i) * weights.get(i); total += weights.get(i); }
        int score = Math.max(Math.round((float) weighted / total), Math.min(100, blacklistHits * 25));
        String level = score >= 75 ? "high" : score >= 40 ? "medium" : "low";
        return new Result(score, level, values.size() == 2 ? "high" : "medium");
    }

    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }
}
