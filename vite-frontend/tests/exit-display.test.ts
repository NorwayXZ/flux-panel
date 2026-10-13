import { describe, expect, it } from "vitest";

import {
  agentSupportsExit,
  exitMemberMeta,
  exitStatusMeta,
  exitSummary,
  formatExitDuration,
  sortExitCountries,
} from "@/utils/exit-display";

describe("formatExitDuration", () => {
  it("writes short durations in minutes", () => {
    expect(formatExitDuration(20_000)).toBe("不到 1 分钟");
    expect(formatExitDuration(5 * 60_000)).toBe("5 分钟");
  });

  it("writes hours and days", () => {
    expect(formatExitDuration(3 * 3_600_000 + 12 * 60_000)).toBe(
      "3 小时 12 分",
    );
    expect(formatExitDuration(2 * 3_600_000)).toBe("2 小时");
    expect(formatExitDuration(26 * 3_600_000)).toBe("1 天 2 小时");
    expect(formatExitDuration(48 * 3_600_000)).toBe("2 天");
  });

  it("handles missing values", () => {
    expect(formatExitDuration(undefined)).toBe("-");
    expect(formatExitDuration(-5)).toBe("-");
  });
});

describe("exitSummary", () => {
  it("describes a working exit", () => {
    const now = 10 * 3_600_000;

    expect(
      exitSummary(
        {
          country: "JP",
          countryName: "日本",
          status: "ok",
          exitIp: "126.115.107.89",
          exitOwner: "SoftBank",
          activeSince: now - 90 * 60_000,
        },
        now,
      ),
    ).toBe("126.115.107.89 · SoftBank · 已稳定 1 小时 30 分");
  });

  it("shows the reason when there is no exit", () => {
    expect(
      exitSummary({
        country: "US",
        countryName: "美国",
        status: "no_exit",
        detail: "当前没有美国的家宽候选",
      }),
    ).toBe("当前没有美国的家宽候选");
    expect(
      exitSummary({ country: "JP", countryName: "日本", status: "searching" }),
    ).toBe("正在寻找出口");
  });
});

describe("status labels", () => {
  it("names group states", () => {
    expect(exitStatusMeta("ok")).toEqual({ label: "正常", color: "success" });
    expect(exitStatusMeta("no_exit").color).toBe("danger");
    expect(exitStatusMeta(undefined).label).toBe("未知");
  });

  it("distinguishes the exit in use from the standby", () => {
    expect(exitMemberMeta({ state: "up", active: 1, approved: 1 }).label).toBe(
      "使用中",
    );
    expect(exitMemberMeta({ state: "up", active: 0, approved: 1 }).label).toBe(
      "备用",
    );
    expect(exitMemberMeta({ state: "up", active: 0, approved: 0 }).label).toBe(
      "等待判定",
    );
    expect(exitMemberMeta({ state: "failed" }).label).toBe("连不上");
    expect(exitMemberMeta({ state: "rejected" }).label).toBe("已排除");
  });
});

describe("sortExitCountries", () => {
  it("puts selectable countries first", () => {
    const sorted = sortExitCountries([
      { country: "US", name: "美国", total: 3, available: 0 },
      { country: "KR", name: "韩国", total: 28, available: 20 },
      { country: "JP", name: "日本", total: 54, available: 31 },
    ]);

    expect(sorted.map((item) => item.country)).toEqual(["JP", "KR", "US"]);
  });
});

describe("agentSupportsExit", () => {
  it("compares versions numerically", () => {
    expect(agentSupportsExit("2.57.0", "2.57.0")).toBe(true);
    expect(agentSupportsExit("v2.60.1", "2.57.0")).toBe(true);
    expect(agentSupportsExit("2.100.0", "2.57.0")).toBe(true);
    expect(agentSupportsExit("2.56.0", "2.57.0")).toBe(false);
    expect(agentSupportsExit("2.51.37", "2.57.0")).toBe(false);
    expect(agentSupportsExit(undefined, "2.57.0")).toBe(false);
    expect(agentSupportsExit("dev", "2.57.0")).toBe(false);
  });
});

describe("exitPurity", () => {
  it("rates home addresses by the available signals", async () => {
    const { exitPurity } = await import("@/utils/exit-display");

    expect(exitPurity({ kind: "home" }).label).toBe("较干净");
    expect(exitPurity({ kind: "home", flaggedProxy: 1 }).label).toBe(
      "已被标记",
    );
    expect(exitPurity({ kind: "home", ipqsScore: 10 }).label).toBe("干净");
    expect(exitPurity({ kind: "home", ipqsScore: 90 }).label).toBe("风险高");
    expect(exitPurity({ kind: "home", abuseScore: 60 }).label).toBe("风险高");
    expect(
      exitPurity({ kind: "home", flaggedProxy: 1, ipqsScore: 20 }).label,
    ).toBe("已被标记");
  });

  it("marks addresses that are not home broadband", async () => {
    const { exitPurity } = await import("@/utils/exit-display");

    expect(exitPurity({ kind: "hosting" }).label).toBe("不是家宽");
    expect(exitPurity({ kind: "project" }).reason).toContain("项目方");
  });
});
