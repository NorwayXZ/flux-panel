/**
 * 加载骨架屏组件
 *
 * 用于显示内容加载时的占位符，提供更好的用户体验
 */

import { Card, CardBody, CardHeader } from "@heroui/card";
import { Skeleton } from "@heroui/skeleton";

import { cn } from "@/lib/utils";

/**
 * 骨架卡片 - 模拟卡片布局
 */
export interface SkeletonCardProps {
  /** 是否显示头像 */
  showAvatar?: boolean;
  /** 是否显示操作按钮 */
  showAction?: boolean;
  /** 内容行数 */
  lines?: number;
  /** 自定义类名 */
  className?: string;
}

export function SkeletonCard({
  showAvatar = true,
  showAction = false,
  lines = 3,
  className,
}: SkeletonCardProps) {
  return (
    <Card className={className}>
      <CardHeader className="flex gap-3">
        {showAvatar && (
          <Skeleton className="w-10 h-10 rounded-full flex-shrink-0" />
        )}
        <div className="flex-1 space-y-2">
          <Skeleton className="w-3/5 h-4 rounded" />
          <Skeleton className="w-4/5 h-3 rounded" />
        </div>
        {showAction && <Skeleton className="w-8 h-8 rounded" />}
      </CardHeader>
      <CardBody className="space-y-3">
        {Array.from({ length: lines }).map((_, i) => (
          <Skeleton
            key={i}
            className={cn("h-3 rounded", i === lines - 1 ? "w-2/3" : "w-full")}
          />
        ))}
      </CardBody>
    </Card>
  );
}

/**
 * 骨架卡片网格 - 显示多个骨架卡片
 */
export interface SkeletonCardGridProps {
  /** 卡片数量 */
  count?: number;
  /** 列数 */
  cols?: {
    default?: number;
    sm?: number;
    md?: number;
    lg?: number;
  };
  /** 是否显示头像 */
  showAvatar?: boolean;
  /** 内容行数 */
  lines?: number;
  /** 自定义类名 */
  className?: string;
}

export function SkeletonCardGrid({
  count = 6,
  cols = { default: 1, md: 2, lg: 3 },
  showAvatar = true,
  lines = 3,
  className,
}: SkeletonCardGridProps) {
  const gridCols = {
    1: "grid-cols-1",
    2: "grid-cols-2",
    3: "grid-cols-3",
    4: "grid-cols-4",
  } as const;

  return (
    <div
      className={cn(
        "grid gap-4",
        cols.default && gridCols[cols.default as keyof typeof gridCols],
        cols.sm && `sm:${gridCols[cols.sm as keyof typeof gridCols]}`,
        cols.md && `md:${gridCols[cols.md as keyof typeof gridCols]}`,
        cols.lg && `lg:${gridCols[cols.lg as keyof typeof gridCols]}`,
        className,
      )}
    >
      {Array.from({ length: count }).map((_, i) => (
        <SkeletonCard key={i} showAvatar={showAvatar} lines={lines} />
      ))}
    </div>
  );
}

/**
 * 骨架表格 - 模拟表格布局
 */
export interface SkeletonTableProps {
  /** 行数 */
  rows?: number;
  /** 列数 */
  columns?: number;
  /** 是否显示表头 */
  showHeader?: boolean;
  /** 自定义类名 */
  className?: string;
}

export function SkeletonTable({
  rows = 5,
  columns = 4,
  showHeader = true,
  className,
}: SkeletonTableProps) {
  return (
    <div className={cn("space-y-2", className)}>
      {showHeader && (
        <div className="flex gap-4 p-4 bg-content2 rounded-lg">
          {Array.from({ length: columns }).map((_, i) => (
            <Skeleton
              key={`header-${i}`}
              className={cn("h-4 rounded", i === 0 ? "w-1/4" : "flex-1")}
            />
          ))}
        </div>
      )}
      <div className="space-y-2">
        {Array.from({ length: rows }).map((_, rowIndex) => (
          <div
            key={`row-${rowIndex}`}
            className="flex gap-4 p-4 bg-content1 rounded-lg"
          >
            {Array.from({ length: columns }).map((_, colIndex) => (
              <Skeleton
                key={`cell-${rowIndex}-${colIndex}`}
                className={cn(
                  "h-4 rounded",
                  colIndex === 0 ? "w-1/4" : "flex-1",
                )}
              />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * 骨架列表 - 简单的列表布局
 */
export interface SkeletonListProps {
  /** 列表项数量 */
  count?: number;
  /** 是否显示头像 */
  showAvatar?: boolean;
  /** 是否显示副标题 */
  showSubtitle?: boolean;
  /** 自定义类名 */
  className?: string;
}

export function SkeletonList({
  count = 5,
  showAvatar = true,
  showSubtitle = true,
  className,
}: SkeletonListProps) {
  return (
    <div className={cn("space-y-3", className)}>
      {Array.from({ length: count }).map((_, i) => (
        <div
          key={i}
          className="flex items-center gap-3 p-3 bg-content1 rounded-lg"
        >
          {showAvatar && (
            <Skeleton className="w-10 h-10 rounded-full flex-shrink-0" />
          )}
          <div className="flex-1 space-y-2">
            <Skeleton className="w-3/4 h-4 rounded" />
            {showSubtitle && <Skeleton className="w-1/2 h-3 rounded" />}
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * 骨架文本 - 文本内容占位符
 */
export interface SkeletonTextProps {
  /** 行数 */
  lines?: number;
  /** 是否显示标题 */
  showTitle?: boolean;
  /** 自定义类名 */
  className?: string;
}

export function SkeletonText({
  lines = 4,
  showTitle = true,
  className,
}: SkeletonTextProps) {
  return (
    <div className={cn("space-y-3", className)}>
      {showTitle && <Skeleton className="w-1/3 h-6 rounded" />}
      <div className="space-y-2">
        {Array.from({ length: lines }).map((_, i) => (
          <Skeleton
            key={i}
            className={cn("h-4 rounded", i === lines - 1 ? "w-2/3" : "w-full")}
          />
        ))}
      </div>
    </div>
  );
}

/**
 * 骨架表单 - 表单布局占位符
 */
export interface SkeletonFormProps {
  /** 字段数量 */
  fields?: number;
  /** 是否显示按钮 */
  showButton?: boolean;
  /** 自定义类名 */
  className?: string;
}

export function SkeletonForm({
  fields = 4,
  showButton = true,
  className,
}: SkeletonFormProps) {
  return (
    <div className={cn("space-y-4", className)}>
      {Array.from({ length: fields }).map((_, i) => (
        <div key={i} className="space-y-2">
          <Skeleton className="w-1/4 h-4 rounded" />
          <Skeleton className="w-full h-10 rounded-lg" />
        </div>
      ))}
      {showButton && (
        <div className="flex gap-2 pt-2">
          <Skeleton className="w-24 h-10 rounded-lg" />
          <Skeleton className="w-24 h-10 rounded-lg" />
        </div>
      )}
    </div>
  );
}

/**
 * 骨架统计卡片 - 统计数据占位符
 */
export interface SkeletonStatCardProps {
  /** 是否显示图标 */
  showIcon?: boolean;
  /** 是否显示趋势 */
  showTrend?: boolean;
  /** 自定义类名 */
  className?: string;
}

export function SkeletonStatCard({
  showIcon = true,
  showTrend = true,
  className,
}: SkeletonStatCardProps) {
  return (
    <Card className={className}>
      <CardBody>
        <div className="flex items-start justify-between">
          <div className="flex-1 space-y-3">
            <Skeleton className="w-1/2 h-4 rounded" />
            <Skeleton className="w-2/3 h-8 rounded" />
            {showTrend && <Skeleton className="w-1/3 h-4 rounded" />}
          </div>
          {showIcon && <Skeleton className="w-12 h-12 rounded-lg" />}
        </div>
      </CardBody>
    </Card>
  );
}

/**
 * 骨架页面 - 完整页面布局占位符
 */
export interface SkeletonPageProps {
  /** 是否显示标题栏 */
  showHeader?: boolean;
  /** 内容类型 */
  contentType?: "cards" | "table" | "list" | "text";
  /** 自定义类名 */
  className?: string;
}

export function SkeletonPage({
  showHeader = true,
  contentType = "cards",
  className,
}: SkeletonPageProps) {
  return (
    <div className={cn("space-y-6", className)}>
      {showHeader && (
        <div className="space-y-2">
          <Skeleton className="w-1/4 h-8 rounded" />
          <Skeleton className="w-1/2 h-4 rounded" />
        </div>
      )}

      {contentType === "cards" && <SkeletonCardGrid count={6} />}
      {contentType === "table" && <SkeletonTable rows={8} />}
      {contentType === "list" && <SkeletonList count={10} />}
      {contentType === "text" && <SkeletonText lines={8} />}
    </div>
  );
}
