package com.admin.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Built-in knowledge of every panel feature (from the in-panel guide, page help, docs and release
 * notes; see scripts/generate-ai-panel-knowledge.mjs). A compact index goes into every system
 * prompt; full steps and caveats are retrieved with the panel_knowledge tool.
 */
@Service
public class AiPanelKnowledge {
  private final List<JSONObject> entries = new ArrayList<>();

  public AiPanelKnowledge() {
    try (var in = new ClassPathResource("ai/panel-knowledge.json").getInputStream()) {
      JSONArray list = JSON.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("entries");
      for (int i = 0; i < list.size(); i++) entries.add(list.getJSONObject(i));
    } catch (Exception ignored) {
      // Without the resource the assistant still works, only without built-in knowledge.
    }
  }

  int size() {
    return entries.size();
  }

  /** One line per feature page: where it is and what it is for. */
  String index() {
    StringBuilder text = new StringBuilder("Panel features (title | page path | summary):");
    for (JSONObject entry : entries) {
      if (!"guide".equals(entry.getString("kind"))) continue;
      text.append("\n- ").append(entry.getString("title")).append(" | ").append(entry.getString("path"))
          .append(" | ").append(entry.getString("summary"));
      if (entry.getBooleanValue("adminOnly")) text.append(" (admin)");
    }
    return text.toString();
  }

  /** Best matching entries for a question, with full steps, notes and known limitations. */
  List<Map<String, Object>> search(String query, int limit) {
    List<String> terms = terms(query);
    if (terms.isEmpty()) throw new IllegalArgumentException("请提供要查询的功能或问题关键词");
    List<Map.Entry<Double, JSONObject>> scored = new ArrayList<>();
    for (JSONObject entry : entries) {
      String title = Objects.toString(entry.getString("title"), "").toLowerCase(Locale.ROOT);
      String keys = (Objects.toString(entry.getString("keywords"), "") + " " + Objects.toString(entry.getString("path"), ""))
          .toLowerCase(Locale.ROOT);
      String body = (Objects.toString(entry.getString("summary"), "") + " " + Objects.toString(entry.getString("text"), ""))
          .toLowerCase(Locale.ROOT);
      double score = 0;
      for (String term : terms) {
        if (title.contains(term)) score += 6;
        if (keys.contains(term)) score += 3;
        score += Math.min(5, occurrences(body, term));
      }
      // Guides describe how to operate a feature; release notes add caveats when equally relevant.
      if (score > 0 && "guide".equals(entry.getString("kind"))) score *= 1.3;
      if (score > 0) scored.add(Map.entry(score, entry));
    }
    scored.sort((a, b) -> Double.compare(b.getKey(), a.getKey()));
    List<Map<String, Object>> result = new ArrayList<>();
    for (var item : scored.subList(0, Math.min(limit, scored.size()))) {
      JSONObject entry = item.getValue();
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("source", entry.getString("kind"));
      view.put("title", entry.getString("title"));
      if (entry.containsKey("path")) view.put("path", entry.getString("path"));
      String text = Objects.toString(entry.getString("text"), "");
      view.put("text", text.length() > 3500 ? text.substring(0, 3500) + "…" : text);
      result.add(view);
    }
    return result;
  }

  /** Latin words and two-character Chinese chunks, so "转发很慢" matches "转发". */
  static List<String> terms(String query) {
    String text = Objects.toString(query, "").toLowerCase(Locale.ROOT);
    LinkedHashSet<String> terms = new LinkedHashSet<>();
    for (String word : text.split("[^a-z0-9.\\-_/]+")) if (word.length() >= 2) terms.add(word);
    StringBuilder run = new StringBuilder();
    for (int i = 0; i <= text.length(); i++) {
      char c = i < text.length() ? text.charAt(i) : ' ';
      if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
        run.append(c);
        continue;
      }
      if (run.length() == 1) terms.add(run.toString());
      for (int j = 0; j + 2 <= run.length(); j++) terms.add(run.substring(j, j + 2));
      run.setLength(0);
    }
    return new ArrayList<>(terms).subList(0, Math.min(40, terms.size()));
  }

  private static int occurrences(String text, String term) {
    int count = 0;
    for (int i = text.indexOf(term); i >= 0 && count < 20; i = text.indexOf(term, i + term.length())) count++;
    return count;
  }
}
