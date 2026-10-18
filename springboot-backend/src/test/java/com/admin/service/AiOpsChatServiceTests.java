package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.admin.common.dto.GostDto;
import com.admin.config.*;
import com.alibaba.fastjson2.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class AiOpsChatServiceTests {
  JdbcTemplate db;
  TestOps ops;
  AiOpsChatService chat;
  HttpServer server;
  List<JSONObject> toolCalls = new ArrayList<>();

  class TestOps extends AiOpsService {
    TestOps() {
      super(db, "chat-test-secret", mock(TelegramNotificationService.class));
    }

    @Override
    boolean nodeOnline(long id) {
      return true;
    }

    @Override
    GostDto callAgent(long node, Object body, String type, long timeout) {
      GostDto result = new GostDto();
      result.setMsg("OK");
      result.setData(Map.of("disk", "20%"));
      if (type.equals("OpsToolCall")) toolCalls.add((JSONObject) body);
      return result;
    }
  }

  @BeforeEach
  void setup() {
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:mem:chat"
            + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    db = new JdbcTemplate(source);
    new AiOpsSchemaInitializer(db).init();
    new AiChatSchemaInitializer(db).initialize();
    db.execute("CREATE TABLE node(id bigint,name varchar)");
    db.update("INSERT INTO node VALUES(1,'test')");
    ops = new TestOps();
    chat = new AiOpsChatService(db, ops);
  }

  @AfterEach
  void close() {
    chat.close();
    ops.close();
    if (server != null) server.stop(0);
  }

  JSONObject config() {
    return JSON.parseObject(
        "{\"name\":\"chat\",\"prompt\":\"hello\",\"nodeIds\":[],\"mode\":\"inspect\",\"retentionDays\":7,\"maxJournalMb\":500,\"serviceAllowlist\":[],\"cron\":\"0"
            + " 0 3 * * *\",\"timezone\":\"Asia/Shanghai\"}");
  }

  JSONObject settings() {
    return ops.storedSettings();
  }

  JSONObject tool(String name, String args) {
    return JSON.parseObject(
        "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":"
            + JSON.toJSONString(name)
            + ",\"arguments\":"
            + JSON.toJSONString(args)
            + "}}");
  }

  static String chunk(JSONObject delta, String reason) {
    JSONObject choice = new JSONObject(Map.of("delta", delta));
    choice.put("finish_reason", reason);
    return "data: " + JSON.toJSONString(Map.of("choices", List.of(choice))) + "\n\n";
  }

  void provider(String body, CountDownLatch gate) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            if (gate != null) gate.await(3, TimeUnit.SECONDS);
            for (byte b : body.getBytes(StandardCharsets.UTF_8)) {
              exchange.getResponseBody().write(b);
              exchange.getResponseBody().flush();
            }
          } catch (Exception ignored) {
          } finally {
            exchange.close();
          }
        });
    server.start();
    assertEquals(
        0,
        ops.settings(
                new JSONObject(
                    Map.of(
                        "base_url",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "model",
                        "test",
                        "api_key",
                        "secret-key")))
            .getCode());
  }

  @Test
  void decodesSplitUtf8AndToolArguments() {
    String wire =
        chunk(new JSONObject(Map.of("reasoning_content", "检查上下文")), null)
            + chunk(new JSONObject(Map.of("content", "你好")), null)
            + chunk(
                new JSONObject(
                    Map.of(
                        "tool_calls",
                        List.of(
                            Map.of(
                                "index",
                                0,
                                "id",
                                "x",
                                "function",
                                Map.of("name", "inspect_disk", "arguments", "{\"node"))))),
                null)
            + chunk(
                new JSONObject(
                    Map.of(
                        "tool_calls",
                        List.of(Map.of("index", 0, "function", Map.of("arguments", "Id\":1}"))))),
                "tool_calls")
            + "data: [DONE]\n\n";
    StringBuilder observed = new StringBuilder();
    AiChatStreamSubscriber subscriber = new AiChatStreamSubscriber(observed::append);
    subscriber.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {}
        });
    for (byte b : wire.getBytes(StandardCharsets.UTF_8))
      subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {b})));
    subscriber.onComplete();
    assertEquals("你好", observed.toString());
    JSONObject message =
        AiChatStreamSubscriber.assemble(subscriber.getBody().toCompletableFuture().join());
    assertEquals("你好", message.getString("content"));
    assertEquals("检查上下文", message.getString("reasoning_content"));
    assertEquals(
        "{\"nodeId\":1}",
        message
            .getJSONArray("tool_calls")
            .getJSONObject(0)
            .getJSONObject("function")
            .getString("arguments"));
    assertThrows(
        IllegalStateException.class,
        () ->
            AiChatStreamSubscriber.assemble(
                chunk(new JSONObject(Map.of("content", "partial")), null)
                    .getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void sseFramingOverheadDoesNotConsumeTheReplyBodyBudget() {
    StringBuilder wire = new StringBuilder();
    for (int i = 0; i < 1400; i++) {
      JSONObject packet =
          new JSONObject(
              Map.of(
                  "id",
                  "metadata-".repeat(30),
                  "model",
                  "thinking-model",
                  "choices",
                  List.of(Map.of("delta", Map.of("reasoning_content", "r")))));
      wire.append("data: ").append(packet.toJSONString()).append("\n\n");
    }
    wire.append(chunk(new JSONObject(Map.of("content", "检查完成")), "stop"));
    byte[] raw = wire.toString().getBytes(StandardCharsets.UTF_8);
    assertTrue(raw.length > 262144);
    var subscriber = new AiChatStreamSubscriber(s -> {});
    subscriber.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {}
        });
    subscriber.onNext(List.of(ByteBuffer.wrap(raw)));
    subscriber.onComplete();
    JSONObject assembled =
        AiChatStreamSubscriber.assemble(subscriber.getBody().toCompletableFuture().join());
    assertEquals("检查完成", assembled.getString("content"));
    assertEquals(1400, assembled.getString("reasoning_content").length());
    assertEquals(
        "模型响应超过容量限制",
        AiOpsService.safeError(new ExecutionException(new IllegalStateException("模型响应超过容量限制"))));
  }

  @Test
  void ordinaryChatWithoutNodesStreamsAndKeepsProviderKeyPrivate() throws Exception {
    provider(
        chunk(new JSONObject(Map.of("content", "secret-")), null)
            + chunk(new JSONObject(Map.of("content", "key 你好")), "stop")
            + "data: [DONE]\n\n",
        null);
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO ai_ops_chat(id,owner_id,title,created_at,updated_at) VALUES(1,7,'chat',?,?)",
        now,
        now);
    db.update(
        "INSERT INTO ai_ops_chat_turn(id,chat_id,user_content,policy_json,status,created_at)"
            + " VALUES(1,1,'hello','{}','running',?)",
        now);
    JSONArray audit = new JSONArray();
    List<JSONObject> events = new ArrayList<>();
    chat.execute(1, 1, config(), settings(), audit, events::add, now);
    String output =
        events.stream()
            .filter(e -> e.getString("type").equals("delta"))
            .map(e -> e.getString("text"))
            .reduce("", String::concat);
    assertEquals("[REDACTED] 你好", output);
    assertTrue(toolCalls.isEmpty());
    assertTrue(events.stream().anyMatch(e -> e.getString("type").equals("delta")));
  }

  @Test
  void ownerIsolationAndMissingModelDoNotCreateTurns() {
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO ai_ops_chat(id,owner_id,title,created_at,updated_at)"
            + " VALUES(1,7,'private',?,?)",
        now,
        now);
    assertTrue(chat.list(8).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> chat.detail(8, 1));
    assertThrows(IllegalArgumentException.class, () -> chat.delete(8, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> chat.send(7, new JSONObject(Map.of("message", "hello", "config", config()))));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ai_ops_chat_turn", Integer.class));
  }

  @Test
  void toolPolicyCannotBeExpandedAndPriorTurnDoesNotAuthorizeRepair() {
    JSONObject c = config();
    c.put("nodeIds", List.of(1));
    AiOpsToolSession session = new AiOpsToolSession(ops, c, Set.of(1L), System.currentTimeMillis());
    assertFalse(session.invoke(tool("inspect_disk", "{\"nodeId\":2}")).getBooleanValue("success"));
    assertFalse(
        session.invoke(tool("vacuum_journal", "{\"nodeId\":1}")).getBooleanValue("success"));
    assertTrue(toolCalls.isEmpty());
    c.put("mode", "repair");
    AiOpsToolSession first = new AiOpsToolSession(ops, c, Set.of(1L), System.currentTimeMillis());
    assertTrue(first.invoke(tool("inspect_disk", "{\"nodeId\":1}")).getBooleanValue("success"));
    AiOpsToolSession next = new AiOpsToolSession(ops, c, Set.of(1L), System.currentTimeMillis());
    assertFalse(next.invoke(tool("vacuum_journal", "{\"nodeId\":1}")).getBooleanValue("success"));
    assertFalse(
        next.invoke(
                new JSONObject(
                    Map.of(
                        "function", Map.of("name", "inspect_disk", "arguments", "{\"nodeId\":1}"))))
            .getBooleanValue("success"));
    assertEquals(1, toolCalls.size());
  }

  @Test
  void stopReleasesOwnerAndPersistsInterruptedTurn() throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    provider(chunk(new JSONObject(Map.of("content", "hello")), "stop"), gate);
    JSONObject body = new JSONObject(Map.of("message", "hello", "config", config()));
    chat.send(7, body);
    assertThrows(IllegalArgumentException.class, () -> chat.send(7, body));
    for (int i = 0; i < 100 && chat.list(7).isEmpty(); i++) Thread.sleep(5);
    chat.stop(7);
    gate.countDown();
    String state = "running";
    for (int i = 0; i < 200; i++) {
      state = db.queryForObject("SELECT status FROM ai_ops_chat_turn", String.class);
      if (!state.equals("running")) break;
      Thread.sleep(10);
    }
    assertEquals("stopped", state);
  }

  @Test
  void mvcReturnsActualEventStreamAndConfigurationErrorsAreJson() throws Exception {
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.admin.controller.AiOpsChatController(chat))
            .setMessageConverters(
                new org.springframework.http.converter.ByteArrayHttpMessageConverter(),
                new org.springframework.http.converter.StringHttpMessageConverter(
                    StandardCharsets.ISO_8859_1),
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
            .build();
    String token =
        "x."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"7\",\"role_id\":0}".getBytes(StandardCharsets.UTF_8))
            + ".x";
    String body = JSON.toJSONString(Map.of("message", "hello", "config", config()));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/ai-ops/chat/send")
                .header("Authorization", token)
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                .asyncNotStarted());
    provider(chunk(new JSONObject(Map.of("content", "你好")), "stop") + "data: [DONE]\n\n", null);
    var request =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/ai-ops/chat/send")
                    .header("Authorization", token)
                    .contentType("application/json")
                    .content(body))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                    .asyncStarted())
            .andReturn();
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(
                    request))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse();
    assertTrue(response.getContentType().contains("text/event-stream"));
    assertEquals("no", response.getHeader("X-Accel-Buffering"));
    assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("你好"));
    assertTrue(response.getContentAsString().contains("done"));
  }

  @Test
  void legacyFallbackAndUpgradeRequireSelectedNodesAndExplicitPermission() {
    AtomicInteger probes = new AtomicInteger(), upgrades = new AtomicInteger();
    TestOps compatible =
        new TestOps() {
          @Override
          GostDto callAgent(long node, Object body, String type, long timeout) {
            var response = new GostDto();
            response.setMsg("未知命令类型: OpsCapabilities");
            return response;
          }

          @Override
          Object legacyProbe(long node, String kind, int timeout) {
            probes.incrementAndGet();
            return Map.of("disk", "44%");
          }

          @Override
          JSONObject inspectUpgrade(long node) {
            return new JSONObject(Map.of("version", "old"));
          }

          @Override
          Object requestUpgrade(long node, int owner) {
            assertEquals(7, owner);
            upgrades.incrementAndGet();
            return Map.of("submitted", true);
          }
        };
    try {
      JSONObject c = config();
      c.put("nodeIds", List.of(1));
      c.put("_chatOwner", 7);
      var readonly = new AiOpsToolSession(compatible, c, Set.of(1L), System.currentTimeMillis());
      assertTrue(
          readonly.invoke(tool("inspect_disk", "{\"nodeId\":1}")).getBooleanValue("success"));
      assertEquals(1, probes.get());
      assertFalse(
          readonly.invoke(tool("inspect_disk", "{\"nodeId\":2}")).getBooleanValue("success"));
      assertFalse(
          readonly.invoke(tool("upgrade_agent", "{\"nodeId\":1}")).getBooleanValue("success"));
      c.put("mode", "repair");
      c.put("allowAgentUpgrade", true);
      var repair = new AiOpsToolSession(compatible, c, Set.of(1L), System.currentTimeMillis());
      assertFalse(
          repair.invoke(tool("upgrade_agent", "{\"nodeId\":1}")).getBooleanValue("success"));
      assertTrue(
          repair
              .invoke(tool("inspect_agent_upgrade", "{\"nodeId\":1}"))
              .getBooleanValue("success"));
      assertTrue(repair.invoke(tool("upgrade_agent", "{\"nodeId\":1}")).getBooleanValue("success"));
      assertEquals(1, upgrades.get());
      assertFalse(
          repair.invoke(tool("vacuum_journal", "{\"nodeId\":1}")).getBooleanValue("success"));
      AiOpsService.validate(c, true);
      assertFalse(c.containsKey("_chatOwner"));
    } finally {
      compatible.close();
    }
  }

  @Test
  void thinkingToolRoundTripsPreserveProviderContext() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<JSONObject> followup = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          JSONObject request =
              JSON.parseObject(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          int step = requests.incrementAndGet();
          String response;
          if (step == 1) {
            JSONObject call = tool("inspect_disk", "{\"nodeId\":1}");
            call.put("index", 0);
            response =
                chunk(new JSONObject(Map.of("reasoning_content", "check disk first")), null)
                    + chunk(new JSONObject(Map.of("tool_calls", List.of(call))), "tool_calls");
          } else {
            followup.set(request);
            response = chunk(new JSONObject(Map.of("content", "检查完成")), "stop");
          }
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
          exchange.close();
        });
    server.start();
    ops.settings(
        new JSONObject(
            Map.of(
                "base_url",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "model",
                "test",
                "api_key",
                "secret-key")));
    long now = System.currentTimeMillis();
    db.update("INSERT INTO ai_ops_chat VALUES(1,7,'test',?,?)", now, now);
    db.update(
        "INSERT INTO ai_ops_chat_turn(id,chat_id,user_content,policy_json,status,created_at)"
            + " VALUES(1,1,'check','{}','running',?)",
        now);
    JSONObject c = config();
    c.put("nodeIds", List.of(1));
    chat.execute(1, 1, c, settings(), new JSONArray(), e -> {}, now);
    assertEquals(2, requests.get());
    JSONArray messages = followup.get().getJSONArray("messages");
    assertEquals("check disk first", messages.getJSONObject(2).getString("reasoning_content"));
    assertEquals("tool", messages.getJSONObject(3).getString("role"));
    assertEquals(1, toolCalls.size());
  }

  @Test
  void textReachesConsumerBeforeProviderFinishes() throws Exception {
    CountDownLatch finish = new CountDownLatch(1), observed = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange
                .getResponseBody()
                .write(
                    chunk(new JSONObject(Map.of("content", "你好")), null)
                        .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            finish.await(3, TimeUnit.SECONDS);
            exchange
                .getResponseBody()
                .write(chunk(new JSONObject(), "stop").getBytes(StandardCharsets.UTF_8));
          } catch (Exception ignored) {
          } finally {
            exchange.close();
          }
        });
    server.start();
    ops.settings(
        new JSONObject(
            Map.of(
                "base_url",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "model",
                "test",
                "api_key",
                "very-long-secret-key")));
    var pending =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return ops.streamCompletion(
                    settings(),
                    new JSONArray(List.of(Map.of("role", "user", "content", "hello"))),
                    new JSONArray(),
                    5,
                    text -> observed.countDown());
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            });
    try {
      assertTrue(observed.await(2, TimeUnit.SECONDS));
      assertFalse(pending.isDone());
    } finally {
      finish.countDown();
    }
    assertEquals("你好", pending.get(5, TimeUnit.SECONDS).getString("content"));
  }
}
