package com.admin.common.utils;

import com.admin.common.dto.ForwardRouteDto;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ForwardRouteFailoverPolicy {

    private static final String MODE_FAILOVER = "failover";
    private static final String MODE_LATENCY = "latency";
    private static final String STATUS_HEALTHY = "healthy";

    private ForwardRouteFailoverPolicy() {
    }

    public static Decision select(
            String mode,
            List<ForwardRouteDto> routes,
            ForwardRouteDto current,
            long lastSwitchTime,
            long now,
            Settings settings
    ) {
        List<ForwardRouteDto> healthy = routes.stream()
                .filter(ForwardRouteFailoverPolicy::isHealthy)
                .toList();
        if (current == null || healthy.isEmpty()) {
            return Decision.stay(current, "没有可用的候选线路");
        }

        if (MODE_FAILOVER.equals(mode)) {
            ForwardRouteDto preferred = healthy.stream()
                    .min(Comparator.comparingInt(ForwardRouteFailoverPolicy::priority))
                    .orElse(current);
            if (!isHealthy(current)) {
                return Objects.equals(preferred.getTunnelId(), current.getTunnelId())
                        ? Decision.stay(current, "当前线路异常，暂无其他可用线路")
                        : Decision.switchTo(preferred, failureReason(current, preferred), true);
            }
            if (Objects.equals(preferred.getTunnelId(), current.getTunnelId())
                    || priority(preferred) >= priority(current)) {
                return Decision.stay(current, "当前线路保持最高可用优先级");
            }
            if (!isStable(preferred, now, settings.failbackStableMs())) {
                return Decision.stay(current, "高优先级线路仍在恢复稳定期");
            }
            if (!cooldownElapsed(lastSwitchTime, now, settings.switchCooldownMs())) {
                return Decision.stay(current, "线路切换仍在冷却期");
            }
            return Decision.switchTo(
                    preferred,
                    routeName(preferred) + " 已稳定恢复，自动回切主线路",
                    false
            );
        }

        if (MODE_LATENCY.equals(mode)) {
            ForwardRouteDto best = healthy.stream()
                    .filter(route -> route.getLatency() != null)
                    .min(Comparator.comparingDouble(ForwardRouteDto::getLatency))
                    .orElse(current);
            if (!isHealthy(current)) {
                return Objects.equals(best.getTunnelId(), current.getTunnelId())
                        ? Decision.stay(current, "当前线路异常，暂无其他可用线路")
                        : Decision.switchTo(best, failureReason(current, best), true);
            }
            if (Objects.equals(best.getTunnelId(), current.getTunnelId()) || best.getLatency() == null
                    || current.getLatency() == null) {
                return Decision.stay(current, "当前线路已是最低延迟线路");
            }
            if (!isStable(best, now, settings.failbackStableMs())) {
                return Decision.stay(current, "低延迟候选线路仍在恢复稳定期");
            }
            if (!cooldownElapsed(lastSwitchTime, now, settings.switchCooldownMs())) {
                return Decision.stay(current, "线路切换仍在冷却期");
            }
            if (best.getLatency() + settings.latencyGapMs() >= current.getLatency()) {
                return Decision.stay(current, "延迟改善未达到切换阈值");
            }
            return Decision.switchTo(
                    best,
                    String.format("%s 延迟更低（%.0f ms -> %.0f ms）", routeName(best), current.getLatency(), best.getLatency()),
                    false
            );
        }

        return Decision.stay(current, "单线路模式不执行自动切换");
    }

    private static boolean isHealthy(ForwardRouteDto route) {
        return route != null && STATUS_HEALTHY.equals(route.getStatus());
    }

    private static boolean isStable(ForwardRouteDto route, long now, long stableMs) {
        return route.getHealthySince() != null && now - route.getHealthySince() >= stableMs;
    }

    private static boolean cooldownElapsed(long lastSwitchTime, long now, long cooldownMs) {
        return lastSwitchTime <= 0 || now - lastSwitchTime >= cooldownMs;
    }

    private static int priority(ForwardRouteDto route) {
        return route.getPriority() == null ? Integer.MAX_VALUE : route.getPriority();
    }

    private static String failureReason(ForwardRouteDto current, ForwardRouteDto selected) {
        return routeName(current) + " 异常，自动切换到 " + routeName(selected);
    }

    private static String routeName(ForwardRouteDto route) {
        return route.getTunnelName() == null || route.getTunnelName().isBlank()
                ? "线路 " + route.getTunnelId()
                : route.getTunnelName();
    }

    public record Settings(long switchCooldownMs, long failbackStableMs, double latencyGapMs) {
    }

    public record Decision(ForwardRouteDto selected, boolean switchRequired, boolean emergency, String reason) {
        private static Decision stay(ForwardRouteDto current, String reason) {
            return new Decision(current, false, false, reason);
        }

        private static Decision switchTo(ForwardRouteDto selected, String reason, boolean emergency) {
            return new Decision(selected, true, emergency, reason);
        }
    }
}
