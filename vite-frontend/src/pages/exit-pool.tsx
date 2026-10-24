import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import { Tab, Tabs } from "@heroui/tabs";
import {
  ArrowLeftRight,
  Ban,
  Globe2,
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
  saveExitPoolSettings,
  switchExit,
  type ExitGroup,
  type ExitMember,
  type ExitPoolOverview,
} from "@/api";
import ExitCandidateModal from "@/components/exit-candidate-modal";
import ExitCountryModal from "@/components/exit-country-modal";
import ExitEventsModal from "@/components/exit-events-modal";
import {
  exitMemberMeta,
  exitStatusMeta,
  exitSummary,
} from "@/utils/exit-display";
import { formatBytes, formatDateTime } from "@/utils/format";
import PageShell from "@/components/page-shell";
import PageHelp from "@/components/page-help";
import CustomExitsPanel from "@/components/custom-exits-panel";
import MysteriumPanel from "@/components/mysterium-panel";
import ProxyProvidersPanel from "@/components/proxy-providers-panel";
import RoutePresetsPanel from "@/components/route-presets-panel";
import RuleCatalogPanel from "@/components/rule-catalog-panel";
import RulesetsPanel from "@/components/rulesets-panel";
import { isAdmin as isAdminUser } from "@/utils/auth";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";

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

function ResidentialExits() {
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [overview, setOverview] = useState<ExitPoolOverview | null>(null);
  const [eventsTarget, setEventsTarget] = useState<{
    groupId: number;
    title: string;
  } | null>(null);

  const [lowRiskOn, setLowRiskOn] = useState(false);
  const [lowRiskLimit, setLowRiskLimit] = useState("50");
  const [savingRisk, setSavingRisk] = useState(false);

  useEffect(() => {
    if (!overview) return;
    setLowRiskOn(overview.maxFraudScore != null);
    if (overview.maxFraudScore != null)
      setLowRiskLimit(String(overview.maxFraudScore));
  }, [overview?.maxFraudScore]);

  const saveRisk = async (enabled: boolean) => {
    const limit = Number(lowRiskLimit);

    if (enabled && (!Number.isInteger(limit) || limit < 0 || limit > 100))
      return toast.error("欺诈分上限应为 0 到 100");
    if (
      enabled &&
      !window.confirm(
        `开启后只使用欺诈分不高于 ${limit} 的出口。正在使用但不符合的出口会立即被换掉，符合条件的出口很少时代理可能暂时断开。确认开启吗？`,
      )
    )
      return;
    setSavingRisk(true);
    const response = await saveExitPoolSettings(enabled ? limit : null);

    setSavingRisk(false);
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    toast.success(
      enabled ? `已开启：只用欺诈分 ≤ ${limit} 的出口` : "已关闭低风险限制",
    );
    void load();
  };

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
  const [countryTarget, setCountryTarget] = useState<{
    code: string;
    name: string;
  } | null>(null);

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
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-3xl text-sm text-default-500">
          家宽出口由系统自动连接、检测和更换，平时不需要操作。只有在某个出口表现不好时，才需要手动排除它。
        </p>
        <Button
          isLoading={refreshing}
          startContent={!refreshing && <RefreshCw size={16} />}
          variant="flat"
          onPress={refresh}
        >
          刷新候选列表
        </Button>
      </div>

      {loading ? (
        <div className="flex min-h-40 items-center justify-center">
          <Spinner />
        </div>
      ) : (
        <>
          {overview?.lastRefreshError && (
            <div className="rounded-lg border border-warning-200 bg-warning-50 px-4 py-3 text-sm text-warning-700 dark:border-warning-500/30 dark:bg-warning-500/10 dark:text-warning-300">
              VPN Gate 候选源刷新失败：{overview.lastRefreshError}
              。已连接的出口继续独立检测，新建和更换出口可能受影响。
              {overview.nextRefreshAt
                ? `下次自动重试：${formatDateTime(overview.nextRefreshAt)}。`
                : "系统会自动重试。"}
            </div>
          )}
          {overview?.lastLookupError && (
            <div className="rounded-lg border border-warning-200 bg-warning-50 px-4 py-3 text-sm text-warning-700 dark:border-warning-500/30 dark:bg-warning-500/10 dark:text-warning-300">
              {overview.lastLookupError}。已确认的出口继续使用，新出口将在归属确认后选用。
            </div>
          )}

          <section className="flex flex-col gap-3 rounded-lg border border-divider px-5 py-4 md:flex-row md:items-center md:justify-between">
            <div className="min-w-0">
              <div className="text-sm font-medium">只用低风险出口</div>
              <p className="mt-0.5 text-xs text-default-500">
                开启后，自动选择、自动切换和手动切换都只使用做过深度检测、欺诈分不高于上限的家宽出口。需要先在“IP
                质量检测”页面配置 IPQualityScore
                密钥；新连上的出口会自动检测一次，消耗一次额度。
              </p>
            </div>
            <div className="flex flex-none items-center gap-3">
              <Input
                aria-label="欺诈分上限"
                className="w-28"
                endContent={
                  <span className="text-xs text-default-400">分</span>
                }
                isDisabled={savingRisk}
                size="sm"
                type="number"
                value={lowRiskLimit}
                onValueChange={setLowRiskLimit}
              />
              {lowRiskOn && (
                <Button
                  isLoading={savingRisk}
                  size="sm"
                  variant="flat"
                  onPress={() => saveRisk(true)}
                >
                  保存上限
                </Button>
              )}
              <Switch
                isDisabled={savingRisk}
                isSelected={lowRiskOn}
                onValueChange={(value) => saveRisk(value)}
              />
            </div>
          </section>

          <section className="rounded-lg border border-divider">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-divider px-5 py-3">
              <h2 className="text-sm font-medium">
                {overview?.maxFraudScore != null
                  ? `各国可用的低风险家宽数量（欺诈分 ≤ ${overview.maxFraudScore}）`
                  : "各国当前可用的家宽候选数量"}
              </h2>
              <span className="text-xs text-default-500">
                {overview?.lastRefreshAt
                  ? `更新于 ${formatDateTime(overview.lastRefreshAt)}`
                  : "尚未读取"}
              </span>
            </div>
            {countries.length === 0 ? (
              <p className="px-5 py-6 text-sm text-default-500">
                {overview?.lastRefreshError
                  ? "候选源暂时不可用，目前没有新鲜候选；已连接出口的状态见下方。系统会自动重试，也可以点击“刷新候选列表”。"
                  : "还没有读取到候选。点击右上角“刷新候选列表”试一次。"}
              </p>
            ) : (
              <div className="grid grid-cols-2 gap-4 p-4 sm:grid-cols-3 lg:grid-cols-6">
                {countries.map((country) => (
                  <StatsCard
                    key={country.country}
                    color={country.available > 0 ? "success" : "default"}
                    hint={`${
                      overview?.maxFraudScore != null
                        ? `家宽 ${country.home ?? 0} · 共 ${country.total}`
                        : `列表共 ${country.total} 个`
                    } · 点击查看`}
                    icon={<Globe2 className="h-5 w-5" />}
                    title={country.name}
                    value={country.available}
                    onPress={() =>
                      setCountryTarget({
                        code: country.country,
                        name: country.name,
                      })
                    }
                  />
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
            <ResourceCardGrid>
              {groups.map((group) => {
                const meta = exitStatusMeta(group.status);
                const { shown, hidden } = visibleMembers(group.members);
                const listed =
                  group.members.length > 0 && membersCurrent(group);

                return (
                  <ResourceCard
                    key={group.id}
                    actions={[
                      {
                        label: "换出口",
                        icon: <Shuffle size={14} />,
                        color: "primary",
                        isDisabled: !group.standbyReady,
                        isLoading: switching === group.id,
                        onPress: () => change(group),
                      },
                      {
                        label: "指定",
                        icon: <MousePointerClick size={14} />,
                        color: "warning",
                        onPress: () =>
                          setCandidateTarget({
                            groupId: group.id,
                            title: `为 ${group.countryName}家宽 · ${group.nodeName} 指定出口`,
                          }),
                      },
                      {
                        label: "记录",
                        icon: <History size={14} />,
                        onPress: () =>
                          setEventsTarget({
                            groupId: group.id,
                            title: `${group.countryName}家宽 · ${group.nodeName} 的切换记录`,
                          }),
                      },
                    ]}
                    blocks={[
                      {
                        label: "当前出口",
                        status: { text: meta.label, color: meta.color },
                        danger: [
                          "no_exit",
                          "node_offline",
                          "agent_error",
                          "setup_failed",
                        ].includes(group.status),
                        rows: [{ value: exitSummary(group), mono: false }],
                      },
                    ]}
                    chips={[
                      {
                        text: `${group.proxyCount} 个代理在用`,
                        color: "primary",
                      },
                      ...(group.proxyTraffic
                        ? [
                            {
                              text: `↕${formatBytes(group.proxyTraffic)}`,
                              color: "success" as const,
                            },
                          ]
                        : []),
                    ]}
                    danger={[
                      "no_exit",
                      "node_offline",
                      "agent_error",
                      "setup_failed",
                    ].includes(group.status)}
                    facts={[
                      {
                        label: "今日切换",
                        value:
                          group.status === "ok"
                            ? `${group.switchesToday ?? 0} 次`
                            : "-",
                      },
                      {
                        label: "备用出口",
                        value: group.standbyReady
                          ? `${group.standbyReady} 个就绪`
                          : "未就绪",
                        tone: group.standbyReady ? "success" : "warning",
                      },
                    ]}
                    status={{ text: meta.label, color: meta.color }}
                    subtitles={[group.nodeName]}
                    title={`${group.countryName}家宽`}
                  >
                    <div className="rounded border border-default-200 bg-default-50 px-2 py-1.5 dark:border-default-300 dark:bg-default-100/50">
                      <div className="mb-1 text-xs font-medium text-default-600">
                        候选出口
                      </div>
                      {!listed ? (
                        <p className="text-xs text-default-500">
                          {group.detail || "正在挑选候选出口"}
                        </p>
                      ) : (
                        <div className="space-y-1">
                          {shown.map((member) => {
                            const memberMeta = exitMemberMeta(member);
                            const live =
                              member.state === "up" ||
                              member.state === "connecting";
                            const note = memberNote(member);

                            return (
                              <div
                                key={member.id}
                                className={`flex items-center gap-1.5 text-xs ${live ? "" : "text-default-400"}`}
                                title={note || undefined}
                              >
                                <Chip
                                  className="h-5 shrink-0 text-[10px]"
                                  color={memberMeta.color}
                                  size="sm"
                                  variant="flat"
                                >
                                  {memberMeta.label}
                                </Chip>
                                <span className="min-w-0 flex-1 truncate font-mono">
                                  {member.exitIp || member.host}
                                </span>
                                <span className="shrink-0 text-default-500">
                                  {member.state === "up" && member.latencyMs
                                    ? `${member.latencyMs} ms`
                                    : member.exitKind
                                      ? kindLabels[member.exitKind] ||
                                        member.exitKind
                                      : ""}
                                </span>
                                {member.state === "up" &&
                                  member.approved &&
                                  !member.active && (
                                    <Button
                                      isIconOnly
                                      aria-label={`切换到出口 ${member.exitIp || member.host}`}
                                      className="h-6 w-6 min-w-6"
                                      size="sm"
                                      title="切换到这个出口"
                                      variant="light"
                                      onPress={() => change(group, member)}
                                    >
                                      <ArrowLeftRight size={13} />
                                    </Button>
                                  )}
                                {live && (
                                  <Button
                                    isIconOnly
                                    aria-label={`排除出口 ${member.exitIp || member.host}`}
                                    className="h-6 w-6 min-w-6"
                                    size="sm"
                                    title="排除这个出口"
                                    variant="light"
                                    onPress={() => block(member)}
                                  >
                                    <Ban size={13} />
                                  </Button>
                                )}
                              </div>
                            );
                          })}
                          {hidden > 0 && (
                            <p className="text-[11px] text-default-500">
                              近一小时内另有 {hidden} 个候选连不上或被排除
                            </p>
                          )}
                        </div>
                      )}
                    </div>
                  </ResourceCard>
                );
              })}
            </ResourceCardGrid>
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

      <ExitCountryModal
        country={countryTarget}
        onChanged={load}
        onClose={() => setCountryTarget(null)}
      />
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

/** Exits a private proxy can use: residential exits (admins) and custom exits. */
export default function ExitManagementPage() {
  const admin = isAdminUser();
  const tabs = [
    ...(admin ? [{ key: "residential", title: "家宽出口" }] : []),
    ...(admin ? [{ key: "mysterium", title: "Mysterium 家宽" }] : []),
    ...(admin ? [{ key: "providers", title: "代理服务商" }] : []),
    { key: "custom", title: "自定义出口" },
    { key: "presets", title: "规则库" },
  ];
  const [tab, setTab] = useState(() => {
    try {
      const saved = localStorage.getItem("cloudnest.exit-tab");

      if (saved && tabs.some((item) => item.key === saved)) return saved;
    } catch {
      // Remembering the tab is optional.
    }

    return tabs[0].key;
  });

  return (
    <PageShell className="space-y-5" width="wide">
      <header>
        <div className="flex items-center gap-1.5">
          <h1 className="text-2xl font-bold text-foreground">出口管理</h1>
          <PageHelp>
            私人代理可以用的出口：各国家宽、你自己添加的节点和出口组，以及分流规则用到的规则集和内置分组
          </PageHelp>
        </div>
      </header>
      <Tabs
        aria-label="出口类型"
        selectedKey={tab}
        variant="underlined"
        onSelectionChange={(key) => {
          setTab(String(key));
          try {
            localStorage.setItem("cloudnest.exit-tab", String(key));
          } catch {
            // Remembering the tab is optional.
          }
        }}
      >
        {tabs.map((item) => (
          <Tab key={item.key} title={item.title} />
        ))}
      </Tabs>
      {tab === "residential" && admin ? (
        <ResidentialExits />
      ) : tab === "mysterium" && admin ? (
        <MysteriumPanel />
      ) : tab === "providers" && admin ? (
        <ProxyProvidersPanel />
      ) : tab === "presets" ? (
        <div className="space-y-8">
          <RuleCatalogPanel />
          <RulesetsPanel />
          <RoutePresetsPanel canRefresh={admin} />
        </div>
      ) : (
        <CustomExitsPanel />
      )}
    </PageShell>
  );
}
