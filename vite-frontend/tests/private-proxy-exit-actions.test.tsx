import { fireEvent, render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import PrivateProxyExitActions from "@/components/private-proxy-exit-actions";
import type { PrivateProxyItem } from "@/api";

function renderActions(exitType: string, standbyReady = 0, isAdmin = true, independent = false) {
  const onSwitch = vi.fn(),
    onLink = vi.fn(),
    onEvents = vi.fn();
  render(
    <PrivateProxyExitActions
      item={
        {
          state: "active",
          exitType,
          mysteriumIndependent: independent,
          exit: { standbyReady },
        } as PrivateProxyItem
      }
      isAdmin={isAdmin}
      switching={false}
      onSwitch={onSwitch}
      onLink={onLink}
      onEvents={onEvents}
    />,
  );
  return { onSwitch, onLink, onEvents };
}
describe("private proxy exit actions", () => {
  it("explains that an independent exit switch affects only this proxy", () => {
    renderActions("mysterium", 0, true, true);
    expect(screen.getByText("独立出口，切换只影响此代理。")).toBeTruthy();
    expect(screen.queryByText("同一节点、同一国家共用出口，切换会影响其他使用它的代理。")).toBeNull();
  });
  it("allows admins to switch Mysterium without a VPN Gate standby group and access its link and history", () => {
    const actions = renderActions("mysterium");
    fireEvent.click(screen.getByRole("button", { name: "换一个出口" }));
    fireEvent.click(screen.getByRole("button", { name: "切换链接" }));
    fireEvent.click(screen.getByRole("button", { name: "切换记录" }));
    expect(actions.onSwitch).toHaveBeenCalledOnce();
    expect(actions.onLink).toHaveBeenCalledOnce();
    expect(actions.onEvents).toHaveBeenCalledOnce();
  });
  it("keeps VPN Gate switching disabled until a standby is ready while leaving the link available to admins", () => {
    const actions = renderActions("residential");
    fireEvent.click(screen.getByRole("button", { name: "换一个出口" }));
    expect(actions.onSwitch).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "切换链接" })).toBeTruthy();
  });
  it("does not give ordinary users the administrator event log", () => {
    renderActions("mysterium", 0, false);
    expect(screen.getByRole("button", { name: "切换链接" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "切换记录" })).toBeNull();
  });
});
