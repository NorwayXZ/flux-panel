package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps a 5-minute sample of each node's CPU, memory and interface counters for 7 days. Agents
 * report these every few seconds, but until now they were only relayed to open browsers.
 */
@Slf4j
@Service
public class NodeMetricHistory {
  static final long INTERVAL_MS = 5 * 60_000L;
  static final long RETENTION_MS = 7 * 86_400_000L;
  private final JdbcTemplate db;
  private final Map<Long, Long> lastSample = new ConcurrentHashMap<>();

  public NodeMetricHistory(JdbcTemplate db) {
    this.db = db;
  }

  @PostConstruct
  public void initialize() {
    try {
      db.execute(
          "CREATE TABLE IF NOT EXISTS node_metric_sample (node_id bigint NOT NULL,sampled_at bigint"
              + " NOT NULL,cpu_percent decimal(6,2) NOT NULL,memory_percent decimal(6,2) NOT NULL,"
              + "rx_bytes bigint NOT NULL DEFAULT 0,tx_bytes bigint NOT NULL DEFAULT 0,uptime_seconds"
              + " bigint NOT NULL DEFAULT 0,PRIMARY KEY(node_id,sampled_at),KEY"
              + " idx_node_metric_time(sampled_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    } catch (Exception e) {
      log.warn("node_metric_sample unavailable: {}", e.getMessage());
    }
  }

  /** Called for every Agent system report; stores at most one sample per node per interval. */
  public void record(long nodeId, String payload) {
    long now = System.currentTimeMillis();
    Long previous = lastSample.get(nodeId);
    if (previous != null && now - previous < INTERVAL_MS) return;
    try {
      JSONObject data = JSONObject.parseObject(payload);
      if (data == null || !data.containsKey("memory_usage")) return;
      lastSample.put(nodeId, now);
      db.update(
          "INSERT IGNORE INTO node_metric_sample(node_id,sampled_at,cpu_percent,memory_percent,"
              + "rx_bytes,tx_bytes,uptime_seconds) VALUES(?,?,?,?,?,?,?)",
          nodeId,
          now,
          clampPercent(data.getDoubleValue("cpu_usage")),
          clampPercent(data.getDoubleValue("memory_usage")),
          Math.max(0, data.getLongValue("bytes_received")),
          Math.max(0, data.getLongValue("bytes_transmitted")),
          Math.max(0, data.getLongValue("uptime")));
    } catch (Exception e) {
      log.debug("node metric sample skipped for {}: {}", nodeId, e.getMessage());
    }
  }

  @Scheduled(cron = "0 35 4 * * *")
  public void cleanup() {
    try {
      db.update("DELETE FROM node_metric_sample WHERE sampled_at<?", System.currentTimeMillis() - RETENTION_MS);
    } catch (Exception e) {
      log.debug("node metric cleanup skipped: {}", e.getMessage());
    }
  }

  /** Per node: latest values, 1h/24h/7d averages and peaks, and an hourly series for the last day. */
  public List<Map<String, Object>> summary(Collection<Long> nodes) {
    List<Map<String, Object>> result = new ArrayList<>();
    long now = System.currentTimeMillis();
    for (Long node : nodes) {
      List<Map<String, Object>> rows =
          db.queryForList(
              "SELECT sampled_at,cpu_percent,memory_percent,rx_bytes,tx_bytes,uptime_seconds FROM"
                  + " node_metric_sample WHERE node_id=? AND sampled_at>=? ORDER BY sampled_at",
              node,
              now - RETENTION_MS);
      result.add(summarize(node, rows, now));
    }
    return result;
  }

  static Map<String, Object> summarize(long node, List<Map<String, Object>> rows, long now) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("nodeId", node);
    view.put("samples", rows.size());
    if (rows.isEmpty()) {
      view.put("note", "暂无历史样本：面板升级后每 5 分钟记录一次，保留 7 天");
      return view;
    }
    Map<String, Object> last = rows.get(rows.size() - 1);
    view.put(
        "latest",
        Map.of(
            "at", last.get("sampled_at"),
            "cpuPercent", number(last.get("cpu_percent")),
            "memoryPercent", number(last.get("memory_percent")),
            "uptimeHours", Math.round(number(last.get("uptime_seconds")) / 360.0) / 10.0));
    for (var window : List.of(Map.entry("1h", 3_600_000L), Map.entry("24h", 86_400_000L), Map.entry("7d", RETENTION_MS))) {
      List<Map<String, Object>> part = rows.stream().filter(r -> number(r.get("sampled_at")) >= now - window.getValue()).toList();
      if (part.isEmpty()) continue;
      view.put(
          window.getKey(),
          Map.of(
              "cpuAvg", round(part.stream().mapToDouble(r -> number(r.get("cpu_percent"))).average().orElse(0)),
              "cpuMax", round(part.stream().mapToDouble(r -> number(r.get("cpu_percent"))).max().orElse(0)),
              "memoryAvg", round(part.stream().mapToDouble(r -> number(r.get("memory_percent"))).average().orElse(0)),
              "memoryMax", round(part.stream().mapToDouble(r -> number(r.get("memory_percent"))).max().orElse(0)),
              "throughputMbps", throughput(part)));
    }
    List<Map<String, Object>> hourly = new ArrayList<>();
    for (int h = 23; h >= 0; h--) {
      long from = now - (h + 1) * 3_600_000L, to = now - h * 3_600_000L;
      List<Map<String, Object>> part =
          rows.stream().filter(r -> number(r.get("sampled_at")) >= from && number(r.get("sampled_at")) < to).toList();
      if (part.isEmpty()) continue;
      hourly.add(
          Map.of(
              "hourEnding", to,
              "cpu", round(part.stream().mapToDouble(r -> number(r.get("cpu_percent"))).average().orElse(0)),
              "memory", round(part.stream().mapToDouble(r -> number(r.get("memory_percent"))).average().orElse(0)),
              "throughputMbps", throughput(part)));
    }
    view.put("last24hHourly", hourly);
    return view;
  }

  /** Average receive/transmit rate from counter deltas; counter resets (reboots) are skipped. */
  static Map<String, Object> throughput(List<Map<String, Object>> rows) {
    double rx = 0, tx = 0, seconds = 0;
    for (int i = 1; i < rows.size(); i++) {
      double dt = (number(rows.get(i).get("sampled_at")) - number(rows.get(i - 1).get("sampled_at"))) / 1000.0;
      double drx = number(rows.get(i).get("rx_bytes")) - number(rows.get(i - 1).get("rx_bytes"));
      double dtx = number(rows.get(i).get("tx_bytes")) - number(rows.get(i - 1).get("tx_bytes"));
      if (dt <= 0 || dt > 3600 || drx < 0 || dtx < 0) continue;
      rx += drx;
      tx += dtx;
      seconds += dt;
    }
    if (seconds == 0) return Map.of();
    return Map.of("rx", round(rx * 8 / seconds / 1e6), "tx", round(tx * 8 / seconds / 1e6));
  }

  static double clampPercent(double value) {
    return Double.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
  }

  static double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : 0;
  }

  static double round(double value) {
    return Math.round(value * 10) / 10.0;
  }
}
