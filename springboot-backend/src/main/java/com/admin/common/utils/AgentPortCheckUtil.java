package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public final class AgentPortCheckUtil {
    public static final String MIN_AGENT_VERSION = "2.12.0";

    private AgentPortCheckUtil() {
    }

    public static Result check(Node node, List<Check> checks) {
        if (node == null || checks == null || checks.isEmpty()) {
            return Result.available(false, "无需检查");
        }
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            return Result.available(false, "Agent 版本低于 " + MIN_AGENT_VERSION + "，沿用面板账本检查");
        }

        JSONArray payloadChecks = new JSONArray();
        for (Check check : checks) {
            JSONObject item = new JSONObject();
            item.put("network", check.getNetwork());
            item.put("host", StringUtils.defaultString(check.getHost()));
            item.put("port", check.getPort());
            payloadChecks.add(item);
        }
        JSONObject payload = new JSONObject();
        payload.put("checks", payloadChecks);

        GostDto response = WebSocketServer.send_msg(node.getId(), payload, "PortCheck");
        return parseResponse(response);
    }

    public static Result checkConnector(Long connectorId, List<Check> checks) {
        if (connectorId == null || checks == null || checks.isEmpty()) {
            return Result.available(false, "无需检查");
        }
        GostDto response = WebSocketServer.sendConnectorMsg(connectorId, payload(checks), "PortCheck");
        return parseResponse(response);
    }

    private static JSONObject payload(List<Check> checks) {
        JSONArray payloadChecks = new JSONArray();
        for (Check check : checks) {
            JSONObject item = new JSONObject();
            item.put("network", check.getNetwork());
            item.put("host", StringUtils.defaultString(check.getHost()));
            item.put("port", check.getPort());
            payloadChecks.add(item);
        }
        JSONObject payload = new JSONObject();
        payload.put("checks", payloadChecks);
        return payload;
    }

    private static Result parseResponse(GostDto response) {
        if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
            String message = response == null ? "Agent 无响应" : StringUtils.defaultIfBlank(response.getMsg(), "Agent 无响应");
            return Result.unavailable(true, "系统端口检查失败：" + message, Collections.emptyList());
        }

        JSONObject data = response.getData() instanceof JSONObject
                ? (JSONObject) response.getData()
                : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
        boolean available = Boolean.TRUE.equals(data.getBoolean("available"));
        List<String> conflicts = new ArrayList<>();
        JSONArray results = data.getJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                if (!Boolean.TRUE.equals(item.getBoolean("available"))) {
                    conflicts.add(item.getString("network") + " " + item.getString("address")
                            + "：" + StringUtils.defaultIfBlank(item.getString("error"), "已被占用"));
                }
            }
        }
        return available
                ? Result.available(true, "系统端口可用")
                : Result.unavailable(true, "端口已被服务器上的其他程序占用", conflicts);
    }

    @Data
    public static class Check {
        private final String network;
        private final String host;
        private final Integer port;
    }

    @Data
    public static class Result {
        private final boolean checked;
        private final boolean available;
        private final String message;
        private final List<String> conflicts;

        private static Result available(boolean checked, String message) {
            return new Result(checked, true, message, Collections.emptyList());
        }

        private static Result unavailable(boolean checked, String message, List<String> conflicts) {
            return new Result(checked, false, message, conflicts);
        }
    }
}
