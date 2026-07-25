package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataUtilsTests {
    @Test
    void masksTerminalCredentialsAndTickets() {
        String masked = SensitiveDataUtils.maskJsonText(
                "{\"password\":\"admin-secret\",\"ticket\":\"one-time-ticket\",\"nodeId\":8}");
        assertFalse(masked.contains("admin-secret"));
        assertFalse(masked.contains("one-time-ticket"));
        assertTrue(masked.contains("******"));
        assertTrue(masked.contains("nodeId"));
    }
}
