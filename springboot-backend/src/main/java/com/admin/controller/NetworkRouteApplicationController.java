package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.NetworkRouteApplicationCreateDto;
import com.admin.common.lang.R;
import com.admin.service.NetworkRouteApplicationService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/network-route-application")
public class NetworkRouteApplicationController {
    private final NetworkRouteApplicationService service;
    public NetworkRouteApplicationController(NetworkRouteApplicationService service) { this.service = service; }
    @RequireRole @PostMapping("/overview") public R overview() { return service.overview(); }
    @RequireRole @LogAnnotation @PostMapping("/create") public R create(@Validated @RequestBody NetworkRouteApplicationCreateDto dto) { return service.create(dto); }
    @RequireRole @LogAnnotation @PostMapping("/deploy") public R deploy(@RequestBody Map<String,Object> body) { return service.deploy(id(body)); }
    @RequireRole @PostMapping("/test") public R test(@RequestBody Map<String,Object> body) { return service.test(id(body)); }
    @RequireRole @LogAnnotation @PostMapping("/pause") public R pause(@RequestBody Map<String,Object> body) { return service.pause(id(body)); }
    @RequireRole @LogAnnotation @PostMapping("/resume") public R resume(@RequestBody Map<String,Object> body) { return service.resume(id(body)); }
    @RequireRole @LogAnnotation @PostMapping("/delete") public R delete(@RequestBody Map<String,Object> body) { return service.delete(id(body)); }
    private static Long id(Map<String,Object> body) { return Long.valueOf(String.valueOf(body.get("id"))); }
}
