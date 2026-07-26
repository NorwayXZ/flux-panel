package com.admin.common.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

public final class ConnectorInstallCommandUtil {
    public static final String RELEASE = "2.13.0";
    private static final String RAW_BASE = "https://raw.githubusercontent.com/NorwayXZ/flux-panel/" + RELEASE;
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of("linux", "windows", "macos");

    private ConnectorInstallCommandUtil() {
    }

    public static String normalizePlatform(String platform) {
        String normalized = StringUtils.defaultIfBlank(platform, "linux").trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_PLATFORMS.contains(normalized) ? normalized : "linux";
    }

    public static String build(String platform, String panelAddress, String secret) {
        return build(platform, panelAddress, secret, false);
    }

    public static String build(String platform, String panelAddress, String secret, boolean uninstall) {
        return switch (normalizePlatform(platform)) {
            case "windows" -> buildWindows(panelAddress, secret, uninstall);
            case "macos" -> buildMacOs(panelAddress, secret, uninstall);
            default -> buildLinux(panelAddress, secret, uninstall);
        };
    }

    private static String buildLinux(String panelAddress, String secret, boolean uninstall) {
        String prefix = "curl -fsSL " + RAW_BASE + "/install.sh -o /tmp/flux-connector-install.sh";
        if (uninstall) return prefix + " && sh /tmp/flux-connector-install.sh -r connector -u";
        return prefix + " && sh /tmp/flux-connector-install.sh -a "
                + shellQuote(panelAddress) + " -s " + shellQuote(secret) + " -r connector";
    }

    private static String buildMacOs(String panelAddress, String secret, boolean uninstall) {
        String prefix = "curl -fsSL " + RAW_BASE + "/install-connector-macos.sh -o /tmp/flux-connector-install.sh"
                + " && chmod +x /tmp/flux-connector-install.sh";
        if (uninstall) return prefix + " && sudo /tmp/flux-connector-install.sh -u";
        return prefix + " && sudo /tmp/flux-connector-install.sh -a " + shellQuote(panelAddress)
                + " -s " + shellQuote(secret);
    }

    private static String buildWindows(String panelAddress, String secret, boolean uninstall) {
        String scriptUrl = RAW_BASE + "/install-connector.ps1";
        String prefix = "powershell.exe -NoProfile -ExecutionPolicy Bypass -Command \""
                + "Invoke-WebRequest -UseBasicParsing -Uri '" + powershellQuote(scriptUrl)
                + "' -OutFile '$env:TEMP\\flux-connector-install.ps1'; ";
        if (uninstall) return prefix + "& '$env:TEMP\\flux-connector-install.ps1' -Uninstall\"";
        return prefix + "& '$env:TEMP\\flux-connector-install.ps1' -ServerAddr '" + powershellQuote(panelAddress)
                + "' -Secret '" + powershellQuote(secret) + "'\"";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String powershellQuote(String value) {
        return value.replace("'", "''");
    }
}
