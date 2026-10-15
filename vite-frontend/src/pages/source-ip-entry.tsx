import { useCallback, useEffect, useMemo, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input, Textarea } from "@heroui/input";
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
  CheckCircle2,
  CircleAlert,
  FlaskConical,
  Info,
  MapPin,
  Pencil,
  Plus,
  RadioTower,
  RefreshCw,
  ShieldCheck,
  Trash2,
  X,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  checkSourceIpEntry,
  debugSourceIpEntry,
  deleteSourceIpEntry,
  getSourceIpEntryOverview,
  refreshSourceIpAsns,
  refreshSourceIpCarriers,
  saveSourceIpEntry,
  type SourceIpBackendForward,
  type SourceIpAsnDatabase,
  type SourceIpDebugResult,
  type SourceIpCarrierDatabase,
  type SourceIpEntryGroup,
  type SourceIpEntryOverview,
} from "@/api";
import { groupForwardOptionsByPort } from "@/utils/forward-option-groups";
import { formatDateTime, truthy } from "@/utils/format";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";
import PageShell from "@/components/page-shell";

type Carrier = "default" | "telecom" | "unicom" | "mobile" | "custom";
type RuleType =
  | "default"
  | "carrier"
  | "cidr"
  | "asn"
  | "region"
  | "vip"
  | "customer"
  | "gray"
  | "risk";
type QualityPolicy =
  "static" | "quality_aware" | "prefer_primary" | "quarantine";

interface FormRoute {
  carrier: Carrier;
  ruleType: RuleType;
  ruleName: string;
  priority: string;
  backendForwardId: string;
  cidrs: string;
  region: string;
  asn: string;
  tags: string;
  qualityPolicy: QualityPolicy;
  notes: string;
  enabled: boolean;
}

interface FormState {
  id?: number;
  name: string;
  ingressNodeId: string;
  listenHost: string;
  listenPort: string;
  enabled: boolean;
  routes: FormRoute[];
}

const carrierLabels: Record<Carrier, string> = {
  default: "默认回退",
  telecom: "中国电信",
  unicom: "中国联通",
  mobile: "中国移动",
  custom: "自定义来源",
};

const ruleTypeLabels: Record<RuleType, string> = {
  default: "默认回退",
  carrier: "运营商",
  cidr: "CIDR/IP 段",
  asn: "ASN",
  region: "地区",
  vip: "VIP 来源",
  customer: "专属客户",
  gray: "灰度测试",
  risk: "风险隔离",
};

const qualityPolicyLabels: Record<QualityPolicy, string> = {
  static: "固定规则",
  quality_aware: "质量联动",
  prefer_primary: "主线优先",
  quarantine: "隔离/降级",
};

const newRoute = (ruleType: RuleType = "cidr"): FormRoute => ({
  carrier:
    ruleType === "default"
      ? "default"
      : ruleType === "carrier"
        ? "telecom"
        : "custom",
  ruleType,
  ruleName: "",
  priority: ruleType === "default" ? "999" : "100",
  backendForwardId: "",
  cidrs: "",
  region: "",
  asn: "",
  tags: "",
  qualityPolicy: ruleType === "risk" ? "quarantine" : "static",
  notes: "",
  enabled: true,
});

const emptyForm = (): FormState => ({
  name: "",
  ingressNodeId: "",
  listenHost: "",
  listenPort: "",
  enabled: true,
  routes: [newRoute("default")],
});

const timeText = (value?: number) => formatDateTime(value, "尚未同步");

function statusMeta(state: SourceIpEntryGroup["state"]) {
  switch (state) {
    case "active":
      return { label: "运行正常", color: "success" as const };
    case "disabled":
      return { label: "已停用", color: "default" as const };
    case "error":
      return { label: "同步失败", color: "danger" as const };
    default:
      return { label: "配置中", color: "warning" as const };
  }
}

function backendText(forward?: SourceIpBackendForward) {
  if (!forward) return "未选择后端入口";

  return `${forward.nodeName || `节点 ${forward.inNodeId}`} · ${forward.entryHost || "-"}:${forward.inPort}`;
}

function carrierText(carrier: string) {
  return carrierLabels[carrier as Carrier] || carrier;
}

function ruleTypeText(ruleType?: string) {
  return ruleTypeLabels[(ruleType || "cidr") as RuleType] || ruleType || "规则";
}

function qualityPolicyText(policy?: string) {
  return (
    qualityPolicyLabels[(policy || "static") as QualityPolicy] ||
    policy ||
    "固定规则"
  );
}

function routeNeedsCidrs(route: FormRoute) {
  return !["default", "carrier", "asn"].includes(route.ruleType);
}

function routeTitle(route: FormRoute) {
  return (
    route.ruleName ||
    (route.ruleType === "carrier"
      ? carrierText(route.carrier)
      : route.ruleType === "asn" && route.asn.trim()
        ? normalizeAsnKey(route.asn)
        : ruleTypeText(route.ruleType))
  );
}

function normalizeAsnKey(value: string) {
  const text = value.trim().toUpperCase().replace(/^ASN/, "AS");

  return /^\d+$/.test(text) ? `AS${text}` : text;
}

export default function SourceIpEntryPage() {
  const [data, setData] = useState<SourceIpEntryOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [formOpen, setFormOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [carrierRefreshing, setCarrierRefreshing] = useState(false);
  const [asnRefreshing, setAsnRefreshing] = useState(false);
  const [checkingId, setCheckingId] = useState<number>();
  const [debugging, setDebugging] = useState(false);
  const [debugSourceIp, setDebugSourceIp] = useState("");
  const [debugGroupId, setDebugGroupId] = useState("");
  const [debugResult, setDebugResult] = useState<SourceIpDebugResult | null>(
    null,
  );
  const [form, setForm] = useState<FormState>(emptyForm);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const response = await getSourceIpEntryOverview();

    if (response.code === 0) setData(response.data);
    else if (!quiet) toast.error(response.msg || "加载来源 IP 分流失败");
    if (!quiet) setLoading(false);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const backendMap = useMemo(
    () =>
      new Map(
        (data?.backendForwards || []).map((item) => [String(item.id), item]),
      ),
    [data],
  );
  const backendGroups = useMemo(
    () => groupForwardOptionsByPort(data?.backendForwards || []),
    [data?.backendForwards],
  );
  const carrierMap = useMemo(
    () => new Map((data?.carriers || []).map((item) => [item.carrier, item])),
    [data],
  );
  const asnMap = useMemo(
    () => new Map((data?.asns || []).map((item) => [item.asn, item])),
    [data],
  );

  const openCreate = () => {
    setForm(emptyForm());
    setFormOpen(true);
  };

  const openEdit = (group: SourceIpEntryGroup) => {
    setForm({
      id: group.id,
      name: group.name,
      ingressNodeId: String(group.ingressNodeId),
      listenHost: group.listenHost || "",
      listenPort: String(group.listenPort),
      enabled: truthy(group.enabled),
      routes: group.routes.map((route) => ({
        carrier: route.carrier,
        ruleType: (route.ruleType ||
          (route.carrier === "default"
            ? "default"
            : route.carrier === "custom"
              ? "cidr"
              : "carrier")) as RuleType,
        ruleName: route.ruleName || "",
        priority: String(route.priority || 100),
        backendForwardId: String(route.backendForwardId),
        cidrs: route.ruleType === "carrier" ? "" : route.cidrs || "",
        region: route.region || "",
        asn: route.asn || "",
        tags: route.tags || "",
        qualityPolicy: (route.qualityPolicy || "static") as QualityPolicy,
        notes: route.notes || "",
        enabled: truthy(route.enabled),
      })),
    });
    setFormOpen(true);
  };

  const updateRoute = (index: number, changes: Partial<FormRoute>) => {
    setForm((current) => ({
      ...current,
      routes: current.routes.map((route, itemIndex) =>
        itemIndex === index ? { ...route, ...changes } : route,
      ),
    }));
  };

  const addRoute = () => {
    if (form.routes.length >= 20) return toast.error("一组最多配置 20 条规则");
    setForm((current) => ({
      ...current,
      routes: [...current.routes, newRoute("cidr")],
    }));
  };

  const removeRoute = (index: number) => {
    if (form.routes[index]?.carrier === "default")
      return toast.error("默认回退线路不能删除");
    setForm((current) => ({
      ...current,
      routes: current.routes.filter((_, itemIndex) => itemIndex !== index),
    }));
  };

  const submit = async () => {
    if (!form.name.trim()) return toast.error("请输入分流名称");
    if (!form.ingressNodeId) return toast.error("请选择统一入口节点");
    const port = Number(form.listenPort);

    if (!Number.isInteger(port) || port < 1 || port > 65535)
      return toast.error("监听端口必须在 1-65535 之间");
    if (
      form.routes.length === 0 ||
      !form.routes.some((route) => route.ruleType === "default")
    )
      return toast.error("必须配置一条默认回退线路");
    const systemCarriers = new Set<string>();

    for (const route of form.routes) {
      if (route.ruleType === "carrier") {
        if (systemCarriers.has(route.carrier))
          return toast.error(
            `运营商规则不能重复：${carrierText(route.carrier)}`,
          );
        systemCarriers.add(route.carrier);
      }
      if (route.ruleType === "asn" && !route.asn.trim())
        return toast.error("ASN 规则必须填写 ASN，例如 AS4134");
      if (!route.backendForwardId)
        return toast.error(`${routeTitle(route)} 未选择后端入口转发`);
      if (routeNeedsCidrs(route) && !route.cidrs.trim())
        return toast.error(`${routeTitle(route)} 必须填写 CIDR`);
      const priority = Number(route.priority || 100);

      if (!Number.isInteger(priority) || priority < 1 || priority > 999)
        return toast.error(`${routeTitle(route)} 优先级必须在 1-999 之间`);
    }
    setSubmitting(true);
    const response = await saveSourceIpEntry({
      id: form.id,
      name: form.name.trim(),
      ingressNodeId: Number(form.ingressNodeId),
      listenHost: form.listenHost.trim(),
      listenPort: port,
      enabled: form.enabled,
      routes: form.routes.map((route) => ({
        carrier:
          route.ruleType === "default"
            ? "default"
            : route.ruleType === "carrier"
              ? route.carrier
              : "custom",
        ruleType: route.ruleType,
        ruleName: route.ruleName.trim(),
        priority: Number(route.priority || 100),
        backendForwardId: Number(route.backendForwardId),
        cidrs:
          route.ruleType === "carrier" || route.ruleType === "asn"
            ? ""
            : route.cidrs,
        region: route.region.trim(),
        asn: route.asn.trim(),
        tags: route.tags.trim(),
        qualityPolicy: route.qualityPolicy,
        notes: route.notes.trim(),
        enabled: route.enabled,
      })),
    });

    setSubmitting(false);
    if (response.code !== 0)
      return toast.error(response.msg || "保存来源 IP 分流失败");
    toast.success("来源 IP 分流已同步到入口 Agent");
    setFormOpen(false);
    void load(true);
  };

  const remove = async (group: SourceIpEntryGroup) => {
    if (
      !window.confirm(
        `确认删除“${group.name}”吗？这会删除统一入口服务，不会删除后端转发。`,
      )
    )
      return;
    const response = await deleteSourceIpEntry(group.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("来源 IP 分流已删除");
    void load(true);
  };

  const check = async (id: number) => {
    setCheckingId(id);
    const response = await checkSourceIpEntry(id);

    setCheckingId(undefined);
    if (response.code !== 0)
      return toast.error(response.msg || "入口同步检查失败");
    toast.success("入口 Agent 配置已重新应用");
    void load(true);
  };

  const refreshCarriers = async () => {
    setCarrierRefreshing(true);
    const response = await refreshSourceIpCarriers();

    setCarrierRefreshing(false);
    if (response.code !== 0)
      return toast.error(response.msg || "运营商 IP 库刷新失败");
    toast.success("运营商 IP 库已刷新");
    void load(true);
  };

  const refreshAsns = async () => {
    setAsnRefreshing(true);
    const response = await refreshSourceIpAsns();

    setAsnRefreshing(false);
    if (response.code !== 0)
      return toast.error(response.msg || "ASN 前缀库刷新失败");
    toast.success("ASN 前缀库已刷新并同步规则");
    void load(true);
  };

  const runDebug = async () => {
    if (!debugSourceIp.trim()) return toast.error("请输入要调试的来源 IP");
    setDebugging(true);
    const response = await debugSourceIpEntry({
      sourceIp: debugSourceIp.trim(),
      groupId: debugGroupId ? Number(debugGroupId) : undefined,
    });

    setDebugging(false);
    if (response.code !== 0)
      return toast.error(response.msg || "来源 IP 调试失败");
    setDebugResult(response.data);
  };

  if (loading)
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <Spinner label="加载来源 IP 分流" />
      </div>
    );

  const groups = data?.groups || [];
  const nodes = data?.ingressNodes || [];

  return (
    <PageShell className="space-y-6" width="wide">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">不依赖 DNS 的入口规则引擎</p>
          <h1 className="mt-1 text-2xl font-semibold">来源 IP 分流</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-default-500">
            一个公网入口端口按客户端真实来源 IP
            选择运营商、地区、ASN、VIP、客户专线、灰度或风险隔离线路。它不依赖客户端
            DNS，也不会终止 VLESS、Reality、Trojan 等上层协议。
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            isLoading={carrierRefreshing}
            startContent={<RefreshCw size={17} />}
            variant="flat"
            onPress={refreshCarriers}
          >
            刷新运营商 IP 库
          </Button>
          <Button
            isLoading={asnRefreshing}
            startContent={<RefreshCw size={17} />}
            variant="flat"
            onPress={refreshAsns}
          >
            刷新 ASN 库
          </Button>
          <Button
            color="primary"
            startContent={<Plus size={18} />}
            onPress={openCreate}
          >
            新建来源 IP 分流
          </Button>
        </div>
      </header>

      <section className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatsCard
          color="primary"
          icon={<RadioTower className="h-5 w-5" />}
          title="分流组"
          value={data?.summary.total || 0}
        />
        <StatsCard
          color="secondary"
          icon={<RadioTower className="h-5 w-5" />}
          title="运行中"
          value={data?.summary.enabled || 0}
        />
        <StatsCard
          color="success"
          icon={<CheckCircle2 className="h-5 w-5" />}
          title="同步正常"
          value={data?.summary.healthy || 0}
        />
        <StatsCard
          color={data?.summary.errors ? "warning" : "default"}
          icon={<CircleAlert className="h-5 w-5" />}
          title="需要处理"
          value={data?.summary.errors || 0}
        />
      </section>

      <section aria-label="来源 IP 分流策略" className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <h2 className="text-sm font-semibold">来源 IP 分流策略</h2>
            <p className="mt-1 text-xs text-default-500">
              统一入口、匹配规则和后端线路在这里集中管理
            </p>
          </div>
          <Chip size="sm" variant="flat">
            {groups.length} 个分流组
          </Chip>
        </div>
        {groups.length === 0 ? (
          <div className="flex min-h-40 flex-col items-center justify-center gap-3 rounded-large bg-content1 text-center text-default-500 shadow-small">
            <RadioTower className="h-9 w-9" />
            <p>暂无来源 IP 分流</p>
            <Button
              size="sm"
              startContent={<Plus size={16} />}
              variant="flat"
              onPress={openCreate}
            >
              创建第一组
            </Button>
          </div>
        ) : (
          <ResourceCardGrid>
            {groups.map((group) => {
              const meta = statusMeta(group.state);
              const defaultRoute = group.routes.find(
                (route) => route.carrier === "default",
              );
              const ruleRoutes = group.routes.filter(
                (route) => route.carrier !== "default",
              );

              return (
                <ResourceCard
                  key={group.id}
                  actions={[
                    {
                      label: "编辑",
                      icon: <Pencil size={14} />,
                      color: "primary",
                      onPress: () => openEdit(group),
                    },
                    {
                      label: "同步",
                      icon: <RefreshCw size={14} />,
                      color: "warning",
                      onPress: () => check(group.id),
                      isLoading: checkingId === group.id,
                    },
                    {
                      label: "删除",
                      icon: <Trash2 size={14} />,
                      color: "danger",
                      onPress: () => remove(group),
                    },
                  ]}
                  blocks={[
                    {
                      label: "统一入口",
                      status: {
                        text: truthy(group.enabled) ? "TCP" : "已停用",
                        color: truthy(group.enabled) ? "primary" : "default",
                      },
                      rows: [
                        {
                          label: "入口",
                          value: `${group.listenHost || "全部地址"}:${group.listenPort}`,
                        },
                      ],
                    },
                    {
                      label: `分流规则 · ${ruleRoutes.length} 条`,
                      danger: group.state === "error",
                      rows:
                        ruleRoutes.length > 0
                          ? ruleRoutes.slice(0, 4).map((route) => {
                              const backend = backendText(
                                backendMap.get(String(route.backendForwardId)),
                              );
                              const name =
                                route.ruleName ||
                                (route.ruleType === "carrier"
                                  ? carrierText(route.carrier)
                                  : ruleTypeText(route.ruleType));

                              return {
                                label: name,
                                value: backend,
                                mono: false,
                                title: `${name} → ${backend}`,
                              };
                            })
                          : [{ value: "仅默认线路", mono: false }],
                    },
                  ]}
                  chips={[
                    ...Array.from(
                      new Set(
                        ruleRoutes.map((route) => ruleTypeText(route.ruleType)),
                      ),
                    ).map((text) => ({ text, color: "primary" as const })),
                    ...(ruleRoutes.length > 4
                      ? [{ text: `另有 ${ruleRoutes.length - 4} 条规则` }]
                      : []),
                  ]}
                  danger={group.state === "error"}
                  facts={[
                    { label: "Agent", value: group.agentVersion || "未知版本" },
                    { label: "最近同步", value: timeText(group.lastSyncedAt) },
                    {
                      label: "默认线路",
                      value: defaultRoute
                        ? backendText(
                            backendMap.get(
                              String(defaultRoute.backendForwardId),
                            ),
                          )
                        : "未设置",
                    },
                    {
                      label: "质量策略",
                      value: qualityPolicyText(defaultRoute?.qualityPolicy),
                    },
                  ]}
                  notice={
                    group.lastError
                      ? {
                          icon: <CircleAlert size={15} />,
                          label: "同步错误",
                          value: (
                            <span title={group.lastError}>
                              {group.lastError}
                            </span>
                          ),
                          tone: "danger",
                        }
                      : undefined
                  }
                  status={{ text: meta.label, color: meta.color }}
                  subtitles={[
                    group.ingressNodeName || `节点 ${group.ingressNodeId}`,
                  ]}
                  title={group.name}
                />
              );
            })}
          </ResourceCardGrid>
        )}
      </section>

      <section className="grid gap-3 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,0.85fr)]">
        <article className="border border-primary-200 bg-primary-50/50 p-4 dark:border-primary-500/20 dark:bg-primary-500/5 sm:p-5">
          <div className="flex items-start gap-3">
            <Info className="mt-0.5 shrink-0 text-primary" size={19} />
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="text-sm font-semibold">工作方式</h2>
                <Chip color="primary" size="sm" variant="flat">
                  TCP 源 IP 识别
                </Chip>
              </div>
              <p className="mt-2 text-sm leading-6 text-default-600 dark:text-default-300">
                客户端先连接统一入口，入口 Agent 在 TCP 接受连接时读取真实源
                IP，按最长 CIDR
                匹配规则后，将原始字节转发到对应后端；没有匹配或后端异常时回到默认线路。
              </p>
            </div>
          </div>
        </article>
        <article className="border border-divider bg-content1 p-4 sm:p-5">
          <div className="flex items-start gap-3">
            <ShieldCheck className="mt-0.5 shrink-0 text-secondary" size={19} />
            <div>
              <h2 className="text-sm font-semibold">使用条件</h2>
              <p className="mt-2 text-sm leading-6 text-default-600 dark:text-default-300">
                Agent ≥ {data?.minimumAgentVersion || "2.42.3"}
                ；入口不能经过隐藏源 IP 的代理或 CDN。 仅支持 TCP，UDP
                继续使用现有入口。
              </p>
            </div>
          </div>
        </article>
      </section>

      <section aria-label="来源 IP 分流能力" className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <h2 className="text-sm font-semibold">支持的规则类型</h2>
            <p className="mt-1 text-xs text-default-500">
              同一个入口可以按多个维度匹配来源 IP
            </p>
          </div>
          <span className="text-xs text-default-500">
            {data?.capabilities?.length || 0} 类能力
          </span>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {(data?.capabilities || []).map((item, index) => (
            <article
              key={item.key}
              className="border border-divider bg-content1 p-4"
            >
              <div className="flex items-center justify-between gap-3">
                {index === 0 ? (
                  <RadioTower className="text-primary" size={18} />
                ) : index === 1 ? (
                  <MapPin className="text-success" size={18} />
                ) : index === 2 ? (
                  <FlaskConical className="text-warning" size={18} />
                ) : (
                  <ShieldCheck className="text-secondary" size={18} />
                )}
                <Chip size="sm" variant="flat">
                  规则
                </Chip>
              </div>
              <h3 className="mt-3 text-sm font-semibold">{item.name}</h3>
              <p className="mt-2 text-xs leading-5 text-default-500">
                {item.detail}
              </p>
            </article>
          ))}
        </div>
      </section>

      <section aria-label="来源 IP 调试中心" className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <div className="flex items-center gap-2">
              <FlaskConical className="text-warning" size={17} />
              <h2 className="text-sm font-semibold">来源 IP 调试中心</h2>
            </div>
            <p className="mt-1 text-xs text-default-500">
              输入来源 IP，查看命中的规则和最终后端入口
            </p>
          </div>
          <Chip size="sm" variant="flat">
            诊断工具
          </Chip>
        </div>
        <div className="border border-divider bg-content1 p-4 sm:p-5">
          <div className="grid gap-2 md:grid-cols-[minmax(220px,1fr)_220px_auto]">
            <Input
              label="来源 IP"
              placeholder="例如 113.88.1.2"
              value={debugSourceIp}
              onValueChange={setDebugSourceIp}
            />
            <Select
              label="分流组"
              placeholder="全部分流组"
              selectedKeys={debugGroupId ? [debugGroupId] : []}
              onSelectionChange={(keys) =>
                setDebugGroupId(String(Array.from(keys)[0] || ""))
              }
            >
              {groups.map((group) => (
                <SelectItem key={String(group.id)}>
                  {group.name} · {group.listenPort}
                </SelectItem>
              ))}
            </Select>
            <Button
              className="self-end"
              color="primary"
              isLoading={debugging}
              startContent={<FlaskConical size={17} />}
              onPress={runDebug}
            >
              开始调试
            </Button>
          </div>
          {debugResult && (
            <div className="mt-5 border-t border-divider pt-4">
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Chip color="primary" size="sm" variant="flat">
                  {debugResult.ipVersion}
                </Chip>
                <span className="font-medium">{debugResult.sourceIp}</span>
                <span className="text-default-500">
                  运营商库判断：{debugResult.inferredCarrier.label}
                  {debugResult.inferredCarrier.matchedCidr
                    ? ` · ${debugResult.inferredCarrier.matchedCidr}`
                    : ""}
                </span>
              </div>
              <div className="mt-3 grid gap-3 lg:grid-cols-2">
                {debugResult.groups.map((item) => (
                  <article
                    key={item.groupId}
                    className="border border-divider p-3"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="font-medium">{item.groupName}</p>
                      <Chip
                        color={item.matched ? "success" : "default"}
                        size="sm"
                        variant="flat"
                      >
                        {item.matched ? "已命中" : "走默认"}
                      </Chip>
                    </div>
                    <p className="mt-1 text-xs text-default-500">
                      {item.listener} · {item.reason}
                    </p>
                    {item.selectedRoute && (
                      <div className="mt-3 border-l-2 border-primary px-3 py-2 text-sm">
                        <p className="font-medium">
                          {item.selectedRoute.ruleName} ·{" "}
                          {item.selectedRoute.ruleTypeLabel}
                        </p>
                        <p className="mt-1 text-xs text-default-500">
                          {item.selectedRoute.backendNodeName || "-"} ·{" "}
                          {item.selectedRoute.backendHost || "-"}:
                          {item.selectedRoute.backendPort || "-"} ·{" "}
                          {item.selectedRoute.qualityPolicyLabel}
                        </p>
                      </div>
                    )}
                    {item.warning && (
                      <p className="mt-2 text-xs text-warning">
                        {item.warning}
                      </p>
                    )}
                  </article>
                ))}
              </div>
            </div>
          )}
        </div>
      </section>

      <section aria-label="IP 库" className="space-y-3">
        <div>
          <h2 className="text-sm font-semibold">IP 库</h2>
          <p className="mt-1 text-xs text-default-500">
            运营商库来自 china-operator-ip，默认每日自动刷新；ASN 前缀来自
            RIPEstat，保存 ASN 规则时自动拉取
          </p>
        </div>
        <ResourceCardGrid>
          {(["telecom", "unicom", "mobile"] as const).map((carrier) => {
            const item = carrierMap.get(carrier) as
              SourceIpCarrierDatabase | undefined;

            return (
              <ResourceCard
                key={carrier}
                danger={item?.state === "error"}
                facts={[
                  { label: "CIDR", value: item?.cidrCount ?? "-" },
                  {
                    label: "更新时间",
                    value:
                      item?.state === "ready"
                        ? timeText(item.updatedTime)
                        : "-",
                  },
                  { label: "IPv4", value: item?.ipv4Count ?? "-" },
                  { label: "IPv6", value: item?.ipv6Count ?? "-" },
                ]}
                notice={
                  item?.lastError
                    ? {
                        icon: <CircleAlert size={15} />,
                        label: "同步错误",
                        value: item.lastError,
                        tone: "danger",
                      }
                    : undefined
                }
                status={{
                  text:
                    item?.state === "ready"
                      ? "已同步"
                      : item?.state === "error"
                        ? "同步失败"
                        : "待同步",
                  color:
                    item?.state === "ready"
                      ? "success"
                      : item?.state === "error"
                        ? "danger"
                        : "default",
                }}
                subtitles={["运营商 IP 库"]}
                title={carrierLabels[carrier]}
              />
            );
          })}
          {(data?.asns || []).map((item: SourceIpAsnDatabase) => (
            <ResourceCard
              key={item.asn}
              danger={item.state === "error"}
              facts={[
                { label: "前缀", value: item.prefixCount ?? "-" },
                {
                  label: "更新时间",
                  value:
                    item.state === "ready" ? timeText(item.updatedTime) : "-",
                },
                { label: "IPv4", value: item.ipv4Count ?? "-" },
                { label: "IPv6", value: item.ipv6Count ?? "-" },
              ]}
              notice={
                item.lastError
                  ? {
                      icon: <CircleAlert size={15} />,
                      label: "同步错误",
                      value: item.lastError,
                      tone: "danger",
                    }
                  : undefined
              }
              status={{
                text:
                  item.state === "ready"
                    ? "已同步"
                    : item.state === "error"
                      ? "同步失败"
                      : "待同步",
                color:
                  item.state === "ready"
                    ? "success"
                    : item.state === "error"
                      ? "danger"
                      : "default",
              }}
              subtitles={["ASN 前缀库"]}
              title={item.asn}
            />
          ))}
        </ResourceCardGrid>
      </section>

      <Modal
        isOpen={formOpen}
        scrollBehavior="inside"
        size="4xl"
        onOpenChange={setFormOpen}
      >
        <ModalContent>
          <ModalHeader>
            {form.id ? "编辑来源 IP 分流" : "新建来源 IP 分流"}
          </ModalHeader>
          <ModalBody className="gap-5">
            <section className="grid gap-3 sm:grid-cols-2">
              <Input
                isRequired
                label="分流名称"
                placeholder="例如：国内三网入口"
                value={form.name}
                onValueChange={(name) => setForm({ ...form, name })}
              />
              <Select
                isRequired
                description={`Agent 需要 ${data?.minimumAgentVersion || "2.42.3"}+，此节点直接接收客户端连接`}
                label="统一入口节点"
                selectedKeys={form.ingressNodeId ? [form.ingressNodeId] : []}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    ingressNodeId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {nodes.map((node) => (
                  <SelectItem
                    key={String(node.id)}
                    isDisabled={!node.available}
                    textValue={`${node.name} ${node.serverIp || node.ip || ""}`}
                  >
                    {node.name} · {node.serverIp || node.ip || "无地址"} ·{" "}
                    {node.available
                      ? `Agent ${node.version}`
                      : node.online
                        ? `需升级到 ${data?.minimumAgentVersion}`
                        : "离线"}
                  </SelectItem>
                ))}
              </Select>
              <Input
                label="监听地址（可选）"
                placeholder="留空监听所有地址；例如 0.0.0.0 或 ::"
                value={form.listenHost}
                onValueChange={(listenHost) => setForm({ ...form, listenHost })}
              />
              <Input
                isRequired
                label="监听 TCP 端口"
                max={65535}
                min={1}
                placeholder="例如 443 或 24443"
                type="number"
                value={form.listenPort}
                onValueChange={(listenPort) => setForm({ ...form, listenPort })}
              />
            </section>
            <div className="border-l-2 border-warning bg-warning-50 px-3 py-3 text-xs leading-5 text-warning-800 dark:bg-warning-500/10 dark:text-warning-200">
              <p className="font-medium">端口和协议说明</p>
              <p className="mt-1">
                统一入口端口必须未被服务器其他程序占用。它只做 TCP
                原始转发，后端转发应使用同一种上层协议和对应端口；不要把
                UDP-only 线路放进来。入口若经过 Cloudflare
                代理、四层代理或其他中间层，源 IP 可能变成中间层地址。
              </p>
            </div>
            <section className="border-t border-divider pt-4">
              <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
                <div>
                  <h3 className="text-sm font-semibold">来源规则</h3>
                  <p className="mt-1 text-xs text-default-500">
                    按最长 CIDR 匹配；未命中或线路失败时使用
                    default。每条规则连接一个现有 TCP 转发入口。
                  </p>
                </div>
                <Button
                  isDisabled={form.routes.length >= 20}
                  size="sm"
                  startContent={<Plus size={16} />}
                  variant="flat"
                  onPress={addRoute}
                >
                  添加规则
                </Button>
              </div>
              <div className="space-y-3">
                {form.routes.map((route, index) => (
                  <div
                    key={`${index}-${route.ruleType}-${route.carrier}`}
                    className="border border-divider p-3"
                  >
                    <div className="grid gap-3 lg:grid-cols-[160px_minmax(0,1fr)_120px] lg:items-end">
                      <Select
                        label="规则类型"
                        selectedKeys={[route.ruleType]}
                        onSelectionChange={(keys) => {
                          const ruleType = String(
                            Array.from(keys)[0] || "cidr",
                          ) as RuleType;

                          updateRoute(index, {
                            ruleType,
                            carrier:
                              ruleType === "default"
                                ? "default"
                                : ruleType === "carrier"
                                  ? route.carrier === "default" ||
                                    route.carrier === "custom"
                                    ? "telecom"
                                    : route.carrier
                                  : "custom",
                            cidrs:
                              ruleType === "default" ||
                              ruleType === "carrier" ||
                              ruleType === "asn"
                                ? ""
                                : route.cidrs,
                            priority:
                              ruleType === "default" ? "999" : route.priority,
                            qualityPolicy:
                              ruleType === "risk"
                                ? "quarantine"
                                : route.qualityPolicy,
                          });
                        }}
                      >
                        <SelectItem key="default">默认回退</SelectItem>
                        <SelectItem key="carrier">运营商</SelectItem>
                        <SelectItem key="cidr">CIDR/IP 段</SelectItem>
                        <SelectItem key="asn">ASN 自动展开</SelectItem>
                        <SelectItem key="region">地区</SelectItem>
                        <SelectItem key="vip">VIP 来源</SelectItem>
                        <SelectItem key="customer">专属客户</SelectItem>
                        <SelectItem key="gray">灰度测试</SelectItem>
                        <SelectItem key="risk">风险隔离</SelectItem>
                      </Select>
                      <Input
                        label="规则名称"
                        placeholder="例如：广东联通 VIP / AS4134"
                        value={route.ruleName}
                        onValueChange={(ruleName) =>
                          updateRoute(index, { ruleName })
                        }
                      />
                      <Input
                        label="优先级"
                        max={999}
                        min={1}
                        type="number"
                        value={route.priority}
                        onValueChange={(priority) =>
                          updateRoute(index, { priority })
                        }
                      />
                    </div>
                    <div className="mt-3 grid gap-3 lg:grid-cols-[160px_minmax(0,1fr)_auto] lg:items-end">
                      {route.ruleType === "carrier" ? (
                        <Select
                          label="运营商"
                          selectedKeys={[route.carrier]}
                          onSelectionChange={(keys) =>
                            updateRoute(index, {
                              carrier: String(
                                Array.from(keys)[0] || "telecom",
                              ) as Carrier,
                            })
                          }
                        >
                          <SelectItem key="telecom">中国电信</SelectItem>
                          <SelectItem key="unicom">中国联通</SelectItem>
                          <SelectItem key="mobile">中国移动</SelectItem>
                        </Select>
                      ) : (
                        <Input
                          isDisabled
                          label="匹配方式"
                          value={
                            route.ruleType === "default"
                              ? "未命中回退"
                              : route.ruleType === "asn"
                                ? "ASN 自动展开"
                                : "CIDR 最长前缀"
                          }
                        />
                      )}
                      <Select
                        isRequired
                        label="后端入口转发"
                        placeholder="选择现有 TCP 转发"
                        selectedKeys={
                          route.backendForwardId ? [route.backendForwardId] : []
                        }
                        onSelectionChange={(keys) =>
                          updateRoute(index, {
                            backendForwardId: String(Array.from(keys)[0] || ""),
                          })
                        }
                      >
                        {backendGroups.map((group, groupIndex) => (
                          <SelectSection
                            key={`port-${group.port}`}
                            showDivider={groupIndex < backendGroups.length - 1}
                            title={`端口 ${group.port} (${group.options.length})`}
                          >
                            {group.options.map((forward) => (
                              <SelectItem
                                key={String(forward.id)}
                                isDisabled={forward.nodeStatus !== 1}
                                textValue={`端口 ${forward.inPort} ${forward.nodeName || ""} ${forward.entryHost || ""} ${forward.name}`}
                              >
                                {backendText(forward)} · {forward.name}
                              </SelectItem>
                            ))}
                          </SelectSection>
                        ))}
                      </Select>
                      <div className="flex justify-end gap-1">
                        <Switch
                          isSelected={route.enabled}
                          size="sm"
                          onValueChange={(enabled) =>
                            updateRoute(index, { enabled })
                          }
                        >
                          启用
                        </Switch>
                        {route.ruleType !== "default" && (
                          <Button
                            isIconOnly
                            aria-label="删除线路"
                            color="danger"
                            size="sm"
                            title="删除线路"
                            variant="light"
                            onPress={() => removeRoute(index)}
                          >
                            <X size={17} />
                          </Button>
                        )}
                      </div>
                    </div>
                    {route.ruleType !== "default" && (
                      <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                        <Input
                          label="地区/场景"
                          placeholder="广东 / 华东 / 香港 / 美西"
                          value={route.region}
                          onValueChange={(region) =>
                            updateRoute(index, { region })
                          }
                        />
                        <Input
                          description={
                            route.ruleType === "asn"
                              ? "可填 AS4134 或 4134，保存时自动拉取当前公告前缀"
                              : undefined
                          }
                          label="ASN"
                          placeholder="AS4134"
                          value={route.asn}
                          onValueChange={(asn) => updateRoute(index, { asn })}
                        />
                        <Input
                          label="标签"
                          placeholder="VIP,客户A,测试"
                          value={route.tags}
                          onValueChange={(tags) => updateRoute(index, { tags })}
                        />
                        <Select
                          label="质量策略"
                          selectedKeys={[route.qualityPolicy]}
                          onSelectionChange={(keys) =>
                            updateRoute(index, {
                              qualityPolicy: String(
                                Array.from(keys)[0] || "static",
                              ) as QualityPolicy,
                            })
                          }
                        >
                          <SelectItem key="static">固定规则</SelectItem>
                          <SelectItem key="quality_aware">质量联动</SelectItem>
                          <SelectItem key="prefer_primary">主线优先</SelectItem>
                          <SelectItem key="quarantine">隔离/降级</SelectItem>
                        </Select>
                      </div>
                    )}
                    {routeNeedsCidrs(route) ? (
                      <Textarea
                        className="mt-3"
                        label="CIDR 列表"
                        minRows={3}
                        placeholder="每行一个，例如：\n113.88.0.0/16\n2408:8000::/20"
                        value={route.cidrs}
                        onValueChange={(cidrs) => updateRoute(index, { cidrs })}
                      />
                    ) : route.ruleType === "default" ? (
                      <p className="mt-2 text-xs text-default-500">
                        default 不匹配来源
                        IP，作为后端失败和未知来源的回退线路。
                      </p>
                    ) : route.ruleType === "asn" ? (
                      <p className="mt-2 text-xs text-default-500">
                        保存时自动联网查询 {route.asn || "该 ASN"}{" "}
                        当前公告前缀并缓存； 当前缓存{" "}
                        {route.asn
                          ? asnMap.get(normalizeAsnKey(route.asn))
                              ?.prefixCount || 0
                          : 0}{" "}
                        条前缀。可通过页面顶部按钮刷新并同步。
                      </p>
                    ) : (
                      <p className="mt-2 text-xs text-default-500">
                        保存时自动使用 {carrierLabels[route.carrier]} IP
                        库；当前缓存{" "}
                        {carrierMap.get(
                          route.carrier as "telecom" | "unicom" | "mobile",
                        )?.cidrCount || 0}{" "}
                        条 CIDR。可通过页面顶部按钮刷新。
                      </p>
                    )}
                    <Textarea
                      className="mt-3"
                      label="备注（可选）"
                      minRows={2}
                      placeholder="写清楚这条规则给谁用、为什么走这条线路，后期排查会轻松很多。"
                      value={route.notes}
                      onValueChange={(notes) => updateRoute(index, { notes })}
                    />
                  </div>
                ))}
              </div>
            </section>
            <Switch
              isSelected={form.enabled}
              onValueChange={(enabled) => setForm({ ...form, enabled })}
            >
              启用统一入口服务
            </Switch>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setFormOpen(false)}>
              取消
            </Button>
            <Button color="primary" isLoading={submitting} onPress={submit}>
              保存并同步 Agent
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
