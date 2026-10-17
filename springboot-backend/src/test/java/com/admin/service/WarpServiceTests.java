package com.admin.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarpServiceTests {

    @Test
    void readsTheRegistrationAnswer() {
        WarpService.Account account = WarpService.parseRegistration("{\"id\":\"abc\",\"config\":{\"client_id\":\"t73b\","
                + "\"peers\":[{\"public_key\":\"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=\",\"endpoint\":{\"host\":\"engage.cloudflareclient.com:2408\"}}],"
                + "\"interface\":{\"addresses\":{\"v4\":\"172.16.0.2\",\"v6\":\"2606:4700:110:89fe::1\"}}}}");
        assertEquals("172.16.0.2", account.addressV4);
        assertEquals("2606:4700:110:89fe::1", account.addressV6);
        assertEquals(List.of(183, 189, 219), account.reserved);
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", account.peerPublicKey);
    }

    @Test
    void generatesRawCurve25519Keys() {
        byte[][] keys = WarpService.keyPair();
        assertEquals(32, keys[0].length);
        assertEquals(32, keys[1].length);
    }

    @Test
    void routesToWarpAsDefaultRuleAndGroupMember() {
        assertTrue(ProxyRoutingService.validExit("warp"));
        List<JSONObject> rules = ProxyRoutingService.rules(ProxyRoutingService.normalizeRules(List.of(
                Map.of("match", "domain_suffix", "values", List.of("chatgpt.com"), "exit", "warp"))));
        JSONObject byRule = ProxyRoutingService.routerRequest("r", "node", null, null, rules, Map.of(), "p", Map.of());
        assertEquals("warp", byRule.getJSONArray("rules").getJSONObject(0).getString("outbound"));
        assertTrue(ProxyRoutingService.usesWarp(byRule));

        JSONObject byDefault = ProxyRoutingService.routerRequest("r", "warp", null, null, List.of(), Map.of(), "p", Map.of());
        assertEquals("warp", byDefault.getString("final"));
        assertTrue(ProxyRoutingService.usesWarp(byDefault));

        Map<Long, JSONObject> customs = new LinkedHashMap<>();
        customs.put(9L, JSONObject.parseObject("{\"type\":\"urltest\",\"outbounds\":[\"custom-1\",\"warp\"]}"));
        JSONObject byGroup = ProxyRoutingService.routerRequest("r", "custom", null, 9L, List.of(), Map.of(), "p", customs);
        assertTrue(ProxyRoutingService.usesWarp(byGroup));
        assertFalse(ProxyRoutingService.usesWarp(ProxyRoutingService.routerRequest("r", "node", null, null, List.of(), Map.of(), "p", Map.of())));

        ProxyRoutingService.attachWarp(byRule, JSONObject.parseObject("{\"type\":\"wireguard\",\"mtu\":1280,\"private_key\":\"k\"}"));
        JSONArray endpoints = byRule.getJSONArray("endpoints");
        assertEquals("warp", endpoints.getJSONObject(0).getString("tag"));
        assertEquals("wireguard", endpoints.getJSONObject(0).getString("type"));
    }
}
