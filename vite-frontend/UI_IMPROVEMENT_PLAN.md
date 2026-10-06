# 🎨 Flux Panel UI 改进方案

## 📋 概述

本文档详细说明 Flux Panel 前端界面的改进方案，包括设计系统优化、组件升级、交互改进和视觉优化。

## 🎯 改进目标

1. **统一视觉语言** - 建立一致的设计系统
2. **提升用户体验** - 优化交互流程和反馈
3. **增强可访问性** - 支持键盘导航和屏幕阅读器
4. **优化响应式** - 更好的移动端体验
5. **提升性能感知** - 更流畅的动画和过渡

## 🎨 设计系统优化

### 1. 颜色系统升级

#### 当前问题
- 颜色使用不够统一
- 暗色模式对比度可以改进
- 缺少语义化的颜色变量

#### 改进方案

**创建新的颜色 Token 系统**:

```css
/* src/styles/design-tokens.css */
:root {
  /* 表面层级 - 深色主题 */
  --surface-0: #05070C;  /* 最深层 3% */
  --surface-1: #0A0D12;  /* 5% */
  --surface-2: #0F131C;  /* 7% */
  --surface-3: #161D2B;  /* 10% */
  --surface-4: #1E2636;  /* 13% */
  
  /* 主色调 - 根据业务定制 */
  --accent-primary: #38BDF8;    /* 明亮青色 */
  --accent-primary-dim: #0284C7; /* 暗青色 */
  --accent-secondary: #6EE7B7;   /* 辅助绿色 */
  
  /* 语义化颜色 */
  --color-success: #10B981;
  --color-warning: #F59E0B;
  --color-danger: #EF4444;
  --color-info: #3B82F6;
  
  /* 文本层级 */
  --text-primary: rgba(255, 255, 255, 0.95);
  --text-secondary: rgba(255, 255, 255, 0.7);
  --text-tertiary: rgba(255, 255, 255, 0.5);
  --text-disabled: rgba(255, 255, 255, 0.3);
}

/* 亮色主题 */
[data-theme="light"] {
  --surface-0: #FFFFFF;
  --surface-1: #F9FAFB;
  --surface-2: #F3F4F6;
  --surface-3: #E5E7EB;
  --surface-4: #D1D5DB;
  
  --text-primary: rgba(0, 0, 0, 0.9);
  --text-secondary: rgba(0, 0, 0, 0.7);
  --text-tertiary: rgba(0, 0, 0, 0.5);
  --text-disabled: rgba(0, 0, 0, 0.3);
}
```

### 2. 字体排版系统

#### 改进方案

```css
/* 字体定义 */
:root {
  --font-family-base: -apple-system, BlinkMacSystemFont, 'Segoe UI', 
                      'Noto Sans SC', sans-serif;
  --font-family-mono: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  
  /* 流式字体大小 - 响应式缩放 */
  --font-size-xs: clamp(0.75rem, 0.7rem + 0.25vw, 0.875rem);
  --font-size-sm: clamp(0.875rem, 0.825rem + 0.25vw, 1rem);
  --font-size-base: clamp(1rem, 0.95rem + 0.25vw, 1.125rem);
  --font-size-lg: clamp(1.125rem, 1.05rem + 0.375vw, 1.25rem);
  --font-size-xl: clamp(1.25rem, 1.15rem + 0.5vw, 1.5rem);
  --font-size-2xl: clamp(1.5rem, 1.35rem + 0.75vw, 2rem);
  --font-size-3xl: clamp(2rem, 1.75rem + 1.25vw, 3rem);
  
  /* 行高 */
  --line-height-tight: 1.25;
  --line-height-normal: 1.5;
  --line-height-relaxed: 1.75;
  
  /* 字重 */
  --font-weight-normal: 400;
  --font-weight-medium: 500;
  --font-weight-semibold: 600;
  --font-weight-bold: 700;
  
  /* 字间距 */
  --letter-spacing-tight: -0.02em;
  --letter-spacing-normal: 0;
  --letter-spacing-wide: 0.025em;
}
```

### 3. 间距系统

```css
:root {
  /* 基础间距单位 */
  --spacing-unit: 0.25rem; /* 4px */
  
  /* 间距比例尺 */
  --spacing-1: calc(var(--spacing-unit) * 1);   /* 4px */
  --spacing-2: calc(var(--spacing-unit) * 2);   /* 8px */
  --spacing-3: calc(var(--spacing-unit) * 3);   /* 12px */
  --spacing-4: calc(var(--spacing-unit) * 4);   /* 16px */
  --spacing-5: calc(var(--spacing-unit) * 5);   /* 20px */
  --spacing-6: calc(var(--spacing-unit) * 6);   /* 24px */
  --spacing-8: calc(var(--spacing-unit) * 8);   /* 32px */
  --spacing-10: calc(var(--spacing-unit) * 10); /* 40px */
  --spacing-12: calc(var(--spacing-unit) * 12); /* 48px */
  --spacing-16: calc(var(--spacing-unit) * 16); /* 64px */
  
  /* 容器间距 */
  --container-padding-sm: var(--spacing-4);
  --container-padding-md: var(--spacing-6);
  --container-padding-lg: var(--spacing-8);
}
```

### 4. 圆角系统

```css
:root {
  /* 组件圆角 */
  --radius-sm: 0.25rem;    /* 4px - 小组件 */
  --radius-md: 0.5rem;     /* 8px - 卡片、输入框 */
  --radius-lg: 0.75rem;    /* 12px - 大卡片 */
  --radius-xl: 1rem;       /* 16px - 模态框 */
  
  /* 特殊圆角 */
  --radius-full: 9999px;   /* 完全圆形 - 按钮、徽章 */
  --radius-circle: 50%;    /* 圆形 - 头像 */
}
```

### 5. 阴影系统

```css
:root {
  /* 阴影层级 */
  --shadow-sm: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  --shadow-md: 0 4px 6px -1px rgba(0, 0, 0, 0.1),
               0 2px 4px -1px rgba(0, 0, 0, 0.06);
  --shadow-lg: 0 10px 15px -3px rgba(0, 0, 0, 0.1),
               0 4px 6px -2px rgba(0, 0, 0, 0.05);
  --shadow-xl: 0 20px 25px -5px rgba(0, 0, 0, 0.1),
               0 10px 10px -5px rgba(0, 0, 0, 0.04);
  
  /* 发光效果 - 用于聚焦状态 */
  --shadow-focus: 0 0 0 3px var(--accent-primary);
  --shadow-glow: 0 0 20px rgba(56, 189, 248, 0.3);
}
```

## 🧩 组件改进

### 1. 卡片组件优化

**创建标准化的卡片变体**:

```typescript
// src/components/ui/card-enhanced.tsx
import { Card, CardBody, CardHeader } from "@heroui/card";
import { cn } from "@/lib/utils";

interface EnhancedCardProps {
  title?: string;
  subtitle?: string;
  icon?: React.ReactNode;
  action?: React.ReactNode;
  children: React.ReactNode;
  variant?: "default" | "outlined" | "elevated" | "flat";
  status?: "success" | "warning" | "danger" | "info";
  interactive?: boolean;
  className?: string;
}

export function EnhancedCard({
  title,
  subtitle,
  icon,
  action,
  children,
  variant = "default",
  status,
  interactive = false,
  className,
}: EnhancedCardProps) {
  return (
    <Card
      className={cn(
        "transition-all duration-200",
        variant === "elevated" && "shadow-lg hover:shadow-xl",
        variant === "outlined" && "border-2",
        interactive && "cursor-pointer hover:scale-[1.02] active:scale-[0.98]",
        status === "success" && "border-l-4 border-l-success",
        status === "warning" && "border-l-4 border-l-warning",
        status === "danger" && "border-l-4 border-l-danger",
        status === "info" && "border-l-4 border-l-info",
        className
      )}
    >
      {(title || subtitle || icon || action) && (
        <CardHeader className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3 flex-1">
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
                <p className="text-sm text-default-500 truncate">
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
      <CardBody>{children}</CardBody>
    </Card>
  );
}
```

### 2. 状态指示器优化

```typescript
// src/components/ui/status-badge.tsx
import { Chip } from "@heroui/chip";
import { Circle, CheckCircle, XCircle, AlertCircle } from "lucide-react";

interface StatusBadgeProps {
  status: "online" | "offline" | "warning" | "error" | "pending";
  text?: string;
  showIcon?: boolean;
  size?: "sm" | "md" | "lg";
}

export function StatusBadge({
  status,
  text,
  showIcon = true,
  size = "sm"
}: StatusBadgeProps) {
  const config = {
    online: {
      color: "success" as const,
      icon: CheckCircle,
      defaultText: "在线"
    },
    offline: {
      color: "default" as const,
      icon: Circle,
      defaultText: "离线"
    },
    warning: {
      color: "warning" as const,
      icon: AlertCircle,
      defaultText: "警告"
    },
    error: {
      color: "danger" as const,
      icon: XCircle,
      defaultText: "错误"
    },
    pending: {
      color: "primary" as const,
      icon: Circle,
      defaultText: "处理中"
    }
  };

  const { color, icon: Icon, defaultText } = config[status];

  return (
    <Chip
      color={color}
      size={size}
      variant="flat"
      startContent={showIcon ? <Icon className="h-3 w-3" /> : undefined}
    >
      {text || defaultText}
    </Chip>
  );
}
```

### 3. 空状态组件

```typescript
// src/components/ui/empty-state.tsx
import { Card, CardBody } from "@heroui/card";
import { Button } from "@heroui/button";

interface EmptyStateProps {
  icon?: React.ReactNode;
  title: string;
  description?: string;
  action?: {
    label: string;
    onClick: () => void;
    icon?: React.ReactNode;
  };
}

export function EmptyState({
  icon,
  title,
  description,
  action
}: EmptyStateProps) {
  return (
    <Card className="border-2 border-dashed">
      <CardBody className="flex flex-col items-center justify-center py-12 px-6 text-center">
        {icon && (
          <div className="mb-4 text-default-300">
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
          <Button
            color="primary"
            startContent={action.icon}
            onClick={action.onClick}
          >
            {action.label}
          </Button>
        )}
      </CardBody>
    </Card>
  );
}
```

### 4. 加载骨架屏

```typescript
// src/components/ui/skeleton-card.tsx
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Skeleton } from "@heroui/skeleton";

export function SkeletonCard() {
  return (
    <Card>
      <CardHeader className="flex gap-3">
        <Skeleton className="w-10 h-10 rounded-full" />
        <div className="flex-1 space-y-2">
          <Skeleton className="w-3/5 h-4 rounded" />
          <Skeleton className="w-4/5 h-3 rounded" />
        </div>
      </CardHeader>
      <CardBody className="space-y-3">
        <Skeleton className="w-full h-3 rounded" />
        <Skeleton className="w-full h-3 rounded" />
        <Skeleton className="w-2/3 h-3 rounded" />
      </CardBody>
    </Card>
  );
}

export function SkeletonTable({ rows = 5 }: { rows?: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className="w-full h-12 rounded" />
      ))}
    </div>
  );
}
```

## 📱 响应式改进

### 1. 断点系统

```typescript
// src/hooks/use-breakpoint.ts
import { useState, useEffect } from 'react';

const breakpoints = {
  sm: 640,
  md: 768,
  lg: 1024,
  xl: 1280,
  '2xl': 1536,
} as const;

export type Breakpoint = keyof typeof breakpoints;

export function useBreakpoint() {
  const [currentBreakpoint, setCurrentBreakpoint] = useState<Breakpoint>('sm');

  useEffect(() => {
    const updateBreakpoint = () => {
      const width = window.innerWidth;
      
      if (width >= breakpoints['2xl']) setCurrentBreakpoint('2xl');
      else if (width >= breakpoints.xl) setCurrentBreakpoint('xl');
      else if (width >= breakpoints.lg) setCurrentBreakpoint('lg');
      else if (width >= breakpoints.md) setCurrentBreakpoint('md');
      else setCurrentBreakpoint('sm');
    };

    updateBreakpoint();
    window.addEventListener('resize', updateBreakpoint);
    return () => window.removeEventListener('resize', updateBreakpoint);
  }, []);

  const isAbove = (breakpoint: Breakpoint) => {
    return breakpoints[currentBreakpoint] >= breakpoints[breakpoint];
  };

  const isBelow = (breakpoint: Breakpoint) => {
    return breakpoints[currentBreakpoint] < breakpoints[breakpoint];
  };

  return {
    current: currentBreakpoint,
    isAbove,
    isBelow,
    isMobile: !isAbove('md'),
    isTablet: isAbove('md') && isBelow('lg'),
    isDesktop: isAbove('lg'),
  };
}
```

### 2. 响应式布局容器

```typescript
// src/components/ui/responsive-grid.tsx
import { cn } from "@/lib/utils";

interface ResponsiveGridProps {
  children: React.ReactNode;
  cols?: {
    default?: number;
    sm?: number;
    md?: number;
    lg?: number;
    xl?: number;
  };
  gap?: number;
  className?: string;
}

export function ResponsiveGrid({
  children,
  cols = { default: 1, md: 2, lg: 3 },
  gap = 4,
  className
}: ResponsiveGridProps) {
  const gridCols = {
    1: "grid-cols-1",
    2: "grid-cols-2",
    3: "grid-cols-3",
    4: "grid-cols-4",
    5: "grid-cols-5",
    6: "grid-cols-6",
  } as const;

  return (
    <div
      className={cn(
        "grid",
        cols.default && gridCols[cols.default as keyof typeof gridCols],
        cols.sm && `sm:${gridCols[cols.sm as keyof typeof gridCols]}`,
        cols.md && `md:${gridCols[cols.md as keyof typeof gridCols]}`,
        cols.lg && `lg:${gridCols[cols.lg as keyof typeof gridCols]}`,
        cols.xl && `xl:${gridCols[cols.xl as keyof typeof gridCols]}`,
        `gap-${gap}`,
        className
      )}
    >
      {children}
    </div>
  );
}
```

## ⚡ 交互改进

### 1. 更好的加载状态

```typescript
// src/components/ui/loading-overlay.tsx
import { Spinner } from "@heroui/spinner";
import { motion, AnimatePresence } from "framer-motion";

interface LoadingOverlayProps {
  isLoading: boolean;
  text?: string;
}

export function LoadingOverlay({ isLoading, text = "加载中..." }: LoadingOverlayProps) {
  return (
    <AnimatePresence>
      {isLoading && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
        >
          <div className="flex flex-col items-center gap-4 rounded-lg bg-content1 p-8 shadow-xl">
            <Spinner size="lg" />
            <p className="text-sm text-default-600">{text}</p>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
```

### 2. 确认对话框优化

```typescript
// src/components/ui/confirm-dialog.tsx
import {
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter,
} from "@heroui/modal";
import { Button } from "@heroui/button";
import { AlertTriangle, Info, CheckCircle, XCircle } from "lucide-react";

interface ConfirmDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  message: string;
  variant?: "danger" | "warning" | "info" | "success";
  confirmText?: string;
  cancelText?: string;
  isLoading?: boolean;
}

export function ConfirmDialog({
  isOpen,
  onClose,
  onConfirm,
  title,
  message,
  variant = "warning",
  confirmText = "确认",
  cancelText = "取消",
  isLoading = false,
}: ConfirmDialogProps) {
  const icons = {
    danger: XCircle,
    warning: AlertTriangle,
    info: Info,
    success: CheckCircle,
  };

  const colors = {
    danger: "danger" as const,
    warning: "warning" as const,
    info: "primary" as const,
    success: "success" as const,
  };

  const Icon = icons[variant];

  return (
    <Modal isOpen={isOpen} onClose={onClose}>
      <ModalContent>
        <ModalHeader className="flex items-center gap-2">
          <Icon className="h-5 w-5" />
          {title}
        </ModalHeader>
        <ModalBody>
          <p className="text-default-600">{message}</p>
        </ModalBody>
        <ModalFooter>
          <Button
            variant="light"
            onPress={onClose}
            isDisabled={isLoading}
          >
            {cancelText}
          </Button>
          <Button
            color={colors[variant]}
            onPress={onConfirm}
            isLoading={isLoading}
          >
            {confirmText}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
```

### 3. Toast 通知优化

```typescript
// src/lib/toast.ts
import toast, { Toast } from 'react-hot-toast';
import { CheckCircle, XCircle, AlertCircle, Info } from 'lucide-react';

interface ToastOptions {
  duration?: number;
  position?: Toast['position'];
}

export const showToast = {
  success: (message: string, options?: ToastOptions) => {
    return toast.success(message, {
      duration: options?.duration || 3000,
      position: options?.position || 'top-center',
      icon: <CheckCircle className="h-5 w-5" />,
      style: {
        background: 'var(--color-success)',
        color: 'white',
      },
    });
  },

  error: (message: string, options?: ToastOptions) => {
    return toast.error(message, {
      duration: options?.duration || 4000,
      position: options?.position || 'top-center',
      icon: <XCircle className="h-5 w-5" />,
      style: {
        background: 'var(--color-danger)',
        color: 'white',
      },
    });
  },

  warning: (message: string, options?: ToastOptions) => {
    return toast(message, {
      duration: options?.duration || 3000,
      position: options?.position || 'top-center',
      icon: <AlertCircle className="h-5 w-5" />,
      style: {
        background: 'var(--color-warning)',
        color: 'white',
      },
    });
  },

  info: (message: string, options?: ToastOptions) => {
    return toast(message, {
      duration: options?.duration || 3000,
      position: options?.position || 'top-center',
      icon: <Info className="h-5 w-5" />,
      style: {
        background: 'var(--color-info)',
        color: 'white',
      },
    });
  },

  promise: <T,>(
    promise: Promise<T>,
    messages: {
      loading: string;
      success: string;
      error: string;
    },
    options?: ToastOptions
  ) => {
    return toast.promise(promise, messages, {
      position: options?.position || 'top-center',
    });
  },
};
```

## 🎭 动画系统

### 1. 页面过渡动画

```typescript
// src/components/ui/page-transition.tsx
import { motion } from "framer-motion";

const pageVariants = {
  initial: {
    opacity: 0,
    y: 20,
  },
  animate: {
    opacity: 1,
    y: 0,
    transition: {
      duration: 0.3,
      ease: "easeOut",
    },
  },
  exit: {
    opacity: 0,
    y: -20,
    transition: {
      duration: 0.2,
      ease: "easeIn",
    },
  },
};

export function PageTransition({ children }: { children: React.ReactNode }) {
  return (
    <motion.div
      variants={pageVariants}
      initial="initial"
      animate="animate"
      exit="exit"
    >
      {children}
    </motion.div>
  );
}
```

### 2. 列表项动画

```typescript
// src/components/ui/animated-list.tsx
import { motion, AnimatePresence } from "framer-motion";

const listItemVariants = {
  hidden: { opacity: 0, x: -20 },
  visible: (i: number) => ({
    opacity: 1,
    x: 0,
    transition: {
      delay: i * 0.05,
      duration: 0.3,
    },
  }),
  exit: { opacity: 0, x: 20, transition: { duration: 0.2 } },
};

interface AnimatedListProps<T> {
  items: T[];
  renderItem: (item: T, index: number) => React.ReactNode;
  keyExtractor: (item: T) => string | number;
}

export function AnimatedList<T>({
  items,
  renderItem,
  keyExtractor,
}: AnimatedListProps<T>) {
  return (
    <AnimatePresence mode="popLayout">
      {items.map((item, index) => (
        <motion.div
          key={keyExtractor(item)}
          custom={index}
          variants={listItemVariants}
          initial="hidden"
          animate="visible"
          exit="exit"
          layout
        >
          {renderItem(item, index)}
        </motion.div>
      ))}
    </AnimatePresence>
  );
}
```

## 🌙 主题切换改进

### 1. 主题切换器组件

```typescript
// src/components/ui/theme-switcher.tsx
import { useState, useEffect } from "react";
import { Button } from "@heroui/button";
import { Sun, Moon, Monitor } from "lucide-react";
import {
  Dropdown,
  DropdownTrigger,
  DropdownMenu,
  DropdownItem,
} from "@heroui/dropdown";

type Theme = "light" | "dark" | "system";

export function ThemeSwitcher() {
  const [theme, setTheme] = useState<Theme>(() => {
    return (localStorage.getItem("theme") as Theme) || "system";
  });

  useEffect(() => {
    const root = window.document.documentElement;
    root.classList.remove("light", "dark");

    if (theme === "system") {
      const systemTheme = window.matchMedia("(prefers-color-scheme: dark)")
        .matches
        ? "dark"
        : "light";
      root.classList.add(systemTheme);
    } else {
      root.classList.add(theme);
    }

    localStorage.setItem("theme", theme);
  }, [theme]);

  const themes = [
    { key: "light", label: "浅色", icon: Sun },
    { key: "dark", label: "深色", icon: Moon },
    { key: "system", label: "跟随系统", icon: Monitor },
  ] as const;

  const currentThemeConfig = themes.find((t) => t.key === theme)!;
  const Icon = currentThemeConfig.icon;

  return (
    <Dropdown>
      <DropdownTrigger>
        <Button
          isIconOnly
          variant="light"
          aria-label="切换主题"
        >
          <Icon className="h-5 w-5" />
        </Button>
      </DropdownTrigger>
      <DropdownMenu
        aria-label="主题选择"
        selectedKeys={[theme]}
        onAction={(key) => setTheme(key as Theme)}
      >
        {themes.map(({ key, label, icon: ItemIcon }) => (
          <DropdownItem
            key={key}
            startContent={<ItemIcon className="h-4 w-4" />}
          >
            {label}
          </DropdownItem>
        ))}
      </DropdownMenu>
    </Dropdown>
  );
}
```

## 📊 数据可视化改进

### 1. 统计卡片组件

```typescript
// src/components/ui/stat-card.tsx
import { Card, CardBody } from "@heroui/card";
import { TrendingUp, TrendingDown, Minus } from "lucide-react";
import { cn } from "@/lib/utils";

interface StatCardProps {
  title: string;
  value: string | number;
  change?: {
    value: number;
    trend: "up" | "down" | "neutral";
  };
  icon?: React.ReactNode;
  color?: "default" | "primary" | "success" | "warning" | "danger";
}

export function StatCard({
  title,
  value,
  change,
  icon,
  color = "default",
}: StatCardProps) {
  const trendIcons = {
    up: TrendingUp,
    down: TrendingDown,
    neutral: Minus,
  };

  const trendColors = {
    up: "text-success",
    down: "text-danger",
    neutral: "text-default-500",
  };

  const TrendIcon = change ? trendIcons[change.trend] : null;

  return (
    <Card>
      <CardBody>
        <div className="flex items-start justify-between">
          <div className="flex-1">
            <p className="text-sm text-default-500 mb-1">{title}</p>
            <p className="text-2xl font-bold">{value}</p>
            {change && TrendIcon && (
              <div className={cn("flex items-center gap-1 mt-2 text-sm", trendColors[change.trend])}>
                <TrendIcon className="h-4 w-4" />
                <span>{Math.abs(change.value)}%</span>
              </div>
            )}
          </div>
          {icon && (
            <div className={cn(
              "rounded-lg p-3",
              color === "primary" && "bg-primary/10 text-primary",
              color === "success" && "bg-success/10 text-success",
              color === "warning" && "bg-warning/10 text-warning",
              color === "danger" && "bg-danger/10 text-danger",
              color === "default" && "bg-default/10 text-default-600"
            )}>
              {icon}
            </div>
          )}
        </div>
      </CardBody>
    </Card>
  );
}
```

## 🎯 实施优先级

### 第一阶段（高优先级）
1. ✅ 创建设计 Token 系统
2. ✅ 实现增强型卡片组件
3. ✅ 优化加载和空状态
4. ✅ 改进 Toast 通知

### 第二阶段（中优先级）
5. 🔄 实现响应式网格系统
6. 🔄 添加页面过渡动画
7. 🔄 优化主题切换
8. 🔄 创建统计卡片组件

### 第三阶段（低优先级）
9. ⏳ 完善动画系统
10. ⏳ 添加更多可视化组件
11. ⏳ 实现高级交互模式
12. ⏳ 优化无障碍访问

## 📝 使用示例

### 使用新的设计 Token

```typescript
// 在组件中使用
<div className="bg-[var(--surface-1)] text-[var(--text-primary)] rounded-[var(--radius-lg)]">
  内容
</div>
```

### 使用增强型卡片

```typescript
<EnhancedCard
  title="节点统计"
  subtitle="实时监控"
  icon={<ServerCog className="h-5 w-5" />}
  variant="elevated"
  status="success"
  interactive
>
  卡片内容
</EnhancedCard>
```

### 使用响应式网格

```typescript
<ResponsiveGrid cols={{ default: 1, md: 2, lg: 3 }} gap={6}>
  {items.map(item => (
    <ItemCard key={item.id} item={item} />
  ))}
</ResponsiveGrid>
```

## 🚀 下一步行动

1. 审查并批准设计方案
2. 创建 UI 组件库
3. 更新现有页面应用新组件
4. 进行用户测试和反馈收集
5. 持续迭代优化

---

**创建日期**: 2026-10-06  
**状态**: 设计阶段  
**负责人**: 待定
