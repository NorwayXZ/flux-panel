import type {
  ClashImportResult,
  RouteRule,
  RouteRuleset,
  RuleCatalogItem,
} from "@/api";

import { useEffect, useMemo, useState } from "react";
import { Button } from "@heroui/button";
import { Checkbox } from "@heroui/checkbox";
import { Textarea } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Radio, RadioGroup } from "@heroui/radio";
import { Select, SelectItem } from "@heroui/select";
import toast from "react-hot-toast";

import { createRouteRuleset, parseClashRules } from "@/api";

const SKIP = "skip";

/**
 * Imports the rules of a Clash configuration: each policy (proxy group) is mapped to an exit,
 * and rule providers become rule-set subscriptions.
 */
export default function ClashImportModal({
  isOpen,
  onClose,
  exitOptions,
  rulesets,
  catalog = [],
  onImport,
  onRulesetsChanged,
}: {
  isOpen: boolean;
  onClose: () => void;
  exitOptions: Array<{ key: string; label: string }>;
  rulesets: RouteRuleset[];
  catalog?: RuleCatalogItem[];
  onImport: (rules: RouteRule[], replace: boolean) => void;
  onRulesetsChanged?: () => void;
}) {
  const [text, setText] = useState("");
  const [parsing, setParsing] = useState(false);
  const [result, setResult] = useState<ClashImportResult | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [subscribe, setSubscribe] = useState<Record<string, boolean>>({});
  const [replace, setReplace] = useState("append");
  const [importing, setImporting] = useState("");

  useEffect(() => {
    if (!isOpen) {
      setText("");
      setResult(null);
      setImporting("");
    }
  }, [isOpen]);

  // Policies used by rules; MATCH alone corresponds to the default exit.
  const policies = useMemo(() => {
    if (!result) return [];
    const used = new Set(result.rules.map((rule) => rule.policy));

    return result.policies.filter((policy) => used.has(policy.name));
  }, [result]);

  const parse = async () => {
    setParsing(true);
    const response = await parseClashRules(text);

    setParsing(false);
    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "解析失败");
    const data = response.data;

    setResult(data);
    setMapping(
      Object.fromEntries(
        data.policies.map((policy) => [
          policy.name,
          policy.suggested &&
          exitOptions.some((option) => option.key === policy.suggested)
            ? policy.suggested
            : "",
        ]),
      ),
    );
    setSubscribe(
      Object.fromEntries(
        data.providers.map((provider) => [
          provider.name,
          !provider.existingId && !provider.catalogKey && Boolean(provider.url),
        ]),
      ),
    );
  };

  const unmapped = policies.filter((policy) => !mapping[policy.name]);

  const submit = async () => {
    if (!result) return;
    // Rule providers: an existing subscription, a new one, or none (its rules are dropped).
    const providerIds: Record<string, number> = {};
    let created = 0;

    const catalogKeys: Record<string, string> = {};

    for (const provider of result.providers) {
      if (provider.catalogKey) {
        catalogKeys[provider.name] = provider.catalogKey;
        continue;
      }
      if (provider.existingId) {
        providerIds[provider.name] = provider.existingId;
        continue;
      }
      if (!provider.url || !subscribe[provider.name]) continue;
      setImporting(`正在订阅规则集 ${provider.name}…`);
      const response = await createRouteRuleset({
        name: provider.name,
        url: provider.url,
        behavior: provider.behavior || "classical",
        intervalHours: provider.intervalHours || 72,
      });

      if (response.code !== 0) {
        toast.error(`规则集 ${provider.name}：${response.msg || "订阅失败"}`);
        continue;
      }
      providerIds[provider.name] = response.data.id;
      created++;
    }
    setImporting("");
    if (created > 0) onRulesetsChanged?.();

    const rules: RouteRule[] = [];
    let dropped = 0;

    for (const rule of result.rules) {
      const exit = mapping[rule.policy];

      if (!exit || exit === SKIP) {
        dropped++;
        continue;
      }
      if (rule.match !== "ruleset") {
        rules.push({ match: rule.match, values: rule.values, exit });
        continue;
      }
      // Providers that are catalog entries use the shared copy; the rest their subscription.
      const shared = rule.values
        .map((name) => catalogKeys[name])
        .filter(Boolean);
      const subscribed = rule.values
        .filter((name) => !catalogKeys[name])
        .map((name) => providerIds[name])
        .filter(Boolean)
        .map(String);

      if (shared.length > 0)
        rules.push({ match: "catalog", values: shared, exit });
      if (subscribed.length > 0)
        rules.push({ match: "ruleset", values: subscribed, exit });
      if (shared.length + subscribed.length === 0) dropped++;
    }
    if (rules.length === 0) return toast.error("没有可以导入的规则");
    onImport(rules, replace === "replace");
    toast.success(
      `已导入 ${rules.length} 条规则${dropped > 0 ? `，跳过 ${dropped} 条` : ""}，记得点“保存”`,
    );
  };

  return (
    <Modal
      isOpen={isOpen}
      scrollBehavior="inside"
      size="3xl"
      onOpenChange={(open) => !open && onClose()}
    >
      <ModalContent>
        <ModalHeader>导入 Clash 规则</ModalHeader>
        <ModalBody className="space-y-4">
          {!result ? (
            <Textarea
              description="粘贴整份 Clash / Mihomo 配置，或只粘贴 rules 部分（每行一条，例如 DOMAIN-SUFFIX,openai.com,美国）。带 rule-providers 的配置会一并订阅规则集。"
              label="Clash 配置或规则"
              maxRows={16}
              minRows={8}
              value={text}
              onValueChange={setText}
            />
          ) : (
            <>
              <div className="rounded-xl bg-default-50 p-3 text-sm dark:bg-default-100/60">
                读到 {result.lines} 行规则，合并后可导入{" "}
                <span className="font-semibold">{result.rules.length}</span> 条
                {result.skipped > 0 && <>，有 {result.skipped} 行不支持</>}。
                {result.finalPolicy && (
                  <span className="text-default-500">
                    {" "}
                    MATCH（{result.finalPolicy}
                    ）对应上方的“默认出口”，请在那里选择。
                  </span>
                )}
                {result.rules.some((rule) =>
                  ["ip_cidr", "geoip", "ruleset"].includes(rule.match),
                ) && (
                  <p className="mt-1 text-xs text-default-500">
                    IP 段、GEOIP 和规则集里的 IP 默认只匹配直接用 IP
                    连接的流量，域名会原样交给出口；需要按解析结果匹配的规则，导入后可在规则里勾选“先解析域名再按
                    IP 匹配”。
                  </p>
                )}
                {result.skippedSamples.length > 0 && (
                  <details className="mt-2 text-xs text-default-500">
                    <summary className="cursor-pointer">
                      查看不支持的规则
                    </summary>
                    <ul className="mt-1 space-y-0.5 font-mono">
                      {result.skippedSamples.map((line) => (
                        <li key={line}>{line}</li>
                      ))}
                    </ul>
                  </details>
                )}
              </div>

              {policies.length > 0 && (
                <div className="space-y-2">
                  <div className="text-sm font-medium">策略对应的出口</div>
                  <p className="text-xs text-default-500">
                    Clash
                    里的每个策略组选一个出口。想自动选最快的节点，可以先在“出口管理
                    → 自定义出口”建一个出口组。
                  </p>
                  {policies.map((policy) => (
                    <div
                      key={policy.name}
                      className="flex flex-wrap items-center gap-2"
                    >
                      <span className="min-w-32 flex-1 truncate text-sm">
                        {policy.name}
                        <span className="ml-1 text-xs text-default-400">
                          {
                            result.rules.filter(
                              (rule) => rule.policy === policy.name,
                            ).length
                          }{" "}
                          条
                        </span>
                      </span>
                      <Select
                        aria-label={`${policy.name} 的出口`}
                        className="w-64"
                        isInvalid={!mapping[policy.name]}
                        placeholder="选择出口"
                        selectedKeys={
                          mapping[policy.name] ? [mapping[policy.name]] : []
                        }
                        size="sm"
                        onSelectionChange={(keys) =>
                          setMapping({
                            ...mapping,
                            [policy.name]: String(Array.from(keys)[0] || ""),
                          })
                        }
                      >
                        {[
                          ...exitOptions.map((option) => (
                            <SelectItem key={option.key}>
                              {option.label}
                            </SelectItem>
                          )),
                          <SelectItem key={SKIP}>不导入这些规则</SelectItem>,
                        ]}
                      </Select>
                    </div>
                  ))}
                </div>
              )}

              {result.providers.length > 0 && (
                <div className="space-y-2">
                  <div className="text-sm font-medium">用到的规则集</div>
                  {result.providers.map((provider) => (
                    <div key={provider.name} className="text-sm">
                      {provider.catalogKey ? (
                        <span>
                          {provider.name}
                          <span className="ml-2 text-xs text-success-600">
                            使用精选规则集“
                            {catalog.find(
                              (item) => item.key === provider.catalogKey,
                            )?.name || provider.catalogKey}
                            ”（共享，自动更新）
                          </span>
                        </span>
                      ) : provider.existingId ? (
                        <span>
                          {provider.name}
                          <span className="ml-2 text-xs text-success-600">
                            使用已有订阅“
                            {rulesets.find(
                              (item) => item.id === provider.existingId,
                            )?.name || provider.name}
                            ”
                          </span>
                        </span>
                      ) : provider.url ? (
                        <Checkbox
                          isSelected={Boolean(subscribe[provider.name])}
                          size="sm"
                          onValueChange={(selected) =>
                            setSubscribe({
                              ...subscribe,
                              [provider.name]: selected,
                            })
                          }
                        >
                          <span className="text-sm">
                            订阅 {provider.name}
                            <span className="ml-2 break-all font-mono text-xs text-default-400">
                              {provider.url}
                            </span>
                          </span>
                        </Checkbox>
                      ) : (
                        <span className="text-default-500">
                          {provider.name}
                          <span className="ml-2 text-xs">
                            配置里没有它的地址（或是二进制格式），相关规则会跳过
                          </span>
                        </span>
                      )}
                    </div>
                  ))}
                </div>
              )}

              <RadioGroup
                orientation="horizontal"
                size="sm"
                value={replace}
                onValueChange={setReplace}
              >
                <Radio value="append">追加到现有规则后面</Radio>
                <Radio value="replace">替换现有规则</Radio>
              </RadioGroup>
            </>
          )}
        </ModalBody>
        <ModalFooter>
          {result && (
            <Button
              className="mr-auto"
              variant="light"
              onPress={() => setResult(null)}
            >
              重新粘贴
            </Button>
          )}
          <Button variant="light" onPress={onClose}>
            取消
          </Button>
          {!result ? (
            <Button
              color="primary"
              isDisabled={!text.trim()}
              isLoading={parsing}
              onPress={parse}
            >
              解析
            </Button>
          ) : (
            <Button
              color="primary"
              isDisabled={unmapped.length > 0}
              isLoading={Boolean(importing)}
              onPress={submit}
            >
              {importing ||
                (unmapped.length > 0
                  ? `还有 ${unmapped.length} 个策略没选出口`
                  : "导入")}
            </Button>
          )}
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
