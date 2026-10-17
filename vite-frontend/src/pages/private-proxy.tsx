import type { ReactNode } from "react";

import { useEffect, useMemo, useState } from "react";
import { Accordion, AccordionItem } from "@heroui/accordion";
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
import { Tab, Tabs } from "@heroui/tabs";
import {
  BarChart3,
  Copy,
  Globe2,
  History,
  Home,
  Link2,
  Pencil,
  QrCode,
  RefreshCw,
  Rss,
  Shuffle,
  KeyRound,
  Pause,
  Play,
  Plus,
  Server,
  ShieldCheck,
  Stethoscope,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import { Tooltip } from "@heroui/tooltip";
import toast from "react-hot-toast";
import { useNavigate } from "react-router-dom";

import {
  createPrivateProxy,
  deletePrivateProxy,
  getExitCountries,
  getExitSwitchToken,
  switchOwnExit,
  getNodeList,
  getPrivateProxies,
  getPrivateProxyClientConfig,
  pausePrivateProxy,
  resumePrivateProxy,
  testPrivateProxy,
  updatePrivateProxyExit,
  updatePrivateProxyRouting,
  getProxyExits,
  type ProxyExit,
  type RoutePreset,
  type RouteRuleset,
  type RuleCatalogItem,
  type RouteRule,
  type ExitCountriesResponse,
  type PrivateProxyClientConfig,
  type PrivateProxyItem,
  type PrivateProxyTransport,
  type PrivateProxyType,
} from "@/api";
import ExitEventsModal from "@/components/exit-events-modal";
import PageShell from "@/components/page-shell";
import ResourceCard, {
  type ChipColor,
  ResourceCardGrid,
} from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";
import CamouflagePicker, {
  AUTO_CAMOUFLAGE,
} from "@/components/camouflage-picker";
import ExitPicker, { type ExitChoice } from "@/components/exit-picker";
import DefaultExitSelect, {
  exitKeyOf,
  RoutingSummary,
} from "@/components/default-exit-select";
import RouteRulesEditor from "@/components/route-rules-editor";
import ProxyEditModal from "@/components/proxy-edit-modal";
import ProxyImport from "@/components/proxy-import";
import ProxyQr from "@/components/proxy-qr";
import ProxySubscriptionModal from "@/components/proxy-subscription-modal";
import ProxyTrafficModal from "@/components/proxy-traffic-modal";
import { isAdmin as isAdminUser } from "@/utils/auth";
import {
  agentSupportsExit,
  exitStatusMeta,
  exitSummary,
} from "@/utils/exit-display";
import { formatBytes } from "@/utils/format";

interface NodeOption {
  id: number;
  name: string;
  ip?: string;
  serverIp?: string;
  status: number;
  version?: string;
  quotaAvailable?: boolean;
  unavailableReason?: string;
}

type Cipher = "aes-128-gcm" | "aes-256-gcm" | "chacha20-ietf-poly1305";
type ProxyGroup = "general" | "encrypted" | "cdn" | "quic" | "vpn";
type Ss2022Method =
  | "2022-blake3-aes-128-gcm"
  | "2022-blake3-aes-256-gcm"
  | "2022-blake3-chacha20-poly1305";

interface ProxyForm {
  name: string;
  nodeId: string;
  proxyType: PrivateProxyType;
  bindIp: string;
  listenPort: string;
  authUsername: string;
  authPassword: string;
  cipher: Cipher;
  realityServerName: string;
  allowedCidrs: string;
  permanent: boolean;
  leaseHours: string;
  exitType: ExitChoice["exitType"];
  exitCountry: string;
  method: Ss2022Method;
  transport: PrivateProxyTransport;
  tls: boolean;
  serverName: string;
  obfs: boolean;
  handshakeServer: string;
  connectHost: string;
}

const EXIT_PROXY_TYPES: PrivateProxyType[] = [
  "socks5",
  "http",
  "mixed",
  "shadowsocks",
  "shadowsocks2022",
  "vless_reality",
  "vless_xhttp",
  "vless",
  "vmess",
  "trojan",
  "anytls",
  "hysteria2",
  "tuic",
  "shadowtls",
];
// 由独立运行时承载的协议经节点本机的出口通道使用家宽出口，需要更高的 Agent 版本
const ROUTED_EXIT_PROXY_TYPES: PrivateProxyType[] = [
  "vless_reality",
  "vless_xhttp",
  "vless",
  "vmess",
  "trojan",
  "anytls",
  "hysteria2",
  "tuic",
  "shadowsocks2022",
  "shadowtls",
];
// 这些协议、自检、修改端口和重置密钥需要的 Agent 版本
const PROTOCOL_AGENT_VERSION = "2.62.0";
// 自定义出口和分流规则由节点上的分流路由承载
const ROUTER_AGENT_VERSION = "2.76.0";
/** Exit groups and the regex, port, network and GEOIP rules. */
const ROUTER_RULES_AGENT_VERSION = "2.79.0";
/** Cloudflare WARP exits. */
const WARP_AGENT_VERSION = "2.82.0";
const NEW_PROTOCOLS: PrivateProxyType[] = [
  "shadowsocks2022",
  "vless_xhttp",
  "vless",
  "vmess",
  "anytls",
  "shadowtls",
];
const DEFAULT_EXIT_AGENT_VERSION = "2.57.0";
const DEFAULT_ROUTED_EXIT_AGENT_VERSION = "2.58.0";

const randomSecret = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(18));

  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join(
    "",
  );
};

const initialForm = (): ProxyForm => ({
  name: "",
  nodeId: "",
  proxyType: "socks5",
  bindIp: "",
  listenPort: "",
  authUsername: "",
  authPassword: "",
  cipher: "aes-256-gcm",
  realityServerName: AUTO_CAMOUFLAGE,
  allowedCidrs: "",
  permanent: true,
  leaseHours: "24",
  exitType: "node",
  exitCountry: "",
  method: "2022-blake3-aes-128-gcm",
  transport: "ws",
  tls: false,
  serverName: "",
  obfs: false,
  handshakeServer: AUTO_CAMOUFLAGE,
  connectHost: "",
});

const protocolMeta: Record<
  PrivateProxyType,
  { label: string; access: string }
> = {
  socks5: { label: "SOCKS5", access: "用户名 / 密码" },
  http: { label: "HTTP", access: "用户名 / 密码" },
  mixed: { label: "SOCKS5+HTTP", access: "同一端口 / 用户名密码" },
  shadowsocks: { label: "Shadowsocks", access: "TCP + UDP" },
  shadowsocks2022: { label: "SS 2022", access: "TCP + UDP / 密钥" },
  vless_reality: { label: "VLESS + REALITY", access: "UUID / Reality" },
  vless_xhttp: { label: "VLESS + XHTTP", access: "UUID / Reality" },
  vless: { label: "VLESS", access: "UUID / 可走 CDN" },
  vmess: { label: "VMess", access: "UUID / 可走 CDN" },
  trojan: { label: "Trojan", access: "TLS 密钥" },
  anytls: { label: "AnyTLS", access: "TLS 密钥" },
  hysteria2: { label: "Hysteria2", access: "QUIC / UDP" },
  tuic: { label: "TUIC v5", access: "QUIC / UUID" },
  shadowtls: { label: "ShadowTLS", access: "伪装 TLS / 密钥" },
  wireguard: { label: "WireGuard", access: "设备 VPN 配置" },
};

const protocolHints: Partial<Record<PrivateProxyType, string>> = {
  mixed: "一个端口同时接受 SOCKS5 和 HTTP 代理，适合浏览器和各种软件。",
  shadowsocks2022:
    "新版 Shadowsocks，抗重放、更难识别；密钥由面板生成，客户端需支持 SS 2022。",
  vless_xhttp:
    "VLESS 走 XHTTP 传输并使用 REALITY 伪装，Xray 新推荐的组合；需要 Xray 内核的客户端（v2rayN、Shadowrocket 等）。",
  vless:
    "选 WebSocket / gRPC / HTTPUpgrade 并关闭 TLS，再把你的域名接入 Cloudflare 等 CDN，可以经 CDN 中转。",
  vmess: "兼容老客户端；搭配 WebSocket 可以经 CDN 中转。",
  anytls:
    "基于 TLS、能减少流量特征的新协议；客户端需支持 AnyTLS（mihomo、sing-box、Shadowrocket）。",
  shadowtls:
    "握手时借用真实网站的 TLS，内部是 SS 2022；没有通用链接，请用 Clash 或 sing-box 订阅导入。",
};

const protocolGroups: Record<
  ProxyGroup,
  { label: string; protocols: PrivateProxyType[] }
> = {
  general: {
    label: "通用代理",
    protocols: ["socks5", "http", "mixed", "shadowsocks", "shadowsocks2022"],
  },
  encrypted: {
    label: "加密代理",
    protocols: [
      "vless_reality",
      "vless_xhttp",
      "trojan",
      "anytls",
      "shadowtls",
    ],
  },
  cdn: { label: "CDN 中转", protocols: ["vless", "vmess"] },
  quic: { label: "QUIC 加速", protocols: ["hysteria2", "tuic"] },
  vpn: { label: "设备组网", protocols: ["wireguard"] },
};

const usesSecret = (proxyType: PrivateProxyType) =>
  [
    "shadowsocks",
    "trojan",
    "hysteria2",
    "tuic",
    "anytls",
    "shadowtls",
  ].includes(proxyType);
const usesAccount = (proxyType: PrivateProxyType) =>
  proxyType === "socks5" || proxyType === "http" || proxyType === "mixed";
const isReality = (proxyType: PrivateProxyType) =>
  proxyType === "vless_reality" || proxyType === "vless_xhttp";
const isAdvancedRuntime = (proxyType: PrivateProxyType) =>
  [
    "trojan",
    "hysteria2",
    "tuic",
    "wireguard",
    "anytls",
    "shadowsocks2022",
    "vmess",
    "vless",
    "shadowtls",
  ].includes(proxyType);
const transportLabel = (proxyType: PrivateProxyType) => {
  if (["hysteria2", "tuic", "wireguard"].includes(proxyType)) return "UDP";
  if (proxyType === "shadowsocks" || proxyType === "shadowsocks2022")
    return "TCP + UDP";

  return "TCP";
};

/** 比较 Agent 版本号，a >= b 时返回 true。 */
const versionAtLeast = (a: string | undefined, b: string) =>
  agentSupportsExit(a, b);

const stateMeta: Record<
  PrivateProxyItem["state"],
  { label: string; color: "success" | "warning" | "danger" | "default" }
> = {
  active: { label: "运行中", color: "success" },
  paused: { label: "已暂停", color: "warning" },
  provisioning: { label: "配置中", color: "default" },
  error: { label: "配置失败", color: "danger" },
  delete_pending: { label: "待清理", color: "warning" },
  expired: { label: "已到期", color: "default" },
  quota_exhausted: { label: "流量用尽", color: "danger" },
};

const formatRemainingTime = (milliseconds?: number) => {
  if (milliseconds === undefined || milliseconds === null) return "永久";
  if (milliseconds <= 0) return "已到期";
  const days = Math.floor(milliseconds / 86400000);

  if (days > 0) return `${days} 天`;
  const hours = Math.max(1, Math.ceil(milliseconds / 3600000));

  return `${hours} 小时`;
};

const copyText = async (value: string, label: string) => {
  await navigator.clipboard.writeText(value);
  toast.success(`${label}已复制`);
};

export default function PrivateProxyPage() {
  const navigate = useNavigate();
  const isAdmin = isAdminUser();
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [configLoading, setConfigLoading] = useState(false);
  const [clientConfig, setClientConfig] =
    useState<PrivateProxyClientConfig | null>(null);
  const [items, setItems] = useState<PrivateProxyItem[]>([]);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [form, setForm] = useState<ProxyForm>(initialForm);
  const [protocolGroup, setProtocolGroup] = useState<ProxyGroup>("general");
  const [exitInfo, setExitInfo] = useState<ExitCountriesResponse | null>(null);
  const [exitInfoLoading, setExitInfoLoading] = useState(false);
  const [exitInfoError, setExitInfoError] = useState("");
  const [exitTarget, setExitTarget] = useState<PrivateProxyItem | null>(null);
  const [exitChoice, setExitChoice] = useState<ExitChoice>({
    exitType: "node",
    exitCountry: "",
  });
  const [exitSaving, setExitSaving] = useState(false);
  const [proxyExits, setProxyExits] = useState<ProxyExit[]>([]);
  const [routePresets, setRoutePresets] = useState<RoutePreset[]>([]);
  const [routeRulesets, setRouteRulesets] = useState<RouteRuleset[]>([]);
  const [ruleCatalog, setRuleCatalog] = useState<RuleCatalogItem[]>([]);
  const [routeRules, setRouteRules] = useState<RouteRule[]>([]);
  const [eventsTarget, setEventsTarget] = useState<{
    groupId: number;
    title: string;
  } | null>(null);
  const [editTarget, setEditTarget] = useState<PrivateProxyItem | null>(null);
  const [trafficTarget, setTrafficTarget] = useState<PrivateProxyItem | null>(
    null,
  );
  const [subscriptionOpen, setSubscriptionOpen] = useState(false);
  const [testing, setTesting] = useState<number | null>(null);
  const [showQr, setShowQr] = useState(false);
  const [configProxyId, setConfigProxyId] = useState<number | null>(null);

  const minExitAgentVersion =
    exitInfo?.minAgentVersion || DEFAULT_EXIT_AGENT_VERSION;

  const loadExitInfo = async () => {
    setExitInfoLoading(true);
    try {
      const response = await getExitCountries();

      if (response.code === 0 && response.data) {
        setExitInfo(response.data);
        setExitInfoError(response.data.lastRefreshError || "");
      } else setExitInfoError(response.msg || "读取失败");
    } catch {
      setExitInfoError("读取失败");
    }
    setExitInfoLoading(false);
  };

  /** 当前协议和节点不能使用家宽出口时返回原因。 */
  const exitBlockedReason = (
    proxyType: PrivateProxyType,
    nodeId?: number | string,
  ) => {
    if (!EXIT_PROXY_TYPES.includes(proxyType))
      return "家宽出口暂不支持 WireGuard。";
    const requiredVersion = NEW_PROTOCOLS.includes(proxyType)
      ? PROTOCOL_AGENT_VERSION
      : ROUTED_EXIT_PROXY_TYPES.includes(proxyType)
        ? exitInfo?.minRoutedAgentVersion || DEFAULT_ROUTED_EXIT_AGENT_VERSION
        : minExitAgentVersion;
    const node = nodes.find((item) => String(item.id) === String(nodeId));

    if (node && !agentSupportsExit(node.version, requiredVersion))
      return `该节点的 Agent 版本是 ${node.version || "未知"}，${protocolMeta[proxyType].label} 使用家宽出口需要升级到 ${requiredVersion} 或更高。`;

    return "";
  };

  const load = async () => {
    setLoading(items.length === 0);
    const proxyRequest = getPrivateProxies();
    const nodeRequest = getNodeList();

    try {
      const proxyRes = await proxyRequest;

      if (proxyRes.code === 0) {
        // 后端已过滤孤儿代理；这里再做一次轻量兜底，避免旧版本接口或
        // 浏览器保留的旧响应把“节点已删除”分组重新显示出来。
        setItems(
          (proxyRes.data || []).filter(
            (item) => item.nodeName !== "节点已删除",
          ),
        );
      } else toast.error(proxyRes.msg || "加载代理失败");
    } catch (error) {
      console.error("加载代理列表失败:", error);
      toast.error("加载代理列表失败");
    }
    setLoading(false);

    try {
      const nodeRes = await nodeRequest;

      if (nodeRes.code === 0) setNodes(nodeRes.data || []);
      else console.warn("获取节点列表失败:", nodeRes.msg);
    } catch (error) {
      console.warn("获取节点列表失败:", error);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  // 有代理在用家宽出口时定期刷新，出口状态和切换能及时显示
  const hasResidentialExit = items.some(
    (item) => item.exitType === "residential",
  );

  useEffect(() => {
    if (!hasResidentialExit) return;
    const timer = window.setInterval(() => {
      if (!document.hidden) void load();
    }, 10_000);

    return () => window.clearInterval(timer);
  }, [hasResidentialExit]);

  // 只在真正要选家宽出口时才读取候选列表，不使用该功能时面板不会访问外部列表
  const wantsExitInfo =
    isAdmin &&
    ((modalOpen && form.exitType === "residential") || Boolean(exitTarget));

  useEffect(() => {
    if (wantsExitInfo) void loadExitInfo();
  }, [wantsExitInfo]);

  const summary = useMemo(
    () => ({
      active: items.filter((item) => item.state === "active").length,
      paused: items.filter((item) => item.state === "paused").length,
      attention: items.filter((item) =>
        ["error", "delete_pending"].includes(item.state),
      ).length,
    }),
    [items],
  );

  const nodeGroups = useMemo(() => {
    const groups = new Map<number, PrivateProxyItem[]>();

    items.forEach((item) =>
      groups.set(item.nodeId, [...(groups.get(item.nodeId) || []), item]),
    );

    return Array.from(groups.entries()).map(([nodeId, proxies]) => {
      const first = proxies[0];
      const protocols = Array.from(
        new Set(proxies.map((item) => protocolMeta[item.proxyType].label)),
      );

      return {
        nodeId,
        nodeName: first.nodeName,
        publicHost: first.publicHost,
        nodeOnline: first.nodeOnline,
        proxies,
        protocols,
        activeCount: proxies.filter((item) => item.state === "active").length,
        attentionCount: proxies.filter((item) =>
          ["error", "delete_pending"].includes(item.state),
        ).length,
      };
    });
  }, [items]);

  const selectProtocol = (proxyType: PrivateProxyType) => {
    setForm((current) => ({
      ...current,
      proxyType,
      exitType: EXIT_PROXY_TYPES.includes(proxyType)
        ? current.exitType
        : "node",
      authUsername: usesAccount(proxyType) ? current.authUsername : "",
      authPassword:
        isReality(proxyType) ||
        ["vmess", "vless", "shadowsocks2022"].includes(proxyType)
          ? ""
          : current.authPassword || randomSecret(),
      realityServerName: isReality(proxyType)
        ? current.realityServerName || AUTO_CAMOUFLAGE
        : AUTO_CAMOUFLAGE,
    }));
  };

  /** 新协议在旧版本 Agent 上不能创建时返回原因。 */
  const protocolBlockedReason = () => {
    if (!NEW_PROTOCOLS.includes(form.proxyType) || !form.nodeId) return "";
    const node = nodes.find((item) => String(item.id) === form.nodeId);

    if (node && !versionAtLeast(node.version, PROTOCOL_AGENT_VERSION))
      return `该节点的 Agent 版本是 ${node.version || "未知"}，${protocolMeta[form.proxyType].label} 需要升级到 ${PROTOCOL_AGENT_VERSION} 或更高。`;

    return "";
  };

  const submit = async () => {
    if (!form.name.trim() || !form.nodeId || !form.listenPort)
      return toast.error("请填写名称、节点和监听端口");
    if (
      usesAccount(form.proxyType) &&
      (form.authUsername.trim().length < 3 || form.authPassword.length < 8)
    ) {
      return toast.error("用户名至少 3 位，密码至少 8 位");
    }
    if (usesSecret(form.proxyType) && form.authPassword.length < 8)
      return toast.error(`${protocolMeta[form.proxyType].label} 密钥至少 8 位`);
    if (isReality(form.proxyType) && !form.realityServerName.trim())
      return toast.error("请填写 REALITY 伪装域名");
    if (form.proxyType === "shadowtls" && !form.handshakeServer.trim())
      return toast.error("请填写握手网站");
    const blockedProtocol = protocolBlockedReason();

    if (blockedProtocol) return toast.error(blockedProtocol);
    const port = Number(form.listenPort);

    if (!Number.isInteger(port) || port < 1 || port > 65535)
      return toast.error("监听端口应为 1-65535");
    const residentialExit = isAdmin && form.exitType === "residential";

    if (residentialExit) {
      const blocked = exitBlockedReason(form.proxyType, form.nodeId);

      if (blocked) return toast.error(blocked);
      if (!form.exitCountry) return toast.error("请选择出口国家");
    }
    setSubmitting(true);
    const response = await createPrivateProxy({
      name: form.name.trim(),
      nodeId: Number(form.nodeId),
      proxyType: form.proxyType,
      bindIp: form.bindIp.trim(),
      listenPort: port,
      authUsername: form.authUsername.trim(),
      authPassword: form.authPassword,
      cipher: form.cipher,
      realityServerName: form.realityServerName.trim(),
      allowedCidrs: form.allowedCidrs.trim(),
      permanent: form.permanent,
      leaseHours: form.permanent ? undefined : Number(form.leaseHours),
      exitType: residentialExit ? "residential" : "node",
      exitCountry: residentialExit ? form.exitCountry : undefined,
      method: form.proxyType === "shadowsocks2022" ? form.method : undefined,
      transport: ["vmess", "vless"].includes(form.proxyType)
        ? form.transport
        : undefined,
      tls: ["vmess", "vless"].includes(form.proxyType) ? form.tls : undefined,
      serverName: form.serverName.trim() || undefined,
      obfs: form.proxyType === "hysteria2" ? form.obfs : undefined,
      handshakeServer:
        form.proxyType === "shadowtls"
          ? form.handshakeServer.trim()
          : undefined,
      connectHost: form.connectHost.trim() || undefined,
    });

    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || "创建代理失败");
    toast.success(
      `${protocolMeta[form.proxyType].label} 已创建，正在从节点自检`,
    );
    setModalOpen(false);
    setForm(initialForm());
    setProtocolGroup("general");
    void load();
  };

  const showClientConfig = async (item: PrivateProxyItem) => {
    setConfigLoading(true);
    const response = await getPrivateProxyClientConfig(item.id);

    setConfigLoading(false);
    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "读取连接信息失败");
    setShowQr(false);
    setConfigProxyId(item.id);
    setClientConfig(response.data);
  };

  const runTest = async (item: PrivateProxyItem) => {
    setTesting(item.id);
    const response = await testPrivateProxy(item.id);

    setTesting(null);
    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "自检失败");
    if (response.data.ok)
      toast.success(
        `代理可用：出口 ${response.data.ip}，用时 ${response.data.latencyMs} ms`,
      );
    else toast.error(response.data.error || "代理不可用");
    void load();
  };

  const openExitSettings = (item: PrivateProxyItem) => {
    setExitChoice({
      exitType: item.exitType || "node",
      exitCountry: item.exitCountry || "",
      exitCustomId: item.routing?.exitCustomId,
    });
    setRouteRules(item.routing?.rules || []);
    setSwitchLink("");
    setExitTarget(item);
    if (ROUTED_EXIT_PROXY_TYPES.includes(item.proxyType)) loadRoutingOptions();
  };

  const loadRoutingOptions = () =>
    void getProxyExits().then((response) => {
      if (response.code === 0 && response.data) {
        setProxyExits(response.data.exits || []);
        setRoutePresets(response.data.presets || []);
        setRouteRulesets(response.data.rulesets || []);
        setRuleCatalog(response.data.catalog || []);
      }
    });

  /** Why the node cannot use Cloudflare WARP yet, or "". */
  const warpBlocked = (item: PrivateProxyItem) => {
    const node = nodes.find(
      (entry) => String(entry.id) === String(item.nodeId),
    );

    return node && !agentSupportsExit(node.version, WARP_AGENT_VERSION)
      ? `Cloudflare WARP 需要节点 Agent ${WARP_AGENT_VERSION} 或更高版本（当前 ${node.version || "未知"}），请先升级节点。`
      : "";
  };

  /** Why the node cannot run exit groups and the newer rule types, or "". */
  const newerRulesBlocked = (item: PrivateProxyItem) => {
    const node = nodes.find(
      (entry) => String(entry.id) === String(item.nodeId),
    );

    return node && !agentSupportsExit(node.version, ROUTER_RULES_AGENT_VERSION)
      ? `出口组和正则、GEOIP、端口、网络类型规则需要节点 Agent ${ROUTER_RULES_AGENT_VERSION} 或更高版本（当前 ${node.version || "未知"}），请先升级节点。`
      : "";
  };

  /** Why this proxy cannot use custom exits or rules, or "" when it can. */
  const routingBlockedReason = (item: PrivateProxyItem) => {
    if (!ROUTED_EXIT_PROXY_TYPES.includes(item.proxyType))
      return "这个协议不支持自定义出口和分流。";
    const node = nodes.find(
      (entry) => String(entry.id) === String(item.nodeId),
    );

    if (node && !agentSupportsExit(node.version, ROUTER_AGENT_VERSION))
      return `该节点的 Agent 版本是 ${node.version || "未知"}，自定义出口和分流需要升级到 ${ROUTER_AGENT_VERSION} 或更高。`;

    return "";
  };

  const saveExit = async () => {
    if (!exitTarget) return;
    if (exitChoice.exitType === "residential" && !exitChoice.exitCountry)
      return toast.error("请选择出口国家");
    if (exitChoice.exitType === "custom" && !exitChoice.exitCustomId)
      return toast.error("请选择自定义出口");
    const hadRouting =
      exitTarget.exitType === "custom" ||
      exitTarget.exitType === "warp" ||
      (exitTarget.routing?.rules?.length || 0) > 0;

    if (
      exitChoice.exitType === "custom" ||
      exitChoice.exitType === "warp" ||
      routeRules.length > 0 ||
      hadRouting
    ) {
      setExitSaving(true);
      const routed = await updatePrivateProxyRouting({
        id: exitTarget.id,
        exitType: exitChoice.exitType,
        exitCountry:
          exitChoice.exitType === "residential"
            ? exitChoice.exitCountry
            : undefined,
        exitCustomId:
          exitChoice.exitType === "custom"
            ? exitChoice.exitCustomId
            : undefined,
        rules: routeRules.map(({ match, values, exit, resolve }) => ({
          match,
          values,
          exit,
          ...(resolve ? { resolve } : {}),
        })),
      });

      setExitSaving(false);
      if (routed.code !== 0)
        return toast.error(routed.msg || "保存出口与分流失败");
      toast.success(
        routeRules.length > 0
          ? `已保存，${routeRules.length} 条分流规则生效中`
          : "出口已保存",
      );
      setExitTarget(null);
      void load();

      return;
    }
    setExitSaving(true);
    const response = await updatePrivateProxyExit(
      exitTarget.id,
      exitChoice.exitType,
      exitChoice.exitType === "residential"
        ? exitChoice.exitCountry
        : undefined,
    );

    setExitSaving(false);
    if (response.code !== 0) return toast.error(response.msg || "修改出口失败");
    toast.success(
      exitChoice.exitType === "residential"
        ? "出口已修改，正在连接家宽出口"
        : "已改回节点自身 IP",
    );
    setExitTarget(null);
    void load();
  };

  const [switchingExit, setSwitchingExit] = useState<number | null>(null);
  const [switchLink, setSwitchLink] = useState("");

  const changeExit = async (item: PrivateProxyItem) => {
    if (!item.exit?.groupId) return toast.error("出口还在准备，请稍后再试");
    setSwitchingExit(item.id);
    const response = await switchOwnExit(item.id);

    setSwitchingExit(null);
    if (response.code !== 0) return toast.error(response.msg || "切换失败");
    toast.success(`已切换到 ${response.data?.exitIp || "新的出口"}`);
    void load();
  };

  const loadSwitchLink = async (item: PrivateProxyItem, reset = false) => {
    if (reset && !window.confirm("重置后旧链接立即失效，确认重置吗？")) return;
    const response = await getExitSwitchToken(item.id, reset);

    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "读取链接失败");
    setSwitchLink(
      `${window.location.origin}/api/v1/open_api/exit-switch?token=${response.data.token}`,
    );
    if (reset) toast.success("链接已重置");
  };

  const copySwitchLink = async (item: PrivateProxyItem) => {
    const response = await getExitSwitchToken(item.id);

    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "读取链接失败");
    await copyText(
      `${window.location.origin}/api/v1/open_api/exit-switch?token=${response.data.token}`,
      "远程切换链接",
    );
  };

  const showEvents = (item: PrivateProxyItem) => {
    if (!item.exit?.groupId) return toast.error("出口还在准备，暂时没有记录");
    setEventsTarget({
      groupId: item.exit.groupId,
      title: `${item.exit.countryName}家宽出口的切换记录`,
    });
  };

  const control = async (
    item: PrivateProxyItem,
    action: "pause" | "resume" | "delete",
  ) => {
    if (
      action === "delete" &&
      !window.confirm(`确认删除代理“${item.name}”吗？`)
    )
      return;
    const response =
      action === "pause"
        ? await pausePrivateProxy(item.id)
        : action === "resume"
          ? await resumePrivateProxy(item.id)
          : await deletePrivateProxy(item.id);

    if (response.code !== 0) return toast.error(response.msg || "操作失败");
    toast.success(
      action === "pause"
        ? "代理已暂停"
        : action === "resume"
          ? "代理已恢复"
          : response.data || "代理已删除",
    );
    void load();
  };

  const configRows = clientConfig
    ? [
        ["服务器", clientConfig.host],
        ["端口", String(clientConfig.port)],
        ...(clientConfig.username ? [["用户名", clientConfig.username]] : []),
        ...(clientConfig.password ? [["密码", clientConfig.password]] : []),
        ...(clientConfig.cipher ? [["加密方式", clientConfig.cipher]] : []),
        ...(clientConfig.clientId ? [["UUID", clientConfig.clientId]] : []),
        ...(clientConfig.publicKey
          ? [["REALITY 公钥", clientConfig.publicKey]]
          : []),
        ...(clientConfig.shortId ? [["Short ID", clientConfig.shortId]] : []),
        ...(clientConfig.serverName
          ? [
              [
                isReality(clientConfig.proxyType) ? "伪装域名" : "SNI / Host",
                clientConfig.serverName,
              ],
            ]
          : []),
        ...(clientConfig.flow ? [["流控", clientConfig.flow]] : []),
        ...(clientConfig.method ? [["加密方式", clientConfig.method]] : []),
        ...(clientConfig.transport
          ? [["传输方式", clientConfig.transport]]
          : []),
        ...(clientConfig.path
          ? [
              [
                clientConfig.transport === "grpc" ? "gRPC 服务名" : "路径",
                clientConfig.path,
              ],
            ]
          : []),
        ...(clientConfig.transport
          ? [["TLS", clientConfig.tls ? "开启" : "关闭"]]
          : []),
        ...(clientConfig.obfsPassword
          ? [["混淆密码", clientConfig.obfsPassword]]
          : []),
        ...(clientConfig.handshakeServer
          ? [["握手网站", clientConfig.handshakeServer]]
          : []),
        ...(clientConfig.innerPassword
          ? [["SS 2022 密钥", clientConfig.innerPassword]]
          : []),
        ...(clientConfig.certSha256
          ? [["证书指纹 SHA-256", clientConfig.certSha256]]
          : []),
        ...(clientConfig.clientPrivateKey
          ? [["客户端私钥", clientConfig.clientPrivateKey]]
          : []),
        ...(clientConfig.serverPublicKey
          ? [["服务器公钥", clientConfig.serverPublicKey]]
          : []),
        ...(clientConfig.clientAddress
          ? [["客户端地址", clientConfig.clientAddress]]
          : []),
      ]
    : [];

  return (
    <PageShell className="space-y-5" width="wide">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">私人代理</h1>
        </div>
        <div className="flex flex-wrap gap-2">
          {isAdmin && hasResidentialExit && (
            <Button
              startContent={<Home size={17} />}
              variant="flat"
              onPress={() => navigate("/exit-pool")}
            >
              出口管理
            </Button>
          )}
          <Button
            startContent={<Rss size={17} />}
            variant="flat"
            onPress={() => setSubscriptionOpen(true)}
          >
            订阅链接
          </Button>
          <Button
            color="primary"
            startContent={<Plus size={18} />}
            onPress={() => setModalOpen(true)}
          >
            新建代理
          </Button>
        </div>
      </header>

      <section className="grid grid-cols-3 gap-4">
        <StatsCard
          color="success"
          icon={<Play className="h-5 w-5" />}
          title="运行中"
          value={summary.active}
        />
        <StatsCard
          color="default"
          icon={<Pause className="h-5 w-5" />}
          title="已暂停"
          value={summary.paused}
        />
        <StatsCard
          color={summary.attention ? "danger" : "default"}
          icon={<TriangleAlert className="h-5 w-5" />}
          title="需要处理"
          value={summary.attention}
        />
      </section>

      {loading ? (
        <div className="flex min-h-40 items-center justify-center">
          <Spinner />
        </div>
      ) : items.length === 0 ? (
        <div className="flex min-h-40 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-divider text-default-500">
          <ShieldCheck size={30} />
          <span>暂无私人代理</span>
        </div>
      ) : (
        <Accordion
          className="min-w-0 overflow-hidden px-0"
          selectionMode="multiple"
          variant="bordered"
        >
          {nodeGroups.map((group) => (
            <AccordionItem
              key={String(group.nodeId)}
              aria-label={`${group.nodeName} 的私人代理`}
              classNames={{
                base: `min-w-0 ${group.attentionCount ? "border-danger-200" : "border-divider"}`,
                trigger: "min-w-0 gap-3 px-4 py-4 md:px-5",
                content: "min-w-0 overflow-hidden pb-0",
              }}
              startContent={
                <span
                  className={`flex h-10 w-10 flex-none items-center justify-center rounded-md ${group.nodeOnline ? "bg-success-50 text-success dark:bg-success-500/10" : "bg-danger-50 text-danger dark:bg-danger-500/10"}`}
                >
                  <Server size={19} />
                </span>
              }
              title={
                <div className="flex min-w-0 flex-1 flex-col gap-3 overflow-hidden pr-1 lg:flex-row lg:items-center lg:justify-between lg:pr-2">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="min-w-0 break-words font-semibold">
                        {group.nodeName}
                      </span>
                      <Chip
                        className="flex-none"
                        color={group.nodeOnline ? "success" : "danger"}
                        size="sm"
                        variant="flat"
                      >
                        {group.nodeOnline ? "在线" : "离线"}
                      </Chip>
                    </div>
                    {isAdmin && (
                      <p className="mt-1 truncate font-mono text-xs font-normal text-default-500">
                        {group.publicHost || "未设置公网地址"}
                      </p>
                    )}
                    <p className="mt-1 line-clamp-2 break-words text-xs font-normal text-default-500">
                      {group.protocols.join(" · ")}
                    </p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2 lg:justify-end">
                    {group.attentionCount > 0 && (
                      <Chip color="danger" size="sm" variant="flat">
                        {group.attentionCount} 个异常
                      </Chip>
                    )}
                    <Chip color="success" size="sm" variant="flat">
                      运行 {group.activeCount}/{group.proxies.length}
                    </Chip>
                    <Chip color="primary" size="sm" variant="flat">
                      {group.proxies.length} 个代理
                    </Chip>
                  </div>
                </div>
              }
            >
              <div className="border-t border-divider px-1 py-4">
                <ResourceCardGrid>
                  {group.proxies.map((item) => {
                    const meta = stateMeta[item.state];
                    const protocol = protocolMeta[item.proxyType];
                    const usedFlow = (item.inFlow || 0) + (item.outFlow || 0);
                    const canControl = isAdmin || !item.granted;
                    const problem = item.unavailableReason || item.lastError;
                    const smallIcon = (
                      label: string,
                      icon: ReactNode,
                      onPress: () => void,
                    ) => (
                      <Tooltip content={label}>
                        <Button
                          isIconOnly
                          aria-label={label}
                          className="h-6 w-6 min-w-6"
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
                        key={item.id}
                        actions={[
                          {
                            label: "连接",
                            icon: <KeyRound size={14} />,
                            color: "primary",
                            isLoading: configLoading,
                            onPress: () => showClientConfig(item),
                          },
                          {
                            label: "修改",
                            icon: <Pencil size={14} />,
                            color: "warning",
                            onPress: () => setEditTarget(item),
                          },
                          ...(canControl
                            ? [
                                {
                                  label: "删除",
                                  icon: <Trash2 size={14} />,
                                  color: "danger" as const,
                                  onPress: () => control(item, "delete"),
                                },
                              ]
                            : []),
                        ]}
                        blocks={[
                          {
                            label: "连接地址",
                            status: {
                              text: transportLabel(item.proxyType),
                              color: "primary",
                            },
                            rows: [
                              {
                                label: "地址",
                                value: `${item.connectHost || item.publicHost || "未设置"}:${item.listenPort}`,
                              },
                              ...(item.transport && item.transport !== "tcp"
                                ? [{ label: "传输", value: item.transport }]
                                : []),
                            ],
                          },
                          ...(item.exitType === "residential" && item.exit
                            ? [
                                {
                                  label: `出口 · ${item.exit.countryName}家宽`,
                                  status: {
                                    text: exitStatusMeta(item.exit.status)
                                      .label,
                                    color: exitStatusMeta(item.exit.status)
                                      .color as ChipColor,
                                  },
                                  danger: [
                                    "no_exit",
                                    "node_offline",
                                    "agent_error",
                                    "setup_failed",
                                  ].includes(item.exit.status),
                                  rows: [
                                    {
                                      value: `${exitSummary(item.exit)}${
                                        item.exit.status === "ok" &&
                                        item.exit.switchesToday !== undefined
                                          ? ` · 今日切换 ${item.exit.switchesToday} 次`
                                          : ""
                                      }`,
                                      mono: false,
                                    },
                                  ],
                                },
                              ]
                            : []),
                          ...(item.exitType === "custom" ||
                          item.exitType === "warp" ||
                          (item.routing?.rules?.length || 0) > 0
                            ? [
                                {
                                  label: "出口与分流",
                                  status: {
                                    text: `${item.routing?.rules?.length || 0} 条规则`,
                                    color: "secondary" as ChipColor,
                                  },
                                  rows: [
                                    {
                                      label: "默认",
                                      value:
                                        item.exitType === "warp"
                                          ? "Cloudflare WARP"
                                          : item.exitType === "custom"
                                          ? `自定义 · ${item.routing?.exitCustomName || `#${item.routing?.exitCustomId}`}`
                                          : item.exitType === "residential"
                                            ? `家宽 · ${item.exit?.countryName || item.exitCountry}`
                                            : "节点自身 IP",
                                      mono: false,
                                    },
                                  ],
                                },
                              ]
                            : []),
                        ]}
                        chips={[
                          {
                            text: `↑${formatBytes(item.inFlow)}`,
                            color: "primary",
                          },
                          {
                            text: `↓${formatBytes(item.outFlow)}`,
                            color: "success",
                          },
                          ...(isAdmin && item.granted
                            ? [
                                {
                                  text: "管理员授权",
                                  color: "secondary" as const,
                                },
                              ]
                            : []),
                        ]}
                        danger={Boolean(problem) || item.state === "error"}
                        facts={[
                          ...(isAdmin
                            ? [
                                { label: "访问控制", value: protocol.access },
                                {
                                  label: "来源白名单",
                                  value: item.allowedCidrs
                                    ? `${item.allowedCidrs.split(",").length} 条`
                                    : "任意来源",
                                },
                              ]
                            : [
                                {
                                  label: "流量额度",
                                  value:
                                    item.flowUnlimited === 1
                                      ? "不限"
                                      : `${item.flowLimit || 0} GB`,
                                },
                                {
                                  label: "已用 / 剩余",
                                  value:
                                    item.flowUnlimited === 1
                                      ? formatBytes(usedFlow)
                                      : `${formatBytes(usedFlow)} / ${formatBytes(item.remainingFlow || 0)}`,
                                },
                              ]),
                          {
                            label: "有效期",
                            value: item.expiresAt
                              ? new Date(item.expiresAt).toLocaleDateString()
                              : "永久",
                          },
                          isAdmin
                            ? {
                                label: "最近使用",
                                value: item.lastActiveAt
                                  ? new Date(item.lastActiveAt).toLocaleString()
                                  : "-",
                              }
                            : {
                                label: "剩余时间",
                                value: formatRemainingTime(item.remainingTime),
                              },
                        ]}
                        headerExtra={
                          <>
                            {smallIcon(
                              "流量记录",
                              <BarChart3 size={14} />,
                              () => setTrafficTarget(item),
                            )}
                            {item.state === "active" &&
                              (isAdmin
                                ? EXIT_PROXY_TYPES.includes(item.proxyType)
                                : ROUTED_EXIT_PROXY_TYPES.includes(
                                    item.proxyType,
                                  )) &&
                              smallIcon("出口与分流", <Home size={14} />, () =>
                                openExitSettings(item),
                              )}
                          </>
                        }
                        notice={
                          problem
                            ? {
                                icon: <TriangleAlert size={15} />,
                                label: "需要处理",
                                value: <span title={problem}>{problem}</span>,
                                tone: "danger",
                              }
                            : item.state === "active"
                              ? {
                                  icon: <Stethoscope size={15} />,
                                  label: "自检",
                                  value: !item.lastCheckAt
                                    ? "尚未自检"
                                    : item.lastCheckOk
                                      ? `正常 · 出口 ${item.lastCheckIp} · ${item.lastCheckLatency} ms`
                                      : `失败：${item.lastCheckError || "原因未知"}`,
                                  tone: !item.lastCheckAt
                                    ? "default"
                                    : item.lastCheckOk
                                      ? "success"
                                      : "danger",
                                  trailing: (
                                    <Tooltip content="立即自检">
                                      <Button
                                        isIconOnly
                                        aria-label={`自检 ${item.name}`}
                                        className="h-7 w-7 min-w-7"
                                        isLoading={testing === item.id}
                                        size="sm"
                                        variant="light"
                                        onPress={() => runTest(item)}
                                      >
                                        <RefreshCw size={15} />
                                      </Button>
                                    </Tooltip>
                                  ),
                                }
                              : undefined
                        }
                        status={{ text: meta.label, color: meta.color }}
                        subtitles={[
                          `${protocol.label}${isAdmin ? ` · ${item.ownerUserName}` : ""}`,
                        ]}
                        title={item.name}
                        toggle={
                          canControl &&
                          (item.state === "active" || item.state === "paused")
                            ? {
                                isSelected: item.state === "active",
                                label:
                                  item.state === "active" ? "暂停" : "恢复",
                                onChange: () =>
                                  control(
                                    item,
                                    item.state === "active"
                                      ? "pause"
                                      : "resume",
                                  ),
                              }
                            : undefined
                        }
                      >
                        {item.exitType === "residential" &&
                          item.exit &&
                          item.state === "active" && (
                            <div className="flex flex-wrap gap-1">
                              <Button
                                className="h-7 min-w-0 px-2 text-xs"
                                isDisabled={!item.exit.standbyReady}
                                isLoading={switchingExit === item.id}
                                size="sm"
                                startContent={
                                  switchingExit !== item.id && (
                                    <Shuffle size={13} />
                                  )
                                }
                                variant="flat"
                                onPress={() => changeExit(item)}
                              >
                                换一个出口
                              </Button>
                              {!isAdmin && (
                                <Button
                                  className="h-7 min-w-0 px-2 text-xs"
                                  size="sm"
                                  startContent={<Link2 size={13} />}
                                  variant="flat"
                                  onPress={() => copySwitchLink(item)}
                                >
                                  切换链接
                                </Button>
                              )}
                              {isAdmin && (
                                <Button
                                  className="h-7 min-w-0 px-2 text-xs"
                                  size="sm"
                                  startContent={<History size={13} />}
                                  variant="flat"
                                  onPress={() => showEvents(item)}
                                >
                                  切换记录
                                </Button>
                              )}
                            </div>
                          )}
                      </ResourceCard>
                    );
                  })}
                </ResourceCardGrid>
              </div>
            </AccordionItem>
          ))}
        </Accordion>
      )}

      <Modal
        isOpen={modalOpen}
        scrollBehavior="inside"
        size="3xl"
        onOpenChange={setModalOpen}
      >
        <ModalContent>
          <ModalHeader>新建私人代理</ModalHeader>
          <ModalBody className="gap-4">
            <Tabs
              fullWidth
              aria-label="代理协议分类"
              selectedKey={protocolGroup}
              variant="underlined"
              onSelectionChange={(key) => {
                const group = String(key) as ProxyGroup;

                setProtocolGroup(group);
                selectProtocol(protocolGroups[group].protocols[0]);
              }}
            >
              {Object.entries(protocolGroups).map(([key, group]) => (
                <Tab key={key} title={group.label} />
              ))}
            </Tabs>
            <Tabs
              fullWidth
              aria-label="代理协议"
              selectedKey={form.proxyType}
              onSelectionChange={(key) =>
                selectProtocol(String(key) as PrivateProxyType)
              }
            >
              {protocolGroups[protocolGroup].protocols.map((proxyType) => (
                <Tab key={proxyType} title={protocolMeta[proxyType].label} />
              ))}
            </Tabs>
            <div className="grid gap-4 md:grid-cols-2">
              <Input
                label="代理名称"
                value={form.name}
                onValueChange={(value) => setForm({ ...form, name: value })}
              />
              <Select
                label="服务器节点"
                placeholder="选择在线节点"
                selectedKeys={form.nodeId ? [form.nodeId] : []}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    nodeId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {nodes
                  .filter((node) => node.status === 1)
                  .map((node) => (
                    <SelectItem key={String(node.id)} textValue={node.name}>
                      {node.name} · {node.serverIp || node.ip || "未设置地址"}
                      {node.quotaAvailable === false
                        ? ` · ${node.unavailableReason}`
                        : ""}
                    </SelectItem>
                  ))}
              </Select>
              <Input
                label="监听端口"
                type="number"
                value={form.listenPort}
                onValueChange={(value) =>
                  setForm({ ...form, listenPort: value })
                }
              />
              <Input
                label="监听 IP（可选）"
                placeholder="留空监听全部地址"
                value={form.bindIp}
                onValueChange={(value) => setForm({ ...form, bindIp: value })}
              />
              {usesAccount(form.proxyType) && (
                <>
                  <Input
                    label="代理用户名"
                    value={form.authUsername}
                    onValueChange={(value) =>
                      setForm({ ...form, authUsername: value })
                    }
                  />
                  <Input
                    label="代理密码"
                    type="password"
                    value={form.authPassword}
                    onValueChange={(value) =>
                      setForm({ ...form, authPassword: value })
                    }
                  />
                </>
              )}
              {form.proxyType === "shadowsocks" && (
                <>
                  <Select
                    label="加密方式"
                    selectedKeys={[form.cipher]}
                    onSelectionChange={(keys) =>
                      setForm({
                        ...form,
                        cipher: String(Array.from(keys)[0]) as Cipher,
                      })
                    }
                  >
                    <SelectItem key="aes-256-gcm">AES-256-GCM</SelectItem>
                    <SelectItem key="aes-128-gcm">AES-128-GCM</SelectItem>
                    <SelectItem key="chacha20-ietf-poly1305">
                      ChaCha20-IETF-Poly1305
                    </SelectItem>
                  </Select>
                  <Input
                    endContent={
                      <Button
                        isIconOnly
                        aria-label="生成随机密码"
                        size="sm"
                        title="生成随机密码"
                        variant="light"
                        onPress={() =>
                          setForm({ ...form, authPassword: randomSecret() })
                        }
                      >
                        <KeyRound size={16} />
                      </Button>
                    }
                    label="连接密码"
                    type="password"
                    value={form.authPassword}
                    onValueChange={(value) =>
                      setForm({ ...form, authPassword: value })
                    }
                  />
                </>
              )}
              {usesSecret(form.proxyType) &&
                form.proxyType !== "shadowsocks" && (
                  <Input
                    className={
                      form.proxyType === "tuic" || form.proxyType === "anytls"
                        ? "md:col-span-2"
                        : ""
                    }
                    description={
                      form.proxyType === "hysteria2" ||
                      form.proxyType === "tuic"
                        ? "QUIC 使用 UDP；请确认节点防火墙和云厂商安全组已放行该端口。"
                        : form.proxyType === "shadowtls"
                          ? "ShadowTLS 外层的密码；内层 SS 2022 密钥由面板生成。"
                          : "自动使用节点生成的 TLS 证书，订阅和连接信息里附带证书指纹。"
                    }
                    endContent={
                      <Button
                        isIconOnly
                        aria-label="生成随机密钥"
                        size="sm"
                        title="生成随机密钥"
                        variant="light"
                        onPress={() =>
                          setForm({ ...form, authPassword: randomSecret() })
                        }
                      >
                        <KeyRound size={16} />
                      </Button>
                    }
                    label={`${protocolMeta[form.proxyType].label} 密钥`}
                    type="password"
                    value={form.authPassword}
                    onValueChange={(value) =>
                      setForm({ ...form, authPassword: value })
                    }
                  />
                )}
              {form.proxyType === "shadowsocks2022" && (
                <Select
                  className="md:col-span-2"
                  description="密钥由面板随机生成，创建后在连接信息里查看。"
                  label="加密方式"
                  selectedKeys={[form.method]}
                  onSelectionChange={(keys) =>
                    setForm({
                      ...form,
                      method: String(Array.from(keys)[0]) as Ss2022Method,
                    })
                  }
                >
                  <SelectItem key="2022-blake3-aes-128-gcm">
                    2022-blake3-aes-128-gcm（推荐）
                  </SelectItem>
                  <SelectItem key="2022-blake3-aes-256-gcm">
                    2022-blake3-aes-256-gcm
                  </SelectItem>
                  <SelectItem key="2022-blake3-chacha20-poly1305">
                    2022-blake3-chacha20-poly1305
                  </SelectItem>
                </Select>
              )}
              {(form.proxyType === "vmess" || form.proxyType === "vless") && (
                <>
                  <Select
                    label="传输方式"
                    selectedKeys={[form.transport]}
                    onSelectionChange={(keys) =>
                      setForm({
                        ...form,
                        transport: String(
                          Array.from(keys)[0],
                        ) as PrivateProxyTransport,
                      })
                    }
                  >
                    <SelectItem key="ws">WebSocket（可走 CDN）</SelectItem>
                    <SelectItem key="grpc">gRPC（可走 CDN）</SelectItem>
                    <SelectItem key="httpupgrade">
                      HTTPUpgrade（可走 CDN）
                    </SelectItem>
                    <SelectItem key="tcp">TCP（直连）</SelectItem>
                  </Select>
                  <Input
                    description={
                      form.tls
                        ? "TLS 证书域名，留空为 cloudnest.local（自签证书）"
                        : "走 CDN 时填写接入 CDN 的域名，作为请求的 Host"
                    }
                    label="域名（可选）"
                    placeholder="proxy.example.com"
                    value={form.serverName}
                    onValueChange={(value) =>
                      setForm({ ...form, serverName: value })
                    }
                  />
                  <Switch
                    className="md:col-span-2"
                    isSelected={form.tls}
                    onValueChange={(value) => setForm({ ...form, tls: value })}
                  >
                    使用 TLS（节点自签证书；经 Cloudflare 等 CDN
                    中转时一般关闭，由 CDN 提供 HTTPS）
                  </Switch>
                </>
              )}
              {form.proxyType === "shadowtls" && (
                <CamouflagePicker
                  className="md:col-span-2"
                  label="握手网站"
                  nodeId={form.nodeId ? Number(form.nodeId) : undefined}
                  value={form.handshakeServer}
                  onChange={(value) =>
                    setForm({ ...form, handshakeServer: value })
                  }
                />
              )}
              {form.proxyType === "hysteria2" && (
                <Switch
                  className="md:col-span-2"
                  isSelected={form.obfs}
                  onValueChange={(value) => setForm({ ...form, obfs: value })}
                >
                  开启 salamander 混淆（QUIC
                  被限速或干扰时使用，客户端需同时开启）
                </Switch>
              )}
              {protocolHints[form.proxyType] && (
                <div className="rounded-md border border-divider bg-default-50 px-3 py-3 text-sm text-default-600 md:col-span-2">
                  {protocolHints[form.proxyType]}
                </div>
              )}
              {protocolBlockedReason() && (
                <div className="rounded-md bg-warning-50 px-3 py-3 text-sm text-warning-700 dark:bg-warning-500/10 dark:text-warning-400 md:col-span-2">
                  {protocolBlockedReason()}
                </div>
              )}
              {form.proxyType === "wireguard" && (
                <div className="rounded-md border border-divider bg-default-50 px-3 py-3 text-sm text-default-600 md:col-span-2">
                  创建后生成独立的 WireGuard 客户端配置。请使用 WireGuard
                  客户端导入配置文件；仅支持 Linux 节点 Agent
                  2.38.0+，该协议使用 UDP。
                </div>
              )}
              {isReality(form.proxyType) && (
                <CamouflagePicker
                  className="md:col-span-2"
                  label="REALITY 伪装站"
                  nodeId={form.nodeId ? Number(form.nodeId) : undefined}
                  value={form.realityServerName}
                  onChange={(value) =>
                    setForm({ ...form, realityServerName: value })
                  }
                />
              )}
            </div>
            <Input
              description="域名指向节点、或节点有多个公网地址时填写；导入链接和订阅会使用这个地址。留空使用节点地址。"
              label="连接地址（可选）"
              placeholder="proxy.example.com"
              value={form.connectHost}
              onValueChange={(value) =>
                setForm({ ...form, connectHost: value })
              }
            />
            {form.proxyType !== "wireguard" ? (
              <Textarea
                description={
                  isAdvancedRuntime(form.proxyType)
                    ? `需要节点 Agent ${PROTOCOL_AGENT_VERSION} 或更高版本。`
                    : undefined
                }
                label="来源 IP 白名单（可选）"
                minRows={2}
                placeholder="203.0.113.10/32, 2001:db8::/64"
                value={form.allowedCidrs}
                onValueChange={(value) =>
                  setForm({ ...form, allowedCidrs: value })
                }
              />
            ) : (
              <div className="rounded-md border border-divider bg-default-50 px-3 py-3 text-sm text-default-600">
                WireGuard 由节点独立运行时管理，仅支持 Linux 节点 Agent
                2.38.0+；来源 IP 白名单不适用于 WireGuard。
              </div>
            )}
            {isAdmin && (
              <div className="border-t border-divider pt-4">
                <ExitPicker
                  blockedReason={exitBlockedReason(form.proxyType, form.nodeId)}
                  countries={exitInfo?.countries || []}
                  error={exitInfoError}
                  loading={exitInfoLoading && !exitInfo}
                  value={{
                    exitType: form.exitType,
                    exitCountry: form.exitCountry,
                  }}
                  onChange={(value) => setForm({ ...form, ...value })}
                />
              </div>
            )}
            <div className="grid items-center gap-4 border-t border-divider pt-4 md:grid-cols-2">
              <Switch
                isSelected={form.permanent}
                onValueChange={(value) =>
                  setForm({ ...form, permanent: value })
                }
              >
                永久有效
              </Switch>
              {!form.permanent && (
                <Input
                  label="有效期（小时）"
                  type="number"
                  value={form.leaseHours}
                  onValueChange={(value) =>
                    setForm({ ...form, leaseHours: value })
                  }
                />
              )}
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setModalOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={submitting}
              startContent={!submitting && <Globe2 size={17} />}
              onPress={submit}
            >
              创建代理
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={Boolean(exitTarget)}
        scrollBehavior="inside"
        size="2xl"
        onOpenChange={(open) => {
          if (!open) setExitTarget(null);
        }}
      >
        <ModalContent>
          <ModalHeader>
            {exitTarget ? `“${exitTarget.name}”的出口与分流` : "出口与分流"}
          </ModalHeader>
          <ModalBody>
            {exitTarget && routingBlockedReason(exitTarget) && (
              <ExitPicker
                blockedReason={
                  isAdmin
                    ? exitBlockedReason(exitTarget.proxyType, exitTarget.nodeId)
                    : "家宽出口目前仅管理员可以配置。"
                }
                countries={exitInfo?.countries || []}
                customBlockedReason={routingBlockedReason(exitTarget)}
                customExits={
                  ROUTED_EXIT_PROXY_TYPES.includes(exitTarget.proxyType)
                    ? proxyExits
                    : undefined
                }
                error={exitInfoError}
                loading={exitInfoLoading && !exitInfo}
                title="出口"
                value={exitChoice}
                onChange={setExitChoice}
              />
            )}
            {exitTarget && !routingBlockedReason(exitTarget) && (
              <div className="space-y-5">
                <RoutingSummary
                  countries={exitInfo?.countries || []}
                  customExits={proxyExits}
                  rules={routeRules}
                  value={exitChoice}
                />
                <RouteRulesEditor
                  allowResidential={
                    isAdmin &&
                    !exitBlockedReason(exitTarget.proxyType, exitTarget.nodeId)
                  }
                  catalog={ruleCatalog}
                  countries={exitInfo?.countries || []}
                  customExits={proxyExits}
                  defaultExit={exitKeyOf(exitChoice)}
                  newerRulesBlocked={newerRulesBlocked(exitTarget)}
                  warpBlocked={warpBlocked(exitTarget)}
                  presets={routePresets}
                  rules={routeRules}
                  rulesets={routeRulesets}
                  onChange={setRouteRules}
                  onRulesetsChanged={loadRoutingOptions}
                />
                <div className="border-t border-divider pt-4">
                  <DefaultExitSelect
                    allowResidential={
                      isAdmin &&
                      !exitBlockedReason(exitTarget.proxyType, exitTarget.nodeId)
                    }
                    countries={exitInfo?.countries || []}
                    customExits={proxyExits}
                    residentialBlockedReason={
                      isAdmin
                        ? exitBlockedReason(
                            exitTarget.proxyType,
                            exitTarget.nodeId,
                          )
                        : undefined
                    }
                    value={exitChoice}
                    warpBlocked={warpBlocked(exitTarget)}
                    onChange={setExitChoice}
                  />
                </div>
                {routeRules.length > 0 && (
                  <p className="text-xs text-default-500">
                    命中规则的出口不可用时，这部分连接会断开，不会改走节点自身
                    IP。
                  </p>
                )}
              </div>
            )}
            <p className="text-xs text-default-500">
              保存后代理会重新启动，正在进行的连接需要重连；设备上填写的地址、端口和密码都不用改。
            </p>
            {exitTarget?.exitType === "residential" && (
              <div className="space-y-2 border-t border-divider pt-4">
                <div className="text-sm font-medium">远程切换链接</div>
                <p className="text-xs text-default-500">
                  不用登录面板，打开这个链接就会换到备用出口，并显示新的出口
                  IP。可以存成浏览器书签或手机快捷指令。拿到链接的人只能触发换出口，请不要公开。
                </p>
                {switchLink ? (
                  <div className="flex flex-wrap items-center gap-2">
                    <code className="min-w-0 flex-1 break-all rounded-md bg-default-100 px-3 py-2 text-xs">
                      {switchLink}
                    </code>
                    <Button
                      size="sm"
                      startContent={<Copy size={14} />}
                      variant="flat"
                      onPress={() => copyText(switchLink, "链接")}
                    >
                      复制
                    </Button>
                    <Button
                      size="sm"
                      startContent={<RefreshCw size={14} />}
                      variant="light"
                      onPress={() => loadSwitchLink(exitTarget, true)}
                    >
                      重置
                    </Button>
                  </div>
                ) : (
                  <Button
                    size="sm"
                    startContent={<Link2 size={14} />}
                    variant="flat"
                    onPress={() => loadSwitchLink(exitTarget)}
                  >
                    显示链接
                  </Button>
                )}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setExitTarget(null)}>
              取消
            </Button>
            <Button color="primary" isLoading={exitSaving} onPress={saveExit}>
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <ExitEventsModal
        target={eventsTarget}
        onClose={() => setEventsTarget(null)}
      />

      <ProxyEditModal
        canManage={Boolean(editTarget && (isAdmin || !editTarget.granted))}
        target={editTarget}
        onClose={() => setEditTarget(null)}
        onSaved={() => {
          setEditTarget(null);
          void load();
        }}
      />
      <ProxyTrafficModal
        target={trafficTarget}
        onClose={() => setTrafficTarget(null)}
      />
      <ProxySubscriptionModal
        isOpen={subscriptionOpen}
        onClose={() => setSubscriptionOpen(false)}
      />

      <Modal
        isOpen={Boolean(clientConfig)}
        scrollBehavior="inside"
        size="2xl"
        onOpenChange={(open) => {
          if (!open) setClientConfig(null);
        }}
      >
        <ModalContent>
          <ModalHeader>
            {clientConfig
              ? `${protocolMeta[clientConfig.proxyType].label} 连接信息`
              : "连接信息"}
          </ModalHeader>
          <ModalBody>
            <div className="divide-y divide-divider border-y border-divider">
              {configRows.map(([label, value]) => (
                <div
                  key={label}
                  className="grid min-h-12 grid-cols-[120px_1fr_auto] items-center gap-3 py-2"
                >
                  <span className="text-sm text-default-500">{label}</span>
                  <span className="break-all font-mono text-sm">{value}</span>
                  <Button
                    isIconOnly
                    aria-label={`复制${label}`}
                    size="sm"
                    title={`复制${label}`}
                    variant="light"
                    onPress={() => copyText(value, label)}
                  >
                    <Copy size={16} />
                  </Button>
                </div>
              ))}
            </div>
            {clientConfig?.uri && (
              <div className="space-y-2 pt-2">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm font-medium">
                    {clientConfig.proxyType === "wireguard"
                      ? "WireGuard 配置文件"
                      : "一键导入链接"}
                  </span>
                  <div className="flex gap-1">
                    <Button
                      size="sm"
                      startContent={<QrCode size={15} />}
                      variant={showQr ? "solid" : "light"}
                      onPress={() => setShowQr(!showQr)}
                    >
                      二维码
                    </Button>
                    <Button
                      size="sm"
                      startContent={<Copy size={15} />}
                      variant="flat"
                      onPress={() =>
                        copyText(
                          clientConfig.uri || "",
                          clientConfig.proxyType === "wireguard"
                            ? "WireGuard 配置"
                            : "导入链接",
                        )
                      }
                    >
                      复制
                    </Button>
                  </div>
                </div>
                {showQr && (
                  <ProxyQr label="导入二维码" value={clientConfig.uri} />
                )}
                <Textarea
                  isReadOnly
                  classNames={{ input: "font-mono text-xs break-all" }}
                  minRows={clientConfig.proxyType === "wireguard" ? 9 : 3}
                  value={clientConfig.uri}
                />
              </div>
            )}
            {clientConfig && !clientConfig.uri && (
              <p className="rounded-md bg-default-50 px-3 py-2 text-xs leading-5 text-default-600">
                {protocolMeta[clientConfig.proxyType].label}
                没有通用的导入链接，请用下面的 Clash 或 sing-box 一键导入。
              </p>
            )}
            {clientConfig?.linkSkipsCertCheck && (
              <p className="rounded-md bg-warning-50 px-3 py-2 text-xs leading-5 text-warning-700 dark:bg-warning-500/10 dark:text-warning-400">
                节点使用自签证书，这种导入链接无法固定证书，客户端会跳过证书校验。
                更安全的做法是用下面的 Clash 或 sing-box
                一键导入，它们写入了证书指纹，只认这一张证书。
              </p>
            )}
            {clientConfig && configProxyId && (
              <ProxyImport config={clientConfig} proxyId={configProxyId} />
            )}
          </ModalBody>
          <ModalFooter>
            <Button color="primary" onPress={() => setClientConfig(null)}>
              完成
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
