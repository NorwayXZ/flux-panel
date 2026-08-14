package com.admin.service;

import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Executes a protocol probe from the panel host.
 *
 * The node Agent is deliberately not involved here. The Agent owns the
 * listening service; this class behaves like an external SOCKS5/HTTP client
 * and measures the connection through that service.
 */
@Service
public class ProtocolClientProbeService {
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int IO_BUFFER_BYTES = 64 * 1024;
    private static final String PROBE_SOURCE = "panel_protocol_client";
    private static final String CLIENT_ENGINE = "java-http-proxy";
    private static final String CLIENT_ENGINE_VERSION = "java-" + Runtime.version();

    public static String probeSource() {
        return PROBE_SOURCE;
    }

    public static String clientEngine() {
        return CLIENT_ENGINE;
    }

    public static String clientEngineVersion() {
        return CLIENT_ENGINE_VERSION;
    }

    public Map<String, Object> probe(String proxyType, String proxyHost, int proxyPort,
                                     String username, String password, String downloadUrl,
                                     String uploadUrl, long downloadBytes, long uploadBytes,
                                     int timeoutMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        ProbePart download = execute(proxyType, proxyHost, proxyPort, username, password,
                addBytesQuery(downloadUrl, downloadBytes), downloadBytes, false, timeoutMs);
        ProbePart upload = execute(proxyType, proxyHost, proxyPort, username, password,
                uploadUrl, uploadBytes, true, timeoutMs);

        if (download.status() != null) result.put("downloadStatus", download.status());
        if (upload.status() != null) result.put("uploadStatus", upload.status());
        result.put("downloadBytes", download.bytes());
        result.put("uploadBytes", upload.bytes());
        if (download.mbps() != null) result.put("downloadMbps", download.mbps());
        if (upload.mbps() != null) result.put("uploadMbps", upload.mbps());
        if (download.headerMs() != null) result.put("latencyMs", download.headerMs());
        if (download.handshakeMs() != null) result.put("handshakeMs", download.handshakeMs());
        if (download.error() != null) result.put("downloadError", download.error());
        if (upload.error() != null) result.put("uploadError", upload.error());

        boolean available = download.error() == null && upload.error() == null
                && download.bytes() > 0 && upload.bytes() > 0;
        result.put("available", available);
        result.put("success", available);
        if (!available) {
            if (download.error() != null) {
                result.put("error", "下载探测失败：" + download.error());
            } else if (upload.error() != null) {
                result.put("error", "上传探测失败：" + upload.error());
            } else {
                result.put("error", "协议没有返回有效测速数据");
            }
        }
        result.put("probeSource", PROBE_SOURCE);
        result.put("clientEngine", CLIENT_ENGINE);
        result.put("clientEngineVersion", CLIENT_ENGINE_VERSION);
        return result;
    }

    private ProbePart execute(String proxyType, String proxyHost, int proxyPort,
                              String username, String password, String targetUrl,
                              long expectedBytes, boolean upload, int timeoutMs) {
        long requestStarted = System.nanoTime();
        try {
            URI target = validTarget(targetUrl);
            ProxyConnection connection = openConnection(proxyType, proxyHost, proxyPort,
                    username, password, target, timeoutMs);
            try (connection) {
                OutputStream output = connection.socket().getOutputStream();
                InputStream input = connection.socket().getInputStream();
                sendRequest(output, target, proxyType, connection.plainHttpProxy(), username,
                        password, upload, expectedBytes);
                output.flush();

                HttpResponseHead response = readResponseHead(input);
                double headerMs = elapsedMs(requestStarted);
                if (response.status() < 200 || response.status() >= 400) {
                    return new ProbePart(response.status(), 0L, null, headerMs,
                            connection.handshakeMs(), "HTTP 状态码 " + response.status());
                }

                long actualBytes = upload
                        ? expectedBytes
                        : readResponseBody(input, response.headers(), expectedBytes);
                if (!upload && actualBytes <= 0) {
                    return new ProbePart(response.status(), actualBytes, null, headerMs,
                            connection.handshakeMs(), "响应没有返回有效数据");
                }
                double mbps = elapsedSeconds(requestStarted) <= 0
                        ? 0D
                        : actualBytes * 8D / elapsedSeconds(requestStarted) / 1_000_000D;
                if (upload) {
                    drainResponseBody(input, response.headers(), 1L * 1024 * 1024);
                }
                return new ProbePart(response.status(), actualBytes, mbps, headerMs,
                        connection.handshakeMs(), null);
            }
        } catch (Exception e) {
            return new ProbePart(null, 0L, null, null, null, concise(e));
        }
    }

    private ProxyConnection openConnection(String proxyType, String proxyHost, int proxyPort,
                                           String username, String password, URI target,
                                           int timeoutMs) throws Exception {
        String normalizedType = Objects.requireNonNullElse(proxyType, "").trim().toLowerCase(Locale.ROOT);
        if (!"socks5".equals(normalizedType) && !"http".equals(normalizedType)) {
            throw new IOException("面板协议客户端暂不支持 " + proxyType);
        }
        if (proxyPort < 1 || proxyPort > 65535) {
            throw new IOException("协议端口无效");
        }
        long started = System.nanoTime();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            boolean plainHttpProxy = false;
            if ("socks5".equals(normalizedType)) {
                socks5Connect(socket, username, password, target.getHost(), target.getPort() > 0
                        ? target.getPort() : defaultPort(target));
            } else if ("https".equalsIgnoreCase(target.getScheme())) {
                httpProxyConnect(socket, username, password, target.getHost(),
                        target.getPort() > 0 ? target.getPort() : 443);
            } else {
                plainHttpProxy = true;
            }

            if ("https".equalsIgnoreCase(target.getScheme())) {
                SSLSocketFactory factory = SSLContext.getDefault().getSocketFactory();
                SSLSocket sslSocket = (SSLSocket) factory.createSocket(socket, target.getHost(),
                        target.getPort() > 0 ? target.getPort() : 443, true);
                sslSocket.setSoTimeout(timeoutMs);
                sslSocket.startHandshake();
                socket = sslSocket;
            }
            return new ProxyConnection(socket, elapsedMs(started), plainHttpProxy);
        } catch (Exception e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private void socks5Connect(Socket socket, String username, String password,
                               String targetHost, int targetPort) throws IOException {
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        boolean authenticated = !isBlank(username) || !isBlank(password);
        output.write(authenticated
                ? new byte[]{0x05, 0x02, 0x00, 0x02}
                : new byte[]{0x05, 0x01, 0x00});
        output.flush();
        byte[] greeting = readFully(input, 2);
        if (greeting[0] != 0x05) throw new IOException("SOCKS5 版本协商失败");
        if ((greeting[1] & 0xff) == 0xff) throw new IOException("SOCKS5 拒绝认证方式");
        if ((greeting[1] & 0xff) == 0x02) {
            byte[] user = username.getBytes(StandardCharsets.UTF_8);
            byte[] pass = password.getBytes(StandardCharsets.UTF_8);
            if (user.length > 255 || pass.length > 255) throw new IOException("SOCKS5 认证信息过长");
            output.write(0x01);
            output.write(user.length);
            output.write(user);
            output.write(pass.length);
            output.write(pass);
            output.flush();
            byte[] auth = readFully(input, 2);
            if (auth[1] != 0x00) throw new IOException("SOCKS5 用户名或密码错误");
        } else if ((greeting[1] & 0xff) != 0x00) {
            throw new IOException("SOCKS5 返回了不支持的认证方式");
        }

        byte[] host = targetHost.getBytes(StandardCharsets.UTF_8);
        if (host.length > 255) throw new IOException("SOCKS5 目标域名过长");
        output.write(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) host.length});
        output.write(host);
        output.write((targetPort >>> 8) & 0xff);
        output.write(targetPort & 0xff);
        output.flush();

        byte[] header = readFully(input, 4);
        if (header[0] != 0x05 || header[1] != 0x00) {
            throw new IOException("SOCKS5 连接目标失败：" + (header[1] & 0xff));
        }
        int addressLength = switch (header[3] & 0xff) {
            case 0x01 -> 4;
            case 0x03 -> readFully(input, 1)[0] & 0xff;
            case 0x04 -> 16;
            default -> throw new IOException("SOCKS5 返回地址类型无效");
        };
        readFully(input, addressLength + 2);
    }

    private void httpProxyConnect(Socket socket, String username, String password,
                                  String targetHost, int targetPort) throws IOException {
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        String authority = targetHost.contains(":") ? "[" + targetHost + "]" : targetHost;
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(authority).append(":").append(targetPort).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append(":").append(targetPort).append("\r\n")
                .append("Connection: close\r\n");
        appendProxyAuthorization(request, username, password);
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        HttpResponseHead response = readResponseHead(input);
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("HTTP 代理 CONNECT 失败：" + response.status());
        }
    }

    private void sendRequest(OutputStream output, URI target, String proxyType,
                             boolean plainHttpProxy, String username, String password,
                             boolean upload, long expectedBytes) throws IOException {
        String path = target.getRawPath();
        if (isBlank(path)) path = "/";
        if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
        String requestTarget = plainHttpProxy ? target.toString() : path;
        String host = target.getHost();
        int port = target.getPort() > 0 ? target.getPort() : defaultPort(target);
        String hostHeader = (port == defaultPort(target)) ? host : host + ":" + port;
        StringBuilder request = new StringBuilder()
                .append(upload ? "POST " : "GET ").append(requestTarget).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader).append("\r\n")
                .append("User-Agent: CloudNest-ProtocolClient/1.0\r\n")
                .append("Cache-Control: no-cache\r\n")
                .append("Pragma: no-cache\r\n")
                .append("Connection: close\r\n");
        if (plainHttpProxy && "http".equalsIgnoreCase(proxyType)) {
            appendProxyAuthorization(request, username, password);
        }
        if (upload) {
            request.append("Content-Type: application/octet-stream\r\n")
                    .append("Content-Length: ").append(expectedBytes).append("\r\n");
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (upload) {
            byte[] buffer = new byte[IO_BUFFER_BYTES];
            long remaining = expectedBytes;
            while (remaining > 0) {
                int length = (int) Math.min(buffer.length, remaining);
                output.write(buffer, 0, length);
                remaining -= length;
            }
        }
    }

    private HttpResponseHead readResponseHead(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            buffer.write(current);
            int size = buffer.size();
            if (size > MAX_HEADER_BYTES) throw new IOException("HTTP 响应头过大");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length >= 4 && bytes[length - 4] == '\r' && bytes[length - 3] == '\n') {
                    String text = new String(bytes, StandardCharsets.ISO_8859_1);
                    String[] lines = text.split("\r\n");
                    if (lines.length == 0) throw new IOException("HTTP 响应为空");
                    String[] statusLine = lines[0].split(" ", 3);
                    if (statusLine.length < 2) throw new IOException("HTTP 状态行无效");
                    int status;
                    try {
                        status = Integer.parseInt(statusLine[1]);
                    } catch (NumberFormatException e) {
                        throw new IOException("HTTP 状态码无效");
                    }
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (int i = 1; i < lines.length; i++) {
                        int separator = lines[i].indexOf(':');
                        if (separator <= 0) continue;
                        headers.put(lines[i].substring(0, separator).trim().toLowerCase(Locale.ROOT),
                                lines[i].substring(separator + 1).trim());
                    }
                    return new HttpResponseHead(status, headers);
                }
            }
            previous = current;
        }
        throw new IOException("HTTP 响应头读取失败");
    }

    private long readResponseBody(InputStream input, Map<String, String> headers,
                                  long expectedBytes) throws IOException {
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            return readChunkedBody(input, expectedBytes);
        }
        long contentLength = parseLong(headers.get("content-length"), -1L);
        long limit = expectedBytes > 0 ? expectedBytes : Long.MAX_VALUE;
        if (contentLength >= 0) limit = Math.min(limit, contentLength);
        return copyBytes(input, limit);
    }

    private void drainResponseBody(InputStream input, Map<String, String> headers,
                                   long maxBytes) throws IOException {
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            readChunkedBody(input, maxBytes);
            return;
        }
        long contentLength = parseLong(headers.get("content-length"), -1L);
        copyBytes(input, contentLength >= 0 ? Math.min(contentLength, maxBytes) : maxBytes);
    }

    private long readChunkedBody(InputStream input, long expectedBytes) throws IOException {
        long total = 0;
        while (true) {
            String line = readAsciiLine(input);
            int extension = line.indexOf(';');
            String sizeText = (extension >= 0 ? line.substring(0, extension) : line).trim();
            long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException e) {
                throw new IOException("HTTP 分块长度无效");
            }
            if (chunkSize == 0) {
                while (!readAsciiLine(input).isEmpty()) {
                    // Drain trailers.
                }
                return total;
            }
            long toRead = expectedBytes > 0
                    ? Math.min(chunkSize, Math.max(0L, expectedBytes - total)) : chunkSize;
            if (toRead > 0) total += copyBytes(input, toRead);
            long remaining = chunkSize - toRead;
            if (remaining > 0) copyBytes(input, remaining);
            readFully(input, 2);
            if (expectedBytes > 0 && total >= expectedBytes) return total;
        }
    }

    private long copyBytes(InputStream input, long limit) throws IOException {
        byte[] buffer = new byte[IO_BUFFER_BYTES];
        long total = 0;
        while (total < limit) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, limit - total));
            if (read < 0) break;
            if (read == 0) continue;
            total += read;
        }
        return total;
    }

    private String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                int length = Math.max(0, bytes.length - 1);
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            previous = current;
            if (line.size() > MAX_HEADER_BYTES) throw new IOException("HTTP 行过长");
        }
        throw new IOException("HTTP 行读取失败");
    }

    private byte[] readFully(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("代理握手响应不完整");
        return bytes;
    }

    private URI validTarget(String raw) throws IOException {
        try {
            URI uri = URI.create(raw);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("测速地址协议无效");
            }
            if (isBlank(uri.getHost())) throw new IOException("测速地址缺少域名");
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("测速地址无效");
        }
    }

    private void appendProxyAuthorization(StringBuilder request, String username, String password) {
        if (isBlank(username)) return;
        String token = Base64.getEncoder().encodeToString(
                (username + ":" + Objects.requireNonNullElse(password, "")).getBytes(StandardCharsets.ISO_8859_1));
        request.append("Proxy-Authorization: Basic ").append(token).append("\r\n");
    }

    private String addBytesQuery(String raw, long bytes) {
        try {
            URI uri = URI.create(raw);
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                    (isBlank(uri.getQuery()) ? "" : uri.getQuery() + "&") + "bytes=" + bytes
                            + "&cb=" + URLEncoder.encode(Long.toString(System.nanoTime()), StandardCharsets.UTF_8),
                    uri.getFragment()).toString();
        } catch (Exception e) {
            return raw;
        }
    }

    private int defaultPort(URI target) {
        return "https".equalsIgnoreCase(target.getScheme()) ? 443 : 80;
    }

    private double elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000D;
    }

    private double elapsedSeconds(long started) {
        return (System.nanoTime() - started) / 1_000_000_000D;
    }

    private long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String concise(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "协议客户端探针失败" : error.getMessage().replace('\n', ' ');
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private record ProxyConnection(Socket socket, double handshakeMs, boolean plainHttpProxy)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private record HttpResponseHead(int status, Map<String, String> headers) {
    }

    private record ProbePart(Integer status, long bytes, Double mbps, Double headerMs,
                             Double handshakeMs, String error) {
    }
}
