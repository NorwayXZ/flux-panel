package com.admin.service;

import com.admin.common.dto.ForwardRouteDto;
import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.PrivateProxy;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserNode;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PrivateProxyMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.UserNodeMapper;
import com.admin.mapper.UserTunnelMapper;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserQuotaServiceTests {

    @Mock private UserMapper userMapper;
    @Mock private UserTunnelMapper userTunnelMapper;
    @Mock private UserNodeMapper userNodeMapper;
    @Mock private TunnelMapper tunnelMapper;
    @Mock private NodeMapper nodeMapper;
    @Mock private ForwardMapper forwardMapper;
    @Mock private PrivateProxyMapper privateProxyMapper;

    @InjectMocks private UserQuotaService userQuotaService;

    @Test
    void rejectsOwnedTunnelWhenAnySharedPathNodeHasNoForwardSlots() {
        User user = activeUser(7);
        Tunnel tunnel = tunnel(44, 7, "12,18,23");
        UserNode first = permission(7, 12, true, 0);
        UserNode second = permission(7, 18, false, 1);
        UserNode third = permission(7, 23, false, 10);
        Forward existing = forward(90L, 7, 44);

        when(userMapper.selectById(7)).thenReturn(user);
        when(userNodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second, third));
        when(nodeMapper.selectById(12)).thenReturn(node(12L, "入口"));
        when(nodeMapper.selectById(18)).thenReturn(node(18L, "中继"));
        when(forwardMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(tunnelMapper.selectById(44)).thenReturn(tunnel);

        R result = userQuotaService.checkTunnelQuota(7, tunnel, null);

        assertTrue(result.getCode() != 0);
        assertTrue(result.getMsg().contains("共享节点转发名额已用尽：中继"));
    }

    @Test
    void countsOneSlotPerForwardEvenWhenMultipleCandidateRoutesUseSameNode() {
        Tunnel primary = tunnel(44, 7, "12,18,23");
        Tunnel backup = tunnel(45, 7, "12,19,23");
        Forward forward = forward(90L, 7, 44);
        ForwardRouteDto primaryRoute = new ForwardRouteDto();
        primaryRoute.setTunnelId(44);
        ForwardRouteDto backupRoute = new ForwardRouteDto();
        backupRoute.setTunnelId(45);
        forward.setRouteConfig(JSON.toJSONString(List.of(primaryRoute, backupRoute)));

        when(forwardMapper.selectList(any(Wrapper.class))).thenReturn(List.of(forward));
        when(tunnelMapper.selectById(44)).thenReturn(primary);
        when(tunnelMapper.selectById(45)).thenReturn(backup);

        assertEquals(1, userQuotaService.countForwardsUsingNode(7, 12, null));
        assertEquals(1, userQuotaService.countForwardsUsingNode(7, 23, null));
        assertEquals(1, userQuotaService.countForwardsUsingNode(7, 19, null));
    }

    @Test
    void countsPrivateProxyAsOneSharedNodeForwardSlot() {
        User user = activeUser(7);
        Node sharedNode = node(12L, "共享入口");
        sharedNode.setOwnerUserId(1);
        UserNode permission = permission(7, 12, false, 1);
        PrivateProxy proxy = new PrivateProxy();
        proxy.setId(55L);
        proxy.setUserId(7);
        proxy.setNodeId(12L);
        proxy.setState("active");

        when(userMapper.selectById(7)).thenReturn(user);
        when(userNodeMapper.selectOne(any(Wrapper.class))).thenReturn(permission);
        when(forwardMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(privateProxyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(proxy));

        R result = userQuotaService.checkNodeQuota(7, sharedNode, null);

        assertTrue(result.getCode() != 0);
        assertTrue(result.getMsg().contains("共享节点转发名额已用尽"));
    }

    private User activeUser(int id) {
        User user = new User();
        user.setId((long) id);
        user.setStatus(1);
        return user;
    }

    private UserNode permission(int userId, int nodeId, boolean unlimited, int limit) {
        UserNode permission = new UserNode();
        permission.setUserId(userId);
        permission.setNodeId(nodeId);
        permission.setStatus(1);
        permission.setFlowUnlimited(1);
        permission.setForwardUnlimited(unlimited ? 1 : 0);
        permission.setNum(limit);
        return permission;
    }

    private Tunnel tunnel(int id, int ownerUserId, String path) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) id);
        tunnel.setName("线路-" + id);
        tunnel.setOwnerUserId(ownerUserId);
        tunnel.setNodePath(path);
        tunnel.setInNodeId(Long.valueOf(path.split(",")[0]));
        String[] nodes = path.split(",");
        tunnel.setOutNodeId(Long.valueOf(nodes[nodes.length - 1]));
        tunnel.setType(nodes.length == 1 ? 1 : 2);
        return tunnel;
    }

    private Forward forward(long id, int userId, int tunnelId) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId(userId);
        forward.setTunnelId(tunnelId);
        return forward;
    }

    private Node node(long id, String name) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        return node;
    }
}
