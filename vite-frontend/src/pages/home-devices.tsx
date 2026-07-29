import { useEffect, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Copy, Download, Laptop, Plus, RefreshCw } from 'lucide-react';
import toast from 'react-hot-toast';

import AccessResourceTabs from '@/components/access-resource-tabs';
import {
  createInternalConnector,
  getInternalConnectorInstall,
  getInternalConnectors,
  type ConnectorPlatform,
  type InternalConnector,
} from '@/api';

const platformLabel: Record<ConnectorPlatform, string> = {
  linux: 'Linux',
  windows: 'Windows',
  macos: 'macOS',
};

const formatTime = (value?: number) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '尚未连接';

export default function HomeDevicesPage() {
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
            <div className="mt-5 flex justify-end gap-2">
              <Button size="sm" variant="flat" startContent={<Download size={15} />} onPress={() => showCommand(device.id, device.platform, 'install')}>{device.online ? '安装命令' : '重新安装'}</Button>
              <Button size="sm" variant="flat" onPress={() => showCommand(device.id, device.platform, 'uninstall')}>卸载命令</Button>
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
    </div>
  );
}
