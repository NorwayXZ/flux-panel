package com.admin.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SmartEntryDnsBindingsServiceTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SmartEntryDnsBindingsService service = new SmartEntryDnsBindingsService(jdbc);

    @Test void movingOneStrategyPreservesOtherPoliciesAndRemovesManualWanMapping() {
        when(jdbc.queryForList(contains("ORDER BY connector_id FOR UPDATE"))).thenReturn(List.of(
                Map.of("connector_id", 11L, "smart_entry_group_ids", "[7,8]", "interface_carriers", "{}"),
                Map.of("connector_id", 12L, "smart_entry_group_ids", "[9]", "interface_carriers", "{\"pppoe-wan\":\"mobile\"}")));
        when(jdbc.queryForList(contains("SELECT id,user_id"), eq(12L))).thenReturn(List.of(Map.of("id", 12L, "user_id", 1)));
        service.setBindings(7L, List.of(12L));
        verify(jdbc).update(startsWith("UPDATE openwrt_dns_resolver SET smart_entry_group_ids"), eq("[8]"), eq(false), anyLong(), eq(11L));
        verify(jdbc).update(startsWith("UPDATE openwrt_dns_resolver SET smart_entry_group_ids"), eq("[9,7]"), eq(true), anyLong(), eq(12L));
    }

    @Test void identicalAutomaticBindingsDoNotAdvanceRevision() {
        when(jdbc.queryForList(contains("ORDER BY connector_id FOR UPDATE"))).thenReturn(List.of(Map.of("connector_id", 11L, "smart_entry_group_ids", "[7]", "interface_carriers", "{}")));
        when(jdbc.queryForList(contains("SELECT id,user_id"), eq(11L))).thenReturn(List.of(Map.of("id", 11L, "user_id", 1)));
        service.setBindings(7L, List.of(11L));
        verify(jdbc, never()).update(anyString(), anyString(), anyBoolean(), anyLong(), anyLong());
    }

    @Test void deletedOrNonDnsAgentIsRejectedBeforeChangingPolicies() {
        assertThrows(IllegalArgumentException.class, () -> service.setBindings(7L, List.of(99L)));
        verify(jdbc, never()).update(anyString(), anyString(), anyBoolean(), anyLong(), anyLong());
    }

    @Test void absentFieldKeepsLegacyBindingsAndExplicitEmptyListRemovesOnlyThatGroup() {
        service.setBindings(7L, null); verifyNoInteractions(jdbc);
        when(jdbc.queryForList(contains("ORDER BY connector_id FOR UPDATE"))).thenReturn(List.of(Map.of("connector_id", 11L, "smart_entry_group_ids", "[7,8]", "interface_carriers", "{}")));
        service.setBindings(7L, List.of());
        verify(jdbc).update(startsWith("UPDATE openwrt_dns_resolver SET smart_entry_group_ids"), eq("[8]"), eq(false), anyLong(), eq(11L));
    }
}
