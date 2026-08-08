package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class AwsResourceSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public AwsResourceSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS aws_access_account ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT,"
                    + "name varchar(100) NOT NULL,"
                    + "access_key_id varchar(128) NOT NULL,"
                    + "secret_access_key varchar(2048) NOT NULL,"
                    + "default_region varchar(64) DEFAULT NULL,"
                    + "enabled tinyint NOT NULL DEFAULT 1,"
                    + "aws_account_id varchar(32) DEFAULT NULL,"
                    + "caller_arn varchar(255) DEFAULT NULL,"
                    + "last_test_at bigint DEFAULT NULL,"
                    + "last_error varchar(500) DEFAULT NULL,"
                    + "created_time bigint NOT NULL,"
                    + "updated_time bigint NOT NULL,"
                    + "PRIMARY KEY(id), UNIQUE KEY uk_aws_access_account_name(name)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ensureColumn("aws_access_account", "aws_account_id", "varchar(32) DEFAULT NULL AFTER enabled");
            ensureColumn("aws_access_account", "caller_arn", "varchar(255) DEFAULT NULL AFTER aws_account_id");
        } catch (DataAccessException e) {
            log.error("AWS resource storage initialization failed", e);
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
