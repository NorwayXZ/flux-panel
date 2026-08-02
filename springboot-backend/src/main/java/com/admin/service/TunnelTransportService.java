package com.admin.service;

import com.admin.common.dto.TunnelHopConfigDto;
import com.admin.common.dto.TunnelHopDetailDto;
import com.admin.common.utils.IpLiteralUtil;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TunnelTransportService {
    private final JdbcTemplate jdbcTemplate;

    public TunnelTransportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String validateAndSerialize(List<Node> pathNodes, List<TunnelHopConfigDto> requested) {
        if (pathNodes == null || pathNodes.size() < 2) return null;
        List<TunnelHopConfigDto> normalized = normalize(pathNodes, requested);
        for (int index = 0; index < normalized.size(); index++) {
            resolve(pathNodes.get(index), pathNodes.get(index + 1), normalized.get(index), true);
        }
        return JSON.toJSONString(normalized);
    }

    public List<ResolvedHop> resolve(Tunnel tunnel, List<Node> pathNodes) {
        if (pathNodes == null || pathNodes.size() < 2) return Collections.emptyList();
        List<TunnelHopConfigDto> configs = parse(tunnel == null ? null : tunnel.getHopConfig());
        configs = normalize(pathNodes, configs);
        List<ResolvedHop> result = new ArrayList<>();
        for (int index = 0; index < configs.size(); index++) {
            result.add(resolve(pathNodes.get(index), pathNodes.get(index + 1), configs.get(index), true));
        }
        return result;
    }

    public List<TunnelHopDetailDto> details(Tunnel tunnel, List<Node> pathNodes) {
        List<TunnelHopDetailDto> details = new ArrayList<>();
        try {
            for (ResolvedHop hop : resolve(tunnel, pathNodes)) details.add(hop.detail());
        } catch (RuntimeException e) {
            List<TunnelHopConfigDto> configs = normalize(pathNodes, parse(tunnel == null ? null : tunnel.getHopConfig()));
            for (int index = 0; index < configs.size(); index++) {
                Node from = pathNodes.get(index);
                Node to = pathNodes.get(index + 1);
                TunnelHopConfigDto config = configs.get(index);
                TunnelHopDetailDto detail = baseDetail(from, to, config);
                detail.setVerificationState("invalid");
                detail.setTargetAddress(e.getMessage());
                details.add(detail);
            }
        }
        return details;
    }

    public List<TunnelHopConfigDto> parse(String value) {
        if (value == null || value.isBlank()) return Collections.emptyList();
        try {
            List<TunnelHopConfigDto> configs = JSON.parseArray(value, TunnelHopConfigDto.class);
            return configs == null ? Collections.emptyList() : configs;
        } catch (Exception e) {
            throw new IllegalStateException("隧道逐跳地址配置损坏，请重新保存隧道");
        }
    }

    public String firstTunnelUsing(String addressMode, Long resourceGroupId) {
        if (resourceGroupId == null) return null;
        List<Map<String, Object>> tunnels = jdbcTemplate.queryForList(
                "SELECT id,name,hop_config FROM tunnel WHERE hop_config IS NOT NULL AND hop_config<>''");
        for (Map<String, Object> tunnel : tunnels) {
            List<TunnelHopConfigDto> configs;
            try {
                configs = parse(String.valueOf(tunnel.get("hop_config")));
            } catch (IllegalStateException ignored) {
                continue;
            }
            boolean used = configs.stream().anyMatch(config -> Objects.equals(resourceGroupId, config.getResourceGroupId())
                    && Objects.equals(normalizeMode(addressMode), normalizeMode(config.getAddressMode())));
            if (used) return String.valueOf(tunnel.get("name"));
        }
        return null;
    }

    private List<TunnelHopConfigDto> normalize(List<Node> pathNodes, List<TunnelHopConfigDto> requested) {
        Map<String, TunnelHopConfigDto> byEdge = new LinkedHashMap<>();
        if (requested != null) {
            for (TunnelHopConfigDto config : requested) {
                if (config != null && config.getFromNodeId() != null && config.getToNodeId() != null) {
                    byEdge.put(config.getFromNodeId() + ":" + config.getToNodeId(), config);
                }
            }
        }
        List<TunnelHopConfigDto> result = new ArrayList<>();
        for (int index = 0; index < pathNodes.size() - 1; index++) {
            Node from = pathNodes.get(index);
            Node to = pathNodes.get(index + 1);
            TunnelHopConfigDto source = byEdge.get(from.getId() + ":" + to.getId());
            TunnelHopConfigDto config = new TunnelHopConfigDto();
            config.setFromNodeId(from.getId());
            config.setToNodeId(to.getId());
            config.setAddressMode(normalizeMode(source == null ? null : source.getAddressMode()));
            config.setResourceGroupId(source == null ? null : source.getResourceGroupId());
            config.setCustomAddress(source == null ? null : blankToNull(source.getCustomAddress()));
            config.setFallbackMode(normalizeFallback(source == null ? null : source.getFallbackMode()));
            result.add(config);
        }
        return result;
    }

    private ResolvedHop resolve(Node from, Node to, TunnelHopConfigDto config, boolean requireVerified) {
        String mode = normalizeMode(config.getAddressMode());
        String primary;
        String groupName = null;
        String verificationState = "not_required";
        Long verifiedAt = null;
        if ("private".equals(mode)) {
            if (config.getResourceGroupId() == null) throw new IllegalArgumentException(edge(from, to) + " 未选择原生内网组");
            Map<String, Object> group = one("SELECT id,name,state FROM private_network_group WHERE id=?", config.getResourceGroupId());
            if (group == null) throw new IllegalArgumentException(edge(from, to) + " 所选内网组不存在");
            Map<String, Object> sourceMember = member("private_network_member", "private_address", config.getResourceGroupId(), from.getId());
            Map<String, Object> targetMember = member("private_network_member", "private_address", config.getResourceGroupId(), to.getId());
            if (sourceMember == null || targetMember == null) throw new IllegalArgumentException(edge(from, to) + " 的两端不在同一个内网组");
            Map<String, Object> link = one("SELECT state,verified_at FROM private_network_link WHERE group_id=? AND source_node_id=? AND target_node_id=?",
                    config.getResourceGroupId(), from.getId(), to.getId());
            verificationState = link == null ? "pending" : String.valueOf(link.get("state"));
            verifiedAt = link == null || link.get("verified_at") == null ? null : number(link.get("verified_at"));
            if (requireVerified && (!"active".equals(group.get("state")) || !"verified".equals(verificationState))) {
                throw new IllegalArgumentException(edge(from, to) + " 的原生内网尚未双向验证通过");
            }
            primary = String.valueOf(targetMember.get("private_address"));
            groupName = String.valueOf(group.get("name"));
        } else if ("virtual".equals(mode)) {
            if (config.getResourceGroupId() == null) throw new IllegalArgumentException(edge(from, to) + " 未选择自动组网");
            Map<String, Object> network = one("SELECT id,name,state FROM virtual_lan_network WHERE id=?", config.getResourceGroupId());
            if (network == null) throw new IllegalArgumentException(edge(from, to) + " 所选自动组网不存在");
            Map<String, Object> sourceMember = member("virtual_lan_member", "virtual_ip", config.getResourceGroupId(), from.getId());
            Map<String, Object> targetMember = member("virtual_lan_member", "virtual_ip", config.getResourceGroupId(), to.getId());
            if (sourceMember == null || targetMember == null) throw new IllegalArgumentException(edge(from, to) + " 的两端不在同一个自动组网中");
            verificationState = String.valueOf(network.get("state"));
            if (requireVerified && !"active".equals(verificationState)) throw new IllegalArgumentException(edge(from, to) + " 的自动组网当前不可用");
            primary = String.valueOf(targetMember.get("virtual_ip"));
            groupName = String.valueOf(network.get("name"));
        } else if ("custom".equals(mode)) {
            primary = validAddress(config.getCustomAddress(), edge(from, to));
        } else {
            primary = firstNonBlank(to.getServerIp(), to.getIp());
            if (primary == null) throw new IllegalArgumentException(to.getName() + " 没有可用公网地址");
        }
        String fallback = null;
        List<String> candidates = new ArrayList<>();
        candidates.add(primary);
        if (!"public".equals(mode) && "public".equals(normalizeFallback(config.getFallbackMode()))) {
            fallback = firstNonBlank(to.getServerIp(), to.getIp());
            if (fallback != null && !Objects.equals(fallback, primary)) candidates.add(fallback);
        }
        TunnelHopDetailDto detail = baseDetail(from, to, config);
        detail.setResourceGroupName(groupName);
        detail.setTargetAddress(primary);
        detail.setFallbackAddress(fallback);
        detail.setVerificationState(verificationState);
        detail.setVerifiedAt(verifiedAt);
        detail.setCandidates(candidates);
        return new ResolvedHop(from, to, mode, primary, fallback, candidates, detail);
    }

    private Map<String, Object> member(String table, String addressColumn, Long groupId, Long nodeId) {
        if ("virtual_lan_member".equals(table)) {
            return one("SELECT target_id AS node_id," + addressColumn + " FROM virtual_lan_member "
                    + "WHERE network_id=? AND target_type='node' AND target_id=?", groupId, nodeId);
        }
        return one("SELECT node_id," + addressColumn + " FROM private_network_member WHERE group_id=? AND node_id=?", groupId, nodeId);
    }

    private static TunnelHopDetailDto baseDetail(Node from, Node to, TunnelHopConfigDto config) {
        TunnelHopDetailDto detail = new TunnelHopDetailDto();
        detail.setFromNodeId(from.getId()); detail.setFromNodeName(from.getName());
        detail.setToNodeId(to.getId()); detail.setToNodeName(to.getName());
        String mode = normalizeMode(config.getAddressMode());
        detail.setAddressMode(mode);
        detail.setAddressModeName(Map.of("public", "公网", "private", "原生内网", "virtual", "自动组网", "custom", "自定义地址").get(mode));
        detail.setResourceGroupId(config.getResourceGroupId());
        detail.setFallbackMode(normalizeFallback(config.getFallbackMode()));
        return detail;
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String normalizeMode(String value) {
        String mode = value == null ? "public" : value.trim().toLowerCase(Locale.ROOT);
        return Set.of("public", "private", "virtual", "custom").contains(mode) ? mode : "public";
    }

    private static String normalizeFallback(String value) {
        return "public".equalsIgnoreCase(value) ? "public" : "fail_closed";
    }

    private static String validAddress(String value, String edge) {
        try {
            String address = blankToNull(value);
            if (address == null) throw new IllegalArgumentException();
            return IpLiteralUtil.normalize(address);
        } catch (Exception e) {
            throw new IllegalArgumentException(edge + " 的自定义地址必须是有效 IP");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private static String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static String edge(Node from, Node to) { return from.getName() + "→" + to.getName(); }
    private static Long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(String.valueOf(value)); }

    public static final class ResolvedHop {
        private final Node from;
        private final Node to;
        private final String mode;
        private final String primaryAddress;
        private final String fallbackAddress;
        private final List<String> candidates;
        private final TunnelHopDetailDto detail;

        ResolvedHop(Node from, Node to, String mode, String primaryAddress, String fallbackAddress,
                    List<String> candidates, TunnelHopDetailDto detail) {
            this.from = from; this.to = to; this.mode = mode; this.primaryAddress = primaryAddress;
            this.fallbackAddress = fallbackAddress; this.candidates = candidates; this.detail = detail;
        }

        public Node from() { return from; }
        public Node to() { return to; }
        public String mode() { return mode; }
        public String primaryAddress() { return primaryAddress; }
        public String fallbackAddress() { return fallbackAddress; }
        public List<String> candidates() { return candidates; }
        public TunnelHopDetailDto detail() { return detail; }
    }
}
