import { describe, expect, it } from "vitest";
import { hasVerifiedRouterDns } from "@/utils/openwrt-dns-status";
import type { OpenWrtDnsResolver } from "@/api";

const ready: OpenWrtDnsResolver = {
  connectorId: 1,
  name: "router",
  platform: "openwrt",
  connectorRole: "openwrt_dns",
  version: "2.56.0",
  online: true,
  policyRevision: 3,
  appliedRevision: 3,
  dnsReady: true,
  dnsCheckedAt: 1,
  reportedAt: 100000,
};
describe("router DNS verification", () => {
  it("does not hide a working strategy because another bound domain failed", () => {
    const router = {
      ...ready,
      dnsReady: false,
      dnsStatus: "domain-error",
      lastError: "another domain failed",
      dnsChecks: [
        {
          domain: "a.example.test",
          recordType: "A",
          expectedAddress: "192.0.2.1",
          answers: ["192.0.2.1"],
          state: "ready" as const,
        },
      ],
    };
    expect(hasVerifiedRouterDns(router, 100001)).toBe(false);
    expect(
      hasVerifiedRouterDns(router, 100001, {
        domain: "a.example.test",
        recordType: "A",
      }),
    ).toBe(true);
    expect(
      hasVerifiedRouterDns(router, 100001, {
        domain: "b.example.test",
        recordType: "A",
      }),
    ).toBe(false);
  });
  it("does not treat an acknowledged policy as usable DNS", () => {
    expect(hasVerifiedRouterDns({ ...ready, dnsReady: false }, 100001)).toBe(
      false,
    );
    expect(
      hasVerifiedRouterDns({ ...ready, dnsReady: undefined }, 100001),
    ).toBe(false);
  });
  it("requires current policy, an online router, and a recent report", () => {
    expect(hasVerifiedRouterDns(ready, 100001)).toBe(true);
    expect(hasVerifiedRouterDns({ ...ready, appliedRevision: 2 }, 100001)).toBe(
      false,
    );
    expect(hasVerifiedRouterDns({ ...ready, appliedRevision: 4 }, 100001)).toBe(
      false,
    );
    expect(hasVerifiedRouterDns({ ...ready, online: false }, 100001)).toBe(
      false,
    );
    expect(hasVerifiedRouterDns(ready, 160001)).toBe(false);
  });
  it("rejects old agents and surfaced errors even if cached state was green", () => {
    expect(hasVerifiedRouterDns({ ...ready, version: "2.54.0" }, 100001)).toBe(
      false,
    );
    expect(
      hasVerifiedRouterDns({ ...ready, lastError: "rules not loaded" }, 100001),
    ).toBe(false);
  });
  it("uses server report time instead of assuming router clocks are synchronized", () => {
    expect(
      hasVerifiedRouterDns({ ...ready, dnsCheckedAt: 9999999999 }, 100001),
    ).toBe(true);
    expect(hasVerifiedRouterDns({ ...ready, reportedAt: 100002 }, 100001)).toBe(
      false,
    );
  });
});
