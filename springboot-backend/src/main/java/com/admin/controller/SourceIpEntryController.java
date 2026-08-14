package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.SourceIpEntrySaveDto;
import com.admin.common.lang.R;
import com.admin.service.SourceIpEntryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/source-ip-entry")
public class SourceIpEntryController {
    private final SourceIpEntryService service;

    public SourceIpEntryController(SourceIpEntryService service) {
        this.service = service;
    }

    @PostMapping("/overview")
    @RequireRole
    public R overview() {
        return service.overview();
    }

    @LogAnnotation
    @PostMapping("/save")
    @RequireRole
    public R save(@Validated @RequestBody SourceIpEntrySaveDto dto) {
        return service.save(dto);
    }

    @PostMapping("/check")
    @RequireRole
    public R check(@RequestBody Map<String, Object> body) {
        return service.check(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @PostMapping("/delete")
    @RequireRole
    public R delete(@RequestBody Map<String, Object> body) {
        return service.delete(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @PostMapping("/carriers/refresh")
    @RequireRole
    public R refreshCarriers() {
        return service.refreshCarriers();
    }

    @LogAnnotation
    @PostMapping("/asn/refresh")
    @RequireRole
    public R refreshAsns() {
        return service.refreshAsns();
    }

    @PostMapping("/debug")
    @RequireRole
    public R debug(@RequestBody Map<String, Object> body) {
        return service.debug(body);
    }
}
