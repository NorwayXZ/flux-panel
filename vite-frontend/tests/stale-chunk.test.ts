import { describe, expect, it } from "vitest";

import { isStaleChunkError } from "../src/utils/stale-chunk";

describe("stale page chunk detection", () => {
  it("recognises missing lazily loaded chunks across browsers", () => {
    expect(
      isStaleChunkError(
        new TypeError(
          "Failed to fetch dynamically imported module: https://panel.example/assets/forward-abc.js",
        ),
      ),
    ).toBe(true);
    expect(
      isStaleChunkError(
        new TypeError("error loading dynamically imported module"),
      ),
    ).toBe(true);
    expect(
      isStaleChunkError(new TypeError("Importing a module script failed.")),
    ).toBe(true);
    expect(
      isStaleChunkError(new Error("Unable to preload CSS for /assets/a.css")),
    ).toBe(true);
  });

  it("leaves ordinary page errors to the error screen", () => {
    expect(
      isStaleChunkError(
        new TypeError("Cannot read properties of undefined (reading 'filter')"),
      ),
    ).toBe(false);
    expect(isStaleChunkError("network down")).toBe(false);
  });
});
