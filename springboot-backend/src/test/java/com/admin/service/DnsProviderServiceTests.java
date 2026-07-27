package com.admin.service;

import com.admin.common.utils.AESCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
