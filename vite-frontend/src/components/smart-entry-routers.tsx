import { useCallback, useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Card, CardBody } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Router, Plus, RefreshCw, Download, Trash2 } from "lucide-react";
import toast from "react-hot-toast";
import {
  createInternalConnector,
  getInternalConnectorInstall,
  getOpenWrtDnsResolvers,
  removeOpenWrtDns,
  deleteInternalConnector,
  type OpenWrtDnsResolver,
  type SmartEntryGroup,
} from "@/api";

const carrierName = (value?: string) =>
  ({ telecom: "电信", unicom: "联通", mobile: "移动" })[value || ""] ||
  "默认入口";

export default function SmartEntryRouters({
  groups,
  onChanged,
  onEditStrategy,
}: {
  groups: SmartEntryGroup[];
  onChanged: () => void;
  onEditStrategy: (group: SmartEntryGroup) => void;
}) {
  const [resolvers, setResolvers] = useState<OpenWrtDnsResolver[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const [command, setCommand] = useState("");
  const load = useCallback(async () => {
    try {
      const result = await getOpenWrtDnsResolvers();
      if (result.code === 0) {
        setResolvers(result.data || []);
        setError("");
      } else setError(result.msg || "加载路由器失败");
    } catch {
      setError("网络异常，请重试");
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
    const timer = setInterval(() => {
      if (!document.hidden) void load();
    }, 10000);
    return () => clearInterval(timer);
  }, [load]);
  useEffect(() => {
    void load();
  }, [groups, load]);
  const create = async () => {
    if (!name.trim()) return toast.error("请输入路由器名称");
    setSaving(true);
    try {
      const result = await createInternalConnector({
        name: name.trim(),
        platform: "openwrt",
        connectorRole: "openwrt_dns",
      });
      if (result.code !== 0) return toast.error(result.msg || "创建失败");
      setCreateOpen(false);
      setName("");
      setCommand(result.data.installCommand);
      toast.success("路由器已添加，安装后创建策略并选择即可");
      void load();
      onChanged();
    } finally {
      setSaving(false);
    }
  };
  const install = async (resolver: OpenWrtDnsResolver, uninstall = false) => {
    const result = await getInternalConnectorInstall(
      resolver.connectorId,
      "openwrt",
      uninstall ? "uninstall" : "install",
    );
    if (result.code !== 0) return toast.error(result.msg || "获取命令失败");
    setCommand(result.data);
  };
  const clear = async (resolver: OpenWrtDnsResolver) => {
    if (!window.confirm("移除此路由器上的全部三网策略？离线时将在上线后清理。"))
      return;
    const result = await removeOpenWrtDns(resolver.connectorId);
    if (result.code !== 0) return toast.error(result.msg || "移除失败");
    toast.success("已提交清理，等待路由器确认");
    void load();
    onChanged();
  };
  const deleteRecord = async (resolver: OpenWrtDnsResolver) => {
    if (
      !window.confirm(
        "删除离线路由器记录？请先在路由器上卸载 Agent，避免旧规则继续生效。",
      )
    )
      return;
    const result = await deleteInternalConnector(resolver.connectorId);
    if (result.code !== 0) return toast.error(result.msg || "删除失败");
    toast.success("路由器记录已删除");
    void load();
    onChanged();
  };
  return (
    <section aria-label="三网优化路由器" className="space-y-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold">OpenWrt 路由器</h2>
          <p className="mt-1 text-sm text-default-500">
            自动识别出口运营商，无需配置 WAN
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            variant="flat"
            isLoading={loading}
            onPress={() => void load()}
            startContent={<RefreshCw size={16} />}
          >
            刷新
          </Button>
          <Button
            color="primary"
            startContent={<Plus size={16} />}
            onPress={() => setCreateOpen(true)}
          >
            添加路由器
          </Button>
        </div>
      </header>
      <details className="rounded-lg border border-divider p-4 text-sm">
        <summary className="cursor-pointer font-medium">使用条件</summary>
        <p className="mt-3 text-default-500">
          设备需使用所选 OpenWrt 的 DNS，Agent
          根据路由器当前公网出口选择入口。业务流量直接连接入口；应用自行使用
          DoH、VPN 或其他 DNS 时可能绕过本功能。多 WAN
          分流时应核对业务实际出口，已有连接需重连。
        </p>
      </details>
      {error && (
        <p
          role="alert"
          className="rounded-lg bg-danger-50 p-3 text-sm text-danger"
        >
          {error}
        </p>
      )}
      {!resolvers.length && !loading && (
        <div className="py-10 text-center text-default-500">
          <Router className="mx-auto mb-3" size={32} />
          暂无路由器，也可以在新建三网优化时直接添加。
        </div>
      )}
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {resolvers.map((resolver) => {
          const applied = Boolean(
            resolver.policyRevision &&
            Number(resolver.appliedRevision || 0) >= resolver.policyRevision,
          );
          return (
            <Card
              key={resolver.connectorId}
              radius="sm"
              shadow="none"
              className="border border-divider"
            >
              <CardBody className="gap-3 p-4">
                <div className="flex items-start justify-between gap-2">
                  <h2 className="min-w-0 break-words font-semibold">
                    {resolver.name}
                  </h2>
                  <Chip
                    size="sm"
                    color={resolver.online ? "success" : "default"}
                    variant="flat"
                  >
                    {resolver.online ? "在线" : "待连接"}
                  </Chip>
                </div>
                <div className="grid grid-cols-2 gap-2 text-sm">
                  <p>
                    IPv4：
                    {resolver.activeInterface
                      ? carrierName(resolver.activeCarrier)
                      : "等待识别"}
                  </p>
                  <p>
                    IPv6：
                    {resolver.activeInterface6
                      ? carrierName(resolver.activeCarrier6)
                      : "未检测到出口"}
                  </p>
                </div>
                <p className="text-sm text-default-500">
                  {resolver.smartEntryGroupIds?.length || 0} 个策略 ·{" "}
                  {resolver.policyRevision
                    ? applied
                      ? "配置已应用"
                      : "等待同步"
                    : "尚未绑定"}
                </p>
                {Boolean(resolver.smartEntryGroupIds?.length) && (
                  <div className="flex flex-wrap gap-1 border-t border-divider pt-2">
                    {resolver.smartEntryGroupIds?.map((id) => {
                      const group = groups.find((item) => item.id === id);
                      return group ? (
                        <Button
                          key={id}
                          size="sm"
                          variant="flat"
                          isDisabled={
                            group.state === "deleting" ||
                            Boolean(group.pendingCleanup)
                          }
                          className="max-w-full"
                          onPress={() => onEditStrategy(group)}
                        >
                          <span className="truncate">
                            {group.name} · {group.domain}
                          </span>
                        </Button>
                      ) : (
                        <span key={id} className="text-xs text-default-500">
                          策略 {id}（已停用或正在清理）
                        </span>
                      );
                    })}
                  </div>
                )}
                {resolver.lastError && (
                  <p
                    role="alert"
                    className="rounded-md bg-warning-50 p-2 text-xs text-warning"
                  >
                    {resolver.lastError}
                  </p>
                )}
                {resolver.detectionError && (
                  <p
                    role="alert"
                    className="rounded-md bg-warning-50 p-2 text-xs text-warning"
                  >
                    识别提示：{resolver.detectionError}
                  </p>
                )}
                <div className="flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    variant="flat"
                    startContent={<Download size={15} />}
                    onPress={() => void install(resolver)}
                  >
                    安装命令
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
                    variant="light"
                    isDisabled={!resolver.smartEntryGroupIds?.length}
                    onPress={() => void clear(resolver)}
                  >
                    移除策略
                  </Button>
                  <Button
                    size="sm"
                    color="danger"
                    variant="light"
                    isDisabled={resolver.online}
                    startContent={<Trash2 size={15} />}
                    onPress={() => void deleteRecord(resolver)}
                  >
                    删除记录
                  </Button>
                </div>
              </CardBody>
            </Card>
          );
        })}
      </div>
      <Modal isOpen={createOpen} onOpenChange={setCreateOpen}>
        <ModalContent>
          <ModalHeader>添加路由器</ModalHeader>
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
              添加
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal
        isOpen={Boolean(command)}
        onOpenChange={(open) => {
          if (!open) setCommand("");
        }}
        size="2xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          <ModalHeader>OpenWrt 终端命令</ModalHeader>
          <ModalBody>
            <pre className="whitespace-pre-wrap break-all rounded-lg bg-default-100 p-3 text-xs">
              {command}
            </pre>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setCommand("")}>
              关闭
            </Button>
            <Button
              color="primary"
              onPress={() =>
                void navigator.clipboard.writeText(command).then(
                  () => toast.success("已复制"),
                  () => toast.error("复制失败，请手动复制"),
                )
              }
            >
              复制命令
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </section>
  );
}
