package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.CrossEntryFailoverSaveDto;
import com.admin.common.lang.R;
import com.admin.service.CrossEntryFailoverService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/cross-entry-failover")
public class CrossEntryFailoverController {
    private final CrossEntryFailoverService service;

    public CrossEntryFailoverController(CrossEntryFailoverService service) {
        this.service = service;
    }

    @PostMapping("/list")
    @RequireRole
    public R list() {
        return service.listGroups();
    }

    @PostMapping("/eligible-forwards")
    @RequireRole
    public R eligibleForwards() {
        return service.listEligibleForwards();
    }

    @LogAnnotation
    @PostMapping("/save")
    @RequireRole
    public R save(@Validated @RequestBody CrossEntryFailoverSaveDto dto) {
        return service.save(dto);
    }

    @LogAnnotation
    @PostMapping("/delete")
    @RequireRole
    public R delete(@RequestBody Map<String, Object> params) {
        return service.delete(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/check")
    @RequireRole
    public R check(@RequestBody Map<String, Object> params) {
        return service.checkNow(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/events")
    @RequireRole
    public R events(@RequestBody Map<String, Object> params) {
        return service.listEvents(Long.valueOf(params.get("id").toString()));
    }
}
