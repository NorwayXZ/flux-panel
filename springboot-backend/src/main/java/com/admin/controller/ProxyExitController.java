package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.service.ProxyExitService;
import com.admin.service.ProxyRoutingService;
import com.admin.service.ProxyRuleSourceService;
import com.admin.service.ProxyRulesetService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Custom exits that private proxies can route chosen traffic through. */
@RestController
@RequestMapping("/api/v1/proxy-exit")
public class ProxyExitController {
    private final ProxyExitService service;
    private final ProxyRulesetService rulesets;
    private final ProxyRuleSourceService sources;
    private final ProxyRoutingService routing;

    public ProxyExitController(ProxyExitService service, ProxyRulesetService rulesets, ProxyRuleSourceService sources,
                               ProxyRoutingService routing) {
        this.service = service;
        this.rulesets = rulesets;
        this.sources = sources;
        this.routing = routing;
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
    @SuppressWarnings("unchecked")
    public R list() {
        R result = service.list();
        if (result.getCode() == 0) {
            ((Map<String, Object>) result.getData()).put("rulesets",
                    sources.rulesetViews(JwtUtil.getUserIdFromToken(), Objects.equals(JwtUtil.getRoleIdFromToken(), 0)));
            ((Map<String, Object>) result.getData()).put("catalog", sources.catalogViews());
        }
        return result;
    }

    /** Creates (no id) or updates an exit group that picks the fastest member automatically. */
    @LogAnnotation
    @PostMapping("/group/save")
    public R saveGroup(@RequestBody Map<String, Object> body) {
        Long id = id(body, "id");
        List<Long> members = new ArrayList<>();
        Object raw = body.get("members");
        if (raw instanceof List) for (Object item : (List<?>) raw) if (item != null) members.add(Long.valueOf(item.toString()));
        R result = service.saveGroup(id, text(body, "name"), members, Boolean.TRUE.equals(body.get("direct")), text(body, "url"),
                integer(body, "intervalMinutes"), integer(body, "tolerance"));
        if (result.getCode() == 0 && id != null) routing.reapplyForExit(id);
        return result;
    }

    /** Which rule a destination hits, for the rules being edited. */
    @PostMapping("/route-test")
    @SuppressWarnings("unchecked")
    public R routeTest(@RequestBody Map<String, Object> body) {
        Object rules = body.get("rules");
        return sources.testRoute(rules instanceof List ? (List<Map<String, Object>>) rules : List.of(), text(body, "target"),
                text(body, "network"));
    }

    /** Well-known community rule sets shared by all users. */
    @PostMapping("/catalog")
    public R catalog() {
        return R.ok(sources.catalogViews());
    }

    @PostMapping("/catalog/refresh")
    public R catalogRefresh(@RequestBody Map<String, Object> body) {
        return sources.refreshCatalog(text(body, "key"));
    }

    @PostMapping("/ruleset/list")
    public R rulesetList() {
        return sources.listRulesets();
    }

    @LogAnnotation
    @PostMapping("/ruleset/create")
    public R rulesetCreate(@RequestBody Map<String, Object> body) {
        return sources.createRuleset(text(body, "name"), text(body, "url"), behavior(body), integer(body, "intervalHours"));
    }

    @LogAnnotation
    @PostMapping("/ruleset/update")
    public R rulesetUpdate(@RequestBody Map<String, Object> body) {
        return sources.updateRuleset(id(body, "id"), text(body, "name"), text(body, "url"), behavior(body), integer(body, "intervalHours"));
    }

    @LogAnnotation
    @PostMapping("/ruleset/delete")
    public R rulesetDelete(@RequestBody Map<String, Object> body) {
        return sources.deleteRuleset(id(body, "id"));
    }

    @PostMapping("/ruleset/refresh")
    public R rulesetRefresh(@RequestBody Map<String, Object> body) {
        return sources.refreshRulesetNow(id(body, "id"));
    }

    /** Reads pasted Clash rules and suggests an exit for each policy. */
    @PostMapping("/clash-import")
    @SuppressWarnings("unchecked")
    public R clashImport(@RequestBody Map<String, Object> body) {
        R exits = service.list();
        List<Map<String, Object>> list = exits.getCode() == 0
                ? (List<Map<String, Object>>) ((Map<String, Object>) exits.getData()).get("exits") : List.of();
        return sources.parseClash(text(body, "text"), list);
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

    private static String behavior(Map<String, Object> body) {
        String value = text(body, "behavior");
        return value == null || value.isBlank() ? "auto" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static Integer integer(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try {
            return (int) Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long id(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null || value.toString().isBlank() ? null : Long.valueOf(value.toString());
    }
}
