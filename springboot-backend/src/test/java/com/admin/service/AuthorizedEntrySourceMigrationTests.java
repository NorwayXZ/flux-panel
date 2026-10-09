package com.admin.service;

import com.admin.common.dto.ForwardDto;
import com.admin.common.lang.R;
import com.admin.entity.Forward;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthorizedEntrySourceMigrationTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ForwardService forwards = mock(ForwardService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AuthorizedEntrySourceMigrationService migration = new AuthorizedEntrySourceMigrationService(
            jdbc, forwards, mock(CrossEntryManagedCleanupService.class),
            new CrossEntryGroupMutationLocks(), new AuthorizedEntryGrantLocks(), transactions);
    private final List<AuthorizedEntrySourceMigrationService.SourceNode> nodes = List.of(
            new AuthorizedEntrySourceMigrationService.SourceNode(10L, 1, "198.51.100.10"),
            new AuthorizedEntrySourceMigrationService.SourceNode(20L, 2, "198.51.100.20"));

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(jdbc.queryForList(anyString(), eq(7L))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT user_id AS ownerUserId")) return List.of(Map.of("ownerUserId", 1));
            if (sql.startsWith("SELECT m.entry_node_id")) return List.of(Map.of("nodeId", 10L, "tunnelId", 1));
            if (sql.startsWith("SELECT p.id AS portId")) return List.of(port("active", "active", "203.0.113.9"));
            return List.of();
        });
        when(jdbc.queryForList(contains("retire_at AS retireAt"), eq(50L)))
                .thenReturn(List.of(Map.of("id", 5L, "forwardId", 99L, "nodeId", 10L)));
        when(jdbc.queryForList(contains("WHERE p.id=? FOR UPDATE"), eq(50L)))
                .thenReturn(List.of(Map.of("portState", "active", "grantState", "active", "flowLimitBytes", 0L, "usedBytes", 0L)));
        Forward existing = new Forward(); existing.setTunnelId(1); existing.setStatus(1);
        when(forwards.getById(99L)).thenReturn(existing);
        Forward deployed = new Forward();
        deployed.setTunnelId(2); deployed.setInPort(11000); deployed.setStatus(1);
        when(forwards.getById(100L)).thenReturn(deployed);
        when(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(1000L);
    }

    private Map<String, Object> port(String portState, String grantState, String targetHost) {
        return new HashMap<>(Map.of("portId", 50L, "grantId", 70L, "port", 11000,
                "targetHost", targetHost, "targetPort", 443, "protocolMode", "tcp",
                "portState", portState, "grantState", grantState, "flowLimitBytes", 0L, "usedBytes", 0L));
    }

    @Test
    void deploysOnNewNodeBeforeActivatingItsBinding() {
        when(forwards.createManagedForward(any(ForwardDto.class), eq(1)))
                .thenReturn(R.ok(Map.of("id", 100L)));
        AuthorizedEntrySourceMigrationService.Stage stage = migration.prepare(7L, nodes, 60);
        assertEquals(List.of(1000L), stage.createdBindings());
        verify(jdbc).update(contains("INSERT INTO authorized_entry_forward"), eq(50L), eq(100L), eq(20L),
                anyLong(), anyLong(), anyLong());
        migration.complete(stage);
        verify(jdbc).update(contains("SET retire_at=NULL"), anyLong(), eq(1000L));
    }

    @Test
    void rejectsAListenerWhosePersistedPortDoesNotMatchTheCustomerPort() {
        Forward mismatched = new Forward();
        mismatched.setTunnelId(2); mismatched.setInPort(11001); mismatched.setStatus(1);
        when(forwards.getById(100L)).thenReturn(mismatched);
        when(forwards.createManagedForward(any(ForwardDto.class), eq(1))).thenReturn(R.ok(Map.of("id", 100L)));
        when(forwards.deleteManagedForward(100L)).thenReturn(R.ok());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> migration.prepare(7L, nodes, 60));
        assertTrue(error.getMessage().contains("预部署失败"));
        verify(forwards).deleteManagedForward(100L);
        assertFalse(mockingDetails(jdbc).getInvocations().stream().anyMatch(call ->
                call.getMethod().getName().equals("update")
                        && call.getArgument(0, String.class).contains("INSERT INTO authorized_entry_forward")));
    }

    @Test
    void preservesAnAlreadyBracketedIpv6LandingAddress() {
        when(jdbc.queryForList(startsWith("SELECT p.id AS portId"), eq(7L)))
                .thenReturn(List.of(port("active", "active", "[2001:4860:4860::8844]")));
        when(forwards.createManagedForward(any(ForwardDto.class), eq(1))).thenReturn(R.ok(Map.of("id", 100L)));
        migration.prepare(7L, nodes, 60);
        org.mockito.ArgumentCaptor<ForwardDto> input = org.mockito.ArgumentCaptor.forClass(ForwardDto.class);
        verify(forwards).createManagedForward(input.capture(), eq(1));
        assertEquals("[2001:4860:4860::8844]:443", input.getValue().getRemoteAddr());
    }

    @Test
    void refusesLoopingTargetsBeforeCreatingAnyListener() {
        when(jdbc.queryForList(startsWith("SELECT p.id AS portId"), eq(7L)))
                .thenReturn(List.of(port("active", "active", "198.51.100.20")));
        assertThrows(IllegalArgumentException.class, () -> migration.prepare(7L, nodes, 60));
        verify(forwards, never()).createManagedForward(any(), anyInt());
    }

    @Test
    void refusesAnAlternateAddressOfTheNewEntryNode() {
        when(jdbc.queryForList(startsWith("SELECT p.id AS portId"), eq(7L)))
                .thenReturn(List.of(port("active", "active", "9.9.9.9")));
        when(jdbc.queryForList(startsWith("SELECT id,server_ip AS serverIp"), eq(10L), eq(20L)))
                .thenReturn(List.of(Map.of("id", 20L, "serverIp", "198.51.100.20", "ip", "8.8.8.8, 9.9.9.9")));
        assertThrows(IllegalArgumentException.class, () -> migration.prepare(7L, nodes, 60));
        verify(forwards, never()).createManagedForward(any(), anyInt());
    }

    @Test
    void sameNodeTunnelReplacementIsRejectedBeforeTouchingCustomers() {
        List<AuthorizedEntrySourceMigrationService.SourceNode> changed = List.of(
                new AuthorizedEntrySourceMigrationService.SourceNode(10L, 3, "198.51.100.10"), nodes.get(1));
        assertThrows(IllegalArgumentException.class, () -> migration.prepare(7L, changed, 60));
        verify(forwards, never()).createManagedForward(any(), anyInt());
    }

    @Test
    void detectsEquivalentIpv6SourceAddress() {
        when(jdbc.queryForList(startsWith("SELECT p.id AS portId"), eq(7L)))
                .thenReturn(List.of(port("active", "active", "[2001:4860:4860::8888]")));
        List<AuthorizedEntrySourceMigrationService.SourceNode> ipv6 = List.of(nodes.get(0),
                new AuthorizedEntrySourceMigrationService.SourceNode(20L, 2, "2001:4860:4860:0:0:0:0:8888"));
        assertThrows(IllegalArgumentException.class, () -> migration.prepare(7L, ipv6, 60));
        verify(forwards, never()).createManagedForward(any(), anyInt());
    }

    @Test
    void leavesQuotaPausedCustomersStoppedUntilTheyAreEligibleToResume() {
        when(jdbc.queryForList(startsWith("SELECT p.id AS portId"), eq(7L)))
                .thenReturn(List.of(port("quota_exhausted", "quota_exhausted", "203.0.113.9")));
        when(jdbc.queryForList(contains("WHERE p.id=? FOR UPDATE"), eq(50L)))
                .thenReturn(List.of(Map.of("portState", "quota_exhausted", "grantState", "quota_exhausted",
                        "flowLimitBytes", 200L, "usedBytes", 200L)));
        AuthorizedEntrySourceMigrationService.Stage stage = migration.prepare(7L, nodes, 60);
        assertTrue(stage.createdBindings().isEmpty());
        verify(forwards, never()).createManagedForward(any(), anyInt());
    }

    @Test
    void failedSecondDeploymentRemovesTheFirstCommittedListener() {
        List<AuthorizedEntrySourceMigrationService.SourceNode> three = List.of(nodes.get(0), nodes.get(1),
                new AuthorizedEntrySourceMigrationService.SourceNode(30L, 3, "198.51.100.30"));
        when(forwards.createManagedForward(any(ForwardDto.class), eq(1)))
                .thenReturn(R.ok(Map.of("id", 100L)), R.err("节点离线"));
        when(jdbc.queryForList(contains("FROM authorized_entry_forward WHERE id=?"), eq(1000L)))
                .thenReturn(List.of(Map.of("forwardId", 100L, "portId", 50L, "nodeId", 20L)));
        when(forwards.deleteManagedForward(100L)).thenReturn(R.ok());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> migration.prepare(7L, three, 60));
        assertTrue(error.getMessage().contains("母容灾组未切换"));
        verify(forwards).deleteManagedForward(100L);
        verify(jdbc).update(contains("DELETE FROM authorized_entry_forward"), eq(50L), eq(100L));
    }

    @Test
    void pausedGrantGetsTheNewNodeOnlyWhenItIsResumed() {
        when(jdbc.queryForList(startsWith("SELECT t.source_group_id AS groupId"), eq(70L)))
                .thenReturn(List.of(Map.of("groupId", 7L, "ttl", 60)));
        when(jdbc.queryForList(startsWith("SELECT m.entry_node_id AS nodeId,f.tunnel_id AS tunnelId,m.entry_address"), eq(7L)))
                .thenReturn(List.of(Map.of("nodeId", 10L, "tunnelId", 1, "address", "198.51.100.10"),
                        Map.of("nodeId", 20L, "tunnelId", 2, "address", "198.51.100.20")));
        when(jdbc.queryForList(startsWith("SELECT p.id AS portId"), eq(7L), eq(70L)))
                .thenReturn(List.of(port("quota_exhausted", "quota_exhausted", "203.0.113.9")));
        when(jdbc.queryForList(contains("WHERE p.id=? FOR UPDATE"), eq(50L)))
                .thenReturn(List.of(Map.of("portState", "quota_exhausted", "grantState", "quota_exhausted",
                        "flowLimitBytes", 200L, "usedBytes", 0L)));
        when(forwards.createManagedForward(any(ForwardDto.class), eq(1))).thenReturn(R.ok(Map.of("id", 100L)));
        when(forwards.pauseManagedForward(100L)).thenReturn(R.ok());
        AuthorizedEntrySourceMigrationService.Stage stage = migration.prepareGrantResume(70L);
        assertEquals(List.of(1000L), stage.createdBindings());
        org.mockito.InOrder order = inOrder(forwards, jdbc);
        order.verify(forwards).createManagedForward(any(ForwardDto.class), eq(1));
        order.verify(forwards).pauseManagedForward(100L);
        order.verify(jdbc).update(contains("INSERT INTO authorized_entry_forward"), eq(50L), eq(100L), eq(20L),
                anyLong(), anyLong(), anyLong());
    }

    @Test
    void partiallyPausedPortGetsAnActiveNewReplicaOnValidResume() {
        when(jdbc.queryForList(startsWith("SELECT t.source_group_id AS groupId"), eq(70L)))
                .thenReturn(List.of(Map.of("groupId", 7L, "ttl", 60)));
        when(jdbc.queryForList(startsWith("SELECT m.entry_node_id AS nodeId,f.tunnel_id AS tunnelId,m.entry_address"), eq(7L)))
                .thenReturn(List.of(Map.of("nodeId", 10L, "tunnelId", 1, "address", "198.51.100.10"),
                        Map.of("nodeId", 20L, "tunnelId", 2, "address", "198.51.100.20")));
        when(jdbc.queryForList(startsWith("SELECT p.id AS portId"), eq(7L), eq(70L)))
                .thenReturn(List.of(port("active", "quota_exhausted", "203.0.113.9")));
        when(jdbc.queryForList(contains("WHERE p.id=? FOR UPDATE"), eq(50L)))
                .thenReturn(List.of(Map.of("portState", "active", "grantState", "quota_exhausted",
                        "flowLimitBytes", 200L, "usedBytes", 0L)));
        when(forwards.createManagedForward(any(ForwardDto.class), eq(1))).thenReturn(R.ok(Map.of("id", 100L)));
        AuthorizedEntrySourceMigrationService.Stage stage = migration.prepareGrantResume(70L);
        assertEquals(List.of(1000L), stage.createdBindings());
        verify(forwards, never()).pauseManagedForward(100L);
    }

    @Test
    void retirementFailureIsRetriedWithoutDeletingTheBinding() {
        when(jdbc.queryForList(startsWith("SELECT b.id,b.forward_id"), anyLong()))
                .thenReturn(List.of(Map.of("id", 5L, "forwardId", 99L, "portId", 50L,
                        "nodeId", 10L, "groupId", 7L)));
        when(jdbc.queryForList(startsWith("SELECT id FROM authorized_entry_forward WHERE id=?"), eq(Long.class), eq(5L), anyLong()))
                .thenReturn(List.of(5L));
        when(jdbc.queryForObject(contains("COUNT(*) FROM authorized_entry_forward WHERE port_id="),
                eq(Integer.class), eq(50L), eq(99L))).thenReturn(1);
        when(forwards.deleteManagedForward(99L)).thenReturn(R.err("offline"), R.ok());
        migration.retireDueBindings();
        verify(jdbc, never()).update(contains("DELETE FROM authorized_entry_forward"), any(), any());
        migration.retireDueBindings();
        verify(forwards, times(2)).deleteManagedForward(99L);
        verify(jdbc).update(contains("DELETE FROM authorized_entry_forward"), eq(50L), eq(99L));
    }
}
