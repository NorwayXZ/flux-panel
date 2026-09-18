import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
  CheckCircle2,
  ChevronDown,
  History,
  Info,
  Pencil,
  Plus,
  RefreshCw,
  Route,
  ScanSearch,
  Trash2,
  TriangleAlert,
  Waypoints,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  checkSmartEntry,
  diagnoseSmartEntryDns,
  deleteSmartEntry,
  getSmartEntryEvents,
  getSmartEntryDomains,
  getSmartEntryOptions,
  getSmartEntryOverview,
  saveSmartEntry,
  type SmartEntryDnsDiagnosis,
  type SmartEntryEvent,
  type SmartEntryForwardOption,
  type SmartEntryGroup,
  type SmartEntryProviderOption,
} from "@/api";
import { groupForwardOptionsByPort } from "@/utils/forward-option-groups";

type Carrier = "default" | "telecom" | "unicom" | "mobile";
type RouteForm = Record<Carrier, string>;

const carriers: {
  key: Carrier;
  label: string;
  note: string;
  color: "default" | "primary" | "secondary" | "success";
}[] = [
  {
    key: "default",
    label: "默认入口",
    note: "无法识别或没有专线记录时使用",
    color: "default",
  },
  {
    key: "telecom",
    label: "电信入口",
    note: "电信线路 DNS 查询优先返回",
    color: "primary",
  },
  {
    key: "unicom",
    label: "联通入口",
    note: "联通线路 DNS 查询优先返回",
    color: "secondary",
  },
  {
    key: "mobile",
    label: "移动入口",
    note: "移动线路 DNS 查询优先返回",
    color: "success",
  },
];

const blankRoutes = (): RouteForm => ({
  default: "",
  telecom: "",
  unicom: "",
  mobile: "",
});
const emptyForm = {
  id: undefined as number | undefined,
  name: "",
  providerRefId: "",
  zoneName: "",
  domain: "",
  recordType: "A" as "A" | "AAAA",
  ttl: "60",
  probeIntervalMs: "5000",
  connectTimeoutMs: "1500",
  failureThreshold: "2",
  recoveryThreshold: "3",
  enabled: true,
  routes: blankRoutes(),
};
const emptySummary = {
  total: 0,
  enabled: 0,
  healthy: 0,
  degraded: 0,
  lineRecords: 0,
};
const truthy = (value: boolean | number) => value === true || value === 1;
const timeText = (value?: number) =>
  value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "尚未检测";
const carrierLabel = (value?: string) =>
  carriers.find((item) => item.key === value)?.label || "线路";
const carrierTone = (value?: string) =>
  ({
    default: {
      dot: "bg-default-400",
      border: "border-default-200 dark:border-default-700",
      background: "bg-default-50/70 dark:bg-default-900/20",
      text: "text-default-700 dark:text-default-200",
    },
    telecom: {
      dot: "bg-primary",
      border: "border-primary-200 dark:border-primary-500/30",
      background: "bg-primary-50/50 dark:bg-primary-500/10",
      text: "text-primary-700 dark:text-primary-300",
    },
    unicom: {
      dot: "bg-secondary",
      border: "border-secondary-200 dark:border-secondary-500/30",
      background: "bg-secondary-50/50 dark:bg-secondary-500/10",
      text: "text-secondary-700 dark:text-secondary-300",
    },
    mobile: {
      dot: "bg-success",
      border: "border-success-200 dark:border-success-500/30",
      background: "bg-success-50/50 dark:bg-success-500/10",
      text: "text-success-700 dark:text-success-300",
    },
  })[value as Carrier] || {
    dot: "bg-default-400",
    border: "border-default-200 dark:border-default-700",
    background: "bg-default-50/70 dark:bg-default-900/20",
    text: "text-default-700 dark:text-default-200",
  };
const activityCarriers = (value: string) =>
  value.split(",").filter(Boolean).map(carrierLabel).join(" / ");
const formatBytes = (value = 0) => {
  if (value <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(
    Math.floor(Math.log(value) / Math.log(1024)),
    units.length - 1,
  );
  const amount = value / 1024 ** index;

  return `${amount >= 100 || index === 0 ? amount.toFixed(0) : amount.toFixed(2)} ${units[index]}`;
};
const supportsConnectionTelemetry = (version?: string) => {
  const values = String(version || "")
    .replace(/^v/, "")
    .split(".")
    .map((part) => Number.parseInt(part, 10) || 0);

  return (
    (values[0] || 0) > 2 ||
    ((values[0] || 0) === 2 &&
      ((values[1] || 0) > 23 ||
        ((values[1] || 0) === 23 && (values[2] || 0) >= 0)))
  );
};
const eventLabel = (event: SmartEntryEvent) =>
  ({
    route_switch: "入口切换",
    first_active: "首次活跃",
    resumed: "重新活跃",
    new_connections: "新连接摘要",
  })[event.eventType] || "入口活动";
const providerLabel = (value: string) =>
  value === "dnspod" ? "DNSPod" : "阿里云 DNS";
const stateMeta = (state: SmartEntryGroup["state"]) =>
  ({
    healthy: { label: "线路正常", color: "success" as const },
    degraded: { label: "部分回退", color: "warning" as const },
    offline: { label: "入口中断", color: "danger" as const },
    error: { label: "DNS 异常", color: "danger" as const },
    unknown: { label: "等待检测", color: "default" as const },
  })[state] || { label: "等待检测", color: "default" as const };
const activityChipMeta = (state?: string) =>
  ({
    connected: { label: "TCP 活跃", color: "success" as const },
    active_without_tcp_current: { label: "有流量", color: "warning" as const },
    idle: { label: "空闲", color: "default" as const },
    stale: { label: "上报中断", color: "warning" as const },
    waiting: { label: "等待上报", color: "default" as const },
  })[state || "waiting"] || { label: "等待上报", color: "default" as const };

export default function SmartEntryPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [groups, setGroups] = useState<SmartEntryGroup[]>([]);
  const [providers, setProviders] = useState<SmartEntryProviderOption[]>([]);
  const [domains, setDomains] = useState<string[]>([]);
  const [domainsLoading, setDomainsLoading] = useState(false);
  const [domainsError, setDomainsError] = useState("");
  const domainRequest = useRef(0);
  const [forwards, setForwards] = useState<SmartEntryForwardOption[]>([]);
  const [summary, setSummary] = useState(emptySummary);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [checkingId, setCheckingId] = useState<number>();
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyName, setHistoryName] = useState("");
  const [events, setEvents] = useState<SmartEntryEvent[]>([]);
  const [diagnosisOpen, setDiagnosisOpen] = useState(false);
  const [diagnosisName, setDiagnosisName] = useState("");
  const [diagnosis, setDiagnosis] = useState<SmartEntryDnsDiagnosis | null>(
    null,
  );
  const [diagnosingId, setDiagnosingId] = useState<number>();

  const loadData = useCallback(async (quiet = false) => {
    const [overview, options] = await Promise.all([
      getSmartEntryOverview(),
      getSmartEntryOptions(),
    ]);

    if (overview.code === 0) {
      setGroups(overview.data?.groups || []);
      setSummary(overview.data?.summary || emptySummary);
    } else if (!quiet) toast.error(overview.msg || "加载三网优化失败");
    if (options.code === 0) {
      setProviders(options.data?.providers || []);
      setForwards(options.data?.forwards || []);
    } else if (!quiet) toast.error(options.msg || "加载入口选项失败");
    if (!quiet) setLoading(false);
  }, []);

  useEffect(() => {
    void loadData();
    const timer = window.setInterval(() => void loadData(true), 5000);

    return () => window.clearInterval(timer);
  }, [loadData]);

  const loadDomains = useCallback(
    async (providerRefId: string, preferredZone = "") => {
      const requestId = ++domainRequest.current;

      if (!providerRefId) {
        setDomains([]);
        setDomainsError("");
        setDomainsLoading(false);

        return;
      }
      setDomainsLoading(true);
      setDomainsError("");
      const response = await getSmartEntryDomains(Number(providerRefId));

      if (requestId !== domainRequest.current) return;
      setDomainsLoading(false);
      if (response.code !== 0) {
        setDomains([]);
        setDomainsError(response.msg || "读取主域名失败");

        return;
      }
      const available = Array.from(
        new Set(
          (response.data?.domains || []).map((item) => item.toLowerCase()),
        ),
      ).sort();
      const normalizedPreferred = preferredZone.trim().toLowerCase();

      if (normalizedPreferred && !available.includes(normalizedPreferred))
        available.unshift(normalizedPreferred);
      setDomains(available);
      setForm((current) =>
        current.providerRefId !== providerRefId
          ? current
          : {
              ...current,
              zoneName:
                normalizedPreferred ||
                (available.includes(current.zoneName)
                  ? current.zoneName
                  : available.length === 1
                    ? available[0]
                    : ""),
            },
      );
    },
    [],
  );

  const selected = useMemo(
    () =>
      Object.fromEntries(
        carriers.map((item) => [
          item.key,
          forwards.find(
            (option) => String(option.id) === form.routes[item.key],
          ),
        ]),
      ) as Record<Carrier, SmartEntryForwardOption | undefined>,
    [form.routes, forwards],
  );
  const forwardGroups = useMemo(
    () => groupForwardOptionsByPort(forwards),
    [forwards],
  );
  const selectionProblem = useMemo(() => {
    if (!selected.default) return "必须选择默认入口";
    const values = carriers
      .map((item) => selected[item.key])
      .filter(Boolean) as SmartEntryForwardOption[];

    if (new Set(values.map((item) => item.id)).size < 2)
      return "至少配置一条不同于默认入口的运营商线路";
    if (new Set(values.map((item) => item.inNodeId)).size < 2)
      return "三网优化至少需要两台不同的公网入口节点";
    if (new Set(values.map((item) => item.inPort)).size > 1)
      return "所有入口转发必须使用相同公网端口";

    return "";
  }, [selected]);
  const selectedPort = selected.default?.inPort;
  const probeIntervalMs = Math.max(2000, Number(form.probeIntervalMs) || 5000);
  const connectTimeoutMs = Math.max(300, Number(form.connectTimeoutMs) || 1500);
  const failureThreshold = Math.max(1, Number(form.failureThreshold) || 2);
  const recoveryThreshold = Math.max(1, Number(form.recoveryThreshold) || 3);
  const failureWindow = `${Math.ceil((probeIntervalMs * failureThreshold) / 1000)}–${Math.ceil((probeIntervalMs * failureThreshold + connectTimeoutMs + 2000) / 1000)} 秒`;
  const recoveryWindow = `${Math.ceil((probeIntervalMs * recoveryThreshold) / 1000)}–${Math.ceil((probeIntervalMs * recoveryThreshold + 2000) / 1000)} 秒`;

  const openCreate = () => {
    domainRequest.current++;
    setDomains([]);
    setDomainsError("");
    setDomainsLoading(false);
    setForm(emptyForm);
    setFormOpen(true);
  };

  const openEdit = (group: SmartEntryGroup) => {
    const routes = blankRoutes();

    group.routes.forEach((route) => {
      routes[route.carrier] = String(route.forwardId);
    });
    setForm({
      id: group.id,
      name: group.name,
      providerRefId: String(group.providerRefId),
      zoneName: group.zoneName,
      domain: group.domain,
      recordType: group.recordType,
      ttl: String(group.ttl),
      probeIntervalMs: String(group.probeIntervalMs),
      connectTimeoutMs: String(group.connectTimeoutMs),
      failureThreshold: String(group.failureThreshold),
      recoveryThreshold: String(group.recoveryThreshold),
      enabled: truthy(group.enabled),
      routes,
    });
    setFormOpen(true);
    void loadDomains(String(group.providerRefId), group.zoneName);
  };

  const selectProvider = (providerRefId: string) => {
    const provider = providers.find(
      (item) => String(item.id) === providerRefId,
    );
    const minimumTtl = provider?.provider === "aliyun" ? "600" : "60";

    setForm((current) => ({
      ...current,
      providerRefId,
      zoneName: "",
      ttl: Number(current.ttl) < Number(minimumTtl) ? minimumTtl : current.ttl,
    }));
    setDomains([]);
    setDomainsError("");
    void loadDomains(providerRefId);
  };

  const submit = async () => {
    if (
      !form.name.trim() ||
      !form.providerRefId ||
      !form.zoneName.trim() ||
      !form.domain.trim()
    )
      return toast.error("请填写名称、DNS 配置和业务域名");
    if (selectionProblem) return toast.error(selectionProblem);
    setSaving(true);
    const response = await saveSmartEntry({
      ...form,
      providerRefId: Number(form.providerRefId),
      ttl: Number(form.ttl),
      probeIntervalMs: Number(form.probeIntervalMs),
      connectTimeoutMs: Number(form.connectTimeoutMs),
      failureThreshold: Number(form.failureThreshold),
      recoveryThreshold: Number(form.recoveryThreshold),
      routes: carriers
        .filter((item) => form.routes[item.key])
        .map((item) => ({
          carrier: item.key,
          forwardId: Number(form.routes[item.key]),
        })),
    });

    setSaving(false);
    if (response.code !== 0)
      return toast.error(response.msg || "保存三网优化失败");
    toast.success(
      form.id ? "三网优化已更新" : "三网优化已创建，运营商 DNS 已同步",
    );
    setFormOpen(false);
    void loadData();
  };

  const checkNow = async (id: number) => {
    setCheckingId(id);
    const response = await checkSmartEntry(id);

    setCheckingId(undefined);
    if (response.code !== 0) return toast.error(response.msg || "检测失败");
    toast.success("入口线路检测完成");
    void loadData(true);
  };

  const remove = async (group: SmartEntryGroup) => {
    if (
      !window.confirm(
        `确认删除“${group.name}”吗？面板会同时删除它创建的运营商线路记录，现有转发不会删除。`,
      )
    )
      return;
    const response = await deleteSmartEntry(group.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("三网优化已删除");
    void loadData();
  };

  const showHistory = async (group: SmartEntryGroup) => {
    const response = await getSmartEntryEvents(group.id);

    if (response.code !== 0)
      return toast.error(response.msg || "加载入口活动失败");
    setHistoryName(group.name);
    setEvents(response.data || []);
    setHistoryOpen(true);
  };

  const showDiagnosis = async (group: SmartEntryGroup) => {
    setDiagnosingId(group.id);
    const response = await diagnoseSmartEntryDns(group.id);

    setDiagnosingId(undefined);
    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "DNS 线路诊断失败");
    setDiagnosisName(group.name);
    setDiagnosis(response.data);
    setDiagnosisOpen(true);
  };

  if (loading)
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <Spinner label="加载三网优化" />
      </div>
    );

  return (
    <div className="mx-auto w-full max-w-[1600px] space-y-6 p-4 sm:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">运营商线路入口</p>
          <h1 className="mt-1 text-2xl font-semibold">三网优化</h1>
        </div>
        <Button
          color="primary"
          startContent={<Plus size={18} />}
          onPress={openCreate}
        >
          新建三网优化
        </Button>
      </header>

      <section aria-label="三网优化概况" className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <div className="flex items-center gap-2">
              <Waypoints className="text-primary" size={17} />
              <h2 className="text-sm font-semibold">运行概况</h2>
            </div>
            <p className="mt-1 text-xs text-default-500">
              按运营商把访问者分配到最合适的公网入口
            </p>
          </div>
          <span className="text-xs text-default-500">
            {summary.total} 个策略 · {summary.lineRecords} 条线路
          </span>
        </div>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {[
            [
              "运行策略",
              summary.enabled,
              <Waypoints key="enabled" className="h-4 w-4 text-primary" />,
              "text-primary",
            ],
            [
              "线路正常",
              summary.healthy,
              <CheckCircle2 key="healthy" className="h-4 w-4 text-success" />,
              "text-success",
            ],
            [
              "需要处理",
              summary.degraded,
              <TriangleAlert
                key="degraded"
                className={`h-4 w-4 ${summary.degraded ? "text-warning" : "text-default-400"}`}
              />,
              summary.degraded ? "text-warning" : "text-default-500",
            ],
            [
              "线路记录",
              summary.lineRecords,
              <Route key="records" className="h-4 w-4 text-secondary" />,
              "text-secondary",
            ],
          ].map(([label, value, icon, valueClass]) => (
            <div
              key={String(label)}
              className="border border-divider bg-content1 px-3 py-3 sm:px-4"
            >
              <div className="flex items-center justify-between gap-2">
                <p className="text-xs text-default-500">{label}</p>
                {icon}
              </div>
              <p className={`mt-2 text-xl font-semibold ${valueClass}`}>
                {value}
              </p>
            </div>
          ))}
        </div>
      </section>

      <section
        aria-label="三网优化工作方式"
        className="border border-primary-200 bg-primary-50/50 px-4 py-4 dark:border-primary-500/20 dark:bg-primary-500/5 sm:px-5"
      >
        <div className="flex items-start gap-3">
          <Info className="mt-0.5 shrink-0 text-primary" size={18} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-sm font-semibold">工作方式</h2>
              <Chip color="primary" size="sm" variant="flat">
                DNS 选路
              </Chip>
            </div>
            <p className="mt-1 text-xs leading-5 text-default-600 dark:text-default-300">
              同一域名按访问者的运营商返回对应入口，切换只影响新连接，已建立的
              TCP 连接不会被强制中断。
            </p>
            <div className="mt-3 grid gap-2 text-xs text-default-500 sm:grid-cols-3">
              <span>支持 DNSPod、阿里云 DNS</span>
              <span>不读取或保存客户 IP</span>
              <span>Cloudflare 不支持运营商线路解析</span>
            </div>
          </div>
        </div>
      </section>

      <section aria-label="三网优化运行规则" className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <div className="flex items-center gap-2">
              <Route className="text-secondary" size={17} />
              <h2 className="text-sm font-semibold">调度流程</h2>
            </div>
            <p className="mt-1 text-xs text-default-500">
              运营商选路与故障回退是两套独立规则
            </p>
          </div>
          <span className="text-xs text-default-500">自动检测 · 自动回退</span>
        </div>
        <div className="grid border-y border-divider sm:grid-cols-2 xl:grid-cols-4">
          {[
            [
              "01",
              "解析时选路",
              "用户重新解析域名时，权威 DNS 按递归 DNS 来源返回运营商入口。",
            ],
            [
              "02",
              "连接保持",
              "更换宽带后，已有连接继续使用原入口；缓存过期后新连接才会更新。",
            ],
            [
              "03",
              "故障回退",
              "Agent 与公网端口连续检测失败后，自动修改对应运营商 DNS 记录。",
            ],
            [
              "04",
              "恢复切回",
              "入口连续恢复后回到原运营商线路，DNS 仍需等待 TTL 和缓存刷新。",
            ],
          ].map(([step, title, detail], index) => (
            <div
              key={step}
              className={`relative border-divider px-4 py-4 ${index < 3 ? "border-b xl:border-b-0" : ""} ${index % 2 === 0 ? "sm:border-r" : ""} ${index > 0 ? "xl:border-l" : ""}`}
            >
              <span className="text-xs font-semibold text-primary">{step}</span>
              <h3 className="mt-2 text-sm font-medium">{title}</h3>
              <p className="mt-2 text-xs leading-5 text-default-500">
                {detail}
              </p>
            </div>
          ))}
        </div>
        <div className="flex items-start gap-2 text-xs leading-5 text-warning">
          <TriangleAlert className="mt-0.5 shrink-0" size={15} />
          <span>
            健康检测只判断入口在线和 TCP 建连，不根据
            P95、抖动、丢包或单个用户的实际访问质量自动切换。
          </span>
        </div>
      </section>

      <section aria-label="三网优化策略" className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <div className="flex items-center gap-2">
              <Waypoints className="text-primary" size={17} />
              <h2 className="text-sm font-semibold">优化策略</h2>
            </div>
            <p className="mt-1 text-xs text-default-500">
              每个策略对应一个业务域名和一组运营商入口
            </p>
          </div>
          <Chip size="sm" variant="flat">
            {groups.length} 个策略
          </Chip>
        </div>

        {groups.length === 0 ? (
          <div className="flex min-h-52 flex-col items-center justify-center gap-3 border-y border-divider text-center text-default-500">
            <Waypoints className="h-9 w-9" />
            <p>暂无三网优化策略</p>
          </div>
        ) : (
          <div className="grid gap-4 xl:grid-cols-2">
            {groups.map((group) => {
              const meta = truthy(group.enabled)
                ? stateMeta(group.state)
                : { label: "已停用", color: "default" as const };

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
                          <Chip size="sm" variant="flat">
                            {providerLabel(group.provider)}
                          </Chip>
                        </div>
                        <p className="mt-1 truncate text-sm text-default-500">
                          {group.domain}:{group.publicPort}
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
                          aria-label="DNS 线路诊断"
                          isLoading={diagnosingId === group.id}
                          size="sm"
                          title="DNS 线路诊断"
                          variant="light"
                          onPress={() => void showDiagnosis(group)}
                        >
                          <ScanSearch size={17} />
                        </Button>
                        <Button
                          isIconOnly
                          aria-label="入口活动记录"
                          size="sm"
                          title="入口活动记录"
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
                    <div className="flex items-center justify-between gap-3 border-t border-divider pt-3">
                      <div>
                        <h3 className="text-sm font-semibold">运营商入口</h3>
                        <p className="mt-1 text-xs text-default-500">
                          DNS 按运营商把新连接分配到对应线路
                        </p>
                      </div>
                      <Chip size="sm" variant="flat">
                        {group.routes.length}/4 条线路
                      </Chip>
                    </div>
                    <div className="grid gap-2 sm:grid-cols-2">
                      {group.routes.map((route) => {
                        const activeOnOwnEntry =
                          route.currentForwardId === route.forwardId;
                        const tone = carrierTone(route.carrier);

                        return (
                          <div
                            key={route.id}
                            className={`min-h-24 rounded-md border px-3 py-3 ${route.status === "unhealthy" ? "border-warning-300 bg-warning-50/60 dark:border-warning-500/40 dark:bg-warning-500/5" : `${tone.border} ${tone.background}`}`}
                          >
                            <div className="flex flex-wrap items-center justify-between gap-2">
                              <div className="flex items-center gap-2">
                                <span
                                  className={`h-2 w-2 rounded-full ${tone.dot}`}
                                />
                                <span
                                  className={`text-sm font-medium ${tone.text}`}
                                >
                                  {carrierLabel(route.carrier)}
                                </span>
                              </div>
                              <div className="flex flex-wrap justify-end gap-1">
                                <Chip
                                  color={
                                    route.status === "healthy"
                                      ? "success"
                                      : route.status === "unhealthy"
                                        ? "warning"
                                        : "default"
                                  }
                                  size="sm"
                                  variant="flat"
                                >
                                  {route.status === "healthy"
                                    ? "可用"
                                    : route.status === "unhealthy"
                                      ? "已回退"
                                      : "确认中"}
                                </Chip>
                                <Chip
                                  color={
                                    route.dnsState === "healthy"
                                      ? "success"
                                      : route.dnsState === "error"
                                        ? "danger"
                                        : "warning"
                                  }
                                  size="sm"
                                  variant="flat"
                                >
                                  {route.dnsState === "healthy"
                                    ? `DNS 已核验 · ${route.appliedTtl || group.ttl}s`
                                    : route.dnsState === "error"
                                      ? "DNS 待处理"
                                      : "DNS 同步中"}
                                </Chip>
                              </div>
                            </div>
                            <p className="mt-3 truncate text-xs text-default-500">
                              {route.nodeName} · {route.entryAddress}
                            </p>
                            <div className="mt-2 flex items-center justify-between gap-2 text-xs">
                              <span
                                className={
                                  activeOnOwnEntry
                                    ? "font-medium text-foreground"
                                    : "font-medium text-warning-700 dark:text-warning-300"
                                }
                              >
                                {activeOnOwnEntry
                                  ? "当前使用此线路"
                                  : `当前回退至 ${route.currentAddress}`}
                              </span>
                              <span className="shrink-0 text-default-500">
                                {route.latencyMs
                                  ? `${route.latencyMs} ms`
                                  : "-"}
                              </span>
                            </div>
                            {route.dnsError && (
                              <p className="mt-2 text-xs text-danger">
                                {route.dnsError}
                              </p>
                            )}
                          </div>
                        );
                      })}
                    </div>
                    <details className="group border-t border-divider pt-3">
                      <summary className="flex cursor-pointer list-none items-center justify-between gap-3 [&::-webkit-details-marker]:hidden">
                        <div className="flex items-center gap-2">
                          <Activity className="text-primary" size={16} />
                          <h3 className="text-sm font-semibold">
                            实时入口状态
                          </h3>
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs text-default-500">
                            每 5 秒更新
                          </span>
                          <ChevronDown
                            className="text-default-500 transition-transform group-open:rotate-180"
                            size={16}
                          />
                        </div>
                      </summary>
                      <div className="mt-3 divide-y divide-divider border-y border-divider">
                        {(group.activities || []).map((activity) => {
                          const telemetryReady = truthy(
                            activity.telemetryReady,
                          );
                          const agentReady = supportsConnectionTelemetry(
                            activity.agentVersion,
                          );
                          const stale = Boolean(
                            activity.lastTelemetryAt &&
                            Date.now() - activity.lastTelemetryAt > 30_000,
                          );
                          const shared = activity.carriers.includes(",");
                          const activityMeta = activityChipMeta(
                            activity.activityState,
                          );

                          return (
                            <div
                              key={`${activity.forwardId}-${activity.entryNodeId}`}
                              className="grid min-w-0 gap-3 px-1 py-3 text-xs sm:grid-cols-2 2xl:grid-cols-[minmax(220px,1.3fr)_minmax(140px,0.9fr)_minmax(120px,0.75fr)_minmax(200px,1fr)] 2xl:items-center"
                            >
                              <div className="min-w-0">
                                <div className="flex flex-wrap items-center gap-2">
                                  <span className="shrink-0 whitespace-nowrap font-medium text-foreground">
                                    {activityCarriers(activity.carriers)}
                                  </span>
                                  {shared && (
                                    <Chip
                                      className="shrink-0"
                                      size="sm"
                                      variant="flat"
                                    >
                                      共用入口
                                    </Chip>
                                  )}
                                  {stale && (
                                    <Chip
                                      className="shrink-0"
                                      color="warning"
                                      size="sm"
                                      variant="flat"
                                    >
                                      上报中断
                                    </Chip>
                                  )}
                                  <Chip
                                    className="shrink-0"
                                    color={activityMeta.color}
                                    size="sm"
                                    variant="flat"
                                  >
                                    {activityMeta.label}
                                  </Chip>
                                </div>
                                <p className="mt-1 truncate text-default-500">
                                  {activity.nodeName} · {activity.entryAddress}
                                </p>
                              </div>
                              <div className="min-w-0">
                                <p className="text-default-500">TCP 当前连接</p>
                                <p className="mt-1 font-medium">
                                  {telemetryReady
                                    ? `${activity.currentConnections || 0} 个`
                                    : agentReady
                                      ? "等待业务"
                                      : "等待新版 Agent"}
                                </p>
                                <p className="mt-1 line-clamp-2 text-default-400">
                                  {activity.activityHint ||
                                    (telemetryReady
                                      ? "实时连接采样正常"
                                      : "等待 Agent 上报连接遥测")}
                                </p>
                              </div>
                              <div className="min-w-0">
                                <p className="text-default-500">累计新增</p>
                                <p className="mt-1 font-medium">
                                  {telemetryReady
                                    ? `${activity.totalConnections || 0} 个`
                                    : "-"}
                                </p>
                              </div>
                              <div className="min-w-0 2xl:text-right">
                                <p className="text-default-500">累计流量</p>
                                <p className="mt-1 font-medium">
                                  {formatBytes(
                                    (activity.inFlow || 0) +
                                      (activity.outFlow || 0),
                                  )}
                                </p>
                                <p className="mt-1 text-default-400">
                                  {activity.lastActivityAt
                                    ? timeText(activity.lastActivityAt)
                                    : `Agent ${activity.agentVersion || "未知"} · 等待业务`}
                                </p>
                                {activity.lastTelemetryAt && (
                                  <p className="mt-1 text-default-400">
                                    上报：{timeText(activity.lastTelemetryAt)}
                                  </p>
                                )}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    </details>
                    <div className="flex flex-wrap items-center justify-between gap-2 border-t border-divider pt-3 text-xs text-default-500">
                      <span>主域名：{group.zoneName}</span>
                      <span>最近检测：{timeText(group.lastCheckedAt)}</span>
                    </div>
                    {group.lastError && (
                      <p className="rounded-md bg-danger-50 px-3 py-2 text-xs text-danger dark:bg-danger-500/10">
                        {group.lastError}
                      </p>
                    )}
                  </CardBody>
                </Card>
              );
            })}
          </div>
        )}
      </section>

      <Modal
        isOpen={formOpen}
        scrollBehavior="inside"
        size="4xl"
        onOpenChange={setFormOpen}
      >
        <ModalContent>
          <ModalHeader>{form.id ? "编辑三网优化" : "新建三网优化"}</ModalHeader>
          <ModalBody className="gap-5">
            <section className="grid gap-3 sm:grid-cols-2">
              <Input
                label="策略名称"
                placeholder="例如：家庭宽带智能入口"
                value={form.name}
                onValueChange={(name) => setForm({ ...form, name })}
              />
              <Select
                label="DNS 服务商配置"
                placeholder="选择 DNSPod 或阿里云 DNS"
                selectedKeys={form.providerRefId ? [form.providerRefId] : []}
                onSelectionChange={(keys) =>
                  selectProvider(String(Array.from(keys)[0] || ""))
                }
              >
                {providers.map((provider) => (
                  <SelectItem
                    key={String(provider.id)}
                    textValue={`${providerLabel(provider.provider)} ${provider.name}`}
                  >
                    {providerLabel(provider.provider)} · {provider.name}
                  </SelectItem>
                ))}
              </Select>
              <div className="flex min-w-0 items-end gap-2">
                <Select
                  className="min-w-0 flex-1"
                  errorMessage={domainsError || undefined}
                  isDisabled={
                    !form.providerRefId ||
                    domainsLoading ||
                    Boolean(domainsError)
                  }
                  isInvalid={Boolean(domainsError)}
                  label="主域名"
                  placeholder={
                    domainsLoading
                      ? "正在读取主域名"
                      : form.providerRefId
                        ? "选择主域名"
                        : "先选择 DNS 服务商配置"
                  }
                  selectedKeys={form.zoneName ? [form.zoneName] : []}
                  onSelectionChange={(keys) =>
                    setForm({
                      ...form,
                      zoneName: String(Array.from(keys)[0] || ""),
                    })
                  }
                >
                  {domains.map((domain) => (
                    <SelectItem key={domain} textValue={domain}>
                      {domain}
                    </SelectItem>
                  ))}
                </Select>
                <Button
                  isIconOnly
                  aria-label="刷新主域名"
                  isDisabled={!form.providerRefId || domainsLoading}
                  isLoading={domainsLoading}
                  title="刷新主域名"
                  variant="flat"
                  onPress={() =>
                    void loadDomains(form.providerRefId, form.zoneName)
                  }
                >
                  <RefreshCw size={17} />
                </Button>
              </div>
              <Input
                label="业务域名或主机记录"
                placeholder="例如 access 或 access.example.com"
                value={form.domain}
                onValueChange={(domain) => setForm({ ...form, domain })}
              />
              <Select
                label="记录类型"
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
                description={
                  form.providerRefId &&
                  providers.find(
                    (item) => String(item.id) === form.providerRefId,
                  )?.provider === "aliyun"
                    ? "阿里云线路解析实际最低 600 秒"
                    : "DNSPod 线路解析最低 60 秒"
                }
                label="DNS TTL（秒）"
                max={86400}
                min={
                  form.providerRefId &&
                  providers.find(
                    (item) => String(item.id) === form.providerRefId,
                  )?.provider === "aliyun"
                    ? 600
                    : 60
                }
                type="number"
                value={form.ttl}
                onValueChange={(ttl) => setForm({ ...form, ttl })}
              />
            </section>

            {providers.length === 0 && (
              <div className="flex flex-col gap-3 border-y border-warning-200 bg-warning-50 px-3 py-3 text-sm text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200 sm:flex-row sm:items-center sm:justify-between">
                <span>尚未保存 DNSPod 或阿里云 DNS 凭据。</span>
                <Button
                  color="warning"
                  size="sm"
                  variant="flat"
                  onPress={() => {
                    setFormOpen(false);
                    navigate("/dns-settings?add=carrier");
                  }}
                >
                  前往资源中心添加
                </Button>
              </div>
            )}

            <section className="border-t border-divider pt-4">
              <div className="mb-3 flex items-end justify-between gap-3">
                <div>
                  <h3 className="text-sm font-semibold">运营商入口</h3>
                  <p className="mt-1 text-xs text-default-500">
                    留空的运营商会自动使用默认入口；所有已选转发必须使用同一公网端口。
                  </p>
                </div>
                <Chip size="sm" variant="flat">
                  端口 {selectedPort || "-"}
                </Chip>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                {carriers.map((carrier) => (
                  <div
                    key={carrier.key}
                    className="border-l-2 border-divider pl-3"
                  >
                    <Select
                      description={carrier.note}
                      label={carrier.label}
                      placeholder={
                        carrier.key === "default"
                          ? "必须选择"
                          : "留空使用默认入口"
                      }
                      selectedKeys={
                        form.routes[carrier.key]
                          ? [form.routes[carrier.key]]
                          : []
                      }
                      onSelectionChange={(keys) =>
                        setForm({
                          ...form,
                          routes: {
                            ...form.routes,
                            [carrier.key]: String(Array.from(keys)[0] || ""),
                          },
                        })
                      }
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
                  </div>
                ))}
              </div>
              {selectionProblem && (
                <p className="mt-3 text-xs text-warning">{selectionProblem}</p>
              )}
            </section>

            <section className="border-t border-divider pt-4">
              <div className="mb-3">
                <h3 className="text-sm font-semibold">入口健康检测</h3>
                <p className="mt-1 text-xs text-default-500">
                  连续失败后回退，连续恢复后回到对应运营商入口。
                </p>
              </div>
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <Input
                  label="探测间隔（毫秒）"
                  min={2000}
                  type="number"
                  value={form.probeIntervalMs}
                  onValueChange={(probeIntervalMs) =>
                    setForm({ ...form, probeIntervalMs })
                  }
                />
                <Input
                  label="连接超时（毫秒）"
                  min={300}
                  type="number"
                  value={form.connectTimeoutMs}
                  onValueChange={(connectTimeoutMs) =>
                    setForm({ ...form, connectTimeoutMs })
                  }
                />
                <Input
                  label="连续失败次数"
                  max={10}
                  min={1}
                  type="number"
                  value={form.failureThreshold}
                  onValueChange={(failureThreshold) =>
                    setForm({ ...form, failureThreshold })
                  }
                />
                <Input
                  label="恢复确认次数"
                  max={10}
                  min={1}
                  type="number"
                  value={form.recoveryThreshold}
                  onValueChange={(recoveryThreshold) =>
                    setForm({ ...form, recoveryThreshold })
                  }
                />
              </div>
              <div className="mt-3 grid gap-2 border-y border-divider py-3 text-xs sm:grid-cols-2">
                <div>
                  <span className="text-default-500">预计故障确认：</span>
                  <strong>{failureWindow}</strong>
                </div>
                <div>
                  <span className="text-default-500">预计恢复确认：</span>
                  <strong>{recoveryWindow}</strong>
                </div>
              </div>
              <div className="mt-4">
                <Switch
                  isSelected={form.enabled}
                  onValueChange={(enabled) => setForm({ ...form, enabled })}
                >
                  启用自动检测和线路回退
                </Switch>
              </div>
            </section>

            <div className="rounded-md bg-warning-50 px-3 py-3 text-xs leading-5 text-warning-700 dark:bg-warning-500/10 dark:text-warning-300">
              DNSPod 或阿里云 DNS 必须是该主域名当前实际使用的权威
              DNS。运营商识别由 DNS 服务商完成，不读取或保存客户 IP。DNS
              缓存可能延迟新连接切换，已有连接不会迁移；TTL 的实际下限由 DNS
              套餐决定。
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setFormOpen(false)}>
              取消
            </Button>
            <Button color="primary" isLoading={saving} onPress={submit}>
              保存并同步线路 DNS
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={diagnosisOpen}
        scrollBehavior="inside"
        size="4xl"
        onOpenChange={setDiagnosisOpen}
      >
        <ModalContent>
          <ModalHeader>{diagnosisName} · DNS 线路诊断</ModalHeader>
          <ModalBody className="gap-4">
            {!diagnosis ? (
              <div className="py-12 text-center text-sm text-default-500">
                暂无诊断结果
              </div>
            ) : (
              <>
                <div className="grid gap-3 border-y border-divider py-4 sm:grid-cols-4">
                  <div>
                    <p className="text-xs text-default-500">业务域名</p>
                    <p className="mt-1 break-all text-sm font-medium">
                      {diagnosis.domain}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-default-500">记录类型</p>
                    <p className="mt-1 text-sm font-medium">
                      {diagnosis.recordType}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-default-500">策略 TTL</p>
                    <p className="mt-1 text-sm font-medium">
                      {diagnosis.ttl} 秒
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-default-500">检查结果</p>
                    <p
                      className={`mt-1 text-sm font-medium ${diagnosis.summary.healthy ? "text-success" : "text-warning"}`}
                    >
                      {diagnosis.summary.healthy ? "线路一致" : "存在差异"}
                    </p>
                  </div>
                </div>
                <div className="divide-y divide-divider border-y border-divider">
                  {diagnosis.lines.map((line) => (
                    <div
                      key={line.carrier}
                      className="grid gap-3 px-1 py-4 sm:grid-cols-[110px_minmax(0,1fr)_minmax(0,1fr)_90px] sm:items-center"
                    >
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium">
                          {carrierLabel(line.carrier)}
                        </span>
                        {line.inherited && (
                          <Chip size="sm" variant="flat">
                            继承默认
                          </Chip>
                        )}
                      </div>
                      <div className="min-w-0">
                        <p className="text-xs text-default-500">预期入口</p>
                        <p className="mt-1 break-all font-mono text-xs">
                          {line.expectedAddress}
                        </p>
                        <p className="mt-2 text-xs text-default-500">
                          服务商实际记录
                        </p>
                        <p className="mt-1 break-all font-mono text-xs">
                          {line.providerRecords.length
                            ? line.providerRecords
                                .map(
                                  (record) =>
                                    `${record.value} / TTL ${record.ttl}s${record.enabled ? "" : " / 已停用"}`,
                                )
                                .join("；")
                            : "未找到"}
                        </p>
                        <Chip
                          className="mt-2"
                          color={line.providerMatch ? "success" : "danger"}
                          size="sm"
                          variant="flat"
                        >
                          {line.providerMatch
                            ? "服务商记录一致"
                            : line.providerRecords.length > 1
                              ? "服务商存在重复记录"
                              : "服务商记录不一致"}
                        </Chip>
                      </div>
                      <div className="min-w-0">
                        <p className="text-xs text-default-500">
                          公共 DNS 实际返回
                        </p>
                        <p className="mt-1 truncate font-mono text-xs">
                          {line.publicProbe.answers.length
                            ? line.publicProbe.answers.join(", ")
                            : line.publicProbe.error || "没有答案"}
                        </p>
                        <p className="mt-1 text-xs text-default-500">
                          TTL {line.publicProbe.ttl ?? "-"} 秒
                        </p>
                      </div>
                      <Chip
                        color={
                          !line.publicProbe.successful
                            ? "danger"
                            : line.publicMatch
                              ? "success"
                              : "warning"
                        }
                        size="sm"
                        variant="flat"
                      >
                        {!line.publicProbe.successful
                          ? "查询失败"
                          : line.publicMatch
                            ? "公网一致"
                            : "公网待收敛"}
                      </Chip>
                    </div>
                  ))}
                </div>
                {diagnosis.summary.queryFailures > 0 && (
                  <div className="rounded-md border border-danger-200 bg-danger-50 px-3 py-3 text-xs leading-5 text-danger-800 dark:border-danger-500/20 dark:bg-danger-500/10 dark:text-danger-200">
                    有 {diagnosis.summary.queryFailures} 条公共 DNS
                    查询失败。该结果表示诊断请求未完成，不代表线路配置错误；稍后重新诊断即可。
                  </div>
                )}
                {diagnosis.sibling.conflict && (
                  <div className="rounded-md border border-warning-200 bg-warning-50 px-3 py-3 text-xs leading-5 text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200">
                    检测到同名 {diagnosis.sibling.recordType}{" "}
                    记录，但它没有由另一条三网优化策略接管。启用 IPv6
                    的设备可能优先使用这条记录，从而绕过当前线路选择。
                  </div>
                )}
                {!diagnosis.sibling.conflict && diagnosis.sibling.visible && (
                  <div className="rounded-md border border-default-200 bg-default-50 px-3 py-3 text-xs leading-5 text-default-600 dark:border-default-700 dark:bg-default-900/30">
                    同名 {diagnosis.sibling.recordType}{" "}
                    记录已被另一条三网优化策略接管，当前诊断不会把它判定为冲突。
                  </div>
                )}
                <p className="text-xs text-default-500">
                  公网 DNS 检查使用带运营商 ECS 的查询模拟线路来源；它能验证权威
                  DNS 和公共解析链路，但无法清除手机、Shadowrocket
                  或运营商本地已有缓存。
                </p>
              </>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setDiagnosisOpen(false)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={historyOpen}
        scrollBehavior="inside"
        size="2xl"
        onOpenChange={setHistoryOpen}
      >
        <ModalContent>
          <ModalHeader>{historyName} · 入口活动记录</ModalHeader>
          <ModalBody>
            {events.length === 0 ? (
              <div className="py-12 text-center text-sm text-default-500">
                暂无入口活动
              </div>
            ) : (
              <div className="divide-y divide-divider">
                {events.map((event) => (
                  <div key={event.id} className="flex gap-3 py-3">
                    <div
                      className={`mt-1 h-2.5 w-2.5 flex-none rounded-full ${event.eventType === "route_switch" ? "bg-warning" : event.eventType === "resumed" ? "bg-success" : "bg-primary"}`}
                    />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <p className="text-sm font-medium">
                          {eventLabel(event)}
                        </p>
                        <span className="text-xs text-default-500">
                          {timeText(event.createdTime)}
                        </span>
                      </div>
                      <p className="mt-1 text-xs text-default-500">
                        {event.detail}
                      </p>
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
