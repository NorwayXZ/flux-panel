package com.admin.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmartEntryServiceTests {
    private Map<String, Object> route(String carrier, long id, String state) {
        return new HashMap<>(Map.of("carrier", carrier, "forwardId", id, "entryAddress", "192.0.2." + id, "status", state));
    }

    @Test
    void neverPublishesAnUnknownOrFailedEntry() {
        var primary = route("telecom", 1, "unknown");
        var backup = route("default", 2, "unhealthy");
        assertNull(SmartEntryService.chooseRoute(primary, List.of(primary, backup), true, 0, 1000));
    }

    @Test
    void obeysExplicitCarrierBackupOrder() {
        var primary = route("telecom", 1, "unhealthy");
        var standard = route("default", 2, "healthy");
        var preferred = route("mobile", 3, "healthy");
        primary.put("fallbackCarriers", List.of("mobile", "default"));
        assertSame(preferred, SmartEntryService.chooseRoute(primary, List.of(primary, standard, preferred), true, 0, 1000));
        primary.put("fallbackCarriers", List.of());
        assertNull(SmartEntryService.chooseRoute(primary, List.of(primary, standard, preferred), true, 0, 1000));
    }

    @Test
    void cooldownKeepsHealthyBackupButNeverPinsFailedBackup() {
        var primary = route("telecom", 1, "healthy");
        var backup = route("default", 2, "healthy");
        primary.put("currentForwardId", 2L);
        primary.put("currentAddress", "192.0.2.2");
        primary.put("lastSwitchedAt", 1000L);
        assertSame(backup, SmartEntryService.chooseRoute(primary, List.of(primary, backup), true, 60000, 2000));
        backup.put("status", "unhealthy");
        assertSame(primary, SmartEntryService.chooseRoute(primary, List.of(primary, backup), true, 60000, 2000));
    }

    @Test
    void pausedAutomaticSchedulingDoesNotChangeExistingDnsTarget() {
        var primary = route("telecom", 1, "healthy");
        var backup = route("default", 2, "unhealthy");
        primary.put("currentForwardId", 2L);
        primary.put("currentAddress", "192.0.2.2");
        assertSame(backup, SmartEntryService.chooseRoute(primary, List.of(primary, backup), false, 0, 1000));
    }

    @Test
    void missingFormerTargetCanOnlyBeReplacedByConfirmedHealthyEntry() {
        var primary = route("default", 1, "unknown");
        primary.put("currentForwardId", 99L);
        primary.put("currentAddress", "192.0.2.99");
        assertNull(SmartEntryService.chooseRoute(primary, List.of(primary), true, 0, 1000));
        primary.put("status", "healthy");
        assertSame(primary, SmartEntryService.chooseRoute(primary, List.of(primary), true, 0, 1000));
    }
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
    void activityStateExplainsZeroCurrentConnectionsWithRecentTraffic() {
        assertEquals("connected", SmartEntryService.activityState(true, true, true, 2));
        assertEquals("active_without_tcp_current", SmartEntryService.activityState(true, true, true, 0));
        assertEquals("idle", SmartEntryService.activityState(true, true, false, 0));
        assertEquals("stale", SmartEntryService.activityState(true, false, true, 0));
        assertEquals("waiting", SmartEntryService.activityState(false, false, false, 0));
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
    void dnsFailureRetryWindowUsesLastAttemptRatherThanLastSuccessfulVerification() {
        long lastAttempt = 1_000_000L;
        assertEquals(false, SmartEntryService.shouldWriteDnsRecord(
                false, true, lastAttempt, false, 60, 60, lastAttempt + 5_000L));
        assertEquals(true, SmartEntryService.shouldWriteDnsRecord(
                false, true, lastAttempt, false, 60, 60, lastAttempt + 60_000L));
    }

    @Test
    void aFailedDnsWriteKeepsTheSuccessfulVerificationTimestampUnchangedForRetry() {
        long lastAttempt = 1_000_000L;
        long lastVerified = 500_000L;
        assertEquals(false, SmartEntryService.shouldWriteDnsRecord(
                false, true, lastAttempt, false, 60, 60, lastAttempt + 59_999L));
        assertEquals(true, SmartEntryService.shouldWriteDnsRecord(
                false, true, lastAttempt, false, 60, 60, lastAttempt + 60_000L));
        assertTrue(lastVerified < lastAttempt, "Failed writes must not replace the successful verification baseline");
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
