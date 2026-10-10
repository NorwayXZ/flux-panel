package com.admin.service;

import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Updates the existing router policy lists within the Smart Entry save transaction. */
@Service
public class SmartEntryDnsBindingsService {
    private final JdbcTemplate jdbc;

    public SmartEntryDnsBindingsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> agents() {
        List<Map<String, Object>> agents = jdbc.queryForList("SELECT id,name FROM internal_connector "
                + "WHERE connector_role='openwrt_dns' AND status=1 ORDER BY id");
        agents.forEach(agent -> agent.put("online", WebSocketServer.isConnectorOnline(((Number) agent.get("id")).longValue())));
        return agents;
    }

    public Map<Long, List<Long>> bindings() {
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT r.connector_id,r.smart_entry_group_ids FROM openwrt_dns_resolver r "
                + "JOIN internal_connector c ON c.id=r.connector_id WHERE c.status=1 AND c.connector_role='openwrt_dns'")) {
            long agentId = ((Number) row.get("connector_id")).longValue();
            for (Long groupId : ids(row.get("smart_entry_group_ids")))
                result.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(agentId);
        }
        return result;
    }

    public void setBindings(long groupId, List<Long> selected) {
        if (selected == null) return; // Legacy edits must not silently remove router bindings.
        Set<Long> desired = new LinkedHashSet<>(selected);
        if (desired.size() > 256 || desired.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("OpenWrt Agent 选择无效");
        long now = System.currentTimeMillis();
        // Lock all policy rows in a stable order; configure/remove also update these rows transactionally.
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT connector_id,smart_entry_group_ids FROM openwrt_dns_resolver ORDER BY connector_id FOR UPDATE");
        Set<Long> existingIds = new LinkedHashSet<>();
        rows.forEach(row -> existingIds.add(((Number) row.get("connector_id")).longValue()));
        for (Long agentId : desired.stream().sorted().toList()) {
            List<Map<String, Object>> agents = jdbc.queryForList("SELECT id,user_id FROM internal_connector "
                    + "WHERE id=? AND connector_role='openwrt_dns' AND status=1 FOR UPDATE", agentId);
            if (agents.isEmpty()) throw new IllegalArgumentException("所选 OpenWrt Agent 不存在或已删除");
            if (!existingIds.contains(agentId)) {
                jdbc.update("INSERT INTO openwrt_dns_resolver (connector_id,user_id,interface_carriers,smart_entry_group_ids,created_time,updated_time) "
                        + "VALUES (?,?,'{}','[]',?,?) ON DUPLICATE KEY UPDATE connector_id=VALUES(connector_id)",
                        agentId, agents.get(0).get("user_id"), now, now);
            }
        }
        rows = jdbc.queryForList("SELECT connector_id,smart_entry_group_ids,interface_carriers FROM openwrt_dns_resolver ORDER BY connector_id FOR UPDATE");
        for (Map<String, Object> row : rows) {
            long agentId = ((Number) row.get("connector_id")).longValue();
            List<Long> old = ids(row.get("smart_entry_group_ids"));
            Set<Long> next = new LinkedHashSet<>(old);
            if (desired.contains(agentId)) next.add(groupId); else next.remove(groupId);
            if (next.size() > 256) throw new IllegalArgumentException("单个 OpenWrt 最多绑定 256 个策略");
            boolean automatic = desired.contains(agentId);
            if (!next.equals(new LinkedHashSet<>(old)) || (automatic && !"{}".equals(Objects.toString(row.get("interface_carriers"), "{}"))))
                jdbc.update("UPDATE openwrt_dns_resolver SET smart_entry_group_ids=?,interface_carriers=IF(?,'{}',interface_carriers),policy_revision=policy_revision+1,policy_hash=NULL,"
                                + "last_error=NULL,sync_error=NULL,updated_time=? WHERE connector_id=?",
                        JSON.toJSONString(next), automatic, now, agentId);
        }
    }

    private List<Long> ids(Object value) {
        List<Long> result = JSON.parseArray(Objects.toString(value, "[]"), Long.class);
        return result == null ? List.of() : result;
    }
}
