package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.SmartEntrySaveDto;
import com.admin.common.lang.R;
import com.admin.service.SmartEntryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/smart-entry")
public class SmartEntryController {
    private final SmartEntryService service;

    public SmartEntryController(SmartEntryService service) {
        this.service = service;
    }

    @PostMapping("/overview")
    @RequireRole
    public R overview() {
        return service.overview();
    }

    @PostMapping("/options")
    @RequireRole
    public R options() {
        return service.options();
    }

    @PostMapping("/domains")
    @RequireRole
    public R domains(@RequestBody Map<String, Object> body) {
        Object providerRefId = body.get("providerRefId");
        if (providerRefId == null) return R.err("请选择 DNS 服务商配置");
        try {
            return service.domains(Long.valueOf(providerRefId.toString()));
        } catch (NumberFormatException e) {
            return R.err("DNS 服务商配置无效");
        }
    }

    @LogAnnotation
    @PostMapping("/save")
    @RequireRole
    public R save(@Validated @RequestBody SmartEntrySaveDto dto) {
        return service.save(dto);
    }

    @PostMapping("/check")
    @RequireRole
    public R check(@RequestBody Map<String, Object> body) {
        return service.checkNow(Long.valueOf(body.get("id").toString()));
    }

    @PostMapping("/events")
    @RequireRole
    public R events(@RequestBody Map<String, Object> body) {
        return service.events(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @PostMapping("/delete")
    @RequireRole
    public R delete(@RequestBody Map<String, Object> body) {
        return service.delete(Long.valueOf(body.get("id").toString()));
    }
}
