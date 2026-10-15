import type { ReactNode } from "react";

import { useEffect, useState } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Chip } from "@heroui/chip";
import {
  Table,
  TableBody,
  TableCell,
  TableColumn,
  TableHeader,
  TableRow,
} from "@heroui/table";
import { ArrowRight, Server } from "lucide-react";

// Layout follows NodePassDash's dashboard (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

const cardClass = "h-full border border-transparent dark:border-default-100";

function CurrentTime() {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);

    return () => window.clearInterval(timer);
  }, []);

  return (
    <span className="tabular-nums">
      {now.toLocaleString("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false,
      })}
    </span>
  );
}

export interface HeaderCount {
  label: string;
  value: ReactNode;
  className: string;
  onPress?: () => void;
}

/** Page title with a live clock, and the headline counts on the right. */
export function DashboardHeader({ counts }: { counts: HeaderCount[] }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-bold text-foreground">仪表盘</h1>
        <p className="text-sm text-default-500 md:text-base">
          当前时间 <CurrentTime />
        </p>
      </div>
      <div className="flex gap-5 md:gap-7">
        {counts.map((count) => (
          <button
            key={count.label}
            className="text-center disabled:cursor-default"
            disabled={!count.onPress}
            type="button"
            onClick={count.onPress}
          >
            <div
              className={`text-xl font-bold tabular-nums md:text-2xl ${count.className}`}
            >
              {count.value}
            </div>
            <div className="text-xs text-default-500 md:text-sm">
              {count.label}
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}

export interface EndpointItem {
  id: number | string;
  name: string;
  detail?: string;
  online: boolean;
  badge?: string;
}

/** A scrolling list of servers with a status-coloured server icon, like NodePass's endpoint list. */
export function EndpointListCard({
  title,
  items,
  emptyText,
  onOpen,
}: {
  title: string;
  items: EndpointItem[];
  emptyText: string;
  onOpen?: () => void;
}) {
  const online = items.filter((item) => item.online).length;

  return (
    <Card className={`${cardClass} lg:h-[436px]`}>
      <CardHeader className="flex items-start justify-between gap-3 p-5 pb-0">
        <div>
          <h2 className="text-base font-semibold text-foreground">{title}</h2>
          <p className="mt-0.5 text-sm text-default-500">
            在线 {online} / {items.length}
          </p>
        </div>
        {onOpen && (
          <button
            className="flex items-center gap-1 text-sm font-medium text-primary"
            type="button"
            onClick={onOpen}
          >
            全部 <ArrowRight className="h-4 w-4" />
          </button>
        )}
      </CardHeader>
      <CardBody className="overflow-hidden p-5 pt-3">
        {items.length === 0 ? (
          <div className="flex h-full min-h-32 items-center justify-center text-sm text-default-500">
            {emptyText}
          </div>
        ) : (
          <div className="scrollbar-thin -mr-2 h-full space-y-3 overflow-y-auto pr-2">
            {items.map((item) => (
              <div
                key={item.id}
                className="flex items-center gap-3 rounded-xl bg-default-50 p-3 dark:bg-default-100/60"
              >
                <div
                  className={`relative flex h-11 w-11 shrink-0 items-center justify-center rounded-xl ${item.online ? "bg-primary/10 text-primary" : "bg-danger/10 text-danger"}`}
                >
                  <Server className="h-5 w-5" />
                  <span
                    className={`absolute -right-0.5 -top-0.5 h-2.5 w-2.5 rounded-full ring-2 ring-content1 ${item.online ? "bg-success" : "bg-danger"}`}
                  />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex min-w-0 items-center gap-1.5">
                    <h3 className="truncate text-sm font-medium text-foreground">
                      {item.name}
                    </h3>
                    {item.badge && (
                      <Chip
                        className="shrink-0"
                        classNames={{ content: "text-xs" }}
                        size="sm"
                        variant="flat"
                      >
                        {item.badge}
                      </Chip>
                    )}
                  </div>
                  {item.detail && (
                    <p className="truncate font-mono text-xs text-default-500">
                      {item.detail}
                    </p>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </CardBody>
    </Card>
  );
}

export interface QuickAction {
  label: string;
  hint: string;
  icon: ReactNode;
  color: "primary" | "success" | "warning" | "secondary";
  onPress: () => void;
}

const quickColor = {
  primary: "bg-primary/10 text-primary",
  success: "bg-success/10 text-success",
  warning: "bg-warning/10 text-warning",
  secondary: "bg-secondary/10 text-secondary",
} as const;

export function QuickActionsCard({ actions }: { actions: QuickAction[] }) {
  return (
    <Card className={cardClass}>
      <CardHeader className="p-5 pb-0">
        <div>
          <h2 className="text-base font-semibold text-foreground">快捷操作</h2>
          <p className="mt-0.5 text-sm text-default-500">常用入口</p>
        </div>
      </CardHeader>
      <CardBody className="grid grid-cols-2 content-start gap-3 p-5 pt-4">
        {actions.map((action) => (
          <button
            key={action.label}
            className="flex min-h-[88px] flex-col items-start justify-between gap-2 rounded-xl bg-default-50 p-3 text-left transition-transform hover:scale-[1.02] dark:bg-default-100/60"
            type="button"
            onClick={action.onPress}
          >
            <span
              className={`flex h-8 w-8 items-center justify-center rounded-lg ${quickColor[action.color]}`}
            >
              {action.icon}
            </span>
            <span className="min-w-0">
              <span className="block truncate text-sm font-medium text-foreground">
                {action.label}
              </span>
              <span className="block truncate text-xs text-default-500">
                {action.hint}
              </span>
            </span>
          </button>
        ))}
      </CardBody>
    </Card>
  );
}

export interface ActivityItem {
  key: string;
  time: number;
  kind: string;
  color: "danger" | "warning" | "primary" | "success" | "default";
  name: string;
  detail: string;
  onPress?: () => void;
}

/** Recent events in a table with a coloured header, like NodePass's activity log. */
export function RecentActivityCard({
  items,
  onOpen,
}: {
  items: ActivityItem[];
  onOpen?: () => void;
}) {
  return (
    <Card className="border border-transparent dark:border-default-100">
      <CardHeader className="flex items-start justify-between gap-3 p-5">
        <div>
          <h2 className="text-base font-semibold text-foreground">最近活动</h2>
          <p className="mt-0.5 text-sm text-default-500">
            待处理告警和线路切换
          </p>
        </div>
        {onOpen && (
          <button
            className="flex items-center gap-1 text-sm font-medium text-primary"
            type="button"
            onClick={onOpen}
          >
            资源监控 <ArrowRight className="h-4 w-4" />
          </button>
        )}
      </CardHeader>
      <CardBody className="p-4 pt-0">
        <div className="scrollbar-thin max-h-[400px] overflow-y-auto">
          <Table
            isHeaderSticky
            removeWrapper
            aria-label="最近活动"
            classNames={{
              th: "bg-primary text-white first:rounded-l-lg last:rounded-r-lg",
              td: "text-xs md:text-sm",
            }}
          >
            <TableHeader>
              <TableColumn className="w-[120px]">时间</TableColumn>
              <TableColumn className="w-[110px]">类型</TableColumn>
              <TableColumn>对象</TableColumn>
              <TableColumn>详情</TableColumn>
            </TableHeader>
            <TableBody
              emptyContent={
                <span className="text-sm text-default-400">暂无活动记录</span>
              }
              items={items}
            >
              {(item) => (
                <TableRow
                  key={item.key}
                  className={item.onPress ? "cursor-pointer" : ""}
                  onClick={item.onPress}
                >
                  <TableCell className="whitespace-nowrap text-default-500">
                    {new Date(item.time).toLocaleString("zh-CN", {
                      month: "2-digit",
                      day: "2-digit",
                      hour: "2-digit",
                      minute: "2-digit",
                      hour12: false,
                    })}
                  </TableCell>
                  <TableCell>
                    <Chip color={item.color} size="sm" variant="flat">
                      {item.kind}
                    </Chip>
                  </TableCell>
                  <TableCell className="max-w-[220px] truncate font-medium">
                    {item.name}
                  </TableCell>
                  <TableCell className="max-w-[360px] truncate text-default-500">
                    {item.detail}
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      </CardBody>
    </Card>
  );
}
