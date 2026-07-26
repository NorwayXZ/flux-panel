package com.admin.service;

import com.admin.common.dto.PortLedgerEntryDto;
import com.admin.common.dto.PortLedgerQueryDto;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortLeaseMapper;
import com.admin.mapper.PortPoolGrantMapper;
import com.admin.mapper.PortPoolMapper;
import com.admin.mapper.PublishedServiceMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortLedgerServiceTests {
    @Mock private NodeMapper nodeMapper;
    @Mock private TunnelMapper tunnelMapper;
    @Mock private ForwardMapper forwardMapper;
    @Mock private PortPoolMapper poolMapper;
    @Mock private PortPoolGrantMapper grantMapper;
    @Mock private PortLeaseMapper leaseMapper;
    @Mock private PublishedServiceMapper serviceMapper;
    @Mock private UserMapper userMapper;

    @InjectMocks private PortLedgerService service;

    @Test
    void filtersBySharedServerNamespaceAndIncludesHopPorts() {
        Node first = node(1L, "入口", "Shared.EXAMPLE.");
        Node alias = node(2L, "同服节点", "shared.example");
        Tunnel tunnel = new Tunnel();
        tunnel.setId(10L);
        tunnel.setName("A-B");
        tunnel.setType(2);
        tunnel.setInNodeId(1L);
        tunnel.setOutNodeId(2L);
        tunnel.setNodePath("1,2");
        tunnel.setProtocol("tls");

        Forward forward = new Forward();
        forward.setId(20L);
        forward.setName("业务入口");
        forward.setTunnelId(10);
        forward.setInPort(12000);
        forward.setHopPorts("13000");
        forward.setProtocolMode("tcp_udp");
        forward.setUserId(7);
        forward.setUserName("user-a");
        forward.setStatus(1);

        when(nodeMapper.selectList(null)).thenReturn(List.of(first, alias));
        when(tunnelMapper.selectList(null)).thenReturn(List.of(tunnel));
        when(userMapper.selectList(null)).thenReturn(Collections.emptyList());
        when(poolMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(serviceMapper.selectList(null)).thenReturn(Collections.emptyList());
        when(forwardMapper.selectList(any())).thenReturn(List.of(forward));
        when(grantMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(leaseMapper.selectList(null)).thenReturn(Collections.emptyList());

        PortLedgerQueryDto query = new PortLedgerQueryDto();
        query.setNodeId(2L);
        Map<String, Object> result = service.list(query);
        @SuppressWarnings("unchecked")
        List<PortLedgerEntryDto> entries = (List<PortLedgerEntryDto>) result.get("entries");

        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(item -> item.getPortStart() == 12000 && "forward_entry".equals(item.getType())));
        assertTrue(entries.stream().anyMatch(item -> item.getPortStart() == 13000 && "tunnel_hop".equals(item.getType())));
    }

    private Node node(Long id, String name, String serverIp) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setServerIp(serverIp);
        return node;
    }
}
