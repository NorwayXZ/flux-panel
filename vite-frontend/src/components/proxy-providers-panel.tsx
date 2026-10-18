import { useEffect, useState } from "react";
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
import { Radio, RadioGroup } from "@heroui/radio";
import { Select, SelectItem } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import {
  Pencil,
  Plus,
  RefreshCw,
  Shuffle,
  ShieldCheck,
  Smartphone,
  Trash2,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  createProviderExit,
  deleteProxyExit,
  deleteProxyProvider,
  getProviderCarriers,
  getProviderCountries,
  getProxyExits,
  getProxyProviders,
  refreshProviderUsage,
  rotateProviderExit,
  saveProxyProvider,
  testProxyExit,
  type ProxyExit,
  type ProxyProvider,
  type ProxyProviderKind,
} from "@/api";
import { formatDateTime } from "@/utils/format";
import { regionName } from "@/utils/region-name";

const kindLabel: Record<ProxyProviderKind, string> = {
  soax: "SOAX",
  oxylabs: "Oxylabs",
  custom: "自定义",
};

const COMMON_COUNTRIES = [
  "US",
  "GB",
  "DE",
  "FR",
  "JP",
  "KR",
  "HK",
  "TW",
  "SG",
  "CA",
  "AU",
  "NL",
  "IT",
  "ES",
  "BR",
  "IN",
  "ID",
  "TH",
  "VN",
  "PH",
  "MY",
  "TR",
  "MX",
  "SE",
  "CH",
  "PL",
  "AE",
  "AR",
  "UA",
  "RU",
];

const STICKY = [
  { key: "0", label: "尽量一直保持同一个 IP" },
  { key: "5", label: "每 5 分钟换一次" },
  { key: "10", label: "每 10 分钟换一次" },
  { key: "30", label: "每 30 分钟换一次" },
  { key: "60", label: "每 1 小时换一次" },
  { key: "180", label: "每 3 小时换一次" },
  { key: "1440", label: "每天换一次" },
];

const bytes = (value?: number | null) =>
  value == null
    ? "-"
    : value >= 1024 ** 3
      ? `${(value / 1024 ** 3).toFixed(2)} GB`
      : `${(value / 1024 ** 2).toFixed(1)} MB`;

type ProviderForm = {
  id?: number;
  name: string;
  kind: ProxyProviderKind;
  network: "mobile" | "residential";
  stickyMinutes: string;
  settings: Record<string, string>;
};

const emptyForm: ProviderForm = {
  name: "",
  kind: "soax",
  network: "mobile",
  stickyMinutes: "10",
  settings: { protocol: "http" },
};

/** Commercial mobile and residential IP providers and the exits made from them. */
export default function ProxyProvidersPanel() {
  const [providers, setProviders] = useState<ProxyProvider[]>([]);
  const [exits, setExits] = useState<ProxyExit[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [form, setForm] = useState<ProviderForm | null>(null);
  const [exitTarget, setExitTarget] = useState<ProxyProvider | null>(null);
  const [countries, setCountries] = useState<Array<{
    code: string;
    name: string;
  }> | null>(null);
  const [carriers, setCarriers] = useState<Array<{
    code: string;
    name: string;
  }> | null>(null);
  const [exitCountry, setExitCountry] = useState("US");
  const [exitCarrier, setExitCarrier] = useState("");
  const [exitName, setExitName] = useState("");

  const load = async () => {
    const [list, exitList] = await Promise.all([
      getProxyProviders(),
      getProxyExits(),
    ]);

    if (list.code === 0) setProviders(list.data || []);
    else toast.error(list.msg || "读取服务商失败");
    if (exitList.code === 0 && exitList.data)
      setExits((exitList.data.exits || []).filter((exit) => exit.provider));
    setLoading(false);
  };

  useEffect(() => {
    void load();
  }, []);

  // Carriers depend on the chosen country (SOAX only).
  useEffect(() => {
    setCarriers(null);
    setExitCarrier("");
    if (
      !exitTarget ||
      exitTarget.kind !== "soax" ||
      !/^[A-Z]{2}$/.test(exitCountry)
    )
      return;
    void getProviderCarriers(exitTarget.id, exitCountry).then((response) => {
      if (response.code === 0) setCarriers(response.data);
    });
  }, [exitTarget, exitCountry]);

  const openForm = (provider?: ProxyProvider) =>
    setForm(
      provider
        ? {
            id: provider.id,
            name: provider.name,
            kind: provider.kind,
            network: provider.network,
            stickyMinutes: String(provider.stickyMinutes),
            settings: Object.fromEntries(
              Object.entries(provider.settings).map(([key, value]) => [
                key,
                value == null ? "" : String(value),
              ]),
            ),
          }
        : { ...emptyForm, settings: { ...emptyForm.settings } },
    );

  const setting = (key: string, value: string) =>
    form && setForm({ ...form, settings: { ...form.settings, [key]: value } });

  const save = async () => {
    if (!form) return;
    setBusy("save");
    const response = await saveProxyProvider({
      id: form.id,
      name: form.name.trim(),
      kind: form.kind,
      network: form.network,
      stickyMinutes: Number(form.stickyMinutes),
      settings: form.settings,
    });

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    toast.success("已保存");
    setForm(null);
    void load();
  };

  const remove = async (provider: ProxyProvider) => {
    if (!window.confirm(`删除服务商“${provider.name}”吗？`)) return;
    const response = await deleteProxyProvider(provider.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    void load();
  };

  const usage = async (provider: ProxyProvider) => {
    setBusy(`usage-${provider.id}`);
    const response = await refreshProviderUsage(provider.id);

    setBusy("");
    if (response.code !== 0) toast.error(response.msg || "读取用量失败");
    void load();
  };

  const openExit = async (provider: ProxyProvider) => {
    setExitTarget(provider);
    setExitCountry("US");
    setExitName("");
    setCountries(null);
    const response = await getProviderCountries(provider.id);

    if (response.code === 0) setCountries(response.data);
    else toast.error(response.msg || "读取国家列表失败，可手动填写国家代码");
  };

  const addExit = async () => {
    if (!exitTarget) return;
    setBusy("exit");
    const response = await createProviderExit({
      providerId: exitTarget.id,
      country: exitCountry.trim().toUpperCase(),
      carrier: exitCarrier.trim() || undefined,
      name: exitName.trim() || undefined,
    });

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "添加失败");
    toast.success("已添加，私人代理的出口列表里可以选它了");
    setExitTarget(null);
    void load();
  };

  const test = async (exit: ProxyExit) => {
    setBusy(`test-${exit.id}`);
    const response = await testProxyExit(exit.id);

    setBusy("");
    if (response.code !== 0) toast.error(response.msg || "自检失败");
    else
      toast.success(
        `可用：${response.data.ip}${response.data.country ? `（${response.data.country}）` : ""}，${response.data.latencyMs} ms`,
      );
    void load();
  };

  const rotate = async (exit: ProxyExit) => {
    setBusy(`rotate-${exit.id}`);
    const response = await rotateProviderExit(exit.id);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "换 IP 失败");
    toast.success(
      response.data.reapplied > 0
        ? `已换 IP，${response.data.reapplied} 个代理已重新下发`
        : "已换 IP，下次使用时生效",
    );
    void load();
  };

  const removeExit = async (exit: ProxyExit) => {
    if (!window.confirm(`删除出口“${exit.name}”吗？`)) return;
    const response = await deleteProxyExit(exit.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    void load();
  };

  if (loading)
    return (
      <div className="flex min-h-40 items-center justify-center">
        <Spinner />
      </div>
    );

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <p className="max-w-3xl text-sm text-default-500">
          接入 SOAX、Oxylabs 等商业服务商的手机（4G/5G）和家宽
          IP。先在服务商官网注册并购买套餐，再把账号填到这里，然后按国家（SOAX
          还可以按运营商）添加出口；私人代理的“出口与分流”里就能选“服务商 ·
          …”。费用按服务商的套餐计算，面板不经手付款。一般只支持 TCP。
        </p>
        <Button
          color="primary"
          startContent={<Plus size={16} />}
          onPress={() => openForm()}
        >
          添加服务商
        </Button>
      </div>

      {providers.length === 0 ? (
        <div className="flex min-h-40 flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-divider px-6 text-center text-sm text-default-500">
          <Smartphone size={26} />
          还没有服务商。点“添加服务商”，选 SOAX、Oxylabs 或自定义模板。
        </div>
      ) : (
        <div className="grid gap-3 xl:grid-cols-2">
          {providers.map((provider) => {
            const providerExits = exits.filter(
              (exit) => exit.provider?.providerId === provider.id,
            );
            const sticky = STICKY.find(
              (item) => item.key === String(provider.stickyMinutes),
            );

            return (
              <div
                key={provider.id}
                className="space-y-3 rounded-2xl border border-divider bg-content1 p-4"
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold text-foreground">
                      {provider.name}
                    </span>
                    <Chip size="sm" variant="flat">
                      {kindLabel[provider.kind]}
                    </Chip>
                    <Chip
                      color={
                        provider.network === "mobile" ? "secondary" : "primary"
                      }
                      size="sm"
                      variant="flat"
                    >
                      {provider.network === "mobile" ? "手机 4G/5G" : "家宽"}
                    </Chip>
                  </div>
                  <div className="flex gap-1">
                    <Button
                      color="primary"
                      size="sm"
                      startContent={<Plus size={14} />}
                      variant="flat"
                      onPress={() => void openExit(provider)}
                    >
                      添加出口
                    </Button>
                    <Button
                      isIconOnly
                      aria-label="编辑"
                      size="sm"
                      variant="light"
                      onPress={() => openForm(provider)}
                    >
                      <Pencil size={14} />
                    </Button>
                    <Button
                      isIconOnly
                      aria-label="删除"
                      color="danger"
                      size="sm"
                      variant="light"
                      onPress={() => void remove(provider)}
                    >
                      <Trash2 size={14} />
                    </Button>
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-default-500">
                  <span>
                    IP：
                    {sticky?.label || `每 ${provider.stickyMinutes} 分钟换一次`}
                  </span>
                  {provider.usageSupported ? (
                    <span className="flex items-center gap-1">
                      已用流量：
                      <span className="font-semibold text-foreground">
                        {bytes(provider.usageUsedBytes)}
                      </span>
                      {provider.usageLimitBytes
                        ? ` / ${bytes(provider.usageLimitBytes)}`
                        : provider.kind === "oxylabs"
                          ? "（本月）"
                          : ""}
                      {provider.usageAt
                        ? ` · ${formatDateTime(provider.usageAt)}`
                        : ""}
                      <Button
                        isIconOnly
                        aria-label="刷新用量"
                        className="h-6 w-6 min-w-6"
                        isLoading={busy === `usage-${provider.id}`}
                        size="sm"
                        variant="light"
                        onPress={() => void usage(provider)}
                      >
                        <RefreshCw size={12} />
                      </Button>
                    </span>
                  ) : (
                    <span>用量请在服务商后台查看</span>
                  )}
                </div>
                {provider.usageError && (
                  <p className="text-xs text-danger">{provider.usageError}</p>
                )}
                {providerExits.length === 0 ? (
                  <p className="text-xs text-default-500">
                    还没有出口。点“添加出口”选国家
                    {provider.kind === "soax" ? "和运营商" : ""}。
                  </p>
                ) : (
                  <div className="divide-y divide-divider rounded-xl border border-divider">
                    {providerExits.map((exit) => (
                      <div
                        key={exit.id}
                        className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm"
                      >
                        <div className="min-w-0 flex-1">
                          <div className="truncate font-medium">
                            {exit.name}
                          </div>
                          <div
                            className={`truncate text-xs ${exit.lastTestOk === false ? "text-danger" : "text-default-500"}`}
                            title={exit.lastTestError || undefined}
                          >
                            {exit.lastTestOk === false
                              ? `自检失败：${exit.lastTestError || ""}`
                              : exit.lastTestIp
                                ? `${exit.lastTestIp}${exit.lastTestCountry ? ` · ${exit.lastTestCountry}` : ""} · ${exit.lastTestLatency} ms`
                                : "未自检"}
                            {exit.usedBy ? ` · ${exit.usedBy} 个代理在用` : ""}
                          </div>
                        </div>
                        <div className="flex gap-1">
                          <Button
                            isLoading={busy === `test-${exit.id}`}
                            size="sm"
                            startContent={
                              busy === `test-${exit.id}` ? null : (
                                <ShieldCheck size={13} />
                              )
                            }
                            variant="flat"
                            onPress={() => void test(exit)}
                          >
                            自检
                          </Button>
                          <Button
                            isLoading={busy === `rotate-${exit.id}`}
                            size="sm"
                            startContent={
                              busy === `rotate-${exit.id}` ? null : (
                                <Shuffle size={13} />
                              )
                            }
                            variant="flat"
                            onPress={() => void rotate(exit)}
                          >
                            换 IP
                          </Button>
                          <Button
                            isIconOnly
                            aria-label="删除出口"
                            color="danger"
                            size="sm"
                            variant="light"
                            onPress={() => void removeExit(exit)}
                          >
                            <Trash2 size={13} />
                          </Button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      <Modal
        isOpen={Boolean(form)}
        scrollBehavior="inside"
        size="xl"
        onOpenChange={(open) => !open && setForm(null)}
      >
        <ModalContent>
          <ModalHeader>{form?.id ? "编辑服务商" : "添加服务商"}</ModalHeader>
          {form && (
            <ModalBody className="space-y-3">
              <RadioGroup
                isDisabled={Boolean(form.id)}
                label="服务商"
                orientation="horizontal"
                size="sm"
                value={form.kind}
                onValueChange={(kind) =>
                  setForm({
                    ...form,
                    kind: kind as ProxyProviderKind,
                    name: form.name || kindLabel[kind as ProxyProviderKind],
                  })
                }
              >
                <Radio value="soax">SOAX</Radio>
                <Radio value="oxylabs">Oxylabs</Radio>
                <Radio value="custom">自定义模板</Radio>
              </RadioGroup>
              <div className="grid gap-3 sm:grid-cols-2">
                <Input
                  label="名称"
                  placeholder="例如 SOAX 手机"
                  size="sm"
                  value={form.name}
                  onValueChange={(name) => setForm({ ...form, name })}
                />
                <Select
                  label="IP 类型"
                  selectedKeys={[form.network]}
                  size="sm"
                  onSelectionChange={(keys) =>
                    setForm({
                      ...form,
                      network: String(Array.from(keys)[0]) as
                        "mobile" | "residential",
                    })
                  }
                >
                  <SelectItem key="mobile">手机 4G/5G</SelectItem>
                  <SelectItem key="residential">家宽</SelectItem>
                </Select>
              </div>
              <Select
                description="同一个出口在这段时间内用同一个 IP，到时自动换；随时可以点“换 IP”手动换"
                label="IP 保持多久"
                selectedKeys={[form.stickyMinutes]}
                size="sm"
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    stickyMinutes: String(Array.from(keys)[0] || "10"),
                  })
                }
              >
                {STICKY.map((item) => (
                  <SelectItem key={item.key}>{item.label}</SelectItem>
                ))}
              </Select>

              {form.kind === "soax" && (
                <>
                  <Input
                    description="SOAX 后台套餐（Package）里的 Package key，即代理密码"
                    label="Package key"
                    size="sm"
                    value={form.settings.packageKey || ""}
                    onValueChange={(value) => setting("packageKey", value)}
                  />
                  <div className="grid gap-3 sm:grid-cols-2">
                    <Input
                      description="选填：用于读取国家、运营商列表和已用流量"
                      label="Package ID"
                      size="sm"
                      value={form.settings.packageId || ""}
                      onValueChange={(value) => setting("packageId", value)}
                    />
                    <Input
                      description="选填：Settings → API keys，需要 proxy:packages:read 权限"
                      label="API key"
                      size="sm"
                      value={form.settings.apiKey || ""}
                      onValueChange={(value) => setting("apiKey", value)}
                    />
                  </div>
                </>
              )}
              {form.kind === "oxylabs" && (
                <div className="grid gap-3 sm:grid-cols-2">
                  <Input
                    description="代理用户名（不带 customer- 前缀）；读取用量需要主用户"
                    label="用户名"
                    size="sm"
                    value={form.settings.username || ""}
                    onValueChange={(value) => setting("username", value)}
                  />
                  <Input
                    label="密码"
                    size="sm"
                    type="password"
                    value={form.settings.password || ""}
                    onValueChange={(value) => setting("password", value)}
                  />
                </div>
              )}
              {form.kind === "custom" && (
                <>
                  <div className="grid gap-3 sm:grid-cols-3">
                    <Input
                      className="sm:col-span-2"
                      label="网关地址"
                      placeholder="gate.example.com"
                      size="sm"
                      value={form.settings.host || ""}
                      onValueChange={(value) => setting("host", value)}
                    />
                    <Input
                      label="端口"
                      size="sm"
                      type="number"
                      value={form.settings.port || ""}
                      onValueChange={(value) => setting("port", value)}
                    />
                  </div>
                  <Select
                    label="协议"
                    selectedKeys={[form.settings.protocol || "http"]}
                    size="sm"
                    onSelectionChange={(keys) =>
                      setting("protocol", String(Array.from(keys)[0] || "http"))
                    }
                  >
                    <SelectItem key="http">HTTP</SelectItem>
                    <SelectItem key="socks5">SOCKS5</SelectItem>
                  </Select>
                  <Input
                    label="用户名模板"
                    placeholder="user-xxx-country-{country}-session-{session}"
                    size="sm"
                    value={form.settings.usernameTemplate || ""}
                    onValueChange={(value) =>
                      setting("usernameTemplate", value)
                    }
                  />
                  <Input
                    label="密码模板"
                    placeholder="password_country-{country}_session-{session}_lifetime-{minutes}m"
                    size="sm"
                    value={form.settings.passwordTemplate || ""}
                    onValueChange={(value) =>
                      setting("passwordTemplate", value)
                    }
                  />
                  <p className="text-xs text-default-500">
                    按服务商文档填写，可用占位符：
                    {"{country}"}（小写国家代码）、{"{COUNTRY}"}（大写）、
                    {"{carrier}"}（运营商）、{"{session}"}（会话 ID，换 IP
                    时会变）、{"{minutes}"} / {"{seconds}"}（保持时长）。例如
                    IPRoyal 的密码模板：你的密码_country-{"{country}"}
                    _session-{"{session}"}_lifetime-{"{minutes}"}m。
                  </p>
                </>
              )}
              {form.id && (
                <p className="text-xs text-default-400">
                  密钥类字段显示为
                  ****，不修改就保持原值。保存后用到这个服务商的代理会重新下发。
                </p>
              )}
            </ModalBody>
          )}
          <ModalFooter>
            <Button variant="light" onPress={() => setForm(null)}>
              取消
            </Button>
            <Button color="primary" isLoading={busy === "save"} onPress={save}>
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={Boolean(exitTarget)}
        size="lg"
        onOpenChange={(open) => !open && setExitTarget(null)}
      >
        <ModalContent>
          <ModalHeader>为“{exitTarget?.name}”添加出口</ModalHeader>
          <ModalBody className="space-y-3">
            {countries && countries.length > 0 ? (
              <Select
                label="国家"
                selectedKeys={[exitCountry]}
                size="sm"
                onSelectionChange={(keys) =>
                  setExitCountry(
                    String(Array.from(keys)[0] || "US").toUpperCase(),
                  )
                }
              >
                {countries.map((country) => (
                  <SelectItem key={country.code.toUpperCase()}>
                    {`${regionName(country.code)}（${country.code.toUpperCase()}）`}
                  </SelectItem>
                ))}
              </Select>
            ) : (
              <div className="space-y-2">
                <Select
                  label="国家"
                  selectedKeys={
                    COMMON_COUNTRIES.includes(exitCountry) ? [exitCountry] : []
                  }
                  size="sm"
                  onSelectionChange={(keys) =>
                    setExitCountry(String(Array.from(keys)[0] || "US"))
                  }
                >
                  {COMMON_COUNTRIES.map((code) => (
                    <SelectItem
                      key={code}
                    >{`${regionName(code)}（${code}）`}</SelectItem>
                  ))}
                </Select>
                <Input
                  description="不在列表里的国家，直接填两位国家代码"
                  label="或填写国家代码"
                  size="sm"
                  value={exitCountry}
                  onValueChange={(value) =>
                    setExitCountry(value.toUpperCase().slice(0, 2))
                  }
                />
              </div>
            )}
            {exitTarget?.kind === "soax" && carriers && carriers.length > 0 ? (
              <Select
                description="不选则使用该国家所有运营商"
                label="运营商（选填）"
                selectedKeys={exitCarrier ? [exitCarrier] : []}
                size="sm"
                onSelectionChange={(keys) =>
                  setExitCarrier(String(Array.from(keys)[0] || ""))
                }
              >
                {carriers.map((carrier) => (
                  <SelectItem key={carrier.code}>{carrier.name}</SelectItem>
                ))}
              </Select>
            ) : exitTarget && exitTarget.kind !== "oxylabs" ? (
              <Input
                description={
                  exitTarget.kind === "soax"
                    ? "选填，例如 t_mobile、verizon_wireless；填写 Package ID 和 API key 后可直接从列表选"
                    : "选填，会填入模板里的 {carrier}"
                }
                label="运营商代码"
                size="sm"
                value={exitCarrier}
                onValueChange={setExitCarrier}
              />
            ) : null}
            <Input
              description="不填自动生成，例如“SOAX 手机 · 手机 · 美国 · t_mobile”"
              label="出口名称（选填）"
              size="sm"
              value={exitName}
              onValueChange={setExitName}
            />
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setExitTarget(null)}>
              取消
            </Button>
            <Button
              color="primary"
              isDisabled={!/^[A-Z]{2}$/.test(exitCountry)}
              isLoading={busy === "exit"}
              onPress={addExit}
            >
              添加
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
