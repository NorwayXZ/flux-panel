package com.admin.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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

    @Test
    void acceptsAliyunDuplicateOnlyWhenTheExistingRecordAlreadyMatches() {
        JSONObject record = JSONObject.parseObject("{\"Value\":\"34.150.15.102\",\"TTL\":600}");
        assertEquals(true, DynamicDnsService.aliyunLineRecordMatches(record, "34.150.15.102", 600));
        assertEquals(false, DynamicDnsService.aliyunLineRecordMatches(record, "8.218.90.244", 600));
    }

    @Test
    void givesEveryCloudflareZoneAUniqueSelectorKey() {
        assertEquals("dns:7:101", DynamicDnsService.providerOptionKey("dns", 7L, 101L));
        assertEquals("dns:7:102", DynamicDnsService.providerOptionKey("dns", 7L, 102L));
        assertEquals("dynamic:9", DynamicDnsService.providerOptionKey("dynamic", 9L, null));
    }

    @Test
    void extractsDnsPodAccountDomains() {
        JSONObject response = JSONObject.parseObject("{\"DomainList\":[{\"Name\":\"766733.xyz\"},{\"Name\":\"example.com\"}]}");
        assertEquals(List.of("766733.xyz", "example.com"), DynamicDnsService.extractDnsPodDomainNames(response));
    }

    @Test
    void extractsAliyunAccountDomains() {
        JSONObject response = JSONObject.parseObject("{\"Domains\":{\"Domain\":[{\"DomainName\":\"766733.xyz\"},{\"DomainName\":\"example.com\"}]}}");
        assertEquals(List.of("766733.xyz", "example.com"), DynamicDnsService.extractAliyunDomainNames(response));
    }

    @Test
    void acceptsEmptyProviderDomainResponses() {
        assertEquals(List.of(), DynamicDnsService.extractDnsPodDomainNames(new JSONObject()));
        assertEquals(List.of(), DynamicDnsService.extractAliyunDomainNames(new JSONObject()));
    }
}
