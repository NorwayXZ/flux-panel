package com.admin.service;

import com.admin.common.utils.AESCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsProviderServiceTests {
    private DnsProviderService service;

    @BeforeEach
    void setUp() {
        String secret = "dns-provider-test-secret";
        JdbcTemplate jdbcTemplate = new JdbcTemplate() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return List.of(Map.of(
                        "id", 7L,
                        "providerZoneId", "zone-424982",
                        "zoneName", "424982.xyz",
                        "apiToken", new AESCrypto(secret).encrypt("token"),
                        "enabled", 1
                ));
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
