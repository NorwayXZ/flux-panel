package com.admin.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads Clash (mihomo) rules: rule-provider content for rule-set subscriptions, and the rules
 * of a pasted Clash configuration for import.
 */
public final class ClashRules {
    static final int MAX_IMPORT_LINES = 20_000;
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("DOMAIN", "domain"),
            Map.entry("DOMAIN-SUFFIX", "domain_suffix"),
            Map.entry("DOMAIN-KEYWORD", "domain_keyword"),
            Map.entry("DOMAIN-REGEX", "domain_regex"),
            Map.entry("IP-CIDR", "ip_cidr"),
            Map.entry("IP-CIDR6", "ip_cidr"),
            Map.entry("DST-PORT", "port"),
            Map.entry("NETWORK", "network"),
            Map.entry("GEOSITE", "geosite"),
            Map.entry("GEOIP", "geoip"),
            Map.entry("RULE-SET", "ruleset"));
    private static final Set<String> SOURCE_TYPES = Set.of("SRC-IP-CIDR", "SRC-PORT", "SRC-GEOIP", "SRC-IP-ASN", "SRC-IP-SUFFIX",
            "IN-PORT", "IN-TYPE", "IN-USER", "IN-NAME", "PROCESS-NAME", "PROCESS-PATH", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX", "UID");

    private ClashRules() {
    }

    /** A subscribed rule set after parsing. */
    public static final class Content {
        public final RouteMatchSet set = new RouteMatchSet();
        public int skipped;
    }

    /**
     * Parses rule-provider content. Accepts Clash YAML (payload:), plain text with one entry per
     * line, and sing-box rule-set source JSON. Behavior is domain, ipcidr, classical or auto.
     */
    public static Content parseRuleSet(String text, String behavior) {
        Content content = new Content();
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) return content;
        if (trimmed.startsWith("{")) {
            parseSingBox(trimmed, content);
            return content;
        }
        List<String> entries = new ArrayList<>();
        if (trimmed.matches("(?s).*(^|\\n)payload:.*")) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(trimmed);
            Object payload = loaded instanceof Map ? ((Map<?, ?>) loaded).get("payload") : null;
            if (!(payload instanceof List)) throw new IllegalArgumentException("YAML 里没有 payload 列表");
            for (Object item : (List<?>) payload) if (item != null) entries.add(item.toString());
        } else {
            for (String line : trimmed.split("\\r?\\n")) entries.add(line);
        }
        for (String raw : entries) {
            String line = stripComment(raw);
            if (line.isEmpty()) continue;
            boolean added;
            String kind = "auto".equals(behavior) ? guess(line) : behavior;
            switch (kind) {
                case "domain":
                    added = addDomainEntry(content.set, line);
                    break;
                case "ipcidr":
                    added = content.set.add("ip_cidr", line);
                    break;
                default:
                    added = addClassical(content.set, line);
            }
            if (!added) content.skipped++;
        }
        return content;
    }

    private static String guess(String line) {
        if (line.contains(",")) return "classical";
        return RouteMatchSet.cidr(line.toLowerCase(Locale.ROOT)) != null ? "ipcidr" : "domain";
    }

    private static String stripComment(String raw) {
        String line = raw.strip();
        if (line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) return "";
        if (line.startsWith("- ")) line = line.substring(2).strip();
        if (line.length() >= 2 && (line.startsWith("'") && line.endsWith("'") || line.startsWith("\"") && line.endsWith("\""))) {
            line = line.substring(1, line.length() - 1).strip();
        }
        return line;
    }

    /** Clash domain behavior: "+.x" and ".x" are suffixes, "*.x" one label, anything else exact. */
    private static boolean addDomainEntry(RouteMatchSet set, String line) {
        String value = line.toLowerCase(Locale.ROOT);
        if (value.startsWith("+.") || value.startsWith(".")) return set.add("domain_suffix", value);
        if (value.startsWith("*.")) {
            String rest = value.substring(2);
            if (RouteMatchSet.normalize("domain", rest) == null) return false;
            return set.add("domain_regex", "^[^.]+\\." + rest.replace(".", "\\.") + "$");
        }
        return set.add("domain", value);
    }

    private static boolean addClassical(RouteMatchSet set, String line) {
        String[] parts = line.split(",");
        if (parts.length < 2) return false;
        String match = TYPES.get(parts[0].strip().toUpperCase(Locale.ROOT));
        if (match == null || Set.of("geosite", "geoip", "ruleset").contains(match)) return false;
        if ("port".equals(match)) {
            boolean any = false;
            for (String port : parts[1].split("/")) any |= set.add("port", port);
            return any;
        }
        return set.add(match, parts[1]);
    }

    private static void parseSingBox(String text, Content content) {
        JSONObject json;
        try {
            json = JSON.parseObject(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法识别的 JSON 规则集");
        }
        JSONArray rules = json == null ? null : json.getJSONArray("rules");
        if (rules == null) throw new IllegalArgumentException("JSON 规则集里没有 rules");
        for (int i = 0; i < rules.size(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if (rule == null) continue;
            for (String key : rule.keySet()) {
                Object value = rule.get(key);
                List<Object> values = value instanceof List ? new ArrayList<>((List<?>) value) : List.of(value);
                String match = "port_range".equals(key) ? "port" : key;
                for (Object item : values) {
                    boolean added;
                    if ("ip_is_private".equals(key)) {
                        content.set.ipPrivate |= Boolean.TRUE.equals(item);
                        added = true;
                    } else if (Set.of("domain", "domain_suffix", "domain_keyword", "domain_regex", "ip_cidr", "port", "network").contains(match)) {
                        added = item != null && content.set.add(match, item.toString());
                    } else {
                        added = false;
                    }
                    if (!added) content.skipped++;
                }
            }
        }
    }

    /** One rule of a pasted Clash configuration, merged with its neighbours when they agree. */
    public static final class ImportedRule {
        public final String match;
        public final List<String> values = new ArrayList<>();
        public final String policy;
        public final boolean noResolve;

        ImportedRule(String match, String policy, boolean noResolve) {
            this.match = match;
            this.policy = policy;
            this.noResolve = noResolve;
        }
    }

    public static final class Provider {
        public final String name;
        public final String url;
        public final String behavior;
        public final Integer intervalSeconds;

        Provider(String name, String url, String behavior, Integer intervalSeconds) {
            this.name = name;
            this.url = url;
            this.behavior = behavior;
            this.intervalSeconds = intervalSeconds;
        }
    }

    public static final class Import {
        public final List<ImportedRule> rules = new ArrayList<>();
        public final Map<String, Provider> providers = new LinkedHashMap<>();
        /** Policies in order of first use, including DIRECT and REJECT. */
        public final Set<String> policies = new LinkedHashSet<>();
        /** The MATCH / FINAL policy, which corresponds to the proxy's default exit. */
        public String finalPolicy;
        public int lines;
        public int skipped;
        public final List<String> skippedSamples = new ArrayList<>();
    }

    /** Reads the rules (and rule providers) of a Clash configuration or a list of rule lines. */
    @SuppressWarnings("unchecked")
    public static Import parseImport(String text) {
        Import result = new Import();
        if (text == null || text.isBlank()) throw new IllegalArgumentException("请粘贴 Clash 配置或规则");
        List<String> lines = new ArrayList<>();
        Object loaded = null;
        if (text.matches("(?s).*(^|\\n)(rules|rule-providers):.*")) {
            try {
                loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            } catch (Exception e) {
                throw new IllegalArgumentException("YAML 格式不正确：" + firstLine(e.getMessage()));
            }
        }
        if (loaded instanceof Map) {
            Map<String, Object> config = (Map<String, Object>) loaded;
            Object rules = config.get("rules");
            if (rules instanceof List) for (Object item : (List<?>) rules) if (item != null) lines.add(item.toString());
            Object providers = config.get("rule-providers");
            if (providers instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) providers).entrySet()) {
                    if (!(entry.getValue() instanceof Map)) continue;
                    Map<String, Object> provider = (Map<String, Object>) entry.getValue();
                    Object url = provider.get("url");
                    Object behavior = provider.get("behavior");
                    Object interval = provider.get("interval");
                    String format = provider.get("format") == null ? "" : provider.get("format").toString();
                    if (url == null || "mrs".equalsIgnoreCase(format)) continue;
                    result.providers.put(entry.getKey(), new Provider(entry.getKey(), url.toString(),
                            behavior == null ? "classical" : behavior.toString().toLowerCase(Locale.ROOT),
                            interval instanceof Number ? ((Number) interval).intValue() : null));
                }
            }
        } else {
            for (String line : text.split("\\r?\\n")) lines.add(line);
        }
        if (lines.size() > MAX_IMPORT_LINES) throw new IllegalArgumentException("规则太多（最多 " + MAX_IMPORT_LINES + " 行）");
        ImportedRule current = null;
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isEmpty()) continue;
            result.lines++;
            String reason = null;
            String[] parts = line.split(",");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].strip();
            String type = parts[0].toUpperCase(Locale.ROOT);
            if ("MATCH".equals(type) || "FINAL".equals(type)) {
                if (parts.length >= 2) result.finalPolicy = policy(parts[1]);
                continue;
            }
            String match = TYPES.get(type);
            if (match == null) {
                reason = SOURCE_TYPES.contains(type) ? "按来源或进程匹配，节点上的分流看不到这些信息"
                        : Set.of("AND", "OR", "NOT", "SUB-RULE").contains(type) ? "暂不支持组合规则" : "不支持的规则类型";
            } else if (parts.length < 3) {
                reason = "缺少策略";
            }
            String value = null;
            if (reason == null) {
                value = parts[1];
                if ("geoip".equals(match)) value = "lan".equalsIgnoreCase(value) ? "private" : value.toLowerCase(Locale.ROOT);
                else if ("geosite".equals(match)) value = value.toLowerCase(Locale.ROOT);
                else if (!"ruleset".equals(match) && !"port".equals(match) && RouteMatchSet.normalize(match, value) == null) reason = "值不正确";
                else if ("port".equals(match)) {
                    for (String port : value.split("/")) if (RouteMatchSet.port(port.strip()) == null) reason = "端口不正确";
                }
            }
            if (reason != null) {
                result.skipped++;
                if (result.skippedSamples.size() < 20) result.skippedSamples.add(line + "（" + reason + "）");
                continue;
            }
            String policy = policy(parts[2]);
            boolean noResolve = false;
            for (int i = 3; i < parts.length; i++) noResolve |= "no-resolve".equalsIgnoreCase(parts[i]);
            result.policies.add(policy);
            if (current == null || !current.match.equals(match) || !current.policy.equals(policy) || current.noResolve != noResolve
                    || current.values.size() >= ProxyRoutingService.MAX_VALUES) {
                current = new ImportedRule(match, policy, noResolve);
                result.rules.add(current);
            }
            if ("port".equals(match)) {
                for (String port : value.split("/")) current.values.add(RouteMatchSet.port(port.strip()));
            } else if (!current.values.contains(value)) {
                current.values.add(value);
            }
        }
        if (result.finalPolicy != null) result.policies.add(result.finalPolicy);
        return result;
    }

    private static String policy(String raw) {
        String value = raw.strip();
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.startsWith("REJECT")) return "REJECT";
        if ("DIRECT".equals(upper)) return "DIRECT";
        return value;
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
