package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyProviderServiceTests {

    @Test
    void buildsSoaxUsernamesAsDocumented() {
        JSONObject config = JSONObject.parseObject("{\"packageKey\":\"KEY123\"}");
        JSONObject mobile = ProxyProviderService.build("soax", "mobile", 10, config, "US", "t_mobile", "ab12cd34");
        assertEquals("http", mobile.getString("type"));
        assertEquals("proxy.soax.com", mobile.getString("server"));
        assertEquals(1337, mobile.getIntValue("server_port"));
        assertEquals("network-mob-country-us-isp-t_mobile-session-ab12cd34-rotate-timed_10m", mobile.getString("username"));
        assertEquals("KEY123", mobile.getString("password"));

        JSONObject residential = ProxyProviderService.build("soax", "residential", 0, config, "DE", null, "x1");
        assertEquals("country-de-session-x1", residential.getString("username"), "no network rule and no rotation when holding the IP");
    }

    @Test
    void buildsOxylabsUsernamesAsDocumented() {
        JSONObject config = JSONObject.parseObject("{\"username\":\"alice\",\"password\":\"pw\"}");
        JSONObject outbound = ProxyProviderService.build("oxylabs", "mobile", 30, config, "fr", null, "s9");
        assertEquals("pr.oxylabs.io", outbound.getString("server"));
        assertEquals(7777, outbound.getIntValue("server_port"));
        assertEquals("customer-alice-cc-FR-sessid-s9-sesstime-30", outbound.getString("username"));
        assertEquals("customer-alice-cc-FR-sessid-s9-sesstime-1440",
                ProxyProviderService.build("oxylabs", "mobile", 0, config, "FR", null, "s9").getString("username"), "0 means hold as long as allowed");
    }

    @Test
    void fillsCustomTemplates() {
        JSONObject config = JSONObject.parseObject("{\"host\":\"gw.example.com\",\"port\":9000,\"protocol\":\"socks5\","
                + "\"usernameTemplate\":\"user-{country}-{carrier}-sid-{session}\",\"passwordTemplate\":\"pass_country-{COUNTRY}_lifetime-{minutes}m\"}");
        JSONObject outbound = ProxyProviderService.build("custom", "mobile", 15, config, "JP", "docomo", "abc");
        assertEquals("socks", outbound.getString("type"));
        assertEquals("5", outbound.getString("version"));
        assertEquals("user-jp-docomo-sid-abc", outbound.getString("username"));
        assertEquals("pass_country-JP_lifetime-15m", outbound.getString("password"));
        assertEquals("s1", ProxyProviderService.build("custom", "mobile", 15, config, "JP", null, "bad session!").getString("username")
                .replaceAll(".*sid-", ""), "an invalid session falls back to a safe value");
    }

    @Test
    void masksSecretsAndReadsNestedUsage() {
        assertEquals("ab****yz", ProxyProviderService.mask("abcdefxyz"));
        assertEquals("****", ProxyProviderService.mask("abc"));
        JSONObject json = JSONObject.parseObject("{\"package\":{\"traffic_spent\":120,\"traffic_limit\":500}}");
        assertEquals(120L, ProxyProviderService.findLong(json, "traffic_spent"));
        assertEquals(500L, ProxyProviderService.findLong(json, "traffic_limit"));
        String session = ProxyProviderService.newSession();
        assertEquals(8, session.length());
        assertTrue(ProxyProviderService.SESSION.matcher(session).matches());
        assertFalse(ProxyProviderService.newSession().equals(session));
    }
}
