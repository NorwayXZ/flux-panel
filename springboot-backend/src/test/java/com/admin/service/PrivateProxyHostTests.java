package com.admin.service;

import com.admin.entity.Node;
import com.admin.entity.PrivateProxy;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.service.impl.NodeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PrivateProxyHostTests {
    private final PrivateProxyService service = new PrivateProxyService(null,null,null,null,null,null,null,
            mock(PlatformTransactionManager.class),null,null,null,null,null,"test-secret");

    @Test void cleansLegacyNodeAddressAndExplicitHostWithoutRecreatingProxy() {
        var node = new Node(); node.setServerIp(" \u00a0\ufeffpqs.example.com\r\n"); node.setIp("1.2.3.4");
        var proxy = new PrivateProxy();
        assertEquals("pqs.example.com", service.clientHost(proxy, node));
        proxy.setConnectHost("\u200b custom.example.com\u3000");
        assertEquals("custom.example.com", service.clientHost(proxy, node));
        proxy.setConnectHost("\u00a0\ufeff");
        assertEquals("pqs.example.com", service.clientHost(proxy, node));
        node.setServerIp("\u00a0\u200b");
        assertEquals("1.2.3.4", service.clientHost(proxy, node));
        proxy.setConnectHost("pqs .example.com");
        assertNull(service.clientHost(proxy, node));
    }

    @Test void trimsNodeAddressBeforeSavingUpdate() throws Exception {
        var dto = new NodeUpdateDto(); dto.setId(1L); dto.setServerIp(" \u00a0pqs.example.com\ufeff\n");
        dto.setPortSta(1000); dto.setPortEnd(65535);
        var method = NodeServiceImpl.class.getDeclaredMethod("buildUpdateNode", NodeUpdateDto.class);
        method.setAccessible(true);
        Node node = (Node) method.invoke(new NodeServiceImpl(), dto);
        assertEquals("pqs.example.com", node.getServerIp());
    }
}
