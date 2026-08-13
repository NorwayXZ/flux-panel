package com.admin.common.utils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CrossEntryFailoverPolicy {
    private static final String MODE_PAUSE = "pause";
    private static final String MODE_LOCK = "lock";

    private CrossEntryFailoverPolicy() {
    }

    public static Decision select(List<Member> members, Long activeId, boolean autoFailback,
                                  int recoveryThreshold, boolean cooldownElapsed) {
        return select(members, activeId, new Settings(autoFailback, recoveryThreshold, cooldownElapsed,
                true, false, true, true, true, false, 5, 10, 10, 20.0, "auto", null));
    }

    public static Decision select(List<Member> members, Long activeId, Settings settings) {
        Member active = members.stream().filter(member -> Objects.equals(member.id(), activeId)).findFirst().orElse(null);
        Member preferred = members.stream().min(Comparator.comparingInt(Member::priority)).orElse(null);

        if (MODE_PAUSE.equals(settings.manualControlMode())) {
            return Decision.stay("已暂停自动切换");
        }
        if (!settings.tcpLatencySelectionEnabled()
                && MODE_LOCK.equals(settings.manualControlMode())
                && settings.lockedMemberId() != null) {
            Member locked = members.stream().filter(member -> Objects.equals(member.id(), settings.lockedMemberId())).findFirst().orElse(null);
            if (locked == null) return Decision.stay("锁定入口不存在");
            if (active != null && Objects.equals(active.id(), locked.id())) return Decision.stay("已锁定当前入口");
            if (!locked.healthy()) return Decision.stay("锁定入口不可用");
            return Decision.switchTo(locked.id(), "手动锁定入口");
        }

        if (active == null || !active.healthy()) {
            Member target = members.stream()
                    .filter(Member::healthy)
                    .filter(member -> !member.degraded())
                    .filter(member -> !member.suppressed())
                    .filter(Member::acceptableForQualitySwitch)
                    .min(candidateComparator(active, settings)).orElse(null);
            if (target == null && settings.degradedFallbackEnabled()) {
                target = members.stream().filter(Member::healthy).filter(member -> !member.suppressed())
                        .min(qualityComparator(active, settings)).orElse(null);
            }
            if (target == null) {
                target = members.stream().filter(Member::healthy).filter(member -> !member.suppressed())
                        .min(candidateComparator(active, settings)).orElse(null);
            }
            if (target == null) {
                target = members.stream().filter(Member::healthy)
                        .min(Comparator.comparingInt(Member::priority)).orElse(null);
            }
            if (target == null || (active != null && Objects.equals(target.id(), active.id()))) {
                return Decision.stay("没有可用的备用入口");
            }
            String reason = active == null ? "当前入口不存在" : "入口连续检测失败";
            if (target.suppressed()) reason += "，仅剩抖动保护中的入口可用";
            return Decision.switchTo(target.id(), reason);
        }
        if (active.degraded()) {
            if (!settings.cooldownElapsed()) return Decision.stay("质量切换仍在冷却期");
            if (!settings.minResidencyElapsed()) return Decision.stay("当前入口驻留时间不足");
            Member target = members.stream()
                    .filter(member -> member.healthy() && !member.degraded() && !Objects.equals(member.id(), active.id()))
                    .filter(member -> !member.suppressed())
                    .filter(Member::acceptableForQualitySwitch)
                    .min(candidateComparator(active, settings)).orElse(null);
            if (target != null) return Decision.switchTo(target.id(), "当前入口质量劣化，自动切换");

            if (settings.degradedFallbackEnabled()) {
                Member best = members.stream().filter(Member::healthy)
                        .min(qualityComparator(active, settings)).orElse(null);
                if (best != null && !Objects.equals(best.id(), active.id())) {
                    return Decision.switchTo(best.id(), "全部入口质量不佳，选择差中最优");
                }
                return Decision.stay("当前入口质量劣化，但已是差中最优");
            }

            boolean hasSuppressedBackup = members.stream()
                    .anyMatch(member -> member.healthy() && !member.degraded() && !Objects.equals(member.id(), active.id())
                            && member.suppressed());
            if (hasSuppressedBackup) return Decision.stay("当前入口质量劣化，但备用入口处于抖动保护期");
            return Decision.stay("当前入口质量劣化，但没有质量正常的备用入口");
        }
        if (settings.tcpLatencySelectionEnabled()) {
            Member lowestLatency = members.stream()
                    .filter(Member::healthy)
                    .filter(member -> !member.degraded())
                    .filter(member -> !member.suppressed())
                    .filter(Member::acceptableForQualitySwitch)
                    .filter(member -> Objects.equals(member.id(), active.id())
                            || member.successCount() >= settings.recoveryThreshold())
                    .filter(member -> member.latencyMs() != null)
                    .min(tcpLatencyComparator()).orElse(null);
            if (lowestLatency == null) {
                return Decision.stay("没有可用于 TCP 延迟优选的稳定入口");
            }
            Member target = lowestLatency;
            if (isStableTcpCandidate(preferred, active, settings)
                    && preferred.latencyMs() <= lowestLatency.latencyMs() + settings.tcpPrimaryPreferenceToleranceMs()) {
                target = preferred;
            }
            if (Objects.equals(target.id(), active.id())) {
                if (preferred != null && Objects.equals(target.id(), preferred.id())) {
                    return Decision.stay("主入口在优先容忍范围内");
                }
                return Decision.stay("当前入口 TCP 延迟最低");
            }
            if (!settings.cooldownElapsed()) return Decision.stay("TCP 延迟优选仍在冷却期");
            if (!settings.minResidencyElapsed()) return Decision.stay("当前入口驻留时间不足");
            if (preferred != null && Objects.equals(target.id(), preferred.id())) {
                return Decision.switchTo(target.id(), "主入口延迟已回到优先范围，自动回切");
            }
            if (preferred == null || !Objects.equals(active.id(), preferred.id())) {
                if (!hasRequiredLatencyGain(active, target, settings.tcpLatencySwitchThresholdMs())) {
                    return Decision.stay("候选入口 TCP 延迟收益不足");
                }
            } else if (active.latencyMs() == null
                    || active.latencyMs() - target.latencyMs() <= settings.tcpPrimaryPreferenceToleranceMs()) {
                return Decision.stay("主入口仍在优先容忍范围内");
            }
            return Decision.switchTo(target.id(), "TCP 延迟优选，自动切换至最低延迟入口");
        }
        if (!settings.autoFailback() || preferred == null || Objects.equals(preferred.id(), active.id())) {
            return Decision.stay("保持当前入口");
        }
        if (!preferred.healthy() || preferred.successCount() < settings.recoveryThreshold()) {
            return Decision.stay("主入口尚未稳定恢复");
        }
        if (preferred.degraded()) {
            return Decision.stay("主入口质量尚未恢复");
        }
        if (preferred.suppressed()) {
            return Decision.stay("主入口处于质量抖动保护期");
        }
        if (!preferred.acceptableForQualitySwitch()) {
            return Decision.stay("主入口尚未达到固定延迟目标");
        }
        if (!settings.minResidencyElapsed()) return Decision.stay("当前入口驻留时间不足");
        if (!settings.cooldownElapsed()) return Decision.stay("回切仍在冷却期");
        return Decision.switchTo(preferred.id(), "主入口持续恢复，自动回切");
    }

    public record Member(long id, int priority, boolean healthy, int successCount, boolean degraded,
                         boolean acceptableForQualitySwitch, boolean suppressed,
                         Integer latencyMs, Double lossPercent, int flapCount, int failCount,
                         long entryNodeId, String entryAddress, String topologySignature, String faultKind,
                         boolean preheated, int penaltyLevel) {
        public Member(long id, int priority, boolean healthy, int successCount, boolean degraded,
                      boolean acceptableForQualitySwitch, boolean suppressed) {
            this(id, priority, healthy, successCount, degraded, acceptableForQualitySwitch, suppressed,
                    null, null, 0, 0, 0L, "", "", "none", true, 0);
        }

        public Member(long id, int priority, boolean healthy, int successCount, boolean degraded,
                      boolean acceptableForQualitySwitch, boolean suppressed,
                      Integer latencyMs, Double lossPercent, int flapCount, int failCount,
                      long entryNodeId, String entryAddress, String topologySignature, String faultKind, boolean preheated) {
            this(id, priority, healthy, successCount, degraded, acceptableForQualitySwitch, suppressed,
                    latencyMs, lossPercent, flapCount, failCount, entryNodeId, entryAddress,
                    topologySignature, faultKind, preheated, 0);
        }
    }

    public record Settings(boolean autoFailback, int recoveryThreshold, boolean cooldownElapsed,
                           boolean minResidencyElapsed, boolean degradedFallbackEnabled,
                           boolean sameFaultAvoidanceEnabled, boolean topologyAvoidanceEnabled,
                           boolean preheatPreferred,
                           boolean tcpLatencySelectionEnabled, int tcpLatencySwitchThresholdMs,
                           int tcpPrimaryPreferenceToleranceMs, int failbackGainMs, double failbackGainPercent,
                           String manualControlMode, Long lockedMemberId) {
        public Settings {
            manualControlMode = manualControlMode == null ? "auto" : manualControlMode;
            recoveryThreshold = Math.max(1, recoveryThreshold);
            tcpLatencySwitchThresholdMs = Math.max(0, tcpLatencySwitchThresholdMs);
            tcpPrimaryPreferenceToleranceMs = Math.max(0, tcpPrimaryPreferenceToleranceMs);
            failbackGainMs = Math.max(0, failbackGainMs);
            failbackGainPercent = Math.max(0.0, failbackGainPercent);
        }

        public Settings(boolean autoFailback, int recoveryThreshold, boolean cooldownElapsed,
                        boolean minResidencyElapsed, boolean degradedFallbackEnabled,
                        boolean sameFaultAvoidanceEnabled, boolean topologyAvoidanceEnabled,
                        boolean preheatPreferred, int failbackGainMs, double failbackGainPercent,
                        String manualControlMode, Long lockedMemberId) {
            this(autoFailback, recoveryThreshold, cooldownElapsed, minResidencyElapsed,
                    degradedFallbackEnabled, sameFaultAvoidanceEnabled, topologyAvoidanceEnabled,
                    preheatPreferred, false, 5, 10, failbackGainMs, failbackGainPercent,
                    manualControlMode, lockedMemberId);
        }
    }

    public record Decision(Long targetId, boolean switchRequired, String reason) {
        private static Decision stay(String reason) {
            return new Decision(null, false, reason);
        }

        private static Decision switchTo(long targetId, String reason) {
            return new Decision(targetId, true, reason);
        }
    }

    private static Comparator<Member> cleanComparator(Member active, Settings settings) {
        return Comparator
                .comparingInt((Member member) -> preheatPenalty(member, settings))
                .thenComparingInt(member -> sameFaultPenalty(active, member, settings))
                .thenComparingInt(member -> topologyPenalty(active, member, settings))
                .thenComparingInt(Member::penaltyLevel)
                .thenComparingInt(Member::priority);
    }

    private static Comparator<Member> candidateComparator(Member active, Settings settings) {
        if (!settings.tcpLatencySelectionEnabled()) return cleanComparator(active, settings);
        return Comparator
                .comparingInt((Member member) -> member.latencyMs() == null ? Integer.MAX_VALUE : member.latencyMs())
                .thenComparingDouble(member -> member.lossPercent() == null ? 0.0 : member.lossPercent())
                .thenComparingInt(member -> sameFaultPenalty(active, member, settings))
                .thenComparingInt(member -> topologyPenalty(active, member, settings))
                .thenComparingInt(Member::penaltyLevel)
                .thenComparingInt(member -> preheatPenalty(member, settings))
                .thenComparingInt(Member::priority);
    }

    private static Comparator<Member> tcpLatencyComparator() {
        return Comparator
                .comparingInt((Member member) -> member.latencyMs() == null ? Integer.MAX_VALUE : member.latencyMs())
                .thenComparingDouble(member -> member.lossPercent() == null ? 0.0 : member.lossPercent())
                .thenComparingInt(Member::priority);
    }

    private static boolean hasRequiredLatencyGain(Member active, Member target, int thresholdMs) {
        if (target.latencyMs() == null) return false;
        if (active.latencyMs() == null) return true;
        return active.latencyMs() - target.latencyMs() >= Math.max(1, thresholdMs);
    }

    private static boolean isStableTcpCandidate(Member member, Member active, Settings settings) {
        return member != null
                && member.healthy()
                && !member.degraded()
                && !member.suppressed()
                && member.acceptableForQualitySwitch()
                && member.latencyMs() != null
                && (Objects.equals(member.id(), active.id())
                || member.successCount() >= settings.recoveryThreshold());
    }

    private static Comparator<Member> qualityComparator(Member active, Settings settings) {
        return Comparator
                .comparingInt((Member member) -> member.healthy() ? 0 : 1)
                .thenComparingInt(member -> member.suppressed() ? 1 : 0)
                .thenComparingInt(member -> member.acceptableForQualitySwitch() ? 0 : 1)
                .thenComparingInt(member -> preheatPenalty(member, settings))
                .thenComparingDouble(member -> member.lossPercent() == null ? 0.0 : member.lossPercent())
                .thenComparingInt(member -> member.latencyMs() == null ? Integer.MAX_VALUE : member.latencyMs())
                .thenComparingInt(Member::flapCount)
                .thenComparingInt(Member::penaltyLevel)
                .thenComparingInt(Member::failCount)
                .thenComparingInt(member -> sameFaultPenalty(active, member, settings))
                .thenComparingInt(member -> topologyPenalty(active, member, settings))
                .thenComparingInt(Member::priority);
    }

    private static int preheatPenalty(Member member, Settings settings) {
        return settings.preheatPreferred() && !member.preheated() ? 1 : 0;
    }

    private static int sameFaultPenalty(Member active, Member member, Settings settings) {
        if (!settings.sameFaultAvoidanceEnabled() || active == null) return 0;
        String activeFault = normalizeFault(active.faultKind());
        String memberFault = normalizeFault(member.faultKind());
        if ("none".equals(activeFault) || "none".equals(memberFault)) return 0;
        return activeFault.equals(memberFault) ? 1 : 0;
    }

    private static int topologyPenalty(Member active, Member member, Settings settings) {
        if (!settings.topologyAvoidanceEnabled() || active == null) return 0;
        if (active.entryNodeId() > 0 && active.entryNodeId() == member.entryNodeId()) return 1;
        Set<String> activeKeys = CrossEntryTopology.keysFromSignatureOrAddress(active.topologySignature(), active.entryAddress());
        Set<String> memberKeys = CrossEntryTopology.keysFromSignatureOrAddress(member.topologySignature(), member.entryAddress());
        return CrossEntryTopology.overlaps(activeKeys, memberKeys) ? 1 : 0;
    }

    private static String normalizeFault(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

}
