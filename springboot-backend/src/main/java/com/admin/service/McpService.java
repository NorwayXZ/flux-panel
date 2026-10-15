package com.admin.service;

import com.admin.common.lang.R;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Model Context Protocol server (JSON-RPC over Streamable HTTP, stateless) so AI assistants can
 * inspect and operate the panel. Every tool runs as the caller, through the same services as the
 * panel's own pages, so it has exactly the caller's permissions.
 */
@Service
public class McpService {
    static final String LATEST_PROTOCOL = "2025-06-18";
    static final List<String> PROTOCOLS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final NodeService nodes;
    private final TunnelService tunnels;
    private final ForwardService forwards;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    record Tool(String description, JSONObject inputSchema, Function<JSONObject, R> call, List<String> fields) {}

    public McpService(NodeService nodes, TunnelService tunnels, ForwardService forwards) {
        this.nodes = nodes;
        this.tunnels = tunnels;
        this.forwards = forwards;
        tools.put("list_nodes", new Tool("列出你可见的节点：ID、名称、地址、在线状态和 Agent 版本。",
                schema(), args -> this.nodes.getAllNodes(),
                List.of("id", "name", "ip", "serverIp", "status", "version", "portSta", "portEnd")));
        tools.put("list_tunnels", new Tool("列出隧道：入口和出口节点、传输协议、状态；CNIX 共享隧道附带加速入口和验证状态。",
                schema(), args -> this.tunnels.getAllTunnels(),
                List.of("id", "name", "type", "inNodeId", "outNodeId", "inIp", "outIp", "protocol", "status", "sharedRelay")));
        tools.put("list_forwards", new Tool("列出转发：入口地址和端口、落地地址、所属隧道、状态和累计流量（字节）。",
                schema(), args -> this.forwards.getAllForwards(),
                List.of("id", "name", "tunnelId", "tunnelName", "inIp", "inPort", "remoteAddr", "status", "strategy",
                        "protocolMode", "inFlow", "outFlow", "userName")));
        tools.put("diagnose_forward", new Tool("诊断一条转发：从入口经隧道到每个落地地址实测连通性和延迟。",
                schema("forwardId", "转发 ID"), args -> this.forwards.diagnoseForward(args.getLong("forwardId")), null));
        tools.put("diagnose_tunnel", new Tool("诊断一条隧道：逐跳检查节点之间的连通性；CNIX 共享隧道检查加速入口和出口监听。",
                schema("tunnelId", "隧道 ID"), args -> this.tunnels.diagnoseTunnel(args.getLong("tunnelId")), null));
        tools.put("pause_forward", new Tool("暂停一条转发，入口端口停止接受连接，配置保留。",
                schema("forwardId", "转发 ID"), args -> this.forwards.pauseForward(args.getLong("forwardId")), null));
        tools.put("resume_forward", new Tool("恢复一条已暂停的转发。",
                schema("forwardId", "转发 ID"), args -> this.forwards.resumeForward(args.getLong("forwardId")), null));
        tools.put("forward_route_events", new Tool("查看一条转发最近的线路切换记录：时间、原因、从哪条隧道切到哪条。",
                schema("forwardId", "转发 ID"), args -> this.forwards.getRouteEvents(args.getLong("forwardId")), null));
    }

    private static JSONObject schema(String... idParams) {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject properties = new JSONObject();
        List<String> required = new ArrayList<>();
        for (int i = 0; i + 1 < idParams.length; i += 2) {
            JSONObject property = new JSONObject();
            property.put("type", "integer");
            property.put("description", idParams[i + 1]);
            properties.put(idParams[i], property);
            required.add(idParams[i]);
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    /** Handles one JSON-RPC message; returns null for notifications, which get no response. */
    public JSONObject handle(JSONObject message) {
        Object id = message.get("id");
        String method = message.getString("method");
        if (method == null) return error(id, -32600, "Invalid Request");
        if (!message.containsKey("id")) return null; // notification, e.g. notifications/initialized
        JSONObject params = message.getJSONObject("params");
        if (params == null) params = new JSONObject();
        switch (method) {
            case "initialize":
                return result(id, initialize(params));
            case "ping":
                return result(id, new JSONObject());
            case "tools/list":
                return result(id, listTools());
            case "tools/call":
                return result(id, callTool(params));
            default:
                return error(id, -32601, "Method not found: " + method);
        }
    }

    private JSONObject initialize(JSONObject params) {
        String requested = params.getString("protocolVersion");
        JSONObject result = new JSONObject();
        result.put("protocolVersion", PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL);
        JSONObject capabilities = new JSONObject();
        capabilities.put("tools", new JSONObject());
        result.put("capabilities", capabilities);
        result.put("serverInfo", Map.of("name", "cloudnest-panel", "title", "云巢 CloudNest", "version", "1"));
        result.put("instructions", "云巢面板：查看节点、隧道和转发，诊断连通性，暂停或恢复转发。"
                + "所有操作以令牌所属账号的权限执行。");
        return result;
    }

    private JSONObject listTools() {
        JSONArray list = new JSONArray();
        tools.forEach((name, tool) -> {
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("description", tool.description());
            item.put("inputSchema", tool.inputSchema());
            list.add(item);
        });
        JSONObject result = new JSONObject();
        result.put("tools", list);
        return result;
    }

    private JSONObject callTool(JSONObject params) {
        String name = params.getString("name");
        Tool tool = tools.get(name);
        if (tool == null) return toolResult("未知工具：" + name, true);
        JSONObject args = params.getJSONObject("arguments");
        if (args == null) args = new JSONObject();
        for (Object required : tool.inputSchema().getJSONArray("required") == null
                ? new JSONArray() : tool.inputSchema().getJSONArray("required")) {
            if (args.get(required.toString()) == null) return toolResult("缺少参数：" + required, true);
        }
        R response;
        try {
            response = tool.call().apply(args);
        } catch (RuntimeException e) {
            return toolResult("执行失败：" + e.getMessage(), true);
        }
        if (response == null) return toolResult("没有返回结果", true);
        if (response.getCode() != 0) return toolResult(response.getMsg(), true);
        Object data = tool.fields() == null ? response.getData() : pick(response.getData(), tool.fields());
        return toolResult(data == null ? response.getMsg() : JSON.toJSONString(data), false);
    }

    /** Keeps the listed fields of each row, so listings stay short in the assistant's context. */
    static Object pick(Object data, List<String> fields) {
        // Round-trip through text: toJSON can hand back the original list unconverted.
        Object json = JSON.parse(JSON.toJSONString(data));
        if (!(json instanceof JSONArray rows)) return json;
        JSONArray picked = new JSONArray();
        for (Object row : rows) {
            if (!(row instanceof JSONObject object)) {
                picked.add(row);
                continue;
            }
            JSONObject item = new JSONObject(true);
            for (String field : fields) if (object.get(field) != null) item.put(field, object.get(field));
            picked.add(item);
        }
        return picked;
    }

    private static JSONObject toolResult(String text, boolean isError) {
        JSONObject content = new JSONObject();
        content.put("type", "text");
        content.put("text", text == null ? "" : text);
        JSONObject result = new JSONObject();
        result.put("content", List.of(content));
        result.put("isError", isError);
        return result;
    }

    private static JSONObject result(Object id, JSONObject result) {
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    public static JSONObject error(Object id, int code, String message) {
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}
