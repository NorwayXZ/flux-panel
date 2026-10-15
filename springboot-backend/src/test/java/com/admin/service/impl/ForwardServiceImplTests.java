package com.admin.service.impl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForwardServiceImplTests {
    @Test
    void missingBalanceStrategyDefaultsToRound() throws Exception {
        Method method = ForwardServiceImpl.class.getDeclaredMethod("normalizeBalanceStrategy", String.class);
        method.setAccessible(true);

        assertEquals("round", method.invoke(new ForwardServiceImpl(), new Object[]{null}));
    }

    @org.junit.jupiter.api.Test
    void outNodeServiceIsOnlyPushedWhenHealthyTargetsChange() {
        java.util.List<String> targets = java.util.List.of("10.0.0.1:443", "10.0.0.2:443");
        org.junit.jupiter.api.Assertions.assertFalse(ForwardServiceImpl.targetPoolChanged(targets, new java.util.ArrayList<>(targets)));
        org.junit.jupiter.api.Assertions.assertTrue(ForwardServiceImpl.targetPoolChanged(targets, java.util.List.of("10.0.0.1:443")));
        org.junit.jupiter.api.Assertions.assertTrue(ForwardServiceImpl.targetPoolChanged(java.util.List.of(), targets));
        org.junit.jupiter.api.Assertions.assertFalse(ForwardServiceImpl.targetPoolChanged(null, null));
    }

    private static com.admin.common.dto.ForwardTargetHealthDto health(String address, double latency) {
        var item = new com.admin.common.dto.ForwardTargetHealthDto();
        item.setAddress(address);
        item.setLatency(latency);
        return item;
    }

    @Test
    void latencyStrategyPutsTheFastestTargetFirstWithHysteresis() {
        var a = "a.example:443";
        var b = "b.example:443";
        var c = "c.example:443";
        var healthy = java.util.List.of(a, b, c);
        var measured = java.util.List.of(health(a, 80), health(b, 20), health(c, 50));

        assertEquals(java.util.List.of(b, c, a), ForwardServiceImpl.orderTargetsByLatency(healthy, measured, null, 15));
        // The current leader is kept while it is within the gap of the fastest.
        var close = java.util.List.of(health(a, 30), health(b, 20), health(c, 50));
        assertEquals(java.util.List.of(a, b, c), ForwardServiceImpl.orderTargetsByLatency(healthy, close, java.util.List.of(a, b, c), 15));
        // A clearly faster target takes over.
        assertEquals(java.util.List.of(b, c, a), ForwardServiceImpl.orderTargetsByLatency(healthy, measured, java.util.List.of(a, b, c), 15));
        // A leader that is no longer healthy is not kept.
        assertEquals(java.util.List.of(b, c), ForwardServiceImpl.orderTargetsByLatency(java.util.List.of(b, c), measured, java.util.List.of(a, b, c), 100));
    }

    @Test
    void guardOptionsAreValidatedAndTranslatedForTheAgent() {
        org.junit.jupiter.api.Assertions.assertNull(ForwardServiceImpl.validateGuardOptions(2, "TLS, socks", 500));
        org.junit.jupiter.api.Assertions.assertNotNull(ForwardServiceImpl.validateGuardOptions(3, null, null));
        org.junit.jupiter.api.Assertions.assertNotNull(ForwardServiceImpl.validateGuardOptions(null, "ssh", null));
        org.junit.jupiter.api.Assertions.assertNotNull(ForwardServiceImpl.validateGuardOptions(null, null, -1));
        assertEquals("socks,tls", ForwardServiceImpl.normalizeBlockProtocols("tls,SOCKS"));
        org.junit.jupiter.api.Assertions.assertNull(ForwardServiceImpl.normalizeBlockProtocols(""));

        var forward = new com.admin.entity.Forward();
        forward.setProxyProtocol(1);
        forward.setBlockProtocols("http");
        forward.setMaxConnections(0);
        assertEquals(java.util.Map.of("forward.proxyProtocol", 1, "forward.block", "http"), ForwardServiceImpl.guardMetadata(forward));
        assertEquals(java.util.Map.of(), ForwardServiceImpl.guardMetadata(new com.admin.entity.Forward()));
    }
}
