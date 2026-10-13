package com.admin.common.utils;

import lombok.Data;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 VPN Gate 公开列表。
 *
 * 列表里的 OpenVPN 配置来自不受信任的志愿者，配置文件可以携带在节点上执行命令的指令。
 * 因此这里只提取固定的几个字段并逐一校验，原始配置文本不会下发到任何节点。
 */
public final class ExitCandidateParser {
    public static final Set<String> CIPHERS = Set.of("AES-128-CBC", "AES-192-CBC", "AES-256-CBC",
            "AES-128-GCM", "AES-192-GCM", "AES-256-GCM", "CHACHA20-POLY1305");
    public static final Set<String> AUTH_DIGESTS = Set.of("SHA1", "SHA256", "SHA384", "SHA512");

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern IPV4 = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z ]{1,40})-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END ([A-Z ]{1,40})-----");
    private static final int MAX_PEM_LENGTH = 32 * 1024;

    private ExitCandidateParser() {
    }

    @Data
    public static class Candidate {
        private String country;
        private String host;
        private int port;
        private String proto;
        private String cipher;
        private String auth;
        private String ca;
        private String cert;
        private String key;
        private long score;
        private int sessions;
        private long speed;
        private long uptime;
    }

    /** 解析整份列表，跳过任何一个字段不符合预期的条目。 */
    public static List<Candidate> parseVpnGateList(String body) {
        List<Candidate> result = new ArrayList<>();
        if (body == null) return result;
        for (String line : body.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue;
            String[] fields = line.split(",");
            if (fields.length < 15) continue;
            String country = fields[6].trim().toUpperCase();
            if (!COUNTRY.matcher(country).matches()) continue;
            String config;
            try {
                config = new String(Base64.getMimeDecoder().decode(fields[fields.length - 1].trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Candidate candidate = parseOpenVpnConfig(config);
            if (candidate == null) continue;
            candidate.setCountry(country);
            candidate.setScore(number(fields[2]));
            candidate.setSpeed(number(fields[4]));
            candidate.setSessions((int) Math.min(Integer.MAX_VALUE, number(fields[7])));
            candidate.setUptime(number(fields[8]));
            result.add(candidate);
        }
        return result;
    }

    /** 从一份 OpenVPN 配置里取出连接所需的字段；任何一项不合规都返回 null。 */
    public static Candidate parseOpenVpnConfig(String config) {
        if (config == null) return null;
        String text = config.replace("\r", "");
        // 需要账号密码的出口无法无人值守地连接
        if (Pattern.compile("(?m)^auth-user-pass").matcher(text).find()) return null;
        String[] remote = directive(text, "remote");
        String[] proto = directive(text, "proto");
        String[] cipher = directive(text, "cipher");
        String[] auth = directive(text, "auth");
        if (remote == null || remote.length < 2 || proto == null || cipher == null || auth == null) return null;

        Candidate candidate = new Candidate();
        String host = publicIpv4(remote[0]);
        if (host == null) return null;
        candidate.setHost(host);
        try {
            candidate.setPort(Integer.parseInt(remote[1]));
        } catch (NumberFormatException e) {
            return null;
        }
        if (candidate.getPort() < 1 || candidate.getPort() > 65535) return null;

        String protocol = proto[0].toLowerCase();
        if (protocol.startsWith("tcp")) candidate.setProto("tcp");
        else if (protocol.startsWith("udp")) candidate.setProto("udp");
        else return null;

        candidate.setCipher(cipher[0].toUpperCase());
        candidate.setAuth(auth[0].toUpperCase());
        if (!CIPHERS.contains(candidate.getCipher()) || !AUTH_DIGESTS.contains(candidate.getAuth())) return null;

        candidate.setCa(pem(block(text, "ca"), "CERTIFICATE"));
        if (candidate.getCa() == null) return null;
        String cert = block(text, "cert");
        String key = block(text, "key");
        if (cert != null || key != null) {
            candidate.setCert(pem(cert, "CERTIFICATE"));
            candidate.setKey(pem(key, "PRIVATE KEY"));
            if (candidate.getCert() == null || candidate.getKey() == null) return null;
        }
        return candidate;
    }

    /** 只接受公网 IPv4 字面量，不接受域名，避免解析结果指向内网。 */
    public static String publicIpv4(String value) {
        if (value == null || !IPV4.matcher(value.trim()).matches()) return null;
        try {
            InetAddress address = InetAddress.getByName(value.trim());
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress()) return null;
            byte[] raw = address.getAddress();
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            if (first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)) return null;
            return address.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static String[] directive(String text, String name) {
        Matcher matcher = Pattern.compile("(?m)^" + name + "[ \\t]+([^\\n#;]+)$").matcher(text);
        return matcher.find() ? matcher.group(1).trim().split("\\s+") : null;
    }

    private static String block(String text, String tag) {
        Matcher matcher = Pattern.compile("(?s)<" + tag + ">(.*?)</" + tag + ">").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /** 逐块解码再重新编码，保证输出里只有指定类型的 PEM 内容。 */
    static String pem(String value, String kindSuffix) {
        if (value == null || value.isBlank() || value.length() > MAX_PEM_LENGTH) return null;
        Matcher matcher = PEM_BLOCK.matcher(value);
        StringBuilder output = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            if (!value.substring(end, matcher.start()).isBlank()) return null;
            String type = matcher.group(1);
            if (!type.equals(matcher.group(3)) || !type.endsWith(kindSuffix)) return null;
            byte[] decoded;
            try {
                decoded = Base64.getMimeDecoder().decode(matcher.group(2));
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (decoded.length == 0) return null;
            output.append("-----BEGIN ").append(type).append("-----\n")
                    .append(Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(decoded))
                    .append("\n-----END ").append(type).append("-----\n");
            end = matcher.end();
        }
        if (output.length() == 0 || !value.substring(end).isBlank()) return null;
        return output.toString();
    }

    private static long number(String value) {
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
