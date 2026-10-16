package com.admin.service;

import com.admin.common.lang.R;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Keeps the built-in domain groups current: every three days the panel downloads the matching
 * v2fly domain-list-community lists, and proxies whose rules use a group that changed get their
 * routers re-applied. The panel does the download, so nodes need no access to GitHub.
 */
@Slf4j
@Service
@DependsOn("privateProxySchemaInitializer")
public class ProxyRulesetService {
    static final long REFRESH_INTERVAL_MS = 3L * 24 * 3_600_000L;
    static final int MAX_FILES = 400;
    static final int MAX_ENTRIES = 30_000;
    static final List<String> MIRRORS = List.of(
            "https://cdn.jsdelivr.net/gh/v2fly/domain-list-community@master/data/",
            "https://fastly.jsdelivr.net/gh/v2fly/domain-list-community@master/data/",
            "https://raw.githubusercontent.com/v2fly/domain-list-community/master/data/");

    private final JdbcTemplate jdbc;
    private final ProxyRoutingService routing;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile String lastError;
    private volatile Long lastRunAt;

    public ProxyRulesetService(JdbcTemplate jdbc, ProxyRoutingService routing) {
        this.jdbc = jdbc;
        this.routing = routing;
    }

    /** Parsed list: suffixes, exact domains and keywords. */
    static final class Lists {
        final Set<String> suffix = new LinkedHashSet<>();
        final Set<String> domain = new LinkedHashSet<>();
        final Set<String> keyword = new LinkedHashSet<>();
        /** Regular expressions; kept for GEOSITE lists, not used by the built-in groups. */
        final Set<String> regex = new LinkedHashSet<>();

        int size() {
            return suffix.size() + domain.size() + keyword.size() + regex.size();
        }
    }

    @PostConstruct
    public void initialize() {
        try {
            load();
        } catch (DataAccessException e) {
            log.error("Loading refreshed route presets failed", e);
        }
    }

    private void load() {
        Map<String, ProxyRoutePresets.Preset> lists = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM route_preset_data")) {
            String key = (String) row.get("preset_key");
            if (ProxyRoutePresets.builtIn(key) == null) continue;
            lists.put(key, ProxyRoutePresets.refreshedPreset(key, json(row.get("domain_suffix")), json(row.get("domain")),
                    json(row.get("domain_keyword")), ((Number) row.get("updated_at")).longValue()));
        }
        ProxyRoutePresets.useRefreshed(lists);
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 300_000L)
    public void refreshWhenDue() {
        try {
            Long oldest = jdbc.queryForObject("SELECT MIN(checked_at) FROM route_preset_data", Long.class);
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM route_preset_data", Long.class);
            boolean due = oldest == null || count == null || count < ProxyRoutePresets.sources().size()
                    || System.currentTimeMillis() - oldest >= REFRESH_INTERVAL_MS;
            if (due) refresh();
        } catch (RuntimeException e) {
            log.warn("Route preset refresh skipped: {}", e.getMessage());
        }
    }

    /** Downloads every group now. */
    public R refreshNow() {
        Map<String, Object> result = refresh();
        return R.ok(result);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("intervalDays", REFRESH_INTERVAL_MS / 86_400_000L);
        status.put("lastRunAt", lastRunAt);
        status.put("lastError", lastError);
        status.put("presets", ProxyRoutePresets.list());
        return status;
    }

    synchronized Map<String, Object> refresh() {
        long now = System.currentTimeMillis();
        Map<String, String> cache = new HashMap<>();
        Set<String> changed = new HashSet<>();
        List<String> errors = new ArrayList<>();
        int updated = 0;
        for (Map.Entry<String, List<String>> entry : ProxyRoutePresets.sources().entrySet()) {
            String key = entry.getKey();
            try {
                Lists lists = new Lists();
                int[] files = {0};
                for (String source : entry.getValue()) parse(source, name -> fetch(name, cache), lists, new HashSet<>(), files);
                if (lists.size() == 0) throw new IllegalStateException("清单为空");
                List<String> suffix = new ArrayList<>(lists.suffix);
                List<String> domain = new ArrayList<>(lists.domain);
                List<String> keyword = new ArrayList<>(lists.keyword);
                ProxyRoutePresets.Preset before = ProxyRoutePresets.get(key);
                boolean same = before != null && before.updatedAt != null && before.domainSuffix.equals(suffix)
                        && before.domain.equals(domain) && before.domainKeyword.equals(keyword);
                long updatedAt = same ? before.updatedAt : now;
                jdbc.update("INSERT INTO route_preset_data (preset_key,domain_suffix,domain,domain_keyword,updated_at,checked_at,last_error) "
                                + "VALUES (?,?,?,?,?,?,NULL) ON DUPLICATE KEY UPDATE domain_suffix=VALUES(domain_suffix), domain=VALUES(domain), "
                                + "domain_keyword=VALUES(domain_keyword), updated_at=VALUES(updated_at), checked_at=VALUES(checked_at), last_error=NULL",
                        key, JSON.toJSONString(suffix), JSON.toJSONString(domain), JSON.toJSONString(keyword), updatedAt, now);
                if (!same) {
                    changed.add(key);
                    updated++;
                }
            } catch (RuntimeException e) {
                errors.add(key + "：" + e.getMessage());
                jdbc.update("UPDATE route_preset_data SET checked_at=?, last_error=? WHERE preset_key=?", now, truncate(e.getMessage()), key);
            }
        }
        load();
        lastRunAt = now;
        lastError = errors.isEmpty() ? null : truncate(String.join("；", errors));
        int reapplied = changed.isEmpty() ? 0 : routing.reapplyForPresets(changed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("reapplied", reapplied);
        result.put("errors", errors);
        result.put("presets", ProxyRoutePresets.list());
        return result;
    }

    /**
     * Reads one v2fly list and the lists it includes. Plain entries and "domain:" are suffixes,
     * "full:" exact domains, "keyword:" keywords; regular expressions and ad-tracking entries
     * (tagged @ads) are skipped.
     */
    static void parse(String name, Function<String, String> fetch, Lists out, Set<String> visited, int[] files) {
        parse(name, null, fetch, out, visited, files);
    }

    /** With an attribute (e.g. "cn" for google@cn), keeps only the entries tagged with it. */
    static void parse(String name, String attribute, Function<String, String> fetch, Lists out, Set<String> visited, int[] files) {
        if (!visited.add(name)) return;
        if (++files[0] > MAX_FILES) throw new IllegalStateException("引用的清单太多");
        String text = fetch.apply(name);
        for (String raw : text.split("\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            String entry = parts[0];
            boolean ads = false;
            boolean tagged = attribute == null;
            for (int i = 1; i < parts.length; i++) {
                if ("@ads".equals(parts[i])) ads = true;
                if (attribute != null && parts[i].equals("@" + attribute)) tagged = true;
            }
            if (entry.startsWith("include:")) {
                parse(entry.substring("include:".length()), attribute, fetch, out, visited, files);
                continue;
            }
            if (entry.startsWith("regexp:") && !ads && tagged) {
                String pattern = entry.substring("regexp:".length());
                if (RouteMatchSet.validRegex(pattern)) out.regex.add(pattern);
                continue;
            }
            if (ads || !tagged || entry.startsWith("regexp:")) continue;
            String value;
            Set<String> target;
            if (entry.startsWith("full:")) {
                value = entry.substring(5);
                target = out.domain;
            } else if (entry.startsWith("keyword:")) {
                value = entry.substring(8);
                target = out.keyword;
            } else {
                value = entry.startsWith("domain:") ? entry.substring(7) : entry;
                target = out.suffix;
            }
            value = value.toLowerCase(Locale.ROOT);
            if (value.isEmpty() || value.length() > 253 || !value.matches("[a-z0-9._-]+")) continue;
            target.add(value);
            if (out.size() > MAX_ENTRIES) throw new IllegalStateException("清单超过 " + MAX_ENTRIES + " 条");
        }
    }

    String fetch(String name, Map<String, String> cache) {
        String cached = cache.get(name);
        if (cached != null) return cached;
        if (!name.matches("[a-z0-9!._-]+")) throw new IllegalStateException("清单名不正确：" + name);
        String lastFailure = null;
        for (String mirror : MIRRORS) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(mirror + name.replace("!", "%21")))
                        .timeout(Duration.ofSeconds(20)).header("User-Agent", "CloudNest-Panel").GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    cache.put(name, response.body());
                    return response.body();
                }
                lastFailure = "HTTP " + response.statusCode();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastFailure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage());
            }
        }
        throw new IllegalStateException("下载 " + name + " 失败（" + lastFailure + "）");
    }

    private static List<String> json(Object value) {
        if (value == null) return List.of();
        try {
            return JSON.parseArray(value.toString(), String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
