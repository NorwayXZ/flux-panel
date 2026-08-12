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
    void staysOnDegradedActiveWhenNoHealthyQualityBackupExists() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(degradedMember(1, 0, true, 10), degradedMember(2, 1, true, 10)), 1L, false, 3, true);

        assertFalse(decision.switchRequired());
        assertEquals("当前入口质量劣化，但没有质量正常的备用入口", decision.reason());
    }

    @Test
    void strictFixedTargetSkipsBackupThatDoesNotMeetTarget() {
        CrossEntryFailoverPolicy.Decision decision = CrossEntryFailoverPolicy.select(
                List.of(degradedMember(1, 0, true, 10), unacceptableMember(2, 1, true, 10), member(3, 2, true, 10)),
                1L, false, 3, true);

        assertTrue(decision.switchRequired());
        assertEquals(3L, decision.targetId());
    }

    private CrossEntryFailoverPolicy.Member member(long id, int priority, boolean healthy, int successCount) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount, false, true);
    }

    private CrossEntryFailoverPolicy.Member degradedMember(long id, int priority, boolean healthy, int successCount) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount, true, true);
    }

    private CrossEntryFailoverPolicy.Member unacceptableMember(long id, int priority, boolean healthy, int successCount) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount, false, false);
    }
}
