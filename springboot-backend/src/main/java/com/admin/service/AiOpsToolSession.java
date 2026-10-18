package com.admin.service;

import com.admin.common.dto.GostDto;
import com.alibaba.fastjson2.*;
import java.util.*;

/** One turn's authorization and observations; earlier chat turns cannot authorize a repair. */
final class AiOpsToolSession {
  private final AiOpsService ops;
  private final JSONObject policy;
  private final Set<Long> nodes;
  private final Set<String> allowed = new HashSet<>();
  private final Set<Long> checked = new HashSet<>(),
      disks = new HashSet<>(),
      upgrades = new HashSet<>(),
      legacy = new HashSet<>();
  private final Set<String> services = new HashSet<>();
  private final long started;
  private int calls;

  AiOpsToolSession(AiOpsService ops, JSONObject policy, Set<Long> nodes, long started) {
    this.ops = ops;
    this.policy = policy;
    this.nodes = nodes;
    this.started = started;
    for (Object item : AiOpsService.tools(policy))
      allowed.add(((JSONObject) JSON.toJSON(item)).getJSONObject("function").getString("name"));
  }

  JSONObject invoke(JSONObject call) {
    JSONObject result = new JSONObject();
    try {
      if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("执行已停止");
      if (++calls > 30) throw new IllegalArgumentException("工具调用超过30次上限");
      if (call.getString("id") == null || call.getString("id").isBlank())
        throw new IllegalArgumentException("工具调用缺少 ID");
      JSONObject fn = call.getJSONObject("function");
      String name = fn.getString("name");
      JSONObject args = JSON.parseObject(fn.getString("arguments"));
      if (name.equals("panel_knowledge") && allowed.contains(name)) {
        result.put("data", ops.searchKnowledge(args.getString("query")));
        result.put("success", true);
        return result;
      }
      if (name.equals("node_speed_test") && allowed.contains(name)) {
        long source = args.getLongValue("sourceNodeId"), target = args.getLongValue("targetNodeId");
        if (!nodes.contains(source) || !nodes.contains(target))
          throw new IllegalArgumentException("测速的两个节点都必须在本轮所选节点中");
        int seconds = args.containsKey("seconds") ? args.getIntValue("seconds") : 8;
        result.put("data", ops.nodeSpeedTest(source, target, seconds));
        result.put("success", true);
        return result;
      }
      if (name.equals("inspect_panel") && allowed.contains(name)) {
        result.put("data", ops.inspectPanel(args.getString("section"), nodes));
        result.put("success", true);
        return result;
      }
      long node = args.getLongValue("nodeId");
      if (!nodes.contains(node) || !allowed.contains(name))
        throw new IllegalArgumentException("工具或节点超出任务权限");
      if (name.equals("network_probe")) {
        Long targetNode = args.containsKey("targetNodeId") ? args.getLong("targetNodeId") : null;
        Integer port = args.containsKey("port") ? args.getInteger("port") : null;
        result.put("data", ops.networkProbe(node, args.getString("kind"), args.getString("target"), targetNode, port,
            Math.min(90, AiOpsService.remainingSeconds(started))));
        result.put("success", true);
        return result;
      }
      if (name.equals("execute_command")) {
        Object data = ops.executeCommand(node, args.getString("command"),
            Math.min(140, AiOpsService.remainingSeconds(started)));
        result.put("data", data);
        result.put("success", ((Number) ((Map<?, ?>) data).get("exitCode")).intValue() == 0);
        return result;
      }
      if (name.equals("inspect_agent_upgrade")) {
        result.put("data", ops.inspectUpgrade(node));
        result.put("success", true);
        upgrades.add(node);
        return result;
      }
      if (name.equals("upgrade_agent")) {
        if (!upgrades.contains(node)) throw new IllegalArgumentException("升级前必须先检查升级状态");
        result.put("data", ops.requestUpgrade(node, policy.getIntValue("_chatOwner")));
        result.put("success", true);
        return result;
      }
      String service = Objects.toString(args.getString("service"), "");
      if (name.contains("service") && !policy.getJSONArray("serviceAllowlist").contains(service))
        throw new IllegalArgumentException("服务超出白名单");
      if ((name.equals("vacuum_journal") || name.equals("cleanup_rotated_logs"))
          && !disks.contains(node)) throw new IllegalArgumentException("清理前必须先检查该节点磁盘");
      if (name.equals("restart_service") && !services.contains(node + ":" + service))
        throw new IllegalArgumentException("重启前必须先检查该服务");
      if (!checked.contains(node)) {
        if (!ops.nodeOnline(node)) throw new IllegalStateException("节点离线");
        GostDto capabilities =
            ops.callAgent(
                node,
                Map.of(),
                "OpsCapabilities",
                Math.min(8, AiOpsService.remainingSeconds(started)));
        if (AiOpsService.legacyCapabilities(capabilities)) legacy.add(node);
        else AiOpsService.requireOK(capabilities);
        checked.add(node);
      }
      if (legacy.contains(node)) {
        result.put(
            "data",
            ops.legacyProbe(node, name, Math.min(40, AiOpsService.remainingSeconds(started))));
        result.put("success", true);
        if (name.equals("inspect_disk")) disks.add(node);
        return result;
      }
      JSONObject payload = new JSONObject();
      payload.put("tool", name);
      payload.put("args", Map.of("service", service));
      payload.put("timeoutSeconds", Math.min(140, AiOpsService.remainingSeconds(started)));
      payload.put("allowLogCleanup", policy.getBooleanValue("allowLogCleanup"));
      payload.put("allowRepair", policy.getString("mode").equals("repair"));
      payload.put("allowlist", policy.getJSONArray("serviceAllowlist"));
      payload.put("retentionDays", policy.getIntValue("retentionDays"));
      payload.put("maxJournalMb", policy.getIntValue("maxJournalMb"));
      GostDto response =
          ops.callAgent(node, payload, "OpsToolCall", AiOpsService.remainingSeconds(started));
      if (response != null) result.put("data", response.getData());
      AiOpsService.requireOK(response);
      result.put("success", true);
      if (name.equals("inspect_disk")) disks.add(node);
      if (name.equals("inspect_service")) services.add(node + ":" + service);
    } catch (Exception error) {
      result.put("success", false);
      result.put("error", AiOpsService.safeError(error));
    }
    return result;
  }

  boolean exhausted() {
    return calls >= 30;
  }
}
