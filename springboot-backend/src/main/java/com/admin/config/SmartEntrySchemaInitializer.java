package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class SmartEntrySchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public SmartEntrySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS smart_entry_group ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,user_id int NOT NULL,name varchar(100) NOT NULL,"
                    + "provider_ref_id bigint NOT NULL,provider varchar(24) NOT NULL,zone_name varchar(253) NOT NULL,domain varchar(253) NOT NULL,"
                    + "record_type varchar(8) NOT NULL DEFAULT 'A',ttl int NOT NULL DEFAULT 60,public_port int NOT NULL,"
                    + "probe_interval_ms int NOT NULL DEFAULT 5000,connect_timeout_ms int NOT NULL DEFAULT 1500,"
                    + "failure_threshold int NOT NULL DEFAULT 2,recovery_threshold int NOT NULL DEFAULT 3,enabled tinyint NOT NULL DEFAULT 1,"
                    + "state varchar(24) NOT NULL DEFAULT 'unknown',last_error varchar(500) DEFAULT NULL,last_checked_at bigint DEFAULT NULL,"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "UNIQUE KEY uk_smart_entry_domain(provider_ref_id,zone_name,domain,record_type),KEY idx_smart_entry_due(enabled,last_checked_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS smart_entry_route ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,group_id bigint NOT NULL,carrier varchar(16) NOT NULL,forward_id bigint NOT NULL,"
                    + "entry_node_id bigint NOT NULL,entry_host varchar(253) NOT NULL,entry_address varchar(128) NOT NULL,entry_port int NOT NULL,"
                    + "forward_name varchar(100) NOT NULL,node_name varchar(100) NOT NULL,record_id varchar(128) DEFAULT NULL,"
                    + "managed_created tinyint NOT NULL DEFAULT 1,original_address varchar(128) DEFAULT NULL,original_ttl int DEFAULT NULL,"
                    + "current_forward_id bigint DEFAULT NULL,current_address varchar(128) DEFAULT NULL,status varchar(24) NOT NULL DEFAULT 'unknown',"
                    + "fail_count int NOT NULL DEFAULT 0,success_count int NOT NULL DEFAULT 0,latency_ms int DEFAULT NULL,last_error varchar(500) DEFAULT NULL,"
                    + "telemetry_ready tinyint NOT NULL DEFAULT 0,total_connections bigint NOT NULL DEFAULT 0,current_connections bigint NOT NULL DEFAULT 0,"
                    + "reported_total_connections bigint NOT NULL DEFAULT 0,pending_connections bigint NOT NULL DEFAULT 0,"
                    + "pending_probe_connections bigint NOT NULL DEFAULT 0,"
                    + "activity_in_flow bigint NOT NULL DEFAULT 0,activity_out_flow bigint NOT NULL DEFAULT 0,"
                    + "last_activity_at bigint DEFAULT NULL,last_telemetry_at bigint DEFAULT NULL,"
                    + "last_checked_at bigint DEFAULT NULL,created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "UNIQUE KEY uk_smart_entry_carrier(group_id,carrier),KEY idx_smart_entry_forward(forward_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS smart_entry_event ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,group_id bigint NOT NULL,carrier varchar(16) DEFAULT NULL,event_type varchar(32) NOT NULL,"
                    + "status varchar(24) NOT NULL,detail varchar(500) DEFAULT NULL,created_time bigint NOT NULL,PRIMARY KEY(id),"
                    + "KEY idx_smart_entry_event(group_id,created_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            addColumn("smart_entry_route", "telemetry_ready", "tinyint NOT NULL DEFAULT 0");
            addColumn("smart_entry_route", "total_connections", "bigint NOT NULL DEFAULT 0");
            addColumn("smart_entry_route", "current_connections", "bigint NOT NULL DEFAULT 0");
            addColumn("smart_entry_route", "reported_total_connections", "bigint NOT NULL DEFAULT 0");
            addColumn("smart_entry_route", "pending_connections", "bigint NOT NULL DEFAULT 0");
            addColumn("smart_entry_route", "activity_in_flow", "bigint NOT NULL DEFAULT 0");
            addColumn("smart_entry_route", "activity_out_flow", "bigint NOT NULL DEFAULT 0");
            addColumn("smart_entry_route", "last_activity_at", "bigint DEFAULT NULL");
            addColumn("smart_entry_route", "last_telemetry_at", "bigint DEFAULT NULL");
            boolean probeTrackingAdded = addColumn("smart_entry_route", "pending_probe_connections", "bigint NOT NULL DEFAULT 0");
            if (probeTrackingAdded) {
                jdbcTemplate.update("UPDATE smart_entry_route SET telemetry_ready=0,total_connections=0,current_connections=0,"
                        + "reported_total_connections=0,pending_connections=0,pending_probe_connections=0,activity_in_flow=0,"
                        + "activity_out_flow=0,last_activity_at=NULL,last_telemetry_at=NULL");
                jdbcTemplate.update("DELETE FROM smart_entry_event WHERE event_type IN ('first_active','resumed','new_connections')");
            }
        } catch (DataAccessException e) {
            log.error("Smart entry storage initialization failed", e);
        }
    }

    private boolean addColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", Integer.class, table, column);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
            return true;
        }
        return false;
    }
}
