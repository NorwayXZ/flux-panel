package com.admin.service;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardRouteDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.MultiLineAggregationDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AggregationWeightPolicy;
import com.admin.common.utils.TunnelRouteUtil;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MultiLineAggregationService {
    private static final long METRIC_WINDOW_MS = AggregationWeightPolicy.METRIC_MAX_AGE_MS;
    private final JdbcTemplate jdbcTemplate;
    private final TunnelService tunnelService;
    private final NodeService nodeService;
    private final ForwardService forwardService;
    private final SchedulingConflictService schedulingConflictService;
    private final AtomicBoolean recalculating = new AtomicBoolean(false);

    public MultiLineAggregationService(JdbcTemplate jdbcTemplate, TunnelService tunnelService,
                                       NodeService nodeService, ForwardService forwardService,
                                       SchedulingConflictService schedulingConflictService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tunnelService = tunnelService;
        this.nodeService = nodeService;
        this.forwardService = forwardService;
        this.schedulingConflictService = schedulingConflictService;
    }

    public R overview() {
        List<Map<String, Object>> groups = jdbcTemplate.queryForList("SELECT g.*,n.name AS entry_node_name,n.server_ip AS entry_server_ip,n.ip AS entry_ip,"
                + "f.status AS forward_status,f.route_config AS forward_route_config,f.in_flow,f.out_flow "
                + "FROM aggregation_group g LEFT JOIN node n ON n.id=g.entry_node_id LEFT JOIN forward f ON f.id=g.forward_id ORDER BY g.created_time DESC");
        List<Map<String, Object>> members = jdbcTemplate.queryForList("SELECT m.*,t.name AS tunnel_name,t.in_node_id,t.out_node_id,t.in_ip AS tunnel_in_ip,"
                + "t.out_ip AS tunnel_out_ip,t.node_path AS tunnel_node_path,t.protocol,"
                + "ni.name AS in_node_name,no.name AS out_node_name FROM aggregation_member m JOIN tunnel t ON t.id=m.tunnel_id "
                + "LEFT JOIN node ni ON ni.id=t.in_node_id LEFT JOIN node no ON no.id=t.out_node_id ORDER BY m.group_id,m.id");
        Map<Long, List<Map<String, Object>>> membersByGroup = members.stream().collect(Collectors.groupingBy(
                row -> longNumber(row.get("group_id")), LinkedHashMap::new, Collectors.toList()));
        int degraded = 0;
        double totalCapacity = 0;
        for (Map<String, Object> group : groups) {
            long groupId = longNumber(group.get("id"));
            List<Map<String, Object>> groupMembers = membersByGroup.getOrDefault(groupId, List.of());
            applyLiveRouteState(group, groupMembers);
            long healthy = groupMembers.stream().filter(this::memberHealthy).count();
            double capacity = groupMembers.stream().filter(this::memberHealthy)
                    .mapToDouble(row -> doubleNumber(row.get("bandwidth_mbps"), 0)).sum();
            group.put("members", groupMembers);
            group.put("healthyPaths", healthy);
            group.put("estimatedCapacityMbps", round(capacity));
            boolean isDegraded = bool(group.get("enabled")) && healthy < intNumber(group.get("minimum_healthy_paths"), 1);
            group.put("degraded", isDegraded);
            if (isDegraded) degraded++;
            totalCapacity += capacity;
        }

        Map<Long, Node> nodes = nodeService.list().stream().collect(Collectors.toMap(Node::getId, node -> node));
        List<Map<String, Object>> tunnels = tunnelService.list().stream()
                .filter(tunnel -> Objects.equals(tunnel.getType(), 2))
                .sorted(Comparator.comparing(Tunnel::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(tunnel -> tunnelOption(tunnel, nodes)).collect(Collectors.toList());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("groups", groups.size());
        summary.put("active", groups.stream().filter(group -> bool(group.get("enabled")) && "active".equals(group.get("state"))).count());
        summary.put("healthyPaths", members.stream().filter(this::memberHealthy).count());
        summary.put("degraded", degraded);
        summary.put("estimatedCapacityMbps", round(totalCapacity));
        return R.ok(Map.of("groups", groups, "tunnels", tunnels, "summary", summary,
                "aggregationType", "multi_session", "agentUpgradeRequired", false));
    }

    public R save(MultiLineAggregationDto dto) {
        try {
            NormalizedConfig config = normalizeAndValidate(dto);
            if (dto.getId() == null) return create(config);
            return update(dto.getId(), config);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        } catch (Exception e) {
            log.error("Save aggregation group failed", e);
            return R.err("保存失败：" + concise(e.getMessage()));
        }
    }

    public R deploy(long id) {
        Map<String, Object> group = findGroup(id);
        if (group == null) return R.err("并发调度组不存在");
        try {
            List<Tunnel> tunnels = loadGroupTunnels(id);
            if (tunnels.size() < 2) return R.err("并发调度组至少需要两条线路");
            Map<Long, Integer> weights = refreshMetricsAndWeights(group, tunnels, true);
            R result = group.get("forward_id") == null
                    ? createUnderlyingForward(id, group, tunnels, weights)
                    : updateUnderlyingForward(group, tunnels, weights);
            if (result.getCode() == 0) {
                jdbcTemplate.update("UPDATE aggregation_group SET state='active',enabled=1,last_error=NULL,updated_time=? WHERE id=?", System.currentTimeMillis(), id);
                event(id, "deploy", "success", "并发调度入口已部署", snapshot(group, weights));
                return overview();
            }
            markError(id, result.getMsg());
            return result;
        } catch (Exception e) {
            markError(id, concise(e.getMessage()));
            return R.err("部署失败：" + concise(e.getMessage()));
        }
    }

    public R recalculate(long id) {
        R result = recalculateGroup(id, false);
        return result.getCode() == 0 ? overview() : result;
    }

    public R toggle(long id, boolean enabled) {
        Map<String, Object> group = findGroup(id);
        if (group == null) return R.err("并发调度组不存在");
        Long forwardId = nullableLong(group.get("forward_id"));
        if (forwardId == null) return enabled ? deploy(id) : R.err("并发调度入口尚未部署");
        R result = enabled ? forwardService.resumeManagedForward(forwardId) : forwardService.pauseManagedForward(forwardId);
        if (result.getCode() != 0) return result;
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE aggregation_group SET enabled=?,state=?,last_error=NULL,updated_time=? WHERE id=?",
                enabled ? 1 : 0, enabled ? "active" : "paused", now, id);
        event(id, enabled ? "resume" : "pause", "success", enabled ? "并发调度入口已恢复" : "并发调度入口已暂停", null);
        return overview();
    }

    public R test(long id) {
        Map<String, Object> group = findGroup(id);
        if (group == null) return R.err("并发调度组不存在");
        Long forwardId = nullableLong(group.get("forward_id"));
        if (forwardId == null) return R.err("请先部署并发调度入口");
        R result = forwardService.diagnoseForward(forwardId);
        event(id, "validation", result.getCode() == 0 ? "success" : "failed",
                result.getCode() == 0 ? "并发调度线路验证完成" : result.getMsg(), JSON.toJSONString(result.getData()));
        if (result.getCode() != 0) return result;
        return R.ok(Map.of("diagnosis", result.getData(), "testedAt", System.currentTimeMillis()));
    }

    public R repair(long id) {
        Map<String, Object> group = findGroup(id);
        if (group == null) return R.err("并发调度组不存在");
        try {
            List<Tunnel> tunnels = loadGroupTunnels(id);
            if (tunnels.size() < 2) return R.err("并发调度组至少需要两条线路");
            Map<Long, Integer> weights = refreshMetricsAndWeights(group, tunnels, true);
            R deployResult = group.get("forward_id") == null
                    ? createUnderlyingForward(id, group, tunnels, weights)
                    : updateUnderlyingForward(group, tunnels, weights);
            if (deployResult.getCode() != 0) {
                markError(id, deployResult.getMsg());
                event(id, "repair", deployResult.getMsg() != null && deployResult.getMsg().contains("原配置已恢复") ? "rollback" : "failed",
                        "底层线路重新下发失败：" + deployResult.getMsg(), snapshot(group, weights));
                return R.err("修复失败：" + deployResult.getMsg());
            }
            Long forwardId = nullableLong(findGroup(id).get("forward_id"));
            R diagnosis = forwardId == null ? R.err("底层转发记录未生成") : forwardService.diagnoseForward(forwardId);
            boolean verified = diagnosis.getCode() == 0;
            long now = System.currentTimeMillis();
            jdbcTemplate.update("UPDATE aggregation_group SET state=?,last_error=?,last_calculated_at=?,updated_time=? WHERE id=?",
                    verified ? "active" : "degraded",
                    verified ? null : concise(diagnosis.getMsg()),
                    now, now, id);
            event(id, "repair", verified ? "success" : "warning",
                    verified ? "底层线路已重新下发并验证完成" : "底层线路已重新下发，但验证仍需处理：" + diagnosis.getMsg(),
                    JSON.toJSONString(repairSnapshot(deployResult, diagnosis)));
            return overview();
        } catch (Exception e) {
            markError(id, concise(e.getMessage()));
            event(id, "repair", "failed", "修复异常：" + concise(e.getMessage()), null);
            return R.err("修复失败：" + concise(e.getMessage()));
        }
    }

    public R events(long id) {
        if (findGroup(id) == null) return R.err("并发调度组不存在");
        return R.ok(jdbcTemplate.queryForList("SELECT id,group_id AS groupId,event_type AS eventType,status,detail,snapshot_json AS snapshotJson,"
                + "created_time AS createdTime FROM aggregation_event WHERE group_id=? ORDER BY created_time DESC LIMIT 100", id));
    }

    public R delete(long id) {
        Map<String, Object> group = findGroup(id);
        if (group == null) return R.err("并发调度组不存在");
        Long forwardId = nullableLong(group.get("forward_id"));
        if (forwardId != null) {
            R result = forwardService.deleteManagedForward(forwardId);
            if (result.getCode() != 0) return R.err("底层转发删除失败，并发调度组已保留：" + result.getMsg());
        }
        jdbcTemplate.update("DELETE FROM aggregation_member WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM aggregation_event WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM aggregation_group WHERE id=?", id);
        return overview();
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void adaptWeights() {
        if (!recalculating.compareAndSet(false, true)) return;
        try {
            List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM aggregation_group WHERE enabled=1 AND auto_weight=1 "
                    + "AND forward_id IS NOT NULL AND state IN ('active','degraded') ORDER BY id", Long.class);
            for (Long id : ids) {
                try { recalculateGroup(id, true); }
                catch (Exception e) { log.warn("Adaptive aggregation update {} failed: {}", id, e.getMessage()); }
            }
        } finally {
            recalculating.set(false);
        }
    }

    private R create(NormalizedConfig config) {
        long now = System.currentTimeMillis();
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO aggregation_group "
                    + "(name,entry_node_id,listen_port,remote_addr,protocol_mode,mode,scheduler,auto_weight,minimum_healthy_paths,enabled,state,created_time,updated_time) "
                    + "VALUES (?,?,?,?,?,?, 'weighted',?,?,1,'provisioning',?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, config.name()); statement.setLong(2, config.entryNodeId()); statement.setInt(3, config.listenPort());
            statement.setString(4, config.remoteAddr()); statement.setString(5, config.protocolMode()); statement.setString(6, config.mode());
            statement.setInt(7, config.autoWeight() ? 1 : 0); statement.setInt(8, config.minimumHealthyPaths());
            statement.setLong(9, now); statement.setLong(10, now); return statement;
        }, holder);
        long id = Objects.requireNonNull(holder.getKey()).longValue();
        replaceMembers(id, config.tunnels(), config.manualWeights(), now);
        Map<String, Object> group = findGroup(id);
        Map<Long, Integer> weights = refreshMetricsAndWeights(group, config.tunnels(), true);
        R result = createUnderlyingForward(id, group, config.tunnels(), weights);
        if (result.getCode() != 0) {
            markError(id, result.getMsg());
            event(id, "create", "failed", result.getMsg(), snapshot(group, weights));
            return R.err("并发调度组已保存，但入口部署失败：" + result.getMsg());
        }
        jdbcTemplate.update("UPDATE aggregation_group SET state='active',last_error=NULL,updated_time=? WHERE id=?", System.currentTimeMillis(), id);
        event(id, "create", "success", "多线路并发调度入口创建成功", snapshot(group, weights));
        return overview();
    }

    private R update(long id, NormalizedConfig config) {
        Map<String, Object> oldGroup = findGroup(id);
        if (oldGroup == null) return R.err("并发调度组不存在");
        List<Map<String, Object>> oldMembers = loadMemberRows(id);
        Map<Long, Integer> newWeights = calculateWeights(config.mode(), config.autoWeight(), config.tunnels(), config.manualWeights(), oldMembers);
        Map<String, Object> desired = new HashMap<>(oldGroup);
        desired.put("name", config.name()); desired.put("entry_node_id", config.entryNodeId()); desired.put("listen_port", config.listenPort());
        desired.put("remote_addr", config.remoteAddr()); desired.put("protocol_mode", config.protocolMode()); desired.put("mode", config.mode());
        desired.put("auto_weight", config.autoWeight()); desired.put("minimum_healthy_paths", config.minimumHealthyPaths());
        R deployResult = updateUnderlyingForward(desired, config.tunnels(), newWeights);
        if (deployResult.getCode() != 0) {
            String rollbackStatus = deployResult.getMsg() != null && deployResult.getMsg().contains("原配置已恢复") ? "rollback" : "failed";
            event(id, "update", rollbackStatus, "新配置部署失败：" + deployResult.getMsg(), JSON.toJSONString(oldMembers));
            return R.err("更新失败：" + deployResult.getMsg());
        }
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE aggregation_group SET name=?,entry_node_id=?,listen_port=?,remote_addr=?,protocol_mode=?,mode=?,auto_weight=?,"
                        + "minimum_healthy_paths=?,state='active',last_error=NULL,last_calculated_at=?,updated_time=? WHERE id=?",
                config.name(), config.entryNodeId(), config.listenPort(), config.remoteAddr(), config.protocolMode(), config.mode(), config.autoWeight() ? 1 : 0,
                config.minimumHealthyPaths(), now, now, id);
        replaceMembers(id, config.tunnels(), config.manualWeights(), now);
        persistCalculatedMetrics(id, config.tunnels(), newWeights, now);
        event(id, "update", "success", "聚合配置已更新", snapshot(desired, newWeights));
        return overview();
    }

    private R recalculateGroup(long id, boolean scheduled) {
        Map<String, Object> group = findGroup(id);
        if (group == null) return R.err("并发调度组不存在");
        if (!bool(group.get("auto_weight"))) return R.ok();
        List<Tunnel> tunnels = loadGroupTunnels(id);
        Map<Long, Integer> oldWeights = loadMemberRows(id).stream().collect(Collectors.toMap(
                row -> longNumber(row.get("tunnel_id")), row -> intNumber(row.get("effective_weight"), 100)));
        Map<Long, Integer> weights = refreshMetricsAndWeights(group, tunnels, false);
        if (weights.equals(oldWeights)) {
            persistCalculatedMetrics(id, tunnels, oldWeights, System.currentTimeMillis());
            return R.ok();
        }
        long healthy = weights.values().stream().filter(weight -> weight > 0).count();
        if (healthy < intNumber(group.get("minimum_healthy_paths"), 1)) {
            String message = "健康线路 " + healthy + " 条，低于最低要求 " + group.get("minimum_healthy_paths");
            persistCalculatedMetrics(id, tunnels, oldWeights, System.currentTimeMillis());
            jdbcTemplate.update("UPDATE aggregation_group SET state='degraded',last_error=?,updated_time=? WHERE id=?", message, System.currentTimeMillis(), id);
            event(id, "health", "warning", message, JSON.toJSONString(weights));
            return R.err(message);
        }
        R result = updateUnderlyingForward(group, tunnels, weights);
        if (result.getCode() != 0) {
            persistCalculatedMetrics(id, tunnels, oldWeights, System.currentTimeMillis());
            String rollbackStatus = result.getMsg() != null && result.getMsg().contains("原配置已恢复") ? "rollback" : "failed";
            event(id, "weight_change", rollbackStatus, "权重更新失败：" + result.getMsg(), JSON.toJSONString(oldWeights));
            return result;
        }
        persistCalculatedMetrics(id, tunnels, weights, System.currentTimeMillis());
        jdbcTemplate.update("UPDATE aggregation_group SET state='active',last_error=NULL,last_calculated_at=?,updated_time=? WHERE id=?",
                System.currentTimeMillis(), System.currentTimeMillis(), id);
        event(id, "weight_change", "success", scheduled ? "自动权重已调整" : "权重已重新计算", JSON.toJSONString(weights));
        return R.ok();
    }

    private R createUnderlyingForward(long groupId, Map<String, Object> group, List<Tunnel> tunnels, Map<Long, Integer> weights) {
        ForwardDto dto = new ForwardDto();
        dto.setName(managedName(groupId, Objects.toString(group.get("name"), "并发调度线路")));
        dto.setTunnelId(tunnels.get(0).getId().intValue());
        dto.setRouteTunnelIds(tunnels.stream().map(tunnel -> tunnel.getId().intValue()).collect(Collectors.toList()));
        dto.setRouteWeights(integerWeights(weights));
        dto.setInPort(intNumber(group.get("listen_port"), 0));
        dto.setRemoteAddr(Objects.toString(group.get("remote_addr"), ""));
        dto.setRouteMode("balance"); dto.setRouteBalanceStrategy("weighted");
        dto.setProtocolMode(normalizeProtocol(Objects.toString(group.get("protocol_mode"), "tcp_udp")));
        dto.setStrategy("round");
        R result = forwardService.createForward(dto);
        if (result.getCode() != 0) return result;
        Forward forward = forwardService.getOne(new QueryWrapper<Forward>().eq("name", dto.getName()).eq("in_port", dto.getInPort()).orderByDesc("id").last("LIMIT 1"));
        if (forward == null) return R.err("底层转发已下发但未找到数据库记录");
        jdbcTemplate.update("UPDATE aggregation_group SET forward_id=?,state='active',last_error=NULL,updated_time=? WHERE id=?",
                forward.getId(), System.currentTimeMillis(), groupId);
        return R.ok(forward.getId());
    }

    private R updateUnderlyingForward(Map<String, Object> group, List<Tunnel> tunnels, Map<Long, Integer> weights) {
        Long forwardId = nullableLong(group.get("forward_id"));
        if (forwardId == null) return R.err("底层转发不存在");
        Forward existing = forwardService.getById(forwardId);
        if (existing == null) return R.err("底层转发记录已丢失，请重新部署");
        ForwardUpdateDto dto = new ForwardUpdateDto();
        dto.setId(forwardId); dto.setUserId(existing.getUserId());
        dto.setName(managedName(longNumber(group.get("id")), Objects.toString(group.get("name"), "并发调度线路")));
        dto.setTunnelId(tunnels.get(0).getId().intValue());
        dto.setRouteTunnelIds(tunnels.stream().map(tunnel -> tunnel.getId().intValue()).collect(Collectors.toList()));
        dto.setRouteWeights(integerWeights(weights)); dto.setInPort(intNumber(group.get("listen_port"), existing.getInPort()));
        dto.setRemoteAddr(Objects.toString(group.get("remote_addr"), existing.getRemoteAddr()));
        dto.setRouteMode("balance"); dto.setRouteBalanceStrategy("weighted"); dto.setStrategy("round");
        dto.setProtocolMode(normalizeProtocol(Objects.toString(group.get("protocol_mode"), existing.getProtocolMode())));
        return forwardService.updateManagedForward(dto);
    }

    private NormalizedConfig normalizeAndValidate(MultiLineAggregationDto dto) {
        if (dto.getTunnelIds() == null || new LinkedHashSet<>(dto.getTunnelIds()).size() < 2) throw new IllegalArgumentException("至少选择两条不同线路");
        if (dto.getListenPort() == null) throw new IllegalArgumentException("请输入并发调度入口端口");
        List<Tunnel> tunnels = new LinkedHashSet<>(dto.getTunnelIds()).stream().map(tunnelService::getById).collect(Collectors.toList());
        if (tunnels.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("所选线路中存在已删除的隧道");
        if (tunnels.stream().anyMatch(tunnel -> !Objects.equals(tunnel.getType(), 2))) {
            throw new IllegalArgumentException("多线路并发调度当前仅支持隧道转发线路");
        }
        Long entryNodeId = tunnels.get(0).getInNodeId();
        if (entryNodeId == null || tunnels.stream().anyMatch(tunnel -> !Objects.equals(entryNodeId, tunnel.getInNodeId()))) {
            throw new IllegalArgumentException("多线路并发调度要求所有隧道使用同一个入口节点");
        }
        Node entry = nodeService.getById(entryNodeId);
        if (entry == null) throw new IllegalArgumentException("入口节点不存在");
        schedulingConflictService.assertTunnelSetAvailable("multi_line_aggregation", dto.getId(),
                tunnels.stream().map(Tunnel::getId).collect(Collectors.toList()));
        String mode = normalizeMode(dto.getMode());
        int minimum = dto.getMinimumHealthyPaths() == null ? 1 : dto.getMinimumHealthyPaths();
        if (minimum > tunnels.size()) throw new IllegalArgumentException("最少健康线路不能超过所选线路数");
        Map<Long, Integer> manual = dto.getManualWeights() == null ? Map.of() : dto.getManualWeights();
        return new NormalizedConfig(dto.getName().trim(), entryNodeId, dto.getListenPort(), dto.getRemoteAddr().trim(),
                normalizeProtocol(dto.getProtocolMode()), mode, !Boolean.FALSE.equals(dto.getAutoWeight()), minimum, tunnels, manual);
    }

    private Map<Long, Integer> refreshMetricsAndWeights(Map<String, Object> group, List<Tunnel> tunnels, boolean alwaysPersist) {
        List<Map<String, Object>> oldMembers = loadMemberRows(longNumber(group.get("id")));
        Map<Long, Integer> manual = oldMembers.stream().collect(Collectors.toMap(row -> longNumber(row.get("tunnel_id")), row -> intNumber(row.get("manual_weight"), 100)));
        Map<Long, Integer> weights = calculateWeights(Objects.toString(group.get("mode"), "balanced"), bool(group.get("auto_weight")), tunnels, manual, oldMembers);
        if (alwaysPersist) persistCalculatedMetrics(longNumber(group.get("id")), tunnels, weights, System.currentTimeMillis());
        return weights;
    }

    private Map<Long, Integer> calculateWeights(String mode, boolean autoWeight, List<Tunnel> tunnels,
                                                Map<Long, Integer> manualWeights, List<Map<String, Object>> previousRows) {
        MetricCatalog catalog = loadMetricCatalog();
        Map<Long, Map<String, Object>> previous = previousRows.stream().collect(Collectors.toMap(row -> longNumber(row.get("tunnel_id")), row -> row, (a, b) -> a));
        Map<Long, Node> nodes = nodeService.list().stream().collect(Collectors.toMap(Node::getId, node -> node));
        List<AggregationWeightPolicy.PathMetric> metrics = new ArrayList<>();
        for (Tunnel tunnel : tunnels) {
            PathMeasurement measurement = measurement(tunnel, catalog);
            boolean healthy = tunnelHealthy(tunnel, nodes);
            int oldWeight = intNumber(previous.getOrDefault(tunnel.getId(), Map.of()).get("effective_weight"), 100);
            metrics.add(new AggregationWeightPolicy.PathMetric(tunnel.getId(), measurement.bandwidth(), measurement.latency(),
                    measurement.loss(), measurement.jitter(), measurement.measuredAt(), healthy, oldWeight));
        }
        if (autoWeight) return AggregationWeightPolicy.calculate(mode, metrics, System.currentTimeMillis());
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (AggregationWeightPolicy.PathMetric metric : metrics) {
            int manual = Math.max(1, Math.min(1000, manualWeights.getOrDefault(metric.tunnelId(), 100)));
            result.put(metric.tunnelId(), metric.healthy() ? manual : 0);
        }
        return result;
    }

    private void persistCalculatedMetrics(long groupId, List<Tunnel> tunnels, Map<Long, Integer> weights, long now) {
        MetricCatalog catalog = loadMetricCatalog();
        Map<Long, Node> nodes = nodeService.list().stream().collect(Collectors.toMap(Node::getId, node -> node));
        for (Tunnel tunnel : tunnels) {
            PathMeasurement metric = measurement(tunnel, catalog);
            String health = tunnelHealthy(tunnel, nodes) && weights.getOrDefault(tunnel.getId(), 0) > 0 ? "healthy" : "unhealthy";
            jdbcTemplate.update("UPDATE aggregation_member SET effective_weight=?,health_status=?,bandwidth_mbps=?,latency_ms=?,packet_loss_percent=?,"
                            + "jitter_ms=?,metric_measured_at=?,last_checked_at=?,last_error=NULL,updated_time=? WHERE group_id=? AND tunnel_id=?",
                    Math.max(0, weights.getOrDefault(tunnel.getId(), 0)), health, metric.bandwidth(), metric.latency(), metric.loss(), metric.jitter(),
                    metric.measuredAt(), now, now, groupId, tunnel.getId());
        }
    }

    private MetricCatalog loadMetricCatalog() {
        long cutoff = System.currentTimeMillis() - METRIC_WINDOW_MS;
        Map<String, Map<String, Object>> bandwidth = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT source_node_id,target_node_id,upload_mbps,download_mbps,total_mbps,rtt_ms,"
                + "packet_loss_percent,jitter_ms,finished_at FROM bandwidth_test_run WHERE status='success' AND finished_at>=? ORDER BY finished_at DESC", cutoff)) {
            bandwidth.putIfAbsent(pair(longNumber(row.get("source_node_id")), longNumber(row.get("target_node_id"))), row);
        }
        Map<String, Map<String, Object>> quality = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT q.source_node_id,q.target_node_id,r.tcp_avg_ms,r.p50_ms,r.failure_rate,r.jitter_ms,r.finished_at "
                + "FROM quality_probe_run r JOIN quality_probe_task q ON q.id=r.task_id WHERE q.target_node_id IS NOT NULL "
                + "AND r.status IN ('success','partial') AND r.finished_at>=? ORDER BY r.finished_at DESC", cutoff)) {
            quality.putIfAbsent(pair(longNumber(row.get("source_node_id")), longNumber(row.get("target_node_id"))), row);
        }
        return new MetricCatalog(bandwidth, quality);
    }

    private PathMeasurement measurement(Tunnel tunnel, MetricCatalog catalog) {
        String key = pair(tunnel.getInNodeId(), tunnel.getOutNodeId());
        Map<String, Object> bandwidth = catalog.bandwidth().get(key);
        Map<String, Object> quality = catalog.quality().get(key);
        Double capacity = null, latency = null, loss = null, jitter = null;
        Long measured = null;
        if (bandwidth != null) {
            capacity = Math.max(doubleNumber(bandwidth.get("upload_mbps"), 0), doubleNumber(bandwidth.get("download_mbps"), 0));
            if (capacity <= 0) capacity = doubleNumber(bandwidth.get("total_mbps"), 0);
            latency = nullableDouble(bandwidth.get("rtt_ms")); loss = nullableDouble(bandwidth.get("packet_loss_percent"));
            jitter = nullableDouble(bandwidth.get("jitter_ms")); measured = nullableLong(bandwidth.get("finished_at"));
        }
        if (quality != null) {
            if (latency == null || latency <= 0) latency = firstPositive(quality.get("tcp_avg_ms"), quality.get("p50_ms"));
            if (loss == null) loss = nullableDouble(quality.get("failure_rate"));
            if (jitter == null || jitter <= 0) jitter = nullableDouble(quality.get("jitter_ms"));
            Long qualityTime = nullableLong(quality.get("finished_at"));
            if (qualityTime != null && (measured == null || qualityTime > measured)) measured = qualityTime;
        }
        return new PathMeasurement(capacity, latency, loss, jitter, measured);
    }

    private void replaceMembers(long groupId, List<Tunnel> tunnels, Map<Long, Integer> manualWeights, long now) {
        jdbcTemplate.update("DELETE FROM aggregation_member WHERE group_id=?", groupId);
        for (Tunnel tunnel : tunnels) {
            int manual = Math.max(1, Math.min(1000, manualWeights.getOrDefault(tunnel.getId(), 100)));
            jdbcTemplate.update("INSERT INTO aggregation_member (group_id,tunnel_id,manual_weight,effective_weight,enabled,health_status,created_time,updated_time) "
                    + "VALUES (?,?,?,?,1,'unknown',?,?)", groupId, tunnel.getId(), manual, manual, now, now);
        }
    }

    private List<Tunnel> loadGroupTunnels(long id) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT tunnel_id FROM aggregation_member WHERE group_id=? AND enabled=1 ORDER BY id", Long.class, id);
        return ids.stream().map(tunnelService::getById).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private List<Map<String, Object>> loadMemberRows(long id) {
        return jdbcTemplate.queryForList("SELECT * FROM aggregation_member WHERE group_id=? ORDER BY id", id);
    }

    private Map<String, Object> findGroup(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM aggregation_group WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void applyLiveRouteState(Map<String, Object> group, List<Map<String, Object>> members) {
        Object raw = group.get("forward_route_config");
        if (raw == null) return;
        try {
            List<ForwardRouteDto> routes = JSON.parseArray(raw.toString(), ForwardRouteDto.class);
            Map<Long, ForwardRouteDto> byTunnel = routes.stream().collect(Collectors.toMap(route -> route.getTunnelId().longValue(), route -> route));
            for (Map<String, Object> member : members) {
                ForwardRouteDto route = byTunnel.get(longNumber(member.get("tunnel_id")));
                if (route == null) continue;
                member.put("health_status", route.getStatus()); member.put("latency_ms", route.getLatency() == null ? member.get("latency_ms") : route.getLatency());
                member.put("packet_loss_percent", route.getPacketLoss() == null ? member.get("packet_loss_percent") : route.getPacketLoss());
                member.put("last_error", route.getMessage());
                applyRouteFailureHint(member, route);
            }
        } catch (Exception e) {
            log.debug("Could not parse aggregation route state: {}", e.getMessage());
        }
    }

    private void applyRouteFailureHint(Map<String, Object> member, ForwardRouteDto route) {
        if (!"unhealthy".equals(Objects.toString(route.getStatus(), "unknown"))) {
            member.put("failure_segment", null);
            member.put("failure_address", null);
            member.put("failure_message", null);
            return;
        }
        String message = concise(route.getMessage());
        String outNode = Objects.toString(member.get("out_node_name"), "出口节点");
        String inNode = Objects.toString(member.get("in_node_name"), "入口节点");
        member.put("failure_message", message);
        if (route.getOutPort() != null) {
            member.put("failure_segment", inNode + " → " + outNode + " 的中转监听端口");
            member.put("failure_address", Objects.toString(member.get("tunnel_out_ip"), outNode) + ":" + route.getOutPort());
            return;
        }
        member.put("failure_segment", "线路探测");
        member.put("failure_address", "-");
    }

    private boolean routeHealthy(Map<String, Object> previous) {
        if (previous == null) return true;
        return !"unhealthy".equals(Objects.toString(previous.get("health_status"), "unknown"));
    }

    private boolean tunnelHealthy(Tunnel tunnel, Map<Long, Node> nodes) {
        try {
            for (Long nodeId : TunnelRouteUtil.parseNodePath(tunnel)) {
                Node node = nodes.get(nodeId);
                if (node == null || !Objects.equals(node.getStatus(), 1)) return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    private boolean memberHealthy(Map<String, Object> row) {
        String status = Objects.toString(row.get("health_status"), "unknown");
        return !"unhealthy".equals(status) && !"offline".equals(status) && bool(row.get("enabled"))
                && intNumber(row.get("effective_weight"), 0) > 0;
    }

    private Map<String, Object> tunnelOption(Tunnel tunnel, Map<Long, Node> nodes) {
        Map<String, Object> option = new LinkedHashMap<>();
        Node in = nodes.get(tunnel.getInNodeId()); Node out = nodes.get(tunnel.getOutNodeId());
        option.put("id", tunnel.getId()); option.put("name", tunnel.getName()); option.put("entryNodeId", tunnel.getInNodeId());
        option.put("entryNodeName", in == null ? "未知入口" : in.getName()); option.put("exitNodeId", tunnel.getOutNodeId());
        option.put("exitNodeName", out == null ? "未知出口" : out.getName()); option.put("protocol", tunnel.getProtocol());
        option.put("online", tunnelHealthy(tunnel, nodes)); return option;
    }

    private void markError(long id, String error) {
        jdbcTemplate.update("UPDATE aggregation_group SET state='error',last_error=?,updated_time=? WHERE id=?", concise(error), System.currentTimeMillis(), id);
    }

    private void event(long id, String type, String status, String detail, String snapshot) {
        jdbcTemplate.update("INSERT INTO aggregation_event (group_id,event_type,status,detail,snapshot_json,created_time) VALUES (?,?,?,?,?,?)",
                id, type, status, concise(detail), snapshot, System.currentTimeMillis());
    }

    private String snapshot(Map<String, Object> group, Map<Long, Integer> weights) {
        return JSON.toJSONString(Map.of("group", group, "weights", weights));
    }

    private Map<String, Object> repairSnapshot(R deployResult, R diagnosis) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deploy", deployResult.getData());
        data.put("diagnosis", diagnosis.getData());
        data.put("diagnosisMessage", diagnosis.getMsg());
        return data;
    }

    private Map<Integer, Integer> integerWeights(Map<Long, Integer> weights) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        weights.forEach((id, weight) -> result.put(id.intValue(), weight)); return result;
    }

    private String managedName(long id, String name) { return "[并发调度#" + id + "] " + name; }
    private String normalizeMode(String mode) { return Set.of("speed", "balanced", "stability").contains(mode) ? mode : "balanced"; }
    private String normalizeProtocol(String protocol) { return Set.of("tcp", "udp", "tcp_udp").contains(protocol) ? protocol : "tcp_udp"; }
    private String pair(Long a, Long b) { return pair(a == null ? 0 : a, b == null ? 0 : b); }
    private String pair(long a, long b) { return Math.min(a, b) + ":" + Math.max(a, b); }
    private long longNumber(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private int intNumber(Object value, int fallback) { return value == null ? fallback : ((Number) value).intValue(); }
    private double doubleNumber(Object value, double fallback) { return value == null ? fallback : ((Number) value).doubleValue(); }
    private Double nullableDouble(Object value) { return value == null ? null : ((Number) value).doubleValue(); }
    private Double firstPositive(Object first, Object second) { double a = doubleNumber(first, 0); return a > 0 ? a : nullableDouble(second); }
    private boolean bool(Object value) { return value instanceof Boolean ? (Boolean) value : value != null && ((Number) value).intValue() != 0; }
    private double round(double value) { return Math.round(value * 10.0) / 10.0; }
    private String concise(String value) { if (value == null || value.isBlank()) return "未知错误"; return value.length() > 500 ? value.substring(0, 500) : value; }

    private record NormalizedConfig(String name, Long entryNodeId, int listenPort, String remoteAddr,
                                    String protocolMode, String mode, boolean autoWeight, int minimumHealthyPaths,
                                    List<Tunnel> tunnels, Map<Long, Integer> manualWeights) { }
    private record MetricCatalog(Map<String, Map<String, Object>> bandwidth, Map<String, Map<String, Object>> quality) { }
    private record PathMeasurement(Double bandwidth, Double latency, Double loss, Double jitter, Long measuredAt) { }
}
