package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
@DependsOn("servicePublishingSchemaInitializer")
public class LanDiscoverySchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public LanDiscoverySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        safeStep("discovery_enabled", () -> ensureColumn("discovery_enabled", "tinyint NOT NULL DEFAULT 0 AFTER last_seen"));
        safeStep("discovery_status", () -> ensureColumn("discovery_status", "varchar(24) NOT NULL DEFAULT 'disabled' AFTER discovery_enabled"));
        safeStep("discovery_last_scan_at", () -> ensureColumn("discovery_last_scan_at", "bigint DEFAULT NULL AFTER discovery_status"));
        safeStep("discovery_last_cidr", () -> ensureColumn("discovery_last_cidr", "varchar(255) DEFAULT NULL AFTER discovery_last_scan_at"));
        safeStep("discovery_last_error", () -> ensureColumn("discovery_last_error", "varchar(500) DEFAULT NULL AFTER discovery_last_cidr"));
        safeStep("lan_discovered_service", () -> jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS lan_discovered_service ("
                + "id bigint unsigned NOT NULL AUTO_INCREMENT, connector_id bigint NOT NULL, user_id int NOT NULL, "
                + "host varchar(45) NOT NULL, port int NOT NULL, service_type varchar(40) NOT NULL, service_name varchar(100) NOT NULL, "
                + "product varchar(160) DEFAULT NULL, title varchar(160) DEFAULT NULL, confidence varchar(16) NOT NULL DEFAULT 'medium', "
                + "`sensitive` tinyint NOT NULL DEFAULT 0, first_seen_at bigint NOT NULL, last_seen_at bigint NOT NULL, "
                + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                + "UNIQUE KEY uk_lan_service_endpoint (connector_id,host,port), "
                + "KEY idx_lan_service_user (user_id,last_seen_at), KEY idx_lan_service_connector (connector_id,last_seen_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"));
    }

    private void ensureColumn(String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='internal_connector' AND column_name=?",
                Integer.class, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE internal_connector ADD COLUMN `" + column + "` " + definition);
        }
    }

    private void safeStep(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("LAN discovery schema step {} failed", step, e);
        }
    }
}
