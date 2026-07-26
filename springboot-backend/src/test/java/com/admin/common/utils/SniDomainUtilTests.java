package com.admin.common.utils;

import com.admin.common.dto.SniRouteTargetDto;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SniDomainUtilTests {
    @Test
    void normalizesInternationalDomainAndRejectsWildcard() {
        assertEquals("xn--fsqu00a.xn--0zwm56d", SniDomainUtil.normalizeDomain("例子.测试."));
        assertThrows(IllegalArgumentException.class, () -> SniDomainUtil.normalizeDomain("*.example.com"));
        assertThrows(IllegalArgumentException.class, () -> SniDomainUtil.normalizeDomain("192.0.2.10"));
    }

    @Test
    void buildsTlsPassthroughIngressWithExactHostFilters() {
        JSONObject service = SniDomainUtil.buildIngressService("domain_ingress_8_443", "", 443, List.of(
                new SniRouteTargetDto(1L, "a.example.com", "127.0.0.1:20001"),
                new SniRouteTargetDto(2L, "b.example.com", "127.0.0.1:20002")));

        assertEquals(":443", service.getString("addr"));
        assertEquals("forward", service.getJSONObject("handler").getString("type"));
        assertTrue(service.getJSONObject("handler").getJSONObject("metadata").getBooleanValue("sniffing"));
        JSONArray nodes = service.getJSONObject("forwarder").getJSONArray("nodes");
        assertEquals(2, nodes.size());
        assertEquals("tls", nodes.getJSONObject(0).getJSONObject("filter").getString("protocol"));
        assertEquals("a.example.com", nodes.getJSONObject(0).getJSONObject("filter").getString("host"));
    }
}
