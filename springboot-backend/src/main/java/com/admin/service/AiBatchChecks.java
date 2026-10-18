package com.admin.service;

import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One check across many nodes with a per-node status, rendered as a table in the chat. Disk runs a
 * fixed read-only probe through the terminal channel; the others read panel data only.
 */
@Service
public class AiBatchChecks {
  static final List<String> CHECKS = List.of("disk", "resources", "agent", "availability");
  private final JdbcTemplate db;
  private final AiLegacyDiagnostics terminal;
  private final NodeMetricHistory metrics;

  public AiBatchChecks(JdbcTemplate db, AiLegacyDiagnostics terminal, NodeMetricHistory metrics) {
    this.db = db;
    this.terminal = terminal;
    this.metrics = metrics;
  }

  public Map<String, Object> run(String check, Collection<Long> nodes, int timeoutSeconds) {
    if (!CHECKS.contains(check)) throw new IllegalArgumentException("不支持的批量检查：" + check);
    if (nodes.isEmpty()) throw new IllegalArgumentException("请先选择节点");
    Map<Long, String> names = new HashMap<>();
    Map<Long, String> versions = new HashMap<>();
    String marks = String.join(",", Collections.nCopies(nodes.size(), "?"));
    for (var row : db.queryForList("SELECT id,name,version FROM node WHERE id IN (" + marks + ")", nodes.toArray())) {
      long id = ((Number) row.get("id")).longValue();
      names.put(id, Objects.toString(row.get("name"), "节点 " + id));
      versions.put(id, Objects.toString(row.get("version"), ""));
    }
    long deadline = System.currentTimeMillis() + Math.max(10, timeoutSeconds) * 1000L;
    List<Map<String, Object>> rows = new ArrayList<>();
    if (check.equals("disk")) {
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Map<Long, Future<Map<String, Object>>> futures = new LinkedHashMap<>();
        for (Long node : nodes) futures.put(node, pool.submit(() -> disk(node, deadline)));
        for (var entry : futures.entrySet()) {
          Map<String, Object> row;
          try {
            row = entry.getValue().get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
          } catch (Exception e) {
            entry.getValue().cancel(true);
            row = row("unknown", "", "超时或失败：" + AiOpsService.safeError(e instanceof Exception ex ? ex : new IllegalStateException("超时")));
          }
          rows.add(withNode(entry.getKey(), names, row));
        }
      } finally {
        pool.shutdownNow();
      }
    } else {
      for (Long node : nodes) rows.add(withNode(node, names, switch (check) {
        case "resources" -> resources(node);
        case "agent" -> agent(node, versions.get(node));
        default -> availability(node);
      }));
    }
    rows.sort(Comparator.comparingInt((Map<String, Object> r) -> rank(r.get("status").toString())).thenComparing(r -> r.get("node").toString()));
    Map<String, Long> counts = new TreeMap<>();
    rows.forEach(r -> counts.merge(r.get("status").toString(), 1L, Long::sum));
    return Map.of("check", check, "rows", rows, "counts", counts,
        "note", "status: ok 正常 / warn 需留意 / fail 异常 / unknown 无法检查；界面会把结果显示成表格，回复里只需总结异常节点和建议");
  }

  private Map<String, Object> disk(long node, long deadline) throws Exception {
    if (!WebSocketServer.isNodeOnline(node)) return row("fail", "", "节点离线");
    int seconds = (int) Math.max(5, Math.min(40, (deadline - System.currentTimeMillis()) / 1000));
    String output = null;
    for (int attempt = 0; output == null; attempt++) {
      try {
        output = Objects.toString(terminal.inspect(node, "inspect_disk", seconds).get("output"), "");
      } catch (IllegalStateException busy) {
        // The terminal channel allows two sessions; wait for a free one while time remains.
        if (!Objects.toString(busy.getMessage(), "").contains("繁忙") || attempt >= 20
            || System.currentTimeMillis() + 2000 > deadline) throw busy;
        Thread.sleep(1500);
      }
    }
    List<Map<String, Object>> mounts = parseDf(output);
    if (mounts.isEmpty()) return row("unknown", "", "未能读取 df 输出");
    Map<String, Object> worst = mounts.stream().max(Comparator.comparingInt(m -> (int) m.get("usedPercent"))).get();
    int used = (int) worst.get("usedPercent");
    String value = worst.get("mount") + " " + used + "%";
    String detail = "可用 " + Math.round(((Number) worst.get("availKb")).longValue() / 1048576.0 * 10) / 10.0 + " GB";
    return row(used >= 90 ? "fail" : used >= 80 ? "warn" : "ok", value, detail);
  }

  /** Real filesystems from `df -Pk` output; pseudo filesystems and loop mounts are ignored. */
  static List<Map<String, Object>> parseDf(String output) {
    List<Map<String, Object>> mounts = new ArrayList<>();
    boolean inDisk = false;
    for (String line : output.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("===")) {
        inDisk = trimmed.contains("DISK");
        continue;
      }
      if (!inDisk || trimmed.startsWith("Filesystem")) continue;
      String[] parts = trimmed.split("\\s+");
      if (parts.length < 6 || !parts[4].endsWith("%")) continue;
      String fs = parts[0], mount = parts[5];
      if (fs.matches("(tmpfs|devtmpfs|overlay|shm|udev|none|efivarfs)") || fs.startsWith("/dev/loop")
          || mount.startsWith("/snap") || mount.startsWith("/boot/efi") || mount.startsWith("/run")) continue;
      try {
        mounts.add(Map.of("mount", mount, "usedPercent", Integer.parseInt(parts[4].replace("%", "")),
            "usedKb", Long.parseLong(parts[2]), "availKb", Long.parseLong(parts[3]), "sizeKb", Long.parseLong(parts[1])));
      } catch (NumberFormatException ignored) {
      }
    }
    return mounts;
  }

  private Map<String, Object> resources(long node) {
    Map<String, Object> view = NodeMetricHistory.summarize(node, List.of(), System.currentTimeMillis());
    var summaries = metrics == null ? List.<Map<String, Object>>of() : metrics.summary(List.of(node));
    if (!summaries.isEmpty()) view = summaries.get(0);
    @SuppressWarnings("unchecked") Map<String, Object> day = (Map<String, Object>) view.get("24h");
    @SuppressWarnings("unchecked") Map<String, Object> latest = (Map<String, Object>) view.get("latest");
    if (latest == null) return row(WebSocketServer.isNodeOnline(node) ? "unknown" : "fail", "", WebSocketServer.isNodeOnline(node) ? "暂无资源样本" : "节点离线");
    double cpu = ((Number) day.get("cpuMax")).doubleValue(), mem = ((Number) day.get("memoryMax")).doubleValue();
    String status = !WebSocketServer.isNodeOnline(node) ? "fail" : cpu >= 95 || mem >= 95 ? "fail" : cpu >= 80 || mem >= 85 ? "warn" : "ok";
    return row(status, "CPU " + latest.get("cpuPercent") + "% · 内存 " + latest.get("memoryPercent") + "%",
        "24h 峰值 CPU " + cpu + "% / 内存 " + mem + "%");
  }

  private Map<String, Object> agent(long node, String version) {
    boolean online = WebSocketServer.isNodeOnline(node);
    boolean current = AgentVersionUtil.isReleaseAtLeast(Objects.toString(version, ""), AgentUpgradeService.TARGET_VERSION);
    return row(!online ? "fail" : current ? "ok" : "warn", version == null || version.isBlank() ? "未知" : version,
        !online ? "节点离线" : current ? "已是面板目标版本" : "可升级到 " + AgentUpgradeService.TARGET_VERSION);
  }

  private Map<String, Object> availability(long node) {
    long now = System.currentTimeMillis();
    var history = db.queryForList("SELECT status,started_at,ended_at FROM monitoring_history WHERE resource_type='node' AND resource_id=?"
        + " AND (ended_at IS NULL OR ended_at>=?) ORDER BY started_at", node, now - 86_400_000L);
    Double value = AiPanelContext.availability(history, now - 86_400_000L, now);
    long incidents = history.stream().filter(h -> List.of("offline", "degraded").contains(h.get("status"))).count();
    if (value == null) return row(WebSocketServer.isNodeOnline(node) ? "unknown" : "fail", "", "暂无监控记录");
    return row(value < 95 ? "fail" : value < 99.9 ? "warn" : "ok", value + "%", "24 小时异常 " + incidents + " 次");
  }

  private static Map<String, Object> row(String status, String value, String detail) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("status", status);
    row.put("value", value);
    row.put("detail", detail);
    return row;
  }

  private static Map<String, Object> withNode(long node, Map<Long, String> names, Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("nodeId", node);
    result.put("node", names.getOrDefault(node, "节点 " + node));
    result.putAll(row);
    return result;
  }

  private static int rank(String status) {
    return switch (status) {
      case "fail" -> 0;
      case "warn" -> 1;
      case "unknown" -> 2;
      default -> 3;
    };
  }
}
