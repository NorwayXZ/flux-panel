package com.admin.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServicePublishingServiceImplTests {
    @Test
    void missingDiscoveryTableDoesNotHideConnectors() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyLong())).thenThrow(
                new BadSqlGrammarException("count", "SELECT COUNT(*) FROM lan_discovered_service", new SQLException("missing")));
        ServicePublishingServiceImpl service = new ServicePublishingServiceImpl();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);

        assertEquals(0, service.discoveredServiceCount(7L));
    }
}
