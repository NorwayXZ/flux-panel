package com.admin.service;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;

import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Personal API tokens. A token acts as the user who created it, with that user's permissions,
 * on every panel endpoint. Only a SHA-256 hash is stored; the token is shown once on creation.
 */
@Service
public class ApiTokenService {
    public static final String PREFIX = "cn_";
    private static final int MAX_TOKENS_PER_USER = 20;
    private static final long TOUCH_INTERVAL_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final UserMapper users;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(JdbcTemplate jdbc, UserMapper users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public static boolean looksLikeToken(String value) {
        return value != null && value.startsWith(PREFIX) && value.length() == PREFIX.length() + 48;
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The user a valid, unrevoked, unexpired token belongs to; null otherwise. */
    public User authenticate(String token) {
        if (!looksLikeToken(token)) return null;
        var rows = jdbc.queryForList(
                "SELECT id,user_id,expires_at,last_used_at FROM api_token WHERE token_hash=? AND revoked=0", hash(token));
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        long now = System.currentTimeMillis();
        if (row.get("expires_at") instanceof Number expiry && expiry.longValue() <= now) return null;
        User user = users.selectById(((Number) row.get("user_id")).longValue());
        if (user == null || !Objects.equals(user.getStatus(), 1)) return null;
        if (user.getExpTime() != null && user.getExpTime() > 0 && user.getExpTime() <= now) return null;
        Object lastUsed = row.get("last_used_at");
        if (!(lastUsed instanceof Number used) || now - used.longValue() > TOUCH_INTERVAL_MS) {
            jdbc.update("UPDATE api_token SET last_used_at=? WHERE id=?", now, row.get("id"));
        }
        return user;
    }

    public R create(String name, Integer validDays) {
        Integer userId = JwtUtil.getUserIdFromToken();
        String label = StringUtils.trimToEmpty(name);
        if (label.isEmpty() || label.length() > 64) return R.err("令牌名称需为 1 到 64 个字符");
        if (validDays != null && (validDays < 0 || validDays > 3650)) return R.err("有效期需在 0 到 3650 天之间，0 表示永久");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM api_token WHERE user_id=? AND revoked=0", Integer.class, userId);
        if (count != null && count >= MAX_TOKENS_PER_USER) return R.err("每个账号最多保留 " + MAX_TOKENS_PER_USER + " 个令牌，请先撤销不用的令牌");
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = PREFIX + HexFormat.of().formatHex(bytes);
        long now = System.currentTimeMillis();
        Long expiresAt = validDays == null || validDays == 0 ? null : now + validDays * 86_400_000L;
        jdbc.update("INSERT INTO api_token(user_id,name,token_prefix,token_hash,created_time,expires_at,revoked) VALUES(?,?,?,?,?,?,0)",
                userId, label, token.substring(0, PREFIX.length() + 6), hash(token), now, expiresAt);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("name", label);
        result.put("expiresAt", expiresAt);
        return R.ok(result);
    }

    public R list() {
        Integer userId = JwtUtil.getUserIdFromToken();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,name,token_prefix AS tokenPrefix,created_time AS createdTime,last_used_at AS lastUsedAt,"
                        + "expires_at AS expiresAt FROM api_token WHERE user_id=? AND revoked=0 ORDER BY id DESC", userId);
        return R.ok(rows);
    }

    public R revoke(Long id) {
        if (id == null) return R.err("令牌不存在");
        int changed = jdbc.update("UPDATE api_token SET revoked=1 WHERE id=? AND user_id=?", id, JwtUtil.getUserIdFromToken());
        return changed > 0 ? R.ok() : R.err("令牌不存在");
    }
}
