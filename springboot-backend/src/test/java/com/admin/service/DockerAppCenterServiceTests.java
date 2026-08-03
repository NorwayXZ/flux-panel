package com.admin.service;

import com.admin.common.lang.R;
import com.admin.mapper.NodeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerAppCenterServiceTests {

    @Test
    void overviewIncludesCoreApplicationTemplates() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        DockerAppCenterService service = new DockerAppCenterService(jdbcTemplate, mock(NodeMapper.class),
                mock(ServicePublishingService.class));

        R result = service.overview();

        assertEquals(0, result.getCode());
        Map<?, ?> data = (Map<?, ?>) result.getData();
        List<?> templates = (List<?>) data.get("templates");
        assertEquals(4, templates.size());
        assertTrue(templates.stream().map(item -> ((Map<?, ?>) item).get("id")).toList()
                .containsAll(List.of("x-ui", "nezha", "alist", "nextcloud")));
        assertEquals("2.47.0", data.get("minimumAgentVersion"));
    }
}
