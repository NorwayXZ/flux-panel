package com.admin.service;

import com.admin.common.dto.AwsAccessAccountSaveDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.JwtUtil;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AwsAccessService {
    private final JdbcTemplate jdbcTemplate;
    private final AESCrypto crypto;

    public AwsAccessService(JdbcTemplate jdbcTemplate, @Value("${jwt-secret}") String secret) {
        this.jdbcTemplate = jdbcTemplate;
        this.crypto = new AESCrypto(secret + ":aws-access");
    }

    public R list() {
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
                "SELECT id,name,access_key_id AS accessKeyId,default_region AS defaultRegion,enabled,"
                        + "aws_account_id AS awsAccountId,caller_arn AS callerArn,last_test_at AS lastTestAt,"
                        + "last_error AS lastError,created_time AS createdTime,updated_time AS updatedTime "
                        + "FROM aws_access_account ORDER BY created_time DESC");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accounts", accounts.size());
        summary.put("enabled", accounts.stream().filter(row -> truth(row.get("enabled"))).count());
        summary.put("errors", accounts.stream().filter(row -> StringUtils.isNotBlank(asString(row.get("lastError")))).count());
        return R.ok(Map.of("accounts", accounts, "summary", summary));
    }

    @Transactional(rollbackFor = Exception.class)
    public R save(AwsAccessAccountSaveDto dto) {
        String name = StringUtils.trimToEmpty(dto.getName());
        if (name.isEmpty()) return R.err("请填写配置名称");
        String accessKeyId = StringUtils.trimToEmpty(dto.getAccessKeyId());
        if (accessKeyId.isEmpty()) return R.err("请填写 AWS Access Key ID");
        String region = normalizeRegion(dto.getDefaultRegion());
        String secret = StringUtils.trimToNull(dto.getSecretAccessKey());
        long now = System.currentTimeMillis();

        Map<String, Object> identity;
        String encryptedSecret;
        if (dto.getId() == null) {
            if (secret == null) return R.err("首次添加需要填写 AWS Secret Access Key");
            identity = verifyCredentials(accessKeyId, secret, region);
            encryptedSecret = crypto.encrypt(secret);
            Integer duplicate = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM aws_access_account WHERE name=?", Integer.class, name);
            if (duplicate != null && duplicate > 0) return R.err("配置名称已存在");
            jdbcTemplate.update("INSERT INTO aws_access_account (name,access_key_id,secret_access_key,default_region,enabled,"
                            + "aws_account_id,caller_arn,last_test_at,last_error,created_time,updated_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    name, accessKeyId, encryptedSecret, region, !Boolean.FALSE.equals(dto.getEnabled()),
                    asString(identity.get("accountId")), asString(identity.get("callerArn")), now, null, now, now);
            return R.ok(Map.of("id", jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class),
                    "awsAccountId", identity.get("accountId"),
                    "callerArn", identity.get("callerArn"),
                    "defaultRegion", region));
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT secret_access_key AS secretAccessKey,access_key_id AS accessKeyId FROM aws_access_account WHERE id=?",
                dto.getId());
        if (rows.isEmpty()) return R.err("AWS 账号不存在");
        String currentSecret = asString(rows.get(0).get("secretAccessKey"));
        String effectiveSecret = secret == null ? decryptSecret(currentSecret) : secret;
        identity = verifyCredentials(accessKeyId, effectiveSecret, region);
        encryptedSecret = secret == null ? currentSecret : crypto.encrypt(secret);
        jdbcTemplate.update("UPDATE aws_access_account SET name=?,access_key_id=?,secret_access_key=?,default_region=?,enabled=?,"
                        + "aws_account_id=?,caller_arn=?,last_test_at=?,last_error=NULL,updated_time=? WHERE id=?",
                name, accessKeyId, encryptedSecret, region, !Boolean.FALSE.equals(dto.getEnabled()),
                asString(identity.get("accountId")), asString(identity.get("callerArn")), now, now, dto.getId());
        return R.ok(Map.of("id", dto.getId(),
                "awsAccountId", identity.get("accountId"),
                "callerArn", identity.get("callerArn"),
                "defaultRegion", region));
    }

    public R test(Long id) {
        AwsAccount account = load(id);
        if (account == null) return R.err("AWS 账号不存在");
        Map<String, Object> identity = verifyCredentials(account.accessKeyId, account.secretAccessKey, account.defaultRegion);
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE aws_access_account SET aws_account_id=?,caller_arn=?,last_test_at=?,last_error=NULL,updated_time=? WHERE id=?",
                asString(identity.get("accountId")), asString(identity.get("callerArn")), now, now, id);
        return R.ok(Map.of("awsAccountId", identity.get("accountId"), "callerArn", identity.get("callerArn"),
                "defaultRegion", account.defaultRegion, "testedAt", now));
    }

    @Transactional(rollbackFor = Exception.class)
    public R delete(Long id) {
        try {
            Integer used = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM network_route_application WHERE aws_access_account_id=? AND state<>'deleted'", Integer.class, id);
            if (used != null && used > 0) return R.err("该 AWS 账号仍被 " + used + " 个 XHTTP 应用使用，请先删除对应应用");
        } catch (org.springframework.dao.DataAccessException ignored) {
            // The route-application schema may not exist during first-start migration ordering.
        }
        return jdbcTemplate.update("DELETE FROM aws_access_account WHERE id=?", id) > 0 ? R.ok() : R.err("AWS 账号不存在");
    }

    AwsAccount load(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,access_key_id AS accessKeyId,secret_access_key AS secretAccessKey,default_region AS defaultRegion "
                        + "FROM aws_access_account WHERE id=?", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        return new AwsAccount(
                id,
                asString(row.get("accessKeyId")),
                decryptSecret(asString(row.get("secretAccessKey"))),
                normalizeRegion(asString(row.get("defaultRegion"))));
    }

    private Map<String, Object> verifyCredentials(String accessKeyId, String secretAccessKey, String region) {
        if (StringUtils.isAnyBlank(accessKeyId, secretAccessKey)) {
            throw new IllegalArgumentException("AWS 凭据不能为空");
        }
        try (StsClient client = StsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()) {
            GetCallerIdentityResponse identity = client.getCallerIdentity();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("accountId", identity.account());
            result.put("callerArn", identity.arn());
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("AWS 凭据验证失败：" + e.getMessage());
        }
    }

    private String normalizeRegion(String value) {
        String region = StringUtils.defaultIfBlank(value, "us-east-1").trim().toLowerCase(Locale.ROOT);
        if (!region.matches("^[a-z0-9-]{2,32}$")) {
            throw new IllegalArgumentException("AWS 区域格式不正确");
        }
        return region;
    }

    private String decryptSecret(String value) {
        if (StringUtils.isBlank(value)) return null;
        if (!value.startsWith("enc:")) return value;
        return crypto.decryptString(value.substring(4));
    }

    private boolean truth(Object value) {
        return value != null && ("1".equals(value.toString()) || Boolean.parseBoolean(value.toString()));
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    record AwsAccount(Long id, String accessKeyId, String secretAccessKey, String defaultRegion) {}
}
