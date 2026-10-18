package com.admin.service;

import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bounded terminal sessions shared by fixed probes and explicitly authorized chat commands. */
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
    String id = "ai-probe-" + UUID.randomUUID();
    String marker = "AI_PROBE_" + UUID.randomUUID().toString().replace("-", "");
    String command = command(node, kind, marker);
    return exchange(node, command, marker, Math.min(timeout, 40), "legacy_readonly_terminal");
  }

  public Map<String, Object> execute(long node, String script, int timeout) throws Exception {
    if (script == null || script.isBlank() || script.length() > 16000 || script.indexOf('\0') >= 0)
      throw new IllegalArgumentException("命令为空、过长或包含无效字符");
    String marker = "AI_EXEC_" + UUID.randomUUID().toString().replace("-", "");
    String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    String command = "stty -echo; printf '%s' '" + encoded
        + "' | base64 -d | /bin/sh; ai_exit=$?; printf '\\n%s%s:%s\\n' '"
        + marker.substring(0, 20) + "' '" + marker.substring(20) + "' \"$ai_exit\"; stty echo\n";
    Map<String, Object> data = exchange(node, command, marker, timeout, "authorized_terminal");
    String output = data.get("output").toString();
    int boundary = output.lastIndexOf("\nAI_EXIT_CODE:");
    if (boundary < 0) throw new IllegalStateException("未取得命令退出状态，不能确认执行完成");
    return Map.of("transport", "authorized_terminal", "output", output.substring(0, boundary),
        "exitCode", Integer.parseInt(output.substring(boundary + 14).trim()));
  }

  private Map<String, Object> exchange(long node, String command, String marker, int timeout,
      String transport) throws Exception {
    String id = "ai-terminal-" + UUID.randomUUID();
    if (!slots.tryAcquire()) throw new IllegalStateException("旧节点诊断通道繁忙，请稍后重试");
    Probe probe =
        new Probe(
            node, command, marker, new java.io.ByteArrayOutputStream(), new CompletableFuture<>());
    probes.put(id, probe);
    try {
      if (!send(node, "TerminalOpen", Map.of("sessionId", id, "cols", 120, "rows", 32)))
        throw new IllegalStateException("节点终端通道不可用");
      String output = probe.result().get(Math.max(1, Math.min(timeout, 140)), TimeUnit.SECONDS);
      return Map.of("transport", transport, "output", output);
    } catch (TimeoutException e) {
      throw new IllegalStateException("终端执行超时，完成状态未知；关闭终端不保证后台任务停止，请先检查状态再重试");
    } catch (ExecutionException e) {
      throw new IllegalStateException(Objects.toString(e.getCause().getMessage(), "旧节点终端诊断失败"));
    } finally {
      probes.remove(id);
      try {
        send(node, "TerminalClose", Map.of("sessionId", id, "reason", "只读诊断结束"));
      } finally {
        slots.release();
      }
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
            + "'; du -xk -d 2 /var/log 2>&1 | sort -nr | head -20; printf '\\n"
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
      extra +=
          "printf '\\n"
              + "=== OS AND INIT ===\\n"
              + "'; cat /etc/os-release 2>/dev/null; for x in curl wget sh rc-service rc-update"
              + " systemctl systemd-run setsid nohup; do command -v \"$x\" || true; done; if"
              + " command -v rc-service >/dev/null 2>&1; then rc-service gost status 2>&1; fi;"
              + " printf '\\n"
              + "=== INSTALLER NETWORK ===\\n"
              + "'; curl -I -L --connect-timeout 4 --max-time 8 "
              + "https://raw.githubusercontent.com/NorwayXZ/flux-panel/"
              + AgentUpgradeService.TARGET_VERSION
              + "/install.sh 2>&1 | head -15; ";
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
            if (end >= 0 && (!probe.marker().startsWith("AI_EXEC_") || text.indexOf('\n', end) >= 0))
              probe
                  .result()
                  .complete((text.substring(0, end)
                      + (probe.marker().startsWith("AI_EXEC_")
                          ? "\nAI_EXIT_CODE:" + text.substring(end + probe.marker().length() + 1, text.indexOf('\n', end)).trim()
                          : "")).replaceAll("\\u001B\\[[0-9;?]*[A-Za-z]", ""));
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
