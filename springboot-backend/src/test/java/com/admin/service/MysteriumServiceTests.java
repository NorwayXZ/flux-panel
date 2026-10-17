package com.admin.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysteriumServiceTests {

    @Test
    void countsBalanceDropsAsSpendingAndTopUpsAsNot() {
        MysteriumService.Spend spend = MysteriumService.spend(79.23, 78.26, "2026-10", 1.0, "2026-10");
        assertEquals(1.97, spend.monthSpent(), 1e-6);
        assertEquals(1.0, MysteriumService.spend(10, 90, "2026-10", 1.0, "2026-10").monthSpent(), 1e-6, "a top-up is not spending");
        assertEquals(0.5, MysteriumService.spend(10, 9.5, "2026-09", 30, "2026-10").monthSpent(), 1e-6, "a new month starts again");
        assertEquals("2026-10", MysteriumService.spend(10, 9.5, "2026-09", 30, "2026-10").month());
    }

    @Test
    void keepsTheTenMostRecentlyExcludedProviders() {
        String excluded = null;
        for (int i = 0; i < 12; i++) excluded = MysteriumService.addExcluded(excluded, "0x" + i);
        List<String> list = MysteriumService.splitExcluded(excluded);
        assertEquals(10, list.size());
        assertEquals("0x2", list.get(0));
        assertEquals("0x11", list.get(9));
        assertEquals(List.of("0x3", "0x4", "0x5", "0x6", "0x7", "0x8", "0x9", "0x10", "0x11", "0x2"),
                MysteriumService.splitExcluded(MysteriumService.addExcluded(excluded, "0x2")), "a repeat moves to the end");
    }

    @Test
    void routesToTheLocalProxyOfTheCountry() {
        assertTrue(ProxyRoutingService.validExit("mysterium:US"));
        assertFalse(ProxyRoutingService.validExit("mysterium:usa"));
        List<JSONObject> rules = ProxyRoutingService.rules(ProxyRoutingService.normalizeRules(List.of(
                Map.of("match", "domain_suffix", "values", List.of("openai.com"), "exit", "mysterium:us"))));
        assertEquals("mysterium:US", rules.get(0).getString("exit"), "country codes are upper-cased");
        assertEquals(Set.of("US", "GB"), MysteriumService.countries("mysterium", "gb", rules));

        JSONObject request = ProxyRoutingService.routerRequest("r", "mysterium", "GB", null, rules, Map.of(), "p", Map.of());
        assertEquals("mys-gb", request.getString("final"));
        assertEquals("mys-us", request.getJSONArray("rules").getJSONObject(0).getString("outbound"));

        JSONObject outbound = MysteriumService.outbound("US", 47100);
        assertEquals("http", outbound.getString("type"));
        assertEquals("127.0.0.1", outbound.getString("server"));
        assertEquals(47100, outbound.getIntValue("server_port"));
    }

    @Test
    void readsCountryPricesWithDefaults() {
        JSONObject prices = JSONObject.parseObject("{\"defaults\":{\"current\":{\"residential\":{\"wireguard\":{\"price_per_gib_human_readable\":5.03}}}},"
                + "\"per_country\":{\"US\":{\"current\":{\"residential\":{\"wireguard\":{\"price_per_gib_human_readable\":5.48}}}}}}");
        assertEquals(5.48, MysteriumService.pricePerGib(prices, "US"), 1e-9);
        assertEquals(5.03, MysteriumService.pricePerGib(prices, "JP"), 1e-9);
        assertEquals(0, MysteriumService.pricePerGib(new JSONObject(), "JP"), 1e-9);
        assertTrue(new JSONArray().isEmpty());
    }
}
