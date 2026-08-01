package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketServerTests {
    @AfterEach
    void clearConnectionState() {
        WebSocketServer.clearConnectionStateForTest();
    }

    @Test
    void configuresProductionSizedBidirectionalMessages() {
        WebSocketSession session = mock(WebSocketSession.class);

        WebSocketServer.configureMessageLimits(session);

        assertEquals(4 * 1024 * 1024, WebSocketServer.WEBSOCKET_MESSAGE_SIZE_LIMIT);
        verify(session).setTextMessageSizeLimit(4 * 1024 * 1024);
        verify(session).setBinaryMessageSizeLimit(4 * 1024 * 1024);
    }

    @Test
    void acceptsReconnectFromSameMachineIdentity() {
        WebSocketSession active = session("active", "203.0.113.10", "machine-a");
        WebSocketSession candidate = session("candidate", "198.51.100.20", "machine-a");

        assertEquals(false, WebSocketServer.isConflictingIdentity(active, candidate));
    }

    @Test
    void rejectsSimultaneousConnectionFromDifferentMachineIdentity() {
        WebSocketSession active = session("active", "203.0.113.10", "machine-a");
        WebSocketSession candidate = session("candidate", "198.51.100.20", "machine-b");

        assertEquals(true, WebSocketServer.isConflictingIdentity(active, candidate));
    }

    @Test
    void fallsBackToRealIpForLegacyAgentsWithoutMachineFingerprint() {
        WebSocketSession active = session("active", "203.0.113.10", "");
        WebSocketSession candidate = session("candidate", "198.51.100.20", "");

        assertEquals(true, WebSocketServer.isConflictingIdentity(active, candidate));
    }

    @Test
    void registeredNodeIpCanReplaceRogueSessionAfterBackendRestart() {
        WebSocketSession rogue = session("rogue", "198.51.100.20", "machine-b", "203.0.113.10");
        WebSocketSession registered = session("registered", "203.0.113.10", "machine-a", "203.0.113.10");

        assertEquals(false, WebSocketServer.isConflictingIdentity(rogue, registered));
        assertEquals(true, WebSocketServer.isConflictingIdentity(registered, rogue));
    }

    @Test
    void closingOldSessionCannotRemoveNewSession() {
        WebSocketSession oldSession = session("old", "203.0.113.10", "machine-a");
        WebSocketSession newSession = session("new", "203.0.113.10", "machine-a");
        WebSocketServer.putNodeSessionForTest(13L, newSession);

        assertEquals(false, WebSocketServer.removeNodeSession(13L, oldSession));
        assertEquals(true, WebSocketServer.isNodeOnline(13L));
        assertEquals(true, WebSocketServer.removeNodeSession(13L, newSession));
        assertEquals(false, WebSocketServer.isNodeOnline(13L));
    }

    private WebSocketSession session(String id, String remoteIp, String machine) {
        return session(id, remoteIp, machine, "");
    }

    private WebSocketSession session(String id, String remoteIp, String machine, String expectedIp) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(
                "remoteIp", remoteIp,
                "machineFingerprint", machine,
                "expectedIp", expectedIp,
                "nodeVersion", "2.42.0"
        ));
        return session;
    }
}
