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

    @Test
    void masksExternalProtocolCredentials() {
        String masked = SensitiveDataUtils.maskJsonText(
                "{\"proxyType\":\"socks5\",\"host\":\"proxy.example.com\","
                        + "\"username\":\"friend-user\",\"password\":\"friend-password\"}");
        assertTrue(masked.contains("friend-user"));
        assertFalse(masked.contains("friend-password"));
        assertTrue(masked.contains("\"proxyType\":\"socks5\""));
        assertTrue(masked.contains("\"******\""));
    }
}
