package com.admin.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyExitLinksTests {
    static final String UUID = "11111111-2222-3333-4444-555555555555";

    static final String[] LINKS = {
            "vless://" + UUID + "@reality.example.com:443?security=reality&sni=www.microsoft.com&fp=chrome&pbk=uPvv8YQ8X2Zx3H9yjUfmdz8nmLNZmV9pWwF0W2V6HXs&sid=ab12&flow=xtls-rprx-vision&type=tcp#JP%20Reality",
            "vless://" + UUID + "@ws.example.com:443?security=tls&sni=ws.example.com&type=ws&path=%2Fray&host=ws.example.com#WS",
            "vmess://" + Base64.getEncoder().encodeToString(("{\"v\":\"2\",\"ps\":\"US VMess\",\"add\":\"1.2.3.4\",\"port\":\"8080\",\"id\":\"" + UUID
                    + "\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"host\":\"a.example.com\",\"path\":\"/v\",\"tls\":\"tls\",\"sni\":\"a.example.com\"}").getBytes(StandardCharsets.UTF_8)),
            "trojan://secret@trojan.example.com:443?sni=trojan.example.com&type=grpc&serviceName=gun#Trojan",
            "ss://" + Base64.getEncoder().encodeToString("2022-blake3-aes-128-gcm:AAAAAAAAAAAAAAAAAAAAAA==".getBytes(StandardCharsets.UTF_8)) + "@5.6.7.8:8388#SS",
            "hysteria2://pass@hy.example.com:8443?sni=hy.example.com&obfs=salamander&obfs-password=ob&insecure=1#HY2",
            "tuic://" + UUID + ":pwd@tuic.example.com:443?sni=tuic.example.com&congestion_control=bbr&alpn=h3#TUIC",
            "anytls://pwd@any.example.com:443?sni=any.example.com#AnyTLS",
            "socks5://user:pass@9.9.9.9:1080#Socks",
            "https://user:pass@proxy.example.com:443#HTTPS",
    };

    @Test
    void parsesEverySupportedLink() {
        List<ProxyExitLinks.Parsed> parsed = ProxyExitLinks.parse(String.join("\n", LINKS));
        assertEquals(LINKS.length, parsed.size());

        JSONObject reality = parsed.get(0).outbound;
        assertEquals("JP Reality", parsed.get(0).name);
        assertEquals("vless", reality.getString("type"));
        assertEquals("xtls-rprx-vision", reality.getString("flow"));
        assertEquals("ab12", reality.getJSONObject("tls").getJSONObject("reality").getString("short_id"));
        assertEquals("www.microsoft.com", reality.getJSONObject("tls").getString("server_name"));

        assertEquals("/ray", parsed.get(1).outbound.getJSONObject("transport").getString("path"));
        assertEquals("ws", parsed.get(2).outbound.getJSONObject("transport").getString("type"));
        assertEquals("US VMess", parsed.get(2).name);
        assertEquals("gun", parsed.get(3).outbound.getJSONObject("transport").getString("service_name"));
        assertEquals("2022-blake3-aes-128-gcm", parsed.get(4).outbound.getString("method"));
        assertEquals("salamander", parsed.get(5).outbound.getJSONObject("obfs").getString("type"));
        assertTrue(parsed.get(5).outbound.getJSONObject("tls").getBooleanValue("insecure"));
        assertEquals("pwd", parsed.get(6).outbound.getString("password"));
        assertEquals("anytls", parsed.get(7).outbound.getString("type"));
        assertEquals("socks", parsed.get(8).outbound.getString("type"));
        assertTrue(parsed.get(9).outbound.getJSONObject("tls").getBooleanValue("enabled"));
    }

    @Test
    void parsesClashProxies() {
        String yaml = "proxies:\n"
                + "  - {name: HK Reality, type: vless, server: hk.example.com, port: 443, uuid: " + UUID + ", tls: true, flow: xtls-rprx-vision, "
                + "servername: www.apple.com, client-fingerprint: chrome, reality-opts: {public-key: uPvv8YQ8X2Zx3H9yjUfmdz8nmLNZmV9pWwF0W2V6HXs, short-id: ab12}}\n"
                + "  - name: SG HY2\n    type: hysteria2\n    server: sg.example.com\n    port: 8443\n    password: p\n    skip-cert-verify: true\n"
                + "  - {name: SS, type: ss, server: 1.1.1.1, port: 8388, cipher: aes-128-gcm, password: x}\n";
        List<ProxyExitLinks.Parsed> parsed = ProxyExitLinks.parse(yaml);
        assertEquals(3, parsed.size());
        assertEquals("HK Reality", parsed.get(0).name);
        assertEquals("www.apple.com", parsed.get(0).outbound.getJSONObject("tls").getString("server_name"));
        assertEquals("hysteria2", parsed.get(1).outbound.getString("type"));
        assertEquals("aes-128-gcm", parsed.get(2).outbound.getString("method"));
    }

    @Test
    void explainsWhatCannotBeUsed() {
        assertThrows(IllegalArgumentException.class, () -> ProxyExitLinks.parse("wireguard://abc@1.2.3.4:51820"));
        assertThrows(IllegalArgumentException.class, () -> ProxyExitLinks.parse(
                "vless://" + UUID + "@a.example.com:443?security=tls&type=xhttp&path=%2F#x"));
        assertThrows(IllegalArgumentException.class, () -> ProxyExitLinks.parse("  "));
    }

    /** With SINGBOX_CHECK_OUT set, writes a router config built from every link so sing-box can check it. */
    @Test
    void writesConfigForSingBoxCheck() throws Exception {
        String out = System.getenv("SINGBOX_CHECK_OUT");
        if (out == null) return;
        JSONArray outbounds = new JSONArray();
        int index = 0;
        for (ProxyExitLinks.Parsed parsed : ProxyExitLinks.parse(String.join("\n", LINKS))) {
            JSONObject outbound = new JSONObject(true);
            outbound.putAll(parsed.outbound);
            outbound.put("tag", "custom-" + index++);
            outbounds.add(outbound);
        }
        outbounds.add(JSONObject.parseObject("{\"type\":\"direct\",\"tag\":\"direct\"}"));
        JSONObject config = JSONObject.parseObject("{\"log\":{\"level\":\"warn\"},\"dns\":{\"servers\":[{\"type\":\"local\",\"tag\":\"local\"}]},"
                + "\"inbounds\":[{\"type\":\"socks\",\"tag\":\"in\",\"listen\":\"127.0.0.1\",\"listen_port\":40001}],"
                + "\"route\":{\"final\":\"direct\",\"default_domain_resolver\":\"local\"}}");
        config.put("outbounds", outbounds);
        Files.writeString(Path.of(out), config.toJSONString());
        assertFalse(outbounds.isEmpty());
    }
}
