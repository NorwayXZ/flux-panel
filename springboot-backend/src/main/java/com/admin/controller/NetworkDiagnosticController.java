package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.NetworkDiagnosticDto;
import com.admin.common.lang.R;
import com.admin.service.NetworkDiagnosticService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/network-tools")
public class NetworkDiagnosticController {
    private final NetworkDiagnosticService service;
    public NetworkDiagnosticController(NetworkDiagnosticService service) { this.service = service; }

    @RequireRole @LogAnnotation @PostMapping("/run")
    public R run(@Validated @RequestBody NetworkDiagnosticDto dto) { return service.run(dto); }
}
