import { useCallback, useEffect, useMemo, useState } from "react";
import { Accordion, AccordionItem } from "@heroui/accordion";
import { useNavigate } from "react-router-dom";
import { Button } from "@heroui/button";
import { Card, CardBody } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Select, SelectItem, SelectSection } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import {
  Activity,
  ArrowDown,
  ArrowRight,
  ArrowUp,
  CheckCircle2,
  History,
  Pencil,
  Plus,
  RefreshCw,
  ShieldCheck,
  Trash2,
  TriangleAlert,
  X,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  checkCrossEntryGroup,
  deleteCrossEntryGroup,
  getCrossEntryEvents,
  getCrossEntryForwardOptions,
  getCrossEntryGroups,
  getCrossEntryProbeSources,
  getDnsZoneOptions,
  saveCrossEntryGroup,
  type CrossEntryEvent,
  type CrossEntryForwardOption,
  type CrossEntryGroup,
  type CrossEntryProbeSourceOverview,
  type CrossEntrySummary,
  type DnsZoneOption,
} from "@/api";
import { groupForwardOptionsByPort } from "@/utils/forward-option-groups";

type PresetProfileKey = "fast" | "standard" | "stable";
type ProfileKey = PresetProfileKey | "custom";

const profiles: Record<
  PresetProfileKey,
  {
    label: string;
    interval: number;
    timeout: number;
    failures: number;
    recovery: number;
    note: string;
  }
> = {
  fast: {
    label: "极速",
    interval: 2000,
    timeout: 1200,
    failures: 2,
    recovery: 3,
    note: "约 3-6 秒触发切换",
  },
  standard: {
    label: "标准",
    interval: 5000,
    timeout: 2000,
    failures: 2,
    recovery: 3,
    note: "约 8-15 秒触发切换",
  },
  stable: {
    label: "稳健",
    interval: 10000,
    timeout: 3000,
    failures: 3,
    recovery: 4,
    note: "适合网络波动较大的入口",
  },
};

const emptySummary: CrossEntrySummary = {
  total: 0,
  enabled: 0,
  healthy: 0,
  degraded: 0,
  switches: 0,
};
const emptyProbeSources: CrossEntryProbeSourceOverview = {
  nodes: [],
  connectors: [],
  minimumRemoteVersion: "2.19.0",
};
const emptyForm = {
  id: undefined as number | undefined,
  name: "",
  domain: "",
  dnsZoneId: "",
  recordId: "",
  recordType: "A" as "A" | "AAAA",
  ttl: "60",
  profile: "fast" as ProfileKey,
  probeIntervalMs: "2000",
  connectTimeoutMs: "1200",
  failureThreshold: "2",
  recoveryThreshold: "3",
  cooldownSeconds: "30",
  autoFailback: false,
  routingMode: "failover" as "failover" | "active_active",
  enabled: true,
  qualityEnabled: false,
  qualityProbeSourceType: "panel" as "panel" | "node" | "connector",
  qualityProbeSourceId: "",
  qualityProbeCount: "4",
  qualityDegradeThresholdMs: "100",
  qualityRecoverThresholdMs: "60",
  qualityDegradeFactor: "3",
  qualityRecoverFactor: "1.8",
  qualityDegradeSamples: "3",
  qualityRecoverSamples: "3",
  qualityLossThresholdPercent: "30",
  qualityP95ThresholdMs: "100",
  qualityJitterThresholdMs: "50",
  qualityFixedTargetEnabled: false,
  qualityFixedTargetMs: "20",
  qualityFixedTargetStrict: false,
  qualityFlapGuardEnabled: true,
  qualityFlapWindowSeconds: "900",
  qualityFlapThreshold: "3",
  qualityFlapSuppressSeconds: "1800",
  qualityPenaltyEnabled: true,
  qualityPenaltyResetSeconds: "86400",
  qualityPenaltyObserveSeconds: "900",
  smartSelectionEnabled: true,
  tcpLatencySelectionEnabled: false,
  tcpLatencySwitchThresholdMs: "5",
  tcpPrimaryPreferenceToleranceMs: "10",
  degradedFallbackEnabled: true,
  sameFaultAvoidanceEnabled: true,
  topologyAvoidanceEnabled: true,
  minResidencySeconds: "300",
  preheatEnabled: true,
  preheatBackupCount: "3",
  preheatStrictIsolation: true,
  postSwitchVerifyEnabled: true,
  postSwitchRejectSuppressSeconds: "600",
  dnsVerifyEnabled: true,
  manualControlMode: "auto" as "auto" | "pause" | "lock",
  lockedMemberId: "",
  manualLockUntil: "",
  manualLockDuration: "forever" as "forever" | "30m" | "2h",
  memberForwardIds: ["", ""],
};

const truthy = (value: boolean | number) => value === true || value === 1;
const timeText = (value?: number) =>
  value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "尚未检测";
const metricText = (value?: number, unit = "") =>
  typeof value === "number" && Number.isFinite(value)
    ? `${Math.round(value * 10) / 10}${unit}`
    : "-";
const durationText = (seconds?: number) => {
  const value = Number(seconds || 0);

  if (!Number.isFinite(value) || value <= 0) return "-";
  if (value % 3600 === 0) return `${value / 3600} 小时`;
  if (value >= 3600) return `${Math.round(value / 360) / 10} 小时`;
  if (value % 60 === 0) return `${value / 60} 分钟`;

  return `${value} 秒`;
};
const lockDurationOptions = [
  { key: "forever", label: "永久锁定", seconds: null },
  { key: "30m", label: "锁 30 分钟", seconds: 30 * 60 },
  { key: "2h", label: "锁 2 小时", seconds: 2 * 60 * 60 },
] as const;
const resolveLockUntil = (duration: typeof emptyForm.manualLockDuration) => {
  const option = lockDurationOptions.find((item) => item.key === duration);

  if (!option || option.seconds == null) return undefined;

  return Date.now() + option.seconds * 1000;
};
const inferLockDuration = (until?: number | null) => {
  if (!until) return "forever" as const;
  const remaining = Math.max(0, until - Date.now());

  if (remaining <= 45 * 60 * 1000) return "30m" as const;
  if (remaining <= 3 * 60 * 60 * 1000) return "2h" as const;

  return "forever" as const;
};
const lockDurationLabel = (value?: number | null) => {
  if (!value) return "永久锁定";
  const remaining = Math.max(0, Math.round((value - Date.now()) / 1000));

  if (remaining <= 0) return "已到期";
  if (remaining <= 30 * 60 + 60 && remaining >= 30 * 60 - 60)
    return "锁 30 分钟";
  if (remaining <= 2 * 60 * 60 + 120 && remaining >= 2 * 60 * 60 - 120)
    return "锁 2 小时";

  return `锁定 ${durationText(remaining)}`;
};
const sectionCardClass =
  "rounded-2xl border border-divider/70 bg-content2/60 p-4 shadow-sm";
const memberStatusCounts = (group: CrossEntryGroup) => {
  const now = Date.now();
  const members = group.members || [];

  return {
    healthy: members.filter((member) => member.status === "healthy").length,
    warming: members.filter(
      (member) =>
        (member.qualityState === "warming" ||
          member.qualityState === "unknown") &&
        Boolean(member.qualityCheckedAt),
    ).length,
    observing: members.filter((member) =>
      Boolean(
        member.qualityRecoveryObserveUntil &&
        member.qualityRecoveryObserveUntil > now,
      ),
    ).length,
    cooling: members.filter(
      (member) =>
        Boolean(
          member.qualitySuppressedUntil && member.qualitySuppressedUntil > now,
        ) &&
        !Boolean(
          member.switchRejectedUntil && member.switchRejectedUntil > now,
        ),
    ).length,
    blacklisted: members.filter((member) =>
      Boolean(member.switchRejectedUntil && member.switchRejectedUntil > now),
    ).length,
    disabled: members.filter(
      (member) => member.enabled === false || member.enabled === 0,
    ).length,
  };
};
const eventRouteText = (event?: CrossEntryEvent) => {
  if (!event) return "暂无自动切换";
  const from = event.fromForwardName || event.fromNodeName || "初始状态";
  const to = event.toForwardName || event.toNodeName || "未知入口";

  return `${from} → ${to}`;
};
const eventEndpointText = (event?: CrossEntryEvent) => {
  if (!event) return "";
  const from =
    event.fromEntryAddress && event.fromEntryPort
      ? `${event.fromEntryAddress}:${event.fromEntryPort}`
      : "";
  const to =
    event.toEntryAddress && event.toEntryPort
      ? `${event.toEntryAddress}:${event.toEntryPort}`
      : "";

  return from && to ? `${from} → ${to}` : to || from;
};
const faultSummaryText = (member: CrossEntryGroup["members"][number]) => {
  const parts = [
    ["连接", member.connectFaultCount],
    ["延迟", member.latencyFaultCount],
    ["P95", member.p95FaultCount],
    ["抖动", member.jitterFaultCount],
    ["丢包", member.lossFaultCount],
    ["保护", member.flapFaultCount],
    ["惩罚", member.qualityPenaltyEpisodeCount],
    ["黑名单", member.switchRejectCount],
    ["切换", member.switchTriggerCount],
  ]
    .filter(([, count]) => typeof count === "number" && count > 0)
    .map(([label, count]) => `${label} ${count}`);
  const total =
    typeof member.faultEpisodeCount === "number" ? member.faultEpisodeCount : 0;

  if (!parts.length && total <= 0) return "故障：暂无";
  const detail = parts.length ? ` · ${parts.join(" · ")}` : "";
  const lastFault = member.lastFaultAt
    ? ` · 最近 ${timeText(member.lastFaultAt)}`
    : "";

  return `故障总计 ${total}${detail}${lastFault}`;
};
const stateMeta = (state: CrossEntryGroup["state"]) =>
  ({
    healthy: { label: "运行正常", color: "success" as const },
    degraded: { label: "部分异常", color: "warning" as const },
    offline: { label: "全部离线", color: "danger" as const },
    switching: { label: "切换中", color: "warning" as const },
    error: { label: "切换失败", color: "danger" as const },
    unknown: { label: "等待检测", color: "default" as const },
  })[state] || { label: "等待检测", color: "default" as const };
const qualityMeta = (
  state?: CrossEntryGroup["members"][number]["qualityState"],
) =>
  ({
    healthy: { label: "质量正常", color: "success" as const },
    degraded: { label: "质量劣化", color: "warning" as const },
    warming: { label: "学习中", color: "secondary" as const },
    unknown: { label: "待学习", color: "default" as const },
  })[state || "unknown"] || { label: "待学习", color: "default" as const };
const qualityProbeMeta = (status?: CrossEntryGroup["qualityProbeStatus"]) =>
  ({
    ok: { label: "探测正常", color: "success" as const },
    warning: { label: "质量告警", color: "warning" as const },
    failed: { label: "探测失败", color: "danger" as const },
    pending: { label: "等待探测", color: "secondary" as const },
    disabled: { label: "未启用", color: "default" as const },
  })[status || "disabled"] || { label: "未启用", color: "default" as const };
const manualControlMeta = (mode?: CrossEntryGroup["manualControlMode"]) =>
  ({
    pause: { label: "已暂停自动切换", color: "warning" as const },
    lock: { label: "已锁定入口", color: "secondary" as const },
    auto: { label: "自动选择", color: "default" as const },
  })[mode || "auto"] || { label: "自动选择", color: "default" as const };

type FixedTargetMode = "prefer_best" | "strict";

const fixedTargetModeMeta: Record<
  FixedTargetMode,
  { label: string; detail: string }
> = {
  prefer_best: {
    label: "达标优先，没达标就选最优",
    detail:
      "先优先挑选达到目标值的备用入口；如果暂时没有达标入口，也会在差线路里选相对最优的那个。",
  },
  strict: {
    label: "只切到达标备用入口",
    detail:
      "只有延迟达到目标值的备用入口才会参与切换；如果没有达标入口，就保持当前规则结果。",
  },
};

const fixedTargetModeKey = (
  strict?: boolean | number | null,
): FixedTargetMode => (truthy(strict ?? true) ? "strict" : "prefer_best");
const fixedTargetModeLabel = (strict?: boolean | number | null) =>
  fixedTargetModeMeta[fixedTargetModeKey(strict)].label;
const fixedTargetModeDetail = (strict?: boolean | number | null) =>
  fixedTargetModeMeta[fixedTargetModeKey(strict)].detail;

type RuleState = "active" | "blocked" | "disabled";
type RuleLine = { label: string; detail: string; state: RuleState };
type StrategySummary = {
  title: string;
  detail: string;
  activeRules: RuleLine[];
  blockedRules: RuleLine[];
  decisionHint: string;
};
type FailoverForm = typeof emptyForm;

const ruleStateMeta: Record<
  RuleState,
  { label: string; color: "success" | "warning" | "default" }
> = {
  active: { label: "生效", color: "success" },
  blocked: { label: "互斥关闭", color: "warning" },
  disabled: { label: "未开启", color: "default" },
};
const ruleLine = (
  label: string,
  detail: string,
  state: RuleState = "active",
): RuleLine => ({ label, detail, state });
const probeSourceText = (
  type?: "panel" | "node" | "connector",
  id?: number | string,
) => {
  if (type === "node") return id ? `指定 Agent 节点 #${id}` : "指定 Agent 节点";
  if (type === "connector")
    return id ? `指定 Connector #${id}` : "指定 Connector";

  return "面板服务器";
};
const isMemberSuppressed = (member: CrossEntryGroup["members"][number]) =>
  Boolean(
    (member.qualitySuppressedUntil &&
      member.qualitySuppressedUntil > Date.now()) ||
    (member.switchRejectedUntil && member.switchRejectedUntil > Date.now()),
  );
const activeGroupFlags = (group: CrossEntryGroup) => {
  const activeActive = group.routingMode === "active_active";
  const tcpLatencySelectionEnabled =
    !activeActive && truthy(group.tcpLatencySelectionEnabled ?? false);
  const qualityEnabled =
    !tcpLatencySelectionEnabled && truthy(group.qualityEnabled || false);
  const fixedTargetEnabled =
    qualityEnabled && truthy(group.qualityFixedTargetEnabled || false);
  const flapGuardEnabled =
    qualityEnabled && truthy(group.qualityFlapGuardEnabled ?? true);
  const penaltyEnabled =
    flapGuardEnabled && truthy(group.qualityPenaltyEnabled ?? true);
  const smartSelectionEnabled =
    qualityEnabled && truthy(group.smartSelectionEnabled ?? true);

  return {
    activeActive,
    tcpLatencySelectionEnabled,
    qualityEnabled,
    fixedTargetEnabled,
    flapGuardEnabled,
    penaltyEnabled,
    smartSelectionEnabled,
  };
};
const currentDecisionHint = (group: CrossEntryGroup) => {
  const flags = activeGroupFlags(group);
  const active = group.members.find((item) => item.id === group.activeMemberId);
  const primary = group.members[0];

  if (!truthy(group.enabled)) return "自动检测已关闭，面板不会主动切换入口。";
  if (flags.activeActive)
    return "多入口同时写入 DNS，面板只摘除不可用入口，不做主备回切。";
  if (group.manualControlMode === "pause")
    return "自动切换已暂停，即使检测到异常也会保持当前入口。";
  if (group.manualControlMode === "lock")
    return group.manualLockUntil
      ? `当前处于锁定模式，直到 ${timeText(group.manualLockUntil)} 才恢复自动选择。`
      : "当前处于锁定模式，只会尝试使用你指定的入口。";
  if (!active)
    return "当前没有确定承载入口，下一轮检测会尝试回到主入口或健康备用。";
  if (active.switchRejectedUntil && active.switchRejectedUntil > Date.now())
    return `当前入口刚发生切换验证失败，处于黑名单保护期，直到 ${timeText(active.switchRejectedUntil)} 才会重新参与。`;
  if (active.status === "unhealthy")
    return "当前入口已不可用，会优先选择健康、未保护、未同类故障的备用入口。";
  if (
    active.qualityRecoveryObserveUntil &&
    active.qualityRecoveryObserveUntil > Date.now()
  )
    return `当前入口仍在恢复观察期，直到 ${timeText(active.qualityRecoveryObserveUntil)} 才会重新优先参与。`;
  if (flags.tcpLatencySelectionEnabled) {
    const measured = group.members.filter(
      (member) =>
        member.status === "healthy" &&
        !isMemberSuppressed(member) &&
        typeof member.qualityLatencyMs === "number",
    );
    const fastest = measured
      .slice()
      .sort((a, b) => (a.qualityLatencyMs || 0) - (b.qualityLatencyMs || 0))[0];
    const activeLatency = active.qualityLatencyMs;
    const tolerance = group.tcpPrimaryPreferenceToleranceMs ?? 10;
    const threshold = Math.max(1, group.tcpLatencySwitchThresholdMs ?? 5);

    if (!fastest || typeof activeLatency !== "number")
      return "等待 TCP 探测样本，暂时不会按最低延迟自动换线。";
    if (
      primary &&
      active.id === primary.id &&
      activeLatency <= (fastest.qualityLatencyMs || 0) + tolerance
    )
      return `主入口在 ${tolerance}ms 容忍范围内，继续使用主入口。`;
    if (
      fastest.id !== active.id &&
      activeLatency - (fastest.qualityLatencyMs || 0) >= threshold
    )
      return `最低延迟入口比当前快至少 ${threshold}ms，满足冷却和驻留后会切换。`;

    return "候选入口延迟收益不足，继续保持当前线路，避免来回跳。";
  }
  if (flags.qualityEnabled) {
    if (active.qualityState === "degraded")
      return flags.smartSelectionEnabled
        ? "当前入口质量劣化，会避开保护期、同类故障、同节点/同大网段线路；全部都差时差中选优。"
        : "当前入口质量劣化，满足冷却和恢复确认后会切到质量正常的备用入口。";
    if (active.switchRejectedUntil && active.switchRejectedUntil > Date.now())
      return `当前入口处于切换验证黑名单，直到 ${timeText(active.switchRejectedUntil)} 才能重新参与。`;
    if (isMemberSuppressed(active))
      return "当前入口处于质量保护或恢复观察期，不会自动回切到它，直到观察结束。";

    return "当前入口质量未劣化，暂时不会切换。";
  }
  if (truthy(group.autoFailback) && primary && active.id !== primary.id)
    return "主入口恢复并连续达标后，会按冷却时间自动回切。";

  return "当前入口可用时保持不动；只有连续连接失败才切到下一条备用。";
};
const explainGroupStrategy = (group: CrossEntryGroup): StrategySummary => {
  const flags = activeGroupFlags(group);
  const activeRules: RuleLine[] = [];
  const blockedRules: RuleLine[] = [];

  activeRules.push(
    ruleLine(
      "基础连通性检测",
      `${group.probeIntervalMs / 1000} 秒一次，连续 ${group.failureThreshold} 次失败才判定入口失效。`,
    ),
    ruleLine(
      "恢复确认",
      `入口连续 ${group.recoveryThreshold} 次恢复后，才允许重新参与选择。`,
    ),
  );

  if (flags.activeActive) {
    activeRules.push(
      ruleLine("多入口 DNS", "所有健康入口同时写入 DNS，异常入口会被摘除。"),
    );
    blockedRules.push(
      ruleLine(
        "主备回切",
        "多入口模式没有唯一当前入口，不执行回主线。",
        "blocked",
      ),
      ruleLine("质量容灾", "质量切换只作用于主备容灾模式。", "blocked"),
      ruleLine(
        "TCP 延迟优选",
        "DNS 多入口不能按连接实时选择最低 TCP 延迟。",
        "blocked",
      ),
    );
  } else if (flags.tcpLatencySelectionEnabled) {
    activeRules.push(
      ruleLine(
        "主线优先 TCP 延迟优选",
        `主线最多慢 ${group.tcpPrimaryPreferenceToleranceMs ?? 10}ms 仍继续用主线。`,
      ),
      ruleLine(
        "备用切换收益",
        `备用至少快 ${group.tcpLatencySwitchThresholdMs ?? 5}ms，才允许切过去。`,
      ),
      ruleLine(
        "探测源",
        probeSourceText(
          group.qualityProbeSourceType,
          group.qualityProbeSourceId,
        ),
      ),
      ruleLine(
        "最短驻留",
        `切换后至少保持 ${durationText(group.minResidencySeconds)}，避免频繁跳线。`,
      ),
    );
    blockedRules.push(
      ruleLine(
        "普通自动回切",
        "TCP 优选自己负责回主线，不再叠加普通回切。",
        "blocked",
      ),
      ruleLine(
        "质量容灾",
        "避免最低延迟策略和质量劣化策略互相抢线。",
        "blocked",
      ),
      ruleLine("锁定入口", "最低延迟自动选择与手动锁定互斥。", "blocked"),
      ruleLine(
        "备用预热/同故障避让",
        "该模式直接按稳定 TCP 延迟排序。",
        "blocked",
      ),
      ruleLine(
        "质量黑名单 / 恢复观察",
        "TCP 优选只看实时延迟，不叠加质量黑名单和恢复观察。",
        "blocked",
      ),
    );
  } else {
    if (truthy(group.autoFailback))
      activeRules.push(ruleLine("自动回主线", "主入口恢复稳定后自动切回。"));
    else activeRules.push(ruleLine("保持备用", "切到备用后不会主动回主线。"));
    if (flags.qualityEnabled) {
      activeRules.push(
        ruleLine(
          "质量容灾",
          `按 ${probeSourceText(group.qualityProbeSourceType, group.qualityProbeSourceId)} 探测 TCP 均值、P95、抖动和丢包。`,
        ),
        ruleLine(
          "基线判断",
          `延迟超过自身基线 ${group.qualityDegradeFactor || 3} 倍或兜底阈值才算劣化。`,
        ),
      );
      if (flags.fixedTargetEnabled)
        activeRules.push(
          ruleLine(
            "固定延迟目标",
            `目标 ${group.qualityFixedTargetMs || 20}ms；${fixedTargetModeDetail(group.qualityFixedTargetStrict)}`,
          ),
        );
      if (flags.flapGuardEnabled)
        activeRules.push(
          ruleLine(
            flags.penaltyEnabled ? "抖动保护 + 阶梯惩罚" : "抖动保护",
            `${durationText(group.qualityFlapWindowSeconds)} 内劣化 ${group.qualityFlapThreshold || 3} 次进入保护期。`,
          ),
        );
      activeRules.push(
        ruleLine(
          "冷却池 / 黑名单",
          `切换验证失败的入口会进入 ${durationText(group.postSwitchRejectSuppressSeconds)} 黑名单；恢复后仍可能先经过观察期。`,
        ),
      );
      if (flags.smartSelectionEnabled) {
        activeRules.push(
          ruleLine("差中选优", "全部入口都差时，选择丢包和延迟相对最好的。"),
          ruleLine(
            "避开同类故障",
            "主线是延迟/丢包/抖动问题时，优先避开同类问题备用。",
          ),
          ruleLine("避开同节点/同大网段", "优先使用不同节点、不同大网段入口。"),
        );
      }
      blockedRules.push(
        ruleLine(
          "TCP 延迟优选",
          "已使用质量容灾，不再启用最低延迟策略。",
          "blocked",
        ),
      );
    } else {
      blockedRules.push(
        ruleLine("质量容灾", "未开启，只按端口连通性判断故障。", "disabled"),
        ruleLine(
          "TCP 延迟优选",
          "未开启，备用顺序由你手动排列决定。",
          "disabled",
        ),
      );
    }
  }
  if (truthy(group.postSwitchVerifyEnabled ?? true))
    activeRules.push(
      ruleLine("切换后验证", "DNS 更新后会再探测目标入口，失败则回滚。"),
    );
  if (!flags.activeActive)
    activeRules.push(
      ruleLine(
        "人工锁定",
        group.manualControlMode === "lock"
          ? group.manualLockUntil
            ? `当前锁定到 ${timeText(group.manualLockUntil)}。`
            : "当前锁定为永久。"
          : "需要时可手动固定某个入口，并设置到期时间。",
      ),
    );
  if (truthy(group.dnsVerifyEnabled ?? true))
    activeRules.push(
      ruleLine("DNS 生效确认", "检查 Cloudflare 记录是否已指向目标入口。"),
    );

  return {
    title: flags.activeActive
      ? "多入口同时运行"
      : flags.tcpLatencySelectionEnabled
        ? "主线优先 TCP 延迟优选"
        : flags.qualityEnabled
          ? "主备容灾 + 质量容灾"
          : "主备容灾",
    detail: flags.activeActive
      ? "适合让 DNS 返回多条健康入口。"
      : flags.tcpLatencySelectionEnabled
        ? "适合一组入口物理距离接近，希望自动选 TCP 延迟更低的入口。"
        : flags.qualityEnabled
          ? "适合主入口仍可用但延迟、抖动、丢包明显变差时自动切备用。"
          : "适合只在入口端口不通时切换备用。",
    activeRules,
    blockedRules,
    decisionHint: currentDecisionHint(group),
  };
};
const explainFormStrategy = (form: FailoverForm): StrategySummary => {
  const activeRules: RuleLine[] = [
    ruleLine(
      "基础连通性检测",
      `${Number(form.probeIntervalMs || 0) / 1000} 秒一次，连续 ${form.failureThreshold} 次失败才切换。`,
    ),
    ruleLine(
      "恢复确认",
      `连续 ${form.recoveryThreshold} 次成功后才认为入口恢复。`,
    ),
  ];
  const blockedRules: RuleLine[] = [];

  if (form.routingMode === "active_active") {
    activeRules.push(
      ruleLine("多入口 DNS", "健康入口同时写入 DNS，失效入口自动摘除。"),
    );
    blockedRules.push(
      ruleLine("主备回切", "多入口模式没有唯一当前承载入口。", "blocked"),
      ruleLine("质量容灾", "质量切换只应用在主备容灾模式。", "blocked"),
      ruleLine(
        "TCP 延迟优选",
        "DNS 解析无法做到每条连接实时最低延迟。",
        "blocked",
      ),
    );

    return {
      title: "多入口同时运行",
      detail: "适合多个入口都能直接承载业务的场景。",
      activeRules,
      blockedRules,
      decisionHint:
        "保存后，所有健康入口会写入同一 DNS 记录；客户端缓存仍会影响实际生效时间。",
    };
  }

  if (form.tcpLatencySelectionEnabled) {
    activeRules.push(
      ruleLine(
        "主线优先 TCP 延迟优选",
        `主线最多慢 ${form.tcpPrimaryPreferenceToleranceMs}ms 仍继续使用主线。`,
      ),
      ruleLine(
        "备用切换收益",
        `备用至少快 ${form.tcpLatencySwitchThresholdMs}ms 才切换。`,
      ),
      ruleLine(
        "TCP 探测源",
        probeSourceText(form.qualityProbeSourceType, form.qualityProbeSourceId),
      ),
      ruleLine(
        "最短驻留",
        `切换后至少保持 ${durationText(Number(form.minResidencySeconds))}。`,
      ),
    );
    blockedRules.push(
      ruleLine("普通自动回切", "TCP 优选已包含回主线判断。", "blocked"),
      ruleLine("质量容灾", "避免两套自动选线规则互相抢占。", "blocked"),
      ruleLine(
        "固定延迟目标/抖动保护/预热",
        "最低 TCP 延迟模式下不参与选线。",
        "blocked",
      ),
      ruleLine("锁定入口", "锁定入口和自动最低延迟互斥。", "blocked"),
      ruleLine(
        "质量黑名单",
        "切换验证失败后的黑名单仍会生效，但不单独展示为选线规则。",
        "blocked",
      ),
    );

    return {
      title: "主线优先 TCP 延迟优选",
      detail:
        "主线正常且延迟差距不大时坚持主线，明显慢时才切到 TCP 延迟最低入口。",
      activeRules,
      blockedRules,
      decisionHint:
        "适合你说的“全是美国入口，就选 TCP 延迟最优秀”的场景，但不会因为 1-2ms 抖动来回跳。",
    };
  }

  activeRules.push(
    form.autoFailback
      ? ruleLine("自动回主线", "主入口恢复并连续达标后自动回切。")
      : ruleLine("保持备用", "切到备用后不主动回主线，直到当前入口故障。"),
  );
  if (form.manualControlMode === "pause")
    activeRules.push(ruleLine("暂停自动切换", "只检测，不自动改 DNS。"));
  if (form.manualControlMode === "lock")
    activeRules.push(
      ruleLine("锁定入口", "强制使用指定入口，其他自动策略不抢占。"),
    );

  if (form.qualityEnabled) {
    activeRules.push(
      ruleLine(
        "质量容灾",
        "主线没断但延迟、P95、抖动、丢包持续变差时也会切换。",
      ),
      ruleLine(
        "质量探测源",
        probeSourceText(form.qualityProbeSourceType, form.qualityProbeSourceId),
      ),
      ruleLine(
        "基线 + 兜底阈值",
        `超过自身基线 ${form.qualityDegradeFactor} 倍，或超过兜底 ${form.qualityDegradeThresholdMs}ms，才算劣化。`,
      ),
    );
    if (form.qualityFixedTargetEnabled)
      activeRules.push(
        ruleLine(
          "固定延迟目标",
          `目标 ${form.qualityFixedTargetMs}ms；${fixedTargetModeDetail(form.qualityFixedTargetStrict)}`,
        ),
      );
    if (form.qualityFlapGuardEnabled)
      activeRules.push(
        ruleLine(
          form.qualityPenaltyEnabled ? "抖动保护 + 阶梯惩罚" : "抖动保护",
          `${durationText(Number(form.qualityFlapWindowSeconds))} 内劣化 ${form.qualityFlapThreshold} 次进入保护期。`,
        ),
      );
    activeRules.push(
      ruleLine(
        "冷却池 / 黑名单",
        `切换验证失败的入口会被额外冷却 ${durationText(Number(form.postSwitchRejectSuppressSeconds || 600))}。`,
      ),
    );
    if (form.smartSelectionEnabled)
      activeRules.push(
        ruleLine(
          "智能选择",
          "避开同类故障、同节点/同大网段，全部差时差中选优。",
        ),
      );
    blockedRules.push(
      ruleLine("TCP 延迟优选", "质量容灾已接管自动选线。", "blocked"),
    );
  } else {
    blockedRules.push(
      ruleLine("质量容灾", "未开启，只按入口连通性切换。", "disabled"),
      ruleLine("TCP 延迟优选", "未开启，按主备顺序切换。", "disabled"),
    );
  }
  activeRules.push(
    form.postSwitchVerifyEnabled
      ? ruleLine("切换后验证", "切换后会验证目标入口，失败会回滚。")
      : ruleLine(
          "切换后验证",
          "未开启，DNS 更新后不再复测目标入口。",
          "disabled",
        ),
    form.dnsVerifyEnabled
      ? ruleLine("DNS 生效确认", "检查 Cloudflare 记录是否写入正确。")
      : ruleLine(
          "DNS 生效确认",
          "未开启，不检查 DNS 服务商返回值。",
          "disabled",
        ),
  );
  activeRules.push(
    ruleLine(
      "人工锁定",
      form.manualControlMode === "lock"
        ? form.manualLockDuration === "forever"
          ? "当前会永久固定到选定入口。"
          : `锁定会在 ${lockDurationOptions.find((item) => item.key === form.manualLockDuration)?.label || "指定时间"} 后自动解除。`
        : "需要时可手动锁定单个入口，并设置到期时间。",
    ),
  );

  return {
    title: form.qualityEnabled ? "主备容灾 + 质量容灾" : "主备容灾",
    detail: form.qualityEnabled
      ? "入口没断但质量变差也会自动切换。"
      : "只有入口端口连续失败时才按备用顺序切换。",
    activeRules,
    blockedRules,
    decisionHint:
      "第一条是主入口，后面按备用 1、备用 2 的顺序使用；上移/下移会直接改变优先级。",
  };
};
const eventActionText = (event: CrossEntryEvent) => {
  const reason = event.reason || "";

  if (event.status === "failed") return "切换失败";
  if (reason.includes("初始化")) return "初始化主入口";
  if (reason.includes("回切") || reason.includes("主入口延迟已回到"))
    return "回到主入口";
  if (reason.includes("TCP 延迟优选")) return "最低 TCP 延迟切换";
  if (reason.includes("质量劣化")) return "质量容灾切换";
  if (reason.includes("差中最优")) return "差中选优";
  if (
    reason.includes("切换验证失败黑名单") ||
    reason.includes("切换后目标入口验证失败")
  )
    return "切换验证黑名单";
  if (reason.includes("手动锁定到期")) return "锁定到期";
  if (reason.includes("连续检测失败") || reason.includes("当前入口不存在"))
    return "故障容灾切换";
  if (reason.includes("手动锁定")) return "手动锁定";

  return "自动切换";
};
const eventReasonText = (event: CrossEntryEvent) => {
  const reason = event.reason || "无原因记录";

  if (reason.includes("候选入口 TCP 延迟收益不足"))
    return "没有切换：候选入口不够快，避免来回跳。";
  if (reason.includes("主入口在优先容忍范围"))
    return "没有切换：主入口仍在你设置的容忍范围内。";
  if (reason.includes("质量抖动保护期"))
    return "没有回切：目标入口处于保护期。";
  if (reason.includes("驻留时间不足"))
    return "没有切换：当前线路还没达到最短驻留时间。";
  if (reason.includes("冷却期")) return "没有切换：仍在冷却期。";
  if (
    reason.includes("切换验证失败黑名单") ||
    reason.includes("切换后目标入口验证失败")
  )
    return "没有切换：目标入口刚失败过，还在黑名单冷却期。";
  if (reason.includes("手动锁定到期"))
    return "没有切换：手动锁定已经到期，自动规则重新接管。";

  return reason;
};

function StrategySummaryPanel({
  summary,
  compact = false,
}: {
  summary: StrategySummary;
  compact?: boolean;
}) {
  const activeCount = summary.activeRules.filter(
    (item) => item.state === "active",
  ).length;
  const blockedCount = summary.blockedRules.length;
  const visibleActiveRules = compact
    ? summary.activeRules.slice(0, 5)
    : summary.activeRules;
  const visibleBlockedRules = compact
    ? summary.blockedRules.slice(0, 4)
    : summary.blockedRules;

  const renderRuleGrid = (items: RuleLine[], prefix: string) => (
    <div className="grid gap-2 md:grid-cols-2">
      {items.map((item) => {
        const meta = ruleStateMeta[item.state];

        return (
          <div
            key={`${prefix}-${item.label}`}
            className={`rounded-xl border px-3 py-2 ${item.state === "active" ? "border-success/30 bg-success-50/50 dark:bg-success-500/10" : item.state === "blocked" ? "border-warning/30 bg-warning-50/50 dark:bg-warning-500/10" : "border-default-200 bg-default-50/60 dark:bg-default-900/30"}`}
          >
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs font-medium">{item.label}</span>
              <Chip color={meta.color} size="sm" variant="flat">
                {meta.label}
              </Chip>
            </div>
            <p className="mt-1 text-xs leading-5 text-default-500">
              {item.detail}
            </p>
          </div>
        );
      })}
    </div>
  );

  if (!compact)
    return (
      <div className="space-y-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs text-default-500">当前生效模式</p>
            <h3 className="mt-1 text-sm font-semibold">{summary.title}</h3>
            <p className="mt-1 text-xs leading-5 text-default-500">
              {summary.detail}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Chip color="primary" size="sm" variant="flat">
              {activeCount} 条生效
            </Chip>
            <Chip color="warning" size="sm" variant="flat">
              {blockedCount} 条互斥 / 未启用
            </Chip>
          </div>
        </div>
        <p className="rounded-xl border border-primary/15 bg-primary-50/50 px-3 py-2 text-xs leading-5 text-primary-700 dark:bg-primary-500/10 dark:text-primary-200">
          {summary.decisionHint}
        </p>
        <Accordion
          className="px-0"
          defaultExpandedKeys={["active"]}
          selectionMode="multiple"
          variant="bordered"
        >
          <AccordionItem
            key="active"
            aria-label="生效规则"
            title={
              <div className="flex w-full flex-wrap items-center justify-between gap-2 pr-2">
                <span className="text-sm font-medium">生效规则</span>
                <Chip color="success" size="sm" variant="flat">
                  {summary.activeRules.length} 项
                </Chip>
              </div>
            }
          >
            {renderRuleGrid(summary.activeRules, "active")}
          </AccordionItem>
          <AccordionItem
            key="blocked"
            aria-label="互斥与未启用"
            title={
              <div className="flex w-full flex-wrap items-center justify-between gap-2 pr-2">
                <span className="text-sm font-medium">互斥 / 未启用</span>
                <Chip color="warning" size="sm" variant="flat">
                  {summary.blockedRules.length} 项
                </Chip>
              </div>
            }
          >
            {visibleBlockedRules.length > 0 ? (
              renderRuleGrid(summary.blockedRules, "blocked")
            ) : (
              <p className="text-xs text-default-500">暂无互斥规则。</p>
            )}
          </AccordionItem>
        </Accordion>
      </div>
    );

  return (
    <div className="border-y border-divider py-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs text-default-500">当前生效模式</p>
          <h3 className="mt-1 text-sm font-semibold">{summary.title}</h3>
          <p className="mt-1 text-xs leading-5 text-default-500">
            {summary.detail}
          </p>
        </div>
        <Chip color="primary" size="sm" variant="flat">
          {activeCount} 条生效
        </Chip>
      </div>
      <p className="mt-3 rounded-md bg-default-100 px-3 py-2 text-xs leading-5 text-default-600 dark:bg-default-900/40">
        {summary.decisionHint}
      </p>
      <div className="mt-3 grid gap-2 md:grid-cols-2">
        {visibleActiveRules.map((item) => {
          const meta = ruleStateMeta[item.state];

          return (
            <div
              key={`active-${item.label}`}
              className="border-l-2 border-success px-3 py-2"
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-medium">{item.label}</span>
                <Chip color={meta.color} size="sm" variant="flat">
                  {meta.label}
                </Chip>
              </div>
              <p className="mt-1 text-xs leading-5 text-default-500">
                {item.detail}
              </p>
            </div>
          );
        })}
        {visibleBlockedRules.map((item) => {
          const meta = ruleStateMeta[item.state];

          return (
            <div
              key={`blocked-${item.label}`}
              className="border-l-2 border-warning px-3 py-2"
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-medium">{item.label}</span>
                <Chip color={meta.color} size="sm" variant="flat">
                  {meta.label}
                </Chip>
              </div>
              <p className="mt-1 text-xs leading-5 text-default-500">
                {item.detail}
              </p>
            </div>
          );
        })}
      </div>
      {compact &&
        summary.activeRules.length + summary.blockedRules.length >
          visibleActiveRules.length + visibleBlockedRules.length && (
          <p className="mt-2 text-xs text-default-400">
            完整规则可进入编辑窗口查看。
          </p>
        )}
    </div>
  );
}

export default function CrossEntryFailoverPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [groups, setGroups] = useState<CrossEntryGroup[]>([]);
  const [summary, setSummary] = useState<CrossEntrySummary>(emptySummary);
  const [forwardOptions, setForwardOptions] = useState<
    CrossEntryForwardOption[]
  >([]);
  const [zoneOptions, setZoneOptions] = useState<DnsZoneOption[]>([]);
  const [probeSources, setProbeSources] =
    useState<CrossEntryProbeSourceOverview>(emptyProbeSources);
  const [formOpen, setFormOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [events, setEvents] = useState<CrossEntryEvent[]>([]);
  const [historyName, setHistoryName] = useState("");
  const [historyGroup, setHistoryGroup] = useState<CrossEntryGroup>();
  const [submitting, setSubmitting] = useState(false);
  const [checkingId, setCheckingId] = useState<number>();
  const [form, setForm] = useState(emptyForm);

  const loadData = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const [groupResult, optionResult, zoneResult, probeResult] =
      await Promise.allSettled([
        getCrossEntryGroups(),
        getCrossEntryForwardOptions(),
        getDnsZoneOptions(),
        getCrossEntryProbeSources(),
      ]);

    if (groupResult.status === "fulfilled" && groupResult.value.code === 0) {
      const groupRes = groupResult.value;

      setGroups(groupRes.data?.groups || []);
      setSummary(groupRes.data?.summary || emptySummary);
    } else if (!quiet) {
      const message =
        groupResult.status === "fulfilled"
          ? groupResult.value.msg
          : "加载入口容灾失败";

      toast.error(message || "加载入口容灾失败");
    }
    if (optionResult.status === "fulfilled" && optionResult.value.code === 0)
      setForwardOptions(optionResult.value.data || []);
    else if (!quiet) toast.error("入口候选线路加载失败，已保留旧选项");
    if (zoneResult.status === "fulfilled" && zoneResult.value.code === 0)
      setZoneOptions(zoneResult.value.data || []);
    else if (!quiet) toast.error("DNS Zone 加载失败，已保留旧选项");
    if (probeResult.status === "fulfilled" && probeResult.value.code === 0)
      setProbeSources(probeResult.value.data || emptyProbeSources);
    if (!quiet) setLoading(false);
  }, []);

  useEffect(() => {
    void loadData();
    const timer = window.setInterval(() => void loadData(true), 5000);

    return () => window.clearInterval(timer);
  }, [loadData]);

  const selectedOptions = useMemo(
    () =>
      form.memberForwardIds.map((id) =>
        forwardOptions.find((item) => String(item.id) === id),
      ),
    [form.memberForwardIds, forwardOptions],
  );
  const forwardGroups = useMemo(
    () => groupForwardOptionsByPort(forwardOptions),
    [forwardOptions],
  );
  const selectedZone = useMemo(
    () => zoneOptions.find((item) => String(item.id) === form.dnsZoneId),
    [form.dnsZoneId, zoneOptions],
  );
  const lockableMembers = useMemo(
    () => groups.find((item) => item.id === form.id)?.members || [],
    [form.id, groups],
  );
  const selectedPort = selectedOptions.find(Boolean)?.inPort;
  const selectionProblem = useMemo(() => {
    const selected = selectedOptions.filter(
      Boolean,
    ) as CrossEntryForwardOption[];

    if (selected.length < 2) return "至少选择两个不同入口的转发";
    if (new Set(selected.map((item) => item.inNodeId)).size !== selected.length)
      return "候选转发必须来自不同入口节点";
    if (new Set(selected.map((item) => item.inPort)).size !== 1)
      return "所有候选转发必须使用相同公网端口";

    return "";
  }, [selectedOptions]);
  const recentSwitches = useMemo(
    () =>
      groups
        .filter((group) => group.lastSwitchEvent)
        .map((group) => ({
          group,
          event: group.lastSwitchEvent as CrossEntryEvent,
        })),
    [groups],
  );
  const formStrategy = useMemo(() => explainFormStrategy(form), [form]);

  const openCreate = () => {
    setForm(emptyForm);
    setFormOpen(true);
  };

  const openEdit = (group: CrossEntryGroup) => {
    const profile = (Object.entries(profiles).find(
      ([, item]) =>
        item.interval === group.probeIntervalMs &&
        item.timeout === group.connectTimeoutMs &&
        item.failures === group.failureThreshold,
    )?.[0] || "custom") as ProfileKey;
    const tcpLatencySelectionEnabled = !Number.isFinite(
      Number(group.tcpLatencySelectionEnabled),
    )
      ? truthy(group.tcpLatencySelectionEnabled ?? false)
      : Number(group.tcpLatencySelectionEnabled) !== 0;

    setForm({
      id: group.id,
      name: group.name,
      domain: group.domain,
      dnsZoneId: group.dnsZoneId ? String(group.dnsZoneId) : "",
      recordId: group.recordId,
      recordType: group.recordType,
      ttl: String(group.ttl),
      profile,
      probeIntervalMs: String(group.probeIntervalMs),
      connectTimeoutMs: String(group.connectTimeoutMs),
      failureThreshold: String(group.failureThreshold),
      recoveryThreshold: String(group.recoveryThreshold),
      cooldownSeconds: String(group.cooldownSeconds),
      autoFailback: tcpLatencySelectionEnabled
        ? false
        : truthy(group.autoFailback),
      routingMode: group.routingMode || "failover",
      enabled: truthy(group.enabled),
      qualityEnabled: tcpLatencySelectionEnabled
        ? false
        : truthy(group.qualityEnabled || false),
      qualityProbeSourceType: group.qualityProbeSourceType || "panel",
      qualityProbeSourceId: group.qualityProbeSourceId
        ? String(group.qualityProbeSourceId)
        : "",
      qualityProbeCount: String(group.qualityProbeCount || 4),
      qualityDegradeThresholdMs: String(group.qualityDegradeThresholdMs || 100),
      qualityRecoverThresholdMs: String(group.qualityRecoverThresholdMs || 60),
      qualityDegradeFactor: String(group.qualityDegradeFactor || 3),
      qualityRecoverFactor: String(group.qualityRecoverFactor || 1.8),
      qualityDegradeSamples: String(group.qualityDegradeSamples || 3),
      qualityRecoverSamples: String(group.qualityRecoverSamples || 3),
      qualityLossThresholdPercent: String(
        group.qualityLossThresholdPercent || 30,
      ),
      qualityP95ThresholdMs: String(group.qualityP95ThresholdMs || 100),
      qualityJitterThresholdMs: String(group.qualityJitterThresholdMs || 50),
      qualityFixedTargetEnabled: tcpLatencySelectionEnabled
        ? false
        : truthy(group.qualityFixedTargetEnabled || false),
      qualityFixedTargetMs: String(group.qualityFixedTargetMs || 20),
      qualityFixedTargetStrict: !Number.isFinite(
        Number(group.qualityFixedTargetStrict),
      )
        ? truthy(group.qualityFixedTargetStrict ?? true)
        : Number(group.qualityFixedTargetStrict) !== 0,
      qualityFlapGuardEnabled: tcpLatencySelectionEnabled
        ? false
        : !Number.isFinite(Number(group.qualityFlapGuardEnabled))
          ? truthy(group.qualityFlapGuardEnabled ?? true)
          : Number(group.qualityFlapGuardEnabled) !== 0,
      qualityFlapWindowSeconds: String(group.qualityFlapWindowSeconds || 900),
      qualityFlapThreshold: String(group.qualityFlapThreshold || 3),
      qualityFlapSuppressSeconds: String(
        group.qualityFlapSuppressSeconds || 1800,
      ),
      qualityPenaltyEnabled: tcpLatencySelectionEnabled
        ? false
        : !Number.isFinite(Number(group.qualityPenaltyEnabled))
          ? truthy(group.qualityPenaltyEnabled ?? true)
          : Number(group.qualityPenaltyEnabled) !== 0,
      qualityPenaltyResetSeconds: String(
        group.qualityPenaltyResetSeconds || 86400,
      ),
      qualityPenaltyObserveSeconds: String(
        group.qualityPenaltyObserveSeconds ?? 900,
      ),
      smartSelectionEnabled: tcpLatencySelectionEnabled
        ? false
        : !Number.isFinite(Number(group.smartSelectionEnabled))
          ? truthy(group.smartSelectionEnabled ?? true)
          : Number(group.smartSelectionEnabled) !== 0,
      tcpLatencySelectionEnabled,
      tcpLatencySwitchThresholdMs: String(
        group.tcpLatencySwitchThresholdMs ?? 5,
      ),
      tcpPrimaryPreferenceToleranceMs: String(
        group.tcpPrimaryPreferenceToleranceMs ?? 10,
      ),
      degradedFallbackEnabled: tcpLatencySelectionEnabled
        ? false
        : !Number.isFinite(Number(group.degradedFallbackEnabled))
          ? truthy(group.degradedFallbackEnabled ?? true)
          : Number(group.degradedFallbackEnabled) !== 0,
      sameFaultAvoidanceEnabled: tcpLatencySelectionEnabled
        ? false
        : !Number.isFinite(Number(group.sameFaultAvoidanceEnabled))
          ? truthy(group.sameFaultAvoidanceEnabled ?? true)
          : Number(group.sameFaultAvoidanceEnabled) !== 0,
      topologyAvoidanceEnabled: tcpLatencySelectionEnabled
        ? false
        : !Number.isFinite(Number(group.topologyAvoidanceEnabled))
          ? truthy(group.topologyAvoidanceEnabled ?? true)
          : Number(group.topologyAvoidanceEnabled) !== 0,
      minResidencySeconds: String(group.minResidencySeconds ?? 300),
      preheatEnabled: tcpLatencySelectionEnabled
        ? false
        : !Number.isFinite(Number(group.preheatEnabled))
          ? truthy(group.preheatEnabled ?? true)
          : Number(group.preheatEnabled) !== 0,
      preheatBackupCount: String(group.preheatBackupCount ?? 3),
      preheatStrictIsolation: !Number.isFinite(
        Number(group.preheatStrictIsolation),
      )
        ? truthy(group.preheatStrictIsolation ?? true)
        : Number(group.preheatStrictIsolation) !== 0,
      postSwitchVerifyEnabled: !Number.isFinite(
        Number(group.postSwitchVerifyEnabled),
      )
        ? truthy(group.postSwitchVerifyEnabled ?? true)
        : Number(group.postSwitchVerifyEnabled) !== 0,
      postSwitchRejectSuppressSeconds: String(
        group.postSwitchRejectSuppressSeconds ?? 600,
      ),
      dnsVerifyEnabled: !Number.isFinite(Number(group.dnsVerifyEnabled))
        ? truthy(group.dnsVerifyEnabled ?? true)
        : Number(group.dnsVerifyEnabled) !== 0,
      manualControlMode:
        tcpLatencySelectionEnabled && group.manualControlMode === "lock"
          ? "auto"
          : group.manualControlMode || "auto",
      lockedMemberId: tcpLatencySelectionEnabled
        ? ""
        : group.lockedMemberId
          ? String(group.lockedMemberId)
          : "",
      manualLockUntil: group.manualLockUntil
        ? String(group.manualLockUntil)
        : "",
      manualLockDuration: inferLockDuration(group.manualLockUntil),
      memberForwardIds: group.members.map((item) => String(item.forwardId)),
    });
    setFormOpen(true);
  };

  const selectProfile = (profile: PresetProfileKey) => {
    const value = profiles[profile];

    setForm((current) => ({
      ...current,
      profile,
      probeIntervalMs: String(value.interval),
      connectTimeoutMs: String(value.timeout),
      failureThreshold: String(value.failures),
      recoveryThreshold: String(value.recovery),
    }));
  };

  const setTcpLatencySelection = (enabled: boolean) => {
    setForm((current) => ({
      ...current,
      tcpLatencySelectionEnabled: enabled,
      autoFailback: enabled ? false : current.autoFailback,
      qualityEnabled: enabled ? false : current.qualityEnabled,
      qualityFixedTargetEnabled: enabled
        ? false
        : current.qualityFixedTargetEnabled,
      qualityFlapGuardEnabled: enabled
        ? false
        : current.qualityFlapGuardEnabled,
      qualityPenaltyEnabled: enabled ? false : current.qualityPenaltyEnabled,
      smartSelectionEnabled: enabled ? false : current.smartSelectionEnabled,
      degradedFallbackEnabled: enabled
        ? false
        : current.degradedFallbackEnabled,
      sameFaultAvoidanceEnabled: enabled
        ? false
        : current.sameFaultAvoidanceEnabled,
      topologyAvoidanceEnabled: enabled
        ? false
        : current.topologyAvoidanceEnabled,
      preheatEnabled: enabled ? false : current.preheatEnabled,
      manualControlMode:
        enabled && current.manualControlMode === "lock"
          ? "auto"
          : current.manualControlMode,
      lockedMemberId: enabled ? "" : current.lockedMemberId,
      manualLockUntil: enabled ? "" : current.manualLockUntil,
      manualLockDuration: enabled ? "forever" : current.manualLockDuration,
    }));
  };

  const moveMember = (index: number, direction: -1 | 1) => {
    setForm((current) => {
      const target = index + direction;

      if (target < 0 || target >= current.memberForwardIds.length)
        return current;
      const memberForwardIds = [...current.memberForwardIds];

      [memberForwardIds[index], memberForwardIds[target]] = [
        memberForwardIds[target],
        memberForwardIds[index],
      ];

      return { ...current, memberForwardIds };
    });
  };

  const submit = async () => {
    if (!form.name.trim() || !form.domain.trim() || !form.dnsZoneId)
      return toast.error("请选择 Cloudflare Zone 并填写业务域名");
    if (selectionProblem) return toast.error(selectionProblem);
    if (
      form.routingMode === "failover" &&
      (form.qualityEnabled || form.tcpLatencySelectionEnabled) &&
      form.qualityProbeSourceType !== "panel" &&
      !form.qualityProbeSourceId
    ) {
      return toast.error("请选择 TCP 探测源");
    }
    if (
      form.routingMode === "failover" &&
      form.manualControlMode === "lock" &&
      !form.lockedMemberId
    )
      return toast.error("请选择要锁定的入口");
    const tcpMode =
      form.routingMode === "failover" && form.tcpLatencySelectionEnabled;
    const qualityMode =
      form.routingMode === "failover" && form.qualityEnabled && !tcpMode;
    const smartMode = qualityMode && form.smartSelectionEnabled;

    setSubmitting(true);
    const response = await saveCrossEntryGroup({
      ...form,
      dnsZoneId: Number(form.dnsZoneId),
      ttl: Number(form.ttl),
      probeIntervalMs: Number(form.probeIntervalMs),
      connectTimeoutMs: Number(form.connectTimeoutMs),
      failureThreshold: Number(form.failureThreshold),
      recoveryThreshold: Number(form.recoveryThreshold),
      cooldownSeconds: Number(form.cooldownSeconds),
      memberForwardIds: form.memberForwardIds.map(Number),
      autoFailback: tcpMode ? false : form.autoFailback,
      qualityEnabled: qualityMode,
      qualityProbeSourceId:
        form.qualityProbeSourceType === "panel"
          ? undefined
          : Number(form.qualityProbeSourceId),
      qualityProbeCount: Number(form.qualityProbeCount),
      qualityDegradeThresholdMs: Number(form.qualityDegradeThresholdMs),
      qualityRecoverThresholdMs: Number(form.qualityRecoverThresholdMs),
      qualityDegradeFactor: Number(form.qualityDegradeFactor),
      qualityRecoverFactor: Number(form.qualityRecoverFactor),
      qualityDegradeSamples: Number(form.qualityDegradeSamples),
      qualityRecoverSamples: Number(form.qualityRecoverSamples),
      qualityLossThresholdPercent: Number(form.qualityLossThresholdPercent),
      qualityP95ThresholdMs: Number(form.qualityP95ThresholdMs),
      qualityJitterThresholdMs: Number(form.qualityJitterThresholdMs),
      qualityFixedTargetEnabled: qualityMode && form.qualityFixedTargetEnabled,
      qualityFixedTargetMs: Number(form.qualityFixedTargetMs),
      qualityFixedTargetStrict: form.qualityFixedTargetStrict,
      qualityFlapGuardEnabled: qualityMode && form.qualityFlapGuardEnabled,
      qualityFlapWindowSeconds: Number(form.qualityFlapWindowSeconds),
      qualityFlapThreshold: Number(form.qualityFlapThreshold),
      qualityFlapSuppressSeconds: Number(form.qualityFlapSuppressSeconds),
      qualityPenaltyEnabled:
        qualityMode &&
        form.qualityFlapGuardEnabled &&
        form.qualityPenaltyEnabled,
      qualityPenaltyResetSeconds: Number(form.qualityPenaltyResetSeconds),
      qualityPenaltyObserveSeconds: Number(form.qualityPenaltyObserveSeconds),
      smartSelectionEnabled: smartMode,
      tcpLatencySelectionEnabled: tcpMode,
      tcpLatencySwitchThresholdMs: Number(form.tcpLatencySwitchThresholdMs),
      tcpPrimaryPreferenceToleranceMs: Number(
        form.tcpPrimaryPreferenceToleranceMs,
      ),
      degradedFallbackEnabled: smartMode && form.degradedFallbackEnabled,
      sameFaultAvoidanceEnabled: smartMode && form.sameFaultAvoidanceEnabled,
      topologyAvoidanceEnabled: smartMode && form.topologyAvoidanceEnabled,
      minResidencySeconds: Number(form.minResidencySeconds),
      preheatEnabled: smartMode && form.preheatEnabled,
      preheatBackupCount: Number(form.preheatBackupCount),
      preheatStrictIsolation: form.preheatStrictIsolation,
      postSwitchVerifyEnabled: form.postSwitchVerifyEnabled,
      postSwitchRejectSuppressSeconds: Number(
        form.postSwitchRejectSuppressSeconds,
      ),
      dnsVerifyEnabled: form.dnsVerifyEnabled,
      manualControlMode:
        form.routingMode === "failover" && !tcpMode
          ? form.manualControlMode
          : "auto",
      lockedMemberId:
        form.routingMode === "failover" &&
        !tcpMode &&
        form.manualControlMode === "lock"
          ? Number(form.lockedMemberId)
          : undefined,
      manualLockUntil:
        form.routingMode === "failover" &&
        !tcpMode &&
        form.manualControlMode === "lock"
          ? form.manualLockUntil
            ? Number(form.manualLockUntil)
            : resolveLockUntil(form.manualLockDuration)
          : undefined,
    });

    setSubmitting(false);
    if (response.code !== 0)
      return toast.error(response.msg || "保存入口容灾失败");
    toast.success(form.id ? "容灾组已更新" : "容灾组已创建，DNS 已指向主入口");
    setFormOpen(false);
    void loadData();
  };

  const checkNow = async (id: number) => {
    setCheckingId(id);
    const response = await checkCrossEntryGroup(id);

    setCheckingId(undefined);
    if (response.code !== 0) return toast.error(response.msg || "入口检测失败");
    setGroups(response.data?.groups || []);
    setSummary(response.data?.summary || emptySummary);
    toast.success("入口检测已完成");
  };

  const remove = async (group: CrossEntryGroup) => {
    if (!window.confirm(`确认删除“${group.name}”吗？现有转发不会被删除。`))
      return;
    const response = await deleteCrossEntryGroup(group.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("容灾组已删除");
    void loadData();
  };

  const showHistory = async (group: CrossEntryGroup) => {
    const response = await getCrossEntryEvents(group.id);

    if (response.code !== 0)
      return toast.error(response.msg || "加载切换历史失败");
    setEvents(response.data || []);
    setHistoryName(group.name);
    setHistoryGroup(group);
    setHistoryOpen(true);
  };

  if (loading)
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <Spinner label="加载入口容灾" />
      </div>
    );

  return (
    <div className="mx-auto w-full max-w-[1600px] space-y-6 p-4 sm:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">公网入口高可用</p>
          <h1 className="mt-1 text-2xl font-semibold">入口容灾</h1>
        </div>
        <Button
          color="primary"
          startContent={<Plus size={18} />}
          onPress={openCreate}
        >
          新建容灾组
        </Button>
      </header>

      <section
        aria-label="入口容灾概况"
        className="grid grid-cols-2 border-y border-divider sm:grid-cols-4"
      >
        {[
          [
            "运行组",
            summary.enabled,
            <ShieldCheck key="shield" className="h-5 w-5 text-primary" />,
          ],
          [
            "健康",
            summary.healthy,
            <CheckCircle2 key="healthy" className="h-5 w-5 text-success" />,
          ],
          [
            "需处理",
            summary.degraded,
            <TriangleAlert key="warning" className="h-5 w-5 text-warning" />,
          ],
          [
            "累计切换",
            summary.switches,
            <Activity key="switches" className="h-5 w-5 text-secondary" />,
          ],
        ].map(([label, value, icon], index) => (
          <div
            key={String(label)}
            className={`flex min-h-24 items-center justify-between px-4 py-4 sm:px-6 ${index % 2 ? "" : "border-r border-divider"} ${index === 1 ? "sm:border-r" : ""}`}
          >
            <div>
              <p className="text-xs text-default-500">{label}</p>
              <p className="mt-1 text-2xl font-semibold">{value}</p>
            </div>
            {icon}
          </div>
        ))}
      </section>

      {recentSwitches.length > 0 && (
        <section
          aria-label="最近入口切换"
          className="border-y border-divider py-4"
        >
          <div className="flex flex-wrap items-end justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold">最近切换</h2>
              <p className="mt-1 text-xs text-default-500">
                累计切换 {summary.switches}{" "}
                次，下面显示每个容灾组最近一次的具体线路和触发原因。
              </p>
            </div>
            <span className="text-xs text-default-500">
              完整记录可打开每张卡片右上角的历史按钮
            </span>
          </div>
          <div className="mt-3 grid gap-2 xl:grid-cols-2">
            {recentSwitches.map(({ group, event }) => (
              <div
                key={group.id}
                className="grid gap-2 border-l-2 border-secondary px-3 py-2 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center"
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium">{group.name}</span>
                    <span className="text-xs text-default-500">
                      {timeText(event.createdTime)}
                    </span>
                  </div>
                  <p className="mt-1 flex items-center gap-1 text-sm">
                    <span className="truncate">{eventRouteText(event)}</span>
                    <ArrowRight
                      className="flex-none text-default-400"
                      size={13}
                    />
                    <span className="truncate text-default-500">
                      {event.reason}
                    </span>
                  </p>
                  <p className="mt-1 truncate text-xs text-default-500">
                    {eventEndpointText(event) ||
                      event.detail ||
                      "无线路地址记录"}
                  </p>
                </div>
                <Button
                  size="sm"
                  startContent={<History size={15} />}
                  variant="flat"
                  onPress={() => showHistory(group)}
                >
                  查看历史
                </Button>
              </div>
            ))}
          </div>
        </section>
      )}

      {groups.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-center text-default-500">
          <ShieldCheck className="h-9 w-9" />
          <p>暂无跨入口容灾组</p>
        </div>
      ) : (
        <section className="grid gap-4 xl:grid-cols-2">
          {groups.map((group) => {
            const meta = truthy(group.enabled)
              ? stateMeta(group.state)
              : { label: "已停用", color: "default" as const };
            const active = group.members.find(
              (item) => item.id === group.activeMemberId,
            );
            const activeActive = group.routingMode === "active_active";
            const tcpLatencySelectionEnabled =
              !activeActive &&
              truthy(group.tcpLatencySelectionEnabled ?? false);
            const qualityEnabled =
              !tcpLatencySelectionEnabled &&
              truthy(group.qualityEnabled || false);
            const fixedTargetEnabled =
              qualityEnabled &&
              truthy(group.qualityFixedTargetEnabled || false);
            const flapGuardEnabled =
              qualityEnabled && truthy(group.qualityFlapGuardEnabled ?? true);
            const penaltyEnabled =
              flapGuardEnabled && truthy(group.qualityPenaltyEnabled ?? true);
            const smartSelectionEnabled =
              qualityEnabled && truthy(group.smartSelectionEnabled ?? true);
            const detailedProbeEnabled =
              qualityEnabled || tcpLatencySelectionEnabled;
            const probeMeta = qualityProbeMeta(group.qualityProbeStatus);
            const manualMeta = manualControlMeta(group.manualControlMode);
            const strategy = explainGroupStrategy(group);
            const statusCounts = memberStatusCounts(group);
            const lockActive = group.manualControlMode === "lock";

            return (
              <Card
                key={group.id}
                className="border border-divider bg-content1"
                radius="sm"
                shadow="none"
              >
                <CardBody className="gap-4 p-4 sm:p-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <h2 className="truncate text-base font-semibold">
                          {group.name}
                        </h2>
                        <Chip color={meta.color} size="sm" variant="flat">
                          {meta.label}
                        </Chip>
                        <Chip
                          color={activeActive ? "secondary" : "default"}
                          size="sm"
                          variant="flat"
                        >
                          {activeActive ? "多入口同时运行" : "主备容灾"}
                        </Chip>
                        {detailedProbeEnabled && (
                          <Chip
                            color={probeMeta.color}
                            size="sm"
                            variant="flat"
                          >
                            {probeMeta.label}
                          </Chip>
                        )}
                        {smartSelectionEnabled && (
                          <Chip color="success" size="sm" variant="flat">
                            智能选择
                          </Chip>
                        )}
                        {tcpLatencySelectionEnabled && (
                          <Chip color="secondary" size="sm" variant="flat">
                            TCP 延迟优选
                          </Chip>
                        )}
                        {fixedTargetEnabled && (
                          <Chip color="secondary" size="sm" variant="flat">
                            目标 ≤ {group.qualityFixedTargetMs || 20} ms ·{" "}
                            {fixedTargetModeLabel(
                              group.qualityFixedTargetStrict,
                            )}
                          </Chip>
                        )}
                        {flapGuardEnabled && (
                          <Chip color="warning" size="sm" variant="flat">
                            {penaltyEnabled ? "阶梯惩罚" : "抖动保护"}
                          </Chip>
                        )}
                        {group.manualControlMode &&
                          group.manualControlMode !== "auto" && (
                            <Chip
                              color={manualMeta.color}
                              size="sm"
                              variant="flat"
                            >
                              {manualMeta.label}
                              {lockActive && group.manualLockUntil
                                ? ` · ${lockDurationLabel(group.manualLockUntil)}`
                                : ""}
                            </Chip>
                          )}
                      </div>
                      <p className="mt-1 truncate text-sm text-default-500">
                        {group.domain}:{group.members[0]?.entryPort || "-"}
                      </p>
                    </div>
                    <div className="flex items-center gap-1">
                      <Button
                        isIconOnly
                        aria-label="立即检测"
                        isLoading={checkingId === group.id}
                        size="sm"
                        title="立即检测"
                        variant="light"
                        onPress={() => checkNow(group.id)}
                      >
                        <RefreshCw size={17} />
                      </Button>
                      <Button
                        isIconOnly
                        aria-label="切换历史"
                        size="sm"
                        title="切换历史"
                        variant="light"
                        onPress={() => showHistory(group)}
                      >
                        <History size={17} />
                      </Button>
                      <Button
                        isIconOnly
                        aria-label="编辑"
                        size="sm"
                        title="编辑"
                        variant="light"
                        onPress={() => openEdit(group)}
                      >
                        <Pencil size={17} />
                      </Button>
                      <Button
                        isIconOnly
                        aria-label="删除"
                        color="danger"
                        size="sm"
                        title="删除"
                        variant="light"
                        onPress={() => remove(group)}
                      >
                        <Trash2 size={17} />
                      </Button>
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Chip size="sm" variant="flat">
                      健康 {statusCounts.healthy}
                    </Chip>
                    <Chip color="secondary" size="sm" variant="flat">
                      学习 {statusCounts.warming}
                    </Chip>
                    <Chip color="secondary" size="sm" variant="flat">
                      观察 {statusCounts.observing}
                    </Chip>
                    <Chip color="warning" size="sm" variant="flat">
                      冷却 {statusCounts.cooling}
                    </Chip>
                    <Chip color="warning" size="sm" variant="flat">
                      黑名单 {statusCounts.blacklisted}
                    </Chip>
                    <Chip color="default" size="sm" variant="flat">
                      禁用 {statusCounts.disabled}
                    </Chip>
                  </div>

                  <div className="grid grid-cols-2 gap-x-4 gap-y-3 border-y border-divider py-3 text-sm sm:grid-cols-4">
                    <div>
                      <p className="text-xs text-default-500">
                        {activeActive ? "DNS 锚点" : "当前入口"}
                      </p>
                      <p className="mt-1 truncate font-medium">
                        {active?.nodeName || "未确定"}
                      </p>
                    </div>
                    <div>
                      <p className="text-xs text-default-500">检测周期</p>
                      <p className="mt-1 font-medium">
                        {group.probeIntervalMs / 1000} 秒
                      </p>
                    </div>
                    <div>
                      <p className="text-xs text-default-500">失败阈值</p>
                      <p className="mt-1 font-medium">
                        连续 {group.failureThreshold} 次
                      </p>
                    </div>
                    <div>
                      <p className="text-xs text-default-500">
                        {activeActive ? "健康入口" : "上次切换"}
                      </p>
                      <p className="mt-1 truncate font-medium">
                        {activeActive
                          ? `${group.members.filter((item) => item.status === "healthy").length}/${group.members.length} 条`
                          : group.lastSwitchEvent
                            ? timeText(group.lastSwitchEvent.createdTime)
                            : group.lastSwitchAt
                              ? timeText(group.lastSwitchAt)
                              : "未切换"}
                      </p>
                      <p className="mt-1 truncate text-xs text-default-500">
                        {activeActive
                          ? "DNS 仅返回健康入口"
                          : eventRouteText(group.lastSwitchEvent)}
                      </p>
                    </div>
                  </div>

                  <StrategySummaryPanel compact summary={strategy} />

                  <div className="space-y-2">
                    {group.members.map((member, index) => {
                      const isActive = member.id === group.activeMemberId;
                      const qMeta = qualityMeta(member.qualityState);
                      const now = Date.now();
                      const qualitySuppressed = Boolean(
                        member.qualitySuppressedUntil &&
                        member.qualitySuppressedUntil > now,
                      );
                      const blacklisted = Boolean(
                        member.switchRejectedUntil &&
                        member.switchRejectedUntil > now,
                      );
                      const suppressed = qualitySuppressed || blacklisted;
                      const observing =
                        !suppressed &&
                        Boolean(
                          member.qualityRecoveryObserveUntil &&
                          member.qualityRecoveryObserveUntil > now,
                        );
                      const preheated =
                        qualityEnabled &&
                        truthy(member.qualityPreheated || false);
                      const penaltyLevel = member.qualityPenaltyLevel || 0;

                      return (
                        <div
                          key={member.id}
                          className={`grid min-h-16 grid-cols-1 gap-3 border-l-2 px-3 py-2 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center ${isActive ? "border-primary bg-primary-50/50 dark:bg-primary-500/5" : "border-divider"}`}
                        >
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="truncate text-sm font-medium">
                                {member.nodeName}
                              </span>
                              <Chip
                                color={
                                  member.status === "healthy"
                                    ? "success"
                                    : member.status === "unhealthy"
                                      ? "danger"
                                      : "default"
                                }
                                size="sm"
                                variant="flat"
                              >
                                {member.status === "healthy"
                                  ? "可用"
                                  : member.status === "unhealthy"
                                    ? "不可用"
                                    : "检测中"}
                              </Chip>
                              {qualityEnabled && (
                                <Chip
                                  color={qMeta.color}
                                  size="sm"
                                  variant="flat"
                                >
                                  {qMeta.label}
                                </Chip>
                              )}
                              {preheated && (
                                <Chip color="success" size="sm" variant="flat">
                                  已预热
                                </Chip>
                              )}
                              {penaltyLevel > 0 && (
                                <Chip color="warning" size="sm" variant="flat">
                                  惩罚 L{penaltyLevel}
                                </Chip>
                              )}
                              {qualitySuppressed && (
                                <Chip color="warning" size="sm" variant="flat">
                                  冷却池
                                </Chip>
                              )}
                              {blacklisted && (
                                <Chip color="danger" size="sm" variant="flat">
                                  黑名单
                                </Chip>
                              )}
                              {observing && (
                                <Chip
                                  color="secondary"
                                  size="sm"
                                  variant="flat"
                                >
                                  恢复观察
                                </Chip>
                              )}
                              {isActive && (
                                <Chip color="primary" size="sm" variant="flat">
                                  {activeActive ? "DNS 锚点" : "当前承载"}
                                </Chip>
                              )}
                            </div>
                            <p className="mt-1 truncate text-xs text-default-500">
                              {activeActive
                                ? `入口 ${index + 1}`
                                : index === 0
                                  ? "主入口"
                                  : `备用 ${index}`}{" "}
                              · {member.entryAddress}:{member.entryPort} ·{" "}
                              {member.forwardName}
                            </p>
                            {qualitySuppressed && (
                              <p className="mt-1 truncate text-xs text-warning">
                                {member.qualitySuppressedReason ||
                                  "质量惩罚保护"}
                                至 {timeText(member.qualitySuppressedUntil)}
                              </p>
                            )}
                            {blacklisted && (
                              <p className="mt-1 truncate text-xs text-danger">
                                {member.switchRejectedReason ||
                                  "切换验证黑名单"}
                                至 {timeText(member.switchRejectedUntil)}
                              </p>
                            )}
                            {observing && (
                              <p className="mt-1 truncate text-xs text-secondary">
                                恢复观察至{" "}
                                {timeText(member.qualityRecoveryObserveUntil)}
                              </p>
                            )}
                          </div>
                          <div className="text-left text-xs sm:text-right">
                            <p className="font-medium">
                              {detailedProbeEnabled
                                ? metricText(member.qualityLatencyMs, " ms")
                                : member.latencyMs
                                  ? `${member.latencyMs} ms`
                                  : "-"}
                            </p>
                            {qualityEnabled ? (
                              <p className="mt-1 text-default-500">
                                均值{" "}
                                {metricText(member.qualityLatencyMs, " ms")} ·
                                P95 {metricText(member.qualityP95Ms, " ms")} ·
                                抖动 {metricText(member.qualityJitterMs, " ms")}{" "}
                                · 丢包{" "}
                                {metricText(member.qualityLossPercent, "%")} ·
                                基线{" "}
                                {metricText(member.qualityBaselineMs, " ms")}
                              </p>
                            ) : tcpLatencySelectionEnabled ? (
                              <p className="mt-1 text-default-500">
                                TCP 多次探测均值 · 主线容忍{" "}
                                {group.tcpPrimaryPreferenceToleranceMs ?? 10} ms
                              </p>
                            ) : (
                              <p className="mt-1 text-default-500">
                                失败 {member.failCount}/{group.failureThreshold}
                              </p>
                            )}
                            <p className="mt-1 text-default-400">
                              {faultSummaryText(member)}
                            </p>
                            {member.lastFaultReason && (
                              <p className="mt-1 truncate text-default-400">
                                {member.lastFaultReason}
                              </p>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                  <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-default-500">
                    <span>最近检测：{timeText(group.lastCheckedAt)}</span>
                    <span>
                      {activeActive
                        ? "健康入口同时写入 DNS；仅影响新连接"
                        : tcpLatencySelectionEnabled
                          ? `主线优先容忍 ${group.tcpPrimaryPreferenceToleranceMs ?? 10} ms`
                          : truthy(group.autoFailback)
                            ? "主入口恢复后自动回切"
                            : "切换后保持当前入口"}
                    </span>
                  </div>
                  {detailedProbeEnabled && group.qualityProbeError && (
                    <p className="rounded-md bg-warning-50 px-3 py-2 text-xs text-warning-700 dark:bg-warning-500/10 dark:text-warning-300">
                      {group.qualityProbeError}
                    </p>
                  )}
                  {group.lastError && (
                    <p className="rounded-md bg-danger-50 px-3 py-2 text-xs text-danger dark:bg-danger-500/10">
                      {group.lastError}
                    </p>
                  )}
                </CardBody>
              </Card>
            );
          })}
        </section>
      )}

      <Modal
        isOpen={formOpen}
        scrollBehavior="inside"
        size="5xl"
        onOpenChange={setFormOpen}
      >
        <ModalContent>
          <ModalHeader>
            {form.id ? "编辑入口容灾组" : "新建入口容灾组"}
          </ModalHeader>
          <ModalBody className="gap-4">
            <section
              className={`${sectionCardClass} grid gap-3 sm:grid-cols-2`}
            >
              <Input
                description="只用于面板识别，不影响 DNS 或转发。"
                label="容灾组名称"
                value={form.name}
                onValueChange={(name) => setForm({ ...form, name })}
              />
              <Select
                description="选择要由面板自动维护记录的 Cloudflare Zone。"
                label="Cloudflare Zone"
                placeholder="选择已登记的域名区域"
                selectedKeys={form.dnsZoneId ? [form.dnsZoneId] : []}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    dnsZoneId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {zoneOptions.map((zone) => (
                  <SelectItem
                    key={String(zone.id)}
                    textValue={`${zone.accountName} ${zone.zoneName}`}
                  >
                    {zone.zoneName} · {zone.accountName}
                  </SelectItem>
                ))}
              </Select>
              <Input
                description={
                  selectedZone
                    ? `保存后自动创建 ${selectedZone.zoneName} 下的 DNS 记录`
                    : "凭据和 Zone 在“资源中心 - 域名管理”中统一维护"
                }
                label="业务域名或主机记录"
                placeholder={
                  selectedZone
                    ? `例如 glglg 或 glglg.${selectedZone.zoneName}`
                    : "先选择 Cloudflare Zone"
                }
                value={form.domain}
                onValueChange={(domain) => setForm({ ...form, domain })}
              />
              <Select
                description="A 写 IPv4 入口，AAAA 写 IPv6 入口。"
                label="DNS 记录类型"
                selectedKeys={[form.recordType]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    recordType: String(Array.from(keys)[0]) as "A" | "AAAA",
                  })
                }
              >
                <SelectItem key="A">A（IPv4）</SelectItem>
                <SelectItem key="AAAA">AAAA（IPv6）</SelectItem>
              </Select>
              <Input
                description="DNS 缓存时间；越低切换越快，但客户端和运营商仍可能缓存。"
                label="DNS TTL（秒）"
                max={86400}
                min={60}
                type="number"
                value={form.ttl}
                onValueChange={(ttl) => setForm({ ...form, ttl })}
              />
              <Select
                description="主备容灾只返回一个入口；多入口模式会返回所有健康入口。"
                label="入口调度模式"
                selectedKeys={[form.routingMode]}
                onSelectionChange={(keys) => {
                  const routingMode = String(
                    Array.from(keys)[0] || "failover",
                  ) as "failover" | "active_active";

                  setForm({
                    ...form,
                    routingMode,
                    tcpLatencySelectionEnabled:
                      routingMode === "active_active"
                        ? false
                        : form.tcpLatencySelectionEnabled,
                    qualityEnabled:
                      routingMode === "active_active"
                        ? false
                        : form.qualityEnabled,
                    qualityFixedTargetEnabled:
                      routingMode === "active_active"
                        ? false
                        : form.qualityFixedTargetEnabled,
                    smartSelectionEnabled:
                      routingMode === "active_active"
                        ? false
                        : form.smartSelectionEnabled,
                    manualControlMode:
                      routingMode === "active_active"
                        ? "auto"
                        : form.manualControlMode,
                    lockedMemberId:
                      routingMode === "active_active"
                        ? ""
                        : form.lockedMemberId,
                    manualLockUntil:
                      routingMode === "active_active"
                        ? ""
                        : form.manualLockUntil,
                    manualLockDuration:
                      routingMode === "active_active"
                        ? "forever"
                        : form.manualLockDuration,
                  });
                }}
              >
                <SelectItem key="failover">主备容灾（默认）</SelectItem>
                <SelectItem key="active_active">
                  多入口同时运行（DNS）
                </SelectItem>
              </Select>
            </section>

            <div className="rounded-2xl border border-primary/20 bg-primary-50/60 px-4 py-3 text-xs leading-5 text-primary-700 dark:bg-primary-500/10 dark:text-primary-200">
              {form.routingMode === "active_active"
                ? "所有健康入口会同时写入同一业务域名的 DNS 记录。客户端 DNS 解析后选择其中一个入口，失效入口会在检测确认后从记录集合摘除。它只影响新的解析和新连接，普通 DNS 不提供严格按权重的连接级均衡。"
                : "域名始终只指向一个当前入口。主入口连续失败后切到备用入口，适合希望地址稳定、只在故障时切换的业务。"}
            </div>

            <section className={sectionCardClass}>
              <StrategySummaryPanel summary={formStrategy} />
            </section>

            {zoneOptions.length === 0 && (
              <div className="flex flex-col gap-3 rounded-2xl border border-warning-200 bg-warning-50 px-4 py-3 text-sm text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200 sm:flex-row sm:items-center sm:justify-between">
                <span>
                  尚未登记 Cloudflare 凭据。先同步 Zone，之后这里直接选择即可。
                </span>
                <Button
                  color="warning"
                  size="sm"
                  variant="flat"
                  onPress={() => {
                    setFormOpen(false);
                    navigate("/dns-settings");
                  }}
                >
                  前往域名管理
                </Button>
              </div>
            )}

            <section className={sectionCardClass}>
              <div className="mb-3 flex items-center justify-between gap-3">
                <div>
                  <h3 className="text-sm font-semibold">
                    {form.routingMode === "active_active"
                      ? "入口成员"
                      : "入口顺序"}
                  </h3>
                  <p className="mt-1 text-xs text-default-500">
                    {form.routingMode === "active_active"
                      ? "全部健康成员同时参与 DNS 返回；第一条用于保留兼容的 DNS 锚点。"
                      : "第一条为主入口，其余按顺序作为备用入口。"}{" "}
                    公网端口必须相同。
                  </p>
                </div>
                <Chip size="sm" variant="flat">
                  端口 {selectedPort || "-"}
                </Chip>
              </div>
              <div className="space-y-2">
                {form.memberForwardIds.map((id, index) => (
                  <div
                    key={`${index}-${id}`}
                    className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-2"
                  >
                    <span className="w-14 text-xs font-medium text-default-500">
                      {form.routingMode === "active_active"
                        ? `入口 ${index + 1}`
                        : index === 0
                          ? "主入口"
                          : `备用 ${index}`}
                    </span>
                    <Select
                      aria-label={
                        index === 0 ? "主入口转发" : `备用入口 ${index}`
                      }
                      placeholder="选择一个现有转发"
                      selectedKeys={id ? [id] : []}
                      onSelectionChange={(keys) => {
                        const values = [...form.memberForwardIds];

                        values[index] = String(Array.from(keys)[0] || "");
                        setForm({ ...form, memberForwardIds: values });
                      }}
                    >
                      {forwardGroups.map((group, groupIndex) => (
                        <SelectSection
                          key={`port-${group.port}`}
                          showDivider={groupIndex < forwardGroups.length - 1}
                          title={`端口 ${group.port} (${group.options.length})`}
                        >
                          {group.options.map((option) => (
                            <SelectItem
                              key={String(option.id)}
                              textValue={`端口 ${option.inPort} ${option.nodeName} ${option.entryHost} ${option.name}`}
                            >
                              {option.nodeName} · {option.entryHost}:
                              {option.inPort} · {option.name}
                            </SelectItem>
                          ))}
                        </SelectSection>
                      ))}
                    </Select>
                    <div className="flex h-10 items-center gap-1">
                      <Button
                        isIconOnly
                        aria-label="上移入口"
                        isDisabled={index === 0}
                        size="sm"
                        title="上移"
                        variant="light"
                        onPress={() => moveMember(index, -1)}
                      >
                        <ArrowUp size={16} />
                      </Button>
                      <Button
                        isIconOnly
                        aria-label="下移入口"
                        isDisabled={index === form.memberForwardIds.length - 1}
                        size="sm"
                        title="下移"
                        variant="light"
                        onPress={() => moveMember(index, 1)}
                      >
                        <ArrowDown size={16} />
                      </Button>
                      <Button
                        isIconOnly
                        aria-label="移除入口"
                        isDisabled={form.memberForwardIds.length <= 2}
                        size="sm"
                        title="移除"
                        variant="light"
                        onPress={() =>
                          setForm({
                            ...form,
                            memberForwardIds: form.memberForwardIds.filter(
                              (_, current) => current !== index,
                            ),
                          })
                        }
                      >
                        <X size={17} />
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
              <Button
                className="mt-3"
                isDisabled={form.memberForwardIds.length >= 10}
                size="sm"
                startContent={<Plus size={16} />}
                variant="flat"
                onPress={() =>
                  setForm({
                    ...form,
                    memberForwardIds: [...form.memberForwardIds, ""],
                  })
                }
              >
                {form.routingMode === "active_active"
                  ? "添加入口成员"
                  : "添加备用入口"}
              </Button>
              {selectionProblem && (
                <p className="mt-2 text-xs text-warning">{selectionProblem}</p>
              )}
            </section>

            <section className={sectionCardClass}>
              <h3 className="text-sm font-semibold">失效检测</h3>
              <div className="mt-3 grid grid-cols-3 gap-2">
                {(Object.keys(profiles) as PresetProfileKey[]).map((key) => (
                  <button
                    key={key}
                    className={`min-h-20 rounded-md border p-3 text-left transition-colors ${form.profile === key ? "border-primary bg-primary-50 dark:bg-primary-500/10" : "border-divider hover:bg-default-100"}`}
                    type="button"
                    onClick={() => selectProfile(key)}
                  >
                    <span className="text-sm font-medium">
                      {profiles[key].label}
                    </span>
                    <span className="mt-1 block text-xs text-default-500">
                      {profiles[key].note}
                    </span>
                  </button>
                ))}
              </div>
              <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
                <Input
                  description="两轮检测之间的间隔。越短切换越快，探测压力也越高。"
                  label="探测间隔（毫秒）"
                  type="number"
                  value={form.probeIntervalMs}
                  onValueChange={(probeIntervalMs) =>
                    setForm({ ...form, profile: "custom", probeIntervalMs })
                  }
                />
                <Input
                  description="单次 TCP 连接等待时间。超过这个时间算本次探测失败。"
                  label="连接超时（毫秒）"
                  type="number"
                  value={form.connectTimeoutMs}
                  onValueChange={(connectTimeoutMs) =>
                    setForm({ ...form, profile: "custom", connectTimeoutMs })
                  }
                />
                <Input
                  description="连续失败达到这个次数后，才认为当前入口真的失效。"
                  label="连续失败次数"
                  type="number"
                  value={form.failureThreshold}
                  onValueChange={(failureThreshold) =>
                    setForm({ ...form, profile: "custom", failureThreshold })
                  }
                />
                <Input
                  description="备用或主入口连续成功达到这个次数后，才认为恢复稳定。"
                  label="恢复确认次数"
                  type="number"
                  value={form.recoveryThreshold}
                  onValueChange={(recoveryThreshold) =>
                    setForm({ ...form, profile: "custom", recoveryThreshold })
                  }
                />
                <Input
                  description="刚切换后至少等待这段时间，避免线路刚恢复就马上来回跳。"
                  label="回切冷却（秒）"
                  type="number"
                  value={form.cooldownSeconds}
                  onValueChange={(cooldownSeconds) =>
                    setForm({ ...form, profile: "custom", cooldownSeconds })
                  }
                />
              </div>
              <div className="mt-4 grid gap-4 border-t border-divider pt-4 lg:grid-cols-[260px_minmax(0,1fr)] lg:items-start">
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-1">
                  {form.routingMode === "failover" ? (
                    <Switch
                      isDisabled={form.tcpLatencySelectionEnabled}
                      isSelected={form.autoFailback}
                      onValueChange={(autoFailback) =>
                        setForm({ ...form, autoFailback })
                      }
                    >
                      主入口恢复后自动回切
                    </Switch>
                  ) : (
                    <span className="text-xs text-default-500">
                      多入口模式不回切，健康成员会自动恢复到 DNS 记录。
                    </span>
                  )}
                  <Switch
                    isSelected={form.enabled}
                    onValueChange={(enabled) => setForm({ ...form, enabled })}
                  >
                    启用自动检测
                  </Switch>
                </div>
                {form.routingMode === "failover" && (
                  <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                    <Select
                      disabledKeys={[
                        ...(!form.id ? ["lock"] : []),
                        ...(form.tcpLatencySelectionEnabled ? ["lock"] : []),
                      ]}
                      label="自动控制"
                      selectedKeys={[form.manualControlMode]}
                      onSelectionChange={(keys) => {
                        const manualControlMode = String(
                          Array.from(keys)[0] || "auto",
                        ) as "auto" | "pause" | "lock";

                        setForm({
                          ...form,
                          manualControlMode,
                          lockedMemberId:
                            manualControlMode === "lock"
                              ? form.lockedMemberId
                              : "",
                          manualLockUntil:
                            manualControlMode === "lock"
                              ? form.manualLockUntil
                              : "",
                          manualLockDuration:
                            manualControlMode === "lock"
                              ? form.manualLockDuration
                              : "forever",
                        });
                      }}
                    >
                      <SelectItem key="auto">自动选择</SelectItem>
                      <SelectItem key="pause">暂停自动切换</SelectItem>
                      <SelectItem key="lock">锁定指定入口</SelectItem>
                    </Select>
                    {form.manualControlMode === "lock" && (
                      <Select
                        label="锁定入口"
                        placeholder="选择要固定承载的入口"
                        selectedKeys={
                          form.lockedMemberId ? [form.lockedMemberId] : []
                        }
                        onSelectionChange={(keys) =>
                          setForm({
                            ...form,
                            lockedMemberId: String(Array.from(keys)[0] || ""),
                          })
                        }
                      >
                        {lockableMembers.map((member, index) => (
                          <SelectItem
                            key={String(member.id)}
                            textValue={`${member.nodeName} ${member.entryAddress}:${member.entryPort}`}
                          >
                            {index === 0 ? "主入口" : `备用 ${index}`} ·{" "}
                            {member.nodeName} · {member.entryAddress}:
                            {member.entryPort}
                          </SelectItem>
                        ))}
                      </Select>
                    )}
                    {form.manualControlMode === "lock" && (
                      <Select
                        label="锁定时长"
                        selectedKeys={[form.manualLockDuration]}
                        onSelectionChange={(keys) => {
                          const duration = String(
                            Array.from(keys)[0] || "forever",
                          ) as FailoverForm["manualLockDuration"];

                          setForm({
                            ...form,
                            manualLockDuration: duration,
                            manualLockUntil:
                              duration === "forever"
                                ? ""
                                : String(resolveLockUntil(duration) || ""),
                          });
                        }}
                      >
                        {lockDurationOptions.map((item) => (
                          <SelectItem key={item.key}>{item.label}</SelectItem>
                        ))}
                      </Select>
                    )}
                  </div>
                )}
              </div>
              <p className="mt-3 text-xs leading-5 text-default-500">
                自动回切只在主入口恢复稳定后生效；暂停自动切换会保留检测但不改
                DNS；锁定入口用于临时固定线路，支持永久、30 分钟、2
                小时，到期后自动恢复。
              </p>
              {form.routingMode === "failover" && (
                <div className="mt-3 border-t border-divider pt-4">
                  <Switch
                    isSelected={form.tcpLatencySelectionEnabled}
                    onValueChange={setTcpLatencySelection}
                  >
                    主线路优先的 TCP 延迟优选
                  </Switch>
                  <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    <Input
                      description="例如 20ms：主线最多慢 20ms 仍优先"
                      isDisabled={!form.tcpLatencySelectionEnabled}
                      label="主线优先容忍（ms）"
                      max={30000}
                      min={0}
                      type="number"
                      value={form.tcpPrimaryPreferenceToleranceMs}
                      onValueChange={(tcpPrimaryPreferenceToleranceMs) =>
                        setForm({ ...form, tcpPrimaryPreferenceToleranceMs })
                      }
                    />
                    <Input
                      description="备用之间至少快多少才切换"
                      isDisabled={!form.tcpLatencySelectionEnabled}
                      label="备用切换收益（ms）"
                      max={30000}
                      min={0}
                      type="number"
                      value={form.tcpLatencySwitchThresholdMs}
                      onValueChange={(tcpLatencySwitchThresholdMs) =>
                        setForm({ ...form, tcpLatencySwitchThresholdMs })
                      }
                    />
                    <Input
                      description="切换后至少保持当前线路的时间"
                      isDisabled={
                        !form.tcpLatencySelectionEnabled &&
                        !form.smartSelectionEnabled
                      }
                      label="最短驻留（秒）"
                      max={86400}
                      min={0}
                      type="number"
                      value={form.minResidencySeconds}
                      onValueChange={(minResidencySeconds) =>
                        setForm({ ...form, minResidencySeconds })
                      }
                    />
                  </div>
                  <p className="mt-3 text-xs leading-5 text-default-500">
                    主入口与最低延迟线路的差值在容忍范围内时，始终使用主入口；超过容忍范围后，才选择
                    TCP 延迟最低的健康线路。开启后会自动关闭存在冲突的策略。
                  </p>
                  {form.tcpLatencySelectionEnabled && (
                    <div className="mt-4 grid gap-4 border-t border-divider pt-4">
                      <div className="grid gap-4 lg:grid-cols-[260px_minmax(0,1fr)] lg:items-start">
                        <div className="space-y-2">
                          <p className="text-sm font-medium">TCP 探测</p>
                          <p className="text-xs leading-5 text-default-500">
                            从指定网络视角测入口延迟，本地 Connector
                            更接近你的宽带体验。
                          </p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                          <Select
                            label="TCP 探测源"
                            selectedKeys={[form.qualityProbeSourceType]}
                            onSelectionChange={(keys) =>
                              setForm({
                                ...form,
                                qualityProbeSourceType: String(
                                  Array.from(keys)[0] || "panel",
                                ) as "panel" | "node" | "connector",
                                qualityProbeSourceId: "",
                              })
                            }
                          >
                            <SelectItem key="panel">面板服务器</SelectItem>
                            <SelectItem key="node">指定 Agent 节点</SelectItem>
                            <SelectItem key="connector">
                              指定 Connector
                            </SelectItem>
                          </Select>
                          {form.qualityProbeSourceType === "node" && (
                            <Select
                              label="Agent 节点"
                              placeholder={`Agent ≥ ${probeSources.minimumRemoteVersion}`}
                              selectedKeys={
                                form.qualityProbeSourceId
                                  ? [form.qualityProbeSourceId]
                                  : []
                              }
                              onSelectionChange={(keys) =>
                                setForm({
                                  ...form,
                                  qualityProbeSourceId: String(
                                    Array.from(keys)[0] || "",
                                  ),
                                })
                              }
                            >
                              {probeSources.nodes.map((source) => (
                                <SelectItem
                                  key={String(source.id)}
                                  textValue={`${source.name} ${source.address || ""}`}
                                >
                                  {source.name} · {source.address || "无地址"} ·{" "}
                                  {source.version || "-"}
                                </SelectItem>
                              ))}
                            </Select>
                          )}
                          {form.qualityProbeSourceType === "connector" && (
                            <Select
                              label="Connector"
                              placeholder={`Connector ≥ ${probeSources.minimumRemoteVersion}`}
                              selectedKeys={
                                form.qualityProbeSourceId
                                  ? [form.qualityProbeSourceId]
                                  : []
                              }
                              onSelectionChange={(keys) =>
                                setForm({
                                  ...form,
                                  qualityProbeSourceId: String(
                                    Array.from(keys)[0] || "",
                                  ),
                                })
                              }
                            >
                              {probeSources.connectors.map((source) => (
                                <SelectItem
                                  key={String(source.id)}
                                  textValue={`${source.name} ${source.platform || ""}`}
                                >
                                  {source.name} · {source.platform || "-"} ·{" "}
                                  {source.version || "-"}
                                </SelectItem>
                              ))}
                            </Select>
                          )}
                          <Input
                            label="每轮 TCP 次数"
                            max={10}
                            min={2}
                            type="number"
                            value={form.qualityProbeCount}
                            onValueChange={(qualityProbeCount) =>
                              setForm({ ...form, qualityProbeCount })
                            }
                          />
                        </div>
                      </div>
                      <div className="grid gap-4 border-t border-divider pt-4 lg:grid-cols-[260px_minmax(0,1fr)] lg:items-start">
                        <div className="space-y-2">
                          <p className="text-sm font-medium">切换验证与 DNS</p>
                          <p className="text-xs leading-5 text-default-500">
                            验证切换目标是否真的可用，失败后短时间避开该入口。
                          </p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                          <div className="flex min-h-14 items-center">
                            <Switch
                              isSelected={form.postSwitchVerifyEnabled}
                              onValueChange={(postSwitchVerifyEnabled) =>
                                setForm({ ...form, postSwitchVerifyEnabled })
                              }
                            >
                              切换后验证入口
                            </Switch>
                          </div>
                          <div className="flex min-h-14 items-center">
                            <Switch
                              isSelected={form.dnsVerifyEnabled}
                              onValueChange={(dnsVerifyEnabled) =>
                                setForm({ ...form, dnsVerifyEnabled })
                              }
                            >
                              DNS 生效确认
                            </Switch>
                          </div>
                          <Input
                            label="验证失败黑名单（秒）"
                            min={60}
                            type="number"
                            value={form.postSwitchRejectSuppressSeconds}
                            onValueChange={(postSwitchRejectSuppressSeconds) =>
                              setForm({
                                ...form,
                                postSwitchRejectSuppressSeconds,
                              })
                            }
                          />
                        </div>
                      </div>
                      <p className="text-xs leading-5 text-default-500">
                        此模式已接管自动选线：普通自动回切、质量容灾、固定延迟目标、智能选择、抖动保护、备用预热和锁定入口均不可同时启用。故障切换、冷却、恢复确认、切换后验证与
                        DNS 确认继续生效。
                      </p>
                    </div>
                  )}
                </div>
              )}
            </section>

            <section className={sectionCardClass}>
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <h3 className="text-sm font-semibold">质量容灾</h3>
                  <p className="mt-1 text-xs text-default-500">
                    按每条入口自己的基线判断劣化，绝对延迟只作为兜底阈值。
                  </p>
                </div>
                <Switch
                  isDisabled={
                    form.routingMode !== "failover" ||
                    form.tcpLatencySelectionEnabled
                  }
                  isSelected={
                    form.routingMode === "failover" && form.qualityEnabled
                  }
                  onValueChange={(qualityEnabled) =>
                    setForm({ ...form, qualityEnabled })
                  }
                >
                  启用质量切换
                </Switch>
              </div>
              {form.routingMode !== "failover" ? (
                <p className="mt-3 text-xs text-default-500">
                  多入口同时运行由 DNS
                  返回健康入口，质量切换只应用在主备容灾模式。
                </p>
              ) : form.tcpLatencySelectionEnabled ? (
                <p className="mt-3 text-xs text-default-500">
                  TCP
                  延迟优选已接管自动选线，质量容灾策略已关闭，避免两套规则互相抢占线路。
                </p>
              ) : (
                form.qualityEnabled && (
                  <div className="mt-3 grid gap-3">
                    <div className="grid gap-3 sm:grid-cols-2">
                      <Select
                        description="从哪个网络视角判断质量劣化；本地 Connector 可代表家庭宽带。"
                        label="质量探测源"
                        selectedKeys={[form.qualityProbeSourceType]}
                        onSelectionChange={(keys) =>
                          setForm({
                            ...form,
                            qualityProbeSourceType: String(
                              Array.from(keys)[0] || "panel",
                            ) as "panel" | "node" | "connector",
                            qualityProbeSourceId: "",
                          })
                        }
                      >
                        <SelectItem key="panel">面板服务器</SelectItem>
                        <SelectItem key="node">指定 Agent 节点</SelectItem>
                        <SelectItem key="connector">指定 Connector</SelectItem>
                      </Select>
                      {form.qualityProbeSourceType === "node" && (
                        <Select
                          label="Agent 节点"
                          placeholder={`Agent ≥ ${probeSources.minimumRemoteVersion}`}
                          selectedKeys={
                            form.qualityProbeSourceId
                              ? [form.qualityProbeSourceId]
                              : []
                          }
                          onSelectionChange={(keys) =>
                            setForm({
                              ...form,
                              qualityProbeSourceId: String(
                                Array.from(keys)[0] || "",
                              ),
                            })
                          }
                        >
                          {probeSources.nodes.map((source) => (
                            <SelectItem
                              key={String(source.id)}
                              textValue={`${source.name} ${source.address || ""}`}
                            >
                              {source.name} · {source.address || "无地址"} ·{" "}
                              {source.version || "-"}
                            </SelectItem>
                          ))}
                        </Select>
                      )}
                      {form.qualityProbeSourceType === "connector" && (
                        <Select
                          label="Connector"
                          placeholder={`Connector ≥ ${probeSources.minimumRemoteVersion}`}
                          selectedKeys={
                            form.qualityProbeSourceId
                              ? [form.qualityProbeSourceId]
                              : []
                          }
                          onSelectionChange={(keys) =>
                            setForm({
                              ...form,
                              qualityProbeSourceId: String(
                                Array.from(keys)[0] || "",
                              ),
                            })
                          }
                        >
                          {probeSources.connectors.map((source) => (
                            <SelectItem
                              key={String(source.id)}
                              textValue={`${source.name} ${source.platform || ""}`}
                            >
                              {source.name} · {source.platform || "-"} ·{" "}
                              {source.version || "-"}
                            </SelectItem>
                          ))}
                        </Select>
                      )}
                    </div>
                    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                      <Input
                        description="每轮对同一入口发起几次 TCP 连接，取均值/P95/抖动。"
                        label="TCP 次数"
                        max={10}
                        min={2}
                        type="number"
                        value={form.qualityProbeCount}
                        onValueChange={(qualityProbeCount) =>
                          setForm({ ...form, qualityProbeCount })
                        }
                      />
                      <Input
                        description="兜底绝对阈值。比如美西本来就 150ms，可把这里设高，主要依赖基线倍数。"
                        label="兜底劣化 ms"
                        min={20}
                        type="number"
                        value={form.qualityDegradeThresholdMs}
                        onValueChange={(qualityDegradeThresholdMs) =>
                          setForm({ ...form, qualityDegradeThresholdMs })
                        }
                      />
                      <Input
                        description="恢复判断参考值。入口延迟低于它，或低于基线恢复倍数，就算恢复一次。"
                        label="恢复参考 ms"
                        min={10}
                        type="number"
                        value={form.qualityRecoverThresholdMs}
                        onValueChange={(qualityRecoverThresholdMs) =>
                          setForm({ ...form, qualityRecoverThresholdMs })
                        }
                      />
                      <Input
                        description="本轮 TCP 样本失败比例达到该值，认为存在丢包或连接异常。"
                        label="丢包阈值 %"
                        max={100}
                        min={1}
                        type="number"
                        value={form.qualityLossThresholdPercent}
                        onValueChange={(qualityLossThresholdPercent) =>
                          setForm({ ...form, qualityLossThresholdPercent })
                        }
                      />
                      <Input
                        description="最慢 5% 样本的延迟阈值，用来识别偶发卡顿和高尾延迟。"
                        label="P95 阈值 ms"
                        min={20}
                        type="number"
                        value={form.qualityP95ThresholdMs}
                        onValueChange={(qualityP95ThresholdMs) =>
                          setForm({ ...form, qualityP95ThresholdMs })
                        }
                      />
                      <Input
                        description="同一轮样本的波动幅度。抖动大说明体验可能忽快忽慢。"
                        label="抖动阈值 ms"
                        min={1}
                        type="number"
                        value={form.qualityJitterThresholdMs}
                        onValueChange={(qualityJitterThresholdMs) =>
                          setForm({ ...form, qualityJitterThresholdMs })
                        }
                      />
                      <Input
                        description="相对自身历史基线判断。3 表示延迟达到平时 3 倍才算劣化。"
                        label="基线劣化倍数"
                        min={1.2}
                        step={0.1}
                        type="number"
                        value={form.qualityDegradeFactor}
                        onValueChange={(qualityDegradeFactor) =>
                          setForm({ ...form, qualityDegradeFactor })
                        }
                      />
                      <Input
                        description="相对自身基线判断恢复。1.8 表示回落到基线 1.8 倍内算恢复。"
                        label="基线恢复倍数"
                        min={1}
                        step={0.1}
                        type="number"
                        value={form.qualityRecoverFactor}
                        onValueChange={(qualityRecoverFactor) =>
                          setForm({ ...form, qualityRecoverFactor })
                        }
                      />
                      <Input
                        description="连续几轮都劣化，才真正标记入口质量差。"
                        label="劣化确认次数"
                        max={20}
                        min={1}
                        type="number"
                        value={form.qualityDegradeSamples}
                        onValueChange={(qualityDegradeSamples) =>
                          setForm({ ...form, qualityDegradeSamples })
                        }
                      />
                      <Input
                        description="连续几轮都恢复，才允许重新参与回切或优选。"
                        label="恢复确认次数"
                        max={20}
                        min={1}
                        type="number"
                        value={form.qualityRecoverSamples}
                        onValueChange={(qualityRecoverSamples) =>
                          setForm({ ...form, qualityRecoverSamples })
                        }
                      />
                    </div>
                    <div className="grid gap-4 border-t border-divider pt-4 lg:grid-cols-[260px_minmax(0,1fr)] lg:items-start">
                      <div className="space-y-2">
                        <Switch
                          isSelected={form.qualityFixedTargetEnabled}
                          onValueChange={(qualityFixedTargetEnabled) =>
                            setForm({ ...form, qualityFixedTargetEnabled })
                          }
                        >
                          启用固定延迟目标
                        </Switch>
                        <p className="text-xs leading-5 text-default-500">
                          超过固定目标时直接记为质量劣化，适合低延迟入口设置硬性体验线。
                        </p>
                      </div>
                      <div className="grid gap-3 sm:grid-cols-2">
                        <Input
                          isDisabled={!form.qualityFixedTargetEnabled}
                          label="目标延迟 ms"
                          min={1}
                          type="number"
                          value={form.qualityFixedTargetMs}
                          onValueChange={(qualityFixedTargetMs) =>
                            setForm({ ...form, qualityFixedTargetMs })
                          }
                        />
                        <Select
                          isDisabled={!form.qualityFixedTargetEnabled}
                          label="目标未达标时"
                          selectedKeys={[
                            fixedTargetModeKey(form.qualityFixedTargetStrict),
                          ]}
                          onSelectionChange={(keys) => {
                            const mode = String(
                              Array.from(keys)[0] || "prefer_best",
                            ) as FixedTargetMode;

                            setForm({
                              ...form,
                              qualityFixedTargetStrict: mode === "strict",
                            });
                          }}
                        >
                          {Object.entries(fixedTargetModeMeta).map(
                            ([key, meta]) => (
                              <SelectItem key={key} textValue={meta.label}>
                                {meta.label}
                              </SelectItem>
                            ),
                          )}
                        </Select>
                      </div>
                      <p className="text-xs leading-5 text-default-500 lg:col-span-2">
                        开启后，入口连续超过目标延迟会算作质量劣化；“达标优先，没达标就选最优”会在没有达标备用时，从差线路里挑相对最优；“只切到达标备用入口”则更严格，没有达标入口时不会因为这个规则强行切换。
                      </p>
                    </div>
                    <div className="grid gap-4 border-t border-divider pt-4">
                      <div className="grid gap-4 lg:grid-cols-[260px_minmax(0,1fr)] lg:items-start">
                        <div className="space-y-2">
                          <Switch
                            isSelected={form.qualityFlapGuardEnabled}
                            onValueChange={(qualityFlapGuardEnabled) =>
                              setForm({
                                ...form,
                                qualityFlapGuardEnabled,
                                qualityPenaltyEnabled: qualityFlapGuardEnabled
                                  ? form.qualityPenaltyEnabled
                                  : false,
                              })
                            }
                          >
                            启用抖动保护
                          </Switch>
                          <p className="text-xs leading-5 text-default-500">
                            统计窗口内反复劣化的次数，避免入口在 10ms/200ms
                            之间来回跳。
                          </p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-3">
                          <Input
                            isDisabled={!form.qualityFlapGuardEnabled}
                            label="统计窗口（秒）"
                            min={60}
                            type="number"
                            value={form.qualityFlapWindowSeconds}
                            onValueChange={(qualityFlapWindowSeconds) =>
                              setForm({ ...form, qualityFlapWindowSeconds })
                            }
                          />
                          <Input
                            isDisabled={!form.qualityFlapGuardEnabled}
                            label="触发次数"
                            max={20}
                            min={2}
                            type="number"
                            value={form.qualityFlapThreshold}
                            onValueChange={(qualityFlapThreshold) =>
                              setForm({ ...form, qualityFlapThreshold })
                            }
                          />
                          <Input
                            isDisabled={!form.qualityFlapGuardEnabled}
                            label="基础保护（秒）"
                            min={60}
                            type="number"
                            value={form.qualityFlapSuppressSeconds}
                            onValueChange={(qualityFlapSuppressSeconds) =>
                              setForm({ ...form, qualityFlapSuppressSeconds })
                            }
                          />
                        </div>
                      </div>
                      <div className="grid gap-4 border-t border-divider pt-4 lg:grid-cols-[260px_minmax(0,1fr)] lg:items-start">
                        <div className="space-y-2">
                          <Switch
                            isDisabled={!form.qualityFlapGuardEnabled}
                            isSelected={form.qualityPenaltyEnabled}
                            onValueChange={(qualityPenaltyEnabled) =>
                              setForm({ ...form, qualityPenaltyEnabled })
                            }
                          >
                            启用阶梯惩罚
                          </Switch>
                          <p className="text-xs leading-5 text-default-500">
                            短期复发会延长保护期，保护结束后继续观察一段时间。
                          </p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-2">
                          <Input
                            isDisabled={
                              !form.qualityFlapGuardEnabled ||
                              !form.qualityPenaltyEnabled
                            }
                            label="复发记忆（秒）"
                            max={604800}
                            min={3600}
                            type="number"
                            value={form.qualityPenaltyResetSeconds}
                            onValueChange={(qualityPenaltyResetSeconds) =>
                              setForm({ ...form, qualityPenaltyResetSeconds })
                            }
                          />
                          <Input
                            isDisabled={
                              !form.qualityFlapGuardEnabled ||
                              !form.qualityPenaltyEnabled
                            }
                            label="恢复观察（秒）"
                            max={86400}
                            min={0}
                            type="number"
                            value={form.qualityPenaltyObserveSeconds}
                            onValueChange={(qualityPenaltyObserveSeconds) =>
                              setForm({ ...form, qualityPenaltyObserveSeconds })
                            }
                          />
                        </div>
                      </div>
                      <p className="text-xs leading-5 text-default-500">
                        规则：
                        {durationText(Number(form.qualityFlapWindowSeconds))}
                        内质量劣化 {form.qualityFlapThreshold || 3}{" "}
                        次算一次故障事件；开启阶梯惩罚后，短期复发会从 L1
                        逐级加码到 L5，保护时长约为{" "}
                        {durationText(Number(form.qualityFlapSuppressSeconds))}{" "}
                        / 1 小时 / 2 小时 / 6 小时 / 12
                        小时。保护结束后进入恢复观察，必须达到恢复确认次数才重新参与回切。
                      </p>
                    </div>
                    <div className="grid gap-4 border-t border-divider pt-4">
                      <div className="grid gap-4 lg:grid-cols-[260px_minmax(0,1fr)] lg:items-start">
                        <div className="space-y-2">
                          <Switch
                            isSelected={form.smartSelectionEnabled}
                            onValueChange={(smartSelectionEnabled) =>
                              setForm({ ...form, smartSelectionEnabled })
                            }
                          >
                            启用智能选择
                          </Switch>
                          <p className="text-xs leading-5 text-default-500">
                            接管同类故障、拓扑隔离和差中选优，避免盲目按顺序切到同样差的线路。
                          </p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-3">
                          <div className="flex min-h-14 items-center">
                            <Switch
                              isDisabled={!form.smartSelectionEnabled}
                              isSelected={form.degradedFallbackEnabled}
                              onValueChange={(degradedFallbackEnabled) =>
                                setForm({ ...form, degradedFallbackEnabled })
                              }
                            >
                              全部差时差中选优
                            </Switch>
                          </div>
                          <div className="flex min-h-14 items-center">
                            <Switch
                              isDisabled={!form.smartSelectionEnabled}
                              isSelected={form.sameFaultAvoidanceEnabled}
                              onValueChange={(sameFaultAvoidanceEnabled) =>
                                setForm({ ...form, sameFaultAvoidanceEnabled })
                              }
                            >
                              避开同类故障
                            </Switch>
                          </div>
                          <div className="flex min-h-14 items-center">
                            <Switch
                              isDisabled={!form.smartSelectionEnabled}
                              isSelected={form.topologyAvoidanceEnabled}
                              onValueChange={(topologyAvoidanceEnabled) =>
                                setForm({ ...form, topologyAvoidanceEnabled })
                              }
                            >
                              避开同节点/同大网段
                            </Switch>
                          </div>
                        </div>
                      </div>
                      <div className="grid gap-4 border-t border-divider pt-4 lg:grid-cols-[260px_minmax(0,1fr)] lg:items-start">
                        <div className="space-y-2">
                          <Switch
                            isDisabled={!form.smartSelectionEnabled}
                            isSelected={form.preheatEnabled}
                            onValueChange={(preheatEnabled) =>
                              setForm({ ...form, preheatEnabled })
                            }
                          >
                            备用线路预热
                          </Switch>
                          <p className="text-xs leading-5 text-default-500">
                            提前确认备用线路可用，主线故障时优先从已验证的备用里选。
                          </p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-2">
                          <Input
                            isDisabled={
                              !form.smartSelectionEnabled ||
                              !form.preheatEnabled
                            }
                            label="预热备用数"
                            max={9}
                            min={1}
                            type="number"
                            value={form.preheatBackupCount}
                            onValueChange={(preheatBackupCount) =>
                              setForm({ ...form, preheatBackupCount })
                            }
                          />
                          <div className="flex min-h-14 items-center">
                            <Switch
                              isDisabled={
                                !form.smartSelectionEnabled ||
                                !form.preheatEnabled
                              }
                              isSelected={form.preheatStrictIsolation}
                              onValueChange={(preheatStrictIsolation) =>
                                setForm({ ...form, preheatStrictIsolation })
                              }
                            >
                              严格预热隔离
                            </Switch>
                          </div>
                        </div>
                      </div>
                      <div className="grid gap-4 border-t border-divider pt-4 lg:grid-cols-[260px_minmax(0,1fr)] lg:items-start">
                        <div className="space-y-2">
                          <p className="text-sm font-medium">切换验证与 DNS</p>
                          <p className="text-xs leading-5 text-default-500">
                            切换后确认新入口可用，并把验证失败的目标短暂拉黑。
                          </p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                          <div className="flex min-h-14 items-center">
                            <Switch
                              isSelected={form.postSwitchVerifyEnabled}
                              onValueChange={(postSwitchVerifyEnabled) =>
                                setForm({ ...form, postSwitchVerifyEnabled })
                              }
                            >
                              切换后验证入口
                            </Switch>
                          </div>
                          <div className="flex min-h-14 items-center">
                            <Switch
                              isSelected={form.dnsVerifyEnabled}
                              onValueChange={(dnsVerifyEnabled) =>
                                setForm({ ...form, dnsVerifyEnabled })
                              }
                            >
                              DNS 生效确认
                            </Switch>
                          </div>
                          <Input
                            label="验证失败黑名单（秒）"
                            min={60}
                            type="number"
                            value={form.postSwitchRejectSuppressSeconds}
                            onValueChange={(postSwitchRejectSuppressSeconds) =>
                              setForm({
                                ...form,
                                postSwitchRejectSuppressSeconds,
                              })
                            }
                          />
                        </div>
                      </div>
                      <p className="text-xs leading-5 text-default-500">
                        智能选择会负责同类故障、拓扑隔离、预热和差中选优。它与
                        TCP
                        延迟优选属于两套互斥的自动选线策略，只能选择其中一种。
                      </p>
                    </div>
                  </div>
                )
              )}
            </section>

            <div className="rounded-2xl border border-warning-200 bg-warning-50 px-4 py-3 text-xs leading-5 text-warning-700 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-300">
              面板会自动创建或更新仅 DNS 记录，不开启 Cloudflare
              代理。请确保面板服务器能访问各公网入口端口。检测和 DNS
              更新可在数秒内完成，但运营商及客户端 DNS
              缓存仍可能延迟实际生效；已经建立的连接需要重新连接。
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setFormOpen(false)}>
              取消
            </Button>
            <Button color="primary" isLoading={submitting} onPress={submit}>
              保存并同步 DNS
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={historyOpen}
        scrollBehavior="inside"
        size="3xl"
        onOpenChange={setHistoryOpen}
      >
        <ModalContent>
          <ModalHeader>{historyName} · 切换历史</ModalHeader>
          <ModalBody className="gap-4">
            {historyGroup && (
              <div className="border-y border-divider py-3">
                <p className="text-xs text-default-500">当前组规则</p>
                <p className="mt-1 text-sm font-medium">
                  {explainGroupStrategy(historyGroup).title}
                </p>
                <p className="mt-1 text-xs leading-5 text-default-500">
                  {explainGroupStrategy(historyGroup).decisionHint}
                </p>
              </div>
            )}
            {events.length === 0 ? (
              <div className="py-12 text-center text-sm text-default-500">
                暂无切换记录
              </div>
            ) : (
              <div className="divide-y divide-divider">
                {events.map((event) => (
                  <div key={event.id} className="flex gap-3 py-4">
                    <div
                      className={`mt-1 h-2.5 w-2.5 flex-none rounded-full ${event.status === "success" ? "bg-success" : "bg-danger"}`}
                    />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <Chip
                            color={
                              event.status === "success" ? "success" : "danger"
                            }
                            size="sm"
                            variant="flat"
                          >
                            {eventActionText(event)}
                          </Chip>
                          <p className="text-sm font-medium">
                            {eventReasonText(event)}
                          </p>
                        </div>
                        <span className="text-xs text-default-500">
                          {timeText(event.createdTime)}
                        </span>
                      </div>
                      <div className="mt-2 grid gap-2 text-xs text-default-500 sm:grid-cols-2">
                        <div>
                          <p className="font-medium text-default-600">
                            线路变化
                          </p>
                          <p className="mt-1 flex flex-wrap items-center gap-1">
                            <span>
                              {event.fromForwardName ||
                                event.fromNodeName ||
                                "初始"}
                            </span>
                            <ArrowRight size={12} />
                            <span>
                              {event.toForwardName || event.toNodeName || "-"}
                            </span>
                          </p>
                        </div>
                        <div>
                          <p className="font-medium text-default-600">
                            入口地址
                          </p>
                          <p className="mt-1 break-all">
                            {eventEndpointText(event) || "无地址记录"}
                          </p>
                        </div>
                      </div>
                      <div className="mt-2 border-l-2 border-default-200 px-3 py-2 text-xs leading-5 text-default-500">
                        <p>
                          <span className="font-medium text-default-600">
                            执行结果：
                          </span>
                          {event.status === "success"
                            ? " DNS 已写入目标入口。"
                            : " 切换失败，按详情处理或已尝试回滚。"}
                        </p>
                        {event.detail && (
                          <p className="mt-1">
                            <span className="font-medium text-default-600">
                              验证细节：
                            </span>
                            {event.detail}
                          </p>
                        )}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setHistoryOpen(false)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
