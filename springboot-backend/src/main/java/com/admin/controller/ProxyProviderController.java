package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.ProxyExitService;
import com.admin.service.ProxyProviderService;
import com.admin.service.ProxyRoutingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Commercial mobile and residential proxy providers, admin only. */
@RestController
@RequestMapping("/api/v1/proxy-provider")
public class ProxyProviderController {
    private final ProxyProviderService providers;
    private final ProxyExitService exits;
    private final ProxyRoutingService routing;

    public ProxyProviderController(ProxyProviderService providers, ProxyExitService exits, ProxyRoutingService routing) {
        this.providers = providers;
        this.exits = exits;
        this.routing = routing;
    }

    @RequireRole @PostMapping("/list") public R list() { return providers.list(); }

    @RequireRole @LogAnnotation @PostMapping("/save")
    @SuppressWarnings("unchecked")
    public R save(@RequestBody Map<String, Object> body) {
        Object settings = body.get("settings");
        Integer sticky = body.get("stickyMinutes") == null ? null : (int) Double.parseDouble(body.get("stickyMinutes").toString());
        R result = providers.save(id(body, "id"), text(body, "name"), text(body, "kind"), text(body, "network"), sticky,
                settings instanceof Map ? (Map<String, Object>) settings : Map.of());
        // New credentials or IP-holding time apply to every proxy using this provider's exits.
        if (result.getCode() == 0 && id(body, "id") != null) routing.reapplyForProvider(id(body, "id"));
        return result;
    }

    @RequireRole @LogAnnotation @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> body) { return providers.delete(id(body, "id")); }

    @RequireRole @PostMapping("/countries")
    public R countries(@RequestBody Map<String, Object> body) { return providers.countries(id(body, "id")); }

    @RequireRole @PostMapping("/carriers")
    public R carriers(@RequestBody Map<String, Object> body) { return providers.carriers(id(body, "id"), text(body, "country")); }

    @RequireRole @PostMapping("/usage")
    public R usage(@RequestBody Map<String, Object> body) { return providers.refreshUsage(id(body, "id")); }

    @RequireRole @LogAnnotation @PostMapping("/exit/create")
    public R createExit(@RequestBody Map<String, Object> body) {
        return exits.createProviderExit(id(body, "providerId"), text(body, "country"), text(body, "carrier"), text(body, "name"));
    }

    /** New session for the exit: the provider hands out another IP; proxies using it are re-applied. */
    @RequireRole @LogAnnotation @PostMapping("/exit/rotate")
    public R rotate(@RequestBody Map<String, Object> body) {
        Long id = id(body, "id");
        R result = exits.rotateProviderExit(id);
        if (result.getCode() != 0) return result;
        int reapplied = routing.reapplyForExit(id);
        for (Long group : exits.groupsContaining(id)) reapplied += routing.reapplyForExit(group);
        return R.ok(Map.of("reapplied", reapplied));
    }

    private static Long id(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null || value.toString().isBlank() ? null : Long.valueOf(value.toString());
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : value.toString().trim();
    }
}
