package com.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitoringServiceTests {

    @Test
    void boundsLongDetailBeforeStorage() {
        String detail = "证书申请失败：".repeat(120);

        String bounded = MonitoringService.boundedDetailForStorage(detail);

        assertTrue(bounded.length() <= MonitoringService.DETAIL_MAX_LENGTH);
    }

    @Test
    void keepsShortDetailReadable() {
        String detail = "动态 DNS 解析失败";

        assertEquals(detail, MonitoringService.boundedDetailForStorage(detail));
    }

    @Test
    void normalizesNullDetailToEmptyString() {
        assertEquals("", MonitoringService.boundedDetailForStorage(null));
    }
}
