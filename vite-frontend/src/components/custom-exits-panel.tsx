import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Checkbox, CheckboxGroup } from "@heroui/checkbox";
import { Input, Textarea } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import { Gauge, Pencil, Plus, Route, ShieldCheck, Trash2 } from "lucide-react";
import toast from "react-hot-toast";

import {
  createProxyExits,
  deleteProxyExit,
  getProxyExits,
  parseProxyExits,
  renameProxyExit,
  saveExitGroup,
  testProxyExit,
  type ProxyExit,
} from "@/api";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import WarpPanel from "@/components/warp-panel";
import { formatDateTime } from "@/utils/format";

const protocolName: Record<string, string> = {
  vless: "VLESS",
  vmess: "VMess",
  trojan: "Trojan",
  shadowsocks: "Shadowsocks",
  hysteria2: "Hysteria2",
  tuic: "TUIC",
  anytls: "AnyTLS",
  socks5: "SOCKS5",
  http: "HTTP",
  https: "HTTPS",
};

/** Custom exits: other proxies the user owns that private proxies can send chosen traffic through. */
export default function CustomExitsPanel() {
  const [exits, setExits] = useState<ProxyExit[]>([]);
  const [minimumAgentVersion, setMinimumAgentVersion] = useState("2.76.0");
  const [rulesAgentVersion, setRulesAgentVersion] = useState("2.79.0");
  const [groupTarget, setGroupTarget] = useState<ProxyExit | "new" | null>(
    null,
  );
  const [groupForm, setGroupForm] = useState({
    name: "",
    members: [] as string[],
    direct: false,
    warp: false,
    url: "",
    intervalMinutes: "3",
    tolerance: "50",
  });
  const [groupSaving, setGroupSaving] = useState(false);
  const [loading, setLoading] = useState(true);
  const [addOpen, setAddOpen] = useState(false);
  const [text, setText] = useState("");
  const [name, setName] = useState("");
  const [preview, setPreview] = useState<
    Array<{ name: string; protocol: string; server: string; port: number }>
  >([]);
  const [previewError, setPreviewError] = useState("");
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState<number | null>(null);
  const [renameTarget, setRenameTarget] = useState<ProxyExit | null>(null);
  const [newName, setNewName] = useState("");

  const load = async () => {
    const response = await getProxyExits();

    if (response.code === 0 && response.data) {
      setExits(response.data.exits || []);
      setMinimumAgentVersion(response.data.minimumAgentVersion);
      if (response.data.rulesAgentVersion)
        setRulesAgentVersion(response.data.rulesAgentVersion);
    } else toast.error(response.msg || "读取自定义出口失败");
    setLoading(false);
  };

  useEffect(() => {
    void load();
  }, []);

  // Show what the pasted text contains a moment after typing stops.
  useEffect(() => {
    if (!addOpen || !text.trim()) {
      setPreview([]);
      setPreviewError("");

      return;
    }
    const timer = window.setTimeout(async () => {
      const response = await parseProxyExits(text);

      if (response.code === 0) {
        setPreview(response.data || []);
        setPreviewError("");
      } else {
        setPreview([]);
        setPreviewError(response.msg || "无法识别");
      }
    }, 500);

    return () => window.clearTimeout(timer);
  }, [addOpen, text]);

  const create = async () => {
    setSaving(true);
    const response = await createProxyExits(text, name.trim() || undefined);

    setSaving(false);
    if (response.code !== 0) return toast.error(response.msg || "添加失败");
    toast.success(`已添加 ${response.data?.created || 0} 个出口`);
    setAddOpen(false);
    setText("");
    setName("");
    void load();
  };

  const test = async (exit: ProxyExit) => {
    setTesting(exit.id);
    const response = await testProxyExit(exit.id);

    setTesting(null);
    if (response.code !== 0) toast.error(response.msg || "自检失败");
    else if (exit.group) {
      const members = (
        response.data as typeof response.data & {
          members?: Array<{ name: string; ok: boolean; latencyMs?: number }>;
        }
      ).members;

      toast.success(
        `可用成员：${(members || [])
          .map(
            (member) =>
              `${member.name} ${member.ok ? `${member.latencyMs} ms` : "不可用"}`,
          )
          .join("，")}`,
        { duration: 6000 },
      );
    } else
      toast.success(
        `出口可用：${response.data.ip}${response.data.country ? `（${response.data.country}）` : ""}，${response.data.latencyMs} ms`,
      );
    void load();
  };

  const openGroup = (exit?: ProxyExit) => {
    setGroupForm({
      name: exit?.name || "",
      members: exit?.group?.members.map((member) => String(member.id)) || [],
      direct: exit?.group?.direct || false,
      warp: exit?.group?.warp || false,
      url: exit?.group?.url || "",
      intervalMinutes: String(exit?.group?.intervalMinutes || 3),
      tolerance: String(exit?.group?.tolerance ?? 50),
    });
    setGroupTarget(exit || "new");
  };

  const saveGroup = async () => {
    setGroupSaving(true);
    const response = await saveExitGroup({
      id: groupTarget && groupTarget !== "new" ? groupTarget.id : undefined,
      name: groupForm.name.trim(),
      members: groupForm.members.map(Number),
      direct: groupForm.direct,
      warp: groupForm.warp,
      url: groupForm.url.trim() || undefined,
      intervalMinutes: Number(groupForm.intervalMinutes) || 3,
      tolerance: Number(groupForm.tolerance) || 0,
    });

    setGroupSaving(false);
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    toast.success("出口组已保存");
    setGroupTarget(null);
    void load();
  };

  const plainExits = exits.filter((exit) => !exit.group);
  // Provider exits are managed on their own tab; they can still join groups.
  const ownExits = exits.filter((exit) => !exit.provider);

  const remove = async (exit: ProxyExit) => {
    if (!window.confirm(`删除自定义出口“${exit.name}”吗？`)) return;
    const response = await deleteProxyExit(exit.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("已删除");
    void load();
  };

  const rename = async () => {
    if (!renameTarget) return;
    const response = await renameProxyExit(renameTarget.id, newName);

    if (response.code !== 0) return toast.error(response.msg || "改名失败");
    setRenameTarget(null);
    void load();
  };

  return (
    <div className="space-y-4">
      <WarpPanel />
      <div className="flex flex-wrap items-end justify-between gap-3">
        <p className="max-w-3xl text-sm text-default-500">
          把你在别处的 VLESS、Trojan、Hysteria2
          等节点添加为出口，然后在私人代理的“出口与分流”里，让全部流量或指定域名经它出去。出口组会持续测速，自动用其中最快的成员，类似
          Clash 的 url-test。自检和使用需要节点 Agent {minimumAgentVersion}{" "}
          或更高版本，出口组需要 {rulesAgentVersion} 或更高。
        </p>
        <div className="flex gap-2">
          <Button
            startContent={<Gauge size={16} />}
            variant="flat"
            onPress={() => openGroup()}
          >
            新建出口组
          </Button>
          <Button
            color="primary"
            startContent={<Plus size={16} />}
            onPress={() => setAddOpen(true)}
          >
            添加出口
          </Button>
        </div>
      </div>

      {loading ? (
        <div className="flex min-h-40 items-center justify-center">
          <Spinner />
        </div>
      ) : ownExits.length === 0 ? (
        <div className="flex min-h-48 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-divider px-6 text-center text-default-500">
          <Route size={28} />
          <span className="text-sm">
            还没有自定义出口。点“添加出口”，粘贴分享链接或 Clash 节点即可。
          </span>
        </div>
      ) : (
        <ResourceCardGrid>
          {ownExits.map((exit) => (
            <ResourceCard
              key={exit.id}
              actions={[
                {
                  label: "自检",
                  icon: <ShieldCheck size={14} />,
                  color: "primary",
                  isLoading: testing === exit.id,
                  onPress: () => void test(exit),
                },
                {
                  label: exit.group ? "编辑" : "改名",
                  icon: <Pencil size={14} />,
                  onPress: () => {
                    if (exit.group) return openGroup(exit);
                    setRenameTarget(exit);
                    setNewName(exit.name);
                  },
                },
                {
                  label: "删除",
                  icon: <Trash2 size={14} />,
                  color: "danger",
                  onPress: () => void remove(exit),
                },
              ]}
              blocks={[
                exit.group
                  ? {
                      label: "成员",
                      status: { text: "自动选最快", color: "secondary" },
                      rows: [
                        {
                          value: [
                            ...exit.group.members.map((member) => member.name),
                            ...(exit.group.direct ? ["节点自身 IP"] : []),
                            ...(exit.group.warp ? ["Cloudflare WARP"] : []),
                          ].join("、"),
                        },
                      ],
                    }
                  : {
                      label: "服务器",
                      status: {
                        text: protocolName[exit.protocol] || exit.protocol,
                        color: "primary",
                      },
                      rows: [{ value: `${exit.server}:${exit.port}` }],
                    },
              ]}
              danger={exit.lastTestOk === false}
              facts={[
                {
                  label: "出口 IP",
                  value: exit.lastTestIp
                    ? `${exit.lastTestIp}${exit.lastTestCountry ? ` · ${exit.lastTestCountry}` : ""}`
                    : "未检测",
                },
                {
                  label: "延迟",
                  value: exit.lastTestLatency
                    ? `${exit.lastTestLatency} ms`
                    : "-",
                },
                { label: "使用中的代理", value: exit.usedBy },
                {
                  label: "最近自检",
                  value: formatDateTime(exit.lastTestAt, "未检测"),
                },
              ]}
              notice={
                exit.lastTestOk === false && exit.lastTestError
                  ? {
                      label: "最近一次自检失败",
                      value: (
                        <span title={exit.lastTestError}>
                          {exit.lastTestError}
                        </span>
                      ),
                      tone: "danger",
                    }
                  : undefined
              }
              status={
                exit.lastTestOk === undefined || exit.lastTestOk === null
                  ? { text: "未检测", color: "default" }
                  : exit.lastTestOk
                    ? { text: "可用", color: "success" }
                    : { text: "不可用", color: "danger" }
              }
              subtitles={[
                ...(exit.group
                  ? [
                      `每 ${exit.group.intervalMinutes} 分钟测速，快 ${exit.group.tolerance} ms 以上才切换`,
                    ]
                  : []),
                ...(exit.ownerUserName ? [`所有者 ${exit.ownerUserName}`] : []),
              ]}
              title={exit.name}
            />
          ))}
        </ResourceCardGrid>
      )}

      <Modal isOpen={addOpen} size="2xl" onOpenChange={setAddOpen}>
        <ModalContent>
          <ModalHeader>添加自定义出口</ModalHeader>
          <ModalBody className="space-y-3">
            <Textarea
              description="支持 vless://、vmess://、trojan://、ss://、hysteria2://、tuic://、anytls://、socks5://、http(s):// 链接（可一次粘贴多条），或 Clash 配置里的 proxies 节点。"
              label="分享链接或 Clash 节点"
              maxRows={12}
              minRows={4}
              value={text}
              onValueChange={setText}
            />
            {preview.length === 1 && (
              <Input
                label="名称（可选）"
                placeholder={preview[0].name}
                value={name}
                onValueChange={setName}
              />
            )}
            {previewError ? (
              <p className="text-sm text-danger">{previewError}</p>
            ) : preview.length > 0 ? (
              <div className="space-y-1.5 rounded-xl bg-default-50 p-3 dark:bg-default-100/60">
                <p className="text-xs text-default-500">
                  识别到 {preview.length} 个出口：
                </p>
                {preview.map((item, index) => (
                  <div
                    // Parsed items have no id yet.
                    // eslint-disable-next-line react/no-array-index-key
                    key={index}
                    className="flex min-w-0 items-center justify-between gap-2 text-sm"
                  >
                    <span className="truncate font-medium">{item.name}</span>
                    <span className="shrink-0 font-mono text-xs text-default-500">
                      {protocolName[item.protocol] || item.protocol} ·{" "}
                      {item.server}:{item.port}
                    </span>
                  </div>
                ))}
              </div>
            ) : null}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setAddOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isDisabled={preview.length === 0}
              isLoading={saving}
              onPress={create}
            >
              添加 {preview.length > 1 ? `${preview.length} 个出口` : ""}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={Boolean(groupTarget)}
        scrollBehavior="inside"
        size="xl"
        onOpenChange={(open) => !open && setGroupTarget(null)}
      >
        <ModalContent>
          <ModalHeader>
            {groupTarget === "new" ? "新建出口组" : "编辑出口组"}
          </ModalHeader>
          <ModalBody className="space-y-3">
            <p className="text-sm text-default-500">
              节点会定时经每个成员访问测速地址，始终使用延迟最低的那个；成员不可用时自动换到其他成员。
            </p>
            <Input
              label="名称"
              placeholder="例如 美国自动"
              value={groupForm.name}
              onValueChange={(name) => setGroupForm({ ...groupForm, name })}
            />
            {plainExits.length === 0 ? (
              <p className="text-sm text-default-500">
                还没有可加入的出口，请先添加至少一个自定义出口。
              </p>
            ) : (
              <CheckboxGroup
                label="成员"
                value={groupForm.members}
                onValueChange={(members) =>
                  setGroupForm({ ...groupForm, members })
                }
              >
                {plainExits.map((exit) => (
                  <Checkbox key={exit.id} value={String(exit.id)}>
                    <span className="text-sm">
                      {exit.name}
                      <span className="ml-2 text-xs text-default-400">
                        {protocolName[exit.protocol] || exit.protocol}
                        {exit.lastTestLatency
                          ? ` · ${exit.lastTestLatency} ms`
                          : ""}
                      </span>
                    </span>
                  </Checkbox>
                ))}
              </CheckboxGroup>
            )}
            <Checkbox
              isSelected={groupForm.warp}
              onValueChange={(warp) => setGroupForm({ ...groupForm, warp })}
            >
              <span className="text-sm">
                Cloudflare WARP 也作为成员
                <span className="ml-2 text-xs text-default-400">
                  免费，节点 Agent 需要 2.82.0 或更高
                </span>
              </span>
            </Checkbox>
            <Checkbox
              isSelected={groupForm.direct}
              onValueChange={(direct) => setGroupForm({ ...groupForm, direct })}
            >
              <span className="text-sm">
                节点自身 IP 也作为成员
                <span className="ml-2 text-xs text-default-400">
                  其他成员都不可用时还能直接出去
                </span>
              </span>
            </Checkbox>
            <details className="rounded-xl bg-default-50 p-3 dark:bg-default-100/60">
              <summary className="cursor-pointer text-sm">测速设置</summary>
              <div className="mt-3 grid gap-3 sm:grid-cols-3">
                <Input
                  className="sm:col-span-3"
                  label="测速地址"
                  placeholder="https://www.gstatic.com/generate_204"
                  size="sm"
                  value={groupForm.url}
                  onValueChange={(url) => setGroupForm({ ...groupForm, url })}
                />
                <Input
                  endContent={<span className="text-xs">分钟</span>}
                  label="测速间隔"
                  size="sm"
                  type="number"
                  value={groupForm.intervalMinutes}
                  onValueChange={(intervalMinutes) =>
                    setGroupForm({ ...groupForm, intervalMinutes })
                  }
                />
                <Input
                  className="sm:col-span-2"
                  description="新成员要比当前的快这么多才切换，避免来回跳"
                  endContent={<span className="text-xs">ms</span>}
                  label="切换容差"
                  size="sm"
                  type="number"
                  value={groupForm.tolerance}
                  onValueChange={(tolerance) =>
                    setGroupForm({ ...groupForm, tolerance })
                  }
                />
              </div>
            </details>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setGroupTarget(null)}>
              取消
            </Button>
            <Button
              color="primary"
              isDisabled={
                !groupForm.name.trim() ||
                groupForm.members.length +
                  (groupForm.direct ? 1 : 0) +
                  (groupForm.warp ? 1 : 0) <
                  2
              }
              isLoading={groupSaving}
              onPress={saveGroup}
            >
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={Boolean(renameTarget)}
        size="sm"
        onOpenChange={(open) => !open && setRenameTarget(null)}
      >
        <ModalContent>
          <ModalHeader>修改名称</ModalHeader>
          <ModalBody>
            <Input label="名称" value={newName} onValueChange={setNewName} />
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setRenameTarget(null)}>
              取消
            </Button>
            <Button color="primary" onPress={rename}>
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
