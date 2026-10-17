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
import { Cloud, Gauge, KeyRound, RotateCcw, ShieldCheck } from "lucide-react";
import toast from "react-hot-toast";

import {
  bindWarpLicense,
  getWarpStatus,
  optimizeWarp,
  resetWarp,
  testWarp,
  type WarpNodeStatus,
} from "@/api";
import { isAdmin as isAdminUser } from "@/utils/auth";
import { formatDateTime } from "@/utils/format";

const plus = (type?: string | null) =>
  type === "limited" || type === "unlimited";

/** Cloudflare WARP: a free exit every node can use, with one account registered per node. */
export default function WarpPanel() {
  const admin = isAdminUser();
  const [nodes, setNodes] = useState<WarpNodeStatus[]>([]);
  const [minimumAgentVersion, setMinimumAgentVersion] = useState("2.82.0");
  const [busy, setBusy] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [licenseOpen, setLicenseOpen] = useState(false);
  const [license, setLicense] = useState("");
  const [binding, setBinding] = useState(false);
  const [bindResults, setBindResults] = useState<
    Array<{
      nodeName: string;
      ok: boolean;
      accountType?: string;
      error?: string;
    }>
  >([]);

  const load = async () => {
    const response = await getWarpStatus();

    if (response.code === 0 && response.data) {
      setNodes(response.data.nodes || []);
      setMinimumAgentVersion(response.data.minimumAgentVersion);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const test = async (node: WarpNodeStatus) => {
    setBusy(`test-${node.nodeId}`);
    const response = await testWarp(node.nodeId);

    setBusy(null);
    if (response.code !== 0) toast.error(response.msg || "WARP 自检失败");
    else
      toast.success(
        `${node.nodeName} 的 WARP 可用：${response.data.ip}${response.data.country ? `（${response.data.country}）` : ""}，${response.data.latencyMs} ms`,
      );
    void load();
  };

  const optimize = async (node: WarpNodeStatus) => {
    setBusy(`optimize-${node.nodeId}`);
    toast("正在逐个测试 WARP 接入点，约需几十秒…", { duration: 4000 });
    const response = await optimizeWarp(node.nodeId);

    setBusy(null);
    if (response.code !== 0) toast.error(response.msg || "优选失败");
    else {
      const working = response.data.results.filter((item) => item.ok).length;

      toast.success(
        response.data.changed
          ? `已换到 ${response.data.endpoint}（${response.data.latencyMs} ms），${working}/${response.data.results.length} 个接入点可用，相关代理已重新下发`
          : `当前接入点已是最快（${response.data.latencyMs} ms），${working}/${response.data.results.length} 个接入点可用`,
        { duration: 6000 },
      );
    }
    void load();
  };

  const reset = async (node: WarpNodeStatus) => {
    if (
      !window.confirm(
        `为“${node.nodeName}”重新注册 WARP 账号吗？用到 WARP 的代理会重新下发，已绑定的 WARP+ 许可证会保留。`,
      )
    )
      return;
    setBusy(`reset-${node.nodeId}`);
    const response = await resetWarp(node.nodeId);

    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || "操作失败");
    toast.success("已重新注册");
    void load();
  };

  const bind = async () => {
    setBinding(true);
    const response = await bindWarpLicense(license.trim());

    setBinding(false);
    if (response.code !== 0) return toast.error(response.msg || "绑定失败");
    setBindResults(response.data.results);
    if (response.data.bound > 0)
      toast.success(`已在 ${response.data.bound} 个节点上绑定 WARP+`);
    void load();
  };

  // Nodes already using WARP are always listed; the rest fold into one line.
  const active = nodes.filter((node) => node.registered);
  const idle = nodes.filter((node) => !node.registered);
  const outdated = idle.filter((node) => !node.supported).length;
  const used = active.length;
  const shown = expanded ? [...active, ...idle] : active;

  return (
    <section className="space-y-3 rounded-2xl border border-divider bg-content1 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 gap-3">
          <Cloud className="mt-0.5 shrink-0 text-warning-500" size={22} />
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-semibold text-foreground">
                Cloudflare WARP
              </span>
              <Chip color="success" size="sm" variant="flat">
                免费 · 内置
              </Chip>
            </div>
            <p className="mt-1 max-w-3xl text-sm text-default-500">
              不用添加，出口列表里直接选“Cloudflare
              WARP”即可：可以作为其余流量的出口、某条规则的出口，或出口组的成员。网站看到的是
              Cloudflare 的 IP，常用来访问对机房 IP 不友好的服务，也能让只有
              IPv4 的节点访问
              IPv6。每个节点第一次使用时由面板自动注册免费账号，需要节点 Agent{" "}
              {minimumAgentVersion} 或更高版本。
            </p>
            <p className="mt-1 max-w-3xl text-xs text-default-400">
              面板每 30 分钟检查一次在用的
              WARP；连续两次失败会自动换一个接入点，仍不行就重新注册账号，修不好时在告警中心提示。
            </p>
          </div>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-2">
          <span className="text-xs text-default-400">
            已在 {used} 个节点上启用
          </span>
          {admin && (
            <Button
              size="sm"
              startContent={<KeyRound size={14} />}
              variant="flat"
              onPress={() => {
                setBindResults([]);
                setLicenseOpen(true);
              }}
            >
              WARP+ 许可证
            </Button>
          )}
        </div>
      </div>
      {shown.length > 0 && (
        <div className="divide-y divide-divider rounded-xl border border-divider">
          {shown.map((node) => (
            <div
              key={node.nodeId}
              className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm"
            >
              <div className="flex min-w-28 items-center gap-1.5">
                <span className="font-medium">{node.nodeName}</span>
                {plus(node.accountType) && (
                  <Chip color="warning" size="sm" variant="flat">
                    WARP+
                  </Chip>
                )}
              </div>
              <div className="min-w-0 flex-1">
                <div
                  className={`truncate text-xs ${node.lastTestOk === false ? "text-danger" : "text-default-500"}`}
                  title={node.lastError || undefined}
                >
                  {!node.supported
                    ? `Agent 版本过低，需要 ${minimumAgentVersion}`
                    : !node.registered
                      ? "还没用过，第一次使用或自检时自动注册"
                      : node.lastTestOk === false
                        ? `自检失败：${node.lastError || "未知原因"}`
                        : node.lastTestIp
                          ? `${node.lastTestIp}${node.lastTestCountry ? ` · ${node.lastTestCountry}` : ""} · ${node.lastTestLatency} ms · ${formatDateTime(node.lastTestAt)}`
                          : "已注册，尚未自检"}
                </div>
                {node.registered && (
                  <div className="truncate text-xs text-default-400">
                    接入点 {node.endpoint}
                    {node.usedBy
                      ? ` · ${node.usedBy} 个代理在用`
                      : " · 暂无代理在用"}
                  </div>
                )}
              </div>
              <div className="flex gap-1">
                <Button
                  isDisabled={!node.supported || !node.online || Boolean(busy)}
                  isLoading={busy === `test-${node.nodeId}`}
                  size="sm"
                  startContent={
                    busy === `test-${node.nodeId}` ? null : (
                      <ShieldCheck size={14} />
                    )
                  }
                  variant="flat"
                  onPress={() => void test(node)}
                >
                  自检
                </Button>
                <Button
                  isDisabled={!node.supported || !node.online || Boolean(busy)}
                  isLoading={busy === `optimize-${node.nodeId}`}
                  size="sm"
                  startContent={
                    busy === `optimize-${node.nodeId}` ? null : (
                      <Gauge size={14} />
                    )
                  }
                  title="逐个测试 WARP 接入点，换到最快的"
                  variant="flat"
                  onPress={() => void optimize(node)}
                >
                  优选接入点
                </Button>
                {admin && node.registered && (
                  <Button
                    isIconOnly
                    aria-label="重新注册 WARP"
                    isDisabled={Boolean(busy)}
                    isLoading={busy === `reset-${node.nodeId}`}
                    size="sm"
                    title="重新注册 WARP 账号"
                    variant="light"
                    onPress={() => void reset(node)}
                  >
                    <RotateCcw size={14} />
                  </Button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
      {idle.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 text-xs text-default-500">
          <span>
            {active.length === 0
              ? "还没有节点用过 WARP：在“出口与分流”里选 Cloudflare WARP，或展开后对任意节点点“自检”即可启用。"
              : `另外 ${idle.length} 个节点还没用过 WARP`}
            {outdated > 0 ? `（其中 ${outdated} 个节点 Agent 版本过低）` : ""}
          </span>
          <Button
            size="sm"
            variant="light"
            onPress={() => setExpanded(!expanded)}
          >
            {expanded ? "收起" : "展开"}
          </Button>
        </div>
      )}

      <Modal isOpen={licenseOpen} size="lg" onOpenChange={setLicenseOpen}>
        <ModalContent>
          <ModalHeader>绑定 WARP+ 许可证</ModalHeader>
          <ModalBody className="space-y-3">
            <p className="text-sm text-default-500">
              WARP+ 走 Cloudflare 的高速网络，通常更快更稳定。填入在 1.1.1.1
              客户端里看到的许可证（xxxxxxxx-xxxxxxxx-xxxxxxxx），会绑定到所有已启用
              WARP
              的节点，之后新启用的节点也会自动绑定。一个许可证能绑定的设备数量有限（通常
              5 台），超出的节点会提示失败，仍按免费 WARP 使用。
            </p>
            <Input
              label="许可证"
              placeholder="xxxxxxxx-xxxxxxxx-xxxxxxxx"
              value={license}
              onValueChange={setLicense}
            />
            {bindResults.length > 0 && (
              <div className="space-y-1 rounded-xl bg-default-50 p-3 text-sm dark:bg-default-100/60">
                {bindResults.map((item) => (
                  <div
                    key={item.nodeName}
                    className="flex justify-between gap-2"
                  >
                    <span>{item.nodeName}</span>
                    <span
                      className={item.ok ? "text-success-600" : "text-danger"}
                    >
                      {item.ok
                        ? plus(item.accountType)
                          ? "已升级为 WARP+"
                          : `已绑定（${item.accountType || "未知类型"}）`
                        : item.error}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setLicenseOpen(false)}>
              关闭
            </Button>
            <Button
              color="primary"
              isDisabled={!license.trim()}
              isLoading={binding}
              onPress={bind}
            >
              绑定到所有节点
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </section>
  );
}
