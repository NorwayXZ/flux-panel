package com.admin.service;

import com.alibaba.fastjson.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Works out which routing rule a destination hits, the way the node router (sing-box) matches:
 * rules top to bottom, any field of a rule may match, IP rules only see addresses unless an
 * earlier rule asked for the domain to be resolved.
 */
public final class RouteTester {
    private static final Map<String, String> FIELD_LABELS = Map.of(
            "domain", "完整域名", "domain_suffix", "域名后缀", "domain_keyword", "域名关键词", "domain_regex", "域名正则",
            "ip_cidr", "IP 段", "port", "端口", "network", "网络类型", "ip_is_private", "私有地址");
    private static final Set<String> IP_MATCHES = Set.of("ip_cidr", "geoip", "ruleset", "catalog");

    private RouteTester() {
    }

    /** A destination typed by the user: host (domain or address), port and network. */
    static final class Target {
        final String host;
        final boolean address;
        final int port;
        final boolean portGiven;
        final String network;

        Target(String host, boolean address, int port, boolean portGiven, String network) {
            this.host = host;
            this.address = address;
            this.port = port;
            this.portGiven = portGiven;
            this.network = network;
        }
    }

    /** Accepts "example.com", "https://example.com/path", "example.com:8443", "1.2.3.4", "[2001:db8::1]:443". */
    static Target parse(String input, String network) {
        String text = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        int scheme = text.indexOf("://");
        if (scheme >= 0) text = text.substring(scheme + 3);
        int slash = text.indexOf('/');
        if (slash >= 0) text = text.substring(0, slash);
        int at = text.lastIndexOf('@');
        if (at >= 0) text = text.substring(at + 1);
        String host = text;
        Integer port = null;
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("地址格式不正确");
            host = text.substring(1, end);
            if (text.length() > end + 2 && text.charAt(end + 1) == ':') port = parsePort(text.substring(end + 2));
        } else if (text.chars().filter(c -> c == ':').count() == 1) {
            host = text.substring(0, text.indexOf(':'));
            port = parsePort(text.substring(text.indexOf(':') + 1));
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        boolean address = RouteMatchSet.cidr(host) != null;
        if (!address && RouteMatchSet.normalize("domain", host) == null) throw new IllegalArgumentException("请输入域名或 IP，例如 chatgpt.com");
        boolean http = input != null && input.trim().toLowerCase(Locale.ROOT).startsWith("http://");
        return new Target(host, address, port != null ? port : http ? 80 : 443, port != null, "udp".equals(network) ? "udp" : "tcp");
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口不正确");
        }
    }

    /**
     * Returns {index (-1 for the default exit), field, value, resolved, notes}. The resolver turns
     * a domain into addresses when a rule asks for it.
     */
    static Map<String, Object> test(List<JSONObject> rules, Target target, Function<String, List<String>> resolver) {
        List<String> notes = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        if (target.address) addresses.add(target.host);
        boolean resolved = false;
        boolean skippedIpRule = false;
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < rules.size(); index++) {
            JSONObject rule = rules.get(index);
            String match = rule.getString("match");
            RouteMatchSet set = ProxyRoutingService.expand(match, rule.getJSONArray("values").toJavaList(String.class));
            if (!target.address && !resolved && set.hasIpMatch() && rule.getBooleanValue("resolve")) {
                resolved = true;
                try {
                    addresses.addAll(resolver.apply(target.host));
                } catch (RuntimeException e) {
                    notes.add("解析 " + target.host + " 失败：" + e.getMessage() + "，节点上会直接断开这个连接");
                    result.put("index", -2);
                    result.put("notes", notes);
                    return result;
                }
                result.put("resolved", new ArrayList<>(addresses));
            }
            String[] hit = hit(set, target, addresses);
            if (hit != null) {
                result.put("index", index);
                result.put("field", FIELD_LABELS.getOrDefault(hit[0], hit[0]));
                result.put("value", hit[1]);
                result.put("notes", notes);
                return result;
            }
            if (!target.address && addresses.isEmpty() && set.hasIpMatch() && IP_MATCHES.contains(match)) skippedIpRule = true;
        }
        if (skippedIpRule) notes.add("目标是域名，IP 类规则没有参与匹配；需要时可在规则上勾选“先解析域名再按 IP 匹配”");
        if (target.address) notes.add("用 IP 直接连接时，如果是 HTTPS 等带域名的流量，节点会识别出域名，域名规则同样生效");
        result.put("index", -1);
        result.put("notes", notes);
        return result;
    }

    /** The field and value that match, or null. */
    static String[] hit(RouteMatchSet set, Target target, List<String> addresses) {
        if (!target.address) {
            String host = target.host;
            if (set.domain.contains(host)) return new String[]{"domain", host};
            String suffix = host;
            while (true) {
                if (set.domainSuffix.contains(suffix)) return new String[]{"domain_suffix", suffix};
                int dot = suffix.indexOf('.');
                if (dot < 0) break;
                suffix = suffix.substring(dot + 1);
            }
            for (String keyword : set.domainKeyword) if (host.contains(keyword)) return new String[]{"domain_keyword", keyword};
            for (String regex : set.domainRegex) {
                if (Pattern.compile(regex).matcher(host).find()) return new String[]{"domain_regex", regex};
            }
        }
        for (String address : addresses) {
            for (String cidr : set.ipCidr) if (contains(cidr, address)) return new String[]{"ip_cidr", cidr};
            if (set.ipPrivate && isPrivate(address)) return new String[]{"ip_is_private", address};
        }
        for (String port : set.port) {
            String[] range = port.split("-");
            int from = Integer.parseInt(range[0]);
            int to = range.length > 1 ? Integer.parseInt(range[1]) : from;
            if (target.port >= from && target.port <= to) return new String[]{"port", port};
        }
        if (set.network.contains(target.network)) return new String[]{"network", target.network.toUpperCase(Locale.ROOT)};
        return null;
    }

    static boolean contains(String cidr, String address) {
        try {
            String[] parts = cidr.split("/");
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] value = InetAddress.getByName(address).getAddress();
            if (network.length != value.length) return false;
            int prefix = Integer.parseInt(parts[1]);
            for (int i = 0; i < network.length && prefix > 0; i++, prefix -= 8) {
                int mask = prefix >= 8 ? 0xff : (0xff << (8 - prefix)) & 0xff;
                if ((network[i] & mask) != (value[i] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPrivate(String address) {
        try {
            return !ProxyRuleSourceService.publicAddress(InetAddress.getByName(address));
        } catch (Exception e) {
            return false;
        }
    }
}
