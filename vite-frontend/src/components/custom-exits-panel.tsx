import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Input, Textarea } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import { Pencil, Plus, Route, ShieldCheck, Trash2 } from "lucide-react";
import toast from "react-hot-toast";

import {
  createProxyExits,
  deleteProxyExit,
  getProxyExits,
  parseProxyExits,
  renameProxyExit,
  testProxyExit,
  type ProxyExit,
} from "@/api";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
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
    else
      toast.success(
        `出口可用：${response.data.ip}${response.data.country ? `（${response.data.country}）` : ""}，${response.data.latencyMs} ms`,
      );
    void load();
  };

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
      <div className="flex flex-wrap items-end justify-between gap-3">
        <p className="max-w-3xl text-sm text-default-500">
          把你在别处的 VLESS、Trojan、Hysteria2
          等节点添加为出口，然后在私人代理的“出口与分流”里，让全部流量或指定域名经它出去。自检和使用都需要节点
          Agent {minimumAgentVersion} 或更高版本。
        </p>
        <Button
          color="primary"
          startContent={<Plus size={16} />}
          onPress={() => setAddOpen(true)}
        >
          添加出口
        </Button>
      </div>

      {loading ? (
        <div className="flex min-h-40 items-center justify-center">
          <Spinner />
        </div>
      ) : exits.length === 0 ? (
        <div className="flex min-h-48 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-divider px-6 text-center text-default-500">
          <Route size={28} />
          <span className="text-sm">
            还没有自定义出口。点“添加出口”，粘贴分享链接或 Clash 节点即可。
          </span>
        </div>
      ) : (
        <ResourceCardGrid>
          {exits.map((exit) => (
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
                  label: "改名",
                  icon: <Pencil size={14} />,
                  onPress: () => {
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
                {
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
              subtitles={
                exit.ownerUserName ? [`所有者 ${exit.ownerUserName}`] : []
              }
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
