package com.admin.service;

import com.admin.common.dto.FlowDto;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceTelemetryServiceTests {
    @Test
    void calculatesBytesPerSecondFromReportInterval() {
        assertEquals(2048L, ServiceTelemetryService.bytesPerSecond(4096L, 2000L));
        assertEquals(0L, ServiceTelemetryService.bytesPerSecond(4096L, 999L));
    }

    @Test
    void acceptsLegacyAgentPayloadWithoutOptionalTelemetryFields() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
                .thenThrow(new DataAccessResourceFailureException("monitoring table unavailable"));
        ServiceTelemetryService service = new ServiceTelemetryService(jdbcTemplate);
        FlowDto legacy = new FlowDto();
        legacy.setN("publish_7_rtcp");
        legacy.setU(1024L);
        legacy.setD(2048L);

        assertDoesNotThrow(() -> service.record(3L, legacy));
    }
}
