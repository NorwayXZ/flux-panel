package com.admin.common.utils;

public final class CrossEntryQualityEvaluator {
    private CrossEntryQualityEvaluator() {
    }

    public static Decision evaluate(Snapshot snapshot, Settings settings) {
        String previousState = snapshot.previousState() == null || snapshot.previousState().isBlank()
                ? "unknown"
                : snapshot.previousState();
        Integer referenceBaseline = positive(snapshot.baselineMs());
        Integer latency = positive(snapshot.latencyMs());
        Double loss = snapshot.lossPercent();

        boolean lossBad = loss != null && loss >= settings.lossThresholdPercent();
        boolean fixedLatencyBad = snapshot.success() && latency != null && settings.fixedTargetEnabled()
                && latency > settings.fixedTargetMs();
        boolean latencyBad = fixedLatencyBad || (snapshot.success() && latency != null && referenceBaseline != null
                && (latency >= referenceBaseline * settings.degradeFactor()
                || (settings.degradeThresholdMs() > referenceBaseline && latency >= settings.degradeThresholdMs())));
        boolean bad = !snapshot.success() || lossBad || latencyBad;
        boolean good = snapshot.success() && latency != null && !lossBad
                && (referenceBaseline == null
                || latency <= settings.recoverThresholdMs()
                || latency <= referenceBaseline * settings.recoverFactor());

        Integer nextBaseline = nextBaseline(referenceBaseline, latency, good, settings);
        int badCount = snapshot.badCount();
        int goodCount = snapshot.goodCount();
        String nextState = previousState;
        if (bad) {
            badCount++;
            goodCount = 0;
            if (badCount >= settings.degradeSamples()) {
                nextState = "degraded";
            } else if (!"healthy".equals(nextState) && !"degraded".equals(nextState)) {
                nextState = "warming";
            }
        } else if (good) {
            goodCount++;
            badCount = 0;
            if (goodCount >= settings.recoverSamples()) {
                nextState = "healthy";
            } else if (!"degraded".equals(nextState)) {
                nextState = "warming";
            }
        }
        return new Decision(nextState, nextBaseline, badCount, goodCount, bad, good, latencyBad, lossBad, fixedLatencyBad);
    }

    private static Integer nextBaseline(Integer referenceBaseline, Integer latency, boolean good, Settings settings) {
        if (latency == null) return referenceBaseline;
        if (referenceBaseline == null) return latency;
        if (latency < referenceBaseline) return latency;
        if (good && latency <= Math.max(settings.recoverThresholdMs(), referenceBaseline * settings.recoverFactor())) {
            return (int) Math.round(referenceBaseline * 0.9 + latency * 0.1);
        }
        return referenceBaseline;
    }

    private static Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    public record Snapshot(String previousState, Integer baselineMs, Integer latencyMs, Double lossPercent,
                           boolean success, int badCount, int goodCount) {
    }

    public record Settings(int degradeThresholdMs, int recoverThresholdMs, double degradeFactor,
                           double recoverFactor, int degradeSamples, int recoverSamples,
                           double lossThresholdPercent, boolean fixedTargetEnabled, int fixedTargetMs) {
    }

    public record Decision(String state, Integer baselineMs, int badCount, int goodCount,
                           boolean bad, boolean good, boolean latencyBad, boolean lossBad, boolean fixedLatencyBad) {
    }
}
