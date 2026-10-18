#!/usr/bin/env node
// Builds the operations assistant's panel knowledge from the in-panel guide, page help texts,
// docs and recent release notes. Run after changing any of them; `--check` fails when stale.
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const output = join(root, "springboot-backend/src/main/resources/ai/panel-knowledge.json");
const read = (path) => readFileSync(join(root, path), "utf8");

function guideEntries() {
  const source = read("vite-frontend/src/pages/guide.tsx");
  const start = source.indexOf("const entries: GuideEntry[] = [");
  if (start < 0) throw new Error("guide entries not found");
  let depth = 0;
  let index = source.indexOf("= [", start) + 2;
  const from = index;
  for (; index < source.length; index++) {
    const char = source[index];
    if (char === '"' || char === "'" || char === "`") {
      const quote = char;
      for (index++; index < source.length && source[index] !== quote; index++) if (source[index] === "\\") index++;
      continue;
    }
    if (char === "[") depth++;
    if (char === "]" && --depth === 0) break;
  }
  const literal = source.slice(from, index + 1).replace(/\bicon:\s*[A-Za-z0-9_]+,?/g, "");
  return Function(`"use strict"; return ${literal};`)();
}

function pageHelp() {
  const pages = join(root, "vite-frontend/src/pages");
  const result = [];
  for (const file of readdirSync(pages).sort()) {
    if (!file.endsWith(".tsx") || file === "guide.tsx") continue;
    const source = readFileSync(join(pages, file), "utf8");
    for (const match of source.matchAll(/<PageHelp>([\s\S]*?)<\/PageHelp>/g)) {
      const text = match[1]
        .replace(/\{\s*["'`]([^"'`]*)["'`]\s*\}/g, "$1")
        .replace(/<[^>]+>/g, " ")
        .replace(/\{[^}]*\}/g, " ")
        .replace(/\s+/g, " ")
        .trim();
      if (text) result.push({ id: `page-help:${file.replace(".tsx", "")}`, kind: "page-help", title: `页面说明：${file.replace(".tsx", "")}`, text });
    }
  }
  return result;
}

function markdownSections(path, kind, limit = Infinity) {
  const text = read(path);
  const sections = [];
  let current = null;
  for (const line of text.split("\n")) {
    const heading = /^##\s+(.+)$/.exec(line);
    if (heading) {
      if (sections.length >= limit) break;
      current = { title: heading[1].trim(), lines: [] };
      sections.push(current);
    } else if (current) current.lines.push(line);
  }
  return sections
    .map((section, i) => ({
      id: `${kind}:${relative(root, join(root, path))}#${i}`,
      kind,
      title: section.title,
      text: section.lines.join("\n").replace(/\n{3,}/g, "\n\n").trim(),
    }))
    .filter((section) => section.text);
}

export function build() {
  const guide = guideEntries().map((entry) => ({
    id: `guide:${entry.id}`,
    kind: "guide",
    title: entry.title,
    path: entry.path,
    adminOnly: !!entry.adminOnly,
    summary: entry.summary,
    keywords: entry.keywords || "",
    text: [
      entry.purpose,
      entry.prerequisites?.length ? `前提：${entry.prerequisites.join(" ")}` : "",
      entry.steps?.length ? `步骤：\n${entry.steps.map((step, i) => `${i + 1}. ${step}`).join("\n")}` : "",
      entry.result ? `结果：${entry.result}` : "",
      entry.notes?.length ? `注意：\n${entry.notes.map((note) => `- ${note}`).join("\n")}` : "",
    ]
      .filter(Boolean)
      .join("\n"),
  }));
  const docs = readdirSync(join(root, "docs"))
    .filter((file) => file.endsWith(".md"))
    .sort()
    .flatMap((file) => markdownSections(`docs/${file}`, "doc"));
  // Recent releases carry the known limitations and pending validations of current features.
  const releases = markdownSections(".github/release-notes.md", "release", 40);
  return { entries: [...guide, ...pageHelp(), ...docs, ...releases] };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const content = JSON.stringify(build(), null, 1) + "\n";
  if (process.argv.includes("--check")) {
    if (readFileSync(output, "utf8") !== content) {
      console.error("AI panel knowledge is stale: run node scripts/generate-ai-panel-knowledge.mjs");
      process.exit(1);
    }
  } else {
    writeFileSync(output, content);
    console.log(`wrote ${JSON.parse(content).entries.length} entries to ${relative(root, output)}`);
  }
}
