import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
} from "@testing-library/react";
import { MemoryRouter, Routes, Route, useLocation } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  NetworkDetectionWorkspace,
  ForwardWorkspace,
  LegacyModuleRedirect,
} from "../src/components/module-workspace";
vi.mock("@/pages/network-tools", () => ({ default: () => <p>诊断内容</p> }));
vi.mock("@/pages/bandwidth-test", () => ({ default: () => <p>带宽内容</p> }));
vi.mock("@/pages/client-speed-test", () => ({
  default: () => <p>本机内容</p>,
}));
vi.mock("@/pages/udp-quic-diagnostic", () => ({
  default: () => <p>UDP 内容</p>,
}));
vi.mock("@/pages/ip-quality", () => ({ default: () => <p>IP 内容</p> }));
vi.mock("@/pages/forward", () => ({ default: () => <p>转发列表内容</p> }));
vi.mock("@/pages/multi-line-aggregation", () => ({
  default: () => <p>动态线路内容</p>,
}));
vi.mock("@/utils/auth", () => ({ isAdmin: () => true }));
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
afterEach(cleanup);
function Location() {
  const l = useLocation();
  return <p>{l.pathname + l.search + l.hash}</p>;
}
describe("合并后的功能入口", () => {
  it("保留旧地址参数并跳转到检测类型", async () => {
    render(
      <MemoryRouter initialEntries={["/old?node=7&h5=true#result"]}>
        <Routes>
          <Route
            path="/old"
            element={
              <LegacyModuleRedirect to="/network-detection/ip-quality" />
            }
          />
          <Route path="*" element={<Location />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(
      await screen.findByText(
        "/network-detection/ip-quality?node=7&h5=true#result",
      ),
    ).toBeTruthy();
  });
  it("切换检测类型时显示对应工具", async () => {
    render(
      <MemoryRouter initialEntries={["/network-detection/diagnostics"]}>
        <Routes>
          <Route
            path="/network-detection/*"
            element={<NetworkDetectionWorkspace />}
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("诊断内容")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "服务器间带宽" }));
    expect(await screen.findByText("带宽内容")).toBeTruthy();
    await waitFor(() => expect(screen.queryByText("诊断内容")).toBeNull());
  });
  it("转发列表和动态线路策略在同一入口且可切回", async () => {
    render(
      <MemoryRouter initialEntries={["/forward?tab=line-strategy"]}>
        <ForwardWorkspace />
      </MemoryRouter>,
    );
    expect(await screen.findByText("动态线路内容")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "转发列表与策略配置" }));
    expect(await screen.findByText("转发列表内容")).toBeTruthy();
  });
});
