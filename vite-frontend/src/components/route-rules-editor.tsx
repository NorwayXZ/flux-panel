import type {
  ExitCountry,
  ProxyExit,
  RoutePreset,
  RouteRule,
  RouteRuleMatch,
} from "@/api";

import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Select, SelectItem } from "@heroui/select";
import { Textarea } from "@heroui/input";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";

/**
 * Per-domain routing rules: matched top to bottom, the first hit decides the exit; everything
 * else uses the proxy's default exit.
 */

/** While editing, a rule keeps the text as typed so separators are not swallowed. */
type EditableRule = RouteRule & { text?: string };

export const matchLabel: Record<RouteRuleMatch, string> = {
  preset: "内置分组",
  domain_suffix: "域名后缀",
  domain_keyword: "域名关键词",
  domain: "完整域名",
  ip_cidr: "IP 段",
};

const matchHint: Record<RouteRuleMatch, string> = {
  preset: "",
  domain_suffix:
    "例如 openai.com，会同时匹配它的所有子域名；多个用空格、逗号或换行分隔",
  domain_keyword: "域名里包含这个词就匹配，例如 netflix",
  domain: "只匹配这个域名本身，例如 api.example.com",
  ip_cidr: "例如 8.8.8.0/24 或 2001:db8::/32",
};

export function exitLabel(
  exit: string,
  countries: ExitCountry[],
  customExits: ProxyExit[],
) {
  if (exit === "direct") return "节点自身 IP";
  if (exit === "block") return "拒绝连接";
  if (exit.startsWith("residential:")) {
    const code = exit.slice("residential:".length);

    return `家宽 · ${countries.find((item) => item.country === code)?.name || code}`;
  }
  if (exit.startsWith("custom:")) {
    const id = Number(exit.slice("custom:".length));

    return `自定义 · ${customExits.find((item) => item.id === id)?.name || `#${id}`}`;
  }

  return exit;
}

export default function RouteRulesEditor({
  rules,
  onChange,
  presets,
  countries,
  customExits,
  allowResidential,
}: {
  rules: RouteRule[];
  onChange: (rules: RouteRule[]) => void;
  presets: RoutePreset[];
  countries: ExitCountry[];
  customExits: ProxyExit[];
  allowResidential: boolean;
}) {
  const exitOptions = [
    { key: "direct", label: "节点自身 IP" },
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
      label: `自定义 · ${exit.name}`,
    })),
    { key: "block", label: "拒绝连接" },
  ];

  const update = (index: number, patch: Partial<RouteRule>) =>
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

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <div>
          <div className="text-sm font-medium">分流规则</div>
          <p className="mt-0.5 text-xs text-default-500">
            从上往下匹配，命中第一条就走它的出口；没有命中的走上面的默认出口。
          </p>
        </div>
        <Button
          size="sm"
          startContent={<Plus size={14} />}
          variant="flat"
          onPress={() =>
            onChange([
              ...rules,
              { match: "preset", values: [], exit: exitOptions[0].key },
            ])
          }
        >
          添加规则
        </Button>
      </div>

      {rules.length === 0 ? (
        <div className="rounded-xl border border-dashed border-divider px-4 py-5 text-center text-xs text-default-500">
          还没有规则，所有流量都走默认出口。例如加一条“内置分组 ChatGPT / OpenAI
          → 家宽 美国”，只有这些网站走家宽。
        </div>
      ) : (
        <ol className="space-y-2">
          {rules.map((rule, index) => (
            <li
              // Rules have no id; their position is their identity while editing.
              // eslint-disable-next-line react/no-array-index-key
              key={index}
              className="space-y-2 rounded-xl bg-default-50 p-3 dark:bg-default-100/60"
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
                    } as Partial<EditableRule>);
                  }}
                >
                  {(Object.keys(matchLabel) as RouteRuleMatch[]).map((key) => (
                    <SelectItem key={key}>{matchLabel[key]}</SelectItem>
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
                <div className="flex flex-wrap gap-1.5">
                  {presets.map((preset) => {
                    const selected = rule.values.includes(preset.key);

                    return (
                      <Chip
                        key={preset.key}
                        className="cursor-pointer"
                        color={selected ? "primary" : "default"}
                        size="sm"
                        title={preset.domains.join("、")}
                        variant={selected ? "solid" : "flat"}
                        onClick={() =>
                          update(index, {
                            values: selected
                              ? rule.values.filter(
                                  (value) => value !== preset.key,
                                )
                              : [...rule.values, preset.key],
                          })
                        }
                      >
                        {preset.name}
                      </Chip>
                    );
                  })}
                </div>
              ) : (
                <Textarea
                  description={matchHint[rule.match]}
                  maxRows={6}
                  minRows={1}
                  placeholder={
                    rule.match === "ip_cidr" ? "8.8.8.0/24" : "example.com"
                  }
                  size="sm"
                  value={(rule as EditableRule).text ?? rule.values.join("\n")}
                  onValueChange={(text) =>
                    update(index, {
                      text,
                      values: text.split(/[\s,，]+/).filter(Boolean),
                    } as Partial<EditableRule>)
                  }
                />
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
