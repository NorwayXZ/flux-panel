package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateProxyClientFormatsTests {
    private static JSONObject config(Object... pairs) {
        JSONObject c = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) c.put((String) pairs[i], pairs[i + 1]);
        return c;
    }

    @Test
    void pinnedFormatsUseTheCertificateInsteadOfSkippingVerification() {
        JSONObject c = config("password", "secret-123", "serverName", "cloudnest.local",
                "certSha256", "ab".repeat(32), "publicKeySha256", "AAAA");
        Map<String, Object> clash = PrivateProxyClientFormats.clash("trojan", "t", "1.2.3.4", 443, c);
        assertEquals("ab".repeat(32), clash.get("fingerprint"));
        assertNull(clash.get("skip-cert-verify"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tls = (Map<String, Object>) PrivateProxyClientFormats.singBox("trojan", "t", "1.2.3.4", 443, c)
                .outbounds.get(0).get("tls");
        assertEquals(List.of("AAAA"), tls.get("certificate_public_key_sha256"));
        assertNull(tls.get("insecure"));

        String hy2 = PrivateProxyClientFormats.uri("hysteria2", "h", "1.2.3.4", 443, c);
        assertTrue(hy2.contains("pinSHA256=" + "ab".repeat(32)));
        assertFalse(PrivateProxyClientFormats.uriSkipsCertificateCheck("hysteria2", c));
        assertTrue(PrivateProxyClientFormats.uriSkipsCertificateCheck("trojan", c));
    }

    @Test
    void withoutPinsClientsSkipVerificationAsBefore() {
        JSONObject c = config("password", "secret-123", "serverName", "cloudnest.local");
        assertEquals(true, PrivateProxyClientFormats.clash("tuic", "t", "h", 1, c).get("skip-cert-verify"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tls = (Map<String, Object>) PrivateProxyClientFormats.singBox("anytls", "a", "h", 1, c)
                .outbounds.get(0).get("tls");
        assertEquals(true, tls.get("insecure"));
        assertTrue(PrivateProxyClientFormats.uriSkipsCertificateCheck("hysteria2", c));
    }

    @Test
    void vmessLinkCarriesTheTransport() {
        JSONObject c = config("clientId", "11111111-2222-4333-8444-555555555555", "transport", "ws",
                "path", "/abc", "tls", false, "serverName", "cdn.example.com");
        String uri = PrivateProxyClientFormats.uri("vmess", "名字", "1.2.3.4", 8080, c);
        JSONObject decoded = JSONObject.parseObject(new String(Base64.getDecoder().decode(uri.substring(8)), StandardCharsets.UTF_8));
        assertEquals("ws", decoded.getString("net"));
        assertEquals("/abc", decoded.getString("path"));
        assertEquals("cdn.example.com", decoded.getString("host"));
        assertEquals("", decoded.getString("tls"));
        assertEquals("名字", decoded.getString("ps"));
        assertFalse(PrivateProxyClientFormats.uriSkipsCertificateCheck("vmess", c));
    }

    @Test
    void vlessGrpcAndHttpUpgradeLinks() {
        JSONObject grpc = config("clientId", "u", "transport", "grpc", "path", "svc", "tls", true, "serverName", "a.example.com");
        String uri = PrivateProxyClientFormats.uri("vless", "v", "1.2.3.4", 443, grpc);
        assertTrue(uri.contains("type=grpc&serviceName=svc&mode=gun"));
        assertTrue(uri.contains("security=tls&sni=a.example.com"));
        @SuppressWarnings("unchecked")
        Map<String, Object> clashGrpc = (Map<String, Object>) PrivateProxyClientFormats.clash("vless", "v", "h", 1, grpc).get("grpc-opts");
        assertEquals("svc", clashGrpc.get("grpc-service-name"));

        JSONObject upgrade = config("clientId", "u", "transport", "httpupgrade", "path", "/p", "tls", false);
        Map<String, Object> clash = PrivateProxyClientFormats.clash("vless", "v", "h", 1, upgrade);
        assertEquals("ws", clash.get("network"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ws = (Map<String, Object>) clash.get("ws-opts");
        assertEquals(true, ws.get("v2ray-http-upgrade"));
        assertNull(clash.get("tls"));
    }

    @Test
    void shadowTlsHasNoLinkButTwoSingBoxOutbounds() {
        JSONObject c = config("password", "outer-pass", "method", "2022-blake3-aes-128-gcm",
                "innerPassword", "MTIzNDU2Nzg5MDEyMzQ1Ng==", "handshakeServer", "www.microsoft.com");
        assertNull(PrivateProxyClientFormats.uri("shadowtls", "s", "h", 1, c));
        PrivateProxyClientFormats.SingBoxClient client = PrivateProxyClientFormats.singBox("shadowtls", "s", "h", 443, c);
        assertEquals(2, client.outbounds.size());
        assertEquals("s-shadowtls", client.outbounds.get(0).get("detour"));
        assertEquals("shadowtls", client.outbounds.get(1).get("type"));
        assertNull(client.outbounds.get(0).get("server"));
        Map<String, Object> clash = PrivateProxyClientFormats.clash("shadowtls", "s", "h", 443, c);
        assertEquals("shadow-tls", clash.get("plugin"));
    }

    @Test
    void xhttpIsOnlyInLinksAndXrayTests() {
        JSONObject c = config("clientId", "u", "publicKey", "pk", "shortId", "ab", "serverName", "www.cloudflare.com", "path", "/x");
        assertTrue(PrivateProxyClientFormats.uri("vless_xhttp", "x", "h", 1, c).contains("type=xhttp&path=%2Fx"));
        assertNull(PrivateProxyClientFormats.clash("vless_xhttp", "x", "h", 1, c));
        assertNull(PrivateProxyClientFormats.singBox("vless_xhttp", "x", "h", 1, c));
        Map<String, Object> outbound = PrivateProxyClientFormats.xrayXhttpOutbound("proxy", "127.0.0.1", 1, c);
        assertEquals("proxy", outbound.get("tag"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stream = (Map<String, Object>) outbound.get("streamSettings");
        assertEquals("xhttp", stream.get("network"));
        assertEquals("reality", stream.get("security"));
    }

    @Test
    void wireGuardIsAnEndpoint() {
        JSONObject c = config("clientPrivateKey", "k", "serverPublicKey", "p", "clientAddress", "10.66.1.2/32");
        PrivateProxyClientFormats.SingBoxClient client = PrivateProxyClientFormats.singBox("wireguard", "w", "h", 51820, c);
        assertTrue(client.outbounds.isEmpty());
        assertEquals("wireguard", client.endpoints.get(0).get("type"));
        assertEquals("10.66.1.2", PrivateProxyClientFormats.clash("wireguard", "w", "h", 1, c).get("ip"));
    }

    @Test
    void mixedIsOfferedAsSocks() {
        JSONObject c = config("username", "user", "password", "password1");
        assertTrue(PrivateProxyClientFormats.uri("mixed", "m", "h", 1, c).startsWith("socks5://user:password1@h:1"));
        assertEquals("socks5", PrivateProxyClientFormats.clash("mixed", "m", "h", 1, c).get("type"));
        assertEquals("socks", PrivateProxyClientFormats.singBox("mixed", "m", "h", 1, c).outbounds.get(0).get("type"));
    }

    @Test
    void subscriptionFormatFollowsRequestThenClient() {
        assertEquals("clash", PrivateProxySubscriptions.detectFormat("clash", "sing-box 1.13"));
        assertEquals("singbox", PrivateProxySubscriptions.detectFormat(null, "SFA/1.13.0 (android)"));
        assertEquals("clash", PrivateProxySubscriptions.detectFormat("", "clash-verge/v2.0 mihomo/1.19"));
        assertEquals("base64", PrivateProxySubscriptions.detectFormat(null, "Shadowrocket/2070"));
    }

    @Test
    void subscriptionNamesAreUnique() {
        Set<String> used = new java.util.HashSet<>();
        assertEquals("a", PrivateProxySubscriptions.uniqueName("a", used));
        assertEquals("a 2", PrivateProxySubscriptions.uniqueName("a", used));
        assertEquals("a 3", PrivateProxySubscriptions.uniqueName(" a ", used));
    }

    @Test
    void clashProfileRoutesEverythingThroughTheGroup() {
        String yaml = PrivateProxySubscriptions.clashProfile(List.of(
                PrivateProxyClientFormats.clash("socks5", "代理一", "1.2.3.4", 1080, config("username", "u", "password", "p"))));
        assertTrue(yaml.contains("name: 代理一"));
        assertTrue(yaml.contains("MATCH,云巢"));
    }

    @Test
    void singBoxProfileSelectsAmongTheProxies() {
        PrivateProxyClientFormats.SingBoxClient client = PrivateProxyClientFormats.singBox("socks5", "p1", "h", 1,
                config("username", "u", "password", "p"));
        JSONObject profile = JSONObject.parseObject(PrivateProxySubscriptions.singBoxProfile(List.of(client), List.of("p1")));
        JSONObject selector = profile.getJSONArray("outbounds").getJSONObject(0);
        assertEquals("selector", selector.getString("type"));
        assertEquals(List.of("p1", "direct"), selector.getJSONArray("outbounds").toJavaList(String.class));
        assertNotNull(profile.getJSONObject("route"));
    }

    @Test
    void allowlistAlwaysLetsTheNodeTestItself() {
        assertEquals(List.of("1.2.3.0/24", "127.0.0.1/32", "::1/128"), PrivateProxyService.admissionCidrs(List.of("1.2.3.0/24")));
        assertEquals(List.of("127.0.0.1/32", "::1/128"), PrivateProxyService.admissionCidrs(List.of("127.0.0.1/32")));
    }

    @Test
    void probeErrorsAreExplained() {
        assertTrue(PrivateProxyService.describeProbeError("dial tcp 127.0.0.1:1: connect: connection refused").startsWith("节点上的代理没有在监听"));
        assertTrue(PrivateProxyService.describeProbeError("context deadline exceeded").startsWith("连接超时"));
    }

    @Test
    void generatedSecretsHaveTheRightShape() {
        assertEquals(16, Base64.getDecoder().decode(PrivateProxyService.randomKey(PrivateProxyService.ss2022KeySize("2022-blake3-aes-128-gcm"))).length);
        assertEquals(32, Base64.getDecoder().decode(PrivateProxyService.randomKey(PrivateProxyService.ss2022KeySize("2022-blake3-aes-256-gcm"))).length);
        assertEquals(20, PrivateProxyService.randomPassword(20).length());
        assertEquals(16, PrivateProxyService.randomHex(8).length());
    }
}
