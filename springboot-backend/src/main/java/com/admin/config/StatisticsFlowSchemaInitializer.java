package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Hourly traffic samples split by direction, so the dashboard can chart inbound and outbound
 * traffic separately. Older samples have no running totals per direction; the next hourly
 * sample treats them as a fresh start instead of counting a whole lifetime as one hour.
 */
@Slf4j
@Component
public class StatisticsFlowSchemaInitializer {
    private final JdbcTemplate jdbc;

    public StatisticsFlowSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        try {
            ensureColumn("in_flow", "bigint NOT NULL DEFAULT 0 AFTER `flow`");
            ensureColumn("out_flow", "bigint NOT NULL DEFAULT 0 AFTER `in_flow`");
            ensureColumn("total_in_flow", "bigint DEFAULT NULL AFTER `total_flow`");
            ensureColumn("total_out_flow", "bigint DEFAULT NULL AFTER `total_in_flow`");
            SchemaSupport.ensureIndex(jdbc, "statistics_flow", "idx_statistics_flow_created", "created_time");
        } catch (DataAccessException e) {
            log.error("Traffic statistics storage initialization failed", e);
        }
    }

    private void ensureColumn(String column, String definition) {
        SchemaSupport.ensureColumn(jdbc, "statistics_flow", column, definition);
    }
}
