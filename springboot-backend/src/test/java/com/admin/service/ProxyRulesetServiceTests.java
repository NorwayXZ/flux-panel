package com.admin.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRulesetServiceTests {

    @AfterEach
    void resetPresets() {
        ProxyRoutePresets.useRefreshed(Map.of());
    }

    private static ProxyRulesetService.Lists parse(Map<String, String> files, String root) {
        ProxyRulesetService.Lists lists = new ProxyRulesetService.Lists();
        ProxyRulesetService.parse(root, name -> {
            String text = files.get(name);
            if (text == null) throw new IllegalStateException("missing " + name);
            return text;
        }, lists, new HashSet<>(), new int[]{0});
        return lists;
    }

    @Test
    void readsV2flyListsWithIncludes() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("openai", "# Main\nopenai.com\nchatgpt.com @cn\nfull:api.example.net\nkeyword:openai\n"
                + "regexp:^chatgpt-\\S+$\nfull:tracker.example.com @ads\ninclude:extra\ninclude:openai\n");
        files.put("extra", "domain:sora.com\n\n  CHAT.com  # comment\n");
        ProxyRulesetService.Lists lists = parse(files, "openai");

        assertEquals(List.of("openai.com", "chatgpt.com", "sora.com", "chat.com"), new ArrayList<>(lists.suffix));
        assertEquals(List.of("api.example.net"), new ArrayList<>(lists.domain));
        assertEquals(List.of("openai"), new ArrayList<>(lists.keyword));
    }

    @Test
    void stopsOnMissingLists() {
        assertThrows(IllegalStateException.class, () -> parse(Map.of("a", "include:b\n"), "a"));
    }

    @Test
    void splitsLargeGroupsIntoSeveralRouterRules() {
        List<String> suffixes = new ArrayList<>();
        for (int i = 0; i < 4500; i++) suffixes.add("d" + i + ".example.com");
        ProxyRoutePresets.useRefreshed(Map.of("apple",
                ProxyRoutePresets.refreshedPreset("apple", suffixes, List.of("full.example.com"), List.of("apple"), 1L)));
        List<JSONObject> rules = ProxyRoutingService.rules(ProxyRoutingService.normalizeRules(List.of(
                Map.of("match", "preset", "values", List.of("apple"), "exit", "direct"))));

        JSONArray routerRules = ProxyRoutingService.routerRequest("r", "node", null, null, rules, Map.of(), "p", Map.of())
                .getJSONArray("rules");

        assertEquals(5, routerRules.size(), "3 suffix chunks + domain + keyword");
        assertEquals(2000, routerRules.getJSONObject(0).getJSONArray("domain_suffix").size());
        assertEquals(500, routerRules.getJSONObject(2).getJSONArray("domain_suffix").size());
        assertEquals("full.example.com", routerRules.getJSONObject(3).getJSONArray("domain").getString(0));
        assertEquals("apple", routerRules.getJSONObject(4).getJSONArray("domain_keyword").getString(0));
        for (int i = 0; i < routerRules.size(); i++) assertEquals("direct", routerRules.getJSONObject(i).getString("outbound"));
    }

    @Test
    void everyAutoUpdatedGroupHasABuiltInFallback() {
        for (String key : ProxyRoutePresets.sources().keySet()) {
            assertTrue(ProxyRoutePresets.builtIn(key) != null, key);
            assertFalse(ProxyRoutePresets.builtIn(key).domainSuffix.isEmpty(), key);
        }
    }

    /** With RULESET_LIVE set, downloads the real lists to check names and format. */
    @Test
    void downloadsTheRealLists() {
        if (System.getenv("RULESET_LIVE") == null) return;
        HttpClient client = HttpClient.newHttpClient();
        for (Map.Entry<String, List<String>> entry : ProxyRoutePresets.sources().entrySet()) {
            ProxyRulesetService.Lists lists = new ProxyRulesetService.Lists();
            for (String source : entry.getValue()) {
                ProxyRulesetService.parse(source, name -> {
                    try {
                        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(ProxyRulesetService.MIRRORS.get(0) + name.replace("!", "%21"))).build(),
                                HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() != 200) throw new IllegalStateException(name + " HTTP " + response.statusCode());
                        return response.body();
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage());
                    }
                }, lists, new HashSet<>(), new int[]{0});
            }
            System.out.println(entry.getKey() + " " + lists.suffix.size() + "/" + lists.domain.size() + "/" + lists.keyword.size());
            assertTrue(lists.size() > 0, entry.getKey());
        }
    }
}
