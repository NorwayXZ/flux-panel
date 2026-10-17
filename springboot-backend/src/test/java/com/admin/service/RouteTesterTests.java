package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTesterTests {

    private static List<JSONObject> rules(List<Map<String, Object>> input) {
        return ProxyRoutingService.rules(ProxyRoutingService.normalizeRules(input));
    }

    private static Map<String, Object> test(List<JSONObject> rules, String target) {
        return RouteTester.test(rules, RouteTester.parse(target, "tcp"), host -> List.of("104.20.26.136"));
    }

    @Test
    void parsesDestinations() {
        RouteTester.Target url = RouteTester.parse("https://Chat.OpenAI.com/c/123", "tcp");
        assertEquals("chat.openai.com", url.host);
        assertEquals(443, url.port);
        assertEquals(8443, RouteTester.parse("example.com:8443", "tcp").port);
        assertEquals(80, RouteTester.parse("http://example.com", "tcp").port);
        RouteTester.Target v6 = RouteTester.parse("[2001:db8::1]:53", "udp");
        assertTrue(v6.address);
        assertEquals(53, v6.port);
        assertEquals("udp", v6.network);
        assertThrows(IllegalArgumentException.class, () -> RouteTester.parse("not a host", "tcp"));
    }

    @Test
    void findsTheFirstRuleThatMatches() {
        List<JSONObject> rules = rules(List.of(
                Map.of("match", "domain_keyword", "values", List.of("netflix"), "exit", "custom:1"),
                Map.of("match", "domain_suffix", "values", List.of("openai.com"), "exit", "residential:US"),
                Map.of("match", "domain_suffix", "values", List.of("chat.openai.com"), "exit", "block"),
                Map.of("match", "port", "values", List.of("8000-9000"), "exit", "direct"),
                Map.of("match", "preset", "values", List.of("claude"), "exit", "custom:2")));

        Map<String, Object> openai = test(rules, "chat.openai.com");
        assertEquals(1, openai.get("index"), "the earlier, wider rule wins");
        assertEquals("域名后缀", openai.get("field"));
        assertEquals("openai.com", openai.get("value"));
        assertEquals(0, test(rules, "www.netflix.com").get("index"));
        assertEquals(-1, test(rules, "notopenai.com").get("index"), "suffixes match whole labels");
        assertEquals(3, test(rules, "example.com:8443").get("index"));
        assertEquals(4, test(rules, "claude.ai").get("index"));
        assertEquals(-1, test(rules, "example.org").get("index"));
    }

    @Test
    void ipRulesSeeAddressesUnlessAskedToResolve() {
        List<JSONObject> plain = rules(List.of(Map.of("match", "ip_cidr", "values", List.of("104.20.0.0/16"), "exit", "block")));
        Map<String, Object> domain = test(plain, "example.org");
        assertEquals(-1, domain.get("index"));
        assertTrue(domain.get("notes").toString().contains("先解析域名"));
        assertEquals(0, test(plain, "104.20.26.136").get("index"));

        List<JSONObject> resolving = rules(List.of(
                Map.of("match", "domain", "values", List.of("other.com"), "exit", "direct"),
                Map.of("match", "ip_cidr", "values", List.of("104.20.0.0/16"), "exit", "block", "resolve", true),
                Map.of("match", "geoip", "values", List.of("private"), "exit", "direct")));
        Map<String, Object> resolved = test(resolving, "example.org");
        assertEquals(1, resolved.get("index"));
        assertEquals(List.of("104.20.26.136"), resolved.get("resolved"));
        assertEquals(2, test(resolving, "192.168.1.10").get("index"));

        Map<String, Object> failed = RouteTester.test(resolving, RouteTester.parse("nope.invalid", "tcp"), host -> {
            throw new IllegalStateException("域名不存在");
        });
        assertEquals(-2, failed.get("index"));
    }

    @Test
    void checksCidrContainment() {
        assertTrue(RouteTester.contains("10.0.0.0/8", "10.200.1.1"));
        assertFalse(RouteTester.contains("10.0.0.0/8", "11.0.0.1"));
        assertTrue(RouteTester.contains("2001:db8::/32", "2001:db8:1::1"));
        assertFalse(RouteTester.contains("2001:db8::/32", "10.0.0.1"));
        assertTrue(RouteTester.contains("1.2.3.4/32", "1.2.3.4"));
    }
}
