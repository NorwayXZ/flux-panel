package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.NftForwardSaveDto;
import com.admin.common.lang.R;
import com.admin.service.NftForwardService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/nft-forward")
public class NftForwardController {
    private final NftForwardService service;

    public NftForwardController(NftForwardService service) {
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
    public R save(@Validated @RequestBody NftForwardSaveDto dto) {
        return service.save(dto);
    }

    @LogAnnotation
    @PostMapping("/toggle")
    @RequireRole
    public R toggle(@RequestBody Map<String, Object> body) {
        return service.toggle(Long.valueOf(body.get("id").toString()), Boolean.parseBoolean(body.get("enabled").toString()));
    }

    @PostMapping("/check")
    @RequireRole
    public R check(@RequestBody Map<String, Object> body) {
        return service.check(Long.valueOf(body.get("id").toString()));
    }

    @PostMapping("/preflight")
    @RequireRole
    public R preflight(@RequestBody NftForwardSaveDto dto) {
        return service.preflight(dto);
    }

    @LogAnnotation
    @PostMapping("/rollback")
    @RequireRole
    public R rollback(@RequestBody Map<String, Object> body) {
        return service.rollback(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @PostMapping("/delete")
    @RequireRole
    public R delete(@RequestBody Map<String, Object> body) {
        return service.delete(Long.valueOf(body.get("id").toString()));
    }

    @PostMapping("/events")
    @RequireRole
    public R events(@RequestBody Map<String, Object> body) {
        return service.events(Long.valueOf(body.get("id").toString()));
    }
}
