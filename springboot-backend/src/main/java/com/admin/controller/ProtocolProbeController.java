package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.service.ProtocolProbeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/protocol-probe")
public class ProtocolProbeController {
    private final ProtocolProbeService service;

    public ProtocolProbeController(ProtocolProbeService service) {
        this.service = service;
    }

    @PostMapping("/overview")
    public R overview() {
        return service.overview();
    }

    @LogAnnotation
    @PostMapping("/run")
    public R run(@RequestBody Map<String, Object> body) {
        Object id = body == null ? null : body.get("proxyId");
        if (id == null) return R.err("请选择协议节点");
        return service.run(Long.valueOf(id.toString()), body);
    }

    @PostMapping("/history")
    public R history(@RequestBody Map<String, Object> body) {
        Object id = body == null ? null : body.get("proxyId");
        if (id == null) return R.err("请选择协议节点");
        Object limit = body.get("limit");
        int value = limit == null ? 30 : Integer.parseInt(limit.toString());
        return service.history(Long.valueOf(id.toString()), value);
    }
}
