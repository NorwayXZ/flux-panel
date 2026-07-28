import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { Tab, Tabs } from '@heroui/tabs';
import { Copy, Download, Home, Plus, RefreshCw, Route, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  createHomeProxyRoute,
  createInternalConnector,
  deleteHomeProxyRoute,
  getInternalConnectorInstall,
  getHomeProxyRoutes,
  getInternalConnectors,
  getPublishingPortPools,
  refreshHomeProxyIpv6,
  type HomeProxyRoute,
  type InternalConnector,
  type PublishingPortPool,
  type ConnectorPlatform,
} from '@/api';

const stateMeta: Record<HomeProxyRoute['state'], { label: string; color: 'success' | 'warning' | 'danger' | 'default' }> = {
  provisioning: { label: '配置中', color: 'warning' },
  active: { label: '运行中', color: 'success' },
  error: { label: '配置失败', color: 'danger' },
  delete_pending: { label: '等待清理', color: 'warning' },
  deleted: { label: '已删除', color: 'default' },
};

type FormState = {
  name: string; connectorId: string; accessMode: 'relay' | 'ipv6_direct';
  ingressPoolKey: string; egressPoolKey: string; directPort: string;
  authEnabled: boolean; authUsername: string; authPassword: string;
};

const emptyForm = (): FormState => ({
  name: '', connectorId: '', accessMode: 'ipv6_direct', ingressPoolKey: '', egressPoolKey: '', directPort: '23888',
  authEnabled: false, authUsername: '', authPassword: '',
});

const poolKey = (pool: PublishingPortPool) => `${pool.id}:${pool.grantId || 0}`;
const selectedPool = (pools: PublishingPortPool[], key: string) => pools.find(pool => poolKey(pool) === key);
const endpointText = (route: HomeProxyRoute) => {
  if (!route.publicHost || !route.publicPort) return '等待生成访问地址';
  const host = route.publicHost.includes(':') && !route.publicHost.startsWith('[') ? `[${route.publicHost}]` : route.publicHost;
  return `${host}:${route.publicPort}`;
};

const formatTime = (timestamp?: number | null) => {
  if (!timestamp) return '尚未检测';
  return new Date(timestamp).toLocaleString('zh-CN', { hour12: false });
};

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
  const [commandLoading, setCommandLoading] = useState(false);
  const [refreshingId, setRefreshingId] = useState<number | null>(null);
  const [command, setCommand] = useState('');
  const [commandConnectorId, setCommandConnectorId] = useState<number | null>(null);
  const [commandPlatform, setCommandPlatform] = useState<ConnectorPlatform>('linux');
  const [commandAction, setCommandAction] = useState<'install' | 'uninstall'>('install');
  const [routes, setRoutes] = useState<HomeProxyRoute[]>([]);
  const [connectors, setConnectors] = useState<InternalConnector[]>([]);
  const [pools, setPools] = useState<PublishingPortPool[]>([]);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [connectorForm, setConnectorForm] = useState<{ name: string; platform: ConnectorPlatform; allowedCidrs: string }>({ name: '', platform: 'linux', allowedCidrs: '' });

  const load = async () => {
    setLoading(true);
    const [routeRes, connectorRes, poolRes] = await Promise.all([
      getHomeProxyRoutes(), getInternalConnectors(), getPublishingPortPools(),
    ]);
    if (routeRes.code === 0) setRoutes(routeRes.data || []); else toast.error(routeRes.msg || '加载家庭接入失败');
    if (connectorRes.code === 0) setConnectors(connectorRes.data || []);
    if (poolRes.code === 0) setPools(poolRes.data || []);
    setLoading(false);
  };

  useEffect(() => { void load(); }, []);

  const ingressPools = useMemo(() => pools.filter(pool => pool.availablePorts > 0), [pools]);
  const activeCount = routes.filter(item => item.state === 'active').length;
  const directCount = routes.filter(item => item.accessMode === 'ipv6_direct').length;

  const submit = async () => {
    if (!form.name.trim() || !form.connectorId || !form.egressPoolKey
        || (form.accessMode === 'relay' && !form.ingressPoolKey)) {
      toast.error(form.accessMode === 'relay' ? '请填写名称、家庭接入端、入口和出口端口池' : '请填写名称、家庭接入端和出口端口池');
      return;
    }
    const directPort = Number(form.directPort);
    if (form.accessMode === 'ipv6_direct' && (!Number.isInteger(directPort) || directPort < 1024 || directPort > 65535)) {
      return toast.error('家庭 IPv6 直连端口必须在 1024-65535 之间');
    }
    if (form.authEnabled && (!form.authUsername.trim() || form.authPassword.length < 8)) {
      toast.error('启用代理认证时，用户名不能为空且密码至少 8 位');
      return;
    }
    const ingressPool = selectedPool(pools, form.ingressPoolKey);
    const egressPool = selectedPool(pools, form.egressPoolKey);
    if ((form.accessMode === 'relay' && !ingressPool) || !egressPool) return toast.error('所选端口资源已变化，请重新选择');
    setSubmitting(true);
    const response = await createHomeProxyRoute({
      name: form.name.trim(), connectorId: Number(form.connectorId),
      accessMode: form.accessMode,
      ingressPoolId: ingressPool?.id, ingressGrantId: ingressPool?.grantId,
      egressPoolId: egressPool.id, egressGrantId: egressPool.grantId,
      directPort: form.accessMode === 'ipv6_direct' ? directPort : undefined,
      authEnabled: form.authEnabled, authUsername: form.authUsername.trim(), authPassword: form.authPassword,
    });
    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || '创建家庭代理失败');
    toast.success('家庭代理已创建');
    setModalOpen(false);
    setForm(emptyForm());
    void load();
  };

  const refreshIpv6 = async (id: number) => {
    setRefreshingId(id);
    const response = await refreshHomeProxyIpv6(id);
    setRefreshingId(null);
    if (response.code !== 0) return toast.error(response.msg || 'IPv6 检测失败');
    toast.success(`家庭 IPv6 已更新：${response.data.address}`);
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
            公司设备连接这里生成的 SOCKS5 地址后，流量会先经过家庭宽带，再从指定 VPS 出口访问目标地址。优先使用家庭公网 IPv6 直连；没有可用 IPv6 时可切换到公网中继模式。
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
          ['代理链', routes.length], ['IPv6 直连', directCount],
        ].map(([label, value]) => <div key={String(label)} className="bg-content1 px-4 py-4"><div className="text-xs text-default-500">{label}</div><div className="mt-1 text-xl font-semibold">{value}</div></div>)}
      </section>

      <div className="rounded-lg border border-warning-200 bg-warning-50 px-4 py-3 text-sm leading-6 text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200">
        IPv6 直连要求家庭网络拥有可入站访问的公网 IPv6，并在家庭路由器和系统防火墙放行所选 TCP 端口。默认不启用代理认证，公网暴露后可能成为开放代理；正式使用时建议开启认证。
      </div>

      {loading ? <div className="flex min-h-64 items-center justify-center"><Spinner /></div> : routes.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><Home size={32} /><span>暂无家庭代理链</span></div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {routes.map(route => {
            const meta = stateMeta[route.state] || stateMeta.error;
            const endpoint = endpointText(route);
            const direct = route.accessMode === 'ipv6_direct';
            return <article key={route.id} className="rounded-lg border border-divider bg-content1 p-5">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0"><h2 className="truncate text-lg font-semibold">{route.name}</h2><p className="mt-1 text-sm text-default-500">{route.connectorName || '家庭接入端'} · {route.proxyType.toUpperCase()}</p></div>
                <div className="flex flex-wrap justify-end gap-2">
                  <Chip size="sm" variant="flat" color={direct ? 'primary' : 'default'}>{direct ? 'IPv6 直连' : '公网中继'}</Chip>
                  <Chip size="sm" color={meta.color} variant="flat">{meta.label}</Chip>
                </div>
              </div>
              <div className="mt-4 rounded-md bg-default-100 px-3 py-3 font-mono text-sm">{endpoint}</div>
              <div className="mt-4 grid gap-3 text-sm md:grid-cols-2">
                <div><div className="text-default-500">{direct ? '家庭公网 IPv6' : '访问入口端口池'}</div><div className="mt-1 break-all font-medium">{direct ? (route.directIpv6 || '等待检测') : (route.ingressPoolName || '未知')}</div></div>
                <div><div className="text-default-500">家庭出口 VPS 端口池</div><div className="mt-1 font-medium">{route.egressPoolName || '未知'}</div></div>
                <div><div className="text-default-500">接入端状态</div><div className={`mt-1 font-medium ${route.connectorOnline ? 'text-success' : 'text-danger'}`}>{route.connectorOnline ? '在线' : '离线'}</div></div>
                <div><div className="text-default-500">{direct ? 'IPv6 最近检测' : '客户端认证'}</div><div className="mt-1 font-medium">{direct ? formatTime(route.ipv6CheckedAt) : (route.authEnabled ? '已启用' : '未启用')}</div></div>
              </div>
              {route.authEnabled && <div className="mt-4 rounded-md border border-divider px-3 py-3 text-sm"><div>用户名：<span className="font-mono">{route.authUsername}</span></div><div className="mt-1">密码：<span className="font-mono">{route.authPassword || '仅创建时显示'}</span></div></div>}
              {route.lastError && <div className="mt-4 rounded-md bg-danger-50 px-3 py-3 text-sm text-danger-700 dark:bg-danger-500/10 dark:text-danger-300">{route.lastError}</div>}
              <div className="mt-5 flex flex-wrap justify-end gap-2">
                {direct && <Button size="sm" variant="flat" startContent={<RefreshCw size={15} />} isLoading={refreshingId === route.id} onPress={() => refreshIpv6(route.id)}>检测 IPv6</Button>}
                {route.state === 'active' && route.publicHost && route.publicPort && <Button size="sm" variant="flat" startContent={<Copy size={15} />} onPress={() => copy(endpoint, '代理地址')}>复制地址</Button>}
                <Button size="sm" color="danger" variant="flat" startContent={<Trash2 size={15} />} onPress={() => remove(route.id)}>删除</Button>
              </div>
            </article>;
          })}
        </div>
      )}

      <div className="rounded-lg border border-divider bg-content1 px-4 py-4 text-sm leading-6 text-default-500">
        <div className="flex items-center gap-2 font-medium text-foreground"><Route size={16} /> 使用方式</div>
        <p className="mt-2">在公司电脑的浏览器、系统代理或代理客户端中填写上方 SOCKS5 地址。IPv6 直连会直接访问家庭公网 IPv6；公网中继会先经过入口 VPS。两种模式都会让家庭 Agent 再经指定出口 VPS 访问网站。</p>
      </div>

      <Modal isOpen={modalOpen} onOpenChange={setModalOpen} size="3xl">
        <ModalContent><ModalHeader>新建家庭代理</ModalHeader><ModalBody className="space-y-4">
          <Input label="代理名称" placeholder="家庭联通出口" value={form.name} onValueChange={value => setForm({ ...form, name: value })} />
          <Select label="家庭接入端" placeholder="选择已安装 Agent 的家庭电脑" selectedKeys={form.connectorId ? [form.connectorId] : []} onSelectionChange={keys => setForm({ ...form, connectorId: String(Array.from(keys)[0] || '') })}>
            {connectors.map(item => <SelectItem key={String(item.id)} textValue={item.name}>{item.name} · {item.platform} · {item.online ? '在线' : '离线'}</SelectItem>)}
          </Select>
          <Tabs aria-label="接入方式" selectedKey={form.accessMode} onSelectionChange={key => setForm({ ...form, accessMode: String(key) as FormState['accessMode'] })}>
            <Tab key="ipv6_direct" title="IPv6 直连（推荐）" />
            <Tab key="relay" title="公网中继（兼容）" />
          </Tabs>
          <div className="rounded-md border border-divider px-4 py-3 text-sm leading-6 text-default-500">
            {form.accessMode === 'ipv6_direct'
              ? '公司网络直接连接家庭公网 IPv6，不经过入口 VPS。需要公司和家庭均可使用 IPv6，并在家庭路由器及系统防火墙放行下方 TCP 端口。'
              : '适合家庭没有公网 IPv6，或 IPv6 入站被运营商拦截的情况。连接会先到入口 VPS，再反向送回家庭设备。'}
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            {form.accessMode === 'ipv6_direct' ? (
              <Input label="家庭 IPv6 监听端口" description="需要在家庭路由器和系统防火墙放行 TCP" type="number" min={1024} max={65535} value={form.directPort} onValueChange={value => setForm({ ...form, directPort: value })} />
            ) : (
              <Select label="公网入口端口池" description="公司电脑首先连接的入口 VPS" selectedKeys={form.ingressPoolKey ? [form.ingressPoolKey] : []} onSelectionChange={keys => setForm({ ...form, ingressPoolKey: String(Array.from(keys)[0] || '') })}>
                {ingressPools.map(item => <SelectItem key={poolKey(item)} textValue={item.name}>{item.name} · {item.publicHost} · 可用 {item.availablePorts}</SelectItem>)}
              </Select>
            )}
            <Select label="家庭出口 VPS 端口池" description="家庭 Agent 访问目标地址时使用" selectedKeys={form.egressPoolKey ? [form.egressPoolKey] : []} onSelectionChange={keys => setForm({ ...form, egressPoolKey: String(Array.from(keys)[0] || '') })}>
              {pools.map(item => <SelectItem key={poolKey(item)} textValue={item.name}>{item.name} · {item.publicHost} · 可用 {item.availablePorts}</SelectItem>)}
            </Select>
          </div>
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
    </div>
  );
}
