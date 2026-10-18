import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Switch } from "@heroui/switch";
import {
  AlertCircle,
  ArrowUp,
  CheckCircle2,
  ChevronRight,
  Copy,
  MessageSquare,
  Plus,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Square,
  Trash2,
} from "lucide-react";
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
import { AssistantMarkdown, copyText } from "@/components/ai-chat-markdown";

const permissionStorageKey = () =>
  `ai-ops-chat-permissions-v1:${localStorage.getItem("name") || "admin"}`;
const policy = (): OpsConfig => {
  const defaults: OpsConfig = {
    name: "实时对话",
    prompt: "",
    nodeIds: [],
    cron: "0 0 3 * * *",
    timezone: "Asia/Shanghai",
    mode: "repair",
    allowTerminal: true,
    allowPanelRead: true,
    allowAgentUpgrade: true,
    retentionDays: 7,
    maxJournalMb: 500,
    serviceAllowlist: [],
    allowLogCleanup: false,
  };

  try {
    const saved = JSON.parse(
      localStorage.getItem(permissionStorageKey()) || "null",
    );

    return saved && typeof saved === "object"
      ? {
          ...defaults,
          ...saved,
          nodeIds: Array.isArray(saved.nodeIds) ? saved.nodeIds : [],
        }
      : defaults;
  } catch {
    return defaults;
  }
};
const inputClass =
  "w-full rounded-xl border border-default-200 bg-content1 px-3 py-2 text-sm outline-none transition focus:border-primary";
const toolLabels: Record<string, string> = {
  inspect_panel: "读取面板",
  execute_command: "运行命令",
  inspect_disk: "检查磁盘",
  inspect_service: "检查服务",
  restart_service: "重启服务",
  vacuum_journal: "清理 Journal",
  cleanup_rotated_logs: "清理旧日志",
  inspect_agent_upgrade: "检查 Agent 升级",
  upgrade_agent: "升级 Agent",
};
const suggestions = [
  "检查所选服务器的磁盘、内存和负载",
  "查看面板当前的告警和异常线路",
  "检查所选节点的 Agent 是否需要升级",
  "nginx 服务状态正常吗？有问题就说明原因",
];
const statusLabels: Record<string, string> = {
  running: "进行中",
  stopped: "已停止",
  failed: "失败",
  partial: "部分工具失败",
  interrupted: "服务重启，对话中断",
};

function parseArgs(raw: unknown): Record<string, any> {
  if (raw && typeof raw === "object") return raw as Record<string, any>;
  try {
    return JSON.parse(String(raw || "{}"));
  } catch {
    return {};
  }
}
function ToolRow({
  event,
  nodeName,
}: {
  event: OpsChatEvent;
  nodeName: (id: number) => string;
}) {
  const name: string = event.call?.function?.name || "tool";
  const args = parseArgs(event.call?.function?.arguments);
  const ok = !!event.result?.success;
  const data = event.result?.data;
  const target = args.nodeId
    ? nodeName(Number(args.nodeId))
    : args.section || "";
  const detail = args.command || args.service || "";
  const command =
    name === "execute_command" && data && typeof data === "object";

  return (
    <details className="group rounded-xl border border-default-200 bg-default-50/60 text-[13px] dark:bg-default-50/30">
      <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2 [&::-webkit-details-marker]:hidden">
        {ok ? (
          <CheckCircle2 className="shrink-0 text-success" size={15} />
        ) : (
          <AlertCircle className="shrink-0 text-danger" size={15} />
        )}
        <span className="shrink-0 font-medium">{toolLabels[name] || name}</span>
        {target && <span className="shrink-0 text-default-500">{target}</span>}
        {detail && (
          <code className="min-w-0 truncate font-mono text-xs text-default-500">
            {detail}
          </code>
        )}
        <span className="ml-auto flex shrink-0 items-center gap-2 text-xs text-default-400">
          <span className="hidden font-mono sm:inline">{name}</span>
          {ok ? "已执行" : "执行失败"}
          <ChevronRight
            className="transition-transform group-open:rotate-90"
            size={14}
          />
        </span>
      </summary>
      <div className="border-t border-default-200 px-3 py-2">
        {command ? (
          <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-black/85 p-3 font-mono text-xs leading-5 text-zinc-100">
            {`$ ${args.command || ""}\n${String((data as any).output ?? "").trimEnd()}\n`}
            <span className="text-zinc-400">{`exit ${(data as any).exitCode}`}</span>
          </pre>
        ) : (
          <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all font-mono text-xs leading-5 text-default-600">
            {JSON.stringify({ arguments: args, result: event.result }, null, 2)}
          </pre>
        )}
      </div>
    </details>
  );
}
function Events({
  events,
  nodeName,
}: {
  events: OpsChatEvent[];
  nodeName: (id: number) => string;
}) {
  const tools = events.filter((e) => e.type === "tool");

  if (!tools.length) return null;

  return (
    <div className="my-3 space-y-1.5">
      {tools.map((e, i) => (
        <ToolRow key={i} event={e} nodeName={nodeName} />
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
function UserMessage({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl rounded-br-md bg-default-100 px-4 py-2.5 text-[15px] leading-7 text-foreground">
        {text}
      </div>
    </div>
  );
}
function AssistantFrame({
  children,
  reply,
  footer,
}: {
  children: ReactNode;
  reply?: string;
  footer?: ReactNode;
}) {
  return (
    <div className="group/turn flex gap-3">
      <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
        <Sparkles size={15} />
      </div>
      <div className="min-w-0 flex-1">
        {children}
        <div className="mt-2 flex min-h-6 items-center gap-3 text-xs text-default-400">
          {footer}
          {reply && (
            <button
              type="button"
              className="flex items-center gap-1 rounded-md px-1.5 py-0.5 opacity-70 transition hover:bg-default-100 hover:text-foreground hover:opacity-100 sm:opacity-0 sm:group-hover/turn:opacity-100"
              onClick={() =>
                void copyText(reply).then((ok) =>
                  ok ? toast.success("已复制回复") : toast.error("复制失败"),
                )
              }
            >
              <Copy size={13} />
              复制
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
function Turn({
  turn,
  nodeName,
}: {
  turn: OpsChatTurn;
  nodeName: (id: number) => string;
}) {
  const events = parseEvents(turn);
  const reply = events
    .filter((e) => e.type === "assistant")
    .map((e) => e.message?.content || "")
    .filter(Boolean)
    .join("\n\n");

  return (
    <div className="space-y-5">
      <UserMessage text={turn.userContent} />
      <AssistantFrame
        reply={reply}
        footer={
          turn.status !== "success" && (
            <span className="rounded-full bg-default-100 px-2 py-0.5 text-default-500">
              状态：{statusLabels[turn.status] || turn.status}
            </span>
          )
        }
      >
        <Events events={events} nodeName={nodeName} />
        {reply ? (
          <AssistantMarkdown content={reply} />
        ) : (
          <p className="ai-prose text-default-500">
            {turn.status === "running" ? "正在回复…" : "本轮没有完整回复"}
          </p>
        )}
        {events
          .filter((e) => e.type === "error")
          .map((e, i) => (
            <p
              key={i}
              role="alert"
              className="mt-3 rounded-xl bg-danger-50 px-3 py-2 text-sm text-danger"
            >
              {e.message}
            </p>
          ))}
      </AssistantFrame>
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
  const wideQuery = () =>
    typeof window.matchMedia === "function" &&
    window.matchMedia("(min-width: 1024px)").matches;
  const [wide, setWide] = useState(wideQuery);

  useEffect(() => {
    const update = () => setWide(wideQuery());

    window.addEventListener("resize", update);

    return () => window.removeEventListener("resize", update);
  }, []);
  const controller = useRef<AbortController>();
  const messagesBox = useRef<HTMLDivElement>(null);
  const composer = useRef<HTMLTextAreaElement>(null);
  const layout = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState<number>();

  // Fill the rest of the window below the page header, so the composer stays visible.
  useLayoutEffect(() => {
    const measure = () => {
      const top = layout.current?.getBoundingClientRect().top;

      if (top === undefined || !wideQuery()) return setHeight(undefined);
      setHeight(
        Math.max(520, window.innerHeight - (top + window.scrollY) - 24),
      );
    };

    measure();
    window.addEventListener("resize", measure);

    return () => window.removeEventListener("resize", measure);
  }, []);
  const nodeName = (id: number) =>
    overview?.nodes.find((node) => node.id === id)?.name || `节点 ${id}`;
  const historyRunning = turns.some((turn) => turn.status === "running");
  const configured = !!overview?.settings.keyConfigured;

  useEffect(() => {
    localStorage.setItem(permissionStorageKey(), JSON.stringify(config));
  }, [config]);
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
    // Grow the composer with its content, like a chat app, up to a limit.
    const box = composer.current;

    if (!box) return;
    box.style.height = "auto";
    box.style.height = `${Math.min(box.scrollHeight, 240)}px`;
  }, [message]);
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
    const upgradeRequest =
      "我授权升级所选节点，允许升级造成短暂连接中断。先检查当前升级状态，已有任务运行时先跟踪，不要重复提交；执行后查询结果，未完成请明确说明。";
    const text = authorizedUpgrade
      ? `${message.trim() || "请继续处理所选节点的 Agent 升级问题。"}\n${upgradeRequest}`
      : message.trim();

    if (!text || running || historyRunning || !configured) return;
    const turnConfig = authorizedUpgrade
      ? {
          ...config,
          mode: "repair" as const,
          allowAgentUpgrade: true,
          upgradeOnly: true,
        }
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
  const newChat = () => {
    setChatId(undefined);
    setTurns([]);
    setLivePrompt("");
    setLiveText("");
    setError("");
  };
  const deleteChat = (id: number) => {
    if (window.confirm("删除当前会话及聊天记录？"))
      void opsChatDelete(id).then((r) => {
        if (r.code === 0) {
          if (id === chatId) newChat();
          void refreshChats();
        } else toast.error(r.msg);
      });
  };
  const modeLabel =
    config.mode === "inspect"
      ? "只读"
      : config.upgradeOnly
        ? "仅允许 Agent 升级"
        : "允许维护";

  return (
    <div
      ref={layout}
      className="grid gap-4 pt-4 lg:grid-cols-[272px_minmax(0,1fr)]"
    >
      <aside
        className="flex min-w-0 flex-col gap-3"
        style={height ? { maxHeight: height } : undefined}
      >
        <div className="rounded-2xl border border-default-200 bg-content1 p-2">
          <Button
            fullWidth
            className="justify-start"
            variant="light"
            startContent={<Plus size={16} />}
            isDisabled={running}
            onPress={newChat}
          >
            新对话
          </Button>
          {!wide && (
            <select
              aria-label="历史对话"
              className={`${inputClass} mt-2`}
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
          )}
          {wide && (
            <nav
              aria-label="会话列表"
              className="mt-1 max-h-64 overflow-y-auto"
            >
              {chats.length > 0 && (
                <p className="px-3 pb-1 pt-2 text-xs text-default-400">最近</p>
              )}
              {chats.map((c) => (
                <div
                  key={c.id}
                  className={`group flex items-center rounded-xl text-sm transition ${c.id === chatId ? "bg-default-100 text-foreground" : "text-default-600 hover:bg-default-100/60"}`}
                >
                  <button
                    type="button"
                    disabled={running}
                    className="flex min-w-0 flex-1 items-center gap-2 px-3 py-2 text-left"
                    onClick={() => void load(c.id)}
                  >
                    <MessageSquare
                      size={14}
                      className="shrink-0 text-default-400"
                    />
                    <span className="truncate">{c.title}</span>
                  </button>
                  <button
                    type="button"
                    aria-label={`删除会话 ${c.title}`}
                    disabled={running}
                    className="mr-1 rounded-lg p-1.5 text-default-400 opacity-0 transition hover:bg-danger-50 hover:text-danger group-hover:opacity-100"
                    onClick={() => deleteChat(c.id)}
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              ))}
            </nav>
          )}
          {chatId && !wide && (
            <Button
              size="sm"
              variant="light"
              color="danger"
              isDisabled={running}
              startContent={<Trash2 size={14} />}
              onPress={() => deleteChat(chatId)}
            >
              删除会话
            </Button>
          )}
        </div>
        <details
          className="min-h-0 overflow-y-auto rounded-2xl border border-default-200 bg-content1 p-3"
          open={permissionsOpen}
          onToggle={(e) => setPermissionsOpen(e.currentTarget.open)}
        >
          <summary className="flex cursor-pointer list-none items-center gap-2 text-sm font-medium [&::-webkit-details-marker]:hidden">
            <SlidersHorizontal size={15} className="text-default-500" />
            本轮节点与权限
            <span className="ml-auto text-xs font-normal text-default-400">
              {config.nodeIds.length
                ? `${config.nodeIds.length} 个节点`
                : "未选节点"}
            </span>
          </summary>
          <div className="mt-3 space-y-3">
            <p className="text-xs leading-5 text-default-500">
              管理员默认开启完整服务器管理。权限和所选节点会保存；需要限制操作时可切换只读或关闭终端管理。不选节点时只聊天。
            </p>
            <div className="flex items-center gap-2">
              <span className="text-xs font-medium text-default-600">节点</span>
              <Button
                className="ml-auto"
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
            <div className="max-h-44 space-y-0.5 overflow-auto rounded-xl border border-default-100 p-1">
              {overview?.nodes.map((node) => (
                <label
                  key={node.id}
                  className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-xs hover:bg-default-100"
                >
                  <input
                    type="checkbox"
                    className="accent-primary"
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
                  <span className="truncate">{node.name}</span>
                  <span className="ml-auto shrink-0 font-mono text-[11px] text-default-400">
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
              onValueChange={(enabled) =>
                setConfig({
                  ...config,
                  allowTerminal: enabled,
                  ...(enabled
                    ? {
                        mode: "repair",
                        upgradeOnly: false,
                        allowAgentUpgrade: true,
                      }
                    : {}),
                })
              }
            >
              <span className="text-sm">
                完整服务器管理（终端命令、安装服务）
              </span>
            </Switch>
            <Switch
              size="sm"
              isSelected={!!config.allowPanelRead}
              isDisabled={running}
              onValueChange={(enabled) =>
                setConfig({ ...config, allowPanelRead: enabled })
              }
            >
              <span className="text-sm">
                读取面板功能、全局告警和所选节点线路
              </span>
            </Switch>
            {config.allowTerminal && (
              <p className="rounded-xl bg-warning-50 px-3 py-2 text-xs leading-5 text-warning-700">
                已授权助手以节点 Agent 的系统身份执行命令，通常为
                root，可安装、修改配置或删除文件。仅作用于所选节点；刷新后保留，可随时关闭或切回只读。
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
                <div className="grid grid-cols-2 gap-2">
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
                </div>
                <Switch
                  size="sm"
                  isSelected={!!config.allowLogCleanup}
                  isDisabled={running}
                  onValueChange={(v) =>
                    setConfig({ ...config, allowLogCleanup: v })
                  }
                >
                  <span className="text-sm">允许清理旧轮转日志</span>
                </Switch>
                <Switch
                  size="sm"
                  isSelected={!!config.allowAgentUpgrade}
                  isDisabled={running}
                  onValueChange={(v) =>
                    setConfig({ ...config, allowAgentUpgrade: v })
                  }
                >
                  <span className="text-sm">允许升级所选节点 Agent</span>
                </Switch>
                <p className="text-xs leading-5 text-warning-700">
                  清理会删除符合保留期限的日志；服务重启或 Agent
                  升级可能中断连接。
                </p>
              </>
            )}
          </div>
        </details>
      </aside>
      <section
        className="flex h-[75dvh] min-h-[520px] min-w-0 flex-col overflow-hidden rounded-2xl border border-default-200 bg-content1"
        style={height ? { height } : undefined}
      >
        <div
          ref={messagesBox}
          aria-live="polite"
          aria-label="聊天记录"
          className="min-h-0 flex-1 overflow-y-auto"
        >
          <div className="mx-auto w-full max-w-3xl space-y-8 px-4 py-6 sm:px-8 sm:py-8">
            {!turns.length && !livePrompt && (
              <div className="flex min-h-[40vh] flex-col items-center justify-center gap-4 text-center">
                <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-primary/10 text-primary">
                  <Sparkles size={22} />
                </div>
                <h2 className="ai-prose text-2xl font-normal">
                  直接和运维助手对话
                </h2>
                <p className="max-w-md text-sm leading-6 text-default-500">
                  可以先问运维问题；选择节点后，试试“检查这些服务器的磁盘使用率”。
                </p>
                {configured ? (
                  <div className="mt-2 grid w-full max-w-xl gap-2 sm:grid-cols-2">
                    {suggestions.map((text) => (
                      <button
                        key={text}
                        type="button"
                        className="rounded-xl border border-default-200 px-3 py-2.5 text-left text-sm text-default-600 transition hover:border-default-300 hover:bg-default-50 hover:text-foreground"
                        onClick={() => {
                          setMessage(text);
                          composer.current?.focus();
                        }}
                      >
                        {text}
                      </button>
                    ))}
                  </div>
                ) : (
                  <Button color="primary" variant="flat" onPress={onSettings}>
                    先配置模型
                  </Button>
                )}
              </div>
            )}
            {turns.map((turn) => (
              <Turn key={turn.id} turn={turn} nodeName={nodeName} />
            ))}
            {livePrompt && (
              <div className="space-y-5">
                <UserMessage text={livePrompt} />
                <AssistantFrame
                  footer={
                    running && (
                      <span
                        role="status"
                        className="flex items-center gap-2 text-primary"
                      >
                        <span className="ai-pulse h-1.5 w-1.5 rounded-full bg-primary" />
                        {progress}
                      </span>
                    )
                  }
                >
                  <Events events={events} nodeName={nodeName} />
                  {liveText ? (
                    <AssistantMarkdown content={liveText} />
                  ) : (
                    <p className="ai-prose text-default-400">{progress}</p>
                  )}
                </AssistantFrame>
              </div>
            )}
          </div>
        </div>
        <div className="shrink-0 px-3 pb-3 sm:px-6 sm:pb-5">
          <div className="mx-auto w-full max-w-3xl space-y-2">
            {config.nodeIds.length > 0 &&
              (config.mode !== "repair" || !config.allowAgentUpgrade) && (
                <div className="flex flex-wrap items-center gap-3 rounded-xl border border-warning-200 bg-warning-50 px-3 py-2 text-sm">
                  <p className="min-w-0 flex-1 text-warning-800">
                    当前未授权升级。点击后，助手可检查并升级已选的{" "}
                    {config.nodeIds.length}{" "}
                    个节点、跟踪结果；升级可能短暂中断连接。不会额外授权清理日志或其他节点。
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
              <p
                role="alert"
                className="rounded-xl bg-danger-50 px-3 py-2 text-sm text-danger"
              >
                {error}
              </p>
            )}
            <div className="rounded-3xl border border-default-200 bg-background shadow-[0_2px_12px_rgba(0,0,0,0.06)] transition focus-within:border-default-400 dark:shadow-none">
              <textarea
                ref={composer}
                aria-label="聊天消息"
                rows={1}
                className="block max-h-60 min-h-[52px] w-full resize-none bg-transparent px-4 pt-3.5 text-[15px] leading-7 outline-none placeholder:text-default-400"
                placeholder={
                  configured
                    ? "给运维助手发消息…"
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
              <div className="flex items-center gap-2 px-3 pb-2.5 pt-1">
                <button
                  type="button"
                  className="flex min-w-0 items-center gap-1.5 rounded-full border border-default-200 px-2.5 py-1 text-xs text-default-600 transition hover:bg-default-100"
                  onClick={() => setPermissionsOpen(true)}
                >
                  <ShieldCheck
                    size={13}
                    className={
                      config.mode === "inspect"
                        ? "text-success"
                        : "text-warning"
                    }
                  />
                  <span className="truncate">
                    {config.nodeIds.length
                      ? `已选择 ${config.nodeIds.length} 个节点`
                      : "普通对话"}{" "}
                    · {modeLabel}
                  </span>
                </button>
                {running || historyRunning ? (
                  <Button
                    isIconOnly
                    aria-label="停止回复"
                    className="ml-auto"
                    radius="full"
                    size="sm"
                    color="danger"
                    variant="flat"
                    onPress={stop}
                  >
                    <Square size={13} fill="currentColor" />
                  </Button>
                ) : (
                  <Button
                    isIconOnly
                    aria-label="发送"
                    className="ml-auto"
                    radius="full"
                    size="sm"
                    color="primary"
                    isDisabled={!configured || !message.trim()}
                    onPress={() => void send()}
                  >
                    <ArrowUp size={17} />
                  </Button>
                )}
              </div>
            </div>
            <p className="text-center text-[11px] text-default-400">
              <span className="hidden sm:inline">
                Enter 发送，Shift+Enter 换行 ·{" "}
              </span>
              助手可能出错；涉及修改服务器的操作请先确认权限和节点。
            </p>
          </div>
        </div>
      </section>
    </div>
  );
}
