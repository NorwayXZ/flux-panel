package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.interceptor.ApiRequestTimingRegistry;
import com.admin.common.lang.R;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/monitoring")
public class ApiTimingController {
    private final ApiRequestTimingRegistry timingRegistry;

    public ApiTimingController(ApiRequestTimingRegistry timingRegistry) {
        this.timingRegistry = timingRegistry;
    }

    @RequireRole
    @PostMapping("/api-timing")
    public R overview() {
        return R.ok(timingRegistry.snapshot());
    }
}
