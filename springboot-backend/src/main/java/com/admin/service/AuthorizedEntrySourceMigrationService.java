package com.admin.service;

import com.admin.common.dto.ForwardDto;
import com.admin.common.lang.R;
import com.admin.common.utils.IpLiteralUtil;
import com.admin.entity.Forward;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Keeps tenant listeners in step with an administrator-owned failover group. */
@Slf4j
@Service
public class AuthorizedEntrySourceMigrationService {
    private static final long MIN_DRAIN_MS = 300_000L;
    private final JdbcTemplate jdbc;
    private final ForwardService forwards;
    private final CrossEntryManagedCleanupService cleanup;
    private final CrossEntryGroupMutationLocks locks;
    private final AuthorizedEntryGrantLocks grantLocks;
    private final TransactionTemplate independent;

    public AuthorizedEntrySourceMigrationService(JdbcTemplate jdbc, ForwardService forwards,
                                                 CrossEntryManagedCleanupService cleanup,
                                                 CrossEntryGroupMutationLocks locks,
                                                 AuthorizedEntryGrantLocks grantLocks,
                                                 PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.forwards = forwards;
        this.cleanup = cleanup;
        this.locks = locks;
        this.grantLocks = grantLocks;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record SourceNode(long nodeId, int tunnelId, String address) {}

    public record Stage(long groupId, List<Long> createdBindings, List<Long> removedBindings,
                        Map<Long, Long> revivedBindings, long drainUntil) {}

    public Stage prepare(long groupId, List<SourceNode> desired, int ttlSeconds) {
        return prepare(groupId, desired, ttlSeconds, null, false);
    }

    public Stage prepareGrantResume(long grantId) {
        List<Map<String, Object>> groups = jdbc.queryForList("SELECT t.source_group_id AS groupId,s.ttl "
                + "FROM authorized_entry_grant g JOIN authorized_entry_template t ON t.id=g.template_id "
                + "JOIN cross_entry_failover_group s ON s.id=t.source_group_id WHERE g.id=? AND s.enabled=1", grantId);
        if (groups.isEmpty()) throw new IllegalStateException("母容灾组已停用，暂不能恢复客户授权");
        long groupId = number(groups.get(0).get("groupId"));
        List<SourceNode> desired = jdbc.queryForList("SELECT m.entry_node_id AS nodeId,f.tunnel_id AS tunnelId,"
                + "m.entry_address AS address FROM cross_entry_failover_member m JOIN forward f ON f.id=m.forward_id "
                + "WHERE m.group_id=? AND m.enabled=1 ORDER BY m.priority", groupId).stream()
                .map(row -> new SourceNode(number(row.get("nodeId")), (int) number(row.get("tunnelId")),
                        Objects.toString(row.get("address"), ""))).toList();
        if (desired.isEmpty()) throw new IllegalStateException("母容灾组没有可用入口");
        return prepare(groupId, desired, (int) number(groups.get(0).get("ttl")), grantId, true);
    }

    private Stage prepare(long groupId, List<SourceNode> desired, int ttlSeconds,
                          Long onlyGrantId, boolean includePaused) {
        List<Map<String, Object>> group = jdbc.queryForList("SELECT user_id AS ownerUserId FROM cross_entry_failover_group WHERE id=?", groupId);
        if (group.isEmpty()) throw new IllegalArgumentException("入口容灾组不存在");
        int owner = ((Number) group.get(0).get("ownerUserId")).intValue();
        Map<Long, SourceNode> targetNodes = new LinkedHashMap<>();
        for (SourceNode node : desired) {
            if (targetNodes.putIfAbsent(node.nodeId(), node) != null) throw new IllegalArgumentException("容灾组存在重复入口节点");
        }
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT m.entry_node_id AS nodeId,f.tunnel_id AS tunnelId "
                + "FROM cross_entry_failover_member m JOIN forward f ON f.id=m.forward_id WHERE m.group_id=?", groupId);
        for (Map<String, Object> row : existing) {
            long nodeId = number(row.get("nodeId"));
            SourceNode target = targetNodes.get(nodeId);
            if (target != null && target.tunnelId() != number(row.get("tunnelId"))) {
                throw new IllegalArgumentException("已有客户授权时不能在同一入口节点更换隧道；同端口无法并行部署，请先使用新节点迁移");
            }
        }
        String portQuery = "SELECT p.id AS portId,p.port,p.target_host AS targetHost,"
                + "p.target_port AS targetPort,p.protocol_mode AS protocolMode,p.state AS portState,"
                + "g.id AS grantId,g.state AS grantState,g.expires_at AS expiresAt,g.flow_limit_bytes AS flowLimitBytes,"
                + "g.used_bytes AS usedBytes FROM authorized_entry_port p "
                + "JOIN authorized_entry_grant g ON g.id=p.grant_id "
                + "JOIN authorized_entry_template t ON t.id=g.template_id "
                + "WHERE t.source_group_id=? AND g.state<>'deleted' AND p.state<>'deleted'";
        List<Map<String, Object>> ports = onlyGrantId == null
                ? jdbc.queryForList(portQuery, groupId)
                : jdbc.queryForList(portQuery + " AND g.id=?", groupId, onlyGrantId);
        String nodePlaceholders = String.join(",", desired.stream().map(node -> "?").toList());
        List<Map<String, Object>> nodeAddresses = desired.isEmpty() ? List.of()
                : jdbc.queryForList("SELECT id,server_ip AS serverIp,ip FROM node WHERE id IN (" + nodePlaceholders + ")",
                        desired.stream().map(SourceNode::nodeId).toArray());
        for (Map<String, Object> port : ports) {
            for (SourceNode node : desired) {
                if (sameAddress(Objects.toString(port.get("targetHost"), ""), node.address())) {
                    throw new IllegalArgumentException("新增入口节点与已有客户落地 IP 相同，迁移会产生循环转发");
                }
            }
            for (Map<String, Object> node : nodeAddresses) {
                for (String field : List.of("serverIp", "ip")) {
                    for (String address : Objects.toString(node.get(field), "").split("[,\\s]+")) {
                        if (!address.isEmpty() && sameAddress(Objects.toString(port.get("targetHost"), ""), address)) {
                            throw new IllegalArgumentException("已有客户落地 IP 与新增入口节点地址相同，迁移会产生循环转发");
                        }
                    }
                }
            }
        }
        List<Long> created = new ArrayList<>();
        List<Long> removed = new ArrayList<>();
        Map<Long, Long> revived = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        long drain = now + Math.max(MIN_DRAIN_MS, Math.min(86_400_000L, Math.max(0L, (long) ttlSeconds) * 2_000L));
        try {
            for (Map<String, Object> port : ports) {
                synchronized (grantLocks.forGrant(number(port.get("grantId")))) {
                    List<Map<String, Object>> current = jdbc.queryForList("SELECT p.state AS portState,g.state AS grantState,"
                            + "g.expires_at AS expiresAt,g.flow_limit_bytes AS flowLimitBytes,g.used_bytes AS usedBytes "
                            + "FROM authorized_entry_port p JOIN authorized_entry_grant g ON g.id=p.grant_id "
                            + "WHERE p.id=? FOR UPDATE", number(port.get("portId")));
                    if (current.isEmpty() || "deleted".equals(current.get(0).get("portState"))) continue;
                    port.putAll(current.get(0));
                    long portId = number(port.get("portId"));
                    Map<Long, Map<String, Object>> bindings = new HashMap<>();
                    for (Map<String, Object> binding : jdbc.queryForList("SELECT id,forward_id AS forwardId,node_id AS nodeId,"
                            + "retire_at AS retireAt FROM authorized_entry_forward WHERE port_id=?", portId)) {
                        if (bindings.put(number(binding.get("nodeId")), binding) != null) {
                            throw new IllegalStateException("客户授权端口在同一节点存在重复隐藏转发，请先修复资源");
                        }
                    }
                    for (Map<String, Object> binding : bindings.values()) {
                        long bindingId = number(binding.get("id"));
                        if (!targetNodes.containsKey(number(binding.get("nodeId")))) removed.add(bindingId);
                        else if (binding.get("retireAt") != null) revived.put(bindingId, number(binding.get("retireAt")));
                    }
                    if (includePaused
                            ? "deleted".equals(port.get("portState")) || "deleted".equals(port.get("grantState"))
                            : !"active".equals(port.get("portState")) || !"active".equals(port.get("grantState"))
                            || (port.get("expiresAt") != null && number(port.get("expiresAt")) <= now)
                            || (number(port.get("flowLimitBytes")) > 0 && number(port.get("usedBytes")) >= number(port.get("flowLimitBytes")))) {
                        continue;
                    }
                    for (SourceNode node : desired) {
                        if (bindings.containsKey(node.nodeId())) {
                            Map<String, Object> binding = bindings.get(node.nodeId());
                            if (binding.get("retireAt") != null && number(binding.get("retireAt")) <= now) {
                                throw new IllegalStateException("客户旧入口正在清理，请等待清理完成后重试迁移");
                            }
                            Forward forward = forwards.getById(number(binding.get("forwardId")));
                            if (forward == null || forward.getTunnelId() != node.tunnelId()) {
                                throw new IllegalStateException("客户授权端口的隐藏转发与入口隧道不一致，请先修复资源");
                            }
                            if (!includePaused && !Objects.equals(forward.getStatus(), 1)) {
                                throw new IllegalStateException("客户授权端口的原入口转发未运行，请先修复再调整母容灾组");
                            }
                            continue;
                        }
                        long forwardId = createForward(groupId, owner, port, node);
                        try {
                            if (includePaused && !"active".equals(port.get("portState"))) {
                                R pause = independent.execute(status -> forwards.pauseManagedForward(forwardId));
                                if (pause == null || pause.getCode() != 0) {
                                    throw new IllegalStateException(pause == null ? "新入口暂停失败" : pause.getMsg());
                                }
                            }
                            long bindingId = independent.execute(status -> {
                                long timestamp = System.currentTimeMillis();
                                jdbc.update("INSERT INTO authorized_entry_forward (port_id,forward_id,node_id,retire_at,created_time,updated_time) "
                                        + "VALUES (?,?,?,?,?,?)", portId, forwardId, node.nodeId(), drain, timestamp, timestamp);
                                return Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
                            });
                            created.add(bindingId);
                        } catch (RuntimeException e) {
                            deleteOrQueue(groupId, forwardId, portId, node.nodeId());
                            throw e;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            abort(new Stage(groupId, created, List.of(), Map.of(), drain));
            throw new IllegalStateException("客户隐藏入口预部署失败，母容灾组未切换：" + e.getMessage(), e);
        }
        return new Stage(groupId, List.copyOf(created), List.copyOf(removed), Map.copyOf(revived), drain);
    }

    private long createForward(long groupId, int owner, Map<String, Object> port, SourceNode node) {
        ForwardDto input = new ForwardDto();
        input.setName("授权入口 " + port.get("portId") + " · " + node.nodeId());
        input.setTunnelId(node.tunnelId());
        input.setInPort((int) number(port.get("port")));
        String host = Objects.toString(port.get("targetHost"));
        input.setRemoteAddr((host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host)
                + ":" + number(port.get("targetPort")));
        input.setProtocolMode(Objects.toString(port.get("protocolMode"), "tcp"));
        input.setRouteBalanceStrategy("round");
        R result = independent.execute(status -> forwards.createManagedForward(input, owner));
        if (result == null || result.getCode() != 0) throw new IllegalStateException(result == null ? "转发服务无响应" : result.getMsg());
        Object data = result.getData();
        Object id = data instanceof Map<?, ?> map ? map.get("id") : null;
        if (!(id instanceof Number)) throw new IllegalStateException("新入口托管转发没有返回资源 ID");
        long forwardId = ((Number) id).longValue();
        Forward deployed = independent.execute(status -> forwards.getById(forwardId));
        if (deployed == null || !Objects.equals(deployed.getStatus(), 1)
                || !Objects.equals(deployed.getTunnelId(), node.tunnelId())
                || !Objects.equals(deployed.getInPort(), (int) number(port.get("port")))) {
            deleteOrQueue(groupId, forwardId, number(port.get("portId")), node.nodeId());
            throw new IllegalStateException("新入口转发创建后未能确认运行状态、隧道和监听端口");
        }
        return forwardId;
    }

    public void complete(Stage stage) {
        if (stage == null) return;
        long now = System.currentTimeMillis();
        for (Long id : stage.createdBindings()) {
            jdbc.update("UPDATE authorized_entry_forward SET retire_at=NULL,updated_time=? WHERE id=?", now, id);
        }
        for (Long id : stage.revivedBindings().keySet()) {
            jdbc.update("UPDATE authorized_entry_forward SET retire_at=NULL,updated_time=? WHERE id=?", now, id);
        }
        for (Long id : stage.removedBindings()) {
            jdbc.update("UPDATE authorized_entry_forward SET retire_at=?,updated_time=? WHERE id=? AND retire_at IS NULL",
                    stage.drainUntil(), now, id);
        }
    }

    public void completeInNewTransaction(Stage stage) {
        independent.executeWithoutResult(status -> complete(stage));
    }

    public void preserveCreatedBindings(Stage stage) {
        independent.executeWithoutResult(status -> {
            for (Long id : stage.createdBindings()) {
                jdbc.update("UPDATE authorized_entry_forward SET retire_at=NULL,updated_time=? WHERE id=?",
                        System.currentTimeMillis(), id);
            }
        });
    }

    public void retirePreservedBindings(List<Long> bindingIds, long drainMillis) {
        independent.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            for (Long id : bindingIds) {
                jdbc.update("UPDATE authorized_entry_forward SET retire_at=?,updated_time=? WHERE id=? AND retire_at IS NULL",
                        now + drainMillis, now, id);
            }
        });
    }

    public void abort(Stage stage) {
        if (stage == null) return;
        independent.executeWithoutResult(status -> {
            for (Map.Entry<Long, Long> revived : stage.revivedBindings().entrySet()) {
                jdbc.update("UPDATE authorized_entry_forward SET retire_at=?,updated_time=? WHERE id=? AND retire_at IS NULL",
                        revived.getValue(), System.currentTimeMillis(), revived.getKey());
            }
            for (Long id : stage.removedBindings()) {
                jdbc.update("UPDATE authorized_entry_forward SET retire_at=NULL,updated_time=? WHERE id=?",
                        System.currentTimeMillis(), id);
            }
        });
        for (Long id : stage.createdBindings()) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT forward_id AS forwardId,port_id AS portId,node_id AS nodeId "
                    + "FROM authorized_entry_forward WHERE id=?", id);
            if (rows.isEmpty()) continue;
            Map<String, Object> row = rows.get(0);
            deleteOrQueue(stage.groupId(), number(row.get("forwardId")), number(row.get("portId")), number(row.get("nodeId")));
        }
    }

    private void deleteOrQueue(long groupId, long forwardId, long portId, long nodeId) {
        try {
            R result = independent.execute(status -> forwards.deleteManagedForward(forwardId));
            if (result != null && (result.getCode() == 0 || Objects.toString(result.getMsg(), "").contains("不存在"))) {
                independent.executeWithoutResult(status -> jdbc.update("DELETE FROM authorized_entry_forward WHERE port_id=? AND forward_id=?", portId, forwardId));
                return;
            }
            log.warn("授权入口迁移转发清理待重试 groupId={} forwardId={}: {}", groupId, forwardId, result == null ? "无响应" : result.getMsg());
        } catch (RuntimeException e) {
            log.warn("授权入口迁移转发清理待重试 groupId={} forwardId={}", groupId, forwardId, e);
        }
        independent.executeWithoutResult(status -> jdbc.update("UPDATE authorized_entry_forward SET retire_at=?,updated_time=? WHERE port_id=? AND forward_id=?",
                System.currentTimeMillis(), System.currentTimeMillis(), portId, forwardId));
        Integer tracked = jdbc.queryForObject("SELECT COUNT(*) FROM authorized_entry_forward WHERE port_id=? AND forward_id=?",
                Integer.class, portId, forwardId);
        if (tracked == null || tracked == 0) {
            cleanup.enqueue(List.of(new CrossEntryManagedCleanupService.Item(groupId, forwardId, null, nodeId,
                    null, null, "auto", "tcp", false)), "客户隐藏入口迁移后清理重试");
        }
    }

    public boolean hasBindingsOnNode(long groupId, long nodeId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM authorized_entry_forward b "
                + "JOIN authorized_entry_port p ON p.id=b.port_id JOIN authorized_entry_grant g ON g.id=p.grant_id "
                + "JOIN authorized_entry_template t ON t.id=g.template_id WHERE t.source_group_id=? AND b.node_id=?",
                Integer.class, groupId, nodeId);
        return count != null && count > 0;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 30_000)
    public void retireDueBindings() {
        try {
            for (Map<String, Object> row : jdbc.queryForList("SELECT b.id,b.forward_id AS forwardId,b.port_id AS portId,"
                    + "b.node_id AS nodeId,t.source_group_id AS groupId FROM authorized_entry_forward b "
                    + "JOIN authorized_entry_port p ON p.id=b.port_id JOIN authorized_entry_grant g ON g.id=p.grant_id "
                    + "JOIN authorized_entry_template t ON t.id=g.template_id WHERE b.retire_at IS NOT NULL "
                    + "AND b.retire_at<=? ORDER BY b.retire_at LIMIT 50", System.currentTimeMillis())) {
                long groupId = number(row.get("groupId"));
                locks.withLock(groupId, () -> {
                    List<Long> stillDue = jdbc.queryForList("SELECT id FROM authorized_entry_forward WHERE id=? AND retire_at<=?", Long.class,
                            number(row.get("id")), System.currentTimeMillis());
                    if (!stillDue.isEmpty()) deleteOrQueue(groupId, number(row.get("forwardId")), number(row.get("portId")), number(row.get("nodeId")));
                    return null;
                });
            }
        } catch (RuntimeException e) {
            log.warn("授权入口旧线路清理将在下一轮重试：{}", e.getMessage());
        }
    }

    private static long number(Object value) { return ((Number) value).longValue(); }

    private static boolean sameAddress(String left, String right) {
        String candidate = left.startsWith("[") && left.endsWith("]") ? left.substring(1, left.length() - 1) : left;
        try { return IpLiteralUtil.normalize(candidate).equals(IpLiteralUtil.normalize(right)); }
        catch (IllegalArgumentException e) { return left.equalsIgnoreCase(right); }
    }
}
