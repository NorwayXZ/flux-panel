package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把私人代理的连接信息写成各种客户端能导入的格式：分享链接、Clash（mihomo）、
 * sing-box，以及节点自检用的客户端配置。
 *
 * 节点使用自签证书。能固定证书的格式（Clash、sing-box、Hysteria2 链接）写入证书指纹，
 * 只认这一张证书；其余链接只能跳过证书校验。
 */
final class PrivateProxyClientFormats {
    private PrivateProxyClientFormats() {
    }

    /** 某个协议的分享链接能否固定证书；不能固定时客户端会跳过证书校验。 */
    static boolean uriSkipsCertificateCheck(String type, JSONObject c) {
        switch (type) {
            case "trojan":
            case "tuic":
            case "anytls":
                return true;
            case "hysteria2":
                return StringUtils.isBlank(c.getString("certSha256"));
            case "vmess":
            case "vless":
                return c.getBooleanValue("tls");
            default:
                return false;
        }
    }

    /** 分享链接。没有通用链接格式的协议返回 null。 */
    static String uri(String type, String name, String host, int port, JSONObject c) {
        String label = url(name);
        String authority = authority(host) + ":" + port;
        switch (type) {
            case "shadowsocks":
                return "ss://" + base64Url(c.getString("cipher") + ":" + c.getString("password")) + "@" + authority + "#" + label;
            case "shadowsocks2022":
                return "ss://" + base64Url(c.getString("method") + ":" + c.getString("password")) + "@" + authority + "#" + label;
            case "vless_reality":
                return "vless://" + c.getString("clientId") + "@" + authority
                        + "?encryption=none&flow=xtls-rprx-vision&security=reality&type=tcp&headerType=none"
                        + "&sni=" + url(c.getString("serverName")) + "&fp=chrome&pbk=" + url(c.getString("publicKey"))
                        + "&sid=" + url(c.getString("shortId")) + "#" + label;
            case "vless_xhttp":
                return "vless://" + c.getString("clientId") + "@" + authority
                        + "?encryption=none&security=reality&type=xhttp&path=" + url(c.getString("path")) + "&mode=auto"
                        + "&sni=" + url(c.getString("serverName")) + "&fp=chrome&pbk=" + url(c.getString("publicKey"))
                        + "&sid=" + url(c.getString("shortId")) + "#" + label;
            case "trojan":
                return "trojan://" + url(c.getString("password")) + "@" + authority
                        + "?security=tls&sni=" + url(c.getString("serverName")) + "&allowInsecure=1#" + label;
            case "hysteria2": {
                StringBuilder uri = new StringBuilder("hysteria2://").append(url(c.getString("password"))).append("@")
                        .append(authority).append("?sni=").append(url(c.getString("serverName"))).append("&insecure=1");
                if (StringUtils.isNotBlank(c.getString("certSha256"))) {
                    uri.append("&pinSHA256=").append(c.getString("certSha256"));
                }
                if (StringUtils.isNotBlank(c.getString("obfsPassword"))) {
                    uri.append("&obfs=salamander&obfs-password=").append(url(c.getString("obfsPassword")));
                }
                return uri.append("#").append(label).toString();
            }
            case "tuic":
                return "tuic://" + url(c.getString("clientId")) + ":" + url(c.getString("password")) + "@"
                        + authority + "?congestion_control=bbr&alpn=h3&sni=" + url(c.getString("serverName"))
                        + "&allow_insecure=1#" + label;
            case "anytls":
                return "anytls://" + url(c.getString("password")) + "@" + authority
                        + "?sni=" + url(c.getString("serverName")) + "&insecure=1#" + label;
            case "vmess":
                return vmessUri(name, host, port, c);
            case "vless":
                return vlessUri(label, authority, c);
            case "wireguard":
                return "[Interface]\nPrivateKey = " + c.getString("clientPrivateKey") + "\nAddress = "
                        + c.getString("clientAddress") + "\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = "
                        + c.getString("serverPublicKey") + "\nEndpoint = " + authority
                        + "\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n";
            case "shadowtls":
                return null;
            case "mixed":
                return "socks5://" + url(c.getString("username")) + ":" + url(c.getString("password")) + "@" + authority + "#" + label;
            default:
                return type + "://" + url(c.getString("username")) + ":" + url(c.getString("password")) + "@" + authority + "#" + label;
        }
    }

    private static String vmessUri(String name, String host, int port, JSONObject c) {
        String transport = StringUtils.defaultIfBlank(c.getString("transport"), "tcp");
        boolean tls = c.getBooleanValue("tls");
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("v", "2");
        v.put("ps", name);
        v.put("add", host);
        v.put("port", String.valueOf(port));
        v.put("id", c.getString("clientId"));
        v.put("aid", "0");
        v.put("scy", "auto");
        v.put("net", transport);
        v.put("type", "grpc".equals(transport) ? "gun" : "none");
        v.put("host", StringUtils.defaultString(c.getString("serverName")));
        v.put("path", StringUtils.defaultString(c.getString("path")));
        v.put("tls", tls ? "tls" : "");
        v.put("sni", tls ? StringUtils.defaultString(c.getString("serverName")) : "");
        if (tls) v.put("allowInsecure", "1");
        return "vmess://" + Base64.getEncoder().encodeToString(JSONObject.toJSONString(v).getBytes(StandardCharsets.UTF_8));
    }

    private static String vlessUri(String label, String authority, JSONObject c) {
        String transport = StringUtils.defaultIfBlank(c.getString("transport"), "tcp");
        StringBuilder uri = new StringBuilder("vless://").append(c.getString("clientId")).append("@").append(authority)
                .append("?encryption=none&type=").append(transport);
        String serverName = StringUtils.defaultString(c.getString("serverName"));
        if ("grpc".equals(transport)) {
            uri.append("&serviceName=").append(url(c.getString("path"))).append("&mode=gun");
        } else if (!"tcp".equals(transport)) {
            uri.append("&path=").append(url(c.getString("path")));
            if (!serverName.isEmpty()) uri.append("&host=").append(url(serverName));
        }
        if (c.getBooleanValue("tls")) {
            uri.append("&security=tls&sni=").append(url(serverName)).append("&allowInsecure=1");
        } else {
            uri.append("&security=none");
        }
        return uri.append("#").append(label).toString();
    }

    /** Clash（mihomo）的代理条目。不支持的协议返回 null。 */
    static Map<String, Object> clash(String type, String name, String host, int port, JSONObject c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("server", host);
        p.put("port", port);
        switch (type) {
            case "socks5":
            case "mixed":
                p.put("type", "socks5");
                p.put("username", c.getString("username"));
                p.put("password", c.getString("password"));
                p.put("udp", true);
                return p;
            case "http":
                p.put("type", "http");
                p.put("username", c.getString("username"));
                p.put("password", c.getString("password"));
                return p;
            case "shadowsocks":
                p.put("type", "ss");
                p.put("cipher", c.getString("cipher"));
                p.put("password", c.getString("password"));
                p.put("udp", true);
                return p;
            case "shadowsocks2022":
                p.put("type", "ss");
                p.put("cipher", c.getString("method"));
                p.put("password", c.getString("password"));
                p.put("udp", true);
                return p;
            case "shadowtls": {
                p.put("type", "ss");
                p.put("cipher", c.getString("method"));
                p.put("password", c.getString("innerPassword"));
                p.put("client-fingerprint", "chrome");
                p.put("plugin", "shadow-tls");
                Map<String, Object> opts = new LinkedHashMap<>();
                opts.put("host", c.getString("handshakeServer"));
                opts.put("password", c.getString("password"));
                opts.put("version", 3);
                p.put("plugin-opts", opts);
                return p;
            }
            case "vless_reality": {
                p.put("type", "vless");
                p.put("uuid", c.getString("clientId"));
                p.put("network", "tcp");
                p.put("tls", true);
                p.put("udp", true);
                p.put("flow", "xtls-rprx-vision");
                p.put("servername", c.getString("serverName"));
                p.put("client-fingerprint", "chrome");
                Map<String, Object> reality = new LinkedHashMap<>();
                reality.put("public-key", c.getString("publicKey"));
                reality.put("short-id", c.getString("shortId"));
                p.put("reality-opts", reality);
                return p;
            }
            case "trojan":
                p.put("type", "trojan");
                p.put("password", c.getString("password"));
                p.put("sni", c.getString("serverName"));
                p.put("udp", true);
                pinClash(p, c);
                return p;
            case "anytls":
                p.put("type", "anytls");
                p.put("password", c.getString("password"));
                p.put("sni", c.getString("serverName"));
                p.put("udp", true);
                pinClash(p, c);
                return p;
            case "hysteria2":
                p.put("type", "hysteria2");
                p.put("password", c.getString("password"));
                p.put("sni", c.getString("serverName"));
                if (StringUtils.isNotBlank(c.getString("obfsPassword"))) {
                    p.put("obfs", "salamander");
                    p.put("obfs-password", c.getString("obfsPassword"));
                }
                pinClash(p, c);
                return p;
            case "tuic":
                p.put("type", "tuic");
                p.put("uuid", c.getString("clientId"));
                p.put("password", c.getString("password"));
                p.put("sni", c.getString("serverName"));
                p.put("alpn", List.of("h3"));
                p.put("congestion-controller", "bbr");
                p.put("udp-relay-mode", "native");
                pinClash(p, c);
                return p;
            case "vmess":
            case "vless": {
                p.put("type", type);
                p.put("uuid", c.getString("clientId"));
                if ("vmess".equals(type)) {
                    p.put("alterId", 0);
                    p.put("cipher", "auto");
                }
                p.put("udp", true);
                String transport = StringUtils.defaultIfBlank(c.getString("transport"), "tcp");
                String serverName = StringUtils.defaultString(c.getString("serverName"));
                if ("ws".equals(transport) || "httpupgrade".equals(transport)) {
                    p.put("network", "ws");
                    Map<String, Object> ws = new LinkedHashMap<>();
                    ws.put("path", c.getString("path"));
                    if (!serverName.isEmpty()) ws.put("headers", Map.of("Host", serverName));
                    if ("httpupgrade".equals(transport)) ws.put("v2ray-http-upgrade", true);
                    p.put("ws-opts", ws);
                } else if ("grpc".equals(transport)) {
                    p.put("network", "grpc");
                    p.put("grpc-opts", Map.of("grpc-service-name", c.getString("path")));
                } else {
                    p.put("network", "tcp");
                }
                if (c.getBooleanValue("tls")) {
                    p.put("tls", true);
                    p.put("servername", serverName);
                    pinClash(p, c);
                }
                return p;
            }
            case "wireguard": {
                p.put("type", "wireguard");
                p.put("ip", StringUtils.substringBefore(c.getString("clientAddress"), "/"));
                p.put("private-key", c.getString("clientPrivateKey"));
                p.put("public-key", c.getString("serverPublicKey"));
                p.put("allowed-ips", List.of("0.0.0.0/0"));
                p.put("udp", true);
                return p;
            }
            default:
                return null;
        }
    }

    private static void pinClash(Map<String, Object> p, JSONObject c) {
        String fingerprint = c.getString("certSha256");
        if (StringUtils.isNotBlank(fingerprint)) {
            p.put("fingerprint", fingerprint);
        } else {
            p.put("skip-cert-verify", true);
        }
    }

    /** sing-box 客户端配置：主出站的标签为 tag。WireGuard 放在 endpoints 里。 */
    static final class SingBoxClient {
        final List<Map<String, Object>> outbounds = new ArrayList<>();
        final List<Map<String, Object>> endpoints = new ArrayList<>();
    }

    static SingBoxClient singBox(String type, String tag, String host, int port, JSONObject c) {
        SingBoxClient client = new SingBoxClient();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("tag", tag);
        o.put("server", host);
        o.put("server_port", port);
        switch (type) {
            case "socks5":
            case "mixed":
                o.put("type", "socks");
                o.put("version", "5");
                o.put("username", c.getString("username"));
                o.put("password", c.getString("password"));
                break;
            case "http":
                o.put("type", "http");
                o.put("username", c.getString("username"));
                o.put("password", c.getString("password"));
                break;
            case "shadowsocks":
                o.put("type", "shadowsocks");
                o.put("method", c.getString("cipher"));
                o.put("password", c.getString("password"));
                break;
            case "shadowsocks2022":
                o.put("type", "shadowsocks");
                o.put("method", c.getString("method"));
                o.put("password", c.getString("password"));
                break;
            case "shadowtls": {
                Map<String, Object> shadowTls = new LinkedHashMap<>();
                shadowTls.put("type", "shadowtls");
                shadowTls.put("tag", tag + "-shadowtls");
                shadowTls.put("server", host);
                shadowTls.put("server_port", port);
                shadowTls.put("version", 3);
                shadowTls.put("password", c.getString("password"));
                Map<String, Object> tls = new LinkedHashMap<>();
                tls.put("enabled", true);
                tls.put("server_name", c.getString("handshakeServer"));
                tls.put("utls", Map.of("enabled", true, "fingerprint", "chrome"));
                shadowTls.put("tls", tls);
                o.remove("server");
                o.remove("server_port");
                o.put("type", "shadowsocks");
                o.put("method", c.getString("method"));
                o.put("password", c.getString("innerPassword"));
                o.put("detour", tag + "-shadowtls");
                client.outbounds.add(o);
                client.outbounds.add(shadowTls);
                return client;
            }
            case "vless_reality":
            case "vless_xhttp": {
                o.put("type", "vless");
                o.put("uuid", c.getString("clientId"));
                if ("vless_reality".equals(type)) o.put("flow", "xtls-rprx-vision");
                Map<String, Object> tls = new LinkedHashMap<>();
                tls.put("enabled", true);
                tls.put("server_name", c.getString("serverName"));
                tls.put("utls", Map.of("enabled", true, "fingerprint", "chrome"));
                Map<String, Object> reality = new LinkedHashMap<>();
                reality.put("enabled", true);
                reality.put("public_key", c.getString("publicKey"));
                reality.put("short_id", c.getString("shortId"));
                tls.put("reality", reality);
                o.put("tls", tls);
                if ("vless_xhttp".equals(type)) return null;
                break;
            }
            case "trojan":
                o.put("type", "trojan");
                o.put("password", c.getString("password"));
                o.put("tls", singBoxTls(c));
                break;
            case "anytls":
                o.put("type", "anytls");
                o.put("password", c.getString("password"));
                o.put("tls", singBoxTls(c));
                break;
            case "hysteria2":
                o.put("type", "hysteria2");
                o.put("password", c.getString("password"));
                if (StringUtils.isNotBlank(c.getString("obfsPassword"))) {
                    o.put("obfs", Map.of("type", "salamander", "password", c.getString("obfsPassword")));
                }
                o.put("tls", singBoxTls(c));
                break;
            case "tuic":
                o.put("type", "tuic");
                o.put("uuid", c.getString("clientId"));
                o.put("password", c.getString("password"));
                o.put("congestion_control", "bbr");
                Map<String, Object> tuicTls = singBoxTls(c);
                tuicTls.put("alpn", List.of("h3"));
                o.put("tls", tuicTls);
                break;
            case "vmess":
            case "vless": {
                o.put("type", type);
                o.put("uuid", c.getString("clientId"));
                if ("vmess".equals(type)) {
                    o.put("security", "auto");
                    o.put("alter_id", 0);
                }
                String transport = StringUtils.defaultIfBlank(c.getString("transport"), "tcp");
                String serverName = StringUtils.defaultString(c.getString("serverName"));
                if ("ws".equals(transport) || "httpupgrade".equals(transport)) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("type", transport);
                    t.put("path", c.getString("path"));
                    if (!serverName.isEmpty()) {
                        if ("ws".equals(transport)) t.put("headers", Map.of("Host", serverName));
                        else t.put("host", serverName);
                    }
                    o.put("transport", t);
                } else if ("grpc".equals(transport)) {
                    o.put("transport", Map.of("type", "grpc", "service_name", c.getString("path")));
                }
                if (c.getBooleanValue("tls")) o.put("tls", singBoxTls(c));
                break;
            }
            case "wireguard": {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", "wireguard");
                e.put("tag", tag);
                e.put("address", List.of(c.getString("clientAddress")));
                e.put("private_key", c.getString("clientPrivateKey"));
                Map<String, Object> peer = new LinkedHashMap<>();
                peer.put("address", host);
                peer.put("port", port);
                peer.put("public_key", c.getString("serverPublicKey"));
                peer.put("allowed_ips", List.of("0.0.0.0/0"));
                peer.put("persistent_keepalive_interval", 25);
                e.put("peers", List.of(peer));
                client.endpoints.add(e);
                return client;
            }
            default:
                return null;
        }
        client.outbounds.add(o);
        return client;
    }

    private static Map<String, Object> singBoxTls(JSONObject c) {
        Map<String, Object> tls = new LinkedHashMap<>();
        tls.put("enabled", true);
        tls.put("server_name", c.getString("serverName"));
        String pin = c.getString("publicKeySha256");
        if (StringUtils.isNotBlank(pin)) {
            tls.put("certificate_public_key_sha256", List.of(pin));
        } else {
            tls.put("insecure", true);
        }
        return tls;
    }

    /** xray 客户端出站，用于 sing-box 不支持的 VLESS + XHTTP + REALITY。 */
    static Map<String, Object> xrayXhttpOutbound(String tag, String host, int port, JSONObject c) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", c.getString("clientId"));
        user.put("encryption", "none");
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", host);
        server.put("port", port);
        server.put("users", List.of(user));
        Map<String, Object> reality = new LinkedHashMap<>();
        reality.put("fingerprint", "chrome");
        reality.put("serverName", c.getString("serverName"));
        reality.put("publicKey", c.getString("publicKey"));
        reality.put("shortId", c.getString("shortId"));
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("network", "xhttp");
        stream.put("security", "reality");
        stream.put("realitySettings", reality);
        stream.put("xhttpSettings", Map.of("path", c.getString("path"), "mode", "auto"));
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("tag", tag);
        outbound.put("protocol", "vless");
        outbound.put("settings", Map.of("vnext", List.of(server)));
        outbound.put("streamSettings", stream);
        return outbound;
    }

    static String authority(String host) {
        return host.contains(":") ? "[" + host + "]" : host;
    }

    static String url(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
