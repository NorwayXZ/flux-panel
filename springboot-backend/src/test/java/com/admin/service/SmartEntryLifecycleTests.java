package com.admin.service;

import com.admin.common.dto.SmartEntrySaveDto;
import com.admin.common.lang.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SmartEntryLifecycleTests {
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DynamicDnsService dns = mock(DynamicDnsService.class);
    private final List<String> writes = new ArrayList<>();
    private final Map<String, Object> group = new HashMap<>();
    private final List<Map<String, Object>> routes = new ArrayList<>();
    private boolean failSave;
    private JdbcTemplate jdbc;
    private SmartEntryService service;
    private final SmartEntryDnsBindingsService bindings = mock(SmartEntryDnsBindingsService.class);

    @BeforeEach void setup() {
        when(dns.normalizeLineRoutingDomain(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        group.putAll(Map.of("id", 7L, "name", "plan", "provider_ref_id", 5L, "zone_name", "example.com", "domain", "a.example.com", "record_type", "A",
                "state", "degraded", "probe_mode", "tcp", "probe_path", "/"));
        routes.add(route("default", 1)); routes.add(route("telecom", 2));
        routes.get(1).put("status", "unhealthy");
        routes.get(1).put("currentForwardId", 1L);
        routes.get(1).put("currentAddress", "192.0.2.1");
        jdbc = mock(JdbcTemplate.class, call -> {
            if (call.getArguments().length == 0 || !(call.getArgument(0) instanceof String sql)) return RETURNS_DEFAULTS.answer(call);
            if (call.getMethod().getName().equals("queryForList")) {
                if (sql.startsWith("SELECT id,provider")) return List.of(Map.of("id", 5L, "provider", "dnspod"));
                if (sql.startsWith("SELECT f.id")) {
                    long id = ((Number) call.getArgument(1)).longValue();
                    return List.of(Map.of("id", id, "name", "forward" + id, "inPort", 443,
                            "protocolMode", "tcp", "inNodeId", id, "nodeName", "node" + id, "entryHost", "192.0.2." + id));
                }
                if (sql.startsWith("SELECT * FROM smart_entry_group")) return List.of(group);
                if (sql.startsWith("SELECT id,group_id")) return routes;
                return List.of();
            }
            if (call.getMethod().getName().equals("queryForObject")) return 0;
            if (call.getMethod().getName().equals("update")) {
                writes.add(sql);
                if (failSave && sql.startsWith("UPDATE smart_entry_route SET fallback")) throw new IllegalStateException("simulated database failure");
                return 1;
            }
            return RETURNS_DEFAULTS.answer(call);
        });
        service = new SmartEntryService(jdbc, dns, mock(SchedulingConflictService.class), new SmartEntryMutationLocks(), transactions, bindings);
    }

    private Map<String, Object> route(String carrier, long id) {
        var result = new HashMap<String, Object>(Map.of("id", id, "carrier", carrier, "forwardId", id, "entryNodeId", id,
                "entryAddress", "192.0.2." + id, "entryPort", 443, "nodeName", "node" + id, "status", "healthy", "recordId", "dns" + id));
        result.put("ownershipReady", 1); result.put("managedCreated", 1);
        return result;
    }

    private SmartEntrySaveDto dto() {
        SmartEntrySaveDto dto = new SmartEntrySaveDto();
        dto.setId(7L); dto.setName("renamed"); dto.setProviderRefId(5L);
        dto.setDomain("a.example.com"); dto.setZoneName("example.com");
        List<SmartEntrySaveDto.Route> assignments = new ArrayList<>();
        for (var old : routes) {
            SmartEntrySaveDto.Route assignment = new SmartEntrySaveDto.Route();
            assignment.setCarrier(old.get("carrier").toString()); assignment.setForwardId((Long)old.get("forwardId")); assignments.add(assignment);
        }
        dto.setRoutes(assignments);
        return dto;
    }

    @Test void renamePreservesFailedRouteAndCurrentFallbackWithoutDnsWrites() {
        assertEquals(0, service.save(dto()).getCode());
        assertFalse(writes.stream().anyMatch(sql -> sql.startsWith("DELETE FROM smart_entry_route")));
        assertFalse(writes.stream().anyMatch(sql -> sql.contains("status='unknown'") || sql.contains("current_forward_id=")));
        assertNoDnsWrites();
        verify(transactions).commit(any());
    }

    @Test void databaseFailureRollsBackConfigurationAndCannotTouchDns() {
        failSave = true;
        assertNotEquals(0, service.save(dto()).getCode());
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
        assertNoDnsWrites();
    }

    @Test void physicalEntryReplacementArchivesOldActivityAndResetsNewBaseline() {
        var dto = dto(); dto.getRoutes().get(1).setForwardId(3L);
        assertEquals(0, service.save(dto).getCode());
        assertTrue(writes.stream().anyMatch(sql -> sql.startsWith("INSERT INTO smart_entry_activity_archive")));
        assertTrue(writes.stream().anyMatch(sql -> sql.contains("reported_total_connections=0")));
        assertFalse(writes.stream().anyMatch(sql -> sql.startsWith("UPDATE smart_entry_route SET telemetry_ready=?")));
    }

    @Test void dnsIdentityChangesAreRejectedBeforeMutation() {
        var dto = dto(); dto.setDomain("new.example.com");
        assertNotEquals(0, service.save(dto).getCode());
        assertTrue(writes.isEmpty()); assertNoDnsWrites();
    }

    @Test void deletionPersistsCleanupBeforeReturningAndDoesNotDeleteGroupEarly() {
        assertEquals(0, service.delete(7L).getCode());
        assertEquals(2, writes.stream().filter(sql -> sql.startsWith("INSERT INTO smart_entry_dns_cleanup")).count());
        assertTrue(writes.stream().anyMatch(sql -> sql.contains("state='deleting'")));
        assertFalse(writes.stream().anyMatch(sql -> sql.startsWith("DELETE FROM smart_entry_group")));
        assertNoDnsWrites();
    }

    @Test void repeatedDeleteDoesNotDuplicateCleanup() {
        group.put("state", "deleting");
        assertEquals(0, service.delete(7L).getCode());
        assertTrue(writes.isEmpty());
    }

    @Test void cannotEditADeletingStrategy() {
        group.put("state", "deleting");
        assertNotEquals(0, service.save(dto()).getCode());
        assertTrue(writes.isEmpty());
    }

    @Test void explicitBackupsMustReferToConfiguredDistinctCarriers() {
        var dto = dto(); dto.getRoutes().get(0).setFallbackCarriers(List.of("mobile"));
        assertNotEquals(0, service.save(dto).getCode());
        assertTrue(writes.isEmpty());
    }

    private void assertNoDnsWrites() {
        verify(dns, never()).ensureLineRoutingRecord(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), nullable(String.class));
        verify(dns, never()).deleteLineRoutingRecord(anyLong(), anyString(), anyString());
    }

    @Test void localModeDoesNotRequireProviderCredentialsAndConvertsPublicRecordsToCleanupTasks() {
        var config = dto(); config.setDnsMode("local"); config.setProviderRefId(null); config.setZoneName(null); config.setDnsAgentIds(List.of(11L));
        assertEquals(0, service.save(config).getCode());
        assertEquals(2, writes.stream().filter(sql -> sql.startsWith("INSERT INTO smart_entry_dns_cleanup")).count());
        verify(bindings).setBindings(7L, List.of(11L));
        verify(jdbc, never()).queryForList(startsWith("SELECT id,provider"), anyLong());
        assertNoDnsWrites();
        verify(transactions).commit(any());
    }

    @Test void localModeRequiresAnAgentAndCannotSilentlyTurnIntoPublicDns() {
        var config = dto(); config.setDnsMode("local"); config.setDnsAgentIds(List.of());
        assertNotEquals(0, service.save(config).getCode());
        assertTrue(writes.isEmpty());
        group.put("dns_mode", "local");
        config.setDnsMode("public");
        assertNotEquals(0, service.save(config).getCode());
        assertTrue(writes.isEmpty());
    }

    @Test void bindingFailureRollsBackTheEntireStrategySave() {
        var config = dto(); config.setDnsMode("local"); config.setDnsAgentIds(List.of(11L));
        doThrow(new IllegalArgumentException("Agent deleted")).when(bindings).setBindings(7L, List.of(11L));
        assertNotEquals(0, service.save(config).getCode());
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
        assertNoDnsWrites();
    }

    @Test void legacyEditKeepsLocalModeAndExistingRouterBindings() {
        group.put("dns_mode", "local"); group.put("provider_ref_id", 0L);
        routes.forEach(route -> { route.remove("recordId"); route.put("ownershipReady", 0); });
        when(bindings.bindings()).thenReturn(Map.of(7L, List.of(11L)));
        var config = dto(); config.setProviderRefId(null);
        assertEquals(0, service.save(config).getCode());
        assertEquals("local", config.getDnsMode());
        verify(bindings).setBindings(7L, List.of(11L));
        assertFalse(writes.stream().anyMatch(sql -> sql.startsWith("INSERT INTO smart_entry_dns_cleanup")));
        assertNoDnsWrites();
    }

    @Test void deletingStrategyRemovesRouterBindingWithinTheSameTransaction() {
        assertEquals(0, service.delete(7L).getCode());
        verify(bindings).setBindings(7L, List.of());
        verify(transactions).commit(any());
    }

    @Test void localHealthSynchronizationNeverContactsDnsProvider() throws Exception {
        group.put("dns_mode", "local"); group.put("enabled", 1);
        var sync = SmartEntryService.class.getDeclaredMethod("syncRecords", Long.class, String.class, boolean.class);
        sync.setAccessible(true); sync.invoke(service, 7L, "test", true);
        verify(dns, never()).inspectLineRoutingRecords(anyLong(), anyString(), anyString(), anyString());
        assertNoDnsWrites();
        verify(jdbc).update(startsWith("UPDATE smart_entry_route SET current_forward_id"), eq(1L), eq("192.0.2.1"), eq("healthy"), anyBoolean(), anyLong(), anyLong(), eq(2L));
    }

    @Test void localHealthSynchronizationClearsDeadAddressesRatherThanKeepingOldDns() throws Exception {
        group.put("dns_mode", "local"); group.put("enabled", 1);
        routes.forEach(route -> route.put("status", "unhealthy"));
        var sync = SmartEntryService.class.getDeclaredMethod("syncRecords", Long.class, String.class, boolean.class);
        sync.setAccessible(true); sync.invoke(service, 7L, "test", true);
        verify(jdbc).update(startsWith("UPDATE smart_entry_route SET current_forward_id"), isNull(), isNull(), eq("pending"), anyBoolean(), anyLong(), anyLong(), eq(2L));
        verify(dns, never()).inspectLineRoutingRecords(anyLong(), anyString(), anyString(), anyString());
        assertNoDnsWrites();
    }

    @Test void failedDnsCleanupKeepsTheDurableTaskAndDeletingGroupForRetry() {
        group.put("state", "deleting");
        when(jdbc.queryForList(startsWith("SELECT id FROM smart_entry_group"), eq(Long.class))).thenReturn(List.of(7L));
        String payload = com.alibaba.fastjson.JSON.toJSONString(Map.of("group", group, "route", routes.get(0)));
        when(jdbc.queryForList(startsWith("SELECT * FROM smart_entry_dns_cleanup"), eq(7L), anyLong()))
                .thenReturn(List.of(Map.of("id", 100L, "payload", payload)));
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM smart_entry_dns_cleanup"), eq(Integer.class), eq(7L))).thenReturn(1);
        when(dns.inspectLineRoutingRecords(5L, "example.com", "a.example.com", "A"))
                .thenReturn(List.of(new DynamicDnsService.LineRoutingRecordState("default", "dns1", "192.0.2.1", 60, true, "default")));
        doThrow(new IllegalStateException("simulated provider outage")).when(dns).deleteLineRoutingRecord(5L, "example.com", "dns1");
        service.cleanupDns();
        assertTrue(writes.stream().anyMatch(sql -> sql.startsWith("UPDATE smart_entry_dns_cleanup SET last_error=")));
        assertFalse(writes.stream().anyMatch(sql -> sql.startsWith("DELETE FROM smart_entry_dns_cleanup") || sql.startsWith("DELETE FROM smart_entry_group")));
    }

    @Test void missingManagedDnsRecordIsAlreadyCleanedAndDoesNotBlockDeletion() {
        group.put("state", "deleting");
        when(jdbc.queryForList(startsWith("SELECT id FROM smart_entry_group"), eq(Long.class))).thenReturn(List.of(7L));
        String payload = com.alibaba.fastjson.JSON.toJSONString(Map.of("group", group, "route", routes.get(0)));
        when(jdbc.queryForList(startsWith("SELECT * FROM smart_entry_dns_cleanup"), eq(7L), anyLong()))
                .thenReturn(List.of(Map.of("id", 100L, "payload", payload)));
        when(dns.inspectLineRoutingRecords(5L, "example.com", "a.example.com", "A")).thenReturn(List.of());
        service.cleanupDns();
        assertTrue(writes.stream().anyMatch(sql -> sql.startsWith("DELETE FROM smart_entry_dns_cleanup")));
        assertTrue(writes.stream().anyMatch(sql -> sql.startsWith("DELETE FROM smart_entry_group")));
        verify(dns, never()).deleteLineRoutingRecord(anyLong(), anyString(), anyString());
    }
}
