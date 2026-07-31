package com.admin.service;

import com.admin.entity.PrivateProxy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PrivateProxyServiceTests {

    @Test
    void assignsUniqueDatabaseSafeRuntimeNamesBeforeInsert() {
        PrivateProxy first = new PrivateProxy();
        PrivateProxy second = new PrivateProxy();

        PrivateProxyService.assignRuntimeNames(first, true);
        PrivateProxyService.assignRuntimeNames(second, true);

        assertTrue(first.getServiceName().startsWith("private-proxy-"));
        assertTrue(first.getAdmissionName().startsWith("private-proxy-admission-"));
        assertFalse(first.getServiceName().isBlank());
        assertTrue(first.getServiceName().length() <= 120);
        assertTrue(first.getAdmissionName().length() <= 120);
        assertNotEquals(first.getServiceName(), second.getServiceName());
        assertNotEquals(first.getAdmissionName(), second.getAdmissionName());
    }

    @Test
    void omitsAdmissionNameWhenNoSourceAllowlistExists() {
        PrivateProxy proxy = new PrivateProxy();

        PrivateProxyService.assignRuntimeNames(proxy, false);

        assertFalse(proxy.getServiceName().isBlank());
        assertNull(proxy.getAdmissionName());
    }

    @Test
    void resetsGrantedProxyFlowOnlyAfterMonthlyThreshold() {
        ZoneId zone = ZoneId.systemDefault();
        long before = LocalDate.of(2026, 8, 10).atStartOfDay(zone).plusHours(23).toInstant().toEpochMilli();
        long threshold = LocalDate.of(2026, 8, 11).atStartOfDay(zone).toInstant().toEpochMilli();
        long previousMonth = LocalDate.of(2026, 7, 11).atStartOfDay(zone).toInstant().toEpochMilli();
        long twoMonthsAgo = LocalDate.of(2026, 6, 11).atStartOfDay(zone).toInstant().toEpochMilli();

        assertFalse(PrivateProxyService.shouldResetFlow(11, previousMonth, before));
        assertTrue(PrivateProxyService.shouldResetFlow(11, twoMonthsAgo, before));
        assertTrue(PrivateProxyService.shouldResetFlow(11, previousMonth, threshold));
        assertFalse(PrivateProxyService.shouldResetFlow(11, threshold, threshold + 60_000L));
        assertFalse(PrivateProxyService.shouldResetFlow(0, previousMonth, threshold));
    }

    @Test
    void hidesInternalLimiterPolicyFromOrdinaryUsers() {
        PrivateProxy proxy = new PrivateProxy();
        proxy.setSpeedLimitMbps(100);
        proxy.setSpeedLimitSupported(true);

        PrivateProxyService.hideInternalGrantPolicy(proxy, false);

        assertNull(proxy.getSpeedLimitMbps());
        assertNull(proxy.getSpeedLimitSupported());
    }

    @Test
    void keepsInternalLimiterPolicyForAdministrators() {
        PrivateProxy proxy = new PrivateProxy();
        proxy.setSpeedLimitMbps(100);
        proxy.setSpeedLimitSupported(true);

        PrivateProxyService.hideInternalGrantPolicy(proxy, true);

        assertTrue(proxy.getSpeedLimitMbps() == 100);
        assertTrue(proxy.getSpeedLimitSupported());
    }

    @Test
    void calculatesRuntimeTrafficDeltaAcrossProcessRestart() {
        assertEquals(300L, PrivateProxyService.runtimeTrafficDelta(1_300L, 1_000L));
        assertEquals(200L, PrivateProxyService.runtimeTrafficDelta(200L, 1_000L));
        assertEquals(0L, PrivateProxyService.runtimeTrafficDelta(-1L, 500L));
    }
}
