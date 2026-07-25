package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardRouteDto;
import com.admin.common.dto.ForwardTargetHealthDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.ForwardWithTunnelDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.ForwardRouteFailoverPolicy;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.PortNamespaceUtil;
import com.admin.common.utils.TunnelRouteUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.PortAllocationLockMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * <p>
 * 端口转发服务实现类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class ForwardServiceImpl extends ServiceImpl<ForwardMapper, Forward> implements ForwardService {

    // 常量定义
    private static final String GOST_SUCCESS_MSG = "OK";
    private static final String GOST_NOT_FOUND_MSG = "not found";
    private static final int ADMIN_ROLE_ID = 0;
    private static final int TUNNEL_TYPE_PORT_FORWARD = 1;
    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2;
    private static final int FORWARD_STATUS_ACTIVE = 1;
    private static final int FORWARD_STATUS_PAUSED = 0;
    private static final int FORWARD_STATUS_ERROR = -1;
    private static final int TUNNEL_STATUS_ACTIVE = 1;
    private static final String ROUTE_MODE_SINGLE = "single";
    private static final String ROUTE_MODE_FAILOVER = "failover";
    private static final String ROUTE_MODE_LATENCY = "latency";
    private static final String ROUTE_STATUS_HEALTHY = "healthy";
    private static final String ROUTE_STATUS_UNHEALTHY = "unhealthy";
    private static final String ROUTE_STATUS_UNKNOWN = "unknown";
    private static final String PROTOCOL_MODE_TCP_UDP = "tcp_udp";
    private static final int MAX_BATCH_FORWARD_COUNT = 200;

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserService userService;

    @Resource
    UserQuotaService userQuotaService;

    @Resource
    NodeService nodeService;

    @Resource
    PortAllocationLockMapper portAllocationLockMapper;

    @Resource
    JdbcTemplate jdbcTemplate;

    @Value("${forward-routing.failure-threshold:2}")
    private int routeFailureThreshold;

    @Value("${forward-routing.recovery-threshold:2}")
    private int routeRecoveryThreshold;

    @Value("${forward-routing.switch-cooldown-ms:120000}")
    private long routeSwitchCooldownMs;

    @Value("${forward-routing.failback-stable-ms:180000}")
    private long routeFailbackStableMs;

    @Value("${forward-routing.latency-gap-ms:15}")
    private double routeLatencyGapMs;

    private final AtomicBoolean routeHealthChecking = new AtomicBoolean(false);


    @Override
    @Transactional(rollbackFor = Exception.class)
    public R createForward(ForwardDto forwardDto) {
        portAllocationLockMapper.lockForUpdate();
        if (forwardDto.getBatchEndPort() != null) {
            return createBatchForwards(forwardDto);
        }
        return createSingleForward(forwardDto);
    }

    private R createSingleForward(ForwardDto forwardDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        RouteValidationResult routeValidation = validateRouteTunnels(
                forwardDto.getTunnelId(),
                forwardDto.getRouteTunnelIds(),
                forwardDto.getRouteMode()
        );
        if (routeValidation.isHasError()) {
            return R.err(routeValidation.getErrorMessage());
        }
        Tunnel tunnel = routeValidation.getTunnels().get(0);

        // 3. 普通用户权限和限制检查
        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }
        R candidatePermission = checkCandidateTunnelPermissions(currentUser, routeValidation.getTunnels(), tunnel.getId().intValue(), null);
        if (candidatePermission.getCode() != 0) {
            return candidatePermission;
        }

        // 4. 分配端口
        PortAllocation portAllocation = allocatePorts(tunnel, forwardDto.getInPort());
        if (portAllocation.isHasError()) {
            return R.err(portAllocation.getErrorMessage());
        }
        RouteAllocationResult routeAllocation = allocateRouteConfigs(
                routeValidation.getTunnels(),
                portAllocation,
                null
        );
        if (routeAllocation.isHasError()) {
            return R.err(routeAllocation.getErrorMessage());
        }

        // 5. 创建并保存Forward对象
        Forward forward = createForwardEntity(forwardDto, currentUser, portAllocation, routeAllocation.getRoutes());
        if (!this.save(forward)) {
            return R.err("端口转发创建失败");
        }

        // 6. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            this.removeById(forward.getId());
            return R.err(nodeInfo.getErrorMessage());
        }

        // 7. 调用Gost服务创建转发
        R gostResult = createGostServices(forward, tunnel, permissionResult.getLimiter(), nodeInfo, permissionResult.getUserTunnel());

        if (gostResult.getCode() != 0) {
            this.removeById(forward.getId());
            return gostResult;
        }

        return R.ok();
    }

    private R createBatchForwards(ForwardDto source) {
        if (source.getInPort() == null) {
            return R.err("批量创建需要填写入口起始端口");
        }
        if (source.getBatchEndPort() < source.getInPort()) {
            return R.err("批量结束端口不能小于入口起始端口");
        }
        int count = source.getBatchEndPort() - source.getInPort() + 1;
        if (count > MAX_BATCH_FORWARD_COUNT) {
            return R.err("单次最多批量创建 " + MAX_BATCH_FORWARD_COUNT + " 条转发");
        }

        int sourceTargetPort = source.getTargetStartPort() != null
                ? source.getTargetStartPort()
                : extractPortFromAddress(source.getRemoteAddr().split(",")[0]);
        if (sourceTargetPort < 1) {
            return R.err("无法识别目标起始端口");
        }
        if (sourceTargetPort + count - 1 > 65535) {
            return R.err("目标端口范围超过 65535");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        for (int offset = 0; offset < count; offset++) {
            ForwardDto item = new ForwardDto();
            BeanUtils.copyProperties(source, item);
            int inPort = source.getInPort() + offset;
            int targetPort = sourceTargetPort + offset;
            item.setName(count == 1 ? source.getName() : source.getName() + "-" + inPort);
            item.setInPort(inPort);
            item.setRemoteAddr(replaceAddressPorts(source.getRemoteAddr(), targetPort));
            item.setBatchEndPort(null);
            item.setTargetStartPort(null);

            R result = createSingleForward(item);
            Map<String, Object> itemResult = new LinkedHashMap<>();
            itemResult.put("inPort", inPort);
            itemResult.put("targetPort", targetPort);
            itemResult.put("success", result.getCode() == 0);
            itemResult.put("message", result.getCode() == 0 ? "创建成功" : result.getMsg());
            results.add(itemResult);
            if (result.getCode() == 0) {
                successCount++;
            } else {
                break;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("successCount", successCount);
        summary.put("requestedCount", count);
        summary.put("results", results);
        if (successCount == count) {
            return R.ok(summary);
        }
        return R.err("已创建 " + successCount + " 条，第 " + (successCount + 1) + " 条失败：" + results.get(results.size() - 1).get("message"));
    }

    @Override
    public R getAllForwards() {
        UserInfo currentUser = getCurrentUserInfo();

        List<ForwardWithTunnelDto> forwardList;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            forwardList = baseMapper.selectForwardsWithTunnelByUserId(currentUser.getUserId());
        } else {
            forwardList = baseMapper.selectAllForwardsWithTunnel();
        }
        forwardList.forEach(this::markForwardNodeOffline);

        return R.ok(forwardList);
    }

    @Override
    public R getRouteEvents(Long forwardId) {
        UserInfo currentUser = getCurrentUserInfo();
        Forward forward = validateForwardExists(forwardId, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT id, forward_id AS forwardId, from_tunnel_id AS fromTunnelId, "
                        + "from_tunnel_name AS fromTunnelName, to_tunnel_id AS toTunnelId, "
                        + "to_tunnel_name AS toTunnelName, reason, trigger_type AS triggerType, "
                        + "status, detail, created_at AS createdAt FROM forward_route_switch "
                        + "WHERE forward_id = ? ORDER BY created_at DESC LIMIT 50",
                forwardId
        );
        return R.ok(events);
    }

    private void markForwardNodeOffline(ForwardWithTunnelDto forward) {
        Forward persisted = this.getById(forward.getId());
        if (persisted != null && persisted.getActiveTunnelId() != null
                && !Objects.equals(persisted.getActiveTunnelId(), forward.getTunnelId())) {
            Tunnel activeTunnel = validateTunnel(persisted.getActiveTunnelId());
            if (activeTunnel != null) {
                forward.setInIp(activeTunnel.getInIp());
                forward.setOutIp(activeTunnel.getOutIp());
                forward.setNodePath(activeTunnel.getNodePath());
                forward.setInNodeId(activeTunnel.getInNodeId());
                forward.setOutNodeId(activeTunnel.getOutNodeId());
                forward.setType(activeTunnel.getType());
                forward.setProtocol(activeTunnel.getProtocol());
                Node inNode = nodeService.getById(activeTunnel.getInNodeId());
                Node outNode = nodeService.getById(activeTunnel.getOutNodeId());
                forward.setInNodeStatus(inNode == null ? 0 : inNode.getStatus());
                forward.setOutNodeStatus(outNode == null ? 0 : outNode.getStatus());
            }
        }
        boolean nodeOffline = !Objects.equals(forward.getInNodeStatus(), 1);
        List<Long> nodePath = parseNodePath(forward.getNodePath(), forward.getInNodeId(), forward.getOutNodeId(), forward.getType());
        if (Objects.equals(forward.getType(), TUNNEL_TYPE_PORT_FORWARD)) {
            forward.setOutNodeStatus(forward.getInNodeStatus());
            forward.setNodeOffline(nodeOffline);
            return;
        }
        for (Long nodeId : nodePath) {
            Node node = nodeService.getById(nodeId);
            if (node == null || !Objects.equals(node.getStatus(), 1)) {
                nodeOffline = true;
                if (Objects.equals(nodeId, forward.getOutNodeId())) {
                    forward.setOutNodeStatus(node == null ? 0 : node.getStatus());
                }
            }
        }
        if (forward.getOutNodeStatus() == null) {
            Node outNode = nodeService.getById(forward.getOutNodeId());
            forward.setOutNodeStatus(outNode == null ? 0 : outNode.getStatus());
        }
        boolean routeUnhealthy = persisted != null
                && ROUTE_STATUS_UNHEALTHY.equals(getActiveRoute(persisted, getForwardRoutes(persisted)).getStatus());
        forward.setNodeOffline(nodeOffline || routeUnhealthy);
    }

    private List<Long> parseNodePath(String nodePath, Long inNodeId, Long outNodeId, Integer type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setNodePath(nodePath);
        tunnel.setInNodeId(inNodeId);
        tunnel.setOutNodeId(outNodeId);
        tunnel.setType(type);
        return TunnelRouteUtil.parseNodePath(tunnel);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R updateForward(ForwardUpdateDto forwardUpdateDto) {
        portAllocationLockMapper.lockForUpdate();
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward existForward = validateForwardExists(forwardUpdateDto.getId(), currentUser);
        if (existForward == null) {
            return R.err("转发不存在");
        }

        RouteValidationResult routeValidation = validateRouteTunnels(
                forwardUpdateDto.getTunnelId(),
                forwardUpdateDto.getRouteTunnelIds(),
                forwardUpdateDto.getRouteMode()
        );
        if (routeValidation.isHasError()) {
            return R.err(routeValidation.getErrorMessage());
        }
        Tunnel tunnel = routeValidation.getTunnels().get(0);
        boolean tunnelChanged = isTunnelChanged(existForward, forwardUpdateDto);
        // 4. 检查权限和限制
        UserPermissionResult permissionResult = null;
        if (tunnelChanged) {
            if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
                // 管理员操作自己的转发时，不需要检查权限限制
                if (Objects.equals(currentUser.getUserId(), existForward.getUserId())) {
                    permissionResult = UserPermissionResult.success(null, null);
                } else {
                    // 管理员操作用户转发时，需要检查原用户是否有新隧道权限
                    // 获取原转发用户的信息
                    User originalUser = userService.getById(existForward.getUserId());
                    if (originalUser == null) {
                        return R.err("用户不存在");
                    }

                    // 检查原用户是否有新隧道权限
                    UserTunnel userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
                    boolean ownedTunnel = Objects.equals(tunnel.getOwnerUserId(), existForward.getUserId());
                    if (!ownedTunnel && userTunnel == null) {
                        return R.err("用户没有该隧道权限");
                    }

                    if (!ownedTunnel && userTunnel.getStatus() != 1) {
                        return R.err("隧道被禁用");
                    }

                    // 检查隧道权限到期时间
                    if (!ownedTunnel && userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
                        return R.err("用户的该隧道权限已到期");
                    }

                    // 检查原用户的流量和转发数量限制
                    R quotaCheckResult = userQuotaService.checkTunnelQuota(existForward.getUserId(), tunnel, forwardUpdateDto.getId());
                    if (quotaCheckResult.getCode() != 0) {
                        return R.err("用户" + quotaCheckResult.getMsg());
                    }

                    permissionResult = UserPermissionResult.success(ownedTunnel ? null : userTunnel.getSpeedId(), ownedTunnel ? null : userTunnel);
                }
            } else {
                // 普通用户检查自己的权限
                permissionResult = checkUserPermissions(currentUser, tunnel, forwardUpdateDto.getId());
                if (permissionResult.isHasError()) {
                    return R.err(permissionResult.getErrorMessage());
                }
            }
        }
        R candidatePermission = checkCandidateTunnelPermissions(currentUser, routeValidation.getTunnels(), tunnel.getId().intValue(), forwardUpdateDto.getId());
        if (candidatePermission.getCode() != 0) {
            return candidatePermission;
        }

        // 5. 获取UserTunnel（即使隧道未变化也需要获取，用于构建服务名称）
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null && !Objects.equals(tunnel.getOwnerUserId(), currentUser.getUserId())) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员用户也需要获取UserTunnel（如果存在的话），用于构建正确的服务名称
            // 通过forward记录获取原始的用户ID
            userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
        }

        Integer specifiedInPort = forwardUpdateDto.getInPort() == null
                ? existForward.getInPort()
                : forwardUpdateDto.getInPort();
        PortAllocation primaryAllocation = allocatePorts(tunnel, specifiedInPort, forwardUpdateDto.getId());
        if (primaryAllocation.isHasError()) {
            return R.err(primaryAllocation.getErrorMessage());
        }
        RouteAllocationResult routeAllocation = allocateRouteConfigs(
                routeValidation.getTunnels(),
                primaryAllocation,
                forwardUpdateDto.getId()
        );
        if (routeAllocation.isHasError()) {
            return R.err(routeAllocation.getErrorMessage());
        }

        // 6. 更新Forward对象
        Forward updatedForward = updateForwardEntity(
                forwardUpdateDto,
                existForward,
                primaryAllocation,
                routeAllocation.getRoutes()
        );

        // 7. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 8. 候选线路、协议或端口可能同时变化，统一替换整组配置
        R gostResult = replaceGostServices(
                existForward,
                updatedForward,
                tunnel,
                permissionResult != null ? permissionResult.getLimiter() : null,
                nodeInfo,
                userTunnel
        );

        if (gostResult.getCode() != 0) {
            return gostResult;
        }
        updatedForward.setStatus(1);
        // 9. 保存更新
        boolean result = this.updateById(updatedForward);
        return result ? R.ok("端口转发更新成功") : R.err("端口转发更新失败");
    }

    @Override
    public R deleteForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }

        List<ForwardRouteDto> forwardRoutes = getForwardRoutes(forward);
        ForwardRouteDto activeRoute = getActiveRoute(forward, forwardRoutes);

        // 3. 获取当前实际线路信息
        Tunnel tunnel = validateTunnel(activeRoute.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 权限检查（仅普通用户需要）
        UserTunnel activeUserTunnel = null;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            activeUserTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (activeUserTunnel == null && !Objects.equals(tunnel.getOwnerUserId(), currentUser.getUserId())) {
                return R.err("你没有该隧道权限");
            }
        }
        UserTunnel serviceUserTunnel = getPrimaryUserTunnel(forward);

        // 5. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 6. 调用Gost服务删除转发
        R gostResult = deleteGostServices(forward, tunnel, nodeInfo, serviceUserTunnel);
        if (gostResult.getCode() != 0) {
            return gostResult;
        }

        // 7. 删除转发记录
        boolean result = this.removeById(id);
        if (result) {
            deleteRouteEvents(id);
            return R.ok("端口转发删除成功");
        } else {
            return R.err("端口转发删除失败");
        }
    }

    @Override
    public R pauseForward(Long id) {
        return changeForwardStatus(id, FORWARD_STATUS_PAUSED, "暂停", "PauseService");
    }

    @Override
    public R resumeForward(Long id) {
        return changeForwardStatus(id, FORWARD_STATUS_ACTIVE, "恢复", "ResumeService");
    }

    @Override
    public R forceDeleteForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限操作
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }

        // 3. 直接删除转发记录，跳过GOST服务删除
        boolean result = this.removeById(id);
        if (result) {
            deleteRouteEvents(id);
            return R.ok("端口转发强制删除成功");
        } else {
            return R.err("端口转发强制删除失败");
        }
    }

    /**
     * 改变转发状态（暂停/恢复）
     */
    private R changeForwardStatus(Long id, int targetStatus, String operation, String gostMethod) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        List<ForwardRouteDto> forwardRoutes = getForwardRoutes(forward);
        ForwardRouteDto activeRoute = getActiveRoute(forward, forwardRoutes);

        // 3. 获取当前实际线路信息
        Tunnel tunnel = validateTunnel(activeRoute.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 恢复服务时需要额外检查
        UserTunnel activeUserTunnel = null;
        if (targetStatus == FORWARD_STATUS_ACTIVE) {
            if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
                return R.err("隧道已禁用，无法恢复服务");
            }

            // 普通用户需要检查流量和账户状态
            if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
                R flowCheckResult = userQuotaService.checkTunnelQuota(currentUser.getUserId(), tunnel, forward.getId());
                if (flowCheckResult.getCode() != 0) {
                    return flowCheckResult;
                }
                for (ForwardRouteDto route : forwardRoutes) {
                    Tunnel routeTunnel = tunnelService.getById(route.getTunnelId());
                    if (routeTunnel == null) return R.err("候选线路不存在：" + route.getTunnelName());
                    R routeQuota = userQuotaService.checkTunnelQuota(currentUser.getUserId(), routeTunnel, forward.getId());
                    if (routeQuota.getCode() != 0) return routeQuota;
                }

                activeUserTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
                if (activeUserTunnel == null && !Objects.equals(tunnel.getOwnerUserId(), currentUser.getUserId())) {
                    return R.err("你没有该隧道权限");
                }

                if (activeUserTunnel != null && activeUserTunnel.getStatus() != 1) {
                    return R.err("隧道被禁用");
                }
            }
        }

        // 5. 权限检查（仅普通用户需要）
        if (currentUser.getRoleId() != ADMIN_ROLE_ID && activeUserTunnel == null) {
            activeUserTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (activeUserTunnel == null && !Objects.equals(tunnel.getOwnerUserId(), currentUser.getUserId())) {
                return R.err("你没有该隧道权限");
            }
        }

        // 服务名由主线路的用户隧道关系决定，自动切线后也必须保持不变。
        UserTunnel serviceUserTunnel = getPrimaryUserTunnel(forward);

        // 7. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 8. 调用Gost服务
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), serviceUserTunnel);
        GostDto gostResult;
        List<ForwardRouteDto> routes = getForwardRoutes(forward);

        if ("PauseService".equals(gostMethod)) {
            gostResult = GostUtil.PauseService(
                    nodeInfo.getInNode().getId(),
                    serviceName,
                    normalizeProtocolMode(forward.getProtocolMode())
            );
            changeRouteInfrastructureStatus(forward, routes, serviceName, true);
        } else {
            gostResult = GostUtil.ResumeService(
                    nodeInfo.getInNode().getId(),
                    serviceName,
                    normalizeProtocolMode(forward.getProtocolMode())
            );
            changeRouteInfrastructureStatus(forward, routes, serviceName, false);
        }

        if (!isGostOperationSuccess(gostResult)) {
            return R.err(operation + "服务失败：" + gostMessage(gostResult));
        }

        // 9. 更新转发状态
        forward.setStatus(targetStatus);
        forward.setUpdatedTime(System.currentTimeMillis());
        boolean result = this.updateById(forward);

        return result ? R.ok("服务已" + operation) : R.err("更新状态失败");
    }

    @Override
    public R diagnoseForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限访问
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        List<ForwardRouteDto> forwardRoutes = getForwardRoutes(forward);
        ForwardRouteDto activeRoute = getActiveRoute(forward, forwardRoutes);

        // 3. 使用当前实际线路诊断
        Tunnel tunnel = validateTunnel(activeRoute.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        Forward diagnosticForward = forwardForRoute(forward, activeRoute);

        // 4. 获取入口节点信息
        Node inNode = nodeService.getById(tunnel.getInNodeId());
        if (inNode == null) {
            return R.err("入口节点不存在");
        }


        List<DiagnosisResult> results = new ArrayList<>();
        String[] remoteAddresses = diagnosticForward.getRemoteAddr().split(",");
        // 6. 根据隧道类型执行不同的诊断策略
        if (tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD) {
            // 端口转发：入口节点直接TCP ping目标地址
            for (String remoteAddress : remoteAddresses) {
                // 提取IP和端口
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }

                DiagnosisResult result = performTcpPingDiagnosis(inNode, targetIp, targetPort, "转发->目标");
                results.add(result);
            }
        } else {
            NodeInfo nodeInfo = getRequiredNodes(tunnel);
            if (nodeInfo.isHasError()) {
                return R.err(nodeInfo.getErrorMessage());
            }
            List<Integer> hopPorts = getForwardHopPorts(diagnosticForward, tunnel);
            List<Node> pathNodes = nodeInfo.getPathNodes();
            for (int i = 0; i < pathNodes.size() - 1; i++) {
                Node fromNode = pathNodes.get(i);
                Node toNode = pathNodes.get(i + 1);
                DiagnosisResult segmentResult = performTcpPingDiagnosis(
                        fromNode,
                        toNode.getServerIp(),
                        hopPorts.get(i),
                        fromNode.getName() + "->" + toNode.getName()
                );
                results.add(segmentResult);
            }

            Node outNode = pathNodes.get(pathNodes.size() - 1);
            for (String remoteAddress : remoteAddresses) {
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }
                DiagnosisResult outToTargetResult = performTcpPingDiagnosis(outNode, targetIp, targetPort, "出口->目标");
                results.add(outToTargetResult);
            }

        }

        // 7. 构建诊断报告
        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("forwardId", id);
        diagnosisReport.put("forwardName", forward.getName());
        diagnosisReport.put("activeTunnelId", activeRoute.getTunnelId());
        diagnosisReport.put("activeTunnelName", activeRoute.getTunnelName());
        diagnosisReport.put("tunnelType", tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD ? "端口转发" : "隧道转发");
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    @Override
    public R updateForwardOrder(Map<String, Object> params) {
        try {
            // 1. 获取当前用户信息
            UserInfo currentUser = getCurrentUserInfo();

            // 2. 验证参数
            if (!params.containsKey("forwards")) {
                return R.err("缺少forwards参数");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> forwardsList = (List<Map<String, Object>>) params.get("forwards");
            if (forwardsList == null || forwardsList.isEmpty()) {
                return R.err("forwards参数不能为空");
            }

            // 3. 验证用户权限（只能更新自己的转发）
            if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
                // 普通用户只能更新自己的转发
                List<Long> forwardIds = forwardsList.stream()
                        .map(item -> Long.valueOf(item.get("id").toString()))
                        .collect(Collectors.toList());

                // 检查所有转发是否属于当前用户
                QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
                queryWrapper.in("id", forwardIds);
                queryWrapper.eq("user_id", currentUser.getUserId());

                long count = this.count(queryWrapper);
                if (count != forwardIds.size()) {
                    return R.err("只能更新自己的转发排序");
                }
            }

            // 4. 批量更新排序
            List<Forward> forwardsToUpdate = new ArrayList<>();
            for (Map<String, Object> forwardData : forwardsList) {
                Long id = Long.valueOf(forwardData.get("id").toString());
                Integer inx = Integer.valueOf(forwardData.get("inx").toString());

                Forward forward = new Forward();
                forward.setId(id);
                forward.setInx(inx);
                forwardsToUpdate.add(forward);
            }

            // 5. 执行批量更新
            boolean success = this.updateBatchById(forwardsToUpdate);
            if (success) {
                log.info("用户 {} 更新了 {} 个转发的排序", currentUser.getUserName(), forwardsToUpdate.size());
                return R.ok("排序更新成功");
            } else {
                return R.err("排序更新失败");
            }

        } catch (Exception e) {
            log.error("更新转发排序失败", e);
            return R.err("更新排序时发生错误: " + e.getMessage());
        }
    }

    /**
     * 从地址字符串中提取IP地址
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private String extractIpFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1) {
                return address.substring(1, closeBracket);
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0) {
            return address.substring(0, lastColon);
        }

        // 如果没有端口，直接返回地址
        return address;
    }

    /**
     * 从地址字符串中提取端口号
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private int extractPortFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return -1;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1 && closeBracket + 1 < address.length() && address.charAt(closeBracket + 1) == ':') {
                String portStr = address.substring(closeBracket + 2);
                try {
                    return Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < address.length()) {
            String portStr = address.substring(lastColon + 1);
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // 如果没有端口，返回-1表示无法解析
        return -1;
    }

    /**
     * 执行TCP ping诊断
     *
     * @param node        执行TCP ping的节点
     * @param targetIp    目标IP地址
     * @param port        目标端口
     * @param description 诊断描述
     * @return 诊断结果
     */
    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 构建TCP ping请求数据
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 2);
            tcpPingData.put("timeout", 3000); // 5秒超时

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
     * 获取当前用户信息
     */
    private UserInfo getCurrentUserInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        String userName = JwtUtil.getNameFromToken();
        return new UserInfo(userId, roleId, userName);
    }

    /**
     * 验证隧道是否存在
     */
    private Tunnel validateTunnel(Integer tunnelId) {
        return tunnelService.getById(tunnelId);
    }

    /**
     * 验证转发是否存在且用户有权限访问
     */
    private Forward validateForwardExists(Long forwardId, UserInfo currentUser) {
        Forward forward = this.getById(forwardId);
        if (forward == null) {
            return null;
        }

        // 普通用户只能操作自己的转发
        if (currentUser.getRoleId() != ADMIN_ROLE_ID &&
                !Objects.equals(currentUser.getUserId(), forward.getUserId())) {
            return null;
        }

        return forward;
    }

    /**
     * 获取所需的节点信息
     */
    private NodeInfo getRequiredNodes(Tunnel tunnel) {
        List<Long> nodePath = TunnelRouteUtil.parseNodePath(tunnel);
        if (nodePath.isEmpty()) {
            return NodeInfo.error("入口节点不存在");
        }

        List<Node> pathNodes = new ArrayList<>();
        for (int i = 0; i < nodePath.size(); i++) {
            Node node = nodeService.getById(nodePath.get(i));
            if (node == null) {
                return NodeInfo.error((i == 0 ? "入口" : "路径") + "节点不存在：" + nodePath.get(i));
            }
            pathNodes.add(node);
        }

        Node inNode = pathNodes.get(0);
        Node outNode = tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD
                ? pathNodes.get(pathNodes.size() - 1)
                : inNode;

        return NodeInfo.success(inNode, outNode, pathNodes);
    }

    /**
     * 检查用户权限和限制
     */
    private UserPermissionResult checkUserPermissions(UserInfo currentUser, Tunnel tunnel, Long excludeForwardId) {
        if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
            return UserPermissionResult.success(null, null);
        }

        // 获取用户信息
        User userInfo = userService.getById(currentUser.getUserId());
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("当前账号已到期");
        }

        boolean ownedTunnel = Objects.equals(tunnel.getOwnerUserId(), currentUser.getUserId());

        // 自建隧道直接使用用户套餐限制；共享隧道继续使用管理员分配的隧道配额。
        UserTunnel userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
        if (!ownedTunnel && userTunnel == null) {
            return UserPermissionResult.error("你没有该隧道权限");
        }

        if (!ownedTunnel && userTunnel.getStatus() != 1) {
            return UserPermissionResult.error("隧道被禁用");
        }

        // 检查隧道权限到期时间
        if (!ownedTunnel && userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("该隧道权限已到期");
        }

        R quotaCheckResult = userQuotaService.checkTunnelQuota(currentUser.getUserId(), tunnel, excludeForwardId);
        if (quotaCheckResult.getCode() != 0) {
            return UserPermissionResult.error(quotaCheckResult.getMsg());
        }

        return UserPermissionResult.success(ownedTunnel ? null : userTunnel.getSpeedId(), ownedTunnel ? null : userTunnel);
    }

    /**
     * 检查用户转发数量限制
     */
    private R checkForwardQuota(Integer userId, Integer tunnelId, UserTunnel userTunnel, User userInfo, Long excludeForwardId) {
        // 检查用户总转发数量限制
        long userForwardCount = this.count(new QueryWrapper<Forward>().eq("user_id", userId));
        if (userForwardCount >= userInfo.getNum()) {
            return R.err("用户总转发数量已达上限，当前限制：" + userInfo.getNum() + "个");
        }

        // 检查用户在该隧道的转发数量限制
        QueryWrapper<Forward> tunnelQuery = new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId);

        if (excludeForwardId != null) {
            tunnelQuery.ne("id", excludeForwardId);
        }

        long tunnelForwardCount = this.count(tunnelQuery);
        if (userTunnel != null && tunnelForwardCount >= userTunnel.getNum()) {
            return R.err("该隧道转发数量已达上限，当前限制：" + userTunnel.getNum() + "个");
        }

        return R.ok();
    }

    /**
     * 检查用户流量限制
     */
    private R checkUserFlowLimits(Integer userId, Tunnel tunnel) {
        User userInfo = userService.getById(userId);
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return R.err("当前账号已到期");
        }

        UserTunnel userTunnel = getUserTunnel(userId, tunnel.getId().intValue());
        boolean ownedTunnel = Objects.equals(tunnel.getOwnerUserId(), userId);
        if (!ownedTunnel && userTunnel == null) {
            return R.err("你没有该隧道权限");
        }

        // 检查隧道权限到期时间
        if (!ownedTunnel && userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return R.err("该隧道权限已到期，无法恢复服务");
        }

        // 检查用户总流量限制
        if (userInfo.getFlow() * BYTES_TO_GB <= userInfo.getInFlow() + userInfo.getOutFlow()) {
            return R.err("用户总流量已用完，无法恢复服务");
        }

        // 检查隧道流量限制
        // 数据库中的流量已按计费类型处理，直接使用总和
        long tunnelFlow = ownedTunnel ? 0L : userTunnel.getInFlow() + userTunnel.getOutFlow();

        if (!ownedTunnel && userTunnel.getFlow() * BYTES_TO_GB <= tunnelFlow) {
            return R.err("该隧道流量已用完，无法恢复服务");
        }

        return R.ok();
    }

    private RouteValidationResult validateRouteTunnels(Integer primaryTunnelId, List<Integer> requestedTunnelIds, String requestedMode) {
        LinkedHashSet<Integer> tunnelIds = new LinkedHashSet<>();
        tunnelIds.add(primaryTunnelId);
        if (requestedTunnelIds != null) {
            tunnelIds.addAll(requestedTunnelIds);
        }
        if (ROUTE_MODE_SINGLE.equals(normalizeRouteMode(requestedMode, tunnelIds.size()))) {
            tunnelIds = new LinkedHashSet<>(Collections.singletonList(primaryTunnelId));
        }

        List<Tunnel> tunnels = new ArrayList<>();
        Long entryNodeId = null;
        for (Integer tunnelId : tunnelIds) {
            Tunnel tunnel = validateTunnel(tunnelId);
            if (tunnel == null) {
                return RouteValidationResult.error("隧道不存在：" + tunnelId);
            }
            if (!Objects.equals(tunnel.getStatus(), TUNNEL_STATUS_ACTIVE)) {
                return RouteValidationResult.error("隧道已禁用：" + tunnel.getName());
            }
            if (entryNodeId == null) {
                entryNodeId = tunnel.getInNodeId();
            } else if (!Objects.equals(entryNodeId, tunnel.getInNodeId())) {
                return RouteValidationResult.error("候选线路必须使用同一个入口节点");
            }
            tunnels.add(tunnel);
        }
        return RouteValidationResult.success(tunnels);
    }

    private R checkCandidateTunnelPermissions(UserInfo currentUser, List<Tunnel> tunnels, Integer primaryTunnelId, Long excludeForwardId) {
        if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
            return R.ok();
        }
        for (Tunnel tunnel : tunnels) {
            if (Objects.equals(tunnel.getId().intValue(), primaryTunnelId)) {
                continue;
            }
            if (Objects.equals(tunnel.getOwnerUserId(), currentUser.getUserId())) {
                R quota = userQuotaService.checkTunnelQuota(currentUser.getUserId(), tunnel, excludeForwardId);
                if (quota.getCode() != 0) return quota;
                continue;
            }
            UserTunnel userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有候选隧道权限：" + tunnel.getName());
            }
            if (!Objects.equals(userTunnel.getStatus(), 1)) {
                return R.err("候选隧道已禁用：" + tunnel.getName());
            }
            if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
                return R.err("候选隧道权限已到期：" + tunnel.getName());
            }
            R quota = userQuotaService.checkTunnelQuota(currentUser.getUserId(), tunnel, excludeForwardId);
            if (quota.getCode() != 0) return quota;
        }
        return R.ok();
    }

    private String normalizeRouteMode(String routeMode, int routeCount) {
        if (routeCount <= 1) {
            return ROUTE_MODE_SINGLE;
        }
        if (ROUTE_MODE_FAILOVER.equals(routeMode) || ROUTE_MODE_LATENCY.equals(routeMode)) {
            return routeMode;
        }
        return ROUTE_MODE_FAILOVER;
    }

    private String normalizeProtocolMode(String protocolMode) {
        if (Objects.equals(protocolMode, "tcp") || Objects.equals(protocolMode, "udp")) {
            return protocolMode;
        }
        return PROTOCOL_MODE_TCP_UDP;
    }

    private RouteAllocationResult allocateRouteConfigs(List<Tunnel> tunnels, PortAllocation primaryAllocation, Long excludeForwardId) {
        List<ForwardRouteDto> routes = new ArrayList<>();
        Map<String, Set<Integer>> reservedPorts = new HashMap<>();

        Tunnel primaryTunnel = tunnels.get(0);
        ForwardRouteDto primaryRoute = buildRouteDto(primaryTunnel, 0, primaryAllocation);
        routes.add(primaryRoute);
        reserveRoutePorts(primaryTunnel, primaryAllocation.getHopPorts(), reservedPorts);

        for (int i = 1; i < tunnels.size(); i++) {
            Tunnel tunnel = tunnels.get(i);
            PortAllocation routePorts = allocateRoutePorts(tunnel, excludeForwardId, reservedPorts);
            if (routePorts.isHasError()) {
                return RouteAllocationResult.error(routePorts.getErrorMessage());
            }
            routes.add(buildRouteDto(tunnel, i, routePorts));
            reserveRoutePorts(tunnel, routePorts.getHopPorts(), reservedPorts);
        }
        return RouteAllocationResult.success(routes);
    }

    private ForwardRouteDto buildRouteDto(Tunnel tunnel, int priority, PortAllocation allocation) {
        ForwardRouteDto route = new ForwardRouteDto();
        route.setTunnelId(tunnel.getId().intValue());
        route.setTunnelName(tunnel.getName());
        route.setPriority(priority);
        route.setOutPort(allocation.getOutPort());
        route.setHopPorts(TunnelRouteUtil.joinHopPorts(allocation.getHopPorts()));
        return route;
    }

    private PortAllocation allocateRoutePorts(Tunnel tunnel, Long excludeForwardId, Map<String, Set<Integer>> reservedPorts) {
        Integer outPort = null;
        List<Integer> hopPorts = new ArrayList<>();
        if (Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)) {
            List<Long> nodePath = TunnelRouteUtil.parseNodePath(tunnel);
            if (nodePath.size() < 2) {
                return PortAllocation.error("隧道节点路径无效：" + tunnel.getName());
            }
            for (int i = 1; i < nodePath.size(); i++) {
                Long nodeId = nodePath.get(i);
                Node node = nodeService.getById(nodeId);
                if (node == null) {
                    return PortAllocation.error("节点不存在：" + nodeId);
                }
                String namespace = PortNamespaceUtil.fromNode(node);
                Integer hopPort = allocatePortForNode(nodeId, excludeForwardId, reservedPorts.get(namespace));
                if (hopPort == null) {
                    return PortAllocation.error("节点 " + nodeId + " 端口已满，无法为线路 " + tunnel.getName() + " 分配端口");
                }
                hopPorts.add(hopPort);
                outPort = hopPort;
                reservedPorts.computeIfAbsent(namespace, key -> new HashSet<>()).add(hopPort);
            }
        }
        return PortAllocation.success(null, outPort, hopPorts);
    }

    private void reserveRoutePorts(Tunnel tunnel, List<Integer> hopPorts, Map<String, Set<Integer>> reservedPorts) {
        List<Long> nodePath = TunnelRouteUtil.parseNodePath(tunnel);
        for (int i = 1; i < nodePath.size() && i - 1 < hopPorts.size(); i++) {
            Node node = nodeService.getById(nodePath.get(i));
            if (node != null) {
                reservedPorts.computeIfAbsent(PortNamespaceUtil.fromNode(node), key -> new HashSet<>())
                        .add(hopPorts.get(i - 1));
            }
        }
    }

    private String replaceAddressPorts(String remoteAddr, int targetPort) {
        return Arrays.stream(remoteAddr.split("[,\\n]"))
                .map(String::trim)
                .filter(address -> !address.isEmpty())
                .map(address -> {
                    String host = extractIpFromAddress(address);
                    if (host == null) {
                        return address;
                    }
                    return host.contains(":")
                            ? "[" + host + "]:" + targetPort
                            : host + ":" + targetPort;
                })
                .collect(Collectors.joining(","));
    }

    /**
     * 分配端口
     */
    private PortAllocation allocatePorts(Tunnel tunnel, Integer specifiedInPort) {
        return allocatePorts(tunnel, specifiedInPort, null);
    }

    /**
     * 分配端口
     */
    private PortAllocation allocatePorts(Tunnel tunnel, Integer specifiedInPort, Long excludeForwardId) {
        Integer inPort;

        if (specifiedInPort != null) {
            // 用户指定了入口端口，需要检查是否可用
            if (!isInPortAvailable(tunnel, specifiedInPort, excludeForwardId)) {
                return PortAllocation.error("指定的入口端口 " + specifiedInPort + " 已被占用或不在允许范围内");
            }
            inPort = specifiedInPort;
        } else {
            // 用户未指定端口时自动分配
            inPort = allocateInPort(tunnel, excludeForwardId);
            if (inPort == null) {
                return PortAllocation.error("隧道入口端口已满，无法分配新端口");
            }
        }

        Integer outPort = null;
        List<Integer> hopPorts = new ArrayList<>();
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            List<Long> nodePath = TunnelRouteUtil.parseNodePath(tunnel);
            Map<String, Set<Integer>> reservedPorts = new HashMap<>();
            if (nodePath.size() < 2) {
                return PortAllocation.error("隧道节点路径无效，至少需要入口和出口两个节点");
            }
            for (int i = 1; i < nodePath.size(); i++) {
                Long nodeId = nodePath.get(i);
                Node node = nodeService.getById(nodeId);
                if (node == null) {
                    return PortAllocation.error("节点不存在：" + nodeId);
                }
                String namespace = PortNamespaceUtil.fromNode(node);
                Integer hopPort = allocatePortForNode(nodeId, excludeForwardId, reservedPorts.get(namespace));
                if (hopPort == null) {
                    return PortAllocation.error("节点 " + nodeId + " 端口已满，无法分配新端口");
                }
                hopPorts.add(hopPort);
                outPort = hopPort;
                reservedPorts.computeIfAbsent(namespace, key -> new HashSet<>()).add(hopPort);
            }
        }

        return PortAllocation.success(inPort, outPort, hopPorts);
    }

    /**
     * 创建Forward实体对象
     */
    private Forward createForwardEntity(ForwardDto forwardDto, UserInfo currentUser, PortAllocation portAllocation, List<ForwardRouteDto> routes) {
        Forward forward = new Forward();
        // 先复制DTO的属性，再设置其他属性，避免被覆盖
        BeanUtils.copyProperties(forwardDto, forward);
        forward.setStatus(FORWARD_STATUS_ACTIVE);
        forward.setInPort(portAllocation.getInPort());
        forward.setOutPort(portAllocation.getOutPort());
        forward.setHopPorts(TunnelRouteUtil.joinHopPorts(portAllocation.getHopPorts()));
        forward.setRouteMode(normalizeRouteMode(forwardDto.getRouteMode(), routes.size()));
        forward.setRouteConfig(JSON.toJSONString(routes));
        forward.setActiveTunnelId(routes.get(0).getTunnelId());
        forward.setProtocolMode(normalizeProtocolMode(forwardDto.getProtocolMode()));
        forward.setUserId(currentUser.getUserId());
        forward.setUserName(currentUser.getUserName());
        forward.setCreatedTime(System.currentTimeMillis());
        forward.setUpdatedTime(System.currentTimeMillis());
        return forward;
    }

    /**
     * 更新Forward实体对象
     */
    private Forward updateForwardEntity(ForwardUpdateDto forwardUpdateDto, Forward existForward, PortAllocation portAllocation, List<ForwardRouteDto> routes) {
        Forward forward = new Forward();
        BeanUtils.copyProperties(forwardUpdateDto, forward);
        forward.setInPort(portAllocation.getInPort());
        forward.setOutPort(portAllocation.getOutPort());
        forward.setHopPorts(TunnelRouteUtil.joinHopPorts(portAllocation.getHopPorts()));
        forward.setRouteMode(normalizeRouteMode(forwardUpdateDto.getRouteMode(), routes.size()));
        forward.setRouteConfig(JSON.toJSONString(routes));
        forward.setActiveTunnelId(routes.get(0).getTunnelId());
        forward.setProtocolMode(normalizeProtocolMode(forwardUpdateDto.getProtocolMode()));
        forward.setTargetHealth(existForward.getTargetHealth());
        forward.setLastHealthCheck(existForward.getLastHealthCheck());
        forward.setUpdatedTime(System.currentTimeMillis());
        return forward;
    }

    /**
     * 创建Gost服务
     */
    private R createGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        List<ForwardRouteDto> routes = getForwardRoutes(forward);

        for (ForwardRouteDto route : routes) {
            Tunnel routeTunnel = validateTunnel(route.getTunnelId());
            if (routeTunnel == null) {
                deleteRouteInfrastructureBestEffort(forward, routes, serviceName);
                return R.err("候选隧道不存在：" + route.getTunnelId());
            }
            R routeResult = createRouteInfrastructure(forward, route, routeTunnel, serviceName);
            if (routeResult.getCode() != 0) {
                deleteRouteInfrastructureBestEffort(forward, routes, serviceName);
                return routeResult;
            }
        }

        ForwardRouteDto activeRoute = getActiveRoute(forward, routes);
        Tunnel activeTunnel = validateTunnel(activeRoute.getTunnelId());
        Node activeInNode = nodeService.getById(activeTunnel.getInNodeId());
        R serviceResult = createMainService(activeInNode, serviceName, forward, activeRoute, limiter, activeTunnel);
        if (serviceResult.getCode() != 0) {
            deleteRouteInfrastructureBestEffort(forward, routes, serviceName);
            return serviceResult;
        }
        return R.ok();
    }

    /**
     * 更新Gost服务
     */
    private R updateGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        List<ForwardRouteDto> routes = getForwardRoutes(forward);
        ForwardRouteDto activeRoute = getActiveRoute(forward, routes);
        Tunnel activeTunnel = validateTunnel(activeRoute.getTunnelId());
        if (activeTunnel == null) {
            return R.err("当前线路不存在");
        }
        Node activeInNode = nodeService.getById(activeTunnel.getInNodeId());
        R serviceResult = updateMainService(activeInNode, serviceName, forward, activeRoute, limiter, activeTunnel);
        if (serviceResult.getCode() != 0) {
            updateForwardStatusToError(forward);
            return serviceResult;
        }

        return R.ok();
    }

    private R replaceGostServices(Forward oldForward, Forward updatedForward, Tunnel newTunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        deleteForwardInfrastructureBestEffort(oldForward);
        R createResult = createGostServices(updatedForward, newTunnel, limiter, nodeInfo, userTunnel);
        if (createResult.getCode() != 0) {
            log.warn("创建新线路组失败，尝试恢复原转发 {}：{}", oldForward.getId(), createResult.getMsg());
            Tunnel oldTunnel = validateTunnel(oldForward.getTunnelId());
            if (oldTunnel != null) {
                NodeInfo oldNodeInfo = getRequiredNodes(oldTunnel);
                UserTunnel oldUserTunnel = getUserTunnel(oldForward.getUserId(), oldTunnel.getId().intValue());
                Integer oldLimiter = oldUserTunnel == null ? null : oldUserTunnel.getSpeedId();
                createGostServices(oldForward, oldTunnel, oldLimiter, oldNodeInfo, oldUserTunnel);
            }
        }
        return createResult;
    }

    /**
     * 隧道变化时更新Gost服务：先删除原配置，再创建新配置
     */
    private R updateGostServicesWithTunnelChange(Forward existForward, Forward updatedForward, Tunnel newTunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        // 1. 获取原隧道信息
        Tunnel oldTunnel = tunnelService.getById(existForward.getTunnelId());
        if (oldTunnel == null) {
            return R.err("原隧道不存在，无法删除旧配置");
        }

        // 2. 删除原有的Gost服务配置
        R deleteResult = deleteOldGostServices(existForward, oldTunnel);
        if (deleteResult.getCode() != 0) {
            // 删除失败时记录日志，但不影响后续创建（可能原配置已不存在）
            log.info("删除原隧道{}的Gost配置失败: {}", oldTunnel.getId(), deleteResult.getMsg());
        }

        // 3. 创建新的Gost服务配置
        R createResult = createGostServices(updatedForward, newTunnel, limiter, nodeInfo, userTunnel);
        if (createResult.getCode() != 0) {
            updateForwardStatusToError(updatedForward);
            return R.err("创建新隧道配置失败: " + createResult.getMsg());
        }

        return R.ok();
    }

    /**
     * 删除原有的Gost服务（隧道变化时专用）
     */
    private R deleteOldGostServices(Forward forward, Tunnel oldTunnel) {
        // 获取原隧道的用户隧道关系
        UserTunnel oldUserTunnel = getUserTunnel(forward.getUserId(), oldTunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), oldUserTunnel);

        // 获取原隧道的节点信息
        NodeInfo oldNodeInfo = getRequiredNodes(oldTunnel);

        // 删除主服务（使用原隧道的入口节点）
        if (!oldNodeInfo.isHasError() && oldNodeInfo.getInNode() != null) {
            GostDto serviceResult = GostUtil.DeleteService(
                    oldNodeInfo.getInNode().getId(),
                    serviceName,
                    normalizeProtocolMode(forward.getProtocolMode())
            );
            if (!isGostOperationSuccess(serviceResult)) {
                log.info("删除主服务失败: {}", gostMessage(serviceResult));
            }
        }

        // 如果原隧道是隧道转发类型，需要删除链和远程服务
        if (oldTunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            // 删除链服务
            if (!oldNodeInfo.isHasError() && oldNodeInfo.getInNode() != null) {
                GostDto chainResult = GostUtil.DeleteChains(oldNodeInfo.getInNode().getId(), serviceName);
                if (!isGostOperationSuccess(chainResult)) {
                    log.info("删除链服务失败: {}", gostMessage(chainResult));
                }
            }

            if (!oldNodeInfo.isHasError()) {
                deleteTunnelHopServices(oldNodeInfo, serviceName);
            } else {
                for (Long nodeId : TunnelRouteUtil.parseNodePath(oldTunnel).stream().skip(1).collect(Collectors.toList())) {
                    GostDto remoteResult = GostUtil.DeleteRemoteService(nodeId, serviceName);
                    if (!isGostOperationSuccess(remoteResult)) {
                        log.info("删除远程服务失败: {}", remoteResult == null ? "节点无响应" : remoteResult.getMsg());
                    }
                }
            }
        }

        return R.ok();
    }

    /**
     * 删除Gost服务
     */
    private R deleteGostServices(Forward forward, Tunnel tunnel, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        // 删除主服务
        GostDto serviceResult = GostUtil.DeleteService(
                nodeInfo.getInNode().getId(),
                serviceName,
                normalizeProtocolMode(forward.getProtocolMode())
        );
        if (!isGostOperationSuccess(serviceResult)) {
            log.warn("删除转发 {} 的入口服务失败：{}", forward.getId(), gostMessage(serviceResult));
        }

        deleteRouteInfrastructureBestEffort(forward, getForwardRoutes(forward), serviceName);
        return R.ok();
    }

    /**
     * 创建链服务
     */
    private R createChainService(NodeInfo nodeInfo, String serviceName, List<Integer> hopPorts, String protocol, String interfaceName) {
        List<String> remoteAddrs = buildHopAddresses(nodeInfo, hopPorts);
        GostDto result = GostUtil.AddChains(nodeInfo.getInNode().getId(), serviceName, remoteAddrs, protocol, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(gostMessage(result));
    }

    /**
     * 创建远程服务
     */
    private R createRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        GostDto result = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(gostMessage(result));
    }

    private R createRelayService(Node node, String serviceName, Integer port, String protocol, String interfaceName) {
        GostDto result = GostUtil.AddRelayService(node.getId(), serviceName, port, protocol, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(gostMessage(result));
    }

    /**
     * 创建主服务
     */
    private R createMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(gostMessage(result));
    }

    private R createMainService(Node inNode, String serviceName, Forward forward, ForwardRouteDto route, Integer limiter, Tunnel tunnel) {
        String interfaceName = Objects.equals(tunnel.getType(), TUNNEL_TYPE_PORT_FORWARD)
                ? forward.getInterfaceName()
                : null;
        String remoteAddr = effectiveRemoteAddr(forward, route);
        String chainName = routeResourceName(forward, serviceName, route);
        GostDto result = GostUtil.AddService(
                inNode.getId(),
                serviceName,
                forward.getInPort(),
                limiter,
                remoteAddr,
                tunnel.getType(),
                tunnel,
                forward.getStrategy(),
                interfaceName,
                normalizeProtocolMode(forward.getProtocolMode()),
                chainName
        );
        return isGostOperationSuccess(result) ? R.ok() : R.err(gostMessage(result));
    }

    /**
     * 更新链服务
     */
    private R updateChainService(NodeInfo nodeInfo, String serviceName, List<Integer> hopPorts, String protocol, String interfaceName) {
        List<String> remoteAddrs = buildHopAddresses(nodeInfo, hopPorts);
        GostDto createResult = GostUtil.UpdateChains(nodeInfo.getInNode().getId(), serviceName, remoteAddrs, protocol, interfaceName);
        if (gostMessage(createResult).contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddChains(nodeInfo.getInNode().getId(), serviceName, remoteAddrs, protocol, interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(gostMessage(createResult));
    }

    /**
     * 更新远程服务
     */
    private R updateRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        // 创建新远程服务
        GostDto createResult = GostUtil.UpdateRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        if (gostMessage(createResult).contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(gostMessage(createResult));
    }

    private R updateRelayService(Node node, String serviceName, Integer port, String protocol, String interfaceName) {
        GostDto createResult = GostUtil.UpdateRelayService(node.getId(), serviceName, port, protocol, interfaceName);
        if (gostMessage(createResult).contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddRelayService(node.getId(), serviceName, port, protocol, interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(gostMessage(createResult));
    }

    /**
     * 更新主服务
     */
    private R updateMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.UpdateService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);

        if (gostMessage(result).contains(GOST_NOT_FOUND_MSG)) {
            result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);
        }

        return isGostOperationSuccess(result) ? R.ok() : R.err(gostMessage(result));
    }

    private R updateMainService(Node inNode, String serviceName, Forward forward, ForwardRouteDto route, Integer limiter, Tunnel tunnel) {
        GostUtil.DeleteService(inNode.getId(), serviceName, normalizeProtocolMode(forward.getProtocolMode()));
        return createMainService(inNode, serviceName, forward, route, limiter, tunnel);
    }

    private R createRouteInfrastructure(Forward forward, ForwardRouteDto route, Tunnel tunnel, String mainServiceName) {
        if (!Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)) {
            return R.ok();
        }
        NodeInfo routeNodeInfo = getRequiredNodes(tunnel);
        if (routeNodeInfo.isHasError()) {
            return R.err(routeNodeInfo.getErrorMessage());
        }
        Forward routeForward = forwardForRoute(forward, route);
        String routeServiceName = routeResourceName(forward, mainServiceName, route);
        List<Integer> hopPorts = getForwardHopPorts(routeForward, tunnel);

        R chainResult = createChainService(
                routeNodeInfo,
                routeServiceName,
                hopPorts,
                tunnel.getProtocol(),
                tunnel.getInterfaceName()
        );
        if (chainResult.getCode() != 0) {
            return chainResult;
        }
        R hopResult = createTunnelHopServices(
                routeNodeInfo,
                routeServiceName,
                routeForward,
                tunnel.getProtocol(),
                hopPorts
        );
        if (hopResult.getCode() != 0) {
            GostUtil.DeleteChains(routeNodeInfo.getInNode().getId(), routeServiceName);
            deleteTunnelHopServices(routeNodeInfo, routeServiceName);
        }
        return hopResult;
    }

    private Forward forwardForRoute(Forward forward, ForwardRouteDto route) {
        Forward routeForward = new Forward();
        BeanUtils.copyProperties(forward, routeForward);
        routeForward.setTunnelId(route.getTunnelId());
        routeForward.setOutPort(route.getOutPort());
        routeForward.setHopPorts(route.getHopPorts());
        routeForward.setRemoteAddr(effectiveRemoteAddr(forward, route));
        return routeForward;
    }

    private String effectiveRemoteAddr(Forward forward, ForwardRouteDto route) {
        if (route.getHealthyTargets() != null && !route.getHealthyTargets().isEmpty()) {
            return String.join(",", route.getHealthyTargets());
        }
        return forward.getRemoteAddr();
    }

    private String routeResourceName(Forward forward, String mainServiceName, ForwardRouteDto route) {
        if (forward.getRouteConfig() == null
                || forward.getRouteConfig().trim().isEmpty()
                || ROUTE_MODE_SINGLE.equals(forward.getRouteMode())) {
            return mainServiceName;
        }
        return mainServiceName + "r" + route.getTunnelId();
    }

    private void deleteForwardInfrastructureBestEffort(Forward forward) {
        Tunnel primaryTunnel = validateTunnel(forward.getTunnelId());
        if (primaryTunnel == null) {
            return;
        }
        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), primaryTunnel.getId().intValue());
        String mainServiceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        Node inNode = nodeService.getById(primaryTunnel.getInNodeId());
        if (inNode != null) {
            GostUtil.DeleteService(
                    inNode.getId(),
                    mainServiceName,
                    normalizeProtocolMode(forward.getProtocolMode())
            );
        }
        deleteRouteInfrastructureBestEffort(forward, getForwardRoutes(forward), mainServiceName);
    }

    private void deleteRouteInfrastructureBestEffort(Forward forward, List<ForwardRouteDto> routes, String mainServiceName) {
        for (ForwardRouteDto route : routes) {
            Tunnel tunnel = validateTunnel(route.getTunnelId());
            if (tunnel == null || !Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)) {
                continue;
            }
            String routeServiceName = routeResourceName(forward, mainServiceName, route);
            NodeInfo nodeInfo = getRequiredNodes(tunnel);
            if (!nodeInfo.isHasError()) {
                GostUtil.DeleteChains(nodeInfo.getInNode().getId(), routeServiceName);
                deleteTunnelHopServices(nodeInfo, routeServiceName);
            }
        }
    }

    private List<ForwardRouteDto> getForwardRoutes(Forward forward) {
        if (forward.getRouteConfig() != null && !forward.getRouteConfig().trim().isEmpty()) {
            try {
                List<ForwardRouteDto> routes = JSON.parseArray(forward.getRouteConfig(), ForwardRouteDto.class);
                if (routes != null && !routes.isEmpty()) {
                    routes.sort(Comparator.comparing(route -> route.getPriority() == null ? Integer.MAX_VALUE : route.getPriority()));
                    return routes;
                }
            } catch (Exception e) {
                log.warn("解析转发 {} 的线路组失败：{}", forward.getId(), e.getMessage());
            }
        }

        ForwardRouteDto route = new ForwardRouteDto();
        route.setTunnelId(forward.getTunnelId());
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        route.setTunnelName(tunnel == null ? "隧道 #" + forward.getTunnelId() : tunnel.getName());
        route.setPriority(0);
        route.setOutPort(forward.getOutPort());
        route.setHopPorts(forward.getHopPorts());
        return new ArrayList<>(Collections.singletonList(route));
    }

    private ForwardRouteDto getActiveRoute(Forward forward, List<ForwardRouteDto> routes) {
        Integer activeTunnelId = forward.getActiveTunnelId() == null
                ? forward.getTunnelId()
                : forward.getActiveTunnelId();
        return routes.stream()
                .filter(route -> Objects.equals(route.getTunnelId(), activeTunnelId))
                .findFirst()
                .orElse(routes.get(0));
    }

    private List<String> buildHopAddresses(NodeInfo nodeInfo, List<Integer> hopPorts) {
        List<Node> pathNodes = nodeInfo.getPathNodes();
        List<String> remoteAddrs = new ArrayList<>();
        for (int i = 1; i < pathNodes.size(); i++) {
            remoteAddrs.add(TunnelRouteUtil.hostPort(pathNodes.get(i).getServerIp(), hopPorts.get(i - 1)));
        }
        return remoteAddrs;
    }

    private List<Integer> getForwardHopPorts(Forward forward, Tunnel tunnel) {
        List<Long> nodePath = TunnelRouteUtil.parseNodePath(tunnel);
        List<Integer> hopPorts = TunnelRouteUtil.parseHopPorts(forward.getHopPorts());
        int expectedHopCount = Math.max(0, nodePath.size() - 1);
        if (hopPorts.size() == expectedHopCount) {
            return hopPorts;
        }
        if (expectedHopCount == 1 && forward.getOutPort() != null) {
            return Collections.singletonList(forward.getOutPort());
        }
        throw new IllegalStateException("转发跳点端口数据不完整，请重新保存该转发");
    }

    private R createTunnelHopServices(NodeInfo nodeInfo, String serviceName, Forward forward, String protocol, List<Integer> hopPorts) {
        List<Node> pathNodes = nodeInfo.getPathNodes();
        for (int i = 1; i < pathNodes.size(); i++) {
            Node node = pathNodes.get(i);
            Integer port = hopPorts.get(i - 1);
            boolean isLastHop = i == pathNodes.size() - 1;
            R result = isLastHop
                    ? createRemoteService(node, serviceName, forward, protocol, forward.getInterfaceName())
                    : createRelayService(node, serviceName, port, protocol, null);
            if (result.getCode() != 0) {
                return result;
            }
        }
        return R.ok();
    }

    private R updateTunnelHopServices(NodeInfo nodeInfo, String serviceName, Forward forward, String protocol, List<Integer> hopPorts) {
        List<Node> pathNodes = nodeInfo.getPathNodes();
        for (int i = 1; i < pathNodes.size(); i++) {
            Node node = pathNodes.get(i);
            Integer port = hopPorts.get(i - 1);
            boolean isLastHop = i == pathNodes.size() - 1;
            R result = isLastHop
                    ? updateRemoteService(node, serviceName, forward, protocol, forward.getInterfaceName())
                    : updateRelayService(node, serviceName, port, protocol, null);
            if (result.getCode() != 0) {
                return result;
            }
        }
        return R.ok();
    }

    private R deleteTunnelHopServices(NodeInfo nodeInfo, String serviceName) {
        for (Node node : nodeInfo.getPathNodes().stream().skip(1).collect(Collectors.toList())) {
            GostDto remoteResult = GostUtil.DeleteRemoteService(node.getId(), serviceName);
            if (!isGostOperationSuccess(remoteResult)) {
                return R.err(remoteResult == null ? "节点无响应" : remoteResult.getMsg());
            }
        }
        return R.ok();
    }

    private R pauseTunnelHopServices(NodeInfo nodeInfo, String serviceName) {
        for (Node node : nodeInfo.getPathNodes().stream().skip(1).collect(Collectors.toList())) {
            GostDto remoteResult = GostUtil.PauseRemoteService(node.getId(), serviceName);
            if (!isGostOperationSuccess(remoteResult)) {
                return R.err(remoteResult == null ? "节点无响应" : remoteResult.getMsg());
            }
        }
        return R.ok();
    }

    private R resumeTunnelHopServices(NodeInfo nodeInfo, String serviceName) {
        for (Node node : nodeInfo.getPathNodes().stream().skip(1).collect(Collectors.toList())) {
            GostDto remoteResult = GostUtil.ResumeRemoteService(node.getId(), serviceName);
            if (!isGostOperationSuccess(remoteResult)) {
                return R.err(remoteResult == null ? "节点无响应" : remoteResult.getMsg());
            }
        }
        return R.ok();
    }

    private void changeRouteInfrastructureStatus(Forward forward, List<ForwardRouteDto> routes, String mainServiceName, boolean pause) {
        for (ForwardRouteDto route : routes) {
            Tunnel routeTunnel = validateTunnel(route.getTunnelId());
            if (routeTunnel == null || !Objects.equals(routeTunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)) {
                continue;
            }
            NodeInfo routeNodes = getRequiredNodes(routeTunnel);
            if (routeNodes.isHasError()) {
                continue;
            }
            String routeServiceName = routeResourceName(forward, mainServiceName, route);
            if (pause) {
                pauseTunnelHopServices(routeNodes, routeServiceName);
            } else {
                resumeTunnelHopServices(routeNodes, routeServiceName);
            }
        }
    }

    @Scheduled(
            initialDelayString = "${forward-routing.initial-delay-ms:30000}",
            fixedDelayString = "${forward-routing.check-interval-ms:60000}"
    )
    public void checkForwardRouteHealth() {
        if (!routeHealthChecking.compareAndSet(false, true)) {
            log.debug("上一次转发线路健康检查尚未结束，跳过本轮任务");
            return;
        }
        try {
            List<Forward> activeForwards = this.list(new QueryWrapper<Forward>().eq("status", FORWARD_STATUS_ACTIVE));
            for (Forward forward : activeForwards) {
                try {
                    checkSingleForwardRouteHealth(forward);
                } catch (Exception e) {
                    log.warn("转发 {} 健康检查失败：{}", forward.getId(), e.getMessage());
                }
            }
        } finally {
            routeHealthChecking.set(false);
        }
    }

    private void checkSingleForwardRouteHealth(Forward forward) {
        List<ForwardRouteDto> routes = getForwardRoutes(forward);
        if (routes.isEmpty()) {
            return;
        }

        ForwardRouteDto previousActive = getActiveRoute(forward, routes);
        List<String> previousActiveTargets = previousActive.getHealthyTargets() == null
                ? Collections.emptyList()
                : new ArrayList<>(previousActive.getHealthyTargets());
        Map<Integer, List<ForwardTargetHealthDto>> targetHealthByRoute = new HashMap<>();
        for (ForwardRouteDto route : routes) {
            Tunnel tunnel = validateTunnel(route.getTunnelId());
            if (tunnel == null) {
                markRouteProbeFailure(route, "隧道不存在");
                continue;
            }
            RouteProbeResult probe = probeRoute(forward, route, tunnel);
            applyRouteProbeResult(route, probe);
            targetHealthByRoute.put(route.getTunnelId(), probe.getTargetHealth());
            updateRouteTargetPool(forward, route, tunnel);
        }

        long now = System.currentTimeMillis();
        ForwardRouteFailoverPolicy.Decision decision = ForwardRouteFailoverPolicy.select(
                normalizeRouteMode(forward.getRouteMode(), routes.size()),
                routes,
                previousActive,
                forward.getLastRouteSwitch() == null ? 0L : forward.getLastRouteSwitch(),
                now,
                new ForwardRouteFailoverPolicy.Settings(
                        Math.max(0L, routeSwitchCooldownMs),
                        Math.max(0L, routeFailbackStableMs),
                        Math.max(0.0, routeLatencyGapMs)
                )
        );
        ForwardRouteDto selected = decision.selected() == null ? previousActive : decision.selected();
        boolean routeChanged = decision.switchRequired()
                && !Objects.equals(previousActive.getTunnelId(), selected.getTunnelId());
        Tunnel selectedTunnel = validateTunnel(selected.getTunnelId());
        boolean directTargetPoolChanged = selectedTunnel != null
                && Objects.equals(selectedTunnel.getType(), TUNNEL_TYPE_PORT_FORWARD)
                && Objects.equals(previousActive.getTunnelId(), selected.getTunnelId())
                && !Objects.equals(previousActiveTargets, selected.getHealthyTargets());

        if (routeChanged || directTargetPoolChanged) {
            R switchResult = switchActiveRoute(forward, selected);
            if (switchResult.getCode() != 0) {
                markRouteProbeFailure(selected, "切换失败：" + switchResult.getMsg());
                if (routeChanged) {
                    recordRouteSwitch(
                            forward,
                            previousActive,
                            selected,
                            decision.reason(),
                            decision.emergency() ? "failure" : switchTriggerType(forward),
                            "failed",
                            switchResult.getMsg()
                    );
                }
                selected = previousActive;
            } else if (routeChanged) {
                forward.setPreviousActiveTunnelId(previousActive.getTunnelId());
                forward.setLastRouteSwitch(now);
                forward.setRouteSwitchReason(decision.reason());
                forward.setRouteSwitchCount((forward.getRouteSwitchCount() == null ? 0 : forward.getRouteSwitchCount()) + 1);
                recordRouteSwitch(
                        forward,
                        previousActive,
                        selected,
                        decision.reason(),
                        decision.emergency() ? "failure" : switchTriggerType(forward),
                        "success",
                        null
                );
            }
        }

        forward.setActiveTunnelId(selected.getTunnelId());
        forward.setRouteConfig(JSON.toJSONString(routes));
        forward.setTargetHealth(JSON.toJSONString(
                targetHealthByRoute.getOrDefault(selected.getTunnelId(), Collections.emptyList())
        ));
        forward.setLastHealthCheck(now);
        forward.setUpdatedTime(now);
        this.updateById(forward);
    }

    private RouteProbeResult probeRoute(Forward forward, ForwardRouteDto route, Tunnel tunnel) {
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return RouteProbeResult.failure(nodeInfo.getErrorMessage());
        }
        for (Node node : nodeInfo.getPathNodes()) {
            if (!Objects.equals(node.getStatus(), 1)) {
                return RouteProbeResult.failure("节点离线：" + node.getName());
            }
        }

        double totalLatency = 0;
        double totalPacketLoss = 0;
        int measurementCount = 0;
        if (Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)) {
            List<Integer> hopPorts = TunnelRouteUtil.parseHopPorts(route.getHopPorts());
            List<Node> pathNodes = nodeInfo.getPathNodes();
            if (hopPorts.size() != pathNodes.size() - 1) {
                return RouteProbeResult.failure("线路端口数据不完整");
            }
            for (int i = 0; i < pathNodes.size() - 1; i++) {
                DiagnosisResult segment = performTcpPingProbe(
                        pathNodes.get(i),
                        pathNodes.get(i + 1).getServerIp(),
                        hopPorts.get(i),
                        pathNodes.get(i).getName() + "->" + pathNodes.get(i + 1).getName()
                );
                if (!segment.isSuccess()) {
                    return RouteProbeResult.failure(segment.getMessage());
                }
                totalLatency += segment.getAverageTime();
                totalPacketLoss += segment.getPacketLoss();
                measurementCount++;
            }
        }

        List<ForwardTargetHealthDto> targetHealth = new ArrayList<>();
        List<String> healthyTargets = new ArrayList<>();
        double bestTargetLatency = Double.MAX_VALUE;
        double bestTargetLoss = 0;
        Node targetProbeNode = nodeInfo.getOutNode();
        for (String address : splitRemoteAddresses(forward.getRemoteAddr())) {
            String targetIp = extractIpFromAddress(address);
            int targetPort = extractPortFromAddress(address);
            DiagnosisResult targetResult = performTcpPingProbe(
                    targetProbeNode,
                    targetIp,
                    targetPort,
                    "目标探测"
            );
            ForwardTargetHealthDto health = new ForwardTargetHealthDto();
            health.setAddress(address);
            health.setStatus(targetResult.isSuccess() ? ROUTE_STATUS_HEALTHY : ROUTE_STATUS_UNHEALTHY);
            health.setLatency(targetResult.getAverageTime());
            health.setPacketLoss(targetResult.getPacketLoss());
            health.setFailCount(targetResult.isSuccess() ? 0 : 1);
            health.setLastCheckTime(System.currentTimeMillis());
            health.setMessage(targetResult.getMessage());
            targetHealth.add(health);
            if (targetResult.isSuccess()) {
                healthyTargets.add(address);
                if (targetResult.getAverageTime() < bestTargetLatency) {
                    bestTargetLatency = targetResult.getAverageTime();
                    bestTargetLoss = targetResult.getPacketLoss();
                }
            }
        }
        if (healthyTargets.isEmpty()) {
            return RouteProbeResult.failure("所有目标地址均不可用", targetHealth);
        }

        totalLatency += bestTargetLatency;
        totalPacketLoss += bestTargetLoss;
        measurementCount++;
        return RouteProbeResult.success(
                totalLatency,
                measurementCount == 0 ? 0 : totalPacketLoss / measurementCount,
                healthyTargets,
                targetHealth
        );
    }

    private DiagnosisResult performTcpPingProbe(Node node, String targetIp, int port, String description) {
        if (node == null || targetIp == null || port < 1) {
            DiagnosisResult invalid = new DiagnosisResult();
            invalid.setSuccess(false);
            invalid.setMessage("探测参数无效");
            invalid.setAverageTime(-1);
            invalid.setPacketLoss(100);
            return invalid;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("ip", targetIp);
            data.put("port", port);
            data.put("count", 1);
            data.put("timeout", 2000);
            GostDto response = WebSocketServer.send_msg(node.getId(), data, "TcpPing");
            if (response != null && GOST_SUCCESS_MSG.equals(response.getMsg()) && response.getData() instanceof JSONObject) {
                JSONObject resultData = (JSONObject) response.getData();
                DiagnosisResult result = new DiagnosisResult();
                result.setSuccess(resultData.getBooleanValue("success"));
                result.setMessage(result.isSuccess() ? "连接成功" : resultData.getString("errorMessage"));
                result.setAverageTime(resultData.getDoubleValue("averageTime"));
                result.setPacketLoss(resultData.getDoubleValue("packetLoss"));
                result.setDescription(description);
                return result;
            }
            DiagnosisResult failed = new DiagnosisResult();
            failed.setSuccess(false);
            failed.setMessage(gostMessage(response));
            failed.setAverageTime(-1);
            failed.setPacketLoss(100);
            failed.setDescription(description);
            return failed;
        } catch (Exception e) {
            DiagnosisResult failed = new DiagnosisResult();
            failed.setSuccess(false);
            failed.setMessage(e.getMessage());
            failed.setAverageTime(-1);
            failed.setPacketLoss(100);
            failed.setDescription(description);
            return failed;
        }
    }

    private void applyRouteProbeResult(ForwardRouteDto route, RouteProbeResult probe) {
        long now = System.currentTimeMillis();
        String previousStatus = route.getStatus() == null ? ROUTE_STATUS_UNKNOWN : route.getStatus();
        route.setLastCheckTime(now);
        if (probe.isSuccess()) {
            int successCount = (route.getSuccessCount() == null ? 0 : route.getSuccessCount()) + 1;
            route.setSuccessCount(Math.min(successCount, Math.max(routeRecoveryThreshold, 1)));
            route.setFailCount(0);
            route.setLastSuccessTime(now);
            route.setLatency(probe.getLatency());
            route.setPacketLoss(probe.getPacketLoss());
            route.setHealthyTargets(probe.getHealthyTargets());
            if (ROUTE_STATUS_UNHEALTHY.equals(previousStatus)
                    && successCount < Math.max(routeRecoveryThreshold, 1)) {
                route.setStatus(ROUTE_STATUS_UNHEALTHY);
                route.setMessage("恢复确认 " + successCount + "/" + Math.max(routeRecoveryThreshold, 1));
                return;
            }
            route.setStatus(ROUTE_STATUS_HEALTHY);
            if (!ROUTE_STATUS_HEALTHY.equals(previousStatus) || route.getHealthySince() == null) {
                route.setHealthySince(now);
            }
            route.setMessage(ROUTE_STATUS_UNHEALTHY.equals(previousStatus)
                    ? "连续探测成功，线路已恢复"
                    : probe.getMessage());
            return;
        }
        int failCount = (route.getFailCount() == null ? 0 : route.getFailCount()) + 1;
        route.setFailCount(failCount);
        route.setSuccessCount(0);
        route.setLastFailureTime(now);
        route.setMessage((probe.getMessage() == null ? "线路探测失败" : probe.getMessage())
                + "（连续失败 " + failCount + "/" + Math.max(routeFailureThreshold, 1) + "）");
        if (failCount >= Math.max(routeFailureThreshold, 1)) {
            route.setStatus(ROUTE_STATUS_UNHEALTHY);
            route.setLatency(null);
            route.setPacketLoss(100.0);
            route.setHealthySince(null);
            route.setHealthyTargets(Collections.emptyList());
        }
    }

    private void markRouteProbeFailure(ForwardRouteDto route, String message) {
        applyRouteProbeResult(route, RouteProbeResult.failure(message));
    }

    private R switchActiveRoute(Forward forward, ForwardRouteDto selected) {
        Tunnel selectedTunnel = validateTunnel(selected.getTunnelId());
        if (selectedTunnel == null) {
            return R.err("目标线路不存在");
        }
        Tunnel primaryTunnel = validateTunnel(forward.getTunnelId());
        UserTunnel userTunnel = primaryTunnel == null
                ? null
                : getUserTunnel(forward.getUserId(), primaryTunnel.getId().intValue());
        Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        Node inNode = nodeService.getById(selectedTunnel.getInNodeId());
        return updateMainService(inNode, serviceName, forward, selected, limiter, selectedTunnel);
    }

    private String switchTriggerType(Forward forward) {
        return ROUTE_MODE_LATENCY.equals(forward.getRouteMode()) ? "latency" : "recovery";
    }

    private void recordRouteSwitch(
            Forward forward,
            ForwardRouteDto previous,
            ForwardRouteDto selected,
            String reason,
            String triggerType,
            String status,
            String detail
    ) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO forward_route_switch "
                            + "(forward_id, user_id, from_tunnel_id, from_tunnel_name, to_tunnel_id, to_tunnel_name, "
                            + "reason, trigger_type, status, detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    forward.getId(),
                    forward.getUserId(),
                    previous == null ? null : previous.getTunnelId(),
                    previous == null ? null : previous.getTunnelName(),
                    selected == null ? null : selected.getTunnelId(),
                    selected == null ? null : selected.getTunnelName(),
                    reason,
                    triggerType,
                    status,
                    detail,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            log.warn("记录转发 {} 线路切换事件失败：{}", forward.getId(), e.getMessage());
        }
    }

    private void deleteRouteEvents(Long forwardId) {
        try {
            jdbcTemplate.update("DELETE FROM forward_route_switch WHERE forward_id = ?", forwardId);
        } catch (Exception e) {
            log.warn("清理转发 {} 线路切换记录失败：{}", forwardId, e.getMessage());
        }
    }

    private void updateRouteTargetPool(Forward forward, ForwardRouteDto route, Tunnel tunnel) {
        if (!Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)
                || route.getHealthyTargets() == null
                || route.getHealthyTargets().isEmpty()) {
            return;
        }
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return;
        }
        Tunnel primaryTunnel = validateTunnel(forward.getTunnelId());
        UserTunnel userTunnel = primaryTunnel == null
                ? null
                : getUserTunnel(forward.getUserId(), primaryTunnel.getId().intValue());
        String mainServiceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        String routeServiceName = routeResourceName(forward, mainServiceName, route);
        Forward routeForward = forwardForRoute(forward, route);
        updateRemoteService(
                nodeInfo.getOutNode(),
                routeServiceName,
                routeForward,
                tunnel.getProtocol(),
                forward.getInterfaceName()
        );
    }

    private List<String> splitRemoteAddresses(String remoteAddr) {
        if (remoteAddr == null) {
            return Collections.emptyList();
        }
        return Arrays.stream(remoteAddr.split("[,\\n]"))
                .map(String::trim)
                .filter(address -> !address.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 更新转发状态为错误
     */
    private void updateForwardStatusToError(Forward forward) {
        forward.setStatus(FORWARD_STATUS_ERROR);
        this.updateById(forward);
    }

    /**
     * 获取用户隧道关系
     */
    private UserTunnel getUserTunnel(Integer userId, Integer tunnelId) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId));
    }

    private UserTunnel getPrimaryUserTunnel(Forward forward) {
        return getUserTunnel(forward.getUserId(), forward.getTunnelId());
    }

    /**
     * 检查隧道是否发生变化
     */
    private boolean isTunnelChanged(Forward existForward, ForwardUpdateDto updateDto) {
        return !existForward.getTunnelId().equals(updateDto.getTunnelId());
    }

    /**
     * 检查Gost操作是否成功
     */
    private boolean isGostOperationSuccess(GostDto gostResult) {
        return gostResult != null && Objects.equals(gostResult.getMsg(), GOST_SUCCESS_MSG);
    }

    private String gostMessage(GostDto gostResult) {
        return gostResult == null ? "节点无响应" : gostResult.getMsg();
    }


    /**
     * 检查指定的入口端口是否可用（可排除指定的转发ID）
     */
    private boolean isInPortAvailable(Tunnel tunnel, Integer port, Long excludeForwardId) {
        // 获取入口节点信息
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            return false;
        }

        // 检查端口是否在节点允许的范围内
        if (port < inNode.getPortSta() || port > inNode.getPortEnd()) {
            return false;
        }

        // 获取该节点上所有已被占用的端口（包括作为入口和出口使用的端口）
        Set<Integer> usedPorts = getAllUsedPortsOnNode(tunnel.getInNodeId(), excludeForwardId);

        // 检查端口是否已被占用（在节点级别检查，考虑入口和出口端口）
        return !usedPorts.contains(port);
    }

    /**
     * 为隧道分配一个可用的入口端口（可排除指定的转发ID）
     */
    private Integer allocateInPort(Tunnel tunnel, Long excludeForwardId) {
        return allocatePortForNode(tunnel.getInNodeId(), excludeForwardId);
    }

    /**
     * 为隧道分配一个可用的出口端口（可排除指定的转发ID）
     */
    private Integer allocateOutPort(Tunnel tunnel, Long excludeForwardId) {
        return allocatePortForNode(tunnel.getOutNodeId(), excludeForwardId);
    }

    /**
     * 为指定节点分配一个可用端口（通用方法）
     *
     * @param nodeId           节点ID
     * @param excludeForwardId 要排除的转发ID
     * @return 可用端口号，如果没有可用端口则返回null
     */
    private Integer allocatePortForNode(Long nodeId, Long excludeForwardId) {
        return allocatePortForNode(nodeId, excludeForwardId, Collections.emptySet());
    }

    private Integer allocatePortForNode(Long nodeId, Long excludeForwardId, Set<Integer> additionallyReserved) {
        // 获取节点信息
        Node node = nodeService.getById(nodeId);
        if (node == null) {
            return null;
        }

        // 获取该节点上所有已被占用的端口（包括作为入口和出口使用的端口）
        Set<Integer> usedPorts = getAllUsedPortsOnNode(nodeId, excludeForwardId);
        if (additionallyReserved != null) {
            usedPorts.addAll(additionallyReserved);
        }

        // 在节点端口范围内寻找未使用的端口
        for (int port = node.getPortSta(); port <= node.getPortEnd(); port++) {
            if (!usedPorts.contains(port)) {
                return port;
            }
        }
        return null;
    }

    /**
     * 获取指定节点上所有已被占用的端口（包括入口和出口端口）
     *
     * @param nodeId           节点ID
     * @param excludeForwardId 要排除的转发ID
     * @return 已占用的端口集合
     */
    private Set<Integer> getAllUsedPortsOnNode(Long nodeId, Long excludeForwardId) {
        Set<Integer> usedPorts = new HashSet<>();
        Node requestedNode = nodeService.getById(nodeId);
        if (requestedNode == null) {
            return usedPorts;
        }

        String requestedNamespace = PortNamespaceUtil.fromNode(requestedNode);
        Set<Long> namespaceNodeIds = nodeService.list().stream()
                .filter(node -> Objects.equals(PortNamespaceUtil.fromNode(node), requestedNamespace))
                .map(Node::getId)
                .collect(Collectors.toSet());
        List<Tunnel> allTunnels = tunnelService.list();
        Map<Integer, Tunnel> tunnelMap = allTunnels.stream()
                .collect(Collectors.toMap(t -> t.getId().intValue(), t -> t, (a, b) -> a));

        QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
        if (excludeForwardId != null) {
            queryWrapper.ne("id", excludeForwardId);
        }
        List<Forward> forwards = this.list(queryWrapper);
        for (Forward forward : forwards) {
            Tunnel primaryTunnel = tunnelMap.get(forward.getTunnelId());
            if (primaryTunnel == null) {
                continue;
            }

            if (namespaceNodeIds.contains(primaryTunnel.getInNodeId()) && forward.getInPort() != null) {
                usedPorts.add(forward.getInPort());
            }

            for (ForwardRouteDto route : getForwardRoutes(forward)) {
                Tunnel routeTunnel = tunnelMap.get(route.getTunnelId());
                if (routeTunnel == null) {
                    continue;
                }
                List<Long> nodePath = TunnelRouteUtil.parseNodePath(routeTunnel);
                List<Integer> hopPorts = TunnelRouteUtil.parseHopPorts(route.getHopPorts());
                if (hopPorts.size() != nodePath.size() - 1 && nodePath.size() == 2 && route.getOutPort() != null) {
                    hopPorts = Collections.singletonList(route.getOutPort());
                }
                for (int i = 1; i < nodePath.size() && i - 1 < hopPorts.size(); i++) {
                    if (namespaceNodeIds.contains(nodePath.get(i))) {
                        usedPorts.add(hopPorts.get(i - 1));
                    }
                }
            }
        }

        for (Long namespaceNodeId : namespaceNodeIds) {
            List<Map<String, Object>> pools = jdbcTemplate.queryForList(
                    "SELECT start_port, end_port, control_port FROM port_pool WHERE node_id=? AND status=1",
                    namespaceNodeId);
            for (Map<String, Object> pool : pools) {
                int start = ((Number) pool.get("start_port")).intValue();
                int end = ((Number) pool.get("end_port")).intValue();
                for (int port = start; port <= end; port++) {
                    usedPorts.add(port);
                }
                usedPorts.add(((Number) pool.get("control_port")).intValue());
            }
        }

        return usedPorts;
    }


    /**
     * 构建服务名称，优化后减少重复查询
     */
    private String buildServiceName(Long forwardId, Integer userId, UserTunnel userTunnel) {
        int userTunnelId = (userTunnel != null) ? userTunnel.getId() : 0;
        return forwardId + "_" + userId + "_" + userTunnelId;
    }


    public void updateForwardA(Forward forward) {
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return;
        }
        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return;
        }
        Integer limiter;
        if (userTunnel == null) {
            limiter = null;
        } else {
            limiter = userTunnel.getSpeedId();
        }
        updateGostServices(forward, tunnel, limiter, nodeInfo, userTunnel);
    }


    // ========== 内部数据类 ==========

    /**
     * 用户信息封装类
     */
    @Data
    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;
        private final String userName;
    }

    /**
     * 用户权限检查结果
     */
    @Data
    private static class UserPermissionResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer limiter;
        private final UserTunnel userTunnel;

        private UserPermissionResult(boolean hasError, String errorMessage, Integer limiter, UserTunnel userTunnel) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.limiter = limiter;
            this.userTunnel = userTunnel;
        }

        public static UserPermissionResult success(Integer limiter, UserTunnel userTunnel) {
            return new UserPermissionResult(false, null, limiter, userTunnel);
        }

        public static UserPermissionResult error(String errorMessage) {
            return new UserPermissionResult(true, errorMessage, null, null);
        }
    }

    /**
     * 端口分配结果
     */
    @Data
    private static class PortAllocation {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer inPort;
        private final Integer outPort;
        private final List<Integer> hopPorts;

        private PortAllocation(boolean hasError, String errorMessage, Integer inPort, Integer outPort, List<Integer> hopPorts) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.inPort = inPort;
            this.outPort = outPort;
            this.hopPorts = hopPorts;
        }

        public static PortAllocation success(Integer inPort, Integer outPort, List<Integer> hopPorts) {
            return new PortAllocation(false, null, inPort, outPort, hopPorts);
        }

        public static PortAllocation error(String errorMessage) {
            return new PortAllocation(true, errorMessage, null, null, Collections.emptyList());
        }
    }

    @Data
    private static class RouteValidationResult {
        private final boolean hasError;
        private final String errorMessage;
        private final List<Tunnel> tunnels;

        private static RouteValidationResult success(List<Tunnel> tunnels) {
            return new RouteValidationResult(false, null, tunnels);
        }

        private static RouteValidationResult error(String message) {
            return new RouteValidationResult(true, message, Collections.emptyList());
        }
    }

    @Data
    private static class RouteAllocationResult {
        private final boolean hasError;
        private final String errorMessage;
        private final List<ForwardRouteDto> routes;

        private static RouteAllocationResult success(List<ForwardRouteDto> routes) {
            return new RouteAllocationResult(false, null, routes);
        }

        private static RouteAllocationResult error(String message) {
            return new RouteAllocationResult(true, message, Collections.emptyList());
        }
    }

    @Data
    private static class RouteProbeResult {
        private final boolean success;
        private final String message;
        private final Double latency;
        private final Double packetLoss;
        private final List<String> healthyTargets;
        private final List<ForwardTargetHealthDto> targetHealth;

        private static RouteProbeResult success(
                Double latency,
                Double packetLoss,
                List<String> healthyTargets,
                List<ForwardTargetHealthDto> targetHealth
        ) {
            return new RouteProbeResult(
                    true,
                    "线路正常",
                    latency,
                    packetLoss,
                    healthyTargets,
                    targetHealth
            );
        }

        private static RouteProbeResult failure(String message) {
            return failure(message, Collections.emptyList());
        }

        private static RouteProbeResult failure(String message, List<ForwardTargetHealthDto> targetHealth) {
            return new RouteProbeResult(
                    false,
                    message,
                    null,
                    100.0,
                    Collections.emptyList(),
                    targetHealth
            );
        }
    }

    /**
     * 节点信息封装类
     */
    @Data
    private static class NodeInfo {
        private final boolean hasError;
        private final String errorMessage;
        private final Node inNode;
        private final Node outNode;
        private final List<Node> pathNodes;

        private NodeInfo(boolean hasError, String errorMessage, Node inNode, Node outNode, List<Node> pathNodes) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.inNode = inNode;
            this.outNode = outNode;
            this.pathNodes = pathNodes;
        }

        public static NodeInfo success(Node inNode, Node outNode, List<Node> pathNodes) {
            return new NodeInfo(false, null, inNode, outNode, pathNodes);
        }

        public static NodeInfo error(String errorMessage) {
            return new NodeInfo(true, errorMessage, null, null, Collections.emptyList());
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
