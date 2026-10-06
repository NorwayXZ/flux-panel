# 🚀 Flux Panel 改进功能快速入门指南

## 📖 目录

- [安装依赖](#安装依赖)
- [核心概念](#核心概念)
- [常用组件](#常用组件)
- [实战示例](#实战示例)
- [常见问题](#常见问题)

---

## 安装依赖

首先，安装所有必需的依赖包：

```bash
cd vite-frontend

# 核心依赖
npm install @tanstack/react-query@^5.0.0 @tanstack/react-query-devtools@^5.0.0

# 性能优化
npm install react-virtual@^2.10.4

# UI增强
npm install framer-motion@^11.0.0

# 数据导出
npm install xlsx@^0.18.5

# 图标（如果还没安装）
npm install lucide-react@^0.400.0
```

---

## 核心概念

### 1. React Query - 数据管理

React Query 是一个强大的数据获取和状态管理库，已经配置完成。

**主要特性**：
- ✅ 自动缓存（5分钟）
- ✅ 后台自动刷新
- ✅ 请求去重
- ✅ 乐观更新
- ✅ 离线支持

### 2. 批量选择 - 用户交互

提供完整的批量选择功能，包括状态管理和UI组件。

**主要特性**：
- ✅ 灵活的选择控制
- ✅ 全选/反选
- ✅ 选择状态持久化
- ✅ 配套操作工具栏

### 3. 虚拟滚动 - 性能优化

处理大量数据列表时使用虚拟滚动，只渲染可见项。

**主要特性**：
- ✅ 支持万级数据
- ✅ 动态高度
- ✅ 流畅滚动
- ✅ 低内存占用

---

## 常用组件

### 1. 增强型卡片 `EnhancedCard`

多功能卡片组件，支持多种变体和状态。

```tsx
import { EnhancedCard } from '@/components/ui/enhanced-card';
import { ServerCog } from 'lucide-react';

<EnhancedCard
  title="节点信息"
  subtitle="192.168.1.100"
  icon={<ServerCog />}
  variant="elevated"
  status="success"
  interactive
>
  卡片内容
</EnhancedCard>
```

**Props**：
- `variant`: `"default" | "outlined" | "elevated" | "flat" | "glass"`
- `status`: `"success" | "warning" | "danger" | "info"` (左侧边框)
- `interactive`: 可交互（悬浮效果）
- `loading`: 加载状态

### 2. 状态徽章 `StatusBadge`

显示各种状态的徽章组件。

```tsx
import { StatusBadge } from '@/components/ui/status-badge';

<StatusBadge status="online" />
<StatusBadge status="error" text="连接失败" />
<StatusBadge status="pending" pulse />
```

**状态类型**：
- `online` - 在线（绿色）
- `offline` - 离线（灰色）
- `warning` - 警告（黄色）
- `error` - 错误（红色）
- `pending` - 处理中（蓝色）
- `success` - 成功（绿色）
- `info` - 信息（蓝色）

### 3. 骨架屏 `Skeleton*`

加载时的占位符组件。

```tsx
import { SkeletonCard, SkeletonTable, SkeletonList } from '@/components/ui/skeleton';

// 卡片骨架
<SkeletonCard showAvatar lines={3} />

// 表格骨架
<SkeletonTable rows={5} columns={4} />

// 列表骨架
<SkeletonList count={10} />
```

### 4. 批量操作栏 `BatchOperationBar`

显示批量操作工具栏。

```tsx
import { BatchOperationBar } from '@/components/ui/batch-operation-bar';

<BatchOperationBar
  selectedCount={selection.selectedCount}
  onClearSelection={selection.deselectAll}
  onDelete={handleDelete}
  onEnable={handleEnable}
  onDisable={handleDisable}
  onExport={handleExport}
/>
```

### 5. 导出菜单 `ExportMenu`

数据导出功能。

```tsx
import { ExportMenu } from '@/lib/export';

<ExportMenu
  data={nodes}
  filename="nodes"
  columns={[
    { key: 'id', label: 'ID' },
    { key: 'name', label: '名称' },
    { key: 'status', label: '状态', format: (v) => v ? '在线' : '离线' },
  ]}
  formats={['csv', 'xlsx', 'json']}
/>
```

---

## 实战示例

### 示例 1: 基础列表页面

```tsx
import { useState } from 'react';
import { Input } from '@heroui/input';
import { Checkbox } from '@heroui/checkbox';
import { Search } from 'lucide-react';
import { EnhancedCard } from '@/components/ui/enhanced-card';
import { StatusBadge } from '@/components/ui/status-badge';
import { BatchOperationBar } from '@/components/ui/batch-operation-bar';
import { ExportMenu } from '@/lib/export';
import { useSimpleBatchSelection } from '@/hooks/use-batch-selection';
import { useNodes } from '@/hooks/api/use-nodes';

export default function NodesPage() {
  const [search, setSearch] = useState('');
  
  // 获取数据
  const { data: nodes = [], isLoading } = useNodes();
  
  // 批量选择
  const selection = useSimpleBatchSelection(nodes);
  
  // 搜索过滤
  const filtered = nodes.filter(node => 
    node.name.toLowerCase().includes(search.toLowerCase())
  );
  
  return (
    <div className="p-6 space-y-6">
      <h1 className="text-3xl font-bold">节点管理</h1>
      
      {/* 搜索栏 */}
      <div className="flex gap-4">
        <Input
          placeholder="搜索节点..."
          startContent={<Search className="h-4 w-4" />}
          value={search}
          onValueChange={setSearch}
          className="flex-1"
        />
        <ExportMenu data={filtered} filename="nodes" />
      </div>
      
      {/* 列表 */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {filtered.map(node => (
          <EnhancedCard
            key={node.id}
            title={node.name}
            subtitle={node.ip}
            status={node.status === 'online' ? 'success' : 'danger'}
            action={
              <Checkbox
                isSelected={selection.isSelected(node.id)}
                onValueChange={() => selection.toggle(node.id)}
              />
            }
          >
            <StatusBadge status={node.status === 'online' ? 'online' : 'offline'} />
          </EnhancedCard>
        ))}
      </div>
      
      {/* 批量操作 */}
      <BatchOperationBar
        selectedCount={selection.selectedCount}
        onClearSelection={selection.deselectAll}
        onDelete={() => console.log('删除', selection.selectedItems)}
      />
    </div>
  );
}
```

### 示例 2: 使用 React Query

```tsx
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

function MyComponent() {
  const queryClient = useQueryClient();
  
  // 查询数据
  const { data, isLoading, error } = useQuery({
    queryKey: ['nodes'],
    queryFn: () => api.get('/nodes'),
  });
  
  // 更新数据
  const updateMutation = useMutation({
    mutationFn: (node) => api.put(`/nodes/${node.id}`, node),
    onSuccess: () => {
      // 刷新列表
      queryClient.invalidateQueries({ queryKey: ['nodes'] });
    },
  });
  
  return (
    <div>
      {isLoading && <p>加载中...</p>}
      {error && <p>错误: {error.message}</p>}
      {data && <NodeList nodes={data} />}
    </div>
  );
}
```

### 示例 3: 响应式布局

```tsx
import { useBreakpoint } from '@/hooks/use-breakpoint';

function ResponsiveComponent() {
  const { isMobile, isTablet, isDesktop } = useBreakpoint();
  
  return (
    <div className={`grid gap-4 ${
      isMobile ? 'grid-cols-1' : 
      isTablet ? 'grid-cols-2' : 
      'grid-cols-3'
    }`}>
      {/* 内容 */}
    </div>
  );
}
```

### 示例 4: 虚拟滚动

```tsx
import { VirtualList } from '@/components/ui/virtual-list';

function LargeList({ items }) {
  return (
    <VirtualList
      items={items}
      height={600}
      itemHeight={80}
      renderItem={(item) => (
        <div className="p-4 border-b">
          {item.name}
        </div>
      )}
    />
  );
}
```

---

## 常见问题

### Q1: 如何手动刷新数据？

```tsx
const { refetch } = useNodes();

<Button onPress={() => refetch()}>刷新</Button>
```

### Q2: 如何处理加载状态？

```tsx
const { data, isLoading } = useNodes();

if (isLoading) {
  return <SkeletonCardGrid count={6} />;
}

return <NodeList nodes={data} />;
```

### Q3: 如何自定义导出列？

```tsx
<ExportMenu
  data={nodes}
  filename="nodes"
  columns={[
    { key: 'id', label: 'ID' },
    { key: 'name', label: '名称' },
    { 
      key: 'status', 
      label: '状态',
      format: (value) => value === 'online' ? '在线' : '离线'
    },
    {
      key: 'createdTime',
      label: '创建时间',
      format: (value) => new Date(value).toLocaleString()
    },
  ]}
/>
```

### Q4: 如何处理批量删除确认？

```tsx
import { Modal, useDisclosure } from '@heroui/modal';

function MyComponent() {
  const { isOpen, onOpen, onClose } = useDisclosure();
  const selection = useSimpleBatchSelection(items);
  
  const handleDelete = async () => {
    await deleteItems(selection.selectedItems.map(i => i.id));
    selection.reset();
    onClose();
  };
  
  return (
    <>
      <Button onPress={onOpen}>删除</Button>
      
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalContent>
          <ModalHeader>确认删除</ModalHeader>
          <ModalBody>
            确定要删除 {selection.selectedCount} 项吗？
          </ModalBody>
          <ModalFooter>
            <Button onPress={onClose}>取消</Button>
            <Button color="danger" onPress={handleDelete}>删除</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
}
```

### Q5: 如何在移动端优化？

```tsx
import { useBreakpoint } from '@/hooks/use-breakpoint';

function MyPage() {
  const { isMobile } = useBreakpoint();
  
  return (
    <div className="p-6">
      {/* 移动端使用列表，桌面端使用卡片网格 */}
      {isMobile ? (
        <div className="space-y-4">
          {items.map(item => <MobileCard key={item.id} item={item} />)}
        </div>
      ) : (
        <div className="grid grid-cols-3 gap-4">
          {items.map(item => <DesktopCard key={item.id} item={item} />)}
        </div>
      )}
    </div>
  );
}
```

### Q6: 如何配置缓存时间？

在 `src/lib/query-client.ts` 中修改：

```typescript
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000, // 5分钟（修改这里）
      gcTime: 10 * 60 * 1000,   // 10分钟
      retry: 1,
    },
  },
});
```

---

## 🎯 下一步

1. ✅ 阅读完本指南
2. 📦 安装依赖包
3. 👀 查看完整示例 (`src/pages/examples/NodesPageExample.tsx`)
4. 🔨 开始在你的页面中使用新组件
5. 📖 参考 `IMPLEMENTATION_SUMMARY.md` 了解更多细节

---

## 📚 相关文档

- [IMPROVEMENT_PLAN.md](./IMPROVEMENT_PLAN.md) - 总体改进计划
- [PERFORMANCE_OPTIMIZATION.md](./PERFORMANCE_OPTIMIZATION.md) - 性能优化详解
- [UI_IMPROVEMENT_PLAN.md](./UI_IMPROVEMENT_PLAN.md) - UI改进方案
- [NEW_FEATURES_PLAN.md](./NEW_FEATURES_PLAN.md) - 新功能规划
- [IMPLEMENTATION_SUMMARY.md](./IMPLEMENTATION_SUMMARY.md) - 实施总结

---

## 💡 提示

- 优先使用新组件替换旧组件
- 保持代码风格一致
- 添加 TypeScript 类型定义
- 编写清晰的注释
- 测试响应式布局

**祝你使用愉快！** 🎉

---

**创建日期**: 2026-10-06  
**版本**: 1.0.0
