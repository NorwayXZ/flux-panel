/**
 * 批量选择 Context 和 Hook
 *
 * 提供批量选择功能的状态管理
 */

import {
  createContext,
  createElement,
  useContext,
  useEffect,
  useReducer,
  useCallback,
  useMemo,
  ReactNode,
} from "react";

export interface BatchSelectionState<T = any> {
  /** 已选中的项ID集合 */
  selectedIds: Set<number>;
  /** 是否全选 */
  selectAll: boolean;
  /** 所有可选项 */
  items: T[];
}

type BatchSelectionAction<T = any> =
  | { type: "SELECT"; id: number }
  | { type: "DESELECT"; id: number }
  | { type: "TOGGLE"; id: number }
  | { type: "SELECT_ALL"; items: T[] }
  | { type: "DESELECT_ALL" }
  | { type: "TOGGLE_ALL"; items: T[] }
  | { type: "SET_ITEMS"; items: T[] }
  | { type: "RESET" };

function batchSelectionReducer<T extends { id: number }>(
  state: BatchSelectionState<T>,
  action: BatchSelectionAction<T>,
): BatchSelectionState<T> {
  switch (action.type) {
    case "SELECT": {
      if (!state.items.some((item) => item.id === action.id)) return state;
      const newSelected = new Set(state.selectedIds);

      newSelected.add(action.id);

      return {
        ...state,
        selectedIds: newSelected,
        selectAll: newSelected.size === state.items.length,
      };
    }

    case "DESELECT": {
      const newSelected = new Set(state.selectedIds);

      newSelected.delete(action.id);

      return {
        ...state,
        selectedIds: newSelected,
        selectAll: false,
      };
    }

    case "TOGGLE": {
      if (!state.items.some((item) => item.id === action.id)) return state;
      const newSelected = new Set(state.selectedIds);

      if (newSelected.has(action.id)) {
        newSelected.delete(action.id);
      } else {
        newSelected.add(action.id);
      }

      return {
        ...state,
        selectedIds: newSelected,
        selectAll: newSelected.size === state.items.length,
      };
    }

    case "SELECT_ALL": {
      const allIds = new Set(action.items.map((item) => item.id));

      return {
        ...state,
        selectedIds: allIds,
        selectAll: action.items.length > 0,
        items: action.items,
      };
    }

    case "DESELECT_ALL": {
      return {
        ...state,
        selectedIds: new Set(),
        selectAll: false,
      };
    }

    case "TOGGLE_ALL": {
      if (state.selectAll) {
        return {
          ...state,
          selectedIds: new Set(),
          selectAll: false,
        };
      } else {
        const allIds = new Set(action.items.map((item) => item.id));

        return {
          ...state,
          selectedIds: allIds,
          selectAll: action.items.length > 0,
          items: action.items,
        };
      }
    }

    case "SET_ITEMS": {
      if (
        state.items === action.items ||
        (state.items.length === 0 &&
          action.items.length === 0 &&
          state.selectedIds.size === 0)
      )
        return state;
      // 过滤掉不存在的选中项
      const validIds = new Set(action.items.map((item) => item.id));
      const newSelected = new Set(
        Array.from(state.selectedIds).filter((id) => validIds.has(id)),
      );

      return {
        ...state,
        items: action.items,
        selectedIds: newSelected,
        selectAll:
          newSelected.size === action.items.length && action.items.length > 0,
      };
    }

    case "RESET": {
      return {
        selectedIds: new Set(),
        selectAll: false,
        items: state.items,
      };
    }

    default:
      return state;
  }
}

interface BatchSelectionContextValue<T = any> {
  state: BatchSelectionState<T>;
  select: (id: number) => void;
  deselect: (id: number) => void;
  toggle: (id: number) => void;
  selectAll: () => void;
  deselectAll: () => void;
  toggleAll: () => void;
  setItems: (items: T[]) => void;
  reset: () => void;
  isSelected: (id: number) => boolean;
  selectedCount: number;
  selectedItems: T[];
}

const BatchSelectionContext = createContext<BatchSelectionContextValue | null>(
  null,
);
const EMPTY_ITEMS: never[] = [];

interface BatchSelectionProviderProps<T extends { id: number }> {
  children: ReactNode;
  items?: T[];
}

/**
 * 批量选择 Provider
 *
 * @example
 * ```tsx
 * <BatchSelectionProvider items={nodes}>
 *   <NodeList />
 *   <BatchOperationBar />
 * </BatchSelectionProvider>
 * ```
 */
export function BatchSelectionProvider<T extends { id: number }>({
  children,
  items = EMPTY_ITEMS,
}: BatchSelectionProviderProps<T>) {
  const [state, dispatch] = useReducer(batchSelectionReducer<T>, {
    selectedIds: new Set<number>(),
    selectAll: false,
    items,
  });

  useEffect(() => {
    dispatch({ type: "SET_ITEMS", items });
  }, [items]);

  const select = useCallback((id: number) => {
    dispatch({ type: "SELECT", id });
  }, []);

  const deselect = useCallback((id: number) => {
    dispatch({ type: "DESELECT", id });
  }, []);

  const toggle = useCallback((id: number) => {
    dispatch({ type: "TOGGLE", id });
  }, []);

  const selectAll = useCallback(() => {
    dispatch({ type: "SELECT_ALL", items: state.items });
  }, [state.items]);

  const deselectAll = useCallback(() => {
    dispatch({ type: "DESELECT_ALL" });
  }, []);

  const toggleAll = useCallback(() => {
    dispatch({ type: "TOGGLE_ALL", items: state.items });
  }, [state.items]);

  const setItems = useCallback((newItems: T[]) => {
    dispatch({ type: "SET_ITEMS", items: newItems });
  }, []);

  const reset = useCallback(() => {
    dispatch({ type: "RESET" });
  }, []);

  const isSelected = useCallback(
    (id: number) => state.selectedIds.has(id),
    [state.selectedIds],
  );

  const selectedCount = state.selectedIds.size;

  const selectedItems = useMemo(() => {
    return state.items.filter((item) => state.selectedIds.has(item.id));
  }, [state.items, state.selectedIds]);

  const value = useMemo(
    () => ({
      state,
      select,
      deselect,
      toggle,
      selectAll,
      deselectAll,
      toggleAll,
      setItems,
      reset,
      isSelected,
      selectedCount,
      selectedItems,
    }),
    [
      state,
      select,
      deselect,
      toggle,
      selectAll,
      deselectAll,
      toggleAll,
      setItems,
      reset,
      isSelected,
      selectedCount,
      selectedItems,
    ],
  );

  return createElement(BatchSelectionContext.Provider, { value }, children);
}

/**
 * 使用批量选择 Hook
 *
 * @example
 * ```tsx
 * function NodeList() {
 *   const { toggle, isSelected, selectedCount } = useBatchSelection();
 *
 *   return (
 *     <div>
 *       <p>已选择 {selectedCount} 项</p>
 *       {nodes.map(node => (
 *         <Checkbox
 *           key={node.id}
 *           checked={isSelected(node.id)}
 *           onChange={() => toggle(node.id)}
 *         />
 *       ))}
 *     </div>
 *   );
 * }
 * ```
 */
export function useBatchSelection<T = any>(): BatchSelectionContextValue<T> {
  const context = useContext(BatchSelectionContext);

  if (!context) {
    throw new Error(
      "useBatchSelection must be used within BatchSelectionProvider",
    );
  }

  return context as BatchSelectionContextValue<T>;
}

/**
 * 简化版批量选择 Hook（不需要 Provider）
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const selection = useSimpleBatchSelection(items);
 *
 *   return (
 *     <div>
 *       <Checkbox
 *         checked={selection.selectAll}
 *         onChange={selection.toggleAll}
 *       />
 *       {items.map(item => (
 *         <Checkbox
 *           key={item.id}
 *           checked={selection.isSelected(item.id)}
 *           onChange={() => selection.toggle(item.id)}
 *         />
 *       ))}
 *     </div>
 *   );
 * }
 * ```
 */
export function useSimpleBatchSelection<T extends { id: number }>(items: T[]) {
  const [state, dispatch] = useReducer(batchSelectionReducer<T>, {
    selectedIds: new Set<number>(),
    selectAll: false,
    items,
  });

  const select = useCallback((id: number) => {
    dispatch({ type: "SELECT", id });
  }, []);

  const deselect = useCallback((id: number) => {
    dispatch({ type: "DESELECT", id });
  }, []);

  const toggle = useCallback((id: number) => {
    dispatch({ type: "TOGGLE", id });
  }, []);

  const selectAll = useCallback(() => {
    dispatch({ type: "SELECT_ALL", items });
  }, [items]);

  const deselectAll = useCallback(() => {
    dispatch({ type: "DESELECT_ALL" });
  }, []);

  const toggleAll = useCallback(() => {
    dispatch({ type: "TOGGLE_ALL", items });
  }, [items]);

  const reset = useCallback(() => {
    dispatch({ type: "RESET" });
  }, []);

  const isSelected = useCallback(
    (id: number) => state.selectedIds.has(id),
    [state.selectedIds],
  );

  const selectedCount = state.selectedIds.size;

  const selectedItems = useMemo(() => {
    return items.filter((item) => state.selectedIds.has(item.id));
  }, [items, state.selectedIds]);

  // 当 items 变化时更新
  useEffect(() => {
    dispatch({ type: "SET_ITEMS", items });
  }, [items]);

  return {
    state,
    select,
    deselect,
    toggle,
    selectAll,
    deselectAll,
    toggleAll,
    reset,
    isSelected,
    selectedCount,
    selectedItems,
  };
}
