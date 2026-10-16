package com.admin.service;

import com.admin.entity.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelLinkCheckServiceTests {

    static final class FakeAgent implements TunnelLinkCheckService.Agent {
        final Set<Long> offline = new HashSet<>();
        final Map<String, TunnelLinkCheckService.Ping> pings = new HashMap<>();
        final Map<Long, String> listenerErrors = new HashMap<>();
        final List<String> opened = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        final Set<String> listening = new HashSet<>();

        @Override
        public boolean online(Long nodeId) {
            return !offline.contains(nodeId);
        }

        @Override
        public TunnelLinkCheckService.Ping tcpPing(Long nodeId, String host, int port) {
            TunnelLinkCheckService.Ping configured = pings.get(host + ":" + port);
            if (configured != null) return configured;
            return listening.contains(host + ":" + port)
                    ? new TunnelLinkCheckService.Ping(true, 12.5, null)
                    : new TunnelLinkCheckService.Ping(false, -1, "dial tcp " + host + ":" + port + ": i/o timeout");
        }

        @Override
        public String openListener(Long nodeId, String session, int port) {
            String error = listenerErrors.get(nodeId);
            if (error != null) return error;
            opened.add(session);
            listening.add("node" + nodeId + ":" + port);
            return null;
        }

        @Override
        public void closeListener(Long nodeId, String session) {
            closed.add(session);
        }
    }

    private static Node node(long id, String version) {
        Node node = new Node();
        node.setId(id);
        node.setName("N" + id);
        node.setServerIp("node" + id);
        node.setVersion(version);
        node.setPortSta(20000);
        node.setPortEnd(20999);
        return node;
    }

    private static TunnelLinkCheckService.Hop hop(Node from, Node to, Integer livePort, Integer previewPort) {
        return new TunnelLinkCheckService.Hop(from, to, to.getServerIp(), "public", livePort, previewPort);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> link(Map<String, Object> report, int index) {
        return ((List<Map<String, Object>>) report.get("links")).get(index);
    }

    private final Node entry = node(1, "2.66.0");
    private final Node exit = node(2, "2.66.0");

    @Test
    void probesAPortThatIsNotListeningYetWithATemporaryListener() {
        FakeAgent agent = new FakeAgent();
        agent.pings.put("www.google.com:443", new TunnelLinkCheckService.Ping(true, 30, null));
        Map<String, Object> report = new TunnelLinkCheckService(agent)
                .check(List.of(entry, exit), List.of(hop(entry, exit, null, 20005)), "tls", false);

        Map<String, Object> hop = link(report, 0);
        assertEquals("ok", hop.get("status"));
        assertEquals("listener", hop.get("probe"));
        assertEquals(20005, hop.get("port"));
        assertEquals(12.5, hop.get("latencyMs"));
        assertEquals(agent.opened, agent.closed);
        assertEquals("ok", report.get("status"));
    }

    @Test
    void aBlockedPortFailsTheLinkAndNamesTheRangeToOpen() {
        FakeAgent agent = new FakeAgent();
        agent.pings.put("node2:20005", new TunnelLinkCheckService.Ping(false, -1, "dial tcp: i/o timeout"));
        Map<String, Object> report = new TunnelLinkCheckService(agent)
                .check(List.of(entry, exit), List.of(hop(entry, exit, null, 20005)), "tls", false);

        Map<String, Object> hop = link(report, 0);
        assertEquals("fail", hop.get("status"));
        assertTrue(String.valueOf(hop.get("message")).contains("20000-20999"));
        assertEquals("fail", report.get("status"));
        assertEquals(1, agent.closed.size(), "the temporary listener is closed even when the probe fails");
    }

    @Test
    void anExistingForwardPortIsPingedWithoutOpeningAListener() {
        FakeAgent agent = new FakeAgent();
        agent.pings.put("node2:20001", new TunnelLinkCheckService.Ping(true, 8, null));
        Map<String, Object> report = new TunnelLinkCheckService(agent)
                .check(List.of(entry, exit), List.of(hop(entry, exit, 20001, null)), "tls", false);

        assertEquals("ok", link(report, 0).get("status"));
        assertEquals("live", link(report, 0).get("probe"));
        assertTrue(agent.opened.isEmpty());
    }

    @Test
    void aPortTakenByAnotherProgramIsAWarning() {
        FakeAgent agent = new FakeAgent();
        agent.listenerErrors.put(2L, "open tcp bandwidth test port 20005: bind: address already in use");
        Map<String, Object> report = new TunnelLinkCheckService(agent)
                .check(List.of(entry, exit), List.of(hop(entry, exit, null, 20005)), "tls", false);

        assertEquals("warn", link(report, 0).get("status"));
    }

    @Test
    void oldAgentsCannotProbeTheRealPort() {
        Node oldExit = node(2, "2.40.0");
        FakeAgent agent = new FakeAgent();
        Map<String, Object> report = new TunnelLinkCheckService(agent)
                .check(List.of(entry, oldExit), List.of(hop(entry, oldExit, null, 20005)), "tls", false);

        assertEquals("warn", link(report, 0).get("status"));
        assertEquals("none", link(report, 0).get("probe"));
        assertTrue(agent.opened.isEmpty());
    }

    @Test
    void anOfflineNodeFailsTheCheckAndSkipsItsLinks() {
        FakeAgent agent = new FakeAgent();
        agent.offline.add(2L);
        Map<String, Object> report = new TunnelLinkCheckService(agent)
                .check(List.of(entry, exit), List.of(hop(entry, exit, null, 20005)), "tls", false);

        assertEquals("skip", link(report, 0).get("status"));
        assertEquals("skip", link(report, 1).get("status"));
        assertEquals("fail", report.get("status"));
        assertEquals("N2 离线", report.get("message"));
    }

    @Test
    void aFullPortRangeFailsTheHop() {
        FakeAgent agent = new FakeAgent();
        Map<String, Object> report = new TunnelLinkCheckService(agent)
                .check(List.of(entry, exit), List.of(hop(entry, exit, null, null)), "tls", false);

        assertEquals("fail", link(report, 0).get("status"));
        assertNull(link(report, 0).get("port"));
    }

    @Test
    void noInternetFromTheExitIsOnlyAWarning() {
        FakeAgent agent = new FakeAgent();
        Map<String, Object> report = new TunnelLinkCheckService(agent)
                .check(List.of(entry), List.of(), null, false);

        assertEquals("internet", link(report, 0).get("kind"));
        assertEquals("warn", link(report, 0).get("status"));
        assertEquals("warn", report.get("status"));
    }

    @Test
    void timeoutsAreExplainedAsFirewallProblems() {
        assertTrue(TunnelLinkCheckService.explain("dial tcp 1.2.3.4:20005: i/o timeout", "20000-20999")
                .startsWith("连接超时"));
        assertTrue(TunnelLinkCheckService.explain("connect: connection refused", null).startsWith("连接被拒绝"));
    }
}
