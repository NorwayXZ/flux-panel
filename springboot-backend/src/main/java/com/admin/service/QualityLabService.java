package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.QualityProbeTaskDto;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
public class QualityLabService {
    static final String MIN_AGENT_VERSION = "2.36.0";
    private static final Pattern HOST = Pattern.compile("^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\\.(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*$");
    private static final Map<String, Long> RANGES = Map.of("24h", 86_400_000L, "7d", 604_800_000L, "30d", 2_592_000_000L);
    private static final long STALE_RUNNING_MS = 5 * 60_000L;
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final JdbcTemplate jdbcTemplate;
    private final NodeMapper nodeMapper;
    private final AtomicInteger activeProbes = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "quality-probe");
        thread.setDaemon(true);
        return thread;
    });

    public QualityLabService(JdbcTemplate jdbcTemplate, NodeMapper nodeMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.nodeMapper = nodeMapper;
    }

    public R overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimumAgentVersion", MIN_AGENT_VERSION);
        result.put("nodes", jdbcTemplate.queryForList("SELECT n.id,n.name,n.ip,n.server_ip AS serverIp,n.status,n.version,"
                + "COALESCE(a.network_line,'未标注线路') AS networkLine FROM node n LEFT JOIN server_asset a ON a.node_id=n.id ORDER BY n.status DESC,n.id DESC"));
        List<Map<String, Object>> tasks = jdbcTemplate.queryForList("SELECT q.id,q.name,q.source_node_id AS sourceNodeId,q.target_type AS targetType,q.target_node_id AS targetNodeId,"
                + "q.target_host AS targetHost,q.port,q.protocol,q.path,q.server_name AS serverName,q.ip_family AS ipFamily,q.sample_count AS sampleCount,"
                + "q.timeout_ms AS timeoutMs,q.interval_minutes AS intervalMinutes,q.retention_days AS retentionDays,q.enabled,q.running,q.next_run_at AS nextRunAt,"
                + "q.last_run_at AS lastRunAt,q.last_status AS lastStatus,q.last_error AS lastError,q.created_time AS createdTime,q.updated_time AS updatedTime,"
                + "COALESCE(n.name,'已删除节点') AS sourceNodeName,n.status AS sourceNodeStatus,n.version AS sourceNodeVersion,"
                + "COALESCE(a.network_line,'未标注线路') AS sourceLine,t.name AS targetNodeName,"
                + "r.id AS latestRunId,r.status AS latestRunStatus,r.p50_ms AS p50Ms,r.p95_ms AS p95Ms,r.p99_ms AS p99Ms,"
                + "r.jitter_ms AS jitterMs,r.failure_rate AS failureRate,r.tcp_avg_ms AS tcpAvgMs,r.tls_avg_ms AS tlsAvgMs,"
                + "r.ttfb_avg_ms AS ttfbAvgMs,r.ip_family AS latestIpFamily,r.started_at AS latestStartedAt "
                + "FROM quality_probe_task q LEFT JOIN node n ON n.id=q.source_node_id LEFT JOIN server_asset a ON a.node_id=n.id "
                + "LEFT JOIN node t ON t.id=q.target_node_id LEFT JOIN quality_probe_run r ON r.id=(SELECT MAX(r2.id) FROM quality_probe_run r2 WHERE r2.task_id=q.id) "
                + "ORDER BY q.created_time DESC");
        result.put("tasks", tasks);
        long healthy = tasks.stream().filter(item -> "success".equals(item.get("latestRunStatus")) && decimal(item.get("failureRate")) == 0).count();
        long degraded = tasks.stream().filter(item -> "partial".equals(item.get("latestRunStatus"))).count();
        long failed = tasks.stream().filter(item -> "failed".equals(item.get("latestRunStatus"))).count();
        result.put("summary", Map.of("total", tasks.size(), "enabled", tasks.stream().filter(item -> truth(item.get("enabled"))).count(),
                "healthy", healthy, "degraded", degraded, "failed", failed));
        result.put("lineProfiles", jdbcTemplate.queryForList("SELECT COALESCE(source_line,'未标注线路') AS label,COUNT(*) AS runs,"
                + "ROUND(AVG(p95_ms),2) AS p95Ms,ROUND(AVG(jitter_ms),2) AS jitterMs,ROUND(AVG(failure_rate),2) AS failureRate "
                + "FROM quality_probe_run WHERE started_at>=? GROUP BY COALESCE(source_line,'未标注线路') ORDER BY runs DESC,label",
                System.currentTimeMillis() - RANGES.get("24h")));
        return R.ok(result);
    }

    public R save(QualityProbeTaskDto dto) {
        try {
            normalizeAndValidate(dto);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        long now = System.currentTimeMillis();
        if (dto.getId() == null) {
            jdbcTemplate.update("INSERT INTO quality_probe_task (name,source_node_id,target_type,target_node_id,target_host,port,protocol,path,server_name,"
                            + "ip_family,sample_count,timeout_ms,interval_minutes,retention_days,enabled,running,next_run_at,last_status,created_time,updated_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    dto.getName().trim(), dto.getSourceNodeId(), dto.getTargetType(), dto.getTargetNodeId(), dto.getTargetHost().trim(), dto.getPort(),
                    dto.getProtocol(), dto.getPath(), blankToNull(dto.getServerName()), dto.getIpFamily(), dto.getSampleCount(), dto.getTimeoutMs(),
                    dto.getIntervalMinutes(), dto.getRetentionDays(), truth(dto.getEnabled()) ? 1 : 0, 0,
                    truth(dto.getEnabled()) ? now : null, "pending", now, now);
        } else {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quality_probe_task WHERE id=?", Integer.class, dto.getId());
            if (count == null || count == 0) return R.err("质量任务不存在");
            int changed = jdbcTemplate.update("UPDATE quality_probe_task SET name=?,source_node_id=?,target_type=?,target_node_id=?,target_host=?,port=?,protocol=?,path=?,"
                            + "server_name=?,ip_family=?,sample_count=?,timeout_ms=?,interval_minutes=?,retention_days=?,enabled=?,next_run_at=?,updated_time=? WHERE id=? AND running=0",
                    dto.getName().trim(), dto.getSourceNodeId(), dto.getTargetType(), dto.getTargetNodeId(), dto.getTargetHost().trim(), dto.getPort(),
                    dto.getProtocol(), dto.getPath(), blankToNull(dto.getServerName()), dto.getIpFamily(), dto.getSampleCount(), dto.getTimeoutMs(),
                    dto.getIntervalMinutes(), dto.getRetentionDays(), truth(dto.getEnabled()) ? 1 : 0,
                    truth(dto.getEnabled()) ? now : null, now, dto.getId());
            if (changed == 0) return R.err("任务正在探测，请等待本轮结束后编辑");
        }
        return overview();
    }

    public R preflight(QualityProbeTaskDto dto) {
        try {
            normalizeAndValidate(dto);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        int active = activeProbes.incrementAndGet();
        if (active > 2) {
            activeProbes.decrementAndGet();
            return R.err("当前已有 2 项探测在运行，请稍后再检查目标端口");
        }
        try {
            Node source = nodeMapper.selectById(dto.getSourceNodeId());
            if (!WebSocketServer.isNodeOnline(source.getId())) return R.err("执行节点离线，无法检查目标端口");
            if (!AgentVersionUtil.isAtLeast(source.getVersion(), MIN_AGENT_VERSION)) {
                return R.err("执行节点 Agent 需要升级到 " + MIN_AGENT_VERSION);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("target", dto.getTargetHost());
            payload.put("port", dto.getPort());
            payload.put("protocol", "tcp");
            payload.put("path", "/");
            payload.put("serverName", null);
            payload.put("ipFamily", dto.getIpFamily());
            payload.put("count", 1);
            payload.put("timeoutMs", Math.min(dto.getTimeoutMs(), 5_000));
            GostDto response = WebSocketServer.send_msg(source.getId(), payload, "QualityProbe", 12);
            if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
                return R.err(response == null ? "执行节点 Agent 无响应" : response.getMsg());
            }
            JSONObject data = response.getData() instanceof JSONObject
                    ? (JSONObject) response.getData()
                    : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
            JSONArray samples = data.getJSONArray("samples");
            JSONObject sample = samples == null || samples.isEmpty() ? null : samples.getJSONObject(0);
            boolean reachable = data.getIntValue("successCount") > 0;
            String error = reachable ? null : probeError(data);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reachable", reachable);
            result.put("message", reachable ? "目标端口可以连接" : explainProbeError(error));
            result.put("error", error);
            result.put("resolvedAddress", data.getString("resolvedAddress"));
            result.put("ipFamily", data.getString("ipFamily"));
            result.put("tcpMs", sample == null ? null : numberOrNull(sample.get("tcpMs")));
            return R.ok(result);
        } catch (Exception e) {
            return R.err("目标预检失败：" + explainProbeError(concise(e.getMessage())));
        } finally {
            activeProbes.decrementAndGet();
        }
    }

    public R runNow(Long id) {
        if (!exists(id)) return R.err("质量任务不存在");
        if (!submit(id, true)) return R.err(activeProbes.get() >= 2 ? "当前已有 2 项探测在运行，请稍后重试" : "该任务正在探测，请稍后查看结果");
        return R.ok(Map.of("id", id, "state", "queued", "message", "探测任务已提交"));
    }

    public R toggle(Long id, boolean enabled) {
        int changed = jdbcTemplate.update("UPDATE quality_probe_task SET enabled=?,next_run_at=?,updated_time=? WHERE id=?",
                enabled ? 1 : 0, enabled ? System.currentTimeMillis() : null, System.currentTimeMillis(), id);
        return changed == 0 ? R.err("质量任务不存在") : overview();
    }

    @Transactional
    public R delete(Long id) {
        if (!exists(id)) return R.err("质量任务不存在");
        Boolean running = jdbcTemplate.queryForObject("SELECT running FROM quality_probe_task WHERE id=?", Boolean.class, id);
        if (Boolean.TRUE.equals(running)) return R.err("任务正在探测，请等待本轮结束后删除");
        jdbcTemplate.update("DELETE s FROM quality_probe_sample s JOIN quality_probe_run r ON r.id=s.run_id WHERE r.task_id=?", id);
        jdbcTemplate.update("DELETE FROM quality_probe_run WHERE task_id=?", id);
        jdbcTemplate.update("DELETE FROM quality_probe_task WHERE id=?", id);
        return R.ok();
    }

    public R detail(Long id, String range) {
        Map<String, Object> task = one("SELECT q.id,q.name,q.source_node_id AS sourceNodeId,q.target_type AS targetType,q.target_node_id AS targetNodeId,"
                        + "q.target_host AS targetHost,q.port,q.protocol,q.path,q.server_name AS serverName,q.ip_family AS ipFamily,q.sample_count AS sampleCount,"
                        + "q.timeout_ms AS timeoutMs,q.interval_minutes AS intervalMinutes,q.retention_days AS retentionDays,q.enabled,q.running,q.next_run_at AS nextRunAt,"
                        + "q.last_run_at AS lastRunAt,q.last_status AS lastStatus,q.last_error AS lastError,q.created_time AS createdTime,q.updated_time AS updatedTime,"
                        + "n.name AS sourceNodeName,COALESCE(a.network_line,'未标注线路') AS sourceLine,t.name AS targetNodeName "
                        + "FROM quality_probe_task q LEFT JOIN node n ON n.id=q.source_node_id LEFT JOIN server_asset a ON a.node_id=n.id "
                        + "LEFT JOIN node t ON t.id=q.target_node_id WHERE q.id=?", id);
        if (task == null) return R.err("质量任务不存在");
        long since = System.currentTimeMillis() - RANGES.getOrDefault(range, RANGES.get("24h"));
        List<Map<String, Object>> runs = jdbcTemplate.queryForList("SELECT id,status,resolved_address AS resolvedAddress,ip_family AS ipFamily,protocol,"
                        + "dns_ms AS dnsMs,tcp_avg_ms AS tcpAvgMs,tls_avg_ms AS tlsAvgMs,ttfb_avg_ms AS ttfbAvgMs,p50_ms AS p50Ms,p95_ms AS p95Ms,"
                        + "p99_ms AS p99Ms,jitter_ms AS jitterMs,failure_rate AS failureRate,success_count AS successCount,sample_count AS sampleCount,"
                        + "http_status AS httpStatus,error,started_at AS startedAt,finished_at AS finishedAt FROM quality_probe_run "
                        + "WHERE task_id=? AND started_at>=? ORDER BY started_at ASC LIMIT 2000", id, since);
        List<Double> totals = jdbcTemplate.query("SELECT s.total_ms FROM quality_probe_sample s JOIN quality_probe_run r ON r.id=s.run_id "
                        + "WHERE r.task_id=? AND r.started_at>=? AND s.success=1 AND s.total_ms IS NOT NULL ORDER BY s.id DESC LIMIT 10000",
                (row, rowNum) -> row.getDouble(1), id, since);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task);
        result.put("range", range);
        result.put("summary", aggregate(runs, totals));
        result.put("runs", runs);
        result.put("ipComparison", groupComparison(id, since, "ip_family"));
        result.put("lineComparison", groupComparison(id, since, "source_line"));
        result.put("hourComparison", jdbcTemplate.queryForList("SELECT HOUR(FROM_UNIXTIME(started_at/1000)) AS label,COUNT(*) AS runs,"
                + "ROUND(AVG(p95_ms),2) AS p95Ms,ROUND(AVG(jitter_ms),2) AS jitterMs,ROUND(AVG(failure_rate),2) AS failureRate "
                + "FROM quality_probe_run WHERE task_id=? AND started_at>=? GROUP BY HOUR(FROM_UNIXTIME(started_at/1000)) ORDER BY label", id, since));
        return R.ok(result);
    }

    public R report(Long id, String range) {
        R detail = detail(id, range);
        if (detail.getCode() != 0) return detail;
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) detail.getData();
        @SuppressWarnings("unchecked") Map<String, Object> task = (Map<String, Object>) data.get("task");
        @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        String markdown = "# CloudNest 网络质量报告\n\n"
                + "- 任务：" + task.get("name") + "\n- 测试方向：" + task.get("sourceNodeName") + " → " + task.get("targetHost") + ":" + task.get("port")
                + "\n- 协议：" + String.valueOf(task.get("protocol")).toUpperCase(Locale.ROOT) + " / " + task.get("ipFamily")
                + "\n- 时间范围：" + range + "\n- 生成时间：" + REPORT_TIME.format(Instant.now()) + "\n\n"
                + "## 结论\n\n- 运行次数：" + summary.get("runs") + "\n- 成功率：" + format(100 - decimal(summary.get("failureRate"))) + "%"
                + "\n- P50 / P95 / P99：" + format(decimal(summary.get("p50Ms"))) + " / " + format(decimal(summary.get("p95Ms"))) + " / " + format(decimal(summary.get("p99Ms"))) + " ms"
                + "\n- 平均抖动：" + format(decimal(summary.get("jitterMs"))) + " ms\n- TCP / TLS / TTFB："
                + format(decimal(summary.get("tcpAvgMs"))) + " / " + format(decimal(summary.get("tlsAvgMs"))) + " / " + format(decimal(summary.get("ttfbAvgMs"))) + " ms\n";
        return R.ok(Map.of("filename", "cloudnest-quality-" + id + "-" + range + ".md", "content", markdown, "generatedAt", System.currentTimeMillis()));
    }

    @Scheduled(initialDelay = 30_000L, fixedDelay = 5_000L)
    public void schedule() {
        try {
            recoverStaleTasks();
            List<Long> due = jdbcTemplate.queryForList("SELECT id FROM quality_probe_task WHERE enabled=1 AND running=0 AND (next_run_at IS NULL OR next_run_at<=?) ORDER BY next_run_at,id LIMIT 2",
                    Long.class, System.currentTimeMillis());
            for (Long id : due) {
                if (!submit(id, false) && activeProbes.get() >= 2) break;
            }
        } catch (Exception e) {
            log.debug("Quality probe scheduler waiting for storage: {}", e.getMessage());
        }
    }

    int recoverStaleTasks() {
        long now = System.currentTimeMillis();
        return jdbcTemplate.update("UPDATE quality_probe_task SET running=0,last_status='failed',"
                        + "last_error='上次探测因面板重启或等待超时而中断，可重新执行',"
                        + "next_run_at=CASE WHEN enabled=1 THEN ? + interval_minutes*60000 ELSE NULL END,updated_time=? "
                        + "WHERE running=1 AND updated_time<?",
                now, now, now - STALE_RUNNING_MS);
    }

    @Scheduled(cron = "0 40 3 * * ?")
    public void cleanup() {
        try {
            jdbcTemplate.update("DELETE s FROM quality_probe_sample s JOIN quality_probe_run r ON r.id=s.run_id JOIN quality_probe_task q ON q.id=r.task_id "
                    + "WHERE r.started_at < ? - q.retention_days*86400000", System.currentTimeMillis());
            jdbcTemplate.update("DELETE r FROM quality_probe_run r JOIN quality_probe_task q ON q.id=r.task_id "
                    + "WHERE r.started_at < ? - q.retention_days*86400000", System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("Quality probe cleanup skipped: {}", e.getMessage());
        }
    }

    void execute(Long id) {
        long started = System.currentTimeMillis();
        Map<String, Object> task = one("SELECT q.*,COALESCE(n.name,'已删除节点') AS sourceNodeName,n.version AS sourceNodeVersion,COALESCE(a.network_line,'未标注线路') AS sourceLine "
                + "FROM quality_probe_task q LEFT JOIN node n ON n.id=q.source_node_id LEFT JOIN server_asset a ON a.node_id=n.id WHERE q.id=?", id);
        if (task == null) return;
        try {
            long sourceId = number(task.get("source_node_id"));
            Node node = nodeMapper.selectById(sourceId);
            if (node == null || !WebSocketServer.isNodeOnline(sourceId)) throw new IllegalStateException("执行节点离线");
            if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) throw new IllegalStateException("执行节点 Agent 需要升级到 " + MIN_AGENT_VERSION);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("target", task.get("target_host")); payload.put("port", task.get("port")); payload.put("protocol", task.get("protocol"));
            payload.put("path", task.get("path")); payload.put("serverName", task.get("server_name")); payload.put("ipFamily", task.get("ip_family"));
            payload.put("count", task.get("sample_count")); payload.put("timeoutMs", task.get("timeout_ms"));
            long timeoutSeconds = Math.min(180, Math.max(15, number(task.get("sample_count")) * number(task.get("timeout_ms")) / 1000 + 10));
            GostDto response = WebSocketServer.send_msg(sourceId, payload, "QualityProbe", timeoutSeconds);
            if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
                throw new IllegalStateException(response == null ? "Agent 无响应" : response.getMsg());
            }
            JSONObject data = response.getData() instanceof JSONObject
                    ? (JSONObject) response.getData()
                    : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
            storeRun(task, data, started);
            int successCount = data.getIntValue("successCount");
            int sampleCount = data.getJSONArray("samples").size();
            String status = successCount == 0 ? "failed" : successCount == sampleCount ? "success" : "partial";
            String error = "success".equals(status) ? null : probeError(data);
            jdbcTemplate.update("UPDATE quality_probe_task SET running=0,last_run_at=?,last_status=?,last_error=?,next_run_at=?,updated_time=? WHERE id=?",
                    System.currentTimeMillis(), status, error,
                    System.currentTimeMillis() + number(task.get("interval_minutes")) * 60_000L, System.currentTimeMillis(), id);
        } catch (Exception e) {
            String error = concise(e.getMessage());
            try {
                storeFailure(task, started, error);
            } catch (Exception storeError) {
                log.warn("Quality probe {} failure result could not be stored: {}", id, storeError.getMessage());
            } finally {
                jdbcTemplate.update("UPDATE quality_probe_task SET running=0,last_run_at=?,last_status='failed',last_error=?,next_run_at=?,updated_time=? WHERE id=?",
                        System.currentTimeMillis(), error, System.currentTimeMillis() + number(task.get("interval_minutes")) * 60_000L, System.currentTimeMillis(), id);
            }
        }
    }

    private boolean submit(Long id, boolean force) {
        while (true) {
            int current = activeProbes.get();
            if (current >= 2) return false;
            if (activeProbes.compareAndSet(current, current + 1)) break;
        }
        if (!claim(id, force)) {
            activeProbes.decrementAndGet();
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    execute(id);
                } finally {
                    activeProbes.decrementAndGet();
                }
            });
            return true;
        } catch (RuntimeException e) {
            activeProbes.decrementAndGet();
            jdbcTemplate.update("UPDATE quality_probe_task SET running=0,last_status='failed',last_error='探测执行器暂不可用',updated_time=? WHERE id=?",
                    System.currentTimeMillis(), id);
            return false;
        }
    }

    private void storeRun(Map<String, Object> task, JSONObject data, long started) {
        JSONArray rawSamples = data.getJSONArray("samples");
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int index = 0; index < rawSamples.size(); index++) samples.add(rawSamples.getJSONObject(index));
        Map<String, Object> stats = QualityStatistics.summary(samples);
        String status = data.getIntValue("successCount") == 0 ? "failed" : data.getIntValue("successCount") == samples.size() ? "success" : "partial";
        Long runId = insertAndId("INSERT INTO quality_probe_run (task_id,status,source_node_id,source_node_name,source_line,target_host,resolved_address,"
                        + "ip_family,protocol,dns_ms,tcp_avg_ms,tls_avg_ms,ttfb_avg_ms,p50_ms,p95_ms,p99_ms,jitter_ms,failure_rate,success_count,sample_count,http_status,error,started_at,finished_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                task.get("id"), status, task.get("source_node_id"), task.get("sourceNodeName"), task.get("sourceLine"), task.get("target_host"), data.getString("resolvedAddress"),
                data.getString("ipFamily"), task.get("protocol"), data.getDoubleValue("dnsMs"), stats.get("tcpAvgMs"), stats.get("tlsAvgMs"), stats.get("ttfbAvgMs"),
                stats.get("p50Ms"), stats.get("p95Ms"), stats.get("p99Ms"), data.getDoubleValue("jitterMs"), data.getDoubleValue("failureRate"),
                data.getIntValue("successCount"), samples.size(), firstStatus(samples), concise(data.getString("error")), started, System.currentTimeMillis());
        for (Map<String, Object> sample : samples) {
            jdbcTemplate.update("INSERT INTO quality_probe_sample (run_id,task_id,sample_index,success,tcp_ms,tls_ms,ttfb_ms,total_ms,http_status,error,created_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    runId, task.get("id"), sample.get("index"), truth(sample.get("success")) ? 1 : 0, numberOrNull(sample.get("tcpMs")), numberOrNull(sample.get("tlsMs")),
                    numberOrNull(sample.get("ttfbMs")), numberOrNull(sample.get("totalMs")), numberOrNull(sample.get("httpStatus")), concise(Objects.toString(sample.get("error"), null)), started);
        }
    }

    private void storeFailure(Map<String, Object> task, long started, String error) {
        jdbcTemplate.update("INSERT INTO quality_probe_run (task_id,status,source_node_id,source_node_name,source_line,target_host,ip_family,protocol,"
                        + "failure_rate,success_count,sample_count,error,started_at,finished_at) VALUES (?,?,?,?,?,?,?,?,100,0,0,?,?,?)",
                task.get("id"), "failed", task.get("source_node_id"), task.get("sourceNodeName"), task.get("sourceLine"), task.get("target_host"),
                task.get("ip_family"), task.get("protocol"), error, started, System.currentTimeMillis());
    }

    private boolean claim(Long id, boolean force) {
        long now = System.currentTimeMillis();
        int updated = jdbcTemplate.update("UPDATE quality_probe_task SET running=1,last_status='running',last_error=NULL,updated_time=? WHERE id=? AND running=0"
                + (force ? "" : " AND enabled=1 AND (next_run_at IS NULL OR next_run_at<=?)"), force ? new Object[]{now, id} : new Object[]{now, id, now});
        return updated == 1;
    }

    private void normalizeAndValidate(QualityProbeTaskDto dto) {
        Node source = nodeMapper.selectById(dto.getSourceNodeId());
        if (source == null) throw new IllegalArgumentException("执行节点不存在");
        dto.setProtocol(dto.getProtocol().toLowerCase(Locale.ROOT));
        dto.setIpFamily(dto.getIpFamily().toLowerCase(Locale.ROOT));
        dto.setTargetType(dto.getTargetType() == null ? "custom" : dto.getTargetType().toLowerCase(Locale.ROOT));
        if ("node".equals(dto.getTargetType())) {
            Node target = nodeMapper.selectById(dto.getTargetNodeId());
            if (target == null) throw new IllegalArgumentException("目标节点不存在");
            if (Objects.equals(target.getId(), source.getId())) throw new IllegalArgumentException("互测任务的来源和目标节点不能相同");
            String targetAddress = blankToNull(target.getServerIp());
            if (targetAddress == null) targetAddress = blankToNull(target.getIp());
            if (targetAddress == null) throw new IllegalArgumentException("目标节点未设置可探测地址");
            dto.setTargetHost(targetAddress);
        } else dto.setTargetNodeId(null);
        String target = dto.getTargetHost().trim();
        if (!validHost(target)) throw new IllegalArgumentException("目标地址格式不正确");
        if (dto.getPath() == null || dto.getPath().isBlank()) dto.setPath("/");
        if (!dto.getPath().startsWith("/") || dto.getPath().contains("\r") || dto.getPath().contains("\n")) throw new IllegalArgumentException("HTTP 路径必须以 / 开头");
        if (dto.getServerName() != null && !dto.getServerName().isBlank() && !validHost(dto.getServerName().trim())) throw new IllegalArgumentException("TLS 域名格式不正确");
    }

    private List<Map<String, Object>> groupComparison(Long id, long since, String column) {
        String safe = "source_line".equals(column) ? "source_line" : "ip_family";
        return jdbcTemplate.queryForList("SELECT " + safe + " AS label,COUNT(*) AS runs,ROUND(AVG(p95_ms),2) AS p95Ms,"
                + "ROUND(AVG(jitter_ms),2) AS jitterMs,ROUND(AVG(failure_rate),2) AS failureRate FROM quality_probe_run "
                + "WHERE task_id=? AND started_at>=? GROUP BY " + safe + " ORDER BY runs DESC", id, since);
    }

    private Map<String, Object> aggregate(List<Map<String, Object>> runs, List<Double> totals) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runs", runs.size());
        result.put("tcpAvgMs", average(runs, "tcpAvgMs")); result.put("tlsAvgMs", average(runs, "tlsAvgMs")); result.put("ttfbAvgMs", average(runs, "ttfbAvgMs"));
        result.put("p50Ms", QualityStatistics.percentile(totals, .50)); result.put("p95Ms", QualityStatistics.percentile(totals, .95)); result.put("p99Ms", QualityStatistics.percentile(totals, .99));
        result.put("jitterMs", average(runs, "jitterMs")); result.put("failureRate", average(runs, "failureRate"));
        result.put("interruptions", runs.stream().filter(row -> decimal(row.get("failureRate")) >= 100).count());
        result.put("lastRunAt", runs.isEmpty() ? 0 : runs.get(runs.size() - 1).get("startedAt"));
        return result;
    }

    private static double average(List<Map<String, Object>> rows, String key) { return rows.stream().map(row -> row.get(key)).filter(Objects::nonNull).mapToDouble(QualityLabService::decimal).average().orElse(0); }
    private boolean exists(Long id) { Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quality_probe_task WHERE id=?", Integer.class, id); return count != null && count > 0; }
    private Map<String, Object> one(String sql, Object... args) { List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args); return rows.isEmpty() ? null : rows.get(0); }
    private Long insertAndId(String sql, Object... args) { org.springframework.jdbc.support.KeyHolder holder = new org.springframework.jdbc.support.GeneratedKeyHolder(); jdbcTemplate.update(connection -> { var statement = connection.prepareStatement(sql, new String[]{"id"}); for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]); return statement; }, holder); return Objects.requireNonNull(holder.getKey()).longValue(); }
    static boolean validHost(String value) {
        if (value == null || value.isBlank()) return false;
        if (isIpv4Literal(value)) return true;
        if (value.contains(":")) {
            if (value.contains("%")) return false;
            try {
                return InetAddress.getByName(value) instanceof Inet6Address;
            } catch (Exception ignored) {
                return false;
            }
        }
        if (value.matches("[0-9.]+")) return false;
        return HOST.matcher(value).matches();
    }

    private static boolean isIpv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int index = 0; index < part.length(); index++) if (!Character.isDigit(part.charAt(index))) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
    private static Object numberOrNull(Object value) { return value instanceof Number && ((Number) value).doubleValue() > 0 ? value : null; }
    private static Integer firstStatus(List<Map<String, Object>> samples) { return samples.stream().map(row -> row.get("httpStatus")).filter(Number.class::isInstance).map(Number.class::cast).map(Number::intValue).findFirst().orElse(null); }
    private static String probeError(JSONObject data) {
        String error = concise(data.getString("error"));
        if (error != null) return error;
        JSONArray samples = data.getJSONArray("samples");
        if (samples == null) return null;
        for (int index = 0; index < samples.size(); index++) {
            error = concise(samples.getJSONObject(index).getString("error"));
            if (error != null) return error;
        }
        return null;
    }
    static String explainProbeError(String error) {
        if (error == null || error.isBlank()) return "目标端口不可连接";
        String lower = error.toLowerCase(Locale.ROOT);
        if (lower.contains("connection refused")) return "目标端口未监听，或目标防火墙主动拒绝连接";
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("deadline exceeded")) return "连接目标端口超时，请检查防火墙、安全组和网络路由";
        if (lower.contains("no route to host") || lower.contains("network is unreachable")) return "没有到目标地址的可用网络路由";
        if (lower.contains("no such host") || lower.contains("server misbehaving")) return "目标域名无法解析";
        return error;
    }
    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }
    private static double decimal(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : 0; }
    private static boolean truth(Object value) { return value instanceof Boolean ? (Boolean) value : value instanceof Number && ((Number) value).intValue() == 1; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String concise(String value) { if (value == null || value.isBlank()) return null; value=value.trim(); return value.length()>500 ? value.substring(0,500) : value; }
    private static String format(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }

    @PreDestroy public void shutdown() { executor.shutdownNow(); }
}
