package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.ApiTokenService;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/api-token")
public class ApiTokenController {
    private final ApiTokenService tokens;

    public ApiTokenController(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/list")
    public R list() {
        return tokens.list();
    }

    // Not logged: the response carries the only copy of the new token.
    @PostMapping("/create")
    public R create(@RequestBody Map<String, Object> params) {
        Object days = params.get("validDays");
        return tokens.create(String.valueOf(params.getOrDefault("name", "")),
                days == null ? null : Integer.valueOf(days.toString()));
    }

    @LogAnnotation
    @PostMapping("/revoke")
    public R revoke(@RequestBody Map<String, Object> params) {
        Object id = params.get("id");
        return tokens.revoke(id == null ? null : Long.valueOf(id.toString()));
    }
}
