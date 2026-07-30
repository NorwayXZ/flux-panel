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
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PrivateProxyService {
    private static final String DEFAULT_REALITY_SERVER_NAME = "www.cloudflare.com";
    private static final String MIN_AGENT_VERSION = "2.19.0";
    private static final String MIN_REALITY_AGENT_VERSION = "2.20.0";
    private static final String MIN_ADVANCED_PROXY_AGENT_VERSION = "2.38.0";
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
        String proxyType = dto.getProxyType();
        if ("vless_reality".equals(proxyType)
                && !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_REALITY_AGENT_VERSION)) {
            return R.err("VLESS+REALITY 需要节点 Agent " + MIN_REALITY_AGENT_VERSION + " 或更高版本");
        }
        if (isAdvancedRuntime(proxyType)
                && !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_ADVANCED_PROXY_AGENT_VERSION)) {
            return R.err(protocolLabel(proxyType) + " 需要节点 Agent " + MIN_ADVANCED_PROXY_AGENT_VERSION + " 或更高版本");
        }
        String username = StringUtils.trimToEmpty(dto.getAuthUsername());
        String password = StringUtils.defaultString(dto.getAuthPassword());
        String cipher = StringUtils.defaultIfBlank(dto.getCipher(), "aes-256-gcm");
        String realityServerName = normalizeServerName("vless_reality".equals(proxyType)
                ? StringUtils.defaultIfBlank(dto.getRealityServerName(), DEFAULT_REALITY_SERVER_NAME)
                : dto.getRealityServerName());
        if (("socks5".equals(proxyType) || "http".equals(proxyType))
                && (username.length() < 3 || password.length() < 8)) {
            return R.err("SOCKS5/HTTP 用户名至少 3 位，密码至少 8 位");
        }
        if ("shadowsocks".equals(proxyType) && password.length() < 8) {
            return R.err("Shadowsocks 密码至少 8 位");
        }
        if ("vless_reality".equals(proxyType) && realityServerName == null) {
            return R.err("请填写有效的 REALITY 伪装域名，不要包含协议或路径");
        }
        if (isAdvancedRuntime(proxyType) && password.length() < 8) {
            return R.err(protocolLabel(proxyType) + " 密钥至少 8 位");
        }
        if (node.getPortSta() != null && dto.getListenPort() < node.getPortSta()
                || node.getPortEnd() != null && dto.getListenPort() > node.getPortEnd()) {
            return R.err("监听端口不在节点允许范围内");
        }
        String bindIp = StringUtils.trimToEmpty(dto.getBindIp());
        if (!bindIp.isEmpty() && !validIp(bindIp)) return R.err("监听 IP 格式不正确");
        List<String> cidrs = parseCidrs(dto.getAllowedCidrs());
        if (cidrs == null) return R.err("IP 白名单格式不正确，请使用 CIDR 并以逗号分隔");
        if (isAdvancedRuntime(proxyType) && !cidrs.isEmpty()) {
            return R.err(protocolLabel(proxyType) + " 暂不支持来源 IP 白名单，请先留空创建");
        }
        if (Boolean.FALSE.equals(dto.getPermanent()) && (dto.getLeaseHours() == null || dto.getLeaseHours() < 1)) {
            return R.err("定时代理必须填写有效期");
        }
        if (Boolean.TRUE.equals(portLedgerService.diagnose(node.getId(), dto.getListenPort()).get("occupied"))) {
            return R.err("端口已被面板中的其他业务占用");
        }
        List<AgentPortCheckUtil.Check> checks = "shadowsocks".equals(proxyType)
                ? List.of(new AgentPortCheckUtil.Check("tcp", bindIp, dto.getListenPort()),
                new AgentPortCheckUtil.Check("udp", bindIp, dto.getListenPort()))
                : isUdpAdvancedRuntime(proxyType)
                ? List.of(new AgentPortCheckUtil.Check("udp", bindIp, dto.getListenPort()))
                : List.of(new AgentPortCheckUtil.Check("tcp", bindIp, dto.getListenPort()));
        AgentPortCheckUtil.Result portCheck = AgentPortCheckUtil.check(node, checks);
        if (!portCheck.isAvailable()) return R.err(portCheck.getMessage());

        long now = System.currentTimeMillis();
        PrivateProxy proxy = new PrivateProxy();
        proxy.setUserId(userId);
        proxy.setName(dto.getName().trim());
        proxy.setNodeId(node.getId());
        proxy.setProxyType(proxyType);
        proxy.setBindIp(bindIp);
        proxy.setListenPort(dto.getListenPort());
        proxy.setAuthUsername("shadowsocks".equals(proxyType) ? cipher : ("vless_reality".equals(proxyType) || isAdvancedRuntime(proxyType) ? "待生成" : username));
        proxy.setAuthPassword(crypto.encrypt("vless_reality".equals(proxyType) ? UUID.randomUUID().toString() : password));
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
            Map<String, Object> clientConfig;
            if ("shadowsocks".equals(proxyType)) {
                requireGost(GostUtil.AddShadowsocksProxy(node.getId(), proxy.getServiceName(), bindIp,
                        proxy.getListenPort(), cipher, password, proxy.getAdmissionName()), "创建 Shadowsocks 失败");
                clientConfig = new LinkedHashMap<>();
                clientConfig.put("cipher", cipher);
                clientConfig.put("password", password);
            } else if ("vless_reality".equals(proxyType)) {
                GostDto runtime = GostUtil.AddRealityRuntime(node.getId(), realityRuntimeName(proxy), realityServerName);
                requireGost(runtime, "准备 REALITY 运行时失败");
                JSONObject runtimeData = JSONObject.parseObject(JSONObject.toJSONString(runtime.getData()));
                Integer runtimePort = runtimeData == null ? null : runtimeData.getInteger("port");
                String clientId = runtimeData == null ? null : runtimeData.getString("clientId");
                String publicKey = runtimeData == null ? null : runtimeData.getString("publicKey");
                String shortId = runtimeData == null ? null : runtimeData.getString("shortId");
                if (runtimePort == null || StringUtils.isAnyBlank(clientId, publicKey, shortId)) {
                    throw new IllegalStateException("Agent 返回的 REALITY 配置不完整");
                }
                proxy.setAuthUsername(clientId);
                proxy.setAuthPassword(crypto.encrypt(clientId));
                requireGost(GostUtil.AddRealityFrontend(node.getId(), proxy.getServiceName(), bindIp,
                        proxy.getListenPort(), runtimePort, proxy.getAdmissionName()), "创建 REALITY 公网入口失败");
                clientConfig = new LinkedHashMap<>();
                clientConfig.put("clientId", clientId);
                clientConfig.put("publicKey", publicKey);
                clientConfig.put("shortId", shortId);
                clientConfig.put("serverName", realityServerName);
                clientConfig.put("fingerprint", "chrome");
                clientConfig.put("flow", "xtls-rprx-vision");
                clientConfig.put("runtimeVersion", runtimeData.getString("version"));
            } else if (isAdvancedRuntime(proxyType)) {
                GostDto runtime = GostUtil.AddPrivateProxyRuntime(node.getId(), advancedRuntimeName(proxy), proxyType,
                        bindIp, proxy.getListenPort(), password);
                requireGost(runtime, "创建 " + protocolLabel(proxyType) + " 运行时失败");
                JSONObject runtimeData = JSONObject.parseObject(JSONObject.toJSONString(runtime.getData()));
                if (runtimeData == null) throw new IllegalStateException("Agent 返回的 " + protocolLabel(proxyType) + " 配置为空");
                clientConfig = new LinkedHashMap<>();
                clientConfig.put("password", password);
                clientConfig.put("serverName", StringUtils.defaultIfBlank(runtimeData.getString("serverName"), "cloudnest.local"));
                clientConfig.put("runtimeVersion", runtimeData.getString("version"));
                if ("tuic".equals(proxyType)) {
                    String clientId = runtimeData.getString("clientId");
                    if (StringUtils.isBlank(clientId)) throw new IllegalStateException("Agent 返回的 TUIC UUID 不完整");
                    proxy.setAuthUsername(clientId);
                    clientConfig.put("clientId", clientId);
                } else if ("wireguard".equals(proxyType)) {
                    String privateKey = runtimeData.getString("clientPrivateKey");
                    String publicKey = runtimeData.getString("serverPublicKey");
                    String clientAddress = runtimeData.getString("clientAddress");
                    if (StringUtils.isAnyBlank(privateKey, publicKey, clientAddress)) {
                        throw new IllegalStateException("Agent 返回的 WireGuard 密钥不完整");
                    }
                    proxy.setAuthUsername("WireGuard");
                    clientConfig.put("clientPrivateKey", privateKey);
                    clientConfig.put("serverPublicKey", publicKey);
                    clientConfig.put("clientAddress", clientAddress);
                }
            } else {
                requireGost(GostUtil.AddPrivateProxy(node.getId(), proxy.getServiceName(), proxyType, bindIp,
                        proxy.getListenPort(), username, password, proxy.getAdmissionName()), "创建代理失败");
                clientConfig = new LinkedHashMap<>();
                clientConfig.put("username", username);
                clientConfig.put("password", password);
            }
            proxy.setClientConfig(crypto.encrypt(JSONObject.toJSONString(clientConfig)));
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

    public R clientConfig(Long id) {
        PrivateProxy proxy = owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        Node node = nodeMapper.selectById(proxy.getNodeId());
        String host = node == null ? null : StringUtils.defaultIfBlank(node.getServerIp(), node.getIp());
        if (StringUtils.isBlank(host)) return R.err("节点未配置公网地址");
        try {
            JSONObject config;
            if (StringUtils.isNotBlank(proxy.getClientConfig())) {
                config = JSONObject.parseObject(crypto.decryptString(proxy.getClientConfig()));
            } else if ("socks5".equals(proxy.getProxyType()) || "http".equals(proxy.getProxyType())) {
                config = new JSONObject();
                config.put("username", proxy.getAuthUsername());
                config.put("password", crypto.decryptString(proxy.getAuthPassword()));
            } else if ("shadowsocks".equals(proxy.getProxyType())) {
                config = new JSONObject();
                config.put("cipher", proxy.getAuthUsername());
                config.put("password", crypto.decryptString(proxy.getAuthPassword()));
            } else {
                return R.err("该代理没有可用的连接信息");
            }
            config.put("proxyType", proxy.getProxyType());
            config.put("name", proxy.getName());
            config.put("host", host);
            config.put("port", proxy.getListenPort());
            config.put("uri", buildClientUri(proxy, host, config));
            return R.ok(config);
        } catch (Exception e) {
            return R.err("连接信息解密失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordTraffic(String serviceName, long inFlow, long outFlow) {
        PrivateProxy proxy = proxyMapper.selectOne(new QueryWrapper<PrivateProxy>()
                .and(q -> q.eq("service_name", serviceName)
                        .or().eq("service_name", shadowsocksBaseName(serviceName)))
                .notIn("state", "deleted", "expired", "error").last("LIMIT 1"));
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
        GostDto paused = pauseRuntime(proxy);
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
        GostDto result = pauseRuntime(proxy);
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
        GostDto result = resumeRuntime(proxy);
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
        proxy.setClientConfig(null);
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
        boolean serviceClean = true;
        if (!isAdvancedRuntime(proxy.getProxyType())) {
            serviceClean = gostCleanupSuccess(GostUtil.DeleteNamedServices(node.getId(), runtimeServiceNames(proxy)));
        }
        boolean advancedRuntimeClean = true;
        if ("vless_reality".equals(proxy.getProxyType())) {
            advancedRuntimeClean = gostCleanupSuccess(GostUtil.DeleteRealityRuntime(node.getId(), realityRuntimeName(proxy)));
        } else if (isAdvancedRuntime(proxy.getProxyType())) {
            advancedRuntimeClean = gostCleanupSuccess(GostUtil.DeletePrivateProxyRuntime(node.getId(), advancedRuntimeName(proxy)));
        }
        boolean admissionClean = true;
        if (proxy.getAdmissionName() != null) {
            admissionClean = gostCleanupSuccess(GostUtil.DeleteAdmission(node.getId(), proxy.getAdmissionName()));
        }
        return serviceClean && admissionClean && advancedRuntimeClean;
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

    private List<String> runtimeServiceNames(PrivateProxy proxy) {
        return "shadowsocks".equals(proxy.getProxyType())
                ? List.of(proxy.getServiceName() + "-tcp", proxy.getServiceName() + "-udp")
                : List.of(proxy.getServiceName());
    }

    private String shadowsocksBaseName(String serviceName) {
        if (serviceName == null) return "";
        if (serviceName.endsWith("-tcp") || serviceName.endsWith("-udp")) {
            return serviceName.substring(0, serviceName.length() - 4);
        }
        return serviceName;
    }

    private String realityRuntimeName(PrivateProxy proxy) {
        return proxy.getServiceName() + "-xray";
    }

    private String advancedRuntimeName(PrivateProxy proxy) {
        return proxy.getServiceName() + "-singbox";
    }

    private String normalizeServerName(String value) {
        String result = StringUtils.trimToEmpty(value).toLowerCase();
        if (result.endsWith(".")) result = result.substring(0, result.length() - 1);
        if (result.isEmpty() || result.length() > 253 || result.contains(":") || result.contains("/")
                || !result.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")) return null;
        return result;
    }

    private String buildClientUri(PrivateProxy proxy, String host, JSONObject config) {
        String label = url(proxy.getName());
        String authorityHost = host.contains(":") ? "[" + host + "]" : host;
        if ("shadowsocks".equals(proxy.getProxyType())) {
            String credential = config.getString("cipher") + ":" + config.getString("password");
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
            return "ss://" + encoded + "@" + authorityHost + ":" + proxy.getListenPort() + "#" + label;
        }
        if ("vless_reality".equals(proxy.getProxyType())) {
            return "vless://" + config.getString("clientId") + "@" + authorityHost + ":" + proxy.getListenPort()
                    + "?encryption=none&flow=xtls-rprx-vision&security=reality&type=tcp&headerType=none"
                    + "&sni=" + url(config.getString("serverName")) + "&fp=chrome&pbk=" + url(config.getString("publicKey"))
                    + "&sid=" + url(config.getString("shortId")) + "#" + label;
        }
        if ("trojan".equals(proxy.getProxyType())) {
            return "trojan://" + url(config.getString("password")) + "@" + authorityHost + ":" + proxy.getListenPort()
                    + "?security=tls&sni=" + url(config.getString("serverName")) + "&allowInsecure=1#" + label;
        }
        if ("hysteria2".equals(proxy.getProxyType())) {
            return "hysteria2://" + url(config.getString("password")) + "@" + authorityHost + ":" + proxy.getListenPort()
                    + "?sni=" + url(config.getString("serverName")) + "&insecure=1#" + label;
        }
        if ("tuic".equals(proxy.getProxyType())) {
            return "tuic://" + url(config.getString("clientId")) + ":" + url(config.getString("password")) + "@"
                    + authorityHost + ":" + proxy.getListenPort() + "?congestion_control=bbr&sni="
                    + url(config.getString("serverName")) + "&allow_insecure=1#" + label;
        }
        if ("wireguard".equals(proxy.getProxyType())) {
            return "[Interface]\nPrivateKey = " + config.getString("clientPrivateKey") + "\nAddress = "
                    + config.getString("clientAddress") + "\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = "
                    + config.getString("serverPublicKey") + "\nEndpoint = " + authorityHost + ":" + proxy.getListenPort()
                    + "\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n";
        }
        return proxy.getProxyType() + "://" + url(config.getString("username")) + ":"
                + url(config.getString("password")) + "@" + authorityHost + ":" + proxy.getListenPort() + "#" + label;
    }

    private String url(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8).replace("+", "%20");
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

    private boolean isAdvancedRuntime(String proxyType) {
        return "trojan".equals(proxyType) || "hysteria2".equals(proxyType)
                || "tuic".equals(proxyType) || "wireguard".equals(proxyType);
    }

    private boolean isUdpAdvancedRuntime(String proxyType) {
        return "hysteria2".equals(proxyType) || "tuic".equals(proxyType) || "wireguard".equals(proxyType);
    }

    private GostDto pauseRuntime(PrivateProxy proxy) {
        return isAdvancedRuntime(proxy.getProxyType())
                ? GostUtil.PausePrivateProxyRuntime(proxy.getNodeId(), advancedRuntimeName(proxy))
                : GostUtil.PauseNamedServices(proxy.getNodeId(), runtimeServiceNames(proxy));
    }

    private GostDto resumeRuntime(PrivateProxy proxy) {
        return isAdvancedRuntime(proxy.getProxyType())
                ? GostUtil.ResumePrivateProxyRuntime(proxy.getNodeId(), advancedRuntimeName(proxy))
                : GostUtil.ResumeNamedServices(proxy.getNodeId(), runtimeServiceNames(proxy));
    }

    private String protocolLabel(String proxyType) {
        switch (proxyType) {
            case "trojan": return "Trojan";
            case "hysteria2": return "Hysteria2";
            case "tuic": return "TUIC v5";
            case "wireguard": return "WireGuard";
            default: return proxyType;
        }
    }
    private boolean isAdminUser(Integer userId) {
        User user = userMapper.selectById(userId);
        return user != null && Objects.equals(user.getRoleId(), 0);
    }
    private void updateState(PrivateProxy proxy, String state, String error) {
        proxy.setState(state); proxy.setLastError(error); proxy.setUpdatedTime(System.currentTimeMillis()); proxyMapper.updateById(proxy);
    }
}
