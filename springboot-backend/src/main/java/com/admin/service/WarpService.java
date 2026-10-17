package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.AgentVersionUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloudflare WARP as a free exit. The panel registers one free WARP account per node with
 * Cloudflare's client API and sends the WireGuard settings to the node router, which can then
 * use "warp" like any other exit.
 */
@Slf4j
@Service
public class WarpService {
    public static final String EXIT_KEY = "warp";
    static final String REGISTER_URL = "https://api.cloudflareclient.com/v0a2158/reg";
    static final String PEER_ADDRESS = "162.159.192.1";
    static final int PEER_PORT = 2408;
    static final String API_BASE = "https://api.cloudflareclient.com/v0a2158/reg";
    /** Cloudflare WARP endpoints tried when picking the best one for a node; some networks block 2408. */
    static final List<String> ENDPOINT_CANDIDATES = List.of("162.159.192.1:2408", "188.114.96.1:2408", "188.114.97.1:2408",
            "188.114.99.1:2408", "162.159.192.1:500", "162.159.192.1:1701", "162.159.192.1:854", "162.159.195.1:2408");
    static final java.util.regex.Pattern LICENSE = java.util.regex.Pattern.compile("^[A-Za-z0-9]{8}-[A-Za-z0-9]{8}-[A-Za-z0-9]{8}$");

    private final JdbcTemplate jdbc;
    private final NodeMapper nodeMapper;
    private final AESCrypto crypto;
    private final ProxyRoutingService routing;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WarpService(JdbcTemplate jdbc, NodeMapper nodeMapper, @Lazy ProxyRoutingService routing,
                       @Value("${jwt-secret}") String secret) {
        this.routing = routing;
        this.jdbc = jdbc;
        this.nodeMapper = nodeMapper;
        this.crypto = new AESCrypto(secret + ":warp");
    }

    /** The node's WARP WireGuard endpoint (without tag), registering an account on first use. */
    public synchronized JSONObject endpoint(Long nodeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM warp_account WHERE node_id = ?", nodeId);
        if (rows.isEmpty()) {
            register(nodeId);
            rows = jdbc.queryForList("SELECT * FROM warp_account WHERE node_id = ?", nodeId);
        }
        Map<String, Object> row = rows.get(0);
        List<String> addresses = new ArrayList<>();
        addresses.add(row.get("address_v4") + "/32");
        if (row.get("address_v6") != null) addresses.add(row.get("address_v6") + "/128");
        JSONObject peer = new JSONObject(true);
        peer.put("address", row.get("peer_address"));
        peer.put("port", ((Number) row.get("peer_port")).intValue());
        peer.put("public_key", row.get("peer_public_key"));
        peer.put("allowed_ips", List.of("0.0.0.0/0", "::/0"));
        peer.put("reserved", JSON.parseArray((String) row.get("reserved"), Integer.class));
        peer.put("persistent_keepalive_interval", 25);
        JSONObject endpoint = new JSONObject(true);
        endpoint.put("type", "wireguard");
        endpoint.put("mtu", 1280);
        endpoint.put("address", addresses);
        endpoint.put("private_key", crypto.decryptString((String) row.get("private_key")));
        endpoint.put("peers", List.of(peer));
        return endpoint;
    }

    private void register(Long nodeId) {
        byte[][] keys = keyPair();
        String privateKey = Base64.getEncoder().encodeToString(keys[0]);
        String publicKey = Base64.getEncoder().encodeToString(keys[1]);
        JSONObject body = new JSONObject(true);
        body.put("key", publicKey);
        body.put("install_id", "");
        body.put("fcm_token", "");
        body.put("tos", Instant.now().toString());
        body.put("model", "PC");
        body.put("serial_number", "");
        body.put("locale", "en_US");
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(REGISTER_URL)).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "okhttp/3.12.1").header("CF-Client-Version", "a-6.30-3596")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString())).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("连接 Cloudflare 注册 WARP 失败：" + e.getClass().getSimpleName());
        }
        if (response.statusCode() != 200) throw new IllegalStateException("Cloudflare 拒绝了 WARP 注册（HTTP " + response.statusCode() + "）");
        Account account = parseRegistration(response.body());
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO warp_account (node_id,account_id,private_key,public_key,address_v4,address_v6,reserved,peer_public_key,"
                        + "peer_address,peer_port,created_time,token,account_type) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                nodeId, account.id, crypto.encrypt(privateKey), publicKey, account.addressV4, account.addressV6,
                JSON.toJSONString(account.reserved), account.peerPublicKey, PEER_ADDRESS, PEER_PORT, now,
                account.token == null ? null : crypto.encrypt(account.token), account.accountType);
        // A WARP+ license set for other nodes also applies to new accounts.
        String license = savedLicense();
        if (license != null) {
            try {
                bindLicense(nodeId, license);
            } catch (IllegalStateException e) {
                log.warn("Binding WARP+ license on node {} failed: {}", nodeId, e.getMessage());
            }
        }
    }

    static final class Account {
        String id;
        String token;
        String accountType;
        String addressV4;
        String addressV6;
        List<Integer> reserved;
        String peerPublicKey;
    }

    /** Reads the registration answer: addresses, the peer key and the reserved bytes (client_id). */
    static Account parseRegistration(String text) {
        JSONObject json = JSON.parseObject(text);
        JSONObject config = json == null ? null : json.getJSONObject("config");
        if (config == null) throw new IllegalStateException("Cloudflare 返回的 WARP 配置不完整");
        Account account = new Account();
        account.id = json.getString("id");
        account.token = json.getString("token");
        JSONObject info = json.getJSONObject("account");
        account.accountType = info == null ? null : info.getString("account_type");
        JSONObject addresses = config.getJSONObject("interface").getJSONObject("addresses");
        account.addressV4 = addresses.getString("v4");
        account.addressV6 = addresses.getString("v6");
        JSONArray peers = config.getJSONArray("peers");
        account.peerPublicKey = peers.getJSONObject(0).getString("public_key");
        byte[] clientId = Base64.getDecoder().decode(config.getString("client_id"));
        account.reserved = new ArrayList<>();
        for (int i = 0; i < 3; i++) account.reserved.add(i < clientId.length ? clientId[i] & 0xff : 0);
        if (account.addressV4 == null || account.peerPublicKey == null) throw new IllegalStateException("Cloudflare 返回的 WARP 配置不完整");
        return account;
    }

    /** A Curve25519 key pair as raw 32-byte private and public keys. */
    static byte[][] keyPair() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            byte[] privateEncoded = pair.getPrivate().getEncoded();
            byte[] publicEncoded = pair.getPublic().getEncoded();
            return new byte[][]{Arrays.copyOfRange(privateEncoded, privateEncoded.length - 32, privateEncoded.length),
                    Arrays.copyOfRange(publicEncoded, publicEncoded.length - 32, publicEncoded.length)};
        } catch (Exception e) {
            throw new IllegalStateException("生成 WARP 密钥失败：" + e.getMessage());
        }
    }

    /** Connects through the node's WARP and records the address Cloudflare reports. */
    public R test(Long nodeId) {
        Node node = nodeId == null ? null : nodeMapper.selectById(nodeId);
        if (node == null || !visible(node.getId())) return R.err("节点不存在");
        return testOn(node);
    }

    /** Tests WARP on a node the caller already chose, e.g. while testing an exit group. */
    R testOn(Node node) {
        if (!WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), ProxyExitService.WARP_AGENT_VERSION)) {
            return R.err("WARP 需要节点 Agent " + ProxyExitService.WARP_AGENT_VERSION + " 或更高版本");
        }
        JSONObject endpoint;
        try {
            endpoint = endpoint(node.getId());
        } catch (IllegalStateException e) {
            return R.err(e.getMessage());
        }
        GostDto response = WebSocketServer.send_msg(node.getId(), Map.of("endpoint", endpoint), "TestProxyExit", 25);
        long now = System.currentTimeMillis();
        if (response == null || !"OK".equals(response.getMsg())) {
            String error = response == null ? "节点无响应" : response.getMsg();
            jdbc.update("UPDATE warp_account SET last_test_at=?, last_test_ok=0, fail_count=fail_count+1, last_error=? WHERE node_id=?", now,
                    error.length() > 500 ? error.substring(0, 500) : error, node.getId());
            return R.err("WARP 自检失败：" + explainRuntimeError(error));
        }
        JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
        jdbc.update("UPDATE warp_account SET last_test_at=?, last_test_ok=1, last_test_ip=?, last_test_country=?, last_test_latency=?, "
                + "fail_count=0, last_error=NULL WHERE node_id=?", now, data.getString("ip"), data.getString("country"), data.getInteger("latencyMs"), node.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ip", data.getString("ip"));
        result.put("country", data.getString("country"));
        result.put("latencyMs", data.getInteger("latencyMs"));
        result.put("nodeName", node.getName());
        return R.ok(result);
    }

    /**
     * Agents before 2.82.2 downloaded a sing-box build that needs glibc; on systems without it the
     * file exists but "no such file or directory" comes back when it starts.
     */
    public static String explainRuntimeError(String error) {
        if (error != null && error.contains("sing-box") && error.contains("no such file or directory")) {
            return "节点系统缺少 glibc，旧版 Agent 下载的 sing-box 无法运行。请把节点 Agent 升级到 2.82.2 或更高版本，"
                    + "会自动换成不依赖系统库的版本。（原始错误：" + error + "）";
        }
        return error;
    }

    /** Every node with its WARP state. */
    public List<Map<String, Object>> status() {
        Map<Long, Map<String, Object>> accounts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM warp_account")) {
            accounts.put(((Number) row.get("node_id")).longValue(), row);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Node node : nodeMapper.selectList(null)) {
            if (!visible(node.getId())) continue;
            Map<String, Object> row = accounts.get(node.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", node.getId());
            item.put("nodeName", node.getName());
            item.put("online", WebSocketServer.isNodeOnline(node.getId()));
            item.put("supported", AgentVersionUtil.isAtLeast(node.getVersion(), ProxyExitService.WARP_AGENT_VERSION));
            item.put("registered", row != null);
            if (row != null) {
                item.put("addressV6", row.get("address_v6"));
                item.put("endpoint", row.get("peer_address") + ":" + row.get("peer_port"));
                item.put("accountType", row.get("account_type"));
                item.put("licensed", row.get("license_key") != null);
                item.put("usedBy", usedBy(node.getId()));
                item.put("lastTestAt", row.get("last_test_at"));
                item.put("lastTestOk", row.get("last_test_ok") == null ? null : ((Number) row.get("last_test_ok")).intValue() == 1);
                item.put("lastTestIp", row.get("last_test_ip"));
                item.put("lastTestCountry", row.get("last_test_country"));
                item.put("lastTestLatency", row.get("last_test_latency"));
                item.put("lastError", explainRuntimeError((String) row.get("last_error")));
            }
            result.add(item);
        }
        return result;
    }

    /** Running proxies on the node whose routing sends something to WARP. */
    private long usedBy(Long nodeId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM private_proxy WHERE node_id = ? AND state = 'active' "
                + "AND (exit_type = 'warp' OR route_rules LIKE '%\"warp\"%')", Long.class, nodeId);
        return count == null ? 0 : count;
    }

    // ---- Endpoint selection ----

    /**
     * Tries every candidate endpoint from the node and keeps the fastest that works. Routers on
     * the node are re-applied when the endpoint changes.
     */
    public R optimize(Long nodeId) {
        Node node = nodeId == null ? null : nodeMapper.selectById(nodeId);
        if (node == null || !visible(node.getId())) return R.err("节点不存在");
        return optimizeOn(node);
    }

    R optimizeOn(Node node) {
        if (!WebSocketServer.isNodeOnline(node.getId())) return R.err("节点离线");
        if (!AgentVersionUtil.isAtLeast(node.getVersion(), ProxyExitService.WARP_AGENT_VERSION)) {
            return R.err("WARP 需要节点 Agent " + ProxyExitService.WARP_AGENT_VERSION + " 或更高版本");
        }
        JSONObject base;
        try {
            base = endpoint(node.getId());
        } catch (IllegalStateException e) {
            return R.err(e.getMessage());
        }
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> best = null;
        for (String candidate : ENDPOINT_CANDIDATES) {
            String address = candidate.substring(0, candidate.lastIndexOf(':'));
            int port = Integer.parseInt(candidate.substring(candidate.lastIndexOf(':') + 1));
            JSONObject endpoint = withPeer(base, address, port);
            GostDto response = WebSocketServer.send_msg(node.getId(), Map.of("endpoint", endpoint), "TestProxyExit", 20);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("endpoint", candidate);
            boolean ok = response != null && "OK".equals(response.getMsg());
            item.put("ok", ok);
            if (ok) {
                JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
                item.put("latencyMs", data.getInteger("latencyMs"));
                item.put("ip", data.getString("ip"));
                item.put("country", data.getString("country"));
                if (best == null || data.getInteger("latencyMs") < (Integer) best.get("latencyMs")) best = item;
            } else {
                item.put("error", response == null ? "节点无响应" : response.getMsg());
            }
            results.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", results);
        if (best == null) {
            result.put("changed", false);
            return R.err("所有 WARP 接入点都连不上，节点所在网络可能屏蔽了 WARP");
        }
        String chosen = (String) best.get("endpoint");
        String address = chosen.substring(0, chosen.lastIndexOf(':'));
        int port = Integer.parseInt(chosen.substring(chosen.lastIndexOf(':') + 1));
        JSONObject peer = base.getJSONArray("peers").getJSONObject(0);
        boolean changed = !address.equals(peer.getString("address")) || port != peer.getIntValue("port");
        jdbc.update("UPDATE warp_account SET peer_address=?, peer_port=?, last_test_at=?, last_test_ok=1, last_test_ip=?, "
                        + "last_test_country=?, last_test_latency=?, fail_count=0, last_error=NULL WHERE node_id=?",
                address, port, System.currentTimeMillis(), best.get("ip"), best.get("country"), best.get("latencyMs"), node.getId());
        if (changed) routing.reapplyOnNodeUsingWarp(node.getId());
        result.put("changed", changed);
        result.put("endpoint", chosen);
        result.put("latencyMs", best.get("latencyMs"));
        return R.ok(result);
    }

    static JSONObject withPeer(JSONObject endpoint, String address, int port) {
        JSONObject copy = JSON.parseObject(endpoint.toJSONString());
        JSONObject peer = copy.getJSONArray("peers").getJSONObject(0);
        peer.put("address", address);
        peer.put("port", port);
        return copy;
    }

    // ---- Self-repair ----

    /**
     * Every 30 minutes, tests WARP on nodes that have an account. After two failures in a row it
     * picks another endpoint, and if none works it registers a fresh account. Failures that remain
     * show up in the alert centre.
     */
    @Scheduled(fixedDelay = 1_800_000L, initialDelay = 600_000L)
    public void checkAll() {
        try {
            for (Map<String, Object> row : jdbc.queryForList("SELECT node_id, fail_count FROM warp_account")) {
                Long nodeId = ((Number) row.get("node_id")).longValue();
                Node node = nodeMapper.selectById(nodeId);
                if (node == null || !WebSocketServer.isNodeOnline(nodeId)
                        || !AgentVersionUtil.isAtLeast(node.getVersion(), ProxyExitService.WARP_AGENT_VERSION)) continue;
                if (testOn(node).getCode() == 0) continue;
                Integer failures = jdbc.queryForObject("SELECT fail_count FROM warp_account WHERE node_id = ?", Integer.class, nodeId);
                if (failures == null || failures < 2) continue;
                if (optimizeOn(node).getCode() == 0) {
                    log.info("WARP on node {} recovered by switching endpoint", nodeId);
                    continue;
                }
                repair(node);
            }
        } catch (RuntimeException e) {
            log.warn("WARP check skipped: {}", e.getMessage());
        }
    }

    /** Registers a fresh account for the node and re-applies its routers; keeps the failure if that does not help. */
    private void repair(Node node) {
        Map<String, Object> old = jdbc.queryForList("SELECT * FROM warp_account WHERE node_id = ?", node.getId()).stream().findFirst().orElse(null);
        try {
            reRegister(node.getId());
        } catch (IllegalStateException e) {
            log.warn("Re-registering WARP on node {} failed: {}", node.getId(), e.getMessage());
            return;
        }
        R tested = testOn(node);
        if (tested.getCode() != 0 && old != null) {
            jdbc.update("UPDATE warp_account SET fail_count=?, last_error=? WHERE node_id=?", ((Number) old.get("fail_count")).intValue() + 1,
                    "自动修复（更换接入点、重新注册）后仍不可用：" + tested.getMsg(), node.getId());
        }
        routing.reapplyOnNodeUsingWarp(node.getId());
    }

    // ---- WARP+ license ----

    /** Binds a WARP+ license to every node's account (a license allows a limited number of devices). */
    public R applyLicense(String license) {
        String key = license == null ? "" : license.trim();
        if (!LICENSE.matcher(key).matches()) return R.err("许可证格式不正确，应为 xxxxxxxx-xxxxxxxx-xxxxxxxx");
        List<Map<String, Object>> results = new ArrayList<>();
        int bound = 0;
        for (Map<String, Object> row : jdbc.queryForList("SELECT node_id FROM warp_account")) {
            Long nodeId = ((Number) row.get("node_id")).longValue();
            Node node = nodeMapper.selectById(nodeId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeName", node == null ? "#" + nodeId : node.getName());
            try {
                item.put("accountType", bindLicense(nodeId, key));
                item.put("ok", true);
                bound++;
            } catch (IllegalStateException e) {
                item.put("ok", false);
                item.put("error", e.getMessage());
            }
            results.add(item);
        }
        if (results.isEmpty()) return R.err("还没有节点用过 WARP；先在任意节点上自检一次，再绑定许可证");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bound", bound);
        result.put("results", results);
        return R.ok(result);
    }

    /** Returns the account type after binding. Accounts registered before tokens were kept are registered again. */
    String bindLicense(Long nodeId, String license) {
        Map<String, Object> row = jdbc.queryForList("SELECT * FROM warp_account WHERE node_id = ?", nodeId).stream().findFirst().orElse(null);
        if (row == null) throw new IllegalStateException("节点还没有 WARP 账号");
        if (row.get("token") == null) {
            jdbc.update("DELETE FROM warp_account WHERE node_id = ?", nodeId);
            register(nodeId);
            routing.reapplyOnNodeUsingWarp(nodeId);
            row = jdbc.queryForList("SELECT * FROM warp_account WHERE node_id = ?", nodeId).get(0);
        }
        String token = crypto.decryptString((String) row.get("token"));
        String path = API_BASE + "/" + row.get("account_id") + "/account";
        HttpResponse<String> response = call(HttpRequest.newBuilder(URI.create(path))
                .PUT(HttpRequest.BodyPublishers.ofString(new JSONObject(Map.of("license", license)).toJSONString())), token);
        if (response.statusCode() != 200) {
            String message = response.statusCode() + "";
            try {
                message = JSON.parseObject(response.body()).getJSONArray("errors").getJSONObject(0).getString("message");
            } catch (Exception ignored) {
                // Keep the status code.
            }
            throw new IllegalStateException("Cloudflare 拒绝了许可证：" + message);
        }
        HttpResponse<String> account = call(HttpRequest.newBuilder(URI.create(path)).GET(), token);
        String type = account.statusCode() == 200 ? JSON.parseObject(account.body()).getString("account_type") : null;
        jdbc.update("UPDATE warp_account SET license_key=?, account_type=? WHERE node_id=?", crypto.encrypt(license), type, nodeId);
        return type;
    }

    private String savedLicense() {
        List<String> keys = jdbc.queryForList("SELECT license_key FROM warp_account WHERE license_key IS NOT NULL LIMIT 1", String.class);
        return keys.isEmpty() ? null : crypto.decryptString(keys.get(0));
    }

    private HttpResponse<String> call(HttpRequest.Builder builder, String token) {
        try {
            return http.send(builder.timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                    .header("User-Agent", "okhttp/3.12.1").header("CF-Client-Version", "a-6.30-3596")
                    .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("连接 Cloudflare 失败：" + e.getClass().getSimpleName());
        }
    }

    /** Admins see every node; other users the nodes that host their proxies. */
    private boolean visible(Long nodeId) {
        if (java.util.Objects.equals(com.admin.common.utils.JwtUtil.getRoleIdFromToken(), 0)) return true;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM private_proxy WHERE node_id = ? AND user_id = ? AND state <> 'deleted'",
                Long.class, nodeId, com.admin.common.utils.JwtUtil.getUserIdFromToken());
        return count != null && count > 0;
    }

    /** Drops a node's account so the next use registers a fresh one, e.g. after Cloudflare blocks it. */
    public R reset(Long nodeId) {
        try {
            reRegister(nodeId);
        } catch (IllegalStateException e) {
            return R.err(e.getMessage());
        }
        return R.ok();
    }

    /** Replaces the node's account with a new one, keeping its WARP+ license if it had one. */
    private void reRegister(Long nodeId) {
        List<String> licenses = jdbc.queryForList("SELECT license_key FROM warp_account WHERE node_id = ? AND license_key IS NOT NULL",
                String.class, nodeId);
        jdbc.update("DELETE FROM warp_account WHERE node_id = ?", nodeId);
        register(nodeId);
        if (!licenses.isEmpty()) {
            Integer bound = jdbc.queryForObject("SELECT COUNT(*) FROM warp_account WHERE node_id = ? AND license_key IS NOT NULL",
                    Integer.class, nodeId);
            if (bound == null || bound == 0) {
                try {
                    bindLicense(nodeId, crypto.decryptString(licenses.get(0)));
                } catch (IllegalStateException e) {
                    log.warn("Re-binding WARP+ license on node {} failed: {}", nodeId, e.getMessage());
                }
            }
        }
    }
}
