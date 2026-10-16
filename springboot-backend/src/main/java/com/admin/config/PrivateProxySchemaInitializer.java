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
            // 出口设置。带默认值，旧版本后端写入时不提供这两列也能成功，版本可以回退。
            ensureColumn("exit_type", "varchar(16) NOT NULL DEFAULT 'node'");
            ensureColumn("exit_country", "varchar(8) DEFAULT NULL");
            ensureColumn("exit_route_port", "int DEFAULT NULL");
            ensureColumn("exit_switch_token", "varchar(64) DEFAULT NULL");
            // 连接地址、自检结果和最近使用时间。都允许为空或有默认值，版本可以回退。
            ensureColumn("connect_host", "varchar(253) DEFAULT NULL");
            ensureColumn("last_check_at", "bigint DEFAULT NULL");
            ensureColumn("last_check_ok", "tinyint DEFAULT NULL");
            ensureColumn("last_check_ip", "varchar(64) DEFAULT NULL");
            ensureColumn("last_check_latency", "int DEFAULT NULL");
            ensureColumn("last_check_error", "varchar(500) DEFAULT NULL");
            ensureColumn("check_failures", "int NOT NULL DEFAULT 0");
            ensureColumn("last_active_at", "bigint DEFAULT NULL");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS private_proxy_traffic ("
                    + "proxy_id bigint NOT NULL, hour_at bigint NOT NULL, in_flow bigint NOT NULL DEFAULT 0, "
                    + "out_flow bigint NOT NULL DEFAULT 0, PRIMARY KEY (proxy_id,hour_at), KEY idx_private_proxy_traffic_hour (hour_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS private_proxy_subscription ("
                    + "user_id int NOT NULL, token varchar(64) NOT NULL, created_time bigint NOT NULL, "
                    + "PRIMARY KEY (user_id), UNIQUE KEY uk_private_proxy_subscription_token (token)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureIndex("idx_private_proxy_grant", "granted_by_user_id,user_id,state");
            // 自定义出口和按域名分流（2.76.0）。都可为空，旧版本忽略即可。
            ensureColumn("exit_custom_id", "bigint DEFAULT NULL");
            ensureColumn("route_rules", "text DEFAULT NULL");
            ensureColumn("route_countries", "varchar(255) DEFAULT NULL");
            ensureColumn("route_exit_ports", "varchar(500) DEFAULT NULL");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS proxy_exit ("
                    + "id bigint NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(100) NOT NULL, "
                    + "protocol varchar(32) NOT NULL, server varchar(253) NOT NULL, port int NOT NULL, "
                    + "outbound text NOT NULL, last_test_at bigint DEFAULT NULL, last_test_ok tinyint DEFAULT NULL, "
                    + "last_test_ip varchar(64) DEFAULT NULL, last_test_country varchar(8) DEFAULT NULL, "
                    + "last_test_latency int DEFAULT NULL, last_test_error varchar(500) DEFAULT NULL, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, "
                    + "PRIMARY KEY (id), KEY idx_proxy_exit_user (user_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS route_preset_data ("
                    + "preset_key varchar(32) NOT NULL, domain_suffix mediumtext, domain mediumtext, domain_keyword text, "
                    + "updated_at bigint NOT NULL, checked_at bigint NOT NULL, last_error varchar(500) DEFAULT NULL, "
                    + "PRIMARY KEY (preset_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            SchemaSupport.ensureColumn(jdbcTemplate, "proxy_exit", "members", "varchar(1000) DEFAULT NULL");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS route_list_cache ("
                    + "list_key varchar(100) NOT NULL, data mediumtext, entry_count int NOT NULL DEFAULT 0, "
                    + "updated_at bigint NOT NULL, checked_at bigint NOT NULL, last_error varchar(500) DEFAULT NULL, "
                    + "PRIMARY KEY (list_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS route_ruleset ("
                    + "id bigint NOT NULL AUTO_INCREMENT, user_id int NOT NULL, name varchar(100) NOT NULL, url varchar(1000) NOT NULL, "
                    + "behavior varchar(16) NOT NULL, interval_hours int NOT NULL DEFAULT 72, data mediumtext, "
                    + "entry_count int NOT NULL DEFAULT 0, skipped int NOT NULL DEFAULT 0, updated_at bigint DEFAULT NULL, "
                    + "checked_at bigint DEFAULT NULL, last_error varchar(500) DEFAULT NULL, created_time bigint NOT NULL, "
                    + "PRIMARY KEY (id), KEY idx_route_ruleset_user (user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureMediumText("route_rules");
            ensureVarcharLength("proxy_type", 32);
        } catch (DataAccessException e) {
            log.error("Private proxy storage initialization failed", e);
        }
    }

    private void ensureIndex(String index, String columns) {
        SchemaSupport.ensureIndex(jdbcTemplate, "private_proxy", index, columns);
    }

    private void ensureColumn(String column, String definition) {
        SchemaSupport.ensureColumn(jdbcTemplate, "private_proxy", column, definition);
    }

    /** Imported Clash rules can be larger than a text column holds. */
    private void ensureMediumText(String column) {
        String type = jdbcTemplate.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'private_proxy' AND COLUMN_NAME = ?", String.class, column);
        if ("text".equalsIgnoreCase(type)) {
            jdbcTemplate.execute("ALTER TABLE private_proxy MODIFY COLUMN `" + column + "` mediumtext DEFAULT NULL");
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
