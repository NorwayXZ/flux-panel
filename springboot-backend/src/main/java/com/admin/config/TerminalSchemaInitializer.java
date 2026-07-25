package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TerminalSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public TerminalSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureColumn("node", "terminal_enabled", "tinyint NOT NULL DEFAULT 0");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS terminal_session_audit ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, session_id varchar(64) NOT NULL, "
                    + "user_id int NOT NULL, username varchar(80) NOT NULL, node_id bigint NOT NULL, "
                    + "node_name varchar(160) NOT NULL, source_ip varchar(128) DEFAULT NULL, "
                    + "status varchar(24) NOT NULL, close_reason varchar(160) DEFAULT NULL, "
                    + "started_at bigint NOT NULL, ended_at bigint DEFAULT NULL, PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_terminal_session (session_id), "
                    + "KEY idx_terminal_node_time (node_id, started_at), "
                    + "KEY idx_terminal_user_time (user_id, started_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("Terminal storage initialization failed", e);
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
}
