import { useCallback, useEffect, useMemo, useState } from "react";
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
import { Select, SelectItem } from "@heroui/select";
import {
  Activity,
  ArrowUp,
  Gauge,
  History,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  deleteBandwidthTestTask,
  getBandwidthTestDetail,
  getBandwidthTestOverview,
  runBandwidthTestTask,
  saveBandwidthTestTask,
  type BandwidthTestOverview,
  type BandwidthTestRun,
  type BandwidthTestTask,
  type BandwidthTestTaskInput,
} from "@/api";
import PageShell from "@/components/page-shell";
import PageHelp from "@/components/page-help";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";
import { formatDateTime } from "@/utils/format";

type FormState = {
  id?: number;
  name: string;
  sourceNodeId: string;
  targetNodeId: string;
  listenPort: string;
  protocol: BandwidthTestTask["protocol"];
  direction: BandwidthTestTask["direction"];
  streams: string;
  durationSeconds: string;
  maximumMegabytes: string;
  retentionDays: string;
};
const emptyForm: FormState = {
  name: "",
  sourceNodeId: "",
  targetNodeId: "",
  listenPort: "5201",
  protocol: "tcp",
  direction: "bidirectional",
  streams: "4",
  durationSeconds: "10",
  maximumMegabytes: "512",
  retentionDays: "30",
};
const initial: BandwidthTestOverview = {
  minimumAgentVersion: "2.44.1",
  nodes: [],
  tasks: [],
  summary: { total: 0, running: 0, success: 0, failed: 0, peakMbps: 0 },
};
const bool = (value: boolean | number) => value === true || value === 1;
const speed = (value?: number) =>
  value && value > 0
    ? value >= 1000
      ? `${(value / 1000).toFixed(2)} Gbps`
      : `${value.toFixed(1)} Mbps`
    : "-";
const timeText = (value?: number) => formatDateTime(value, "尚未测试");
const directionText = (value: string) =>
  value === "upload" ? "上传" : value === "download" ? "下载" : "双向";
const percentText = (value?: number) => `${Number(value || 0).toFixed(2)}%`;
const latencyText = (value?: number) =>
  value && value > 0 ? `${value.toFixed(2)} ms` : "-";
const stateMeta = (task: BandwidthTestTask) =>
  bool(task.running) || task.lastStatus === "running"
    ? { label: "测试中", color: "primary" as const }
    : task.lastStatus === "success"
      ? { label: "已完成", color: "success" as const }
      : task.lastStatus === "failed"
        ? { label: "失败", color: "danger" as const }
        : { label: "未测试", color: "default" as const };

export default function BandwidthTestPage() {
  const [data, setData] = useState(initial);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyTask, setHistoryTask] = useState<BandwidthTestTask | null>(
    null,
  );
  const [runs, setRuns] = useState<BandwidthTestRun[]>([]);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const response = await getBandwidthTestOverview();

    if (!quiet) setLoading(false);
    if (response.code === 0) setData(response.data);
    else if (!quiet) toast.error(response.msg || "加载带宽测试中心失败");
  }, []);

  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (!document.hidden && data.tasks.some((task) => bool(task.running)))
        void load(true);
    }, 2000);

    return () => window.clearInterval(timer);
  }, [data.tasks, load]);
  const onlineNodes = useMemo(
    () => data.nodes.filter((node) => node.status === 1),
    [data.nodes],
  );

  const openCreate = () => {
    setForm(emptyForm);
    setFormOpen(true);
  };
  const openEdit = (task: BandwidthTestTask) => {
    setForm({
      id: task.id,
      name: task.name,
      sourceNodeId: String(task.sourceNodeId),
      targetNodeId: String(task.targetNodeId),
      listenPort: String(task.listenPort),
      protocol: task.protocol || "tcp",
      direction: task.direction,
      streams: String(task.streams),
      durationSeconds: String(task.durationSeconds),
      maximumMegabytes: String(task.maximumMegabytes),
      retentionDays: String(task.retentionDays),
    });
    setFormOpen(true);
  };
  const input = (): BandwidthTestTaskInput => ({
    id: form.id,
    name: form.name.trim(),
    sourceNodeId: Number(form.sourceNodeId),
    targetNodeId: Number(form.targetNodeId),
    listenPort: Number(form.listenPort),
    protocol: form.protocol,
    direction: form.direction,
    streams: Number(form.streams),
    durationSeconds: Number(form.durationSeconds),
    maximumMegabytes: Number(form.maximumMegabytes),
    retentionDays: Number(form.retentionDays),
  });
  const save = async () => {
    if (!form.name.trim() || !form.sourceNodeId || !form.targetNodeId)
      return toast.error("请填写任务名称并选择来源和目标节点");
    if (form.sourceNodeId === form.targetNodeId)
      return toast.error("来源和目标节点不能相同");
    if (form.direction === "bidirectional" && Number(form.streams) < 2)
      return toast.error("双向测试至少需要 2 个并发流");
    setBusy("save");
    const response = await saveBandwidthTestTask(input());

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    setData(response.data);
    setFormOpen(false);
    toast.success("带宽测试任务已保存");
  };
  const run = async (task: BandwidthTestTask) => {
    setBusy(`run-${task.id}`);
    const response = await runBandwidthTestTask(task.id);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "无法开始测试");
    toast.success("测试已开始，目标端口会在结束后自动关闭");
    void load(true);
  };
  const remove = async (task: BandwidthTestTask) => {
    if (!window.confirm(`删除“${task.name}”及全部历史结果？`)) return;
    setBusy(`delete-${task.id}`);
    const response = await deleteBandwidthTestTask(task.id);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    setData(response.data);
    toast.success("已删除");
  };
  const history = async (task: BandwidthTestTask) => {
    setBusy(`history-${task.id}`);
    const response = await getBandwidthTestDetail(task.id);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "加载历史失败");
    setHistoryTask(task);
    setRuns(response.data.runs);
    setHistoryOpen(true);
  };

  return (
    <PageShell className="space-y-6" width="wide">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-1.5">
            <h1 className="text-2xl font-bold text-foreground">带宽测试</h1>
            <PageHelp>两台在线 Agent 直接传输真实数据，测试端口仅在本轮测试期间开放。</PageHelp>
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
            color="primary"
            startContent={<Plus size={17} />}
            onPress={openCreate}
          >
            新建测试
          </Button>
        </div>
      </header>
      <section className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        <StatsCard
          color="primary"
          icon={<Gauge size={20} />}
          title="测试任务"
          value={loading ? "-" : data.summary.total}
        />
        <StatsCard
          color="warning"
          icon={<Activity size={20} />}
          title="运行中"
          value={loading ? "-" : data.summary.running}
        />
        <StatsCard
          color="success"
          icon={<History size={20} />}
          title="成功 / 失败"
          value={
            loading ? "-" : `${data.summary.success} / ${data.summary.failed}`
          }
        />
        <StatsCard
          color="secondary"
          icon={<ArrowUp size={20} />}
          title="历史峰值"
          value={loading ? "-" : speed(data.summary.peakMbps)}
        />
      </section>
      {data.tasks.length === 0 && !loading ? (
        <div className="flex min-h-72 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-divider text-default-400">
          <Gauge size={36} />
          <p>尚未创建真实带宽测试</p>
          <Button size="sm" variant="flat" onPress={openCreate}>
            创建第一项测试
          </Button>
        </div>
      ) : (
        <ResourceCardGrid>
          {data.tasks.map((task) => {
            const state = stateMeta(task);
            const udp = task.protocol === "udp";
            const running = bool(task.running);

            return (
              <ResourceCard
                key={task.id}
                actions={[
                  {
                    label: "开始",
                    icon: <Play size={14} />,
                    color: "primary",
                    isDisabled: running,
                    isLoading: busy === `run-${task.id}`,
                    onPress: () => void run(task),
                  },
                  {
                    label: "历史",
                    icon: <History size={14} />,
                    isLoading: busy === `history-${task.id}`,
                    onPress: () => void history(task),
                  },
                  {
                    label: "编辑",
                    icon: <Pencil size={14} />,
                    isDisabled: running,
                    onPress: () => openEdit(task),
                  },
                  {
                    label: "删除",
                    icon: <Trash2 size={14} />,
                    color: "danger",
                    isDisabled: running,
                    isLoading: busy === `delete-${task.id}`,
                    onPress: () => void remove(task),
                  },
                ]}
                blocks={[
                  {
                    label: "吞吐",
                    rows: [
                      { label: "上传", value: speed(task.uploadMbps) },
                      { label: "下载", value: speed(task.downloadMbps) },
                      { label: "总计", value: speed(task.totalMbps) },
                    ],
                  },
                ]}
                chips={[
                  {
                    text: `${(task.protocol || "tcp").toUpperCase()} · ${directionText(task.direction)} · ${task.streams} 流`,
                    color: "primary",
                  },
                ]}
                danger={state.color === "danger"}
                facts={
                  udp
                    ? [
                        {
                          label: "丢包率",
                          value: percentText(task.packetLossPercent),
                        },
                        { label: "抖动", value: latencyText(task.jitterMs) },
                        {
                          label: "乱序包",
                          value: Number(
                            task.outOfOrderPackets || 0,
                          ).toLocaleString(),
                        },
                        {
                          label: "最近运行",
                          value: timeText(task.latestStartedAt || task.lastRunAt),
                        },
                      ]
                    : [
                        { label: "RTT", value: latencyText(task.rttMs) },
                        {
                          label: "重传率",
                          value: percentText(task.retransmissionRate),
                        },
                        {
                          label: "重传次数",
                          value: Number(task.retransmits || 0).toLocaleString(),
                        },
                        {
                          label: "最近运行",
                          value: timeText(task.latestStartedAt || task.lastRunAt),
                        },
                      ]
                }
                notice={
                  task.lastError
                    ? {
                        icon: <TriangleAlert size={14} />,
                        label: "最近错误",
                        value: (
                          <span title={task.lastError}>{task.lastError}</span>
                        ),
                        tone: "danger",
                      }
                    : undefined
                }
                status={{ text: state.label, color: state.color }}
                subtitles={[
                  `${task.sourceNodeName} → ${task.targetNodeName}:${task.listenPort}`,
                  `${task.durationSeconds} 秒 · 最多 ${task.maximumMegabytes} MB`,
                ]}
                title={task.name}
              />
            );
          })}
        </ResourceCardGrid>
      )}

      <Modal isOpen={formOpen} size="2xl" onOpenChange={setFormOpen}>
        <ModalContent>
          <ModalHeader>{form.id ? "编辑带宽测试" : "新建带宽测试"}</ModalHeader>
          <ModalBody className="space-y-4">
            <Input
              isRequired
              label="任务名称"
              placeholder="香港节点到圣何塞出口"
              value={form.name}
              onValueChange={(value) => setForm({ ...form, name: value })}
            />
            <div className="grid gap-4 sm:grid-cols-2">
              <Select
                isRequired
                label="来源节点"
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
                    {node.name} · {node.serverIp || node.ip}
                  </SelectItem>
                ))}
              </Select>
              <Select
                isRequired
                label="目标节点"
                selectedKeys={form.targetNodeId ? [form.targetNodeId] : []}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    targetNodeId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {onlineNodes
                  .filter((node) => String(node.id) !== form.sourceNodeId)
                  .map((node) => (
                    <SelectItem key={String(node.id)} textValue={node.name}>
                      {node.name} · {node.serverIp || node.ip}
                    </SelectItem>
                  ))}
              </Select>
            </div>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <Select
                label="传输协议"
                selectedKeys={[form.protocol]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    protocol: String(
                      Array.from(keys)[0] || "tcp",
                    ) as FormState["protocol"],
                  })
                }
              >
                <SelectItem key="tcp">TCP</SelectItem>
                <SelectItem key="udp">UDP</SelectItem>
              </Select>
              <Select
                label="测试方向"
                selectedKeys={[form.direction]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    direction: String(
                      Array.from(keys)[0] || "bidirectional",
                    ) as FormState["direction"],
                  })
                }
              >
                <SelectItem key="download">下载（目标到来源）</SelectItem>
                <SelectItem key="upload">上传（来源到目标）</SelectItem>
                <SelectItem key="bidirectional">双向同时</SelectItem>
              </Select>
              <Select
                label="并发流数"
                selectedKeys={[form.streams]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    streams: String(Array.from(keys)[0] || "4"),
                  })
                }
              >
                {["1", "2", "4", "8"].map((value) => (
                  <SelectItem key={value}>{value} 流</SelectItem>
                ))}
              </Select>
              <Input
                label="目标监听端口"
                max={65535}
                min={1}
                type="number"
                value={form.listenPort}
                onValueChange={(value) =>
                  setForm({ ...form, listenPort: value })
                }
              />
            </div>
            <div className="grid gap-4 sm:grid-cols-3">
              <Input
                label="测试时长（秒）"
                max={30}
                min={1}
                type="number"
                value={form.durationSeconds}
                onValueChange={(value) =>
                  setForm({ ...form, durationSeconds: value })
                }
              />
              <Input
                label="流量上限（MB）"
                max={2048}
                min={16}
                type="number"
                value={form.maximumMegabytes}
                onValueChange={(value) =>
                  setForm({ ...form, maximumMegabytes: value })
                }
              />
              <Input
                label="历史保留（天）"
                max={365}
                min={1}
                type="number"
                value={form.retentionDays}
                onValueChange={(value) =>
                  setForm({ ...form, retentionDays: value })
                }
              />
            </div>
            <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">
              目标服务器防火墙需允许所填 {form.protocol.toUpperCase()}{" "}
              端口。一次性令牌仅在本轮测试有效，监听会在测试完成或超时后自动关闭。
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setFormOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "save"}
              onPress={() => void save()}
            >
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={historyOpen}
        scrollBehavior="inside"
        size="5xl"
        onOpenChange={setHistoryOpen}
      >
        <ModalContent>
          <ModalHeader>{historyTask?.name} · 测试历史</ModalHeader>
          <ModalBody>
            {runs.length === 0 ? (
              <div className="py-16 text-center text-default-400">
                尚无测试结果
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[1100px] text-left text-sm">
                  <thead className="border-b border-divider text-xs text-default-500">
                    <tr>
                      <th className="p-3">时间</th>
                      <th className="p-3">协议 / 方向</th>
                      <th className="p-3">上传</th>
                      <th className="p-3">下载</th>
                      <th className="p-3">总吞吐</th>
                      <th className="p-3">RTT</th>
                      <th className="p-3">丢包 / 重传</th>
                      <th className="p-3">抖动 / 乱序</th>
                      <th className="p-3">CPU / 内存</th>
                      <th className="p-3">结果</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runs.map((run) => {
                      const udp = run.protocol === "udp";

                      return (
                        <tr key={run.id} className="border-b border-divider/60">
                          <td className="p-3 whitespace-nowrap">
                            {timeText(run.startedAt)}
                          </td>
                          <td className="p-3">
                            {(run.protocol || "tcp").toUpperCase()} ·{" "}
                            {directionText(run.direction)} · {run.streams} 流
                          </td>
                          <td className="p-3">{speed(run.uploadMbps)}</td>
                          <td className="p-3">{speed(run.downloadMbps)}</td>
                          <td className="p-3 font-medium">
                            {speed(run.totalMbps)}
                          </td>
                          <td className="p-3">{latencyText(run.rttMs)}</td>
                          <td className="p-3">
                            {udp
                              ? `${percentText(run.packetLossPercent)} · ${Number(run.packetsLost || 0).toLocaleString()} 包`
                              : `${percentText(run.retransmissionRate)} · ${Number(run.retransmits || 0).toLocaleString()} 次`}
                          </td>
                          <td className="p-3">
                            {udp
                              ? `${latencyText(run.jitterMs)} · ${Number(run.outOfOrderPackets || 0).toLocaleString()} 包`
                              : "-"}
                          </td>
                          <td className="p-3">
                            {Number(run.cpuPercent || 0).toFixed(1)}% /{" "}
                            {Number(run.memoryPercent || 0).toFixed(1)}%
                          </td>
                          <td className="p-3">
                            <Chip
                              color={
                                run.status === "success" ? "success" : "danger"
                              }
                              size="sm"
                              variant="flat"
                            >
                              {run.status === "success"
                                ? `${run.successfulStreams}/${run.streams} 成功`
                                : run.error || "失败"}
                            </Chip>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
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
