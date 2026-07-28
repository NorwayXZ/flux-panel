package com.admin.service;

import com.admin.common.dto.PortLedgerEntryDto;
import com.admin.common.dto.PortLedgerQueryDto;
import com.admin.entity.Forward;
import com.admin.entity.DomainRoute;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.HomeProxyRouteMapper;
import com.admin.mapper.DomainRouteMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortLeaseMapper;
import com.admin.mapper.PortPoolGrantMapper;
import com.admin.mapper.PortPoolMapper;
import com.admin.mapper.PublishedServiceMapper;
import com.admin.mapper.PrivateProxyMapper;
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
    @Mock private DomainRouteMapper domainRouteMapper;
    @Mock private PrivateProxyMapper privateProxyMapper;
    @Mock private HomeProxyRouteMapper homeProxyRouteMapper;

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
        when(domainRouteMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(privateProxyMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(homeProxyRouteMapper.selectList(any())).thenReturn(Collections.emptyList());

        PortLedgerQueryDto query = new PortLedgerQueryDto();
        query.setNodeId(2L);
        Map<String, Object> result = service.list(query);
        @SuppressWarnings("unchecked")
        List<PortLedgerEntryDto> entries = (List<PortLedgerEntryDto>) result.get("entries");

        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(item -> item.getPortStart() == 12000 && "forward_entry".equals(item.getType())));
        assertTrue(entries.stream().anyMatch(item -> item.getPortStart() == 13000 && "tunnel_hop".equals(item.getType())));
    }

    @Test
    void groupsDomainsSharingOneTlsIngressIntoOneLedgerEntry() {
        Node node = node(8L, "公网入口", "203.0.113.8");
        User user = new User();
        user.setId(7L);
        user.setUser("user-a");
        DomainRoute first = domainRoute(1L, 7, "a.example.com");
        DomainRoute second = domainRoute(2L, 7, "b.example.com");

        when(nodeMapper.selectList(null)).thenReturn(List.of(node));
        when(tunnelMapper.selectList(null)).thenReturn(Collections.emptyList());
        when(userMapper.selectList(null)).thenReturn(List.of(user));
        when(poolMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(serviceMapper.selectList(null)).thenReturn(Collections.emptyList());
        when(forwardMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(grantMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(leaseMapper.selectList(null)).thenReturn(Collections.emptyList());
        when(domainRouteMapper.selectList(any())).thenReturn(List.of(first, second));
        when(privateProxyMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(homeProxyRouteMapper.selectList(any())).thenReturn(Collections.emptyList());

        Map<String, Object> result = service.list(new PortLedgerQueryDto());
        @SuppressWarnings("unchecked")
        List<PortLedgerEntryDto> entries = (List<PortLedgerEntryDto>) result.get("entries");

        assertEquals(1, entries.size());
        assertEquals("domain_ingress", entries.get(0).getType());
        assertEquals(443, entries.get(0).getPortStart());
        assertTrue(entries.get(0).getDetail().contains("a.example.com"));
        assertTrue(entries.get(0).getDetail().contains("b.example.com"));
    }

    private DomainRoute domainRoute(Long id, Integer userId, String domain) {
        DomainRoute route = new DomainRoute();
        route.setId(id);
        route.setUserId(userId);
        route.setDomain(domain);
        route.setNodeId(8L);
        route.setListenPort(443);
        route.setState("active");
        route.setCreatedTime(1000L + id);
        return route;
    }

    private Node node(Long id, String name, String serverIp) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setServerIp(serverIp);
        return node;
    }
}
