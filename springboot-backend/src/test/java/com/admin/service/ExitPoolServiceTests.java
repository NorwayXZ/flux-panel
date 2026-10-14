package com.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitPoolServiceTests {

    @Test
    void classifiesNetworkKinds() {
        assertEquals(ExitPoolService.KIND_HOME, ExitPoolService.kindOf("126.115.107.89", "JP", false, false, "SoftBank Corp.", "Softbank BB"));
        assertEquals(ExitPoolService.KIND_HOSTING, ExitPoolService.kindOf("45.76.1.1", "JP", true, false, "Vultr", "Vultr"));
        assertEquals(ExitPoolService.KIND_MOBILE, ExitPoolService.kindOf("1.72.1.1", "JP", false, true, "NTT DOCOMO", "NTT DOCOMO"));
        assertEquals(ExitPoolService.KIND_PROJECT, ExitPoolService.kindOf("219.100.37.234", "JP", false, false, "x", "y"));
        assertEquals(ExitPoolService.KIND_PROJECT, ExitPoolService.kindOf("1.2.3.4", "JP", false, false, "SoftEther Corporation", ""));
        assertEquals(ExitPoolService.KIND_UNKNOWN, ExitPoolService.kindOf("1.2.3.4", "", false, false, "x", "y"));
    }

    @Test
    void onlyHomeNetworksInTheRequestedCountryAreAccepted() {
        assertTrue(ExitPoolService.acceptable(info("JP", ExitPoolService.KIND_HOME), "jp"));
        assertFalse(ExitPoolService.acceptable(info("KR", ExitPoolService.KIND_HOME), "JP"), "another country");
        assertFalse(ExitPoolService.acceptable(info("JP", ExitPoolService.KIND_HOSTING), "JP"), "data centre");
        assertFalse(ExitPoolService.acceptable(info("JP", ExitPoolService.KIND_MOBILE), "JP"), "mobile network");
        assertFalse(ExitPoolService.acceptable(info("JP", ExitPoolService.KIND_PROJECT), "JP"), "project network");
        assertFalse(ExitPoolService.acceptable(info("JP", ExitPoolService.KIND_UNKNOWN), "JP"), "unknown");
        assertFalse(ExitPoolService.acceptable(null, "JP"), "an exit that could not be looked up is not approved");
    }

    @Test
    void explainsWhyAnExitWasRejected() {
        assertEquals("出口 IP 位于韩国，不是日本", ExitPoolService.rejectionReason(info("KR", ExitPoolService.KIND_HOME), "JP"));
        assertTrue(ExitPoolService.rejectionReason(info("JP", ExitPoolService.KIND_HOSTING), "JP").contains("机房"));
        assertTrue(ExitPoolService.rejectionReason(info("JP", ExitPoolService.KIND_PROJECT), "JP").contains("项目方"));
    }

    @Test
    void failedCandidatesWaitLongerEachTimeUpToSixHours() {
        assertEquals(10 * 60_000L, ExitPoolService.retryDelay(1, false));
        assertEquals(20 * 60_000L, ExitPoolService.retryDelay(2, false));
        assertEquals(40 * 60_000L, ExitPoolService.retryDelay(3, false));
        assertEquals(6 * 3_600_000L, ExitPoolService.retryDelay(10, false));
        assertEquals(6 * 3_600_000L, ExitPoolService.retryDelay(500, false));
        assertEquals(10 * 60_000L, ExitPoolService.retryDelay(0, false));
    }

    @Test
    void exitsThatWorkedBeforeAreRetriedSoon() {
        assertEquals(60_000L, ExitPoolService.retryDelay(1, true));
        assertEquals(2 * 60_000L, ExitPoolService.retryDelay(2, true));
        assertEquals(6 * 3_600_000L, ExitPoolService.retryDelay(50, true));
    }

    @Test
    void namesTheExitGroupAfterTheCountry() {
        assertEquals("JP", ExitPoolService.normalizeCountry(" jp "));
        assertEquals("jp", ExitPoolService.groupName("JP"));
        assertEquals("cnexit:jp", ExitPoolService.interfaceName("Jp"));
        assertEquals("日本", ExitPoolService.countryName("jp"));
        assertEquals("格林纳达", ExitPoolService.countryName("gd"));
        assertEquals("QQ", ExitPoolService.countryName("qq"));
        assertEquals("未知地区", ExitPoolService.countryName(""));
    }

    @Test
    void translatesCommonAgentErrors() {
        assertEquals("连接超时", ExitPoolService.translate("exit connection failed (TCP: connect to [AF_INET]1.2.3.4:443 failed: Connection timed out)"));
        assertEquals("连接中断", ExitPoolService.translate("exit tunnel closed"));
        assertEquals("能连上但不通流量", ExitPoolService.translate("exit carried no traffic: no reply"));
        assertEquals("节点上的 OpenVPN 无法运行", ExitPoolService.translate("unable to read the OpenVPN version"));
        assertEquals("未知原因", ExitPoolService.translate(""));
        assertEquals("something new", ExitPoolService.translate("something new"));
    }

    @Test
    void lowRiskOnlyRequiresAKnownScoreAtOrBelowTheLimit() {
        ExitPoolService.IpInfo clean = new ExitPoolService.IpInfo("JP", "x", "AS1", ExitPoolService.KIND_HOME, false, 20);
        ExitPoolService.IpInfo risky = new ExitPoolService.IpInfo("JP", "x", "AS1", ExitPoolService.KIND_HOME, false, 90);
        ExitPoolService.IpInfo unknown = new ExitPoolService.IpInfo("JP", "x", "AS1", ExitPoolService.KIND_HOME, false, null);
        assertTrue(ExitPoolService.acceptable(clean, "JP", 50));
        assertTrue(ExitPoolService.acceptable(clean, "JP", 20));
        assertFalse(ExitPoolService.acceptable(risky, "JP", 50));
        assertFalse(ExitPoolService.acceptable(unknown, "JP", 50), "without a score the exit cannot be shown to be low risk");
        assertTrue(ExitPoolService.acceptable(risky, "JP", null), "the limit is off by default");
        assertEquals("欺诈分 90 高于设定的上限 50", ExitPoolService.rejectionReason(risky, "JP", 50));
    }

    private static ExitPoolService.IpInfo info(String country, String kind) {
        return new ExitPoolService.IpInfo(country, "Example ISP", "AS1", kind, false);
    }
}
