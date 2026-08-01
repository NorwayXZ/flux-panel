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

    @Test
    void healthProbeConnectionsAreRemovedFromBusinessActivity() {
        assertEquals(4L, SmartEntryService.businessConnectionDelta(7L, 3L));
        assertEquals(0L, SmartEntryService.businessConnectionDelta(2L, 3L));
    }

    @Test
    void dnsFailureDoesNotRewriteRecordsOnEveryHealthCheck() {
        long failedAt = 1_000_000L;
        assertEquals(false, SmartEntryService.shouldWriteDnsRecord(
                false, true, failedAt, false, 60, 60, failedAt + 5_000L));
        assertEquals(true, SmartEntryService.shouldWriteDnsRecord(
                false, true, failedAt, false, 60, 60, failedAt + 60_000L));
    }

    @Test
    void routeAndTtlChangesStillWriteImmediately() {
        long now = 1_000_000L;
        assertEquals(true, SmartEntryService.shouldWriteDnsRecord(
                true, false, now, false, 60, 60, now));
        assertEquals(true, SmartEntryService.shouldWriteDnsRecord(
                false, false, now, false, 60, 600, now));
    }
}
