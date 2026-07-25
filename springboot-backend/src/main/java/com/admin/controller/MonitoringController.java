package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.service.MonitoringService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    @Resource
    private MonitoringService monitoringService;

    @PostMapping("/overview")
    public R overview(@RequestBody(required = false) Map<String, Object> params) {
        String range = params == null || params.get("range") == null ? "24h" : params.get("range").toString();
        return R.ok(monitoringService.getOverview(
                JwtUtil.getUserIdFromToken(),
                JwtUtil.getRoleIdFromToken(),
                range
        ));
    }

    @PostMapping("/alerts")
    public R alerts(@RequestBody(required = false) Map<String, Object> params) {
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        return R.ok(monitoringService.listAlerts(
                JwtUtil.getUserIdFromToken(),
                JwtUtil.getRoleIdFromToken(),
                value(safeParams, "status", "all"),
                value(safeParams, "resourceType", "all"),
                value(safeParams, "severity", "all"),
                value(safeParams, "keyword", ""),
                intValue(safeParams, "page", 1),
                intValue(safeParams, "size", 20)
        ));
    }

    @PostMapping("/alerts/read")
    public R markRead(@RequestBody(required = false) Map<String, Object> params) {
        Object idsValue = params == null ? null : params.get("ids");
        if (!(idsValue instanceof List<?> rawIds)) {
            return R.err("请选择需要标记的告警");
        }
        List<Long> ids;
        try {
            ids = rawIds.stream().map(value -> Long.valueOf(value.toString())).toList();
        } catch (NumberFormatException e) {
            return R.err("告警编号无效");
        }
        return R.ok(monitoringService.markAlertsRead(
                JwtUtil.getUserIdFromToken(), JwtUtil.getRoleIdFromToken(), ids
        ));
    }

    @PostMapping("/alerts/read-all")
    public R markAllRead() {
        return R.ok(monitoringService.markAllAlertsRead(
                JwtUtil.getUserIdFromToken(), JwtUtil.getRoleIdFromToken()
        ));
    }

    @PostMapping("/alerts/unread-count")
    public R unreadCount() {
        return R.ok(monitoringService.getUnreadCount(
                JwtUtil.getUserIdFromToken(), JwtUtil.getRoleIdFromToken()
        ));
    }

    private String value(Map<String, Object> params, String key, String fallback) {
        return params.get(key) == null ? fallback : params.get(key).toString();
    }

    private int intValue(Map<String, Object> params, String key, int fallback) {
        try {
            return params.get(key) == null ? fallback : Integer.parseInt(params.get(key).toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
