package com.admin.service;

import com.admin.common.dto.DynamicDnsProviderSaveDto;
import com.admin.common.dto.DynamicDnsRuleSaveDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class DynamicDnsService {
    private static final String MIN_AGENT_VERSION = "2.21.0";
    private static final String CLOUDFLARE_API = "https://api.cloudflare.com/client/v4";
    private static final String DNSPOD_API = "https://dnspod.tencentcloudapi.com";
    private static final String ALIYUN_API = "https://alidns.aliyuncs.com";

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final TelegramNotificationService telegramNotificationService;

    @Value("${jwt-secret}")
    private String encryptionSecret;

    public DynamicDnsService(JdbcTemplate jdbcTemplate, RestTemplate restTemplate,
                             TelegramNotificationService telegramNotificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.telegramNotificationService = telegramNotificationService;
    }

    public R overview() {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT r.id,r.name,r.node_id AS nodeId,r.provider_source AS providerSource,r.provider_ref_id AS providerRefId,"
                        + "r.provider,r.zone_ref_id AS zoneRefId,r.zone_name AS zoneName,r.record_name AS recordName,r.record_type AS recordType,"
                        + "r.ttl,r.check_interval_seconds AS checkIntervalSeconds,r.enabled,r.last_detected_ip AS lastDetectedIp,"
                        + "r.last_applied_ip AS lastAppliedIp,r.last_status AS lastStatus,r.last_error AS lastError,"
                        + "r.last_checked_at AS lastCheckedAt,r.last_updated_at AS lastUpdatedAt,n.name AS nodeName,n.version AS nodeVersion,"
                        + "n.status AS nodeStatus,p.name AS providerAccountName "
                        + "FROM dynamic_dns_rule r LEFT JOIN node n ON n.id=r.node_id "
                        + "LEFT JOIN dynamic_dns_provider p ON r.provider_source='dynamic' AND p.id=r.provider_ref_id "
                        + "ORDER BY r.updated_time DESC");
        rules.forEach(row -> {
            if ("dns".equals(row.get("providerSource"))) {
                List<String> names = jdbcTemplate.query("SELECT name FROM dns_provider_account WHERE id=?",
                        (rs, index) -> rs.getString(1), row.get("providerRefId"));
                row.put("providerAccountName", names.isEmpty() ? "Cloudflare" : names.get(0));
            }
            row.put("nodeOnline", WebSocketServer.isNodeOnline(number(row.get("nodeId"))));
        });
        List<Map<String, Object>> providers = providerOptions();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rules", rules.size());
        summary.put("active", rules.stream().filter(row -> truth(row.get("enabled"))).count());
        summary.put("healthy", rules.stream().filter(row -> "success".equals(row.get("lastStatus"))).count());
        summary.put("errors", rules.stream().filter(row -> "error".equals(row.get("lastStatus"))).count());
        return R.ok(Map.of("rules", rules, "providers", providers, "summary", summary,
                "minimumAgentVersion", MIN_AGENT_VERSION));
    }

    public R history(Long ruleId) {
        return R.ok(jdbcTemplate.queryForList(
                "SELECT id,rule_id AS ruleId,old_ip AS oldIp,new_ip AS newIp,status,error,created_time AS createdTime "
                        + "FROM dynamic_dns_history WHERE rule_id=? ORDER BY created_time DESC LIMIT 200", ruleId));
    }

    public R lineRoutingProviders() {
        return R.ok(jdbcTemplate.queryForList(
                "SELECT id,name,provider,enabled,last_error AS lastError FROM dynamic_dns_provider "
                        + "WHERE enabled=1 AND provider IN ('dnspod','aliyun') ORDER BY provider,name"));
    }

    public String normalizeLineRoutingDomain(String zone, String input) {
        String normalizedZone = StringUtils.trimToEmpty(zone).toLowerCase(Locale.ROOT);
        if (!validDomain(normalizedZone)) throw new IllegalArgumentException("主域名格式不正确");
        return normalizeRecord(input, normalizedZone);
    }

    public LineRoutingRecord ensureLineRoutingRecord(Long providerRefId, String zone, String fqdn, String type,
                                                     String carrier, String value, int ttl, String knownRecordId) {
        ProviderAccess access = loadProvider("dynamic", providerRefId, null, null);
        if (!List.of("dnspod", "aliyun").contains(access.provider)) {
            throw new IllegalArgumentException("运营商线路解析仅支持 DNSPod 和阿里云 DNS");
        }
        String line = normalizeCarrierLine(access.provider, carrier);
        validateIp(type, value);
        return "dnspod".equals(access.provider)
                ? updateDnsPodLine(access, zone, fqdn, type, value, ttl, knownRecordId, line)
                : updateAliyunLine(access, zone, fqdn, type, value, ttl, knownRecordId, line);
    }

    public void deleteLineRoutingRecord(Long providerRefId, String zone, String recordId) {
        if (providerRefId == null || StringUtils.isBlank(recordId)) return;
        ProviderAccess access = loadProvider("dynamic", providerRefId, null, null);
        if ("dnspod".equals(access.provider)) {
            dnsPod(access, "DeleteRecord", Map.of("Domain", zone, "RecordId", Long.parseLong(recordId)));
        } else if ("aliyun".equals(access.provider)) {
            aliyun(access, "DeleteDomainRecord", Map.of("RecordId", recordId));
        }
    }

    public R saveProvider(DynamicDnsProviderSaveDto dto) {
        String provider = normalizeProvider(dto.getProvider());
        String name = StringUtils.trimToEmpty(dto.getName());
        if (name.isEmpty()) return R.err("请填写配置名称");
        String credentialA = StringUtils.trimToNull(dto.getCredentialA());
        String credentialB = StringUtils.trimToNull(dto.getCredentialB());
        if (dto.getId() == null && credentialA == null) return R.err(providerCredentialHint(provider));
        if (dto.getId() == null && !"cloudflare".equals(provider) && credentialB == null) return R.err(providerCredentialHint(provider));
        long now = System.currentTimeMillis();
        if (dto.getId() == null) {
            jdbcTemplate.update("INSERT INTO dynamic_dns_provider (name,provider,credential_a,credential_b,enabled,created_time,updated_time) VALUES (?,?,?,?,?,?,?)",
                    name, provider, encrypt(credentialA), credentialB == null ? null : encrypt(credentialB),
                    !Boolean.FALSE.equals(dto.getEnabled()), now, now);
        } else {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT credential_a,credential_b FROM dynamic_dns_provider WHERE id=?", dto.getId());
            if (rows.isEmpty()) return R.err("DNS 提供商配置不存在");
            String encryptedA = credentialA == null ? Objects.toString(rows.get(0).get("credential_a")) : encrypt(credentialA);
            String encryptedB = credentialB == null ? Objects.toString(rows.get(0).get("credential_b"), null) : encrypt(credentialB);
            jdbcTemplate.update("UPDATE dynamic_dns_provider SET name=?,provider=?,credential_a=?,credential_b=?,enabled=?,last_error=NULL,updated_time=? WHERE id=?",
                    name, provider, encryptedA, encryptedB, !Boolean.FALSE.equals(dto.getEnabled()), now, dto.getId());
        }
        return R.ok();
    }

    @Transactional
    public R deleteProvider(Long id) {
        Integer used = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dynamic_dns_rule WHERE provider_source='dynamic' AND provider_ref_id=?", Integer.class, id);
        if (used != null && used > 0) return R.err("该配置仍被动态 DNS 规则使用");
        Integer smartEntryUsed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_entry_group WHERE provider_ref_id=?", Integer.class, id);
        if (smartEntryUsed != null && smartEntryUsed > 0) return R.err("该配置仍被入口接入使用");
        return jdbcTemplate.update("DELETE FROM dynamic_dns_provider WHERE id=?", id) > 0 ? R.ok() : R.err("DNS 提供商配置不存在");
    }

    public R saveRule(DynamicDnsRuleSaveDto dto) {
        if (dto.getNodeId() == null) return R.err("请选择检测节点");
        String source = "dns".equals(dto.getProviderSource()) ? "dns" : "dynamic";
        ProviderAccess access;
        try { access = loadProvider(source, dto.getProviderRefId(), dto.getZoneRefId(), dto.getProvider()); }
        catch (IllegalArgumentException e) { return R.err(e.getMessage()); }
        String zone = StringUtils.trimToEmpty(dto.getZoneName()).toLowerCase(Locale.ROOT);
        if ("dns".equals(source)) zone = access.zoneName;
        if (!validDomain(zone)) return R.err("主域名格式不正确");
        String record;
        try { record = normalizeRecord(dto.getRecordName(), zone); }
        catch (IllegalArgumentException e) { return R.err(e.getMessage()); }
        String type = StringUtils.defaultIfBlank(dto.getRecordType(), "A").toUpperCase(Locale.ROOT);
        if (!List.of("A", "AAAA").contains(type)) return R.err("动态 DNS 仅支持 A 和 AAAA 记录");
        int ttl = Math.max(60, Math.min(86400, dto.getTtl() == null ? 600 : dto.getTtl()));
        int interval = Math.max(30, Math.min(86400, dto.getCheckIntervalSeconds() == null ? 60 : dto.getCheckIntervalSeconds()));
        String name = StringUtils.defaultIfBlank(dto.getName(), record).trim();
        long now = System.currentTimeMillis();
        try {
            if (dto.getId() == null) {
                jdbcTemplate.update("INSERT INTO dynamic_dns_rule (name,node_id,provider_source,provider_ref_id,provider,zone_ref_id,zone_name,record_name,"
                                + "record_type,ttl,check_interval_seconds,enabled,last_status,created_time,updated_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'pending',?,?)",
                        name, dto.getNodeId(), source, dto.getProviderRefId(), access.provider, dto.getZoneRefId(), zone, record,
                        type, ttl, interval, !Boolean.FALSE.equals(dto.getEnabled()), now, now);
            } else {
                int updated = jdbcTemplate.update("UPDATE dynamic_dns_rule SET name=?,node_id=?,provider_source=?,provider_ref_id=?,provider=?,zone_ref_id=?,"
                                + "zone_name=?,record_name=?,record_type=?,ttl=?,check_interval_seconds=?,enabled=?,last_detected_ip=NULL,last_applied_ip=NULL,"
                                + "provider_record_id=NULL,last_status='pending',last_error=NULL,last_checked_at=NULL,last_updated_at=NULL,updated_time=? WHERE id=?",
                        name, dto.getNodeId(), source, dto.getProviderRefId(), access.provider, dto.getZoneRefId(), zone, record,
                        type, ttl, interval, !Boolean.FALSE.equals(dto.getEnabled()), now, dto.getId());
                if (updated == 0) return R.err("动态 DNS 规则不存在");
            }
            return R.ok();
        } catch (Exception e) {
            if (StringUtils.containsIgnoreCase(e.getMessage(), "uk_dynamic_dns_record")) return R.err("该域名记录已经存在动态 DNS 规则");
            throw e;
        }
    }

    @Transactional
    public R deleteRule(Long id) {
        jdbcTemplate.update("DELETE FROM dynamic_dns_history WHERE rule_id=?", id);
        return jdbcTemplate.update("DELETE FROM dynamic_dns_rule WHERE id=?", id) > 0 ? R.ok() : R.err("动态 DNS 规则不存在");
    }

    public R runNow(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM dynamic_dns_rule WHERE id=?", id);
        if (rows.isEmpty()) return R.err("动态 DNS 规则不存在");
        try {
            process(rows.get(0), true);
            return R.ok();
        } catch (RuntimeException e) {
            return R.err(e.getMessage());
        }
    }

    @Scheduled(initialDelay = 45_000L, fixedDelay = 30_000L)
    public void poll() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT * FROM dynamic_dns_rule WHERE enabled=1 AND (last_checked_at IS NULL OR last_checked_at + check_interval_seconds*1000 <= ?) ORDER BY last_checked_at LIMIT 20",
                now);
        for (Map<String, Object> rule : rules) {
            try { process(rule, false); } catch (RuntimeException ignored) { }
        }
    }

    private void process(Map<String, Object> rule, boolean manual) {
        long id = number(rule.get("id"));
        long nodeId = number(rule.get("node_id"));
        String previousStatus = Objects.toString(rule.get("last_status"), "pending");
        String oldIp = Objects.toString(rule.get("last_applied_ip"), null);
        long now = System.currentTimeMillis();
        try {
            if (!WebSocketServer.isNodeOnline(nodeId)) throw new IllegalStateException("检测节点离线");
            String version = jdbcTemplate.queryForObject("SELECT version FROM node WHERE id=?", String.class, nodeId);
            if (!AgentVersionUtil.isAtLeast(version, MIN_AGENT_VERSION)) {
                throw new IllegalStateException("节点 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
            }
            String type = Objects.toString(rule.get("record_type"));
            GostDto result = WebSocketServer.send_msg(nodeId, Map.of("family", "AAAA".equals(type) ? "ipv6" : "ipv4"), "PublicIpQuery", 15);
            if (result == null || !"OK".equals(result.getMsg()) || result.getData() == null) {
                throw new IllegalStateException(result == null ? "Agent 无响应" : result.getMsg());
            }
            JSONObject data = JSON.parseObject(JSON.toJSONString(result.getData()));
            String ip = StringUtils.trimToEmpty(data.getString("address"));
            validateIp(type, ip);
            jdbcTemplate.update("UPDATE dynamic_dns_rule SET last_detected_ip=?,last_checked_at=?,updated_time=? WHERE id=?", ip, now, now, id);
            if (ip.equals(oldIp)) {
                jdbcTemplate.update("UPDATE dynamic_dns_rule SET last_status='success',last_error=NULL WHERE id=?", id);
                if (manual) addHistory(id, oldIp, ip, "unchanged", null, now);
                if ("error".equals(previousStatus)) telegramNotificationService.notifyDynamicDnsRecovery(id, Objects.toString(rule.get("name")), ip);
                return;
            }
            ProviderAccess access = loadProvider(Objects.toString(rule.get("provider_source")),
                    number(rule.get("provider_ref_id")), nullableLong(rule.get("zone_ref_id")), Objects.toString(rule.get("provider")));
            String providerRecordId = updateProvider(access, Objects.toString(rule.get("zone_name")),
                    Objects.toString(rule.get("record_name")), type, ip, ((Number) rule.get("ttl")).intValue(),
                    Objects.toString(rule.get("provider_record_id"), null));
            jdbcTemplate.update("UPDATE dynamic_dns_rule SET last_applied_ip=?,provider_record_id=?,last_status='success',last_error=NULL,last_updated_at=?,updated_time=? WHERE id=?",
                    ip, providerRecordId, now, now, id);
            addHistory(id, oldIp, ip, "updated", null, now);
            if ("error".equals(previousStatus)) telegramNotificationService.notifyDynamicDnsRecovery(id, Objects.toString(rule.get("name")), ip);
        } catch (Exception e) {
            String message = StringUtils.abbreviate(StringUtils.defaultIfBlank(e.getMessage(), "动态 DNS 更新失败"), 500);
            jdbcTemplate.update("UPDATE dynamic_dns_rule SET last_status='error',last_error=?,last_checked_at=?,updated_time=? WHERE id=?",
                    message, now, now, id);
            if (!"error".equals(previousStatus) || manual) addHistory(id, oldIp, null, "failed", message, now);
            if (!"error".equals(previousStatus)) {
                telegramNotificationService.notifyDynamicDnsFailure(id, Objects.toString(rule.get("name")), message);
            }
            throw new IllegalStateException(message);
        }
    }

    private List<Map<String, Object>> providerOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        options.addAll(jdbcTemplate.queryForList(
                "SELECT CONCAT('dns:',a.id) AS optionKey,'dns' AS source,a.id,a.name,a.provider,a.enabled,"
                        + "z.id AS zoneRefId,z.zone_name AS zoneName FROM dns_provider_account a JOIN dns_zone z ON z.account_id=a.id "
                        + "WHERE a.provider='cloudflare' AND a.enabled=1 AND z.status='active' ORDER BY a.name,z.zone_name"));
        options.addAll(jdbcTemplate.queryForList(
                "SELECT CONCAT('dynamic:',id) AS optionKey,'dynamic' AS source,id,name,provider,enabled,last_error AS lastError,"
                        + "CASE WHEN credential_a IS NULL OR credential_a='' THEN 0 ELSE 1 END AS credentialConfigured "
                        + "FROM dynamic_dns_provider ORDER BY created_time DESC"));
        return options;
    }

    private ProviderAccess loadProvider(String source, Long refId, Long zoneRefId, String requestedProvider) {
        if (refId == null) throw new IllegalArgumentException("请选择 DNS 提供商配置");
        if ("dns".equals(source)) {
            if (zoneRefId == null) throw new IllegalArgumentException("请选择 Cloudflare Zone");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT a.api_token,z.provider_zone_id,z.zone_name FROM dns_provider_account a JOIN dns_zone z ON z.account_id=a.id "
                            + "WHERE a.id=? AND z.id=? AND a.enabled=1 AND z.status='active'", refId, zoneRefId);
            if (rows.isEmpty()) throw new IllegalArgumentException("Cloudflare 配置或 Zone 不可用");
            Map<String, Object> row = rows.get(0);
            return new ProviderAccess("cloudflare", decrypt(Objects.toString(row.get("api_token"))), null,
                    Objects.toString(row.get("provider_zone_id")), Objects.toString(row.get("zone_name")));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM dynamic_dns_provider WHERE id=? AND enabled=1", refId);
        if (rows.isEmpty()) throw new IllegalArgumentException("DNS 提供商配置不存在或已停用");
        Map<String, Object> row = rows.get(0);
        String provider = normalizeProvider(Objects.toString(row.get("provider"), requestedProvider));
        return new ProviderAccess(provider, decrypt(Objects.toString(row.get("credential_a"))),
                StringUtils.isBlank(Objects.toString(row.get("credential_b"), null)) ? null : decrypt(Objects.toString(row.get("credential_b"))),
                null, null);
    }

    private String updateProvider(ProviderAccess access, String zone, String fqdn, String type,
                                  String value, int ttl, String knownRecordId) {
        return switch (access.provider) {
            case "cloudflare" -> updateCloudflare(access, zone, fqdn, type, value, ttl, knownRecordId);
            case "dnspod" -> updateDnsPod(access, zone, fqdn, type, value, ttl, knownRecordId);
            case "aliyun" -> updateAliyun(access, zone, fqdn, type, value, ttl, knownRecordId);
            default -> throw new IllegalArgumentException("不支持的 DNS 提供商");
        };
    }

    private String updateCloudflare(ProviderAccess access, String zone, String fqdn, String type,
                                    String value, int ttl, String knownRecordId) {
        String zoneId = access.zoneId;
        if (zoneId == null) {
            JSONObject zones = cloudflare(access.keyA, HttpMethod.GET, "/zones?name=" + encode(zone), null);
            JSONArray result = zones.getJSONArray("result");
            if (result == null || result.isEmpty()) throw new IllegalStateException("Cloudflare 中找不到 Zone " + zone);
            zoneId = result.getJSONObject(0).getString("id");
        }
        String recordId = StringUtils.trimToNull(knownRecordId);
        if (recordId == null) {
            JSONObject records = cloudflare(access.keyA, HttpMethod.GET,
                    "/zones/" + zoneId + "/dns_records?type=" + type + "&name=" + encode(fqdn), null);
            JSONArray result = records.getJSONArray("result");
            if (result != null && result.size() > 1) {
                throw new IllegalStateException("Cloudflare 存在多个同名记录，请先整理后再由动态 DNS 接管");
            }
            if (result != null && !result.isEmpty()) recordId = result.getJSONObject(0).getString("id");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type); body.put("name", fqdn); body.put("content", value); body.put("ttl", ttl); body.put("proxied", false);
        if (recordId == null) {
            JSONObject created = cloudflare(access.keyA, HttpMethod.POST, "/zones/" + zoneId + "/dns_records", body);
            return created.getJSONObject("result").getString("id");
        }
        cloudflare(access.keyA, HttpMethod.PUT, "/zones/" + zoneId + "/dns_records/" + recordId, body);
        return recordId;
    }

    private JSONObject cloudflare(String token, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> response = restTemplate.exchange(CLOUDFLARE_API + path, method,
                    new HttpEntity<>(body, headers), String.class);
            JSONObject json = JSON.parseObject(response.getBody());
            if (json == null || !json.getBooleanValue("success")) throw new IllegalStateException("Cloudflare API 拒绝请求");
            return json;
        } catch (RestClientException e) { throw new IllegalStateException("Cloudflare API 请求失败"); }
    }

    private String updateDnsPod(ProviderAccess access, String zone, String fqdn, String type,
                                String value, int ttl, String knownRecordId) {
        String sub = relativeName(fqdn, zone);
        String recordId = StringUtils.trimToNull(knownRecordId);
        if (recordId == null) {
            JSONObject result = dnsPod(access, "DescribeRecordList", Map.of("Domain", zone, "Subdomain", sub, "RecordType", type, "Limit", 100));
            JSONArray records = result.getJSONObject("Response").getJSONArray("RecordList");
            if (records != null && !records.isEmpty()) recordId = records.getJSONObject(0).getString("RecordId");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Domain", zone); payload.put("SubDomain", sub); payload.put("RecordType", type);
        payload.put("RecordLine", "默认"); payload.put("Value", value); payload.put("TTL", Math.max(600, ttl));
        if (recordId == null) {
            JSONObject created = dnsPod(access, "CreateRecord", payload);
            return created.getJSONObject("Response").getString("RecordId");
        }
        payload.put("RecordId", Long.parseLong(recordId));
        dnsPod(access, "ModifyRecord", payload);
        return recordId;
    }

    private LineRoutingRecord updateDnsPodLine(ProviderAccess access, String zone, String fqdn, String type,
                                               String value, int ttl, String knownRecordId, String line) {
        String sub = relativeName(fqdn, zone);
        String recordId = StringUtils.trimToNull(knownRecordId);
        String originalValue = null;
        Integer originalTtl = null;
        if (recordId == null) {
            JSONObject result = dnsPod(access, "DescribeRecordList",
                    Map.of("Domain", zone, "Subdomain", sub, "RecordType", type, "Limit", 3000));
            JSONArray records = result.getJSONObject("Response").getJSONArray("RecordList");
            if (records != null) {
                for (int index = 0; index < records.size(); index++) {
                    JSONObject item = records.getJSONObject(index);
                    if (line.equals(item.getString("Line"))) {
                        recordId = item.getString("RecordId");
                        originalValue = item.getString("Value");
                        originalTtl = item.getInteger("TTL");
                        break;
                    }
                }
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Domain", zone);
        payload.put("SubDomain", sub);
        payload.put("RecordType", type);
        payload.put("RecordLine", line);
        payload.put("Value", value);
        payload.put("TTL", Math.max(60, ttl));
        if (recordId == null) {
            JSONObject created = dnsPod(access, "CreateRecord", payload);
            return new LineRoutingRecord(created.getJSONObject("Response").getString("RecordId"), true, null, null);
        }
        payload.put("RecordId", Long.parseLong(recordId));
        dnsPod(access, "ModifyRecord", payload);
        return new LineRoutingRecord(recordId, false, originalValue, originalTtl);
    }

    private JSONObject dnsPod(ProviderAccess access, String action, Map<String, Object> payload) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            String date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString();
            String body = JSON.toJSONString(payload);
            String canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:dnspod.tencentcloudapi.com\n";
            String signedHeaders = "content-type;host";
            String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + sha256Hex(body);
            String scope = date + "/dnspod/tc3_request";
            String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
            byte[] secretDate = hmac(("TC3" + access.keyB).getBytes(StandardCharsets.UTF_8), date);
            byte[] secretService = hmac(secretDate, "dnspod");
            byte[] secretSigning = hmac(secretService, "tc3_request");
            String signature = hex(hmac(secretSigning, stringToSign));
            String authorization = "TC3-HMAC-SHA256 Credential=" + access.keyA + "/" + scope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/json; charset=utf-8"));
            headers.set("Host", "dnspod.tencentcloudapi.com"); headers.set("X-TC-Action", action);
            headers.set("X-TC-Version", "2021-03-23"); headers.set("X-TC-Timestamp", Long.toString(timestamp));
            headers.set("Authorization", authorization);
            String response = restTemplate.postForObject(DNSPOD_API, new HttpEntity<>(body, headers), String.class);
            JSONObject json = JSON.parseObject(response);
            JSONObject error = json == null ? null : json.getJSONObject("Response").getJSONObject("Error");
            if (error != null) throw new IllegalStateException("DNSPod: " + error.getString("Message"));
            return json;
        } catch (RestClientException e) { throw new IllegalStateException("DNSPod API 请求失败"); }
        catch (Exception e) { if (e instanceof IllegalStateException) throw (IllegalStateException) e; throw new IllegalStateException("DNSPod 请求签名失败"); }
    }

    private String updateAliyun(ProviderAccess access, String zone, String fqdn, String type,
                                String value, int ttl, String knownRecordId) {
        String rr = relativeName(fqdn, zone);
        String recordId = StringUtils.trimToNull(knownRecordId);
        if (recordId == null) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("DomainName", zone); params.put("RRKeyWord", rr); params.put("TypeKeyWord", type);
            JSONObject records = aliyun(access, "DescribeDomainRecords", params);
            JSONArray list = records.getJSONObject("DomainRecords").getJSONArray("Record");
            if (list != null && !list.isEmpty()) recordId = list.getJSONObject(0).getString("RecordId");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("RR", rr); params.put("Type", type); params.put("Value", value); params.put("TTL", Integer.toString(Math.max(600, ttl)));
        if (recordId == null) {
            params.put("DomainName", zone);
            return aliyun(access, "AddDomainRecord", params).getString("RecordId");
        }
        params.put("RecordId", recordId);
        aliyun(access, "UpdateDomainRecord", params);
        return recordId;
    }

    private LineRoutingRecord updateAliyunLine(ProviderAccess access, String zone, String fqdn, String type,
                                               String value, int ttl, String knownRecordId, String line) {
        String rr = relativeName(fqdn, zone);
        String recordId = StringUtils.trimToNull(knownRecordId);
        String originalValue = null;
        Integer originalTtl = null;
        if (recordId == null) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("DomainName", zone);
            query.put("RRKeyWord", rr);
            query.put("TypeKeyWord", type);
            query.put("PageSize", "500");
            JSONObject records = aliyun(access, "DescribeDomainRecords", query);
            JSONArray list = records.getJSONObject("DomainRecords").getJSONArray("Record");
            if (list != null) {
                for (int index = 0; index < list.size(); index++) {
                    JSONObject item = list.getJSONObject(index);
                    if (line.equalsIgnoreCase(StringUtils.defaultString(item.getString("Line"), "default"))) {
                        recordId = item.getString("RecordId");
                        originalValue = item.getString("Value");
                        originalTtl = item.getInteger("TTL");
                        break;
                    }
                }
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("RR", rr);
        params.put("Type", type);
        params.put("Value", value);
        params.put("TTL", Integer.toString(Math.max(600, ttl)));
        params.put("Line", line);
        if (recordId == null) {
            params.put("DomainName", zone);
            return new LineRoutingRecord(aliyun(access, "AddDomainRecord", params).getString("RecordId"), true, null, null);
        }
        params.put("RecordId", recordId);
        aliyun(access, "UpdateDomainRecord", params);
        return new LineRoutingRecord(recordId, false, originalValue, originalTtl);
    }

    private String normalizeCarrierLine(String provider, String carrier) {
        String normalized = StringUtils.defaultIfBlank(carrier, "default").toLowerCase(Locale.ROOT);
        if (!List.of("default", "telecom", "unicom", "mobile").contains(normalized)) {
            throw new IllegalArgumentException("不支持的运营商线路");
        }
        if ("aliyun".equals(provider)) return normalized;
        return switch (normalized) {
            case "telecom" -> "电信";
            case "unicom" -> "联通";
            case "mobile" -> "移动";
            default -> "默认";
        };
    }

    private JSONObject aliyun(ProviderAccess access, String action, Map<String, String> actionParams) {
        try {
            Map<String, String> params = new TreeMap<>();
            params.put("Format", "JSON"); params.put("Version", "2015-01-09"); params.put("AccessKeyId", access.keyA);
            params.put("SignatureMethod", "HMAC-SHA1"); params.put("Timestamp", formatAliyunTimestamp(Instant.now()));
            params.put("SignatureVersion", "1.0"); params.put("SignatureNonce", UUID.randomUUID().toString()); params.put("Action", action);
            params.putAll(actionParams);
            String canonical = params.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .reduce((a, b) -> a + "&" + b).orElse("");
            String signature = java.util.Base64.getEncoder().encodeToString(hmacSha1((access.keyB + "&").getBytes(StandardCharsets.UTF_8),
                    "GET&%2F&" + encode(canonical)));
            String response = restTemplate.getForObject(ALIYUN_API + "?" + canonical + "&Signature=" + encode(signature), String.class);
            JSONObject json = JSON.parseObject(response);
            if (json != null && json.containsKey("Code")) throw new IllegalStateException("阿里云 DNS: " + json.getString("Message"));
            return json;
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException(formatAliyunApiError(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            throw new IllegalStateException("阿里云 DNS API 连接失败");
        }
        catch (Exception e) { if (e instanceof IllegalStateException) throw (IllegalStateException) e; throw new IllegalStateException("阿里云 DNS 请求签名失败"); }
    }

    static String formatAliyunApiError(String responseBody) {
        try {
            JSONObject body = JSON.parseObject(responseBody);
            String code = body == null ? null : StringUtils.trimToNull(body.getString("Code"));
            String message = body == null ? null : StringUtils.trimToNull(body.getString("Message"));
            if (code != null && message != null) return "阿里云 DNS：" + code + " - " + message;
            if (code != null) return "阿里云 DNS：" + code;
            if (message != null) return "阿里云 DNS：" + message;
        } catch (RuntimeException ignored) {
            // Provider gateway pages must not be copied into the administrator UI.
        }
        return "阿里云 DNS API 请求失败";
    }

    static String formatAliyunTimestamp(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    private void addHistory(long ruleId, String oldIp, String newIp, String status, String error, long now) {
        jdbcTemplate.update("INSERT INTO dynamic_dns_history (rule_id,old_ip,new_ip,status,error,created_time) VALUES (?,?,?,?,?,?)",
                ruleId, oldIp, newIp, status, error, now);
        jdbcTemplate.update("DELETE FROM dynamic_dns_history WHERE rule_id=? AND id NOT IN (SELECT id FROM (SELECT id FROM dynamic_dns_history WHERE rule_id=? ORDER BY created_time DESC LIMIT 200) keep_rows)",
                ruleId, ruleId);
    }

    private String normalizeProvider(String value) {
        String provider = StringUtils.defaultIfBlank(value, "cloudflare").toLowerCase(Locale.ROOT);
        if (!List.of("cloudflare", "dnspod", "aliyun").contains(provider)) throw new IllegalArgumentException("仅支持 Cloudflare、DNSPod 和阿里云 DNS");
        return provider;
    }

    private String providerCredentialHint(String provider) {
        return switch (provider) {
            case "cloudflare" -> "请填写 Cloudflare API Token";
            case "dnspod" -> "请填写腾讯云 SecretId 和 SecretKey";
            default -> "请填写阿里云 AccessKey ID 和 AccessKey Secret";
        };
    }

    private String normalizeRecord(String input, String zone) {
        String value = StringUtils.trimToEmpty(input).toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "@".equals(value)) return zone;
        String fqdn = value.contains(".") ? value : value + "." + zone;
        if (!fqdn.equals(zone) && !fqdn.endsWith("." + zone)) throw new IllegalArgumentException("记录不属于主域名 " + zone);
        if (!validDomain(fqdn)) throw new IllegalArgumentException("记录名称格式不正确");
        return fqdn;
    }

    private boolean validDomain(String value) {
        return value != null && value.length() <= 253 && value.matches("(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    }

    private String relativeName(String fqdn, String zone) {
        return fqdn.equals(zone) ? "@" : fqdn.substring(0, fqdn.length() - zone.length() - 1);
    }

    private void validateIp(String type, String value) {
        try {
            InetAddress address = InetAddress.getByName(value);
            if (("A".equals(type) && !(address instanceof Inet4Address)) || ("AAAA".equals(type) && !(address instanceof Inet6Address))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) { throw new IllegalStateException("Agent 返回的公网地址与记录类型不匹配"); }
    }

    private byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
    private byte[] hmacSha1(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key, "HmacSHA1"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
    private String sha256Hex(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
    private String encrypt(String value) { return new AESCrypto(encryptionSecret).encrypt(value); }
    private String decrypt(String value) { return new AESCrypto(encryptionSecret).decryptString(value); }
    private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private boolean truth(Object value) { return value != null && ("1".equals(value.toString()) || Boolean.parseBoolean(value.toString())); }

    public record LineRoutingRecord(String recordId, boolean created, String originalValue, Integer originalTtl) { }

    private static class ProviderAccess {
        final String provider;
        final String keyA;
        final String keyB;
        final String zoneId;
        final String zoneName;
        ProviderAccess(String provider, String keyA, String keyB, String zoneId, String zoneName) {
            this.provider = provider; this.keyA = keyA; this.keyB = keyB; this.zoneId = zoneId; this.zoneName = zoneName;
        }
    }
}
