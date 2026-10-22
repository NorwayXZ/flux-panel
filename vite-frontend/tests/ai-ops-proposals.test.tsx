import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";

import AiOpsProposals from "@/components/ai-ops-proposals";
import { opsApproveProposal, opsProposals, opsRejectProposal } from "@/api/ai-ops";

vi.mock("@/api/ai-ops", () => ({
  opsProposals: vi.fn(),
  opsApproveProposal: vi.fn(),
  opsRejectProposal: vi.fn(),
}));
vi.mock("@heroui/button", () => ({
  Button: ({ children, onPress, isLoading, ...p }: any) => (
    <button disabled={isLoading} onClick={onPress} {...p}>
      {children}
    </button>
  ),
}));
vi.mock("@heroui/chip", () => ({
  Chip: ({ children }: any) => <span>{children}</span>,
}));

const pending = {
  id: 3,
  creatorId: 1,
  source: "chat",
  action: "pause_forward",
  actionLabel: "暂停转发",
  targetId: 7,
  targetName: "异常转发",
  title: "暂停异常转发",
  reason: "落地连续超时",
  status: "pending",
  createdAt: 1760000000000,
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(opsProposals).mockResolvedValue({ code: 0, msg: "", data: [pending] as any });
  vi.mocked(opsApproveProposal).mockResolvedValue({
    code: 0,
    msg: "",
    data: { ...pending, status: "executed" } as any,
  });
  vi.mocked(opsRejectProposal).mockResolvedValue({
    code: 0,
    msg: "",
    data: { ...pending, status: "rejected" } as any,
  });
});

it("shows a pending proposal and approves it through the API", async () => {
  render(<AiOpsProposals />);
  await screen.findByText(/暂停异常转发/);
  expect(screen.getByText(/落地连续超时/)).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: /批准并执行/ }));
  await waitFor(() => expect(opsApproveProposal).toHaveBeenCalledWith(3));
});

it("rejects a pending proposal through the API", async () => {
  render(<AiOpsProposals />);
  await screen.findByText(/暂停异常转发/);
  fireEvent.click(screen.getByRole("button", { name: "拒绝" }));
  await waitFor(() => expect(opsRejectProposal).toHaveBeenCalledWith(3));
});
