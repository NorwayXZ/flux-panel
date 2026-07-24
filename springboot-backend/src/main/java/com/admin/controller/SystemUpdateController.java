package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.SystemUpdateService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/system-update")
public class SystemUpdateController {

    private final SystemUpdateService systemUpdateService;

    public SystemUpdateController(SystemUpdateService systemUpdateService) {
        this.systemUpdateService = systemUpdateService;
    }

    @RequireRole
    @PostMapping("/status")
    public R getStatus() {
        return R.ok(systemUpdateService.getStatus());
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/trigger")
    public R triggerUpdate() {
        return systemUpdateService.triggerUpdate();
    }
}
