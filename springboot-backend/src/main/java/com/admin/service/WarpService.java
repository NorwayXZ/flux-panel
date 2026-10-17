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
import org.springframework.beans.factory.annotation.Value;
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
@Service
public class WarpService {
    public static final String EXIT_KEY = "warp";
    static final String REGISTER_URL = "https://api.cloudflareclient.com/v0a2158/reg";
    static final String PEER_ADDRESS = "162.159.192.1";
    static final int PEER_PORT = 2408;

    private final JdbcTemplate jdbc;
    private final NodeMapper nodeMapper;
    private final AESCrypto crypto;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WarpService(JdbcTemplate jdbc, NodeMapper nodeMapper, @Value("${jwt-secret}") String secret) {
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
                        + "peer_address,peer_port,created_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                nodeId, account.id, crypto.encrypt(privateKey), publicKey, account.addressV4, account.addressV6,
                JSON.toJSONString(account.reserved), account.peerPublicKey, PEER_ADDRESS, PEER_PORT, now);
    }

    static final class Account {
        String id;
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
            jdbc.update("UPDATE warp_account SET last_test_at=?, last_test_ok=0, last_error=? WHERE node_id=?", now,
                    error.length() > 500 ? error.substring(0, 500) : error, node.getId());
            return R.err("WARP 自检失败：" + error);
        }
        JSONObject data = JSON.parseObject(JSON.toJSONString(response.getData()));
        jdbc.update("UPDATE warp_account SET last_test_at=?, last_test_ok=1, last_test_ip=?, last_test_country=?, last_test_latency=?, "
                + "last_error=NULL WHERE node_id=?", now, data.getString("ip"), data.getString("country"), data.getInteger("latencyMs"), node.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ip", data.getString("ip"));
        result.put("country", data.getString("country"));
        result.put("latencyMs", data.getInteger("latencyMs"));
        result.put("nodeName", node.getName());
        return R.ok(result);
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
                item.put("lastTestAt", row.get("last_test_at"));
                item.put("lastTestOk", row.get("last_test_ok") == null ? null : ((Number) row.get("last_test_ok")).intValue() == 1);
                item.put("lastTestIp", row.get("last_test_ip"));
                item.put("lastTestCountry", row.get("last_test_country"));
                item.put("lastTestLatency", row.get("last_test_latency"));
                item.put("lastError", row.get("last_error"));
            }
            result.add(item);
        }
        return result;
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
        jdbc.update("DELETE FROM warp_account WHERE node_id = ?", nodeId);
        return R.ok();
    }
}
