package com.admin.common.utils;

import com.admin.service.TerminalSessionManager;
import com.alibaba.fastjson.JSONObject;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class TerminalWebSocketHandler extends TextWebSocketHandler {
    private final TerminalSessionManager terminalSessionManager;

    public TerminalWebSocketHandler(TerminalSessionManager terminalSessionManager) {
        this.terminalSessionManager = terminalSessionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        TerminalSessionManager.TerminalTicket ticket =
                (TerminalSessionManager.TerminalTicket) session.getAttributes().get("terminalTicket");
        if (!terminalSessionManager.openBrowserSession(ticket, session) && session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.getPayloadLength() > 100_000) return;
        try {
            JSONObject payload = JSONObject.parseObject(message.getPayload());
            terminalSessionManager.handleBrowserMessage(
                    (String) session.getAttributes().get("terminalSessionId"), payload);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        terminalSessionManager.browserDisconnected(
                (String) session.getAttributes().get("terminalSessionId"));
    }
}
