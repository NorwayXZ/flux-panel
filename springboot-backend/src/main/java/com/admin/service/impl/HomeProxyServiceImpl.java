package com.admin.service.impl;

import com.admin.common.dto.HomeProxyRouteCreateDto;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.TunnelPathNodeDto;
import com.admin.common.dto.PortLedgerEntryDto;
import com.admin.common.dto.PortLedgerQueryDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.TunnelRouteUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.HomeProxyGateway;
import com.admin.entity.HomeProxyRoute;
import com.admin.entity.InternalConnector;
import com.admin.entity.Node;
import com.admin.entity.PortLease;
import com.admin.entity.PortPool;
import com.admin.entity.PortPoolGrant;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.HomeProxyGatewayMapper;
import com.admin.mapper.HomeProxyRouteMapper;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortLeaseMapper;
import com.admin.mapper.PortPoolMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.DynamicDnsService;
import com.admin.service.HomeProxyService;
import com.admin.service.PortPoolGrantService;
import com.admin.service.PortLedgerService;
import com.admin.service.UserQuotaService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class HomeProxyServiceImpl implements HomeProxyService {
    private static final String MIN_AGENT_VERSION = "2.7.0";
    private static final String MIN_DIRECT_AGENT_VERSION = "2.21.0";
    private static final long IPV6_REFRESH_INTERVAL_MS = 5 * 60 * 1000L;
    private static final String IPV6_UNVERIFIED_PREFIX = "公网验证未完成：";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource private HomeProxyRouteMapper routeMapper;
    @Resource private HomeProxyGatewayMapper gatewayMapper;
    @Resource private InternalConnectorMapper connectorMapper;
    @Resource private PortPoolMapper poolMapper;
    @Resource private TunnelMapper tunnelMapper;
    @Resource private PortLeaseMapper leaseMapper;
    @Resource private NodeMapper nodeMapper;
    @Resource private UserMapper userMapper;
    @Resource private PortPoolGrantService grantService;
    @Resource private PortLedgerService portLedgerService;
    @Resource private UserQuotaService userQuotaService;
    @Resource private DynamicDnsService dynamicDnsService;
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
        String egressMode;
        try {
            egressMode = normalizeEgressMode(dto.getEgressMode());
        } catch (IllegalArgumentException error) {
            return R.err(error.getMessage());
        }
        if ("ipv6_direct".equals(accessMode) || "ipv4_direct".equals(accessMode)) {
            return createDirect(dto, connector, userId, accessMode, egressMode);
        }
        if (dto.getIngressPoolId() == null) return R.err("请选择公网入口端口池");

        PortPool ingress = accessiblePool(dto.getIngressPoolId(), userId);
        PortPool egress = "single".equals(egressMode) ? accessiblePool(dto.getEgressPoolId(), userId) : null;
        if (ingress == null) return R.err("公网入口端口池不存在、已停用或无权使用");
        if ("single".equals(egressMode) && egress == null) return R.err("请选择可用的家庭出口 VPS 端口池");
        Node ingressNode = nodeMapper.selectById(ingress.getNodeId());
        Node egressNode = egress == null ? null : nodeMapper.selectById(egress.getNodeId());
        if (ingressNode == null) return R.err("公网入口节点不存在");
        if (!WebSocketServer.isNodeOnline(ingressNode.getId())) return R.err("公网入口节点必须在线");
        if (!AgentVersionUtil.isAtLeast(ingressNode.getVersion(), MIN_AGENT_VERSION)) {
            return R.err("公网入口节点 Agent 需要升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }
        Tunnel egressTunnel;
        try {
            egressTunnel = resolveEgressTunnel(dto.getEgressTunnelId(), egressMode, egressNode, userId);
        } catch (IllegalStateException error) {
            return R.err(error.getMessage());
        }
        if (egressTunnel != null) egressNode = tunnelFinalNode(egressTunnel);

        jdbcTemplate.queryForObject("SELECT id FROM service_publish_lock WHERE id=1 FOR UPDATE", Integer.class);
        PortPoolGrant ingressGrant = isAdmin() ? null : selectedGrant(dto.getIngressGrantId(), ingress.getId(), userId);
        PortPoolGrant egressGrant = isAdmin() || egress == null ? null : selectedGrant(dto.getEgressGrantId(), egress.getId(), userId);
        if (!isAdmin() && (ingressGrant == null || (egress != null && egressGrant == null))) {
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
        route.setEgressPoolId(egress == null ? null : egress.getId());
        route.setEgressMode(egressMode);
        route.setEgressTunnelId(egressTunnel == null ? null : egressTunnel.getId());
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

        List<GatewayRuntime> gateways;
        try {
            gateways = allocateGatewayPath(route, buildGatewayPlans(egressMode, egressTunnel, egress, egressGrant, userId));
        } catch (IllegalStateException error) {
            leaseMapper.deleteById(lease.getId());
            routeMapper.deleteById(route.getId());
            return R.err(error.getMessage());
        }
        GatewayRuntime finalGateway = gateways.get(gateways.size() - 1);
        route.setLeaseId(lease.getId());
        route.setEgressLeaseId(finalGateway.lease() == null ? null : finalGateway.lease().getId());
        route.setEgressGatewayPort(finalGateway.gateway().getGatewayPort());
        routeMapper.updateById(route);

        AgentPortCheckUtil.Result ingressPortCheck = AgentPortCheckUtil.check(ingressNode,
                List.of(new AgentPortCheckUtil.Check("tcp", ingress.getBindIp(), port)));
        if (!ingressPortCheck.isAvailable()) {
            return failProvision(route, lease, gateways,
                    "公网入口端口不可用：" + portCheckMessage(ingressPortCheck));
        }
        String gatewayPortError = checkGatewayPorts(gateways);
        if (gatewayPortError != null) {
            return failProvision(route, lease, gateways, gatewayPortError);
        }

        String base = "home_proxy_" + route.getId();
        String ingressChain = base + "_ingress";
        String egressChain = base + "_egress";
        String ingressAddress = poolAddress(ingress);
        String gatewayError = provisionGateways(gateways);
        if (gatewayError != null) {
            return failProvision(route, lease, gateways, gatewayError);
        }
        GostDto ingressResult = GostUtil.AddPublishingChain(connector.getId(), ingressChain, ingressAddress,
                ingress.getAuthUsername(), ingress.getAuthPassword());
        if (!ok(ingressResult)) {
            return failProvision(route, lease, gateways, "创建家庭入口反向链失败：" + message(ingressResult));
        }
        GostDto egressResult = GostUtil.AddPublishingChain(connector.getId(), egressChain, publishingHops(gateways));
        if (!ok(egressResult)) {
            safeDeleteChains(connector.getId(), ingressChain);
            return failProvision(route, lease, gateways, "创建家庭出口链失败：" + message(egressResult));
        }
        GostDto serviceResult = GostUtil.AddHomeProxyService(connector.getId(), base + "_service", ingressChain,
                egressChain, ingress.getBindIp(), port, authEnabled, authUsername, authPassword);
        if (!ok(serviceResult)) {
            safeDeleteChains(connector.getId(), ingressChain, egressChain);
            return failProvision(route, lease, gateways, "创建家庭 SOCKS5 服务失败：" + message(serviceResult));
        }
        if (waitForEndpoint(ingressNode, ingress.getPublicHost(), port).status != EndpointProbeStatus.REACHABLE) {
            GostUtil.DeleteHomeProxyService(connector.getId(), base + "_service", ingressChain, egressChain);
            return failProvision(route, lease, gateways, "公网入口端口未能在 12 秒内就绪，请检查节点防火墙和端口范围");
        }

        route.setState("active");
        route.setLastError(null);
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
        markLeaseActive(lease);
        gateways.forEach(item -> markLeaseActive(item.lease()));
        return R.ok(enrich(route, true));
    }

    private R createDirect(HomeProxyRouteCreateDto dto, InternalConnector connector, Integer userId,
                           String accessMode, String egressMode) {
        boolean ipv6 = "ipv6_direct".equals(accessMode);
        String family = ipv6 ? "ipv6" : "ipv4";
        String familyLabel = ipv6 ? "IPv6" : "IPv4";
        if (!AgentVersionUtil.isAtLeast(connector.getVersion(), MIN_DIRECT_AGENT_VERSION)) {
            return R.err(familyLabel + " 直连要求家庭 Agent " + MIN_DIRECT_AGENT_VERSION + " 或更高版本");
        }
        Integer directPort = dto.getDirectPort();
        if (directPort == null || directPort < 1024 || directPort > 65535) {
            return R.err("家庭 " + familyLabel + " 直连端口必须在 1024-65535 之间");
        }
        Integer duplicate = routeMapper.selectCount(new QueryWrapper<HomeProxyRoute>()
                .eq("connector_id", connector.getId()).eq("access_mode", accessMode)
                .eq("direct_port", directPort).notIn("state", "deleted"));
        if (duplicate != null && duplicate > 0) return R.err("该家庭设备的直连端口已被其他代理使用");

        String directAddress;
        try {
            directAddress = queryConnectorPublicIp(connector.getId(), family);
        } catch (RuntimeException error) {
            return R.err(error.getMessage());
        }
        String portError = checkConnectorPort(connector.getId(), ipv6 ? "::" : "0.0.0.0", directPort, familyLabel);
        if (portError != null) return R.err(portError);
        Map<String, Object> dynamicDnsRule = null;
        if (dto.getDynamicDnsRuleId() != null) {
            try {
                dynamicDnsRule = validateDynamicDnsBinding(dto.getDynamicDnsRuleId(), connector.getId(), family);
            } catch (RuntimeException error) {
                return R.err(error.getMessage());
            }
        }

        PortPool egress = "single".equals(egressMode) ? accessiblePool(dto.getEgressPoolId(), userId) : null;
        if ("single".equals(egressMode) && egress == null) return R.err("请选择可用的家庭出口 VPS 端口池");
        Node egressNode = egress == null ? null : nodeMapper.selectById(egress.getNodeId());
        Tunnel egressTunnel;
        try {
            egressTunnel = resolveEgressTunnel(dto.getEgressTunnelId(), egressMode, egressNode, userId);
        } catch (IllegalStateException error) {
            return R.err(error.getMessage());
        }
        if (egressTunnel != null) egressNode = tunnelFinalNode(egressTunnel);

        jdbcTemplate.queryForObject("SELECT id FROM service_publish_lock WHERE id=1 FOR UPDATE", Integer.class);
        PortPoolGrant egressGrant = isAdmin() || egress == null ? null : selectedGrant(dto.getEgressGrantId(), egress.getId(), userId);
        if (!isAdmin() && egress != null && egressGrant == null) return R.err("所选出口端口资源未分配给当前用户或已被收回");
        boolean authEnabled = Boolean.TRUE.equals(dto.getAuthEnabled());
        String authUsername = authEnabled ? StringUtils.defaultIfBlank(dto.getAuthUsername(), "cloudnest") : null;
        String authPassword = authEnabled ? StringUtils.defaultIfBlank(dto.getAuthPassword(), randomHex(18)) : null;
        long now = System.currentTimeMillis();

        HomeProxyRoute route = new HomeProxyRoute();
        route.setUserId(userId);
        route.setName(dto.getName().trim());
        route.setConnectorId(connector.getId());
        route.setAccessMode(accessMode);
        route.setIngressPoolId(null);
        route.setEgressPoolId(egress == null ? null : egress.getId());
        route.setEgressMode(egressMode);
        route.setEgressTunnelId(egressTunnel == null ? null : egressTunnel.getId());
        route.setPublicPort(directPort);
        route.setDirectIpv6(ipv6 ? directAddress : null);
        route.setDirectIpv4(ipv6 ? null : directAddress);
        route.setDirectPort(directPort);
        route.setIpv6CheckedAt(ipv6 ? now : null);
        route.setIpCheckedAt(now);
        route.setDynamicDnsRuleId(dynamicDnsRule == null ? null : number(dynamicDnsRule.get("id")));
        route.setPublicDomain(dynamicDnsRule == null ? null : Objects.toString(dynamicDnsRule.get("record_name"), null));
        route.setProxyType("socks5");
        route.setAuthEnabled(authEnabled ? 1 : 0);
        route.setAuthUsername(authUsername);
        route.setAuthPassword(authEnabled ? encryptPassword(authPassword) : null);
        route.setState("provisioning");
        route.setCreatedTime(now);
        route.setUpdatedTime(now);
        routeMapper.insert(route);

        List<GatewayRuntime> gateways;
        try {
            gateways = allocateGatewayPath(route, buildGatewayPlans(egressMode, egressTunnel, egress, egressGrant, userId));
        } catch (IllegalStateException error) {
            routeMapper.deleteById(route.getId());
            return R.err(error.getMessage());
        }
        GatewayRuntime finalGateway = gateways.get(gateways.size() - 1);
        route.setEgressLeaseId(finalGateway.lease() == null ? null : finalGateway.lease().getId());
        route.setEgressGatewayPort(finalGateway.gateway().getGatewayPort());
        routeMapper.updateById(route);

        String gatewayPortError = checkGatewayPorts(gateways);
        if (gatewayPortError != null) return failDirectProvision(route, gateways, gatewayPortError);

        String base = "home_proxy_" + route.getId();
        String egressChain = base + "_egress";
        String gatewayError = provisionGateways(gateways);
        if (gatewayError != null) return failDirectProvision(route, gateways, gatewayError);
        GostDto egressResult = GostUtil.AddPublishingChain(connector.getId(), egressChain, publishingHops(gateways));
        if (!ok(egressResult)) {
            return failDirectProvision(route, gateways, "创建家庭出口链失败：" + message(egressResult));
        }
        GostDto serviceResult = GostUtil.AddDirectHomeProxyService(connector.getId(), base + "_service",
                egressChain, ipv6 ? "::" : "0.0.0.0", directPort, authEnabled, authUsername, authPassword);
        if (!ok(serviceResult)) {
            safeDeleteChains(connector.getId(), egressChain);
            return failDirectProvision(route, gateways, "创建家庭 " + familyLabel + " SOCKS5 服务失败：" + message(serviceResult));
        }
        EndpointProbeResult probe = ipv6
                ? waitForIpv6Endpoint(egressNode, directAddress, directPort)
                : waitForEndpoint(egressNode, directAddress, directPort);
        if (probe.status == EndpointProbeStatus.UNREACHABLE) {
            GostUtil.DeleteDirectHomeProxyService(connector.getId(), base + "_service", egressChain);
            return failDirectProvision(route, gateways,
                    directFailureMessage(familyLabel, directPort, probe.detail));
        }

        route.setState("active");
        route.setLastError(ipv6 && probe.status == EndpointProbeStatus.PROBE_IPV6_UNSUPPORTED
                ? ipv6UnverifiedMessage(egressNode, directPort) : null);
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
        gateways.forEach(item -> markLeaseActive(item.lease()));
        syncDynamicDnsIfBound(route);
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
        if (!isDirectMode(route.getAccessMode())) return R.err("该代理不是公网直连模式");
        try {
            String address = refreshDirectAddress(route);
            return R.ok(Map.of("address", address, "checkedAt", route.getIpCheckedAt() == null ? route.getIpv6CheckedAt() : route.getIpCheckedAt(),
                    "family", "ipv6_direct".equals(route.getAccessMode()) ? "ipv6" : "ipv4"));
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
        boolean gatewayCleaned = cleanupStoredGateways(route);
        PortLease lease = route.getLeaseId() == null ? null : leaseMapper.selectById(route.getLeaseId());
        List<PortLease> gatewayLeases = storedGatewayLeases(route);
        if (connectorCleaned && gatewayCleaned) {
            markDeleted(route, lease, gatewayLeases);
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
                boolean connectorCleaned = connector == null;
                if (connector != null && WebSocketServer.isConnectorOnline(connector.getId())) {
                    GostDto result = deleteConnectorRuntime(route, connector.getId());
                    connectorCleaned = ok(result) || containsNotFound(result);
                }
                boolean gatewayCleaned = cleanupStoredGateways(route);
                if (connectorCleaned && gatewayCleaned) {
                    markDeleted(route,
                            route.getLeaseId() == null ? null : leaseMapper.selectById(route.getLeaseId()),
                            storedGatewayLeases(route));
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
                .in("access_mode", List.of("ipv6_direct", "ipv4_direct")).eq("state", "active")
                .and(query -> query.isNull("ip_checked_at").or().lt("ip_checked_at", cutoff))
                .orderByAsc("ip_checked_at").last("LIMIT 20"));
        HomeProxyRoute route = routes.stream()
                .filter(item -> WebSocketServer.isConnectorOnline(item.getConnectorId()))
                .findFirst().orElse(null);
        if (route == null) return;
        try {
            refreshDirectAddress(route);
        } catch (RuntimeException error) {
            route.setIpCheckedAt(System.currentTimeMillis());
            route.setLastError(StringUtils.abbreviate("公网地址自动检测失败：" + error.getMessage(), 500));
            route.setUpdatedTime(System.currentTimeMillis());
            routeMapper.updateById(route);
        }
    }

    private Tunnel resolveEgressTunnel(Long tunnelId, String egressMode, Node finalNode, Integer userId) {
        if ("single".equals(egressMode)) {
            if (finalNode == null) throw new IllegalStateException("家庭出口 VPS 不存在");
            validateEgressNode(finalNode);
            return null;
        }
        if (tunnelId == null) throw new IllegalStateException("请选择家庭出口隧道");
        Tunnel tunnel = tunnelMapper.selectById(tunnelId);
        if (tunnel == null || !Objects.equals(tunnel.getType(), 2) || !Objects.equals(tunnel.getStatus(), 1)) {
            throw new IllegalStateException("家庭出口隧道不存在或已停用");
        }
        List<Long> path = TunnelRouteUtil.parseNodePath(tunnel);
        if (path.size() < 2) throw new IllegalStateException("家庭出口隧道至少需要两个节点");
        if (!isAdmin()) {
            R quota = userQuotaService.checkTunnelQuota(userId, tunnel, null);
            if (quota.getCode() != 0) throw new IllegalStateException(quota.getMsg());
        }
        for (Long nodeId : path) {
            Node node = nodeMapper.selectById(nodeId);
            if (node == null) throw new IllegalStateException("出口隧道包含已删除节点：" + nodeId);
            validateEgressNode(node);
        }
        return tunnel;
    }

    private Node tunnelFinalNode(Tunnel tunnel) {
        List<Long> path = TunnelRouteUtil.parseNodePath(tunnel);
        Node node = path.isEmpty() ? null : nodeMapper.selectById(path.get(path.size() - 1));
        if (node == null) throw new IllegalStateException("出口隧道的落地节点不存在");
        return node;
    }

    private void validateEgressNode(Node node) {
        if (!WebSocketServer.isNodeOnline(node.getId())) {
            throw new IllegalStateException("出口路径节点离线：" + node.getName());
        }
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            throw new IllegalStateException("出口路径节点 " + node.getName() + " 的 Agent 需要升级到 "
                    + MIN_AGENT_VERSION + " 或更高版本");
        }
    }

    private List<GatewayPlan> buildGatewayPlans(String egressMode, Tunnel tunnel, PortPool finalPool,
                                                 PortPoolGrant finalGrant, Integer userId) {
        List<Long> nodePath = "tunnel".equals(egressMode)
                ? TunnelRouteUtil.parseNodePath(tunnel)
                : List.of(finalPool.getNodeId());
        List<GatewayPlan> plans = new ArrayList<>();
        for (Long nodeId : nodePath) {
            Node node = nodeMapper.selectById(nodeId);
            if ("tunnel".equals(egressMode)) {
                plans.add(new GatewayPlan(node, null, null));
            } else {
                plans.add(new GatewayPlan(node, finalPool, finalGrant));
            }
        }
        return plans;
    }

    private List<GatewayRuntime> allocateGatewayPath(HomeProxyRoute route, List<GatewayPlan> plans) {
        List<GatewayRuntime> result = new ArrayList<>();
        try {
            for (int index = 0; index < plans.size(); index++) {
                GatewayPlan plan = plans.get(index);
                PortPool pool = plan.pool();
                PortPoolGrant grant = plan.grant();
                Integer port;
                if (pool == null) {
                    port = findAvailableNodePort(plan.node());
                } else {
                    int start = grant == null ? pool.getStartPort() : grant.getStartPort();
                    int end = grant == null ? pool.getEndPort() : grant.getEndPort();
                    port = findAvailablePort(pool.getId(), start, end, grant);
                }
                if (port == null) throw new IllegalStateException(plan.node().getName() + " 的端口资源已用尽");

                long now = System.currentTimeMillis();
                PortLease lease = null;
                if (pool != null) {
                    lease = new PortLease();
                    lease.setPoolId(pool.getId());
                    lease.setGrantId(grant == null ? null : grant.getId());
                    lease.setUserId(route.getUserId());
                    lease.setPort(port);
                    lease.setProtocol("tcp");
                    lease.setState("reserved");
                    lease.setCreatedTime(now);
                    lease.setUpdatedTime(now);
                    leaseMapper.insert(lease);
                }

                String username = "cloudnest_" + randomHex(5);
                String password = randomHex(18);
                HomeProxyGateway gateway = new HomeProxyGateway();
                gateway.setRouteId(route.getId());
                gateway.setSequenceNo(index + 1);
                gateway.setTunnelId(route.getEgressTunnelId());
                gateway.setNodeId(plan.node().getId());
                gateway.setPoolId(pool == null ? null : pool.getId());
                gateway.setGrantId(grant == null ? null : grant.getId());
                gateway.setLeaseId(lease == null ? null : lease.getId());
                gateway.setGatewayPort(port);
                gateway.setGatewayName("tunnel".equals(route.getEgressMode())
                        ? "home_proxy_" + route.getId() + "_egress_gateway_" + (index + 1)
                        : "home_proxy_" + route.getId() + "_egress_gateway");
                gateway.setAuthUsername(username);
                gateway.setAuthPassword(encryptGatewayPassword(password));
                gateway.setCreatedTime(now);
                gatewayMapper.insert(gateway);
                result.add(new GatewayRuntime(gateway, lease, plan.node(), pool, username, password));
            }
            return result;
        } catch (RuntimeException error) {
            discardGatewayAllocations(route.getId(), result);
            throw error;
        }
    }

    private String checkGatewayPorts(List<GatewayRuntime> gateways) {
        for (GatewayRuntime item : gateways) {
            AgentPortCheckUtil.Result result = AgentPortCheckUtil.check(item.node(),
                    List.of(new AgentPortCheckUtil.Check("tcp", item.pool() == null ? "" : item.pool().getBindIp(), item.gateway().getGatewayPort())));
            if (!result.isAvailable()) {
                return item.node().getName() + " 出口网关端口不可用：" + portCheckMessage(result);
            }
        }
        return null;
    }

    private String provisionGateways(List<GatewayRuntime> gateways) {
        for (GatewayRuntime item : gateways) {
            GostDto result = GostUtil.AddHomeEgressGateway(item.node().getId(), item.gateway().getGatewayName(),
                    item.pool() == null ? "" : item.pool().getBindIp(), item.gateway().getGatewayPort(), item.username(), item.password());
            if (!ok(result)) {
                return "创建 " + item.node().getName() + " 出口网关失败：" + message(result);
            }
        }
        return null;
    }

    private List<GostUtil.PublishingProxyHop> publishingHops(List<GatewayRuntime> gateways) {
        return gateways.stream().map(item -> new GostUtil.PublishingProxyHop(
                hostPort(item.pool() == null ? item.node().getServerIp() : item.pool().getPublicHost(), item.gateway().getGatewayPort()),
                item.username(), item.password())).collect(Collectors.toList());
    }

    private R failProvision(HomeProxyRoute route, PortLease lease, List<GatewayRuntime> gateways, String error) {
        cleanupProvisionedGateways(gateways);
        discardGatewayAllocations(route.getId(), gateways);
        if (lease != null && lease.getId() != null) leaseMapper.deleteById(lease.getId());
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

    private R failDirectProvision(HomeProxyRoute route, List<GatewayRuntime> gateways, String error) {
        cleanupProvisionedGateways(gateways);
        discardGatewayAllocations(route.getId(), gateways);
        route.setEgressLeaseId(null);
        route.setEgressGatewayPort(null);
        route.setState("error");
        route.setLastError(error);
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
        return R.err(error);
    }

    private void cleanupProvisionedGateways(List<GatewayRuntime> gateways) {
        for (GatewayRuntime item : gateways) {
            try {
                if (WebSocketServer.isNodeOnline(item.node().getId())) {
                    GostUtil.DeletePublishingGateway(item.node().getId(), item.gateway().getGatewayName());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void discardGatewayAllocations(Long routeId, List<GatewayRuntime> gateways) {
        for (GatewayRuntime item : gateways) {
            if (item.lease() != null && item.lease().getId() != null) leaseMapper.deleteById(item.lease().getId());
        }
        gatewayMapper.delete(new QueryWrapper<HomeProxyGateway>().eq("route_id", routeId));
    }

    private void markDeleted(HomeProxyRoute route, PortLease lease, List<PortLease> gatewayLeases) {
        route.setState("deleted");
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
        markLeaseCooldown(lease);
        gatewayLeases.forEach(this::markLeaseCooldown);
        gatewayMapper.delete(new QueryWrapper<HomeProxyGateway>().eq("route_id", route.getId()));
    }

    private boolean cleanupStoredGateways(HomeProxyRoute route) {
        List<HomeProxyGateway> gateways = gatewayMapper.selectList(new QueryWrapper<HomeProxyGateway>()
                .eq("route_id", route.getId()).orderByAsc("sequence_no"));
        if (gateways.isEmpty()) {
            PortPool egressPool = route.getEgressPoolId() == null ? null : poolMapper.selectById(route.getEgressPoolId());
            Node egressNode = egressPool == null ? null : nodeMapper.selectById(egressPool.getNodeId());
            if (egressNode == null) return true;
            if (!WebSocketServer.isNodeOnline(egressNode.getId())) return false;
            GostDto result = GostUtil.DeletePublishingGateway(egressNode.getId(),
                    "home_proxy_" + route.getId() + "_egress_gateway");
            return ok(result) || containsNotFound(result);
        }
        boolean cleaned = true;
        for (HomeProxyGateway gateway : gateways) {
            Node node = nodeMapper.selectById(gateway.getNodeId());
            if (node == null) continue;
            if (!WebSocketServer.isNodeOnline(node.getId())) {
                cleaned = false;
                continue;
            }
            GostDto result = GostUtil.DeletePublishingGateway(node.getId(), gateway.getGatewayName());
            if (!ok(result) && !containsNotFound(result)) cleaned = false;
        }
        return cleaned;
    }

    private List<PortLease> storedGatewayLeases(HomeProxyRoute route) {
        List<HomeProxyGateway> gateways = gatewayMapper.selectList(new QueryWrapper<HomeProxyGateway>()
                .eq("route_id", route.getId()).orderByAsc("sequence_no"));
        if (gateways.isEmpty()) {
            PortLease legacy = route.getEgressLeaseId() == null ? null : leaseMapper.selectById(route.getEgressLeaseId());
            return legacy == null ? List.of() : List.of(legacy);
        }
        return gateways.stream().filter(item -> item.getLeaseId() != null)
                .map(item -> leaseMapper.selectById(item.getLeaseId()))
                .filter(Objects::nonNull).collect(Collectors.toList());
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

    @SuppressWarnings("unchecked")
    private Integer findAvailableNodePort(Node node) {
        if (node == null || node.getPortSta() == null || node.getPortEnd() == null) return null;
        PortLedgerQueryDto query = new PortLedgerQueryDto();
        query.setNodeId(node.getId());
        Map<String, Object> ledger = portLedgerService.list(query);
        List<PortLedgerEntryDto> entries = (List<PortLedgerEntryDto>) ledger.getOrDefault("entries", List.of());
        for (int port = node.getPortSta(); port <= node.getPortEnd(); port++) {
            int candidate = port;
            boolean occupied = entries.stream().anyMatch(item -> item.getPortStart() != null && item.getPortEnd() != null
                    && candidate >= item.getPortStart() && candidate <= item.getPortEnd()
                    && !"available".equals(item.getStatus()));
            if (!occupied) return port;
        }
        return null;
    }

    private HomeProxyRoute enrich(HomeProxyRoute route, boolean includeSecret) {
        User owner = userMapper.selectById(route.getUserId());
        InternalConnector connector = connectorMapper.selectById(route.getConnectorId());
        PortPool ingress = route.getIngressPoolId() == null ? null : poolMapper.selectById(route.getIngressPoolId());
        PortPool egress = route.getEgressPoolId() == null ? null : poolMapper.selectById(route.getEgressPoolId());
        if (StringUtils.isBlank(route.getAccessMode())) route.setAccessMode("relay");
        if (StringUtils.isBlank(route.getEgressMode())) route.setEgressMode("single");
        route.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
        route.setConnectorName(connector == null ? "接入端已删除" : connector.getName());
        route.setConnectorOnline(connector != null && WebSocketServer.isConnectorOnline(connector.getId()));
        route.setIngressPoolName(isDirectMode(route.getAccessMode())
                ? ("ipv4_direct".equals(route.getAccessMode()) ? "家庭 IPv4 直连" : "家庭 IPv6 直连")
                : ingress == null ? "入口端口池已删除" : ingress.getName());
        route.setEgressPoolName("tunnel".equals(route.getEgressMode())
                ? "由出口隧道自动分配" : egress == null ? "出口端口池已删除" : egress.getName());
        Tunnel egressTunnel = route.getEgressTunnelId() == null ? null : tunnelMapper.selectById(route.getEgressTunnelId());
        route.setEgressTunnelName(egressTunnel == null ? null : egressTunnel.getName());
        List<Long> pathNodeIds = egressTunnel == null ? List.of() : TunnelRouteUtil.parseNodePath(egressTunnel);
        if (pathNodeIds.isEmpty() && "tunnel".equals(route.getEgressMode())) {
            pathNodeIds = gatewayMapper.selectList(new QueryWrapper<HomeProxyGateway>()
                            .eq("route_id", route.getId()).orderByAsc("sequence_no"))
                    .stream().map(HomeProxyGateway::getNodeId).collect(Collectors.toList());
        }
        route.setEgressPathNodeDetails(pathNodeIds.stream().map(nodeId -> {
            Node node = nodeMapper.selectById(nodeId);
            TunnelPathNodeDto detail = new TunnelPathNodeDto();
            detail.setNodeId(nodeId);
            detail.setName(node == null ? "节点已删除" : node.getName());
            detail.setStatus(node != null && WebSocketServer.isNodeOnline(nodeId) ? 1 : 0);
            return detail;
        }).collect(Collectors.toList()));
        route.setPublicHost(isDirectMode(route.getAccessMode())
                ? StringUtils.defaultIfBlank(route.getPublicDomain(),
                "ipv4_direct".equals(route.getAccessMode()) ? route.getDirectIpv4() : route.getDirectIpv6())
                : ingress == null ? null : ingress.getPublicHost());
        if (!includeSecret || !Objects.equals(route.getAuthEnabled(), 1)) {
            route.setAuthPassword(null);
        } else {
            route.setAuthPassword(decryptPassword(route.getAuthPassword()));
        }
        return route;
    }

    private String normalizeAccessMode(String value) {
        String mode = StringUtils.defaultIfBlank(value, "relay").trim().toLowerCase();
        if (!List.of("relay", "ipv6_direct", "ipv4_direct").contains(mode)) {
            throw new IllegalArgumentException("家庭接入方式不受支持");
        }
        return mode;
    }

    private String normalizeEgressMode(String value) {
        String mode = StringUtils.defaultIfBlank(value, "single").trim().toLowerCase();
        if (!List.of("single", "tunnel").contains(mode)) {
            throw new IllegalArgumentException("家庭出口方式不受支持");
        }
        return mode;
    }

    private boolean isDirectMode(String accessMode) {
        return "ipv6_direct".equals(accessMode) || "ipv4_direct".equals(accessMode);
    }

    private HomeProxyRoute ownedRoute(Long id) {
        HomeProxyRoute route = routeMapper.selectById(id);
        if (route == null || (!isAdmin() && !Objects.equals(route.getUserId(), currentUserId()))) return null;
        return route;
    }

    private String queryConnectorPublicIp(Long connectorId, String family) {
        GostDto response = WebSocketServer.sendConnectorMsg(connectorId,
                Map.of("family", family), "PublicIpQuery");
        if (!ok(response) || response.getData() == null) {
            throw new IllegalStateException("家庭 Agent 未检测到可用公网 " + familyLabel(family) + "：" + message(response));
        }
        JSONObject data = response.getData() instanceof JSONObject
                ? (JSONObject) response.getData()
                : JSONObject.parseObject(JSONObject.toJSONString(response.getData()));
        String address = StringUtils.trimToEmpty(data.getString("address"));
        try {
            InetAddress parsed = InetAddress.getByName(address);
            if ("ipv4".equals(family)) {
                if (!(parsed instanceof Inet4Address) || !isPublicIpv4((Inet4Address) parsed)) {
                    throw new IllegalArgumentException();
                }
                return parsed.getHostAddress();
            }
            String lower = address.toLowerCase();
            if (!(parsed instanceof Inet6Address) || parsed.isAnyLocalAddress() || parsed.isLoopbackAddress()
                    || parsed.isLinkLocalAddress() || parsed.isSiteLocalAddress() || parsed.isMulticastAddress()
                    || lower.startsWith("fc") || lower.startsWith("fd")) {
                throw new IllegalArgumentException();
            }
            return parsed.getHostAddress();
        } catch (Exception error) {
            throw new IllegalStateException("家庭 Agent 返回的不是公网 " + familyLabel(family) + " 地址");
        }
    }

    private boolean isPublicIpv4(Inet4Address address) {
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        return first != 192 || second != 168;
    }

    private String checkConnectorPort(Long connectorId, String host, int port, String familyLabel) {
        JSONObject check = new JSONObject();
        check.put("network", "tcp");
        check.put("host", host);
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
        return "家庭设备无法监听 " + familyLabel + " TCP " + port + "：" + StringUtils.defaultIfBlank(detail, "Agent 未返回原因");
    }

    private String refreshDirectAddress(HomeProxyRoute route) {
        if (!WebSocketServer.isConnectorOnline(route.getConnectorId())) throw new IllegalStateException("家庭接入端离线");
        boolean ipv6 = "ipv6_direct".equals(route.getAccessMode());
        String family = ipv6 ? "ipv6" : "ipv4";
        String address = queryConnectorPublicIp(route.getConnectorId(), family);
        Node egressNode = routeFinalEgressNode(route);
        if (egressNode == null || !WebSocketServer.isNodeOnline(egressNode.getId())) {
            throw new IllegalStateException("家庭出口 VPS 离线，无法验证新的公网地址");
        }
        if (route.getDirectPort() == null) {
            throw new IllegalStateException("家庭直连端口缺失");
        }
        EndpointProbeResult probe = ipv6
                ? waitForIpv6Endpoint(egressNode, address, route.getDirectPort())
                : waitForEndpoint(egressNode, address, route.getDirectPort());
        if (probe.status == EndpointProbeStatus.UNREACHABLE) {
            throw new IllegalStateException(directFailureMessage(ipv6 ? "IPv6" : "IPv4", route.getDirectPort(), probe.detail));
        }
        long now = System.currentTimeMillis();
        if (ipv6) {
            route.setDirectIpv6(address);
            route.setIpv6CheckedAt(now);
        } else {
            route.setDirectIpv4(address);
        }
        route.setPublicPort(route.getDirectPort());
        route.setIpCheckedAt(now);
        route.setLastError(ipv6 && probe.status == EndpointProbeStatus.PROBE_IPV6_UNSUPPORTED
                ? ipv6UnverifiedMessage(egressNode, route.getDirectPort()) : null);
        route.setUpdatedTime(now);
        routeMapper.updateById(route);
        syncDynamicDnsIfBound(route);
        return address;
    }

    private Node routeFinalEgressNode(HomeProxyRoute route) {
        if (route.getEgressTunnelId() != null) {
            Tunnel tunnel = tunnelMapper.selectById(route.getEgressTunnelId());
            return tunnel == null ? null : tunnelFinalNode(tunnel);
        }
        PortPool pool = route.getEgressPoolId() == null ? null : poolMapper.selectById(route.getEgressPoolId());
        return pool == null ? null : nodeMapper.selectById(pool.getNodeId());
    }

    private GostDto deleteConnectorRuntime(HomeProxyRoute route, Long connectorId) {
        String base = "home_proxy_" + route.getId();
        if (isDirectMode(route.getAccessMode())) {
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

    private String encryptGatewayPassword(String value) {
        return "enc:" + new AESCrypto(encryptionSecret + ":home-proxy-gateway").encrypt(value);
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

    private String directFailureMessage(String familyLabel, int port, String detail) {
        String routerHint = "IPv4".equals(familyLabel)
                ? "请在家庭路由器配置 IPv4 端口转发：WAN TCP " + port + " -> 家庭设备局域网 IP:" + port
                : "请在家庭路由器和系统防火墙放行 TCP " + port;
        String message = "家庭 " + familyLabel + " 端口无法从公网访问，" + routerHint;
        return StringUtils.isBlank(detail) ? message : message + "（探测结果：" + StringUtils.abbreviate(detail, 160) + "）";
    }

    private Map<String, Object> validateDynamicDnsBinding(Long ruleId, Long connectorId, String family) {
        if (!isAdmin()) throw new IllegalStateException("只有管理员可以绑定动态 DNS 规则");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,name,source_type,connector_id,record_name,record_type,enabled FROM dynamic_dns_rule WHERE id=?",
                ruleId);
        if (rows.isEmpty()) throw new IllegalStateException("动态 DNS 规则不存在");
        Map<String, Object> rule = rows.get(0);
        if (!"connector".equals(StringUtils.defaultIfBlank(Objects.toString(rule.get("source_type"), null), "node"))) {
            throw new IllegalStateException("请选择来源为家庭接入端的动态 DNS 规则");
        }
        if (!Objects.equals(number(rule.get("connector_id")), connectorId)) {
            throw new IllegalStateException("动态 DNS 规则的家庭接入端与当前代理不一致");
        }
        String expectedType = "ipv6".equals(family) ? "AAAA" : "A";
        if (!expectedType.equalsIgnoreCase(Objects.toString(rule.get("record_type")))) {
            throw new IllegalStateException("IPv4 直连必须绑定 A 记录，IPv6 直连必须绑定 AAAA 记录");
        }
        if (!truthy(rule.get("enabled"))) throw new IllegalStateException("动态 DNS 规则未启用");
        return rule;
    }

    private void syncDynamicDnsIfBound(HomeProxyRoute route) {
        if (route.getDynamicDnsRuleId() == null) return;
        R result = dynamicDnsService.runNow(route.getDynamicDnsRuleId());
        if (result.getCode() == 0) return;
        String current = StringUtils.trimToNull(route.getLastError());
        String dnsError = "动态 DNS 同步失败：" + result.getMsg();
        route.setLastError(StringUtils.abbreviate(current == null ? dnsError : current + "；" + dnsError, 500));
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.updateById(route);
    }

    private String familyLabel(String family) {
        return "ipv4".equals(family) ? "IPv4" : "IPv6";
    }

    private long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private boolean truthy(Object value) {
        return value != null && ("1".equals(value.toString()) || Boolean.parseBoolean(value.toString()));
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

    private record GatewayPlan(Node node, PortPool pool, PortPoolGrant grant) {
    }

    private record GatewayRuntime(HomeProxyGateway gateway, PortLease lease, Node node, PortPool pool,
                                  String username, String password) {
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
