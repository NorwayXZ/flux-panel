package com.admin.service;

import com.admin.common.dto.DnsProviderAccountSaveDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.JwtUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class DnsProviderService {
    private static final String CF_API = "https://api.cloudflare.com/client/v4";

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;

    @Value("${jwt-secret}")
    private String encryptionSecret;

    public DnsProviderService(JdbcTemplate jdbcTemplate, RestTemplate restTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    public R list() {
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
                "SELECT a.id,a.name,a.provider,a.enabled,a.last_sync_at AS lastSyncAt,a.last_error AS lastError,"
                        + "a.created_time AS createdTime,CASE WHEN a.api_token IS NULL OR a.api_token='' THEN 0 ELSE 1 END AS apiTokenConfigured,"
                        + "COUNT(z.id) AS zoneCount FROM dns_provider_account a LEFT JOIN dns_zone z ON z.account_id=a.id AND z.status='active' "
                        + "GROUP BY a.id ORDER BY a.created_time DESC");
        List<Map<String, Object>> zones = jdbcTemplate.queryForList(
                "SELECT z.id,z.account_id AS accountId,a.name AS accountName,z.provider_zone_id AS providerZoneId,z.zone_name AS zoneName,z.status,"
                        + "z.updated_time AS updatedTime,COUNT(DISTINCT r.id) AS recordCount,COUNT(DISTINCT g.id) AS failoverCount "
                        + "FROM dns_zone z JOIN dns_provider_account a ON a.id=z.account_id "
                        + "LEFT JOIN dns_managed_record r ON r.zone_id=z.id LEFT JOIN cross_entry_failover_group g ON g.dns_zone_id=z.id "
                        + "GROUP BY z.id ORDER BY a.created_time DESC,z.zone_name");
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT r.id,r.zone_id AS zoneId,z.zone_name AS zoneName,r.provider_record_id AS providerRecordId,r.fqdn,r.record_type AS recordType,"
                        + "r.content,r.ttl,r.owner_type AS ownerType,r.owner_id AS ownerId,r.status,r.last_error AS lastError,r.updated_time AS updatedTime,"
                        + "CASE WHEN r.owner_type='cross_entry' THEN g.name ELSE NULL END AS ownerName "
                        + "FROM dns_managed_record r JOIN dns_zone z ON z.id=r.zone_id "
                        + "LEFT JOIN cross_entry_failover_group g ON r.owner_type='cross_entry' AND g.id=r.owner_id "
                        + "ORDER BY r.updated_time DESC");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accounts", accounts.size());
        summary.put("zones", zones.stream().filter(row -> "active".equals(row.get("status"))).count());
        summary.put("records", records.size());
        summary.put("errors", accounts.stream().filter(row -> StringUtils.isNotBlank(Objects.toString(row.get("lastError"), null))).count());
        return R.ok(Map.of("accounts", accounts, "zones", zones, "records", records, "summary", summary));
    }

    public R listZoneOptions() {
        return R.ok(jdbcTemplate.queryForList(
                "SELECT z.id,z.account_id AS accountId,a.name AS accountName,z.zone_name AS zoneName,z.provider_zone_id AS providerZoneId "
                        + "FROM dns_zone z JOIN dns_provider_account a ON a.id=z.account_id "
                        + "WHERE z.status='active' AND a.enabled=1 ORDER BY a.name,z.zone_name"));
    }

    @Transactional(rollbackFor = Exception.class)
    public R saveAccount(DnsProviderAccountSaveDto dto) {
        try {
            String name = dto.getName().trim();
            String token = StringUtils.trimToNull(dto.getApiToken());
            String encryptedToken;
            Long id = dto.getId();
            if (id == null) {
                if (token == null) return R.err("首次添加需要填写 Cloudflare API Token");
                encryptedToken = crypto().encrypt(token);
            } else {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT api_token AS apiToken FROM dns_provider_account WHERE id=?", id);
                if (rows.isEmpty()) return R.err("Cloudflare 配置不存在");
                encryptedToken = token == null ? Objects.toString(rows.get(0).get("apiToken"), "") : crypto().encrypt(token);
                if (token == null) token = decryptToken(encryptedToken);
            }
            List<CloudflareZone> zones = fetchZones(token);
            if (zones.isEmpty()) return R.err("Token 没有可读取的 Cloudflare Zone，请检查 Zone Read 权限和授权范围");
            long now = System.currentTimeMillis();
            Integer duplicate = id == null
                    ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dns_provider_account WHERE provider='cloudflare' AND name=?", Integer.class, name)
                    : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dns_provider_account WHERE provider='cloudflare' AND name=? AND id<>?", Integer.class, name, id);
            if (duplicate != null && duplicate > 0) return R.err("配置名称已经存在");
            if (id == null) {
                jdbcTemplate.update("INSERT INTO dns_provider_account (name,provider,api_token,enabled,last_sync_at,last_error,created_by,created_time,updated_time) "
                                + "VALUES (?,'cloudflare',?,?,?,NULL,?,?,?)",
                        name, encryptedToken, !Boolean.FALSE.equals(dto.getEnabled()), now, JwtUtil.getUserIdFromToken(), now, now);
                id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            } else {
                jdbcTemplate.update("UPDATE dns_provider_account SET name=?,api_token=?,enabled=?,last_sync_at=?,last_error=NULL,updated_time=? WHERE id=?",
                        name, encryptedToken, !Boolean.FALSE.equals(dto.getEnabled()), now, now, id);
            }
            persistZones(id, zones, now);
            return R.ok(Map.of("id", id, "zoneCount", zones.size()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return R.err(e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public R syncAccount(Long id) {
        try {
            Map<String, Object> account = loadAccount(id);
            List<CloudflareZone> zones = fetchZones(decryptToken(Objects.toString(account.get("apiToken"))));
            if (zones.isEmpty()) return R.err("Token 没有可读取的 Cloudflare Zone");
            long now = System.currentTimeMillis();
            persistZones(id, zones, now);
            jdbcTemplate.update("UPDATE dns_provider_account SET last_sync_at=?,last_error=NULL,updated_time=? WHERE id=?", now, now, id);
            return R.ok(Map.of("zoneCount", zones.size()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            jdbcTemplate.update("UPDATE dns_provider_account SET last_error=?,updated_time=? WHERE id=?", shorten(e.getMessage(), 500), System.currentTimeMillis(), id);
            return R.err(e.getMessage());
        }
    }

    @Transactional
    public R deleteAccount(Long id) {
        Integer used = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_entry_failover_group WHERE dns_zone_id IN (SELECT id FROM dns_zone WHERE account_id=?)",
                Integer.class, id);
        if (used != null && used > 0) return R.err("该配置仍被入口容灾使用，请先迁移或删除相关容灾组");
        Integer certificateUsed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM managed_certificate WHERE zone_id IN (SELECT id FROM dns_zone WHERE account_id=?)",
                Integer.class, id);
        if (certificateUsed != null && certificateUsed > 0) return R.err("该配置仍被托管 HTTPS 证书使用，请先删除相关域名入口");
        jdbcTemplate.update("DELETE FROM dns_managed_record WHERE zone_id IN (SELECT id FROM dns_zone WHERE account_id=?)", id);
        jdbcTemplate.update("DELETE FROM dns_zone WHERE account_id=?", id);
        int deleted = jdbcTemplate.update("DELETE FROM dns_provider_account WHERE id=?", id);
        return deleted > 0 ? R.ok() : R.err("Cloudflare 配置不存在");
    }

    public ZoneAccess loadZoneAccess(Long zoneRefId) {
        if (zoneRefId == null) throw new IllegalArgumentException("请选择已登记的 Cloudflare Zone");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT z.id,z.provider_zone_id AS providerZoneId,z.zone_name AS zoneName,a.api_token AS apiToken,a.enabled "
                        + "FROM dns_zone z JOIN dns_provider_account a ON a.id=z.account_id WHERE z.id=? AND z.status='active'", zoneRefId);
        if (rows.isEmpty()) throw new IllegalArgumentException("所选 Cloudflare Zone 不存在或已停用");
        Map<String, Object> row = rows.get(0);
        if (!truthy(row.get("enabled"))) throw new IllegalArgumentException("所选 Cloudflare 配置已停用");
        return new ZoneAccess(number(row.get("id")).longValue(), Objects.toString(row.get("providerZoneId")),
                Objects.toString(row.get("zoneName")), decryptToken(Objects.toString(row.get("apiToken"))));
    }

    public String normalizeDomain(Long zoneRefId, String input) {
        ZoneAccess zone = loadZoneAccess(zoneRefId);
        String value = StringUtils.trimToEmpty(input).toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IllegalArgumentException("请输入业务域名或主机记录");
        String fqdn = value.contains(".") ? value : value + "." + zone.zoneName();
        if (!fqdn.equals(zone.zoneName()) && !fqdn.endsWith("." + zone.zoneName())) {
            throw new IllegalArgumentException("业务域名不属于所选 Zone " + zone.zoneName());
        }
        if (fqdn.length() > 253 || !fqdn.matches("(?i)^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")) {
            throw new IllegalArgumentException("业务域名格式不正确");
        }
        return fqdn;
    }

    public String ensureManagedRecord(Long zoneRefId, String requestedRecordId, String domain, String type,
                                      String content, int ttl, Long ownerId) {
        return ensureOwnedRecord(zoneRefId, requestedRecordId, domain, type, content, ttl, "cross_entry", ownerId);
    }

    public String ensureDomainRouteRecord(Long zoneRefId, String requestedRecordId, String domain,
                                          String content, Long ownerId) {
        String type;
        try {
            InetAddress address = InetAddress.getByName(content);
            type = address instanceof Inet6Address ? "AAAA" : "A";
            content = address.getHostAddress();
        } catch (Exception e) {
            throw new IllegalArgumentException("公网入口必须是可解析的 IPv4 或 IPv6 地址");
        }
        return ensureOwnedRecord(zoneRefId, requestedRecordId, domain, type, content, 60, "domain_route", ownerId);
    }

    public String createDnsChallenge(Long zoneRefId, String recordName, String value) {
        ZoneAccess zone = loadZoneAccess(zoneRefId);
        String fqdn = normalizeChallengeName(zone, recordName);
        // Always create an owned TXT record so cleanup never removes a record created outside the panel.
        return createRecord(zone, fqdn, "TXT", value, 60);
    }

    String normalizeChallengeName(ZoneAccess zone, String recordName) {
        String fqdn = StringUtils.trimToEmpty(recordName).toLowerCase(Locale.ROOT);
        while (fqdn.endsWith(".")) fqdn = fqdn.substring(0, fqdn.length() - 1);
        if (!fqdn.matches("^[a-z0-9_.-]+$")
                || (!fqdn.equals(zone.zoneName()) && !fqdn.endsWith("." + zone.zoneName()))) {
            throw new IllegalArgumentException("DNS 验证记录不属于所选域名 Zone");
        }
        return fqdn;
    }

    public void deleteDnsChallenge(Long zoneRefId, String recordId) {
        if (zoneRefId == null || StringUtils.isBlank(recordId)) return;
        ZoneAccess zone = loadZoneAccess(zoneRefId);
        exchange(CF_API + "/zones/" + zone.providerZoneId() + "/dns_records/" + recordId,
                HttpMethod.DELETE, new HttpEntity<>(headers(zone.token())));
    }

    public void releaseDomainRouteRecord(Long ownerId) {
        if (ownerId == null) return;
        jdbcTemplate.update("UPDATE dns_managed_record SET owner_type=NULL,owner_id=NULL,updated_time=? "
                        + "WHERE owner_type='domain_route' AND owner_id=?",
                System.currentTimeMillis(), ownerId);
    }

    public void transferDomainRouteRecord(Long currentOwnerId, Long replacementOwnerId) {
        if (currentOwnerId == null || replacementOwnerId == null) return;
        jdbcTemplate.update("UPDATE dns_managed_record SET owner_id=?,updated_time=? "
                        + "WHERE owner_type='domain_route' AND owner_id=?",
                replacementOwnerId, System.currentTimeMillis(), currentOwnerId);
    }

    private String ensureOwnedRecord(Long zoneRefId, String requestedRecordId, String domain, String type,
                                     String content, int ttl, String ownerType, Long ownerId) {
        ZoneAccess zone = loadZoneAccess(zoneRefId);
        String fqdn = normalizeDomain(zoneRefId, domain);
        validateRecord(type, content);
        List<Map<String, Object>> managed = jdbcTemplate.queryForList(
                "SELECT provider_record_id AS providerRecordId,owner_type AS ownerType,owner_id AS ownerId FROM dns_managed_record "
                        + "WHERE zone_id=? AND fqdn=? AND record_type=?", zoneRefId, fqdn, type);
        if (!managed.isEmpty()) {
            Map<String, Object> row = managed.get(0);
            Long existingOwner = nullableLong(row.get("ownerId"));
            if (StringUtils.isNotBlank(Objects.toString(row.get("ownerType"), null)) && existingOwner != null
                    && ownerId != null && !ownerId.equals(existingOwner)) {
                throw new IllegalArgumentException("该域名已经被其他面板资源使用");
            }
            requestedRecordId = Objects.toString(row.get("providerRecordId"), requestedRecordId);
        }
        String recordId = StringUtils.trimToNull(requestedRecordId);
        if (recordId == null) {
            JSONArray existing = findRecords(zone, fqdn, type);
            if (existing.size() > 1) throw new IllegalArgumentException("Cloudflare 存在多个同名记录，请先整理后再由面板接管");
            if (existing.size() == 1) {
                JSONObject record = existing.getJSONObject(0);
                boolean proxied = record.getBooleanValue("proxied");
                String current = record.getString("content");
                if (proxied || !content.equalsIgnoreCase(current)) {
                    throw new IllegalArgumentException("Cloudflare 已存在同名记录且内容不同，请先确认现有业务后再处理");
                }
                recordId = record.getString("id");
            } else {
                recordId = createRecord(zone, fqdn, type, content, ttl);
            }
        }
        updateRecord(zone, recordId, fqdn, type, content, ttl);
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO dns_managed_record (zone_id,provider_record_id,fqdn,record_type,content,ttl,owner_type,owner_id,status,last_error,created_time,updated_time) "
                        + "VALUES (?,?,?,?,?,?,?,?, 'active',NULL,?,?) ON DUPLICATE KEY UPDATE provider_record_id=VALUES(provider_record_id),"
                        + "content=VALUES(content),ttl=VALUES(ttl),owner_type=VALUES(owner_type),owner_id=VALUES(owner_id),status='active',last_error=NULL,updated_time=VALUES(updated_time)",
                zoneRefId, recordId, fqdn, type, content, ttl, ownerType, ownerId, now, now);
        return recordId;
    }

    public void updateManagedRecord(Long zoneRefId, String recordId, String domain, String type, String content, int ttl, Long ownerId) {
        ZoneAccess zone = loadZoneAccess(zoneRefId);
        String fqdn = normalizeDomain(zoneRefId, domain);
        validateRecord(type, content);
        updateRecord(zone, recordId, fqdn, type, content, ttl);
        jdbcTemplate.update("UPDATE dns_managed_record SET content=?,ttl=?,owner_type='cross_entry',owner_id=?,status='active',last_error=NULL,updated_time=? "
                        + "WHERE zone_id=? AND provider_record_id=?",
                content, ttl, ownerId, System.currentTimeMillis(), zoneRefId, recordId);
    }

    public void releaseRecord(Long ownerId) {
        if (ownerId == null) return;
        jdbcTemplate.update("UPDATE dns_managed_record SET owner_type=NULL,owner_id=NULL,updated_time=? WHERE owner_type='cross_entry' AND owner_id=?",
                System.currentTimeMillis(), ownerId);
    }

    private List<CloudflareZone> fetchZones(String token) {
        List<CloudflareZone> zones = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        do {
            URI uri = UriComponentsBuilder.fromHttpUrl(CF_API + "/zones")
                    .queryParam("status", "active").queryParam("page", page).queryParam("per_page", 50).build(true).toUri();
            JSONObject json = exchange(uri, HttpMethod.GET, new HttpEntity<>(headers(token)));
            JSONArray result = json.getJSONArray("result");
            if (result != null) {
                for (int i = 0; i < result.size(); i++) {
                    JSONObject item = result.getJSONObject(i);
                    zones.add(new CloudflareZone(item.getString("id"), item.getString("name")));
                }
            }
            JSONObject info = json.getJSONObject("result_info");
            totalPages = info == null ? 1 : Math.max(1, info.getIntValue("total_pages"));
            page++;
        } while (page <= totalPages && page <= 20);
        return zones;
    }

    private void persistZones(Long accountId, List<CloudflareZone> zones, long now) {
        for (CloudflareZone zone : zones) {
            Integer duplicate = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dns_zone WHERE provider_zone_id=? AND account_id<>?", Integer.class, zone.id(), accountId);
            if (duplicate != null && duplicate > 0) {
                throw new IllegalArgumentException("Zone " + zone.name() + " 已由其他 Cloudflare 配置管理");
            }
        }
        jdbcTemplate.update("UPDATE dns_zone SET status='inactive',updated_time=? WHERE account_id=?", now, accountId);
        for (CloudflareZone zone : zones) {
            jdbcTemplate.update("INSERT INTO dns_zone (account_id,provider_zone_id,zone_name,status,created_time,updated_time) VALUES (?,?,?,'active',?,?) "
                            + "ON DUPLICATE KEY UPDATE account_id=VALUES(account_id),zone_name=VALUES(zone_name),status='active',updated_time=VALUES(updated_time)",
                    accountId, zone.id(), zone.name().toLowerCase(Locale.ROOT), now, now);
        }
    }

    private JSONArray findRecords(ZoneAccess zone, String fqdn, String type) {
        URI uri = UriComponentsBuilder.fromHttpUrl(CF_API + "/zones/" + zone.providerZoneId() + "/dns_records")
                .queryParam("type", type).queryParam("name", fqdn).build(true).toUri();
        JSONObject json = exchange(uri, HttpMethod.GET, new HttpEntity<>(headers(zone.token())));
        JSONArray result = json.getJSONArray("result");
        return result == null ? new JSONArray() : result;
    }

    private String createRecord(ZoneAccess zone, String fqdn, String type, String content, int ttl) {
        JSONObject json = exchange(CF_API + "/zones/" + zone.providerZoneId() + "/dns_records", HttpMethod.POST,
                new HttpEntity<>(recordBody(fqdn, type, content, ttl), headers(zone.token())));
        JSONObject result = json.getJSONObject("result");
        if (result == null || StringUtils.isBlank(result.getString("id"))) throw new IllegalStateException("Cloudflare 创建 DNS 记录后未返回记录 ID");
        return result.getString("id");
    }

    private void updateRecord(ZoneAccess zone, String recordId, String fqdn, String type, String content, int ttl) {
        exchange(CF_API + "/zones/" + zone.providerZoneId() + "/dns_records/" + recordId, HttpMethod.PUT,
                new HttpEntity<>(recordBody(fqdn, type, content, ttl), headers(zone.token())));
    }

    private Map<String, Object> recordBody(String fqdn, String type, String content, int ttl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("name", fqdn);
        body.put("content", content);
        body.put("ttl", Math.max(60, Math.min(86400, ttl)));
        body.put("proxied", false);
        return body;
    }

    private JSONObject exchange(Object target, HttpMethod method, HttpEntity<?> entity) {
        try {
            ResponseEntity<String> response = target instanceof URI
                    ? restTemplate.exchange((URI) target, method, entity, String.class)
                    : restTemplate.exchange(target.toString(), method, entity, String.class);
            JSONObject json = JSON.parseObject(response.getBody());
            if (json == null || !json.getBooleanValue("success")) throw new IllegalStateException(cloudflareError(json));
            return json;
        } catch (RestClientException e) {
            throw new IllegalStateException("无法连接 Cloudflare API，请检查网络和 Token 权限");
        }
    }

    private Map<String, Object> loadAccount(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,api_token AS apiToken FROM dns_provider_account WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Cloudflare 配置不存在");
        return rows.get(0);
    }

    private String decryptToken(String encryptedToken) {
        try {
            return crypto().decryptString(encryptedToken);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cloudflare Token 无法解密，请重新填写 Token");
        }
    }

    private void validateRecord(String type, String content) {
        if (!List.of("A", "AAAA").contains(type)) throw new IllegalArgumentException("仅支持 A 或 AAAA 记录");
        try {
            InetAddress address = InetAddress.getByName(content);
            if ("A".equals(type) && !(address instanceof Inet4Address)) throw new IllegalArgumentException("A 记录入口必须是公网 IPv4");
            if ("AAAA".equals(type) && !(address instanceof Inet6Address)) throw new IllegalArgumentException("AAAA 记录入口必须是 IPv6");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(type + " 记录入口地址格式不正确");
        }
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String cloudflareError(JSONObject json) {
        if (json != null && json.getJSONArray("errors") != null && !json.getJSONArray("errors").isEmpty()) {
            String message = json.getJSONArray("errors").getJSONObject(0).getString("message");
            if (StringUtils.isNotBlank(message)) return "Cloudflare：" + message;
        }
        return "Cloudflare API 操作失败";
    }

    private AESCrypto crypto() {
        return new AESCrypto(encryptionSecret);
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : Long.parseLong(value.toString());
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : number(value).longValue();
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private static String shorten(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    public record ZoneAccess(long id, String providerZoneId, String zoneName, String token) {}
    private record CloudflareZone(String id, String name) {}
}
