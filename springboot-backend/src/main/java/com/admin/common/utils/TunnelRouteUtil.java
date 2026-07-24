package com.admin.common.utils;

import com.admin.entity.Tunnel;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class TunnelRouteUtil {

    private TunnelRouteUtil() {
    }

    public static List<Long> parseNodePath(Tunnel tunnel) {
        if (tunnel == null) {
            return Collections.emptyList();
        }

        List<Long> path = parseLongCsv(tunnel.getNodePath());
        if (!path.isEmpty()) {
            return path;
        }

        if (tunnel.getType() != null && tunnel.getType() == 1) {
            return tunnel.getInNodeId() == null
                    ? Collections.emptyList()
                    : Collections.singletonList(tunnel.getInNodeId());
        }

        path = new ArrayList<>();
        if (tunnel.getInNodeId() != null) {
            path.add(tunnel.getInNodeId());
        }
        if (tunnel.getOutNodeId() != null && !tunnel.getOutNodeId().equals(tunnel.getInNodeId())) {
            path.add(tunnel.getOutNodeId());
        }
        return path;
    }

    public static String joinNodePath(List<Long> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return path.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public static List<Integer> parseHopPorts(String hopPorts) {
        return parseIntegerCsv(hopPorts);
    }

    public static String joinHopPorts(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return null;
        }
        return ports.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public static String hostPort(String host, Integer port) {
        if (host == null || port == null) {
            return null;
        }
        return host.contains(":") && !host.startsWith("[")
                ? "[" + host + "]:" + port
                : host + ":" + port;
    }

    private static List<Long> parseLongCsv(String value) {
        if (StringUtils.isBlank(value)) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static List<Integer> parseIntegerCsv(String value) {
        if (StringUtils.isBlank(value)) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }
}
