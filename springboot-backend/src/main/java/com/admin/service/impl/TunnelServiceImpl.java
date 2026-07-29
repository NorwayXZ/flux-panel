package com.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.*;

import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.TunnelRouteUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.SpeedLimit;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.entity.UserNode;
import com.admin.entity.HomeProxyRoute;
import com.admin.mapper.HomeProxyRouteMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.mapper.UserNodeMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.UserQuotaService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 隧道服务实现类
 * 提供隧道的增删改查功能，包括隧道创建、删除和用户权限管理
 * 支持端口转发和隧道转发两种模式
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Service
public class TunnelServiceImpl extends ServiceImpl<TunnelMapper, Tunnel> implements TunnelService {

    // ========== 常量定义 ==========
    
    /** 隧道类型常量 */
    private static final int TUNNEL_TYPE_PORT_FORWARD = 1;  // 端口转发
    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2; // 隧道转发
    
    /** 隧道状态常量 */
    private static final int TUNNEL_STATUS_ACTIVE = 1;      // 启用状态
    
    /** 节点状态常量 */
    private static final int NODE_STATUS_ONLINE = 1;        // 节点在线状态
    
    /** 用户角色常量 */
    private static final int ADMIN_ROLE_ID = 0;             // 管理员角色ID
    
    /** 成功响应消息 */
    private static final String SUCCESS_CREATE_MSG = "隧道创建成功";
    private static final String SUCCESS_DELETE_MSG = "隧道删除成功";
    
    /** 错误响应消息 */
    private static final String ERROR_CREATE_MSG = "隧道创建失败";
    private static final String ERROR_DELETE_MSG = "隧道删除失败";
    private static final String ERROR_TUNNEL_NOT_FOUND = "隧道不存在";
    private static final String ERROR_TUNNEL_NAME_EXISTS = "隧道名称已存在";
    private static final String ERROR_IN_NODE_NOT_FOUND = "入口节点不存在";
    private static final String ERROR_OUT_NODE_NOT_FOUND = "出口节点不存在";
    private static final String ERROR_OUT_NODE_REQUIRED = "出口节点不能为空";
    private static final String ERROR_OUT_PORT_REQUIRED = "出口端口不能为空";
    private static final String ERROR_SAME_NODE_NOT_ALLOWED = "隧道转发模式下，入口和出口不能是同一个节点";
    private static final String ERROR_NODE_PATH_REQUIRED = "隧道转发路径至少需要两个节点";
    private static final String ERROR_NODE_PATH_DUPLICATED = "隧道转发路径不能包含重复节点";
    private static final String ERROR_IN_PORT_RANGE_INVALID = "入口端口开始不能大于结束端口";
    private static final String ERROR_OUT_PORT_RANGE_INVALID = "出口端口开始不能大于结束端口";
    private static final String ERROR_NO_AVAILABLE_TUNNELS = "暂无可用隧道";
    private static final String ERROR_IN_NODE_OFFLINE = "入口节点当前离线，请确保节点正常运行";
    private static final String ERROR_OUT_NODE_OFFLINE = "出口节点当前离线，请确保节点正常运行";
    
    /** 使用检查相关消息 */
    private static final String ERROR_FORWARDS_IN_USE = "该隧道还有 %d 个转发在使用，请先删除相关转发";
    private static final String ERROR_USER_PERMISSIONS_IN_USE = "该隧道还有 %d 个用户权限关联，请先取消用户权限分配";

    // ========== 依赖注入 ==========
    
    @Resource
    UserTunnelMapper userTunnelMapper;

    @Resource
    UserNodeMapper userNodeMapper;

    @Resource
    UserMapper userMapper;

    @Resource
    HomeProxyRouteMapper homeProxyRouteMapper;

    @Resource
    NodeService nodeService;
    
    @Resource
    ForwardService forwardService;
    
    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserQuotaService userQuotaService;

    @Resource
    @Lazy
    SpeedLimitService speedLimitService;

    // ========== 公共接口实现 ==========

    /**
     * 创建隧道
     * 支持端口转发和隧道转发两种模式
     * 
     * @param tunnelDto 隧道创建数据传输对象
     * @return 创建结果响应
     */
    @Override
    public R createTunnel(TunnelDto tunnelDto) {
        // 1. 验证隧道名称唯一性
        R nameValidationResult = validateTunnelNameUniqueness(tunnelDto.getName());
        if (nameValidationResult.getCode() != 0) {
            return nameValidationResult;
        }

        if (!canUseNode(tunnelDto.getInNodeId())) {
            return R.err(ERROR_IN_NODE_NOT_FOUND);
        }
        for (Long nodeId : resolveCreateNodePath(tunnelDto)) {
            if (!canUseNode(nodeId)) {
                return R.err("节点不可用或没有权限使用：" + nodeId);
            }
        }

        // 2. 验证隧道转发类型的必要参数
        if (tunnelDto.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            R tunnelForwardValidationResult = validateTunnelForwardCreate(tunnelDto);
            if (tunnelForwardValidationResult.getCode() != 0) {
                return tunnelForwardValidationResult;
            }
        }

        // 3. 验证入口节点和端口
        NodeValidationResult inNodeValidation = validateInNode(tunnelDto);
        if (inNodeValidation.isHasError()) {
            return R.err(inNodeValidation.getErrorMessage());
        }

        // 4. 构建隧道实体
        Tunnel tunnel = buildTunnelEntity(tunnelDto, inNodeValidation.getNode());

        // 5. 根据隧道类型设置出口参数
        R outNodeSetupResult = setupOutNodeParameters(tunnel, tunnelDto, inNodeValidation.getNode().getServerIp());
        if (outNodeSetupResult.getCode() != 0) {
            return outNodeSetupResult;
        }

        // 6. 设置默认属性并保存
        setDefaultTunnelProperties(tunnel);
        tunnel.setOwnerUserId(JwtUtil.getUserIdFromToken());
        boolean result = this.save(tunnel);
        
        return result ? R.ok(SUCCESS_CREATE_MSG) : R.err(ERROR_CREATE_MSG);
    }

    /**
     * 获取所有隧道列表
     * 
     * @return 包含所有隧道的响应对象
     */
    @Override
    public R getAllTunnels() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        List<Tunnel> tunnelList;
        if (Objects.equals(roleId, ADMIN_ROLE_ID)) {
            tunnelList = this.list();
        } else {
            List<Integer> sharedIds = userTunnelMapper.selectList(
                    new QueryWrapper<UserTunnel>().eq("user_id", userId))
                    .stream().map(UserTunnel::getTunnelId).collect(Collectors.toList());
            QueryWrapper<Tunnel> query = new QueryWrapper<Tunnel>().eq("owner_user_id", userId);
            if (!sharedIds.isEmpty()) {
                query.or().in("id", sharedIds);
            }
            tunnelList = this.list(query);
        }
        enrichTunnelAccess(tunnelList, userId, roleId);
        enrichTunnelPathDetails(tunnelList);
        return R.ok(tunnelList);
    }

    /**
     * 更新隧道（只允许修改名称、流量计费、端口范围）
     * 
     * @param tunnelUpdateDto 更新数据传输对象
     * @return 更新结果响应
     */
    @Override
    public R updateTunnel(TunnelUpdateDto tunnelUpdateDto) {
        // 1. 验证隧道是否存在
        Tunnel existingTunnel = this.getById(tunnelUpdateDto.getId());
        if (existingTunnel == null) {
            return R.err(ERROR_TUNNEL_NOT_FOUND);
        }
        if (!canEdit(existingTunnel)) {
            return R.err("共享隧道为只读，不能修改");
        }

        // 2. 验证隧道名称唯一性（排除自身）
        R nameValidationResult = validateTunnelNameUniquenessForUpdate(tunnelUpdateDto.getName(), tunnelUpdateDto.getId());
        if (nameValidationResult.getCode() != 0) {
            return nameValidationResult;
        }
        int up = 0;
        if (!Objects.equals(existingTunnel.getTcpListenAddr(), tunnelUpdateDto.getTcpListenAddr()) ||
                !Objects.equals(existingTunnel.getUdpListenAddr(), tunnelUpdateDto.getUdpListenAddr()) ||
                !Objects.equals(existingTunnel.getProtocol(), tunnelUpdateDto.getProtocol()) ||
                !Objects.equals(existingTunnel.getInterfaceName(), tunnelUpdateDto.getInterfaceName())) {
            up++;
        }


        // 5. 更新允许修改的字段
        existingTunnel.setName(tunnelUpdateDto.getName());
        existingTunnel.setFlow(tunnelUpdateDto.getFlow());
        existingTunnel.setTcpListenAddr(tunnelUpdateDto.getTcpListenAddr());
        existingTunnel.setUdpListenAddr(tunnelUpdateDto.getUdpListenAddr());
        existingTunnel.setTrafficRatio(tunnelUpdateDto.getTrafficRatio());
        existingTunnel.setProtocol(tunnelUpdateDto.getProtocol());
        existingTunnel.setInterfaceName(tunnelUpdateDto.getInterfaceName());
        this.updateById(existingTunnel);
        int err = 0;
        if (up != 0){
            System.out.println("123123");
            List<Forward> tunnel = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelUpdateDto.getId()));
            if (!tunnel.isEmpty()) {
                for (Forward forward : tunnel) {
                    ForwardUpdateDto forwardUpdateDto = new ForwardUpdateDto();
                    forwardUpdateDto.setId(forward.getId());
                    forwardUpdateDto.setUserId(forward.getUserId());
                    forwardUpdateDto.setName(forward.getName());
                    forwardUpdateDto.setTunnelId(forward.getTunnelId());
                    forwardUpdateDto.setRemoteAddr(forward.getRemoteAddr());
                    forwardUpdateDto.setStrategy(forward.getStrategy());
                    forwardUpdateDto.setInPort(forward.getInPort());
                    forwardUpdateDto.setInterfaceName(forward.getInterfaceName());
                    R r = forwardService.updateForward(forwardUpdateDto);
                    if (r.getCode() != 0){
                        err++;
                    }
                }
            }
        }

        if (err != 0) {
            return R.err("隧道信息更新成功，但部分转发同步更新失败");
        }
        return R.ok("隧道更新成功");
    }

    /**
     * 删除隧道
     * 删除隧道时同步清理该隧道下的转发、用户权限和限速规则
     * 
     * @param id 隧道ID
     * @return 删除结果响应
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deleteTunnel(Long id) {
        // 1. 验证隧道是否存在
        Tunnel tunnel = this.getById(id);
        if (tunnel == null) {
            return R.err(ERROR_TUNNEL_NOT_FOUND);
        }
        if (!canEdit(tunnel)) {
            return R.err("共享隧道为只读，不能删除");
        }

        try {
            Map<String, Object> cleanupSummary = cleanupTunnelDependencies(id);

            // 3. 执行删除操作
            boolean result = this.removeById(id);
            if (!result) {
                throw new IllegalStateException(ERROR_DELETE_MSG);
            }

            cleanupSummary.put("tunnelId", id);
            cleanupSummary.put("tunnelName", tunnel.getName());
            cleanupSummary.put("message", SUCCESS_DELETE_MSG);
            return R.ok(cleanupSummary);
        } catch (RuntimeException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return R.err(e.getMessage());
        }
    }

    /**
     * 获取用户可用的隧道列表
     * 管理员可以看到所有启用的隧道，普通用户只能看到有权限的启用隧道
     * 
     * @return 用户可用隧道列表响应
     */
    @Override
    public R userTunnel() {
        UserInfo currentUser = getCurrentUserInfo();
        
        // 根据用户角色获取隧道列表
        List<Tunnel> tunnelEntities = getUserAccessibleTunnels(currentUser);
        enrichTunnelAccess(tunnelEntities, currentUser.getUserId(), currentUser.getRoleId());
        
        // 转换为DTO并返回
        List<TunnelListDto> tunnelDtos = convertToTunnelListDtos(tunnelEntities);
        return R.ok(tunnelDtos);
    }

    // ========== 私有辅助方法 ==========

    /**
     * 获取当前用户信息
     * 
     * @return 用户信息对象
     */
    private UserInfo getCurrentUserInfo() {
        Integer roleId = JwtUtil.getRoleIdFromToken();
        Integer userId = JwtUtil.getUserIdFromToken();
        return new UserInfo(userId, roleId);
    }

    private boolean canUseNode(Long nodeId) {
        if (nodeId == null) {
            return false;
        }
        UserInfo current = getCurrentUserInfo();
        if (Objects.equals(current.getRoleId(), ADMIN_ROLE_ID)) {
            return true;
        }
        Node node = nodeService.getById(nodeId);
        if (node == null) {
            return false;
        }
        if (Objects.equals(node.getOwnerUserId(), current.getUserId())) {
            return true;
        }
        return userNodeMapper.selectCount(new QueryWrapper<UserNode>()
                .eq("user_id", current.getUserId()).eq("node_id", nodeId)) > 0;
    }

    private boolean canAccessTunnel(Tunnel tunnel) {
        UserInfo current = getCurrentUserInfo();
        if (Objects.equals(current.getRoleId(), ADMIN_ROLE_ID)
                || Objects.equals(tunnel.getOwnerUserId(), current.getUserId())) {
            return true;
        }
        return userTunnelMapper.selectCount(new QueryWrapper<UserTunnel>()
                .eq("user_id", current.getUserId()).eq("tunnel_id", tunnel.getId())) > 0;
    }

    private boolean canEdit(Tunnel tunnel) {
        UserInfo current = getCurrentUserInfo();
        return Objects.equals(current.getRoleId(), ADMIN_ROLE_ID)
                || Objects.equals(tunnel.getOwnerUserId(), current.getUserId());
    }

    private void enrichTunnelAccess(List<Tunnel> tunnels, Integer userId, Integer roleId) {
        for (Tunnel tunnel : tunnels) {
            User owner = userMapper.selectById(tunnel.getOwnerUserId());
            tunnel.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
            tunnel.setOwnerRoleId(owner == null ? null : owner.getRoleId());
            if (Objects.equals(roleId, ADMIN_ROLE_ID)) {
                tunnel.setAccessType("admin");
                tunnel.setEditable(true);
                tunnel.setDeletable(true);
            } else if (Objects.equals(tunnel.getOwnerUserId(), userId)) {
                tunnel.setAccessType("owned");
                tunnel.setEditable(true);
                tunnel.setDeletable(true);
            } else {
                tunnel.setAccessType("shared");
                tunnel.setEditable(false);
                tunnel.setDeletable(false);
                enrichSharedTunnelQuota(tunnel, userId);
            }
        }
    }

    private void enrichSharedTunnelQuota(Tunnel tunnel, Integer userId) {
        UserTunnel permission = userQuotaService.getTunnelPermission(userId, tunnel.getId().intValue());
        if (permission == null) return;
        long used = valueOrZero(permission.getInFlow()) + valueOrZero(permission.getOutFlow());
        int forwardUsed = userQuotaService.countForwardsUsingTunnel(userId, tunnel.getId().intValue(), null);
        boolean flowUnlimited = Objects.equals(permission.getFlowUnlimited(), 1);
        boolean forwardUnlimited = Objects.equals(permission.getForwardUnlimited(), 1);
        tunnel.setQuotaFlow(permission.getFlow());
        tunnel.setQuotaUsedFlow(used);
        tunnel.setQuotaFlowUnlimited(flowUnlimited);
        tunnel.setQuotaForwardLimit(permission.getNum());
        tunnel.setQuotaForwardUsed(forwardUsed);
        tunnel.setQuotaForwardUnlimited(forwardUnlimited);

        String reason = null;
        if (!Objects.equals(permission.getStatus(), 1)) {
            reason = "管理员已禁用该隧道权限";
        } else if (permission.getExpTime() != null && permission.getExpTime() <= System.currentTimeMillis()) {
            reason = "隧道权限已到期";
        } else if (!flowUnlimited && used >= valueOrZero(permission.getFlow()) * 1024L * 1024L * 1024L) {
            reason = "隧道流量额度已用尽";
        } else if (!forwardUnlimited && forwardUsed >= (permission.getNum() == null ? 0 : permission.getNum())) {
            reason = "隧道转发名额已用尽";
        }
        tunnel.setQuotaAvailable(reason == null);
        tunnel.setUnavailableReason(reason);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private void enrichTunnelPathDetails(List<Tunnel> tunnels) {
        Set<Long> nodeIds = tunnels.stream()
                .flatMap(tunnel -> TunnelRouteUtil.parseNodePath(tunnel).stream())
                .collect(Collectors.toSet());
        if (nodeIds.isEmpty()) {
            return;
        }

        Map<Long, Node> nodesById = nodeService.listByIds(nodeIds).stream()
                .collect(Collectors.toMap(Node::getId, node -> node));

        for (Tunnel tunnel : tunnels) {
            List<TunnelPathNodeDto> details = TunnelRouteUtil.parseNodePath(tunnel).stream()
                    .map(nodeId -> {
                        Node node = nodesById.get(nodeId);
                        TunnelPathNodeDto detail = new TunnelPathNodeDto();
                        detail.setNodeId(nodeId);
                        detail.setName(node == null ? "节点" + nodeId : node.getName());
                        detail.setStatus(WebSocketServer.isNodeOnline(nodeId) ? NODE_STATUS_ONLINE : 0);
                        return detail;
                    })
                    .collect(Collectors.toList());
            tunnel.setPathNodeDetails(details);
        }
    }

    /**
     * 验证隧道名称唯一性
     * 
     * @param tunnelName 隧道名称
     * @return 验证结果响应
     */
    private R validateTunnelNameUniqueness(String tunnelName) {
        Tunnel existTunnel = this.getOne(new QueryWrapper<Tunnel>().eq("name", tunnelName));
        if (existTunnel != null) {
            return R.err(ERROR_TUNNEL_NAME_EXISTS);
        }
        return R.ok();
    }

    /**
     * 验证隧道名称唯一性（更新时使用，排除自身）
     * 
     * @param tunnelName 隧道名称
     * @param tunnelId 隧道ID（要排除的隧道）
     * @return 验证结果响应
     */
    private R validateTunnelNameUniquenessForUpdate(String tunnelName, Long tunnelId) {
        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        query.eq("name", tunnelName);
        query.ne("id", tunnelId);  // 排除自身
        Tunnel existTunnel = this.getOne(query);
        if (existTunnel != null) {
            return R.err(ERROR_TUNNEL_NAME_EXISTS);
        }
        return R.ok();
    }



    /**
     * 验证隧道转发创建时的必要参数
     *
     * @param tunnelDto 隧道创建数据传输对象
     * @return 验证结果响应
     */
    private R validateTunnelForwardCreate(TunnelDto tunnelDto) {
        List<Long> nodePath = resolveCreateNodePath(tunnelDto);
        if (nodePath.size() < 2) {
            return R.err(ERROR_OUT_NODE_REQUIRED);
        }
        if (!Objects.equals(nodePath.get(0), tunnelDto.getInNodeId())) {
            return R.err("节点路径第一个节点必须等于入口节点");
        }
        if (new HashSet<>(nodePath).size() != nodePath.size()) {
            return R.err(ERROR_NODE_PATH_DUPLICATED);
        }
        return R.ok();
    }

    /**
     * 验证入口节点和端口
     * 
     * @param tunnelDto 隧道创建DTO
     * @return 节点验证结果
     */
    private NodeValidationResult validateInNode(TunnelDto tunnelDto) {
        // 验证入口节点是否存在
        Node inNode = nodeService.getById(tunnelDto.getInNodeId());
        if (inNode == null || !canUseNode(inNode.getId())) {
            return NodeValidationResult.error(ERROR_IN_NODE_NOT_FOUND);
        }

        // 验证入口节点是否在线
        if (inNode.getStatus() != NODE_STATUS_ONLINE) {
            return NodeValidationResult.error(ERROR_IN_NODE_OFFLINE);
        }

        return NodeValidationResult.success(inNode);
    }

    /**
     * 构建隧道实体对象
     * 
     * @param tunnelDto 隧道创建DTO
     * @param inNode 入口节点
     * @return 构建完成的隧道对象
     */
    private Tunnel buildTunnelEntity(TunnelDto tunnelDto, Node inNode) {
        Tunnel tunnel = new Tunnel();
        BeanUtils.copyProperties(tunnelDto, tunnel);
        
        // 设置入口节点信息
        tunnel.setInNodeId(tunnelDto.getInNodeId());
        tunnel.setInIp(inNode.getIp());
        
        // 设置流量计算类型
        tunnel.setFlow(tunnelDto.getFlow());
        
        // 设置流量倍率，如果为空则设置默认值1.0
        if (tunnelDto.getTrafficRatio() != null) {
            tunnel.setTrafficRatio(tunnelDto.getTrafficRatio());
        } else {
            tunnel.setTrafficRatio(new BigDecimal("1.0"));
        }
        
        // 设置协议类型（仅隧道转发需要）
        if (tunnelDto.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            // 隧道转发时，设置协议类型，默认为tls
            String protocol = StrUtil.isNotBlank(tunnelDto.getProtocol()) ? tunnelDto.getProtocol() : "tls";
            tunnel.setProtocol(protocol);
        } else {
            // 端口转发时，协议类型为null
            tunnel.setProtocol(null);
        }
        
        // 设置TCP和UDP监听地址
        tunnel.setTcpListenAddr(StrUtil.isNotBlank(tunnelDto.getTcpListenAddr()) ? 
                               tunnelDto.getTcpListenAddr() : "0.0.0.0");
        tunnel.setUdpListenAddr(StrUtil.isNotBlank(tunnelDto.getUdpListenAddr()) ? 
                               tunnelDto.getUdpListenAddr() : "0.0.0.0");
        
        return tunnel;
    }

    /**
     * 设置出口节点参数
     * 
     * @param tunnel 隧道对象
     * @param tunnelDto 隧道创建DTO
     * @return 设置结果响应
     */
    private R setupOutNodeParameters(Tunnel tunnel, TunnelDto tunnelDto, String server_ip) {
        if (tunnelDto.getType() == TUNNEL_TYPE_PORT_FORWARD) {
            // 端口转发：出口参数使用入口参数
            return setupPortForwardOutParameters(tunnel, tunnelDto, server_ip);
        } else {
            // 隧道转发：需要验证出口参数
            return setupTunnelForwardOutParameters(tunnel, tunnelDto);
        }
    }

    /**
     * 设置端口转发的出口参数
     * 
     * @param tunnel 隧道对象
     * @param tunnelDto 隧道创建DTO
     * @return 设置结果响应
     */
    private R setupPortForwardOutParameters(Tunnel tunnel, TunnelDto tunnelDto, String server_ip) {
        tunnel.setOutNodeId(tunnelDto.getInNodeId());
        tunnel.setOutIp(server_ip);
        tunnel.setNodePath(TunnelRouteUtil.joinNodePath(Collections.singletonList(tunnelDto.getInNodeId())));
        return R.ok();
    }

    /**
     * 设置隧道转发的出口参数
     * 
     * @param tunnel 隧道对象
     * @param tunnelDto 隧道创建DTO
     * @return 设置结果响应
     */
    private R setupTunnelForwardOutParameters(Tunnel tunnel, TunnelDto tunnelDto) {
        List<Long> nodePath = resolveCreateNodePath(tunnelDto);
        if (nodePath.size() < 2) {
            return R.err(ERROR_NODE_PATH_REQUIRED);
        }

        // 验证协议类型
        String protocol = tunnelDto.getProtocol();
        if (StrUtil.isBlank(protocol)) {
            return R.err("协议类型必选");
        }
        
        List<Node> pathNodes = new ArrayList<>();
        for (Long nodeId : nodePath) {
            Node node = nodeService.getById(nodeId);
            if (node == null || !canUseNode(node.getId())) {
                return R.err("节点路径中存在不存在的节点：" + nodeId);
            }
            if (node.getStatus() != NODE_STATUS_ONLINE) {
                return R.err("节点路径中的节点离线：" + node.getName());
            }
            pathNodes.add(node);
        }

        Node firstNode = pathNodes.get(0);
        Node outNode = pathNodes.get(pathNodes.size() - 1);
        if (outNode == null) {
            return R.err(ERROR_OUT_NODE_NOT_FOUND);
        }

        // 设置出口参数
        tunnel.setInNodeId(firstNode.getId());
        tunnel.setInIp(firstNode.getIp());
        tunnel.setOutNodeId(outNode.getId());
        tunnel.setOutIp(outNode.getServerIp());
        tunnel.setNodePath(TunnelRouteUtil.joinNodePath(nodePath));

        return R.ok();
    }

    private List<Long> resolveCreateNodePath(TunnelDto tunnelDto) {
        if (tunnelDto.getNodePath() != null && !tunnelDto.getNodePath().isEmpty()) {
            return tunnelDto.getNodePath().stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        List<Long> path = new ArrayList<>();
        if (tunnelDto.getInNodeId() != null) {
            path.add(tunnelDto.getInNodeId());
        }
        if (tunnelDto.getOutNodeId() != null) {
            path.add(tunnelDto.getOutNodeId());
        }
        return path;
    }

    /**
     * 设置隧道默认属性
     * 
     * @param tunnel 隧道对象
     */
    private void setDefaultTunnelProperties(Tunnel tunnel) {
        tunnel.setStatus(TUNNEL_STATUS_ACTIVE);
        long currentTime = System.currentTimeMillis();
        tunnel.setCreatedTime(currentTime);
        tunnel.setUpdatedTime(currentTime);
    }

    /**
     * 检查隧道是否存在
     * 
     * @param tunnelId 隧道ID
     * @return 隧道是否存在
     */
    private boolean isTunnelExists(Long tunnelId) {
        return this.getById(tunnelId) != null;
    }

    private Map<String, Object> cleanupTunnelDependencies(Long tunnelId) {
        Integer tunnelIdInt = tunnelId.intValue();
        Integer homeProxyCount = homeProxyRouteMapper.selectCount(new QueryWrapper<HomeProxyRoute>()
                .eq("egress_tunnel_id", tunnelId).notIn("state", "deleted", "error"));
        if (homeProxyCount != null && homeProxyCount > 0) {
            throw new IllegalStateException("该隧道正被 " + homeProxyCount + " 条家庭代理用作出口路径，请先删除对应家庭代理");
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("forwardCount", 0L);
        summary.put("userTunnelCount", 0L);
        summary.put("speedLimitCount", 0L);

        List<Forward> relatedForwards = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelIdInt));
        long forwardCount = relatedForwards.size();
        long forwardGostCleanupFailCount = 0L;
        if (forwardCount > 0) {
            for (Forward forward : relatedForwards) {
                R deleteResult = forwardService.deleteForward(forward.getId());
                if (deleteResult.getCode() != 0) {
                    forwardGostCleanupFailCount++;
                }
            }

            long remainingForwardCount = forwardService.count(new QueryWrapper<Forward>().eq("tunnel_id", tunnelIdInt));
            if (remainingForwardCount > 0) {
                boolean removed = forwardService.remove(new QueryWrapper<Forward>().eq("tunnel_id", tunnelIdInt));
                if (!removed) {
                    throw new IllegalStateException("关联转发删除失败");
                }
            }
        }

        long userTunnelCount = userTunnelService.count(new QueryWrapper<UserTunnel>().eq("tunnel_id", tunnelIdInt));
        if (userTunnelCount > 0) {
            boolean removed = userTunnelService.remove(new QueryWrapper<UserTunnel>().eq("tunnel_id", tunnelIdInt));
            if (!removed) {
                throw new IllegalStateException("关联用户隧道权限删除失败");
            }
        }

        long speedLimitCount = speedLimitService.count(new QueryWrapper<SpeedLimit>().eq("tunnel_id", tunnelId));
        if (speedLimitCount > 0) {
            boolean removed = speedLimitService.remove(new QueryWrapper<SpeedLimit>().eq("tunnel_id", tunnelId));
            if (!removed) {
                throw new IllegalStateException("关联限速规则删除失败");
            }
        }

        summary.put("forwardCount", forwardCount);
        summary.put("forwardGostCleanupFailCount", forwardGostCleanupFailCount);
        summary.put("userTunnelCount", userTunnelCount);
        summary.put("speedLimitCount", speedLimitCount);
        return summary;
    }

    /**
     * 检查隧道使用情况
     * 
     * @param tunnelId 隧道ID
     * @return 检查结果响应
     */
    private R checkTunnelUsage(Long tunnelId) {
        // 检查转发使用情况
        R forwardCheckResult = checkForwardUsage(tunnelId);
        if (forwardCheckResult.getCode() != 0) {
            return forwardCheckResult;
        }

        // 检查用户权限使用情况
        return checkUserPermissionUsage(tunnelId);
    }

    /**
     * 检查转发使用情况
     * 
     * @param tunnelId 隧道ID
     * @return 检查结果响应
     */
    private R checkForwardUsage(Long tunnelId) {
        QueryWrapper<Forward> forwardQuery = new QueryWrapper<>();
        forwardQuery.eq("tunnel_id", tunnelId);
        long forwardCount = forwardService.count(forwardQuery);
        
        if (forwardCount > 0) {
            String errorMsg = String.format(ERROR_FORWARDS_IN_USE, forwardCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 检查用户权限使用情况
     * 
     * @param tunnelId 隧道ID
     * @return 检查结果响应
     */
    private R checkUserPermissionUsage(Long tunnelId) {
        QueryWrapper<UserTunnel> userTunnelQuery = new QueryWrapper<>();
        userTunnelQuery.eq("tunnel_id", tunnelId);
        long userTunnelCount = userTunnelService.count(userTunnelQuery);
        
        if (userTunnelCount > 0) {
            String errorMsg = String.format(ERROR_USER_PERMISSIONS_IN_USE, userTunnelCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 获取用户可访问的隧道列表
     * 
     * @param userInfo 用户信息
     * @return 隧道列表
     */
    private List<Tunnel> getUserAccessibleTunnels(UserInfo userInfo) {
        if (userInfo.getRoleId() == ADMIN_ROLE_ID) {
            // 管理员：获取所有启用状态的隧道
            return getActiveTunnels();
        } else {
            // 普通用户：根据权限获取启用状态的隧道
            return getUserAuthorizedTunnels(userInfo.getUserId());
        }
    }

    /**
     * 获取所有启用状态的隧道
     * 
     * @return 启用状态的隧道列表
     */
    private List<Tunnel> getActiveTunnels() {
        return this.list(new QueryWrapper<Tunnel>().eq("status", TUNNEL_STATUS_ACTIVE));
    }

    /**
     * 获取用户有权限的启用隧道
     * 
     * @param userId 用户ID
     * @return 用户有权限的隧道列表
     */
    private List<Tunnel> getUserAuthorizedTunnels(Integer userId) {
        List<UserTunnel> userTunnels = userTunnelMapper.selectList(
            new QueryWrapper<UserTunnel>().eq("user_id", userId)
        );
        
        List<Integer> tunnelIds = userTunnels.stream()
                .map(UserTunnel::getTunnelId)
                .collect(Collectors.toList());

        QueryWrapper<Tunnel> query = new QueryWrapper<Tunnel>().eq("status", TUNNEL_STATUS_ACTIVE)
                .and(wrapper -> {
                    wrapper.eq("owner_user_id", userId);
                    if (!tunnelIds.isEmpty()) {
                        wrapper.or().in("id", tunnelIds);
                    }
                });
        return this.list(query);
    }

    /**
     * 将隧道实体列表转换为DTO列表
     * 
     * @param tunnelEntities 隧道实体列表
     * @return 隧道DTO列表
     */
    private List<TunnelListDto> convertToTunnelListDtos(List<Tunnel> tunnelEntities) {
        return tunnelEntities.stream()
                .map(this::convertToTunnelListDto)
                .collect(Collectors.toList());
    }

    /**
     * 将Tunnel实体转换为TunnelListDto
     * 
     * @param tunnel 隧道实体
     * @return 隧道列表DTO
     */
    private TunnelListDto convertToTunnelListDto(Tunnel tunnel) {
        TunnelListDto dto = new TunnelListDto();
        dto.setId(tunnel.getId().intValue());
        dto.setName(tunnel.getName());
        dto.setIp(tunnel.getInIp());
        dto.setInNodeId(tunnel.getInNodeId());
        dto.setNodePath(TunnelRouteUtil.joinNodePath(TunnelRouteUtil.parseNodePath(tunnel)));
        dto.setType(tunnel.getType());
        dto.setProtocol(tunnel.getProtocol());
        dto.setOwnerUserId(tunnel.getOwnerUserId());
        dto.setOwnerUserName(tunnel.getOwnerUserName());
        dto.setOwnerRoleId(tunnel.getOwnerRoleId());
        dto.setAccessType(tunnel.getAccessType());
        
        // 获取入口节点的端口范围信息
        if (tunnel.getInNodeId() != null) {
            Node inNode = nodeService.getById(tunnel.getInNodeId());
            if (inNode != null) {
                dto.setInNodePortSta(inNode.getPortSta());
                dto.setInNodePortEnd(inNode.getPortEnd());
            }
        }
        
        return dto;
    }

    /**
     * 隧道诊断功能
     * 
     * @param tunnelId 隧道ID
     * @return 诊断结果响应
     */
    @Override
    public R diagnoseTunnel(Long tunnelId) {
        // 1. 验证隧道是否存在
        Tunnel tunnel = this.getById(tunnelId);
        if (tunnel == null || !canAccessTunnel(tunnel)) {
            return R.err(ERROR_TUNNEL_NOT_FOUND);
        }

        // 2. 获取入口和出口节点信息
        Node inNode = nodeService.getById(tunnel.getInNodeId());
        if (inNode == null) {
            return R.err(ERROR_IN_NODE_NOT_FOUND);
        }

        Node outNode = null;
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            outNode = nodeService.getById(tunnel.getOutNodeId());
            if (outNode == null) {
                return R.err(ERROR_OUT_NODE_NOT_FOUND);
            }
        }

        List<DiagnosisResult> results = new ArrayList<>();

        // 3. 根据隧道类型执行不同的诊断策略
        if (tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD) {
            // 端口转发：只给入口节点发送诊断指令，TCP ping谷歌443端口
            DiagnosisResult inResult = performTcpPingDiagnosisWithConnectionCheck(inNode, "www.google.com", 443, "入口->外网");
            results.add(inResult);
        } else {
            List<Node> pathNodes = getTunnelPathNodes(tunnel);
            List<Integer> hopPorts = getTunnelSampleHopPorts(tunnel, pathNodes.size() - 1);
            for (int i = 0; i < pathNodes.size() - 1; i++) {
                Node fromNode = pathNodes.get(i);
                Node toNode = pathNodes.get(i + 1);
                DiagnosisResult segmentResult = performTcpPingDiagnosisWithConnectionCheck(
                        fromNode,
                        toNode.getServerIp(),
                        hopPorts.get(i),
                        fromNode.getName() + "->" + toNode.getName()
                );
                results.add(segmentResult);
            }

            DiagnosisResult outToExternalResult = performTcpPingDiagnosisWithConnectionCheck(outNode, "www.google.com", 443, "出口->外网");
            results.add(outToExternalResult);
        }

        // 4. 构建诊断报告
        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("tunnelId", tunnelId);
        diagnosisReport.put("tunnelName", tunnel.getName());
        diagnosisReport.put("tunnelType", tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD ? "端口转发" : "隧道转发");
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    /**
     * 获取出口节点的TCP端口
     * 通过隧道ID查找转发服务的出口端口，如果没有则使用默认SSH端口22
     * 
     * @param tunnelId 隧道ID
     * @return TCP端口号
     */
    private int getOutNodeTcpPort(Long tunnelId) {
        List<Forward> forwards = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId).eq("status", TUNNEL_STATUS_ACTIVE));
        if (!forwards.isEmpty()) {
            return forwards.get(0).getOutPort();
        }
        // 如果没有转发服务，使用默认SSH端口22
        return 22;
    }

    private List<Node> getTunnelPathNodes(Tunnel tunnel) {
        List<Node> pathNodes = new ArrayList<>();
        for (Long nodeId : TunnelRouteUtil.parseNodePath(tunnel)) {
            Node node = nodeService.getById(nodeId);
            if (node != null) {
                pathNodes.add(node);
            }
        }
        return pathNodes;
    }

    private List<Integer> getTunnelSampleHopPorts(Tunnel tunnel, int expectedHopCount) {
        List<Forward> forwards = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnel.getId()).eq("status", TUNNEL_STATUS_ACTIVE));
        if (!forwards.isEmpty()) {
            Forward forward = forwards.get(0);
            List<Integer> hopPorts = TunnelRouteUtil.parseHopPorts(forward.getHopPorts());
            if (hopPorts.size() == expectedHopCount) {
                return hopPorts;
            }
            if (expectedHopCount == 1 && forward.getOutPort() != null) {
                return Collections.singletonList(forward.getOutPort());
            }
        }
        List<Integer> fallbackPorts = new ArrayList<>();
        for (int i = 0; i < expectedHopCount; i++) {
            fallbackPorts.add(22);
        }
        return fallbackPorts;
    }

    /**
     * 执行TCP ping诊断
     * 
     * @param node 执行TCP ping的节点
     * @param targetIp 目标IP地址
     * @param port 目标端口
     * @param description 诊断描述
     * @return 诊断结果
     */
    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 构建TCP ping请求数据
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 4);
            tcpPingData.put("timeout", 5000); // 5秒超时

            // 发送TCP ping命令到节点
            GostDto gostResult = WebSocketServer.send_msg(node.getId(), tcpPingData, "TcpPing");
            
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setTimestamp(System.currentTimeMillis());

            if (gostResult != null && "OK".equals(gostResult.getMsg())) {
                // 尝试解析TCP ping响应数据
                try {
                    if (gostResult.getData() != null) {
                        JSONObject tcpPingResponse = (JSONObject) gostResult.getData();
                        boolean success = tcpPingResponse.getBooleanValue("success");
                        
                        result.setSuccess(success);
                        if (success) {
                            result.setMessage("TCP连接成功");
                            result.setAverageTime(tcpPingResponse.getDoubleValue("averageTime"));
                            result.setPacketLoss(tcpPingResponse.getDoubleValue("packetLoss"));
                        } else {
                            result.setMessage(tcpPingResponse.getString("errorMessage"));
                            result.setAverageTime(-1.0);
                            result.setPacketLoss(100.0);
                        }
                    } else {
                        // 没有详细数据，使用默认值
                        result.setSuccess(true);
                        result.setMessage("TCP连接成功");
                        result.setAverageTime(0.0);
                        result.setPacketLoss(0.0);
                    }
                } catch (Exception e) {
                    // 解析响应数据失败，但TCP ping命令本身成功了
                    result.setSuccess(true);
                    result.setMessage("TCP连接成功，但无法解析详细数据");
                    result.setAverageTime(0.0);
                    result.setPacketLoss(0.0);
                }
            } else {
                result.setSuccess(false);
                result.setMessage(gostResult != null ? gostResult.getMsg() : "节点无响应");
                result.setAverageTime(-1.0);
                result.setPacketLoss(100.0);
            }

            return result;
        } catch (Exception e) {
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setSuccess(false);
            result.setMessage("诊断执行异常: " + e.getMessage());
            result.setTimestamp(System.currentTimeMillis());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    /**
     * 执行TCP ping诊断（带连接状态检查）
     * 
     * @param node 执行TCP ping的节点
     * @param targetIp 目标IP地址
     * @param port 目标端口
     * @param description 诊断描述
     * @return 诊断结果
     */
    private DiagnosisResult performTcpPingDiagnosisWithConnectionCheck(Node node, String targetIp, int port, String description) {
        DiagnosisResult result = new DiagnosisResult();
        result.setNodeId(node.getId());
        result.setNodeName(node.getName());
        result.setTargetIp(targetIp);
        result.setTargetPort(port);
        result.setDescription(description);
        result.setTimestamp(System.currentTimeMillis());

        try {
            return performTcpPingDiagnosis(node, targetIp, port, description);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("连接检查异常: " + e.getMessage());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }


    // ========== 内部数据类 ==========

    /**
     * 用户信息封装类
     */
    @Data
    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;
    }

    /**
     * 节点验证结果封装类
     */
    @Data
    private static class NodeValidationResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Node node;

        private NodeValidationResult(boolean hasError, String errorMessage, Node node) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.node = node;
        }

        public static NodeValidationResult success(Node node) {
            return new NodeValidationResult(false, null, node);
        }

        public static NodeValidationResult error(String errorMessage) {
            return new NodeValidationResult(true, errorMessage, null);
        }
    }

    /**
     * 诊断结果数据类
     */
    @Data
    public static class DiagnosisResult {
        private Long nodeId;
        private String nodeName;
        private String targetIp;
        private Integer targetPort;
        private String description;
        private boolean success;
        private String message;
        private double averageTime;
        private double packetLoss;
        private long timestamp;
    }
}
