package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.NetworkDiagnosticDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class NetworkDiagnosticService {
    private static final String MIN_AGENT_VERSION = "2.19.0";
    private final NodeMapper nodeMapper;

    public NetworkDiagnosticService(NodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    public R run(NetworkDiagnosticDto dto) {
        Node node = nodeMapper.selectById(dto.getNodeId());
        if (node == null) return R.err("节点不存在");
        if (!WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线，无法执行诊断");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            return R.err("节点 Agent 需要先升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("mode", dto.getMode());
        request.put("target", dto.getTarget().trim());
        request.put("recordType", dto.getRecordType());
        request.put("port", dto.getPort());
        request.put("count", dto.getCount() == null ? 4 : dto.getCount());
        request.put("timeoutMs", dto.getTimeoutMs() == null ? 5000 : dto.getTimeoutMs());
        GostDto result = WebSocketServer.send_msg(node.getId(), request, "NetworkDiagnostic");
        if (result == null || !"OK".equals(result.getMsg())) return R.err(result == null ? "Agent 无响应" : result.getMsg());
        return R.ok(result.getData());
    }
}
