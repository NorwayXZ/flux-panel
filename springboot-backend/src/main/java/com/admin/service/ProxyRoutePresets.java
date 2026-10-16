package com.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain groups for routing rules. Each group ships with a built-in list, so it works without
 * network access, and most also name the v2fly domain-list-community lists they are refreshed
 * from (see ProxyRulesetService). Nodes never download rule sets themselves.
 */
public final class ProxyRoutePresets {

    public static final class Preset {
        public final String key;
        public final String name;
        public final List<String> domainSuffix;
        public final List<String> domain;
        public final List<String> domainKeyword;
        /** v2fly list names this group is refreshed from; empty when it stays built-in. */
        public final List<String> sources;
        /** When the list was last refreshed, or null for the built-in list. */
        public final Long updatedAt;

        Preset(String key, String name, List<String> domainSuffix, List<String> domain, List<String> domainKeyword,
               List<String> sources, Long updatedAt) {
            this.key = key;
            this.name = name;
            this.domainSuffix = domainSuffix;
            this.domain = domain;
            this.domainKeyword = domainKeyword;
            this.sources = sources;
            this.updatedAt = updatedAt;
        }

        public int size() {
            return domainSuffix.size() + domain.size() + domainKeyword.size();
        }
    }

    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();
    /** Refreshed lists by group key, replacing the built-in ones. */
    private static volatile Map<String, Preset> refreshed = Map.of();

    private static final Map<String, List<String>> SOURCES = Map.ofEntries(
            Map.entry("ai", List.of("category-ai-!cn")),
            Map.entry("openai", List.of("openai")),
            Map.entry("claude", List.of("anthropic")),
            Map.entry("gemini", List.of("google-deepmind")),
            Map.entry("google", List.of("google")),
            Map.entry("youtube", List.of("youtube")),
            Map.entry("netflix", List.of("netflix")),
            Map.entry("disney", List.of("disney")),
            Map.entry("spotify", List.of("spotify")),
            Map.entry("telegram", List.of("telegram")),
            Map.entry("twitter", List.of("twitter")),
            Map.entry("meta", List.of("facebook", "instagram", "whatsapp")),
            Map.entry("tiktok", List.of("tiktok")),
            Map.entry("github", List.of("github")),
            Map.entry("microsoft", List.of("microsoft", "bing")),
            Map.entry("apple", List.of("apple")),
            Map.entry("steam", List.of("steam")),
            Map.entry("discord", List.of("discord")));

    private static void add(String key, String name, String... domains) {
        PRESETS.put(key, new Preset(key, name, List.of(domains), List.of(), List.of(), SOURCES.getOrDefault(key, List.of()), null));
    }

    static {
        add("ai", "AI 服务合集", "openai.com", "chatgpt.com", "anthropic.com", "claude.ai", "gemini.google.com",
                "aistudio.google.com", "perplexity.ai", "x.ai", "grok.com", "mistral.ai", "poe.com", "character.ai");
        add("openai", "ChatGPT / OpenAI", "openai.com", "chatgpt.com", "chat.com", "oaistatic.com", "oaiusercontent.com",
                "openaiapi-site.azureedge.net", "sora.com", "auth0.com");
        add("claude", "Claude / Anthropic", "anthropic.com", "claude.ai", "claude.com", "claudeusercontent.com");
        add("gemini", "Gemini / Google AI", "gemini.google.com", "aistudio.google.com", "generativelanguage.googleapis.com",
                "bard.google.com", "makersuite.google.com", "deepmind.google", "notebooklm.google.com");
        add("google", "Google", "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "ggpht.com",
                "googlevideo.com", "gvt1.com", "gvt2.com", "withgoogle.com", "google.com.hk", "googlesyndication.com", "googleadservices.com");
        add("youtube", "YouTube", "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "youtube-nocookie.com", "youtubei.googleapis.com");
        add("netflix", "Netflix", "netflix.com", "netflix.net", "nflxext.com", "nflximg.com", "nflximg.net", "nflxso.net", "nflxvideo.net", "fast.com");
        add("disney", "Disney+", "disneyplus.com", "disney-plus.net", "dssott.com", "bamgrid.com", "disneystreaming.com", "disney.com");
        add("spotify", "Spotify", "spotify.com", "scdn.co", "spotifycdn.com", "spotify.design", "pscdn.co");
        add("telegram", "Telegram", "telegram.org", "t.me", "telegram.me", "tdesktop.com", "telesco.pe", "telegra.ph", "cdn-telegram.org");
        add("twitter", "X / Twitter", "x.com", "twitter.com", "twimg.com", "t.co", "twitpic.com");
        add("meta", "Facebook / Instagram / WhatsApp", "facebook.com", "fbcdn.net", "fb.com", "instagram.com", "cdninstagram.com",
                "whatsapp.com", "whatsapp.net", "messenger.com", "threads.net");
        add("tiktok", "TikTok", "tiktok.com", "tiktokv.com", "tiktokcdn.com", "byteoversea.com", "ibytedtos.com", "muscdn.com");
        add("github", "GitHub", "github.com", "githubusercontent.com", "github.io", "githubassets.com", "ghcr.io", "copilot.microsoft.com");
        add("microsoft", "Microsoft / Bing", "microsoft.com", "bing.com", "live.com", "office.com", "office365.com", "msn.com",
                "windows.net", "sharepoint.com", "onedrive.com", "xbox.com");
        add("apple", "Apple 服务", "apple.com", "icloud.com", "mzstatic.com", "apple-cloudkit.com", "cdn-apple.com", "itunes.com");
        add("steam", "Steam", "steampowered.com", "steamcommunity.com", "steamstatic.com", "steamcontent.com", "steamserver.net");
        add("discord", "Discord", "discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media");
        add("cn", "国内常见网站", "cn", "baidu.com", "qq.com", "weixin.qq.com", "taobao.com", "tmall.com", "jd.com", "alipay.com",
                "aliyun.com", "bilibili.com", "douyin.com", "zhihu.com", "weibo.com", "163.com", "126.com", "sohu.com", "iqiyi.com",
                "youku.com", "meituan.com", "pinduoduo.com", "xiaohongshu.com", "csdn.net", "gitee.com");
    }

    private ProxyRoutePresets() {
    }

    public static Preset get(String key) {
        Preset preset = refreshed.get(key);
        return preset != null ? preset : PRESETS.get(key);
    }

    /** Groups that can be refreshed, with the v2fly lists each is built from. */
    public static Map<String, List<String>> sources() {
        return SOURCES;
    }

    /** Built-in group (ignoring refreshed data), or null. */
    static Preset builtIn(String key) {
        return PRESETS.get(key);
    }

    /** Replaces the refreshed lists; groups missing from the map fall back to built-in. */
    static void useRefreshed(Map<String, Preset> lists) {
        refreshed = Map.copyOf(lists);
    }

    static Preset refreshedPreset(String key, List<String> suffix, List<String> domain, List<String> keyword, long updatedAt) {
        Preset base = PRESETS.get(key);
        return new Preset(key, base == null ? key : base.name, List.copyOf(suffix), List.copyOf(domain), List.copyOf(keyword),
                SOURCES.getOrDefault(key, List.of()), updatedAt);
    }

    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : PRESETS.keySet()) {
            Preset preset = get(key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", preset.key);
            item.put("name", preset.name);
            List<String> sample = new ArrayList<>(preset.domainSuffix);
            sample.addAll(preset.domain);
            item.put("domains", sample.size() > 30 ? sample.subList(0, 30) : sample);
            item.put("count", preset.size());
            item.put("autoUpdate", !preset.sources.isEmpty());
            item.put("updatedAt", preset.updatedAt);
            result.add(item);
        }
        return result;
    }
}
