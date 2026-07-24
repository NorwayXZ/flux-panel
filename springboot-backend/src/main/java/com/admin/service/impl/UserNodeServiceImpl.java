package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.entity.UserNode;
import com.admin.mapper.UserNodeMapper;
import com.admin.service.NodeService;
import com.admin.service.UserNodeService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserNodeServiceImpl extends ServiceImpl<UserNodeMapper, UserNode> implements UserNodeService {

    @Resource
    @Lazy
    private NodeService nodeService;

    @Resource
    private UserService userService;

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
        return this.save(permission) ? R.ok("节点共享成功") : R.err("节点共享失败");
    }

    @Override
    public R listByUser(Integer userId) {
        List<UserNode> permissions = this.list(new QueryWrapper<UserNode>().eq("user_id", userId));
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
