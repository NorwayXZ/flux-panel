package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.ProxyExitService;
import com.admin.service.ProxyRulesetService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Custom exits that private proxies can route chosen traffic through. */
@RestController
@RequestMapping("/api/v1/proxy-exit")
public class ProxyExitController {
    private final ProxyExitService service;
    private final ProxyRulesetService rulesets;

    public ProxyExitController(ProxyExitService service, ProxyRulesetService rulesets) {
        this.service = service;
        this.rulesets = rulesets;
    }

    /** Built-in domain groups, their sizes and when they were last refreshed. */
    @PostMapping("/presets")
    public R presets() {
        return R.ok(rulesets.status());
    }

    @RequireRole
    @LogAnnotation
    @PostMapping("/presets/refresh")
    public R refreshPresets() {
        return rulesets.refreshNow();
    }

    @PostMapping("/list")
    public R list() {
        return service.list();
    }

    @PostMapping("/parse")
    public R parse(@RequestBody Map<String, Object> body) {
        return service.parse(text(body, "text"));
    }

    @LogAnnotation
    @PostMapping("/create")
    public R create(@RequestBody Map<String, Object> body) {
        return service.create(text(body, "text"), text(body, "name"));
    }

    @LogAnnotation
    @PostMapping("/rename")
    public R rename(@RequestBody Map<String, Object> body) {
        return service.rename(id(body, "id"), text(body, "name"));
    }

    @LogAnnotation
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> body) {
        return service.delete(id(body, "id"));
    }

    @PostMapping("/test")
    public R test(@RequestBody Map<String, Object> body) {
        return service.test(id(body, "id"), id(body, "nodeId"));
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : value.toString();
    }

    private static Long id(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null || value.toString().isBlank() ? null : Long.valueOf(value.toString());
    }
}
