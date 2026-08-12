package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
