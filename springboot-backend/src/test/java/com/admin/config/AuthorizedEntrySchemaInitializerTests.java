package com.admin.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizedEntrySchemaInitializerTests {
    @Test
    void createsUsableTablesAndAddsRetirementColumnOnExistingInstallations() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new AuthorizedEntrySchemaInitializer(jdbc).initialize();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).execute(sql.capture());
        List<String> statements = sql.getAllValues();
        String templates = statements.stream().filter(s -> s.startsWith("CREATE TABLE IF NOT EXISTS authorized_entry_template"))
                .findFirst().orElseThrow();
        assertEquals(templates.indexOf("ENGINE=InnoDB"), templates.lastIndexOf("ENGINE=InnoDB"));
        assertTrue(templates.contains("blocked_target_cidrs text NULL"));
        assertTrue(statements.stream().anyMatch(s -> s.contains("ADD COLUMN retire_at bigint DEFAULT NULL")));
        assertTrue(statements.stream().anyMatch(s -> s.contains("retire_at bigint DEFAULT NULL,created_time")));
    }
}
