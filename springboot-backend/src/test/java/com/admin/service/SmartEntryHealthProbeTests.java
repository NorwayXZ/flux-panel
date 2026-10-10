package com.admin.service;

import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class SmartEntryHealthProbeTests {
    @Test void rejectsUrlsAndHeaderInjection() {
        for (String path : new String[]{"https://example.com/", "//example.com", "/x\r\nHost: internal", "/bad path", "/#fragment"})
            assertThrows(IllegalArgumentException.class, () -> SmartEntryHealthProbe.validate("http", path));
        assertDoesNotThrow(() -> SmartEntryHealthProbe.validate("https", "/health?probe=1"));
    }

    @Test void httpChecksApplicationResponseRatherThanJustAnOpenPort() throws Exception {
        check("HTTP/1.1 204 No Content\r\n\r\n", false);
        check("HTTP/1.1 503 Service Unavailable\r\n\r\n", true);
        check("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1/\r\n\r\n", true);
        check("not http\r\n", true);
    }

    private void check(String response, boolean fails) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(2000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(accepted.getInputStream(), StandardCharsets.US_ASCII));
                    assertEquals("GET /health HTTP/1.1", reader.readLine());
                    assertEquals("Host: example.com:" + server.getLocalPort(), reader.readLine());
                    accepted.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    accepted.getOutputStream().flush();
                } catch (IOException e) { throw new RuntimeException(e); }
            });
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                if (fails) assertThrows(IOException.class, () -> SmartEntryHealthProbe.verify(socket, "http", "example.com", "/health", 1000));
                else SmartEntryHealthProbe.verify(socket, "http", "example.com", "/health", 1000);
            }
            worker.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
