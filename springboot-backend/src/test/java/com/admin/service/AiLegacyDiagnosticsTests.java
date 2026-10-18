package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiLegacyDiagnosticsTests {
  @Test
  void onlyFixedCommandsAndOwnSessionOutputAreAccepted() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:legacy;MODE=MySQL;DB_CLOSE_DELAY=-1");
    var db = new JdbcTemplate(source);
    db.execute("CREATE TABLE agent_upgrade_task(id int, node_id bigint, task_id varchar)");
    db.update("INSERT INTO agent_upgrade_task VALUES(1,73,'b2291fa7-6bd6-470e-82d3-45f2ebd8697a')");
    var opened = new CompletableFuture<String>();
    var input = new CompletableFuture<String>();
    var closed = new CompletableFuture<Boolean>();
    AiLegacyDiagnostics service =
        new AiLegacyDiagnostics(db) {
          @Override
          boolean send(long node, String type, Map<String, Object> data) {
            if (type.equals("TerminalOpen")) opened.complete(data.get("sessionId").toString());
            if (type.equals("TerminalInput"))
              input.complete(
                  new String(
                      Base64.getDecoder().decode(data.get("data").toString()),
                      StandardCharsets.UTF_8));
            if (type.equals("TerminalClose")) closed.complete(true);
            return true;
          }
        };
    assertThrows(IllegalArgumentException.class, () -> service.inspect(73, "restart_service", 1));
    var executor = Executors.newSingleThreadExecutor();
    try {
      var result = executor.submit(() -> service.inspect(73, "inspect_agent_upgrade", 3));
      String id = opened.get(1, TimeUnit.SECONDS);
      JSONObject event =
          new JSONObject(
              Map.of("type", "TerminalOpened", "data", new JSONObject(Map.of("sessionId", id))));
      assertFalse(service.handle(74, event));
      assertTrue(service.handle(73, event));
      String script = input.get(1, TimeUnit.SECONDS);
      assertTrue(script.contains("b2291fa7-6bd.log"));
      assertFalse(script.contains("curl"));
      assertFalse(script.contains("systemctl restart"));
      int pos = script.indexOf("AI_PROBE_");
      int end = script.indexOf("'", pos);
      int suffix = script.indexOf("'", end + 1) + 1;
      String marker =
          script.substring(pos, end) + script.substring(suffix, script.indexOf("'", suffix));
      byte[] bytes = ("磁盘正常\n" + marker).getBytes(StandardCharsets.UTF_8);
      for (byte b : bytes)
        service.handle(
            73,
            new JSONObject(
                Map.of(
                    "type",
                    "TerminalOutput",
                    "data",
                    new JSONObject(
                        Map.of(
                            "sessionId",
                            id,
                            "data",
                            Base64.getEncoder().encodeToString(new byte[] {b}))))));
      assertEquals("磁盘正常\n", result.get(1, TimeUnit.SECONDS).get("output"));
      assertTrue(closed.get(1, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }
}
