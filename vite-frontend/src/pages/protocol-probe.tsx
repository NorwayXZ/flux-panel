import { useEffect, useMemo, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import {
  Activity,
  CheckCircle2,
  Clock3,
  Gauge,
  History,
  Info,
  Network,
  Trash2,
  XCircle,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  deleteProtocolProbeExternalTarget,
  getProtocolProbeHistory,
  getProtocolProbeOverview,
  runProtocolProbe,
  saveProtocolProbeExternalTarget,
  type ProtocolProbeExternalCreateRequest,
  type ProtocolProbeOverview,
  type ProtocolProbeOverviewItem,
  type ProtocolProbeRun,
  type ProtocolProbeTarget,
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

const protocolLabel = (target: ProtocolProbeTarget) => {
  const labels: Record<string, string> = {
    socks5: "SOCKS5",
    http: "HTTP",
    vless_reality: "VLESS + REALITY",
    shadowsocks: "Shadowsocks",
    trojan: "Trojan",
    hysteria2: "Hysteria2",
    tuic: "TUIC v5",
    wireguard: "WireGuard",
  };

  return labels[target.proxyType] || target.proxyType;
};

const runStatus = (run?: ProtocolProbeRun | null) => {
  if (!run) return { label: "未测试", color: "default" as const };
  if (run.status === "success")
    return { label: "可用", color: "success" as const };
  if (run.status === "running")
    return { label: "测试中", color: "warning" as const };

  return { label: "失败", color: "danger" as const };
};

const itemTarget = (item?: ProtocolProbeOverviewItem | null): ProtocolProbeTarget | null => {
  if (!item) return null;
  if (item.target?.targetType && item.target?.targetId != null) return item.target;
  if (item.targetType && item.targetId != null && item.name && item.proxyType) {
    return {
      targetType: item.targetType,
      targetId: item.targetId,
      name: item.name,
      proxyType: item.proxyType,
      host: item.host,
      port: item.port,
      nodeName: item.nodeName,
      source: item.source,
    };
  }
  if (item.proxy) {
    return {
      targetType: "created",
      targetId: item.proxy.id,
      name: item.proxy.name,
      proxyType: item.proxy.proxyType,
      host: item.proxy.publicHost || item.proxy.bindIp || "",
      port: item.proxy.listenPort,
      nodeName: item.proxy.nodeName,
      source: "CloudNest 创建",
    };
  }
  return null;
};

const itemKey = (item?: ProtocolProbeOverviewItem | null) => {
  const target = itemTarget(item);
  return target ? `${target.targetType}:${target.targetId}` : null;
};

const emptyExternalForm: ProtocolProbeExternalCreateRequest = {
  name: "",
  proxyType: "socks5",
  host: "",
  port: 1080,
  username: "",
  password: "",
};

export default function ProtocolProbePage() {
  const [overview, setOverview] = useState<ProtocolProbeOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [runningKey, setRunningKey] = useState<string | null>(null);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [downloadMiB, setDownloadMiB] = useState("32");
  const [uploadMiB, setUploadMiB] = useState("16");
  const [history, setHistory] = useState<ProtocolProbeRun[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [externalForm, setExternalForm] =
    useState<ProtocolProbeExternalCreateRequest>(emptyExternalForm);
  const [externalSaving, setExternalSaving] = useState(false);

  const load = async (keepContent = true) => {
    if (!keepContent || !overview) setLoading(true);
    try {
      const response = await getProtocolProbeOverview();

      if (response.code !== 0 || !response.data)
        throw new Error(response.msg || "加载协议测速中心失败");
      setOverview(response.data);
      if (!selectedKey && response.data.items.length > 0) {
        const first = response.data.items.map((item) => itemTarget(item)).find(Boolean);
        if (first) setSelectedKey(`${first.targetType}:${first.targetId}`);
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
    () =>
      overview?.items.find((item) => itemKey(item) === selectedKey),
    [overview, selectedKey],
  );

  const selectedTarget = useMemo(() => itemTarget(selected), [selected]);

  const loadHistory = async (target: ProtocolProbeTarget) => {
    setSelectedKey(`${target.targetType}:${target.targetId}`);
    setHistoryLoading(true);
    try {
      const response = await getProtocolProbeHistory(
        target.targetType,
        target.targetId,
      );

      if (response.code !== 0) throw new Error(response.msg || "读取历史失败");
      setHistory(response.data || []);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "读取测速历史失败");
    } finally {
      setHistoryLoading(false);
    }
  };

  const startProbe = async (item: ProtocolProbeOverviewItem) => {
    const target = itemTarget(item);
    if (!target) return toast.error("协议目标数据不完整");
    const download = Number(downloadMiB);
    const upload = Number(uploadMiB);

    if (!Number.isInteger(download) || download < 1 || download > 128)
      return toast.error("下载测试量应为 1-128 MiB");
    if (!Number.isInteger(upload) || upload < 1 || upload > 128)
      return toast.error("上传测试量应为 1-128 MiB");

    const key = `${target.targetType}:${target.targetId}`;

    setRunningKey(key);
    setSelectedKey(key);
    try {
      const response = await runProtocolProbe({
        targetType: target.targetType,
        targetId: target.targetId,
        downloadBytes: download * MiB,
        uploadBytes: upload * MiB,
      });

      if (response.code !== 0) throw new Error(response.msg || "协议测速失败");
      toast.success(`${protocolLabel(target)} 测试完成`);
      await loadHistory(target);
      await load();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "协议测速失败");
      await load();
    } finally {
      setRunningKey(null);
    }
  };

  const saveExternal = async () => {
    if (!externalForm.host?.trim()) return toast.error("请填写外部协议地址");
    if (
      !externalForm.port ||
      externalForm.port < 1 ||
      externalForm.port > 65535
    )
      return toast.error("协议端口必须在 1-65535 之间");
    setExternalSaving(true);
    try {
      const response = await saveProtocolProbeExternalTarget({
        ...externalForm,
        name: externalForm.name?.trim(),
        host: externalForm.host.trim(),
        username: externalForm.username?.trim(),
      });

      if (response.code !== 0)
        throw new Error(response.msg || "保存外部协议失败");
      setExternalForm(emptyExternalForm);
      toast.success("外部协议已添加");
      await load();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存外部协议失败");
    } finally {
      setExternalSaving(false);
    }
  };

  const deleteExternal = async (target: ProtocolProbeTarget) => {
    if (target.targetType !== "external") return;
    try {
      const response = await deleteProtocolProbeExternalTarget(target.targetId);

      if (response.code !== 0)
        throw new Error(response.msg || "删除外部协议失败");
      if (selectedKey === `${target.targetType}:${target.targetId}`) {
        setSelectedKey(null);
        setHistory([]);
      }
      toast.success("外部协议已删除");
      await load();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "删除外部协议失败");
    }
  };

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-3 border-b border-divider pb-5 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="text-sm text-default-500">接入与观测</p>
          <h1 className="mt-1 text-2xl font-semibold">协议测速中心</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-default-500">
            通过协议客户端真实连接已创建协议或外部协议，再访问测速地址，测量协议握手、首响应、下载和上传。
            这里不是 Agent 本地测速，也不等同于手机或家庭宽带的最终体验。
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

      <section className="flex items-start gap-3 border border-primary-200 bg-primary-50 px-4 py-3 text-sm leading-6 text-primary-800 dark:border-primary-500/30 dark:bg-primary-500/10 dark:text-primary-200">
        <Info className="mt-1 shrink-0" size={17} />
        <div>
          <div className="font-medium">测速链路</div>
          <div>
            面板服务器作为独立协议客户端，连接列表里的公网地址和端口，再通过该协议访问
            Cloudflare 测速端点。协议所在 Agent
            只负责创建和维护服务，不参与测速流量。
            要测本地电脑或手机体验，请使用“本机单线程测速”。
          </div>
        </div>
      </section>

      <section className="border border-divider p-4">
        <div className="flex flex-col gap-2 border-b border-divider pb-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="font-semibold">添加外部协议</h2>
            <p className="mt-1 text-xs leading-5 text-default-500">
              可保存朋友提供的 SOCKS5/HTTP
              协议。账号密码只在服务端加密保存，不会回显到页面。
            </p>
          </div>
          <Chip color="secondary" size="sm" variant="flat">
            面板协议客户端
          </Chip>
        </div>
        <div className="grid gap-3 pt-4 md:grid-cols-2 xl:grid-cols-6">
          <Input
            className="xl:col-span-1"
            label="名称"
            value={externalForm.name}
            onValueChange={(value) =>
              setExternalForm((current) => ({ ...current, name: value }))
            }
          />
          <Select
            label="协议"
            selectedKeys={[externalForm.proxyType]}
            onSelectionChange={(keys) => {
              const value = String(Array.from(keys)[0] || "socks5") as
                "socks5" | "http";

              setExternalForm((current) => ({ ...current, proxyType: value }));
            }}
          >
            <SelectItem key="socks5">SOCKS5</SelectItem>
            <SelectItem key="http">HTTP</SelectItem>
          </Select>
          <Input
            label="地址"
            placeholder="example.com"
            value={externalForm.host}
            onValueChange={(value) =>
              setExternalForm((current) => ({ ...current, host: value }))
            }
          />
          <Input
            label="端口"
            min={1}
            max={65535}
            type="number"
            value={String(externalForm.port || "")}
            onValueChange={(value) =>
              setExternalForm((current) => ({
                ...current,
                port: Number(value) || 0,
              }))
            }
          />
          <Input
            label="用户名"
            value={externalForm.username}
            onValueChange={(value) =>
              setExternalForm((current) => ({ ...current, username: value }))
            }
          />
          <Input
            label="密码"
            type="password"
            value={externalForm.password}
            onValueChange={(value) =>
              setExternalForm((current) => ({ ...current, password: value }))
            }
          />
        </div>
        <div className="mt-3 flex justify-end">
          <Button
            color="primary"
            isLoading={externalSaving}
            onPress={() => void saveExternal()}
          >
            保存外部协议
          </Button>
        </div>
      </section>

      {loading ? (
        <div className="flex min-h-64 items-center justify-center">
          <Spinner />
        </div>
      ) : !overview || overview.items.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500">
          <Network size={30} />
          <span>暂无协议，请先创建协议或添加外部协议</span>
        </div>
      ) : (
        <section className="overflow-hidden border border-divider">
          <div className="hidden grid-cols-[1.5fr_1.2fr_0.9fr_0.9fr_1.15fr_auto] gap-4 bg-default-50 px-4 py-3 text-xs text-default-500 lg:grid">
            <span>协议目标</span>
            <span>能力</span>
            <span>握手 / 延迟</span>
            <span>下载 / 上传</span>
            <span>最近测试</span>
            <span>操作</span>
          </div>
          {overview.items.map((item) => {
            const target = itemTarget(item);
            if (!target) return null;
            const latest = item.latest;
            const status = runStatus(latest);
            const supported = item.capability?.status === "supported";
            const key = `${target.targetType}:${target.targetId}`;
            const running = runningKey === key;

            return (
              <div
                key={key}
                className={`grid gap-4 border-t border-divider px-4 py-4 first:border-t-0 lg:grid-cols-[1.5fr_1.2fr_0.9fr_0.9fr_1.15fr_auto] lg:items-center ${selectedKey === key ? "bg-primary-50/40 dark:bg-primary-500/5" : ""}`}
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="break-all font-medium">{target.name}</span>
                    <Chip color={status.color} size="sm" variant="flat">
                      {status.label}
                    </Chip>
                  </div>
                  <div className="mt-1 break-all text-xs text-default-500">
                    {target.source}
                    {target.targetType === "created" && target.nodeName
                      ? ` · ${target.nodeName}`
                      : ""}{" "}
                    {protocolLabel(target)}
                  </div>
                  <div className="mt-1 break-all font-mono text-xs text-default-500">
                    {target.host || "未设置公网地址"}:{target.port || "-"}
                  </div>
                </div>
                <div className="text-sm">
                  <div className="flex items-center gap-2">
                    {supported ? (
                      <CheckCircle2 className="text-success" size={16} />
                    ) : (
                      <Clock3 className="text-default-400" size={16} />
                    )}
                    <span>
                      {supported ? "可执行协议探针" : "等待独立客户端"}
                    </span>
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
                    aria-label={`查看 ${target.name} 测试历史`}
                    size="sm"
                    title="查看测试历史"
                    onPress={() => void loadHistory(target)}
                  >
                    <History size={16} />
                  </Button>
                  {target.targetType === "external" && (
                    <Button
                      isIconOnly
                      aria-label={`删除 ${target.name}`}
                      color="danger"
                      size="sm"
                      title="删除外部协议"
                      variant="light"
                      onPress={() => void deleteExternal(target)}
                    >
                      <Trash2 size={16} />
                    </Button>
                  )}
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
                {selectedTarget?.name || "测试历史"}
              </h2>
              <p className="mt-1 text-xs text-default-500">
                测速执行端：面板协议客户端。历史结果不会显示认证密码。
              </p>
            </div>
            <div className="flex items-center gap-2 text-xs text-default-500">
              <Activity size={15} />
              {selectedTarget ? protocolLabel(selectedTarget) : ""}
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
