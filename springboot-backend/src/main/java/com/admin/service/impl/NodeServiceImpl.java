package com.admin.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.TunnelRouteUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.PortNamespaceUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.SpeedLimit;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.entity.UserNode;
import com.admin.entity.User;
import com.admin.entity.ViteConfig;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserNodeMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.UserQuotaService;
import com.admin.service.ViteConfigService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;

/**
 * <p>
 * 节点服务实现类
 * 提供节点的增删改查功能，包括节点创建、更新、删除和查询操作
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Service
public class NodeServiceImpl extends ServiceImpl<NodeMapper, Node> implements NodeService {

    // ========== 常量定义 ==========
    
    /** 节点默认状态：启用 */
    private static final int NODE_STATUS_ACTIVE = 0;
    
    /** 成功响应消息 */
    private static final String SUCCESS_CREATE_MSG = "节点创建成功";
    private static final String SUCCESS_UPDATE_MSG = "节点更新成功";
    private static final String SUCCESS_DELETE_MSG = "节点删除成功";
    
    /** 错误响应消息 */
    private static final String ERROR_CREATE_MSG = "节点创建失败";
    private static final String ERROR_UPDATE_MSG = "节点更新失败";
    private static final String ERROR_DELETE_MSG = "节点删除失败";
    private static final String ERROR_NODE_NOT_FOUND = "节点不存在";
    private static final String ERROR_ONLINE_NODE_HAS_TUNNELS = "节点当前在线，并且还有 %d 个关联隧道。请在隧道管理中删除需要清理的失效隧道，或确认节点离线后再删除节点。";
    
    /** 隧道使用检查相关消息 */
    private static final String ERROR_IN_NODE_IN_USE = "该节点还有 %d 个隧道作为入口节点在使用，请先删除相关隧道";
    private static final String ERROR_OUT_NODE_IN_USE = "该节点还有 %d 个隧道作为出口节点在使用，请先删除相关隧道";
    
    /** 端口范围验证相关消息 */
    private static final String ERROR_PORT_STA_REQUIRED = "起始端口不能为空";
    private static final String ERROR_PORT_END_REQUIRED = "结束端口不能为空";
    private static final String ERROR_PORT_RANGE_INVALID = "端口必须在1-65535范围内";
    private static final String ERROR_PORT_ORDER_INVALID = "结束端口不能小于起始端口";
    private static final String AGENT_INSTALL_SCRIPT_URL =
            "https://raw.githubusercontent.com/NorwayXZ/flux-panel/2.8.2/install.sh";

    // ========== 依赖注入 ==========
    
    @Resource
    private TunnelMapper tunnelMapper;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    @Lazy
    private UserTunnelService userTunnelService;

    @Resource
    @Lazy
    private SpeedLimitService speedLimitService;

    @Resource
    private UserNodeMapper userNodeMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserQuotaService userQuotaService;

    @Resource
    ViteConfigService viteConfigService;


    // ========== 公共接口实现 ==========

    /**
     * 创建新节点
     * 
     * @param nodeDto 节点创建数据传输对象
     * @return 创建结果响应
     */
    @Override
    public R createNode(NodeDto nodeDto) {
        Node node = buildNewNode(nodeDto);
        boolean result = this.save(node);
        return result ? R.ok(SUCCESS_CREATE_MSG) : R.err(ERROR_CREATE_MSG);
    }



    /**
     * 获取所有节点列表
     * 注意：返回结果中会隐藏节点密钥信息
     * 
     * @return 包含所有节点的响应对象
     */
    @Override
    public R getAllNodes() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        List<Node> nodeList;
        if (Objects.equals(roleId, 0)) {
            nodeList = this.list();
        } else {
            List<Integer> sharedIds = userNodeMapper.selectList(
                    new QueryWrapper<UserNode>().eq("user_id", userId))
                    .stream().map(UserNode::getNodeId).collect(Collectors.toList());
            QueryWrapper<Node> query = new QueryWrapper<Node>().eq("owner_user_id", userId);
            if (!sharedIds.isEmpty()) {
                query.or().in("id", sharedIds);
            }
            nodeList = this.list(query);
        }
        nodeList.forEach(this::refreshNodeStatus);
        enrichNodeAccess(nodeList, userId, roleId);
        hideNodeSecrets(nodeList);
        return R.ok(nodeList);
    }

    /**
     * 更新节点信息
     * 
     * @param nodeUpdateDto 节点更新数据传输对象
     * @return 更新结果响应
     */
    @Override
    public R updateNode(NodeUpdateDto nodeUpdateDto) {
        // 1. 验证节点是否存在
        Node node = this.getById(nodeUpdateDto.getId());
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        if (!canEdit(node)) {
            return R.err("共享节点为只读，不能修改");
        }

        //1.1 如果节点在线 且传入更新的 http/tls/socks 任意一项与数据库不一致，则通过 WS 通知节点更新设置
        boolean online = node.getStatus() != null && node.getStatus() == 1;
        Integer newHttp = nodeUpdateDto.getHttp();
        Integer newTls = nodeUpdateDto.getTls();
        Integer newSocks = nodeUpdateDto.getSocks();

        boolean httpChanged = newHttp != null && !newHttp.equals(node.getHttp());
        boolean tlsChanged = newTls != null && !newTls.equals(node.getTls());
        boolean socksChanged = newSocks != null && !newSocks.equals(node.getSocks());

        if (online && (httpChanged || tlsChanged || socksChanged)) {
            JSONObject req = new JSONObject();
            req.put("http", newHttp);
            req.put("tls", newTls);
            req.put("socks", newSocks);

            GostDto gostResult = WebSocketServer.send_msg(node.getId(), req, "SetProtocol");
            if (!Objects.equals(gostResult.getMsg(), "OK")){
                return R.err(gostResult.getMsg());
            }
        }


        // 2. 构建更新对象并执行更新
        Node updateNode = buildUpdateNode(nodeUpdateDto);
        boolean result = this.updateById(updateNode);

        // 更新隧道入口ip
        List<Tunnel> inNodeId = tunnelService.list(new QueryWrapper<Tunnel>().eq("in_node_id", updateNode.getId()));
        if (!inNodeId.isEmpty()) {
            for (Tunnel tunnel : inNodeId) {
                tunnel.setInIp(updateNode.getIp());
            }
            tunnelService.updateBatchById(inNodeId);
        }

        // 更新服务器出口ip
        List<Tunnel> outNodeId = tunnelService.list(new QueryWrapper<Tunnel>().eq("out_node_id", updateNode.getId()));
        if (!outNodeId.isEmpty()) {
            for (Tunnel tunnel : outNodeId) {
                tunnel.setOutIp(updateNode.getServerIp());
            }
            tunnelService.updateBatchById(outNodeId);
        }

        return result ? R.ok(SUCCESS_UPDATE_MSG) : R.err(ERROR_UPDATE_MSG);
    }

    /**
     * 删除节点
     * 在线节点仍有隧道关联时不允许级联删除；离线节点允许清理依赖该节点的转发、隧道、用户隧道权限和限速规则
     * 
     * @param id 节点ID
     * @return 删除结果响应
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deleteNode(Long id) {
        // 1. 验证节点是否存在
        Node node = this.getById(id);
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }
        if (!canEdit(node)) {
            return R.err("共享节点为只读，不能修改");
        }

        try {
            long relatedTunnelCount = countNodeTunnels(id);
            if (isNodeOnlineForDeletion(node) && relatedTunnelCount > 0) {
                return R.err(String.format(ERROR_ONLINE_NODE_HAS_TUNNELS, relatedTunnelCount));
            }

            Map<String, Object> cleanupSummary = cleanupNodeDependencies(id);

            // 3. 执行删除操作
            boolean result = this.removeById(id);
            if (!result) {
                throw new IllegalStateException(ERROR_DELETE_MSG);
            }

            cleanupSummary.put("nodeId", id);
            cleanupSummary.put("nodeName", node.getName());
            cleanupSummary.put("message", SUCCESS_DELETE_MSG);
            return R.ok(cleanupSummary);
        } catch (RuntimeException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return R.err(e.getMessage());
        }
    }

    @Override
    public R checkNodeStatus(Long id) {
        if (id != null) {
            Node node = this.getById(id);
            if (node == null || !canAccess(node)) {
                return R.err(ERROR_NODE_NOT_FOUND);
            }
            return R.ok(syncNodeStatus(node));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Node> accessibleNodes = (List<Node>) getAllNodes().getData();
        for (Node node : accessibleNodes) {
            result.add(syncNodeStatus(node));
        }
        return R.ok(result);
    }

    /**
     * 根据ID获取节点信息
     * 
     * @param id 节点ID
     * @return 节点对象
     * @throws RuntimeException 当节点不存在时抛出异常
     */
    @Override
    public Node getNodeById(Long id) {
        Node node = this.getById(id);
        if (node == null) {
            throw new RuntimeException(ERROR_NODE_NOT_FOUND);
        }
        return node;
    }

    // ========== 私有辅助方法 ==========

    /**
     * 构建新节点对象
     * 
     * @param nodeDto 节点创建DTO
     * @return 构建完成的节点对象
     */
    private Node buildNewNode(NodeDto nodeDto) {
        Node node = new Node();
        BeanUtils.copyProperties(nodeDto, node);
        
        // 验证端口范围
        validatePortRange(node.getPortSta(), node.getPortEnd());
        
        // 设置默认属性
        node.setSecret(IdUtil.simpleUUID());
        node.setStatus(NODE_STATUS_ACTIVE);
        node.setOwnerUserId(JwtUtil.getUserIdFromToken());
        
        // 设置时间戳
        long currentTime = System.currentTimeMillis();
        node.setCreatedTime(currentTime);
        node.setUpdatedTime(currentTime);

        return node;
    }

    private Map<String, Object> syncNodeStatus(Node node) {
        int status = refreshNodeStatus(node);

        Map<String, Object> item = new HashMap<>();
        item.put("id", node.getId());
        item.put("status", status);
        item.put("online", status == 1);
        item.put("checkedTime", System.currentTimeMillis());
        return item;
    }

    private int refreshNodeStatus(Node node) {
        int status = WebSocketServer.isNodeOnline(node.getId()) ? 1 : 0;
        if (!Objects.equals(node.getStatus(), status)) {
            Node updateNode = new Node();
            updateNode.setId(node.getId());
            updateNode.setStatus(status);
            updateNode.setUpdatedTime(System.currentTimeMillis());
            this.updateById(updateNode);
        }
        node.setStatus(status);
        return status;
    }

    private boolean isNodeOnlineForDeletion(Node node) {
        return refreshNodeStatus(node) == 1;
    }

    private long countNodeTunnels(Long nodeId) {
        return findTunnelsByNodePath(nodeId).size();
    }

    private Map<String, Object> cleanupNodeDependencies(Long nodeId) {
        List<Tunnel> relatedTunnels = findTunnelsByNodePath(nodeId);

        Map<String, Object> summary = new HashMap<>();
        summary.put("tunnelCount", relatedTunnels.size());
        summary.put("forwardCount", 0L);
        summary.put("userTunnelCount", 0L);
        summary.put("speedLimitCount", 0L);

        List<Long> tunnelIds = new ArrayList<>();
        List<Integer> tunnelIdInts = new ArrayList<>();
        for (Tunnel tunnel : relatedTunnels) {
            tunnelIds.add(tunnel.getId());
            tunnelIdInts.add(tunnel.getId().intValue());
        }

        long forwardCount = tunnelIdInts.isEmpty() ? 0L : forwardService.count(new QueryWrapper<Forward>().in("tunnel_id", tunnelIdInts));
        if (forwardCount > 0) {
            boolean removed = forwardService.remove(new QueryWrapper<Forward>().in("tunnel_id", tunnelIdInts));
            if (!removed) {
                throw new IllegalStateException("关联转发删除失败");
            }
        }

        long userTunnelCount = tunnelIdInts.isEmpty() ? 0L : userTunnelService.count(new QueryWrapper<UserTunnel>().in("tunnel_id", tunnelIdInts));
        if (userTunnelCount > 0) {
            boolean removed = userTunnelService.remove(new QueryWrapper<UserTunnel>().in("tunnel_id", tunnelIdInts));
            if (!removed) {
                throw new IllegalStateException("关联用户隧道权限删除失败");
            }
        }

        long speedLimitCount = tunnelIds.isEmpty() ? 0L : speedLimitService.count(new QueryWrapper<SpeedLimit>().in("tunnel_id", tunnelIds));
        if (speedLimitCount > 0) {
            boolean removed = speedLimitService.remove(new QueryWrapper<SpeedLimit>().in("tunnel_id", tunnelIds));
            if (!removed) {
                throw new IllegalStateException("关联限速规则删除失败");
            }
        }

        boolean tunnelsRemoved = tunnelIds.isEmpty() || tunnelService.removeByIds(tunnelIds);
        if (!tunnelsRemoved) {
            throw new IllegalStateException("关联隧道删除失败");
        }

        summary.put("forwardCount", forwardCount);
        summary.put("userTunnelCount", userTunnelCount);
        summary.put("speedLimitCount", speedLimitCount);
        userNodeMapper.delete(new QueryWrapper<UserNode>().eq("node_id", nodeId));
        return summary;
    }

    private List<Tunnel> findTunnelsByNodePath(Long nodeId) {
        List<Tunnel> relatedTunnels = new ArrayList<>();
        for (Tunnel tunnel : tunnelService.list()) {
            if (TunnelRouteUtil.parseNodePath(tunnel).contains(nodeId)) {
                relatedTunnels.add(tunnel);
            }
        }
        return relatedTunnels;
    }

    /**
     * 构建节点更新对象
     * 
     * @param nodeUpdateDto 节点更新DTO
     * @return 构建完成的更新对象
     */
    private Node buildUpdateNode(NodeUpdateDto nodeUpdateDto) {
        Node node = new Node();
        node.setId(nodeUpdateDto.getId());
        node.setName(nodeUpdateDto.getName());
        node.setIp(nodeUpdateDto.getIp());
        node.setServerIp(nodeUpdateDto.getServerIp());
        node.setPortSta(nodeUpdateDto.getPortSta());
        node.setPortEnd(nodeUpdateDto.getPortEnd());
        node.setHttp(nodeUpdateDto.getHttp());
        node.setTls(nodeUpdateDto.getTls());
        node.setSocks(nodeUpdateDto.getSocks());
        // 验证端口范围
        validatePortRange(node.getPortSta(), node.getPortEnd());
        
        node.setUpdatedTime(System.currentTimeMillis());
        return node;
    }

    /**
     * 隐藏节点列表中的密钥信息
     * 
     * @param nodeList 节点列表
     */
    private void hideNodeSecrets(List<Node> nodeList) {
        nodeList.forEach(node -> node.setSecret(null));
    }


    /**
     * 检查节点使用情况
     * 验证是否有隧道正在使用该节点作为入口或出口节点
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkNodeUsage(Long nodeId) {
        // 检查入口节点使用情况
        R inNodeCheckResult = checkInNodeUsage(nodeId);
        if (inNodeCheckResult.getCode() != 0) {
            return inNodeCheckResult;
        }

        // 检查出口节点使用情况
        return checkOutNodeUsage(nodeId);
    }

    /**
     * 检查节点作为入口节点的使用情况
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkInNodeUsage(Long nodeId) {
        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        query.eq("in_node_id", nodeId);
        
        long tunnelCount = tunnelMapper.selectCount(query);
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_IN_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 检查节点作为出口节点的使用情况
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkOutNodeUsage(Long nodeId) {
        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        query.eq("out_node_id", nodeId);
        
        long tunnelCount = tunnelMapper.selectCount(query);
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_OUT_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 获取节点安装命令
     * 根据节点信息生成对应的安装命令
     * 
     * @param id 节点ID
     * @return 包含安装命令的响应对象
     */
    @Override
    public R getInstallCommand(Long id) {
        // 1. 验证节点是否存在
        Node node = this.getById(id);
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        if (!canEdit(node)) {
            return R.err("共享节点不提供安装密钥");
        }

        // 2. 构建安装命令
        return buildInstallCommand(node);
    }

    /**
     * 构建节点安装命令
     * 
     * @param node 节点对象
     * @return 格式化的安装命令
     */
    private R buildInstallCommand(Node node) {
        ViteConfig viteConfig = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", "ip"));
        if (viteConfig == null) return R.err("请先前往网站配置中设置ip");

        StringBuilder command = new StringBuilder();
        
        // 第一部分：下载安装脚本  
        command.append("curl -fsSL ").append(AGENT_INSTALL_SCRIPT_URL)
               .append(" -o ./install.sh && chmod +x ./install.sh && ");
        
        // 处理服务器地址，如果是IPv6需要添加方括号
        String processedServerAddr = processServerAddress(viteConfig.getValue());
        
        // 第二部分：执行安装脚本（去掉-u参数）
        command.append("./install.sh")
               .append(" -a ").append(shellQuote(processedServerAddr))
               .append(" -s ").append(shellQuote(node.getSecret()));

        return R.ok(command.toString());
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private boolean canAccess(Node node) {
        Integer userId = JwtUtil.getUserIdFromToken();
        if (Objects.equals(JwtUtil.getRoleIdFromToken(), 0) || Objects.equals(node.getOwnerUserId(), userId)) {
            return true;
        }
        return userNodeMapper.selectCount(new QueryWrapper<UserNode>()
                .eq("user_id", userId).eq("node_id", node.getId())) > 0;
    }

    private boolean canEdit(Node node) {
        return Objects.equals(JwtUtil.getRoleIdFromToken(), 0)
                || Objects.equals(node.getOwnerUserId(), JwtUtil.getUserIdFromToken());
    }

    private void enrichNodeAccess(List<Node> nodes, Integer userId, Integer roleId) {
        Map<String, Long> portPoolGroupSizes = this.list().stream()
                .collect(Collectors.groupingBy(PortNamespaceUtil::fromNode, Collectors.counting()));
        Map<Integer, Integer> forwardUsage = Objects.equals(roleId, 0)
                ? Collections.emptyMap()
                : userQuotaService.countForwardsUsingNodes(
                        userId,
                        nodes.stream().map(node -> node.getId().intValue()).collect(Collectors.toSet()),
                        null
                );
        for (Node node : nodes) {
            node.setPortPoolGroupSize(portPoolGroupSizes
                    .getOrDefault(PortNamespaceUtil.fromNode(node), 1L)
                    .intValue());
            User owner = userMapper.selectById(node.getOwnerUserId());
            node.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
            node.setOwnerRoleId(owner == null ? null : owner.getRoleId());
            if (Objects.equals(roleId, 0)) {
                node.setAccessType("admin");
                node.setEditable(true);
                node.setDeletable(true);
            } else if (Objects.equals(node.getOwnerUserId(), userId)) {
                node.setAccessType("owned");
                node.setEditable(true);
                node.setDeletable(true);
            } else {
                node.setAccessType("shared");
                node.setEditable(false);
                node.setDeletable(false);
                UserNode permission = userNodeMapper.selectOne(new QueryWrapper<UserNode>()
                        .eq("user_id", userId).eq("node_id", node.getId()));
                if (permission != null) {
                    long used = (permission.getInFlow() == null ? 0L : permission.getInFlow())
                            + (permission.getOutFlow() == null ? 0L : permission.getOutFlow());
                    boolean flowUnlimited = Objects.equals(permission.getFlowUnlimited(), 1);
                    boolean forwardUnlimited = Objects.equals(permission.getForwardUnlimited(), 1);
                    int forwardUsed = forwardUsage.getOrDefault(node.getId().intValue(), 0);
                    node.setQuotaFlow(permission.getFlow());
                    node.setQuotaUsedFlow(used);
                    node.setQuotaFlowUnlimited(flowUnlimited);
                    node.setQuotaForwardLimit(permission.getNum());
                    node.setQuotaForwardUsed(forwardUsed);
                    node.setQuotaForwardUnlimited(forwardUnlimited);
                    node.setQuotaFlowResetTime(permission.getFlowResetTime());
                    String reason = null;
                    if (!Objects.equals(permission.getStatus(), 1)) reason = "管理员已禁用节点权限";
                    else if (permission.getExpTime() != null && permission.getExpTime() <= System.currentTimeMillis()) reason = "节点权限已到期";
                    else if (!flowUnlimited && used >= (permission.getFlow() == null ? 0L : permission.getFlow()) * 1024L * 1024L * 1024L) reason = "节点流量额度已用尽";
                    else if (!forwardUnlimited && forwardUsed >= (permission.getNum() == null ? 0 : permission.getNum())) reason = "节点转发名额已用尽";
                    node.setQuotaAvailable(reason == null);
                    node.setUnavailableReason(reason);
                }
            }
        }
    }

    /**
     * 处理服务器地址，确保IPv6地址被方括号包裹
     * 
     * @param serverAddr 原始服务器地址，格式可能为 host:port
     * @return 处理后的服务器地址
     */
    private String processServerAddress(String serverAddr) {
        if (StrUtil.isBlank(serverAddr)) {
            return serverAddr;
        }
        
        // 如果已经被方括号包裹，直接返回
        if (serverAddr.startsWith("[")) {
            return serverAddr;
        }
        
        // 查找最后一个冒号，分离主机和端口
        int lastColonIndex = serverAddr.lastIndexOf(':');
        if (lastColonIndex == -1) {
            // 没有端口号，直接检查是否需要包裹
            return isIPv6Address(serverAddr) ? "[" + serverAddr + "]" : serverAddr;
        }
        
        String host = serverAddr.substring(0, lastColonIndex);
        String port = serverAddr.substring(lastColonIndex);
        
        // 检查主机部分是否为IPv6地址
        if (isIPv6Address(host)) {
            return "[" + host + "]" + port;
        }
        
        return serverAddr;
    }

    /**
     * 判断是否为IPv6地址
     * 
     * @param address 地址字符串（不包含端口号）
     * @return 是否为IPv6地址
     */
    private boolean isIPv6Address(String address) {
        // IPv6地址包含多个冒号，至少2个
        if (!address.contains(":")) {
            return false;
        }
        
        // 计算冒号数量，IPv6地址至少有2个冒号
        long colonCount = address.chars().filter(ch -> ch == ':').count();
        return colonCount >= 2;
    }

    /**
     * 验证端口范围的有效性
     * 
     * @param portSta 起始端口
     * @param portEnd 结束端口
     * @throws RuntimeException 当端口范围无效时抛出异常
     */
    private void validatePortRange(Integer portSta, Integer portEnd) {
        // 检查起始端口是否为空
        if (portSta == null) {
            throw new RuntimeException(ERROR_PORT_STA_REQUIRED);
        }
        
        // 检查结束端口是否为空
        if (portEnd == null) {
            throw new RuntimeException(ERROR_PORT_END_REQUIRED);
        }
        
        // 检查端口范围是否在有效区间内
        if (portSta < 1 || portSta > 65535 || portEnd < 1 || portEnd > 65535) {
            throw new RuntimeException(ERROR_PORT_RANGE_INVALID);
        }
        
        // 检查端口顺序是否正确
        if (portEnd < portSta) {
            throw new RuntimeException(ERROR_PORT_ORDER_INVALID);
        }
    }

}
