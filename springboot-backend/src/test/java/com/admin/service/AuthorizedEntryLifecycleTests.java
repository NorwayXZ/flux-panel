package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.AuthorizedEntryForward;
import com.admin.entity.AuthorizedEntryGrant;
import com.admin.entity.AuthorizedEntryPort;
import com.admin.entity.AuthorizedEntryTemplate;
import com.admin.mapper.AuthorizedEntryForwardMapper;
import com.admin.mapper.AuthorizedEntryGrantMapper;
import com.admin.mapper.AuthorizedEntryPortMapper;
import com.admin.mapper.AuthorizedEntryTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthorizedEntryLifecycleTests {
    private final AuthorizedEntryService service = new AuthorizedEntryService();
    private final AuthorizedEntryGrantMapper grants = mock(AuthorizedEntryGrantMapper.class);
    private final AuthorizedEntryPortMapper ports = mock(AuthorizedEntryPortMapper.class);
    private final AuthorizedEntryTemplateMapper templates = mock(AuthorizedEntryTemplateMapper.class);
    private final AuthorizedEntryForwardMapper bindings = mock(AuthorizedEntryForwardMapper.class);
    private final ForwardService forwards = mock(ForwardService.class);
    private final long now = Instant.parse("2026-10-08T00:00:00Z").toEpochMilli();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "grantMapper", grants);
        ReflectionTestUtils.setField(service, "portMapper", ports);
        ReflectionTestUtils.setField(service, "templateMapper", templates);
        ReflectionTestUtils.setField(service, "forwardMapper", bindings);
        ReflectionTestUtils.setField(service, "forwardService", forwards);
        ReflectionTestUtils.setField(service, "grantLocks", new AuthorizedEntryGrantLocks());
        ReflectionTestUtils.setField(service, "migrationLocks", new CrossEntryGroupMutationLocks());
        ReflectionTestUtils.setField(service, "sourceMigrationService", mock(AuthorizedEntrySourceMigrationService.class));
        AuthorizedEntryTemplate template = new AuthorizedEntryTemplate();
        template.setId(1L); template.setSourceGroupId(7L);
        when(templates.selectById(any())).thenReturn(template);
        when(ports.selectList(any())).thenReturn(List.of());
    }

    private AuthorizedEntryGrant grant(String state) {
        AuthorizedEntryGrant grant = new AuthorizedEntryGrant();
        grant.setId(1L);
        grant.setTemplateId(1L);
        grant.setState(state);
        grant.setFlowResetDay(8);
        grant.setUsedBytes(200L);
        grant.setFlowLimitBytes(200L);
        grant.setLastResetAt(1L);
        return grant;
    }

    @Test
    void exhaustedGrantResetsAndResumesAtNewCycle() {
        AuthorizedEntryGrant grant = grant("quota_exhausted");
        ReflectionTestUtils.invokeMethod(service, "reconcile", grant, now);
        assertEquals(0L, grant.getUsedBytes());
        assertEquals("active", grant.getState());
        assertTrue(grant.getLastResetAt() > 1L);
    }

    @Test
    void monthlyResetNeverResumesAdminPausedOrExpiredGrants() {
        AuthorizedEntryGrant paused = grant("admin_paused");
        AuthorizedEntryGrant expired = grant("quota_exhausted");
        expired.setExpiresAt(now - 1);
        ReflectionTestUtils.invokeMethod(service, "reconcile", paused, now);
        ReflectionTestUtils.invokeMethod(service, "reconcile", expired, now);
        assertEquals(0L, paused.getUsedBytes());
        assertEquals("admin_paused", paused.getState());
        assertEquals("expired", expired.getState());
        verifyNoInteractions(forwards);
    }

    @Test
    void failedResumeIsRetriedWithoutWaitingForAnotherMonthlyBoundary() {
        AuthorizedEntryGrant grant = grant("quota_exhausted");
        AuthorizedEntryPort port = new AuthorizedEntryPort();
        port.setId(2L); port.setPort(10000);
        AuthorizedEntryForward binding = new AuthorizedEntryForward();
        binding.setForwardId(3L);
        when(ports.selectList(any())).thenReturn(List.of(port));
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        when(forwards.resumeManagedForward(3L)).thenReturn(R.err("offline"), R.ok());
        ReflectionTestUtils.invokeMethod(service, "reconcile", grant, now);
        assertEquals("resume_pending", grant.getState());
        ReflectionTestUtils.invokeMethod(service, "reconcile", grant, now + 60_000);
        assertEquals("active", grant.getState());
        verify(forwards, times(2)).resumeManagedForward(3L);
    }

    @Test
    void noResetDayKeepsExhaustedGrantPaused() {
        AuthorizedEntryGrant grant = grant("quota_exhausted");
        grant.setFlowResetDay(0);
        ReflectionTestUtils.invokeMethod(service, "reconcile", grant, now);
        assertEquals(200L, grant.getUsedBytes());
        assertEquals("quota_exhausted", grant.getState());
    }

    @Test
    void oneBrokenGrantDoesNotPreventOtherCustomerResets() {
        AuthorizedEntryGrant broken = grant("quota_exhausted");
        AuthorizedEntryGrant healthy = grant("quota_exhausted"); healthy.setId(2L);
        when(grants.selectList(any())).thenReturn(List.of(broken, healthy));
        when(grants.selectById(1L)).thenThrow(new IllegalStateException("temporary database failure"));
        when(grants.selectById(2L)).thenReturn(healthy);
        service.reconcileGrants();
        assertEquals(0L, healthy.getUsedBytes());
        assertEquals("active", healthy.getState());
    }

    @Test
    void rejectsLandingOnTheSourceEntryAddress() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        when(jdbc.queryForList("SELECT id,name,server_ip AS serverIp,ip FROM node"))
                .thenReturn(List.of(Map.of("id", 9L, "name", "entry", "serverIp", "8.8.8.8", "ip", "8.8.8.8")));
        assertEquals("entry", ReflectionTestUtils.invokeMethod(service, "entryNodeAddressName",
                "8.8.8.8:10000", List.of(9L)));
        when(jdbc.queryForList("SELECT id,name,server_ip AS serverIp,ip FROM node"))
                .thenReturn(List.of(Map.of("id", 9L, "name", "entry6", "serverIp", "2001:4860:4860::8888")));
        assertEquals("entry6", ReflectionTestUtils.invokeMethod(service, "entryNodeAddressName",
                "[2001:4860:4860:0:0:0:0:8888]:10000", List.of(9L)));
    }
}
