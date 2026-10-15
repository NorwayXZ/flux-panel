package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.PrivateProxyCreateDto;
import com.admin.common.dto.PrivateProxyGrantDto;
import com.admin.common.dto.PrivateProxyGrantUpdateDto;
import com.admin.common.dto.PrivateProxyUpdateDto;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PrivateProxyService {
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final int UNLIMITED_LIMITER_MBPS = 1_000_000;
    private static final String DEFAULT_REALITY_SERVER_NAME = "www.cloudflare.com";
    private static final String MIN_AGENT_VERSION = "2.19.0";
    private static final String MIN_REALITY_AGENT_VERSION = "2.20.0";
    private static final String MIN_ADVANCED_PROXY_AGENT_VERSION = "2.38.0";
    private static final String MIN_ADVANCED_GRANT_AGENT_VERSION = "2.40.0";
    /** 新协议、自检、独立运行时白名单、修改端口和重置密钥需要的 Agent 版本 */
    static final String MIN_PROTOCOL_AGENT_VERSION = "2.62.0";
    static final java.util.Set<String> NEW_PROTOCOL_TYPES = java.util.Set.of(
            "shadowsocks2022", "vless_xhttp", "vless", "vmess", "anytls", "shadowtls");
    private static final String DEFAULT_TLS_SERVER_NAME = "cloudnest.local";
    private static final String DEFAULT_SHADOWTLS_SERVER = "www.microsoft.com";
    private final PrivateProxyMapper proxyMapper;
    private final NodeMapper nodeMapper;
    private final UserMapper userMapper;
    private final PortAllocationLockMapper allocationLockMapper;
    private final PortLedgerService portLedgerService;
    private final UserQuotaService userQuotaService;
    private final ExitPoolService exitPoolService;
    private final AESCrypto crypto;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public PrivateProxyService(PrivateProxyMapper proxyMapper, NodeMapper nodeMapper, UserMapper userMapper,
                               PortAllocationLockMapper allocationLockMapper, PortLedgerService portLedgerService,
                               UserQuotaService userQuotaService, ExitPoolService exitPoolService,
                               PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate,
                               @Value("${jwt-secret}") String secret) {
        this.jdbcTemplate = jdbcTemplate;
        this.proxyMapper = proxyMapper;
        this.nodeMapper = nodeMapper;
        this.userMapper = userMapper;
        this.allocationLockMapper = allocationLockMapper;
        this.portLedgerService = portLedgerService;
        this.userQuotaService = userQuotaService;
        this.exitPoolService = exitPoolService;
        this.crypto = new AESCrypto(secret + ":private-proxy");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(rollbackFor = Exception.class)
    public R create(PrivateProxyCreateDto dto) {
        return createInternal(dto, JwtUtil.getUserIdFromToken(), null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public R grant(PrivateProxyGrantDto dto) {
        if (!isAdmin()) return R.err("只有管理员可以授权代理");
        User target = userMapper.selectById(dto.getTargetUserId());
        if (target == null || Objects.equals(target.getRoleId(), 0)) return R.err("请选择普通用户");
        if (!supportsGrant(dto.getProxyType())) {
            return R.err("该协议暂不支持用户授权");
        }
        if (!Boolean.TRUE.equals(dto.getFlowUnlimited()) && value(dto.getFlowLimit()) <= 0L) {
            return R.err("流量额度必须大于 0");
        }
        if (!Boolean.TRUE.equals(dto.getPermanent())
                && (dto.getExpiresAt() == null || dto.getExpiresAt() <= System.currentTimeMillis())) {
            return R.err("请选择未来的到期时间");
        }
        return createInternal(dto, dto.getTargetUserId(), JwtUtil.getUserIdFromToken(), dto);
    }

    private R createInternal(PrivateProxyCreateDto dto, Integer ownerUserId, Integer grantedByUserId,
                             PrivateProxyGrantDto grant) {
        allocationLockMapper.lockForUpdate();
        Integer userId = ownerUserId;
        Node node = nodeMapper.selectById(dto.getNodeId());
        if (node == null) return R.err("节点不存在");
        R quota = grantedByUserId != null || isAdmin() ? R.ok() : userQuotaService.checkNodeQuota(userId, node, null);
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
        if (NEW_PROTOCOL_TYPES.contains(proxyType)
                && !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_PROTOCOL_AGENT_VERSION)) {
            return R.err(protocolLabel(proxyType) + " 需要节点 Agent " + MIN_PROTOCOL_AGENT_VERSION + " 或更高版本，请先升级该节点");
        }
        if (isAdvancedRuntime(proxyType)
                && !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_ADVANCED_PROXY_AGENT_VERSION)) {
            return R.err(protocolLabel(proxyType) + " 需要节点 Agent " + MIN_ADVANCED_PROXY_AGENT_VERSION + " 或更高版本");
        }
        if (grant != null && isAdvancedRuntime(proxyType)
                && !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_ADVANCED_GRANT_AGENT_VERSION)) {
            return R.err(protocolLabel(proxyType) + " 用户授权需要节点 Agent "
                    + MIN_ADVANCED_GRANT_AGENT_VERSION + " 或更高版本，才能执行独立流量额度");
        }
        Spec spec = new Spec();
        R specCheck = fillSpec(spec, dto, proxyType);
        if (specCheck.getCode() != 0) return specCheck;
        R camouflage = settleCamouflage(spec, node, proxyType);
        if (camouflage.getCode() != 0) return camouflage;
        if ("hysteria2".equals(proxyType) && spec.obfsPassword != null
                && !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_PROTOCOL_AGENT_VERSION)) {
            return R.err("Hysteria2 混淆需要节点 Agent " + MIN_PROTOCOL_AGENT_VERSION + " 或更高版本");
        }
        if (node.getPortSta() != null && dto.getListenPort() < node.getPortSta()
                || node.getPortEnd() != null && dto.getListenPort() > node.getPortEnd()) {
            return R.err("监听端口不在节点允许范围内");
        }
        String bindIp = StringUtils.trimToEmpty(dto.getBindIp());
        if (!bindIp.isEmpty() && !validIp(bindIp)) return R.err("监听 IP 格式不正确");
        String connectHost = normalizeConnectHost(dto.getConnectHost());
        if (connectHost == null) return R.err("连接地址格式不正确，请填写域名或 IP");
        List<String> cidrs = parseCidrs(dto.getAllowedCidrs());
        if (cidrs == null) return R.err("IP 白名单格式不正确，请使用 CIDR 并以逗号分隔");
        R cidrCheck = checkAllowedSources(proxyType, node, cidrs);
        if (cidrCheck.getCode() != 0) return cidrCheck;
        if (grant == null && Boolean.FALSE.equals(dto.getPermanent()) && (dto.getLeaseHours() == null || dto.getLeaseHours() < 1)) {
            return R.err("定时代理必须填写有效期");
        }
        boolean residentialExit = "residential".equals(dto.getExitType());
        String exitCountry = residentialExit ? ExitPoolService.normalizeCountry(dto.getExitCountry()) : null;
        if (residentialExit) {
            R exitCheck = checkResidentialExit(proxyType, node, exitCountry);
            if (exitCheck.getCode() != 0) return exitCheck;
        }
        R portCheck = checkListenPort(node, proxyType, bindIp, dto.getListenPort());
        if (portCheck.getCode() != 0) return portCheck;

        long now = System.currentTimeMillis();
        PrivateProxy proxy = new PrivateProxy();
        proxy.setUserId(userId);
        proxy.setGrantedByUserId(grantedByUserId);
        proxy.setName(dto.getName().trim());
        proxy.setNodeId(node.getId());
        proxy.setProxyType(proxyType);
        proxy.setBindIp(bindIp);
        proxy.setListenPort(dto.getListenPort());
        proxy.setAuthUsername(initialUsername(proxyType, spec));
        proxy.setAuthPassword(crypto.encrypt(storedSecret(proxyType, spec)));
        proxy.setAllowedCidrs(String.join(",", cidrs));
        proxy.setConnectHost(connectHost.isEmpty() ? null : connectHost);
        proxy.setState("provisioning");
        proxy.setExpiresAt(Boolean.TRUE.equals(dto.getPermanent()) ? null
                : grant != null ? grant.getExpiresAt() : now + dto.getLeaseHours() * 3_600_000L);
        proxy.setFlowLimit(grant == null ? 0L : grant.getFlowLimit());
        proxy.setFlowUnlimited(grant == null || Boolean.TRUE.equals(grant.getFlowUnlimited()) ? 1 : 0);
        proxy.setFlowResetDay(grant == null ? 0 : grant.getFlowResetDay());
        proxy.setLastFlowResetAt(grant == null ? null : now);
        proxy.setSpeedLimitMbps(grant != null && supportsGrantRateLimit(proxyType) ? grant.getSpeedLimitMbps() : null);
        proxy.setExitType(residentialExit ? "residential" : "node");
        proxy.setExitCountry(exitCountry);
        assignRuntimeNames(proxy, !cidrs.isEmpty());
        proxy.setCreatedTime(now);
        proxy.setUpdatedTime(now);
        proxyMapper.insert(proxy);

        try {
            JSONObject clientConfig = provision(proxy, node, spec, false);
            proxy.setClientConfig(crypto.encrypt(JSONObject.toJSONString(clientConfig)));
            proxy.setState("active");
            proxy.setLastError(null);
            proxy.setUpdatedTime(System.currentTimeMillis());
            proxyMapper.updateById(proxy);
            if (residentialExit) reconcileExitsAfterCommit();
            checkAfterCommit(proxy.getId());
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

    /**
     * 协议相关的设置。保存在加密的客户端配置里，修改端口、重置密钥或自动修复时用它重建运行时。
     */
    static final class Spec {
        String username = "";
        String password = "";
        String cipher;
        String serverName;
        String transport;
        boolean tls;
        String path;
        String method;
        String obfsPassword;
        String handshakeServer;
        String innerPassword;
        String clientId;
    }

    /** 根据创建请求填写协议设置；随机生成的密钥和路径也在这里生成，之后保持不变。 */
    private R fillSpec(Spec spec, PrivateProxyCreateDto dto, String proxyType) {
        String password = StringUtils.defaultString(dto.getAuthPassword());
        switch (proxyType) {
            case "socks5":
            case "http":
            case "mixed":
                spec.username = StringUtils.trimToEmpty(dto.getAuthUsername());
                spec.password = password;
                if (spec.username.length() < 3 || password.length() < 8) return R.err("SOCKS5/HTTP 用户名至少 3 位，密码至少 8 位");
                return R.ok();
            case "shadowsocks":
                spec.password = password;
                spec.cipher = StringUtils.defaultIfBlank(dto.getCipher(), "aes-256-gcm");
                if (password.length() < 8) return R.err("Shadowsocks 密码至少 8 位");
                return R.ok();
            case "shadowsocks2022":
                spec.method = StringUtils.defaultIfBlank(dto.getMethod(), "2022-blake3-aes-128-gcm");
                spec.password = randomKey(ss2022KeySize(spec.method));
                return R.ok();
            case "vless_reality":
            case "vless_xhttp":
                spec.serverName = camouflageInput(dto.getRealityServerName());
                if (spec.serverName == null) return R.err("请填写有效的 REALITY 伪装域名，不要包含协议或路径");
                if ("vless_xhttp".equals(proxyType)) spec.path = "/" + randomHex(8);
                return R.ok();
            case "vmess":
            case "vless":
                spec.clientId = UUID.randomUUID().toString();
                spec.transport = StringUtils.defaultIfBlank(dto.getTransport(), "tcp");
                spec.tls = Boolean.TRUE.equals(dto.getTls());
                spec.path = "grpc".equals(spec.transport) ? randomHex(8) : "tcp".equals(spec.transport) ? null : "/" + randomHex(8);
                if (StringUtils.isNotBlank(dto.getServerName())) {
                    spec.serverName = normalizeServerName(dto.getServerName());
                    if (spec.serverName == null) return R.err("请填写有效的域名，不要包含协议或路径");
                } else if (spec.tls) {
                    spec.serverName = DEFAULT_TLS_SERVER_NAME;
                }
                return R.ok();
            case "shadowtls":
                spec.password = password;
                if (password.length() < 8) return R.err("ShadowTLS 密码至少 8 位");
                spec.handshakeServer = camouflageInput(dto.getHandshakeServer());
                if (spec.handshakeServer == null) return R.err("请填写有效的握手网站域名");
                spec.method = "2022-blake3-aes-128-gcm";
                spec.innerPassword = randomKey(16);
                return R.ok();
            default:
                // Trojan、AnyTLS、Hysteria2、TUIC、WireGuard
                spec.password = password;
                if (password.length() < 8) return R.err(protocolLabel(proxyType) + " 密钥至少 8 位");
                if (StringUtils.isNotBlank(dto.getServerName())) {
                    spec.serverName = normalizeServerName(dto.getServerName());
                    if (spec.serverName == null) return R.err("请填写有效的证书域名");
                } else if (!"wireguard".equals(proxyType)) {
                    spec.serverName = DEFAULT_TLS_SERVER_NAME;
                }
                if ("hysteria2".equals(proxyType) && Boolean.TRUE.equals(dto.getObfs())) spec.obfsPassword = randomPassword(16);
                return R.ok();
        }
    }

    // ---------------------------------------------------------------- 伪装站

    /** 选择伪装站时自动从节点测速 */
    static final String AUTO_CAMOUFLAGE = "auto";
    /** 检测伪装站需要的 Agent 版本 */
    static final String MIN_TARGET_PROBE_AGENT_VERSION = "2.63.0";
    /**
     * 自动选择时检测的网站：大型网站、在中国大陆没有被封锁，通常支持 TLS 1.3、X25519 和 HTTP/2。
     * 不放 Google、YouTube 等被封锁的网站：设备发出的连接里带着这个域名，会在国内被直接拦截。
     */
    static final List<String> CAMOUFLAGE_CANDIDATES = List.of(
            "www.microsoft.com", "learn.microsoft.com", "www.bing.com", "www.apple.com", "swdist.apple.com",
            "gateway.icloud.com", "www.amazon.com", "aws.amazon.com", "www.cloudflare.com", "www.nvidia.com",
            "www.amd.com", "www.intel.com", "www.samsung.com", "www.tesla.com", "www.yahoo.com",
            "addons.mozilla.org", "www.mozilla.org", "www.oracle.com", "www.cisco.com", "www.dell.com",
            "www.sony.com", "www.speedtest.net", "www.lovelive-anime.jp", "www.logitech.com");

    /** 留空或 auto 表示自动选择；否则返回规范化后的域名，格式不对返回 null。 */
    private String camouflageInput(String value) {
        String text = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
        if (text.isEmpty() || AUTO_CAMOUFLAGE.equals(text)) return AUTO_CAMOUFLAGE;
        return normalizeServerName(text);
    }

    /**
     * 确定伪装站：自动选择时在节点上检测候选网站，选可用且握手最快的；手动填写的先检测，不可用就拒绝创建。
     * 节点 Agent 不支持检测时，自动选择使用默认网站，手动填写的不检测。
     */
    private R settleCamouflage(Spec spec, Node node, String proxyType) {
        boolean shadowTls = "shadowtls".equals(proxyType);
        if (!shadowTls && !isRealityType(proxyType)) return R.ok();
        String target = shadowTls ? spec.handshakeServer : spec.serverName;
        R chosen = chooseCamouflage(node, target, shadowTls ? DEFAULT_SHADOWTLS_SERVER : DEFAULT_REALITY_SERVER_NAME);
        if (chosen.getCode() != 0) return chosen;
        if (shadowTls) spec.handshakeServer = (String) chosen.getData();
        else spec.serverName = (String) chosen.getData();
        return R.ok();
    }

    /** 返回选定的域名（R.data）。 */
    private R chooseCamouflage(Node node, String target, String fallback) {
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_TARGET_PROBE_AGENT_VERSION)) {
            return R.ok(AUTO_CAMOUFLAGE.equals(target) ? fallback : target);
        }
        boolean auto = AUTO_CAMOUFLAGE.equals(target);
        List<JSONObject> results = probeCamouflage(node, auto ? CAMOUFLAGE_CANDIDATES : List.of(target));
        if (results == null) return R.err("检测伪装站失败：节点没有响应，请稍后再试");
        for (JSONObject result : results) {
            if (result.getBooleanValue("ok")) return R.ok(result.getString("domain"));
        }
        if (auto) return R.err("节点连不上任何候选伪装站，请检查节点的网络或 DNS");
        return R.err("伪装站 " + target + " 在节点上检测不通过（" + describeCamouflageError(results.isEmpty() ? null : results.get(0).getString("error"))
                + "），请换一个，或选择“自动选择”");
    }

    private List<JSONObject> probeCamouflage(Node node, List<String> domains) {
        GostDto result = GostUtil.ProbeRealityTargets(node.getId(), domains);
        if (!gostSuccess(result) || result.getData() == null) return null;
        JSONObject data = JSONObject.parseObject(JSONObject.toJSONString(result.getData()));
        com.alibaba.fastjson.JSONArray array = data == null ? null : data.getJSONArray("results");
        List<JSONObject> rows = new java.util.ArrayList<>();
        for (int i = 0; array != null && i < array.size(); i++) rows.add(array.getJSONObject(i));
        return rows;
    }

    static String describeCamouflageError(String error) {
        String text = StringUtils.removeStart(StringUtils.defaultString(error), "TLS handshake failed: ");
        switch (text) {
            case "DNS lookup failed": return "节点解析不了这个域名";
            case "timeout": return "节点连接超时";
            case "connection refused": return "443 端口拒绝连接";
            case "connection reset": return "连接被重置，可能被拦截";
            case "unreachable": return "节点无法到达这个网站";
            case "does not support TLS 1.3": return "不支持 TLS 1.3";
            case "TLS 1.3 with X25519 refused": return "不支持 TLS 1.3 或 X25519 密钥交换";
            case "certificate does not match the name": return "网站证书与域名不符";
            case "does not support HTTP/2": return "不支持 HTTP/2";
            default: return text.isEmpty() ? "原因未知" : text;
        }
    }

    /** 在节点上检测伪装站，返回每个网站能否使用和握手用时。domains 为空时检测内置候选。 */
    public R camouflageTargets(Long nodeId, List<String> domains) {
        Node node = nodeId == null ? null : nodeMapper.selectById(nodeId);
        if (node == null) return R.err("节点不存在");
        if (!isAdmin() && userQuotaService.checkNodeQuota(JwtUtil.getUserIdFromToken(), node, null).getCode() != 0) {
            return R.err("无权使用该节点");
        }
        if (!WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线，无法检测");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_TARGET_PROBE_AGENT_VERSION)) {
            return R.err("检测伪装站需要节点 Agent " + MIN_TARGET_PROBE_AGENT_VERSION + " 或更高版本，请先升级该节点");
        }
        List<String> list = new java.util.ArrayList<>();
        if (domains != null) {
            for (String domain : domains) {
                String normalized = normalizeServerName(domain);
                if (normalized == null) return R.err("域名格式不正确：" + domain);
                if (!list.contains(normalized)) list.add(normalized);
            }
        }
        if (list.isEmpty()) list.addAll(CAMOUFLAGE_CANDIDATES);
        if (list.size() > 40) return R.err("一次最多检测 40 个网站");
        List<JSONObject> results = probeCamouflage(node, list);
        if (results == null) return R.err("检测失败：节点没有响应，请稍后再试");
        for (JSONObject result : results) {
            if (!result.getBooleanValue("ok")) result.put("reason", describeCamouflageError(result.getString("error")));
        }
        return R.ok(results);
    }

    /** 更换 REALITY 伪装站：保留密钥和 UUID，客户端只需更新 SNI。 */
    private R changeRealityTarget(PrivateProxy proxy, Node node, String requested) {
        Spec spec = specOf(proxy);
        R chosen = chooseCamouflage(node, requested, DEFAULT_REALITY_SERVER_NAME);
        if (chosen.getCode() != 0) return chosen;
        String serverName = (String) chosen.getData();
        if (serverName.equals(spec.serverName)) return R.ok(serverName);
        boolean residentialExit = "residential".equals(proxy.getExitType()) && StringUtils.isNotBlank(proxy.getExitCountry());
        Integer routePort = residentialExit ? openExitRoute(proxy, node, ExitPoolService.interfaceName(proxy.getExitCountry())) : null;
        GostDto runtime = GostUtil.AddRealityRuntime(node.getId(), realityRuntimeName(proxy), serverName,
                "vless_xhttp".equals(proxy.getProxyType()) ? "xhttp" : null, spec.path,
                routePort == null ? null : "127.0.0.1", routePort,
                routePort == null ? null : EXIT_ROUTE_USER, routePort == null ? null : exitRoutePassword(proxy));
        if (!gostSuccess(runtime)) return R.err("更换伪装站失败：" + gostMessage(runtime));
        JSONObject config = storedClientConfig(proxy);
        config.put("serverName", serverName);
        proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxy.getId())
                .set("client_config", crypto.encrypt(JSONObject.toJSONString(config)))
                .set("exit_route_port", proxy.getExitRoutePort())
                .set("updated_time", System.currentTimeMillis()));
        return R.ok(serverName);
    }

    /** 从已保存的代理还原协议设置。 */
    private Spec specOf(PrivateProxy proxy) {
        Spec spec = new Spec();
        JSONObject c = storedClientConfig(proxy);
        String secret = crypto.decryptString(proxy.getAuthPassword());
        String type = proxy.getProxyType();
        switch (type) {
            case "socks5":
            case "http":
            case "mixed":
                spec.username = proxy.getAuthUsername();
                spec.password = secret;
                break;
            case "shadowsocks":
                spec.cipher = proxy.getAuthUsername();
                spec.password = secret;
                break;
            case "vless_reality":
            case "vless_xhttp":
                spec.serverName = c.getString("serverName");
                spec.path = c.getString("path");
                break;
            default:
                spec.password = "vmess".equals(type) || "vless".equals(type) ? "" : StringUtils.defaultIfBlank(c.getString("password"), secret);
                spec.serverName = c.getString("serverName");
                spec.transport = c.getString("transport");
                spec.tls = c.getBooleanValue("tls");
                spec.path = c.getString("path");
                spec.method = c.getString("method");
                spec.obfsPassword = c.getString("obfsPassword");
                spec.handshakeServer = c.getString("handshakeServer");
                spec.innerPassword = c.getString("innerPassword");
                spec.clientId = c.getString("clientId");
        }
        return spec;
    }

    /** 重新生成凭据：密码、密钥、UUID；REALITY 的密钥由 Agent 在重建运行时时生成。 */
    private Spec withNewCredentials(String type, Spec spec) {
        switch (type) {
            case "socks5":
            case "http":
            case "mixed":
            case "shadowsocks":
            case "trojan":
            case "anytls":
            case "hysteria2":
            case "wireguard":
                spec.password = randomPassword(20);
                break;
            case "tuic":
                spec.password = randomPassword(20);
                spec.clientId = UUID.randomUUID().toString();
                break;
            case "shadowsocks2022":
                spec.password = randomKey(ss2022KeySize(spec.method));
                break;
            case "shadowtls":
                spec.password = randomPassword(20);
                spec.innerPassword = randomKey(16);
                break;
            case "vmess":
            case "vless":
                spec.clientId = UUID.randomUUID().toString();
                break;
            default:
                break;
        }
        if (spec.obfsPassword != null) spec.obfsPassword = randomPassword(16);
        return spec;
    }

    private String initialUsername(String proxyType, Spec spec) {
        switch (proxyType) {
            case "socks5":
            case "http":
            case "mixed":
                return spec.username;
            case "shadowsocks":
                return spec.cipher;
            case "shadowsocks2022":
            case "shadowtls":
                return spec.method;
            case "vmess":
            case "vless":
                return spec.clientId;
            default:
                return "待生成";
        }
    }

    /** auth_password 列保存的内容：密码；VMess/VLESS/REALITY 保存 UUID。 */
    private String storedSecret(String proxyType, Spec spec) {
        if ("vmess".equals(proxyType) || "vless".equals(proxyType)) return spec.clientId;
        if ("vless_reality".equals(proxyType) || "vless_xhttp".equals(proxyType)) return UUID.randomUUID().toString();
        return spec.password;
    }

    /**
     * 在节点上建立代理的全部运行时（限速器、白名单、服务或独立运行时、出口通道），返回客户端配置。
     * repair 为 true 时用于修复：已存在的部分原样保留，缺失的重新创建。
     */
    private JSONObject provision(PrivateProxy proxy, Node node, Spec spec, boolean repair) {
        String proxyType = proxy.getProxyType();
        boolean residentialExit = "residential".equals(proxy.getExitType()) && StringUtils.isNotBlank(proxy.getExitCountry());
        String exitInterface = residentialExit ? ExitPoolService.interfaceName(proxy.getExitCountry()) : null;
        List<String> cidrs = parseCidrs(proxy.getAllowedCidrs());
        if (cidrs == null) cidrs = List.of();
        String bindIp = proxy.getBindIp();
        String limiterName = proxy.getGrantedByUserId() != null && supportsGrantRateLimit(proxyType) ? limiterName(proxy) : null;
        if (limiterName != null) {
            GostDto limiter = repair ? GostUtil.UpdateNamedLimiter(node.getId(), limiterName, limiterSpeed(proxy.getSpeedLimitMbps())) : null;
            if (limiter == null || !gostSuccess(limiter)) {
                requireGost(GostUtil.AddNamedLimiter(node.getId(), limiterName, limiterSpeed(proxy.getSpeedLimitMbps())), "创建用户限速器失败");
            }
        }
        if (proxy.getAdmissionName() != null) {
            if (repair) GostUtil.DeleteAdmission(node.getId(), proxy.getAdmissionName());
            requireGost(GostUtil.AddAdmission(node.getId(), proxy.getAdmissionName(), admissionCidrs(cidrs)), "创建 IP 白名单失败");
        }
        String admission = proxy.getAdmissionName();
        JSONObject c = new JSONObject();
        switch (proxyType) {
            case "shadowsocks":
                requireGost(upsert(command -> GostUtil.sendShadowsocksProxy(node.getId(), proxy.getServiceName(), bindIp,
                        proxy.getListenPort(), spec.cipher, spec.password, admission, limiterName, exitInterface, command), repair),
                        "创建 Shadowsocks 失败");
                c.put("cipher", spec.cipher);
                c.put("password", spec.password);
                return c;
            case "socks5":
            case "http":
            case "mixed":
                requireGost(upsert(command -> GostUtil.sendPrivateProxy(node.getId(), proxy.getServiceName(),
                        "mixed".equals(proxyType) ? "auto" : proxyType, bindIp, proxy.getListenPort(), spec.username,
                        spec.password, admission, limiterName, exitInterface, command), repair), "创建代理失败");
                c.put("username", spec.username);
                c.put("password", spec.password);
                return c;
            case "vless_reality":
            case "vless_xhttp": {
                Integer routePort = residentialExit ? openExitRoute(proxy, node, exitInterface) : null;
                String network = "vless_xhttp".equals(proxyType) ? "xhttp" : null;
                GostDto runtime = GostUtil.AddRealityRuntime(node.getId(), realityRuntimeName(proxy), spec.serverName,
                        network, spec.path, routePort == null ? null : "127.0.0.1", routePort,
                        routePort == null ? null : EXIT_ROUTE_USER, routePort == null ? null : exitRoutePassword(proxy));
                requireGost(runtime, "准备 REALITY 运行时失败");
                JSONObject data = JSONObject.parseObject(JSONObject.toJSONString(runtime.getData()));
                Integer runtimePort = data == null ? null : data.getInteger("port");
                String clientId = data == null ? null : data.getString("clientId");
                String publicKey = data == null ? null : data.getString("publicKey");
                String shortId = data == null ? null : data.getString("shortId");
                if (runtimePort == null || StringUtils.isAnyBlank(clientId, publicKey, shortId)) {
                    throw new IllegalStateException("Agent 返回的 REALITY 配置不完整");
                }
                if (network != null && !"xhttp".equals(data.getString("network"))) {
                    throw new IllegalStateException("节点 Agent 不支持 XHTTP，请先升级到 " + MIN_PROTOCOL_AGENT_VERSION + " 或更高版本");
                }
                proxy.setAuthUsername(clientId);
                proxy.setAuthPassword(crypto.encrypt(clientId));
                requireGost(upsert(command -> GostUtil.SendRealityFrontend(node.getId(), proxy.getServiceName(), bindIp,
                        proxy.getListenPort(), runtimePort, admission, limiterName, command), repair), "创建 REALITY 公网入口失败");
                c.put("clientId", clientId);
                c.put("publicKey", publicKey);
                c.put("shortId", shortId);
                c.put("serverName", spec.serverName);
                c.put("fingerprint", "chrome");
                if (network == null) c.put("flow", "xtls-rprx-vision");
                else c.put("path", data.getString("path"));
                c.put("runtimeVersion", data.getString("version"));
                return c;
            }
            default: {
                if (!isAdvancedRuntime(proxyType)) throw new IllegalStateException("不支持的代理类型");
                Integer routePort = residentialExit ? openExitRoute(proxy, node, exitInterface) : null;
                GostDto runtime = GostUtil.AddPrivateProxyRuntime(node.getId(), advancedRuntimeName(proxy), proxyType,
                        bindIp, proxy.getListenPort(), runtimePassword(proxyType, spec), routePort,
                        routePort == null ? null : EXIT_ROUTE_USER, routePort == null ? null : exitRoutePassword(proxy),
                        runtimeOptions(spec, cidrs));
                requireGost(runtime, "创建 " + protocolLabel(proxyType) + " 运行时失败");
                JSONObject data = JSONObject.parseObject(JSONObject.toJSONString(runtime.getData()));
                if (data == null) throw new IllegalStateException("Agent 返回的 " + protocolLabel(proxyType) + " 配置为空");
                return advancedClientConfig(proxy, spec, data);
            }
        }
    }

    /** 独立运行时的客户端配置：创建时的设置加上 Agent 生成的部分（UUID、证书指纹、WireGuard 密钥）。 */
    private JSONObject advancedClientConfig(PrivateProxy proxy, Spec spec, JSONObject data) {
        String proxyType = proxy.getProxyType();
        JSONObject c = new JSONObject();
        if (!"vmess".equals(proxyType) && !"vless".equals(proxyType)) c.put("password", spec.password);
        if (!List.of("wireguard", "shadowsocks2022", "shadowtls").contains(proxyType)) {
            boolean plainTransport = ("vmess".equals(proxyType) || "vless".equals(proxyType)) && !spec.tls;
            String serverName = plainTransport ? spec.serverName
                    : StringUtils.defaultIfBlank(spec.serverName, StringUtils.defaultIfBlank(data.getString("serverName"), DEFAULT_TLS_SERVER_NAME));
            if (StringUtils.isNotBlank(serverName)) c.put("serverName", serverName);
        }
        c.put("runtimeVersion", data.getString("version"));
        if (StringUtils.isNotBlank(data.getString("certSha256"))) {
            c.put("certSha256", data.getString("certSha256"));
            c.put("publicKeySha256", data.getString("publicKeySha256"));
        }
        switch (proxyType) {
            case "tuic":
            case "vmess":
            case "vless": {
                String clientId = data.getString("clientId");
                if (StringUtils.isBlank(clientId)) throw new IllegalStateException("Agent 返回的 " + protocolLabel(proxyType) + " UUID 不完整");
                if (spec.clientId != null && !spec.clientId.equals(clientId) && !"tuic".equals(proxyType)) {
                    throw new IllegalStateException("节点 Agent 版本过低，请先升级到 " + MIN_PROTOCOL_AGENT_VERSION + " 或更高版本");
                }
                proxy.setAuthUsername(clientId);
                c.put("clientId", clientId);
                if (!"tuic".equals(proxyType)) {
                    c.put("transport", StringUtils.defaultIfBlank(data.getString("transport"), spec.transport));
                    c.put("path", StringUtils.defaultIfBlank(data.getString("path"), spec.path));
                    c.put("tls", spec.tls);
                }
                break;
            }
            case "shadowsocks2022":
                c.put("method", spec.method);
                break;
            case "shadowtls":
                c.put("method", spec.method);
                c.put("innerPassword", spec.innerPassword);
                c.put("handshakeServer", spec.handshakeServer);
                break;
            case "hysteria2":
                if (spec.obfsPassword != null) c.put("obfsPassword", spec.obfsPassword);
                break;
            case "wireguard": {
                String privateKey = data.getString("clientPrivateKey");
                String publicKey = data.getString("serverPublicKey");
                String clientAddress = data.getString("clientAddress");
                if (StringUtils.isAnyBlank(privateKey, publicKey, clientAddress)) {
                    throw new IllegalStateException("Agent 返回的 WireGuard 密钥不完整");
                }
                proxy.setAuthUsername("WireGuard");
                c.put("clientPrivateKey", privateKey);
                c.put("serverPublicKey", publicKey);
                c.put("clientAddress", clientAddress);
                break;
            }
            default:
                break;
        }
        return c;
    }

    /** Agent 独立运行时的 password 字段。VMess/VLESS 没有密码，用 UUID 占位以通过校验。 */
    private String runtimePassword(String proxyType, Spec spec) {
        return "vmess".equals(proxyType) || "vless".equals(proxyType) ? StringUtils.defaultString(spec.clientId) : spec.password;
    }

    private JSONObject runtimeOptions(Spec spec, List<String> cidrs) {
        JSONObject options = new JSONObject();
        if (spec.serverName != null) options.put("serverName", spec.serverName);
        if (spec.clientId != null) options.put("clientId", spec.clientId);
        if (spec.transport != null) options.put("transport", spec.transport);
        if (spec.tls) options.put("tls", true);
        if (spec.path != null) options.put("path", spec.path);
        if (spec.method != null) options.put("method", spec.method);
        if (spec.obfsPassword != null) options.put("obfsPassword", spec.obfsPassword);
        if (spec.handshakeServer != null) options.put("handshakeServer", spec.handshakeServer);
        if (spec.innerPassword != null) options.put("innerPassword", spec.innerPassword);
        if (cidrs != null && !cidrs.isEmpty()) options.put("allowedCidrs", cidrs);
        return options;
    }

    /** 创建服务；修复时先尝试更新，节点上没有这个服务时再创建。 */
    private GostDto upsert(java.util.function.Function<String, GostDto> send, boolean repair) {
        if (!repair) return send.apply("AddService");
        GostDto updated = send.apply("UpdateService");
        if (!gostSuccess(updated) && StringUtils.containsIgnoreCase(gostMessage(updated), "not found")) {
            return send.apply("AddService");
        }
        return updated;
    }

    /** gost 白名单额外放行本机，节点自检要从本机连接代理。 */
    static List<String> admissionCidrs(List<String> cidrs) {
        List<String> result = new java.util.ArrayList<>(cidrs);
        if (!result.contains("127.0.0.1/32")) result.add("127.0.0.1/32");
        if (!result.contains("::1/128")) result.add("::1/128");
        return result;
    }

    private R checkAllowedSources(String proxyType, Node node, List<String> cidrs) {
        if (cidrs.isEmpty()) return R.ok();
        if ("wireguard".equals(proxyType)) return R.err("WireGuard 暂不支持来源 IP 白名单，请先留空");
        if (isAdvancedRuntime(proxyType) && !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_PROTOCOL_AGENT_VERSION)) {
            return R.err(protocolLabel(proxyType) + " 的来源 IP 白名单需要节点 Agent " + MIN_PROTOCOL_AGENT_VERSION + " 或更高版本");
        }
        return R.ok();
    }

    private R checkListenPort(Node node, String proxyType, String bindIp, int port) {
        if (Boolean.TRUE.equals(portLedgerService.diagnose(node.getId(), port).get("occupied"))) {
            return R.err("端口已被面板中的其他业务占用");
        }
        List<AgentPortCheckUtil.Check> checks = "shadowsocks".equals(proxyType) || "shadowsocks2022".equals(proxyType)
                ? List.of(new AgentPortCheckUtil.Check("tcp", bindIp, port), new AgentPortCheckUtil.Check("udp", bindIp, port))
                : isUdpAdvancedRuntime(proxyType)
                ? List.of(new AgentPortCheckUtil.Check("udp", bindIp, port))
                : List.of(new AgentPortCheckUtil.Check("tcp", bindIp, port));
        AgentPortCheckUtil.Result result = AgentPortCheckUtil.check(node, checks);
        return result.isAvailable() ? R.ok() : R.err(result.getMessage());
    }

    private JSONObject storedClientConfig(PrivateProxy proxy) {
        if (StringUtils.isBlank(proxy.getClientConfig())) return new JSONObject();
        JSONObject config = JSONObject.parseObject(crypto.decryptString(proxy.getClientConfig()));
        return config == null ? new JSONObject() : config;
    }

    /** 留空返回空字符串；格式不对返回 null。 */
    private String normalizeConnectHost(String value) {
        String host = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return "";
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (validIp(host)) return host;
        return normalizeServerName(host);
    }

    static int ss2022KeySize(String method) {
        return "2022-blake3-aes-128-gcm".equals(method) ? 16 : 32;
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    static String randomKey(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return java.util.HexFormat.of().formatHex(value);
    }

    static String randomPassword(int length) {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return value.toString();
    }

    /**
     * 修改已有代理的出口。改回“节点自身 IP”即可随时停用家宽出口。
     */
    @Transactional(rollbackFor = Exception.class)
    public R updateExit(Long id, String exitType, String country) {
        if (!isAdmin()) return R.err("只有管理员可以修改代理出口");
        PrivateProxy proxy = id == null ? null : proxyMapper.selectById(id);
        if (proxy == null || "deleted".equals(proxy.getState())) return R.err("代理不存在");
        boolean residentialExit = "residential".equals(exitType);
        if (!residentialExit && !"node".equals(exitType)) return R.err("不支持的出口类型");
        String exitCountry = residentialExit ? ExitPoolService.normalizeCountry(country) : null;
        if (Objects.equals(StringUtils.defaultIfBlank(proxy.getExitType(), "node"), exitType)
                && Objects.equals(proxy.getExitCountry(), exitCountry)) {
            return R.ok(view(proxy));
        }
        if (!ExitPoolService.SUPPORTED_PROXY_TYPES.contains(proxy.getProxyType())) {
            return R.err("家宽出口暂不支持 WireGuard");
        }
        if (!"active".equals(proxy.getState())) return R.err("只有运行中的代理可以修改出口，请先恢复代理");
        Node node = nodeMapper.selectById(proxy.getNodeId());
        if (node == null || !WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线，无法修改出口");
        if (residentialExit) {
            R exitCheck = checkResidentialExit(proxy.getProxyType(), node, exitCountry);
            if (exitCheck.getCode() != 0) return exitCheck;
        }
        String exitInterface = residentialExit ? ExitPoolService.interfaceName(exitCountry) : null;
        String limiterName = proxy.getGrantedByUserId() != null && supportsGrantRateLimit(proxy.getProxyType()) ? limiterName(proxy) : null;
        String password;
        try {
            password = crypto.decryptString(proxy.getAuthPassword());
        } catch (Exception e) {
            return R.err("代理密码解密失败，无法修改出口");
        }
        if (ExitPoolService.ROUTED_PROXY_TYPES.contains(proxy.getProxyType())) {
            if (!AgentVersionUtil.isAtLeast(node.getVersion(), ExitPoolService.MIN_ROUTED_AGENT_VERSION)) {
                return R.err("修改该协议的出口需要节点 Agent " + ExitPoolService.MIN_ROUTED_AGENT_VERSION + " 或更高版本，请先升级该节点");
            }
            try {
                rerouteRuntime(proxy, node, exitInterface);
            } catch (RuntimeException e) {
                return R.err("修改出口失败：" + e.getMessage());
            }
            proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxy.getId())
                    .set("exit_type", exitType).set("exit_country", exitCountry)
                    .set("exit_route_port", proxy.getExitRoutePort())
                    .set("updated_time", System.currentTimeMillis()));
            reconcileExitsAfterCommit();
            return R.ok(view(proxyMapper.selectById(proxy.getId())));
        }
        GostDto result = "shadowsocks".equals(proxy.getProxyType())
                ? GostUtil.sendShadowsocksProxy(node.getId(), proxy.getServiceName(), proxy.getBindIp(), proxy.getListenPort(),
                proxy.getAuthUsername(), password, proxy.getAdmissionName(), limiterName, exitInterface, "UpdateService")
                : GostUtil.sendPrivateProxy(node.getId(), proxy.getServiceName(), gostHandlerType(proxy.getProxyType()),
                proxy.getBindIp(), proxy.getListenPort(), proxy.getAuthUsername(), password, proxy.getAdmissionName(),
                limiterName, exitInterface, "UpdateService");
        if (!gostSuccess(result)) return R.err("修改出口失败：" + gostMessage(result));
        proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxy.getId())
                .set("exit_type", exitType).set("exit_country", exitCountry)
                .set("updated_time", System.currentTimeMillis()));
        reconcileExitsAfterCommit();
        return R.ok(view(proxyMapper.selectById(proxy.getId())));
    }

    private static final String EXIT_ROUTE_USER = "exit";

    private String exitRouteName(PrivateProxy proxy) {
        return proxy.getServiceName() + "-exit";
    }

    /** 出口通道只监听本机，凭据的作用是防止节点上的其他程序误用它。 */
    private String exitRoutePassword(PrivateProxy proxy) {
        return StringUtils.removeStart(proxy.getServiceName(), "private-proxy-");
    }

    /** 在节点本机创建（或改向）出口通道，返回它的端口。 */
    private Integer openExitRoute(PrivateProxy proxy, Node node, String exitInterface) {
        if (proxy.getExitRoutePort() != null) {
            GostDto updated = GostUtil.sendExitRoute(node.getId(), exitRouteName(proxy), proxy.getExitRoutePort(),
                    EXIT_ROUTE_USER, exitRoutePassword(proxy), exitInterface, "UpdateService");
            if (gostSuccess(updated)) return proxy.getExitRoutePort();
            if (!StringUtils.containsIgnoreCase(gostMessage(updated), "not found")) {
                throw new IllegalStateException("更新出口通道失败：" + gostMessage(updated));
            }
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            int port = 45000 + (int) ((proxy.getId() * 131L + attempt * 977L) % 15000L);
            Number taken = proxyMapper.selectCount(new QueryWrapper<PrivateProxy>().eq("node_id", node.getId())
                    .eq("exit_route_port", port).ne("state", "deleted").ne("id", proxy.getId()));
            if (taken != null && taken.longValue() > 0) continue;
            if (!AgentPortCheckUtil.check(node, List.of(new AgentPortCheckUtil.Check("tcp", "127.0.0.1", port))).isAvailable()) continue;
            requireGost(GostUtil.sendExitRoute(node.getId(), exitRouteName(proxy), port, EXIT_ROUTE_USER,
                    exitRoutePassword(proxy), exitInterface, "AddService"), "创建出口通道失败");
            proxy.setExitRoutePort(port);
            return port;
        }
        throw new IllegalStateException("节点上没有可用的本机端口用于出口通道");
    }

    /** 改变独立运行时的出站方式：经出口通道，或直接从节点发出。密钥和客户端配置保持不变。 */
    private void rerouteRuntime(PrivateProxy proxy, Node node, String exitInterface) {
        Integer routePort = exitInterface == null ? null : openExitRoute(proxy, node, exitInterface);
        String routeUser = routePort == null ? null : EXIT_ROUTE_USER;
        String routePassword = routePort == null ? null : exitRoutePassword(proxy);
        Spec spec;
        try {
            spec = specOf(proxy);
        } catch (Exception e) {
            throw new IllegalStateException("读取代理配置失败");
        }
        if (isRealityType(proxy.getProxyType())) {
            requireGost(GostUtil.AddRealityRuntime(node.getId(), realityRuntimeName(proxy), spec.serverName,
                    "vless_xhttp".equals(proxy.getProxyType()) ? "xhttp" : null, spec.path,
                    routePort == null ? null : "127.0.0.1", routePort, routeUser, routePassword), "更新 REALITY 运行时失败");
        } else {
            List<String> cidrs = parseCidrs(proxy.getAllowedCidrs());
            requireGost(GostUtil.AddPrivateProxyRuntime(node.getId(), advancedRuntimeName(proxy), proxy.getProxyType(),
                    proxy.getBindIp(), proxy.getListenPort(), runtimePassword(proxy.getProxyType(), spec), routePort,
                    routeUser, routePassword, runtimeOptions(spec, cidrs == null ? List.of() : cidrs)),
                    "更新 " + protocolLabel(proxy.getProxyType()) + " 运行时失败");
        }
        if (routePort == null && proxy.getExitRoutePort() != null) {
            GostUtil.DeleteNamedService(node.getId(), exitRouteName(proxy));
            proxy.setExitRoutePort(null);
        }
    }

    /** 远程切换链接使用的令牌。只有管理员可以查看和重置。 */
    public R exitSwitchToken(Long id, boolean reset) {
        PrivateProxy proxy = id == null ? null : owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        if (!"residential".equals(proxy.getExitType())) return R.err("这个代理没有使用家宽出口");
        if (reset || StringUtils.isBlank(proxy.getExitSwitchToken())) {
            byte[] bytes = new byte[24];
            new java.security.SecureRandom().nextBytes(bytes);
            proxy.setExitSwitchToken(java.util.HexFormat.of().formatHex(bytes));
            proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxy.getId())
                    .set("exit_switch_token", proxy.getExitSwitchToken()));
        }
        return R.ok(Map.of("token", proxy.getExitSwitchToken()));
    }

    /** 代理的使用者自己换一个出口。 */
    public R switchOwnExit(Long id) {
        PrivateProxy proxy = id == null ? null : owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        if (!"residential".equals(proxy.getExitType()) || !"active".equals(proxy.getState())) {
            return R.err("这个代理当前没有使用家宽出口");
        }
        Long groupId = exitPoolService.groupId(proxy.getNodeId(), proxy.getExitCountry());
        if (groupId == null) return R.err("出口还没有准备好，请稍后再试");
        return exitPoolService.switchExit(groupId, null, isAdmin() ? "手动切换" : "用户切换");
    }

    /** 凭令牌换一个出口，供不登录面板时使用。 */
    public R switchExitByToken(String token) {
        if (StringUtils.isBlank(token) || !token.matches("[0-9a-f]{48}")) return R.err("链接无效");
        PrivateProxy proxy = proxyMapper.selectOne(new QueryWrapper<PrivateProxy>().eq("exit_switch_token", token)
                .ne("state", "deleted").last("LIMIT 1"));
        if (proxy == null) return R.err("链接无效或已重置");
        if (!"residential".equals(proxy.getExitType()) || !"active".equals(proxy.getState())) {
            return R.err("这个代理当前没有使用家宽出口");
        }
        Long groupId = exitPoolService.groupId(proxy.getNodeId(), proxy.getExitCountry());
        if (groupId == null) return R.err("出口还没有准备好，请稍后再试");
        return exitPoolService.switchExit(groupId, null, "远程切换");
    }

    private R checkResidentialExit(String proxyType, Node node, String exitCountry) {
        if (!isAdmin()) return R.err("家宽出口目前仅管理员可以配置");
        if (StringUtils.isBlank(exitCountry)) return R.err("请选择出口国家");
        if (!ExitPoolService.SUPPORTED_PROXY_TYPES.contains(proxyType)) {
            return R.err("家宽出口暂不支持 WireGuard");
        }
        String minimum = ExitPoolService.minAgentVersion(proxyType);
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), minimum)) {
            return R.err("该协议使用家宽出口需要节点 Agent " + minimum + " 或更高版本，请先升级该节点");
        }
        if (!exitPoolService.hasCandidates(exitCountry)) return R.err("该国家当前没有可用的家宽出口");
        return R.ok();
    }

    /** 事务提交后再触发出口调度，保证调度线程能读到刚保存的代理。 */
    private void reconcileExitsAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            exitPoolService.requestReconcile();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                exitPoolService.requestReconcile();
            }
        });
    }

    public R list() {
        QueryWrapper<PrivateProxy> query = new QueryWrapper<PrivateProxy>().ne("state", "deleted").orderByDesc("created_time");
        excludeMissingNodes(query);
        if (!isAdmin()) query.eq("user_id", JwtUtil.getUserIdFromToken());
        return R.ok(proxyMapper.selectList(query).stream().map(this::view).collect(Collectors.toList()));
    }

    public R grants(Integer userId) {
        if (!isAdmin()) return R.err("只有管理员可以查看用户代理授权");
        QueryWrapper<PrivateProxy> query = new QueryWrapper<PrivateProxy>()
                .isNotNull("granted_by_user_id").ne("state", "deleted").orderByDesc("created_time");
        excludeMissingNodes(query);
        if (userId != null) query.eq("user_id", userId);
        return R.ok(proxyMapper.selectList(query).stream().map(this::view).collect(Collectors.toList()));
    }

    @Transactional(rollbackFor = Exception.class)
    public R updateGrant(PrivateProxyGrantUpdateDto dto) {
        if (!isAdmin()) return R.err("只有管理员可以修改代理授权");
        PrivateProxy proxy = proxyMapper.selectById(dto.getId());
        if (proxy == null || proxy.getGrantedByUserId() == null || "deleted".equals(proxy.getState())) {
            return R.err("代理授权不存在");
        }
        if (!Boolean.TRUE.equals(dto.getPermanent())
                && (dto.getExpiresAt() == null || dto.getExpiresAt() <= System.currentTimeMillis())) {
            return R.err("请选择未来的到期时间");
        }
        if (!Boolean.TRUE.equals(dto.getFlowUnlimited()) && value(dto.getFlowLimit()) <= 0L) {
            return R.err("流量额度必须大于 0");
        }
        boolean rateLimitSupported = supportsGrantRateLimit(proxy.getProxyType());
        Integer previousSpeedLimit = proxy.getSpeedLimitMbps();
        proxy.setFlowLimit(dto.getFlowLimit());
        proxy.setFlowUnlimited(Boolean.TRUE.equals(dto.getFlowUnlimited()) ? 1 : 0);
        proxy.setFlowResetDay(dto.getFlowResetDay());
        proxy.setExpiresAt(Boolean.TRUE.equals(dto.getPermanent()) ? null : dto.getExpiresAt());
        proxy.setSpeedLimitMbps(rateLimitSupported ? dto.getSpeedLimitMbps() : null);
        proxy.setUpdatedTime(System.currentTimeMillis());
        Node node = nodeMapper.selectById(proxy.getNodeId());
        if (rateLimitSupported && !Objects.equals(previousSpeedLimit, proxy.getSpeedLimitMbps())
                && (node == null || !WebSocketServer.isNodeOnline(node.getId()))) {
            return R.err("节点离线，无法修改代理限速；流量和到期设置未保存");
        }
        if (node != null && WebSocketServer.isNodeOnline(node.getId()) && rateLimitSupported) {
            GostDto limiter = GostUtil.UpdateNamedLimiter(node.getId(), limiterName(proxy), limiterSpeed(proxy.getSpeedLimitMbps()));
            if (!gostSuccess(limiter) && StringUtils.containsIgnoreCase(gostMessage(limiter), "not found")) {
                limiter = GostUtil.AddNamedLimiter(node.getId(), limiterName(proxy), limiterSpeed(proxy.getSpeedLimitMbps()));
            }
            if (!gostSuccess(limiter)) return R.err("更新用户限速失败：" + gostMessage(limiter));
        }
        proxyMapper.updateById(proxy);
        reconcileGrantedProxy(proxy, System.currentTimeMillis());
        return R.ok(view(proxyMapper.selectById(proxy.getId())));
    }

    @Transactional(rollbackFor = Exception.class)
    public R resetGrantFlow(Long id) {
        if (!isAdmin()) return R.err("只有管理员可以重置代理流量");
        PrivateProxy proxy = proxyMapper.selectById(id);
        if (proxy == null || proxy.getGrantedByUserId() == null || "deleted".equals(proxy.getState())) {
            return R.err("代理授权不存在");
        }
        resetFlow(proxy, System.currentTimeMillis());
        return R.ok(view(proxyMapper.selectById(id)));
    }

    public R clientConfig(Long id) {
        PrivateProxy proxy = owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        Node node = nodeMapper.selectById(proxy.getNodeId());
        String host = clientHost(proxy, node);
        if (StringUtils.isBlank(host)) return R.err("节点未配置公网地址");
        try {
            JSONObject config = clientConfigOf(proxy);
            if (config == null) return R.err("该代理没有可用的连接信息");
            String type = proxy.getProxyType();
            config.put("proxyType", type);
            config.put("name", proxy.getName());
            config.put("host", host);
            config.put("port", proxy.getListenPort());
            String uri = PrivateProxyClientFormats.uri(type, proxy.getName(), host, proxy.getListenPort(), config);
            if (uri != null) config.put("uri", uri);
            config.put("linkSkipsCertCheck", PrivateProxyClientFormats.uriSkipsCertificateCheck(type, config));
            Map<String, Object> clash = PrivateProxyClientFormats.clash(type, proxy.getName(), host, proxy.getListenPort(), config);
            if (clash != null) config.put("clash", PrivateProxySubscriptions.yaml(List.of(clash)).trim());
            return R.ok(config);
        } catch (Exception e) {
            return R.err("连接信息解密失败");
        }
    }

    /** 客户端连接用的地址：代理设置的连接地址，否则为节点的公网地址。 */
    String clientHost(PrivateProxy proxy, Node node) {
        if (StringUtils.isNotBlank(proxy.getConnectHost())) return proxy.getConnectHost();
        return node == null ? null : StringUtils.defaultIfBlank(node.getServerIp(), node.getIp());
    }

    /** 解密后的客户端配置；早期版本的 SOCKS5/HTTP/Shadowsocks 没有保存，从账号密码还原。 */
    JSONObject clientConfigOf(PrivateProxy proxy) {
        if (StringUtils.isNotBlank(proxy.getClientConfig())) {
            return JSONObject.parseObject(crypto.decryptString(proxy.getClientConfig()));
        }
        String type = proxy.getProxyType();
        JSONObject config = new JSONObject();
        if ("socks5".equals(type) || "http".equals(type) || "mixed".equals(type)) {
            config.put("username", proxy.getAuthUsername());
            config.put("password", crypto.decryptString(proxy.getAuthPassword()));
            return config;
        }
        if ("shadowsocks".equals(type)) {
            config.put("cipher", proxy.getAuthUsername());
            config.put("password", crypto.decryptString(proxy.getAuthPassword()));
            return config;
        }
        return null;
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
        recordHourlyTraffic(proxy.getId(), inbound, outbound);
        if (proxy.getGrantedByUserId() != null) {
            long used = value(proxy.getInFlow()) + value(proxy.getOutFlow()) + inbound + outbound;
            if (!Objects.equals(proxy.getFlowUnlimited(), 1)
                    && used >= value(proxy.getFlowLimit()) * BYTES_PER_GB) {
                GostDto paused = pauseRuntime(proxy);
                if (gostSuccess(paused)) updateState(proxy, "quota_exhausted", "代理流量额度已用尽");
                else updateState(proxy, proxy.getState(), "代理流量额度已用尽；自动暂停失败：" + gostMessage(paused));
            }
            return;
        }
        userQuotaService.recordPrivateProxyFlow(proxy, inbound, outbound);
        enforceUserQuota(proxy);
    }

    /** 用户自己创建的代理超出节点额度时自动暂停。 */
    private void enforceUserQuota(PrivateProxy proxy) {
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
        if (!canManage(proxy)) return R.err("该代理由管理员授权，请联系管理员调整");
        if (!"active".equals(proxy.getState())) return R.err("只有运行中的代理可以暂停");
        GostDto result = pauseRuntime(proxy);
        if (!gostSuccess(result)) return R.err(gostMessage(result));
        updateState(proxy, "paused", null);
        return R.ok();
    }

    public R resume(Long id) {
        PrivateProxy proxy = owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        if (!canManage(proxy)) return R.err("该代理由管理员授权，请联系管理员调整");
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
        if (!canManage(proxy)) return R.err("该代理由管理员授权，请联系管理员调整");
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

    public void deleteForUser(Integer userId) {
        List<PrivateProxy> proxies = proxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .eq("user_id", userId).ne("state", "deleted"));
        for (PrivateProxy proxy : proxies) {
            Node node = nodeMapper.selectById(proxy.getNodeId());
            if (node != null && WebSocketServer.isNodeOnline(node.getId()) && cleanupRuntime(proxy, node)) {
                updateState(proxy, "deleted", null);
            } else {
                updateState(proxy, "delete_pending", "用户已删除，等待节点上线清理");
            }
        }
    }

    /**
     * 删除节点时同步处理该节点创建的所有私人代理。
     *
     * 在线节点必须先确认 Agent 已经删除运行时；否则中止节点删除，避免
     * 面板记录消失但 Agent 端口仍然继续监听。离线节点无法回收远端运行时，
     * 但节点本身即将从面板删除，代理记录必须同步标记为 deleted，避免形成
     * 永久显示“节点已删除”的孤儿代理。
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteForNode(Long nodeId) {
        List<PrivateProxy> proxies = proxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .eq("node_id", nodeId).ne("state", "deleted"));
        if (proxies.isEmpty()) return 0;

        Node node = nodeMapper.selectById(nodeId);
        boolean online = node != null && WebSocketServer.isNodeOnline(nodeId);
        int deleted = 0;
        for (PrivateProxy proxy : proxies) {
            if (online && !cleanupRuntime(proxy, node)) {
                throw new IllegalStateException("节点还有私人代理未清理成功，请先处理代理 "
                        + proxy.getName() + " 后再删除节点");
            }
            updateState(proxy, "deleted", online ? null : "所属节点已删除，跳过离线 Agent 清理");
            deleted++;
        }
        return deleted;
    }

    @Scheduled(initialDelay = 30_000L, fixedDelay = 30_000L)
    public void reconcileExpiryAndCleanup() {
        long now = System.currentTimeMillis();
        synchronizeAdvancedGrantTraffic();

        // 节点被历史版本删除后，旧代理记录没有机会进入普通到期清理队列。
        // 先收敛这些孤儿记录，列表接口也会同步排除它们。
        List<PrivateProxy> orphaned = proxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .ne("state", "deleted")
                .notExists("SELECT 1 FROM node n WHERE n.id = private_proxy.node_id"));
        for (PrivateProxy proxy : orphaned) {
            updateState(proxy, "deleted", "所属节点已删除，自动清理孤儿代理记录");
        }

        List<PrivateProxy> granted = proxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .isNotNull("granted_by_user_id").notIn("state", "deleted", "delete_pending", "error"));
        for (PrivateProxy proxy : granted) reconcileGrantedProxy(proxy, now);

        List<PrivateProxy> pending = proxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .and(q -> q.eq("state", "delete_pending")
                        .or(n -> n.isNull("granted_by_user_id").in("state", "active", "paused")
                                .isNotNull("expires_at").le("expires_at", now))));
        for (PrivateProxy proxy : pending) {
            boolean explicitDelete = "delete_pending".equals(proxy.getState());
            Node node = nodeMapper.selectById(proxy.getNodeId());
            if (node == null || !WebSocketServer.isNodeOnline(proxy.getNodeId())) {
                if (!"delete_pending".equals(proxy.getState())) updateState(proxy, "delete_pending", "代理已到期，等待节点上线清理");
                continue;
            }
            if (cleanupRuntime(proxy, node)) updateState(proxy, explicitDelete ? "deleted" : "expired", null);
        }
    }

    // ---------------------------------------------------------------- 修改、续期、重置密钥

    @Transactional(rollbackFor = Exception.class)
    public R update(PrivateProxyUpdateDto dto) {
        PrivateProxy proxy = owned(dto.getId());
        if (proxy == null) return R.err("代理不存在或无权访问");
        boolean manage = canManage(proxy);
        long now = System.currentTimeMillis();
        UpdateWrapper<PrivateProxy> simple = new UpdateWrapper<PrivateProxy>().eq("id", proxy.getId());
        boolean simpleChange = false;
        if (dto.getName() != null) {
            String name = dto.getName().trim();
            if (name.isEmpty()) return R.err("请填写代理名称");
            if (!name.equals(proxy.getName())) {
                simple.set("name", name);
                proxy.setName(name);
                simpleChange = true;
            }
        }
        if (dto.getConnectHost() != null) {
            String host = normalizeConnectHost(dto.getConnectHost());
            if (host == null) return R.err("连接地址格式不正确，请填写域名或 IP");
            String value = host.isEmpty() ? null : host;
            if (!Objects.equals(value, proxy.getConnectHost())) {
                simple.set("connect_host", value);
                proxy.setConnectHost(value);
                simpleChange = true;
            }
        }
        if (dto.getRenewHours() != null || Boolean.TRUE.equals(dto.getPermanent())) {
            if (proxy.getGrantedByUserId() != null) return R.err("授权代理的到期时间请在“用户授权”里调整");
            if (!manage) return R.err("该代理由管理员授权，请联系管理员调整");
            if (!"active".equals(proxy.getState()) && !"paused".equals(proxy.getState())) {
                return R.err("只有运行中或暂停的代理可以续期");
            }
            Long expiresAt = Boolean.TRUE.equals(dto.getPermanent()) ? null
                    : Math.max(now, proxy.getExpiresAt() == null ? now : proxy.getExpiresAt()) + dto.getRenewHours() * 3_600_000L;
            simple.set("expires_at", expiresAt);
            proxy.setExpiresAt(expiresAt);
            simpleChange = true;
        }

        Integer newPort = dto.getListenPort() == null || dto.getListenPort().equals(proxy.getListenPort()) ? null : dto.getListenPort();
        List<String> newCidrs = null;
        if (dto.getAllowedCidrs() != null) {
            newCidrs = parseCidrs(dto.getAllowedCidrs());
            if (newCidrs == null) return R.err("IP 白名单格式不正确，请使用 CIDR 并以逗号分隔");
            List<String> current = parseCidrs(proxy.getAllowedCidrs());
            if (Objects.equals(newCidrs, current == null ? List.of() : current)) newCidrs = null;
        }
        boolean reset = Boolean.TRUE.equals(dto.getResetCredentials());
        String realityTarget = isRealityType(proxy.getProxyType()) && StringUtils.isNotBlank(dto.getRealityServerName())
                ? camouflageInput(dto.getRealityServerName()) : null;
        String handshakeTarget = "shadowtls".equals(proxy.getProxyType()) && StringUtils.isNotBlank(dto.getHandshakeServer())
                ? camouflageInput(dto.getHandshakeServer()) : null;
        if (StringUtils.isNotBlank(dto.getRealityServerName()) && isRealityType(proxy.getProxyType()) && realityTarget == null
                || StringUtils.isNotBlank(dto.getHandshakeServer()) && "shadowtls".equals(proxy.getProxyType()) && handshakeTarget == null) {
            return R.err("伪装站域名格式不正确，不要包含协议或路径");
        }
        JSONObject storedConfig = storedClientConfig(proxy);
        if (realityTarget != null && realityTarget.equals(storedConfig.getString("serverName"))) realityTarget = null;
        if (handshakeTarget != null && handshakeTarget.equals(storedConfig.getString("handshakeServer"))) handshakeTarget = null;
        if ((newPort != null || newCidrs != null) && !manage) return R.err("该代理由管理员授权，端口和白名单请联系管理员调整");
        if (newPort == null && newCidrs == null && !reset && realityTarget == null && handshakeTarget == null) {
            if (simpleChange) {
                simple.set("updated_time", now);
                proxyMapper.update(null, simple);
            }
            return R.ok(view(proxyMapper.selectById(proxy.getId())));
        }

        if (!"active".equals(proxy.getState())) return R.err("只有运行中的代理可以修改端口、白名单、伪装站或重置密钥");
        Node node = nodeMapper.selectById(proxy.getNodeId());
        if (node == null || !WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线，无法修改代理");
        if ((realityTarget != null || handshakeTarget != null)
                && !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_TARGET_PROBE_AGENT_VERSION)) {
            return R.err("更换伪装站需要节点 Agent " + MIN_TARGET_PROBE_AGENT_VERSION + " 或更高版本，请先升级该节点");
        }
        if (realityTarget != null) {
            if (simpleChange) {
                simple.set("updated_time", now);
                proxyMapper.update(null, simple);
                simpleChange = false;
            }
            R changed = changeRealityTarget(proxy, node, realityTarget);
            if (changed.getCode() != 0) return changed;
            proxy = proxyMapper.selectById(proxy.getId());
            if (newPort == null && newCidrs == null && !reset) {
                checkAfterCommit(proxy.getId());
                return R.ok(view(proxy));
            }
        }
        if (handshakeTarget != null) {
            R chosen = chooseCamouflage(node, handshakeTarget, DEFAULT_SHADOWTLS_SERVER);
            if (chosen.getCode() != 0) return chosen;
            handshakeTarget = (String) chosen.getData();
        }
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_PROTOCOL_AGENT_VERSION)) {
            return R.err("修改端口、白名单或重置密钥需要节点 Agent " + MIN_PROTOCOL_AGENT_VERSION + " 或更高版本，请先升级该节点");
        }
        if (newCidrs != null) {
            R cidrCheck = checkAllowedSources(proxy.getProxyType(), node, newCidrs);
            if (cidrCheck.getCode() != 0) return cidrCheck;
        }
        if (newPort != null) {
            allocationLockMapper.lockForUpdate();
            if (node.getPortSta() != null && newPort < node.getPortSta() || node.getPortEnd() != null && newPort > node.getPortEnd()) {
                return R.err("监听端口不在节点允许范围内");
            }
            R portCheck = checkListenPort(node, proxy.getProxyType(), proxy.getBindIp(), newPort);
            if (portCheck.getCode() != 0) return portCheck;
        }
        if (simpleChange) {
            simple.set("updated_time", now);
            proxyMapper.update(null, simple);
        }
        Spec previous;
        try {
            previous = specOf(proxy);
        } catch (Exception e) {
            return R.err("读取代理配置失败，无法修改");
        }
        Spec next = specOf(proxy);
        if (reset) withNewCredentials(proxy.getProxyType(), next);
        if (handshakeTarget != null) next.handshakeServer = handshakeTarget;
        return reprovision(proxy, node, previous, next, newPort, newCidrs);
    }

    /**
     * 按新设置重建代理：先清掉节点上的旧配置，再创建新的；失败时按原设置恢复。
     * 修改端口会重新生成证书，REALITY 会重新生成密钥，客户端需要重新导入。
     */
    private R reprovision(PrivateProxy proxy, Node node, Spec previous, Spec next, Integer newPort, List<String> newCidrs) {
        int oldPort = proxy.getListenPort();
        String oldCidrs = proxy.getAllowedCidrs();
        String oldAdmission = proxy.getAdmissionName();
        if (!cleanupRuntime(proxy, node)) return R.err("清理节点上的旧配置失败，代理保持不变，请稍后重试");
        if (newPort != null) proxy.setListenPort(newPort);
        if (newCidrs != null) {
            proxy.setAllowedCidrs(String.join(",", newCidrs));
            proxy.setAdmissionName(newCidrs.isEmpty() ? null
                    : StringUtils.defaultIfBlank(oldAdmission, "private-proxy-admission-" + StringUtils.removeStart(proxy.getServiceName(), "private-proxy-")));
        }
        try {
            saveProvisioned(proxy, provision(proxy, node, next, false), next);
            reconcileExitsAfterCommit();
            checkAfterCommit(proxy.getId());
            return R.ok(view(proxyMapper.selectById(proxy.getId())));
        } catch (RuntimeException e) {
            cleanupRuntime(proxy, node);
            proxy.setListenPort(oldPort);
            proxy.setAllowedCidrs(oldCidrs);
            proxy.setAdmissionName(oldAdmission);
            try {
                saveProvisioned(proxy, provision(proxy, node, previous, false), previous);
                return R.err("修改失败，已恢复原来的设置：" + e.getMessage()
                        + (isRealityType(proxy.getProxyType()) || isAdvancedRuntime(proxy.getProxyType()) ? "。连接信息可能已变化，请重新导入或更新订阅" : ""));
            } catch (RuntimeException restoreError) {
                cleanupRuntime(proxy, node);
                proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxy.getId())
                        .set("state", "error").set("last_error", StringUtils.left("修改失败且无法恢复：" + e.getMessage(), 480))
                        .set("listen_port", oldPort).set("allowed_cidrs", StringUtils.defaultString(oldCidrs))
                        .set("admission_name", oldAdmission).set("updated_time", System.currentTimeMillis()));
                return R.err("修改失败且无法恢复原设置：" + e.getMessage() + "；请删除后重新创建");
            }
        }
    }

    private void saveProvisioned(PrivateProxy proxy, JSONObject clientConfig, Spec spec) {
        String secret = isRealityType(proxy.getProxyType()) ? clientConfig.getString("clientId")
                : storedSecret(proxy.getProxyType(), spec);
        if ("vmess".equals(proxy.getProxyType()) || "vless".equals(proxy.getProxyType())) secret = clientConfig.getString("clientId");
        proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxy.getId())
                .set("listen_port", proxy.getListenPort())
                .set("allowed_cidrs", StringUtils.defaultString(proxy.getAllowedCidrs()))
                .set("admission_name", proxy.getAdmissionName())
                .set("auth_username", proxy.getAuthUsername())
                .set("auth_password", crypto.encrypt(secret))
                .set("client_config", crypto.encrypt(JSONObject.toJSONString(clientConfig)))
                .set("exit_route_port", proxy.getExitRoutePort())
                .set("state", "active").set("last_error", null)
                .set("check_failures", 0).set("last_check_at", null).set("last_check_ok", null)
                .set("updated_time", System.currentTimeMillis()));
    }

    // ---------------------------------------------------------------- 自检与自动修复

    /** 手动自检：节点运行一次这个代理的客户端，经代理打开测试页面。 */
    public R test(Long id) {
        PrivateProxy proxy = id == null ? null : owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        if (!"active".equals(proxy.getState())) return R.err("只有运行中的代理可以自检");
        Node node = nodeMapper.selectById(proxy.getNodeId());
        if (node == null || !WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线，无法自检");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), MIN_PROTOCOL_AGENT_VERSION)) {
            return R.err("自检需要节点 Agent " + MIN_PROTOCOL_AGENT_VERSION + " 或更高版本，请先升级该节点");
        }
        CheckResult result = runCheck(proxy, node);
        recordCheck(proxy.getId(), result, true);
        return R.ok(result.view());
    }

    static final class CheckResult {
        final boolean ok;
        final String ip;
        final String location;
        final Integer latencyMs;
        final String error;
        final long checkedAt = System.currentTimeMillis();

        CheckResult(boolean ok, String ip, String location, Integer latencyMs, String error) {
            this.ok = ok;
            this.ip = ip;
            this.location = location;
            this.latencyMs = latencyMs;
            this.error = error;
        }

        Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("ok", ok);
            view.put("ip", ip);
            view.put("location", location);
            view.put("latencyMs", latencyMs);
            view.put("error", error);
            view.put("checkedAt", checkedAt);
            return view;
        }
    }

    /** 自检请求：客户端连到代理在节点上的监听地址。 */
    JSONObject probeRequest(PrivateProxy proxy) {
        JSONObject config = clientConfigOf(proxy);
        if (config == null) throw new IllegalStateException("该代理没有可用的连接信息");
        String host = StringUtils.isBlank(proxy.getBindIp()) || "0.0.0.0".equals(proxy.getBindIp()) || "::".equals(proxy.getBindIp())
                ? "127.0.0.1" : proxy.getBindIp();
        JSONObject request = new JSONObject();
        if ("vless_xhttp".equals(proxy.getProxyType())) {
            request.put("engine", "xray");
            request.put("outbounds", List.of(PrivateProxyClientFormats.xrayXhttpOutbound("proxy", host, proxy.getListenPort(), config)));
            return request;
        }
        PrivateProxyClientFormats.SingBoxClient client = PrivateProxyClientFormats.singBox(
                proxy.getProxyType(), "proxy", host, proxy.getListenPort(), config);
        if (client == null) throw new IllegalStateException("该协议暂不支持自检");
        request.put("outbounds", client.outbounds);
        if (!client.endpoints.isEmpty()) request.put("endpoints", client.endpoints);
        return request;
    }

    /** 同一个代理的自检排队进行：WireGuard 的同一把密钥同时只能有一个连接端点。 */
    private final Map<Long, java.util.concurrent.locks.ReentrantLock> checkLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private CheckResult runCheck(PrivateProxy proxy, Node node) {
        java.util.concurrent.locks.ReentrantLock lock = checkLocks.computeIfAbsent(proxy.getId(), id -> new java.util.concurrent.locks.ReentrantLock());
        try {
            if (!lock.tryLock(90, java.util.concurrent.TimeUnit.SECONDS)) {
                return new CheckResult(false, null, null, null, "这个代理正在自检，请稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult(false, null, null, null, "自检被中断");
        }
        try {
            return probe(proxy, node);
        } finally {
            lock.unlock();
        }
    }

    private CheckResult probe(PrivateProxy proxy, Node node) {
        JSONObject request;
        try {
            request = probeRequest(proxy);
        } catch (Exception e) {
            return new CheckResult(false, null, null, null, e.getMessage());
        }
        GostDto result = GostUtil.ProbePrivateProxy(node.getId(), request);
        if (!gostSuccess(result)) return new CheckResult(false, null, null, null, describeProbeError(gostMessage(result)));
        JSONObject data = JSONObject.parseObject(JSONObject.toJSONString(result.getData()));
        if (data == null || StringUtils.isBlank(data.getString("ip"))) {
            return new CheckResult(false, null, null, null, "测试页面没有返回出口 IP");
        }
        return new CheckResult(true, data.getString("ip"), data.getString("location"), data.getInteger("latencyMs"), null);
    }

    /** 把 Agent 的英文错误翻成能看懂的说明，原文附在后面便于排查。 */
    static String describeProbeError(String message) {
        String text = StringUtils.defaultString(message);
        String lower = text.toLowerCase(Locale.ROOT);
        String hint;
        if (lower.contains("connection refused")) hint = "节点上的代理没有在监听这个端口";
        else if (lower.contains("timeout") || lower.contains("deadline exceeded")) hint = "连接超时，代理或它的出口没有响应";
        else if (lower.contains("certificate") || lower.contains("x509")) hint = "证书校验失败";
        else if (lower.contains("authentication") || lower.contains("auth") || lower.contains("407")) hint = "认证失败，凭据可能已变化";
        else if (lower.contains("exit group") || lower.contains("no usable exit")) hint = "家宽出口暂不可用";
        else if (lower.contains("too many proxy tests")) hint = "节点上同时进行的自检太多";
        else hint = "经代理访问测试页面失败";
        return StringUtils.left(hint + "（" + text + "）", 480);
    }

    private void recordCheck(Long proxyId, CheckResult result, boolean countFailure) {
        UpdateWrapper<PrivateProxy> update = new UpdateWrapper<PrivateProxy>().eq("id", proxyId)
                .set("last_check_at", result.checkedAt)
                .set("last_check_ok", result.ok ? 1 : 0)
                .set("last_check_ip", result.ip)
                .set("last_check_latency", result.latencyMs)
                .set("last_check_error", result.error);
        if (result.ok) update.set("check_failures", 0);
        else if (countFailure) update.setSql("check_failures = check_failures + 1");
        proxyMapper.update(null, update);
    }

    private final java.util.concurrent.ExecutorService checker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "private-proxy-check");
        thread.setDaemon(true);
        return thread;
    });

    /** 创建或修改成功后自检一次。等事务提交，否则检查线程读不到新的配置。 */
    private void checkAfterCommit(Long proxyId) {
        Runnable task = () -> checker.submit(() -> checkQuietly(proxyId, false));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    /**
     * 每 10 分钟自检所有运行中的代理；连续两次失败时尝试修复一次。
     * WireGuard 不参加：自检会短暂占用这把密钥的连接端点，打断正在使用的设备。
     */
    @Scheduled(initialDelay = 180_000L, fixedDelay = 600_000L)
    public void checkActiveProxies() {
        List<PrivateProxy> proxies = proxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .eq("state", "active").ne("proxy_type", "wireguard").orderByAsc("node_id").orderByAsc("id"));
        for (PrivateProxy proxy : proxies) checker.submit(() -> checkQuietly(proxy.getId(), true));
    }

    private void checkQuietly(Long proxyId, boolean allowRepair) {
        try {
            PrivateProxy proxy = proxyMapper.selectById(proxyId);
            if (proxy == null || !"active".equals(proxy.getState())) return;
            Node node = nodeMapper.selectById(proxy.getNodeId());
            if (node == null || !WebSocketServer.isNodeOnline(node.getId())
                    || !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_PROTOCOL_AGENT_VERSION)) {
                return;
            }
            CheckResult result = runCheck(proxy, node);
            boolean exitDown = !result.ok && "residential".equals(proxy.getExitType())
                    && !"ok".equals(exitPoolService.exitView(proxy.getNodeId(), proxy.getExitCountry()).get("status"));
            // 家宽出口不可用时由出口告警负责，这里不累计失败，也不修复
            recordCheck(proxyId, result, !exitDown);
            int failures = (proxy.getCheckFailures() == null ? 0 : proxy.getCheckFailures()) + 1;
            if (result.ok || exitDown || !allowRepair || failures != 2) return;
            log.info("Private proxy {} failed two checks, repairing: {}", proxyId, result.error);
            try {
                transactionTemplate.executeWithoutResult(status -> repair(proxyId));
            } catch (RuntimeException e) {
                proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxyId)
                        .set("last_check_error", StringUtils.left("自动修复失败：" + e.getMessage() + "；" + result.error, 480)));
                return;
            }
            PrivateProxy repaired = proxyMapper.selectById(proxyId);
            if (repaired != null && "active".equals(repaired.getState())) recordCheck(proxyId, runCheck(repaired, node), true);
        } catch (Exception e) {
            log.warn("Private proxy {} check failed: {}", proxyId, e.getMessage());
        }
    }

    /** 按保存的设置把代理在节点上补齐，已有的部分保持不变。 */
    private void repair(Long proxyId) {
        PrivateProxy proxy = proxyMapper.selectById(proxyId);
        if (proxy == null || !"active".equals(proxy.getState())) return;
        Node node = nodeMapper.selectById(proxy.getNodeId());
        if (node == null || !WebSocketServer.isNodeOnline(node.getId())) return;
        Spec spec = specOf(proxy);
        JSONObject before = storedClientConfig(proxy);
        JSONObject after = provision(proxy, node, spec, true);
        if (!after.equals(before)) {
            // 节点上的运行时丢失后重建，会生成新的证书或密钥
            saveProvisioned(proxy, after, spec);
            proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxyId)
                    .set("last_error", "节点上的配置丢失，已自动重建；连接信息已变化，请重新导入或更新订阅"));
        }
    }

    // ---------------------------------------------------------------- 订阅

    /** 当前用户的订阅令牌；reset 为 true 时换一个，旧链接立即失效。 */
    public R subscription(boolean reset) {
        Integer userId = JwtUtil.getUserIdFromToken();
        List<String> tokens = jdbcTemplate.queryForList("SELECT token FROM private_proxy_subscription WHERE user_id=?", String.class, userId);
        String token = tokens.isEmpty() ? null : tokens.get(0);
        if (reset || token == null) {
            token = randomHex(24);
            jdbcTemplate.update("INSERT INTO private_proxy_subscription (user_id, token, created_time) VALUES (?,?,?) "
                    + "ON DUPLICATE KEY UPDATE token=VALUES(token), created_time=VALUES(created_time)", userId, token, System.currentTimeMillis());
        }
        return R.ok(Map.of("token", token));
    }

    /** 订阅内容。令牌无效时返回 null。 */
    public SubscriptionContent subscriptionContent(String token, String format, String userAgent) {
        return subscriptionContent(token, format, userAgent, null);
    }

    /**
     * 订阅内容。proxyId 不为空时只包含这一个代理，用于单个代理的“一键导入”；
     * 代理必须属于令牌的主人（管理员可以导入任何代理）。令牌无效时返回 null。
     */
    public SubscriptionContent subscriptionContent(String token, String format, String userAgent, Long proxyId) {
        if (StringUtils.isBlank(token) || !token.matches("[0-9a-f]{48}")) return null;
        List<Integer> users = jdbcTemplate.queryForList("SELECT user_id FROM private_proxy_subscription WHERE token=?", Integer.class, token);
        if (users.isEmpty()) return null;
        Integer userId = users.get(0);
        User user = userMapper.selectById(userId);
        if (user == null || !Objects.equals(user.getStatus(), 1)) return null;
        QueryWrapper<PrivateProxy> query = new QueryWrapper<PrivateProxy>().eq("state", "active").orderByAsc("id");
        if (proxyId != null) {
            query.eq("id", proxyId);
            if (!Objects.equals(user.getRoleId(), 0)) query.eq("user_id", userId);
        } else {
            query.eq("user_id", userId);
        }
        excludeMissingNodes(query);
        List<PrivateProxy> proxies = proxyMapper.selectList(query);
        if (proxyId != null && proxies.isEmpty()) return null;
        String title = proxyId != null ? proxies.get(0).getName() : PrivateProxySubscriptions.GROUP;

        String kind = PrivateProxySubscriptions.detectFormat(format, userAgent);
        java.util.Set<String> names = new java.util.HashSet<>();
        List<String> uris = new java.util.ArrayList<>();
        List<Map<String, Object>> clash = new java.util.ArrayList<>();
        List<PrivateProxyClientFormats.SingBoxClient> singBox = new java.util.ArrayList<>();
        List<String> tags = new java.util.ArrayList<>();
        long upload = 0, download = 0, total = 0;
        boolean limited = true;
        Long expire = null;
        for (PrivateProxy proxy : proxies) {
            Node node = nodeMapper.selectById(proxy.getNodeId());
            String host = clientHost(proxy, node);
            JSONObject config;
            try {
                config = clientConfigOf(proxy);
            } catch (Exception e) {
                continue;
            }
            if (StringUtils.isBlank(host) || config == null) continue;
            String name = PrivateProxySubscriptions.uniqueName(proxy.getName(), names);
            String type = proxy.getProxyType();
            if (!"wireguard".equals(type)) {
                String uri = PrivateProxyClientFormats.uri(type, name, host, proxy.getListenPort(), config);
                if (uri != null) uris.add(uri);
            }
            Map<String, Object> entry = PrivateProxyClientFormats.clash(type, name, host, proxy.getListenPort(), config);
            if (entry != null) clash.add(entry);
            PrivateProxyClientFormats.SingBoxClient client = PrivateProxyClientFormats.singBox(type, name, host, proxy.getListenPort(), config);
            if (client != null) {
                singBox.add(client);
                tags.add(name);
            }
            upload += value(proxy.getInFlow());
            download += value(proxy.getOutFlow());
            if (proxy.getGrantedByUserId() != null && !Objects.equals(proxy.getFlowUnlimited(), 1)) {
                total += value(proxy.getFlowLimit()) * BYTES_PER_GB;
            } else {
                limited = false;
            }
            if (proxy.getExpiresAt() != null) expire = expire == null ? proxy.getExpiresAt() : Math.min(expire, proxy.getExpiresAt());
        }
        StringBuilder info = new StringBuilder("upload=").append(upload).append("; download=").append(download);
        if (limited && total > 0) info.append("; total=").append(total);
        if (expire != null) info.append("; expire=").append(expire / 1000L);
        switch (kind) {
            case "clash":
                return new SubscriptionContent(PrivateProxySubscriptions.clashProfile(clash), "text/yaml", "CloudNest.yaml", info.toString(), title);
            case "singbox":
                return new SubscriptionContent(PrivateProxySubscriptions.singBoxProfile(singBox, tags), "application/json", "CloudNest.json", info.toString(), title);
            default:
                return new SubscriptionContent(PrivateProxySubscriptions.base64(uris), "text/plain", "CloudNest.txt", info.toString(), title);
        }
    }

    public static final class SubscriptionContent {
        public final String body;
        public final String contentType;
        public final String filename;
        public final String userInfo;
        /** 客户端里显示的配置名 */
        public final String title;

        SubscriptionContent(String body, String contentType, String filename, String userInfo, String title) {
            this.body = body;
            this.contentType = contentType;
            this.filename = filename;
            this.userInfo = userInfo;
            this.title = title;
        }
    }

    // ---------------------------------------------------------------- 流量记录

    private static final long HOUR_MS = 3_600_000L;

    /** 记入按小时汇总的流量，并更新最近使用时间。 */
    private void recordHourlyTraffic(Long proxyId, long inbound, long outbound) {
        if (inbound <= 0 && outbound <= 0) return;
        long now = System.currentTimeMillis();
        long hour = now - now % HOUR_MS;
        try {
            jdbcTemplate.update("INSERT INTO private_proxy_traffic (proxy_id, hour_at, in_flow, out_flow) VALUES (?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE in_flow=in_flow+VALUES(in_flow), out_flow=out_flow+VALUES(out_flow)",
                    proxyId, hour, Math.max(0L, inbound), Math.max(0L, outbound));
            jdbcTemplate.update("UPDATE private_proxy SET last_active_at=? WHERE id=?", now, proxyId);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Private proxy traffic history write failed: {}", e.getMessage());
        }
    }

    /** 流量曲线：24h 按小时，7d/30d 按天。 */
    public R traffic(Long id, String range) {
        PrivateProxy proxy = id == null ? null : owned(id);
        if (proxy == null) return R.err("代理不存在或无权访问");
        boolean hourly = !"7d".equals(range) && !"30d".equals(range);
        int buckets = hourly ? 24 : "7d".equals(range) ? 7 : 30;
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        long end;
        long step;
        if (hourly) {
            step = HOUR_MS;
            end = now - now % HOUR_MS + HOUR_MS;
        } else {
            step = 24 * HOUR_MS;
            end = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        }
        long start = hourly ? end - buckets * step
                : Instant.ofEpochMilli(end).atZone(zone).toLocalDate().minusDays(buckets).atStartOfDay(zone).toInstant().toEpochMilli();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT hour_at, in_flow, out_flow FROM private_proxy_traffic WHERE proxy_id=? AND hour_at>=? AND hour_at<? ORDER BY hour_at",
                proxy.getId(), start, end);
        Map<Long, long[]> sums = new java.util.TreeMap<>();
        for (int i = 0; i < buckets; i++) {
            long bucket = hourly ? start + i * step
                    : Instant.ofEpochMilli(start).atZone(zone).toLocalDate().plusDays(i).atStartOfDay(zone).toInstant().toEpochMilli();
            sums.put(bucket, new long[2]);
        }
        for (Map<String, Object> row : rows) {
            long hour = ((Number) row.get("hour_at")).longValue();
            long bucket = hourly ? hour
                    : Instant.ofEpochMilli(hour).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
            long[] sum = sums.get(bucket);
            if (sum == null) continue;
            sum[0] += ((Number) row.get("in_flow")).longValue();
            sum[1] += ((Number) row.get("out_flow")).longValue();
        }
        List<Map<String, Object>> points = new java.util.ArrayList<>();
        long totalIn = 0, totalOut = 0;
        for (Map.Entry<Long, long[]> entry : sums.entrySet()) {
            points.add(Map.of("time", entry.getKey(), "inFlow", entry.getValue()[0], "outFlow", entry.getValue()[1]));
            totalIn += entry.getValue()[0];
            totalOut += entry.getValue()[1];
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", hourly ? "24h" : range);
        result.put("points", points);
        result.put("totalIn", totalIn);
        result.put("totalOut", totalOut);
        result.put("lastActiveAt", proxy.getLastActiveAt());
        return R.ok(result);
    }

    /** 每天清理 35 天前的流量记录，并删除已删除代理的记录。 */
    @Scheduled(cron = "0 17 4 * * *")
    public void cleanupTrafficHistory() {
        try {
            jdbcTemplate.update("DELETE FROM private_proxy_traffic WHERE hour_at < ?", System.currentTimeMillis() - 35L * 24 * HOUR_MS);
            jdbcTemplate.update("DELETE t FROM private_proxy_traffic t LEFT JOIN private_proxy p ON p.id=t.proxy_id "
                    + "WHERE p.id IS NULL OR p.state='deleted'");
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Private proxy traffic cleanup failed: {}", e.getMessage());
        }
    }

    private void excludeMissingNodes(QueryWrapper<PrivateProxy> query) {
        query.exists("SELECT 1 FROM node n WHERE n.id = private_proxy.node_id");
    }

    private PrivateProxy view(PrivateProxy proxy) {
        Node node = nodeMapper.selectById(proxy.getNodeId());
        User owner = userMapper.selectById(proxy.getUserId());
        proxy.setNodeName(node == null ? "节点已删除" : node.getName());
        proxy.setPublicHost(node == null ? null : StringUtils.defaultIfBlank(node.getServerIp(), node.getIp()));
        proxy.setOwnerUserName(owner == null ? "未知用户" : owner.getUser());
        proxy.setNodeOnline(node != null && WebSocketServer.isNodeOnline(node.getId()));
        proxy.setPasswordConfigured(StringUtils.isNotBlank(proxy.getAuthPassword()));
        long used = value(proxy.getInFlow()) + value(proxy.getOutFlow());
        boolean granted = proxy.getGrantedByUserId() != null;
        proxy.setGranted(granted);
        proxy.setSpeedLimitSupported(supportsGrantRateLimit(proxy.getProxyType()));
        proxy.setRemainingFlow(!granted || Objects.equals(proxy.getFlowUnlimited(), 1) ? null
                : Math.max(0L, value(proxy.getFlowLimit()) * BYTES_PER_GB - used));
        proxy.setRemainingTime(proxy.getExpiresAt() == null ? null
                : Math.max(0L, proxy.getExpiresAt() - System.currentTimeMillis()));
        String unavailableReason = unavailableReason(proxy);
        if ("residential".equals(proxy.getExitType()) && StringUtils.isNotBlank(proxy.getExitCountry())) {
            Map<String, Object> exit = exitPoolService.exitView(proxy.getNodeId(), proxy.getExitCountry());
            proxy.setExit(exit);
            if (unavailableReason == null && !"ok".equals(exit.get("status"))) {
                unavailableReason = "当前没有可用的" + exit.get("countryName") + "家宽出口，代理已断开，不会改用节点自身 IP";
            }
        }
        try {
            JSONObject config = clientConfigOf(proxy);
            if (config != null) {
                proxy.setLinkSkipsCertCheck(PrivateProxyClientFormats.uriSkipsCertificateCheck(proxy.getProxyType(), config));
                proxy.setTransport(config.getString("transport"));
                if (isRealityType(proxy.getProxyType())) proxy.setCamouflage(config.getString("serverName"));
                if ("shadowtls".equals(proxy.getProxyType())) proxy.setCamouflage(config.getString("handshakeServer"));
            }
        } catch (Exception ignored) {
            // 解密失败时列表照常显示，连接信息接口会给出错误
        }
        proxy.setAvailable(unavailableReason == null);
        proxy.setUnavailableReason(unavailableReason);
        hideInternalGrantPolicy(proxy, isAdmin());
        proxy.setAuthPassword(null);
        proxy.setExitSwitchToken(null);
        proxy.setClientConfig(null);
        proxy.setRuntimeInFlow(null);
        proxy.setRuntimeOutFlow(null);
        return proxy;
    }

    static void hideInternalGrantPolicy(PrivateProxy proxy, boolean admin) {
        if (admin) return;
        proxy.setSpeedLimitMbps(null);
        proxy.setSpeedLimitSupported(null);
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
        if (isRealityType(proxy.getProxyType())) {
            advancedRuntimeClean = gostCleanupSuccess(GostUtil.DeleteRealityRuntime(node.getId(), realityRuntimeName(proxy)));
        } else if (isAdvancedRuntime(proxy.getProxyType())) {
            advancedRuntimeClean = gostCleanupSuccess(GostUtil.DeletePrivateProxyRuntime(node.getId(), advancedRuntimeName(proxy)));
        }
        boolean admissionClean = true;
        if (proxy.getAdmissionName() != null) {
            admissionClean = gostCleanupSuccess(GostUtil.DeleteAdmission(node.getId(), proxy.getAdmissionName()));
        }
        if (proxy.getExitRoutePort() != null
                || ("residential".equals(proxy.getExitType()) && ExitPoolService.ROUTED_PROXY_TYPES.contains(proxy.getProxyType()))) {
            serviceClean = gostCleanupSuccess(GostUtil.DeleteNamedService(node.getId(), exitRouteName(proxy))) && serviceClean;
        }
        boolean limiterClean = proxy.getGrantedByUserId() == null || !supportsGrantRateLimit(proxy.getProxyType())
                || gostCleanupSuccess(GostUtil.DeleteNamedLimiter(node.getId(), limiterName(proxy)));
        return serviceClean && admissionClean && advancedRuntimeClean && limiterClean;
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

    static final java.util.Set<String> ADVANCED_RUNTIME_TYPES = java.util.Set.of("trojan", "hysteria2", "tuic", "wireguard",
            "anytls", "shadowsocks2022", "vmess", "vless", "shadowtls");

    /** 由 Agent 的 sing-box 运行时承载的协议。 */
    private boolean isAdvancedRuntime(String proxyType) {
        return ADVANCED_RUNTIME_TYPES.contains(proxyType);
    }

    /** 由 Agent 的 xray 运行时承载、gost 负责公网入口的协议。 */
    private boolean isRealityType(String proxyType) {
        return "vless_reality".equals(proxyType) || "vless_xhttp".equals(proxyType);
    }

    private String gostHandlerType(String proxyType) {
        return "mixed".equals(proxyType) ? "auto" : proxyType;
    }

    static boolean isUdpAdvancedRuntime(String proxyType) {
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

    static String protocolLabel(String proxyType) {
        switch (proxyType) {
            case "socks5": return "SOCKS5";
            case "http": return "HTTP";
            case "mixed": return "SOCKS5+HTTP";
            case "shadowsocks": return "Shadowsocks";
            case "shadowsocks2022": return "Shadowsocks 2022";
            case "vless_reality": return "VLESS+REALITY";
            case "vless_xhttp": return "VLESS+XHTTP+REALITY";
            case "vless": return "VLESS";
            case "vmess": return "VMess";
            case "trojan": return "Trojan";
            case "anytls": return "AnyTLS";
            case "hysteria2": return "Hysteria2";
            case "tuic": return "TUIC v5";
            case "shadowtls": return "ShadowTLS";
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

    private void reconcileGrantedProxy(PrivateProxy proxy, long now) {
        if (proxy == null || proxy.getGrantedByUserId() == null) return;
        if (shouldResetFlow(proxy.getFlowResetDay(), proxy.getLastFlowResetAt(), now)) {
            resetFlow(proxy, now);
        }
        boolean expired = proxy.getExpiresAt() != null && proxy.getExpiresAt() <= now;
        boolean quotaExhausted = !Objects.equals(proxy.getFlowUnlimited(), 1)
                && value(proxy.getInFlow()) + value(proxy.getOutFlow()) >= value(proxy.getFlowLimit()) * BYTES_PER_GB;
        Node node = nodeMapper.selectById(proxy.getNodeId());
        boolean online = node != null && WebSocketServer.isNodeOnline(node.getId());

        if (expired) {
            enforceGrantPause(proxy, online, "expired", "代理授权已到期");
            return;
        }
        if (quotaExhausted) {
            enforceGrantPause(proxy, online, "quota_exhausted", "代理流量额度已用尽");
            return;
        }
        if (online && ("expired".equals(proxy.getState()) || "quota_exhausted".equals(proxy.getState()))) {
            GostDto resumed = resumeRuntime(proxy);
            if (gostSuccess(resumed)) updateState(proxy, "active", null);
            else updateState(proxy, proxy.getState(), "恢复代理失败：" + gostMessage(resumed));
        }
    }

    private void enforceGrantPause(PrivateProxy proxy, boolean online, String targetState, String reason) {
        boolean runtimeMayBeActive = "active".equals(proxy.getState())
                || (targetState.equals(proxy.getState()) && !reason.equals(proxy.getLastError()));
        if (runtimeMayBeActive && !online) {
            String waiting = reason + "，等待节点上线暂停";
            if (!targetState.equals(proxy.getState()) || !waiting.equals(proxy.getLastError())) {
                updateState(proxy, targetState, waiting);
            }
            return;
        }
        if (runtimeMayBeActive) {
            GostDto paused = pauseRuntime(proxy);
            if (!gostSuccess(paused)) {
                updateState(proxy, targetState, reason + "；自动暂停失败：" + gostMessage(paused));
                return;
            }
        }
        if (!targetState.equals(proxy.getState()) || !reason.equals(proxy.getLastError())) {
            updateState(proxy, targetState, reason);
        }
    }

    private void resetFlow(PrivateProxy proxy, long now) {
        proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", proxy.getId())
                .set("in_flow", 0L).set("out_flow", 0L).set("last_flow_reset_at", now)
                .set("updated_time", now));
        proxy.setInFlow(0L);
        proxy.setOutFlow(0L);
        proxy.setLastFlowResetAt(now);
        if ("quota_exhausted".equals(proxy.getState())
                && (proxy.getExpiresAt() == null || proxy.getExpiresAt() > now)) {
            Node node = nodeMapper.selectById(proxy.getNodeId());
            if (node != null && WebSocketServer.isNodeOnline(node.getId())) {
                GostDto resumed = resumeRuntime(proxy);
                if (gostSuccess(resumed)) updateState(proxy, "active", null);
            }
        }
    }

    static boolean shouldResetFlow(Integer resetDay, Long lastResetAt, long now) {
        if (resetDay == null || resetDay <= 0) return false;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate thresholdDate = today.withDayOfMonth(Math.min(resetDay, today.lengthOfMonth()));
        long threshold = thresholdDate.atStartOfDay(zone).toInstant().toEpochMilli();
        if (now < threshold) {
            LocalDate previousMonth = today.minusMonths(1);
            thresholdDate = previousMonth.withDayOfMonth(Math.min(resetDay, previousMonth.lengthOfMonth()));
            threshold = thresholdDate.atStartOfDay(zone).toInstant().toEpochMilli();
        }
        return lastResetAt == null || lastResetAt < threshold;
    }

    private String unavailableReason(PrivateProxy proxy) {
        if (!Boolean.TRUE.equals(proxy.getNodeOnline())) return "代理节点离线";
        if ("expired".equals(proxy.getState())) return "代理授权已到期";
        if ("quota_exhausted".equals(proxy.getState())) return "代理流量额度已用尽";
        if ("paused".equals(proxy.getState())) return "代理已暂停";
        if ("error".equals(proxy.getState()) || "delete_pending".equals(proxy.getState())) {
            return StringUtils.defaultIfBlank(proxy.getLastError(), "代理运行异常");
        }
        return "active".equals(proxy.getState()) ? null : StringUtils.defaultIfBlank(proxy.getLastError(), "代理暂不可用");
    }

    private boolean canManage(PrivateProxy proxy) {
        return isAdmin() || proxy.getGrantedByUserId() == null;
    }

    private boolean supportsGrant(String proxyType) {
        return supportsGrantRateLimit(proxyType) || isAdvancedRuntime(proxyType);
    }

    private boolean supportsGrantRateLimit(String proxyType) {
        return "socks5".equals(proxyType) || "http".equals(proxyType) || "mixed".equals(proxyType)
                || "shadowsocks".equals(proxyType) || isRealityType(proxyType);
    }

    /** 从独立运行时读取流量计数。授权代理和用户自己创建的代理都记账。 */
    private void synchronizeAdvancedGrantTraffic() {
        List<PrivateProxy> proxies = proxyMapper.selectList(new QueryWrapper<PrivateProxy>()
                .eq("state", "active")
                .in("proxy_type", ADVANCED_RUNTIME_TYPES));
        for (PrivateProxy proxy : proxies) {
            Node node = nodeMapper.selectById(proxy.getNodeId());
            if (node == null || !WebSocketServer.isNodeOnline(node.getId())
                    || !AgentVersionUtil.isAtLeast(node.getVersion(), MIN_ADVANCED_GRANT_AGENT_VERSION)) {
                continue;
            }
            GostDto result = GostUtil.GetPrivateProxyRuntimeTraffic(node.getId(), advancedRuntimeName(proxy));
            if (!gostSuccess(result) || result.getData() == null) continue;
            JSONObject data = JSONObject.parseObject(JSONObject.toJSONString(result.getData()));
            if (data == null || !data.containsKey("inFlow") || !data.containsKey("outFlow")) continue;
            long currentIn = Math.max(0L, data.getLongValue("inFlow"));
            long currentOut = Math.max(0L, data.getLongValue("outFlow"));
            Boolean counted = transactionTemplate.execute(status -> applyAdvancedRuntimeTraffic(proxy.getId(), currentIn, currentOut));
            if (Boolean.TRUE.equals(counted) && proxy.getGrantedByUserId() == null) enforceUserQuota(proxy);
        }
    }

    /** 返回是否记入了新的流量。 */
    private boolean applyAdvancedRuntimeTraffic(Long proxyId, long currentIn, long currentOut) {
        PrivateProxy latest = proxyMapper.selectOne(new QueryWrapper<PrivateProxy>()
                .eq("id", proxyId).last("FOR UPDATE"));
        if (latest == null || !"active".equals(latest.getState()) || !isAdvancedRuntime(latest.getProxyType())) return false;
        long deltaIn = runtimeTrafficDelta(currentIn, value(latest.getRuntimeInFlow()));
        long deltaOut = runtimeTrafficDelta(currentOut, value(latest.getRuntimeOutFlow()));
        if (latest.getGrantedByUserId() == null && value(latest.getRuntimeInFlow()) == 0L && value(latest.getRuntimeOutFlow()) == 0L
                && value(latest.getInFlow()) == 0L && value(latest.getOutFlow()) == 0L) {
            // 早期版本不统计用户自建的独立运行时；第一次读取只记下起点，不把升级前的累计流量算到现在
            deltaIn = 0L;
            deltaOut = 0L;
        }
        proxyMapper.update(null, new UpdateWrapper<PrivateProxy>().eq("id", latest.getId())
                .set("runtime_in_flow", currentIn)
                .set("runtime_out_flow", currentOut)
                .setSql("in_flow = in_flow + " + deltaIn)
                .setSql("out_flow = out_flow + " + deltaOut)
                .set("updated_time", System.currentTimeMillis()));
        if (deltaIn == 0L && deltaOut == 0L) return false;
        userMapper.update(null, new UpdateWrapper<User>().eq("id", latest.getUserId())
                .setSql("in_flow = in_flow + " + deltaIn)
                .setSql("out_flow = out_flow + " + deltaOut));
        recordHourlyTraffic(latest.getId(), deltaIn, deltaOut);
        userQuotaService.recordPrivateProxyFlow(latest, deltaIn, deltaOut);
        return true;
    }

    static long runtimeTrafficDelta(long current, long previous) {
        long safeCurrent = Math.max(0L, current);
        long safePrevious = Math.max(0L, previous);
        return safeCurrent >= safePrevious ? safeCurrent - safePrevious : safeCurrent;
    }

    private String limiterName(PrivateProxy proxy) {
        return "private-proxy-user-" + proxy.getId();
    }

    private String limiterSpeed(Integer speedMbps) {
        int mbps = speedMbps == null ? UNLIMITED_LIMITER_MBPS : speedMbps;
        return String.format(Locale.ROOT, "%.1f", mbps / 8.0d);
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
