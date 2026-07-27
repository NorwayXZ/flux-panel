package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.PrivateProxyCreateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.entity.PrivateProxy;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.PortAllocationLockMapper;
import com.admin.mapper.PrivateProxyMapper;
import com.admin.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PrivateProxyService {
    private static final String MIN_AGENT_VERSION = "2.19.0";
    private final PrivateProxyMapper proxyMapper;
    private final NodeMapper nodeMapper;
    private final UserMapper userMapper;
    private final PortAllocationLockMapper allocationLockMapper;
    private final PortLedgerService portLedgerService;
    private final UserQuotaService userQuotaService;
    private final AESCrypto crypto;

    public PrivateProxyService(PrivateProxyMapper proxyMapper, NodeMapper nodeMapper, UserMapper userMapper,
                               PortAllocationLockMapper allocationLockMapper, PortLedgerService portLedgerService,
                               UserQuotaService userQuotaService, @Value("${jwt-secret}") String secret) {
        this.proxyMapper = proxyMapper;
        this.nodeMapper = nodeMapper;
        this.userMapper = userMapper;
        this.allocationLockMapper = allocationLockMapper;
        this.portLedgerService = portLedgerService;
        this.userQuotaService = userQuotaService;
        this.crypto = new AESCrypto(secret + ":private-proxy");
    }

    @Transactional(rollbackFor = Exception.class)
    public R create(PrivateProxyCreateDto dto) {
        allocationLockMapper.lockForUpdate();
        Integer userId = JwtUtil.getUserIdFromToken();
        Node node = nodeMapper.selectById(dto.getNodeId());
        if (node == null) return R.err("节点不存在");
        R quota = isAdmin() ? R.ok() : userQuotaService.checkNodeQuota(userId, node, null);
        if (quota.getCode() != 0) return quota;
        if (!WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线，无法创建代理");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_AGENT_VERSION)) {
            return R.err("节点 Agent 需要先升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }
        if (node.getPortSta() != null && dto.getListenPort() < node.getPortSta()
                || node.getPortEnd() != null && dto.getListenPort() > node.getPortEnd()) {
            return R.err("监听端口不在节点允许范围内");
        }
        String bindIp = StringUtils.trimToEmpty(dto.getBindIp());
        if (!bindIp.isEmpty() && !validIp(bindIp)) return R.err("监听 IP 格式不正确");
        List<String> cidrs = parseCidrs(dto.getAllowedCidrs());
        if (cidrs == null) return R.err("IP 白名单格式不正确，请使用 CIDR 并以逗号分隔");
        if (Boolean.FALSE.equals(dto.getPermanent()) && (dto.getLeaseHours() == null || dto.getLeaseHours() < 1)) {
            return R.err("定时代理必须填写有效期");
        }
        if (Boolean.TRUE.equals(portLedgerService.diagnose(node.getId(), dto.getListenPort()).get("occupied"))) {
            return R.err("端口已被面板中的其他业务占用");
        }
        AgentPortCheckUtil.Result portCheck = AgentPortCheckUtil.check(node,
                List.of(new AgentPortCheckUtil.Check("tcp", bindIp, dto.getListenPort())));
        if (!portCheck.isAvailable()) return R.err(portCheck.getMessage());

        long now = System.currentTimeMillis();
        PrivateProxy proxy = new PrivateProxy();
        proxy.setUserId(userId);
        proxy.setName(dto.getName().trim());
        proxy.setNodeId(node.getId());
        proxy.setProxyType(dto.getProxyType());
        proxy.setBindIp(bindIp);
        proxy.setListenPort(dto.getListenPort());
        proxy.setAuthUsername(dto.getAuthUsername().trim());
        proxy.setAuthPassword(crypto.encrypt(dto.getAuthPassword()));
        proxy.setAllowedCidrs(String.join(",", cidrs));
        proxy.setState("provisioning");
        proxy.setExpiresAt(Boolean.TRUE.equals(dto.getPermanent()) ? null : now + dto.getLeaseHours() * 3_600_000L);
        assignRuntimeNames(proxy, !cidrs.isEmpty());
        proxy.setCreatedTime(now);
        proxy.setUpdatedTime(now);
        proxyMapper.insert(proxy);

        try {
            if (proxy.getAdmissionName() != null) {
                requireGost(GostUtil.AddAdmission(node.getId(), proxy.getAdmissionName(), cidrs), "创建 IP 白名单失败");
            }
            requireGost(GostUtil.AddPrivateProxy(node.getId(), proxy.getServiceName(), proxy.getProxyType(), bindIp,
                    proxy.getListenPort(), proxy.getAuthUsername(), dto.getAuthPassword(), proxy.getAdmissionName()), "创建代理失败");
            proxy.setState("active");
            proxy.setLastError(null);
            proxy.setUpdatedTime(System.currentTimeMillis());
            proxyMapper.updateById(proxy);
            return R.ok(view(proxy));
        } catch (RuntimeException e) {
            boolean cleaned = cleanupRuntime(proxy, node);
            proxy.setState(cleaned ? "error" : "delete_pending");
            proxy.setLastError(cleaned ? e.getMessage() : e.getMessage() + "；Agent 清理失败，端口保持占用并自动重试");
            proxy.setUpdatedTime(System.currentTimeMillis());
            proxyMapper.updateById(proxy);
            return R.err(e.getMessage());
        }
    }

    public R list() {
        QueryWrapper<PrivateProxy> query = new QueryWrapper<PrivateProxy>().ne("state", "deleted").orderByDesc("created_time");
        if (!isAdmin()) query.eq("user_id", JwtUtil.getUserIdFromToken());
        return R.ok(proxyMapper.selectList(query).stream().map(this::view).collect(Collectors.toList()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordTraffic(String serviceName, long inFlow, long outFlow) {
        PrivateProxy proxy = proxyMapper.selectOne(new QueryWrapper<PrivateProxy>()
                .eq("service_name", serviceName).notIn("state", "deleted", "expired", "error").last("LIMIT 1"));
        if (proxy == null) return;
        long inbound = Math.max(0L, inFlow);
        long outbound = Math.max(0L, outFlow);
        if (inbound == 0L && outbound == 0L) return;

        proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxy.getId())
                .setSql("in_flow = in_flow + " + inbound)
                .setSql("out_flow = out_flow + " + outbound)
                .set("updated_time", System.currentTimeMillis()));
        userMapper.update(null, new UpdateWrapper<User>().eq("id", proxy.getUserId())
                .setSql("in_flow = in_flow + " + inbound)
                .setSql("out_flow = out_flow + " + outbound));
        userQuotaService.recordPrivateProxyFlow(proxy, inbound, outbound);

        if (isAdminUser(proxy.getUserId())) return;
        Node node = nodeMapper.selectById(proxy.getNodeId());
        R quota = userQuotaService.checkNodeQuota(proxy.getUserId(), node, proxy.getId());
        if (quota.getCode() == 0) return;
        GostDto paused = GostUtil.PauseNamedService(proxy.getNodeId(), proxy.getServiceName());
        if (gostSuccess(paused)) {
            updateState(proxy, "paused", quota.getMsg());
        } else {
            proxy.setLastError(quota.getMsg() + "；自动暂停失败：" + gostMessage(paused));
            proxy.setUpdatedTime(System.currentTimeMillis());
            proxyMapper.updateById(proxy);
        }
    }

    public R pause(Long id) {
        PrivateProxy proxy = owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        if (!"active".equals(proxy.getState())) return R.err("只有运行中的代理可以暂停");
        GostDto result = GostUtil.PauseNamedService(proxy.getNodeId(), proxy.getServiceName());
        if (!gostSuccess(result)) return R.err(gostMessage(result));
        updateState(proxy, "paused", null);
        return R.ok();
    }

    public R resume(Long id) {
        PrivateProxy proxy = owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        if (!"paused".equals(proxy.getState())) return R.err("只有暂停的代理可以恢复");
        if (proxy.getExpiresAt() != null && proxy.getExpiresAt() <= System.currentTimeMillis()) return R.err("代理已到期");
        Node node = nodeMapper.selectById(proxy.getNodeId());
        R quota = isAdmin() ? R.ok() : userQuotaService.checkNodeQuota(proxy.getUserId(), node, proxy.getId());
        if (quota.getCode() != 0) return quota;
        GostDto result = GostUtil.ResumeNamedService(proxy.getNodeId(), proxy.getServiceName());
        if (!gostSuccess(result)) return R.err(gostMessage(result));
        updateState(proxy, "active", null);
        return R.ok();
    }

    public R delete(Long id) {
        PrivateProxy proxy = owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        Node node = nodeMapper.selectById(proxy.getNodeId());
        if (node == null || !WebSocketServer.isNodeOnline(proxy.getNodeId())) {
            updateState(proxy, "delete_pending", "节点离线，待上线后自动清理");
            return R.ok("节点离线，代理已进入待清理状态，端口暂不释放");
        }
        if (!cleanupRuntime(proxy, node)) {
            updateState(proxy, "delete_pending", "Agent 清理失败，将自动重试");
            return R.err("Agent 清理失败，已加入自动重试队列");
        }
        updateState(proxy, "deleted", null);
        return R.ok();
    }

    @Scheduled(initialDelay = 30_000L, fixedDelay = 30_000L)
    public void reconcileExpiryAndCleanup() {
        long now = System.currentTimeMillis();
        List<PrivateProxy> pending = proxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .and(q -> q.eq("state", "delete_pending")
                        .or(n -> n.in("state", "active", "paused").isNotNull("expires_at").le("expires_at", now))));
        for (PrivateProxy proxy : pending) {
            Node node = nodeMapper.selectById(proxy.getNodeId());
            if (node == null || !WebSocketServer.isNodeOnline(proxy.getNodeId())) {
                if (!"delete_pending".equals(proxy.getState())) updateState(proxy, "delete_pending", "代理已到期，等待节点上线清理");
                continue;
            }
            if (cleanupRuntime(proxy, node)) updateState(proxy, proxy.getExpiresAt() != null && proxy.getExpiresAt() <= now ? "expired" : "deleted", null);
        }
    }

    private PrivateProxy view(PrivateProxy proxy) {
        Node node = nodeMapper.selectById(proxy.getNodeId());
        User owner = userMapper.selectById(proxy.getUserId());
        proxy.setNodeName(node == null ? "节点已删除" : node.getName());
        proxy.setPublicHost(node == null ? null : StringUtils.defaultIfBlank(node.getServerIp(), node.getIp()));
        proxy.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
        proxy.setNodeOnline(node != null && WebSocketServer.isNodeOnline(node.getId()));
        proxy.setPasswordConfigured(StringUtils.isNotBlank(proxy.getAuthPassword()));
        proxy.setAuthPassword(null);
        return proxy;
    }

    private PrivateProxy owned(Long id) {
        PrivateProxy proxy = proxyMapper.selectById(id);
        if (proxy == null || "deleted".equals(proxy.getState())) return null;
        if (!isAdmin() && !Objects.equals(proxy.getUserId(), JwtUtil.getUserIdFromToken())) return null;
        return proxy;
    }

    private boolean cleanupRuntime(PrivateProxy proxy, Node node) {
        if (node == null) return false;
        GostDto serviceResult = GostUtil.DeleteNamedService(node.getId(), proxy.getServiceName());
        boolean serviceClean = gostCleanupSuccess(serviceResult);
        boolean admissionClean = true;
        if (proxy.getAdmissionName() != null) {
            admissionClean = gostCleanupSuccess(GostUtil.DeleteAdmission(node.getId(), proxy.getAdmissionName()));
        }
        return serviceClean && admissionClean;
    }

    static void assignRuntimeNames(PrivateProxy proxy, boolean withAdmission) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        proxy.setServiceName("private-proxy-" + suffix);
        proxy.setAdmissionName(withAdmission ? "private-proxy-admission-" + suffix : null);
    }

    private List<String> parseCidrs(String value) {
        if (StringUtils.isBlank(value)) return List.of();
        List<String> items = Arrays.stream(value.split("[,\\n]"))
                .map(String::trim).filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList());
        for (String item : items) {
            String[] parts = item.split("/");
            if (parts.length != 2 || !validIp(parts[0])) return null;
            try {
                int prefix = Integer.parseInt(parts[1]);
                int max = parts[0].contains(":") ? 128 : 32;
                if (prefix < 0 || prefix > max) return null;
            } catch (NumberFormatException e) { return null; }
        }
        return items;
    }

    private boolean validIp(String value) {
        try { return InetAddress.getByName(value).getHostAddress() != null && (value.contains(":") || value.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")); }
        catch (Exception e) { return false; }
    }

    private void requireGost(GostDto result, String prefix) {
        if (!gostSuccess(result)) throw new IllegalStateException(prefix + "：" + gostMessage(result));
    }

    private boolean gostSuccess(GostDto result) { return result != null && "OK".equals(result.getMsg()); }
    private boolean gostCleanupSuccess(GostDto result) {
        return result != null && ("OK".equals(result.getMsg()) || StringUtils.containsIgnoreCase(result.getMsg(), "not found"));
    }
    private String gostMessage(GostDto result) { return result == null ? "Agent 无响应" : StringUtils.defaultIfBlank(result.getMsg(), "Agent 无响应"); }
    private boolean isAdmin() { return Objects.equals(JwtUtil.getRoleIdFromToken(), 0); }
    private boolean isAdminUser(Integer userId) {
        User user = userMapper.selectById(userId);
        return user != null && Objects.equals(user.getRoleId(), 0);
    }
    private void updateState(PrivateProxy proxy, String state, String error) {
        proxy.setState(state); proxy.setLastError(error); proxy.setUpdatedTime(System.currentTimeMillis()); proxyMapper.updateById(proxy);
    }
}
