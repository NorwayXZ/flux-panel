# 🚀 Flux Panel 新功能模块规划

## 📋 概述

本文档规划 Flux Panel 的新功能模块，旨在提升用户体验和系统功能完整性。

## 🎯 新功能列表

### 1. 批量操作功能 ✨

#### 功能描述
支持对节点、隧道、转发等资源进行批量操作，提高管理效率。

#### 具体功能
- ✅ 批量选择（多选框）
- ✅ 批量启用/禁用
- ✅ 批量删除（带确认）
- ✅ 批量更新配置
- ✅ 批量导出数据

#### 技术实现
```typescript
// 使用 Context + Reducer 管理选择状态
interface BatchSelectionState {
  selectedIds: Set<number>;
  selectAll: boolean;
  items: any[];
}

// 批量操作组件
<BatchOperationBar
  selectedCount={selected.length}
  onDelete={handleBatchDelete}
  onEnable={handleBatchEnable}
  onDisable={handleBatchDisable}
  onExport={handleBatchExport}
/>
```

#### 优先级: 🔥 高

---

### 2. 高级搜索和过滤 🔍

#### 功能描述
提供强大的搜索和过滤功能，快速定位资源。

#### 具体功能
- ✅ 多字段搜索（名称、IP、标签等）
- ✅ 高级过滤器（状态、时间范围、所有者等）
- ✅ 保存搜索条件
- ✅ 搜索历史
- ✅ 模糊搜索和正则搜索

#### 技术实现
```typescript
// 搜索过滤组件
<AdvancedFilter
  fields={[
    { key: 'name', label: '名称', type: 'text' },
    { key: 'status', label: '状态', type: 'select', options: [...] },
    { key: 'createdTime', label: '创建时间', type: 'dateRange' },
  ]}
  onFilter={handleFilter}
  savedFilters={savedFilters}
/>
```

#### 优先级: 🔥 高

---

### 3. 数据导出功能 📊

#### 功能描述
支持将数据导出为多种格式，便于分析和备份。

#### 具体功能
- ✅ 导出为 CSV
- ✅ 导出为 Excel (XLSX)
- ✅ 导出为 JSON
- ✅ 自定义导出字段
- ✅ 导出当前视图/全部数据

#### 技术实现
```typescript
// 使用 xlsx 库进行导出
import * as XLSX from 'xlsx';

function exportToExcel(data: any[], filename: string) {
  const ws = XLSX.utils.json_to_sheet(data);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Data');
  XLSX.writeFile(wb, `${filename}.xlsx`);
}

// 导出按钮组件
<ExportMenu
  formats={['csv', 'xlsx', 'json']}
  data={filteredData}
  filename="nodes-export"
/>
```

#### 优先级: 🔥 高

---

### 4. 实时通知系统 🔔

#### 功能描述
实时推送系统事件和状态变化通知。

#### 具体功能
- ✅ WebSocket 实时通知
- ✅ 通知中心（查看历史通知）
- ✅ 通知分类（系统、节点、隧道等）
- ✅ 通知优先级（普通、重要、紧急）
- ✅ 桌面通知（Notification API）
- ✅ 通知设置（开关、过滤等）

#### 技术实现
```typescript
// WebSocket 通知客户端
class NotificationService {
  private ws: WebSocket;
  
  connect() {
    this.ws = new WebSocket('ws://...');
    this.ws.onmessage = (event) => {
      const notification = JSON.parse(event.data);
      this.handleNotification(notification);
    };
  }
  
  handleNotification(notification: Notification) {
    // 显示 Toast
    toast.info(notification.message);
    
    // 桌面通知
    if (Notification.permission === 'granted') {
      new Notification(notification.title, {
        body: notification.message,
        icon: '/icon.png',
      });
    }
  }
}

// 通知中心组件
<NotificationCenter
  notifications={notifications}
  onRead={handleRead}
  onClear={handleClear}
/>
```

#### 优先级: ⚠️ 中

---

### 5. 操作历史记录 📜

#### 功能描述
记录用户的重要操作，便于审计和回溯。

#### 具体功能
- ✅ 记录所有创建/修改/删除操作
- ✅ 显示操作时间、操作人、操作类型
- ✅ 操作详情查看
- ✅ 按时间/类型/用户过滤
- ✅ 导出操作日志

#### 技术实现
```typescript
interface OperationLog {
  id: number;
  userId: number;
  userName: string;
  action: 'create' | 'update' | 'delete';
  resourceType: 'node' | 'tunnel' | 'forward';
  resourceId: number;
  resourceName: string;
  details: string;
  timestamp: number;
}

// 操作日志组件
<OperationHistory
  logs={logs}
  onFilter={handleFilter}
  onExport={handleExport}
/>
```

#### 优先级: ⚠️ 中

---

### 6. 数据可视化大屏 📈

#### 功能描述
提供数据可视化大屏，实时展示系统整体状况。

#### 具体功能
- ✅ 实时统计卡片（节点数、隧道数、流量等）
- ✅ 流量趋势图表
- ✅ 节点状态分布图
- ✅ 地理位置分布地图
- ✅ 告警信息滚动展示
- ✅ 自动刷新数据

#### 技术实现
```typescript
// 使用 Recharts 进行数据可视化
import { LineChart, PieChart, BarChart } from 'recharts';

function DashboardScreen() {
  const { data, refetch } = useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: getDashboardStats,
    refetchInterval: 30000, // 30秒刷新
  });

  return (
    <div className="grid grid-cols-4 gap-6">
      <StatCard title="在线节点" value={data.onlineNodes} />
      <StatCard title="总流量" value={data.totalTraffic} />
      
      <div className="col-span-2">
        <LineChart data={data.trafficHistory} />
      </div>
      
      <div className="col-span-2">
        <PieChart data={data.nodeDistribution} />
      </div>
    </div>
  );
}
```

#### 优先级: ⚠️ 中

---

### 7. 快捷操作面板 ⚡

#### 功能描述
提供快捷操作面板，快速访问常用功能。

#### 具体功能
- ✅ 命令面板（Cmd+K / Ctrl+K）
- ✅ 快速搜索资源
- ✅ 快速执行操作
- ✅ 最近访问记录
- ✅ 快捷键提示

#### 技术实现
```typescript
// 使用 cmdk 库实现命令面板
import { Command } from 'cmdk';

function CommandPalette() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setOpen(true);
      }
    };
    document.addEventListener('keydown', down);
    return () => document.removeEventListener('keydown', down);
  }, []);

  return (
    <Command.Dialog open={open} onOpenChange={setOpen}>
      <Command.Input placeholder="搜索或执行命令..." />
      <Command.List>
        <Command.Group heading="操作">
          <Command.Item onSelect={() => navigate('/nodes/create')}>
            创建节点
          </Command.Item>
          <Command.Item onSelect={() => navigate('/tunnels/create')}>
            创建隧道
          </Command.Item>
        </Command.Group>
      </Command.List>
    </Command.Dialog>
  );
}
```

#### 优先级: 🔥 高

---

### 8. 模板管理 📝

#### 功能描述
支持保存和使用配置模板，简化重复操作。

#### 具体功能
- ✅ 保存节点配置模板
- ✅ 保存隧道配置模板
- ✅ 从模板创建资源
- ✅ 模板分类和标签
- ✅ 模板分享（管理员）

#### 技术实现
```typescript
interface ConfigTemplate {
  id: number;
  name: string;
  type: 'node' | 'tunnel' | 'forward';
  config: Record<string, any>;
  tags: string[];
  isPublic: boolean;
  createdBy: number;
}

// 模板选择器
<TemplateSelector
  type="node"
  onSelect={(template) => {
    form.setValues(template.config);
  }}
/>
```

#### 优先级: ⚠️ 中

---

### 9. 标签和分组管理 🏷️

#### 功能描述
支持为资源添加标签和分组，便于组织管理。

#### 具体功能
- ✅ 为节点/隧道添加多个标签
- ✅ 创建自定义标签
- ✅ 标签颜色设置
- ✅ 按标签过滤
- ✅ 标签统计

#### 技术实现
```typescript
interface Tag {
  id: number;
  name: string;
  color: string;
  count: number;
}

// 标签管理器
<TagManager
  tags={tags}
  selectedTags={selectedTags}
  onTagCreate={handleCreateTag}
  onTagSelect={handleSelectTag}
/>

// 标签输入组件
<TagInput
  value={selectedTags}
  onChange={setSelectedTags}
  suggestions={availableTags}
/>
```

#### 优先级: ⚠️ 中

---

### 10. 移动端优化 📱

#### 功能描述
优化移动端用户体验，提供更好的触控操作。

#### 具体功能
- ✅ 响应式布局优化
- ✅ 触控手势支持（滑动、长按）
- ✅ 底部导航栏
- ✅ 移动端专用组件
- ✅ PWA 支持（离线访问）

#### 技术实现
```typescript
// 手势检测
import { useSwipeable } from 'react-swipeable';

function MobileCard({ onDelete }) {
  const handlers = useSwipeable({
    onSwipedLeft: () => setShowActions(true),
    onSwipedRight: () => setShowActions(false),
  });

  return (
    <div {...handlers} className="relative">
      <CardContent />
      {showActions && (
        <div className="absolute right-0 top-0 bottom-0 flex items-center">
          <Button color="danger" onPress={onDelete}>
            删除
          </Button>
        </div>
      )}
    </div>
  );
}

// PWA 配置
// public/manifest.json
{
  "name": "Flux Panel",
  "short_name": "Flux",
  "start_url": "/",
  "display": "standalone",
  "theme_color": "#38BDF8",
  "icons": [...]
}
```

#### 优先级: ⚠️ 中

---

## 📊 实施计划

### 第一阶段（立即实施）
1. ✅ 批量操作功能
2. ✅ 高级搜索和过滤
3. ✅ 数据导出功能
4. ✅ 快捷操作面板

**预计时间**: 2-3周

### 第二阶段（短期）
5. 实时通知系统
6. 操作历史记录
7. 标签和分组管理

**预计时间**: 3-4周

### 第三阶段（中期）
8. 数据可视化大屏
9. 模板管理
10. 移动端优化

**预计时间**: 4-6周

## 🎨 设计原则

1. **一致性** - 所有新功能保持与现有UI风格一致
2. **性能优先** - 使用虚拟化、懒加载等技术确保性能
3. **渐进增强** - 新功能不影响现有功能
4. **用户体验** - 提供清晰的反馈和引导
5. **可访问性** - 支持键盘操作和屏幕阅读器

## 📝 技术栈

### 新增依赖
```json
{
  "cmdk": "^1.0.0",           // 命令面板
  "xlsx": "^0.18.5",          // Excel 导出
  "react-swipeable": "^7.0.0", // 手势支持
  "recharts": "^2.12.0",      // 数据可视化
  "date-fns": "^3.0.0"        // 日期处理
}
```

## 🐛 注意事项

1. **权限控制** - 所有新功能都需要考虑权限控制
2. **数据安全** - 批量操作需要二次确认，防止误操作
3. **性能测试** - 大数据量下的性能测试
4. **兼容性** - 确保新功能在不同浏览器下正常工作
5. **文档更新** - 及时更新用户文档

## 📚 参考资源

- [Recharts 文档](https://recharts.org/)
- [CMDK 文档](https://cmdk.paco.me/)
- [PWA 指南](https://web.dev/progressive-web-apps/)
- [React Swipeable](https://github.com/FormidableLabs/react-swipeable)

---

**创建日期**: 2026-10-06  
**状态**: 规划阶段  
**负责人**: 待定
