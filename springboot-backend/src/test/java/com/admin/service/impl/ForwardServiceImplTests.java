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
}
