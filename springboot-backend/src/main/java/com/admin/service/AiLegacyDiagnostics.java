package com.admin.service;

import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Only fixed read-only probes can cross the legacy terminal channel. */
@Service
public class AiLegacyDiagnostics {
  private final JdbcTemplate db;
  private final ConcurrentHashMap<String, Probe> probes = new ConcurrentHashMap<>();
  private final Semaphore slots = new Semaphore(2);

  record Probe(
      long node,
      String command,
      String marker,
      java.io.ByteArrayOutputStream output,
      CompletableFuture<String> result) {}

  public AiLegacyDiagnostics(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String, Object> inspect(long node, String kind, int timeout) throws Exception {
    if (!Set.of("inspect_disk", "inspect_agent_upgrade").contains(kind))
      throw new IllegalArgumentException("旧节点仅支持磁盘与升级只读诊断，请先升级 Agent");
    if (!slots.tryAcquire()) throw new IllegalStateException("旧节点诊断通道繁忙，请稍后重试");
    String id = "ai-probe-" + UUID.randomUUID();
    String marker = "AI_PROBE_" + UUID.randomUUID().toString().replace("-", "");
    Probe probe =
        new Probe(
            node,
            command(node, kind, marker),
            marker,
            new java.io.ByteArrayOutputStream(),
            new CompletableFuture<>());
    probes.put(id, probe);
    try {
      if (!send(node, "TerminalOpen", Map.of("sessionId", id, "cols", 120, "rows", 32)))
        throw new IllegalStateException("节点终端通道不可用");
      String output = probe.result().get(Math.max(1, Math.min(timeout, 40)), TimeUnit.SECONDS);
      return Map.of("transport", "legacy_readonly_terminal", "output", output);
    } catch (TimeoutException e) {
      throw new IllegalStateException("旧节点终端诊断超时：终端协议可能不受支持或命令未返回；不能据此判断磁盘已满");
    } catch (ExecutionException e) {
      throw new IllegalStateException(Objects.toString(e.getCause().getMessage(), "旧节点终端诊断失败"));
    } finally {
      probes.remove(id);
      send(node, "TerminalClose", Map.of("sessionId", id, "reason", "只读诊断结束"));
      slots.release();
    }
  }

  String command(long node, String kind, String marker) {
    String disk =
        "printf '\\n"
            + "=== DISK ===\\n"
            + "'; df -Pk; printf '\\n"
            + "=== INODES ===\\n"
            + "'; df -Pi; printf '\\n"
            + "=== LOGS ===\\n"
            + "'; du -xk --max-depth=2 /var/log 2>/dev/null | sort -nr | head -20; printf '\\n"
            + "=== JOURNAL ===\\n"
            + "'; journalctl --disk-usage 2>&1; ";
    String extra = "";
    if (kind.equals("inspect_agent_upgrade")) {
      extra =
          "printf '\\n"
              + "=== PLATFORM ===\\n"
              + "'; uname -sm; id -u; printf '\\n"
              + "=== TMP MOUNT ===\\n"
              + "'; findmnt -T /tmp -no TARGET,OPTIONS 2>&1; printf '\\n"
              + "=== AGENT SERVICE ===\\n"
              + "'; systemctl show gost -p ActiveState -p SubState -p ExecMainStatus --no-pager"
              + " 2>&1; ";
      var rows =
          db.queryForList(
              "SELECT task_id FROM agent_upgrade_task WHERE node_id=? ORDER BY id DESC LIMIT 1",
              node);
      if (!rows.isEmpty()) {
        String task = Objects.toString(rows.get(0).get("task_id"), "");
        if (task.matches("[a-fA-F0-9-]{36}"))
          extra +=
              "printf '\\n=== UPGRADE LOG ===\\n'; tail -c 10000 /var/log/flux-agent-update-"
                  + task.substring(0, 12)
                  + ".log 2>&1; ";
      }
    }
    // Disable terminal echo before submitting probes; split marker prevents shell echo matching it.
    return "stty -echo; "
        + disk
        + extra
        + "printf '\\n%s%s\\n' '"
        + marker.substring(0, 20)
        + "' '"
        + marker.substring(20)
        + "'; stty echo\n";
  }

  boolean send(long node, String type, Map<String, Object> data) {
    return WebSocketServer.sendNodeEvent(node, type, new JSONObject(data));
  }

  public boolean handle(long node, JSONObject message) {
    JSONObject data = message.getJSONObject("data");
    String id = data == null ? null : data.getString("sessionId");
    Probe probe = id == null ? null : probes.get(id);
    if (probe == null || probe.node() != node) return false;
    switch (Objects.toString(message.getString("type"), "")) {
      case "TerminalOpened" ->
          send(
              node,
              "TerminalInput",
              Map.of(
                  "sessionId",
                  id,
                  "data",
                  Base64.getEncoder()
                      .encodeToString(probe.command().getBytes(StandardCharsets.UTF_8))));
      case "TerminalOutput" -> {
        byte[] bytes = Base64.getDecoder().decode(data.getString("data"));
        synchronized (probe) {
          if (bytes.length > 60000 - probe.output().size()) {
            probe.result().completeExceptionally(new IllegalStateException("诊断输出超出容量限制"));
          } else {
            probe.output().writeBytes(bytes);
            String text = probe.output().toString(StandardCharsets.UTF_8);
            int end = text.indexOf(probe.marker());
            if (end >= 0)
              probe
                  .result()
                  .complete(text.substring(0, end).replaceAll("\\u001B\\[[0-9;?]*[A-Za-z]", ""));
          }
        }
      }
      case "TerminalError", "TerminalClosed" ->
          probe
              .result()
              .completeExceptionally(
                  new IllegalStateException(
                      "旧节点终端诊断失败：" + Objects.toString(data.getString("message"), "通道关闭")));
    }
    return true;
  }
}
