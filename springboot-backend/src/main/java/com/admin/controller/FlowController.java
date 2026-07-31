package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.lang.R;
import com.admin.service.UserQuotaService;
import com.admin.service.PrivateProxyService;
import com.admin.service.SmartEntryService;
import com.admin.service.ServiceTelemetryService;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TrafficBillingUtil;
import com.admin.entity.*;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.mapper.PublishedServiceMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计
 * <p>
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * <p>
 * 并发安全解决方案：
 * 1. 使用UpdateWrapper进行数据库层面的原子更新操作，避免读取-修改-写入的竞态条件
 * 2. 使用synchronized锁确保同一用户/隧道的流量更新串行执行
 * 3. 这样可以避免相同用户相同隧道不同转发同时上报时流量统计丢失的问题
 */
@RestController
@RequestMapping("/flow")
@CrossOrigin
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final String DEFAULT_USER_TUNNEL_ID = "0";
    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;
    private static final Pattern FORWARD_SERVICE_PATTERN =
            Pattern.compile("^(\\d+)_(\\d+)_(\\d+)(?:_(tcp|udp))?$");

    // 用于同步相同用户和隧道的流量更新操作
    private static final ConcurrentHashMap<String, Object> USER_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> TUNNEL_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> FORWARD_LOCKS = new ConcurrentHashMap<>();

    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    @Resource
    UserQuotaService userQuotaService;

    @Resource
    PrivateProxyService privateProxyService;

    @Resource
    SmartEntryService smartEntryService;

    @Resource
    ServiceTelemetryService serviceTelemetryService;

    @Resource
    InternalConnectorMapper internalConnectorMapper;

    @Resource
    PublishedServiceMapper publishedServiceMapper;

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }

    @PostMapping("/config")
    @LogAnnotation
    public String config(@RequestBody String rawData, String secret) {
        Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (node == null) return SUCCESS_RESPONSE;

        try {
            // 尝试解密数据
            String decryptedData = decryptIfNeeded(rawData, secret);

            // 解析为GostConfigDto
            GostConfigDto gostConfigDto = JSON.parseObject(decryptedData, GostConfigDto.class);
            checkGostConfigAsync.cleanNodeConfigs(node.getId().toString(), gostConfigDto);

            log.info("🔓 节点 {} 配置数据接收成功{}", node.getId(), isEncryptedMessage(rawData) ? "（已解密）" : "");

        } catch (Exception e) {
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getMessage());
        }

        return SUCCESS_RESPONSE;
    }

    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }

    /**
     * 处理流量数据上报
     *
     * @param rawData 原始数据（可能是加密的）
     * @param secret  节点密钥
     * @return 处理结果
     */
    @RequestMapping("/upload")
    @LogAnnotation
    public String uploadFlowData(@RequestBody String rawData, String secret) {
        // 1. 验证节点权限
        Node reportingNode = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret).last("LIMIT 1"));
        InternalConnector reportingConnector = reportingNode == null
                ? internalConnectorMapper.selectOne(new QueryWrapper<InternalConnector>()
                    .eq("secret", secret).eq("status", 1).last("LIMIT 1"))
                : null;
        if (reportingNode == null && reportingConnector == null) {
            return SUCCESS_RESPONSE;
        }

        // 2. 尝试解密数据
        String decryptedData = decryptIfNeeded(rawData, secret);

        // 3. 解析为FlowDto列表
        FlowDto flowDataList = JSONObject.parseObject(decryptedData, FlowDto.class);
        if (Objects.equals(flowDataList.getN(), "web_api")) {
            return SUCCESS_RESPONSE;
        }
        if (reportingConnector != null && !connectorOwnsService(reportingConnector, flowDataList.getN())) {
            return SUCCESS_RESPONSE;
        }

        // 记录日志
        log.info("节点上报流量数据{}", flowDataList);
        // 4. 处理流量数据
        // Connector IDs use a negative namespace so they cannot collide with public node IDs.
        Long reporterId = reportingNode != null ? reportingNode.getId() : -reportingConnector.getId();
        return processFlowData(flowDataList, reporterId);
    }

    private boolean connectorOwnsService(InternalConnector connector, String serviceName) {
        if (connector == null || serviceName == null || !serviceName.matches("^publish_\\d+_rtcp$")) return false;
        String id = serviceName.substring("publish_".length(), serviceName.length() - "_rtcp".length());
        PublishedService service = publishedServiceMapper.selectById(id);
        return service != null && Objects.equals(service.getConnectorId(), connector.getId())
                && Objects.equals(service.getServiceName(), serviceName)
                && !List.of("released", "deleted").contains(service.getState());
    }

    /**
     * 检测消息是否为加密格式
     */
    private boolean isEncryptedMessage(String data) {
        try {
            JSONObject json = JSON.parseObject(data);
            return json.getBooleanValue("encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据需要解密数据
     */
    private String decryptIfNeeded(String rawData, String secret) {
        if (rawData == null || rawData.trim().isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(rawData, EncryptedMessage.class);

            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(secret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return rawData;
                }

                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.info("数据未加密或解密失败，使用原始数据: {}", e.getMessage());
        }

        return rawData;
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    /**
     * 处理流量数据的核心逻辑
     */
    private String processFlowData(FlowDto flowDataList, Long reportingNodeId) {
        String serviceName = flowDataList.getN();
        if (serviceName == null || serviceName.isBlank()) return SUCCESS_RESPONSE;
        serviceTelemetryService.record(reportingNodeId, flowDataList);
        if (reportingNodeId < 0) return SUCCESS_RESPONSE;
        if (serviceName.startsWith("private-proxy-")) {
            privateProxyService.recordTraffic(serviceName, flowDataList.getD(), flowDataList.getU());
            return SUCCESS_RESPONSE;
        }
        ForwardServiceName forwardServiceName = parseForwardServiceName(serviceName);
        if (forwardServiceName == null) return SUCCESS_RESPONSE;
        String forwardId = forwardServiceName.forwardId();
        String userId = forwardServiceName.userId();
        String userTunnelId = forwardServiceName.userTunnelId();

        Forward forward = forwardService.getById(forwardId);
        if (forward == null) return SUCCESS_RESPONSE;

        boolean reportConnections = forwardServiceName.reportsConnections();
        smartEntryService.recordActivity(forward.getId(), reportingNodeId,
                reportConnections ? flowDataList.getT() : null, reportConnections ? flowDataList.getC() : null,
                flowDataList.getD(), flowDataList.getU());

        // 获取流量计费类型
        int flowType = getFlowType(forward);

        //  处理流量倍率及单双向计算
        FlowDto flowStats = filterFlowData(flowDataList, forward, flowType);

        // 先更新所有流量统计 - 确保流量数据的一致性
        updateForwardFlow(forwardId, flowStats);
        updateUserFlow(userId, flowStats);
        userQuotaService.recordResourceFlow(forward, flowStats.getD(), flowStats.getU());

        // 7. 检查和服务暂停操作
        String name = buildServiceName(forwardId, userId, userTunnelId);
        User user = userService.getById(userId);
        if (user != null && !Objects.equals(user.getRoleId(), 0)) {
            R quota = userQuotaService.checkForwardActiveQuota(forward);
            if (quota.getCode() != 0) {
                log.warn("暂停超出资源额度的转发 {}: {}", forwardId, quota.getMsg());
                pauseService(java.util.Collections.singletonList(forward), name);
            }
        }

        return SUCCESS_RESPONSE;
    }

    private void checkUserRelatedLimits(String userId, String name) {

        // 重新查询用户以获取最新的流量数据
        User updatedUser = userService.getById(userId);
        if (updatedUser == null) return;

        // 检查用户总流量限制
        long userFlowLimit = updatedUser.getFlow() * BYTES_TO_GB;
        long userCurrentFlow = updatedUser.getInFlow() + updatedUser.getOutFlow();
        if (userFlowLimit < userCurrentFlow) {
            pauseAllUserServices(userId, name);
            return;
        }

        // 检查用户到期时间
        if (updatedUser.getExpTime() != null && updatedUser.getExpTime() <= new Date().getTime()) {
            pauseAllUserServices(userId, name);
            return;
        }

        // 检查用户状态
        if (updatedUser.getStatus() != 1) {
            pauseAllUserServices(userId, name);
        }
    }

    public void pauseAllUserServices(String userId, String name) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userId));
        pauseService(forwardList, name);
    }

    public void checkUserTunnelRelatedLimits(String userTunnelId, String name, String userId) {

        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel == null) return;
        long flow = userTunnel.getInFlow() + userTunnel.getOutFlow();
        if (flow >= userTunnel.getFlow() *  BYTES_TO_GB) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
            return;
        }

        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
            return;
        }

        if (userTunnel.getStatus() != 1) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
        }


    }

    private void pauseSpecificForward(Integer tunnelId, String name, String userId) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId).eq("user_id", userId));
        pauseService(forwardList, name);
    }

    public void pauseService(List<Forward> forwardList, String name) {
        for (Forward forward : forwardList) {
            Integer activeTunnelId = forward.getActiveTunnelId() == null ? forward.getTunnelId() : forward.getActiveTunnelId();
            Tunnel tunnel = tunnelService.getById(activeTunnelId);
            if (tunnel != null){
                GostUtil.PauseService(tunnel.getInNodeId(), name);
                if (tunnel.getType() == 2){
                    GostUtil.PauseRemoteService(tunnel.getOutNodeId(), name);
                }
            }
            forward.setStatus(0);
            forwardService.updateById(forward);
        }
    }

    private FlowDto filterFlowData(FlowDto flowDto, Forward forward, int flowType) {
        if (forward != null) {
            Integer activeTunnelId = forward.getActiveTunnelId() == null ? forward.getTunnelId() : forward.getActiveTunnelId();
            Tunnel tunnel = tunnelService.getById(activeTunnelId);
            if (tunnel != null) {
                BigDecimal trafficRatio = tunnel.getTrafficRatio();

                long[] billed = TrafficBillingUtil.calculate(flowDto.getD(), flowDto.getU(), flowType, trafficRatio);
                flowDto.setD(billed[0]);
                flowDto.setU(billed[1]);
            }
        }
        return flowDto;
    }

    private int getFlowType(Forward forward) {
        int defaultFlowType = 2;
        if (forward == null) return defaultFlowType;
        Integer activeTunnelId = forward.getActiveTunnelId() == null ? forward.getTunnelId() : forward.getActiveTunnelId();
        Tunnel tunnel = tunnelService.getById(activeTunnelId);
        if (tunnel == null) return defaultFlowType;
        return tunnel.getFlow();
    }

    private void updateForwardFlow(String forwardId, FlowDto flowStats) {
        // 对相同转发的流量更新进行同步，避免并发覆盖
        synchronized (getForwardLock(forwardId)) {
            UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", forwardId);
            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());

            forwardService.update(null, updateWrapper);
        }
    }

    private void updateUserFlow(String userId, FlowDto flowStats) {
        // 对相同用户的流量更新进行同步，避免并发覆盖
        synchronized (getUserLock(userId)) {
            UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userId);

            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());

            userService.update(null, updateWrapper);
        }
    }

    private void updateUserTunnelFlow(String userTunnelId, FlowDto flowStats) {
        if (Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            return; // 默认隧道不需要更新，返回成功
        }

        // 对相同用户隧道的流量更新进行同步，避免并发覆盖
        synchronized (getTunnelLock(userTunnelId)) {
            UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userTunnelId);
            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());
            userTunnelService.update(null, updateWrapper);
        }
    }

    private Object getUserLock(String userId) {
        return USER_LOCKS.computeIfAbsent(userId, k -> new Object());
    }

    private Object getTunnelLock(String userTunnelId) {
        return TUNNEL_LOCKS.computeIfAbsent(userTunnelId, k -> new Object());
    }

    private Object getForwardLock(String forwardId) {
        return FORWARD_LOCKS.computeIfAbsent(forwardId, k -> new Object());
    }

    static ForwardServiceName parseForwardServiceName(String serviceName) {
        if (serviceName == null) return null;
        Matcher matcher = FORWARD_SERVICE_PATTERN.matcher(serviceName);
        if (!matcher.matches()) return null;
        return new ForwardServiceName(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
    }

    record ForwardServiceName(String forwardId, String userId, String userTunnelId, String transport) {
        boolean reportsConnections() {
            return !"udp".equals(transport);
        }
    }

    private String buildServiceName(String forwardId, String userId, String userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }
}
