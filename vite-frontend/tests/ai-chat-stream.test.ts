import { expect, it, vi, afterEach } from "vitest";
import { createChatEventDecoder, streamOpsChat } from "@/api/ai-chat-stream";
vi.mock("@/api/network", () => ({ getApiBaseUrl: () => "/api/v1/" }));
afterEach(() => vi.unstubAllGlobals());
it("decodes UTF8 and CRLF frames split at every byte", () => {
  const received: any[] = [];
  const parser = createChatEventDecoder((e) => received.push(e));
  const bytes = new TextEncoder().encode(
    'data:{"type":"delta","text":"你好🙂"}\r\n\r\ndata:{"type":"done","status":"success"}\r\n\r\n',
  );
  for (const byte of bytes) parser.push(new Uint8Array([byte]));
  parser.finish();
  expect(received).toEqual([
    { type: "delta", text: "你好🙂" },
    { type: "done", status: "success" },
  ]);
});
it("reports an interrupted event instead of silently completing", () => {
  const parser = createChatEventDecoder(() => {});
  parser.push(new TextEncoder().encode('data:{"type":"delta"'));
  expect(() => parser.finish()).toThrow(/中断/);
});
it("shows backend model-configuration errors returned before SSE starts", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({
      ok: false,
      status: 400,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => ({ code: -1, msg: "AI 模型未配置" }),
    })),
  );
  await expect(
    streamOpsChat(
      { message: "hi", config: {} as any },
      new AbortController().signal,
      () => {},
    ),
  ).rejects.toThrow("AI 模型未配置");
});
