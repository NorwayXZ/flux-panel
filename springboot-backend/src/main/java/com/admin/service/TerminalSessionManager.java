package com.admin.service;

import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TerminalSessionManager {
    public static final String MIN_AGENT_VERSION = "2.8.0";
    private static final long TICKET_TTL_MS = 60_000L;
    private static final long IDLE_TIMEOUT_MS = 10 * 60_000L;
    private static final long SESSION_TIMEOUT_MS = 60 * 60_000L;
    private static final int MAX_ACTIVE_SESSIONS = 3;

    private final NodeService nodeService;
    private final UserService userService;
    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, TerminalTicket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveTerminal> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> sendLocks = new ConcurrentHashMap<>();

    public TerminalSessionManager(NodeService nodeService, UserService userService, JdbcTemplate jdbcTemplate) {
        this.nodeService = nodeService;
        this.userService = userService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> setNodeEnabled(Long nodeId, boolean enabled) {
        User admin = requireAdmin();
        Node node = requireNode(nodeId);
        if (enabled && !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            throw new IllegalArgumentException("节点 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }
        Node update = new Node();
        update.setId(nodeId);
        update.setTerminalEnabled(enabled);
        update.setUpdatedTime(System.currentTimeMillis());
        if (!nodeService.updateById(update)) {
            throw new IllegalStateException("终端开关保存失败");
        }
        if (!enabled) {
            closeNodeSessions(nodeId, "管理员关闭了远程终端", true);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("enabled", enabled);
        result.put("operator", admin.getUser());
        return result;
    }

    public Map<String, Object> createTicket(Long nodeId, String sourceIp) {
        User admin = requireAdmin();
        Node node = requireNode(nodeId);
        if (!Boolean.TRUE.equals(node.getTerminalEnabled())) {
            throw new IllegalArgumentException("该节点尚未启用远程终端");
        }
        if (!WebSocketServer.isNodeOnline(nodeId)) {
            throw new IllegalArgumentException("节点当前离线");
        }
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            throw new IllegalArgumentException("节点 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }
        if (sessions.size() >= MAX_ACTIVE_SESSIONS) {
            throw new IllegalStateException("当前远程终端会话已达到上限");
        }
        if (sessions.values().stream().anyMatch(item -> Objects.equals(item.getTicket().getNodeId(), nodeId))) {
            throw new IllegalStateException("该节点已有远程终端会话");
        }

        long now = System.currentTimeMillis();
        String token = randomToken();
        String sessionId = randomToken().substring(0, 32);
        TerminalTicket ticket = new TerminalTicket(token, sessionId, nodeId, node.getName(),
                admin.getId().intValue(), admin.getUser(), sourceIp, now + TICKET_TTL_MS);
        tickets.put(token, ticket);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket", token);
        result.put("expiresAt", ticket.getExpiresAt());
        result.put("sessionId", sessionId);
        return result;
    }

    public TerminalTicket consumeTicket(String token) {
        if (StringUtils.isBlank(token)) return null;
        TerminalTicket ticket = tickets.remove(token);
        if (ticket == null || ticket.getExpiresAt() < System.currentTimeMillis()) return null;
        if (sessions.size() >= MAX_ACTIVE_SESSIONS) return null;
        if (sessions.values().stream().anyMatch(item -> Objects.equals(item.getTicket().getNodeId(), ticket.getNodeId()))) return null;
        return ticket;
    }

    public boolean openBrowserSession(TerminalTicket ticket, WebSocketSession browserSession) {
        if (ticket == null || browserSession == null || !browserSession.isOpen()) return false;
        long now = System.currentTimeMillis();
        ActiveTerminal active = new ActiveTerminal(ticket, browserSession, now, now, false);
        sessions.put(ticket.getSessionId(), active);
        try {
            insertAudit(ticket, now);
        } catch (Exception e) {
            sessions.remove(ticket.getSessionId());
            log.error("Creating terminal audit record failed", e);
            return false;
        }

        JSONObject data = new JSONObject();
        data.put("sessionId", ticket.getSessionId());
        data.put("cols", 120);
        data.put("rows", 32);
        if (!WebSocketServer.sendNodeEvent(ticket.getNodeId(), "TerminalOpen", data)) {
            closeSession(ticket.getSessionId(), "节点连接不可用", false, CloseStatus.SERVER_ERROR);
            return false;
        }
        return true;
    }

    public boolean handleAgentMessage(Long nodeId, JSONObject message) {
        String type = message.getString("type");
        if (type == null || !type.startsWith("Terminal")) return false;
        JSONObject data = message.getJSONObject("data");
        String sessionId = data == null ? null : data.getString("sessionId");
        ActiveTerminal active = sessions.get(sessionId);
        if (active == null || !Objects.equals(active.getTicket().getNodeId(), nodeId)) return true;
        active.setLastActivity(System.currentTimeMillis());

        if ("TerminalOpened".equals(type)) {
            active.setOpened(true);
            try {
                updateAudit(sessionId, "active", null, null);
            } catch (Exception e) {
                log.error("Activating terminal audit record failed for {}", sessionId, e);
                JSONObject error = new JSONObject();
                error.put("message", "终端审计不可用，会话已拒绝");
                sendBrowser(active, jsonMessage("error", error));
                closeSession(sessionId, "终端审计不可用", true, CloseStatus.SERVER_ERROR);
                return true;
            }
            sendBrowser(active, jsonMessage("ready", data));
        } else if ("TerminalOutput".equals(type)) {
            sendBrowser(active, jsonMessage("output", data));
        } else if ("TerminalClosed".equals(type)) {
            String reason = data.getString("reason");
            sendBrowser(active, jsonMessage("closed", data));
            closeSession(sessionId, StringUtils.defaultIfBlank(reason, "远程终端已结束"), false, CloseStatus.NORMAL);
        } else if ("TerminalError".equals(type)) {
            String reason = data.getString("message");
            sendBrowser(active, jsonMessage("error", data));
            closeSession(sessionId, StringUtils.defaultIfBlank(reason, "远程终端发生错误"), false, CloseStatus.SERVER_ERROR);
        }
        return true;
    }

    public void handleBrowserMessage(String sessionId, JSONObject message) {
        ActiveTerminal active = sessions.get(sessionId);
        if (active == null) return;
        active.setLastActivity(System.currentTimeMillis());
        String type = message.getString("type");
        JSONObject data = new JSONObject();
        data.put("sessionId", sessionId);
        if ("input".equals(type)) {
            String encoded = message.getString("data");
            if (encoded == null || encoded.length() > 90_000) return;
            data.put("data", encoded);
            WebSocketServer.sendNodeEvent(active.getTicket().getNodeId(), "TerminalInput", data);
        } else if ("resize".equals(type)) {
            int cols = Math.max(20, Math.min(400, message.getIntValue("cols")));
            int rows = Math.max(5, Math.min(160, message.getIntValue("rows")));
            data.put("cols", cols);
            data.put("rows", rows);
            WebSocketServer.sendNodeEvent(active.getTicket().getNodeId(), "TerminalResize", data);
        } else if ("close".equals(type)) {
            closeSession(sessionId, "管理员主动断开", true, CloseStatus.NORMAL);
        }
    }

    public void browserDisconnected(String sessionId) {
        closeSession(sessionId, "浏览器连接断开", true, CloseStatus.NORMAL);
    }

    public void closeNodeSessions(Long nodeId, String reason, boolean notifyNode) {
        List<String> ids = new ArrayList<>();
        sessions.forEach((id, active) -> {
            if (Objects.equals(active.getTicket().getNodeId(), nodeId)) ids.add(id);
        });
        ids.forEach(id -> closeSession(id, reason, notifyNode, CloseStatus.NORMAL));
    }

    public List<Map<String, Object>> listAudit(Long nodeId) {
        return jdbcTemplate.query("SELECT session_id, username, node_id, node_name, source_ip, status, close_reason, started_at, ended_at "
                        + "FROM terminal_session_audit WHERE (? IS NULL OR node_id = ?) ORDER BY id DESC LIMIT 30",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sessionId", rs.getString("session_id"));
                    item.put("username", rs.getString("username"));
                    item.put("nodeId", rs.getLong("node_id"));
                    item.put("nodeName", rs.getString("node_name"));
                    item.put("sourceIp", rs.getString("source_ip"));
                    item.put("status", rs.getString("status"));
                    item.put("closeReason", rs.getString("close_reason"));
                    item.put("startedAt", rs.getLong("started_at"));
                    long endedAt = rs.getLong("ended_at");
                    item.put("endedAt", rs.wasNull() ? null : endedAt);
                    return item;
                }, nodeId, nodeId);
    }

    @Scheduled(fixedDelay = 30_000L)
    public void cleanup() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(entry -> entry.getValue().getExpiresAt() < now);
        List<String> expired = new ArrayList<>();
        sessions.forEach((id, active) -> {
            if (now - active.getLastActivity() > IDLE_TIMEOUT_MS || now - active.getStartedAt() > SESSION_TIMEOUT_MS
                    || !WebSocketServer.isNodeOnline(active.getTicket().getNodeId())) {
                expired.add(id);
            }
        });
        expired.forEach(id -> closeSession(id, "会话超时或节点离线", true, CloseStatus.SESSION_NOT_RELIABLE));
    }

    private User requireAdmin() {
        Integer userId = JwtUtil.getUserIdFromToken();
        User user = userService.getById(userId);
        if (user == null || !Objects.equals(user.getRoleId(), 0) || !Objects.equals(user.getStatus(), 1)) {
            throw new SecurityException("仅管理员可以使用远程终端");
        }
        return user;
    }

    private Node requireNode(Long nodeId) {
        Node node = nodeService.getById(nodeId);
        if (node == null) throw new IllegalArgumentException("节点不存在");
        return node;
    }

    private void closeSession(String sessionId, String reason, boolean notifyNode, CloseStatus status) {
        ActiveTerminal active = sessions.remove(sessionId);
        if (active == null) return;
        if (notifyNode) {
            JSONObject data = new JSONObject();
            data.put("sessionId", sessionId);
            data.put("reason", reason);
            WebSocketServer.sendNodeEvent(active.getTicket().getNodeId(), "TerminalClose", data);
        }
        try {
            updateAudit(sessionId, "closed", reason, System.currentTimeMillis());
        } catch (Exception e) {
            log.error("Closing terminal audit record failed for {}", sessionId, e);
        }
        sendLocks.remove(active.getBrowserSession().getId());
        try {
            if (active.getBrowserSession().isOpen()) active.getBrowserSession().close(status);
        } catch (Exception e) {
            log.debug("Closing terminal browser session failed: {}", e.getMessage());
        }
    }

    private void sendBrowser(ActiveTerminal active, String payload) {
        WebSocketSession session = active.getBrowserSession();
        if (session == null || !session.isOpen()) return;
        Object lock = sendLocks.computeIfAbsent(session.getId(), ignored -> new Object());
        synchronized (lock) {
            try {
                if (session.isOpen()) session.sendMessage(new TextMessage(payload));
            } catch (Exception e) {
                closeSession(active.getTicket().getSessionId(), "浏览器发送失败", true, CloseStatus.SERVER_ERROR);
            }
        }
    }

    private String jsonMessage(String type, JSONObject data) {
        JSONObject message = new JSONObject();
        message.put("type", type);
        message.put("data", data == null ? Collections.emptyMap() : data);
        return message.toJSONString();
    }

    private void insertAudit(TerminalTicket ticket, long startedAt) {
        jdbcTemplate.update("INSERT INTO terminal_session_audit (session_id,user_id,username,node_id,node_name,source_ip,status,started_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)", ticket.getSessionId(), ticket.getUserId(), ticket.getUsername(),
                ticket.getNodeId(), ticket.getNodeName(), ticket.getSourceIp(), "connecting", startedAt);
    }

    private void updateAudit(String sessionId, String status, String reason, Long endedAt) {
        jdbcTemplate.update("UPDATE terminal_session_audit SET status=?, close_reason=?, ended_at=? WHERE session_id=?",
                status, reason, endedAt, sessionId);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(64);
        for (byte value : bytes) builder.append(String.format("%02x", value & 0xff));
        return builder.toString();
    }

    @Data
    @AllArgsConstructor
    public static class TerminalTicket {
        private String token;
        private String sessionId;
        private Long nodeId;
        private String nodeName;
        private Integer userId;
        private String username;
        private String sourceIp;
        private long expiresAt;
    }

    @Data
    @AllArgsConstructor
    private static class ActiveTerminal {
        private TerminalTicket ticket;
        private WebSocketSession browserSession;
        private long startedAt;
        private volatile long lastActivity;
        private volatile boolean opened;
    }

}
