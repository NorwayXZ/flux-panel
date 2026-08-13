package com.admin.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingConflictServiceTests {
    @Test
    void rejectsDnsRecordAlreadyOwnedByAnotherScheduler() {
        SchedulingConflictService service = new SchedulingConflictService(new StubJdbcTemplate(
                List.of(Map.of("id", 8L, "name", "三网线路")),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.assertDnsRecordAvailable("cross_entry", null, "Edge.Example.com", "a"));

        assertTrue(error.getMessage().contains("三网优化"));
        assertTrue(error.getMessage().contains("三网线路"));
    }

    @Test
    void rejectsExactForwardSetOwnedByAnotherScheduler() {
        SchedulingConflictService service = new SchedulingConflictService(new StubJdbcTemplate(
                List.of(),
                List.of(),
                List.of(
                        Map.of("groupId", 12L, "groupName", "运营商入口", "forwardId", 100L),
                        Map.of("groupId", 12L, "groupName", "运营商入口", "forwardId", 200L)),
                List.of(),
                List.of()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.assertForwardSetAvailable("source_ip_entry", null, List.of(200L, 100L, 100L)));

        assertTrue(error.getMessage().contains("三网优化"));
        assertTrue(error.getMessage().contains("运营商入口"));
    }

    @Test
    void allowsPartialForwardOverlapBecauseItIsNotTheSameSchedulingGroup() {
        SchedulingConflictService service = new SchedulingConflictService(new StubJdbcTemplate(
                List.of(),
                List.of(),
                List.of(
                        Map.of("groupId", 12L, "groupName", "运营商入口", "forwardId", 100L),
                        Map.of("groupId", 12L, "groupName", "运营商入口", "forwardId", 200L)),
                List.of(),
                List.of()));

        assertDoesNotThrow(() ->
                service.assertForwardSetAvailable("source_ip_entry", null, List.of(100L, 300L)));
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final List<Map<String, Object>> smartDns;
        private final List<Map<String, Object>> crossDns;
        private final List<Map<String, Object>> smartRoutes;
        private final List<Map<String, Object>> crossRoutes;
        private final List<Map<String, Object>> sourceRoutes;

        private StubJdbcTemplate(List<Map<String, Object>> smartDns,
                                 List<Map<String, Object>> crossDns,
                                 List<Map<String, Object>> smartRoutes,
                                 List<Map<String, Object>> crossRoutes,
                                 List<Map<String, Object>> sourceRoutes) {
            this.smartDns = smartDns;
            this.crossDns = crossDns;
            this.smartRoutes = smartRoutes;
            this.crossRoutes = crossRoutes;
            this.sourceRoutes = sourceRoutes;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("FROM smart_entry_group WHERE")) return smartDns;
            if (sql.contains("FROM cross_entry_failover_group WHERE")) return crossDns;
            if (sql.contains("FROM smart_entry_group g JOIN smart_entry_route")) return smartRoutes;
            if (sql.contains("FROM cross_entry_failover_group g JOIN cross_entry_failover_member")) return crossRoutes;
            if (sql.contains("FROM source_ip_entry_group g JOIN source_ip_entry_route")) return sourceRoutes;
            return List.of();
        }
    }
}
