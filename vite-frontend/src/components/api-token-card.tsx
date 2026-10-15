import type { ApiTokenItem } from "@/api";

import { useEffect, useState } from "react";
import { Card, CardBody } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Copy, KeyRound, Trash2 } from "lucide-react";
import toast from "react-hot-toast";

import { createApiToken, getApiTokens, revokeApiToken } from "@/api";

const formatTime = (value?: number | null) =>
  value ? new Date(value).toLocaleString() : "—";

/** Personal API tokens for scripts, the event stream and AI assistants (MCP). */
export default function ApiTokenCard() {
  const [tokens, setTokens] = useState<ApiTokenItem[]>([]);
  const [name, setName] = useState("");
  const [validDays, setValidDays] = useState("90");
  const [created, setCreated] = useState("");
  const [busy, setBusy] = useState(false);
  const origin = window.location.origin;

  const load = async () => {
    const response = await getApiTokens();

    if (response.code === 0) setTokens(response.data || []);
  };

  useEffect(() => {
    load();
  }, []);

  const copy = async (value: string, label: string) => {
    await navigator.clipboard.writeText(value);
    toast.success(`${label}已复制`);
  };

  const create = async () => {
    if (!name.trim()) return toast.error("请填写令牌名称");
    setBusy(true);
    const response = await createApiToken(name.trim(), Number(validDays));

    setBusy(false);
    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "创建失败");
    setCreated(response.data.token);
    setName("");
    load();
  };

  const revoke = async (item: ApiTokenItem) => {
    if (!window.confirm(`撤销令牌「${item.name}」？使用它的脚本会立即失效。`))
      return;
    const response = await revokeApiToken(item.id);

    if (response.code !== 0) return toast.error(response.msg || "撤销失败");
    toast.success("已撤销");
    load();
  };

  return (
    <Card className="border border-transparent dark:border-default-100">
      <CardBody className="space-y-4 p-4">
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-500/20 dark:text-violet-400">
            <KeyRound className="h-5 w-5" />
          </div>
          <div>
            <h3 className="text-base font-medium text-foreground">API 令牌</h3>
            <p className="mt-1 text-xs leading-5 text-default-500">
              用于脚本调用面板接口、订阅实时事件，或让 AI 助手通过 MCP
              管理面板。令牌拥有你账号的全部权限，只在创建时显示一次。
            </p>
          </div>
        </div>

        <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
          <Input
            className="sm:flex-1"
            label="令牌名称"
            placeholder="例如：监控脚本"
            size="sm"
            value={name}
            variant="bordered"
            onValueChange={setName}
          />
          <Select
            className="sm:w-40"
            label="有效期"
            selectedKeys={[validDays]}
            size="sm"
            variant="bordered"
            onSelectionChange={(keys) =>
              setValidDays(String(Array.from(keys)[0] ?? "90"))
            }
          >
            <SelectItem key="30">30 天</SelectItem>
            <SelectItem key="90">90 天</SelectItem>
            <SelectItem key="365">1 年</SelectItem>
            <SelectItem key="0">永久</SelectItem>
          </Select>
          <Button color="primary" isLoading={busy} onPress={create}>
            创建令牌
          </Button>
        </div>

        {created && (
          <div className="space-y-2 rounded-md border border-warning-200 bg-warning-50 p-3 dark:border-warning-500/30 dark:bg-warning-500/10">
            <p className="text-xs font-medium text-warning-700 dark:text-warning-400">
              请立即复制保存，关闭后无法再次查看
            </p>
            <div className="flex items-center gap-2">
              <code className="min-w-0 flex-1 break-all rounded bg-content1 px-2 py-1.5 text-xs">
                {created}
              </code>
              <Button
                isIconOnly
                aria-label="复制令牌"
                size="sm"
                variant="flat"
                onPress={() => copy(created, "令牌")}
              >
                <Copy size={15} />
              </Button>
            </div>
            <Button size="sm" variant="light" onPress={() => setCreated("")}>
              我已保存
            </Button>
          </div>
        )}

        {tokens.length > 0 && (
          <div className="divide-y divide-divider rounded-md border border-divider">
            {tokens.map((item) => (
              <div
                key={item.id}
                className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-xs"
              >
                <div className="min-w-0">
                  <div className="font-medium text-foreground">{item.name}</div>
                  <div className="text-default-500">
                    <code>{item.tokenPrefix}…</code> · 最近使用{" "}
                    {formatTime(item.lastUsedAt)} · 到期{" "}
                    {item.expiresAt ? formatTime(item.expiresAt) : "永久"}
                  </div>
                </div>
                <Button
                  isIconOnly
                  aria-label={`撤销${item.name}`}
                  color="danger"
                  size="sm"
                  variant="light"
                  onPress={() => revoke(item)}
                >
                  <Trash2 size={15} />
                </Button>
              </div>
            ))}
          </div>
        )}

        <details className="text-xs text-default-500">
          <summary className="cursor-pointer select-none">使用方法</summary>
          <div className="mt-2 space-y-2">
            <p>在请求头带上令牌即可调用面板的所有接口：</p>
            <pre className="overflow-x-auto rounded bg-default-100 p-2">{`curl -X POST ${origin}/api/v1/forward/list \\
  -H "Authorization: Bearer cn_你的令牌" \\
  -H "Content-Type: application/json" -d '{}'`}</pre>
            <p>
              让 AI 助手管理面板（MCP）：服务地址{" "}
              <code>{origin}/api/v1/mcp</code>，以 Claude Code 为例：
            </p>
            <pre className="overflow-x-auto rounded bg-default-100 p-2">{`claude mcp add --transport http cloudnest ${origin}/api/v1/mcp \
  --header "Authorization: Bearer cn_你的令牌"`}</pre>
            <p>
              可用工具：查看节点、隧道、转发，诊断转发和隧道，暂停或恢复转发，查看线路切换记录。
            </p>
            <p>实时事件（节点上下线、转发变化、线路切换）：</p>
            <pre className="overflow-x-auto rounded bg-default-100 p-2">{`curl -N ${origin}/api/v1/events \\
  -H "Authorization: Bearer cn_你的令牌"`}</pre>
          </div>
        </details>
      </CardBody>
    </Card>
  );
}
