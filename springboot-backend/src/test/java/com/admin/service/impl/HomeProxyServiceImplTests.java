package com.admin.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeProxyServiceImplTests {

    @Test
    void recognizesProbeNodesWithoutIpv6Connectivity() {
        assertTrue(HomeProxyServiceImpl.isIpv6UnsupportedProbeError(
                "dial tcp [2408::1]:23888: connect: network is unreachable"));
        assertTrue(HomeProxyServiceImpl.isIpv6UnsupportedProbeError("connect: no route to host"));
        assertTrue(HomeProxyServiceImpl.isIpv6UnsupportedProbeError("address family not supported by protocol"));
        assertTrue(HomeProxyServiceImpl.isIpv6UnsupportedProbeError("cannot assign requested address"));
    }

    @Test
    void keepsRealEndpointFailuresStrict() {
        assertFalse(HomeProxyServiceImpl.isIpv6UnsupportedProbeError("i/o timeout"));
        assertFalse(HomeProxyServiceImpl.isIpv6UnsupportedProbeError("connection refused"));
        assertFalse(HomeProxyServiceImpl.isIpv6UnsupportedProbeError(null));
    }
}
