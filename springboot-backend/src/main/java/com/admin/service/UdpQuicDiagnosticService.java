package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.UdpQuicDiagnosticTaskDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Slf4j
@Service
public class UdpQuicDiagnosticService {
    public static final String MIN_AGENT_VERSION = "2.49.0";
    private static final Pattern HOST = Pattern.compile("^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\\.(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*$");
    private final JdbcTemplate jdbcTemplate;
    private final NodeMapper nodeMapper;
    private final AtomicInteger active = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "udp-quic-diagnostic");
        thread.setDaemon(true);
        return thread;
    });

    public UdpQuicDiagnosticService(JdbcTemplate jdbcTemplate, NodeMapper nodeMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.nodeMapper = nodeMapper;
    }

    public R overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimumAgentVersion", MIN_AGENT_VERSION);
        List<Map<String, Object>> nodes = jdbcTemplate.queryForList("SELECT id,name,ip,server_ip AS serverIp,status,version FROM node ORDER BY status DESC,id DESC");
        nodes.forEach(node -> node.put("compatible", AgentVersionUtil.isAtLeast(Objects.toString(node.get("version"), ""), MIN_AGENT_VERSION)));
        result.put("nodes", nodes);
        List<Map<String, Object>> tasks = jdbcTemplate.queryForList("SELECT t.id,t.name,t.source_node_id AS sourceNodeId,t.target_type AS targetType,"
                + "t.target_node_id AS targetNodeId,t.target_host AS targetHost,t.port,t.mode,t.server_name AS serverName,t.ip_family AS ipFamily,"
                + "t.sample_count AS sampleCount,t.timeout_ms AS timeoutMs,t.packet_size AS packetSize,t.idle_timeout_seconds AS idleTimeoutSeconds,"
                + "t.alpn,t.verify_certificate AS verifyCertificate,t.retention_days AS retentionDays,t.running,t.last_status AS lastStatus,"
                + "t.last_error AS lastError,t.last_run_at AS lastRunAt,t.created_time AS createdTime,t.updated_time AS updatedTime,"
                + "COALESCE(s.name,'已删除节点') AS sourceNodeName,s.status AS sourceNodeStatus,s.version AS sourceNodeVersion,"
                + "COALESCE(n.name,'自定义目标') AS targetNodeName,n.status AS targetNodeStatus,n.version AS targetNodeVersion,"
                + "r.status AS latestRunStatus,r.resolved_address AS resolvedAddress,r.success_count AS successCount,r.sample_count AS latestSampleCount,"
                + "r.failure_rate AS failureRate,r.packet_loss_percent AS packetLossPercent,r.rtt_avg_ms AS rttAvgMs,r.jitter_ms AS jitterMs,"
                + "r.nat_idle_alive AS natIdleAlive,r.quic_handshake_avg_ms AS quicHandshakeAvgMs,r.diagnosis,r.started_at AS latestStartedAt "
                + "FROM udp_quic_diagnostic_task t LEFT JOIN node s ON s.id=t.source_node_id LEFT JOIN node n ON n.id=t.target_node_id "
                + "LEFT JOIN udp_quic_diagnostic_run r ON r.id=(SELECT MAX(r2.id) FROM udp_quic_diagnostic_run r2 WHERE r2.task_id=t.id) "
                + "ORDER BY t.created_time DESC");
        result.put("tasks", tasks);
        result.put("summary", Map.of("total", tasks.size(), "running", tasks.stream().filter(row -> truth(row.get("running"))).count(),
                "success", tasks.stream().filter(row -> "success".equals(row.get("latestRunStatus"))).count(),
                "degraded", tasks.stream().filter(row -> "partial".equals(row.get("latestRunStatus"))).count(),
                "failed", tasks.stream().filter(row -> "failed".equals(row.get("latestRunStatus"))).count()));
        return R.ok(result);
    }

    public R save(UdpQuicDiagnosticTaskDto dto) {
        try {
            normalizeAndValidate(dto);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        long now = System.currentTimeMillis();
        if (dto.getId() == null) {
            jdbcTemplate.update("INSERT INTO udp_quic_diagnostic_task (name,source_node_id,target_type,target_node_id,target_host,port,mode,server_name,"
                            + "ip_family,sample_count,timeout_ms,packet_size,idle_timeout_seconds,alpn,verify_certificate,retention_days,running,last_status,created_time,updated_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,'pending',?,?)",
                    dto.getName().trim(), dto.getSourceNodeId(), dto.getTargetType(), dto.getTargetNodeId(), blankToNull(dto.getTargetHost()), dto.getPort(),
                    dto.getMode(), blankToNull(dto.getServerName()), dto.getIpFamily(), dto.getSampleCount(), dto.getTimeoutMs(), dto.getPacketSize(),
                    dto.getIdleTimeoutSeconds(), blankToNull(dto.getAlpn()), truth(dto.getVerifyCertificate()) ? 1 : 0, dto.getRetentionDays(), now, now);
        } else {
            int changed = jdbcTemplate.update("UPDATE udp_quic_diagnostic_task SET name=?,source_node_id=?,target_type=?,target_node_id=?,target_host=?,port=?,mode=?,"
                            + "server_name=?,ip_family=?,sample_count=?,timeout_ms=?,packet_size=?,idle_timeout_seconds=?,alpn=?,verify_certificate=?,retention_days=?,updated_time=? "
                            + "WHERE id=? AND running=0",
                    dto.getName().trim(), dto.getSourceNodeId(), dto.getTargetType(), dto.getTargetNodeId(), blankToNull(dto.getTargetHost()), dto.getPort(),
                    dto.getMode(), blankToNull(dto.getServerName()), dto.getIpFamily(), dto.getSampleCount(), dto.getTimeoutMs(), dto.getPacketSize(),
                    dto.getIdleTimeoutSeconds(), blankToNull(dto.getAlpn()), truth(dto.getVerifyCertificate()) ? 1 : 0, dto.getRetentionDays(), now, dto.getId());
            if (changed == 0) return R.err("诊断任务不存在或正在运行");
        }
        return overview();
    }

    public R runNow(Long id) {
        Map<String, Object> task = one("SELECT * FROM udp_quic_diagnostic_task WHERE id=?", id);
        if (task == null) return R.err("诊断任务不存在");
        try {
            validateRunnable(task);
        } catch (IllegalStateException e) {
            return R.err(e.getMessage());
        }
        if (active.get() >= 3) return R.err("已有 3 项 UDP / QUIC 诊断在运行，请稍后重试");
        long now = System.currentTimeMillis();
        if (jdbcTemplate.update("UPDATE udp_quic_diagnostic_task SET running=1,last_status='running',last_error=NULL,updated_time=? WHERE id=? AND running=0", now, id) != 1) {
            return R.err("该诊断任务正在运行");
        }
        active.incrementAndGet();
        executor.submit(() -> {
            try {
                execute(id);
            } finally {
                active.decrementAndGet();
            }
        });
        return R.ok(Map.of("id", id, "state", "running", "message", "UDP / QUIC 诊断已开始"));
    }

    @Transactional
    public R delete(Long id) {
        Boolean running = nullableBoolean("SELECT running FROM udp_quic_diagnostic_task WHERE id=?", id);
        if (running == null) return R.err("诊断任务不存在");
        if (running) return R.err("诊断任务正在运行，不能删除");
        jdbcTemplate.update("DELETE FROM udp_quic_diagnostic_run WHERE task_id=?", id);
        jdbcTemplate.update("DELETE FROM udp_quic_diagnostic_task WHERE id=?", id);
        return overview();
    }

    public R detail(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,status,target_host AS targetHost,resolved_address AS resolvedAddress,ip_family AS ipFamily,"
                + "port,mode,packet_size AS packetSize,sample_count AS sampleCount,success_count AS successCount,failure_rate AS failureRate,"
                + "packet_loss_percent AS packetLossPercent,rtt_min_ms AS rttMinMs,rtt_avg_ms AS rttAvgMs,rtt_max_ms AS rttMaxMs,jitter_ms AS jitterMs,"
                + "nat_idle_seconds AS natIdleSeconds,nat_idle_alive AS natIdleAlive,quic_handshake_avg_ms AS quicHandshakeAvgMs,alpn,diagnosis,error,"
                + "samples_json AS samplesJson,started_at AS startedAt,finished_at AS finishedAt FROM udp_quic_diagnostic_run WHERE task_id=? ORDER BY started_at DESC LIMIT 200", id);
        return R.ok(Map.of("taskId", id, "runs", rows));
    }

    void execute(Long id) {
        long started = System.currentTimeMillis();
        Map<String, Object> task = one("SELECT * FROM udp_quic_diagnostic_task WHERE id=?", id);
        if (task == null) return;
        String sessionId = "uq-" + id + "-" + started;
        Long targetNodeId = longOrNull(task.get("target_node_id"));
        boolean prepared = false;
        try {
            Long sourceId = longNumber(task.get("source_node_id"));
            Node source = requireOnlineNode(sourceId, "执行");
            String mode = Objects.toString(task.get("mode"), "udp_echo").toLowerCase(Locale.ROOT);
            String targetHost = targetHost(task);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("mode", mode);
            payload.put("targetHost", targetHost);
            payload.put("port", task.get("port"));
            payload.put("serverName", task.get("server_name"));
            payload.put("ipFamily", task.get("ip_family"));
            payload.put("count", task.get("sample_count"));
            payload.put("timeoutMs", task.get("timeout_ms"));
            payload.put("packetSize", task.get("packet_size"));
            payload.put("idleTimeoutSeconds", task.get("idle_timeout_seconds"));
            payload.put("alpn", task.get("alpn"));
            payload.put("verifyCertificate", truth(task.get("verify_certificate")));
            if ("udp_echo".equals(mode)) {
                Node target = requireOnlineNode(targetNodeId, "目标");
                targetHost = firstNonBlank(target.getServerIp(), target.getIp());
                if (targetHost == null) throw new IllegalStateException("目标节点没有可连接地址");
                long ttl = Math.min(180, Math.max(20, intNumber(task.get("sample_count")) * intNumber(task.get("timeout_ms")) / 1000L
                        + intNumber(task.get("idle_timeout_seconds")) + 30));
                GostDto prepare = WebSocketServer.send_msg(target.getId(), Map.of(
                        "sessionId", sessionId,
                        "listenPort", task.get("port"),
                        "ttlSeconds", ttl,
                        "packetSize", task.get("packet_size")
                ), "UdpQuicPrepare", 12);
                requireOK(prepare, "目标节点未能打开 UDP 诊断端口");
                prepared = true;
                JSONObject preparedData = json(prepare.getData());
                payload.put("targetHost", targetHost);
                payload.put("port", preparedData.getIntValue("port"));
                payload.put("token", preparedData.getString("token"));
            }
            long timeoutSeconds = Math.min(240, Math.max(15, intNumber(task.get("sample_count")) * intNumber(task.get("timeout_ms")) / 1000L
                    + intNumber(task.get("idle_timeout_seconds")) + 20));
            GostDto response = WebSocketServer.send_msg(source.getId(), payload, "UdpQuicRun", timeoutSeconds);
            requireOK(response, "执行节点未完成 UDP / QUIC 诊断");
            JSONObject data = json(response.getData());
            storeRun(id, task, data, started);
            String status = statusOf(data);
            String diagnosis = diagnosisOf(data, mode);
            jdbcTemplate.update("UPDATE udp_quic_diagnostic_task SET running=0,last_run_at=?,last_status=?,last_error=?,updated_time=? WHERE id=?",
                    System.currentTimeMillis(), status, "success".equals(status) ? null : diagnosis, System.currentTimeMillis(), id);
        } catch (Exception e) {
            String error = concise(e.getMessage());
            storeFailure(id, task, started, error);
            jdbcTemplate.update("UPDATE udp_quic_diagnostic_task SET running=0,last_run_at=?,last_status='failed',last_error=?,updated_time=? WHERE id=?",
                    System.currentTimeMillis(), error, System.currentTimeMillis(), id);
        } finally {
            if (prepared && targetNodeId != null) WebSocketServer.send_msg(targetNodeId, Map.of("sessionId", sessionId), "UdpQuicStop", 5);
        }
    }

    private Node requireOnlineNode(Long id, String label) {
        if (id == null) throw new IllegalStateException(label + "节点不存在");
        Node node = nodeMapper.selectById(id);
        if (node == null) throw new IllegalStateException(label + "节点不存在");
        if (!WebSocketServer.isNodeOnline(id)) throw new IllegalStateException(label + "节点离线");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) throw new IllegalStateException(label + "节点 Agent 需要升级到 " + MIN_AGENT_VERSION);
        return node;
    }

    private void validateRunnable(Map<String, Object> task) {
        requireOnlineNode(longNumber(task.get("source_node_id")), "执行");
        String mode = Objects.toString(task.get("mode"), "udp_echo").toLowerCase(Locale.ROOT);
        if ("udp_echo".equals(mode)) {
            requireOnlineNode(longOrNull(task.get("target_node_id")), "目标");
        } else {
            targetHost(task);
        }
    }

    private void normalizeAndValidate(UdpQuicDiagnosticTaskDto dto) {
        dto.setMode(dto.getMode().toLowerCase(Locale.ROOT));
        dto.setTargetType(dto.getTargetType() == null ? "node" : dto.getTargetType().toLowerCase(Locale.ROOT));
        dto.setIpFamily(dto.getIpFamily() == null ? "auto" : dto.getIpFamily().toLowerCase(Locale.ROOT));
        if (nodeMapper.selectById(dto.getSourceNodeId()) == null) throw new IllegalArgumentException("执行节点不存在");
        if ("udp_echo".equals(dto.getMode())) {
            if (!"node".equals(dto.getTargetType()) || dto.getTargetNodeId() == null) throw new IllegalArgumentException("UDP Echo 诊断必须选择目标 Agent");
            if (nodeMapper.selectById(dto.getTargetNodeId()) == null) throw new IllegalArgumentException("目标节点不存在");
        } else if ("node".equals(dto.getTargetType())) {
            if (dto.getTargetNodeId() == null || nodeMapper.selectById(dto.getTargetNodeId()) == null) throw new IllegalArgumentException("目标节点不存在");
        } else {
            String target = Objects.toString(dto.getTargetHost(), "").trim();
            if (!validHostOrIp(target)) throw new IllegalArgumentException("请填写有效的 QUIC 目标域名或 IP");
            dto.setTargetHost(target);
        }
        if (dto.getServerName() != null && !dto.getServerName().isBlank() && !validHostOrIp(dto.getServerName().trim())) {
            throw new IllegalArgumentException("SNI / Server Name 不合法");
        }
        if (dto.getAlpn() != null && dto.getAlpn().contains("\n")) throw new IllegalArgumentException("ALPN 不合法");
    }

    private String targetHost(Map<String, Object> task) {
        if ("node".equals(task.get("target_type")) && task.get("target_node_id") != null) {
            Node target = nodeMapper.selectById(longNumber(task.get("target_node_id")));
            if (target == null) throw new IllegalStateException("目标节点不存在");
            String host = firstNonBlank(target.getServerIp(), target.getIp());
            if (host == null) throw new IllegalStateException("目标节点没有可连接地址");
            return host;
        }
        String host = Objects.toString(task.get("target_host"), "").trim();
        if (!validHostOrIp(host)) throw new IllegalStateException("目标地址不合法");
        return host;
    }

    private void storeRun(Long id, Map<String, Object> task, JSONObject data, long started) {
        String mode = Objects.toString(task.get("mode"), "udp_echo").toLowerCase(Locale.ROOT);
        JSONArray samples = data.getJSONArray("samples");
        int sampleCount = samples == null ? data.getIntValue("successCount") : samples.size();
        String status = statusOf(data);
        String diagnosis = diagnosisOf(data, mode);
        jdbcTemplate.update("INSERT INTO udp_quic_diagnostic_run (task_id,status,source_node_id,target_node_id,target_host,resolved_address,ip_family,port,mode,"
                        + "packet_size,sample_count,success_count,failure_rate,packet_loss_percent,rtt_min_ms,rtt_avg_ms,rtt_max_ms,jitter_ms,nat_idle_seconds,"
                        + "nat_idle_alive,quic_handshake_avg_ms,alpn,diagnosis,error,samples_json,started_at,finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, status, task.get("source_node_id"), task.get("target_node_id"), data.getString("targetHost"), data.getString("resolvedAddress"),
                data.getString("ipFamily"), data.getIntValue("port"), mode, data.getIntValue("packetSize"), sampleCount, data.getIntValue("successCount"),
                data.getDoubleValue("failureRate"), data.getDoubleValue("packetLossPercent"), numberOrNull(data.get("rttMinMs")), numberOrNull(data.get("rttAvgMs")),
                numberOrNull(data.get("rttMaxMs")), numberOrNull(data.get("jitterMs")), numberOrNull(data.get("natIdleSeconds")), nullableBooleanInt(data.get("natIdleAlive")),
                numberOrNull(data.get("quicHandshakeAvgMs")), data.getString("alpn"), diagnosis, concise(data.getString("error")),
                samples == null ? null : samples.toJSONString(), started, System.currentTimeMillis());
    }

    private void storeFailure(Long id, Map<String, Object> task, long started, String error) {
        try {
            jdbcTemplate.update("INSERT INTO udp_quic_diagnostic_run (task_id,status,source_node_id,target_node_id,target_host,ip_family,port,mode,"
                            + "sample_count,success_count,failure_rate,packet_loss_percent,diagnosis,error,started_at,finished_at) VALUES (?,?,?,?,?,?,?,?,0,0,100,100,?,?,?,?)",
                    id, "failed", task.get("source_node_id"), task.get("target_node_id"), safeTargetHost(task), Objects.toString(task.get("ip_family"), "auto"),
                    task.get("port"), task.get("mode"), "诊断执行失败：" + error, error, started, System.currentTimeMillis());
        } catch (Exception storeError) {
            log.warn("UDP / QUIC diagnostic failure result could not be stored: {}", storeError.getMessage());
        }
    }

    private String statusOf(JSONObject data) {
        int sampleCount = data.getJSONArray("samples") == null ? 0 : data.getJSONArray("samples").size();
        int success = data.getIntValue("successCount");
        if (success <= 0) return "failed";
        if (data.containsKey("natIdleAlive") && !data.getBooleanValue("natIdleAlive")) return "partial";
        return success >= sampleCount ? "success" : "partial";
    }

    private String diagnosisOf(JSONObject data, String mode) {
        int success = data.getIntValue("successCount");
        double failureRate = data.getDoubleValue("failureRate");
        double jitter = data.getDoubleValue("jitterMs");
        if (success <= 0) {
            String error = concise(data.getString("error"));
            return "quic".equals(mode)
                    ? "QUIC 握手失败：检查 UDP 端口、安全组、防火墙、SNI/ALPN 和服务是否真的支持 QUIC。" + (error == null ? "" : " 原因：" + error)
                    : "UDP 无回包：检查目标节点 UDP 端口、安全组、防火墙或运营商是否限制 UDP。" + (error == null ? "" : " 原因：" + error);
        }
        if (data.containsKey("natIdleAlive") && !data.getBooleanValue("natIdleAlive")) {
            return "UDP 连通，但 NAT 空闲 " + data.getIntValue("natIdleSeconds") + " 秒后失效，建议降低 keepalive 间隔或改用 TCP 回退。";
        }
        if (failureRate >= 20 || jitter >= 30) {
            return "UDP 可用但质量不稳：丢包 " + format(failureRate) + "%，抖动 " + format(jitter) + " ms，建议检查线路拥塞或启用 TCP 回退。";
        }
        return "UDP / QUIC 状态正常。";
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void recoverInterrupted() {
        try {
            jdbcTemplate.update("UPDATE udp_quic_diagnostic_task SET running=0,last_status='failed',last_error='上次诊断因面板重启或等待超时而中断',updated_time=? WHERE running=1 AND updated_time<?",
                    System.currentTimeMillis(), System.currentTimeMillis() - 300_000);
        } catch (Exception e) {
            log.debug("UDP / QUIC interrupted task recovery skipped: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 55 3 * * ?")
    public void cleanup() {
        try {
            jdbcTemplate.update("DELETE r FROM udp_quic_diagnostic_run r JOIN udp_quic_diagnostic_task t ON t.id=r.task_id WHERE r.started_at < ? - t.retention_days*86400000",
                    System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("UDP / QUIC history cleanup skipped: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Boolean nullableBoolean(String sql, Object... args) {
        List<Boolean> rows = jdbcTemplate.query(sql, (rs, row) -> rs.getBoolean(1), args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static JSONObject json(Object value) {
        return value instanceof JSONObject ? (JSONObject) value : JSONObject.parseObject(JSONObject.toJSONString(value));
    }

    private static void requireOK(GostDto result, String prefix) {
        if (result == null || !"OK".equals(result.getMsg()) || result.getData() == null) throw new IllegalStateException(prefix + "：" + (result == null ? "Agent 无响应" : result.getMsg()));
    }

    private static boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number && ((Number) value).intValue() == 1);
    }

    private static int intNumber(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static Long longNumber(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(String.valueOf(value));
    }

    private static Long longOrNull(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    private static Object numberOrNull(Object value) {
        return value == null ? null : value;
    }

    private static Integer nullableBooleanInt(Object value) {
        if (value == null) return null;
        return truth(value) ? 1 : 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private static String safeTargetHost(Map<String, Object> task) {
        String host = Objects.toString(task.get("target_host"), "").trim();
        return host.isEmpty() ? "unknown" : host;
    }

    private static String concise(String value) {
        if (value == null || value.isBlank()) return null;
        value = value.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static boolean validHostOrIp(String value) {
        if (value == null || value.isBlank()) return false;
        if (value.contains(":") && value.indexOf(':') != value.lastIndexOf(':')) return value.length() <= 45;
        return HOST.matcher(value).matches();
    }
}
