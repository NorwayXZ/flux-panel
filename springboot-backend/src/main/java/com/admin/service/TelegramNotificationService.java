package com.admin.service;

import com.admin.common.dto.TelegramNotificationSettingsDto;
import com.admin.common.utils.IpAddressMatcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class TelegramNotificationService {

    private static final int CONFIG_ID = 1;
    private static final int MIN_REPEAT_MINUTES = 5;
    private static final int MAX_REPEAT_MINUTES = 1440;
    private static final int MAX_REPEAT_LIMIT = 5;
    private static final long DELIVERY_RETENTION_MS = 90L * 24 * 60 * 60 * 1000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.of("Asia/Shanghai"));

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Object deliveryLock = new Object();

    @Value("${jwt-secret}")
    private String encryptionSecret;

    public TelegramNotificationService(JdbcTemplate jdbcTemplate, RestTemplate restTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    public TelegramNotificationSettingsDto getSettings() {
        Settings settings = loadSettings();
        TelegramNotificationSettingsDto dto = new TelegramNotificationSettingsDto();
        dto.setEnabled(settings.enabled());
        dto.setBotToken("");
        dto.setBotTokenConfigured(StringUtils.isNotBlank(settings.botToken()));
        dto.setChatId(settings.chatId());
        dto.setNodeEnabled(settings.nodeEnabled());
        dto.setNodeRepeatLimit(settings.nodeRepeatLimit());
        dto.setTunnelEnabled(settings.tunnelEnabled());
        dto.setTunnelRepeatLimit(settings.tunnelRepeatLimit());
        dto.setForwardEnabled(settings.forwardEnabled());
        dto.setForwardRepeatLimit(settings.forwardRepeatLimit());
        dto.setRecoveryEnabled(settings.recoveryEnabled());
        dto.setAssetExpiryEnabled(settings.assetExpiryEnabled());
        dto.setDynamicDnsEnabled(settings.dynamicDnsEnabled());
        dto.setLoginOutsideWhitelistEnabled(settings.loginOutsideWhitelistEnabled());
        dto.setLoginAllowedCidrs(settings.loginAllowedCidrs());
        dto.setRepeatIntervalMinutes(settings.repeatIntervalMinutes());
        return dto;
    }

    public TelegramNotificationSettingsDto saveSettings(TelegramNotificationSettingsDto dto) {
        if (dto == null) throw new IllegalArgumentException("通知设置不能为空");
        Settings current = loadSettings();
        String encryptedToken = current.encryptedBotToken();
        if (StringUtils.isNotBlank(dto.getBotToken())) {
            encryptedToken = encrypt(dto.getBotToken().trim());
        }
        String chatId = StringUtils.trimToEmpty(dto.getChatId());
        if (dto.isEnabled() && (StringUtils.isBlank(encryptedToken) || StringUtils.isBlank(chatId))) {
            throw new IllegalArgumentException("启用 Telegram 通知前请填写 Bot Token 和 Chat ID");
        }
        int interval = clamp(dto.getRepeatIntervalMinutes(), MIN_REPEAT_MINUTES, MAX_REPEAT_MINUTES);
        jdbcTemplate.update("UPDATE telegram_notification_config SET enabled=?, bot_token=?, chat_id=?, "
                        + "node_enabled=?, node_repeat_limit=?, tunnel_enabled=?, tunnel_repeat_limit=?, "
                        + "forward_enabled=?, forward_repeat_limit=?, recovery_enabled=?, asset_expiry_enabled=?, dynamic_dns_enabled=?, "
                        + "login_outside_whitelist_enabled=?, login_allowed_cidrs=?, repeat_interval_minutes=?, updated_at=? WHERE id=?",
                dto.isEnabled(), encryptedToken, chatId,
                dto.isNodeEnabled(), repeatLimit(dto.getNodeRepeatLimit()),
                dto.isTunnelEnabled(), repeatLimit(dto.getTunnelRepeatLimit()),
                dto.isForwardEnabled(), repeatLimit(dto.getForwardRepeatLimit()),
                dto.isRecoveryEnabled(), dto.isAssetExpiryEnabled(), dto.isDynamicDnsEnabled(), dto.isLoginOutsideWhitelistEnabled(),
                StringUtils.trimToEmpty(dto.getLoginAllowedCidrs()), interval, System.currentTimeMillis(), CONFIG_ID);
        return getSettings();
    }

    public void sendTest() {
        Settings settings = loadSettings();
        requireConnection(settings);
        sendTelegram(settings, "[测试通知]\n状态：Telegram 连接正常\n时间：" + formatTime(System.currentTimeMillis()));
    }

    @Async
    public void notifyResourceIncident(String resourceType, long resourceId, String resourceName,
                                       String status, String detail, long incidentStartedAt) {
        try {
            Settings settings = loadSettings();
            int limit = repeatLimit(settings, resourceType);
            if (!settings.enabled() || !categoryEnabled(settings, resourceType) || limit <= 0) return;
            String eventKey = resourceEventKey(resourceType, resourceId, incidentStartedAt);
            synchronized (deliveryLock) {
                Delivery delivery = loadDelivery(eventKey);
                long now = System.currentTimeMillis();
                if (delivery.sendCount() >= limit || now - delivery.lastSentAt() < settings.repeatIntervalMinutes() * 60_000L) return;
                String message = "[" + resourceLabel(resourceType) + "异常]\n"
                        + "对象：" + clean(resourceName) + "\n"
                        + "状态：" + ("offline".equals(status) ? "离线" : "性能下降") + "\n"
                        + "时间：" + formatTime(now) + "\n"
                        + "原因：" + clean(detail);
                deliver(settings, eventKey, "resource", resourceType, resourceId, message, false);
            }
        } catch (Exception e) {
            log.warn("Telegram resource notification failed for {} {}: {}", resourceType, resourceId, e.getMessage());
        }
    }

    @Async
    public void notifyResourceRecovery(String resourceType, long resourceId, String resourceName,
                                       long incidentStartedAt) {
        try {
            Settings settings = loadSettings();
            if (!settings.enabled() || !settings.recoveryEnabled() || !categoryEnabled(settings, resourceType)) return;
            String eventKey = resourceEventKey(resourceType, resourceId, incidentStartedAt);
            synchronized (deliveryLock) {
                Delivery delivery = loadDelivery(eventKey);
                if (delivery.sendCount() <= 0 || delivery.recoverySent()) return;
                long now = System.currentTimeMillis();
                String message = "[" + resourceLabel(resourceType) + "恢复]\n"
                        + "对象：" + clean(resourceName) + "\n"
                        + "状态：正常\n"
                        + "时间：" + formatTime(now);
                deliver(settings, eventKey, "resource", resourceType, resourceId, message, true);
            }
        } catch (Exception e) {
            log.warn("Telegram recovery notification failed for {} {}: {}", resourceType, resourceId, e.getMessage());
        }
    }

    @Async
    public void notifyLoginOutsideWhitelist(String username, String sourceIp, long loginAt) {
        try {
            Settings settings = loadSettings();
            if (!settings.enabled() || !settings.loginOutsideWhitelistEnabled()
                    || IpAddressMatcher.isAllowed(sourceIp, settings.loginAllowedCidrs())) return;
            long bucketMs = settings.repeatIntervalMinutes() * 60_000L;
            long bucket = loginAt / Math.max(bucketMs, 1L);
            String eventKey = shortenKey("login:" + clean(username) + ":" + clean(sourceIp) + ":" + bucket);
            synchronized (deliveryLock) {
                if (loadDelivery(eventKey).sendCount() > 0) return;
                String message = "[登录提醒]\n"
                        + "账号：" + clean(username) + "\n"
                        + "地址：" + clean(sourceIp) + "\n"
                        + "结果：白名单外登录成功\n"
                        + "时间：" + formatTime(loginAt);
                deliver(settings, eventKey, "login", null, null, message, false);
            }
        } catch (Exception e) {
            log.warn("Telegram login notification failed: {}", e.getMessage());
        }
    }

    @Async
    public void notifyCrossEntrySwitch(long groupId, String groupName, String domain,
                                       String fromEntry, String toEntry, String reason,
                                       boolean success, long occurredAt) {
        try {
            Settings settings = loadSettings();
            if (!settings.enabled() || !settings.forwardEnabled()) return;
            String eventKey = shortenKey("cross-entry:" + groupId + ":" + occurredAt + ":" + success);
            synchronized (deliveryLock) {
                if (loadDelivery(eventKey).sendCount() > 0) return;
                String message = "[入口容灾" + (success ? "切换" : "失败") + "]\n"
                        + "业务：" + clean(groupName) + "\n"
                        + "域名：" + clean(domain) + "\n"
                        + "入口：" + clean(fromEntry) + " -> " + clean(toEntry) + "\n"
                        + "原因：" + clean(reason) + "\n"
                        + "时间：" + formatTime(occurredAt);
                deliver(settings, eventKey, "cross_entry", "forward", groupId, message, false);
            }
        } catch (Exception e) {
            log.warn("Telegram cross-entry notification failed for group {}: {}", groupId, e.getMessage());
        }
    }

    @Async
    public void notifyServerAssetExpiry(long assetId, String assetName, String provider, long expiryAt, long remainingDays) {
        try {
            Settings settings = loadSettings();
            if (!settings.enabled() || !settings.assetExpiryEnabled()) return;
            String eventKey = shortenKey("asset-expiry:" + assetId + ":" + expiryAt + ":" + remainingDays);
            synchronized (deliveryLock) {
                if (loadDelivery(eventKey).sendCount() > 0) return;
                String state = remainingDays == 0 ? "今天到期" : "剩余 " + remainingDays + " 天";
                String message = "[服务器到期]\n"
                        + "资产：" + clean(assetName) + "\n"
                        + "厂商：" + clean(provider) + "\n"
                        + "状态：" + state + "\n"
                        + "到期：" + formatTime(expiryAt);
                deliver(settings, eventKey, "asset_expiry", "asset", assetId, message, false);
            }
        } catch (Exception e) {
            log.warn("Telegram asset expiry notification failed for {}: {}", assetId, e.getMessage());
        }
    }

    @Async
    public void notifyDynamicDnsFailure(long ruleId, String ruleName, String detail) {
        try {
            Settings settings = loadSettings();
            if (!settings.enabled() || !settings.dynamicDnsEnabled()) return;
            String eventKey = shortenKey("dynamic-dns:" + ruleId + ":open");
            synchronized (deliveryLock) {
                if (loadDelivery(eventKey).sendCount() > 0) return;
                String message = "[动态 DNS 失败]\n"
                        + "规则：" + clean(ruleName) + "\n"
                        + "原因：" + clean(detail) + "\n"
                        + "时间：" + formatTime(System.currentTimeMillis());
                deliver(settings, eventKey, "dynamic_dns", "dynamic_dns", ruleId, message, false);
            }
        } catch (Exception e) {
            log.warn("Telegram dynamic DNS notification failed for {}: {}", ruleId, e.getMessage());
        }
    }

    @Async
    public void notifyDynamicDnsRecovery(long ruleId, String ruleName, String address) {
        try {
            Settings settings = loadSettings();
            if (!settings.enabled() || !settings.dynamicDnsEnabled() || !settings.recoveryEnabled()) return;
            String eventKey = shortenKey("dynamic-dns:" + ruleId + ":open");
            synchronized (deliveryLock) {
                Delivery delivery = loadDelivery(eventKey);
                if (delivery.sendCount() == 0 || delivery.recoverySent()) return;
                String message = "[动态 DNS 恢复]\n"
                        + "规则：" + clean(ruleName) + "\n"
                        + "地址：" + clean(address) + "\n"
                        + "状态：更新正常";
                deliver(settings, eventKey, "dynamic_dns", "dynamic_dns", ruleId, message, true);
                jdbcTemplate.update("DELETE FROM telegram_notification_delivery WHERE event_key=?", eventKey);
            }
        } catch (Exception e) {
            log.warn("Telegram dynamic DNS recovery failed for {}: {}", ruleId, e.getMessage());
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 40 3 * * ?")
    public void cleanupDeliveries() {
        jdbcTemplate.update("DELETE FROM telegram_notification_delivery WHERE updated_at < ?",
                System.currentTimeMillis() - DELIVERY_RETENTION_MS);
    }

    private void deliver(Settings settings, String eventKey, String eventType, String resourceType,
                         Long resourceId, String message, boolean recovery) {
        try {
            sendTelegram(settings, message);
            long now = System.currentTimeMillis();
            jdbcTemplate.update("INSERT INTO telegram_notification_delivery "
                            + "(event_key,event_type,resource_type,resource_id,send_count,last_sent_at,recovery_sent,last_error,updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                            + (recovery
                            ? "recovery_sent=1,last_error=NULL,updated_at=VALUES(updated_at)"
                            : "send_count=send_count+1,last_sent_at=VALUES(last_sent_at),last_error=NULL,updated_at=VALUES(updated_at)"),
                    eventKey, eventType, resourceType, resourceId, recovery ? 0 : 1, recovery ? null : now,
                    recovery, null, now);
        } catch (RuntimeException e) {
            recordFailure(eventKey, eventType, resourceType, resourceId, e.getMessage());
            throw e;
        }
    }

    private void sendTelegram(Settings settings, String message) {
        requireConnection(settings);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", settings.chatId());
        body.put("text", message);
        body.put("disable_web_page_preview", true);
        try {
            restTemplate.postForEntity("https://api.telegram.org/bot" + settings.botToken() + "/sendMessage",
                    new HttpEntity<>(body, headers), String.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("Telegram API 请求失败");
        }
    }

    private Settings loadSettings() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM telegram_notification_config WHERE id=?", CONFIG_ID);
        if (rows.isEmpty()) throw new IllegalStateException("Telegram 通知设置尚未初始化");
        Map<String, Object> row = rows.get(0);
        String encrypted = value(row.get("bot_token"));
        return new Settings(
                bool(row.get("enabled")), encrypted, decrypt(encrypted), value(row.get("chat_id")),
                bool(row.get("node_enabled")), integer(row.get("node_repeat_limit"), 1),
                bool(row.get("tunnel_enabled")), integer(row.get("tunnel_repeat_limit"), 1),
                bool(row.get("forward_enabled")), integer(row.get("forward_repeat_limit"), 1),
                bool(row.get("recovery_enabled")), bool(row.get("asset_expiry_enabled")), bool(row.get("dynamic_dns_enabled")),
                bool(row.get("login_outside_whitelist_enabled")),
                value(row.get("login_allowed_cidrs")), integer(row.get("repeat_interval_minutes"), 30)
        );
    }

    private Delivery loadDelivery(String eventKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT send_count,last_sent_at,recovery_sent FROM telegram_notification_delivery WHERE event_key=?", eventKey);
        if (rows.isEmpty()) return new Delivery(0, 0, false);
        Map<String, Object> row = rows.get(0);
        return new Delivery(integer(row.get("send_count"), 0), longValue(row.get("last_sent_at")), bool(row.get("recovery_sent")));
    }

    private void recordFailure(String eventKey, String eventType, String resourceType, Long resourceId, String error) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO telegram_notification_delivery "
                        + "(event_key,event_type,resource_type,resource_id,send_count,last_sent_at,recovery_sent,last_error,updated_at) "
                        + "VALUES (?,?,?,?,0,NULL,0,?,?) ON DUPLICATE KEY UPDATE last_error=VALUES(last_error),updated_at=VALUES(updated_at)",
                eventKey, eventType, resourceType, resourceId, StringUtils.abbreviate(error, 255), now);
    }

    private void requireConnection(Settings settings) {
        if (StringUtils.isBlank(settings.botToken()) || StringUtils.isBlank(settings.chatId())) {
            throw new IllegalArgumentException("请先填写 Bot Token 和 Chat ID");
        }
    }

    private boolean categoryEnabled(Settings settings, String type) {
        return switch (type) {
            case "node" -> settings.nodeEnabled();
            case "tunnel" -> settings.tunnelEnabled();
            case "forward" -> settings.forwardEnabled();
            case "certificate" -> settings.forwardEnabled();
            default -> false;
        };
    }

    private int repeatLimit(Settings settings, String type) {
        return switch (type) {
            case "node" -> settings.nodeRepeatLimit();
            case "tunnel" -> settings.tunnelRepeatLimit();
            case "forward" -> settings.forwardRepeatLimit();
            default -> 0;
        };
    }

    private String encrypt(String token) {
        try {
            byte[] nonce = new byte[12];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Bot Token 加密失败");
        }
    }

    private String decrypt(String encrypted) {
        if (StringUtils.isBlank(encrypted)) return "";
        if (!encrypted.startsWith("v1:")) return encrypted;
        try {
            String[] parts = encrypted.split(":", 3);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Stored Telegram token could not be decrypted");
            return "";
        }
    }

    private SecretKeySpec encryptionKey() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(encryptionSecret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private String resourceEventKey(String type, long id, long startedAt) {
        return shortenKey("resource:" + type + ":" + id + ":" + startedAt);
    }

    private String shortenKey(String value) {
        if (value.length() <= 191) return value;
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String resourceLabel(String type) {
        return switch (type) {
            case "node" -> "节点";
            case "tunnel" -> "隧道";
            case "forward" -> "转发";
            case "certificate" -> "证书";
            case "dynamic_dns" -> "动态 DNS";
            default -> "资源";
        };
    }

    private String clean(String value) {
        String cleaned = StringUtils.defaultIfBlank(value, "未知").replace('\n', ' ').replace('\r', ' ').trim();
        return StringUtils.abbreviate(cleaned, 160);
    }

    private String formatTime(long value) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(value));
    }

    private int repeatLimit(int value) {
        return clamp(value, 1, MAX_REPEAT_LIMIT);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private boolean bool(Object value) {
        return value != null && ("1".equals(value.toString()) || Boolean.parseBoolean(value.toString()));
    }

    private int integer(Object value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private long longValue(Object value) {
        try { return value == null ? 0 : Long.parseLong(value.toString()); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private record Settings(boolean enabled, String encryptedBotToken, String botToken, String chatId,
                            boolean nodeEnabled, int nodeRepeatLimit,
                            boolean tunnelEnabled, int tunnelRepeatLimit,
                            boolean forwardEnabled, int forwardRepeatLimit,
                            boolean recoveryEnabled, boolean assetExpiryEnabled, boolean dynamicDnsEnabled,
                            boolean loginOutsideWhitelistEnabled,
                            String loginAllowedCidrs, int repeatIntervalMinutes) { }

    private record Delivery(int sendCount, long lastSentAt, boolean recoverySent) { }
}
