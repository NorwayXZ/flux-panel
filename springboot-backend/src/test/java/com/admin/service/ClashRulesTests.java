package com.admin.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClashRulesTests {

    @Test
    void readsDomainProvidersInYaml() {
        ClashRules.Content content = ClashRules.parseRuleSet("payload:\n  - '+.openai.com'\n  - '.chatgpt.com'\n  - 'api.x.ai'\n"
                + "  - '*.oaiusercontent.com'\n  - 'bad domain'\n", "domain");
        assertEquals(List.of("openai.com", "chatgpt.com"), new ArrayList<>(content.set.domainSuffix));
        assertEquals(List.of("api.x.ai"), new ArrayList<>(content.set.domain));
        assertEquals(List.of("^[^.]+\\.oaiusercontent\\.com$"), new ArrayList<>(content.set.domainRegex));
        assertEquals(1, content.skipped);
    }

    @Test
    void readsClassicalTextAndIpLists() {
        ClashRules.Content classical = ClashRules.parseRuleSet("# comment\nDOMAIN-SUFFIX,google.com\nDOMAIN-KEYWORD,youtube\n"
                + "IP-CIDR,8.8.8.0/24,no-resolve\nIP-CIDR6,2001:4860::/32\nDST-PORT,443/8000-9000\nPROCESS-NAME,chrome\n", "classical");
        assertEquals(List.of("google.com"), new ArrayList<>(classical.set.domainSuffix));
        assertEquals(List.of("youtube"), new ArrayList<>(classical.set.domainKeyword));
        assertEquals(List.of("8.8.8.0/24", "2001:4860::/32"), new ArrayList<>(classical.set.ipCidr));
        assertEquals(List.of("443", "8000-9000"), new ArrayList<>(classical.set.port));
        assertEquals(1, classical.skipped);

        ClashRules.Content ips = ClashRules.parseRuleSet("1.0.1.0/24\n1.2.3.4\n999.1.1.1/8\n", "ipcidr");
        assertEquals(List.of("1.0.1.0/24", "1.2.3.4/32"), new ArrayList<>(ips.set.ipCidr));
        assertEquals(1, ips.skipped);

        ClashRules.Content auto = ClashRules.parseRuleSet("example.com\n10.0.0.0/8\nDOMAIN-SUFFIX,a.com\n", "auto");
        assertEquals(1, auto.set.domain.size());
        assertEquals(1, auto.set.ipCidr.size());
        assertEquals(1, auto.set.domainSuffix.size());
    }

    @Test
    void readsSingBoxRuleSetSource() {
        ClashRules.Content content = ClashRules.parseRuleSet("{\"version\":2,\"rules\":[{\"domain_suffix\":[\"netflix.com\"],"
                + "\"ip_cidr\":[\"23.246.0.0/18\"],\"port_range\":[\"1000:2000\"]}]}", "auto");
        assertEquals(List.of("netflix.com"), new ArrayList<>(content.set.domainSuffix));
        assertEquals(List.of("1000-2000"), new ArrayList<>(content.set.port));
    }

    @Test
    void importsAClashConfiguration() {
        String config = String.join("\n",
                "proxies: []",
                "rule-providers:",
                "  ai:",
                "    type: http",
                "    behavior: domain",
                "    url: https://example.com/ai.yaml",
                "    interval: 86400",
                "  bin:",
                "    type: http",
                "    behavior: domain",
                "    format: mrs",
                "    url: https://example.com/bin.mrs",
                "rules:",
                "  - DOMAIN-SUFFIX,openai.com,🇺🇸 美国",
                "  - DOMAIN-SUFFIX,chatgpt.com,🇺🇸 美国",
                "  - DOMAIN-KEYWORD,google,Proxy",
                "  - RULE-SET,ai,🇺🇸 美国",
                "  - GEOSITE,netflix,Proxy",
                "  - GEOIP,LAN,DIRECT",
                "  - IP-CIDR,1.1.1.0/24,REJECT-DROP,no-resolve",
                "  - DST-PORT,22/3389,DIRECT",
                "  - SRC-IP-CIDR,192.168.1.2/32,DIRECT",
                "  - AND,((DOMAIN,a.com),(NETWORK,UDP)),REJECT",
                "  - GEOIP,CN,DIRECT",
                "  - MATCH,Proxy");
        ClashRules.Import parsed = ClashRules.parseImport(config);

        assertEquals(8, parsed.rules.size());
        assertEquals(List.of("openai.com", "chatgpt.com"), parsed.rules.get(0).values);
        assertEquals("🇺🇸 美国", parsed.rules.get(0).policy);
        assertEquals("ruleset", parsed.rules.get(2).match);
        assertEquals("private", parsed.rules.get(4).values.get(0));
        assertEquals("REJECT", parsed.rules.get(5).policy);
        assertTrue(parsed.rules.get(5).noResolve);
        assertEquals(List.of("22", "3389"), parsed.rules.get(6).values);
        assertEquals("cn", parsed.rules.get(7).values.get(0));
        assertEquals("Proxy", parsed.finalPolicy);
        assertEquals(2, parsed.skipped);
        assertEquals(List.of("ai"), new ArrayList<>(parsed.providers.keySet()), "binary providers are left out");
        assertEquals(List.of("🇺🇸 美国", "Proxy", "DIRECT", "REJECT"), new ArrayList<>(parsed.policies));
    }

    @Test
    void importsPlainRuleLines() {
        ClashRules.Import parsed = ClashRules.parseImport("DOMAIN-SUFFIX,a.com,DIRECT\n- DOMAIN-SUFFIX,b.com,DIRECT\nDOMAIN,c.com,X\n");
        assertEquals(2, parsed.rules.size());
        assertEquals(List.of("a.com", "b.com"), parsed.rules.get(0).values);
        assertThrows(IllegalArgumentException.class, () -> ClashRules.parseImport(" "));
    }

    @Test
    void validatesRuleValues() {
        assertEquals("example.com", RouteMatchSet.normalize("domain_suffix", "*.Example.com"));
        assertNull(RouteMatchSet.normalize("domain_regex", "^(?!ads).*\\.com$"), "lookahead is not supported by the router");
        assertEquals("^chat.*\\.com$", RouteMatchSet.normalize("domain_regex", "^chat.*\\.com$"));
        assertEquals("1000-2000", RouteMatchSet.normalize("port", "1000:2000"));
        assertNull(RouteMatchSet.normalize("port", "70000"));
        assertNull(RouteMatchSet.normalize("network", "icmp"));
        assertEquals("private", ProxyRoutingService.validValue("geoip", "LAN"));
        assertEquals("google@cn", ProxyRoutingService.validValue("geosite", "Google@CN"));
        assertNull(ProxyRoutingService.validValue("ruleset", "abc"));
    }

    @Test
    void routerResolvesBeforeTheFirstIpRuleAndSplitsFields() {
        RouteMatchSet list = new RouteMatchSet();
        list.domainSuffix.add("netflix.com");
        list.ipCidr.add("23.246.0.0/18");
        ProxyRuleSourceService.putList("ruleset:5", list);
        List<JSONObject> rules = ProxyRoutingService.rules(ProxyRoutingService.normalizeRules(List.of(
                Map.of("match", "ip_cidr", "values", List.of("1.1.1.0/24"), "exit", "block"),
                Map.of("match", "port", "values", List.of("443", "8000-9000"), "exit", "direct"),
                Map.of("match", "ruleset", "values", List.of("5"), "exit", "custom:3", "resolve", true),
                Map.of("match", "geoip", "values", List.of("lan"), "exit", "direct"))));

        JSONObject request = ProxyRoutingService.routerRequest("r", "node", null, null, rules, Map.of(), "p", Map.of(), true);
        JSONArray routerRules = request.getJSONArray("rules");

        assertEquals("reject", routerRules.getJSONObject(0).getString("action"), "IP rules match addresses without a lookup by default");
        assertEquals(List.of(443), routerRules.getJSONObject(1).getJSONArray("port").toJavaList(Integer.class));
        assertEquals(List.of("8000:9000"), routerRules.getJSONObject(2).getJSONArray("port_range").toJavaList(String.class));
        assertEquals("resolve", routerRules.getJSONObject(3).getString("action"));
        assertEquals("netflix.com", routerRules.getJSONObject(4).getJSONArray("domain_suffix").getString(0));
        assertEquals("custom-3", routerRules.getJSONObject(5).getString("outbound"));
        assertTrue(routerRules.getJSONObject(6).getBooleanValue("ip_is_private"));
        assertEquals(7, routerRules.size(), "only one lookup");
        assertTrue(ProxyRoutingService.needsNewerRouter(request));

        JSONObject plain = ProxyRoutingService.routerRequest("r", "node", null, null, ProxyRoutingService.rules(ProxyRoutingService.normalizeRules(
                List.of(Map.of("match", "domain_suffix", "values", List.of("a.com"), "exit", "direct")))), Map.of(), "p", Map.of());
        assertFalse(ProxyRoutingService.needsNewerRouter(plain));
    }

    @Test
    void rejectsPrivateSubscriptionAddresses() throws Exception {
        assertFalse(ProxyRuleSourceService.publicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(ProxyRuleSourceService.publicAddress(InetAddress.getByName("10.1.2.3")));
        assertFalse(ProxyRuleSourceService.publicAddress(InetAddress.getByName("100.100.1.1")));
        assertFalse(ProxyRuleSourceService.publicAddress(InetAddress.getByName("169.254.169.254")));
        assertFalse(ProxyRuleSourceService.publicAddress(InetAddress.getByName("::ffff:192.168.1.1")));
        assertFalse(ProxyRuleSourceService.publicAddress(InetAddress.getByName("fd00::1")));
        assertTrue(ProxyRuleSourceService.publicAddress(InetAddress.getByName("104.16.0.1")));
        assertTrue(ProxyRuleSourceService.publicAddress(InetAddress.getByName("2606:4700::1")));
    }

    /** With RULESET_LIVE set, reads well-known public rule providers. */
    @Test
    void readsRealProviders() throws Exception {
        if (System.getenv("RULESET_LIVE") == null) return;
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        Map<String, String> sources = Map.of(
                "https://cdn.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/OpenAI/OpenAI.yaml", "classical",
                "https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/proxy.txt", "domain",
                "https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/cncidr.txt", "ipcidr",
                "https://cdn.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/Netflix/Netflix.list", "classical");
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String body = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(source.getKey())).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            ClashRules.Content content = ClashRules.parseRuleSet(body, source.getValue());
            System.out.println(source.getKey() + " -> " + content.set.size() + " (skipped " + content.skipped + ") " + content.set.sample(4));
            assertTrue(content.set.size() > 0);
        }
    }

    @Test
    void recognisesCatalogUrlsInAnyForm() {
        assertEquals("netflix", RuleCatalog.match("https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Clash/Netflix/Netflix.yaml").key);
        assertEquals("netflix", RuleCatalog.match("https://cdn.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/Netflix/Netflix_No_Resolve.yaml").key);
        assertEquals("google", RuleCatalog.match("https://fastly.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/Google/Google.yaml").key);
        assertEquals("cncidr", RuleCatalog.match("https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/cncidr.txt").key);
        assertNull(RuleCatalog.match("https://cdn.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/GoogleFCM/GoogleFCM.yaml"));
        assertNull(RuleCatalog.match("https://example.com/proxy.txt"));
        assertEquals("openai", ProxyRoutingService.validValue("catalog", "openai"));
        assertNull(ProxyRoutingService.validValue("catalog", "nope"));
    }

    /** With RULESET_LIVE set, downloads every catalog entry and checks it parses within the size limit. */
    @Test
    void downloadsTheCatalog() throws Exception {
        if (System.getenv("RULESET_LIVE") == null) return;
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        for (RuleCatalog.Item item : RuleCatalog.items()) {
            java.net.http.HttpResponse<String> response = client.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(item.urls.get(0))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), item.key);
            ClashRules.Content content = ClashRules.parseRuleSet(response.body(), item.behavior);
            System.out.println("catalog " + item.key + " " + content.set.size() + " skipped " + content.skipped);
            assertTrue(content.set.size() > 0, item.key);
        }
    }
}
