package com.admin.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VpnGateListFetcherTests {
    static String validList() {
        String cert = "-----BEGIN CERTIFICATE-----\nY2VydA==\n-----END CERTIFICATE-----\n";
        String config = "dev tun\nproto tcp\nremote 60.137.217.108 443\ncipher AES-128-CBC\nauth SHA1\n<ca>\n" + cert + "</ca>\n";
        return "*vpn_servers\n#HostName,IP\nvpn1,60.137.217.108,1000,10,1000000,Japan,JP,2,3000,1,1,2weeks,operator,,"
                + Base64.getEncoder().encodeToString(config.getBytes(StandardCharsets.UTF_8)) + "\n*\n";
    }

    @Test
    void skipsErrorPagesAndReusesTheLastWorkingSource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger badRequests = new AtomicInteger();
        AtomicInteger goodRequests = new AtomicInteger();
        server.createContext("/bad", exchange -> {
            badRequests.incrementAndGet();
            byte[] body = "<html>upstream unavailable</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/good", exchange -> {
            goodRequests.incrementAndGet();
            byte[] body = validList().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var fetcher = new VpnGateListFetcher(base + "/bad," + base + "/good");
            assertEquals("60.137.217.108", fetcher.fetch().get(0).getHost());
            assertEquals(1, fetcher.fetch().size());
            assertEquals(1, badRequests.get());
            assertEquals(2, goodRequests.get());
            assertEquals("127.0.0.1", fetcher.sourceHost());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackOnTheObservedConnectionTimeout() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> good = mock(HttpResponse.class);
        when(good.statusCode()).thenReturn(200);
        when(good.body()).thenReturn(validList());
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpConnectTimeoutException("HTTP connect timed out")).thenReturn(good);

        var fetcher = new VpnGateListFetcher("http://www.vpngate.net/api/iphone/", client, Duration.ofSeconds(1));
        assertEquals(1, fetcher.fetch().size());
        assertEquals("api.vpngate.net", fetcher.sourceHost());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allSourceFailuresStayErrorsAndDoNotSelectASource() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpConnectTimeoutException("HTTP connect timed out"));
        var fetcher = new VpnGateListFetcher("http://www.vpngate.net/api/iphone/", client, Duration.ofSeconds(1));
        IOException error = assertThrows(IOException.class, fetcher::fetch);
        assertTrue(error.getMessage().contains("api.vpngate.net"));
        assertNull(fetcher.sourceHost());
        verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptionDoesNotContinueToAnotherSource() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        var fetcher = new VpnGateListFetcher("http://www.vpngate.net/api/iphone/", client, Duration.ofSeconds(1));
        assertThrows(InterruptedException.class, fetcher::fetch);
        verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void customSourcesRemainExplicitAndInvalidUrlsAreRejected() {
        assertEquals(List.of(URI.create("https://example.test/list")), VpnGateListFetcher.sources("https://example.test/list"));
        assertEquals(3, VpnGateListFetcher.sources("http://www.vpngate.net/api/iphone/").size());
        assertThrows(IllegalArgumentException.class, () -> VpnGateListFetcher.sources("file:///tmp/list"));
        assertThrows(IllegalArgumentException.class, () -> VpnGateListFetcher.sources("https://user:password@example.test/list"));
    }
}
