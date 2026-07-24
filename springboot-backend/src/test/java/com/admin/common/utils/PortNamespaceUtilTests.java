package com.admin.common.utils;

import com.admin.entity.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortNamespaceUtilTests {

    @Test
    void normalizesEquivalentServerAddresses() {
        Node first = node(1L, "HK-BGP.EXAMPLE.COM.");
        Node second = node(2L, "hk-bgp.example.com");

        assertEquals(PortNamespaceUtil.fromNode(first), PortNamespaceUtil.fromNode(second));
    }

    @Test
    void keepsAddresslessNodesInSeparatePools() {
        assertEquals("node:1", PortNamespaceUtil.fromNode(node(1L, "")));
        assertEquals("node:2", PortNamespaceUtil.fromNode(node(2L, null)));
    }

    private Node node(Long id, String serverIp) {
        Node node = new Node();
        node.setId(id);
        node.setServerIp(serverIp);
        return node;
    }
}
