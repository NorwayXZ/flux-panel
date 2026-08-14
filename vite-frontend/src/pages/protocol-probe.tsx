import { useEffect, useMemo, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Spinner } from "@heroui/spinner";
import {
  Activity,
  CheckCircle2,
  Clock3,
  Gauge,
  History,
  Info,
  Network,
  XCircle,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  getProtocolProbeHistory,
  getProtocolProbeOverview,
  runProtocolProbe,
  type ProtocolProbeOverview,
  type ProtocolProbeOverviewItem,
  type ProtocolProbeRun,
} from "@/api";

const MiB = 1024 * 1024;

const formatMbps = (value?: number) =>
  value === undefined || value === null ? "未测" : `${value.toFixed(2)} Mbps`;

const formatMs = (value?: number) =>
  value === undefined || value === null ? "未测" : `${value.toFixed(1)} ms`;

const formatBytes = (value?: number) => {
  if (!value) return "0 B";
  if (value >= 1024 * 1024 * 1024)
    return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GiB`;
  if (value >= 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MiB`;

  return `${(value / 1024).toFixed(1)} KiB`;
};

const formatTime = (value?: number) =>
  value ? new Date(value).toLocaleString() : "暂无记录";

const protocolLabel = (item: ProtocolProbeOverviewItem) =>
  item.capability.label || item.proxy.proxyType;

const runStatus = (run?: ProtocolProbeRun | null) => {
  if (!run) return { label: "未测试", color: "default" as const };
  if (run.status === "success")
    return { label: "可用", color: "success" as const };
  if (run.status === "running")
    return { label: "测试中", color: "warning" as const };

  return { label: "失败", color: "danger" as const };
};

export default function ProtocolProbePage() {
  const [overview, setOverview] = useState<ProtocolProbeOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [runningId, setRunningId] = useState<number | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [downloadMiB, setDownloadMiB] = useState("32");
  const [uploadMiB, setUploadMiB] = useState("16");
  const [history, setHistory] = useState<ProtocolProbeRun[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  const load = async (keepContent = true) => {
    if (!keepContent || !overview) setLoading(true);
    try {
      const response = await getProtocolProbeOverview();

      if (response.code !== 0 || !response.data)
        throw new Error(response.msg || "加载协议测速中心失败");
      setOverview(response.data);
      if (!selectedId && response.data.items.length > 0) {
        setSelectedId(response.data.items[0].proxy.id);
      }
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "加载协议测速中心失败",
      );
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load(false);
  }, []);

  const selected = useMemo(
    () => overview?.items.find((item) => item.proxy.id === selectedId),
    [overview, selectedId],
  );

  const loadHistory = async (proxyId: number) => {
    setSelectedId(proxyId);
    setHistoryLoading(true);
    try {
      const response = await getProtocolProbeHistory(proxyId);

      if (response.code !== 0) throw new Error(response.msg || "读取历史失败");
      setHistory(response.data || []);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "读取测速历史失败");
    } finally {
      setHistoryLoading(false);
    }
  };

  const startProbe = async (item: ProtocolProbeOverviewItem) => {
    const download = Number(downloadMiB);
    const upload = Number(uploadMiB);

    if (!Number.isInteger(download) || download < 1 || download > 128)
      return toast.error("下载测试量应为 1-128 MiB");
    if (!Number.isInteger(upload) || upload < 1 || upload > 128)
      return toast.error("上传测试量应为 1-128 MiB");
    setRunningId(item.proxy.id);
    setSelectedId(item.proxy.id);
    try {
      const response = await runProtocolProbe({
        proxyId: item.proxy.id,
        downloadBytes: download * MiB,
        uploadBytes: upload * MiB,
      });

      if (response.code !== 0) throw new Error(response.msg || "协议测速失败");
      toast.success(`${protocolLabel(item)} 测试完成`);
      await loadHistory(item.proxy.id);
      await load();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "协议测速失败");
      await load();
    } finally {
      setRunningId(null);
    }
  };

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-3 border-b border-divider pb-5 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="text-sm text-default-500">接入与观测</p>
          <h1 className="mt-1 text-2xl font-semibold">协议测速中心</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-default-500">
            对已创建的代理协议执行 Agent
            侧可用性、握手延迟、下载吞吐和上传吞吐测试。它不等同于手机或家庭宽带到节点的最终速度。
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <Input
            className="w-32"
            label="下载 MiB"
            min={1}
            max={128}
            type="number"
            value={downloadMiB}
            onValueChange={setDownloadMiB}
          />
          <Input
            className="w-32"
            label="上传 MiB"
            min={1}
            max={128}
            type="number"
            value={uploadMiB}
            onValueChange={setUploadMiB}
          />
        </div>
      </header>

      <section className="flex items-start gap-3 border border-warning-200 bg-warning-50 px-4 py-3 text-sm leading-6 text-warning-800 dark:border-warning-500/30 dark:bg-warning-500/10 dark:text-warning-200">
        <Info className="mt-1 shrink-0" size={17} />
        <div>
          <div className="font-medium">探针位置和结果含义</div>
          <div>
            第一版由协议所在节点的 Agent
            发起测试，主要验证协议运行时和出口链路是否可用。要测“本地电脑/手机通过该协议”的真实体验，后续还需要本地
            Connector 或客户端探针。
          </div>
        </div>
      </section>

      {loading ? (
        <div className="flex min-h-64 items-center justify-center">
          <Spinner />
        </div>
      ) : !overview || overview.items.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500">
          <Network size={30} />
          <span>暂无可测速的私人代理协议</span>
        </div>
      ) : (
        <section className="overflow-hidden border border-divider">
          <div className="hidden grid-cols-[1.5fr_1.2fr_0.9fr_0.9fr_1.15fr_auto] gap-4 bg-default-50 px-4 py-3 text-xs text-default-500 lg:grid">
            <span>协议节点</span>
            <span>能力</span>
            <span>握手 / 延迟</span>
            <span>下载 / 上传</span>
            <span>最近测试</span>
            <span>操作</span>
          </div>
          {overview.items.map((item) => {
            const proxy = item.proxy;
            const latest = item.latest;
            const status = runStatus(latest);
            const supported = item.capability.status === "supported";
            const running = runningId === proxy.id;

            return (
              <div
                key={proxy.id}
                className={`grid gap-4 border-t border-divider px-4 py-4 first:border-t-0 lg:grid-cols-[1.5fr_1.2fr_0.9fr_0.9fr_1.15fr_auto] lg:items-center ${selectedId === proxy.id ? "bg-primary-50/40 dark:bg-primary-500/5" : ""}`}
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="break-all font-medium">{proxy.name}</span>
                    <Chip color={status.color} size="sm" variant="flat">
                      {status.label}
                    </Chip>
                  </div>
                  <div className="mt-1 break-all text-xs text-default-500">
                    {proxy.nodeName} · {protocolLabel(item)}
                  </div>
                  <div className="mt-1 break-all font-mono text-xs text-default-500">
                    {proxy.publicHost || "本机监听"}:{proxy.listenPort}
                  </div>
                </div>
                <div className="text-sm">
                  <div className="flex items-center gap-2">
                    {supported ? (
                      <CheckCircle2 className="text-success" size={16} />
                    ) : (
                      <Clock3 className="text-default-400" size={16} />
                    )}
                    <span>{supported ? "可执行探针" : "等待 Agent 探针"}</span>
                  </div>
                  <div className="mt-1 text-xs leading-5 text-default-500">
                    {item.capability.message}
                  </div>
                </div>
                <div className="text-sm">
                  <div>{formatMs(latest?.handshakeMs)}</div>
                  <div className="mt-1 text-xs text-default-500">
                    首响应 {formatMs(latest?.latencyMs)}
                  </div>
                </div>
                <div className="text-sm">
                  <div>{formatMbps(latest?.downloadMbps)}</div>
                  <div className="mt-1 text-xs text-default-500">
                    上行 {formatMbps(latest?.uploadMbps)}
                  </div>
                </div>
                <div className="text-sm text-default-500">
                  <div>{formatTime(latest?.finishedAt)}</div>
                  {latest?.error && (
                    <div className="mt-1 line-clamp-2 text-xs text-danger">
                      {latest.error}
                    </div>
                  )}
                </div>
                <div className="flex gap-2 lg:justify-end">
                  <Button
                    color="primary"
                    isDisabled={!supported}
                    isLoading={running}
                    size="sm"
                    startContent={!running && <Gauge size={16} />}
                    onPress={() => void startProbe(item)}
                  >
                    测试
                  </Button>
                  <Button
                    isIconOnly
                    aria-label={`查看 ${proxy.name} 测试历史`}
                    size="sm"
                    title="查看测试历史"
                    onPress={() => void loadHistory(proxy.id)}
                  >
                    <History size={16} />
                  </Button>
                </div>
              </div>
            );
          })}
        </section>
      )}

      {selected && (
        <section className="border border-divider">
          <div className="flex flex-col gap-2 border-b border-divider px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h2 className="font-semibold">
                {selected.proxy.name} 的测试历史
              </h2>
              <p className="mt-1 text-xs text-default-500">
                每次测试最多保留本次请求的结果和 Agent
                版本，便于判断线路是否逐渐劣化。
              </p>
            </div>
            <div className="flex items-center gap-2 text-xs text-default-500">
              <Activity size={15} />
              {selected.capability.label}
            </div>
          </div>
          {historyLoading ? (
            <div className="flex min-h-32 items-center justify-center">
              <Spinner size="sm" />
            </div>
          ) : history.length === 0 ? (
            <div className="px-4 py-8 text-sm text-default-500">
              暂无历史结果
            </div>
          ) : (
            <div className="divide-y divide-divider">
              {history.map((run) => {
                const status = runStatus(run);

                return (
                  <div
                    key={run.id || run.runId || run.startedAt}
                    className="grid gap-3 px-4 py-4 text-sm sm:grid-cols-[1fr_0.8fr_1fr_1fr_1.4fr]"
                  >
                    <div className="flex items-center gap-2">
                      {status.color === "success" ? (
                        <CheckCircle2 className="text-success" size={16} />
                      ) : (
                        <XCircle className="text-danger" size={16} />
                      )}
                      <span>{status.label}</span>
                      <span className="text-xs text-default-500">
                        {formatTime(run.finishedAt)}
                      </span>
                    </div>
                    <div>
                      <span className="text-default-500">握手</span>{" "}
                      {formatMs(run.handshakeMs)}
                    </div>
                    <div>
                      <span className="text-default-500">延迟</span>{" "}
                      {formatMs(run.latencyMs)}
                    </div>
                    <div>
                      <span className="text-default-500">吞吐</span>{" "}
                      {formatMbps(run.downloadMbps)} /{" "}
                      {formatMbps(run.uploadMbps)}
                    </div>
                    <div className="text-xs text-default-500">
                      {formatBytes(run.downloadBytesActual)} 下载 ·{" "}
                      {formatBytes(run.uploadBytesActual)} 上传
                      {run.error && (
                        <span className="mt-1 block break-words text-danger">
                          {run.error}
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
