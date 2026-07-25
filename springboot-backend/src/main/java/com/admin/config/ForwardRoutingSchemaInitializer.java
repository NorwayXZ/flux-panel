package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ForwardRoutingSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public ForwardRoutingSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureColumn("previous_active_tunnel_id", "int DEFAULT NULL AFTER last_health_check");
            ensureColumn("last_route_switch", "bigint DEFAULT NULL AFTER previous_active_tunnel_id");
            ensureColumn("route_switch_reason", "varchar(255) DEFAULT NULL AFTER last_route_switch");
            ensureColumn("route_switch_count", "int NOT NULL DEFAULT 0 AFTER route_switch_reason");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS forward_route_switch ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, "
                    + "forward_id bigint NOT NULL, "
                    + "user_id int NOT NULL, "
                    + "from_tunnel_id int DEFAULT NULL, "
                    + "from_tunnel_name varchar(160) DEFAULT NULL, "
                    + "to_tunnel_id int DEFAULT NULL, "
                    + "to_tunnel_name varchar(160) DEFAULT NULL, "
                    + "reason varchar(255) NOT NULL, "
                    + "trigger_type varchar(32) NOT NULL, "
                    + "status varchar(16) NOT NULL, "
                    + "detail varchar(255) DEFAULT NULL, "
                    + "created_at bigint NOT NULL, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_forward_route_switch_forward (forward_id, created_at), "
                    + "KEY idx_forward_route_switch_user (user_id, created_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("Forward routing storage initialization failed", e);
        }
    }

    private void ensureColumn(String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'forward' AND column_name = ?",
                Integer.class,
                column
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE forward ADD COLUMN " + column + " " + definition);
        }
    }
}
