package com.admin.service;

import com.admin.common.dto.CrossEntryFailoverSaveDto;
import com.admin.common.dto.FlowDto;
import com.admin.common.lang.R;
import com.admin.common.utils.CrossEntryFailoverPolicy;
import com.admin.controller.FlowController;
import com.admin.entity.Forward;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrossEntryLifecycleTests {
    private CrossEntryFailoverService service(JdbcTemplate jdbc, ForwardService forwards, DnsProviderService dns) {
        return new CrossEntryFailoverService(jdbc, null, null, dns, mock(SchedulingConflictService.class),
                forwards, null, null, null, null);
    }

    @Test
    void udpBytesEnterQuotaAccountingWithoutTcpConnectionMetrics() {
        FlowController controller = new FlowController();
        ForwardService forwards = mock(ForwardService.class);
        CrossEntryFailoverService cross = mock(CrossEntryFailoverService.class);
        ReflectionTestUtils.setField(controller, "forwardService", forwards);
        ReflectionTestUtils.setField(controller, "crossEntryFailoverService", cross);
        ReflectionTestUtils.setField(controller, "serviceTelemetryService", mock(ServiceTelemetryService.class));
        ReflectionTestUtils.setField(controller, "authorizedEntryService", mock(AuthorizedEntryService.class));
        ReflectionTestUtils.setField(controller, "smartEntryService", mock(SmartEntryService.class));
        ReflectionTestUtils.setField(controller, "userQuotaService", mock(UserQuotaService.class));
        ReflectionTestUtils.setField(controller, "userService", mock(UserService.class));
        ReflectionTestUtils.setField(controller, "tunnelService", mock(TunnelService.class));
        Forward forward = new Forward(); forward.setId(9L); forward.setTunnelId(1);
        when(forwards.getById("9")).thenReturn(forward);
        FlowDto flow = new FlowDto(); flow.setN("9_7_0_udp"); flow.setD(123L); flow.setU(456L);
        ReflectionTestUtils.invokeMethod(controller, "processFlowData", flow, 1L);
        verify(cross).recordTraffic(9L, 1L, 123L, 456L);
    }

    @Test
    void exhaustedQuotaSchedulerRetriesUnconfirmedPause() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ForwardService forwards = mock(ForwardService.class);
        Map<String,Object> quota = new HashMap<>(Map.of("trafficQuotaEnabled", true,
                "trafficQuotaPaused", true, "trafficQuotaExhausted", true, "trafficQuotaResetDay", 1,
                "trafficQuotaPeriodStartAt", CrossEntryFailoverService.trafficQuotaPeriodStartAt(1, System.currentTimeMillis())));
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(7L));
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(quota));
        when(jdbc.queryForList(contains("paused=0"), eq(Long.class), eq(7L))).thenReturn(List.of(9L));
        when(forwards.pauseManagedForward(9L)).thenReturn(R.err("offline"), R.ok());
        CrossEntryFailoverService service = service(jdbc, forwards, null);
        try {
            service.scheduledTrafficQuotaReset(); service.scheduledTrafficQuotaReset();
            verify(forwards, times(2)).pauseManagedForward(9L);
            verify(jdbc).update(contains("SET paused=1"), eq(7L), eq(9L));
        } finally { service.shutdown(); }
    }

    @Test
    void expiredOrAdminStoppedGroupsNeverResumeAtResetBoundary() {
        for (Map<String,Object> guard : List.of(Map.<String,Object>of("expiresAt", 1L),
                Map.<String,Object>of("trafficQuotaResumeEnabled", 0))) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ForwardService forwards = mock(ForwardService.class);
            Map<String,Object> quota = new HashMap<>(Map.of("trafficQuotaEnabled", true,
                    "trafficQuotaPaused", true, "trafficQuotaExhausted", false, "trafficQuotaPeriodStartAt", 1L,
                    "trafficQuotaResetDay", 8));
            quota.putAll(guard);
            when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(quota));
            CrossEntryFailoverService service = service(jdbc, forwards, null);
            try {
                ReflectionTestUtils.invokeMethod(service, "prepareTrafficQuotaPeriod", 7L, System.currentTimeMillis());
                verifyNoInteractions(forwards);
                verify(jdbc).update(contains("traffic_quota_used_bytes=0"), any(), any(), eq(7L));
            } finally { service.shutdown(); }
        }
    }

    @Test
    void restoresOnlyForwardsOwnedByTheQuotaPause() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ForwardService forwards = mock(ForwardService.class);
        when(jdbc.queryForList(contains("cross_entry_quota_pause"), eq(Long.class), eq(7L))).thenReturn(List.of(9L));
        when(forwards.resumeManagedForward(9L)).thenReturn(R.ok());
        CrossEntryFailoverService service = service(jdbc, forwards, null);
        try {
            assertNull(ReflectionTestUtils.invokeMethod(service, "resumeQuotaPausedForwards", 7L));
            verify(forwards).resumeManagedForward(9L);
            verifyNoMoreInteractions(forwards);
        } finally { service.shutdown(); }
    }

    @Test
    void blacklistRejectsCandidateInEverySelectionModeEvenDuringEmergency() {
        CrossEntryFailoverService service = service(mock(JdbcTemplate.class), null, null);
        try {
            for (boolean tcp : List.of(false, true)) {
                Map<String,Object> group = Map.of("tcpLatencySelectionEnabled", tcp);
                Map<String,Object> member = Map.of("id", 2L, "enabled", true, "status", "healthy", "successCount", 10,
                        "switchRejectedUntil", System.currentTimeMillis() + 600_000L, "qualityLatencyMs", 10);
                CrossEntryFailoverPolicy.Member candidate = ReflectionTestUtils.invokeMethod(service, "policyMember",
                        group, member, false, true, System.currentTimeMillis());
                assertFalse(candidate.healthy());
                assertFalse(CrossEntryFailoverPolicy.select(List.of(candidate), null, false, 3, true).switchRequired());
            }
        } finally { service.shutdown(); }
    }

    @Test
    void oneUpdatedPublicResolverDoesNotConfirmCompleteDnsPropagation() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"Answer\":[{\"type\":1,\"data\":\"8.8.8.8\"}]}"),
                        ResponseEntity.ok("{\"Answer\":[{\"type\":1,\"data\":\"1.1.1.1\"}]}"));
        CrossEntryFailoverService service = new CrossEntryFailoverService(null, rest, null, null, null, null, null, null, null, null);
        try {
            Object result = ReflectionTestUtils.invokeMethod(service, "queryPublicDns", "a.example.com", "A", "8.8.8.8");
            assertEquals(false, ReflectionTestUtils.invokeMethod(result, "matched"));
        } finally { service.shutdown(); }
    }

    @Test
    void publicDnsConfirmationRequiresBothResolversAndRejectsQueryFailures() {
        RestTemplate rest = mock(RestTemplate.class);
        String answer = "{\"Answer\":[{\"type\":1,\"data\":\"8.8.8.8\"}]}";
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(answer), ResponseEntity.ok(answer), ResponseEntity.ok(answer))
                .thenThrow(new IllegalStateException("resolver unavailable"));
        CrossEntryFailoverService service = new CrossEntryFailoverService(null, rest, null, null, null, null, null, null, null, null);
        try {
            Object confirmed = ReflectionTestUtils.invokeMethod(service, "queryPublicDns", "a.example.com", "A", "8.8.8.8");
            assertEquals(true, ReflectionTestUtils.invokeMethod(confirmed, "matched"));
            Object failed = ReflectionTestUtils.invokeMethod(service, "queryPublicDns", "a.example.com", "A", "8.8.8.8");
            assertEquals(false, ReflectionTestUtils.invokeMethod(failed, "matched"));
        } finally { service.shutdown(); }
    }

    @Test
    void failedGroupRestoreKeepsPauseOwnershipAndRetriesWithinTheSamePeriod() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ForwardService forwards = mock(ForwardService.class);
        long now = System.currentTimeMillis();
        Map<String,Object> quota = Map.of("trafficQuotaEnabled", true, "trafficQuotaPaused", true,
                "trafficQuotaExhausted", false, "trafficQuotaResetDay", 8,
                "trafficQuotaPeriodStartAt", CrossEntryFailoverService.trafficQuotaPeriodStartAt(8, now));
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(quota));
        when(jdbc.queryForList(contains("cross_entry_quota_pause"), eq(Long.class), eq(7L))).thenReturn(List.of(9L));
        when(forwards.resumeManagedForward(9L)).thenReturn(R.err("offline"), R.ok());
        CrossEntryFailoverService service = service(jdbc, forwards, null);
        try {
            ReflectionTestUtils.invokeMethod(service, "prepareTrafficQuotaPeriod", 7L, now);
            verify(jdbc, never()).update(contains("traffic_quota_paused=0,enabled=1"), any(), eq(7L));
            verify(jdbc, never()).update(contains("DELETE FROM cross_entry_quota_pause"), eq(7L), eq(9L));
            ReflectionTestUtils.invokeMethod(service, "prepareTrafficQuotaPeriod", 7L, now + 60_000L);
            verify(forwards, times(2)).resumeManagedForward(9L);
            verify(jdbc).update(contains("traffic_quota_paused=0,enabled=1"), any(), eq(7L));
            verify(jdbc).update(contains("DELETE FROM cross_entry_quota_pause"), eq(7L), eq(9L));
        } finally { service.shutdown(); }
    }

    @Test
    void activeCustomerGrantsProtectSourceMembershipAndDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(7L))).thenReturn(1);
        when(jdbc.queryForList(contains("FROM cross_entry_failover_group WHERE id="), eq(7L)))
                .thenReturn(List.of(Map.of("domain", "a.example.com", "recordType", "A", "dnsZoneId", 1L)));
        when(jdbc.queryForList(contains("FROM cross_entry_failover_member WHERE group_id="), eq(7L)))
                .thenReturn(List.of(new HashMap<>(Map.of("forwardId", 10L))));
        CrossEntryFailoverService service = service(jdbc, null, null);
        CrossEntryFailoverSaveDto dto = new CrossEntryFailoverSaveDto(); dto.setId(7L); dto.setDomain("a.example.com"); dto.setDnsZoneId(1L);
        try {
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service,
                    "assertAuthorizedSourceChangeAllowed", dto, List.of(Map.of("id", 10L))));
            assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service,
                    "assertAuthorizedSourceChangeAllowed", dto, List.of(Map.of("id", 20L))));
            dto.setDomain("changed.example.com");
            assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service,
                    "assertAuthorizedSourceChangeAllowed", dto, List.of(Map.of("id", 10L))));
            dto.setDomain("a.example.com"); dto.setDnsZoneId(2L);
            assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(service,
                    "assertAuthorizedSourceChangeAllowed", dto, List.of(Map.of("id", 10L))));
            assertNotEquals(0, service.delete(7L).getCode());
        } finally { service.shutdown(); }
    }

    @Test
    void dnsVerificationAcceptsEquivalentIpv6Notation() {
        assertTrue(CrossEntryFailoverService.dnsAddressMatches("2001:4860:4860:0:0:0:0:8888", "2001:4860:4860::8888"));
        assertFalse(CrossEntryFailoverService.dnsAddressMatches("8.8.8.8", "1.1.1.1"));
    }

    @Test
    void saveRetainsMemberIdsDisabledStateAndDailyHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DnsProviderService dns = mock(DnsProviderService.class);
        when(dns.normalizeDomain(1L, "a.example.com")).thenReturn("a.example.com");
        when(dns.loadZoneAccess(1L)).thenReturn(new DnsProviderService.ZoneAccess(1L, "zone", "example.com", "token"));
        when(dns.ensureManagedRecord(anyLong(), any(), anyString(), anyString(), anyString(), anyInt(), anyLong())).thenReturn("record");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        Map<String,Object> old = new HashMap<>(Map.of("activeForwardId", 10L, "activeMemberId", 100L,
                "groupEnabled", true, "domain", "a.example.com", "record_type", "A", "dns_zone_id", 1L, "record_id", "record"));
        List<Map<String,Object>> members = List.of(Map.of("id", 100L, "forwardId", 10L, "enabled", true),
                Map.of("id", 200L, "forwardId", 20L, "enabled", false));
        List<Map<String,Object>> options = List.of(option(10L, 1L, "8.8.8.8"), option(20L, 2L, "1.1.1.1"));
        when(jdbc.queryForList(anyString(), eq(7L))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.startsWith("SELECT f.id")) return options;
            if (sql.startsWith("SELECT g.api_token")) return List.of(old);
            if (sql.startsWith("SELECT id,enabled,forward_id")) return members;
            if (sql.startsWith("SELECT id,entry_address")) return List.of(Map.of("id", 100L, "entryAddress", "8.8.8.8", "nodeName", "entry"));
            if (sql.contains("FROM cross_entry_failover_group WHERE id=")) return List.of(Map.of("id", 7L, "enabled", true,
                    "dnsZoneId", 1L, "recordId", "record", "domain", "a.example.com", "recordType", "A", "ttl", 60));
            return List.of();
        });
        when(jdbc.queryForList(contains("WHERE f.id IN"), eq(10L), eq(20L))).thenReturn(options);
        when(jdbc.queryForList(contains("SELECT id,entry_address"), eq(100L)))
                .thenReturn(List.of(Map.of("id", 100L, "entryAddress", "8.8.8.8", "nodeName", "entry")));
        CrossEntryFailoverService service = service(jdbc, null, dns);
        CrossEntryFailoverSaveDto dto = new CrossEntryFailoverSaveDto();
        dto.setId(7L); dto.setName("renamed"); dto.setDomain("a.example.com"); dto.setDnsZoneId(1L); dto.setMemberForwardIds(List.of(10L,20L));
        org.springframework.transaction.PlatformTransactionManager transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        org.springframework.aop.framework.ProxyFactory proxy = new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        try {
            R result = ((CrossEntryFailoverService) proxy.getProxy()).save(dto);
            assertEquals(0, result.getCode(), result.getMsg());
            List<String> sql = mockingDetails(jdbc).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("update"))
                    .map(call -> call.getArgument(0, String.class)).toList();
            assertFalse(sql.stream().anyMatch(s -> s.startsWith("INSERT INTO cross_entry_failover_member")));
            assertFalse(sql.stream().anyMatch(s -> s.startsWith("DELETE FROM cross_entry_member_daily_usage")));
            assertTrue(sql.stream().anyMatch(s -> s.startsWith("UPDATE cross_entry_failover_event e")));
            List<String> updates = sql.stream().filter(s -> s.startsWith("UPDATE cross_entry_failover_member SET priority")).toList();
            assertEquals(2, updates.size());
            assertTrue(updates.stream().noneMatch(s -> s.contains("enabled=")));
        } finally { service.shutdown(); }
    }

    private Map<String,Object> option(long id, long node, String ip) {
        return new HashMap<>(Map.of("id",id,"name","forward","status",1,"inPort",10000,"inNodeId",node,
                "entryHost",ip,"protocolMode","tcp","nodeName","entry"));
    }
}
