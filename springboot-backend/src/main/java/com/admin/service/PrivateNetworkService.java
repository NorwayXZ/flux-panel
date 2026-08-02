package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.PrivateNetworkSaveDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.IpLiteralUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSONObject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PrivateNetworkService {
    public static final String MIN_AGENT_VERSION = "2.45.0";

    private final JdbcTemplate jdbcTemplate;
    private final NodeMapper nodeMapper;
    private final TunnelTransportService tunnelTransportService;
    private final SecureRandom random = new SecureRandom();

    public PrivateNetworkService(JdbcTemplate jdbcTemplate, NodeMapper nodeMapper, TunnelTransportService tunnelTransportService) {
        this.jdbcTemplate = jdbcTemplate;
        this.nodeMapper = nodeMapper;
        this.tunnelTransportService = tunnelTransportService;
    }

    public R overview() {
        List<Map<String, Object>> nodes = jdbcTemplate.queryForList(
                "SELECT id,name,server_ip AS serverIp,ip,status,version FROM node ORDER BY name,id");
        List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                "SELECT id,name,network_type AS networkType,cidr,state,last_error AS lastError,created_time AS createdTime,updated_time AS updatedTime "
                        + "FROM private_network_group ORDER BY id DESC");
        for (Map<String, Object> group : groups) {
            Long groupId = number(group.get("id"));
            group.put("members", jdbcTemplate.queryForList(
                    "SELECT m.id,m.group_id AS groupId,m.node_id AS nodeId,n.name AS nodeName,n.status AS nodeStatus,n.version AS nodeVersion,"
                            + "m.private_address AS privateAddress,m.interface_name AS interfaceName,m.mtu,m.updated_time AS updatedTime "
                            + "FROM private_network_member m LEFT JOIN node n ON n.id=m.node_id WHERE m.group_id=? ORDER BY m.id", groupId));
            group.put("links", jdbcTemplate.queryForList(
                    "SELECT l.id,l.source_node_id AS sourceNodeId,src.name AS sourceNodeName,l.target_node_id AS targetNodeId,dst.name AS targetNodeName,"
                            + "l.source_address AS sourceAddress,l.target_address AS targetAddress,l.route_info AS routeInfo,l.interface_name AS interfaceName,"
                            + "l.state,l.latency_ms AS latencyMs,l.packet_loss AS packetLoss,l.last_error AS lastError,l.verified_at AS verifiedAt "
                            + "FROM private_network_link l LEFT JOIN node src ON src.id=l.source_node_id LEFT JOIN node dst ON dst.id=l.target_node_id "
                            + "WHERE l.group_id=? ORDER BY l.source_node_id,l.target_node_id", groupId));
        }
        return R.ok(Map.of("minimumAgentVersion", MIN_AGENT_VERSION, "nodes", nodes, "groups", groups));
    }

    @Transactional
    public R save(PrivateNetworkSaveDto dto) {
        try {
            validate(dto);
            long now = System.currentTimeMillis();
            Long id = dto.getId();
            if (id == null) {
                jdbcTemplate.update("INSERT INTO private_network_group(name,network_type,cidr,state,created_time,updated_time) VALUES (?,?,?,'pending',?,?)",
                        dto.getName().trim(), normalizeType(dto.getNetworkType()), blankToNull(dto.getCidr()), now, now);
                id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            } else {
                String usedBy = tunnelTransportService.firstTunnelUsing("private", id);
                if (usedBy != null) return R.err("该内网组正被线路使用，请先删除出口应用或修改线路：" + usedBy);
                Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM private_network_group WHERE id=?", Integer.class, id);
                if (exists == null || exists == 0) return R.err("内网资源组不存在");
                jdbcTemplate.update("UPDATE private_network_group SET name=?,network_type=?,cidr=?,state='pending',last_error=NULL,updated_time=? WHERE id=?",
                        dto.getName().trim(), normalizeType(dto.getNetworkType()), blankToNull(dto.getCidr()), now, id);
                jdbcTemplate.update("DELETE FROM private_network_link WHERE group_id=?", id);
                jdbcTemplate.update("DELETE FROM private_network_member WHERE group_id=?", id);
            }
            for (PrivateNetworkSaveDto.Member member : dto.getMembers()) {
                jdbcTemplate.update("INSERT INTO private_network_member(group_id,node_id,private_address,interface_name,mtu,created_time,updated_time) VALUES (?,?,?,?,?,?,?)",
                        id, member.getNodeId(), normalizeAddress(member.getPrivateAddress()), blankToNull(member.getInterfaceName()),
                        member.getMtu() == null ? 1500 : member.getMtu(), now, now);
            }
            return overview();
        } catch (DuplicateKeyException e) {
            return R.err("内网组名称、节点或内网地址重复");
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
    }

    public R verify(Long groupId) {
        Map<String, Object> group = one("SELECT * FROM private_network_group WHERE id=?", groupId);
        if (group == null) return R.err("内网资源组不存在");
        List<Map<String, Object>> members = jdbcTemplate.queryForList("SELECT * FROM private_network_member WHERE group_id=? ORDER BY id", groupId);
        if (members.size() < 2) return R.err("至少需要两台服务器才能验证内网");
        jdbcTemplate.update("UPDATE private_network_group SET state='verifying',last_error=NULL,updated_time=? WHERE id=?", System.currentTimeMillis(), groupId);
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> source : members) {
            for (Map<String, Object> target : members) {
                if (Objects.equals(source.get("node_id"), target.get("node_id"))) continue;
                String error = verifyLink(groupId, source, target);
                if (error != null) failures.add(nodeName(source) + "→" + nodeName(target) + "：" + error);
            }
        }
        long now = System.currentTimeMillis();
        if (failures.isEmpty()) {
            jdbcTemplate.update("UPDATE private_network_group SET state='active',last_error=NULL,updated_time=? WHERE id=?", now, groupId);
            return overview();
        }
        String summary = concise(String.join("；", failures));
        jdbcTemplate.update("UPDATE private_network_group SET state='failed',last_error=?,updated_time=? WHERE id=?", summary, now, groupId);
        return R.err("内网验证未通过：" + summary);
    }

    @Transactional
    public R delete(Long id) {
        if (one("SELECT id FROM private_network_group WHERE id=?", id) == null) return R.err("内网资源组不存在");
        String usedBy = tunnelTransportService.firstTunnelUsing("private", id);
        if (usedBy != null) return R.err("该内网组仍被线路使用，请先删除出口应用或修改线路：" + usedBy);
        jdbcTemplate.update("DELETE FROM private_network_link WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM private_network_member WHERE group_id=?", id);
        jdbcTemplate.update("DELETE FROM private_network_group WHERE id=?", id);
        return overview();
    }

    private String verifyLink(Long groupId, Map<String, Object> sourceMember, Map<String, Object> targetMember) {
        Long sourceId = number(sourceMember.get("node_id"));
        Long targetId = number(targetMember.get("node_id"));
        String targetAddress = String.valueOf(targetMember.get("private_address"));
        long now = System.currentTimeMillis();
        String sessionId = "pn-" + groupId + "-" + sourceId + "-" + targetId + "-" + now;
        boolean prepared = false;
        try {
            Node source = requireOnline(sourceId);
            requireOnline(targetId);
            if (!AgentVersionUtil.isAtLeast(source.getVersion(), MIN_AGENT_VERSION)) {
                throw new IllegalStateException(source.getName() + " Agent 需要升级到 " + MIN_AGENT_VERSION);
            }
            int port = prepareTarget(targetId, sessionId);
            prepared = true;
            GostDto response = WebSocketServer.send_msg(sourceId, Map.of(
                    "target", targetAddress, "port", port, "count", 4, "timeoutMs", 3000), "PrivateNetworkProbe", 18);
            if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
                throw new IllegalStateException(response == null ? "来源 Agent 无响应" : response.getMsg());
            }
            JSONObject data = json(response.getData());
            if (!data.getBooleanValue("success")) throw new IllegalStateException(data.getString("error"));
            upsertLink(groupId, sourceId, targetId, targetAddress, "verified", data, null, now);
            return null;
        } catch (Exception e) {
            String error = concise(e.getMessage());
            upsertLink(groupId, sourceId, targetId, targetAddress, "failed", null, error, now);
            return error;
        } finally {
            if (prepared) WebSocketServer.send_msg(targetId, Map.of("sessionId", sessionId), "BandwidthStop", 5);
        }
    }

    private int prepareTarget(Long targetId, String sessionId) {
        String lastError = "没有可用的临时验证端口";
        for (int attempt = 0; attempt < 12; attempt++) {
            int port = 49152 + random.nextInt(16384);
            GostDto response = WebSocketServer.send_msg(targetId, Map.of(
                    "sessionId", sessionId, "protocol", "tcp", "listenPort", port, "ttlSeconds", 45,
                    "maximumBytes", 1048576, "maximumStreams", 8), "BandwidthPrepare", 10);
            if (response != null && "OK".equals(response.getMsg()) && response.getData() != null) return port;
            if (response != null) lastError = response.getMsg();
        }
        throw new IllegalStateException(lastError);
    }

    private void upsertLink(Long groupId, Long sourceId, Long targetId, String targetAddress, String state,
                            JSONObject data, String error, long now) {
        String sourceAddress = data == null ? null : data.getString("sourceAddress");
        String routeInfo = data == null ? null : data.getString("routeInfo");
        String interfaceName = data == null ? null : data.getString("interfaceName");
        Double latency = data == null ? null : data.getDouble("averageTime");
        Double loss = data == null ? null : data.getDouble("packetLoss");
        jdbcTemplate.update("INSERT INTO private_network_link(group_id,source_node_id,target_node_id,source_address,target_address,route_info,interface_name,state,latency_ms,packet_loss,last_error,verified_at,updated_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE source_address=VALUES(source_address),target_address=VALUES(target_address),"
                        + "route_info=VALUES(route_info),interface_name=VALUES(interface_name),state=VALUES(state),latency_ms=VALUES(latency_ms),"
                        + "packet_loss=VALUES(packet_loss),last_error=VALUES(last_error),verified_at=VALUES(verified_at),updated_time=VALUES(updated_time)",
                groupId, sourceId, targetId, sourceAddress, targetAddress, routeInfo, interfaceName, state, latency, loss, error,
                "verified".equals(state) ? now : null, now);
    }

    private void validate(PrivateNetworkSaveDto dto) {
        if (dto.getMembers().size() < 2) throw new IllegalArgumentException("至少选择两台服务器");
        if (dto.getMembers().size() > 32) throw new IllegalArgumentException("单个内网组最多允许 32 台服务器");
        normalizeType(dto.getNetworkType());
        Set<Long> nodes = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        for (PrivateNetworkSaveDto.Member member : dto.getMembers()) {
            if (!nodes.add(member.getNodeId())) throw new IllegalArgumentException("同一节点不能重复添加");
            if (nodeMapper.selectById(member.getNodeId()) == null) throw new IllegalArgumentException("节点不存在：" + member.getNodeId());
            String address = normalizeAddress(member.getPrivateAddress());
            if (!addresses.add(address)) throw new IllegalArgumentException("内网地址不能重复：" + address);
        }
    }

    private Node requireOnline(Long id) {
        Node node = nodeMapper.selectById(id);
        if (node == null) throw new IllegalStateException("节点不存在");
        if (!WebSocketServer.isNodeOnline(id)) throw new IllegalStateException(node.getName() + " Agent 离线");
        return node;
    }

    private String nodeName(Map<String, Object> member) {
        Node node = nodeMapper.selectById(number(member.get("node_id")));
        return node == null ? "节点 " + member.get("node_id") : node.getName();
    }

    private static String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("vpc", "cloud_backbone", "dedicated").contains(type)) {
            throw new IllegalArgumentException("内网类型只允许 VPC、云骨干或专线");
        }
        return type;
    }

    private static String normalizeAddress(String value) {
        try {
            InetAddress parsed = InetAddress.getByName(IpLiteralUtil.normalize(value));
            if (parsed.isAnyLocalAddress() || parsed.isLoopbackAddress() || parsed.isMulticastAddress()) {
                throw new IllegalArgumentException("不能使用回环、任意或组播地址");
            }
            return parsed.getHostAddress();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("内网地址格式不正确：" + value);
        }
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static JSONObject json(Object value) {
        return value instanceof JSONObject ? (JSONObject) value : JSONObject.parseObject(JSONObject.toJSONString(value));
    }

    private static Long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(String.valueOf(value));
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String concise(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }
}
