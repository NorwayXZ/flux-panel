import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Select, SelectItem } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import { ListPlus, Pencil, RefreshCw, Trash2 } from "lucide-react";
import toast from "react-hot-toast";

import {
  createRouteRuleset,
  deleteRouteRuleset,
  getRouteRulesets,
  refreshRouteRuleset,
  updateRouteRuleset,
  type RouteRuleset,
} from "@/api";
import { formatDateTime } from "@/utils/format";

const behaviorLabel: Record<RouteRuleset["behavior"], string> = {
  auto: "自动识别",
  domain: "domain（域名列表）",
  ipcidr: "ipcidr（IP 段列表）",
  classical: "classical（带类型的规则）",
};

const intervalOptions = [
  { key: "6", label: "每 6 小时" },
  { key: "24", label: "每天" },
  { key: "72", label: "每 3 天" },
  { key: "168", label: "每周" },
];

const emptyForm = {
  name: "",
  url: "",
  behavior: "auto" as RouteRuleset["behavior"],
  intervalHours: "72",
};

/** Clash rule providers subscribed by URL; the panel downloads and refreshes them. */
export default function RulesetsPanel() {
  const [rulesets, setRulesets] = useState<RouteRuleset[]>([]);
  const [loading, setLoading] = useState(true);
  const [target, setTarget] = useState<RouteRuleset | "new" | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [refreshing, setRefreshing] = useState<number | null>(null);

  const load = async () => {
    const response = await getRouteRulesets();

    if (response.code === 0) setRulesets(response.data || []);
    else toast.error(response.msg || "读取规则集失败");
    setLoading(false);
  };

  useEffect(() => {
    void load();
  }, []);

  const open = (ruleset?: RouteRuleset) => {
    setForm(
      ruleset
        ? {
            name: ruleset.name,
            url: ruleset.url,
            behavior: ruleset.behavior,
            intervalHours: String(ruleset.intervalHours),
          }
        : emptyForm,
    );
    setTarget(ruleset || "new");
  };

  const save = async () => {
    setSaving(true);
    const data = {
      name: form.name.trim(),
      url: form.url.trim(),
      behavior: form.behavior,
      intervalHours: Number(form.intervalHours),
    };
    const response =
      target && target !== "new"
        ? await updateRouteRuleset({ id: target.id, ...data })
        : await createRouteRuleset(data);

    setSaving(false);
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    const created = response.data as
      { count?: number; skipped?: number } | undefined;

    toast.success(
      created?.count !== undefined
        ? `已订阅，读到 ${created.count} 条${created.skipped ? `，${created.skipped} 条不支持` : ""}`
        : "已保存",
    );
    setTarget(null);
    void load();
  };

  const refresh = async (ruleset: RouteRuleset) => {
    setRefreshing(ruleset.id);
    const response = await refreshRouteRuleset(ruleset.id);

    setRefreshing(null);
    if (response.code !== 0) toast.error(response.msg || "更新失败");
    else
      toast.success(
        response.data.changed
          ? `已更新为 ${response.data.count} 条${response.data.reapplied ? `，已重新下发 ${response.data.reapplied} 个代理` : ""}`
          : "内容没有变化",
      );
    void load();
  };

  const remove = async (ruleset: RouteRuleset) => {
    if (!window.confirm(`删除规则集“${ruleset.name}”吗？`)) return;
    const response = await deleteRouteRuleset(ruleset.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("已删除");
    void load();
  };

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="max-w-3xl">
          <h2 className="text-base font-semibold text-foreground">
            规则集订阅
          </h2>
          <p className="mt-1 text-sm text-default-500">
            填写 Clash rule-provider 的地址（YAML payload、每行一条的文本或
            sing-box
            JSON），面板按设定的间隔下载更新，有变化时自动重新下发用到它的代理。分流规则里选“规则集订阅”即可使用。
          </p>
        </div>
        <Button
          color="primary"
          startContent={<ListPlus size={16} />}
          onPress={() => open()}
        >
          添加规则集
        </Button>
      </div>

      {loading ? (
        <div className="flex min-h-24 items-center justify-center">
          <Spinner />
        </div>
      ) : rulesets.length === 0 ? (
        <div className="rounded-xl border border-dashed border-divider px-6 py-6 text-center text-sm text-default-500">
          还没有规则集。可以添加 blackmatrix7、Loyalsoldier
          等社区维护的规则集地址，或在分流规则里“导入 Clash 规则”时一并订阅。
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
          {rulesets.map((ruleset) => (
            <div
              key={ruleset.id}
              className="flex flex-col gap-2 rounded-2xl border border-divider bg-content1 p-4"
            >
              <div className="flex items-center justify-between gap-2">
                <div className="min-w-0 truncate font-semibold text-foreground">
                  {ruleset.name}
                </div>
                <Chip
                  className="shrink-0"
                  color={ruleset.lastError ? "danger" : "success"}
                  size="sm"
                  variant="flat"
                >
                  {ruleset.lastError ? "更新失败" : "正常"}
                </Chip>
              </div>
              <div className="flex flex-wrap items-baseline gap-x-2 text-xs text-default-500">
                <span className="font-semibold text-foreground">
                  {ruleset.count} 条
                </span>
                {ruleset.skipped > 0 && <span>{ruleset.skipped} 条不支持</span>}
                <span>
                  {intervalOptions.find(
                    (option) => option.key === String(ruleset.intervalHours),
                  )?.label || `每 ${ruleset.intervalHours} 小时`}
                </span>
                <span>使用中 {ruleset.usedBy}</span>
              </div>
              <div className="text-xs text-default-500">
                更新于 {formatDateTime(ruleset.updatedAt, "-")} · 检查于{" "}
                {formatDateTime(ruleset.checkedAt, "-")}
              </div>
              <p
                className="truncate font-mono text-xs text-default-400"
                title={ruleset.url}
              >
                {ruleset.url}
              </p>
              {ruleset.lastError && (
                <p className="text-xs text-danger" title={ruleset.lastError}>
                  {ruleset.lastError}
                </p>
              )}
              <div className="mt-auto flex gap-1 pt-1">
                <Button
                  isLoading={refreshing === ruleset.id}
                  size="sm"
                  startContent={
                    refreshing === ruleset.id ? null : <RefreshCw size={14} />
                  }
                  variant="flat"
                  onPress={() => void refresh(ruleset)}
                >
                  更新
                </Button>
                <Button
                  size="sm"
                  startContent={<Pencil size={14} />}
                  variant="light"
                  onPress={() => open(ruleset)}
                >
                  编辑
                </Button>
                <Button
                  color="danger"
                  size="sm"
                  startContent={<Trash2 size={14} />}
                  variant="light"
                  onPress={() => void remove(ruleset)}
                >
                  删除
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal
        isOpen={Boolean(target)}
        size="xl"
        onOpenChange={(isOpen) => !isOpen && setTarget(null)}
      >
        <ModalContent>
          <ModalHeader>
            {target === "new" ? "添加规则集" : "编辑规则集"}
          </ModalHeader>
          <ModalBody className="space-y-3">
            <Input
              label="名称"
              placeholder="例如 OpenAI"
              value={form.name}
              onValueChange={(name) => setForm({ ...form, name })}
            />
            <Input
              description="例如 https://cdn.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/OpenAI/OpenAI.yaml；不支持 .mrs / .srs 二进制格式"
              label="地址"
              placeholder="https://"
              value={form.url}
              onValueChange={(url) => setForm({ ...form, url })}
            />
            <div className="grid gap-3 sm:grid-cols-2">
              <Select
                description="和 Clash 配置里的 behavior 一致"
                label="类型"
                selectedKeys={[form.behavior]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    behavior: String(
                      Array.from(keys)[0] || "auto",
                    ) as RouteRuleset["behavior"],
                  })
                }
              >
                {(Object.keys(behaviorLabel) as RouteRuleset["behavior"][]).map(
                  (key) => (
                    <SelectItem key={key}>{behaviorLabel[key]}</SelectItem>
                  ),
                )}
              </Select>
              <Select
                label="更新间隔"
                selectedKeys={[form.intervalHours]}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    intervalHours: String(Array.from(keys)[0] || "72"),
                  })
                }
              >
                {[
                  ...intervalOptions,
                  ...(intervalOptions.some(
                    (option) => option.key === form.intervalHours,
                  )
                    ? []
                    : [
                        {
                          key: form.intervalHours,
                          label: `每 ${form.intervalHours} 小时`,
                        },
                      ]),
                ].map((option) => (
                  <SelectItem key={option.key}>{option.label}</SelectItem>
                ))}
              </Select>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setTarget(null)}>
              取消
            </Button>
            <Button
              color="primary"
              isDisabled={!form.name.trim() || !form.url.trim()}
              isLoading={saving}
              onPress={save}
            >
              {target === "new" ? "下载并保存" : "保存"}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </section>
  );
}
