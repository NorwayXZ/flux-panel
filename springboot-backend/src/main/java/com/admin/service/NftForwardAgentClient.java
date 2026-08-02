package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import org.springframework.stereotype.Component;

@Component
public class NftForwardAgentClient {
    public boolean isOnline(Long nodeId) {
        return WebSocketServer.isNodeOnline(nodeId);
    }

    public GostDto preflight(Long nodeId, Object payload) {
        return WebSocketServer.send_msg(nodeId, payload, "NftForwardPreflight", 15);
    }

    public GostDto apply(Long nodeId, Object payload) {
        return WebSocketServer.send_msg(nodeId, payload, "NftForwardApply", 20);
    }

    public GostDto status(Long nodeId) {
        return WebSocketServer.send_msg(nodeId, java.util.Map.of(), "NftForwardStatus", 15);
    }

    public GostDto tcpProbe(Long nodeId, String address, int port) {
        return WebSocketServer.send_msg(nodeId,
                java.util.Map.of("ip", address, "port", port, "count", 1, "timeout", 3000), "TcpPing", 10);
    }
}
