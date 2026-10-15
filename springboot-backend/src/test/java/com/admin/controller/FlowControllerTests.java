package com.admin.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControllerTests {
    @Test void sharedQuotaPauseUsesBusinessRevocationInsteadOfPausingTheSharedExit() {
        var controller=new FlowController();
        var forwards=org.mockito.Mockito.mock(com.admin.service.ForwardService.class);
        var tunnels=org.mockito.Mockito.mock(com.admin.service.TunnelService.class);
        var cnix=org.mockito.Mockito.mock(com.admin.service.CnixSharedRelayService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"forwardService",forwards);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"tunnelService",tunnels);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"cnixSharedRelayService",cnix);
        var tunnel=new com.admin.entity.Tunnel();tunnel.setId(7L);tunnel.setType(2);
        var business=new com.admin.entity.Forward();business.setId(17L);business.setTunnelId(7);business.setStatus(1);
        org.mockito.Mockito.when(tunnels.getById(7)).thenReturn(tunnel);
        org.mockito.Mockito.when(cnix.isShared(tunnel)).thenReturn(true);
        org.mockito.Mockito.when(forwards.pauseManagedForward(17L)).thenReturn(com.admin.common.lang.R.ok());
        controller.pauseService(java.util.List.of(business),"17_3_7");
        org.mockito.Mockito.verify(forwards).pauseManagedForward(17L);
        org.mockito.Mockito.verify(forwards,org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.any());
    }
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
        // Shared listener totals must never be attributed to every business.
        assertNull(FlowController.parseForwardServiceName("cnixshared-7"));
        assertNull(FlowController.parseForwardServiceName("publish_12_rtcp"));
        assertNull(FlowController.parseForwardServiceName("private-proxy-12"));
        assertNull(FlowController.parseForwardServiceName("12_1_0_quic"));
        assertNull(FlowController.parseForwardServiceName("12_1"));
        assertNull(FlowController.parseForwardServiceName(null));
    }
}
