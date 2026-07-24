package com.admin.common.utils;

import com.admin.entity.Node;

import java.util.Locale;

/**
 * Maps duplicate node records for the same server into one physical port pool.
 */
public final class PortNamespaceUtil {

    private PortNamespaceUtil() {
    }

    public static String fromNode(Node node) {
        if (node == null) {
            return "node:unknown";
        }
        return fromAddress(node.getId(), node.getServerIp());
    }

    public static String fromAddress(Long nodeId, String serverIp) {
        if (serverIp == null || serverIp.trim().isEmpty()) {
            return "node:" + nodeId;
        }

        String normalized = serverIp.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return "server:" + normalized;
    }
}
