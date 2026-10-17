package com.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Well-known community rule sets offered to every user. The panel downloads each one once,
 * shares it between all proxies that use it, and checks for updates every 12 hours.
 */
public final class RuleCatalog {
    static final long REFRESH_MS = 12L * 3_600_000L;

    public static final class Item {
        public final String key;
        public final String name;
        public final String category;
        public final String source;
        public final String behavior;
        /** Mirrors, tried in order. */
        public final List<String> urls;
        /** Matches the same list in another URL form, e.g. a different mirror or file variant. */
        final String marker;

        Item(String key, String name, String category, String source, String behavior, List<String> urls, String marker) {
            this.key = key;
            this.name = name;
            this.category = category;
            this.source = source;
            this.behavior = behavior;
            this.urls = urls;
            this.marker = marker;
        }
    }

    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();

    /** blackmatrix7/ios_rule_script; large lists keep their full content in NAME_Classical.yaml. */
    private static void bm7(String key, String name, String category, String dir, boolean classicalFile) {
        String file = dir + "/" + dir + (classicalFile ? "_Classical" : "") + ".yaml";
        ITEMS.put(key, new Item(key, name, category, "blackmatrix7", "classical", List.of(
                "https://cdn.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/" + file,
                "https://fastly.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/" + file,
                "https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Clash/" + file),
                "blackmatrix7/ios_rule_script|/rule/clash/" + dir.toLowerCase(Locale.ROOT) + "/"));
    }

    /** Loyalsoldier/clash-rules. */
    private static void loyalsoldier(String key, String name, String category, String file, String behavior) {
        ITEMS.put(key, new Item(key, name, category, "Loyalsoldier", behavior, List.of(
                "https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/" + file + ".txt",
                "https://fastly.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/" + file + ".txt",
                "https://raw.githubusercontent.com/Loyalsoldier/clash-rules/release/" + file + ".txt"),
                "loyalsoldier/clash-rules|/" + file + ".txt"));
    }

    static {
        bm7("openai", "OpenAI / ChatGPT", "AI", "OpenAI", false);
        bm7("claude", "Claude", "AI", "Claude", false);
        bm7("gemini", "Gemini", "AI", "Gemini", false);
        bm7("copilot", "Copilot", "AI", "Copilot", false);
        bm7("netflix", "Netflix", "流媒体", "Netflix", true);
        bm7("disney", "Disney+", "流媒体", "Disney", false);
        bm7("youtube", "YouTube", "流媒体", "YouTube", false);
        bm7("spotify", "Spotify", "流媒体", "Spotify", false);
        bm7("hbo", "HBO", "流媒体", "HBO", false);
        bm7("primevideo", "Prime Video", "流媒体", "PrimeVideo", false);
        bm7("tiktok", "TikTok", "流媒体", "TikTok", false);
        bm7("twitch", "Twitch", "流媒体", "Twitch", false);
        bm7("bilibili", "哔哩哔哩", "流媒体", "BiliBili", false);
        bm7("telegram", "Telegram", "社交通讯", "Telegram", false);
        bm7("twitter", "X / Twitter", "社交通讯", "Twitter", false);
        bm7("facebook", "Facebook", "社交通讯", "Facebook", false);
        bm7("instagram", "Instagram", "社交通讯", "Instagram", false);
        bm7("discord", "Discord", "社交通讯", "Discord", false);
        bm7("google", "Google", "常用服务", "Google", false);
        bm7("microsoft", "Microsoft", "常用服务", "Microsoft", false);
        bm7("apple", "Apple", "常用服务", "Apple", true);
        bm7("github", "GitHub", "常用服务", "GitHub", false);
        bm7("steam", "Steam", "常用服务", "Steam", false);
        bm7("epic", "Epic Games", "常用服务", "Epic", false);
        bm7("cloudflare", "Cloudflare", "常用服务", "Cloudflare", false);
        bm7("amazon", "Amazon", "常用服务", "Amazon", false);
        bm7("paypal", "PayPal", "常用服务", "PayPal", false);
        bm7("ads-lite", "广告拦截（精简）", "广告与隐私", "AdvertisingLite", true);
        bm7("privacy", "隐私追踪", "广告与隐私", "Privacy", true);
        loyalsoldier("proxy", "常见境外网站", "综合", "proxy", "domain");
        loyalsoldier("gfw", "GFW 列表", "综合", "gfw", "domain");
        loyalsoldier("tld-not-cn", "非中国顶级域名", "综合", "tld-not-cn", "domain");
        bm7("china", "国内常用服务", "综合", "China", true);
        loyalsoldier("cncidr", "中国大陆 IP", "IP 段", "cncidr", "ipcidr");
        loyalsoldier("telegramcidr", "Telegram IP", "IP 段", "telegramcidr", "ipcidr");
        loyalsoldier("lancidr", "局域网 IP", "IP 段", "lancidr", "ipcidr");
    }

    private RuleCatalog() {
    }

    public static Item get(String key) {
        return key == null ? null : ITEMS.get(key);
    }

    public static List<Item> items() {
        return new ArrayList<>(ITEMS.values());
    }

    /** The catalog entry a rule-provider URL points at, in any mirror or file variant, or null. */
    public static Item match(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase(Locale.ROOT).replace("@master", "/master").replace("@release", "/release");
        for (Item item : ITEMS.values()) {
            String[] parts = item.marker.split("\\|");
            if (lower.contains(parts[0]) && lower.contains(parts[1])) return item;
        }
        return null;
    }
}
