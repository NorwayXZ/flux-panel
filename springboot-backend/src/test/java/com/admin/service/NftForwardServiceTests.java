package com.admin.service;

import com.admin.common.dto.NftForwardSaveDto;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NftForwardServiceTests {

    @Test
    void normalizesSafeIPv4Rule() {
        NftForwardSaveDto dto = validRule();
        dto.setProtocol("TCP+UDP");
        dto.setSourceCidrs("192.0.2.0/24, 198.51.100.8/32\n192.0.2.0/24");

        NftForwardService.Normalized normalized = NftForwardService.normalize(dto);

        assertEquals("tcp_udp", normalized.protocol());
        assertEquals("0.0.0.0", normalized.listenAddress());
        assertEquals("192.0.2.0/24\n198.51.100.8/32", normalized.sourceCidrs());
        assertTrue(normalized.enabled());
    }

    @Test
    void rejectsDomainsIPv6AndLoopbackTargets() {
        NftForwardSaveDto domain = validRule();
        domain.setTargetAddress("example.com");
        assertThrows(IllegalArgumentException.class, () -> NftForwardService.normalize(domain));

        NftForwardSaveDto ipv6 = validRule();
        ipv6.setTargetAddress("2001:db8::1");
        assertThrows(IllegalArgumentException.class, () -> NftForwardService.normalize(ipv6));

        NftForwardSaveDto loopback = validRule();
        loopback.setTargetAddress("127.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> NftForwardService.normalize(loopback));
    }

    @Test
    void treatsTcpAndUdpAsSeparatePortNamespaces() {
        assertFalse(NftForwardService.protocolOverlap("tcp", "udp"));
        assertTrue(NftForwardService.protocolOverlap("tcp_udp", "udp"));
        assertTrue(NftForwardService.protocolOverlap("tcp", "HTTP"));
    }

    @Test
    void failedEditKeepsTheLastSuccessfulRollbackSnapshot() {
        Map<String, Object> failed = new HashMap<>();
        failed.put("state", "error");
        failed.put("last_good_config", "{\"name\":\"known-good\"}");
        assertEquals("{\"name\":\"known-good\"}", NftForwardService.rollbackSnapshot(failed));

        Map<String, Object> active = ruleRow("active");
        NftForwardSaveDto snapshot = JSON.parseObject(NftForwardService.rollbackSnapshot(active), NftForwardSaveDto.class);
        assertEquals("kernel-forward", snapshot.getName());
        assertEquals(20000, snapshot.getListenPort());
        assertTrue(snapshot.getEnabled());
    }

    private NftForwardSaveDto validRule() {
        NftForwardSaveDto dto = new NftForwardSaveDto();
        dto.setName("kernel-forward");
        dto.setNodeId(1L);
        dto.setListenAddress("0.0.0.0");
        dto.setListenPort(20000);
        dto.setProtocol("tcp");
        dto.setTargetAddress("10.0.0.8");
        dto.setTargetPort(8080);
        dto.setNatMode("masquerade");
        dto.setEnabled(true);
        return dto;
    }

    private Map<String, Object> ruleRow(String state) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 8L);
        row.put("name", "kernel-forward");
        row.put("node_id", 1L);
        row.put("listen_address", "0.0.0.0");
        row.put("listen_port", 20000);
        row.put("protocol", "tcp");
        row.put("target_address", "10.0.0.8");
        row.put("target_port", 8080);
        row.put("nat_mode", "masquerade");
        row.put("source_cidrs", "");
        row.put("enabled", 1);
        row.put("state", state);
        return row;
    }
}
