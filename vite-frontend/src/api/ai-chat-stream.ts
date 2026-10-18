import { getApiBaseUrl } from "./network";
import type { OpsConfig, OpsChatEvent } from "./ai-ops";

export function createChatEventDecoder(onEvent: (event: OpsChatEvent) => void) {
  const decoder = new TextDecoder();
  let buffer = "";
  const parse = () => {
    let boundary: number;
    while ((boundary = buffer.indexOf("\n\n")) >= 0) {
      const frame = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const data = frame
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart())
        .join("\n");
      if (data) onEvent(JSON.parse(data));
    }
  };
  return {
    push(bytes: Uint8Array) {
      buffer += decoder.decode(bytes, { stream: true });
      buffer = buffer.replace(/\r\n/g, "\n");
      parse();
    },
    finish() {
      buffer += decoder.decode();
      buffer = buffer.replace(/\r\n/g, "\n");
      parse();
      if (buffer.trim()) throw new Error("对话连接意外中断");
    },
  };
}
export async function streamOpsChat(
  body: { chatId?: number; message: string; config: OpsConfig },
  signal: AbortSignal,
  onEvent: (event: OpsChatEvent) => void,
) {
  const base = getApiBaseUrl();
  if (!base) throw new Error("请先设置面板地址");
  const url = new URL("ai-ops/chat/send", new URL(base, window.location.href));
  const response = await fetch(url, {
    method: "POST",
    signal,
    headers: {
      "Content-Type": "application/json",
      Authorization: window.localStorage.getItem("token") || "",
    },
    body: JSON.stringify(body),
  });
  if (
    !response.ok ||
    !response.headers.get("content-type")?.includes("text/event-stream")
  ) {
    const error = await response.json().catch(() => null);
    throw new Error(
      error?.msg ||
        (response.status === 401 ? "登录已过期，请重新登录" : "对话请求失败"),
    );
  }
  if (!response.body) throw new Error("浏览器不支持流式回复");
  let done = false;
  const parser = createChatEventDecoder((event) => {
    if (event.type === "done") done = true;
    onEvent(event);
  });
  const reader = response.body.getReader();
  try {
    while (true) {
      const result = await reader.read();
      if (result.done) break;
      parser.push(result.value);
    }
    parser.finish();
    if (!done) throw new Error("对话连接意外中断，请查看记录后重试");
  } finally {
    reader.releaseLock();
  }
}
