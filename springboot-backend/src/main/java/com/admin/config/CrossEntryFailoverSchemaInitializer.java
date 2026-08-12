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
            // Default stays failover so upgrading never changes existing DNS behaviour.
            ensureColumn("cross_entry_failover_group", "routing_mode", "varchar(24) NOT NULL DEFAULT 'failover' AFTER auto_failback");
            ensureColumn("cross_entry_failover_group", "quality_enabled", "tinyint NOT NULL DEFAULT 0 AFTER routing_mode");
            ensureColumn("cross_entry_failover_group", "quality_probe_source_type", "varchar(16) NOT NULL DEFAULT 'panel' AFTER quality_enabled");
            ensureColumn("cross_entry_failover_group", "quality_probe_source_id", "bigint DEFAULT NULL AFTER quality_probe_source_type");
            ensureColumn("cross_entry_failover_group", "quality_probe_count", "int NOT NULL DEFAULT 4 AFTER quality_probe_source_id");
            ensureColumn("cross_entry_failover_group", "quality_degrade_threshold_ms", "int NOT NULL DEFAULT 100 AFTER quality_probe_count");
            ensureColumn("cross_entry_failover_group", "quality_recover_threshold_ms", "int NOT NULL DEFAULT 60 AFTER quality_degrade_threshold_ms");
            ensureColumn("cross_entry_failover_group", "quality_degrade_factor", "decimal(8,2) NOT NULL DEFAULT 3.00 AFTER quality_recover_threshold_ms");
            ensureColumn("cross_entry_failover_group", "quality_recover_factor", "decimal(8,2) NOT NULL DEFAULT 1.80 AFTER quality_degrade_factor");
            ensureColumn("cross_entry_failover_group", "quality_degrade_samples", "int NOT NULL DEFAULT 3 AFTER quality_recover_factor");
            ensureColumn("cross_entry_failover_group", "quality_recover_samples", "int NOT NULL DEFAULT 3 AFTER quality_degrade_samples");
            ensureColumn("cross_entry_failover_group", "quality_loss_threshold_percent", "decimal(8,2) NOT NULL DEFAULT 30.00 AFTER quality_recover_samples");
            ensureColumn("cross_entry_failover_group", "quality_p95_threshold_ms", "int NOT NULL DEFAULT 100 AFTER quality_loss_threshold_percent");
            ensureColumn("cross_entry_failover_group", "quality_jitter_threshold_ms", "int NOT NULL DEFAULT 50 AFTER quality_p95_threshold_ms");
            ensureColumn("cross_entry_failover_group", "quality_fixed_target_enabled", "tinyint NOT NULL DEFAULT 0 AFTER quality_jitter_threshold_ms");
            ensureColumn("cross_entry_failover_group", "quality_fixed_target_ms", "int NOT NULL DEFAULT 20 AFTER quality_fixed_target_enabled");
            ensureColumn("cross_entry_failover_group", "quality_fixed_target_strict", "tinyint NOT NULL DEFAULT 1 AFTER quality_fixed_target_ms");
            ensureColumn("cross_entry_failover_group", "quality_flap_guard_enabled", "tinyint NOT NULL DEFAULT 1 AFTER quality_fixed_target_strict");
            ensureColumn("cross_entry_failover_group", "quality_flap_window_seconds", "int NOT NULL DEFAULT 900 AFTER quality_flap_guard_enabled");
            ensureColumn("cross_entry_failover_group", "quality_flap_threshold", "int NOT NULL DEFAULT 3 AFTER quality_flap_window_seconds");
            ensureColumn("cross_entry_failover_group", "quality_flap_suppress_seconds", "int NOT NULL DEFAULT 1800 AFTER quality_flap_threshold");
            ensureColumn("cross_entry_failover_group", "smart_selection_enabled", "tinyint NOT NULL DEFAULT 1 AFTER quality_flap_suppress_seconds");
            ensureColumn("cross_entry_failover_group", "degraded_fallback_enabled", "tinyint NOT NULL DEFAULT 1 AFTER smart_selection_enabled");
            ensureColumn("cross_entry_failover_group", "same_fault_avoidance_enabled", "tinyint NOT NULL DEFAULT 1 AFTER degraded_fallback_enabled");
            ensureColumn("cross_entry_failover_group", "topology_avoidance_enabled", "tinyint NOT NULL DEFAULT 1 AFTER same_fault_avoidance_enabled");
            ensureColumn("cross_entry_failover_group", "min_residency_seconds", "int NOT NULL DEFAULT 300 AFTER topology_avoidance_enabled");
            ensureColumn("cross_entry_failover_group", "failback_gain_ms", "int NOT NULL DEFAULT 10 AFTER min_residency_seconds");
            ensureColumn("cross_entry_failover_group", "failback_gain_percent", "decimal(8,2) NOT NULL DEFAULT 20.00 AFTER failback_gain_ms");
            ensureColumn("cross_entry_failover_group", "preheat_enabled", "tinyint NOT NULL DEFAULT 1 AFTER failback_gain_percent");
            ensureColumn("cross_entry_failover_group", "preheat_backup_count", "int NOT NULL DEFAULT 3 AFTER preheat_enabled");
            ensureColumn("cross_entry_failover_group", "post_switch_verify_enabled", "tinyint NOT NULL DEFAULT 1 AFTER preheat_backup_count");
            ensureColumn("cross_entry_failover_group", "dns_verify_enabled", "tinyint NOT NULL DEFAULT 1 AFTER post_switch_verify_enabled");
            ensureColumn("cross_entry_failover_group", "manual_control_mode", "varchar(16) NOT NULL DEFAULT 'auto' AFTER dns_verify_enabled");
            ensureColumn("cross_entry_failover_group", "locked_member_id", "bigint DEFAULT NULL AFTER manual_control_mode");
            ensureColumn("cross_entry_failover_group", "quality_probe_status", "varchar(24) NOT NULL DEFAULT 'disabled' AFTER locked_member_id");
            ensureColumn("cross_entry_failover_group", "quality_probe_error", "varchar(500) DEFAULT NULL AFTER quality_probe_status");
            ensureColumn("cross_entry_failover_group", "quality_probe_at", "bigint DEFAULT NULL AFTER quality_probe_error");
            ensureColumn("cross_entry_failover_member", "weight", "int NOT NULL DEFAULT 100 AFTER priority");
            ensureColumn("cross_entry_failover_member", "enabled", "tinyint NOT NULL DEFAULT 1 AFTER weight");
            ensureColumn("cross_entry_failover_member", "quality_latency_ms", "int DEFAULT NULL AFTER latency_ms");
            ensureColumn("cross_entry_failover_member", "quality_p95_ms", "int DEFAULT NULL AFTER quality_latency_ms");
            ensureColumn("cross_entry_failover_member", "quality_jitter_ms", "int DEFAULT NULL AFTER quality_p95_ms");
            ensureColumn("cross_entry_failover_member", "quality_loss_percent", "decimal(8,2) DEFAULT NULL AFTER quality_jitter_ms");
            ensureColumn("cross_entry_failover_member", "quality_baseline_ms", "int DEFAULT NULL AFTER quality_loss_percent");
            ensureColumn("cross_entry_failover_member", "quality_preheated", "tinyint NOT NULL DEFAULT 0 AFTER quality_baseline_ms");
            ensureColumn("cross_entry_failover_member", "quality_state", "varchar(24) NOT NULL DEFAULT 'unknown' AFTER quality_baseline_ms");
            ensureColumn("cross_entry_failover_member", "quality_bad_count", "int NOT NULL DEFAULT 0 AFTER quality_state");
            ensureColumn("cross_entry_failover_member", "quality_good_count", "int NOT NULL DEFAULT 0 AFTER quality_bad_count");
            ensureColumn("cross_entry_failover_member", "quality_flap_count", "int NOT NULL DEFAULT 0 AFTER quality_good_count");
            ensureColumn("cross_entry_failover_member", "quality_flap_window_started_at", "bigint DEFAULT NULL AFTER quality_flap_count");
            ensureColumn("cross_entry_failover_member", "quality_suppressed_until", "bigint DEFAULT NULL AFTER quality_flap_window_started_at");
            ensureColumn("cross_entry_failover_member", "quality_suppressed_reason", "varchar(255) DEFAULT NULL AFTER quality_suppressed_until");
            ensureColumn("cross_entry_failover_member", "quality_last_error", "varchar(500) DEFAULT NULL AFTER quality_suppressed_reason");
            ensureColumn("cross_entry_failover_member", "quality_checked_at", "bigint DEFAULT NULL AFTER quality_last_error");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS cross_entry_dns_record ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,group_id bigint NOT NULL,member_id bigint NOT NULL,"
                    + "provider_record_id varchar(64) NOT NULL,content varchar(255) NOT NULL,created_time bigint NOT NULL,updated_time bigint NOT NULL,"
                    + "PRIMARY KEY (id),UNIQUE KEY uk_cross_entry_dns_member (group_id,member_id),"
                    + "UNIQUE KEY uk_cross_entry_dns_provider (provider_record_id),KEY idx_cross_entry_dns_group (group_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
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
