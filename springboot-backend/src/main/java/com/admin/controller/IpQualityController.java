package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.IpQualityService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ip-quality")
public class IpQualityController {
    private final IpQualityService service;
    public IpQualityController(IpQualityService service) { this.service = service; }

    @RequireRole @PostMapping("/overview") public R overview() { return service.overview(); }
    @RequireRole @LogAnnotation @PostMapping("/run") public R run(@RequestBody Map<String, Object> input) { return service.run(Long.valueOf(String.valueOf(input.get("nodeId")))); }
    @RequireRole @PostMapping("/history") public R history(@RequestBody Map<String, Object> input) { return service.history(Long.valueOf(String.valueOf(input.get("nodeId")))); }
    @RequireRole @LogAnnotation @PostMapping("/providers/save") public R saveProviders(@RequestBody Map<String, Object> input) { return service.saveProviders(input); }
}
