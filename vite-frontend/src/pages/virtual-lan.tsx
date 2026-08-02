import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Cable, CircleCheck, Network, Pause, Play, Plus, RefreshCw, RotateCw, Server, Trash2, TriangleAlert } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  createVirtualLan, deleteVirtualLan, deployVirtualLan, getVirtualLanOverview, pauseVirtualLan, refreshVirtualLan, resumeVirtualLan,
  type VirtualLanCreateInput, type VirtualLanNetwork, type VirtualLanOverview,
} from '@/api';

const initial: VirtualLanOverview = { minimumAgentVersion: '2.44.0', nodes: [], connectors: [], networks: [] };
type FormState = { name: string; cidr: string; hubNodeId: string; listenPort: string; members: Set<string> };
const emptyForm = (): FormState => ({ name: '', cidr: '10.88.0.0/24', hubNodeId: '', listenPort: '51820', members: new Set() });
const bytes = (value?: number) => { if (!value) return '0 B'; const units = ['B','KB','MB','GB','TB']; let size=value, unit=0; while (size >= 1024 && unit < units.length-1) { size/=1024; unit++; } return `${size.toFixed(unit > 1 ? 2 : 0)} ${units[unit]}`; };
const timeText = (value?: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '暂无握手';
const stateMeta = (state: string) => state === 'active' || state === 'online' ? { label: '在线', color: 'success' as const } : state === 'paused' ? { label: '已暂停', color: 'default' as const } : state === 'degraded' ? { label: '部分离线', color: 'warning' as const } : state === 'deploying' || state === 'pending' ? { label: '部署中', color: 'primary' as const } : { label: state === 'failed' ? '部署失败' : '离线', color: 'danger' as const };

export default function VirtualLanPage() {
  const [data, setData] = useState(initial);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm());
  const load = useCallback(async (quiet = false) => { if (!quiet) setLoading(true); const response = await getVirtualLanOverview(); if (!quiet) setLoading(false); if (response.code === 0) setData(response.data); else if (!quiet) toast.error(response.msg || '加载虚拟局域网失败'); }, []);
  useEffect(() => { void load(); }, [load]);
  const onlineNodes = useMemo(() => data.nodes.filter(node => node.status === 1), [data.nodes]);
  const onlineLinuxConnectors = useMemo(() => data.connectors.filter(item => item.status === 1 && item.platform.toLowerCase() === 'linux'), [data.connectors]);
  const memberOptions = useMemo(() => [
    ...onlineNodes.map(node => ({ key: `node:${node.id}`, label: node.name, detail: `服务器节点 · ${node.serverIp || node.ip || '未设置地址'}` })),
    ...onlineLinuxConnectors.map(connector => ({ key: `connector:${connector.id}`, label: connector.name, detail: `Linux Connector · ${connector.remoteIp || '在线'}` })),
  ], [onlineNodes, onlineLinuxConnectors]);

  const create = async () => {
    if (!form.name.trim() || !form.hubNodeId || form.members.size === 0) return toast.error('请填写网络名称，并选择中继节点和至少一个成员');
    const members: VirtualLanCreateInput['members'] = Array.from(form.members).map(value => { const [targetType, id] = value.split(':'); return { targetType: targetType as 'node' | 'connector', targetId: Number(id) }; });
    setBusy('create'); const response = await createVirtualLan({ name: form.name.trim(), cidr: form.cidr.trim(), hubNodeId: Number(form.hubNodeId), listenPort: Number(form.listenPort), members }); setBusy('');
    if (response.code !== 0) { void load(true); return toast.error(response.msg || '创建组网失败'); }
    setData(response.data); setFormOpen(false); toast.success('虚拟局域网已部署');
  };
  const action = async (network: VirtualLanNetwork, kind: 'refresh' | 'pause' | 'resume' | 'deploy' | 'delete') => {
    if (kind === 'delete' && !window.confirm(`删除“${network.name}”并清理所有成员上的 WireGuard 接口？`)) return;
    setBusy(`${kind}-${network.id}`);
    const response = kind === 'refresh' ? await refreshVirtualLan(network.id) : kind === 'pause' ? await pauseVirtualLan(network.id) : kind === 'resume' ? await resumeVirtualLan(network.id) : kind === 'deploy' ? await deployVirtualLan(network.id) : await deleteVirtualLan(network.id);
    setBusy('');
    if (response.code !== 0) { void load(true); return toast.error(response.msg || '操作失败'); }
    setData(response.data); toast.success(kind === 'delete' ? '虚拟局域网已删除' : kind === 'refresh' ? '成员状态已刷新' : '操作完成');
  };
  const activeNetworks = data.networks.filter(network => network.state === 'active').length;
  const memberCount = data.networks.reduce((sum, network) => sum + Number(network.memberCount || 0), 0);
  const onlineCount = data.networks.reduce((sum, network) => sum + Number(network.onlineCount || 0), 0);

  return <div className="mx-auto w-full max-w-[1500px] space-y-6 p-4 sm:p-6">
    <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between"><div><p className="text-sm text-default-500">接入与发布</p><h1 className="mt-1 text-xl font-semibold sm:text-2xl">虚拟局域网组网</h1><p className="mt-2 text-sm text-default-500">服务器和 Linux Connector 通过内置 WireGuard 加入同一虚拟网段，当前使用稳定中继路径。</p></div><div className="flex gap-2"><Button isIconOnly variant="flat" title="刷新列表" onPress={() => void load()}><RefreshCw size={17} /></Button><Button color="primary" startContent={<Plus size={17} />} onPress={() => { setForm(emptyForm()); setFormOpen(true); }}>创建虚拟网络</Button></div></header>
    <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">{[
      ['虚拟网络', loading ? '-' : data.networks.length, <Network size={18} />], ['运行中', loading ? '-' : activeNetworks, <CircleCheck size={18} />], ['成员总数', loading ? '-' : memberCount, <Server size={18} />], ['在线成员', loading ? '-' : `${onlineCount} / ${memberCount}`, <Cable size={18} />],
    ].map(([label,value,icon]) => <div key={String(label)} className="border border-divider bg-content1 p-4"><div className="flex items-center justify-between"><span className="text-sm text-default-500">{label as string}</span><span className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10 text-primary">{icon}</span></div><p className="mt-3 text-2xl font-semibold">{value as string | number}</p></div>)}</section>
    {data.networks.length === 0 && !loading ? <div className="flex min-h-72 flex-col items-center justify-center gap-3 border-y border-divider text-default-400"><Network size={36} /><p>尚未创建虚拟局域网</p><Button size="sm" variant="flat" onPress={() => { setForm(emptyForm()); setFormOpen(true); }}>创建第一张虚拟网络</Button></div> : <section className="space-y-5">{data.networks.map(network => { const state = stateMeta(network.state); return <article key={network.id} className="border border-divider bg-content1"><div className="flex flex-wrap items-start justify-between gap-3 border-b border-divider p-4 sm:p-5"><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h2 className="font-semibold">{network.name}</h2><Chip size="sm" color={state.color} variant="flat">{state.label}</Chip><Chip size="sm" variant="flat">WireGuard · 中继</Chip></div><p className="mt-2 text-sm text-default-500">{network.cidr} · 中继 {network.hubNodeName}:{network.listenPort}</p>{network.lastError && <p className="mt-2 max-w-3xl text-xs text-danger">{network.lastError}</p>}</div><div className="flex gap-1"><Button isIconOnly size="sm" variant="light" title="刷新握手和流量" isLoading={busy === `refresh-${network.id}`} onPress={() => void action(network, 'refresh')}><RefreshCw size={16} /></Button>{network.state === 'paused' ? <Button isIconOnly size="sm" variant="light" title="恢复网络" isLoading={busy === `resume-${network.id}`} onPress={() => void action(network, 'resume')}><Play size={16} /></Button> : <Button isIconOnly size="sm" variant="light" title="暂停网络" isLoading={busy === `pause-${network.id}`} onPress={() => void action(network, 'pause')}><Pause size={16} /></Button>}<Button isIconOnly size="sm" variant="light" title="重新部署" isLoading={busy === `deploy-${network.id}`} onPress={() => void action(network, 'deploy')}><RotateCw size={16} /></Button><Button isIconOnly size="sm" variant="light" color="danger" title="删除网络" isLoading={busy === `delete-${network.id}`} onPress={() => void action(network, 'delete')}><Trash2 size={16} /></Button></div></div><div className="overflow-x-auto"><table className="w-full min-w-[850px] text-left text-sm"><thead className="border-b border-divider text-xs text-default-500"><tr><th className="p-3 pl-5">成员</th><th className="p-3">虚拟 IP</th><th className="p-3">角色</th><th className="p-3">状态</th><th className="p-3">接收 / 发送</th><th className="p-3">最近握手</th></tr></thead><tbody>{network.members.map(member => { const memberState = stateMeta(member.state); return <tr key={member.id} className="border-b border-divider/60 last:border-0"><td className="p-3 pl-5"><p className="font-medium">{member.memberName}</p><p className="mt-1 text-xs text-default-400">{member.targetType === 'node' ? '服务器 Agent' : 'Linux Connector'}</p></td><td className="p-3 font-mono">{member.virtualIp}</td><td className="p-3">{member.role === 'hub' ? '中继节点' : '普通成员'}</td><td className="p-3"><Chip size="sm" color={memberState.color} variant="flat">{memberState.label}</Chip>{member.lastError && <p className="mt-1 max-w-64 truncate text-xs text-danger" title={member.lastError}>{member.lastError}</p>}</td><td className="p-3">{bytes(member.receiveBytes)} / {bytes(member.transmitBytes)}</td><td className="p-3 whitespace-nowrap">{timeText(member.latestHandshake)}</td></tr>; })}</tbody></table></div></article>; })}</section>}

    <Modal isOpen={formOpen} onOpenChange={setFormOpen} size="3xl" scrollBehavior="inside"><ModalContent><ModalHeader>创建虚拟局域网</ModalHeader><ModalBody className="space-y-4"><div className="grid gap-4 sm:grid-cols-2"><Input isRequired label="网络名称" placeholder="公司与家庭 NAS" value={form.name} onValueChange={value => setForm({ ...form, name: value })} /><Input isRequired label="虚拟网段" description="成员会自动分配虚拟 IP" placeholder="10.88.0.0/24" value={form.cidr} onValueChange={value => setForm({ ...form, cidr: value })} /></div><div className="grid gap-4 sm:grid-cols-2"><Select isRequired label="公网中继节点" description="需要公网 UDP 可达，承载成员之间的中继流量" selectedKeys={form.hubNodeId ? [form.hubNodeId] : []} onSelectionChange={keys => { const hubNodeId = String(Array.from(keys)[0] || ''); const members = new Set(form.members); if (hubNodeId) members.add(`node:${hubNodeId}`); setForm({ ...form, hubNodeId, members }); }}>{onlineNodes.map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · {node.serverIp || node.ip}</SelectItem>)}</Select><Input isRequired label="WireGuard UDP 端口" description="请同时放行服务器安全组和系统防火墙" type="number" min={1} max={65535} value={form.listenPort} onValueChange={value => setForm({ ...form, listenPort: value })} /></div><Select isRequired selectionMode="multiple" label="网络成员" description="Linux Agent/Connector 已集成运行时，不需要额外安装 WireGuard App" selectedKeys={form.members} onSelectionChange={keys => setForm({ ...form, members: new Set(Array.from(keys).map(String)) })}>{memberOptions.map(option => <SelectItem key={option.key} textValue={`${option.label} ${option.detail}`}><div><p>{option.label}</p><p className="text-xs text-default-400">{option.detail}</p></div></SelectItem>)}</Select>{data.connectors.some(item => item.platform.toLowerCase() !== 'linux') && <div className="flex gap-3 border border-warning/30 bg-warning/10 p-3 text-sm text-warning-700 dark:text-warning-400"><TriangleAlert className="mt-0.5 shrink-0" size={17} /><p>Windows 和 macOS Connector 暂不显示，因为当前版本不能创建系统 TUN 接口；服务器与 Linux Connector 可直接组网。</p></div>}<div className="border-y border-divider py-3 text-xs leading-5 text-default-500">创建时会先生成各成员独立密钥，再部署中继和成员配置。任何成员失败都会暂停本轮涉及的接口；修复后可在网络卡片中重新部署。</div></ModalBody><ModalFooter><Button variant="flat" onPress={() => setFormOpen(false)}>取消</Button><Button color="primary" isLoading={busy === 'create'} onPress={() => void create()}>创建并部署</Button></ModalFooter></ModalContent></Modal>
  </div>;
}
