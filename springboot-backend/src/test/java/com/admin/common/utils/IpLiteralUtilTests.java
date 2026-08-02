package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpLiteralUtilTests {
    @Test
    void acceptsIpv4AndIpv6Literals() {
        assertEquals("10.20.0.5", IpLiteralUtil.normalize("10.20.0.5"));
        assertTrue(IpLiteralUtil.isLiteral("2408:8256:3500:911:548b:e7f7:d5db:73de"));
        assertTrue(IpLiteralUtil.isLiteral("fd88::2"));
    }

    @Test
    void rejectsDomainsAndAmbiguousAddresses() {
        assertFalse(IpLiteralUtil.isLiteral("node.internal.example"));
        assertFalse(IpLiteralUtil.isLiteral("127.1"));
        assertFalse(IpLiteralUtil.isLiteral("10.0.0.999"));
        assertFalse(IpLiteralUtil.isLiteral("fe80::1%eth0"));
    }
}
