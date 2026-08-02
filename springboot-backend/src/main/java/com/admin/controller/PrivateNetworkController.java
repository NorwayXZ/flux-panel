package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.PrivateNetworkSaveDto;
import com.admin.common.lang.R;
import com.admin.service.PrivateNetworkService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/private-network")
public class PrivateNetworkController {
    private final PrivateNetworkService service;

    public PrivateNetworkController(PrivateNetworkService service) {
        this.service = service;
    }

    @RequireRole
    @PostMapping("/overview")
    public R overview() {
        return service.overview();
    }

    @RequireRole
    @LogAnnotation
    @PostMapping("/save")
    public R save(@Validated @RequestBody PrivateNetworkSaveDto dto) {
        return service.save(dto);
    }

    @RequireRole
    @LogAnnotation
    @PostMapping("/verify")
    public R verify(@RequestBody Map<String, Object> body) {
        return service.verify(id(body));
    }

    @RequireRole
    @LogAnnotation
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> body) {
        return service.delete(id(body));
    }

    private static Long id(Map<String, Object> body) {
        return Long.valueOf(String.valueOf(body.get("id")));
    }
}
