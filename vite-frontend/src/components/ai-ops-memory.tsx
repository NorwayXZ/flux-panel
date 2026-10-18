import { useEffect, useMemo, useState } from "react";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { FileDiff, NotebookPen, RotateCcw, SquareTerminal } from "lucide-react";
import toast from "react-hot-toast";

import {
  opsChanges,
  opsNotes,
  opsPreferences,
  opsRestoreChange,
  opsSaveNote,
  opsSavePreferences,
  type OpsChange,
  type OpsNodeNote,
} from "@/api/ai-ops";

const formatTime = (value?: number) =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";

type DiffLine = { kind: " " | "+" | "-"; text: string };

/** Line diff by longest common subsequence; files are capped at 32 KB, so this stays small. */
export function lineDiff(before: string, after: string): DiffLine[] {
  const a = before.split("\n");
  const b = after.split("\n");
  const n = a.length;
  const m = b.length;
  const table = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));

  for (let i = n - 1; i >= 0; i--)
    for (let j = m - 1; j >= 0; j--)
      table[i][j] =
        a[i] === b[j]
          ? table[i + 1][j + 1] + 1
          : Math.max(table[i + 1][j], table[i][j + 1]);
  const lines: DiffLine[] = [];
  let i = 0;
  let j = 0;

  while (i < n || j < m) {
    if (i < n && j < m && a[i] === b[j]) {
      lines.push({ kind: " ", text: a[i] });
      i++;
      j++;
    } else if (j < m && (i >= n || table[i][j + 1] > table[i + 1][j])) {
      lines.push({ kind: "+", text: b[j++] });
    } else {
      lines.push({ kind: "-", text: a[i++] });
    }
  }

  return lines;
}

function Diff({ change }: { change: OpsChange }) {
  const lines = useMemo(
    () => lineDiff(change.beforeContent || "", change.afterContent || ""),
    [change.beforeContent, change.afterContent],
  );

  return (
    <pre className="max-h-96 overflow-auto rounded-lg bg-default-50 p-2 font-mono text-xs leading-5 dark:bg-black/40">
      {lines.map((line, index) => (
        <div
          key={index}
          className={
            line.kind === "+"
              ? "bg-success-50 text-success-700 dark:bg-success-50/10"
              : line.kind === "-"
                ? "bg-danger-50 text-danger dark:bg-danger-50/10"
                : "text-default-500"
          }
        >
          {line.kind} {line.text}
        </div>
      ))}
    </pre>
  );
}

/** What the assistant remembers per node, what it changed, and conversation model settings. */
export default function AiOpsMemory() {
  const [notes, setNotes] = useState<OpsNodeNote[]>([]);
  const [drafts, setDrafts] = useState<Record<number, string>>({});
  const [changes, setChanges] = useState<OpsChange[]>([]);
  const [open, setOpen] = useState<number>();
  const [restoring, setRestoring] = useState<number>();
  const [settings, setSettings] = useState({
    models: "",
    inputPrice: "",
    outputPrice: "",
    currency: "CNY",
  });

  const load = async () => {
    const [n, c, p] = await Promise.all([
      opsNotes(),
      opsChanges(),
      opsPreferences(),
    ]);

    if (n.code === 0) setNotes(n.data);
    if (c.code === 0) setChanges(c.data);
    if (p.code === 0)
      setSettings({
        models: p.data.models || "",
        inputPrice: p.data.inputPrice || "",
        outputPrice: p.data.outputPrice || "",
        currency: p.data.currency || "CNY",
      });
  };

  useEffect(() => {
    void load();
  }, []);

  const saveSettings = async () => {
    const r = await opsSavePreferences(settings);

    if (r.code === 0) toast.success("已保存；对话页刷新后可切换模型");
    else toast.error(r.msg);
  };

  const saveNote = async (node: OpsNodeNote) => {
    const content = drafts[node.nodeId] ?? node.content ?? "";
    const r = await opsSaveNote(node.nodeId, content);

    if (r.code !== 0) return toast.error(r.msg);
    toast.success(content.trim() ? "笔记已保存" : "笔记已清空");
    setDrafts(({ [node.nodeId]: _, ...rest }) => rest);
    void load();
  };

  const restore = async (change: OpsChange) => {
    if (!window.confirm(`把 ${change.path} 还原到这次修改之前的内容？`)) return;
    setRestoring(change.id);
    const r = await opsRestoreChange(change.id);

    setRestoring(undefined);
    if (r.code !== 0) return toast.error(r.msg);
    toast.success("已还原");
    void load();
  };

  return (
    <div className="grid gap-4 pt-4 xl:grid-cols-2">
      <section className="space-y-4">
        <div className="space-y-3 rounded-2xl border border-default-200 bg-content1 p-4">
          <p className="text-sm font-medium">对话模型与费用</p>
          <Input
            description="除默认模型外，可在对话框切换的模型，用逗号分隔，例如 deepseek-chat,deepseek-reasoner"
            label="可切换模型"
            size="sm"
            value={settings.models}
            onValueChange={(models) => setSettings({ ...settings, models })}
          />
          <div className="grid grid-cols-3 gap-2">
            <Input
              label="输入单价"
              size="sm"
              value={settings.inputPrice}
              onValueChange={(inputPrice) =>
                setSettings({ ...settings, inputPrice })
              }
            />
            <Input
              label="输出单价"
              size="sm"
              value={settings.outputPrice}
              onValueChange={(outputPrice) =>
                setSettings({ ...settings, outputPrice })
              }
            />
            <Input
              label="币种"
              size="sm"
              value={settings.currency}
              onValueChange={(currency) =>
                setSettings({ ...settings, currency })
              }
            />
          </div>
          <p className="text-xs text-default-500">
            单价按每百万 token 填写；对话中每轮回复会显示 token 用量和估算费用。
          </p>
          <Button
            color="primary"
            size="sm"
            variant="flat"
            onPress={() => void saveSettings()}
          >
            保存
          </Button>
        </div>
        <div className="rounded-2xl border border-default-200 bg-content1 p-4">
          <p className="flex items-center gap-2 text-sm font-medium">
            <NotebookPen size={15} />
            节点笔记
          </p>
          <p className="mt-1 text-xs leading-5 text-default-500">
            助手会把查到的系统、服务、端口和处理过的问题记在这里，下次对话自动带上，不必从头摸索；你也可以直接修改。每台最多
            2000 字。
          </p>
          <div className="mt-3 space-y-2">
            {notes.map((node) => {
              const value = drafts[node.nodeId] ?? node.content ?? "";
              const dirty = drafts[node.nodeId] !== undefined;

              return (
                <details
                  key={node.nodeId}
                  className="rounded-xl border border-default-200 px-3 py-2"
                >
                  <summary className="cursor-pointer text-sm">
                    {node.name}
                    <span className="ml-2 text-xs text-default-400">
                      {node.content
                        ? `${node.updatedBy === "assistant" ? "助手" : "管理员"} · ${formatTime(node.updatedAt)}`
                        : "暂无笔记"}
                    </span>
                  </summary>
                  <textarea
                    aria-label={`${node.name} 的笔记`}
                    className="mt-2 min-h-28 w-full rounded-lg border border-default-200 bg-transparent p-2 text-sm leading-6 outline-none focus:border-primary"
                    maxLength={2000}
                    value={value}
                    onChange={(e) =>
                      setDrafts({ ...drafts, [node.nodeId]: e.target.value })
                    }
                  />
                  <div className="mt-1 flex items-center justify-between text-xs text-default-400">
                    <span>{value.length} / 2000</span>
                    <Button
                      isDisabled={!dirty}
                      size="sm"
                      variant="flat"
                      onPress={() => void saveNote(node)}
                    >
                      保存笔记
                    </Button>
                  </div>
                </details>
              );
            })}
          </div>
        </div>
      </section>
      <section className="rounded-2xl border border-default-200 bg-content1 p-4">
        <p className="flex items-center gap-2 text-sm font-medium">
          <FileDiff size={15} />
          改动记录
        </p>
        <p className="mt-1 text-xs leading-5 text-default-500">
          助手执行的每条命令都会记录；用“写入文件”修改的配置文件保存了修改前后的内容，可以查看差异并一键还原。
        </p>
        <div className="mt-3 space-y-2">
          {changes.length === 0 && (
            <p className="py-6 text-center text-sm text-default-500">
              还没有改动。
            </p>
          )}
          {changes.map((change) => (
            <div
              key={change.id}
              className="rounded-xl border border-default-200"
            >
              <button
                className="flex w-full items-start gap-2 px-3 py-2 text-left"
                type="button"
                onClick={() =>
                  setOpen(open === change.id ? undefined : change.id)
                }
              >
                {change.kind === "file" ? (
                  <FileDiff
                    className="mt-0.5 shrink-0 text-primary"
                    size={15}
                  />
                ) : (
                  <SquareTerminal
                    className="mt-0.5 shrink-0 text-default-400"
                    size={15}
                  />
                )}
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm">
                    {change.kind === "file" ? change.path : change.summary}
                  </span>
                  <span className="block text-xs text-default-400">
                    {change.nodeName || `节点 ${change.nodeId}`} ·{" "}
                    {formatTime(change.createdAt)} ·{" "}
                    {change.source === "chat"
                      ? "对话"
                      : change.source === "task"
                        ? "定时任务"
                        : change.source === "restore"
                          ? "还原"
                          : change.source}
                    {change.kind === "command" &&
                    change.exitCode !== undefined &&
                    change.exitCode !== null
                      ? ` · 退出码 ${change.exitCode}`
                      : ""}
                    {change.restoredAt
                      ? ` · 已于 ${formatTime(change.restoredAt)} 还原`
                      : ""}
                  </span>
                </span>
              </button>
              {open === change.id && (
                <div className="space-y-2 border-t border-default-200 px-3 py-2">
                  {change.kind === "file" ? (
                    <>
                      <p className="text-xs text-default-500">
                        {change.summary}
                      </p>
                      <Diff change={change} />
                      {change.source !== "restore" && !change.restoredAt && (
                        <Button
                          color="warning"
                          isLoading={restoring === change.id}
                          size="sm"
                          startContent={<RotateCcw size={14} />}
                          variant="flat"
                          onPress={() => void restore(change)}
                        >
                          还原到修改前
                        </Button>
                      )}
                    </>
                  ) : (
                    <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-black/85 p-3 font-mono text-xs leading-5 text-zinc-100">
                      {`$ ${change.command || ""}\n${change.output || ""}`}
                    </pre>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
