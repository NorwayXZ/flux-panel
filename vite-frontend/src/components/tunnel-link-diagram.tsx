import type { ReactNode } from "react";

import { Chip } from "@heroui/chip";
import { Globe2, Laptop, LogIn, LogOut, Repeat, Server } from "lucide-react";

/**
 * A vertical diagram of a tunnel: client → entry → relays → exit → internet. Nodes and the
 * links between them are coloured by the latest check, so a broken hop shows where it breaks.
 */

export type LinkStatus = "ok" | "fail" | "warn" | "skip" | "checking" | "idle";

export interface LinkCheckNode {
  id: number;
  name: string;
  role: "entry" | "relay" | "exit";
  address?: string;
  version?: string;
  online: boolean;
  status: LinkStatus;
  message?: string;
}

export interface LinkCheckLink {
  from: number;
  to: number;
  kind: "hop" | "internet";
  label: string;
  address?: string;
  port?: number;
  mode?: string;
  status: LinkStatus;
  message?: string;
  latencyMs?: number | null;
  probe?: "live" | "listener" | "none";
}

export interface LinkCheck {
  nodes: LinkCheckNode[];
  links: LinkCheckLink[];
  status: "ok" | "warn" | "fail";
  message: string;
  checkedAt: number;
}

export interface DiagramNode {
  key: string | number;
  name: string;
  role: "client" | "entry" | "relay" | "exit" | "internet" | "single";
  detail?: string;
  status: LinkStatus;
  statusText?: string;
}

export interface DiagramEdge {
  status: LinkStatus;
  label?: string;
  message?: string;
  latencyMs?: number | null;
}

const statusTone: Record<
  LinkStatus,
  {
    line: string;
    icon: string;
    chip: "success" | "danger" | "warning" | "default" | "primary";
    text: string;
  }
> = {
  ok: {
    line: "bg-success",
    icon: "bg-success/15 text-success",
    chip: "success",
    text: "正常",
  },
  fail: {
    line: "bg-danger",
    icon: "bg-danger/15 text-danger",
    chip: "danger",
    text: "不通",
  },
  warn: {
    line: "bg-warning",
    icon: "bg-warning/15 text-warning",
    chip: "warning",
    text: "注意",
  },
  skip: {
    line: "bg-default-300",
    icon: "bg-default-100 text-default-500",
    chip: "default",
    text: "未检测",
  },
  checking: {
    line: "bg-primary animate-pulse",
    icon: "bg-primary/15 text-primary",
    chip: "primary",
    text: "检测中",
  },
  idle: {
    line: "bg-default-300",
    icon: "bg-default-100 text-default-500",
    chip: "default",
    text: "",
  },
};

const roleIcon: Record<DiagramNode["role"], ReactNode> = {
  client: <Laptop className="h-4 w-4" />,
  entry: <LogIn className="h-4 w-4" />,
  relay: <Repeat className="h-4 w-4" />,
  exit: <LogOut className="h-4 w-4" />,
  internet: <Globe2 className="h-4 w-4" />,
  single: <Server className="h-4 w-4" />,
};

export const roleLabel: Record<DiagramNode["role"], string> = {
  client: "客户端",
  entry: "入口",
  relay: "中转",
  exit: "出口",
  internet: "外网",
  single: "入口/出口",
};

/** Builds the diagram for a check result; nodes the user picked but not yet checked show as idle. */
export function diagramFromCheck(
  check: LinkCheck,
  options: { checking?: boolean; withEnds?: boolean } = {},
): { nodes: DiagramNode[]; edges: DiagramEdge[] } {
  const checking = Boolean(options.checking);
  const single = check.nodes.length === 1;
  const nodes: DiagramNode[] = check.nodes.map((node) => ({
    key: node.id,
    name: node.name,
    role: single ? "single" : node.role,
    detail: node.address,
    status: checking ? "checking" : node.status,
    statusText: checking ? "检测中" : node.online ? "在线" : "离线",
  }));
  const hopEdges: DiagramEdge[] = check.links
    .filter((link) => link.kind === "hop")
    .map((link) => edgeFromLink(link, checking));
  const internet = check.links.find((link) => link.kind === "internet");

  if (!options.withEnds) return { nodes, edges: hopEdges };

  return {
    nodes: [
      { key: "client", name: "用户设备", role: "client", status: "idle" },
      ...nodes,
      {
        key: "internet",
        name: "目标 / 外网",
        role: "internet",
        status: internet ? (checking ? "checking" : internet.status) : "idle",
        statusText: internet && !checking ? undefined : "",
      },
    ],
    edges: [
      { status: "idle", label: "连接转发入口端口" },
      ...hopEdges,
      internet ? edgeFromLink(internet, checking) : { status: "idle" },
    ],
  };
}

export function edgeFromLink(
  link: LinkCheckLink,
  checking = false,
): DiagramEdge {
  const target =
    link.kind === "internet"
      ? undefined
      : [link.address, link.port].filter(Boolean).join(":");

  return {
    status: checking ? "checking" : link.status,
    label: [link.mode ? modeName(link.mode) : undefined, target]
      .filter(Boolean)
      .join(" · "),
    message: checking ? "正在检测…" : link.message,
    latencyMs: checking ? undefined : link.latencyMs,
  };
}

const modeName = (mode: string) =>
  ({
    public: "公网",
    private: "原生内网",
    virtual: "自动组网",
    custom: "自定义地址",
  })[mode] || mode;

export default function TunnelLinkDiagram({
  nodes,
  edges,
  compact = false,
}: {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  compact?: boolean;
}) {
  return (
    <ol className="flex flex-col">
      {nodes.map((node, index) => {
        const tone = statusTone[node.status];
        const edge = edges[index];
        const edgeTone = edge ? statusTone[edge.status] : undefined;

        return (
          <li key={node.key} className="flex flex-col">
            <div className="flex min-w-0 items-center gap-2.5">
              <span
                className={`flex shrink-0 items-center justify-center rounded-full ${compact ? "h-7 w-7" : "h-9 w-9"} ${tone.icon}`}
              >
                {roleIcon[node.role]}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex min-w-0 items-center gap-1.5">
                  <span className="shrink-0 text-[11px] text-default-400">
                    {roleLabel[node.role]}
                  </span>
                  <span
                    className={`truncate font-medium text-foreground ${compact ? "text-xs" : "text-sm"}`}
                    title={node.name}
                  >
                    {node.name}
                  </span>
                </div>
                {node.detail && (
                  <p
                    className="truncate font-mono text-[11px] text-default-500"
                    title={node.detail}
                  >
                    {node.detail}
                  </p>
                )}
              </div>
              {(node.statusText ?? tone.text) && (
                <Chip
                  className="h-5 shrink-0 text-[10px]"
                  color={tone.chip}
                  size="sm"
                  variant="flat"
                >
                  {node.statusText ?? tone.text}
                </Chip>
              )}
            </div>
            {edge && edgeTone && (
              <div
                className={`flex min-w-0 gap-2.5 ${compact ? "pl-[13px]" : "pl-[17px]"}`}
              >
                <span
                  className={`w-0.5 shrink-0 rounded-full ${compact ? "my-1 min-h-5" : "my-1.5 min-h-8"} ${edgeTone.line}`}
                />
                <div
                  className={`min-w-0 flex-1 self-center ${compact ? "py-0.5 pl-[11px]" : "py-1 pl-[15px]"}`}
                >
                  {(edge.label || edge.latencyMs) && (
                    <p
                      className="truncate text-[11px] text-default-500"
                      title={edge.label}
                    >
                      {edge.label}
                      {edge.latencyMs ? (
                        <span className="ml-1 font-medium text-success">
                          {edge.latencyMs} ms
                        </span>
                      ) : null}
                    </p>
                  )}
                  {edge.message && edge.status !== "ok" && (
                    <p
                      className={`text-[11px] leading-4 ${edge.status === "fail" ? "text-danger" : edge.status === "warn" ? "text-warning-600 dark:text-warning-400" : "text-default-500"} ${compact ? "line-clamp-2" : ""}`}
                      title={edge.message}
                    >
                      {edge.message}
                    </p>
                  )}
                </div>
              </div>
            )}
          </li>
        );
      })}
    </ol>
  );
}
