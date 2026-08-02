package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.BandwidthTestTaskDto;
import com.admin.common.lang.R;
import com.admin.service.BandwidthTestService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/bandwidth-test")
public class BandwidthTestController {
    private final BandwidthTestService service;
    public BandwidthTestController(BandwidthTestService service) { this.service = service; }
    @RequireRole @PostMapping("/overview") public R overview() { return service.overview(); }
    @RequireRole @LogAnnotation @PostMapping("/save") public R save(@Validated @RequestBody BandwidthTestTaskDto dto) { return service.save(dto); }
    @RequireRole @LogAnnotation @PostMapping("/run") public R run(@RequestBody Map<String, Object> body) { return service.runNow(Long.valueOf(body.get("id").toString())); }
    @RequireRole @LogAnnotation @PostMapping("/delete") public R delete(@RequestBody Map<String, Object> body) { return service.delete(Long.valueOf(body.get("id").toString())); }
    @RequireRole @PostMapping("/detail") public R detail(@RequestBody Map<String, Object> body) { return service.detail(Long.valueOf(body.get("id").toString())); }
}
