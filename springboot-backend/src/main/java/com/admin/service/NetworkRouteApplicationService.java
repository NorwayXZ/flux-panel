package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.NetworkRouteApplicationCreateDto;
import com.admin.common.dto.TunnelDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.IpLiteralUtil;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TunnelRouteUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class NetworkRouteApplicationService {
    private static final String MIN_AGENT_VERSION = "2.45.0";

    private final JdbcTemplate jdbcTemplate;
    private final TunnelMapper tunnelMapper;
    private final NodeMapper nodeMapper;
    private final TunnelTransportService transportService;
    private final PortLedgerService portLedgerService;
    private final TunnelService tunnelService;
    private final AESCrypto crypto;

    public NetworkRouteApplicationService(JdbcTemplate jdbcTemplate, TunnelMapper tunnelMapper, NodeMapper nodeMapper,
                                          TunnelTransportService transportService, PortLedgerService portLedgerService, TunnelService tunnelService,
                                          @Value("${jwt-secret}") String secret) {
        this.jdbcTemplate = jdbcTemplate;
        this.tunnelMapper = tunnelMapper;
        this.nodeMapper = nodeMapper;
        this.transportService = transportService;
        this.portLedgerService = portLedgerService;
        this.tunnelService = tunnelService;
        this.crypto = new AESCrypto(secret + ":network-route-application");
    }

    public R overview() {
        List<Map<String, Object>> applications = jdbcTemplate.queryForList(
                "SELECT a.id,a.name,a.tunnel_id AS tunnelId,t.name AS tunnelName,a.entry_node_id AS entryNodeId,en.name AS entryNodeName,"
                        + "a.exit_node_id AS exitNodeId,xn.name AS exitNodeName,a.proxy_type AS proxyType,a.bind_ip AS bindIp,a.listen_port AS listenPort,"
                        + "a.auth_username AS username,a.auth_password AS encryptedPassword,a.hop_ports AS hopPorts,a.managed_tunnel AS managedTunnel,a.state,a.last_error AS lastError,"
                        + "a.last_test_at AS lastTestAt,a.last_test_latency_ms AS lastTestLatencyMs,a.created_time AS createdTime,a.updated_time AS updatedTime,"
                        + "COALESCE(en.server_ip,en.ip) AS entryHost FROM network_route_application a LEFT JOIN tunnel t ON t.id=a.tunnel_id "
                        + "LEFT JOIN node en ON en.id=a.entry_node_id LEFT JOIN node xn ON xn.id=a.exit_node_id WHERE a.state<>'deleted' ORDER BY a.id DESC");
        Set<Long> tunnelIds = applications.stream().map(item -> number(item.get("tunnelId"))).collect(Collectors.toSet());
        Map<Long, Tunnel> tunnelsById = tunnelIds.isEmpty() ? Collections.emptyMap() : tunnelMapper.selectBatchIds(tunnelIds).stream()
                .collect(Collectors.toMap(Tunnel::getId, tunnel -> tunnel));
        Set<Long> nodeIds = new HashSet<>();
        for (Tunnel tunnel : tunnelsById.values()) nodeIds.addAll(TunnelRouteUtil.parseNodePath(tunnel));
        Map<Long, Node> nodesById = nodeIds.isEmpty() ? Collections.emptyMap() : nodeMapper.selectBatchIds(nodeIds).stream()
                .collect(Collectors.toMap(Node::getId, node -> node));
        for (Map<String, Object> item : applications) {
            String password = decrypt(String.valueOf(item.remove("encryptedPassword")));
            item.put("password", password);
            item.put("clientUri", clientUri(item, password));
            Tunnel tunnel = tunnelsById.get(number(item.get("tunnelId")));
            List<Node> path = tunnel == null ? Collections.emptyList() : TunnelRouteUtil.parseNodePath(tunnel).stream()
                    .map(nodesById::get).filter(Objects::nonNull).collect(Collectors.toList());
            item.put("nodePath", path.stream().map(node -> Map.of("nodeId", node.getId(), "nodeName", node.getName())).toList());
            item.put("hopDetails", tunnel == null ? Collections.emptyList() : transportService.details(tunnel, path));
        }
        return R.ok(Map.of("minimumAgentVersion", MIN_AGENT_VERSION, "applications", applications));
    }

    @Transactional
    public synchronized R create(NetworkRouteApplicationCreateDto dto) {
        try {
            Tunnel tunnel = resolveOrCreateTunnel(dto);
            List<Node> path = requirePath(tunnel);
            validateEntry(dto, path.get(0));
            List<Integer> hopPorts = allocateHopPorts(path, tunnel.getProtocol());
            long now = System.currentTimeMillis();
            jdbcTemplate.update("INSERT INTO network_route_application(name,tunnel_id,entry_node_id,exit_node_id,proxy_type,bind_ip,listen_port,auth_username,auth_password,service_name,chain_name,hop_ports,managed_tunnel,state,created_time,updated_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,'pending','pending',?,?,'provisioning',?,?)",
                    dto.getName().trim(), tunnel.getId(), path.get(0).getId(), path.get(path.size() - 1).getId(),
                    dto.getProxyType().toLowerCase(Locale.ROOT), StringUtils.trimToEmpty(dto.getBindIp()), dto.getListenPort(),
                    dto.getUsername().trim(), crypto.encrypt(dto.getPassword()), TunnelRouteUtil.joinHopPorts(hopPorts),
                    dto.getTunnelId() == null ? 1 : 0, now, now);
            Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            String base = "network-route-app-" + id;
            jdbcTemplate.update("UPDATE network_route_application SET service_name=?,chain_name=? WHERE id=?", base + "-entry", base, id);
            R deployed = deploy(id);
            return deployed.getCode() == 0 ? overview() : deployed;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.err(e.getMessage());
        }
    }

    private Tunnel resolveOrCreateTunnel(NetworkRouteApplicationCreateDto dto) {
        if (dto.getTunnelId() != null) return requireTunnel(dto.getTunnelId());
        if (dto.getNodePath() == null || dto.getNodePath().size() < 2) {
            throw new IllegalArgumentException("请选择至少 B 入口和 C 出口两台服务器");
        }
        TunnelDto tunnelDto = new TunnelDto();
        tunnelDto.setName(dto.getName().trim() + "-出口线路");
        tunnelDto.setInNodeId(dto.getNodePath().get(0));
        tunnelDto.setOutNodeId(dto.getNodePath().get(dto.getNodePath().size() - 1));
        tunnelDto.setNodePath(dto.getNodePath());
        tunnelDto.setHopConfigs(dto.getHopConfigs());
        tunnelDto.setType(2);
        tunnelDto.setFlow(1);
        tunnelDto.setTrafficRatio(java.math.BigDecimal.ONE);
        tunnelDto.setProtocol(List.of("tls", "quic").contains(dto.getTunnelProtocol()) ? dto.getTunnelProtocol() : "tls");
        tunnelDto.setTcpListenAddr("[::]");
        tunnelDto.setUdpListenAddr("[::]");
        R result = tunnelService.createTunnel(tunnelDto);
        if (result.getCode() != 0) throw new IllegalArgumentException(result.getMsg());
        List<Tunnel> created = jdbcTemplate.query("SELECT * FROM tunnel WHERE name=? ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> tunnelMapper.selectById(rs.getLong("id")), tunnelDto.getName());
        if (created.isEmpty() || created.get(0) == null) throw new IllegalStateException("出口线路创建后未能读取");
        return created.get(0);
    }

    public synchronized R deploy(Long id) {
        Map<String, Object> application = one("SELECT * FROM network_route_application WHERE id=? AND state<>'deleted'", id);
        if (application == null) return R.err("出口应用不存在");
        Tunnel tunnel;
        List<Node> path;
        List<Integer> hopPorts;
        try {
            tunnel = requireTunnel(number(application.get("tunnel_id")));
            path = requirePath(tunnel);
            hopPorts = TunnelRouteUtil.parseHopPorts(String.valueOf(application.get("hop_ports")));
            if (hopPorts.size() != path.size() - 1) throw new IllegalStateException("出口应用的跳点端口数据不完整，请删除后重新创建");
            cleanup(application, tunnel, path, false);
            List<TunnelTransportService.ResolvedHop> hops = transportService.resolve(tunnel, path);
            String chainName = String.valueOf(application.get("chain_name"));
            String protocol = StringUtils.defaultIfBlank(tunnel.getProtocol(), "tls");
            for (int index = path.size() - 1; index >= 1; index--) {
                requireOK(GostUtil.AddRelayService(path.get(index).getId(), relayName(id, index), hopPorts.get(index - 1), protocol, null),
                        "创建 " + path.get(index).getName() + " 出口服务失败");
            }
            List<List<String>> candidates = new ArrayList<>();
            for (int index = 0; index < hops.size(); index++) {
                Integer port = hopPorts.get(index);
                List<String> addresses = new ArrayList<>();
                for (String address : hops.get(index).candidates()) addresses.add(TunnelRouteUtil.hostPort(address, port));
                candidates.add(addresses);
            }
            requireOK(GostUtil.AddRoutedChains(path.get(0).getId(), chainName, candidates, protocol, null), "创建多跳出口链失败");
            String password = decrypt(String.valueOf(application.get("auth_password")));
            requireOK(GostUtil.AddPrivateProxyWithChain(path.get(0).getId(), String.valueOf(application.get("service_name")),
                    String.valueOf(application.get("proxy_type")), String.valueOf(application.get("bind_ip")),
                    intNumber(application.get("listen_port")), String.valueOf(application.get("auth_username")), password, chainName),
                    "创建 B 服务器代理入口失败");
            jdbcTemplate.update("UPDATE network_route_application SET state='active',last_error=NULL,updated_time=? WHERE id=?", System.currentTimeMillis(), id);
            R test = test(id);
            if (test.getCode() != 0) {
                jdbcTemplate.update("UPDATE network_route_application SET state='degraded',last_error=?,updated_time=? WHERE id=?",
                        concise(test.getMsg()), System.currentTimeMillis(), id);
                return R.err("线路已部署，但出口测试失败：" + test.getMsg());
            }
            return overview();
        } catch (Exception e) {
            String error = concise(e.getMessage());
            try {
                tunnel = tunnelMapper.selectById(number(application.get("tunnel_id")));
                path = tunnel == null ? Collections.emptyList() : nodes(tunnel);
                cleanup(application, tunnel, path, true);
            } catch (Exception ignored) { }
            jdbcTemplate.update("UPDATE network_route_application SET state='error',last_error=?,updated_time=? WHERE id=?", error, System.currentTimeMillis(), id);
            return R.err("出口应用部署失败：" + error + "。已回退本次创建的运行时");
        }
    }

    public R test(Long id) {
        Map<String, Object> app = one("SELECT * FROM network_route_application WHERE id=? AND state<>'deleted'", id);
        if (app == null) return R.err("出口应用不存在");
        try {
            Long entryId = number(app.get("entry_node_id"));
            requireOnline(nodeMapper.selectById(entryId));
            String bindIp = String.valueOf(app.get("bind_ip"));
            String proxyHost = bindIp == null || bindIp.isBlank() || "0.0.0.0".equals(bindIp) || "::".equals(bindIp) ? "127.0.0.1" : bindIp;
            GostDto result = WebSocketServer.send_msg(entryId, Map.of(
                    "proxyType", app.get("proxy_type"), "proxyHost", proxyHost, "proxyPort", app.get("listen_port"),
                    "username", app.get("auth_username"), "password", decrypt(String.valueOf(app.get("auth_password"))),
                    "target", "www.cloudflare.com:443", "timeoutMs", 8000), "ProxyRouteProbe", 15);
            if (result == null || !"OK".equals(result.getMsg()) || result.getData() == null) throw new IllegalStateException(result == null ? "入口 Agent 无响应" : result.getMsg());
            JSONObject data = json(result.getData());
            if (!data.getBooleanValue("success")) throw new IllegalStateException(data.getString("error"));
            jdbcTemplate.update("UPDATE network_route_application SET state='active',last_error=NULL,last_test_at=?,last_test_latency_ms=?,updated_time=? WHERE id=?",
                    System.currentTimeMillis(), data.getDouble("latencyMs"), System.currentTimeMillis(), id);
            return R.ok(data);
        } catch (Exception e) {
            String error = concise(e.getMessage());
            jdbcTemplate.update("UPDATE network_route_application SET last_error=?,last_test_at=?,updated_time=? WHERE id=?", error, System.currentTimeMillis(), System.currentTimeMillis(), id);
            return R.err(error);
        }
    }

    public R pause(Long id) {
        Map<String, Object> app = one("SELECT * FROM network_route_application WHERE id=? AND state<>'deleted'", id);
        if (app == null) return R.err("出口应用不存在");
        GostDto result = GostUtil.PauseNamedService(number(app.get("entry_node_id")), String.valueOf(app.get("service_name")));
        if (!ok(result)) return R.err(result == null ? "入口 Agent 无响应" : result.getMsg());
        jdbcTemplate.update("UPDATE network_route_application SET state='paused',last_error=NULL,updated_time=? WHERE id=?", System.currentTimeMillis(), id);
        return overview();
    }

    public R resume(Long id) {
        Map<String, Object> app = one("SELECT * FROM network_route_application WHERE id=? AND state<>'deleted'", id);
        if (app == null) return R.err("出口应用不存在");
        GostDto result = GostUtil.ResumeNamedService(number(app.get("entry_node_id")), String.valueOf(app.get("service_name")));
        if (!ok(result)) return deploy(id);
        jdbcTemplate.update("UPDATE network_route_application SET state='active',last_error=NULL,updated_time=? WHERE id=?", System.currentTimeMillis(), id);
        return overview();
    }

    @Transactional
    public R delete(Long id) {
        Map<String, Object> app = one("SELECT * FROM network_route_application WHERE id=? AND state<>'deleted'", id);
        if (app == null) return R.err("出口应用不存在");
        Tunnel tunnel = tunnelMapper.selectById(number(app.get("tunnel_id")));
        List<Node> path = tunnel == null ? Collections.emptyList() : nodes(tunnel);
        List<String> failures = cleanup(app, tunnel, path, true);
        if (!failures.isEmpty()) {
            String error = "清理失败：" + String.join("、", failures);
            jdbcTemplate.update("UPDATE network_route_application SET state='delete_pending',last_error=?,updated_time=? WHERE id=?", error, System.currentTimeMillis(), id);
            return R.err(error + "；保留记录以便重试");
        }
        jdbcTemplate.update("UPDATE network_route_application SET state='deleted',last_error=NULL,updated_time=? WHERE id=?", System.currentTimeMillis(), id);
        if (intNumber(app.get("managed_tunnel")) == 1 && tunnel != null) {
            R tunnelDelete = tunnelService.deleteTunnel(tunnel.getId());
            if (tunnelDelete.getCode() != 0) {
                String error = "运行时已清理，但自动线路删除失败：" + concise(tunnelDelete.getMsg());
                jdbcTemplate.update("UPDATE network_route_application SET state='delete_pending',last_error=?,updated_time=? WHERE id=?",
                        error, System.currentTimeMillis(), id);
                return R.err(error + "；保留记录以便重试");
            }
        }
        return overview();
    }

    private List<String> cleanup(Map<String, Object> app, Tunnel tunnel, List<Node> path, boolean collectFailures) {
        List<String> failures = new ArrayList<>();
        if (!path.isEmpty()) {
            recordFailure(failures, "入口服务", GostUtil.DeleteNamedService(path.get(0).getId(), String.valueOf(app.get("service_name"))), collectFailures);
            recordFailure(failures, "入口链", GostUtil.DeleteChains(path.get(0).getId(), String.valueOf(app.get("chain_name"))), collectFailures);
            Long id = number(app.get("id"));
            for (int index = 1; index < path.size(); index++) {
                recordFailure(failures, path.get(index).getName(), GostUtil.DeleteRemoteService(path.get(index).getId(), relayName(id, index)), collectFailures);
            }
        }
        return failures;
    }

    private void validateEntry(NetworkRouteApplicationCreateDto dto, Node entry) {
        requireOnline(entry);
        if (!AgentVersionUtil.isAtLeast(entry.getVersion(), MIN_AGENT_VERSION)) throw new IllegalArgumentException(entry.getName() + " Agent 需要升级到 " + MIN_AGENT_VERSION);
        String bindIp = StringUtils.trimToEmpty(dto.getBindIp());
        if (!bindIp.isEmpty() && !validIp(bindIp)) throw new IllegalArgumentException("入口监听 IP 格式不正确");
        if (Boolean.TRUE.equals(portLedgerService.diagnose(entry.getId(), dto.getListenPort()).get("occupied"))) throw new IllegalArgumentException("B 服务器入口端口已被占用");
        AgentPortCheckUtil.Result check = AgentPortCheckUtil.check(entry, List.of(new AgentPortCheckUtil.Check("tcp", bindIp, dto.getListenPort())));
        if (!check.isAvailable()) throw new IllegalArgumentException(check.getMessage());
    }

    private List<Integer> allocateHopPorts(List<Node> path, String protocol) {
        List<Integer> result = new ArrayList<>();
        Map<String, List<Integer>> reservedByServer = new java.util.HashMap<>();
        String network = "quic".equalsIgnoreCase(protocol) ? "udp" : "tcp";
        for (int index = 1; index < path.size(); index++) {
            Node node = path.get(index); requireOnline(node);
            String serverKey = StringUtils.defaultIfBlank(node.getServerIp(), node.getIp());
            serverKey = serverKey == null ? "node:" + node.getId() : serverKey.trim().toLowerCase(Locale.ROOT);
            List<Integer> reserved = reservedByServer.computeIfAbsent(serverKey, ignored -> new ArrayList<>());
            int start = node.getPortSta() == null ? 20000 : node.getPortSta();
            int end = node.getPortEnd() == null ? 60000 : node.getPortEnd();
            Integer selected = null;
            for (int port = start, attempts = 0; port <= end && attempts < 10000; port++, attempts++) {
                if (reserved.contains(port)) continue;
                if (Boolean.TRUE.equals(portLedgerService.diagnose(node.getId(), port).get("occupied"))) continue;
                if (AgentPortCheckUtil.check(node, List.of(new AgentPortCheckUtil.Check(network, "", port))).isAvailable()) { selected = port; break; }
            }
            if (selected == null) throw new IllegalStateException(node.getName() + " 没有可用的链路端口");
            result.add(selected);
            reserved.add(selected);
        }
        return result;
    }

    private Tunnel requireTunnel(Long id) {
        Tunnel tunnel = tunnelMapper.selectById(id);
        if (tunnel == null || !Objects.equals(tunnel.getType(), 2) || !Objects.equals(tunnel.getStatus(), 1)) throw new IllegalArgumentException("请选择启用的多跳隧道");
        return tunnel;
    }

    private List<Node> requirePath(Tunnel tunnel) {
        List<Node> path = nodes(tunnel);
        if (path.size() < 2) throw new IllegalArgumentException("线路至少需要 B 入口和 C 出口两台服务器");
        for (Node node : path) { requireOnline(node); if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) throw new IllegalArgumentException(node.getName() + " Agent 需要升级到 " + MIN_AGENT_VERSION); }
        transportService.resolve(tunnel, path);
        return path;
    }

    private List<Node> nodes(Tunnel tunnel) {
        List<Node> result = new ArrayList<>();
        for (Long id : TunnelRouteUtil.parseNodePath(tunnel)) { Node node = nodeMapper.selectById(id); if (node != null) result.add(node); }
        return result;
    }

    private void requireOnline(Node node) { if (node == null || !WebSocketServer.isNodeOnline(node.getId())) throw new IllegalArgumentException(node == null ? "节点不存在" : node.getName() + " Agent 离线"); }
    private static void requireOK(GostDto result, String prefix) { if (!ok(result)) throw new IllegalStateException(prefix + "：" + (result == null ? "Agent 无响应" : result.getMsg())); }
    private static boolean ok(GostDto result) { return result != null && "OK".equals(result.getMsg()); }
    private static String relayName(Long id, int index) { return "network-route-app-" + id + "-hop-" + index; }
    private static void recordFailure(List<String> failures, String label, GostDto result, boolean collect) {
        if (!collect || ok(result)) return;
        if (result == null || !String.valueOf(result.getMsg()).contains("not found")) failures.add(label);
    }
    private static boolean validIp(String value) { return IpLiteralUtil.isLiteral(value); }
    private static JSONObject json(Object value) { return value instanceof JSONObject ? (JSONObject)value : JSONObject.parseObject(JSONObject.toJSONString(value)); }
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbcTemplate.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private String decrypt(String value) { try { return crypto.decryptString(value); } catch (Exception e) { return ""; } }
    private static Long number(Object value){return value instanceof Number?((Number)value).longValue():Long.valueOf(String.valueOf(value));}
    private static int intNumber(Object value){return value instanceof Number?((Number)value).intValue():Integer.parseInt(String.valueOf(value));}
    private static String concise(String value){if(value==null||value.isBlank())return "未知错误";String v=value.replace('\r',' ').replace('\n',' ').trim();return v.length()>500?v.substring(0,500):v;}
    private static String clientUri(Map<String,Object> item,String password){String scheme="http".equals(item.get("proxyType"))?"http":"socks5";return scheme+"://"+url(String.valueOf(item.get("username")))+":"+url(password)+"@"+hostPort(String.valueOf(item.get("entryHost")),intNumber(item.get("listenPort")));}
    private static String url(String value){return URLEncoder.encode(value, StandardCharsets.UTF_8);}
    private static String hostPort(String host,int port){return host!=null&&host.contains(":")?"["+host.replace("[","").replace("]","")+"]:"+port:host+":"+port;}
}
