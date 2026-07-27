import { Button } from '@heroui/button';
import { Card, CardBody } from '@heroui/card';
import { Input, Textarea } from '@heroui/input';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import {
  AlertTriangle,
  ArrowRightLeft,
  BellRing,
  CheckCheck,
  CircleCheck,
  CloudCog,
  Clock3,
  Network,
  RefreshCw,
  Save,
  Search,
  Send,
  Server,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import {
  Area,
  Bar,
  CartesianGrid,
  ComposedChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import {
  getMonitoringAlerts,
  getMonitoringOverview,
  getTelegramNotificationSettings,
  markAllMonitoringAlertsRead,
  markMonitoringAlertsRead,
  saveTelegramNotificationSettings,
  testTelegramNotification,
  type MonitoringAlertItem,
  type MonitoringAlertPage,
  type MonitoringOverview,
  type MonitoringRange,
  type MonitoringResource,
  type MonitoringResourceType,
  type MonitoringStatus,
  type TelegramNotificationSettings,
} from '@/api';
import { notifyAlertCountChanged } from '@/hooks/use-alert-unread-count';

const EMPTY_OVERVIEW: MonitoringOverview = {
  range: '24h',
  summary: {
    totalResources: 0,
    healthy: 0,
    degraded: 0,
    offline: 0,
    paused: 0,
    unknown: 0,
    openAlerts: 0,
    criticalAlerts: 0,
    unreadAlerts: 0,
    availability: 100,
    trackedFrom: 0,
  },
  trend: [],
  resources: [],
};

const EMPTY_ALERTS: MonitoringAlertPage = {
  items: [],
  total: 0,
  page: 1,
  size: 20,
  unread: 0,
};

const EMPTY_NOTIFICATION_SETTINGS: TelegramNotificationSettings = {
  enabled: false,
  botToken: '',
  botTokenConfigured: false,
  chatId: '',
  nodeEnabled: true,
  nodeRepeatLimit: 1,
  tunnelEnabled: true,
  tunnelRepeatLimit: 1,
  forwardEnabled: true,
  forwardRepeatLimit: 1,
  recoveryEnabled: true,
  assetExpiryEnabled: true,
  dynamicDnsEnabled: true,
  loginOutsideWhitelistEnabled: false,
  loginAllowedCidrs: '',
  repeatIntervalMinutes: 30,
};

const RANGE_LABELS: Record<MonitoringRange, string> = {
  '24h': '24 小时',
  '7d': '7 天',
  '30d': '30 天',
};

const RESOURCE_LABELS: Record<MonitoringResourceType, string> = {
  node: '节点',
  tunnel: '隧道',
  forward: '转发',
  certificate: '证书',
  dynamic_dns: '动态 DNS',
};

const STATUS_LABELS: Record<MonitoringStatus, string> = {
  healthy: '正常',
  degraded: '性能下降',
  offline: '异常',
  paused: '已暂停',
  unknown: '未知',
};

const statusStyles: Record<MonitoringStatus, string> = {
  healthy: 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-500/25 dark:bg-emerald-500/10 dark:text-emerald-300',
  degraded: 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-500/25 dark:bg-amber-500/10 dark:text-amber-300',
  offline: 'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-500/25 dark:bg-rose-500/10 dark:text-rose-300',
  paused: 'border-gray-200 bg-gray-100 text-gray-600 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300',
  unknown: 'border-gray-200 bg-gray-50 text-gray-500 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-400',
};

const resourceIcon = (type: MonitoringResourceType, className = 'h-4 w-4') => {
  if (type === 'node') return <Server className={className} />;
  if (type === 'tunnel') return <Network className={className} />;
  if (type === 'certificate' || type === 'dynamic_dns') return <CloudCog className={className} />;
  return <ArrowRightLeft className={className} />;
};

const formatDateTime = (value?: number) => {
  if (!value) return '-';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value));
};

const formatTrendTime = (value: number, range: MonitoringRange) => {
  const date = new Date(value);
  if (range === '24h') {
    return `${date.getHours().toString().padStart(2, '0')}:00`;
  }
  return `${date.getMonth() + 1}/${date.getDate()}`;
};

const formatDuration = (startedAt: number, endedAt?: number) => {
  const duration = Math.max(0, (endedAt || Date.now()) - startedAt);
  const minutes = Math.floor(duration / 60000);
  if (minutes < 1) return '不足 1 分钟';
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分钟`;
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`;
};

export default function MonitoringPage() {
  const [range, setRange] = useState<MonitoringRange>('24h');
  const [view, setView] = useState<'alerts' | 'resources' | 'notifications'>('alerts');
  const [overview, setOverview] = useState<MonitoringOverview>(EMPTY_OVERVIEW);
  const [alerts, setAlerts] = useState<MonitoringAlertPage>(EMPTY_ALERTS);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [statusFilter, setStatusFilter] = useState<'all' | 'open' | 'resolved'>('all');
  const [typeFilter, setTypeFilter] = useState<'all' | MonitoringResourceType>('all');
  const [severityFilter, setSeverityFilter] = useState<'all' | 'critical' | 'warning'>('all');
  const [keyword, setKeyword] = useState('');
  const [searchKeyword, setSearchKeyword] = useState('');
  const [page, setPage] = useState(1);
  const isAdmin = localStorage.getItem('admin') === 'true';

  const loadOverview = useCallback(async (selectedRange: MonitoringRange, silent = false) => {
    if (!silent) setLoading(true);
    try {
      const response = await getMonitoringOverview(selectedRange);
      if (response.code !== 0) throw new Error(response.msg || '获取监控概览失败');
      setOverview(response.data || EMPTY_OVERVIEW);
    } catch (error) {
      if (!silent) toast.error(error instanceof Error ? error.message : '获取监控概览失败');
    } finally {
      if (!silent) setLoading(false);
    }
  }, []);

  const loadAlerts = useCallback(async (silent = false) => {
    try {
      const response = await getMonitoringAlerts({
        status: statusFilter,
        resourceType: typeFilter,
        severity: severityFilter,
        keyword: searchKeyword,
        page,
        size: 20,
      });
      if (response.code !== 0) throw new Error(response.msg || '获取告警列表失败');
      setAlerts(response.data || EMPTY_ALERTS);
      notifyAlertCountChanged();
    } catch (error) {
      if (!silent) toast.error(error instanceof Error ? error.message : '获取告警列表失败');
    }
  }, [page, searchKeyword, severityFilter, statusFilter, typeFilter]);

  const refreshAll = useCallback(async (silent = false) => {
    if (!silent) setRefreshing(true);
    await Promise.all([loadOverview(range, true), loadAlerts(true)]);
    if (!silent) setRefreshing(false);
  }, [loadAlerts, loadOverview, range]);

  useEffect(() => {
    localStorage.setItem('e', '/monitoring');
    loadOverview(range);
  }, [loadOverview, range]);

  useEffect(() => {
    loadAlerts();
  }, [loadAlerts]);

  useEffect(() => {
    const timer = window.setInterval(() => refreshAll(true), 30000);
    return () => window.clearInterval(timer);
  }, [refreshAll]);

  const chartData = useMemo(() => overview.trend.map(point => ({
    ...point,
    label: formatTrendTime(point.time, range),
  })), [overview.trend, range]);

  const handleMarkRead = async (alert: MonitoringAlertItem) => {
    if (alert.read) return;
    const response = await markMonitoringAlertsRead([alert.id]);
    if (response.code === 0) {
      setAlerts(current => ({
        ...current,
        unread: Math.max(0, current.unread - 1),
        items: current.items.map(item => item.id === alert.id ? { ...item, read: true } : item),
      }));
      notifyAlertCountChanged();
    }
  };

  const handleMarkAllRead = async () => {
    const response = await markAllMonitoringAlertsRead();
    if (response.code !== 0) {
      toast.error(response.msg || '标记失败');
      return;
    }
    setAlerts(current => ({
      ...current,
      unread: 0,
      items: current.items.map(item => ({ ...item, read: true })),
    }));
    setOverview(current => ({
      ...current,
      summary: { ...current.summary, unreadAlerts: 0 },
    }));
    notifyAlertCountChanged();
    toast.success('全部告警已标记为已读');
  };

  const applySearch = () => {
    setPage(1);
    setSearchKeyword(keyword.trim());
  };

  const summaryCards = [
    {
      label: '待处理告警',
      value: overview.summary.openAlerts,
      meta: `${overview.summary.unreadAlerts} 条未读`,
      icon: <BellRing className="h-5 w-5" />,
      tone: 'text-amber-700 bg-amber-50 dark:text-amber-300 dark:bg-amber-500/10',
    },
    {
      label: '严重告警',
      value: overview.summary.criticalAlerts,
      meta: overview.summary.criticalAlerts > 0 ? '需要立即处理' : '当前无严重故障',
      icon: <AlertTriangle className="h-5 w-5" />,
      tone: 'text-rose-700 bg-rose-50 dark:text-rose-300 dark:bg-rose-500/10',
    },
    {
      label: '健康资源',
      value: `${overview.summary.healthy}/${overview.summary.totalResources}`,
      meta: `${overview.summary.degraded} 个性能下降`,
      icon: <CircleCheck className="h-5 w-5" />,
      tone: 'text-emerald-700 bg-emerald-50 dark:text-emerald-300 dark:bg-emerald-500/10',
    },
    {
      label: '综合在线率',
      value: `${overview.summary.availability.toFixed(2)}%`,
      meta: RANGE_LABELS[range],
      icon: <Clock3 className="h-5 w-5" />,
      tone: 'text-blue-700 bg-blue-50 dark:text-blue-300 dark:bg-blue-500/10',
    },
  ];

  return (
    <div className="min-h-full bg-gray-100 px-3 py-4 dark:bg-black sm:px-5 lg:px-7 lg:py-6">
      <div className="mx-auto max-w-[1500px] space-y-5">
        <header className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="text-sm font-medium text-default-500">运行状态</p>
            <h1 className="mt-1 text-2xl font-semibold text-foreground">告警中心</h1>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <div className="inline-flex rounded-lg border border-gray-200 bg-white p-1 dark:border-gray-700 dark:bg-gray-950">
              {(Object.keys(RANGE_LABELS) as MonitoringRange[]).map(item => (
                <button
                  key={item}
                  type="button"
                  onClick={() => setRange(item)}
                  className={`min-h-9 rounded-md px-3 text-sm font-medium transition-colors ${
                    range === item
                      ? 'bg-gray-900 text-white dark:bg-white dark:text-black'
                      : 'text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-900'
                  }`}
                >
                  {RANGE_LABELS[item]}
                </button>
              ))}
            </div>
            <Button
              isIconOnly
              aria-label="刷新监控数据"
              title="刷新监控数据"
              variant="flat"
              onPress={() => refreshAll()}
              isLoading={refreshing}
            >
              {!refreshing && <RefreshCw className="h-4 w-4" />}
            </Button>
          </div>
        </header>

        <section className="grid grid-cols-2 gap-3 lg:grid-cols-4" aria-label="监控摘要">
          {summaryCards.map(card => (
            <Card key={card.label} radius="sm" shadow="sm" className="border border-gray-200 dark:border-gray-800 dark:bg-gray-950">
              <CardBody className="gap-3 p-4 sm:p-5">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-sm font-medium text-default-500">{card.label}</span>
                  <span className={`flex h-9 w-9 items-center justify-center rounded-lg ${card.tone}`}>{card.icon}</span>
                </div>
                <div>
                  <p className="text-2xl font-semibold text-foreground sm:text-3xl">{loading ? '-' : card.value}</p>
                  <p className="mt-1 truncate text-xs text-default-500 sm:text-sm">{card.meta}</p>
                </div>
              </CardBody>
            </Card>
          ))}
        </section>

        <section className="border-y border-gray-200 bg-white px-3 py-5 dark:border-gray-800 dark:bg-gray-950 sm:px-5" aria-label="在线率趋势">
          <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 className="text-base font-semibold text-foreground">在线率趋势</h2>
              <p className="mt-1 text-sm text-default-500">
                {overview.summary.trackedFrom ? `数据起始 ${formatDateTime(overview.summary.trackedFrom)}` : '正在建立历史基线'}
              </p>
            </div>
            <div className="flex items-center gap-4 text-xs text-default-500">
              <span className="flex items-center gap-1.5"><i className="h-2.5 w-2.5 rounded-sm bg-blue-500" />在线率</span>
              <span className="flex items-center gap-1.5"><i className="h-2.5 w-2.5 rounded-sm bg-amber-400" />新故障</span>
            </div>
          </div>
          <div className="h-64 w-full sm:h-72">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={chartData} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                <defs>
                  <linearGradient id="availabilityFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.24} />
                    <stop offset="100%" stopColor="#3b82f6" stopOpacity={0.02} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="rgba(148,163,184,.22)" />
                <XAxis dataKey="label" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} minTickGap={20} />
                <YAxis yAxisId="availability" domain={[0, 100]} tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                <YAxis yAxisId="incidents" orientation="right" allowDecimals={false} hide />
                <Tooltip
                  contentStyle={{ borderRadius: 6, borderColor: 'rgba(148,163,184,.35)', fontSize: 12 }}
                  formatter={(value, name) => {
                    const numericValue = Number(value ?? 0);
                    const label = String(name);
                    return label === '在线率'
                      ? [`${numericValue.toFixed(2)}%`, label]
                      : [numericValue, label];
                  }}
                />
                <Area yAxisId="availability" type="monotone" dataKey="availability" name="在线率" stroke="#3b82f6" strokeWidth={2} fill="url(#availabilityFill)" />
                <Bar yAxisId="incidents" dataKey="incidents" name="新故障" fill="#fbbf24" radius={[3, 3, 0, 0]} maxBarSize={10} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        </section>

        <section className="space-y-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="inline-flex w-fit rounded-lg bg-gray-200/70 p-1 dark:bg-gray-900">
              <button
                type="button"
                onClick={() => setView('alerts')}
                className={`min-h-9 rounded-md px-4 text-sm font-medium outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${view === 'alerts' ? 'bg-white shadow-sm dark:bg-gray-800' : 'text-default-500'}`}
              >
                告警事件 {overview.summary.openAlerts > 0 && `(${overview.summary.openAlerts})`}
              </button>
              <button
                type="button"
                onClick={() => setView('resources')}
                className={`min-h-9 rounded-md px-4 text-sm font-medium outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${view === 'resources' ? 'bg-white shadow-sm dark:bg-gray-800' : 'text-default-500'}`}
              >
                资源状态 ({overview.summary.totalResources})
              </button>
              {isAdmin && (
                <button
                  type="button"
                  onClick={() => setView('notifications')}
                  className={`min-h-9 rounded-md px-4 text-sm font-medium outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${view === 'notifications' ? 'bg-white shadow-sm dark:bg-gray-800' : 'text-default-500'}`}
                >
                  通知设置
                </button>
              )}
            </div>
            {view === 'alerts' && alerts.unread > 0 && (
              <Button size="sm" variant="flat" startContent={<CheckCheck className="h-4 w-4" />} onPress={handleMarkAllRead}>
                全部已读
              </Button>
            )}
          </div>

          {view === 'notifications' && isAdmin ? (
            <NotificationSettings />
          ) : view === 'alerts' ? (
            <AlertList
              alerts={alerts}
              statusFilter={statusFilter}
              typeFilter={typeFilter}
              severityFilter={severityFilter}
              keyword={keyword}
              isAdmin={isAdmin}
              onStatusChange={value => { setPage(1); setStatusFilter(value); }}
              onTypeChange={value => { setPage(1); setTypeFilter(value); }}
              onSeverityChange={value => { setPage(1); setSeverityFilter(value); }}
              onKeywordChange={setKeyword}
              onSearch={applySearch}
              onRead={handleMarkRead}
              onPageChange={setPage}
            />
          ) : (
            <ResourceList resources={overview.resources} isAdmin={isAdmin} range={range} />
          )}
        </section>
      </div>
    </div>
  );
}

function NotificationSettings() {
  const [settings, setSettings] = useState<TelegramNotificationSettings>(EMPTY_NOTIFICATION_SETTINGS);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);

  const loadSettings = useCallback(async () => {
    setLoading(true);
    const response = await getTelegramNotificationSettings();
    if (response.code === 0 && response.data) setSettings({ ...EMPTY_NOTIFICATION_SETTINGS, ...response.data, botToken: '' });
    else toast.error(response.msg || '获取通知设置失败');
    setLoading(false);
  }, []);

  useEffect(() => { loadSettings(); }, [loadSettings]);

  const save = async (showSuccess = true) => {
    setSaving(true);
    const response = await saveTelegramNotificationSettings(settings);
    setSaving(false);
    if (response.code !== 0) {
      toast.error(response.msg || '保存通知设置失败');
      return false;
    }
    setSettings({ ...response.data, botToken: '' });
    if (showSuccess) toast.success('通知设置已保存');
    return true;
  };

  const test = async () => {
    setTesting(true);
    const saved = await save(false);
    if (!saved) {
      setTesting(false);
      return;
    }
    const response = await testTelegramNotification();
    setTesting(false);
    response.code === 0 ? toast.success('测试通知已发送') : toast.error(response.msg || '测试通知发送失败');
  };

  const update = <K extends keyof TelegramNotificationSettings>(key: K, value: TelegramNotificationSettings[K]) => {
    setSettings(current => ({ ...current, [key]: value }));
  };

  if (loading) return <div className="flex min-h-64 items-center justify-center"><Spinner /></div>;

  const eventRows: Array<{
    label: string;
    detail: string;
    enabledKey: 'nodeEnabled' | 'tunnelEnabled' | 'forwardEnabled';
    repeatKey: 'nodeRepeatLimit' | 'tunnelRepeatLimit' | 'forwardRepeatLimit';
  }> = [
    { label: '节点异常', detail: 'Agent 离线或恢复', enabledKey: 'nodeEnabled', repeatKey: 'nodeRepeatLimit' },
    { label: '隧道异常', detail: '链路离线、降级或恢复', enabledKey: 'tunnelEnabled', repeatKey: 'tunnelRepeatLimit' },
    { label: '转发异常', detail: '线路或目标不可用、恢复', enabledKey: 'forwardEnabled', repeatKey: 'forwardRepeatLimit' },
  ];

  return (
    <div className="overflow-hidden border-y border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-950">
      <div className="flex flex-col gap-4 border-b border-divider px-4 py-5 sm:flex-row sm:items-center sm:justify-between sm:px-5">
        <div>
          <h2 className="font-semibold text-foreground">Telegram 通知</h2>
          <p className="mt-1 text-sm text-default-500">按事件独立控制，重复通知受次数和冷却时间约束。</p>
        </div>
        <Switch isSelected={settings.enabled} onValueChange={value => update('enabled', value)}>
          {settings.enabled ? '已启用' : '未启用'}
        </Switch>
      </div>

      <div className="grid gap-4 border-b border-divider px-4 py-5 sm:px-5 lg:grid-cols-2">
        <Input
          type="password"
          label="Bot Token"
          placeholder={settings.botTokenConfigured ? '已保存，留空保持不变' : '123456:ABC...'}
          value={settings.botToken}
          onValueChange={value => update('botToken', value)}
          autoComplete="off"
        />
        <Input label="Chat ID" placeholder="-1001234567890" value={settings.chatId} onValueChange={value => update('chatId', value)} />
      </div>

      <div className="border-b border-divider px-4 py-5 sm:px-5">
        <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h3 className="font-medium text-foreground">资源事件</h3>
            <p className="mt-1 text-sm text-default-500">同一故障按设定次数发送，具体对象单独计数。</p>
          </div>
          <Switch size="sm" isSelected={settings.recoveryEnabled} onValueChange={value => update('recoveryEnabled', value)}>恢复通知</Switch>
        </div>
        <div className="divide-y divide-divider border-y border-divider">
          {eventRows.map(row => (
            <div key={row.label} className="grid gap-3 py-4 sm:grid-cols-[minmax(0,1fr)_auto_auto] sm:items-center">
              <div className="min-w-0">
                <p className="font-medium text-foreground">{row.label}</p>
                <p className="mt-1 text-sm text-default-500">{row.detail}</p>
              </div>
              <Switch size="sm" isSelected={settings[row.enabledKey] as boolean} onValueChange={value => update(row.enabledKey, value)}>
                {settings[row.enabledKey] ? '通知' : '关闭'}
              </Switch>
              <label className="flex items-center gap-2 text-sm text-default-500">
                最多
                <select
                  className="h-9 rounded-md border border-divider bg-background px-3 text-foreground outline-none focus:border-primary"
                  value={settings[row.repeatKey] as number}
                  onChange={event => update(row.repeatKey, Number(event.target.value))}
                  disabled={!settings[row.enabledKey]}
                  aria-label={`${row.label}发送次数`}
                >
                  {[1, 2, 3, 4, 5].map(value => <option key={value} value={value}>{value} 次</option>)}
                </select>
              </label>
            </div>
          ))}
          <div className="grid gap-3 py-4 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center">
            <div><p className="font-medium text-foreground">动态 DNS</p><p className="mt-1 text-sm text-default-500">更新失败和恢复，每次故障只通知一次</p></div>
            <Switch size="sm" isSelected={settings.dynamicDnsEnabled} onValueChange={value => update('dynamicDnsEnabled', value)}>{settings.dynamicDnsEnabled ? '通知' : '关闭'}</Switch>
          </div>
          <div className="grid gap-3 py-4 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center">
            <div><p className="font-medium text-foreground">服务器到期</p><p className="mt-1 text-sm text-default-500">按资产中心设置的提前天数提醒</p></div>
            <Switch size="sm" isSelected={settings.assetExpiryEnabled} onValueChange={value => update('assetExpiryEnabled', value)}>{settings.assetExpiryEnabled ? '通知' : '关闭'}</Switch>
          </div>
        </div>
        <div className="mt-4 max-w-sm">
          <Input
            type="number"
            min={5}
            max={1440}
            label="重复通知冷却（分钟）"
            value={String(settings.repeatIntervalMinutes)}
            onValueChange={value => update('repeatIntervalMinutes', Math.max(5, Math.min(1440, Number(value) || 5)))}
          />
        </div>
      </div>

      <div className="grid gap-4 border-b border-divider px-4 py-5 sm:px-5 lg:grid-cols-[minmax(0,1fr)_minmax(320px,1fr)]">
        <div>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h3 className="font-medium text-foreground">白名单外登录</h3>
              <p className="mt-1 text-sm text-default-500">登录成功后检查来源地址，同账号同地址在冷却期内只发一次。</p>
            </div>
            <Switch size="sm" isSelected={settings.loginOutsideWhitelistEnabled} onValueChange={value => update('loginOutsideWhitelistEnabled', value)}>
              {settings.loginOutsideWhitelistEnabled ? '通知' : '关闭'}
            </Switch>
          </div>
        </div>
        <Textarea
          label="允许登录的 IP / CIDR"
          placeholder={'198.51.100.20\n203.0.113.0/24'}
          minRows={3}
          value={settings.loginAllowedCidrs}
          onValueChange={value => update('loginAllowedCidrs', value)}
          isDisabled={!settings.loginOutsideWhitelistEnabled}
        />
      </div>

      <div className="flex flex-col-reverse gap-2 px-4 py-4 sm:flex-row sm:justify-end sm:px-5">
        <Button variant="flat" startContent={<Send className="h-4 w-4" />} isLoading={testing} onPress={test}>保存并测试</Button>
        <Button color="primary" startContent={<Save className="h-4 w-4" />} isLoading={saving && !testing} onPress={() => save()}>保存设置</Button>
      </div>
    </div>
  );
}

interface AlertListProps {
  alerts: MonitoringAlertPage;
  statusFilter: 'all' | 'open' | 'resolved';
  typeFilter: 'all' | MonitoringResourceType;
  severityFilter: 'all' | 'critical' | 'warning';
  keyword: string;
  isAdmin: boolean;
  onStatusChange: (value: 'all' | 'open' | 'resolved') => void;
  onTypeChange: (value: 'all' | MonitoringResourceType) => void;
  onSeverityChange: (value: 'all' | 'critical' | 'warning') => void;
  onKeywordChange: (value: string) => void;
  onSearch: () => void;
  onRead: (alert: MonitoringAlertItem) => void;
  onPageChange: (page: number) => void;
}

function AlertList(props: AlertListProps) {
  const totalPages = Math.max(1, Math.ceil(props.alerts.total / props.alerts.size));
  const controlClass = 'h-10 rounded-md border border-gray-200 bg-white px-3 text-sm text-foreground outline-none focus:border-blue-500 dark:border-gray-700 dark:bg-gray-950';

  return (
    <div className="space-y-3">
      <div className="flex flex-col gap-2 lg:flex-row">
        <div className="grid grid-cols-3 gap-2 lg:flex">
          <select value={props.statusFilter} onChange={event => props.onStatusChange(event.target.value as AlertListProps['statusFilter'])} className={controlClass} aria-label="告警状态">
            <option value="all">全部状态</option>
            <option value="open">待处理</option>
            <option value="resolved">已恢复</option>
          </select>
          <select value={props.typeFilter} onChange={event => props.onTypeChange(event.target.value as AlertListProps['typeFilter'])} className={controlClass} aria-label="资源类型">
            <option value="all">全部资源</option>
            <option value="node">节点</option>
            <option value="tunnel">隧道</option>
            <option value="forward">转发</option>
            <option value="certificate">证书</option>
            <option value="dynamic_dns">动态 DNS</option>
          </select>
          <select value={props.severityFilter} onChange={event => props.onSeverityChange(event.target.value as AlertListProps['severityFilter'])} className={controlClass} aria-label="严重程度">
            <option value="all">全部级别</option>
            <option value="critical">严重</option>
            <option value="warning">警告</option>
          </select>
        </div>
        <form className="relative min-w-0 flex-1" onSubmit={event => { event.preventDefault(); props.onSearch(); }}>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-default-400" />
          <input
            value={props.keyword}
            onChange={event => props.onKeywordChange(event.target.value)}
            className={`${controlClass} w-full pl-9 pr-20`}
            placeholder="搜索资源或告警"
          />
          <button type="submit" className="absolute right-1 top-1 h-8 rounded px-3 text-sm font-medium text-blue-600 hover:bg-blue-50 dark:text-blue-300 dark:hover:bg-blue-500/10">搜索</button>
        </form>
      </div>

      <div className="overflow-hidden border-y border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-950 sm:rounded-md sm:border">
        {props.alerts.items.length === 0 ? (
          <div className="flex min-h-48 flex-col items-center justify-center px-4 text-center text-default-500">
            <CircleCheck className="mb-3 h-8 w-8 text-emerald-500" />
            <p className="font-medium text-foreground">当前筛选条件下没有告警</p>
          </div>
        ) : props.alerts.items.map((alert, index) => (
          <button
            key={alert.id}
            type="button"
            onClick={() => props.onRead(alert)}
            className={`grid w-full gap-3 px-4 py-4 text-left transition-colors hover:bg-gray-50 dark:hover:bg-gray-900/70 sm:grid-cols-[auto_minmax(0,1fr)_auto] sm:items-center ${
              index > 0 ? 'border-t border-gray-100 dark:border-gray-800' : ''
            } ${!alert.read ? 'bg-blue-50/45 dark:bg-blue-500/5' : ''}`}
          >
            <span className={`mt-0.5 flex h-9 w-9 items-center justify-center rounded-md sm:mt-0 ${
              alert.severity === 'critical'
                ? 'bg-rose-100 text-rose-700 dark:bg-rose-500/15 dark:text-rose-300'
                : 'bg-amber-100 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300'
            }`}>
              {resourceIcon(alert.resourceType, 'h-4 w-4')}
            </span>
            <span className="min-w-0">
              <span className="flex flex-wrap items-center gap-2">
                <strong className="truncate text-sm font-semibold text-foreground">{alert.title}</strong>
                {!alert.read && <i className="h-2 w-2 rounded-full bg-blue-500" title="未读" />}
                <span className={`rounded border px-1.5 py-0.5 text-[11px] font-medium ${
                  alert.status === 'open'
                    ? 'border-rose-200 text-rose-700 dark:border-rose-500/30 dark:text-rose-300'
                    : 'border-emerald-200 text-emerald-700 dark:border-emerald-500/30 dark:text-emerald-300'
                }`}>{alert.status === 'open' ? '待处理' : '已恢复'}</span>
              </span>
              <span className="mt-1 block text-sm text-default-500">{alert.detail || '未提供故障详情'}</span>
              <span className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-xs text-default-400">
                <span>{RESOURCE_LABELS[alert.resourceType]} ID {alert.resourceId}</span>
                {props.isAdmin && <span>所属：{alert.ownerUserName}</span>}
                <span>持续：{formatDuration(alert.startedAt, alert.resolvedAt)}</span>
              </span>
            </span>
            <span className="text-xs text-default-400 sm:text-right">
              <span className="block">{formatDateTime(alert.startedAt)}</span>
              {alert.resolvedAt && <span className="mt-1 block text-emerald-600 dark:text-emerald-400">恢复 {formatDateTime(alert.resolvedAt)}</span>}
            </span>
          </button>
        ))}
      </div>

      {props.alerts.total > props.alerts.size && (
        <div className="flex items-center justify-between text-sm text-default-500">
          <span>共 {props.alerts.total} 条</span>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="flat" isDisabled={props.alerts.page <= 1} onPress={() => props.onPageChange(props.alerts.page - 1)}>上一页</Button>
            <span>{props.alerts.page}/{totalPages}</span>
            <Button size="sm" variant="flat" isDisabled={props.alerts.page >= totalPages} onPress={() => props.onPageChange(props.alerts.page + 1)}>下一页</Button>
          </div>
        </div>
      )}
    </div>
  );
}

function ResourceList({ resources, isAdmin, range }: { resources: MonitoringResource[]; isAdmin: boolean; range: MonitoringRange }) {
  return (
    <div className="overflow-hidden border-y border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-950 sm:rounded-md sm:border">
      <div className="hidden grid-cols-[minmax(180px,1.4fr)_100px_minmax(170px,1fr)_110px_90px] gap-4 border-b border-gray-200 px-4 py-3 text-xs font-semibold text-default-500 dark:border-gray-800 lg:grid">
        <span>资源</span>
        <span>状态</span>
        <span>说明</span>
        <span>{RANGE_LABELS[range]}在线率</span>
        <span>故障次数</span>
      </div>
      {resources.length === 0 ? (
        <div className="flex min-h-48 items-center justify-center text-default-500">监控基线正在建立</div>
      ) : resources.map((resource, index) => (
        <div
          key={`${resource.type}-${resource.id}`}
          className={`grid gap-3 px-4 py-4 lg:grid-cols-[minmax(180px,1.4fr)_100px_minmax(170px,1fr)_110px_90px] lg:items-center lg:gap-4 ${
            index > 0 ? 'border-t border-gray-100 dark:border-gray-800' : ''
          }`}
        >
          <div className="flex min-w-0 items-center gap-3">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-gray-100 text-gray-600 dark:bg-gray-900 dark:text-gray-300">
              {resourceIcon(resource.type)}
            </span>
            <span className="min-w-0">
              <strong className="block truncate text-sm text-foreground">{resource.name}</strong>
              <span className="mt-0.5 block truncate text-xs text-default-400">
                {RESOURCE_LABELS[resource.type]} ID {resource.id}{isAdmin ? ` · ${resource.ownerUserName}` : ''}
              </span>
            </span>
          </div>
          <div>
            <span className={`inline-flex rounded border px-2 py-1 text-xs font-medium ${statusStyles[resource.status]}`}>
              {STATUS_LABELS[resource.status]}
            </span>
          </div>
          <div className="min-w-0">
            <p className="truncate text-sm text-foreground">{resource.detail}</p>
            <p className="mt-0.5 text-xs text-default-400">状态变化 {formatDateTime(resource.changedAt)}</p>
          </div>
          <div>
            <p className="text-sm font-semibold text-foreground">{resource.availability.toFixed(2)}%</p>
            <div className="mt-1.5 h-1.5 overflow-hidden rounded bg-gray-100 dark:bg-gray-800">
              <div
                className={`h-full rounded ${resource.availability >= 99 ? 'bg-emerald-500' : resource.availability >= 95 ? 'bg-amber-400' : 'bg-rose-500'}`}
                style={{ width: `${Math.max(0, Math.min(resource.availability, 100))}%` }}
              />
            </div>
          </div>
          <div className="flex items-center justify-between lg:block">
            <span className="text-xs text-default-400 lg:hidden">故障次数</span>
            <span className="text-sm font-medium text-foreground">{resource.incidentCount}</span>
          </div>
        </div>
      ))}
    </div>
  );
}
