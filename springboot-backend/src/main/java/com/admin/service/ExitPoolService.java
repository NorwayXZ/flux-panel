package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.ExitCandidateParser;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 国家家宽出口池。
 *
 * 面板负责决策：收集候选、判定是不是该国家宽、决定每个节点上保留哪些出口。
 * 节点上的 Agent 负责执行：连接出口、检测、在已批准的出口之间切换。
 * 只有出口 IP 被这里判定为“目标国家的家宽”之后，Agent 才会让流量经过它。
 */
@Slf4j
@Service
public class ExitPoolService {
    public static final String MIN_AGENT_VERSION = "2.57.0";
    /** 由独立运行时承载、经本机出口通道使用家宽出口的协议，需要更高的 Agent 版本。 */
    public static final String MIN_ROUTED_AGENT_VERSION = "2.58.0";
    public static final Set<String> ROUTED_PROXY_TYPES = Set.of("vless_reality", "trojan", "hysteria2", "tuic");
    public static final Set<String> SUPPORTED_PROXY_TYPES = Set.of("socks5", "http", "shadowsocks",
            "vless_reality", "trojan", "hysteria2", "tuic");

    public static String minAgentVersion(String proxyType) {
        return ROUTED_PROXY_TYPES.contains(proxyType) ? MIN_ROUTED_AGENT_VERSION : MIN_AGENT_VERSION;
    }

    static final String KIND_HOME = "home";
    static final String KIND_MOBILE = "mobile";
    static final String KIND_HOSTING = "hosting";
    static final String KIND_PROJECT = "project";
    static final String KIND_MISMATCH = "mismatch";
    static final String KIND_UNKNOWN = "unknown";

    private static final String SOURCE_VPNGATE = "vpngate";
    private static final long LIST_REFRESH_MS = 10 * 60_000L;
    private static final long CANDIDATE_FRESH_MS = 30 * 60_000L;
    private static final long IP_INFO_TTL_MS = 7 * 86_400_000L;
    private static final long CONNECT_PATIENCE_MS = 5 * 60_000L;
    private static final long REJECT_COOLDOWN_MS = 6 * 3_600_000L;
    private static final long BLOCK_COOLDOWN_MS = 30 * 86_400_000L;
    private static final long EVENT_RETENTION_MS = 14 * 86_400_000L;
    private static final int READY_TARGET = 2;
    private static final int SEARCH_PARALLEL = 6;
    private static final int STANDBY_SEARCH_PARALLEL = 3;
    private static final long FAST_RETRY_SECONDS = 5L;
    private static final long LOOKUP_SPACING_MS = 4_000L;
    private static final long LOOKUP_RETRY_MS = 30_000L;
    private static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(
            Map.entry("JP", "日本"), Map.entry("KR", "韩国"), Map.entry("RU", "俄罗斯"), Map.entry("TH", "泰国"),
            Map.entry("VN", "越南"), Map.entry("US", "美国"), Map.entry("GB", "英国"), Map.entry("HK", "香港"),
            Map.entry("TW", "台湾"), Map.entry("SG", "新加坡"), Map.entry("DE", "德国"), Map.entry("FR", "法国"),
            Map.entry("CA", "加拿大"), Map.entry("AU", "澳大利亚"), Map.entry("IN", "印度"), Map.entry("ID", "印度尼西亚"),
            Map.entry("HR", "克罗地亚"), Map.entry("BY", "白俄罗斯"), Map.entry("UA", "乌克兰"), Map.entry("BR", "巴西"));

    private final JdbcTemplate jdbc;
    private final NodeMapper nodeMapper;
    private final IpQualityService ipQualityService;
    private final String listUrl;
    private final String lookupUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "exit-pool");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private final Map<Long, String> syncedPayloads = new ConcurrentHashMap<>();
    private volatile long lastRefreshAt;
    private volatile long lastRefreshAttemptAt;
    private volatile String lastRefreshError;
    private volatile String lastLookupError;
    private volatile long lastLookupAt;
    private final Map<String, Long> lookupRetryAt = new ConcurrentHashMap<>();
    private volatile long lastCleanupAt;

    public ExitPoolService(JdbcTemplate jdbc, NodeMapper nodeMapper, IpQualityService ipQualityService,
                           @Value("${exit-pool.vpngate-url:http://www.vpngate.net/api/iphone/}") String listUrl,
                           @Value("${exit-pool.ip-lookup-url:http://ip-api.com/batch}") String lookupUrl) {
        this.jdbc = jdbc;
        this.nodeMapper = nodeMapper;
        this.ipQualityService = ipQualityService;
        this.listUrl = listUrl;
        this.lookupUrl = lookupUrl;
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }

    @Scheduled(initialDelay = 25_000L, fixedDelay = 20_000L)
    public void scheduledReconcile() {
        requestReconcile();
    }

    /** 尽快执行一轮调度。在独立线程里运行，不占用其他定时任务。 */
    public void requestReconcile() {
        scheduleReconcile(0L);
    }

    private void scheduleReconcile(long delaySeconds) {
        if (!queued.compareAndSet(false, true)) return;
        try {
            worker.schedule(() -> {
                queued.set(false);
                boolean waitingForExit = false;
                try {
                    waitingForExit = reconcile();
                } catch (Exception e) {
                    log.warn("Exit pool reconcile failed: {}", e.getMessage());
                }
                // 有代理正等着出口恢复时不等下一个周期，几秒后就再试
                if (waitingForExit) scheduleReconcile(FAST_RETRY_SECONDS);
            }, delaySeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            queued.set(false);
        }
    }

    // ---------------------------------------------------------------- 查询接口

    /** 各国当前可用的家宽候选数量，用于新建代理时选择国家。 */
    public R countries() {
        refreshCandidatesIfDue(false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minAgentVersion", MIN_AGENT_VERSION);
        result.put("minRoutedAgentVersion", MIN_ROUTED_AGENT_VERSION);
        result.put("routedProxyTypes", ROUTED_PROXY_TYPES);
        result.put("supportedProxyTypes", SUPPORTED_PROXY_TYPES);
        result.put("countries", countryRows());
        result.put("lastRefreshAt", lastRefreshAt == 0 ? null : lastRefreshAt);
        result.put("lastRefreshError", refreshProblem());
        return R.ok(result);
    }

    @SuppressWarnings("unchecked")
    public R overview() {
        refreshCandidatesIfDue(false);
        long now = System.currentTimeMillis();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> group : jdbc.queryForList("SELECT * FROM exit_group ORDER BY country, node_id")) {
            long groupId = longValue(group.get("id"));
            Node node = nodeMapper.selectById(longValue(group.get("node_id")));
            Map<String, Object> view = groupView(group, now);
            view.put("id", groupId);
            view.put("nodeId", group.get("node_id"));
            view.put("nodeName", node == null ? "节点已删除" : node.getName());
            view.put("proxyCount", jdbc.queryForObject("SELECT COUNT(*) FROM private_proxy WHERE node_id=? AND exit_type='residential' "
                    + "AND UPPER(exit_country)=? AND state IN ('provisioning','active')", Long.class, group.get("node_id"), group.get("country")));
            view.put("members", jdbc.queryForList("SELECT m.id, m.state, m.exit_ip AS exitIp, m.exit_owner AS exitOwner, m.exit_kind AS exitKind, "
                    + "m.exit_flagged AS exitFlagged, m.approved, m.latency_ms AS latencyMs, m.rx_bytes AS rxBytes, m.tx_bytes AS txBytes, "
                    + "m.connected_at AS connectedAt, m.last_ok_at AS lastOkAt, m.error, m.retry_after AS retryAfter, m.updated_time AS updatedTime, "
                    + "c.host, c.port, c.proto, c.owner, c.flagged_proxy AS flaggedProxy, (m.id = ?) AS active "
                    + "FROM exit_member m JOIN exit_candidate c ON c.id = m.candidate_id WHERE m.group_id = ? "
                    + "AND (m.state IN ('connecting','up') OR m.updated_time >= ?) "
                    + "ORDER BY (m.id = ?) DESC, FIELD(m.state,'up','connecting','failed','rejected'), m.updated_time DESC LIMIT 30",
                    longValue(group.get("active_member_id")), groupId, now - 3_600_000L, longValue(group.get("active_member_id"))));
            for (Map<String, Object> member : (List<Map<String, Object>>) view.get("members")) {
                if (member.get("error") != null) member.put("error", translate(string(member.get("error"))));
            }
            groups.add(view);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minAgentVersion", MIN_AGENT_VERSION);
        result.put("countries", countryRows());
        result.put("groups", groups);
        result.put("lastRefreshAt", lastRefreshAt == 0 ? null : lastRefreshAt);
        result.put("lastRefreshError", refreshProblem());
        return R.ok(result);
    }

    public R events(Long groupId) {
        if (groupId == null) return R.err("缺少出口编号");
        return R.ok(jdbc.queryForList("SELECT id, type, from_ip AS fromIp, to_ip AS toIp, message, created_time AS createdTime "
                + "FROM exit_event WHERE group_id = ? ORDER BY id DESC LIMIT 100", groupId));
    }

    public R refresh() {
        refreshCandidatesIfDue(true);
        requestReconcile();
        return lastRefreshError == null ? R.ok() : R.err("候选列表刷新失败：" + lastRefreshError);
    }

    private String refreshProblem() {
        return lastRefreshError != null ? lastRefreshError : lastLookupError;
    }

    /** 手动排除一个出口。它如果正在使用，节点会立即切到另一个已批准的出口。 */
    public R block(Long memberId) {
        if (memberId == null) return R.err("缺少出口编号");
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT m.group_id, m.exit_ip, g.node_id, g.country FROM exit_member m "
                + "JOIN exit_group g ON g.id = m.group_id WHERE m.id = ?", memberId);
        if (rows.isEmpty()) return R.err("出口不存在");
        Map<String, Object> row = rows.get(0);
        jdbc.update("UPDATE exit_member SET state='rejected', approved=0, error='已手动排除', retry_after=?, updated_time=? WHERE id=?",
                now + BLOCK_COOLDOWN_MS, now, memberId);
        event(longValue(row.get("group_id")), longValue(row.get("node_id")), string(row.get("country")), "reject",
                string(row.get("exit_ip")), null, "已手动排除出口" + (row.get("exit_ip") == null ? "" : " " + row.get("exit_ip")));
        requestReconcile();
        return R.ok();
    }

    private static final long MANUAL_SWITCH_INTERVAL_MS = 10_000L;
    private static final long RELEASED_COOLDOWN_MS = 10 * 60_000L;
    private final Map<Long, Long> lastManualSwitchAt = new ConcurrentHashMap<>();

    /**
     * 立即换一个出口。memberId 为空时换到已就绪的备用出口；指定时换到那个出口。
     * 只在有另一个已就绪的出口时才会切换，不会让代理因为手动操作而断线。
     */
    public synchronized R switchExit(Long groupId, Long memberId, String source) {
        if (groupId == null) return R.err("缺少出口编号");
        List<Map<String, Object>> groups = jdbc.queryForList("SELECT * FROM exit_group WHERE id = ?", groupId);
        if (groups.isEmpty()) return R.err("出口还没有准备好，请稍后再试");
        Map<String, Object> group = groups.get(0);
        long now = System.currentTimeMillis();
        Long last = lastManualSwitchAt.get(groupId);
        if (last != null && now - last < MANUAL_SWITCH_INTERVAL_MS) return R.err("刚刚切换过，请十秒后再试");
        long activeId = longValue(group.get("active_member_id"));
        List<Map<String, Object>> ready = jdbc.queryForList("SELECT id, exit_ip, exit_owner FROM exit_member WHERE group_id = ? AND state = 'up' "
                + "AND approved = 1 AND id <> ? ORDER BY COALESCE(latency_ms, 999999), id", groupId, activeId);
        Map<String, Object> target = null;
        for (Map<String, Object> row : ready) {
            if (memberId == null || longValue(row.get("id")) == memberId) {
                target = row;
                break;
            }
        }
        if (target == null) {
            return R.err(memberId == null ? "暂时没有已就绪的备用出口，系统正在准备，请稍后再试" : "这个出口还没有就绪，无法切换");
        }
        String fromIp = string(group.get("active_exit_ip"));
        String toIp = string(target.get("exit_ip"));
        // 放掉原来的出口并把目标排到最前，节点收到新列表后会立刻改用目标
        if (activeId > 0) {
            jdbc.update("UPDATE exit_member SET state='idle', approved=0, error='手动切换后释放', retry_after=?, updated_time=? WHERE id=?",
                    now + RELEASED_COOLDOWN_MS, now, activeId);
        }
        jdbc.update("UPDATE exit_group SET active_member_id=?, active_exit_ip=?, active_owner=?, active_since=?, preferred_candidate_id=NULL, "
                + "updated_time=? WHERE id=?", target.get("id"), toIp, target.get("exit_owner"), now, now, groupId);
        event(groupId, longValue(group.get("node_id")), string(group.get("country")), "switch", fromIp, toIp,
                source + "：从 " + StringUtils.defaultString(fromIp, "原出口") + " 切换到 " + toIp + ownerSuffix(string(target.get("exit_owner"))));
        lastManualSwitchAt.put(groupId, now);
        try {
            reconcileGroup(jdbc.queryForList("SELECT * FROM exit_group WHERE id = ?", groupId).get(0));
        } catch (Exception e) {
            log.warn("Exit group {} manual switch push failed: {}", groupId, e.getMessage());
        }
        requestReconcile();
        Map<String, Object> view = groupView(jdbc.queryForList("SELECT * FROM exit_group WHERE id = ?", groupId).get(0), now);
        view.put("groupId", groupId);
        return R.ok(view);
    }

    /** 某个国家当前列表里的全部候选及其纯净度信息，包括被排除的非家宽地址。 */
    public R countryCandidates(String country) {
        String code = normalizeCountry(country);
        if (!code.matches("[A-Z]{2}")) return R.err("国家代码不正确");
        long now = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("country", code);
        result.put("countryName", countryName(code));
        result.put("groups", jdbc.queryForList("SELECT g.id, n.name AS nodeName FROM exit_group g JOIN node n ON n.id = g.node_id "
                + "WHERE g.country = ? AND g.status <> 'removing' ORDER BY g.id", code));
        result.put("candidates", jdbc.queryForList("SELECT c.id, c.host, c.port, c.proto, c.owner, c.asn, c.ip_kind AS kind, "
                        + "c.flagged_proxy AS flaggedProxy, c.list_sessions AS sessions, c.list_speed AS speed, c.list_uptime AS uptime, "
                        + "c.list_score AS score, i.ipqs_score AS ipqsScore, i.abuse_score AS abuseScore, i.reputation_at AS reputationAt "
                        + "FROM exit_candidate c LEFT JOIN exit_ip_info i ON i.ip = c.host WHERE c.country = ? AND c.last_seen >= ? "
                        + "ORDER BY (c.ip_kind = 'home') DESC, c.flagged_proxy, COALESCE(i.ipqs_score, 50), c.list_sessions, c.list_score DESC",
                code, now - CANDIDATE_FRESH_MS));
        return R.ok(result);
    }

    /** 用已配置的风险情报源对一个候选 IP 做深度检测。按次消耗情报源额度，所以只在手动点击时执行。 */
    public R reputation(String ip) {
        String address = ExitCandidateParser.publicIpv4(ip);
        if (address == null) return R.err("IP 格式不正确");
        Long known = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM exit_candidate WHERE host = ?) + "
                + "(SELECT COUNT(*) FROM exit_member WHERE exit_ip = ?)", Long.class, address, address);
        if (known == null || known == 0) return R.err("只能检测出口池里的 IP");
        JSONObject reputation = JSONObject.parseObject(ipQualityService.reputation(address).toJSONString());
        if (!reputation.getBooleanValue("configured")) {
            return R.err("还没有配置 IPQualityScore 或 AbuseIPDB 的密钥，可以在“IP 质量检测”页面配置后再检测");
        }
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE exit_ip_info SET ipqs_score=?, abuse_score=?, reputation_json=?, reputation_at=? WHERE ip=?",
                reputation.getInteger("ipqsScore"), reputation.getInteger("abuseScore"),
                reputation.getJSONObject("sources").toJSONString(), now, address);
        reputation.put("ip", address);
        reputation.put("reputationAt", now);
        return R.ok(reputation);
    }

    public Long groupId(Long nodeId, String country) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM exit_group WHERE node_id = ? AND country = ?", Long.class,
                nodeId, normalizeCountry(country));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 可以手动指定的候选出口。 */
    public R candidates(Long groupId) {
        if (groupId == null) return R.err("缺少出口编号");
        List<Map<String, Object>> groups = jdbc.queryForList("SELECT * FROM exit_group WHERE id = ?", groupId);
        if (groups.isEmpty()) return R.err("出口不存在");
        long now = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preferredCandidateId", groups.get(0).get("preferred_candidate_id"));
        result.put("candidates", jdbc.queryForList("SELECT c.id, c.host, c.owner, c.flagged_proxy AS flaggedProxy, c.list_sessions AS sessions, "
                        + "c.list_speed AS speed, c.list_uptime AS uptime, m.state, m.exit_ip AS exitIp, m.latency_ms AS latencyMs, "
                        + "m.success_count AS successCount, m.fail_count AS failCount, m.error, i.ipqs_score AS ipqsScore, "
                        + "i.abuse_score AS abuseScore "
                        + "FROM exit_candidate c LEFT JOIN exit_member m ON m.candidate_id = c.id AND m.group_id = ? "
                        + "LEFT JOIN exit_ip_info i ON i.ip = c.host "
                        + "WHERE c.country = ? AND c.ip_kind = ? AND c.last_seen >= ? "
                        + "ORDER BY FIELD(COALESCE(m.state,'new'),'up','connecting','new','idle','failed','rejected'), "
                        + "COALESCE(m.success_count,0) DESC, c.flagged_proxy, c.list_score DESC LIMIT 80",
                groupId, groups.get(0).get("country"), KIND_HOME, now - CANDIDATE_FRESH_MS));
        for (Map<String, Object> row : (List<Map<String, Object>>) result.get("candidates")) {
            if (row.get("error") != null) row.put("error", translate(string(row.get("error"))));
        }
        return R.ok(result);
    }

    /**
     * 指定使用某个候选出口。系统先连接并检测它，确认是家宽后再切换过去，期间继续使用当前出口；
     * 它连不上、不是家宽或者以后失效时，自动回到自动选择。
     */
    public synchronized R prefer(Long groupId, Long candidateId) {
        if (groupId == null || candidateId == null) return R.err("缺少参数");
        List<Map<String, Object>> groups = jdbc.queryForList("SELECT * FROM exit_group WHERE id = ?", groupId);
        if (groups.isEmpty()) return R.err("出口不存在");
        Map<String, Object> group = groups.get(0);
        long now = System.currentTimeMillis();
        List<Map<String, Object>> candidates = jdbc.queryForList("SELECT host FROM exit_candidate WHERE id = ? AND country = ? AND ip_kind = ? "
                + "AND last_seen >= ?", candidateId, group.get("country"), KIND_HOME, now - CANDIDATE_FRESH_MS);
        if (candidates.isEmpty()) return R.err("这个候选已经不在列表里了，请重新选择");
        jdbc.update("UPDATE exit_group SET preferred_candidate_id=?, updated_time=? WHERE id=?", candidateId, now, groupId);
        jdbc.update("UPDATE exit_member SET retry_after=NULL WHERE group_id=? AND candidate_id=? AND state NOT IN ('connecting','up')",
                groupId, candidateId);
        event(longValue(group.get("id")), longValue(group.get("node_id")), string(group.get("country")), "prefer", null,
                string(candidates.get(0).get("host")), "已指定出口 " + candidates.get(0).get("host") + "，正在连接和检测，通过后自动切换");
        requestReconcile();
        return R.ok();
    }

    /** 处理手动指定的出口：就绪后切换过去；不可用时取消指定。 */
    private void followPreference(Map<String, Object> group, long now) {
        if (group.get("preferred_candidate_id") == null) return;
        long groupId = longValue(group.get("id"));
        long candidateId = longValue(group.get("preferred_candidate_id"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, state, approved, exit_ip, error FROM exit_member WHERE group_id = ? "
                + "AND candidate_id = ?", groupId, candidateId);
        if (rows.isEmpty()) return;
        Map<String, Object> member = rows.get(0);
        String state = string(member.get("state"));
        if ("failed".equals(state) || "rejected".equals(state)) {
            jdbc.update("UPDATE exit_group SET preferred_candidate_id=NULL WHERE id=?", groupId);
            event(groupId, longValue(group.get("node_id")), string(group.get("country")), "prefer", null, null,
                    "指定的出口不可用（" + translate(string(member.get("error"))) + "），已恢复自动选择");
            return;
        }
        if ("up".equals(state) && longValue(member.get("approved")) == 1
                && longValue(member.get("id")) != longValue(group.get("active_member_id"))) {
            long activeId = longValue(group.get("active_member_id"));
            if (activeId > 0) {
                jdbc.update("UPDATE exit_member SET state='idle', approved=0, error='改用指定出口后释放', retry_after=?, updated_time=? WHERE id=?",
                        now + RELEASED_COOLDOWN_MS, now, activeId);
            }
            jdbc.update("UPDATE exit_group SET active_member_id=?, active_exit_ip=?, active_since=?, updated_time=? WHERE id=?",
                    member.get("id"), member.get("exit_ip"), now, now, groupId);
            event(groupId, longValue(group.get("node_id")), string(group.get("country")), "switch", string(group.get("active_exit_ip")),
                    string(member.get("exit_ip")), "已切换到指定的出口 " + member.get("exit_ip"));
            group.put("active_member_id", member.get("id"));
            group.put("active_exit_ip", member.get("exit_ip"));
        }
    }

    /** 某个国家当前是否有可用的家宽候选。 */
    public boolean hasCandidates(String country) {
        // 只读数据库：调用方持有端口分配锁，不能在这里等待外部网络
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM exit_candidate WHERE country = ? AND ip_kind = ? AND last_seen >= ?",
                Long.class, normalizeCountry(country), KIND_HOME, System.currentTimeMillis() - CANDIDATE_FRESH_MS);
        return count != null && count > 0;
    }

    /** 私人代理卡片上显示的出口状态。 */
    public Map<String, Object> exitView(Long nodeId, String country) {
        String code = normalizeCountry(country);
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM exit_group WHERE node_id = ? AND country = ?", nodeId, code);
        Map<String, Object> view = rows.isEmpty() ? new LinkedHashMap<>() : groupView(rows.get(0), now);
        if (rows.isEmpty()) {
            view.put("status", "searching");
            view.put("detail", "正在准备出口");
        } else {
            view.put("groupId", rows.get(0).get("id"));
        }
        view.put("country", code);
        view.put("countryName", countryName(code));
        return view;
    }

    private Map<String, Object> groupView(Map<String, Object> group, long now) {
        Map<String, Object> view = new LinkedHashMap<>();
        long groupId = longValue(group.get("id"));
        String country = string(group.get("country"));
        view.put("country", country);
        view.put("countryName", countryName(country));
        view.put("status", group.get("status"));
        view.put("detail", group.get("detail"));
        view.put("exitIp", group.get("active_exit_ip"));
        view.put("exitOwner", group.get("active_owner"));
        view.put("activeSince", group.get("active_since"));
        view.put("noExitSince", group.get("no_exit_since"));
        view.put("runtimeVersion", group.get("runtime_version"));
        view.put("preferredCandidateId", group.get("preferred_candidate_id"));
        view.put("standbyReady", jdbc.queryForObject("SELECT COUNT(*) FROM exit_member WHERE group_id = ? AND state = 'up' AND approved = 1 "
                + "AND id <> ?", Long.class, groupId, longValue(group.get("active_member_id"))));
        long dayStart = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        view.put("switchesToday", jdbc.queryForObject("SELECT COUNT(*) FROM exit_event WHERE group_id = ? AND type = 'switch' AND created_time >= ?",
                Long.class, groupId, dayStart));
        view.put("checkedAt", group.get("updated_time"));
        return view;
    }

    private List<Map<String, Object>> countryRows() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT country, COUNT(*) AS total, SUM(ip_kind = 'home') AS available "
                + "FROM exit_candidate WHERE last_seen >= ? GROUP BY country ORDER BY available DESC, total DESC",
                System.currentTimeMillis() - CANDIDATE_FRESH_MS);
        for (Map<String, Object> row : rows) {
            row.put("name", countryName(string(row.get("country"))));
            row.put("available", longValue(row.get("available")));
            row.put("total", longValue(row.get("total")));
        }
        return rows;
    }

    // ---------------------------------------------------------------- 候选收集与判定

    void refreshCandidatesIfDue(boolean force) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (!force && now - lastRefreshAttemptAt < LIST_REFRESH_MS) return;
            lastRefreshAttemptAt = now;
        }
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(listUrl))
                    .timeout(Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("出口列表返回 HTTP " + response.statusCode());
            List<ExitCandidateParser.Candidate> candidates = ExitCandidateParser.parseVpnGateList(response.body());
            if (candidates.isEmpty()) throw new IllegalStateException("出口列表为空或格式无法识别");
            for (ExitCandidateParser.Candidate candidate : candidates) {
                // 先更新再插入：已有的候选每十分钟刷新一次，不为它们消耗新的自增编号
                int updated = jdbc.update("UPDATE exit_candidate SET country=?, cipher=?, auth=?, ca=?, cert=?, client_key=?, list_score=?, "
                                + "list_sessions=?, list_speed=?, list_uptime=?, last_seen=? WHERE source=? AND host=? AND port=? AND proto=?",
                        candidate.getCountry(), candidate.getCipher(), candidate.getAuth(), candidate.getCa(), candidate.getCert(),
                        candidate.getKey(), candidate.getScore(), candidate.getSessions(), candidate.getSpeed(), candidate.getUptime(), now,
                        SOURCE_VPNGATE, candidate.getHost(), candidate.getPort(), candidate.getProto());
                if (updated > 0) continue;
                jdbc.update("INSERT IGNORE INTO exit_candidate (source,country,host,port,proto,cipher,auth,ca,cert,client_key,list_score,"
                                + "list_sessions,list_speed,list_uptime,first_seen,last_seen) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        SOURCE_VPNGATE, candidate.getCountry(), candidate.getHost(), candidate.getPort(), candidate.getProto(),
                        candidate.getCipher(), candidate.getAuth(), candidate.getCa(), candidate.getCert(), candidate.getKey(),
                        candidate.getScore(), candidate.getSessions(), candidate.getSpeed(), candidate.getUptime(), now, now);
            }
            classifyCandidates(now);
            lastRefreshAt = now;
            lastRefreshError = null;
        } catch (Exception e) {
            lastRefreshError = concise(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("Exit candidate refresh failed: {}", lastRefreshError);
        }
        cleanupIfDue(now);
    }

    private void classifyCandidates(long now) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, host, country FROM exit_candidate WHERE last_seen >= ? "
                + "AND (classified_at IS NULL OR classified_at < ?)", now - CANDIDATE_FRESH_MS, now - IP_INFO_TTL_MS);
        for (int start = 0; start < rows.size(); start += 100) {
            List<Map<String, Object>> batch = rows.subList(start, Math.min(rows.size(), start + 100));
            List<String> ips = new ArrayList<>();
            for (Map<String, Object> row : batch) ips.add(string(row.get("host")));
            Map<String, IpInfo> lookedUp = lookup(ips, now);
            for (Map<String, Object> row : batch) {
                IpInfo info = lookedUp.get(string(row.get("host")));
                if (info == null) continue;
                String kind = KIND_HOME.equals(info.kind) && !string(row.get("country")).equalsIgnoreCase(info.country)
                        ? KIND_MISMATCH : info.kind;
                jdbc.update("UPDATE exit_candidate SET owner=?, asn=?, ip_kind=?, flagged_proxy=?, classified_at=? WHERE id=?",
                        info.owner, info.asn, kind, info.flaggedProxy ? 1 : 0, now, row.get("id"));
            }
        }
    }

    /** 查询一个出口 IP 的归属；数据源暂时不可用时返回 null，由调用方稍后重试。 */
    IpInfo classifyIp(String ip, long now) {
        List<Map<String, Object>> cached = jdbc.queryForList("SELECT * FROM exit_ip_info WHERE ip = ? AND checked_at >= ?", ip, now - IP_INFO_TTL_MS);
        if (!cached.isEmpty()) {
            Map<String, Object> row = cached.get(0);
            return new IpInfo(string(row.get("country")), string(row.get("owner")), string(row.get("asn")),
                    string(row.get("kind")), longValue(row.get("flagged_proxy")) == 1);
        }
        // 归属查询的免费额度有限：同一个 IP 查询失败后等半分钟，两次查询之间至少隔几秒
        Long retryAt = lookupRetryAt.get(ip);
        if ((retryAt != null && now < retryAt) || now - lastLookupAt < LOOKUP_SPACING_MS) return null;
        IpInfo info = lookup(List.of(ip), now).get(ip);
        if (info == null) lookupRetryAt.put(ip, now + LOOKUP_RETRY_MS);
        else lookupRetryAt.remove(ip);
        return info;
    }

    private Map<String, IpInfo> lookup(List<String> ips, long now) {
        Map<String, IpInfo> result = new HashMap<>();
        if (ips.isEmpty()) return result;
        lastLookupAt = System.currentTimeMillis();
        try {
            String separator = lookupUrl.contains("?") ? "&" : "?";
            HttpRequest request = HttpRequest.newBuilder(URI.create(lookupUrl + separator
                            + "fields=status,query,countryCode,isp,org,as,hosting,mobile,proxy"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSONArray.toJSONString(ips))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            lastLookupError = null;
            JSONArray items = JSONArray.parseArray(response.body());
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                String ip = item.getString("query");
                if (ip == null || !"success".equals(item.getString("status"))) continue;
                String owner = StringUtils.left(StringUtils.defaultIfBlank(item.getString("isp"), item.getString("org")), 160);
                String asn = StringUtils.left(StringUtils.substringBefore(StringUtils.defaultString(item.getString("as")), " "), 32);
                String country = StringUtils.upperCase(item.getString("countryCode"));
                String kind = kindOf(ip, country, item.getBooleanValue("hosting"), item.getBooleanValue("mobile"),
                        item.getString("isp"), item.getString("org"));
                IpInfo info = new IpInfo(country, owner, asn, kind, item.getBooleanValue("proxy"));
                result.put(ip, info);
                jdbc.update("INSERT INTO exit_ip_info (ip,country,owner,asn,kind,flagged_proxy,checked_at) VALUES (?,?,?,?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE country=VALUES(country),owner=VALUES(owner),asn=VALUES(asn),kind=VALUES(kind),"
                                + "flagged_proxy=VALUES(flagged_proxy),checked_at=VALUES(checked_at)",
                        ip, country, owner, asn, kind, info.flaggedProxy ? 1 : 0, now);
            }
        } catch (Exception e) {
            lastLookupError = "IP 归属查询失败，暂时无法判定新出口是不是家宽：" + concise(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("Exit IP lookup failed: {}", e.getMessage());
        }
        return result;
    }

    /** 根据 IP 库结果判断网络类型。VPN Gate 项目方自己的服务器不是家宽，单独标出。 */
    static String kindOf(String ip, String country, boolean hosting, boolean mobile, String isp, String org) {
        String names = (StringUtils.defaultString(isp) + " " + StringUtils.defaultString(org)).toLowerCase(Locale.ROOT);
        if (StringUtils.startsWith(ip, "219.100.37.") || names.contains("softether") || names.contains("vpn gate")) return KIND_PROJECT;
        if (StringUtils.isBlank(country)) return KIND_UNKNOWN;
        if (hosting) return KIND_HOSTING;
        if (mobile) return KIND_MOBILE;
        return KIND_HOME;
    }

    static boolean acceptable(IpInfo info, String country) {
        return info != null && KIND_HOME.equals(info.kind) && StringUtils.equalsIgnoreCase(info.country, country);
    }

    private void cleanupIfDue(long now) {
        if (now - lastCleanupAt < 6 * 3_600_000L) return;
        lastCleanupAt = now;
        jdbc.update("DELETE FROM exit_event WHERE created_time < ?", now - EVENT_RETENTION_MS);
        jdbc.update("DELETE FROM exit_member WHERE state NOT IN ('connecting','up') AND updated_time < ? "
                + "AND (retry_after IS NULL OR retry_after < ?)", now - 7 * 86_400_000L, now);
        jdbc.update("DELETE FROM exit_candidate WHERE last_seen < ? AND id NOT IN (SELECT candidate_id FROM exit_member)", now - 7 * 86_400_000L);
        jdbc.update("DELETE FROM exit_ip_info WHERE checked_at < ?", now - 30 * 86_400_000L);
    }

    // ---------------------------------------------------------------- 调度

    /** 执行一轮调度；返回是否还有出口组在等待可用出口。 */
    synchronized boolean reconcile() {
        List<Map<String, Object>> wanted = jdbc.queryForList("SELECT DISTINCT node_id, UPPER(exit_country) AS country FROM private_proxy "
                + "WHERE exit_type = 'residential' AND exit_country IS NOT NULL AND exit_country <> '' AND state IN ('provisioning','active')");
        List<Map<String, Object>> groups = jdbc.queryForList("SELECT * FROM exit_group");
        if (wanted.isEmpty() && groups.isEmpty()) return false;

        long now = System.currentTimeMillis();
        Set<String> existingKeys = new HashSet<>();
        for (Map<String, Object> group : groups) existingKeys.add(group.get("node_id") + "/" + group.get("country"));
        Set<String> wantedKeys = new HashSet<>();
        for (Map<String, Object> row : wanted) {
            String key = row.get("node_id") + "/" + row.get("country");
            wantedKeys.add(key);
            if (existingKeys.contains(key)) continue;
            jdbc.update("INSERT IGNORE INTO exit_group (node_id,country,status,detail,created_time,updated_time) VALUES (?,?,?,?,?,?)",
                    row.get("node_id"), row.get("country"), "searching", "正在连接并检测候选出口", now, now);
        }
        if (!wanted.isEmpty()) refreshCandidatesIfDue(false);
        for (Map<String, Object> group : jdbc.queryForList("SELECT * FROM exit_group")) {
            try {
                if (wantedKeys.contains(group.get("node_id") + "/" + group.get("country"))) reconcileGroup(group);
                else removeGroup(group);
            } catch (Exception e) {
                log.warn("Exit group {} reconcile failed: {}", group.get("id"), e.getMessage());
            }
        }
        // 还在找出口或者还在补备用出口的组，需要尽快再看一次结果
        Long waiting = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM exit_group WHERE status = 'searching') + "
                + "(SELECT COUNT(*) FROM exit_member m JOIN exit_group g ON g.id = m.group_id "
                + "WHERE m.state = 'connecting' AND g.status = 'ok')", Long.class);
        return waiting != null && waiting > 0;
    }

    private void removeGroup(Map<String, Object> group) {
        long groupId = longValue(group.get("id"));
        long nodeId = longValue(group.get("node_id"));
        Node node = nodeMapper.selectById(nodeId);
        if (node != null) {
            if (!WebSocketServer.isNodeOnline(nodeId)) {
                setStatus(group, "removing", "已不再使用，等待节点上线后清理", System.currentTimeMillis());
                return;
            }
            if (AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)
                    && !success(GostUtil.DeleteExitPool(nodeId, groupName(string(group.get("country")))))) {
                setStatus(group, "removing", "已不再使用，节点清理失败，稍后重试", System.currentTimeMillis());
                return;
            }
        }
        jdbc.update("DELETE FROM exit_member WHERE group_id = ?", groupId);
        jdbc.update("DELETE FROM exit_group WHERE id = ?", groupId);
        syncedPayloads.remove(groupId);
    }

    private void reconcileGroup(Map<String, Object> group) {
        long now = System.currentTimeMillis();
        long groupId = longValue(group.get("id"));
        long nodeId = longValue(group.get("node_id"));
        String country = string(group.get("country"));
        Node node = nodeMapper.selectById(nodeId);
        if (node == null || !WebSocketServer.isNodeOnline(nodeId)) {
            setStatus(group, "node_offline", "出口节点离线", now);
            return;
        }
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            setStatus(group, "agent_outdated", "节点 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本", now);
            return;
        }
        JSONObject status = statusData(GostUtil.GetExitPoolStatus(nodeId, groupName(country)));
        if (status == null) {
            setStatus(group, "agent_error", "节点没有响应出口指令，稍后自动重试", now);
            return;
        }

        foldAgentStatus(groupId, nodeId, country, status, now);
        followPreference(group, now);
        Long preferred = group.get("preferred_candidate_id") == null ? null : longValue(group.get("preferred_candidate_id"));
        topUpMembers(groupId, country, now, StringUtils.isNotBlank(status.getString("setupError")), status.getString("activeId"), preferred);

        List<Map<String, Object>> members = jdbc.queryForList("SELECT m.*, c.host, c.port, c.proto, c.cipher, c.auth, c.ca, c.cert, c.client_key "
                + "FROM exit_member m JOIN exit_candidate c ON c.id = m.candidate_id WHERE m.group_id = ? AND m.state IN ('connecting','up') "
                + "ORDER BY (m.id = ?) DESC, m.approved DESC, COALESCE(m.latency_ms, 999999), m.id", groupId, longValue(group.get("active_member_id")));
        JSONObject payload = payload(country, members);
        String fingerprint = fingerprint(payload);
        Set<String> agentIds = new HashSet<>();
        JSONArray agentMembers = status.getJSONArray("members");
        for (int i = 0; agentMembers != null && i < agentMembers.size(); i++) agentIds.add(agentMembers.getJSONObject(i).getString("id"));
        Set<String> desiredIds = new HashSet<>();
        for (Map<String, Object> member : members) desiredIds.add(memberName(longValue(member.get("candidate_id"))));
        if (!fingerprint.equals(syncedPayloads.get(groupId)) || !agentIds.equals(desiredIds)) {
            JSONObject synced = statusData(GostUtil.SyncExitPool(nodeId, payload));
            if (synced == null) {
                syncedPayloads.remove(groupId);
                setStatus(group, "agent_error", "向节点下发出口列表失败，稍后自动重试", now);
                return;
            }
            syncedPayloads.put(groupId, fingerprint);
            status = synced;
        }
        recordActive(group, status, members, now);
    }

    /** 把节点上报的每个出口的状态写回数据库，并对新出现的出口 IP 做家宽判定。 */
    private void foldAgentStatus(long groupId, long nodeId, String country, JSONObject status, long now) {
        Map<String, JSONObject> reported = new HashMap<>();
        JSONArray agentMembers = status.getJSONArray("members");
        for (int i = 0; agentMembers != null && i < agentMembers.size(); i++) {
            JSONObject member = agentMembers.getJSONObject(i);
            reported.put(member.getString("id"), member);
        }
        String setupError = status.getString("setupError");
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM exit_member WHERE group_id = ? AND state IN ('connecting','up')", groupId)) {
            long memberId = longValue(row.get("id"));
            JSONObject member = reported.get(memberName(longValue(row.get("candidate_id"))));
            String state = member == null ? "connecting" : member.getString("state");
            if ("failed".equals(state) && StringUtils.isNotBlank(setupError)) {
                // 节点自身环境的问题，不记到候选头上，环境修好后可以立刻再试
                jdbc.update("UPDATE exit_member SET state='idle', approved=0, error=?, retry_after=NULL, updated_time=? WHERE id=?",
                        StringUtils.left(setupError, 300), now, memberId);
                continue;
            }
            if ("failed".equals(state)) {
                boolean wasUp = "up".equals(row.get("state"));
                int failures = (int) longValue(row.get("fail_count")) + 1;
                String error = StringUtils.left(member.getString("error"), 300);
                jdbc.update("UPDATE exit_member SET state='failed', approved=0, error=?, fail_count=?, retry_after=?, updated_time=? WHERE id=?",
                        error, failures, now + retryDelay(failures, wasUp), now, memberId);
                if (wasUp) {
                    event(groupId, nodeId, country, "fail", string(row.get("exit_ip")), null,
                            "出口 " + StringUtils.defaultString(string(row.get("exit_ip")), "") + " 失效：" + translate(error));
                }
                continue;
            }
            if (!"up".equals(state)) {
                if (now - longValue(row.get("updated_time")) > CONNECT_PATIENCE_MS && "connecting".equals(row.get("state"))) {
                    int failures = (int) longValue(row.get("fail_count")) + 1;
                    jdbc.update("UPDATE exit_member SET state='failed', error=?, fail_count=?, retry_after=?, updated_time=? WHERE id=?",
                            "连接超时", failures, now + retryDelay(failures, false), now, memberId);
                }
                continue;
            }
            String exitIp = member.getString("exitIp");
            IpInfo info = StringUtils.isBlank(exitIp) ? null : classifyIp(exitIp, now);
            if (info != null && !acceptable(info, country)) {
                jdbc.update("UPDATE exit_member SET state='rejected', approved=0, exit_ip=?, exit_owner=?, exit_kind=?, exit_country=?, "
                                + "exit_flagged=?, error=?, retry_after=?, updated_time=? WHERE id=?",
                        exitIp, info.owner, info.kind, info.country, info.flaggedProxy ? 1 : 0, rejectionReason(info, country),
                        now + REJECT_COOLDOWN_MS, now, memberId);
                event(groupId, nodeId, country, "reject", exitIp, null, "出口 " + exitIp + " 已排除：" + rejectionReason(info, country));
                continue;
            }
            boolean firstUp = !"up".equals(row.get("state"));
            jdbc.update("UPDATE exit_member SET state='up', exit_ip=?, exit_owner=?, exit_kind=?, exit_country=?, exit_flagged=?, approved=?, "
                            + "latency_ms=?, rx_bytes=?, tx_bytes=?, connected_at=?, last_ok_at=?, error=NULL, retry_after=NULL, "
                            + "success_count=success_count+?, fail_count=0, updated_time=? WHERE id=?",
                    exitIp, info == null ? row.get("exit_owner") : info.owner, info == null ? null : info.kind,
                    info == null ? null : info.country, info != null && info.flaggedProxy ? 1 : 0, info == null ? 0 : 1,
                    member.getLong("latencyMs"), member.getLongValue("rxBytes"), member.getLongValue("txBytes"),
                    member.getLong("connectedAt"), member.getLong("lastOkAt"), firstUp ? 1 : 0, now, memberId);
        }
    }

    /** 保证有足够的出口在连接或待命：没有可用出口时并行多试几个，有了之后只保留一个备用。 */
    private void topUpMembers(long groupId, String country, long now, boolean setupFailed, String activeName, Long preferred) {
        if (preferred != null) {
            // 指定的出口不受数量限制和冷却限制，直接加入
            Long live = jdbc.queryForObject("SELECT COUNT(*) FROM exit_member WHERE group_id = ? AND candidate_id = ? "
                    + "AND state IN ('connecting','up')", Long.class, groupId, preferred);
            if (live == null || live == 0) {
                jdbc.update("INSERT INTO exit_member (group_id,candidate_id,state,attempt,created_time,updated_time) VALUES (?,?,?,1,?,?) "
                                + "ON DUPLICATE KEY UPDATE state='connecting', approved=0, exit_ip=NULL, error=NULL, attempt=attempt+1, "
                                + "retry_after=NULL, updated_time=VALUES(updated_time)", groupId, preferred, "connecting", now, now);
            }
        }
        List<Map<String, Object>> live = jdbc.queryForList("SELECT id, candidate_id, state, approved, latency_ms FROM exit_member "
                + "WHERE group_id = ? AND state IN ('connecting','up')", groupId);
        long ready = live.stream().filter(row -> "up".equals(row.get("state")) && longValue(row.get("approved")) == 1).count();
        // 多出来的备用出口只在知道节点正在用哪一个之后才释放，避免把在用的那个释放掉
        if (ready > READY_TARGET && StringUtils.isNotBlank(activeName)) {
            live.stream().filter(row -> "up".equals(row.get("state")) && longValue(row.get("approved")) == 1
                            && !memberName(longValue(row.get("candidate_id"))).equals(activeName)
                            && (preferred == null || longValue(row.get("candidate_id")) != preferred))
                    .sorted(Comparator.comparingLong((Map<String, Object> row) -> row.get("latency_ms") == null
                            ? Long.MAX_VALUE : longValue(row.get("latency_ms"))).reversed())
                    .limit(ready - READY_TARGET)
                    .forEach(row -> jdbc.update("UPDATE exit_member SET state='idle', approved=0, updated_time=? WHERE id=?", now, row.get("id")));
            return;
        }
        int target = setupFailed ? 1 : ready >= READY_TARGET ? READY_TARGET : ready == 0 ? SEARCH_PARALLEL : STANDBY_SEARCH_PARALLEL;
        int missing = target - live.size();
        if (missing <= 0) return;
        List<Map<String, Object>> picks = jdbc.queryForList("SELECT c.id FROM exit_candidate c "
                        + "LEFT JOIN exit_member m ON m.candidate_id = c.id AND m.group_id = ? "
                        + "WHERE c.country = ? AND c.ip_kind = ? AND c.last_seen >= ? "
                        // 手动切换时放掉的出口本身是好的：平时先冷却，避免马上又换回去；一个可用出口都没有时立刻拿回来用
                        + "AND (m.id IS NULL OR (m.state NOT IN ('connecting','up') AND (m.retry_after IS NULL OR m.retry_after <= ? "
                        + "OR (m.state = 'idle' AND ? = 1)))) "
                        + "ORDER BY COALESCE(m.success_count, 0) DESC, c.flagged_proxy ASC, c.list_score DESC LIMIT ?",
                groupId, country, KIND_HOME, now - CANDIDATE_FRESH_MS, now, ready == 0 ? 1 : 0, missing);
        for (Map<String, Object> pick : picks) {
            // attempt 每次加一：节点据此知道这是一次新的连接请求，而不是它已经试过的那一次
            jdbc.update("INSERT INTO exit_member (group_id,candidate_id,state,attempt,created_time,updated_time) VALUES (?,?,?,1,?,?) "
                            + "ON DUPLICATE KEY UPDATE state='connecting', approved=0, exit_ip=NULL, error=NULL, attempt=attempt+1, "
                            + "updated_time=VALUES(updated_time)",
                    groupId, pick.get("id"), "connecting", now, now);
        }
    }

    private JSONObject payload(String country, List<Map<String, Object>> members) {
        JSONArray specs = new JSONArray();
        List<String> approved = new ArrayList<>();
        for (Map<String, Object> member : members) {
            JSONObject spec = new JSONObject(true);
            spec.put("id", memberName(longValue(member.get("candidate_id"))));
            spec.put("attempt", longValue(member.get("attempt")));
            spec.put("host", member.get("host"));
            spec.put("port", member.get("port"));
            spec.put("proto", member.get("proto"));
            spec.put("cipher", member.get("cipher"));
            spec.put("auth", member.get("auth"));
            spec.put("ca", member.get("ca"));
            if (member.get("cert") != null) spec.put("cert", member.get("cert"));
            if (member.get("client_key") != null) spec.put("key", member.get("client_key"));
            specs.add(spec);
            if (longValue(member.get("approved")) == 1 && member.get("exit_ip") != null) approved.add(string(member.get("exit_ip")));
        }
        approved.sort(Comparator.naturalOrder());
        JSONObject payload = new JSONObject(true);
        payload.put("group", groupName(country));
        payload.put("members", specs);
        payload.put("approvedIps", approved);
        return payload;
    }

    /** 记录节点当前使用的出口，并在它发生变化时写入切换记录。 */
    private void recordActive(Map<String, Object> group, JSONObject status, List<Map<String, Object>> members, long now) {
        long groupId = longValue(group.get("id"));
        long nodeId = longValue(group.get("node_id"));
        String country = string(group.get("country"));
        String activeName = status.getString("activeId");
        Map<String, Object> active = null;
        boolean connecting = false;
        for (Map<String, Object> member : members) {
            if (memberName(longValue(member.get("candidate_id"))).equals(activeName) && longValue(member.get("approved")) == 1) active = member;
            if ("connecting".equals(member.get("state")) || longValue(member.get("approved")) != 1) connecting = true;
        }
        Long previousId = group.get("active_member_id") == null ? null : longValue(group.get("active_member_id"));
        String previousIp = string(group.get("active_exit_ip"));
        Long activeId = active == null ? null : longValue(active.get("id"));
        String activeIp = active == null ? null : string(active.get("exit_ip"));
        String runtimeVersion = StringUtils.left(status.getString("runtimeVersion"), 32);
        String setupError = status.getString("setupError");

        if (!Objects.equals(previousId, activeId)) {
            if (active != null && previousId == null) {
                event(groupId, nodeId, country, "activate", null, activeIp,
                        "开始使用出口 " + activeIp + ownerSuffix(string(active.get("exit_owner"))));
            } else if (active != null) {
                event(groupId, nodeId, country, "switch", previousIp, activeIp, "从 " + StringUtils.defaultString(previousIp, "原出口")
                        + " 切换到 " + activeIp + ownerSuffix(string(active.get("exit_owner"))) + "：" + leaveReason(previousId));
            } else {
                event(groupId, nodeId, country, "lost", previousIp, null, "出口 " + StringUtils.defaultString(previousIp, "")
                        + " 不可用且暂无备用，代理已断开，不会改用节点自身 IP：" + leaveReason(previousId));
            }
        }

        String state;
        String detail = null;
        if (active != null) {
            state = "ok";
        } else if (StringUtils.isNotBlank(setupError)) {
            state = "setup_failed";
            detail = "节点环境不满足：" + translate(setupError);
        } else if (connecting) {
            state = "searching";
            detail = "正在连接并检测候选出口";
        } else {
            state = "no_exit";
            detail = hasCandidatesNow(country, now) ? "候选出口暂时都连不上，稍后自动重试" : "当前没有" + countryName(country) + "的家宽候选";
        }
        Long noExitSince = active != null ? null : group.get("no_exit_since") == null ? Long.valueOf(now) : Long.valueOf(longValue(group.get("no_exit_since")));
        Long activeSince = active == null ? null : Objects.equals(previousId, activeId) && group.get("active_since") != null
                ? Long.valueOf(longValue(group.get("active_since"))) : Long.valueOf(now);
        jdbc.update("UPDATE exit_group SET status=?, detail=?, active_member_id=?, active_exit_ip=?, active_owner=?, active_since=?, "
                        + "no_exit_since=?, runtime_version=?, updated_time=? WHERE id=?",
                state, detail, activeId, activeIp, active == null ? null : active.get("exit_owner"), activeSince, noExitSince,
                runtimeVersion, now, groupId);
    }

    private boolean hasCandidatesNow(String country, long now) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM exit_candidate WHERE country = ? AND ip_kind = ? AND last_seen >= ?",
                Long.class, country, KIND_HOME, now - CANDIDATE_FRESH_MS);
        return count != null && count > 0;
    }

    private String leaveReason(Long memberId) {
        if (memberId == null) return "原出口不可用";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT state, error FROM exit_member WHERE id = ?", memberId);
        if (rows.isEmpty()) return "原出口已移除";
        String state = string(rows.get(0).get("state"));
        String error = string(rows.get(0).get("error"));
        if ("failed".equals(state)) return "原出口失效（" + translate(error) + "）";
        if ("rejected".equals(state)) return "原出口不再符合要求（" + StringUtils.defaultString(error, "已排除") + "）";
        return "原出口不可用";
    }

    private void setStatus(Map<String, Object> group, String status, String detail, long now) {
        Long noExitSince = group.get("no_exit_since") == null ? Long.valueOf(now) : Long.valueOf(longValue(group.get("no_exit_since")));
        jdbc.update("UPDATE exit_group SET status=?, detail=?, no_exit_since=?, updated_time=? WHERE id=?",
                status, detail, noExitSince, now, group.get("id"));
    }

    private void event(long groupId, long nodeId, String country, String type, String fromIp, String toIp, String message) {
        jdbc.update("INSERT INTO exit_event (group_id,node_id,country,type,from_ip,to_ip,message,created_time) VALUES (?,?,?,?,?,?,?,?)",
                groupId, nodeId, country, type, fromIp, toIp, StringUtils.left(message, 500), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 失败的候选隔一段时间再试，连续失败时间隔加倍，最长六小时。
     * 用过一段时间才断开的出口多半只是临时中断，一分钟后就可以再试；从没连上过的要等十分钟起。
     */
    static long retryDelay(int failures, boolean wasWorking) {
        long base = wasWorking ? 60_000L : 10 * 60_000L;
        long delay = base << Math.min(Math.max(failures - 1, 0), 9);
        return Math.min(delay, REJECT_COOLDOWN_MS);
    }

    static String rejectionReason(IpInfo info, String country) {
        if (!StringUtils.equalsIgnoreCase(info.country, country)) {
            return "出口 IP 位于" + countryName(info.country) + "，不是" + countryName(country);
        }
        switch (info.kind) {
            case KIND_HOSTING: return "出口 IP 属于机房网络（" + StringUtils.defaultString(info.owner, "未知运营商") + "）";
            case KIND_MOBILE: return "出口 IP 属于移动网络（" + StringUtils.defaultString(info.owner, "未知运营商") + "）";
            case KIND_PROJECT: return "出口 IP 属于 VPN Gate 项目方网络";
            default: return "无法确认出口 IP 是家宽";
        }
    }

    /** 把节点返回的常见英文失败原因转成中文，其余原样显示。 */
    static String translate(String error) {
        if (StringUtils.isBlank(error)) return "未知原因";
        String lower = error.toLowerCase(Locale.ROOT);
        if (lower.contains("timed out") || lower.contains("did not connect in time")) return "连接超时";
        if (lower.contains("connection refused")) return "对方拒绝连接";
        if (lower.contains("auth_failed")) return "对方拒绝了连接请求";
        if (lower.contains("tunnel closed")) return "连接中断";
        if (lower.contains("stopped responding")) return "出口不再响应";
        if (lower.contains("carried no traffic")) return "能连上但不通流量";
        if (lower.contains("no tun device")) return "节点不支持 TUN 虚拟网卡";
        if (lower.contains("openvpn 2.5 or newer")) return "节点上的 OpenVPN 版本过低，需要 2.5 或更高";
        if (lower.contains("automatic openvpn installation failed") || lower.contains("no supported package manager")) {
            return "自动安装 OpenVPN 失败，请在节点上手动安装 openvpn";
        }
        if (lower.contains("overlaps local network")) return "出口分配的地址与节点内网冲突";
        if (lower.contains("unable to read the openvpn version")) return "节点上的 OpenVPN 无法运行";
        if (lower.contains("executable was not found")) return "已安装 OpenVPN 但找不到它的程序文件";
        if (lower.contains("iproute2")) return "节点缺少 ip 命令（iproute2）";
        if (lower.contains("supported only on linux")) return "家宽出口只支持 Linux 节点";
        if (lower.contains("connection failed")) return "连接失败";
        return error;
    }

    public static String countryName(String country) {
        if (StringUtils.isBlank(country)) return "未知地区";
        String code = country.toUpperCase(Locale.ROOT);
        String known = COUNTRY_NAMES.get(code);
        if (known != null) return known;
        String localized = new Locale("", code).getDisplayCountry(Locale.SIMPLIFIED_CHINESE);
        return StringUtils.isBlank(localized) ? code : localized;
    }

    public static String normalizeCountry(String country) {
        return StringUtils.trimToEmpty(country).toUpperCase(Locale.ROOT);
    }

    /** 节点上的出口组名称，也是私人代理服务绑定的出口引用。 */
    public static String groupName(String country) {
        return normalizeCountry(country).toLowerCase(Locale.ROOT);
    }

    public static String interfaceName(String country) {
        return "cnexit:" + groupName(country);
    }

    private static String memberName(long candidateId) {
        return "c" + candidateId;
    }

    private static String ownerSuffix(String owner) {
        return StringUtils.isBlank(owner) ? "" : "（" + owner + "）";
    }

    private static boolean success(GostDto result) {
        return result != null && "OK".equals(result.getMsg());
    }

    private static JSONObject statusData(GostDto result) {
        if (!success(result) || result.getData() == null) return null;
        return JSONObject.parseObject(JSONObject.toJSONString(result.getData()));
    }

    private static String fingerprint(JSONObject payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.toJSONString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return payload.toJSONString();
        }
    }

    private static String concise(String value) {
        return StringUtils.left(StringUtils.trimToEmpty(value), 300);
    }

    private static long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof Boolean) return (Boolean) value ? 1L : 0L;
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    static final class IpInfo {
        final String country;
        final String owner;
        final String asn;
        final String kind;
        final boolean flaggedProxy;

        IpInfo(String country, String owner, String asn, String kind, boolean flaggedProxy) {
            this.country = country;
            this.owner = owner;
            this.asn = asn;
            this.kind = kind;
            this.flaggedProxy = flaggedProxy;
        }
    }
}
