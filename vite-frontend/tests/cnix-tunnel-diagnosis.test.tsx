import { render, screen, cleanup } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import CnixTunnelDiagnosis from "@/components/cnix-tunnel-diagnosis";
afterEach(cleanup);
describe("CNIX shared channel diagnosis", () => {
  it("shows the mapped path and readiness without any business or fabricated total latency", () => {
    render(
      <CnixTunnelDiagnosis
        report={{
          channelReady: true,
          externalAddress: "161.248.136.186:14694",
          exitAddress: "186.244.251.77:1000",
          businessCount: 0,
          checks: [
            {
              description: "CNIX共享握手",
              targetAddress: "161.248.136.186:14694",
              state: "passed",
              message: "证书与认证保护已验证",
              elapsedMs: 18.5,
            },
          ],
        }}
      />,
    );
    expect(screen.getByText("共享通道已验证，可以添加业务")).toBeTruthy();
    expect(screen.getByText(/已关联 0 条业务/)).toBeTruthy();
    expect(screen.getByText(/186.244.251.77:1000/)).toBeTruthy();
    expect(screen.queryByText("隧道总延迟")).toBeNull();
    expect(screen.queryByText(/:22/)).toBeNull();
    expect(screen.queryByText(/google.com/)).toBeNull();
  });
  it("does not turn a pending mapping into success", () => {
    render(
      <CnixTunnelDiagnosis
        report={{
          channelReady: false,
          exitAddress: "186.244.251.77:1000",
          checks: [
            {
              description: "CNIX共享握手",
              targetAddress: "",
              state: "pending",
              message: "请配置映射",
            },
          ],
        }}
      />,
    );
    expect(screen.getByText("共享通道尚未验证通过")).toBeTruthy();
    expect(screen.getByText("待配置")).toBeTruthy();
    expect(screen.queryByText("共享通道已验证，可以添加业务")).toBeNull();
  });
});
