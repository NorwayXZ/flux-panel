package com.admin.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns share links (vless://, vmess://, trojan://, ss://, hysteria2://, tuic://, anytls://,
 * socks5://, http(s)://) and Clash proxy entries into sing-box outbound objects.
 */
public final class ProxyExitLinks {

    private ProxyExitLinks() {
    }

    /** One parsed exit: a display name, its protocol and server, and the sing-box outbound (without tag). */
    public static final class Parsed {
        public final String name;
        public final String protocol;
        public final String server;
        public final int port;
        public final JSONObject outbound;

        Parsed(String name, String protocol, String server, int port, JSONObject outbound) {
            this.name = name;
            this.protocol = protocol;
            this.server = server;
            this.port = port;
            this.outbound = outbound;
        }
    }

    /** Parses every link or Clash proxy in the text; throws with a readable reason when nothing usable is found. */
    public static List<Parsed> parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("请粘贴分享链接或 Clash 节点");
        String trimmed = text.trim();
        if (!trimmed.contains("://") || trimmed.startsWith("proxies:") || trimmed.startsWith("- ") || trimmed.startsWith("{")) {
            return parseClash(trimmed);
        }
        List<Parsed> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String line : trimmed.split("\\s+")) {
            if (line.isBlank()) continue;
            try {
                result.add(parseLink(line.trim()));
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(errors.isEmpty() ? "没有识别到分享链接" : errors.get(0));
        }
        return result;
    }

    static Parsed parseLink(String link) {
        String scheme = link.substring(0, link.indexOf("://")).toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "vmess":
                return vmess(link);
            case "ss":
                return shadowsocks(link);
            case "vless":
            case "trojan":
            case "hysteria2":
            case "hy2":
            case "tuic":
            case "anytls":
            case "socks":
            case "socks5":
            case "http":
            case "https":
                return uriLink(scheme, link);
            default:
                throw new IllegalArgumentException("不支持的链接类型：" + scheme);
        }
    }

    private static Parsed uriLink(String scheme, String link) {
        URI uri;
        try {
            uri = new URI(link.replace(" ", "%20"));
        } catch (Exception e) {
            throw new IllegalArgumentException("链接格式不正确：" + abbreviate(link));
        }
        String host = stripBrackets(uri.getHost());
        int port = uri.getPort();
        if (host == null || port < 1) throw new IllegalArgumentException("链接缺少地址或端口：" + abbreviate(link));
        Map<String, String> query = query(uri.getRawQuery());
        String userInfo = uri.getRawUserInfo() == null ? "" : decode(uri.getRawUserInfo());
        String name = uri.getRawFragment() == null ? host + ":" + port : decode(uri.getRawFragment());
        JSONObject out = new JSONObject(true);
        String protocol;
        switch (scheme) {
            case "vless": {
                protocol = "vless";
                out.put("type", "vless");
                put(out, host, port);
                out.put("uuid", require(userInfo, "VLESS 链接缺少 UUID"));
                if (notBlank(query.get("flow"))) out.put("flow", query.get("flow"));
                String security = query.getOrDefault("security", "none");
                if ("reality".equals(security) || "tls".equals(security)) {
                    JSONObject tls = tls(firstNonBlank(query.get("sni"), query.get("peer"), host), truthy(query.get("allowInsecure")) || truthy(query.get("insecure")), query.get("alpn"));
                    if (notBlank(query.get("fp"))) tls.put("utls", utls(query.get("fp")));
                    if ("reality".equals(security)) {
                        JSONObject reality = new JSONObject(true);
                        reality.put("enabled", true);
                        reality.put("public_key", require(query.get("pbk"), "REALITY 链接缺少公钥（pbk）"));
                        if (notBlank(query.get("sid"))) reality.put("short_id", query.get("sid"));
                        tls.put("reality", reality);
                        if (!tls.containsKey("utls")) tls.put("utls", utls("chrome"));
                    }
                    out.put("tls", tls);
                }
                transport(out, query.getOrDefault("type", "tcp"), query.get("path"), query.get("host"), query.get("serviceName"));
                break;
            }
            case "trojan": {
                protocol = "trojan";
                out.put("type", "trojan");
                put(out, host, port);
                out.put("password", require(userInfo, "Trojan 链接缺少密码"));
                if (!"none".equals(query.get("security"))) {
                    JSONObject tls = tls(firstNonBlank(query.get("sni"), query.get("peer"), host), truthy(query.get("allowInsecure")), query.get("alpn"));
                    if (notBlank(query.get("fp"))) tls.put("utls", utls(query.get("fp")));
                    out.put("tls", tls);
                }
                transport(out, query.getOrDefault("type", "tcp"), query.get("path"), query.get("host"), query.get("serviceName"));
                break;
            }
            case "hysteria2":
            case "hy2": {
                protocol = "hysteria2";
                out.put("type", "hysteria2");
                put(out, host, port);
                out.put("password", require(userInfo, "Hysteria2 链接缺少密码"));
                if (notBlank(query.get("obfs")) && !"none".equals(query.get("obfs"))) {
                    JSONObject obfs = new JSONObject(true);
                    obfs.put("type", query.get("obfs"));
                    obfs.put("password", query.getOrDefault("obfs-password", ""));
                    out.put("obfs", obfs);
                }
                out.put("tls", tls(firstNonBlank(query.get("sni"), host), truthy(query.get("insecure")), query.get("alpn")));
                break;
            }
            case "tuic": {
                protocol = "tuic";
                out.put("type", "tuic");
                put(out, host, port);
                String[] parts = userInfo.split(":", 2);
                out.put("uuid", require(parts[0], "TUIC 链接缺少 UUID"));
                out.put("password", parts.length > 1 ? parts[1] : require(query.get("password"), "TUIC 链接缺少密码"));
                out.put("congestion_control", query.getOrDefault("congestion_control", "bbr"));
                out.put("tls", tls(firstNonBlank(query.get("sni"), host), truthy(query.get("allow_insecure")) || truthy(query.get("insecure")),
                        firstNonBlank(query.get("alpn"), "h3")));
                break;
            }
            case "anytls": {
                protocol = "anytls";
                out.put("type", "anytls");
                put(out, host, port);
                out.put("password", require(userInfo, "AnyTLS 链接缺少密码"));
                out.put("tls", tls(firstNonBlank(query.get("sni"), host), truthy(query.get("insecure")), query.get("alpn")));
                break;
            }
            case "socks":
            case "socks5": {
                protocol = "socks5";
                out.put("type", "socks");
                out.put("version", "5");
                put(out, host, port);
                credentials(out, userInfo, "username", "password");
                break;
            }
            default: {
                protocol = scheme;
                out.put("type", "http");
                put(out, host, port);
                credentials(out, userInfo, "username", "password");
                if ("https".equals(scheme)) out.put("tls", tls(host, truthy(query.get("insecure")), null));
                break;
            }
        }
        return new Parsed(name, protocol, host, port, out);
    }

    private static Parsed vmess(String link) {
        JSONObject v;
        try {
            v = JSON.parseObject(decodeBase64(link.substring("vmess://".length())));
        } catch (Exception e) {
            throw new IllegalArgumentException("VMess 链接无法解析");
        }
        String host = require(v.getString("add"), "VMess 链接缺少地址");
        int port = v.getIntValue("port");
        if (port < 1) throw new IllegalArgumentException("VMess 链接缺少端口");
        JSONObject out = new JSONObject(true);
        out.put("type", "vmess");
        put(out, host, port);
        out.put("uuid", require(v.getString("id"), "VMess 链接缺少 UUID"));
        out.put("security", firstNonBlank(v.getString("scy"), "auto"));
        out.put("alter_id", v.getIntValue("aid"));
        if ("tls".equalsIgnoreCase(v.getString("tls"))) {
            JSONObject tls = tls(firstNonBlank(v.getString("sni"), v.getString("host"), host), false, v.getString("alpn"));
            if (notBlank(v.getString("fp"))) tls.put("utls", utls(v.getString("fp")));
            out.put("tls", tls);
        }
        transport(out, firstNonBlank(v.getString("net"), "tcp"), v.getString("path"), v.getString("host"), v.getString("path"));
        return new Parsed(firstNonBlank(v.getString("ps"), host + ":" + port), "vmess", host, port, out);
    }

    private static Parsed shadowsocks(String link) {
        String body = link.substring("ss://".length());
        String name = null;
        int hash = body.indexOf('#');
        if (hash >= 0) {
            name = decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        int question = body.indexOf('?');
        if (question >= 0) {
            if (body.substring(question).contains("plugin=")) throw new IllegalArgumentException("暂不支持带插件的 Shadowsocks 链接");
            body = body.substring(0, question);
        }
        String userInfo;
        String hostPort;
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            userInfo = body.substring(0, at);
            hostPort = body.substring(at + 1);
            if (!userInfo.contains(":")) userInfo = decodeBase64(userInfo);
            else userInfo = decode(userInfo);
        } else {
            String decoded = decodeBase64(body);
            int split = decoded.lastIndexOf('@');
            if (split < 0) throw new IllegalArgumentException("Shadowsocks 链接格式不正确");
            userInfo = decoded.substring(0, split);
            hostPort = decoded.substring(split + 1);
        }
        hostPort = hostPort.replaceAll("/+$", "");
        int colon = hostPort.lastIndexOf(':');
        String[] credentials = userInfo.split(":", 2);
        if (colon < 0 || credentials.length < 2) throw new IllegalArgumentException("Shadowsocks 链接缺少加密方式或密码");
        String host = stripBrackets(hostPort.substring(0, colon));
        int port = Integer.parseInt(hostPort.substring(colon + 1));
        JSONObject out = new JSONObject(true);
        out.put("type", "shadowsocks");
        put(out, host, port);
        out.put("method", credentials[0]);
        out.put("password", credentials[1]);
        return new Parsed(name == null ? host + ":" + port : name, "shadowsocks", host, port, out);
    }

    @SuppressWarnings("unchecked")
    static List<Parsed> parseClash(String text) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法识别：请粘贴分享链接，或 Clash 配置里的节点");
        }
        List<Object> items = new ArrayList<>();
        if (loaded instanceof Map && ((Map<String, Object>) loaded).get("proxies") instanceof List) {
            items.addAll((List<Object>) ((Map<String, Object>) loaded).get("proxies"));
        } else if (loaded instanceof List) {
            items.addAll((List<Object>) loaded);
        } else if (loaded instanceof Map) {
            items.add(loaded);
        }
        List<Parsed> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map)) continue;
            try {
                result.add(clashProxy(new JSONObject((Map<String, Object>) item)));
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException(errors.isEmpty() ? "没有找到 Clash 节点" : errors.get(0));
        return result;
    }

    private static Parsed clashProxy(JSONObject c) {
        String type = String.valueOf(c.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
        String host = require(c.getString("server"), "Clash 节点缺少 server");
        int port = c.getIntValue("port");
        if (port < 1) throw new IllegalArgumentException("Clash 节点缺少 port");
        String name = firstNonBlank(c.getString("name"), host + ":" + port);
        String sni = firstNonBlank(c.getString("servername"), c.getString("sni"), host);
        boolean insecure = c.getBooleanValue("skip-cert-verify");
        String alpn = c.get("alpn") instanceof List ? String.join(",", c.getJSONArray("alpn").toJavaList(String.class)) : c.getString("alpn");
        JSONObject out = new JSONObject(true);
        String protocol = type;
        switch (type) {
            case "vless": {
                out.put("type", "vless");
                put(out, host, port);
                out.put("uuid", require(c.getString("uuid"), "VLESS 节点缺少 uuid"));
                if (notBlank(c.getString("flow"))) out.put("flow", c.getString("flow"));
                JSONObject reality = c.getJSONObject("reality-opts");
                if (c.getBooleanValue("tls") || reality != null) {
                    JSONObject tls = tls(sni, insecure, alpn);
                    if (notBlank(c.getString("client-fingerprint"))) tls.put("utls", utls(c.getString("client-fingerprint")));
                    if (reality != null) {
                        JSONObject r = new JSONObject(true);
                        r.put("enabled", true);
                        r.put("public_key", require(reality.getString("public-key"), "REALITY 节点缺少 public-key"));
                        if (notBlank(reality.getString("short-id"))) r.put("short_id", reality.getString("short-id"));
                        tls.put("reality", r);
                        if (!tls.containsKey("utls")) tls.put("utls", utls("chrome"));
                    }
                    out.put("tls", tls);
                }
                clashTransport(out, c);
                break;
            }
            case "vmess": {
                out.put("type", "vmess");
                put(out, host, port);
                out.put("uuid", require(c.getString("uuid"), "VMess 节点缺少 uuid"));
                out.put("security", firstNonBlank(c.getString("cipher"), "auto"));
                out.put("alter_id", c.getIntValue("alterId"));
                if (c.getBooleanValue("tls")) out.put("tls", tls(sni, insecure, alpn));
                clashTransport(out, c);
                break;
            }
            case "trojan": {
                out.put("type", "trojan");
                put(out, host, port);
                out.put("password", require(c.getString("password"), "Trojan 节点缺少 password"));
                out.put("tls", tls(sni, insecure, alpn));
                clashTransport(out, c);
                break;
            }
            case "ss": {
                if (c.get("plugin") != null) throw new IllegalArgumentException("暂不支持带插件的 Shadowsocks 节点");
                protocol = "shadowsocks";
                out.put("type", "shadowsocks");
                put(out, host, port);
                out.put("method", require(c.getString("cipher"), "Shadowsocks 节点缺少 cipher"));
                out.put("password", require(c.getString("password"), "Shadowsocks 节点缺少 password"));
                break;
            }
            case "hysteria2": {
                out.put("type", "hysteria2");
                put(out, host, port);
                out.put("password", require(firstNonBlank(c.getString("password"), c.getString("auth")), "Hysteria2 节点缺少 password"));
                if (notBlank(c.getString("obfs"))) {
                    JSONObject obfs = new JSONObject(true);
                    obfs.put("type", c.getString("obfs"));
                    obfs.put("password", firstNonBlank(c.getString("obfs-password"), ""));
                    out.put("obfs", obfs);
                }
                out.put("tls", tls(sni, insecure, alpn));
                break;
            }
            case "tuic": {
                out.put("type", "tuic");
                put(out, host, port);
                out.put("uuid", require(c.getString("uuid"), "TUIC 节点缺少 uuid"));
                out.put("password", require(c.getString("password"), "TUIC 节点缺少 password"));
                out.put("congestion_control", firstNonBlank(c.getString("congestion-controller"), "bbr"));
                out.put("tls", tls(sni, insecure, firstNonBlank(alpn, "h3")));
                break;
            }
            case "anytls": {
                out.put("type", "anytls");
                put(out, host, port);
                out.put("password", require(c.getString("password"), "AnyTLS 节点缺少 password"));
                out.put("tls", tls(sni, insecure, alpn));
                break;
            }
            case "socks5": {
                out.put("type", "socks");
                out.put("version", "5");
                put(out, host, port);
                if (notBlank(c.getString("username"))) out.put("username", c.getString("username"));
                if (notBlank(c.getString("password"))) out.put("password", c.getString("password"));
                break;
            }
            case "http": {
                out.put("type", "http");
                put(out, host, port);
                if (notBlank(c.getString("username"))) out.put("username", c.getString("username"));
                if (notBlank(c.getString("password"))) out.put("password", c.getString("password"));
                if (c.getBooleanValue("tls")) out.put("tls", tls(sni, insecure, alpn));
                break;
            }
            default:
                throw new IllegalArgumentException("暂不支持的 Clash 节点类型：" + type);
        }
        return new Parsed(name, protocol, host, port, out);
    }

    private static void clashTransport(JSONObject out, JSONObject c) {
        String network = firstNonBlank(c.getString("network"), "tcp");
        JSONObject ws = c.getJSONObject("ws-opts");
        JSONObject grpc = c.getJSONObject("grpc-opts");
        JSONObject h2 = c.getJSONObject("h2-opts");
        String path = ws != null ? ws.getString("path") : h2 != null ? h2.getString("path") : null;
        String hostHeader = ws != null && ws.getJSONObject("headers") != null ? ws.getJSONObject("headers").getString("Host") : null;
        transport(out, network, path, hostHeader, grpc == null ? null : grpc.getString("grpc-service-name"));
    }

    private static void transport(JSONObject out, String network, String path, String host, String serviceName) {
        String type = network == null ? "tcp" : network.toLowerCase(Locale.ROOT);
        switch (type) {
            case "tcp":
            case "raw":
            case "":
                return;
            case "ws":
            case "httpupgrade": {
                JSONObject t = new JSONObject(true);
                t.put("type", type);
                if (notBlank(path)) t.put("path", path);
                if (notBlank(host)) {
                    if ("ws".equals(type)) t.put("headers", Map.of("Host", host));
                    else t.put("host", host);
                }
                out.put("transport", t);
                return;
            }
            case "grpc": {
                JSONObject t = new JSONObject(true);
                t.put("type", "grpc");
                if (notBlank(serviceName)) t.put("service_name", serviceName);
                out.put("transport", t);
                return;
            }
            case "h2":
            case "http": {
                JSONObject t = new JSONObject(true);
                t.put("type", "http");
                if (notBlank(path)) t.put("path", path);
                if (notBlank(host)) t.put("host", JSONArray.parseArray(JSON.toJSONString(List.of(host))));
                out.put("transport", t);
                return;
            }
            default:
                throw new IllegalArgumentException("出口暂不支持 " + network + " 传输方式");
        }
    }

    private static JSONObject tls(String serverName, boolean insecure, String alpn) {
        JSONObject tls = new JSONObject(true);
        tls.put("enabled", true);
        if (notBlank(serverName)) tls.put("server_name", serverName);
        if (insecure) tls.put("insecure", true);
        if (notBlank(alpn)) tls.put("alpn", List.of(alpn.split(",")));
        return tls;
    }

    private static JSONObject utls(String fingerprint) {
        JSONObject utls = new JSONObject(true);
        utls.put("enabled", true);
        utls.put("fingerprint", fingerprint);
        return utls;
    }

    private static void put(JSONObject out, String host, int port) {
        out.put("server", host);
        out.put("server_port", port);
    }

    private static void credentials(JSONObject out, String userInfo, String userKey, String passwordKey) {
        if (userInfo == null || userInfo.isEmpty()) return;
        String[] parts = userInfo.split(":", 2);
        out.put(userKey, parts[0]);
        if (parts.length > 1) out.put(passwordKey, parts[1]);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) result.put(decode(pair), "");
            else result.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return result;
    }

    private static String decodeBase64(String value) {
        String text = value.trim().replace('-', '+').replace('_', '/');
        int pad = (4 - text.length() % 4) % 4;
        try {
            return new String(Base64.getDecoder().decode(text + "=".repeat(pad)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("链接里的 Base64 内容无法解码");
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private static String stripBrackets(String host) {
        return host == null ? null : host.replaceAll("^\\[|]$", "");
    }

    private static boolean truthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String require(String value, String message) {
        if (!notBlank(value)) throw new IllegalArgumentException(message);
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (notBlank(value)) return value;
        return null;
    }

    private static String abbreviate(String link) {
        return link.length() > 40 ? link.substring(0, 40) + "…" : link;
    }
}
