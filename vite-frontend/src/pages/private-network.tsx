import { useEffect, useMemo, useState } from "react";
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
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import {
  Copy,
  Gauge,
  Network,
  Pause,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  RotateCw,
  Route,
  ShieldCheck,
  Trash2,
  Unplug,
  Waypoints,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  createNetworkRouteApplication,
  createVirtualLan,
  deleteNetworkRouteApplication,
  deletePrivateNetwork,
  deleteVirtualLan,
  deployNetworkRouteApplication,
  getNetworkRouteApplications,
  getAwsAccessAccounts,
  getDnsZoneOptions,
  getPrivateNetworkOverview,
  getVirtualLanOverview,
  NetworkRouteApplication,
  pauseNetworkRouteApplication,
  PrivateNetworkGroup,
  PrivateNetworkOverview,
  refreshVirtualLan,
  resumeNetworkRouteApplication,
  savePrivateNetwork,
  testNetworkRouteApplication,
  verifyPrivateNetwork,
  VirtualLanNetwork,
  VirtualLanOverview,
  AwsAccessAccount,
  DnsZoneOption,
} from "@/api";

type NativeMemberForm = {
  nodeId: string;
  privateAddress: string;
  interfaceName: string;
  mtu: string;
};
type RouteHopForm = {
  addressMode: "public" | "private" | "virtual";
  resourceGroupId: string;
  fallbackMode: "fail_closed" | "public";
};

const nativeEmpty = {
  id: undefined as number | undefined,
  name: "",
  networkType: "vpc",
  cidr: "",
  members: [
    { nodeId: "", privateAddress: "", interfaceName: "", mtu: "1500" },
    { nodeId: "", privateAddress: "", interfaceName: "", mtu: "1500" },
  ] as NativeMemberForm[],
};
const automaticEmpty = {
  name: "",
  cidr: "10.88.0.0/24",
  hubNodeId: "",
  listenPort: "51820",
  members: new Set<string>(),
};
const applicationEmpty = {
  name: "",
  nodePath: ["", ""] as string[],
  hops: [
    { addressMode: "public", resourceGroupId: "", fallbackMode: "fail_closed" },
  ] as RouteHopForm[],
  tunnelProtocol: "tls" as "tls" | "quic",
  proxyType: "socks5" as
    | "socks5"
    | "http"
    | "vless_reality"
    | "vless_xhttp_tls",
  bindIp: "",
  listenPort: "1080",
  username: "",
  password: "",
  realityServerName: "www.cloudflare.com",
  xhttpPath: "/xhttp/",
  xhttpMode: "auto" as "auto" | "packet-up" | "stream-up",
  xhttpPaddingBytes: "100-1000",
  xhttpOriginDomain: "",
  xhttpUploadDomain: "",
  xhttpDownloadDomain: "",
  autoProvisionCloudFront: true,
  awsAccessAccountId: "",
  dnsZoneId: "",
};

const stateMeta = (state: string) => {
  if (state === "active" || state === "online" || state === "verified")
    return { label: "可用", color: "success" as const };
  if (state === "paused") return { label: "已暂停", color: "warning" as const };
  if (
    state === "verifying" ||
    state === "deploying" ||
    state === "provisioning"
  )
    return { label: "处理中", color: "primary" as const };
  if (state === "pending")
    return { label: "待验证", color: "default" as const };
  if (state === "degraded")
    return { label: "部分异常", color: "warning" as const };
  return { label: "异常", color: "danger" as const };
};

const networkTypeName = (type: string) =>
  ({ vpc: "云 VPC", cloud_backbone: "云骨干", dedicated: "专线内网" })[type] ||
  type;
const bytes = (value?: number) =>
  value == null
    ? "-"
    : value >= 1024 ** 3
      ? `${(value / 1024 ** 3).toFixed(2)} GB`
      : value >= 1024 ** 2
        ? `${(value / 1024 ** 2).toFixed(1)} MB`
        : `${Math.round(value / 1024)} KB`;
const timeText = (value?: number) =>
  value ? new Date(value).toLocaleString() : "-";

export default function PrivateNetworkPage() {
  const [loading, setLoading] = useState(true);
  const [native, setNative] = useState<PrivateNetworkOverview>({
    minimumAgentVersion: "",
    nodes: [],
    groups: [],
  });
  const [automatic, setAutomatic] = useState<VirtualLanOverview>({
    minimumAgentVersion: "",
    nodes: [],
    connectors: [],
    networks: [],
  });
  const [applications, setApplications] = useState<NetworkRouteApplication[]>(
    [],
  );
  const [busy, setBusy] = useState("");
  const [nativeOpen, setNativeOpen] = useState(false);
  const [automaticOpen, setAutomaticOpen] = useState(false);
  const [applicationOpen, setApplicationOpen] = useState(false);
  const [nativeForm, setNativeForm] = useState(nativeEmpty);
  const [automaticForm, setAutomaticForm] = useState(automaticEmpty);
  const [applicationForm, setApplicationForm] = useState(applicationEmpty);
  const [awsAccounts, setAwsAccounts] = useState<AwsAccessAccount[]>([]);
  const [dnsZones, setDnsZones] = useState<DnsZoneOption[]>([]);

  const onlineNodes = useMemo(
    () => native.nodes.filter((node) => node.status === 1),
    [native.nodes],
  );

  const load = async (blocking = false) => {
    if (blocking) setLoading(true);
    const [nativeRequest, automaticRequest, applicationRequest] =
      await Promise.allSettled([
        getPrivateNetworkOverview(),
        getVirtualLanOverview(),
        getNetworkRouteApplications(),
      ]);
    try {
      if (nativeRequest.status === "rejected") toast.error("加载原生内网失败");
      else {
        const nativeResult = nativeRequest.value;
        if (nativeResult.code === 0) setNative(nativeResult.data);
        else toast.error(nativeResult.msg || "加载原生内网失败");
      }
      if (automaticRequest.status === "rejected")
        toast.error("加载自动组网失败");
      else {
        const automaticResult = automaticRequest.value;
        if (automaticResult.code === 0) setAutomatic(automaticResult.data);
        else toast.error(automaticResult.msg || "加载自动组网失败");
      }
      if (applicationRequest.status === "rejected")
        toast.error("加载出口应用失败");
      else {
        const applicationResult = applicationRequest.value;
        if (applicationResult.code === 0)
          setApplications(applicationResult.data.applications || []);
        else toast.error(applicationResult.msg || "加载出口应用失败");
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load(true);
  }, []);

  useEffect(() => {
    if (!applicationOpen || applicationForm.proxyType !== "vless_xhttp_tls") return;
    void Promise.allSettled([getAwsAccessAccounts(), getDnsZoneOptions()]).then(([aws, zones]) => {
      if (aws.status === "fulfilled" && aws.value.code === 0) setAwsAccounts(aws.value.data.accounts || []);
      if (zones.status === "fulfilled" && zones.value.code === 0) setDnsZones(zones.value.data || []);
    });
  }, [applicationOpen, applicationForm.proxyType]);

  const saveNative = async () => {
    if (
      !nativeForm.name.trim() ||
      nativeForm.members.length < 2 ||
      nativeForm.members.some(
        (item) => !item.nodeId || !item.privateAddress.trim(),
      )
    )
      return toast.error("请填写名称和至少两台服务器的内网地址");
    setBusy("native-save");
    try {
      const result = await savePrivateNetwork({
        id: nativeForm.id,
        name: nativeForm.name.trim(),
        networkType: nativeForm.networkType,
        cidr: nativeForm.cidr.trim(),
        members: nativeForm.members.map((item) => ({
          nodeId: Number(item.nodeId),
          privateAddress: item.privateAddress.trim(),
          interfaceName: item.interfaceName.trim(),
          mtu: Number(item.mtu) || 1500,
        })),
      });
      if (result.code !== 0) return toast.error(result.msg || "保存失败");
      setNative(result.data);
      setNativeOpen(false);
      toast.success("原生内网组已保存，请执行双向验证");
    } finally {
      setBusy("");
    }
  };

  const editNative = (group: PrivateNetworkGroup) => {
    setNativeForm({
      id: group.id,
      name: group.name,
      networkType: group.networkType,
      cidr: group.cidr || "",
      members: group.members.map((item) => ({
        nodeId: String(item.nodeId),
        privateAddress: item.privateAddress,
        interfaceName: item.interfaceName || "",
        mtu: String(item.mtu || 1500),
      })),
    });
    setNativeOpen(true);
  };

  const nativeAction = async (
    group: PrivateNetworkGroup,
    action: "verify" | "delete",
  ) => {
    setBusy(`${action}-${group.id}`);
    try {
      const result =
        action === "verify"
          ? await verifyPrivateNetwork(group.id)
          : await deletePrivateNetwork(group.id);
      if (result.code !== 0) return toast.error(result.msg || "操作失败");
      setNative(result.data);
      toast.success(action === "verify" ? "双向内网验证通过" : "内网组已删除");
    } finally {
      setBusy("");
    }
  };

  const createAutomatic = async () => {
    const members = Array.from(automaticForm.members);
    if (
      !automaticForm.name.trim() ||
      !automaticForm.hubNodeId ||
      members.length < 2
    )
      return toast.error("请选择至少两台服务器，并指定一台公网可达节点");
    setBusy("automatic-save");
    try {
      const result = await createVirtualLan({
        name: automaticForm.name.trim(),
        cidr: automaticForm.cidr.trim(),
        hubNodeId: Number(automaticForm.hubNodeId),
        listenPort: Number(automaticForm.listenPort),
        members: members.map((id) => ({
          targetType: "node",
          targetId: Number(id),
        })),
      });
      if (result.code !== 0) return toast.error(result.msg || "自动组网失败");
      setAutomatic(result.data);
      setAutomaticOpen(false);
      toast.success("Agent 已完成自动组网");
    } finally {
      setBusy("");
    }
  };

  const automaticAction = async (
    network: VirtualLanNetwork,
    action: "refresh" | "delete",
  ) => {
    setBusy(`auto-${action}-${network.id}`);
    try {
      const result =
        action === "refresh"
          ? await refreshVirtualLan(network.id)
          : await deleteVirtualLan(network.id);
      if (result.code !== 0) return toast.error(result.msg || "操作失败");
      setAutomatic(result.data);
      toast.success(action === "refresh" ? "组网状态已刷新" : "自动组网已删除");
    } finally {
      setBusy("");
    }
  };

  const syncApplicationPath = (path: string[]) => {
    setApplicationForm((prev) => ({
      ...prev,
      nodePath: path,
      hops: Array.from(
        { length: Math.max(1, path.length - 1) },
        (_, index) =>
          prev.hops[index] || {
            addressMode: "public",
            resourceGroupId: "",
            fallbackMode: "fail_closed",
          },
      ),
    }));
  };

  const createApplication = async () => {
    const nodePath = applicationForm.nodePath.map(Number);
    if (
      !applicationForm.name.trim() ||
      nodePath.some((id) => !id) ||
      new Set(nodePath).size !== nodePath.length
    )
      return toast.error("请填写名称并选择不重复的 B、C、D 路径节点");
    if (!applicationForm.listenPort) return toast.error("请填写入口端口");
    if (applicationForm.proxyType === "vless_reality") {
      if (!applicationForm.realityServerName.trim())
        return toast.error("请填写 REALITY 伪装域名");
    } else if (applicationForm.proxyType === "vless_xhttp_tls") {
      if (!applicationForm.xhttpPath.trim().startsWith("/"))
        return toast.error("XHTTP 路径必须以 / 开头");
      if (applicationForm.autoProvisionCloudFront && (!applicationForm.awsAccessAccountId || !applicationForm.dnsZoneId || !applicationForm.xhttpOriginDomain.trim()))
        return toast.error("请选择 AWS 账号、Cloudflare Zone 并填写源站域名");
      if (!applicationForm.autoProvisionCloudFront && !applicationForm.xhttpUploadDomain.trim())
        return toast.error("请填写 CloudFront 上行域名");
    } else if (
      applicationForm.username.length < 3 ||
      applicationForm.password.length < 8
    ) {
      return toast.error("请填写用户名和至少 8 位密码");
    }
    const invalidHop = applicationForm.hops.some(
      (hop) => hop.addressMode !== "public" && !hop.resourceGroupId,
    );
    if (invalidHop) return toast.error("内网跳点必须选择对应的组网");
    setBusy("application-save");
    try {
      const result = await createNetworkRouteApplication({
        name: applicationForm.name.trim(),
        nodePath,
        tunnelProtocol: applicationForm.tunnelProtocol,
        proxyType: applicationForm.proxyType,
        bindIp: applicationForm.bindIp.trim(),
        listenPort: Number(applicationForm.listenPort),
        username: applicationForm.username.trim(),
        password: applicationForm.password,
        realityServerName:
          applicationForm.proxyType === "vless_reality"
            ? applicationForm.realityServerName.trim()
            : undefined,
        xhttpPath:
          applicationForm.proxyType === "vless_xhttp_tls"
            ? applicationForm.xhttpPath.trim()
            : undefined,
        xhttpMode:
          applicationForm.proxyType === "vless_xhttp_tls"
            ? applicationForm.xhttpMode
            : undefined,
        xhttpPaddingBytes:
          applicationForm.proxyType === "vless_xhttp_tls"
            ? applicationForm.xhttpPaddingBytes.trim()
            : undefined,
        xhttpOriginDomain:
          applicationForm.proxyType === "vless_xhttp_tls"
            ? applicationForm.xhttpOriginDomain.trim()
            : undefined,
        xhttpUploadDomain:
          applicationForm.proxyType === "vless_xhttp_tls"
            ? applicationForm.xhttpUploadDomain.trim()
            : undefined,
        xhttpDownloadDomain:
          applicationForm.proxyType === "vless_xhttp_tls"
            ? applicationForm.xhttpDownloadDomain.trim()
            : undefined,
        autoProvisionCloudFront:
          applicationForm.proxyType === "vless_xhttp_tls"
            ? applicationForm.autoProvisionCloudFront
            : undefined,
        awsAccessAccountId:
          applicationForm.proxyType === "vless_xhttp_tls" && applicationForm.autoProvisionCloudFront
            ? Number(applicationForm.awsAccessAccountId)
            : undefined,
        dnsZoneId:
          applicationForm.proxyType === "vless_xhttp_tls" && applicationForm.autoProvisionCloudFront
            ? Number(applicationForm.dnsZoneId)
            : undefined,
        hopConfigs: applicationForm.hops.map((hop, index) => ({
          fromNodeId: nodePath[index],
          toNodeId: nodePath[index + 1],
          addressMode: hop.addressMode,
          resourceGroupId: hop.resourceGroupId
            ? Number(hop.resourceGroupId)
            : undefined,
          fallbackMode: hop.fallbackMode,
        })),
      });
      if (result.code !== 0)
        return toast.error(result.msg || "创建出口应用失败");
      setApplications(result.data.applications || []);
      setApplicationOpen(false);
      toast.success("出口应用已部署并测试通过");
    } finally {
      setBusy("");
    }
  };

  const applicationAction = async (
    app: NetworkRouteApplication,
    action: "test" | "pause" | "resume" | "deploy" | "delete",
  ) => {
    setBusy(`app-${action}-${app.id}`);
    try {
      const result =
        action === "test"
          ? await testNetworkRouteApplication(app.id)
          : action === "pause"
            ? await pauseNetworkRouteApplication(app.id)
            : action === "resume"
              ? await resumeNetworkRouteApplication(app.id)
              : action === "deploy"
                ? await deployNetworkRouteApplication(app.id)
                : await deleteNetworkRouteApplication(app.id);
      if (result.code !== 0) return toast.error(result.msg || "操作失败");
      toast.success(
        action === "test"
          ? `出口测试通过，延迟 ${Number(result.data?.latencyMs || 0).toFixed(1)} ms`
          : "操作成功",
      );
      await load();
    } finally {
      setBusy("");
    }
  };

  const matchingGroups = (
    fromId: string,
    toId: string,
    mode: RouteHopForm["addressMode"],
  ) => {
    if (mode === "private")
      return native.groups.filter(
        (group) =>
          group.members.some((item) => String(item.nodeId) === fromId) &&
          group.members.some((item) => String(item.nodeId) === toId),
      );
    if (mode === "virtual")
      return automatic.networks.filter(
        (network) =>
          network.members.some(
            (item) =>
              item.targetType === "node" && String(item.targetId) === fromId,
          ) &&
          network.members.some(
            (item) =>
              item.targetType === "node" && String(item.targetId) === toId,
          ),
      );
    return [];
  };

  if (loading)
    return (
      <div className="flex min-h-[55vh] items-center justify-center">
        <Spinner label="正在加载内网组建" />
      </div>
    );

  return (
    <div className="mx-auto w-full max-w-7xl space-y-6 px-3 py-4 sm:px-6">
      <header className="flex flex-wrap items-start justify-between gap-4 border-b border-divider pb-5">
        <div>
          <h1 className="text-2xl font-semibold">内网组建</h1>
          <p className="mt-1 text-sm text-default-500">
            自动建立服务器内网，并直接应用到 B→C 或 B→C→D 的代理出口。
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            startContent={<Network size={17} />}
            variant="flat"
            onPress={() => {
              setAutomaticForm(automaticEmpty);
              setAutomaticOpen(true);
            }}
          >
            自动组网
          </Button>
          <Button
            startContent={<ShieldCheck size={17} />}
            variant="flat"
            onPress={() => {
              setNativeForm(nativeEmpty);
              setNativeOpen(true);
            }}
          >
            登记原生内网
          </Button>
          <Button
            color="primary"
            startContent={<Route size={17} />}
            onPress={() => {
              setApplicationForm(applicationEmpty);
              setApplicationOpen(true);
            }}
          >
            创建出口应用
          </Button>
        </div>
      </header>

      <section className="grid gap-3 sm:grid-cols-3">
        <div className="border-y border-divider px-3 py-4">
          <p className="text-xs text-default-500">自动组网</p>
          <p className="mt-1 text-2xl font-semibold">
            {automatic.networks.length}
          </p>
        </div>
        <div className="border-y border-divider px-3 py-4">
          <p className="text-xs text-default-500">原生内网组</p>
          <p className="mt-1 text-2xl font-semibold">{native.groups.length}</p>
        </div>
        <div className="border-y border-divider px-3 py-4">
          <p className="text-xs text-default-500">运行中的出口</p>
          <p className="mt-1 text-2xl font-semibold">
            {applications.filter((item) => item.state === "active").length}
          </p>
        </div>
      </section>

      <section className="space-y-3">
        <div className="flex items-center gap-2">
          <Network size={18} />
          <h2 className="font-semibold">Agent 自动组网</h2>
        </div>
        {automatic.networks.length === 0 ? (
          <p className="border-y border-divider py-8 text-center text-sm text-default-500">
            还没有自动组网
          </p>
        ) : (
          automatic.networks.map((network) => {
            const meta = stateMeta(network.state);
            return (
              <article
                key={network.id}
                className="border border-divider bg-content1"
              >
                <div className="flex flex-wrap items-start justify-between gap-3 border-b border-divider p-4">
                  <div>
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-medium">{network.name}</h3>
                      <Chip size="sm" color={meta.color} variant="flat">
                        {meta.label}
                      </Chip>
                      <Chip size="sm" variant="flat">
                        WireGuard
                      </Chip>
                    </div>
                    <p className="mt-1 text-xs text-default-500">
                      {network.cidr} ·{" "}
                      {network.members
                        .map((item) => `${item.memberName} ${item.virtualIp}`)
                        .join(" → ")}
                    </p>
                    {network.lastError && (
                      <p className="mt-2 text-xs text-danger">
                        {network.lastError}
                      </p>
                    )}
                  </div>
                  <div className="flex gap-1">
                    <Button
                      isIconOnly
                      size="sm"
                      variant="light"
                      title="刷新状态"
                      isLoading={busy === `auto-refresh-${network.id}`}
                      onPress={() => void automaticAction(network, "refresh")}
                    >
                      <RefreshCw size={16} />
                    </Button>
                    <Button
                      isIconOnly
                      size="sm"
                      variant="light"
                      color="danger"
                      title="删除组网"
                      isLoading={busy === `auto-delete-${network.id}`}
                      onPress={() => void automaticAction(network, "delete")}
                    >
                      <Trash2 size={16} />
                    </Button>
                  </div>
                </div>
                <div className="grid gap-px bg-divider sm:grid-cols-2 lg:grid-cols-3">
                  {network.members.map((member) => (
                    <div key={member.id} className="bg-content1 p-3 text-sm">
                      <div className="flex justify-between gap-2">
                        <span className="font-medium">{member.memberName}</span>
                        <Chip
                          size="sm"
                          color={stateMeta(member.state).color}
                          variant="dot"
                        >
                          {stateMeta(member.state).label}
                        </Chip>
                      </div>
                      <p className="mt-1 font-mono text-xs">
                        {member.virtualIp}
                      </p>
                      <p className="mt-1 text-xs text-default-500">
                        接收 {bytes(member.receiveBytes)} · 发送{" "}
                        {bytes(member.transmitBytes)}
                      </p>
                    </div>
                  ))}
                </div>
              </article>
            );
          })
        )}
      </section>

      <section className="space-y-3">
        <div className="flex items-center gap-2">
          <ShieldCheck size={18} />
          <h2 className="font-semibold">原生 VPC、云骨干与专线</h2>
        </div>
        {native.groups.length === 0 ? (
          <p className="border-y border-divider py-8 text-center text-sm text-default-500">
            还没有登记原生内网
          </p>
        ) : (
          native.groups.map((group) => {
            const meta = stateMeta(group.state);
            return (
              <article
                key={group.id}
                className="border border-divider bg-content1"
              >
                <div className="flex flex-wrap items-start justify-between gap-3 border-b border-divider p-4">
                  <div>
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-medium">{group.name}</h3>
                      <Chip size="sm" color={meta.color} variant="flat">
                        {meta.label}
                      </Chip>
                      <Chip size="sm" variant="flat">
                        {networkTypeName(group.networkType)}
                      </Chip>
                    </div>
                    <p className="mt-1 text-xs text-default-500">
                      {group.cidr || "未填写网段"} ·{" "}
                      {group.members
                        .map(
                          (item) => `${item.nodeName} ${item.privateAddress}`,
                        )
                        .join(" → ")}
                    </p>
                    {group.lastError && (
                      <p className="mt-2 text-xs text-danger">
                        {group.lastError}
                      </p>
                    )}
                  </div>
                  <div className="flex gap-1">
                    <Button
                      isIconOnly
                      size="sm"
                      variant="light"
                      title="编辑"
                      onPress={() => editNative(group)}
                    >
                      <Pencil size={16} />
                    </Button>
                    <Button
                      isIconOnly
                      size="sm"
                      variant="light"
                      title="双向验证"
                      isLoading={busy === `verify-${group.id}`}
                      onPress={() => void nativeAction(group, "verify")}
                    >
                      <Gauge size={16} />
                    </Button>
                    <Button
                      isIconOnly
                      size="sm"
                      variant="light"
                      color="danger"
                      title="删除"
                      isLoading={busy === `delete-${group.id}`}
                      onPress={() => void nativeAction(group, "delete")}
                    >
                      <Trash2 size={16} />
                    </Button>
                  </div>
                </div>
                {group.links.length > 0 && (
                  <div className="overflow-x-auto">
                    <table className="w-full min-w-[760px] text-left text-sm">
                      <thead className="border-b border-divider text-xs text-default-500">
                        <tr>
                          <th className="p-3">实际路径</th>
                          <th className="p-3">来源地址</th>
                          <th className="p-3">网卡</th>
                          <th className="p-3">延迟</th>
                          <th className="p-3">丢包</th>
                          <th className="p-3">验证时间</th>
                        </tr>
                      </thead>
                      <tbody>
                        {group.links.map((link) => (
                          <tr
                            key={link.id}
                            className="border-b border-divider/60 last:border-0"
                          >
                            <td className="p-3">
                              <span>
                                {link.sourceNodeName} → {link.targetNodeName}
                              </span>
                              <Chip
                                className="ml-2"
                                size="sm"
                                color={stateMeta(link.state).color}
                                variant="dot"
                              >
                                {stateMeta(link.state).label}
                              </Chip>
                            </td>
                            <td className="p-3 font-mono text-xs">
                              {link.sourceAddress || "-"} → {link.targetAddress}
                            </td>
                            <td className="p-3">{link.interfaceName || "-"}</td>
                            <td className="p-3">
                              {link.latencyMs == null
                                ? "-"
                                : `${Number(link.latencyMs).toFixed(2)} ms`}
                            </td>
                            <td className="p-3">
                              {link.packetLoss == null
                                ? "-"
                                : `${Number(link.packetLoss).toFixed(1)}%`}
                            </td>
                            <td className="p-3 whitespace-nowrap">
                              {timeText(link.verifiedAt)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </article>
            );
          })
        )}
      </section>

      <section className="space-y-3">
        <div className="flex items-center gap-2">
          <Route size={18} />
          <h2 className="font-semibold">代理出口应用</h2>
        </div>
        {applications.length === 0 ? (
          <p className="border-y border-divider py-8 text-center text-sm text-default-500">
            创建后，本地只连接 B，最终由 C 或 D 访问网站
          </p>
        ) : (
          applications.map((app) => {
            const meta = stateMeta(app.state);
            return (
              <article
                key={app.id}
                className="border border-divider bg-content1 p-4"
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-medium">{app.name}</h3>
                      <Chip size="sm" color={meta.color} variant="flat">
                        {meta.label}
                      </Chip>
                      <Chip size="sm" variant="flat">
                        {app.proxyType === "vless_reality"
                          ? "VLESS + REALITY"
                          : app.proxyType === "vless_xhttp_tls"
                            ? "VLESS + XHTTP + TLS"
                            : app.proxyType.toUpperCase()}
                      </Chip>
                    </div>
                    <p className="mt-1 text-sm">
                      {(app.nodePath || [])
                        .map((item) => item.nodeName)
                        .join(" → ") ||
                        `${app.entryNodeName} → ${app.exitNodeName}`}{" "}
                      · {app.exitNodeName} 最终出口
                    </p>
                    <div className="mt-2 flex max-w-3xl items-center gap-2">
                      <code className="min-w-0 flex-1 truncate border-y border-divider py-2 text-xs">
                        {app.clientUri}
                      </code>
                      <Button
                        isIconOnly
                        size="sm"
                        variant="light"
                        title="复制连接"
                        onPress={() => {
                          void navigator.clipboard.writeText(app.clientUri);
                          toast.success("连接已复制");
                        }}
                      >
                        <Copy size={16} />
                      </Button>
                    </div>
                    {app.hopDetails?.length > 0 && (
                      <div className="mt-3 grid gap-px border border-divider bg-divider sm:grid-cols-2">
                        {app.hopDetails.map((hop, index) => (
                          <div
                            key={`${hop.fromNodeId}-${hop.toNodeId}`}
                            className="bg-content1 p-3 text-xs"
                          >
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="font-medium">
                                第 {index + 1} 跳 · {hop.fromNodeName} →{" "}
                                {hop.toNodeName}
                              </span>
                              <Chip
                                size="sm"
                                variant="flat"
                                color={
                                  hop.verificationState === "invalid"
                                    ? "danger"
                                    : hop.addressMode === "public"
                                      ? "default"
                                      : "success"
                                }
                              >
                                {hop.addressModeName}
                              </Chip>
                            </div>
                            <p className="mt-1 font-mono">
                              主地址 {hop.targetAddress || "-"}
                            </p>
                            {hop.resourceGroupName && (
                              <p className="mt-1 text-default-500">
                                组网 {hop.resourceGroupName}
                              </p>
                            )}
                            {hop.fallbackAddress && (
                              <p className="mt-1 font-mono text-warning">
                                备用公网 {hop.fallbackAddress}
                              </p>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                    {app.lastError && (
                      <p className="mt-2 text-xs text-danger">
                        {app.lastError}
                      </p>
                    )}
                    <p className="mt-2 text-xs text-default-500">
                      最近测试 {timeText(app.lastTestAt)}
                      {app.lastTestLatencyMs != null
                        ? ` · ${Number(app.lastTestLatencyMs).toFixed(1)} ms`
                        : ""}
                    </p>
                  </div>
                  <div className="flex gap-1">
                    <Button
                      isIconOnly
                      size="sm"
                      variant="light"
                      title="真实出口测试"
                      isLoading={busy === `app-test-${app.id}`}
                      onPress={() => void applicationAction(app, "test")}
                    >
                      <Gauge size={16} />
                    </Button>
                    {app.state === "paused" ? (
                      <Button
                        isIconOnly
                        size="sm"
                        variant="light"
                        title="恢复"
                        onPress={() => void applicationAction(app, "resume")}
                      >
                        <Play size={16} />
                      </Button>
                    ) : (
                      <Button
                        isIconOnly
                        size="sm"
                        variant="light"
                        title="暂停"
                        onPress={() => void applicationAction(app, "pause")}
                      >
                        <Pause size={16} />
                      </Button>
                    )}
                    <Button
                      isIconOnly
                      size="sm"
                      variant="light"
                      title="重新部署"
                      onPress={() => void applicationAction(app, "deploy")}
                    >
                      <RotateCw size={16} />
                    </Button>
                    <Button
                      isIconOnly
                      size="sm"
                      variant="light"
                      color="danger"
                      title="删除"
                      onPress={() => void applicationAction(app, "delete")}
                    >
                      <Trash2 size={16} />
                    </Button>
                  </div>
                </div>
              </article>
            );
          })
        )}
      </section>

      <Modal isOpen={automaticOpen} onOpenChange={setAutomaticOpen} size="2xl">
        <ModalContent>
          <ModalHeader>Agent 自动组网</ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Input
                label="组网名称"
                value={automaticForm.name}
                onValueChange={(name) =>
                  setAutomaticForm({ ...automaticForm, name })
                }
              />
              <Input
                label="虚拟网段"
                value={automaticForm.cidr}
                onValueChange={(cidr) =>
                  setAutomaticForm({ ...automaticForm, cidr })
                }
              />
            </div>
            <Select
              selectionMode="multiple"
              label="组网服务器"
              selectedKeys={automaticForm.members}
              onSelectionChange={(keys) => {
                const members = new Set(Array.from(keys).map(String));
                const hubNodeId = members.has(automaticForm.hubNodeId)
                  ? automaticForm.hubNodeId
                  : Array.from(members)[0] || "";
                setAutomaticForm({ ...automaticForm, members, hubNodeId });
              }}
            >
              {onlineNodes.map((node) => (
                <SelectItem key={String(node.id)}>{node.name}</SelectItem>
              ))}
            </Select>
            <div className="grid gap-4 sm:grid-cols-2">
              <Select
                label="公网握手节点"
                selectedKeys={
                  automaticForm.hubNodeId ? [automaticForm.hubNodeId] : []
                }
                onSelectionChange={(keys) =>
                  setAutomaticForm({
                    ...automaticForm,
                    hubNodeId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {onlineNodes
                  .filter((node) => automaticForm.members.has(String(node.id)))
                  .map((node) => (
                    <SelectItem key={String(node.id)}>{node.name}</SelectItem>
                  ))}
              </Select>
              <Input
                label="WireGuard UDP 端口"
                type="number"
                value={automaticForm.listenPort}
                onValueChange={(listenPort) =>
                  setAutomaticForm({ ...automaticForm, listenPort })
                }
              />
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setAutomaticOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "automatic-save"}
              onPress={() => void createAutomatic()}
            >
              组网并验证
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={nativeOpen}
        onOpenChange={setNativeOpen}
        size="3xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          <ModalHeader>
            {nativeForm.id ? "编辑原生内网" : "登记原生内网"}
          </ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-3">
              <Input
                label="内网组名称"
                value={nativeForm.name}
                onValueChange={(name) => setNativeForm({ ...nativeForm, name })}
              />
              <Select
                label="内网类型"
                selectedKeys={[nativeForm.networkType]}
                onSelectionChange={(keys) =>
                  setNativeForm({
                    ...nativeForm,
                    networkType: String(Array.from(keys)[0]),
                  })
                }
              >
                <SelectItem key="vpc">云 VPC</SelectItem>
                <SelectItem key="cloud_backbone">云骨干</SelectItem>
                <SelectItem key="dedicated">专线内网</SelectItem>
              </Select>
              <Input
                label="内网网段"
                placeholder="10.20.0.0/24"
                value={nativeForm.cidr}
                onValueChange={(cidr) => setNativeForm({ ...nativeForm, cidr })}
              />
            </div>
            {nativeForm.members.map((member, index) => (
              <div
                key={index}
                className="grid gap-3 border-y border-divider py-3 sm:grid-cols-[1fr_1fr_1fr_110px_40px]"
              >
                <Select
                  label={`服务器 ${index + 1}`}
                  selectedKeys={member.nodeId ? [member.nodeId] : []}
                  onSelectionChange={(keys) => {
                    const members = [...nativeForm.members];
                    members[index] = {
                      ...member,
                      nodeId: String(Array.from(keys)[0] || ""),
                    };
                    setNativeForm({ ...nativeForm, members });
                  }}
                >
                  {onlineNodes.map((node) => (
                    <SelectItem key={String(node.id)}>{node.name}</SelectItem>
                  ))}
                </Select>
                <Input
                  label="内网 IP"
                  placeholder="10.20.0.5"
                  value={member.privateAddress}
                  onValueChange={(privateAddress) => {
                    const members = [...nativeForm.members];
                    members[index] = { ...member, privateAddress };
                    setNativeForm({ ...nativeForm, members });
                  }}
                />
                <Input
                  label="网卡（可留空）"
                  placeholder="eth1"
                  value={member.interfaceName}
                  onValueChange={(interfaceName) => {
                    const members = [...nativeForm.members];
                    members[index] = { ...member, interfaceName };
                    setNativeForm({ ...nativeForm, members });
                  }}
                />
                <Input
                  label="MTU"
                  type="number"
                  value={member.mtu}
                  onValueChange={(mtu) => {
                    const members = [...nativeForm.members];
                    members[index] = { ...member, mtu };
                    setNativeForm({ ...nativeForm, members });
                  }}
                />
                <Button
                  isIconOnly
                  variant="light"
                  color="danger"
                  className="self-end"
                  isDisabled={nativeForm.members.length <= 2}
                  onPress={() =>
                    setNativeForm({
                      ...nativeForm,
                      members: nativeForm.members.filter(
                        (_, itemIndex) => itemIndex !== index,
                      ),
                    })
                  }
                >
                  <Trash2 size={16} />
                </Button>
              </div>
            ))}
            <Button
              variant="flat"
              startContent={<Plus size={16} />}
              onPress={() =>
                setNativeForm({
                  ...nativeForm,
                  members: [
                    ...nativeForm.members,
                    {
                      nodeId: "",
                      privateAddress: "",
                      interfaceName: "",
                      mtu: "1500",
                    },
                  ],
                })
              }
            >
              添加服务器
            </Button>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setNativeOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "native-save"}
              onPress={() => void saveNative()}
            >
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={applicationOpen}
        onOpenChange={setApplicationOpen}
        size="4xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          <ModalHeader>创建代理出口应用</ModalHeader>
          <ModalBody className="space-y-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <Input
                label="应用名称"
                placeholder="香港入口-日本出口"
                value={applicationForm.name}
                onValueChange={(name) =>
                  setApplicationForm({ ...applicationForm, name })
                }
              />
              <Select
                label="入口代理协议"
                selectedKeys={[applicationForm.proxyType]}
                onSelectionChange={(keys) =>
                  setApplicationForm({
                    ...applicationForm,
                    proxyType: String(Array.from(keys)[0]) as
                      | "socks5"
                      | "http"
                      | "vless_reality"
                      | "vless_xhttp_tls",
                  })
                }
              >
                <SelectItem key="socks5">SOCKS5</SelectItem>
                <SelectItem key="http">HTTP</SelectItem>
                <SelectItem key="vless_reality">VLESS + REALITY</SelectItem>
                <SelectItem key="vless_xhttp_tls">
                  VLESS + XHTTP + TLS / CloudFront
                </SelectItem>
              </Select>
            </div>
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium">服务器线路</p>
                <Button
                  size="sm"
                  variant="flat"
                  startContent={<Plus size={15} />}
                  onPress={() =>
                    syncApplicationPath([...applicationForm.nodePath, ""])
                  }
                >
                  添加中转/出口
                </Button>
              </div>
              {applicationForm.nodePath.map((nodeId, index) => (
                <div key={index}>
                  <div className="grid items-end gap-3 sm:grid-cols-[1fr_42px]">
                    <Select
                      label={
                        index === 0
                          ? "B 入口服务器"
                          : index === applicationForm.nodePath.length - 1
                            ? `${String.fromCharCode(66 + index)} 出口服务器`
                            : `${String.fromCharCode(66 + index)} 中转服务器`
                      }
                      selectedKeys={nodeId ? [nodeId] : []}
                      onSelectionChange={(keys) => {
                        const path = [...applicationForm.nodePath];
                        path[index] = String(Array.from(keys)[0] || "");
                        syncApplicationPath(path);
                      }}
                    >
                      {onlineNodes.map((node) => (
                        <SelectItem key={String(node.id)}>
                          {node.name}
                        </SelectItem>
                      ))}
                    </Select>
                    <Button
                      isIconOnly
                      variant="light"
                      color="danger"
                      isDisabled={
                        applicationForm.nodePath.length <= 2 || index === 0
                      }
                      onPress={() =>
                        syncApplicationPath(
                          applicationForm.nodePath.filter(
                            (_, itemIndex) => itemIndex !== index,
                          ),
                        )
                      }
                    >
                      <Trash2 size={16} />
                    </Button>
                  </div>
                  {index < applicationForm.hops.length && (
                    <div className="ml-5 mt-2 grid gap-3 border-l-2 border-divider pl-4 sm:grid-cols-3">
                      <Select
                        label={`${String.fromCharCode(66 + index)}→${String.fromCharCode(67 + index)} 传输地址`}
                        selectedKeys={[applicationForm.hops[index].addressMode]}
                        onSelectionChange={(keys) => {
                          const hops = [...applicationForm.hops];
                          hops[index] = {
                            ...hops[index],
                            addressMode: String(
                              Array.from(keys)[0],
                            ) as RouteHopForm["addressMode"],
                            resourceGroupId: "",
                          };
                          setApplicationForm({ ...applicationForm, hops });
                        }}
                      >
                        <SelectItem key="public">公网地址</SelectItem>
                        <SelectItem key="virtual">Agent 自动组网</SelectItem>
                        <SelectItem key="private">原生内网</SelectItem>
                      </Select>
                      {applicationForm.hops[index].addressMode !== "public" ? (
                        <Select
                          label="选择组网"
                          selectedKeys={
                            applicationForm.hops[index].resourceGroupId
                              ? [applicationForm.hops[index].resourceGroupId]
                              : []
                          }
                          onSelectionChange={(keys) => {
                            const hops = [...applicationForm.hops];
                            hops[index] = {
                              ...hops[index],
                              resourceGroupId: String(
                                Array.from(keys)[0] || "",
                              ),
                            };
                            setApplicationForm({ ...applicationForm, hops });
                          }}
                        >
                          {matchingGroups(
                            applicationForm.nodePath[index],
                            applicationForm.nodePath[index + 1],
                            applicationForm.hops[index].addressMode,
                          ).map((group) => (
                            <SelectItem key={String(group.id)}>
                              {group.name}
                            </SelectItem>
                          ))}
                        </Select>
                      ) : (
                        <div className="flex items-end pb-3 text-xs text-default-500">
                          <Unplug className="mr-2" size={15} />
                          直接使用下一跳公网 IP
                        </div>
                      )}
                      <Select
                        label="内网失败策略"
                        isDisabled={
                          applicationForm.hops[index].addressMode === "public"
                        }
                        selectedKeys={[
                          applicationForm.hops[index].fallbackMode,
                        ]}
                        onSelectionChange={(keys) => {
                          const hops = [...applicationForm.hops];
                          hops[index] = {
                            ...hops[index],
                            fallbackMode: String(
                              Array.from(keys)[0],
                            ) as RouteHopForm["fallbackMode"],
                          };
                          setApplicationForm({ ...applicationForm, hops });
                        }}
                      >
                        <SelectItem key="fail_closed">
                          严格内网，失败停止
                        </SelectItem>
                        <SelectItem key="public">自动回退公网</SelectItem>
                      </Select>
                    </div>
                  )}
                </div>
              ))}
            </div>
            <div className="grid gap-4 sm:grid-cols-3">
              <Select
                label="服务器间传输协议"
                selectedKeys={[applicationForm.tunnelProtocol]}
                onSelectionChange={(keys) =>
                  setApplicationForm({
                    ...applicationForm,
                    tunnelProtocol: String(Array.from(keys)[0]) as
                      "tls" | "quic",
                  })
                }
              >
                <SelectItem key="tls">TLS / TCP</SelectItem>
                <SelectItem key="quic">QUIC / UDP</SelectItem>
              </Select>
              <Input
                label="B 入口监听 IP"
                placeholder="留空监听全部地址"
                value={applicationForm.bindIp}
                onValueChange={(bindIp) =>
                  setApplicationForm({ ...applicationForm, bindIp })
                }
              />
              <Input
                label="B 入口端口"
                type="number"
                value={applicationForm.listenPort}
                onValueChange={(listenPort) =>
                  setApplicationForm({ ...applicationForm, listenPort })
                }
              />
            </div>
            {applicationForm.proxyType === "vless_reality" ? (
              <Input
                label="REALITY 伪装域名"
                placeholder="www.cloudflare.com"
                value={applicationForm.realityServerName}
                onValueChange={(realityServerName) =>
                  setApplicationForm({
                    ...applicationForm,
                    realityServerName,
                  })
                }
              />
            ) : applicationForm.proxyType === "vless_xhttp_tls" ? (
              <div className="space-y-4">
                <Switch
                  isSelected={applicationForm.autoProvisionCloudFront}
                  onValueChange={(autoProvisionCloudFront) =>
                    setApplicationForm({
                      ...applicationForm,
                      autoProvisionCloudFront,
                    })
                  }
                >
                  自动创建 Cloudflare DNS 和两条 CloudFront 分配
                </Switch>
                <div className="grid gap-4 sm:grid-cols-3">
                  <Input
                    label="XHTTP 路径"
                    placeholder="/aws/"
                    value={applicationForm.xhttpPath}
                    onValueChange={(xhttpPath) =>
                      setApplicationForm({ ...applicationForm, xhttpPath })
                    }
                  />
                  <Select
                    label="XHTTP 模式"
                    selectedKeys={[applicationForm.xhttpMode]}
                    onSelectionChange={(keys) =>
                      setApplicationForm({
                        ...applicationForm,
                        xhttpMode: String(Array.from(keys)[0]) as
                          | "auto"
                          | "packet-up"
                          | "stream-up",
                      })
                    }
                  >
                    <SelectItem key="auto">自动</SelectItem>
                    <SelectItem key="packet-up">Packet Up</SelectItem>
                    <SelectItem key="stream-up">Stream Up</SelectItem>
                  </Select>
                  <Input
                    label="填充字节"
                    placeholder="100-1000"
                    value={applicationForm.xhttpPaddingBytes}
                    onValueChange={(xhttpPaddingBytes) =>
                      setApplicationForm({
                        ...applicationForm,
                        xhttpPaddingBytes,
                      })
                    }
                  />
                </div>
                {applicationForm.autoProvisionCloudFront ? (
                  <div className="grid gap-4 sm:grid-cols-3">
                    <Select
                      isRequired
                      label="AWS 账号"
                      selectedKeys={applicationForm.awsAccessAccountId ? [applicationForm.awsAccessAccountId] : []}
                      onSelectionChange={(keys) =>
                        setApplicationForm({ ...applicationForm, awsAccessAccountId: String(Array.from(keys)[0] || "") })
                      }
                    >
                      {awsAccounts
                        .filter((item) => Boolean(item.enabled))
                        .map((item) => (
                          <SelectItem key={String(item.id)}>{item.name}</SelectItem>
                        ))}
                    </Select>
                    <Select
                      isRequired
                      label="Cloudflare Zone"
                      selectedKeys={applicationForm.dnsZoneId ? [applicationForm.dnsZoneId] : []}
                      onSelectionChange={(keys) =>
                        setApplicationForm({ ...applicationForm, dnsZoneId: String(Array.from(keys)[0] || "") })
                      }
                    >
                      {dnsZones.map((item) => (
                        <SelectItem key={String(item.id)}>{item.zoneName}</SelectItem>
                      ))}
                    </Select>
                    <Input
                      isRequired
                      label="源站域名"
                      placeholder="origin.example.com"
                      value={applicationForm.xhttpOriginDomain}
                      onValueChange={(xhttpOriginDomain) =>
                        setApplicationForm({ ...applicationForm, xhttpOriginDomain })
                      }
                    />
                  </div>
                ) : (
                <div className="grid gap-4 sm:grid-cols-3">
                  <Input
                    label="源站域名"
                    placeholder="origin.example.com"
                    value={applicationForm.xhttpOriginDomain}
                    onValueChange={(xhttpOriginDomain) =>
                      setApplicationForm({
                        ...applicationForm,
                        xhttpOriginDomain,
                      })
                    }
                  />
                  <Input
                    isRequired
                    label="CloudFront 上行域名"
                    placeholder="dxxx.cloudfront.net"
                    value={applicationForm.xhttpUploadDomain}
                    onValueChange={(xhttpUploadDomain) =>
                      setApplicationForm({
                        ...applicationForm,
                        xhttpUploadDomain,
                      })
                    }
                  />
                  <Input
                    label="CloudFront 下行域名"
                    placeholder="dyyy.cloudfront.net"
                    value={applicationForm.xhttpDownloadDomain}
                    onValueChange={(xhttpDownloadDomain) =>
                      setApplicationForm({
                        ...applicationForm,
                        xhttpDownloadDomain,
                      })
                    }
                  />
                </div>
                )}
              </div>
            ) : (
              <div className="grid gap-4 sm:grid-cols-2">
                <Input
                  label="代理用户名"
                  value={applicationForm.username}
                  onValueChange={(username) =>
                    setApplicationForm({ ...applicationForm, username })
                  }
                />
                <Input
                  label="代理密码"
                  type="password"
                  value={applicationForm.password}
                  onValueChange={(password) =>
                    setApplicationForm({ ...applicationForm, password })
                  }
                />
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setApplicationOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "application-save"}
              startContent={<Waypoints size={16} />}
              onPress={() => void createApplication()}
            >
              创建、部署并测试
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
