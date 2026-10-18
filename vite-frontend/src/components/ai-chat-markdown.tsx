import { memo, useState, type ReactNode } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { Check, Copy } from "lucide-react";

export async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);

    return true;
  } catch {
    return false;
  }
}

function CodeBlock({
  language,
  children,
}: {
  language: string;
  children: string;
}) {
  const [copied, setCopied] = useState(false);

  return (
    <div className="ai-code my-4 overflow-hidden rounded-xl border border-default-200 bg-default-50 dark:bg-black/40">
      <div className="flex items-center justify-between border-b border-default-200 px-3 py-1.5 text-xs text-default-500">
        <span className="font-mono">{language || "text"}</span>
        <button
          type="button"
          className="flex items-center gap-1 rounded-md px-1.5 py-0.5 hover:bg-default-200/60 hover:text-foreground"
          onClick={() =>
            void copyText(children).then((ok) => {
              if (!ok) return;
              setCopied(true);
              window.setTimeout(() => setCopied(false), 1500);
            })
          }
        >
          {copied ? <Check size={13} /> : <Copy size={13} />}
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      <pre className="overflow-x-auto px-4 py-3 text-[13px] leading-6">
        <code className="font-mono">{children}</code>
      </pre>
    </div>
  );
}

const text = (node: ReactNode): string =>
  typeof node === "string" || typeof node === "number"
    ? String(node)
    : Array.isArray(node)
      ? node.map(text).join("")
      : "";

const components: Components = {
  p: ({ children }) => <p className="my-3 first:mt-0 last:mb-0">{children}</p>,
  h1: ({ children }) => (
    <h1 className="mb-3 mt-6 text-[1.35em] font-semibold leading-snug first:mt-0">
      {children}
    </h1>
  ),
  h2: ({ children }) => (
    <h2 className="mb-2.5 mt-6 text-[1.2em] font-semibold leading-snug first:mt-0">
      {children}
    </h2>
  ),
  h3: ({ children }) => (
    <h3 className="mb-2 mt-5 text-[1.05em] font-semibold first:mt-0">
      {children}
    </h3>
  ),
  h4: ({ children }) => (
    <h4 className="mb-2 mt-4 font-semibold first:mt-0">{children}</h4>
  ),
  ul: ({ children }) => (
    <ul className="my-3 list-disc space-y-1.5 pl-6 marker:text-default-400">
      {children}
    </ul>
  ),
  ol: ({ children }) => (
    <ol className="my-3 list-decimal space-y-1.5 pl-6 marker:text-default-500">
      {children}
    </ol>
  ),
  li: ({ children }) => <li className="pl-1">{children}</li>,
  strong: ({ children }) => (
    <strong className="font-semibold text-foreground">{children}</strong>
  ),
  a: ({ href, children }) => (
    <a
      className="text-primary underline decoration-primary/40 underline-offset-2 hover:decoration-primary"
      href={href}
      rel="noopener noreferrer"
      target="_blank"
    >
      {children}
    </a>
  ),
  blockquote: ({ children }) => (
    <blockquote className="my-3 border-l-[3px] border-default-300 pl-4 text-default-600">
      {children}
    </blockquote>
  ),
  hr: () => <hr className="my-6 border-default-200" />,
  table: ({ children }) => (
    <div className="my-4 overflow-x-auto rounded-xl border border-default-200">
      <table className="w-full border-collapse text-[0.9em]">{children}</table>
    </div>
  ),
  th: ({ children }) => (
    <th className="border-b border-default-200 bg-default-100/70 px-3 py-2 text-left font-semibold">
      {children}
    </th>
  ),
  td: ({ children }) => (
    <td className="border-b border-default-100 px-3 py-2 align-top">
      {children}
    </td>
  ),
  pre: ({ children }) => <>{children}</>,
  code: ({ className, children }) => {
    const language = /language-([\w-]+)/.exec(className || "")?.[1];
    const content = text(children);

    if (language || content.includes("\n"))
      return (
        <CodeBlock language={language || ""}>
          {content.replace(/\n$/, "")}
        </CodeBlock>
      );

    return (
      <code className="rounded-md bg-default-100 px-1.5 py-0.5 font-mono text-[0.86em] text-foreground">
        {children}
      </code>
    );
  },
};

/** Assistant reply typography: a reading column close to Claude's. */
export const AssistantMarkdown = memo(function AssistantMarkdown({
  content,
}: {
  content: string;
}) {
  return (
    <div className="ai-prose">
      <ReactMarkdown components={components} remarkPlugins={[remarkGfm]}>
        {content}
      </ReactMarkdown>
    </div>
  );
});
