package com.admin.common.interceptor;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiRequestTimingFilterTests {

    @Test
    void addsRequestIdAndClearsLoggingContext() throws Exception {
        ApiRequestTimingFilter filter = new ApiRequestTimingFilter(1000);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/node/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNotNull(response.getHeader("X-Request-ID"));
        assertNull(MDC.get("requestId"));
    }

    @Test
    void preservesSafeProxyRequestId() throws Exception {
        ApiRequestTimingFilter filter = new ApiRequestTimingFilter(1000);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/node/list");
        request.addHeader("X-Request-ID", "nginx-request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("nginx-request-123", response.getHeader("X-Request-ID"));
    }

    @Test
    void clearsLoggingContextAfterFailure() {
        ApiRequestTimingFilter filter = new ApiRequestTimingFilter(1000);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/node/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> { throw new ServletException("test failure"); };
        assertThrows(ServletException.class, () -> filter.doFilter(request, response, throwingChain));
        assertNull(MDC.get("requestId"));
    }
}
