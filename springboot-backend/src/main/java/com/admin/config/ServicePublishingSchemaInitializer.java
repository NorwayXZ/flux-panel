package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class ServicePublishingSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public ServicePublishingSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS internal_connector ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(80) NOT NULL, "
                    + "secret varchar(64) NOT NULL, allowed_cidrs varchar(1000) NOT NULL, platform varchar(16) NOT NULL DEFAULT 'linux', "
                    + "version varchar(40) DEFAULT NULL, "
                    + "remote_ip varchar(128) DEFAULT NULL, last_seen bigint DEFAULT NULL, status tinyint NOT NULL DEFAULT 1, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), UNIQUE KEY uk_connector_secret (secret), "
                    + "KEY idx_connector_user (user_id, status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS port_pool ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, name varchar(80) NOT NULL, node_id bigint NOT NULL, "
                    + "bind_ip varchar(128) NOT NULL DEFAULT '', public_host varchar(255) NOT NULL, start_port int NOT NULL, end_port int NOT NULL, "
                    + "control_port int NOT NULL, auth_username varchar(64) NOT NULL, auth_password varchar(64) NOT NULL, "
                    + "default_lease_hours int NOT NULL DEFAULT 24, max_lease_hours int NOT NULL DEFAULT 720, cooldown_seconds int NOT NULL DEFAULT 60, "
                    + "status tinyint NOT NULL DEFAULT 1, created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_pool_control (node_id, bind_ip, control_port), KEY idx_pool_node (node_id, status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS published_service ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(100) NOT NULL, connector_id bigint NOT NULL, "
                    + "pool_id bigint NOT NULL, lease_id bigint DEFAULT NULL, target_host varchar(255) NOT NULL, target_port int NOT NULL, "
                    + "public_port int DEFAULT NULL, protocol varchar(12) NOT NULL DEFAULT 'tcp', state varchar(24) NOT NULL, "
                    + "lease_hours int NOT NULL, expires_at bigint DEFAULT NULL, service_name varchar(120) DEFAULT NULL, last_error varchar(500) DEFAULT NULL, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), KEY idx_service_user (user_id, state), "
                    + "KEY idx_service_expire (state, expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS port_lease ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, pool_id bigint NOT NULL, service_id bigint DEFAULT NULL, user_id int NOT NULL, "
                    + "port int NOT NULL, protocol varchar(12) NOT NULL, state varchar(24) NOT NULL, expires_at bigint DEFAULT NULL, release_after bigint DEFAULT NULL, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), UNIQUE KEY uk_active_pool_port (pool_id, protocol, port), "
                    + "KEY idx_lease_expire (state, expires_at), KEY idx_lease_release (state, release_after)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS port_lease_event ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, lease_id bigint DEFAULT NULL, service_id bigint DEFAULT NULL, user_id int NOT NULL, "
                    + "event_type varchar(32) NOT NULL, detail varchar(500) DEFAULT NULL, created_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "KEY idx_lease_event_service (service_id, created_time)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS port_pool_grant ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, pool_id bigint NOT NULL, user_id int NOT NULL, "
                    + "start_port int NOT NULL, end_port int NOT NULL, status tinyint NOT NULL DEFAULT 1, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "KEY idx_port_grant_user (user_id, status), KEY idx_port_grant_pool (pool_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS service_publish_lock (id int NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.update("INSERT IGNORE INTO service_publish_lock (id) VALUES (1)");
            ensureColumn("internal_connector", "platform", "varchar(16) NOT NULL DEFAULT 'linux'");
            ensureColumn("port_lease", "grant_id", "bigint DEFAULT NULL AFTER pool_id");
            ensureIndex("port_lease", "idx_lease_grant", "grant_id, state");
        } catch (DataAccessException e) {
            log.error("Service publishing storage initialization failed", e);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        }
    }

    private void ensureIndex(String table, String index, String columns) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD KEY `" + index + "` (" + columns + ")");
        }
    }
}
