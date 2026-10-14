package com.admin.controller;

import com.admin.service.PrivateProxyService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 私人代理订阅：客户端定时拉取，得到该用户全部运行中的代理。
 * 令牌只能读取这一份订阅，用户可以随时在面板里换一个令牌让旧链接失效。
 */
@RestController
@RequestMapping("/api/v1/open_api")
public class PrivateProxySubscriptionController {
    private final PrivateProxyService service;

    public PrivateProxySubscriptionController(PrivateProxyService service) {
        this.service = service;
    }

    @GetMapping("/proxy-sub")
    public ResponseEntity<String> subscription(@RequestParam(value = "token", required = false) String token,
                                               @RequestParam(value = "format", required = false) String format,
                                               @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        PrivateProxyService.SubscriptionContent content = service.subscriptionContent(token, format, userAgent);
        if (content == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body("订阅链接无效或已重置");
        }
        String[] type = content.contentType.split("/");
        return ResponseEntity.ok()
                .contentType(new MediaType(type[0], type[1], StandardCharsets.UTF_8))
                .header("subscription-userinfo", content.userInfo)
                .header("profile-update-interval", "12")
                .header("profile-title", "base64:" + java.util.Base64.getEncoder().encodeToString("云巢".getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + content.filename)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content.body);
    }
}
