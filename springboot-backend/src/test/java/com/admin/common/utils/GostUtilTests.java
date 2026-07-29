package com.admin.common.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GostUtilTests {

    @Test
    void buildsOrderedSocks5HopsForHomeTunnelEgress() {
        JSONObject chain = GostUtil.createPublishingChainData("home_proxy_9_egress", List.of(
                new GostUtil.PublishingProxyHop("198.51.100.10:20001", "first", "secret-1"),
                new GostUtil.PublishingProxyHop("203.0.113.20:20002", "last", "secret-2")
        ));

        JSONArray hops = chain.getJSONArray("hops");
        assertEquals("home_proxy_9_egress", chain.getString("name"));
        assertEquals(2, hops.size());
        assertEquals("198.51.100.10:20001", hops.getJSONObject(0).getJSONArray("nodes")
                .getJSONObject(0).getString("addr"));
        assertEquals("socks5", hops.getJSONObject(0).getJSONArray("nodes")
                .getJSONObject(0).getJSONObject("connector").getString("type"));
        assertEquals("203.0.113.20:20002", hops.getJSONObject(1).getJSONArray("nodes")
                .getJSONObject(0).getString("addr"));
        assertEquals("last", hops.getJSONObject(1).getJSONArray("nodes")
                .getJSONObject(0).getJSONObject("connector").getJSONObject("auth").getString("username"));
    }

    @Test
    void omitsAuthenticationForLocalRealitySocksHop() {
        JSONObject chain = GostUtil.createPublishingChainData("home_proxy_10_egress", List.of(
                new GostUtil.PublishingProxyHop("127.0.0.1:19080", null, null)
        ));

        JSONObject connector = chain.getJSONArray("hops").getJSONObject(0).getJSONArray("nodes")
                .getJSONObject(0).getJSONObject("connector");
        assertEquals("socks5", connector.getString("type"));
        assertFalse(connector.containsKey("auth"));
    }
}
