package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Checks a tunnel path hop by hop and describes it as nodes and links for the link diagram.
 *
 * A hop whose port is not listening yet (the tunnel is not created, or no forward uses it)
 * is tested on the real port: the next node opens a short-lived listener on the port the
 * next forward would get, and the previous node connects to it. This catches security
 * groups, firewalls and port mappings before anything is saved.
 */
@Service
public class TunnelLinkCheckService {
    public static final String PROBE_AGENT_VERSION = "2.44.1";
    static final String INTERNET_HOST = "www.google.com";
    static final int INTERNET_PORT = 443;

    /** The agent operations a check needs; replaced in tests. */
    public interface Agent {
        boolean online(Long nodeId);

        Ping tcpPing(Long nodeId, String host, int port);

        /** Opens a temporary TCP listener; returns null on success or the error message. */
        String openListener(Long nodeId, String session, int port);

        void closeListener(Long nodeId, String session);
    }

    public static final class Ping {
        final boolean success;
        final double latencyMs;
        final String error;

        public Ping(boolean success, double latencyMs, String error) {
            this.success = success;
            this.latencyMs = latencyMs;
            this.error = error;
        }
    }

    /** One hop of the path. A null livePort means the port is not listening and must be probed. */
    public static final class Hop {
        final Node from;
        final Node to;
        final String address;
        final String mode;
        final Integer livePort;
        final Integer previewPort;

        public Hop(Node from, Node to, String address, String mode, Integer livePort, Integer previewPort) {
            this.from = from;
            this.to = to;
            this.address = address;
            this.mode = mode;
            this.livePort = livePort;
            this.previewPort = previewPort;
        }
    }

    private final Agent agent;

    public TunnelLinkCheckService() {
        this(new WebSocketAgent());
    }

    TunnelLinkCheckService(Agent agent) {
        this.agent = agent;
    }

    /**
     * @param path   nodes from entry to exit; a single node for a port-forward tunnel
     * @param hops   one hop per consecutive pair in path
     * @param shared CNIX shared relay tunnels have their own diagnosis and skip probing here
     */
    public Map<String, Object> check(List<Node> path, List<Hop> hops, String protocol, boolean shared) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        boolean[] online = new boolean[path.size()];
        for (int index = 0; index < path.size(); index++) {
            Node node = path.get(index);
            online[index] = agent.online(node.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.getId());
            item.put("name", node.getName());
            item.put("role", path.size() == 1 ? "entry" : index == 0 ? "entry" : index == path.size() - 1 ? "exit" : "relay");
            item.put("address", firstNonBlank(node.getServerIp(), node.getIp()));
            item.put("version", node.getVersion());
            item.put("online", online[index]);
            item.put("status", online[index] ? "ok" : "fail");
            item.put("message", online[index] ? "在线" : "节点离线");
            nodes.add(item);
        }

        List<Map<String, Object>> links = new ArrayList<>();
        boolean udpTransport = protocol != null && isUdpTransport(protocol);
        for (int index = 0; index < hops.size(); index++) {
            Hop hop = hops.get(index);
            Map<String, Object> link = link(index, index + 1, "hop",
                    hop.from.getName() + " → " + hop.to.getName(), hop.address, hop.mode);
            if (shared) {
                skip(link, "CNIX 共享隧道创建后用专用诊断检测");
            } else if (!online[index] || !online[index + 1]) {
                skip(link, "两端节点在线后才能检测");
            } else if (hop.livePort != null) {
                link.put("port", hop.livePort);
                link.put("probe", "live");
                ping(link, hop.from, hop.address, hop.livePort, portRange(hop.to), udpTransport);
            } else {
                probeWithListener(link, hop, udpTransport);
            }
            links.add(link);
        }

        Node exit = path.get(path.size() - 1);
        Map<String, Object> internet = link(path.size() - 1, -1, "internet",
                exit.getName() + " → 外网", INTERNET_HOST, null);
        internet.put("port", INTERNET_PORT);
        if (shared && path.size() > 1) {
            skip(internet, "CNIX 共享隧道创建后用专用诊断检测");
        } else if (!online[path.size() - 1]) {
            skip(internet, "节点离线");
        } else {
            Ping result = agent.tcpPing(exit.getId(), INTERNET_HOST, INTERNET_PORT);
            if (result.success) {
                ok(internet, result.latencyMs, "可以访问外网");
            } else {
                // The tunnel itself can still work; only this destination is unreachable.
                internet.put("status", "warn");
                internet.put("message", "访问 " + INTERNET_HOST + ":443 失败，可能只是该网站被屏蔽：" + explain(result.error, null));
            }
        }
        links.add(internet);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("nodes", nodes);
        report.put("links", links);
        String status = overall(nodes, links);
        report.put("status", status);
        report.put("message", summary(status, nodes, links));
        report.put("checkedAt", System.currentTimeMillis());
        return report;
    }

    private void probeWithListener(Map<String, Object> link, Hop hop, boolean udpTransport) {
        String range = portRange(hop.to);
        if (hop.previewPort == null) {
            link.put("status", "fail");
            link.put("message", hop.to.getName() + " 的端口范围 " + range + " 已经分配完");
            return;
        }
        link.put("port", hop.previewPort);
        if (!AgentVersionUtil.isAtLeast(hop.from.getVersion(), PROBE_AGENT_VERSION)
                || !AgentVersionUtil.isAtLeast(hop.to.getVersion(), PROBE_AGENT_VERSION)) {
            link.put("status", "warn");
            link.put("probe", "none");
            link.put("message", "两端 Agent 需要 " + PROBE_AGENT_VERSION + " 以上才能在创建前测试真实端口");
            return;
        }
        link.put("probe", "listener");
        String session = "tunnel-check-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String openError = agent.openListener(hop.to.getId(), session, hop.previewPort);
        if (openError != null) {
            link.put("status", "warn");
            link.put("message", hop.to.getName() + " 的临时诊断监听端口 " + hop.previewPort
                    + " 启动失败：" + openError + "；这不代表隧道已经使用该端口，请重新诊断");
            return;
        }
        try {
            ping(link, hop.from, hop.address, hop.previewPort, range, udpTransport);
        } finally {
            agent.closeListener(hop.to.getId(), session);
        }
    }

    private void ping(Map<String, Object> link, Node from, String address, int port, String range, boolean udpTransport) {
        Ping result = agent.tcpPing(from.getId(), address, port);
        if (result.success) {
            ok(link, result.latencyMs, udpTransport
                    ? "TCP 端口可达；QUIC 等协议走 UDP，还需放行 UDP"
                    : "端口 " + port + " 可达");
        } else {
            link.put("status", "fail");
            link.put("message", explain(result.error, range));
        }
    }

    static String explain(String error, String range) {
        String text = error == null ? "" : error.toLowerCase(Locale.ROOT);
        String scope = range == null ? "" : "端口范围 " + range;
        if (text.contains("timeout") || text.contains("timed out") || text.contains("deadline")) {
            return "连接超时：安全组、防火墙或端口映射可能没有放行" + scope;
        }
        if (text.contains("refused")) {
            return "连接被拒绝：对端端口没有放行或被其他设备拦截";
        }
        if (text.contains("no route") || text.contains("unreachable")) {
            return "地址不可达：检查跳点地址和路由";
        }
        if (text.contains("no such host") || text.contains("lookup")) {
            return "域名解析失败：检查跳点地址";
        }
        return error == null || error.isBlank() ? "连接失败" : error;
    }

    static String overall(List<Map<String, Object>> nodes, List<Map<String, Object>> links) {
        boolean warn = false;
        for (Map<String, Object> node : nodes) {
            if ("fail".equals(node.get("status"))) return "fail";
        }
        for (Map<String, Object> link : links) {
            if ("fail".equals(link.get("status"))) return "fail";
            if ("warn".equals(link.get("status"))) warn = true;
        }
        return warn ? "warn" : "ok";
    }

    private static String summary(String status, List<Map<String, Object>> nodes, List<Map<String, Object>> links) {
        for (Map<String, Object> node : nodes) {
            if ("fail".equals(node.get("status"))) return node.get("name") + " 离线";
        }
        for (Map<String, Object> link : links) {
            if ("fail".equals(link.get("status"))) return link.get("label") + "：" + link.get("message");
        }
        if ("warn".equals(status)) {
            for (Map<String, Object> link : links) {
                if ("warn".equals(link.get("status"))) return link.get("label") + "：" + link.get("message");
            }
        }
        return "整条链路可用";
    }

    private static boolean isUdpTransport(String protocol) {
        return "quic".equals(protocol.toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> link(int from, int to, String kind, String label, String address, String mode) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("from", from);
        link.put("to", to);
        link.put("kind", kind);
        link.put("label", label);
        link.put("address", address);
        link.put("mode", mode);
        link.put("status", "skip");
        return link;
    }

    private static void ok(Map<String, Object> link, double latencyMs, String message) {
        link.put("status", "ok");
        link.put("latencyMs", latencyMs > 0 ? Math.round(latencyMs * 10) / 10.0 : null);
        link.put("message", message);
    }

    private static void skip(Map<String, Object> link, String message) {
        link.put("status", "skip");
        link.put("message", message);
    }

    private static String portRange(Node node) {
        return node.getPortSta() == null || node.getPortEnd() == null ? null : node.getPortSta() + "-" + node.getPortEnd();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    /** Talks to agents over the panel websocket. */
    static final class WebSocketAgent implements Agent {
        @Override
        public boolean online(Long nodeId) {
            return WebSocketServer.isNodeOnline(nodeId);
        }

        @Override
        public Ping tcpPing(Long nodeId, String host, int port) {
            JSONObject request = new JSONObject();
            request.put("ip", host);
            request.put("port", port);
            request.put("count", 2);
            request.put("timeout", 3000);
            GostDto response = WebSocketServer.send_msg(nodeId, request, "TcpPing", 15);
            if (response == null) return new Ping(false, -1, "节点无响应");
            if (!"OK".equals(response.getMsg())) return new Ping(false, -1, response.getMsg());
            if (response.getData() == null) return new Ping(true, 0, null);
            JSONObject data = response.getData() instanceof JSONObject
                    ? (JSONObject) response.getData()
                    : JSON.parseObject(JSON.toJSONString(response.getData()));
            return data.getBooleanValue("success")
                    ? new Ping(true, data.getDoubleValue("averageTime"), null)
                    : new Ping(false, -1, data.getString("errorMessage"));
        }

        @Override
        public String openListener(Long nodeId, String session, int port) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("sessionId", session);
            request.put("protocol", "tcp");
            request.put("listenPort", port);
            request.put("ttlSeconds", 20);
            request.put("maximumBytes", 1024);
            request.put("maximumStreams", 4);
            GostDto response = WebSocketServer.send_msg(nodeId, request, "BandwidthPrepare", 10);
            if (response == null) return "节点无响应";
            return "OK".equals(response.getMsg()) ? null : response.getMsg();
        }

        @Override
        public void closeListener(Long nodeId, String session) {
            WebSocketServer.send_msg(nodeId, Map.of("sessionId", session), "BandwidthStop", 5);
        }
    }
}
