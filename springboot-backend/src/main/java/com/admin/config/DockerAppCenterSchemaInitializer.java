package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class DockerAppCenterSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public DockerAppCenterSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS docker_app_instance ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id int NOT NULL, node_id bigint NOT NULL, "
                    + "template_id varchar(40) NOT NULL, name varchar(100) NOT NULL, container_name varchar(120) NOT NULL, "
                    + "image varchar(200) NOT NULL, host_port int NOT NULL, container_port int NOT NULL, "
                    + "domain_route_id bigint DEFAULT NULL, state varchar(24) NOT NULL DEFAULT 'draft', "
                    + "last_error varchar(500) DEFAULT NULL, last_command text DEFAULT NULL, rollback_command text DEFAULT NULL, "
                    + "backup_path varchar(500) DEFAULT NULL, detected tinyint NOT NULL DEFAULT 0, "
                    + "created_time bigint NOT NULL, updated_time bigint NOT NULL, PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_docker_app_node_container (node_id, container_name), "
                    + "KEY idx_docker_app_node (node_id,state), KEY idx_docker_app_user (user_id,state), "
                    + "KEY idx_docker_app_domain_route (domain_route_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS docker_app_event ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, app_id bigint DEFAULT NULL, node_id bigint NOT NULL, "
                    + "event_type varchar(32) NOT NULL, status varchar(24) NOT NULL, detail varchar(500) DEFAULT NULL, "
                    + "created_time bigint NOT NULL, PRIMARY KEY (id), KEY idx_docker_app_event_app (app_id,created_time), "
                    + "KEY idx_docker_app_event_node (node_id,created_time)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("docker_app_instance", "compose_path", "varchar(500) DEFAULT NULL AFTER rollback_command");
            ensureColumn("docker_app_instance", "version_label", "varchar(80) DEFAULT NULL AFTER image");
        } catch (DataAccessException e) {
            log.error("Docker app center storage initialization failed", e);
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
