package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.InternalConnectorCreateDto;
import com.admin.common.dto.PortPoolCreateDto;
import com.admin.common.dto.PublishedServiceCreateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.PortNamespaceUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.InternalConnector;
import com.admin.entity.Node;
import com.admin.entity.PortLease;
import com.admin.entity.PortLeaseEvent;
import com.admin.entity.PortPool;
import com.admin.entity.PublishedService;
import com.admin.entity.User;
import com.admin.entity.ViteConfig;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortLeaseEventMapper;
import com.admin.mapper.PortLeaseMapper;
import com.admin.mapper.PortPoolMapper;
import com.admin.mapper.PublishedServiceMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.ViteConfigMapper;
import com.admin.service.ServicePublishingService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ServicePublishingServiceImpl implements ServicePublishingService {
    private static final String MIN_PUBLISHING_AGENT_VERSION = "2.7.0";
    private static final String DEFAULT_ALLOWED_CIDRS = "127.0.0.1/32,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
    private static final String INSTALL_SCRIPT_URL = "https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/install.sh";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource private InternalConnectorMapper connectorMapper;
    @Resource private PortPoolMapper poolMapper;
    @Resource private PortLeaseMapper leaseMapper;
    @Resource private PortLeaseEventMapper eventMapper;
    @Resource private PublishedServiceMapper publishedServiceMapper;
    @Resource private NodeMapper nodeMapper;
    @Resource private UserMapper userMapper;
    @Resource private ViteConfigMapper viteConfigMapper;
    @Resource private JdbcTemplate jdbcTemplate;

    @Override
    public R createConnector(InternalConnectorCreateDto dto) {
        long now = System.currentTimeMillis();
        InternalConnector connector = new InternalConnector();
        connector.setUserId(currentUserId());
        connector.setName(dto.getName().trim());
        connector.setSecret(randomHex(24));
        connector.setAllowedCidrs(StringUtils.defaultIfBlank(dto.getAllowedCidrs(), DEFAULT_ALLOWED_CIDRS));
        if (!validCidrList(connector.getAllowedCidrs())) {
            return R.err("允许访问的网段格式不正确");
        }
        connector.setStatus(1);
        connector.setCreatedTime(now);
        connector.setUpdatedTime(now);
        connectorMapper.insert(connector);
        String installCommand = buildInstallCommand(connector);
        connector.setSecret(null);
        Map<String, Object> result = new HashMap<>();
        result.put("connector", connector);
        result.put("installCommand", installCommand);
        return R.ok(result);
    }

    @Override
    public R listConnectors() {
        QueryWrapper<InternalConnector> query = new QueryWrapper<InternalConnector>().eq("status", 1).orderByDesc("created_time");
        if (!isAdmin()) {
            query.eq("user_id", currentUserId());
        }
        List<InternalConnector> connectors = connectorMapper.selectList(query);
        for (InternalConnector connector : connectors) {
            connector.setOnline(WebSocketServer.isConnectorOnline(connector.getId()));
            User owner = userMapper.selectById(connector.getUserId());
            connector.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
            connector.setSecret(null);
        }
        return R.ok(connectors);
    }

    @Override
    public R connectorInstallCommand(Long id) {
        InternalConnector connector = ownedConnector(id);
        if (connector == null) return R.err("内网接入端不存在或无权访问");
        return R.ok(buildInstallCommand(connector));
    }

    @Override
    public R deleteConnector(Long id) {
        InternalConnector connector = ownedConnector(id);
        if (connector == null) return R.err("内网接入端不存在或无权访问");
        Integer count = publishedServiceMapper.selectCount(new QueryWrapper<PublishedService>()
                .eq("connector_id", id).notIn("state", "released", "deleted"));
        if (count != null && count > 0) return R.err("该接入端仍有发布服务，请先删除相关服务");
        connector.setStatus(0);
        connector.setUpdatedTime(System.currentTimeMillis());
        connectorMapper.updateById(connector);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R createPortPool(PortPoolCreateDto dto) {
        Node node = nodeMapper.selectById(dto.getNodeId());
        if (node == null) return R.err("公网节点不存在");
        if (!WebSocketServer.isNodeOnline(node.getId())) return R.err("公网节点离线，暂时不能创建端口池");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_PUBLISHING_AGENT_VERSION)) {
            return R.err("公网节点 Agent 需要先升级到 " + MIN_PUBLISHING_AGENT_VERSION + " 或更高版本");
        }
        String bindIp = StringUtils.defaultString(dto.getBindIp()).trim();
        if (dto.getStartPort() > dto.getEndPort()) return R.err("起始端口不能大于结束端口");
        if (dto.getControlPort() >= dto.getStartPort() && dto.getControlPort() <= dto.getEndPort()) {
            return R.err("控制端口不能位于租用端口范围内");
        }
        jdbcTemplate.queryForObject("SELECT id FROM service_publish_lock WHERE id=1 FOR UPDATE", Integer.class);
        Set<Long> namespaceNodeIds = namespaceNodeIds(dto.getNodeId());
        if (poolRangeConflict(namespaceNodeIds, dto.getStartPort(), dto.getEndPort(), dto.getControlPort())) {
            return R.err("端口范围或控制端口与现有端口池冲突");
        }
        if (forwardRangeConflict(namespaceNodeIds, dto.getStartPort(), dto.getEndPort(), dto.getControlPort())) {
            return R.err("端口范围或控制端口已被现有转发使用");
        }

        long now = System.currentTimeMillis();
        PortPool pool = new PortPool();
        pool.setName(dto.getName().trim());
        pool.setNodeId(dto.getNodeId());
        pool.setBindIp(bindIp);
        pool.setPublicHost(dto.getPublicHost().trim());
        pool.setStartPort(dto.getStartPort());
        pool.setEndPort(dto.getEndPort());
        pool.setControlPort(dto.getControlPort());
        pool.setAuthUsername("flux_" + randomHex(5));
        pool.setAuthPassword(randomHex(16));
        pool.setDefaultLeaseHours(dto.getDefaultLeaseHours() == null ? 24 : dto.getDefaultLeaseHours());
        pool.setMaxLeaseHours(dto.getMaxLeaseHours() == null ? 720 : dto.getMaxLeaseHours());
        pool.setCooldownSeconds(dto.getCooldownSeconds() == null ? 60 : dto.getCooldownSeconds());
        if (pool.getDefaultLeaseHours() > pool.getMaxLeaseHours()) return R.err("默认租期不能大于最大租期");
        pool.setStatus(1);
        pool.setCreatedTime(now);
        pool.setUpdatedTime(now);
        poolMapper.insert(pool);

        GostDto result = GostUtil.AddPublishingGateway(node.getId(), gatewayName(pool.getId()), bindIp,
                pool.getControlPort(), pool.getAuthUsername(), pool.getAuthPassword());
        if (!gostSuccess(result)) {
            throw new IllegalStateException("公网节点创建反向入口失败：" + gostMessage(result));
        }
        return R.ok(pool);
    }

    @Override
    public R listPortPools() {
        List<PortPool> pools = poolMapper.selectList(new QueryWrapper<PortPool>().eq("status", 1).orderByDesc("created_time"));
        for (PortPool pool : pools) {
            Node node = nodeMapper.selectById(pool.getNodeId());
            pool.setNodeName(node == null ? "节点已删除" : node.getName());
            int total = pool.getEndPort() - pool.getStartPort() + 1;
            Integer used = leaseMapper.selectCount(new QueryWrapper<PortLease>().eq("pool_id", pool.getId()));
            pool.setTotalPorts(total);
            pool.setUsedPorts(used == null ? 0 : used.intValue());
            pool.setAvailablePorts(Math.max(0, total - pool.getUsedPorts()));
            pool.setAuthUsername(null);
            pool.setAuthPassword(null);
        }
        return R.ok(pools);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deletePortPool(Long id) {
        PortPool pool = poolMapper.selectById(id);
        if (pool == null || pool.getStatus() == 0) return R.err("端口池不存在");
        Integer leases = leaseMapper.selectCount(new QueryWrapper<PortLease>().eq("pool_id", id));
        if (leases != null && leases > 0) return R.err("端口池仍有租约，不能删除");
        GostDto result = GostUtil.DeletePublishingGateway(pool.getNodeId(), gatewayName(pool.getId()));
        if (!gostSuccess(result)) return R.err("删除公网入口失败：" + gostMessage(result));
        pool.setStatus(0);
        pool.setUpdatedTime(System.currentTimeMillis());
        poolMapper.updateById(pool);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R createPublishedService(PublishedServiceCreateDto dto) {
        Integer userId = currentUserId();
        InternalConnector connector = connectorMapper.selectById(dto.getConnectorId());
        if (connector == null || connector.getStatus() == 0 || (!isAdmin() && !Objects.equals(connector.getUserId(), userId))) {
            return R.err("内网接入端不存在或无权使用");
        }
        if (!WebSocketServer.isConnectorOnline(connector.getId())) return R.err("内网接入端离线，暂时不能发布服务");
        PortPool pool = poolMapper.selectById(dto.getPoolId());
        if (pool == null || pool.getStatus() == 0) return R.err("端口池不存在或已停用");
        if (!WebSocketServer.isNodeOnline(pool.getNodeId())) return R.err("公网节点离线，暂时不能发布服务");
        int leaseHours = dto.getLeaseHours() == null ? pool.getDefaultLeaseHours() : dto.getLeaseHours();
        if (leaseHours > pool.getMaxLeaseHours()) return R.err("租期超过该端口池允许的最大值");
        if (!targetAllowed(dto.getTargetHost(), connector.getAllowedCidrs())) {
            return R.err("目标地址不在该接入端允许访问的网段内");
        }

        jdbcTemplate.queryForObject("SELECT id FROM service_publish_lock WHERE id=1 FOR UPDATE", Integer.class);
        int port = allocatePort(pool, dto.getRequestedPort());
        long now = System.currentTimeMillis();
        long expiresAt = now + leaseHours * 3600000L;

        PublishedService published = new PublishedService();
        published.setUserId(userId);
        published.setName(dto.getName().trim());
        published.setConnectorId(connector.getId());
        published.setPoolId(pool.getId());
        published.setTargetHost(dto.getTargetHost().trim());
        published.setTargetPort(dto.getTargetPort());
        published.setPublicPort(port);
        published.setProtocol("tcp");
        published.setState("provisioning");
        published.setLeaseHours(leaseHours);
        published.setExpiresAt(expiresAt);
        published.setCreatedTime(now);
        published.setUpdatedTime(now);
        publishedServiceMapper.insert(published);

        String baseName = "publish_" + published.getId();
        String chainName = baseName + "_chain";
        String serviceName = baseName + "_rtcp";
        published.setServiceName(serviceName);

        PortLease lease = new PortLease();
        lease.setPoolId(pool.getId());
        lease.setServiceId(published.getId());
        lease.setUserId(userId);
        lease.setPort(port);
        lease.setProtocol("tcp");
        lease.setState("reserved");
        lease.setExpiresAt(expiresAt);
        lease.setCreatedTime(now);
        lease.setUpdatedTime(now);
        try {
            leaseMapper.insert(lease);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("端口刚刚被其他任务占用，请重试");
        }
        published.setLeaseId(lease.getId());
        publishedServiceMapper.updateById(published);

        String controlAddress = hostPort(pool.getPublicHost(), pool.getControlPort());
        GostDto chainResult = GostUtil.AddPublishingChain(connector.getId(), chainName, controlAddress,
                pool.getAuthUsername(), pool.getAuthPassword());
        if (!gostSuccess(chainResult)) {
            throw new IllegalStateException("创建反向线路失败：" + gostMessage(chainResult));
        }
        GostDto serviceResult = GostUtil.AddPublishedTcpService(connector.getId(), serviceName, chainName,
                pool.getBindIp(), port, hostPort(dto.getTargetHost(), dto.getTargetPort()));
        if (!gostSuccess(serviceResult)) {
            GostUtil.DeletePublishedTcpService(connector.getId(), serviceName, chainName);
            throw new IllegalStateException("发布内网服务失败：" + gostMessage(serviceResult));
        }

        published.setState("active");
        published.setLastError(null);
        published.setUpdatedTime(System.currentTimeMillis());
        publishedServiceMapper.updateById(published);
        lease.setState("active");
        lease.setUpdatedTime(System.currentTimeMillis());
        leaseMapper.updateById(lease);
        recordEvent(lease, published, "created", "租用端口 " + port + "，租期 " + leaseHours + " 小时");
        return R.ok(enrich(published));
    }

    @Override
    public R listPublishedServices() {
        QueryWrapper<PublishedService> query = new QueryWrapper<PublishedService>().ne("state", "deleted").orderByDesc("created_time");
        if (!isAdmin()) query.eq("user_id", currentUserId());
        List<PublishedService> services = publishedServiceMapper.selectList(query);
        services.replaceAll(this::enrich);
        return R.ok(services);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R renewPublishedService(Long id, Integer hours) {
        if (hours == null || hours < 1) return R.err("续租时长至少为1小时");
        PublishedService service = ownedService(id);
        if (service == null) return R.err("发布服务不存在或无权访问");
        if (!List.of("active", "expiring").contains(service.getState())) return R.err("当前状态不能续租");
        PortPool pool = poolMapper.selectById(service.getPoolId());
        if (pool == null || hours > pool.getMaxLeaseHours()) return R.err("续租时长超过端口池限制");
        long now = System.currentTimeMillis();
        long expiresAt = Math.max(now, service.getExpiresAt() == null ? now : service.getExpiresAt()) + hours * 3600000L;
        service.setExpiresAt(expiresAt);
        service.setLeaseHours(service.getLeaseHours() + hours);
        service.setState("active");
        service.setUpdatedTime(now);
        publishedServiceMapper.updateById(service);
        PortLease lease = leaseMapper.selectById(service.getLeaseId());
        if (lease != null) {
            lease.setExpiresAt(expiresAt);
            lease.setState("active");
            lease.setReleaseAfter(null);
            lease.setUpdatedTime(now);
            leaseMapper.updateById(lease);
            recordEvent(lease, service, "renewed", "续租 " + hours + " 小时");
        }
        return R.ok(enrich(service));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deletePublishedService(Long id) {
        PublishedService service = ownedService(id);
        if (service == null) return R.err("发布服务不存在或无权访问");
        if ("deleted".equals(service.getState())) return R.ok();
        if (!cleanupService(service, true)) {
            return R.ok(enrich(publishedServiceMapper.selectById(id)));
        }
        return R.ok();
    }

    @Override
    public R listLeaseEvents(Long serviceId) {
        PublishedService service = ownedService(serviceId);
        if (service == null) return R.err("发布服务不存在或无权访问");
        return R.ok(eventMapper.selectList(new QueryWrapper<PortLeaseEvent>()
                .eq("service_id", serviceId).orderByDesc("created_time").last("LIMIT 100")));
    }

    @Override
    public void processLeaseLifecycle() {
        long now = System.currentTimeMillis();
        List<PublishedService> expired = publishedServiceMapper.selectList(new QueryWrapper<PublishedService>()
                .and(q -> q.eq("state", "delete_pending")
                        .or(expiredQuery -> expiredQuery.in("state", "active", "expiring", "cleanup_pending")
                                .le("expires_at", now)))
                .orderByAsc("updated_time").last("LIMIT 100"));
        for (PublishedService service : expired) {
            try {
                cleanupService(service, "delete_pending".equals(service.getState()));
            } catch (Exception e) {
                log.warn("清理过期发布服务 {} 失败: {}", service.getId(), e.getMessage());
            }
        }
        List<PortLease> releasable = leaseMapper.selectList(new QueryWrapper<PortLease>()
                .eq("state", "cooldown").le("release_after", now).last("LIMIT 200"));
        for (PortLease lease : releasable) {
            leaseMapper.deleteById(lease.getId());
            PublishedService service = publishedServiceMapper.selectById(lease.getServiceId());
            if (service != null) {
                if (!"deleted".equals(service.getState())) {
                    service.setState("released");
                }
                service.setUpdatedTime(now);
                publishedServiceMapper.updateById(service);
                recordEvent(lease, service, "released", "端口冷却结束，已返回端口池");
            }
        }
    }

    private boolean cleanupService(PublishedService service, boolean manual) {
        long now = System.currentTimeMillis();
        String pendingState = manual ? "delete_pending" : "cleanup_pending";
        boolean alreadyPending = pendingState.equals(service.getState());
        service.setState(pendingState);
        service.setLastError(manual ? "等待内网接入端完成删除" : "租约已到期，等待内网接入端清理反向服务");
        service.setUpdatedTime(now);
        publishedServiceMapper.updateById(service);
        PortLease lease = leaseMapper.selectById(service.getLeaseId());
        if (!WebSocketServer.isConnectorOnline(service.getConnectorId())) {
            if (lease != null && !alreadyPending) {
                recordEvent(lease, service, "cleanup_pending", "接入端离线，端口保持占用以防重复分配");
            }
            return false;
        }
        String baseName = "publish_" + service.getId();
        GostDto result = GostUtil.DeletePublishedTcpService(service.getConnectorId(), service.getServiceName(), baseName + "_chain");
        if (!gostCleanupSuccess(result)) {
            service.setLastError(gostMessage(result));
            publishedServiceMapper.updateById(service);
            return false;
        }
        PortPool pool = poolMapper.selectById(service.getPoolId());
        int cooldown = pool == null ? 60 : pool.getCooldownSeconds();
        if (lease != null) {
            lease.setState("cooldown");
            lease.setReleaseAfter(now + cooldown * 1000L);
            lease.setUpdatedTime(now);
            leaseMapper.updateById(lease);
            recordEvent(lease, service, manual ? "deleted" : "expired",
                    manual ? "用户删除服务，端口进入冷却" : "租约到期，服务已停止，端口进入冷却");
        }
        service.setState(manual ? "deleted" : "expired");
        service.setLastError(null);
        service.setUpdatedTime(now);
        publishedServiceMapper.updateById(service);
        return true;
    }

    private PublishedService enrich(PublishedService service) {
        InternalConnector connector = connectorMapper.selectById(service.getConnectorId());
        PortPool pool = poolMapper.selectById(service.getPoolId());
        User owner = userMapper.selectById(service.getUserId());
        service.setConnectorName(connector == null ? "接入端已删除" : connector.getName());
        service.setConnectorOnline(connector != null && WebSocketServer.isConnectorOnline(connector.getId()));
        service.setPoolName(pool == null ? "端口池已删除" : pool.getName());
        service.setPublicHost(pool == null ? null : pool.getPublicHost());
        service.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
        return service;
    }

    private int allocatePort(PortPool pool, Integer requested) {
        if (requested != null && (requested < pool.getStartPort() || requested > pool.getEndPort())) {
            throw new IllegalArgumentException("指定端口不在端口池范围内");
        }
        List<Integer> used = leaseMapper.selectList(new QueryWrapper<PortLease>().eq("pool_id", pool.getId()))
                .stream().map(PortLease::getPort).sorted().toList();
        if (requested != null) {
            if (used.contains(requested) || forwardPortConflict(namespaceNodeIds(pool.getNodeId()), requested)) {
                throw new IllegalStateException("指定端口已被占用");
            }
            return requested;
        }
        for (int port = pool.getStartPort(); port <= pool.getEndPort(); port++) {
            if (!used.contains(port) && !forwardPortConflict(namespaceNodeIds(pool.getNodeId()), port)) return port;
        }
        throw new IllegalStateException("端口池没有可用端口");
    }

    private boolean poolRangeConflict(Set<Long> nodeIds, int start, int end, int controlPort) {
        Integer count = poolMapper.selectCount(new QueryWrapper<PortPool>().in("node_id", nodeIds).eq("status", 1)
                .and(q -> q.le("start_port", end).ge("end_port", start)
                        .or().between("control_port", start, end)
                        .or().apply("{0} BETWEEN start_port AND end_port", controlPort)
                        .or().eq("control_port", controlPort)));
        return count != null && count > 0;
    }

    private boolean forwardPortConflict(Set<Long> nodeIds, int port) {
        for (Long nodeId : nodeIds) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM forward f JOIN tunnel t ON t.id=f.tunnel_id WHERE t.in_node_id=? AND f.in_port=? AND f.status<>-1",
                    Integer.class, nodeId, port);
            if (count != null && count > 0) return true;
        }
        return false;
    }

    private boolean forwardRangeConflict(Set<Long> nodeIds, int start, int end, int controlPort) {
        for (Long nodeId : nodeIds) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM forward f JOIN tunnel t ON t.id=f.tunnel_id "
                            + "WHERE t.in_node_id=? AND f.status<>-1 AND (f.in_port BETWEEN ? AND ? OR f.in_port=?)",
                    Integer.class, nodeId, start, end, controlPort);
            if (count != null && count > 0) return true;
        }
        return false;
    }

    private Set<Long> namespaceNodeIds(Long nodeId) {
        Node requested = nodeMapper.selectById(nodeId);
        if (requested == null) return Set.of(nodeId);
        String namespace = PortNamespaceUtil.fromNode(requested);
        return nodeMapper.selectList(null).stream()
                .filter(node -> Objects.equals(PortNamespaceUtil.fromNode(node), namespace))
                .map(Node::getId)
                .collect(Collectors.toSet());
    }

    private boolean targetAllowed(String host, String cidrs) {
        try {
            if (!isIpLiteral(host)) return false;
            InetAddress address = InetAddress.getByName(stripBrackets(host));
            if ("169.254.169.254".equals(address.getHostAddress())) return false;
            for (String cidr : cidrs.split(",")) {
                if (inCidr(address.getAddress(), cidr.trim())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean validCidrList(String cidrs) {
        try {
            for (String cidr : cidrs.split(",")) {
                String[] parts = cidr.trim().split("/");
                if (parts.length != 2 || !isIpLiteral(parts[0])) return false;
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > address.length * 8) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean inCidr(byte[] address, String cidr) throws Exception {
        String[] parts = cidr.split("/");
        byte[] network = InetAddress.getByName(parts[0]).getAddress();
        if (network.length != address.length) return false;
        int bits = Integer.parseInt(parts[1]);
        for (int i = 0; i < address.length; i++) {
            int maskBits = Math.min(8, Math.max(0, bits - i * 8));
            int mask = maskBits == 0 ? 0 : (0xff << (8 - maskBits)) & 0xff;
            if ((address[i] & mask) != (network[i] & mask)) return false;
        }
        return true;
    }

    private boolean isIpLiteral(String value) {
        String host = stripBrackets(value);
        if (host.contains(":")) return host.matches("[0-9a-fA-F:%.]+");
        return host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
    }

    private String stripBrackets(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private String hostPort(String host, int port) {
        String value = stripBrackets(host.trim());
        return value.contains(":") ? "[" + value + "]:" + port : value + ":" + port;
    }

    private void recordEvent(PortLease lease, PublishedService service, String type, String detail) {
        PortLeaseEvent event = new PortLeaseEvent();
        event.setLeaseId(lease == null ? null : lease.getId());
        event.setServiceId(service.getId());
        event.setUserId(service.getUserId());
        event.setEventType(type);
        event.setDetail(detail);
        event.setCreatedTime(System.currentTimeMillis());
        eventMapper.insert(event);
    }

    private InternalConnector ownedConnector(Long id) {
        InternalConnector connector = connectorMapper.selectById(id);
        if (connector == null || (!isAdmin() && !Objects.equals(connector.getUserId(), currentUserId()))) return null;
        return connector;
    }

    private PublishedService ownedService(Long id) {
        PublishedService service = publishedServiceMapper.selectById(id);
        if (service == null || (!isAdmin() && !Objects.equals(service.getUserId(), currentUserId()))) return null;
        return service;
    }

    private String buildInstallCommand(InternalConnector connector) {
        ViteConfig config = viteConfigMapper.selectOne(new QueryWrapper<ViteConfig>().eq("name", "ip"));
        if (config == null || StringUtils.isBlank(config.getValue())) return "请先在网站设置中配置面板连接地址";
        return "curl -fsSL " + INSTALL_SCRIPT_URL + " -o ./install.sh && chmod +x ./install.sh && ./install.sh -a "
                + shellQuote(config.getValue()) + " -s " + shellQuote(connector.getSecret()) + " -r connector";
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String gatewayName(Long poolId) {
        return "publish_pool_" + poolId + "_socks";
    }

    private boolean gostSuccess(GostDto result) {
        return result != null && "OK".equals(result.getMsg());
    }

    private boolean gostCleanupSuccess(GostDto result) {
        return result != null && ("OK".equals(result.getMsg())
                || StringUtils.containsIgnoreCase(result.getMsg(), "not found"));
    }

    private String gostMessage(GostDto result) {
        return result == null ? "Agent 无响应" : StringUtils.defaultIfBlank(result.getMsg(), "Agent 无响应");
    }

    private String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        StringBuilder value = new StringBuilder(bytes * 2);
        for (byte b : data) value.append(String.format("%02x", b));
        return value.toString();
    }

    private Integer currentUserId() {
        return JwtUtil.getUserIdFromToken();
    }

    private boolean isAdmin() {
        return Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
    }

}
