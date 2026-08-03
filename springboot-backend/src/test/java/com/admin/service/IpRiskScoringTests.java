package com.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IpRiskScoringTests {
    @Test void doesNotInventScoreWithoutRiskProvider() {
        IpRiskScoring.Result result = IpRiskScoring.calculate(null, null, 0);
        assertNull(result.score()); assertEquals("unknown", result.level()); assertEquals("none", result.confidence());
    }

    @Test void combinesProvidersAndRaisesListedAddress() {
        IpRiskScoring.Result result = IpRiskScoring.calculate(20, 40, 3);
        assertEquals(75, result.score()); assertEquals("high", result.level()); assertEquals("high", result.confidence());
    }

    @Test void singleProviderIsMediumConfidence() {
        IpRiskScoring.Result result = IpRiskScoring.calculate(35, null, 0);
        assertEquals(35, result.score()); assertEquals("low", result.level()); assertEquals("medium", result.confidence());
    }
}
