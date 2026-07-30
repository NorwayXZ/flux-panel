package com.admin.common.utils;

import com.admin.common.dto.SniRouteTargetDto;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.net.IDN;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public final class SniDomainUtil {
    private SniDomainUtil() {
    }

    public static String normalizeDomain(String value) {
        String input = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
        while (input.endsWith(".")) input = input.substring(0, input.length() - 1);
        if (input.isEmpty() || input.contains("*") || input.contains(":") || input.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
            throw new IllegalArgumentException("请输入完整域名，第一阶段暂不支持通配符或 IP 地址");
        }
        final String domain;
        try {
            domain = IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("域名格式不正确");
        }
        String[] labels = domain.split("\\.");
        if (domain.length() > 253 || labels.length < 2) throw new IllegalArgumentException("请输入完整的公网域名");
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                throw new IllegalArgumentException("域名格式不正确");
            }
        }
        return domain;
    }

    public static String normalizePathPrefix(String value) {
        String path = StringUtils.defaultIfBlank(value, "/").trim();
        if (!path.startsWith("/")) path = "/" + path;
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.length() > 255 || path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf('\\') >= 0
                || path.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("匹配路径必须以 / 开头，且不能包含空格、查询参数或片段");
        }
        return path;
    }

    public static String normalizeBackendPath(String value) {
        return normalizePathPrefix(value);
    }

    public static JSONObject buildIngressService(String serviceName, String bindIp, int listenPort,
                                                  List<SniRouteTargetDto> targets) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", (StringUtils.isBlank(bindIp) ? "" : bindIp.trim()) + ":" + listenPort);

        JSONObject handlerMetadata = new JSONObject();
        handlerMetadata.put("sniffing", true);
        handlerMetadata.put("sniffing.timeout", "10s");
        handlerMetadata.put("readTimeout", "15s");
        JSONObject handler = new JSONObject();
        handler.put("type", "forward");
        handler.put("metadata", handlerMetadata);
        service.put("handler", handler);

        JSONObject listener = new JSONObject();
        listener.put("type", "tcp");
        service.put("listener", listener);

        JSONArray nodes = new JSONArray();
        for (SniRouteTargetDto target : targets) {
            JSONObject filter = new JSONObject();
            filter.put("protocol", "tls");
            filter.put("host", target.getDomain());
            JSONObject node = new JSONObject();
            node.put("name", "domain_route_" + target.getRouteId());
            node.put("addr", target.getTargetAddress());
            node.put("filter", filter);
            nodes.add(node);
        }
        JSONObject selector = new JSONObject();
        selector.put("strategy", "fifo");
        JSONObject forwarder = new JSONObject();
        forwarder.put("selector", selector);
        forwarder.put("nodes", nodes);
        service.put("forwarder", forwarder);
        return service;
    }

    public static JSONObject buildManagedHttpsService(String serviceName, String bindIp, int listenPort,
                                                       List<SniRouteTargetDto> targets,
                                                       List<Map<String, Object>> certificates) {
        JSONObject service = buildIngressService(serviceName, bindIp, listenPort, targets);
        JSONObject listener = new JSONObject();
        listener.put("type", "tls");
        JSONObject tls = new JSONObject();
        tls.put("certificates", certificates);
        JSONObject options = new JSONObject();
        options.put("minVersion", "VersionTLS12");
        options.put("alpn", List.of("http/1.1"));
        tls.put("options", options);
        listener.put("tls", tls);
        service.put("listener", listener);
        JSONArray nodes = service.getJSONObject("forwarder").getJSONArray("nodes");
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            node.getJSONObject("filter").put("protocol", "http");
            String pathPrefix = targets.get(i).getPathPrefix();
            if (StringUtils.isNotBlank(pathPrefix)) {
                node.getJSONObject("filter").put("path", pathPrefix);
            }
            String backendPath = normalizeBackendPath(targets.get(i).getBackendPath());
            String externalPath = normalizePathPrefix(pathPrefix);
            JSONObject http = new JSONObject();
            JSONObject requestHeaders = new JSONObject();
            requestHeaders.put("X-Forwarded-Proto", "https");
            http.put("requestHeader", requestHeaders);
            if (!backendPath.equals(externalPath)) {
                // Body path rewriting requires an uncompressed backend response.
                requestHeaders.put("Accept-Encoding", "identity");
                JSONArray rewriteURL = new JSONArray();
                JSONObject rewrite = new JSONObject();
                rewrite.put("match", requestPathPattern(externalPath));
                rewrite.put("replacement", requestPathReplacement(backendPath));
                rewriteURL.add(rewrite);
                http.put("rewriteURL", rewriteURL);
                JSONObject responseHeaders = new JSONObject();
                responseHeaders.put("@cloudnest.internalPath", backendPath);
                responseHeaders.put("@cloudnest.externalPath", externalPath);
                http.put("responseHeader", responseHeaders);

                JSONArray bodyRewrites = new JSONArray();
                for (String type : List.of("text/html", "text/css", "application/javascript", "application/json")) {
                    JSONObject bodyRewrite = new JSONObject();
                    bodyRewrite.put("type", type);
                    bodyRewrite.put("match", java.util.regex.Pattern.quote(backendPath + "/"));
                    bodyRewrite.put("replacement", externalPath.equals("/") ? "/" : externalPath + "/");
                    bodyRewrites.add(bodyRewrite);
                }
                http.put("rewriteBody", bodyRewrites);
            }
            node.put("http", http);
            if ("https".equalsIgnoreCase(targets.get(i).getBackendScheme())) {
                JSONObject tlsBackend = new JSONObject();
                tlsBackend.put("secure", false);
                tlsBackend.put("options", new JSONObject());
                node.put("tls", tlsBackend);
            }
        }
        return service;
    }

    private static String requestPathPattern(String externalPath) {
        if ("/".equals(externalPath)) return "^/(.*)$";
        return "^" + java.util.regex.Pattern.quote(externalPath) + "(?:/(.*))?$";
    }

    private static String requestPathReplacement(String backendPath) {
        if ("/".equals(backendPath)) return "/$1";
        return backendPath + "/$1";
    }
}
