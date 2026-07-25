package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserQuotaSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public UserQuotaSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureColumn("user", "flow_unlimited", "tinyint NOT NULL DEFAULT 0");
            ensureColumn("user", "forward_unlimited", "tinyint NOT NULL DEFAULT 0");
            ensureColumn("user", "owned_in_flow", "bigint NOT NULL DEFAULT 0");
            ensureColumn("user", "owned_out_flow", "bigint NOT NULL DEFAULT 0");
            makeNullable("user", "exp_time", "bigint");

            ensureColumn("user_tunnel", "flow_unlimited", "tinyint NOT NULL DEFAULT 0");
            ensureColumn("user_tunnel", "forward_unlimited", "tinyint NOT NULL DEFAULT 0");
            makeNullable("user_tunnel", "exp_time", "bigint");

            ensureColumn("user_node", "flow", "bigint NOT NULL DEFAULT 0");
            ensureColumn("user_node", "in_flow", "bigint NOT NULL DEFAULT 0");
            ensureColumn("user_node", "out_flow", "bigint NOT NULL DEFAULT 0");
            ensureColumn("user_node", "flow_unlimited", "tinyint NOT NULL DEFAULT 1");
            ensureColumn("user_node", "num", "int NOT NULL DEFAULT 0");
            ensureColumn("user_node", "forward_unlimited", "tinyint NOT NULL DEFAULT 1");
            ensureColumn("user_node", "flow_reset_time", "bigint NOT NULL DEFAULT 0");
            ensureColumn("user_node", "exp_time", "bigint DEFAULT NULL");
            ensureColumn("user_node", "status", "tinyint NOT NULL DEFAULT 1");
            ensureIndex("user_node", "uq_user_node", "UNIQUE KEY uq_user_node (user_id, node_id)");
        } catch (DataAccessException e) {
            if (!isDuplicateKeyError(e)) {
                log.error("User quota storage initialization failed", e);
            }
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

    private void makeNullable(String table, String column, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ? AND is_nullable = 'NO'",
                Integer.class, table, column);
        if (count != null && count > 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` MODIFY COLUMN `" + column + "` " + type + " DEFAULT NULL");
        }
    }

    private void ensureIndex(String table, String index, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD " + definition);
        }
    }

    private boolean isDuplicateKeyError(DataAccessException e) {
        return e.getMessage() != null && e.getMessage().contains("Duplicate key name");
    }
}
