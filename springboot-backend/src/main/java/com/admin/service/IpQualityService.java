package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class IpQualityService {
    public static final String MIN_AGENT_VERSION = "2.46.0";
    private static final List<String> DNSBL_ZONES = List.of("zen.spamhaus.org", "bl.spamcop.net", "psbl.surriel.com");
    private final JdbcTemplate jdbcTemplate;
    private final NodeMapper nodeMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> { Thread thread = new Thread(runnable, "ip-quality"); thread.setDaemon(true); return thread; });
    private final AESCrypto crypto;

    public IpQualityService(JdbcTemplate jdbcTemplate, NodeMapper nodeMapper, @Value("${jwt-secret}") String secret) {
        this.jdbcTemplate = jdbcTemplate; this.nodeMapper = nodeMapper; this.crypto = new AESCrypto(secret + ":ip-quality");
    }

    public R overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimumAgentVersion", MIN_AGENT_VERSION);
        List<Map<String, Object>> nodes = jdbcTemplate.queryForList("SELECT n.id,n.name,n.ip,n.server_ip AS serverIp,n.status,n.version,"
                + "s.id AS scanId,s.status AS scanStatus,s.public_ipv4 AS publicIpv4,s.public_ipv6 AS publicIpv6,s.country_code AS countryCode,s.country,s.region,s.city,"
                + "s.asn,s.organization,s.network_type AS networkType,s.risk_score AS riskScore,s.risk_level AS riskLevel,s.confidence,"
                + "s.risk_sources_json AS riskSources,s.blacklist_json AS blacklist,s.unlock_json AS unlockResults,s.dns_json AS dns,s.ports_json AS ports,"
                + "s.error AS scanError,s.started_at AS startedAt,s.finished_at AS finishedAt FROM node n LEFT JOIN ip_quality_scan s ON s.id=(SELECT MAX(x.id) FROM ip_quality_scan x WHERE x.node_id=n.id) ORDER BY n.status DESC,n.id DESC");
        nodes.forEach(this::parseScanJson);
        result.put("nodes", nodes);
        result.put("providers", providerStatus());
        result.put("summary", Map.of("total", nodes.size(), "running", nodes.stream().filter(row -> "running".equals(row.get("scanStatus"))).count(),
                "tested", nodes.stream().filter(row -> row.get("finishedAt") != null).count(),
                "highRisk", nodes.stream().filter(row -> "high".equals(row.get("riskLevel"))).count()));
        return R.ok(result);
    }

    public R run(Long nodeId) {
        Node node = nodeMapper.selectById(nodeId);
        if (node == null) return R.err("节点不存在");
        if (!WebSocketServer.isNodeOnline(nodeId)) return R.err("节点离线，无法检测真实出口");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) return R.err("该节点 Agent 需要升级到 " + MIN_AGENT_VERSION);
        Integer running = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ip_quality_scan WHERE node_id=? AND status='running'", Integer.class, nodeId);
        if (running != null && running > 0) return R.err("该节点正在检测");
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO ip_quality_scan(node_id,status,started_at) VALUES(?,'running',?)", nodeId, now);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM ip_quality_scan WHERE node_id=?", Long.class, nodeId);
        executor.submit(() -> execute(Objects.requireNonNull(id), nodeId));
        return overview();
    }

    public R history(Long nodeId) {
        List<Map<String, Object>> scans = jdbcTemplate.queryForList("SELECT id AS scanId,node_id AS nodeId,status AS scanStatus,public_ipv4 AS publicIpv4,public_ipv6 AS publicIpv6,country_code AS countryCode,country,region,city,asn,organization,network_type AS networkType,"
                + "risk_score AS riskScore,risk_level AS riskLevel,confidence,risk_sources_json AS riskSources,blacklist_json AS blacklist,unlock_json AS unlockResults,dns_json AS dns,ports_json AS ports,error AS scanError,started_at AS startedAt,finished_at AS finishedAt "
                + "FROM ip_quality_scan WHERE node_id=? ORDER BY id DESC LIMIT 30", nodeId);
        scans.forEach(this::parseScanJson);
        return R.ok(Map.of("scans", scans));
    }

    public R saveProviders(Map<String, Object> input) {
        Map<String, Object> current = providerRow();
        String ipqs = secretValue(input.get("ipqsApiKey"), Boolean.TRUE.equals(input.get("clearIpqs")), current.get("ipqs_api_key"));
        String abuse = secretValue(input.get("abuseipdbApiKey"), Boolean.TRUE.equals(input.get("clearAbuseipdb")), current.get("abuseipdb_api_key"));
        jdbcTemplate.update("INSERT INTO ip_quality_provider_setting(id,ipqs_api_key,abuseipdb_api_key,updated_time) VALUES(1,?,?,?) ON DUPLICATE KEY UPDATE ipqs_api_key=VALUES(ipqs_api_key),abuseipdb_api_key=VALUES(abuseipdb_api_key),updated_time=VALUES(updated_time)", ipqs, abuse, System.currentTimeMillis());
        return overview();
    }

    private void execute(long scanId, long nodeId) {
        try {
            GostDto response = WebSocketServer.send_msg(nodeId, Map.of(), "IpQualityInspect", 40);
            if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) throw new IllegalStateException(response == null ? "Agent 无响应" : response.getMsg());
            JSONObject agent = response.getData() instanceof JSONObject object ? object : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
            String ipv4 = validPublicIp(agent.getString("publicIpv4"), true);
            String ipv6 = validPublicIp(agent.getString("publicIpv6"), false);
            String primary = ipv4 != null ? ipv4 : ipv6;
            if (primary == null) throw new IllegalStateException("Agent 未返回有效公网出口 IP");

            JSONObject geo = queryJson(HttpRequest.newBuilder(URI.create("https://ipwho.is/" + primary)).timeout(Duration.ofSeconds(8)).GET().build());
            if (!geo.getBooleanValue("success")) geo = new JSONObject();
            JSONObject connection = geo.getJSONObject("connection");
            JSONObject riskSources = new JSONObject();
            Integer ipqsScore = queryIpqs(primary, riskSources);
            Integer abuseScore = queryAbuse(primary, riskSources);
            JSONArray blacklist = queryBlacklists(ipv4);
            int blacklistHits = (int) blacklist.stream().filter(item -> ((JSONObject) item).getBooleanValue("listed")).count();
            IpRiskScoring.Result risk = IpRiskScoring.calculate(ipqsScore, abuseScore, blacklistHits);
            long finished = System.currentTimeMillis();
            jdbcTemplate.update("UPDATE ip_quality_scan SET status='success',public_ipv4=?,public_ipv6=?,country_code=?,country=?,region=?,city=?,asn=?,organization=?,network_type=?,risk_score=?,risk_level=?,confidence=?,risk_sources_json=?,blacklist_json=?,unlock_json=?,dns_json=?,ports_json=?,error=NULL,finished_at=? WHERE id=?",
                    ipv4, ipv6, blank(geo.getString("country_code")), blank(geo.getString("country")), blank(geo.getString("region")), blank(geo.getString("city")),
                    connection == null ? null : blank(connection.getString("asn")), connection == null ? null : first(connection.getString("org"), connection.getString("isp")),
                    connection == null ? null : blank(connection.getString("type")), risk.score(), risk.level(), risk.confidence(), riskSources.toJSONString(), blacklist.toJSONString(),
                    json(agent.get("services")), json(agent.get("dns")), json(agent.get("ports")), finished, scanId);
        } catch (Exception e) {
            jdbcTemplate.update("UPDATE ip_quality_scan SET status='failed',error=?,finished_at=? WHERE id=?", concise(e.getMessage()), System.currentTimeMillis(), scanId);
            log.warn("IP quality scan {} failed: {}", scanId, e.getMessage());
        }
    }

    private Integer queryIpqs(String ip, JSONObject sources) {
        String key = decrypt(providerRow().get("ipqs_api_key"));
        JSONObject source = new JSONObject(); source.put("name", "IPQualityScore"); source.put("configured", StringUtils.isNotBlank(key));
        if (StringUtils.isBlank(key)) { source.put("status", "not_configured"); sources.put("ipqualityscore", source); return null; }
        try {
            JSONObject data = queryJson(HttpRequest.newBuilder(URI.create("https://www.ipqualityscore.com/api/json/ip/" + key + "/" + ip + "?strictness=1&allow_public_access_points=true&lighter_penalties=true&mobile=true")).timeout(Duration.ofSeconds(8)).GET().build());
            if (!data.getBooleanValue("success")) throw new IllegalStateException(first(data.getString("message"), "服务返回失败"));
            int score = data.getIntValue("fraud_score"); source.put("status", "success"); source.put("score", score); source.put("proxy", data.getBoolean("proxy")); source.put("vpn", data.getBoolean("vpn")); source.put("tor", data.getBoolean("tor")); source.put("recentAbuse", data.getBoolean("recent_abuse")); source.put("bot", data.getBoolean("bot_status"));
            sources.put("ipqualityscore", source); return score;
        } catch (Exception e) { source.put("status", "failed"); source.put("error", concise(e.getMessage())); sources.put("ipqualityscore", source); return null; }
    }

    private Integer queryAbuse(String ip, JSONObject sources) {
        String key = decrypt(providerRow().get("abuseipdb_api_key"));
        JSONObject source = new JSONObject(); source.put("name", "AbuseIPDB"); source.put("configured", StringUtils.isNotBlank(key));
        if (StringUtils.isBlank(key)) { source.put("status", "not_configured"); sources.put("abuseipdb", source); return null; }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.abuseipdb.com/api/v2/check?ipAddress=" + ip + "&maxAgeInDays=90&verbose=true")).timeout(Duration.ofSeconds(8)).header("Key", key).header("Accept", "application/json").GET().build();
            JSONObject root = queryJson(request); JSONObject data = root.getJSONObject("data");
            if (data == null) throw new IllegalStateException("服务未返回 data");
            int score = data.getIntValue("abuseConfidenceScore"); source.put("status", "success"); source.put("score", score); source.put("totalReports", data.getIntValue("totalReports")); source.put("lastReportedAt", data.getString("lastReportedAt")); source.put("usageType", data.getString("usageType"));
            sources.put("abuseipdb", source); return score;
        } catch (Exception e) { source.put("status", "failed"); source.put("error", concise(e.getMessage())); sources.put("abuseipdb", source); return null; }
    }

    private JSONArray queryBlacklists(String ipv4) {
        JSONArray results = new JSONArray();
        for (String zone : DNSBL_ZONES) {
            JSONObject item = new JSONObject(); item.put("provider", zone); item.put("listed", false);
            if (ipv4 == null) { item.put("status", "skipped"); item.put("detail", "仅支持 IPv4"); results.add(item); continue; }
            try {
                String[] octets = ipv4.split("\\."); String query = octets[3] + "." + octets[2] + "." + octets[1] + "." + octets[0] + "." + zone;
                Hashtable<String, String> env = new Hashtable<>(); env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory"); env.put("com.sun.jndi.dns.timeout.initial", "1800"); env.put("com.sun.jndi.dns.timeout.retries", "1");
                Attributes attributes = new InitialDirContext(env).getAttributes(query, new String[]{"A"});
                String answer = attributes.get("A") == null ? null : String.valueOf(attributes.get("A").get());
                if (answer != null && answer.startsWith("127.255.255.")) {
                    item.put("status", "failed"); item.put("detail", "查询源拒绝当前 DNS 解析器");
                } else {
                    boolean listed = answer != null; item.put("listed", listed); item.put("status", "success"); if (listed) item.put("answer", answer);
                }
            } catch (javax.naming.NameNotFoundException e) { item.put("status", "success"); }
            catch (Exception e) { item.put("status", "failed"); item.put("detail", concise(e.getMessage())); }
            results.add(item);
        }
        return results;
    }

    private JSONObject queryJson(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
        return JSONObject.parseObject(response.body());
    }

    private Map<String, Object> providerStatus() {
        Map<String, Object> row = providerRow();
        return Map.of("ipqsConfigured", StringUtils.isNotBlank(Objects.toString(row.get("ipqs_api_key"), "")), "abuseipdbConfigured", StringUtils.isNotBlank(Objects.toString(row.get("abuseipdb_api_key"), "")));
    }

    private Map<String, Object> providerRow() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT ipqs_api_key,abuseipdb_api_key FROM ip_quality_provider_setting WHERE id=1");
        return rows.isEmpty() ? Map.of("ipqs_api_key", "", "abuseipdb_api_key", "") : rows.get(0);
    }

    private String secretValue(Object value, boolean clear, Object existing) {
        if (clear) return null;
        String raw = value == null ? "" : String.valueOf(value).trim();
        return raw.isEmpty() ? Objects.toString(existing, null) : crypto.encrypt(raw);
    }

    private String decrypt(Object encrypted) {
        if (encrypted == null || StringUtils.isBlank(String.valueOf(encrypted))) return null;
        try { return crypto.decryptString(String.valueOf(encrypted)); } catch (Exception e) { return null; }
    }

    private void parseScanJson(Map<String, Object> row) {
        parse(row, "riskSources", new JSONObject()); parse(row, "blacklist", new JSONArray()); parse(row, "unlockResults", new JSONArray()); parse(row, "dns", new JSONObject()); parse(row, "ports", new JSONArray());
    }

    private void parse(Map<String, Object> row, String key, Object fallback) {
        Object value = row.get(key);
        if (value == null || StringUtils.isBlank(String.valueOf(value))) { row.put(key, fallback); return; }
        try { row.put(key, fallback instanceof JSONArray ? JSONArray.parseArray(String.valueOf(value)) : JSONObject.parseObject(String.valueOf(value))); }
        catch (Exception e) { row.put(key, fallback); }
    }

    private String validPublicIp(String value, boolean ipv4) {
        try { InetAddress parsed = InetAddress.getByName(value); if (parsed.isAnyLocalAddress() || parsed.isLoopbackAddress() || parsed.isLinkLocalAddress() || parsed.isSiteLocalAddress() || (ipv4 != (parsed instanceof Inet4Address))) return null; return parsed.getHostAddress(); }
        catch (Exception e) { return null; }
    }

    private static String json(Object value) { return value == null ? "[]" : JSONObject.toJSONString(value); }
    private static String blank(String value) { return StringUtils.isBlank(value) ? null : value; }
    private static String first(String left, String right) { return StringUtils.isNotBlank(left) ? left : blank(right); }
    private static String concise(String value) { if (StringUtils.isBlank(value)) return "未知错误"; value = value.trim(); return value.length() > 480 ? value.substring(0, 480) : value; }

    @PreDestroy public void shutdown() { executor.shutdownNow(); }
}
