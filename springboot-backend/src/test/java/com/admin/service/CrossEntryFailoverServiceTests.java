package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import com.admin.common.utils.CrossEntryQualityEvaluator;
import com.admin.common.utils.CrossEntryQualityFlapGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEntryFailoverServiceTests {
    @Test
    void roundedMetricTreatsMissingOrInvalidAgentMetricsAsAbsent() {
        JSONObject data = new JSONObject();

        assertNull(CrossEntryFailoverService.roundedMetric(data, "p95Time", 1));

        data.put("p95Time", "");
        assertNull(CrossEntryFailoverService.roundedMetric(data, "p95Time", 1));

        data.put("p95Time", -1);
        assertNull(CrossEntryFailoverService.roundedMetric(data, "p95Time", 1));
    }

    @Test
    void roundedMetricKeepsMinimumForValidAgentMetrics() {
        JSONObject data = new JSONObject();
        data.put("averageTime", 0.2);
        data.put("jitter", "3.6");

        assertEquals(1, CrossEntryFailoverService.roundedMetric(data, "averageTime", 1));
        assertEquals(4, CrossEntryFailoverService.roundedMetric(data, "jitter", 0));
    }

    @Test
    void qualityFaultStatsOnlyCountNewDegradationEpisodes() {
        CrossEntryQualityEvaluator.Decision decision = new CrossEntryQualityEvaluator.Decision(
                "degraded", 20, 1, 0, true, false, true, true, false, false, false);
        CrossEntryQualityFlapGuard.Decision flapDecision = new CrossEntryQualityFlapGuard.Decision(
                1, 1000L, null, null, true, true);

        CrossEntryFailoverService.FaultStatsUpdate first = CrossEntryFailoverService.qualityFaultStatsUpdate(
                decision, true, null, "healthy", false, flapDecision, 100L);
        CrossEntryFailoverService.FaultStatsUpdate repeat = CrossEntryFailoverService.qualityFaultStatsUpdate(
                decision, true, null, "degraded", false, flapDecision, 100L);

        assertEquals(1, first.episodeDelta());
        assertEquals(1, first.latencyDelta());
        assertEquals(1, first.lossDelta());
        assertTrue(first.at() != null);
        assertEquals(0, repeat.latencyDelta());
        assertEquals(0, repeat.lossDelta());
    }

    @Test
    void qualityConnectFailureDoesNotDuplicateHealthFaultEpisode() {
        CrossEntryQualityEvaluator.Decision decision = new CrossEntryQualityEvaluator.Decision(
                "degraded", 20, 1, 0, true, false, false, false, false, false, false);

        CrossEntryFailoverService.FaultStatsUpdate stats = CrossEntryFailoverService.qualityFaultStatsUpdate(
                decision, false, "TCP 探测失败", "healthy", true,
                new CrossEntryQualityFlapGuard.Decision(0, null, null, null, false, false), 100L);

        assertEquals(0, stats.episodeDelta());
        assertEquals(0, stats.connectDelta());
    }
}
