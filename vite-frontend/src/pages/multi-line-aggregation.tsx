import type { ReactNode } from "react";

import { useEffect, useMemo, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Checkbox } from "@heroui/checkbox";
import { Input } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
  useDisclosure,
} from "@heroui/modal";
import { Select, SelectItem } from "@heroui/select";
import { Switch } from "@heroui/switch";
import {
  Activity,
  Calculator,
  CirclePlay,
  FlaskConical,
  Gauge,
  History,
  Pencil,
  Plus,
  RefreshCw,
  Route,
  Trash2,
  TriangleAlert,
  Wrench,
  X,
} from "lucide-react";

import {
  AggregationEvent,
  AggregationGroup,
  AggregationMode,
  AggregationOverview,
  AggregationProtocol,
  deleteAggregation,
  deployAggregation,
  getAggregationEvents,
  getAggregationOverview,
  recalculateAggregation,
  repairAggregation,
  saveAggregation,
  testAggregation,
  toggleAggregation,
} from "@/api";
import PageShell from "@/components/page-shell";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";
import { Tooltip } from "@heroui/tooltip";
import { formatDateTime } from "@/utils/format";

type FormState = {
  id?: number;
  name: string;
  tunnelIds: number[];
  listenPort: string;
  remoteAddr: string;
  protocolMode: AggregationProtocol;
  mode: AggregationMode;
  autoWeight: boolean;
  minimumHealthyPaths: string;
  manualWeights: Record<number, number>;
};
type ApiResult<T> = { code: number; msg: string; data: T };

const emptyForm = (): FormState => ({
  name: "",
  tunnelIds: [],
  listenPort: "",
  remoteAddr: "",
  protocolMode: "tcp_udp",
  mode: "balanced",
  autoWeight: true,
  minimumHealthyPaths: "1",
  manualWeights: {},
});

const modeLabel: Record<AggregationMode, string> = {
  speed: "速度优先",
  balanced: "均衡",
  stability: "稳定优先",
};
const statusLabel: Record<string, string> = {
  active: "运行中",
  paused: "已暂停",
  degraded: "线路不足",
  error: "部署失败",
  provisioning: "待部署",
};
const eventLabel: Record<string, string> = {
  create: "创建",
  update: "更新",
  deploy: "部署",
  repair: "修复",
  pause: "暂停",
  resume: "恢复",
  validation: "线路验证",
  weight_change: "权重调整",
  health: "健康变化",
};

const number = (value: unknown, fallback = 0) =>
  Number.isFinite(Number(value)) ? Number(value) : fallback;
const speed = (value?: number) =>
  value == null
    ? "待测试"
    : `${number(value).toFixed(value >= 100 ? 0 : 1)} Mbps`;
const latency = (value?: number) =>
  value == null ? "-" : `${number(value).toFixed(1)} ms`;
const percent = (value?: number) =>
  value == null ? "-" : `${number(value).toFixed(2)}%`;
const bytes = (value?: number) => {
  const amount = number(value);

  if (!amount) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(
    Math.floor(Math.log(amount) / Math.log(1024)),
    units.length - 1,
  );

  return `${(amount / Math.pow(1024, index)).toFixed(index > 2 ? 1 : 0)} ${units[index]}`;
};
const timeText = (value?: number) => formatDateTime(value, "-");

export default function MultiLineAggregationPage() {
  const [data, setData] = useState<AggregationOverview | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm());
  const [busy, setBusy] = useState<string>("");
  const [error, setError] = useState("");
  const [events, setEvents] = useState<AggregationEvent[]>([]);
  const [eventGroup, setEventGroup] = useState<AggregationGroup | null>(null);
  const formModal = useDisclosure();
  const eventModal = useDisclosure();

  const load = async () => {
    setError("");
    try {
      const response = await getAggregationOverview();

      if (response.code === 0) setData(response.data);
      else setError(response.msg);
    } catch (reason: any) {
      setError(reason?.message || "加载多线路并发调度失败");
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const groupedTunnels = useMemo(() => {
    const groups = new Map<
      number,
      { name: string; items: AggregationOverview["tunnels"] }
    >();

    for (const tunnel of data?.tunnels || []) {
      const current = groups.get(tunnel.entryNodeId) || {
        name: tunnel.entryNodeName,
        items: [],
      };

      current.items.push(tunnel);
      groups.set(tunnel.entryNodeId, current);
    }

    return Array.from(groups.entries());
  }, [data?.tunnels]);

  const openCreate = () => {
    setForm(emptyForm());
    formModal.onOpen();
  };
  const openEdit = (group: AggregationGroup) => {
    setForm({
      id: group.id,
      name: group.name,
      tunnelIds: group.members.map((member) => member.tunnel_id),
      listenPort: String(group.listen_port),
      remoteAddr: group.remote_addr,
      protocolMode: group.protocol_mode,
      mode: group.mode,
      autoWeight: Boolean(group.auto_weight),
      minimumHealthyPaths: String(group.minimum_healthy_paths),
      manualWeights: Object.fromEntries(
        group.members.map((member) => [
          member.tunnel_id,
          number(member.manual_weight, 100),
        ]),
      ),
    });
    formModal.onOpen();
  };

  const toggleTunnel = (id: number) => {
    const tunnel = data?.tunnels.find((item) => item.id === id);

    if (!tunnel) return;
    const selectedEntry = data?.tunnels.find((item) =>
      form.tunnelIds.includes(item.id),
    )?.entryNodeId;

    if (
      !form.tunnelIds.includes(id) &&
      selectedEntry != null &&
      selectedEntry !== tunnel.entryNodeId
    ) {
      setError("同一个并发调度组的所有线路必须使用相同入口节点");

      return;
    }
    setError("");
    setForm((current) => ({
      ...current,
      tunnelIds: current.tunnelIds.includes(id)
        ? current.tunnelIds.filter((item) => item !== id)
        : [...current.tunnelIds, id],
      manualWeights: {
        ...current.manualWeights,
        [id]: current.manualWeights[id] || 100,
      },
    }));
  };

  const submit = async () => {
    if (!form.name.trim() || !form.remoteAddr.trim() || !form.listenPort) {
      setError("请填写名称、入口端口和目标地址");

      return;
    }
    if (form.tunnelIds.length < 2) {
      setError("至少选择两条相同入口节点的线路");

      return;
    }
    setBusy("save");
    setError("");
    try {
      const response = await saveAggregation({
        id: form.id,
        name: form.name.trim(),
        tunnelIds: form.tunnelIds,
        listenPort: number(form.listenPort),
        remoteAddr: form.remoteAddr.trim(),
        protocolMode: form.protocolMode,
        mode: form.mode,
        autoWeight: form.autoWeight,
        minimumHealthyPaths: number(form.minimumHealthyPaths, 1),
        manualWeights: form.manualWeights,
      });

      if (response.code !== 0) {
        setError(response.msg || "保存失败");

        return;
      }
      setData(response.data);
      formModal.onClose();
    } catch (reason: any) {
      setError(reason?.message || "保存失败");
    } finally {
      setBusy("");
    }
  };

  const action = async (
    key: string,
    operation: () => Promise<ApiResult<AggregationOverview>>,
  ) => {
    setBusy(key);
    setError("");
    try {
      const response = await operation();

      if (response.code === 0) setData(response.data);
      else setError(response.msg || "操作失败");
    } catch (reason: any) {
      setError(reason?.message || "操作失败");
    } finally {
      setBusy("");
    }
  };

  const validate = async (group: AggregationGroup) => {
    setBusy(`test-${group.id}`);
    setError("");
    try {
      const response = await testAggregation(group.id);

      if (response.code !== 0) {
        setError(response.msg);

        return;
      }
      await openEvents(group);
    } catch (reason: any) {
      setError(reason?.message || "线路验证失败");
    } finally {
      setBusy("");
    }
  };

  const openEvents = async (group: AggregationGroup) => {
    setEventGroup(group);
    eventModal.onOpen();
    try {
      const response = await getAggregationEvents(group.id);

      if (response.code === 0) setEvents(response.data);
      else setError(response.msg);
    } catch (reason: any) {
      setError(reason?.message || "读取历史失败");
    }
  };

  const remove = async (group: AggregationGroup) => {
    if (!window.confirm(`删除并发调度组“${group.name}”及其底层转发？`)) return;
    await action(`delete-${group.id}`, () => deleteAggregation(group.id));
  };

  return (
    <PageShell width="wide" className="space-y-6 pb-10">
      <div className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <Route className="h-6 w-6 text-primary" />
            <h1 className="text-2xl font-semibold">多线路并发调度</h1>
          </div>
          <p className="mt-1 text-sm text-default-500">
            一个公网入口，自适应调度多条完整线路上的并发连接；单个 TCP
            连接不会被拆分到多条线路。
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            isIconOnly
            title="刷新"
            variant="flat"
            onPress={() => void load()}
          >
            <RefreshCw className="h-4 w-4" />
          </Button>
          <Button
            color="primary"
            startContent={<Plus className="h-4 w-4" />}
            onPress={openCreate}
          >
            新建调度组
          </Button>
        </div>
      </div>

      {error && (
        <div className="flex items-center justify-between border border-danger-200 bg-danger-50 px-4 py-3 text-sm text-danger-700 dark:bg-danger-950/30">
          <span>{error}</span>
          <Button
            isIconOnly
            size="sm"
            variant="light"
            onPress={() => setError("")}
          >
            <X className="h-4 w-4" />
          </Button>
        </div>
      )}

      <section className="border border-primary-200 bg-primary-50/50 p-4 dark:border-primary-500/20 dark:bg-primary-500/5 sm:p-5">
        <div className="flex items-start gap-3">
          <Activity className="mt-0.5 shrink-0 text-primary" size={18} />
          <div>
            <h2 className="text-sm font-semibold">多连接分配</h2>
            <p className="mt-1 text-xs leading-5 text-default-600 dark:text-default-300">
              同一个入口同时承载多条完整线路，按连接分配权重；单个 TCP
              连接不会被拆分，单连接速度仍受实际线路限制。
            </p>
          </div>
        </div>
      </section>

      <section aria-label="多线路并发调度概况" className="space-y-3">
        <div className="flex items-center justify-between gap-2">
          <div>
            <h2 className="text-sm font-semibold">运行概况</h2>
            <p className="mt-1 text-xs text-default-500">
              调度组、健康线路和预计并发能力
            </p>
          </div>
          <Chip size="sm" variant="flat">
            实时状态
          </Chip>
        </div>
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-3 2xl:grid-cols-5">
          <StatsCard
            color="primary"
            icon={<Route className="h-5 w-5" />}
            title="调度组"
            value={data?.summary.groups || 0}
          />
          <StatsCard
            color="success"
            icon={<CirclePlay className="h-5 w-5" />}
            title="运行中"
            value={data?.summary.active || 0}
          />
          <StatsCard
            color="secondary"
            icon={<Activity className="h-5 w-5" />}
            title="健康线路"
            value={data?.summary.healthyPaths || 0}
          />
          <StatsCard
            color="primary"
            icon={<Gauge className="h-5 w-5" />}
            title="预估并发容量"
            value={speed(data?.summary.estimatedCapacityMbps)}
          />
          <StatsCard
            color={data?.summary.degraded ? "warning" : "default"}
            icon={<TriangleAlert className="h-5 w-5" />}
            title="降级组"
            value={data?.summary.degraded || 0}
          />
        </div>
      </section>

      {!data ? (
        <div className="py-24 text-center text-default-400">正在加载...</div>
      ) : data.groups.length === 0 ? (
        <div className="border border-divider bg-content1 py-20 text-center">
          <Gauge className="mx-auto h-9 w-9 text-default-300" />
          <div className="mt-3 font-medium">尚未创建并发调度入口</div>
        </div>
      ) : (
        <section aria-label="并发调度组" className="space-y-3">
          <div className="flex flex-wrap items-end justify-between gap-2">
            <div>
              <div className="flex items-center gap-2">
                <Route className="text-primary" size={17} />
                <h2 className="text-sm font-semibold">并发调度组</h2>
              </div>
              <p className="mt-1 text-xs text-default-500">
                每个调度组对应一个公网入口和多个可分配线路
              </p>
            </div>
            <Chip size="sm" variant="flat">
              {data.groups.length} 个调度组
            </Chip>
          </div>
          <ResourceCardGrid>
            {data.groups.map((group) => {
              const stateColor =
                group.state === "active"
                  ? "success"
                  : group.state === "paused"
                    ? "default"
                    : group.state === "degraded"
                      ? "warning"
                      : "danger";
              const smallIcon = (
                label: string,
                icon: ReactNode,
                onPress: () => void,
                key: string,
                isDisabled = false,
              ) => (
                <Tooltip content={label}>
                  <Button
                    isIconOnly
                    aria-label={label}
                    className="h-6 w-6 min-w-6"
                    isDisabled={isDisabled}
                    isLoading={busy === key}
                    size="sm"
                    variant="light"
                    onPress={onPress}
                  >
                    {icon}
                  </Button>
                </Tooltip>
              );

              return (
                <ResourceCard
                  key={group.id}
                  actions={[
                    ...(!group.forward_id
                      ? [
                          {
                            label: "部署",
                            icon: <CirclePlay className="h-3.5 w-3.5" />,
                            color: "success" as const,
                            isLoading: busy === `deploy-${group.id}`,
                            onPress: () =>
                              void action(`deploy-${group.id}`, () =>
                                deployAggregation(group.id),
                              ),
                          },
                        ]
                      : []),
                    {
                      label: "编辑",
                      icon: <Pencil className="h-3.5 w-3.5" />,
                      color: "primary",
                      onPress: () => openEdit(group),
                    },
                    {
                      label: "验证",
                      icon: <FlaskConical className="h-3.5 w-3.5" />,
                      color: "warning",
                      isLoading: busy === `test-${group.id}`,
                      onPress: () => void validate(group),
                    },
                    {
                      label: "删除",
                      icon: <Trash2 className="h-3.5 w-3.5" />,
                      color: "danger",
                      isLoading: busy === `delete-${group.id}`,
                      onPress: () => void remove(group),
                    },
                  ]}
                  blocks={[
                    {
                      label: "公网入口",
                      status: {
                        text:
                          group.protocol_mode === "tcp_udp"
                            ? "TCP + UDP"
                            : group.protocol_mode.toUpperCase(),
                        color: "primary",
                      },
                      rows: [
                        {
                          label: "入口",
                          value: `${group.entry_server_ip || group.entry_ip || group.entry_node_name}:${group.listen_port}`,
                        },
                      ],
                    },
                    {
                      label: `并发线路 · ${group.healthyPaths}/${group.members.length} 健康`,
                      danger:
                        group.members.length > 0 && group.healthyPaths === 0,
                      rows: group.members.map((member) => ({
                        label: member.tunnel_name,
                        mono: false,
                        title:
                          member.health_status === "unhealthy"
                            ? `${member.failure_segment || "线路探测失败"} ${member.failure_address || ""} ${member.failure_message || member.last_error || ""}`
                            : `${member.in_node_name} → ${member.out_node_name}`,
                        value: (
                          <span
                            className={
                              member.health_status === "healthy"
                                ? "text-success"
                                : member.health_status === "unhealthy"
                                  ? "text-danger"
                                  : "text-warning"
                            }
                          >
                            {member.health_status === "healthy"
                              ? "健康"
                              : member.health_status === "unhealthy"
                                ? "异常"
                                : "待确认"}
                            <span className="text-default-500">
                              {" "}
                              · 权重 {member.effective_weight} ·{" "}
                              {speed(member.bandwidth_mbps)} ·{" "}
                              {latency(member.latency_ms)} · 丢包{" "}
                              {percent(member.packet_loss_percent)}
                            </span>
                          </span>
                        ),
                      })),
                    },
                  ]}
                  chips={[
                    { text: modeLabel[group.mode], color: "secondary" },
                    {
                      text: `↕${bytes(number(group.in_flow) + number(group.out_flow))}`,
                      color: "success",
                    },
                  ]}
                  danger={group.state === "error"}
                  facts={[
                    { label: "落地目标", value: group.remote_addr },
                    {
                      label: "预估容量",
                      value: speed(group.estimatedCapacityMbps),
                    },
                    {
                      label: "自动权重",
                      value: group.auto_weight ? "已开启" : "手动",
                    },
                    {
                      label: "最近计算",
                      value: timeText(group.last_calculated_at),
                    },
                  ]}
                  headerExtra={
                    <>
                      {smallIcon(
                        "重新计算权重",
                        <Calculator className="h-3.5 w-3.5" />,
                        () =>
                          void action(`calc-${group.id}`, () =>
                            recalculateAggregation(group.id),
                          ),
                        `calc-${group.id}`,
                      )}
                      {smallIcon(
                        "修复底层线路",
                        <Wrench className="h-3.5 w-3.5" />,
                        () =>
                          void action(`repair-${group.id}`, () =>
                            repairAggregation(group.id),
                          ),
                        `repair-${group.id}`,
                        !group.forward_id,
                      )}
                    </>
                  }
                  notice={{
                    icon: group.last_error ? (
                      <TriangleAlert className="h-4 w-4" />
                    ) : (
                      <Activity className="h-4 w-4" />
                    ),
                    label: group.last_error ? "最近错误" : "调度状态",
                    value: group.last_error ? (
                      <span title={group.last_error}>{group.last_error}</span>
                    ) : (
                      `${group.healthyPaths} 条线路分担新连接`
                    ),
                    tone: group.last_error ? "warning" : "success",
                    trailing: (
                      <Tooltip content="历史记录">
                        <Button
                          isIconOnly
                          aria-label="历史记录"
                          className="h-7 w-7 min-w-7"
                          size="sm"
                          variant="light"
                          onPress={() => void openEvents(group)}
                        >
                          <History className="h-4 w-4" />
                        </Button>
                      </Tooltip>
                    ),
                  }}
                  status={{
                    text: statusLabel[group.state] || group.state,
                    color: stateColor,
                  }}
                  subtitles={[`并发会话聚合 · ${group.entry_node_name}`]}
                  title={group.name}
                  toggle={{
                    isSelected: Boolean(group.enabled),
                    isDisabled:
                      !group.forward_id || busy === `toggle-${group.id}`,
                    label: group.enabled ? "暂停" : "恢复",
                    onChange: () =>
                      void action(`toggle-${group.id}`, () =>
                        toggleAggregation(group.id, !group.enabled),
                      ),
                  }}
                />
              );
            })}
          </ResourceCardGrid>
        </section>
      )}

      <Modal
        isOpen={formModal.isOpen}
        scrollBehavior="inside"
        size="3xl"
        onOpenChange={formModal.onOpenChange}
      >
        <ModalContent>
          <ModalHeader>
            {form.id ? "编辑并发调度组" : "新建多线路并发调度"}
          </ModalHeader>
          <ModalBody className="gap-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <Input
                label="名称"
                value={form.name}
                onValueChange={(value) => setForm({ ...form, name: value })}
              />
              <Input
                label="公网入口端口"
                max={65535}
                min={1}
                type="number"
                value={form.listenPort}
                onValueChange={(value) =>
                  setForm({ ...form, listenPort: value })
                }
              />
            </div>
            <Input
              label="最终目标地址"
              placeholder="host:port"
              value={form.remoteAddr}
              onValueChange={(value) => setForm({ ...form, remoteAddr: value })}
            />
            <div className="grid gap-4 sm:grid-cols-3">
              <Select
                label="入口协议"
                selectedKeys={[form.protocolMode]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    protocolMode: String(
                      Array.from(keys)[0] || "tcp_udp",
                    ) as AggregationProtocol,
                  })
                }
              >
                <SelectItem key="tcp_udp">TCP + UDP</SelectItem>
                <SelectItem key="tcp">TCP</SelectItem>
                <SelectItem key="udp">UDP</SelectItem>
              </Select>
              <Select
                label="调度模式"
                selectedKeys={[form.mode]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    mode: String(
                      Array.from(keys)[0] || "balanced",
                    ) as AggregationMode,
                  })
                }
              >
                <SelectItem key="speed">速度优先</SelectItem>
                <SelectItem key="balanced">均衡</SelectItem>
                <SelectItem key="stability">稳定优先</SelectItem>
              </Select>
              <Input
                label="最低健康线路"
                max={Math.max(1, form.tunnelIds.length)}
                min={1}
                type="number"
                value={form.minimumHealthyPaths}
                onValueChange={(value) =>
                  setForm({ ...form, minimumHealthyPaths: value })
                }
              />
            </div>
            <div className="flex items-center justify-between border-y border-divider py-3">
              <div>
                <div className="text-sm font-medium">自动权重</div>
                <div className="text-xs text-default-500">
                  综合最近带宽、延迟、丢包和抖动
                </div>
              </div>
              <Switch
                isSelected={form.autoWeight}
                onValueChange={(value) =>
                  setForm({ ...form, autoWeight: value })
                }
              />
            </div>
            <div>
              <div className="mb-2 flex items-center justify-between">
                <span className="text-sm font-medium">调度线路</span>
                <span className="text-xs text-default-500">
                  已选 {form.tunnelIds.length} 条
                </span>
              </div>
              <div className="max-h-72 overflow-y-auto border border-divider">
                {groupedTunnels.map(([entryId, group]) => (
                  <div
                    key={entryId}
                    className="border-b border-divider last:border-0"
                  >
                    <div className="bg-default-100 px-3 py-2 text-xs font-medium">
                      入口：{group.name}
                    </div>
                    {group.items.map((tunnel) => {
                      const selected = form.tunnelIds.includes(tunnel.id);
                      const selectedEntry = data?.tunnels.find((item) =>
                        form.tunnelIds.includes(item.id),
                      )?.entryNodeId;
                      const disabled =
                        !selected &&
                        selectedEntry != null &&
                        selectedEntry !== tunnel.entryNodeId;

                      return (
                        <div
                          key={tunnel.id}
                          className="flex items-center gap-3 border-t border-divider px-3 py-3"
                        >
                          <Checkbox
                            isDisabled={disabled}
                            isSelected={selected}
                            onValueChange={() => toggleTunnel(tunnel.id)}
                          />
                          <div className="min-w-0 flex-1">
                            <div className="truncate text-sm">
                              {tunnel.name}
                            </div>
                            <div className="text-xs text-default-400">
                              {tunnel.entryNodeName} → {tunnel.exitNodeName} ·{" "}
                              {tunnel.protocol || "TCP"}
                            </div>
                          </div>
                          <Chip
                            color={tunnel.online ? "success" : "danger"}
                            size="sm"
                            variant="dot"
                          >
                            {tunnel.online ? "在线" : "离线"}
                          </Chip>
                          {!form.autoWeight && selected && (
                            <Input
                              aria-label="线路权重"
                              className="w-24"
                              max={1000}
                              min={1}
                              size="sm"
                              type="number"
                              value={String(
                                form.manualWeights[tunnel.id] || 100,
                              )}
                              onValueChange={(value) =>
                                setForm((current) => ({
                                  ...current,
                                  manualWeights: {
                                    ...current.manualWeights,
                                    [tunnel.id]: number(value, 100),
                                  },
                                }))
                              }
                            />
                          )}
                        </div>
                      );
                    })}
                  </div>
                ))}
              </div>
            </div>
            <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">
              该功能按权重分配新建连接，适合 YouTube
              分片、下载器和多连接业务；单个 TCP 连接不会被拆分到多条线路。
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={formModal.onClose}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "save"}
              onPress={() => void submit()}
            >
              保存并部署
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={eventModal.isOpen}
        scrollBehavior="inside"
        size="3xl"
        onOpenChange={eventModal.onOpenChange}
      >
        <ModalContent>
          <ModalHeader>{eventGroup?.name} · 事件与测试记录</ModalHeader>
          <ModalBody>
            {events.length === 0 ? (
              <div className="py-16 text-center text-default-400">暂无记录</div>
            ) : (
              <div className="divide-y divide-divider">
                {events.map((event) => (
                  <div key={event.id} className="flex gap-3 py-3">
                    <Activity
                      className={`mt-0.5 h-4 w-4 shrink-0 ${event.status === "success" ? "text-success" : event.status === "warning" ? "text-warning" : "text-danger"}`}
                    />
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2 text-sm">
                        <span className="font-medium">
                          {eventLabel[event.eventType] || event.eventType}
                        </span>
                        <Chip size="sm" variant="flat">
                          {event.status}
                        </Chip>
                        <span className="text-xs text-default-400">
                          {timeText(event.createdTime)}
                        </span>
                      </div>
                      <div className="mt-1 text-sm text-default-600">
                        {event.detail || "-"}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button color="primary" onPress={eventModal.onClose}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
