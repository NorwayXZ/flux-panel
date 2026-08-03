package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.MultiLineAggregationDto;
import com.admin.common.lang.R;
import com.admin.service.MultiLineAggregationService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/multi-line-aggregation")
public class MultiLineAggregationController {
    private final MultiLineAggregationService service;

    public MultiLineAggregationController(MultiLineAggregationService service) { this.service = service; }

    @RequireRole @PostMapping("/overview") public R overview() { return service.overview(); }
    @RequireRole @LogAnnotation @PostMapping("/save") public R save(@Validated @RequestBody MultiLineAggregationDto dto) { return service.save(dto); }
    @RequireRole @LogAnnotation @PostMapping("/deploy") public R deploy(@RequestBody Map<String, Object> body) { return service.deploy(id(body)); }
    @RequireRole @LogAnnotation @PostMapping("/recalculate") public R recalculate(@RequestBody Map<String, Object> body) { return service.recalculate(id(body)); }
    @RequireRole @LogAnnotation @PostMapping("/repair") public R repair(@RequestBody Map<String, Object> body) { return service.repair(id(body)); }
    @RequireRole @LogAnnotation @PostMapping("/toggle") public R toggle(@RequestBody Map<String, Object> body) { return service.toggle(id(body), Boolean.parseBoolean(body.get("enabled").toString())); }
    @RequireRole @LogAnnotation @PostMapping("/test") public R test(@RequestBody Map<String, Object> body) { return service.test(id(body)); }
    @RequireRole @PostMapping("/events") public R events(@RequestBody Map<String, Object> body) { return service.events(id(body)); }
    @RequireRole @LogAnnotation @PostMapping("/delete") public R delete(@RequestBody Map<String, Object> body) { return service.delete(id(body)); }

    private long id(Map<String, Object> body) { return Long.parseLong(body.get("id").toString()); }
}
