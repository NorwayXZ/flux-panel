package com.admin.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Service
public class SchedulingConflictService {
    private static final String SMART_ENTRY = "smart_entry";
    private static final String CROSS_ENTRY = "cross_entry";
    private static final String SOURCE_IP_ENTRY = "source_ip_entry";

    private final JdbcTemplate jdbcTemplate;

    public SchedulingConflictService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void assertDnsRecordAvailable(String ownerType, Long ownerId, String domain, String recordType) {
        String normalizedDomain = StringUtils.lowerCase(StringUtils.trimToEmpty(domain), Locale.ROOT);
        String normalizedType = StringUtils.upperCase(StringUtils.defaultIfBlank(recordType, "A"), Locale.ROOT);
        if (StringUtils.isBlank(normalizedDomain)) return;

        if (!SMART_ENTRY.equals(ownerType)) {
            List<Map<String, Object>> smart = jdbcTemplate.queryForList(
                    "SELECT id,name FROM smart_entry_group WHERE LOWER(domain)=? AND UPPER(record_type)=?",
                    normalizedDomain, normalizedType);
            if (!smart.isEmpty()) {
                throw new IllegalArgumentException("该 DNS 记录已由三网优化接管：" + label(smart.get(0)) + "，请先删除或更换记录");
            }
        }
        if (!CROSS_ENTRY.equals(ownerType)) {
            List<Map<String, Object>> cross = jdbcTemplate.queryForList(
                    "SELECT id,name FROM cross_entry_failover_group WHERE LOWER(domain)=? AND UPPER(record_type)=?",
                    normalizedDomain, normalizedType);
            if (!cross.isEmpty()) {
                throw new IllegalArgumentException("该 DNS 记录已由入口容灾接管：" + label(cross.get(0)) + "，请先删除或更换记录");
            }
        }
    }

    public void assertForwardSetAvailable(String ownerType, Long ownerId, Collection<Long> forwardIds) {
        Set<Long> requested = normalizedForwardSet(forwardIds);
        if (requested.size() < 2) return;

        if (!SMART_ENTRY.equals(ownerType)) {
            Conflict conflict = exactForwardSetConflict("三网优化", SMART_ENTRY, "smart_entry_group", "smart_entry_route",
                    "forward_id", requested, ownerIdFor(ownerType, SMART_ENTRY, ownerId), "");
            if (conflict != null) throw conflict.toException();
        }
        if (!CROSS_ENTRY.equals(ownerType)) {
            Conflict conflict = exactForwardSetConflict("入口容灾", CROSS_ENTRY, "cross_entry_failover_group", "cross_entry_failover_member",
                    "forward_id", requested, ownerIdFor(ownerType, CROSS_ENTRY, ownerId), "");
            if (conflict != null) throw conflict.toException();
        }
        if (!SOURCE_IP_ENTRY.equals(ownerType)) {
            Conflict conflict = exactForwardSetConflict("来源 IP 分流", SOURCE_IP_ENTRY, "source_ip_entry_group", "source_ip_entry_route",
                    "backend_forward_id", requested, ownerIdFor(ownerType, SOURCE_IP_ENTRY, ownerId), "AND g.state<>'deleted'");
            if (conflict != null) throw conflict.toException();
        }
    }

    private Conflict exactForwardSetConflict(String moduleLabel, String moduleType, String groupTable, String routeTable,
                                             String forwardColumn, Set<Long> requested, Long excludedId,
                                             String extraWhere) {
        String excluded = excludedId == null ? "" : " AND g.id<>?";
        List<Object> args = new ArrayList<>();
        if (excludedId != null) args.add(excludedId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT g.id AS groupId,g.name AS groupName,r." + forwardColumn + " AS forwardId "
                        + "FROM " + groupTable + " g JOIN " + routeTable + " r ON r.group_id=g.id "
                        + "WHERE 1=1 " + extraWhere + excluded,
                args.toArray());
        Map<Long, ExistingGroup> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long groupId = number(row.get("groupId"));
            ExistingGroup group = groups.computeIfAbsent(groupId,
                    ignored -> new ExistingGroup(groupId, Objects.toString(row.get("groupName"), moduleLabel), new TreeSet<>()));
            group.forwardIds().add(number(row.get("forwardId")));
        }
        for (ExistingGroup group : groups.values()) {
            if (group.forwardIds().equals(requested)) {
                return new Conflict(moduleLabel, moduleType, group.id(), group.name(), requested);
            }
        }
        return null;
    }

    private Long ownerIdFor(String currentOwnerType, String targetOwnerType, Long ownerId) {
        return targetOwnerType.equals(currentOwnerType) ? ownerId : null;
    }

    private Set<Long> normalizedForwardSet(Collection<Long> values) {
        Set<Long> result = new TreeSet<>();
        if (values == null) return result;
        for (Long value : values) {
            if (value != null && value > 0) result.add(value);
        }
        return result;
    }

    private String label(Map<String, Object> row) {
        return Objects.toString(row.get("name"), "ID " + row.get("id"));
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(Objects.toString(value, "0"));
    }

    private record ExistingGroup(long id, String name, Set<Long> forwardIds) {
    }

    private record Conflict(String moduleLabel, String moduleType, long groupId, String groupName, Set<Long> forwardIds) {
        IllegalArgumentException toException() {
            return new IllegalArgumentException("这组入口转发已由" + moduleLabel + "接管：" + groupName
                    + "，不能再交给另一个调度策略管理");
        }
    }
}
