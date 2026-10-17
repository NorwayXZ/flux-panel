package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.MysteriumService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Mysterium residential exits, paid from the admin's per-node accounts. */
@RestController
@RequestMapping("/api/v1/mysterium")
public class MysteriumController {
    private final MysteriumService service;

    public MysteriumController(MysteriumService service) {
        this.service = service;
    }

    @RequireRole @PostMapping("/overview") public R overview() { return service.overview(); }

    @RequireRole @PostMapping("/countries") public R countries() { return service.countryList(); }

    @RequireRole @LogAnnotation @PostMapping("/settings")
    public R settings(@RequestBody Map<String, Object> body) {
        return service.saveSettings(bool(body, "enabled"), bool(body, "agreeTerms"), number(body, "monthlyBudgetUsd"), number(body, "lowBalanceMyst"));
    }

    @RequireRole @LogAnnotation @PostMapping("/setup")
    public R setup(@RequestBody Map<String, Object> body) { return service.setup(id(body)); }

    @RequireRole @LogAnnotation @PostMapping("/topup")
    public R topup(@RequestBody Map<String, Object> body) {
        return service.topup(id(body), text(body, "gateway"), number(body, "amountUsd"), text(body, "country"));
    }

    @RequireRole @PostMapping("/refresh")
    public R refresh(@RequestBody Map<String, Object> body) { return service.refresh(id(body)); }

    @RequireRole @LogAnnotation @PostMapping("/reconnect")
    public R reconnect(@RequestBody Map<String, Object> body) { return service.reconnect(id(body), text(body, "country")); }

    @RequireRole @PostMapping("/events")
    public R events(@RequestBody Map<String, Object> body) { return service.events(id(body), text(body, "country")); }

    private static Long id(Map<String, Object> body) {
        Object value = body == null ? null : body.get("nodeId");
        return value == null || value.toString().isBlank() ? null : Long.valueOf(value.toString());
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : value.toString().trim();
    }

    private static Boolean bool(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : Boolean.valueOf(value.toString());
    }

    private static Double number(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
