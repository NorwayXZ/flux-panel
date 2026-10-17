import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Spinner } from "@heroui/spinner";
import { Download, RefreshCw } from "lucide-react";
import toast from "react-hot-toast";

import {
  getRuleCatalog,
  refreshRuleCatalog,
  type RuleCatalogItem,
} from "@/api";
import { formatDateTime } from "@/utils/format";

const ago = (time?: number | null) => {
  if (!time) return "";
  const hours = Math.floor((Date.now() - time) / 3_600_000);

  if (hours < 1) return "刚刚更新";
  if (hours < 24) return `${hours} 小时前更新`;

  return `${Math.floor(hours / 24)} 天前更新`;
};

/** Well-known community rule sets: downloaded once, shared by everyone, checked every 12 hours. */
export default function RuleCatalogPanel() {
  const [items, setItems] = useState<RuleCatalogItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState<string | null>(null);

  const load = async () => {
    const response = await getRuleCatalog();

    if (response.code === 0) setItems(response.data || []);
    else toast.error(response.msg || "读取精选规则集失败");
    setLoading(false);
  };

  useEffect(() => {
    void load();
  }, []);

  const refresh = async (item: RuleCatalogItem) => {
    setRefreshing(item.key);
    const response = await refreshRuleCatalog(item.key);

    setRefreshing(null);
    if (response.code !== 0) toast.error(response.msg || "下载失败");
    else
      toast.success(
        `${item.name}：${response.data.count} 条${response.data.reapplied ? `，已重新下发 ${response.data.reapplied} 个代理` : ""}`,
      );
    void load();
  };

  const categories = Array.from(new Set(items.map((item) => item.category)));

  return (
    <section className="space-y-3">
      <div className="max-w-3xl">
        <h2 className="text-base font-semibold text-foreground">精选规则集</h2>
        <p className="mt-1 text-sm text-default-500">
          blackmatrix7、Loyalsoldier
          等社区持续维护的规则集，在分流规则里点“按规则集”即可直接选用，不用自己找地址。所有用户共用一份，被使用的规则集每
          12 小时检查一次更新，有变化时自动重新下发相关代理。
        </p>
      </div>
      {loading ? (
        <div className="flex min-h-24 items-center justify-center">
          <Spinner />
        </div>
      ) : (
        <div className="space-y-4">
          {categories.map((category) => (
            <div key={category} className="space-y-2">
              <div className="text-xs font-medium text-default-500">
                {category}
              </div>
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
                {items
                  .filter((item) => item.category === category)
                  .map((item) => (
                    <div
                      key={item.key}
                      className="flex items-center gap-3 rounded-xl border border-divider bg-content1 px-3 py-2.5"
                    >
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium text-foreground">
                          {item.name}
                        </div>
                        <div
                          className={`truncate text-xs ${item.lastError ? "text-danger" : "text-default-500"}`}
                          title={
                            item.lastError ||
                            `${item.source} · ${item.url}${item.updatedAt ? ` · 更新于 ${formatDateTime(item.updatedAt)}` : ""}`
                          }
                        >
                          {item.lastError
                            ? `更新失败：${item.lastError}`
                            : item.count != null
                              ? `${item.count} 条 · ${ago(item.updatedAt)}`
                              : "首次使用时下载"}
                        </div>
                      </div>
                      <Button
                        isIconOnly
                        aria-label={
                          item.count != null
                            ? `更新 ${item.name}`
                            : `下载 ${item.name}`
                        }
                        isLoading={refreshing === item.key}
                        size="sm"
                        variant="light"
                        onPress={() => void refresh(item)}
                      >
                        {item.count != null ? (
                          <RefreshCw size={14} />
                        ) : (
                          <Download size={14} />
                        )}
                      </Button>
                    </div>
                  ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
