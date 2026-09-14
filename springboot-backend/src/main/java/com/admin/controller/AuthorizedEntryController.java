package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.AuthorizedEntryService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/authorized-entry")
public class AuthorizedEntryController {
    private final AuthorizedEntryService service;

    public AuthorizedEntryController(AuthorizedEntryService service) {
        this.service = service;
    }

    @PostMapping("/grants")
    public R grants(@RequestBody(required = false) Map<String, Object> params) {
        Integer userId = params == null || params.get("userId") == null ? null : Integer.valueOf(params.get("userId").toString());
        return service.listGrants(userId);
    }

    @PostMapping("/port/create")
    @LogAnnotation
    public R createPort(@RequestBody Map<String, Object> params) {
        return service.createPort(params);
    }

    @PostMapping("/port/delete")
    @LogAnnotation
    public R deletePort(@RequestBody Map<String, Object> params) {
        return service.deletePort(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/port/update")
    @LogAnnotation
    public R updatePort(@RequestBody Map<String, Object> params) {
        return service.updatePort(Long.valueOf(params.get("id").toString()), params);
    }

    @PostMapping("/template/list")
    @RequireRole
    public R templates() { return service.listTemplates(); }

    @PostMapping("/template/save")
    @RequireRole
    @LogAnnotation
    public R saveTemplate(@RequestBody Map<String, Object> params) { return service.saveTemplate(params); }

    @PostMapping("/grant/save")
    @RequireRole
    @LogAnnotation
    public R saveGrant(@RequestBody Map<String, Object> params) { return service.saveGrant(params); }

    @PostMapping("/grant/state")
    @RequireRole
    @LogAnnotation
    public R grantState(@RequestBody Map<String, Object> params) {
        return service.setGrantState(Long.valueOf(params.get("id").toString()),
                Boolean.parseBoolean(String.valueOf(params.getOrDefault("active", false))));
    }

    @PostMapping("/grant/revoke")
    @RequireRole
    @LogAnnotation
    public R revokeGrant(@RequestBody Map<String, Object> params) {
        return service.revokeGrant(Long.valueOf(params.get("id").toString()));
    }
}
