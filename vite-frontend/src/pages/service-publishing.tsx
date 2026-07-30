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
  Activity,
  ArrowDown,
  ArrowUp,
  Copy,
  Database,
  ExternalLink,
  FileKey2,
  Gamepad2,
  Globe2,
  HardDrive,
  LockKeyhole,
  Monitor,
  Pencil,
  Plus,
  RadioTower,
  RefreshCw,
  Route,
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
  getManagedCertificates,
  getInternalConnectors,
  getNodeList,
  getPublishedServices,
  getServiceTelemetryDetail,
  getServiceTelemetrySummary,
  getPublishingPortPools,
  getDnsZoneOptions,
  updateDomainRouteBackend,
  renewPublishedService,
  retryManagedCertificate,
  type InternalConnector,
  type ConnectorPlatform,
  type DomainRoute,
  type ManagedCertificate,
  type PublishedService,
  type ServiceTelemetry,
  type PublishingPortPool,
  type DnsZoneOption,
} from '@/api';

interface EntryNodeOption {
  id: number;
  name: string;
  serverIp?: string;
  ip?: string;
  status: number;
  version?: string;
}

const stateMeta: Record<string, { label: string; color: 'success' | 'warning' | 'danger' | 'default' | 'primary' }> = {
  provisioning: { label: '配置中', color: 'primary' },
  active: { label: '运行中', color: 'success' },
  expiring: { label: '即将到期', color: 'warning' },
  cleanup_pending: { label: '到期待清理', color: 'danger' },
  delete_pending: { label: '删除待清理', color: 'danger' },
  certificate_pending: { label: '申请证书', color: 'primary' },
  dns_propagating: { label: 'DNS 同步中', color: 'primary' },
  certificate_failed: { label: '证书失败', color: 'danger' },
  deployment_failed: { label: '部署失败', color: 'danger' },
  expired: { label: '已到期', color: 'warning' },
  released: { label: '已释放', color: 'default' },
};

const formatTime = (value?: number) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '无限制';

const formatBytes = (value = 0) => {
  if (!Number.isFinite(value) || value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
};

const formatSpeed = (value = 0) => `${formatBytes(value)}/s`;

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

const discoveredTemplate = (serviceType: string): ServiceTemplateId => {
  if (serviceType === 'ssh') return 'ssh';
  if (serviceType === 'rdp') return 'rdp';
  if (serviceType === 'mysql') return 'mysql';
  if (serviceType === 'postgresql') return 'postgresql';
  if (['synology', 'qnap', 'nas'].includes(serviceType)) return 'synology-dsm';
  if (serviceType === 'https') return 'https';
  if (['http', 'router', 'home-assistant', 'plex', 'camera'].includes(serviceType)) return 'http';
  return 'custom-tcp';
};

export default function ServicePublishingPage() {
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [services, setServices] = useState<PublishedService[]>([]);
  const [domainRoutes, setDomainRoutes] = useState<DomainRoute[]>([]);
  const [certificates, setCertificates] = useState<ManagedCertificate[]>([]);
  const [connectors, setConnectors] = useState<InternalConnector[]>([]);
  const [pools, setPools] = useState<PublishingPortPool[]>([]);
  const [dnsZones, setDnsZones] = useState<DnsZoneOption[]>([]);
  const [entryNodes, setEntryNodes] = useState<EntryNodeOption[]>([]);
  const [telemetry, setTelemetry] = useState<Record<string, ServiceTelemetry>>({});
  const [telemetryDetail, setTelemetryDetail] = useState<ServiceTelemetry | null>(null);
  const [telemetryLoading, setTelemetryLoading] = useState(false);
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
  const [backendEditRoute, setBackendEditRoute] = useState<DomainRoute | null>(null);
  const [backendEditForm, setBackendEditForm] = useState({ host: '', port: '', scheme: 'http' as 'http' | 'https', path: '/' });
  const [serviceForm, setServiceForm] = useState(createEmptyServiceForm);
  const [selectedTemplateId, setSelectedTemplateId] = useState<ServiceTemplateId>('custom-tcp');
  const [connectorForm, setConnectorForm] = useState<{ name: string; allowedCidrs: string; platform: ConnectorPlatform }>({
    name: '', allowedCidrs: '', platform: 'linux',
  });
  const isAdmin = localStorage.getItem('admin') === 'true' || localStorage.getItem('role_id') === '0';
  const emptyDomainForm = () => ({
    name: '', domain: '', pathPrefix: '/', publishedServiceId: '', backendType: 'mapping' as 'mapping' | 'direct',
    backendNodeId: '', backendHost: '127.0.0.1', backendPort: '', backendScheme: 'http' as 'http' | 'https',
    backendPath: '/', entryNodeId: 'mapping', listenPort: '443',
    ingressMode: (isAdmin ? 'managed_https' : 'passthrough') as 'managed_https' | 'passthrough', dnsZoneId: '',
  });
  const [domainForm, setDomainForm] = useState(emptyDomainForm);

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
      if (isAdmin) {
        const [zoneRes, certificateRes, nodeRes] = await Promise.all([getDnsZoneOptions(), getManagedCertificates(), getNodeList()]);
        if (zoneRes.code === 0) setDnsZones(zoneRes.data || []);
        if (certificateRes.code === 0) setCertificates(certificateRes.data || []);
        if (nodeRes.code === 0) setEntryNodes((nodeRes.data || []) as EntryNodeOption[]);
      }
      const failed = [serviceRes, connectorRes, poolRes, domainRes].find(item => item.code !== 0);
      if (failed) toast.error(failed.msg || '加载内网映射数据失败');
    } finally {
      setLoading(false);
    }
  };

  const loadTelemetry = async () => {
    const res = await getServiceTelemetrySummary();
    if (res.code !== 0) return;
    const next: Record<string, ServiceTelemetry> = {};
    for (const item of res.data || []) next[`${item.resourceType}:${item.resourceId}`] = item;
    setTelemetry(next);
  };

  useEffect(() => { loadData(); loadTelemetry(); }, []);

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible' && ['services', 'domains'].includes(activeView)) loadTelemetry();
    }, 5000);
    return () => window.clearInterval(timer);
  }, [activeView]);

  useEffect(() => {
    if (!telemetryDetail) return;
    const resourceType = telemetryDetail.resourceType;
    const resourceId = telemetryDetail.resourceId;
    const timer = window.setInterval(async () => {
      if (document.visibilityState !== 'visible') return;
      const res = await getServiceTelemetryDetail(resourceType, resourceId);
      if (res.code === 0) setTelemetryDetail(res.data);
    }, 5000);
    return () => window.clearInterval(timer);
  }, [telemetryDetail?.resourceType, telemetryDetail?.resourceId]);

  const openTelemetry = async (resourceType: 'service' | 'domain', resourceId: number) => {
    setTelemetryLoading(true);
    const res = await getServiceTelemetryDetail(resourceType, resourceId);
    setTelemetryLoading(false);
    if (res.code !== 0) return toast.error(res.msg || '读取流量详情失败');
    setTelemetryDetail(res.data);
  };

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('publish') === 'node-service') {
      const backendNodeId = params.get('backendNodeId') || '';
      setDomainForm(current => ({
        ...current,
        name: `${params.get('serviceName') || '节点服务'} 域名直达`,
        backendType: 'direct',
        backendNodeId,
        backendHost: params.get('backendHost') || '127.0.0.1',
        backendPort: params.get('backendPort') || '',
        backendScheme: params.get('backendScheme') === 'https' ? 'https' : 'http',
        entryNodeId: backendNodeId || 'mapping',
        ingressMode: 'managed_https',
      }));
      setActiveView('domains');
      setDomainModal(true);
      window.history.replaceState({}, '', window.location.pathname);
      return;
    }
    const connectorId = params.get('connectorId');
    const targetHost = params.get('targetHost');
    const targetPort = params.get('targetPort');
    if (!connectorId || !targetHost || !targetPort) return;
    const serviceType = params.get('serviceType') || 'custom-tcp';
    setSelectedTemplateId(discoveredTemplate(serviceType));
    setServiceForm(current => ({
      ...current,
      connectorId,
      targetHost,
      targetPort,
      name: params.get('serviceName') || `内网服务 ${targetHost}:${targetPort}`,
    }));
    setActiveView('services');
    setServiceModal(true);
    window.history.replaceState({}, '', window.location.pathname);
  }, []);

  const activeCount = useMemo(() => services.filter(item => item.state === 'active').length, [services]);
  const onlineConnectors = useMemo(() => connectors.filter(item => item.online).length, [connectors]);
  const selectedPool = useMemo(() => pools.find(item => `${item.id}:${item.grantId || 'admin'}` === serviceForm.poolAccessKey), [pools, serviceForm.poolAccessKey]);
  const selectedDomainMapping = useMemo(() => services.find(item => String(item.id) === domainForm.publishedServiceId), [services, domainForm.publishedServiceId]);
  const selectedBackendNodeKeys = useMemo(() => entryNodes.some(node => String(node.id) === domainForm.backendNodeId)
    ? [domainForm.backendNodeId]
    : [], [domainForm.backendNodeId, entryNodes]);
  const selectedEntryNodeKeys = useMemo(() => domainForm.entryNodeId === 'mapping'
    || entryNodes.some(node => String(node.id) === domainForm.entryNodeId)
    ? [domainForm.entryNodeId]
    : [], [domainForm.entryNodeId, entryNodes]);
  const selectedDomainPool = useMemo(() => pools.find(item => item.id === selectedDomainMapping?.poolId), [pools, selectedDomainMapping]);
  const selectedEntryNode = useMemo(() => domainForm.entryNodeId === 'mapping'
    ? entryNodes.find(item => item.id === (domainForm.backendType === 'direct' ? Number(domainForm.backendNodeId) : selectedDomainPool?.nodeId))
    : entryNodes.find(item => String(item.id) === domainForm.entryNodeId), [domainForm.backendNodeId, domainForm.backendType, domainForm.entryNodeId, entryNodes, selectedDomainPool]);
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
    if (!domainForm.name.trim() || !domainForm.domain.trim()) return toast.error('请填写入口名称和访问域名');
    if (domainForm.backendType === 'mapping' && !domainForm.publishedServiceId) return toast.error('请选择后端内网映射');
    if (domainForm.backendType === 'direct' && (!domainForm.backendNodeId || !domainForm.backendHost.trim() || !domainForm.backendPort)) return toast.error('请填写完整的节点本机服务');
    if (domainForm.ingressMode === 'managed_https' && !domainForm.dnsZoneId) return toast.error('请选择证书和 DNS 使用的域名配置');
    const listenPort = Number(domainForm.listenPort);
    if (!Number.isInteger(listenPort) || listenPort < 1 || listenPort > 65535) return toast.error('监听端口必须在 1-65535 之间');
    const backendPort = domainForm.backendType === 'direct' ? Number(domainForm.backendPort) : undefined;
    if (backendPort != null && (!Number.isInteger(backendPort) || backendPort < 1 || backendPort > 65535)) return toast.error('后端端口必须在 1-65535 之间');
    setSubmitting(true);
    const res = await createDomainRoute({
      name: domainForm.name.trim(),
      domain: domainForm.domain.trim(),
      backendType: domainForm.backendType,
      publishedServiceId: domainForm.backendType === 'mapping' ? Number(domainForm.publishedServiceId) : undefined,
      backendNodeId: domainForm.backendType === 'direct' ? Number(domainForm.backendNodeId) : undefined,
      backendHost: domainForm.backendType === 'direct' ? domainForm.backendHost.trim() : undefined,
      backendPort,
      backendScheme: domainForm.backendType === 'direct' ? domainForm.backendScheme : undefined,
      backendPath: domainForm.ingressMode === 'managed_https' ? domainForm.backendPath.trim() || '/' : '/',
      entryNodeId: domainForm.entryNodeId === 'mapping' ? undefined : Number(domainForm.entryNodeId),
      listenPort,
      ingressMode: domainForm.ingressMode,
      dnsZoneId: domainForm.dnsZoneId ? Number(domainForm.dnsZoneId) : undefined,
      pathPrefix: domainForm.ingressMode === 'managed_https' ? domainForm.pathPrefix.trim() || '/' : '/',
    });
    setSubmitting(false);
    if (res.code !== 0) return toast.error(res.msg || '创建域名直达失败');
    toast.success(domainForm.ingressMode === 'managed_https' ? '域名直达已创建，正在自动申请 HTTPS 证书' : '域名直达已创建');
    setDomainModal(false);
    setDomainForm(emptyDomainForm());
    loadData();
  };

  const bindDomain = (service: PublishedService) => {
    setDomainForm({
      ...emptyDomainForm(),
      name: `${service.name} 域名直达`,
      publishedServiceId: String(service.id),
    });
    setActiveView('domains');
    setDomainModal(true);
  };

  const removeDomainRoute = async (id: number) => {
    if (!window.confirm('确认删除该域名直达规则吗？原有内网映射不会被删除。')) return;
    const res = await deleteDomainRoute(id);
    if (res.code !== 0) return toast.error(res.msg || '删除域名直达失败');
    if (res.data?.state === 'delete_pending') toast('公网节点离线，恢复连接后将自动删除域名直达规则');
    else toast.success('域名直达已删除');
    loadData();
  };

  const editDomainBackend = (route: DomainRoute) => {
    setBackendEditRoute(route);
    setBackendEditForm({
      host: route.backendHost || '127.0.0.1',
      port: String(route.backendPort || ''),
      scheme: route.backendScheme === 'https' ? 'https' : 'http',
      path: route.backendPath || '/',
    });
  };

  const submitDomainBackend = async () => {
    if (!backendEditRoute || !backendEditForm.host.trim()) return;
    const port = Number(backendEditForm.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return toast.error('后端端口必须在 1-65535 之间');
    setSubmitting(true);
    const res = await updateDomainRouteBackend({
      id: backendEditRoute.id,
      backendHost: backendEditForm.host.trim(),
      backendPort: port,
      backendScheme: backendEditForm.scheme,
      backendPath: backendEditForm.path.trim() || '/',
    });
    setSubmitting(false);
    if (res.code !== 0) return toast.error(res.msg || '更新后端失败');
    toast.success('后端配置已更新');
    setBackendEditRoute(null);
    loadData();
  };

  const retryCertificate = async (id: number) => {
    const res = await retryManagedCertificate(id);
    if (res.code !== 0) return toast.error(res.msg || '重新申请证书失败');
    toast.success('证书任务已重新开始');
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
        <Button color="primary" startContent={activeView === 'certificates' ? <RefreshCw size={18} /> : <Plus size={18} />} onPress={() => {
          if (activeView === 'domains') setDomainModal(true);
          else if (activeView === 'connectors') setConnectorModal(true);
          else if (activeView === 'certificates') loadData();
          else setServiceModal(true);
        }}>
          {activeView === 'domains' ? '新增域名直达' : activeView === 'connectors' ? '添加接入端' : activeView === 'certificates' ? '刷新证书' : '新建映射'}
        </Button>
      </header>

      <section className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider md:grid-cols-4">
        {[
          ['运行映射', activeCount], ['域名直达', domainRoutes.length], ['HTTPS 证书', certificates.length], ['在线接入端', onlineConnectors],
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
                const stats = telemetry[`service:${service.id}`];
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
                    <div className="mt-4 grid grid-cols-3 gap-px overflow-hidden rounded-md border border-divider bg-divider text-sm">
                      <div className="min-w-0 bg-default-50 px-3 py-2"><div className="text-xs text-default-500">当前连接</div><div className="mt-1 truncate font-medium">{stats?.currentConnections || 0}</div></div>
                      <div className="min-w-0 bg-default-50 px-3 py-2"><div className="text-xs text-default-500">实时速率</div><div className="mt-1 truncate font-medium" title={`上传 ${formatSpeed(stats?.uploadSpeed)} · 下载 ${formatSpeed(stats?.downloadSpeed)}`}>↑ {formatSpeed(stats?.uploadSpeed)} · ↓ {formatSpeed(stats?.downloadSpeed)}</div></div>
                      <div className="min-w-0 bg-default-50 px-3 py-2"><div className="text-xs text-default-500">今日双向</div><div className="mt-1 truncate font-medium">{formatBytes(stats?.todayTotal)}</div></div>
                    </div>
                    {service.lastError && <div className="mt-3 rounded-md border border-danger/25 bg-danger/10 px-3 py-2 text-sm text-danger">{service.lastError}</div>}
                    <div className="mt-4 flex flex-wrap justify-end gap-2 border-t border-divider pt-3">
                      <Button size="sm" variant="light" startContent={<Activity size={15} />} onPress={() => openTelemetry('service', service.id)}>流量详情</Button>
                      {service.state === 'active' && <Button size="sm" variant="flat" color="primary" startContent={<Globe2 size={15} />} onPress={() => bindDomain(service)}>绑定域名</Button>}
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
        <Tab key="domains" title={`域名直达 ${domainRoutes.length}`}>
          {loading ? (
            <div className="flex min-h-64 items-center justify-center"><Spinner /></div>
          ) : domainRoutes.length === 0 ? (
            <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500">
              <Globe2 size={30} />
              <span>暂无域名直达规则</span>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg border border-divider">
              <div className="hidden grid-cols-[1.2fr_1fr_1.2fr_1fr_1fr_auto] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500 lg:grid">
                <span>域名</span><span>公网入口</span><span>后端映射</span><span>实时访问</span><span>状态</span><span>操作</span>
              </div>
              {domainRoutes.map(route => {
                const managedHttps = route.ingressMode === 'managed_https';
                const stats = telemetry[`domain:${route.id}`];
                const status = route.state === 'delete_pending'
                  ? { label: '待删除', color: 'warning' as const, detail: route.lastError || '等待公网节点处理' }
                  : route.certificateState === 'dns_propagating'
                    ? { label: 'DNS 同步中', color: 'primary' as const, detail: route.lastError || '正在等待公共 DNS 读取验证记录' }
                  : route.certificateState === 'renewal_failed'
                    ? { label: '续签异常', color: 'warning' as const, detail: route.lastError || '当前证书仍在使用，面板将自动重试续签' }
                  : ['certificate_pending', 'provisioning'].includes(route.state) && managedHttps
                    ? { label: '申请中', color: 'primary' as const, detail: route.lastError || '正在完成 DNS 验证和证书部署' }
                    : ['certificate_failed', 'deployment_failed'].includes(route.state)
                      ? { label: '配置失败', color: 'danger' as const, detail: route.lastError || '证书申请或部署失败，将自动重试' }
                  : !route.nodeOnline
                    ? { label: '节点离线', color: 'danger' as const, detail: '公网入口节点离线' }
                    : route.backendType === 'direct' && !route.backendNodeOnline
                      ? { label: '后端离线', color: 'danger' as const, detail: '节点本机服务所在节点离线' }
                    : route.mappingState !== 'active'
                      ? { label: '映射不可用', color: 'danger' as const, detail: `后端映射状态：${stateMeta[route.mappingState]?.label || route.mappingState}` }
                      : route.backendType !== 'direct' && !route.connectorOnline
                        ? { label: '接入端离线', color: 'danger' as const, detail: '内网接入端离线' }
                        : route.healthState === 'unhealthy'
                          ? { label: '服务异常', color: 'danger' as const, detail: route.healthError || '完整访问链路健康检查失败' }
                          : { label: '运行中', color: 'success' as const, detail: route.healthState === 'healthy'
                            ? `健康 · ${route.healthStatusCode || '--'} · ${route.healthLatencyMs ?? '--'} ms`
                            : managedHttps ? `HTTPS 有效至 ${formatTime(route.certificateExpiresAt)}` : 'TLS 透传正常' };
                return (
                  <article key={route.id} className="grid gap-3 border-t border-divider px-4 py-4 first:border-t-0 lg:grid-cols-[1.2fr_1fr_1.2fr_1fr_1fr_auto] lg:items-center">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2"><span className="truncate font-medium">{route.name}</span><Chip size="sm" variant="flat" color={managedHttps ? 'success' : 'primary'}>{managedHttps ? '托管 HTTPS' : 'TLS 透传'}</Chip></div>
                      <a className="mt-1 flex min-w-0 items-center gap-1 font-mono text-sm text-primary hover:underline" href={`https://${route.domain}${route.listenPort === 443 ? '' : `:${route.listenPort}`}${managedHttps ? route.pathPrefix || '/' : ''}`} target="_blank" rel="noreferrer">
                        <span className="truncate">{route.domain}{route.listenPort === 443 ? '' : `:${route.listenPort}`}{managedHttps ? route.pathPrefix || '/' : ''}</span><ExternalLink className="shrink-0" size={13} />
                      </a>
                      <div className="mt-1 text-xs text-default-500">{route.ownerRoleId === 1 ? `普通用户 · ${route.ownerUserName}` : '管理员'}</div>
                    </div>
                    <div className="min-w-0 text-sm">
                      <div className="truncate font-mono">{route.domain}:{route.listenPort}</div>
                      <div className="mt-1 truncate text-xs text-default-500">DNS → {route.publicHost || '未配置'}</div>
                    </div>
                    <div className="min-w-0 text-sm">
                      <div className="flex min-w-0 items-center gap-2"><span className="truncate">{route.backendType === 'direct' ? route.backendNodeName || '节点本机服务' : route.mappingName}</span><Chip size="sm" variant="flat" color={route.backendType === 'direct' ? 'secondary' : 'default'}>{route.backendType === 'direct' ? '本机' : '映射'}</Chip></div>
                      <div className="mt-1 truncate font-mono text-xs text-default-500">{route.backendType === 'direct'
                        ? `${route.backendScheme || 'http'}://${route.backendHost}:${route.backendPort}${route.backendPath || '/'}`
                        : `${route.mappingPublicHost || '映射地址不可用'}:${route.mappingPublicPort}`}</div>
                    </div>
                    <div className="min-w-0 text-sm"><div>{stats?.currentConnections || 0} 个连接</div><div className="mt-1 truncate text-xs text-default-500">↑ {formatSpeed(stats?.uploadSpeed)} · ↓ {formatSpeed(stats?.downloadSpeed)}</div></div>
                    <div className="min-w-0"><Chip size="sm" variant="flat" color={status.color}>{status.label}</Chip><div className="mt-1 truncate text-xs text-default-500">{status.detail}</div></div>
                    <div className="flex justify-end gap-1">
                      <Button isIconOnly size="sm" variant="light" aria-label="流量详情" title="流量详情" onPress={() => openTelemetry('domain', route.id)}><Activity size={16} /></Button>
                      {route.backendType === 'direct' && route.state !== 'delete_pending' && <Button isIconOnly size="sm" variant="light" aria-label="编辑后端" title="编辑后端" onPress={() => editDomainBackend(route)}><Pencil size={16} /></Button>}
                      {route.state !== 'delete_pending' && <Button isIconOnly size="sm" variant="light" color="danger" aria-label="删除域名入口" onPress={() => removeDomainRoute(route.id)}><Trash2 size={16} /></Button>}
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </Tab>
        {isAdmin ? (
          <Tab key="certificates" title={`HTTPS 证书 ${certificates.length}`}>
            {loading ? (
              <div className="flex min-h-64 items-center justify-center"><Spinner /></div>
            ) : certificates.length === 0 ? (
              <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500">
                <FileKey2 size={30} />
                <span>暂无托管证书</span>
              </div>
            ) : (
              <div className="overflow-hidden rounded-lg border border-divider">
                <div className="hidden grid-cols-[1.3fr_1fr_1fr_1.1fr_auto] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500 lg:grid">
                  <span>域名</span><span>DNS 配置</span><span>使用情况</span><span>有效期</span><span>操作</span>
                </div>
                {certificates.map(certificate => {
                  const failed = ['failed', 'renewal_failed', 'deployment_failed'].includes(certificate.state);
                  const active = certificate.state === 'active';
                  const status = active
                    ? { label: '有效', color: 'success' as const }
                    : failed
                      ? { label: certificate.state === 'renewal_failed' ? '续签失败' : '申请失败', color: 'danger' as const }
                      : { label: certificate.state === 'dns_propagating' ? 'DNS 同步中' : certificate.state === 'renewing' ? '续签中' : '申请中', color: 'primary' as const };
                  return (
                    <article key={certificate.id} className="grid gap-3 border-t border-divider px-4 py-4 first:border-t-0 lg:grid-cols-[1.3fr_1fr_1fr_1.1fr_auto] lg:items-center">
                      <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><span className="truncate font-mono font-medium">{certificate.domain}</span><Chip size="sm" variant="flat" color={status.color}>{status.label}</Chip></div><div className="mt-1 truncate text-xs text-default-500">{certificate.issuer || '等待签发机构'}</div></div>
                      <div className="min-w-0 text-sm"><div className="truncate">{certificate.zoneName}</div><div className="mt-1 truncate text-xs text-default-500">{certificate.accountName}</div></div>
                      <div className="text-sm"><div>{certificate.routeCount} 条路径规则</div><div className="mt-1 text-xs text-default-500">部署到 {certificate.ingressCount} 个 HTTPS 入口</div></div>
                      <div className="min-w-0 text-sm"><div>{certificate.expiresAt ? formatTime(certificate.expiresAt) : '尚未签发'}</div><div className="mt-1 truncate text-xs text-default-500">{certificate.lastAttemptAt ? `上次处理 ${formatTime(certificate.lastAttemptAt)}` : '等待首次处理'}</div></div>
                      <Button isIconOnly size="sm" variant="light" color={failed ? 'danger' : 'primary'} aria-label="重新申请或续签证书" title="重新申请或续签" onPress={() => retryCertificate(certificate.id)}><RefreshCw size={16} /></Button>
                      {certificate.lastError && <div className="rounded-md border border-danger/25 bg-danger/10 px-3 py-2 text-sm text-danger lg:col-span-5">{certificate.lastError}</div>}
                    </article>
                  );
                })}
              </div>
            )}
          </Tab>
        ) : null}
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

      <Modal isOpen={telemetryLoading || Boolean(telemetryDetail)} onOpenChange={open => !open && setTelemetryDetail(null)} size="3xl" scrollBehavior="inside">
        <ModalContent>
          <ModalHeader className="flex items-center gap-2"><Activity size={19} />连接与流量详情</ModalHeader>
          <ModalBody className="gap-5">
            {telemetryLoading && !telemetryDetail ? <div className="flex min-h-48 items-center justify-center"><Spinner /></div> : telemetryDetail && (
              <>
                <div className="flex flex-wrap items-start justify-between gap-3 border-b border-divider pb-4">
                  <div className="min-w-0"><h3 className="truncate text-lg font-semibold">{telemetryDetail.name}</h3><div className="mt-1 text-sm text-default-500">创建者 · {telemetryDetail.ownerUserName} · {formatTime(telemetryDetail.createdTime)}</div></div>
                  <Chip size="sm" variant="flat" color={telemetryDetail.updatedAt && Date.now() - telemetryDetail.updatedAt < 20000 ? 'success' : 'default'}>{telemetryDetail.updatedAt ? `更新于 ${formatTime(telemetryDetail.updatedAt)}` : '等待 Agent 数据'}</Chip>
                </div>
                {telemetryDetail.sharedIngress && <div className="rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-sm text-warning-700 dark:text-warning-400">该域名与其他域名共用同一 HTTPS 入口。连接数和字节数是入口汇总；下方域名记录用于确认实际访问分布。</div>}
                {telemetryDetail.sharedTotalsHidden && <div className="rounded-md border border-primary/25 bg-primary/10 px-3 py-2 text-sm text-primary">为保护同一入口下其他用户的数据，普通用户不显示共享入口的汇总连接与流量；域名访问记录仍只展示当前域名。</div>}
                <section className="grid grid-cols-2 gap-px overflow-hidden rounded-md border border-divider bg-divider sm:grid-cols-4">
                  <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">当前连接</div><div className="mt-1 text-xl font-semibold">{telemetryDetail.currentConnections}</div></div>
                  <div className="bg-content1 px-4 py-3"><div className="flex items-center gap-1 text-xs text-default-500"><ArrowUp size={13} />上传速度</div><div className="mt-1 text-base font-semibold">{formatSpeed(telemetryDetail.uploadSpeed)}</div></div>
                  <div className="bg-content1 px-4 py-3"><div className="flex items-center gap-1 text-xs text-default-500"><ArrowDown size={13} />下载速度</div><div className="mt-1 text-base font-semibold">{formatSpeed(telemetryDetail.downloadSpeed)}</div></div>
                  <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">失败连接</div><div className="mt-1 text-xl font-semibold">{telemetryDetail.failedConnections}</div></div>
                </section>
                <section>
                  <h4 className="text-sm font-semibold">今日流量</h4>
                  <div className="mt-3 grid grid-cols-3 gap-px overflow-hidden rounded-md border border-divider bg-divider text-sm">
                    <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">上传</div><div className="mt-1 font-medium">{formatBytes(telemetryDetail.todayUpload)}</div></div>
                    <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">下载</div><div className="mt-1 font-medium">{formatBytes(telemetryDetail.todayDownload)}</div></div>
                    <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">双向合计</div><div className="mt-1 font-medium">{formatBytes(telemetryDetail.todayTotal)}</div></div>
                  </div>
                </section>
                <section className="grid gap-5 md:grid-cols-3">
                  <div className="min-w-0"><h4 className="text-sm font-semibold">最近来源地址</h4><div className="mt-3 overflow-hidden rounded-md border border-divider">
                    {(telemetryDetail.sources || []).length === 0 ? <div className="px-4 py-8 text-center text-sm text-default-500">暂无来源样本</div> : telemetryDetail.sources?.map(item => <div key={`${item.sourceKind}:${item.value}`} className="grid grid-cols-[minmax(0,1fr)_auto] gap-3 border-t border-divider px-3 py-2 text-sm first:border-t-0"><div className="min-w-0"><div className="truncate font-mono">{item.value}</div><div className="mt-1 text-xs text-default-500">{item.sourceKind === 'forwarded' ? '转发链保留地址' : item.sourceKind === 'real' ? '已确认真实来源' : 'Agent 可见上一跳'}</div></div><div className="text-right"><div>{item.count} 次</div><div className="mt-1 text-xs text-default-500">{formatTime(item.lastSeen)}</div></div></div>)}
                  </div></div>
                  <div className="min-w-0"><h4 className="text-sm font-semibold">Top 来源</h4><div className="mt-3 overflow-hidden rounded-md border border-divider">
                    {(telemetryDetail.topSources || []).length === 0 ? <div className="px-4 py-8 text-center text-sm text-default-500">暂无来源排行</div> : telemetryDetail.topSources?.map(item => <div key={`top:${item.sourceKind}:${item.value}`} className="grid grid-cols-[minmax(0,1fr)_auto] gap-3 border-t border-divider px-3 py-2 text-sm first:border-t-0"><span className="truncate font-mono">{item.value}</span><span>{item.count} 次</span></div>)}
                  </div></div>
                  <div className="min-w-0"><h4 className="text-sm font-semibold">访问域名</h4><div className="mt-3 overflow-hidden rounded-md border border-divider">
                    {(telemetryDetail.domains || []).length === 0 ? <div className="px-4 py-8 text-center text-sm text-default-500">TCP 映射没有域名信息，或尚无 HTTP/SNI 样本</div> : telemetryDetail.domains?.map(item => <div key={item.value} className="grid grid-cols-[minmax(0,1fr)_auto] gap-3 border-t border-divider px-3 py-2 text-sm first:border-t-0"><span className="truncate font-mono">{item.value}</span><span>{item.count} 次</span></div>)}
                  </div></div>
                </section>
                <div className="border-t border-divider pt-3 text-xs leading-5 text-default-500">上传表示服务向访问端发送的数据，下载表示访问端进入服务的数据。UDP 的连接数按会话超时估算；多级 TCP 转发未保留源地址时只显示 Agent 实际看到的上一跳。</div>
              </>
            )}
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setTelemetryDetail(null)}>关闭</Button></ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={domainModal} onOpenChange={setDomainModal} size="2xl" scrollBehavior="inside">
        <ModalContent>
          <ModalHeader>新增域名直达</ModalHeader>
          <ModalBody className="grid gap-4 sm:grid-cols-2">
            <Tabs className="sm:col-span-2" aria-label="HTTPS 模式" selectedKey={domainForm.ingressMode} onSelectionChange={key => setDomainForm({ ...domainForm, ingressMode: String(key) as 'managed_https' | 'passthrough', backendType: String(key) === 'passthrough' ? 'mapping' : domainForm.backendType })}>
              {isAdmin ? <Tab key="managed_https" title="面板托管 HTTPS" /> : null}
              <Tab key="passthrough" title="TLS 原样透传" />
            </Tabs>
            <Input label="入口名称" value={domainForm.name} onValueChange={value => setDomainForm({ ...domainForm, name: value })} />
            <Input label="访问域名" placeholder="app.example.com" value={domainForm.domain} onValueChange={value => setDomainForm({ ...domainForm, domain: value })} />
            {domainForm.ingressMode === 'managed_https' && (
              <>
                <Select className="sm:col-span-2" label="DNS 与域名配置" placeholder="选择 DNS 与证书所属域名" selectedKeys={domainForm.dnsZoneId ? [domainForm.dnsZoneId] : []} onSelectionChange={keys => setDomainForm({ ...domainForm, dnsZoneId: String(Array.from(keys)[0] || '') })}>
                  {dnsZones.map(zone => <SelectItem key={String(zone.id)} textValue={`${zone.zoneName} ${zone.accountName}`}>{zone.zoneName} · {zone.accountName}</SelectItem>)}
                </Select>
                <Input label="外部访问路径" placeholder="/" value={domainForm.pathPrefix} onValueChange={value => setDomainForm({ ...domainForm, pathPrefix: value })} description="浏览器访问的路径，例如 /。" startContent={<Route size={16} className="text-default-400" />} />
                <Input label="后端根路径" placeholder="/abc123/" value={domainForm.backendPath} onValueChange={value => setDomainForm({ ...domainForm, backendPath: value })} description="请求转给后端时添加的根路径。" startContent={<Route size={16} className="text-default-400" />} />
              </>
            )}
            {isAdmin && domainForm.ingressMode === 'managed_https' && <Tabs className="sm:col-span-2" aria-label="后端来源" selectedKey={domainForm.backendType} onSelectionChange={key => setDomainForm({ ...domainForm, backendType: String(key) as 'mapping' | 'direct', entryNodeId: 'mapping' })}>
              <Tab key="mapping" title="已有内网映射" />
              <Tab key="direct" title="节点本机服务" />
            </Tabs>}
            {domainForm.backendType === 'mapping' ? <Select className="sm:col-span-2" label="后端内网映射" selectedKeys={domainForm.publishedServiceId ? [domainForm.publishedServiceId] : []} onSelectionChange={keys => setDomainForm({ ...domainForm, publishedServiceId: String(Array.from(keys)[0] || '') })}>
                {services.filter(item => item.state === 'active').map(item => (
                  <SelectItem key={String(item.id)} textValue={`${item.name} ${item.publicHost}:${item.publicPort}`}>
                    {item.name} · {item.publicHost}:{item.publicPort} · {item.connectorOnline ? '接入端在线' : '接入端离线'}
                  </SelectItem>
                ))}
              </Select> : <>
                <Select className="sm:col-span-2" label="服务所在节点" selectedKeys={selectedBackendNodeKeys} onSelectionChange={keys => { const value = String(Array.from(keys)[0] || ''); setDomainForm({ ...domainForm, backendNodeId: value, entryNodeId: value || 'mapping' }); }}>
                  {entryNodes.map(node => <SelectItem key={String(node.id)} textValue={`${node.name} ${node.serverIp || node.ip || ''}`}>{node.name} · {node.serverIp || node.ip || '地址未知'} · {node.status === 1 ? '在线' : '离线'}</SelectItem>)}
                </Select>
                <Select label="后端协议" selectedKeys={[domainForm.backendScheme]} onSelectionChange={keys => setDomainForm({ ...domainForm, backendScheme: String(Array.from(keys)[0] || 'http') as 'http' | 'https' })}>
                  <SelectItem key="http">HTTP</SelectItem><SelectItem key="https">HTTPS</SelectItem>
                </Select>
                <Input label="监听地址" value={domainForm.backendHost} onValueChange={value => setDomainForm({ ...domainForm, backendHost: value })} description="本机服务常见为 127.0.0.1 或 0.0.0.0。" />
                <Input className="sm:col-span-2" label="后端端口" type="number" min={1} max={65535} value={domainForm.backendPort} onValueChange={value => setDomainForm({ ...domainForm, backendPort: value })} />
              </>}
            {isAdmin && domainForm.ingressMode === 'managed_https' && (
              <Select className="sm:col-span-2" label="HTTPS 入口节点" description="入口节点负责监听 443；后端映射可以位于另一台服务器。入口端口被占用时请选择其他在线节点。" selectedKeys={selectedEntryNodeKeys} onSelectionChange={keys => setDomainForm({ ...domainForm, entryNodeId: String(Array.from(keys)[0] || 'mapping') })}>
                {[{ id: 'mapping', name: domainForm.backendType === 'direct' ? '跟随服务所在节点' : '跟随后端映射节点', address: '', online: true }, ...entryNodes.map(node => ({ id: String(node.id), name: node.name, address: node.serverIp || node.ip || '地址未知', online: node.status === 1 }))].map(node => (
                  <SelectItem key={node.id} textValue={`${node.name} ${node.address}`}>
                    {node.name}{node.address ? ` · ${node.address}` : ''} · {node.online ? '在线' : '离线'}
                  </SelectItem>
                ))}
              </Select>
            )}
            <Input label={domainForm.ingressMode === 'managed_https' ? 'HTTPS 监听端口' : 'TLS 监听端口'} description={domainForm.listenPort === '443' ? '使用 443 后，访问地址无需填写端口。' : '非 443 端口仍需在域名后填写端口。'} type="number" min={1} max={65535} value={domainForm.listenPort} onValueChange={value => setDomainForm({ ...domainForm, listenPort: value })} />
            <div className="rounded-md border border-divider bg-default-100 px-4 py-3 text-sm">
              <div className="text-xs text-default-500">DNS 解析目标</div>
              <div className="mt-1 truncate font-mono">{selectedEntryNode?.serverIp || selectedEntryNode?.ip || selectedDomainMapping?.publicHost || '选择后端映射后显示'}</div>
            </div>
            <div className="sm:col-span-2 grid gap-px overflow-hidden rounded-md border border-divider bg-divider sm:grid-cols-2">
              <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">入口方式</div><div className="mt-1 text-sm">{domainForm.ingressMode === 'managed_https' ? 'Agent 终止 HTTPS · 按域名和路径分流' : 'TLS 原样透传 · 仅按域名分流'}</div></div>
              <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">证书管理</div><div className="mt-1 text-sm">{domainForm.ingressMode === 'managed_https' ? 'Let’s Encrypt 自动签发与续期' : '由内网服务负责'}</div></div>
            </div>
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setDomainModal(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submitDomainRoute}>创建域名直达</Button></ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={Boolean(backendEditRoute)} onOpenChange={open => !open && setBackendEditRoute(null)} size="lg">
        <ModalContent>
          <ModalHeader>编辑节点服务后端</ModalHeader>
          <ModalBody className="grid gap-4 sm:grid-cols-2">
            <Select label="后端协议" selectedKeys={[backendEditForm.scheme]} onSelectionChange={keys => setBackendEditForm({ ...backendEditForm, scheme: String(Array.from(keys)[0] || 'http') as 'http' | 'https' })}>
              <SelectItem key="http">HTTP</SelectItem><SelectItem key="https">HTTPS</SelectItem>
            </Select>
            <Input label="后端端口" type="number" min={1} max={65535} value={backendEditForm.port} onValueChange={port => setBackendEditForm({ ...backendEditForm, port })} />
            <Input className="sm:col-span-2" label="监听地址" value={backendEditForm.host} onValueChange={host => setBackendEditForm({ ...backendEditForm, host })} description="同节点通配监听可填写 0.0.0.0 或 ::。" />
            <Input className="sm:col-span-2" label="后端根路径" value={backendEditForm.path} onValueChange={path => setBackendEditForm({ ...backendEditForm, path })} placeholder="/abc123/" />
            <div className="sm:col-span-2 border-y border-divider py-3 text-sm text-default-500">保留现有域名、DNS 和 HTTPS 证书，只重新下发后端连接配置。</div>
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setBackendEditRoute(null)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submitDomainBackend}>保存并应用</Button></ModalFooter>
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
