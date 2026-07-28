package com.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicDnsServiceTests {
    @Test
    void formatsAliyunApiErrorsWithoutDroppingTheProviderCode() {
        assertEquals(
                "阿里云 DNS：Forbidden.RAM - User not authorized to operate on the specified resource",
                DynamicDnsService.formatAliyunApiError("{\"Code\":\"Forbidden.RAM\",\"Message\":\"User not authorized to operate on the specified resource\"}"));
    }

    @Test
    void hidesUnexpectedGatewayBodies() {
        assertEquals("阿里云 DNS API 请求失败", DynamicDnsService.formatAliyunApiError("<html>bad gateway</html>"));
    }
}
