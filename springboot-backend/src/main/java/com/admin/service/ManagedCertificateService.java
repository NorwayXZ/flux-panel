package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.SniRouteTargetDto;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.time.Duration;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ManagedCertificateService {
    private static final String ACME_DIRECTORY = "https://acme-v02.api.letsencrypt.org/directory";
    private static final long RENEW_BEFORE_MS = 30L * 86_400_000L;
    private static final long RETRY_DELAY_MS = 15L * 60_000L;

    private final JdbcTemplate jdbcTemplate;
    private final DnsProviderService dnsProviderService;
    private final ConcurrentHashMap<Long, Boolean> running = new ConcurrentHashMap<>();

    @Value("${jwt-secret}")
    private String encryptionSecret;

    public ManagedCertificateService(JdbcTemplate jdbcTemplate, DnsProviderService dnsProviderService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dnsProviderService = dnsProviderService;
    }

    public long ensureCertificate(long zoneId, String domain) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT id FROM managed_certificate WHERE zone_id=? AND domain=?", zoneId, domain);
        if (!existing.isEmpty()) return number(existing.get(0).get("id"));
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO managed_certificate (zone_id,domain,state,next_attempt_at,created_time,updated_time) "
                        + "VALUES (?,?,'pending',?,?,?)",
                zoneId, domain, now, now, now);
        return Objects.requireNonNull(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @Async
    public void provisionAsync(long certificateId) {
        provision(certificateId);
    }

    @Scheduled(initialDelay = 30_000L, fixedDelay = 60_000L)
    public void maintainCertificates() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,state FROM managed_certificate WHERE "
                        + "((state IN ('pending','failed','renewal_failed','deployment_failed') AND (next_attempt_at IS NULL OR next_attempt_at<=?)) "
                        + "OR (state='active' AND expires_at IS NOT NULL AND expires_at<=?)) ORDER BY updated_time LIMIT 10",
                now, now + RENEW_BEFORE_MS);
        for (Map<String, Object> row : rows) {
            long id = number(row.get("id"));
            if ("deployment_failed".equals(row.get("state"))) {
                try {
                    deployCertificate(id);
                } catch (RuntimeException ignored) {
                }
            } else {
                provisionAsync(id);
            }
        }
    }

    public void deployForRoute(long routeId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT certificate_id AS certificateId FROM domain_route WHERE id=?", routeId);
        if (!rows.isEmpty() && rows.get(0).get("certificateId") != null) {
            deployCertificate(number(rows.get(0).get("certificateId")));
        }
    }

    public void reconfigureEntry(long nodeId, int listenPort, String serviceName) {
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_route WHERE node_id=? AND listen_port=? AND ingress_mode='managed_https' AND state<>'deleted'",
                Integer.class, nodeId, listenPort);
        if (remaining == null || remaining == 0) {
            GostDto result = GostUtil.DeleteDomainIngress(nodeId, serviceName);
            if (!success(result) && !StringUtils.containsIgnoreCase(message(result), "not found")) {
                throw new IllegalStateException(message(result));
            }
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT certificate_id AS certificateId FROM domain_route WHERE node_id=? AND listen_port=? "
                        + "AND ingress_mode='managed_https' AND state<>'deleted' AND certificate_id IS NOT NULL LIMIT 1",
                nodeId, listenPort);
        if (!rows.isEmpty()) deployCertificate(number(rows.get(0).get("certificateId")));
    }

    private void provision(long certificateId) {
        if (running.putIfAbsent(certificateId, Boolean.TRUE) != null) return;
        boolean renewal = false;
        try {
            Map<String, Object> certificate = loadCertificate(certificateId);
            String currentState = Objects.toString(certificate.get("state"));
            long expiresAt = certificate.get("expiresAt") == null ? 0L : number(certificate.get("expiresAt"));
            if (certificate.get("certificateChain") != null
                    && ("deployment_failed".equals(currentState)
                    || ("active".equals(currentState) && expiresAt > System.currentTimeMillis() + RENEW_BEFORE_MS))) {
                try {
                    deployCertificate(certificateId);
                } catch (RuntimeException ignored) {
                    // deployCertificate records the retry state and error details.
                }
                return;
            }
            renewal = certificate.get("certificateChain") != null;
            mark(certificateId, renewal ? "renewing" : "issuing", null, null);
            IssuedCertificate issued = issue(certificate);
            long now = System.currentTimeMillis();
            jdbcTemplate.update("UPDATE managed_certificate SET account_key=?,private_key=?,certificate_chain=?,issuer=?,serial_number=?,"
                            + "not_before=?,expires_at=?,state='active',last_error=NULL,last_attempt_at=?,next_attempt_at=NULL,updated_time=? WHERE id=?",
                    encrypt(issued.accountKeyPem()), encrypt(issued.privateKeyPem()), encrypt(issued.certificatePem()),
                    issued.issuer(), issued.serialNumber(), issued.notBefore(), issued.expiresAt(), now, now, certificateId);
        } catch (Exception e) {
            String message = shorten(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 500);
            log.warn("Managed certificate {} provisioning failed: {}", certificateId, message);
            mark(certificateId, renewal ? "renewal_failed" : "failed", message, System.currentTimeMillis() + RETRY_DELAY_MS);
            if (renewal) {
                jdbcTemplate.update("UPDATE domain_route SET last_error=?,updated_time=? "
                                + "WHERE certificate_id=? AND state<>'deleted'",
                        "证书续签失败，当前证书仍继续使用：" + message,
                        System.currentTimeMillis(), certificateId);
            } else {
                jdbcTemplate.update("UPDATE domain_route SET state='certificate_failed',last_error=?,updated_time=? "
                                + "WHERE certificate_id=? AND state<>'deleted'",
                        message, System.currentTimeMillis(), certificateId);
            }
            return;
        } finally {
            running.remove(certificateId);
        }
        try {
            deployCertificate(certificateId);
        } catch (RuntimeException ignored) {
            // deployCertificate records the retry state and error details.
        }
    }

    private IssuedCertificate issue(Map<String, Object> certificate) throws Exception {
        long zoneId = number(certificate.get("zoneId"));
        String domain = Objects.toString(certificate.get("domain"));
        KeyPair accountKey = readOrCreateKey(certificate.get("accountKey"));
        Session session = new Session(ACME_DIRECTORY);
        Login login = new AccountBuilder().agreeToTermsOfService().useKeyPair(accountKey).createLogin(session);
        Order order = login.getAccount().newOrder().domain(domain).create();
        for (var authorization : order.getAuthorizations()) {
            if (authorization.getStatus() == Status.VALID) continue;
            Dns01Challenge challenge = authorization.findChallenge(Dns01Challenge.class)
                    .orElseThrow(() -> new IllegalStateException("证书机构未提供 DNS-01 验证"));
            String recordName = challenge.getRRName(authorization.getIdentifier());
            String recordId = dnsProviderService.createDnsChallenge(zoneId, recordName, challenge.getDigest());
            try {
                Thread.sleep(8_000L);
                challenge.trigger();
                if (challenge.waitForCompletion(Duration.ofMinutes(2)) != Status.VALID) {
                    throw new IllegalStateException("DNS 验证未通过，请检查 Cloudflare Token 的 DNS 编辑权限");
                }
            } finally {
                try {
                    dnsProviderService.deleteDnsChallenge(zoneId, recordId);
                } catch (Exception cleanupError) {
                    log.warn("Could not remove ACME DNS challenge for {}: {}", domain, cleanupError.getMessage());
                }
            }
        }
        if (order.waitUntilReady(Duration.ofMinutes(2)) != Status.READY) {
            throw new IllegalStateException("证书订单未进入可签发状态");
        }
        KeyPair domainKey = KeyPairUtils.createKeyPair(2048);
        order.execute(domainKey);
        if (order.waitForCompletion(Duration.ofMinutes(2)) != Status.VALID) {
            throw new IllegalStateException("证书签发失败");
        }
        org.shredzone.acme4j.Certificate result = order.getCertificate();
        StringWriter certificatePem = new StringWriter();
        result.writeCertificate(certificatePem);
        X509Certificate leaf = result.getCertificate();
        return new IssuedCertificate(writeKey(accountKey), writeKey(domainKey), certificatePem.toString(),
                leaf.getIssuerX500Principal().getName(), leaf.getSerialNumber().toString(16),
                leaf.getNotBefore().getTime(), leaf.getNotAfter().getTime());
    }

    private void deployCertificate(long certificateId) {
        try {
            Map<String, Object> certificate = loadCertificate(certificateId);
            if (!List.of("active", "deployment_failed").contains(Objects.toString(certificate.get("state")))) return;
            List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                    "SELECT DISTINCT node_id AS nodeId,listen_port AS listenPort,service_name AS serviceName FROM domain_route "
                            + "WHERE certificate_id=? AND ingress_mode='managed_https' AND state<>'deleted'", certificateId);
            for (Map<String, Object> entry : entries) {
                configureEntry(number(entry.get("nodeId")), ((Number) entry.get("listenPort")).intValue(),
                        Objects.toString(entry.get("serviceName")));
            }
            jdbcTemplate.update("UPDATE managed_certificate SET state='active',last_error=NULL,next_attempt_at=NULL,updated_time=? WHERE id=?",
                    System.currentTimeMillis(), certificateId);
        } catch (Exception e) {
            String message = shorten(e.getMessage(), 500);
            mark(certificateId, "deployment_failed", message, System.currentTimeMillis() + RETRY_DELAY_MS);
            jdbcTemplate.update("UPDATE domain_route SET state='deployment_failed',last_error=?,updated_time=? "
                    + "WHERE certificate_id=? AND state<>'deleted'", message, System.currentTimeMillis(), certificateId);
            throw new IllegalStateException(message, e);
        }
    }

    private void configureEntry(long nodeId, int listenPort, String serviceName) {
        if (!WebSocketServer.isNodeOnline(nodeId)) throw new IllegalStateException("公网入口节点离线，证书将在节点恢复后自动部署");
        List<Map<String, Object>> routes = jdbcTemplate.queryForList(
                "SELECT r.id,r.domain,r.state,r.certificate_id AS certificateId,p.public_port AS publicPort,pool.bind_ip AS bindIp,"
                        + "c.private_key AS privateKey,c.certificate_chain AS certificateChain "
                        + "FROM domain_route r JOIN published_service p ON p.id=r.published_service_id "
                        + "JOIN port_pool pool ON pool.id=p.pool_id JOIN managed_certificate c ON c.id=r.certificate_id "
                        + "WHERE r.node_id=? AND r.listen_port=? AND r.ingress_mode='managed_https' AND r.state<>'deleted' "
                        + "AND c.state IN ('active','deployment_failed') "
                        + "ORDER BY r.created_time", nodeId, listenPort);
        if (routes.isEmpty()) return;
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Map<String, Object> route : routes) {
            payload.add(Map.of("name", certificateName(number(route.get("certificateId"))),
                    "certPem", decrypt(Objects.toString(route.get("certificateChain"))),
                    "keyPem", decrypt(Objects.toString(route.get("privateKey")))));
        }
        GostDto deployment = GostUtil.DeployCertificates(nodeId, payload);
        if (!success(deployment)) throw new IllegalStateException("证书下发失败：" + message(deployment));
        JSONObject paths = JSON.parseObject(JSON.toJSONString(deployment.getData()));
        List<Map<String, Object>> tlsCertificates = new ArrayList<>();
        List<SniRouteTargetDto> targets = new ArrayList<>();
        for (Map<String, Object> route : routes) {
            long certId = number(route.get("certificateId"));
            JSONObject certPaths = paths.getJSONObject(certificateName(certId));
            if (certPaths == null) throw new IllegalStateException("Agent 未返回证书文件位置");
            tlsCertificates.add(Map.of("names", List.of(Objects.toString(route.get("domain"))),
                    "certFile", certPaths.getString("certFile"), "keyFile", certPaths.getString("keyFile")));
            String bindIp = StringUtils.trimToEmpty(Objects.toString(route.get("bindIp"), ""));
            String host = bindIp.isBlank() || List.of("0.0.0.0", "::", "[::]").contains(bindIp) ? "127.0.0.1" : bindIp;
            if (host.contains(":")) host = "[" + host.replace("[", "").replace("]", "") + "]";
            targets.add(new SniRouteTargetDto(number(route.get("id")), Objects.toString(route.get("domain")),
                    host + ":" + route.get("publicPort")));
        }
        boolean update = routes.stream().anyMatch(route -> "active".equals(route.get("state")));
        GostDto configured = GostUtil.ConfigureManagedHttpsIngress(nodeId, serviceName, "", listenPort, targets, tlsCertificates, update);
        if (!success(configured)) throw new IllegalStateException("HTTPS 入口配置失败：" + message(configured));
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE domain_route SET state='active',last_error=NULL,updated_time=? "
                + "WHERE node_id=? AND listen_port=? AND ingress_mode='managed_https' AND state<>'deleted'", now, nodeId, listenPort);
    }

    private Map<String, Object> loadCertificate(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,zone_id AS zoneId,domain,account_key AS accountKey,private_key AS privateKey,"
                        + "certificate_chain AS certificateChain,expires_at AS expiresAt,state FROM managed_certificate WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("托管证书不存在");
        return rows.get(0);
    }

    private KeyPair readOrCreateKey(Object encrypted) throws Exception {
        if (encrypted == null || StringUtils.isBlank(encrypted.toString())) return KeyPairUtils.createKeyPair(2048);
        return KeyPairUtils.readKeyPair(new StringReader(decrypt(encrypted.toString())));
    }

    private String writeKey(KeyPair keyPair) throws Exception {
        StringWriter writer = new StringWriter();
        KeyPairUtils.writeKeyPair(keyPair, writer);
        return writer.toString();
    }

    private void mark(long id, String state, String error, Long nextAttempt) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE managed_certificate SET state=?,last_error=?,last_attempt_at=?,next_attempt_at=?,updated_time=? WHERE id=?",
                state, error, now, nextAttempt, now, id);
    }

    private String encrypt(String value) { return new AESCrypto(encryptionSecret).encrypt(value); }
    private String decrypt(String value) { return new AESCrypto(encryptionSecret).decryptString(value); }
    private long number(Object value) { return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString()); }
    private String certificateName(long id) { return "managed_" + id; }
    private boolean success(GostDto value) { return value != null && "OK".equals(value.getMsg()); }
    private String message(GostDto value) { return value == null ? "Agent 无响应" : Objects.toString(value.getMsg(), "Agent 无响应"); }
    private String shorten(String value, int length) {
        if (value == null) return null;
        return value.length() <= length ? value : value.substring(0, length);
    }

    private record IssuedCertificate(String accountKeyPem, String privateKeyPem, String certificatePem,
                                     String issuer, String serialNumber, long notBefore, long expiresAt) {}
}
