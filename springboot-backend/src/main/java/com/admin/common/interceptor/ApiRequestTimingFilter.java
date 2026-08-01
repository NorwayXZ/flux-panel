package com.admin.common.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiRequestTimingFilter extends OncePerRequestFilter {
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final long slowRequestThresholdMs;
    private final ApiRequestTimingRegistry timingRegistry;

    @Autowired
    public ApiRequestTimingFilter(
            @Value("${observability.slow-api-threshold-ms:1000}") long slowRequestThresholdMs,
            ApiRequestTimingRegistry timingRegistry
    ) {
        this.slowRequestThresholdMs = slowRequestThresholdMs;
        this.timingRegistry = timingRegistry;
    }

    public ApiRequestTimingFilter(long slowRequestThresholdMs) {
        this(slowRequestThresholdMs, new ApiRequestTimingRegistry(slowRequestThresholdMs, 900_000, 256));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = requestId(request);
        long startedAt = System.nanoTime();
        Exception failure = null;
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        try {
            filterChain.doFilter(request, response);
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            timingRegistry.record(request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs,
                    failure != null, requestId);
            String message = "API method={} path={} status={} durationMs={} requestId={}";
            if (failure != null) {
                log.error(message, request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs,
                        requestId, failure);
            } else if (response.getStatus() >= 500) {
                log.error(message, request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs,
                        requestId);
            } else if (durationMs >= slowRequestThresholdMs) {
                log.warn("Slow " + message, request.getMethod(), request.getRequestURI(), response.getStatus(),
                        durationMs, requestId);
            } else {
                log.info(message, request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs,
                        requestId);
            }
            MDC.remove("requestId");
        }
    }

    private String requestId(HttpServletRequest request) {
        String supplied = request.getHeader("X-Request-ID");
        if (supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()) return supplied;
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
