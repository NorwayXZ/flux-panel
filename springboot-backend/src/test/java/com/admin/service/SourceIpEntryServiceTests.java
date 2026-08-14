package com.admin.service;

import com.admin.common.lang.R;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceIpEntryServiceTests {

    @Test
    @SuppressWarnings("unchecked")
    void debugUsesLongestPrefixAndExplainsSelectedRoute() {
        SourceIpEntryService service = new SourceIpEntryService(new StubJdbcTemplate(), null, null);

        R response = service.debug(Map.of("groupId", 1, "sourceIp", "203.0.113.200"));

        assertEquals(0, response.getCode());
        Map<String, Object> data = (Map<String, Object>) response.getData();
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        Map<String, Object> group = groups.get(0);
        Map<String, Object> selected = (Map<String, Object>) group.get("selectedRoute");

        assertEquals("VIP 客户", selected.get("ruleName"));
        assertEquals("203.0.113.128/25", selected.get("matchedCidr"));
        assertTrue(String.valueOf(group.get("reason")).contains("最长前缀"));
    }

    private static class StubJdbcTemplate extends JdbcTemplate {
        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.startsWith("SELECT * FROM source_ip_entry_group")) {
                return List.of(Map.of(
                        "id", 1L,
                        "name", "测试分流",
                        "enabled", 1,
                        "state", "active",
                        "listen_host", "",
                        "listen_port", 443));
            }
            if (sql.startsWith("SELECT r.id")) {
                return List.of(
                        route(10, "default", "default", "默认", 999, "", 1000),
                        route(11, "custom", "cidr", "普通网段", 100, "203.0.113.0/24", 1001),
                        route(12, "custom", "vip", "VIP 客户", 10, "203.0.113.128/25", 1002));
            }
            if (sql.startsWith("SELECT cidrs FROM source_ip_carrier_database")) {
                return List.of();
            }
            return List.of();
        }

        private static Map<String, Object> route(int id, String carrier, String ruleType, String ruleName,
                                                 int priority, String cidrs, int forwardId) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", (long) id);
            row.put("carrier", carrier);
            row.put("ruleType", ruleType);
            row.put("ruleName", ruleName);
            row.put("priority", priority);
            row.put("cidrs", cidrs);
            row.put("enabled", 1);
            row.put("backendForwardId", (long) forwardId);
            row.put("backendForwardName", "转发 " + forwardId);
            row.put("backendPort", 443);
            row.put("backendHost", "198.51.100.10");
            row.put("backendNodeName", "香港入口");
            row.put("qualityPolicy", "static");
            row.put("region", "");
            row.put("asn", "");
            row.put("tags", "");
            row.put("notes", "");
            return row;
        }
    }
}
