import { useEffect, useMemo, useState } from "react";
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
import { Select, SelectItem } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import { Tooltip } from "@heroui/tooltip";
import {
  CircleCheck,
  History,
  Pause,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  ShieldCheck,
  Stethoscope,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  checkNftForward,
  deleteNftForward,
  getNftForwardEvents,
  getNftForwardOverview,
  preflightNftForward,
  rollbackNftForward,
  saveNftForward,
  toggleNftForward,
  type NftForwardEvent,
  type NftForwardForm,
  type NftForwardNatMode,
  type NftForwardOverview,
  type NftForwardPreflight,
  type NftForwardProtocol,
  type NftForwardRule,
} from "@/api";
import { formatBytesCompact, formatDateTime } from "@/utils/format";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import PageShell from "@/components/page-shell";
import StatsCard from "@/components/ui/stats-card";

type RuleForm = {
  id?: number;
  name: string;
  nodeId: string;
  listenAddress: string;
  listenPort: string;
  protocol: NftForwardProtocol;
  targetAddress: string;
  targetPort: string;
  natMode: NftForwardNatMode;
  sourceCidrs: string;
  enabled: boolean;
};

const emptyOverview: NftForwardOverview = {
  rules: [],
  nodes: [],
  summary: { total: 0, active: 0, paused: 0, errors: 0, packets: 0, bytes: 0 },
  minimumAgentVersion: "2.44.0",
};

const emptyForm = (): RuleForm => ({
  name: "",
  nodeId: "",
  listenAddress: "0.0.0.0",
  listenPort: "",
  protocol: "tcp",
  targetAddress: "",
  targetPort: "",
  natMode: "masquerade",
  sourceCidrs: "",
  enabled: true,
});

const stateLabel: Record<string, string> = {
  provisioning: "正在应用",
  active: "运行中",
  paused: "已暂停",
  error: "异常",
  delete_pending: "等待删除",
};

const stateColor = (
  state: string,
): "success" | "warning" | "danger" | "default" => {
  if (state === "active") return "success";
  if (state === "error" || state === "delete_pending") return "danger";
  if (state === "provisioning") return "warning";

  return "default";
};

const protocolLabel = (protocol: string) =>
  protocol === "tcp_udp" ? "TCP + UDP" : protocol.toUpperCase();

const formatBytes = formatBytesCompact;

const timeText = (value?: number) => formatDateTime(value, "尚未同步");

export default function NftForwardPage() {
  const [data, setData] = useState<NftForwardOverview>(emptyOverview);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | number | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<RuleForm>(emptyForm());
  const [preflight, setPreflight] = useState<NftForwardPreflight | null>(null);
  const [eventsOpen, setEventsOpen] = useState(false);
  const [events, setEvents] = useState<NftForwardEvent[]>([]);
  const [eventRule, setEventRule] = useState<NftForwardRule | null>(null);

  const rules = useMemo(
    () =>
      [...data.rules].sort(
        (left, right) =>
          left.nodeName.localeCompare(right.nodeName, "zh-CN") ||
          left.listenPort - right.listenPort ||
          left.id - right.id,
      ),
    [data.rules],
  );

  const load = async (quiet = false) => {
    if (!quiet) setLoading(true);
    const response = await getNftForwardOverview();

    if (!quiet) setLoading(false);
    if (response.code !== 0)
      return toast.error(response.msg || "读取 nftables 转发失败");
    setData(response.data || emptyOverview);
  };

  useEffect(() => {
    void load();
  }, []);

  const openForm = (rule?: NftForwardRule) => {
    setPreflight(null);
    setForm(
      rule
        ? {
            id: rule.id,
            name: rule.name,
            nodeId: String(rule.nodeId),
            listenAddress: rule.listenAddress || "0.0.0.0",
            listenPort: String(rule.listenPort),
            protocol: rule.protocol,
            targetAddress: rule.targetAddress,
            targetPort: String(rule.targetPort),
            natMode: rule.natMode,
            sourceCidrs: rule.sourceCidrs || "",
            enabled: rule.enabled,
          }
        : emptyForm(),
    );
    setFormOpen(true);
  };

  const payload = (): NftForwardForm | null => {
    const listenPort = Number(form.listenPort);
    const targetPort = Number(form.targetPort);

    if (!form.name.trim() || !form.nodeId || !form.targetAddress.trim()) {
      toast.error("请填写规则名称、执行节点和目标 IPv4");

      return null;
    }
    if (
      !Number.isInteger(listenPort) ||
      listenPort < 1 ||
      listenPort > 65535 ||
      !Number.isInteger(targetPort) ||
      targetPort < 1 ||
      targetPort > 65535
    ) {
      toast.error("入口端口和目标端口必须在 1-65535 之间");

      return null;
    }

    return {
      id: form.id,
      name: form.name.trim(),
      nodeId: Number(form.nodeId),
      listenAddress: form.listenAddress.trim() || "0.0.0.0",
      listenPort,
      protocol: form.protocol,
      targetAddress: form.targetAddress.trim(),
      targetPort,
      natMode: form.natMode,
      sourceCidrs: form.sourceCidrs.trim(),
      enabled: form.enabled,
    };
  };

  const runPreflight = async () => {
    const request = payload();

    if (!request) return;
    setBusy("preflight");
    setPreflight(null);
    const response = await preflightNftForward(request);

    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || "环境检查失败");
    setPreflight(response.data);
    toast.success("节点、端口和 nftables 环境检查通过");
  };

  const save = async () => {
    const request = payload();

    if (!request) return;
    setBusy("save");
    const response = await saveNftForward(request);

    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    toast.success(request.enabled ? "规则已原子应用" : "规则已保存为暂停状态");
    setFormOpen(false);
    await load(true);
  };

  const toggle = async (rule: NftForwardRule) => {
    const enabled = !rule.enabled;

    if (!enabled && !window.confirm(`暂停“${rule.name}”？新连接将不再转发。`))
      return;
    setBusy(rule.id);
    const response = await toggleNftForward(rule.id, enabled);

    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || "状态切换失败");
    toast.success(enabled ? "规则已恢复" : "规则已暂停");
    await load(true);
  };

  const check = async (rule: NftForwardRule) => {
    setBusy(rule.id);
    const response = await checkNftForward(rule.id);

    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || "检查失败");
    toast.success("规则状态、计数器和目标连通性已刷新");
    await load(true);
  };

  const rollback = async (rule: NftForwardRule) => {
    if (
      !window.confirm(`将“${rule.name}”回退到上次成功配置？当前配置会被替换。`)
    )
      return;
    setBusy(rule.id);
    const response = await rollbackNftForward(rule.id);

    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || "回退失败");
    toast.success("已恢复到上次成功配置");
    await load(true);
  };

  const remove = async (rule: NftForwardRule) => {
    if (
      !window.confirm(
        `删除“${rule.name}”？Agent 确认规则移除前，端口不会释放。`,
      )
    )
      return;
    setBusy(rule.id);
    const response = await deleteNftForward(rule.id);

    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("规则已从 Agent 删除，端口已释放");
    await load(true);
  };

  const showEvents = async (rule: NftForwardRule) => {
    setEventRule(rule);
    setEventsOpen(true);
    setEvents([]);
    const response = await getNftForwardEvents(rule.id);

    if (response.code !== 0) return toast.error(response.msg || "读取事件失败");
    setEvents(response.data || []);
  };

  return (
    <PageShell className="space-y-5" width="wide">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">
            nftables 端口转发
          </h1>
        </div>
        <div className="flex gap-2">
          <Tooltip content="刷新规则和计数器">
            <Button
              isIconOnly
              aria-label="刷新"
              variant="flat"
              onPress={() => load()}
            >
              <RefreshCw size={17} />
            </Button>
          </Tooltip>
          <Button
            color="primary"
            startContent={<Plus size={17} />}
            onPress={() => openForm()}
          >
            新建规则
          </Button>
        </div>
      </header>

      <section className="grid grid-cols-2 gap-4 xl:grid-cols-5">
        <StatsCard
          color="primary"
          icon={<ShieldCheck className="h-5 w-5" />}
          title="规则"
          value={data.summary.total}
        />
        <StatsCard
          color="success"
          icon={<Play className="h-5 w-5" />}
          title="运行中"
          value={data.summary.active}
        />
        <StatsCard
          color="default"
          icon={<Pause className="h-5 w-5" />}
          title="已暂停"
          value={data.summary.paused}
        />
        <StatsCard
          color={data.summary.errors ? "danger" : "default"}
          icon={<TriangleAlert className="h-5 w-5" />}
          title="异常"
          value={data.summary.errors}
        />
        <StatsCard
          color="secondary"
          icon={<RefreshCw className="h-5 w-5" />}
          title="当前计数"
          value={formatBytes(data.summary.bytes)}
        />
      </section>

      <section className="border-y border-divider py-4 text-sm">
        <div className="flex gap-3">
          <ShieldCheck className="mt-0.5 shrink-0 text-primary" size={19} />
          <div>
            <p className="font-medium">独立规则域，不接管 GOST</p>
            <p className="mt-1 leading-6 text-default-500">
              只管理 Linux 节点中的 <code>table ip cloudnest_nat</code>
              。创建前同时检查面板端口账本、服务器真实监听和外部
              DNAT；每次变更先校验再原子应用，失败时 Agent 保留上一份成功规则。
            </p>
          </div>
        </div>
      </section>

      {loading ? (
        <div className="flex min-h-40 items-center justify-center">
          <Spinner />
        </div>
      ) : rules.length === 0 ? (
        <div className="flex min-h-40 flex-col items-center justify-center gap-3 border-b border-divider text-default-500">
          <ShieldCheck size={30} />
          <span>尚未创建 nftables 转发规则</span>
        </div>
      ) : (
        <ResourceCardGrid>
          {rules.map((rule) => {
            const failing = rule.state === "error";

            return (
              <ResourceCard
                key={rule.id}
                actions={[
                  {
                    label: "编辑",
                    icon: <Pencil size={14} />,
                    color: "primary",
                    onPress: () => openForm(rule),
                  },
                  {
                    label: "检查",
                    icon: <Stethoscope size={14} />,
                    color: "warning",
                    onPress: () => check(rule),
                    isLoading: busy === rule.id,
                  },
                  {
                    label: "删除",
                    icon: <Trash2 size={14} />,
                    color: "danger",
                    onPress: () => remove(rule),
                  },
                ]}
                blocks={[
                  {
                    label: "公网入口",
                    status: {
                      text: protocolLabel(rule.protocol),
                      color: "primary",
                    },
                    rows: [
                      {
                        label: "入口",
                        value: `${rule.publicHost || rule.listenAddress}:${rule.listenPort}`,
                      },
                    ],
                  },
                  {
                    label: "转发目标",
                    danger: failing,
                    rows: [
                      {
                        label: "目标",
                        value: `${rule.targetAddress}:${rule.targetPort}`,
                      },
                    ],
                  },
                ]}
                chips={[
                  {
                    text:
                      rule.natMode === "masquerade"
                        ? "标准 NAT"
                        : "保留来源 IP",
                    color: "primary",
                  },
                  { text: `${rule.packetCount || 0} 包` },
                  { text: `↓${formatBytes(rule.byteCount)}`, color: "success" },
                ]}
                danger={failing}
                facts={[
                  { label: "监听地址", value: rule.listenAddress },
                  {
                    label: "来源限制",
                    value: rule.sourceCidrs
                      ? `${rule.sourceCidrs.split(/\s+/).length} 个网段`
                      : "允许任意来源",
                  },
                  { label: "节点", value: rule.nodeName },
                  { label: "Agent", value: rule.agentVersion || "未知" },
                ]}
                headerExtra={
                  rule.rollbackAvailable ? (
                    <Tooltip content="回退到上次成功配置">
                      <Button
                        isIconOnly
                        aria-label="回退"
                        className="h-6 w-6 min-w-6"
                        color="warning"
                        size="sm"
                        variant="light"
                        onPress={() => rollback(rule)}
                      >
                        <RotateCcw size={14} />
                      </Button>
                    </Tooltip>
                  ) : undefined
                }
                notice={
                  rule.lastError || rule.lastWarning
                    ? {
                        icon: <TriangleAlert size={15} />,
                        label: rule.lastError ? "最近错误" : "提示",
                        value: (
                          <span title={rule.lastError || rule.lastWarning}>
                            {rule.lastError || rule.lastWarning}
                          </span>
                        ),
                        tone: rule.lastError ? "danger" : "warning",
                        trailing: (
                          <Tooltip content="事件记录">
                            <Button
                              isIconOnly
                              aria-label="事件记录"
                              className="h-7 w-7 min-w-7"
                              size="sm"
                              variant="light"
                              onPress={() => showEvents(rule)}
                            >
                              <History size={15} />
                            </Button>
                          </Tooltip>
                        ),
                      }
                    : {
                        icon: <CircleCheck size={15} />,
                        label: "规则状态",
                        value: stateLabel[rule.state] || rule.state,
                        tone: rule.state === "active" ? "success" : "default",
                        trailing: (
                          <Tooltip content="事件记录">
                            <Button
                              isIconOnly
                              aria-label="事件记录"
                              className="h-7 w-7 min-w-7"
                              size="sm"
                              variant="light"
                              onPress={() => showEvents(rule)}
                            >
                              <History size={15} />
                            </Button>
                          </Tooltip>
                        ),
                      }
                }
                status={{
                  text: stateLabel[rule.state] || rule.state,
                  color: stateColor(rule.state),
                }}
                subtitles={[
                  `${rule.nodeName} · ${protocolLabel(rule.protocol)}`,
                ]}
                title={rule.name}
                toggle={{
                  isSelected: Boolean(rule.enabled),
                  onChange: () => toggle(rule),
                  isDisabled: busy === rule.id,
                  label: rule.enabled ? "暂停" : "恢复",
                }}
              />
            );
          })}
        </ResourceCardGrid>
      )}

      <Modal
        isOpen={formOpen}
        scrollBehavior="inside"
        size="3xl"
        onOpenChange={setFormOpen}
      >
        <ModalContent>
          <ModalHeader>
            {form.id ? "编辑 nftables 转发" : "新建 nftables 转发"}
          </ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 md:grid-cols-2">
              <Input
                isRequired
                label="规则名称"
                placeholder="Web 服务内核转发"
                value={form.name}
                onValueChange={(value) => {
                  setForm({ ...form, name: value });
                  setPreflight(null);
                }}
              />
              <Select
                isRequired
                description={`Linux Agent ${data.minimumAgentVersion} 或更高版本`}
                isDisabled={Boolean(form.id)}
                label="执行节点"
                selectedKeys={form.nodeId ? [form.nodeId] : []}
                onSelectionChange={(keys) => {
                  setForm({
                    ...form,
                    nodeId: String(Array.from(keys)[0] || ""),
                  });
                  setPreflight(null);
                }}
              >
                {data.nodes.map((node) => (
                  <SelectItem key={String(node.id)} textValue={node.name}>
                    {node.name} ·{" "}
                    {!node.online
                      ? "离线"
                      : !node.compatible
                        ? "需要升级"
                        : "可用"}{" "}
                    · {node.version || "未知版本"}
                  </SelectItem>
                ))}
              </Select>
            </div>
            <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_150px_180px]">
              <Input
                description="0.0.0.0 表示监听节点全部 IPv4"
                label="监听 IPv4"
                value={form.listenAddress}
                onValueChange={(value) => {
                  setForm({ ...form, listenAddress: value });
                  setPreflight(null);
                }}
              />
              <Input
                isRequired
                label="入口端口"
                max={65535}
                min={1}
                type="number"
                value={form.listenPort}
                onValueChange={(value) => {
                  setForm({ ...form, listenPort: value });
                  setPreflight(null);
                }}
              />
              <Select
                label="协议"
                selectedKeys={[form.protocol]}
                onSelectionChange={(keys) => {
                  setForm({
                    ...form,
                    protocol: String(
                      Array.from(keys)[0] || "tcp",
                    ) as NftForwardProtocol,
                  });
                  setPreflight(null);
                }}
              >
                <SelectItem key="tcp">TCP</SelectItem>
                <SelectItem key="udp">UDP</SelectItem>
                <SelectItem key="tcp_udp">TCP + UDP</SelectItem>
              </Select>
            </div>
            <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_150px]">
              <Input
                isRequired
                description="第一版只接受固定 IPv4，不解析域名"
                label="目标 IPv4"
                placeholder="10.0.0.8"
                value={form.targetAddress}
                onValueChange={(value) => {
                  setForm({ ...form, targetAddress: value });
                  setPreflight(null);
                }}
              />
              <Input
                isRequired
                label="目标端口"
                max={65535}
                min={1}
                type="number"
                value={form.targetPort}
                onValueChange={(value) => {
                  setForm({ ...form, targetPort: value });
                  setPreflight(null);
                }}
              />
            </div>
            <Select
              label="NAT 模式"
              selectedKeys={[form.natMode]}
              onSelectionChange={(keys) => {
                setForm({
                  ...form,
                  natMode: String(
                    Array.from(keys)[0] || "masquerade",
                  ) as NftForwardNatMode,
                });
                setPreflight(null);
              }}
            >
              <SelectItem key="masquerade" textValue="标准 NAT">
                标准 NAT · 目标无需配置返回路由
              </SelectItem>
              <SelectItem key="preserve_source" textValue="保留来源 IP">
                保留来源 IP · 目标必须经本节点返回流量
              </SelectItem>
            </Select>
            <Textarea
              description="留空允许任意来源；最多 64 个 CIDR，可用换行、逗号或空格分隔"
              label="来源 IPv4 白名单"
              minRows={3}
              placeholder={"203.0.113.8/32\n198.51.100.0/24"}
              value={form.sourceCidrs}
              onValueChange={(value) => {
                setForm({ ...form, sourceCidrs: value });
                setPreflight(null);
              }}
            />
            <Switch
              isSelected={form.enabled}
              onValueChange={(value) => setForm({ ...form, enabled: value })}
            >
              保存后立即启用
            </Switch>
            {form.natMode === "preserve_source" && (
              <div className="border-y border-warning-200 bg-warning-50 px-3 py-3 text-sm text-warning-700 dark:bg-warning-900/10 dark:text-warning-300">
                保留来源 IP 不做
                SNAT。目标主机的默认网关或静态路由必须把回程交回此节点，否则连接只会收到单向数据。
              </div>
            )}
            {preflight && (
              <div className="border-y border-divider py-3 text-sm">
                <p className="flex items-center gap-2 font-medium text-success">
                  <CircleCheck size={16} />
                  预检通过 · {preflight.nftVersion || "nftables 可用"}
                </p>
                <p className="mt-1 text-default-500">
                  IPv4 转发{" "}
                  {preflight.ipv4Forwarding ? "已启用" : "将在应用时启用"}
                  {preflight.firewallManager
                    ? ` · 检测到 ${preflight.firewallManager}`
                    : ""}
                </p>
                {preflight.warnings?.map((item) => (
                  <p key={item} className="mt-1 text-warning">
                    {item}
                  </p>
                ))}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setFormOpen(false)}>
              取消
            </Button>
            <Button
              isLoading={busy === "preflight"}
              startContent={<ShieldCheck size={16} />}
              variant="flat"
              onPress={runPreflight}
            >
              环境预检
            </Button>
            <Button color="primary" isLoading={busy === "save"} onPress={save}>
              保存并应用
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={eventsOpen}
        scrollBehavior="inside"
        size="3xl"
        onOpenChange={setEventsOpen}
      >
        <ModalContent>
          <ModalHeader>{eventRule?.name || "规则"} · 事件记录</ModalHeader>
          <ModalBody>
            {events.length === 0 ? (
              <div className="flex min-h-40 items-center justify-center text-default-500">
                尚无事件记录
              </div>
            ) : (
              <div className="divide-y divide-divider border-y border-divider">
                {events.map((event) => (
                  <div
                    key={event.id}
                    className="grid gap-2 py-3 sm:grid-cols-[110px_110px_minmax(0,1fr)_170px] sm:items-center"
                  >
                    <Chip
                      className="w-fit"
                      color={
                        event.status === "success"
                          ? "success"
                          : event.status === "failed"
                            ? "danger"
                            : "warning"
                      }
                      size="sm"
                      variant="flat"
                    >
                      {event.status === "success"
                        ? "成功"
                        : event.status === "failed"
                          ? "失败"
                          : "处理中"}
                    </Chip>
                    <span className="text-sm">{event.eventType}</span>
                    <span className="text-sm text-default-600">
                      {event.detail || "-"}
                    </span>
                    <span className="text-xs text-default-500 sm:text-right">
                      {timeText(event.createdTime)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setEventsOpen(false)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
