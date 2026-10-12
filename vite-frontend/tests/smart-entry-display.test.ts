import { describe, expect, it } from "vitest";

import {
  limitGroupsToPort,
  lineCheckState,
  matchProviderZone,
  matchZone,
  ttlDelayText,
  uniqueFallbackNames,
} from "../src/utils/smart-entry-display";

describe("smart entry display helpers", () => {
  it("explains TTL as a switching delay", () => {
    expect(ttlDelayText(5)).toBe("切换网络后最长约 5 秒生效");
    expect(ttlDelayText("600")).toBe("切换网络后最长约 10 分钟生效");
    expect(ttlDelayText(90)).toBe("切换网络后最长约 2 分钟生效");
    expect(ttlDelayText(7200)).toBe("切换网络后最长约 2 小时生效");
    expect(ttlDelayText("")).toBe("");
    expect(ttlDelayText(0)).toBe("");
  });

  it("limits entry options to the chosen public port", () => {
    const groups = [
      { port: 1000, options: [{ id: 1, name: "a", inPort: 1000 }] },
      { port: 10088, options: [{ id: 2, name: "b", inPort: 10088 }] },
    ];

    expect(limitGroupsToPort(groups, 1000)).toEqual([groups[0]]);
    expect(limitGroupsToPort(groups)).toEqual(groups);
    // A stale port keeps every option reachable instead of an empty list.
    expect(limitGroupsToPort(groups, 9)).toEqual(groups);
  });

  it("drops repeated and own entries from the backup order", () => {
    expect(
      uniqueFallbackNames(["腾讯广州", "NosLa圣何塞", "腾讯广州"], "阿里云CB"),
    ).toEqual(["腾讯广州", "NosLa圣何塞"]);
    expect(
      uniqueFallbackNames(["阿里云CB", "NosLa圣何塞", "腾讯广州"], "腾讯广州"),
    ).toEqual(["阿里云CB", "NosLa圣何塞"]);
    expect(uniqueFallbackNames([undefined, "腾讯广州"], "腾讯广州")).toEqual(
      [],
    );
  });

  it("finds the hosting zone and account for a business domain", () => {
    expect(matchZone("ggs.766733.xyz", ["766733.xyz", "eeaa.xyz"])).toBe(
      "766733.xyz",
    );
    expect(matchZone("a.b.example.com", ["example.com", "b.example.com"])).toBe(
      "b.example.com",
    );
    expect(matchZone("not766733.xyz", ["766733.xyz"])).toBe("");
    expect(matchZone("access", ["766733.xyz"])).toBe("");

    const accounts = [
      { providerRefId: "1", zones: ["766733.xyz"] },
      { providerRefId: "2", zones: ["other.com"] },
    ];

    expect(matchProviderZone("GGS.766733.xyz", accounts)).toEqual({
      providerRefId: "1",
      zoneName: "766733.xyz",
    });
    expect(matchProviderZone("os.eeaa.xyz", accounts)).toBeNull();
    expect(
      matchProviderZone("ggs.766733.xyz", [
        ...accounts,
        { providerRefId: "3", zones: ["766733.xyz"] },
      ]),
    ).toBeNull();
  });

  it("summarises one carrier line check", () => {
    const probe = (successful: boolean) => ({ successful });

    expect(
      lineCheckState({
        providerMatch: true,
        publicMatch: true,
        publicProbe: probe(true),
      }),
    ).toBe("ok");
    expect(
      lineCheckState({
        providerMatch: true,
        publicMatch: false,
        publicProbe: probe(true),
      }),
    ).toBe("pending");
    expect(
      lineCheckState({
        providerMatch: true,
        publicMatch: false,
        publicProbe: probe(false),
      }),
    ).toBe("failed");
    expect(
      lineCheckState({
        providerMatch: false,
        publicMatch: true,
        publicProbe: probe(true),
      }),
    ).toBe("mismatch");
  });
});
