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
