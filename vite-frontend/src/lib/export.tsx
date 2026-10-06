/**
 * 数据导出功能
 *
 * 支持导出为 CSV, Excel, JSON 等格式
 */

import { Button } from "@heroui/button";
import {
  Dropdown,
  DropdownTrigger,
  DropdownMenu,
  DropdownItem,
} from "@heroui/dropdown";
import { Download, FileSpreadsheet, FileJson, FileText } from "lucide-react";
import { useState } from "react";
import toast from "react-hot-toast";

export type ExportFormat = "csv" | "xlsx" | "json";

export interface ExportColumn<T = any> {
  /** 字段键 */
  key: keyof T | string;
  /** 列标题 */
  label: string;
  /** 自定义格式化函数 */
  format?: (value: any, item: T) => string | number;
}

export interface ExportOptions<T = any> {
  /** 导出的数据 */
  data: T[];
  /** 文件名（不含扩展名） */
  filename: string;
  /** 导出格式 */
  format: ExportFormat;
  /** 列配置（不提供则导出所有字段） */
  columns?: ExportColumn<T>[];
  /** 是否包含时间戳 */
  includeTimestamp?: boolean;
}

/**
 * 导出为 CSV
 */
export function exportToCSV<T>(options: ExportOptions<T>): void {
  const { data, filename, columns, includeTimestamp = true } = options;

  if (data.length === 0) {
    throw new Error("没有数据可导出");
  }

  // 确定列
  const cols: ExportColumn<T>[] =
    columns ||
    Object.keys(data[0] as object).map((key) => ({
      key,
      label: key,
    }));

  // 构建 CSV 内容
  const headers = cols.map((col) => escapeCsvCell(col.label)).join(",");
  const rows = data.map((item) => {
    return cols
      .map((col) => {
        const value = col.format
          ? col.format((item as any)[col.key], item)
          : (item as any)[col.key];

        // CSV 转义：包含逗号、换行或引号的值需要用引号包裹
        return escapeCsvCell(value);
      })
      .join(",");
  });

  const csv = [headers, ...rows].join("\n");

  // 添加 BOM 以支持 Excel 正确显示中文
  const BOM = "﻿";
  const blob = new Blob([BOM + csv], { type: "text/csv;charset=utf-8;" });

  const finalFilename = includeTimestamp
    ? `${filename}_${getTimestamp()}.csv`
    : `${filename}.csv`;

  downloadBlob(blob, finalFilename);
}

/**
 * 导出为 Excel
 */
export async function exportToExcel<T>(
  options: ExportOptions<T>,
): Promise<void> {
  const { data, filename, columns, includeTimestamp = true } = options;

  if (data.length === 0) {
    throw new Error("没有数据可导出");
  }

  // 确定列
  const cols: ExportColumn<T>[] =
    columns ||
    Object.keys(data[0] as object).map((key) => ({
      key,
      label: key,
    }));

  // 构建表格数据
  const worksheetData = [
    // 表头
    cols.map((col) => col.label),
    // 数据行
    ...data.map((item) => {
      return cols.map((col) => {
        const value = col.format
          ? col.format((item as any)[col.key], item)
          : (item as any)[col.key];

        return value ?? "";
      });
    }),
  ];

  // 创建工作表
  const { default: ExcelJS } = await import("exceljs");
  const workbook = new ExcelJS.Workbook();
  const worksheet = workbook.addWorksheet("Data");

  worksheet.addRows(worksheetData);

  // 设置列宽
  const colWidths = cols.map((col) => ({
    width: Math.min(
      60,
      data.reduce((width, item) => {
        const value = col.format
          ? col.format((item as any)[col.key], item)
          : (item as any)[col.key];

        return Math.max(width, String(value ?? "").length);
      }, col.label.length) + 2,
    ),
  }));

  worksheet.columns.forEach((column, index) => {
    column.width = colWidths[index].width;
  });

  const finalFilename = includeTimestamp
    ? `${filename}_${getTimestamp()}.xlsx`
    : `${filename}.xlsx`;

  // 导出
  const buffer = await workbook.xlsx.writeBuffer();

  downloadBlob(
    new Blob([new Uint8Array(buffer)], {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    }),
    finalFilename,
  );
}

/**
 * 导出为 JSON
 */
export function exportToJSON<T>(options: ExportOptions<T>): void {
  const { data, filename, columns, includeTimestamp = true } = options;

  if (data.length === 0) {
    throw new Error("没有数据可导出");
  }

  let exportData: any[];

  if (columns) {
    // 只导出指定列
    exportData = data.map((item) => {
      const obj: any = {};

      columns.forEach((col) => {
        const value = col.format
          ? col.format((item as any)[col.key], item)
          : (item as any)[col.key];

        obj[col.label] = value;
      });

      return obj;
    });
  } else {
    // 导出所有字段
    exportData = data;
  }

  const json = JSON.stringify(exportData, null, 2);
  const blob = new Blob([json], { type: "application/json;charset=utf-8;" });

  const finalFilename = includeTimestamp
    ? `${filename}_${getTimestamp()}.json`
    : `${filename}.json`;

  downloadBlob(blob, finalFilename);
}

/**
 * 通用导出函数
 */
export async function exportData<T>(options: ExportOptions<T>): Promise<void> {
  const { format } = options;

  switch (format) {
    case "csv":
      exportToCSV(options);
      break;
    case "xlsx":
      await exportToExcel(options);
      break;
    case "json":
      exportToJSON(options);
      break;
    default:
      throw new Error(`不支持的导出格式: ${format}`);
  }
}

export function escapeCsvCell(value: unknown): string {
  let text = String(value ?? "");

  if (typeof value === "string" && /^[\s]*[=+\-@]/.test(text))
    text = `'${text}`;

  return /[,\r\n"]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/**
 * 下载 Blob
 */
function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");

  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

/**
 * 获取时间戳字符串
 */
function getTimestamp(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  const hour = String(now.getHours()).padStart(2, "0");
  const minute = String(now.getMinutes()).padStart(2, "0");
  const second = String(now.getSeconds()).padStart(2, "0");

  return `${year}${month}${day}_${hour}${minute}${second}`;
}

/**
 * 导出菜单组件
 */
export interface ExportMenuProps<T = any> {
  /** 要导出的数据 */
  data: T[];
  /** 文件名 */
  filename: string;
  /** 列配置 */
  columns?: ExportColumn<T>[];
  /** 支持的格式 */
  formats?: ExportFormat[];
  /** 按钮变体 */
  variant?: "solid" | "flat" | "bordered" | "light";
  /** 按钮颜色 */
  color?: "default" | "primary" | "success" | "warning" | "danger";
  /** 按钮大小 */
  size?: "sm" | "md" | "lg";
  /** 是否显示为图标按钮 */
  iconOnly?: boolean;
  /** 自定义类名 */
  className?: string;
  /** 导出前回调 */
  onBeforeExport?: (format: ExportFormat) => void;
  /** 导出后回调 */
  onAfterExport?: (format: ExportFormat) => void;
}

/**
 * 导出菜单组件
 *
 * @example
 * ```tsx
 * <ExportMenu
 *   data={nodes}
 *   filename="nodes"
 *   columns={[
 *     { key: 'id', label: 'ID' },
 *     { key: 'name', label: '名称' },
 *     { key: 'status', label: '状态', format: (v) => v ? '在线' : '离线' },
 *   ]}
 *   formats={['csv', 'xlsx', 'json']}
 * />
 * ```
 */
export function ExportMenu<T>({
  data,
  filename,
  columns,
  formats = ["csv", "xlsx", "json"],
  variant = "flat",
  color = "default",
  size = "md",
  iconOnly = false,
  className,
  onBeforeExport,
  onAfterExport,
}: ExportMenuProps<T>) {
  const [busy, setBusy] = useState(false);
  const handleExport = async (format: ExportFormat) => {
    if (busy) return;
    setBusy(true);
    try {
      onBeforeExport?.(format);

      await exportData({
        data,
        filename,
        format,
        columns,
      });

      onAfterExport?.(format);
    } catch (error) {
      console.error("导出失败:", error);
      toast.error(error instanceof Error ? error.message : "导出失败");
    } finally {
      setBusy(false);
    }
  };

  const formatConfig = {
    csv: {
      label: "导出为 CSV",
      icon: <FileText className="h-4 w-4" />,
    },
    xlsx: {
      label: "导出为 Excel",
      icon: <FileSpreadsheet className="h-4 w-4" />,
    },
    json: {
      label: "导出为 JSON",
      icon: <FileJson className="h-4 w-4" />,
    },
  };

  return (
    <Dropdown>
      <DropdownTrigger>
        <Button
          variant={variant}
          color={color}
          size={size}
          startContent={
            !iconOnly ? <Download className="h-4 w-4" /> : undefined
          }
          isIconOnly={iconOnly}
          className={className}
          isDisabled={busy || data.length === 0}
          isLoading={busy}
          aria-label="导出数据"
        >
          {iconOnly ? <Download className="h-4 w-4" /> : "导出"}
        </Button>
      </DropdownTrigger>
      <DropdownMenu aria-label="导出格式">
        {formats.map((format) => (
          <DropdownItem
            key={format}
            startContent={formatConfig[format].icon}
            onPress={() => void handleExport(format)}
            textValue={formatConfig[format].label}
          >
            {formatConfig[format].label}
          </DropdownItem>
        ))}
      </DropdownMenu>
    </Dropdown>
  );
}

/**
 * 快速导出按钮（单一格式）
 */
export interface QuickExportButtonProps<T = any> {
  data: T[];
  filename: string;
  format: ExportFormat;
  columns?: ExportColumn<T>[];
  variant?: "solid" | "flat" | "bordered" | "light";
  color?: "default" | "primary" | "success" | "warning" | "danger";
  size?: "sm" | "md" | "lg";
  className?: string;
  children?: React.ReactNode;
}

export function QuickExportButton<T>({
  data,
  filename,
  format,
  columns,
  variant = "flat",
  color = "default",
  size = "md",
  className,
  children,
}: QuickExportButtonProps<T>) {
  const [busy, setBusy] = useState(false);
  const handleExport = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await exportData({
        data,
        filename,
        format,
        columns,
      });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "导出失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Button
      variant={variant}
      color={color}
      size={size}
      startContent={<Download className="h-4 w-4" />}
      onPress={() => void handleExport()}
      isDisabled={busy || data.length === 0}
      isLoading={busy}
      className={className}
    >
      {children || `导出为 ${format.toUpperCase()}`}
    </Button>
  );
}
