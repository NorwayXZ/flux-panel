import { useCallback, useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Card, CardBody } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import {
  Router,
  Plus,
  RefreshCw,
  Settings2,
  Download,
  Trash2,
  Copy,
} from "lucide-react";
import toast from "react-hot-toast";
import {
  createInternalConnector,
  getInternalConnectorInstall,
  getOpenWrtDnsResolvers,
  getOpenWrtDnsOptions,
  configureOpenWrtDns,
  removeOpenWrtDns,
  refreshSourceIpCarriers,
  deleteInternalConnector,
  type OpenWrtDnsResolver,
} from "@/api";

const carrierName = (value?: string) =>
  ({ telecom: "电信", unicom: "联通", mobile: "移动", default: "默认入口" })[
    value || "default"
  ] || "未识别";
const sourceName = (value?: string) =>
  ({
    interface: "WAN 映射",
    "public-ip-database": "公网 IP 地址库",
    default: "默认回退",
  })[value || "default"] || "等待识别";
type GroupOption = {
  id: number;
  name: string;
  domain: string;
  recordType: string;
  state: string;
  routeCount: number;
};
type Mapping = { iface: string; carrier: string };

export default function OpenWrtDnsPage() {
  const [resolvers, setResolvers] = useState<OpenWrtDnsResolver[]>([]);
  const [groups, setGroups] = useState<GroupOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<OpenWrtDnsResolver | null>(null);
  const [selectedGroups, setSelectedGroups] = useState<string[]>([]);
  const [mappings, setMappings] = useState<Mapping[]>([]);
  const [command, setCommand] = useState("");
  const [databaseReady, setDatabaseReady] = useState(0);
  const [refreshingDatabase, setRefreshingDatabase] = useState(false);
  const load = useCallback(async () => {
    const [list, options] = await Promise.all([
      getOpenWrtDnsResolvers(),
      getOpenWrtDnsOptions(),
    ]);
    if (list.code === 0) {
      setResolvers(list.data || []);
      setError("");
    } else setError(list.msg || "加载失败");
    if (options.code === 0) {
      setGroups(options.data?.groups || []);
      setDatabaseReady(
        options.data?.carrierDatabase?.filter((item) => item.cidrCount > 0)
          .length || 0,
      );
    }
    setLoading(false);
  }, []);
  useEffect(() => {
    void load();
    const timer = setInterval(() => {
      if (!document.hidden) void load();
    }, 10000);
    return () => clearInterval(timer);
  }, [load]);
  const create = async () => {
    if (!name.trim()) return toast.error("请输入路由器名称");
    setSaving(true);
    const result = await createInternalConnector({
      name: name.trim(),
      platform: "openwrt",
      connectorRole: "openwrt_dns",
    });
    setSaving(false);
    if (result.code !== 0) return toast.error(result.msg);
    setCreateOpen(false);
    setName("");
    setCommand(result.data.installCommand);
    toast.success("OpenWrt Agent 已创建");
    void load();
  };
  const edit = (resolver: OpenWrtDnsResolver) => {
    setEditing(resolver);
    setSelectedGroups((resolver.smartEntryGroupIds || []).map(String));
    setMappings(
      Object.entries(resolver.interfaceCarriers || {}).map(
        ([iface, carrier]) => ({ iface, carrier }),
      ),
    );
  };
  const save = async () => {
    if (!editing) return;
    const interfaces: Record<string, string> = {};
    for (const mapping of mappings) {
      if (!mapping.iface.trim()) return toast.error("请填写 WAN 设备名称");
      if (interfaces[mapping.iface.trim()])
        return toast.error("WAN 设备名称重复");
      interfaces[mapping.iface.trim()] = mapping.carrier;
    }
    setSaving(true);
    const result = await configureOpenWrtDns({
      connectorId: editing.connectorId,
      interfaceCarriers: interfaces,
      smartEntryGroupIds: selectedGroups.map(Number),
    });
    setSaving(false);
    if (result.code !== 0) return toast.error(result.msg);
    toast.success(result.data?.message || result.msg);
    setEditing(null);
    void load();
  };
  const install = async (resolver: OpenWrtDnsResolver, uninstall = false) => {
    const result = await getInternalConnectorInstall(
      resolver.connectorId,
      "openwrt",
      uninstall ? "uninstall" : "install",
    );
    if (result.code !== 0) return toast.error(result.msg);
    setCommand(result.data);
  };
  const clear = async (resolver: OpenWrtDnsResolver) => {
    if (!window.confirm(`移除“${resolver.name}”的本地 DNS 策略？`)) return;
    const result = await removeOpenWrtDns(resolver.connectorId);
    if (result.code !== 0) return toast.error(result.msg);
    toast.success(result.data || result.msg);
    void load();
  };
  const refreshDatabase = async () => {
    setRefreshingDatabase(true);
    const result = await refreshSourceIpCarriers();
    setRefreshingDatabase(false);
    if (result.code !== 0) return toast.error(result.msg);
    if (result.data?.updated === 0)
      toast.error(
        "本次未成功下载新地址库，请检查来源 IP 分流页面的具体错误；仍可使用 WAN 映射",
      );
    else toast.success(`已更新 ${result.data?.updated || 0} 家运营商地址库`);
    void load();
  };
  const deleteRecord = async (resolver: OpenWrtDnsResolver) => {
    if (!window.confirm(`确认已在“${resolver.name}”执行卸载并删除记录？`))
      return;
    const result = await deleteInternalConnector(resolver.connectorId);
    if (result.code !== 0) return toast.error(result.msg);
    toast.success("记录已删除");
    void load();
  };
  return (
    <div className="mx-auto w-full max-w-[1600px] space-y-5 p-4 sm:p-6">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-divider pb-4">
        <div>
          <h1 className="text-2xl font-semibold">OpenWrt DNS 决策</h1>
          <p className="mt-1 text-sm text-default-500">
            按路由器当前 WAN 出口选择三网入口
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            isIconOnly
            aria-label="刷新"
            title="刷新"
            variant="flat"
            isLoading={loading}
            onPress={() => void load()}
          >
            <RefreshCw size={18} />
          </Button>
          <Button
            color="primary"
            startContent={<Plus size={18} />}
            onPress={() => setCreateOpen(true)}
          >
            添加 OpenWrt
          </Button>
        </div>
      </header>
      <details className="rounded-lg border border-divider bg-content1 p-4 text-sm">
        <summary className="cursor-pointer font-medium">安装与使用条件</summary>
        <p className="mt-3 text-default-500">
          创建路由器，复制安装命令到 OpenWrt
          终端执行，再绑定三网策略。客户端需使用这个 OpenWrt 的
          DNS；业务连接直接到入口，DNS Agent 只处理解析。
        </p>
        <p className="mt-2 text-default-500">
          IPv4 与 IPv6 按各自默认出口识别；只有 A 策略时会抑制同域名
          AAAA，避免绕回旧入口。应用自行使用 DoH、VPN 或其他 DNS
          时可能绕过本功能，已有连接需要重连。
        </p>
        <p className="mt-2 text-default-500">
          适用于默认 WAN 出口。多 WAN
          按客户端或目标分流时，应核对实际出口；同一个接口更换运营商时，请更新映射或使用地址库自动识别。
        </p>
      </details>
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-divider bg-content1 p-3 text-sm">
        <span>
          运营商地址库：{databaseReady}/3 可用 · 未配置 WAN 映射时用于自动识别
        </span>
        <Button
          size="sm"
          variant="flat"
          isLoading={refreshingDatabase}
          onPress={() => void refreshDatabase()}
        >
          刷新地址库
        </Button>
      </div>
      {error && (
        <p
          role="alert"
          className="rounded-lg bg-danger-50 p-3 text-sm text-danger"
        >
          {error}
        </p>
      )}
      {!resolvers.length && !loading && (
        <div className="border-y border-divider py-10 text-center text-default-500">
          <Router className="mx-auto mb-3" size={32} />
          暂无 OpenWrt DNS Agent
        </div>
      )}
      <div className="grid gap-4 lg:grid-cols-2 xl:grid-cols-3">
        {resolvers.map((resolver) => (
          <Card
            key={resolver.connectorId}
            radius="sm"
            shadow="none"
            className="border border-divider"
          >
            <CardBody className="gap-3 p-4">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <h2 className="break-words font-semibold">{resolver.name}</h2>
                  <p className="mt-1 text-xs text-default-500">
                    Agent {resolver.version || "未连接"}
                  </p>
                </div>
                <Chip
                  size="sm"
                  color={resolver.online ? "success" : "default"}
                  variant="flat"
                >
                  {resolver.online ? "在线" : "离线"}
                </Chip>
              </div>
              <dl className="grid grid-cols-2 gap-3 border-y border-divider py-3 text-sm">
                <div>
                  <dt className="text-xs text-default-500">IPv4 WAN</dt>
                  <dd className="mt-1 break-all">
                    {resolver.activeInterface || "等待上报"}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-default-500">IPv4 选择</dt>
                  <dd className="mt-1">
                    {carrierName(resolver.activeCarrier)}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-default-500">已绑定策略</dt>
                  <dd className="mt-1">
                    {resolver.smartEntryGroupIds?.length || 0}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-default-500">本地解析次数</dt>
                  <dd className="mt-1">{resolver.resolvedQueries || 0}</dd>
                </div>
              </dl>
              <div className="space-y-1 text-xs text-default-500">
                {resolver.smartEntryGroupIds?.map((id) => {
                  const group = groups.find((item) => item.id === id);
                  return (
                    <p key={id} className="break-all">
                      {group
                        ? `${group.name} · ${group.domain} · ${group.recordType}`
                        : `策略 ${id}（已停用或删除）`}
                    </p>
                  );
                })}
              </div>
              <div className="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <p className="text-xs text-default-500">IPv6 WAN</p>
                  <p className="mt-1 break-all">
                    {resolver.activeInterface6 || "未检测到出口"}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-default-500">IPv6 选择</p>
                  <p className="mt-1">{carrierName(resolver.activeCarrier6)}</p>
                </div>
              </div>
              <p className="break-all text-xs text-default-500">
                IPv4：{resolver.publicIp || "未上报"} ·{" "}
                {sourceName(resolver.detectionSource)}
              </p>
              <p className="break-all text-xs text-default-500">
                IPv6：{resolver.publicIp6 || "未上报"} ·{" "}
                {sourceName(resolver.detectionSource6)}
              </p>
              <p className="text-xs text-default-500">
                配置：
                {resolver.policyRevision
                  ? Number(resolver.appliedRevision || 0) >=
                    resolver.policyRevision
                    ? "已应用"
                    : "等待同步"
                  : "尚未配置"}
              </p>
              <p className="text-xs text-default-500">
                dnsmasq：{resolver.dnsmasqReloaded ? "已加载" : "等待加载"} ·
                上报：
                {resolver.reportedAt
                  ? new Date(resolver.reportedAt).toLocaleString()
                  : "-"}
              </p>
              {resolver.lastError && (
                <p className="rounded-md bg-warning-50 p-2 text-xs text-warning">
                  {resolver.lastError}
                </p>
              )}
              {resolver.detectionError && (
                <p className="rounded-md bg-warning-50 p-2 text-xs text-warning">
                  {resolver.detectionError}
                </p>
              )}
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  startContent={<Settings2 size={15} />}
                  variant="flat"
                  onPress={() => edit(resolver)}
                >
                  配置
                </Button>
                <Button
                  size="sm"
                  startContent={<Download size={15} />}
                  variant="flat"
                  onPress={() => void install(resolver)}
                >
                  安装
                </Button>
                <Button
                  isIconOnly
                  size="sm"
                  aria-label="移除策略"
                  title="移除本地 DNS 策略"
                  color="danger"
                  variant="light"
                  onPress={() => void clear(resolver)}
                >
                  <Trash2 size={16} />
                </Button>
                <Button
                  size="sm"
                  variant="light"
                  onPress={() => void install(resolver, true)}
                >
                  卸载命令
                </Button>
                <Button
                  size="sm"
                  color="danger"
                  variant="light"
                  isDisabled={resolver.online}
                  onPress={() => void deleteRecord(resolver)}
                >
                  删除记录
                </Button>
              </div>
            </CardBody>
          </Card>
        ))}
      </div>
      <Modal isOpen={createOpen} onOpenChange={setCreateOpen}>
        <ModalContent>
          <ModalHeader>添加 OpenWrt Agent</ModalHeader>
          <ModalBody>
            <Input label="路由器名称" value={name} onValueChange={setName} />
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setCreateOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={saving}
              onPress={() => void create()}
            >
              创建
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal
        isOpen={Boolean(editing)}
        onOpenChange={(open) => {
          if (!open) setEditing(null);
        }}
        size="3xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          <ModalHeader>{editing?.name} · DNS 策略</ModalHeader>
          <ModalBody className="gap-4">
            <Select
              label="三网优化策略"
              selectionMode="multiple"
              selectedKeys={selectedGroups}
              onSelectionChange={(keys) =>
                setSelectedGroups(Array.from(keys).map(String))
              }
            >
              {groups.map((group) => (
                <SelectItem
                  key={String(group.id)}
                  textValue={`${group.name} ${group.domain}`}
                >
                  {group.name} · {group.domain} · {group.recordType}
                </SelectItem>
              ))}
            </Select>
            <div className="border-t border-divider pt-3">
              <div className="flex items-center justify-between gap-2">
                <h3 className="text-sm font-semibold">WAN 设备运营商映射</h3>
                <Button
                  size="sm"
                  startContent={<Plus size={15} />}
                  variant="flat"
                  onPress={() =>
                    setMappings([...mappings, { iface: "", carrier: "mobile" }])
                  }
                >
                  添加 WAN
                </Button>
              </div>
              <p className="my-2 text-xs text-default-500">
                使用实际设备名，如 pppoe-wan、eth1；没有映射时使用公网 IP
                地址库识别。
              </p>
              {mappings.map((mapping, index) => (
                <div key={index} className="mb-3 flex items-center gap-2">
                  <Input
                    label="WAN 设备"
                    value={mapping.iface}
                    onValueChange={(iface) =>
                      setMappings(
                        mappings.map((item, i) =>
                          i === index ? { ...item, iface } : item,
                        ),
                      )
                    }
                  />
                  <Select
                    label="运营商"
                    selectedKeys={[mapping.carrier]}
                    onSelectionChange={(keys) =>
                      setMappings(
                        mappings.map((item, i) =>
                          i === index
                            ? { ...item, carrier: String(Array.from(keys)[0]) }
                            : item,
                        ),
                      )
                    }
                  >
                    <SelectItem key="telecom">电信</SelectItem>
                    <SelectItem key="unicom">联通</SelectItem>
                    <SelectItem key="mobile">移动</SelectItem>
                  </Select>
                  <Button
                    isIconOnly
                    aria-label="删除 WAN 映射"
                    title="删除映射"
                    variant="light"
                    onPress={() =>
                      setMappings(mappings.filter((_, i) => i !== index))
                    }
                  >
                    <Trash2 size={16} />
                  </Button>
                </div>
              ))}
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setEditing(null)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={saving}
              onPress={() => void save()}
            >
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal
        isOpen={Boolean(command)}
        onOpenChange={(open) => {
          if (!open) setCommand("");
        }}
        size="3xl"
      >
        <ModalContent>
          <ModalHeader>OpenWrt 终端命令</ModalHeader>
          <ModalBody>
            <pre className="whitespace-pre-wrap break-all rounded-md bg-default-100 p-3 text-xs">
              {command}
            </pre>
          </ModalBody>
          <ModalFooter>
            <Button
              startContent={<Copy size={16} />}
              onPress={() => {
                void navigator.clipboard.writeText(command);
                toast.success("已复制");
              }}
            >
              复制
            </Button>
            <Button variant="flat" onPress={() => setCommand("")}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
