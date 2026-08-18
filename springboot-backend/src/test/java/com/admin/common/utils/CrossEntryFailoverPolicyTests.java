package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEntryFailoverPolicyTests {
    @Test
    void switchesToHealthyBackupWhenActiveEntryFails() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, false, 0), member(2, 1, true, 2)), 1L, false, 3, true);

        assertTrue(decision.switchRequired());
        assertEquals(2L, decision.targetId());
    }

    @Test
    void emergencyFailoverBypassesCooldown() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, false, 0), member(2, 1, true, 2)), 1L, false, 3, false);

        assertTrue(decision.switchRequired());
        assertEquals(2L, decision.targetId());
    }

    @Test
    void waitsForEnoughRecoverySamplesBeforeFailback() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, true, 2), member(2, 1, true, 8)), 2L, true, 3, true);

        assertFalse(decision.switchRequired());
    }

    @Test
    void failsBackAfterStableRecovery() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, true, 3), member(2, 1, true, 8)), 2L, true, 3, true);

        assertTrue(decision.switchRequired());
        assertEquals(1L, decision.targetId());
    }

    @Test
    void switchesAwayFromDegradedActiveEntryAfterCooldown() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(degradedMember(1, 0, true, 10), member(2, 1, true, 5)), 1L, false, 3, true);

        assertTrue(decision.switchRequired());
        assertEquals(2L, decision.targetId());
    }

    @Test
    void doesNotFailbackToDegradedPrimary() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(degradedMember(1, 0, true, 8), member(2, 1, true, 8)), 2L, true, 3, true);

        assertFalse(decision.switchRequired());
    }

    @Test
    void doesNotFailbackToSuppressedPrimary() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(suppressedMember(1, 0, true, 8), member(2, 1, true, 8)), 2L, true, 3, true);

        assertFalse(decision.switchRequired());
        assertEquals("主入口处于质量抖动保护期", decision.reason());
    }

    @Test
    void staysOnDegradedActiveWhenNoHealthyQualityBackupExists() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(degradedMember(1, 0, true, 10), degradedMember(2, 1, true, 10)), 1L, false, 3, true);

        assertFalse(decision.switchRequired());
        assertEquals("当前入口质量劣化，但没有质量正常的备用入口", decision.reason());
    }

    @Test
    void degradedFallbackChoosesBestBadBackupWhenEveryEntryIsBad() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 10, true, true, false, 180, 0.0, 1, 0, 10, "203.0.113.10", "latency"),
                        qualityMember(2, 1, true, 10, true, true, false, 70, 0.0, 0, 0, 11, "198.51.100.20", "latency"),
                        qualityMember(3, 2, true, 10, true, true, false, 120, 5.0, 0, 0, 12, "192.0.2.30", "latency")
                ),
                1L,
                settings(false, 3, true, true, true, true, true, 10, 20.0, "auto", null));

        assertTrue(decision.switchRequired());
        assertEquals(2L, decision.targetId());
        assertEquals("全部入口质量不佳，选择差中最优", decision.reason());
    }

    @Test
    void degradedFallbackAvoidsHigherPenaltyWhenMetricsTie() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 10, true, true, false, 180, 0.0, 0, 0, 10, "203.0.113.10", "latency", true, 3),
                        qualityMember(2, 1, true, 10, true, true, false, 70, 0.0, 0, 0, 11, "198.51.100.20", "latency", true, 2),
                        qualityMember(3, 2, true, 10, true, true, false, 70, 0.0, 0, 0, 12, "192.0.2.30", "latency", true, 0)
                ),
                1L,
                settings(false, 3, true, true, true, true, true, 10, 20.0, "auto", null));

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
    }

    @Test
    void sameFaultAvoidancePrefersBackupWithoutTheActiveFault() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 10, true, true, false, 150, 40.0, 0, 0, 10, "203.0.113.10", "loss"),
                        qualityMember(2, 1, true, 10, false, true, false, 18, 35.0, 0, 0, 11, "198.51.100.20", "loss"),
                        qualityMember(3, 2, true, 10, false, true, false, 25, 0.0, 0, 0, 12, "192.0.2.30", "none")
                ),
                1L,
                settings(false, 3, true, true, true, true, true, 10, 20.0, "auto", null));

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
    }

    @Test
    void topologyAvoidancePrefersDifferentAddressGroup() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 10, true, true, false, 150, 0.0, 0, 0, 10, "203.0.113.10", "latency"),
                        qualityMember(2, 1, true, 10, false, true, false, 18, 0.0, 0, 0, 11, "203.0.113.20", "none"),
                        qualityMember(3, 2, true, 10, false, true, false, 25, 0.0, 0, 0, 12, "198.51.100.30", "none")
                ),
                1L,
                settings(false, 3, true, true, true, true, true, 10, 20.0, "auto", null));

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
    }

    @Test
    void topologyAvoidanceTreatsSameLargeIpv4RangeAsSameRiskGroup() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 10, true, true, false, 150, 0.0, 0, 0, 10, "8.218.89.115", "latency"),
                        qualityMember(2, 1, true, 10, false, true, false, 18, 0.0, 0, 0, 11, "8.218.152.71", "none"),
                        qualityMember(3, 2, true, 10, false, true, false, 25, 0.0, 0, 0, 12, "34.150.15.102", "none")
                ),
                1L,
                settings(false, 3, true, true, true, true, true, 10, 20.0, "auto", null));

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
    }

    @Test
    void minimumResidencyBlocksQualitySwitch() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(degradedMember(1, 0, true, 10), member(2, 1, true, 5)),
                1L,
                settings(false, 3, true, false, true, true, true, 10, 20.0, "auto", null));

        assertFalse(decision.switchRequired());
        assertEquals("当前入口驻留时间不足", decision.reason());
    }

    @Test
    void failbackDoesNotRequireMeaningfulLatencyBenefit() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 50, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 8, false, true, false, 50, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                2L,
                settings(true, 3, true, true, true, true, true, 5, 15.0, "auto", null));

        assertTrue(decision.switchRequired());
        assertEquals(1L, decision.targetId());
    }

    @Test
    void failbackAllowsPrimaryEvenWhenBackupHasLowerLatency() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 30, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 8, false, true, false, 10, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                2L,
                settings(true, 3, true, true, true, true, true, 5, 15.0, "auto", null));

        assertTrue(decision.switchRequired());
        assertEquals(1L, decision.targetId());
    }

    @Test
    void failbackUsesPrimaryOwnQualityInsteadOfComparingBackupLatency() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 120, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 8, false, true, false, 10, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                2L,
                settings(true, 3, true, true, true, true, true, 5, 15.0, "auto", null));

        assertTrue(decision.switchRequired());
        assertEquals(1L, decision.targetId());
    }

    @Test
    void manualPauseKeepsCurrentEntry() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, false, 0), member(2, 1, true, 5)),
                1L,
                settings(false, 3, true, true, true, true, true, 10, 20.0, "pause", null));

        assertFalse(decision.switchRequired());
        assertEquals("已暂停自动切换", decision.reason());
    }

    @Test
    void manualLockSwitchesToLockedHealthyEntry() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, true, 5), member(2, 1, true, 5)),
                1L,
                settings(false, 3, true, true, true, true, true, 10, 20.0, "lock", 2L));

        assertTrue(decision.switchRequired());
        assertEquals(2L, decision.targetId());
        assertEquals("手动锁定入口", decision.reason());
    }

    @Test
    void scheduledHealthyTargetOverridesNormalPrimaryOrder() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, true, 8), member(2, 1, true, 8)), 1L,
                new CrossEntryFailoverPolicy.Settings(false, 3, true, true,
                        true, true, true, true, false, 5, 10, 0, 0.0,
                        "auto", null, 2L, true, true));

        assertTrue(decision.switchRequired());
        assertEquals(2L, decision.targetId());
        assertEquals("按当前时段优先线路切换", decision.reason());
    }

    @Test
    void scheduledUnhealthyTargetFallsBackToExistingRules() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, true, 8), degradedMember(2, 1, true, 8), member(3, 2, true, 8)), 1L,
                new CrossEntryFailoverPolicy.Settings(false, 3, true, true,
                        true, true, true, true, false, 5, 10, 0, 0.0,
                        "auto", null, 2L, true, true));

        assertFalse(decision.switchRequired());
        assertEquals("保持当前入口", decision.reason());
    }

    @Test
    void manualPauseTakesPriorityOverScheduledTarget() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, true, 8), member(2, 1, true, 8)), 1L,
                new CrossEntryFailoverPolicy.Settings(false, 3, true, true,
                        true, true, true, true, false, 5, 10, 0, 0.0,
                        "pause", null, 2L, true, true));

        assertFalse(decision.switchRequired());
        assertEquals("已暂停自动切换", decision.reason());
    }

    @Test
    void manualLockTakesPriorityOverScheduledTarget() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, true, 8), member(2, 1, true, 8)), 1L,
                new CrossEntryFailoverPolicy.Settings(false, 3, true, true,
                        true, true, true, true, false, 5, 10, 0, 0.0,
                        "lock", 1L, 2L, true, true));

        assertFalse(decision.switchRequired());
        assertEquals("已锁定当前入口", decision.reason());
    }

    @Test
    void scheduledTargetRespectsStrictQualityTarget() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, true, 8), unacceptableMember(2, 1, true, 8)), 1L,
                new CrossEntryFailoverPolicy.Settings(false, 3, true, true,
                        true, true, true, true, false, 5, 10, 0, 0.0,
                        "auto", null, 2L, true, true));

        assertFalse(decision.switchRequired());
        assertEquals("保持当前入口", decision.reason());
    }

    @Test
    void returnsToPrimaryAfterScheduledWindowEnds() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(member(1, 0, true, 8), member(2, 1, true, 8)), 2L,
                new CrossEntryFailoverPolicy.Settings(false, 3, true, true,
                        true, true, true, true, false, 5, 10, 0, 0.0,
                        "auto", null, null, false, true));

        assertTrue(decision.switchRequired());
        assertEquals(1L, decision.targetId());
        assertEquals("当前时段结束，恢复默认主入口", decision.reason());
    }

    @Test
    void strictFixedTargetSkipsBackupThatDoesNotMeetTarget() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(degradedMember(1, 0, true, 10), unacceptableMember(2, 1, true, 10), member(3, 2, true, 10)),
                1L, false, 3, true);

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
    }

    @Test
    void qualitySwitchSkipsSuppressedBackup() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(degradedMember(1, 0, true, 10), suppressedMember(2, 1, true, 10), member(3, 2, true, 10)),
                1L, false, 3, true);

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
    }

    @Test
    void smartQualitySelectionPrefersPreheatedBackup() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 10, true, true, false, 150, 0.0, 0, 0, 10, "203.0.113.10", "latency", true),
                        qualityMember(2, 1, true, 10, false, true, false, 15, 0.0, 0, 0, 11, "198.51.100.20", "none", false),
                        qualityMember(3, 2, true, 10, false, true, false, 22, 0.0, 0, 0, 12, "192.0.2.30", "none", true)
                ),
                1L,
                settings(false, 3, true, true, true, true, true, true, 10, 20.0, "auto", null));

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
    }

    @Test
    void tcpLatencySelectionChoosesLowestStableHealthyEntry() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 180, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 5, false, true, false, 95, 0.0, 0, 0, 11, "198.51.100.20", "none"),
                        qualityMember(3, 2, true, 5, false, true, false, 70, 0.0, 0, 0, 12, "192.0.2.30", "none")
                ),
                1L,
                settingsWithTcpLatency(true, 3, true, true, 5));

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
        assertEquals("TCP 延迟优选，自动切换至最低延迟入口", decision.reason());
    }

    @Test
    void tcpLatencySelectionHandlesMissingActiveEntry() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 80, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 5, false, true, false, 60, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                null,
                settingsWithTcpLatency(true, 3, true, true, 5));

        assertTrue(decision.switchRequired());
        assertEquals(2L, decision.targetId());
    }

    @Test
    void tcpLatencySelectionIgnoresUnstableCandidate() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 80, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 2, false, true, false, 20, 0.0, 0, 0, 11, "198.51.100.20", "none"),
                        qualityMember(3, 2, true, 3, false, true, false, 60, 0.0, 0, 0, 12, "192.0.2.30", "none")
                ),
                1L,
                settingsWithTcpLatency(true, 3, true, true, 5));

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
    }

    @Test
    void tcpLatencySelectionKeepsPrimaryWhenItIsOnlySlightlySlower() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 30, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 5, false, true, false, 27, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                1L,
                settingsWithTcpLatency(true, 3, true, true, 5));

        assertFalse(decision.switchRequired());
        assertEquals("主入口在优先容忍范围内", decision.reason());
    }

    @Test
    void tcpLatencySelectionKeepsPrimaryWithinConfiguredTolerance() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 80, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 5, false, true, false, 65, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                1L,
                settingsWithTcpLatency(true, 3, true, true, 5, 20));

        assertFalse(decision.switchRequired());
        assertEquals("主入口在优先容忍范围内", decision.reason());
    }

    @Test
    void tcpLatencySelectionLeavesPrimaryWhenToleranceIsExceeded() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 90, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 5, false, true, false, 65, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                1L,
                settingsWithTcpLatency(true, 3, true, true, 5, 20));

        assertTrue(decision.switchRequired());
        assertEquals(2L, decision.targetId());
    }

    @Test
    void tcpLatencySelectionReturnsToPrimaryWithinTolerance() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 72, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 5, false, true, false, 65, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                2L,
                settingsWithTcpLatency(true, 3, true, true, 5, 10));

        assertTrue(decision.switchRequired());
        assertEquals(1L, decision.targetId());
        assertEquals("主入口延迟已回到优先范围，自动回切", decision.reason());
    }

    @Test
    void tcpLatencySelectionDoesNotChaseSmallBackupLatencyChanges() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 100, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 5, false, true, false, 60, 0.0, 0, 0, 11, "198.51.100.20", "none"),
                        qualityMember(3, 2, true, 5, false, true, false, 56, 0.0, 0, 0, 12, "192.0.2.30", "none")
                ),
                2L,
                settingsWithTcpLatency(true, 3, true, true, 5, 10));

        assertFalse(decision.switchRequired());
        assertEquals("候选入口 TCP 延迟收益不足", decision.reason());
    }

    @Test
    void tcpLatencySelectionIgnoresLegacyManualLockConflict() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 90, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 5, false, true, false, 60, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                1L,
                new CrossEntryFailoverPolicy.Settings(false, 3, true, true,
                        false, false, false, false, true, 5, 10,
                        0, 0.0, "lock", 1L));

        assertTrue(decision.switchRequired());
        assertEquals(2L, decision.targetId());
        assertEquals("TCP 延迟优选，自动切换至最低延迟入口", decision.reason());
    }

    @Test
    void disabledTcpLatencySelectionKeepsPriorityOrder() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(
                        qualityMember(1, 0, true, 5, false, true, false, 80, 0.0, 0, 0, 10, "203.0.113.10", "none"),
                        qualityMember(2, 1, true, 5, false, true, false, 20, 0.0, 0, 0, 11, "198.51.100.20", "none")
                ),
                1L,
                settings(false, 3, true, true, true, true, true, 10, 20.0, "auto", null));

        assertFalse(decision.switchRequired());
        assertEquals("保持当前入口", decision.reason());
    }

    private CrossEntryFailoverPolicy.Member member(long id, int priority, boolean healthy, int successCount) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount, false, true, false);
    }

    private CrossEntryFailoverPolicy.Member degradedMember(long id, int priority, boolean healthy, int successCount) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount, true, true, false);
    }

    private CrossEntryFailoverPolicy.Member unacceptableMember(long id, int priority, boolean healthy, int successCount) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount, false, false, false);
    }

    private CrossEntryFailoverPolicy.Member suppressedMember(long id, int priority, boolean healthy, int successCount) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount, false, true, true);
    }

    private CrossEntryFailoverPolicy.Member qualityMember(long id, int priority, boolean healthy, int successCount,
                                                         boolean degraded, boolean acceptable, boolean suppressed,
                                                         Integer latencyMs, Double lossPercent, int flapCount,
                                                         int failCount, long nodeId, String address, String faultKind) {
        return qualityMember(id, priority, healthy, successCount, degraded, acceptable, suppressed,
                latencyMs, lossPercent, flapCount, failCount, nodeId, address, faultKind, true);
    }

    private CrossEntryFailoverPolicy.Member qualityMember(long id, int priority, boolean healthy, int successCount,
                                                         boolean degraded, boolean acceptable, boolean suppressed,
                                                         Integer latencyMs, Double lossPercent, int flapCount,
                                                         int failCount, long nodeId, String address, String faultKind,
                                                         boolean preheated) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount, degraded, acceptable, suppressed,
                latencyMs, lossPercent, flapCount, failCount, nodeId, address, "", faultKind, preheated);
    }

    private CrossEntryFailoverPolicy.Member qualityMember(long id, int priority, boolean healthy, int successCount,
                                                         boolean degraded, boolean acceptable, boolean suppressed,
                                                         Integer latencyMs, Double lossPercent, int flapCount,
                                                         int failCount, long nodeId, String address, String faultKind,
                                                         boolean preheated, int penaltyLevel) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount, degraded, acceptable, suppressed,
                latencyMs, lossPercent, flapCount, failCount, nodeId, address, "", faultKind, preheated, penaltyLevel);
    }

    private CrossEntryFailoverPolicy.Settings settings(boolean autoFailback, int recoveryThreshold,
                                                      boolean cooldownElapsed, boolean minResidencyElapsed,
                                                      boolean degradedFallbackEnabled, boolean sameFaultAvoidanceEnabled,
                                                      boolean topologyAvoidanceEnabled, int failbackGainMs,
                                                      double failbackGainPercent, String manualControlMode, Long lockedMemberId) {
        return settings(autoFailback, recoveryThreshold, cooldownElapsed, minResidencyElapsed,
                degradedFallbackEnabled, sameFaultAvoidanceEnabled, topologyAvoidanceEnabled, true, failbackGainMs,
                failbackGainPercent, manualControlMode, lockedMemberId);
    }

    private CrossEntryFailoverPolicy.Settings settingsWithTcpLatency(boolean autoFailback, int recoveryThreshold,
                                                                     boolean cooldownElapsed, boolean minResidencyElapsed,
                                                                     int switchThresholdMs) {
        return settingsWithTcpLatency(autoFailback, recoveryThreshold, cooldownElapsed, minResidencyElapsed,
                switchThresholdMs, 10);
    }

    private CrossEntryFailoverPolicy.Settings settingsWithTcpLatency(boolean autoFailback, int recoveryThreshold,
                                                                     boolean cooldownElapsed, boolean minResidencyElapsed,
                                                                     int switchThresholdMs, int primaryToleranceMs) {
        return new CrossEntryFailoverPolicy.Settings(autoFailback, recoveryThreshold, cooldownElapsed,
                minResidencyElapsed, true, true, true, true, true, switchThresholdMs,
                primaryToleranceMs, 5, 15.0, "auto", null);
    }

    private CrossEntryFailoverPolicy.Settings settings(boolean autoFailback, int recoveryThreshold,
                                                      boolean cooldownElapsed, boolean minResidencyElapsed,
                                                      boolean degradedFallbackEnabled, boolean sameFaultAvoidanceEnabled,
                                                      boolean topologyAvoidanceEnabled, boolean preheatPreferred, int failbackGainMs,
                                                      double failbackGainPercent, String manualControlMode, Long lockedMemberId) {
        return new CrossEntryFailoverPolicy.Settings(autoFailback, recoveryThreshold, cooldownElapsed, minResidencyElapsed,
                degradedFallbackEnabled, sameFaultAvoidanceEnabled, topologyAvoidanceEnabled, preheatPreferred, failbackGainMs,
                failbackGainPercent, manualControlMode, lockedMemberId);
    }
}
