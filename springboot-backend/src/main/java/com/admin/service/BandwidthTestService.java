package com.admin.service;

import com.admin.common.dto.BandwidthTestTaskDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
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

@Slf4j
@Service
public class BandwidthTestService {
    public static final String MIN_AGENT_VERSION = "2.44.1";
    private static final long MAXIMUM_BYTES = 2L * 1024 * 1024 * 1024;
    private final JdbcTemplate jdbcTemplate;
    private final NodeMapper nodeMapper;
    private final AtomicInteger active = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "bandwidth-test"); thread.setDaemon(true); return thread;
    });

    public BandwidthTestService(JdbcTemplate jdbcTemplate, NodeMapper nodeMapper) {
        this.jdbcTemplate = jdbcTemplate; this.nodeMapper = nodeMapper;
    }

    public R overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimumAgentVersion", MIN_AGENT_VERSION);
        result.put("nodes", jdbcTemplate.queryForList("SELECT id,name,ip,server_ip AS serverIp,status,version FROM node ORDER BY status DESC,id DESC"));
        List<Map<String, Object>> tasks = jdbcTemplate.queryForList("SELECT b.id,b.name,b.source_node_id AS sourceNodeId,b.target_node_id AS targetNodeId,"
                + "b.listen_port AS listenPort,b.protocol,b.direction,b.streams,b.duration_seconds AS durationSeconds,b.maximum_megabytes AS maximumMegabytes,"
                + "b.retention_days AS retentionDays,b.running,b.last_status AS lastStatus,b.last_error AS lastError,b.last_run_at AS lastRunAt,"
                + "b.created_time AS createdTime,b.updated_time AS updatedTime,COALESCE(s.name,'已删除节点') AS sourceNodeName,s.status AS sourceNodeStatus,"
                + "s.version AS sourceNodeVersion,COALESCE(t.name,'已删除节点') AS targetNodeName,t.status AS targetNodeStatus,t.version AS targetNodeVersion,"
                + "r.upload_mbps AS uploadMbps,r.download_mbps AS downloadMbps,r.total_mbps AS totalMbps,r.duration_ms AS latestDurationMs,"
                + "r.successful_streams AS successfulStreams,r.failed_streams AS failedStreams,r.rtt_ms AS rttMs,r.retransmits,"
                + "r.retransmission_rate AS retransmissionRate,r.packets_sent AS packetsSent,r.packets_received AS packetsReceived,"
                + "r.packets_lost AS packetsLost,r.packet_loss_percent AS packetLossPercent,r.jitter_ms AS jitterMs,"
                + "r.out_of_order_packets AS outOfOrderPackets,r.started_at AS latestStartedAt "
                + "FROM bandwidth_test_task b LEFT JOIN node s ON s.id=b.source_node_id LEFT JOIN node t ON t.id=b.target_node_id "
                + "LEFT JOIN bandwidth_test_run r ON r.id=(SELECT MAX(r2.id) FROM bandwidth_test_run r2 WHERE r2.task_id=b.id) ORDER BY b.created_time DESC");
        result.put("tasks", tasks);
        result.put("summary", Map.of("total", tasks.size(), "running", tasks.stream().filter(row -> truth(row.get("running"))).count(),
                "success", tasks.stream().filter(row -> "success".equals(row.get("lastStatus"))).count(),
                "failed", tasks.stream().filter(row -> "failed".equals(row.get("lastStatus"))).count(),
                "peakMbps", tasks.stream().map(row -> number(row.get("totalMbps"))).mapToDouble(Double::doubleValue).max().orElse(0)));
        return R.ok(result);
    }

    public R save(BandwidthTestTaskDto dto) {
        try { validate(dto); } catch (IllegalArgumentException e) { return R.err(e.getMessage()); }
        long now = System.currentTimeMillis();
        if (dto.getId() == null) {
            jdbcTemplate.update("INSERT INTO bandwidth_test_task (name,source_node_id,target_node_id,listen_port,protocol,direction,streams,duration_seconds,maximum_megabytes,retention_days,running,last_status,created_time,updated_time) VALUES (?,?,?,?,?,?,?,?,?,?,0,'pending',?,?)",
                    dto.getName().trim(), dto.getSourceNodeId(), dto.getTargetNodeId(), dto.getListenPort(), dto.getProtocol(), dto.getDirection(), dto.getStreams(),
                    dto.getDurationSeconds(), dto.getMaximumMegabytes(), dto.getRetentionDays(), now, now);
        } else {
            int changed = jdbcTemplate.update("UPDATE bandwidth_test_task SET name=?,source_node_id=?,target_node_id=?,listen_port=?,protocol=?,direction=?,streams=?,duration_seconds=?,maximum_megabytes=?,retention_days=?,updated_time=? WHERE id=? AND running=0",
                    dto.getName().trim(), dto.getSourceNodeId(), dto.getTargetNodeId(), dto.getListenPort(), dto.getProtocol(), dto.getDirection(), dto.getStreams(),
                    dto.getDurationSeconds(), dto.getMaximumMegabytes(), dto.getRetentionDays(), now, dto.getId());
            if (changed == 0) return R.err("带宽任务不存在或正在运行");
        }
        return overview();
    }

    public R runNow(Long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bandwidth_test_task WHERE id=?", Integer.class, id);
        if (count == null || count == 0) return R.err("带宽任务不存在");
        if (active.get() >= 2) return R.err("已有 2 项带宽测试在运行，请稍后重试");
        long now = System.currentTimeMillis();
        if (jdbcTemplate.update("UPDATE bandwidth_test_task SET running=1,last_status='running',last_error=NULL,updated_time=? WHERE id=? AND running=0", now, id) != 1) {
            return R.err("该带宽任务正在运行");
        }
        active.incrementAndGet();
        executor.submit(() -> { try { execute(id); } finally { active.decrementAndGet(); } });
        return R.ok(Map.of("id", id, "state", "running", "message", "真实带宽测试已开始"));
    }

    @Transactional
    public R delete(Long id) {
        Boolean running = nullableBoolean("SELECT running FROM bandwidth_test_task WHERE id=?", id);
        if (running == null) return R.err("带宽任务不存在");
        if (running) return R.err("带宽任务正在运行，不能删除");
        jdbcTemplate.update("DELETE FROM bandwidth_test_run WHERE task_id=?", id);
        jdbcTemplate.update("DELETE FROM bandwidth_test_task WHERE id=?", id);
        return overview();
    }

    public R detail(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,status,protocol,direction,streams,duration_ms AS durationMs,upload_bytes AS uploadBytes,"
                + "download_bytes AS downloadBytes,upload_mbps AS uploadMbps,download_mbps AS downloadMbps,total_mbps AS totalMbps,cpu_percent AS cpuPercent,"
                + "memory_used AS memoryUsed,memory_percent AS memoryPercent,successful_streams AS successfulStreams,failed_streams AS failedStreams,error,"
                + "rtt_ms AS rttMs,retransmits,retransmission_rate AS retransmissionRate,packets_sent AS packetsSent,packets_received AS packetsReceived,"
                + "packets_lost AS packetsLost,packet_loss_percent AS packetLossPercent,jitter_ms AS jitterMs,out_of_order_packets AS outOfOrderPackets,"
                + "started_at AS startedAt,finished_at AS finishedAt FROM bandwidth_test_run WHERE task_id=? ORDER BY started_at DESC LIMIT 200", id);
        return R.ok(Map.of("taskId", id, "runs", rows));
    }

    void execute(Long id) {
        long started = System.currentTimeMillis();
        Map<String, Object> task = one("SELECT * FROM bandwidth_test_task WHERE id=?", id);
        if (task == null) return;
        String sessionId = "bw-" + id + "-" + started;
        Long targetId = longNumber(task.get("target_node_id"));
        boolean prepared = false;
        try {
            Long sourceId = longNumber(task.get("source_node_id"));
            Node source = requireOnlineNode(sourceId, "来源");
            Node target = requireOnlineNode(targetId, "目标");
            String targetHost = firstNonBlank(target.getServerIp(), target.getIp());
            if (targetHost == null) throw new IllegalStateException("目标节点没有可连接地址");
            int streams = intNumber(task.get("streams"));
            String protocol = String.valueOf(task.getOrDefault("protocol", "tcp")).toLowerCase(Locale.ROOT);
            long totalBytes = Math.min(MAXIMUM_BYTES, (long) intNumber(task.get("maximum_megabytes")) * 1024 * 1024);
            long bytesPerStream = Math.max(1024 * 1024, totalBytes / streams);
            Map<String, Object> prepare = Map.of("sessionId", sessionId, "protocol", protocol, "listenPort", task.get("listen_port"), "ttlSeconds", Math.min(120, intNumber(task.get("duration_seconds")) + 45),
                    "maximumBytes", bytesPerStream, "maximumStreams", streams);
            GostDto prepareResult = WebSocketServer.send_msg(targetId, prepare, "BandwidthPrepare", 12);
            requireOK(prepareResult, "目标节点未能打开带宽测试端口"); prepared = true;
            JSONObject preparedData = json(prepareResult.getData());
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("targetHost", targetHost); run.put("port", preparedData.getIntValue("port")); run.put("token", preparedData.getString("token"));
            run.put("protocol", protocol); run.put("direction", task.get("direction")); run.put("streams", streams); run.put("durationSeconds", task.get("duration_seconds")); run.put("maximumBytes", bytesPerStream);
            GostDto runResult = WebSocketServer.send_msg(source.getId(), run, "BandwidthRun", intNumber(task.get("duration_seconds")) + 25L);
            requireOK(runResult, "来源节点未完成带宽测试");
            JSONObject data = json(runResult.getData());
            JSONObject targetMetrics = new JSONObject();
            GostDto stopResult = WebSocketServer.send_msg(targetId, Map.of("sessionId", sessionId), "BandwidthStop", 5);
            if (stopResult != null && "OK".equals(stopResult.getMsg()) && stopResult.getData() != null) {
                prepared = false;
                targetMetrics = json(stopResult.getData());
            }
            long retransmits = data.getLongValue("retransmits") + targetMetrics.getLongValue("retransmits");
            long packetsSent = data.getLongValue("packetsSent") + targetMetrics.getLongValue("packetsSent");
            long packetsReceived = data.getLongValue("packetsReceived") + targetMetrics.getLongValue("packetsReceived");
            long packetsLost = data.getLongValue("packetsLost") + targetMetrics.getLongValue("packetsLost");
            long outOfOrder = data.getLongValue("outOfOrderPackets") + targetMetrics.getLongValue("outOfOrderPackets");
            double rttMs = averagePositive(data.getDoubleValue("rttMs"), targetMetrics.getDoubleValue("rttMs"));
            double jitterMs = Math.max(data.getDoubleValue("jitterMs"), targetMetrics.getDoubleValue("jitterMs"));
            double retransmissionRate = percent(retransmits, packetsSent);
            double packetLossPercent = percent(packetsLost, packetsReceived + packetsLost);
            jdbcTemplate.update("INSERT INTO bandwidth_test_run (task_id,status,source_node_id,target_node_id,protocol,direction,streams,duration_ms,upload_bytes,download_bytes,upload_mbps,download_mbps,total_mbps,cpu_percent,memory_used,memory_percent,successful_streams,failed_streams,rtt_ms,retransmits,retransmission_rate,packets_sent,packets_received,packets_lost,packet_loss_percent,jitter_ms,out_of_order_packets,started_at,finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, "success", sourceId, targetId, protocol, task.get("direction"), streams, data.getLongValue("durationMs"), data.getLongValue("uploadBytes"),
                    data.getLongValue("downloadBytes"), data.getDoubleValue("uploadMbps"), data.getDoubleValue("downloadMbps"), data.getDoubleValue("totalMbps"),
                    data.getDoubleValue("cpuPercent"), data.getLongValue("memoryUsed"), data.getDoubleValue("memoryPercent"), data.getIntValue("successfulStreams"),
                    data.getIntValue("failedStreams"), rttMs, retransmits, retransmissionRate, packetsSent, packetsReceived, packetsLost, packetLossPercent,
                    jitterMs, outOfOrder, started, System.currentTimeMillis());
            finish(id, "success", null, started);
        } catch (Exception e) {
            String error = concise(e.getMessage());
            jdbcTemplate.update("INSERT INTO bandwidth_test_run (task_id,status,source_node_id,target_node_id,protocol,direction,streams,error,started_at,finished_at) VALUES (?,'failed',?,?,?,?,?,?,?,?)",
                    id, task.get("source_node_id"), task.get("target_node_id"), task.getOrDefault("protocol", "tcp"), task.get("direction"), task.get("streams"), error, started, System.currentTimeMillis());
            finish(id, "failed", error, started);
        } finally {
            if (prepared) WebSocketServer.send_msg(targetId, Map.of("sessionId", sessionId), "BandwidthStop", 5);
        }
    }

    private Node requireOnlineNode(Long id, String label) {
        Node node = nodeMapper.selectById(id);
        if (node == null) throw new IllegalStateException(label + "节点不存在");
        if (!WebSocketServer.isNodeOnline(id)) throw new IllegalStateException(label + "节点离线");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) throw new IllegalStateException(label + "节点 Agent 需要升级到 " + MIN_AGENT_VERSION);
        return node;
    }

    private void validate(BandwidthTestTaskDto dto) {
        if (Objects.equals(dto.getSourceNodeId(), dto.getTargetNodeId())) throw new IllegalArgumentException("来源节点和目标节点不能相同");
        if (nodeMapper.selectById(dto.getSourceNodeId()) == null || nodeMapper.selectById(dto.getTargetNodeId()) == null) throw new IllegalArgumentException("来源或目标节点不存在");
        dto.setDirection(dto.getDirection().toLowerCase(Locale.ROOT));
        dto.setProtocol(dto.getProtocol().toLowerCase(Locale.ROOT));
        if ("bidirectional".equals(dto.getDirection()) && dto.getStreams() < 2) throw new IllegalArgumentException("双向测试至少需要 2 个并发流");
    }

    private void finish(Long id, String status, String error, long started) {
        jdbcTemplate.update("UPDATE bandwidth_test_task SET running=0,last_status=?,last_error=?,last_run_at=?,updated_time=? WHERE id=?", status, error, started, System.currentTimeMillis(), id);
    }

    @Scheduled(cron = "0 50 3 * * ?")
    public void cleanup() {
        try { jdbcTemplate.update("DELETE r FROM bandwidth_test_run r JOIN bandwidth_test_task t ON t.id=r.task_id WHERE r.started_at < ? - t.retention_days*86400000", System.currentTimeMillis()); }
        catch (Exception e) { log.debug("Bandwidth history cleanup skipped: {}", e.getMessage()); }
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void recoverInterrupted() {
        try { jdbcTemplate.update("UPDATE bandwidth_test_task SET running=0,last_status='failed',last_error='上次测试因面板重启而中断',updated_time=? WHERE running=1 AND updated_time<?", System.currentTimeMillis(), System.currentTimeMillis() - 180_000); }
        catch (Exception e) { log.debug("Bandwidth interrupted task recovery skipped: {}", e.getMessage()); }
    }

    @PreDestroy public void shutdown() { executor.shutdownNow(); }
    private Map<String, Object> one(String sql, Object... args) { List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args); return rows.isEmpty() ? null : rows.get(0); }
    private Boolean nullableBoolean(String sql, Object... args) { List<Boolean> rows = jdbcTemplate.query(sql, (rs, row) -> rs.getBoolean(1), args); return rows.isEmpty() ? null : rows.get(0); }
    private static JSONObject json(Object value) { return value instanceof JSONObject ? (JSONObject) value : JSONObject.parseObject(JSONObject.toJSONString(value)); }
    private static void requireOK(GostDto result, String prefix) { if (result == null || !"OK".equals(result.getMsg()) || result.getData() == null) throw new IllegalStateException(prefix + "：" + (result == null ? "Agent 无响应" : result.getMsg())); }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || (value instanceof Number && ((Number) value).intValue() == 1); }
    private static Double number(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : 0D; }
    private static int intNumber(Object value) { return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value)); }
    private static Long longNumber(Object value) { return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(String.valueOf(value)); }
    private static double percent(long numerator, long denominator) { return denominator <= 0 ? 0D : Math.min(100D, numerator * 100D / denominator); }
    private static double averagePositive(double first, double second) { if (first <= 0) return Math.max(0, second); if (second <= 0) return first; return (first + second) / 2D; }
    private static String firstNonBlank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value.trim(); return null; }
    private static String concise(String value) { if (value == null || value.isBlank()) return "未知错误"; value = value.replace('\r', ' ').replace('\n', ' ').trim(); return value.length() > 500 ? value.substring(0, 500) : value; }
}
