import { useMemo, useRef, useState } from "react";
import SpeedTest, {
  type BandwidthPoint,
  type MeasurementConfig,
  type MeasurementSummary,
  type Results,
  type Scores,
} from "@cloudflare/speedtest";
import { Button } from "@heroui/button";
import { Card, CardBody } from "@heroui/card";
import { Modal, ModalBody, ModalContent, ModalHeader } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Switch } from "@heroui/switch";
import { Tab, Tabs } from "@heroui/tabs";
import {
  Activity,
  AlertCircle,
  Copy,
  Download,
  Globe2,
  MapPin,
  RotateCcw,
  Settings2,
  Square,
  Upload,
  Wifi,
} from "lucide-react";
import {
  Area,
  AreaChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import toast from "react-hot-toast";

import PageShell from "@/components/page-shell";
import PageHelp from "@/components/page-help";
import SpeedGauge, {
  formatGaugeSpeed,
  GoButton,
} from "@/components/speed-gauge";

type Phase =
  | "idle"
  | "metadata"
  | "latency"
  | "download"
  | "upload"
  | "quality"
  | "done"
  | "error";

type QualityDepth = "standard" | "deep";

interface SpeedSample {
  second: number;
  mbps: number;
  bytes: number;
}

interface LatencyStats {
  averageMs: number;
  medianMs: number;
  p95Ms: number;
  minMs: number;
  maxMs: number;
  jitterMs: number;
  samples: number[];
  sent: number;
  received: number;
  lossPercent: number;
  source: "http" | "cloudflare-engine";
}

interface Metric {
  averageMbps: number;
  peakMbps: number;
  bytes: number;
  seconds: number;
  samples: SpeedSample[];
  loadedLatency?: LatencyStats;
}

interface EdgeMeta {
  ip?: string;
  ipVersion?: "IPv4" | "IPv6";
  asn?: string;
  colo?: string;
  country?: string;
  city?: string;
  latitude?: string;
  longitude?: string;
  timezone?: string;
  http?: string;
  tls?: string;
  warp?: string;
  gateway?: string;
  ray?: string;
}

interface QualityPoint {
  direction: "download" | "upload";
  label: string;
  bytes: number;
  bps: number;
  mbps: number;
  durationMs: number;
  pingMs: number;
}

interface QualityResult {
  summary?: MeasurementSummary;
  scores?: Scores;
  downloadPoints: QualityPoint[];
  uploadPoints: QualityPoint[];
  unloadedLatency?: LatencyStats;
  downLoadedLatency?: LatencyStats;
  upLoadedLatency?: LatencyStats;
  packetLossPercent?: number;
  durationSeconds?: number;
  updatedAt: number;
}

interface Result {
  startedAt: number;
  download?: Metric;
  upload?: Metric;
  unloadedLatency?: LatencyStats;
  httpLoss?: LatencyStats;
  quality?: QualityResult;
  meta?: EdgeMeta;
  userAgent: string;
}

interface LoadedLatencySampler {
  stop: () => Promise<LatencyStats | undefined>;
  getStats: () => LatencyStats | undefined;
}

const DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down";
const DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up";
const DEFAULT_TRACE_URL = "https://speed.cloudflare.com/cdn-cgi/trace";
const HISTORY_KEY = "cloudnest-client-speed-test-history";
const MAX_DOWNLOAD_MB = 65_536;
const MAX_UPLOAD_MB = 2_048;

const clampNumber = (value: number, min: number, max: number) =>
  Math.min(max, Math.max(min, Number.isFinite(value) ? value : min));

const megabytesToBytes = (value: number, maxMegabytes: number) =>
  Math.round(clampNumber(value, 1, maxMegabytes)) * 1024 * 1024;

const percentile = (values: number[], ratio: number) => {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(
    sorted.length - 1,
    Math.max(0, Math.ceil(sorted.length * ratio) - 1),
  );

  return sorted[index];
};

const average = (values: number[]) =>
  values.length
    ? values.reduce((sum, value) => sum + value, 0) / values.length
    : 0;

const calculateJitter = (values: number[]) => {
  if (values.length < 2) return 0;
  const deltas = values
    .slice(1)
    .map((value, index) => Math.abs(value - values[index]));

  return average(deltas);
};

const latencyStatsFromSamples = (
  samples: number[],
  sent = samples.length,
  source: LatencyStats["source"] = "http",
): LatencyStats | undefined => {
  if (!samples.length && sent <= 0) return undefined;
  const received = samples.length;

  return {
    averageMs: average(samples),
    medianMs: percentile(samples, 0.5),
    p95Ms: percentile(samples, 0.95),
    minMs: samples.length ? Math.min(...samples) : 0,
    maxMs: samples.length ? Math.max(...samples) : 0,
    jitterMs: calculateJitter(samples),
    samples,
    sent,
    received,
    lossPercent: sent > 0 ? ((sent - received) / sent) * 100 : 0,
    source,
  };
};

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

const formatBps = (value?: number) =>
  value && value > 0 ? formatSpeed(value / 1_000_000) : "-";

const formatBytes = (bytes?: number) => {
  if (!bytes || bytes <= 0) return "-";
  const mb = bytes / 1024 / 1024;

  if (mb < 1024) return `${mb.toFixed(mb >= 100 ? 0 : 1)} MB`;

  return `${(mb / 1024).toFixed(2)} GB`;
};

const formatSeconds = (seconds?: number) =>
  seconds && seconds > 0 ? `${seconds.toFixed(2)} 秒` : "-";

const formatLatency = (value?: number | null) => {
  if (value === null || value === undefined || Number.isNaN(value)) return "-";
  if (value < 1) return `${(value * 1000).toFixed(0)} us`;

  return `${value.toFixed(value >= 100 ? 0 : 1)} ms`;
};

const formatPercent = (value?: number | null) => {
  if (value === null || value === undefined || Number.isNaN(value)) return "-";

  return `${value.toFixed(value >= 10 ? 1 : 2)}%`;
};

const formatPacketLossRatio = (value?: number | null) =>
  value === null || value === undefined || Number.isNaN(value)
    ? "-"
    : formatPercent(value * 100);

const formatPayloadLabel = (bytes: number) => {
  if (bytes < 1_000_000) return `${Math.round(bytes / 1000)}KB`;
  if (bytes < 1_000_000_000) return `${bytes / 1_000_000}MB`;

  return `${(bytes / 1_000_000_000).toFixed(1)}GB`;
};

const addCacheBuster = (rawUrl: string, bytes?: number) => {
  const url = new URL(rawUrl);

  if (bytes !== undefined) url.searchParams.set("bytes", String(bytes));
  url.searchParams.set("cacheBust", `${Date.now()}-${Math.random()}`);

  return url.toString();
};

const sleep = (ms: number) =>
  new Promise((resolve) => window.setTimeout(resolve, ms));

const metricFrom = (
  bytes: number,
  startedAt: number,
  peakMbps: number,
  samples: SpeedSample[],
  loadedLatency?: LatencyStats,
): Metric => {
  const seconds = Math.max(0.001, (performance.now() - startedAt) / 1000);
  const averageMbps = (bytes * 8) / seconds / 1_000_000;

  return {
    averageMbps,
    peakMbps: Math.max(peakMbps, averageMbps),
    bytes,
    seconds,
    samples,
    loadedLatency,
  };
};

const parseTrace = (body: string) =>
  body
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .reduce<Record<string, string>>((acc, line) => {
      const index = line.indexOf("=");

      if (index > -1) acc[line.slice(0, index)] = line.slice(index + 1);

      return acc;
    }, {});

const ipVersion = (ip?: string): EdgeMeta["ipVersion"] | undefined => {
  if (!ip) return undefined;

  return ip.includes(":") ? "IPv6" : "IPv4";
};

const loadHistory = (): Result[] => {
  try {
    const parsed = JSON.parse(localStorage.getItem(HISTORY_KEY) || "[]");

    return Array.isArray(parsed) ? parsed.slice(0, 8) : [];
  } catch {
    return [];
  }
};

const buildQualityMeasurements = (
  includeUpload: boolean,
  depth: QualityDepth,
): MeasurementConfig[] => {
  const measurements: MeasurementConfig[] = [
    { type: "latency", numPackets: 2 },
    { type: "download", bytes: 1e5, count: 1, bypassMinDuration: true },
    { type: "latency", numPackets: 20 },
    { type: "download", bytes: 1e5, count: 6 },
    { type: "download", bytes: 1e6, count: 5 },
  ];

  if (includeUpload) {
    measurements.push(
      { type: "upload", bytes: 1e5, count: 5 },
      { type: "upload", bytes: 1e6, count: 4 },
    );
  }

  measurements.push(
    { type: "download", bytes: 1e7, count: depth === "deep" ? 5 : 3 },
    { type: "download", bytes: 25e6, count: depth === "deep" ? 4 : 2 },
    { type: "download", bytes: 1e8, count: depth === "deep" ? 3 : 1 },
  );

  if (includeUpload) {
    measurements.push(
      { type: "upload", bytes: 1e7, count: depth === "deep" ? 3 : 2 },
      { type: "upload", bytes: 25e6, count: depth === "deep" ? 3 : 1 },
    );
  }

  if (depth === "deep") {
    measurements.push({ type: "download", bytes: 25e7, count: 2 });
  }

  return measurements;
};

const normalizeBandwidthPoint = (
  point: BandwidthPoint,
  direction: QualityPoint["direction"],
): QualityPoint => ({
  direction,
  label: `${formatPayloadLabel(point.bytes)} ${direction === "download" ? "下载" : "上传"}`,
  bytes: point.bytes,
  bps: point.bps,
  mbps: point.bps / 1_000_000,
  durationMs: point.duration,
  pingMs: point.ping,
});

const statsFromEnginePoints = (points: number[] | undefined) =>
  latencyStatsFromSamples(
    points || [],
    points?.length || 0,
    "cloudflare-engine",
  );

const qualityFromResults = (results: Results): QualityResult => {
  const summary = results.getSummary();
  const packetLoss = results.getPacketLoss();

  return {
    summary,
    scores: results.getScores(),
    downloadPoints: results
      .getDownloadBandwidthPoints()
      .map((point) => normalizeBandwidthPoint(point, "download")),
    uploadPoints: results
      .getUploadBandwidthPoints()
      .map((point) => normalizeBandwidthPoint(point, "upload")),
    unloadedLatency: statsFromEnginePoints(results.getUnloadedLatencyPoints()),
    downLoadedLatency: statsFromEnginePoints(
      results.getDownLoadedLatencyPoints(),
    ),
    upLoadedLatency: statsFromEnginePoints(results.getUpLoadedLatencyPoints()),
    packetLossPercent:
      packetLoss === undefined || packetLoss === null
        ? undefined
        : packetLoss * 100,
    durationSeconds: results.getTotalDurationMs()
      ? (results.getTotalDurationMs() || 0) / 1000
      : undefined,
    updatedAt: Date.now(),
  };
};

const scoreText = (name?: string) => {
  switch (name) {
    case "great":
      return "优秀";
    case "good":
      return "良好";
    case "average":
      return "一般";
    case "poor":
      return "较差";
    case "bad":
      return "很差";
    default:
      return "-";
  }
};

const scoreColor = (name?: string) => {
  switch (name) {
    case "great":
    case "good":
      return "text-success";
    case "average":
      return "text-warning";
    case "poor":
    case "bad":
      return "text-danger";
    default:
      return "text-default-500";
  }
};

export default function ClientSpeedTestPage() {
  const [phase, setPhase] = useState<Phase>("idle");
  const [currentMbps, setCurrentMbps] = useState(0);
  const [download, setDownload] = useState<Metric | undefined>();
  const [upload, setUpload] = useState<Metric | undefined>();
  const [unloadedLatency, setUnloadedLatency] = useState<
    LatencyStats | undefined
  >();
  const [httpLoss, setHttpLoss] = useState<LatencyStats | undefined>();
  const [quality, setQuality] = useState<QualityResult | undefined>();
  const [edgeMeta, setEdgeMeta] = useState<EdgeMeta | undefined>();
  const [qualityPhase, setQualityPhase] = useState("");
  const [error, setError] = useState("");
  const [history, setHistory] = useState<Result[]>(loadHistory);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [form, setForm] = useState({
    downloadUrl: DEFAULT_DOWNLOAD_URL,
    uploadUrl: DEFAULT_UPLOAD_URL,
    durationSeconds: "20",
    downloadMegabytes: "8192",
    uploadMegabytes: "256",
    includeUpload: false,
    includeQualityEngine: true,
    qualityDepth: "standard" as QualityDepth,
    latencySamples: "24",
    httpLossSamples: "60",
  });
  const abortRef = useRef<AbortController | null>(null);
  const uploadXhrRef = useRef<XMLHttpRequest | null>(null);
  const qualityEngineRef = useRef<SpeedTest | null>(null);
  const manualStopRef = useRef(false);

  const running =
    phase === "metadata" ||
    phase === "latency" ||
    phase === "download" ||
    phase === "upload" ||
    phase === "quality";
  const browserNetworkType =
    (
      navigator as Navigator & {
        connection?: { effectiveType?: string };
      }
    ).connection?.effectiveType || "browser";

  const summary = useMemo(
    () => [
      ["下载平均", formatSpeed(download?.averageMbps)],
      ["下载峰值", formatSpeed(download?.peakMbps)],
      ["下载数据", formatBytes(download?.bytes)],
      ["上传平均", form.includeUpload ? formatSpeed(upload?.averageMbps) : "-"],
      [
        "空载延迟",
        formatLatency(quality?.summary?.latency || unloadedLatency?.medianMs),
      ],
      [
        "抖动",
        formatLatency(quality?.summary?.jitter || unloadedLatency?.jitterMs),
      ],
      [
        "下载中延迟",
        formatLatency(
          quality?.summary?.downLoadedLatency ||
            download?.loadedLatency?.medianMs,
        ),
      ],
      [
        "上传中延迟",
        formatLatency(
          quality?.summary?.upLoadedLatency || upload?.loadedLatency?.medianMs,
        ),
      ],
      [
        "HTTP失败率",
        formatPercent(httpLoss?.lossPercent ?? quality?.packetLossPercent),
      ],
      [
        "耗时",
        formatSeconds(
          (download?.seconds || 0) +
            (upload?.seconds || 0) +
            (quality?.durationSeconds || 0),
        ),
      ],
    ],
    [download, form.includeUpload, httpLoss, quality, unloadedLatency, upload],
  );

  const chartData = useMemo(() => {
    const rows = [
      ...(download?.samples || []).map((sample) => ({
        ...sample,
        name: `${sample.second.toFixed(1)}s`,
        download: sample.mbps,
      })),
      ...(upload?.samples || []).map((sample) => ({
        ...sample,
        name: `${sample.second.toFixed(1)}s`,
        upload: sample.mbps,
      })),
    ];

    return rows.slice(-120);
  }, [download, upload]);

  const qualityRows = useMemo(
    () => ({
      download: quality?.downloadPoints.slice(-24) || [],
      upload: quality?.uploadPoints.slice(-18) || [],
    }),
    [quality],
  );

  const persistResult = (next: Result) => {
    const values = [next, ...history].slice(0, 8);

    setHistory(values);
    localStorage.setItem(HISTORY_KEY, JSON.stringify(values));
  };

  const stop = () => {
    manualStopRef.current = true;
    abortRef.current?.abort();
    uploadXhrRef.current?.abort();
    qualityEngineRef.current?.pause();
    abortRef.current = null;
    uploadXhrRef.current = null;
    qualityEngineRef.current = null;
  };

  const reset = () => {
    stop();
    setPhase("idle");
    setCurrentMbps(0);
    setDownload(undefined);
    setUpload(undefined);
    setUnloadedLatency(undefined);
    setHttpLoss(undefined);
    setQuality(undefined);
    setEdgeMeta(undefined);
    setQualityPhase("");
    setError("");
  };

  const fetchWithTimeout = async (
    url: string,
    timeoutMs: number,
    options: RequestInit = {},
  ) => {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), timeoutMs);
    const outerSignal = abortRef.current?.signal;
    const abortFromOuter = () => controller.abort();

    outerSignal?.addEventListener("abort", abortFromOuter, { once: true });
    try {
      return await fetch(url, {
        ...options,
        cache: "no-store",
        signal: controller.signal,
      });
    } finally {
      window.clearTimeout(timer);
      outerSignal?.removeEventListener("abort", abortFromOuter);
    }
  };

  const fetchEdgeMeta = async () => {
    const meta: EdgeMeta = {};

    try {
      const response = await fetchWithTimeout(
        addCacheBuster(form.downloadUrl, 0),
        5000,
      );

      meta.ip =
        response.headers.get("cf-meta-ip") ||
        response.headers.get("cf-connecting-ip") ||
        response.headers.get("x-real-ip") ||
        undefined;
      meta.asn =
        response.headers.get("cf-meta-asn") ||
        response.headers.get("asn") ||
        undefined;
      meta.colo =
        response.headers.get("cf-meta-colo") ||
        response.headers.get("colo") ||
        undefined;
      meta.country =
        response.headers.get("cf-meta-country") ||
        response.headers.get("country") ||
        undefined;
      meta.city =
        response.headers.get("cf-meta-city") ||
        response.headers.get("city") ||
        undefined;
      meta.latitude =
        response.headers.get("cf-meta-latitude") ||
        response.headers.get("latitude") ||
        undefined;
      meta.longitude =
        response.headers.get("cf-meta-longitude") ||
        response.headers.get("longitude") ||
        undefined;
      meta.timezone =
        response.headers.get("cf-meta-timezone") ||
        response.headers.get("timezone") ||
        undefined;
      meta.ray = response.headers.get("cf-ray") || undefined;
    } catch {
      // Trace below can still fill the important client metadata.
    }

    try {
      const traceResponse = await fetchWithTimeout(
        `${DEFAULT_TRACE_URL}?cacheBust=${Date.now()}-${Math.random()}`,
        5000,
      );
      const trace = parseTrace(await traceResponse.text());

      meta.ip = meta.ip || trace.ip;
      meta.colo = meta.colo || trace.colo;
      meta.country = meta.country || trace.loc;
      meta.http = trace.http;
      meta.tls = trace.tls;
      meta.warp = trace.warp;
      meta.gateway = trace.gateway;
    } catch {
      // Metadata is useful, but failing it must not block the speed test.
    }

    meta.ipVersion = ipVersion(meta.ip);
    setEdgeMeta(meta);

    return meta;
  };

  const runLatencyProbe = async (
    sampleCount: number,
    timeoutMs = 3500,
    settleMs = 80,
  ) => {
    const samples: number[] = [];
    let sent = 0;

    for (let index = 0; index < sampleCount; index += 1) {
      if (manualStopRef.current) break;
      sent += 1;
      const startedAt = performance.now();

      try {
        const response = await fetchWithTimeout(
          addCacheBuster(form.downloadUrl, 0),
          timeoutMs,
        );

        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        samples.push(performance.now() - startedAt);
      } catch {
        if (manualStopRef.current) break;
      }

      if (settleMs > 0) await sleep(settleMs);
    }

    return latencyStatsFromSamples(samples, sent, "http");
  };

  const startLoadedLatencySampler = (
    onChange: (stats: LatencyStats | undefined) => void,
  ): LoadedLatencySampler => {
    const samples: number[] = [];
    let sent = 0;
    let stopped = false;
    let currentRun: Promise<void> | undefined;

    currentRun = (async () => {
      while (!stopped && !manualStopRef.current) {
        sent += 1;
        const startedAt = performance.now();

        try {
          const response = await fetchWithTimeout(
            addCacheBuster(form.downloadUrl, 0),
            2500,
          );

          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          samples.push(performance.now() - startedAt);
        } catch {
          if (manualStopRef.current) break;
        }

        onChange(latencyStatsFromSamples(samples, sent, "http"));
        await sleep(400);
      }
    })();

    return {
      stop: async () => {
        stopped = true;
        await currentRun;

        return latencyStatsFromSamples(samples, sent, "http");
      },
      getStats: () => latencyStatsFromSamples(samples, sent, "http"),
    };
  };

  const runDownload = async () => {
    const bytes = megabytesToBytes(
      Number(form.downloadMegabytes) || 8192,
      MAX_DOWNLOAD_MB,
    );
    const durationMs =
      clampNumber(Number(form.durationSeconds) || 20, 3, 180) * 1000;
    const controller = new AbortController();
    const startedAt = performance.now();
    const samples: SpeedSample[] = [];
    let totalBytes = 0;
    let peakMbps = 0;
    let lastBytes = 0;
    let lastAt = startedAt;
    let timer: number | undefined;
    let expectedAbort = false;
    let loadedLatency: LatencyStats | undefined;

    abortRef.current = controller;
    const sampler = startLoadedLatencySampler((stats) => {
      setDownload((previous) =>
        previous ? { ...previous, loadedLatency: stats } : previous,
      );
    });

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
          const sample = {
            second: (now - startedAt) / 1000,
            mbps: instantMbps,
            bytes: totalBytes,
          };

          samples.push(sample);
          peakMbps = Math.max(peakMbps, instantMbps);
          setCurrentMbps(instantMbps);
          setDownload(
            metricFrom(
              totalBytes,
              startedAt,
              peakMbps,
              samples,
              sampler.getStats(),
            ),
          );
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
      loadedLatency = await sampler.stop();
    }

    const finalMetric = metricFrom(
      totalBytes,
      startedAt,
      peakMbps,
      samples,
      loadedLatency,
    );

    setDownload(finalMetric);

    return finalMetric;
  };

  const runUpload = async () =>
    new Promise<Metric>((resolve, reject) => {
      const bytes = megabytesToBytes(
        Number(form.uploadMegabytes) || 256,
        MAX_UPLOAD_MB,
      );
      const durationMs =
        clampNumber(Number(form.durationSeconds) || 20, 3, 180) * 1000;

      abortRef.current = new AbortController();
      const xhr = new XMLHttpRequest();
      const startedAt = performance.now();
      const samples: SpeedSample[] = [];
      let sentBytes = 0;
      let peakMbps = 0;
      let lastBytes = 0;
      let lastAt = startedAt;
      let expectedAbort = false;
      let loadedLatency: LatencyStats | undefined;
      const sampler = startLoadedLatencySampler((stats) => {
        setUpload((previous) =>
          previous ? { ...previous, loadedLatency: stats } : previous,
        );
      });
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
          const sample = {
            second: (now - startedAt) / 1000,
            mbps: instantMbps,
            bytes: sentBytes,
          };

          samples.push(sample);
          peakMbps = Math.max(peakMbps, instantMbps);
          setCurrentMbps(instantMbps);
          setUpload(
            metricFrom(
              sentBytes,
              startedAt,
              peakMbps,
              samples,
              sampler.getStats(),
            ),
          );
          lastBytes = sentBytes;
          lastAt = now;
        }
      };

      const finish = async (metricBytes: number) => {
        window.clearTimeout(timer);
        loadedLatency = await sampler.stop();
        const finalMetric = metricFrom(
          metricBytes,
          startedAt,
          peakMbps,
          samples,
          loadedLatency,
        );

        setUpload(finalMetric);
        resolve(finalMetric);
      };

      xhr.onload = () => {
        if (xhr.status < 200 || xhr.status >= 300) {
          window.clearTimeout(timer);
          sampler
            .stop()
            .finally(() =>
              reject(new Error(`上传端点返回 HTTP ${xhr.status}`)),
            );

          return;
        }
        finish(bytes);
      };
      xhr.onerror = () => {
        window.clearTimeout(timer);
        sampler.stop().finally(() => reject(new Error("上传测速失败")));
      };
      xhr.ontimeout = () => {
        window.clearTimeout(timer);
        sampler.stop().finally(() => reject(new Error("上传测速超时")));
      };
      xhr.onabort = () => {
        finish(sentBytes).catch(() => {
          expectedAbort || manualStopRef.current
            ? resolve(
                metricFrom(
                  sentBytes,
                  startedAt,
                  peakMbps,
                  samples,
                  loadedLatency,
                ),
              )
            : reject(new Error("测速已停止"));
        });
      };
      xhr.send(new Blob([new Uint8Array(bytes)]));
    });

  const runQualityEngine = async () =>
    new Promise<QualityResult>((resolve, reject) => {
      let settled = false;
      const engine = new SpeedTest({
        autoStart: false,
        downloadApiUrl: form.downloadUrl,
        uploadApiUrl: form.uploadUrl,
        logAimApiUrl: null,
        logMeasurementApiUrl: null,
        measurements: buildQualityMeasurements(
          form.includeUpload,
          form.qualityDepth,
        ),
        measureDownloadLoadedLatency: true,
        measureUploadLoadedLatency: form.includeUpload,
        loadedLatencyThrottle: 400,
        bandwidthFinishRequestDuration:
          form.qualityDepth === "deep" ? 1600 : 1000,
        bandwidthAbortRequestDuration:
          form.qualityDepth === "deep" ? 9000 : 6000,
        bandwidthPercentile: 0.9,
        latencyPercentile: 0.5,
        loadedLatencyMaxPoints: 32,
      });

      qualityEngineRef.current = engine;
      engine.onPhaseChange = ({ measurement }) => {
        const typeLabel =
          measurement.type === "latency"
            ? "延迟"
            : measurement.type === "download"
              ? `${formatPayloadLabel(measurement.bytes || 0)} 下载`
              : measurement.type === "upload"
                ? `${formatPayloadLabel(measurement.bytes || 0)} 上传`
                : measurement.type;

        setQualityPhase(typeLabel);
      };
      engine.onResultsChange = () => {
        setQuality(qualityFromResults(engine.results));
      };
      engine.onError = (message) => {
        if (manualStopRef.current) return;
        setQuality(qualityFromResults(engine.results));
        if (!settled) {
          settled = true;
          reject(new Error(message || "Cloudflare 质量引擎执行失败"));
        }
      };
      engine.onFinish = (results) => {
        const next = qualityFromResults(results);

        setQuality(next);
        if (!settled) {
          settled = true;
          resolve(next);
        }
      };
      engine.play();
    });

  const run = async () => {
    if (running) return;
    manualStopRef.current = false;
    abortRef.current = new AbortController();
    setError("");
    setDownload(undefined);
    setUpload(undefined);
    setUnloadedLatency(undefined);
    setHttpLoss(undefined);
    setQuality(undefined);
    setEdgeMeta(undefined);
    setCurrentMbps(0);
    setQualityPhase("");

    try {
      setPhase("metadata");
      abortRef.current = new AbortController();
      const meta = await fetchEdgeMeta();

      if (manualStopRef.current) throw new Error("测速已停止");
      setPhase("latency");
      abortRef.current = new AbortController();
      const unloaded = await runLatencyProbe(
        clampNumber(Number(form.latencySamples) || 24, 4, 100),
      );

      setUnloadedLatency(unloaded);
      if (manualStopRef.current) throw new Error("测速已停止");

      setPhase("download");
      const downloadMetric = await runDownload();
      let uploadMetric: Metric | undefined;

      if (form.includeUpload) {
        setCurrentMbps(0);
        setPhase("upload");
        uploadMetric = await runUpload();
      }

      if (manualStopRef.current) throw new Error("测速已停止");
      abortRef.current = new AbortController();
      const lossStats = await runLatencyProbe(
        clampNumber(Number(form.httpLossSamples) || 60, 10, 300),
        2500,
        20,
      );

      setHttpLoss(lossStats);
      let qualityResult: QualityResult | undefined;

      if (form.includeQualityEngine) {
        setCurrentMbps(0);
        setPhase("quality");
        try {
          qualityResult = await runQualityEngine();
        } catch (qualityError) {
          const partialEngine = qualityEngineRef.current;

          qualityResult = partialEngine
            ? qualityFromResults(partialEngine.results)
            : undefined;
          toast.error(
            qualityError instanceof Error
              ? `质量引擎未完整完成：${qualityError.message}`
              : "质量引擎未完整完成，已保留单线程结果",
          );
        }
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
        unloadedLatency: unloaded,
        httpLoss: lossStats,
        quality: qualityResult,
        meta,
        userAgent: navigator.userAgent,
      };

      persistResult(result);
      setPhase("done");
      toast.success("本机测速已完成");
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
      qualityEngineRef.current = null;
      setCurrentMbps(0);
    }
  };

  const copyResult = async () => {
    const latest = history[0];

    if (!latest) return toast.error("暂无测速结果");
    await navigator.clipboard.writeText(JSON.stringify(latest, null, 2));
    toast.success("测速结果已复制");
  };

  const renderMetric = (
    label: string,
    value: string,
    icon?: React.ReactNode,
  ) => (
    <div
      key={label}
      className="rounded-xl bg-default-50 px-3 py-3 dark:bg-default-100/60"
    >
      <div className="flex items-center gap-1.5 text-xs text-default-500">
        {icon}
        <span>{label}</span>
      </div>
      <p className="mt-1 break-words text-base font-semibold text-foreground">
        {value}
      </p>
    </div>
  );

  const renderQualityPointRows = (points: QualityPoint[]) =>
    points.length === 0 ? (
      <div className="flex min-h-20 items-center justify-center rounded-xl bg-default-50 text-sm text-default-500 dark:bg-default-100/60">
        暂无明细
      </div>
    ) : (
      <div className="divide-y divide-divider overflow-hidden rounded-xl bg-default-50 dark:bg-default-100/60">
        {points.map((point, index) => (
          <div
            key={`${point.direction}-${point.bytes}-${point.durationMs}-${index}`}
            className="grid gap-2 px-3 py-2 text-sm md:grid-cols-[130px_1fr_120px_120px]"
          >
            <span className="font-medium">{point.label}</span>
            <span>{formatBps(point.bps)}</span>
            <span className="text-default-500">
              {formatLatency(point.pingMs)}
            </span>
            <span className="text-default-500">
              {(point.durationMs / 1000).toFixed(2)} 秒
            </span>
          </div>
        ))}
      </div>
    );

  const phaseLabel =
    phase === "metadata"
      ? "正在连接测速节点"
      : phase === "latency"
        ? "正在测量延迟"
        : phase === "download"
          ? "单线程下载"
          : phase === "upload"
            ? "单线程上传"
            : phase === "quality"
              ? `Cloudflare 质量测量${qualityPhase ? ` · ${qualityPhase}` : ""}`
              : "";
  const pingValue = quality?.summary?.latency || unloadedLatency?.medianMs;
  const jitterValue = quality?.summary?.jitter || unloadedLatency?.jitterMs;
  const liveDownload =
    phase === "download" ? currentMbps : download?.averageMbps;
  const liveUpload = phase === "upload" ? currentMbps : upload?.averageMbps;
  const hasResult = Boolean(download || upload);
  const readouts = [
    {
      key: "ping",
      label: "延迟",
      unit: "ms",
      icon: <Activity className="h-4 w-4" />,
      tone: "text-warning",
      active: phase === "latency",
      value:
        pingValue !== undefined && pingValue !== null
          ? Number(pingValue).toFixed(0)
          : undefined,
    },
    {
      key: "jitter",
      label: "抖动",
      unit: "ms",
      icon: <Wifi className="h-4 w-4" />,
      tone: "text-success",
      active: phase === "latency",
      value:
        jitterValue !== undefined && jitterValue !== null
          ? Number(jitterValue).toFixed(1)
          : undefined,
    },
    {
      key: "download",
      label: "下载",
      unit: (liveDownload || 0) >= 1000 ? "Gbps" : "Mbps",
      icon: <Download className="h-4 w-4" />,
      tone: "text-primary",
      active: phase === "download",
      value: liveDownload ? formatGaugeSpeed(liveDownload).value : undefined,
    },
    {
      key: "upload",
      label: "上传",
      unit: (liveUpload || 0) >= 1000 ? "Gbps" : "Mbps",
      icon: <Upload className="h-4 w-4" />,
      tone: "text-secondary",
      active: phase === "upload",
      value: liveUpload ? formatGaugeSpeed(liveUpload).value : undefined,
    },
  ];

  return (
    <PageShell className="space-y-6" width="wide">
      <header className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <div className="flex items-center gap-1.5">
            <h1 className="text-2xl font-bold text-foreground">本机测速</h1>
            <PageHelp>浏览器直连 Cloudflare 单线程大包测速，显示真实 Mbps / Gbps；质量引擎补充延迟、抖动和加载中延迟。</PageHelp>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Chip variant="flat">{browserNetworkType}</Chip>
          <Button
            startContent={<Settings2 size={16} />}
            variant="flat"
            onPress={() => setSettingsOpen(true)}
          >
            测速设置
          </Button>
          <Button
            isDisabled={history.length === 0}
            startContent={<Copy size={15} />}
            variant="flat"
            onPress={copyResult}
          >
            复制结果
          </Button>
        </div>
      </header>

      <Card className="overflow-hidden border border-transparent dark:border-default-100">
        <CardBody className="gap-6 p-5 sm:p-8">
          <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
            {readouts.map((item) => (
              <div
                key={item.key}
                className={`rounded-2xl px-4 py-3 transition-colors duration-300 ${item.active ? "bg-default-100 dark:bg-default-100/80" : ""}`}
              >
                <div
                  className={`flex items-center gap-1.5 text-sm font-medium ${item.tone}`}
                >
                  {item.icon}
                  {item.label}
                  <span className="text-xs font-normal text-default-400">
                    {item.unit}
                  </span>
                </div>
                <div className="mt-1 h-10 text-3xl font-bold tabular-nums text-foreground">
                  {item.value ??
                    (item.active ? (
                      <span className="inline-flex gap-1 pt-3">
                        <span className="h-2 w-2 animate-bounce rounded-full bg-default-400 [animation-delay:-0.3s]" />
                        <span className="h-2 w-2 animate-bounce rounded-full bg-default-400 [animation-delay:-0.15s]" />
                        <span className="h-2 w-2 animate-bounce rounded-full bg-default-400" />
                      </span>
                    ) : (
                      <span className="text-default-300">—</span>
                    ))}
                </div>
              </div>
            ))}
          </div>

          <div className="flex flex-col items-center gap-4">
            {running ? (
              <>
                <SpeedGauge
                  label={phaseLabel}
                  mbps={
                    phase === "download" || phase === "upload" ? currentMbps : 0
                  }
                  mode={
                    phase === "upload"
                      ? "upload"
                      : phase === "download"
                        ? "download"
                        : "idle"
                  }
                />
                <Button
                  radius="full"
                  startContent={<Square size={14} />}
                  variant="flat"
                  onPress={stop}
                >
                  停止
                </Button>
              </>
            ) : (
              <>
                <GoButton label={hasResult ? "再测" : "开始"} onPress={run} />
                {hasResult && (
                  <Button
                    radius="full"
                    size="sm"
                    startContent={<RotateCcw size={14} />}
                    variant="light"
                    onPress={reset}
                  >
                    清空结果
                  </Button>
                )}
              </>
            )}
            {error && (
              <p className="rounded-xl bg-danger-50 px-4 py-2 text-sm text-danger dark:bg-danger-500/10">
                {error}
              </p>
            )}
          </div>

          {chartData.length > 0 && (
            <div className="h-24 w-full">
              <ResponsiveContainer height="100%" width="100%">
                <AreaChart
                  data={chartData}
                  margin={{ top: 4, right: 0, left: 0, bottom: 0 }}
                >
                  <defs>
                    <linearGradient id="speed-down" x1="0" x2="0" y1="0" y2="1">
                      <stop
                        offset="5%"
                        stopColor="#3b82f6"
                        stopOpacity={0.35}
                      />
                      <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                    </linearGradient>
                    <linearGradient id="speed-up" x1="0" x2="0" y1="0" y2="1">
                      <stop
                        offset="5%"
                        stopColor="#8b5cf6"
                        stopOpacity={0.35}
                      />
                      <stop offset="95%" stopColor="#8b5cf6" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <XAxis dataKey="name" hide />
                  <YAxis hide />
                  <Tooltip
                    formatter={(value) => formatSpeed(Number(value))}
                    labelFormatter={(value) => `时间 ${value}`}
                  />
                  <Area
                    dataKey="download"
                    fill="url(#speed-down)"
                    isAnimationActive={false}
                    name="下载"
                    stroke="#3b82f6"
                    strokeWidth={2}
                    type="monotone"
                  />
                  <Area
                    dataKey="upload"
                    fill="url(#speed-up)"
                    isAnimationActive={false}
                    name="上传"
                    stroke="#8b5cf6"
                    strokeWidth={2}
                    type="monotone"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          )}

          <div className="grid gap-3 border-t border-divider pt-5 sm:grid-cols-2">
            <div className="flex items-center gap-3">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                <Globe2 className="h-5 w-5" />
              </span>
              <div className="min-w-0">
                <p className="text-xs text-default-500">你的网络</p>
                <p className="truncate font-medium text-foreground">
                  {edgeMeta?.asn
                    ? `AS${edgeMeta.asn}`
                    : edgeMeta?.ip
                      ? "公网出口"
                      : "开始测速后显示"}
                </p>
                <p className="truncate font-mono text-xs text-default-500">
                  {edgeMeta?.ip
                    ? `${edgeMeta.ip}${edgeMeta.ipVersion ? ` · ${edgeMeta.ipVersion}` : ""}`
                    : "—"}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-3 sm:justify-end sm:text-right">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-secondary/10 text-secondary sm:order-last">
                <MapPin className="h-5 w-5" />
              </span>
              <div className="min-w-0">
                <p className="text-xs text-default-500">测速节点</p>
                <p className="truncate font-medium text-foreground">
                  Cloudflare{" "}
                  {[edgeMeta?.colo, edgeMeta?.city || edgeMeta?.country]
                    .filter(Boolean)
                    .join(" · ") || ""}
                </p>
                <p className="truncate text-xs text-default-500">
                  {[edgeMeta?.http, edgeMeta?.tls]
                    .filter(Boolean)
                    .join(" · ") || "—"}
                </p>
              </div>
            </div>
          </div>
        </CardBody>
      </Card>

      <div className="grid gap-4 md:gap-6 xl:grid-cols-2">
        <Card className="border border-transparent dark:border-default-100">
          <CardBody className="gap-4 p-5">
            <div>
              <h2 className="text-base font-semibold text-foreground">
                测速明细
              </h2>
              <p className="mt-0.5 text-sm text-default-500">
                单线程结果和 HTTP 层延迟统计
              </p>
            </div>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
              {summary.map(([label, value]) => renderMetric(label, value))}
            </div>
          </CardBody>
        </Card>

        <Card className="border border-transparent dark:border-default-100">
          <CardBody className="gap-4 p-5">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="text-base font-semibold text-foreground">
                  网络质量评分
                </h2>
                <p className="mt-0.5 text-sm text-default-500">
                  Cloudflare 质量引擎的下载、上传、丢包与场景评分
                </p>
              </div>
              <Chip size="sm" variant="flat">
                {quality?.summary?.packetLoss !== undefined
                  ? "原生丢包"
                  : "HTTP 近似丢包"}
              </Chip>
            </div>
            <div className="grid grid-cols-3 gap-3">
              {(["streaming", "gaming", "rtc"] as const).map((key) => {
                const label =
                  key === "streaming"
                    ? "视频流媒体"
                    : key === "gaming"
                      ? "在线游戏"
                      : "视频通话";
                const score = quality?.scores?.[key];

                return (
                  <div
                    key={key}
                    className="rounded-xl bg-default-50 px-3 py-3 text-center dark:bg-default-100/60"
                  >
                    <p className="text-xs text-default-500">{label}</p>
                    <p
                      className={`mt-1 text-xl font-bold ${scoreColor(
                        score?.classificationName,
                      )}`}
                    >
                      {scoreText(score?.classificationName)}
                    </p>
                    <p className="mt-0.5 text-xs text-default-500">
                      {score ? `${score.points} 分` : "等待测量"}
                    </p>
                  </div>
                );
              })}
            </div>
            <div className="grid grid-cols-3 gap-3">
              {renderMetric(
                "下载",
                formatBps(quality?.summary?.download),
                <Download className="h-3.5 w-3.5" />,
              )}
              {renderMetric(
                "上传",
                formatBps(quality?.summary?.upload),
                <Upload className="h-3.5 w-3.5" />,
              )}
              {renderMetric(
                "丢包",
                quality?.summary?.packetLoss !== undefined
                  ? formatPacketLossRatio(quality.summary.packetLoss)
                  : formatPercent(httpLoss?.lossPercent),
                <Wifi className="h-3.5 w-3.5" />,
              )}
            </div>
          </CardBody>
        </Card>
      </div>

      <Card className="border border-transparent dark:border-default-100">
        <CardBody className="gap-2 p-5">
          <Tabs aria-label="测速结果明细" color="primary" variant="underlined">
            <Tab key="history" title="最近测速">
              {history.length === 0 ? (
                <div className="flex min-h-24 items-center justify-center text-sm text-default-500">
                  暂无本机测速记录
                </div>
              ) : (
                <div className="divide-y divide-divider">
                  {history.map((item) => (
                    <div
                      key={item.startedAt}
                      className="grid gap-2 py-3 text-sm sm:grid-cols-[170px_1fr_1fr_1fr] sm:items-center"
                    >
                      <div className="text-default-500">
                        {new Date(item.startedAt).toLocaleString("zh-CN", {
                          hour12: false,
                        })}
                      </div>
                      <div className="flex items-center gap-2">
                        <Download className="h-4 w-4 text-primary" />
                        <span className="font-medium">
                          {formatSpeed(item.download?.averageMbps)}
                        </span>
                      </div>
                      <div className="flex items-center gap-2">
                        <Upload className="h-4 w-4 text-secondary" />
                        <span className="font-medium">
                          {formatSpeed(item.upload?.averageMbps)}
                        </span>
                      </div>
                      <div className="flex min-w-0 items-center gap-2 text-default-500">
                        <Globe2 className="h-4 w-4 shrink-0 text-success" />
                        <span className="truncate">
                          {item.meta?.ip || "-"} · {item.meta?.colo || "-"}
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </Tab>
            <Tab key="download" title="下载分档">
              {renderQualityPointRows(qualityRows.download)}
            </Tab>
            <Tab key="upload" title="上传分档">
              {renderQualityPointRows(qualityRows.upload)}
            </Tab>
          </Tabs>
        </CardBody>
      </Card>

      <Modal
        isOpen={settingsOpen}
        scrollBehavior="inside"
        size="lg"
        onOpenChange={setSettingsOpen}
      >
        <ModalContent>
          <ModalHeader>测速设置</ModalHeader>
          <ModalBody className="gap-5 pb-6">
            <div className="space-y-2">
              <p className="text-xs font-medium uppercase tracking-wide text-default-500">
                测速档位
              </p>
              <Select
                label="测试档位"
                selectedKeys={[form.downloadMegabytes]}
                onSelectionChange={(keys) => {
                  const downloadMegabytes = String(
                    Array.from(keys)[0] || "8192",
                  );

                  setForm({
                    ...form,
                    downloadMegabytes,
                    durationSeconds:
                      downloadMegabytes === "65536"
                        ? "90"
                        : downloadMegabytes === "32768"
                          ? "60"
                          : downloadMegabytes === "16384"
                            ? "40"
                            : downloadMegabytes === "2048"
                              ? "12"
                              : "20",
                  });
                }}
              >
                <SelectItem key="2048">高带宽 2 GB</SelectItem>
                <SelectItem key="4096">高带宽 4 GB</SelectItem>
                <SelectItem key="8192">5G/万兆 8 GB</SelectItem>
                <SelectItem key="16384">极限 16 GB</SelectItem>
                <SelectItem key="32768">极限 32 GB</SelectItem>
                <SelectItem key="65536">极限 64 GB</SelectItem>
              </Select>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <Input
                label="最长下载时间（秒）"
                max={180}
                min={3}
                type="number"
                value={form.durationSeconds}
                onValueChange={(durationSeconds) =>
                  setForm({ ...form, durationSeconds })
                }
              />
              <Input
                label="最大下载量（MB）"
                max={MAX_DOWNLOAD_MB}
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

            <div className="grid gap-3 sm:grid-cols-2">
              <Input
                label="延迟样本"
                max={100}
                min={4}
                type="number"
                value={form.latencySamples}
                onValueChange={(latencySamples) =>
                  setForm({ ...form, latencySamples })
                }
              />
              <Input
                label="HTTP失败率样本"
                max={300}
                min={10}
                type="number"
                value={form.httpLossSamples}
                onValueChange={(httpLossSamples) =>
                  setForm({ ...form, httpLossSamples })
                }
              />
            </div>

            <div className="space-y-3 border-t border-divider pt-4">
              <p className="text-xs font-medium uppercase tracking-wide text-default-500">
                测速内容
              </p>
              <Switch
                isSelected={form.includeUpload}
                onValueChange={(includeUpload) =>
                  setForm({ ...form, includeUpload })
                }
              >
                同时测试上传
              </Switch>
              {form.includeUpload && (
                <div className="grid gap-3 border-l-2 border-divider pl-4">
                  <Input
                    label="最大上传量（MB）"
                    max={MAX_UPLOAD_MB}
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
                    onValueChange={(uploadUrl) =>
                      setForm({ ...form, uploadUrl })
                    }
                  />
                </div>
              )}
              <Switch
                isSelected={form.includeQualityEngine}
                onValueChange={(includeQualityEngine) =>
                  setForm({ ...form, includeQualityEngine })
                }
              >
                Cloudflare质量引擎
              </Switch>
              {form.includeQualityEngine && (
                <Select
                  label="质量测量深度"
                  selectedKeys={[form.qualityDepth]}
                  onSelectionChange={(keys) =>
                    setForm({
                      ...form,
                      qualityDepth: String(
                        Array.from(keys)[0] || "standard",
                      ) as QualityDepth,
                    })
                  }
                >
                  <SelectItem key="standard">标准</SelectItem>
                  <SelectItem key="deep">深度</SelectItem>
                </Select>
              )}
            </div>
            <div className="flex gap-2 rounded-xl bg-warning-50 px-3 py-2 text-xs leading-5 text-warning-700 dark:bg-warning-500/10">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>
                HTTP 失败率来自浏览器请求失败/超时统计；严格 UDP 丢包需要 TURN
                服务。
              </span>
            </div>
          </ModalBody>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
