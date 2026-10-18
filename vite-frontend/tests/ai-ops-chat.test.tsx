import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import AiOpsChat from "@/components/ai-ops-chat";
import { opsChatList, opsChatDetail, opsChatStop } from "@/api/ai-ops";
import { streamOpsChat } from "@/api/ai-chat-stream";
vi.mock("@/api/ai-ops", () => ({
  opsChatList: vi.fn(),
  opsChatDetail: vi.fn(),
  opsChatDelete: vi.fn(),
  opsChatStop: vi.fn(),
}));
vi.mock("@/api/ai-chat-stream", () => ({ streamOpsChat: vi.fn() }));
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
  Input: ({ label, value, onValueChange, type, isDisabled }: any) => (
    <label>
      {label}
      <input
        disabled={isDisabled}
        type={type}
        value={value}
        onChange={(e) => onValueChange(e.target.value)}
      />
    </label>
  ),
}));
vi.mock("@heroui/switch", () => ({
  Switch: ({ children, isSelected, isDisabled, onValueChange }: any) => (
    <label>
      {children}
      <input
        type="checkbox"
        checked={isSelected}
        disabled={isDisabled}
        onChange={(e) => onValueChange(e.target.checked)}
      />
    </label>
  ),
}));
const overview = (configured = true): any => ({
  settings: { baseUrl: "", model: "test", keyConfigured: configured },
  nodes: [{ id: 1, name: "节点A" }],
  tasks: [],
});
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(opsChatList).mockResolvedValue({ code: 0, msg: "", data: [] });
  vi.mocked(opsChatDetail).mockResolvedValue({
    code: 0,
    msg: "",
    data: { id: 1, turns: [] },
  });
  vi.mocked(opsChatStop).mockResolvedValue({ code: 0, msg: "", data: null });
  Element.prototype.scrollIntoView = vi.fn();
});
it("offers model configuration and prevents sending when unconfigured", () => {
  const settings = vi.fn();
  render(<AiOpsChat overview={overview(false)} onSettings={settings} />);
  expect(
    (screen.getByLabelText("聊天消息") as HTMLTextAreaElement).disabled,
  ).toBe(true);
  fireEvent.click(screen.getByRole("button", { name: "先配置模型" }));
  expect(settings).toHaveBeenCalledOnce();
  expect(streamOpsChat).not.toHaveBeenCalled();
});
it("renders text and tool results before the request finishes and lets the user stop", async () => {
  let finish!: () => void;
  vi.mocked(streamOpsChat).mockImplementation(async (_body, _signal, emit) => {
    emit({ type: "meta", chatId: 1 });
    emit({ type: "delta", text: "已检查" });
    emit({
      type: "tool",
      call: { function: { name: "inspect_disk" } },
      result: { success: true, data: { usage: "20%" } },
    });
    await new Promise<void>((resolve) => {
      finish = resolve;
    });
  });
  render(<AiOpsChat overview={overview()} onSettings={() => {}} />);
  fireEvent.click(screen.getByLabelText("节点A"));
  fireEvent.change(screen.getByLabelText("聊天消息"), {
    target: { value: "检查磁盘" },
  });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
  expect(await screen.findByText("已检查")).toBeTruthy();
  expect(screen.getByText(/inspect_disk/)).toBeTruthy();
  expect(vi.mocked(streamOpsChat).mock.calls[0][0]).toMatchObject({
    message: "检查磁盘",
    config: { nodeIds: [1], mode: "inspect", allowLogCleanup: false },
  });
  fireEvent.click(screen.getByRole("button", { name: "停止回复" }));
  expect(opsChatStop).toHaveBeenCalled();
  expect(vi.mocked(streamOpsChat).mock.calls[0][1].aborted).toBe(true);
  await act(async () => finish());
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "发送" })).toBeTruthy(),
  );
});
it("loads a previous conversation and continues with its ID", async () => {
  vi.mocked(opsChatList).mockResolvedValue({
    code: 0,
    msg: "",
    data: [{ id: 9, title: "之前的对话", updatedAt: 0 }],
  });
  vi.mocked(opsChatDetail).mockResolvedValue({
    code: 0,
    msg: "",
    data: {
      id: 9,
      turns: [
        {
          id: 1,
          userContent: "你好",
          status: "success",
          events: JSON.stringify([
            { type: "assistant", message: { content: "你好，我是助手" } },
          ]),
          createdAt: 0,
        },
      ],
    },
  });
  vi.mocked(streamOpsChat).mockResolvedValue();
  render(<AiOpsChat overview={overview()} onSettings={() => {}} />);
  await screen.findByText("之前的对话");
  fireEvent.change(screen.getByLabelText("历史对话"), {
    target: { value: "9" },
  });
  await screen.findByText("你好，我是助手");
  fireEvent.change(screen.getByLabelText("聊天消息"), {
    target: { value: "继续" },
  });
  fireEvent.keyDown(screen.getByLabelText("聊天消息"), { key: "Enter" });
  await waitFor(() => expect(streamOpsChat).toHaveBeenCalled());
  expect(vi.mocked(streamOpsChat).mock.calls[0][0]).toMatchObject({
    chatId: 9,
    message: "继续",
    config: { nodeIds: [], mode: "inspect" },
  });
});
it("authorizes only selected nodes and immediately continues upgrades without granting log cleanup", async () => {
  vi.mocked(streamOpsChat).mockResolvedValue();
  render(<AiOpsChat overview={overview()} onSettings={() => {}} />);
  expect(screen.queryByRole("button", { name: "授权升级并继续" })).toBeNull();
  fireEvent.click(screen.getByLabelText("节点A"));
  fireEvent.click(screen.getByRole("button", { name: "授权升级并继续" }));
  await waitFor(() => expect(streamOpsChat).toHaveBeenCalledOnce());
  expect(vi.mocked(streamOpsChat).mock.calls[0][0]).toMatchObject({
    config: { nodeIds: [1], mode: "repair", allowAgentUpgrade: true, upgradeOnly: true, allowLogCleanup: false, serviceAllowlist: [] },
  });
  expect(vi.mocked(streamOpsChat).mock.calls[0][0].message).toContain("已有任务运行时先跟踪");
});
it("opens terminal management explicitly and includes it in the actual request", async () => {
  vi.mocked(streamOpsChat).mockResolvedValue();
  render(<AiOpsChat overview={overview()} onSettings={() => {}} />);
  fireEvent.click(screen.getByLabelText("节点A"));
  fireEvent.click(screen.getByLabelText("完整服务器管理（终端命令、安装服务）"));
  fireEvent.change(screen.getByLabelText("聊天消息"), { target: { value: "安装 nginx 并验证" } });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
  await waitFor(() => expect(streamOpsChat).toHaveBeenCalledOnce());
  expect(vi.mocked(streamOpsChat).mock.calls[0][0]).toMatchObject({ config: {
    nodeIds: [1], mode: "repair", allowTerminal: true, upgradeOnly: false, allowAgentUpgrade: true,
  } });
});
