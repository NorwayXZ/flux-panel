package com.admin.common.utils;

import com.admin.entity.Node;
import com.admin.entity.PortPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublishedServiceTargetUtilTests {
    @Test
    void usesLoopbackWhenEntryAndMappingShareTheSameServer() {
        Node entry = node(1L, "34.150.15.102");
        Node mapping = node(2L, "34.150.15.102");
        PortPool pool = pool("", "34.150.15.102");

        assertEquals("127.0.0.1:20000", PublishedServiceTargetUtil.resolve(entry, mapping, pool, 20000));
    }

    @Test
    void usesPublishedAddressForCrossNodeIngress() {
        Node entry = node(1L, "47.242.115.72");
        Node mapping = node(2L, "34.150.15.102");
        PortPool pool = pool("", "34.150.15.102");

        assertEquals("34.150.15.102:20000", PublishedServiceTargetUtil.resolve(entry, mapping, pool, 20000));
    }

    @Test
    void bracketsIpv6PublishedAddress() {
        Node entry = node(1L, "47.242.115.72");
        Node mapping = node(2L, "2001:db8::20");
        PortPool pool = pool("", "[2001:db8::20]");

        assertEquals("[2001:db8::20]:443", PublishedServiceTargetUtil.resolve(entry, mapping, pool, 443));
    }

    private Node node(long id, String address) {
        Node node = new Node();
        node.setId(id);
        node.setServerIp(address);
        return node;
    }

    private PortPool pool(String bindIp, String publicHost) {
        PortPool pool = new PortPool();
        pool.setBindIp(bindIp);
        pool.setPublicHost(publicHost);
        return pool;
    }
}
