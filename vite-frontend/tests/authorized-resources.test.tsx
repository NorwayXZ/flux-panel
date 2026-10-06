import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import * as api from "@/api";
import { useAuthorizedEntryResources } from "@/hooks/use-api";

vi.mock("@/api", () => ({
  getAuthorizedEntryGrants: vi.fn(),
  getAuthorizedEntryTemplates: vi.fn(),
  getCrossEntryGroups: vi.fn(),
  getAllUsers: vi.fn(),
}));

it("loads customer grants without requesting administrator resources and deduplicates observers", async () => {
  vi.mocked(api.getAuthorizedEntryGrants).mockResolvedValue({
    code: 0,
    msg: "",
    data: [
      { id: 1, state: "active" },
      { id: 2, state: "deleted" },
    ] as any,
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const first = renderHook(() => useAuthorizedEntryResources(false), {
    wrapper,
  });
  const second = renderHook(() => useAuthorizedEntryResources(false), {
    wrapper,
  });
  await waitFor(() => expect(first.result.current.isSuccess).toBe(true));
  expect(second.result.current.data?.grants.map((grant) => grant.id)).toEqual([
    1,
  ]);
  expect(api.getAuthorizedEntryGrants).toHaveBeenCalledTimes(1);
  expect(api.getAuthorizedEntryTemplates).not.toHaveBeenCalled();
  expect(api.getCrossEntryGroups).not.toHaveBeenCalled();
  expect(api.getAllUsers).not.toHaveBeenCalled();
  client.clear();
});
