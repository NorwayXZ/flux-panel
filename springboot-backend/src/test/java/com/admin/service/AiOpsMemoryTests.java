package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.fastjson2.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiOpsMemoryTests {

  @Test
  void filePathsMustBePlainAbsolutePaths() {
    AiNodeMemory.validatePath("/etc/nginx/conf.d/site.conf");
    AiNodeMemory.validatePath("/opt/app/.env");
    for (String bad : List.of("etc/passwd", "/etc/../shadow", "/proc/1/environ", "/sys/kernel/x", "/tmp/a b", "/tmp/a'b",
        "/tmp/$(id)", "/tmp/a;rm", "/", ""))
      assertThrows(IllegalArgumentException.class, () -> AiNodeMemory.validatePath(bad), bad);
  }

  @Test
  void notesAreLimitedAndSaved() {
    JdbcTemplate db = mock(JdbcTemplate.class);
    AiNodeMemory memory = new AiNodeMemory(db, null);
    assertThrows(IllegalArgumentException.class, () -> memory.remember(8, "x".repeat(2001), "assistant"));
    assertThrows(IllegalArgumentException.class, () -> memory.remember(8, "  ", "assistant"));
    memory.remember(8, "Debian 12; nginx on 443", "assistant");
    verify(db).update(startsWith("INSERT INTO ai_node_note"), eq(8L), eq("Debian 12; nginx on 443"), anyLong(), eq("assistant"));
    when(db.queryForList(startsWith("SELECT node_id,content FROM ai_node_note"), eq(8L)))
        .thenReturn(List.of(Map.of("node_id", 8L, "content", "Debian 12")));
    assertTrue(memory.promptSection(List.of(8L)).contains("[node 8] Debian 12"));
    assertEquals("", new AiNodeMemory(mock(JdbcTemplate.class), null).promptSection(List.of()));
  }

  @Test
  void writeFileSnapshotsTheOldContentAndStagesNewContent() throws Exception {
    JdbcTemplate db = mock(JdbcTemplate.class);
    AiLegacyDiagnostics terminal = mock(AiLegacyDiagnostics.class);
    String old = "listen 80;\n";
    List<String> scripts = new ArrayList<>();
    when(terminal.execute(anyLong(), anyString(), anyInt())).thenAnswer(call -> {
      String script = call.getArgument(1);
      scripts.add(script);
      if (script.startsWith("if [ -f "))
        return Map.of("output", "noise\nEXISTS\n" + Base64.getEncoder().encodeToString(old.getBytes(StandardCharsets.UTF_8)) + "\n", "exitCode", 0);
      if (script.startsWith("umask 077")) return Map.of("output", "STAGED", "exitCode", 0);
      return Map.of("output", "WRITTEN", "exitCode", 0);
    });
    when(db.queryForObject(startsWith("SELECT MAX(id) FROM ai_ops_change"), eq(Long.class), any(), any())).thenReturn(5L);
    AiNodeMemory memory = new AiNodeMemory(db, terminal);
    String content = "listen 443 ssl;\n" + "#".repeat(20000);
    Map<String, Object> result = memory.writeFile(8, "/etc/nginx/conf.d/a.conf", content, "启用 HTTPS", "chat", 30);
    assertEquals(5L, result.get("changeId"));
    // 20 KB becomes about 27 KB of base64: staged in three chunks within the terminal script limit.
    assertEquals(3, scripts.stream().filter(s -> s.startsWith("umask 077")).count());
    assertTrue(scripts.stream().allMatch(s -> s.length() < 16000));
    verify(db).update(startsWith("INSERT INTO ai_ops_change"), eq(8L), eq("file"), eq("chat"), eq("启用 HTTPS"),
        eq("/etc/nginx/conf.d/a.conf"), eq(old), eq(content), eq(1), anyLong());
    assertThrows(IllegalArgumentException.class, () -> memory.writeFile(8, "/etc/a", "x".repeat(40000), "r", "chat", 30));
  }

  @Test
  void streamUsageIsCapturedOutsideTheMessage() {
    String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":8}}\n\ndata: [DONE]\n\n";
    JSONObject message = AiChatStreamSubscriber.assemble(stream.getBytes(StandardCharsets.UTF_8));
    assertEquals("hi", message.getString("content"));
    assertEquals(120, message.getJSONObject("_usage").getIntValue("prompt_tokens"));
  }

  @Test
  void writeAccessFollowsTerminalPermission() {
    JSONObject inspect = JSONObject.parseObject("{\"mode\":\"inspect\",\"allowPanelRead\":true,\"allowTerminal\":true,\"serviceAllowlist\":[]}");
    String names = AiOpsService.tools(inspect).toString();
    assertTrue(names.contains("\"remember_node_note\"") && names.contains("\"security_audit\""));
    assertFalse(names.contains("\"write_file\""), "read-only mode cannot write files");
    JSONObject repair = JSONObject.parseObject("{\"mode\":\"repair\",\"allowPanelRead\":true,\"allowTerminal\":true,\"serviceAllowlist\":[]}");
    assertTrue(AiOpsService.tools(repair).toString().contains("\"write_file\""));
    repair.put("allowTerminal", false);
    assertFalse(AiOpsService.tools(repair).toString().contains("\"write_file\""));
  }
}
