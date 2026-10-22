package com.admin.service;

import com.admin.common.lang.R;
import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * AI change proposals for panel control-plane actions.
 *
 * <p>The assistant can only <em>propose</em> an action here; nothing runs until an administrator
 * approves it in the panel, and approval executes through the same {@link ForwardService} methods
 * as the panel pages and the MCP endpoint, so permissions and safeguards stay identical. The
 * action whitelist is deliberately small and reversible: pause or resume one forward.
 */
@Service
public class AiOpsProposalService {
  static final Set<String> ACTIONS = Set.of("pause_forward", "resume_forward");
  static final long MAX_AGE_MS = 7L * 86400000;

  private final JdbcTemplate db;
  private final ForwardService forwards;

  public AiOpsProposalService(JdbcTemplate db, ForwardService forwards) {
    this.db = db;
    this.forwards = forwards;
  }

  static String validate(String action, long targetId, String title, String reason) {
    if (action == null || !ACTIONS.contains(action)) return "不支持的提案动作";
    if (targetId <= 0) return "提案目标无效";
    if (title == null || title.isBlank() || title.length() > 200) return "请填写提案标题（不超过200字）";
    if (reason == null || reason.isBlank() || reason.length() > 1000)
      return "请填写提案理由（不超过1000字）";
    return null;
  }

  static String actionLabel(String action) {
    return switch (action == null ? "" : action) {
      case "pause_forward" -> "暂停转发";
      case "resume_forward" -> "恢复转发";
      default -> "未知动作";
    };
  }

  /** Creates a pending proposal and returns its view; invalid input throws IllegalArgumentException. */
  public Object propose(long creatorId, String source, String action, long targetId,
      String title, String reason) {
    String error = validate(action, targetId, title, reason);
    if (error != null) throw new IllegalArgumentException(error);
    var targets = db.queryForList("SELECT id,name FROM forward WHERE id=?", targetId);
    if (targets.isEmpty()) throw new IllegalArgumentException("目标转发不存在：" + targetId);
    String targetName = String.valueOf(targets.get(0).get("name"));
    var pending = db.queryForList(
        "SELECT id FROM ai_ops_proposal WHERE action=? AND target_id=? AND status='pending'",
        action, targetId);
    if (!pending.isEmpty())
      throw new IllegalArgumentException(
          "已有待审批的相同提案（#" + pending.get(0).get("id") + "），请勿重复提交");
    long now = System.currentTimeMillis();
    Object[] values = {
      creatorId, source == null ? "chat" : source, action, targetId, targetName,
      title.trim(), reason.trim(), now
    };
    var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
    db.update(
        connection -> {
          var statement = connection.prepareStatement(
              "INSERT INTO ai_ops_proposal(creator_id,source,action,target_id,target_name,title,"
                  + "reason,status,created_at) VALUES(?,?,?,?,?,?,?,'pending',?)",
              java.sql.Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
          return statement;
        },
        key);
    long id = java.util.Objects.requireNonNull(key.getKey()).longValue();
    return view(db.queryForMap("SELECT * FROM ai_ops_proposal WHERE id=?", id));
  }

  public R list(String status) {
    expireOld();
    List<JSONObject> items = new ArrayList<>();
    var rows = status == null || status.isBlank()
        ? db.queryForList("SELECT * FROM ai_ops_proposal ORDER BY id DESC LIMIT 100")
        : db.queryForList(
            "SELECT * FROM ai_ops_proposal WHERE status=? ORDER BY id DESC LIMIT 100", status);
    for (var row : rows) items.add(view(row));
    return R.ok(items);
  }

  /** Approves a pending proposal and executes it immediately as the deciding administrator. */
  public synchronized R approve(long id, long deciderId) {
    expireOld();
    var rows = db.queryForList("SELECT * FROM ai_ops_proposal WHERE id=?", id);
    if (rows.isEmpty()) return R.err("提案不存在");
    var row = rows.get(0);
    if (!"pending".equals(row.get("status"))) return R.err("提案已处理，不能重复批准");
    String action = String.valueOf(row.get("action"));
    long targetId = ((Number) row.get("target_id")).longValue();
    long now = System.currentTimeMillis();
    R executed;
    try {
      executed = switch (action) {
        case "pause_forward" -> forwards.pauseForward(targetId);
        case "resume_forward" -> forwards.resumeForward(targetId);
        default -> R.err("不支持的提案动作：" + action);
      };
    } catch (Exception e) {
      executed = R.err("执行异常：" + e.getMessage());
    }
    boolean ok = executed != null && executed.getCode() == 0;
    String result = executed == null
        ? "执行无返回"
        : String.valueOf(executed.getData() != null ? executed.getData() : executed.getMsg());
    if (result.length() > 1000) result = result.substring(0, 1000);
    db.update(
        "UPDATE ai_ops_proposal SET status=?,decided_by=?,decided_at=?,executed_at=?,result=?"
            + " WHERE id=? AND status='pending'",
        ok ? "executed" : "failed", deciderId, now, now, result, id);
    var updated = db.queryForMap("SELECT * FROM ai_ops_proposal WHERE id=?", id);
    return ok ? R.ok(view(updated)) : R.err("提案执行失败：" + result);
  }

  public synchronized R reject(long id, long deciderId) {
    var rows = db.queryForList("SELECT * FROM ai_ops_proposal WHERE id=?", id);
    if (rows.isEmpty()) return R.err("提案不存在");
    if (!"pending".equals(rows.get(0).get("status"))) return R.err("提案已处理");
    long now = System.currentTimeMillis();
    db.update(
        "UPDATE ai_ops_proposal SET status='rejected',decided_by=?,decided_at=? WHERE id=?"
            + " AND status='pending'",
        deciderId, now, id);
    return R.ok(view(db.queryForMap("SELECT * FROM ai_ops_proposal WHERE id=?", id)));
  }

  private void expireOld() {
    db.update(
        "UPDATE ai_ops_proposal SET status='expired' WHERE status='pending' AND created_at<?",
        System.currentTimeMillis() - MAX_AGE_MS);
  }

  static JSONObject view(Map<String, Object> row) {
    JSONObject o = new JSONObject();
    o.put("id", row.get("id"));
    o.put("creatorId", row.get("creator_id"));
    o.put("source", row.get("source"));
    o.put("action", row.get("action"));
    o.put("actionLabel", actionLabel(String.valueOf(row.get("action"))));
    o.put("targetId", row.get("target_id"));
    o.put("targetName", row.get("target_name"));
    o.put("title", row.get("title"));
    o.put("reason", row.get("reason"));
    o.put("status", row.get("status"));
    o.put("createdAt", row.get("created_at"));
    o.put("decidedBy", row.get("decided_by"));
    o.put("decidedAt", row.get("decided_at"));
    o.put("executedAt", row.get("executed_at"));
    o.put("result", row.get("result"));
    return o;
  }
}
