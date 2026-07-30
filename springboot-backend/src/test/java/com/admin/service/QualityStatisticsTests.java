package com.admin.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityStatisticsTests {
    @Test
    void interpolatesPercentiles() {
        assertEquals(25, QualityStatistics.percentile(List.of(10d, 20d, 30d, 40d), .50), .001);
        assertEquals(38.5, QualityStatistics.percentile(List.of(10d, 20d, 30d, 40d), .95), .001);
        assertEquals(39.7, QualityStatistics.percentile(List.of(10d, 20d, 30d, 40d), .99), .001);
    }

    @Test
    void summarizesSuccessfulSamplesOnlyForLatency() {
        Map<String, Object> result = QualityStatistics.summary(List.of(
                Map.of("success", true, "tcpMs", 10d, "tlsMs", 20d, "ttfbMs", 30d, "totalMs", 60d),
                Map.of("success", true, "tcpMs", 20d, "tlsMs", 30d, "ttfbMs", 40d, "totalMs", 90d),
                Map.of("success", false, "tcpMs", 1000d, "totalMs", 1000d)
        ));
        assertEquals(2L, result.get("successCount"));
        assertEquals(15d, (Double) result.get("tcpAvgMs"), .001);
        assertEquals(75d, (Double) result.get("p50Ms"), .001);
    }

    @Test
    void validatesTargetsWithoutAcceptingMalformedIpLiterals() {
        assertTrue(QualityLabService.validHost("example.com"));
        assertTrue(QualityLabService.validHost("1.1.1.1"));
        assertTrue(QualityLabService.validHost("2001:db8::1"));
        assertFalse(QualityLabService.validHost("1.1.1.999"));
        assertFalse(QualityLabService.validHost("bad host.example"));
        assertFalse(QualityLabService.validHost("2001:db8:::1"));
    }
}
