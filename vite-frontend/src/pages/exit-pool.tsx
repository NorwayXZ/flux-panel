import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import {
  ArrowLeftRight,
  Ban,
  History,
  Home,
  MousePointerClick,
  RefreshCw,
  Shuffle,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  blockExitMember,
  getExitPoolOverview,
  refreshExitPool,
  switchExit,
  type ExitGroup,
  type ExitMember,
  type ExitPoolOverview,
} from "@/api";
import ExitCandidateModal from "@/components/exit-candidate-modal";
import ExitEventsModal from "@/components/exit-events-modal";
import {
  exitMemberMeta,
  exitStatusMeta,
  exitSummary,
} from "@/utils/exit-display";
import { formatBytes, formatDateTime } from "@/utils/format";

const kindLabels: Record<string, string> = {
  home: "家庭宽带",
  mobile: "移动网络",
  hosting: "机房",
  project: "项目方网络",
  mismatch: "国家不符",
  unknown: "未知",
};

/** 出口失败或被排除时显示的说明。 */
const memberNote = (member: ExitMember) => {
  if (member.state === "failed" || member.state === "rejected")
    return member.error || "";
  if (member.state === "up" && !member.approved) return "正在确认是否为家宽";
  if (member.exitFlagged) return "已被部分 IP 库标记为代理";

  return "";
};

/** 节点离线或版本不够时，出口的状态无法更新，列表不再显示。 */
const membersCurrent = (group: Pick<ExitGroup, "status">) =>
  !["node_offline", "agent_outdated", "agent_error", "removing"].includes(
    group.status,
  );

const MAX_INACTIVE_ROWS = 5;

/** 在用和连接中的出口全部显示，连不上的只显示最近几个。 */
const visibleMembers = (members: ExitMember[]) => {
  const live = members.filter(
    (member) => member.state === "up" || member.state === "connecting",
  );
  const inactive = members.filter(
    (member) => member.state !== "up" && member.state !== "connecting",
  );

  return {
    shown: [...live, ...inactive.slice(0, MAX_INACTIVE_ROWS)],
    hidden: Math.max(0, inactive.length - MAX_INACTIVE_ROWS),
  };
};

export default function ExitPoolPage() {
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [overview, setOverview] = useState<ExitPoolOverview | null>(null);
  const [eventsTarget, setEventsTarget] = useState<{
    groupId: number;
    title: string;
  } | null>(null);

  const load = async () => {
    try {
      const response = await getExitPoolOverview();

      if (response.code === 0 && response.data) setOverview(response.data);
      else toast.error(response.msg || "读取出口池失败");
    } catch {
      toast.error("读取出口池失败");
    }
    setLoading(false);
  };

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => {
      if (!document.hidden) void load();
    }, 10_000);

    return () => window.clearInterval(timer);
  }, []);

  const refresh = async () => {
    setRefreshing(true);
    const response = await refreshExitPool();

    setRefreshing(false);
    if (response.code !== 0) toast.error(response.msg || "刷新失败");
    else toast.success("候选列表已刷新");
    void load();
  };

  const [candidateTarget, setCandidateTarget] = useState<{
    groupId: number;
    title: string;
  } | null>(null);
  const [switching, setSwitching] = useState<number | null>(null);

  const change = async (group: ExitGroup, member?: ExitMember) => {
    setSwitching(group.id);
    const response = await switchExit(group.id, member?.id);

    setSwitching(null);
    if (response.code !== 0) return toast.error(response.msg || "切换失败");
    toast.success(`已切换到 ${response.data?.exitIp || "新的出口"}`);
    void load();
  };

  const block = async (member: ExitMember) => {
    const address = member.exitIp || member.host;

    if (
      !window.confirm(
        `确认排除出口 ${address} 吗？${member.active ? "它正在使用中，节点会立即换到另一个出口。" : ""}`,
      )
    )
      return;
    const response = await blockExitMember(member.id);

    if (response.code !== 0) return toast.error(response.msg || "操作失败");
    toast.success(`已排除 ${address}`);
    void load();
  };

  const countries = overview?.countries || [];
  const groups = overview?.groups || [];

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">私人代理</p>
          <h1 className="mt-1 text-2xl font-semibold">家宽出口池</h1>
          <p className="mt-2 max-w-3xl text-sm text-default-500">
            这里的出口由系统自动连接、检测和更换，平时不需要操作。只有在某个出口表现不好时，才需要手动排除它。
          </p>
        </div>
        <Button
          isLoading={refreshing}
          startContent={!refreshing && <RefreshCw size={16} />}
          variant="flat"
          onPress={refresh}
        >
          刷新候选列表
        </Button>
      </header>

      {loading ? (
        <div className="flex min-h-40 items-center justify-center">
          <Spinner />
        </div>
      ) : (
        <>
          {overview?.lastRefreshError && (
            <div className="rounded-lg border border-warning-200 bg-warning-50 px-4 py-3 text-sm text-warning-700 dark:border-warning-500/30 dark:bg-warning-500/10 dark:text-warning-300">
              最近一次读取候选列表失败：{overview.lastRefreshError}
              。已有的出口不受影响，系统会自动重试。
            </div>
          )}

          <section className="rounded-lg border border-divider">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-divider px-5 py-3">
              <h2 className="text-sm font-medium">
                各国当前可用的家宽候选数量
              </h2>
              <span className="text-xs text-default-500">
                {overview?.lastRefreshAt
                  ? `更新于 ${formatDateTime(overview.lastRefreshAt)}`
                  : "尚未读取"}
              </span>
            </div>
            {countries.length === 0 ? (
              <p className="px-5 py-6 text-sm text-default-500">
                还没有读取到候选。点击右上角“刷新候选列表”试一次。
              </p>
            ) : (
              <div className="grid grid-cols-2 gap-px bg-divider sm:grid-cols-3 lg:grid-cols-6">
                {countries.map((country) => (
                  <div
                    key={country.country}
                    className="bg-background px-5 py-4"
                  >
                    <div className="text-sm font-medium">{country.name}</div>
                    <div
                      className={`mt-1 text-2xl font-semibold ${country.available > 0 ? "" : "text-default-300"}`}
                    >
                      {country.available}
                    </div>
                    <div className="mt-0.5 text-xs text-default-500">
                      列表共 {country.total} 个
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>

          {groups.length === 0 ? (
            <div className="flex min-h-40 flex-col items-center justify-center gap-3 rounded-lg border border-divider px-6 text-center text-default-500">
              <Home size={28} />
              <span className="text-sm">
                还没有代理使用家宽出口。在“私人代理”里新建代理时，把出口选成“指定国家的家宽”即可。
              </span>
            </div>
          ) : (
            groups.map((group) => {
              const meta = exitStatusMeta(group.status);
              const { shown, hidden } = visibleMembers(group.members);

              return (
                <section
                  key={group.id}
                  className="overflow-hidden rounded-lg border border-divider"
                >
                  <div className="flex flex-col gap-3 border-b border-divider px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <h2 className="font-semibold">
                          {group.countryName}家宽 · {group.nodeName}
                        </h2>
                        <Chip color={meta.color} size="sm" variant="flat">
                          {meta.label}
                        </Chip>
                        <Chip size="sm" variant="flat">
                          {group.proxyCount} 个代理在用
                        </Chip>
                      </div>
                      <p className="mt-1 break-all text-sm text-default-500">
                        {exitSummary(group)}
                        {group.status === "ok"
                          ? ` · 今日切换 ${group.switchesToday ?? 0} 次`
                          : ""}
                      </p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        isDisabled={!group.standbyReady}
                        isLoading={switching === group.id}
                        size="sm"
                        startContent={
                          switching !== group.id && <Shuffle size={15} />
                        }
                        title={
                          group.standbyReady
                            ? "换到已就绪的备用出口"
                            : "备用出口还没有就绪，稍后再试"
                        }
                        variant="flat"
                        onPress={() => change(group)}
                      >
                        换一个出口
                      </Button>
                      <Button
                        size="sm"
                        startContent={<MousePointerClick size={15} />}
                        variant="flat"
                        onPress={() =>
                          setCandidateTarget({
                            groupId: group.id,
                            title: `为 ${group.countryName}家宽 · ${group.nodeName} 指定出口`,
                          })
                        }
                      >
                        指定出口
                      </Button>
                      <Button
                        size="sm"
                        startContent={<History size={15} />}
                        variant="flat"
                        onPress={() =>
                          setEventsTarget({
                            groupId: group.id,
                            title: `${group.countryName}家宽 · ${group.nodeName} 的切换记录`,
                          })
                        }
                      >
                        切换记录
                      </Button>
                    </div>
                  </div>
                  {group.members.length === 0 || !membersCurrent(group) ? (
                    <p className="px-5 py-6 text-sm text-default-500">
                      {group.detail || "正在挑选候选出口"}
                    </p>
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full min-w-[760px] text-sm">
                        <thead>
                          <tr className="bg-default-50 text-left text-xs text-default-500">
                            <th className="px-5 py-2.5 font-normal">出口 IP</th>
                            <th className="px-3 py-2.5 font-normal">运营商</th>
                            <th className="px-3 py-2.5 font-normal">类型</th>
                            <th className="px-3 py-2.5 font-normal">延迟</th>
                            <th className="px-3 py-2.5 font-normal">流量</th>
                            <th className="px-3 py-2.5 font-normal">状态</th>
                            <th className="px-5 py-2.5 text-right font-normal">
                              操作
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {shown.map((member) => {
                            const memberMeta = exitMemberMeta(member);
                            const live =
                              member.state === "up" ||
                              member.state === "connecting";
                            const note = memberNote(member);

                            return (
                              <tr
                                key={member.id}
                                className={`border-t border-divider ${live ? "" : "text-default-400"}`}
                              >
                                <td className="px-5 py-3 font-mono">
                                  {member.exitIp || member.host}
                                </td>
                                <td className="px-3 py-3">
                                  {member.exitOwner || member.owner || "-"}
                                </td>
                                <td className="px-3 py-3">
                                  {member.exitKind
                                    ? kindLabels[member.exitKind] ||
                                      member.exitKind
                                    : "-"}
                                </td>
                                <td className="px-3 py-3">
                                  {member.state === "up" && member.latencyMs
                                    ? `${member.latencyMs} ms`
                                    : "-"}
                                </td>
                                <td className="px-3 py-3">
                                  {member.state === "up"
                                    ? formatBytes(
                                        (member.rxBytes || 0) +
                                          (member.txBytes || 0),
                                      )
                                    : "-"}
                                </td>
                                <td className="px-3 py-3">
                                  <div className="flex flex-wrap items-center gap-2">
                                    <Chip
                                      color={memberMeta.color}
                                      size="sm"
                                      variant="flat"
                                    >
                                      {memberMeta.label}
                                    </Chip>
                                    {note && (
                                      <span className="text-xs text-default-500">
                                        {note}
                                      </span>
                                    )}
                                  </div>
                                </td>
                                <td className="px-5 py-3 text-right">
                                  {member.state === "up" &&
                                  member.approved &&
                                  !member.active ? (
                                    <Button
                                      isIconOnly
                                      aria-label={`切换到出口 ${member.exitIp || member.host}`}
                                      size="sm"
                                      title="切换到这个出口"
                                      variant="light"
                                      onPress={() => change(group, member)}
                                    >
                                      <ArrowLeftRight size={16} />
                                    </Button>
                                  ) : null}
                                  {live && (
                                    <Button
                                      isIconOnly
                                      aria-label={`排除出口 ${member.exitIp || member.host}`}
                                      size="sm"
                                      title="排除这个出口"
                                      variant="light"
                                      onPress={() => block(member)}
                                    >
                                      <Ban size={16} />
                                    </Button>
                                  )}
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                      {hidden > 0 && (
                        <p className="border-t border-divider px-5 py-2.5 text-xs text-default-500">
                          近一小时内另有 {hidden}{" "}
                          个候选连不上或被排除，未逐一列出。
                        </p>
                      )}
                    </div>
                  )}
                </section>
              );
            })
          )}

          <section className="rounded-lg border border-divider px-5 py-4 text-sm text-default-600">
            <h2 className="font-medium text-foreground">使用前需要知道</h2>
            <ul className="mt-2 list-disc space-y-1.5 pl-5">
              <li>
                出口来自 VPN Gate
                志愿者的家庭宽带，可选国家由志愿者决定，目前以日本、韩国为主。
              </li>
              <li>
                IP 归属是家庭宽带，但出口列表是公开的，一部分地址已被 IP
                库标记为代理，对代理敏感的网站仍可能识别出来。
              </li>
              <li>
                出口由多人共用，速度不稳定；更换出口时会断开几秒，出口 IP
                也会变。
              </li>
              <li>
                出口不可用时代理会断开，不会改用节点自身
                IP。志愿者能看到未加密的流量并会保留连接记录，请不要用它传输明文的敏感内容。
              </li>
            </ul>
          </section>
        </>
      )}

      <ExitCandidateModal
        target={candidateTarget}
        onChanged={load}
        onClose={() => setCandidateTarget(null)}
      />
      <ExitEventsModal
        target={eventsTarget}
        onClose={() => setEventsTarget(null)}
      />
    </div>
  );
}
