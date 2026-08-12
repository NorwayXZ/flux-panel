package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEntryQualityEvaluatorTests {
    private final CrossEntryQualityEvaluator.Settings defaults =
            new CrossEntryQualityEvaluator.Settings(100, 60, 3.0, 1.8, 3, 3, 30.0, false, 20);

    @Test
    void learnedHighLatencyBaselineDoesNotTripDefaultAbsoluteThreshold() {
        CrossEntryQualityEvaluator.Decision decision = CrossEntryQualityEvaluator.evaluate(
                new CrossEntryQualityEvaluator.Snapshot("healthy", 160, 170, 0.0, true, 0, 5),
                defaults);

        assertFalse(decision.bad());
        assertFalse(decision.latencyBad());
        assertEquals("healthy", decision.state());
    }

    @Test
    void lowLatencyRouteCanStillDegradeByItsOwnBaseline() {
        CrossEntryQualityEvaluator.Decision decision = CrossEntryQualityEvaluator.evaluate(
                new CrossEntryQualityEvaluator.Snapshot("healthy", 10, 35, 0.0, true, 2, 0),
                defaults);

        assertTrue(decision.bad());
        assertTrue(decision.latencyBad());
        assertEquals("degraded", decision.state());
    }

    @Test
    void absoluteThresholdOnlyAppliesWhenAboveLearnedBaseline() {
        CrossEntryQualityEvaluator.Decision decision = CrossEntryQualityEvaluator.evaluate(
                new CrossEntryQualityEvaluator.Snapshot("healthy", 80, 110, 0.0, true, 2, 0),
                defaults);

        assertTrue(decision.bad());
        assertTrue(decision.latencyBad());
        assertEquals("degraded", decision.state());
    }

    @Test
    void firstSuccessfulProbeLearnsBaselineInsteadOfDegrading() {
        CrossEntryQualityEvaluator.Decision decision = CrossEntryQualityEvaluator.evaluate(
                new CrossEntryQualityEvaluator.Snapshot("unknown", null, 170, 0.0, true, 0, 0),
                defaults);

        assertFalse(decision.bad());
        assertEquals(170, decision.baselineMs());
        assertEquals("warming", decision.state());
    }

    @Test
    void fixedTargetCanDegradeLowLatencyGroupBeforeBaselineMultiplier() {
        CrossEntryQualityEvaluator.Settings settings =
                new CrossEntryQualityEvaluator.Settings(100, 60, 3.0, 1.8, 3, 3, 30.0, true, 20);
        CrossEntryQualityEvaluator.Decision decision = CrossEntryQualityEvaluator.evaluate(
                new CrossEntryQualityEvaluator.Snapshot("healthy", 15, 25, 0.0, true, 2, 0),
                settings);

        assertTrue(decision.bad());
        assertTrue(decision.fixedLatencyBad());
        assertEquals("degraded", decision.state());
    }

    @Test
    void fixedTargetBoundaryAllowsTargetLatency() {
        CrossEntryQualityEvaluator.Settings settings =
                new CrossEntryQualityEvaluator.Settings(100, 60, 3.0, 1.8, 3, 3, 30.0, true, 20);
        CrossEntryQualityEvaluator.Decision decision = CrossEntryQualityEvaluator.evaluate(
                new CrossEntryQualityEvaluator.Snapshot("healthy", 15, 20, 0.0, true, 0, 5),
                settings);

        assertFalse(decision.bad());
        assertFalse(decision.fixedLatencyBad());
        assertEquals("healthy", decision.state());
    }
}
