import { useEffect, useMemo, useState } from "react";
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
import { Switch } from "@heroui/switch";
import {
  CalendarClock,
  CircleDollarSign,
  Pencil,
  Plus,
  RefreshCw,
  Server,
  Trash2,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  deleteServerAsset,
  getNodeList,
  getServerAssets,
  saveServerAsset,
  type ServerAsset,
  type ServerAssetOverview,
} from "@/api";

interface NodeOption {
  id: number;
  name: string;
  serverIp?: string;
  ip?: string;
  status: number;
}
const EMPTY: ServerAssetOverview = {
  items: [],
  summary: { total: 0, expiringSoon: 0, expired: 0, costByCurrency: [] },
};
const initialForm = {
  id: undefined as number | undefined,
  nodeId: "",
  name: "",
  provider: "",
  region: "",
  cpuSpec: "",
  memoryMb: "",
  diskGb: "",
  bandwidthMbps: "",
  currency: "CNY",
  monthlyCost: "0",
  purchaseDate: "",
  expiryDate: "",
  autoRenew: false,
  ipv4: "",
  ipv6: "",
  asn: "",
  networkLine: "",
  trafficPlan: "",
  tags: "",
  notes: "",
  reminderEnabled: true,
  reminderDays: "30,7,3,1,0",
};

const dateInput = (value?: number) =>
  value ? new Date(value).toISOString().slice(0, 10) : "";
const timestamp = (value: string) =>
  value ? new Date(`${value}T00:00:00+08:00`).getTime() : undefined;
const displayDate = (value?: number) =>
  value
    ? new Intl.DateTimeFormat("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
      }).format(value)
    : "未设置";

export default function ServerAssetsPage() {
  const [data, setData] = useState(EMPTY);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState(initialForm);

  const load = async () => {
    setLoading(true);
    const [assetResponse, nodeResponse] = await Promise.all([
      getServerAssets(),
      getNodeList(),
    ]);

    if (assetResponse.code === 0) setData(assetResponse.data || EMPTY);
    else toast.error(assetResponse.msg || "获取服务器资产失败");
    if (nodeResponse.code === 0) setNodes(nodeResponse.data || []);
    setLoading(false);
  };

  useEffect(() => {
    void load();
  }, []);

  const costs = useMemo(
    () =>
      data.summary.costByCurrency
        .map(
          (item) => `${item.currency} ${Number(item.monthlyCost).toFixed(2)}`,
        )
        .join(" · ") || "0.00",
    [data],
  );
  const edit = (item?: ServerAsset) => {
    setForm(
      item
        ? {
            id: item.id,
            nodeId: item.nodeId ? String(item.nodeId) : "",
            name: item.name,
            provider: item.provider || "",
            region: item.region || "",
            cpuSpec: item.cpuSpec || "",
            memoryMb: item.memoryMb ? String(item.memoryMb) : "",
            diskGb: item.diskGb ? String(item.diskGb) : "",
            bandwidthMbps: item.bandwidthMbps ? String(item.bandwidthMbps) : "",
            currency: item.currency || "CNY",
            monthlyCost: String(item.monthlyCost || 0),
            purchaseDate: dateInput(item.purchaseDate),
            expiryDate: dateInput(item.expiryDate),
            autoRenew: Boolean(item.autoRenew),
            ipv4: item.ipv4 || "",
            ipv6: item.ipv6 || "",
            asn: item.asn || "",
            networkLine: item.networkLine || "",
            trafficPlan: item.trafficPlan || "",
            tags: item.tags || "",
            notes: item.notes || "",
            reminderEnabled: Boolean(item.reminderEnabled),
            reminderDays: item.reminderDays || "30,7,3,1,0",
          }
        : initialForm,
    );
    setAdvanced(
      Boolean(
        item?.ipv6 ||
        item?.asn ||
        item?.networkLine ||
        item?.trafficPlan ||
        item?.notes,
      ),
    );
    setOpen(true);
  };
  const save = async () => {
    if (!form.name.trim()) return toast.error("请填写资产名称");
    setSaving(true);
    const response = await saveServerAsset({
      ...form,
      nodeId: form.nodeId ? Number(form.nodeId) : undefined,
      memoryMb: form.memoryMb ? Number(form.memoryMb) : undefined,
      diskGb: form.diskGb ? Number(form.diskGb) : undefined,
      bandwidthMbps: form.bandwidthMbps
        ? Number(form.bandwidthMbps)
        : undefined,
      monthlyCost: Number(form.monthlyCost || 0),
      purchaseDate: timestamp(form.purchaseDate),
      expiryDate: timestamp(form.expiryDate),
    });

    setSaving(false);
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    toast.success("服务器资产已保存");
    setOpen(false);
    await load();
  };
  const remove = async (item: ServerAsset) => {
    if (!window.confirm(`删除资产“${item.name}”？节点本身不会被删除。`)) return;
    const response = await deleteServerAsset(item.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("资产资料已删除");
    await load();
  };

  return (
    <div className="mx-auto w-full max-w-[1500px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">资产与成本</p>
          <h1 className="mt-1 text-2xl font-semibold">服务器资产</h1>
        </div>
        <div className="flex gap-2">
          <Button isIconOnly aria-label="刷新" variant="flat" onPress={load}>
            <RefreshCw size={17} />
          </Button>
          <Button
            color="primary"
            startContent={<Plus size={17} />}
            onPress={() => edit()}
          >
            登记服务器
          </Button>
        </div>
      </header>
      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {[
          ["资产总数", data.summary.total, <Server size={18} />],
          [
            "30 天内到期",
            data.summary.expiringSoon,
            <CalendarClock size={18} />,
          ],
          ["已到期", data.summary.expired, <CalendarClock size={18} />],
          ["月度费用", costs, <CircleDollarSign size={18} />],
        ].map(([label, value, icon]) => (
          <div
            key={String(label)}
            className="rounded-md border border-divider bg-content1 p-4"
          >
            <div className="flex items-center justify-between text-sm text-default-500">
              <span>{label}</span>
              {icon}
            </div>
            <div className="mt-2 text-xl font-semibold">{value}</div>
          </div>
        ))}
      </section>
      <section className="overflow-hidden rounded-md border border-divider bg-content1">
        <div className="hidden grid-cols-[minmax(180px,1.3fr)_minmax(150px,1fr)_150px_130px_150px_96px] gap-4 border-b border-divider px-4 py-3 text-xs font-semibold text-default-500 lg:grid">
          <span>服务器</span>
          <span>配置与线路</span>
          <span>费用</span>
          <span>到期</span>
          <span>标签</span>
          <span className="text-right">操作</span>
        </div>
        {loading ? (
          <div className="flex min-h-48 items-center justify-center text-default-500">
            正在读取资产资料
          </div>
        ) : data.items.length === 0 ? (
          <div className="flex min-h-48 flex-col items-center justify-center gap-2 text-default-500">
            <Server size={28} />
            <span>尚未登记服务器资产</span>
          </div>
        ) : (
          data.items.map((item, index) => {
            const urgent =
              item.remainingDays !== undefined &&
              item.remainingDays !== null &&
              item.remainingDays <= 30;

            return (
              <div
                key={item.id}
                className={`grid gap-3 px-4 py-4 lg:grid-cols-[minmax(180px,1.3fr)_minmax(150px,1fr)_150px_130px_150px_96px] lg:items-center ${index ? "border-t border-divider" : ""}`}
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong className="truncate">{item.name}</strong>
                    {item.nodeId && (
                      <Chip
                        color={item.nodeStatus === 1 ? "success" : "default"}
                        size="sm"
                        variant="flat"
                      >
                        {item.nodeName || `节点 ${item.nodeId}`}
                      </Chip>
                    )}
                  </div>
                  <p className="mt-1 truncate text-sm text-default-500">
                    {item.provider || "未填写厂商"} ·{" "}
                    {item.region || "未填写地区"}
                  </p>
                  <p className="mt-1 font-mono text-xs text-default-400">
                    {item.ipv4 || item.ipv6 || "未登记 IP"}
                  </p>
                </div>
                <div className="text-sm">
                  <p>
                    {item.cpuSpec || "配置未填写"}
                    {item.memoryMb ? ` · ${item.memoryMb} MB` : ""}
                    {item.diskGb ? ` · ${item.diskGb} GB` : ""}
                  </p>
                  <p className="mt-1 text-default-500">
                    {item.networkLine ||
                      item.trafficPlan ||
                      "线路与流量套餐未填写"}
                  </p>
                </div>
                <div>
                  <p className="font-medium">
                    {item.currency} {Number(item.monthlyCost).toFixed(2)}
                  </p>
                  <p className="mt-1 text-xs text-default-500">每月</p>
                </div>
                <div>
                  <Chip
                    color={
                      item.remainingDays !== undefined && item.remainingDays < 0
                        ? "danger"
                        : urgent
                          ? "warning"
                          : "success"
                    }
                    size="sm"
                    variant="flat"
                  >
                    {item.expiryDate
                      ? item.remainingDays! < 0
                        ? `已过期 ${Math.abs(item.remainingDays!)} 天`
                        : `剩余 ${item.remainingDays} 天`
                      : "长期 / 未设置"}
                  </Chip>
                  <p className="mt-1 text-xs text-default-500">
                    {displayDate(item.expiryDate)}
                    {item.autoRenew ? " · 自动续费" : ""}
                  </p>
                </div>
                <div
                  className="truncate text-sm text-default-500"
                  title={item.tags || ""}
                >
                  {item.tags || "无标签"}
                </div>
                <div className="flex justify-end gap-1">
                  <Button
                    isIconOnly
                    aria-label="编辑"
                    size="sm"
                    variant="light"
                    onPress={() => edit(item)}
                  >
                    <Pencil size={16} />
                  </Button>
                  <Button
                    isIconOnly
                    aria-label="删除"
                    color="danger"
                    size="sm"
                    variant="light"
                    onPress={() => remove(item)}
                  >
                    <Trash2 size={16} />
                  </Button>
                </div>
              </div>
            );
          })
        )}
      </section>
      <Modal
        isOpen={open}
        scrollBehavior="inside"
        size="4xl"
        onOpenChange={setOpen}
      >
        <ModalContent>
          <ModalHeader>
            {form.id ? "编辑服务器资产" : "登记服务器资产"}
          </ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 md:grid-cols-2">
              <Input
                isRequired
                label="资产名称"
                value={form.name}
                onValueChange={(value) => setForm({ ...form, name: value })}
              />
              <Select
                label="关联节点（可选）"
                selectedKeys={form.nodeId ? [form.nodeId] : []}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    nodeId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {nodes.map((node) => (
                  <SelectItem key={String(node.id)} textValue={node.name}>
                    {node.name} · {node.serverIp || node.ip || "无地址"}
                  </SelectItem>
                ))}
              </Select>
              <Input
                label="厂商"
                placeholder="RackNerd / AWS / 阿里云"
                value={form.provider}
                onValueChange={(value) => setForm({ ...form, provider: value })}
              />
              <Input
                label="地区"
                placeholder="US Los Angeles"
                value={form.region}
                onValueChange={(value) => setForm({ ...form, region: value })}
              />
            </div>
            <div className="grid gap-4 md:grid-cols-4">
              <Input
                label="CPU 配置"
                placeholder="2 vCPU"
                value={form.cpuSpec}
                onValueChange={(value) => setForm({ ...form, cpuSpec: value })}
              />
              <Input
                label="内存（MB）"
                type="number"
                value={form.memoryMb}
                onValueChange={(value) => setForm({ ...form, memoryMb: value })}
              />
              <Input
                label="磁盘（GB）"
                type="number"
                value={form.diskGb}
                onValueChange={(value) => setForm({ ...form, diskGb: value })}
              />
              <Input
                label="带宽（Mbps）"
                type="number"
                value={form.bandwidthMbps}
                onValueChange={(value) =>
                  setForm({ ...form, bandwidthMbps: value })
                }
              />
            </div>
            <div className="grid gap-4 md:grid-cols-4">
              <Input
                label="币种"
                value={form.currency}
                onValueChange={(value) =>
                  setForm({ ...form, currency: value.toUpperCase() })
                }
              />
              <Input
                label="月度费用"
                type="number"
                value={form.monthlyCost}
                onValueChange={(value) =>
                  setForm({ ...form, monthlyCost: value })
                }
              />
              <Input
                label="购买日期"
                type="date"
                value={form.purchaseDate}
                onValueChange={(value) =>
                  setForm({ ...form, purchaseDate: value })
                }
              />
              <Input
                label="到期日期"
                type="date"
                value={form.expiryDate}
                onValueChange={(value) =>
                  setForm({ ...form, expiryDate: value })
                }
              />
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              <Input
                label="IPv4"
                value={form.ipv4}
                onValueChange={(value) => setForm({ ...form, ipv4: value })}
              />
              <Input
                label="标签"
                placeholder="生产, 年付, CN2"
                value={form.tags}
                onValueChange={(value) => setForm({ ...form, tags: value })}
              />
            </div>
            <div className="flex flex-wrap items-center gap-6 border-y border-divider py-3">
              <Switch
                isSelected={form.autoRenew}
                size="sm"
                onValueChange={(value) =>
                  setForm({ ...form, autoRenew: value })
                }
              >
                自动续费
              </Switch>
              <Switch
                isSelected={form.reminderEnabled}
                size="sm"
                onValueChange={(value) =>
                  setForm({ ...form, reminderEnabled: value })
                }
              >
                Telegram 到期提醒
              </Switch>
              {form.reminderEnabled && (
                <Input
                  className="max-w-xs"
                  description="用逗号分隔，0 表示到期当天"
                  label="提前提醒天数"
                  size="sm"
                  value={form.reminderDays}
                  onValueChange={(value) =>
                    setForm({ ...form, reminderDays: value })
                  }
                />
              )}
            </div>
            <Button
              className="justify-start"
              variant="light"
              onPress={() => setAdvanced(!advanced)}
            >
              {advanced ? "收起高级资料" : "展开线路、ASN 和备注"}
            </Button>
            {advanced && (
              <div className="grid gap-4 border-t border-divider pt-4 md:grid-cols-2">
                <Input
                  label="IPv6"
                  value={form.ipv6}
                  onValueChange={(value) => setForm({ ...form, ipv6: value })}
                />
                <Input
                  label="ASN"
                  placeholder="AS12345"
                  value={form.asn}
                  onValueChange={(value) => setForm({ ...form, asn: value })}
                />
                <Input
                  label="网络线路"
                  placeholder="CN2 GIA / BGP"
                  value={form.networkLine}
                  onValueChange={(value) =>
                    setForm({ ...form, networkLine: value })
                  }
                />
                <Input
                  label="流量套餐"
                  placeholder="1 TB 双向 / 不限流量"
                  value={form.trafficPlan}
                  onValueChange={(value) =>
                    setForm({ ...form, trafficPlan: value })
                  }
                />
                <Textarea
                  className="md:col-span-2"
                  label="备注"
                  value={form.notes}
                  onValueChange={(value) => setForm({ ...form, notes: value })}
                />
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setOpen(false)}>
              取消
            </Button>
            <Button color="primary" isLoading={saving} onPress={save}>
              保存资产
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
