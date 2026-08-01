package com.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
public class RuntimeHealthController {
    private final JdbcTemplate jdbcTemplate;

    public RuntimeHealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (value != null && value == 1) {
                return ResponseEntity.ok(Map.of("status", "ready"));
            }
        } catch (RuntimeException ignored) {
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "not_ready"));
    }
}
