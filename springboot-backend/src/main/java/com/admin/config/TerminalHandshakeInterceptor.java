package com.admin.config;

import com.admin.service.TerminalSessionManager;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class TerminalHandshakeInterceptor implements HandshakeInterceptor {
    private final TerminalSessionManager terminalSessionManager;

    public TerminalHandshakeInterceptor(TerminalSessionManager terminalSessionManager) {
        this.terminalSessionManager = terminalSessionManager;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) return false;
        String ticketValue = ((ServletServerHttpRequest) request).getServletRequest().getParameter("ticket");
        TerminalSessionManager.TerminalTicket ticket = terminalSessionManager.consumeTicket(ticketValue);
        if (ticket == null) return false;
        attributes.put("terminalTicket", ticket);
        attributes.put("terminalSessionId", ticket.getSessionId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
