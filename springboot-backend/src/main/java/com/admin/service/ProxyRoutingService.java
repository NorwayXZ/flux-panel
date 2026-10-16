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
import java.util.regex.Pattern;

/**
 * Default exit plus per-domain rules for a private proxy. When a proxy uses a custom exit or has
 * rules, its traffic goes through a router on the node (a loopback sing-box) that sends each
 * connection to direct, a residential exit or a custom exit.
 */
@Service
public class ProxyRoutingService {
    static final int MAX_RULES = 100;
    static final int MAX_VALUES = 500;
    /** The node router's limit per match field in one rule. */
    static final int ROUTER_RULE_VALUES = 2000;
    private static final String EXIT_ROUTE_USER = "exit";
    private static final Set<String> MATCHES = Set.of("domain_suffix", "domain_keyword", "domain", "ip_cidr", "preset");
    private static final Pattern DOMAIN = Pattern.compile("^(?=.{1,253}$)[a-z0-9_*]([a-z0-9_.-]*[a-z0-9])?$");
    private static final Pattern CIDR = Pattern.compile("^[0-9a-fA-F:.]+(/\\d{1,3})?$");

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
        int applied = 0;
        List<PrivateProxy> proxies = proxyMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PrivateProxy>()
                .eq("state", "active").like("route_rules", "\"preset\""));
        for (PrivateProxy proxy : proxies) {
            boolean uses = false;
            for (JSONObject rule : rules(proxy.getRouteRules())) {
                if (!"preset".equals(rule.getString("match"))) continue;
                for (String key : rule.getJSONArray("values").toJavaList(String.class)) if (presetKeys.contains(key)) uses = true;
            }
            if (!uses || !active(proxy)) continue;
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
                String item = value.trim().toLowerCase(Locale.ROOT);
                if (item.isEmpty()) continue;
                if ("domain_suffix".equals(match)) item = StringUtils.removeStart(item, "*.");
                if ("domain_suffix".equals(match)) item = StringUtils.removeStart(item, ".");
                boolean valid = "preset".equals(match) ? ProxyRoutePresets.get(item) != null
                        : "ip_cidr".equals(match) ? CIDR.matcher(item).matches()
                        : "domain_keyword".equals(match) ? item.length() <= 100 && !item.contains(" ")
                        : DOMAIN.matcher(item).matches();
                if (!valid) throw new IllegalArgumentException(label + "里有不正确的值：" + value.trim());
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
            result.add(stored);
        }
        return result.toJSONString();
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
        for (JSONObject rule : rules) {
            String match = rule.getString("match");
            List<String> values = rule.getJSONArray("values").toJavaList(String.class);
            Map<String, List<String>> matches = new LinkedHashMap<>();
            if ("preset".equals(match)) {
                Set<String> suffix = new LinkedHashSet<>();
                Set<String> domain = new LinkedHashSet<>();
                Set<String> keyword = new LinkedHashSet<>();
                for (String key : values) {
                    ProxyRoutePresets.Preset preset = ProxyRoutePresets.get(key);
                    if (preset == null) continue;
                    suffix.addAll(preset.domainSuffix);
                    domain.addAll(preset.domain);
                    keyword.addAll(preset.domainKeyword);
                }
                if (!suffix.isEmpty()) matches.put("domain_suffix", new ArrayList<>(suffix));
                if (!domain.isEmpty()) matches.put("domain", new ArrayList<>(domain));
                if (!keyword.isEmpty()) matches.put("domain_keyword", new ArrayList<>(keyword));
            } else {
                matches.put(match, values);
            }
            String exit = rule.getString("exit");
            // The router accepts up to ROUTER_RULE_VALUES entries per field; larger groups become
            // several consecutive rules with the same exit.
            for (Map.Entry<String, List<String>> field : matches.entrySet()) {
                List<String> list = field.getValue();
                for (int from = 0; from < list.size(); from += ROUTER_RULE_VALUES) {
                    JSONObject converted = new JSONObject(true);
                    converted.put(field.getKey(), new ArrayList<>(list.subList(from, Math.min(list.size(), from + ROUTER_RULE_VALUES))));
                    if ("block".equals(exit)) converted.put("action", "reject");
                    else converted.put("outbound", tagOf(exit));
                    routerRules.add(converted);
                }
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
            customs.put(id, outbound);
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
        JSONObject request = routerRequest(routerName(proxy), proxy.getExitType(), proxy.getExitCountry(), proxy.getExitCustomId(),
                rules, ports, routePassword(proxy), customs);
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
