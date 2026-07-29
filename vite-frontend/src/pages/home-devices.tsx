import { useEffect, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { Tooltip } from '@heroui/tooltip';
import { ArrowRight, Copy, Download, Laptop, Network, Plus, Radar, RefreshCw, ShieldAlert, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';
import { useNavigate } from 'react-router-dom';

import AccessResourceTabs from '@/components/access-resource-tabs';
import {
  createInternalConnector,
  clearLanDiscoveryResults,
  deleteInternalConnector,
  getLanDiscoveryResults,
  getInternalConnectorInstall,
  getInternalConnectors,
  scanLanServices,
  setLanDiscoveryEnabled,
  type ConnectorPlatform,
  type InternalConnector,
  type LanDiscoveryResult,
  type LanDiscoveredService,
} from '@/api';

const platformLabel: Record<ConnectorPlatform, string> = {
  linux: 'Linux',
  windows: 'Windows',
  macos: 'macOS',
};

const formatTime = (value?: number) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '尚未连接';

const versionAtLeast = (version?: string, minimum = '2.32.0') => {
  const current = String(version || '').replace(/^v/, '').split('.').map(part => Number.parseInt(part, 10) || 0);
  const required = minimum.split('.').map(part => Number.parseInt(part, 10) || 0);
  return required.every((part, index) => (current[index] || 0) === part || (current[index] || 0) > part
    || current.slice(0, index).some((value, position) => value > required[position]));
};

const discoveryStatusMeta = {
  disabled: { label: '发现已关闭', color: 'default' as const },
  idle: { label: '等待扫描', color: 'default' as const },
  scanning: { label: '正在扫描', color: 'primary' as const },
  complete: { label: '扫描完成', color: 'success' as const },
  failed: { label: '扫描失败', color: 'danger' as const },
};

const getDiscoveryStatusMeta = (status?: string) => discoveryStatusMeta[
  status && status in discoveryStatusMeta ? status as keyof typeof discoveryStatusMeta : 'disabled'
];

export default function HomeDevicesPage() {
  const navigate = useNavigate();
  const [devices, setDevices] = useState<InternalConnector[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [commandLoading, setCommandLoading] = useState(false);
  const [command, setCommand] = useState('');
  const [commandDeviceId, setCommandDeviceId] = useState<number | null>(null);
  const [commandPlatform, setCommandPlatform] = useState<ConnectorPlatform>('linux');
  const [commandAction, setCommandAction] = useState<'install' | 'uninstall'>('install');
  const [discoveryOpen, setDiscoveryOpen] = useState(false);
  const [discoveryDevice, setDiscoveryDevice] = useState<InternalConnector | null>(null);
  const [discovery, setDiscovery] = useState<LanDiscoveryResult | null>(null);
  const [discoveryLoading, setDiscoveryLoading] = useState(false);
  const [scanLoading, setScanLoading] = useState(false);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [deleteDevice, setDeleteDevice] = useState<InternalConnector | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [scanCidr, setScanCidr] = useState('');
  const [form, setForm] = useState<{ name: string; platform: ConnectorPlatform; allowedCidrs: string }>({
    name: '', platform: 'linux', allowedCidrs: '',
  });

  const load = async () => {
    setLoading(true);
    const response = await getInternalConnectors();
    if (response.code === 0) setDevices(response.data || []);
    else toast.error(response.msg || '读取家庭设备失败');
    setLoading(false);
  };

  useEffect(() => { void load(); }, []);

  const showCommand = async (id: number, platform: ConnectorPlatform, action: 'install' | 'uninstall') => {
    setCommandDeviceId(id);
    setCommandPlatform(platform);
    setCommandAction(action);
    setCommandOpen(true);
    setCommandLoading(true);
    const response = await getInternalConnectorInstall(id, platform, action);
    setCommandLoading(false);
    if (response.code !== 0) return toast.error(response.msg || '获取命令失败');
    setCommand(response.data);
  };

  const switchCommand = async (platform: ConnectorPlatform, action: 'install' | 'uninstall') => {
    if (commandDeviceId === null) return;
    await showCommand(commandDeviceId, platform, action);
  };

  const createDevice = async () => {
    if (!form.name.trim()) return toast.error('请输入设备名称');
    setSubmitting(true);
    const response = await createInternalConnector({
      name: form.name.trim(), platform: form.platform,
      allowedCidrs: form.allowedCidrs.trim() || undefined,
    });
    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || '创建家庭设备失败');
    setCreateOpen(false);
    setForm({ name: '', platform: 'linux', allowedCidrs: '' });
    setCommandDeviceId(response.data.connector.id);
    setCommandPlatform(response.data.connector.platform || form.platform);
    setCommandAction('install');
    setCommand(response.data.installCommand);
    setCommandOpen(true);
    await load();
  };

  const copyCommand = async () => {
    await navigator.clipboard.writeText(command);
    toast.success(`${commandAction === 'install' ? '安装' : '卸载'}命令已复制`);
  };

  const openDiscovery = async (device: InternalConnector) => {
    setDiscoveryDevice(device);
    setDiscoveryOpen(true);
    setDiscoveryLoading(true);
    setScanCidr('');
    const response = await getLanDiscoveryResults(device.id);
    setDiscoveryLoading(false);
    if (response.code !== 0) return toast.error(response.msg || '读取服务发现结果失败');
    setDiscovery(response.data);
  };

  const toggleDiscovery = async (enabled: boolean) => {
    if (!discoveryDevice) return;
    setSettingsLoading(true);
    const response = await setLanDiscoveryEnabled(discoveryDevice.id, enabled);
    setSettingsLoading(false);
    if (response.code !== 0) return toast.error(response.msg || '修改服务发现设置失败');
    setDiscovery(current => ({ ...(current || response.data), ...response.data, services: current?.services || [] }));
    setDevices(current => current.map(item => item.id === discoveryDevice.id
      ? { ...item, discoveryEnabled: enabled ? 1 : 0, discoveryStatus: enabled ? item.discoveryStatus === 'disabled' ? 'idle' : item.discoveryStatus : 'disabled' }
      : item));
    toast.success(enabled ? '已开启局域网服务发现' : '已关闭局域网服务发现');
  };

  const runDiscovery = async () => {
    if (!discoveryDevice) return;
    setScanLoading(true);
    const response = await scanLanServices(discoveryDevice.id, scanCidr.trim() || 'auto');
    setScanLoading(false);
    if (response.code !== 0) {
      setDiscovery(current => current ? { ...current, status: 'failed', lastError: response.msg } : current);
      return toast.error(response.msg || '局域网扫描失败');
    }
    setDiscovery(response.data);
    setDevices(current => current.map(item => item.id === discoveryDevice.id ? {
      ...item,
      discoveryEnabled: 1,
      discoveryStatus: 'complete',
      discoveryLastScanAt: response.data.lastScanAt,
      discoveryLastCidr: response.data.lastCidr,
      discoveryLastError: undefined,
      discoveredServiceCount: response.data.services.length,
    } : item));
    toast.success(`发现 ${response.data.services.length} 个可访问服务`);
  };

  const clearDiscovery = async () => {
    if (!discoveryDevice) return;
    const response = await clearLanDiscoveryResults(discoveryDevice.id);
    if (response.code !== 0) return toast.error(response.msg || '清空候选服务失败');
    setDiscovery(current => current ? { ...current, services: [] } : current);
    setDevices(current => current.map(item => item.id === discoveryDevice.id ? { ...item, discoveredServiceCount: 0 } : item));
    toast.success('候选服务已清空');
  };

  const confirmDeleteDevice = async () => {
    if (!deleteDevice) return;
    setDeleting(true);
    const response = await deleteInternalConnector(deleteDevice.id);
    setDeleting(false);
    if (response.code !== 0) return toast.error(response.msg || '删除家庭设备失败');
    setDevices(current => current.filter(item => item.id !== deleteDevice.id));
    setDeleteDevice(null);
    toast.success('家庭设备记录已删除');
  };

  const publishDiscoveredService = (service: LanDiscoveredService) => {
    if (!discoveryDevice) return;
    const query = new URLSearchParams({
      connectorId: String(discoveryDevice.id),
      targetHost: service.host,
      targetPort: String(service.port),
      serviceName: service.title || service.serviceName,
      serviceType: service.serviceType,
    });
    setDiscoveryOpen(false);
    navigate(`/service-publishing?${query.toString()}`);
  };

  return (
    <div className="mx-auto w-full max-w-[1500px] space-y-5 p-4 md:p-6">
      <AccessResourceTabs />
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div><p className="text-sm text-default-500">资源中心</p><h1 className="mt-1 text-2xl font-semibold">家庭设备</h1></div>
        <div className="flex gap-2">
          <Button isIconOnly variant="flat" aria-label="刷新家庭设备" onPress={load}><RefreshCw size={17} /></Button>
          <Button color="primary" startContent={<Plus size={17} />} onPress={() => setCreateOpen(true)}>添加设备</Button>
        </div>
      </header>

      <section className="grid grid-cols-2 gap-px overflow-hidden rounded-md border border-divider bg-divider sm:grid-cols-3">
        {[
          ['设备总数', devices.length],
          ['在线设备', devices.filter(item => item.online).length],
          ['离线设备', devices.filter(item => !item.online).length],
        ].map(([label, value]) => <div key={String(label)} className="bg-content1 px-4 py-4"><div className="text-xs text-default-500">{label}</div><div className="mt-1 text-xl font-semibold">{value}</div></div>)}
      </section>

      {loading ? <div className="flex min-h-56 items-center justify-center"><Spinner /></div> : devices.length === 0 ? (
        <div className="flex min-h-56 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><Laptop size={30} /><span>尚未添加家庭设备</span></div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {devices.map(device => <article key={device.id} className="rounded-md border border-divider bg-content1 p-5">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0"><h2 className="truncate text-base font-semibold">{device.name}</h2><p className="mt-1 text-xs text-default-500">{device.ownerUserName || '当前用户'} · {platformLabel[device.platform] || device.platform}</p></div>
              <Chip size="sm" variant="flat" color={device.online ? 'success' : 'danger'}>{device.online ? '在线' : '离线'}</Chip>
            </div>
            <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2">
              <div><dt className="text-default-500">Agent 版本</dt><dd className="mt-1 font-medium">{device.version || '尚未上报'}</dd></div>
              <div><dt className="text-default-500">最近连接</dt><dd className="mt-1 font-medium">{formatTime(device.lastSeen)}</dd></div>
              <div><dt className="text-default-500">远端地址</dt><dd className="mt-1 break-all font-mono text-xs">{device.remoteIp || '尚未上报'}</dd></div>
              <div><dt className="text-default-500">允许网段</dt><dd className="mt-1 break-all font-mono text-xs">{device.allowedCidrs || '默认网段'}</dd></div>
            </dl>
            <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-divider pt-4">
              <Chip size="sm" variant="flat" color={getDiscoveryStatusMeta(device.discoveryStatus).color}>
                {device.discoveryEnabled ? `${device.discoveredServiceCount || 0} 个候选服务` : '服务发现已关闭'}
              </Chip>
              <div className="ml-auto flex flex-wrap justify-end gap-2">
              <Button size="sm" variant="flat" startContent={<Radar size={15} />} onPress={() => openDiscovery(device)}>发现服务</Button>
              <Button size="sm" variant="flat" startContent={<Download size={15} />} onPress={() => showCommand(device.id, device.platform, 'install')}>{device.online ? '安装命令' : '重新安装'}</Button>
              <Button size="sm" variant="flat" onPress={() => showCommand(device.id, device.platform, 'uninstall')}>卸载命令</Button>
              <Tooltip content="删除设备记录">
                <Button isIconOnly size="sm" color="danger" variant="flat" aria-label={`删除 ${device.name} 设备记录`} onPress={() => setDeleteDevice(device)}><Trash2 size={15} /></Button>
              </Tooltip>
              </div>
            </div>
          </article>)}
        </div>
      )}

      <Modal isOpen={createOpen} onOpenChange={setCreateOpen} size="xl">
        <ModalContent><ModalHeader>添加家庭设备</ModalHeader><ModalBody className="space-y-4">
          <Input label="设备名称" placeholder="家里 Windows 电脑" value={form.name} onValueChange={name => setForm({ ...form, name })} />
          <Select label="操作系统" selectedKeys={[form.platform]} onSelectionChange={keys => setForm({ ...form, platform: String(Array.from(keys)[0] || 'linux') as ConnectorPlatform })}>
            <SelectItem key="linux">Linux · amd64 / arm64</SelectItem>
            <SelectItem key="windows">Windows · amd64 / arm64</SelectItem>
            <SelectItem key="macos">macOS · Intel / Apple Silicon</SelectItem>
          </Select>
          <Input label="允许访问的家庭网段（可选）" placeholder="留空使用本机和常见内网网段" value={form.allowedCidrs} onValueChange={allowedCidrs => setForm({ ...form, allowedCidrs })} />
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setCreateOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={createDevice}>生成安装命令</Button></ModalFooter></ModalContent>
      </Modal>

      <Modal isOpen={commandOpen} onOpenChange={setCommandOpen} size="2xl">
        <ModalContent><ModalHeader>{commandAction === 'install' ? '安装' : '卸载'}家庭 Agent</ModalHeader><ModalBody className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {(['linux', 'windows', 'macos'] as ConnectorPlatform[]).map(platform => <Button key={platform} size="sm" color={commandPlatform === platform ? 'primary' : 'default'} variant={commandPlatform === platform ? 'solid' : 'flat'} onPress={() => switchCommand(platform, commandAction)}>{platformLabel[platform]}</Button>)}
            <div className="ml-auto flex gap-2"><Button size="sm" color={commandAction === 'install' ? 'primary' : 'default'} variant={commandAction === 'install' ? 'solid' : 'flat'} onPress={() => switchCommand(commandPlatform, 'install')}>安装</Button><Button size="sm" color={commandAction === 'uninstall' ? 'danger' : 'default'} variant={commandAction === 'uninstall' ? 'solid' : 'flat'} onPress={() => switchCommand(commandPlatform, 'uninstall')}>卸载</Button></div>
          </div>
          <div className="rounded-md bg-default-100 p-4">{commandLoading ? <Spinner size="sm" /> : <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all font-mono text-sm">{command}</pre>}</div>
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setCommandOpen(false)}>关闭</Button><Button color="primary" startContent={<Copy size={16} />} isDisabled={!command || commandLoading} onPress={copyCommand}>复制命令</Button></ModalFooter></ModalContent>
      </Modal>

      <Modal isOpen={Boolean(deleteDevice)} onOpenChange={open => !open && !deleting && setDeleteDevice(null)} size="lg">
        <ModalContent><ModalHeader>删除家庭设备记录</ModalHeader><ModalBody className="space-y-4">
          <div className="rounded-md border border-divider bg-default-50 px-4 py-3 text-sm">
            <div className="font-medium">{deleteDevice?.name}</div>
            <div className="mt-1 text-default-500">Agent {deleteDevice?.version || '尚未上报'} · {deleteDevice?.online ? '在线' : '离线'}</div>
          </div>
          <p className="text-sm text-default-600">此操作只删除面板记录，不会远程卸载 Agent。设备必须离线，并且不能被内网映射或家庭网络中转使用。</p>
          {deleteDevice?.online && <div className="rounded-md border border-danger/30 bg-danger/10 px-4 py-3 text-sm text-danger">设备仍然在线。请先执行卸载命令，等待设备离线后再删除记录。</div>}
        </ModalBody><ModalFooter><Button variant="flat" isDisabled={deleting} onPress={() => setDeleteDevice(null)}>取消</Button><Button color="danger" isLoading={deleting} isDisabled={Boolean(deleteDevice?.online)} onPress={confirmDeleteDevice}>删除记录</Button></ModalFooter></ModalContent>
      </Modal>

      <Modal isOpen={discoveryOpen} onOpenChange={setDiscoveryOpen} size="4xl" scrollBehavior="inside">
        <ModalContent><ModalHeader>{discoveryDevice?.name || '家庭设备'} · 局域网服务发现</ModalHeader><ModalBody className="space-y-5">
          {discoveryLoading ? <div className="flex min-h-48 items-center justify-center"><Spinner /></div> : discovery && discoveryDevice ? <>
            <section className="grid gap-px overflow-hidden rounded-md border border-divider bg-divider sm:grid-cols-3">
              <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">发现状态</div><div className="mt-2"><Chip size="sm" variant="flat" color={getDiscoveryStatusMeta(discovery.status).color}>{getDiscoveryStatusMeta(discovery.status).label}</Chip></div></div>
              <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">候选服务</div><div className="mt-1 text-xl font-semibold">{discovery.services.length}</div></div>
              <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">最近扫描</div><div className="mt-1 text-sm font-medium">{formatTime(discovery.lastScanAt)}</div></div>
            </section>

            <section className="grid gap-4 border-y border-divider py-5 lg:grid-cols-[minmax(0,1fr)_220px] lg:items-end">
              <div className="space-y-4">
                <Switch isSelected={discovery.enabled} isDisabled={settingsLoading} onValueChange={toggleDiscovery}>启用局域网服务发现</Switch>
                <Input label="扫描网段" placeholder="自动识别，或填写 192.168.100.0/24" value={scanCidr} onValueChange={setScanCidr} description="仅允许当前家庭设备授权范围内的 IPv4 私网；单次范围不能大于 /24。" startContent={<Network size={16} className="text-default-400" />} />
              </div>
              <Button color="primary" startContent={<Radar size={17} />} isLoading={scanLoading} isDisabled={!discovery.enabled || !discoveryDevice.online || !versionAtLeast(discoveryDevice.version)} onPress={runDiscovery}>开始扫描</Button>
            </section>

            {!discoveryDevice.online && <div className="rounded-md border border-warning/30 bg-warning/10 px-4 py-3 text-sm text-warning-700 dark:text-warning-400">家庭设备当前离线，恢复在线后才能扫描。</div>}
            {discoveryDevice.online && !versionAtLeast(discoveryDevice.version) && <div className="rounded-md border border-warning/30 bg-warning/10 px-4 py-3 text-sm text-warning-700 dark:text-warning-400">服务发现需要 Agent 2.32.0 或更高版本，请先使用安装命令更新该设备。</div>}
            {discovery.lastError && <div className="rounded-md border border-danger/30 bg-danger/10 px-4 py-3 text-sm text-danger">{discovery.lastError}</div>}

            <section>
              <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
                <div><h3 className="text-sm font-semibold">发现结果</h3>{discovery.lastCidr && <p className="mt-1 text-xs text-default-500">扫描范围：{discovery.lastCidr}</p>}</div>
                <Button size="sm" variant="light" color="danger" startContent={<Trash2 size={15} />} isDisabled={discovery.services.length === 0} onPress={clearDiscovery}>清空结果</Button>
              </div>
              {discovery.services.length === 0 ? <div className="flex min-h-36 flex-col items-center justify-center gap-2 border-y border-divider text-default-500"><Radar size={26} /><span className="text-sm">暂无候选服务</span></div> : <div className="grid gap-3 lg:grid-cols-2">
                {discovery.services.map(service => <article key={`${service.host}:${service.port}`} className="rounded-md border border-divider bg-content1 p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h4 className="font-medium">{service.title || service.serviceName}</h4>{service.sensitive === 1 && <Chip size="sm" color="warning" variant="flat" startContent={<ShieldAlert size={13} />}>敏感服务</Chip>}</div><p className="mt-1 truncate font-mono text-sm text-default-600">{service.host}:{service.port}</p></div>
                    <Chip size="sm" variant="flat" color={service.confidence === 'high' ? 'success' : 'default'}>{service.confidence === 'high' ? '已识别' : '端口推测'}</Chip>
                  </div>
                  <div className="mt-3 min-h-5 truncate text-xs text-default-500">{service.product || service.serviceName}</div>
                  <div className="mt-3 flex justify-end"><Button size="sm" color="primary" variant="flat" endContent={<ArrowRight size={15} />} onPress={() => publishDiscoveredService(service)}>创建映射</Button></div>
                </article>)}
              </div>}
            </section>
          </> : null}
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setDiscoveryOpen(false)}>关闭</Button></ModalFooter></ModalContent>
      </Modal>
    </div>
  );
}
