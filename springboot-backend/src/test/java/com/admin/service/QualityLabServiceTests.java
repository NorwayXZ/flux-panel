package com.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QualityLabServiceTests {
    @Test
    void explainsCommonConnectivityFailures() {
        assertEquals("目标端口未监听，或目标防火墙主动拒绝连接",
                QualityLabService.explainProbeError("dial tcp4 1.2.3.4:443: connect: connection refused"));
        assertEquals("连接目标端口超时，请检查防火墙、安全组和网络路由",
                QualityLabService.explainProbeError("i/o timeout"));
        assertEquals("没有到目标地址的可用网络路由",
                QualityLabService.explainProbeError("network is unreachable"));
        assertEquals("目标域名无法解析",
                QualityLabService.explainProbeError("lookup example.invalid: no such host"));
    }
}
