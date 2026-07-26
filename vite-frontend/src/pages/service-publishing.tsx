import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Tab, Tabs } from '@heroui/tabs';
import {
  Clock3,
  Copy,
  Database,
  Gamepad2,
  Globe2,
  HardDrive,
  LockKeyhole,
  Monitor,
  Plus,
  RadioTower,
  Settings2,
  ServerCog,
  SquareTerminal,
  Trash2,
  type LucideIcon,
} from 'lucide-react';
import toast from 'react-hot-toast';

import {
  createInternalConnector,
  createDomainRoute,
  createPublishedService,
  deleteInternalConnector,
  deleteDomainRoute,
  deletePublishedService,
  getInternalConnectorInstall,
  getDomainRoutes,
  getInternalConnectors,
  getPublishedServices,
  getPublishingPortPools,
  renewPublishedService,
  type InternalConnector,
  type ConnectorPlatform,
  type DomainRoute,
  type PublishedService,
  type PublishingPortPool,
} from '@/api';

const stateMeta: Record<string, { label: string; color: 'success' | 'warning' | 'danger' | 'default' | 'primary' }> = {
  provisioning: { label: '配置中', color: 'primary' },
  active: { label: '运行中', color: 'success' },
  expiring: { label: '即将到期', color: 'warning' },
  cleanup_pending: { label: '到期待清理', color: 'danger' },
  delete_pending: { label: '删除待清理', color: 'danger' },
  expired: { label: '已到期', color: 'warning' },
  released: { label: '已释放', color: 'default' },
};

const formatTime = (value?: number) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '无限制';

const platformMeta: Record<ConnectorPlatform, { label: string; commandLabel: string }> = {
  linux: { label: 'Linux', commandLabel: '终端命令' },
  windows: { label: 'Windows', commandLabel: '管理员 PowerShell' },
  macos: { label: 'macOS', commandLabel: '终端命令' },
};

type ServiceTemplateId = 'http' | 'https' | 'ssh' | 'rdp' | 'minecraft-java' | 'synology-dsm' | 'mysql' | 'postgresql' | 'custom-tcp';

type ServiceTemplate = {
  id: ServiceTemplateId;
  name: string;
  category: string;
  summary: string;
  suggestedName: string;
  targetPort: string;
  icon: LucideIcon;
  notice?: string;
};

const serviceTemplates: ServiceTemplate[] = [
  { id: 'http', name: 'Web HTTP', category: 'Web', summary: 'HTTP · 80', suggestedName: 'Web HTTP', targetPort: '80', icon: Globe2 },
  { id: 'https', name: 'Web HTTPS', category: 'Web', summary: 'TLS · 443', suggestedName: 'Web HTTPS', targetPort: '443', icon: LockKeyhole, notice: 'HTTPS 证书由内网 Web 服务负责配置和续期。' },
  { id: 'ssh', name: 'SSH', category: '远程管理', summary: '终端 · 22', suggestedName: 'SSH', targetPort: '22', icon: SquareTerminal, notice: 'SSH 属于敏感服务，建议限制接入端允许访问网段并使用密钥登录。' },
  { id: 'rdp', name: 'Windows RDP', category: '远程管理', summary: '远程桌面 · 3389', suggestedName: 'Windows RDP', targetPort: '3389', icon: Monitor, notice: 'RDP 属于敏感服务，建议限制接入端允许访问网段并启用系统账户保护。' },
  { id: 'minecraft-java', name: 'Minecraft Java', category: '游戏', summary: 'Java 版 · 25565', suggestedName: 'Minecraft Java', targetPort: '25565', icon: Gamepad2, notice: '此模板仅适用于使用 TCP 的 Java 版；基岩版需要 UDP，当前内网映射暂不支持。' },
  { id: 'synology-dsm', name: '群晖 DSM', category: 'NAS', summary: 'HTTPS · 5001', suggestedName: '群晖 DSM', targetPort: '5001', icon: HardDrive, notice: '管理后台不宜直接暴露给所有来源，建议限制接入端允许访问网段。' },
  { id: 'mysql', name: 'MySQL', category: '数据库', summary: 'TCP · 3306', suggestedName: 'MySQL', targetPort: '3306', icon: Database, notice: '数据库端口属于敏感服务，必须设置强密码并限制允许访问网段。' },
  { id: 'postgresql', name: 'PostgreSQL', category: '数据库', summary: 'TCP · 5432', suggestedName: 'PostgreSQL', targetPort: '5432', icon: Database, notice: '数据库端口属于敏感服务，必须设置强密码并限制允许访问网段。' },
  { id: 'custom-tcp', name: '自定义 TCP', category: '自定义', summary: '手动填写端口', suggestedName: '', targetPort: '', icon: Settings2 },
];

const createEmptyServiceForm = () => ({
  name: '',
  connectorId: '',
  poolAccessKey: '',
  targetHost: '127.0.0.1',
  targetPort: '',
  leaseMode: 'permanent' as 'timed' | 'permanent',
  leaseDuration: '24',
  leaseUnit: 'hours' as 'hours' | 'days',
  requestedPort: '',
});

export default function ServicePublishingPage() {
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [services, setServices] = useState<PublishedService[]>([]);
  const [domainRoutes, setDomainRoutes] = useState<DomainRoute[]>([]);
  const [connectors, setConnectors] = useState<InternalConnector[]>([]);
  const [pools, setPools] = useState<PublishingPortPool[]>([]);
  const [serviceModal, setServiceModal] = useState(false);
  const [connectorModal, setConnectorModal] = useState(false);
  const [commandModal, setCommandModal] = useState(false);
  const [installCommand, setInstallCommand] = useState('');
  const [commandLoading, setCommandLoading] = useState(false);
  const [commandConnectorId, setCommandConnectorId] = useState<number | null>(null);
  const [commandPlatform, setCommandPlatform] = useState<ConnectorPlatform>('linux');
  const [commandAction, setCommandAction] = useState<'install' | 'uninstall'>('install');
  const [activeView, setActiveView] = useState('services');
  const [domainModal, setDomainModal] = useState(false);
  const [serviceForm, setServiceForm] = useState(createEmptyServiceForm);
  const [selectedTemplateId, setSelectedTemplateId] = useState<ServiceTemplateId>('custom-tcp');
  const [connectorForm, setConnectorForm] = useState<{ name: string; allowedCidrs: string; platform: ConnectorPlatform }>({
    name: '', allowedCidrs: '', platform: 'linux',
  });
  const [domainForm, setDomainForm] = useState({ name: '', domain: '', publishedServiceId: '', listenPort: '443' });

  const loadData = async () => {
    setLoading(true);
    try {
      const [serviceRes, connectorRes, poolRes, domainRes] = await Promise.all([
        getPublishedServices(), getInternalConnectors(), getPublishingPortPools(), getDomainRoutes(),
      ]);
      if (serviceRes.code === 0) setServices(serviceRes.data || []);
      if (connectorRes.code === 0) setConnectors(connectorRes.data || []);
      if (poolRes.code === 0) setPools(poolRes.data || []);
      if (domainRes.code === 0) setDomainRoutes(domainRes.data || []);
      const failed = [serviceRes, connectorRes, poolRes, domainRes].find(item => item.code !== 0);
      if (failed) toast.error(failed.msg || '加载内网映射数据失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); }, []);

  const activeCount = useMemo(() => services.filter(item => item.state === 'active').length, [services]);
  const onlineConnectors = useMemo(() => connectors.filter(item => item.online).length, [connectors]);
  const selectedPool = useMemo(() => pools.find(item => `${item.id}:${item.grantId || 'admin'}` === serviceForm.poolAccessKey), [pools, serviceForm.poolAccessKey]);
  const selectedDomainMapping = useMemo(() => services.find(item => String(item.id) === domainForm.publishedServiceId), [services, domainForm.publishedServiceId]);
  const selectedTemplate = useMemo(() => serviceTemplates.find(item => item.id === selectedTemplateId) || serviceTemplates[serviceTemplates.length - 1], [selectedTemplateId]);

  const applyServiceTemplate = (template: ServiceTemplate) => {
    const previousTemplate = serviceTemplates.find(item => item.id === selectedTemplateId);
    setSelectedTemplateId(template.id);
    setServiceForm(current => ({
      ...current,
      name: !current.name.trim() || current.name === previousTemplate?.suggestedName ? template.suggestedName : current.name,
      targetPort: template.targetPort,
    }));
  };

  const submitService = async () => {
    if (!serviceForm.name.trim() || !serviceForm.connectorId || !selectedPool || !serviceForm.targetHost || !serviceForm.targetPort) {
      toast.error('请填写完整的内网映射配置');
      return;
    }
    const targetPort = Number(serviceForm.targetPort);
    const requestedPort = serviceForm.requestedPort ? Number(serviceForm.requestedPort) : undefined;
    if (!Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) return toast.error('内网目标端口必须在 1-65535 之间');
    if (requestedPort !== undefined && (!Number.isInteger(requestedPort) || requestedPort < 1 || requestedPort > 65535)) return toast.error('指定公网端口必须在 1-65535 之间');
    const permanent = serviceForm.leaseMode === 'permanent';
    const duration = Number(serviceForm.leaseDuration);
    if (!permanent && (!Number.isFinite(duration) || duration < 1)) return toast.error('定时服务的有效期至少为 1 小时');
    const leaseHours = permanent ? undefined : Math.round(duration * (serviceForm.leaseUnit === 'days' ? 24 : 1));
    setSubmitting(true);
    const res = await createPublishedService({
      name: serviceForm.name.trim(),
      connectorId: Number(serviceForm.connectorId),
      poolId: selectedPool.id,
      grantId: selectedPool.grantId,
      targetHost: serviceForm.targetHost.trim(),
      targetPort,
      permanent,
      leaseHours,
      requestedPort,
    });
    setSubmitting(false);
    if (res.code !== 0) return toast.error(res.msg || '创建映射失败');
    toast.success('内网映射已创建');
    setServiceModal(false);
    setServiceForm(createEmptyServiceForm());
    setSelectedTemplateId('custom-tcp');
    loadData();
  };

  const submitConnector = async () => {
    if (!connectorForm.name.trim()) return toast.error('请输入接入端名称');
    setSubmitting(true);
    const res = await createInternalConnector({
      name: connectorForm.name.trim(),
      allowedCidrs: connectorForm.allowedCidrs.trim() || undefined,
      platform: connectorForm.platform,
    });
    setSubmitting(false);
    if (res.code !== 0) return toast.error(res.msg || '创建失败');
    setConnectorModal(false);
    setConnectorForm({ name: '', allowedCidrs: '', platform: 'linux' });
    setCommandConnectorId(res.data.connector.id);
    setCommandPlatform(res.data.connector.platform || connectorForm.platform);
    setCommandAction('install');
    setInstallCommand(res.data.installCommand);
    setCommandModal(true);
    loadData();
  };

  const showInstall = async (id: number, platform: ConnectorPlatform) => {
    setCommandConnectorId(id);
    setCommandPlatform(platform);
    setCommandAction('install');
    setCommandLoading(true);
    const res = await getInternalConnectorInstall(id, platform, 'install');
    setCommandLoading(false);
    if (res.code !== 0) return toast.error(res.msg || '获取安装命令失败');
    setInstallCommand(res.data);
    setCommandModal(true);
  };

  const refreshInstallCommand = async (platform: ConnectorPlatform, action: 'install' | 'uninstall') => {
    setCommandPlatform(platform);
    setCommandAction(action);
    if (commandConnectorId === null) return;
    setCommandLoading(true);
    const res = await getInternalConnectorInstall(commandConnectorId, platform, action);
    setCommandLoading(false);
    if (res.code !== 0) return toast.error(res.msg || '获取安装命令失败');
    setInstallCommand(res.data);
  };

  const copyCommand = async () => {
    await navigator.clipboard.writeText(installCommand);
    toast.success(commandAction === 'install' ? '安装命令已复制' : '卸载命令已复制');
  };

  const renew = async (id: number) => {
    const res = await renewPublishedService(id, 24);
    if (res.code !== 0) return toast.error(res.msg || '续租失败');
    toast.success('已续租 24 小时');
    loadData();
  };

  const makePermanent = async (id: number) => {
    const res = await renewPublishedService(id, undefined, true);
    if (res.code !== 0) return toast.error(res.msg || '设置永久有效失败');
    toast.success('服务已改为永久有效');
    loadData();
  };

  const removeService = async (id: number) => {
    if (!window.confirm('确认停止该映射并释放端口吗？')) return;
    const res = await deletePublishedService(id);
    if (res.code !== 0) return toast.error(res.msg || '删除失败');
    const pending = res.data?.state === 'delete_pending';
    if (pending) {
      toast('接入端当前离线，映射将在恢复连接后自动删除，端口暂不释放');
    } else {
      toast.success('映射已停止，端口进入冷却');
    }
    loadData();
  };

  const submitDomainRoute = async () => {
    if (!domainForm.name.trim() || !domainForm.domain.trim() || !domainForm.publishedServiceId) return toast.error('请填写完整的域名入口配置');
    const listenPort = Number(domainForm.listenPort);
    if (!Number.isInteger(listenPort) || listenPort < 1 || listenPort > 65535) return toast.error('监听端口必须在 1-65535 之间');
    setSubmitting(true);
    const res = await createDomainRoute({
      name: domainForm.name.trim(),
      domain: domainForm.domain.trim(),
      publishedServiceId: Number(domainForm.publishedServiceId),
      listenPort,
    });
    setSubmitting(false);
    if (res.code !== 0) return toast.error(res.msg || '创建域名入口失败');
    toast.success('域名入口已创建');
    setDomainModal(false);
    setDomainForm({ name: '', domain: '', publishedServiceId: '', listenPort: '443' });
    loadData();
  };

  const removeDomainRoute = async (id: number) => {
    if (!window.confirm('确认删除该域名入口吗？原有内网映射不会被删除。')) return;
    const res = await deleteDomainRoute(id);
    if (res.code !== 0) return toast.error(res.msg || '删除域名入口失败');
    if (res.data?.state === 'delete_pending') toast('公网节点离线，恢复连接后将自动删除域名入口');
    else toast.success('域名入口已删除');
    loadData();
  };

  const removeConnector = async (id: number) => {
    if (!window.confirm('确认删除该内网接入端吗？')) return;
    const res = await deleteInternalConnector(id);
    if (res.code !== 0) return toast.error(res.msg || '删除失败');
    toast.success('接入端已删除');
    loadData();
  };

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">内网穿透</p>
          <h1 className="mt-1 text-2xl font-semibold">内网映射</h1>
        </div>
        <Button color="primary" startContent={<Plus size={18} />} onPress={() => {
          if (activeView === 'domains') setDomainModal(true);
          else if (activeView === 'connectors') setConnectorModal(true);
          else setServiceModal(true);
        }}>
          {activeView === 'domains' ? '新增域名入口' : activeView === 'connectors' ? '添加接入端' : '新建映射'}
        </Button>
      </header>

      <section className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider md:grid-cols-4">
        {[
          ['运行映射', activeCount], ['域名入口', domainRoutes.length], ['在线接入端', onlineConnectors], ['可用端口', pools.reduce((sum, pool) => sum + pool.availablePorts, 0)],
        ].map(([label, value]) => (
          <div key={String(label)} className="bg-content1 px-4 py-4">
            <div className="text-xs text-default-500">{label}</div>
            <div className="mt-1 text-xl font-semibold">{value}</div>
          </div>
        ))}
      </section>

      <Tabs aria-label="内网映射视图" variant="underlined" selectedKey={activeView} onSelectionChange={key => setActiveView(String(key))}>
        <Tab key="services" title={`映射列表 ${services.length}`}>
          {loading ? (
            <div className="flex min-h-64 items-center justify-center"><Spinner /></div>
          ) : services.length === 0 ? (
            <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500">
              <RadioTower size={30} />
              <span>暂无内网映射</span>
            </div>
          ) : (
            <div className="grid gap-4 lg:grid-cols-2 2xl:grid-cols-3">
              {services.map(service => {
                const meta = stateMeta[service.state] || { label: service.state, color: 'default' as const };
                return (
                  <article key={service.id} className="rounded-lg border border-divider bg-content1 p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <h2 className="truncate text-base font-semibold">{service.name}</h2>
                        <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-default-500"><span>{service.ownerRoleId === 1 ? `普通用户 · ${service.ownerUserName}` : '管理员'}</span><Chip size="sm" variant="flat">{service.protocol?.toUpperCase() || 'TCP'}</Chip>{service.grantId && <Chip size="sm" color="secondary" variant="flat">共享端口</Chip>}</div>
                      </div>
                      <Chip size="sm" color={meta.color} variant="flat">{meta.label}</Chip>
                    </div>
                    <div className="mt-4 rounded-md bg-default-100 px-3 py-3 font-mono text-sm">
                      {service.publicHost}:{service.publicPort}
                      <span className="mx-2 text-default-400">→</span>
                      {service.targetHost}:{service.targetPort}
                    </div>
                    <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
                      <div><dt className="text-default-500">端口资源</dt><dd className="mt-1 truncate">{service.poolName}{service.grantStartPort ? ` · ${service.grantStartPort}-${service.grantEndPort}` : ''}</dd></div>
                      <div><dt className="text-default-500">内网接入端</dt><dd className="mt-1 flex items-center gap-2"><span className={`h-2 w-2 rounded-full ${service.connectorOnline ? 'bg-success' : 'bg-danger'}`} />{service.connectorName}</dd></div>
                      <div className="col-span-2"><dt className="text-default-500">有效期</dt><dd className="mt-1">{service.permanent ? '永久有效' : formatTime(service.expiresAt)}</dd></div>
                    </dl>
                    {service.lastError && <div className="mt-3 rounded-md border border-danger/25 bg-danger/10 px-3 py-2 text-sm text-danger">{service.lastError}</div>}
                    <div className="mt-4 flex justify-end gap-2 border-t border-divider pt-3">
                      {service.state === 'active' && !service.permanent && <Button size="sm" variant="flat" startContent={<Clock3 size={15} />} onPress={() => renew(service.id)}>续期 24 小时</Button>}
                      {service.state === 'active' && !service.permanent && <Button size="sm" variant="light" color="primary" onPress={() => makePermanent(service.id)}>改为永久</Button>}
                      {!['released', 'expired', 'delete_pending'].includes(service.state) && <Button isIconOnly size="sm" variant="light" color="danger" aria-label="删除映射" onPress={() => removeService(service.id)}><Trash2 size={16} /></Button>}
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </Tab>
        <Tab key="domains" title={`域名入口 ${domainRoutes.length}`}>
          {loading ? (
            <div className="flex min-h-64 items-center justify-center"><Spinner /></div>
          ) : domainRoutes.length === 0 ? (
            <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500">
              <Globe2 size={30} />
              <span>暂无域名入口</span>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg border border-divider">
              <div className="hidden grid-cols-[1.2fr_1fr_1.2fr_1fr_auto] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500 lg:grid">
                <span>域名</span><span>公网入口</span><span>后端映射</span><span>状态</span><span>操作</span>
              </div>
              {domainRoutes.map(route => {
                const status = route.state === 'delete_pending'
                  ? { label: '待删除', color: 'warning' as const, detail: route.lastError || '等待公网节点处理' }
                  : !route.nodeOnline
                    ? { label: '节点离线', color: 'danger' as const, detail: '公网入口节点离线' }
                    : route.mappingState !== 'active'
                      ? { label: '映射不可用', color: 'danger' as const, detail: `后端映射状态：${stateMeta[route.mappingState]?.label || route.mappingState}` }
                      : !route.connectorOnline
                        ? { label: '接入端离线', color: 'danger' as const, detail: '内网接入端离线' }
                        : { label: '运行中', color: 'success' as const, detail: 'TLS 透传正常' };
                return (
                  <article key={route.id} className="grid gap-3 border-t border-divider px-4 py-4 first:border-t-0 lg:grid-cols-[1.2fr_1fr_1.2fr_1fr_auto] lg:items-center">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2"><span className="truncate font-medium">{route.name}</span><Chip size="sm" variant="flat" color="primary">TLS 透传</Chip></div>
                      <div className="mt-1 truncate font-mono text-sm text-default-600">{route.domain}</div>
                      <div className="mt-1 text-xs text-default-500">{route.ownerRoleId === 1 ? `普通用户 · ${route.ownerUserName}` : '管理员'}</div>
                    </div>
                    <div className="min-w-0 text-sm">
                      <div className="truncate font-mono">{route.domain}:{route.listenPort}</div>
                      <div className="mt-1 truncate text-xs text-default-500">DNS → {route.publicHost || '未配置'}</div>
                    </div>
                    <div className="min-w-0 text-sm">
                      <div className="truncate">{route.mappingName}</div>
                      <div className="mt-1 truncate font-mono text-xs text-default-500">{route.publicHost}:{route.mappingPublicPort}</div>
                    </div>
                    <div className="min-w-0"><Chip size="sm" variant="flat" color={status.color}>{status.label}</Chip><div className="mt-1 truncate text-xs text-default-500">{status.detail}</div></div>
                    <div className="flex justify-end">
                      {route.state !== 'delete_pending' && <Button isIconOnly size="sm" variant="light" color="danger" aria-label="删除域名入口" onPress={() => removeDomainRoute(route.id)}><Trash2 size={16} /></Button>}
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </Tab>
        <Tab key="connectors" title={`内网接入端 ${connectors.length}`}>
          <div className="overflow-hidden rounded-lg border border-divider">
            <div className="hidden grid-cols-[1.2fr_1fr_1fr_auto] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500 md:grid">
              <span>名称</span><span>连接状态</span><span>最近地址</span><span>操作</span>
            </div>
            {connectors.length === 0 ? <div className="py-16 text-center text-default-500">暂无内网接入端</div> : connectors.map(connector => (
              <div key={connector.id} className="grid gap-3 border-t border-divider px-4 py-4 first:border-t-0 md:grid-cols-[1.2fr_1fr_1fr_auto] md:items-center">
                <div>
                  <div className="flex min-w-0 items-center gap-2">
                    <span className="truncate font-medium">{connector.name}</span>
                    <Chip size="sm" variant="flat">{platformMeta[connector.platform || 'linux'].label}</Chip>
                  </div>
                  <div className="mt-1 text-xs text-default-500">{connector.ownerUserName}</div>
                </div>
                <div className="flex items-center gap-2 text-sm"><span className={`h-2 w-2 rounded-full ${connector.online ? 'bg-success' : 'bg-default-400'}`} />{connector.online ? '在线' : '离线'}</div>
                <div className="text-sm text-default-500">{connector.remoteIp || '尚未连接'}</div>
                <div className="flex justify-end gap-1">
                  <Button isIconOnly size="sm" variant="light" aria-label="安装命令" onPress={() => showInstall(connector.id, connector.platform || 'linux')}><ServerCog size={17} /></Button>
                  <Button isIconOnly size="sm" variant="light" color="danger" aria-label="删除接入端" onPress={() => removeConnector(connector.id)}><Trash2 size={17} /></Button>
                </div>
              </div>
            ))}
          </div>
        </Tab>
      </Tabs>

      <Modal isOpen={domainModal} onOpenChange={setDomainModal} size="2xl" scrollBehavior="inside">
        <ModalContent>
          <ModalHeader>新增 TLS 域名入口</ModalHeader>
          <ModalBody className="grid gap-4 sm:grid-cols-2">
            <Input label="入口名称" value={domainForm.name} onValueChange={value => setDomainForm({ ...domainForm, name: value })} />
            <Input label="访问域名" placeholder="app.example.com" value={domainForm.domain} onValueChange={value => setDomainForm({ ...domainForm, domain: value })} />
            <Select className="sm:col-span-2" label="后端内网映射" selectedKeys={domainForm.publishedServiceId ? [domainForm.publishedServiceId] : []} onSelectionChange={keys => setDomainForm({ ...domainForm, publishedServiceId: String(Array.from(keys)[0] || '') })}>
              {services.filter(item => item.state === 'active').map(item => (
                <SelectItem key={String(item.id)} textValue={`${item.name} ${item.publicHost}:${item.publicPort}`}>
                  {item.name} · {item.publicHost}:{item.publicPort} · {item.connectorOnline ? '接入端在线' : '接入端离线'}
                </SelectItem>
              ))}
            </Select>
            <Input label="TLS 监听端口" type="number" min={1} max={65535} value={domainForm.listenPort} onValueChange={value => setDomainForm({ ...domainForm, listenPort: value })} />
            <div className="rounded-md border border-divider bg-default-100 px-4 py-3 text-sm">
              <div className="text-xs text-default-500">DNS 解析目标</div>
              <div className="mt-1 truncate font-mono">{selectedDomainMapping?.publicHost || '选择后端映射后显示'}</div>
            </div>
            <div className="sm:col-span-2 grid gap-px overflow-hidden rounded-md border border-divider bg-divider sm:grid-cols-2">
              <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">传输方式</div><div className="mt-1 text-sm">TLS 原样透传</div></div>
              <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">证书位置</div><div className="mt-1 text-sm">后端服务管理域名证书</div></div>
            </div>
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setDomainModal(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submitDomainRoute}>创建入口</Button></ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={serviceModal} onOpenChange={setServiceModal} size="3xl" scrollBehavior="inside">
        <ModalContent>
          <ModalHeader>映射内网服务</ModalHeader>
          <ModalBody className="gap-5">
            <section className="space-y-3">
              <div className="flex items-center justify-between gap-3">
                <h3 className="text-sm font-semibold">服务模板</h3>
                <Chip size="sm" color="primary" variant="flat">TCP</Chip>
              </div>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                {serviceTemplates.map(template => {
                  const Icon = template.icon;
                  const selected = template.id === selectedTemplateId;
                  return (
                    <button
                      key={template.id}
                      type="button"
                      aria-pressed={selected}
                      className={`min-h-20 rounded-md border px-3 py-2 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${selected ? 'border-primary bg-primary/10 text-primary' : 'border-divider bg-content1 hover:bg-default-100'}`}
                      onClick={() => applyServiceTemplate(template)}
                    >
                      <div className="flex items-center gap-2">
                        <Icon aria-hidden="true" className="shrink-0" size={17} />
                        <span className="break-words text-sm font-medium">{template.name}</span>
                      </div>
                      <div className={`mt-1 break-words text-xs leading-5 ${selected ? 'text-primary/80' : 'text-default-500'}`}>{template.category} · {template.summary}</div>
                    </button>
                  );
                })}
              </div>
              {selectedTemplate.notice && <div className="rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-sm text-warning-700 dark:text-warning-400">{selectedTemplate.notice}</div>}
            </section>

            <div className="grid gap-4 border-t border-divider pt-5 sm:grid-cols-2">
              <Input label="映射名称" value={serviceForm.name} onValueChange={value => setServiceForm({ ...serviceForm, name: value })} />
              <Select label="内网接入端" selectedKeys={serviceForm.connectorId ? [serviceForm.connectorId] : []} onSelectionChange={keys => setServiceForm({ ...serviceForm, connectorId: String(Array.from(keys)[0] || '') })}>
                {connectors.map(item => <SelectItem key={String(item.id)} textValue={item.name}>{item.name} · {item.online ? '在线' : '离线'}</SelectItem>)}
              </Select>
              <Select className="sm:col-span-2" label="公网端口资源" selectedKeys={serviceForm.poolAccessKey ? [serviceForm.poolAccessKey] : []} onSelectionChange={keys => setServiceForm({ ...serviceForm, poolAccessKey: String(Array.from(keys)[0] || '') })}>
                {pools.map(item => {
                  const key = `${item.id}:${item.grantId || 'admin'}`;
                  const range = item.grantId ? `${item.grantStartPort}-${item.grantEndPort}` : `${item.startPort}-${item.endPort}`;
                  return <SelectItem key={key} textValue={`${item.name} ${range}`}>{item.name} · {item.nodeName} · {range} · 剩余 {item.availablePorts}</SelectItem>;
                })}
              </Select>
              <Input label="内网目标 IP" value={serviceForm.targetHost} onValueChange={value => setServiceForm({ ...serviceForm, targetHost: value })} />
              <Input label="内网目标端口" type="number" min={1} max={65535} value={serviceForm.targetPort} onValueChange={value => setServiceForm({ ...serviceForm, targetPort: value })} />
              <Input label="指定公网端口（可选）" type="number" min={1} max={65535} value={serviceForm.requestedPort} onValueChange={value => setServiceForm({ ...serviceForm, requestedPort: value })} />
              <div>
                <div className="mb-2 text-sm text-default-600">映射有效期</div>
                <Tabs fullWidth size="sm" selectedKey={serviceForm.leaseMode} onSelectionChange={key => setServiceForm({ ...serviceForm, leaseMode: String(key) as 'timed' | 'permanent' })} aria-label="映射有效期">
                  <Tab key="permanent" title="永久" />
                  <Tab key="timed" title="定时" />
                </Tabs>
              </div>
              {serviceForm.leaseMode === 'timed' && <div className="grid grid-cols-[minmax(0,1fr)_120px] gap-2 sm:col-span-2">
                <Input label="有效时长" type="number" min={1} value={serviceForm.leaseDuration} onValueChange={value => setServiceForm({ ...serviceForm, leaseDuration: value })} />
                <Select label="单位" selectedKeys={[serviceForm.leaseUnit]} onSelectionChange={keys => setServiceForm({ ...serviceForm, leaseUnit: String(Array.from(keys)[0] || 'hours') as 'hours' | 'days' })}><SelectItem key="hours">小时</SelectItem><SelectItem key="days">天</SelectItem></Select>
              </div>}
            </div>
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setServiceModal(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submitService}>创建映射</Button></ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={connectorModal} onOpenChange={setConnectorModal} size="xl">
        <ModalContent>
          <ModalHeader>添加内网接入端</ModalHeader>
          <ModalBody className="gap-4">
            <Input label="名称" value={connectorForm.name} onValueChange={value => setConnectorForm({ ...connectorForm, name: value })} />
            <div>
              <div className="mb-2 text-sm text-default-600">安装系统</div>
              <Tabs
                fullWidth
                aria-label="接入端安装系统"
                selectedKey={connectorForm.platform}
                onSelectionChange={key => setConnectorForm({ ...connectorForm, platform: String(key) as ConnectorPlatform })}
              >
                {Object.entries(platformMeta).map(([key, meta]) => <Tab key={key} title={meta.label} />)}
              </Tabs>
            </div>
            <Input label="允许访问网段（可选）" placeholder="127.0.0.1/32,192.168.0.0/16" value={connectorForm.allowedCidrs} onValueChange={value => setConnectorForm({ ...connectorForm, allowedCidrs: value })} />
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setConnectorModal(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submitConnector}>创建</Button></ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={commandModal} onOpenChange={setCommandModal} size="2xl">
        <ModalContent>
          <ModalHeader>接入端安装与卸载</ModalHeader>
          <ModalBody className="gap-4">
            <Tabs
              fullWidth
              aria-label="安装命令系统"
              selectedKey={commandPlatform}
              onSelectionChange={key => refreshInstallCommand(String(key) as ConnectorPlatform, commandAction)}
            >
              {Object.entries(platformMeta).map(([key, meta]) => <Tab key={key} title={meta.label} />)}
            </Tabs>
            <Tabs
              aria-label="安装或卸载"
              selectedKey={commandAction}
              variant="bordered"
              onSelectionChange={key => refreshInstallCommand(commandPlatform, String(key) as 'install' | 'uninstall')}
            >
              <Tab key="install" title="安装 / 更新" />
              <Tab key="uninstall" title="卸载" />
            </Tabs>
            <div>
              <div className="mb-2 text-xs text-default-500">
                {platformMeta[commandPlatform].commandLabel} · {commandAction === 'install' ? '安装或更新接入端' : '仅卸载内网接入端'}
              </div>
              <pre className="min-h-28 max-h-64 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-default-100 p-4 font-mono text-sm">
                {commandLoading ? '正在生成安装命令...' : installCommand}
              </pre>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setCommandModal(false)}>关闭</Button>
            <Button color="primary" isDisabled={commandLoading} startContent={<Copy size={16} />} onPress={copyCommand}>复制命令</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
