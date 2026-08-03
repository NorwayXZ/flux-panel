import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { Archive, Boxes, Copy, ExternalLink, Play, RefreshCw, RotateCcw, Search, Square, Trash2, UploadCloud } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  deployDockerApp,
  getDnsZoneOptions,
  getDockerAppCommand,
  getDockerAppEvents,
  getDockerAppOverview,
  inspectDockerNode,
  runDockerAppAction,
  type DockerAppEvent,
  type DockerAppInstance,
  type DockerAppOverview,
  type DockerContainerInfo,
  type DnsZoneOption,
} from '@/api';

const empty: DockerAppOverview = {
  nodes: [], templates: [], apps: [], summary: { apps: 0, active: 0, errors: 0, dockerReadyNodes: 0 }, minimumAgentVersion: '2.47.0',
};

const stateMeta = (state: string) => {
  if (state === 'active') return { label: '运行中', color: 'success' as const };
  if (state === 'error') return { label: '异常', color: 'danger' as const };
  if (state === 'delete_pending') return { label: '删除中', color: 'warning' as const };
  if (state === 'operating' || state === 'provisioning') return { label: '处理中', color: 'primary' as const };
  return { label: state || '未知', color: 'default' as const };
};
const timeText = (value?: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';

export default function DockerAppsPage() {
  const [data, setData] = useState<DockerAppOverview>(empty);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [inspectOpen, setInspectOpen] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [eventsOpen, setEventsOpen] = useState(false);
  const [containers, setContainers] = useState<DockerContainerInfo[]>([]);
  const [events, setEvents] = useState<DockerAppEvent[]>([]);
  const [dnsZones, setDnsZones] = useState<DnsZoneOption[]>([]);
  const [command, setCommand] = useState('');
  const [form, setForm] = useState({
    nodeId: '', templateId: '', name: '', containerName: '', hostPort: '',
    bindDomain: false, domain: '', dnsZoneId: '', entryNodeId: '', listenPort: '443', pathPrefix: '/', backendPath: '/',
  });

  const selectedTemplate = useMemo(() => data.templates.find(item => item.id === form.templateId), [data.templates, form.templateId]);

  const load = async (quiet = false) => {
    if (!quiet) setLoading(true);
    const [response, zones] = await Promise.all([getDockerAppOverview(), getDnsZoneOptions()]);
    if (!quiet) setLoading(false);
    if (response.code === 0) setData(response.data);
    else if (!quiet) toast.error(response.msg || '加载 Docker 应用中心失败');
    if (zones.code === 0) setDnsZones(zones.data || []);
  };
  useEffect(() => { void load(); }, []);

  const openCreate = () => {
    const template = data.templates[0];
    const node = data.nodes.find(item => item.online && item.compatible) || data.nodes[0];
    setForm({
      nodeId: node ? String(node.id) : '',
      templateId: template?.id || '',
      name: template?.name || '',
      containerName: template ? `flux-${template.id}` : '',
      hostPort: template ? String(template.defaultHostPort) : '',
      bindDomain: false,
      domain: '',
      dnsZoneId: '',
      entryNodeId: node ? String(node.id) : '',
      listenPort: '443',
      pathPrefix: '/',
      backendPath: '/',
    });
    setCreateOpen(true);
  };

  const deploy = async () => {
    if (!form.nodeId || !form.templateId || !form.name.trim()) return toast.error('请选择节点、应用模板并填写名称');
    if (form.bindDomain && (!form.domain.trim() || !form.dnsZoneId)) return toast.error('请填写访问域名并选择 DNS 域名配置');
    setBusy('deploy');
    const response = await deployDockerApp({
      nodeId: Number(form.nodeId), templateId: form.templateId, name: form.name.trim(),
      containerName: form.containerName.trim() || undefined,
      hostPort: form.hostPort ? Number(form.hostPort) : undefined,
      bindDomain: form.bindDomain,
      domain: form.bindDomain ? form.domain.trim() : undefined,
      dnsZoneId: form.bindDomain ? Number(form.dnsZoneId) : undefined,
      entryNodeId: form.bindDomain && form.entryNodeId ? Number(form.entryNodeId) : undefined,
      listenPort: form.bindDomain ? Number(form.listenPort || 443) : undefined,
      pathPrefix: form.bindDomain ? form.pathPrefix || '/' : undefined,
      backendPath: form.bindDomain ? form.backendPath || '/' : undefined,
    });
    setBusy('');
    if (response.code !== 0) return toast.error(response.msg || '部署失败');
    setData(response.data); setCreateOpen(false); toast.success('部署任务已提交');
  };

  const inspect = async (nodeId: number) => {
    setBusy(`inspect-${nodeId}`);
    const response = await inspectDockerNode(nodeId);
    setBusy('');
    if (response.code !== 0) return toast.error(response.msg || 'Docker 检查失败');
    setContainers(response.data.containers || []);
    setInspectOpen(true);
  };

  const action = async (app: DockerAppInstance, name: 'upgrade' | 'backup' | 'stop' | 'start' | 'remove' | 'rollback') => {
    setBusy(`${name}-${app.id}`);
    const response = await runDockerAppAction(app.id, name);
    setBusy('');
    if (response.code !== 0) return toast.error(response.msg || '操作失败');
    setData(response.data);
    toast.success('操作已提交');
  };

  const showCommand = async (app: DockerAppInstance, name: 'upgrade' | 'backup' | 'stop' | 'start' | 'remove' | 'rollback') => {
    setBusy(`cmd-${app.id}`);
    const response = await getDockerAppCommand(app.id, name);
    setBusy('');
    if (response.code !== 0) return toast.error(response.msg || '生成命令失败');
    setCommand(response.data); setCommandOpen(true);
  };

  const showEvents = async (app: DockerAppInstance) => {
    setBusy(`events-${app.id}`);
    const response = await getDockerAppEvents(app.id);
    setBusy('');
    if (response.code !== 0) return toast.error(response.msg || '加载事件失败');
    setEvents(response.data || []); setEventsOpen(true);
  };

  const copy = async () => {
    await navigator.clipboard.writeText(command);
    toast.success('已复制命令');
  };

  if (loading) return <div className="flex min-h-[50vh] items-center justify-center"><Spinner label="加载 Docker 应用中心" /></div>;

  return <div className="mx-auto w-full max-w-[1500px] space-y-6 p-4 sm:p-6">
    <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div><p className="text-sm text-default-500">接入与发布</p><h1 className="mt-1 text-xl font-semibold sm:text-2xl">Docker 应用中心</h1><p className="mt-2 text-sm text-default-500">识别节点 Docker，一键部署常用应用，并保留升级、备份、迁移和回退操作记录。</p></div>
      <div className="flex gap-2"><Button isIconOnly variant="flat" title="刷新" onPress={() => void load()}><RefreshCw size={17} /></Button><Button color="primary" startContent={<UploadCloud size={17} />} onPress={openCreate}>部署应用</Button></div>
    </header>

    <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {[
        ['应用数量', data.summary.apps], ['运行中', data.summary.active], ['异常', data.summary.errors], ['可部署节点', data.summary.dockerReadyNodes],
      ].map(([label, value]) => <div key={String(label)} className="border border-divider bg-content1 p-4"><p className="text-sm text-default-500">{label}</p><p className="mt-2 text-2xl font-semibold">{value}</p></div>)}
    </section>

    <section className="grid gap-4 xl:grid-cols-[360px_1fr]">
      <div className="space-y-3">
        <h2 className="text-base font-semibold">Docker 节点</h2>
        {data.nodes.map(node => <div key={node.id} className="border border-divider bg-content1 p-4">
          <div className="flex items-start justify-between gap-3"><div className="min-w-0"><div className="flex items-center gap-2"><h3 className="truncate font-medium">{node.name}</h3><Chip size="sm" color={node.online ? 'success' : 'default'} variant="flat">{node.online ? '在线' : '离线'}</Chip></div><p className="mt-1 truncate text-xs text-default-400">Agent {node.version || '-'} · 要求 {data.minimumAgentVersion}+</p></div><Button isIconOnly size="sm" variant="light" title="识别 Docker" isLoading={busy === `inspect-${node.id}`} isDisabled={!node.online} onPress={() => void inspect(node.id)}><Search size={16} /></Button></div>
          {!node.compatible && <p className="mt-3 border-t border-divider pt-3 text-xs text-warning-600">需要升级 Agent 后才能直接执行 Docker 应用动作；页面仍会提供手动命令。</p>}
        </div>)}
      </div>

      <div className="space-y-3">
        <h2 className="text-base font-semibold">已部署应用</h2>
        {data.apps.length === 0 ? <div className="flex min-h-64 flex-col items-center justify-center gap-3 border border-dashed border-divider text-default-400"><Boxes size={32} /><p>还没有 Docker 应用</p></div> : <div className="grid gap-4 lg:grid-cols-2">{data.apps.map(app => {
          const meta = stateMeta(app.state);
          return <article key={app.id} className="border border-divider bg-content1 p-4">
            <div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h3 className="font-semibold">{app.name}</h3><Chip size="sm" color={meta.color} variant="flat">{meta.label}</Chip><Chip size="sm" variant="flat">{app.templateId}</Chip></div><p className="mt-1 break-all text-xs text-default-400">{app.nodeName || app.nodeId} · {app.containerName}</p></div><Button isIconOnly size="sm" variant="light" title="事件" isLoading={busy === `events-${app.id}`} onPress={() => void showEvents(app)}><ExternalLink size={16} /></Button></div>
            <div className="mt-4 grid grid-cols-2 gap-3 border-y border-divider py-3 text-sm"><div><p className="text-xs text-default-500">镜像</p><p className="mt-1 truncate">{app.image}</p></div><div><p className="text-xs text-default-500">访问端口</p><p className="mt-1 font-mono">{app.hostPort}:{app.containerPort}</p></div><div><p className="text-xs text-default-500">绑定域名</p><p className="mt-1 truncate">{app.domain || '未绑定'}</p></div><div><p className="text-xs text-default-500">最近更新</p><p className="mt-1">{timeText(app.updatedTime)}</p></div></div>
            {app.lastError && <p className="mt-3 text-xs text-danger">{app.lastError}</p>}
            {app.backupPath && <p className="mt-3 break-all text-xs text-default-500">最近备份：{app.backupPath}</p>}
            <div className="mt-4 flex flex-wrap gap-2">
              <Button size="sm" variant="flat" startContent={<UploadCloud size={15} />} isLoading={busy === `upgrade-${app.id}`} onPress={() => void action(app, 'upgrade')}>升级</Button>
              <Button size="sm" variant="flat" startContent={<Archive size={15} />} isLoading={busy === `backup-${app.id}`} onPress={() => void action(app, 'backup')}>备份</Button>
              <Button size="sm" variant="flat" startContent={app.state === 'active' ? <Square size={14} /> : <Play size={15} />} isLoading={busy === `${app.state === 'active' ? 'stop' : 'start'}-${app.id}`} onPress={() => void action(app, app.state === 'active' ? 'stop' : 'start')}>{app.state === 'active' ? '停止' : '启动'}</Button>
              <Button size="sm" variant="flat" startContent={<RotateCcw size={15} />} isLoading={busy === `rollback-${app.id}`} onPress={() => void action(app, 'rollback')}>回退</Button>
              <Button size="sm" variant="flat" startContent={<Copy size={15} />} isLoading={busy === `cmd-${app.id}`} onPress={() => void showCommand(app, 'upgrade')}>命令</Button>
              <Button size="sm" color="danger" variant="flat" startContent={<Trash2 size={15} />} isLoading={busy === `remove-${app.id}`} onPress={() => void action(app, 'remove')}>删除</Button>
            </div>
          </article>;
        })}</div>}
      </div>
    </section>

    <Modal isOpen={createOpen} onOpenChange={setCreateOpen} size="2xl"><ModalContent><ModalHeader>部署 Docker 应用</ModalHeader><ModalBody className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2"><Select label="节点" selectedKeys={form.nodeId ? [form.nodeId] : []} onSelectionChange={keys => setForm({ ...form, nodeId: String(Array.from(keys)[0] || '') })}>{data.nodes.map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · {node.online ? '在线' : '离线'}</SelectItem>)}</Select><Select label="应用模板" selectedKeys={form.templateId ? [form.templateId] : []} onSelectionChange={keys => { const id = String(Array.from(keys)[0] || ''); const template = data.templates.find(item => item.id === id); setForm({ ...form, templateId: id, name: template?.name || form.name, containerName: template ? `flux-${template.id}` : form.containerName, hostPort: template ? String(template.defaultHostPort) : form.hostPort }); }}>{data.templates.map(template => <SelectItem key={template.id} textValue={template.name}>{template.name} · {template.image}</SelectItem>)}</Select></div>
      <div className="grid gap-4 sm:grid-cols-2"><Input label="应用名称" value={form.name} onValueChange={name => setForm({ ...form, name })} /><Input label="容器名" value={form.containerName} onValueChange={containerName => setForm({ ...form, containerName })} /></div>
      <div className="grid gap-4 sm:grid-cols-2"><Input type="number" label="公网端口" value={form.hostPort} onValueChange={hostPort => setForm({ ...form, hostPort })} /><Input label="容器端口" isReadOnly value={selectedTemplate ? String(selectedTemplate.containerPort) : ''} /></div>
      <Switch isSelected={form.bindDomain} onValueChange={bindDomain => setForm({ ...form, bindDomain })}>同时绑定 HTTPS 域名</Switch>
      {form.bindDomain && <div className="grid gap-4 border-y border-divider py-4 sm:grid-cols-2">
        <Input label="访问域名" placeholder="app.example.com" value={form.domain} onValueChange={domain => setForm({ ...form, domain })} />
        <Select label="DNS 与证书配置" selectedKeys={form.dnsZoneId ? [form.dnsZoneId] : []} onSelectionChange={keys => setForm({ ...form, dnsZoneId: String(Array.from(keys)[0] || '') })}>{dnsZones.map(zone => <SelectItem key={String(zone.id)} textValue={zone.zoneName}>{zone.zoneName} · {zone.accountName}</SelectItem>)}</Select>
        <Select label="HTTPS 入口节点" selectedKeys={form.entryNodeId ? [form.entryNodeId] : []} onSelectionChange={keys => setForm({ ...form, entryNodeId: String(Array.from(keys)[0] || '') })}>{data.nodes.map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · {node.serverIp || node.ip || '地址未知'}</SelectItem>)}</Select>
        <Input type="number" label="HTTPS 监听端口" value={form.listenPort} onValueChange={listenPort => setForm({ ...form, listenPort })} />
        <Input label="外部访问路径" value={form.pathPrefix} onValueChange={pathPrefix => setForm({ ...form, pathPrefix })} />
        <Input label="后端根路径" value={form.backendPath} onValueChange={backendPath => setForm({ ...form, backendPath })} />
      </div>}
      <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">旧 Agent 不支持直接执行时，应用卡片会保留手动命令；开启域名绑定后，面板会自动创建托管 HTTPS 入口并申请证书。</div>
    </ModalBody><ModalFooter><Button variant="flat" onPress={() => setCreateOpen(false)}>取消</Button><Button color="primary" isLoading={busy === 'deploy'} onPress={() => void deploy()}>部署</Button></ModalFooter></ModalContent></Modal>

    <Modal isOpen={inspectOpen} onOpenChange={setInspectOpen} size="4xl" scrollBehavior="inside"><ModalContent><ModalHeader>Docker 容器识别</ModalHeader><ModalBody>{containers.length === 0 ? <div className="py-16 text-center text-default-400">没有识别到运行容器</div> : <div className="divide-y divide-divider">{containers.map(item => <div key={item.id || item.name} className="grid gap-2 py-3 text-sm sm:grid-cols-[1fr_1fr_1fr]"><div><p className="font-medium">{item.name}</p><p className="font-mono text-xs text-default-400">{item.id}</p></div><p className="break-all">{item.image}</p><p className="text-default-500">{item.status || item.state}</p></div>)}</div>}</ModalBody><ModalFooter><Button color="primary" onPress={() => setInspectOpen(false)}>关闭</Button></ModalFooter></ModalContent></Modal>
    <Modal isOpen={commandOpen} onOpenChange={setCommandOpen} size="2xl"><ModalContent><ModalHeader>手动命令</ModalHeader><ModalBody><pre className="max-h-[55vh] overflow-auto whitespace-pre-wrap rounded-lg bg-default-100 p-4 font-mono text-sm">{command}</pre></ModalBody><ModalFooter><Button variant="flat" onPress={() => setCommandOpen(false)}>关闭</Button><Button color="primary" startContent={<Copy size={16} />} onPress={() => void copy()}>复制</Button></ModalFooter></ModalContent></Modal>
    <Modal isOpen={eventsOpen} onOpenChange={setEventsOpen} size="3xl" scrollBehavior="inside"><ModalContent><ModalHeader>应用事件</ModalHeader><ModalBody>{events.length === 0 ? <div className="py-16 text-center text-default-400">暂无事件</div> : <div className="divide-y divide-divider">{events.map(event => <div key={event.id} className="grid gap-2 py-3 text-sm sm:grid-cols-[140px_120px_1fr]"><span>{timeText(event.createdTime)}</span><Chip size="sm" variant="flat" color={event.status === 'success' ? 'success' : event.status === 'failed' ? 'danger' : 'primary'}>{event.eventType}</Chip><span className="text-default-500">{event.detail || '-'}</span></div>)}</div>}</ModalBody><ModalFooter><Button color="primary" onPress={() => setEventsOpen(false)}>关闭</Button></ModalFooter></ModalContent></Modal>
  </div>;
}
