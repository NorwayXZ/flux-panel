package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = WebSocketLargeMessageIntegrationTests.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "LOG_DIR=/tmp/cloudnest-websocket-test-logs")
class WebSocketLargeMessageIntegrationTests {
    private static final int PAYLOAD_SIZE = 3 * 1024 * 1024;

    @Value("${local.server.port}")
    private int port;

    @Test
    void acceptsProductionSizedTextMessageEndToEnd() throws Exception {
        CompletableFuture<Integer> receivedSize = new CompletableFuture<>();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.doHandshake(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                receivedSize.complete(Integer.parseInt(message.getPayload()));
            }
        }, "ws://127.0.0.1:" + port + "/large-message").get(10, TimeUnit.SECONDS);

        try {
            session.sendMessage(new TextMessage("x".repeat(PAYLOAD_SIZE)));
            assertEquals(PAYLOAD_SIZE, receivedSize.get(20, TimeUnit.SECONDS));
        } finally {
            session.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import(WebSocketTestConfiguration.class)
    static class TestApplication {
    }

    @Configuration
    @EnableWebSocket
    static class WebSocketTestConfiguration implements WebSocketConfigurer {
        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(largeMessageHandler(), "/large-message").setAllowedOrigins("*");
        }

        @Bean
        WebSocketHandler largeMessageHandler() {
            return new TextWebSocketHandler() {
                @Override
                public void afterConnectionEstablished(WebSocketSession session) {
                    WebSocketServer.configureMessageLimits(session);
                }

                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                    session.sendMessage(new TextMessage(Integer.toString(message.getPayloadLength())));
                }
            };
        }
    }
}
