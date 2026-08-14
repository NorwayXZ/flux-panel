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
        Object id = body == null ? null : body.get("targetId");
        if (id == null && body != null) id = body.get("proxyId");
        if (id == null) return R.err("请选择协议节点");
        String targetType = body == null ? "created" : String.valueOf(body.getOrDefault("targetType", "created"));
        return service.run(targetType, Long.valueOf(id.toString()), body);
    }

    @PostMapping("/history")
    public R history(@RequestBody Map<String, Object> body) {
        Object id = body == null ? null : body.get("targetId");
        if (id == null && body != null) id = body.get("proxyId");
        if (id == null) return R.err("请选择协议节点");
        Object limit = body.get("limit");
        int value = limit == null ? 30 : Integer.parseInt(limit.toString());
        String targetType = String.valueOf(body.getOrDefault("targetType", "created"));
        return service.history(targetType, Long.valueOf(id.toString()), value);
    }

    @PostMapping("/external/list")
    public R externalList() {
        return service.externalList();
    }

    @LogAnnotation
    @PostMapping("/external/save")
    public R saveExternal(@RequestBody Map<String, Object> body) {
        return service.saveExternal(body);
    }

    @LogAnnotation
    @PostMapping("/external/delete")
    public R deleteExternal(@RequestBody Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        return service.deleteExternal(id == null ? null : Long.valueOf(id.toString()));
    }
}
