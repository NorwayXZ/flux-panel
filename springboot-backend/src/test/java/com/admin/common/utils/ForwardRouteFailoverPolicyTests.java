package com.admin.common.utils;

import com.admin.common.dto.ForwardRouteDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardRouteFailoverPolicyTests {

    private static final long NOW = 1_000_000L;
    private static final ForwardRouteFailoverPolicy.Settings SETTINGS =
            new ForwardRouteFailoverPolicy.Settings(120_000L, 180_000L, 15.0);

    @Test
    void switchesImmediatelyWhenCurrentRouteFails() {
        ForwardRouteDto primary = route(1, 0, "unhealthy", null, null);
        ForwardRouteDto backup = route(2, 1, "healthy", 80.0, NOW - 10_000L);

        ForwardRouteFailoverPolicy.Decision decision = ForwardRouteFailoverPolicy.select(
                "failover", List.of(primary, backup), primary, NOW - 5_000L, NOW, SETTINGS
        );

        assertTrue(decision.switchRequired());
        assertTrue(decision.emergency());
        assertEquals(2, decision.selected().getTunnelId());
    }

    @Test
    void waitsForStableRecoveryBeforeFailback() {
        ForwardRouteDto primary = route(1, 0, "healthy", 60.0, NOW - 30_000L);
        ForwardRouteDto backup = route(2, 1, "healthy", 80.0, NOW - 600_000L);

        ForwardRouteFailoverPolicy.Decision decision = ForwardRouteFailoverPolicy.select(
                "failover", List.of(primary, backup), backup, NOW - 300_000L, NOW, SETTINGS
        );

        assertFalse(decision.switchRequired());
        assertEquals(2, decision.selected().getTunnelId());
    }

    @Test
    void failsBackAfterStableRecoveryAndCooldown() {
        ForwardRouteDto primary = route(1, 0, "healthy", 60.0, NOW - 240_000L);
        ForwardRouteDto backup = route(2, 1, "healthy", 80.0, NOW - 600_000L);

        ForwardRouteFailoverPolicy.Decision decision = ForwardRouteFailoverPolicy.select(
                "failover", List.of(primary, backup), backup, NOW - 180_000L, NOW, SETTINGS
        );

        assertTrue(decision.switchRequired());
        assertFalse(decision.emergency());
        assertEquals(1, decision.selected().getTunnelId());
    }

    @Test
    void latencyModeIgnoresSmallImprovement() {
        ForwardRouteDto current = route(1, 0, "healthy", 60.0, NOW - 600_000L);
        ForwardRouteDto candidate = route(2, 1, "healthy", 50.0, NOW - 600_000L);

        ForwardRouteFailoverPolicy.Decision decision = ForwardRouteFailoverPolicy.select(
                "latency", List.of(current, candidate), current, NOW - 300_000L, NOW, SETTINGS
        );

        assertFalse(decision.switchRequired());
        assertEquals(1, decision.selected().getTunnelId());
    }

    private ForwardRouteDto route(
            int id,
            int priority,
            String status,
            Double latency,
            Long healthySince
    ) {
        ForwardRouteDto route = new ForwardRouteDto();
        route.setTunnelId(id);
        route.setTunnelName("线路 " + id);
        route.setPriority(priority);
        route.setStatus(status);
        route.setLatency(latency);
        route.setHealthySince(healthySince);
        return route;
    }
}
