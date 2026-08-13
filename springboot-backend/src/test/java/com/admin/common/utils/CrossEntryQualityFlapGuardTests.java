package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEntryQualityFlapGuardTests {
    private final CrossEntryQualityFlapGuard.Settings defaults =
            new CrossEntryQualityFlapGuard.Settings(true, 900, 3, 1800);
    private final CrossEntryQualityFlapGuard.Settings penaltyDefaults =
            new CrossEntryQualityFlapGuard.Settings(true, 900, 3, 1800, true, 86400, 900, 3);

    @Test
    void onlyCountsTransitionsIntoDegraded() {
        CrossEntryQualityFlapGuard.Decision decision = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("degraded", "degraded", 1, 1_000L, null, null, 2_000L),
                defaults);

        assertEquals(1, decision.flapCount());
        assertFalse(decision.newlySuppressed());
        assertNull(decision.suppressedUntil());
    }

    @Test
    void suppressesAfterRepeatedDegradedTransitionsInsideWindow() {
        CrossEntryQualityFlapGuard.Decision decision = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("healthy", "degraded", 2, 1_000L, null, null, 60_000L),
                defaults);

        assertTrue(decision.newlySuppressed());
        assertTrue(decision.suppressed());
        assertEquals(60_000L + 1_800_000L, decision.suppressedUntil());
        assertEquals("质量抖动保护", decision.suppressedReason());
        assertEquals(0, decision.flapCount());
        assertNull(decision.windowStartedAt());
    }

    @Test
    void resetsWindowWhenTransitionHappensAfterWindow() {
        CrossEntryQualityFlapGuard.Decision decision = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("healthy", "degraded", 2, 1_000L, null, null, 1_000_000L),
                defaults);

        assertFalse(decision.newlySuppressed());
        assertEquals(1, decision.flapCount());
        assertEquals(1_000_000L, decision.windowStartedAt());
    }

    @Test
    void clearsExpiredSuppression() {
        CrossEntryQualityFlapGuard.Decision decision = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("healthy", "healthy", 0, null, 1_000L, "质量抖动保护", 2_000L),
                defaults);

        assertFalse(decision.suppressed());
        assertNull(decision.suppressedUntil());
        assertNull(decision.suppressedReason());
    }

    @Test
    void firstPenaltyUsesBaseDurationAndStartsObservationWindow() {
        CrossEntryQualityFlapGuard.Decision decision = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("healthy", "degraded", 2, 1_000L, null, null,
                        0, 0, null, null, null, 0, 60_000L),
                penaltyDefaults);

        assertTrue(decision.newlySuppressed());
        assertEquals(1, decision.penaltyLevel());
        assertEquals(1, decision.penaltyEpisodeCount());
        assertEquals("质量惩罚保护 L1", decision.suppressedReason());
        assertEquals(60_000L + 1_800_000L, decision.suppressedUntil());
        assertEquals(60_000L + 1_800_000L + 900_000L, decision.recoveryObserveUntil());
    }

    @Test
    void recurrenceInsideMemoryEscalatesPenaltyLevel() {
        CrossEntryQualityFlapGuard.Decision decision = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("healthy", "degraded", 2, 3_600_000L, null, null,
                        1, 1, 60_000L, 60_000L, null, 0, 3_700_000L),
                penaltyDefaults);

        assertTrue(decision.newlySuppressed());
        assertEquals(2, decision.penaltyLevel());
        assertEquals(2, decision.penaltyEpisodeCount());
        assertEquals("质量惩罚保护 L2", decision.suppressedReason());
        assertEquals(3_700_000L + 3_600_000L, decision.suppressedUntil());
    }

    @Test
    void recurrenceAfterMemoryResetsPenaltyLevel() {
        long now = 90_000_000L;
        CrossEntryQualityFlapGuard.Decision decision = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("healthy", "degraded", 2, now - 1_000L, null, null,
                        4, 4, 60_000L, 60_000L, null, 0, now),
                penaltyDefaults);

        assertTrue(decision.newlySuppressed());
        assertEquals(1, decision.penaltyLevel());
        assertEquals(1, decision.penaltyEpisodeCount());
    }

    @Test
    void observationPeriodSuppressesUntilRecoverySamplesAreMet() {
        CrossEntryQualityFlapGuard.Decision observing = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("degraded", "healthy", 0, null, 1_000L, "质量惩罚保护 L1",
                        1, 1, 1_000L, 1_000L, 10_000L, 2, 2_000L),
                penaltyDefaults);

        assertTrue(observing.suppressed());
        assertTrue(observing.recoveryObserving());
        assertEquals("质量惩罚保护 L1", observing.suppressedReason());

        CrossEntryQualityFlapGuard.Decision recovered = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("healthy", "healthy", observing.flapCount(), observing.windowStartedAt(),
                        observing.suppressedUntil(), observing.suppressedReason(), observing.penaltyLevel(),
                        observing.penaltyEpisodeCount(), observing.penaltyWindowStartedAt(), observing.penaltyLastAt(),
                        observing.recoveryObserveUntil(), 3, 3_000L),
                penaltyDefaults);

        assertFalse(recovered.suppressed());
        assertFalse(recovered.recoveryObserving());
        assertNull(recovered.recoveryObserveUntil());
    }

    @Test
    void degradationInsideHardSuppressionDoesNotEscalateAgain() {
        CrossEntryQualityFlapGuard.Decision decision = CrossEntryQualityFlapGuard.evaluate(
                new CrossEntryQualityFlapGuard.Snapshot("healthy", "degraded", 2, 2_000L, 10_000L, "质量惩罚保护 L1",
                        1, 1, 1_000L, 1_000L, 910_000L, 0, 5_000L),
                penaltyDefaults);

        assertFalse(decision.newlySuppressed());
        assertEquals(1, decision.penaltyLevel());
        assertEquals(1, decision.penaltyEpisodeCount());
        assertEquals(10_000L, decision.suppressedUntil());
    }
}
