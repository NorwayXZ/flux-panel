package com.admin.service;

import com.admin.common.dto.ForwardRouteDto;
import com.admin.common.lang.R;
import com.admin.common.utils.TunnelRouteUtil;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.PrivateProxy;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserNode;
import com.admin.entity.UserTunnel;
import com.admin.entity.HomeProxyRoute;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PrivateProxyMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.UserNodeMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.mapper.HomeProxyRouteMapper;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class UserQuotaService {

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;
    private final UserMapper userMapper;
    private final UserTunnelMapper userTunnelMapper;
    private final UserNodeMapper userNodeMapper;
    private final TunnelMapper tunnelMapper;
    private final NodeMapper nodeMapper;
    private final ForwardMapper forwardMapper;
    private final PrivateProxyMapper privateProxyMapper;
    private final HomeProxyRouteMapper homeProxyRouteMapper;

    public UserQuotaService(UserMapper userMapper, UserTunnelMapper userTunnelMapper,
                            UserNodeMapper userNodeMapper, TunnelMapper tunnelMapper,
                            NodeMapper nodeMapper, ForwardMapper forwardMapper,
                            PrivateProxyMapper privateProxyMapper, HomeProxyRouteMapper homeProxyRouteMapper) {
        this.userMapper = userMapper;
        this.userTunnelMapper = userTunnelMapper;
        this.userNodeMapper = userNodeMapper;
        this.tunnelMapper = tunnelMapper;
        this.nodeMapper = nodeMapper;
        this.forwardMapper = forwardMapper;
        this.privateProxyMapper = privateProxyMapper;
        this.homeProxyRouteMapper = homeProxyRouteMapper;
    }

    public R checkNodeQuota(Integer userId, Node node, Long excludeProxyId) {
        if (node == null) return R.err("节点不存在");
        User user = userMapper.selectById(userId);
        if (user == null || !Objects.equals(user.getStatus(), 1)) return R.err("当前账号已禁用");
        if (user.getExpTime() != null && user.getExpTime() <= System.currentTimeMillis()) return R.err("当前账号已到期");
        if (Objects.equals(node.getOwnerUserId(), userId)) {
            return checkOwnedPool(user, null, excludeProxyId);
        }
        UserNode permission = userNodeMapper.selectOne(new QueryWrapper<UserNode>()
                .eq("user_id", userId).eq("node_id", node.getId()));
        if (permission == null) return R.err("你没有该节点权限");
        return checkNodePermission(permission, node.getName(), null, excludeProxyId);
    }

    public R checkTunnelQuota(Integer userId, Tunnel tunnel, Long excludeForwardId) {
        User user = userMapper.selectById(userId);
        if (user == null || !Objects.equals(user.getStatus(), 1)) return R.err("当前账号已禁用");
        if (user.getExpTime() != null && user.getExpTime() <= System.currentTimeMillis()) return R.err("当前账号已到期");

        if (!Objects.equals(tunnel.getOwnerUserId(), userId)) {
            UserTunnel permission = userTunnelMapper.selectOne(new QueryWrapper<UserTunnel>()
                    .eq("user_id", userId).eq("tunnel_id", tunnel.getId()));
            if (permission == null) return R.err("你没有该隧道权限");
            return checkTunnelPermission(permission, tunnel, excludeForwardId);
        }

        List<UserNode> sharedNodes = sharedNodesOnPath(userId, tunnel);
        if (sharedNodes.isEmpty()) {
            return checkOwnedPool(user, excludeForwardId, null);
        }
        for (UserNode permission : sharedNodes) {
            Node node = nodeMapper.selectById(permission.getNodeId());
            String nodeName = node == null ? "节点" + permission.getNodeId() : node.getName();
            R result = checkNodePermission(permission, nodeName, excludeForwardId, null);
            if (result.getCode() != 0) return result;
        }
        return R.ok();
    }

    public R checkForwardActiveQuota(Forward forward) {
        if (forward == null) return R.err("转发不存在");
        Integer tunnelId = forward.getActiveTunnelId() == null ? forward.getTunnelId() : forward.getActiveTunnelId();
        Tunnel tunnel = tunnelMapper.selectById(tunnelId);
        return tunnel == null ? R.err("当前线路不存在") : checkTunnelQuota(forward.getUserId(), tunnel, forward.getId());
    }

    public void recordResourceFlow(Forward forward, long inFlow, long outFlow) {
        if (forward == null) return;
        Integer tunnelId = forward.getActiveTunnelId() == null ? forward.getTunnelId() : forward.getActiveTunnelId();
        Tunnel tunnel = tunnelMapper.selectById(tunnelId);
        if (tunnel == null) return;

        if (!Objects.equals(tunnel.getOwnerUserId(), forward.getUserId())) {
            UserTunnel permission = userTunnelMapper.selectOne(new QueryWrapper<UserTunnel>()
                    .eq("user_id", forward.getUserId()).eq("tunnel_id", tunnelId));
            if (permission != null) incrementTunnel(permission.getId(), inFlow, outFlow);
            return;
        }

        List<UserNode> sharedNodes = sharedNodesOnPath(forward.getUserId(), tunnel);
        if (sharedNodes.isEmpty()) {
            incrementOwned(forward.getUserId(), inFlow, outFlow);
        } else {
            for (UserNode permission : sharedNodes) incrementNode(permission.getId(), inFlow, outFlow);
        }
    }

    public void recordPrivateProxyFlow(PrivateProxy proxy, long inFlow, long outFlow) {
        if (proxy == null) return;
        Node node = nodeMapper.selectById(proxy.getNodeId());
        if (node == null) return;
        if (Objects.equals(node.getOwnerUserId(), proxy.getUserId())) {
            incrementOwned(proxy.getUserId(), inFlow, outFlow);
            return;
        }
        UserNode permission = userNodeMapper.selectOne(new QueryWrapper<UserNode>()
                .eq("user_id", proxy.getUserId()).eq("node_id", proxy.getNodeId()));
        if (permission != null) incrementNode(permission.getId(), inFlow, outFlow);
    }

    public UserTunnel getTunnelPermission(Integer userId, Integer tunnelId) {
        return userTunnelMapper.selectOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId).eq("tunnel_id", tunnelId));
    }

    public int countForwardsUsingTunnel(Integer userId, Integer tunnelId, Long excludeForwardId) {
        int count = 0;
        for (Forward forward : userForwards(userId, excludeForwardId)) {
            if (routeTunnelIds(forward).contains(tunnelId)) count++;
        }
        count += homeProxyRouteMapper.selectCount(new QueryWrapper<HomeProxyRoute>()
                .eq("user_id", userId).eq("egress_tunnel_id", tunnelId)
                .notIn("state", "deleted", "error"));
        return count;
    }

    private R checkTunnelPermission(UserTunnel permission, Tunnel tunnel, Long excludeForwardId) {
        if (!Objects.equals(permission.getStatus(), 1)) return R.err("隧道权限已禁用：" + tunnel.getName());
        if (permission.getExpTime() != null && permission.getExpTime() <= System.currentTimeMillis()) {
            return R.err("隧道权限已到期：" + tunnel.getName());
        }
        long used = value(permission.getInFlow()) + value(permission.getOutFlow());
        if (!isUnlimited(permission.getFlowUnlimited()) && used >= value(permission.getFlow()) * BYTES_TO_GB) {
            return R.err("隧道流量额度已用尽：" + tunnel.getName());
        }
        int usedForwards = countForwardsUsingTunnel(permission.getUserId(), permission.getTunnelId(), excludeForwardId);
        if (!isUnlimited(permission.getForwardUnlimited()) && usedForwards >= intValue(permission.getNum())) {
            return R.err("隧道转发名额已用尽：" + tunnel.getName());
        }
        return R.ok();
    }

    private R checkNodePermission(UserNode permission, String nodeName, Long excludeForwardId, Long excludeProxyId) {
        if (!Objects.equals(permission.getStatus(), 1)) return R.err("共享节点权限已禁用：" + nodeName);
        if (permission.getExpTime() != null && permission.getExpTime() <= System.currentTimeMillis()) {
            return R.err("共享节点权限已到期：" + nodeName);
        }
        long used = value(permission.getInFlow()) + value(permission.getOutFlow());
        if (!isUnlimited(permission.getFlowUnlimited()) && used >= value(permission.getFlow()) * BYTES_TO_GB) {
            return R.err("共享节点流量额度已用尽：" + nodeName);
        }
        int usedForwards = countForwardsUsingNode(permission.getUserId(), permission.getNodeId(), excludeForwardId);
        if (excludeProxyId != null) {
            usedForwards -= privateProxyMapper.selectCount(new QueryWrapper<PrivateProxy>()
                    .eq("id", excludeProxyId).eq("user_id", permission.getUserId())
                    .eq("node_id", permission.getNodeId()).notIn("state", "deleted", "expired", "error"));
        }
        if (!isUnlimited(permission.getForwardUnlimited()) && usedForwards >= intValue(permission.getNum())) {
            return R.err("共享节点转发名额已用尽：" + nodeName);
        }
        return R.ok();
    }

    private R checkOwnedPool(User user, Long excludeForwardId, Long excludeProxyId) {
        long used = value(user.getOwnedInFlow()) + value(user.getOwnedOutFlow());
        if (!isUnlimited(user.getFlowUnlimited()) && used >= value(user.getFlow()) * BYTES_TO_GB) {
            return R.err("自有资源流量额度已用尽");
        }
        int usedForwards = countOwnedPoolForwards(user.getId().intValue(), excludeForwardId)
                + countPrivateProxies(user.getId().intValue(), null, excludeProxyId);
        if (!isUnlimited(user.getForwardUnlimited()) && usedForwards >= intValue(user.getNum())) {
            return R.err("自有资源转发名额已用尽");
        }
        return R.ok();
    }

    private List<UserNode> sharedNodesOnPath(Integer userId, Tunnel tunnel) {
        List<Long> path = TunnelRouteUtil.parseNodePath(tunnel);
        if (path.isEmpty()) return Collections.emptyList();
        return userNodeMapper.selectList(new QueryWrapper<UserNode>()
                .eq("user_id", userId).in("node_id", path));
    }

    public int countForwardsUsingNode(Integer userId, Integer nodeId, Long excludeForwardId) {
        return countForwardsUsingNodes(userId, Collections.singleton(nodeId), excludeForwardId)
                .getOrDefault(nodeId, 0);
    }

    public Map<Integer, Integer> countForwardsUsingNodes(Integer userId, Collection<Integer> nodeIds,
                                                         Long excludeForwardId) {
        if (nodeIds == null || nodeIds.isEmpty()) return Collections.emptyMap();
        Set<Integer> requestedNodeIds = new HashSet<>(nodeIds);
        Map<Integer, Integer> counts = new HashMap<>();
        for (Forward forward : userForwards(userId, excludeForwardId)) {
            Set<Integer> usedByForward = new HashSet<>();
            for (Integer tunnelId : routeTunnelIds(forward)) {
                Tunnel tunnel = tunnelMapper.selectById(tunnelId);
                if (tunnel == null) continue;
                for (Long pathNodeId : TunnelRouteUtil.parseNodePath(tunnel)) {
                    int nodeId = pathNodeId.intValue();
                    if (requestedNodeIds.contains(nodeId)) usedByForward.add(nodeId);
                }
            }
            usedByForward.forEach(nodeId -> counts.merge(nodeId, 1, Integer::sum));
        }
        for (PrivateProxy proxy : privateProxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .eq("user_id", userId).in("node_id", requestedNodeIds)
                .notIn("state", "deleted", "expired", "error"))) {
            counts.merge(proxy.getNodeId().intValue(), 1, Integer::sum);
        }
        for (HomeProxyRoute route : activeHomeTunnelRoutes(userId)) {
            Tunnel tunnel = tunnelMapper.selectById(route.getEgressTunnelId());
            if (tunnel == null) continue;
            Set<Integer> usedByRoute = TunnelRouteUtil.parseNodePath(tunnel).stream()
                    .map(Long::intValue).filter(requestedNodeIds::contains).collect(java.util.stream.Collectors.toSet());
            usedByRoute.forEach(nodeId -> counts.merge(nodeId, 1, Integer::sum));
        }
        return counts;
    }

    private int countOwnedPoolForwards(Integer userId, Long excludeForwardId) {
        int count = 0;
        for (Forward forward : userForwards(userId, excludeForwardId)) {
            for (Integer tunnelId : routeTunnelIds(forward)) {
                Tunnel tunnel = tunnelMapper.selectById(tunnelId);
                if (tunnel != null && Objects.equals(tunnel.getOwnerUserId(), userId)
                        && sharedNodesOnPath(userId, tunnel).isEmpty()) {
                    count++;
                    break;
                }
            }
        }
        for (HomeProxyRoute route : activeHomeTunnelRoutes(userId)) {
            Tunnel tunnel = tunnelMapper.selectById(route.getEgressTunnelId());
            if (tunnel != null && Objects.equals(tunnel.getOwnerUserId(), userId)
                    && sharedNodesOnPath(userId, tunnel).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private List<HomeProxyRoute> activeHomeTunnelRoutes(Integer userId) {
        return homeProxyRouteMapper.selectList(new QueryWrapper<HomeProxyRoute>()
                .eq("user_id", userId).isNotNull("egress_tunnel_id")
                .notIn("state", "deleted", "error"));
    }

    private List<Forward> userForwards(Integer userId, Long excludeForwardId) {
        QueryWrapper<Forward> query = new QueryWrapper<Forward>().eq("user_id", userId);
        if (excludeForwardId != null) query.ne("id", excludeForwardId);
        return forwardMapper.selectList(query);
    }

    private int countPrivateProxies(Integer userId, Integer nodeId, Long excludeProxyId) {
        QueryWrapper<PrivateProxy> query = new QueryWrapper<PrivateProxy>()
                .eq("user_id", userId).notIn("state", "deleted", "expired", "error");
        if (nodeId != null) query.eq("node_id", nodeId);
        if (excludeProxyId != null) query.ne("id", excludeProxyId);
        Integer count = privateProxyMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    private Set<Integer> routeTunnelIds(Forward forward) {
        Set<Integer> ids = new HashSet<>();
        if (forward.getTunnelId() != null) ids.add(forward.getTunnelId());
        if (forward.getRouteConfig() == null || forward.getRouteConfig().trim().isEmpty()) return ids;
        try {
            List<ForwardRouteDto> routes = JSON.parseArray(forward.getRouteConfig(), ForwardRouteDto.class);
            if (routes != null) routes.stream().map(ForwardRouteDto::getTunnelId).filter(Objects::nonNull).forEach(ids::add);
        } catch (Exception ignored) {
            // Legacy records can have no route JSON; the primary tunnel remains authoritative.
        }
        return ids;
    }

    private void incrementTunnel(Integer id, long inFlow, long outFlow) {
        userTunnelMapper.update(null, increments("id", id, "in_flow", "out_flow", inFlow, outFlow));
    }

    private void incrementNode(Integer id, long inFlow, long outFlow) {
        userNodeMapper.update(null, increments("id", id, "in_flow", "out_flow", inFlow, outFlow));
    }

    private void incrementOwned(Integer userId, long inFlow, long outFlow) {
        userMapper.update(null, increments("id", userId, "owned_in_flow", "owned_out_flow", inFlow, outFlow));
    }

    private <T> UpdateWrapper<T> increments(String idColumn, Object id, String inColumn, String outColumn,
                                             long inFlow, long outFlow) {
        UpdateWrapper<T> update = new UpdateWrapper<>();
        update.eq(idColumn, id);
        update.setSql(inColumn + " = " + inColumn + " + " + Math.max(0, inFlow));
        update.setSql(outColumn + " = " + outColumn + " + " + Math.max(0, outFlow));
        return update;
    }

    private boolean isUnlimited(Integer value) { return Objects.equals(value, 1); }
    private long value(Long value) { return value == null ? 0L : value; }
    private int intValue(Integer value) { return value == null ? 0 : value; }
}
