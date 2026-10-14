import type {
  ExitCountry,
  ExitGroupStatus,
  ExitMember,
  ExitStatusView,
} from "@/api";

type ChipColor = "success" | "warning" | "danger" | "default" | "primary";

/** 出口整体状态在界面上的名称和颜色。 */
export const exitStatusMeta = (
  status?: ExitGroupStatus,
): { label: string; color: ChipColor } => {
  switch (status) {
    case "ok":
      return { label: "正常", color: "success" };
    case "searching":
      return { label: "正在寻找出口", color: "primary" };
    case "no_exit":
      return { label: "暂无可用出口", color: "danger" };
    case "node_offline":
      return { label: "节点离线", color: "default" };
    case "agent_outdated":
      return { label: "节点需要升级", color: "warning" };
    case "setup_failed":
      return { label: "节点环境不满足", color: "danger" };
    case "agent_error":
      return { label: "节点未响应", color: "warning" };
    case "removing":
      return { label: "正在清理", color: "default" };
    default:
      return { label: "未知", color: "default" };
  }
};

/** 单个出口的状态名称和颜色。 */
export const exitMemberMeta = (
  member: Pick<ExitMember, "state" | "active" | "approved">,
): { label: string; color: ChipColor } => {
  if (member.state === "up" && member.active)
    return { label: "使用中", color: "success" };
  if (member.state === "up" && member.approved)
    return { label: "备用", color: "primary" };
  if (member.state === "up") return { label: "等待判定", color: "default" };
  if (member.state === "connecting")
    return { label: "连接中", color: "default" };
  if (member.state === "rejected") return { label: "已排除", color: "warning" };
  if (member.state === "idle") return { label: "已释放", color: "default" };

  return { label: "连不上", color: "danger" };
};

export interface ExitPuritySignals {
  kind?: string;
  flaggedProxy?: number | boolean | null;
  ipqsScore?: number | null;
  abuseScore?: number | null;
}

/**
 * 候选出口的纯净度。依据是 IP 库给出的网络类型、是否已被标记为代理，
 * 以及做过深度检测时的风险分。没有做过深度检测时只能给出初步结论。
 */
export const exitPurity = (
  signals: ExitPuritySignals,
): { label: string; color: ChipColor; reason: string } => {
  const kind = signals.kind || "home";

  if (kind !== "home") {
    const names: Record<string, string> = {
      hosting: "机房网络",
      mobile: "移动网络",
      project: "VPN Gate 项目方网络",
      mismatch: "IP 库显示在别的国家",
      unknown: "归属未知",
    };

    return {
      label: "不是家宽",
      color: "default",
      reason: `${names[kind] || kind}，不会被选用`,
    };
  }
  const fraud = signals.ipqsScore ?? null;
  const abuse = signals.abuseScore ?? null;

  if ((fraud !== null && fraud >= 75) || (abuse !== null && abuse >= 50))
    return {
      label: "风险高",
      color: "danger",
      reason: "风险情报评分高，容易被网站拦截",
    };
  if (fraud !== null || abuse !== null) {
    const low =
      (fraud === null || fraud < 30) && (abuse === null || abuse < 25);

    if (low && !signals.flaggedProxy)
      return {
        label: "干净",
        color: "success",
        reason: "未被标记为代理，风险情报评分低",
      };

    return {
      label: "一般",
      color: "warning",
      reason: low
        ? "风险评分低，但 IP 库已把它标记为代理"
        : "风险评分中等（欺诈 30–74 或滥用 25–49），部分网站可能要求验证",
    };
  }
  if (signals.flaggedProxy)
    return {
      label: "已被标记",
      color: "warning",
      reason: "IP 库已把它标记为代理，敏感网站可能识别",
    };

  return {
    label: "较干净",
    color: "success",
    reason: "未被标记为代理；尚未做深度检测",
  };
};

/** 把毫秒时长写成“3 小时 12 分”这样的短句。 */
export const formatExitDuration = (milliseconds?: number | null): string => {
  if (milliseconds === undefined || milliseconds === null || milliseconds < 0)
    return "-";
  const minutes = Math.floor(milliseconds / 60_000);

  if (minutes < 1) return "不到 1 分钟";
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);

  if (hours < 24) {
    const rest = minutes % 60;

    return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`;
  }
  const days = Math.floor(hours / 24);
  const restHours = hours % 24;

  return restHours ? `${days} 天 ${restHours} 小时` : `${days} 天`;
};

/** 私人代理行内的一句话出口说明。 */
export const exitSummary = (exit: ExitStatusView, now = Date.now()): string => {
  if (exit.status === "ok") {
    const parts = [exit.exitIp || "", exit.exitOwner || ""].filter(Boolean);
    const stable =
      exit.activeSince && now >= exit.activeSince
        ? `已稳定 ${formatExitDuration(now - exit.activeSince)}`
        : "";

    return [parts.join(" · "), stable].filter(Boolean).join(" · ");
  }

  return exit.detail || exitStatusMeta(exit.status).label;
};

/** 可以选择的国家排在前面，暂无出口的国家排在后面并保持原顺序。 */
export const sortExitCountries = (countries: ExitCountry[]): ExitCountry[] =>
  [...countries].sort((left, right) => {
    if (left.available > 0 !== right.available > 0)
      return left.available > 0 ? -1 : 1;

    return right.available - left.available;
  });

/** 比较 Agent 版本号，判断节点是否支持家宽出口。 */
export const agentSupportsExit = (
  version: string | undefined,
  minimum: string,
): boolean => {
  if (!version) return false;
  const parse = (value: string) =>
    value
      .trim()
      .replace(/^v/i, "")
      .split(/[-+]/)[0]
      .split(".")
      .map((part) => Number.parseInt(part, 10));
  const actual = parse(version);
  const required = parse(minimum);

  if (actual.some(Number.isNaN) || required.some(Number.isNaN)) return false;
  for (let i = 0; i < Math.max(actual.length, required.length); i++) {
    const left = actual[i] ?? 0;
    const right = required[i] ?? 0;

    if (left !== right) return left > right;
  }

  return true;
};
