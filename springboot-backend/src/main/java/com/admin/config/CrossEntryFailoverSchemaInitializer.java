package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class CrossEntryFailoverSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public CrossEntryFailoverSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS cross_entry_failover_group ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(100) NOT NULL, "
                    + "domain varchar(253) NOT NULL, zone_id varchar(64) NOT NULL, record_id varchar(64) NOT NULL, "
                    + "api_token varchar(2048) NOT NULL, record_type varchar(8) NOT NULL DEFAULT 'A', ttl int NOT NULL DEFAULT 60, "
                    + "probe_interval_ms int NOT NULL DEFAULT 2000, connect_timeout_ms int NOT NULL DEFAULT 1200, "
                    + "failure_threshold int NOT NULL DEFAULT 2, recovery_threshold int NOT NULL DEFAULT 3, "
                    + "cooldown_seconds int NOT NULL DEFAULT 30, auto_failback tinyint NOT NULL DEFAULT 0, enabled tinyint NOT NULL DEFAULT 1, "
                    + "state varchar(24) NOT NULL DEFAULT 'unknown', active_member_id bigint DEFAULT NULL, "
                    + "last_error varchar(500) DEFAULT NULL, last_checked_at bigint DEFAULT NULL, last_switch_at bigint DEFAULT NULL, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_cross_entry_domain (domain, record_type), KEY idx_cross_entry_due (enabled, last_checked_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS cross_entry_failover_member ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, group_id bigint NOT NULL, forward_id bigint NOT NULL, priority int NOT NULL, "
                    + "entry_node_id bigint NOT NULL, entry_host varchar(255) NOT NULL, entry_address varchar(128) NOT NULL, entry_port int NOT NULL, "
                    + "forward_name varchar(100) NOT NULL, node_name varchar(100) NOT NULL, status varchar(24) NOT NULL DEFAULT 'unknown', "
                    + "fail_count int NOT NULL DEFAULT 0, success_count int NOT NULL DEFAULT 0, latency_ms int DEFAULT NULL, "
                    + "last_error varchar(500) DEFAULT NULL, last_checked_at bigint DEFAULT NULL, last_healthy_at bigint DEFAULT NULL, "
                    + "last_failure_at bigint DEFAULT NULL, created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_cross_entry_member (group_id, forward_id), KEY idx_cross_entry_member_group (group_id, priority)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS cross_entry_failover_event ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, group_id bigint NOT NULL, from_member_id bigint DEFAULT NULL, "
                    + "to_member_id bigint DEFAULT NULL, from_node_name varchar(100) DEFAULT NULL, to_node_name varchar(100) DEFAULT NULL, "
                    + "reason varchar(255) NOT NULL, status varchar(24) NOT NULL, "
                    + "detail varchar(500) DEFAULT NULL, created_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "KEY idx_cross_entry_event_group (group_id, created_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("cross_entry_failover_event", "from_node_name", "varchar(100) DEFAULT NULL AFTER to_member_id");
            ensureColumn("cross_entry_failover_event", "to_node_name", "varchar(100) DEFAULT NULL AFTER from_node_name");
            ensureColumn("cross_entry_failover_group", "dns_zone_id", "bigint DEFAULT NULL AFTER domain");
        } catch (DataAccessException e) {
            log.error("Cross-entry failover storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (count == null || count == 0) jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
    }
}
