package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorInstallCommandUtilTests {
    @Test
    void defaultsUnknownPlatformsToLinux() {
        assertEquals("linux", ConnectorInstallCommandUtil.normalizePlatform(null));
        assertEquals("linux", ConnectorInstallCommandUtil.normalizePlatform("unsupported"));
        assertEquals("macos", ConnectorInstallCommandUtil.normalizePlatform(" macOS "));
    }

    @Test
    void buildsPinnedLinuxCommand() {
        String command = ConnectorInstallCommandUtil.build("linux", "panel.example:6366", "secret");
        assertTrue(command.contains("/2.34.0/install.sh"));
        assertTrue(command.contains("-r connector"));
        assertTrue(command.contains("-a 'panel.example:6366'"));
    }

    @Test
    void buildsWindowsPowerShellCommandAndEscapesApostrophes() {
        String command = ConnectorInstallCommandUtil.build("windows", "panel.example:6366", "sec'ret");
        assertTrue(command.startsWith("powershell.exe"));
        assertTrue(command.contains("install-connector.ps1"));
        assertTrue(command.contains("-Secret 'sec''ret'"));
    }

    @Test
    void buildsMacOsLaunchDaemonInstallerCommand() {
        String command = ConnectorInstallCommandUtil.build("macos", "panel.example:6366", "secret");
        assertTrue(command.contains("install-connector-macos.sh"));
        assertTrue(command.contains("sudo /tmp/flux-connector-install.sh"));
    }

    @Test
    void buildsPlatformSpecificUninstallCommands() {
        assertTrue(ConnectorInstallCommandUtil.build("linux", "panel", "secret", true)
                .contains("-r connector -u"));
        assertTrue(ConnectorInstallCommandUtil.build("windows", "panel", "secret", true)
                .contains("-Uninstall"));
        assertTrue(ConnectorInstallCommandUtil.build("macos", "panel", "secret", true)
                .contains("flux-connector-install.sh -u"));
    }
}
