import axios from "axios";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { testPrivateProxy } from "@/api";

vi.mock("axios", () => ({
  default: { defaults: {}, post: vi.fn(), get: vi.fn() },
}));
vi.mock("@/utils/panel", () => ({
  getPanelAddresses: vi.fn(),
  isWebViewFunc: () => false,
}));

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
});
afterEach(() => vi.useRealTimers());

function nodeRespondsAfter(delay?: number) {
  vi.mocked(axios.post).mockImplementation((_url, _body, options) =>
    new Promise((resolve, reject) => {
      const deadline = setTimeout(() => reject({ code: "ECONNABORTED", message: "request timed out" }), options?.timeout);
      if (delay !== undefined) {
        setTimeout(() => {
          clearTimeout(deadline);
          resolve({ data: { code: 0, data: { ok: true, ip: "192.0.2.1" } } });
        }, delay);
      }
    }),
  );
}

describe("private proxy check waiting", () => {
  it("keeps a node result that arrives after the ordinary API deadline", async () => {
    nodeRespondsAfter(45_000);
    const result = testPrivateProxy(32);
    await vi.advanceTimersByTimeAsync(45_000);
    expect((await result).data.ok).toBe(true);
  });

  it("still fails within a bounded interval when no result ever arrives", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    nodeRespondsAfter();
    const result = testPrivateProxy(32);
    await vi.advanceTimersByTimeAsync(80_000);
    expect((await result).code).not.toBe(0);
    vi.mocked(console.error).mockRestore();
  });
});
