package com.admin.service.impl;

import com.admin.common.dto.HomeProxyRouteCreateDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.HomeProxyRoute;
import com.admin.entity.InternalConnector;
import com.admin.entity.Node;
import com.admin.entity.PortLease;
import com.admin.entity.PortPool;
import com.admin.entity.PortPoolGrant;
import com.admin.entity.User;
import com.admin.mapper.HomeProxyRouteMapper;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortLeaseMapper;
import com.admin.mapper.PortPoolMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.HomeProxyService;
import com.admin.service.PortPoolGrantService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class HomeProxyServiceImpl implements HomeProxyService {
    private static final String MIN_AGENT_VERSION = "2.7.0";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource private HomeProxyRouteMapper routeMapper;
    @Resource private InternalConnectorMapper connectorMapper;
    @Resource private PortPoolMapper poolMapper;
    @Resource private PortLeaseMapper leaseMapper;
    @Resource private NodeMapper nodeMapper;
    @Resource private UserMapper userMapper;
    @Resource private PortPoolGrantService grantService;
    @Resource private JdbcTemplate jdbcTemplate;
    @Value("${jwt-secret}") private String encryptionSecret;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R create(HomeProxyRouteCreateDto dto) {
        Integer userId = currentUserId();
        InternalConnector connector = connectorMapper.selectById(dto.getConnectorId());
        if (connector == null || connector.getStatus() == null || connector.getStatus() == 0
                || (!isAdmin() && !Objects.equals(connector.getUserId(), userId))) {
            return R.err("家庭接入端不存在或无权使用");
        }
        if (!WebSocketServer.isConnectorOnline(connector.getId())) return R.err("家庭接入端离线，请先启动 Agent");
        if (!AgentVersionUtil.isAtLeast(connector.getVersion(), MIN_AGENT_VERSION)) {
            return R.err("家庭接入端 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }

        PortPool ingress = accessiblePool(dto.getIngressPoolId(), userId);
        PortPool egress = accessiblePool(dto.getEgressPoolId(), userId);
        if (ingress == null || egress == null) return R.err("端口池不存在、已停用或无权使用");
        Node ingressNode = nodeMapper.selectById(ingress.getNodeId());
        Node egressNode = nodeMapper.selectById(egress.getNodeId());
        if (ingressNode == null || egressNode == null) return R.err("入口或出口节点不存在");
        if (!WebSocketServer.isNodeOnline(ingressNode.getId()) || !WebSocketServer.isNodeOnline(egressNode.getId())) {
            return R.err("入口和出口节点都必须在线");
        }
        if (!AgentVersionUtil.isAtLeast(ingressNode.getVersion(), MIN_AGENT_VERSION)
                || !AgentVersionUtil.isAtLeast(egressNode.getVersion(), MIN_AGENT_VERSION)) {
            return R.err("入口和出口节点 Agent 都需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }

        jdbcTemplate.queryForObject("SELECT id FROM service_publish_lock WHERE id=1 FOR UPDATE", Integer.class);
        PortPoolGrant ingressGrant = isAdmin() ? null : selectedGrant(dto.getIngressGrantId(), ingress.getId(), userId);
        PortPoolGrant egressGrant = isAdmin() ? null : selectedGrant(dto.getEgressGrantId(), egress.getId(), userId);
        if (!isAdmin() && (ingressGrant == null || egressGrant == null)) {
            return R.err("所选端口资源未分配给当前用户或已被收回");
        }
        int start = ingressGrant == null ? ingress.getStartPort() : ingressGrant.getStartPort();
        int end = ingressGrant == null ? ingress.getEndPort() : ingressGrant.getEndPort();
        Integer port = findAvailablePort(ingress.getId(), start, end, ingressGrant);
        if (port == null) return R.err("公网入口端口池已没有可用端口");

        boolean authEnabled = Boolean.TRUE.equals(dto.getAuthEnabled());
        String authUsername = authEnabled ? StringUtils.defaultIfBlank(dto.getAuthUsername(), "cloudnest") : null;
        String authPassword = authEnabled ? StringUtils.defaultIfBlank(dto.getAuthPassword(), randomHex(18)) : null;
        if (authEnabled && StringUtils.isBlank(authUsername)) return R.err("启用代理认证时必须填写用户名");

        long now = System.currentTimeMillis();
        HomeProxyRoute route = new HomeProxyRoute();
        route.setUserId(userId);
        route.setName(dto.getName().trim());
        route.setConnectorId(connector.getId());
        route.setIngressPoolId(ingress.getId());
        route.setEgressPoolId(egress.getId());
        route.setPublicPort(port);
        route.setProxyType("socks5");
        route.setAuthEnabled(authEnabled ? 1 : 0);
        route.setAuthUsername(authUsername);
        route.setAuthPassword(authEnabled ? encryptPassword(authPassword) : null);
        route.setState("provisioning");
        route.setCreatedTime(now);
        route.setUpdatedTime(now);
        routeMapper.insert(route);

        PortLease lease = new PortLease();
        lease.setPoolId(ingress.getId());
        lease.setGrantId(ingressGrant == null ? null : ingressGrant.getId());
        lease.setUserId(userId);
        lease.setPort(port);
        lease.setProtocol("tcp");
        lease.setState("reserved");
        lease.setCreatedTime(now);
        lease.setUpdatedTime(now);
        leaseMapper.insert(lease);

        int egressStart = egressGrant == null ? egress.getStartPort() : egressGrant.getStartPort();
        int egressEnd = egressGrant == null ? egress.getEndPort() : egressGrant.getEndPort();
        Integer egressPort = findAvailablePort(egress.getId(), egressStart, egressEnd, egressGrant);
        if (egressPort == null) {
            leaseMapper.deleteById(lease.getId());
            routeMapper.deleteById(route.getId());
            return R.err("家庭出口 VPS 端口池已没有可用端口");
        }
        PortLease egressLease = new PortLease();
        egressLease.setPoolId(egress.getId());
        egressLease.setGrantId(egressGrant == null ? null : egressGrant.getId());
        egressLease.setUserId(userId);
        egressLease.setPort(egressPort);
        egressLease.setProtocol("tcp");
        egressLease.setState("reserved");
        egressLease.setCreatedTime(now);
        egressLease.setUpdatedTime(now);
        leaseMapper.insert(egressLease);
        route.setLeaseId(lease.getId());
        route.setEgressLeaseId(egressLease.getId());
        route.setEgressGatewayPort(egressPort);
        routeMapper.updateById(route);

        AgentPortCheckUtil.Result ingressPortCheck = AgentPortCheckUtil.check(ingressNode,
                List.of(new AgentPortCheckUtil.Check("tcp", ingress.getBindIp(), port)));
        if (!ingressPortCheck.isAvailable()) {
            return failProvision(route, lease, egressLease,
                    "公网入口端口不可用：" + portCheckMessage(ingressPortCheck));
        }
        AgentPortCheckUtil.Result egressPortCheck = AgentPortCheckUtil.check(egressNode,
                List.of(new AgentPortCheckUtil.Check("tcp", egress.getBindIp(), egressPort)));
        if (!egressPortCheck.isAvailable()) {
            return failProvision(route, lease, egressLease,
                    "家庭出口网关端口不可用：" + portCheckMessage(egressPortCheck));
        }

        String base = "home_proxy_" + route.getId();
        String ingressChain = base + "_ingress";
        String egressChain = base + "_egress";
        String ingressAddress = poolAddress(ingress);
        String gatewayName = base + "_egress_gateway";
        String gatewayUsername = "cloudnest_" + randomHex(5);
        String gatewayPassword = randomHex(18);
        String egressAddress = hostPort(egress.getPublicHost(), egressPort);
        GostDto gatewayResult = GostUtil.AddHomeEgressGateway(egressNode.getId(), gatewayName,
                egress.getBindIp(), egressPort, gatewayUsername, gatewayPassword);
        if (!ok(gatewayResult)) {
            return failProvision(route, lease, egressLease, "创建出口 VPS 网关失败：" + message(gatewayResult));
        }
        GostDto ingressResult = GostUtil.AddPublishingChain(connector.getId(), ingressChain, ingressAddress,
                ingress.getAuthUsername(), ingress.getAuthPassword());
        if (!ok(ingressResult)) {
            GostUtil.DeletePublishingGateway(egressNode.getId(), gatewayName);
            return failProvision(route, lease, egressLease, "创建家庭入口反向链失败：" + message(ingressResult));
        }
        GostDto egressResult = GostUtil.AddPublishingChain(connector.getId(), egressChain, egressAddress,
                gatewayUsername, gatewayPassword);
        if (!ok(egressResult)) {
            safeDeleteChains(connector.getId(), ingressChain);
            GostUtil.DeletePublishingGateway(egressNode.getId(), gatewayName);
            return failProvision(route, lease, egressLease, "创建家庭出口链失败：" + message(egressResult));
        }
        GostDto serviceResult = GostUtil.AddHomeProxyService(connector.getId(), base + "_service", ingressChain,
                egressChain, ingress.getBindIp(), port, authEnabled, authUsername, authPassword);
        if (!ok(serviceResult)) {
            safeDeleteChains(connector.getId(), ingressChain, egressChain);
            GostUtil.DeletePublishingGateway(egressNode.getId(), gatewayName);
            return failProvision(route, lease, egressLease, "创建家庭 SOCKS5 服务失败：" + message(serviceResult));
        }
        if (!waitForEndpoint(ingressNode, ingress.getPublicHost(), port)) {
            GostUtil.DeleteHomeProxyService(connector.getId(), base + "_service", ingressChain, egressChain);
            GostUtil.DeletePublishingGateway(egressNode.getId(), gatewayName);
            return failProvision(route, lease, egressLease, "公网入口端口未能在 12 秒内就绪，请检查节点防火墙和端口范围");
        }

        route.setState("active");
        route.setLastError(null);
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
        markLeaseActive(lease);
        markLeaseActive(egressLease);
        return R.ok(enrich(route, true));
    }

    @Override
    public R list() {
        QueryWrapper<HomeProxyRoute> query = new QueryWrapper<HomeProxyRoute>()
                .ne("state", "deleted").orderByDesc("created_time");
        if (!isAdmin()) query.eq("user_id", currentUserId());
        List<HomeProxyRoute> routes = routeMapper.selectList(query);
        List<HomeProxyRoute> result = new ArrayList<>();
        for (HomeProxyRoute route : routes) result.add(enrich(route, true));
        return R.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R delete(Long id) {
        HomeProxyRoute route = routeMapper.selectById(id);
        if (route == null || (!isAdmin() && !Objects.equals(route.getUserId(), currentUserId()))) {
            return R.err("家庭代理不存在或无权访问");
        }
        if ("deleted".equals(route.getState())) return R.ok();
        InternalConnector connector = connectorMapper.selectById(route.getConnectorId());
        boolean connectorCleaned = connector == null;
        if (connector != null && WebSocketServer.isConnectorOnline(connector.getId())) {
            String base = "home_proxy_" + route.getId();
            GostDto result = GostUtil.DeleteHomeProxyService(connector.getId(), base + "_service",
                    base + "_ingress", base + "_egress");
            connectorCleaned = ok(result) || containsNotFound(result);
        }
        PortPool egressPool = poolMapper.selectById(route.getEgressPoolId());
        Node egressNode = egressPool == null ? null : nodeMapper.selectById(egressPool.getNodeId());
        boolean gatewayCleaned = egressNode == null;
        if (egressNode != null && WebSocketServer.isNodeOnline(egressNode.getId())) {
            GostDto result = GostUtil.DeletePublishingGateway(egressNode.getId(), "home_proxy_" + route.getId() + "_egress_gateway");
            gatewayCleaned = ok(result) || containsNotFound(result);
        }
        PortLease lease = route.getLeaseId() == null ? null : leaseMapper.selectById(route.getLeaseId());
        PortLease egressLease = route.getEgressLeaseId() == null ? null : leaseMapper.selectById(route.getEgressLeaseId());
        if (connectorCleaned && gatewayCleaned) {
            markDeleted(route, lease, egressLease);
            return R.ok();
        }
        route.setState("delete_pending");
        route.setLastError(pendingDeleteReason(connectorCleaned, gatewayCleaned));
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
        return R.ok(route);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processPendingDeletes() {
        for (HomeProxyRoute route : routeMapper.selectList(new QueryWrapper<HomeProxyRoute>().eq("state", "delete_pending"))) {
            try {
                InternalConnector connector = connectorMapper.selectById(route.getConnectorId());
                PortPool egressPool = poolMapper.selectById(route.getEgressPoolId());
                Node egressNode = egressPool == null ? null : nodeMapper.selectById(egressPool.getNodeId());
                String base = "home_proxy_" + route.getId();
                boolean connectorCleaned = connector == null;
                if (connector != null && WebSocketServer.isConnectorOnline(connector.getId())) {
                    GostDto result = GostUtil.DeleteHomeProxyService(connector.getId(), base + "_service",
                            base + "_ingress", base + "_egress");
                    connectorCleaned = ok(result) || containsNotFound(result);
                }
                boolean gatewayCleaned = egressNode == null;
                if (egressNode != null && WebSocketServer.isNodeOnline(egressNode.getId())) {
                    GostDto result = GostUtil.DeletePublishingGateway(egressNode.getId(), base + "_egress_gateway");
                    gatewayCleaned = ok(result) || containsNotFound(result);
                }
                if (connectorCleaned && gatewayCleaned) {
                    markDeleted(route,
                            route.getLeaseId() == null ? null : leaseMapper.selectById(route.getLeaseId()),
                            route.getEgressLeaseId() == null ? null : leaseMapper.selectById(route.getEgressLeaseId()));
                } else {
                    route.setLastError(pendingDeleteReason(connectorCleaned, gatewayCleaned));
                    route.setUpdatedTime(System.currentTimeMillis());
                    routeMapper.updateById(route);
                }
            } catch (Exception error) {
                log.warn("清理家庭代理 {} 失败: {}", route.getId(), error.getMessage());
            }
        }
    }

    private R failProvision(HomeProxyRoute route, PortLease lease, PortLease egressLease, String error) {
        leaseMapper.deleteById(lease.getId());
        leaseMapper.deleteById(egressLease.getId());
        route.setLeaseId(null);
        route.setEgressLeaseId(null);
        route.setPublicPort(null);
        route.setEgressGatewayPort(null);
        route.setState("error");
        route.setLastError(error);
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
        return R.err(error);
    }

    private void markDeleted(HomeProxyRoute route, PortLease lease, PortLease egressLease) {
        route.setState("deleted");
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
        markLeaseCooldown(lease);
        markLeaseCooldown(egressLease);
    }

    private void markLeaseCooldown(PortLease lease) {
        if (lease == null) return;
        PortPool pool = poolMapper.selectById(lease.getPoolId());
        lease.setState("cooldown");
        lease.setReleaseAfter(System.currentTimeMillis() + (pool == null ? 60 : pool.getCooldownSeconds()) * 1000L);
        lease.setUpdatedTime(System.currentTimeMillis());
        leaseMapper.updateById(lease);
    }

    private void markLeaseActive(PortLease lease) {
        lease.setState("active");
        lease.setUpdatedTime(System.currentTimeMillis());
        leaseMapper.updateById(lease);
    }

    private PortPool accessiblePool(Long id, Integer userId) {
        PortPool pool = poolMapper.selectById(id);
        if (pool == null || pool.getStatus() == null || pool.getStatus() != 1) return null;
        if (isAdmin() || usableGrant(pool.getId(), userId) != null) return pool;
        return null;
    }

    private PortPoolGrant usableGrant(Long poolId, Integer userId) {
        if (userId == null) return null;
        return grantService.listGrants(userId).stream()
                .filter(item -> Objects.equals(item.getPoolId(), poolId)).findFirst().orElse(null);
    }

    private PortPoolGrant selectedGrant(Long grantId, Long poolId, Integer userId) {
        if (grantId != null) return grantService.usableGrant(grantId, userId, poolId);
        List<PortPoolGrant> matching = grantService.listGrants(userId).stream()
                .filter(item -> Objects.equals(item.getPoolId(), poolId)).toList();
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private Integer findAvailablePort(Long poolId, int start, int end, PortPoolGrant grant) {
        List<PortLease> leases = leaseMapper.selectList(new QueryWrapper<PortLease>().eq("pool_id", poolId));
        Map<Integer, Boolean> used = new HashMap<>();
        for (PortLease lease : leases) used.put(lease.getPort(), true);
        if (grant == null) {
            for (Integer grantedPort : grantService.grantedPorts(poolId)) used.put(grantedPort, true);
        }
        for (int port = start; port <= end; port++) {
            if (!used.containsKey(port)) return port;
        }
        return null;
    }

    private HomeProxyRoute enrich(HomeProxyRoute route, boolean includeSecret) {
        User owner = userMapper.selectById(route.getUserId());
        InternalConnector connector = connectorMapper.selectById(route.getConnectorId());
        PortPool ingress = poolMapper.selectById(route.getIngressPoolId());
        PortPool egress = poolMapper.selectById(route.getEgressPoolId());
        route.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
        route.setConnectorName(connector == null ? "接入端已删除" : connector.getName());
        route.setConnectorOnline(connector != null && WebSocketServer.isConnectorOnline(connector.getId()));
        route.setIngressPoolName(ingress == null ? "入口端口池已删除" : ingress.getName());
        route.setEgressPoolName(egress == null ? "出口端口池已删除" : egress.getName());
        route.setPublicHost(ingress == null ? null : ingress.getPublicHost());
        if (!includeSecret || !Objects.equals(route.getAuthEnabled(), 1)) {
            route.setAuthPassword(null);
        } else {
            route.setAuthPassword(decryptPassword(route.getAuthPassword()));
        }
        return route;
    }

    private String pendingDeleteReason(boolean connectorCleaned, boolean gatewayCleaned) {
        if (!connectorCleaned && !gatewayCleaned) return "家庭接入端和出口 VPS 离线，恢复连接后自动清理";
        if (!connectorCleaned) return "家庭接入端离线，恢复连接后自动清理";
        return "出口 VPS 离线，恢复连接后自动清理";
    }

    private String portCheckMessage(AgentPortCheckUtil.Result result) {
        if (result.getConflicts() == null || result.getConflicts().isEmpty()) return result.getMessage();
        return result.getMessage() + "（" + String.join("；", result.getConflicts()) + "）";
    }

    private String encryptPassword(String value) {
        return "enc:" + new AESCrypto(encryptionSecret + ":home-proxy").encrypt(value);
    }

    private String decryptPassword(String value) {
        if (StringUtils.isBlank(value) || !value.startsWith("enc:")) return value;
        try {
            return new AESCrypto(encryptionSecret + ":home-proxy").decryptString(value.substring(4));
        } catch (RuntimeException error) {
            log.warn("家庭代理密码解密失败: {}", error.getMessage());
            return null;
        }
    }

    private String poolAddress(PortPool pool) {
        String host = pool.getPublicHost();
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]:" + pool.getControlPort() : host + ":" + pool.getControlPort();
    }

    private String hostPort(String host, int port) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]:" + port : host + ":" + port;
    }

    private boolean waitForEndpoint(Node probeNode, String host, int port) {
        for (int attempt = 0; attempt < 8; attempt++) {
            JSONObject payload = new JSONObject();
            payload.put("ip", host);
            payload.put("port", port);
            payload.put("count", 1);
            payload.put("timeout", 1000);
            GostDto response = WebSocketServer.send_msg(probeNode.getId(), payload, "TcpPing");
            if (ok(response) && response.getData() != null) {
                JSONObject data = response.getData() instanceof JSONObject
                        ? (JSONObject) response.getData()
                        : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
                if (Boolean.TRUE.equals(data.getBoolean("success"))) return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void safeDeleteChains(Long connectorId, String... chains) {
        for (String chain : chains) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("chain", chain);
                WebSocketServer.sendConnectorMsg(connectorId, data, "DeleteChains");
            } catch (Exception ignored) {
            }
        }
    }

    private boolean ok(GostDto result) {
        return result != null && "OK".equalsIgnoreCase(result.getMsg());
    }

    private boolean containsNotFound(GostDto result) {
        return result != null && StringUtils.containsIgnoreCase(result.getMsg(), "not found");
    }

    private String message(GostDto result) {
        return result == null ? "Agent 无响应" : StringUtils.defaultIfBlank(result.getMsg(), "Agent 无响应");
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        StringBuilder result = new StringBuilder(bytes * 2);
        for (byte item : value) result.append(String.format("%02x", item));
        return result.toString();
    }

    private Integer currentUserId() {
        return JwtUtil.getUserIdFromToken();
    }

    private boolean isAdmin() {
        return Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
    }
}
