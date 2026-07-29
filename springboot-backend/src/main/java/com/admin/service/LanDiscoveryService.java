package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.InternalConnector;
import com.admin.entity.LanDiscoveredService;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.mapper.LanDiscoveredServiceMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LanDiscoveryService {
    public static final String MIN_AGENT_VERSION = "2.32.0";
    private static final List<Integer> DISCOVERY_PORTS = List.of(
            21, 22, 23, 53, 80, 81, 139, 443, 445, 554, 1883, 3000, 3306, 3389,
            5000, 5001, 5432, 8000, 8001, 8080, 8123, 8443, 8883, 9000, 9090, 32400);
    private static final Set<Long> RUNNING_SCANS = ConcurrentHashMap.newKeySet();

    @Resource private InternalConnectorMapper connectorMapper;
    @Resource private LanDiscoveredServiceMapper discoveredServiceMapper;
    @Resource private TransactionTemplate transactionTemplate;

    public R settings(Long connectorId, boolean enabled) {
        InternalConnector connector = ownedConnector(connectorId);
        if (connector == null) return R.err("家庭设备不存在或无权访问");
        connector.setDiscoveryEnabled(enabled ? 1 : 0);
        if (!enabled) connector.setDiscoveryStatus("disabled");
        else if (StringUtils.isBlank(connector.getDiscoveryStatus()) || "disabled".equals(connector.getDiscoveryStatus())) {
            connector.setDiscoveryStatus("idle");
        }
        connector.setUpdatedTime(System.currentTimeMillis());
        connectorMapper.updateById(connector);
        return R.ok(connectorView(connector));
    }

    public R results(Long connectorId) {
        InternalConnector connector = ownedConnector(connectorId);
        if (connector == null) return R.err("家庭设备不存在或无权访问");
        if ("scanning".equals(connector.getDiscoveryStatus())
                && connector.getUpdatedTime() != null && connector.getUpdatedTime() < System.currentTimeMillis() - 60_000L) {
            markFailure(connector, "上次扫描未正常结束，请重新开始");
        }
        Map<String, Object> result = connectorView(connector);
        result.put("services", listResults(connectorId));
        return R.ok(result);
    }

    public R clear(Long connectorId) {
        InternalConnector connector = ownedConnector(connectorId);
        if (connector == null) return R.err("家庭设备不存在或无权访问");
        discoveredServiceMapper.delete(new QueryWrapper<LanDiscoveredService>().eq("connector_id", connectorId));
        return R.ok();
    }

    public R scan(Long connectorId, String requestedCidr) {
        if (connectorId == null) return R.err("家庭设备不能为空");
        if (!RUNNING_SCANS.add(connectorId)) return R.err("该设备已有扫描任务正在运行");
        try {
            return executeScan(connectorId, requestedCidr);
        } finally {
            RUNNING_SCANS.remove(connectorId);
        }
    }

    private R executeScan(Long connectorId, String requestedCidr) {
        InternalConnector connector = ownedConnector(connectorId);
        if (connector == null) return R.err("家庭设备不存在或无权访问");
        if (!Objects.equals(connector.getDiscoveryEnabled(), 1)) return R.err("请先开启该设备的局域网服务发现");
        if (!WebSocketServer.isConnectorOnline(connectorId)) return R.err("家庭设备离线，无法执行服务发现");
        if (!AgentVersionUtil.isAtLeast(connector.getVersion(), MIN_AGENT_VERSION)) {
            return R.err("家庭 Agent 需要先升级到 " + MIN_AGENT_VERSION + " 或更高版本");
        }
        String cidr = StringUtils.defaultIfBlank(requestedCidr, "auto").trim();
        if (!"auto".equalsIgnoreCase(cidr) && !validDiscoveryCidr(cidr, connector.getAllowedCidrs())) {
            return R.err("扫描范围必须是允许网段内的 IPv4 私网，且不能大于 /24");
        }

        markScanning(connector, cidr);
        GostDto response = WebSocketServer.sendConnectorMsg(connectorId, Map.of(
                "cidr", cidr,
                "allowedCidrs", Arrays.stream(StringUtils.defaultString(connector.getAllowedCidrs()).split(","))
                        .map(String::trim).filter(StringUtils::isNotBlank).toList(),
                "ports", DISCOVERY_PORTS,
                "timeoutMs", 250,
                "maxHosts", 513
        ), "LanDiscovery", 27);
        if (response == null || !"OK".equals(response.getMsg()) || response.getData() == null) {
            String message = response == null ? "家庭 Agent 无响应" : StringUtils.defaultIfBlank(response.getMsg(), "家庭 Agent 无响应");
            markFailure(connector, message);
            return R.err("服务发现失败：" + message);
        }

        JSONObject payload = JSONObject.parseObject(JSON.toJSONString(response.getData()));
        JSONArray items = payload.getJSONArray("services");
        List<LanDiscoveredService> services = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (items != null) {
            for (int i = 0; i < items.size() && services.size() < 1000; i++) {
                JSONObject item = items.getJSONObject(i);
                String host = StringUtils.trimToEmpty(item.getString("host"));
                Integer port = item.getInteger("port");
                if (port == null || port < 1 || port > 65535 || !targetAllowed(host, connector.getAllowedCidrs())) continue;
                LanDiscoveredService service = new LanDiscoveredService();
                service.setConnectorId(connectorId);
                service.setUserId(connector.getUserId());
                service.setHost(host);
                service.setPort(port);
                service.setServiceType(limit(item.getString("serviceType"), 40));
                service.setServiceName(limit(item.getString("serviceName"), 100));
                service.setProduct(limit(item.getString("product"), 160));
                service.setTitle(limit(item.getString("title"), 160));
                service.setConfidence(limit(item.getString("confidence"), 16));
                service.setSensitive(Boolean.TRUE.equals(item.getBoolean("sensitive")) ? 1 : 0);
                service.setFirstSeenAt(now);
                service.setLastSeenAt(now);
                service.setCreatedTime(now);
                service.setUpdatedTime(now);
                services.add(service);
            }
        }
        transactionTemplate.executeWithoutResult(status -> {
            discoveredServiceMapper.delete(new QueryWrapper<LanDiscoveredService>().eq("connector_id", connectorId));
            for (LanDiscoveredService service : services) discoveredServiceMapper.insert(service);
            connector.setDiscoveryStatus("complete");
            connector.setDiscoveryLastScanAt(now);
            JSONArray rangeItems = payload.getJSONArray("ranges");
            connector.setDiscoveryLastCidr(rangeItems == null ? cidr : String.join(", ", rangeItems.toJavaList(String.class)));
            connector.setDiscoveryLastError(null);
            connector.setUpdatedTime(now);
            connectorMapper.updateById(connector);
        });

        Map<String, Object> result = connectorView(connector);
        result.put("services", services);
        result.put("scannedHosts", payload.getIntValue("scannedHosts"));
        result.put("scannedPorts", payload.getIntValue("scannedPorts"));
        result.put("durationMs", payload.getLongValue("durationMs"));
        return R.ok(result);
    }

    private void markScanning(InternalConnector connector, String cidr) {
        connector.setDiscoveryStatus("scanning");
        connector.setDiscoveryLastCidr(cidr);
        connector.setDiscoveryLastError(null);
        connector.setUpdatedTime(System.currentTimeMillis());
        connectorMapper.updateById(connector);
    }

    private void markFailure(InternalConnector connector, String message) {
        connector.setDiscoveryStatus("failed");
        connector.setDiscoveryLastError(limit(message, 500));
        connector.setUpdatedTime(System.currentTimeMillis());
        connectorMapper.updateById(connector);
    }

    private List<LanDiscoveredService> listResults(Long connectorId) {
        return discoveredServiceMapper.selectList(new QueryWrapper<LanDiscoveredService>()
                .eq("connector_id", connectorId).orderByAsc("host", "port"));
    }

    private Map<String, Object> connectorView(InternalConnector connector) {
        Map<String, Object> result = new HashMap<>();
        result.put("connectorId", connector.getId());
        result.put("enabled", Objects.equals(connector.getDiscoveryEnabled(), 1));
        result.put("status", StringUtils.defaultIfBlank(connector.getDiscoveryStatus(), "disabled"));
        result.put("lastScanAt", connector.getDiscoveryLastScanAt());
        result.put("lastCidr", connector.getDiscoveryLastCidr());
        result.put("lastError", connector.getDiscoveryLastError());
        return result;
    }

    private InternalConnector ownedConnector(Long id) {
        InternalConnector connector = connectorMapper.selectById(id);
        if (connector == null || connector.getStatus() == 0) return null;
        if (!isAdmin() && !Objects.equals(connector.getUserId(), JwtUtil.getUserIdFromToken())) return null;
        return connector;
    }

    static boolean validDiscoveryCidr(String value, String allowedCidrs) {
        try {
            String[] parts = value.split("/");
            if (parts.length != 2 || !isIpv4Literal(parts[0])) return false;
            InetAddress address = InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            if (!(address instanceof Inet4Address) || prefix < 24 || prefix > 32 || !isPrivateOrLoopback(address)) return false;
            long requestedStart = ipv4Long(address) & mask(prefix);
            long requestedEnd = requestedStart | (~mask(prefix) & 0xffffffffL);
            for (String allowed : StringUtils.defaultString(allowedCidrs).split(",")) {
                String[] allowedParts = allowed.trim().split("/");
                if (allowedParts.length != 2) continue;
                InetAddress allowedAddress = InetAddress.getByName(allowedParts[0]);
                if (!(allowedAddress instanceof Inet4Address)) continue;
                int allowedPrefix = Integer.parseInt(allowedParts[1]);
                long allowedStart = ipv4Long(allowedAddress) & mask(allowedPrefix);
                long allowedEnd = allowedStart | (~mask(allowedPrefix) & 0xffffffffL);
                if (requestedStart >= allowedStart && requestedEnd <= allowedEnd) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean targetAllowed(String host, String allowedCidrs) {
        try {
            if (!isIpv4Literal(host)) return false;
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet4Address) || !isPrivateOrLoopback(address)) return false;
            long target = ipv4Long(address);
            for (String allowed : StringUtils.defaultString(allowedCidrs).split(",")) {
                String[] parts = allowed.trim().split("/");
                if (parts.length != 2) continue;
                InetAddress networkAddress = InetAddress.getByName(parts[0]);
                if (!(networkAddress instanceof Inet4Address)) continue;
                int prefix = Integer.parseInt(parts[1]);
                long start = ipv4Long(networkAddress) & mask(prefix);
                long end = start | (~mask(prefix) & 0xffffffffL);
                if (target >= start && target <= end) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean isPrivateOrLoopback(InetAddress address) {
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 10 || first == 127 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168);
    }

    private static boolean isIpv4Literal(String value) {
        if (value == null || !value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        for (String part : value.split("\\.")) {
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static long ipv4Long(InetAddress address) {
        byte[] bytes = address.getAddress();
        return ((long) (bytes[0] & 0xff) << 24) | ((long) (bytes[1] & 0xff) << 16)
                | ((long) (bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
    }

    private static long mask(int prefix) {
        return prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
    }

    private static String limit(String value, int max) {
        value = StringUtils.trimToEmpty(value);
        return value.length() <= max ? value : value.substring(0, max);
    }

    private boolean isAdmin() {
        return Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
    }
}
