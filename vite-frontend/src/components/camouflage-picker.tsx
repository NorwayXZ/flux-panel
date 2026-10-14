import type { CamouflageTarget } from "@/api";

import { useState } from "react";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import { Gauge } from "lucide-react";
import toast from "react-hot-toast";

import { getCamouflageTargets } from "@/api";

export const AUTO_CAMOUFLAGE = "auto";

const PRESETS = [
  "www.microsoft.com",
  "www.apple.com",
  "www.amazon.com",
  "www.cloudflare.com",
];

interface CamouflagePickerProps {
  /** "auto" 或域名 */
  value: string;
  onChange: (value: string) => void;
  /** 选了节点才能检测 */
  nodeId?: number;
  label: string;
  className?: string;
}

/**
 * 选择 REALITY 伪装站（或 ShadowTLS 握手网站）。默认自动选择：创建时节点检测候选网站，
 * 选能用且握手最快的。也可以先“检测”看每个网站在这个节点上的结果，再点选一个。
 */
export default function CamouflagePicker({
  value,
  onChange,
  nodeId,
  label,
  className,
}: CamouflagePickerProps) {
  const [results, setResults] = useState<CamouflageTarget[] | null>(null);
  const [checking, setChecking] = useState(false);
  const preset =
    value === AUTO_CAMOUFLAGE || PRESETS.includes(value) ? value : "custom";

  const check = async () => {
    if (!nodeId) return toast.error("请先选择服务器节点");
    setChecking(true);
    const custom = preset === "custom" && value.trim() ? [value.trim()] : [];
    const [response, extra] = await Promise.all([
      getCamouflageTargets(nodeId),
      custom.length ? getCamouflageTargets(nodeId, custom) : null,
    ]);

    setChecking(false);
    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "检测失败");
    const merged = [...(extra?.data || []), ...response.data].filter(
      (item, index, list) =>
        list.findIndex((other) => other.domain === item.domain) === index,
    );

    merged.sort((a, b) =>
      a.ok !== b.ok ? (a.ok ? -1 : 1) : (a.latencyMs || 0) - (b.latencyMs || 0),
    );
    setResults(merged);
  };

  return (
    <div className={`space-y-2 ${className || ""}`}>
      <div className="flex items-end gap-2">
        <Select
          className="flex-1"
          description="伪装站要能从节点连上，并支持 TLS 1.3、X25519 和 HTTP/2；也不能是国内被封锁的网站，否则设备的连接会被拦截。"
          label={label}
          selectedKeys={[preset]}
          onSelectionChange={(keys) => {
            const key = String(Array.from(keys)[0] || AUTO_CAMOUFLAGE);

            onChange(key === "custom" ? "" : key);
          }}
        >
          {[
            <SelectItem key={AUTO_CAMOUFLAGE}>
              自动选择（节点测速，选能用且最快的）
            </SelectItem>,
            ...PRESETS.map((domain) => (
              <SelectItem key={domain}>{domain}</SelectItem>
            )),
            <SelectItem key="custom">自定义域名</SelectItem>,
          ]}
        </Select>
        <Button
          className="mb-6 shrink-0"
          isDisabled={!nodeId}
          isLoading={checking}
          startContent={!checking && <Gauge size={16} />}
          title={nodeId ? "在节点上检测候选网站" : "请先选择服务器节点"}
          variant="flat"
          onPress={check}
        >
          检测
        </Button>
      </div>
      {preset === "custom" && (
        <Input
          description="创建前会在节点上检测，不可用会提示原因。"
          label="自定义伪装域名"
          placeholder="例如 www.example.com"
          value={value}
          onValueChange={onChange}
        />
      )}
      {checking && !results && (
        <div className="flex items-center gap-2 text-xs text-default-500">
          <Spinner size="sm" />
          正在从节点逐个检测，约需 10 秒
        </div>
      )}
      {results && (
        <div className="max-h-64 overflow-y-auto rounded-md border border-divider">
          {results.map((item) => {
            const selected = item.domain === value;

            return (
              <button
                key={item.domain}
                className={`flex w-full items-center justify-between gap-3 border-b border-divider px-3 py-2 text-left text-sm last:border-b-0 ${item.ok ? "hover:bg-default-50" : "cursor-not-allowed opacity-60"} ${selected ? "bg-primary-50 dark:bg-primary-500/10" : ""}`}
                disabled={!item.ok}
                type="button"
                onClick={() => onChange(item.domain)}
              >
                <span className="min-w-0 break-all font-mono text-xs">
                  {item.domain}
                </span>
                <span
                  className={`shrink-0 text-xs ${item.ok ? "text-success" : "text-danger"}`}
                >
                  {item.ok ? `${item.latencyMs} ms` : item.reason || "不可用"}
                </span>
              </button>
            );
          })}
        </div>
      )}
      {results && (
        <p className="text-xs text-default-500">
          点击一个可用的网站即可选用。用时是节点和网站完成一次 TLS
          握手的时间，每次连接都要经过它，越短越好。
        </p>
      )}
    </div>
  );
}
