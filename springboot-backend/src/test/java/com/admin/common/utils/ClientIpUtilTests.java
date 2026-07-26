package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpUtilTests {

    @Test
    void trustsHeaderFromPanelProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.20.0.4");
        request.addHeader("X-Real-IP", "203.0.113.18");
        assertEquals("203.0.113.18", ClientIpUtil.resolve(request));
    }

    @Test
    void ignoresSpoofedHeaderOnDirectBackendRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.40");
        request.addHeader("X-Real-IP", "10.0.0.1");
        assertEquals("198.51.100.40", ClientIpUtil.resolve(request));
    }
}
