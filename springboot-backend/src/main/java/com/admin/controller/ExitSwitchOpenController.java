package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.service.PrivateProxyService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 远程切换出口：不登录面板，打开一个带令牌的链接就换一个出口 IP。
 * 令牌只能触发“换到已就绪的备用出口”这一个动作，不能读取或修改其他内容。
 */
@RestController
@RequestMapping("/api/v1/open_api")
public class ExitSwitchOpenController {
    private final PrivateProxyService service;

    public ExitSwitchOpenController(PrivateProxyService service) {
        this.service = service;
    }

    @GetMapping("/exit-switch")
    public ResponseEntity<?> switchExit(@RequestParam(value = "token", required = false) String token,
                                        @RequestParam(value = "format", required = false) String format) {
        R result = service.switchExitByToken(token);
        if ("json".equalsIgnoreCase(format)) return ResponseEntity.ok(result);
        String text;
        if (result.getCode() == 0 && result.getData() instanceof Map) {
            Map<?, ?> view = (Map<?, ?>) result.getData();
            Object owner = view.get("exitOwner");
            text = "已切换到 " + view.get("exitIp") + (owner == null || owner.toString().isBlank() ? "" : "（" + owner + "）");
        } else {
            text = "切换失败：" + result.getMsg();
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body(text);
    }
}
