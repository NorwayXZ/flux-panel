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

    @Test
    void switchesAwayFromTheCurrentProviderAndPersistsHistory() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var nodes = org.mockito.Mockito.mock(com.admin.mapper.NodeMapper.class);
        var service = org.mockito.Mockito.spy(new MysteriumService(jdbc, nodes, null));
        org.mockito.Mockito.doReturn(true).when(service).enabled();
        var node = new com.admin.entity.Node(); node.setId(7L);
        org.mockito.Mockito.when(nodes.selectById(7L)).thenReturn(node);
        org.mockito.Mockito.when(jdbc.queryForList("SELECT * FROM mysterium_account WHERE node_id = ?", 7L))
                .thenReturn(List.of(Map.of("balance", 10, "suspended", 0)));
        org.mockito.Mockito.when(jdbc.queryForList("SELECT * FROM mysterium_connection WHERE node_id=? AND country=?", 7L, "US"))
                .thenReturn(List.of(Map.of("proxy_port", 47100, "provider_id", "old-provider", "excluded", "earlier-provider")));
        org.mockito.Mockito.when(jdbc.queryForList("SELECT exit_ip, provider_id FROM mysterium_connection WHERE node_id=? AND country=?", 7L, "US"))
                .thenReturn(List.of(Map.of("exit_ip", "192.0.2.1")));
        var response = new com.admin.common.dto.GostDto(); response.setMsg("OK");
        response.setData(Map.of("providerId", "new-provider", "exitIp", "192.0.2.2", "latencyMs", 15));
        org.mockito.Mockito.doReturn(true).when(service).nodeOnline(7L);
        org.mockito.Mockito.doReturn(response).when(service).sendConnect(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyMap());
        assertEquals(0, service.reconnect(7L, "US", "远程切换").getCode());
        org.mockito.Mockito.verify(service).sendConnect(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.argThat(payload ->
                List.of("earlier-provider", "old-provider").equals(payload.get("excludeProviders")) && Integer.valueOf(47100).equals(payload.get("proxyPort"))));
        org.mockito.Mockito.verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO mysterium_event"), org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("US"), org.mockito.ArgumentMatchers.contains("远程切换"), org.mockito.ArgumentMatchers.eq("192.0.2.1"), org.mockito.ArgumentMatchers.eq("192.0.2.2"), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void doesNotSwitchSuspendedPaidAccounts() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = org.mockito.Mockito.spy(new MysteriumService(jdbc, null, null));
        org.mockito.Mockito.doReturn(true).when(service).enabled();
        org.mockito.Mockito.when(jdbc.queryForList("SELECT * FROM mysterium_account WHERE node_id = ?", 7L))
                .thenReturn(List.of(Map.of("balance", 10, "suspended", 1)));
        assertTrue(service.reconnect(7L, "US").getMsg().contains("预算"));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
    }
}
