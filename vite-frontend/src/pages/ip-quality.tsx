import { useCallback, useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Switch } from "@heroui/switch";
import {
  Activity,
  Clock3,
  ExternalLink,
  History,
  KeyRound,
  MapPin,
  Play,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  getIpQualityHistory,
  getIpQualityOverview,
  runIpQualityScan,
  saveIpQualityProviders,
  type IpQualityNode,
  type IpQualityOverview,
  type IpQualityScan,
} from "@/api";
import PageShell from "@/components/page-shell";
import PageHelp from "@/components/page-help";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";
import { formatDateTime } from "@/utils/format";

const initial: IpQualityOverview = {
  minimumAgentVersion: "2.46.0",
  nodes: [],
  providers: { ipqsConfigured: false, abuseipdbConfigured: false },
  summary: { total: 0, running: 0, tested: 0, highRisk: 0 },
};
const timeText = (value?: number) => formatDateTime(value, "尚未检测");
const stateText = (state?: string) =>
  state === "available"
    ? "可访问"
    : state === "restricted"
      ? "地区受限"
      : state === "unavailable"
        ? "不可达"
        : "无法确认";
const stateColor = (state?: string) =>
  state === "available"
    ? ("success" as const)
    : state === "restricted" || state === "unavailable"
      ? ("danger" as const)
      : ("default" as const);
const riskMeta = (scan: IpQualityScan) =>
  scan.riskScore == null
    ? { label: "暂无风险分", color: "default" as const }
    : scan.riskLevel === "high"
      ? { label: `${scan.riskScore} · 高风险`, color: "danger" as const }
      : scan.riskLevel === "medium"
        ? { label: `${scan.riskScore} · 中风险`, color: "warning" as const }
        : { label: `${scan.riskScore} · 低风险`, color: "success" as const };

export default function IpQualityPage() {
  const [data, setData] = useState(initial);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [detail, setDetail] = useState<IpQualityNode | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settings, setSettings] = useState({
    ipqsApiKey: "",
    abuseipdbApiKey: "",
    clearIpqs: false,
    clearAbuseipdb: false,
  });
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyNode, setHistoryNode] = useState<IpQualityNode | null>(null);
  const [history, setHistory] = useState<IpQualityScan[]>([]);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const response = await getIpQualityOverview();

    if (!quiet) setLoading(false);
    if (response.code === 0) setData(response.data);
    else if (!quiet) toast.error(response.msg || "加载 IP 质量检测失败");
  }, []);

  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (!document.hidden && data.summary.running > 0) void load(true);
    }, 2000);

    return () => window.clearInterval(timer);
  }, [data.summary.running, load]);

  const run = async (node: IpQualityNode) => {
    setBusy(`run-${node.id}`);
    const response = await runIpQualityScan(node.id);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "无法开始检测");
    setData(response.data);
    toast.success("检测已开始");
  };
  const saveSettings = async () => {
    setBusy("settings");
    const response = await saveIpQualityProviders(settings);

    setBusy("");
    if (response.code !== 0)
      return toast.error(response.msg || "保存情报源失败");
    setData(response.data);
    setSettingsOpen(false);
    setSettings({
      ipqsApiKey: "",
      abuseipdbApiKey: "",
      clearIpqs: false,
      clearAbuseipdb: false,
    });
    toast.success("情报源设置已保存");
  };
  const openHistory = async (node: IpQualityNode) => {
    setBusy(`history-${node.id}`);
    const response = await getIpQualityHistory(node.id);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "加载历史失败");
    setHistoryNode(node);
    setHistory(response.data.scans);
    setHistoryOpen(true);
  };

  return (
    <PageShell className="space-y-6" width="wide">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-1.5">
            <h1 className="text-2xl font-bold text-foreground">IP 质量检测</h1>
            <PageHelp>从节点真实出口检测公网 IP、风险情报、黑名单、服务访问、DNS 解析器和常见出站端口。</PageHelp>
          </div>
        </div>
        <div className="flex gap-2">
          <Button
            isIconOnly
            title="刷新"
            variant="flat"
            onPress={() => void load()}
          >
            <RefreshCw size={17} />
          </Button>
          <Button
            startContent={<KeyRound size={17} />}
            variant="flat"
            onPress={() => setSettingsOpen(true)}
          >
            情报源
          </Button>
        </div>
      </header>
      {(!data.providers.ipqsConfigured ||
        !data.providers.abuseipdbConfigured) && (
        <div className="flex flex-col gap-3 border-y border-warning/30 bg-warning/10 p-4 text-sm sm:flex-row sm:items-center sm:justify-between">
          <span>
            风险评分需要 IPQualityScore 或 AbuseIPDB API
            Key；未配置时仍可检测地区、黑名单、服务、DNS 和端口。
          </span>
          <Button
            color="warning"
            size="sm"
            variant="flat"
            onPress={() => setSettingsOpen(true)}
          >
            配置情报源
          </Button>
        </div>
      )}
      <section className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        <StatsCard
          color="primary"
          icon={<Activity size={20} />}
          title="服务器节点"
          value={loading ? "-" : data.summary.total}
        />
        <StatsCard
          color="success"
          icon={<ShieldCheck size={20} />}
          title="已检测"
          value={loading ? "-" : data.summary.tested}
        />
        <StatsCard
          color="warning"
          icon={<Clock3 size={20} />}
          title="检测中"
          value={loading ? "-" : data.summary.running}
        />
        <StatsCard
          color="danger"
          icon={<ShieldAlert size={20} />}
          title="高风险"
          value={loading ? "-" : data.summary.highRisk}
        />
      </section>
      <ResourceCardGrid>
        {data.nodes.map((node) => {
          const running = node.scanStatus === "running";
          const risk = riskMeta(node);
          const services = node.unlockResults || [];
          const ports = node.ports || [];
          const tested = Boolean(node.finishedAt);

          return (
            <ResourceCard
              key={node.id}
              actions={[
                {
                  label: running ? "检测中" : "检测",
                  icon: <Play size={14} />,
                  color: "primary",
                  isDisabled: node.status !== 1 || running,
                  isLoading: busy === `run-${node.id}` || running,
                  onPress: () => void run(node),
                },
                {
                  label: "历史",
                  icon: <History size={14} />,
                  isLoading: busy === `history-${node.id}`,
                  onPress: () => void openHistory(node),
                },
                {
                  label: "详情",
                  icon: <ExternalLink size={14} />,
                  isDisabled: !tested,
                  onPress: () => setDetail(node),
                },
              ]}
              blocks={
                tested
                  ? [
                      {
                        label: "出口地址",
                        status: { text: risk.label, color: risk.color },
                        rows: [
                          ...(node.publicIpv4
                            ? [{ label: "IPv4", value: node.publicIpv4 }]
                            : []),
                          ...(node.publicIpv6
                            ? [
                                {
                                  label: "IPv6",
                                  value: node.publicIpv6,
                                  title: node.publicIpv6,
                                },
                              ]
                            : []),
                          ...(!node.publicIpv4 && !node.publicIpv6
                            ? [{ value: "-" }]
                            : []),
                        ],
                      },
                    ]
                  : []
              }
              chips={
                tested
                  ? [
                      {
                        text:
                          [node.country, node.region, node.city]
                            .filter(Boolean)
                            .join(" · ") || "未知地区",
                      },
                      ...(node.asn ? [{ text: node.asn }] : []),
                    ]
                  : []
              }
              danger={risk.color === "danger"}
              facts={
                tested
                  ? [
                      {
                        label: "服务可访问",
                        value: `${services.filter((item) => item.state === "available").length} / ${services.length}`,
                      },
                      {
                        label: "出站端口",
                        value: `${ports.filter((item) => item.reachable).length} / ${ports.length}`,
                      },
                      {
                        label: "黑名单命中",
                        value: (node.blacklist || []).filter(
                          (item) => item.listed,
                        ).length,
                        tone:
                          (node.blacklist || []).some((item) => item.listed)
                            ? "danger"
                            : "default",
                      },
                      { label: "检测时间", value: timeText(node.finishedAt) },
                    ]
                  : []
              }
              notice={
                tested
                  ? node.organization
                    ? {
                        icon: <MapPin size={14} />,
                        label: "网络",
                        value: (
                          <span title={node.organization}>
                            {node.organization}
                          </span>
                        ),
                      }
                    : undefined
                  : {
                      icon: <MapPin size={14} />,
                      label: running ? "检测中" : "状态",
                      value:
                        node.scanError ||
                        (running ? "正在从真实出口检测" : "尚未检测"),
                      tone: node.scanError ? "danger" : "default",
                    }
              }
              status={
                running
                  ? { text: "检测中", color: "primary" }
                  : node.status === 1
                    ? { text: "在线", color: "success" }
                    : { text: "离线", color: "default" }
              }
              subtitles={[
                `Agent ${node.version || "-"} · 要求 ${data.minimumAgentVersion}+`,
              ]}
              title={node.name}
            />
          );
        })}
      </ResourceCardGrid>

      <Modal isOpen={settingsOpen} size="2xl" onOpenChange={setSettingsOpen}>
        <ModalContent>
          <ModalHeader>风险情报源</ModalHeader>
          <ModalBody className="space-y-4">
            <Input
              label="IPQualityScore API Key"
              placeholder={
                data.providers.ipqsConfigured
                  ? "已保存，留空保持不变"
                  : "未配置"
              }
              type="password"
              value={settings.ipqsApiKey}
              onValueChange={(value) =>
                setSettings({
                  ...settings,
                  ipqsApiKey: value,
                  clearIpqs: false,
                })
              }
            />
            <Switch
              isSelected={settings.clearIpqs}
              onValueChange={(value) =>
                setSettings({ ...settings, clearIpqs: value, ipqsApiKey: "" })
              }
            >
              删除已保存的 IPQualityScore Key
            </Switch>
            <Input
              label="AbuseIPDB API Key"
              placeholder={
                data.providers.abuseipdbConfigured
                  ? "已保存，留空保持不变"
                  : "未配置"
              }
              type="password"
              value={settings.abuseipdbApiKey}
              onValueChange={(value) =>
                setSettings({
                  ...settings,
                  abuseipdbApiKey: value,
                  clearAbuseipdb: false,
                })
              }
            />
            <Switch
              isSelected={settings.clearAbuseipdb}
              onValueChange={(value) =>
                setSettings({
                  ...settings,
                  clearAbuseipdb: value,
                  abuseipdbApiKey: "",
                })
              }
            >
              删除已保存的 AbuseIPDB Key
            </Switch>
            <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">
              密钥使用面板 JWT 密钥派生的 AES-GCM
              密钥加密保存，只由后端请求情报服务，不会下发到
              Agent。第三方评分按各自标准生成，页面会保留原始来源。
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setSettingsOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "settings"}
              onPress={() => void saveSettings()}
            >
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={Boolean(detail)}
        scrollBehavior="inside"
        size="5xl"
        onOpenChange={(open) => {
          if (!open) setDetail(null);
        }}
      >
        <ModalContent>
          <ModalHeader>{detail?.name} · 完整检测结果</ModalHeader>
          <ModalBody>{detail && <ScanDetail scan={detail} />}</ModalBody>
          <ModalFooter>
            <Button color="primary" onPress={() => setDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal
        isOpen={historyOpen}
        scrollBehavior="inside"
        size="4xl"
        onOpenChange={setHistoryOpen}
      >
        <ModalContent>
          <ModalHeader>{historyNode?.name} · 检测历史</ModalHeader>
          <ModalBody>
            {history.length === 0 ? (
              <div className="py-16 text-center text-default-400">
                暂无历史结果
              </div>
            ) : (
              <div className="divide-y divide-divider">
                {history.map((scan) => {
                  const risk = riskMeta(scan);

                  return (
                    <div
                      key={scan.scanId}
                      className="grid gap-3 py-4 sm:grid-cols-[1fr_1fr_auto]"
                    >
                      <div>
                        <p className="font-mono text-sm">
                          {scan.publicIpv4 || scan.publicIpv6 || "-"}
                        </p>
                        <p className="mt-1 text-xs text-default-400">
                          {timeText(scan.finishedAt || scan.startedAt)}
                        </p>
                      </div>
                      <div className="text-sm">
                        {[scan.country, scan.region, scan.organization]
                          .filter(Boolean)
                          .join(" · ") ||
                          scan.scanError ||
                          "-"}
                      </div>
                      <Chip color={risk.color} size="sm" variant="flat">
                        {scan.scanStatus === "failed" ? "检测失败" : risk.label}
                      </Chip>
                    </div>
                  );
                })}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button color="primary" onPress={() => setHistoryOpen(false)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}

function ScanDetail({ scan }: { scan: IpQualityScan }) {
  const sources = Object.values(scan.riskSources || {});

  return (
    <div className="space-y-6">
      <section>
        <h3 className="font-medium">风险情报</h3>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          {sources.map((source) => (
            <div key={source.name} className="border-y border-divider py-3">
              <div className="flex items-center justify-between">
                <span>{source.name}</span>
                <Chip
                  color={
                    source.status === "success"
                      ? "success"
                      : source.status === "failed"
                        ? "danger"
                        : "default"
                  }
                  size="sm"
                  variant="flat"
                >
                  {source.status === "success"
                    ? `${source.score} 分`
                    : source.status === "not_configured"
                      ? "未配置"
                      : "查询失败"}
                </Chip>
              </div>
              <p className="mt-2 text-xs text-default-500">
                {source.error ||
                  [
                    source.proxy && "代理",
                    source.vpn && "VPN",
                    source.tor && "Tor",
                    source.recentAbuse && "近期滥用",
                    source.bot && "机器人",
                    source.totalReports != null &&
                      `${source.totalReports} 次举报`,
                  ]
                    .filter(Boolean)
                    .join(" · ") ||
                  "未发现额外风险标签"}
              </p>
            </div>
          ))}
        </div>
      </section>
      <section>
        <h3 className="font-medium">服务访问</h3>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full min-w-[650px] text-sm">
            <tbody>
              {(scan.unlockResults || []).map((item) => (
                <tr key={item.name} className="border-b border-divider">
                  <td className="p-3 font-medium">{item.name}</td>
                  <td className="p-3">
                    <Chip
                      color={stateColor(item.state)}
                      size="sm"
                      variant="flat"
                    >
                      {stateText(item.state)}
                    </Chip>
                  </td>
                  <td className="p-3">HTTP {item.httpStatus || "-"}</td>
                  <td className="p-3">
                    {Number(item.latencyMs || 0).toFixed(0)} ms
                  </td>
                  <td className="p-3 text-default-500">{item.detail || "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section>
        <h3 className="font-medium">DNS 与出站端口</h3>
        <div className="mt-3 grid gap-5 lg:grid-cols-2">
          <div className="border-y border-divider py-3 text-sm">
            <p className="text-xs text-default-500">系统配置的解析器</p>
            <p className="mt-1 break-all">
              {scan.dns?.configuredResolvers?.join(", ") || "-"}
            </p>
            <p className="mt-3 text-xs text-default-500">
              权威侧观察到的解析器出口
            </p>
            <p className="mt-1 break-all">
              {scan.dns?.observedResolvers?.join(", ") ||
                scan.dns?.error ||
                "-"}
            </p>
          </div>
          <div className="divide-y divide-divider border-y border-divider">
            {(scan.ports || []).map((item) => (
              <div
                key={`${item.host}:${item.port}`}
                className="flex items-center justify-between gap-3 py-2 text-sm"
              >
                <span>
                  {item.name} · {item.port}
                </span>
                <Chip
                  color={item.reachable ? "success" : "danger"}
                  size="sm"
                  variant="flat"
                >
                  {item.reachable
                    ? `${item.latencyMs.toFixed(0)} ms`
                    : "不可达"}
                </Chip>
              </div>
            ))}
          </div>
        </div>
      </section>
      <section>
        <div className="flex items-center gap-2">
          <h3 className="font-medium">公开黑名单</h3>
          <a
            className="text-default-400 hover:text-primary"
            href="https://check.spamhaus.org/"
            rel="noreferrer"
            target="_blank"
            title="打开 Spamhaus 查询"
          >
            <ExternalLink size={15} />
          </a>
        </div>
        <div className="mt-3 grid gap-2 sm:grid-cols-3">
          {(scan.blacklist || []).map((item) => (
            <div
              key={item.provider}
              className="flex items-center justify-between border-y border-divider py-3 text-sm"
            >
              <span>{item.provider}</span>
              <Chip
                color={
                  item.listed
                    ? "danger"
                    : item.status === "success"
                      ? "success"
                      : "default"
                }
                size="sm"
                variant="flat"
              >
                {item.listed
                  ? "命中"
                  : item.status === "success"
                    ? "未命中"
                    : "查询失败"}
              </Chip>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
