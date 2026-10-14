package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.ExitPoolService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/exit-pool")
public class ExitPoolController {
    private final ExitPoolService service;

    public ExitPoolController(ExitPoolService service) {
        this.service = service;
    }

    @RequireRole @PostMapping("/countries") public R countries() { return service.countries(); }
    @RequireRole @PostMapping("/overview") public R overview() { return service.overview(); }
    @RequireRole @PostMapping("/events") public R events(@RequestBody Map<String, Object> body) { return service.events(id(body, "groupId")); }
    @RequireRole @LogAnnotation @PostMapping("/refresh") public R refresh() { return service.refresh(); }
    @RequireRole @LogAnnotation @PostMapping("/block") public R block(@RequestBody Map<String, Object> body) { return service.block(id(body, "memberId")); }

    @RequireRole @LogAnnotation @PostMapping("/switch") public R switchExit(@RequestBody Map<String, Object> body) {
        return service.switchExit(id(body, "groupId"), id(body, "memberId"), "手动切换");
    }
    @RequireRole @PostMapping("/candidates") public R candidates(@RequestBody Map<String, Object> body) { return service.candidates(id(body, "groupId")); }
    @RequireRole @LogAnnotation @PostMapping("/prefer") public R prefer(@RequestBody Map<String, Object> body) {
        return service.prefer(id(body, "groupId"), id(body, "candidateId"));
    }

    @RequireRole @PostMapping("/country-candidates") public R countryCandidates(@RequestBody Map<String, Object> body) {
        return service.countryCandidates(body == null || body.get("country") == null ? null : body.get("country").toString());
    }
    @RequireRole @LogAnnotation @PostMapping("/reputation") public R reputation(@RequestBody Map<String, Object> body) {
        return service.reputation(body == null || body.get("ip") == null ? null : body.get("ip").toString());
    }

    @RequireRole @PostMapping("/settings") public R settings() { return service.settings(); }
    @RequireRole @LogAnnotation @PostMapping("/settings/save") public R saveSettings(@RequestBody Map<String, Object> body) {
        Object value = body == null ? null : body.get("maxFraudScore");
        try {
            return service.saveSettings(value == null || value.toString().isBlank() ? null : Integer.valueOf(value.toString()));
        } catch (NumberFormatException e) {
            return R.err("欺诈分上限应为 0 到 100");
        }
    }
    @RequireRole @LogAnnotation @PostMapping("/check-country") public R checkCountry(@RequestBody Map<String, Object> body) {
        return service.checkCountry(body == null || body.get("country") == null ? null : body.get("country").toString());
    }

    private static Long id(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
