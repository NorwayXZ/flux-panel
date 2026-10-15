package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.admin.common.interceptor.ApiTokenAuthFilter;
import com.admin.common.utils.JwtUtil;
import com.admin.config.ApiTokenSchemaInitializer;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;

class ApiTokenServiceTests {
    private final JdbcTemplate jdbc;
    private final UserMapper users = mock(UserMapper.class);
    private final ApiTokenService service;
    private final User alice = new User();

    ApiTokenServiceTests() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:token" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        new ApiTokenSchemaInitializer(jdbc).initialize();
        service = new ApiTokenService(jdbc, users);
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", "api-token-test-secret-long-enough-1234567890");
        alice.setId(7L);
        alice.setUser("alice");
        alice.setRoleId(1);
        alice.setStatus(1);
        when(users.selectById(7L)).thenReturn(alice);
        signIn(alice);
    }

    private static void signIn(User user) {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", JwtUtil.generateToken(user));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @SuppressWarnings("unchecked")
    private String newToken(Integer days) {
        var result = service.create("ci", days);
        assertEquals(0, result.getCode(), result.getMsg());
        return ((Map<String, Object>) result.getData()).get("token").toString();
    }

    @Test
    void tokenActsAsItsOwnerUntilRevoked() {
        String token = newToken(0);
        assertTrue(ApiTokenService.looksLikeToken(token));
        assertSame(alice, service.authenticate(token));
        String altered = token.substring(0, token.length() - 1) + (token.endsWith("0") ? "1" : "0");
        assertNull(service.authenticate(altered));
        // Only the hash is stored.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM api_token WHERE token_hash=?", Integer.class, token));

        @SuppressWarnings("unchecked")
        var listed = (List<Map<String, Object>>) service.list().getData();
        assertEquals(1, listed.size());
        assertFalse(listed.get(0).containsKey("token_hash"));

        assertEquals(0, service.revoke(((Number) listed.get(0).get("id")).longValue()).getCode());
        assertNull(service.authenticate(token));
    }

    @Test
    void expiredTokensAndDisabledUsersAreRejected() {
        String token = newToken(1);
        jdbc.update("UPDATE api_token SET expires_at=?", System.currentTimeMillis() - 1);
        assertNull(service.authenticate(token));

        String other = newToken(0);
        alice.setStatus(0);
        assertNull(service.authenticate(other));
        alice.setStatus(1);
        alice.setExpTime(System.currentTimeMillis() - 1);
        assertNull(service.authenticate(other));
    }

    @Test
    void anotherUserCannotRevokeTheToken() {
        String token = newToken(0);
        Long id = jdbc.queryForObject("SELECT id FROM api_token", Long.class);
        User bob = new User();
        bob.setId(8L);
        bob.setUser("bob");
        bob.setRoleId(1);
        signIn(bob);
        assertNotEquals(0, service.revoke(id).getCode());
        assertSame(alice, service.authenticate(token));
    }

    @Test
    void filterSwapsTheTokenForASessionAndGuardsTokenManagement() throws Exception {
        String token = newToken(0);
        var filter = new ApiTokenAuthFilter(service);
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

        var request = new MockHttpServletRequest("POST", "/api/v1/forward/list");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        String session = seen.get().getHeader("Authorization");
        assertTrue(JwtUtil.validateToken(session));
        assertEquals(7L, JwtUtil.getUserIdFromToken(session));
        assertEquals(1, JwtUtil.getRoleIdFromToken(session));

        var keyed = new MockHttpServletRequest("POST", "/api/v1/node/list");
        keyed.addHeader("X-API-Key", token);
        seen.set(null);
        filter.doFilter(keyed, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        assertEquals(7L, JwtUtil.getUserIdFromToken(seen.get().getHeader("Authorization")));

        var mint = new MockHttpServletRequest("POST", "/api/v1/api-token/create");
        mint.addHeader("Authorization", "Bearer " + token);
        var denied = new MockHttpServletResponse();
        filter.doFilter(mint, denied, (req, res) -> fail("token management must need a login session"));
        assertEquals(403, denied.getStatus());

        var bad = new MockHttpServletRequest("POST", "/api/v1/forward/list");
        bad.addHeader("Authorization", "Bearer cn_" + "0".repeat(48));
        var rejected = new MockHttpServletResponse();
        filter.doFilter(bad, rejected, (req, res) -> fail("unknown token must not pass"));
        assertEquals(401, rejected.getStatus());

        // Ordinary login sessions are left alone.
        var normal = new MockHttpServletRequest("POST", "/api/v1/forward/list");
        normal.addHeader("Authorization", "plain.jwt.value");
        seen.set(null);
        filter.doFilter(normal, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        assertEquals("plain.jwt.value", seen.get().getHeader("Authorization"));
    }

    @Test
    void eventsReachAdministratorsAndOwnersOnly() {
        var hub = spy(new PanelEventHub());
        var admin = hub.subscribe(1, true);
        var owner = hub.subscribe(7, false);
        var stranger = hub.subscribe(9, false);
        clearInvocations(hub);

        hub.dispatch("forward.paused", 7, Map.of("forwardId", 3));
        verify(hub).send(argThat(s -> s.emitter() == admin), any());
        verify(hub).send(argThat(s -> s.emitter() == owner), any());
        verify(hub, never()).send(argThat(s -> s.emitter() == stranger), any());

        clearInvocations(hub);
        hub.dispatch("node.offline", null, Map.of("nodeId", 2));
        verify(hub, times(1)).send(any(), any(SseEmitter.SseEventBuilder.class));
        verify(hub).send(argThat(s -> s.emitter() == admin), any());
    }
}
