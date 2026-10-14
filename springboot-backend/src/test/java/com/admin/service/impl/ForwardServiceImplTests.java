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
}
