package com.admin.service;

import com.admin.common.dto.ForwardRouteDto;
import com.admin.common.dto.PortLedgerEntryDto;
import com.admin.common.dto.PortLedgerQueryDto;
import com.admin.common.utils.PortNamespaceUtil;
import com.admin.common.utils.TunnelRouteUtil;
import com.admin.entity.Forward;
import com.admin.entity.DomainRoute;
import com.admin.entity.Node;
import com.admin.entity.PortLease;
import com.admin.entity.PortPool;
import com.admin.entity.PortPoolGrant;
import com.admin.entity.PublishedService;
import com.admin.entity.PrivateProxy;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.DomainRouteMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortLeaseMapper;
import com.admin.mapper.PortPoolGrantMapper;
import com.admin.mapper.PortPoolMapper;
import com.admin.mapper.PublishedServiceMapper;
import com.admin.mapper.PrivateProxyMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PortLedgerService {
    @Resource private NodeMapper nodeMapper;
    @Resource private TunnelMapper tunnelMapper;
    @Resource private ForwardMapper forwardMapper;
    @Resource private PortPoolMapper poolMapper;
    @Resource private PortPoolGrantMapper grantMapper;
    @Resource private PortLeaseMapper leaseMapper;
    @Resource private PublishedServiceMapper serviceMapper;
    @Resource private UserMapper userMapper;
    @Resource private DomainRouteMapper domainRouteMapper;
    @Resource private PrivateProxyMapper privateProxyMapper;

    public Map<String, Object> list(PortLedgerQueryDto query) {
        List<Node> nodes = nodeMapper.selectList(null);
        Map<Long, Node> nodeMap = nodes.stream().collect(Collectors.toMap(Node::getId, Function.identity(), (a, b) -> a));
        Map<Long, Tunnel> tunnelMap = tunnelMapper.selectList(null).stream()
                .collect(Collectors.toMap(Tunnel::getId, Function.identity(), (a, b) -> a));
        Map<Integer, User> userMap = userMapper.selectList(null).stream()
                .collect(Collectors.toMap(user -> user.getId().intValue(), Function.identity(), (a, b) -> a));
        List<PortPool> pools = poolMapper.selectList(new QueryWrapper<PortPool>().eq("status", 1));
        Map<Long, PortPool> poolMap = pools.stream().collect(Collectors.toMap(PortPool::getId, Function.identity()));
        Map<Long, PublishedService> publishedMap = serviceMapper.selectList(null).stream()
                .collect(Collectors.toMap(PublishedService::getId, Function.identity(), (a, b) -> a));

        List<PortLedgerEntryDto> entries = new ArrayList<>();
        addForwards(entries, nodeMap, tunnelMap);
        addPools(entries, nodeMap, pools);
        addGrants(entries, nodeMap, poolMap, userMap);
        addLeases(entries, nodeMap, poolMap, publishedMap, userMap);
        addDomainIngresses(entries, nodeMap, userMap);
        addPrivateProxies(entries, nodeMap, userMap);

        String namespaceFilter = null;
        if (query != null && query.getNodeId() != null && nodeMap.containsKey(query.getNodeId())) {
            namespaceFilter = PortNamespaceUtil.fromNode(nodeMap.get(query.getNodeId()));
        }
        final String selectedNamespace = namespaceFilter;
        final Integer selectedPort = query == null ? null : query.getPort();
        final String selectedType = query == null ? null : StringUtils.trimToNull(query.getType());
        final String keyword = query == null ? null : StringUtils.trimToNull(query.getKeyword());
        entries = entries.stream()
                .filter(item -> selectedNamespace == null || Objects.equals(selectedNamespace, item.getNamespace()))
                .filter(item -> selectedPort == null || (selectedPort >= item.getPortStart() && selectedPort <= item.getPortEnd()))
                .filter(item -> selectedType == null || Objects.equals(selectedType, item.getType()))
                .filter(item -> matchesKeyword(item, keyword))
                .sorted(Comparator.comparing(PortLedgerEntryDto::getServerAddress, Comparator.nullsLast(String::compareTo))
                        .thenComparing(PortLedgerEntryDto::getPortStart)
                        .thenComparing(PortLedgerEntryDto::getType))
                .collect(Collectors.toList());

        Map<String, Long> summary = entries.stream().collect(Collectors.groupingBy(PortLedgerEntryDto::getStatus, LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", entries);
        result.put("summary", summary);
        result.put("total", entries.size());
        return result;
    }

    public Map<String, Object> diagnose(Long nodeId, Integer port) {
        PortLedgerQueryDto query = new PortLedgerQueryDto();
        query.setNodeId(nodeId);
        query.setPort(port);
        Map<String, Object> result = list(query);
        @SuppressWarnings("unchecked")
        List<PortLedgerEntryDto> entries = (List<PortLedgerEntryDto>) result.get("entries");
        result.put("occupied", !entries.isEmpty());
        result.put("port", port);
        result.put("nodeId", nodeId);
        return result;
    }

    private void addForwards(List<PortLedgerEntryDto> entries, Map<Long, Node> nodes, Map<Long, Tunnel> tunnels) {
        List<Forward> forwards = forwardMapper.selectList(new QueryWrapper<Forward>().ne("status", -1));
        for (Forward forward : forwards) {
            Tunnel primary = tunnels.get(Long.valueOf(forward.getTunnelId()));
            if (primary == null) continue;
            add(entries, nodeEntry("forward_entry", "occupied", nodes.get(primary.getInNodeId()), forward.getInPort(), forward.getInPort(),
                    forward.getProtocolMode(), forward.getUserId(), forward.getUserName(), forward.getId(), forward.getName(),
                    "转发入口 · " + primary.getName(), forward.getCreatedTime(), null));

            for (ForwardRouteDto route : routes(forward, primary)) {
                Tunnel tunnel = tunnels.get(Long.valueOf(route.getTunnelId()));
                if (tunnel == null) continue;
                List<Long> path = TunnelRouteUtil.parseNodePath(tunnel);
                List<Integer> hopPorts = TunnelRouteUtil.parseHopPorts(route.getHopPorts());
                for (int i = 1; i < path.size() && i - 1 < hopPorts.size(); i++) {
                    String protocol = "quic".equalsIgnoreCase(tunnel.getProtocol()) ? "udp" : "tcp";
                    add(entries, nodeEntry("tunnel_hop", "occupied", nodes.get(path.get(i)), hopPorts.get(i - 1), hopPorts.get(i - 1),
                            protocol, forward.getUserId(), forward.getUserName(), forward.getId(), forward.getName(),
                            "隧道跳点 " + i + " · " + tunnel.getName(), forward.getCreatedTime(), null));
                }
            }
        }
    }

    private void addPools(List<PortLedgerEntryDto> entries, Map<Long, Node> nodes, List<PortPool> pools) {
        for (PortPool pool : pools) {
            add(entries, nodeEntry("pool_range", "reserved", nodes.get(pool.getNodeId()), pool.getStartPort(), pool.getEndPort(),
                    "tcp_udp", 1, "admin", pool.getId(), pool.getName(), "内网映射端口池", pool.getCreatedTime(), null));
            add(entries, nodeEntry("pool_control", "occupied", nodes.get(pool.getNodeId()), pool.getControlPort(), pool.getControlPort(),
                    "tcp", 1, "admin", pool.getId(), pool.getName(), "反向连接控制端口", pool.getCreatedTime(), null));
        }
    }

    private void addGrants(List<PortLedgerEntryDto> entries, Map<Long, Node> nodes, Map<Long, PortPool> pools, Map<Integer, User> users) {
        for (PortPoolGrant grant : grantMapper.selectList(new QueryWrapper<PortPoolGrant>().eq("status", 1))) {
            PortPool pool = pools.get(grant.getPoolId());
            if (pool == null) continue;
            User owner = users.get(grant.getUserId());
            add(entries, nodeEntry("user_grant", "granted", nodes.get(pool.getNodeId()), grant.getStartPort(), grant.getEndPort(),
                    "tcp_udp", grant.getUserId(), owner == null ? "未知用户" : owner.getUser(), grant.getId(), pool.getName(),
                    "用户独占授权范围", grant.getCreatedTime(), null));
        }
    }

    private void addLeases(List<PortLedgerEntryDto> entries, Map<Long, Node> nodes, Map<Long, PortPool> pools,
                           Map<Long, PublishedService> services, Map<Integer, User> users) {
        for (PortLease lease : leaseMapper.selectList(null)) {
            if ("released".equals(lease.getState())) continue;
            PortPool pool = pools.get(lease.getPoolId());
            if (pool == null) continue;
            PublishedService service = lease.getServiceId() == null ? null : services.get(lease.getServiceId());
            User owner = users.get(lease.getUserId());
            String status = "cooldown".equals(lease.getState()) ? "cooldown" : "occupied";
            add(entries, nodeEntry("published_service", status, nodes.get(pool.getNodeId()), lease.getPort(), lease.getPort(),
                    lease.getProtocol(), lease.getUserId(), owner == null ? "未知用户" : owner.getUser(),
                    service == null ? lease.getId() : service.getId(), service == null ? "服务记录已删除" : service.getName(),
                    service == null ? "内网映射端口" : service.getTargetHost() + ":" + service.getTargetPort(),
                    lease.getCreatedTime(), lease.getExpiresAt()));
        }
    }

    private void addDomainIngresses(List<PortLedgerEntryDto> entries, Map<Long, Node> nodes, Map<Integer, User> users) {
        Map<String, List<DomainRoute>> groups = domainRouteMapper.selectList(new QueryWrapper<DomainRoute>().ne("state", "deleted"))
                .stream().collect(Collectors.groupingBy(item -> item.getNodeId() + ":" + item.getListenPort(), LinkedHashMap::new, Collectors.toList()));
        for (List<DomainRoute> routes : groups.values()) {
            if (routes.isEmpty()) continue;
            DomainRoute first = routes.get(0);
            User owner = users.get(first.getUserId());
            String ownerName = routes.stream().map(DomainRoute::getUserId).distinct().count() > 1
                    ? "多个用户" : owner == null ? "未知用户" : owner.getUser();
            String domains = routes.stream().map(DomainRoute::getDomain).limit(3).collect(Collectors.joining("、"));
            if (routes.size() > 3) domains += " 等 " + routes.size() + " 个域名";
            add(entries, nodeEntry("domain_ingress", "occupied", nodes.get(first.getNodeId()),
                    first.getListenPort(), first.getListenPort(), "tcp", first.getUserId(), ownerName,
                    first.getId(), "TLS 域名入口", domains, first.getCreatedTime(), null));
        }
    }

    private void addPrivateProxies(List<PortLedgerEntryDto> entries, Map<Long, Node> nodes, Map<Integer, User> users) {
        for (PrivateProxy proxy : privateProxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .notIn("state", "deleted", "expired", "error"))) {
            User owner = users.get(proxy.getUserId());
            String detail = ("http".equals(proxy.getProxyType()) ? "HTTP" : "SOCKS5") + " 私人代理";
            add(entries, nodeEntry("private_proxy", "occupied", nodes.get(proxy.getNodeId()),
                    proxy.getListenPort(), proxy.getListenPort(), "tcp", proxy.getUserId(),
                    owner == null ? "未知用户" : owner.getUser(), proxy.getId(), proxy.getName(), detail,
                    proxy.getCreatedTime(), proxy.getExpiresAt()));
        }
    }

    private ForwardRouteDto fallbackRoute(Forward forward, Tunnel tunnel) {
        ForwardRouteDto route = new ForwardRouteDto();
        route.setTunnelId(forward.getTunnelId());
        route.setTunnelName(tunnel.getName());
        route.setOutPort(forward.getOutPort());
        route.setHopPorts(forward.getHopPorts());
        return route;
    }

    private List<ForwardRouteDto> routes(Forward forward, Tunnel primary) {
        if (StringUtils.isBlank(forward.getRouteConfig())) return List.of(fallbackRoute(forward, primary));
        try {
            List<ForwardRouteDto> routes = JSON.parseArray(forward.getRouteConfig(), ForwardRouteDto.class);
            return routes == null || routes.isEmpty() ? List.of(fallbackRoute(forward, primary)) : routes;
        } catch (Exception ignored) {
            return List.of(fallbackRoute(forward, primary));
        }
    }

    private PortLedgerEntryDto nodeEntry(String type, String status, Node node, Integer start, Integer end, String protocol,
                                         Integer ownerId, String ownerName, Long resourceId, String resourceName, String detail,
                                         Long createdTime, Long expiresAt) {
        if (node == null || start == null || end == null) return null;
        PortLedgerEntryDto entry = new PortLedgerEntryDto();
        entry.setKey(type + ":" + resourceId + ":" + node.getId() + ":" + start + ":" + end);
        entry.setType(type);
        entry.setStatus(status);
        entry.setNodeId(node.getId());
        entry.setNodeName(node.getName());
        entry.setNamespace(PortNamespaceUtil.fromNode(node));
        entry.setServerAddress(StringUtils.defaultIfBlank(node.getServerIp(), node.getIp()));
        entry.setPortStart(start);
        entry.setPortEnd(end);
        entry.setProtocol(StringUtils.defaultIfBlank(protocol, "tcp"));
        entry.setOwnerUserId(ownerId);
        entry.setOwnerUserName(StringUtils.defaultIfBlank(ownerName, "未知用户"));
        entry.setResourceId(resourceId);
        entry.setResourceName(resourceName);
        entry.setDetail(detail);
        entry.setCreatedTime(createdTime);
        entry.setExpiresAt(expiresAt);
        return entry;
    }

    private void add(List<PortLedgerEntryDto> entries, PortLedgerEntryDto entry) {
        if (entry != null) entries.add(entry);
    }

    private boolean matchesKeyword(PortLedgerEntryDto item, String keyword) {
        if (keyword == null) return true;
        String needle = keyword.toLowerCase(Locale.ROOT);
        return String.join(" ", StringUtils.defaultString(item.getNodeName()), StringUtils.defaultString(item.getServerAddress()),
                        StringUtils.defaultString(item.getOwnerUserName()), StringUtils.defaultString(item.getResourceName()),
                        StringUtils.defaultString(item.getDetail()))
                .toLowerCase(Locale.ROOT).contains(needle);
    }
}
