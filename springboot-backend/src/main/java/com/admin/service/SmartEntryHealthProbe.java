package com.admin.service;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Probes only the configured entry address/port; never follows redirects or fetches arbitrary URLs. */
final class SmartEntryHealthProbe {
    static void validate(String mode, String path) {
        if (!Set.of("tcp", "tls", "http", "https").contains(mode)) throw new IllegalArgumentException("不支持的业务检测方式");
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.length() > 255
                || !path.chars().allMatch(c -> c > 32 && c < 127) || path.contains("#")) {
            throw new IllegalArgumentException("检测路径应为 / 或 /health 等不含空格的相对路径");
        }
    }

    static void verify(Socket connected, String mode, String hostname, String path, int timeout) throws IOException {
        validate(mode, path);
        if ("tcp".equals(mode)) return;
        connected.setSoTimeout(timeout);
        if ("tls".equals(mode) || "https".equals(mode)) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(connected, hostname, connected.getPort(), true)) {
                tls.setSoTimeout(timeout);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                if ("https".equals(mode)) verifyHttp(tls, hostname, path, timeout);
            }
        } else verifyHttp(connected, hostname, path, timeout);
    }

    private static void verifyHttp(Socket socket, String hostname, String path, int timeout) throws IOException {
        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + hostname + ":" + socket.getPort()
                + "\r\nUser-Agent: Flux-Health-Probe\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        InputStream input = socket.getInputStream();
        StringBuilder line = new StringBuilder();
        long deadline = System.nanoTime() + timeout * 1_000_000L;
        while (line.length() < 1024) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) throw new IOException("HTTP 状态行读取超时");
            socket.setSoTimeout((int) Math.max(1, remaining));
            int value = input.read();
            if (value < 0) throw new IOException("HTTP 响应不完整");
            if (value == '\n') {
                if (!line.toString().trim().matches("HTTP/1\\.[01] 2[0-9]{2}(?: .*)?"))
                    throw new IOException("HTTP 健康检查未返回 2xx：" + line.toString().trim());
                return;
            }
            line.append((char) value);
        }
        throw new IOException("HTTP 状态行过长");
    }
}
