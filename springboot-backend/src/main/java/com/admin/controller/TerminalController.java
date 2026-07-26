package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.common.utils.ClientIpUtil;
import com.admin.service.TerminalSessionManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/terminal")
public class TerminalController {
    private final TerminalSessionManager terminalSessionManager;

    public TerminalController(TerminalSessionManager terminalSessionManager) {
        this.terminalSessionManager = terminalSessionManager;
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/node/toggle")
    public R toggle(@RequestBody Map<String, Object> params) {
        try {
            Long nodeId = Long.valueOf(params.get("nodeId").toString());
            boolean enabled = Boolean.parseBoolean(params.get("enabled").toString());
            return R.ok(terminalSessionManager.setNodeEnabled(nodeId, enabled));
        } catch (SecurityException e) {
            return R.err(403, e.getMessage());
        } catch (Exception e) {
            return R.err(e.getMessage());
        }
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/session/create")
    public R create(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        try {
            Long nodeId = Long.valueOf(params.get("nodeId").toString());
            return R.ok(terminalSessionManager.createTicket(nodeId, ClientIpUtil.resolve(request)));
        } catch (SecurityException e) {
            return R.err(403, e.getMessage());
        } catch (Exception e) {
            return R.err(e.getMessage());
        }
    }

    @RequireRole
    @PostMapping("/audit/list")
    public R audit(@RequestBody(required = false) Map<String, Object> params) {
        Long nodeId = null;
        if (params != null && params.get("nodeId") != null) {
            nodeId = Long.valueOf(params.get("nodeId").toString());
        }
        return R.ok(terminalSessionManager.listAudit(nodeId));
    }
}
