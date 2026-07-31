package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class PrivateProxySchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public PrivateProxySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS private_proxy ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(100) NOT NULL, "
                    + "node_id bigint NOT NULL, proxy_type varchar(32) NOT NULL, bind_ip varchar(128) NOT NULL DEFAULT '', "
                    + "listen_port int NOT NULL, auth_username varchar(64) NOT NULL, auth_password text NOT NULL, "
                    + "allowed_cidrs varchar(1000) NOT NULL DEFAULT '', state varchar(24) NOT NULL, expires_at bigint DEFAULT NULL, "
                    + "service_name varchar(120) NOT NULL, admission_name varchar(120) DEFAULT NULL, client_config text DEFAULT NULL, last_error varchar(500) DEFAULT NULL, "
                    + "in_flow bigint NOT NULL DEFAULT 0, out_flow bigint NOT NULL DEFAULT 0, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "KEY idx_private_proxy_user (user_id,state), KEY idx_private_proxy_node_port (node_id,listen_port,state), "
                    + "KEY idx_private_proxy_expiry (state,expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("client_config", "text DEFAULT NULL AFTER admission_name");
            ensureColumn("granted_by_user_id", "int DEFAULT NULL AFTER user_id");
            ensureColumn("flow_limit", "bigint NOT NULL DEFAULT 0 AFTER out_flow");
            ensureColumn("flow_unlimited", "tinyint NOT NULL DEFAULT 1 AFTER flow_limit");
            ensureColumn("flow_reset_day", "tinyint NOT NULL DEFAULT 0 AFTER flow_unlimited");
            ensureColumn("last_flow_reset_at", "bigint DEFAULT NULL AFTER flow_reset_day");
            ensureColumn("speed_limit_mbps", "int DEFAULT NULL AFTER last_flow_reset_at");
            ensureColumn("runtime_in_flow", "bigint NOT NULL DEFAULT 0 AFTER out_flow");
            ensureColumn("runtime_out_flow", "bigint NOT NULL DEFAULT 0 AFTER runtime_in_flow");
            ensureIndex("idx_private_proxy_grant", "granted_by_user_id,user_id,state");
            ensureVarcharLength("proxy_type", 32);
        } catch (DataAccessException e) {
            log.error("Private proxy storage initialization failed", e);
        }
    }

    private void ensureIndex(String index, String columns) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'private_proxy' AND INDEX_NAME = ?", Integer.class, index);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE private_proxy ADD KEY `" + index + "` (" + columns + ")");
        }
    }

    private void ensureColumn(String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'private_proxy' AND COLUMN_NAME = ?", Integer.class, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE private_proxy ADD COLUMN `" + column + "` " + definition);
        }
    }

    private void ensureVarcharLength(String column, int minimumLength) {
        Long length = jdbcTemplate.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'private_proxy' AND COLUMN_NAME = ?", Long.class, column);
        if (length == null || length < minimumLength) {
            jdbcTemplate.execute("ALTER TABLE private_proxy MODIFY COLUMN `" + column + "` varchar(" + minimumLength + ") NOT NULL");
        }
    }
}
