import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import {
  ArrowLeft,
  Power,
  RefreshCw,
  ShieldAlert,
  SquareTerminal,
} from "lucide-react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import axios from "axios";
import toast from "react-hot-toast";

import {
  createTerminalSession,
  getNodeList,
  getTerminalAudit,
  setNodeTerminalEnabled,
  type TerminalAuditItem,
} from "@/api";
import { isAdmin } from "@/utils/auth";

interface TerminalNode {
  id: number;
  name: string;
  serverIp: string;
  version?: string;
  status: number;
  terminalEnabled?: boolean;
}

const MIN_AGENT_VERSION = "2.8.0";

const encodeBase64 = (value: string) => {
  const bytes = new TextEncoder().encode(value);
  let binary = "";

  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });

  return btoa(binary);
};

const decodeBase64 = (value: string) => {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);

  for (let index = 0; index < binary.length; index += 1)
    bytes[index] = binary.charCodeAt(index);

  return bytes;
};

const versionAtLeast = (actual?: string, required = MIN_AGENT_VERSION) => {
  if (!actual) return false;
  const parse = (value: string) =>
    value
      .replace(/^v/i, "")
      .split(/[-+]/)[0]
      .split(".")
      .map((part) => Number(part) || 0);
  const left = parse(actual);
  const right = parse(required);

  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    const difference = (left[index] || 0) - (right[index] || 0);

    if (difference !== 0) return difference > 0;
  }

  return true;
};

const auditStatus: Record<string, string> = {
  connecting: "连接中",
  active: "已连接",
  closed: "已结束",
  failed: "失败",
};

export default function NodeTerminalPage() {
  const navigate = useNavigate();
  const { nodeId } = useParams();
  const numericNodeId = Number(nodeId);
  const terminalContainerRef = useRef<HTMLDivElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const dataDisposableRef = useRef<{ dispose: () => void } | null>(null);
  const resizeObserverRef = useRef<ResizeObserver | null>(null);

  const [node, setNode] = useState<TerminalNode | null>(null);
  const [audits, setAudits] = useState<TerminalAuditItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [sessionStatus, setSessionStatus] = useState("未连接");

  const supported = useMemo(
    () => versionAtLeast(node?.version),
    [node?.version],
  );

  const loadPage = useCallback(async () => {
    setLoading(true);
    const [nodeResult, auditResult] = await Promise.all([
      getNodeList(),
      getTerminalAudit(numericNodeId),
    ]);

    if (nodeResult.code === 0) {
      setNode(
        (nodeResult.data || []).find(
          (item: TerminalNode) => item.id === numericNodeId,
        ) || null,
      );
    }
    if (auditResult.code === 0) setAudits(auditResult.data || []);
    setLoading(false);
  }, [numericNodeId]);

  useEffect(() => {
    if (!isAdmin()) {
      toast.error("仅管理员可以使用远程终端");
      navigate("/node", { replace: true });

      return;
    }
    loadPage();
  }, [loadPage, navigate]);

  const closeSocket = useCallback((notify = true) => {
    const socket = socketRef.current;

    if (socket && socket.readyState === WebSocket.OPEN && notify) {
      socket.send(JSON.stringify({ type: "close" }));
    }
    if (socket) socket.close();
    socketRef.current = null;
    dataDisposableRef.current?.dispose();
    dataDisposableRef.current = null;
    resizeObserverRef.current?.disconnect();
    resizeObserverRef.current = null;
    setConnected(false);
    setConnecting(false);
  }, []);

  useEffect(
    () => () => {
      closeSocket(true);
      terminalRef.current?.dispose();
      terminalRef.current = null;
    },
    [closeSocket],
  );

  const ensureTerminal = () => {
    if (terminalRef.current || !terminalContainerRef.current) return;
    const dark = document.documentElement.classList.contains("dark");
    const terminal = new Terminal({
      cursorBlink: true,
      cursorStyle: "bar",
      convertEol: false,
      fontFamily:
        "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
      fontSize: 14,
      lineHeight: 1.25,
      scrollback: 5000,
      theme: dark
        ? {
            background: "#0b0d10",
            foreground: "#e5e7eb",
            cursor: "#60a5fa",
            selectionBackground: "#334155",
          }
        : {
            background: "#111318",
            foreground: "#f3f4f6",
            cursor: "#60a5fa",
            selectionBackground: "#334155",
          },
    });
    const fitAddon = new FitAddon();

    terminal.loadAddon(fitAddon);
    terminal.open(terminalContainerRef.current);
    terminalRef.current = terminal;
    fitAddonRef.current = fitAddon;
    requestAnimationFrame(() => fitAddon.fit());
  };

  const connectSocket = (ticket: string) => {
    ensureTerminal();
    const baseUrl =
      axios.defaults.baseURL ||
      (import.meta.env.VITE_API_BASE
        ? `${import.meta.env.VITE_API_BASE}/api/v1/`
        : "/api/v1/");
    const wsBase = baseUrl.replace(/^http/, "ws").replace(/\/api\/v1\/$/, "");
    const socket = new WebSocket(
      `${wsBase}/terminal?ticket=${encodeURIComponent(ticket)}`,
    );

    socketRef.current = socket;
    setConnecting(true);
    setSessionStatus("建立安全通道");

    socket.onopen = () => {
      fitAddonRef.current?.fit();
      const terminal = terminalRef.current;

      if (!terminal) return;
      dataDisposableRef.current = terminal.onData((data) => {
        if (socket.readyState === WebSocket.OPEN) {
          socket.send(
            JSON.stringify({ type: "input", data: encodeBase64(data) }),
          );
        }
      });
      const sendResize = () => {
        fitAddonRef.current?.fit();
        if (
          socket.readyState === WebSocket.OPEN &&
          terminal.cols &&
          terminal.rows
        ) {
          socket.send(
            JSON.stringify({
              type: "resize",
              cols: terminal.cols,
              rows: terminal.rows,
            }),
          );
        }
      };

      resizeObserverRef.current = new ResizeObserver(sendResize);
      if (terminalContainerRef.current)
        resizeObserverRef.current.observe(terminalContainerRef.current);
      sendResize();
    };

    socket.onmessage = (event) => {
      let message: { type?: string; data?: Record<string, any> };

      try {
        message = JSON.parse(event.data);
      } catch {
        terminalRef.current?.writeln("\r\n[Flux] 收到无法识别的终端消息");

        return;
      }
      if (message.type === "ready") {
        setConnected(true);
        setConnecting(false);
        setSessionStatus(message.data?.root ? "已连接 · root" : "已连接");
        terminalRef.current?.focus();
      } else if (message.type === "output" && message.data?.data) {
        terminalRef.current?.write(decodeBase64(message.data.data));
      } else if (message.type === "error") {
        terminalRef.current?.writeln(
          `\r\n[Flux] ${message.data?.message || "终端连接失败"}`,
        );
        setSessionStatus("连接失败");
      } else if (message.type === "closed") {
        terminalRef.current?.writeln(
          `\r\n[Flux] ${message.data?.reason || "会话已结束"}`,
        );
        setSessionStatus("已结束");
        closeSocket(false);
        getTerminalAudit(numericNodeId).then(
          (result) => result.code === 0 && setAudits(result.data || []),
        );
      }
    };
    socket.onerror = () => setSessionStatus("连接失败");
    socket.onclose = () => {
      setConnected(false);
      setConnecting(false);
      socketRef.current = null;
      setSessionStatus((current) =>
        current.startsWith("已连接") ? "连接已断开" : current,
      );
    };
  };

  const startSession = async () => {
    if (!node) return;
    setSubmitting(true);
    if (!node.terminalEnabled) {
      const toggleResult = await setNodeTerminalEnabled({
        nodeId: node.id,
        enabled: true,
      });

      if (toggleResult.code !== 0) {
        setSubmitting(false);
        toast.error(toggleResult.msg || "启用远程终端失败");

        return;
      }
      setNode({ ...node, terminalEnabled: true });
    }
    const result = await createTerminalSession({ nodeId: node.id });

    setSubmitting(false);
    if (result.code !== 0) {
      toast.error(result.msg || "创建终端会话失败");

      return;
    }
    terminalRef.current?.clear();
    connectSocket(result.data.ticket);
  };

  const disableTerminal = async () => {
    if (!node) return;
    setSubmitting(true);
    const result = await setNodeTerminalEnabled({
      nodeId: node.id,
      enabled: false,
    });

    setSubmitting(false);
    if (result.code !== 0) return toast.error(result.msg || "关闭远程终端失败");
    closeSocket(true);
    setNode({ ...node, terminalEnabled: false });
    setSessionStatus("终端功能已关闭");
    toast.success("该节点的远程终端已关闭");
  };

  if (loading)
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <Spinner />
      </div>
    );
  if (!node)
    return <div className="p-6 text-default-500">节点不存在或无权访问</div>;

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 lg:flex-row lg:items-end lg:justify-between">
        <div className="flex min-w-0 items-center gap-3">
          <Button
            isIconOnly
            aria-label="返回节点列表"
            variant="light"
            onPress={() => navigate("/node")}
          >
            <ArrowLeft size={20} />
          </Button>
          <div className="min-w-0">
            <p className="text-sm text-default-500">节点远程终端</p>
            <h1 className="mt-1 truncate text-2xl font-semibold">
              {node.name}
            </h1>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Chip
            color={node.status === 1 ? "success" : "danger"}
            size="sm"
            variant="flat"
          >
            {node.status === 1 ? "节点在线" : "节点离线"}
          </Chip>
          <Chip
            color={supported ? "primary" : "warning"}
            size="sm"
            variant="flat"
          >
            Agent {node.version || "未知"}
          </Chip>
          <Chip
            color={node.terminalEnabled ? "success" : "default"}
            size="sm"
            variant="flat"
          >
            {node.terminalEnabled ? "终端已启用" : "终端已关闭"}
          </Chip>
        </div>
      </header>

      {!supported && (
        <section className="flex items-start gap-3 border-y border-warning/40 bg-warning/10 px-4 py-4 text-sm text-warning-700 dark:text-warning-300">
          <ShieldAlert className="mt-0.5 shrink-0" size={19} />
          <div>
            <div className="font-medium">需要升级 Agent</div>
            <div className="mt-1 text-default-600">
              远程终端要求 Agent {MIN_AGENT_VERSION} 或更高版本，当前版本为{" "}
              {node.version || "未知"}。
            </div>
          </div>
        </section>
      )}

      <section className="flex flex-col gap-3 border-y border-divider py-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <p className="font-medium text-foreground">远程会话</p>
          <p className="mt-1 text-sm text-default-500">{sessionStatus}</p>
        </div>
        <div className="flex flex-wrap gap-2 sm:justify-end">
          {node.terminalEnabled && !connected && !connecting && (
            <Button
              color="danger"
              isLoading={submitting}
              variant="flat"
              onPress={disableTerminal}
            >
              关闭终端功能
            </Button>
          )}
          {!connected && (
            <Button
              color="primary"
              isDisabled={!supported || node.status !== 1}
              isLoading={submitting || connecting}
              startContent={<SquareTerminal size={17} />}
              onPress={startSession}
            >
              {node.terminalEnabled ? "打开终端" : "启用并打开"}
            </Button>
          )}
          {connected && (
            <Button
              color="danger"
              startContent={<Power size={17} />}
              variant="flat"
              onPress={() => closeSocket(true)}
            >
              断开会话
            </Button>
          )}
        </div>
      </section>

      <section className="overflow-hidden rounded-lg border border-divider bg-[#111318]">
        <div className="flex h-11 items-center justify-between border-b border-white/10 px-4 text-sm text-gray-300">
          <div className="flex items-center gap-2">
            <span
              className={`h-2 w-2 rounded-full ${connected ? "bg-emerald-400" : connecting ? "bg-amber-400" : "bg-gray-500"}`}
            />
            {sessionStatus}
          </div>
          <Button
            isIconOnly
            aria-label="重新适配终端"
            className="text-gray-300"
            size="sm"
            variant="light"
            onPress={() => fitAddonRef.current?.fit()}
          >
            <RefreshCw size={16} />
          </Button>
        </div>
        <div
          ref={terminalContainerRef}
          className="h-[56vh] min-h-[420px] w-full p-3"
        />
      </section>

      <section className="space-y-3 border-t border-divider pt-5">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold">最近会话</h2>
          <span className="text-xs text-default-500">仅记录会话元数据</span>
        </div>
        {audits.length === 0 ? (
          <div className="py-8 text-center text-sm text-default-500">
            暂无终端会话记录
          </div>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-divider">
            <div className="min-w-[760px]">
              <div className="grid grid-cols-[1fr_1fr_1.2fr_1fr_1.4fr] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500">
                <span>操作者</span>
                <span>来源 IP</span>
                <span>开始时间</span>
                <span>状态</span>
                <span>结束原因</span>
              </div>
              {audits.map((item) => (
                <div
                  key={item.sessionId}
                  className="grid grid-cols-[1fr_1fr_1.2fr_1fr_1.4fr] gap-4 border-t border-divider px-4 py-3 text-sm"
                >
                  <span>{item.username}</span>
                  <span className="font-mono text-xs">
                    {item.sourceIp || "-"}
                  </span>
                  <span>
                    {new Date(item.startedAt).toLocaleString("zh-CN", {
                      hour12: false,
                    })}
                  </span>
                  <span>{auditStatus[item.status] || item.status}</span>
                  <span
                    className="truncate text-default-500"
                    title={item.closeReason}
                  >
                    {item.closeReason || "-"}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
