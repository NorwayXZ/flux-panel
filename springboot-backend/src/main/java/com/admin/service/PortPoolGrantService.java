package com.admin.service;

import com.admin.common.dto.UserPortProvisionDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.PortLease;
import com.admin.entity.PortPool;
import com.admin.entity.PortPoolGrant;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortLeaseMapper;
import com.admin.mapper.PortPoolGrantMapper;
import com.admin.mapper.PortPoolMapper;
import com.admin.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PortPoolGrantService {
    @Resource private PortPoolGrantMapper grantMapper;
    @Resource private PortPoolMapper poolMapper;
    @Resource private PortLeaseMapper leaseMapper;
    @Resource private NodeMapper nodeMapper;
    @Resource private UserMapper userMapper;
    @Resource private JdbcTemplate jdbcTemplate;

    public R validatePermissions(Integer userId, List<UserPortProvisionDto> permissions) {
        List<UserPortProvisionDto> requested = permissions == null ? List.of() : permissions;
        Set<Long> ids = new HashSet<>();
        List<UserPortProvisionDto> checked = new ArrayList<>();
        for (UserPortProvisionDto permission : requested) {
            if (permission == null || permission.getPoolId() == null || permission.getStartPort() == null
                    || permission.getEndPort() == null) {
                return R.err("端口资源配置不完整");
            }
            if (permission.getStartPort() > permission.getEndPort()) return R.err("端口资源起始端口不能大于结束端口");
            if (permission.getId() != null && !ids.add(permission.getId())) return R.err("端口资源配置重复");
            PortPool pool = poolMapper.selectById(permission.getPoolId());
            if (pool == null || pool.getStatus() == null || pool.getStatus() != 1) return R.err("端口池不存在或已停用");
            if (permission.getStartPort() < pool.getStartPort() || permission.getEndPort() > pool.getEndPort()) {
                return R.err("授权端口必须位于端口池 " + pool.getName() + " 的范围内");
            }
            if (permission.getId() != null) {
                PortPoolGrant existing = grantMapper.selectById(permission.getId());
                if (existing == null || userId == null || !Objects.equals(existing.getUserId(), userId)) {
                    return R.err("端口资源授权不存在或不属于该用户");
                }
                if (!Objects.equals(existing.getPoolId(), permission.getPoolId())) return R.err("已分配的端口资源不能更换端口池");
            }
            for (UserPortProvisionDto previous : checked) {
                if (Objects.equals(previous.getPoolId(), permission.getPoolId())
                        && rangesOverlap(previous.getStartPort(), previous.getEndPort(), permission.getStartPort(), permission.getEndPort())) {
                    return R.err("同一用户的端口授权范围不能重叠");
                }
            }
            checked.add(permission);
        }

        List<PortPoolGrant> activeGrants = grantMapper.selectList(new QueryWrapper<PortPoolGrant>().eq("status", 1));
        for (UserPortProvisionDto permission : requested) {
            for (PortPoolGrant grant : activeGrants) {
                if (userId != null && Objects.equals(grant.getUserId(), userId)) continue;
                if (Objects.equals(grant.getPoolId(), permission.getPoolId())
                        && rangesOverlap(grant.getStartPort(), grant.getEndPort(), permission.getStartPort(), permission.getEndPort())) {
                    User owner = userMapper.selectById(grant.getUserId());
                    return R.err("端口范围已分配给用户 " + (owner == null ? grant.getUserId() : owner.getUser()));
                }
            }
            QueryWrapper<PortLease> occupied = new QueryWrapper<PortLease>()
                    .eq("pool_id", permission.getPoolId())
                    .between("port", permission.getStartPort(), permission.getEndPort());
            if (permission.getId() != null) {
                occupied.and(q -> q.isNull("grant_id").or().ne("grant_id", permission.getId()));
            }
            Integer occupiedCount = leaseMapper.selectCount(occupied);
            if (occupiedCount != null && occupiedCount > 0) return R.err("端口范围内已有服务占用，不能分享");
        }

        if (userId != null) {
            Set<Long> retained = requested.stream().map(UserPortProvisionDto::getId).filter(Objects::nonNull).collect(Collectors.toSet());
            List<PortPoolGrant> existing = grantMapper.selectList(new QueryWrapper<PortPoolGrant>().eq("user_id", userId).eq("status", 1));
            for (PortPoolGrant grant : existing) {
                UserPortProvisionDto replacement = requested.stream().filter(item -> Objects.equals(item.getId(), grant.getId())).findFirst().orElse(null);
                if (!retained.contains(grant.getId())) {
                    if (hasActiveLease(grant.getId())) return R.err("端口资源仍被内网映射使用，不能取消分享");
                } else if (replacement != null && hasLeaseOutside(grant.getId(), replacement.getStartPort(), replacement.getEndPort())) {
                    return R.err("新的授权范围不包含正在使用的端口");
                }
            }
        }
        return R.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public void syncPermissions(Integer userId, List<UserPortProvisionDto> permissions) {
        jdbcTemplate.queryForObject("SELECT id FROM service_publish_lock WHERE id=1 FOR UPDATE", Integer.class);
        R validation = validatePermissions(userId, permissions);
        if (validation.getCode() != 0) throw new IllegalStateException(validation.getMsg());
        List<UserPortProvisionDto> requested = permissions == null ? List.of() : permissions;
        Set<Long> retained = requested.stream().map(UserPortProvisionDto::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<PortPoolGrant> existing = grantMapper.selectList(new QueryWrapper<PortPoolGrant>().eq("user_id", userId).eq("status", 1));
        for (PortPoolGrant grant : existing) {
            if (!retained.contains(grant.getId())) grantMapper.deleteById(grant.getId());
        }
        long now = System.currentTimeMillis();
        for (UserPortProvisionDto dto : requested) {
            PortPoolGrant grant = dto.getId() == null ? new PortPoolGrant() : grantMapper.selectById(dto.getId());
            grant.setPoolId(dto.getPoolId());
            grant.setUserId(userId);
            grant.setStartPort(dto.getStartPort());
            grant.setEndPort(dto.getEndPort());
            grant.setStatus(1);
            grant.setUpdatedTime(now);
            if (grant.getId() == null) {
                grant.setCreatedTime(now);
                grantMapper.insert(grant);
            } else {
                grantMapper.updateById(grant);
            }
        }
    }

    public List<PortPoolGrant> listGrants(Integer userId) {
        QueryWrapper<PortPoolGrant> query = new QueryWrapper<PortPoolGrant>().eq("status", 1).orderByDesc("created_time");
        if (userId != null) query.eq("user_id", userId);
        List<PortPoolGrant> grants = grantMapper.selectList(query);
        for (PortPoolGrant grant : grants) enrich(grant);
        return grants;
    }

    public PortPoolGrant usableGrant(Long grantId, Integer userId, Long poolId) {
        if (grantId == null) return null;
        PortPoolGrant grant = grantMapper.selectById(grantId);
        if (grant == null || grant.getStatus() == null || grant.getStatus() != 1
                || !Objects.equals(grant.getUserId(), userId) || !Objects.equals(grant.getPoolId(), poolId)) return null;
        return grant;
    }

    public Set<Integer> grantedPorts(Long poolId) {
        Set<Integer> ports = new HashSet<>();
        for (PortPoolGrant grant : grantMapper.selectList(new QueryWrapper<PortPoolGrant>().eq("pool_id", poolId).eq("status", 1))) {
            for (int port = grant.getStartPort(); port <= grant.getEndPort(); port++) ports.add(port);
        }
        return ports;
    }

    public int sharedPortCount(Long poolId) {
        return grantedPorts(poolId).size();
    }

    private void enrich(PortPoolGrant grant) {
        PortPool pool = poolMapper.selectById(grant.getPoolId());
        User user = userMapper.selectById(grant.getUserId());
        Node node = pool == null ? null : nodeMapper.selectById(pool.getNodeId());
        grant.setPoolName(pool == null ? "端口池已删除" : pool.getName());
        grant.setNodeId(pool == null ? null : pool.getNodeId());
        grant.setNodeName(node == null ? "节点已删除" : node.getName());
        grant.setPublicHost(pool == null ? null : pool.getPublicHost());
        grant.setOwnerUserName(user == null ? "未知用户" : user.getUser());
        int total = grant.getEndPort() - grant.getStartPort() + 1;
        Integer used = leaseMapper.selectCount(new QueryWrapper<PortLease>().eq("grant_id", grant.getId()));
        grant.setTotalPorts(total);
        grant.setUsedPorts(used == null ? 0 : used);
        grant.setAvailablePorts(Math.max(0, total - grant.getUsedPorts()));
    }

    private boolean hasActiveLease(Long grantId) {
        Integer count = leaseMapper.selectCount(new QueryWrapper<PortLease>().eq("grant_id", grantId));
        return count != null && count > 0;
    }

    private boolean hasLeaseOutside(Long grantId, int startPort, int endPort) {
        Integer count = leaseMapper.selectCount(new QueryWrapper<PortLease>().eq("grant_id", grantId)
                .and(q -> q.lt("port", startPort).or().gt("port", endPort)));
        return count != null && count > 0;
    }

    private boolean rangesOverlap(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart <= bEnd && bStart <= aEnd;
    }
}
