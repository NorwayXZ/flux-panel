import { describe, expect, it } from "vitest";

import { lineDiff } from "@/components/ai-ops-memory";
import { usageOf, usageText } from "@/components/ai-ops-chat";

describe("AI operations helpers", () => {
  it("diffs configuration files line by line", () => {
    const diff = lineDiff("a\nlisten 80;\nc", "a\nlisten 443 ssl;\nc\nd");

    expect(diff.map((line) => `${line.kind}${line.text}`)).toEqual([
      " a",
      "-listen 80;",
      "+listen 443 ssl;",
      " c",
      "+d",
    ]);
  });

  it("sums token usage and estimates cost per million tokens", () => {
    const usage = usageOf([
      { type: "assistant", usage: { prompt_tokens: 1000, completion_tokens: 200 } } as any,
      { type: "tool" } as any,
      { type: "assistant", usage: { prompt_tokens: 3000, completion_tokens: 300 } } as any,
    ]);

    expect(usage).toEqual({ input: 4000, output: 500 });
    expect(usageText(usage)).toBe("4,500 tokens（输入 4,000 / 输出 500）");
    expect(usageText(usage, { inputPrice: "2", outputPrice: "8", currency: "CNY" })).toBe(
      "4,500 tokens（输入 4,000 / 输出 500） · 约 0.0120 CNY",
    );
    expect(usageText({ input: 0, output: 0 })).toBe("");
  });
});
