package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.DnsProviderAccountSaveDto;
import com.admin.common.lang.R;
import com.admin.service.DnsProviderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/dns-provider")
public class DnsProviderController {
    private final DnsProviderService service;

    public DnsProviderController(DnsProviderService service) {
        this.service = service;
    }

    @PostMapping("/list")
    @RequireRole
    public R list() {
        return service.list();
    }

    @PostMapping("/zones")
    @RequireRole
    public R zones() {
        return service.listZoneOptions();
    }

    @LogAnnotation
    @PostMapping("/account/save")
    @RequireRole
    public R saveAccount(@Validated @RequestBody DnsProviderAccountSaveDto dto) {
        return service.saveAccount(dto);
    }

    @LogAnnotation
    @PostMapping("/account/sync")
    @RequireRole
    public R syncAccount(@RequestBody Map<String, Object> params) {
        return service.syncAccount(Long.valueOf(params.get("id").toString()));
    }

    @LogAnnotation
    @PostMapping("/account/delete")
    @RequireRole
    public R deleteAccount(@RequestBody Map<String, Object> params) {
        return service.deleteAccount(Long.valueOf(params.get("id").toString()));
    }
}
