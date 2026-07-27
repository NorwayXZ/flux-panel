package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.DynamicDnsProviderSaveDto;
import com.admin.common.dto.DynamicDnsRuleSaveDto;
import com.admin.common.lang.R;
import com.admin.service.DynamicDnsService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/dynamic-dns")
public class DynamicDnsController {
    private final DynamicDnsService service;
    public DynamicDnsController(DynamicDnsService service) { this.service = service; }

    @RequireRole @PostMapping("/overview") public R overview() { return service.overview(); }
    @RequireRole @PostMapping("/history") public R history(@RequestBody Map<String, Object> body) { return service.history(id(body)); }
    @LogAnnotation @RequireRole @PostMapping("/provider/save") public R saveProvider(@RequestBody DynamicDnsProviderSaveDto dto) { return service.saveProvider(dto); }
    @LogAnnotation @RequireRole @PostMapping("/provider/delete") public R deleteProvider(@RequestBody Map<String, Object> body) { return service.deleteProvider(id(body)); }
    @LogAnnotation @RequireRole @PostMapping("/rule/save") public R saveRule(@RequestBody DynamicDnsRuleSaveDto dto) { return service.saveRule(dto); }
    @LogAnnotation @RequireRole @PostMapping("/rule/delete") public R deleteRule(@RequestBody Map<String, Object> body) { return service.deleteRule(id(body)); }
    @LogAnnotation @RequireRole @PostMapping("/rule/run") public R run(@RequestBody Map<String, Object> body) { return service.runNow(id(body)); }
    private Long id(Map<String, Object> body) { return Long.valueOf(body.get("id").toString()); }
}
