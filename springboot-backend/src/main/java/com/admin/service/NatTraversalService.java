package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.HomeProxyNatEvent;
import com.admin.entity.HomeProxyRoute;
import com.admin.entity.InternalConnector;
import com.admin.entity.PortPool;
import com.admin.mapper.HomeProxyNatEventMapper;
import com.admin.mapper.HomeProxyRouteMapper;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.mapper.PortPoolMapper;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import javax.annotation.PreDestroy;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class NatTraversalService {
    private static final long RETRY_COOLDOWN_MS = 60_000L;
    private static final List<String> DEFAULT_STUN_SERVERS = List.of(
            "stun:stun.cloudflare.com:3478",
            "stun:stun.l.google.com:19302"
    );
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource private HomeProxyRouteMapper routeMapper;
    @Resource private HomeProxyNatEventMapper eventMapper;
    @Resource private InternalConnectorMapper connectorMapper;
    @Resource private PortPoolMapper poolMapper;
    private final ConcurrentHashMap<Long, Boolean> activating = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
        Thread thread = new Thread(runnable, "nat-traversal");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    public void schedule(HomeProxyRoute route) {
        if (route == null || route.getId() == null) return;
        Runnable task = () -> activate(routeMapper.selectById(route.getId()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { submit(task, route.getId()); }
            });
        } else {
            submit(task, route.getId());
        }
    }

    public void connectorOnline(Long connectorId) {
        if (connectorId == null) return;
        List<HomeProxyRoute> routes = routeMapper.selectList(new QueryWrapper<HomeProxyRoute>()
                .eq("access_mode", "smart_nat").eq("state", "active")
                .and(q -> q.eq("connector_id", connectorId).or().eq("source_connector_id", connectorId))
                .last("LIMIT 20"));
        for (HomeProxyRoute route : routes) schedule(route);
    }

    public void activate(HomeProxyRoute route) {
        if (route == null || !"smart_nat".equals(route.getAccessMode()) || !"active".equals(route.getState())) return;
        if (activating.putIfAbsent(route.getId(), Boolean.TRUE) != null) return;
        try {
            startSession(routeMapper.selectById(route.getId()));
        } finally {
            activating.remove(route.getId());
        }
    }

    public R retry(Long routeId) {
        HomeProxyRoute route = routeMapper.selectById(routeId);
        if (!canAccess(route) || !"smart_nat".equals(route.getAccessMode())) return R.err("智能 NAT 路由不存在或无权访问");
        schedule(route);
        return R.ok(routeMapper.selectById(routeId));
    }

    public R events(Long routeId) {
        HomeProxyRoute route = routeMapper.selectById(routeId);
        if (!canAccess(route)) return R.err("家庭中转不存在或无权访问");
        return R.ok(eventMapper.selectList(new QueryWrapper<HomeProxyNatEvent>()
                .eq("route_id", routeId).orderByDesc("created_time").last("LIMIT 50")));
    }

    public boolean stop(HomeProxyRoute route) {
        if (route == null || !"smart_nat".equals(route.getAccessMode())) return true;
        Map<String, Object> payload = Map.of("routeId", route.getId());
        boolean cleaned = true;
        InternalConnector source = route.getSourceConnectorId() == null ? null : connectorMapper.selectById(route.getSourceConnectorId());
        InternalConnector home = route.getConnectorId() == null ? null : connectorMapper.selectById(route.getConnectorId());
        if (source != null && WebSocketServer.isConnectorOnline(route.getSourceConnectorId())) {
            cleaned = ok(WebSocketServer.sendConnectorMsg(route.getSourceConnectorId(), payload, "NatStop", 5));
        } else if (source != null) cleaned = false;
        if (home != null && WebSocketServer.isConnectorOnline(route.getConnectorId())) {
            cleaned = ok(WebSocketServer.sendConnectorMsg(route.getConnectorId(), payload, "NatStop", 5)) && cleaned;
        } else if (home != null) cleaned = false;
        return cleaned;
    }

    public void handleAgentEvent(Long connectorId, JSONObject message) {
        JSONObject data = message.getJSONObject("data");
        if (data == null || data.getLong("routeId") == null) return;
        HomeProxyRoute route = routeMapper.selectById(data.getLong("routeId"));
        if (route == null || !"smart_nat".equals(route.getAccessMode())
                || (!Objects.equals(route.getConnectorId(), connectorId)
                && !Objects.equals(route.getSourceConnectorId(), connectorId))) return;

        String type = message.getString("type");
        long now = System.currentTimeMillis();
        if ("NatTraffic".equals(type)) {
            long directRx = number(data, "directRxDelta", null);
            long directTx = number(data, "directTxDelta", null);
            long relayRx = number(data, "relayRxDelta", null);
            long relayTx = number(data, "relayTxDelta", null);
            route.setDirectRxBytes(defaultZero(route.getDirectRxBytes()) + directRx);
            route.setDirectTxBytes(defaultZero(route.getDirectTxBytes()) + directTx);
            route.setRelayRxBytes(defaultZero(route.getRelayRxBytes()) + relayRx);
            route.setRelayTxBytes(defaultZero(route.getRelayTxBytes()) + relayTx);
            route.setUpdatedTime(now);
            routeMapper.update(null, new UpdateWrapper<HomeProxyRoute>().eq("id", route.getId())
                    .setSql("direct_rx_bytes=direct_rx_bytes+" + directRx)
                    .setSql("direct_tx_bytes=direct_tx_bytes+" + directTx)
                    .setSql("relay_rx_bytes=relay_rx_bytes+" + relayRx)
                    .setSql("relay_tx_bytes=relay_tx_bytes+" + relayTx)
                    .set("updated_time", now));
            return;
        }

        String state = StringUtils.defaultIfBlank(data.getString("state"), "probing");
        String path = StringUtils.defaultIfBlank(data.getString("accessPath"), route.getActiveAccessPath());
        String detail = StringUtils.abbreviate(StringUtils.defaultIfBlank(data.getString("detail"), message.getString("message")), 500);
        String previousPath = route.getActiveAccessPath();
        route.setNatState(state);
        route.setActiveAccessPath(path);
        route.setNatType(StringUtils.defaultIfBlank(data.getString("natType"), route.getNatType()));
        route.setLastNatProbeAt(now);
        boolean direct = "direct".equals(state);
        boolean failed = "relay".equals(state) || "failed".equals(state);
        if (direct) {
            route.setDirectSuccessCount(defaultZero(route.getDirectSuccessCount()) + 1);
            route.setLastNatError(null);
        } else if (failed) {
            route.setDirectFailureCount(defaultZero(route.getDirectFailureCount()) + 1);
            route.setLastNatError(detail);
        }
        boolean switched = !Objects.equals(previousPath, path) || "NatPathChanged".equals(type);
        if (switched) {
            route.setLastPathSwitchAt(now);
        }
        route.setUpdatedTime(now);
        UpdateWrapper<HomeProxyRoute> update = new UpdateWrapper<HomeProxyRoute>().eq("id", route.getId())
                .set("nat_state", state).set("active_access_path", path)
                .set("nat_type", route.getNatType()).set("last_nat_probe_at", now)
                .set("last_nat_error", route.getLastNatError()).set("updated_time", now);
        if (direct) update.setSql("direct_success_count=direct_success_count+1");
        if (failed) update.setSql("direct_failure_count=direct_failure_count+1");
        if (switched) update.set("last_path_switch_at", now);
        routeMapper.update(null, update);
        record(route, eventType(type, state), path, detail);
    }

    @Scheduled(initialDelay = 20_000L, fixedDelay = 30_000L)
    public void reconcile() {
        long cutoff = System.currentTimeMillis() - RETRY_COOLDOWN_MS;
        List<HomeProxyRoute> routes = routeMapper.selectList(new QueryWrapper<HomeProxyRoute>()
                .eq("access_mode", "smart_nat").eq("state", "active")
                .and(q -> q.isNull("active_access_path").or().ne("active_access_path", "udp_direct"))
                .and(q -> q.isNull("last_nat_probe_at").or().lt("last_nat_probe_at", cutoff))
                .orderByAsc("last_nat_probe_at").last("LIMIT 3"));
        for (HomeProxyRoute route : routes) {
            if (WebSocketServer.isConnectorOnline(route.getConnectorId())
                    && WebSocketServer.isConnectorOnline(route.getSourceConnectorId())) schedule(route);
        }
    }

    private void startSession(HomeProxyRoute route) {
        if (route == null) return;
        InternalConnector home = connectorMapper.selectById(route.getConnectorId());
        InternalConnector source = connectorMapper.selectById(route.getSourceConnectorId());
        PortPool ingress = route.getIngressPoolId() == null ? null : poolMapper.selectById(route.getIngressPoolId());
        if (home == null || source == null || ingress == null || route.getPublicPort() == null) {
            fallback(route, "智能 NAT 配置不完整，已保持公网中继");
            return;
        }
        if (!WebSocketServer.isConnectorOnline(home.getId()) || !WebSocketServer.isConnectorOnline(source.getId())) {
            fallback(route, "来源设备或家庭设备离线，已保持公网中继");
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        String fallbackAddress = hostPort(ingress.getPublicHost(), route.getPublicPort());
        setProbing(route, "正在进行 STUN 探测和 UDP 直连协商");

        JSONObject homePrepare = preparePayload(route, sessionId, token, "home");
        homePrepare.put("backendAddress", "127.0.0.1:" + route.getNatBackendPort());
        GostDto homeResult = WebSocketServer.sendConnectorMsg(home.getId(), homePrepare, "NatPrepare", 20);
        JSONObject homeData = responseData(homeResult);
        if (homeData == null) {
            stop(route);
            fallback(route, "家庭端 STUN 探测失败：" + responseMessage(homeResult));
            return;
        }

        JSONObject sourcePrepare = preparePayload(route, sessionId, token, "source");
        sourcePrepare.put("listenAddress", "127.0.0.1:" + route.getSourceListenPort());
        sourcePrepare.put("fallbackAddress", fallbackAddress);
        GostDto sourceResult = WebSocketServer.sendConnectorMsg(source.getId(), sourcePrepare, "NatPrepare", 20);
        JSONObject sourceData = responseData(sourceResult);
        if (sourceData == null) {
            stop(route);
            fallback(route, "来源端 STUN 探测失败：" + responseMessage(sourceResult));
            return;
        }

        JSONObject homeConnect = connectPayload(route, sessionId, token, "controlled", sourceData);
        JSONObject sourceConnect = connectPayload(route, sessionId, token, "controlling", homeData);
        sourceConnect.put("expectedFingerprint", homeData.getString("certificateFingerprint"));
        GostDto accept = WebSocketServer.sendConnectorMsg(home.getId(), homeConnect, "NatConnect", 8);
        GostDto dial = WebSocketServer.sendConnectorMsg(source.getId(), sourceConnect, "NatConnect", 8);
        if (!ok(accept) || !ok(dial)) {
            stop(route);
            fallback(route, "UDP 直连启动失败，已切换中继：" + (!ok(accept) ? responseMessage(accept) : responseMessage(dial)));
            return;
        }
        route.setNatType(sourceData.getString("natType") + " / " + homeData.getString("natType"));
        route.setLastNatProbeAt(System.currentTimeMillis());
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.update(null, new UpdateWrapper<HomeProxyRoute>().eq("id", route.getId())
                .set("nat_type", route.getNatType())
                .set("last_nat_probe_at", route.getLastNatProbeAt())
                .set("updated_time", route.getUpdatedTime()));
        record(route, "probe", "probing", "候选地址交换完成，等待 UDP 直连结果");
    }

    private JSONObject preparePayload(HomeProxyRoute route, String sessionId, String token, String role) {
        JSONObject payload = new JSONObject();
        payload.put("routeId", route.getId());
        payload.put("sessionId", sessionId);
        payload.put("role", role);
        payload.put("token", token);
        payload.put("stunServers", DEFAULT_STUN_SERVERS);
        payload.put("connectTimeoutSeconds", 5);
        return payload;
    }

    private JSONObject connectPayload(HomeProxyRoute route, String sessionId, String token, String role, JSONObject remote) {
        JSONObject payload = new JSONObject();
        payload.put("routeId", route.getId());
        payload.put("sessionId", sessionId);
        payload.put("role", role);
        payload.put("token", token);
        payload.put("remoteUsernameFragment", remote.getString("usernameFragment"));
        payload.put("remotePassword", remote.getString("password"));
        payload.put("remoteCandidates", remote.getJSONArray("candidates"));
        payload.put("connectTimeoutSeconds", 5);
        return payload;
    }

    private void setProbing(HomeProxyRoute route, String detail) {
        route.setNatState("probing");
        route.setActiveAccessPath("relay");
        route.setLastNatProbeAt(System.currentTimeMillis());
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.update(null, new UpdateWrapper<HomeProxyRoute>().eq("id", route.getId())
                .set("nat_state", route.getNatState())
                .set("active_access_path", route.getActiveAccessPath())
                .set("last_nat_probe_at", route.getLastNatProbeAt())
                .set("updated_time", route.getUpdatedTime()));
        record(route, "probe", "relay", detail);
    }

    private void fallback(HomeProxyRoute route, String error) {
        String fallbackError = ensureFallback(route);
        boolean available = fallbackError == null;
        route.setNatState(available ? "relay" : "failed");
        route.setActiveAccessPath("relay");
        String detail = available ? error : error + "；本地中继入口启动失败：" + fallbackError;
        route.setLastNatError(StringUtils.abbreviate(detail, 500));
        route.setDirectFailureCount(defaultZero(route.getDirectFailureCount()) + 1);
        route.setLastNatProbeAt(System.currentTimeMillis());
        route.setLastPathSwitchAt(System.currentTimeMillis());
        route.setUpdatedTime(System.currentTimeMillis());
        routeMapper.update(null, new UpdateWrapper<HomeProxyRoute>().eq("id", route.getId())
                .set("nat_state", route.getNatState())
                .set("active_access_path", route.getActiveAccessPath())
                .set("last_nat_error", route.getLastNatError())
                .setSql("direct_failure_count=direct_failure_count+1")
                .set("last_nat_probe_at", route.getLastNatProbeAt())
                .set("last_path_switch_at", route.getLastPathSwitchAt())
                .set("updated_time", route.getUpdatedTime()));
        record(route, available ? "fallback" : "failed", "relay", detail);
    }

    private String ensureFallback(HomeProxyRoute route) {
        if (route.getSourceConnectorId() == null || route.getSourceListenPort() == null
                || route.getIngressPoolId() == null || route.getPublicPort() == null
                || !WebSocketServer.isConnectorOnline(route.getSourceConnectorId())) return "公司接入设备离线";
        PortPool ingress = poolMapper.selectById(route.getIngressPoolId());
        if (ingress == null) return "公网入口端口池不存在";
        Map<String, Object> payload = Map.of(
                "routeId", route.getId(),
                "listenAddress", "127.0.0.1:" + route.getSourceListenPort(),
                "fallbackAddress", hostPort(ingress.getPublicHost(), route.getPublicPort())
        );
        GostDto result = WebSocketServer.sendConnectorMsg(route.getSourceConnectorId(), payload, "NatFallback", 8);
        return ok(result) ? null : responseMessage(result);
    }

    private void record(HomeProxyRoute route, String eventType, String path, String detail) {
        HomeProxyNatEvent event = new HomeProxyNatEvent();
        event.setRouteId(route.getId());
        event.setUserId(route.getUserId());
        event.setEventType(eventType);
        event.setAccessPath(path);
        event.setDetail(StringUtils.abbreviate(detail, 500));
        event.setCreatedTime(System.currentTimeMillis());
        eventMapper.insert(event);
        List<HomeProxyNatEvent> expired = eventMapper.selectList(new QueryWrapper<HomeProxyNatEvent>()
                .select("id").eq("route_id", route.getId())
                .orderByDesc("created_time", "id").last("LIMIT 100, 100"));
        expired.forEach(item -> eventMapper.deleteById(item.getId()));
    }

    private void submit(Runnable task, Long routeId) {
        try {
            executor.execute(task);
        } catch (RuntimeException error) {
            log.warn("NAT route {} activation queue is full: {}", routeId, error.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private JSONObject responseData(GostDto result) {
        if (!ok(result) || result.getData() == null) return null;
        return result.getData() instanceof JSONObject ? (JSONObject) result.getData()
                : JSONObject.parseObject(JSONObject.toJSONString(result.getData()));
    }

    private boolean ok(GostDto result) { return result != null && "OK".equals(result.getMsg()); }
    private String responseMessage(GostDto result) { return result == null ? "Agent 无响应" : StringUtils.defaultIfBlank(result.getMsg(), "Agent 无响应"); }
    private long defaultZero(Long value) { return value == null ? 0L : value; }
    private long number(JSONObject data, String key, Long fallback) { Long value = data.getLong(key); return value == null ? defaultZero(fallback) : value; }
    private String eventType(String type, String state) {
        if ("direct".equals(state)) return "direct_connected";
        if ("relay".equals(state)) return "fallback";
        return StringUtils.defaultIfBlank(type, "status").replace("Nat", "").toLowerCase();
    }
    private String hostPort(String host, int port) { return host != null && host.contains(":") && !host.startsWith("[") ? "[" + host + "]:" + port : host + ":" + port; }
    private boolean canAccess(HomeProxyRoute route) {
        return route != null && (Objects.equals(JwtUtil.getRoleIdFromToken(), 0)
                || Objects.equals(route.getUserId(), JwtUtil.getUserIdFromToken()));
    }
}
