package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class AgentUpgradeService {
    public static final String TARGET_VERSION = "2.13.2";
    public static final String SELF_UPDATE_MIN_VERSION = "2.13.0";
    public static final String TERMINAL_BOOTSTRAP_MIN_VERSION = "2.8.0";
    private static final long TASK_TIMEOUT_MS = 5 * 60_000L;
    private static final String RELEASE_SCRIPT = "https://raw.githubusercontent.com/NorwayXZ/flux-panel/"
            + TARGET_VERSION + "/install.sh";
    private static final List<String> ACTIVE_STATES = List.of(
            "queued", "bootstrapping", "accepted", "downloading", "verified", "restarting", "installing");

    private final NodeService nodeService;
    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, BootstrapSession> bootstrapSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> bootstrapOutput = new ConcurrentHashMap<>();

    public AgentUpgradeService(NodeService nodeService, JdbcTemplate jdbcTemplate) {
        this.nodeService = nodeService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> getStatus(Long nodeId) {
        List<Node> nodes = nodeId == null ? nodeService.list() : List.of(requireNode(nodeId));
        Map<Long, Map<String, Object>> latest = latestTasks(nodes.stream().map(Node::getId).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Node node : nodes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", node.getId());
            item.put("nodeName", node.getName());
            item.put("currentVersion", node.getVersion());
            item.put("targetVersion", TARGET_VERSION);
            item.put("online", WebSocketServer.isNodeOnline(node.getId()));
            item.put("upToDate", AgentVersionUtil.isAtLeast(node.getVersion(), TARGET_VERSION));
            item.put("mode", upgradeMode(node.getVersion()));
            item.put("task", latest.get(node.getId()));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetVersion", TARGET_VERSION);
        result.put("items", items);
        return result;
    }

    public R start(Long nodeId) {
        Node node = requireNode(nodeId);
        if (!WebSocketServer.isNodeOnline(nodeId)) {
            return R.err(409, "节点离线，无法远程升级");
        }
        if (AgentVersionUtil.isAtLeast(node.getVersion(), TARGET_VERSION)) {
            return R.ok(statusForNode(nodeId));
        }
        if (hasActiveTask(nodeId)) {
            return R.err(409, "该节点已有升级任务正在执行");
        }
        String mode = upgradeMode(node.getVersion());
        if ("manual".equals(mode)) {
            return R.err(409, "当前 Agent 版本不支持远程升级，请先手动升级到 "
                    + TERMINAL_BOOTSTRAP_MIN_VERSION + " 或更高版本");
        }

        String taskId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO agent_upgrade_task "
                        + "(task_id,node_id,node_name,from_version,target_version,state,message,requested_by,requested_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                taskId, node.getId(), node.getName(), node.getVersion(), TARGET_VERSION, "queued",
                "等待节点接收升级任务", JwtUtil.getUserIdFromToken(), now, now);

        if ("terminal".equals(mode)) {
            return startTerminalBootstrap(node, taskId);
        }
        JSONObject payload = new JSONObject();
        payload.put("taskId", taskId);
        payload.put("targetVersion", TARGET_VERSION);
        updateTask(taskId, "accepted", "节点正在准备升级", null);
        GostDto response = WebSocketServer.send_msg(nodeId, payload, "AgentUpgrade");
        if (!Objects.equals(response.getMsg(), "OK")) {
            updateTask(taskId, "failed", response.getMsg(), System.currentTimeMillis());
            return R.err(response.getMsg());
        }
        return R.ok(statusForNode(nodeId));
    }

    public R startBatch() {
        List<Map<String, Object>> results = new ArrayList<>();
        int submitted = 0;
        for (Node node : nodeService.list()) {
            if (submitted >= 20 || !WebSocketServer.isNodeOnline(node.getId())
                    || AgentVersionUtil.isAtLeast(node.getVersion(), TARGET_VERSION)
                    || "manual".equals(upgradeMode(node.getVersion())) || hasActiveTask(node.getId())) {
                continue;
            }
            R result = start(node.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", node.getId());
            item.put("nodeName", node.getName());
            item.put("accepted", result.getCode() == 0);
            item.put("message", result.getMsg());
            results.add(item);
            if (result.getCode() == 0) submitted++;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("submitted", submitted);
        response.put("results", results);
        return R.ok(response);
    }

    public List<Map<String, Object>> history(Long nodeId) {
        String sql = "SELECT task_id AS taskId,node_id AS nodeId,node_name AS nodeName,"
                + "from_version AS fromVersion,target_version AS targetVersion,state,message,requested_by AS requestedBy,"
                + "requested_at AS requestedAt,updated_at AS updatedAt,finished_at AS finishedAt "
                + "FROM agent_upgrade_task " + (nodeId == null ? "" : "WHERE node_id=? ")
                + "ORDER BY requested_at DESC LIMIT 50";
        return nodeId == null ? jdbcTemplate.queryForList(sql) : jdbcTemplate.queryForList(sql, nodeId);
    }

    public boolean handleAgentMessage(Long nodeId, JSONObject message) {
        String type = message.getString("type");
        if (type == null || !type.startsWith("AgentUpgrade")) return false;
        JSONObject data = message.getJSONObject("data");
        String taskId = data == null ? null : data.getString("taskId");
        if (taskId == null || !taskBelongsToNode(taskId, nodeId)) return true;
        String state = data.getString("state");
        String detail = message.getString("message");
        if ("AgentUpgradeResult".equals(type)) {
            boolean success = message.getBooleanValue("success");
            updateTask(taskId, success ? "success" : Objects.equals(state, "rolled_back") ? "rolled_back" : "failed",
                    detail, System.currentTimeMillis());
        } else if (state != null && ACTIVE_STATES.contains(state)) {
            updateTask(taskId, state, detail, null);
        }
        return true;
    }

    public boolean handleTerminalEvent(Long nodeId, JSONObject message) {
        JSONObject data = message.getJSONObject("data");
        String sessionId = data == null ? null : data.getString("sessionId");
        BootstrapSession bootstrap = sessionId == null ? null : bootstrapSessions.get(sessionId);
        if (bootstrap == null || !Objects.equals(bootstrap.nodeId(), nodeId)) return false;

        String type = message.getString("type");
        if ("TerminalOpened".equals(type)) {
            JSONObject input = new JSONObject();
            input.put("sessionId", sessionId);
            input.put("data", Base64.getEncoder().encodeToString(
                    (bootstrapCommand(bootstrap.taskId()) + "\n").getBytes(StandardCharsets.UTF_8)));
            updateTask(bootstrap.taskId(), "bootstrapping", "正在启动独立升级助手", null);
            WebSocketServer.sendNodeEvent(nodeId, "TerminalInput", input);
        } else if ("TerminalOutput".equals(type)) {
            String output = bootstrapOutput.merge(sessionId, decodeTerminalOutput(data.getString("data")),
                    (previous, current) -> {
                        String combined = previous + current;
                        return combined.length() <= 4096 ? combined : combined.substring(combined.length() - 4096);
                    });
            if (output.contains("FLUX_UPGRADE_STARTED")) {
                updateTask(bootstrap.taskId(), "restarting", "升级助手已启动，等待 Agent 重新上线", null);
                closeBootstrapSession(bootstrap, "升级助手已启动");
            } else if (output.contains("FLUX_UPGRADE_FAILED")) {
                updateTask(bootstrap.taskId(), "failed", "无法启动升级助手", System.currentTimeMillis());
                closeBootstrapSession(bootstrap, "升级助手启动失败");
            }
        } else if ("TerminalError".equals(type) || "TerminalClosed".equals(type)) {
            bootstrapSessions.remove(sessionId);
            bootstrapOutput.remove(sessionId);
            if (!"restarting".equals(taskState(bootstrap.taskId()))) {
                updateTask(bootstrap.taskId(), "failed", data.getString("message"), System.currentTimeMillis());
            }
        }
        return true;
    }

    public void handleNodeConnected(Long nodeId, String version) {
        Map<String, Object> active = latestActiveTask(nodeId);
        if (active == null) return;
        String taskId = Objects.toString(active.get("taskId"), "");
        String target = Objects.toString(active.get("targetVersion"), TARGET_VERSION);
        if (AgentVersionUtil.isAtLeast(version, target)) {
            updateTask(taskId, "success", "Agent 已升级并重新上线", System.currentTimeMillis());
            bootstrapSessions.entrySet().removeIf(entry -> Objects.equals(entry.getValue().nodeId(), nodeId));
            bootstrapOutput.entrySet().removeIf(entry -> !bootstrapSessions.containsKey(entry.getKey()));
        }
    }

    @Scheduled(fixedDelay = 30_000L)
    public void expireTasks() {
        long cutoff = System.currentTimeMillis() - TASK_TIMEOUT_MS;
        jdbcTemplate.update("UPDATE agent_upgrade_task SET state='timeout',message='等待 Agent 重新上线超时',"
                        + "updated_at=?,finished_at=? WHERE state IN (?,?,?,?,?,?,?) AND updated_at<?",
                System.currentTimeMillis(), System.currentTimeMillis(),
                ACTIVE_STATES.get(0), ACTIVE_STATES.get(1), ACTIVE_STATES.get(2), ACTIVE_STATES.get(3),
                ACTIVE_STATES.get(4), ACTIVE_STATES.get(5), ACTIVE_STATES.get(6), cutoff);
    }

    private R startTerminalBootstrap(Node node, String taskId) {
        String sessionId = "agent-upgrade-" + taskId;
        bootstrapSessions.put(sessionId, new BootstrapSession(sessionId, taskId, node.getId()));
        bootstrapOutput.put(sessionId, "");
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        data.put("cols", 120);
        data.put("rows", 32);
        updateTask(taskId, "bootstrapping", "正在通过节点终端启动升级助手", null);
        if (!WebSocketServer.sendNodeEvent(node.getId(), "TerminalOpen", data)) {
            bootstrapSessions.remove(sessionId);
            updateTask(taskId, "failed", "节点终端通道不可用", System.currentTimeMillis());
            return R.err("节点终端通道不可用");
        }
        return R.ok(statusForNode(node.getId()));
    }

    String bootstrapCommand(String taskId) {
        String script = "/tmp/flux-agent-update-" + taskId + ".sh";
        String unit = "flux-agent-bootstrap-" + taskId.substring(0, 12);
        String run = "/bin/sh " + script + " -U >>/var/log/flux-agent-update.log 2>&1";
        return "SCRIPT=" + script + "; "
                + "if curl -fsSL --connect-timeout 15 '" + RELEASE_SCRIPT + "' -o \"$SCRIPT\" && chmod 700 \"$SCRIPT\"; then "
                + "if command -v systemd-run >/dev/null 2>&1 && [ -d /run/systemd/system ]; then "
                + "systemd-run --unit=" + unit + " --collect --property=Type=oneshot /bin/sh \"$SCRIPT\" -U >/dev/null 2>&1; "
                + "elif command -v setsid >/dev/null 2>&1; then setsid /bin/sh -c 'sleep 1; exec " + run + "' </dev/null >/dev/null 2>&1 & "
                + "else nohup /bin/sh -c 'sleep 1; exec " + run + "' </dev/null >/dev/null 2>&1 & fi; "
                + "echo FLUX_UPGRADE_STARTED; else echo FLUX_UPGRADE_FAILED; fi";
    }

    private void closeBootstrapSession(BootstrapSession bootstrap, String reason) {
        bootstrapSessions.remove(bootstrap.sessionId());
        bootstrapOutput.remove(bootstrap.sessionId());
        JSONObject close = new JSONObject();
        close.put("sessionId", bootstrap.sessionId());
        close.put("reason", reason);
        WebSocketServer.sendNodeEvent(bootstrap.nodeId(), "TerminalClose", close);
    }

    private String decodeTerminalOutput(String encoded) {
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    String upgradeMode(String version) {
        if (AgentVersionUtil.isAtLeast(version, SELF_UPDATE_MIN_VERSION)) return "self";
        if (AgentVersionUtil.isAtLeast(version, TERMINAL_BOOTSTRAP_MIN_VERSION)) return "terminal";
        return "manual";
    }

    private Node requireNode(Long nodeId) {
        Node node = nodeService.getById(nodeId);
        if (node == null) throw new IllegalArgumentException("节点不存在");
        return node;
    }

    private boolean hasActiveTask(Long nodeId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_upgrade_task WHERE node_id=? AND state IN (?,?,?,?,?,?,?)",
                Integer.class, nodeId, ACTIVE_STATES.get(0), ACTIVE_STATES.get(1), ACTIVE_STATES.get(2),
                ACTIVE_STATES.get(3), ACTIVE_STATES.get(4), ACTIVE_STATES.get(5), ACTIVE_STATES.get(6));
        return count != null && count > 0;
    }

    private boolean taskBelongsToNode(String taskId, Long nodeId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_upgrade_task WHERE task_id=? AND node_id=?",
                Integer.class, taskId, nodeId);
        return count != null && count > 0;
    }

    private void updateTask(String taskId, String state, String message, Long finishedAt) {
        jdbcTemplate.update("UPDATE agent_upgrade_task SET state=?,message=?,updated_at=?,finished_at=? WHERE task_id=?",
                state, abbreviate(message), System.currentTimeMillis(), finishedAt, taskId);
    }

    private String taskState(String taskId) {
        List<String> states = jdbcTemplate.query("SELECT state FROM agent_upgrade_task WHERE task_id=?",
                (rs, rowNum) -> rs.getString(1), taskId);
        return states.isEmpty() ? "" : states.get(0);
    }

    private Map<String, Object> statusForNode(Long nodeId) {
        Map<String, Object> status = getStatus(nodeId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) status.get("items");
        return items.isEmpty() ? Map.of() : items.get(0);
    }

    private Map<Long, Map<String, Object>> latestTasks(List<Long> nodeIds) {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        if (nodeIds.isEmpty()) return result;
        String placeholders = String.join(",", java.util.Collections.nCopies(nodeIds.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.task_id AS taskId,t.node_id AS nodeId,t.from_version AS fromVersion,t.target_version AS targetVersion,"
                        + "t.state,t.message,t.requested_at AS requestedAt,t.updated_at AS updatedAt,t.finished_at AS finishedAt "
                        + "FROM agent_upgrade_task t INNER JOIN (SELECT node_id,MAX(id) id FROM agent_upgrade_task WHERE node_id IN ("
                        + placeholders + ") GROUP BY node_id) latest ON latest.id=t.id", nodeIds.toArray());
        for (Map<String, Object> row : rows) result.put(((Number) row.get("nodeId")).longValue(), row);
        return result;
    }

    private Map<String, Object> latestActiveTask(Long nodeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT task_id AS taskId,target_version AS targetVersion,state FROM agent_upgrade_task "
                        + "WHERE node_id=? AND state IN (?,?,?,?,?,?,?) ORDER BY id DESC LIMIT 1",
                nodeId, ACTIVE_STATES.get(0), ACTIVE_STATES.get(1), ACTIVE_STATES.get(2),
                ACTIVE_STATES.get(3), ACTIVE_STATES.get(4), ACTIVE_STATES.get(5), ACTIVE_STATES.get(6));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private record BootstrapSession(String sessionId, String taskId, Long nodeId) {
    }
}
