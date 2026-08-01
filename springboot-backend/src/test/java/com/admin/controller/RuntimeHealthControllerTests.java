package com.admin.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeHealthControllerTests {
    @Test
    void reportsReadyOnlyWhenDatabaseResponds() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        ResponseEntity<Map<String, String>> response = new RuntimeHealthController(jdbcTemplate).ready();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ready", response.getBody().get("status"));
    }

    @Test
    void reportsUnavailableWhenDatabaseProbeFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("database unavailable"));

        ResponseEntity<Map<String, String>> response = new RuntimeHealthController(jdbcTemplate).ready();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("not_ready", response.getBody().get("status"));
    }
}
