package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiOpsAutomationTests {

  @Test
  void parsesRealFilesystemsFromDiskProbe() {
    String output = "noise\n=== DISK ===\nFilesystem 1024-blocks Used Available Capacity Mounted on\n"
        + "/dev/vda1 41152736 36000000 3000000 93% /\n"
        + "tmpfs 1000 10 990 1% /run\n"
        + "/dev/loop3 63488 63488 0 100% /snap/core/1\n"
        + "/dev/vdb 100000 20000 80000 20% /data\n"
        + "=== INODES ===\n/dev/vda1 100 99 1 99% /\n";
    var mounts = AiBatchChecks.parseDf(output);
    assertEquals(2, mounts.size(), mounts.toString());
    assertEquals("/", mounts.get(0).get("mount"));
    assertEquals(93, mounts.get(0).get("usedPercent"));
    assertTrue(AiBatchChecks.parseDf("no disk section").isEmpty());
  }

  @Test
  void weeklyReportHighlightsProblems() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("disk", List.of(
        new LinkedHashMap<>(Map.of("node", "懒猫云美国vimiss", "status", "fail", "value", "/ 93%", "detail", "可用 2.6 GB", "weekGrowthPoints", 6)),
        new LinkedHashMap<>(Map.of("node", "NNC日本", "status", "ok", "value", "/ 40%", "detail", "可用 30 GB"))));
    data.put("availability", List.of(Map.of("node", "阿里云CB", "status", "warn", "value", "99.2%", "detail", "24 小时异常 2 次")));
    data.put("resources", List.of(Map.of("node", "NNC日本", "status", "ok", "value", "CPU 3%", "detail", "")));
    data.put("agents", List.of(Map.of("node", "懒猫云家宽", "status", "warn", "value", "2.51.37", "detail", "可升级")));
    data.put("traffic", Map.of("thisWeekBytes", 46_000_000_000L, "lastWeekBytes", 40_000_000_000L));
    data.put("openAlerts", List.of(Map.of("title", "转发“rola”异常", "started_at", 1792000000000L)));
    String report = AiOpsAutomation.render(data);
    assertTrue(report.contains("| 懒猫云美国vimiss | 🔴 异常 | / 93% | +6 个百分点 |"), report);
    assertTrue(report.contains("🟡 留意 阿里云CB"), report);
    assertFalse(report.contains("CPU 3%"), "healthy resources are not listed");
    assertTrue(report.contains("懒猫云家宽：2.51.37"));
    assertTrue(report.contains("本周 46.0 GB，上周 40.0 GB，变化 15.0%"), report);
    assertTrue(report.contains("转发“rola”异常"));
  }

  @Test
  void findsTheNodesBehindAnAlert() {
    JdbcTemplate db = mock(JdbcTemplate.class);
    when(db.queryForList(startsWith("SELECT tunnel_id,active_tunnel_id FROM forward"), eq(5L)))
        .thenReturn(List.of(new HashMap<>(Map.of("tunnel_id", 2, "active_tunnel_id", 3))));
    when(db.queryForList(startsWith("SELECT in_node_id,out_node_id,node_path FROM tunnel"), eq(3L)))
        .thenReturn(List.of(Map.of("in_node_id", 8, "out_node_id", 75, "node_path", "[8,14,75]")));
    when(db.queryForList(startsWith("SELECT in_node_id,out_node_id,node_path FROM tunnel"), eq(2L)))
        .thenReturn(List.of(Map.of("in_node_id", 8, "out_node_id", 13, "node_path", "")));
    AiOpsAutomation automation = new AiOpsAutomation(db, null, null, null);
    assertEquals(List.of(8L, 75L, 14L, 13L), new ArrayList<>(automation.relatedNodes("forward", 5)));
    assertEquals(List.of(75L), new ArrayList<>(automation.relatedNodes("node", 75)));
    assertTrue(automation.relatedNodes("certificate", 1).isEmpty());
  }

  @Test
  void alertDiagnosisRespectsSwitchAndRateLimits() throws Exception {
    JdbcTemplate db = mock(JdbcTemplate.class);
    when(db.queryForList("SELECT name,value FROM ai_ops_preference")).thenReturn(List.of(Map.of("name", "alert_diagnosis", "value", "0")));
    AiOpsService ops = mock(AiOpsService.class);
    when(ops.storedSettings()).thenReturn(new JSONObject());
    when(ops.configured(any())).thenReturn(true);
    AiOpsAutomation automation = spy(new AiOpsAutomation(db, ops, null, null));
    automation.onAlertOpened("node", 1, "A", "offline", "x", 1);
    verify(ops, never()).configured(any());

    when(db.queryForList("SELECT name,value FROM ai_ops_preference")).thenReturn(List.of());
    for (int i = 0; i < 10; i++) automation.onAlertOpened("node", i, "A", "offline", "x", 1);
    automation.onAlertOpened("node", 0, "A", "offline", "x", 2);
    // Six per hour; the repeated resource inside 30 minutes is not queued again.
    assertEquals(6, countQueued(automation));
  }

  @SuppressWarnings("unchecked")
  private static int countQueued(AiOpsAutomation automation) throws Exception {
    var field = AiOpsAutomation.class.getDeclaredField("recentDiagnoses");
    field.setAccessible(true);
    return ((Deque<Long>) field.get(automation)).size();
  }

  @Test
  void toolsOfferBatchChecks() {
    JSONObject config = JSONObject.parseObject("{\"mode\":\"inspect\",\"allowPanelRead\":true,\"serviceAllowlist\":[]}");
    assertTrue(AiOpsService.tools(config).toString().contains("\"check_nodes\""));
  }
}
