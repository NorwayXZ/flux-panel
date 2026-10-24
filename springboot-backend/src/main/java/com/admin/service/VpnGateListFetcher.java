package com.admin.service;

import com.admin.common.utils.ExitCandidateParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Fetches only parsed connection fields; an HTML error page never replaces a good list. */
class VpnGateListFetcher {
    static final String DEFAULT_URL = "http://api.vpngate.net/api/iphone/";
    private static final Set<String> OFFICIAL_HOSTS = Set.of("www.vpngate.net", "vpngate.net", "api.vpngate.net");
    private final List<URI> sources;
    private final HttpClient client;
    private final Duration requestTimeout;
    private volatile URI lastSuccessfulSource;

    VpnGateListFetcher(String configuredUrl) {
        this(configuredUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), Duration.ofSeconds(6));
    }

    VpnGateListFetcher(String configuredUrl, HttpClient client, Duration requestTimeout) {
        this.sources = sources(configuredUrl);
        this.client = client;
        this.requestTimeout = requestTimeout;
    }

    static List<URI> sources(String configuredUrl) {
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        for (String value : configuredUrl.split(",")) {
            URI uri = URI.create(value.trim());
            if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("候选列表地址必须是无账号密码的 HTTP(S) 地址");
            }
            result.add(uri);
        }
        // Preserve custom sources. Only the standard VPN Gate endpoint gains automatic fallbacks.
        if (result.stream().anyMatch(uri -> OFFICIAL_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                && "/api/iphone/".equals(uri.getPath()))) {
            result.add(URI.create(DEFAULT_URL));
            result.add(URI.create("https://www.vpngate.net/api/iphone/"));
            result.add(URI.create("http://www.vpngate.net/api/iphone/"));
        }
        return List.copyOf(result);
    }

    List<ExitCandidateParser.Candidate> fetch() throws IOException, InterruptedException {
        LinkedHashSet<URI> ordered = new LinkedHashSet<>();
        if (lastSuccessfulSource != null) ordered.add(lastSuccessfulSource);
        ordered.addAll(sources);
        List<String> failures = new ArrayList<>();
        for (URI source : ordered) {
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(source)
                        .timeout(requestTimeout).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                List<ExitCandidateParser.Candidate> parsed = ExitCandidateParser.parseVpnGateList(response.body());
                if (parsed.isEmpty()) throw new IOException("列表为空或格式无法识别");
                lastSuccessfulSource = source;
                return parsed;
            } catch (IOException | IllegalArgumentException e) {
                failures.add(source.getHost() + "：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        throw new IOException(String.join("；", failures));
    }

    String sourceHost() {
        URI source = lastSuccessfulSource;
        return source == null ? null : source.getHost();
    }
}
