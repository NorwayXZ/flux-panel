package com.admin.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRoutingServiceTests {

    private static Map<String, Object> rule(String match, Object values, String exit) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("match", match);
        rule.put("values", values);
        rule.put("exit", exit);
        return rule;
    }

    @Test
    void normalizesRulesAndRejectsBadInput() {
        String json = ProxyRoutingService.normalizeRules(List.of(
                rule("domain_suffix", "*.OpenAI.com, chatgpt.com\n.claude.ai", "residential:us"),
                rule("preset", List.of("netflix"), "custom:3"),
                rule("ip_cidr", List.of("10.0.0.0/8"), "block")));
        List<JSONObject> rules = ProxyRoutingService.rules(json);
        assertEquals(List.of("openai.com", "chatgpt.com", "claude.ai"), rules.get(0).getJSONArray("values").toJavaList(String.class));
        assertEquals("residential:US", rules.get(0).getString("exit"));
        assertNull(ProxyRoutingService.normalizeRules(List.of()));

        assertThrows(IllegalArgumentException.class, () -> ProxyRoutingService.normalizeRules(List.of(rule("process", "x", "direct"))));
        assertThrows(IllegalArgumentException.class, () -> ProxyRoutingService.normalizeRules(List.of(rule("preset", "unknown", "direct"))));
        assertThrows(IllegalArgumentException.class, () -> ProxyRoutingService.normalizeRules(List.of(rule("domain", "bad domain!", "direct"))));
        assertThrows(IllegalArgumentException.class, () -> ProxyRoutingService.normalizeRules(List.of(rule("domain", "a.com", "elsewhere"))));
        assertThrows(IllegalArgumentException.class, () -> ProxyRoutingService.normalizeRules(List.of(rule("domain", "", "direct"))));
    }

    @Test
    void collectsTheCountriesAndExitsTheRulesNeed() {
        List<JSONObject> rules = ProxyRoutingService.rules(ProxyRoutingService.normalizeRules(List.of(
                rule("domain", "a.com", "residential:jp"), rule("domain", "b.com", "custom:9"))));
        Set<String> countries = ProxyRoutingService.residentialCountries("residential", "US", rules);
        assertEquals(Set.of("JP", "US"), countries);
        assertEquals(",JP,US,", ProxyRoutingService.routeCountriesColumn(countries));
        assertEquals(Set.of(4L, 9L), ProxyRoutingService.customExitIds("custom", 4L, rules));
    }

    @Test
    void buildsTheRouterCommand() {
        List<JSONObject> rules = ProxyRoutingService.rules(ProxyRoutingService.normalizeRules(List.of(
                rule("preset", List.of("openai", "claude"), "residential:US"),
                rule("domain_keyword", "netflix", "custom:5"),
                rule("ip_cidr", "10.0.0.0/8", "block"))));
        Map<String, Integer> ports = new TreeMap<>(Map.of("US", 45123));
        Map<Long, JSONObject> customs = new LinkedHashMap<>();
        customs.put(5L, JSONObject.parseObject("{\"type\":\"trojan\",\"server\":\"t.example.com\",\"server_port\":443,\"password\":\"p\",\"detour\":\"x\"}"));

        JSONObject request = ProxyRoutingService.routerRequest("private-proxy-abc-router", "node", null, null, rules, ports, "abc", customs);

        assertEquals("direct", request.getString("final"));
        JSONArray outbounds = request.getJSONArray("outbounds");
        assertEquals("res-us", outbounds.getJSONObject(0).getString("tag"));
        assertEquals(45123, outbounds.getJSONObject(0).getIntValue("server_port"));
        assertEquals("custom-5", outbounds.getJSONObject(1).getString("tag"));
        assertTrue(!outbounds.getJSONObject(1).containsKey("detour"), "custom exits cannot chain");
        JSONArray routerRules = request.getJSONArray("rules");
        assertTrue(routerRules.getJSONObject(0).getJSONArray("domain_suffix").contains("chatgpt.com"));
        assertTrue(routerRules.getJSONObject(0).getJSONArray("domain_suffix").contains("claude.ai"));
        assertEquals("res-us", routerRules.getJSONObject(0).getString("outbound"));
        assertEquals("custom-5", routerRules.getJSONObject(1).getString("outbound"));
        assertEquals("reject", routerRules.getJSONObject(2).getString("action"));

        assertEquals("custom-5", ProxyRoutingService.routerRequest("r", "custom", null, 5L, rules, ports, "abc", customs).getString("final"));
        assertEquals("res-us", ProxyRoutingService.routerRequest("r", "residential", "US", null, rules, ports, "abc", customs).getString("final"));
    }
}
