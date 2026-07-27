package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TelegramNotificationSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public TelegramNotificationSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS telegram_notification_config ("
                    + "id int NOT NULL, "
                    + "enabled tinyint NOT NULL DEFAULT 0, "
                    + "bot_token varchar(768) DEFAULT NULL, "
                    + "chat_id varchar(128) DEFAULT NULL, "
                    + "node_enabled tinyint NOT NULL DEFAULT 1, "
                    + "node_repeat_limit int NOT NULL DEFAULT 1, "
                    + "tunnel_enabled tinyint NOT NULL DEFAULT 1, "
                    + "tunnel_repeat_limit int NOT NULL DEFAULT 1, "
                    + "forward_enabled tinyint NOT NULL DEFAULT 1, "
                    + "forward_repeat_limit int NOT NULL DEFAULT 1, "
                    + "recovery_enabled tinyint NOT NULL DEFAULT 1, "
                    + "login_outside_whitelist_enabled tinyint NOT NULL DEFAULT 0, "
                    + "login_allowed_cidrs text DEFAULT NULL, "
                    + "repeat_interval_minutes int NOT NULL DEFAULT 30, "
                    + "updated_at bigint NOT NULL, "
                    + "PRIMARY KEY (id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.update("INSERT IGNORE INTO telegram_notification_config (id, updated_at) VALUES (1, ?)",
                    System.currentTimeMillis());
            addColumn("telegram_notification_config", "asset_expiry_enabled", "tinyint NOT NULL DEFAULT 1");
            addColumn("telegram_notification_config", "dynamic_dns_enabled", "tinyint NOT NULL DEFAULT 1");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS telegram_notification_delivery ("
                    + "event_key varchar(191) NOT NULL, "
                    + "event_type varchar(32) NOT NULL, "
                    + "resource_type varchar(16) DEFAULT NULL, "
                    + "resource_id bigint DEFAULT NULL, "
                    + "send_count int NOT NULL DEFAULT 0, "
                    + "last_sent_at bigint DEFAULT NULL, "
                    + "recovery_sent tinyint NOT NULL DEFAULT 0, "
                    + "last_error varchar(255) DEFAULT NULL, "
                    + "updated_at bigint NOT NULL, "
                    + "PRIMARY KEY (event_key), "
                    + "KEY idx_telegram_delivery_updated (updated_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("Telegram notification storage initialization failed", e);
        }
    }

    private void addColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (count != null && count == 0) jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
