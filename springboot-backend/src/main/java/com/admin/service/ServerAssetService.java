package com.admin.service;

import com.admin.common.dto.ServerAssetSaveDto;
import com.admin.common.lang.R;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class ServerAssetService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbcTemplate;
    private final TelegramNotificationService telegramNotificationService;

    public ServerAssetService(JdbcTemplate jdbcTemplate, TelegramNotificationService telegramNotificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.telegramNotificationService = telegramNotificationService;
    }

    public R list() {
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT a.id,a.node_id AS nodeId,a.name,a.provider,a.region,a.cpu_spec AS cpuSpec,a.memory_mb AS memoryMb,"
                        + "a.disk_gb AS diskGb,a.bandwidth_mbps AS bandwidthMbps,a.currency,a.monthly_cost AS monthlyCost,"
                        + "a.purchase_date AS purchaseDate,a.expiry_date AS expiryDate,a.auto_renew AS autoRenew,a.ipv4,a.ipv6,a.asn,"
                        + "a.network_line AS networkLine,a.traffic_plan AS trafficPlan,a.tags,a.notes,a.reminder_enabled AS reminderEnabled,"
                        + "a.reminder_days AS reminderDays,a.created_time AS createdTime,a.updated_time AS updatedTime,"
                        + "n.name AS nodeName,n.status AS nodeStatus FROM server_asset a LEFT JOIN node n ON n.id=a.node_id "
                        + "ORDER BY CASE WHEN a.expiry_date IS NULL THEN 1 ELSE 0 END,a.expiry_date,a.updated_time DESC");
        LocalDate today = LocalDate.now(ZONE);
        items.forEach(row -> {
            Long expiry = nullableLong(row.get("expiryDate"));
            row.put("remainingDays", expiry == null ? null : ChronoUnit.DAYS.between(today, date(expiry)));
        });
        List<Map<String, Object>> costByCurrency = jdbcTemplate.queryForList(
                "SELECT currency,ROUND(SUM(monthly_cost),2) AS monthlyCost FROM server_asset GROUP BY currency ORDER BY currency");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", items.size());
        summary.put("expiringSoon", items.stream().filter(row -> {
            Object value = row.get("remainingDays");
            return value instanceof Number && ((Number) value).longValue() >= 0 && ((Number) value).longValue() <= 30;
        }).count());
        summary.put("expired", items.stream().filter(row -> row.get("remainingDays") instanceof Number
                && ((Number) row.get("remainingDays")).longValue() < 0).count());
        summary.put("costByCurrency", costByCurrency);
        return R.ok(Map.of("items", items, "summary", summary));
    }

    @Transactional
    public R save(ServerAssetSaveDto dto) {
        String name = StringUtils.trimToEmpty(dto.getName());
        if (name.isEmpty()) return R.err("请填写资产名称");
        String currency = StringUtils.defaultIfBlank(dto.getCurrency(), "CNY").trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3,8}")) return R.err("币种格式不正确");
        String reminderDays;
        try {
            reminderDays = normalizeReminderDays(dto.getReminderDays());
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        BigDecimal cost = dto.getMonthlyCost() == null ? BigDecimal.ZERO : dto.getMonthlyCost();
        if (cost.signum() < 0) return R.err("月度费用不能小于 0");
        long now = System.currentTimeMillis();
        Object[] values = {dto.getNodeId(), name, trim(dto.getProvider()), trim(dto.getRegion()), trim(dto.getCpuSpec()),
                positive(dto.getMemoryMb()), positive(dto.getDiskGb()), positive(dto.getBandwidthMbps()), currency, cost,
                dto.getPurchaseDate(), dto.getExpiryDate(), truth(dto.getAutoRenew()), trim(dto.getIpv4()), trim(dto.getIpv6()),
                trim(dto.getAsn()), trim(dto.getNetworkLine()), trim(dto.getTrafficPlan()), trim(dto.getTags()), trim(dto.getNotes()),
                truthDefault(dto.getReminderEnabled(), true), reminderDays, now};
        try {
            if (dto.getId() == null) {
                jdbcTemplate.update("INSERT INTO server_asset (node_id,name,provider,region,cpu_spec,memory_mb,disk_gb,bandwidth_mbps,currency,monthly_cost,"
                                + "purchase_date,expiry_date,auto_renew,ipv4,ipv6,asn,network_line,traffic_plan,tags,notes,reminder_enabled,reminder_days,created_time,updated_time) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9],
                        values[10], values[11], values[12], values[13], values[14], values[15], values[16], values[17], values[18], values[19],
                        values[20], values[21], now, now);
            } else {
                int updated = jdbcTemplate.update("UPDATE server_asset SET node_id=?,name=?,provider=?,region=?,cpu_spec=?,memory_mb=?,disk_gb=?,bandwidth_mbps=?,"
                                + "currency=?,monthly_cost=?,purchase_date=?,expiry_date=?,auto_renew=?,ipv4=?,ipv6=?,asn=?,network_line=?,traffic_plan=?,tags=?,notes=?,"
                                + "reminder_enabled=?,reminder_days=?,updated_time=? WHERE id=?",
                        values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9],
                        values[10], values[11], values[12], values[13], values[14], values[15], values[16], values[17], values[18], values[19],
                        values[20], values[21], now, dto.getId());
                if (updated == 0) return R.err("服务器资产不存在");
            }
            return R.ok();
        } catch (Exception e) {
            if (dto.getNodeId() != null && StringUtils.containsIgnoreCase(e.getMessage(), "uk_server_asset_node")) {
                return R.err("该节点已经关联了服务器资产");
            }
            throw e;
        }
    }

    public R delete(Long id) {
        return jdbcTemplate.update("DELETE FROM server_asset WHERE id=?", id) > 0 ? R.ok() : R.err("服务器资产不存在");
    }

    @Scheduled(cron = "0 15 * * * ?")
    public void sendExpiryReminders() {
        LocalDate today = LocalDate.now(ZONE);
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id,name,provider,expiry_date,reminder_days FROM server_asset WHERE reminder_enabled=1 AND expiry_date IS NOT NULL")) {
            long days = ChronoUnit.DAYS.between(today, date(((Number) row.get("expiry_date")).longValue()));
            boolean due = Arrays.stream(Objects.toString(row.get("reminder_days"), "").split(","))
                    .anyMatch(value -> value.trim().equals(Long.toString(days)));
            if (due) {
                telegramNotificationService.notifyServerAssetExpiry(((Number) row.get("id")).longValue(),
                        Objects.toString(row.get("name")), Objects.toString(row.get("provider"), "未填写"),
                        ((Number) row.get("expiry_date")).longValue(), days);
            }
        }
    }

    private String normalizeReminderDays(String raw) {
        String value = StringUtils.defaultIfBlank(raw, "30,7,3,1,0");
        return Arrays.stream(value.split("[,，\\s]+"))
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .map(part -> {
                    int day;
                    try { day = Integer.parseInt(part); } catch (NumberFormatException e) { throw new IllegalArgumentException("提醒天数格式不正确"); }
                    if (day < 0 || day > 3650) throw new IllegalArgumentException("提醒天数应在 0 到 3650 之间");
                    return day;
                }).distinct().sorted((a, b) -> Integer.compare(b, a)).map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("30,7,3,1,0");
    }

    private LocalDate date(long millis) { return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate(); }
    private Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private String trim(String value) { return StringUtils.trimToNull(value); }
    private Integer positive(Integer value) { return value == null || value <= 0 ? null : value; }
    private boolean truth(Boolean value) { return Boolean.TRUE.equals(value); }
    private boolean truthDefault(Boolean value, boolean fallback) { return value == null ? fallback : value; }
}
