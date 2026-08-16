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
    public static final String TARGET_VERSION = "2.51.13";
    private static final String INSTALLER_RELEASE = "2.51.23";
    public static final String TERMINAL_BOOTSTRAP_MIN_VERSION = "2.8.0";
    private static final String BATCH_MODE_PARALLEL = "parallel";
    private static final String BATCH_MODE_STAGED = "staged";
    private static final long TASK_TIMEOUT_MS = 5 * 60_000L;
    private static final String RELEASE_SCRIPT = "https://raw.githubusercontent.com/NorwayXZ/flux-panel/"
            + INSTALLER_RELEASE + "/install.sh";
    private static final List<String> ACTIVE_STATES = List.of(
            "queued", "bootstrapping", "accepted", "preflight", "downloading", "verified", "restarting", "installing");

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
        reconcileCompletedTasks(nodes);
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
        result.put("batch", latestBatch());
        return result;
    }

    public R start(Long nodeId) {
        Node node = requireNode(nodeId);
        return start(node, null, null, JwtUtil.getUserIdFromToken());
    }

    private R start(Node node, String batchId, Integer sequenceNo, Integer requestedBy) {
        Long nodeId = node.getId();
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
                        + "(task_id,batch_id,sequence_no,node_id,node_name,from_version,target_version,state,message,requested_by,requested_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                taskId, batchId, sequenceNo, node.getId(), node.getName(), node.getVersion(), TARGET_VERSION, "queued",
                batchId == null ? "等待节点接收升级任务" : "批量升级：等待节点接收任务", requestedBy, now, now);

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

    public synchronized R startBatch() {
        return startBatch(BATCH_MODE_PARALLEL);
    }

    public synchronized R startBatch(String requestedMode) {
        String batchMode = BATCH_MODE_STAGED.equalsIgnoreCase(Objects.toString(requestedMode, ""))
                ? BATCH_MODE_STAGED : BATCH_MODE_PARALLEL;
        Integer running = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_upgrade_batch WHERE state='running'", Integer.class);
        if (running != null && running > 0) {
            return R.err(409, "已有批量升级正在执行");
        }
        List<Node> eligible = new ArrayList<>();
        for (Node node : nodeService.list()) {
            if (eligible.size() >= 100 || !WebSocketServer.isNodeOnline(node.getId())
                    || AgentVersionUtil.isAtLeast(node.getVersion(), TARGET_VERSION)
                    || "manual".equals(upgradeMode(node.getVersion())) || hasActiveTask(node.getId())) {
                continue;
            }
            eligible.add(node);
        }
        if (eligible.isEmpty()) return R.err("没有可远程升级的在线节点");

        String batchId = UUID.randomUUID().toString();
        String nodeIds = eligible.stream().map(node -> node.getId().toString()).collect(java.util.stream.Collectors.joining(","));
        long now = System.currentTimeMillis();
        int requestedBy = JwtUtil.getUserIdFromToken();
        jdbcTemplate.update("INSERT INTO agent_upgrade_batch "
                        + "(batch_id,target_version,state,mode,node_ids,total_nodes,completed_nodes,message,requested_by,started_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                batchId, TARGET_VERSION, "running", batchMode, nodeIds, eligible.size(), 0,
                BATCH_MODE_PARALLEL.equals(batchMode) ? "正在向全部在线节点分发升级任务" : "正在升级首台试运行节点",
                requestedBy, now, now);
        if (BATCH_MODE_PARALLEL.equals(batchMode)) {
            dispatchParallelBatch(batchId, eligible, requestedBy);
        } else {
            dispatchNextBatchNode(batchId);
        }
        return R.ok(latestBatch());
    }

    public R manualCommand(Long nodeId) {
        requireNode(nodeId);
        return R.ok(manualCommand());
    }

    String manualCommand() {
        String script = "/tmp/cloudnest-agent-update-" + TARGET_VERSION + ".sh";
        String mirror = "https://ghfast.top/" + RELEASE_SCRIPT;
        return "(curl -fL --retry 3 --connect-timeout 15 " + shellQuote(RELEASE_SCRIPT)
                + " -o " + shellQuote(script)
                + " || curl -fL --retry 3 --connect-timeout 15 " + shellQuote(mirror)
                + " -o " + shellQuote(script) + ")"
                + " && chmod +x " + shellQuote(script)
                + " && if [ \"$(id -u)\" -eq 0 ]; then sh " + shellQuote(script) + " -U; "
                + "elif command -v sudo >/dev/null 2>&1; then sudo sh " + shellQuote(script) + " -U; "
                + "else echo '请切换到 root 用户后重新执行'; exit 1; fi";
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
            if (WebSocketServer.sendNodeEvent(nodeId, "TerminalInput", input)) {
                updateTask(bootstrap.taskId(), "restarting", "升级命令已提交，等待新版 Agent 重新连接或旧版自动恢复", null);
            } else {
                updateTask(bootstrap.taskId(), "failed", "无法向节点终端提交升级命令", System.currentTimeMillis());
            }
        } else if ("TerminalOutput".equals(type)) {
            String output = bootstrapOutput.merge(sessionId, decodeTerminalOutput(data.getString("data")),
                    (previous, current) -> {
                        String combined = previous + current;
                        return combined.length() <= 4096 ? combined : combined.substring(combined.length() - 4096);
                    });
            if (output.contains("FLUX_UPGRADE_FAILED")) {
                updateTask(bootstrap.taskId(), "failed", "升级助手执行失败，请查看节点日志 " + bootstrap.logPath(),
                        System.currentTimeMillis());
                closeBootstrapSession(bootstrap, "升级助手启动失败");
            } else if (output.contains("FLUX_UPGRADE_STARTED")) {
                updateTask(bootstrap.taskId(), "restarting",
                        "升级助手已启动，等待 Agent 重新上线；日志 " + bootstrap.logPath(), null);
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
        if (active == null) active = latestTask(nodeId);
        if (active == null) return;
        String taskId = Objects.toString(active.get("taskId"), "");
        String target = Objects.toString(active.get("targetVersion"), TARGET_VERSION);
        String state = Objects.toString(active.get("state"), "");
        if (!"success".equals(state) && AgentVersionUtil.isAtLeast(version, target)) {
            updateTask(taskId, "success", "Agent 已升级并重新上线", System.currentTimeMillis());
            String batchId = Objects.toString(active.get("batchId"), "");
            if (!batchId.isBlank()) refreshBatchProgress(batchId);
            bootstrapSessions.entrySet().removeIf(entry -> Objects.equals(entry.getValue().nodeId(), nodeId));
            bootstrapOutput.entrySet().removeIf(entry -> !bootstrapSessions.containsKey(entry.getKey()));
        }
    }

    @Scheduled(fixedDelay = 30_000L)
    public void expireTasks() {
        long cutoff = System.currentTimeMillis() - TASK_TIMEOUT_MS;
        List<Map<String, Object>> expired = jdbcTemplate.queryForList(
                "SELECT task_id AS taskId,node_id AS nodeId "
                        + "FROM agent_upgrade_task WHERE state IN (?,?,?,?,?,?,?,?) AND updated_at<?",
                ACTIVE_STATES.get(0), ACTIVE_STATES.get(1), ACTIVE_STATES.get(2), ACTIVE_STATES.get(3),
                ACTIVE_STATES.get(4), ACTIVE_STATES.get(5), ACTIVE_STATES.get(6), ACTIVE_STATES.get(7), cutoff);
        long now = System.currentTimeMillis();
        for (Map<String, Object> task : expired) {
            Long nodeId = ((Number) task.get("nodeId")).longValue();
            Node node = nodeService.getById(nodeId);
            String message;
            if (node != null && WebSocketServer.isNodeOnline(nodeId)) {
                String taskId = Objects.toString(task.get("taskId"), "");
                String logPath = taskId.length() >= 12
                        ? "/var/log/flux-agent-update-" + taskId.substring(0, 12) + ".log"
                        : "/var/log/flux-agent-update-*.log";
                message = "升级未生效，Agent 已在线但仍为 " + Objects.toString(node.getVersion(), "未知版本")
                        + "；可重试升级，任务日志 " + logPath;
            } else {
                String taskId = Objects.toString(task.get("taskId"), "");
                String logPath = taskId.length() >= 12
                        ? "/var/log/flux-agent-update-" + taskId.substring(0, 12) + ".log"
                        : "/var/log/flux-agent-update-*.log";
                message = "等待 Agent 重新上线超时；请检查节点服务和任务日志 " + logPath;
            }
            updateTask(Objects.toString(task.get("taskId"), ""), "timeout", message, now);
        }
    }

    @Scheduled(fixedDelay = 5_000L)
    public void advanceBatchUpgrades() {
        try {
            List<Map<String, Object>> batches = jdbcTemplate.queryForList(
                    "SELECT batch_id AS batchId,mode,current_node_id AS currentNodeId FROM agent_upgrade_batch WHERE state='running' ORDER BY id");
            for (Map<String, Object> batch : batches) {
                String batchId = Objects.toString(batch.get("batchId"), "");
                if (BATCH_MODE_PARALLEL.equals(Objects.toString(batch.get("mode"), BATCH_MODE_STAGED))) {
                    advanceParallelBatch(batchId);
                    continue;
                }
                Object currentNode = batch.get("currentNodeId");
                if (currentNode == null) {
                    dispatchNextBatchNode(batchId);
                    continue;
                }
                List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                        "SELECT state,message,node_name AS nodeName FROM agent_upgrade_task WHERE batch_id=? AND node_id=? ORDER BY id DESC LIMIT 1",
                        batchId, ((Number) currentNode).longValue());
                if (tasks.isEmpty()) {
                    pauseBatch(batchId, "当前节点缺少升级任务记录，批量升级已暂停");
                    continue;
                }
                String state = Objects.toString(tasks.get(0).get("state"), "");
                if (ACTIVE_STATES.contains(state)) continue;
                if (!"success".equals(state)) {
                    pauseBatch(batchId, Objects.toString(tasks.get(0).get("nodeName"), "节点")
                            + " 升级未通过（" + Objects.toString(tasks.get(0).get("message"), state) + "），后续节点未执行");
                    continue;
                }
                jdbcTemplate.update("UPDATE agent_upgrade_batch SET completed_nodes=completed_nodes+1,current_node_id=NULL,current_node_name=NULL,"
                        + "message='上一节点已确认重新上线，准备下一台',updated_at=? WHERE batch_id=? AND state='running'",
                        System.currentTimeMillis(), batchId);
                dispatchNextBatchNode(batchId);
            }
        } catch (Exception e) {
            log.warn("Advance staged Agent upgrades failed: {}", e.getMessage());
        }
    }

    private R startTerminalBootstrap(Node node, String taskId) {
        String sessionId = "agent-upgrade-" + taskId;
        String taskPrefix = taskId.substring(0, 12);
        String logPath = "/var/log/flux-agent-update-" + taskPrefix + ".log";
        bootstrapSessions.put(sessionId, new BootstrapSession(sessionId, taskId, node.getId(), logPath));
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
        String prefix = taskId.substring(0, 12);
        String unit = "flux-agent-bootstrap-" + prefix;
        String log = "/var/log/flux-agent-update-" + prefix + ".log";
        String result = "/tmp/flux-agent-update-" + prefix + ".result";
        String mirror = "https://ghfast.top/" + RELEASE_SCRIPT;
        String run = "FLUX_AGENT_UPDATE_TASK_ID=" + shellQuote(taskId) + " /bin/sh " + shellQuote(script) + " -U >>" + shellQuote(log)
                + " 2>&1; code=$?; printf '%s\\n' \"$code\" >" + shellQuote(result) + "; exit \"$code\"";
        return "SCRIPT=" + shellQuote(script) + "; RESULT=" + shellQuote(result) + "; LOG=" + shellQuote(log) + "; "
                + "rm -f \"$RESULT\"; "
                + "if (curl -fL --retry 3 --connect-timeout 15 " + shellQuote(RELEASE_SCRIPT) + " -o \"$SCRIPT\" "
                + "|| curl -fL --retry 3 --connect-timeout 15 " + shellQuote(mirror) + " -o \"$SCRIPT\") "
                + "&& chmod 700 \"$SCRIPT\"; then "
                + "if command -v systemd-run >/dev/null 2>&1 && [ -d /run/systemd/system ]; then "
                + "systemd-run --unit=" + unit + " --collect --property=Type=oneshot /bin/sh -c "
                + shellQuote(run) + " >/dev/null 2>&1; started=$?; "
                + "elif command -v setsid >/dev/null 2>&1; then setsid /bin/sh -c " + shellQuote("sleep 1; " + run)
                + " </dev/null >/dev/null 2>&1 & started=$?; "
                + "else nohup /bin/sh -c " + shellQuote("sleep 1; " + run)
                + " </dev/null >/dev/null 2>&1 & started=$?; fi; "
                + "if [ \"$started\" -ne 0 ]; then printf 'FLUX_%s\\n' 'UPGRADE_FAILED'; "
                + "else printf 'FLUX_%s\\n' 'UPGRADE_STARTED'; i=0; while [ \"$i\" -lt 150 ]; do "
                + "if [ -f \"$RESULT\" ]; then code=$(cat \"$RESULT\" 2>/dev/null || echo 1); "
                + "if [ \"$code\" = 0 ]; then printf 'FLUX_%s\\n' 'UPGRADE_FINISHED'; "
                + "else printf 'FLUX_%s\\n' 'UPGRADE_FAILED'; tail -n 20 \"$LOG\" 2>/dev/null; fi; break; fi; "
                + "i=$((i+1)); sleep 2; done; fi; else printf 'FLUX_%s\\n' 'UPGRADE_FAILED'; fi";
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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
        if (AgentVersionUtil.isAtLeast(version, TERMINAL_BOOTSTRAP_MIN_VERSION)) return "terminal";
        return "manual";
    }

    private Node requireNode(Long nodeId) {
        Node node = nodeService.getById(nodeId);
        if (node == null) throw new IllegalArgumentException("节点不存在");
        return node;
    }

    private boolean hasActiveTask(Long nodeId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_upgrade_task WHERE node_id=? AND state IN (?,?,?,?,?,?,?,?)",
                Integer.class, nodeId, ACTIVE_STATES.get(0), ACTIVE_STATES.get(1), ACTIVE_STATES.get(2),
                ACTIVE_STATES.get(3), ACTIVE_STATES.get(4), ACTIVE_STATES.get(5), ACTIVE_STATES.get(6), ACTIVE_STATES.get(7));
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

    private void reconcileCompletedTasks(List<Node> nodes) {
        try {
            Map<Long, Map<String, Object>> latest = latestTasks(nodes.stream().map(Node::getId).toList());
            long now = System.currentTimeMillis();
            for (Node node : nodes) {
                Map<String, Object> task = latest.get(node.getId());
                if (task == null || "success".equals(Objects.toString(task.get("state"), ""))) continue;
                String target = Objects.toString(task.get("targetVersion"), TARGET_VERSION);
                if (!AgentVersionUtil.isAtLeast(node.getVersion(), target)) continue;
                jdbcTemplate.update("UPDATE agent_upgrade_task SET state='success',message=?,updated_at=?,finished_at=? WHERE task_id=?",
                        "Agent 已上报目标版本，升级状态已自动校正", now, now, Objects.toString(task.get("taskId"), ""));
                String batchId = Objects.toString(task.get("batchId"), "");
                if (!batchId.isBlank()) refreshBatchProgress(batchId);
            }
        } catch (Exception e) {
            log.warn("Reconcile Agent upgrade status failed: {}", e.getMessage());
        }
    }

    private Map<Long, Map<String, Object>> latestTasks(List<Long> nodeIds) {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        if (nodeIds.isEmpty()) return result;
        String placeholders = String.join(",", java.util.Collections.nCopies(nodeIds.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.task_id AS taskId,t.batch_id AS batchId,t.node_id AS nodeId,t.from_version AS fromVersion,t.target_version AS targetVersion,"
                        + "t.state,t.message,t.requested_at AS requestedAt,t.updated_at AS updatedAt,t.finished_at AS finishedAt "
                        + "FROM agent_upgrade_task t INNER JOIN (SELECT node_id,MAX(id) id FROM agent_upgrade_task WHERE node_id IN ("
                        + placeholders + ") GROUP BY node_id) latest ON latest.id=t.id", nodeIds.toArray());
        for (Map<String, Object> row : rows) result.put(((Number) row.get("nodeId")).longValue(), row);
        return result;
    }

    private Map<String, Object> latestTask(Long nodeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT task_id AS taskId,batch_id AS batchId,target_version AS targetVersion,state "
                        + "FROM agent_upgrade_task WHERE node_id=? ORDER BY id DESC LIMIT 1", nodeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> latestActiveTask(Long nodeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT task_id AS taskId,batch_id AS batchId,target_version AS targetVersion,state FROM agent_upgrade_task "
                        + "WHERE node_id=? AND state IN (?,?,?,?,?,?,?,?) ORDER BY id DESC LIMIT 1",
                nodeId, ACTIVE_STATES.get(0), ACTIVE_STATES.get(1), ACTIVE_STATES.get(2),
                ACTIVE_STATES.get(3), ACTIVE_STATES.get(4), ACTIVE_STATES.get(5), ACTIVE_STATES.get(6), ACTIVE_STATES.get(7));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> latestBatch() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT batch_id AS batchId,target_version AS targetVersion,state,mode,total_nodes AS totalNodes,"
                        + "completed_nodes AS completedNodes,current_node_id AS currentNodeId,current_node_name AS currentNodeName,"
                        + "message,started_at AS startedAt,updated_at AS updatedAt,finished_at AS finishedAt "
                        + "FROM agent_upgrade_batch ORDER BY id DESC LIMIT 1");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private synchronized void dispatchNextBatchNode(String batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT node_ids AS nodeIds,total_nodes AS totalNodes,completed_nodes AS completedNodes,requested_by AS requestedBy "
                        + "FROM agent_upgrade_batch WHERE batch_id=? AND state='running' LIMIT 1", batchId);
        if (rows.isEmpty()) return;
        Map<String, Object> batch = rows.get(0);
        List<Long> nodeIds = new ArrayList<>();
        for (String value : Objects.toString(batch.get("nodeIds"), "").split(",")) {
            try { nodeIds.add(Long.parseLong(value.trim())); } catch (NumberFormatException ignored) { }
        }
        List<Long> completedOrStarted = jdbcTemplate.query(
                "SELECT node_id FROM agent_upgrade_task WHERE batch_id=? ORDER BY sequence_no",
                (rs, rowNum) -> rs.getLong(1), batchId);
        Long nextNodeId = nodeIds.stream().filter(id -> !completedOrStarted.contains(id)).findFirst().orElse(null);
        if (nextNodeId == null) {
            long now = System.currentTimeMillis();
            jdbcTemplate.update("UPDATE agent_upgrade_batch SET state='success',completed_nodes=total_nodes,current_node_id=NULL,current_node_name=NULL,"
                    + "message='全部节点已逐台升级并确认重新上线',updated_at=?,finished_at=? WHERE batch_id=? AND state='running'",
                    now, now, batchId);
            return;
        }
        Node node = nodeService.getById(nextNodeId);
        if (node == null || !WebSocketServer.isNodeOnline(nextNodeId)) {
            pauseBatch(batchId, (node == null ? "节点 " + nextNodeId : node.getName()) + " 当前离线，后续节点未执行");
            return;
        }
        int sequence = completedOrStarted.size() + 1;
        String stage = sequence == 1 ? "试运行" : "第 " + sequence + " 台";
        jdbcTemplate.update("UPDATE agent_upgrade_batch SET current_node_id=?,current_node_name=?,message=?,updated_at=? "
                        + "WHERE batch_id=? AND state='running'",
                node.getId(), node.getName(), "正在升级" + stage + "节点 " + node.getName(), System.currentTimeMillis(), batchId);
        R result = start(node, batchId, sequence, ((Number) batch.get("requestedBy")).intValue());
        if (result.getCode() != 0) {
            pauseBatch(batchId, node.getName() + " 无法开始升级：" + result.getMsg());
        }
    }

    private void dispatchParallelBatch(String batchId, List<Node> nodes, int requestedBy) {
        int sequence = 0;
        for (Node node : nodes) {
            sequence++;
            try {
                R result = start(node, batchId, sequence, requestedBy);
                if (result.getCode() != 0) {
                    recordBatchDispatchFailure(batchId, node, sequence, requestedBy, result.getMsg());
                }
            } catch (Exception e) {
                recordBatchDispatchFailure(batchId, node, sequence, requestedBy, e.getMessage());
            }
        }
        jdbcTemplate.update("UPDATE agent_upgrade_batch SET message=?,updated_at=? WHERE batch_id=? AND state='running'",
                "已向 " + nodes.size() + " 台节点分发任务，正在等待各节点独立升级和回连", System.currentTimeMillis(), batchId);
    }

    private void recordBatchDispatchFailure(String batchId, Node node, int sequence, int requestedBy, String message) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_upgrade_task WHERE batch_id=? AND node_id=?", Integer.class, batchId, node.getId());
        if (existing != null && existing > 0) return;
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO agent_upgrade_task "
                        + "(task_id,batch_id,sequence_no,node_id,node_name,from_version,target_version,state,message,requested_by,requested_at,updated_at,finished_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), batchId, sequence, node.getId(), node.getName(), node.getVersion(), TARGET_VERSION,
                "failed", abbreviate("任务分发失败：" + Objects.toString(message, "未知错误")), requestedBy, now, now, now);
    }

    private void advanceParallelBatch(String batchId) {
        refreshBatchProgress(batchId);
    }

    private void refreshBatchProgress(String batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT total_nodes AS totalNodes,state FROM agent_upgrade_batch WHERE batch_id=? LIMIT 1", batchId);
        if (rows.isEmpty()) return;
        String currentState = Objects.toString(rows.get(0).get("state"), "");
        if ("paused".equals(currentState)) return;
        int total = ((Number) rows.get(0).get("totalNodes")).intValue();
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_upgrade_task WHERE batch_id=? AND state IN (?,?,?,?,?,?,?,?)", Integer.class,
                batchId, ACTIVE_STATES.get(0), ACTIVE_STATES.get(1), ACTIVE_STATES.get(2), ACTIVE_STATES.get(3),
                ACTIVE_STATES.get(4), ACTIVE_STATES.get(5), ACTIVE_STATES.get(6), ACTIVE_STATES.get(7));
        Integer taskCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_upgrade_task WHERE batch_id=?", Integer.class, batchId);
        Integer successes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_upgrade_task WHERE batch_id=? AND state='success'", Integer.class, batchId);
        int completed = Math.max(0, Objects.requireNonNullElse(taskCount, 0) - Objects.requireNonNullElse(active, 0));
        if (Objects.requireNonNullElse(taskCount, 0) < total || Objects.requireNonNullElse(active, 0) > 0) {
            jdbcTemplate.update("UPDATE agent_upgrade_batch SET completed_nodes=?,message=?,updated_at=? WHERE batch_id=? AND state='running'",
                    completed, "并发升级进行中：已完成 " + completed + "/" + total + "，成功 "
                            + Objects.requireNonNullElse(successes, 0), System.currentTimeMillis(), batchId);
            return;
        }

        int successCount = Objects.requireNonNullElse(successes, 0);
        int failureCount = total - successCount;
        long now = System.currentTimeMillis();
        String state = failureCount == 0 ? "success" : "completed_with_errors";
        String message = failureCount == 0
                ? "全部 " + total + " 台节点升级成功并重新上线"
                : "批量升级已结束：成功 " + successCount + " 台，失败或回退 " + failureCount + " 台；可单独重试失败节点";
        jdbcTemplate.update("UPDATE agent_upgrade_batch SET state=?,completed_nodes=?,message=?,updated_at=?,finished_at=? "
                        + "WHERE batch_id=? AND state IN ('running','success','completed_with_errors')",
                state, total, message, now, now, batchId);
    }

    private void pauseBatch(String batchId, String message) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE agent_upgrade_batch SET state='paused',message=?,updated_at=?,finished_at=? "
                + "WHERE batch_id=? AND state='running'", abbreviateLong(message), now, now, batchId);
    }

    private String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private String abbreviateLong(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record BootstrapSession(String sessionId, String taskId, Long nodeId, String logPath) {
    }
}
