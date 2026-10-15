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
import { Switch } from "@heroui/switch";
import { Tabs, Tab } from "@heroui/tabs";
import {
  CircleCheck,
  Clock3,
  CloudCog,
  History,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import toast from "react-hot-toast";
import { useNavigate } from "react-router-dom";

import AccessResourceTabs from "@/components/access-resource-tabs";
import PageShell from "@/components/page-shell";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";
import {
  deleteDynamicDnsProvider,
  deleteDynamicDnsRule,
  getDynamicDnsHistory,
  getDynamicDnsOverview,
  getInternalConnectors,
  getNodeList,
  runDynamicDnsRule,
  saveDynamicDnsProvider,
  saveDynamicDnsRule,
  type DynamicDnsHistoryItem,
  type DynamicDnsOverview,
  type DynamicDnsProviderOption,
  type DynamicDnsProviderType,
  type DynamicDnsRule,
  type InternalConnector,
} from "@/api";

interface NodeOption {
  id: number;
  name: string;
  serverIp?: string;
  ip?: string;
  status: number;
  version?: string;
}
const EMPTY: DynamicDnsOverview = {
  rules: [],
  providers: [],
  summary: { rules: 0, active: 0, healthy: 0, errors: 0 },
  minimumAgentVersion: "2.21.0",
};
const providerLabel: Record<DynamicDnsProviderType, string> = {
  cloudflare: "Cloudflare",
  dnspod: "DNSPod",
  aliyun: "阿里云 DNS",
};
const ruleInitial = {
  id: undefined as number | undefined,
  name: "",
  sourceType: "node" as "node" | "connector",
  nodeId: "",
  connectorId: "",
  providerKey: "",
  zoneName: "",
  recordName: "",
  recordType: "A" as "A" | "AAAA",
  ttl: "600",
  checkIntervalSeconds: "60",
  enabled: true,
};
const providerInitial = {
  id: undefined as number | undefined,
  name: "",
  provider: "cloudflare" as DynamicDnsProviderType,
  credentialA: "",
  credentialB: "",
  enabled: true,
};
const time = (value?: number) =>
  value
    ? new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false,
      }).format(value)
    : "尚未执行";
const statusLabel = (value: string) =>
  value === "success" ? "正常" : value === "error" ? "失败" : "待检测";

export default function DynamicDnsPage() {
  const navigate = useNavigate();
  const [data, setData] = useState(EMPTY);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [connectors, setConnectors] = useState<InternalConnector[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState("rules");
  const [ruleOpen, setRuleOpen] = useState(false);
  const [providerOpen, setProviderOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [ruleForm, setRuleForm] = useState(ruleInitial);
  const [providerForm, setProviderForm] = useState(providerInitial);
  const [history, setHistory] = useState<DynamicDnsHistoryItem[]>([]);
  const [historyName, setHistoryName] = useState("");
  const [busy, setBusy] = useState<number | string | null>(null);

  const load = async () => {
    setLoading(true);
    const [overview, nodeResponse, connectorResponse] = await Promise.all([
      getDynamicDnsOverview(),
      getNodeList(),
      getInternalConnectors(),
    ]);

    if (overview.code === 0) setData(overview.data || EMPTY);
    else toast.error(overview.msg || "获取动态 DNS 失败");
    if (nodeResponse.code === 0) setNodes(nodeResponse.data || []);
    if (connectorResponse.code === 0)
      setConnectors(connectorResponse.data || []);
    setLoading(false);
  };

  useEffect(() => {
    void load();
  }, []);

  const dynamicProviders = useMemo(() => {
    const unique = new Map<number, DynamicDnsProviderOption>();

    data.providers
      .filter((item) => item.source === "dynamic")
      .forEach((item) => unique.set(item.id, item));

    return Array.from(unique.values());
  }, [data.providers]);
  const providerOption = data.providers.find(
    (item) => item.optionKey === ruleForm.providerKey,
  );

  const openRule = (rule?: DynamicDnsRule) => {
    const provider = rule
      ? data.providers.find(
          (item) =>
            item.source === rule.providerSource &&
            item.id === rule.providerRefId &&
            (!rule.zoneRefId || item.zoneRefId === rule.zoneRefId),
        )
      : undefined;

    setRuleForm(
      rule
        ? {
            id: rule.id,
            name: rule.name,
            sourceType: rule.sourceType || "node",
            nodeId: rule.nodeId ? String(rule.nodeId) : "",
            connectorId: rule.connectorId ? String(rule.connectorId) : "",
            providerKey:
              provider?.optionKey ||
              `${rule.providerSource}:${rule.providerRefId}`,
            zoneName: rule.zoneName,
            recordName: rule.recordName,
            recordType: rule.recordType,
            ttl: String(rule.ttl),
            checkIntervalSeconds: String(rule.checkIntervalSeconds),
            enabled: Boolean(rule.enabled),
          }
        : ruleInitial,
    );
    setRuleOpen(true);
  };
  const saveRule = async () => {
    const option = data.providers.find(
      (item) => item.optionKey === ruleForm.providerKey,
    );

    if (!option || !ruleForm.recordName.trim())
      return toast.error("请选择 DNS 配置并填写记录");
    if (ruleForm.sourceType === "node" && !ruleForm.nodeId)
      return toast.error("请选择检测节点");
    if (ruleForm.sourceType === "connector" && !ruleForm.connectorId)
      return toast.error("请选择家庭设备");
    setBusy("rule");
    const response = await saveDynamicDnsRule({
      id: ruleForm.id,
      name: ruleForm.name,
      sourceType: ruleForm.sourceType,
      nodeId:
        ruleForm.sourceType === "node" ? Number(ruleForm.nodeId) : undefined,
      connectorId:
        ruleForm.sourceType === "connector"
          ? Number(ruleForm.connectorId)
          : undefined,
      providerSource: option.source,
      providerRefId: option.id,
      provider: option.provider,
      zoneRefId: option.zoneRefId,
      zoneName: option.zoneName || ruleForm.zoneName,
      recordName: ruleForm.recordName,
      recordType: ruleForm.recordType,
      ttl: Number(ruleForm.ttl),
      checkIntervalSeconds: Number(ruleForm.checkIntervalSeconds),
      enabled: ruleForm.enabled,
    });

    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || "保存规则失败");
    toast.success("动态 DNS 规则已保存");
    setRuleOpen(false);
    await load();
  };
  const openProvider = (provider?: DynamicDnsProviderOption) => {
    setProviderForm(
      provider
        ? {
            id: provider.id,
            name: provider.name,
            provider: provider.provider,
            credentialA: "",
            credentialB: "",
            enabled: Boolean(provider.enabled),
          }
        : providerInitial,
    );
    setProviderOpen(true);
  };
  const saveProvider = async () => {
    if (
      !providerForm.name.trim() ||
      (!providerForm.id && !providerForm.credentialA.trim())
    )
      return toast.error("请填写配置名称和凭据");
    if (
      providerForm.provider !== "cloudflare" &&
      !providerForm.id &&
      !providerForm.credentialB.trim()
    )
      return toast.error("请填写完整的密钥对");
    setBusy("provider");
    const response = await saveDynamicDnsProvider(providerForm);

    setBusy(null);
    if (response.code !== 0)
      return toast.error(response.msg || "保存 DNS 配置失败");
    toast.success("DNS 提供商配置已保存");
    setProviderOpen(false);
    await load();
  };
  const run = async (rule: DynamicDnsRule) => {
    setBusy(rule.id);
    const response = await runDynamicDnsRule(rule.id);

    setBusy(null);
    if (response.code !== 0) toast.error(response.msg || "执行失败");
    else toast.success("检测和同步已完成");
    await load();
  };
  const showHistory = async (rule: DynamicDnsRule) => {
    const response = await getDynamicDnsHistory(rule.id);

    if (response.code !== 0) return toast.error(response.msg || "读取历史失败");
    setHistory(response.data || []);
    setHistoryName(rule.name);
    setHistoryOpen(true);
  };
  const removeRule = async (rule: DynamicDnsRule) => {
    if (!window.confirm(`删除动态 DNS 规则“${rule.name}”？DNS 记录会保留。`))
      return;
    const response = await deleteDynamicDnsRule(rule.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    await load();
  };
  const removeProvider = async (provider: DynamicDnsProviderOption) => {
    if (!window.confirm(`删除 DNS 配置“${provider.name}”？`)) return;
    const response = await deleteDynamicDnsProvider(provider.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    await load();
  };

  return (
    <PageShell className="space-y-5" width="wide">
      <AccessResourceTabs />
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">资源中心</p>
          <h1 className="mt-1 text-2xl font-semibold">动态解析</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-default-500">
            本页只管理公网地址变化后的更新规则和执行历史；DNS
            凭据统一优先在域名管理维护，旧的独立凭据仍可兼容使用。
          </p>
        </div>
        <div className="flex gap-2">
          <Button isIconOnly aria-label="刷新" variant="flat" onPress={load}>
            <RefreshCw size={17} />
          </Button>
          {tab === "rules" ? (
            <Button
              color="primary"
              startContent={<Plus size={17} />}
              onPress={() => openRule()}
            >
              新建规则
            </Button>
          ) : (
            <Button
              color="primary"
              startContent={<Plus size={17} />}
              onPress={() => navigate("/dns-settings")}
            >
              前往域名管理
            </Button>
          )}
        </div>
      </header>
      <section className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        <StatsCard
          color="primary"
          icon={<CloudCog size={20} />}
          title="规则"
          value={data.summary.rules}
        />
        <StatsCard
          color="secondary"
          icon={<Clock3 size={20} />}
          title="运行中"
          value={data.summary.active}
        />
        <StatsCard
          color="success"
          icon={<CircleCheck size={20} />}
          title="正常"
          value={data.summary.healthy}
        />
        <StatsCard
          color="danger"
          icon={<TriangleAlert size={20} />}
          title="失败"
          value={data.summary.errors}
        />
      </section>
      <Tabs
        selectedKey={tab}
        variant="underlined"
        onSelectionChange={(key) => setTab(String(key))}
      >
        <Tab key="rules" title={`更新规则 ${data.rules.length}`} />
        <Tab key="providers" title={`DNS 配置 ${dynamicProviders.length}`} />
      </Tabs>
      {tab === "rules" ? (
        <section className="space-y-3">
          {loading ? (
            <div className="flex min-h-48 items-center justify-center text-default-500">
              正在读取规则
            </div>
          ) : data.rules.length === 0 ? (
            <div className="flex min-h-48 flex-col items-center justify-center gap-2 text-default-500">
              <CloudCog size={30} />
              <span>尚未创建动态 DNS 规则</span>
            </div>
          ) : (
            <ResourceCardGrid>
              {data.rules.map((rule) => {
                const connector = rule.sourceType === "connector";
                const sourceOnline = connector
                  ? rule.connectorOnline
                  : rule.nodeOnline;
                const failing =
                  Boolean(rule.enabled) && rule.lastStatus === "error";
                const drifted =
                  rule.lastDetectedIp &&
                  rule.lastDetectedIp !== rule.lastAppliedIp;

                return (
                  <ResourceCard
                    key={rule.id}
                    actions={[
                      {
                        label: "检测",
                        icon: <Play size={14} />,
                        color: "primary",
                        isLoading: busy === rule.id,
                        onPress: () => void run(rule),
                      },
                      {
                        label: "历史",
                        icon: <History size={14} />,
                        onPress: () => void showHistory(rule),
                      },
                      {
                        label: "编辑",
                        icon: <Pencil size={14} />,
                        onPress: () => openRule(rule),
                      },
                      {
                        label: "删除",
                        icon: <Trash2 size={14} />,
                        color: "danger",
                        onPress: () => void removeRule(rule),
                      },
                    ]}
                    blocks={[
                      {
                        label: "解析",
                        rows: [
                          {
                            label: "当前",
                            value: rule.lastAppliedIp || "尚未同步",
                          },
                          ...(drifted
                            ? [{ label: "检测到", value: rule.lastDetectedIp }]
                            : []),
                        ],
                      },
                      {
                        label: connector ? "家庭设备" : "检测节点",
                        status: sourceOnline
                          ? { text: "在线", color: "success" as const }
                          : { text: "离线", color: "danger" as const },
                        rows: [
                          {
                            value: connector
                              ? rule.connectorName ||
                                `接入端 ${rule.connectorId}`
                              : rule.nodeName || `节点 ${rule.nodeId}`,
                            mono: false,
                          },
                        ],
                      },
                    ]}
                    chips={[
                      { text: rule.recordType, color: "primary" },
                      { text: providerLabel[rule.provider] },
                    ]}
                    danger={failing}
                    facts={[
                      {
                        label: "检测间隔",
                        value: `${rule.checkIntervalSeconds} 秒 · TTL ${rule.ttl}`,
                      },
                      {
                        label: "Agent",
                        value:
                          (connector
                            ? rule.connectorVersion
                            : rule.nodeVersion) || "未知",
                      },
                      {
                        label: "DNS 账号",
                        value: rule.providerAccountName || rule.zoneName,
                      },
                      { label: "最近检测", value: time(rule.lastCheckedAt) },
                    ]}
                    notice={
                      rule.lastError
                        ? {
                            icon: <TriangleAlert size={14} />,
                            label: "最近错误",
                            value: (
                              <span title={rule.lastError}>
                                {rule.lastError}
                              </span>
                            ),
                            tone: "danger",
                          }
                        : undefined
                    }
                    status={
                      !rule.enabled
                        ? { text: "已停用", color: "default" }
                        : {
                            text: statusLabel(rule.lastStatus),
                            color:
                              rule.lastStatus === "success"
                                ? "success"
                                : rule.lastStatus === "error"
                                  ? "danger"
                                  : "warning",
                          }
                    }
                    subtitles={[rule.recordName]}
                    title={rule.name}
                  />
                );
              })}
            </ResourceCardGrid>
          )}
        </section>
      ) : (
        <section className="space-y-3">
          <div className="flex flex-col gap-3 border-b border-divider px-4 py-3 text-sm text-default-500 sm:flex-row sm:items-center sm:justify-between">
            <span>
              DNS 凭据统一入口是域名管理。这里仅显示历史兼容的独立 DDNS
              凭据，后续新配置建议从域名管理添加。
            </span>
            <Button
              size="sm"
              variant="flat"
              onPress={() => navigate("/dns-settings")}
            >
              打开域名管理
            </Button>
          </div>
          {dynamicProviders.length === 0 ? (
            <div className="flex min-h-48 flex-col items-center justify-center gap-3 text-default-500">
              <span>没有独立 DDNS 凭据</span>
              <Button
                color="primary"
                size="sm"
                variant="flat"
                onPress={() => navigate("/dns-settings")}
              >
                前往域名管理添加
              </Button>
            </div>
          ) : (
            <ResourceCardGrid>
              {dynamicProviders.map((provider) => (
                <ResourceCard
                  key={provider.id}
                  actions={[
                    {
                      label: "编辑",
                      icon: <Pencil size={14} />,
                      onPress: () => openProvider(provider),
                    },
                    {
                      label: "删除",
                      icon: <Trash2 size={14} />,
                      color: "danger",
                      onPress: () => void removeProvider(provider),
                    },
                  ]}
                  chips={[{ text: providerLabel[provider.provider] }]}
                  danger={Boolean(provider.lastError)}
                  notice={
                    provider.lastError
                      ? {
                          icon: <TriangleAlert size={14} />,
                          label: "最近错误",
                          value: (
                            <span title={provider.lastError}>
                              {provider.lastError}
                            </span>
                          ),
                          tone: "danger",
                        }
                      : undefined
                  }
                  status={
                    provider.enabled
                      ? { text: "启用", color: "success" }
                      : { text: "停用", color: "default" }
                  }
                  subtitles={["历史兼容凭据，建议迁移到域名管理"]}
                  title={provider.name}
                />
              ))}
            </ResourceCardGrid>
          )}
        </section>
      )}

      <Modal isOpen={ruleOpen} size="3xl" onOpenChange={setRuleOpen}>
        <ModalContent>
          <ModalHeader>
            {ruleForm.id ? "编辑动态解析规则" : "新建动态解析规则"}
          </ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 md:grid-cols-2">
              <Input
                label="规则名称"
                placeholder="家庭宽带 IPv4"
                value={ruleForm.name}
                onValueChange={(value) =>
                  setRuleForm({ ...ruleForm, name: value })
                }
              />
              <Select
                isRequired
                label="检测来源"
                selectedKeys={[ruleForm.sourceType]}
                onSelectionChange={(keys) =>
                  setRuleForm({
                    ...ruleForm,
                    sourceType: String(Array.from(keys)[0] || "node") as
                      "node" | "connector",
                    nodeId: "",
                    connectorId: "",
                  })
                }
              >
                <SelectItem key="node">服务器节点</SelectItem>
                <SelectItem key="connector">家庭设备</SelectItem>
              </Select>
            </div>
            {ruleForm.sourceType === "node" ? (
              <Select
                isRequired
                description={`Agent 需为 ${data.minimumAgentVersion} 或更高版本`}
                label="检测节点"
                selectedKeys={ruleForm.nodeId ? [ruleForm.nodeId] : []}
                onSelectionChange={(keys) =>
                  setRuleForm({
                    ...ruleForm,
                    nodeId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {nodes.map((node) => (
                  <SelectItem key={String(node.id)} textValue={node.name}>
                    {node.name} · {node.status === 1 ? "在线" : "离线"} · Agent{" "}
                    {node.version || "未知"}
                  </SelectItem>
                ))}
              </Select>
            ) : (
              <Select
                isRequired
                description={`Agent 需为 ${data.minimumAgentVersion} 或更高版本`}
                label="家庭设备"
                selectedKeys={
                  ruleForm.connectorId ? [ruleForm.connectorId] : []
                }
                onSelectionChange={(keys) =>
                  setRuleForm({
                    ...ruleForm,
                    connectorId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {connectors.map((item) => (
                  <SelectItem key={String(item.id)} textValue={item.name}>
                    {item.name} · {item.online ? "在线" : "离线"} · Agent{" "}
                    {item.version || "未知"}
                  </SelectItem>
                ))}
              </Select>
            )}
            <Select
              isRequired
              label="DNS 提供商配置"
              selectedKeys={ruleForm.providerKey ? [ruleForm.providerKey] : []}
              onSelectionChange={(keys) =>
                setRuleForm({
                  ...ruleForm,
                  providerKey: String(Array.from(keys)[0] || ""),
                })
              }
            >
              {data.providers
                .filter((item) => item.enabled)
                .map((item) => (
                  <SelectItem
                    key={item.optionKey}
                    textValue={`${item.name} ${item.zoneName || ""}`}
                  >
                    {providerLabel[item.provider]} · {item.name}
                    {item.zoneName ? ` · ${item.zoneName}` : ""}
                  </SelectItem>
                ))}
            </Select>
            {providerOption?.source === "dynamic" && (
              <Input
                isRequired
                label="主域名（Zone）"
                placeholder="example.com"
                value={ruleForm.zoneName}
                onValueChange={(value) =>
                  setRuleForm({ ...ruleForm, zoneName: value })
                }
              />
            )}
            <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_150px]">
              <Input
                isRequired
                label="域名记录"
                placeholder="home.example.com 或 home"
                value={ruleForm.recordName}
                onValueChange={(value) =>
                  setRuleForm({ ...ruleForm, recordName: value })
                }
              />
              <Select
                label="记录类型"
                selectedKeys={[ruleForm.recordType]}
                onSelectionChange={(keys) =>
                  setRuleForm({
                    ...ruleForm,
                    recordType: String(Array.from(keys)[0] || "A") as
                      "A" | "AAAA",
                  })
                }
              >
                <SelectItem key="A">A · IPv4</SelectItem>
                <SelectItem key="AAAA">AAAA · IPv6</SelectItem>
              </Select>
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              <Input
                label="DNS TTL（秒）"
                max={86400}
                min={60}
                type="number"
                value={ruleForm.ttl}
                onValueChange={(value) =>
                  setRuleForm({ ...ruleForm, ttl: value })
                }
              />
              <Input
                description="最低 30 秒；仅地址变化时更新 DNS"
                label="检测间隔（秒）"
                max={86400}
                min={30}
                type="number"
                value={ruleForm.checkIntervalSeconds}
                onValueChange={(value) =>
                  setRuleForm({ ...ruleForm, checkIntervalSeconds: value })
                }
              />
            </div>
            <Switch
              isSelected={ruleForm.enabled}
              onValueChange={(value) =>
                setRuleForm({ ...ruleForm, enabled: value })
              }
            >
              启用自动检测
            </Switch>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setRuleOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "rule"}
              onPress={saveRule}
            >
              保存规则
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={providerOpen} size="2xl" onOpenChange={setProviderOpen}>
        <ModalContent>
          <ModalHeader>
            {providerForm.id ? "编辑 DNS 提供商" : "添加 DNS 提供商"}
          </ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 md:grid-cols-2">
              <Input
                isRequired
                autoComplete="off"
                label="配置名称"
                name="dynamic-dns-config-name"
                placeholder="我的 DNSPod"
                value={providerForm.name}
                onValueChange={(value) =>
                  setProviderForm({ ...providerForm, name: value })
                }
              />
              <Select
                label="提供商"
                selectedKeys={[providerForm.provider]}
                onSelectionChange={(keys) =>
                  setProviderForm({
                    ...providerForm,
                    provider: String(
                      Array.from(keys)[0] || "cloudflare",
                    ) as DynamicDnsProviderType,
                  })
                }
              >
                <SelectItem key="cloudflare">Cloudflare</SelectItem>
                <SelectItem key="dnspod">DNSPod</SelectItem>
                <SelectItem key="aliyun">阿里云 DNS</SelectItem>
              </Select>
            </div>
            <Input
              autoComplete="new-password"
              data-1p-ignore="true"
              data-lpignore="true"
              label={
                providerForm.provider === "cloudflare"
                  ? "API Token"
                  : providerForm.provider === "dnspod"
                    ? "SecretId"
                    : "AccessKey ID"
              }
              name="dynamic-dns-credential-id"
              placeholder={providerForm.id ? "已保存，留空保持不变" : ""}
              type="password"
              value={providerForm.credentialA}
              onValueChange={(value) =>
                setProviderForm({ ...providerForm, credentialA: value })
              }
            />
            {providerForm.provider !== "cloudflare" && (
              <Input
                autoComplete="new-password"
                data-1p-ignore="true"
                data-lpignore="true"
                label={
                  providerForm.provider === "dnspod"
                    ? "SecretKey"
                    : "AccessKey Secret"
                }
                name="dynamic-dns-credential-secret"
                placeholder={providerForm.id ? "已保存，留空保持不变" : ""}
                type="password"
                value={providerForm.credentialB}
                onValueChange={(value) =>
                  setProviderForm({ ...providerForm, credentialB: value })
                }
              />
            )}
            <Switch
              isSelected={providerForm.enabled}
              onValueChange={(value) =>
                setProviderForm({ ...providerForm, enabled: value })
              }
            >
              启用此配置
            </Switch>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setProviderOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "provider"}
              onPress={saveProvider}
            >
              保存配置
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={historyOpen}
        scrollBehavior="inside"
        size="3xl"
        onOpenChange={setHistoryOpen}
      >
        <ModalContent>
          <ModalHeader>{historyName} · 变更历史</ModalHeader>
          <ModalBody>
            {history.length === 0 ? (
              <div className="flex min-h-40 items-center justify-center text-default-500">
                尚无检测记录
              </div>
            ) : (
              <div className="divide-y divide-divider border-y border-divider">
                {history.map((item) => (
                  <div
                    key={item.id}
                    className="grid gap-2 py-3 sm:grid-cols-[120px_minmax(0,1fr)_160px] sm:items-center"
                  >
                    <Chip
                      className="w-fit"
                      color={
                        item.status === "updated"
                          ? "success"
                          : item.status === "failed"
                            ? "danger"
                            : "default"
                      }
                      size="sm"
                      variant="flat"
                    >
                      {item.status === "updated"
                        ? "地址已更新"
                        : item.status === "failed"
                          ? "更新失败"
                          : "地址未变化"}
                    </Chip>
                    <div className="min-w-0">
                      <p className="truncate font-mono text-sm">
                        {item.oldIp || "-"}{" "}
                        {item.status === "updated" ? "→" : ""}{" "}
                        {item.newIp || ""}
                      </p>
                      {item.error && (
                        <p className="mt-1 text-sm text-danger">{item.error}</p>
                      )}
                    </div>
                    <span className="text-xs text-default-500 sm:text-right">
                      {time(item.createdTime)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setHistoryOpen(false)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
