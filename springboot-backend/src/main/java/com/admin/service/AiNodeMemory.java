package com.admin.service;

import com.admin.common.lang.R;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What the assistant remembers per server (node notes) and what it changed (change log with file
 * snapshots, so a file edit can be compared and restored).
 */
@Slf4j
@Service
public class AiNodeMemory {
  static final int NOTE_LIMIT = 2000;
  static final int FILE_LIMIT = 32 * 1024;
  private static final Pattern PATH = Pattern.compile("^/(?:[A-Za-z0-9._@+-]+/)*[A-Za-z0-9._@+-]+$");
  private final JdbcTemplate db;
  private final AiLegacyDiagnostics terminal;

  public AiNodeMemory(JdbcTemplate db, AiLegacyDiagnostics terminal) {
    this.db = db;
    this.terminal = terminal;
  }

  @PostConstruct
  public void initialize() {
    try {
      db.execute("CREATE TABLE IF NOT EXISTS ai_node_note (node_id bigint NOT NULL PRIMARY KEY, content text NOT NULL,"
          + " updated_at bigint NOT NULL, updated_by varchar(32) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      db.execute("CREATE TABLE IF NOT EXISTS ai_ops_change (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, node_id bigint NOT NULL,"
          + " kind varchar(16) NOT NULL, source varchar(64) NOT NULL, summary varchar(500) NOT NULL, path varchar(512) NULL,"
          + " before_content mediumtext NULL, after_content mediumtext NULL, existed_before tinyint NOT NULL DEFAULT 1,"
          + " command mediumtext NULL, exit_code int NULL, output text NULL, restored_at bigint NULL, created_at bigint NOT NULL,"
          + " KEY idx_ai_change_node(node_id,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    } catch (Exception e) {
      log.warn("AI memory tables unavailable: {}", e.getMessage());
    }
  }

  // ---------------- Node notes ----------------

  public Map<Long, String> notes(Collection<Long> nodes) {
    Map<Long, String> result = new LinkedHashMap<>();
    if (nodes.isEmpty()) return result;
    String marks = String.join(",", Collections.nCopies(nodes.size(), "?"));
    try {
      for (var row : db.queryForList("SELECT node_id,content FROM ai_node_note WHERE node_id IN (" + marks + ")", nodes.toArray()))
        result.put(((Number) row.get("node_id")).longValue(), Objects.toString(row.get("content"), ""));
    } catch (Exception ignored) {
    }
    return result;
  }

  /** Prompt section with the stored notes of the selected nodes. */
  public String promptSection(Collection<Long> nodes) {
    Map<Long, String> notes = notes(nodes);
    if (notes.isEmpty()) return "";
    StringBuilder text = new StringBuilder("Saved node notes (your earlier findings; verify before relying on them):");
    notes.forEach((node, note) -> text.append("\n[node ").append(node).append("] ").append(note));
    return text.toString();
  }

  public Map<String, Object> remember(long node, String note, String by) {
    String content = Objects.toString(note, "").replace("\r", "").trim();
    if (content.isEmpty()) throw new IllegalArgumentException("笔记内容为空");
    if (content.length() > NOTE_LIMIT)
      throw new IllegalArgumentException("笔记超过 " + NOTE_LIMIT + " 字，请合并旧内容后整体改写得更精炼");
    db.update("INSERT INTO ai_node_note(node_id,content,updated_at,updated_by) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE"
        + " content=VALUES(content),updated_at=VALUES(updated_at),updated_by=VALUES(updated_by)", node, content, System.currentTimeMillis(), by);
    return Map.of("saved", true, "length", content.length());
  }

  public R listNotes() {
    return R.ok(db.queryForList("SELECT n.id AS nodeId,n.name,a.content,a.updated_at AS updatedAt,a.updated_by AS updatedBy FROM node n"
        + " LEFT JOIN ai_node_note a ON a.node_id=n.id ORDER BY n.id"));
  }

  public R saveNote(long node, String content) {
    if (Objects.toString(content, "").isBlank()) {
      db.update("DELETE FROM ai_node_note WHERE node_id=?", node);
      return R.ok();
    }
    return R.ok(remember(node, content, "admin"));
  }

  // ---------------- Change log ----------------

  public void recordCommand(long node, String source, String command, Map<String, Object> result) {
    try {
      String output = Objects.toString(result.get("output"), "");
      db.update("INSERT INTO ai_ops_change(node_id,kind,source,summary,command,exit_code,output,created_at) VALUES(?,?,?,?,?,?,?,?)",
          node, "command", source, abbreviate(firstLine(command), 480), command, result.get("exitCode"),
          output.length() > 8000 ? output.substring(output.length() - 8000) : output, System.currentTimeMillis());
    } catch (Exception e) {
      log.debug("change log skipped: {}", e.getMessage());
    }
  }

  static void validatePath(String path) {
    if (path == null || path.length() > 512 || !PATH.matcher(path).matches() || path.contains("/../") || path.endsWith("/..")
        || path.contains("/./"))
      throw new IllegalArgumentException("文件路径必须是不含特殊字符的绝对路径");
    for (String forbidden : List.of("/proc/", "/sys/", "/dev/"))
      if (path.startsWith(forbidden)) throw new IllegalArgumentException("不能写入 " + forbidden);
  }

  /** Reads the current file, writes the new content atomically, and records both for diff and restore. */
  public Map<String, Object> writeFile(long node, String path, String content, String reason, String source, int timeout) throws Exception {
    validatePath(path);
    byte[] bytes = Objects.toString(content, "").getBytes(StandardCharsets.UTF_8);
    if (bytes.length > FILE_LIMIT) throw new IllegalArgumentException("文件内容超过 32 KB，请改用命令处理大文件");
    Snapshot before = read(node, path, timeout);
    if (before.text != null && before.text.getBytes(StandardCharsets.UTF_8).length > FILE_LIMIT)
      throw new IllegalArgumentException("原文件超过 32 KB，无法保存可还原的快照");
    write(node, path, bytes, timeout);
    db.update("INSERT INTO ai_ops_change(node_id,kind,source,summary,path,before_content,after_content,existed_before,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?)", node, "file", source, abbreviate(Objects.toString(reason, "修改 " + path), 480), path,
        before.text, new String(bytes, StandardCharsets.UTF_8), before.exists ? 1 : 0, System.currentTimeMillis());
    Long id = db.queryForObject("SELECT MAX(id) FROM ai_ops_change WHERE node_id=? AND path=?", Long.class, node, path);
    return Map.of("changeId", id, "path", path, "existedBefore", before.exists, "bytes", bytes.length,
        "note", "已记录修改前内容，可在 AI 运维 → 改动记录 查看差异或还原");
  }

  public R changes(Long node) {
    String where = node == null ? "" : " WHERE node_id=" + node;
    return R.ok(db.queryForList("SELECT c.id,c.node_id AS nodeId,n.name AS nodeName,c.kind,c.source,c.summary,c.path,c.before_content AS"
        + " beforeContent,c.after_content AS afterContent,c.existed_before AS existedBefore,c.command,c.exit_code AS exitCode,c.output,"
        + " c.restored_at AS restoredAt,c.created_at AS createdAt FROM ai_ops_change c LEFT JOIN node n ON n.id=c.node_id"
        + where.replace("node_id", "c.node_id") + " ORDER BY c.id DESC LIMIT 100"));
  }

  /** Puts a file back to its recorded state before the change (or removes it if it did not exist). */
  public R restore(long changeId) throws Exception {
    var rows = db.queryForList("SELECT * FROM ai_ops_change WHERE id=? AND kind='file'", changeId);
    if (rows.isEmpty()) return R.err("只有文件修改可以还原");
    var row = rows.get(0);
    long node = ((Number) row.get("node_id")).longValue();
    String path = row.get("path").toString();
    validatePath(path);
    Snapshot current = read(node, path, 40);
    String after = Objects.toString(row.get("after_content"), "");
    if (current.exists && !after.equals(current.text) && row.get("restored_at") == null)
      return R.err("文件在这次修改之后又被改动过，为避免覆盖新内容，请先对比后手动处理");
    boolean existed = ((Number) row.get("existed_before")).intValue() == 1;
    if (existed) write(node, path, Objects.toString(row.get("before_content"), "").getBytes(StandardCharsets.UTF_8), 40);
    else terminal.execute(node, "rm -f -- '" + path + "'", 30);
    db.update("UPDATE ai_ops_change SET restored_at=? WHERE id=?", System.currentTimeMillis(), changeId);
    db.update("INSERT INTO ai_ops_change(node_id,kind,source,summary,path,before_content,after_content,existed_before,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?)", node, "file", "restore", "还原改动 #" + changeId, path, current.text,
        existed ? row.get("before_content") : null, current.exists ? 1 : 0, System.currentTimeMillis());
    return R.ok(Map.of("restored", true, "path", path));
  }

  record Snapshot(boolean exists, String text) {}

  private Snapshot read(long node, String path, int timeout) throws Exception {
    String quoted = "'" + path + "'";
    var result = terminal.execute(node, "if [ -f " + quoted + " ]; then echo EXISTS; size=$(wc -c < " + quoted + ");"
        + " if [ \"$size\" -gt " + FILE_LIMIT + " ]; then echo TOO_LARGE; else base64 < " + quoted + " | tr -d '\\n'; echo; fi;"
        + " elif [ -e " + quoted + " ]; then echo NOT_REGULAR; else echo MISSING; fi", timeout);
    String output = Objects.toString(result.get("output"), "").trim();
    String[] lines = output.split("\n");
    int start = 0;
    while (start < lines.length && !List.of("EXISTS", "MISSING", "NOT_REGULAR").contains(lines[start].trim())) start++;
    if (start >= lines.length) throw new IllegalStateException("未能读取文件状态");
    String state = lines[start].trim();
    if (state.equals("MISSING")) return new Snapshot(false, null);
    if (state.equals("NOT_REGULAR")) throw new IllegalArgumentException("目标不是普通文件");
    String body = start + 1 < lines.length ? lines[start + 1].trim() : "";
    if (body.equals("TOO_LARGE")) throw new IllegalArgumentException("原文件超过 32 KB，无法保存可还原的快照");
    return new Snapshot(true, new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8));
  }

  private void write(long node, String path, byte[] bytes, int timeout) throws Exception {
    String quoted = "'" + path + "'";
    String encoded = Base64.getEncoder().encodeToString(bytes);
    // The terminal channel accepts scripts up to 16000 characters, so the content is staged in chunks.
    String staged = "/tmp/.cloudnest-ai-write-" + UUID.randomUUID().toString().replace("-", "");
    for (int offset = 0; offset < encoded.length() || offset == 0; offset += 12000) {
      String chunk = encoded.substring(offset, Math.min(encoded.length(), offset + 12000));
      var part = terminal.execute(node, "umask 077; printf '%s' '" + chunk + "' " + (offset == 0 ? ">" : ">>") + " " + staged + " && echo STAGED", timeout);
      if (!Objects.toString(part.get("output"), "").contains("STAGED")) throw new IllegalStateException("暂存文件内容失败");
      if (encoded.isEmpty()) break;
    }
    // Write next to the target and rename, keeping the original mode and owner when the file exists.
    var result = terminal.execute(node, "set -e; dir=$(dirname " + quoted + "); mkdir -p \"$dir\"; tmp=$(mktemp \"$dir/.ai-write.XXXXXX\");"
        + " base64 -d " + staged + " > \"$tmp\"; rm -f " + staged + "; if [ -f " + quoted + " ]; then chmod --reference=" + quoted
        + " \"$tmp\" 2>/dev/null || true; chown --reference=" + quoted + " \"$tmp\" 2>/dev/null || true; else chmod 644 \"$tmp\"; fi;"
        + " mv -f \"$tmp\" " + quoted + "; echo WRITTEN", timeout);
    if (!Objects.toString(result.get("output"), "").contains("WRITTEN") || !Objects.equals(result.get("exitCode"), 0))
      throw new IllegalStateException("写入失败：" + abbreviate(Objects.toString(result.get("output"), ""), 300));
  }

  private static String firstLine(String text) {
    String value = Objects.toString(text, "").trim();
    int newline = value.indexOf('\n');
    return newline < 0 ? value : value.substring(0, newline) + " …";
  }

  private static String abbreviate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }
}
