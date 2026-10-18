package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import org.junit.jupiter.api.Test;

class AiOpsInsightToolsTests {

  @Test
  void networkProbeAcceptsHostsAndRejectsShell() {
    assertTrue(AiNetworkTools.validHost("1.1.1.1"));
    assertTrue(AiNetworkTools.validHost("2606:4700:4700::1111"));
    assertTrue(AiNetworkTools.validHost("www.example.com"));
    for (String bad : List.of("1.1.1.1;reboot", "$(id)", "a b", "999.1.1.1", "`id`", "x'y", "", "-oProxyCommand=x"))
      assertThrows(IllegalArgumentException.class, () -> AiNetworkTools.script("ping", bad, null), bad);
    assertThrows(IllegalArgumentException.class, () -> AiNetworkTools.script("http", "https://a.com/'$(id)'", null));
    assertThrows(IllegalArgumentException.class, () -> AiNetworkTools.script("tcp", "a.com", null));
    assertThrows(IllegalArgumentException.class, () -> AiNetworkTools.script("tcp", "a.com", 70000));
    assertThrows(IllegalArgumentException.class, () -> AiNetworkTools.script("rm", "a.com", null));
    assertTrue(AiNetworkTools.script("tcp", "2001:db8::1", 443).contains("telnet://[2001:db8::1]:443"));
    assertTrue(AiNetworkTools.script("trace", "example.com", null).contains("mtr -rwbz"));
    assertTrue(AiNetworkTools.script("trace", "example.com", null).contains("ping -n -c 1 -W 1 -t $ttl"), "falls back to ping TTL");
    assertEquals("from host at now\n64 bytes", AiNetworkTools.clean("root@x:/# stty -echo; printf 'abc' | base64 -d\r\n\rfrom host at now\r\n64 bytes\r\n"));
    assertTrue(AiNetworkTools.script("http", "https://example.com/path?a=1", null).contains("'https://example.com/path?a=1'"));
  }

  @Test
  void metricHistorySummarizesWindowsAndThroughput() {
    long now = 10_000_000_000L;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      rows.add(Map.of("sampled_at", now - (11 - i) * 300_000L, "cpu_percent", 10.0 + i, "memory_percent", 50.0,
          "rx_bytes", i * 37_500_000L, "tx_bytes", i * 75_000_000L, "uptime_seconds", 7200L));
    }
    Map<String, Object> view = NodeMetricHistory.summarize(8, rows, now);
    assertEquals(12, view.get("samples"));
    @SuppressWarnings("unchecked") Map<String, Object> hour = (Map<String, Object>) view.get("1h");
    assertEquals(21.0, hour.get("cpuMax"));
    // 37.5 MB per 300 s = 1 Mbps receive, 2 Mbps transmit.
    assertEquals(Map.of("rx", 1.0, "tx", 2.0), hour.get("throughputMbps"));
    List<Map<String, Object>> reset = new ArrayList<>(rows);
    reset.add(Map.of("sampled_at", now + 300_000L, "cpu_percent", 1.0, "memory_percent", 1.0, "rx_bytes", 0L, "tx_bytes", 0L, "uptime_seconds", 1L));
    assertEquals(Map.of("rx", 1.0, "tx", 2.0), NodeMetricHistory.throughput(reset), "a counter reset is skipped");
    assertEquals(0.0, NodeMetricHistory.clampPercent(Double.NaN));
    assertTrue(NodeMetricHistory.summarize(1, List.of(), now).containsKey("note"));
  }

  @Test
  void availabilityCountsOfflineAndDegradedTime() {
    long day = 86_400_000L;
    List<Map<String, Object>> history = new ArrayList<>();
    history.add(new HashMap<>(Map.of("status", "healthy", "started_at", 0L, "ended_at", day / 2)));
    history.add(new HashMap<>(Map.of("status", "offline", "started_at", day / 2, "ended_at", day / 2 + day / 4)));
    Map<String, Object> open = new HashMap<>(Map.of("status", "healthy", "started_at", day / 2 + day / 4));
    open.put("ended_at", null);
    history.add(open);
    assertEquals(75.0, AiPanelContext.availability(history, 0, day));
    assertNull(AiPanelContext.availability(List.of(), 0, day));
  }

  @Test
  void knowledgeCoversThePanelAndFindsFeatures() {
    AiPanelKnowledge knowledge = new AiPanelKnowledge();
    assertTrue(knowledge.size() > 50, "generated knowledge is packaged");
    String index = knowledge.index();
    for (String feature : List.of("私人代理", "转发管理", "家宽出口", "告警中心"))
      assertTrue(index.contains(feature), feature);
    var hits = knowledge.search("Mysterium 怎么充值续费", 4);
    assertFalse(hits.isEmpty());
    assertTrue(hits.stream().anyMatch(h -> h.get("title").toString().contains("Mysterium")), hits.toString());
    var forward = knowledge.search("nftables 端口转发怎么用", 2);
    assertEquals("/nft-forward", forward.get(0).get("path"));
    assertThrows(IllegalArgumentException.class, () -> knowledge.search("   ", 3));
    assertTrue(AiPanelKnowledge.terms("转发很慢").containsAll(List.of("转发", "发很", "很慢")));
  }

  @Test
  void toolsExposeKnowledgeWithoutNodesAndProbesOnlyForSelectedNodes() {
    JSONObject config = JSONObject.parseObject("{\"mode\":\"inspect\",\"allowPanelRead\":true,\"serviceAllowlist\":[]}");
    List<String> names = names(AiOpsService.tools(config));
    assertTrue(names.containsAll(List.of("panel_knowledge", "inspect_panel", "network_probe", "node_speed_test")), names.toString());
    assertFalse(names.contains("execute_command"), "read-only mode never gains shell access");
    assertEquals(List.of("panel_knowledge", "inspect_panel"), names(AiOpsService.generalTools(config)));
    config.put("upgradeOnly", true);
    assertFalse(names(AiOpsService.tools(config)).contains("network_probe"));

    config.put("upgradeOnly", false);
    AiOpsToolSession session = new AiOpsToolSession(null, config, Set.of(8L), System.currentTimeMillis());
    JSONObject call = JSONObject.parseObject("{\"id\":\"c1\",\"function\":{\"name\":\"network_probe\",\"arguments\":\"{\\\"nodeId\\\":9,\\\"kind\\\":\\\"ping\\\",\\\"target\\\":\\\"1.1.1.1\\\"}\"}}");
    JSONObject result = session.invoke(call);
    assertFalse(result.getBooleanValue("success"));
    assertTrue(result.getString("error").contains("超出"), result.toString());
    JSONObject speed = JSONObject.parseObject("{\"id\":\"c2\",\"function\":{\"name\":\"node_speed_test\",\"arguments\":\"{\\\"sourceNodeId\\\":8,\\\"targetNodeId\\\":9}\"}}");
    assertTrue(session.invoke(speed).getString("error").contains("所选节点"));
  }

  private static List<String> names(Object tools) {
    List<String> names = new ArrayList<>();
    for (Object tool : (Iterable<?>) tools)
      names.add(((JSONObject) JSON.toJSON(tool)).getJSONObject("function").getString("name"));
    return names;
  }
}
