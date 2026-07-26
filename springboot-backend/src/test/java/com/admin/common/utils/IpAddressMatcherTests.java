package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpAddressMatcherTests {

    @Test
    void matchesExactAddressesAndCidrs() {
        assertTrue(IpAddressMatcher.isAllowed("198.51.100.20", "198.51.100.20"));
        assertTrue(IpAddressMatcher.isAllowed("203.0.113.42", "198.51.100.20\n203.0.113.0/24"));
        assertFalse(IpAddressMatcher.isAllowed("203.0.114.42", "203.0.113.0/24"));
    }

    @Test
    void supportsIpv6AndBracketedAddresses() {
        assertTrue(IpAddressMatcher.isAllowed("2001:db8::18", "2001:db8::/32"));
        assertTrue(IpAddressMatcher.isAllowed("[2001:db8::18]", "2001:db8::18"));
        assertFalse(IpAddressMatcher.isAllowed("2001:db9::18", "2001:db8::/32"));
    }

    @Test
    void ignoresInvalidEntriesWithoutDiscardingValidRules() {
        assertTrue(IpAddressMatcher.isAllowed("10.0.0.8", "invalid/900, 10.0.0.0/24"));
        assertFalse(IpAddressMatcher.isAllowed("10.0.1.8", "invalid/900, 10.0.0.0/24"));
        assertFalse(IpAddressMatcher.isAllowed("10.0.0.8", ""));
    }
}
