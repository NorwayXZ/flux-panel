package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

    @Test
    void formatsAliyunTimestampsWithoutFractionalSeconds() {
        assertEquals("2026-07-28T07:19:50Z", DynamicDnsService.formatAliyunTimestamp(
                Instant.parse("2026-07-28T07:19:50.123456Z")));
    }

    @Test
    void keepsAliyunQueryParametersEncodedExactlyOnce() {
        String rawQuery = DynamicDnsService.buildAliyunRequestUri(
                "Timestamp=2026-07-28T07%3A19%3A50Z", "abc%2Bdef%3D").getRawQuery();
        assertEquals("Timestamp=2026-07-28T07%3A19%3A50Z&Signature=abc%2Bdef%3D", rawQuery);
    }

    @Test
    void identifiesAliyunCarrierRecordsByLineCode() {
        JSONObject record = JSONObject.parseObject("{\"Line\":\"中国电信\",\"LineCode\":\"telecom\"}");
        assertEquals(true, DynamicDnsService.aliyunLineMatches(record, "telecom"));
    }
}
