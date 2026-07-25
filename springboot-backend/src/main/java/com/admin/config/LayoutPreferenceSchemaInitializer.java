package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LayoutPreferenceSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public LayoutPreferenceSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS layout_preference ("
                    + "id bigint unsigned NOT NULL AUTO_INCREMENT, "
                    + "user_id int NOT NULL, "
                    + "scope varchar(64) NOT NULL, "
                    + "item_order text NOT NULL, "
                    + "updated_time bigint NOT NULL, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_layout_preference_user_scope (user_id, scope)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (DataAccessException e) {
            log.warn("Card layout storage could not be initialized; browser-local ordering remains available");
        }
    }
}
