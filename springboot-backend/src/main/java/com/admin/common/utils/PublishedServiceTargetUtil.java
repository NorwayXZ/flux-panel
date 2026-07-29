package com.admin.common.utils;

import com.admin.entity.Node;
import com.admin.entity.PortPool;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

public final class PublishedServiceTargetUtil {
    private PublishedServiceTargetUtil() {
    }

    public static String resolve(Node entryNode, Node mappingNode, PortPool pool, int port) {
        if (entryNode == null || mappingNode == null || pool == null) {
            throw new IllegalArgumentException("域名入口或后端映射节点不存在");
        }
        String host;
        if (Objects.equals(PortNamespaceUtil.fromNode(entryNode), PortNamespaceUtil.fromNode(mappingNode))) {
            String bindIp = StringUtils.trimToEmpty(pool.getBindIp());
            host = StringUtils.isBlank(bindIp) || List.of("0.0.0.0", "::", "[::]").contains(bindIp)
                    ? "127.0.0.1" : stripBrackets(bindIp);
        } else {
            host = StringUtils.firstNonBlank(pool.getPublicHost(), mappingNode.getServerIp(), mappingNode.getIp());
            if (StringUtils.isBlank(host)) {
                throw new IllegalArgumentException("跨节点域名入口需要后端映射的公网地址");
            }
            host = stripBrackets(host);
        }
        return hostPort(host, port);
    }

    private static String stripBrackets(String value) {
        String result = StringUtils.trimToEmpty(value);
        if (result.startsWith("[") && result.endsWith("]")) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static String hostPort(String host, int port) {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }
}
