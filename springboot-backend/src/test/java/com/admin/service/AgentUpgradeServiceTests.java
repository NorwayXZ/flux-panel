package com.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentUpgradeServiceTests {
    private AgentUpgradeService service;

    @BeforeEach
    void setUp() {
        service = new AgentUpgradeService(mock(NodeService.class), mock(JdbcTemplate.class));
    }

    @Test
    void selectsCompatibleUpgradeMode() {
        assertEquals("manual", service.upgradeMode("2.7.0"));
        assertEquals("terminal", service.upgradeMode("2.8.0"));
        assertEquals("terminal", service.upgradeMode("2.12.9"));
        assertEquals("self", service.upgradeMode("2.13.0"));
    }

    @Test
    void bootstrapCommandUsesFixedReleaseAndDetachedHelpers() {
        String taskId = "12345678-1234-1234-1234-123456789012";
        String command = service.bootstrapCommand(taskId);

        assertTrue(command.contains("NorwayXZ/flux-panel/2.13.1/install.sh"));
        assertTrue(command.contains("systemd-run"));
        assertTrue(command.contains("setsid"));
        assertTrue(command.contains(" -U"));
        assertTrue(command.contains("FLUX_UPGRADE_STARTED"));
        assertFalse(command.contains("sudo"));
    }
}
