package com.admin.common.utils;

public final class CrossEntryQualityFlapGuard {
    private static final int MAX_PENALTY_LEVEL = 5;

    private CrossEntryQualityFlapGuard() {
    }

    public static Decision evaluate(Snapshot snapshot, Settings settings) {
        if (!settings.enabled()) {
            return new Decision(0, null, null, null, false, false,
                    0, 0, null, null, null, false);
        }

        long now = snapshot.now();
        int count = Math.max(0, snapshot.flapCount());
        Long windowStartedAt = snapshot.windowStartedAt();
        Long suppressedUntil = snapshot.suppressedUntil();
        String suppressedReason = snapshot.suppressedReason();
        int penaltyLevel = Math.max(0, snapshot.penaltyLevel());
        int penaltyEpisodeCount = Math.max(0, snapshot.penaltyEpisodeCount());
        Long penaltyWindowStartedAt = snapshot.penaltyWindowStartedAt();
        Long penaltyLastAt = snapshot.penaltyLastAt();
        Long recoveryObserveUntil = snapshot.recoveryObserveUntil();

        boolean expiredSuppression = suppressedUntil != null && suppressedUntil <= now;
        if (expiredSuppression) {
            suppressedUntil = null;
            count = 0;
            windowStartedAt = null;
        }
        if (recoveryObserveUntil != null && recoveryObserveUntil <= now) {
            recoveryObserveUntil = null;
        }

        boolean recovered = "healthy".equals(snapshot.newState())
                && snapshot.goodCount() >= Math.max(1, settings.recoverSamples());
        if (recovered && suppressedUntil == null) {
            recoveryObserveUntil = null;
            suppressedReason = null;
        }

        if (!settings.penaltyEnabled()) {
            penaltyLevel = 0;
            penaltyEpisodeCount = 0;
            penaltyWindowStartedAt = null;
            penaltyLastAt = null;
            recoveryObserveUntil = null;
            if (expiredSuppression) suppressedReason = null;
        } else if (penaltyLastAt != null
                && now - penaltyLastAt > Math.max(1L, settings.penaltyResetSeconds()) * 1000L
                && suppressedUntil == null && recoveryObserveUntil == null) {
            penaltyLevel = 0;
            penaltyEpisodeCount = 0;
            penaltyWindowStartedAt = null;
            penaltyLastAt = null;
        }

        boolean hardSuppressed = suppressedUntil != null && suppressedUntil > now;
        boolean recoveryObserving = !hardSuppressed && recoveryObserveUntil != null
                && recoveryObserveUntil > now && !recovered;
        if (recoveryObserving) {
            suppressedReason = suppressedReason == null ? "质量恢复观察" : suppressedReason;
        } else if (!hardSuppressed && suppressedReason != null) {
            suppressedReason = null;
        }

        long windowMs = Math.max(1L, settings.windowSeconds()) * 1000L;
        boolean transitionedToDegraded = "degraded".equals(snapshot.newState())
                && !"degraded".equals(snapshot.previousState());

        if (transitionedToDegraded && hardSuppressed) {
            return new Decision(0, null, suppressedUntil, suppressedReason, false, true,
                    penaltyLevel, penaltyEpisodeCount, penaltyWindowStartedAt, penaltyLastAt,
                    recoveryObserveUntil, false);
        }

        if (!transitionedToDegraded) {
            if (windowStartedAt != null && now - windowStartedAt > windowMs) {
                count = 0;
                windowStartedAt = null;
            }
            return new Decision(count, windowStartedAt, suppressedUntil, suppressedReason, false,
                    hardSuppressed || recoveryObserving, penaltyLevel, penaltyEpisodeCount,
                    penaltyWindowStartedAt, penaltyLastAt, recoveryObserveUntil, recoveryObserving);
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
                    hardSuppressed || recoveryObserving, penaltyLevel, penaltyEpisodeCount,
                    penaltyWindowStartedAt, penaltyLastAt, recoveryObserveUntil, recoveryObserving);
        }

        long suppressSeconds = Math.max(1L, settings.suppressSeconds());
        if (settings.penaltyEnabled()) {
            boolean recurring = penaltyLastAt != null
                    && now - penaltyLastAt <= Math.max(1L, settings.penaltyResetSeconds()) * 1000L;
            penaltyLevel = recurring && penaltyLevel > 0
                    ? Math.min(MAX_PENALTY_LEVEL, penaltyLevel + 1)
                    : 1;
            penaltyEpisodeCount = recurring ? Math.max(0, penaltyEpisodeCount) + 1 : 1;
            penaltyWindowStartedAt = recurring && penaltyWindowStartedAt != null ? penaltyWindowStartedAt : now;
            penaltyLastAt = now;
            suppressSeconds = penaltyDurationSeconds(suppressSeconds, penaltyLevel);
            suppressedReason = "质量惩罚保护 L" + penaltyLevel;
            recoveryObserveUntil = now + suppressSeconds * 1000L
                    + Math.max(0L, settings.penaltyObserveSeconds()) * 1000L;
        } else {
            suppressedReason = "质量抖动保护";
            recoveryObserveUntil = null;
        }
        suppressedUntil = now + suppressSeconds * 1000L;
        return new Decision(0, null, suppressedUntil, suppressedReason, true, true,
                penaltyLevel, penaltyEpisodeCount, penaltyWindowStartedAt, penaltyLastAt,
                recoveryObserveUntil, false);
    }

    private static long penaltyDurationSeconds(long baseSeconds, int level) {
        return switch (Math.max(1, Math.min(MAX_PENALTY_LEVEL, level))) {
            case 1 -> baseSeconds;
            case 2 -> Math.max(baseSeconds * 2L, 3600L);
            case 3 -> Math.max(baseSeconds * 4L, 7200L);
            case 4 -> Math.max(baseSeconds * 12L, 21600L);
            default -> Math.max(baseSeconds * 24L, 43200L);
        };
    }

    public record Snapshot(String previousState, String newState, int flapCount, Long windowStartedAt,
                           Long suppressedUntil, String suppressedReason, int penaltyLevel,
                           int penaltyEpisodeCount, Long penaltyWindowStartedAt, Long penaltyLastAt,
                           Long recoveryObserveUntil, int goodCount, long now) {
        public Snapshot(String previousState, String newState, int flapCount, Long windowStartedAt,
                        Long suppressedUntil, String suppressedReason, long now) {
            this(previousState, newState, flapCount, windowStartedAt, suppressedUntil, suppressedReason,
                    0, 0, null, null, null, 0, now);
        }
    }

    public record Settings(boolean enabled, int windowSeconds, int threshold, int suppressSeconds,
                           boolean penaltyEnabled, int penaltyResetSeconds, int penaltyObserveSeconds,
                           int recoverSamples) {
        public Settings(boolean enabled, int windowSeconds, int threshold, int suppressSeconds) {
            this(enabled, windowSeconds, threshold, suppressSeconds, false, 86400, 900, 3);
        }
    }

    public record Decision(int flapCount, Long windowStartedAt, Long suppressedUntil, String suppressedReason,
                           boolean newlySuppressed, boolean suppressed, int penaltyLevel,
                           int penaltyEpisodeCount, Long penaltyWindowStartedAt, Long penaltyLastAt,
                           Long recoveryObserveUntil, boolean recoveryObserving) {
        public Decision(int flapCount, Long windowStartedAt, Long suppressedUntil, String suppressedReason,
                        boolean newlySuppressed, boolean suppressed) {
            this(flapCount, windowStartedAt, suppressedUntil, suppressedReason, newlySuppressed, suppressed,
                    0, 0, null, null, null, false);
        }
    }
}
