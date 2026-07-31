package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.SystemSelfCheckService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/system-self-check")
public class SystemSelfCheckController {
    private final SystemSelfCheckService service;

    public SystemSelfCheckController(SystemSelfCheckService service) {
        this.service = service;
    }

    @RequireRole
    @PostMapping("/overview")
    public R overview() {
        return service.overview();
    }

    @RequireRole
    @LogAnnotation
    @PostMapping("/run")
    public R run(@RequestBody(required = false) Map<String, Object> params) {
        Long nodeId = params != null && params.get("nodeId") != null
                ? Long.valueOf(params.get("nodeId").toString()) : null;
        Long connectorId = params != null && params.get("connectorId") != null
                ? Long.valueOf(params.get("connectorId").toString()) : null;
        return service.start(nodeId, connectorId);
    }

    @RequireRole
    @LogAnnotation
    @PostMapping("/identity/reset")
    public R resetIdentity(@RequestBody Map<String, Object> params) {
        return service.resetIdentityBaseline(Long.parseLong(params.get("nodeId").toString()));
    }
}
