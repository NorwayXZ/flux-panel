package com.admin.service;

import com.alibaba.fastjson2.*;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** Incremental SSE decoding keeps split UTF-8 characters and tool argument fragments intact. */
final class AiChatStreamSubscriber implements HttpResponse.BodySubscriber<byte[]> {
  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> result = new CompletableFuture<>();
  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private final Consumer<String> text;
  private Flow.Subscription subscription;

  AiChatStreamSubscriber(Consumer<String> text) {
    this.text = text;
  }

  public CompletionStage<byte[]> getBody() {
    return result;
  }

  public void onSubscribe(Flow.Subscription s) {
    subscription = s;
    s.request(1);
  }

  public void onNext(List<ByteBuffer> buffers) {
    try {
      for (ByteBuffer b : buffers) {
        if (b.remaining() > 262144 - body.size()) throw new IllegalStateException("模型响应超过容量限制");
        while (b.hasRemaining()) {
          byte next = b.get();
          body.write(next);
          if (next == '\n') {
            emit(line.toString(StandardCharsets.UTF_8));
            line.reset();
          } else line.write(next);
        }
      }
      subscription.request(1);
    } catch (Exception e) {
      subscription.cancel();
      result.completeExceptionally(e);
    }
  }

  private void emit(String raw) {
    JSONObject part = event(raw);
    if (part == null) return;
    JSONArray choices = part.getJSONArray("choices");
    if (choices == null || choices.isEmpty()) return;
    JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
    if (delta != null && delta.getString("content") != null)
      text.accept(delta.getString("content"));
  }

  static JSONObject event(String line) {
    if (!line.startsWith("data:")) return null;
    String data = line.substring(5).trim();
    if (data.equals("[DONE]") || data.isEmpty()) return null;
    JSONObject result = JSON.parseObject(data);
    if (result.containsKey("error")) throw new IllegalStateException("模型流返回错误");
    return result;
  }

  public void onError(Throwable error) {
    result.completeExceptionally(error);
  }

  public void onComplete() {
    try {
      if (line.size() > 0) emit(line.toString(StandardCharsets.UTF_8));
      result.complete(body.toByteArray());
    } catch (Exception e) {
      result.completeExceptionally(e);
    }
  }

  static JSONObject assemble(byte[] bytes) {
    StringBuilder content = new StringBuilder(), reasoning = new StringBuilder();
    TreeMap<Integer, JSONObject> tools = new TreeMap<>();
    boolean finished = false;
    for (String raw : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
      JSONObject part = event(raw);
      if (part == null) continue;
      JSONArray choices = part.getJSONArray("choices");
      if (choices == null || choices.isEmpty()) continue;
      JSONObject choice = choices.getJSONObject(0);
      if (choice.getString("finish_reason") != null) {
        if (List.of("length", "content_filter").contains(choice.getString("finish_reason")))
          throw new IllegalStateException("模型回复被截断或拦截，请缩小范围后重试");
        finished = true;
      }
      JSONObject delta = choice.getJSONObject("delta");
      if (delta == null) continue;
      if (delta.getString("content") != null) content.append(delta.getString("content"));
      if (delta.getString("reasoning_content") != null)
        reasoning.append(delta.getString("reasoning_content"));
      JSONArray calls = delta.getJSONArray("tool_calls");
      if (calls == null) continue;
      for (Object value : calls) {
        JSONObject fragment = (JSONObject) value;
        int index = fragment.getIntValue("index");
        if (index < 0 || index >= 30) throw new IllegalStateException("模型工具调用数量超限");
        JSONObject call =
            tools.computeIfAbsent(
                index,
                k ->
                    new JSONObject(
                        Map.of(
                            "type",
                            "function",
                            "function",
                            new JSONObject(Map.of("name", "", "arguments", "")))));
        if (fragment.getString("id") != null)
          call.put("id", Objects.toString(call.get("id"), "") + fragment.getString("id"));
        JSONObject fn = fragment.getJSONObject("function");
        if (fn != null)
          for (String key : List.of("name", "arguments"))
            if (fn.getString(key) != null)
              call.getJSONObject("function")
                  .put(key, call.getJSONObject("function").getString(key) + fn.getString(key));
      }
    }
    if (!finished) throw new IllegalStateException("模型流意外中断，请重试");
    JSONObject message = new JSONObject(Map.of("role", "assistant", "content", content.toString()));
    if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning.toString());
    if (!tools.isEmpty()) message.put("tool_calls", new JSONArray(new ArrayList<>(tools.values())));
    return message;
  }
}
