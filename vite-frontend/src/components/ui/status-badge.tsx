/**
 * 状态徽章组件
 *
 * 用于显示各种状态，如在线/离线、成功/失败等
 */

import { Chip } from "@heroui/chip";
import {
  Circle,
  CheckCircle,
  XCircle,
  AlertCircle,
  Clock,
  Loader2,
} from "lucide-react";

import { cn } from "@/lib/utils";

export type BadgeStatus =
  "online" | "offline" | "warning" | "error" | "pending" | "success" | "info";

export interface StatusBadgeProps {
  /** 状态类型 */
  status: BadgeStatus;
  /** 自定义文本（不提供则使用默认文本） */
  text?: string;
  /** 是否显示图标 */
  showIcon?: boolean;
  /** 大小 */
  size?: "sm" | "md" | "lg";
  /** 变体 */
  variant?: "solid" | "flat" | "bordered" | "dot";
  /** 自定义类名 */
  className?: string;
  /** 是否显示脉动动画（用于pending状态） */
  pulse?: boolean;
}

const statusConfig = {
  online: {
    color: "success" as const,
    icon: CheckCircle,
    defaultText: "在线",
    dotColor: "bg-success",
  },
  offline: {
    color: "default" as const,
    icon: Circle,
    defaultText: "离线",
    dotColor: "bg-default-400",
  },
  warning: {
    color: "warning" as const,
    icon: AlertCircle,
    defaultText: "警告",
    dotColor: "bg-warning",
  },
  error: {
    color: "danger" as const,
    icon: XCircle,
    defaultText: "错误",
    dotColor: "bg-danger",
  },
  pending: {
    color: "primary" as const,
    icon: Clock,
    defaultText: "处理中",
    dotColor: "bg-primary",
  },
  success: {
    color: "success" as const,
    icon: CheckCircle,
    defaultText: "成功",
    dotColor: "bg-success",
  },
  info: {
    color: "primary" as const,
    icon: AlertCircle,
    defaultText: "信息",
    dotColor: "bg-primary",
  },
};

/**
 * 状态徽章组件
 *
 * @example
 * ```tsx
 * <StatusBadge status="online" />
 * <StatusBadge status="error" text="连接失败" />
 * <StatusBadge status="pending" pulse />
 * ```
 */
export function StatusBadge({
  status,
  text,
  showIcon = true,
  size = "sm",
  variant = "flat",
  className,
  pulse = false,
}: StatusBadgeProps) {
  const config = statusConfig[status];
  const Icon = config.icon;
  const displayText = text || config.defaultText;

  // 点状变体
  if (variant === "dot") {
    return (
      <span className={cn("inline-flex items-center gap-2", className)}>
        <span className="relative flex h-2 w-2">
          {pulse && (
            <span
              className={cn(
                "absolute inline-flex h-full w-full animate-ping rounded-full opacity-75",
                config.dotColor,
              )}
            />
          )}
          <span
            className={cn(
              "relative inline-flex h-2 w-2 rounded-full",
              config.dotColor,
            )}
          />
        </span>
        <span className="text-sm text-default-700">{displayText}</span>
      </span>
    );
  }

  return (
    <Chip
      color={config.color}
      size={size}
      variant={variant}
      className={cn("transition-all", pulse && "animate-pulse", className)}
      startContent={
        showIcon ? (
          status === "pending" && pulse ? (
            <Loader2 className="h-3 w-3 animate-spin" />
          ) : (
            <Icon className="h-3 w-3" />
          )
        ) : undefined
      }
    >
      {displayText}
    </Chip>
  );
}

/**
 * 数字徽章 - 用于显示计数
 */
export interface CountBadgeProps {
  /** 数量 */
  count: number;
  /** 最大显示数量（超过显示 99+） */
  max?: number;
  /** 颜色 */
  color?: "default" | "primary" | "success" | "warning" | "danger";
  /** 是否显示为点（count为0时隐藏） */
  showZero?: boolean;
  /** 是否显示为小点 */
  dot?: boolean;
  /** 自定义类名 */
  className?: string;
}

export function CountBadge({
  count,
  max = 99,
  color = "danger",
  showZero = false,
  dot = false,
  className,
}: CountBadgeProps) {
  if (!showZero && count === 0) {
    return null;
  }

  const displayCount = count > max ? `${max}+` : count;

  if (dot) {
    return (
      <span
        className={cn(
          "absolute -top-0.5 -right-0.5 h-2 w-2 rounded-full",
          color === "primary" && "bg-primary",
          color === "success" && "bg-success",
          color === "warning" && "bg-warning",
          color === "danger" && "bg-danger",
          color === "default" && "bg-default-500",
          className,
        )}
      />
    );
  }

  return (
    <Chip
      color={color}
      size="sm"
      variant="solid"
      className={cn("min-w-5 h-5 px-1", className)}
    >
      {displayCount}
    </Chip>
  );
}

/**
 * 标签徽章 - 用于显示标签或分类
 */
export interface LabelBadgeProps {
  /** 标签文本 */
  label: string;
  /** 颜色 */
  color?:
    "default" | "primary" | "success" | "warning" | "danger" | "secondary";
  /** 变体 */
  variant?: "solid" | "flat" | "bordered";
  /** 大小 */
  size?: "sm" | "md" | "lg";
  /** 是否可移除 */
  removable?: boolean;
  /** 移除回调 */
  onRemove?: () => void;
  /** 自定义类名 */
  className?: string;
}

export function LabelBadge({
  label,
  color = "default",
  variant = "flat",
  size = "sm",
  removable = false,
  onRemove,
  className,
}: LabelBadgeProps) {
  return (
    <Chip
      color={color}
      size={size}
      variant={variant}
      className={className}
      onClose={removable ? onRemove : undefined}
    >
      {label}
    </Chip>
  );
}

/**
 * 进度徽章 - 显示百分比进度
 */
export interface ProgressBadgeProps {
  /** 进度值 0-100 */
  progress: number;
  /** 是否显示百分号 */
  showPercent?: boolean;
  /** 颜色（根据进度自动判断） */
  color?: "auto" | "default" | "primary" | "success" | "warning" | "danger";
  /** 自定义类名 */
  className?: string;
}

export function ProgressBadge({
  progress,
  showPercent = true,
  color = "auto",
  className,
}: ProgressBadgeProps) {
  const getColor = () => {
    if (color !== "auto") return color;
    if (progress >= 80) return "success";
    if (progress >= 50) return "primary";
    if (progress >= 30) return "warning";

    return "danger";
  };

  const badgeColor = getColor();

  return (
    <Chip color={badgeColor} size="sm" variant="flat" className={className}>
      {showPercent ? `${Math.round(progress)}%` : Math.round(progress)}
    </Chip>
  );
}
