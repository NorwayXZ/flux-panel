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

    private CrossEntryFailoverPolicy.Member member(long id, int priority, boolean healthy, int successCount) {
        return new CrossEntryFailoverPolicy.Member(id, priority, healthy, successCount);
    }
}
