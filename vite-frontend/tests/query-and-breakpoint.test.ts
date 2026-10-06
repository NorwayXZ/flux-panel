import { act, renderHook, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { queryClient, sessionQueryKey } from "@/lib/query-client";
import { useBreakpoint } from "@/hooks/use-breakpoint";

it("isolates cached data between login sessions without putting credentials into keys", () => {
  localStorage.setItem("token", "test-account-a");
  const first = sessionQueryKey(["grants"]);
  queryClient.setQueryData(first, [{ id: 1 }]);
  expect(sessionQueryKey(["grants"])).toEqual(first);
  localStorage.setItem("token", "test-account-b");
  const second = sessionQueryKey(["grants"]);
  expect(queryClient.getQueryData(second)).toBeUndefined();
  expect(JSON.stringify(first)).not.toContain("test-account-a");
  expect(queryClient.getDefaultOptions().mutations?.retry).toBe(false);
  queryClient.clear();
});

it("reports widths below 640px correctly and responds to a desktop resize", async () => {
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) =>
    setTimeout(callback, 0),
  );
  vi.stubGlobal("cancelAnimationFrame", clearTimeout);
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 320,
  });
  const { result } = renderHook(() => useBreakpoint());
  expect(result.current.current).toBe("base");
  expect(result.current.isAbove("sm")).toBe(false);
  expect(result.current.isMobile).toBe(true);
  act(() => {
    window.innerWidth = 1200;
    window.dispatchEvent(new Event("resize"));
  });
  await waitFor(() => expect(result.current.isDesktop).toBe(true));
  vi.unstubAllGlobals();
});
