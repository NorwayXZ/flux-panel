import { act, render, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import {
  BatchSelectionProvider,
  useSimpleBatchSelection,
  useBatchSelection,
} from "@/hooks/use-batch-selection";

describe("selection across asynchronous data updates", () => {
  it("keeps valid selections and removes deleted rows after a refresh", () => {
    const { result, rerender } = renderHook(
      ({ items }) => useSimpleBatchSelection(items),
      {
        initialProps: { items: [{ id: 1 }, { id: 2 }] },
      },
    );
    act(() => result.current.selectAll());
    rerender({ items: [{ id: 2 }, { id: 3 }] });
    expect(result.current.selectedItems).toEqual([{ id: 2 }]);
    expect(result.current.state.selectAll).toBe(false);
    act(() => result.current.toggleAll());
    expect(result.current.selectedCount).toBe(2);
    act(() => result.current.reset());
    act(() => result.current.toggle(2));
    expect(result.current.selectedItems).toEqual([{ id: 2 }]);
  });

  it("cannot select an absent row or report an empty list as fully selected", () => {
    const { result } = renderHook(() => useSimpleBatchSelection([]));
    act(() => {
      result.current.select(99);
      result.current.toggle(99);
      result.current.selectAll();
    });
    expect(result.current.selectedCount).toBe(0);
    expect(result.current.state.selectAll).toBe(false);
  });

  it("updates provider data and mounts safely without an items prop", () => {
    function Count() {
      return <span>{useBatchSelection().state.items.length}</span>;
    }
    const view = render(
      <BatchSelectionProvider>
        <Count />
      </BatchSelectionProvider>,
    );
    expect(view.container.textContent).toBe("0");
    view.rerender(
      <BatchSelectionProvider items={[{ id: 3 }]}>
        <Count />
      </BatchSelectionProvider>,
    );
    expect(view.container.textContent).toBe("1");
  });
});
