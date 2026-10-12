package com.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaSupportTests {

    @Test
    void addsAMissingColumn() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"), eq(Integer.class), eq("forward"), eq("route_mode")))
                .thenReturn(0);

        assertTrue(SchemaSupport.ensureColumn(jdbcTemplate, "forward", "route_mode", "varchar(16) NOT NULL DEFAULT 'single'"));

        verify(jdbcTemplate).execute("ALTER TABLE `forward` ADD COLUMN `route_mode` varchar(16) NOT NULL DEFAULT 'single'");
    }

    @Test
    void leavesAnExistingColumnAlone() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"), eq(Integer.class), eq("forward"), eq("route_mode")))
                .thenReturn(1);

        assertFalse(SchemaSupport.ensureColumn(jdbcTemplate, "forward", "route_mode", "varchar(16)"));

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void treatsAMissingCountAsAbsent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        assertTrue(SchemaSupport.ensureColumn(jdbcTemplate, "node", "owner_user_id", "int DEFAULT NULL"));

        verify(jdbcTemplate).execute("ALTER TABLE `node` ADD COLUMN `owner_user_id` int DEFAULT NULL");
    }

    @Test
    void addsAnIndexOnlyWhenItIsMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("information_schema.statistics"), eq(Integer.class), eq("node"), eq("idx_owner")))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("information_schema.statistics"), eq(Integer.class), eq("node"), eq("idx_existing")))
                .thenReturn(1);

        SchemaSupport.ensureIndex(jdbcTemplate, "node", "idx_owner", "`owner_user_id`");
        SchemaSupport.ensureIndex(jdbcTemplate, "node", "idx_existing", "`status`");

        verify(jdbcTemplate).execute("ALTER TABLE `node` ADD KEY `idx_owner` (`owner_user_id`)");
        verify(jdbcTemplate, never()).execute(contains("idx_existing"));
    }
}
