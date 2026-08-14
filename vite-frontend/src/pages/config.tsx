import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Divider } from "@heroui/divider";
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
  AlertTriangle,
  CheckCircle2,
  ExternalLink,
  Globe2,
  LockKeyhole,
  Plus,
  Radio,
  RefreshCw,
  Save,
  Server,
  Settings,
  ShieldCheck,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  createDomainRoute,
  getDnsZoneOptions,
  getDomainRoutes,
  getNodeList,
  updateConfig,
  updateConfigs,
  type DnsZoneOption,
  type DomainRoute,
} from "@/api";
import {
  clearConfigCache,
  getCachedConfigs,
  updateSiteConfig,
} from "@/config/site";
import { isAdmin } from "@/utils/auth";

interface NodeOption {
  id: number;
  name: string;
  ip?: string;
  serverIp?: string;
  status: number;
  version?: string;
}

interface AgentEndpoint {
  host: string;
  port: number;
}

const PANEL_ROUTE_PREFIX = "面板访问 · ";
const emptyAccessForm = { dnsZoneId: "", domain: "", entryNodeId: "" };

const parseAgentEndpoint = (value?: string): AgentEndpoint | null => {
  const input = value?.trim();

  if (!input) return null;
  const bracketed = input.match(/^\[([^\]]+)]:(\d{1,5})$/);

  if (bracketed) {
    const port = Number(bracketed[2]);

    return port >= 1 && port <= 65535 ? { host: bracketed[1], port } : null;
  }
  const separator = input.lastIndexOf(":");

  if (separator < 1) return null;
  const host = input.slice(0, separator).trim();
  const port = Number(input.slice(separator + 1));

  return host && Number.isInteger(port) && port >= 1 && port <= 65535
    ? { host, port }
    : null;
};

const routeStatus = (route: DomainRoute) => {
  if (route.state === "active" && route.certificateState === "active") {
    return {
      label: "HTTPS 正常",
      color: "success" as const,
      icon: CheckCircle2,
    };
  }
  if (
    ["certificate_failed", "deployment_failed"].includes(route.state) ||
    ["failed", "deployment_failed", "renewal_failed"].includes(
      route.certificateState || "",
    )
  ) {
    return {
      label:
        route.certificateState === "renewal_failed" ? "续签异常" : "配置失败",
      color: "danger" as const,
      icon: AlertTriangle,
    };
  }
  if (route.state === "delete_pending") {
    return {
      label: "等待删除",
      color: "warning" as const,
      icon: AlertTriangle,
    };
  }

  return {
    label:
      route.certificateState === "dns_propagating" ? "DNS 同步中" : "配置中",
    color: "primary" as const,
    icon: RefreshCw,
  };
};

const formatTime = (value?: number) =>
  value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "尚未签发";

export default function ConfigPage() {
  const navigate = useNavigate();
  const [configs, setConfigs] = useState<Record<string, string>>({});
  const [originalConfigs, setOriginalConfigs] = useState<
    Record<string, string>
  >({});
  const [zones, setZones] = useState<DnsZoneOption[]>([]);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [routes, setRoutes] = useState<DomainRoute[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [accessModal, setAccessModal] = useState(false);
  const [accessForm, setAccessForm] = useState(emptyAccessForm);
  const [creatingAccess, setCreatingAccess] = useState(false);

  useEffect(() => {
    if (!isAdmin()) {
      toast.error("权限不足，只有管理员可以访问此页面");
      navigate("/dashboard", { replace: true });
    }
  }, [navigate]);

  const loadData = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    else setRefreshing(true);
    try {
      const [configData, zoneResponse, nodeResponse, routeResponse] =
        await Promise.all([
          getCachedConfigs(),
          getDnsZoneOptions(),
          getNodeList(),
          getDomainRoutes(),
        ]);

      setConfigs(configData);
      setOriginalConfigs({ ...configData });
      if (zoneResponse.code === 0) setZones(zoneResponse.data || []);
      if (nodeResponse.code === 0) setNodes(nodeResponse.data || []);
      if (routeResponse.code === 0) setRoutes(routeResponse.data || []);
      if (
        zoneResponse.code !== 0 ||
        nodeResponse.code !== 0 ||
        routeResponse.code !== 0
      ) {
        toast.error("部分入口资源加载失败，请刷新重试");
      }
    } catch {
      toast.error("加载网站设置失败");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const endpoint = useMemo(() => parseAgentEndpoint(configs.ip), [configs.ip]);
  const nodeById = useMemo(
    () => new Map(nodes.map((node) => [node.id, node])),
    [nodes],
  );
  const panelRoutes = useMemo(
    () =>
      routes.filter((route) => {
        if (route.name.startsWith(PANEL_ROUTE_PREFIX)) return true;
        if (
          !endpoint ||
          route.backendType !== "direct" ||
          route.backendPort !== endpoint.port
        )
          return false;
        const backendNode = route.backendNodeId
          ? nodeById.get(route.backendNodeId)
          : undefined;

        return [route.backendHost, backendNode?.serverIp, backendNode?.ip].some(
          (address) =>
            address?.replace(/^\[|]$/g, "") ===
            endpoint.host.replace(/^\[|]$/g, ""),
        );
      }),
    [endpoint, nodeById, routes],
  );
  const currentOrigin =
    typeof window === "undefined" ? "" : window.location.origin;
  const preferredUrl = configs.panel_public_url || currentOrigin;
  const hasChanges =
    configs.ip !== originalConfigs.ip ||
    configs.app_name !== originalConfigs.app_name ||
    configs.captcha_enabled !== originalConfigs.captcha_enabled ||
    configs.captcha_type !== originalConfigs.captcha_type;

  const updateValue = (key: string, value: string) =>
    setConfigs((current) => ({ ...current, [key]: value }));

  const saveSettings = async () => {
    if (!parseAgentEndpoint(configs.ip))
      return toast.error("Agent 通信地址必须使用 IP:端口 格式");
    if (!configs.app_name?.trim()) return toast.error("应用名称不能为空");
    setSaving(true);
    const response = await updateConfigs({
      ip: configs.ip.trim(),
      app_name: configs.app_name.trim(),
      captcha_enabled: configs.captcha_enabled || "false",
      captcha_type: configs.captcha_type || "RANDOM",
    });

    setSaving(false);
    if (response.code !== 0) return toast.error(response.msg || "保存配置失败");
    const appNameChanged = configs.app_name !== originalConfigs.app_name;

    clearConfigCache();
    setOriginalConfigs({
      ...configs,
      ip: configs.ip.trim(),
      app_name: configs.app_name.trim(),
    });
    if (appNameChanged) await updateSiteConfig();
    window.dispatchEvent(
      new CustomEvent("configUpdated", {
        detail: {
          changedKeys: ["ip", "app_name", "captcha_enabled", "captcha_type"],
        },
      }),
    );
    toast.success("网站设置已保存");
  };

  const openAccessModal = () => {
    if (!endpoint) return toast.error("请先填写并保存正确的 Agent 通信地址");
    const matchingNode = nodes.find((node) =>
      [node.serverIp, node.ip].some((address) => address === endpoint.host),
    );
    const entryNode = matchingNode || nodes.find((node) => node.status === 1);
    const zone = zones[0];

    setAccessForm({
      dnsZoneId: zone ? String(zone.id) : "",
      domain: zone ? `panel.${zone.zoneName}` : "",
      entryNodeId: entryNode ? String(entryNode.id) : "",
    });
    setAccessModal(true);
  };

  const selectZone = (zoneId: string) => {
    const previousZone = zones.find(
      (zone) => String(zone.id) === accessForm.dnsZoneId,
    );
    const zone = zones.find((item) => String(item.id) === zoneId);
    const autoDomain =
      !accessForm.domain ||
      (previousZone && accessForm.domain === `panel.${previousZone.zoneName}`);

    setAccessForm((current) => ({
      ...current,
      dnsZoneId: zoneId,
      domain: autoDomain && zone ? `panel.${zone.zoneName}` : current.domain,
    }));
  };

  const createPanelAccess = async () => {
    if (!endpoint) return toast.error("Agent 通信地址格式不正确");
    if (!accessForm.dnsZoneId || !accessForm.domain.trim())
      return toast.error("请选择 DNS 域名并填写访问域名");
    if (!accessForm.entryNodeId) return toast.error("请选择 HTTPS 入口节点");
    const entryNode = nodeById.get(Number(accessForm.entryNodeId));

    if (!entryNode || entryNode.status !== 1)
      return toast.error("HTTPS 入口节点必须在线");
    const entryAddress = (entryNode.serverIp || entryNode.ip || "").replace(
      /^\[|]$/g,
      "",
    );
    const backendHost =
      entryAddress === endpoint.host.replace(/^\[|]$/g, "")
        ? "0.0.0.0"
        : endpoint.host;

    setCreatingAccess(true);
    const response = await createDomainRoute({
      name: `${PANEL_ROUTE_PREFIX}${accessForm.domain.trim().toLowerCase()}`,
      domain: accessForm.domain.trim().toLowerCase(),
      backendType: "direct",
      backendNodeId: entryNode.id,
      backendHost,
      backendPort: endpoint.port,
      backendScheme: "http",
      backendPath: "/",
      entryNodeId: entryNode.id,
      listenPort: 443,
      ingressMode: "managed_https",
      dnsZoneId: Number(accessForm.dnsZoneId),
      pathPrefix: "/",
    });

    if (response.code !== 0) {
      setCreatingAccess(false);

      return toast.error(response.msg || "创建面板入口失败");
    }
    const url = `https://${response.data?.domain || accessForm.domain.trim().toLowerCase()}`;
    const configResponse = await updateConfig("panel_public_url", url);

    if (configResponse.code !== 0)
      toast.error("入口已创建，但首选访问地址保存失败");
    else clearConfigCache();
    setCreatingAccess(false);
    setAccessModal(false);
    toast.success("面板入口已创建，正在完成 DNS 验证和证书签发");
    await loadData(true);
  };

  const setPreferredUrl = async (route: DomainRoute) => {
    const url = `https://${route.domain}${route.listenPort === 443 ? "" : `:${route.listenPort}`}`;
    const response = await updateConfig("panel_public_url", url);

    if (response.code !== 0)
      return toast.error(response.msg || "保存首选地址失败");
    clearConfigCache();
    setConfigs((current) => ({ ...current, panel_public_url: url }));
    setOriginalConfigs((current) => ({ ...current, panel_public_url: url }));
    toast.success("已设为面板首选访问地址");
  };

  if (loading)
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <Spinner label="加载网站设置" />
      </div>
    );

  return (
    <div className="mx-auto w-full max-w-[1200px] space-y-8 p-4 sm:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">系统管理</p>
          <h1 className="mt-1 text-2xl font-semibold">网站设置</h1>
        </div>
        <div className="flex gap-2">
          <Button
            isIconOnly
            aria-label="刷新网站设置"
            isLoading={refreshing}
            title="刷新"
            variant="flat"
            onPress={() => loadData(true)}
          >
            <RefreshCw size={18} />
          </Button>
          <Button
            color="primary"
            isDisabled={!hasChanges}
            isLoading={saving}
            startContent={<Save size={17} />}
            onPress={saveSettings}
          >
            保存网站设置
          </Button>
        </div>
      </header>

      <section className="space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="flex items-center gap-2">
              <Globe2 className="text-primary" size={19} />
              <h2 className="text-base font-semibold">面板访问入口</h2>
            </div>
            <p className="mt-1 text-sm text-default-500">
              使用已登记的域名和托管证书访问面板
            </p>
          </div>
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="flat"
              onPress={() => navigate("/dns-settings")}
            >
              管理 DNS
            </Button>
            <Button
              color="primary"
              size="sm"
              startContent={<Plus size={16} />}
              onPress={openAccessModal}
            >
              新增面板域名
            </Button>
          </div>
        </div>

        <div className="grid gap-px overflow-hidden rounded-md border border-divider bg-divider sm:grid-cols-3">
          <div className="min-w-0 bg-content1 px-4 py-3">
            <p className="text-xs text-default-500">当前浏览器地址</p>
            <a
              className="mt-1 flex items-center gap-1 truncate font-mono text-sm text-primary hover:underline"
              href={currentOrigin}
              rel="noreferrer"
              target="_blank"
            >
              <span className="truncate">{currentOrigin}</span>
              <ExternalLink className="shrink-0" size={13} />
            </a>
          </div>
          <div className="min-w-0 bg-content1 px-4 py-3">
            <p className="text-xs text-default-500">首选访问地址</p>
            <p className="mt-1 truncate font-mono text-sm">{preferredUrl}</p>
          </div>
          <div className="bg-content1 px-4 py-3">
            <p className="text-xs text-default-500">托管入口</p>
            <p className="mt-1 text-sm">
              {panelRoutes.length} 个 ·{" "}
              {panelRoutes.filter((route) => route.state === "active").length}{" "}
              个可用
            </p>
          </div>
        </div>

        {panelRoutes.length === 0 ? (
          <div className="flex min-h-36 flex-col items-center justify-center gap-3 border-y border-divider px-4 text-center text-default-500">
            <LockKeyhole size={28} />
            <p className="text-sm">
              当前访问地址尚未关联面板托管的 DNS 与 HTTPS 证书
            </p>
          </div>
        ) : (
          <div className="divide-y divide-divider border-y border-divider">
            {panelRoutes.map((route) => {
              const status = routeStatus(route);
              const StatusIcon = status.icon;
              const url = `https://${route.domain}${route.listenPort === 443 ? "" : `:${route.listenPort}`}`;
              const isPreferred = preferredUrl === url;

              return (
                <article
                  key={route.id}
                  className="grid gap-3 py-4 lg:grid-cols-[minmax(220px,1.3fr)_minmax(180px,1fr)_minmax(170px,0.8fr)_auto] lg:items-center"
                >
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <a
                        className="truncate font-mono text-sm font-medium text-primary hover:underline"
                        href={url}
                        rel="noreferrer"
                        target="_blank"
                      >
                        {url}
                      </a>
                      {isPreferred && (
                        <Chip color="primary" size="sm" variant="flat">
                          首选
                        </Chip>
                      )}
                    </div>
                    <p className="mt-1 truncate text-xs text-default-500">
                      回源{" "}
                      {route.backendHost &&
                      !["0.0.0.0", "::", "*"].includes(route.backendHost)
                        ? route.backendHost
                        : route.backendNodeName || "入口本机"}
                      :{route.backendPort || "-"}
                    </p>
                  </div>
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <StatusIcon
                        className={
                          status.color === "success"
                            ? "text-success"
                            : status.color === "danger"
                              ? "text-danger"
                              : status.color === "warning"
                                ? "text-warning"
                                : "text-primary"
                        }
                        size={15}
                      />
                      <Chip color={status.color} size="sm" variant="flat">
                        {status.label}
                      </Chip>
                    </div>
                    <p className="mt-1 truncate text-xs text-default-500">
                      {route.lastError ||
                        route.healthError ||
                        "DNS、证书与回源均正常"}
                    </p>
                  </div>
                  <div className="min-w-0 text-sm">
                    <p className="truncate">
                      入口：{route.nodeName || "未知节点"}
                    </p>
                    <p className="mt-1 truncate text-xs text-default-500">
                      证书有效至 {formatTime(route.certificateExpiresAt)}
                    </p>
                  </div>
                  <div className="flex justify-end gap-2">
                    {!isPreferred && (
                      <Button
                        size="sm"
                        variant="flat"
                        onPress={() => setPreferredUrl(route)}
                      >
                        设为首选
                      </Button>
                    )}
                    <Button
                      isIconOnly
                      aria-label="打开面板域名"
                      as="a"
                      href={url}
                      size="sm"
                      target="_blank"
                      title="打开"
                      variant="light"
                    >
                      <ExternalLink size={16} />
                    </Button>
                  </div>
                </article>
              );
            })}
          </div>
        )}
        <div className="flex items-start gap-2 border-l-3 border-warning px-3 py-2 text-xs leading-5 text-default-600">
          <AlertTriangle className="mt-0.5 shrink-0 text-warning" size={15} />
          <span>
            保留原始 IP 访问地址作为救援入口。新域名会先完成 DNS
            和证书配置，不会自动删除或替换现有域名。
          </span>
        </div>
      </section>

      <Divider />

      <section className="space-y-5">
        <div>
          <div className="flex items-center gap-2">
            <Radio className="text-primary" size={19} />
            <h2 className="text-base font-semibold">Agent 通信</h2>
          </div>
          <p className="mt-1 text-sm text-default-500">
            节点、接入端和升级脚本连接面板时使用
          </p>
        </div>
        <Input
          description="仅填写公网 IP:端口；不要填写 http://、https://，也不要使用 CDN 代理域名。"
          errorMessage="格式应为 IP:端口，例如 168.110.113.19:6366"
          isInvalid={Boolean(configs.ip) && !endpoint}
          label="Agent 通信地址"
          placeholder="168.110.113.19:6366"
          startContent={<Server className="text-default-400" size={16} />}
          value={configs.ip || ""}
          onValueChange={(value) => updateValue("ip", value)}
        />
        <div className="grid gap-px overflow-hidden rounded-md border border-divider bg-divider sm:grid-cols-2">
          <div className="bg-content1 px-4 py-3">
            <p className="text-xs text-default-500">用途</p>
            <p className="mt-1 text-sm">Agent WebSocket、配置与数据上报</p>
          </div>
          <div className="bg-content1 px-4 py-3">
            <p className="text-xs text-default-500">与浏览器域名的关系</p>
            <p className="mt-1 text-sm">
              相互独立，修改面板域名不会更改 Agent 地址
            </p>
          </div>
        </div>
      </section>

      <Divider />

      <section className="space-y-5">
        <div>
          <div className="flex items-center gap-2">
            <Settings className="text-primary" size={19} />
            <h2 className="text-base font-semibold">品牌与登录</h2>
          </div>
          <p className="mt-1 text-sm text-default-500">
            设置界面名称和登录验证方式
          </p>
        </div>
        <Input
          description="显示在浏览器标签页和导航栏。"
          label="应用名称"
          placeholder="云巢 CloudNest"
          value={configs.app_name || ""}
          onValueChange={(value) => updateValue("app_name", value)}
        />
        <div className="flex flex-col gap-4 border-y border-divider py-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div className="flex items-center gap-2">
              <ShieldCheck className="text-default-500" size={17} />
              <p className="text-sm font-medium">登录验证码</p>
            </div>
            <p className="mt-1 text-xs text-default-500">
              开启后，用户登录时需要完成验证码验证
            </p>
          </div>
          <Switch
            isSelected={configs.captcha_enabled === "true"}
            onValueChange={(value) =>
              updateValue("captcha_enabled", value ? "true" : "false")
            }
          >
            {configs.captcha_enabled === "true" ? "已启用" : "已禁用"}
          </Switch>
        </div>
        {configs.captcha_enabled === "true" && (
          <Select
            label="验证码类型"
            selectedKeys={[configs.captcha_type || "RANDOM"]}
            onSelectionChange={(keys) =>
              updateValue(
                "captcha_type",
                String(Array.from(keys)[0] || "RANDOM"),
              )
            }
          >
            <SelectItem key="RANDOM">随机类型</SelectItem>
            <SelectItem key="SLIDER">滑块验证码</SelectItem>
            <SelectItem key="WORD_IMAGE_CLICK">文字点选验证码</SelectItem>
            <SelectItem key="ROTATE">旋转验证码</SelectItem>
            <SelectItem key="CONCAT">拼图验证码</SelectItem>
          </Select>
        )}
      </section>

      <Modal
        isOpen={accessModal}
        scrollBehavior="inside"
        size="2xl"
        onOpenChange={setAccessModal}
      >
        <ModalContent>
          <ModalHeader>新增面板访问域名</ModalHeader>
          <ModalBody className="grid gap-4 sm:grid-cols-2">
            {zones.length === 0 ? (
              <div className="sm:col-span-2 flex items-center justify-between border-y border-warning-200 bg-warning-50 px-3 py-3 text-sm text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200">
                <span>尚未添加可用于托管 HTTPS 的 Cloudflare 域名。</span>
                <Button
                  color="warning"
                  size="sm"
                  variant="flat"
                  onPress={() => {
                    setAccessModal(false);
                    navigate("/dns-settings");
                  }}
                >
                  添加 DNS
                </Button>
              </div>
            ) : null}
            <Select
              className="sm:col-span-2"
              label="DNS 与主域名"
              placeholder="选择已登记的域名"
              selectedKeys={accessForm.dnsZoneId ? [accessForm.dnsZoneId] : []}
              onSelectionChange={(keys) =>
                selectZone(String(Array.from(keys)[0] || ""))
              }
            >
              {zones.map((zone) => (
                <SelectItem
                  key={String(zone.id)}
                  textValue={`${zone.zoneName} ${zone.accountName}`}
                >
                  {zone.zoneName} · {zone.accountName}
                </SelectItem>
              ))}
            </Select>
            <Input
              className="sm:col-span-2"
              description="DNS 记录、Let's Encrypt 证书和 443 入口会自动创建。"
              label="面板访问域名"
              placeholder="panel.example.com"
              value={accessForm.domain}
              onValueChange={(domain) =>
                setAccessForm((current) => ({ ...current, domain }))
              }
            />
            <Select
              className="sm:col-span-2"
              description="入口节点接收浏览器 HTTPS 请求，再回源到当前 Agent 通信地址。"
              label="HTTPS 入口节点"
              placeholder="选择监听 443 的服务器"
              selectedKeys={
                accessForm.entryNodeId ? [accessForm.entryNodeId] : []
              }
              onSelectionChange={(keys) =>
                setAccessForm((current) => ({
                  ...current,
                  entryNodeId: String(Array.from(keys)[0] || ""),
                }))
              }
            >
              {nodes.map((node) => (
                <SelectItem
                  key={String(node.id)}
                  textValue={`${node.name} ${node.serverIp || node.ip || ""}`}
                >
                  {node.name} · {node.serverIp || node.ip || "地址未知"} ·{" "}
                  {node.status === 1 ? "在线" : "离线"}
                </SelectItem>
              ))}
            </Select>
            <div className="sm:col-span-2 grid gap-px overflow-hidden rounded-md border border-divider bg-divider sm:grid-cols-3">
              <div className="bg-content1 px-3 py-3">
                <p className="text-xs text-default-500">访问协议</p>
                <p className="mt-1 text-sm">HTTPS · 443</p>
              </div>
              <div className="bg-content1 px-3 py-3">
                <p className="text-xs text-default-500">面板回源</p>
                <p className="mt-1 truncate font-mono text-sm">
                  HTTP · {endpoint?.port || "-"}
                </p>
              </div>
              <div className="bg-content1 px-3 py-3">
                <p className="text-xs text-default-500">证书</p>
                <p className="mt-1 text-sm">自动申请与续期</p>
              </div>
            </div>
            <p className="sm:col-span-2 text-xs leading-5 text-default-500">
              入口节点可以是面板所在服务器，也可以是另一台 443
              端口空闲的在线节点。异机入口需要能够访问上方显示的面板回源地址。
            </p>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setAccessModal(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isDisabled={zones.length === 0}
              isLoading={creatingAccess}
              onPress={createPanelAccess}
            >
              创建 DNS 与 HTTPS 入口
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
