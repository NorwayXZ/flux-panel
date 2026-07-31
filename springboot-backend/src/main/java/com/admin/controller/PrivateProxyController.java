package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.PrivateProxyCreateDto;
import com.admin.common.dto.PrivateProxyGrantDto;
import com.admin.common.dto.PrivateProxyGrantUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.PrivateProxyService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/private-proxy")
public class PrivateProxyController {
    private final PrivateProxyService service;
    public PrivateProxyController(PrivateProxyService service) { this.service = service; }

    @LogAnnotation @PostMapping("/create") public R create(@Validated @RequestBody PrivateProxyCreateDto dto) { return service.create(dto); }
    @RequireRole @LogAnnotation @PostMapping("/grant") public R grant(@Validated @RequestBody PrivateProxyGrantDto dto) { return service.grant(dto); }
    @RequireRole @PostMapping("/grant-list") public R grants(@RequestBody(required = false) Map<String, Object> body) {
        Object value = body == null ? null : body.get("userId");
        return service.grants(value == null ? null : Integer.valueOf(value.toString()));
    }
    @RequireRole @LogAnnotation @PostMapping("/grant-update") public R updateGrant(@Validated @RequestBody PrivateProxyGrantUpdateDto dto) { return service.updateGrant(dto); }
    @RequireRole @LogAnnotation @PostMapping("/grant-reset") public R resetGrant(@RequestBody Map<String, Long> body) { return service.resetGrantFlow(body.get("id")); }
    @PostMapping("/list") public R list() { return service.list(); }
    @PostMapping("/client-config") public R clientConfig(@RequestBody Map<String, Long> body) { return service.clientConfig(body.get("id")); }
    @LogAnnotation @PostMapping("/pause") public R pause(@RequestBody Map<String, Long> body) { return service.pause(body.get("id")); }
    @LogAnnotation @PostMapping("/resume") public R resume(@RequestBody Map<String, Long> body) { return service.resume(body.get("id")); }
    @LogAnnotation @PostMapping("/delete") public R delete(@RequestBody Map<String, Long> body) { return service.delete(body.get("id")); }
}
