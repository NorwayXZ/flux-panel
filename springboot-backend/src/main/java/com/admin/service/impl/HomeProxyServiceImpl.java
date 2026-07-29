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
import java.net.Inet6Address;
import java.net.InetAddress;
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
    private static final String MIN_DIRECT_AGENT_VERSION = "2.21.0";
    private static final long IPV6_REFRESH_INTERVAL_MS = 5 * 60 * 1000L;
    private static final String IPV6_UNVERIFIED_PREFIX = "公网验证未完成：";
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

        String accessMode;
        try {
            accessMode = normalizeAccessMode(dto.getAccessMode());
        } catch (IllegalArgumentException error) {
            return R.err(error.getMessage());
        }
        if ("ipv6_direct".equals(accessMode)) {
            return createDirectIpv6(dto, connector, userId);
        }
        if (dto.getIngressPoolId() == null) return R.err("请选择公网入口端口池");

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
        route.setAccessMode("relay");
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
        if (waitForEndpoint(ingressNode, ingress.getPublicHost(), port).status != EndpointProbeStatus.REACHABLE) {
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

    private R createDirectIpv6(HomeProxyRouteCreateDto dto, InternalConnector connector, Integer userId) {
        if (!AgentVersionUtil.isAtLeast(connector.getVersion(), MIN_DIRECT_AGENT_VERSION)) {
            return R.err("IPv6 直连要求家庭 Agent " + MIN_DIRECT_AGENT_VERSION + " 或更高版本");
        }
        Integer directPort = dto.getDirectPort();
        if (directPort == null || directPort < 1024 || directPort > 65535) {
            return R.err("家庭 IPv6 直连端口必须在 1024-65535 之间");
        }
        Integer duplicate = routeMapper.selectCount(new QueryWrapper<HomeProxyRoute>()
                .eq("connector_id", connector.getId()).eq("access_mode", "ipv6_direct")
                .eq("direct_port", directPort).notIn("state", "deleted"));
        if (duplicate != null && duplicate > 0) return R.err("该家庭设备的直连端口已被其他代理使用");

        String directIpv6;
        try {
            directIpv6 = queryConnectorIpv6(connector.getId());
        } catch (RuntimeException error) {
            return R.err(error.getMessage());
        }
        String portError = checkConnectorPort(connector.getId(), directPort);
        if (portError != null) return R.err(portError);

        PortPool egress = accessiblePool(dto.getEgressPoolId(), userId);
        if (egress == null) return R.err("家庭出口 VPS 端口池不存在、已停用或无权使用");
        Node egressNode = nodeMapper.selectById(egress.getNodeId());
        if (egressNode == null || !WebSocketServer.isNodeOnline(egressNode.getId())) {
            return R.err("家庭出口 VPS 必须在线");
        }
        if (!AgentVersionUtil.isAtLeast(egressNode.getVersion(), MIN_AGENT_VERSION)) {
            return R.err("家庭出口 VPS Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }

        jdbcTemplate.queryForObject("SELECT id FROM service_publish_lock WHERE id=1 FOR UPDATE", Integer.class);
        PortPoolGrant egressGrant = isAdmin() ? null : selectedGrant(dto.getEgressGrantId(), egress.getId(), userId);
        if (!isAdmin() && egressGrant == null) return R.err("所选出口端口资源未分配给当前用户或已被收回");
        int egressStart = egressGrant == null ? egress.getStartPort() : egressGrant.getStartPort();
        int egressEnd = egressGrant == null ? egress.getEndPort() : egressGrant.getEndPort();
        Integer egressPort = findAvailablePort(egress.getId(), egressStart, egressEnd, egressGrant);
        if (egressPort == null) return R.err("家庭出口 VPS 端口池已没有可用端口");

        AgentPortCheckUtil.Result egressPortCheck = AgentPortCheckUtil.check(egressNode,
                List.of(new AgentPortCheckUtil.Check("tcp", egress.getBindIp(), egressPort)));
        if (!egressPortCheck.isAvailable()) {
            return R.err("家庭出口网关端口不可用：" + portCheckMessage(egressPortCheck));
        }

        boolean authEnabled = Boolean.TRUE.equals(dto.getAuthEnabled());
        String authUsername = authEnabled ? StringUtils.defaultIfBlank(dto.getAuthUsername(), "cloudnest") : null;
        String authPassword = authEnabled ? StringUtils.defaultIfBlank(dto.getAuthPassword(), randomHex(18)) : null;
        long now = System.currentTimeMillis();

        HomeProxyRoute route = new HomeProxyRoute();
        route.setUserId(userId);
        route.setName(dto.getName().trim());
        route.setConnectorId(connector.getId());
        route.setAccessMode("ipv6_direct");
        route.setIngressPoolId(null);
        route.setEgressPoolId(egress.getId());
        route.setPublicPort(directPort);
        route.setDirectIpv6(directIpv6);
        route.setDirectPort(directPort);
        route.setIpv6CheckedAt(now);
        route.setProxyType("socks5");
        route.setAuthEnabled(authEnabled ? 1 : 0);
        route.setAuthUsername(authUsername);
        route.setAuthPassword(authEnabled ? encryptPassword(authPassword) : null);
        route.setState("provisioning");
        route.setCreatedTime(now);
        route.setUpdatedTime(now);
        routeMapper.insert(route);

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
        route.setEgressLeaseId(egressLease.getId());
        route.setEgressGatewayPort(egressPort);
        routeMapper.updateById(route);

        String base = "home_proxy_" + route.getId();
        String egressChain = base + "_egress";
        String gatewayName = base + "_egress_gateway";
        String gatewayUsername = "cloudnest_" + randomHex(5);
        String gatewayPassword = randomHex(18);
        GostDto gatewayResult = GostUtil.AddHomeEgressGateway(egressNode.getId(), gatewayName,
                egress.getBindIp(), egressPort, gatewayUsername, gatewayPassword);
        if (!ok(gatewayResult)) {
            return failDirectProvision(route, egressLease, "创建出口 VPS 网关失败：" + message(gatewayResult));
        }
        GostDto egressResult = GostUtil.AddPublishingChain(connector.getId(), egressChain,
                hostPort(egress.getPublicHost(), egressPort), gatewayUsername, gatewayPassword);
        if (!ok(egressResult)) {
            GostUtil.DeletePublishingGateway(egressNode.getId(), gatewayName);
            return failDirectProvision(route, egressLease, "创建家庭出口链失败：" + message(egressResult));
        }
        GostDto serviceResult = GostUtil.AddDirectHomeProxyService(connector.getId(), base + "_service",
                egressChain, directPort, authEnabled, authUsername, authPassword);
        if (!ok(serviceResult)) {
            safeDeleteChains(connector.getId(), egressChain);
            GostUtil.DeletePublishingGateway(egressNode.getId(), gatewayName);
            return failDirectProvision(route, egressLease, "创建家庭 IPv6 SOCKS5 服务失败：" + message(serviceResult));
        }
        EndpointProbeResult probe = waitForIpv6Endpoint(egressNode, directIpv6, directPort);
        if (probe.status == EndpointProbeStatus.UNREACHABLE) {
            GostUtil.DeleteDirectHomeProxyService(connector.getId(), base + "_service", egressChain);
            GostUtil.DeletePublishingGateway(egressNode.getId(), gatewayName);
            return failDirectProvision(route, egressLease,
                    directIpv6FailureMessage(directPort, probe.detail));
        }

        route.setState("active");
        route.setLastError(probe.status == EndpointProbeStatus.PROBE_IPV6_UNSUPPORTED
                ? ipv6UnverifiedMessage(egressNode, directPort) : null);
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
        markLeaseActive(egressLease);
        return R.ok(enrich(route, true));
    }

    @Override
    public R list() {
        QueryWrapper<HomeProxyRoute> query = new QueryWrapper<HomeProxyRoute>()
                .notIn("state", List.of("deleted", "delete_pending")).orderByDesc("created_time");
        if (!isAdmin()) query.eq("user_id", currentUserId());
        List<HomeProxyRoute> routes = routeMapper.selectList(query);
        List<HomeProxyRoute> result = new ArrayList<>();
        for (HomeProxyRoute route : routes) result.add(enrich(route, true));
        return R.ok(result);
    }

    @Override
    public R refreshIpv6(Long id) {
        HomeProxyRoute route = ownedRoute(id);
        if (route == null) return R.err("家庭代理不存在或无权访问");
        if (!"ipv6_direct".equals(route.getAccessMode())) return R.err("该代理不是 IPv6 直连模式");
        try {
            String address = refreshDirectIpv6(route);
            return R.ok(Map.of("address", address, "checkedAt", route.getIpv6CheckedAt()));
        } catch (RuntimeException error) {
            return R.err(error.getMessage());
        }
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
            GostDto result = deleteConnectorRuntime(route, connector.getId());
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
        return R.ok();
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
                    GostDto result = deleteConnectorRuntime(route, connector.getId());
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

    @Override
    public void refreshDirectIpv6Addresses() {
        long cutoff = System.currentTimeMillis() - IPV6_REFRESH_INTERVAL_MS;
        List<HomeProxyRoute> routes = routeMapper.selectList(new QueryWrapper<HomeProxyRoute>()
                .eq("access_mode", "ipv6_direct").eq("state", "active")
                .and(query -> query.isNull("ipv6_checked_at").or().lt("ipv6_checked_at", cutoff))
                .orderByAsc("ipv6_checked_at").last("LIMIT 20"));
        HomeProxyRoute route = routes.stream()
                .filter(item -> WebSocketServer.isConnectorOnline(item.getConnectorId()))
                .findFirst().orElse(null);
        if (route == null) return;
        try {
            refreshDirectIpv6(route);
        } catch (RuntimeException error) {
            route.setIpv6CheckedAt(System.currentTimeMillis());
            route.setLastError(StringUtils.abbreviate("IPv6 自动检测失败：" + error.getMessage(), 500));
            route.setUpdatedTime(System.currentTimeMillis());
            routeMapper.updateById(route);
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

    private R failDirectProvision(HomeProxyRoute route, PortLease egressLease, String error) {
        if (egressLease != null && egressLease.getId() != null) leaseMapper.deleteById(egressLease.getId());
        route.setEgressLeaseId(null);
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
        if (lease == null) return;
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
        PortPool ingress = route.getIngressPoolId() == null ? null : poolMapper.selectById(route.getIngressPoolId());
        PortPool egress = poolMapper.selectById(route.getEgressPoolId());
        if (StringUtils.isBlank(route.getAccessMode())) route.setAccessMode("relay");
        route.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
        route.setConnectorName(connector == null ? "接入端已删除" : connector.getName());
        route.setConnectorOnline(connector != null && WebSocketServer.isConnectorOnline(connector.getId()));
        route.setIngressPoolName("ipv6_direct".equals(route.getAccessMode())
                ? "家庭 IPv6 直连" : ingress == null ? "入口端口池已删除" : ingress.getName());
        route.setEgressPoolName(egress == null ? "出口端口池已删除" : egress.getName());
        route.setPublicHost("ipv6_direct".equals(route.getAccessMode())
                ? route.getDirectIpv6() : ingress == null ? null : ingress.getPublicHost());
        if (!includeSecret || !Objects.equals(route.getAuthEnabled(), 1)) {
            route.setAuthPassword(null);
        } else {
            route.setAuthPassword(decryptPassword(route.getAuthPassword()));
        }
        return route;
    }

    private String normalizeAccessMode(String value) {
        String mode = StringUtils.defaultIfBlank(value, "relay").trim().toLowerCase();
        if (!List.of("relay", "ipv6_direct").contains(mode)) {
            throw new IllegalArgumentException("家庭接入方式不受支持");
        }
        return mode;
    }

    private HomeProxyRoute ownedRoute(Long id) {
        HomeProxyRoute route = routeMapper.selectById(id);
        if (route == null || (!isAdmin() && !Objects.equals(route.getUserId(), currentUserId()))) return null;
        return route;
    }

    private String queryConnectorIpv6(Long connectorId) {
        GostDto response = WebSocketServer.sendConnectorMsg(connectorId,
                Map.of("family", "ipv6"), "PublicIpQuery");
        if (!ok(response) || response.getData() == null) {
            throw new IllegalStateException("家庭 Agent 未检测到可用公网 IPv6：" + message(response));
        }
        JSONObject data = response.getData() instanceof JSONObject
                ? (JSONObject) response.getData()
                : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
        String address = StringUtils.trimToEmpty(data.getString("address"));
        try {
            InetAddress parsed = InetAddress.getByName(address);
            String lower = address.toLowerCase();
            if (!(parsed instanceof Inet6Address) || parsed.isAnyLocalAddress() || parsed.isLoopbackAddress()
                    || parsed.isLinkLocalAddress() || parsed.isSiteLocalAddress() || parsed.isMulticastAddress()
                    || lower.startsWith("fc") || lower.startsWith("fd")) {
                throw new IllegalArgumentException();
            }
            return parsed.getHostAddress();
        } catch (Exception error) {
            throw new IllegalStateException("家庭 Agent 返回的不是公网 IPv6 地址");
        }
    }

    private String checkConnectorPort(Long connectorId, int port) {
        JSONObject check = new JSONObject();
        check.put("network", "tcp");
        check.put("host", "::");
        check.put("port", port);
        com.alibaba.fastjson.JSONArray checks = new com.alibaba.fastjson.JSONArray();
        checks.add(check);
        JSONObject payload = new JSONObject();
        payload.put("checks", checks);
        GostDto response = WebSocketServer.sendConnectorMsg(connectorId, payload, "PortCheck");
        if (!ok(response) || response.getData() == null) {
            return "家庭设备端口检查失败：" + message(response);
        }
        JSONObject data = response.getData() instanceof JSONObject
                ? (JSONObject) response.getData()
                : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
        if (Boolean.TRUE.equals(data.getBoolean("available"))) return null;
        String detail = null;
        if (data.getJSONArray("results") != null && !data.getJSONArray("results").isEmpty()) {
            detail = data.getJSONArray("results").getJSONObject(0).getString("error");
        }
        if (StringUtils.containsIgnoreCase(detail, "address already in use")
                || StringUtils.containsIgnoreCase(detail, "only one usage")) {
            return "家庭设备 TCP " + port + " 已被其他程序占用";
        }
        return "家庭设备无法监听 IPv6 TCP " + port + "：" + StringUtils.defaultIfBlank(detail, "Agent 未返回原因");
    }

    private String refreshDirectIpv6(HomeProxyRoute route) {
        if (!WebSocketServer.isConnectorOnline(route.getConnectorId())) throw new IllegalStateException("家庭接入端离线");
        String address = queryConnectorIpv6(route.getConnectorId());
        PortPool egressPool = poolMapper.selectById(route.getEgressPoolId());
        Node egressNode = egressPool == null ? null : nodeMapper.selectById(egressPool.getNodeId());
        if (egressNode == null || !WebSocketServer.isNodeOnline(egressNode.getId())) {
            throw new IllegalStateException("家庭出口 VPS 离线，无法验证新的 IPv6 地址");
        }
        if (route.getDirectPort() == null) {
            throw new IllegalStateException("家庭 IPv6 直连端口缺失");
        }
        EndpointProbeResult probe = waitForIpv6Endpoint(egressNode, address, route.getDirectPort());
        if (probe.status == EndpointProbeStatus.UNREACHABLE) {
            throw new IllegalStateException(directIpv6FailureMessage(route.getDirectPort(), probe.detail));
        }
        long now = System.currentTimeMillis();
        route.setDirectIpv6(address);
        route.setPublicPort(route.getDirectPort());
        route.setIpv6CheckedAt(now);
        route.setLastError(probe.status == EndpointProbeStatus.PROBE_IPV6_UNSUPPORTED
                ? ipv6UnverifiedMessage(egressNode, route.getDirectPort()) : null);
        route.setUpdatedTime(now);
        routeMapper.updateById(route);
        return address;
    }

    private GostDto deleteConnectorRuntime(HomeProxyRoute route, Long connectorId) {
        String base = "home_proxy_" + route.getId();
        if ("ipv6_direct".equals(route.getAccessMode())) {
            return GostUtil.DeleteDirectHomeProxyService(connectorId, base + "_service", base + "_egress");
        }
        return GostUtil.DeleteHomeProxyService(connectorId, base + "_service", base + "_ingress", base + "_egress");
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

    private EndpointProbeResult waitForEndpoint(Node probeNode, String host, int port) {
        String lastDetail = null;
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
                if (Boolean.TRUE.equals(data.getBoolean("success"))) {
                    return new EndpointProbeResult(EndpointProbeStatus.REACHABLE, null);
                }
                lastDetail = StringUtils.defaultIfBlank(data.getString("errorMessage"), data.getString("error"));
                if (isIpv6UnsupportedProbeError(lastDetail)) {
                    return new EndpointProbeResult(EndpointProbeStatus.PROBE_IPV6_UNSUPPORTED, lastDetail);
                }
            } else {
                lastDetail = message(response);
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new EndpointProbeResult(EndpointProbeStatus.UNREACHABLE, "探测被中断");
            }
        }
        return new EndpointProbeResult(EndpointProbeStatus.UNREACHABLE, lastDetail);
    }

    private EndpointProbeResult waitForIpv6Endpoint(Node probeNode, String host, int port) {
        Ipv6ProbeCapability capability = queryProbeNodeIpv6Capability(probeNode);
        if (capability == Ipv6ProbeCapability.UNAVAILABLE) {
            return new EndpointProbeResult(EndpointProbeStatus.PROBE_IPV6_UNSUPPORTED,
                    "验证节点没有可用公网 IPv6");
        }
        return waitForEndpoint(probeNode, host, port);
    }

    private Ipv6ProbeCapability queryProbeNodeIpv6Capability(Node probeNode) {
        if (!AgentVersionUtil.isAtLeast(probeNode.getVersion(), MIN_DIRECT_AGENT_VERSION)) {
            return Ipv6ProbeCapability.UNKNOWN;
        }
        GostDto response = WebSocketServer.send_msg(probeNode.getId(),
                Map.of("family", "ipv6"), "PublicIpQuery", 15);
        if (ok(response) && response.getData() != null) {
            JSONObject data = response.getData() instanceof JSONObject
                    ? (JSONObject) response.getData()
                    : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
            String address = StringUtils.trimToEmpty(data.getString("address"));
            try {
                return InetAddress.getByName(address) instanceof Inet6Address
                        ? Ipv6ProbeCapability.AVAILABLE : Ipv6ProbeCapability.UNKNOWN;
            } catch (Exception ignored) {
                return Ipv6ProbeCapability.UNKNOWN;
            }
        }
        String detail = message(response).toLowerCase();
        if (detail.contains("no globally routable ipv6")
                || detail.contains("no such host")
                || isIpv6UnsupportedProbeError(detail)) {
            return Ipv6ProbeCapability.UNAVAILABLE;
        }
        return Ipv6ProbeCapability.UNKNOWN;
    }

    static boolean isIpv6UnsupportedProbeError(String detail) {
        if (StringUtils.isBlank(detail)) return false;
        String normalized = detail.toLowerCase();
        return normalized.contains("network is unreachable")
                || normalized.contains("no route to host")
                || normalized.contains("address family not supported")
                || normalized.contains("protocol not supported")
                || normalized.contains("cannot assign requested address");
    }

    private String ipv6UnverifiedMessage(Node probeNode, int port) {
        return IPV6_UNVERIFIED_PREFIX + "出口 VPS " + probeNode.getName()
                + " 没有 IPv6 路由，家庭监听已保留；请从实际 IPv6 网络测试 TCP " + port;
    }

    private String directIpv6FailureMessage(int port, String detail) {
        String message = "家庭 IPv6 端口无法从公网访问，请在家庭路由器和系统防火墙放行 TCP " + port;
        return StringUtils.isBlank(detail) ? message : message + "（探测结果：" + StringUtils.abbreviate(detail, 160) + "）";
    }

    private enum EndpointProbeStatus {
        REACHABLE,
        UNREACHABLE,
        PROBE_IPV6_UNSUPPORTED
    }

    private enum Ipv6ProbeCapability {
        AVAILABLE,
        UNAVAILABLE,
        UNKNOWN
    }

    private static final class EndpointProbeResult {
        private final EndpointProbeStatus status;
        private final String detail;

        private EndpointProbeResult(EndpointProbeStatus status, String detail) {
            this.status = status;
            this.detail = detail;
        }
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
