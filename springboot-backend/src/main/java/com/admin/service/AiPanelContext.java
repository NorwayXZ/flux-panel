package com.admin.service;

import com.alibaba.fastjson2.JSON;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Explicit projections avoid exposing passwords, identities and provider credentials. */
@Service
public class AiPanelContext {
  private final JdbcTemplate db;
  public AiPanelContext(JdbcTemplate db) { this.db = db; }

  static String guidance() {
    return "This is Flux Panel / CloudNest: nodes run Agents; tunnels describe ordered node paths;"
        + " forwards bind listen ports and destinations to tunnels, with candidate failover/latency/balance routes."
        + " Private proxies have separate protocols, routing rules and exits (VPNGate, Mysterium, custom providers)."
        + " Smart entry uses public authoritative DNS or local OpenWrt DNS; DNS success is not client handshake success."
        + " Cross-entry failover and multiline aggregation own their backing forwards: never edit those independently."
        + " Other modules include nftables forwards, Docker apps, published services, private networks, DDNS,"
        + " user quotas/grants, monitoring/history/alerts and Agent upgrades. Use inspect_panel for actual state"
        + " and API catalog; catalog routes document capabilities, they are not callable tools or permission grants."
        + " Never edit the database directly to repair panel-managed configuration. Inspect topology and affected"
        + " nodes before changes; preserve ports, credentials, user allocations and existing policies."
        + " For low disk space measure filesystem/inodes and largest directories first; reclaim only known caches"
        + " and expired rotated logs according to retention. Do not delete databases, current logs, backups, user"
        + " uploads, proxy credentials or unknown files as junk. Recheck free space and services after cleanup."
        + " For line failures distinguish offline Agent, process/listener, DNS, transport, target and real proxy"
        + " handshake; a successful TCP connection or restart alone does not prove recovery."
        + " Scheduled repair authorization permits action within selected nodes without repeated confirmation."
        + " Report unsupported control-plane repairs explicitly; do not fabricate complete panel control.";
  }

  public Object inspect(String section, Set<Long> nodes) throws Exception {
    if (section.equals("capabilities")) {
      try (var in = new ClassPathResource("ai/panel-api-catalog.json").getInputStream()) {
        return Map.of("guide", guidance(), "catalog", JSON.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      }
    }
    if (section.equals("alerts")) {
      // Global alert metadata is explicitly a panel-wide read permission; never include resource secrets.
      return db.queryForList("SELECT resource_type,resource_id,resource_name,status,detail,checked_at FROM monitoring_current ORDER BY checked_at DESC LIMIT 200");
    }
    if (section.equals("issues")) return issues();
    if (nodes.isEmpty()) throw new IllegalArgumentException("请先选择巡查节点");
    String marks = String.join(",", Collections.nCopies(nodes.size(), "?"));
    if (section.equals("nodes"))
      return db.queryForList("SELECT id,name,ip,version,status FROM node WHERE id IN (" + marks + ")", nodes.toArray());
    if (section.equals("routes")) {
      var tunnels = db.queryForList("SELECT id,name,in_node_id,out_node_id,node_path,status,protocol FROM tunnel WHERE in_node_id IN (" + marks + ") OR out_node_id IN (" + marks + ")", java.util.stream.Stream.concat(nodes.stream(),nodes.stream()).toArray());
      Set<Object> ids = new LinkedHashSet<>();
      for (var row : tunnels) ids.add(row.get("id"));
      if (ids.isEmpty()) return Map.of("tunnels", tunnels, "forwards", List.of());
      String tmarks = String.join(",", Collections.nCopies(ids.size(), "?"));
      return Map.of("tunnels", tunnels, "forwards", db.queryForList("SELECT id,name,tunnel_id,active_tunnel_id,in_port,remote_addr,status,route_mode,target_health,last_health_check,route_switch_reason FROM forward WHERE tunnel_id IN (" + tmarks + ") OR active_tunnel_id IN (" + tmarks + ") LIMIT 200", java.util.stream.Stream.concat(ids.stream(),ids.stream()).toArray()));
    }
    if (section.equals("health")) return health(nodes, marks);
    if (section.equals("traffic")) return traffic(nodes, marks);
    if (section.equals("exits")) return exits(nodes, marks);
    if (section.equals("metrics")) {
      if (metrics == null) throw new IllegalStateException("资源历史不可用");
      return metrics.summary(nodes);
    }
    if (section.equals("probes")) return probes(nodes, marks);
    throw new IllegalArgumentException("未知面板巡查类别");
  }

  @javax.annotation.Resource private NodeMetricHistory metrics;

  static final List<String> SECTIONS =
      List.of("capabilities", "nodes", "alerts", "issues", "routes", "health", "traffic", "exits", "metrics", "probes");

  /** Known current problems: open alerts and the non-healthy findings of the latest self-check. */
  private Object issues() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("openAlerts", safe(() -> db.queryForList(
        "SELECT resource_type,resource_id,resource_name,severity,status,title,detail,started_at,updated_at FROM monitoring_alert WHERE status='open'"
            + " ORDER BY started_at DESC LIMIT 100")));
    result.put("latestSelfCheck", safe(() -> {
      var runs = db.queryForList("SELECT id,status,total_checks,healthy_count,warning_count,failed_count,message,finished_at FROM"
          + " system_self_check_run WHERE finished_at IS NOT NULL ORDER BY id DESC LIMIT 1");
      if (runs.isEmpty()) return Map.of("note", "尚未运行全系统自检，可在“全系统自检”页面运行");
      Map<String, Object> run = new LinkedHashMap<>(runs.get(0));
      run.put("findings", db.queryForList("SELECT category,resource_type,resource_id,resource_name,status,fault_segment,summary,impact,remediation"
          + " FROM system_self_check_finding WHERE run_id=? AND status NOT IN ('healthy','ok','skipped') ORDER BY sort_order LIMIT 80", run.get("id")));
      return run;
    }));
    return result;
  }

  private Object safe(java.util.concurrent.Callable<Object> query) {
    try {
      return query.call();
    } catch (Exception e) {
      return Map.of("unavailable", Objects.toString(e.getMessage(), "query failed"));
    }
  }

  private Set<Object> tunnelIds(Set<Long> nodes, String marks) {
    Set<Object> ids = new LinkedHashSet<>();
    for (var row : db.queryForList("SELECT id FROM tunnel WHERE in_node_id IN (" + marks + ") OR out_node_id IN (" + marks + ")",
        java.util.stream.Stream.concat(nodes.stream(), nodes.stream()).toArray())) ids.add(row.get("id"));
    return ids;
  }

  private List<Long> forwardIds(Set<Long> nodes, String marks) {
    Set<Object> tunnels = tunnelIds(nodes, marks);
    if (tunnels.isEmpty()) return List.of();
    String t = String.join(",", Collections.nCopies(tunnels.size(), "?"));
    return db.queryForList("SELECT id FROM forward WHERE tunnel_id IN (" + t + ") OR active_tunnel_id IN (" + t + ") LIMIT 300",
            java.util.stream.Stream.concat(tunnels.stream(), tunnels.stream()).toArray())
        .stream().map(r -> ((Number) r.get("id")).longValue()).toList();
  }

  /** Current status and 24h/7d availability of the selected nodes and their tunnels and forwards. */
  private Object health(Set<Long> nodes, String marks) {
    long now = System.currentTimeMillis();
    Map<String, Object> result = new LinkedHashMap<>();
    List<Object[]> resources = new ArrayList<>();
    nodes.forEach(n -> resources.add(new Object[] {"node", n}));
    tunnelIds(nodes, marks).forEach(t -> resources.add(new Object[] {"tunnel", t}));
    forwardIds(nodes, marks).forEach(f -> resources.add(new Object[] {"forward", f}));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object[] r : resources) {
      var current = db.queryForList("SELECT resource_name,status,detail,changed_at,checked_at FROM monitoring_current WHERE resource_type=? AND resource_id=?", r[0], r[1]);
      var history = db.queryForList("SELECT status,started_at,ended_at FROM monitoring_history WHERE resource_type=? AND resource_id=? AND (ended_at IS NULL OR ended_at>=?) ORDER BY started_at",
          r[0], r[1], now - 7 * 86_400_000L);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", r[0]);
      row.put("id", r[1]);
      if (!current.isEmpty()) row.putAll(current.get(0));
      row.put("availability24h", availability(history, now - 86_400_000L, now));
      row.put("availability7d", availability(history, now - 7 * 86_400_000L, now));
      row.put("incidents7d", history.stream().filter(h -> List.of("offline", "degraded").contains(h.get("status"))).count());
      rows.add(row);
    }
    result.put("resources", rows);
    result.put("note", "availability = 非 offline/degraded 时间占比（%），基于面板监控记录；paused 计为可用");
    if (!forwardIds(nodes, marks).isEmpty()) {
      List<Long> ids = forwardIds(nodes, marks);
      String f = String.join(",", Collections.nCopies(ids.size(), "?"));
      result.put("recentRouteSwitches", safe(() -> db.queryForList(
          "SELECT forward_id,from_tunnel_name,to_tunnel_name,reason,trigger_type,status,created_at FROM forward_route_switch WHERE forward_id IN (" + f + ") ORDER BY id DESC LIMIT 30", ids.toArray())));
    }
    return result;
  }

  static Double availability(List<Map<String, Object>> history, long from, long to) {
    if (history.isEmpty()) return null;
    long bad = 0, covered = 0;
    for (var h : history) {
      long start = Math.max(from, ((Number) h.get("started_at")).longValue());
      long end = Math.min(to, h.get("ended_at") == null ? to : ((Number) h.get("ended_at")).longValue());
      if (end <= start) continue;
      covered += end - start;
      if (List.of("offline", "degraded").contains(h.get("status"))) bad += end - start;
    }
    if (covered == 0) return null;
    return Math.round((covered - bad) * 10000.0 / covered) / 100.0;
  }

  /** Panel-wide hourly traffic, busiest forwards on the selected nodes and private proxy traffic. */
  private Object traffic(Set<Long> nodes, String marks) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("panelHourlyLast48", safe(() -> db.queryForList(
        "SELECT time, SUM(in_flow) AS in_bytes, SUM(out_flow) AS out_bytes, MAX(created_time) AS at FROM statistics_flow"
            + " WHERE created_time>=? GROUP BY time ORDER BY at", System.currentTimeMillis() - 48 * 3_600_000L)));
    List<Long> ids = forwardIds(nodes, marks);
    if (!ids.isEmpty()) {
      String f = String.join(",", Collections.nCopies(ids.size(), "?"));
      result.put("forwardsByCumulativeBytes", safe(() -> db.queryForList(
          "SELECT id,name,user_name,in_port,status,in_flow,out_flow FROM forward WHERE id IN (" + f + ") ORDER BY in_flow+out_flow DESC LIMIT 20", ids.toArray())));
    }
    result.put("privateProxiesLast24h", safe(() -> db.queryForList(
        "SELECT p.id,p.name,p.node_id,SUM(t.in_flow) AS in_bytes,SUM(t.out_flow) AS out_bytes FROM private_proxy p JOIN private_proxy_traffic t ON t.proxy_id=p.id"
            + " WHERE p.node_id IN (" + marks + ") AND t.hour_at>=? GROUP BY p.id,p.name,p.node_id ORDER BY SUM(t.in_flow+t.out_flow) DESC LIMIT 20",
        java.util.stream.Stream.concat(nodes.stream().map(Object.class::cast), java.util.stream.Stream.of(System.currentTimeMillis() - 86_400_000L)).toArray())));
    result.put("note", "字节数为原始值；in/out 方向以面板统计口径为准");
    return result;
  }

  /** Private proxy exits on the selected nodes: VPNGate pool, Mysterium, WARP and custom exits. */
  private Object exits(Set<Long> nodes, String marks) {
    Map<String, Object> result = new LinkedHashMap<>();
    Object[] args = nodes.toArray();
    result.put("privateProxies", safe(() -> db.queryForList(
        "SELECT id,name,node_id,proxy_type,state,exit_type,exit_country,last_error FROM private_proxy WHERE node_id IN (" + marks + ")", args)));
    result.put("residentialPools", safe(() -> db.queryForList(
        "SELECT id,node_id,country,status,detail,active_exit_ip,active_owner,active_since,no_exit_since FROM exit_group WHERE node_id IN (" + marks + ")", args)));
    result.put("mysterium", safe(() -> db.queryForList(
        "SELECT node_id,country,state,exit_ip,isp,city,latency_ms,fail_count,last_ok_at,last_error FROM mysterium_connection WHERE node_id IN (" + marks + ")", args)));
    result.put("warp", safe(() -> db.queryForList(
        "SELECT node_id,last_test_at,last_test_ok,last_test_ip,last_test_country,last_test_latency,last_error FROM warp_account WHERE node_id IN (" + marks + ")", args)));
    result.put("customExits", safe(() -> db.queryForList(
        "SELECT id,name,protocol,last_test_at,last_test_ok,last_test_ip,last_test_country,last_test_latency,last_test_error FROM proxy_exit ORDER BY id LIMIT 50")));
    return result;
  }

  /** Latest scheduled line-quality probes and node-to-node bandwidth tests involving the nodes. */
  private Object probes(Set<Long> nodes, String marks) {
    Map<String, Object> result = new LinkedHashMap<>();
    Object[] twice = java.util.stream.Stream.concat(nodes.stream(), nodes.stream()).toArray();
    result.put("qualityProbes", safe(() -> db.queryForList(
        "SELECT t.id,t.name,t.source_node_id,t.target_host,t.port,t.protocol,r.status,r.dns_ms,r.tcp_avg_ms,r.tls_avg_ms,r.ttfb_avg_ms,r.jitter_ms,"
            + "r.failure_rate,r.http_status,r.error,r.finished_at FROM quality_probe_task t LEFT JOIN quality_probe_run r ON r.id=(SELECT MAX(id) FROM"
            + " quality_probe_run WHERE task_id=t.id) WHERE t.source_node_id IN (" + marks + ") OR t.target_node_id IN (" + marks + ") LIMIT 40", twice)));
    result.put("bandwidthTests", safe(() -> db.queryForList(
        "SELECT task_id,status,source_node_id,target_node_id,protocol,direction,upload_mbps,download_mbps,rtt_ms,retransmission_rate,packet_loss_percent,"
            + "jitter_ms,error,finished_at FROM bandwidth_test_run WHERE source_node_id IN (" + marks + ") OR target_node_id IN (" + marks + ")"
            + " ORDER BY id DESC LIMIT 15", twice)));
    return result;
  }
}
