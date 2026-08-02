package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.VirtualLanCreateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.InternalConnector;
import com.admin.entity.Node;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class VirtualLanService {
    public static final String MIN_AGENT_VERSION = "2.44.0";
    private final JdbcTemplate jdbcTemplate;
    private final NodeMapper nodeMapper;
    private final InternalConnectorMapper connectorMapper;
    private final TunnelTransportService tunnelTransportService;

    public VirtualLanService(JdbcTemplate jdbcTemplate, NodeMapper nodeMapper, InternalConnectorMapper connectorMapper,
                             TunnelTransportService tunnelTransportService) {
        this.jdbcTemplate = jdbcTemplate; this.nodeMapper = nodeMapper; this.connectorMapper = connectorMapper;
        this.tunnelTransportService = tunnelTransportService;
    }

    public R overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimumAgentVersion", MIN_AGENT_VERSION);
        result.put("nodes", jdbcTemplate.queryForList("SELECT id,name,ip,server_ip AS serverIp,status,version FROM node ORDER BY status DESC,id DESC"));
        result.put("connectors", jdbcTemplate.queryForList("SELECT id,name,platform,version,status,remote_ip AS remoteIp,last_seen AS lastSeen FROM internal_connector ORDER BY status DESC,id DESC"));
        List<Map<String, Object>> networks = jdbcTemplate.queryForList("SELECT v.id,v.name,v.cidr,v.hub_node_id AS hubNodeId,v.listen_port AS listenPort,v.state,v.last_error AS lastError,"
                + "v.created_time AS createdTime,v.updated_time AS updatedTime,COALESCE(n.name,'已删除节点') AS hubNodeName,n.server_ip AS hubServerIp,n.ip AS hubIp,"
                + "(SELECT COUNT(*) FROM virtual_lan_member m WHERE m.network_id=v.id) AS memberCount,"
                + "(SELECT COUNT(*) FROM virtual_lan_member m WHERE m.network_id=v.id AND m.state='online') AS onlineCount "
                + "FROM virtual_lan_network v LEFT JOIN node n ON n.id=v.hub_node_id ORDER BY v.created_time DESC");
        for (Map<String, Object> network : networks) {
            network.put("members", jdbcTemplate.queryForList("SELECT id,network_id AS networkId,target_type AS targetType,target_id AS targetId,member_name AS memberName,"
                    + "role,virtual_ip AS virtualIp,state,receive_bytes AS receiveBytes,transmit_bytes AS transmitBytes,latest_handshake AS latestHandshake,"
                    + "last_error AS lastError,updated_time AS updatedTime FROM virtual_lan_member WHERE network_id=? ORDER BY role='hub' DESC,virtual_ip", network.get("id")));
        }
        result.put("networks", networks);
        return R.ok(result);
    }

    @Transactional
    public R create(VirtualLanCreateDto dto) {
        ParsedCidr parsed;
        try { parsed = validate(dto); } catch (IllegalArgumentException e) { return R.err(e.getMessage()); }
        long now = System.currentTimeMillis();
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO virtual_lan_network (name,cidr,hub_node_id,listen_port,state,created_time,updated_time) VALUES (?,?,?,?,'pending',?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, dto.getName().trim()); statement.setString(2, parsed.normalized); statement.setLong(3, dto.getHubNodeId());
            statement.setInt(4, dto.getListenPort()); statement.setLong(5, now); statement.setLong(6, now); return statement;
        }, holder);
        long networkId = Objects.requireNonNull(holder.getKey()).longValue();
        List<VirtualLanCreateDto.Member> members = new ArrayList<>(dto.getMembers());
        if (members.stream().noneMatch(item -> "node".equals(item.getTargetType()) && dto.getHubNodeId().equals(item.getTargetId()))) {
            VirtualLanCreateDto.Member hub = new VirtualLanCreateDto.Member(); hub.setTargetType("node"); hub.setTargetId(dto.getHubNodeId()); members.add(0, hub);
        }
        int offset = 1;
        for (VirtualLanCreateDto.Member member : members) {
            boolean hub = "node".equals(member.getTargetType()) && dto.getHubNodeId().equals(member.getTargetId());
            String memberName = memberName(member.getTargetType(), member.getTargetId());
            jdbcTemplate.update("INSERT INTO virtual_lan_member (network_id,target_type,target_id,member_name,role,virtual_ip,state,updated_time) VALUES (?,?,?,?,?,?,'pending',?)",
                    networkId, member.getTargetType(), member.getTargetId(), memberName, hub ? "hub" : "member", address(parsed.network, offset++), now);
        }
        return deploy(networkId);
    }

    public R deploy(Long id) {
        Map<String, Object> network = one("SELECT * FROM virtual_lan_network WHERE id=?", id);
        if (network == null) return R.err("虚拟局域网不存在");
        List<Map<String, Object>> members = jdbcTemplate.queryForList("SELECT * FROM virtual_lan_member WHERE network_id=? ORDER BY role='hub' DESC,id", id);
        String runtimeName = "vlan-" + id;
        List<Map<String, Object>> touched = new ArrayList<>();
        try {
            for (Map<String, Object> member : members) {
                requireOnline(member);
                GostDto response = send(member, Map.of("name", runtimeName), "VirtualLanPrepareKey", 12);
                requireOK(response, member.get("member_name") + " 无法生成组网密钥");
                String publicKey = json(response.getData()).getString("publicKey");
                jdbcTemplate.update("UPDATE virtual_lan_member SET public_key=?,state='deploying',last_error=NULL,updated_time=? WHERE id=?", publicKey, System.currentTimeMillis(), member.get("id"));
                member.put("public_key", publicKey);
                touched.add(member);
            }
            Map<String, Object> hub = members.stream().filter(member -> "hub".equals(member.get("role"))).findFirst().orElseThrow(() -> new IllegalStateException("缺少中继节点"));
            Node hubNode = nodeMapper.selectById(longNumber(hub.get("target_id")));
            String endpointHost = firstNonBlank(hubNode.getServerIp(), hubNode.getIp());
            if (endpointHost == null) throw new IllegalStateException("中继节点没有公网地址");
            String endpoint = endpoint(endpointHost, intNumber(network.get("listen_port")));
            int prefix = Integer.parseInt(String.valueOf(network.get("cidr")).split("/")[1]);
            for (Map<String, Object> member : members) {
                boolean isHub = "hub".equals(member.get("role"));
                List<Map<String, Object>> peers = new ArrayList<>();
                if (isHub) {
                    for (Map<String, Object> peer : members) if (!"hub".equals(peer.get("role"))) peers.add(Map.of("publicKey", peer.get("public_key"), "allowedIps", List.of(peer.get("virtual_ip") + "/32")));
                } else {
                    peers.add(Map.of("publicKey", hub.get("public_key"), "allowedIps", List.of(network.get("cidr")), "endpoint", endpoint, "persistentKeepalive", 25));
                }
                Map<String, Object> request = new LinkedHashMap<>(); request.put("name", runtimeName); request.put("interfaceAddress", member.get("virtual_ip") + "/" + prefix);
                request.put("listenPort", isHub ? network.get("listen_port") : 0); request.put("hub", isHub); request.put("peers", peers);
                GostDto response = send(member, request, "VirtualLanApply", 25); requireOK(response, member.get("member_name") + " 应用组网配置失败");
                updateStatus(member, json(response.getData()), null);
            }
            jdbcTemplate.update("UPDATE virtual_lan_network SET state='active',last_error=NULL,updated_time=? WHERE id=?", System.currentTimeMillis(), id);
            return overview();
        } catch (Exception e) {
            String error = concise(e.getMessage());
            for (Map<String, Object> member : touched) {
                try { send(member, Map.of("name", runtimeName), "VirtualLanPause", 5); } catch (Exception ignored) { }
            }
            jdbcTemplate.update("UPDATE virtual_lan_member SET state='failed',last_error=?,updated_time=? WHERE network_id=? AND state<>'online'", error, System.currentTimeMillis(), id);
            jdbcTemplate.update("UPDATE virtual_lan_network SET state='failed',last_error=?,updated_time=? WHERE id=?", error, System.currentTimeMillis(), id);
            return R.err("组网失败：" + error + "。已暂停本轮涉及的成员，可修复后重新部署");
        }
    }

    public R pause(Long id) { return control(id, "VirtualLanPause", "paused"); }
    public R resume(Long id) { return deploy(id); }

    public R refresh(Long id) {
        List<Map<String, Object>> members = jdbcTemplate.queryForList("SELECT * FROM virtual_lan_member WHERE network_id=?", id);
        if (members.isEmpty()) return R.err("虚拟局域网不存在");
        int online = 0;
        for (Map<String, Object> member : members) {
            try {
                if (!online(member)) { updateStatus(member, null, "Agent 离线"); continue; }
                GostDto response = send(member, Map.of("name", "vlan-" + id), "VirtualLanStatus", 8);
                if (response != null && "OK".equals(response.getMsg())) { updateStatus(member, json(response.getData()), null); online++; }
                else updateStatus(member, null, response == null ? "Agent 无响应" : response.getMsg());
            } catch (Exception e) { updateStatus(member, null, concise(e.getMessage())); }
        }
        String state = online == members.size() ? "active" : online == 0 ? "offline" : "degraded";
        jdbcTemplate.update("UPDATE virtual_lan_network SET state=?,updated_time=? WHERE id=?", state, System.currentTimeMillis(), id);
        return overview();
    }

    @Transactional
    public R delete(Long id) {
        List<Map<String, Object>> members = jdbcTemplate.queryForList("SELECT * FROM virtual_lan_member WHERE network_id=?", id);
        if (members.isEmpty()) return R.err("虚拟局域网不存在");
        String usedBy = tunnelTransportService.firstTunnelUsing("virtual", id);
        if (usedBy != null) return R.err("该自动组网仍被线路使用，请先删除出口应用或修改线路：" + usedBy);
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> member : members) {
            if (!online(member)) { failures.add(String.valueOf(member.get("member_name"))); continue; }
            GostDto result = send(member, Map.of("name", "vlan-" + id), "VirtualLanDelete", 8);
            if (result == null || !"OK".equals(result.getMsg())) failures.add(String.valueOf(member.get("member_name")));
        }
        if (!failures.isEmpty()) return R.err("以下成员离线或清理失败，未删除面板记录：" + String.join("、", failures));
        jdbcTemplate.update("DELETE FROM virtual_lan_member WHERE network_id=?", id);
        jdbcTemplate.update("DELETE FROM virtual_lan_network WHERE id=?", id);
        return overview();
    }

    private R control(Long id, String command, String targetState) {
        List<Map<String, Object>> members = jdbcTemplate.queryForList("SELECT * FROM virtual_lan_member WHERE network_id=?", id);
        if (members.isEmpty()) return R.err("虚拟局域网不存在");
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> member : members) {
            if (!online(member)) { failures.add(String.valueOf(member.get("member_name"))); continue; }
            GostDto result = send(member, Map.of("name", "vlan-" + id), command, 8);
            if (result == null || !"OK".equals(result.getMsg())) failures.add(String.valueOf(member.get("member_name")));
            else jdbcTemplate.update("UPDATE virtual_lan_member SET state=?,last_error=NULL,updated_time=? WHERE id=?", targetState, System.currentTimeMillis(), member.get("id"));
        }
        if (!failures.isEmpty()) { jdbcTemplate.update("UPDATE virtual_lan_network SET state='degraded',last_error=?,updated_time=? WHERE id=?", "部分成员操作失败：" + String.join("、", failures), System.currentTimeMillis(), id); return R.err("部分成员操作失败：" + String.join("、", failures)); }
        jdbcTemplate.update("UPDATE virtual_lan_network SET state=?,last_error=NULL,updated_time=? WHERE id=?", targetState, System.currentTimeMillis(), id);
        return overview();
    }

    private ParsedCidr validate(VirtualLanCreateDto dto) {
        ParsedCidr parsed = parseCidr(dto.getCidr());
        if (parsed.prefix < 16 || parsed.prefix > 29) throw new IllegalArgumentException("虚拟网段掩码只允许 /16 到 /29");
        Node hub = nodeMapper.selectById(dto.getHubNodeId()); if (hub == null) throw new IllegalArgumentException("中继节点不存在");
        Set<String> unique = new HashSet<>();
        for (VirtualLanCreateDto.Member member : dto.getMembers()) {
            member.setTargetType(member.getTargetType().toLowerCase(Locale.ROOT));
            if (!unique.add(member.getTargetType() + ":" + member.getTargetId())) throw new IllegalArgumentException("成员不能重复选择");
            if ("node".equals(member.getTargetType())) { if (nodeMapper.selectById(member.getTargetId()) == null) throw new IllegalArgumentException("节点成员不存在"); }
            else { InternalConnector connector = connectorMapper.selectById(member.getTargetId()); if (connector == null) throw new IllegalArgumentException("Connector 成员不存在"); if (!"linux".equalsIgnoreCase(connector.getPlatform())) throw new IllegalArgumentException(connector.getName() + " 不是 Linux Connector，当前无法创建 TUN 接口"); }
        }
        if (dto.getMembers().size() + 1 >= (1L << (32 - parsed.prefix)) - 1) throw new IllegalArgumentException("所选成员数量超过该虚拟网段容量");
        return parsed;
    }

    private void requireOnline(Map<String, Object> member) {
        if (!online(member)) throw new IllegalStateException(member.get("member_name") + " Agent 离线");
        String version;
        if ("node".equals(member.get("target_type"))) version = nodeMapper.selectById(longNumber(member.get("target_id"))).getVersion();
        else version = connectorMapper.selectById(longNumber(member.get("target_id"))).getVersion();
        if (!AgentVersionUtil.isAtLeast(version, MIN_AGENT_VERSION)) throw new IllegalStateException(member.get("member_name") + " Agent 需要升级到 " + MIN_AGENT_VERSION);
    }

    private boolean online(Map<String, Object> member) { return "node".equals(member.get("target_type")) ? WebSocketServer.isNodeOnline(longNumber(member.get("target_id"))) : WebSocketServer.isConnectorOnline(longNumber(member.get("target_id"))); }
    private GostDto send(Map<String, Object> member, Object payload, String type, long timeout) { return "node".equals(member.get("target_type")) ? WebSocketServer.send_msg(longNumber(member.get("target_id")), payload, type, timeout) : WebSocketServer.sendConnectorMsg(longNumber(member.get("target_id")), payload, type, timeout); }
    private void updateStatus(Map<String, Object> member, JSONObject data, String error) {
        if (data == null) jdbcTemplate.update("UPDATE virtual_lan_member SET state='offline',last_error=?,updated_time=? WHERE id=?", error, System.currentTimeMillis(), member.get("id"));
        else jdbcTemplate.update("UPDATE virtual_lan_member SET state=?,receive_bytes=?,transmit_bytes=?,latest_handshake=?,last_error=NULL,updated_time=? WHERE id=?", data.getBooleanValue("active") ? "online" : "paused", data.getLongValue("receiveBytes"), data.getLongValue("transmitBytes"), data.getLong("latestHandshake"), System.currentTimeMillis(), member.get("id"));
    }

    private String memberName(String type, Long id) { if ("node".equals(type)) { Node value = nodeMapper.selectById(id); if (value == null) throw new IllegalArgumentException("节点不存在"); return value.getName(); } InternalConnector value = connectorMapper.selectById(id); if (value == null) throw new IllegalArgumentException("Connector 不存在"); return value.getName(); }
    private static ParsedCidr parseCidr(String value) { try { String[] parts = value.trim().split("/"); if (parts.length != 2) throw new Exception(); InetAddress address = InetAddress.getByName(parts[0]); if (!(address instanceof Inet4Address)) throw new Exception(); int prefix = Integer.parseInt(parts[1]); if (prefix < 0 || prefix > 32) throw new Exception(); int raw = ByteBuffer.wrap(address.getAddress()).getInt(); int mask = prefix == 0 ? 0 : -1 << (32-prefix); int network = raw & mask; return new ParsedCidr(network, prefix, address(network, 0) + "/" + prefix); } catch (Exception e) { throw new IllegalArgumentException("虚拟网段格式不正确，例如 10.88.0.0/24"); } }
    private static String address(int network, int offset) { int value = network + offset; return ((value >>> 24)&255) + "." + ((value >>> 16)&255) + "." + ((value >>> 8)&255) + "." + (value&255); }
    private static String endpoint(String host, int port) { return host.contains(":") ? "[" + host.replace("[", "").replace("]", "") + "]:" + port : host + ":" + port; }
    private Map<String, Object> one(String sql, Object... args) { List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args); return rows.isEmpty() ? null : rows.get(0); }
    private static JSONObject json(Object value) { return value instanceof JSONObject ? (JSONObject) value : JSONObject.parseObject(JSONObject.toJSONString(value)); }
    private static void requireOK(GostDto result, String prefix) { if (result == null || !"OK".equals(result.getMsg()) || result.getData() == null) throw new IllegalStateException(prefix + "：" + (result == null ? "Agent 无响应" : result.getMsg())); }
    private static Long longNumber(Object value) { return value instanceof Number ? ((Number)value).longValue() : Long.valueOf(String.valueOf(value)); }
    private static int intNumber(Object value) { return value instanceof Number ? ((Number)value).intValue() : Integer.parseInt(String.valueOf(value)); }
    private static String firstNonBlank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value.trim(); return null; }
    private static String concise(String value) { if (value == null || value.isBlank()) return "未知错误"; value = value.replace('\r',' ').replace('\n',' ').trim(); return value.length() > 500 ? value.substring(0,500) : value; }
    private static final class ParsedCidr { final int network; final int prefix; final String normalized; ParsedCidr(int network, int prefix, String normalized) { this.network=network; this.prefix=prefix; this.normalized=normalized; } }
}
