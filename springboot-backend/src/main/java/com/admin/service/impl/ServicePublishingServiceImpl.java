package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.DomainRouteCreateDto;
import com.admin.common.dto.InternalConnectorCreateDto;
import com.admin.common.dto.PortPoolCreateDto;
import com.admin.common.dto.PublishedServiceCreateDto;
import com.admin.common.dto.PortLedgerQueryDto;
import com.admin.common.dto.PortLedgerEntryDto;
import com.admin.common.dto.SniRouteTargetDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.common.utils.ConnectorInstallCommandUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.PortNamespaceUtil;
import com.admin.common.utils.SniDomainUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.InternalConnector;
import com.admin.entity.DomainRoute;
import com.admin.entity.Node;
import com.admin.entity.PortLease;
import com.admin.entity.PortLeaseEvent;
import com.admin.entity.PortPool;
import com.admin.entity.PortPoolGrant;
import com.admin.entity.PublishedService;
import com.admin.entity.User;
import com.admin.entity.ViteConfig;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.mapper.DomainRouteMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortLeaseEventMapper;
import com.admin.mapper.PortLeaseMapper;
import com.admin.mapper.PortAllocationLockMapper;
import com.admin.mapper.PortPoolMapper;
import com.admin.mapper.PublishedServiceMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.ViteConfigMapper;
import com.admin.service.ServicePublishingService;
import com.admin.service.PortPoolGrantService;
import com.admin.service.PortLedgerService;
import com.admin.service.DnsProviderService;
import com.admin.service.ManagedCertificateService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ServicePublishingServiceImpl implements ServicePublishingService {
    private static final String MIN_PUBLISHING_AGENT_VERSION = "2.7.0";
    private static final String MIN_MANAGED_HTTPS_AGENT_VERSION = "2.17.0";
    private static final String DEFAULT_ALLOWED_CIDRS = "127.0.0.1/32,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource private InternalConnectorMapper connectorMapper;
    @Resource private PortPoolMapper poolMapper;
    @Resource private PortLeaseMapper leaseMapper;
    @Resource private PortLeaseEventMapper eventMapper;
    @Resource private PublishedServiceMapper publishedServiceMapper;
    @Resource private DomainRouteMapper domainRouteMapper;
    @Resource private NodeMapper nodeMapper;
    @Resource private UserMapper userMapper;
    @Resource private ViteConfigMapper viteConfigMapper;
    @Resource private JdbcTemplate jdbcTemplate;
    @Resource private PortAllocationLockMapper portAllocationLockMapper;
    @Resource private PortPoolGrantService portPoolGrantService;
    @Resource private PortLedgerService portLedgerService;
    @Resource private DnsProviderService dnsProviderService;
    @Resource private ManagedCertificateService managedCertificateService;

    @Override
    public R createConnector(InternalConnectorCreateDto dto) {
        long now = System.currentTimeMillis();
        InternalConnector connector = new InternalConnector();
        connector.setUserId(currentUserId());
        connector.setName(dto.getName().trim());
        connector.setSecret(randomHex(24));
        connector.setAllowedCidrs(StringUtils.defaultIfBlank(dto.getAllowedCidrs(), DEFAULT_ALLOWED_CIDRS));
        connector.setPlatform(ConnectorInstallCommandUtil.normalizePlatform(dto.getPlatform()));
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
    public R connectorInstallCommand(Long id, String platform, boolean uninstall) {
        InternalConnector connector = ownedConnector(id);
        if (connector == null) return R.err("内网接入端不存在或无权访问");
        return R.ok(buildInstallCommand(connector, platform, uninstall));
    }

    @Override
    public R deleteConnector(Long id) {
        InternalConnector connector = ownedConnector(id);
        if (connector == null) return R.err("内网接入端不存在或无权访问");
        Integer count = publishedServiceMapper.selectCount(new QueryWrapper<PublishedService>()
                .eq("connector_id", id).notIn("state", "released", "deleted"));
        if (count != null && count > 0) return R.err("该接入端仍有内网映射，请先删除相关映射");
        Integer homeProxyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM home_proxy_route WHERE (connector_id=? OR source_connector_id=?) AND state<>'deleted'",
                Integer.class, id, id);
        if (homeProxyCount != null && homeProxyCount > 0) {
            return R.err("该接入设备仍被家庭网络中转使用，请先删除相关中转");
        }
        connector.setStatus(0);
        connector.setUpdatedTime(System.currentTimeMillis());
        connectorMapper.updateById(connector);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R createPortPool(PortPoolCreateDto dto) {
        portAllocationLockMapper.lockForUpdate();
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
        if (domainRangeConflict(namespaceNodeIds, dto.getStartPort(), dto.getEndPort(), dto.getControlPort())) {
            return R.err("端口范围或控制端口已被域名入口使用");
        }
        if (ledgerRangeConflict(dto.getNodeId(), dto.getStartPort(), dto.getEndPort(), dto.getControlPort())) {
            return R.err("端口范围或控制端口已被转发、隧道跳点或其他端口资源使用");
        }
        AgentPortCheckUtil.Result controlCheck = AgentPortCheckUtil.check(node,
                List.of(new AgentPortCheckUtil.Check("tcp", bindIp, dto.getControlPort())));
        if (!controlCheck.isAvailable()) {
            return R.err(controlCheck.getMessage() + conflictSuffix(controlCheck));
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
        // Retained only for compatibility with existing installations; lease policy now belongs to each service.
        pool.setDefaultLeaseHours(24);
        pool.setMaxLeaseHours(720);
        pool.setCooldownSeconds(dto.getCooldownSeconds() == null ? 60 : dto.getCooldownSeconds());
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
        if (!isAdmin()) {
            Map<Long, PortPool> poolMap = pools.stream().collect(Collectors.toMap(PortPool::getId, item -> item));
            List<PortPool> grantedPools = new ArrayList<>();
            for (PortPoolGrant grant : portPoolGrantService.listGrants(currentUserId())) {
                PortPool source = poolMap.get(grant.getPoolId());
                if (source == null) continue;
                PortPool view = poolMapper.selectById(source.getId());
                Node node = nodeMapper.selectById(view.getNodeId());
                view.setNodeName(node == null ? "节点已删除" : node.getName());
                view.setGrantId(grant.getId());
                view.setGrantStartPort(grant.getStartPort());
                view.setGrantEndPort(grant.getEndPort());
                view.setGrantTotalPorts(grant.getTotalPorts());
                view.setGrantUsedPorts(grant.getUsedPorts());
                view.setTotalPorts(grant.getTotalPorts());
                view.setUsedPorts(grant.getUsedPorts());
                view.setAvailablePorts(grant.getAvailablePorts());
                view.setAccessType("shared");
                view.setStartPort(grant.getStartPort());
                view.setEndPort(grant.getEndPort());
                view.setAuthUsername(null);
                view.setAuthPassword(null);
                grantedPools.add(view);
            }
            return R.ok(grantedPools);
        }
        for (PortPool pool : pools) {
            Node node = nodeMapper.selectById(pool.getNodeId());
            pool.setNodeName(node == null ? "节点已删除" : node.getName());
            int total = pool.getEndPort() - pool.getStartPort() + 1;
            Integer used = leaseMapper.selectCount(new QueryWrapper<PortLease>().eq("pool_id", pool.getId()).isNull("grant_id"));
            pool.setTotalPorts(total);
            pool.setUsedPorts(used == null ? 0 : used.intValue());
            Set<Integer> unavailable = portPoolGrantService.grantedPorts(pool.getId());
            leaseMapper.selectList(new QueryWrapper<PortLease>().eq("pool_id", pool.getId()))
                    .forEach(lease -> unavailable.add(lease.getPort()));
            pool.setSharedPorts(portPoolGrantService.sharedPortCount(pool.getId()));
            pool.setAvailablePorts(Math.max(0, total - unavailable.size()));
            pool.setAccessType("admin");
            pool.setAuthUsername(null);
            pool.setAuthPassword(null);
        }
        return R.ok(pools);
    }

    @Override
    public R listPortGrants(Integer userId) {
        Integer effectiveUserId = isAdmin() ? userId : currentUserId();
        return R.ok(portPoolGrantService.listGrants(effectiveUserId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deletePortPool(Long id) {
        PortPool pool = poolMapper.selectById(id);
        if (pool == null || pool.getStatus() == 0) return R.err("端口池不存在");
        Integer leases = leaseMapper.selectCount(new QueryWrapper<PortLease>().eq("pool_id", id));
        if (leases != null && leases > 0) return R.err("端口池仍有租约，不能删除");
        boolean granted = portPoolGrantService.listGrants(null).stream().anyMatch(item -> Objects.equals(item.getPoolId(), id));
        if (granted) return R.err("端口池仍有分享授权，不能删除");
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
        portAllocationLockMapper.lockForUpdate();
        Integer userId = currentUserId();
        InternalConnector connector = connectorMapper.selectById(dto.getConnectorId());
        if (connector == null || connector.getStatus() == 0 || (!isAdmin() && !Objects.equals(connector.getUserId(), userId))) {
            return R.err("内网接入端不存在或无权使用");
        }
        if (!WebSocketServer.isConnectorOnline(connector.getId())) return R.err("内网接入端离线，暂时不能创建映射");
        PortPool pool = poolMapper.selectById(dto.getPoolId());
        if (pool == null || pool.getStatus() == 0) return R.err("端口池不存在或已停用");
        if (!WebSocketServer.isNodeOnline(pool.getNodeId())) return R.err("公网节点离线，暂时不能创建映射");
        boolean permanent = Boolean.TRUE.equals(dto.getPermanent());
        int leaseHours = permanent ? 0 : (dto.getLeaseHours() == null ? 24 : dto.getLeaseHours());
        if (!permanent && leaseHours < 1) return R.err("定时服务的租期至少为 1 小时");
        if (!targetAllowed(dto.getTargetHost(), connector.getAllowedCidrs())) {
            return R.err("目标地址不在该接入端允许访问的网段内");
        }

        jdbcTemplate.queryForObject("SELECT id FROM service_publish_lock WHERE id=1 FOR UPDATE", Integer.class);
        PortPoolGrant grant = null;
        if (!isAdmin()) {
            grant = portPoolGrantService.usableGrant(dto.getGrantId(), userId, pool.getId());
            if (grant == null) return R.err("端口资源未分配给当前用户或已被收回");
        }
        int port = allocatePort(pool, dto.getRequestedPort(), grant);
        Node publicNode = nodeMapper.selectById(pool.getNodeId());
        AgentPortCheckUtil.Result publicPortCheck = AgentPortCheckUtil.check(publicNode,
                List.of(new AgentPortCheckUtil.Check("tcp", pool.getBindIp(), port)));
        if (!publicPortCheck.isAvailable()) {
            return R.err(publicPortCheck.getMessage() + conflictSuffix(publicPortCheck));
        }
        long now = System.currentTimeMillis();
        Long expiresAt = permanent ? null : now + leaseHours * 3600000L;

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
        lease.setGrantId(grant == null ? null : grant.getId());
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
            throw new IllegalStateException("创建内网映射失败：" + gostMessage(serviceResult));
        }

        published.setState("active");
        published.setLastError(null);
        published.setUpdatedTime(System.currentTimeMillis());
        publishedServiceMapper.updateById(published);
        lease.setState("active");
        lease.setUpdatedTime(System.currentTimeMillis());
        leaseMapper.updateById(lease);
        recordEvent(lease, published, "created", "使用端口 " + port + "，" + (permanent ? "永久有效" : "租期 " + leaseHours + " 小时"));
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
    public R renewPublishedService(Long id, Integer hours, boolean permanent) {
        if (!permanent && (hours == null || hours < 1)) return R.err("续租时长至少为1小时");
        PublishedService service = ownedService(id);
        if (service == null) return R.err("内网映射不存在或无权访问");
        if (!List.of("active", "expiring").contains(service.getState())) return R.err("当前状态不能续租");
        long now = System.currentTimeMillis();
        Long expiresAt = permanent ? null : Math.max(now, service.getExpiresAt() == null ? now : service.getExpiresAt()) + hours * 3600000L;
        service.setExpiresAt(expiresAt);
        service.setLeaseHours(permanent ? 0 : service.getLeaseHours() + hours);
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
            recordEvent(lease, service, "renewed", permanent ? "已改为永久有效" : "续租 " + hours + " 小时");
        }
        return R.ok(enrich(service));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deletePublishedService(Long id) {
        PublishedService service = ownedService(id);
        if (service == null) return R.err("内网映射不存在或无权访问");
        if ("deleted".equals(service.getState())) return R.ok();
        Integer domainCount = domainRouteMapper.selectCount(new QueryWrapper<DomainRoute>()
                .eq("published_service_id", id).ne("state", "deleted"));
        if (domainCount != null && domainCount > 0) {
            return R.err("该映射仍被域名入口使用，请先删除相关域名入口");
        }
        if (!cleanupService(service, true)) {
            return R.ok(enrich(publishedServiceMapper.selectById(id)));
        }
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R createDomainRoute(DomainRouteCreateDto dto) {
        portAllocationLockMapper.lockForUpdate();
        jdbcTemplate.queryForObject("SELECT id FROM service_publish_lock WHERE id=1 FOR UPDATE", Integer.class);
        PublishedService mapping = publishedServiceMapper.selectById(dto.getPublishedServiceId());
        if (mapping == null || !"active".equals(mapping.getState())
                || (!isAdmin() && !Objects.equals(mapping.getUserId(), currentUserId()))) {
            return R.err("内网映射不存在、不可用或无权访问");
        }
        PortPool pool = poolMapper.selectById(mapping.getPoolId());
        if (pool == null || pool.getStatus() == 0) return R.err("映射对应的端口资源已停用");
        Node requestedNode = nodeMapper.selectById(pool.getNodeId());
        if (requestedNode == null) return R.err("映射对应的公网节点不存在");

        String ingressMode = StringUtils.defaultIfBlank(dto.getIngressMode(), "passthrough").toLowerCase(Locale.ROOT);
        if (!List.of("passthrough", "managed_https").contains(ingressMode)) {
            return R.err("不支持的域名入口模式");
        }
        if ("managed_https".equals(ingressMode) && !isAdmin()) {
            return R.err("面板托管 HTTPS 仅允许管理员配置");
        }
        final String domain;
        final String pathPrefix;
        try {
            domain = "managed_https".equals(ingressMode)
                    ? dnsProviderService.normalizeDomain(dto.getDnsZoneId(), dto.getDomain())
                    : SniDomainUtil.normalizeDomain(dto.getDomain());
            pathPrefix = "managed_https".equals(ingressMode)
                    ? SniDomainUtil.normalizePathPrefix(dto.getPathPrefix()) : "/";
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        Set<Long> namespaceIds = namespaceNodeIds(requestedNode.getId());
        List<DomainRoute> entryRoutes = domainRouteMapper.selectList(new QueryWrapper<DomainRoute>()
                .in("node_id", namespaceIds).eq("listen_port", dto.getListenPort()).ne("state", "deleted")
                .orderByAsc("created_time"));
        if (entryRoutes.stream().anyMatch(item -> domain.equalsIgnoreCase(item.getDomain())
                && pathPrefix.equals(SniDomainUtil.normalizePathPrefix(item.getPathPrefix())))) {
            return R.err("该公网入口已经配置了相同域名和路径");
        }
        if (entryRoutes.stream().anyMatch(item -> !ingressMode.equals(StringUtils.defaultIfBlank(item.getIngressMode(), "passthrough")))) {
            return R.err("同一公网入口端口不能同时使用 TLS 透传和面板托管 HTTPS");
        }

        DomainRoute existingEntry = entryRoutes.stream()
                .filter(item -> !"delete_pending".equals(item.getState()))
                .findFirst().orElse(null);
        DomainRoute sameDomainRoute = entryRoutes.stream()
                .filter(item -> domain.equalsIgnoreCase(item.getDomain()) && !"delete_pending".equals(item.getState()))
                .findFirst().orElse(null);
        if (sameDomainRoute != null && "managed_https".equals(ingressMode)
                && !Objects.equals(sameDomainRoute.getDnsZoneId(), dto.getDnsZoneId())) {
            return R.err("同一域名的路径规则必须使用相同 DNS Zone");
        }
        Long entryNodeId = existingEntry == null ? requestedNode.getId() : existingEntry.getNodeId();
        Node entryNode = nodeMapper.selectById(entryNodeId);
        if (entryNode == null) return R.err("域名入口节点不存在");
        if (!WebSocketServer.isNodeOnline(entryNodeId)) return R.err("公网节点离线，暂时不能配置域名入口");
        if ("managed_https".equals(ingressMode)
                && !AgentVersionUtil.isAtLeast(entryNode.getVersion(), MIN_MANAGED_HTTPS_AGENT_VERSION)) {
            return R.err("面板托管 HTTPS 需要入口 Agent " + MIN_MANAGED_HTTPS_AGENT_VERSION + " 或更高版本，请先升级该节点");
        }

        if (existingEntry == null) {
            Map<String, Object> ledger = portLedgerService.diagnose(entryNodeId, dto.getListenPort());
            if (Boolean.TRUE.equals(ledger.get("occupied"))) return R.err("监听端口已被转发、隧道跳点或端口资源占用");
            AgentPortCheckUtil.Result check = AgentPortCheckUtil.check(entryNode,
                    List.of(new AgentPortCheckUtil.Check("tcp", "", dto.getListenPort())));
            if (!check.isAvailable()) return R.err(check.getMessage() + conflictSuffix(check));
        }

        long now = System.currentTimeMillis();
        DomainRoute route = new DomainRoute();
        route.setUserId(currentUserId());
        route.setName(dto.getName().trim());
        route.setDomain(domain);
        route.setPathPrefix(pathPrefix);
        route.setPublishedServiceId(mapping.getId());
        route.setNodeId(entryNodeId);
        route.setListenPort(dto.getListenPort());
        route.setServiceName(existingEntry == null ? domainIngressName(entryNodeId, dto.getListenPort()) : existingEntry.getServiceName());
        route.setIngressMode(ingressMode);
        route.setDnsZoneId("managed_https".equals(ingressMode) ? dto.getDnsZoneId() : null);
        route.setState("managed_https".equals(ingressMode) ? "certificate_pending" : "provisioning");
        route.setCreatedTime(now);
        route.setUpdatedTime(now);
        try {
            domainRouteMapper.insert(route);
        } catch (DuplicateKeyException e) {
            return R.err("该域名入口刚刚被其他任务创建，请刷新后重试");
        }

        if ("managed_https".equals(ingressMode)) {
            try {
                String address = StringUtils.defaultIfBlank(entryNode.getServerIp(), entryNode.getIp());
                long certificateId = managedCertificateService.ensureCertificate(dto.getDnsZoneId(), domain);
                String recordId;
                if (sameDomainRoute == null) {
                    recordId = dnsProviderService.ensureDomainRouteRecord(dto.getDnsZoneId(), null, domain, address, route.getId());
                } else if (StringUtils.isBlank(sameDomainRoute.getDnsRecordId())) {
                    recordId = dnsProviderService.ensureDomainRouteRecord(dto.getDnsZoneId(), null, domain, address, sameDomainRoute.getId());
                    sameDomainRoute.setDnsRecordId(recordId);
                    sameDomainRoute.setUpdatedTime(System.currentTimeMillis());
                    domainRouteMapper.updateById(sameDomainRoute);
                } else {
                    recordId = sameDomainRoute.getDnsRecordId();
                }
                route.setDnsRecordId(recordId);
                route.setCertificateId(certificateId);
                route.setLastError("正在通过 DNS 验证申请 HTTPS 证书");
                route.setUpdatedTime(System.currentTimeMillis());
                domainRouteMapper.updateById(route);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        managedCertificateService.provisionAsync(certificateId);
                    }
                });
            } catch (RuntimeException e) {
                throw new IllegalStateException("创建 DNS 或证书任务失败：" + e.getMessage(), e);
            }
        } else {
            GostDto result = configureDomainIngress(entryNodeId, dto.getListenPort(), route.getServiceName(), existingEntry != null);
            if (!gostSuccess(result)) throw new IllegalStateException("创建域名入口失败：" + gostMessage(result));
            route.setState("active");
            route.setLastError(null);
            route.setUpdatedTime(System.currentTimeMillis());
            domainRouteMapper.updateById(route);
        }
        return R.ok(enrichDomainRoute(route));
    }

    @Override
    public R listDomainRoutes() {
        QueryWrapper<DomainRoute> query = new QueryWrapper<DomainRoute>().ne("state", "deleted").orderByDesc("created_time");
        if (!isAdmin()) query.eq("user_id", currentUserId());
        List<DomainRoute> routes = domainRouteMapper.selectList(query);
        routes.replaceAll(this::enrichDomainRoute);
        return R.ok(routes);
    }

    @Override
    public R listManagedCertificates() {
        if (!isAdmin()) return R.err("仅管理员可以管理 HTTPS 证书");
        return R.ok(managedCertificateService.listCertificates());
    }

    @Override
    public R retryManagedCertificate(Long id) {
        if (!isAdmin()) return R.err("仅管理员可以管理 HTTPS 证书");
        if (id == null || !managedCertificateService.prepareRetry(id)) return R.err("托管证书不存在");
        managedCertificateService.provisionAsync(id);
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deleteDomainRoute(Long id) {
        DomainRoute route = ownedDomainRoute(id);
        if (route == null) return R.err("域名入口不存在或无权访问");
        if ("deleted".equals(route.getState())) return R.ok();
        route.setState("delete_pending");
        route.setLastError("等待公网节点移除域名入口");
        route.setUpdatedTime(System.currentTimeMillis());
        domainRouteMapper.updateById(route);
        if (!cleanupDomainRoute(route)) return R.ok(enrichDomainRoute(route));
        return R.ok();
    }

    @Override
    public R listLeaseEvents(Long serviceId) {
        PublishedService service = ownedService(serviceId);
        if (service == null) return R.err("内网映射不存在或无权访问");
        return R.ok(eventMapper.selectList(new QueryWrapper<PortLeaseEvent>()
                .eq("service_id", serviceId).orderByDesc("created_time").last("LIMIT 100")));
    }

    @Override
    public R listPortLedger(PortLedgerQueryDto query) {
        return R.ok(portLedgerService.list(query));
    }

    @Override
    public R diagnosePort(Long nodeId, Integer port) {
        if (nodeId == null || port == null || port < 1 || port > 65535) return R.err("节点和端口参数不正确");
        return R.ok(portLedgerService.diagnose(nodeId, port));
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
                log.warn("清理过期内网映射 {} 失败: {}", service.getId(), e.getMessage());
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
        for (DomainRoute route : domainRouteMapper.selectList(new QueryWrapper<DomainRoute>()
                .eq("state", "delete_pending").orderByAsc("updated_time").last("LIMIT 100"))) {
            try {
                cleanupDomainRoute(route);
            } catch (Exception e) {
                log.warn("清理域名入口 {} 失败: {}", route.getId(), e.getMessage());
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
        service.setOwnerRoleId(owner == null ? null : owner.getRoleId());
        PortLease lease = service.getLeaseId() == null ? null : leaseMapper.selectById(service.getLeaseId());
        PortPoolGrant grant = lease == null || lease.getGrantId() == null ? null
                : portPoolGrantService.listGrants(service.getUserId()).stream()
                .filter(item -> Objects.equals(item.getId(), lease.getGrantId())).findFirst().orElse(null);
        service.setGrantId(lease == null ? null : lease.getGrantId());
        service.setGrantStartPort(grant == null ? null : grant.getStartPort());
        service.setGrantEndPort(grant == null ? null : grant.getEndPort());
        service.setPermanent(service.getExpiresAt() == null);
        return service;
    }

    private DomainRoute enrichDomainRoute(DomainRoute route) {
        User owner = userMapper.selectById(route.getUserId());
        Node node = nodeMapper.selectById(route.getNodeId());
        PublishedService mapping = publishedServiceMapper.selectById(route.getPublishedServiceId());
        PortPool pool = mapping == null ? null : poolMapper.selectById(mapping.getPoolId());
        InternalConnector connector = mapping == null ? null : connectorMapper.selectById(mapping.getConnectorId());
        route.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
        route.setOwnerRoleId(owner == null ? null : owner.getRoleId());
        route.setNodeName(node == null ? "节点已删除" : node.getName());
        route.setNodeOnline(node != null && WebSocketServer.isNodeOnline(node.getId()));
        route.setPublicHost(pool == null ? null : pool.getPublicHost());
        route.setMappingName(mapping == null ? "映射已删除" : mapping.getName());
        route.setMappingState(mapping == null ? "deleted" : mapping.getState());
        route.setMappingPublicPort(mapping == null ? null : mapping.getPublicPort());
        route.setConnectorOnline(connector != null && WebSocketServer.isConnectorOnline(connector.getId()));
        if (route.getCertificateId() != null) {
            List<Map<String, Object>> certificates = jdbcTemplate.queryForList(
                    "SELECT state,expires_at AS expiresAt,issuer FROM managed_certificate WHERE id=?", route.getCertificateId());
            if (!certificates.isEmpty()) {
                route.setCertificateState(Objects.toString(certificates.get(0).get("state"), null));
                Object expiresAt = certificates.get(0).get("expiresAt");
                route.setCertificateExpiresAt(expiresAt == null ? null : ((Number) expiresAt).longValue());
                route.setCertificateIssuer(Objects.toString(certificates.get(0).get("issuer"), null));
            }
        }
        return route;
    }

    private GostDto configureDomainIngress(Long nodeId, Integer listenPort, String serviceName, boolean update) {
        List<SniRouteTargetDto> targets = new ArrayList<>();
        List<DomainRoute> routes = domainRouteMapper.selectList(new QueryWrapper<DomainRoute>()
                .eq("node_id", nodeId).eq("listen_port", listenPort)
                .and(q -> q.eq("ingress_mode", "passthrough").or().isNull("ingress_mode"))
                .in("state", "active", "provisioning").orderByAsc("created_time"));
        for (DomainRoute route : routes) {
            PublishedService mapping = publishedServiceMapper.selectById(route.getPublishedServiceId());
            PortPool pool = mapping == null ? null : poolMapper.selectById(mapping.getPoolId());
            if (mapping == null || pool == null || mapping.getPublicPort() == null) continue;
            targets.add(new SniRouteTargetDto(route.getId(), route.getDomain(), null,
                    localPublishedTarget(pool, mapping.getPublicPort())));
        }
        if (targets.isEmpty()) return GostUtil.DeleteDomainIngress(nodeId, serviceName);
        return GostUtil.ConfigureDomainIngress(nodeId, serviceName, "", listenPort, targets, update);
    }

    private boolean cleanupDomainRoute(DomainRoute route) {
        if (nodeMapper.selectById(route.getNodeId()) == null) {
            route.setState("deleted");
            route.setLastError(null);
            route.setUpdatedTime(System.currentTimeMillis());
            domainRouteMapper.updateById(route);
            if ("managed_https".equals(route.getIngressMode())) {
                releaseOrTransferDomainRecord(route);
                jdbcTemplate.update("DELETE FROM managed_certificate WHERE id=? AND NOT EXISTS "
                                + "(SELECT 1 FROM domain_route WHERE certificate_id=? AND state<>'deleted')",
                        route.getCertificateId(), route.getCertificateId());
            }
            return true;
        }
        if (!WebSocketServer.isNodeOnline(route.getNodeId())) {
            route.setLastError("公网节点离线，恢复连接后将自动删除");
            route.setUpdatedTime(System.currentTimeMillis());
            domainRouteMapper.updateById(route);
            return false;
        }
        if ("managed_https".equals(route.getIngressMode())) {
            route.setState("deleted");
            route.setLastError(null);
            route.setUpdatedTime(System.currentTimeMillis());
            domainRouteMapper.updateById(route);
            try {
                managedCertificateService.reconfigureEntry(route.getNodeId(), route.getListenPort(), route.getServiceName());
            } catch (RuntimeException e) {
                route.setState("delete_pending");
                route.setLastError("删除 HTTPS 入口失败：" + e.getMessage());
                domainRouteMapper.updateById(route);
                return false;
            }
            releaseOrTransferDomainRecord(route);
            Integer references = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM domain_route WHERE certificate_id=? AND state<>'deleted'", Integer.class, route.getCertificateId());
            if (references != null && references == 0) {
                jdbcTemplate.update("DELETE FROM managed_certificate WHERE id=?", route.getCertificateId());
            }
            return true;
        }
        GostDto result = configureDomainIngress(route.getNodeId(), route.getListenPort(), route.getServiceName(), true);
        if (!gostCleanupSuccess(result)) {
            route.setLastError("删除域名入口失败：" + gostMessage(result));
            route.setUpdatedTime(System.currentTimeMillis());
            domainRouteMapper.updateById(route);
            return false;
        }
        route.setState("deleted");
        route.setLastError(null);
        route.setUpdatedTime(System.currentTimeMillis());
        domainRouteMapper.updateById(route);
        return true;
    }

    private void releaseOrTransferDomainRecord(DomainRoute route) {
        DomainRoute replacement = domainRouteMapper.selectOne(new QueryWrapper<DomainRoute>()
                .eq("dns_zone_id", route.getDnsZoneId()).eq("domain", route.getDomain()).ne("state", "deleted")
                .ne("id", route.getId()).orderByAsc("created_time").last("LIMIT 1"));
        if (replacement == null) {
            dnsProviderService.releaseDomainRouteRecord(route.getId());
        } else {
            dnsProviderService.transferDomainRouteRecord(route.getId(), replacement.getId());
        }
    }

    private String localPublishedTarget(PortPool pool, int port) {
        String bindIp = StringUtils.trimToEmpty(pool.getBindIp());
        String host = StringUtils.isBlank(bindIp) || "0.0.0.0".equals(bindIp) || "::".equals(bindIp)
                || "[::]".equals(bindIp) ? "127.0.0.1" : stripBrackets(bindIp);
        return hostPort(host, port);
    }

    private DomainRoute ownedDomainRoute(Long id) {
        DomainRoute route = domainRouteMapper.selectById(id);
        if (route == null || (!isAdmin() && !Objects.equals(route.getUserId(), currentUserId()))) return null;
        return route;
    }

    private String domainIngressName(Long nodeId, Integer port) {
        return "domain_ingress_" + nodeId + "_" + port;
    }

    private int allocatePort(PortPool pool, Integer requested, PortPoolGrant grant) {
        int startPort = grant == null ? pool.getStartPort() : grant.getStartPort();
        int endPort = grant == null ? pool.getEndPort() : grant.getEndPort();
        if (requested != null && (requested < startPort || requested > endPort)) {
            throw new IllegalArgumentException("指定端口不在当前可用端口范围内");
        }
        Set<Integer> used = leaseMapper.selectList(new QueryWrapper<PortLease>().eq("pool_id", pool.getId()))
                .stream().map(PortLease::getPort).collect(Collectors.toSet());
        Set<Integer> grantedToUsers = grant == null ? portPoolGrantService.grantedPorts(pool.getId()) : Set.of();
        if (requested != null) {
            if (used.contains(requested) || grantedToUsers.contains(requested)
                    || forwardPortConflict(namespaceNodeIds(pool.getNodeId()), requested)
                    || domainPortConflict(namespaceNodeIds(pool.getNodeId()), requested)) {
                throw new IllegalStateException("指定端口已被占用");
            }
            return requested;
        }
        for (int port = startPort; port <= endPort; port++) {
            if (!used.contains(port) && !grantedToUsers.contains(port)
                    && !forwardPortConflict(namespaceNodeIds(pool.getNodeId()), port)
                    && !domainPortConflict(namespaceNodeIds(pool.getNodeId()), port)) return port;
        }
        throw new IllegalStateException("当前端口资源没有可用端口");
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

    private boolean domainPortConflict(Set<Long> nodeIds, int port) {
        Integer count = domainRouteMapper.selectCount(new QueryWrapper<DomainRoute>()
                .in("node_id", nodeIds).eq("listen_port", port).ne("state", "deleted"));
        return count != null && count > 0;
    }

    private boolean domainRangeConflict(Set<Long> nodeIds, int start, int end, int controlPort) {
        Integer count = domainRouteMapper.selectCount(new QueryWrapper<DomainRoute>()
                .in("node_id", nodeIds).ne("state", "deleted")
                .and(q -> q.between("listen_port", start, end).or().eq("listen_port", controlPort)));
        return count != null && count > 0;
    }

    private boolean ledgerRangeConflict(Long nodeId, int start, int end, int controlPort) {
        PortLedgerQueryDto query = new PortLedgerQueryDto();
        query.setNodeId(nodeId);
        Map<String, Object> result = portLedgerService.list(query);
        @SuppressWarnings("unchecked")
        List<PortLedgerEntryDto> entries = (List<PortLedgerEntryDto>) result.get("entries");
        return entries != null && entries.stream().anyMatch(item -> rangesOverlap(start, end, item.getPortStart(), item.getPortEnd())
                || (controlPort >= item.getPortStart() && controlPort <= item.getPortEnd()));
    }

    private boolean rangesOverlap(int firstStart, int firstEnd, int secondStart, int secondEnd) {
        return firstStart <= secondEnd && firstEnd >= secondStart;
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
        return buildInstallCommand(connector, connector.getPlatform(), false);
    }

    private String buildInstallCommand(InternalConnector connector, String platform, boolean uninstall) {
        ViteConfig config = viteConfigMapper.selectOne(new QueryWrapper<ViteConfig>().eq("name", "ip"));
        if (config == null || StringUtils.isBlank(config.getValue())) return "请先在网站设置中配置面板连接地址";
        return ConnectorInstallCommandUtil.build(platform, config.getValue(), connector.getSecret(), uninstall);
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

    private String conflictSuffix(AgentPortCheckUtil.Result result) {
        return result.getConflicts().isEmpty() ? "" : "（" + String.join("；", result.getConflicts()) + "）";
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
