import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

const values = new Map<string, string>();
const storage: Storage = {
  getItem: (key) => values.get(key) ?? null,
  setItem: (key, value) => {
    values.set(key, String(value));
  },
  removeItem: (key) => {
    values.delete(key);
  },
  clear: () => values.clear(),
  key: (index) => [...values.keys()][index] ?? null,
  get length() {
    return values.size;
  },
};
Object.defineProperty(window, "localStorage", {
  configurable: true,
  value: storage,
});
Object.defineProperty(globalThis, "localStorage", {
  configurable: true,
  value: storage,
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
