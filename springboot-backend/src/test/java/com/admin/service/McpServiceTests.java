package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.admin.common.lang.R;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class McpServiceTests {
    private final NodeService nodes = mock(NodeService.class);
    private final TunnelService tunnels = mock(TunnelService.class);
    private final ForwardService forwards = mock(ForwardService.class);
    private final McpService mcp = new McpService(nodes, tunnels, forwards);

    private JSONObject call(String method, Object params) {
        JSONObject message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("id", 1);
        message.put("method", method);
        if (params != null) message.put("params", params);
        return mcp.handle(message);
    }

    @Test
    void initializeNegotiatesTheProtocolAndAdvertisesTools() {
        var result = call("initialize", Map.of("protocolVersion", "2025-03-26")).getJSONObject("result");
        assertEquals("2025-03-26", result.getString("protocolVersion"));
        assertNotNull(result.getJSONObject("capabilities").getJSONObject("tools"));
        var unknown = call("initialize", Map.of("protocolVersion", "1999-01-01")).getJSONObject("result");
        assertEquals(McpService.LATEST_PROTOCOL, unknown.getString("protocolVersion"));

        var tools = call("tools/list", null).getJSONObject("result").getJSONArray("tools");
        assertEquals(8, tools.size());
        var diagnose = tools.stream().map(JSONObject.class::cast).filter(t -> t.getString("name").equals("diagnose_forward")).findFirst().orElseThrow();
        assertEquals(List.of("forwardId"), diagnose.getJSONObject("inputSchema").getJSONArray("required").toJavaList(String.class));
    }

    @Test
    void notificationsGetNoResponseAndUnknownMethodsAnError() {
        JSONObject notification = new JSONObject();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        assertNull(mcp.handle(notification));
        assertEquals(-32601, call("resources/list", null).getJSONObject("error").getIntValue("code"));
    }

    @Test
    void listingsAreTrimmedAndToolsRunAsTheCaller() {
        when(forwards.getAllForwards()).thenReturn(R.ok(List.of(Map.of(
                "id", 3, "name", "NNC", "inPort", 1000, "remoteAddr", "203.0.113.1:443", "status", 1,
                "routeConfig", "{\"huge\":\"internal\"}", "targetHealth", "[...]"))));
        var listed = call("tools/call", Map.of("name", "list_forwards")).getJSONObject("result");
        assertFalse(listed.getBooleanValue("isError"));
        var rows = JSON.parseArray(listed.getJSONArray("content").getJSONObject(0).getString("text"));
        assertEquals("NNC", rows.getJSONObject(0).getString("name"));
        assertFalse(rows.getJSONObject(0).containsKey("routeConfig"));

        when(forwards.pauseForward(3L)).thenReturn(R.err("无权限操作此转发"));
        var denied = call("tools/call", Map.of("name", "pause_forward", "arguments", Map.of("forwardId", 3))).getJSONObject("result");
        assertTrue(denied.getBooleanValue("isError"));
        assertEquals("无权限操作此转发", denied.getJSONArray("content").getJSONObject(0).getString("text"));

        var missing = call("tools/call", Map.of("name", "resume_forward", "arguments", Map.of())).getJSONObject("result");
        assertTrue(missing.getBooleanValue("isError"));
        verify(forwards, never()).resumeForward(any());

        var unknown = call("tools/call", Map.of("name", "drop_database")).getJSONObject("result");
        assertTrue(unknown.getBooleanValue("isError"));
    }
}
