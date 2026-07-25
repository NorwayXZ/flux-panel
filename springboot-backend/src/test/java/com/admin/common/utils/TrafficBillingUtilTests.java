package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TrafficBillingUtilTests {

    @Test
    void singleDirectionOnlyBillsUpload() {
        assertArrayEquals(new long[]{0L, 200L},
                TrafficBillingUtil.calculate(300L, 100L, 1, new BigDecimal("2")));
    }

    @Test
    void bidirectionalBillsBothDirectionsOnce() {
        assertArrayEquals(new long[]{450L, 150L},
                TrafficBillingUtil.calculate(300L, 100L, 2, new BigDecimal("1.5")));
    }
}
