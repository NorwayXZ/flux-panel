import { describe, expect, it } from "vitest";

import {
  formatBytes,
  formatBytesCompact,
  formatDateTime,
  truthy,
} from "../src/utils/format";

describe("shared formatting helpers", () => {
  it("formats timestamps and falls back for empty values", () => {
    const stamp = new Date(2026, 9, 12, 14, 5, 9).getTime();

    expect(formatDateTime(stamp)).toBe("2026/10/12 14:05:09");
    expect(formatDateTime(new Date(stamp))).toBe("2026/10/12 14:05:09");
    expect(formatDateTime(undefined, "尚未检测")).toBe("尚未检测");
    expect(formatDateTime(0, "尚未同步")).toBe("尚未同步");
    expect(formatDateTime(null)).toBe("-");
  });

  it("reads API flags sent as booleans or 0/1", () => {
    expect(truthy(true)).toBe(true);
    expect(truthy(1)).toBe(true);
    expect(truthy(0)).toBe(false);
    expect(truthy(false)).toBe(false);
    expect(truthy(undefined)).toBe(false);
    expect(truthy(2)).toBe(false);
  });

  it("formats byte counts with two decimals below 100 of a unit", () => {
    expect(formatBytes()).toBe("0 B");
    expect(formatBytes(-5)).toBe("0 B");
    expect(formatBytes(Number.NaN)).toBe("0 B");
    expect(formatBytes(42)).toBe("42 B");
    expect(formatBytes(1536)).toBe("1.50 KB");
    expect(formatBytes(512 * 1024 ** 2)).toBe("512 MB");
    expect(formatBytes(1.5 * 1024 ** 3)).toBe("1.50 GB");
    expect(formatBytes(5 * 1024 ** 5)).toBe("5120 TB");
  });

  it("formats byte counts compactly with one decimal", () => {
    expect(formatBytesCompact(0)).toBe("0 B");
    expect(formatBytesCompact(42)).toBe("42 B");
    expect(formatBytesCompact(1536)).toBe("1.5 KB");
    expect(formatBytesCompact(512 * 1024 ** 2)).toBe("512.0 MB");
  });
});
