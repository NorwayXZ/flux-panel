package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

@Service
public class NodeServiceDiscoveryService {
    public static final String MIN_AGENT_VERSION = "2.34.0";

    private final NodeMapper nodeMapper;

    public NodeServiceDiscoveryService(NodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    public R scan(long nodeId) {
        Node node = nodeMapper.selectById(nodeId);
        if (node == null) return R.err("节点不存在");
        if (!WebSocketServer.isNodeOnline(nodeId)) return R.err("节点离线，无法发现服务");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            return R.err("节点 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }
        GostDto response = WebSocketServer.send_msg(nodeId, Map.of(
                "timeoutMs", 700,
                "maxServices", 200
        ), "NodeServiceDiscovery", 25);
        if (response == null || !Objects.equals(response.getMsg(), "OK") || response.getData() == null) {
            return R.err("服务发现失败：" + (response == null ? "Agent 无响应" : response.getMsg()));
        }
        JSONObject payload = JSONObject.parseObject(JSON.toJSONString(response.getData()));
        payload.put("nodeId", node.getId());
        payload.put("nodeName", node.getName());
        payload.put("nodeAddress", Objects.toString(node.getServerIp(), node.getIp()));
        payload.put("minimumAgentVersion", MIN_AGENT_VERSION);
        return R.ok(payload);
    }
}
