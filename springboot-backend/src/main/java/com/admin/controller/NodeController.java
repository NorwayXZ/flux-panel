package com.admin.controller;


import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.AgentUpgradeService;
import com.admin.service.NodeServiceDiscoveryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/node")
public class NodeController extends BaseController {

    private final AgentUpgradeService agentUpgradeService;
    private final NodeServiceDiscoveryService nodeServiceDiscoveryService;

    public NodeController(AgentUpgradeService agentUpgradeService,
                          NodeServiceDiscoveryService nodeServiceDiscoveryService) {
        this.agentUpgradeService = agentUpgradeService;
        this.nodeServiceDiscoveryService = nodeServiceDiscoveryService;
    }

    @LogAnnotation
    @PostMapping("/create")
    public R create(@Validated @RequestBody NodeDto nodeDto) {
        return nodeService.createNode(nodeDto);
    }


    @LogAnnotation
    @PostMapping("/list")
    public R list() {
        return nodeService.getAllNodes();
    }

    @LogAnnotation
    @PostMapping("/update")
    public R update(@Validated @RequestBody NodeUpdateDto nodeUpdateDto) {
        return nodeService.updateNode(nodeUpdateDto);
    }

    @LogAnnotation
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return nodeService.deleteNode(id);
    }

    @LogAnnotation
    @PostMapping("/check-status")
    public R checkStatus(@RequestBody(required = false) Map<String, Object> params) {
        Long id = null;
        if (params != null && params.get("nodeId") != null) {
            id = Long.valueOf(params.get("nodeId").toString());
        }
        return nodeService.checkNodeStatus(id);
    }

    @LogAnnotation
    @PostMapping("/install")
    public R getInstallCommand(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return nodeService.getInstallCommand(id);
    }

    @RequireRole
    @PostMapping("/upgrade/status")
    public R upgradeStatus(@RequestBody(required = false) Map<String, Object> params) {
        Long nodeId = params != null && params.get("nodeId") != null
                ? Long.valueOf(params.get("nodeId").toString()) : null;
        return R.ok(agentUpgradeService.getStatus(nodeId));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/upgrade/start")
    public R startUpgrade(@RequestBody Map<String, Object> params) {
        return agentUpgradeService.start(Long.valueOf(params.get("nodeId").toString()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/upgrade/batch")
    public R startBatchUpgrade(@RequestBody(required = false) Map<String, Object> params) {
        String mode = params == null ? null : Objects.toString(params.get("mode"), null);
        return agentUpgradeService.startBatch(mode);
    }

    @RequireRole
    @PostMapping("/upgrade/manual-command")
    public R manualUpgradeCommand(@RequestBody Map<String, Object> params) {
        return agentUpgradeService.manualCommand(Long.valueOf(params.get("nodeId").toString()));
    }

    @RequireRole
    @PostMapping("/upgrade/history")
    public R upgradeHistory(@RequestBody(required = false) Map<String, Object> params) {
        Long nodeId = params != null && params.get("nodeId") != null
                ? Long.valueOf(params.get("nodeId").toString()) : null;
        return R.ok(agentUpgradeService.history(nodeId));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/discovery/scan")
    public R discoverServices(@RequestBody Map<String, Object> params) {
        return nodeServiceDiscoveryService.scan(Long.parseLong(params.get("nodeId").toString()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/user/assign")
    public R assignUserNode(@RequestBody Map<String, Object> params) {
        Integer userId = Integer.valueOf(params.get("userId").toString());
        Integer nodeId = Integer.valueOf(params.get("nodeId").toString());
        return userNodeService.assign(userId, nodeId);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/user/list")
    public R listUserNodes(@RequestBody Map<String, Object> params) {
        Integer userId = Integer.valueOf(params.get("userId").toString());
        return userNodeService.listByUser(userId);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/user/remove")
    public R removeUserNode(@RequestBody Map<String, Object> params) {
        Integer userId = Integer.valueOf(params.get("userId").toString());
        Integer nodeId = Integer.valueOf(params.get("nodeId").toString());
        return userNodeService.removeAccess(userId, nodeId);
    }

}
