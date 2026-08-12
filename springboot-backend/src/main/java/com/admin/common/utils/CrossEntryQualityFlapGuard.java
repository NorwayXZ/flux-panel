package com.admin.common.utils;

public final class CrossEntryQualityFlapGuard {
    private CrossEntryQualityFlapGuard() {
    }

    public static Decision evaluate(Snapshot snapshot, Settings settings) {
        if (!settings.enabled()) {
            return new Decision(0, null, null, null, false, false);
        }

        long now = snapshot.now();
        int count = Math.max(0, snapshot.flapCount());
        Long windowStartedAt = snapshot.windowStartedAt();
        Long suppressedUntil = snapshot.suppressedUntil();
        String suppressedReason = snapshot.suppressedReason();

        if (suppressedUntil != null && suppressedUntil <= now) {
            suppressedUntil = null;
            suppressedReason = null;
            count = 0;
            windowStartedAt = null;
        }

        long windowMs = Math.max(1L, settings.windowSeconds()) * 1000L;
        boolean transitionedToDegraded = "degraded".equals(snapshot.newState())
                && !"degraded".equals(snapshot.previousState());

        if (!transitionedToDegraded) {
            if (windowStartedAt != null && now - windowStartedAt > windowMs) {
                count = 0;
                windowStartedAt = null;
            }
            return new Decision(count, windowStartedAt, suppressedUntil, suppressedReason, false,
                    suppressedUntil != null && suppressedUntil > now);
        }

        if (windowStartedAt == null || now - windowStartedAt > windowMs) {
            windowStartedAt = now;
            count = 1;
        } else {
            count++;
        }

        int threshold = Math.max(1, settings.threshold());
        if (count < threshold) {
            return new Decision(count, windowStartedAt, suppressedUntil, suppressedReason, false,
                    suppressedUntil != null && suppressedUntil > now);
        }

        suppressedUntil = now + Math.max(1L, settings.suppressSeconds()) * 1000L;
        suppressedReason = "质量抖动保护";
        return new Decision(0, null, suppressedUntil, suppressedReason, true, true);
    }

    public record Snapshot(String previousState, String newState, int flapCount, Long windowStartedAt,
                           Long suppressedUntil, String suppressedReason, long now) {
    }

    public record Settings(boolean enabled, int windowSeconds, int threshold, int suppressSeconds) {
    }

    public record Decision(int flapCount, Long windowStartedAt, Long suppressedUntil, String suppressedReason,
                           boolean newlySuppressed, boolean suppressed) {
    }
}
