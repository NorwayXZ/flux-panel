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

    @Test
    void mapsProviderLineNamesToStableCarrierKeys() {
        assertEquals("default", DynamicDnsService.providerLineCarrier("dnspod", null, "默认"));
        assertEquals("telecom", DynamicDnsService.providerLineCarrier("dnspod", null, "电信"));
        assertEquals("unicom", DynamicDnsService.providerLineCarrier("aliyun", "unicom", "中国联通"));
        assertEquals("mobile", DynamicDnsService.providerLineCarrier("aliyun", "mobile", "中国移动"));
    }

    @Test
    void usesProviderMinimumTtlInsteadOfPretendingAliyunSupportsSixtySeconds() {
        assertEquals(60, DynamicDnsService.lineRoutingMinimumTtl("dnspod"));
        assertEquals(600, DynamicDnsService.lineRoutingMinimumTtl("aliyun"));
    }

    @Test
    void parsesPublicDnsAnswersAndTheirRemainingTtl() {
        DynamicDnsService.PublicDnsProbe probe = DynamicDnsService.parsePublicDnsProbe(
                "mobile", "A", "{\"Status\":0,\"Answer\":[{\"type\":1,\"data\":\"8.218.90.244.\",\"TTL\":47}]}" );
        assertEquals(List.of("8.218.90.244"), probe.answers());
        assertEquals(47, probe.ttl());
        assertEquals(true, probe.successful());
    }

    @Test
    void treatsDnsPodNoRecordResponsesAsAnEmptyRecordSet() {
        assertEquals(true, DynamicDnsService.isDnsPodNoRecordError(
                "DNSPod [ResourceNotFound.NoDataOfRecord]: 暂无记录"));
        assertEquals(false, DynamicDnsService.isDnsPodNoRecordError(
                "DNSPod [AuthFailure.SignatureFailure]: 签名错误"));
    }
}
