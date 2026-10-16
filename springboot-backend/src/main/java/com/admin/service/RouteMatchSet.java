package com.admin.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Everything one routing list can match, by sing-box field: a downloaded GEOSITE or GEOIP list,
 * or a subscribed rule set. Values are validated as they are added, so a list read from the
 * internet only ever holds values the node router accepts.
 */
public final class RouteMatchSet {
    static final int MAX_ENTRIES = 50_000;
    private static final Pattern DOMAIN = Pattern.compile("^(?=.{1,253}$)[a-z0-9_]([a-z0-9_.-]*[a-z0-9_])?$");
    private static final Pattern IPV4_CIDR = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(/(\\d{1,2}))?$");
    private static final Pattern IPV6_CIDR = Pattern.compile("^[0-9a-f:.]*:[0-9a-f:.]*(/(\\d{1,3}))?$");
    private static final Pattern PORT = Pattern.compile("^(\\d{1,5})(?:[-:](\\d{1,5}))?$");
    /** Constructs Java accepts but the router's Go regular expressions do not. */
    private static final Pattern UNSUPPORTED_REGEX = Pattern.compile("\\(\\?[=!<]|\\\\[1-9]|\\(\\?>|[*+?}][+]");

    public final Set<String> domain = new LinkedHashSet<>();
    public final Set<String> domainSuffix = new LinkedHashSet<>();
    public final Set<String> domainKeyword = new LinkedHashSet<>();
    public final Set<String> domainRegex = new LinkedHashSet<>();
    public final Set<String> ipCidr = new LinkedHashSet<>();
    /** "443" or "8000-9000". */
    public final Set<String> port = new LinkedHashSet<>();
    public final Set<String> network = new LinkedHashSet<>();
    public boolean ipPrivate;

    public int size() {
        return domain.size() + domainSuffix.size() + domainKeyword.size() + domainRegex.size() + ipCidr.size()
                + port.size() + network.size() + (ipPrivate ? 1 : 0);
    }

    public boolean hasIpMatch() {
        return !ipCidr.isEmpty() || ipPrivate;
    }

    public void addAll(RouteMatchSet other) {
        domain.addAll(other.domain);
        domainSuffix.addAll(other.domainSuffix);
        domainKeyword.addAll(other.domainKeyword);
        domainRegex.addAll(other.domainRegex);
        ipCidr.addAll(other.ipCidr);
        port.addAll(other.port);
        network.addAll(other.network);
        ipPrivate |= other.ipPrivate;
    }

    /** Adds one value for a rule match type; returns false when the value is not valid for it. */
    public boolean add(String match, String raw) {
        String value = normalize(match, raw);
        if (value == null) return false;
        target(match).add(value);
        if (size() > MAX_ENTRIES) throw new IllegalStateException("超过 " + MAX_ENTRIES + " 条");
        return true;
    }

    private Set<String> target(String match) {
        switch (match) {
            case "domain": return domain;
            case "domain_suffix": return domainSuffix;
            case "domain_keyword": return domainKeyword;
            case "domain_regex": return domainRegex;
            case "ip_cidr": return ipCidr;
            case "port": return port;
            case "network": return network;
            default: throw new IllegalArgumentException("unknown match " + match);
        }
    }

    /** The cleaned value, or null when it is not valid for the match type. */
    static String normalize(String match, String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!"domain_regex".equals(match)) value = value.toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        switch (match) {
            case "domain":
                return DOMAIN.matcher(value).matches() ? value : null;
            case "domain_suffix":
                while (value.startsWith("*.") || value.startsWith("+.") || value.startsWith(".")) {
                    value = value.substring(value.startsWith(".") ? 1 : 2);
                }
                return DOMAIN.matcher(value).matches() ? value : null;
            case "domain_keyword":
                return value.length() <= 100 && !value.matches(".*[\\s,].*") ? value : null;
            case "domain_regex":
                return validRegex(value) ? value : null;
            case "ip_cidr":
                return cidr(value);
            case "port":
                return port(value);
            case "network":
                return "tcp".equals(value) || "udp".equals(value) ? value : null;
            default:
                return null;
        }
    }

    static boolean validRegex(String value) {
        if (value.length() > 1000 || UNSUPPORTED_REGEX.matcher(value).find()) return false;
        try {
            Pattern.compile(value);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** An address or CIDR as CIDR, or null. */
    static String cidr(String value) {
        java.util.regex.Matcher v4 = IPV4_CIDR.matcher(value);
        if (v4.matches()) {
            for (int i = 1; i <= 4; i++) if (Integer.parseInt(v4.group(i)) > 255) return null;
            if (v4.group(6) == null) return value + "/32";
            return Integer.parseInt(v4.group(6)) <= 32 ? value : null;
        }
        java.util.regex.Matcher v6 = IPV6_CIDR.matcher(value);
        if (v6.matches() && value.length() <= 43) {
            if (v6.group(2) == null) return value + "/128";
            return Integer.parseInt(v6.group(2)) <= 128 ? value : null;
        }
        return null;
    }

    static String port(String value) {
        java.util.regex.Matcher matcher = PORT.matcher(value);
        if (!matcher.matches()) return null;
        int from = Integer.parseInt(matcher.group(1));
        int to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
        if (from < 1 || to > 65535 || from > to) return null;
        return from == to ? String.valueOf(from) : from + "-" + to;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject(true);
        put(json, "domain", domain);
        put(json, "domain_suffix", domainSuffix);
        put(json, "domain_keyword", domainKeyword);
        put(json, "domain_regex", domainRegex);
        put(json, "ip_cidr", ipCidr);
        put(json, "port", port);
        put(json, "network", network);
        if (ipPrivate) json.put("ip_is_private", true);
        return json;
    }

    private static void put(JSONObject json, String key, Set<String> values) {
        if (!values.isEmpty()) json.put(key, new JSONArray(List.copyOf(values)));
    }

    public static RouteMatchSet fromJson(String text) {
        RouteMatchSet set = new RouteMatchSet();
        if (text == null || text.isBlank()) return set;
        JSONObject json;
        try {
            json = JSON.parseObject(text);
        } catch (Exception e) {
            return set;
        }
        if (json == null) return set;
        for (String key : List.of("domain", "domain_suffix", "domain_keyword", "domain_regex", "ip_cidr", "port", "network")) {
            JSONArray values = json.getJSONArray(key);
            if (values == null) continue;
            for (int i = 0; i < values.size(); i++) set.target(key).add(values.getString(i));
        }
        set.ipPrivate = json.getBooleanValue("ip_is_private");
        return set;
    }

    /** Sample values for display. */
    public List<String> sample(int limit) {
        List<String> sample = new java.util.ArrayList<>();
        for (Set<String> values : List.of(domainSuffix, domain, domainKeyword, domainRegex, ipCidr, port, network)) {
            for (String value : values) {
                if (sample.size() >= limit) return sample;
                sample.add(value);
            }
        }
        if (ipPrivate && sample.size() < limit) sample.add("私有地址");
        return sample;
    }
}
