import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Tab, Tabs } from "@heroui/tabs";
import {
  Activity,
  CircleCheck,
  CircleX,
  Play,
  Route,
  Search,
  Server,
} from "lucide-react";
import toast from "react-hot-toast";

import PageShell from "@/components/page-shell";
import PageHelp from "@/components/page-help";

import {
  getNodeList,
  runNetworkDiagnostic,
  type NetworkDiagnosticResult,
} from "@/api";

interface NodeOption {
  id: number;
  name: string;
  ip?: string;
  serverIp?: string;
  status: number;
  version?: string;
}
type Mode = "ping" | "tcp" | "dns" | "trace";

export default function NetworkToolsPage() {
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<NetworkDiagnosticResult | null>(null);
  const [form, setForm] = useState({
    nodeId: "",
    mode: "ping" as Mode,
    target: "",
    port: "443",
    recordType: "A",
    count: "4",
    timeoutMs: "5000",
  });

  useEffect(() => {
    void getNodeList().then((response) => {
      if (response.code === 0) setNodes(response.data || []);
    });
  }, []);

  const run = async () => {
    if (!form.nodeId || !form.target.trim())
      return toast.error("请选择节点并填写目标地址");
    if (
      form.mode === "tcp" &&
      (!Number(form.port) || Number(form.port) > 65535)
    )
      return toast.error("请填写有效的 TCP 端口");
    setRunning(true);
    setResult(null);
    const response = await runNetworkDiagnostic({
      nodeId: Number(form.nodeId),
      mode: form.mode,
      target: form.target.trim(),
      port: form.mode === "tcp" ? Number(form.port) : undefined,
      recordType: form.mode === "dns" ? form.recordType : undefined,
      count: Number(form.count),
      timeoutMs: Number(form.timeoutMs),
    });

    setRunning(false);
    if (response.code !== 0) return toast.error(response.msg || "诊断失败");
    setResult(response.data);
  };

  return (
    <PageShell className="space-y-5">
      <header>
        <div className="flex items-center gap-1.5">
          <h1 className="text-2xl font-bold text-foreground">网络诊断</h1>
          <PageHelp>从指定节点执行 Ping、TCP 连接和 DNS 解析</PageHelp>
        </div>
      </header>
      <section className="grid gap-5 lg:grid-cols-[420px_1fr]">
        <div className="space-y-4 rounded-large border border-transparent bg-content1 shadow-small dark:border-default-100 p-5">
          <Select
            label="执行节点"
            placeholder="选择在线节点"
            selectedKeys={form.nodeId ? [form.nodeId] : []}
            onSelectionChange={(keys) =>
              setForm({ ...form, nodeId: String(Array.from(keys)[0] || "") })
            }
          >
            {nodes
              .filter((node) => node.status === 1)
              .map((node) => (
                <SelectItem key={String(node.id)} textValue={node.name}>
                  {node.name} · {node.serverIp || node.ip || "未设置地址"}
                </SelectItem>
              ))}
          </Select>
          <Tabs
            fullWidth
            selectedKey={form.mode}
            onSelectionChange={(key) =>
              setForm({ ...form, mode: String(key) as Mode })
            }
          >
            <Tab key="ping" title="Ping" />
            <Tab key="tcp" title="TCP" />
            <Tab key="dns" title="DNS" />
            <Tab key="trace" title="路由" />
          </Tabs>
          <Input
            label="目标地址"
            placeholder="example.com 或 1.1.1.1"
            startContent={<Search size={16} />}
            value={form.target}
            onValueChange={(value) => setForm({ ...form, target: value })}
          />
          {form.mode === "tcp" && (
            <Input
              label="TCP 端口"
              type="number"
              value={form.port}
              onValueChange={(value) => setForm({ ...form, port: value })}
            />
          )}
          {form.mode === "dns" && (
            <Select
              label="记录类型"
              selectedKeys={[form.recordType]}
              onSelectionChange={(keys) =>
                setForm({
                  ...form,
                  recordType: String(Array.from(keys)[0] || "A"),
                })
              }
            >
              {["A", "AAAA", "CNAME", "MX", "TXT"].map((type) => (
                <SelectItem key={type}>{type}</SelectItem>
              ))}
            </Select>
          )}
          {(form.mode === "ping" || form.mode === "tcp") && (
            <Input
              label="探测次数"
              max={10}
              min={1}
              type="number"
              value={form.count}
              onValueChange={(value) => setForm({ ...form, count: value })}
            />
          )}
          <Input
            label="超时（毫秒）"
            max={30000}
            min={200}
            type="number"
            value={form.timeoutMs}
            onValueChange={(value) => setForm({ ...form, timeoutMs: value })}
          />
          <Button
            className="w-full"
            color="primary"
            isLoading={running}
            startContent={!running && <Play size={17} />}
            onPress={run}
          >
            开始诊断
          </Button>
        </div>

        <div className="min-h-[420px] overflow-hidden rounded-large border border-transparent bg-content1 shadow-small dark:border-default-100">
          <div className="flex min-h-14 items-center justify-between gap-3 border-b border-divider px-4">
            <div className="flex items-center gap-2">
              <Activity className="text-primary" size={18} />
              <span className="font-medium">诊断结果</span>
            </div>
            {result && (
              <Chip
                color={result.success ? "success" : "danger"}
                size="sm"
                startContent={
                  result.success ? (
                    <CircleCheck size={14} />
                  ) : (
                    <CircleX size={14} />
                  )
                }
                variant="flat"
              >
                {result.success ? "完成" : "失败"} · {result.durationMs} ms
              </Chip>
            )}
          </div>
          {!result && !running && (
            <div className="flex min-h-[360px] flex-col items-center justify-center gap-3 text-default-400">
              <Server size={30} />
              <span>等待执行诊断</span>
            </div>
          )}
          {running && (
            <div className="flex min-h-[360px] items-center justify-center text-default-500">
              正在等待节点返回结果
            </div>
          )}
          {result && (
            <div className="space-y-4 p-4">
              <div className="flex items-start gap-3 border-b border-divider pb-4">
                <Route className="mt-0.5 text-default-400" size={18} />
                <div>
                  <div className="font-medium break-all">{result.target}</div>
                  <div className="mt-1 text-sm text-default-500">
                    {result.summary}
                  </div>
                </div>
              </div>
              <pre className="max-h-[520px] min-h-64 overflow-auto whitespace-pre-wrap break-words rounded-md bg-default-100 p-4 font-mono text-xs leading-6">
                {result.output || result.addresses?.join("\n") || "无输出"}
              </pre>
            </div>
          )}
        </div>
      </section>
    </PageShell>
  );
}
