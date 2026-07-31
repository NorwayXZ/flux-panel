package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebSocketServerTests {
    @Test
    void configuresProductionSizedBidirectionalMessages() {
        WebSocketSession session = mock(WebSocketSession.class);

        WebSocketServer.configureMessageLimits(session);

        assertEquals(4 * 1024 * 1024, WebSocketServer.WEBSOCKET_MESSAGE_SIZE_LIMIT);
        verify(session).setTextMessageSizeLimit(4 * 1024 * 1024);
        verify(session).setBinaryMessageSizeLimit(4 * 1024 * 1024);
    }
}
