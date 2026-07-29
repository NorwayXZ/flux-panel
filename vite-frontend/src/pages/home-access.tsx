import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { Tab, Tabs } from '@heroui/tabs';
import { BookOpen, Copy, Download, Home, Plus, RefreshCw, Route, ShieldAlert, Terminal, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  createHomeProxyRoute,
  createInternalConnector,
  deleteHomeProxyRoute,
  getDynamicDnsOverview,
  getInternalConnectorInstall,
  getHomeProxyRoutes,
  getInternalConnectors,
  getPublishingPortPools,
  getTunnelList,
  refreshHomeProxyIpv6,
  type HomeProxyRoute,
  type InternalConnector,
  type PublishingPortPool,
  type ConnectorPlatform,
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
  name: string; connectorId: string; accessMode: 'relay' | 'ipv6_direct' | 'ipv4_direct';
  ingressPoolKey: string; egressPoolKey: string; egressMode: 'single' | 'tunnel'; egressTunnelId: string;
  directPort: string; dynamicDnsRuleId: string;
  authEnabled: boolean; authUsername: string; authPassword: string;
};

type TunnelOption = {
  id: number; name: string; type: number; status: number; nodePath?: string;
  quotaAvailable?: boolean; unavailableReason?: string;
  pathNodeDetails?: Array<{ nodeId: number; name: string; status: number }>;
};

const emptyForm = (): FormState => ({
  name: '', connectorId: '', accessMode: 'ipv6_direct', ingressPoolKey: '', egressPoolKey: '',
  egressMode: 'single', egressTunnelId: '', directPort: '23888',
  dynamicDnsRuleId: '', authEnabled: false, authUsername: '', authPassword: '',
});

const poolKey = (pool: PublishingPortPool) => `${pool.id}:${pool.grantId || 0}`;
const selectedPool = (pools: PublishingPortPool[], key: string) => pools.find(pool => poolKey(pool) === key);
const endpointText = (route: HomeProxyRoute) => {
  if (!route.publicHost || !route.publicPort) return '等待生成访问地址';
  const host = route.publicHost.includes(':') && !route.publicHost.startsWith('[') ? `[${route.publicHost}]` : route.publicHost;
  return `${host}:${route.publicPort}`;
};
const isDirect = (mode: HomeProxyRoute['accessMode'] | FormState['accessMode']) => mode === 'ipv6_direct' || mode === 'ipv4_direct';
const isIpv6Direct = (mode: HomeProxyRoute['accessMode'] | FormState['accessMode']) => mode === 'ipv6_direct';
const accessLabel = (mode: HomeProxyRoute['accessMode'] | FormState['accessMode']) =>
  mode === 'ipv4_direct' ? 'IPv4 直连' : mode === 'ipv6_direct' ? 'IPv6 直连' : '公网中继';

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
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [connectorModalOpen, setConnectorModalOpen] = useState(false);
  const [commandModalOpen, setCommandModalOpen] = useState(false);
  const [guideRoute, setGuideRoute] = useState<HomeProxyRoute | null>(null);
  const [commandLoading, setCommandLoading] = useState(false);
  const [refreshingId, setRefreshingId] = useState<number | null>(null);
  const [command, setCommand] = useState('');
  const [commandConnectorId, setCommandConnectorId] = useState<number | null>(null);
  const [commandPlatform, setCommandPlatform] = useState<ConnectorPlatform>('linux');
  const [commandAction, setCommandAction] = useState<'install' | 'uninstall'>('install');
  const [routes, setRoutes] = useState<HomeProxyRoute[]>([]);
  const [connectors, setConnectors] = useState<InternalConnector[]>([]);
  const [pools, setPools] = useState<PublishingPortPool[]>([]);
  const [tunnels, setTunnels] = useState<TunnelOption[]>([]);
  const [dynamicDnsRules, setDynamicDnsRules] = useState<DynamicDnsRule[]>([]);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [connectorForm, setConnectorForm] = useState<{ name: string; platform: ConnectorPlatform; allowedCidrs: string }>({ name: '', platform: 'linux', allowedCidrs: '' });

  const load = async () => {
    setLoading(true);
    const adminMode = isAdmin();
    const [routeRes, connectorRes, poolRes, tunnelRes, dnsRes] = await Promise.all([
      getHomeProxyRoutes(), getInternalConnectors(), getPublishingPortPools(), getTunnelList(),
      adminMode ? getDynamicDnsOverview() : Promise.resolve({ code: 0, msg: '', data: { rules: [] } }),
    ]);
    if (routeRes.code === 0) setRoutes(routeRes.data || []); else toast.error(routeRes.msg || '加载家庭接入失败');
    if (connectorRes.code === 0) setConnectors(connectorRes.data || []);
    if (poolRes.code === 0) setPools(poolRes.data || []);
    if (tunnelRes.code === 0) setTunnels((tunnelRes.data || []) as TunnelOption[]);
    if (dnsRes.code === 0) setDynamicDnsRules(dnsRes.data?.rules || []);
    setLoading(false);
  };

  useEffect(() => { void load(); }, []);

  const ingressPools = useMemo(() => pools.filter(pool => pool.availablePorts > 0), [pools]);
  const tunnelOptions = useMemo(() => tunnels.filter(tunnel => tunnel.type === 2 && tunnel.status === 1
    && tunnel.quotaAvailable !== false && (tunnel.pathNodeDetails?.length || 0) >= 2), [tunnels]);
  const selectedEgressTunnel = useMemo(() => tunnelOptions.find(item => String(item.id) === form.egressTunnelId), [tunnelOptions, form.egressTunnelId]);
  const activeCount = routes.filter(item => item.state === 'active').length;
  const directCount = routes.filter(item => isDirect(item.accessMode)).length;
  const matchingDnsRules = useMemo(() => dynamicDnsRules.filter(rule => rule.sourceType === 'connector'
    && String(rule.connectorId || '') === form.connectorId
    && rule.recordType === (form.accessMode === 'ipv4_direct' ? 'A' : 'AAAA')
    && rule.enabled), [dynamicDnsRules, form.accessMode, form.connectorId]);

  const submit = async () => {
    if (!form.name.trim() || !form.connectorId
        || (form.egressMode === 'single' && !form.egressPoolKey)
        || (form.egressMode === 'tunnel' && !form.egressTunnelId)
        || (form.accessMode === 'relay' && !form.ingressPoolKey)) {
      toast.error('请完整选择家庭接入端、接入方式和出口路径');
      return;
    }
    const directPort = Number(form.directPort);
    if (isDirect(form.accessMode) && (!Number.isInteger(directPort) || directPort < 1024 || directPort > 65535)) {
      return toast.error('家庭直连端口必须在 1024-65535 之间');
    }
    if (form.authEnabled && (!form.authUsername.trim() || form.authPassword.length < 8)) {
      toast.error('启用代理认证时，用户名不能为空且密码至少 8 位');
      return;
    }
    const ingressPool = selectedPool(pools, form.ingressPoolKey);
    const egressPool = selectedPool(pools, form.egressPoolKey);
    if ((form.accessMode === 'relay' && !ingressPool) || (form.egressMode === 'single' && !egressPool)) {
      return toast.error('所选端口资源已变化，请重新选择');
    }
    setSubmitting(true);
    const response = await createHomeProxyRoute({
      name: form.name.trim(), connectorId: Number(form.connectorId),
      accessMode: form.accessMode,
      ingressPoolId: ingressPool?.id, ingressGrantId: ingressPool?.grantId,
      egressPoolId: form.egressMode === 'single' ? egressPool?.id : undefined,
      egressGrantId: form.egressMode === 'single' ? egressPool?.grantId : undefined,
      egressMode: form.egressMode,
      egressTunnelId: form.egressMode === 'tunnel' ? Number(form.egressTunnelId) : undefined,
      directPort: isDirect(form.accessMode) ? directPort : undefined,
      dynamicDnsRuleId: form.dynamicDnsRuleId ? Number(form.dynamicDnsRuleId) : undefined,
      authEnabled: form.authEnabled, authUsername: form.authUsername.trim(), authPassword: form.authPassword,
    });
    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || '创建家庭代理失败');
    toast.success('家庭代理已创建');
    setModalOpen(false);
    setForm(emptyForm());
    void load();
  };

  const refreshPublicAddress = async (id: number) => {
    setRefreshingId(id);
    const response = await refreshHomeProxyIpv6(id);
    setRefreshingId(null);
    if (response.code !== 0) return toast.error(response.msg || '公网地址检测失败');
    toast.success(`家庭 ${response.data.family === 'ipv4' ? 'IPv4' : 'IPv6'} 已更新：${response.data.address}`);
    void load();
  };

  const remove = async (id: number) => {
    if (!window.confirm('确认删除家庭代理并释放其占用的端口资源吗？')) return;
    const response = await deleteHomeProxyRoute(id);
    if (response.code !== 0) return toast.error(response.msg || '删除失败');
    toast.success('家庭代理已删除');
    void load();
  };

  const createConnector = async () => {
    if (!connectorForm.name.trim()) return toast.error('请输入家庭设备名称');
    setSubmitting(true);
    const response = await createInternalConnector({
      name: connectorForm.name.trim(), platform: connectorForm.platform,
      allowedCidrs: connectorForm.allowedCidrs.trim() || undefined,
    });
    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || '创建家庭接入端失败');
    setConnectorModalOpen(false);
    setCommand(response.data.installCommand);
    setCommandConnectorId(response.data.connector.id);
    setCommandPlatform(response.data.connector.platform || connectorForm.platform);
    setCommandAction('install');
    setCommandModalOpen(true);
    setConnectorForm({ name: '', platform: 'linux', allowedCidrs: '' });
    void load();
  };

  const refreshCommand = async (platform: ConnectorPlatform, action: 'install' | 'uninstall') => {
    setCommandPlatform(platform);
    setCommandAction(action);
    if (commandConnectorId === null) return;
    setCommandLoading(true);
    const response = await getInternalConnectorInstall(commandConnectorId, platform, action);
    setCommandLoading(false);
    if (response.code !== 0) return toast.error(response.msg || '获取命令失败');
    setCommand(response.data);
  };

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">反向接入 · 代理链</p>
          <h1 className="mt-1 text-2xl font-semibold">家庭接入</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-default-500">
            公司设备连接这里生成的 SOCKS5 地址后，流量会先经过家庭宽带，再从指定 VPS 出口访问目标地址。优先使用家庭公网 IPv6 或公网 IPv4 直连；没有公网入站条件时可切换到公网中继模式。
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="flat" startContent={<Download size={18} />} onPress={() => setConnectorModalOpen(true)}>添加家庭设备</Button>
          <Button color="primary" startContent={<Plus size={18} />} onPress={() => setModalOpen(true)}>新建家庭代理</Button>
        </div>
      </header>

      <section className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider md:grid-cols-4">
        {[
          ['运行中', activeCount], ['家庭接入端', connectors.filter(item => item.online).length],
          ['代理链', routes.length], ['公网直连', directCount],
        ].map(([label, value]) => <div key={String(label)} className="bg-content1 px-4 py-4"><div className="text-xs text-default-500">{label}</div><div className="mt-1 text-xl font-semibold">{value}</div></div>)}
      </section>

      <div className="rounded-lg border border-warning-200 bg-warning-50 px-4 py-3 text-sm leading-6 text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200">
        公网直连要求家庭网络具备可入站访问的公网 IPv6 或公网 IPv4。IPv6 需要放行通信规则，IPv4 需要端口转发到家庭设备；正式暴露到公网时建议启用代理认证。
      </div>

      {loading ? <div className="flex min-h-64 items-center justify-center"><Spinner /></div> : routes.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><Home size={32} /><span>暂无家庭代理链</span></div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {routes.map(route => {
            const meta = stateMeta[route.state] || stateMeta.error;
            const endpoint = endpointText(route);
            const direct = isDirect(route.accessMode);
            const ipv6Direct = isIpv6Direct(route.accessMode);
            const directAddress = ipv6Direct ? route.directIpv6 : route.directIpv4;
            return <article key={route.id} className="rounded-lg border border-divider bg-content1 p-5">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0"><h2 className="truncate text-lg font-semibold">{route.name}</h2><p className="mt-1 text-sm text-default-500">{route.connectorName || '家庭接入端'} · {route.proxyType.toUpperCase()}</p></div>
                <div className="flex flex-wrap justify-end gap-2">
                  <Chip size="sm" variant="flat" color={direct ? 'primary' : 'default'}>{accessLabel(route.accessMode)}</Chip>
                  <Chip size="sm" color={meta.color} variant="flat">{meta.label}</Chip>
                </div>
              </div>
              <div className="mt-4 rounded-md bg-default-100 px-3 py-3 font-mono text-sm">{endpoint}</div>
              <div className="mt-4 grid gap-3 text-sm md:grid-cols-2">
                <div><div className="text-default-500">{direct ? `家庭公网 ${ipv6Direct ? 'IPv6' : 'IPv4'}` : '访问入口端口池'}</div><div className="mt-1 break-all font-medium">{direct ? (directAddress || '等待检测') : (route.ingressPoolName || '未知')}</div></div>
                <div><div className="text-default-500">{route.egressMode === 'tunnel' ? '出口网关端口' : '家庭出口 VPS 端口池'}</div><div className="mt-1 font-medium">{route.egressPoolName || '未知'}</div></div>
                <div><div className="text-default-500">接入端状态</div><div className={`mt-1 font-medium ${route.connectorOnline ? 'text-success' : 'text-danger'}`}>{route.connectorOnline ? '在线' : '离线'}</div></div>
                <div><div className="text-default-500">{direct ? '公网地址最近检测' : '客户端认证'}</div><div className="mt-1 font-medium">{direct ? formatTime(route.ipCheckedAt || route.ipv6CheckedAt) : (route.authEnabled ? '已启用' : '未启用')}</div></div>
                {direct && <div className="md:col-span-2"><div className="text-default-500">动态 DNS</div><div className="mt-1 break-all font-medium">{route.publicDomain ? `${route.publicDomain} · 已绑定` : '未绑定，使用裸 IP 地址'}</div></div>}
                {route.egressMode === 'tunnel' && <div className="md:col-span-2"><div className="text-default-500">出口隧道</div><div className="mt-1 font-medium">{route.egressTunnelName || '隧道已删除'}</div><div className="mt-2 flex flex-wrap items-center gap-1.5">{(route.egressPathNodeDetails || []).map((node, index) => <span key={node.nodeId} className="contents">{index > 0 && <span className="text-default-400">→</span>}<Chip size="sm" variant="flat" color={node.status === 1 ? (index === (route.egressPathNodeDetails?.length || 0) - 1 ? 'success' : 'default') : 'danger'}>{node.name}{index === (route.egressPathNodeDetails?.length || 0) - 1 ? ' · 落地' : ''}</Chip></span>)}</div></div>}
              </div>
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
                {route.state === 'active' && route.publicHost && route.publicPort && <Button size="sm" variant="flat" startContent={<Copy size={15} />} onPress={() => copy(endpoint, '代理地址')}>复制地址</Button>}
                <Button size="sm" color="danger" variant="flat" startContent={<Trash2 size={15} />} onPress={() => remove(route.id)}>删除</Button>
              </div>
            </article>;
          })}
        </div>
      )}

      <div className="rounded-lg border border-divider bg-content1 px-4 py-4 text-sm leading-6 text-default-500">
        <div className="flex items-center gap-2 font-medium text-foreground"><Route size={16} /> 使用方式</div>
        <p className="mt-2">在公司电脑的浏览器、系统代理或代理客户端中填写上方 SOCKS5 地址。IPv6 直连和 IPv4 直连会直接访问家庭公网地址；公网中继会先经过入口 VPS。进入家庭后，流量可从单台 VPS 出口，也可以依次经过已有隧道，并由最后一个节点作为公网落地出口。</p>
      </div>

      <Modal isOpen={modalOpen} onOpenChange={setModalOpen} size="3xl">
        <ModalContent><ModalHeader>新建家庭代理</ModalHeader><ModalBody className="space-y-4">
          <Input label="代理名称" placeholder="家庭联通出口" value={form.name} onValueChange={value => setForm({ ...form, name: value })} />
          <Select label="家庭接入端" placeholder="选择已安装 Agent 的家庭电脑" selectedKeys={form.connectorId ? [form.connectorId] : []} onSelectionChange={keys => setForm({ ...form, connectorId: String(Array.from(keys)[0] || ''), dynamicDnsRuleId: '' })}>
            {connectors.map(item => <SelectItem key={String(item.id)} textValue={item.name}>{item.name} · {item.platform} · {item.online ? '在线' : '离线'}</SelectItem>)}
          </Select>
          <Tabs aria-label="接入方式" selectedKey={form.accessMode} onSelectionChange={key => setForm({ ...form, accessMode: String(key) as FormState['accessMode'], dynamicDnsRuleId: '' })}>
            <Tab key="ipv6_direct" title="IPv6 直连（推荐）" />
            <Tab key="ipv4_direct" title="IPv4 直连" />
            <Tab key="relay" title="公网中继（兼容）" />
          </Tabs>
          <div className="rounded-md border border-divider px-4 py-3 text-sm leading-6 text-default-500">
            {form.accessMode === 'ipv6_direct'
              ? '公司网络直接连接家庭公网 IPv6，不经过入口 VPS。需要公司和家庭均可使用 IPv6，并在家庭路由器及系统防火墙放行下方 TCP 端口。'
              : form.accessMode === 'ipv4_direct'
                ? '公司网络直接连接家庭公网 IPv4。需要运营商提供真正公网 IPv4，并在家庭路由器配置端口转发到家庭设备。'
              : '适合家庭没有公网 IPv6，或 IPv6 入站被运营商拦截的情况。连接会先到入口 VPS，再反向送回家庭设备。'}
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            {isDirect(form.accessMode) ? (
              <Input label={`家庭 ${form.accessMode === 'ipv4_direct' ? 'IPv4' : 'IPv6'} 监听端口`} description={form.accessMode === 'ipv4_direct' ? '路由器需转发 WAN TCP 到家庭设备' : '路由器和系统防火墙需放行 TCP'} type="number" min={1024} max={65535} value={form.directPort} onValueChange={value => setForm({ ...form, directPort: value })} />
            ) : (
              <Select label="公网入口端口池" description="公司电脑首先连接的入口 VPS" selectedKeys={form.ingressPoolKey ? [form.ingressPoolKey] : []} onSelectionChange={keys => setForm({ ...form, ingressPoolKey: String(Array.from(keys)[0] || '') })}>
                {ingressPools.map(item => <SelectItem key={poolKey(item)} textValue={item.name}>{item.name} · {item.publicHost} · 可用 {item.availablePorts}</SelectItem>)}
              </Select>
            )}
            <div className="space-y-2">
              <div className="text-sm font-medium">家庭之后的出口路径</div>
              <Tabs size="sm" aria-label="出口路径" selectedKey={form.egressMode} onSelectionChange={key => setForm({ ...form, egressMode: String(key) as FormState['egressMode'], egressTunnelId: '', egressPoolKey: '' })}>
                <Tab key="single" title="单 VPS 出口" />
                <Tab key="tunnel" title="隧道出口" />
              </Tabs>
            </div>
          </div>
          {form.egressMode === 'tunnel' && <Select label="出口隧道" description="家庭流量会依次经过隧道中的节点，最后一个节点作为公网落地出口" selectedKeys={form.egressTunnelId ? [form.egressTunnelId] : []} onSelectionChange={keys => setForm({ ...form, egressTunnelId: String(Array.from(keys)[0] || ''), egressPoolKey: '' })}>
            {tunnelOptions.map(item => {
              const path = item.pathNodeDetails || [];
              return <SelectItem key={String(item.id)} textValue={item.name}>{path.length}级 · {item.name} · {path.map(node => node.name).join(' → ')}</SelectItem>;
            })}
          </Select>}
          {form.egressMode === 'single' && <Select label="家庭出口 VPS 端口池" description="家庭 Agent 访问目标地址时使用" selectedKeys={form.egressPoolKey ? [form.egressPoolKey] : []} onSelectionChange={keys => setForm({ ...form, egressPoolKey: String(Array.from(keys)[0] || '') })}>
            {pools.map(item => <SelectItem key={poolKey(item)} textValue={item.name}>{item.nodeName} · {item.name} · {item.publicHost} · 可用 {item.availablePorts}</SelectItem>)}
          </Select>}
          {form.egressMode === 'tunnel' && selectedEgressTunnel && <div className="rounded-md border border-divider px-4 py-3">
            <div className="text-xs font-medium text-default-500">链路预览</div>
            <div className="mt-2 flex flex-wrap items-center gap-2 text-sm">
              <Chip size="sm" variant="flat">公司网络</Chip><span className="text-default-400">→</span>
              <Chip size="sm" variant="flat" color="primary">家庭宽带</Chip>
              {(selectedEgressTunnel.pathNodeDetails || []).map((node, index) => <span key={node.nodeId} className="contents"><span className="text-default-400">→</span><Chip size="sm" variant="flat" color={index === (selectedEgressTunnel.pathNodeDetails?.length || 0) - 1 ? 'success' : 'default'}>{node.name}{index === (selectedEgressTunnel.pathNodeDetails?.length || 0) - 1 ? ' · 落地' : ''}</Chip></span>)}
            </div>
            <div className="mt-2 text-xs leading-5 text-default-500">
              系统会从每个路径节点的端口范围自动分配一个网关端口，并纳入全局端口占用管理；最后一个节点作为公网出口。
            </div>
          </div>}
          {isDirect(form.accessMode) && <Select label="动态 DNS 域名（可选）" description={form.accessMode === 'ipv4_direct' ? '只显示来源为该家庭接入端的 A 记录' : '只显示来源为该家庭接入端的 AAAA 记录'} selectedKeys={form.dynamicDnsRuleId ? [form.dynamicDnsRuleId] : []} onSelectionChange={keys => setForm({ ...form, dynamicDnsRuleId: String(Array.from(keys)[0] || '') })}>
            {matchingDnsRules.map(rule => <SelectItem key={String(rule.id)} textValue={rule.recordName}>{rule.recordName} · {rule.recordType} · {rule.lastStatus === 'success' ? '正常' : rule.lastStatus === 'error' ? '失败' : '待检测'}</SelectItem>)}
          </Select>}
          <Switch isSelected={form.authEnabled} onValueChange={value => setForm({ ...form, authEnabled: value })}>启用代理用户名密码认证</Switch>
          {form.authEnabled && <div className="grid gap-4 md:grid-cols-2"><Input label="代理用户名" value={form.authUsername} onValueChange={value => setForm({ ...form, authUsername: value })} /><Input label="代理密码" type="password" value={form.authPassword} onValueChange={value => setForm({ ...form, authPassword: value })} /></div>}
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setModalOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submit}>创建代理</Button></ModalFooter></ModalContent>
      </Modal>

      <Modal isOpen={connectorModalOpen} onOpenChange={setConnectorModalOpen} size="xl">
        <ModalContent><ModalHeader>添加家庭设备</ModalHeader><ModalBody className="space-y-4">
          <Input label="设备名称" placeholder="家里 Windows 电脑" value={connectorForm.name} onValueChange={value => setConnectorForm({ ...connectorForm, name: value })} />
          <Select label="操作系统" selectedKeys={[connectorForm.platform]} onSelectionChange={keys => setConnectorForm({ ...connectorForm, platform: String(Array.from(keys)[0] || 'linux') as ConnectorPlatform })}>
            <SelectItem key="linux">Linux · amd64 / arm64</SelectItem>
            <SelectItem key="windows">Windows · amd64 / arm64</SelectItem>
            <SelectItem key="macos">macOS · Intel / Apple Silicon</SelectItem>
          </Select>
          <Input label="允许访问的家庭网段（可选）" placeholder="留空使用本机和常见内网网段" value={connectorForm.allowedCidrs} onValueChange={value => setConnectorForm({ ...connectorForm, allowedCidrs: value })} />
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setConnectorModalOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={createConnector}>生成安装命令</Button></ModalFooter></ModalContent>
      </Modal>

      <Modal isOpen={commandModalOpen} onOpenChange={setCommandModalOpen} size="2xl">
        <ModalContent><ModalHeader>{commandAction === 'install' ? '安装' : '卸载'}家庭 Agent</ModalHeader><ModalBody className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {(['linux', 'windows', 'macos'] as ConnectorPlatform[]).map(platform => <Button key={platform} size="sm" color={commandPlatform === platform ? 'primary' : 'default'} variant={commandPlatform === platform ? 'solid' : 'flat'} onPress={() => refreshCommand(platform, commandAction)}>{platform === 'linux' ? 'Linux' : platform === 'windows' ? 'Windows' : 'macOS'}</Button>)}
            <div className="ml-auto flex gap-2"><Button size="sm" variant={commandAction === 'install' ? 'solid' : 'flat'} color={commandAction === 'install' ? 'primary' : 'default'} onPress={() => refreshCommand(commandPlatform, 'install')}>安装</Button><Button size="sm" variant={commandAction === 'uninstall' ? 'solid' : 'flat'} color={commandAction === 'uninstall' ? 'danger' : 'default'} onPress={() => refreshCommand(commandPlatform, 'uninstall')}>卸载</Button></div>
          </div>
          <div className="rounded-md bg-default-100 p-4"><div className="mb-2 text-xs text-default-500">{commandPlatform === 'windows' ? '请使用管理员 PowerShell' : commandPlatform === 'macos' ? '请在终端执行，系统会要求管理员密码' : '请使用 root 用户或 sudo 执行'}</div>{commandLoading ? <Spinner size="sm" /> : <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all font-mono text-sm">{command}</pre>}</div>
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setCommandModalOpen(false)}>关闭</Button><Button color="primary" startContent={<Copy size={16} />} isDisabled={!command || commandLoading} onPress={() => copy(command, commandAction === 'install' ? '安装命令' : '卸载命令')}>复制命令</Button></ModalFooter></ModalContent>
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
