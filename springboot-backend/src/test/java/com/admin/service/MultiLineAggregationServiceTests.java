package com.admin.service;

import com.admin.common.dto.MultiLineAggregationDto;
import com.admin.common.lang.R;
import com.admin.entity.Tunnel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiLineAggregationServiceTests {
    private TunnelService tunnelService;
    private MultiLineAggregationService service;

    @BeforeEach
    void setUp() {
        tunnelService = mock(TunnelService.class);
        service = new MultiLineAggregationService(mock(JdbcTemplate.class), tunnelService,
                mock(NodeService.class), mock(ForwardService.class));
    }

    @Test
    void rejectsFewerThanTwoDistinctTunnels() {
        MultiLineAggregationDto dto = baseDto();
        dto.setTunnelIds(List.of(1L, 1L));

        R result = service.save(dto);

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("至少选择两条"));
    }

    @Test
    void rejectsTunnelsWithDifferentEntryNodes() {
        Tunnel first = tunnel(1L, 10L);
        Tunnel second = tunnel(2L, 20L);
        when(tunnelService.getById(1L)).thenReturn(first);
        when(tunnelService.getById(2L)).thenReturn(second);
        MultiLineAggregationDto dto = baseDto();
        dto.setTunnelIds(List.of(1L, 2L));

        R result = service.save(dto);

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("同一个入口节点"), result.getMsg());
    }

    private MultiLineAggregationDto baseDto() {
        MultiLineAggregationDto dto = new MultiLineAggregationDto();
        dto.setName("测试聚合"); dto.setListenPort(12000); dto.setRemoteAddr("example.com:443");
        dto.setProtocolMode("tcp_udp"); dto.setMode("balanced"); dto.setAutoWeight(true); dto.setMinimumHealthyPaths(1);
        return dto;
    }

    private Tunnel tunnel(long id, long entryNodeId) {
        Tunnel tunnel = new Tunnel(); tunnel.setId(id); tunnel.setName("线路 " + id);
        tunnel.setInNodeId(entryNodeId); tunnel.setOutNodeId(99L); tunnel.setType(2); return tunnel;
    }
}
