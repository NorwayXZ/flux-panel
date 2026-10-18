import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import AiOpsPage from "@/pages/ai-ops";
import { opsOverview, opsSave, opsRun } from "@/api/ai-ops";
vi.mock("@/components/ai-ops-chat", () => ({ default: () => <div /> }));
vi.mock("@/api/ai-ops", () => ({
  opsOverview: vi.fn(),
  opsSave: vi.fn(),
  opsRun: vi.fn(),
  opsSettings: vi.fn(),
  opsDelete: vi.fn(),
  opsDetail: vi.fn(),
  opsPreferences: vi.fn(async () => ({ code: 0, data: { alertDiagnosis: true, weeklyReport: true } })),
  opsSavePreferences: vi.fn(),
  opsReports: vi.fn(async () => ({ code: 0, data: [] })),
  opsGenerateReport: vi.fn(),
  opsNotes: vi.fn(async () => ({ code: 0, data: [] })),
  opsSaveNote: vi.fn(),
  opsChanges: vi.fn(async () => ({ code: 0, data: [] })),
  opsRestoreChange: vi.fn(),
  opsChatSearch: vi.fn(async () => ({ code: 0, data: [] })),
  opsChatRename: vi.fn(),
}));
vi.mock("@heroui/button", () => ({
  Button: ({ children, onPress, isDisabled, isLoading, ...p }: any) => (
    <button
      aria-label={p["aria-label"]}
      disabled={isDisabled || isLoading}
      onClick={onPress}
    >
      {children}
    </button>
  ),
}));
vi.mock("@heroui/input", () => ({
  Input: ({ label, value, onValueChange, type }: any) => (
    <label>
      {label}
      <input
        type={type}
        value={value}
        onChange={(e) => onValueChange(e.target.value)}
      />
    </label>
  ),
}));
vi.mock("@heroui/switch", () => ({
  Switch: ({ children, isSelected, isDisabled, onValueChange, ...p }: any) => (
    <label>
      {children}
      <input
        aria-label={p["aria-label"]}
        type="checkbox"
        checked={isSelected}
        disabled={isDisabled}
        onChange={(e) => onValueChange(e.target.checked)}
      />
    </label>
  ),
}));
vi.mock("@heroui/tabs", () => ({
  Tabs: ({ children }: any) => <div>{children}</div>,
  Tab: ({ children }: any) => <section>{children}</section>,
}));
vi.mock("@heroui/modal", () => ({
  Modal: ({ children, isOpen }: any) => (isOpen ? <div>{children}</div> : null),
  ModalContent: ({ children }: any) => <div>{children}</div>,
  ModalHeader: ({ children }: any) => <div>{children}</div>,
  ModalBody: ({ children }: any) => <div>{children}</div>,
  ModalFooter: ({ children }: any) => <div>{children}</div>,
}));
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(opsOverview).mockResolvedValue({
    code: 0,
    msg: "",
    data: {
      settings: { baseUrl: "", model: "", keyConfigured: false },
      tasks: [],
      nodes: [{ id: 1, name: "测试节点", version: "2.84.0-beta.3", status: 1 }],
      minimumAgentVersion: "2.84.0-beta.3",
    },
  });
  vi.mocked(opsSave).mockResolvedValue({ code: 0, msg: "", data: null });
});
it("allows preparing a disabled task without model credentials and preserves multiple service names", async () => {
  render(<AiOpsPage />);
  await screen.findByText(/AI 模型尚未配置/);
  fireEvent.click(screen.getByRole("button", { name: "新建任务" }));
  fireEvent.change(screen.getByLabelText("任务名称"), {
    target: { value: "磁盘巡检" },
  });
  fireEvent.change(screen.getByLabelText("任务要求"), {
    target: { value: "检查磁盘" },
  });
  fireEvent.click(screen.getByLabelText(/测试节点/));
  const services = screen.getByLabelText("服务白名单（逗号分隔）");
  fireEvent.change(services, { target: { value: "nginx.service," } });
  expect((services as HTMLInputElement).value).toBe("nginx.service,");
  fireEvent.change(services, {
    target: { value: "nginx.service, app.service" },
  });
  expect(
    (screen.getByLabelText("启用定时执行") as HTMLInputElement).disabled,
  ).toBe(true);
  fireEvent.click(screen.getByRole("button", { name: "保存任务" }));
  await waitFor(() => expect(opsSave).toHaveBeenCalled());
  expect(vi.mocked(opsSave).mock.calls[0][0]).toMatchObject({
    enabled: false,
    config: {
      name: "磁盘巡检",
      nodeIds: [1],
      mode: "inspect",
      allowLogCleanup: false,
      serviceAllowlist: ["nginx.service", "app.service"],
    },
  });
  expect(opsRun).not.toHaveBeenCalled();
});
it("disables running when the model is unconfigured", async () => {
  const r = await opsOverview();
  r.data.tasks = [
    {
      id: 2,
      enabled: false,
      running: false,
      nextRun: 0,
      config: {
        name: "daily",
        prompt: "check",
        nodeIds: [1],
        cron: "0 0 3 * * *",
        timezone: "Asia/Shanghai",
        mode: "inspect",
        retentionDays: 7,
        maxJournalMb: 500,
        serviceAllowlist: [],
      },
    },
  ];
  vi.mocked(opsOverview).mockResolvedValue(r);
  render(<AiOpsPage />);
  expect(
    (
      (await screen.findByRole("button", {
        name: "立即执行",
      })) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
});
