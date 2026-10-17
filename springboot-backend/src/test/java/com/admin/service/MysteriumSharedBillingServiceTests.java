package com.admin.service;

import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysteriumSharedBillingServiceTests {
    @Test void rejectsUnauthenticatedSpendingBeforeNodeCommands() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new MysteriumSharedBillingService(jdbc,mock(NodeMapper.class),mock(MysteriumService.class));
        assertThrows(IllegalArgumentException.class,()->service.pay("short",new JSONObject()));
        verifyNoInteractions(jdbc);
        assertThrows(IllegalArgumentException.class,()->service.pay("a".repeat(64),new JSONObject()));
    }
    @Test void doesNotTreatUnconfiguredNodesAsSharedMembers() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new MysteriumSharedBillingService(jdbc,mock(NodeMapper.class),mock(MysteriumService.class));
        assertEquals(7L,service.ownerFor(7L));
    }
    @Test void rejectsUnsafeConfigurationBeforeExportingAccount() {
        var jdbc=mock(JdbcTemplate.class);var nodes=mock(NodeMapper.class);
        var service=new MysteriumSharedBillingService(jdbc,nodes,mock(MysteriumService.class));
        assertNotEquals(0,service.configure(7L,List.of(8L),"http://example.com/api/v1/mysterium-shared/pay").getCode());
        assertNotEquals(0,service.configure(7L,List.of(8L),"https://user@example.com/api/v1/mysterium-shared/pay").getCode());
        verifyNoInteractions(nodes,jdbc);
    }
    @Test void rejectsConsumerMismatchBeforeSigning() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(startsWith("SELECT * FROM mysterium_shared_member"),any(Object[].class)))
                .thenReturn(List.of(Map.of("node_id",8L)));
        when(jdbc.queryForList("SELECT * FROM mysterium_shared_pool WHERE id=1 AND enabled=1"))
                .thenReturn(List.of(Map.of("consumer","0x1111","owner_node_id",7L)));
        var service=new MysteriumSharedBillingService(jdbc,mock(NodeMapper.class),mock(MysteriumService.class));
        assertThrows(IllegalArgumentException.class,()->service.pay("a".repeat(64),new JSONObject(Map.of("Consumer","0x2222"))));
    }
}
