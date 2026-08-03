package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.DockerAppCenterService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/docker-apps")
public class DockerAppCenterController {
    private final DockerAppCenterService service;

    public DockerAppCenterController(DockerAppCenterService service) {
        this.service = service;
    }

    @PostMapping("/overview")
    @RequireRole
    public R overview() {
        return service.overview();
    }

    @LogAnnotation
    @PostMapping("/inspect")
    @RequireRole
    public R inspect(@RequestBody Map<String, Object> params) {
        return service.inspect(Long.valueOf(params.get("nodeId").toString()));
    }

    @LogAnnotation
    @PostMapping("/deploy")
    @RequireRole
    public R deploy(@RequestBody Map<String, Object> params) {
        return service.deploy(params);
    }

    @LogAnnotation
    @PostMapping("/action")
    @RequireRole
    public R action(@RequestBody Map<String, Object> params) {
        return service.action(Long.valueOf(params.get("id").toString()), String.valueOf(params.get("action")));
    }

    @PostMapping("/command")
    @RequireRole
    public R command(@RequestBody Map<String, Object> params) {
        return service.command(Long.valueOf(params.get("id").toString()), String.valueOf(params.get("action")));
    }

    @PostMapping("/events")
    @RequireRole
    public R events(@RequestBody Map<String, Object> params) {
        return service.events(Long.valueOf(params.get("id").toString()));
    }
}
