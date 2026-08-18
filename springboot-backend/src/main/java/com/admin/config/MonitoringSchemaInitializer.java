package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MonitoringSchemaInitializer implements ApplicationRunner {

    private static final int DETAIL_MAX_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;

    public MonitoringSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS monitoring_current ("
                    + "resource_type varchar(16) NOT NULL, "
                    + "resource_id bigint NOT NULL, "
                    + "resource_name varchar(160) NOT NULL, "
                    + "owner_user_id int NOT NULL, "
                    + "status varchar(16) NOT NULL, "
                    + "detail varchar(500) DEFAULT NULL, "
                    + "changed_at bigint NOT NULL, "
                    + "checked_at bigint NOT NULL, "
                    + "PRIMARY KEY (resource_type, resource_id), "
                    + "KEY idx_monitoring_current_owner (owner_user_id, resource_type), "
                    + "KEY idx_monitoring_current_status (status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS monitoring_history ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, "
                    + "resource_type varchar(16) NOT NULL, "
                    + "resource_id bigint NOT NULL, "
                    + "resource_name varchar(160) NOT NULL, "
                    + "owner_user_id int NOT NULL, "
                    + "status varchar(16) NOT NULL, "
                    + "detail varchar(500) DEFAULT NULL, "
                    + "started_at bigint NOT NULL, "
                    + "ended_at bigint DEFAULT NULL, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_monitoring_history_resource (resource_type, resource_id, started_at), "
                    + "KEY idx_monitoring_history_owner (owner_user_id, started_at), "
                    + "KEY idx_monitoring_history_window (started_at, ended_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS monitoring_alert ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, "
                    + "resource_type varchar(16) NOT NULL, "
                    + "resource_id bigint NOT NULL, "
                    + "resource_name varchar(160) NOT NULL, "
                    + "owner_user_id int NOT NULL, "
                    + "severity varchar(16) NOT NULL, "
                    + "status varchar(16) NOT NULL, "
                    + "title varchar(200) NOT NULL, "
                    + "detail varchar(500) DEFAULT NULL, "
                    + "started_at bigint NOT NULL, "
                    + "resolved_at bigint DEFAULT NULL, "
                    + "updated_at bigint NOT NULL, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_monitoring_alert_resource (resource_type, resource_id, status), "
                    + "KEY idx_monitoring_alert_owner (owner_user_id, status, started_at), "
                    + "KEY idx_monitoring_alert_updated (updated_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS monitoring_alert_read ("
                    + "alert_id bigint unsigned NOT NULL, "
                    + "user_id int NOT NULL, "
                    + "read_at bigint NOT NULL, "
                    + "PRIMARY KEY (alert_id, user_id), "
                    + "KEY idx_monitoring_alert_read_user (user_id, read_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            ensureDetailWidth("monitoring_current");
            ensureDetailWidth("monitoring_history");
            ensureDetailWidth("monitoring_alert");
        } catch (DataAccessException e) {
            log.error("Monitoring storage initialization failed", e);
        }
    }

    private void ensureDetailWidth(String table) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT DATA_TYPE AS data_type, CHARACTER_MAXIMUM_LENGTH AS max_length FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name=? AND column_name='detail'",
                table);
        if (columns.isEmpty()) {
            return;
        }
        Map<String, Object> column = columns.get(0);
        String dataType = String.valueOf(column.get("data_type"));
        Object rawLength = column.get("max_length");
        long length = rawLength == null ? Long.MAX_VALUE : Long.parseLong(rawLength.toString());
        if (!"varchar".equalsIgnoreCase(dataType) || length >= DETAIL_MAX_LENGTH) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE `" + table + "` MODIFY COLUMN `detail` varchar(" + DETAIL_MAX_LENGTH + ") DEFAULT NULL");
    }
}
