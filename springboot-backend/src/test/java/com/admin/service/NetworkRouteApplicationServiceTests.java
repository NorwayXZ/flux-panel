package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkRouteApplicationServiceTests {

    @Test
    void buildsRealityClientUriForIpv6Entry() {
        Map<String, Object> application = new HashMap<>();
        application.put("name", "B-C-D 出口");
        application.put("entryHost", "2001:db8::10");
        application.put("listenPort", 443);
        JSONObject config = new JSONObject();
        config.put("clientId", "08e2b879-b30c-4ff2-b725-d709deebef4e");
        config.put("serverName", "www.cloudflare.com");
        config.put("publicKey", "public-key");
        config.put("shortId", "a1b2c3d4");

        assertEquals("vless://08e2b879-b30c-4ff2-b725-d709deebef4e@[2001:db8::10]:443"
                        + "?encryption=none&flow=xtls-rprx-vision&security=reality&type=tcp&headerType=none"
                        + "&sni=www.cloudflare.com&fp=chrome&pbk=public-key&sid=a1b2c3d4#B-C-D%20%E5%87%BA%E5%8F%A3",
                NetworkRouteApplicationService.realityClientUri(application, config));
    }

    @Test
    void buildsCloudFrontXhttpSplitClientUri() {
        Map<String, Object> application = new HashMap<>();
        application.put("name", "AWS-HK"); application.put("entryHost", "18.162.196.113"); application.put("listenPort", 10086);
        JSONObject config = new JSONObject();
        config.put("clientId", "9f0306a4-ac8c-470b-b2db-5ea2da71f7f7"); config.put("path", "/aws/"); config.put("mode", "auto");
        config.put("xPaddingBytes", "100-1000"); config.put("uploadDomain", "upload.cloudfront.net"); config.put("downloadDomain", "download.cloudfront.net");
        String uri = NetworkRouteApplicationService.xhttpClientUri(application, config);
        org.junit.jupiter.api.Assertions.assertTrue(uri.startsWith("vless://9f0306a4-ac8c-470b-b2db-5ea2da71f7f7@upload.cloudfront.net:443?"));
        org.junit.jupiter.api.Assertions.assertTrue(uri.contains("type=xhttp"));
        org.junit.jupiter.api.Assertions.assertTrue(uri.contains("downloadSettings"));
        org.junit.jupiter.api.Assertions.assertTrue(uri.endsWith("#AWS-HK"));
    }
}
