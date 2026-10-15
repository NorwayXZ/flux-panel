package com.admin.common.interceptor;

import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.service.ApiTokenService;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

/**
 * Lets scripts call the panel API with a personal API token instead of a login session: a valid
 * token in "Authorization: Bearer cn_..." or "X-API-Key" is replaced, for this request only, by a
 * login token of its owner, so every endpoint and permission check works unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiTokenAuthFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "apiTokenAuthenticated";
    /** A token must not mint tokens or change the password of the account it belongs to. */
    private static final List<String> SESSION_ONLY = List.of("/api/v1/api-token/", "/api/v1/user/updatePassword");

    private final ApiTokenService tokens;

    public ApiTokenAuthFilter(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    static String presentedToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null) {
            String value = header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
            if (ApiTokenService.looksLikeToken(value)) return value;
        }
        String key = request.getHeader("X-API-Key");
        return ApiTokenService.looksLikeToken(key) ? key.trim() : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = presentedToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (SESSION_ONLY.stream().anyMatch(path::startsWith)) {
            reject(response, 403, "API 令牌不能用于管理令牌或修改密码，请登录面板操作");
            return;
        }
        User user = tokens.authenticate(token);
        if (user == null) {
            reject(response, 401, "API 令牌无效、已过期或已撤销");
            return;
        }
        String session = JwtUtil.generateToken(user);
        request.setAttribute(ATTRIBUTE, Boolean.TRUE);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return "Authorization".equalsIgnoreCase(name) ? session : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return "Authorization".equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of(session))
                        : super.getHeaders(name);
            }
        }, response);
    }

    private static void reject(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":" + code + ",\"msg\":\"" + message + "\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
