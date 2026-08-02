package com.admin.service;

import com.admin.common.dto.TunnelHopConfigDto;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TunnelTransportServiceTests {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TunnelTransportService service = new TunnelTransportService(jdbcTemplate);

    @Test
    void existingTunnelWithoutHopConfigKeepsUsingPublicAddress() {
        Node entry = node(1L, "B", "198.51.100.10");
        Node exit = node(2L, "C", "203.0.113.20");
        Tunnel tunnel = new Tunnel();

        TunnelTransportService.ResolvedHop hop = service.resolve(tunnel, List.of(entry, exit)).get(0);

        assertEquals("public", hop.mode());
        assertEquals("203.0.113.20", hop.primaryAddress());
        assertEquals(List.of("203.0.113.20"), hop.candidates());
        assertNull(hop.fallbackAddress());
    }

    @Test
    void customIpv6CanBePrimaryWithPublicFallback() {
        Node entry = node(1L, "B", "198.51.100.10");
        Node exit = node(2L, "C", "203.0.113.20");
        TunnelHopConfigDto config = new TunnelHopConfigDto();
        config.setFromNodeId(1L);
        config.setToNodeId(2L);
        config.setAddressMode("custom");
        config.setCustomAddress("fd88::2");
        config.setFallbackMode("public");
        Tunnel tunnel = new Tunnel();
        tunnel.setHopConfig(JSON.toJSONString(List.of(config)));

        TunnelTransportService.ResolvedHop hop = service.resolve(tunnel, List.of(entry, exit)).get(0);

        assertEquals("custom", hop.mode());
        assertEquals("fd88:0:0:0:0:0:0:2", hop.primaryAddress());
        assertEquals(List.of("fd88:0:0:0:0:0:0:2", "203.0.113.20"), hop.candidates());
    }

    @Test
    void resourceUsageMatchesExactGroupAndAddressMode() {
        TunnelHopConfigDto privateTen = new TunnelHopConfigDto();
        privateTen.setAddressMode("private"); privateTen.setResourceGroupId(10L);
        TunnelHopConfigDto virtualOne = new TunnelHopConfigDto();
        virtualOne.setAddressMode("virtual"); virtualOne.setResourceGroupId(1L);
        when(jdbcTemplate.queryForList("SELECT id,name,hop_config FROM tunnel WHERE hop_config IS NOT NULL AND hop_config<>''"))
                .thenReturn(List.of(Map.of("id", 9L, "name", "B-C-D", "hop_config", JSON.toJSONString(List.of(privateTen, virtualOne)))));

        assertNull(service.firstTunnelUsing("private", 1L));
        assertEquals("B-C-D", service.firstTunnelUsing("private", 10L));
        assertEquals("B-C-D", service.firstTunnelUsing("virtual", 1L));
    }

    private static Node node(Long id, String name, String serverIp) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setServerIp(serverIp);
        return node;
    }
}
