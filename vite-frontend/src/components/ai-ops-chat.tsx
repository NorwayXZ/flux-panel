import { useEffect, useRef, useState } from "react";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Switch } from "@heroui/switch";
import { Bot, Plus, Send, Square, Trash2, UserRound } from "lucide-react";
import toast from "react-hot-toast";
import {
  opsChatList,
  opsChatDetail,
  opsChatDelete,
  opsChatStop,
  type OpsOverview,
  type OpsConfig,
  type OpsChatEvent,
  type OpsChatTurn,
} from "@/api/ai-ops";
import { streamOpsChat } from "@/api/ai-chat-stream";

const policy = (): OpsConfig => ({
  name: "实时对话",
  prompt: "",
  nodeIds: [],
  cron: "0 0 3 * * *",
  timezone: "Asia/Shanghai",
  mode: "inspect",
  retentionDays: 7,
  maxJournalMb: 500,
  serviceAllowlist: [],
  allowLogCleanup: false,
});
const inputClass =
  "w-full rounded-lg border border-default-200 bg-content1 p-2 text-sm";
function Events({ events }: { events: OpsChatEvent[] }) {
  return (
    <div className="mt-3 space-y-2">
      {events
        .filter((e) => e.type === "tool")
        .map((e, i) => (
          <details
            key={i}
            className="rounded-lg border border-default-200 p-3 text-xs"
          >
            <summary className="cursor-pointer">
              {e.result?.success ? "✓" : "!"} {e.call?.function?.name} ·{" "}
              {e.result?.success ? "已执行" : "执行失败"}
            </summary>
            <pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-all">
              {JSON.stringify(
                { arguments: e.call?.function?.arguments, result: e.result },
                null,
                2,
              )}
            </pre>
          </details>
        ))}
    </div>
  );
}
function parseEvents(turn: OpsChatTurn): OpsChatEvent[] {
  try {
    return JSON.parse(turn.events || "[]");
  } catch {
    return [];
  }
}
function Turn({ turn }: { turn: OpsChatTurn }) {
  const events = parseEvents(turn);
  const reply = events
    .filter((e) => e.type === "assistant")
    .map((e) => e.message?.content || "")
    .filter(Boolean)
    .join("\n\n");
  return (
    <div className="space-y-3">
      <div className="ml-6 rounded-2xl bg-primary-50 p-4 text-sm">
        <span className="mb-2 flex items-center gap-2 font-medium">
          <UserRound size={16} />你
        </span>
        <p className="whitespace-pre-wrap break-words">{turn.userContent}</p>
      </div>
      <div className="mr-2 rounded-2xl border border-default-200 p-4 text-sm">
        <span className="mb-2 flex items-center gap-2 font-medium">
          <Bot size={16} />
          AI 助手
        </span>
        <p className="whitespace-pre-wrap break-words">
          {reply ||
            (turn.status === "running" ? "正在回复…" : "本轮没有完整回复")}
        </p>
        <Events events={events} />
        {events
          .filter((e) => e.type === "error")
          .map((e, i) => (
            <p key={i} role="alert" className="mt-2 text-danger">
              {e.message}
            </p>
          ))}
        {turn.status !== "success" && (
          <p className="mt-2 text-xs text-default-500">
            状态：
            {{
              running: "进行中",
              stopped: "已停止",
              failed: "失败",
              partial: "部分工具失败",
              interrupted: "服务重启，对话中断",
            }[turn.status] || turn.status}
          </p>
        )}
      </div>
    </div>
  );
}
export default function AiOpsChat({
  overview,
  onSettings,
}: {
  overview?: OpsOverview;
  onSettings: () => void;
}) {
  const [chats, setChats] = useState<{ id: number; title: string }[]>([]),
    [chatId, setChatId] = useState<number>();
  const [turns, setTurns] = useState<OpsChatTurn[]>([]),
    [message, setMessage] = useState("");
  const [config, setConfig] = useState<OpsConfig>(policy),
    [services, setServices] = useState("");
  const [running, setRunning] = useState(false),
    [liveText, setLiveText] = useState(""),
    [livePrompt, setLivePrompt] = useState("");
  const [events, setEvents] = useState<OpsChatEvent[]>([]),
    [progress, setProgress] = useState("");
  const [error, setError] = useState("");
  const [permissionsOpen, setPermissionsOpen] = useState(
    () =>
      typeof window.matchMedia === "function" &&
      window.matchMedia("(min-width: 1024px)").matches,
  );
  const controller = useRef<AbortController>();
  const messagesBox = useRef<HTMLDivElement>(null);
  const historyRunning = turns.some((turn) => turn.status === "running");
  const configured = !!overview?.settings.keyConfigured;
  const refreshChats = async () => {
    const r = await opsChatList();
    if (r.code === 0) setChats(r.data);
  };
  useEffect(() => {
    void refreshChats();
    return () => {
      if (controller.current) {
        controller.current.abort();
        void opsChatStop();
      }
    };
  }, []);
  useEffect(() => {
    if (messagesBox.current)
      messagesBox.current.scrollTop = messagesBox.current.scrollHeight;
  }, [liveText, events.length, turns.length]);
  useEffect(() => {
    if (running || !chatId || !historyRunning) return;
    let active = true;
    const timer = window.setInterval(() => {
      void opsChatDetail(chatId).then((r) => {
        if (active && r.code === 0) setTurns(r.data.turns);
      });
    }, 1500);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [running, chatId, historyRunning]);
  const load = async (id: number) => {
    const r = await opsChatDetail(id);
    if (r.code === 0) {
      setChatId(id);
      setTurns(r.data.turns);
      setError("");
      setLiveText("");
      setLivePrompt("");
      setEvents([]);
    } else toast.error(r.msg);
  };
  const stop = () => {
    controller.current?.abort();
    void opsChatStop();
  };
  const send = async (authorizedUpgrade = false) => {
    const upgradeRequest = "我授权升级所选节点，允许升级造成短暂连接中断。先检查当前升级状态，已有任务运行时先跟踪，不要重复提交；执行后查询结果，未完成请明确说明。";
    const text = authorizedUpgrade
      ? `${message.trim() || "请继续处理所选节点的 Agent 升级问题。"}\n${upgradeRequest}`
      : message.trim();
    if (!text || running || historyRunning || !configured) return;
    const turnConfig = authorizedUpgrade
      ? { ...config, mode: "repair" as const, allowAgentUpgrade: true, upgradeOnly: true }
      : config;
    if (authorizedUpgrade) setConfig(turnConfig);
    setMessage("");
    setLivePrompt(text);
    setLiveText("");
    setError("");
    setEvents([]);
    setRunning(true);
    setProgress("正在连接模型");
    const request = new AbortController();
    controller.current = request;
    let id = chatId;
    try {
      await streamOpsChat(
        {
          chatId,
          message: text,
          config: {
            ...turnConfig,
            prompt: text,
            serviceAllowlist: services
              .split(",")
              .map((s) => s.trim())
              .filter(Boolean),
          },
        },
        request.signal,
        (event) => {
          if (event.type === "meta") {
            id = event.chatId;
            setChatId(id);
          }
          if (event.type === "delta") {
            setLiveText((previous) => previous + (event.text || ""));
            if (event.text?.trim()) setProgress("正在回复");
          }
          if (event.type === "status") setProgress(String(event.message));
          if (event.type === "tool")
            setEvents((previous) => [...previous, event]);
          if (event.type === "error") setError(String(event.message));
        },
      );
    } catch (e) {
      if (request.signal.aborted)
        setError("回复已停止；已经执行的工具不会撤销。");
      else setError(e instanceof Error ? e.message : "对话失败");
    } finally {
      controller.current = undefined;
      setRunning(false);
      setProgress("");
      void refreshChats();
      if (id) {
        const r = await opsChatDetail(id);
        if (r.code === 0) {
          setTurns(r.data.turns);
          setLivePrompt("");
          setLiveText("");
          setEvents([]);
        }
      }
    }
  };
  return (
    <div className="grid gap-4 pt-4 lg:grid-cols-[260px_minmax(0,1fr)]">
      <aside className="space-y-4">
        <div className="rounded-xl border border-default-200 p-3">
          <Button
            fullWidth
            variant="flat"
            startContent={<Plus size={16} />}
            isDisabled={running}
            onPress={() => {
              setChatId(undefined);
              setTurns([]);
              setLivePrompt("");
              setLiveText("");
              setError("");
            }}
          >
            新对话
          </Button>
          <select
            aria-label="历史对话"
            className={`${inputClass} mt-3`}
            value={chatId || ""}
            disabled={running}
            onChange={(e) => void load(Number(e.target.value))}
          >
            <option value="" disabled>
              选择历史对话
            </option>
            {chats.map((c) => (
              <option key={c.id} value={c.id}>
                {c.title}
              </option>
            ))}
          </select>
          {chatId && (
            <Button
              size="sm"
              variant="light"
              color="danger"
              isDisabled={running}
              startContent={<Trash2 size={14} />}
              onPress={() => {
                if (window.confirm("删除当前会话及聊天记录？"))
                  void opsChatDelete(chatId).then((r) => {
                    if (r.code === 0) {
                      setChatId(undefined);
                      setTurns([]);
                      void refreshChats();
                    } else toast.error(r.msg);
                  });
              }}
            >
              删除会话
            </Button>
          )}
        </div>
        <details
          className="rounded-xl border border-default-200 p-3"
          open={permissionsOpen}
          onToggle={(e) => setPermissionsOpen(e.currentTarget.open)}
        >
          <summary className="cursor-pointer text-sm font-medium">
            本轮节点与权限
          </summary>
          <div className="mt-3 space-y-3">
            <p className="text-xs text-default-500">
              不选节点也可以聊天。旧节点可先查升级记录和安装日志；执行清理、重启或升级需切换到允许维护，升级还需单独勾选。权限每轮生效。
            </p>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="flat"
                isDisabled={running}
                onPress={() =>
                  setConfig({
                    ...config,
                    nodeIds: overview?.nodes.map((n) => n.id) || [],
                  })
                }
              >
                全选节点
              </Button>
              <Button
                size="sm"
                variant="light"
                isDisabled={running}
                onPress={() => setConfig({ ...config, nodeIds: [] })}
              >
                清空
              </Button>
            </div>
            <div className="max-h-40 space-y-2 overflow-auto">
              {overview?.nodes.map((node) => (
                <label
                  key={node.id}
                  className="flex items-center gap-2 text-xs"
                >
                  <input
                    type="checkbox"
                    aria-label={node.name}
                    checked={config.nodeIds.includes(node.id)}
                    disabled={running}
                    onChange={(e) =>
                      setConfig({
                        ...config,
                        nodeIds: e.target.checked
                          ? [...config.nodeIds, node.id]
                          : config.nodeIds.filter((id) => id !== node.id),
                      })
                    }
                  />
                  {node.name}
                  <span className="text-default-400">
                    {node.version || "版本未知"}
                  </span>
                </label>
              ))}
            </div>
            <select
              aria-label="对话执行权限"
              className={inputClass}
              disabled={running}
              value={config.mode}
              onChange={(e) =>
                setConfig({
                  ...config,
                  mode: e.target.value as OpsConfig["mode"],
                  upgradeOnly: false,
                })
              }
            >
              <option value="inspect">只读检查</option>
              <option value="repair">允许维护</option>
            </select>
            <Switch
              size="sm"
              isSelected={!!config.allowTerminal}
              isDisabled={running}
              onValueChange={(enabled) => setConfig({
                ...config, allowTerminal: enabled,
                ...(enabled ? { mode: "repair", upgradeOnly: false, allowAgentUpgrade: true } : {}),
              })}
            >
              完整服务器管理（终端命令、安装服务）
            </Switch>
            <Switch size="sm" isSelected={!!config.allowPanelRead} isDisabled={running}
              onValueChange={(enabled) => setConfig({ ...config, allowPanelRead: enabled })}>
              读取面板功能、全局告警和所选节点线路
            </Switch>
            {config.allowTerminal && (
              <p className="text-xs text-warning-700">
                已授权助手以节点 Agent 的系统身份执行命令，通常为 root，可安装、修改配置或删除文件。仅作用于本轮所选节点；刷新页面后需重新开启。
              </p>
            )}
            <Input
              size="sm"
              label="服务白名单（逗号分隔）"
              value={services}
              isDisabled={running}
              onValueChange={setServices}
            />
            {config.mode === "repair" && (
              <>
                <Input
                  size="sm"
                  type="number"
                  label="日志保留天数"
                  value={String(config.retentionDays)}
                  isDisabled={running}
                  onValueChange={(v) =>
                    setConfig({ ...config, retentionDays: Number(v) })
                  }
                />
                <Input
                  size="sm"
                  type="number"
                  label="Journal 上限（MB）"
                  value={String(config.maxJournalMb)}
                  isDisabled={running}
                  onValueChange={(v) =>
                    setConfig({ ...config, maxJournalMb: Number(v) })
                  }
                />
                <Switch
                  size="sm"
                  isSelected={!!config.allowLogCleanup}
                  isDisabled={running}
                  onValueChange={(v) =>
                    setConfig({ ...config, allowLogCleanup: v })
                  }
                >
                  允许清理旧轮转日志
                </Switch>
                <Switch
                  size="sm"
                  isSelected={!!config.allowAgentUpgrade}
                  isDisabled={running}
                  onValueChange={(v) =>
                    setConfig({ ...config, allowAgentUpgrade: v })
                  }
                >
                  允许升级所选节点 Agent
                </Switch>
                <p className="text-xs text-warning-700">
                  清理会删除符合保留期限的日志；服务重启或 Agent
                  升级可能中断连接。
                </p>
              </>
            )}
          </div>
        </details>
      </aside>
      <section className="min-w-0 rounded-2xl border border-default-200 bg-content1">
        <div
          ref={messagesBox}
          aria-live="polite"
          aria-label="聊天记录"
          className="h-[42vh] min-h-48 sm:h-[min(60vh,680px)] sm:min-h-72 space-y-5 overflow-y-auto p-4 sm:p-5"
        >
          {!turns.length && !livePrompt && (
            <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
              <Bot className="h-9 w-9 text-primary" />
              <h2 className="font-semibold">直接和运维助手对话</h2>
              <p className="max-w-sm text-sm text-default-500">
                可以先问运维问题；选择节点后，试试“检查这些服务器的磁盘使用率”。
              </p>
              {!configured && (
                <Button color="primary" variant="flat" onPress={onSettings}>
                  先配置模型
                </Button>
              )}
            </div>
          )}
          {turns.map((turn) => (
            <Turn key={turn.id} turn={turn} />
          ))}
          {livePrompt && (
            <div className="space-y-3">
              <div className="ml-6 rounded-2xl bg-primary-50 p-4 text-sm whitespace-pre-wrap">
                {livePrompt}
              </div>
              <div className="rounded-2xl border border-default-200 p-4 text-sm">
                <span className="mb-2 flex items-center gap-2 font-medium">
                  <Bot size={16} />
                  AI 助手
                </span>
                <p className="whitespace-pre-wrap break-words">
                  {liveText || progress}
                </p>
                <Events events={events} />
                {running && (
                  <p role="status" className="mt-3 text-xs text-primary">
                    {progress}
                  </p>
                )}
              </div>
            </div>
          )}
          <div />
        </div>
        <div className="space-y-2 border-t border-default-200 p-3 sm:p-4">
          {config.nodeIds.length > 0 &&
            (config.mode !== "repair" || !config.allowAgentUpgrade) && (
              <div className="rounded-lg bg-default-100 p-3 text-sm">
                <p className="mb-2 text-default-600">
                  当前未授权升级。点击后，助手可检查并升级已选的 {config.nodeIds.length} 个节点、跟踪结果；升级可能短暂中断连接。不会额外授权清理日志或其他节点。
                </p>
                <Button
                  size="sm"
                  color="warning"
                  variant="flat"
                  isDisabled={!configured || running || historyRunning}
                  onPress={() => void send(true)}
                >
                  授权升级并继续
                </Button>
              </div>
            )}
          {error && (
            <p role="alert" className="text-sm text-danger">
              {error}
            </p>
          )}
          <textarea
            aria-label="聊天消息"
            className={`${inputClass} min-h-24 resize-y`}
            placeholder={
              configured
                ? "输入问题或运维要求；Enter 发送，Shift+Enter 换行"
                : "请先配置支持流式回复和工具调用的模型"
            }
            value={message}
            maxLength={8000}
            disabled={!configured || running}
            onChange={(e) => setMessage(e.target.value)}
            onKeyDown={(e) => {
              if (
                e.key === "Enter" &&
                !e.shiftKey &&
                !e.nativeEvent.isComposing
              ) {
                e.preventDefault();
                void send();
              }
            }}
          />
          <div className="flex items-center justify-between gap-2">
            <p className="text-xs text-default-500">
              {config.nodeIds.length
                ? `已选择 ${config.nodeIds.length} 个节点`
                : "普通对话"}{" "}
              · {config.mode === "inspect" ? "只读" : config.upgradeOnly ? "仅允许 Agent 升级" : "允许维护"}
            </p>
            {running || historyRunning ? (
              <Button
                color="danger"
                variant="flat"
                startContent={<Square size={14} />}
                onPress={stop}
              >
                停止回复
              </Button>
            ) : (
              <Button
                color="primary"
                startContent={<Send size={16} />}
                isDisabled={!configured || !message.trim()}
                onPress={() => void send()}
              >
                发送
              </Button>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
