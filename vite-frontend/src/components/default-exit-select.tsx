import type { ExitCountry, ProxyExit, RouteRule } from "@/api";
import type { ExitChoice } from "@/components/exit-picker";

import { Select, SelectItem, SelectSection } from "@heroui/select";

import { exitLabel } from "@/components/route-rules-editor";
import { sortExitCountries } from "@/utils/exit-display";
import { regionName } from "@/utils/region-name";

/** The rule-exit key ("direct", "residential:CC", "custom:ID") of an exit choice. */
export const exitKeyOf = (choice: ExitChoice) =>
  choice.exitType === "warp"
    ? "warp"
    : choice.exitType === "mysterium" && choice.exitCountry
      ? `mysterium:${choice.exitCountry}`
      : choice.exitType === "custom" && choice.exitCustomId
        ? `custom:${choice.exitCustomId}`
        : choice.exitType === "residential" && choice.exitCountry
          ? `residential:${choice.exitCountry}`
          : "direct";

const choiceOf = (key: string): ExitChoice =>
  key === "warp"
    ? { exitType: "warp", exitCountry: "" }
    : key.startsWith("mysterium:")
      ? { exitType: "mysterium", exitCountry: key.slice("mysterium:".length) }
      : key.startsWith("custom:")
        ? {
            exitType: "custom",
            exitCountry: "",
            exitCustomId: Number(key.slice("custom:".length)),
          }
        : key.startsWith("residential:")
          ? {
              exitType: "residential",
              exitCountry: key.slice("residential:".length),
            }
          : { exitType: "node", exitCountry: "" };

/** One line per exit: which rules go where, then where everything else goes. */
export function RoutingSummary({
  rules,
  value,
  countries,
  customExits,
}: {
  rules: RouteRule[];
  value: ExitChoice;
  countries: ExitCountry[];
  customExits: ProxyExit[];
}) {
  const byExit = new Map<string, number[]>();

  rules.forEach((rule, index) => {
    if (rule.values.length === 0) return;
    byExit.set(rule.exit, [...(byExit.get(rule.exit) || []), index + 1]);
  });
  const fallback = exitKeyOf(value);
  const label = (key: string) =>
    key === "direct"
      ? "节点自身 IP（原始 IP）"
      : exitLabel(key, countries, customExits);

  return (
    <div className="space-y-1 rounded-xl border border-primary/30 bg-primary-50/60 px-4 py-3 text-sm dark:bg-primary-500/10">
      <div className="text-xs font-medium text-default-500">现在的效果</div>
      {Array.from(byExit.entries()).map(([exit, numbers]) => (
        <div key={exit}>
          命中规则 {numbers.join("、")} 的流量 →{" "}
          <span className="font-semibold">{label(exit)}</span>
        </div>
      ))}
      <div>
        {byExit.size > 0 ? "其余所有流量" : "所有流量"} →{" "}
        <span
          className={`font-semibold ${fallback === "direct" ? "" : "text-warning-600 dark:text-warning-400"}`}
        >
          {label(fallback)}
        </span>
      </div>
    </div>
  );
}

/** Where traffic that matches no rule goes; the node's own IP unless chosen otherwise. */
export default function DefaultExitSelect({
  value,
  onChange,
  countries,
  customExits,
  allowResidential,
  residentialBlockedReason,
  warpBlocked,
  mysteriumCountries = [],
}: {
  /** Countries offered as Mysterium residential exits. */
  mysteriumCountries?: string[];
  /** Why the node cannot use WARP yet, or empty. */
  warpBlocked?: string;
  value: ExitChoice;
  onChange: (value: ExitChoice) => void;
  countries: ExitCountry[];
  customExits: ProxyExit[];
  allowResidential: boolean;
  residentialBlockedReason?: string;
}) {
  const key = exitKeyOf(value);
  const residential = allowResidential ? sortExitCountries(countries) : [];

  return (
    <div className="space-y-2">
      <div>
        <div className="text-sm font-medium">其余流量</div>
        <p className="mt-0.5 text-xs text-default-500">
          没有命中上面任何规则的流量走这里。只想让部分网站走自己添加的节点时，保持“节点自身
          IP”，在上面的规则里选那个节点即可。
        </p>
      </div>
      <Select
        aria-label="其余流量的出口"
        disabledKeys={residential
          .filter((country) => country.available <= 0)
          .map((country) => `residential:${country.country}`)}
        selectedKeys={[key]}
        onSelectionChange={(keys) => {
          const next = String(Array.from(keys)[0] || "");

          if (next) onChange(choiceOf(next));
        }}
      >
        <SelectSection title="原始出口">
          <SelectItem key="direct" textValue="节点自身 IP（原始 IP）">
            节点自身 IP（原始 IP，推荐）
          </SelectItem>
        </SelectSection>
        <SelectSection title="全部改走免费出口">
          <SelectItem key="warp" textValue="Cloudflare WARP">
            Cloudflare WARP（免费，Cloudflare 的 IP）
          </SelectItem>
        </SelectSection>
        {customExits.length > 0 ? (
          <SelectSection title="全部改走自己添加的节点">
            {customExits.map((exit) => (
              <SelectItem
                key={`custom:${exit.id}`}
                textValue={`${exit.group ? "出口组" : "自定义"} · ${exit.name}`}
              >
                {exit.group ? "出口组" : "自定义"} · {exit.name}
              </SelectItem>
            ))}
          </SelectSection>
        ) : null}
        {allowResidential && mysteriumCountries.length > 0 ? (
          <SelectSection title="全部改走 Mysterium 家宽（按流量计费）">
            {mysteriumCountries.map((country) => (
              <SelectItem
                key={`mysterium:${country}`}
                textValue={`Mysterium 家宽 · ${regionName(country)}`}
              >
                Mysterium 家宽 · {regionName(country)}
              </SelectItem>
            ))}
          </SelectSection>
        ) : null}
        {residential.length > 0 ? (
          <SelectSection title="全部改走家宽">
            {residential.map((country) => (
              <SelectItem
                key={`residential:${country.country}`}
                textValue={`家宽 · ${country.name}`}
              >
                家宽 · {country.name}（可用 {country.available}）
              </SelectItem>
            ))}
          </SelectSection>
        ) : null}
      </Select>
      {key !== "direct" && (
        <p className="text-xs text-warning-600 dark:text-warning-400">
          注意：除了上面规则指定的流量，其余所有流量都会走“
          {exitLabel(key, countries, customExits)}”，而不是节点自身 IP。
        </p>
      )}
      {key === "warp" && warpBlocked && (
        <p className="text-xs text-warning-600 dark:text-warning-400">
          {warpBlocked}
        </p>
      )}
      {!allowResidential && residentialBlockedReason && (
        <p className="text-xs text-default-400">{residentialBlockedReason}</p>
      )}
    </div>
  );
}
