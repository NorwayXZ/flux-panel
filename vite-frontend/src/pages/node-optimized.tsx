/**
 * 优化版节点管理页面
 *
 * 主要优化点：
 * 1. 使用 React Query 进行数据获取和缓存
 * 2. 使用虚拟滚动处理大量节点
 * 3. 优化状态管理，减少不必要的重渲染
 * 4. 使用 useCallback 和 useMemo 优化性能
 */

import { useState, useCallback, useMemo } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import toast from "react-hot-toast";
import { RefreshCw, ServerCog, Plus, Search } from "lucide-react";

import { VirtualList } from "@/components/virtual-list";
import PageShell from "@/components/page-shell";
import { useNodes } from "@/hooks/use-api";
import { isAdmin } from "@/utils/auth";

interface Node {
  id: number;
  name: string;
  ip: string;
  serverIp: string;
  portSta: number;
  portEnd: number;
  version?: string;
  status: number;
  ownerUserName?: string;
  connectionStatus: "online" | "offline";
  systemInfo?: {
    cpuUsage: number;
    memoryUsage: number;
    uploadSpeed: number;
    downloadSpeed: number;
  } | null;
}

// 节点卡片组件 - 使用 memo 避免不必要的重渲染
const NodeCard = ({ node }: { node: Node }) => {
  const isOnline = node.connectionStatus === "online";

  return (
    <Card className="w-full">
      <CardHeader className="flex justify-between">
        <div className="flex items-center gap-2">
          <ServerCog className="h-5 w-5" />
          <h3 className="text-lg font-semibold">{node.name}</h3>
        </div>
        <Chip color={isOnline ? "success" : "default"} size="sm" variant="flat">
          {isOnline ? "在线" : "离线"}
        </Chip>
      </CardHeader>
      <CardBody>
        <div className="space-y-2 text-sm">
          <div className="flex justify-between">
            <span className="text-default-500">IP地址:</span>
            <span className="font-mono">{node.ip}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-default-500">端口范围:</span>
            <span className="font-mono">
              {node.portSta}-{node.portEnd}
            </span>
          </div>
          {node.version && (
            <div className="flex justify-between">
              <span className="text-default-500">版本:</span>
              <span className="font-mono">{node.version}</span>
            </div>
          )}
          {node.systemInfo && isOnline && (
            <div className="mt-3 space-y-1 border-t pt-2">
              <div className="flex justify-between text-xs">
                <span className="text-default-500">CPU使用率:</span>
                <span>{node.systemInfo.cpuUsage.toFixed(1)}%</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-default-500">内存使用率:</span>
                <span>{node.systemInfo.memoryUsage.toFixed(1)}%</span>
              </div>
            </div>
          )}
        </div>
      </CardBody>
    </Card>
  );
};

export function NodePageOptimized() {
  const [searchTerm, setSearchTerm] = useState("");
  const admin = isAdmin();

  // 使用 React Query hook 获取节点列表
  const { data: nodes = [], isLoading, isError, error, refetch } = useNodes();

  // 过滤节点列表
  const filteredNodes = useMemo(() => {
    if (!searchTerm) return nodes;

    const term = searchTerm.toLowerCase();

    return nodes.filter(
      (node: Node) =>
        node.name.toLowerCase().includes(term) ||
        node.ip.toLowerCase().includes(term) ||
        node.serverIp?.toLowerCase().includes(term),
    );
  }, [nodes, searchTerm]);

  // 处理搜索
  const handleSearch = useCallback((value: string) => {
    setSearchTerm(value);
  }, []);

  // 刷新数据
  const handleRefresh = useCallback(() => {
    toast.promise(refetch(), {
      loading: "正在刷新节点列表...",
      success: "刷新成功",
      error: "刷新失败",
    });
  }, [refetch]);

  // 渲染单个节点项
  const renderNodeItem = useCallback(
    (node: Node) => (
      <div className="px-4 py-2">
        <NodeCard node={node} />
      </div>
    ),
    [],
  );

  return (
    <PageShell>
      <header className="mb-6">
        <h1 className="text-2xl font-semibold">节点管理</h1>
        <p>{admin ? "管理所有GOST节点" : "查看可用节点"}</p>
      </header>
      {/* 工具栏 */}
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <Input
          className="max-w-xs"
          placeholder="搜索节点名称或IP..."
          startContent={<Search className="h-4 w-4 text-default-400" />}
          value={searchTerm}
          onChange={(e) => handleSearch(e.target.value)}
        />

        <div className="flex gap-2">
          <Button
            color="primary"
            isIconOnly
            variant="flat"
            onClick={handleRefresh}
          >
            <RefreshCw className="h-4 w-4" />
          </Button>

          {admin && (
            <Button color="primary" startContent={<Plus className="h-4 w-4" />}>
              添加节点
            </Button>
          )}
        </div>
      </div>

      {/* 节点列表 */}
      {isLoading && (
        <div className="flex items-center justify-center py-12">
          <Spinner size="lg" />
        </div>
      )}

      {isError && (
        <Card className="border-danger-200 bg-danger-50">
          <CardBody>
            <p className="text-danger">
              加载失败: {error instanceof Error ? error.message : "未知错误"}
            </p>
          </CardBody>
        </Card>
      )}

      {!isLoading && !isError && filteredNodes.length === 0 && (
        <Card>
          <CardBody>
            <p className="text-center text-default-500">
              {searchTerm ? "没有找到匹配的节点" : "暂无节点"}
            </p>
          </CardBody>
        </Card>
      )}

      {!isLoading && !isError && filteredNodes.length > 0 && (
        <>
          <div className="mb-2 text-sm text-default-500">
            共 {filteredNodes.length} 个节点
            {searchTerm && ` (从 ${nodes.length} 个中筛选)`}
          </div>

          {/* 使用虚拟滚动优化大列表性能 */}
          <VirtualList
            items={filteredNodes}
            estimatedItemHeight={200}
            height={600}
            renderItem={renderNodeItem}
            getItemKey={(node) => `node-${node.id}`}
          />
        </>
      )}
    </PageShell>
  );
}

export default NodePageOptimized;
