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

    private static Long id(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
