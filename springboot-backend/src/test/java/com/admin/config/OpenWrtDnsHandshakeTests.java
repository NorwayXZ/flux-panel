package com.admin.config;

import com.admin.entity.InternalConnector;
import com.admin.mapper.InternalConnectorMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OpenWrtDnsHandshakeTests {
    @Test void panelCannotSendForwardingCommandsToADnsOnlySession(){
        var session=mock(org.springframework.web.socket.WebSocketSession.class);
        when(session.getAttributes()).thenReturn(java.util.Map.of("connectorRole","openwrt_dns"));
        var sessions=(java.util.concurrent.ConcurrentHashMap<Long,org.springframework.web.socket.WebSocketSession>)ReflectionTestUtils.getField(com.admin.common.utils.WebSocketServer.class,"connectorSessions");
        sessions.put(9000007L,session);
        try{
            var response=com.admin.common.utils.WebSocketServer.sendConnectorMsg(9000007L,java.util.Map.of(),"AddService",1);
            assertTrue(response.getMsg().contains("仅接受 DNS 配置"));
            verify(session,never()).sendMessage(any());
        }catch(java.io.IOException e){throw new RuntimeException(e);}
        finally{sessions.remove(9000007L,session);}
    }
    private boolean handshake(String storedRole,String requestedRole,String version)throws Exception{
        var mapper=mock(InternalConnectorMapper.class);var connector=new InternalConnector();
        connector.setId(7L);connector.setConnectorRole(storedRole);
        when(mapper.selectOne(any())).thenReturn(connector);
        var interceptor=new WebSocketInterceptor();ReflectionTestUtils.setField(interceptor,"internalConnectorMapper",mapper);
        var request=new MockHttpServletRequest();request.setParameter("type","2");request.setParameter("secret","test-router-secret");request.setParameter("version",version);
        if(requestedRole!=null)request.setParameter("role",requestedRole);
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request),new ServletServerHttpResponse(new MockHttpServletResponse()),mock(WebSocketHandler.class),new HashMap<>());
    }
    @Test void dnsIdentityRequiresDnsRoleAndSupportedVersion()throws Exception{
        assertTrue(handshake("openwrt_dns","openwrt_dns","2.53.0"));
        assertFalse(handshake("openwrt_dns",null,"2.53.0"));
        assertFalse(handshake("openwrt_dns","openwrt_dns","2.52.2"));
    }
    @Test void ordinaryConnectorCannotUseDnsResolverIdentity()throws Exception{
        assertFalse(handshake("service","openwrt_dns","2.53.0"));
        assertTrue(handshake("service",null,"2.52.0"));
        assertTrue(handshake(null,null,"2.52.0"));
    }
}
