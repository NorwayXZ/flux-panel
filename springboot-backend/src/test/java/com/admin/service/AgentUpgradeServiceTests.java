package com.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;

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
        assertEquals("terminal", service.upgradeMode("2.40.0"));
        assertEquals("terminal", service.upgradeMode("2.41.2"));
        assertEquals("terminal", service.upgradeMode("2.41.4"));
        assertEquals("self", service.upgradeMode("2.42.0"));
    }

    @Test
    void bootstrapCommandUsesFixedReleaseAndDetachedHelpers() {
        String taskId = "12345678-1234-1234-1234-123456789012";
        String command = service.bootstrapCommand(taskId);

        assertTrue(command.contains("NorwayXZ/flux-panel/2.45.3/install.sh"));
        assertTrue(command.contains("ghfast.top"));
        assertTrue(command.contains("--retry 3"));
        assertTrue(command.contains("systemd-run"));
        assertTrue(command.contains("setsid"));
        assertTrue(command.contains(" -U"));
        assertTrue(command.contains("FLUX_AGENT_UPDATE_TASK_ID="));
        assertTrue(command.contains("printf 'FLUX_%s\\n' 'UPGRADE_STARTED'"));
        assertTrue(command.contains("printf 'FLUX_%s\\n' 'UPGRADE_FAILED'"));
        assertFalse(command.contains("FLUX_UPGRADE_STARTED"));
        assertFalse(command.contains("FLUX_UPGRADE_FAILED"));
        assertFalse(command.contains("FLUX_UPGRADE_FINISHED"));
        assertTrue(command.contains("flux-agent-update-12345678-123.log"));
        assertTrue(command.contains(".result"));
        assertFalse(command.contains("sudo"));
    }

    @Test
    void bootstrapCommandIsValidShell() throws IOException, InterruptedException {
        String command = service.bootstrapCommand("12345678-1234-1234-1234-123456789012");
        Process process = new ProcessBuilder("/bin/sh", "-n", "-c", command).start();
        assertEquals(0, process.waitFor());
    }

    @Test
    void manualCommandUsesUpdateModeWithoutNodeSecret() {
        String command = service.manualCommand();

        assertTrue(command.contains("NorwayXZ/flux-panel/2.45.3/install.sh"));
        assertTrue(command.contains("ghfast.top"));
        assertTrue(command.contains("--retry 3"));
        assertTrue(command.contains(" -U"));
        assertTrue(command.contains("id -u"));
        assertTrue(command.contains("sudo"));
        assertFalse(command.contains(" -s "));
        assertFalse(command.contains(" -a "));
    }

    @Test
    void manualCommandIsValidShell() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/sh", "-n", "-c", service.manualCommand()).start();
        assertEquals(0, process.waitFor());
    }
}
