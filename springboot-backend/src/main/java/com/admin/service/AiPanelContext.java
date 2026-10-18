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
    if (nodes.isEmpty()) throw new IllegalArgumentException("请先选择巡查节点");
    String marks = String.join(",", Collections.nCopies(nodes.size(), "?"));
    if (section.equals("nodes"))
      return db.queryForList("SELECT id,name,ip,version,status FROM node WHERE id IN (" + marks + ")", nodes.toArray());
    if (section.equals("alerts")) {
      // Global alert metadata is explicitly a panel-wide read permission; never include resource secrets.
      return db.queryForList("SELECT resource_type,resource_id,resource_name,status,detail,checked_at FROM monitoring_current ORDER BY checked_at DESC LIMIT 200");
    }
    if (section.equals("routes")) {
      var tunnels = db.queryForList("SELECT id,name,in_node_id,out_node_id,node_path,status,protocol FROM tunnel WHERE in_node_id IN (" + marks + ") OR out_node_id IN (" + marks + ")", java.util.stream.Stream.concat(nodes.stream(),nodes.stream()).toArray());
      Set<Object> ids = new LinkedHashSet<>();
      for (var row : tunnels) ids.add(row.get("id"));
      if (ids.isEmpty()) return Map.of("tunnels", tunnels, "forwards", List.of());
      String tmarks = String.join(",", Collections.nCopies(ids.size(), "?"));
      return Map.of("tunnels", tunnels, "forwards", db.queryForList("SELECT id,name,tunnel_id,active_tunnel_id,in_port,remote_addr,status,route_mode,target_health,last_health_check,route_switch_reason FROM forward WHERE tunnel_id IN (" + tmarks + ") OR active_tunnel_id IN (" + tmarks + ") LIMIT 200", java.util.stream.Stream.concat(ids.stream(),ids.stream()).toArray()));
    }
    throw new IllegalArgumentException("未知面板巡查类别");
  }
}
