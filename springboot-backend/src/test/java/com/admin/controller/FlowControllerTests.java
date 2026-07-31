package com.admin.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControllerTests {
    @Test
    void acceptsLegacyForwardServiceName() {
        FlowController.ForwardServiceName service = FlowController.parseForwardServiceName("12_1_0");

        assertEquals("12", service.forwardId());
        assertEquals("1", service.userId());
        assertEquals("0", service.userTunnelId());
        assertNull(service.transport());
        assertTrue(service.reportsConnections());
    }

    @Test
    void acceptsTcpForwardServiceName() {
        FlowController.ForwardServiceName service = FlowController.parseForwardServiceName("12_1_0_tcp");

        assertEquals("12", service.forwardId());
        assertEquals("tcp", service.transport());
        assertTrue(service.reportsConnections());
    }

    @Test
    void acceptsUdpForwardServiceName() {
        FlowController.ForwardServiceName service = FlowController.parseForwardServiceName("12_1_0_udp");

        assertEquals("12", service.forwardId());
        assertEquals("udp", service.transport());
        assertFalse(service.reportsConnections());
    }

    @Test
    void rejectsUnrelatedAndMalformedServiceNames() {
        assertNull(FlowController.parseForwardServiceName("publish_12_rtcp"));
        assertNull(FlowController.parseForwardServiceName("private-proxy-12"));
        assertNull(FlowController.parseForwardServiceName("12_1_0_quic"));
        assertNull(FlowController.parseForwardServiceName("12_1"));
        assertNull(FlowController.parseForwardServiceName(null));
    }
}
