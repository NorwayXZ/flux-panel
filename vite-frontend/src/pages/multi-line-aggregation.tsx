import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Checkbox } from '@heroui/checkbox';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader, useDisclosure } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Switch } from '@heroui/switch';
import { Table, TableBody, TableCell, TableColumn, TableHeader, TableRow } from '@heroui/table';
import {
  Activity, Calculator, CirclePause, CirclePlay, FlaskConical, Gauge, History,
  Plus, RefreshCw, Route, Trash2, Wrench, X,
} from 'lucide-react';
import {
  AggregationEvent, AggregationGroup, AggregationMode, AggregationOverview, AggregationProtocol,
  deleteAggregation, deployAggregation, getAggregationEvents, getAggregationOverview,
  recalculateAggregation, repairAggregation, saveAggregation, testAggregation, toggleAggregation,
} from '@/api';

type FormState = {
  id?: number; name: string; tunnelIds: number[]; listenPort: string; remoteAddr: string;
  protocolMode: AggregationProtocol; mode: AggregationMode; autoWeight: boolean;
  minimumHealthyPaths: string; manualWeights: Record<number, number>;
};
type ApiResult<T> = { code: number; msg: string; data: T };

const emptyForm = (): FormState => ({
  name: '', tunnelIds: [], listenPort: '', remoteAddr: '', protocolMode: 'tcp_udp',
  mode: 'balanced', autoWeight: true, minimumHealthyPaths: '1', manualWeights: {},
});

const modeLabel: Record<AggregationMode, string> = { speed: '速度优先', balanced: '均衡', stability: '稳定优先' };
const statusLabel: Record<string, string> = { active: '运行中', paused: '已暂停', degraded: '线路不足', error: '部署失败', provisioning: '待部署' };
const eventLabel: Record<string, string> = { create: '创建', update: '更新', deploy: '部署', repair: '修复', pause: '暂停', resume: '恢复', validation: '线路验证', weight_change: '权重调整', health: '健康变化' };

const number = (value: unknown, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback;
const speed = (value?: number) => value == null ? '待测试' : `${number(value).toFixed(value >= 100 ? 0 : 1)} Mbps`;
const latency = (value?: number) => value == null ? '-' : `${number(value).toFixed(1)} ms`;
const percent = (value?: number) => value == null ? '-' : `${number(value).toFixed(2)}%`;
const bytes = (value?: number) => {
  const amount = number(value); if (!amount) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB']; const index = Math.min(Math.floor(Math.log(amount) / Math.log(1024)), units.length - 1);
  return `${(amount / Math.pow(1024, index)).toFixed(index > 2 ? 1 : 0)} ${units[index]}`;
};
const timeText = (value?: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';

export default function MultiLineAggregationPage() {
  const [data, setData] = useState<AggregationOverview | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm());
  const [busy, setBusy] = useState<string>('');
  const [error, setError] = useState('');
  const [events, setEvents] = useState<AggregationEvent[]>([]);
  const [eventGroup, setEventGroup] = useState<AggregationGroup | null>(null);
  const formModal = useDisclosure();
  const eventModal = useDisclosure();

  const load = async () => {
    setError('');
    try { const response = await getAggregationOverview(); if (response.code === 0) setData(response.data); else setError(response.msg); }
    catch (reason: any) { setError(reason?.message || '加载多线路聚合失败'); }
  };
  useEffect(() => { void load(); }, []);

  const groupedTunnels = useMemo(() => {
    const groups = new Map<number, { name: string; items: AggregationOverview['tunnels'] }>();
    for (const tunnel of data?.tunnels || []) {
      const current = groups.get(tunnel.entryNodeId) || { name: tunnel.entryNodeName, items: [] };
      current.items.push(tunnel); groups.set(tunnel.entryNodeId, current);
    }
    return Array.from(groups.entries());
  }, [data?.tunnels]);

  const openCreate = () => { setForm(emptyForm()); formModal.onOpen(); };
  const openEdit = (group: AggregationGroup) => {
    setForm({ id: group.id, name: group.name, tunnelIds: group.members.map(member => member.tunnel_id),
      listenPort: String(group.listen_port), remoteAddr: group.remote_addr, protocolMode: group.protocol_mode,
      mode: group.mode, autoWeight: Boolean(group.auto_weight), minimumHealthyPaths: String(group.minimum_healthy_paths),
      manualWeights: Object.fromEntries(group.members.map(member => [member.tunnel_id, number(member.manual_weight, 100)])) });
    formModal.onOpen();
  };

  const toggleTunnel = (id: number) => {
    const tunnel = data?.tunnels.find(item => item.id === id); if (!tunnel) return;
    const selectedEntry = data?.tunnels.find(item => form.tunnelIds.includes(item.id))?.entryNodeId;
    if (!form.tunnelIds.includes(id) && selectedEntry != null && selectedEntry !== tunnel.entryNodeId) {
      setError('同一个聚合组的所有线路必须使用相同入口节点'); return;
    }
    setError('');
    setForm(current => ({ ...current,
      tunnelIds: current.tunnelIds.includes(id) ? current.tunnelIds.filter(item => item !== id) : [...current.tunnelIds, id],
      manualWeights: { ...current.manualWeights, [id]: current.manualWeights[id] || 100 },
    }));
  };

  const submit = async () => {
    if (!form.name.trim() || !form.remoteAddr.trim() || !form.listenPort) { setError('请填写名称、入口端口和目标地址'); return; }
    if (form.tunnelIds.length < 2) { setError('至少选择两条相同入口节点的线路'); return; }
    setBusy('save'); setError('');
    try {
      const response = await saveAggregation({ id: form.id, name: form.name.trim(), tunnelIds: form.tunnelIds,
        listenPort: number(form.listenPort), remoteAddr: form.remoteAddr.trim(), protocolMode: form.protocolMode,
        mode: form.mode, autoWeight: form.autoWeight, minimumHealthyPaths: number(form.minimumHealthyPaths, 1),
        manualWeights: form.manualWeights });
      if (response.code !== 0) { setError(response.msg || '保存失败'); return; }
      setData(response.data); formModal.onClose();
    } catch (reason: any) { setError(reason?.message || '保存失败'); }
    finally { setBusy(''); }
  };

  const action = async (key: string, operation: () => Promise<ApiResult<AggregationOverview>>) => {
    setBusy(key); setError('');
    try { const response = await operation(); if (response.code === 0) setData(response.data); else setError(response.msg || '操作失败'); }
    catch (reason: any) { setError(reason?.message || '操作失败'); }
    finally { setBusy(''); }
  };

  const validate = async (group: AggregationGroup) => {
    setBusy(`test-${group.id}`); setError('');
    try { const response = await testAggregation(group.id); if (response.code !== 0) { setError(response.msg); return; } await openEvents(group); }
    catch (reason: any) { setError(reason?.message || '线路验证失败'); }
    finally { setBusy(''); }
  };

  const openEvents = async (group: AggregationGroup) => {
    setEventGroup(group); eventModal.onOpen();
    try { const response = await getAggregationEvents(group.id); if (response.code === 0) setEvents(response.data); else setError(response.msg); }
    catch (reason: any) { setError(reason?.message || '读取历史失败'); }
  };

  const remove = async (group: AggregationGroup) => {
    if (!window.confirm(`删除聚合组“${group.name}”及其底层转发？`)) return;
    await action(`delete-${group.id}`, () => deleteAggregation(group.id));
  };

  return <div className="space-y-6 pb-10">
    <div className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
      <div><div className="flex items-center gap-2"><Route className="h-6 w-6 text-primary" /><h1 className="text-2xl font-semibold">多线路聚合</h1></div>
        <p className="mt-1 text-sm text-default-500">一个公网入口，自适应调度多条完整线路上的并发连接。</p></div>
      <div className="flex gap-2"><Button isIconOnly variant="flat" title="刷新" onPress={() => void load()}><RefreshCw className="h-4 w-4" /></Button>
        <Button color="primary" startContent={<Plus className="h-4 w-4" />} onPress={openCreate}>新建聚合</Button></div>
    </div>

    {error && <div className="flex items-center justify-between border border-danger-200 bg-danger-50 px-4 py-3 text-sm text-danger-700 dark:bg-danger-950/30"><span>{error}</span><Button isIconOnly size="sm" variant="light" onPress={() => setError('')}><X className="h-4 w-4" /></Button></div>}

    <div className="grid grid-cols-2 gap-px overflow-hidden border border-divider bg-divider sm:grid-cols-5">
      {[['聚合组', data?.summary.groups || 0], ['运行中', data?.summary.active || 0], ['健康线路', data?.summary.healthyPaths || 0], ['预估总容量', speed(data?.summary.estimatedCapacityMbps)], ['降级组', data?.summary.degraded || 0]].map(([label, value]) =>
        <div key={String(label)} className="bg-content1 px-4 py-4"><div className="text-xs text-default-500">{label}</div><div className="mt-1 text-xl font-semibold">{value}</div></div>)}</div>

    {!data ? <div className="py-24 text-center text-default-400">正在加载...</div> : data.groups.length === 0 ?
      <div className="border-y border-divider py-20 text-center"><Gauge className="mx-auto h-9 w-9 text-default-300" /><div className="mt-3 font-medium">尚未创建聚合入口</div></div> :
      <div className="space-y-5">{data.groups.map(group => <section key={group.id} className="border-y border-divider bg-content1">
        <div className="flex flex-col gap-4 px-4 py-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h2 className="font-semibold">{group.name}</h2>
            <Chip size="sm" variant="flat" color={group.state === 'active' ? 'success' : group.state === 'paused' ? 'default' : group.state === 'degraded' ? 'warning' : 'danger'}>{statusLabel[group.state] || group.state}</Chip>
            <Chip size="sm" variant="bordered">并发会话聚合</Chip><Chip size="sm" variant="flat">{modeLabel[group.mode]}</Chip></div>
            <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs text-default-500"><span>入口 {group.entry_server_ip || group.entry_ip || group.entry_node_name}:{group.listen_port}</span><span>目标 {group.remote_addr}</span><span>{group.protocol_mode === 'tcp_udp' ? 'TCP + UDP' : group.protocol_mode.toUpperCase()}</span><span>流量 {bytes(number(group.in_flow) + number(group.out_flow))}</span></div></div>
          <div className="flex flex-wrap gap-2">
            {!group.forward_id && <Button size="sm" color="primary" variant="flat" isLoading={busy === `deploy-${group.id}`} onPress={() => void action(`deploy-${group.id}`, () => deployAggregation(group.id))}>部署</Button>}
            <Button size="sm" isIconOnly variant="flat" title="重新计算权重" isLoading={busy === `calc-${group.id}`} onPress={() => void action(`calc-${group.id}`, () => recalculateAggregation(group.id))}><Calculator className="h-4 w-4" /></Button>
            <Button size="sm" isIconOnly variant="flat" title="修复底层线路" isDisabled={!group.forward_id} isLoading={busy === `repair-${group.id}`} onPress={() => void action(`repair-${group.id}`, () => repairAggregation(group.id))}><Wrench className="h-4 w-4" /></Button>
            <Button size="sm" isIconOnly variant="flat" title="验证线路" isLoading={busy === `test-${group.id}`} onPress={() => void validate(group)}><FlaskConical className="h-4 w-4" /></Button>
            <Button size="sm" isIconOnly variant="flat" title="历史记录" onPress={() => void openEvents(group)}><History className="h-4 w-4" /></Button>
            <Button size="sm" isIconOnly variant="flat" title={group.enabled ? '暂停' : '恢复'} isDisabled={!group.forward_id} isLoading={busy === `toggle-${group.id}`} onPress={() => void action(`toggle-${group.id}`, () => toggleAggregation(group.id, !group.enabled))}>{group.enabled ? <CirclePause className="h-4 w-4" /> : <CirclePlay className="h-4 w-4" />}</Button>
            <Button size="sm" variant="flat" onPress={() => openEdit(group)}>编辑</Button>
            <Button size="sm" isIconOnly color="danger" variant="light" title="删除" isLoading={busy === `delete-${group.id}`} onPress={() => void remove(group)}><Trash2 className="h-4 w-4" /></Button>
          </div>
        </div>
        {group.last_error && <div className="border-t border-warning-200 bg-warning-50 px-4 py-2 text-xs text-warning-700 dark:bg-warning-950/20">{group.last_error}</div>}
        <div className="overflow-x-auto border-t border-divider"><Table removeWrapper aria-label={`${group.name} 线路`} classNames={{ table: 'min-w-[1000px]' }}>
          <TableHeader><TableColumn>线路</TableColumn><TableColumn>状态</TableColumn><TableColumn>有效权重</TableColumn><TableColumn>实测带宽</TableColumn><TableColumn>RTT</TableColumn><TableColumn>丢包</TableColumn><TableColumn>抖动</TableColumn><TableColumn>指标时间</TableColumn></TableHeader>
          <TableBody items={group.members}>{member => <TableRow key={member.id}><TableCell><div className="font-medium">{member.tunnel_name}</div><div className="text-xs text-default-400">{member.in_node_name} → {member.out_node_name}</div></TableCell>
            <TableCell><div className="space-y-1"><Chip size="sm" variant="dot" color={member.health_status === 'healthy' ? 'success' : member.health_status === 'unhealthy' ? 'danger' : 'warning'}>{member.health_status === 'healthy' ? '健康' : member.health_status === 'unhealthy' ? '异常' : '待确认'}</Chip>
              {member.health_status === 'unhealthy' && <div className="max-w-[300px] text-xs leading-5 text-danger-600"><div>{member.failure_segment || '线路探测失败'}</div><div className="font-mono">{member.failure_address || '-'}</div><div className="truncate" title={member.failure_message || member.last_error}>{member.failure_message || member.last_error}</div></div>}</div></TableCell>
            <TableCell>{member.effective_weight}</TableCell><TableCell>{speed(member.bandwidth_mbps)}</TableCell><TableCell>{latency(member.latency_ms)}</TableCell><TableCell>{percent(member.packet_loss_percent)}</TableCell><TableCell>{latency(member.jitter_ms)}</TableCell><TableCell>{timeText(member.metric_measured_at)}</TableCell></TableRow>}</TableBody>
        </Table></div>
        <div className="flex flex-wrap justify-between gap-2 border-t border-divider px-4 py-3 text-xs text-default-500"><span>{group.healthyPaths}/{group.members.length} 条健康线路 · 预估容量 {speed(group.estimatedCapacityMbps)}</span><span>自动权重 {group.auto_weight ? '已开启' : '手动'} · 最近计算 {timeText(group.last_calculated_at)}</span></div>
      </section>)}</div>}

    <Modal isOpen={formModal.isOpen} onOpenChange={formModal.onOpenChange} size="3xl" scrollBehavior="inside">
      <ModalContent><ModalHeader>{form.id ? '编辑聚合组' : '新建多线路聚合'}</ModalHeader><ModalBody className="gap-5">
        <div className="grid gap-4 sm:grid-cols-2"><Input label="名称" value={form.name} onValueChange={value => setForm({ ...form, name: value })} /><Input label="公网入口端口" type="number" min={1} max={65535} value={form.listenPort} onValueChange={value => setForm({ ...form, listenPort: value })} /></div>
        <Input label="最终目标地址" placeholder="host:port" value={form.remoteAddr} onValueChange={value => setForm({ ...form, remoteAddr: value })} />
        <div className="grid gap-4 sm:grid-cols-3"><Select label="入口协议" selectedKeys={[form.protocolMode]} onSelectionChange={keys => setForm({ ...form, protocolMode: String(Array.from(keys)[0] || 'tcp_udp') as AggregationProtocol })}><SelectItem key="tcp_udp">TCP + UDP</SelectItem><SelectItem key="tcp">TCP</SelectItem><SelectItem key="udp">UDP</SelectItem></Select>
          <Select label="调度模式" selectedKeys={[form.mode]} onSelectionChange={keys => setForm({ ...form, mode: String(Array.from(keys)[0] || 'balanced') as AggregationMode })}><SelectItem key="speed">速度优先</SelectItem><SelectItem key="balanced">均衡</SelectItem><SelectItem key="stability">稳定优先</SelectItem></Select>
          <Input label="最低健康线路" type="number" min={1} max={Math.max(1, form.tunnelIds.length)} value={form.minimumHealthyPaths} onValueChange={value => setForm({ ...form, minimumHealthyPaths: value })} /></div>
        <div className="flex items-center justify-between border-y border-divider py-3"><div><div className="text-sm font-medium">自动权重</div><div className="text-xs text-default-500">综合最近带宽、延迟、丢包和抖动</div></div><Switch isSelected={form.autoWeight} onValueChange={value => setForm({ ...form, autoWeight: value })} /></div>
        <div><div className="mb-2 flex items-center justify-between"><span className="text-sm font-medium">聚合线路</span><span className="text-xs text-default-500">已选 {form.tunnelIds.length} 条</span></div>
          <div className="max-h-72 overflow-y-auto border border-divider">{groupedTunnels.map(([entryId, group]) => <div key={entryId} className="border-b border-divider last:border-0"><div className="bg-default-100 px-3 py-2 text-xs font-medium">入口：{group.name}</div>{group.items.map(tunnel => {
            const selected = form.tunnelIds.includes(tunnel.id); const selectedEntry = data?.tunnels.find(item => form.tunnelIds.includes(item.id))?.entryNodeId;
            const disabled = !selected && selectedEntry != null && selectedEntry !== tunnel.entryNodeId;
            return <div key={tunnel.id} className="flex items-center gap-3 border-t border-divider px-3 py-3"><Checkbox isSelected={selected} isDisabled={disabled} onValueChange={() => toggleTunnel(tunnel.id)} />
              <div className="min-w-0 flex-1"><div className="truncate text-sm">{tunnel.name}</div><div className="text-xs text-default-400">{tunnel.entryNodeName} → {tunnel.exitNodeName} · {tunnel.protocol || 'TCP'}</div></div>
              <Chip size="sm" variant="dot" color={tunnel.online ? 'success' : 'danger'}>{tunnel.online ? '在线' : '离线'}</Chip>
              {!form.autoWeight && selected && <Input aria-label="线路权重" size="sm" type="number" min={1} max={1000} className="w-24" value={String(form.manualWeights[tunnel.id] || 100)} onValueChange={value => setForm(current => ({ ...current, manualWeights: { ...current.manualWeights, [tunnel.id]: number(value, 100) } }))} />}
            </div>; })}</div>)}</div></div>
        <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">该功能按权重分配新建连接，适合 YouTube 分片、下载器和多连接业务；单个 TCP 连接不会被拆分到多条线路。</div>
      </ModalBody><ModalFooter><Button variant="flat" onPress={formModal.onClose}>取消</Button><Button color="primary" isLoading={busy === 'save'} onPress={() => void submit()}>保存并部署</Button></ModalFooter></ModalContent>
    </Modal>

    <Modal isOpen={eventModal.isOpen} onOpenChange={eventModal.onOpenChange} size="3xl" scrollBehavior="inside"><ModalContent><ModalHeader>{eventGroup?.name} · 事件与测试记录</ModalHeader><ModalBody>
      {events.length === 0 ? <div className="py-16 text-center text-default-400">暂无记录</div> : <div className="divide-y divide-divider">{events.map(event => <div key={event.id} className="flex gap-3 py-3"><Activity className={`mt-0.5 h-4 w-4 shrink-0 ${event.status === 'success' ? 'text-success' : event.status === 'warning' ? 'text-warning' : 'text-danger'}`} /><div className="min-w-0"><div className="flex flex-wrap items-center gap-2 text-sm"><span className="font-medium">{eventLabel[event.eventType] || event.eventType}</span><Chip size="sm" variant="flat">{event.status}</Chip><span className="text-xs text-default-400">{timeText(event.createdTime)}</span></div><div className="mt-1 text-sm text-default-600">{event.detail || '-'}</div></div></div>)}</div>}
    </ModalBody><ModalFooter><Button color="primary" onPress={eventModal.onClose}>关闭</Button></ModalFooter></ModalContent></Modal>
  </div>;
}
