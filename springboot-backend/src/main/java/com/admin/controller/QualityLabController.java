package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.QualityProbeTaskDto;
import com.admin.common.lang.R;
import com.admin.service.QualityLabService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/quality-lab")
public class QualityLabController {
    private final QualityLabService service;

    public QualityLabController(QualityLabService service) { this.service = service; }

    @RequireRole @PostMapping("/overview") public R overview() { return service.overview(); }
    @RequireRole @PostMapping("/preflight") public R preflight(@Validated @RequestBody QualityProbeTaskDto dto) { return service.preflight(dto); }
    @RequireRole @LogAnnotation @PostMapping("/save") public R save(@Validated @RequestBody QualityProbeTaskDto dto) { return service.save(dto); }
    @RequireRole @LogAnnotation @PostMapping("/run") public R run(@RequestBody Map<String, Object> params) { return service.runNow(Long.valueOf(params.get("id").toString())); }
    @RequireRole @LogAnnotation @PostMapping("/toggle") public R toggle(@RequestBody Map<String, Object> params) { return service.toggle(Long.valueOf(params.get("id").toString()), Boolean.parseBoolean(params.get("enabled").toString())); }
    @RequireRole @LogAnnotation @PostMapping("/delete") public R delete(@RequestBody Map<String, Object> params) { return service.delete(Long.valueOf(params.get("id").toString())); }
    @RequireRole @PostMapping("/detail") public R detail(@RequestBody Map<String, Object> params) { return service.detail(Long.valueOf(params.get("id").toString()), String.valueOf(params.getOrDefault("range", "24h"))); }
    @RequireRole @PostMapping("/report") public R report(@RequestBody Map<String, Object> params) { return service.report(Long.valueOf(params.get("id").toString()), String.valueOf(params.getOrDefault("range", "7d"))); }
}
