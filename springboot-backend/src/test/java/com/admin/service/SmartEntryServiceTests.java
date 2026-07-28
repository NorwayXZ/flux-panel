package com.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmartEntryServiceTests {
    @Test
    void firstReportEstablishesBaselineWithoutCountingHistoricalConnections() {
        assertEquals(0L, SmartEntryService.connectionDelta(0L, 42L, false));
    }

    @Test
    void laterReportsOnlyCountNewConnections() {
        assertEquals(5L, SmartEntryService.connectionDelta(42L, 47L, true));
    }

    @Test
    void agentRestartCountsConnectionsFromTheNewCounter() {
        assertEquals(3L, SmartEntryService.connectionDelta(47L, 3L, true));
    }
}
