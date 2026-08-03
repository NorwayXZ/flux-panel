package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregationWeightPolicyTests {
    private static final long NOW = 2_000_000_000L;

    @Test
    void speedModeGivesMoreWeightToHigherCapacityPath() {
        Map<Long, Integer> weights = AggregationWeightPolicy.calculate("speed", List.of(
                metric(1, 900.0, 60.0, 0.1, 3.0, true, null),
                metric(2, 200.0, 60.0, 0.1, 3.0, true, null)
        ), NOW);
        assertEquals(1000, weights.get(1L));
        assertTrue(weights.get(2L) < 500);
    }

    @Test
    void unhealthyPathIsRemoved() {
        Map<Long, Integer> weights = AggregationWeightPolicy.calculate("balanced", List.of(
                metric(1, 500.0, 50.0, 0.0, 2.0, true, null),
                metric(2, 900.0, 20.0, 0.0, 1.0, false, null)
        ), NOW);
        assertEquals(1000, weights.get(1L));
        assertEquals(0, weights.get(2L));
    }

    @Test
    void stabilityModeStronglyPenalizesSevereLoss() {
        Map<Long, Integer> weights = AggregationWeightPolicy.calculate("stability", List.of(
                metric(1, 500.0, 50.0, 9.0, 20.0, true, null),
                metric(2, 300.0, 70.0, 0.2, 5.0, true, null)
        ), NOW);
        assertEquals(1000, weights.get(2L));
        assertTrue(weights.get(1L) < 500);
    }

    @Test
    void smallChangesKeepPreviousWeight() {
        Map<Long, Integer> weights = AggregationWeightPolicy.calculate("speed", List.of(
                metric(1, 100.0, 50.0, 0.0, 1.0, true, 980),
                metric(2, 50.0, 50.0, 0.0, 1.0, true, 500)
        ), NOW);
        assertEquals(980, weights.get(1L));
        assertEquals(500, weights.get(2L));
    }

    private AggregationWeightPolicy.PathMetric metric(long id, Double bandwidth, Double latency,
                                                       Double loss, Double jitter, boolean healthy,
                                                       Integer previousWeight) {
        return new AggregationWeightPolicy.PathMetric(id, bandwidth, latency, loss, jitter,
                NOW - 1000, healthy, previousWeight);
    }
}
