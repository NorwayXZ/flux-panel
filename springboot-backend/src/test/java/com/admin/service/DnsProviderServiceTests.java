package com.admin.service;

import com.admin.common.utils.AESCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.h2.jdbcx.JdbcDataSource;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DnsProviderServiceTests {
    private DnsProviderService service;

    @BeforeEach
    void setUp() {
        String secret = "dns-provider-test-secret";
        JdbcTemplate jdbcTemplate = new JdbcTemplate() {
            private List<Map<String, Object>> rows() {
                return List.of(Map.of(
                        "id", 7L,
                        "providerZoneId", "zone-424982",
                        "zoneName", "424982.xyz",
                        "apiToken", new AESCrypto(secret).encrypt("token"),
                        "enabled", 1
                ));
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql) {
                return rows();
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return rows();
            }
        };
        service = new DnsProviderService(jdbcTemplate, new RestTemplate());
        ReflectionTestUtils.setField(service, "encryptionSecret", secret);
    }

    @Test
    void expandsHostLabelInsideSelectedZone() {
        assertEquals("glglg.424982.xyz", service.normalizeDomain(7L, "GLGLG"));
    }

    @Test
    void keepsFullDomainInsideSelectedZone() {
        assertEquals("api.dev.424982.xyz", service.normalizeDomain(7L, "api.dev.424982.xyz."));
    }

    @Test
    void findsAnAlreadyImportedZoneForSubdomain() {
        DnsProviderService.ZoneAccess zone = service.findOrImportZoneForDomain("api.dev.424982.xyz.");

        assertEquals(7L, zone.id());
        assertEquals("zone-424982", zone.providerZoneId());
        assertEquals("424982.xyz", zone.zoneName());
        assertEquals("token", zone.token());
    }

    @Test
    void rejectsDomainOutsideSelectedZone() {
        assertThrows(IllegalArgumentException.class, () -> service.normalizeDomain(7L, "api.example.com"));
    }

    @Test
    void acceptsAcmeChallengeOnlyInsideSelectedZone() {
        DnsProviderService.ZoneAccess zone = new DnsProviderService.ZoneAccess(
                7L, "zone-424982", "424982.xyz", "token");
        assertEquals("_acme-challenge.app.424982.xyz",
                service.normalizeChallengeName(zone, "_acme-challenge.app.424982.xyz."));
        assertThrows(IllegalArgumentException.class,
                () -> service.normalizeChallengeName(zone, "_acme-challenge.example.com"));
    }

    @Test
    void readsQuotedTxtAnswersFromDnsOverHttps() {
        DnsProviderService resolver = resolverReturning(
                "{\"Status\":0,\"Answer\":[{\"type\":16,\"data\":\"\\\"challenge-value\\\"\"}]}");

        assertTrue(resolver.txtRecordVisible("https://dns.example/resolve",
                "_acme-challenge.424982.xyz", "challenge-value"));
        assertFalse(resolver.txtRecordVisible("https://dns.example/resolve",
                "_acme-challenge.424982.xyz", "different-value"));
    }

    @Test
    void requiresStablePropagationBeforeReturning() {
        DnsProviderService resolver = resolverReturning(
                "{\"Status\":0,\"Answer\":[{\"type\":16,\"data\":\"\\\"challenge-value\\\"\"}]}");

        assertDoesNotThrow(() -> resolver.waitForDnsChallengePropagation(
                "_acme-challenge.424982.xyz", "challenge-value",
                Duration.ofMillis(100), Duration.ofMillis(1), 2));
    }

    @Test
    void reportsPropagationTimeoutInsteadOfTokenPermissions() {
        DnsProviderService resolver = resolverReturning("{\"Status\":3}");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> resolver.waitForDnsChallengePropagation(
                        "_acme-challenge.424982.xyz", "challenge-value",
                        Duration.ofMillis(10), Duration.ofMillis(1), 1));

        assertTrue(error.getMessage().contains("DNS 同步超时"));
        assertFalse(error.getMessage().contains("Token"));
    }

    @Test
    void unchangedPoolDoesNotRepeatedlyCallCloudflare() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RestTemplate rest = mock(RestTemplate.class);
        DnsProviderService dns = spy(new DnsProviderService(jdbc, rest));
        doReturn(new DnsProviderService.ZoneAccess(7L, "zone", "example.com", "token")).when(dns).loadZoneAccess(7L);
        List<Map<String, Object>> state = new ArrayList<>();
        when(jdbc.queryForList(anyString(), eq(3L))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("FROM cross_entry_pool_sync_state")) return state;
            if (sql.contains("FROM cross_entry_dns_record")) return List.of(Map.of("id", 9L, "memberId", 2L,
                    "providerRecordId", "backup", "content", "1.1.1.1"));
            return List.of();
        });
        when(jdbc.update(startsWith("INSERT INTO cross_entry_pool_sync_state"), any(), any(), any())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.startsWith("INSERT INTO cross_entry_pool_sync_state")) {
                state.clear();
                state.add(new HashMap<>(Map.of("desiredSignature", call.getArgument(2), "lastVerifiedAt", call.getArgument(3))));
            }
            return 1;
        });
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"result\":["
                        + "{\"id\":\"anchor\",\"name\":\"a.example.com\",\"type\":\"A\",\"content\":\"8.8.8.8\",\"ttl\":60,\"proxied\":false},"
                        + "{\"id\":\"backup\",\"name\":\"a.example.com\",\"type\":\"A\",\"content\":\"1.1.1.1\",\"ttl\":60,\"proxied\":false}]}"));
        List<DnsProviderService.CrossEntryDnsTarget> targets = List.of(
                new DnsProviderService.CrossEntryDnsTarget(1, "8.8.8.8"),
                new DnsProviderService.CrossEntryDnsTarget(2, "1.1.1.1"));
        dns.syncCrossEntryActiveRecords(7L, "anchor", "a.example.com", "A", 60, 3L, targets);
        dns.syncCrossEntryActiveRecords(7L, "anchor", "a.example.com", "A", 60, 3L, targets);
        verify(rest, times(1)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(rest, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
        dns.syncCrossEntryActiveRecords(7L, "anchor", "a.example.com", "A", 60, 3L, targets, true);
        verify(rest, times(2)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void unexpectedProviderRecordBlocksWritesAndRequiresInspection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RestTemplate rest = mock(RestTemplate.class);
        DnsProviderService dns = spy(new DnsProviderService(jdbc, rest));
        doReturn(new DnsProviderService.ZoneAccess(7L, "zone", "example.com", "token")).when(dns).loadZoneAccess(7L);
        when(jdbc.queryForList(anyString(), eq(3L))).thenReturn(List.of());
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"result\":["
                        + "{\"id\":\"anchor\",\"name\":\"a.example.com\",\"type\":\"A\",\"content\":\"8.8.8.8\",\"ttl\":60},"
                        + "{\"id\":\"other\",\"name\":\"a.example.com\",\"type\":\"A\",\"content\":\"9.9.9.9\",\"ttl\":60}]}"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> dns.syncCrossEntryActiveRecords(
                7L, "anchor", "a.example.com", "A", 60, 3L,
                List.of(new DnsProviderService.CrossEntryDnsTarget(1, "1.1.1.1"))));
        assertTrue(error.getMessage().contains("同名记录"));
        verify(rest, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void uncertainPoolCreateRemainsDurableAndIsRecoveredByItsUniqueTag() {
        JdbcTemplate jdbc = poolDatabase();
        RestTemplate rest = mock(RestTemplate.class);
        DnsProviderService dns = poolService(jdbc, rest);
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(poolRecords(null)));
        when(rest.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("response lost"));
        List<DnsProviderService.CrossEntryDnsTarget> targets = List.of(
                new DnsProviderService.CrossEntryDnsTarget(1L, "8.8.8.8"),
                new DnsProviderService.CrossEntryDnsTarget(2L, "1.1.1.1"));
        assertThrows(IllegalStateException.class,
                () -> dns.syncCrossEntryActiveRecords(7L, "anchor", "a.example.com", "A", 60, 3L, targets));
        String tag = jdbc.queryForObject("SELECT tag FROM cross_entry_pool_create_pending WHERE group_id=3", String.class);
        assertTrue(tag.startsWith("fluxop:"));
        org.mockito.ArgumentCaptor<HttpEntity> request = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).exchange(anyString(), eq(HttpMethod.POST), request.capture(), eq(String.class));
        assertEquals(List.of(tag), ((Map<?, ?>) request.getValue().getBody()).get("tags"));
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(poolRecords(tag)));
        dns.syncCrossEntryActiveRecords(7L, "anchor", "a.example.com", "A", 60, 3L, targets);
        assertEquals("created", jdbc.queryForObject("SELECT provider_record_id FROM cross_entry_dns_record WHERE group_id=3 AND member_id=2", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_pool_create_pending", Integer.class));
        verify(rest, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void explicitCloudflareRejectionDoesNotLeaveAnUncertainPoolCreate() {
        JdbcTemplate jdbc = poolDatabase();
        RestTemplate rest = mock(RestTemplate.class);
        DnsProviderService dns = poolService(jdbc, rest);
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(poolRecords(null)));
        when(rest.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "denied", null, null, null));
        List<DnsProviderService.CrossEntryDnsTarget> targets = List.of(
                new DnsProviderService.CrossEntryDnsTarget(1L, "8.8.8.8"),
                new DnsProviderService.CrossEntryDnsTarget(2L, "1.1.1.1"));
        assertThrows(IllegalStateException.class,
                () -> dns.syncCrossEntryActiveRecords(7L, "anchor", "a.example.com", "A", 60, 3L, targets));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_pool_create_pending", Integer.class));
    }

    @Test
    void promotingBackupDeletesOwnedDuplicateBeforeUpdatingAnchorAndRetriesAfterFailure() {
        JdbcTemplate jdbc = poolDatabase();
        jdbc.update("INSERT INTO cross_entry_dns_record(group_id,member_id,provider_record_id,content,created_time,updated_time) "
                + "VALUES(3,2,'backup','1.1.1.1',0,0)");
        RestTemplate rest = mock(RestTemplate.class);
        DnsProviderService dns = poolService(jdbc, rest);
        String withBackup = "{\"success\":true,\"result\":["
                + "{\"id\":\"anchor\",\"name\":\"a.example.com\",\"type\":\"A\",\"content\":\"8.8.8.8\",\"ttl\":60},"
                + "{\"id\":\"backup\",\"name\":\"a.example.com\",\"type\":\"A\",\"content\":\"1.1.1.1\",\"ttl\":60}]}";
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(withBackup), ResponseEntity.ok(poolRecords(null)));
        when(rest.exchange(contains("/backup"), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));
        when(rest.exchange(contains("/anchor"), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("response lost"))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));
        List<DnsProviderService.CrossEntryDnsTarget> target = List.of(new DnsProviderService.CrossEntryDnsTarget(2L, "1.1.1.1"));
        assertThrows(IllegalStateException.class, () -> dns.syncCrossEntryActiveRecords(7L, "anchor", "a.example.com", "A", 60, 3L, target));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_dns_record WHERE group_id=3", Integer.class));
        dns.syncCrossEntryActiveRecords(7L, "anchor", "a.example.com", "A", 60, 3L, target);
        var order = inOrder(rest);
        order.verify(rest).exchange(contains("/backup"), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class));
        order.verify(rest).exchange(contains("/anchor"), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(String.class));
        verify(rest, times(1)).exchange(contains("/backup"), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void poolOwnershipSurvivesOuterTransactionRollback() {
        JdbcTemplate jdbc = poolDatabase();
        RestTemplate rest = mock(RestTemplate.class);
        DnsProviderService dns = poolService(jdbc, rest);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        ProxyFactory proxy = new ProxyFactory(dns);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        DnsProviderService transactional = (DnsProviderService) proxy.getProxy();
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(poolRecords(null)));
        when(rest.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true,\"result\":{\"id\":\"created\"}}"));
        new TransactionTemplate(transactions).execute(status -> {
            transactional.syncCrossEntryActiveRecords(7L, "anchor", "a.example.com", "A", 60, 3L, List.of(
                    new DnsProviderService.CrossEntryDnsTarget(1L, "8.8.8.8"),
                    new DnsProviderService.CrossEntryDnsTarget(2L, "1.1.1.1")));
            status.setRollbackOnly();
            return null;
        });
        assertEquals("created", jdbc.queryForObject("SELECT provider_record_id FROM cross_entry_dns_record WHERE group_id=3 AND member_id=2", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cross_entry_pool_create_pending", Integer.class));
    }

    @Test
    void administratorCanRecoverAbsentOrTaggedPendingRecordWithoutCreatingAnother() {
        JdbcTemplate jdbc = poolDatabase();
        RestTemplate rest = mock(RestTemplate.class);
        DnsProviderService dns = poolService(jdbc, rest);
        jdbc.update("INSERT INTO cross_entry_pool_create_pending VALUES(3,2,'1.1.1.1','fluxop:one',0)");
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(poolRecords(null)));
        assertThrows(IllegalStateException.class, () -> dns.resolvePendingPoolCreate(7L, "a.example.com", "A", 3L, "fluxop:old", true));
        assertThrows(IllegalStateException.class, () -> dns.resolvePendingPoolCreate(7L, "a.example.com", "A", 3L, "fluxop:one", false));
        assertFalse(dns.resolvePendingPoolCreate(7L, "a.example.com", "A", 3L, "fluxop:one", true));
        jdbc.update("INSERT INTO cross_entry_pool_create_pending VALUES(3,2,'1.1.1.1','fluxop:two',0)");
        when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(poolRecords("fluxop:two")));
        assertTrue(dns.resolvePendingPoolCreate(7L, "a.example.com", "A", 3L, "fluxop:two", true));
        assertEquals("created", jdbc.queryForObject("SELECT provider_record_id FROM cross_entry_dns_record WHERE group_id=3 AND member_id=2", String.class));
        verify(rest, never()).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    private JdbcTemplate poolDatabase() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:dnspool" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE cross_entry_pool_sync_state (group_id BIGINT PRIMARY KEY,desired_signature CLOB,last_verified_at BIGINT)");
        jdbc.execute("CREATE TABLE cross_entry_pool_create_pending (group_id BIGINT PRIMARY KEY,member_id BIGINT,content VARCHAR(255),tag VARCHAR(128),started_at BIGINT)");
        jdbc.execute("CREATE TABLE cross_entry_dns_record (id BIGINT AUTO_INCREMENT PRIMARY KEY,group_id BIGINT,member_id BIGINT,"
                + "provider_record_id VARCHAR(64),content VARCHAR(255),created_time BIGINT,updated_time BIGINT, UNIQUE(group_id,member_id))");
        jdbc.execute("CREATE TABLE dns_managed_record (zone_id BIGINT,provider_record_id VARCHAR(64),content VARCHAR(255),ttl INT,"
                + "owner_type VARCHAR(24),owner_id BIGINT,status VARCHAR(24),last_error VARCHAR(500),updated_time BIGINT)");
        jdbc.update("INSERT INTO dns_managed_record(zone_id,provider_record_id,content,ttl,owner_type,owner_id) VALUES(7,'anchor','8.8.8.8',60,'cross_entry',3)");
        return jdbc;
    }

    private DnsProviderService poolService(JdbcTemplate jdbc, RestTemplate rest) {
        DnsProviderService dns = spy(new DnsProviderService(jdbc, rest));
        doReturn(new DnsProviderService.ZoneAccess(7L, "zone", "example.com", "token")).when(dns).loadZoneAccess(7L);
        ReflectionTestUtils.setField(dns, "transactionManager", new DataSourceTransactionManager(jdbc.getDataSource()));
        return dns;
    }

    private String poolRecords(String tag) {
        String backup = tag == null ? "" : ", {\"id\":\"created\",\"name\":\"a.example.com\",\"type\":\"A\","
                + "\"content\":\"1.1.1.1\",\"ttl\":60,\"proxied\":false,\"tags\":[\"" + tag + "\"]}";
        return "{\"success\":true,\"result\":[{\"id\":\"anchor\",\"name\":\"a.example.com\",\"type\":\"A\","
                + "\"content\":\"8.8.8.8\",\"ttl\":60,\"proxied\":false}" + backup + "]}";
    }

    private DnsProviderService resolverReturning(String body) {
        RestTemplate restTemplate = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> exchange(URI url, HttpMethod method,
                                                  HttpEntity<?> requestEntity, Class<T> responseType) {
                return ResponseEntity.ok(responseType.cast(body));
            }
        };
        return new DnsProviderService(new JdbcTemplate(), restTemplate);
    }
}
