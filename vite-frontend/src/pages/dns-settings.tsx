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
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import { useSearchParams } from "react-router-dom";
import {
  AlertTriangle,
  CheckCircle2,
  CloudCog,
  Globe2,
  KeyRound,
  Pencil,
  Plus,
  RefreshCw,
  Trash2,
} from "lucide-react";
import toast from "react-hot-toast";

import AccessResourceTabs from "@/components/access-resource-tabs";
import PageShell from "@/components/page-shell";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";
import {
  deleteDnsProviderAccount,
  deleteDynamicDnsProvider,
  getDynamicDnsOverview,
  getDnsProviderData,
  saveDynamicDnsProvider,
  saveDnsProviderAccount,
  syncDnsProviderAccount,
  type DnsManagedRecord,
  type DnsProviderAccount,
  type DnsProviderSummary,
  type DnsZone,
  type DynamicDnsProviderOption,
} from "@/api";
import { formatDateTime, truthy } from "@/utils/format";

const emptySummary: DnsProviderSummary = {
  accounts: 0,
  zones: 0,
  records: 0,
  errors: 0,
};
const emptyForm = {
  id: undefined as number | undefined,
  name: "",
  apiToken: "",
  enabled: true,
};
const emptyCarrierForm = {
  id: undefined as number | undefined,
  name: "",
  provider: "dnspod" as "dnspod" | "aliyun",
  credentialA: "",
  credentialB: "",
  enabled: true,
};
const timeText = (value?: number) => formatDateTime(value, "尚未同步");

export default function DnsSettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [loading, setLoading] = useState(true);
  const [accounts, setAccounts] = useState<DnsProviderAccount[]>([]);
  const [zones, setZones] = useState<DnsZone[]>([]);
  const [records, setRecords] = useState<DnsManagedRecord[]>([]);
  const [summary, setSummary] = useState<DnsProviderSummary>(emptySummary);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [syncingId, setSyncingId] = useState<number>();
  const [carrierProviders, setCarrierProviders] = useState<
    DynamicDnsProviderOption[]
  >([]);
  const [carrierFormOpen, setCarrierFormOpen] = useState(false);
  const [carrierForm, setCarrierForm] = useState(emptyCarrierForm);
  const [carrierSaving, setCarrierSaving] = useState(false);

  const loadData = useCallback(async () => {
    const [response, dynamicResponse] = await Promise.all([
      getDnsProviderData(),
      getDynamicDnsOverview(),
    ]);

    if (response.code === 0) {
      setAccounts(response.data?.accounts || []);
      setZones(response.data?.zones || []);
      setRecords(response.data?.records || []);
      setSummary(response.data?.summary || emptySummary);
    } else {
      toast.error(response.msg || "加载 DNS 配置失败");
    }
    if (dynamicResponse.code === 0) {
      setCarrierProviders(
        (dynamicResponse.data?.providers || []).filter(
          (item) =>
            item.source === "dynamic" &&
            (item.provider === "dnspod" || item.provider === "aliyun"),
        ),
      );
    } else {
      toast.error(dynamicResponse.msg || "加载线路 DNS 配置失败");
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  useEffect(() => {
    if (searchParams.get("add") !== "carrier") return;
    setCarrierForm(emptyCarrierForm);
    setCarrierFormOpen(true);
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  const zonesByAccount = useMemo(() => {
    const result = new Map<number, DnsZone[]>();

    zones.forEach((zone) =>
      result.set(zone.accountId, [...(result.get(zone.accountId) || []), zone]),
    );

    return result;
  }, [zones]);

  const openCreate = () => {
    setForm(emptyForm);
    setFormOpen(true);
  };

  const openEdit = (account: DnsProviderAccount) => {
    setForm({
      id: account.id,
      name: account.name,
      apiToken: "",
      enabled: truthy(account.enabled),
    });
    setFormOpen(true);
  };

  const submit = async () => {
    if (!form.name.trim()) return toast.error("请输入配置名称");
    if (!form.id && !form.apiToken.trim())
      return toast.error("首次添加需要填写 Cloudflare API Token");
    setSaving(true);
    const response = await saveDnsProviderAccount(form);

    setSaving(false);
    if (response.code !== 0)
      return toast.error(response.msg || "保存 Cloudflare 配置失败");
    toast.success(
      `Cloudflare 已连接，同步 ${response.data?.zoneCount || 0} 个 Zone`,
    );
    setFormOpen(false);
    void loadData();
  };

  const sync = async (account: DnsProviderAccount) => {
    setSyncingId(account.id);
    const response = await syncDnsProviderAccount(account.id);

    setSyncingId(undefined);
    if (response.code !== 0)
      return toast.error(response.msg || "同步 Zone 失败");
    toast.success(`已同步 ${response.data?.zoneCount || 0} 个 Zone`);
    void loadData();
  };

  const remove = async (account: DnsProviderAccount) => {
    if (
      !window.confirm(
        `确认删除“${account.name}”吗？Cloudflare 中的 DNS 记录不会被删除。`,
      )
    )
      return;
    const response = await deleteDnsProviderAccount(account.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("Cloudflare 配置已删除");
    void loadData();
  };

  const openCarrierCreate = () => {
    setCarrierForm(emptyCarrierForm);
    setCarrierFormOpen(true);
  };

  const openCarrierEdit = (provider: DynamicDnsProviderOption) => {
    setCarrierForm({
      id: provider.id,
      name: provider.name,
      provider: provider.provider as "dnspod" | "aliyun",
      credentialA: "",
      credentialB: "",
      enabled: truthy(provider.enabled),
    });
    setCarrierFormOpen(true);
  };

  const submitCarrier = async () => {
    if (!carrierForm.name.trim()) return toast.error("请输入配置名称");
    if (
      !carrierForm.id &&
      (!carrierForm.credentialA.trim() || !carrierForm.credentialB.trim())
    )
      return toast.error("首次添加需要填写完整的密钥对");
    setCarrierSaving(true);
    const response = await saveDynamicDnsProvider(carrierForm);

    setCarrierSaving(false);
    if (response.code !== 0)
      return toast.error(response.msg || "保存线路 DNS 配置失败");
    toast.success(
      `${carrierForm.provider === "dnspod" ? "DNSPod" : "阿里云 DNS"} 配置已保存`,
    );
    setCarrierFormOpen(false);
    void loadData();
  };

  const removeCarrier = async (provider: DynamicDnsProviderOption) => {
    if (
      !window.confirm(
        `确认删除“${provider.name}”吗？正在被三网优化或动态 DNS 使用的配置不会被删除。`,
      )
    )
      return;
    const response = await deleteDynamicDnsProvider(provider.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("线路 DNS 配置已删除");
    void loadData();
  };

  if (loading)
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <Spinner label="加载域名管理" />
      </div>
    );

  return (
    <PageShell className="space-y-6" width="wide">
      <AccessResourceTabs />
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">域名管理</h1>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            startContent={<Plus size={18} />}
            variant="flat"
            onPress={openCreate}
          >
            添加 Cloudflare
          </Button>
          <Button
            color="primary"
            startContent={<Plus size={18} />}
            onPress={openCarrierCreate}
          >
            添加线路 DNS
          </Button>
        </div>
      </header>

      <section
        aria-label="DNS 概况"
        className="grid grid-cols-2 gap-3 xl:grid-cols-4"
      >
        <StatsCard
          color="primary"
          icon={<CloudCog size={20} />}
          title="DNS 服务商"
          value={summary.accounts + carrierProviders.length}
        />
        <StatsCard
          color="success"
          icon={<Globe2 size={20} />}
          title="可用 Zone"
          value={summary.zones}
        />
        <StatsCard
          color="secondary"
          icon={<CheckCircle2 size={20} />}
          title="受管记录"
          value={summary.records}
        />
        <StatsCard
          color={summary.errors ? "danger" : "default"}
          icon={<AlertTriangle size={20} />}
          title="同步异常"
          value={summary.errors}
        />
      </section>

      <section>
        <div className="mb-3 flex items-end justify-between">
          <div>
            <h2 className="text-base font-semibold">Cloudflare 托管</h2>
            <p className="mt-1 text-xs text-default-500">
              Zone、HTTPS 与入口容灾
            </p>
          </div>
          <Chip size="sm" variant="flat">
            Cloudflare
          </Chip>
        </div>
        {accounts.length === 0 ? (
          <div className="flex min-h-48 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-divider text-default-500">
            <KeyRound className="h-8 w-8" />
            <span className="text-sm">尚未添加 Cloudflare 配置</span>
          </div>
        ) : (
          <ResourceCardGrid>
            {accounts.map((account) => {
              const activeZones = (zonesByAccount.get(account.id) || []).filter(
                (zone) => zone.status === "active",
              );

              return (
                <ResourceCard
                  key={account.id}
                  actions={[
                    {
                      label: "同步",
                      icon: <RefreshCw size={14} />,
                      color: "primary",
                      isLoading: syncingId === account.id,
                      onPress: () => void sync(account),
                    },
                    {
                      label: "编辑",
                      icon: <Pencil size={14} />,
                      onPress: () => openEdit(account),
                    },
                    {
                      label: "删除",
                      icon: <Trash2 size={14} />,
                      color: "danger",
                      onPress: () => void remove(account),
                    },
                  ]}
                  chips={
                    activeZones.length > 0
                      ? activeZones.map((zone) => ({
                          text: zone.zoneName,
                          color: "primary" as const,
                        }))
                      : [{ text: "没有可用 Zone" }]
                  }
                  danger={Boolean(account.lastError)}
                  facts={[
                    { label: "可用 Zone", value: activeZones.length },
                    { label: "最近同步", value: timeText(account.lastSyncAt) },
                  ]}
                  notice={
                    account.lastError
                      ? {
                          icon: <AlertTriangle size={14} />,
                          label: "最近错误",
                          value: (
                            <span title={account.lastError}>
                              {account.lastError}
                            </span>
                          ),
                          tone: "danger",
                        }
                      : undefined
                  }
                  status={
                    truthy(account.enabled)
                      ? { text: "启用", color: "success" }
                      : { text: "停用", color: "default" }
                  }
                  subtitles={["Cloudflare"]}
                  title={account.name}
                />
              );
            })}
          </ResourceCardGrid>
        )}
      </section>

      <section>
        <div className="mb-3 flex items-end justify-between">
          <div>
            <h2 className="text-base font-semibold">运营商线路 DNS</h2>
            <p className="mt-1 text-xs text-default-500">三网优化与动态 DNS</p>
          </div>
          <div className="flex gap-2">
            <Chip size="sm" variant="flat">
              DNSPod
            </Chip>
            <Chip size="sm" variant="flat">
              阿里云 DNS
            </Chip>
          </div>
        </div>
        {carrierProviders.length === 0 ? (
          <div className="flex min-h-40 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-divider text-default-500">
            <Globe2 className="h-8 w-8" />
            <span className="text-sm">尚未添加线路 DNS 配置</span>
            <Button
              color="primary"
              size="sm"
              startContent={<Plus size={15} />}
              variant="flat"
              onPress={openCarrierCreate}
            >
              添加配置
            </Button>
          </div>
        ) : (
          <ResourceCardGrid>
            {carrierProviders.map((provider) => (
              <ResourceCard
                key={provider.id}
                actions={[
                  {
                    label: "编辑",
                    icon: <Pencil size={14} />,
                    onPress: () => openCarrierEdit(provider),
                  },
                  {
                    label: "删除",
                    icon: <Trash2 size={14} />,
                    color: "danger",
                    onPress: () => void removeCarrier(provider),
                  },
                ]}
                chips={[
                  {
                    text:
                      provider.provider === "dnspod" ? "DNSPod" : "阿里云 DNS",
                    color: "primary",
                  },
                ]}
                danger={Boolean(provider.lastError)}
                notice={
                  provider.lastError
                    ? {
                        icon: <AlertTriangle size={14} />,
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
                  truthy(provider.enabled)
                    ? { text: "启用", color: "success" }
                    : { text: "停用", color: "default" }
                }
                subtitles={["密钥已加密保存，页面不会回显"]}
                title={provider.name}
              />
            ))}
          </ResourceCardGrid>
        )}
      </section>

      <section>
        <div className="mb-3">
          <h2 className="text-base font-semibold">面板受管记录</h2>
          <p className="mt-1 text-xs text-default-500">
            由入口容灾自动创建或接管的 DNS-only 记录
          </p>
        </div>
        {records.length === 0 ? (
          <div className="flex min-h-40 items-center justify-center rounded-xl border border-dashed border-divider text-sm text-default-500">
            暂无受管 DNS 记录
          </div>
        ) : (
          <div className="overflow-x-auto border-y border-divider">
            <div className="grid min-w-[820px] grid-cols-[minmax(220px,1.5fr)_90px_minmax(160px,1fr)_100px_minmax(180px,1fr)_150px] gap-4 border-b border-divider bg-default-100/60 px-4 py-3 text-xs font-medium text-default-500">
              <span>域名</span>
              <span>类型</span>
              <span>当前入口</span>
              <span>TTL</span>
              <span>用途</span>
              <span>更新时间</span>
            </div>
            {records.map((record) => (
              <div
                key={record.id}
                className="grid min-w-[820px] grid-cols-[minmax(220px,1.5fr)_90px_minmax(160px,1fr)_100px_minmax(180px,1fr)_150px] items-center gap-4 border-b border-divider px-4 py-3 text-sm last:border-0"
              >
                <span className="truncate font-medium">{record.fqdn}</span>
                <Chip size="sm" variant="flat">
                  {record.recordType}
                </Chip>
                <span className="truncate font-mono text-xs">
                  {record.content}
                </span>
                <span>{record.ttl} 秒</span>
                <span className="truncate">
                  {record.ownerName
                    ? `入口容灾 · ${record.ownerName}`
                    : "未绑定"}
                </span>
                <span className="text-xs text-default-500">
                  {timeText(record.updatedTime)}
                </span>
              </div>
            ))}
          </div>
        )}
      </section>

      <Modal isOpen={formOpen} size="lg" onOpenChange={setFormOpen}>
        <ModalContent>
          <ModalHeader>
            {form.id ? "编辑 Cloudflare 配置" : "添加 Cloudflare 配置"}
          </ModalHeader>
          <ModalBody className="gap-4">
            <Input
              autoComplete="off"
              label="配置名称"
              name="cloudflare-config-name"
              placeholder="例如：主域名账号"
              value={form.name}
              onValueChange={(name) => setForm({ ...form, name })}
            />
            <Input
              autoComplete="new-password"
              data-1p-ignore="true"
              data-lpignore="true"
              label={
                form.id
                  ? "Cloudflare API Token（留空保持不变）"
                  : "Cloudflare API Token"
              }
              name="cloudflare-api-token"
              type="password"
              value={form.apiToken}
              onValueChange={(apiToken) => setForm({ ...form, apiToken })}
            />
            <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">
              Token 需要 Zone Read 与 DNS Edit 权限。保存时会验证
              Token，并自动读取其授权范围内的 Zone。
            </div>
            <Switch
              isSelected={form.enabled}
              onValueChange={(enabled) => setForm({ ...form, enabled })}
            >
              启用该配置
            </Switch>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setFormOpen(false)}>
              取消
            </Button>
            <Button color="primary" isLoading={saving} onPress={submit}>
              验证并同步 Zone
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={carrierFormOpen}
        size="lg"
        onOpenChange={setCarrierFormOpen}
      >
        <ModalContent>
          <ModalHeader>
            {carrierForm.id ? "编辑线路 DNS 配置" : "添加线路 DNS 配置"}
          </ModalHeader>
          <ModalBody className="gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Input
                autoComplete="off"
                label="配置名称"
                name="carrier-dns-config-name"
                placeholder="例如：主域名线路解析"
                value={carrierForm.name}
                onValueChange={(name) =>
                  setCarrierForm({ ...carrierForm, name })
                }
              />
              <Select
                label="DNS 服务商"
                selectedKeys={[carrierForm.provider]}
                onSelectionChange={(keys) =>
                  setCarrierForm({
                    ...carrierForm,
                    provider: String(Array.from(keys)[0] || "dnspod") as
                      "dnspod" | "aliyun",
                  })
                }
              >
                <SelectItem key="dnspod">DNSPod</SelectItem>
                <SelectItem key="aliyun">阿里云 DNS</SelectItem>
              </Select>
            </div>
            <Input
              autoComplete="new-password"
              data-1p-ignore="true"
              data-lpignore="true"
              label={
                carrierForm.provider === "dnspod"
                  ? "腾讯云 SecretId"
                  : "阿里云 AccessKey ID"
              }
              name="carrier-dns-credential-id"
              placeholder={carrierForm.id ? "已保存，留空保持不变" : ""}
              type="password"
              value={carrierForm.credentialA}
              onValueChange={(credentialA) =>
                setCarrierForm({ ...carrierForm, credentialA })
              }
            />
            <Input
              autoComplete="new-password"
              data-1p-ignore="true"
              data-lpignore="true"
              label={
                carrierForm.provider === "dnspod"
                  ? "腾讯云 SecretKey"
                  : "阿里云 AccessKey Secret"
              }
              name="carrier-dns-credential-secret"
              placeholder={carrierForm.id ? "已保存，留空保持不变" : ""}
              type="password"
              value={carrierForm.credentialB}
              onValueChange={(credentialB) =>
                setCarrierForm({ ...carrierForm, credentialB })
              }
            />
            <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">
              密钥需要对应域名的 DNS
              记录读取和修改权限，保存后仅以加密形式存储。
            </div>
            <Switch
              isSelected={carrierForm.enabled}
              onValueChange={(enabled) =>
                setCarrierForm({ ...carrierForm, enabled })
              }
            >
              启用该配置
            </Switch>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setCarrierFormOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={carrierSaving}
              onPress={submitCarrier}
            >
              保存配置
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
