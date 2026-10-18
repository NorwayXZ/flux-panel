package com.admin.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.admin.entity.PortPool;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortLeaseMapper;
import com.admin.mapper.PortPoolMapper;
import com.admin.mapper.PublishedServiceMapper;
import com.admin.service.PortPoolGrantService;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void pendingHomeProxyDeletionDoesNotBlockConnectorDeletion() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(contains("state NOT IN ('deleted','delete_pending')"),
                eq(Integer.class), eq(7L), eq(7L))).thenReturn(0);
        ServicePublishingServiceImpl service = new ServicePublishingServiceImpl();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);

        assertEquals(0, service.blockingHomeProxyRouteCount(7L));
    }

    @Test
    void deletesPortPoolWhenItsNodeWasAlreadyRemoved() {
        ServicePublishingServiceImpl service = new ServicePublishingServiceImpl();
        PortPoolMapper poolMapper = mock(PortPoolMapper.class);
        NodeMapper nodeMapper = mock(NodeMapper.class);
        PortLeaseMapper leaseMapper = mock(PortLeaseMapper.class);
        PublishedServiceMapper publishedServiceMapper = mock(PublishedServiceMapper.class);
        PortPoolGrantService grantService = mock(PortPoolGrantService.class);
        PortPool pool = new PortPool();
        pool.setId(9L);
        pool.setNodeId(99L);
        pool.setStatus(1);

        when(poolMapper.selectById(9L)).thenReturn(pool);
        when(nodeMapper.selectById(99L)).thenReturn(null);
        when(publishedServiceMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());
        when(leaseMapper.delete(org.mockito.ArgumentMatchers.any())).thenReturn(0);
        when(grantService.deleteForPool(9L)).thenReturn(0);
        when(poolMapper.updateById(pool)).thenReturn(1);
        ReflectionTestUtils.setField(service, "poolMapper", poolMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "leaseMapper", leaseMapper);
        ReflectionTestUtils.setField(service, "publishedServiceMapper", publishedServiceMapper);
        ReflectionTestUtils.setField(service, "portPoolGrantService", grantService);

        assertEquals(0, service.deletePortPool(9L).getCode());
        assertEquals(0, pool.getStatus());
        verify(grantService).deleteForPool(9L);
    }

    @Test
    void listsAndOptionallyRemovesDomainEntriesOfAnOrphanedPool() {
        ServicePublishingServiceImpl service = new ServicePublishingServiceImpl();
        PortPoolMapper poolMapper = mock(PortPoolMapper.class);
        NodeMapper nodeMapper = mock(NodeMapper.class);
        PortLeaseMapper leaseMapper = mock(PortLeaseMapper.class);
        PublishedServiceMapper publishedServiceMapper = mock(PublishedServiceMapper.class);
        com.admin.mapper.DomainRouteMapper domainRouteMapper = mock(com.admin.mapper.DomainRouteMapper.class);
        com.admin.mapper.InternalConnectorMapper connectorMapper = mock(com.admin.mapper.InternalConnectorMapper.class);
        PortPoolGrantService grantService = mock(PortPoolGrantService.class);
        PortPool pool = new PortPool();
        pool.setId(2L);
        pool.setNodeId(16L);
        pool.setStatus(1);
        com.admin.entity.PublishedService published = new com.admin.entity.PublishedService();
        published.setId(3L);
        published.setName("openwrt");
        published.setConnectorId(30L);
        com.admin.entity.DomainRoute route = new com.admin.entity.DomainRoute();
        route.setId(1L);
        route.setDomain("openwrt-web.eeaa.xyz");
        route.setNodeId(16L);
        route.setState("deployment_failed");

        when(poolMapper.selectById(2L)).thenReturn(pool);
        when(nodeMapper.selectById(16L)).thenReturn(null);
        when(publishedServiceMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(published));
        when(domainRouteMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(route));
        when(connectorMapper.selectById(30L)).thenReturn(null);
        ReflectionTestUtils.setField(service, "poolMapper", poolMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "leaseMapper", leaseMapper);
        ReflectionTestUtils.setField(service, "publishedServiceMapper", publishedServiceMapper);
        ReflectionTestUtils.setField(service, "domainRouteMapper", domainRouteMapper);
        ReflectionTestUtils.setField(service, "connectorMapper", connectorMapper);
        ReflectionTestUtils.setField(service, "portPoolGrantService", grantService);

        var blocked = service.deletePortPool(2L, false);
        assertEquals(409, blocked.getCode());
        org.junit.jupiter.api.Assertions.assertTrue(blocked.getMsg().contains("openwrt-web.eeaa.xyz"));
        assertEquals(1, ((java.util.List<?>) ((java.util.Map<?, ?>) blocked.getData()).get("domainRoutes")).size());
        assertEquals(1, pool.getStatus(), "nothing is removed without confirmation");

        assertEquals(0, service.deletePortPool(2L, true).getCode());
        assertEquals("deleted", route.getState());
        assertEquals("deleted", published.getState());
        assertEquals(0, pool.getStatus());
    }
}
