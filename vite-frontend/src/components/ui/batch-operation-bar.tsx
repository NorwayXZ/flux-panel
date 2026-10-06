/**
 * 批量操作工具栏组件
 *
 * 当有项目被选中时显示，提供批量操作功能
 */

import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import {
  Dropdown,
  DropdownTrigger,
  DropdownMenu,
  DropdownItem,
} from "@heroui/dropdown";
import {
  Trash2,
  Power,
  PowerOff,
  Download,
  MoreVertical,
  X,
  CheckCircle,
} from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";

import { cn } from "@/lib/utils";

export interface BatchOperation {
  key: string;
  label: string;
  icon?: React.ReactNode;
  color?: "default" | "primary" | "success" | "warning" | "danger";
  action: () => void | Promise<void>;
}

export interface BatchOperationBarProps {
  /** 已选中的项目数量 */
  selectedCount: number;
  /** 取消选择回调 */
  onClearSelection?: () => void;
  /** 删除操作 */
  onDelete?: () => void | Promise<void>;
  /** 启用操作 */
  onEnable?: () => void | Promise<void>;
  /** 禁用操作 */
  onDisable?: () => void | Promise<void>;
  /** 导出操作 */
  onExport?: () => void | Promise<void>;
  /** 其他自定义操作 */
  customOperations?: BatchOperation[];
  /** 是否正在执行操作 */
  loading?: boolean;
  /** 自定义类名 */
  className?: string;
  /** 位置 */
  position?: "top" | "bottom" | "fixed";
}

/**
 * 批量操作工具栏
 *
 * @example
 * ```tsx
 * <BatchOperationBar
 *   selectedCount={5}
 *   onClearSelection={() => selection.deselectAll()}
 *   onDelete={handleBatchDelete}
 *   onEnable={handleBatchEnable}
 *   onExport={handleBatchExport}
 * />
 * ```
 */
export function BatchOperationBar({
  selectedCount,
  onClearSelection,
  onDelete,
  onEnable,
  onDisable,
  onExport,
  customOperations = [],
  loading = false,
  className,
  position = "fixed",
}: BatchOperationBarProps) {
  const isVisible = selectedCount > 0;

  const positionClasses = {
    top: "sticky top-4 z-20",
    bottom: "sticky bottom-4 z-20",
    fixed: "fixed bottom-6 left-1/2 -translate-x-1/2 z-50",
  };

  return (
    <AnimatePresence>
      {isVisible && (
        <motion.div
          initial={{
            opacity: 0,
            y: position === "bottom" ? 20 : -20,
            scale: 0.95,
          }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{
            opacity: 0,
            y: position === "bottom" ? 20 : -20,
            scale: 0.95,
          }}
          transition={{ duration: 0.2 }}
          className={cn(
            positionClasses[position],
            "bg-content1 shadow-2xl rounded-full px-4 py-3 border-2 border-divider",
            "backdrop-blur-md bg-opacity-95",
            className,
          )}
        >
          <div className="flex flex-wrap items-center justify-center gap-3">
            {/* 选中数量 */}
            <div className="flex items-center gap-2 px-3">
              <CheckCircle className="h-4 w-4 text-primary" />
              <span className="text-sm font-medium">
                已选择{" "}
                <span className="text-primary font-bold">{selectedCount}</span>{" "}
                项
              </span>
            </div>

            <div className="h-6 w-px bg-divider" />

            {/* 快速操作按钮 */}
            <div className="flex flex-wrap items-center gap-2">
              {onEnable && (
                <Button
                  size="sm"
                  variant="flat"
                  color="success"
                  startContent={<Power className="h-4 w-4" />}
                  onPress={onEnable}
                  isLoading={loading}
                  isDisabled={loading}
                >
                  启用
                </Button>
              )}

              {onDisable && (
                <Button
                  size="sm"
                  variant="flat"
                  color="warning"
                  startContent={<PowerOff className="h-4 w-4" />}
                  onPress={onDisable}
                  isLoading={loading}
                  isDisabled={loading}
                >
                  禁用
                </Button>
              )}

              {onExport && (
                <Button
                  size="sm"
                  variant="flat"
                  color="primary"
                  startContent={<Download className="h-4 w-4" />}
                  onPress={onExport}
                  isLoading={loading}
                  isDisabled={loading}
                >
                  导出
                </Button>
              )}

              {onDelete && (
                <Button
                  size="sm"
                  variant="flat"
                  color="danger"
                  startContent={<Trash2 className="h-4 w-4" />}
                  onPress={onDelete}
                  isLoading={loading}
                  isDisabled={loading}
                >
                  删除
                </Button>
              )}

              {/* 更多操作下拉菜单 */}
              {customOperations.length > 0 && (
                <Dropdown>
                  <DropdownTrigger>
                    <Button
                      size="sm"
                      variant="flat"
                      isIconOnly
                      isDisabled={loading}
                    >
                      <MoreVertical className="h-4 w-4" />
                    </Button>
                  </DropdownTrigger>
                  <DropdownMenu aria-label="批量操作">
                    {customOperations.map((op) => (
                      <DropdownItem
                        key={op.key}
                        color={op.color}
                        startContent={op.icon}
                        onPress={op.action}
                      >
                        {op.label}
                      </DropdownItem>
                    ))}
                  </DropdownMenu>
                </Dropdown>
              )}
            </div>

            {/* 取消选择 */}
            {onClearSelection && (
              <>
                <div className="h-6 w-px bg-divider" />
                <Button
                  size="sm"
                  variant="light"
                  isIconOnly
                  onPress={onClearSelection}
                  isDisabled={loading}
                >
                  <X className="h-4 w-4" />
                </Button>
              </>
            )}
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

/**
 * 紧凑版批量操作栏（用于表格顶部）
 */
export interface CompactBatchBarProps {
  selectedCount: number;
  totalCount: number;
  onSelectAll?: () => void;
  onClearSelection?: () => void;
  actions?: React.ReactNode;
  className?: string;
}

export function CompactBatchBar({
  selectedCount,
  totalCount,
  onSelectAll,
  onClearSelection,
  actions,
  className,
}: CompactBatchBarProps) {
  const isVisible = selectedCount > 0;

  return (
    <AnimatePresence>
      {isVisible && (
        <motion.div
          initial={{ opacity: 0, height: 0 }}
          animate={{ opacity: 1, height: "auto" }}
          exit={{ opacity: 0, height: 0 }}
          className={cn(
            "bg-primary/10 border-l-4 border-primary px-4 py-3 rounded-lg",
            className,
          )}
        >
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <Chip color="primary" size="sm" variant="flat">
                {selectedCount} / {totalCount}
              </Chip>
              <span className="text-sm text-default-600">
                已选择 {selectedCount} 项
              </span>
              {selectedCount < totalCount && onSelectAll && (
                <Button
                  size="sm"
                  variant="light"
                  color="primary"
                  onPress={onSelectAll}
                >
                  全选
                </Button>
              )}
            </div>

            <div className="flex items-center gap-2">
              {actions}
              {onClearSelection && (
                <Button size="sm" variant="light" onPress={onClearSelection}>
                  取消选择
                </Button>
              )}
            </div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

/**
 * 批量操作确认对话框数据
 */
export interface BatchConfirmData {
  title: string;
  message: string;
  confirmText?: string;
  confirmColor?: "primary" | "success" | "warning" | "danger";
  items: Array<{ id: number; name: string }>;
}

/**
 * 生成批量删除确认数据
 */
export function createBatchDeleteConfirm(
  items: Array<{ id: number; name: string }>,
): BatchConfirmData {
  return {
    title: "批量删除确认",
    message: `确定要删除以下 ${items.length} 项吗？此操作无法撤销。`,
    confirmText: "删除",
    confirmColor: "danger",
    items,
  };
}

/**
 * 生成批量启用确认数据
 */
export function createBatchEnableConfirm(
  items: Array<{ id: number; name: string }>,
): BatchConfirmData {
  return {
    title: "批量启用确认",
    message: `确定要启用以下 ${items.length} 项吗？`,
    confirmText: "启用",
    confirmColor: "success",
    items,
  };
}

/**
 * 生成批量禁用确认数据
 */
export function createBatchDisableConfirm(
  items: Array<{ id: number; name: string }>,
): BatchConfirmData {
  return {
    title: "批量禁用确认",
    message: `确定要禁用以下 ${items.length} 项吗？`,
    confirmText: "禁用",
    confirmColor: "warning",
    items,
  };
}
