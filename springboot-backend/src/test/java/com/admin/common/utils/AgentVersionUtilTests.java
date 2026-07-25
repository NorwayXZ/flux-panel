package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentVersionUtilTests {
    @Test
    void acceptsEqualAndNewerVersions() {
        assertTrue(AgentVersionUtil.isAtLeast("2.7.0", "2.7.0"));
        assertTrue(AgentVersionUtil.isAtLeast("v2.7.1", "2.7.0"));
        assertTrue(AgentVersionUtil.isAtLeast("2.7.0-beta", "2.7.0"));
        assertTrue(AgentVersionUtil.isAtLeast("2.7", "2.7.0"));
        assertTrue(AgentVersionUtil.isAtLeast("2.8.0", "2.8.0"));
    }

    @Test
    void rejectsOldOrInvalidVersions() {
        assertFalse(AgentVersionUtil.isAtLeast("2.6.9", "2.7.0"));
        assertFalse(AgentVersionUtil.isAtLeast("2.7.1", "2.8.0"));
        assertFalse(AgentVersionUtil.isAtLeast("unknown", "2.7.0"));
        assertFalse(AgentVersionUtil.isAtLeast(null, "2.7.0"));
    }
}
