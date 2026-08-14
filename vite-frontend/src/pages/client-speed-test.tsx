import { useMemo, useRef, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Switch } from "@heroui/switch";
import {
  Activity,
  Copy,
  Download,
  Gauge,
  Play,
  RotateCcw,
  Square,
  Upload,
} from "lucide-react";
import toast from "react-hot-toast";

type Phase = "idle" | "download" | "upload" | "done" | "error";

interface Metric {
  averageMbps: number;
  peakMbps: number;
  bytes: number;
  seconds: number;
}

interface Result {
  startedAt: number;
  download?: Metric;
  upload?: Metric;
  userAgent: string;
}

const DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down";
const DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up";
const HISTORY_KEY = "cloudnest-client-speed-test-history";

const megabytesToBytes = (value: number, maxMegabytes: number) =>
  Math.min(maxMegabytes, Math.max(1, Math.round(value))) * 1024 * 1024;

const formatSpeed = (value?: number) => {
  if (!value || value <= 0) return "-";
  const mbps = value.toLocaleString("zh-CN", {
    maximumFractionDigits: value >= 1000 ? 2 : 1,
  });

  if (value < 1000) return `${mbps} Mbps`;

  return `${(value / 1000).toLocaleString("zh-CN", {
    maximumFractionDigits: 3,
  })} Gbps / ${mbps} Mbps`;
};

const formatBytes = (bytes?: number) => {
  if (!bytes || bytes <= 0) return "-";
  const mb = bytes / 1024 / 1024;

  if (mb < 1024) return `${mb.toFixed(1)} MB`;

  return `${(mb / 1024).toFixed(2)} GB`;
};

const formatSeconds = (seconds?: number) =>
  seconds && seconds > 0 ? `${seconds.toFixed(2)} 秒` : "-";

const metricFrom = (
  bytes: number,
  startedAt: number,
  peakMbps: number,
): Metric => {
  const seconds = Math.max(0.001, (performance.now() - startedAt) / 1000);
  const averageMbps = (bytes * 8) / seconds / 1_000_000;

  return {
    averageMbps,
    peakMbps: Math.max(peakMbps, averageMbps),
    bytes,
    seconds,
  };
};

const addCacheBuster = (rawUrl: string, bytes?: number) => {
  const url = new URL(rawUrl);

  if (bytes) url.searchParams.set("bytes", String(bytes));
  url.searchParams.set("cacheBust", `${Date.now()}-${Math.random()}`);

  return url.toString();
};

const loadHistory = (): Result[] => {
  try {
    const parsed = JSON.parse(localStorage.getItem(HISTORY_KEY) || "[]");

    return Array.isArray(parsed) ? parsed.slice(0, 5) : [];
  } catch {
    return [];
  }
};

export default function ClientSpeedTestPage() {
  const [phase, setPhase] = useState<Phase>("idle");
  const [currentMbps, setCurrentMbps] = useState(0);
  const [download, setDownload] = useState<Metric | undefined>();
  const [upload, setUpload] = useState<Metric | undefined>();
  const [error, setError] = useState("");
  const [history, setHistory] = useState<Result[]>(loadHistory);
  const [form, setForm] = useState({
    downloadUrl: DEFAULT_DOWNLOAD_URL,
    uploadUrl: DEFAULT_UPLOAD_URL,
    durationSeconds: "12",
    downloadMegabytes: "2048",
    uploadMegabytes: "128",
    includeUpload: false,
  });
  const abortRef = useRef<AbortController | null>(null);
  const uploadXhrRef = useRef<XMLHttpRequest | null>(null);
  const manualStopRef = useRef(false);

  const running = phase === "download" || phase === "upload";
  const primaryMetric = phase === "upload" ? upload : download;
  const displaySpeed = running ? currentMbps : primaryMetric?.averageMbps;
  const summary = useMemo(
    () => [
      ["下载平均", formatSpeed(download?.averageMbps)],
      ["下载峰值", formatSpeed(download?.peakMbps)],
      ["下载数据", formatBytes(download?.bytes)],
      ["上传平均", form.includeUpload ? formatSpeed(upload?.averageMbps) : "-"],
      ["上传峰值", form.includeUpload ? formatSpeed(upload?.peakMbps) : "-"],
      [
        "耗时",
        formatSeconds((download?.seconds || 0) + (upload?.seconds || 0)),
      ],
    ],
    [download, form.includeUpload, upload],
  );
  const browserNetworkType =
    (
      navigator as Navigator & {
        connection?: { effectiveType?: string };
      }
    ).connection?.effectiveType || "browser";

  const persistResult = (next: Result) => {
    const values = [next, ...history].slice(0, 5);

    setHistory(values);
    localStorage.setItem(HISTORY_KEY, JSON.stringify(values));
  };

  const stop = () => {
    manualStopRef.current = true;
    abortRef.current?.abort();
    uploadXhrRef.current?.abort();
    abortRef.current = null;
    uploadXhrRef.current = null;
  };

  const reset = () => {
    stop();
    setPhase("idle");
    setCurrentMbps(0);
    setDownload(undefined);
    setUpload(undefined);
    setError("");
  };

  const runDownload = async () => {
    const bytes = megabytesToBytes(
      Number(form.downloadMegabytes) || 2048,
      8192,
    );
    const durationMs = Math.max(3, Number(form.durationSeconds) || 12) * 1000;
    const controller = new AbortController();
    const startedAt = performance.now();
    let totalBytes = 0;
    let peakMbps = 0;
    let lastBytes = 0;
    let lastAt = startedAt;
    let timer: number | undefined;
    let expectedAbort = false;

    abortRef.current = controller;
    timer = window.setTimeout(() => {
      expectedAbort = true;
      controller.abort();
    }, durationMs);

    try {
      const response = await fetch(addCacheBuster(form.downloadUrl, bytes), {
        cache: "no-store",
        signal: controller.signal,
      });

      if (!response.ok) throw new Error(`下载端点返回 HTTP ${response.status}`);
      if (!response.body) throw new Error("当前浏览器不支持流式测速");

      const reader = response.body.getReader();

      while (true) {
        const { value, done } = await reader.read();

        if (done) break;
        totalBytes += value?.byteLength || 0;
        const now = performance.now();

        if (now - lastAt >= 250) {
          const instantMbps =
            ((totalBytes - lastBytes) * 8) /
            ((now - lastAt) / 1000) /
            1_000_000;

          peakMbps = Math.max(peakMbps, instantMbps);
          setCurrentMbps(instantMbps);
          setDownload(metricFrom(totalBytes, startedAt, peakMbps));
          lastBytes = totalBytes;
          lastAt = now;
        }
      }
    } catch (downloadError) {
      if (!expectedAbort && !manualStopRef.current) {
        throw downloadError instanceof Error
          ? downloadError
          : new Error("下载测速失败");
      }
    } finally {
      if (timer) window.clearTimeout(timer);
    }

    const finalMetric = metricFrom(totalBytes, startedAt, peakMbps);

    setDownload(finalMetric);

    return finalMetric;
  };

  const runUpload = async () =>
    new Promise<Metric>((resolve, reject) => {
      const bytes = megabytesToBytes(Number(form.uploadMegabytes) || 128, 1024);
      const durationMs = Math.max(3, Number(form.durationSeconds) || 12) * 1000;
      const xhr = new XMLHttpRequest();
      const startedAt = performance.now();
      let sentBytes = 0;
      let peakMbps = 0;
      let lastBytes = 0;
      let lastAt = startedAt;
      let expectedAbort = false;
      const timer = window.setTimeout(() => {
        expectedAbort = true;
        xhr.abort();
      }, durationMs);

      uploadXhrRef.current = xhr;
      xhr.open("POST", addCacheBuster(form.uploadUrl, bytes));
      xhr.timeout = durationMs + 5000;
      xhr.upload.onprogress = (event) => {
        sentBytes = event.loaded || sentBytes;
        const now = performance.now();

        if (now - lastAt >= 250) {
          const instantMbps =
            ((sentBytes - lastBytes) * 8) / ((now - lastAt) / 1000) / 1_000_000;

          peakMbps = Math.max(peakMbps, instantMbps);
          setCurrentMbps(instantMbps);
          setUpload(metricFrom(sentBytes, startedAt, peakMbps));
          lastBytes = sentBytes;
          lastAt = now;
        }
      };
      xhr.onload = () => {
        window.clearTimeout(timer);
        if (xhr.status < 200 || xhr.status >= 300) {
          reject(new Error(`上传端点返回 HTTP ${xhr.status}`));

          return;
        }
        const finalMetric = metricFrom(bytes, startedAt, peakMbps);

        setUpload(finalMetric);
        resolve(finalMetric);
      };
      xhr.onerror = () => {
        window.clearTimeout(timer);
        reject(new Error("上传测速失败"));
      };
      xhr.ontimeout = () => {
        window.clearTimeout(timer);
        reject(new Error("上传测速超时"));
      };
      xhr.onabort = () => {
        window.clearTimeout(timer);
        const finalMetric = metricFrom(sentBytes, startedAt, peakMbps);

        setUpload(finalMetric);
        expectedAbort || manualStopRef.current
          ? resolve(finalMetric)
          : reject(new Error("测速已停止"));
      };
      xhr.send(new Blob([new Uint8Array(bytes)]));
    });

  const run = async () => {
    if (running) return;
    manualStopRef.current = false;
    setError("");
    setDownload(undefined);
    setUpload(undefined);
    setCurrentMbps(0);
    setPhase("download");
    try {
      const downloadMetric = await runDownload();
      let uploadMetric: Metric | undefined;

      if (form.includeUpload) {
        setCurrentMbps(0);
        setPhase("upload");
        uploadMetric = await runUpload();
      }
      if (manualStopRef.current) {
        setPhase("idle");
        toast("测速已停止，已保留当前结果");

        return;
      }
      const result = {
        startedAt: Date.now(),
        download: downloadMetric,
        upload: uploadMetric,
        userAgent: navigator.userAgent,
      };

      persistResult(result);
      setPhase("done");
      toast.success("本机单线程测速已完成");
    } catch (testError) {
      const message =
        testError instanceof Error ? testError.message : "本机测速失败";

      if (manualStopRef.current) {
        setPhase("idle");
        toast("测速已停止");
      } else {
        setError(message);
        setPhase("error");
        toast.error(message);
      }
    } finally {
      abortRef.current = null;
      uploadXhrRef.current = null;
      setCurrentMbps(0);
    }
  };

  const copyResult = async () => {
    const latest = history[0];

    if (!latest) return toast.error("暂无测速结果");
    await navigator.clipboard.writeText(JSON.stringify(latest, null, 2));
    toast.success("测速结果已复制");
  };

  return (
    <div className="mx-auto w-full max-w-[1500px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-3 border-b border-divider pb-5 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="text-sm text-default-500">浏览器直连测速</p>
          <h1 className="mt-1 text-2xl font-semibold">本机单线程测速</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-default-500">
            单个浏览器请求直连测速端点，详细显示 Mbps 和
            Gbps，不经过面板服务器或 Agent。
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Chip color="primary" variant="flat">
            单请求
          </Chip>
          <Chip color="secondary" variant="flat">
            Cloudflare
          </Chip>
          <Chip variant="flat">{browserNetworkType}</Chip>
        </div>
      </header>

      <section className="grid gap-5 xl:grid-cols-[420px_1fr]">
        <div className="space-y-4 border border-divider p-4">
          <Select
            label="测试档位"
            selectedKeys={[form.downloadMegabytes]}
            onSelectionChange={(keys) => {
              const downloadMegabytes = String(Array.from(keys)[0] || "2048");

              setForm({
                ...form,
                downloadMegabytes,
                durationSeconds:
                  downloadMegabytes === "4096"
                    ? "15"
                    : downloadMegabytes === "256"
                      ? "8"
                      : "12",
              });
            }}
          >
            <SelectItem key="256">标准 256 MB</SelectItem>
            <SelectItem key="1024">千兆 1 GB</SelectItem>
            <SelectItem key="2048">高带宽 2 GB</SelectItem>
            <SelectItem key="4096">万兆 4 GB</SelectItem>
          </Select>
          <div className="grid gap-3 sm:grid-cols-2">
            <Input
              label="最长下载时间（秒）"
              min={3}
              type="number"
              value={form.durationSeconds}
              onValueChange={(durationSeconds) =>
                setForm({ ...form, durationSeconds })
              }
            />
            <Input
              label="最大下载量（MB）"
              min={16}
              type="number"
              value={form.downloadMegabytes}
              onValueChange={(downloadMegabytes) =>
                setForm({ ...form, downloadMegabytes })
              }
            />
          </div>
          <Input
            label="下载测速地址"
            value={form.downloadUrl}
            onValueChange={(downloadUrl) => setForm({ ...form, downloadUrl })}
          />
          <Switch
            isSelected={form.includeUpload}
            onValueChange={(includeUpload) =>
              setForm({ ...form, includeUpload })
            }
          >
            同时测试上传
          </Switch>
          {form.includeUpload && (
            <div className="space-y-3 border-t border-divider pt-3">
              <Input
                label="最大上传量（MB）"
                max={1024}
                min={8}
                type="number"
                value={form.uploadMegabytes}
                onValueChange={(uploadMegabytes) =>
                  setForm({ ...form, uploadMegabytes })
                }
              />
              <Input
                label="上传测速地址"
                value={form.uploadUrl}
                onValueChange={(uploadUrl) => setForm({ ...form, uploadUrl })}
              />
            </div>
          )}
          <div className="flex flex-wrap gap-2 pt-1">
            <Button
              color="primary"
              isDisabled={running}
              startContent={<Play size={17} />}
              onPress={run}
            >
              开始测速
            </Button>
            <Button
              isDisabled={!running}
              startContent={<Square size={16} />}
              variant="flat"
              onPress={stop}
            >
              停止
            </Button>
            <Button
              startContent={<RotateCcw size={16} />}
              variant="light"
              onPress={reset}
            >
              重置
            </Button>
          </div>
          {error && (
            <p className="border border-danger-200 bg-danger-50 px-3 py-2 text-sm text-danger dark:border-danger-500/20 dark:bg-danger-500/10">
              {error}
            </p>
          )}
        </div>

        <div className="space-y-4">
          <div className="border border-divider p-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <p className="text-sm text-default-500">
                  {phase === "download"
                    ? "下载测速中"
                    : phase === "upload"
                      ? "上传测速中"
                      : phase === "done"
                        ? "最近结果"
                        : "等待测速"}
                </p>
                <p className="mt-2 text-4xl font-semibold tracking-normal sm:text-5xl">
                  {formatSpeed(displaySpeed)}
                </p>
              </div>
              <div className="rounded-full border border-divider p-4">
                {phase === "upload" ? (
                  <Upload className="h-8 w-8 text-secondary" />
                ) : phase === "download" ? (
                  <Download className="h-8 w-8 text-primary" />
                ) : (
                  <Gauge className="h-8 w-8 text-default-500" />
                )}
              </div>
            </div>
            <div className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {summary.map(([label, value]) => (
                <div key={label} className="border border-divider px-3 py-3">
                  <p className="text-xs text-default-500">{label}</p>
                  <p className="mt-1 text-base font-semibold">{value}</p>
                </div>
              ))}
            </div>
          </div>

          <div className="border border-divider p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-sm font-semibold">最近测速</h2>
              <Button
                size="sm"
                startContent={<Copy size={15} />}
                variant="flat"
                onPress={copyResult}
              >
                复制结果
              </Button>
            </div>
            {history.length === 0 ? (
              <div className="mt-3 flex min-h-24 items-center justify-center border-y border-divider text-sm text-default-500">
                暂无本机测速记录
              </div>
            ) : (
              <div className="mt-3 divide-y divide-divider border-y border-divider">
                {history.map((item) => (
                  <div
                    key={item.startedAt}
                    className="grid gap-2 py-3 text-sm md:grid-cols-[180px_1fr_1fr]"
                  >
                    <div className="text-default-500">
                      {new Date(item.startedAt).toLocaleString("zh-CN", {
                        hour12: false,
                      })}
                    </div>
                    <div className="flex items-center gap-2">
                      <Activity className="h-4 w-4 text-primary" />
                      <span>
                        下载 {formatSpeed(item.download?.averageMbps)}
                      </span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Upload className="h-4 w-4 text-secondary" />
                      <span>上传 {formatSpeed(item.upload?.averageMbps)}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
