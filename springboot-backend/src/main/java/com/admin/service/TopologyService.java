package com.admin.service;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TopologyService {
    private final JdbcTemplate jdbcTemplate;

    public TopologyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public R graph() {
        Graph graph = new Graph();
        boolean admin = Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
        int userId = JwtUtil.getUserIdFromToken();
        addForwardFlows(graph, admin, userId);
        addInternalFlows(graph, admin, userId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nodes", graph.nodes.size());
        summary.put("links", graph.edges.size());
        summary.put("healthy", graph.nodes.values().stream().filter(node -> "healthy".equals(node.status())).count());
        summary.put("abnormal", graph.nodes.values().stream().filter(node -> List.of("offline", "degraded", "failed").contains(node.status())).count());
        return R.ok(Map.of("nodes", new ArrayList<>(graph.nodes.values()), "edges", graph.edges, "summary", summary));
    }

    private void addForwardFlows(Graph graph, boolean admin, int userId) {
        String userFilter = admin ? "" : " WHERE f.user_id=" + userId;
        List<Map<String, Object>> forwards = jdbcTemplate.queryForList(
                "SELECT f.id,f.user_id AS userId,u.user,f.name,f.status,f.tunnel_id AS tunnelId,f.active_tunnel_id AS activeTunnelId,"
                        + "f.route_config AS routeConfig,f.in_port AS inPort,f.remote_addr AS remoteAddr,f.protocol_mode AS protocolMode "
                        + "FROM forward f LEFT JOIN user u ON u.id=f.user_id" + userFilter + " ORDER BY f.created_time DESC");
        Map<Long, Map<String, Object>> tunnels = loadTunnels();
        Map<Long, Map<String, Object>> nodeRows = loadNodes();
        for (Map<String, Object> forward : forwards) {
            long forwardId = number(forward.get("id"));
            int ownerId = intValue(forward.get("userId"));
            String userNode = addUser(graph, ownerId, Objects.toString(forward.get("user"), "用户" + ownerId));
            String forwardNode = "forward:" + forwardId;
            long primaryTunnel = number(forward.get("tunnelId"));
            long activeTunnel = nullableNumber(forward.get("activeTunnelId"), primaryTunnel);
            boolean enabled = intValue(forward.get("status")) == 1;
            String forwardStatus = !enabled ? "paused" : tunnelHealthy(tunnels.get(activeTunnel), nodeRows) ? "healthy" : "offline";
            graph.node(new TopologyNode(forwardNode, "forward", Objects.toString(forward.get("name")),
                    Objects.toString(forward.get("protocolMode"), "tcp") + " · 入口端口 " + forward.get("inPort"),
                    forwardStatus, "/forward", ownerId));
            graph.edge(userNode, forwardNode, "转发", forwardStatus, true);

            Set<Long> routeIds = new LinkedHashSet<>();
            routeIds.add(primaryTunnel);
            try {
                JSONArray configured = JSON.parseArray(Objects.toString(forward.get("routeConfig"), "[]"));
                for (int i = 0; i < configured.size(); i++) {
                    Long id = configured.getJSONObject(i).getLong("tunnelId");
                    if (id != null) routeIds.add(id);
                }
            } catch (Exception ignored) {
            }
            for (Long tunnelId : routeIds) {
                Map<String, Object> tunnel = tunnels.get(tunnelId);
                if (tunnel == null) continue;
                String tunnelNode = addTunnel(graph, tunnel, nodeRows);
                boolean active = tunnelId == activeTunnel;
                String status = tunnelHealthy(tunnel, nodeRows) ? "healthy" : "offline";
                graph.edge(forwardNode, tunnelNode, active ? "当前线路" : tunnelId == primaryTunnel ? "主线路" : "候选线路", status, active);
                List<Long> path = tunnelPath(tunnel);
                String previous = tunnelNode;
                for (int i = 0; i < path.size(); i++) {
                    long nodeId = path.get(i);
                    String node = addPublicNode(graph, nodeId, nodeRows.get(nodeId));
                    graph.edge(previous, node, "第 " + (i + 1) + " 跳", nodeStatus(nodeId), active);
                    previous = node;
                }
                if (active) {
                    String target = "forward-target:" + forwardId;
                    graph.node(new TopologyNode(target, "service", "目标服务", Objects.toString(forward.get("remoteAddr")),
                            forwardStatus, "/forward", ownerId));
                    graph.edge(previous, target, "目标", forwardStatus, true);
                }
            }
        }

        String groupFilter = admin ? "" : " WHERE g.user_id=" + userId;
        List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                "SELECT g.id,g.user_id AS userId,u.user,g.name,g.domain,g.state,g.active_member_id AS activeMemberId "
                        + "FROM cross_entry_failover_group g LEFT JOIN user u ON u.id=g.user_id" + groupFilter);
        for (Map<String, Object> group : groups) {
            long groupId = number(group.get("id"));
            int ownerId = intValue(group.get("userId"));
            String userNode = addUser(graph, ownerId, Objects.toString(group.get("user"), "用户" + ownerId));
            String domainNode = "failover-domain:" + groupId;
            String state = normalizeStatus(Objects.toString(group.get("state"), "unknown"));
            graph.node(new TopologyNode(domainNode, "domain", Objects.toString(group.get("domain")),
                    Objects.toString(group.get("name")), state, "/cross-entry-failover", ownerId));
            graph.edge(userNode, domainNode, "业务域名", state, true);
            List<Map<String, Object>> members = jdbcTemplate.queryForList(
                    "SELECT id,forward_id AS forwardId,priority,status FROM cross_entry_failover_member WHERE group_id=? ORDER BY priority", groupId);
            long activeMember = nullableNumber(group.get("activeMemberId"), -1);
            for (Map<String, Object> member : members) {
                String forwardNode = "forward:" + number(member.get("forwardId"));
                boolean active = number(member.get("id")) == activeMember;
                graph.edge(domainNode, forwardNode, active ? "当前入口" : intValue(member.get("priority")) == 0 ? "主入口" : "备用入口",
                        normalizeStatus(Objects.toString(member.get("status"))), active);
            }
        }
    }

    private void addInternalFlows(Graph graph, boolean admin, int userId) {
        String filter = admin ? "" : " WHERE r.user_id=" + userId;
        List<Map<String, Object>> routes = jdbcTemplate.queryForList(
                "SELECT r.id,r.user_id AS userId,u.user,r.name,r.domain,r.state,r.ingress_mode AS ingressMode,r.listen_port AS listenPort,"
                        + "r.node_id AS nodeId,r.backend_type AS backendType,r.backend_node_id AS backendNodeId,"
                        + "r.backend_host AS backendHost,r.backend_port AS backendPort,r.backend_scheme AS backendScheme,r.health_state AS healthState,"
                        + "p.id AS mappingId,p.name AS mappingName,p.state AS mappingState,p.target_host AS targetHost,"
                        + "p.target_port AS targetPort,p.connector_id AS connectorId,c.name AS connectorName,cert.state AS certificateState "
                        + "FROM domain_route r LEFT JOIN published_service p ON p.id=r.published_service_id "
                        + "LEFT JOIN internal_connector c ON c.id=p.connector_id LEFT JOIN user u ON u.id=r.user_id "
                        + "LEFT JOIN managed_certificate cert ON cert.id=r.certificate_id" + filter
                        + (filter.isEmpty() ? " WHERE" : " AND") + " r.state<>'deleted' ORDER BY r.created_time DESC");
        Map<Long, Map<String, Object>> nodeRows = loadNodes();
        for (Map<String, Object> route : routes) {
            long routeId = number(route.get("id"));
            int ownerId = intValue(route.get("userId"));
            long nodeId = number(route.get("nodeId"));
            boolean direct = "direct".equals(Objects.toString(route.get("backendType")));
            long connectorId = nullableNumber(route.get("connectorId"), -1);
            long backendNodeId = nullableNumber(route.get("backendNodeId"), -1);
            String routeStatus = domainRouteStatus(route, nodeId, connectorId, backendNodeId, direct);
            String userNode = addUser(graph, ownerId, Objects.toString(route.get("user"), "用户" + ownerId));
            String domainNode = "domain-route:" + routeId;
            graph.node(new TopologyNode(domainNode, "domain", Objects.toString(route.get("domain")),
                    "managed_https".equals(route.get("ingressMode")) ? "托管 HTTPS" : "TLS 透传",
                    routeStatus, "/service-publishing", ownerId));
            graph.edge(userNode, domainNode, "域名", routeStatus, true);
            String publicNode = addPublicNode(graph, nodeId, nodeRows.get(nodeId));
            graph.edge(domainNode, publicNode, "入口 :" + route.get("listenPort"), nodeStatus(nodeId), true);
            if (direct) {
                String backendPublicNode = addPublicNode(graph, backendNodeId, nodeRows.get(backendNodeId));
                if (backendNodeId != nodeId) {
                    graph.edge(publicNode, backendPublicNode, "节点直连", nodeStatus(backendNodeId), true);
                }
                String serviceNode = "node-service:" + routeId;
                String health = normalizeStatus(Objects.toString(route.get("healthState"), "pending"));
                graph.node(new TopologyNode(serviceNode, "service", Objects.toString(route.get("name"), "节点本机服务"),
                        Objects.toString(route.get("backendScheme"), "http") + "://"
                                + Objects.toString(route.get("backendHost"), "127.0.0.1") + ":" + route.get("backendPort"),
                        health, "/service-publishing", ownerId));
                graph.edge(backendPublicNode, serviceNode, "本机 Web 服务", health, true);
                continue;
            }
            long mappingId = number(route.get("mappingId"));
            String mappingNode = "mapping:" + mappingId;
            String mappingStatus = "active".equals(route.get("mappingState")) ? "healthy" : "offline";
            graph.node(new TopologyNode(mappingNode, "mapping", Objects.toString(route.get("mappingName")),
                    "反向端口映射", mappingStatus, "/service-publishing", ownerId));
            graph.edge(publicNode, mappingNode, "内网映射", mappingStatus, true);
            String connectorNode = "connector:" + connectorId;
            String connectorStatus = WebSocketServer.isConnectorOnline(connectorId) ? "healthy" : "offline";
            graph.node(new TopologyNode(connectorNode, "connector", Objects.toString(route.get("connectorName"), "接入端"),
                    "Flux 内网接入端", connectorStatus, "/service-publishing", ownerId));
            graph.edge(mappingNode, connectorNode, "反向连接", connectorStatus, true);
            String targetNode = "internal-target:" + mappingId;
            graph.node(new TopologyNode(targetNode, "service", "内网服务", route.get("targetHost") + ":" + route.get("targetPort"),
                    connectorStatus, "/service-publishing", ownerId));
            graph.edge(connectorNode, targetNode, "目标", connectorStatus, true);
        }
    }

    private Map<Long, Map<String, Object>> loadTunnels() {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id,name,owner_user_id AS ownerUserId,in_node_id AS inNodeId,out_node_id AS outNodeId,node_path AS nodePath,type FROM tunnel")) {
            result.put(number(row.get("id")), row);
        }
        return result;
    }

    private Map<Long, Map<String, Object>> loadNodes() {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT id,name,server_ip AS serverIp,ip FROM node")) {
            result.put(number(row.get("id")), row);
        }
        return result;
    }

    private String addUser(Graph graph, int id, String name) {
        String key = "user:" + id;
        graph.node(new TopologyNode(key, "user", name, id == 1 ? "管理员" : "普通用户", "healthy", "/user", id));
        return key;
    }

    private String addTunnel(Graph graph, Map<String, Object> tunnel, Map<Long, Map<String, Object>> nodes) {
        long id = number(tunnel.get("id"));
        String key = "tunnel:" + id;
        List<Long> path = tunnelPath(tunnel);
        graph.node(new TopologyNode(key, "tunnel", Objects.toString(tunnel.get("name")), path.size() + " 级隧道",
                tunnelHealthy(tunnel, nodes) ? "healthy" : "offline", "/tunnel", intValue(tunnel.get("ownerUserId"))));
        return key;
    }

    private String addPublicNode(Graph graph, long id, Map<String, Object> row) {
        String key = "node:" + id;
        graph.node(new TopologyNode(key, "node", row == null ? "节点 " + id : Objects.toString(row.get("name")),
                row == null ? "节点不存在" : Objects.toString(row.get("serverIp"), Objects.toString(row.get("ip"))),
                nodeStatus(id), "/node", 0));
        return key;
    }

    private boolean tunnelHealthy(Map<String, Object> tunnel, Map<Long, Map<String, Object>> nodes) {
        if (tunnel == null) return false;
        List<Long> path = tunnelPath(tunnel);
        return !path.isEmpty() && path.stream().allMatch(id -> nodes.containsKey(id) && WebSocketServer.isNodeOnline(id));
    }

    private List<Long> tunnelPath(Map<String, Object> tunnel) {
        if (tunnel == null) return List.of();
        LinkedHashSet<Long> path = new LinkedHashSet<>();
        String raw = Objects.toString(tunnel.get("nodePath"), "");
        for (String value : raw.split(",")) {
            try { if (!value.isBlank()) path.add(Long.parseLong(value.trim())); } catch (NumberFormatException ignored) {}
        }
        if (path.isEmpty()) {
            path.add(number(tunnel.get("inNodeId")));
            path.add(number(tunnel.get("outNodeId")));
        }
        return new ArrayList<>(path);
    }

    private String domainRouteStatus(Map<String, Object> route, long nodeId, long connectorId,
                                     long backendNodeId, boolean direct) {
        String state = Objects.toString(route.get("state"));
        if (List.of("certificate_failed", "deployment_failed", "delete_pending").contains(state)) return "failed";
        if (!WebSocketServer.isNodeOnline(nodeId)) return "offline";
        if (direct) {
            if (backendNodeId < 0 || !WebSocketServer.isNodeOnline(backendNodeId)) return "offline";
            if ("unhealthy".equals(route.get("healthState"))) return "failed";
        } else if (connectorId < 0 || !WebSocketServer.isConnectorOnline(connectorId)) {
            return "offline";
        }
        if (List.of("renewal_failed", "deployment_failed").contains(Objects.toString(route.get("certificateState")))) return "degraded";
        return "active".equals(state) ? "healthy" : "degraded";
    }

    private String nodeStatus(long id) { return WebSocketServer.isNodeOnline(id) ? "healthy" : "offline"; }
    private String normalizeStatus(String value) {
        return switch (value) {
            case "healthy", "active", "online" -> "healthy";
            case "degraded", "unknown", "pending" -> "degraded";
            case "paused", "disabled" -> "paused";
            default -> "offline";
        };
    }
    private long number(Object value) { return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString()); }
    private long nullableNumber(Object value, long fallback) { return value == null ? fallback : number(value); }
    private int intValue(Object value) { return value instanceof Number n ? n.intValue() : Integer.parseInt(Objects.toString(value, "0")); }

    private static final class Graph {
        private final Map<String, TopologyNode> nodes = new LinkedHashMap<>();
        private final List<TopologyEdge> edges = new ArrayList<>();
        void node(TopologyNode node) { nodes.putIfAbsent(node.id(), node); }
        void edge(String source, String target, String label, String status, boolean active) {
            if (!nodes.containsKey(source) || !nodes.containsKey(target)) return;
            String id = source + "->" + target + ":" + label;
            if (edges.stream().noneMatch(edge -> edge.id().equals(id))) edges.add(new TopologyEdge(id, source, target, label, status, active));
        }
    }

    public record TopologyNode(String id, String type, String label, String subtitle, String status, String path, int ownerUserId) {}
    public record TopologyEdge(String id, String source, String target, String label, String status, boolean active) {}
}
