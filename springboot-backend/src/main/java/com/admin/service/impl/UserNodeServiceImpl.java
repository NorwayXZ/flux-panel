package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.entity.UserNode;
import com.admin.mapper.UserNodeMapper;
import com.admin.service.NodeService;
import com.admin.service.UserNodeService;
import com.admin.service.UserService;
import com.admin.service.UserQuotaService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserNodeServiceImpl extends ServiceImpl<UserNodeMapper, UserNode> implements UserNodeService {

    @Resource
    @Lazy
    private NodeService nodeService;

    @Resource
    private UserService userService;

    @Resource
    private UserQuotaService userQuotaService;

    @Override
    public R assign(Integer userId, Integer nodeId) {
        User user = userService.getById(userId);
        Node node = nodeService.getById(nodeId);
        if (user == null || user.getRoleId() == 0) {
            return R.err("用户不存在");
        }
        if (node == null) {
            return R.err("节点不存在");
        }
        User owner = userService.getById(node.getOwnerUserId());
        if (owner == null || owner.getRoleId() != 0) {
            return R.err("只能共享管理员创建的节点");
        }
        long exists = this.count(new QueryWrapper<UserNode>()
                .eq("user_id", userId)
                .eq("node_id", nodeId));
        if (exists > 0) {
            return R.err("该用户已经拥有此节点权限");
        }

        UserNode permission = new UserNode();
        permission.setUserId(userId);
        permission.setNodeId(nodeId);
        permission.setCreatedTime(System.currentTimeMillis());
        permission.setFlow(0L);
        permission.setInFlow(0L);
        permission.setOutFlow(0L);
        permission.setFlowUnlimited(1);
        permission.setNum(0);
        permission.setForwardUnlimited(1);
        permission.setFlowResetTime(0L);
        permission.setStatus(1);
        return this.save(permission) ? R.ok("节点共享成功") : R.err("节点共享失败");
    }

    @Override
    public R listByUser(Integer userId) {
        List<UserNode> permissions = this.list(new QueryWrapper<UserNode>().eq("user_id", userId));
        Map<Integer, Integer> forwardUsage = userQuotaService.countForwardsUsingNodes(
                userId,
                permissions.stream().map(UserNode::getNodeId).collect(Collectors.toSet()),
                null
        );
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserNode permission : permissions) {
            Node node = nodeService.getById(permission.getNodeId());
            if (node == null) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("id", permission.getId());
            item.put("userId", permission.getUserId());
            item.put("nodeId", permission.getNodeId());
            item.put("nodeName", node.getName());
            item.put("status", node.getStatus());
            item.put("serverIp", node.getServerIp());
            item.put("createdTime", permission.getCreatedTime());
            item.put("flow", permission.getFlow());
            item.put("inFlow", permission.getInFlow());
            item.put("outFlow", permission.getOutFlow());
            item.put("flowUnlimited", permission.getFlowUnlimited());
            item.put("num", permission.getNum());
            item.put("forwardUnlimited", permission.getForwardUnlimited());
            item.put("usedForwards", forwardUsage.getOrDefault(permission.getNodeId(), 0));
            item.put("flowResetTime", permission.getFlowResetTime());
            item.put("expTime", permission.getExpTime());
            item.put("permissionStatus", permission.getStatus());
            result.add(item);
        }
        return R.ok(result);
    }

    @Override
    public R removeAccess(Integer userId, Integer nodeId) {
        boolean removed = this.remove(new QueryWrapper<UserNode>()
                .eq("user_id", userId)
                .eq("node_id", nodeId));
        return removed ? R.ok("节点权限已移除") : R.err("节点权限不存在");
    }
}
