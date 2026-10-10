package com.admin.service;

import com.admin.mapper.InternalConnectorMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OpenWrtDnsResolverServiceTests {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final InternalConnectorMapper connectors=mock(InternalConnectorMapper.class);
    private final OpenWrtDnsResolverService service=new OpenWrtDnsResolverService(jdbc,connectors);
    private final Map<String,Object> mobile=new HashMap<>();

    @BeforeEach void setup(){
        when(jdbc.query(contains("policy_revision AS revision"),any(ResultSetExtractor.class),eq(7L)))
                .thenReturn(Map.of("interfaceCarriers","{\"pppoe-wan\":\"mobile\"}","groupIds","[10]","revision",3L));
        when(jdbc.query(contains("record_type AS recordType"),any(ResultSetExtractor.class),eq(10L)))
                .thenAnswer(call->new HashMap<>(Map.of("id",10L,"domain","a.example.test","recordType","A","ttl",5)));
        mobile.putAll(Map.of("carrier","mobile","forwardId",2L,"entryAddress","192.0.2.2","status","unhealthy"));
        when(jdbc.queryForList(contains("FROM smart_entry_route WHERE group_id"),eq(10L))).thenAnswer(call->List.of(
                new HashMap<>(Map.of("carrier","default","forwardId",1L,"entryAddress","192.0.2.1","status","healthy")),mobile));
        when(jdbc.queryForList(contains("source_ip_carrier_database"))).thenReturn(List.of(Map.of("carrier","mobile","cidrs","192.0.2.0/24\n2001:db8::/32\n")));
    }

    @Test void localPolicyChoosesHealthyFallbackAndUsesShortLocalTtl(){
        Map<String,Object> policy=service.policy(7L);
        var groups=(List<Map<String,Object>>)policy.get("groups");
        var addresses=(Map<String,String>)groups.get(0).get("addresses");
        assertEquals("192.0.2.1",addresses.get("mobile"));
        assertEquals(5,groups.get(0).get("ttl"));
        assertEquals(Map.of("pppoe-wan","mobile"),policy.get("interfaceCarriers"));
        assertEquals(List.of("192.0.2.0/24","2001:db8::/32"),((Map<?,?>)policy.get("carrierCidrs")).get("mobile"));
    }

    @Test void explicitNoFallbackIsRepresentedAsAnUnavailableCarrier(){
        mobile.put("fallbackCarriers","[]");
        Map<String,Object> policy=service.policy(7L);
        var groups=(List<Map<String,Object>>)policy.get("groups");
        var addresses=(Map<String,String>)groups.get(0).get("addresses");
        assertTrue(addresses.containsKey("mobile"));assertEquals("",addresses.get("mobile"));
        assertFalse(addresses.containsKey("unicom"));
    }

    @Test void disabledOrDeletedSourceStrategyIsRemovedFromLocalPolicy(){
        when(jdbc.query(contains("record_type AS recordType"),any(ResultSetExtractor.class),eq(10L))).thenReturn(null);
        assertTrue(((List<?>)service.policy(7L).get("groups")).isEmpty());
        verify(jdbc,never()).queryForList(contains("FROM smart_entry_route WHERE group_id"),eq(10L));
    }

    @Test void unconfiguredResolverDoesNotReceiveOtherRouterPolicies(){
        assertNull(service.policy(8L));
    }

    @Test void sourceRouteChangesAdvanceRevisionWhileFirstSyncKeepsConfiguredRevision(){
        Map<String,Object> first=service.policy(7L);
        Map<String,Object> row=new HashMap<>(Map.of("revision",3L));
        when(jdbc.queryForList(contains("SELECT policy_hash AS policyHash"),eq(7L))).thenReturn(List.of(row));
        when(jdbc.update(contains("UPDATE openwrt_dns_resolver SET policy_hash"),anyString(),anyLong(),eq(7L),eq(3L))).thenReturn(1);
        assertTrue(service.advancePolicyRevision(7L,first));assertEquals(3L,first.get("revision"));
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("UPDATE openwrt_dns_resolver SET policy_hash"),captured.capture(),eq(3L),eq(7L),eq(3L));
        row.put("policyHash",captured.getValue());clearInvocations(jdbc);
        assertTrue(service.advancePolicyRevision(7L,service.policy(7L)));
        verify(jdbc,never()).update(contains("UPDATE openwrt_dns_resolver SET policy_hash"),anyString(),anyLong(),anyLong(),anyLong());
        mobile.put("status","healthy");Map<String,Object> changed=service.policy(7L);
        assertTrue(service.advancePolicyRevision(7L,changed));assertEquals(4L,changed.get("revision"));
    }

    @Test void concurrentConfigurationChangePreventsSendingAnOlderSnapshot(){
        Map<String,Object> stale=service.policy(7L);
        when(jdbc.queryForList(contains("SELECT policy_hash AS policyHash"),eq(7L))).thenReturn(List.of(Map.of("revision",4L)));
        assertFalse(service.advancePolicyRevision(7L,stale));
        verify(jdbc,never()).update(contains("UPDATE openwrt_dns_resolver SET policy_hash"),anyString(),anyLong(),anyLong(),anyLong());
    }

    @Test void routerListDoesNotTreatSavedRevisionAsWorkingDns() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("Authorization", "e30." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".test");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            var row=new HashMap<String,Object>(Map.of("connectorId",7L,"policyRevision",3L,"appliedRevision",3L,"statusJson","{\"dnsReady\":false,\"dnsStatus\":\"integration-error\",\"dnsChecks\":[],\"dnsCheckedAt\":123}"));
            when(jdbc.queryForList(contains("FROM internal_connector c LEFT JOIN"),eq(0),eq(0))).thenReturn(List.of(row));
            var data=(List<Map<String,Object>>)service.list().getData();
            assertEquals(false,data.get(0).get("dnsReady"));
            assertEquals("integration-error",data.get(0).get("dnsStatus"));
            assertEquals(3L,data.get(0).get("appliedRevision"));
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

    @Test void dnsRepairEndpointRequiresAdministratorRole() throws Exception {
        var method=com.admin.controller.OpenWrtDnsController.class.getMethod("repair",Map.class);
        assertTrue(method.isAnnotationPresent(com.admin.common.annotation.RequireRole.class));
    }
}
