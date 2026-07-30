package com.admin.common.utils;

import com.admin.entity.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectServiceTargetUtilTests {
    @Test
    void usesLoopbackForWildcardListenerOnSameNode() {
        Node node = node(1L, "203.0.113.10");
        assertEquals("127.0.0.1:54321", DirectServiceTargetUtil.resolve(node, node, "0.0.0.0", 54321));
        assertEquals("[::1]:54321", DirectServiceTargetUtil.resolve(node, node, "::", 54321));
    }

    @Test
    void rejectsLoopbackListenerAcrossNodes() {
        assertThrows(IllegalArgumentException.class, () -> DirectServiceTargetUtil.resolve(
                node(1L, "203.0.113.10"), node(2L, "203.0.113.11"), "127.0.0.1", 54321));
    }

    private Node node(long id, String address) {
        Node node = new Node();
        node.setId(id);
        node.setIp(address);
        node.setServerIp(address);
        return node;
    }
}
