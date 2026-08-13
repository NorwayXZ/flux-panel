import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { Tab, Tabs } from '@heroui/tabs';
import { Activity, BookOpen, Copy, Home, Laptop, Plus, RefreshCw, Route, ShieldAlert, Terminal, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';
import { useNavigate } from 'react-router-dom';

import {
  createHomeProxyRoute,
  deleteHomeProxyRoute,
  getDynamicDnsOverview,
  getHomeProxyRoutes,
  getHomeProxyNatEvents,
  getInternalConnectors,
  getNodeList,
  getPublishingPortPools,
  getTunnelList,
  refreshHomeProxyIpv6,
  retryHomeProxyNat,
  type HomeProxyNatEvent,
  type HomeProxyRoute,
  type InternalConnector,
  type PublishingPortPool,
  type DynamicDnsRule,
} from '@/api';
import { isAdmin } from '@/utils/auth';

const stateMeta: Record<HomeProxyRoute['state'], { label: string; color: 'success' | 'warning' | 'danger' | 'default' }> = {
  provisioning: { label: '配置中', color: 'warning' },
  active: { label: '运行中', color: 'success' },
  error: { label: '配置失败', color: 'danger' },
  delete_pending: { label: '等待清理', color: 'warning' },
  deleted: { label: '已删除', color: 'default' },
};

type FormState = {
  name: string; connectorId: string; sourceConnectorId: string; accessMode: 'relay' | 'ipv6_direct' | 'ipv4_direct' | 'smart_nat';
  ingressPoolKey: string; egressNodeId: string; egressMode: 'single' | 'tunnel'; egressTunnelId: string;
  transportMode: 'socks5' | 'vless_reality'; realityServerName: string;
  directPort: string; sourceListenPort: string; dynamicDnsRuleId: string;
  authEnabled: boolean; authUsername: string; authPassword: string;
};

type NodeOption = {
  id: number; name: string; serverIp?: string; ip?: string; version?: string; status: number;
  accessType?: 'admin' | 'owned' | 'shared'; ownerUserName?: string; quotaAvailable?: boolean; unavailableReason?: string;
  portSta?: number; portEnd?: number;
};

type TunnelOption = {
  id: number; name: string; type: number; status: number; nodePath?: string;
  quotaAvailable?: boolean; unavailableReason?: string;
  pathNodeDetails?: Array<{ nodeId: number; name: string; status: number }>;
};

const emptyForm = (): FormState => ({
  name: '', connectorId: '', sourceConnectorId: '', accessMode: 'ipv6_direct', ingressPoolKey: '', egressNodeId: '',
  egressMode: 'single', egressTunnelId: '', directPort: '23888',
  transportMode: 'vless_reality', realityServerName: 'www.cloudflare.com',
  sourceListenPort: '23888', dynamicDnsRuleId: '', authEnabled: false, authUsername: '', authPassword: '',
});

const poolKey = (pool: PublishingPortPool) => `${pool.id}:${pool.grantId || 0}`;
const selectedPool = (pools: PublishingPortPool[], key: string) => pools.find(pool => poolKey(pool) === key);
const endpointText = (route: HomeProxyRoute) => {
  if (route.accessMode === 'smart_nat') return route.clientEndpoint || '等待公司设备 Agent 启动本地入口';
  if (!route.publicHost || !route.publicPort) return '等待生成访问地址';
  const host = route.publicHost.includes(':') && !route.publicHost.startsWith('[') ? `[${route.publicHost}]` : route.publicHost;
  return `${host}:${route.publicPort}`;
};
const isDirect = (mode: HomeProxyRoute['accessMode'] | FormState['accessMode']) => mode === 'ipv6_direct' || mode === 'ipv4_direct';
const isIpv6Direct = (mode: HomeProxyRoute['accessMode'] | FormState['accessMode']) => mode === 'ipv6_direct';
const accessLabel = (mode: HomeProxyRoute['accessMode'] | FormState['accessMode']) =>
  mode === 'smart_nat' ? '智能直连' : mode === 'ipv4_direct' ? 'IPv4 直连' : mode === 'ipv6_direct' ? 'IPv6 直连' : '公网中继';

const formatBytes = (value = 0) => {
  if (value < 1024) return `${value} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let amount = value / 1024;
  let index = 0;
  while (amount >= 1024 && index < units.length - 1) { amount /= 1024; index += 1; }
  return `${amount.toFixed(amount >= 100 ? 0 : amount >= 10 ? 1 : 2)} ${units[index]}`;
};

const natPathLabel = (route: HomeProxyRoute) => route.natState === 'probing'
  ? '探测中' : route.activeAccessPath === 'udp_direct' ? 'UDP 直连' : '公网中继';

const formatTime = (timestamp?: number | null) => {
  if (!timestamp) return '尚未检测';
  return new Date(timestamp).toLocaleString('zh-CN', { hour12: false });
};

const isIpv6VerificationWarning = (route: HomeProxyRoute) =>
  route.state === 'active' && route.lastError?.startsWith('公网验证未完成：');

const copy = async (value: string, label: string) => {
  await navigator.clipboard.writeText(value);
  toast.success(`${label}已复制`);
};

export default function HomeAccessPage() {
  const navigate = useNavigate();
  const adminMode = isAdmin();
  const [loading, setLoading] = useState(true);
  const [formOptionsLoading, setFormOptionsLoading] = useState(false);
  const [formOptionsLoaded, setFormOptionsLoaded] = useState(false);
  const [formOptionsError, setFormOptionsError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [guideRoute, setGuideRoute] = useState<HomeProxyRoute | null>(null);
  const [eventRoute, setEventRoute] = useState<HomeProxyRoute | null>(null);
  const [natEvents, setNatEvents] = useState<HomeProxyNatEvent[]>([]);
  const [eventLoading, setEventLoading] = useState(false);
  const [refreshingId, setRefreshingId] = useState<number | null>(null);
  const [routes, setRoutes] = useState<HomeProxyRoute[]>([]);
  const [connectors, setConnectors] = useState<InternalConnector[]>([]);
  const [pools, setPools] = useState<PublishingPortPool[]>([]);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [tunnels, setTunnels] = useState<TunnelOption[]>([]);
  const [dynamicDnsRules, setDynamicDnsRules] = useState<DynamicDnsRule[]>([]);
  const [form, setForm] = useState<FormState>(emptyForm);

  const loadRoutes = async (showLoading = routes.length === 0) => {
    if (showLoading) setLoading(true);
    const response = await getHomeProxyRoutes();
    if (response.code === 0) setRoutes(response.data || []);
    else toast.error(response.msg || '加载家庭网络中转失败');
    if (showLoading) setLoading(false);
  };

  const loadFormOptions = async () => {
    if (formOptionsLoading) return;
    setFormOptionsLoading(true);
    setFormOptionsError('');
    const results = await Promise.allSettled([
      getInternalConnectors(), getPublishingPortPools(), getTunnelList(), getNodeList(),
      adminMode ? getDynamicDnsOverview() : Promise.resolve({ code: 0, msg: '', data: { rules: [] } }),
    ]);
    const errors: string[] = [];
    const applyResult = <T,>(index: number, label: string, apply: (data: T) => void) => {
      const result = results[index];
      if (result.status === 'rejected') {
        errors.push(label);
        return;
      }
      if (result.value.code !== 0) {
        errors.push(label);
        return;
      }
      apply(result.value.data as T);
    };
    applyResult<InternalConnector[]>(0, '家庭设备', data => setConnectors(data || []));
    applyResult<PublishingPortPool[]>(1, '端口资源', data => setPools(data || []));
    applyResult<TunnelOption[]>(2, '隧道', data => setTunnels(data || []));
    applyResult<NodeOption[]>(3, '节点', data => setNodes(data || []));
    applyResult<{ rules: DynamicDnsRule[] }>(4, '动态 DNS', data => setDynamicDnsRules(data?.rules || []));
    setFormOptionsLoaded(errors.length === 0);
    setFormOptionsError(errors.length > 0 ? `${errors.join('、')}加载失败，可重试；其他选项仍可使用。` : '');
    setFormOptionsLoading(false);
  };

  useEffect(() => {
    void loadRoutes(true);
    const timer = window.setInterval(() => void loadRoutes(false), 10000);
    return () => window.clearInterval(timer);
  }, []);

  const openCreateModal = () => {
    setModalOpen(true);
    if (!formOptionsLoaded) void loadFormOptions();
  };

  const ingressPools = useMemo(() => pools.filter(pool => pool.availablePorts > 0), [pools]);
  const tunnelOptions = useMemo(() => tunnels.filter(tunnel => tunnel.type === 2 && tunnel.status === 1
    && tunnel.quotaAvailable !== false && (tunnel.pathNodeDetails?.length || 0) >= 2), [tunnels]);
  const selectedEgressTunnel = useMemo(() => tunnelOptions.find(item => String(item.id) === form.egressTunnelId), [tunnelOptions, form.egressTunnelId]);
  const egressNodes = useMemo(() => nodes.filter(node => node.status === 1 && node.quotaAvailable !== false), [nodes]);
  const selectedEgressNode = useMemo(() => egressNodes.find(node => String(node.id) === form.egressNodeId), [egressNodes, form.egressNodeId]);
  const activeCount = routes.filter(item => item.state === 'active').length;
  const directCount = routes.filter(item => isDirect(item.accessMode)).length;
  const smartCount = routes.filter(item => item.accessMode === 'smart_nat').length;
  const matchingDnsRules = useMemo(() => dynamicDnsRules.filter(rule => rule.sourceType === 'connector'
    && String(rule.connectorId || '') === form.connectorId
    && rule.recordType === (form.accessMode === 'ipv4_direct' ? 'A' : 'AAAA')
    && rule.enabled), [dynamicDnsRules, form.accessMode, form.connectorId]);

  const submit = async () => {
    if (!form.name.trim() || !form.connectorId
        || (form.egressMode === 'single' && !form.egressNodeId)
        || (form.egressMode === 'tunnel' && !form.egressTunnelId)
        || ((form.accessMode === 'relay' || form.accessMode === 'smart_nat') && !form.ingressPoolKey)
        || (form.accessMode === 'smart_nat' && !form.sourceConnectorId)) {
      toast.error('请完整选择家庭设备、接入方式和出口路径');
      return;
    }
    const directPort = Number(form.directPort);
    const sourceListenPort = Number(form.sourceListenPort);
    if (isDirect(form.accessMode) && (!Number.isInteger(directPort) || directPort < 1024 || directPort > 65535)) {
      return toast.error('家庭直连端口必须在 1024-65535 之间');
    }
    if (form.accessMode === 'smart_nat' && (!Number.isInteger(sourceListenPort) || sourceListenPort < 1024 || sourceListenPort > 65535)) {
      return toast.error('公司本地 SOCKS5 端口必须在 1024-65535 之间');
    }
    if (form.authEnabled && (!form.authUsername.trim() || form.authPassword.length < 8)) {
      toast.error('启用代理认证时，用户名不能为空且密码至少 8 位');
      return;
    }
    const ingressPool = selectedPool(pools, form.ingressPoolKey);
    if (((form.accessMode === 'relay' || form.accessMode === 'smart_nat') && !ingressPool) || (form.egressMode === 'single' && !selectedEgressNode)) {
      return toast.error('所选端口资源已变化，请重新选择');
    }
    setSubmitting(true);
    const response = await createHomeProxyRoute({
      name: form.name.trim(), connectorId: Number(form.connectorId),
      sourceConnectorId: form.accessMode === 'smart_nat' ? Number(form.sourceConnectorId) : undefined,
      sourceListenPort: form.accessMode === 'smart_nat' ? sourceListenPort : undefined,
      accessMode: form.accessMode,
      ingressPoolId: ingressPool?.id, ingressGrantId: ingressPool?.grantId,
      egressNodeId: form.egressMode === 'single' ? Number(form.egressNodeId) : undefined,
      egressMode: form.egressMode,
      egressTunnelId: form.egressMode === 'tunnel' ? Number(form.egressTunnelId) : undefined,
      transportMode: form.transportMode,
      realityServerName: form.transportMode === 'vless_reality' ? form.realityServerName.trim() : undefined,
      directPort: isDirect(form.accessMode) ? directPort : undefined,
      dynamicDnsRuleId: form.dynamicDnsRuleId ? Number(form.dynamicDnsRuleId) : undefined,
      authEnabled: form.authEnabled, authUsername: form.authUsername.trim(), authPassword: form.authPassword,
    });
    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || '创建家庭代理失败');
    toast.success('家庭代理已创建');
    setModalOpen(false);
    setForm(emptyForm());
    void loadRoutes(false);
  };

  const retryNat = async (id: number) => {
    setRefreshingId(id);
    const response = await retryHomeProxyNat(id);
    setRefreshingId(null);
    if (response.code !== 0) return toast.error(response.msg || '重新探测失败');
    toast.success('已重新发起 STUN 和 UDP 直连探测');
    window.setTimeout(() => void loadRoutes(false), 1500);
  };

  const showNatEvents = async (route: HomeProxyRoute) => {
    setEventRoute(route);
    setEventLoading(true);
    const response = await getHomeProxyNatEvents(route.id);
    setEventLoading(false);
    if (response.code === 0) setNatEvents(response.data || []);
    else toast.error(response.msg || '读取路径事件失败');
  };

  const refreshPublicAddress = async (id: number) => {
    setRefreshingId(id);
    const response = await refreshHomeProxyIpv6(id);
    setRefreshingId(null);
    if (response.code !== 0) return toast.error(response.msg || '公网地址检测失败');
    toast.success(`家庭 ${response.data.family === 'ipv4' ? 'IPv4' : 'IPv6'} 已更新：${response.data.address}`);
    void loadRoutes(false);
  };

  const remove = async (id: number) => {
    if (!window.confirm('确认删除家庭代理并释放其占用的端口资源吗？')) return;
    const response = await deleteHomeProxyRoute(id);
    if (response.code !== 0) return toast.error(response.msg || '删除失败');
    toast.success('家庭代理已删除');
    void loadRoutes(false);
  };

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">接入与发布</p>
          <h1 className="mt-1 text-2xl font-semibold">家庭网络中转</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-default-500">
            公司设备连接这里生成的 SOCKS5 地址后，流量会先经过家庭宽带，再从指定服务器或已有隧道访问目标地址。家庭到出口可选择轻量 SOCKS5，或使用 VLESS + REALITY 保护跨境首跳。
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="flat" startContent={<Laptop size={18} />} onPress={() => navigate('/home-devices')}>管理家庭设备</Button>
          <Button color="primary" startContent={<Plus size={18} />} onPress={openCreateModal}>新建中转</Button>
        </div>
      </header>

      <section className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider md:grid-cols-4">
        {[
          ['运行中', activeCount], ['在线中转设备', new Set(routes.filter(item => item.connectorOnline).map(item => item.connectorId)).size],
          ['中转链路', routes.length], ['智能直连', smartCount],
        ].map(([label, value]) => <div key={String(label)} className="bg-content1 px-4 py-4"><div className="text-xs text-default-500">{label}</div><div className="mt-1 text-xl font-semibold">{value}</div></div>)}
      </section>

      <div className="rounded-lg border border-warning-200 bg-warning-50 px-4 py-3 text-sm leading-6 text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200">
        当前有 {directCount} 条公网直连。智能直连需要公司和家庭设备均运行 2.31.0 或更高版本 Agent，并保留一个公网入口作为失败回退。
      </div>

      {loading ? <div className="flex min-h-64 items-center justify-center"><Spinner /></div> : routes.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><Home size={32} /><span>暂无家庭网络中转</span></div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {routes.map(route => {
            const meta = stateMeta[route.state] || stateMeta.error;
            const endpoint = endpointText(route);
            const direct = isDirect(route.accessMode);
            const ipv6Direct = isIpv6Direct(route.accessMode);
            const directAddress = ipv6Direct ? route.directIpv6 : route.directIpv4;
            const smart = route.accessMode === 'smart_nat';
            const attempts = (route.directSuccessCount || 0) + (route.directFailureCount || 0);
            const successRate = attempts > 0 ? Math.round(((route.directSuccessCount || 0) / attempts) * 100) : 0;
            return <article key={route.id} className="rounded-lg border border-divider bg-content1 p-5">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0"><h2 className="truncate text-lg font-semibold">{route.name}</h2><p className="mt-1 text-sm text-default-500">{route.connectorName || '家庭设备'} · {route.proxyType.toUpperCase()}</p></div>
                <div className="flex flex-wrap justify-end gap-2">
                  <Chip size="sm" variant="flat" color={route.transportMode === 'vless_reality' ? 'secondary' : 'default'}>
                    {route.transportMode === 'vless_reality' ? 'VLESS + REALITY' : route.transportMode === 'socks5' ? 'SOCKS5' : '标准 TCP · 旧版'}
                  </Chip>
                  <Chip size="sm" variant="flat" color={direct || smart ? 'primary' : 'default'}>{accessLabel(route.accessMode)}</Chip>
                  <Chip size="sm" color={meta.color} variant="flat">{meta.label}</Chip>
                </div>
              </div>
              <div className="mt-4"><div className="mb-1 text-xs text-default-500">{smart ? '公司设备本地 SOCKS5 地址' : '代理访问地址'}</div><div className="rounded-md bg-default-100 px-3 py-3 font-mono text-sm">{endpoint}</div></div>
              <div className="mt-4 grid gap-3 text-sm md:grid-cols-2">
                <div><div className="text-default-500">{direct ? `家庭公网 ${ipv6Direct ? 'IPv6' : 'IPv4'}` : smart ? '失败回退入口' : '访问入口'}</div><div className="mt-1 break-all font-medium">{direct ? (directAddress || '等待检测') : adminMode ? (route.ingressPoolName || '未知') : '已配置'}</div></div>
                <div><div className="text-default-500">{route.egressMode === 'tunnel' ? '出口路径' : '出口服务器'}</div><div className="mt-1 font-medium">{adminMode ? (route.egressMode === 'tunnel' ? (route.egressTunnelName || '隧道已删除') : (route.egressNodeName || route.egressPoolName || '出口已删除')) : (route.egressMode === 'tunnel' ? '已配置多跳出口' : '已配置出口')}</div></div>
                <div><div className="text-default-500">家庭设备状态</div><div className={`mt-1 font-medium ${route.connectorOnline ? 'text-success' : 'text-danger'}`}>{route.connectorOnline ? '在线' : '离线'}</div></div>
                {smart && <div><div className="text-default-500">公司接入设备</div><div className={`mt-1 font-medium ${route.sourceConnectorOnline ? 'text-success' : 'text-danger'}`}>{route.sourceConnectorName || '设备已删除'} · {route.sourceConnectorOnline ? '在线' : '离线'}</div></div>}
                <div><div className="text-default-500">{direct ? '公网地址最近检测' : '客户端认证'}</div><div className="mt-1 font-medium">{direct ? formatTime(route.ipCheckedAt || route.ipv6CheckedAt) : (route.authEnabled ? '已启用' : '未启用')}</div></div>
                {direct && <div className="md:col-span-2"><div className="text-default-500">动态 DNS</div><div className="mt-1 break-all font-medium">{route.publicDomain ? `${route.publicDomain} · 已绑定` : '未绑定，使用裸 IP 地址'}</div></div>}
                {route.transportMode === 'vless_reality' && <div className="md:col-span-2"><div className="text-default-500">家庭到首个出口</div><div className="mt-1 font-medium">VLESS + REALITY · 伪装域名 {route.realityServerName || 'www.cloudflare.com'}</div></div>}
                {adminMode && route.egressMode === 'tunnel' && <div className="md:col-span-2"><div className="text-default-500">出口隧道</div><div className="mt-1 font-medium">{route.egressTunnelName || '隧道已删除'}</div><div className="mt-2 flex flex-wrap items-center gap-1.5">{(route.egressPathNodeDetails || []).map((node, index) => <span key={node.nodeId} className="contents">{index > 0 && <span className="text-default-400">→</span>}<Chip size="sm" variant="flat" color={node.status === 1 ? (index === (route.egressPathNodeDetails?.length || 0) - 1 ? 'success' : 'default') : 'danger'}>{node.name}{index === (route.egressPathNodeDetails?.length || 0) - 1 ? ' · 落地' : ''}</Chip></span>)}</div></div>}
              </div>
              {smart && <section className="mt-4 border-y border-divider py-4">
                <div className="flex flex-wrap items-center justify-between gap-3"><div><div className="text-xs text-default-500">当前访问路径</div><div className={`mt-1 font-semibold ${route.activeAccessPath === 'udp_direct' ? 'text-success' : 'text-warning'}`}>{natPathLabel(route)}</div></div><Chip size="sm" variant="flat" color={route.activeAccessPath === 'udp_direct' ? 'success' : 'warning'}>{route.natType || 'NAT 类型待检测'}</Chip></div>
                <div className="mt-4 grid grid-cols-2 gap-x-5 gap-y-3 text-sm sm:grid-cols-4">
                  <div><div className="text-default-500">直连成功率</div><div className="mt-1 font-medium">{attempts ? `${successRate}% · ${attempts} 次` : '暂无结果'}</div></div>
                  <div><div className="text-default-500">直连流量</div><div className="mt-1 font-medium">{formatBytes((route.directRxBytes || 0) + (route.directTxBytes || 0))}</div></div>
                  <div><div className="text-default-500">中继流量</div><div className="mt-1 font-medium">{formatBytes((route.relayRxBytes || 0) + (route.relayTxBytes || 0))}</div></div>
                  <div><div className="text-default-500">最近切换</div><div className="mt-1 font-medium">{formatTime(route.lastPathSwitchAt)}</div></div>
                </div>
                {route.lastNatError && <div className="mt-3 text-xs leading-5 text-warning-700 dark:text-warning-300">{route.lastNatError}</div>}
              </section>}
              {route.authEnabled === 1 && <div className="mt-4 rounded-md border border-divider px-3 py-3 text-sm"><div>用户名：<span className="font-mono">{route.authUsername}</span></div><div className="mt-1">密码：<span className="font-mono">{route.authPassword || '仅创建时显示'}</span></div></div>}
              {route.lastError && (
                <div className={`mt-4 rounded-md px-3 py-3 text-sm ${isIpv6VerificationWarning(route)
                  ? 'bg-warning-50 text-warning-800 dark:bg-warning-500/10 dark:text-warning-300'
                  : 'bg-danger-50 text-danger-700 dark:bg-danger-500/10 dark:text-danger-300'}`}>
                  {route.lastError}
                </div>
              )}
              <div className="mt-5 flex flex-wrap justify-end gap-2">
                {direct && <Button size="sm" variant="flat" startContent={<RefreshCw size={15} />} isLoading={refreshingId === route.id} onPress={() => refreshPublicAddress(route.id)}>检测地址</Button>}
                {direct && <Button size="sm" variant="flat" startContent={<BookOpen size={15} />} onPress={() => setGuideRoute(route)}>公网接入</Button>}
                {smart && <Button size="sm" variant="flat" startContent={<Activity size={15} />} onPress={() => showNatEvents(route)}>路径记录</Button>}
                {smart && <Button size="sm" variant="flat" startContent={<RefreshCw size={15} />} isLoading={refreshingId === route.id} onPress={() => retryNat(route.id)}>重新探测</Button>}
                {route.state === 'active' && (smart || (route.publicHost && route.publicPort)) && <Button size="sm" variant="flat" startContent={<Copy size={15} />} onPress={() => copy(endpoint, smart ? '本地代理地址' : '代理地址')}>复制地址</Button>}
                <Button size="sm" color="danger" variant="flat" startContent={<Trash2 size={15} />} onPress={() => remove(route.id)}>删除</Button>
              </div>
            </article>;
          })}
        </div>
      )}

      <div className="rounded-lg border border-divider bg-content1 px-4 py-4 text-sm leading-6 text-default-500">
        <div className="flex items-center gap-2 font-medium text-foreground"><Route size={16} /> 使用方式</div>
        <p className="mt-2">在公司电脑的浏览器、系统代理或代理客户端中填写上方 SOCKS5 地址。公司到家庭仍使用 SOCKS5；家庭之后按已配置出口访问公网。</p>
      </div>

      <Modal isOpen={modalOpen} onOpenChange={setModalOpen} size="3xl" scrollBehavior="inside">
        <ModalContent><ModalHeader>新建家庭网络中转</ModalHeader><ModalBody className="min-w-0 space-y-4">
          {formOptionsLoading && <div className="flex items-center gap-3 border-y border-divider px-1 py-3 text-sm text-default-500"><Spinner size="sm" /><span>正在加载可用设备、出口和端口资源</span></div>}
          {formOptionsError && <div className="flex flex-wrap items-center justify-between gap-3 border-y border-warning-200 bg-warning-50 px-3 py-3 text-sm text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200"><span>{formOptionsError}</span><Button size="sm" variant="flat" startContent={<RefreshCw size={15} />} isLoading={formOptionsLoading} onPress={() => void loadFormOptions()}>重试</Button></div>}
          <Input label="中转名称" placeholder="家庭联通出口" value={form.name} onValueChange={value => setForm({ ...form, name: value })} />
          <Select label="家庭设备" placeholder="选择已安装 Agent 的家庭电脑" selectedKeys={form.connectorId ? [form.connectorId] : []} onSelectionChange={keys => setForm({ ...form, connectorId: String(Array.from(keys)[0] || ''), dynamicDnsRuleId: '' })}>
            {connectors.map(item => <SelectItem key={String(item.id)} textValue={item.name}>{item.name} · {item.platform} · {item.online ? '在线' : '离线'}</SelectItem>)}
          </Select>
          <Tabs
            aria-label="接入方式"
            classNames={{
              tabList: 'grid w-full grid-cols-2 sm:grid-cols-4',
              tab: 'h-auto min-h-10 whitespace-normal px-2 leading-5',
            }}
            selectedKey={form.accessMode}
            onSelectionChange={key => setForm({ ...form, accessMode: String(key) as FormState['accessMode'], dynamicDnsRuleId: '' })}
          >
            <Tab key="ipv6_direct" title="IPv6 直连（推荐）" />
            <Tab key="ipv4_direct" title="IPv4 直连" />
            <Tab key="smart_nat" title="智能直连 + 中继" />
            <Tab key="relay" title="公网中继（兼容）" />
          </Tabs>
          <div className="rounded-md border border-divider px-4 py-3 text-sm leading-6 text-default-500">
            {form.accessMode === 'ipv6_direct'
              ? '公司网络直接连接家庭公网 IPv6，不经过入口 VPS。需要公司和家庭均可使用 IPv6，并在家庭路由器及系统防火墙放行下方 TCP 端口。'
              : form.accessMode === 'ipv4_direct'
                ? '公司网络直接连接家庭公网 IPv4。需要运营商提供真正公网 IPv4，并在家庭路由器配置端口转发到家庭设备。'
                : form.accessMode === 'smart_nat'
                  ? '公司和家庭设备都安装 CloudNest Agent。系统优先尝试 IPv4/IPv6 UDP 打洞直连，5 秒未建立便让新连接自动走公网入口中继。普通浏览器或手机不能单独参与打洞。'
                  : '适合家庭没有公网 IPv6，或 IPv6 入站被运营商拦截的情况。连接会先到入口 VPS，再反向送回家庭设备。'}
          </div>
          {form.accessMode === 'smart_nat' && <div className="grid gap-4 md:grid-cols-2">
            <Select label="公司接入设备" description="公司网络中已安装 Agent 的电脑；浏览器连接该设备的本地 SOCKS 地址" selectedKeys={form.sourceConnectorId ? [form.sourceConnectorId] : []} onSelectionChange={keys => setForm({ ...form, sourceConnectorId: String(Array.from(keys)[0] || '') })}>
              {connectors.filter(item => String(item.id) !== form.connectorId).map(item => <SelectItem key={String(item.id)} textValue={item.name}>{item.name} · {item.platform} · {item.online ? '在线' : '离线'}{adminMode ? ` · Agent ${item.version || '未知'}` : ''}</SelectItem>)}
            </Select>
            <Input label="公司本地 SOCKS5 端口" description="只监听 127.0.0.1，不暴露到公司局域网或公网" type="number" min={1024} max={65535} value={form.sourceListenPort} onValueChange={value => setForm({ ...form, sourceListenPort: value })} />
          </div>}
          <div className="grid gap-4 md:grid-cols-2">
            {isDirect(form.accessMode) ? (
              <Input label={`家庭 ${form.accessMode === 'ipv4_direct' ? 'IPv4' : 'IPv6'} 监听端口`} description={form.accessMode === 'ipv4_direct' ? '路由器需转发 WAN TCP 到家庭设备' : '路由器和系统防火墙需放行 TCP'} type="number" min={1024} max={65535} value={form.directPort} onValueChange={value => setForm({ ...form, directPort: value })} />
            ) : (
              <Select label="公网入口端口池" description="公司电脑首先连接的入口 VPS" selectedKeys={form.ingressPoolKey ? [form.ingressPoolKey] : []} onSelectionChange={keys => setForm({ ...form, ingressPoolKey: String(Array.from(keys)[0] || '') })}>
                {ingressPools.map(item => <SelectItem key={poolKey(item)} textValue={item.name}>{item.name} · {adminMode ? `${item.publicHost} · ` : ''}可用 {item.availablePorts}</SelectItem>)}
              </Select>
            )}
            <div className="space-y-2">
              <div className="text-sm font-medium">家庭之后的出口路径</div>
              <Tabs size="sm" aria-label="出口路径" selectedKey={form.egressMode} onSelectionChange={key => setForm({ ...form, egressMode: String(key) as FormState['egressMode'], egressTunnelId: '', egressNodeId: '' })}>
                <Tab key="single" title="指定服务器出口" />
                <Tab key="tunnel" title="隧道出口" />
              </Tabs>
            </div>
          </div>
          {form.egressMode === 'tunnel' && <Select label="出口隧道" description="家庭流量会依次经过隧道中的节点，最后一个节点作为公网落地出口" selectedKeys={form.egressTunnelId ? [form.egressTunnelId] : []} onSelectionChange={keys => setForm({ ...form, egressTunnelId: String(Array.from(keys)[0] || ''), egressNodeId: '' })}>
            {tunnelOptions.map(item => {
              const path = item.pathNodeDetails || [];
              return <SelectItem key={String(item.id)} textValue={item.name}>{path.length}级 · {item.name} · {path.map(node => node.name).join(' → ')}</SelectItem>;
            })}
          </Select>}
          {form.egressMode === 'single' && <Select label="指定出口服务器" description="可选择任意在线且有权限的节点，端口由系统从节点范围自动分配" selectedKeys={form.egressNodeId ? [form.egressNodeId] : []} onSelectionChange={keys => setForm({ ...form, egressNodeId: String(Array.from(keys)[0] || '') })}>
            {egressNodes.map(item => <SelectItem key={String(item.id)} textValue={item.name}>{item.name}{adminMode ? ` · ${item.serverIp || item.ip} · Agent ${item.version || '未知'}${item.accessType === 'shared' ? ` · ${item.ownerUserName || '管理员'}共享` : ''}` : ` · ${item.status === 1 ? '在线' : '离线'}`}</SelectItem>)}
          </Select>}
          <div className="space-y-2">
            <div className="text-sm font-medium">家庭到出口协议</div>
            <Tabs aria-label="家庭到出口协议" selectedKey={form.transportMode} onSelectionChange={key => setForm({ ...form, transportMode: String(key) as FormState['transportMode'] })}>
              <Tab key="socks5" title="SOCKS5 · 轻量" />
              <Tab key="vless_reality" title="VLESS + REALITY · 加密" />
            </Tabs>
            <p className="text-xs leading-5 text-default-500">{form.transportMode === 'vless_reality'
              ? '公司到家庭仍是 SOCKS5；家庭到首个出口使用 Reality。适合家庭宽带连接海外服务器。'
              : '家庭到出口使用带随机凭据的 SOCKS5 网关，资源占用更低，适合可信网络或境内链路。'}</p>
          </div>
          {form.transportMode === 'vless_reality' && <Input label="REALITY 伪装域名" description="必须是可正常访问的真实 HTTPS 域名，不要填写自己的业务域名" value={form.realityServerName} onValueChange={value => setForm({ ...form, realityServerName: value })} />}
          {(selectedEgressNode || selectedEgressTunnel) && <div className="rounded-md border border-divider px-4 py-3">
            <div className="text-xs font-medium text-default-500">完整链路预览</div>
            <div className="mt-2 flex flex-wrap items-center gap-2 text-sm">
              <Chip size="sm" variant="flat">公司 · SOCKS5</Chip><span className="text-default-400">→</span>
              <Chip size="sm" variant="flat" color="primary">家庭宽带</Chip><span className="text-default-400">→</span>
              {form.egressMode === 'single' ? <Chip size="sm" variant="flat" color="success">{form.transportMode === 'vless_reality' ? 'Reality' : 'SOCKS5'} · {selectedEgressNode?.name} · 出口</Chip>
                : (selectedEgressTunnel?.pathNodeDetails || []).map((node, index) => <span key={node.nodeId} className="contents"><Chip size="sm" variant="flat" color={index === (selectedEgressTunnel?.pathNodeDetails?.length || 0) - 1 ? 'success' : 'default'}>{index === 0 ? `${form.transportMode === 'vless_reality' ? 'Reality' : 'SOCKS5'} · ` : ''}{node.name}{index === (selectedEgressTunnel?.pathNodeDetails?.length || 0) - 1 ? ' · 落地' : ''}</Chip>{index < (selectedEgressTunnel?.pathNodeDetails?.length || 0) - 1 && <span className="text-default-400">→</span>}</span>)}
            </div>
            <p className="mt-2 text-xs leading-5 text-default-500">路径端口由系统自动分配并写入全局端口账本；隧道模式由最后一个节点作为公网出口。</p>
          </div>}
          {isDirect(form.accessMode) && <Select label="动态解析域名（可选）" description={form.accessMode === 'ipv4_direct' ? '只显示来源为该家庭设备的 A 记录' : '只显示来源为该家庭设备的 AAAA 记录'} selectedKeys={form.dynamicDnsRuleId ? [form.dynamicDnsRuleId] : []} onSelectionChange={keys => setForm({ ...form, dynamicDnsRuleId: String(Array.from(keys)[0] || '') })}>
            {matchingDnsRules.map(rule => <SelectItem key={String(rule.id)} textValue={rule.recordName}>{rule.recordName} · {rule.recordType} · {rule.lastStatus === 'success' ? '正常' : rule.lastStatus === 'error' ? '失败' : '待检测'}</SelectItem>)}
          </Select>}
          <Switch isSelected={form.authEnabled} onValueChange={value => setForm({ ...form, authEnabled: value })}>启用代理用户名密码认证</Switch>
          {form.authEnabled && <div className="grid gap-4 md:grid-cols-2"><Input label="代理用户名" value={form.authUsername} onValueChange={value => setForm({ ...form, authUsername: value })} /><Input label="代理密码" type="password" value={form.authPassword} onValueChange={value => setForm({ ...form, authPassword: value })} /></div>}
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setModalOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} isDisabled={formOptionsLoading} onPress={submit}>创建中转</Button></ModalFooter></ModalContent>
      </Modal>

      <Modal isOpen={Boolean(eventRoute)} onOpenChange={open => !open && setEventRoute(null)} size="2xl" scrollBehavior="inside">
        <ModalContent><ModalHeader className="flex flex-col gap-1"><span>NAT 路径记录</span><span className="text-sm font-normal text-default-500">{eventRoute?.name} · 只记录探测、直连建立和中继回退</span></ModalHeader><ModalBody className="pb-6">
          {eventLoading ? <div className="flex min-h-40 items-center justify-center"><Spinner /></div> : natEvents.length === 0 ? <div className="py-12 text-center text-default-500">暂无路径事件</div> : <div className="divide-y divide-divider border-y border-divider">
            {natEvents.map(event => <div key={event.id} className="grid gap-1 py-3 sm:grid-cols-[160px_120px_minmax(0,1fr)] sm:gap-3"><div className="text-xs text-default-500">{formatTime(event.createdTime)}</div><div className="text-sm font-medium">{event.eventType === 'direct_connected' ? '直连建立' : event.eventType === 'fallback' ? '切换中继' : event.eventType === 'probe' ? '开始探测' : event.eventType}</div><div className="break-words text-sm text-default-600">{event.detail || '-'}</div></div>)}
          </div>}
        </ModalBody><ModalFooter><Button onPress={() => setEventRoute(null)}>关闭</Button></ModalFooter></ModalContent>
      </Modal>

      <Modal isOpen={Boolean(guideRoute)} onOpenChange={open => !open && setGuideRoute(null)} size="3xl" scrollBehavior="inside">
        <ModalContent>
          <ModalHeader className="flex flex-col gap-1">
            <span>公网直连接入</span>
            <span className="text-sm font-normal text-default-500">{guideRoute?.name || '家庭代理'} · 路由器与系统防火墙配置</span>
          </ModalHeader>
          <ModalBody className="space-y-5 pb-6">
            {guideRoute && <>
              {(() => {
                const ipv6Direct = guideRoute.accessMode === 'ipv6_direct';
                const address = ipv6Direct ? guideRoute.directIpv6 : guideRoute.directIpv4;
                const testCommand = ipv6Direct
                  ? `nc -6 -vz ${guideRoute.directIpv6 || '<家庭 IPv6>'} ${guideRoute.publicPort || guideRoute.directPort || '<端口>'}`
                  : `nc -vz ${guideRoute.directIpv4 || guideRoute.publicDomain || '<家庭公网 IPv4 或域名>'} ${guideRoute.publicPort || guideRoute.directPort || '<端口>'}`;
                return <>
              <section className="grid gap-px overflow-hidden rounded-lg border border-divider bg-divider sm:grid-cols-3">
                <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">公网地址</div><div className="mt-1 break-all font-mono text-sm">{endpointText(guideRoute)}</div></div>
                <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">代理认证</div><div className={`mt-1 font-medium ${guideRoute.authEnabled === 1 ? 'text-success' : 'text-danger'}`}>{guideRoute.authEnabled === 1 ? '已启用' : '未启用'}</div></div>
                <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">接入端</div><div className={`mt-1 font-medium ${guideRoute.connectorOnline ? 'text-success' : 'text-danger'}`}>{guideRoute.connectorOnline ? '在线' : '离线'}</div></div>
              </section>

              {guideRoute.authEnabled !== 1 && <div className="flex gap-3 rounded-lg border border-danger-200 bg-danger-50 px-4 py-3 text-sm leading-6 text-danger-700 dark:border-danger-500/20 dark:bg-danger-500/10 dark:text-danger-300">
                <ShieldAlert className="mt-0.5 shrink-0" size={18} />
                <div><div className="font-medium">当前 SOCKS5 没有认证</div><div>放行公网端口后，知道地址的人都能使用你的家庭宽带。只建议临时测试，长期使用请删除并重建为“启用认证”。</div></div>
              </div>}

              <section className="space-y-3 border-y border-divider py-4">
                <div><h3 className="font-semibold">OpenWrt / iStoreOS {ipv6Direct ? '放行规则' : '端口转发'}</h3><p className="mt-1 text-sm text-default-500">{ipv6Direct ? 'IPv6 不做端口映射。启用 SOCKS5 认证后，可以只按 WAN -> LAN、TCP 和端口放行，不绑定临时 IPv6。' : 'IPv4 需要把公网 WAN 端口转发到家庭设备的局域网 IPv4。'}</p></div>
                <div className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
                  {(ipv6Direct ? [
                    ['入口', '网络 -> 防火墙 -> 通信规则 -> 添加'],
                    ['协议与方向', 'TCP · wan -> lan · 操作：接受'],
                    ['目标地址', guideRoute.authEnabled === 1 ? '可留空，避免临时 IPv6 变化失效' : (guideRoute.directIpv6 || '未开启认证时建议绑定当前 IPv6')],
                    ['目标端口', String(guideRoute.publicPort || guideRoute.directPort || '-')],
                    ['地址族限制', '仅 IPv6'],
                    ['最后一步', '保存，然后点击“保存并应用”'],
                  ] : [
                    ['入口', '网络 -> 防火墙 -> 端口转发 -> 添加'],
                    ['协议与方向', 'TCP · wan -> lan'],
                    ['外部端口', String(guideRoute.publicPort || guideRoute.directPort || '-')],
                    ['内部地址', '家庭设备局域网 IPv4，例如 192.168.100.x'],
                    ['内部端口', String(guideRoute.publicPort || guideRoute.directPort || '-')],
                    ['最后一步', '保存，然后点击“保存并应用”'],
                  ]).map(([label, value]) => <div key={label} className="min-w-0"><div className="text-default-500">{label}</div><div className="mt-1 break-all font-medium">{value}</div></div>)}
                </div>
              </section>

              <section className="space-y-3">
                <div className="flex items-center gap-2"><Terminal size={17} /><h3 className="font-semibold">先确认家庭设备正在监听</h3></div>
                <div className="divide-y divide-divider rounded-lg border border-divider font-mono text-xs sm:text-sm">
                  <div className="grid gap-1 px-4 py-3 sm:grid-cols-[110px_minmax(0,1fr)]"><span className="font-sans text-default-500">macOS</span><code className="break-all">netstat -anv -p tcp | grep {guideRoute.publicPort || guideRoute.directPort}</code></div>
                  <div className="grid gap-1 px-4 py-3 sm:grid-cols-[110px_minmax(0,1fr)]"><span className="font-sans text-default-500">Linux</span><code className="break-all">ss -lntp | grep {guideRoute.publicPort || guideRoute.directPort}</code></div>
                  <div className="grid gap-1 px-4 py-3 sm:grid-cols-[110px_minmax(0,1fr)]"><span className="font-sans text-default-500">Windows</span><code className="break-all">Get-NetTCPConnection -LocalPort {guideRoute.publicPort || guideRoute.directPort} -State Listen</code></div>
                </div>
              </section>

              <section className="space-y-3 border-t border-divider pt-4">
                <div className="flex flex-wrap items-center justify-between gap-3"><div><h3 className="font-semibold">从家庭网络以外测试</h3><p className="mt-1 text-sm text-default-500">{ipv6Direct ? '可在支持 IPv6 的 VPS、公司电脑或手机终端执行。' : '可在任意公网 VPS、公司电脑或手机热点环境执行。'}</p></div><Button size="sm" variant="flat" startContent={<Copy size={15} />} isDisabled={!address && !guideRoute.publicDomain} onPress={() => copy(testCommand, '测试命令')}>复制测试命令</Button></div>
                <pre className="overflow-x-auto rounded-lg bg-default-100 p-4 font-mono text-sm">{testCommand}</pre>
                <div className="grid gap-3 text-sm sm:grid-cols-3">
                  <div><div className="font-medium text-success">succeeded</div><div className="mt-1 text-default-500">公网路由、路由器规则和监听均正常。</div></div>
                  <div><div className="font-medium text-danger">Connection refused</div><div className="mt-1 text-default-500">公网地址已到达家庭网络，但端口未监听或被防火墙拒绝。</div></div>
                  <div><div className="font-medium text-warning">timed out</div><div className="mt-1 text-default-500">路由器、运营商或系统防火墙静默丢弃了连接。</div></div>
                </div>
              </section>

              <div className="rounded-lg border border-warning-200 bg-warning-50 px-4 py-3 text-sm leading-6 text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200">
                {ipv6Direct ? 'macOS、Windows 和手机可能使用会变化的临时 IPv6。建议开启代理认证，并让 OpenWrt 规则不绑定具体临时 IPv6；绑定动态 DNS 后，客户端优先填写域名。' : '公网 IPv4 必须是真正公网地址。如果运营商给的是 100.64.x.x、10.x.x.x、172.16-31.x.x 或 192.168.x.x，直连不可用，需要改用公网中继。'}
              </div>
                </>;
              })()}
            </>}
          </ModalBody>
          <ModalFooter><Button color="primary" onPress={() => setGuideRoute(null)}>完成</Button></ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
