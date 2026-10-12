package com.admin.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViteConfigControllerTests {
    private static final Map<String, String> ALL = Map.of(
            "app_name", "CloudNest",
            "captcha_enabled", "false",
            "ip", "203.0.113.10:6366",
            "panel_public_url", "https://panel.example.com");

    @Test
    void unauthenticatedLookupOnlyExposesLoginPageSettings() {
        assertTrue(ViteConfigController.isPublicConfigName("app_name"));
        assertTrue(ViteConfigController.isPublicConfigName("captcha_enabled"));
        assertTrue(ViteConfigController.isPublicConfigName("captcha_type"));
        assertFalse(ViteConfigController.isPublicConfigName("ip"));
        assertFalse(ViteConfigController.isPublicConfigName("panel_public_url"));
        assertFalse(ViteConfigController.isPublicConfigName(null));
    }

    @Test
    void nonAdminListOmitsTheAgentAddress() {
        Map<String, String> visible = ViteConfigController.visibleConfigs(ALL, false);

        assertEquals(Map.of("app_name", "CloudNest", "captcha_enabled", "false"), visible);
    }

    @Test
    void adminListKeepsEverySetting() {
        assertEquals(ALL, ViteConfigController.visibleConfigs(ALL, true));
    }
}
