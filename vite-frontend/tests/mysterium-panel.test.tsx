import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import MysteriumPanel from "@/components/mysterium-panel";
import { getMysteriumOverview, setupMysteriumNode } from "@/api";

vi.mock("@/api", () => ({
  getMysteriumOverview: vi.fn(),
  getMysteriumSharedBilling: vi.fn(async () => ({ code: 0, data: { members: [] } })),
  configureMysteriumSharedBilling: vi.fn(),
  getMysteriumCountries: vi.fn(async () => ({ code: 0, data: [] })),
  setupMysteriumNode: vi.fn(),
  saveMysteriumSettings: vi.fn(),
  refreshMysterium: vi.fn(),
  reconnectMysterium: vi.fn(),
  topupMysterium: vi.fn(),
}));
vi.mock("@heroui/select", () => ({
  Select: ({ children, onSelectionChange, selectedKeys, isDisabled, "aria-label": ariaLabel }: any) => (
    <select
      aria-label={ariaLabel || "选择节点"}
      disabled={isDisabled}
      value={[...selectedKeys][0] || ""}
      onChange={(event) => onSelectionChange(new Set([event.target.value]))}
    >
      <option value="">选择节点</option>
      {children}
    </select>
  ),
  SelectItem: ({ children }: any) => <option value="7">{children}</option>,
}));
vi.mock("@heroui/button", () => ({
  Button: ({ children, onPress, isDisabled, isLoading }: any) => (
    <button disabled={isDisabled || isLoading} onClick={onPress}>
      {children}
    </button>
  ),
}));

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getMysteriumOverview).mockResolvedValue({
    code: 0,
    msg: "",
    data: {
      settings: {
        enabled: true,
        monthlyBudgetUsd: 20,
        lowBalanceMyst: 10,
        mystUsd: 0.08,
        agentVersion: "2.83.0",
      },
      nodes: [{ id: 7, name: "测试节点", online: true, supported: true }],
      accounts: [],
    },
  } as any);
});
async function startSetup() {
  render(<MysteriumPanel />);
  await screen.findByLabelText("选择节点");
  fireEvent.change(screen.getByLabelText("选择节点"), {
    target: { value: "7" },
  });
  fireEvent.click(screen.getByRole("button", { name: "在节点上启用" }));
}
describe("Mysterium node activation feedback", () => {
  it("keeps backend errors visible and allows another attempt", async () => {
    vi.mocked(setupMysteriumNode).mockResolvedValue({
      code: -1,
      msg: "download myst: connection refused",
      data: null,
    } as any);
    await startSetup();
    expect((await screen.findByRole("alert")).textContent).toContain(
      "download myst: connection refused",
    );
    expect(
      (
        screen.getByRole("button", {
          name: "在节点上启用",
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(false);
  });
  it("shows progress, prevents duplicate submissions, and recovers after a rejected request", async () => {
    let reject!: (error: Error) => void;
    vi.mocked(setupMysteriumNode).mockImplementation(
      () =>
        new Promise((_, fail) => {
          reject = fail;
        }),
    );
    await startSetup();
    expect(screen.getByRole("status").textContent).toContain(
      "正在为“测试节点”",
    );
    fireEvent.click(screen.getByRole("button", { name: "在节点上启用" }));
    expect(setupMysteriumNode).toHaveBeenCalledTimes(1);
    await act(async () => reject(new Error("network disconnected")));
    expect((await screen.findByRole("alert")).textContent).toContain(
      "network disconnected",
    );
    expect(
      (
        screen.getByRole("button", {
          name: "在节点上启用",
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(false);
  });
  it("does not announce success when the agent returns an empty account", async () => {
    vi.mocked(setupMysteriumNode).mockResolvedValue({
      code: 0,
      msg: "",
      data: { installed: false },
    } as any);
    await startSetup();
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain("未返回有效账户"),
    );
  });
  it("displays the returned account without depending on another overview request", async () => {
    vi.mocked(setupMysteriumNode).mockResolvedValue({
      code: 0,
      msg: "",
      data: {
        nodeId: 7,
        nodeName: "测试节点",
        identity: "0xabc",
        balance: 0,
        balanceUsd: 0,
        monthSpent: 0,
        monthSpentUsd: 0,
        suspended: false,
        connections: [],
      },
    });
    await startSetup();
    expect((await screen.findByRole("status")).textContent).toContain(
      "已在“测试节点”上启用",
    );
    expect(screen.getByRole("button", { name: /充值/ })).toBeTruthy();
    expect(getMysteriumOverview).toHaveBeenCalledTimes(1);
  });
});
