package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEntryQualityFlapGuardTests {
    private final CrossEntryQualityFlapGuard.Settings defaults =
            new CrossEntryQualityFlapGuard.Settings(true, 900, 3, 1800);

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
}
