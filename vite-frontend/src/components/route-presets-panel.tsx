import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import { RefreshCw } from "lucide-react";
import toast from "react-hot-toast";

import {
  getRoutePresets,
  refreshRoutePresets,
  type RoutePresetStatus,
} from "@/api";
import { formatDateTime } from "@/utils/format";

/** The domain groups routing rules can pick, and when each was last refreshed. */
export default function RoutePresetsPanel({
  canRefresh,
}: {
  canRefresh: boolean;
}) {
  const [status, setStatus] = useState<RoutePresetStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const load = async () => {
    const response = await getRoutePresets();

    if (response.code === 0 && response.data) setStatus(response.data);
    else toast.error(response.msg || "读取规则分组失败");
    setLoading(false);
  };

  useEffect(() => {
    void load();
  }, []);

  const refresh = async () => {
    setRefreshing(true);
    const response = await refreshRoutePresets();

    setRefreshing(false);
    if (response.code !== 0) return toast.error(response.msg || "更新失败");
    const { updated, reapplied, errors } = response.data;

    if (errors.length > 0) toast.error(`部分分组更新失败：${errors[0]}`);
    else
      toast.success(
        updated > 0
          ? `${updated} 个分组有变化${reapplied > 0 ? `，已重新下发 ${reapplied} 个代理` : ""}`
          : "已是最新",
      );
    void load();
  };

  if (loading)
    return (
      <div className="flex min-h-40 items-center justify-center">
        <Spinner />
      </div>
    );

  const presets = status?.presets || [];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="max-w-3xl space-y-1 text-sm text-default-500">
          <p>
            私人代理“出口与分流”里“按分组”可选的域名分组。标着“自动更新”的分组每{" "}
            {status?.intervalDays || 3} 天从 v2fly
            社区域名清单同步一次，由面板下载，节点不需要访问
            GitHub；有变化时，用到它的代理会自动重新下发。
          </p>
          <p className="text-xs">
            上次检查：{formatDateTime(status?.lastRunAt, "尚未检查")}
            {status?.lastError ? (
              <span className="ml-2 text-danger">{status.lastError}</span>
            ) : null}
          </p>
        </div>
        {canRefresh && (
          <Button
            color="primary"
            isLoading={refreshing}
            startContent={refreshing ? null : <RefreshCw size={16} />}
            variant="flat"
            onPress={refresh}
          >
            立即更新
          </Button>
        )}
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
        {presets.map((preset) => (
          <div
            key={preset.key}
            className="space-y-2 rounded-2xl border border-divider bg-content1 p-4"
          >
            <div className="flex items-center justify-between gap-2">
              <div className="min-w-0 truncate font-semibold text-foreground">
                {preset.name}
              </div>
              <Chip
                className="shrink-0"
                color={preset.autoUpdate ? "success" : "default"}
                size="sm"
                variant="flat"
              >
                {preset.autoUpdate ? "自动更新" : "内置"}
              </Chip>
            </div>
            <div className="flex flex-wrap items-baseline gap-x-2 text-xs text-default-500">
              <span className="font-semibold text-foreground">
                {preset.count ?? preset.domains.length} 条
              </span>
              <span>
                {preset.autoUpdate
                  ? preset.updatedAt
                    ? `更新于 ${formatDateTime(preset.updatedAt)}`
                    : "等待首次同步，暂用内置清单"
                  : "固定清单"}
              </span>
            </div>
            <p
              className="line-clamp-2 font-mono text-xs text-default-400"
              title={preset.domains.join("\n")}
            >
              {preset.domains.slice(0, 8).join("  ")}
            </p>
          </div>
        ))}
      </div>
    </div>
  );
}
