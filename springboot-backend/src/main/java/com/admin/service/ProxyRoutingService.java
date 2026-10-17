package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.entity.PrivateProxy;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Default exit plus per-domain rules for a private proxy. When a proxy uses a custom exit or has
 * rules, its traffic goes through a router on the node (a loopback sing-box) that sends each
 * connection to direct, a residential exit or a custom exit.
 */
@Service
public class ProxyRoutingService {
    static final int MAX_RULES = 200;
    static final int MAX_VALUES = 2000;
    /** The node router's limit per match field in one rule (regular expressions: 500). */
    static final int ROUTER_RULE_VALUES = 2000;
    static final int ROUTER_REGEX_VALUES = 500;
    /** Values one router may hold after expanding groups, lists and rule sets. */
    static final int ROUTER_TOTAL_VALUES = 120_000;
    private static final String EXIT_ROUTE_USER = "exit";
    private static final Set<String> MATCHES = Set.of("domain_suffix", "domain_keyword", "domain", "domain_regex", "ip_cidr",
            "port", "network", "preset", "geosite", "geoip", "ruleset");
    /** Matches that work on the destination address; a rule may ask for the domain to be resolved first. */
    private static final Set<String> IP_MATCHES = Set.of("ip_cidr", "geoip", "ruleset");
    /** Router fields that need Agent ROUTER_RULES_AGENT_VERSION. */
    private static final Set<String> NEWER_ROUTER_FIELDS = Set.of("domain_regex", "port", "port_range", "network", "ip_is_private");

    /** Where the proxy's own runtime should send traffic: the router's loopback SOCKS5. */
    public static final class Endpoint {
        public final int port;
        public final String username;
        public final String password;

        Endpoint(int port, String username, String password) {
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }

    private final JdbcTemplate jdbc;
    private final ProxyExitService exits;
    private final com.admin.mapper.PrivateProxyMapper proxyMapper;
    private final com.admin.mapper.NodeMapper nodeMapper;

    public ProxyRoutingService(JdbcTemplate jdbc, ProxyExitService exits, com.admin.mapper.PrivateProxyMapper proxyMapper,
                               com.admin.mapper.NodeMapper nodeMapper) {
        this.jdbc = jdbc;
        this.exits = exits;
        this.proxyMapper = proxyMapper;
        this.nodeMapper = nodeMapper;
    }

    /**
     * Re-applies the routers of running proxies whose rules use one of the given groups, so a
     * refreshed list takes effect. Returns how many routers were updated.
     */
    public int reapplyForPresets(Set<String> presetKeys) {
        return reapplyMatching(rule -> "preset".equals(rule.getString("match"))
                && rule.getJSONArray("values").toJavaList(String.class).stream().anyMatch(presetKeys::contains));
    }

    /** Re-applies the routers of running proxies with a rule the predicate accepts. */
    public int reapplyMatching(java.util.function.Predicate<JSONObject> uses) {
        return reapplyWhere(proxy -> rules(proxy.getRouteRules()).stream().anyMatch(uses));
    }

    /** Re-applies the routers of running proxies that use the exit as default or in a rule. */
    public int reapplyForExit(Long exitId) {
        String key = "custom:" + exitId;
        return reapplyWhere(proxy -> "custom".equals(proxy.getExitType()) && exitId.equals(proxy.getExitCustomId())
                || rules(proxy.getRouteRules()).stream().anyMatch(rule -> key.equals(rule.getString("exit"))));
    }

    private int reapplyWhere(java.util.function.Predicate<PrivateProxy> uses) {
        int applied = 0;
        List<PrivateProxy> proxies = proxyMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PrivateProxy>()
                .eq("state", "active"));
        for (PrivateProxy proxy : proxies) {
            if (!active(proxy) || !uses.test(proxy)) continue;
            Node node = nodeMapper.selectById(proxy.getNodeId());
            if (node == null || !WebSocketServer.isNodeOnline(node.getId())
                    || !com.admin.common.utils.AgentVersionUtil.isAtLeast(node.getVersion(), ProxyExitService.ROUTER_AGENT_VERSION)) continue;
            try {
                apply(proxy, node, true);
                applied++;
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ProxyRoutingService.class)
                        .warn("Re-applying router for proxy {} failed: {}", proxy.getId(), e.getMessage());
            }
        }
        return applied;
    }

    /** A proxy needs a router when it uses a custom exit or has any rule. */
    public boolean active(PrivateProxy proxy) {
        return ExitPoolService.ROUTED_PROXY_TYPES.contains(proxy.getProxyType())
                && ("custom".equals(proxy.getExitType()) || !rules(proxy.getRouteRules()).isEmpty());
    }

    public static List<JSONObject> rules(String json) {
        if (StringUtils.isBlank(json)) return List.of();
        try {
            JSONArray array = JSON.parseArray(json);
            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) result.add(array.getJSONObject(i));
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Validates rules sent by the page and returns them as stored JSON (null when empty).
     * Each rule: {match, values, exit} where exit is direct, block, residential:CC or custom:ID.
     */
    public static String normalizeRules(List<Map<String, Object>> input) {
        if (input == null || input.isEmpty()) return null;
        if (input.size() > MAX_RULES) throw new IllegalArgumentException("分流规则最多 " + MAX_RULES + " 条");
        JSONArray result = new JSONArray();
        for (int index = 0; index < input.size(); index++) {
            Map<String, Object> rule = input.get(index);
            String label = "第 " + (index + 1) + " 条规则";
            String match = String.valueOf(rule.getOrDefault("match", ""));
            if (!MATCHES.contains(match)) throw new IllegalArgumentException(label + "的匹配方式不正确");
            Object rawValues = rule.get("values");
            List<String> values = new ArrayList<>();
            if (rawValues instanceof List) {
                for (Object value : (List<?>) rawValues) if (value != null) values.add(value.toString());
            } else if (rawValues != null) {
                for (String value : rawValues.toString().split("[\\s,，]+")) values.add(value);
            }
            Set<String> cleaned = new LinkedHashSet<>();
            for (String value : values) {
                String item = validValue(match, value);
                if (item == null) throw new IllegalArgumentException(label + "里有不正确的值：" + value.trim());
                cleaned.add(item);
            }
            if (cleaned.isEmpty()) throw new IllegalArgumentException(label + "没有填写匹配内容");
            if (cleaned.size() > MAX_VALUES) throw new IllegalArgumentException(label + "的内容太多（最多 " + MAX_VALUES + " 个）");
            String exit = String.valueOf(rule.getOrDefault("exit", ""));
            if (!validExit(exit)) throw new IllegalArgumentException(label + "没有选择出口");
            JSONObject stored = new JSONObject(true);
            stored.put("match", match);
            stored.put("values", new ArrayList<>(cleaned));
            stored.put("exit", exit.startsWith("residential:") ? exit.toUpperCase(Locale.ROOT).replace("RESIDENTIAL:", "residential:") : exit);
            if (IP_MATCHES.contains(match) && Boolean.TRUE.equals(rule.get("resolve"))) stored.put("resolve", true);
            result.add(stored);
        }
        return result.toJSONString();
    }

    /** The cleaned value for a rule match, or null when it is not valid. */
    static String validValue(String match, String value) {
        String item = value.trim();
        if (item.isEmpty()) return null;
        switch (match) {
            case "preset":
                item = item.toLowerCase(Locale.ROOT);
                return ProxyRoutePresets.get(item) != null ? item : null;
            case "geosite":
                item = item.toLowerCase(Locale.ROOT);
                return ProxyRuleSourceService.GEOSITE_NAME.matcher(item).matches() ? item : null;
            case "geoip":
                item = item.toLowerCase(Locale.ROOT);
                if ("lan".equals(item)) item = "private";
                return ProxyRuleSourceService.GEOIP_NAME.matcher(item).matches() ? item : null;
            case "ruleset":
                return item.matches("\\d{1,18}") ? item : null;
            default:
                return RouteMatchSet.normalize(match, item);
        }
    }

    static boolean validExit(String exit) {
        return "direct".equals(exit) || "block".equals(exit)
                || exit.matches("residential:[A-Za-z]{2}") || exit.matches("custom:\\d{1,18}");
    }

    /** Residential countries the proxy's router sends traffic to, sorted. */
    public static Set<String> residentialCountries(String exitType, String exitCountry, List<JSONObject> rules) {
        Set<String> countries = new java.util.TreeSet<>();
        if ("residential".equals(exitType) && StringUtils.isNotBlank(exitCountry)) countries.add(exitCountry.toUpperCase(Locale.ROOT));
        for (JSONObject rule : rules) {
            String exit = rule.getString("exit");
            if (exit != null && exit.startsWith("residential:")) countries.add(exit.substring("residential:".length()).toUpperCase(Locale.ROOT));
        }
        return countries;
    }

    public static Set<Long> customExitIds(String exitType, Long exitCustomId, List<JSONObject> rules) {
        Set<Long> ids = new LinkedHashSet<>();
        if ("custom".equals(exitType) && exitCustomId != null) ids.add(exitCustomId);
        for (JSONObject rule : rules) {
            String exit = rule.getString("exit");
            if (exit != null && exit.startsWith("custom:")) ids.add(Long.parseLong(exit.substring("custom:".length())));
        }
        return ids;
    }

    /** The comma-wrapped country list the exit pool reads, e.g. ",JP,US,". */
    public static String routeCountriesColumn(Set<String> countries) {
        return countries.isEmpty() ? null : "," + String.join(",", countries) + ",";
    }

    /**
     * Builds the router command. Outbound tags: direct, res-CC (the proxy's residential route for
     * that country on the given local port), custom-ID.
     */
    static JSONObject routerRequest(String name, String exitType, String exitCountry, Long exitCustomId, List<JSONObject> rules,
                                    Map<String, Integer> residentialPorts, String routePassword, Map<Long, JSONObject> customOutbounds) {
        return routerRequest(name, exitType, exitCountry, exitCustomId, rules, residentialPorts, routePassword, customOutbounds, false);
    }

    /**
     * With resolveForIp, a domain is looked up before the first IP rule (as Clash does), which
     * Agents before ROUTER_RULES_AGENT_VERSION do not support; without it IP rules only match
     * connections made to an address.
     */
    static JSONObject routerRequest(String name, String exitType, String exitCountry, Long exitCustomId, List<JSONObject> rules,
                                    Map<String, Integer> residentialPorts, String routePassword, Map<Long, JSONObject> customOutbounds,
                                    boolean resolveForIp) {
        JSONArray outbounds = new JSONArray();
        for (Map.Entry<String, Integer> entry : residentialPorts.entrySet()) {
            JSONObject outbound = new JSONObject(true);
            outbound.put("type", "socks");
            outbound.put("tag", "res-" + entry.getKey().toLowerCase(Locale.ROOT));
            outbound.put("version", "5");
            outbound.put("server", "127.0.0.1");
            outbound.put("server_port", entry.getValue());
            outbound.put("username", EXIT_ROUTE_USER);
            outbound.put("password", routePassword);
            outbounds.add(outbound);
        }
        for (Map.Entry<Long, JSONObject> entry : customOutbounds.entrySet()) {
            JSONObject outbound = new JSONObject(true);
            outbound.putAll(entry.getValue());
            outbound.put("tag", "custom-" + entry.getKey());
            outbound.remove("detour");
            outbounds.add(outbound);
        }
        JSONArray routerRules = new JSONArray();
        boolean resolved = false;
        int total = 0;
        for (JSONObject rule : rules) {
            RouteMatchSet set = expand(rule.getString("match"), rule.getJSONArray("values").toJavaList(String.class));
            if (set.size() == 0) continue;
            total += set.size();
            if (total > ROUTER_TOTAL_VALUES) {
                throw new IllegalStateException("分流规则展开后超过 " + ROUTER_TOTAL_VALUES + " 条，请减少大型 GEOIP、GEOSITE 或规则集");
            }
            // IP rules match connections made to an address. A rule can ask for the domain to be
            // resolved first; sing-box then hands every later exit the address instead of the
            // domain, so this is opt-in rather than Clash's default.
            if (resolveForIp && set.hasIpMatch() && !resolved && rule.getBooleanValue("resolve")) {
                JSONObject resolve = new JSONObject(true);
                resolve.put("action", "resolve");
                routerRules.add(resolve);
                resolved = true;
            }
            String exit = rule.getString("exit");
            // One field per router rule: sing-box ANDs different kinds of fields in one rule.
            Map<String, List<Object>> fields = new LinkedHashMap<>();
            putField(fields, "domain_suffix", set.domainSuffix);
            putField(fields, "domain", set.domain);
            putField(fields, "domain_keyword", set.domainKeyword);
            putField(fields, "domain_regex", set.domainRegex);
            putField(fields, "ip_cidr", set.ipCidr);
            List<Object> ports = new ArrayList<>();
            List<Object> ranges = new ArrayList<>();
            for (String port : set.port) {
                if (port.contains("-")) ranges.add(port.replace('-', ':'));
                else ports.add(Integer.parseInt(port));
            }
            if (!ports.isEmpty()) fields.put("port", ports);
            if (!ranges.isEmpty()) fields.put("port_range", ranges);
            putField(fields, "network", set.network);
            for (Map.Entry<String, List<Object>> field : fields.entrySet()) {
                List<Object> list = field.getValue();
                int chunk = "domain_regex".equals(field.getKey()) || "port_range".equals(field.getKey()) ? ROUTER_REGEX_VALUES : ROUTER_RULE_VALUES;
                for (int from = 0; from < list.size(); from += chunk) {
                    JSONObject converted = new JSONObject(true);
                    converted.put(field.getKey(), new ArrayList<>(list.subList(from, Math.min(list.size(), from + chunk))));
                    routeTo(converted, exit);
                    routerRules.add(converted);
                }
            }
            if (set.ipPrivate) {
                JSONObject converted = new JSONObject(true);
                converted.put("ip_is_private", true);
                routeTo(converted, exit);
                routerRules.add(converted);
            }
        }
        JSONObject request = new JSONObject(true);
        request.put("name", name);
        request.put("outbounds", outbounds);
        request.put("rules", routerRules);
        request.put("final", "custom".equals(exitType) && exitCustomId != null ? "custom-" + exitCustomId
                : "residential".equals(exitType) && StringUtils.isNotBlank(exitCountry) ? "res-" + exitCountry.toLowerCase(Locale.ROOT)
                : "direct");
        return request;
    }

    private static void putField(Map<String, List<Object>> fields, String key, Set<String> values) {
        if (!values.isEmpty()) fields.put(key, new ArrayList<>(values));
    }

    private static void routeTo(JSONObject rule, String exit) {
        if ("block".equals(exit)) rule.put("action", "reject");
        else rule.put("outbound", tagOf(exit));
    }

    /** Everything a rule matches, with groups, lists and rule sets expanded. */
    static RouteMatchSet expand(String match, List<String> values) {
        RouteMatchSet set = new RouteMatchSet();
        for (String value : values) {
            switch (match) {
                case "preset": {
                    ProxyRoutePresets.Preset preset = ProxyRoutePresets.get(value);
                    if (preset == null) break;
                    set.domainSuffix.addAll(preset.domainSuffix);
                    set.domain.addAll(preset.domain);
                    set.domainKeyword.addAll(preset.domainKeyword);
                    break;
                }
                case "geosite":
                case "geoip":
                case "ruleset": {
                    RouteMatchSet list = ProxyRuleSourceService.list(match + ":" + value);
                    if (list != null) set.addAll(list);
                    break;
                }
                case "domain": set.domain.add(value); break;
                case "domain_suffix": set.domainSuffix.add(value); break;
                case "domain_keyword": set.domainKeyword.add(value); break;
                case "domain_regex": set.domainRegex.add(value); break;
                case "ip_cidr": set.ipCidr.add(value); break;
                case "port": set.port.add(value); break;
                case "network": set.network.add(value); break;
                default: break;
            }
        }
        return set;
    }

    /** True when the router command uses fields or exit groups that older Agents reject. */
    static boolean needsNewerRouter(JSONObject request) {
        JSONArray outbounds = request.getJSONArray("outbounds");
        for (int i = 0; i < outbounds.size(); i++) if ("urltest".equals(outbounds.getJSONObject(i).getString("type"))) return true;
        JSONArray rules = request.getJSONArray("rules");
        for (int i = 0; i < rules.size(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if ("resolve".equals(rule.getString("action"))) return true;
            for (String key : rule.keySet()) if (NEWER_ROUTER_FIELDS.contains(key)) return true;
        }
        return false;
    }

    private static String tagOf(String exit) {
        if (exit.startsWith("residential:")) return "res-" + exit.substring("residential:".length()).toLowerCase(Locale.ROOT);
        if (exit.startsWith("custom:")) return "custom-" + exit.substring("custom:".length());
        return "direct";
    }

    /**
     * Opens the residential routes the rules need, applies the router on the node and returns
     * where the proxy should send its traffic. Updates the proxy's route columns in memory.
     */
    public Endpoint apply(PrivateProxy proxy, Node node, boolean admin) {
        List<JSONObject> rules = rules(proxy.getRouteRules());
        Set<String> countries = residentialCountries(proxy.getExitType(), proxy.getExitCountry(), rules);
        Map<Long, JSONObject> customs = new LinkedHashMap<>();
        for (Long id : customExitIds(proxy.getExitType(), proxy.getExitCustomId(), rules)) {
            JSONObject outbound = exits.usableOutbound(id, proxy.getUserId(), admin);
            if (outbound == null) throw new IllegalStateException("自定义出口 #" + id + " 不存在或不能使用");
            if (!ProxyExitService.GROUP_TYPE.equals(outbound.getString("type"))) {
                customs.put(id, outbound);
                continue;
            }
            // An exit group becomes a sing-box urltest over its members.
            JSONArray members = new JSONArray();
            for (Long member : outbound.getJSONArray("members").toJavaList(Long.class)) {
                JSONObject memberOutbound = customs.containsKey(member) ? customs.get(member) : exits.usableOutbound(member, proxy.getUserId(), admin);
                if (memberOutbound == null || ProxyExitService.GROUP_TYPE.equals(memberOutbound.getString("type"))) {
                    throw new IllegalStateException("出口组 #" + id + " 的成员 #" + member + " 不存在或不能使用");
                }
                customs.put(member, memberOutbound);
                members.add("custom-" + member);
            }
            if (outbound.getBooleanValue("direct")) members.add("direct");
            JSONObject group = new JSONObject(true);
            group.put("type", "urltest");
            group.put("outbounds", members);
            group.put("url", outbound.getString("url"));
            group.put("interval", outbound.getIntValue("intervalMinutes") + "m");
            group.put("tolerance", outbound.getIntValue("tolerance"));
            customs.put(id, group);
        }
        Map<String, Integer> previous = routeExitPorts(proxy.getRouteExitPorts());
        Map<String, Integer> ports = new TreeMap<>();
        for (String country : countries) {
            ports.put(country, openCountryRoute(proxy, node, country, previous.get(country)));
        }
        for (Map.Entry<String, Integer> stale : previous.entrySet()) {
            if (!ports.containsKey(stale.getKey())) GostUtil.DeleteNamedService(node.getId(), countryRouteName(proxy, stale.getKey()));
        }
        proxy.setRouteExitPorts(ports.isEmpty() ? null : JSON.toJSONString(ports));
        proxy.setRouteCountries(routeCountriesColumn(countries));
        boolean newerAgent = com.admin.common.utils.AgentVersionUtil.isAtLeast(node.getVersion(), ProxyExitService.ROUTER_RULES_AGENT_VERSION);
        JSONObject request = routerRequest(routerName(proxy), proxy.getExitType(), proxy.getExitCountry(), proxy.getExitCustomId(),
                rules, ports, routePassword(proxy), customs, newerAgent);
        if (needsNewerRouter(request) && !newerAgent) {
            throw new IllegalStateException("出口组和正则、端口、网络、GEOIP 等规则需要节点 Agent " + ProxyExitService.ROUTER_RULES_AGENT_VERSION
                    + " 或更高版本，请先升级该节点");
        }
        GostDto response = WebSocketServer.send_msg(node.getId(), request, "ApplyProxyRouter", 30);
        if (response == null || !"OK".equals(response.getMsg())) {
            throw new IllegalStateException("下发分流规则失败：" + (response == null ? "节点无响应" : response.getMsg()));
        }
        JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
        if (data == null || data.getIntValue("port") < 1) throw new IllegalStateException("节点返回的分流路由不完整");
        return new Endpoint(data.getIntValue("port"), data.getString("username"), data.getString("password"));
    }

    /** Removes the router and the per-country routes of a proxy. */
    public void remove(PrivateProxy proxy, Node node) {
        if (node == null) return;
        WebSocketServer.send_msg(node.getId(), Map.of("name", routerName(proxy)), "DeleteProxyRouter", 10);
        for (String country : routeExitPorts(proxy.getRouteExitPorts()).keySet()) {
            GostUtil.DeleteNamedService(node.getId(), countryRouteName(proxy, country));
        }
        proxy.setRouteExitPorts(null);
    }

    private Integer openCountryRoute(PrivateProxy proxy, Node node, String country, Integer previousPort) {
        String name = countryRouteName(proxy, country);
        String exitInterface = ExitPoolService.interfaceName(country);
        if (previousPort != null) {
            GostDto updated = GostUtil.sendExitRoute(node.getId(), name, previousPort, EXIT_ROUTE_USER, routePassword(proxy), exitInterface, "UpdateService");
            if (updated != null && "OK".equals(updated.getMsg())) return previousPort;
        }
        for (int attempt = 0; attempt < 30; attempt++) {
            int port = 45000 + (int) ((proxy.getId() * 131L + country.hashCode() * 31L + attempt * 977L) % 15000L + 15000L) % 15000;
            if (portTaken(node.getId(), port, proxy.getId())) continue;
            if (!AgentPortCheckUtil.check(node, List.of(new AgentPortCheckUtil.Check("tcp", "127.0.0.1", port))).isAvailable()) continue;
            GostDto created = GostUtil.sendExitRoute(node.getId(), name, port, EXIT_ROUTE_USER, routePassword(proxy), exitInterface, "AddService");
            if (created == null || !"OK".equals(created.getMsg())) {
                throw new IllegalStateException("创建家宽通道失败：" + (created == null ? "节点无响应" : created.getMsg()));
            }
            return port;
        }
        throw new IllegalStateException("节点上没有可用的本机端口用于家宽通道");
    }

    private boolean portTaken(Long nodeId, int port, Long proxyId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM private_proxy WHERE node_id = ? AND id <> ? AND state <> 'deleted' "
                + "AND (exit_route_port = ? OR route_exit_ports LIKE ?)", Long.class, nodeId, proxyId, port, "%:" + port + "%");
        return count != null && count > 0;
    }

    public static Map<String, Integer> routeExitPorts(String json) {
        Map<String, Integer> ports = new TreeMap<>();
        if (StringUtils.isBlank(json)) return ports;
        try {
            JSONObject object = JSON.parseObject(json);
            for (String key : object.keySet()) ports.put(key, object.getInteger(key));
        } catch (Exception ignored) {
            // A damaged value is rebuilt on the next apply.
        }
        return ports;
    }

    static String routerName(PrivateProxy proxy) {
        return proxy.getServiceName() + "-router";
    }

    private static String countryRouteName(PrivateProxy proxy, String country) {
        return proxy.getServiceName() + "-exit-" + country.toLowerCase(Locale.ROOT);
    }

    /** Same credential the proxy's single residential route uses; the routes only listen on loopback. */
    private static String routePassword(PrivateProxy proxy) {
        return StringUtils.removeStart(proxy.getServiceName(), "private-proxy-");
    }
}
