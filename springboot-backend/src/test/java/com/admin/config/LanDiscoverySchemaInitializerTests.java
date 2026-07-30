package com.admin.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LanDiscoverySchemaInitializerTests {
    @Test
    void createsCandidateTableIndependently() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"), eq(Integer.class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(1);

        new LanDiscoverySchemaInitializer(jdbcTemplate).initialize();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("CREATE TABLE IF NOT EXISTS lan_discovered_service"));
        assertTrue(sqlCaptor.getValue().contains("`sensitive` tinyint"));
    }
}
