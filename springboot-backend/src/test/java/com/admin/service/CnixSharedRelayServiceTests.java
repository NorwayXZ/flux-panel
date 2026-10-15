package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.admin.common.dto.CnixSharedRelayDto;
import com.admin.common.dto.GostDto;
import com.admin.config.CnixSharedRelaySchemaInitializer;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class CnixSharedRelayServiceTests {
    private final JdbcTemplate jdbc;
    private final NodeMapper nodes = mock(NodeMapper.class);
    private final PortLedgerService ledger = mock(PortLedgerService.class);
    private final CnixSharedRelayService service;
    private final Tunnel tunnel = new Tunnel();

    CnixSharedRelayServiceTests() {
        var source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:cnix"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        var initializer = new CnixSharedRelaySchemaInitializer(jdbc);
        initializer.initialize();
        initializer.initialize();
        jdbc.execute(
                "CREATE TABLE forward(id bigint PRIMARY KEY,tunnel_id bigint,user_id int,status int"
                        + " DEFAULT 1)");
        jdbc.execute(
                "CREATE TABLE tunnel(id bigint PRIMARY KEY,owner_user_id int,in_node_id"
                        + " bigint,out_node_id bigint)");
        jdbc.execute(
                "CREATE TABLE user(id int PRIMARY KEY,role_id int DEFAULT 0,status int DEFAULT"
                        + " 1,exp_time bigint)");
        jdbc.execute(
                "CREATE TABLE user_tunnel(tunnel_id bigint,user_id int,status int,exp_time"
                        + " bigint)");
        jdbc.update("INSERT INTO tunnel VALUES(7,1,1,2)");
        for (int id = 1; id <= 3; id++) {
            jdbc.update("INSERT INTO user(id) VALUES(?)", id);
            jdbc.update("INSERT INTO forward(id,tunnel_id,user_id) VALUES(?,7,?)", id, id);
        }
        service = spy(new CnixSharedRelayService(jdbc, nodes, ledger));
        ReflectionTestUtils.setField(
                service, "encryptionSecret", "cnix-test-key-long-enough-for-encryption-123456");
        tunnel.setId(7L);
        tunnel.setType(2);
        tunnel.setInNodeId(1L);
        tunnel.setOutNodeId(2L);
        tunnel.setNodePath("1,2");
        tunnel.setProtocol("tls");
        Node node = new Node();
        node.setId(2L);
        node.setPortSta(12000);
        node.setPortEnd(12010);
        node.setVersion("2.0.0");
        when(nodes.selectById(2L)).thenReturn(node);
        when(ledger.diagnose(anyLong(), anyInt())).thenReturn(Map.of("occupied", false));
    }

    private GostDto response(String message) {
        GostDto response = new GostDto();
        response.setMsg(message);
        if ("OK".equals(message)) response.setData(Map.of("sharedRelay", 1));
        return response;
    }

    private Forward business(long id) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId((int) id);
        return forward;
    }

    private CnixSharedRelayDto config(String external) {
        var config = new CnixSharedRelayDto();
        config.setServicePort(12002);
        config.setExternalAddress(external);
        return config;
    }

    @Test
    void threeBusinessesShareOneExitAndGrantsRemainIndependent() {
        List<Object> snapshots = new ArrayList<>();
        List<Object> chains = new ArrayList<>();
        boolean[] exitCreated = {false};
        {
            doAnswer(
                            call -> {
                                String method = call.getArgument(2);
                                Object payload = call.getArgument(1);
                                if (method.equals("CnixCertificate")) {
                                    var result = response("OK");
                                    result.setData(
                                            Map.of(
                                                    "certPem",
                                                    "test-certificate",
                                                    "keyPem",
                                                    "test-private-key"));
                                    return result;
                                }
                                if (method.equals("AddService")) {
                                    if (exitCreated[0]) return response("service already exists");
                                    exitCreated[0] = true;
                                    return response("OK");
                                }
                                if (method.equals("CnixGrants"))
                                    snapshots.add(JSON.parseObject(JSON.toJSONString(payload)));
                                if (method.equals("AddChains")) chains.add(payload);
                                return response("OK");
                            })
                    .when(service)
                    .dispatch(anyLong(), any(), anyString());
            service.prepare(tunnel, config("198.51.100.20:17149"));
            for (long id = 1; id <= 3; id++)
                service.bindBusiness(
                        tunnel, business(id), "business" + id, "203.0.113." + id + ":443");
            assertEquals(
                    1,
                    jdbc.queryForObject("SELECT COUNT(*) FROM cnix_shared_relay", Integer.class));
            assertEquals(
                    3,
                    jdbc.queryForObject("SELECT COUNT(*) FROM cnix_shared_grant", Integer.class));
            var last = (JSONObject) snapshots.get(snapshots.size() - 1);
            assertEquals(3, last.getJSONArray("grants").size());
            for (var chain : chains) {
                var node =
                        ((JSONObject) chain)
                                .getJSONArray("hops")
                                .getJSONObject(0)
                                .getJSONArray("nodes")
                                .getJSONObject(0);
                assertEquals("198.51.100.20:17149", node.getString("addr"));
                assertTrue(
                        node.getJSONObject("dialer")
                                .getJSONObject("tls")
                                .getBooleanValue("secure"));
            }
            assertEquals(
                    12002,
                    jdbc.queryForObject(
                            "SELECT service_port FROM cnix_shared_relay", Integer.class));
            assertFalse(
                    jdbc.queryForObject("SELECT key_pem FROM cnix_shared_relay", String.class)
                            .contains("private-key"));
            service.pauseBusiness(tunnel, 2L, true);
            jdbc.update("UPDATE forward SET status=0 WHERE id=2");
            service.pauseBusiness(tunnel, 2L, false);
            assertEquals(
                    3,
                    ((JSONObject) snapshots.get(snapshots.size() - 1))
                            .getJSONArray("grants")
                            .size());
            jdbc.update("UPDATE forward SET status=1 WHERE id=2");
            service.pauseBusiness(tunnel, 2L, true);
            assertEquals(
                    2,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM cnix_shared_grant WHERE enabled=1",
                            Integer.class));
            service.removeBusiness(tunnel, 2L, "business2");
            assertEquals(
                    2,
                    jdbc.queryForObject("SELECT COUNT(*) FROM cnix_shared_grant", Integer.class));
            assertEquals(
                    1,
                    jdbc.queryForObject("SELECT COUNT(*) FROM cnix_shared_relay", Integer.class));
            service.enrich(tunnel);
            String publicData = JSON.toJSONString(tunnel.getSharedRelay());
            assertFalse(publicData.contains("password"));
            assertFalse(publicData.contains("keyPem"));
            assertEquals("verified", tunnel.getSharedRelay().getState());
        }
    }

    @Test
    void failedGrantPushRestoresOriginalBusinessAndDoesNotRebuildExit() {
        boolean[] fail = {false};
        List<Object> snapshots = new ArrayList<>();
        {
            doAnswer(
                            call -> {
                                String method = call.getArgument(2);
                                if (method.equals("CnixCertificate")) {
                                    var response = response("OK");
                                    response.setData(
                                            Map.of("certPem", "certificate", "keyPem", "private"));
                                    return response;
                                }
                                if (method.equals("CnixGrants")) {
                                    snapshots.add(call.getArgument(1));
                                    if (fail[0]) {
                                        fail[0] = false;
                                        return response("simulated snapshot rejection");
                                    }
                                }
                                return response("OK");
                            })
                    .when(service)
                    .dispatch(anyLong(), any(), anyString());
            service.prepare(tunnel, config("198.51.100.20:17149"));
            service.bindBusiness(tunnel, business(1), "A", "203.0.113.1:443");
            fail[0] = true;
            assertThrows(
                    IllegalStateException.class,
                    () -> service.bindBusiness(tunnel, business(1), "A", "203.0.113.2:443"));
            assertEquals(
                    "[\"203.0.113.1:443\"]",
                    jdbc.queryForObject(
                            "SELECT targets FROM cnix_shared_grant WHERE forward_id=1",
                            String.class));
            assertTrue(
                    JSON.toJSONString(snapshots.get(snapshots.size() - 1))
                            .contains("203.0.113.1:443"));
        }
    }

    @Test
    void endpointValidationAndLiveTunnelDeletionFailClosed() {
        for (String value :
                List.of(
                        "127.0.0.1:80",
                        "10.1.2.3:22",
                        "169.254.169.254:80",
                        "host:0",
                        "host:65536",
                        "::1:443",
                        "bad/path:443"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CnixSharedRelayService.normalizeTargets(value));
        assertEquals(
                "landing.example:443",
                CnixSharedRelayService.normalizeTargets("landing.example:443").get(0));
        jdbc.update(
                "INSERT INTO cnix_shared_relay"
                    + " VALUES(7,2,12002,NULL,'cert','cipher','cipher','mapping-required',NULL,1)");
        assertThrows(IllegalArgumentException.class, () -> service.delete(tunnel));
    }

    @Test
    void concurrentBusinessChangesDoNotLoseOtherTargetsAndRevocationIsScoped() throws Exception {
        List<JSONObject> snapshots = Collections.synchronizedList(new ArrayList<>());
        doAnswer(
                        call -> {
                            String method = call.getArgument(2);
                            var result = response("OK");
                            if (method.equals("CnixCertificate"))
                                result.setData(
                                        Map.of("certPem", "certificate", "keyPem", "private"));
                            if (method.equals("CnixGrants"))
                                snapshots.add(
                                        JSON.parseObject(JSON.toJSONString(call.getArgument(1))));
                            return result;
                        })
                .when(service)
                .dispatch(anyLong(), any(), anyString());
        service.prepare(tunnel, config("198.51.100.20:17149"));
        var workers = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (long id = 1; id <= 3; id++) {
                long businessId = id;
                futures.add(
                        workers.submit(
                                () ->
                                        service.bindBusiness(
                                                tunnel,
                                                business(businessId),
                                                "business" + businessId,
                                                "203.0.113." + businessId + ":443")));
            }
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        assertEquals(
                3, jdbc.queryForObject("SELECT COUNT(*) FROM cnix_shared_grant", Integer.class));
        assertEquals(3, snapshots.get(snapshots.size() - 1).getJSONArray("grants").size());
        service.revokeUser(tunnel, 2);
        assertEquals(
                2, jdbc.queryForObject("SELECT COUNT(*) FROM cnix_shared_grant", Integer.class));
        assertEquals(2, snapshots.get(snapshots.size() - 1).getJSONArray("grants").size());
        jdbc.update("UPDATE user SET role_id=1 WHERE id=3");
        service.pauseBusiness(tunnel, 1, false);
        var valid = snapshots.get(snapshots.size() - 1).getJSONArray("grants");
        assertEquals(1, valid.size());
        assertEquals("f1", valid.getJSONObject(0).getString("username"));
    }

    @Test
    void legacyAgentCannotCreateAnAnonymousSharedExit() {
        doReturn(response("unknown command"))
                .when(service)
                .dispatch(anyLong(), any(), eq("CnixCapabilities"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.prepare(tunnel, config("198.51.100.20:17149")));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM cnix_shared_relay", Integer.class));
        verify(service, never()).dispatch(anyLong(), any(), eq("AddService"));
    }

    @Test
    void migrationFileIsRepeatableAlongsideStartupInitializer() throws Exception {
        String sql = Files.readString(Path.of("../migrations/20261015_cnix_shared_relay.sql"));
        for (String command : sql.split(";")) if (!command.isBlank()) jdbc.execute(command);
        for (String command : sql.split(";")) if (!command.isBlank()) jdbc.execute(command);
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM cnix_shared_grant", Integer.class));
    }
}
