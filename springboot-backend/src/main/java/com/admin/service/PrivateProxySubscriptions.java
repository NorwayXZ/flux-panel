package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.apache.commons.lang3.StringUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 订阅内容：通用 Base64 链接列表、Clash（mihomo）配置和 sing-box 配置。 */
final class PrivateProxySubscriptions {
    static final String GROUP = "云巢";

    private PrivateProxySubscriptions() {
    }

    /** 请求里指定的格式优先；否则按客户端的 User-Agent 判断。 */
    static String detectFormat(String format, String userAgent) {
        String requested = StringUtils.trimToEmpty(format).toLowerCase(Locale.ROOT);
        if (List.of("base64", "clash", "singbox").contains(requested)) return requested;
        if ("sing-box".equals(requested)) return "singbox";
        String agent = StringUtils.defaultString(userAgent).toLowerCase(Locale.ROOT);
        if (agent.contains("sing-box") || agent.startsWith("sfa") || agent.startsWith("sfi") || agent.startsWith("sfm")
                || agent.startsWith("sft")) {
            return "singbox";
        }
        if (agent.contains("clash") || agent.contains("mihomo") || agent.contains("stash")) return "clash";
        return "base64";
    }

    static String base64(List<String> uris) {
        return Base64.getEncoder().encodeToString(String.join("\n", uris).getBytes(StandardCharsets.UTF_8));
    }

    static String clashProfile(List<Map<String, Object>> proxies) {
        List<Object> names = new ArrayList<>();
        for (Map<String, Object> proxy : proxies) names.add(proxy.get("name"));
        names.add("DIRECT");
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("name", GROUP);
        group.put("type", "select");
        group.put("proxies", names);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("mixed-port", 7890);
        profile.put("allow-lan", false);
        profile.put("mode", "rule");
        profile.put("log-level", "warning");
        profile.put("proxies", proxies);
        profile.put("proxy-groups", List.of(group));
        profile.put("rules", List.of(
                "IP-CIDR,127.0.0.0/8,DIRECT,no-resolve",
                "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
                "IP-CIDR,172.16.0.0/12,DIRECT,no-resolve",
                "IP-CIDR,192.168.0.0/16,DIRECT,no-resolve",
                "MATCH," + GROUP));
        return yaml(profile);
    }

    static String singBoxProfile(List<PrivateProxyClientFormats.SingBoxClient> clients, List<String> tags) {
        List<Object> outbounds = new ArrayList<>();
        List<Object> endpoints = new ArrayList<>();
        List<String> choices = new ArrayList<>(tags);
        choices.add("direct");
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("type", "selector");
        selector.put("tag", GROUP);
        selector.put("outbounds", choices);
        if (!tags.isEmpty()) selector.put("default", tags.get(0));
        outbounds.add(selector);
        for (PrivateProxyClientFormats.SingBoxClient client : clients) {
            outbounds.addAll(client.outbounds);
            endpoints.addAll(client.endpoints);
        }
        outbounds.add(Map.of("type", "direct", "tag", "direct"));

        Map<String, Object> remoteDns = new LinkedHashMap<>();
        remoteDns.put("type", "https");
        remoteDns.put("tag", "remote");
        remoteDns.put("server", "1.1.1.1");
        remoteDns.put("detour", GROUP);
        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("servers", List.of(remoteDns, Map.of("type", "local", "tag", "local")));
        dns.put("final", "remote");

        Map<String, Object> tun = new LinkedHashMap<>();
        tun.put("type", "tun");
        tun.put("tag", "tun-in");
        tun.put("address", List.of("172.19.0.1/30"));
        tun.put("auto_route", true);
        tun.put("strict_route", true);
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("type", "mixed");
        mixed.put("tag", "mixed-in");
        mixed.put("listen", "127.0.0.1");
        mixed.put("listen_port", 2080);

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("rules", List.of(
                Map.of("action", "sniff"),
                Map.of("protocol", "dns", "action", "hijack-dns"),
                Map.of("ip_is_private", true, "outbound", "direct")));
        route.put("final", GROUP);
        route.put("auto_detect_interface", true);
        route.put("default_domain_resolver", "local");

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("log", Map.of("level", "warn"));
        profile.put("dns", dns);
        profile.put("inbounds", List.of(tun, mixed));
        profile.put("outbounds", outbounds);
        if (!endpoints.isEmpty()) profile.put("endpoints", endpoints);
        profile.put("route", route);
        return JSONObject.toJSONString(profile, SerializerFeature.PrettyFormat, SerializerFeature.DisableCircularReferenceDetect);
    }

    static String yaml(Object data) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setWidth(4096);
        return new Yaml(options).dump(data);
    }

    /** 名字在同一份订阅里必须唯一，重名的依次加上序号。 */
    static String uniqueName(String name, java.util.Set<String> used) {
        String base = StringUtils.defaultIfBlank(StringUtils.trimToEmpty(name), "代理");
        String candidate = base;
        for (int i = 2; used.contains(candidate); i++) candidate = base + " " + i;
        used.add(candidate);
        return candidate;
    }
}
