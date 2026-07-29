package com.admin.service;

import com.admin.entity.HomeProxyNatEvent;
import com.admin.entity.HomeProxyRoute;
import com.admin.mapper.HomeProxyNatEventMapper;
import com.admin.mapper.HomeProxyRouteMapper;
import com.admin.mapper.InternalConnectorMapper;
import com.admin.mapper.PortPoolMapper;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NatTraversalServiceTests {
    @Mock private HomeProxyRouteMapper routeMapper;
    @Mock private HomeProxyNatEventMapper eventMapper;
    @Mock private InternalConnectorMapper connectorMapper;
    @Mock private PortPoolMapper poolMapper;
    @InjectMocks private NatTraversalService service;

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

    @Test
    void recordsDirectPathAndAccumulatesTrafficDeltas() {
        HomeProxyRoute route = smartRoute();
        when(routeMapper.selectById(7L)).thenReturn(route);
        when(eventMapper.selectList(any())).thenReturn(Collections.emptyList());

        JSONObject direct = event(7L, "direct", "udp_direct");
        direct.getJSONObject("data").put("natType", "endpoint-independent-likely");
        direct.getJSONObject("data").put("detail", "UDP 直连已建立");
        service.handleAgentEvent(12L, direct);

        assertEquals(1L, route.getDirectSuccessCount());
        assertEquals("direct", route.getNatState());
        assertEquals("udp_direct", route.getActiveAccessPath());
        assertEquals("endpoint-independent-likely", route.getNatType());
        assertNull(route.getLastNatError());
        ArgumentCaptor<HomeProxyNatEvent> eventCaptor = ArgumentCaptor.forClass(HomeProxyNatEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals("direct_connected", eventCaptor.getValue().getEventType());

        JSONObject traffic = event(7L, null, null);
        traffic.put("type", "NatTraffic");
        JSONObject data = traffic.getJSONObject("data");
        data.put("directRxDelta", 11L);
        data.put("directTxDelta", 13L);
        data.put("relayRxDelta", 17L);
        data.put("relayTxDelta", 19L);
        service.handleAgentEvent(12L, traffic);

        assertEquals(11L, route.getDirectRxBytes());
        assertEquals(13L, route.getDirectTxBytes());
        assertEquals(17L, route.getRelayRxBytes());
        assertEquals(19L, route.getRelayTxBytes());
    }

    @Test
    void ignoresEventsFromAnUnrelatedConnector() {
        HomeProxyRoute route = smartRoute();
        when(routeMapper.selectById(7L)).thenReturn(route);

        service.handleAgentEvent(99L, event(7L, "direct", "udp_direct"));

        verify(routeMapper, never()).update(any(), any());
        verify(eventMapper, never()).insert(any());
    }

    @Test
    void removesOnlyEventsBeyondTheNewestHundred() {
        HomeProxyRoute route = smartRoute();
        HomeProxyNatEvent first = new HomeProxyNatEvent();
        first.setId(1L);
        HomeProxyNatEvent second = new HomeProxyNatEvent();
        second.setId(2L);
        when(routeMapper.selectById(7L)).thenReturn(route);
        when(eventMapper.selectList(any())).thenReturn(List.of(first, second));

        service.handleAgentEvent(12L, event(7L, "relay", "relay"));

        assertEquals(1L, route.getDirectFailureCount());
        verify(eventMapper).deleteById(1L);
        verify(eventMapper).deleteById(2L);
    }

    private HomeProxyRoute smartRoute() {
        HomeProxyRoute route = new HomeProxyRoute();
        route.setId(7L);
        route.setUserId(3);
        route.setConnectorId(11L);
        route.setSourceConnectorId(12L);
        route.setAccessMode("smart_nat");
        route.setState("active");
        route.setActiveAccessPath("relay");
        route.setDirectSuccessCount(0L);
        route.setDirectFailureCount(0L);
        route.setDirectRxBytes(0L);
        route.setDirectTxBytes(0L);
        route.setRelayRxBytes(0L);
        route.setRelayTxBytes(0L);
        return route;
    }

    private JSONObject event(long routeId, String state, String path) {
        JSONObject message = new JSONObject();
        message.put("type", "NatPathChanged");
        JSONObject data = new JSONObject();
        data.put("routeId", routeId);
        if (state != null) data.put("state", state);
        if (path != null) data.put("accessPath", path);
        message.put("data", data);
        return message;
    }
}
