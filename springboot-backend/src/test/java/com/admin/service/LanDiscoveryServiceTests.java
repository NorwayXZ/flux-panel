package com.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanDiscoveryServiceTests {
    private static final String DEFAULT_ALLOWED = "127.0.0.1/32,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    @Test
    void acceptsPrivateRangeUpToSlash24() {
        assertTrue(LanDiscoveryService.validDiscoveryCidr("192.168.100.0/24", DEFAULT_ALLOWED));
        assertTrue(LanDiscoveryService.validDiscoveryCidr("10.20.30.40/32", DEFAULT_ALLOWED));
    }

    @Test
    void rejectsPublicLargeAndUnauthorizedRanges() {
        assertFalse(LanDiscoveryService.validDiscoveryCidr("8.8.8.0/24", DEFAULT_ALLOWED));
        assertFalse(LanDiscoveryService.validDiscoveryCidr("192.168.0.0/16", DEFAULT_ALLOWED));
        assertFalse(LanDiscoveryService.validDiscoveryCidr("192.168.50.0/24", "192.168.100.0/24"));
        assertFalse(LanDiscoveryService.validDiscoveryCidr("localhost/32", DEFAULT_ALLOWED));
    }
}
