import { useCallback, useEffect, useMemo, useState } from "react";
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
import { Select, SelectItem } from "@heroui/select";
import { Switch } from "@heroui/switch";
import { Tab, Tabs } from "@heroui/tabs";
import {
  Activity,
  CircleCheck,
  Download,
  FlaskConical,
  Gauge,
  ListRestart,
  Pause,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import toast from "react-hot-toast";
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import {
  deleteQualityProbeTask,
  discoverNodeServices,
  getQualityLabDetail,
  getQualityLabOverview,
  getQualityLabReport,
  preflightQualityProbeTask,
  runQualityProbeTask,
  saveQualityProbeTask,
  toggleQualityProbeTask,
  type NodeDiscoveredService,
  type QualityLabDetail,
  type QualityLabOverview,
  type QualityProbePreflight,
  type QualityProbeTask,
  type QualityProbeTaskInput,
} from "@/api";

type Range = "24h" | "7d" | "30d";
type FormState = {
  id?: number;
  name: string;
  sourceNodeId: string;
  targetType: "custom" | "node";
  targetNodeId: string;
  targetHost: string;
  port: string;
  protocol: "tcp" | "tls" | "http" | "https";
  path: string;
  serverName: string;
  ipFamily: "auto" | "ipv4" | "ipv6";
  sampleCount: string;
  timeoutMs: string;
  intervalMinutes: string;
  retentionDays: string;
  enabled: boolean;
};

const emptyForm: FormState = {
  name: "",
  sourceNodeId: "",
  targetType: "custom",
  targetNodeId: "",
  targetHost: "",
  port: "443",
  protocol: "https",
  path: "/",
  serverName: "",
  ipFamily: "auto",
  sampleCount: "5",
  timeoutMs: "5000",
  intervalMinutes: "15",
  retentionDays: "30",
  enabled: false,
};
const bool = (value: boolean | number) => value === true || value === 1;
const metric = (value?: number) =>
  value && value > 0
    ? `${Number(value).toFixed(value >= 100 ? 0 : 1)} ms`
    : "-";
const timeText = (value?: number) =>
  value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "尚未运行";
const friendlyError = (error?: string) => {
  const value = error || "";

  if (/connection refused/i.test(value))
    return "目标端口未监听，或目标防火墙主动拒绝连接";
  if (/timeout|timed out|deadline exceeded/i.test(value))
    return "连接目标端口超时，请检查防火墙、安全组和网络路由";
  if (/no route to host|network is unreachable/i.test(value))
    return "没有到目标地址的可用网络路由";
  if (/no such host|server misbehaving/i.test(value)) return "目标域名无法解析";

  return value;
};
const statusMeta = (task: QualityProbeTask) => {
  if (bool(task.running) || task.lastStatus === "running")
    return { label: "探测中", color: "primary" as const };
  if (task.lastStatus === "failed")
    return { label: "探测失败", color: "danger" as const };
  if (task.lastStatus === "partial")
    return { label: "部分失败", color: "warning" as const };
  if (task.lastStatus === "success")
    return { label: "质量正常", color: "success" as const };

  return {
    label: bool(task.enabled) ? "等待探测" : "已暂停",
    color: "default" as const,
  };
};

export default function QualityLabPage() {
  const [data, setData] = useState<QualityLabOverview>({
    minimumAgentVersion: "2.36.0",
    nodes: [],
    tasks: [],
    summary: { total: 0, enabled: 0, healthy: 0, degraded: 0, failed: 0 },
    lineProfiles: [],
  });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState<QualityLabDetail | null>(null);
  const [detailTask, setDetailTask] = useState<QualityProbeTask | null>(null);
  const [range, setRange] = useState<Range>("24h");
  const [preflight, setPreflight] = useState<QualityProbePreflight | null>(
    null,
  );
  const [discoveredServices, setDiscoveredServices] = useState<
    NodeDiscoveredService[]
  >([]);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const response = await getQualityLabOverview();

    if (!quiet) setLoading(false);
    if (response.code === 0) setData(response.data);
    else if (!quiet) toast.error(response.msg || "加载质量任务失败");
  }, []);

  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (!document.hidden) void load(true);
    }, 5000);

    return () => window.clearInterval(timer);
  }, [load]);

  const onlineNodes = useMemo(
    () => data.nodes.filter((node) => node.status === 1),
    [data.nodes],
  );
  const targetNode = data.nodes.find(
    (node) => String(node.id) === form.targetNodeId,
  );
  const openCreate = () => {
    setForm(emptyForm);
    setPreflight(null);
    setDiscoveredServices([]);
    setFormOpen(true);
  };
  const openEdit = (task: QualityProbeTask) => {
    setForm({
      id: task.id,
      name: task.name,
      sourceNodeId: String(task.sourceNodeId),
      targetType: task.targetType,
      targetNodeId: task.targetNodeId ? String(task.targetNodeId) : "",
      targetHost: task.targetHost,
      port: String(task.port),
      protocol: task.protocol,
      path: task.path || "/",
      serverName: task.serverName || "",
      ipFamily: task.ipFamily,
      sampleCount: String(task.sampleCount),
      timeoutMs: String(task.timeoutMs),
      intervalMinutes: String(task.intervalMinutes),
      retentionDays: String(task.retentionDays),
      enabled: bool(task.enabled),
    });
    setPreflight(null);
    setDiscoveredServices([]);
    setFormOpen(true);
  };

  useEffect(() => {
    setPreflight(null);
  }, [
    form.sourceNodeId,
    form.targetType,
    form.targetNodeId,
    form.targetHost,
    form.port,
    form.ipFamily,
  ]);

  const inputFromForm = (): QualityProbeTaskInput => ({
    id: form.id,
    name: form.name.trim(),
    sourceNodeId: Number(form.sourceNodeId),
    targetType: form.targetType,
    targetNodeId:
      form.targetType === "node" ? Number(form.targetNodeId) : undefined,
    targetHost: form.targetHost.trim(),
    port: Number(form.port),
    protocol: form.protocol,
    path: form.path || "/",
    serverName: form.serverName.trim() || undefined,
    ipFamily: form.ipFamily,
    sampleCount: Number(form.sampleCount),
    timeoutMs: Number(form.timeoutMs),
    intervalMinutes: Number(form.intervalMinutes),
    retentionDays: Number(form.retentionDays),
    enabled: form.enabled,
  });

  const checkTarget = async (input = inputFromForm()) => {
    if (!form.sourceNodeId || !form.targetHost.trim() || !form.port) {
      toast.error("请先选择执行节点、目标地址和开放端口");

      return null;
    }
    setBusy("preflight");
    const response = await preflightQualityProbeTask(input);

    setBusy("");
    if (response.code !== 0) {
      toast.error(response.msg || "目标预检失败");

      return null;
    }
    setPreflight(response.data);
    if (response.data.reachable)
      toast.success(
        `目标端口可连接${response.data.tcpMs ? `，TCP ${Number(response.data.tcpMs).toFixed(1)} ms` : ""}`,
      );
    else toast.error(response.data.message);

    return response.data;
  };

  const loadTargetServices = async () => {
    if (!form.targetNodeId) return toast.error("请先选择目标节点");
    setBusy("services");
    const response = await discoverNodeServices(Number(form.targetNodeId));

    setBusy("");
    if (response.code !== 0)
      return toast.error(response.msg || "读取开放端口失败");
    const unique = response.data.services.filter(
      (item, index, all) =>
        all.findIndex((candidate) => candidate.port === item.port) === index,
    );

    setDiscoveredServices(unique);
    if (unique.length === 0) toast("目标节点没有发现正在监听的 TCP 服务");
  };

  const save = async () => {
    if (!form.name.trim() || !form.sourceNodeId || !form.targetHost.trim())
      return toast.error("请填写任务名称、执行节点和目标地址");
    const input = inputFromForm();

    if (!preflight) {
      const checked = await checkTarget(input);

      if (!checked?.reachable) return;
    } else if (
      !preflight.reachable &&
      !window.confirm(
        "目标端口当前不可连接。仍然保存后，可用它持续监控端口何时恢复。确认继续吗？",
      )
    )
      return;
    setBusy("save");
    const response = await saveQualityProbeTask(input);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    setData(response.data);
    setFormOpen(false);
    toast.success("质量任务已保存");
  };

  const run = async (task: QualityProbeTask) => {
    setBusy(`run-${task.id}`);
    const response = await runQualityProbeTask(task.id);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "提交探测失败");
    toast.success("探测已提交，完成后自动刷新");
    void load(true);
  };

  const toggle = async (task: QualityProbeTask) => {
    setBusy(`toggle-${task.id}`);
    const response = await toggleQualityProbeTask(task.id, !bool(task.enabled));

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "修改状态失败");
    setData(response.data);
  };

  const remove = async (task: QualityProbeTask) => {
    if (
      !window.confirm(
        `删除“${task.name}”及其全部历史样本？现有节点和业务配置不会受影响。`,
      )
    )
      return;
    setBusy(`delete-${task.id}`);
    const response = await deleteQualityProbeTask(task.id);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("质量任务已删除");
    void load();
  };

  const loadDetail = useCallback(
    async (task: QualityProbeTask, selectedRange: Range) => {
      setBusy(`detail-${task.id}`);
      const response = await getQualityLabDetail(task.id, selectedRange);

      setBusy("");
      if (response.code !== 0)
        return toast.error(response.msg || "加载质量详情失败");
      setDetailTask(task);
      setDetail(response.data);
      setRange(selectedRange);
      setDetailOpen(true);
    },
    [],
  );

  const downloadReport = async () => {
    if (!detailTask) return;
    const response = await getQualityLabReport(detailTask.id, range);

    if (response.code !== 0) return toast.error(response.msg || "生成报告失败");
    const blob = new Blob([response.data.content], {
      type: "text/markdown;charset=utf-8",
    });
    const href = URL.createObjectURL(blob);
    const anchor = document.createElement("a");

    anchor.href = href;
    anchor.download = response.data.filename;
    anchor.click();
    URL.revokeObjectURL(href);
  };

  const summaryCards = [
    {
      label: "质量任务",
      value: data.summary.total,
      meta: `${data.summary.enabled} 项自动运行`,
      icon: <FlaskConical size={18} />,
      tone: "bg-blue-50 text-blue-600 dark:bg-blue-500/10",
    },
    {
      label: "质量正常",
      value: data.summary.healthy,
      meta: "最近一轮全部成功",
      icon: <Activity size={18} />,
      tone: "bg-emerald-50 text-emerald-600 dark:bg-emerald-500/10",
    },
    {
      label: "需要关注",
      value: data.summary.degraded,
      meta: "存在失败或明显波动",
      icon: <TriangleAlert size={18} />,
      tone: "bg-amber-50 text-amber-600 dark:bg-amber-500/10",
    },
    {
      label: "探测失败",
      value: data.summary.failed,
      meta: "目标或执行节点不可达",
      icon: <Gauge size={18} />,
      tone: "bg-rose-50 text-rose-600 dark:bg-rose-500/10",
    },
  ];

  return (
    <div className="mx-auto w-full max-w-[1500px] space-y-5 p-4 md:p-6">
      <header className="flex min-w-0 flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div className="min-w-0">
          <p className="text-sm text-default-500">实用工具</p>
          <h1 className="mt-1 text-xl font-semibold sm:text-2xl">
            网络质量实验室
          </h1>
          <p className="mt-2 max-w-3xl text-sm text-default-500">
            由现有 Agent
            定时建立线路画像。任务默认关闭，不创建任务就不会产生探测流量。
          </p>
        </div>
        <div className="grid w-full grid-cols-[44px_minmax(0,1fr)] gap-2 sm:flex sm:w-auto">
          <Button
            isIconOnly
            className="h-11 w-11 min-w-11"
            title="刷新"
            variant="flat"
            onPress={() => void load()}
          >
            <RefreshCw size={17} />
          </Button>
          <Button
            className="min-w-0"
            color="primary"
            startContent={<Plus size={17} />}
            onPress={openCreate}
          >
            新建质量任务
          </Button>
        </div>
      </header>

      <section
        aria-label="质量摘要"
        className="grid grid-cols-2 gap-3 lg:grid-cols-4"
      >
        {summaryCards.map((card) => (
          <Card
            key={card.label}
            className="border border-divider"
            radius="sm"
            shadow="none"
          >
            <CardBody className="gap-3 p-4">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm text-default-500">{card.label}</span>
                <span
                  className={`flex h-9 w-9 items-center justify-center rounded-lg ${card.tone}`}
                >
                  {card.icon}
                </span>
              </div>
              <div>
                <p className="text-2xl font-semibold">
                  {loading ? "-" : card.value}
                </p>
                <p className="mt-1 truncate text-xs text-default-500">
                  {card.meta}
                </p>
              </div>
            </CardBody>
          </Card>
        ))}
      </section>

      {data.lineProfiles.length > 0 && (
        <section className="border-y border-divider py-4">
          <div className="mb-3">
            <h2 className="font-medium">过去 24 小时线路画像</h2>
            <p className="mt-1 text-xs text-default-500">
              按服务器资产中的线路标签汇总多台执行节点；这是节点标签对比，不等同于住宅三网实测。
            </p>
          </div>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {data.lineProfiles.map((item) => (
              <div
                key={String(item.label)}
                className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-3 border border-divider p-3 text-sm"
              >
                <div className="min-w-0">
                  <p className="truncate font-medium">{String(item.label)}</p>
                  <p className="mt-1 text-xs text-default-500">
                    {item.runs} 轮 · 失败{" "}
                    {Number(item.failureRate || 0).toFixed(1)}%
                  </p>
                </div>
                <div className="text-right text-xs">
                  <strong>P95 {metric(item.p95Ms)}</strong>
                  <br />
                  <span className="text-default-500">
                    抖动 {metric(item.jitterMs)}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {data.tasks.length === 0 && !loading ? (
        <div className="flex min-h-72 flex-col items-center justify-center gap-3 border-y border-divider text-default-400">
          <FlaskConical size={34} />
          <p>尚未创建质量任务</p>
          <Button size="sm" variant="flat" onPress={openCreate}>
            创建第一项任务
          </Button>
        </div>
      ) : (
        <section className="grid gap-4 xl:grid-cols-2">
          {data.tasks.map((task) => {
            const state = statusMeta(task);

            return (
              <article
                key={task.id}
                className="min-w-0 border border-divider bg-content1 p-4 sm:p-5"
              >
                <div className="flex min-w-0 flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2
                        className="max-w-full truncate text-base font-semibold"
                        title={task.name}
                      >
                        {task.name}
                      </h2>
                      <Chip color={state.color} size="sm" variant="flat">
                        {state.label}
                      </Chip>
                      <Chip size="sm" variant="flat">
                        {task.protocol.toUpperCase()} ·{" "}
                        {task.ipFamily === "auto"
                          ? "自动 IP"
                          : task.ipFamily.toUpperCase()}
                      </Chip>
                    </div>
                    <p
                      className="mt-2 truncate text-sm text-default-500"
                      title={`${task.sourceNodeName} → ${task.targetHost}:${task.port}`}
                    >
                      {task.sourceNodeName} → {task.targetHost}:{task.port}
                    </p>
                    <p className="mt-1 text-xs text-default-400">
                      {task.sourceLine || "未标注线路"} ·{" "}
                      {bool(task.enabled)
                        ? `每 ${task.intervalMinutes} 分钟`
                        : "仅手动探测"}{" "}
                      · 保留 {task.retentionDays} 天
                    </p>
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <Button
                      isIconOnly
                      isDisabled={bool(task.running)}
                      isLoading={busy === `run-${task.id}`}
                      size="sm"
                      title="立即探测"
                      variant="light"
                      onPress={() => void run(task)}
                    >
                      <Play size={16} />
                    </Button>
                    <Button
                      isIconOnly
                      isLoading={busy === `toggle-${task.id}`}
                      size="sm"
                      title={
                        bool(task.enabled) ? "暂停自动探测" : "启用自动探测"
                      }
                      variant="light"
                      onPress={() => void toggle(task)}
                    >
                      {bool(task.enabled) ? (
                        <Pause size={16} />
                      ) : (
                        <Activity size={16} />
                      )}
                    </Button>
                    <Button
                      isIconOnly
                      isDisabled={bool(task.running)}
                      size="sm"
                      title="编辑"
                      variant="light"
                      onPress={() => openEdit(task)}
                    >
                      <Pencil size={16} />
                    </Button>
                    <Button
                      isIconOnly
                      color="danger"
                      isDisabled={bool(task.running)}
                      isLoading={busy === `delete-${task.id}`}
                      size="sm"
                      title="删除"
                      variant="light"
                      onPress={() => void remove(task)}
                    >
                      <Trash2 size={16} />
                    </Button>
                  </div>
                </div>
                <div className="mt-4 grid grid-cols-3 divide-x divide-divider border-y border-divider py-3 text-center">
                  <div>
                    <p className="text-xs text-default-500">P95 延迟</p>
                    <p className="mt-1 font-semibold">{metric(task.p95Ms)}</p>
                  </div>
                  <div>
                    <p className="text-xs text-default-500">平均抖动</p>
                    <p className="mt-1 font-semibold">
                      {metric(task.jitterMs)}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-default-500">失败率</p>
                    <p
                      className={`mt-1 font-semibold ${(task.failureRate || 0) > 0 ? "text-warning" : ""}`}
                    >
                      {task.latestStartedAt
                        ? `${Number(task.failureRate || 0).toFixed(1)}%`
                        : "-"}
                    </p>
                  </div>
                </div>
                <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0 text-xs text-default-500">
                    <span>
                      {timeText(task.latestStartedAt || task.lastRunAt)}
                    </span>
                    {task.lastError && (
                      <p
                        className="mt-1 max-w-xl truncate text-danger"
                        title={task.lastError}
                      >
                        {friendlyError(task.lastError)}
                      </p>
                    )}
                  </div>
                  <Button
                    isLoading={busy === `detail-${task.id}`}
                    size="sm"
                    variant="flat"
                    onPress={() => void loadDetail(task, "24h")}
                  >
                    质量详情
                  </Button>
                </div>
              </article>
            );
          })}
        </section>
      )}

      <Modal
        isOpen={formOpen}
        scrollBehavior="inside"
        size="3xl"
        onOpenChange={setFormOpen}
      >
        <ModalContent>
          <ModalHeader>{form.id ? "编辑质量任务" : "新建质量任务"}</ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Input
                isRequired
                label="任务名称"
                placeholder="香港入口到业务站点"
                value={form.name}
                onValueChange={(value) => setForm({ ...form, name: value })}
              />
              <Select
                isRequired
                label="执行节点"
                selectedKeys={form.sourceNodeId ? [form.sourceNodeId] : []}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    sourceNodeId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {onlineNodes.map((node) => (
                  <SelectItem key={String(node.id)} textValue={node.name}>
                    {node.name} · {node.networkLine || "未标注线路"} · Agent{" "}
                    {node.version || "未知"}
                  </SelectItem>
                ))}
              </Select>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <Select
                label="目标类型"
                selectedKeys={[form.targetType]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    targetType: String(
                      Array.from(keys)[0] || "custom",
                    ) as FormState["targetType"],
                    targetNodeId: "",
                    targetHost: "",
                  })
                }
              >
                <SelectItem key="custom">任意域名或 IP</SelectItem>
                <SelectItem key="node">两台 Agent 节点互测</SelectItem>
              </Select>
              {form.targetType === "node" ? (
                <Select
                  isRequired
                  label="目标节点"
                  selectedKeys={form.targetNodeId ? [form.targetNodeId] : []}
                  onSelectionChange={(keys) => {
                    const id = String(Array.from(keys)[0] || "");
                    const node = data.nodes.find(
                      (item) => String(item.id) === id,
                    );

                    setForm({
                      ...form,
                      targetNodeId: id,
                      targetHost: node?.serverIp || node?.ip || "",
                    });
                  }}
                >
                  {data.nodes
                    .filter((node) => String(node.id) !== form.sourceNodeId)
                    .map((node) => (
                      <SelectItem key={String(node.id)} textValue={node.name}>
                        {node.name} · {node.serverIp || node.ip || "未设置地址"}
                      </SelectItem>
                    ))}
                </Select>
              ) : (
                <Input
                  isRequired
                  label="目标地址"
                  placeholder="example.com 或 1.1.1.1"
                  value={form.targetHost}
                  onValueChange={(value) =>
                    setForm({ ...form, targetHost: value })
                  }
                />
              )}
            </div>
            {form.targetType === "node" && (
              <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto]">
                <Input
                  isReadOnly
                  description={
                    targetNode
                      ? `目标节点：${targetNode.name}。Agent 在线不代表任意端口都已开放。`
                      : "选择目标节点后自动带出"
                  }
                  label="互测地址"
                  value={form.targetHost}
                />
                <Button
                  className="self-end"
                  isLoading={busy === "services"}
                  startContent={<ListRestart size={16} />}
                  variant="flat"
                  onPress={() => void loadTargetServices()}
                >
                  读取监听端口
                </Button>
              </div>
            )}
            <div className="grid gap-4 sm:grid-cols-3">
              <Select
                label="探测协议"
                selectedKeys={[form.protocol]}
                onSelectionChange={(keys) => {
                  const protocol = String(
                    Array.from(keys)[0] || "https",
                  ) as FormState["protocol"];

                  setForm({
                    ...form,
                    protocol,
                    port:
                      protocol === "http"
                        ? "80"
                        : protocol === "https" || protocol === "tls"
                          ? "443"
                          : form.port,
                  });
                }}
              >
                {["tcp", "tls", "http", "https"].map((item) => (
                  <SelectItem key={item}>{item.toUpperCase()}</SelectItem>
                ))}
              </Select>
              {form.targetType === "node" && discoveredServices.length > 0 ? (
                <Select
                  description="来自目标 Agent 的监听服务；仍需预检公网可达性"
                  label="目标开放端口"
                  selectedKeys={form.port ? [form.port] : []}
                  onSelectionChange={(keys) =>
                    setForm({
                      ...form,
                      port: String(Array.from(keys)[0] || ""),
                    })
                  }
                >
                  {discoveredServices.map((service) => (
                    <SelectItem
                      key={String(service.port)}
                      textValue={`${service.port} ${service.serviceName || service.protocol}`}
                    >
                      {service.port} ·{" "}
                      {service.serviceName || service.protocol.toUpperCase()}
                      {service.processName ? ` · ${service.processName}` : ""}
                    </SelectItem>
                  ))}
                </Select>
              ) : (
                <Input
                  description={
                    form.targetType === "node"
                      ? "填写目标服务器真实监听且允许外部访问的端口"
                      : undefined
                  }
                  label={
                    form.targetType === "node" ? "目标开放端口" : "目标端口"
                  }
                  max={65535}
                  min={1}
                  type="number"
                  value={form.port}
                  onValueChange={(value) => setForm({ ...form, port: value })}
                />
              )}
              <Select
                label="IP 协议族"
                selectedKeys={[form.ipFamily]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    ipFamily: String(
                      Array.from(keys)[0] || "auto",
                    ) as FormState["ipFamily"],
                  })
                }
              >
                <SelectItem key="auto">自动选择</SelectItem>
                <SelectItem key="ipv4">仅 IPv4</SelectItem>
                <SelectItem key="ipv6">仅 IPv6</SelectItem>
              </Select>
            </div>
            {preflight && (
              <div
                className={`flex items-start gap-3 border px-4 py-3 text-sm ${preflight.reachable ? "border-success/30 bg-success/10 text-success-700 dark:text-success-400" : "border-danger/30 bg-danger/10 text-danger-700 dark:text-danger-400"}`}
              >
                {preflight.reachable ? (
                  <CircleCheck className="mt-0.5 shrink-0" size={17} />
                ) : (
                  <TriangleAlert className="mt-0.5 shrink-0" size={17} />
                )}
                <div>
                  <p className="font-medium">{preflight.message}</p>
                  <p className="mt-1 text-xs opacity-80">
                    {preflight.reachable
                      ? `${preflight.resolvedAddress || form.targetHost}:${form.port}${preflight.tcpMs ? ` · TCP ${Number(preflight.tcpMs).toFixed(1)} ms` : ""}`
                      : `${preflight.error || ""}。可修改端口后重试，或保存为故障监控任务。`}
                  </p>
                </div>
              </div>
            )}
            {(form.protocol === "http" || form.protocol === "https") && (
              <Input
                label="HTTP 路径"
                placeholder="/health"
                value={form.path}
                onValueChange={(value) => setForm({ ...form, path: value })}
              />
            )}
            {(form.protocol === "tls" || form.protocol === "https") && (
              <Input
                description="目标填写 IP 时需要；填写域名时可留空"
                label="TLS 域名（SNI）"
                placeholder="example.com"
                value={form.serverName}
                onValueChange={(value) =>
                  setForm({ ...form, serverName: value })
                }
              />
            )}
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <Input
                label="每轮样本"
                max={10}
                min={1}
                type="number"
                value={form.sampleCount}
                onValueChange={(value) =>
                  setForm({ ...form, sampleCount: value })
                }
              />
              <Input
                label="单次超时（ms）"
                max={15000}
                min={500}
                type="number"
                value={form.timeoutMs}
                onValueChange={(value) =>
                  setForm({ ...form, timeoutMs: value })
                }
              />
              <Input
                label="间隔（分钟）"
                max={1440}
                min={5}
                type="number"
                value={form.intervalMinutes}
                onValueChange={(value) =>
                  setForm({ ...form, intervalMinutes: value })
                }
              />
              <Input
                label="保留（天）"
                max={365}
                min={1}
                type="number"
                value={form.retentionDays}
                onValueChange={(value) =>
                  setForm({ ...form, retentionDays: value })
                }
              />
            </div>
            <div className="flex items-center justify-between gap-4 border-y border-divider py-3">
              <div>
                <p className="text-sm font-medium">自动探测</p>
                <p className="mt-1 text-xs text-default-500">
                  关闭时只响应“立即探测”，不会产生后台流量
                </p>
              </div>
              <Switch
                isSelected={form.enabled}
                onValueChange={(enabled) => setForm({ ...form, enabled })}
              />
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setFormOpen(false)}>
              取消
            </Button>
            <Button
              isLoading={busy === "preflight"}
              variant="flat"
              onPress={() => void checkTarget()}
            >
              检查目标
            </Button>
            <Button
              color="primary"
              isLoading={busy === "save" || busy === "preflight"}
              onPress={() => void save()}
            >
              {preflight && !preflight.reachable
                ? "仍然保存（监控故障）"
                : "保存任务"}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={detailOpen}
        scrollBehavior="inside"
        size="5xl"
        onOpenChange={setDetailOpen}
      >
        <ModalContent>
          <ModalHeader>质量详情</ModalHeader>
          <ModalBody className="space-y-5">
            {detail && (
              <>
                <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
                  <div className="min-w-0">
                    <h3 className="truncate text-lg font-semibold">
                      {detailTask?.name}
                    </h3>
                    <p className="mt-1 truncate text-sm text-default-500">
                      {detailTask?.sourceNodeName} → {detailTask?.targetHost}:
                      {detailTask?.port}
                    </p>
                  </div>
                  <Tabs
                    selectedKey={range}
                    size="sm"
                    onSelectionChange={(key) =>
                      detailTask &&
                      void loadDetail(detailTask, String(key) as Range)
                    }
                  >
                    <Tab key="24h" title="24 小时" />
                    <Tab key="7d" title="7 天" />
                    <Tab key="30d" title="30 天" />
                  </Tabs>
                </div>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
                  {[
                    ["P50", metric(detail.summary.p50Ms)],
                    ["P95", metric(detail.summary.p95Ms)],
                    ["P99", metric(detail.summary.p99Ms)],
                    ["抖动", metric(detail.summary.jitterMs)],
                    ["失败率", `${detail.summary.failureRate.toFixed(2)}%`],
                    ["中断", `${detail.summary.interruptions} 次`],
                    ["运行", `${detail.summary.runs} 轮`],
                  ].map(([label, value]) => (
                    <div key={label} className="border border-divider p-3">
                      <p className="text-xs text-default-500">{label}</p>
                      <p className="mt-1 text-base font-semibold">{value}</p>
                    </div>
                  ))}
                </div>
                <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_260px]">
                  <section className="min-w-0 border-y border-divider py-4">
                    <div className="mb-4">
                      <h4 className="font-medium">时延趋势</h4>
                      <p className="mt-1 text-xs text-default-500">
                        每轮 P50、P95、P99 和抖动
                      </p>
                    </div>
                    <div className="h-72 w-full">
                      <ResponsiveContainer height="100%" width="100%">
                        <LineChart
                          data={detail.runs}
                          margin={{ top: 8, right: 8, left: -22, bottom: 0 }}
                        >
                          <CartesianGrid
                            stroke="rgba(148,163,184,.22)"
                            strokeDasharray="3 3"
                            vertical={false}
                          />
                          <XAxis
                            dataKey="startedAt"
                            minTickGap={30}
                            tick={{ fontSize: 10 }}
                            tickFormatter={(value) =>
                              new Date(value).toLocaleString("zh-CN", {
                                month: "numeric",
                                day: "numeric",
                                hour: "2-digit",
                                minute: "2-digit",
                                hour12: false,
                              })
                            }
                          />
                          <YAxis tick={{ fontSize: 10 }} unit=" ms" />
                          <Tooltip
                            formatter={(value) =>
                              `${Number(value || 0).toFixed(2)} ms`
                            }
                            labelFormatter={(value) => timeText(Number(value))}
                          />
                          <Legend />
                          <Line
                            connectNulls
                            dataKey="p50Ms"
                            dot={false}
                            name="P50"
                            stroke="#10b981"
                            type="monotone"
                          />
                          <Line
                            connectNulls
                            dataKey="p95Ms"
                            dot={false}
                            name="P95"
                            stroke="#3b82f6"
                            type="monotone"
                          />
                          <Line
                            connectNulls
                            dataKey="p99Ms"
                            dot={false}
                            name="P99"
                            stroke="#f43f5e"
                            type="monotone"
                          />
                          <Line
                            connectNulls
                            dataKey="jitterMs"
                            dot={false}
                            name="抖动"
                            stroke="#f59e0b"
                            type="monotone"
                          />
                        </LineChart>
                      </ResponsiveContainer>
                    </div>
                  </section>
                  <aside className="space-y-3 border-y border-divider py-4">
                    <h4 className="font-medium">阶段平均</h4>
                    {[
                      ["TCP 建连", detail.summary.tcpAvgMs],
                      ["TLS 握手", detail.summary.tlsAvgMs],
                      ["首字节 TTFB", detail.summary.ttfbAvgMs],
                    ].map(([label, value]) => (
                      <div
                        key={String(label)}
                        className="flex items-center justify-between gap-3 border-b border-divider pb-3 text-sm"
                      >
                        <span className="text-default-500">{label}</span>
                        <strong>{metric(Number(value))}</strong>
                      </div>
                    ))}
                    <p className="text-xs leading-5 text-default-500">
                      TCP 代表网络与端口建立连接；TLS 只适用于 TLS/HTTPS；TTFB
                      只适用于 HTTP/HTTPS。
                    </p>
                  </aside>
                </div>
                <div className="grid gap-4 lg:grid-cols-3">
                  <Comparison items={detail.ipComparison} title="IPv4 / IPv6" />
                  <Comparison items={detail.lineComparison} title="来源线路" />
                  <Comparison items={detail.hourComparison} title="小时段" />
                </div>
                {detail.runs.length === 0 && (
                  <div className="py-10 text-center text-default-400">
                    当前时间范围还没有探测数据
                  </div>
                )}
              </>
            )}
          </ModalBody>
          <ModalFooter>
            <Button
              startContent={<Download size={16} />}
              variant="flat"
              onPress={() => void downloadReport()}
            >
              下载报告
            </Button>
            <Button color="primary" onPress={() => setDetailOpen(false)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

function Comparison({
  title,
  items,
}: {
  title: string;
  items: QualityLabDetail["ipComparison"];
}) {
  return (
    <section className="min-w-0 border border-divider p-4">
      <h4 className="font-medium">{title}</h4>
      <div className="mt-3 space-y-3">
        {items.length === 0 ? (
          <p className="text-sm text-default-400">等待建立对比数据</p>
        ) : (
          items.slice(0, 12).map((item) => (
            <div
              key={String(item.label)}
              className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-3 text-sm"
            >
              <div className="min-w-0">
                <p className="truncate font-medium">
                  {typeof item.label === "number"
                    ? `${item.label}:00`
                    : String(item.label).toUpperCase()}
                </p>
                <p className="mt-1 text-xs text-default-500">
                  {item.runs} 轮 · 失败{" "}
                  {Number(item.failureRate || 0).toFixed(1)}%
                </p>
              </div>
              <span className="text-right text-xs">
                <strong>{metric(item.p95Ms)}</strong>
                <br />
                <span className="text-default-500">
                  抖动 {metric(item.jitterMs)}
                </span>
              </span>
            </div>
          ))
        )}
      </div>
    </section>
  );
}
