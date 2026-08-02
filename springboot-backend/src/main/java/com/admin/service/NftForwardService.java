package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.NftForwardSaveDto;
import com.admin.common.dto.PortLedgerEntryDto;
import com.admin.common.dto.PortLedgerQueryDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NftForwardService {
    public static final String MIN_AGENT_VERSION = "2.43.1";

    private static final int MAX_SOURCE_CIDRS = 64;
    private static final AtomicLong GENERATION = new AtomicLong(System.currentTimeMillis());

    private final JdbcTemplate jdbcTemplate;
    private final PortLedgerService portLedgerService;
    private final NftForwardAgentClient agentClient;
    private final Map<Long, Object> nodeLocks = new ConcurrentHashMap<>();

    public NftForwardService(JdbcTemplate jdbcTemplate,
                             PortLedgerService portLedgerService,
                             NftForwardAgentClient agentClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.portLedgerService = portLedgerService;
        this.agentClient = agentClient;
    }

    public R overview() {
        List<Map<String, Object>> rules = loadRules();
        List<Map<String, Object>> nodes = jdbcTemplate.queryForList(
                "SELECT id,name,server_ip AS serverIp,ip,status,version FROM node ORDER BY name,id");
        for (Map<String, Object> node : nodes) {
            Long nodeId = numberObject(node.get("id"));
            boolean online = nodeId != null && truth(node.get("status")) && agentClient.isOnline(nodeId);
            node.put("online", online);
            node.put("compatible", AgentVersionUtil.isAtLeast(Objects.toString(node.get("version"), ""), MIN_AGENT_VERSION));
        }
        for (Map<String, Object> rule : rules) {
            Long nodeId = numberObject(rule.get("nodeId"));
            rule.put("enabled", truth(rule.get("enabled")));
            rule.put("nodeOnline", nodeId != null && agentClient.isOnline(nodeId));
            rule.put("rollbackAvailable", StringUtils.isNotBlank(Objects.toString(rule.remove("lastGoodConfig"), "")));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", rules.size());
        summary.put("active", rules.stream().filter(item -> "active".equals(item.get("state"))).count());
        summary.put("paused", rules.stream().filter(item -> "paused".equals(item.get("state"))).count());
        summary.put("errors", rules.stream().filter(item -> List.of("error", "delete_pending").contains(item.get("state"))).count());
        summary.put("packets", rules.stream().mapToLong(item -> number(item.get("packetCount"))).sum());
        summary.put("bytes", rules.stream().mapToLong(item -> number(item.get("byteCount"))).sum());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rules", rules);
        result.put("nodes", nodes);
        result.put("summary", summary);
        result.put("minimumAgentVersion", MIN_AGENT_VERSION);
        return R.ok(result);
    }

    public R preflight(NftForwardSaveDto dto) {
        try {
            Normalized rule = normalize(dto);
            Map<String, Object> node = requireNode(rule.nodeId());
            ensureNodeReady(node);
            ensureLedgerAvailable(rule, dto.getId());
            return R.ok(runPreflight(rule, node, dto.getId()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.err(e.getMessage());
        }
    }

    public R save(NftForwardSaveDto dto) {
        if (dto == null) return R.err("配置不能为空");
        try {
            Normalized normalized = normalize(dto);
            Object lock = nodeLocks.computeIfAbsent(normalized.nodeId(), ignored -> new Object());
            synchronized (lock) {
                return saveLocked(dto, normalized);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.err(e.getMessage());
        }
    }

    private R saveLocked(NftForwardSaveDto dto, Normalized normalized) {
        Map<String, Object> old = dto.getId() == null ? null : rule(dto.getId());
        if (dto.getId() != null && old == null) return R.err("nftables 转发规则不存在");
        if (old != null && number(old.get("node_id")) != normalized.nodeId()) {
            return R.err("已创建规则不能更换执行节点；请新建规则并删除旧规则");
        }

        Map<String, Object> node = requireNode(normalized.nodeId());
        ensureNodeReady(node);
        ensureLedgerAvailable(normalized, dto.getId());
        JSONObject preflight = normalized.enabled() ? runPreflight(normalized, node, dto.getId()) : new JSONObject();
        String warning = warnings(preflight);
        long now = System.currentTimeMillis();
        Long id = dto.getId();
        String previousConfig = rollbackSnapshot(old);

        if (id == null) {
            jdbcTemplate.update("INSERT INTO nft_forward_rule "
                            + "(user_id,name,node_id,listen_address,listen_port,protocol,target_address,target_port,nat_mode,source_cidrs,"
                            + "enabled,state,last_warning,created_time,updated_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,'provisioning',?,?,?)",
                    JwtUtil.getUserIdFromToken(), normalized.name(), normalized.nodeId(), normalized.listenAddress(), normalized.listenPort(),
                    normalized.protocol(), normalized.targetAddress(), normalized.targetPort(), normalized.natMode(), normalized.sourceCidrs(),
                    normalized.enabled(), StringUtils.trimToNull(warning), now, now);
            id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            event(id, normalized.nodeId(), "create", "pending", "规则已保存，等待 Agent 原子应用");
        } else {
            jdbcTemplate.update("UPDATE nft_forward_rule SET name=?,listen_address=?,listen_port=?,protocol=?,target_address=?,target_port=?,"
                            + "nat_mode=?,source_cidrs=?,enabled=?,state='provisioning',last_error=NULL,last_warning=?,last_good_config=?,updated_time=? WHERE id=?",
                    normalized.name(), normalized.listenAddress(), normalized.listenPort(), normalized.protocol(), normalized.targetAddress(),
                    normalized.targetPort(), normalized.natMode(), normalized.sourceCidrs(), normalized.enabled(), StringUtils.trimToNull(warning),
                    previousConfig, now, id);
            event(id, normalized.nodeId(), "update", "pending", "规则配置已更新，旧配置已保存用于回退");
        }

        try {
            syncNode(normalized.nodeId());
            event(id, normalized.nodeId(), normalized.enabled() ? "apply" : "pause", "success", "Agent 规则与面板期望状态一致");
            return R.ok(Map.of("id", id, "state", normalized.enabled() ? "active" : "paused"));
        } catch (IllegalStateException e) {
            markRuleError(id, e.getMessage());
            event(id, normalized.nodeId(), "apply", "failed", shorten(e.getMessage()));
            return R.err("配置已保存但没有生效，Agent 保留上一份成功规则：" + e.getMessage());
        }
    }

    public R toggle(Long id, boolean enabled) {
        Map<String, Object> row = rule(id);
        if (row == null) return R.err("nftables 转发规则不存在");
        NftForwardSaveDto dto = dtoFromRow(row);
        dto.setEnabled(enabled);
        return save(dto);
    }

    public R check(Long id) {
        Map<String, Object> row = rule(id);
        if (row == null) return R.err("nftables 转发规则不存在");
        Long nodeId = numberObject(row.get("node_id"));
        Object lock = nodeLocks.computeIfAbsent(nodeId, ignored -> new Object());
        synchronized (lock) {
            try {
                if (!refreshNodeStatus(nodeId)) syncNode(nodeId);
                Map<String, Object> refreshed = rule(id);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", id);
                result.put("state", refreshed.get("state"));
                result.put("lastSyncedAt", refreshed.get("last_synced_at"));
                result.put("packetCount", refreshed.get("packet_count"));
                result.put("byteCount", refreshed.get("byte_count"));
                if (Objects.toString(row.get("protocol"), "").contains("tcp")) {
                    GostDto probe = agentClient.tcpProbe(nodeId, Objects.toString(row.get("target_address")), ((Number) row.get("target_port")).intValue());
                    result.put("targetProbe", responseData(probe));
                }
                event(id, nodeId, "check", "success", "规则与目标状态检查完成");
                return R.ok(result);
            } catch (IllegalStateException e) {
                markRuleError(id, e.getMessage());
                event(id, nodeId, "check", "failed", shorten(e.getMessage()));
                return R.err(e.getMessage());
            }
        }
    }

    public R rollback(Long id) {
        Map<String, Object> row = rule(id);
        if (row == null) return R.err("nftables 转发规则不存在");
        String snapshot = StringUtils.trimToNull(Objects.toString(row.get("last_good_config"), null));
        if (snapshot == null) return R.err("这条规则还没有可回退的历史配置");
        try {
            NftForwardSaveDto dto = JSON.parseObject(snapshot, NftForwardSaveDto.class);
            dto.setId(id);
            R result = save(dto);
            if (result.getCode() == 0) {
                event(id, numberObject(row.get("node_id")), "rollback", "success", "已恢复到上次成功配置");
            }
            return result;
        } catch (Exception e) {
            return R.err("历史配置无法读取：" + e.getMessage());
        }
    }

    public R delete(Long id) {
        Map<String, Object> row = rule(id);
        if (row == null) return R.err("nftables 转发规则不存在");
        Long nodeId = numberObject(row.get("node_id"));
        Object lock = nodeLocks.computeIfAbsent(nodeId, ignored -> new Object());
        synchronized (lock) {
            long now = System.currentTimeMillis();
            jdbcTemplate.update("UPDATE nft_forward_rule SET enabled=0,state='delete_pending',last_error=NULL,updated_time=? WHERE id=?", now, id);
            event(id, nodeId, "delete", "pending", "等待 Agent 删除规则；完成前端口继续保留");
            try {
                syncNode(nodeId);
                jdbcTemplate.update("UPDATE nft_forward_rule SET state='deleted',last_error=NULL,last_synced_at=?,updated_time=? WHERE id=?", now, now, id);
                event(id, nodeId, "delete", "success", "Agent 规则已删除，端口已释放");
                return R.ok();
            } catch (IllegalStateException e) {
                jdbcTemplate.update("UPDATE nft_forward_rule SET last_error=?,updated_time=? WHERE id=?", shorten(e.getMessage()), now, id);
                event(id, nodeId, "delete", "failed", shorten(e.getMessage()));
                return R.err("Agent 尚未删除规则，端口继续保留；节点恢复后可重试：" + e.getMessage());
            }
        }
    }

    public R events(Long id) {
        if (id == null) return R.err("nftables 转发规则不存在");
        return R.ok(jdbcTemplate.queryForList(
                "SELECT id,rule_id AS ruleId,node_id AS nodeId,event_type AS eventType,status,detail,created_time AS createdTime "
                        + "FROM nft_forward_event WHERE rule_id=? ORDER BY created_time DESC,id DESC LIMIT 100", id));
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void reconcilePending() {
        List<Long> nodes = jdbcTemplate.queryForList(
                "SELECT DISTINCT node_id FROM nft_forward_rule WHERE state IN ('provisioning','error','delete_pending')", Long.class);
        for (Long nodeId : nodes) {
            if (nodeId == null || !agentClient.isOnline(nodeId)) continue;
            Object lock = nodeLocks.computeIfAbsent(nodeId, ignored -> new Object());
            synchronized (lock) {
                try {
                    syncNode(nodeId);
                } catch (Exception e) {
                    log.debug("nftables pending reconciliation failed for node {}: {}", nodeId, e.getMessage());
                }
            }
        }
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 120_000L)
    public void detectDriftAndCollectCounters() {
        List<Long> nodes = jdbcTemplate.queryForList(
                "SELECT DISTINCT node_id FROM nft_forward_rule WHERE state NOT IN ('deleted','delete_pending')", Long.class);
        for (Long nodeId : nodes) {
            if (nodeId == null || !agentClient.isOnline(nodeId)) continue;
            Object lock = nodeLocks.computeIfAbsent(nodeId, ignored -> new Object());
            synchronized (lock) {
                try {
                    if (!refreshNodeStatus(nodeId)) syncNode(nodeId);
                } catch (Exception e) {
                    log.debug("nftables drift check failed for node {}: {}", nodeId, e.getMessage());
                }
            }
        }
    }

    private void syncNode(Long nodeId) {
        Map<String, Object> node = requireNode(nodeId);
        ensureNodeReady(node);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM nft_forward_rule WHERE node_id=? AND state NOT IN ('deleted','delete_pending') ORDER BY id", nodeId);
        long generation = nextGeneration();
        JSONArray rules = new JSONArray();
        for (Map<String, Object> row : rows) {
            if (!truth(row.get("enabled"))) continue;
            rules.add(agentRule(row));
        }
        JSONObject payload = new JSONObject();
        payload.put("generation", generation);
        payload.put("rules", rules);
        GostDto response = agentClient.apply(nodeId, payload);
        JSONObject data = requireOK(response, "Agent 应用 nftables 规则失败");
        applyAgentStatus(nodeId, generation, data);
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE nft_forward_rule SET state='deleted',last_error=NULL,last_synced_at=?,updated_time=? "
                + "WHERE node_id=? AND state='delete_pending'", now, now, nodeId);
    }

    private boolean refreshNodeStatus(Long nodeId) {
        GostDto response = agentClient.status(nodeId);
        JSONObject data = requireOK(response, "Agent 读取 nftables 状态失败");
        Long expected = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(generation),0) FROM nft_forward_rule WHERE node_id=? AND state NOT IN ('deleted','delete_pending')",
                Long.class, nodeId);
        long actual = number(data.get("generation"));
        Long expectedRuleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nft_forward_rule WHERE node_id=? AND enabled=1 AND state NOT IN ('deleted','delete_pending')",
                Long.class, nodeId);
        boolean expectedRules = expectedRuleCount != null && expectedRuleCount > 0;
        boolean tablePresent = truth(data.get("tablePresent"));
        if (actual != (expected == null ? 0L : expected) || (expectedRules && !tablePresent)) return false;
        applyAgentStatus(nodeId, actual, data);
        return allExpectedRulesApplied(nodeId, data);
    }

    private void applyAgentStatus(Long nodeId, long generation, JSONObject data) {
        Map<Long, CounterState> states = statusByRule(data.getJSONArray("rules"));
        String hash = StringUtils.trimToNull(data.getString("appliedHash"));
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,enabled,state,protocol FROM nft_forward_rule WHERE node_id=? AND state<>'deleted'", nodeId);
        for (Map<String, Object> row : rows) {
            long id = number(row.get("id"));
            if ("delete_pending".equals(row.get("state"))) continue;
            boolean enabled = truth(row.get("enabled"));
            CounterState state = states.getOrDefault(id, CounterState.EMPTY);
            boolean applied = !enabled || state.appliedFor(Objects.toString(row.get("protocol"), "tcp"));
            String nextState = enabled ? (applied ? "active" : "error") : "paused";
            String error = enabled && !applied ? "Agent 返回的实际规则不完整，等待重新同步" : null;
            jdbcTemplate.update("UPDATE nft_forward_rule SET state=?,generation=?,applied_hash=?,packet_count=?,byte_count=?,"
                            + "last_error=?,last_synced_at=?,updated_time=? WHERE id=?",
                    nextState, generation, hash, state.packets(), state.bytes(), error, now, now, id);
        }
    }

    private boolean allExpectedRulesApplied(Long nodeId, JSONObject data) {
        Map<Long, CounterState> states = statusByRule(data.getJSONArray("rules"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id,protocol FROM nft_forward_rule WHERE node_id=? AND enabled=1 AND state NOT IN ('deleted','delete_pending')", nodeId)) {
            CounterState state = states.get(number(row.get("id")));
            if (state == null || !state.appliedFor(Objects.toString(row.get("protocol"), "tcp"))) return false;
        }
        return true;
    }

    private Map<Long, CounterState> statusByRule(JSONArray items) {
        Map<Long, CounterState> result = new LinkedHashMap<>();
        if (items == null) return result;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            long id = item.getLongValue("id");
            CounterState current = result.getOrDefault(id, CounterState.EMPTY);
            String protocol = item.getString("protocol");
            result.put(id, current.add(protocol, item.getBooleanValue("applied"), item.getLongValue("packets"), item.getLongValue("bytes")));
        }
        return result;
    }

    private JSONObject runPreflight(Normalized rule, Map<String, Object> node, Long currentId) {
        List<AgentPortCheckUtil.Check> socketChecks = protocols(rule.protocol()).stream()
                .map(protocol -> new AgentPortCheckUtil.Check(protocol, rule.listenAddress(), rule.listenPort()))
                .collect(Collectors.toList());
        AgentPortCheckUtil.Result portCheck = AgentPortCheckUtil.check(nodeEntity(node), socketChecks);
        if (!portCheck.isChecked()) throw new IllegalStateException("Agent 没有完成系统端口检查");
        if (!portCheck.isAvailable()) throw new IllegalStateException(portCheck.getMessage() + conflictDetail(portCheck.getConflicts()));

        JSONArray checks = new JSONArray();
        for (String protocol : protocols(rule.protocol())) {
            checks.add(new JSONObject(Map.of("protocol", protocol, "listenAddress", rule.listenAddress(), "listenPort", rule.listenPort())));
        }
        GostDto response = agentClient.preflight(rule.nodeId(), new JSONObject(Map.of("checks", checks)));
        JSONObject data = requireOK(response, "Agent nftables 环境检查失败");
        if (!data.getBooleanValue("supported")) throw new IllegalStateException("该节点不支持 nftables 内核转发");
        if (!data.getBooleanValue("available")) {
            JSONArray conflicts = data.getJSONArray("conflicts");
            String detail = conflicts == null || conflicts.isEmpty() ? "服务器上已有冲突规则" : conflicts.stream()
                    .map(item -> ((JSONObject) item).getString("detail")).filter(StringUtils::isNotBlank).distinct().collect(Collectors.joining("；"));
            throw new IllegalStateException(detail);
        }
        data.put("currentRuleId", currentId);
        return data;
    }

    private void ensureLedgerAvailable(Normalized rule, Long currentId) {
        PortLedgerQueryDto query = new PortLedgerQueryDto();
        query.setNodeId(rule.nodeId());
        query.setPort(rule.listenPort());
        @SuppressWarnings("unchecked")
        List<PortLedgerEntryDto> entries = (List<PortLedgerEntryDto>) portLedgerService.list(query).get("entries");
        for (PortLedgerEntryDto entry : entries) {
            if ("nft_forward".equals(entry.getType()) && Objects.equals(currentId, entry.getResourceId())) continue;
            if (protocolOverlap(rule.protocol(), entry.getProtocol())) {
                throw new IllegalStateException("入口端口已被占用：" + entry.getResourceName() + "（" + entry.getDetail() + "）");
            }
        }
        List<Map<String, Object>> nftRules = jdbcTemplate.queryForList(
                "SELECT id,name,listen_address,protocol FROM nft_forward_rule WHERE node_id=? AND listen_port=? AND state<>'deleted' "
                        + "AND (? IS NULL OR id<>?)",
                rule.nodeId(), rule.listenPort(), currentId, currentId);
        for (Map<String, Object> existing : nftRules) {
            String address = Objects.toString(existing.get("listen_address"), "0.0.0.0");
            if (("0.0.0.0".equals(address) || "0.0.0.0".equals(rule.listenAddress()) || address.equals(rule.listenAddress()))
                    && protocolOverlap(rule.protocol(), Objects.toString(existing.get("protocol"), "tcp"))) {
                throw new IllegalStateException("监听地址和端口已被 nftables 规则“" + existing.get("name") + "”占用");
            }
        }
    }

    private Map<String, Object> requireNode(Long nodeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,name,server_ip,ip,status,version FROM node WHERE id=?", nodeId);
        if (rows.isEmpty()) throw new IllegalStateException("执行节点不存在");
        return rows.get(0);
    }

    private void ensureNodeReady(Map<String, Object> node) {
        Long nodeId = numberObject(node.get("id"));
        if (!truth(node.get("status")) || nodeId == null || !agentClient.isOnline(nodeId)) {
            throw new IllegalStateException("执行节点已离线");
        }
        if (!AgentVersionUtil.isAtLeast(Objects.toString(node.get("version"), ""), MIN_AGENT_VERSION)) {
            throw new IllegalStateException("执行节点 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }
    }

    private List<Map<String, Object>> loadRules() {
        return jdbcTemplate.queryForList(
                "SELECT r.id,r.user_id AS userId,r.name,r.node_id AS nodeId,n.name AS nodeName,"
                        + "COALESCE(NULLIF(n.server_ip,''),n.ip) AS publicHost,n.version AS agentVersion,"
                        + "r.listen_address AS listenAddress,r.listen_port AS listenPort,r.protocol,r.target_address AS targetAddress,"
                        + "r.target_port AS targetPort,r.nat_mode AS natMode,r.source_cidrs AS sourceCidrs,r.enabled,r.state,"
                        + "r.generation,r.applied_hash AS appliedHash,r.packet_count AS packetCount,r.byte_count AS byteCount,"
                        + "r.last_error AS lastError,r.last_warning AS lastWarning,r.last_good_config AS lastGoodConfig,"
                        + "r.last_synced_at AS lastSyncedAt,r.created_time AS createdTime,r.updated_time AS updatedTime "
                        + "FROM nft_forward_rule r LEFT JOIN node n ON n.id=r.node_id WHERE r.state<>'deleted' ORDER BY r.created_time DESC,r.id DESC");
    }

    private Map<String, Object> rule(Long id) {
        if (id == null) return null;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM nft_forward_rule WHERE id=? AND state<>'deleted'", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private JSONObject agentRule(Map<String, Object> row) {
        JSONObject item = new JSONObject();
        item.put("id", row.get("id"));
        item.put("name", row.get("name"));
        item.put("listenAddress", row.get("listen_address"));
        item.put("listenPort", row.get("listen_port"));
        item.put("protocol", row.get("protocol"));
        item.put("targetAddress", row.get("target_address"));
        item.put("targetPort", row.get("target_port"));
        item.put("natMode", row.get("nat_mode"));
        item.put("sourceCidrs", splitSourceCIDRs(Objects.toString(row.get("source_cidrs"), "")));
        item.put("enabled", true);
        return item;
    }

    private static NftForwardSaveDto dtoFromRow(Map<String, Object> row) {
        NftForwardSaveDto dto = new NftForwardSaveDto();
        dto.setId(numberObject(row.get("id")));
        dto.setName(Objects.toString(row.get("name"), ""));
        dto.setNodeId(numberObject(row.get("node_id")));
        dto.setListenAddress(Objects.toString(row.get("listen_address"), "0.0.0.0"));
        dto.setListenPort(((Number) row.get("listen_port")).intValue());
        dto.setProtocol(Objects.toString(row.get("protocol"), "tcp"));
        dto.setTargetAddress(Objects.toString(row.get("target_address"), ""));
        dto.setTargetPort(((Number) row.get("target_port")).intValue());
        dto.setNatMode(Objects.toString(row.get("nat_mode"), "masquerade"));
        dto.setSourceCidrs(Objects.toString(row.get("source_cidrs"), ""));
        dto.setEnabled(truth(row.get("enabled")));
        return dto;
    }

    static String rollbackSnapshot(Map<String, Object> row) {
        if (row == null) return null;
        String state = Objects.toString(row.get("state"), "");
        if ("active".equals(state) || "paused".equals(state)) {
            return JSON.toJSONString(dtoFromRow(row));
        }
        return StringUtils.trimToNull(Objects.toString(row.get("last_good_config"), null));
    }

    static Normalized normalize(NftForwardSaveDto dto) {
        if (dto == null) throw new IllegalArgumentException("配置不能为空");
        String name = StringUtils.trimToEmpty(dto.getName());
        if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("规则名称必须为1到100个字符");
        if (dto.getNodeId() == null || dto.getNodeId() <= 0) throw new IllegalArgumentException("请选择执行节点");
        int listenPort = validPort(dto.getListenPort(), "入口端口");
        int targetPort = validPort(dto.getTargetPort(), "目标端口");
        String listenAddress = StringUtils.defaultIfBlank(dto.getListenAddress(), "0.0.0.0").trim();
        listenAddress = ipv4(listenAddress, true, "监听地址");
        String targetAddress = ipv4(StringUtils.trimToEmpty(dto.getTargetAddress()), false, "目标地址");
        if (targetAddress.startsWith("127.") || "0.0.0.0".equals(targetAddress)) {
            throw new IllegalArgumentException("目标地址不能是回环地址或未指定地址");
        }
        int firstOctet = Integer.parseInt(targetAddress.substring(0, targetAddress.indexOf('.')));
        if (firstOctet >= 224) throw new IllegalArgumentException("目标地址不能是组播或保留地址");
        String protocol = normalizeProtocol(dto.getProtocol());
        String natMode = StringUtils.trimToEmpty(dto.getNatMode()).toLowerCase(Locale.ROOT);
        if (!List.of("masquerade", "preserve_source").contains(natMode)) {
            throw new IllegalArgumentException("NAT 模式必须是标准 NAT 或保留来源 IP");
        }
        List<String> cidrs = splitSourceCIDRs(dto.getSourceCidrs());
        if (cidrs.size() > MAX_SOURCE_CIDRS) throw new IllegalArgumentException("来源白名单最多允许64个网段");
        for (String cidr : cidrs) validateCIDR(cidr);
        return new Normalized(name, dto.getNodeId(), listenAddress, listenPort, protocol, targetAddress, targetPort,
                natMode, String.join("\n", cidrs), !Boolean.FALSE.equals(dto.getEnabled()));
    }

    private static int validPort(Integer port, String label) {
        if (port == null || port < 1 || port > 65535) throw new IllegalArgumentException(label + "必须在1到65535之间");
        return port;
    }

    private static String normalizeProtocol(String value) {
        String protocol = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
        if ("both".equals(protocol) || "tcp+udp".equals(protocol)) protocol = "tcp_udp";
        if (!List.of("tcp", "udp", "tcp_udp").contains(protocol)) throw new IllegalArgumentException("协议必须是 TCP、UDP 或 TCP+UDP");
        return protocol;
    }

    private static String ipv4(String value, boolean allowUnspecified, String label) {
        if (!value.matches("[0-9.]+")) throw new IllegalArgumentException(label + "必须是固定 IPv4 地址");
        try {
            InetAddress address = InetAddress.getByName(value);
            if (!(address instanceof Inet4Address)) throw new IllegalArgumentException(label + "必须是 IPv4 地址");
            if (!allowUnspecified && address.isAnyLocalAddress()) throw new IllegalArgumentException(label + "不能是未指定地址");
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(label + "格式不正确");
        }
    }

    private static void validateCIDR(String value) {
        String[] parts = value.split("/", 2);
        if (parts.length != 2) throw new IllegalArgumentException("来源白名单必须使用 IPv4/CIDR 格式：" + value);
        ipv4(parts[0], true, "来源白名单");
        try {
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("来源白名单前缀必须在0到32之间：" + value);
        }
    }

    private static List<String> splitSourceCIDRs(String raw) {
        if (StringUtils.isBlank(raw)) return List.of();
        return Arrays.stream(raw.trim().split("[\\s,，;；]+"))
                .map(String::trim).filter(StringUtils::isNotBlank).distinct().sorted().collect(Collectors.toList());
    }

    private static List<String> protocols(String protocol) {
        return "tcp_udp".equals(protocol) ? List.of("tcp", "udp") : List.of(protocol);
    }

    static boolean protocolOverlap(String left, String right) {
        String normalizedRight = StringUtils.defaultString(right).toLowerCase(Locale.ROOT);
        if (normalizedRight.contains("http") || normalizedRight.contains("socks") || normalizedRight.contains("vless")) normalizedRight = "tcp";
        return "tcp_udp".equals(left) || "tcp_udp".equals(normalizedRight) || left.equals(normalizedRight);
    }

    private JSONObject requireOK(GostDto response, String prefix) {
        if (response == null || !"OK".equals(response.getMsg())) {
            throw new IllegalStateException(prefix + "：" + (response == null ? "Agent 无响应" : StringUtils.defaultIfBlank(response.getMsg(), "Agent 无响应")));
        }
        return responseData(response);
    }

    private JSONObject responseData(GostDto response) {
        if (response == null || response.getData() == null) return new JSONObject();
        return response.getData() instanceof JSONObject object ? object : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
    }

    private Node nodeEntity(Map<String, Object> row) {
        Node node = new Node();
        node.setId(numberObject(row.get("id")));
        node.setVersion(Objects.toString(row.get("version"), ""));
        return node;
    }

    private String warnings(JSONObject preflight) {
        JSONArray items = preflight.getJSONArray("warnings");
        if (items == null || items.isEmpty()) return null;
        return items.stream().map(String::valueOf).filter(StringUtils::isNotBlank).distinct().collect(Collectors.joining("；"));
    }

    private String conflictDetail(List<String> conflicts) {
        return conflicts == null || conflicts.isEmpty() ? "" : "：" + String.join("；", conflicts);
    }

    private void markRuleError(Long id, String message) {
        jdbcTemplate.update("UPDATE nft_forward_rule SET state='error',last_error=?,updated_time=? WHERE id=? AND state<>'delete_pending'",
                shorten(message), System.currentTimeMillis(), id);
    }

    private void event(Long ruleId, Long nodeId, String type, String status, String detail) {
        if (ruleId == null || nodeId == null) return;
        jdbcTemplate.update("INSERT INTO nft_forward_event(rule_id,node_id,event_type,status,detail,created_time) VALUES (?,?,?,?,?,?)",
                ruleId, nodeId, type, status, shorten(detail), System.currentTimeMillis());
    }

    private static long nextGeneration() {
        return GENERATION.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : value == null ? 0L : Long.parseLong(value.toString());
    }

    private static Long numberObject(Object value) {
        return value == null ? null : number(value);
    }

    private static boolean truth(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return value != null && List.of("true", "1", "yes", "active", "online").contains(value.toString().toLowerCase(Locale.ROOT));
    }

    private static String shorten(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    static record Normalized(String name, Long nodeId, String listenAddress, int listenPort, String protocol,
                             String targetAddress, int targetPort, String natMode, String sourceCidrs, boolean enabled) { }

    private record CounterState(boolean tcpApplied, boolean udpApplied, long packets, long bytes) {
        private static final CounterState EMPTY = new CounterState(false, false, 0, 0);

        private CounterState add(String protocol, boolean applied, long packetCount, long byteCount) {
            return new CounterState(tcpApplied || ("tcp".equals(protocol) && applied),
                    udpApplied || ("udp".equals(protocol) && applied), packets + packetCount, bytes + byteCount);
        }

        private boolean appliedFor(String protocol) {
            return switch (protocol) {
                case "tcp" -> tcpApplied;
                case "udp" -> udpApplied;
                case "tcp_udp" -> tcpApplied && udpApplied;
                default -> false;
            };
        }
    }
}
