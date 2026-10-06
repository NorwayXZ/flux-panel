/**
 * 增强型卡片组件
 *
 * 提供多种卡片变体和状态，统一卡片样式
 */

import { Card, CardBody, CardHeader } from "@heroui/card";
import { cn } from "@/lib/utils";

export interface EnhancedCardProps {
  /** 卡片标题 */
  title?: string;
  /** 副标题 */
  subtitle?: string;
  /** 标题图标 */
  icon?: React.ReactNode;
  /** 右上角操作按钮 */
  action?: React.ReactNode;
  /** 卡片内容 */
  children: React.ReactNode;
  /** 卡片变体 */
  variant?: "default" | "outlined" | "elevated" | "flat" | "glass";
  /** 状态指示器（左侧边框） */
  status?: "success" | "warning" | "danger" | "info";
  /** 是否可交互（悬浮效果） */
  interactive?: boolean;
  /** 是否显示加载状态 */
  loading?: boolean;
  /** 自定义类名 */
  className?: string;
  /** 点击事件 */
  onClick?: () => void;
  /** 卡片头部自定义类名 */
  headerClassName?: string;
  /** 卡片内容自定义类名 */
  bodyClassName?: string;
}

/**
 * 增强型卡片组件
 *
 * @example
 * ```tsx
 * <EnhancedCard
 *   title="节点状态"
 *   subtitle="实时监控"
 *   icon={<ServerCog />}
 *   variant="elevated"
 *   status="success"
 *   interactive
 * >
 *   卡片内容
 * </EnhancedCard>
 * ```
 */
export function EnhancedCard({
  title,
  subtitle,
  icon,
  action,
  children,
  variant = "default",
  status,
  interactive = false,
  loading = false,
  className,
  onClick,
  headerClassName,
  bodyClassName,
}: EnhancedCardProps) {
  return (
    <Card
      className={cn(
        "transition-all duration-200",
        // 变体样式
        variant === "elevated" && "shadow-lg hover:shadow-xl",
        variant === "outlined" && "border-2",
        variant === "flat" && "shadow-none bg-content2/50",
        variant === "glass" && "glass",
        // 交互效果
        interactive && "cursor-pointer hover:scale-[1.02] active:scale-[0.98]",
        // 状态指示器
        status === "success" && "border-l-4 border-l-success",
        status === "warning" && "border-l-4 border-l-warning",
        status === "danger" && "border-l-4 border-l-danger",
        status === "info" && "border-l-4 border-l-info",
        // 加载状态
        loading && "opacity-60 pointer-events-none",
        className
      )}
      isPressable={interactive}
      onPress={onClick}
    >
      {(title || subtitle || icon || action) && (
        <CardHeader
          className={cn(
            "flex items-start justify-between gap-3",
            headerClassName
          )}
        >
          <div className="flex items-center gap-3 flex-1 min-w-0">
            {icon && (
              <div className="flex-shrink-0 text-default-500">
                {icon}
              </div>
            )}
            <div className="flex-1 min-w-0">
              {title && (
                <h3 className="text-lg font-semibold truncate">
                  {title}
                </h3>
              )}
              {subtitle && (
                <p className="text-sm text-default-500 truncate mt-0.5">
                  {subtitle}
                </p>
              )}
            </div>
          </div>
          {action && (
            <div className="flex-shrink-0">
              {action}
            </div>
          )}
        </CardHeader>
      )}
      <CardBody className={cn(bodyClassName)}>
        {children}
      </CardBody>
    </Card>
  );
}

/**
 * 统计卡片组件 - 用于显示数据指标
 */
export interface StatCardProps {
  /** 指标标题 */
  title: string;
  /** 指标值 */
  value: string | number;
  /** 变化趋势 */
  change?: {
    value: number;
    trend: "up" | "down" | "neutral";
  };
  /** 图标 */
  icon?: React.ReactNode;
  /** 颜色主题 */
  color?: "default" | "primary" | "success" | "warning" | "danger";
  /** 是否加载中 */
  loading?: boolean;
  /** 自定义类名 */
  className?: string;
}

export function StatCard({
  title,
  value,
  change,
  icon,
  color = "default",
  loading = false,
  className,
}: StatCardProps) {
  const colorClasses = {
    default: "bg-default/10 text-default-600",
    primary: "bg-primary/10 text-primary",
    success: "bg-success/10 text-success",
    warning: "bg-warning/10 text-warning",
    danger: "bg-danger/10 text-danger",
  };

  const trendColors = {
    up: "text-success",
    down: "text-danger",
    neutral: "text-default-500",
  };

  return (
    <Card className={cn("shadow-md", loading && "opacity-60", className)}>
      <CardBody>
        <div className="flex items-start justify-between">
          <div className="flex-1">
            <p className="text-sm text-default-500 mb-1">{title}</p>
            {loading ? (
              <div className="h-8 w-24 bg-default-200 animate-pulse rounded" />
            ) : (
              <p className="text-2xl font-bold">{value}</p>
            )}
            {change && !loading && (
              <div className={cn("flex items-center gap-1 mt-2 text-sm", trendColors[change.trend])}>
                <span className="font-medium">
                  {change.trend === "up" && "↑"}
                  {change.trend === "down" && "↓"}
                  {change.trend === "neutral" && "→"}
                </span>
                <span>{Math.abs(change.value)}%</span>
              </div>
            )}
          </div>
          {icon && (
            <div className={cn("rounded-lg p-3", colorClasses[color])}>
              {icon}
            </div>
          )}
        </div>
      </CardBody>
    </Card>
  );
}

/**
 * 空状态卡片 - 用于显示无数据的情况
 */
export interface EmptyCardProps {
  /** 图标 */
  icon?: React.ReactNode;
  /** 标题 */
  title: string;
  /** 描述 */
  description?: string;
  /** 操作按钮 */
  action?: {
    label: string;
    onClick: () => void;
    icon?: React.ReactNode;
  };
  /** 自定义类名 */
  className?: string;
}

export function EmptyCard({
  icon,
  title,
  description,
  action,
  className,
}: EmptyCardProps) {
  return (
    <Card className={cn("border-2 border-dashed", className)}>
      <CardBody className="flex flex-col items-center justify-center py-12 px-6 text-center">
        {icon && (
          <div className="mb-4 text-default-300 [&>svg]:h-12 [&>svg]:w-12">
            {icon}
          </div>
        )}
        <h3 className="text-lg font-semibold text-foreground mb-2">
          {title}
        </h3>
        {description && (
          <p className="text-sm text-default-500 mb-6 max-w-sm">
            {description}
          </p>
        )}
        {action && (
          <button
            onClick={action.onClick}
            className="inline-flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-colors"
          >
            {action.icon}
            <span>{action.label}</span>
          </button>
        )}
      </CardBody>
    </Card>
  );
}
