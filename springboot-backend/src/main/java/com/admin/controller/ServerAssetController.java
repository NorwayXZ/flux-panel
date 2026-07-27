package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.ServerAssetSaveDto;
import com.admin.common.lang.R;
import com.admin.service.ServerAssetService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/server-assets")
public class ServerAssetController {
    private final ServerAssetService service;

    public ServerAssetController(ServerAssetService service) { this.service = service; }

    @RequireRole @PostMapping("/list") public R list() { return service.list(); }
    @LogAnnotation @RequireRole @PostMapping("/save") public R save(@RequestBody ServerAssetSaveDto dto) { return service.save(dto); }
    @LogAnnotation @RequireRole @PostMapping("/delete") public R delete(@RequestBody Map<String, Object> body) {
        return service.delete(Long.valueOf(body.get("id").toString()));
    }
}
