package com.admin.service;

import com.admin.common.dto.CnixSharedRelayDto;
import com.admin.common.dto.GostDto;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TunnelRouteUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** One dedicated relay listener per tunnel; mutation locks serialize complete grant snapshots. */
@Service
public class CnixSharedRelayService {
    @Value("${jwt-secret}")
    private String encryptionSecret;

    private final JdbcTemplate jdbc;
    private final NodeMapper nodes;
    private final PortLedgerService ledger;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public CnixSharedRelayService(JdbcTemplate jdbc, NodeMapper nodes, PortLedgerService ledger) {
        this.jdbc = jdbc;
        this.nodes = nodes;
        this.ledger = ledger;
    }

    public boolean isShared(Tunnel tunnel) {
        return tunnel != null
                && tunnel.getId() != null
                && !jdbc.queryForList(
                                "SELECT tunnel_id FROM cnix_shared_relay WHERE tunnel_id=?",
                                tunnel.getId())
                        .isEmpty();
    }

    @Scheduled(initialDelay = 30000, fixedDelay = 30000)
    public void reconcileExitPolicies() {
        for (var row :
                jdbc.queryForList(
                        "SELECT r.tunnel_id,t.in_node_id,t.out_node_id FROM cnix_shared_relay r"
                                + " JOIN tunnel t ON t.id=r.tunnel_id")) {
            long id = ((Number) row.get("tunnel_id")).longValue();
            if (!WebSocketServer.isNodeOnline(((Number) row.get("out_node_id")).longValue()))
                continue;
            synchronized (locks.computeIfAbsent(id, ignored -> new Object())) {
                Tunnel tunnel = new Tunnel();
                tunnel.setId(id);
                tunnel.setInNodeId(((Number) row.get("in_node_id")).longValue());
                tunnel.setOutNodeId(((Number) row.get("out_node_id")).longValue());
                try {
                    ensureExit(tunnel);
                    pushGrants(tunnel);
                } catch (RuntimeException error) {
                    jdbc.update(
                            "UPDATE cnix_shared_relay SET state='error',last_error=? WHERE"
                                    + " tunnel_id=?",
                            StringUtils.abbreviate(error.getMessage(), 500),
                            id);
                }
            }
        }
    }

    public void recordProbe(Tunnel tunnel, GostDto probe) {
        jdbc.update(
                "UPDATE cnix_shared_relay SET state=?,last_error=? WHERE tunnel_id=?",
                ok(probe) ? "verified" : "handshake-pending",
                ok(probe)
                        ? null
                        : StringUtils.abbreviate(
                                probe == null ? "入口Agent没有返回握手结果" : probe.getMsg(), 500),
                tunnel.getId());
    }

    private Map<String, Object> resource(long id) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM cnix_shared_relay WHERE tunnel_id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("CNIX共享隧道不存在");
        var row = rows.get(0);
        row.put(
                "key_pem",
                new AESCrypto(encryptionSecret).decryptString(row.get("key_pem").toString()));
        row.put(
                "auth_seed",
                new AESCrypto(encryptionSecret).decryptString(row.get("auth_seed").toString()));
        return row;
    }

    public void enrich(Tunnel tunnel) {
        if (tunnel == null || tunnel.getId() == null) return;
        var rows =
                jdbc.queryForList(
                        "SELECT service_port,external_address,state,last_error FROM"
                                + " cnix_shared_relay WHERE tunnel_id=?",
                        tunnel.getId());
        if (rows.isEmpty()) {
            tunnel.setSharedRelay(null);
            return;
        }
        var row = rows.get(0);
        var dto = new CnixSharedRelayDto();
        dto.setServicePort(((Number) row.get("service_port")).intValue());
        dto.setExternalAddress((String) row.get("external_address"));
        dto.setState((String) row.get("state"));
        dto.setLastError((String) row.get("last_error"));
        tunnel.setSharedRelay(dto);
    }

    public void prepare(Tunnel tunnel, CnixSharedRelayDto dto) {
        if (tunnel.getType() != 2
                || TunnelRouteUtil.parseNodePath(tunnel).size() != 2
                || !"tls".equals(tunnel.getProtocol()))
            throw new IllegalArgumentException("CNIX共享隧道首版仅支持两个节点和relay/TLS传输");
        requireCapableAgent(tunnel.getInNodeId());
        requireCapableAgent(tunnel.getOutNodeId());
        Node out = nodes.selectById(tunnel.getOutNodeId());
        Integer port = dto.getServicePort();
        if (port == null) {
            for (int candidate = out.getPortSta(); candidate <= out.getPortEnd(); candidate++)
                if (!Boolean.TRUE.equals(ledger.diagnose(out.getId(), candidate).get("occupied"))) {
                    port = candidate;
                    break;
                }
        }
        if (port == null
                || port < out.getPortSta()
                || port > out.getPortEnd()
                || Boolean.TRUE.equals(ledger.diagnose(out.getId(), port).get("occupied")))
            throw new IllegalArgumentException("共享出口端口不在节点范围内或已占用");
        String external = normalizeEndpoint(dto.getExternalAddress(), true);
        var portCheck =
                com.admin.common.utils.AgentPortCheckUtil.check(
                        out,
                        List.of(
                                new com.admin.common.utils.AgentPortCheckUtil.Check(
                                        "tcp", "0.0.0.0", port)));
        if (!portCheck.isAvailable())
            throw new IllegalArgumentException("共享出口端口已被系统占用：" + portCheck.getMessage());
        var certificate =
                send(out.getId(), Map.of("name", serverName(tunnel.getId())), "CnixCertificate");
        JSONObject cert = (JSONObject) JSON.toJSON(certificate.getData());
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        jdbc.update(
                "INSERT INTO"
                    + " cnix_shared_relay(tunnel_id,out_node_id,service_port,external_address,cert_pem,key_pem,auth_seed,state,created_time)"
                    + " VALUES(?,?,?,?,?,?,?,'mapping-required',?)",
                tunnel.getId(),
                out.getId(),
                port,
                external,
                cert.getString("certPem"),
                new AESCrypto(encryptionSecret).encrypt(cert.getString("keyPem")),
                new AESCrypto(encryptionSecret)
                        .encrypt(Base64.getUrlEncoder().withoutPadding().encodeToString(random)),
                System.currentTimeMillis());
        try {
            ensureExit(tunnel);
        } catch (RuntimeException e) {
            jdbc.update("DELETE FROM cnix_shared_relay WHERE tunnel_id=?", tunnel.getId());
            throw e;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                var result =
                                        dispatch(
                                                tunnel.getOutNodeId(),
                                                Map.of(
                                                        "services",
                                                        List.of(serviceName(tunnel.getId()))),
                                                "DeleteService");
                                if (!ok(result))
                                    LoggerFactory.getLogger(CnixSharedRelayService.class)
                                            .warn(
                                                    "CNIX shared staging cleanup failed"
                                                            + " tunnelId={}",
                                                    tunnel.getId());
                            }
                        }
                    });
    }

    public void update(Tunnel tunnel, CnixSharedRelayDto dto) {
        synchronized (locks.computeIfAbsent(tunnel.getId(), ignored -> new Object())) {
            var row = resource(tunnel.getId());
            if (dto.getServicePort() != null
                    && dto.getServicePort() != ((Number) row.get("service_port")).intValue())
                throw new IllegalArgumentException("共享服务端口不能直接修改，请创建新的共享隧道迁移");
            String external = normalizeEndpoint(dto.getExternalAddress(), true);
            if (!Objects.equals(external, row.get("external_address"))) {
                Integer count =
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cnix_shared_grant WHERE tunnel_id=?",
                                Integer.class,
                                tunnel.getId());
                if (count != null && count > 0)
                    throw new IllegalArgumentException("请先删除关联业务再更换CNIX外部映射，避免中断其他业务");
                jdbc.update(
                        "UPDATE cnix_shared_relay SET"
                            + " external_address=?,state='mapping-required',last_error=NULL WHERE"
                            + " tunnel_id=?",
                        external,
                        tunnel.getId());
            }
        }
    }

    private static String serverName(long id) {
        return "cnix-" + id + ".invalid";
    }

    private static String serviceName(long id) {
        return "cnixshared-" + id;
    }

    private static String password(Map<String, Object> row, long id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            row.get("auth_seed").toString().getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mac.doFinal(Long.toString(id).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> grants(long id) {
        return grants(id, -1L);
    }

    private List<Map<String, Object>> grants(long id, long resumingForwardId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row :
                jdbc.queryForList(
                        "SELECT g.username,g.password,g.targets,u.exp_time AS user_expiry,CASE WHEN"
                            + " u.role_id=0 OR t.owner_user_id=f.user_id THEN NULL ELSE p.exp_time"
                            + " END AS grant_expiry FROM cnix_shared_grant g JOIN forward f ON"
                            + " f.id=g.forward_id JOIN tunnel t ON t.id=g.tunnel_id JOIN user u ON"
                            + " u.id=f.user_id LEFT JOIN user_tunnel p ON p.user_id=f.user_id AND"
                            + " p.tunnel_id=g.tunnel_id WHERE g.tunnel_id=? AND g.enabled=1 AND"
                            + " (f.status=1 OR f.id=?) AND u.status=1 AND (u.exp_time IS NULL OR"
                            + " u.exp_time>?) AND (u.role_id=0 OR t.owner_user_id=f.user_id OR"
                            + " (p.status=1 AND (p.exp_time IS NULL OR p.exp_time>?))) ORDER BY"
                            + " g.forward_id",
                        id,
                        resumingForwardId,
                        System.currentTimeMillis(),
                        System.currentTimeMillis())) {
            long expiry = Long.MAX_VALUE;
            for (String column : List.of("user_expiry", "grant_expiry"))
                if (row.get(column) instanceof Number value)
                    expiry = Math.min(expiry, value.longValue());
            result.add(
                    Map.of(
                            "username",
                            row.get("username"),
                            "password",
                            new AESCrypto(encryptionSecret)
                                    .decryptString(row.get("password").toString()),
                            "targets",
                            JSON.parseArray(row.get("targets").toString()),
                            "expiresAt",
                            expiry == Long.MAX_VALUE ? 0 : expiry));
        }
        return result;
    }

    private void pushGrants(Tunnel tunnel) {
        send(
                tunnel.getOutNodeId(),
                Map.of("service", serviceName(tunnel.getId()), "grants", grants(tunnel.getId())),
                "CnixGrants");
    }

    private void pushResumingGrant(Tunnel tunnel, long forwardId) {
        send(
                tunnel.getOutNodeId(),
                Map.of(
                        "service",
                        serviceName(tunnel.getId()),
                        "grants",
                        grants(tunnel.getId(), forwardId)),
                "CnixGrants");
    }

    public void ensureExit(Tunnel tunnel) {
        requireCapableAgent(tunnel.getOutNodeId());
        var row = resource(tunnel.getId());
        JSONObject tls = new JSONObject();
        tls.put("certPem", row.get("cert_pem"));
        tls.put("keyPem", row.get("key_pem"));
        JSONObject metadata = new JSONObject();
        metadata.put("cnix.shared", true);
        metadata.put("cnix.grants", grants(tunnel.getId()));
        metadata.put("nodelay", true);
        JSONObject service = new JSONObject();
        service.put("name", serviceName(tunnel.getId()));
        service.put("addr", "0.0.0.0:" + row.get("service_port"));
        // Legacy relay handlers ignore cnix.shared metadata. A credential which is
        // never issued to ingress prevents an accidental binary rollback from
        // turning this listener into an anonymous dynamic proxy.
        service.put(
                "handler",
                Map.of(
                        "type",
                        "relay",
                        "metadata",
                        metadata,
                        "auth",
                        Map.of(
                                "username",
                                "cnix-disabled-fallback",
                                "password",
                                password(row, 0))));
        service.put("listener", Map.of("type", "tls", "tls", tls));
        var response = dispatch(tunnel.getOutNodeId(), List.of(service), "AddService");
        if (!ok(response)
                && (response == null
                        || response.getMsg() == null
                        || !response.getMsg().contains("already exists"))) check(response);
    }

    public void bindBusiness(Tunnel tunnel, Forward forward, String chainName, String targets) {
        synchronized (locks.computeIfAbsent(tunnel.getId(), ignored -> new Object())) {
            requireCapableAgent(tunnel.getInNodeId());
            var row = resource(tunnel.getId());
            String external = normalizeEndpoint((String) row.get("external_address"), false);
            List<String> allowed = normalizeTargets(targets);
            var old =
                    jdbc.queryForList(
                            "SELECT * FROM cnix_shared_grant WHERE tunnel_id=? AND forward_id=?",
                            tunnel.getId(),
                            forward.getId());
            String user = "f" + forward.getId();
            String secret = password(row, forward.getId());
            ensureExit(tunnel);
            jdbc.update(
                    "INSERT INTO"
                        + " cnix_shared_grant(tunnel_id,forward_id,username,password,targets,enabled)"
                        + " VALUES(?,?,?,?,?,1) ON DUPLICATE KEY UPDATE"
                        + " targets=VALUES(targets),enabled=1",
                    tunnel.getId(),
                    forward.getId(),
                    user,
                    new AESCrypto(encryptionSecret).encrypt(secret),
                    JSON.toJSONString(allowed));
            try {
                pushGrants(tunnel);
                JSONObject chain =
                        chain(
                                chainName,
                                external,
                                user,
                                secret,
                                (String) row.get("cert_pem"),
                                serverName(tunnel.getId()));
                var response = dispatch(tunnel.getInNodeId(), chain, "AddChains");
                if (!ok(response)) {
                    if (response != null
                            && response.getMsg() != null
                            && response.getMsg().contains("already exists"))
                        send(
                                tunnel.getInNodeId(),
                                Map.of("chain", chainName + "_chains", "data", chain),
                                "UpdateChains");
                    else check(response);
                }
                var probe =
                        dispatch(
                                tunnel.getInNodeId(),
                                Map.of("chain", chainName + "_chains", "target", allowed.get(0)),
                                "CnixProbe");
                recordProbe(tunnel, probe);
            } catch (RuntimeException e) {
                restoreGrant(tunnel, forward.getId(), old);
                jdbc.update(
                        "UPDATE cnix_shared_relay SET state='error',last_error=? WHERE tunnel_id=?",
                        StringUtils.abbreviate(e.getMessage(), 500),
                        tunnel.getId());
                throw e;
            }
        }
    }

    private void restoreGrant(Tunnel tunnel, long forwardId, List<Map<String, Object>> old) {
        jdbc.update(
                "DELETE FROM cnix_shared_grant WHERE tunnel_id=? AND forward_id=?",
                tunnel.getId(),
                forwardId);
        if (!old.isEmpty()) {
            var row = old.get(0);
            jdbc.update(
                    "INSERT INTO"
                        + " cnix_shared_grant(tunnel_id,forward_id,username,password,targets,enabled)"
                        + " VALUES(?,?,?,?,?,?)",
                    tunnel.getId(),
                    forwardId,
                    row.get("username"),
                    row.get("password"),
                    row.get("targets"),
                    row.get("enabled"));
        }
        pushGrants(tunnel);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeBusiness(Tunnel tunnel, long forwardId, String chainName) {
        synchronized (locks.computeIfAbsent(tunnel.getId(), ignored -> new Object())) {
            resource(tunnel.getId());
            var old =
                    jdbc.queryForList(
                            "SELECT * FROM cnix_shared_grant WHERE tunnel_id=? AND forward_id=?",
                            tunnel.getId(),
                            forwardId);
            jdbc.update(
                    "DELETE FROM cnix_shared_grant WHERE tunnel_id=? AND forward_id=?",
                    tunnel.getId(),
                    forwardId);
            try {
                pushGrants(tunnel);
            } catch (RuntimeException e) {
                restoreGrant(tunnel, forwardId, old);
                throw e;
            }
            GostUtil.DeleteChains(tunnel.getInNodeId(), chainName);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeUser(Tunnel tunnel, int userId) {
        synchronized (locks.computeIfAbsent(tunnel.getId(), ignored -> new Object())) {
            resource(tunnel.getId());
            var old =
                    jdbc.queryForList(
                            "SELECT * FROM cnix_shared_grant WHERE tunnel_id=? AND forward_id"
                                    + " IN(SELECT id FROM forward WHERE user_id=?)",
                            tunnel.getId(),
                            userId);
            if (old.isEmpty()) return;
            jdbc.update(
                    "DELETE FROM cnix_shared_grant WHERE tunnel_id=? AND forward_id IN(SELECT id"
                            + " FROM forward WHERE user_id=?)",
                    tunnel.getId(),
                    userId);
            try {
                pushGrants(tunnel);
            } catch (RuntimeException error) {
                for (var row : old)
                    restoreGrant(
                            tunnel, ((Number) row.get("forward_id")).longValue(), List.of(row));
                throw error;
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void pauseBusiness(Tunnel tunnel, long forwardId, boolean pause) {
        synchronized (locks.computeIfAbsent(tunnel.getId(), ignored -> new Object())) {
            resource(tunnel.getId());
            jdbc.update(
                    "UPDATE cnix_shared_grant SET enabled=? WHERE tunnel_id=? AND forward_id=?",
                    !pause,
                    tunnel.getId(),
                    forwardId);
            try {
                if (pause) pushGrants(tunnel);
                else pushResumingGrant(tunnel, forwardId);
            } catch (RuntimeException e) {
                jdbc.update(
                        "UPDATE cnix_shared_grant SET enabled=? WHERE tunnel_id=? AND forward_id=?",
                        pause,
                        tunnel.getId(),
                        forwardId);
                throw e;
            }
        }
    }

    public void delete(Tunnel tunnel) {
        if (!isShared(tunnel)) return;
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM forward WHERE tunnel_id=?",
                        Integer.class,
                        tunnel.getId());
        if (count != null && count > 0)
            throw new IllegalArgumentException("共享隧道仍有" + count + "条业务，请先迁移或删除业务");
        check(
                dispatch(
                        tunnel.getOutNodeId(),
                        Map.of("services", List.of(serviceName(tunnel.getId()))),
                        "DeleteService"));
        jdbc.update("DELETE FROM cnix_shared_grant WHERE tunnel_id=?", tunnel.getId());
        jdbc.update("DELETE FROM cnix_shared_relay WHERE tunnel_id=?", tunnel.getId());
    }

    static JSONObject chain(
            String name,
            String external,
            String user,
            String password,
            String certificate,
            String serverName) {
        JSONObject tls = new JSONObject();
        tls.put("secure", true);
        tls.put("caPem", certificate);
        tls.put("serverName", serverName);
        JSONObject node = new JSONObject();
        node.put("name", name + "_node");
        node.put("addr", external);
        node.put(
                "connector",
                Map.of(
                        "type",
                        "relay",
                        "auth",
                        Map.of("username", user, "password", password),
                        "metadata",
                        Map.of("nodelay", true, "connectTimeout", "5s")));
        node.put("dialer", Map.of("type", "tls", "tls", tls));
        JSONObject chain = new JSONObject();
        chain.put("name", name + "_chains");
        chain.put("hops", List.of(Map.of("name", name + "_hop", "nodes", List.of(node))));
        return chain;
    }

    static List<String> normalizeTargets(String targets) {
        if (targets == null || targets.isBlank()) throw new IllegalArgumentException("请填写落地地址");
        List<String> result = new ArrayList<>();
        for (String target : targets.split(",")) result.add(normalizeEndpoint(target, false));
        return result;
    }

    static String normalizeEndpoint(String address, boolean optional) {
        if (address == null || address.isBlank()) {
            if (optional) return null;
            throw new IllegalArgumentException("请先补录CNIX外部加速入口");
        }
        String value = address.trim();
        int separator = value.lastIndexOf(':');
        if (separator < 1 || value.indexOf(':') != separator)
            throw new IllegalArgumentException("首版仅支持IPv4或域名:端口");
        String host = value.substring(0, separator).toLowerCase(Locale.ROOT);
        int port;
        try {
            port = Integer.parseInt(value.substring(separator + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("端口无效");
        }
        if (port < 1 || port > 65535 || !host.matches("[a-z0-9][a-z0-9.-]*"))
            throw new IllegalArgumentException("加速入口或落地地址格式无效");
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".internal"))
            throw new IllegalArgumentException("禁止内网或管理地址");
        if (host.matches("[0-9.]+"))
            try {
                InetAddress ip = InetAddress.getByName(host);
                if (ip.isAnyLocalAddress()
                        || ip.isLoopbackAddress()
                        || ip.isLinkLocalAddress()
                        || ip.isSiteLocalAddress()
                        || ip.isMulticastAddress()) throw new IllegalArgumentException("禁止内网或管理地址");
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException("IP无效");
            }
        return host + ":" + port;
    }

    private static boolean ok(GostDto response) {
        return response != null && "OK".equals(response.getMsg());
    }

    private static void check(GostDto response) {
        if (!ok(response))
            throw new IllegalStateException(response == null ? "Agent没有响应" : response.getMsg());
    }

    GostDto dispatch(Long node, Object data, String method) {
        return WebSocketServer.send_msg(node, data, method);
    }

    private void requireCapableAgent(Long node) {
        var response = dispatch(node, Map.of(), "CnixCapabilities");
        var data = response == null ? null : (JSONObject) JSON.toJSON(response.getData());
        if (!ok(response) || data == null || data.getIntValue("sharedRelay") != 1)
            throw new IllegalArgumentException(
                    "节点" + node + "需要安装本次开发版Agent，已发布的旧版Agent不支持CNIX共享隧道");
    }

    private GostDto send(Long node, Object data, String method) {
        var result = dispatch(node, data, method);
        check(result);
        return result;
    }
}
