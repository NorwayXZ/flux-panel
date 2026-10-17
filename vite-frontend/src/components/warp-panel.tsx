import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Cloud, RotateCcw, ShieldCheck } from "lucide-react";
import toast from "react-hot-toast";

import { getWarpStatus, resetWarp, testWarp, type WarpNodeStatus } from "@/api";
import { isAdmin as isAdminUser } from "@/utils/auth";
import { formatDateTime } from "@/utils/format";

/** Cloudflare WARP: a free exit every node can use, with one account registered per node. */
export default function WarpPanel() {
  const admin = isAdminUser();
  const [nodes, setNodes] = useState<WarpNodeStatus[]>([]);
  const [minimumAgentVersion, setMinimumAgentVersion] = useState("2.82.0");
  const [busy, setBusy] = useState<number | null>(null);
  const [expanded, setExpanded] = useState(false);

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
    setBusy(node.nodeId);
    const response = await testWarp(node.nodeId);

    setBusy(null);
    if (response.code !== 0) toast.error(response.msg || "WARP 自检失败");
    else
      toast.success(
        `${node.nodeName} 的 WARP 可用：${response.data.ip}${response.data.country ? `（${response.data.country}）` : ""}，${response.data.latencyMs} ms`,
      );
    void load();
  };

  const reset = async (node: WarpNodeStatus) => {
    if (
      !window.confirm(
        `为“${node.nodeName}”重新注册 WARP 账号吗？用到 WARP 的代理会重新下发。`,
      )
    )
      return;
    setBusy(node.nodeId);
    const response = await resetWarp(node.nodeId);

    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || "操作失败");
    toast.success("已重新注册，下次使用时生效");
    void load();
  };

  const used = nodes.filter((node) => node.registered).length;
  const shown = expanded ? nodes : nodes.slice(0, 4);

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
              WARP”即可：可以作为其余流量的出口、某条规则的出口，或出口组的成员。流量经
              Cloudflare 的网络出去，网站看到的是 Cloudflare 的
              IP，常用来访问对数据中心 IP 不友好的服务，也能让只有 IPv4
              的节点访问
              IPv6。每个节点第一次使用时由面板自动注册一个免费账号，需要节点
              Agent {minimumAgentVersion} 或更高版本。
            </p>
          </div>
        </div>
        <span className="text-xs text-default-400">
          已在 {used} 个节点上启用
        </span>
      </div>
      {nodes.length > 0 && (
        <div className="divide-y divide-divider rounded-xl border border-divider">
          {shown.map((node) => (
            <div
              key={node.nodeId}
              className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm"
            >
              <span className="min-w-28 font-medium">{node.nodeName}</span>
              <span
                className={`min-w-0 flex-1 truncate text-xs ${node.lastTestOk === false ? "text-danger" : "text-default-500"}`}
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
              </span>
              <div className="flex gap-1">
                <Button
                  isDisabled={!node.supported || !node.online}
                  isLoading={busy === node.nodeId}
                  size="sm"
                  startContent={
                    busy === node.nodeId ? null : <ShieldCheck size={14} />
                  }
                  variant="flat"
                  onPress={() => void test(node)}
                >
                  自检
                </Button>
                {admin && node.registered && (
                  <Button
                    isIconOnly
                    aria-label="重新注册 WARP"
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
      {nodes.length > 4 && (
        <Button
          size="sm"
          variant="light"
          onPress={() => setExpanded(!expanded)}
        >
          {expanded ? "收起" : `显示全部 ${nodes.length} 个节点`}
        </Button>
      )}
    </section>
  );
}
