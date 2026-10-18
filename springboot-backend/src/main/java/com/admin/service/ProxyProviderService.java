package com.admin.service;

import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Commercial proxy providers (SOAX, Oxylabs, or any gateway described by a template) whose
 * mobile and residential IPs private proxies can use. A provider exit is a custom exit that
 * stores only provider, country, carrier and session; the gateway credentials are filled in
 * when a router is built, so changing a provider's key updates every exit.
 */
@Slf4j
@Service
public class ProxyProviderService {
    public static final String EXIT_TYPE = "provider";
    static final Set<String> KINDS = Set.of("soax", "oxylabs", "custom");
    static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    static final Pattern CARRIER = Pattern.compile("^[a-z0-9_.-]{1,64}$");
    static final Pattern SESSION = Pattern.compile("^[A-Za-z0-9]{1,32}$");
    static final Pattern TEMPLATE = Pattern.compile("^[^\\s:@]{1,300}$");

    private final JdbcTemplate jdbc;
    private final AESCrypto crypto;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ProxyProviderService(JdbcTemplate jdbc, @Value("${jwt-secret}") String secret) {
        this.jdbc = jdbc;
        this.crypto = new AESCrypto(secret + ":proxy-provider");
    }

    // ---- Providers ----

    public R list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM proxy_provider ORDER BY id")) result.add(view(row));
        return R.ok(result);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        JSONObject config = config(row);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("id"));
        item.put("name", row.get("name"));
        item.put("kind", row.get("kind"));
        item.put("network", row.get("network"));
        item.put("stickyMinutes", row.get("sticky_minutes"));
        item.put("usageUsedBytes", row.get("usage_used_bytes"));
        item.put("usageLimitBytes", row.get("usage_limit_bytes"));
        item.put("usageAt", row.get("usage_at"));
        item.put("usageError", row.get("usage_error"));
        item.put("usageSupported", usageSupported((String) row.get("kind"), config));
        // Settings that are not secret, so the form can be edited; keys are only shown masked.
        Map<String, Object> settings = new LinkedHashMap<>();
        switch ((String) row.get("kind")) {
            case "soax" -> {
                settings.put("packageId", config.getString("packageId"));
                settings.put("packageKey", mask(config.getString("packageKey")));
                settings.put("apiKey", mask(config.getString("apiKey")));
            }
            case "oxylabs" -> {
                settings.put("username", config.getString("username"));
                settings.put("password", mask(config.getString("password")));
            }
            default -> {
                settings.put("host", config.getString("host"));
                settings.put("port", config.getInteger("port"));
                settings.put("protocol", config.getString("protocol"));
                settings.put("usernameTemplate", config.getString("usernameTemplate"));
                settings.put("passwordTemplate", mask(config.getString("passwordTemplate")));
            }
        }
        item.put("settings", settings);
        item.put("exits", jdbc.queryForObject("SELECT COUNT(*) FROM proxy_exit WHERE protocol = ? AND members = ?", Long.class,
                EXIT_TYPE, "provider:" + row.get("id")));
        return item;
    }

    static String mask(String secret) {
        if (StringUtils.isBlank(secret)) return null;
        return secret.length() <= 4 ? "****" : secret.substring(0, 2) + "****" + secret.substring(secret.length() - 2);
    }

    /**
     * Creates (no id) or updates a provider. Secret fields left empty or still masked keep their
     * saved value.
     */
    public R save(Long id, String name, String kind, String network, Integer stickyMinutes, Map<String, Object> settings) {
        if (StringUtils.isBlank(name)) return R.err("请输入名称");
        if (!KINDS.contains(kind)) return R.err("不支持的服务商");
        String net = "residential".equals(network) ? "residential" : "mobile";
        int sticky = stickyMinutes == null ? 10 : stickyMinutes;
        if (sticky < 0 || sticky > 1440) return R.err("保持 IP 时长需要在 0 到 1440 分钟之间");
        Map<String, Object> existing = id == null ? null : row(id);
        if (id != null && existing == null) return R.err("服务商不存在");
        if (existing != null && !kind.equals(existing.get("kind"))) return R.err("不能修改服务商类型，请新建一个");
        JSONObject previous = existing == null ? new JSONObject() : config(existing);
        JSONObject config = new JSONObject(true);
        Map<String, Object> input = settings == null ? Map.of() : settings;
        switch (kind) {
            case "soax" -> {
                config.put("packageKey", secret(input, "packageKey", previous));
                config.put("packageId", text(input, "packageId"));
                config.put("apiKey", secret(input, "apiKey", previous));
                if (StringUtils.isBlank(config.getString("packageKey"))) return R.err("请填写 SOAX 套餐的 Package key（代理密码）");
                if (StringUtils.isNotBlank(config.getString("packageId")) && !config.getString("packageId").matches("[A-Za-z0-9_-]{1,64}")) {
                    return R.err("Package ID 格式不正确");
                }
            }
            case "oxylabs" -> {
                String username = StringUtils.removeStart(text(input, "username"), "customer-");
                config.put("username", username);
                config.put("password", secret(input, "password", previous));
                if (StringUtils.isBlank(username) || !username.matches("[A-Za-z0-9_.-]{1,64}")) return R.err("请填写正确的 Oxylabs 用户名");
                if (StringUtils.isBlank(config.getString("password"))) return R.err("请填写 Oxylabs 密码");
            }
            default -> {
                String host = text(input, "host");
                Integer port = input.get("port") == null ? null : Integer.valueOf(input.get("port").toString());
                String protocol = "socks5".equals(text(input, "protocol")) ? "socks5" : "http";
                String usernameTemplate = text(input, "usernameTemplate");
                String passwordTemplate = secret(input, "passwordTemplate", previous);
                if (StringUtils.isBlank(host) || !host.matches("[A-Za-z0-9.-]{1,253}")) return R.err("请填写正确的网关地址");
                if (port == null || port < 1 || port > 65535) return R.err("请填写正确的端口");
                if (StringUtils.isBlank(usernameTemplate) || !TEMPLATE.matcher(usernameTemplate).matches()) return R.err("用户名模板不能为空，也不能包含空格、冒号或 @");
                if (StringUtils.isBlank(passwordTemplate) || passwordTemplate.length() > 300) return R.err("请填写密码模板");
                config.put("host", host);
                config.put("port", port);
                config.put("protocol", protocol);
                config.put("usernameTemplate", usernameTemplate);
                config.put("passwordTemplate", passwordTemplate);
            }
        }
        long now = System.currentTimeMillis();
        String encrypted = crypto.encrypt(config.toJSONString());
        if (id == null) {
            jdbc.update("INSERT INTO proxy_provider (name, kind, network, config, sticky_minutes, created_time, updated_time) VALUES (?,?,?,?,?,?,?)",
                    StringUtils.left(name.trim(), 100), kind, net, encrypted, sticky, now, now);
        } else {
            jdbc.update("UPDATE proxy_provider SET name=?, network=?, config=?, sticky_minutes=?, updated_time=? WHERE id=?",
                    StringUtils.left(name.trim(), 100), net, encrypted, sticky, now, id);
        }
        return R.ok();
    }

    private static String secret(Map<String, Object> input, String key, JSONObject previous) {
        String value = text(input, key);
        // Empty or the masked value shown in the form: keep what was saved.
        if (StringUtils.isBlank(value) || value.contains("****")) return previous.getString(key);
        return value;
    }

    private static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? null : value.toString().trim();
    }

    public R delete(Long id) {
        if (row(id) == null) return R.err("服务商不存在");
        Long exits = jdbc.queryForObject("SELECT COUNT(*) FROM proxy_exit WHERE protocol = ? AND members = ?", Long.class, EXIT_TYPE, "provider:" + id);
        if (exits != null && exits > 0) return R.err("请先删除这个服务商下的 " + exits + " 个出口");
        jdbc.update("DELETE FROM proxy_provider WHERE id = ?", id);
        return R.ok();
    }

    Map<String, Object> row(Long id) {
        if (id == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM proxy_provider WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private JSONObject config(Map<String, Object> row) {
        try {
            return JSON.parseObject(crypto.decryptString((String) row.get("config")));
        } catch (RuntimeException e) {
            return new JSONObject();
        }
    }

    // ---- Exits ----

    /** Validates an exit's country and carrier for the provider; returns an error message or null. */
    String validateExit(Map<String, Object> provider, String country, String carrier) {
        if (provider == null) return "服务商不存在";
        if (country == null || !COUNTRY.matcher(country).matches()) return "请选择国家";
        if (carrier != null && !CARRIER.matcher(carrier).matches()) return "运营商代码只能包含小写字母、数字、下划线、点和横线";
        if (carrier != null && "oxylabs".equals(provider.get("kind"))) return "Oxylabs 不支持按运营商选择";
        return null;
    }

    /** What the panel shows as an exit's server: the provider's gateway. */
    String gateway(Map<String, Object> provider) {
        return switch ((String) provider.get("kind")) {
            case "soax" -> "proxy.soax.com:1337";
            case "oxylabs" -> "pr.oxylabs.io:7777";
            default -> {
                JSONObject config = config(provider);
                yield config.getString("host") + ":" + config.getInteger("port");
            }
        };
    }

    /** A new random session ID: the provider assigns a different IP to it. */
    static String newSession() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder builder = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 8; i++) builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return builder.toString();
    }

    /**
     * The router outbound for a provider exit reference
     * {"type":"provider","providerId","country","carrier","session"}, or null when the provider
     * is gone.
     */
    public JSONObject expand(JSONObject reference) {
        Map<String, Object> provider = row(reference.getLong("providerId"));
        if (provider == null) return null;
        return build((String) provider.get("kind"), (String) provider.get("network"), intValue(provider.get("sticky_minutes")),
                config(provider), reference.getString("country"), reference.getString("carrier"), reference.getString("session"));
    }

    static JSONObject build(String kind, String network, int stickyMinutes, JSONObject config, String country, String carrier, String session) {
        String sid = session != null && SESSION.matcher(session).matches() ? session : "s1";
        JSONObject outbound = new JSONObject(true);
        switch (kind) {
            case "soax" -> {
                // SOAX: rules in the username, package key as password; rotate-timed changes the IP
                // on schedule within the session (docs: developers.soax.com/proxies/mobile).
                StringBuilder user = new StringBuilder();
                if ("mobile".equals(network)) user.append("network-mob-");
                user.append("country-").append(country.toLowerCase(Locale.ROOT));
                if (StringUtils.isNotBlank(carrier)) user.append("-isp-").append(carrier);
                user.append("-session-").append(sid);
                if (stickyMinutes > 0) user.append("-rotate-timed_").append(stickyMinutes).append("m");
                outbound.put("type", "http");
                outbound.put("server", "proxy.soax.com");
                outbound.put("server_port", 1337);
                outbound.put("username", user.toString());
                outbound.put("password", config.getString("packageKey"));
            }
            case "oxylabs" -> {
                // Oxylabs: customer-USER-cc-XX-sessid-ID-sesstime-MINUTES (max 1440).
                int minutes = stickyMinutes > 0 ? Math.min(stickyMinutes, 1440) : 1440;
                outbound.put("type", "http");
                outbound.put("server", "pr.oxylabs.io");
                outbound.put("server_port", 7777);
                outbound.put("username", "customer-" + config.getString("username") + "-cc-" + country.toUpperCase(Locale.ROOT)
                        + "-sessid-" + sid + "-sesstime-" + minutes);
                outbound.put("password", config.getString("password"));
            }
            default -> {
                outbound.put("type", "socks5".equals(config.getString("protocol")) ? "socks" : "http");
                if ("socks5".equals(config.getString("protocol"))) outbound.put("version", "5");
                outbound.put("server", config.getString("host"));
                outbound.put("server_port", config.getInteger("port"));
                outbound.put("username", fill(config.getString("usernameTemplate"), country, carrier, sid, stickyMinutes));
                outbound.put("password", fill(config.getString("passwordTemplate"), country, carrier, sid, stickyMinutes));
            }
        }
        return outbound;
    }

    /** Template placeholders: {country} {COUNTRY} {carrier} {session} {minutes} {seconds}. */
    static String fill(String template, String country, String carrier, String session, int minutes) {
        if (template == null) return null;
        return template.replace("{country}", country.toLowerCase(Locale.ROOT))
                .replace("{COUNTRY}", country.toUpperCase(Locale.ROOT))
                .replace("{carrier}", StringUtils.defaultString(carrier))
                .replace("{session}", session)
                .replace("{minutes}", String.valueOf(minutes))
                .replace("{seconds}", String.valueOf(minutes * 60));
    }

    // ---- Locations ----

    /** Countries the provider offers: from SOAX's API when configured, otherwise null (any country). */
    public R countries(Long id) {
        Map<String, Object> provider = row(id);
        if (provider == null) return R.err("服务商不存在");
        JSONObject config = config(provider);
        if (!"soax".equals(provider.get("kind")) || StringUtils.isAnyBlank(config.getString("apiKey"), config.getString("packageId"))) {
            return R.ok(null);
        }
        try {
            JSONObject json = JSON.parseObject(soax(config, "/v1/proxy/packages/" + config.getString("packageId") + "/locations/countries"));
            return R.ok(codeNames(json.getJSONArray("data")));
        } catch (RuntimeException e) {
            return R.err("读取 SOAX 国家列表失败：" + e.getMessage());
        }
    }

    /** Carriers in a country (SOAX only). */
    public R carriers(Long id, String country) {
        Map<String, Object> provider = row(id);
        if (provider == null) return R.err("服务商不存在");
        if (country == null || !COUNTRY.matcher(country).matches()) return R.err("国家代码不正确");
        JSONObject config = config(provider);
        if (!"soax".equals(provider.get("kind")) || StringUtils.isAnyBlank(config.getString("apiKey"), config.getString("packageId"))) {
            return R.ok(null);
        }
        try {
            String network = "mobile".equals(provider.get("network")) ? "?network=mob" : "";
            JSONObject json = JSON.parseObject(soax(config, "/v1/proxy/packages/" + config.getString("packageId")
                    + "/locations/countries/" + country.toLowerCase(Locale.ROOT) + "/isps" + network));
            return R.ok(codeNames(json.getJSONArray("data")));
        } catch (RuntimeException e) {
            return R.err("读取 SOAX 运营商列表失败：" + e.getMessage());
        }
    }

    private static List<Map<String, Object>> codeNames(JSONArray data) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (data == null) return list;
        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            list.add(Map.of("code", StringUtils.defaultString(item.getString("code")), "name", StringUtils.defaultString(item.getString("name"))));
        }
        return list;
    }

    // ---- Usage ----

    static boolean usageSupported(String kind, JSONObject config) {
        return "oxylabs".equals(kind) || "soax".equals(kind) && StringUtils.isNoneBlank(config.getString("apiKey"), config.getString("packageId"));
    }

    /** Reads the provider's traffic usage from its API (SOAX package, Oxylabs month). */
    public R refreshUsage(Long id) {
        Map<String, Object> provider = row(id);
        if (provider == null) return R.err("服务商不存在");
        String error = updateUsage(provider);
        if (error != null) return R.err(error);
        return R.ok(view(row(id)));
    }

    private String updateUsage(Map<String, Object> provider) {
        JSONObject config = config(provider);
        String kind = (String) provider.get("kind");
        if (!usageSupported(kind, config)) return "这个服务商没有可用的用量接口（SOAX 需要填写 API key 和 Package ID）";
        long now = System.currentTimeMillis();
        try {
            Long used;
            Long limit = null;
            if ("soax".equals(kind)) {
                JSONObject json = JSON.parseObject(soax(config, "/v1/proxy/packages/" + config.getString("packageId")));
                JSONObject data = json.containsKey("data") && json.get("data") instanceof JSONObject ? json.getJSONObject("data") : json;
                used = findLong(data, "traffic_spent");
                limit = findLong(data, "traffic_limit");
            } else {
                used = oxylabsMonthBytes(config);
            }
            jdbc.update("UPDATE proxy_provider SET usage_used_bytes=?, usage_limit_bytes=?, usage_at=?, usage_error=NULL WHERE id=?",
                    used, limit, now, provider.get("id"));
            return null;
        } catch (RuntimeException e) {
            jdbc.update("UPDATE proxy_provider SET usage_at=?, usage_error=? WHERE id=?", now, StringUtils.left(e.getMessage(), 500), provider.get("id"));
            return "读取用量失败：" + e.getMessage();
        }
    }

    /** Searches a JSON object (one level of nesting) for a numeric field. */
    static Long findLong(JSONObject json, String key) {
        if (json == null) return null;
        if (json.containsKey(key)) return json.getLong(key);
        for (String name : json.keySet()) {
            Object value = json.get(name);
            if (value instanceof JSONObject && ((JSONObject) value).containsKey(key)) return ((JSONObject) value).getLong(key);
        }
        return null;
    }

    private long oxylabsMonthBytes(JSONObject config) {
        String basic = Base64.getEncoder().encodeToString((config.getString("username") + ":" + config.getString("password")).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create("https://residential-api.oxylabs.io/v2/login"))
                .header("Authorization", "Basic " + basic).POST(HttpRequest.BodyPublishers.noBody()));
        if (login.statusCode() != 200) throw new IllegalStateException("Oxylabs 登录失败（HTTP " + login.statusCode() + "）");
        JSONObject auth = JSON.parseObject(login.body());
        HttpResponse<String> stats = send(HttpRequest.newBuilder(URI.create("https://residential-api.oxylabs.io/v2/users/"
                + auth.getString("user_id") + "/client-stats")).header("Authorization", "Bearer " + auth.getString("token")).GET());
        if (stats.statusCode() != 200) throw new IllegalStateException("Oxylabs 用量接口返回 HTTP " + stats.statusCode());
        // "traffic" is in GB for the current month.
        double gigabytes = JSON.parseObject(stats.body()).getDoubleValue("traffic");
        return Math.round(gigabytes * 1024 * 1024 * 1024);
    }

    private String soax(JSONObject config, String path) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create("https://api.platform.soax.com" + path))
                .header("Authorization", "Bearer " + config.getString("apiKey")).GET());
        if (response.statusCode() == 401) throw new IllegalStateException("SOAX API key 无效");
        if (response.statusCode() != 200) throw new IllegalStateException("SOAX 接口返回 HTTP " + response.statusCode());
        return response.body();
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.timeout(Duration.ofSeconds(20)).header("User-Agent", "CloudNest-Panel").build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("连接失败：" + e.getClass().getSimpleName());
        }
    }

    /** Every 30 minutes: usage of providers with an API. */
    @Scheduled(fixedDelay = 1_800_000L, initialDelay = 300_000L)
    public void refreshAllUsage() {
        try {
            for (Map<String, Object> provider : jdbc.queryForList("SELECT * FROM proxy_provider")) {
                if (usageSupported((String) provider.get("kind"), config(provider))) updateUsage(provider);
            }
        } catch (RuntimeException e) {
            log.warn("Provider usage refresh skipped: {}", e.getMessage());
        }
    }

    private static int intValue(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}
