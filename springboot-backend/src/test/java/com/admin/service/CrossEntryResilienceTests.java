package com.admin.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrossEntryResilienceTests {
    private JdbcTemplate jdbc;
    private CrossEntryFailoverService service;
    private DnsProviderService dns;
    private TelegramNotificationService telegram;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:entry" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE cross_entry_failover_member (id BIGINT PRIMARY KEY,group_id BIGINT,forward_id BIGINT,"
                + "priority INT DEFAULT 0,weight INT DEFAULT 100,enabled BOOLEAN DEFAULT TRUE,entry_node_id BIGINT,"
                + "entry_host VARCHAR(255),entry_address VARCHAR(128),address_refresh_error VARCHAR(500),topology_signature VARCHAR(500),"
                + "entry_port INT,forward_name VARCHAR(100),node_name VARCHAR(100),status VARCHAR(24),fail_count INT DEFAULT 0,"
                + "success_count INT DEFAULT 0,latency_ms INT,quality_latency_ms INT,quality_p95_ms INT,quality_jitter_ms INT,"
                + "quality_loss_percent DOUBLE,quality_baseline_ms INT,quality_preheated BOOLEAN DEFAULT FALSE,quality_state VARCHAR(24),"
                + "quality_bad_count INT DEFAULT 0,quality_good_count INT DEFAULT 0,quality_flap_count INT DEFAULT 0,"
                + "quality_flap_window_started_at BIGINT,quality_suppressed_until BIGINT,quality_suppressed_reason VARCHAR(255),"
                + "quality_penalty_level INT DEFAULT 0,quality_penalty_episode_count INT DEFAULT 0,quality_penalty_window_started_at BIGINT,"
                + "quality_penalty_last_at BIGINT,quality_recovery_observe_until BIGINT,switch_rejected_until BIGINT,"
                + "switch_rejected_reason VARCHAR(255),switch_reject_count INT DEFAULT 0,quality_last_error VARCHAR(500),quality_checked_at BIGINT,"
                + "fault_episode_count INT DEFAULT 0,connect_fault_count INT DEFAULT 0,latency_fault_count INT DEFAULT 0,loss_fault_count INT DEFAULT 0,"
                + "p95_fault_count INT DEFAULT 0,jitter_fault_count INT DEFAULT 0,flap_fault_count INT DEFAULT 0,switch_trigger_count INT DEFAULT 0,"
                + "last_fault_type VARCHAR(32),last_fault_reason VARCHAR(255),last_fault_at BIGINT,last_error VARCHAR(500),"
                + "last_checked_at BIGINT,last_healthy_at BIGINT,last_failure_at BIGINT,telemetry_ready BOOLEAN DEFAULT FALSE,"
                + "total_connections BIGINT DEFAULT 0,current_connections BIGINT DEFAULT 0,reported_total_connections BIGINT DEFAULT 0,"
                + "telemetry_generation BIGINT DEFAULT 0,last_telemetry_at BIGINT,activity_in_flow BIGINT DEFAULT 0,activity_out_flow BIGINT DEFAULT 0,"
                + "last_in_flow_at BIGINT,last_out_flow_at BIGINT,last_activity_at BIGINT,pending_probe_connections INT DEFAULT 0,updated_time BIGINT)");
        jdbc.execute("CREATE TABLE cross_entry_failover_group (id BIGINT PRIMARY KEY,state VARCHAR(24),last_error VARCHAR(500),"
                + "active_member_id BIGINT,last_checked_at BIGINT,last_switch_at BIGINT,active_since_at BIGINT,updated_time BIGINT,"
                + "availability_alert_signature VARCHAR(255),detached_active_address VARCHAR(128),manual_control_mode VARCHAR(16) DEFAULT 'auto',"
                + "locked_member_id BIGINT,manual_lock_until BIGINT,quality_probe_status VARCHAR(24),quality_probe_error VARCHAR(500),quality_probe_at BIGINT)");
        jdbc.execute("CREATE TABLE cross_entry_switch_pending (group_id BIGINT PRIMARY KEY,from_member_id BIGINT,to_member_id BIGINT,"
                + "from_address VARCHAR(128),to_address VARCHAR(128),reason VARCHAR(255),phase VARCHAR(24),last_error VARCHAR(500),"
                + "attempts INT DEFAULT 0,created_time BIGINT,updated_time BIGINT)");
        jdbc.execute("CREATE TABLE cross_entry_failover_event (id BIGINT AUTO_INCREMENT PRIMARY KEY,group_id BIGINT,from_member_id BIGINT,"
                + "to_member_id BIGINT,from_node_name VARCHAR(100),to_node_name VARCHAR(100),from_forward_name VARCHAR(100),"
                + "to_forward_name VARCHAR(100),from_entry_address VARCHAR(128),to_entry_address VARCHAR(128),"
                + "from_entry_port INT,to_entry_port INT,reason VARCHAR(255),status VARCHAR(24),detail VARCHAR(500),created_time BIGINT)");
        jdbc.execute("CREATE TABLE authorized_entry_template (id BIGINT,source_group_id BIGINT)");
        jdbc.execute("CREATE TABLE authorized_entry_grant (id BIGINT,template_id BIGINT,state VARCHAR(24))");
        jdbc.execute("CREATE TABLE authorized_entry_port (grant_id BIGINT,state VARCHAR(24),target_host VARCHAR(255))");
        jdbc.update("INSERT INTO cross_entry_failover_group(id,state,active_member_id) VALUES(7,'healthy',1)");
        insertMember(1, "8.8.8.8", "8.8.8.8", "healthy");
        insertMember(2, "1.1.1.1", "1.1.1.1", "unhealthy");
        dns = mock(DnsProviderService.class);
        telegram = mock(TelegramNotificationService.class);
        service = spy(new CrossEntryFailoverService(jdbc, null, telegram, dns, null, null, null, null, null, null));
    }

    private void insertMember(long id, String host, String address, String status) {
        jdbc.update("INSERT INTO cross_entry_failover_member(id,group_id,forward_id,entry_node_id,entry_host,entry_address,entry_port,"
                        + "node_name,forward_name,status,updated_time) VALUES(?,7,?,?,?, ?,443,?,?,?,0)",
                id, id, id, host, address, "node" + id, "forward" + id, status);
    }

    private Map<String, Object> group() {
        return new HashMap<>(Map.of("id", 7L, "activeMemberId", 1L, "dnsZoneId", 5L,
                "recordId", "record", "domain", "service.example", "recordType", "A", "ttl", 60,
                "routingMode", "failover", "connectTimeoutMs", 300));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> members() {
        return ReflectionTestUtils.invokeMethod(service, "loadMembers", 7L);
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    @Test
    void failedEntryRequiresConsecutiveSuccessesAndResetsAfterAnotherFailure() {
        Map<String, Object> member = members().get(1);
        for (int expected = 1; expected <= 2; expected++) {
            ReflectionTestUtils.invokeMethod(service, "updateMemberHealth",
                    new CrossEntryFailoverService.ProbeResult(member, true, 10, null), 2, 3, 1000L + expected);
            member = members().get(1);
            assertEquals("recovering", member.get("status"));
            assertEquals(expected, ((Number) member.get("successCount")).intValue());
        }
        ReflectionTestUtils.invokeMethod(service, "updateMemberHealth",
                new CrossEntryFailoverService.ProbeResult(member, false, null, "timeout"), 2, 3, 2000L);
        member = members().get(1);
        assertEquals("unhealthy", member.get("status"));
        assertEquals(0, ((Number) member.get("successCount")).intValue());
        for (int expected = 1; expected <= 3; expected++) {
            ReflectionTestUtils.invokeMethod(service, "updateMemberHealth",
                    new CrossEntryFailoverService.ProbeResult(member, true, 10, null), 2, 3, 3000L + expected);
            member = members().get(1);
        }
        assertEquals("healthy", member.get("status"));
    }

    @Test
    void multiEntryPublishesOnlyStableMembersUnlessNoStableMemberExists() {
        jdbc.update("UPDATE cross_entry_failover_member SET status='recovering',success_count=1 WHERE id=2");
        Map<String, Object> group = group();
        group.put("routingMode", "active_active");
        when(dns.syncCrossEntryActiveRecords(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyList(), anyBoolean()))
                .thenReturn(new DnsProviderService.DnsPoolSyncResult(0, 0, 1));
        ReflectionTestUtils.invokeMethod(service, "updateActiveActiveGroup", group, members(), 1000L);
        @SuppressWarnings("unchecked") var targets = (org.mockito.ArgumentCaptor<List<DnsProviderService.CrossEntryDnsTarget>>) (Object)
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(dns).syncCrossEntryActiveRecords(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyLong(), targets.capture(), anyBoolean());
        assertEquals(List.of(1L), targets.getValue().stream().map(DnsProviderService.CrossEntryDnsTarget::memberId).toList());
        jdbc.update("UPDATE cross_entry_failover_member SET status='unhealthy' WHERE id=1");
        ReflectionTestUtils.invokeMethod(service, "updateActiveActiveGroup", group, members(), 2000L);
        verify(dns, times(2)).syncCrossEntryActiveRecords(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyLong(), targets.capture(), anyBoolean());
        assertEquals(List.of(2L), targets.getAllValues().get(targets.getAllValues().size() - 1).stream().map(DnsProviderService.CrossEntryDnsTarget::memberId).toList());
        assertTrue(jdbc.queryForObject("SELECT last_error FROM cross_entry_failover_group WHERE id=7", String.class).contains("恢复观察"));
    }

    @Test
    void successfulButTimedOutDnsWriteIsConfirmedOnNextPass() {
        Map<String, Object> group = group();
        List<Map<String, Object>> members = members();
        doReturn("8.8.8.8", "1.1.1.1").when(service).readProviderRecordContent(anyMap());
        doThrow(new IllegalStateException("timeout")).when(dns).updateManagedRecord(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong());
        ReflectionTestUtils.invokeMethod(service, "switchEntry", group, members.get(0), members.get(1), "故障", 1000L);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
        doAnswer(call -> new CrossEntryFailoverService.ProbeResult(call.getArgument(0), true, 10, null))
                .when(service).probe(anyMap(), anyInt());
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "reconcilePendingSwitch", group));
        assertEquals(2L, jdbc.queryForObject("SELECT active_member_id FROM cross_entry_failover_group WHERE id=7", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
        verify(dns, times(1)).updateManagedRecord(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void externallyChangedDnsIsNotOverwrittenOnInitialSwitch() {
        doReturn("9.9.9.9").when(service).readProviderRecordContent(anyMap());
        List<Map<String, Object>> list = members();
        ReflectionTestUtils.invokeMethod(service, "switchEntry", group(), list.get(0), list.get(1), "故障", 1000L);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
        assertEquals("error", jdbc.queryForObject("SELECT state FROM cross_entry_failover_group WHERE id=7", String.class));
        verify(dns, never()).updateManagedRecord(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong());
        verify(telegram).notifyCrossEntryAlert(eq(7L), any(), any(), contains("外部修改"), anyString(), eq(false), eq(1000L));
    }

    @Test
    void dnsRestoredToOldAddressAlsoRestoresTheRecordedActiveMember() {
        jdbc.update("UPDATE cross_entry_failover_group SET active_member_id=2 WHERE id=7");
        jdbc.update("INSERT INTO cross_entry_switch_pending VALUES(7,1,2,'8.8.8.8','1.1.1.1','故障','applying',NULL,0,1000,1000)");
        doReturn("8.8.8.8").when(service).readProviderRecordContent(anyMap());
        Map<String, Object> group = group();
        group.put("activeMemberId", 2L);
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "reconcilePendingSwitch", group));
        assertEquals(1L, jdbc.queryForObject("SELECT active_member_id FROM cross_entry_failover_group WHERE id=7", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
    }

    @Test
    void recoveringCurrentEntryIsDegradedRatherThanOffline() {
        var result = CrossEntryFailoverService.groupHealth(true, true, false, 0, 2, "");
        assertEquals("degraded", result.state());
        assertTrue(result.error().contains("观察"));
        assertEquals("offline", CrossEntryFailoverService.groupHealth(true, false, false, 0, 2, "").state());
    }

    @Test
    void entryDnsLookupHasABoundedWait() throws Exception {
        doAnswer(call -> { Thread.sleep(20_000); return new java.net.InetAddress[0]; })
                .when(service).lookupEntryHost("slow.example");
        long start = System.nanoTime();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.resolveAddresses("slow.example", "A"));
        assertTrue(error.getMessage().contains("超时"));
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 7);
    }

    @Test
    void providerRecordMustStillBelongToExpectedDomainAndBeDnsOnly() {
        RestTemplate rest = mock(RestTemplate.class);
        DnsProviderService provider = mock(DnsProviderService.class);
        when(provider.loadZoneAccess(5L)).thenReturn(new DnsProviderService.ZoneAccess(5L, "zone", "example", "token"));
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"result\":{\"name\":\"other.example\",\"type\":\"A\",\"content\":\"8.8.8.8\",\"proxied\":false}}"),
                        ResponseEntity.ok("{\"success\":true,\"result\":{\"name\":\"service.example\",\"type\":\"A\",\"content\":\"8.8.8.8\",\"proxied\":true}}"),
                        ResponseEntity.ok("{\"success\":true,\"result\":{\"name\":\"service.example\",\"type\":\"A\",\"content\":\"8.8.8.8\",\"proxied\":false}}"));
        CrossEntryFailoverService reader = new CrossEntryFailoverService(jdbc, rest, null, provider, null, null, null, null, null, null);
        try {
            assertThrows(IllegalStateException.class, () -> reader.readProviderRecordContent(group()));
            assertThrows(IllegalStateException.class, () -> reader.readProviderRecordContent(group()));
            assertEquals("8.8.8.8", reader.readProviderRecordContent(group()));
        } finally { reader.shutdown(); }
    }

    @Test
    void externalDnsConflictStopsAutomaticOverwrite() {
        jdbc.update("INSERT INTO cross_entry_switch_pending VALUES(7,1,2,'8.8.8.8','1.1.1.1','故障','applying',NULL,0,1000,1000)");
        doReturn("9.9.9.9").when(service).readProviderRecordContent(anyMap());
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "reconcilePendingSwitch", group()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
        verifyNoInteractions(dns);
    }

    @Test
    void dnsMovedToAnotherEntryIsFollowedInsteadOfBlocked() {
        doReturn("1.1.1.1").when(service).readProviderRecordContent(anyMap());
        List<Map<String, Object>> list = members();
        Object outcome = ReflectionTestUtils.invokeMethod(service, "checkDnsDrift", group(), list, list.get(0), false, 1000L);
        assertEquals("ADOPTED", outcome.toString());
        assertEquals(2L, jdbc.queryForObject("SELECT active_member_id FROM cross_entry_failover_group WHERE id=7", Long.class));
        verifyNoInteractions(dns);
    }

    @Test
    void unknownDnsBlocksSwitchingOnlyUntilTheRecordReturns() {
        Map<String, Object> group = group();
        List<Map<String, Object>> list = members();
        doReturn("9.9.9.9").when(service).readProviderRecordContent(anyMap());
        assertEquals("BLOCKED", ReflectionTestUtils.invokeMethod(service, "checkDnsDrift", group, list, list.get(0), false, 1000L).toString());
        assertEquals("BLOCKED", ReflectionTestUtils.invokeMethod(service, "checkDnsDrift", group, list, list.get(0), false, 2000L).toString());
        verify(service, times(1)).readProviderRecordContent(anyMap());
        verify(telegram, times(1)).notifyCrossEntryAlert(eq(7L), any(), any(), contains("外部修改"), anyString(), eq(false), anyLong());
        doReturn("8.8.8.8").when(service).readProviderRecordContent(anyMap());
        assertEquals("CLEAR", ReflectionTestUtils.invokeMethod(service, "checkDnsDrift", group, list, list.get(0), false, 40_000L).toString());
        verify(telegram).notifyCrossEntryAlert(eq(7L), any(), any(), contains("恢复"), anyString(), eq(true), anyLong());
    }

    @Test
    void deletingTheActiveNodeMovesDnsOffItsAddress() {
        assertEquals(1, service.detachNodeResources(1L, List.of()));
        assertNull(jdbc.queryForObject("SELECT active_member_id FROM cross_entry_failover_group WHERE id=7", Long.class));
        assertEquals("8.8.8.8", jdbc.queryForObject("SELECT detached_active_address FROM cross_entry_failover_group WHERE id=7", String.class));

        Map<String, Object> group = group();
        group.put("activeMemberId", null);
        group.put("detachedActiveAddress", "8.8.8.8");
        doReturn("8.8.8.8").when(service).readProviderRecordContent(anyMap());
        List<Map<String, Object>> list = members();
        assertEquals("CLEAR", ReflectionTestUtils.invokeMethod(service, "checkDnsDrift", group, list, null, true, 1000L).toString());
        ReflectionTestUtils.invokeMethod(service, "switchEntry", group, null, list.get(0), "当前入口不存在", 1000L);

        verify(dns).updateManagedRecord(eq(5L), anyString(), anyString(), anyString(), eq("1.1.1.1"), anyInt(), eq(7L));
        assertEquals(2L, jdbc.queryForObject("SELECT active_member_id FROM cross_entry_failover_group WHERE id=7", Long.class));
        assertNull(jdbc.queryForObject("SELECT detached_active_address FROM cross_entry_failover_group WHERE id=7", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
    }

    @Test
    void interruptedSwitchOffADeletedEntryIsRetried() {
        jdbc.update("UPDATE cross_entry_failover_group SET active_member_id=NULL WHERE id=7");
        jdbc.update("DELETE FROM cross_entry_failover_member WHERE id=1");
        jdbc.update("INSERT INTO cross_entry_switch_pending VALUES(7,NULL,2,'8.8.8.8','1.1.1.1','当前入口不存在','applying',NULL,0,1000,1000)");
        doReturn("8.8.8.8").when(service).readProviderRecordContent(anyMap());
        Map<String, Object> group = group();
        group.put("activeMemberId", null);
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "reconcilePendingSwitch", group));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
        assertEquals("8.8.8.8", group.get("detachedActiveAddress"));
    }

    @Test
    void offlineEntryAlertsOncePerChangeAndAgainOnRecovery() {
        long now = 1_000_000L;
        Map<String, Object> group = group();
        jdbc.update("UPDATE cross_entry_failover_member SET last_healthy_at=? WHERE id=2", now - 5_000L);
        ReflectionTestUtils.invokeMethod(service, "evaluateAvailabilityAlert", group, members(), now);
        verify(telegram, never()).notifyCrossEntryAlert(anyLong(), any(), any(), anyString(), anyString(), anyBoolean(), anyLong());

        long later = now + CrossEntryFailoverService.AVAILABILITY_ALERT_DEBOUNCE_MS;
        ReflectionTestUtils.invokeMethod(service, "evaluateAvailabilityAlert", group, members(), later);
        ReflectionTestUtils.invokeMethod(service, "evaluateAvailabilityAlert", group, members(), later + 2_000L);
        verify(telegram, times(1)).notifyCrossEntryAlert(eq(7L), any(), any(), contains("没有备用"), anyString(), eq(false), eq(later));

        jdbc.update("UPDATE cross_entry_failover_member SET status='healthy' WHERE id=2");
        ReflectionTestUtils.invokeMethod(service, "evaluateAvailabilityAlert", group, members(), later + 4_000L);
        verify(telegram).notifyCrossEntryAlert(eq(7L), any(), any(), contains("恢复"), anyString(), eq(true), eq(later + 4_000L));
        assertNull(jdbc.queryForObject("SELECT availability_alert_signature FROM cross_entry_failover_group WHERE id=7", String.class));
    }

    @Test
    void silentProbeSourceKeepsEntryQualityAndStopsQualityDecisions() {
        jdbc.update("UPDATE cross_entry_failover_member SET quality_state='healthy',quality_latency_ms=30,quality_loss_percent=0");
        Map<String, Object> group = group();
        group.put("qualityProbeSourceType", "panel");
        group.put("qualityProbeCount", 4);
        doAnswer(call -> CrossEntryFailoverService.QualityProbeResult.sourceFailed(call.getArgument(1), "探测源无响应"))
                .when(service).probeQuality(anyMap(), anyMap(), anyInt(), anyInt());

        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "updateQuality", group, members(), 300, 1000L));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "updateQuality", group, members(), 300, 2000L));

        verify(service, times(2)).probeQuality(anyMap(), anyMap(), anyInt(), anyInt());
        assertEquals("failed", jdbc.queryForObject("SELECT quality_probe_status FROM cross_entry_failover_group WHERE id=7", String.class));
        assertEquals("healthy", jdbc.queryForObject("SELECT quality_state FROM cross_entry_failover_member WHERE id=1", String.class));
        assertEquals(0.0, jdbc.queryForObject("SELECT quality_loss_percent FROM cross_entry_failover_member WHERE id=1", Double.class));
    }

    @Test
    void oneMissingSampleLeavesThatEntryUntouched() {
        jdbc.update("UPDATE cross_entry_failover_member SET quality_state='healthy',quality_loss_percent=0");
        Map<String, Object> group = group();
        group.put("qualityProbeSourceType", "panel");
        doAnswer(call -> {
            Map<String, Object> member = call.getArgument(1);
            return ((Number) member.get("id")).longValue() == 1L
                    ? CrossEntryFailoverService.QualityProbeResult.sourceFailed(member, "等待响应超时")
                    : new CrossEntryFailoverService.QualityProbeResult(member, true, 30, 30, 1, 0.0, null);
        }).when(service).probeQuality(anyMap(), anyMap(), anyInt(), anyInt());
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "updateQuality", group, members(), 300, 1000L));
        assertEquals("healthy", jdbc.queryForObject("SELECT quality_state FROM cross_entry_failover_member WHERE id=1", String.class));
        assertEquals(0.0, jdbc.queryForObject("SELECT quality_loss_percent FROM cross_entry_failover_member WHERE id=1", Double.class));
    }

    @Test
    void qualityRoundIsInconclusiveOnlyWhenEveryEntryFailed() {
        assertTrue(CrossEntryFailoverService.qualityRoundInconclusive(3, 3));
        assertFalse(CrossEntryFailoverService.qualityRoundInconclusive(3, 2));
        assertFalse(CrossEntryFailoverService.qualityRoundInconclusive(0, 0));
    }

    @Test
    void nodeDisconnectIsJudgedByTheEntryPortUntilGraceRunsOut() throws Exception {
        doReturn(false).when(service).isEntryNodeOnline(anyLong());
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))) {
            Map<String, Object> member = members().get(0);
            member.put("entryAddress", "127.0.0.1");
            member.put("entryPort", listener.getLocalPort());
            member.put("status", "healthy");
            assertTrue(service.probe(member, 500).healthy());
            member.put("status", "unhealthy");
            assertEquals("入口节点离线", service.probe(member, 500).error());
            member.put("status", "healthy");

            Map<Long, Long> offlineSince = (Map<Long, Long>) ReflectionTestUtils.getField(service, "nodeOfflineSince");
            long nodeId = ((Number) member.get("entryNodeId")).longValue();
            offlineSince.put(nodeId, System.currentTimeMillis() - CrossEntryFailoverService.NODE_OFFLINE_GRACE_MS);
            CrossEntryFailoverService.ProbeResult expired = service.probe(member, 500);
            assertFalse(expired.healthy());
            assertEquals("入口节点离线", expired.error());

            doReturn(true).when(service).isEntryNodeOnline(anyLong());
            assertTrue(service.probe(member, 500).healthy());
            assertFalse(offlineSince.containsKey(nodeId));
        }
    }

    @Test
    void editedNodeAddressReachesTheEntryAfterVerification() {
        doReturn("4.4.4.4").when(service).currentEntryHost(anyMap(), anyLong());
        doAnswer(call -> new CrossEntryFailoverService.ProbeResult(call.getArgument(0), true, 10, null))
                .when(service).probe(anyMap(), anyInt());
        Map<String, Object> group = group();
        jdbc.update("UPDATE cross_entry_failover_group SET active_member_id=1 WHERE id=7");
        jdbc.update("UPDATE cross_entry_failover_member SET enabled=FALSE WHERE id=1");
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "refreshMemberAddresses", group, members(), 1000L));
        assertEquals("4.4.4.4", jdbc.queryForObject("SELECT entry_address FROM cross_entry_failover_member WHERE id=2", String.class));
    }

    @Test
    void rejectedTargetDoesNotRollBackToAnUnhealthyOriginalEntry() {
        jdbc.update("INSERT INTO cross_entry_switch_pending VALUES(7,1,2,'8.8.8.8','1.1.1.1','故障','rejected',NULL,0,1000,1000)");
        doReturn("1.1.1.1").when(service).readProviderRecordContent(anyMap());
        doAnswer(call -> new CrossEntryFailoverService.ProbeResult(call.getArgument(0), false, null, "unreachable"))
                .when(service).probe(anyMap(), anyInt());
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "reconcilePendingSwitch", group()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT last_error FROM cross_entry_failover_group WHERE id=7", String.class).contains("均不可用"));
        verifyNoInteractions(dns);
    }

    @Test
    void rejectedTargetCanMoveToAnotherVerifiedHealthyEntry() {
        jdbc.update("UPDATE cross_entry_failover_member SET status='unhealthy' WHERE id IN (1,2)");
        insertMember(3, "9.9.9.9", "9.9.9.9", "healthy");
        jdbc.update("INSERT INTO cross_entry_switch_pending VALUES(7,1,2,'8.8.8.8','1.1.1.1','故障','rejected',NULL,0,1000,1000)");
        doReturn("1.1.1.1", "9.9.9.9").when(service).readProviderRecordContent(anyMap());
        doAnswer(call -> new CrossEntryFailoverService.ProbeResult(call.getArgument(0), true, 10, null))
                .when(service).probe(anyMap(), anyInt());
        Map<String, Object> group = group();
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "reconcilePendingSwitch", group));
        verify(dns).updateManagedRecord(5L, "record", "service.example", "A", "9.9.9.9", 60, 7L);
        assertEquals(3L, jdbc.queryForObject("SELECT to_member_id FROM cross_entry_switch_pending WHERE group_id=7", Long.class));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "reconcilePendingSwitch", group));
        assertEquals(3L, jdbc.queryForObject("SELECT active_member_id FROM cross_entry_failover_group WHERE id=7", Long.class));
    }

    @Test
    void stableAlternativeWinsOverRecoveringEntryDuringEmergency() {
        jdbc.update("UPDATE cross_entry_failover_member SET status='unhealthy' WHERE id=1");
        jdbc.update("UPDATE cross_entry_failover_member SET status='recovering',success_count=1 WHERE id=2");
        insertMember(3, "9.9.9.9", "9.9.9.9", "healthy");
        Map<String, Object> group = group();
        group.put("routingMode", "active_active");
        when(dns.syncCrossEntryActiveRecords(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyList(), anyBoolean()))
                .thenReturn(new DnsProviderService.DnsPoolSyncResult(0, 0, 1));
        ReflectionTestUtils.invokeMethod(service, "updateActiveActiveGroup", group, members(), 1000L);
        @SuppressWarnings("unchecked") var targets = (org.mockito.ArgumentCaptor<List<DnsProviderService.CrossEntryDnsTarget>>) (Object)
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(dns).syncCrossEntryActiveRecords(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyLong(), targets.capture(), anyBoolean());
        assertEquals(List.of(3L), targets.getValue().stream().map(DnsProviderService.CrossEntryDnsTarget::memberId).toList());
    }

    @Test
    void dynamicDomainChangeKeepsOldAddressUntilDnsIsConfirmed() {
        jdbc.update("UPDATE cross_entry_failover_member SET entry_host='node.example' WHERE id=1");
        doReturn(List.of("9.9.9.9")).when(service).resolveAddresses("node.example", "A");
        doReturn("8.8.8.8", "9.9.9.9").when(service).readProviderRecordContent(anyMap());
        doAnswer(call -> new CrossEntryFailoverService.ProbeResult(call.getArgument(0), true, 10, null))
                .when(service).probe(anyMap(), anyInt());
        doThrow(new IllegalStateException("timeout")).when(dns).updateManagedRecord(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong());
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "refreshMemberAddresses", group(), members(), 1000L));
        assertEquals("8.8.8.8", jdbc.queryForObject("SELECT entry_address FROM cross_entry_failover_member WHERE id=1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "reconcilePendingSwitch", group()));
        assertEquals("9.9.9.9", jdbc.queryForObject("SELECT entry_address FROM cross_entry_failover_member WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_switch_pending", Integer.class));
    }

    @Test
    void dynamicDomainFailureOrLoopKeepsLastAddress() {
        jdbc.update("UPDATE cross_entry_failover_member SET entry_host='node.example' WHERE id=1");
        doThrow(new IllegalArgumentException("DNS unavailable")).when(service).resolveAddresses("node.example", "A");
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "refreshMemberAddresses", group(), members(), 1000L));
        assertEquals("8.8.8.8", jdbc.queryForObject("SELECT entry_address FROM cross_entry_failover_member WHERE id=1", String.class));
        assertTrue(jdbc.queryForObject("SELECT address_refresh_error FROM cross_entry_failover_member WHERE id=1", String.class).contains("保留旧地址"));
        jdbc.update("UPDATE cross_entry_failover_member SET entry_host='service.example' WHERE id=1");
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "refreshMemberAddresses", group(), members(), 62000L));
        assertTrue(jdbc.queryForObject("SELECT address_refresh_error FROM cross_entry_failover_member WHERE id=1", String.class).contains("避免循环"));
        verifyNoInteractions(dns);
    }

    @Test
    void authorizedTargetCannotMatchAnInProgressEntryAddress() {
        jdbc.update("INSERT INTO cross_entry_switch_pending VALUES(7,1,1,'8.8.8.8','9.9.9.9','地址变化','applying',NULL,0,1000,1000)");
        AuthorizedEntryService authorized = new AuthorizedEntryService();
        ReflectionTestUtils.setField(authorized, "jdbcTemplate", jdbc);
        assertEquals(true, ReflectionTestUtils.invokeMethod(authorized, "pendingEntryAddressMatches", 7L, "9.9.9.9:443"));
        assertEquals(false, ReflectionTestUtils.invokeMethod(authorized, "pendingEntryAddressMatches", 7L, "1.1.1.1:443"));
    }
}
