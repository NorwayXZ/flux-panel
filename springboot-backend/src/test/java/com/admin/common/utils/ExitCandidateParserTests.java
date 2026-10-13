package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitCandidateParserTests {
    private static final String BODY = Base64.getEncoder().encodeToString("certificate-or-key-bytes-for-tests".getBytes(StandardCharsets.UTF_8));
    private static final String CERTIFICATE = "-----BEGIN CERTIFICATE-----\n" + BODY + "\n-----END CERTIFICATE-----";
    private static final String KEY = "-----BEGIN RSA PRIVATE KEY-----\n" + BODY + "\n-----END RSA PRIVATE KEY-----";

    private static String config(String remote, String proto, String cipher, String auth, String extra) {
        return "dev tun\r\nproto " + proto + "\r\nremote " + remote + "\r\ncipher " + cipher + "\r\ndata-ciphers " + cipher
                + "\r\nauth " + auth + "\r\nresolv-retry infinite\r\nnobind\r\nclient\r\nverb 3\r\n" + extra
                + "<ca>\r\n" + CERTIFICATE + "\r\n</ca>\r\n<cert>\r\n" + CERTIFICATE + "\r\n</cert>\r\n<key>\r\n" + KEY + "\r\n</key>\r\n";
    }

    private static String listLine(String country, String config) {
        return "vpn123,60.137.217.108,1500000,12,88000000,Japan," + country + ",24,3600000,100,2000000,2weeks,Operator's PC,,"
                + Base64.getEncoder().encodeToString(config.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void extractsOnlyTheConnectionFields() {
        ExitCandidateParser.Candidate candidate = ExitCandidateParser.parseOpenVpnConfig(
                config("60.137.217.108 1648", "udp", "AES-128-CBC", "SHA1", ""));
        assertNotNull(candidate);
        assertEquals("60.137.217.108", candidate.getHost());
        assertEquals(1648, candidate.getPort());
        assertEquals("udp", candidate.getProto());
        assertEquals("AES-128-CBC", candidate.getCipher());
        assertEquals("SHA1", candidate.getAuth());
        assertTrue(candidate.getCa().startsWith("-----BEGIN CERTIFICATE-----\n"));
        assertTrue(candidate.getKey().contains("RSA PRIVATE KEY"));
    }

    @Test
    void directivesThatRunCommandsNeverReachTheResult() {
        ExitCandidateParser.Candidate candidate = ExitCandidateParser.parseOpenVpnConfig(
                config("60.137.217.108 1648", "tcp", "AES-128-CBC", "SHA1", "script-security 3\r\nup /bin/sh -c 'id'\r\nplugin /tmp/x.so\r\n"));
        assertNotNull(candidate);
        String everything = candidate.getHost() + candidate.getProto() + candidate.getCipher() + candidate.getAuth()
                + candidate.getCa() + candidate.getCert() + candidate.getKey();
        assertFalse(everything.contains("script-security"));
        assertFalse(everything.contains("/bin/sh"));
        assertFalse(everything.contains("plugin"));
    }

    @Test
    void rejectsAnythingUnexpected() {
        assertNull(ExitCandidateParser.parseOpenVpnConfig(config("vpn.example.com 443", "tcp", "AES-128-CBC", "SHA1", "")), "host name");
        assertNull(ExitCandidateParser.parseOpenVpnConfig(config("10.0.0.5 443", "tcp", "AES-128-CBC", "SHA1", "")), "private address");
        assertNull(ExitCandidateParser.parseOpenVpnConfig(config("127.0.0.1 443", "tcp", "AES-128-CBC", "SHA1", "")), "loopback");
        assertNull(ExitCandidateParser.parseOpenVpnConfig(config("100.64.1.1 443", "tcp", "AES-128-CBC", "SHA1", "")), "carrier-grade NAT");
        assertNull(ExitCandidateParser.parseOpenVpnConfig(config("60.137.217.108 70000", "tcp", "AES-128-CBC", "SHA1", "")), "port");
        assertNull(ExitCandidateParser.parseOpenVpnConfig(config("60.137.217.108 443", "icmp", "AES-128-CBC", "SHA1", "")), "protocol");
        assertNull(ExitCandidateParser.parseOpenVpnConfig(config("60.137.217.108 443", "tcp", "BF-CBC", "SHA1", "")), "cipher");
        assertNull(ExitCandidateParser.parseOpenVpnConfig(config("60.137.217.108 443", "tcp", "AES-128-CBC", "MD5", "")), "digest");
        assertNull(ExitCandidateParser.parseOpenVpnConfig(config("60.137.217.108 443", "tcp", "AES-128-CBC", "SHA1", "auth-user-pass\r\n")),
                "needs credentials");
        assertNull(ExitCandidateParser.parseOpenVpnConfig("remote 60.137.217.108 443\nproto tcp\ncipher AES-128-CBC\nauth SHA1\n"), "no CA");
        assertNull(ExitCandidateParser.parseOpenVpnConfig(null));
    }

    @Test
    void pemBlocksMustBeCleanAndOfTheExpectedKind() {
        assertNotNull(ExitCandidateParser.pem(CERTIFICATE, "CERTIFICATE"));
        assertNotNull(ExitCandidateParser.pem(CERTIFICATE + "\n" + CERTIFICATE, "CERTIFICATE"));
        assertNull(ExitCandidateParser.pem(CERTIFICATE + "\nup /bin/sh", "CERTIFICATE"), "trailing directive");
        assertNull(ExitCandidateParser.pem("script-security 3\n" + CERTIFICATE, "CERTIFICATE"), "leading directive");
        assertNull(ExitCandidateParser.pem(KEY, "CERTIFICATE"), "wrong kind");
        assertNull(ExitCandidateParser.pem("-----BEGIN CERTIFICATE-----\n%%%\n-----END CERTIFICATE-----", "CERTIFICATE"), "not base64");
        assertNull(ExitCandidateParser.pem("", "CERTIFICATE"));
    }

    @Test
    void parsesTheListAndSkipsBrokenRows() {
        String good = listLine("JP", config("60.137.217.108 1648", "udp", "AES-128-CBC", "SHA1", ""));
        String hostName = listLine("KR", config("vpn.example.com 443", "tcp", "AES-128-CBC", "SHA1", ""));
        String badCountry = listLine("Japan", config("60.137.217.109 1648", "udp", "AES-128-CBC", "SHA1", ""));
        String body = "*vpn_servers\r\n#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\r\n"
                + good + "\r\n" + hostName + "\r\n" + badCountry + "\r\nshort,row\r\n*\r\n";
        List<ExitCandidateParser.Candidate> candidates = ExitCandidateParser.parseVpnGateList(body);
        assertEquals(1, candidates.size());
        ExitCandidateParser.Candidate candidate = candidates.get(0);
        assertEquals("JP", candidate.getCountry());
        assertEquals(1_500_000L, candidate.getScore());
        assertEquals(24, candidate.getSessions());
        assertEquals(88_000_000L, candidate.getSpeed());
        assertEquals(3_600_000L, candidate.getUptime());
        assertTrue(ExitCandidateParser.parseVpnGateList(null).isEmpty());
        assertTrue(ExitCandidateParser.parseVpnGateList("<html>blocked</html>").isEmpty());
    }
}
