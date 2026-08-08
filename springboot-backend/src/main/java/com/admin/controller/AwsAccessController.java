package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.AwsAccessAccountSaveDto;
import com.admin.common.lang.R;
import com.admin.service.AwsAccessService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/aws-access")
public class AwsAccessController {
    private final AwsAccessService service;

    public AwsAccessController(AwsAccessService service) {
        this.service = service;
    }

    @RequireRole
    @PostMapping("/list")
    public R list() {
        return service.list();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/save")
    public R save(@Validated @RequestBody AwsAccessAccountSaveDto dto) {
        return service.save(dto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/sync")
    public R sync(@RequestBody Map<String, Object> body) {
        return service.test(Long.valueOf(body.get("id").toString()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> body) {
        return service.delete(Long.valueOf(body.get("id").toString()));
    }
}
