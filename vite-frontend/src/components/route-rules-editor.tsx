import type {
  ExitCountry,
  ProxyExit,
  RoutePreset,
  RouteRule,
  RouteRuleMatch,
  RouteRuleset,
  RouteTestResult,
  RuleCatalogItem,
} from "@/api";

import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Checkbox } from "@heroui/checkbox";
import { Chip } from "@heroui/chip";
import { Select, SelectItem, SelectSection } from "@heroui/select";
import { Input, Textarea } from "@heroui/input";
import {
  ArrowDown,
  ArrowUp,
  FileInput,
  FlaskConical,
  Plus,
  Trash2,
} from "lucide-react";
import toast from "react-hot-toast";

import { testRoute } from "@/api";
import ClashImportModal from "@/components/clash-import-modal";

/**
 * Routing rules: matched top to bottom, the first hit decides the exit; everything else uses the
 * proxy's default exit. Match types follow Clash: domains, GEOSITE, rule sets, IP ranges,
 * GEOIP, destination ports and network.
 */

/** While editing, a rule keeps the text as typed so separators are not swallowed. */
type EditableRule = RouteRule & { text?: string };

export const matchLabel: Record<RouteRuleMatch, string> = {
  preset: "内置分组",
  catalog: "精选规则集",
  geosite: "GEOSITE",
  ruleset: "规则集订阅",
  domain_suffix: "域名后缀",
  domain: "完整域名",
  domain_keyword: "域名关键词",
  domain_regex: "域名正则",
  geoip: "GEOIP",
  ip_cidr: "IP 段",
  port: "目标端口",
  network: "网络类型",
};

const matchSections: Array<{ title: string; keys: RouteRuleMatch[] }> = [
  { title: "现成清单", keys: ["catalog", "preset", "geosite", "ruleset"] },
  {
    title: "按域名",
    keys: ["domain_suffix", "domain", "domain_keyword", "domain_regex"],
  },
  { title: "按 IP", keys: ["geoip", "ip_cidr"] },
  { title: "其他", keys: ["port", "network"] },
];

const matchHint: Partial<Record<RouteRuleMatch, string>> = {
  geosite:
    "v2fly 社区清单名，例如 netflix、openai、category-games；google@cn 表示只取带 @cn 标记的部分。首次保存时由面板下载，之后每 3 天更新",
  domain_suffix:
    "例如 openai.com，会同时匹配它的所有子域名；多个用空格、逗号或换行分隔",
  domain_keyword: "域名里包含这个词就匹配，例如 netflix",
  domain: "只匹配这个域名本身，例如 api.example.com",
  domain_regex: "每行一个正则表达式，例如 ^chatgpt-.+\\.example\\.com$",
  geoip:
    "国家或地区代码，例如 cn、us、jp；private 表示局域网和保留地址。首次保存时由面板下载",
  ip_cidr: "例如 8.8.8.0/24 或 2001:db8::/32",
  port: "例如 443、8443 或范围 8000-9000",
};

const placeholder: Partial<Record<RouteRuleMatch, string>> = {
  geosite: "netflix",
  domain_regex: "^chatgpt-.+\\.example\\.com$",
  geoip: "cn",
  ip_cidr: "8.8.8.0/24",
  port: "443",
};

/** Rule types that only Agent 2.79.0 or newer can run. */
const newerMatches = new Set<RouteRuleMatch>([
  "domain_regex",
  "geoip",
  "port",
  "network",
]);
const ipMatches = new Set<RouteRuleMatch>([
  "ip_cidr",
  "geoip",
  "ruleset",
  "catalog",
]);

export function exitLabel(
  exit: string,
  countries: ExitCountry[],
  customExits: ProxyExit[],
) {
  if (exit === "direct") return "节点自身 IP";
  if (exit === "warp") return "Cloudflare WARP";
  if (exit === "block") return "拒绝连接";
  if (exit.startsWith("residential:")) {
    const code = exit.slice("residential:".length);

    return `家宽 · ${countries.find((item) => item.country === code)?.name || code}`;
  }
  if (exit.startsWith("custom:")) {
    const id = Number(exit.slice("custom:".length));
    const custom = customExits.find((item) => item.id === id);

    return `${custom?.group ? "出口组" : "自定义"} · ${custom?.name || `#${id}`}`;
  }

  return exit;
}

export default function RouteRulesEditor({
  rules,
  onChange,
  presets,
  rulesets = [],
  catalog = [],
  countries,
  customExits,
  allowResidential,
  defaultExit,
  newerRulesBlocked,
  warpBlocked,
  onRulesetsChanged,
}: {
  /** Why the node cannot use WARP yet, or empty. */
  warpBlocked?: string;
  /** The proxy's default exit as a rule exit key, to flag rules that change nothing. */
  defaultExit?: string;
  rules: RouteRule[];
  onChange: (rules: RouteRule[]) => void;
  presets: RoutePreset[];
  rulesets?: RouteRuleset[];
  catalog?: RuleCatalogItem[];
  countries: ExitCountry[];
  customExits: ProxyExit[];
  allowResidential: boolean;
  /** Why the node cannot run exit groups and the newer rule types, or empty. */
  newerRulesBlocked?: string;
  /** Called after an import added rule-set subscriptions. */
  onRulesetsChanged?: () => void;
}) {
  const [importOpen, setImportOpen] = useState(false);
  const [testTarget, setTestTarget] = useState("");
  const [testNetwork, setTestNetwork] = useState("tcp");
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<RouteTestResult | null>(null);

  // A result describes the rules as they were tested; edits make it stale.
  useEffect(() => setTestResult(null), [rules, defaultExit]);

  const runTest = async () => {
    if (!testTarget.trim()) return;
    setTesting(true);
    const response = await testRoute({
      rules: rules.map(({ match, values, exit, resolve }) => ({
        match,
        values,
        exit,
        ...(resolve ? { resolve } : {}),
      })),
      target: testTarget.trim(),
      network: testNetwork,
    });

    setTesting(false);
    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "测试失败");
    setTestResult(response.data);
  };
  const exitOptions = [
    { key: "direct", label: "节点自身 IP" },
    { key: "warp", label: "Cloudflare WARP（免费）" },
    ...(allowResidential
      ? countries
          .filter((country) => country.available > 0)
          .map((country) => ({
            key: `residential:${country.country}`,
            label: `家宽 · ${country.name}`,
          }))
      : []),
    ...customExits.map((exit) => ({
      key: `custom:${exit.id}`,
      label: `${exit.group ? "出口组" : "自定义"} · ${exit.name}`,
    })),
    { key: "block", label: "拒绝连接" },
  ];

  const update = (index: number, patch: Partial<EditableRule>) =>
    onChange(
      rules.map((rule, current) =>
        current === index ? { ...rule, ...patch } : rule,
      ),
    );
  const move = (index: number, delta: number) => {
    const next = [...rules];
    const [item] = next.splice(index, 1);

    next.splice(index + delta, 0, item);
    onChange(next);
  };
  const add = (match: RouteRuleMatch) =>
    onChange([...rules, { match, values: [], exit: exitOptions[0].key }]);
  const hasCatalog = catalog.length > 0;
  const toggle = (index: number, rule: RouteRule, value: string) =>
    update(index, {
      values: rule.values.includes(value)
        ? rule.values.filter((item) => item !== value)
        : [...rule.values, value],
    });
  const groupExit = (exit: string) =>
    exit.startsWith("custom:") &&
    customExits.some(
      (item) => `custom:${item.id}` === exit && Boolean(item.group),
    );

  const chips = (
    index: number,
    rule: RouteRule,
    items: Array<{
      key: string;
      label: string;
      count?: number;
      title?: string;
    }>,
  ) => (
    <div className="flex flex-wrap gap-1.5">
      {items.map((item) => {
        const selected = rule.values.includes(item.key);

        return (
          <Chip
            key={item.key}
            className="cursor-pointer"
            color={selected ? "primary" : "default"}
            size="sm"
            title={item.title}
            variant={selected ? "solid" : "flat"}
            onClick={() => toggle(index, rule, item.key)}
          >
            {item.label}
            {item.count ? (
              <span className="ml-1 opacity-60">{item.count}</span>
            ) : null}
          </Chip>
        );
      })}
    </div>
  );

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <div className="text-sm font-medium">分流规则</div>
          <p className="mt-0.5 text-xs text-default-500">
            从上往下匹配，命中第一条就走它的出口；没有命中任何规则的流量走下面的“其余流量”（默认是节点自身
            IP）。
          </p>
        </div>
        <div className="flex shrink-0 flex-wrap gap-2">
          <Button
            size="sm"
            startContent={<Plus size={14} />}
            variant="flat"
            onPress={() => add(hasCatalog ? "catalog" : "preset")}
          >
            按规则集
          </Button>
          <Button
            size="sm"
            startContent={<Plus size={14} />}
            variant="flat"
            onPress={() => add("domain_suffix")}
          >
            按域名
          </Button>
          <Button
            size="sm"
            startContent={<FileInput size={14} />}
            variant="flat"
            onPress={() => setImportOpen(true)}
          >
            导入 Clash 规则
          </Button>
        </div>
      </div>

      {rules.length === 0 ? (
        <div className="rounded-xl border border-dashed border-divider px-4 py-5 text-center text-xs text-default-500">
          还没有规则，所有流量都走下面“其余流量”选的出口。点“按规则集”选
          OpenAI、Netflix
          等社区维护的现成规则集，点“按域名”填写你自己的域名，或者直接导入 Clash
          配置里的规则。每条规则还可以改成 GEOSITE、GEOIP、规则集订阅、IP
          段、端口等匹配方式。
        </div>
      ) : (
        <ol className="space-y-2">
          {rules.map((rule, index) => (
            <li
              // Rules have no id; their position is their identity while editing.
              // eslint-disable-next-line react/no-array-index-key
              key={index}
              className={`space-y-2 rounded-xl bg-default-50 p-3 dark:bg-default-100/60 ${testResult?.index === index ? "ring-2 ring-primary" : ""}`}
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary">
                  {index + 1}
                </span>
                <Select
                  aria-label="匹配方式"
                  className="w-36"
                  selectedKeys={[rule.match]}
                  size="sm"
                  onSelectionChange={(keys) => {
                    const match = String(
                      Array.from(keys)[0] || "preset",
                    ) as RouteRuleMatch;

                    update(index, {
                      match,
                      values: [],
                      text: "",
                      resolve: undefined,
                    });
                  }}
                >
                  {matchSections.map((section) => (
                    <SelectSection key={section.title} title={section.title}>
                      {section.keys.map((key) => (
                        <SelectItem key={key}>{matchLabel[key]}</SelectItem>
                      ))}
                    </SelectSection>
                  ))}
                </Select>
                <span className="text-xs text-default-400">→</span>
                <Select
                  aria-label="出口"
                  className="min-w-40 flex-1"
                  selectedKeys={
                    exitOptions.some((option) => option.key === rule.exit)
                      ? [rule.exit]
                      : []
                  }
                  size="sm"
                  onSelectionChange={(keys) =>
                    update(index, {
                      exit: String(Array.from(keys)[0] || "direct"),
                    })
                  }
                >
                  {exitOptions.map((option) => (
                    <SelectItem key={option.key}>{option.label}</SelectItem>
                  ))}
                </Select>
                <div className="ml-auto flex items-center">
                  <Button
                    isIconOnly
                    aria-label="上移"
                    isDisabled={index === 0}
                    size="sm"
                    variant="light"
                    onPress={() => move(index, -1)}
                  >
                    <ArrowUp size={14} />
                  </Button>
                  <Button
                    isIconOnly
                    aria-label="下移"
                    isDisabled={index === rules.length - 1}
                    size="sm"
                    variant="light"
                    onPress={() => move(index, 1)}
                  >
                    <ArrowDown size={14} />
                  </Button>
                  <Button
                    isIconOnly
                    aria-label="删除规则"
                    color="danger"
                    size="sm"
                    variant="light"
                    onPress={() =>
                      onChange(rules.filter((_, current) => current !== index))
                    }
                  >
                    <Trash2 size={14} />
                  </Button>
                </div>
              </div>
              {rule.match === "preset" ? (
                <div className="space-y-1.5">
                  {chips(
                    index,
                    rule,
                    presets.map((preset) => ({
                      key: preset.key,
                      label: preset.name,
                      count: preset.count,
                      title:
                        preset.domains.join("、") +
                        (preset.count && preset.count > preset.domains.length
                          ? ` 等 ${preset.count} 条`
                          : ""),
                    })),
                  )}
                  {presets.some((preset) => preset.autoUpdate) && (
                    <p className="text-xs text-default-400">
                      分组清单每 3
                      天自动更新，数字是分组里的域名条数；没有的网站可以再加一条“按域名”规则。
                    </p>
                  )}
                </div>
              ) : rule.match === "catalog" ? (
                <div className="space-y-2">
                  {Array.from(
                    new Set(catalog.map((item) => item.category)),
                  ).map((category) => (
                    <div
                      key={category}
                      className="flex flex-wrap items-center gap-1.5"
                    >
                      <span className="w-16 shrink-0 text-xs text-default-400">
                        {category}
                      </span>
                      {chips(
                        index,
                        rule,
                        catalog
                          .filter((item) => item.category === category)
                          .map((item) => ({
                            key: item.key,
                            label: item.name,
                            count: item.count ?? undefined,
                            title: `${item.source} · ${item.url}`,
                          })),
                      )}
                    </div>
                  ))}
                  <p className="text-xs text-default-400">
                    来自 blackmatrix7、Loyalsoldier
                    等社区维护的规则集，所有用户共用一份，面板每 12
                    小时检查更新；第一次保存时下载。
                  </p>
                </div>
              ) : rule.match === "ruleset" ? (
                rulesets.length === 0 ? (
                  <p className="text-xs text-default-500">
                    还没有规则集订阅。到“出口管理 → 规则库”添加 Clash
                    规则集地址，或者导入带 rule-providers 的 Clash 配置。
                  </p>
                ) : (
                  chips(
                    index,
                    rule,
                    rulesets.map((ruleset) => ({
                      key: String(ruleset.id),
                      label: ruleset.name,
                      count: ruleset.count,
                      title: ruleset.url,
                    })),
                  )
                )
              ) : rule.match === "network" ? (
                chips(index, rule, [
                  { key: "tcp", label: "TCP" },
                  { key: "udp", label: "UDP（含 QUIC）" },
                ])
              ) : (
                <Textarea
                  description={matchHint[rule.match]}
                  maxRows={6}
                  minRows={1}
                  placeholder={placeholder[rule.match] || "example.com"}
                  size="sm"
                  value={(rule as EditableRule).text ?? rule.values.join("\n")}
                  onValueChange={(text) =>
                    update(index, {
                      text,
                      values: text
                        .split(
                          rule.match === "domain_regex" ? /\r?\n/ : /[\s,，]+/,
                        )
                        .map((value) => value.trim())
                        .filter(Boolean),
                    })
                  }
                />
              )}
              {ipMatches.has(rule.match) && (
                <div className="space-y-1">
                  <Checkbox
                    isSelected={Boolean(rule.resolve)}
                    size="sm"
                    onValueChange={(selected) =>
                      update(index, { resolve: selected || undefined })
                    }
                  >
                    <span className="text-xs text-default-500">
                      先解析域名再按 IP 匹配
                    </span>
                  </Checkbox>
                  <p className="text-xs text-default-400">
                    {rule.resolve
                      ? "开启后，从这条规则开始，之后的规则和“其余流量”收到的都是节点解析出的 IP 而不是域名，可能影响出口那边的 CDN 就近和部分流媒体解锁。"
                      : "默认只匹配直接用 IP 连接的流量（相当于 Clash 的 no-resolve），访问域名的流量会把域名原样交给出口。"}
                  </p>
                </div>
              )}
              {newerRulesBlocked &&
                (newerMatches.has(rule.match) || groupExit(rule.exit)) && (
                  <p className="text-xs text-warning-600 dark:text-warning-400">
                    {newerRulesBlocked}
                  </p>
                )}
              {warpBlocked && rule.exit === "warp" && (
                <p className="text-xs text-warning-600 dark:text-warning-400">
                  {warpBlocked}
                </p>
              )}
              {rule.values.length === 0 && (
                <p className="text-xs text-warning-600 dark:text-warning-400">
                  这条规则还没有选择或填写内容，不会匹配任何流量。
                </p>
              )}
              {defaultExit && rule.exit === defaultExit && (
                <p className="text-xs text-warning-600 dark:text-warning-400">
                  {defaultExit === "direct"
                    ? "没有命中规则的流量本来就走节点自身 IP，这条规则不起作用，可以删掉。"
                    : "这条规则的出口和下面“其余流量”的出口相同，不起作用。想只让这些网站走这个出口，请把“其余流量”改回“节点自身 IP”。"}
                </p>
              )}
            </li>
          ))}
        </ol>
      )}

      <div className="space-y-2 rounded-xl border border-divider p-3">
        <div className="flex flex-wrap items-center gap-2">
          <FlaskConical className="shrink-0 text-default-500" size={16} />
          <span className="text-sm font-medium">分流测试</span>
          <Input
            aria-label="要测试的域名或 IP"
            className="min-w-48 flex-1"
            placeholder="输入域名或 IP，例如 chatgpt.com、1.1.1.1:53"
            size="sm"
            value={testTarget}
            onKeyDown={(event) => {
              if (event.key === "Enter") void runTest();
            }}
            onValueChange={setTestTarget}
          />
          <Select
            aria-label="网络类型"
            className="w-24"
            selectedKeys={[testNetwork]}
            size="sm"
            onSelectionChange={(keys) =>
              setTestNetwork(String(Array.from(keys)[0] || "tcp"))
            }
          >
            <SelectItem key="tcp">TCP</SelectItem>
            <SelectItem key="udp">UDP</SelectItem>
          </Select>
          <Button
            isDisabled={!testTarget.trim()}
            isLoading={testing}
            size="sm"
            variant="flat"
            onPress={runTest}
          >
            测试
          </Button>
        </div>
        {testResult ? (
          <div className="space-y-1 text-sm">
            <div>
              <span className="font-mono text-xs text-default-500">
                {testResult.host}:{testResult.port} ·{" "}
                {testResult.network.toUpperCase()}
              </span>
            </div>
            {testResult.index >= 0 ? (
              <div>
                命中第{" "}
                <span className="font-semibold">{testResult.index + 1}</span>{" "}
                条规则（{testResult.field}{" "}
                <span className="font-mono">{testResult.value}</span>）→{" "}
                <span className="font-semibold text-primary">
                  {exitLabel(
                    rules[testResult.index]?.exit || "direct",
                    countries,
                    customExits,
                  )}
                </span>
              </div>
            ) : testResult.index === -1 ? (
              <div>
                没有命中任何规则 → 其余流量{" "}
                <span className="font-semibold text-primary">
                  {exitLabel(defaultExit || "direct", countries, customExits)}
                </span>
              </div>
            ) : (
              <div className="text-danger">
                域名解析失败，节点会直接断开这个连接
              </div>
            )}
            {testResult.resolved && (
              <div className="text-xs text-default-500">
                按 IP 匹配时用的地址（由面板解析，节点上的结果可能不同）：
                {testResult.resolved.join("、")}
              </div>
            )}
            {testResult.notes.map((note) => (
              <div key={note} className="text-xs text-default-500">
                {note}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-xs text-default-400">
            按当前编辑中的规则计算（不用先保存），命中的规则会高亮。
          </p>
        )}
      </div>

      <ClashImportModal
        exitOptions={exitOptions}
        isOpen={importOpen}
        catalog={catalog}
        rulesets={rulesets}
        onClose={() => setImportOpen(false)}
        onImport={(imported, replace) => {
          onChange(replace ? imported : [...rules, ...imported]);
          setImportOpen(false);
        }}
        onRulesetsChanged={onRulesetsChanged}
      />
    </div>
  );
}
