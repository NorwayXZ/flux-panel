/**
 * 节点列表页面示例 - 展示所有新功能的集成
 *
 * 包含：
 * - 批量选择和操作
 * - 数据导出
 * - 高级搜索和过滤
 * - 性能优化（React Query + 虚拟滚动）
 * - 新UI组件
 */

import { useState } from 'react';
import { Card, CardBody, CardHeader } from '@heroui/card';
import { Button } from '@heroui/button';
import { Input } from '@heroui/input';
import { Checkbox } from '@heroui/checkbox';
import { Chip } from '@heroui/chip';
import {
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter,
  useDisclosure,
} from '@heroui/modal';
import {
  Search,
  Filter,
  Plus,
  RefreshCw,
  ServerCog,
} from 'lucide-react';

// 新组件导入
import { EnhancedCard, StatCard } from '@/components/ui/enhanced-card';
import { StatusBadge } from '@/components/ui/status-badge';
import { SkeletonCardGrid } from '@/components/ui/skeleton';
import { BatchOperationBar, CompactBatchBar } from '@/components/ui/batch-operation-bar';
import { ExportMenu } from '@/lib/export';
import { useSimpleBatchSelection } from '@/hooks/use-batch-selection';
import { useBreakpoint } from '@/hooks/use-breakpoint';
import { VirtualList } from '@/components/ui/virtual-list';

// API Hooks
import { useNodes, useDeleteNodes, useUpdateNodeStatus } from '@/hooks/api/use-nodes';

// 类型定义
interface Node {
  id: number;
  name: string;
  ip: string;
  port: number;
  status: 'online' | 'offline';
  cpu: number;
  memory: number;
  traffic: number;
  createdTime: number;
  tags?: string[];
}

export default function NodesPageExample() {
  const { isMobile } = useBreakpoint();
  const [searchText, setSearchText] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'online' | 'offline'>('all');

  // 数据获取
  const { data: nodes = [], isLoading, refetch } = useNodes();

  // 批量选择
  const selection = useSimpleBatchSelection(nodes);

  // 批量操作
  const deleteNodesMutation = useDeleteNodes();
  const updateStatusMutation = useUpdateNodeStatus();

  // 确认对话框
  const { isOpen: isDeleteOpen, onOpen: onDeleteOpen, onClose: onDeleteClose } = useDisclosure();

  // 过滤数据
  const filteredNodes = nodes.filter(node => {
    // 搜索过滤
    if (searchText) {
      const search = searchText.toLowerCase();
      if (
        !node.name.toLowerCase().includes(search) &&
        !node.ip.includes(search)
      ) {
        return false;
      }
    }

    // 状态过滤
    if (statusFilter !== 'all' && node.status !== statusFilter) {
      return false;
    }

    return true;
  });

  // 统计数据
  const stats = {
    total: nodes.length,
    online: nodes.filter(n => n.status === 'online').length,
    offline: nodes.filter(n => n.status === 'offline').length,
    selected: selection.selectedCount,
  };

  // 批量删除
  const handleBatchDelete = async () => {
    try {
      await deleteNodesMutation.mutateAsync(
        selection.selectedItems.map(n => n.id)
      );
      selection.reset();
      onDeleteClose();
    } catch (error) {
      console.error('批量删除失败:', error);
    }
  };

  // 批量启用
  const handleBatchEnable = async () => {
    try {
      await updateStatusMutation.mutateAsync({
        ids: selection.selectedItems.map(n => n.id),
        status: 'online',
      });
      selection.reset();
    } catch (error) {
      console.error('批量启用失败:', error);
    }
  };

  // 批量禁用
  const handleBatchDisable = async () => {
    try {
      await updateStatusMutation.mutateAsync({
        ids: selection.selectedItems.map(n => n.id),
        status: 'offline',
      });
      selection.reset();
    } catch (error) {
      console.error('批量禁用失败:', error);
    }
  };

  // 导出列配置
  const exportColumns = [
    { key: 'id', label: 'ID' },
    { key: 'name', label: '节点名称' },
    { key: 'ip', label: 'IP地址' },
    { key: 'port', label: '端口' },
    {
      key: 'status',
      label: '状态',
      format: (value: string) => value === 'online' ? '在线' : '离线',
    },
    { key: 'cpu', label: 'CPU使用率(%)' },
    { key: 'memory', label: '内存使用率(%)' },
    {
      key: 'traffic',
      label: '流量(GB)',
      format: (value: number) => (value / 1024 / 1024 / 1024).toFixed(2),
    },
    {
      key: 'createdTime',
      label: '创建时间',
      format: (value: number) => new Date(value).toLocaleString('zh-CN'),
    },
  ];

  return (
    <div className="p-6 space-y-6">
      {/* 页面标题 */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">节点管理</h1>
          <p className="text-default-500 mt-1">管理和监控所有节点</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="flat"
            startContent={<RefreshCw className="h-4 w-4" />}
            onPress={() => refetch()}
          >
            刷新
          </Button>
          <Button
            color="primary"
            startContent={<Plus className="h-4 w-4" />}
          >
            添加节点
          </Button>
        </div>
      </div>

      {/* 统计卡片 */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="总节点数"
          value={stats.total}
          icon={<ServerCog className="h-6 w-6" />}
          color="primary"
        />
        <StatCard
          title="在线节点"
          value={stats.online}
          icon={<ServerCog className="h-6 w-6" />}
          color="success"
          change={{ value: 12, trend: 'up' }}
        />
        <StatCard
          title="离线节点"
          value={stats.offline}
          icon={<ServerCog className="h-6 w-6" />}
          color="danger"
          change={{ value: 5, trend: 'down' }}
        />
        <StatCard
          title="已选择"
          value={stats.selected}
          icon={<ServerCog className="h-6 w-6" />}
          color="default"
        />
      </div>

      {/* 搜索和过滤栏 */}
      <Card>
        <CardBody>
          <div className="flex flex-col md:flex-row gap-4">
            {/* 搜索框 */}
            <Input
              placeholder="搜索节点名称或IP..."
              startContent={<Search className="h-4 w-4 text-default-400" />}
              value={searchText}
              onValueChange={setSearchText}
              className="flex-1"
            />

            {/* 状态过滤 */}
            <div className="flex gap-2">
              <Button
                variant={statusFilter === 'all' ? 'solid' : 'flat'}
                color={statusFilter === 'all' ? 'primary' : 'default'}
                onPress={() => setStatusFilter('all')}
              >
                全部
              </Button>
              <Button
                variant={statusFilter === 'online' ? 'solid' : 'flat'}
                color={statusFilter === 'online' ? 'success' : 'default'}
                onPress={() => setStatusFilter('online')}
              >
                在线
              </Button>
              <Button
                variant={statusFilter === 'offline' ? 'solid' : 'flat'}
                color={statusFilter === 'offline' ? 'danger' : 'default'}
                onPress={() => setStatusFilter('offline')}
              >
                离线
              </Button>
            </div>

            {/* 导出按钮 */}
            <ExportMenu
              data={selection.selectedCount > 0 ? selection.selectedItems : filteredNodes}
              filename="nodes"
              columns={exportColumns}
            />
          </div>
        </CardBody>
      </Card>

      {/* 批量操作提示条（紧凑版） */}
      <CompactBatchBar
        selectedCount={selection.selectedCount}
        totalCount={filteredNodes.length}
        onSelectAll={selection.selectAll}
        onClearSelection={selection.deselectAll}
        actions={
          <>
            <Button
              size="sm"
              variant="flat"
              color="success"
              onPress={handleBatchEnable}
            >
              启用
            </Button>
            <Button
              size="sm"
              variant="flat"
              color="warning"
              onPress={handleBatchDisable}
            >
              禁用
            </Button>
            <Button
              size="sm"
              variant="flat"
              color="danger"
              onPress={onDeleteOpen}
            >
              删除
            </Button>
          </>
        }
      />

      {/* 节点列表 */}
      {isLoading ? (
        <SkeletonCardGrid count={6} cols={{ default: 1, md: 2, lg: 3 }} />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filteredNodes.map(node => (
            <EnhancedCard
              key={node.id}
              variant="elevated"
              status={node.status === 'online' ? 'success' : 'danger'}
              interactive
              className="hover:border-primary"
              icon={<ServerCog className="h-5 w-5" />}
              title={node.name}
              subtitle={`${node.ip}:${node.port}`}
              action={
                <Checkbox
                  isSelected={selection.isSelected(node.id)}
                  onValueChange={() => selection.toggle(node.id)}
                />
              }
            >
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm text-default-500">状态</span>
                  <StatusBadge
                    status={node.status === 'online' ? 'online' : 'offline'}
                  />
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-sm text-default-500">CPU</span>
                  <span className="text-sm font-medium">{node.cpu}%</span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-sm text-default-500">内存</span>
                  <span className="text-sm font-medium">{node.memory}%</span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-sm text-default-500">流量</span>
                  <span className="text-sm font-medium">
                    {(node.traffic / 1024 / 1024 / 1024).toFixed(2)} GB
                  </span>
                </div>

                {node.tags && node.tags.length > 0 && (
                  <div className="flex flex-wrap gap-1">
                    {node.tags.map(tag => (
                      <Chip key={tag} size="sm" variant="flat">
                        {tag}
                      </Chip>
                    ))}
                  </div>
                )}
              </div>
            </EnhancedCard>
          ))}
        </div>
      )}

      {/* 批量操作工具栏（浮动） */}
      <BatchOperationBar
        selectedCount={selection.selectedCount}
        onClearSelection={selection.deselectAll}
        onDelete={onDeleteOpen}
        onEnable={handleBatchEnable}
        onDisable={handleBatchDisable}
        onExport={() => {
          // 导出选中项
          ExportMenu({
            data: selection.selectedItems,
            filename: 'selected-nodes',
            columns: exportColumns,
          });
        }}
        loading={deleteNodesMutation.isPending || updateStatusMutation.isPending}
      />

      {/* 删除确认对话框 */}
      <Modal isOpen={isDeleteOpen} onClose={onDeleteClose}>
        <ModalContent>
          <ModalHeader>批量删除确认</ModalHeader>
          <ModalBody>
            <p>确定要删除以下 {selection.selectedCount} 个节点吗？此操作无法撤销。</p>
            <div className="mt-4 space-y-2 max-h-60 overflow-y-auto">
              {selection.selectedItems.map(node => (
                <div
                  key={node.id}
                  className="flex items-center gap-2 p-2 bg-danger/10 rounded"
                >
                  <ServerCog className="h-4 w-4 text-danger" />
                  <span className="text-sm">{node.name}</span>
                  <span className="text-xs text-default-500">({node.ip})</span>
                </div>
              ))}
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={onDeleteClose}>
              取消
            </Button>
            <Button
              color="danger"
              onPress={handleBatchDelete}
              isLoading={deleteNodesMutation.isPending}
            >
              删除
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
