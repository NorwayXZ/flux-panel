package com.admin.config;

import com.admin.common.utils.WebSocketServer;
import com.admin.common.utils.TerminalWebSocketHandler;
import com.admin.service.TerminalSessionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import javax.annotation.Resource;


@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private WebSocketInterceptor webSocketInterceptor;

    @Resource
    private TerminalHandshakeInterceptor terminalHandshakeInterceptor;

    @Resource
    private TerminalSessionManager terminalSessionManager;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry webSocketHandlerRegistry) {
        webSocketHandlerRegistry
                .addHandler(myHandler(), "/system-info")
                .setAllowedOrigins("*")
                .addInterceptors(webSocketInterceptor);
        webSocketHandlerRegistry
                .addHandler(terminalHandler(), "/terminal")
                .setAllowedOrigins("*")
                .addInterceptors(terminalHandshakeInterceptor);
    }


    @Bean
    public WebSocketHandler myHandler() {
        return new WebSocketServer();
    }

    @Bean
    public WebSocketHandler terminalHandler() {
        return new TerminalWebSocketHandler(terminalSessionManager);
    }

}
