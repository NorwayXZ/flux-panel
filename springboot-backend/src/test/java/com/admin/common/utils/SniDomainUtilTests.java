package com.admin.common.utils;

import com.admin.common.dto.SniRouteTargetDto;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
                new SniRouteTargetDto(1L, "a.example.com", null, "127.0.0.1:20001"),
                new SniRouteTargetDto(2L, "b.example.com", null, "127.0.0.1:20002")));

        assertEquals(":443", service.getString("addr"));
        assertEquals("forward", service.getJSONObject("handler").getString("type"));
        assertTrue(service.getJSONObject("handler").getJSONObject("metadata").getBooleanValue("sniffing"));
        JSONArray nodes = service.getJSONObject("forwarder").getJSONArray("nodes");
        assertEquals(2, nodes.size());
        assertEquals("tls", nodes.getJSONObject(0).getJSONObject("filter").getString("protocol"));
        assertEquals("a.example.com", nodes.getJSONObject(0).getJSONObject("filter").getString("host"));
    }

    @Test
    void buildsManagedHttpsIngressWithSniCertificatesAndHttpFilters() {
        JSONObject service = SniDomainUtil.buildManagedHttpsService(
                "managed_https_8_443", "", 443,
                List.of(new SniRouteTargetDto(1L, "app.example.com", "/api", "127.0.0.1:20001")),
                List.of(Map.of("names", List.of("app.example.com"),
                        "certFile", "/etc/gost/managed-certs/1.crt",
                        "keyFile", "/etc/gost/managed-certs/1.key")));

        JSONObject listener = service.getJSONObject("listener");
        assertEquals("tls", listener.getString("type"));
        assertEquals("VersionTLS12", listener.getJSONObject("tls").getJSONObject("options").getString("minVersion"));
        assertEquals(1, listener.getJSONObject("tls").getJSONArray("certificates").size());
        assertEquals("http", service.getJSONObject("forwarder").getJSONArray("nodes")
                .getJSONObject(0).getJSONObject("filter").getString("protocol"));
        assertEquals("/api", service.getJSONObject("forwarder").getJSONArray("nodes")
                .getJSONObject(0).getJSONObject("filter").getString("path"));
    }

    @Test
    void normalizesHttpPathPrefixes() {
        assertEquals("/", SniDomainUtil.normalizePathPrefix(null));
        assertEquals("/api", SniDomainUtil.normalizePathPrefix("api/"));
        assertThrows(IllegalArgumentException.class, () -> SniDomainUtil.normalizePathPrefix("/api?q=1"));
    }

    @Test
    void buildsBackendRootPathRewrite() {
        JSONObject service = SniDomainUtil.buildManagedHttpsService(
                "managed_https_8_443", "", 443,
                List.of(new SniRouteTargetDto(1L, "xui.example.com", "/", "127.0.0.1:54321", "http", "/abc123")),
                List.of(Map.of("names", List.of("xui.example.com"), "certFile", "/tmp/x.crt", "keyFile", "/tmp/x.key")));

        JSONObject node = service.getJSONObject("forwarder").getJSONArray("nodes").getJSONObject(0);
        assertEquals("^/(.*)$", node.getJSONObject("http").getJSONArray("rewriteURL").getJSONObject(0).getString("match"));
        assertEquals("/abc123/$1", node.getJSONObject("http").getJSONArray("rewriteURL").getJSONObject(0).getString("replacement"));
        assertEquals("/abc123", node.getJSONObject("http").getJSONObject("responseHeader").getString("@cloudnest.internalPath"));
    }
}
