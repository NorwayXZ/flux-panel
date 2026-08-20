package com.admin.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossEntryFailoverSchemaInitializerTests {
    @Test
    void addsConnectionTelemetryColumnsAndLookupIndex() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString(), anyString()))
                .thenReturn(0);

        new CrossEntryFailoverSchemaInitializer(jdbcTemplate).initialize();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sql.capture());
        List<String> statements = sql.getAllValues();

        assertTrue(statements.stream().anyMatch(value -> value.contains("ADD COLUMN `telemetry_ready`")));
        assertTrue(statements.stream().anyMatch(value -> value.contains("ADD COLUMN `total_connections`")));
        assertTrue(statements.stream().anyMatch(value -> value.contains("ADD COLUMN `current_connections`")));
        assertTrue(statements.stream().anyMatch(value -> value.contains("ADD COLUMN `reported_total_connections`")));
        assertTrue(statements.stream().anyMatch(value -> value.contains("ADD COLUMN `last_telemetry_at`")));
        assertTrue(statements.stream().anyMatch(value -> value.contains("ADD INDEX `idx_cross_entry_activity`")));
    }
}
