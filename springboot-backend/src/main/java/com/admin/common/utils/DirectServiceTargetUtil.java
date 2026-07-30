package com.admin.common.utils;

import com.admin.entity.Node;
import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

public final class DirectServiceTargetUtil {
    private DirectServiceTargetUtil() {
    }

    public static String resolve(Node entryNode, Node backendNode, String listenerHost, int port) {
        if (entryNode == null || backendNode == null) {
            throw new IllegalArgumentException("域名入口或后端节点不存在");
        }
        String host = stripBrackets(StringUtils.defaultIfBlank(listenerHost, "127.0.0.1"));
        boolean sameNamespace = Objects.equals(PortNamespaceUtil.fromNode(entryNode), PortNamespaceUtil.fromNode(backendNode));
        if (sameNamespace) {
            if ("::".equals(host)) {
                host = "::1";
            } else if (List.of("0.0.0.0", "*").contains(host)) {
                host = "127.0.0.1";
            }
        } else {
            if (isLoopback(host)) {
                throw new IllegalArgumentException("该服务仅监听本机地址，HTTPS 入口必须选择服务所在节点");
            }
            host = StringUtils.firstNonBlank(backendNode.getServerIp(), backendNode.getIp());
            if (StringUtils.isBlank(host)) throw new IllegalArgumentException("后端节点缺少可访问地址");
            host = stripBrackets(host);
        }
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    public static boolean validListenerHost(String value) {
        String host = stripBrackets(StringUtils.trimToEmpty(value));
        if (List.of("0.0.0.0", "::", "*").contains(host)) return true;
        try {
            return !host.isEmpty() && InetAddress.getByName(host).getHostAddress() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLoopback(String value) {
        try {
            return InetAddress.getByName(value).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String stripBrackets(String value) {
        String result = StringUtils.trimToEmpty(value);
        if (result.startsWith("[") && result.endsWith("]")) return result.substring(1, result.length() - 1);
        return result;
    }
}
