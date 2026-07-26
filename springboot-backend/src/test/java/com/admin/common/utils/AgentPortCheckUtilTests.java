package com.admin.common.utils;

import com.admin.entity.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPortCheckUtilTests {
    @Test
    void keepsLegacyAgentsCompatibleWithLedgerOnlyChecks() {
        Node node = new Node();
        node.setId(8L);
        node.setVersion("2.11.0");

        AgentPortCheckUtil.Result result = AgentPortCheckUtil.check(
                node,
                List.of(new AgentPortCheckUtil.Check("tcp", "", 20000))
        );

        assertFalse(result.isChecked());
        assertTrue(result.isAvailable());
    }
}
