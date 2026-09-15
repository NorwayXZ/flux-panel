package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class AuthorizedEntrySchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public AuthorizedEntrySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS authorized_entry_template ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,name varchar(100) NOT NULL,source_group_id bigint NOT NULL,"
                    + "start_port int NOT NULL,end_port int NOT NULL,protocol_mode varchar(16) NOT NULL DEFAULT 'tcp',blocked_target_cidrs text DEFAULT NULL,block_platform_nodes tinyint NOT NULL DEFAULT 1,status tinyint NOT NULL DEFAULT 1,"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_authorized_entry_template_group (source_group_id),KEY idx_authorized_entry_template_status (status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS authorized_entry_grant ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,template_id bigint NOT NULL,user_id int NOT NULL,name varchar(100) NOT NULL,"
                    + "access_host varchar(253) NOT NULL,max_ports int NOT NULL,flow_limit_bytes bigint NOT NULL DEFAULT 0,"
                    + "flow_direction varchar(16) NOT NULL DEFAULT 'total',flow_reset_day tinyint NOT NULL DEFAULT 0,used_bytes bigint NOT NULL DEFAULT 0,"
                    + "last_reset_at bigint DEFAULT NULL,expires_at bigint DEFAULT NULL,state varchar(24) NOT NULL DEFAULT 'active',"
                    + "last_error varchar(500) DEFAULT NULL,created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY (id),"
                    + "KEY idx_authorized_entry_grant_user (user_id,state),KEY idx_authorized_entry_grant_cycle (state,expires_at,flow_reset_day)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS authorized_entry_port ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,grant_id bigint NOT NULL,user_id int NOT NULL,port int NOT NULL,"
                    + "target_host varchar(128) NOT NULL,target_port int NOT NULL,protocol_mode varchar(16) NOT NULL,state varchar(24) NOT NULL DEFAULT 'active',"
                    + "in_flow bigint NOT NULL DEFAULT 0,out_flow bigint NOT NULL DEFAULT 0,charged_bytes bigint NOT NULL DEFAULT 0,"
                    + "last_error varchar(500) DEFAULT NULL,created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_authorized_entry_grant_port (grant_id,port),KEY idx_authorized_entry_port_user (user_id,state)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS authorized_entry_forward ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,port_id bigint NOT NULL,forward_id bigint NOT NULL,node_id bigint NOT NULL,"
                    + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_authorized_entry_forward (forward_id),KEY idx_authorized_entry_port_forward (port_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("authorized_entry_template", "blocked_target_cidrs", "text DEFAULT NULL AFTER protocol_mode");
            ensureColumn("authorized_entry_template", "block_platform_nodes", "tinyint NOT NULL DEFAULT 1 AFTER blocked_target_cidrs");
        } catch (DataAccessException e) {
            log.error("Authorized entry storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (DataAccessException ignored) {
            // The initializer is also used on existing installations where the column may already exist.
        }
    }
}
