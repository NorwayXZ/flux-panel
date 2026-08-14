package com.admin.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolClientProbeServiceTests {
    private final ProtocolClientProbeService service = new ProtocolClientProbeService();

    @Test
    void measuresThroughAnHttpProxyWithoutUsingAgent() throws Exception {
        try (StubProxy proxy = new StubProxy(false)) {
            Map<String, Object> result = service.probe(
                    "http", "127.0.0.1", proxy.port(), "", "",
                    "http://example.test/down", "http://example.test/up",
                    4 * 1024L, 2 * 1024L, 5_000);

            assertTrue((Boolean) result.get("success"));
            assertEquals(4 * 1024L, result.get("downloadBytes"));
            assertEquals(2 * 1024L, result.get("uploadBytes"));
            assertNotNull(result.get("handshakeMs"));
            assertEquals("panel_protocol_client", result.get("probeSource"));
        }
    }

    @Test
    void performsSocks5NegotiationAndAuthenticationPath() throws Exception {
        try (StubProxy proxy = new StubProxy(true)) {
            Map<String, Object> result = service.probe(
                    "socks5", "127.0.0.1", proxy.port(), "user", "password",
                    "http://example.test/down", "http://example.test/up",
                    4 * 1024L, 2 * 1024L, 5_000);

            assertTrue((Boolean) result.get("success"));
            assertEquals(200, result.get("downloadStatus"));
            assertEquals(200, result.get("uploadStatus"));
            assertEquals("java-http-proxy", result.get("clientEngine"));
        }
    }

    private static final class StubProxy implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final boolean socks5;

        private StubProxy(boolean socks5) throws IOException {
            this.socks5 = socks5;
            this.server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            executor.submit(this::acceptConnections);
        }

        private int port() {
            return server.getLocalPort();
        }

        private void acceptConnections() {
            try {
                while (!server.isClosed()) {
                    Socket socket = server.accept();
                    executor.submit(() -> handle(socket));
                }
            } catch (IOException ignored) {
                // Closing the test server interrupts accept().
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                if (socks5) {
                    handleSocks5(socket);
                } else {
                    handleHttp(socket);
                }
            } catch (IOException ignored) {
                // Assertions are made on the client result.
            }
        }

        private void handleHttp(Socket socket) throws IOException {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            byte[] request = readHeaders(input);
            int contentLength = contentLength(request);
            readFully(input, contentLength);
            writeResponse(output, 4 * 1024);
        }

        private void handleSocks5(Socket socket) throws IOException {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            int version = input.read();
            int methodCount = input.read();
            if (version != 5 || methodCount < 1) throw new IOException("invalid greeting");
            readFully(input, methodCount);
            output.write(new byte[]{5, 2});
            output.flush();

            if (input.read() != 1) throw new IOException("invalid auth version");
            int userLength = input.read();
            readFully(input, userLength);
            int passwordLength = input.read();
            readFully(input, passwordLength);
            output.write(new byte[]{1, 0});
            output.flush();

            if (input.read() != 5 || input.read() != 1) throw new IOException("invalid connect");
            input.read();
            int addressType = input.read();
            if (addressType == 3) {
                int hostLength = input.read();
                readFully(input, hostLength);
            } else if (addressType == 1) {
                readFully(input, 4);
            } else if (addressType == 4) {
                readFully(input, 16);
            } else {
                throw new IOException("invalid address type");
            }
            readFully(input, 2);
            output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
            output.flush();

            byte[] request = readHeaders(input);
            readFully(input, contentLength(request));
            writeResponse(output, 4 * 1024);
        }

        private void writeResponse(OutputStream output, int bytes) throws IOException {
            output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + bytes
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.write(new byte[bytes]);
            output.flush();
        }

        private byte[] readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int current;
            while ((current = input.read()) != -1) {
                output.write(current);
                byte[] bytes = output.toByteArray();
                int length = bytes.length;
                if (length >= 4 && bytes[length - 4] == '\r' && bytes[length - 3] == '\n'
                        && bytes[length - 2] == '\r' && bytes[length - 1] == '\n') {
                    return bytes;
                }
            }
            throw new IOException("request headers ended unexpectedly");
        }

        private int contentLength(byte[] headers) {
            String text = new String(headers, StandardCharsets.ISO_8859_1).toLowerCase();
            for (String line : text.split("\r\n")) {
                if (line.startsWith("content-length:")) {
                    return Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            return 0;
        }

        private byte[] readFully(InputStream input, int length) throws IOException {
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new IOException("unexpected eof");
            return bytes;
        }

        @Override
        public void close() throws Exception {
            server.close();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
