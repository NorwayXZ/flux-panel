package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class SystemSelfCheckService {
    public static final String MIN_AGENT_VERSION = "2.41.0";
    public static final String MIN_CONNECTOR_SELF_CHECK_VERSION = "2.41.2";
    private final JdbcTemplate jdbcTemplate;
    private final NodeService nodeService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "system-self-check");
        thread.setDaemon(true);
        return thread;
    });

    public SystemSelfCheckService(JdbcTemplate jdbcTemplate, NodeService nodeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.nodeService = nodeService;
    }

    public R overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimumAgentVersion", MIN_AGENT_VERSION);
        result.put("minimumConnectorVersion", MIN_CONNECTOR_SELF_CHECK_VERSION);
        result.put("nodes", jdbcTemplate.queryForList(
                "SELECT id,name,server_ip AS serverIp,ip,status,version FROM node ORDER BY status DESC,id DESC"));
        List<Map<String, Object>> connectors = jdbcTemplate.queryForList(
                "SELECT id,name,platform,status,version,remote_ip AS remoteIp,last_seen AS lastSeen "
                        + "FROM internal_connector WHERE status=1 ORDER BY last_seen DESC,id DESC");
        for (Map<String, Object> connector : connectors) {
            connector.put("online", WebSocketServer.isConnectorOnline(((Number) connector.get("id")).longValue()));
        }
        result.put("connectors", connectors);
        List<Map<String, Object>> runs = jdbcTemplate.queryForList(
                "SELECT id,status,scope_node_id AS scopeNodeId,scope_type AS scopeType,scope_resource_id AS scopeResourceId,"
                        + "total_checks AS totalChecks,healthy_count AS healthyCount,"
                        + "warning_count AS warningCount,failed_count AS failedCount,skipped_count AS skippedCount,message,"
                        + "started_at AS startedAt,finished_at AS finishedAt FROM system_self_check_run ORDER BY id DESC LIMIT 12");
        result.put("history", runs);
        Map<String, Object> latest = runs.isEmpty() ? null : runs.get(0);
        result.put("run", latest);
        result.put("findings", latest == null ? List.of() : findings(((Number) latest.get("id")).longValue()));
        return R.ok(result);
    }

    public synchronized R start(Long nodeId, Long connectorId) {
        Integer running = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_self_check_run WHERE status='running'", Integer.class);
        if (running != null && running > 0) return R.err(409, "已有系统自检正在运行");
        if (nodeId != null && nodeService.getById(nodeId) == null) return R.err("节点不存在");
        if (connectorId != null && !connectorExists(connectorId)) return R.err("接入设备不存在");
        if (nodeId != null && connectorId != null) return R.err("一次只能选择节点或接入设备");
        long now = System.currentTimeMillis();
        String scopeType = connectorId == null ? (nodeId == null ? null : "node") : "connector";
        Long scopeResourceId = connectorId == null ? nodeId : connectorId;
        String message = connectorId != null ? "正在检查指定接入设备" : (nodeId == null ? "正在检查全部资源" : "正在检查指定节点");
        jdbcTemplate.update("INSERT INTO system_self_check_run (status,scope_node_id,scope_type,scope_resource_id,message,requested_by,started_at) VALUES ('running',?,?,?,?,?,?)",
                nodeId, scopeType, scopeResourceId, message, JwtUtil.getUserIdFromToken(), now);
        Long runId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        executor.submit(() -> execute(runId, nodeId, connectorId));
        return overview();
    }

    public R resetIdentityBaseline(long nodeId) {
        Node node = nodeService.getById(nodeId);
        if (node == null) return R.err("节点不存在");
        jdbcTemplate.update("DELETE FROM agent_identity_baseline WHERE node_id=?", nodeId);
        return R.ok("已清除 " + node.getName() + " 的身份基线，下次自检将重新登记");
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void execute(long runId, Long scopeNodeId, Long scopeConnectorId) {
        List<Finding> findings = new ArrayList<>();
        try {
            List<String> domains = loadDomains();
            Map<Long, List<ExpectedPort>> expectedPorts = loadExpectedPorts();
            if (scopeNodeId != null || scopeConnectorId == null) {
                collectNodeFindings(findings, scopeNodeId, domains, expectedPorts);
            }
            if (scopeConnectorId != null || (scopeNodeId == null && scopeConnectorId == null)) {
                collectConnectorFindings(findings, scopeConnectorId, domains, expectedPorts);
            }
            if (scopeNodeId == null && scopeConnectorId == null) collectConfigurationFindings(findings);
            if (findings.isEmpty()) {
                findings.add(new Finding("system", "panel", null, "CloudNest", "healthy", "面板配置",
                        "未发现需要处理的问题", "节点与资源检查均已完成", "无", "无需操作", 900));
            }
            persist(runId, findings);
            finish(runId, "completed", findings, "自检完成");
        } catch (Exception e) {
            log.error("System self-check {} failed", runId, e);
            findings.add(new Finding("system", "panel", null, "CloudNest", "failed", "自检执行器",
                    "自检任务未能完整执行", abbreviate(e.getMessage(), 1000), "部分资源没有得到检查",
                    "查看面板后端日志，修复数据库或 Agent 通信问题后重新运行", 0));
            persist(runId, findings);
            finish(runId, "failed", findings, "自检执行失败");
        }
    }

    private boolean connectorExists(Long connectorId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM internal_connector WHERE id=? AND status=1", Integer.class, connectorId);
        return count != null && count > 0;
    }

    private void collectNodeFindings(List<Finding> findings, Long scopeNodeId, List<String> domains,
                                     Map<Long, List<ExpectedPort>> expectedPorts) {
        List<Node> nodes = scopeNodeId == null ? nodeService.list() : List.of(nodeService.getById(scopeNodeId));
        for (Node node : nodes) {
            if (!WebSocketServer.isNodeOnline(node.getId())) {
                findings.add(new Finding("agent", "node", node.getId(), node.getName(), "failed", "面板 -> Agent",
                        "Agent 当前离线", "面板没有该节点的有效 WebSocket 会话，数据库版本为 " + value(node.getVersion()),
                        "该节点上的转发、隧道、代理和健康检查可能无法管理",
                        "先在服务器检查 gost 服务；如升级后离线，使用节点页的手动升级命令，脚本会失败自动回退", 10));
                continue;
            }
            if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
                findings.add(new Finding("agent", "node", node.getId(), node.getName(), "warning", "Agent 能力",
                        "Agent 在线，但版本不支持完整自检", "当前 " + value(node.getVersion()) + "，完整自检最低需要 " + MIN_AGENT_VERSION,
                        "身份、DNS、协议族和真实监听端口只能跳过",
                        "在节点页升级 Agent；批量升级会先试运行一台，失败后自动暂停", 12));
                continue;
            }
            List<Map<String, Object>> ports = new ArrayList<>();
            for (ExpectedPort expected : expectedPorts.getOrDefault(node.getId(), List.of())) {
                ports.add(Map.of("network", expected.network(), "port", expected.port()));
            }
            GostDto response = WebSocketServer.send_msg(node.getId(), Map.of("domains", domains, "ports", ports),
                    "SystemSelfCheck", 45);
            if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
                findings.add(new Finding("agent", "node", node.getId(), node.getName(), "failed", "面板 -> Agent 自检",
                        "Agent 自检命令没有成功返回", response == null ? "Agent 无响应" : response.getMsg(),
                        "无法确认该节点 DNS、IPv6 与端口真实状态",
                        "确认节点版本已升级并在线，然后重试；失败不会改动节点配置", 14));
                continue;
            }
            JSONObject payload = JSONObject.parseObject(JSON.toJSONString(response.getData()));
            inspectIdentity(findings, node, payload);
            inspectFamilies(findings, node.getName(), "node", node.getId(), payload);
            inspectDNS(findings, node.getName(), "node", node.getId(), payload.getJSONArray("dns"), payload.getJSONArray("dnsResolvers"));
            inspectPorts(findings, node, payload.getJSONArray("ports"), expectedPorts.getOrDefault(node.getId(), List.of()));
        }
    }

    private void collectConnectorFindings(List<Finding> findings, Long scopeConnectorId, List<String> domains,
                                         Map<Long, List<ExpectedPort>> expectedPorts) {
        String sql = "SELECT id,name,secret,platform,version,remote_ip AS remoteIp FROM internal_connector WHERE status=1 "
                + (scopeConnectorId == null ? "" : "AND id=? ") + "ORDER BY id";
        List<Map<String, Object>> connectors = scopeConnectorId == null
                ? jdbcTemplate.queryForList(sql)
                : jdbcTemplate.queryForList(sql, scopeConnectorId);
        List<Map<String, Object>> targets = loadConnectorTargets(expectedPorts);
        for (Map<String, Object> connector : connectors) {
            long id = ((Number) connector.get("id")).longValue();
            String name = Objects.toString(connector.get("name"), "接入设备 " + id);
            String version = Objects.toString(connector.get("version"), null);
            if (!WebSocketServer.isConnectorOnline(id)) {
                findings.add(new Finding("agent", "connector", id, name, "failed", "面板 -> 接入设备",
                        "Connector 当前离线", "平台记录版本 " + value(version) + "，平台无法建立有效 WebSocket 会话",
                        "本机 DNS、IPv4/IPv6、默认路由和入口可达性无法从远程读取",
                        "在该电脑检查 CloudNest Connector 服务；确认它能主动访问面板地址并查看安装日志", 110));
                continue;
            }
            if (!AgentVersionUtil.isAtLeast(version, MIN_CONNECTOR_SELF_CHECK_VERSION)) {
                findings.add(new Finding("agent", "connector", id, name, "warning", "接入设备 Agent 能力",
                        "Connector 在线，但版本不支持完整本机自检", "当前 " + value(version) + "，完整自检最低需要 " + MIN_CONNECTOR_SELF_CHECK_VERSION,
                        "本机默认路由和入口端口可达性只能跳过",
                        "在家庭设备页按对应操作系统重新执行升级命令；升级失败会自动恢复旧版本", 112));
                continue;
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("domains", domains);
            request.put("ports", List.of());
            request.put("targets", targets);
            GostDto response = WebSocketServer.sendConnectorMsg(id, request, "SystemSelfCheck", 45);
            if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
                findings.add(new Finding("agent", "connector", id, name, "failed", "面板 -> 接入设备自检",
                        "Connector 自检命令没有成功返回", response == null ? "Connector 无响应" : response.getMsg(),
                        "无法确认本机 DNS、默认路由和到入口端口的真实可达性",
                        "确认 Connector 版本已升级并在线，然后重试；本次检查不会修改本机网络配置", 114));
                continue;
            }
            JSONObject payload = JSONObject.parseObject(JSON.toJSONString(response.getData()));
            inspectConnectorIdentity(findings, connector, payload);
            inspectFamilies(findings, name, "connector", id, payload);
            inspectDNS(findings, name, "connector", id, payload.getJSONArray("dns"), payload.getJSONArray("dnsResolvers"));
            inspectConnectorRoutes(findings, id, name, payload);
            inspectConnectorReachability(findings, id, name, payload.getJSONArray("reachability"));
        }
    }

    private List<Map<String, Object>> loadConnectorTargets(Map<Long, List<ExpectedPort>> expectedPorts) {
        Map<Long, String> hosts = new HashMap<>();
        for (Node node : nodeService.list()) {
            String host = node.getServerIp();
            if (host == null || host.isBlank()) host = node.getIp();
            if (host != null && !host.isBlank()) hosts.put(node.getId(), host.trim());
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map.Entry<Long, List<ExpectedPort>> entry : expectedPorts.entrySet()) {
            String host = hosts.get(entry.getKey());
            if (host == null) continue;
            for (ExpectedPort expected : entry.getValue()) {
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("name", expected.resourceName() + " · " + host + ":" + expected.port());
                target.put("host", host);
                target.put("port", expected.port());
                target.put("network", "tcp");
                targets.add(target);
                if (targets.size() >= 280) return targets;
            }
        }
        return targets;
    }

    private void inspectConnectorIdentity(List<Finding> findings, Map<String, Object> connector, JSONObject payload) {
        long id = ((Number) connector.get("id")).longValue();
        String name = Objects.toString(connector.get("name"), "接入设备 " + id);
        String expected = shortFingerprint(Objects.toString(connector.get("secret"), ""));
        String actual = payload.getString("identityFingerprint");
        if (!Objects.equals(expected, actual)) {
            findings.add(new Finding("agent", "connector", id, name, "failed", "接入设备身份",
                    "Connector 密钥指纹与设备记录不一致", "面板 " + expected + "，本机 " + value(actual),
                    "可能把另一台接入设备的安装命令复制到了当前电脑",
                    "在家庭设备页重新生成当前设备对应操作系统的安装命令，不要混用其他设备密钥", 116));
            return;
        }
        findings.add(new Finding("agent", "connector", id, name, "healthy", "接入设备身份",
                "Connector 身份已确认", value(payload.getString("hostname")) + " / " + value(payload.getString("os"))
                        + " / " + value(payload.getString("arch")), "无", "无需操作", 117));
    }

    private void inspectConnectorRoutes(List<Finding> findings, long id, String name, JSONObject payload) {
        JSONArray routes = payload.getJSONArray("defaultRoutes");
        String routeText = routes == null ? "[]" : routes.toJSONString();
        if (routes == null || routes.isEmpty()) {
            findings.add(new Finding("network", "connector", id, name, "warning", "本机 -> 默认路由",
                    "本机未读取到默认路由", routeText + "；" + value(payload.getString("routeError")),
                    "没有默认路由时，依赖公网入口的连接可能只能通过静态路由或完全失败",
                    "检查电脑网络连接、系统路由和 VPN；这项检查不会修改路由", 130));
        } else {
            findings.add(new Finding("network", "connector", id, name, "healthy", "本机 -> 默认路由",
                    "本机默认路由存在", routeText, "无", "无需操作", 131));
        }
    }

    private void inspectConnectorReachability(List<Finding> findings, long id, String name, JSONArray results) {
        if (results == null || results.isEmpty()) {
            findings.add(new Finding("network", "connector", id, name, "skipped", "本机 -> 入口端口",
                    "没有可测试的面板或入口端口", "当前没有登记可供本机测试的 TCP 目标", "无法判断本机到入口的 TCP 路径",
                    "先登记一个节点服务、转发、代理或域名入口后重试", 140));
            return;
        }
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            boolean reachable = item.getBooleanValue("reachable");
            String target = value(item.getString("name"));
            String evidence = item.getString("host") + ":" + item.getIntValue("port") + "，耗时 "
                    + item.getLongValue("durationMs") + " ms" + (item.getString("error") == null ? "" : "；" + item.getString("error"));
            findings.add(new Finding("network", "connector", id, name, reachable ? "healthy" : "failed",
                    "本机 -> 入口端口", reachable ? target + " 可达" : target + " 不可达", evidence,
                    reachable ? "无" : "可能是本机防火墙、本地路由、运营商路径或入口服务器端口未监听；单次失败不能单独证明是哪一方",
                    reachable ? "无需操作" : "先用同一台电脑测试其他入口；再从面板检查入口端口监听状态和服务器防火墙。若只有该网络失败，再对比另一运营商网络", 140 + i));
        }
    }

    private void inspectIdentity(List<Finding> findings, Node node, JSONObject payload) {
        String machine = payload.getString("machineFingerprint");
        String identity = payload.getString("identityFingerprint");
        String expectedIdentity = shortFingerprint(node.getSecret());
        String hostname = payload.getString("hostname");
        if (!Objects.equals(identity, expectedIdentity)) {
            findings.add(new Finding("agent", "node", node.getId(), node.getName(), "failed", "Agent 身份",
                    "Agent 密钥指纹与节点记录不一致", "面板 " + expectedIdentity + "，Agent " + value(identity),
                    "节点可能安装了错误密钥，或身份被另一节点覆盖",
                    "不要重复执行其他节点的安装命令；在当前节点重新执行它自己的手动安装命令", 20));
            return;
        }
        List<Map<String, Object>> baselines = jdbcTemplate.queryForList(
                "SELECT machine_fingerprint AS machineFingerprint,hostname FROM agent_identity_baseline WHERE node_id=?", node.getId());
        long now = System.currentTimeMillis();
        if (baselines.isEmpty()) {
            jdbcTemplate.update("INSERT INTO agent_identity_baseline (node_id,machine_fingerprint,hostname,first_seen_at,last_seen_at) VALUES (?,?,?,?,?)",
                    node.getId(), machine, hostname, now, now);
            findings.add(new Finding("agent", "node", node.getId(), node.getName(), "healthy", "Agent 身份",
                    "身份基线已登记", "主机 " + value(hostname) + "，机器指纹 " + value(machine), "无",
                    "以后机器指纹变化会提示密钥可能装错；服务器迁移后可在自检页重置基线", 22));
        } else if (!Objects.equals(machine, baselines.get(0).get("machineFingerprint"))) {
            findings.add(new Finding("agent", "node", node.getId(), node.getName(), "warning", "Agent 身份",
                    "节点连接来自不同机器", "基线 " + baselines.get(0).get("hostname") + "/" + baselines.get(0).get("machineFingerprint")
                            + "，当前 " + value(hostname) + "/" + value(machine),
                    "可能把另一节点的安装密钥装到了这台服务器；也可能是正常迁移",
                    "先核对实际服务器。确认是迁移后点击“重置身份基线”；否则重新安装正确节点密钥", 21));
        } else {
            jdbcTemplate.update("UPDATE agent_identity_baseline SET hostname=?,last_seen_at=? WHERE node_id=?", hostname, now, node.getId());
            findings.add(new Finding("agent", "node", node.getId(), node.getName(), "healthy", "Agent 身份",
                    "Agent 身份与机器基线一致", value(hostname) + " / " + value(machine), "无", "无需操作", 22));
        }
    }

    private void inspectFamilies(List<Finding> findings, String resourceName, String resourceType, Long resourceId, JSONObject payload) {
        JSONObject ipv4 = payload.getJSONObject("ipv4");
        JSONObject ipv6 = payload.getJSONObject("ipv6");
        inspectFamily(findings, resourceName, resourceType, resourceId, "IPv4", ipv4, 30);
        inspectFamily(findings, resourceName, resourceType, resourceId, "IPv6", ipv6, 31);
    }

    private void inspectFamily(List<Finding> findings, String resourceName, String resourceType, Long resourceId, String label, JSONObject family, int order) {
        boolean available = family != null && family.getBooleanValue("available");
        boolean outbound = family != null && family.getBooleanValue("outbound");
        String addresses = family == null ? "[]" : String.valueOf(family.getJSONArray("addresses"));
        if (!available) {
            findings.add(new Finding("network", resourceType, resourceId, resourceName, "skipped", label + " 能力",
                    "节点未检测到可用的 " + label, "接口地址 " + addresses, "不影响另一协议族正常使用",
                    label.equals("IPv6") ? "如果运营商或服务器没有提供 IPv6，可保持现状" : "确认节点至少有一种协议族可访问面板", order));
        } else if (!outbound) {
            findings.add(new Finding("network", resourceType, resourceId, resourceName, "failed", label + " 出站",
                    label + " 有地址但没有可用出站路由", "接口地址 " + addresses + "；错误 " + value(family.getString("error")),
                    "该协议族的域名、DDNS 或转发连接会失败", "检查网关、路由和上游防火墙；这不是特定路由器型号的判断", order));
        } else {
            findings.add(new Finding("network", resourceType, resourceId, resourceName, "healthy", label + " 出站",
                    label + " 地址与出站路由正常", "接口地址 " + addresses, "无", "无需操作", order));
        }
    }

    private void inspectDNS(List<Finding> findings, String resourceName, String resourceType, Long resourceId, JSONArray dnsResults, JSONArray resolvers) {
        if (dnsResults == null || dnsResults.isEmpty()) return;
        for (int i = 0; i < dnsResults.size(); i++) {
            JSONObject dns = dnsResults.getJSONObject(i);
            String domain = dns.getString("domain");
            Set<String> systemA = strings(dns.getJSONArray("systemA"));
            Set<String> publicA = strings(dns.getJSONArray("publicA"));
            Set<String> systemAAAA = strings(dns.getJSONArray("systemAAAA"));
            Set<String> publicAAAA = strings(dns.getJSONArray("publicAAAA"));
            if (publicA.isEmpty() && publicAAAA.isEmpty() && dns.getString("publicError") != null) {
                findings.add(new Finding("dns", "domain", null, domain, "warning", resourceName + " -> 公网 DoH",
                        "公网 DNS 对照查询不可用", value(dns.getString("publicError")),
                        "本轮无法判断系统 DNS 与公网记录是否一致，但不代表域名记录不存在",
                        "确认节点可以访问 1.1.1.1:443 后重试；受限网络可暂时忽略此项", 39));
            } else if (publicA.isEmpty() && publicAAAA.isEmpty()) {
                findings.add(new Finding("dns", "domain", null, domain, "failed", resourceName + " -> 公网 DNS",
                        "公网 DNS 没有返回 A 或 AAAA", "DoH A=" + publicA + "，AAAA=" + publicAAAA + "；" + value(dns.getString("publicError")),
                        "域名无法稳定访问", "检查域名记录状态、服务商同步错误与生效时间", 40));
            } else if (!publicAAAA.isEmpty() && systemAAAA.isEmpty()) {
                findings.add(new Finding("dns", resourceType, resourceId, resourceName, "warning", resourceName + " -> 本地 DNS",
                        "公网已有 AAAA，但该节点的系统 DNS 没有返回", "公网 AAAA=" + publicAAAA + "，系统 AAAA=[]；解析器 "
                                + strings(resolvers),
                        "使用该 DNS 的设备可能无法通过域名连接 IPv6，直接填写 IPv6 仍可能成功",
                        "检查当前网络 DNS 是否过滤 AAAA；无需假定使用 OpenWrt，小米、华为和运营商路由同样适用", 41));
            } else if ((!publicA.isEmpty() && !systemA.equals(publicA)) || (!publicAAAA.isEmpty() && !systemAAAA.equals(publicAAAA))) {
                findings.add(new Finding("dns", resourceType, resourceId, resourceName, "warning", resourceName + " -> DNS 一致性",
                        "系统 DNS 与公网 DoH 结果不一致", "系统 A=" + systemA + " AAAA=" + systemAAAA + "；公网 A=" + publicA + " AAAA=" + publicAAAA,
                        "可能命中旧缓存、分线路解析或本地 DNS 改写", "等待 TTL 后复查；如持续不一致，检查本地 DNS 和运营商线路解析策略", 42));
            } else {
                findings.add(new Finding("dns", resourceType, resourceId, resourceName, "healthy", resourceName + " -> DNS",
                        "系统 DNS 与公网记录一致", "A=" + publicA + "，AAAA=" + publicAAAA, "无", "无需操作", 43));
            }
        }
    }

    private void inspectPorts(List<Finding> findings, Node node, JSONArray actual, List<ExpectedPort> expected) {
        Map<String, Boolean> listening = new HashMap<>();
        if (actual != null) {
            for (int i = 0; i < actual.size(); i++) {
                JSONObject item = actual.getJSONObject(i);
                listening.put(item.getString("network") + ":" + item.getIntValue("port"), item.getBooleanValue("listening"));
            }
        }
        int healthy = 0;
        for (ExpectedPort port : expected) {
            if (Boolean.TRUE.equals(listening.get(port.network() + ":" + port.port()))) {
                healthy++;
                continue;
            }
            findings.add(new Finding("port", port.resourceType(), port.resourceId(), port.resourceName(), "failed",
                    node.getName() + " 端口账本 -> 真实监听", "配置存在但 " + port.network().toUpperCase() + " " + port.port() + " 未监听",
                    "资源 " + port.resourceName() + " 要求节点监听 " + port.network() + ":" + port.port(),
                    "外部连接会超时或被拒绝", "先检查资源状态与 Agent 日志；不要直接删除端口账本，确认运行时服务后再重建该资源", 50));
        }
        if (healthy > 0) {
            findings.add(new Finding("port", "node", node.getId(), node.getName(), "healthy", "端口账本 -> 真实监听",
                    healthy + " 个已登记端口正在真实监听", "已检查 " + expected.size() + " 个端口", "无", "无需操作", 51));
        } else if (expected.isEmpty()) {
            findings.add(new Finding("port", "node", node.getId(), node.getName(), "skipped", "端口账本",
                    "该节点没有需要核对的活动端口", "未发现活动转发、域名入口、端口池或私人代理端口", "无", "无需操作", 52));
        }
    }

    private void collectConfigurationFindings(List<Finding> findings) {
        Map<Long, Map<String, Object>> nodes = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT id,name FROM node")) {
            nodes.put(((Number) row.get("id")).longValue(), row);
        }
        Map<Long, Map<String, Object>> tunnels = new HashMap<>();
        for (Map<String, Object> tunnel : jdbcTemplate.queryForList(
                "SELECT id,name,in_node_id AS inNodeId,out_node_id AS outNodeId,node_path AS nodePath,status FROM tunnel")) {
            long id = ((Number) tunnel.get("id")).longValue();
            tunnels.put(id, tunnel);
            List<Long> path = tunnelPath(tunnel);
            List<Long> missing = path.stream().filter(nodeId -> !nodes.containsKey(nodeId)).toList();
            List<Long> offline = path.stream().filter(nodeId -> nodes.containsKey(nodeId) && !WebSocketServer.isNodeOnline(nodeId)).toList();
            if (!missing.isEmpty() || !offline.isEmpty()) {
                findings.add(new Finding("dependency", "tunnel", id, Objects.toString(tunnel.get("name")), "failed", "隧道 -> 节点",
                        "隧道依赖链不完整", "缺失节点 " + missing + "，离线节点 " + offline,
                        "使用该隧道的转发会中断", "恢复离线节点；缺失节点无法恢复时，编辑隧道路径并重新部署", 60));
            }
        }
        collectForwardDependencies(findings, tunnels);
        collectDomainDependencies(findings);
        collectDnsAndCertificateFindings(findings);
    }

    private void collectForwardDependencies(List<Finding> findings, Map<Long, Map<String, Object>> tunnels) {
        for (Map<String, Object> forward : jdbcTemplate.queryForList(
                "SELECT id,name,tunnel_id AS tunnelId,route_config AS routeConfig,remote_addr AS remoteAddr,status FROM forward")) {
            long id = ((Number) forward.get("id")).longValue();
            long primary = ((Number) forward.get("tunnelId")).longValue();
            if (!tunnels.containsKey(primary)) {
                findings.add(new Finding("dependency", "forward", id, Objects.toString(forward.get("name")), "failed", "转发 -> 隧道",
                        "主隧道不存在", "转发引用隧道 " + primary, "转发无法建立", "重新选择有效隧道后保存转发", 62));
                continue;
            }
            LinkedHashSet<Long> routeIds = new LinkedHashSet<>();
            routeIds.add(primary);
            try {
                JSONArray routes = JSON.parseArray(Objects.toString(forward.get("routeConfig"), "[]"));
                for (int i = 0; i < routes.size(); i++) {
                    Long tunnelId = routes.getJSONObject(i).getLong("tunnelId");
                    if (tunnelId != null) routeIds.add(tunnelId);
                }
            } catch (Exception ignored) { }
            if (routeIds.size() > 1) {
                Set<String> exits = new LinkedHashSet<>();
                for (Long routeId : routeIds) {
                    Map<String, Object> tunnel = tunnels.get(routeId);
                    if (tunnel != null) exits.add(String.valueOf(tunnel.get("outNodeId")) + "|" + forward.get("remoteAddr"));
                }
                if (exits.size() == 1) {
                    findings.add(new Finding("dependency", "forward", id, Objects.toString(forward.get("name")), "warning", "多入口 -> 出口",
                            "多条候选线路最终汇聚到同一个出口端点", "候选隧道 " + routeIds + "，共同端点 " + exits,
                            "入口故障可以分散，但出口节点或出口端口故障仍会同时影响全部线路",
                            "这是可用架构但不是完整端到端冗余；需要时增加独立出口节点或独立出口端口", 63));
                }
            }
        }
    }

    private void collectDomainDependencies(List<Finding> findings) {
        for (Map<String, Object> route : jdbcTemplate.queryForList(
                "SELECT r.id,r.name,r.domain,r.node_id AS nodeId,r.state,r.last_error AS lastError,r.ingress_mode AS ingressMode,"
                        + "r.certificate_id AS certificateId,r.health_state AS healthState,r.health_error AS healthError,c.state AS certificateState,c.expires_at AS expiresAt "
                        + "FROM domain_route r LEFT JOIN managed_certificate c ON c.id=r.certificate_id WHERE r.state<>'deleted'")) {
            long id = ((Number) route.get("id")).longValue();
            String domain = Objects.toString(route.get("domain"));
            if (!"active".equals(route.get("state"))) {
                findings.add(new Finding("dependency", "domain_route", id, domain, "failed", "域名 -> 发布入口",
                        "域名入口不在活动状态", "状态 " + route.get("state") + "；" + value(route.get("lastError")),
                        "域名可能无法访问后端服务", "打开内网映射中的域名入口，按最后错误修复 DNS、证书或部署", 70));
            }
            if ("managed_https".equals(route.get("ingressMode"))) {
                if (route.get("certificateId") == null || !List.of("active", "issued").contains(route.get("certificateState"))) {
                    findings.add(new Finding("certificate", "domain_route", id, domain, "failed", "域名 -> HTTPS 证书",
                            "托管 HTTPS 没有可用证书", "证书状态 " + value(route.get("certificateState")),
                            "浏览器 TLS 握手会失败", "在域名入口中重新申请证书并检查 DNS API 权限", 71));
                }
            }
            if ("unhealthy".equals(route.get("healthState"))) {
                findings.add(new Finding("dependency", "domain_route", id, domain, "failed", "入口 -> 后端服务",
                        "后端健康检查失败", value(route.get("healthError")), "入口可连接但页面可能为空白或返回网关错误",
                        "确认后端 IP、端口、协议和根路径；在目标节点本机测试后端地址", 72));
            }
        }
    }

    private void collectDnsAndCertificateFindings(List<Finding> findings) {
        for (Map<String, Object> item : jdbcTemplate.queryForList(
                "SELECT id,fqdn,record_type AS recordType,status,last_error AS lastError FROM dns_managed_record WHERE status<>'active' OR last_error IS NOT NULL")) {
            findings.add(new Finding("dns", "dns_record", ((Number) item.get("id")).longValue(), Objects.toString(item.get("fqdn")), "warning",
                    "面板 -> DNS 服务商", "DNS 记录同步存在异常", "类型 " + item.get("recordType") + "，状态 " + item.get("status") + "；" + value(item.get("lastError")),
                    "公网解析可能仍是旧地址", "在资源中心的 DNS 与域名中重新同步，确认 API 凭据与记录权限", 75));
        }
        for (Map<String, Object> item : jdbcTemplate.queryForList(
                "SELECT id,name,record_name AS recordName,record_type AS recordType,last_status AS lastStatus,last_error AS lastError "
                        + "FROM dynamic_dns_rule WHERE enabled=1 AND last_status='error'")) {
            findings.add(new Finding("dns", "dynamic_dns", ((Number) item.get("id")).longValue(), Objects.toString(item.get("name")), "failed",
                    "Agent -> DDNS -> DNS 服务商", "动态 DNS 最近更新失败", item.get("recordName") + " " + item.get("recordType") + "；" + value(item.get("lastError")),
                    "IP 变化后域名会继续指向旧地址", "检查来源节点在线状态、DNS API 凭据与记录权限，然后立即运行该 DDNS 规则", 76));
        }
        long renewalWindow = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000;
        for (Map<String, Object> item : jdbcTemplate.queryForList(
                "SELECT id,domain,state,expires_at AS expiresAt,last_error AS lastError FROM managed_certificate "
                        + "WHERE state IN ('failed','renewal_failed') OR (expires_at IS NOT NULL AND expires_at<?)", renewalWindow)) {
            String status = List.of("failed", "renewal_failed").contains(item.get("state")) ? "failed" : "warning";
            findings.add(new Finding("certificate", "certificate", ((Number) item.get("id")).longValue(), Objects.toString(item.get("domain")), status,
                    "证书 -> 自动续期", "证书需要处理", "状态 " + item.get("state") + "，到期时间 " + value(item.get("expiresAt")) + "；" + value(item.get("lastError")),
                    "到期后 HTTPS 会出现证书错误", "检查 DNS API 后手动重试；系统仍会按续期计划自动重试", 77));
        }
    }

    private List<String> loadDomains() {
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT domain AS value FROM domain_route WHERE state<>'deleted' UNION SELECT fqdn AS value FROM dns_managed_record WHERE status='active' LIMIT 40")) {
            String value = Objects.toString(row.get("value"), "").trim();
            if (!value.isEmpty()) domains.add(value);
        }
        return new ArrayList<>(domains);
    }

    private Map<Long, List<ExpectedPort>> loadExpectedPorts() {
        Map<Long, List<ExpectedPort>> result = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT r.id,r.name,r.node_id AS nodeId,r.listen_port AS port FROM domain_route r WHERE r.state='active'")) {
            addExpected(result, row, "tcp", "domain_route");
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id,name,node_id AS nodeId,listen_port AS port,proxy_type AS proxyType FROM private_proxy WHERE state='active'")) {
            String type = Objects.toString(row.get("proxyType"), "").toLowerCase();
            addExpected(result, row, List.of("hysteria2", "tuic", "wireguard").contains(type) ? "udp" : "tcp", "private_proxy");
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id,name,node_id AS nodeId,control_port AS port FROM port_pool WHERE status=1")) {
            addExpected(result, row, "tcp", "port_pool");
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT f.id,f.name,t.in_node_id AS nodeId,f.in_port AS port,f.protocol_mode AS protocolMode "
                        + "FROM forward f JOIN tunnel t ON t.id=f.tunnel_id WHERE f.status=1")) {
            String mode = Objects.toString(row.get("protocolMode"), "tcp_udp");
            if (!"udp".equals(mode)) addExpected(result, row, "tcp", "forward");
            if (!"tcp".equals(mode)) addExpected(result, row, "udp", "forward");
        }
        return result;
    }

    private void addExpected(Map<Long, List<ExpectedPort>> target, Map<String, Object> row, String network, String resourceType) {
        if (row.get("nodeId") == null || row.get("port") == null) return;
        long nodeId = ((Number) row.get("nodeId")).longValue();
        ExpectedPort item = new ExpectedPort(network, ((Number) row.get("port")).intValue(), resourceType,
                ((Number) row.get("id")).longValue(), Objects.toString(row.get("name"), resourceType));
        if (!target.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).contains(item)) target.get(nodeId).add(item);
    }

    private List<Map<String, Object>> findings(long runId) {
        return jdbcTemplate.queryForList(
                "SELECT id,category,resource_type AS resourceType,resource_id AS resourceId,resource_name AS resourceName,status,"
                        + "fault_segment AS faultSegment,summary,evidence,impact,remediation,sort_order AS sortOrder,created_at AS createdAt "
                        + "FROM system_self_check_finding WHERE run_id=? ORDER BY FIELD(status,'failed','warning','healthy','skipped'),sort_order,id", runId);
    }

    private void persist(long runId, List<Finding> findings) {
        long now = System.currentTimeMillis();
        for (Finding finding : findings) {
            jdbcTemplate.update("INSERT INTO system_self_check_finding "
                            + "(run_id,category,resource_type,resource_id,resource_name,status,fault_segment,summary,evidence,impact,remediation,sort_order,created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    runId, finding.category(), finding.resourceType(), finding.resourceId(), finding.resourceName(), finding.status(),
                    finding.faultSegment(), abbreviate(finding.summary(), 500), finding.evidence(), abbreviate(finding.impact(), 500),
                    finding.remediation(), finding.sortOrder(), now);
        }
    }

    private void finish(long runId, String status, List<Finding> findings, String message) {
        int healthy = (int) findings.stream().filter(item -> "healthy".equals(item.status())).count();
        int warning = (int) findings.stream().filter(item -> "warning".equals(item.status())).count();
        int failed = (int) findings.stream().filter(item -> "failed".equals(item.status())).count();
        int skipped = (int) findings.stream().filter(item -> "skipped".equals(item.status())).count();
        jdbcTemplate.update("UPDATE system_self_check_run SET status=?,total_checks=?,healthy_count=?,warning_count=?,failed_count=?,"
                        + "skipped_count=?,message=?,finished_at=? WHERE id=?",
                status, findings.size(), healthy, warning, failed, skipped, message, System.currentTimeMillis(), runId);
    }

    private List<Long> tunnelPath(Map<String, Object> tunnel) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (String value : Objects.toString(tunnel.get("nodePath"), "").split(",")) {
            try { if (!value.isBlank()) result.add(Long.parseLong(value.trim())); } catch (NumberFormatException ignored) { }
        }
        if (result.isEmpty()) {
            result.add(((Number) tunnel.get("inNodeId")).longValue());
            result.add(((Number) tunnel.get("outNodeId")).longValue());
        }
        return new ArrayList<>(result);
    }

    private Set<String> strings(JSONArray array) {
        if (array == null) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : array) values.add(String.valueOf(item));
        return values;
    }

    private static String shortFingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.substring(0, 16);
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private String value(Object value) { return Objects.toString(value, "未提供"); }
    private String abbreviate(String value, int length) {
        if (value == null) return null;
        return value.length() <= length ? value : value.substring(0, length);
    }

    private record ExpectedPort(String network, int port, String resourceType, long resourceId, String resourceName) { }
    private record Finding(String category, String resourceType, Long resourceId, String resourceName, String status,
                           String faultSegment, String summary, String evidence, String impact, String remediation, int sortOrder) { }
}
