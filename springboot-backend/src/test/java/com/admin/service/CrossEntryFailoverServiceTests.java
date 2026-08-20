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
    void connectionCounterEstablishesBaselineAndCountsWithinOneServiceInstance() {
        var baseline = CrossEntryFailoverService.connectionCounterUpdate(0, 0, 120, 1000, false);
        var next = CrossEntryFailoverService.connectionCounterUpdate(120, 1000, 128, 1000, true);

        assertEquals(0, baseline.delta());
        assertEquals(120, baseline.baseline());
        assertEquals(8, next.delta());
        assertTrue(next.accepted());
    }

    @Test
    void newerServiceInstanceRebasesAndRejectsLateReportsFromOldInstance() {
        var restarted = CrossEntryFailoverService.connectionCounterUpdate(128, 1000, 3, 2000, true);
        var stale = CrossEntryFailoverService.connectionCounterUpdate(3, 2000, 129, 1000, true);

        assertEquals(0, restarted.delta());
        assertEquals(3, restarted.baseline());
        assertEquals(2000, restarted.generation());
        assertEquals(false, stale.accepted());
    }

    @Test
    void legacyAgentCounterRegressionDoesNotDuplicateItsHistory() {
        var regression = CrossEntryFailoverService.connectionCounterUpdate(344_669, 0, 344_665, 0, true);

        assertEquals(0, regression.delta());
        assertEquals(344_669, regression.baseline());
        assertTrue(regression.accepted());
    }

    @Test
    void failoverHealthProbesAreRemovedFromDisplayedBusinessConnections() {
        assertEquals(2, CrossEntryFailoverService.businessConnectionDelta(7, 5));
        assertEquals(0, CrossEntryFailoverService.businessConnectionDelta(3, 5));
    }

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

    @Test
    void saveFailureMapsManagedForwardErrorsToConcreteFields() {
        var fields = CrossEntryFailoverService.saveFieldErrors(
                "managed_members",
                "节点 香港入口 创建托管转发失败（公共端口 443，落地 203.0.113.10:443）：Agent 无响应");

        assertTrue(fields.containsKey("managedEntryNodeIds"));
        assertTrue(fields.containsKey("managedPublicPort"));
        assertTrue(fields.containsKey("managedTargetAddress"));
    }

    @Test
    void saveFailureMapsDnsAndForwardErrorsSeparately() {
        assertTrue(CrossEntryFailoverService.saveFieldErrors(
                "dns_configuration", "请选择已在 DNS 与域名中登记的 Cloudflare Zone")
                .containsKey("dnsZoneId"));
        assertTrue(CrossEntryFailoverService.saveFieldErrors(
                "forward_validation", "候选转发必须来自不同入口节点")
                .containsKey("memberForwardIds"));
    }
}
