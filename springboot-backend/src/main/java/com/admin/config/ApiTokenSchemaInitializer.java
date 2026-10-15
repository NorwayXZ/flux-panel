package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class ApiTokenSchemaInitializer {
    private final JdbcTemplate jdbc;

    public ApiTokenSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS api_token ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,"
                    + "user_id int NOT NULL,"
                    + "name varchar(64) NOT NULL,"
                    + "token_prefix varchar(16) NOT NULL,"
                    + "token_hash char(64) NOT NULL,"
                    + "created_time bigint NOT NULL,"
                    + "last_used_at bigint DEFAULT NULL,"
                    + "expires_at bigint DEFAULT NULL,"
                    + "revoked tinyint NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_api_token_hash (token_hash),"
                    + "KEY idx_api_token_user (user_id, revoked)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.error("API token storage initialization failed", e);
        }
    }
}
