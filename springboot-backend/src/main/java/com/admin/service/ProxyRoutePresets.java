package com.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in domain groups for routing rules. They ship with the panel, so nodes never need to
 * download rule sets from GitHub, which is often unreachable from mainland China.
 */
public final class ProxyRoutePresets {

    public static final class Preset {
        public final String key;
        public final String name;
        public final List<String> domainSuffix;

        Preset(String key, String name, List<String> domainSuffix) {
            this.key = key;
            this.name = name;
            this.domainSuffix = domainSuffix;
        }
    }

    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

    private static void add(String key, String name, String... domains) {
        PRESETS.put(key, new Preset(key, name, List.of(domains)));
    }

    static {
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
        return PRESETS.get(key);
    }

    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Preset preset : PRESETS.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", preset.key);
            item.put("name", preset.name);
            item.put("domains", preset.domainSuffix);
            result.add(item);
        }
        return result;
    }
}
