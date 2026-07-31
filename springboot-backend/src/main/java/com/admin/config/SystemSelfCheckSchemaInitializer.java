package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class SystemSelfCheckSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public SystemSelfCheckSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS system_self_check_run ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,status varchar(24) NOT NULL,scope_node_id bigint DEFAULT NULL,"
                    + "scope_type varchar(24) DEFAULT NULL,scope_resource_id bigint DEFAULT NULL,"
                    + "total_checks int NOT NULL DEFAULT 0,healthy_count int NOT NULL DEFAULT 0,warning_count int NOT NULL DEFAULT 0,"
                    + "failed_count int NOT NULL DEFAULT 0,skipped_count int NOT NULL DEFAULT 0,message varchar(500) DEFAULT NULL,"
                    + "requested_by int NOT NULL,started_at bigint NOT NULL,finished_at bigint DEFAULT NULL,PRIMARY KEY(id),"
                    + "KEY idx_self_check_started(started_at),KEY idx_self_check_status(status,started_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            addColumn("system_self_check_run", "scope_type", "varchar(24) DEFAULT NULL");
            addColumn("system_self_check_run", "scope_resource_id", "bigint DEFAULT NULL");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS system_self_check_finding ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,run_id bigint NOT NULL,category varchar(32) NOT NULL,"
                    + "resource_type varchar(32) NOT NULL,resource_id bigint DEFAULT NULL,resource_name varchar(253) DEFAULT NULL,"
                    + "status varchar(16) NOT NULL,fault_segment varchar(120) NOT NULL,summary varchar(500) NOT NULL,"
                    + "evidence text DEFAULT NULL,impact varchar(500) DEFAULT NULL,remediation text DEFAULT NULL,sort_order int NOT NULL DEFAULT 0,"
                    + "created_at bigint NOT NULL,PRIMARY KEY(id),KEY idx_self_check_finding_run(run_id,status,sort_order),"
                    + "KEY idx_self_check_finding_resource(resource_type,resource_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agent_identity_baseline ("
                    + "node_id bigint NOT NULL,machine_fingerprint varchar(64) NOT NULL,hostname varchar(253) DEFAULT NULL,"
                    + "first_seen_at bigint NOT NULL,last_seen_at bigint NOT NULL,PRIMARY KEY(node_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("System self-check storage initialization failed", e);
        }
    }

    private void addColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        }
    }
}
