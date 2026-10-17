package com.admin.service;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Downloaded routing lists beyond the built-in groups: GEOSITE (v2fly domain lists), GEOIP
 * (Loyalsoldier country address lists) and rule-set subscriptions (Clash rule providers). The
 * panel downloads them and sends the expanded values to the node router, so nodes need no access
 * to the sources. Proxies using a list that changed are re-applied.
 */
@Slf4j
@Service
@DependsOn("privateProxySchemaInitializer")
public class ProxyRuleSourceService {
    static final long LIST_REFRESH_MS = 3L * 24 * 3_600_000L;
    static final int MAX_RULESETS_PER_USER = 50;
    static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    static final Set<String> BEHAVIORS = Set.of("auto", "domain", "ipcidr", "classical");
    static final Pattern GEOSITE_NAME = Pattern.compile("^[a-z0-9!._-]{1,64}(@[a-z0-9!_-]{1,32})?$");
    static final Pattern GEOIP_NAME = Pattern.compile("^[a-z0-9-]{2,24}$");
    static final List<String> GEOIP_MIRRORS = List.of(
            "https://cdn.jsdelivr.net/gh/Loyalsoldier/geoip@release/text/",
            "https://fastly.jsdelivr.net/gh/Loyalsoldier/geoip@release/text/",
            "https://raw.githubusercontent.com/Loyalsoldier/geoip/release/text/");

    /** Loaded lists by key: geosite:NAME, geoip:CODE, ruleset:ID. */
    private static final Map<String, RouteMatchSet> LISTS = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final ProxyRulesetService presets;
    private final ProxyRoutingService routing;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ProxyRuleSourceService(JdbcTemplate jdbc, ProxyRulesetService presets, ProxyRoutingService routing) {
        this.jdbc = jdbc;
        this.presets = presets;
        this.routing = routing;
    }

    /** The loaded list for a key, or null when it was never downloaded. */
    public static RouteMatchSet list(String key) {
        if ("geoip:private".equals(key)) {
            RouteMatchSet set = new RouteMatchSet();
            set.ipPrivate = true;
            return set;
        }
        return LISTS.get(key);
    }

    static void putList(String key, RouteMatchSet set) {
        LISTS.put(key, set);
    }

    @PostConstruct
    public void initialize() {
        try {
            for (Map<String, Object> row : jdbc.queryForList("SELECT list_key, data FROM route_list_cache")) {
                LISTS.put((String) row.get("list_key"), RouteMatchSet.fromJson((String) row.get("data")));
            }
            for (Map<String, Object> row : jdbc.queryForList("SELECT id, data FROM route_ruleset WHERE data IS NOT NULL")) {
                LISTS.put("ruleset:" + ((Number) row.get("id")).longValue(), RouteMatchSet.fromJson((String) row.get("data")));
            }
        } catch (DataAccessException e) {
            log.error("Loading routing lists failed", e);
        }
    }

    // ---- Rules that use these lists ----

    /**
     * Checks that the rules' rule sets belong to the user and that their GEOSITE and GEOIP lists
     * are downloaded, downloading missing ones now. Returns an error message, or null.
     */
    public String prepare(List<JSONObject> rules, Integer userId, boolean admin) {
        Set<String> missing = new LinkedHashSet<>();
        for (JSONObject rule : rules) {
            String match = rule.getString("match");
            for (String value : rule.getJSONArray("values").toJavaList(String.class)) {
                if ("ruleset".equals(match)) {
                    Map<String, Object> row = ruleset(Long.valueOf(value));
                    if (row == null || !admin && !Objects.equals(((Number) row.get("user_id")).intValue(), userId)) {
                        return "规则集 #" + value + " 不存在或不能使用";
                    }
                    if (list("ruleset:" + value) == null) return "规则集“" + row.get("name") + "”还没有下载成功，请先在规则库里更新它";
                } else if (Set.of("geosite", "geoip", "catalog").contains(match) && list(match + ":" + value) == null) {
                    missing.add(match + ":" + value);
                }
            }
        }
        for (String key : missing) {
            try {
                download(key);
            } catch (RuntimeException e) {
                return key.toUpperCase(Locale.ROOT).replaceFirst(":", " ") + " 下载失败：" + e.getMessage();
            }
        }
        return null;
    }

    private void download(String key) {
        RouteMatchSet set = fetchList(key, new HashMap<>());
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO route_list_cache (list_key,data,entry_count,updated_at,checked_at,last_error) VALUES (?,?,?,?,?,NULL) "
                        + "ON DUPLICATE KEY UPDATE updated_at=IF(data <=> VALUES(data), updated_at, VALUES(updated_at)), "
                        + "data=VALUES(data), entry_count=VALUES(entry_count), checked_at=VALUES(checked_at), last_error=NULL",
                key, set.toJson().toJSONString(), set.size(), now, now);
        LISTS.put(key, set);
    }

    RouteMatchSet fetchList(String key, Map<String, String> cache) {
        String kind = StringUtils.substringBefore(key, ":");
        String name = StringUtils.substringAfter(key, ":");
        RouteMatchSet set = new RouteMatchSet();
        if ("geosite".equals(kind)) {
            if (!GEOSITE_NAME.matcher(name).matches()) throw new IllegalArgumentException("名称不正确");
            String list = StringUtils.substringBefore(name, "@");
            String attribute = name.contains("@") ? StringUtils.substringAfter(name, "@") : null;
            ProxyRulesetService.Lists lists = new ProxyRulesetService.Lists();
            try {
                ProxyRulesetService.parse(list, attribute, item -> presets.fetch(item, cache), lists, new HashSet<>(), new int[]{0});
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException(e.getMessage().contains("HTTP 404") ? "v2fly 社区清单里没有 " + list : e.getMessage());
            }
            set.domainSuffix.addAll(lists.suffix);
            set.domain.addAll(lists.domain);
            set.domainKeyword.addAll(lists.keyword);
            set.domainRegex.addAll(lists.regex);
        } else if ("geoip".equals(kind)) {
            if (!GEOIP_NAME.matcher(name).matches()) throw new IllegalArgumentException("名称不正确");
            String text = fetchMirrors(GEOIP_MIRRORS, name + ".txt");
            for (String line : text.split("\\r?\\n")) {
                if (!line.isBlank()) set.add("ip_cidr", line);
            }
        } else if ("catalog".equals(kind)) {
            RuleCatalog.Item item = RuleCatalog.get(name);
            if (item == null) throw new IllegalArgumentException("没有这个精选规则集");
            return requireEntries(ClashRules.parseRuleSet(fetchUrls(item.urls), item.behavior).set);
        } else {
            throw new IllegalArgumentException("未知的清单 " + key);
        }
        return requireEntries(set);
    }

    private static RouteMatchSet requireEntries(RouteMatchSet set) {
        if (set.size() == 0) throw new IllegalArgumentException("清单为空");
        return set;
    }

    private String fetchMirrors(List<String> mirrors, String path) {
        return fetchUrls(mirrors.stream().map(mirror -> mirror + path).toList());
    }

    private String fetchUrls(List<String> urls) {
        String lastFailure = null;
        for (String url : urls) {
            try {
                HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30)).header("User-Agent", "CloudNest-Panel").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 404) {
                    response.body().close();
                    throw new IllegalArgumentException("清单不存在");
                }
                if (response.statusCode() == 200) return read(response.body());
                response.body().close();
                lastFailure = "HTTP " + response.statusCode();
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastFailure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage());
            }
        }
        throw new IllegalStateException("下载失败（" + lastFailure + "）");
    }

    /**
     * Refreshes downloaded lists that are in use: catalog rule sets every 12 hours, GEOSITE and
     * GEOIP every three days; then the subscriptions that are due.
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 420_000L)
    public void refreshDue() {
        long now = System.currentTimeMillis();
        try {
            Map<String, String> cache = new HashMap<>();
            Set<String> changed = new HashSet<>();
            for (Map<String, Object> row : jdbc.queryForList("SELECT list_key, checked_at FROM route_list_cache WHERE checked_at < ?",
                    now - RuleCatalog.REFRESH_MS)) {
                String key = (String) row.get("list_key");
                boolean catalog = key.startsWith("catalog:");
                if (!catalog && ((Number) row.get("checked_at")).longValue() >= now - LIST_REFRESH_MS) continue;
                if (catalog && !inUse(key)) continue;
                if (!inUse(key)) {
                    jdbc.update("DELETE FROM route_list_cache WHERE list_key=?", key);
                    LISTS.remove(key);
                    continue;
                }
                try {
                    RouteMatchSet set = fetchList(key, cache);
                    boolean same = set.toJson().equals(Objects.requireNonNullElse(LISTS.get(key), new RouteMatchSet()).toJson());
                    jdbc.update("UPDATE route_list_cache SET data=?, entry_count=?, updated_at=?, checked_at=?, last_error=NULL WHERE list_key=?",
                            set.toJson().toJSONString(), set.size(), same ? jdbc.queryForObject(
                                    "SELECT updated_at FROM route_list_cache WHERE list_key=?", Long.class, key) : now, now, key);
                    if (!same) {
                        LISTS.put(key, set);
                        changed.add(key);
                    }
                } catch (RuntimeException e) {
                    jdbc.update("UPDATE route_list_cache SET checked_at=?, last_error=? WHERE list_key=?", now, truncate(e.getMessage()), key);
                }
            }
            for (Map<String, Object> row : jdbc.queryForList("SELECT id FROM route_ruleset WHERE checked_at IS NULL "
                    + "OR checked_at + interval_hours * 3600000 <= ?", now)) {
                long id = ((Number) row.get("id")).longValue();
                if (refreshRuleset(id)) changed.add("ruleset:" + id);
            }
            if (!changed.isEmpty()) reapply(changed);
        } catch (RuntimeException e) {
            log.warn("Routing list refresh skipped: {}", e.getMessage());
        }
    }

    private boolean inUse(String key) {
        String match = StringUtils.substringBefore(key, ":");
        String value = StringUtils.substringAfter(key, ":");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM private_proxy WHERE state <> 'deleted' AND route_rules LIKE ? AND route_rules LIKE ?",
                Long.class, "%\"" + match + "\"%", "%\"" + value + "\"%");
        return count != null && count > 0;
    }

    private int reapply(Set<String> keys) {
        return routing.reapplyMatching(rule -> {
            String match = rule.getString("match");
            for (String value : rule.getJSONArray("values").toJavaList(String.class)) {
                if (keys.contains(match + ":" + value)) return true;
            }
            return false;
        });
    }

    // ---- Rule hit test ----

    /** Which of the given (unsaved) rules a destination hits, downloading lists the rules need. */
    public R testRoute(List<Map<String, Object>> input, String target, String network) {
        RouteTester.Target parsed;
        List<JSONObject> rules;
        try {
            parsed = RouteTester.parse(target, network);
            rules = ProxyRoutingService.rules(ProxyRoutingService.normalizeRules(input));
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        String error = prepare(rules, currentUserId(), isAdmin());
        if (error != null) return R.err(error);
        Map<String, Object> result = RouteTester.test(rules, parsed, host -> {
            try {
                List<String> addresses = new ArrayList<>();
                for (InetAddress address : InetAddress.getAllByName(host)) addresses.add(address.getHostAddress());
                return addresses;
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException("域名不存在");
            }
        });
        result.put("host", parsed.host);
        result.put("port", parsed.port);
        result.put("network", parsed.network);
        return R.ok(result);
    }

    // ---- Catalog ----

    /** The catalog with each entry's download state. */
    public List<Map<String, Object>> catalogViews() {
        Map<String, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT list_key, entry_count, updated_at, checked_at, last_error "
                + "FROM route_list_cache WHERE list_key LIKE 'catalog:%'")) {
            rows.put(StringUtils.substringAfter((String) row.get("list_key"), ":"), row);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (RuleCatalog.Item item : RuleCatalog.items()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key", item.key);
            view.put("name", item.name);
            view.put("category", item.category);
            view.put("source", item.source);
            view.put("url", item.urls.get(0));
            Map<String, Object> row = rows.get(item.key);
            view.put("count", row == null ? null : row.get("entry_count"));
            view.put("updatedAt", row == null ? null : row.get("updated_at"));
            view.put("checkedAt", row == null ? null : row.get("checked_at"));
            view.put("lastError", row == null ? null : row.get("last_error"));
            result.add(view);
        }
        return result;
    }

    /** Downloads one catalog entry now (at most once every 10 minutes unless admin). */
    public R refreshCatalog(String key) {
        RuleCatalog.Item item = RuleCatalog.get(key);
        if (item == null) return R.err("没有这个精选规则集");
        String listKey = "catalog:" + key;
        List<Long> checked = jdbc.queryForList("SELECT checked_at FROM route_list_cache WHERE list_key=?", Long.class, listKey);
        if (!isAdmin() && !checked.isEmpty() && System.currentTimeMillis() - checked.get(0) < 600_000L && LISTS.containsKey(listKey)) {
            return R.ok(Map.of("changed", false, "reapplied", 0, "count", LISTS.get(listKey).size()));
        }
        RouteMatchSet before = LISTS.get(listKey);
        try {
            download(listKey);
        } catch (RuntimeException e) {
            jdbc.update("UPDATE route_list_cache SET checked_at=?, last_error=? WHERE list_key=?", System.currentTimeMillis(),
                    truncate(e.getMessage()), listKey);
            return R.err("下载失败：" + e.getMessage());
        }
        RouteMatchSet after = LISTS.get(listKey);
        boolean changed = before == null || !before.toJson().equals(after.toJson());
        int reapplied = changed && before != null ? reapply(Set.of(listKey)) : 0;
        return R.ok(Map.of("changed", changed, "reapplied", reapplied, "count", after.size()));
    }

    // ---- Rule-set subscriptions ----

    public R listRulesets() {
        return R.ok(rulesetViews(currentUserId(), isAdmin()));
    }

    /** Subscriptions the user can pick in rules (all of them for admins). */
    public List<Map<String, Object>> rulesetViews(Integer userId, boolean admin) {
        List<Map<String, Object>> rows = admin
                ? jdbc.queryForList("SELECT r.*, u.user AS owner_name FROM route_ruleset r LEFT JOIN user u ON u.id = r.user_id ORDER BY r.id")
                : jdbc.queryForList("SELECT r.*, u.user AS owner_name FROM route_ruleset r LEFT JOIN user u ON u.id = r.user_id "
                + "WHERE r.user_id = ? ORDER BY r.id", userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            long id = ((Number) row.get("id")).longValue();
            item.put("id", id);
            item.put("name", row.get("name"));
            item.put("url", row.get("url"));
            item.put("behavior", row.get("behavior"));
            item.put("intervalHours", row.get("interval_hours"));
            item.put("count", row.get("entry_count"));
            item.put("skipped", row.get("skipped"));
            item.put("updatedAt", row.get("updated_at"));
            item.put("checkedAt", row.get("checked_at"));
            item.put("lastError", row.get("last_error"));
            item.put("ownerUserName", row.get("owner_name"));
            RouteMatchSet set = LISTS.get("ruleset:" + id);
            item.put("sample", set == null ? List.of() : set.sample(12));
            item.put("usedBy", jdbc.queryForObject("SELECT COUNT(*) FROM private_proxy WHERE state <> 'deleted' "
                    + "AND route_rules LIKE '%\"ruleset\"%' AND route_rules LIKE ?", Long.class, "%\"" + id + "\"%"));
            result.add(item);
        }
        return result;
    }

    public R createRuleset(String name, String url, String behavior, Integer intervalHours) {
        String error = validateRuleset(name, url, behavior, intervalHours);
        if (error != null) return R.err(error);
        Integer userId = currentUserId();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM route_ruleset WHERE user_id = ?", Long.class, userId);
        if (count != null && count >= MAX_RULESETS_PER_USER) return R.err("规则集最多 " + MAX_RULESETS_PER_USER + " 个");
        ClashRules.Content content;
        try {
            content = download(url.trim(), behavior);
        } catch (RuntimeException e) {
            return R.err("下载规则集失败：" + e.getMessage());
        }
        long now = System.currentTimeMillis();
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement("INSERT INTO route_ruleset (user_id,name,url,behavior,"
                    + "interval_hours,data,entry_count,skipped,updated_at,checked_at,created_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, userId);
            statement.setString(2, StringUtils.left(name.trim(), 100));
            statement.setString(3, url.trim());
            statement.setString(4, behavior);
            statement.setInt(5, intervalHours == null ? 72 : intervalHours);
            statement.setString(6, content.set.toJson().toJSONString());
            statement.setInt(7, content.set.size());
            statement.setInt(8, content.skipped);
            statement.setLong(9, now);
            statement.setLong(10, now);
            statement.setLong(11, now);
            return statement;
        }, keys);
        long id = Objects.requireNonNull(keys.getKey()).longValue();
        LISTS.put("ruleset:" + id, content.set);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("count", content.set.size());
        result.put("skipped", content.skipped);
        return R.ok(result);
    }

    public R updateRuleset(Long id, String name, String url, String behavior, Integer intervalHours) {
        Map<String, Object> row = owned(id);
        if (row == null) return R.err("规则集不存在");
        String error = validateRuleset(name, url, behavior, intervalHours);
        if (error != null) return R.err(error);
        boolean sourceChanged = !Objects.equals(row.get("url"), url.trim()) || !Objects.equals(row.get("behavior"), behavior);
        jdbc.update("UPDATE route_ruleset SET name=?, url=?, behavior=?, interval_hours=? WHERE id=?",
                StringUtils.left(name.trim(), 100), url.trim(), behavior, intervalHours == null ? 72 : intervalHours, id);
        if (sourceChanged && refreshRuleset(id)) reapply(Set.of("ruleset:" + id));
        Map<String, Object> updated = owned(id);
        if (sourceChanged && updated != null && updated.get("last_error") != null) return R.err("已保存，但下载失败：" + updated.get("last_error"));
        return R.ok();
    }

    public R deleteRuleset(Long id) {
        if (owned(id) == null) return R.err("规则集不存在");
        for (Map<String, Object> proxy : jdbc.queryForList("SELECT route_rules FROM private_proxy WHERE state <> 'deleted' "
                + "AND route_rules LIKE '%\"ruleset\"%'")) {
            for (JSONObject rule : ProxyRoutingService.rules((String) proxy.get("route_rules"))) {
                if ("ruleset".equals(rule.getString("match")) && rule.getJSONArray("values").contains(String.valueOf(id))) {
                    return R.err("还有代理的分流规则在使用这个规则集，请先删掉那些规则");
                }
            }
        }
        jdbc.update("DELETE FROM route_ruleset WHERE id=?", id);
        LISTS.remove("ruleset:" + id);
        return R.ok();
    }

    /** Downloads one subscription now and re-applies the proxies using it when it changed. */
    public R refreshRulesetNow(Long id) {
        if (owned(id) == null) return R.err("规则集不存在");
        boolean changed = refreshRuleset(id);
        int reapplied = changed ? reapply(Set.of("ruleset:" + id)) : 0;
        Map<String, Object> row = owned(id);
        if (row != null && row.get("last_error") != null) return R.err("更新失败：" + row.get("last_error"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changed", changed);
        result.put("reapplied", reapplied);
        result.put("count", row == null ? 0 : row.get("entry_count"));
        return R.ok(result);
    }

    /** Returns true when the content changed. */
    boolean refreshRuleset(long id) {
        Map<String, Object> row = ruleset(id);
        if (row == null) return false;
        long now = System.currentTimeMillis();
        try {
            ClashRules.Content content = download((String) row.get("url"), (String) row.get("behavior"));
            String data = content.set.toJson().toJSONString();
            boolean changed = !data.equals(row.get("data"));
            jdbc.update("UPDATE route_ruleset SET data=?, entry_count=?, skipped=?, updated_at=?, checked_at=?, last_error=NULL WHERE id=?",
                    data, content.set.size(), content.skipped, changed ? now : row.get("updated_at"), now, id);
            LISTS.put("ruleset:" + id, content.set);
            return changed;
        } catch (RuntimeException e) {
            jdbc.update("UPDATE route_ruleset SET checked_at=?, last_error=? WHERE id=?", now, truncate(e.getMessage()), id);
            return false;
        }
    }

    private ClashRules.Content download(String url, String behavior) {
        ClashRules.Content content = ClashRules.parseRuleSet(fetchPublic(url), behavior);
        if (content.set.size() == 0) {
            throw new IllegalArgumentException(content.skipped > 0 ? "没有可用的规则（" + content.skipped + " 条不支持）" : "内容为空");
        }
        return content;
    }

    private static String validateRuleset(String name, String url, String behavior, Integer intervalHours) {
        if (StringUtils.isBlank(name)) return "请输入名称";
        if (StringUtils.isBlank(url) || url.trim().length() > 1000) return "请输入规则集地址";
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "地址格式不正确";
        }
        if (!Set.of("http", "https").contains(StringUtils.lowerCase(uri.getScheme())) || StringUtils.isBlank(uri.getHost())) {
            return "地址需要以 http:// 或 https:// 开头";
        }
        if (url.toLowerCase(Locale.ROOT).endsWith(".mrs") || url.toLowerCase(Locale.ROOT).endsWith(".srs")) {
            return "不支持二进制规则集（.mrs / .srs），请使用 YAML、文本或 JSON 格式的地址";
        }
        if (!BEHAVIORS.contains(behavior)) return "规则类型不正确";
        if (intervalHours != null && (intervalHours < 1 || intervalHours > 24 * 30)) return "更新间隔需要在 1 小时到 30 天之间";
        return null;
    }

    /**
     * Fetches a user-supplied address. Only public addresses are allowed, including after
     * redirects, so subscriptions cannot reach the panel's own network.
     */
    String fetchPublic(String url) {
        URI uri = URI.create(url);
        for (int redirect = 0; redirect < 5; redirect++) {
            requirePublicHost(uri);
            HttpResponse<InputStream> response;
            try {
                response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "clash.meta CloudNest-Panel").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage()));
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) throw new IllegalStateException("HTTP " + status + " 没有跳转地址");
                uri = uri.resolve(location);
                if (!Set.of("http", "https").contains(StringUtils.lowerCase(uri.getScheme()))) throw new IllegalStateException("跳转到了不支持的地址");
                continue;
            }
            if (status != 200) {
                closeQuietly(response.body());
                throw new IllegalStateException("HTTP " + status);
            }
            return read(response.body());
        }
        throw new IllegalStateException("跳转次数太多");
    }

    private static void requirePublicHost(URI uri) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (Exception e) {
            throw new IllegalStateException("无法解析域名 " + uri.getHost());
        }
        for (InetAddress address : addresses) {
            if (!publicAddress(address)) throw new IllegalStateException("不允许访问内网地址 " + address.getHostAddress());
        }
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            if ((bytes[0] & 0xfe) == 0xfc) return false;
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
            mapped &= (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
            if (mapped) {
                try {
                    return publicAddress(InetAddress.getByAddress(java.util.Arrays.copyOfRange(bytes, 12, 16)));
                } catch (Exception e) {
                    return false;
                }
            }
            return true;
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return !(first == 0 || first == 100 && second >= 64 && second < 128 || first == 192 && second == 0 && (bytes[2] & 0xff) == 0
                || first == 198 && (second == 18 || second == 19) || first >= 240);
    }

    private static String read(InputStream stream) {
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("内容超过 " + (MAX_DOWNLOAD_BYTES >> 20) + " MB");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取失败：" + e.getMessage());
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // Nothing to clean up.
        }
    }

    // ---- Clash import ----

    /** Parses pasted Clash rules and suggests an exit for each policy. */
    public R parseClash(String text, Collection<Map<String, Object>> exits) {
        ClashRules.Import parsed;
        try {
            parsed = ClashRules.parseImport(text);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        if (parsed.rules.isEmpty() && parsed.finalPolicy == null) {
            return R.err(parsed.skipped > 0 ? "没有可导入的规则：" + String.join("；", parsed.skippedSamples) : "没有找到规则");
        }
        List<Map<String, Object>> rulesets = rulesetViews(currentUserId(), isAdmin());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (ClashRules.ImportedRule rule : parsed.rules) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("match", rule.match);
            item.put("values", rule.values);
            item.put("policy", rule.policy);
            rules.add(item);
        }
        List<Map<String, Object>> policies = new ArrayList<>();
        for (String policy : parsed.policies) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", policy);
            item.put("suggested", "DIRECT".equals(policy) ? "direct" : "REJECT".equals(policy) ? "block" : suggestExit(policy, exits));
            policies.add(item);
        }
        List<Map<String, Object>> providers = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (ClashRules.ImportedRule rule : parsed.rules) if ("ruleset".equals(rule.match)) used.addAll(rule.values);
        for (String name : used) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            ClashRules.Provider provider = parsed.providers.get(name);
            RuleCatalog.Item catalog = provider == null ? null : RuleCatalog.match(provider.url);
            if (catalog != null) item.put("catalogKey", catalog.key);
            if (provider != null) {
                item.put("url", provider.url);
                item.put("behavior", BEHAVIORS.contains(provider.behavior) ? provider.behavior : "classical");
                if (provider.intervalSeconds != null) item.put("intervalHours", Math.max(1, Math.min(720, provider.intervalSeconds / 3600)));
            }
            for (Map<String, Object> existing : rulesets) {
                if (name.equals(existing.get("name")) || provider != null && provider.url.equals(existing.get("url"))) {
                    item.put("existingId", existing.get("id"));
                    break;
                }
            }
            providers.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rules", rules);
        result.put("policies", policies);
        result.put("providers", providers);
        result.put("finalPolicy", parsed.finalPolicy);
        result.put("lines", parsed.lines);
        result.put("skipped", parsed.skipped);
        result.put("skippedSamples", parsed.skippedSamples);
        return R.ok(result);
    }

    private static String suggestExit(String policy, Collection<Map<String, Object>> exits) {
        String wanted = policy.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{So}\\p{Cs}]+", "");
        for (Map<String, Object> exit : exits) {
            String name = String.valueOf(exit.get("name")).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{So}\\p{Cs}]+", "");
            if (!wanted.isEmpty() && name.equals(wanted)) return "custom:" + exit.get("id");
        }
        return null;
    }

    private Map<String, Object> ruleset(Long id) {
        if (id == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM route_ruleset WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> owned(Long id) {
        Map<String, Object> row = ruleset(id);
        if (row == null) return null;
        if (!isAdmin() && !Objects.equals(((Number) row.get("user_id")).intValue(), currentUserId())) return null;
        return row;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static boolean isAdmin() {
        return Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
    }

    private static Integer currentUserId() {
        return JwtUtil.getUserIdFromToken();
    }
}
