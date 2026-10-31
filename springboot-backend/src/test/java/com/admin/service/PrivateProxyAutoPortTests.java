package com.admin.service;

import com.admin.common.dto.PortLedgerEntryDto;
import com.admin.common.dto.PrivateProxyCreateDto;
import com.admin.common.utils.AgentPortCheckUtil;
import com.admin.entity.Node;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import java.util.Map;
import javax.validation.Validation;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrivateProxyAutoPortTests {
    @Test void nullPortIsOptionalButExplicitInvalidPortIsRejected() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var dto = new PrivateProxyCreateDto();
        assertTrue(validator.validateProperty(dto, "listenPort").isEmpty());
        dto.setListenPort(0);
        assertFalse(validator.validateProperty(dto, "listenPort").isEmpty());
    }

    @Test void skipsLedgerAndSystemOccupancyAndChecksBothShadowsocksTransports() {
        var ledger = mock(PortLedgerService.class);
        var entry = new PortLedgerEntryDto(); entry.setPortStart(20000); entry.setPortEnd(20001);
        when(ledger.list(any())).thenReturn(Map.of("entries", List.of(entry)));
        var service = service(ledger);
        var node = node();
        doReturn(new AgentPortCheckUtil.Result(true, false, "occupied", List.of("tcp busy")),
                new AgentPortCheckUtil.Result(true, true, "available", List.of()))
                .when(service).checkCandidatePort(eq(node), anyList());
        var result = service.allocateListenPort(node, "shadowsocks2022", "");
        assertEquals(0, result.getCode());
        int chosen = (Integer) result.getData();
        assertTrue(chosen >= 20002 && chosen <= 20003);
        var checks = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(service, times(2)).checkCandidatePort(eq(node), checks.capture());
        for (Object value : checks.getAllValues()) {
            @SuppressWarnings("unchecked") var ports = (List<AgentPortCheckUtil.Check>) value;
            assertEquals(List.of("tcp", "udp"), ports.stream().map(AgentPortCheckUtil.Check::getNetwork).toList());
            assertTrue(ports.get(0).getPort() >= 20002);
        }
    }

    @Test void failsWithoutGuessingWhenAgentDoesNotRespondOrRangeIsFull() {
        var ledger = mock(PortLedgerService.class);
        when(ledger.list(any())).thenReturn(Map.of("entries", List.of()));
        var service = service(ledger); var node = node();
        doReturn(new AgentPortCheckUtil.Result(true, false, "Agent 无响应", List.of()))
                .when(service).checkCandidatePort(eq(node), anyList());
        assertNotEquals(0, service.allocateListenPort(node, "socks5", "").getCode());
        verify(service, times(1)).checkCandidatePort(eq(node), anyList());
        var entry = new PortLedgerEntryDto(); entry.setPortStart(20000); entry.setPortEnd(20003);
        when(ledger.list(any())).thenReturn(Map.of("entries", List.of(entry)));
        clearInvocations(service);
        assertNotEquals(0, service.allocateListenPort(node, "socks5", "").getCode());
        verify(service, never()).checkCandidatePort(any(), anyList());
    }

    private Node node() {
        var node = new Node(); node.setId(1L); node.setPortSta(20000); node.setPortEnd(20003); return node;
    }
    private PrivateProxyService service(PortLedgerService ledger) {
        return spy(new PrivateProxyService(null,null,null,null,ledger,null,null,
                mock(PlatformTransactionManager.class),null,null,null,null,null,"test-secret"));
    }
}
