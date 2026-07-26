package com.admin.common.utils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class CrossEntryFailoverPolicy {
    private CrossEntryFailoverPolicy() {
    }

    public static Decision select(List<Member> members, Long activeId, boolean autoFailback,
                                  int recoveryThreshold, boolean cooldownElapsed) {
        Member active = members.stream().filter(member -> Objects.equals(member.id(), activeId)).findFirst().orElse(null);
        Member preferred = members.stream().min(Comparator.comparingInt(Member::priority)).orElse(null);
        if (active == null || !active.healthy()) {
            Member target = members.stream().filter(Member::healthy)
                    .min(Comparator.comparingInt(Member::priority)).orElse(null);
            if (target == null || (active != null && Objects.equals(target.id(), active.id()))) {
                return Decision.stay("没有可用的备用入口");
            }
            return Decision.switchTo(target.id(), active == null ? "当前入口不存在" : "入口连续检测失败");
        }
        if (!autoFailback || preferred == null || Objects.equals(preferred.id(), active.id())) {
            return Decision.stay("保持当前入口");
        }
        if (!preferred.healthy() || preferred.successCount() < recoveryThreshold) {
            return Decision.stay("主入口尚未稳定恢复");
        }
        if (!cooldownElapsed) return Decision.stay("回切仍在冷却期");
        return Decision.switchTo(preferred.id(), "主入口持续恢复，自动回切");
    }

    public record Member(long id, int priority, boolean healthy, int successCount) {
    }

    public record Decision(Long targetId, boolean switchRequired, String reason) {
        private static Decision stay(String reason) {
            return new Decision(null, false, reason);
        }

        private static Decision switchTo(long targetId, String reason) {
            return new Decision(targetId, true, reason);
        }
    }
}
