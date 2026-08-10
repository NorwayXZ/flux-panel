import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Switch } from '@heroui/switch';
import { Activity, History, Pencil, Play, Plus, RefreshCw, RadioTower, Trash2, Waves } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  deleteUdpQuicDiagnosticTask,
  getUdpQuicDiagnosticDetail,
  getUdpQuicDiagnosticOverview,
  runUdpQuicDiagnosticTask,
  saveUdpQuicDiagnosticTask,
  type UdpQuicDiagnosticInput,
  type UdpQuicDiagnosticOverview,
  type UdpQuicDiagnosticRun,
  type UdpQuicDiagnosticTask,
} from '@/api';

type FormState = {
  id?: number; name: string; sourceNodeId: string; targetType: 'node' | 'custom'; targetNodeId: string; targetHost: string;
  port: string; mode: 'udp_echo' | 'quic'; serverName: string; ipFamily: 'auto' | 'ipv4' | 'ipv6'; sampleCount: string;
  timeoutMs: string; packetSize: string; idleTimeoutSeconds: string; alpn: string; verifyCertificate: boolean; retentionDays: string;
};

const emptyForm: FormState = {
  name: '', sourceNodeId: '', targetType: 'node', targetNodeId: '', targetHost: '', port: '443', mode: 'udp_echo',
  serverName: '', ipFamily: 'auto', sampleCount: '5', timeoutMs: '3000', packetSize: '1200',
  idleTimeoutSeconds: '15', alpn: 'h3', verifyCertificate: false, retentionDays: '30',
};
const initial: UdpQuicDiagnosticOverview = { minimumAgentVersion: '2.49.0', nodes: [], tasks: [], summary: { total: 0, running: 0, success: 0, degraded: 0, failed: 0 } };
const bool = (value?: boolean | number) => value === true || value === 1;
const timeText = (value?: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未诊断';
const percentText = (value?: number) => `${Number(value || 0).toFixed(2)}%`;
const latencyText = (value?: number) => value && value > 0 ? `${Number(value).toFixed(2)} ms` : '-';
const modeText = (mode: string) => mode === 'quic' ? 'QUIC 握手' : 'UDP Echo';
const stateMeta = (task: UdpQuicDiagnosticTask) => bool(task.running) || task.lastStatus === 'running'
  ? { label: '诊断中', color: 'primary' as const }
  : task.lastStatus === 'success'
    ? { label: '正常', color: 'success' as const }
    : task.lastStatus === 'partial'
      ? { label: '不稳定', color: 'warning' as const }
      : task.lastStatus === 'failed'
        ? { label: '失败', color: 'danger' as const }
        : { label: '未诊断', color: 'default' as const };

export default function UdpQuicDiagnosticPage() {
  const [data, setData] = useState(initial);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyTask, setHistoryTask] = useState<UdpQuicDiagnosticTask | null>(null);
  const [runs, setRuns] = useState<UdpQuicDiagnosticRun[]>([]);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const response = await getUdpQuicDiagnosticOverview();
    if (!quiet) setLoading(false);
    if (response.code === 0) setData(response.data); else if (!quiet) toast.error(response.msg || '加载 UDP / QUIC 诊断失败');
  }, []);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (!document.hidden && data.tasks.some(task => bool(task.running))) void load(true);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [data.tasks, load]);

  const onlineNodes = useMemo(() => data.nodes.filter(node => node.status === 1), [data.nodes]);

  const openCreate = () => { setForm(emptyForm); setFormOpen(true); };
  const openEdit = (task: UdpQuicDiagnosticTask) => {
    setForm({
      id: task.id, name: task.name, sourceNodeId: String(task.sourceNodeId), targetType: task.targetType || 'node',
      targetNodeId: task.targetNodeId ? String(task.targetNodeId) : '', targetHost: task.targetHost || '', port: String(task.port),
      mode: task.mode || 'udp_echo', serverName: task.serverName || '', ipFamily: task.ipFamily || 'auto',
      sampleCount: String(task.sampleCount), timeoutMs: String(task.timeoutMs), packetSize: String(task.packetSize),
      idleTimeoutSeconds: String(task.idleTimeoutSeconds), alpn: task.alpn || 'h3',
      verifyCertificate: bool(task.verifyCertificate), retentionDays: String(task.retentionDays),
    });
    setFormOpen(true);
  };
  const input = (): UdpQuicDiagnosticInput => ({
    id: form.id, name: form.name.trim(), sourceNodeId: Number(form.sourceNodeId), targetType: form.mode === 'udp_echo' ? 'node' : form.targetType,
    targetNodeId: form.targetType === 'node' || form.mode === 'udp_echo' ? Number(form.targetNodeId) : undefined,
    targetHost: form.targetType === 'custom' && form.mode === 'quic' ? form.targetHost.trim() : undefined,
    port: Number(form.port), mode: form.mode, serverName: form.serverName.trim() || undefined, ipFamily: form.ipFamily,
    sampleCount: Number(form.sampleCount), timeoutMs: Number(form.timeoutMs), packetSize: Number(form.packetSize),
    idleTimeoutSeconds: form.mode === 'udp_echo' ? Number(form.idleTimeoutSeconds) : 0,
    alpn: form.mode === 'quic' ? form.alpn.trim() || 'h3' : undefined, verifyCertificate: form.verifyCertificate,
    retentionDays: Number(form.retentionDays),
  });
  const save = async () => {
    if (!form.name.trim() || !form.sourceNodeId) return toast.error('请填写任务名称并选择执行节点');
    if ((form.mode === 'udp_echo' || form.targetType === 'node') && !form.targetNodeId) return toast.error('请选择目标节点');
    if (form.mode === 'quic' && form.targetType === 'custom' && !form.targetHost.trim()) return toast.error('请填写 QUIC 目标地址');
    setBusy('save');
    const response = await saveUdpQuicDiagnosticTask(input());
    setBusy('');
    if (response.code !== 0) return toast.error(response.msg || '保存失败');
    setData(response.data); setFormOpen(false); toast.success('诊断任务已保存');
  };
  const run = async (task: UdpQuicDiagnosticTask) => {
    setBusy(`run-${task.id}`);
    const response = await runUdpQuicDiagnosticTask(task.id);
    setBusy('');
    if (response.code !== 0) return toast.error(response.msg || '无法开始诊断');
    toast.success('诊断已开始'); void load(true);
  };
  const remove = async (task: UdpQuicDiagnosticTask) => {
    if (!window.confirm(`删除“${task.name}”及全部历史结果？`)) return;
    setBusy(`delete-${task.id}`);
    const response = await deleteUdpQuicDiagnosticTask(task.id);
    setBusy('');
    if (response.code !== 0) return toast.error(response.msg || '删除失败');
    setData(response.data); toast.success('已删除');
  };
  const history = async (task: UdpQuicDiagnosticTask) => {
    setBusy(`history-${task.id}`);
    const response = await getUdpQuicDiagnosticDetail(task.id);
    setBusy('');
    if (response.code !== 0) return toast.error(response.msg || '加载历史失败');
    setHistoryTask(task); setRuns(response.data.runs); setHistoryOpen(true);
  };

  return <div className="mx-auto w-full max-w-[1500px] space-y-6 p-4 sm:p-6">
    <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div><p className="text-sm text-default-500">实用工具</p><h1 className="mt-1 text-xl font-semibold sm:text-2xl">UDP / QUIC 诊断</h1></div>
      <div className="flex gap-2"><Button isIconOnly variant="flat" title="刷新" onPress={() => void load()}><RefreshCw size={17} /></Button><Button color="primary" startContent={<Plus size={17} />} onPress={openCreate}>新建诊断</Button></div>
    </header>

    <section className="grid grid-cols-2 gap-3 lg:grid-cols-5">{[
      ['任务', loading ? '-' : data.summary.total, <RadioTower size={18} />, 'text-primary bg-primary/10'],
      ['运行中', loading ? '-' : data.summary.running, <Activity size={18} />, 'text-warning bg-warning/10'],
      ['正常', loading ? '-' : data.summary.success, <Waves size={18} />, 'text-success bg-success/10'],
      ['不稳定', loading ? '-' : data.summary.degraded, <History size={18} />, 'text-warning bg-warning/10'],
      ['失败', loading ? '-' : data.summary.failed, <Trash2 size={18} />, 'text-danger bg-danger/10'],
    ].map(([label, value, icon, tone]) => <div key={String(label)} className="border border-divider bg-content1 p-4"><div className="flex items-center justify-between"><span className="text-sm text-default-500">{label as string}</span><span className={`flex h-9 w-9 items-center justify-center rounded-lg ${tone}`}>{icon}</span></div><p className="mt-3 text-2xl font-semibold">{value as string | number}</p></div>)}</section>

    {data.tasks.length === 0 && !loading ? <div className="flex min-h-72 flex-col items-center justify-center gap-3 border-y border-divider text-default-400"><RadioTower size={36} /><p>尚未创建 UDP / QUIC 诊断</p><Button size="sm" variant="flat" onPress={openCreate}>创建第一项诊断</Button></div> : <section className="grid gap-4 xl:grid-cols-2">{data.tasks.map(task => {
      const state = stateMeta(task);
      return <article key={task.id} className="border border-divider bg-content1 p-4 sm:p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2"><h2 className="truncate font-semibold">{task.name}</h2><Chip size="sm" color={state.color} variant="flat">{state.label}</Chip><Chip size="sm" variant="flat">{modeText(task.mode)} · {task.ipFamily.toUpperCase()}</Chip></div>
            <p className="mt-2 truncate text-sm text-default-500">{task.sourceNodeName} → {task.targetType === 'node' ? task.targetNodeName : task.targetHost}:{task.port}</p>
            <p className="mt-1 text-xs text-default-400">Agent {data.minimumAgentVersion}+ · {task.sampleCount} 次 · {task.timeoutMs} ms</p>
          </div>
          <div className="flex gap-1"><Button isIconOnly size="sm" variant="light" title="开始诊断" isLoading={busy === `run-${task.id}`} isDisabled={bool(task.running)} onPress={() => void run(task)}><Play size={16} /></Button><Button isIconOnly size="sm" variant="light" title="历史结果" isLoading={busy === `history-${task.id}`} onPress={() => void history(task)}><History size={16} /></Button><Button isIconOnly size="sm" variant="light" title="编辑" isDisabled={bool(task.running)} onPress={() => openEdit(task)}><Pencil size={16} /></Button><Button isIconOnly size="sm" variant="light" color="danger" title="删除" isLoading={busy === `delete-${task.id}`} isDisabled={bool(task.running)} onPress={() => void remove(task)}><Trash2 size={16} /></Button></div>
        </div>
        <div className="mt-4 grid grid-cols-4 divide-x divide-divider border-y border-divider py-3 text-center">
          <div><p className="text-xs text-default-500">成功</p><p className="mt-1 font-semibold">{Number(task.successCount || 0)}/{Number(task.latestSampleCount || task.sampleCount || 0)}</p></div>
          <div><p className="text-xs text-default-500">丢包</p><p className="mt-1 font-semibold">{percentText(task.packetLossPercent ?? task.failureRate)}</p></div>
          <div><p className="text-xs text-default-500">{task.mode === 'quic' ? '握手' : 'RTT'}</p><p className="mt-1 font-semibold">{latencyText(task.quicHandshakeAvgMs || task.rttAvgMs)}</p></div>
          <div><p className="text-xs text-default-500">抖动</p><p className="mt-1 font-semibold">{latencyText(task.jitterMs)}</p></div>
        </div>
        <div className="mt-3 flex items-start justify-between gap-3 text-xs text-default-500"><span>{timeText(task.latestStartedAt || task.lastRunAt)}</span>{task.diagnosis || task.lastError ? <span className={task.lastStatus === 'success' ? 'max-w-[68%] truncate' : 'max-w-[68%] truncate text-warning-600 dark:text-warning-400'} title={task.diagnosis || task.lastError}>{task.diagnosis || task.lastError}</span> : null}</div>
      </article>;
    })}</section>}

    <Modal isOpen={formOpen} onOpenChange={setFormOpen} size="3xl" scrollBehavior="inside"><ModalContent><ModalHeader>{form.id ? '编辑 UDP / QUIC 诊断' : '新建 UDP / QUIC 诊断'}</ModalHeader><ModalBody className="space-y-4">
      <Input isRequired label="任务名称" placeholder="移动 5G 到香港 QUIC" value={form.name} onValueChange={value => setForm({ ...form, name: value })} />
      <div className="grid gap-4 md:grid-cols-3">
        <Select label="诊断类型" selectedKeys={[form.mode]} onSelectionChange={keys => { const mode = String(Array.from(keys)[0] || 'udp_echo') as FormState['mode']; setForm({ ...form, mode, targetType: mode === 'udp_echo' ? 'node' : form.targetType, port: mode === 'quic' ? form.port : form.port }); }}><SelectItem key="udp_echo">UDP Echo</SelectItem><SelectItem key="quic">QUIC 握手</SelectItem></Select>
        <Select isRequired label="执行节点" selectedKeys={form.sourceNodeId ? [form.sourceNodeId] : []} onSelectionChange={keys => setForm({ ...form, sourceNodeId: String(Array.from(keys)[0] || '') })}>{onlineNodes.map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · Agent {node.version || '未知'}</SelectItem>)}</Select>
        <Input label="端口" type="number" min={1} max={65535} value={form.port} onValueChange={value => setForm({ ...form, port: value })} />
      </div>
      {form.mode === 'quic' && <Select label="目标类型" selectedKeys={[form.targetType]} onSelectionChange={keys => setForm({ ...form, targetType: String(Array.from(keys)[0] || 'custom') as FormState['targetType'] })}><SelectItem key="custom">自定义目标</SelectItem><SelectItem key="node">节点地址</SelectItem></Select>}
      {(form.mode === 'udp_echo' || form.targetType === 'node') ? <Select isRequired label="目标节点" selectedKeys={form.targetNodeId ? [form.targetNodeId] : []} onSelectionChange={keys => setForm({ ...form, targetNodeId: String(Array.from(keys)[0] || '') })}>{onlineNodes.map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · {node.serverIp || node.ip || '未设置地址'} · Agent {node.version || '未知'}</SelectItem>)}</Select> : <Input isRequired label="目标地址" placeholder="example.com 或 1.1.1.1" value={form.targetHost} onValueChange={value => setForm({ ...form, targetHost: value })} />}
      <div className="grid gap-4 md:grid-cols-4">
        <Select label="IP 栈" selectedKeys={[form.ipFamily]} onSelectionChange={keys => setForm({ ...form, ipFamily: String(Array.from(keys)[0] || 'auto') as FormState['ipFamily'] })}><SelectItem key="auto">自动</SelectItem><SelectItem key="ipv4">IPv4</SelectItem><SelectItem key="ipv6">IPv6</SelectItem></Select>
        <Input label="探测次数" type="number" min={1} max={20} value={form.sampleCount} onValueChange={value => setForm({ ...form, sampleCount: value })} />
        <Input label="超时（毫秒）" type="number" min={300} max={20000} value={form.timeoutMs} onValueChange={value => setForm({ ...form, timeoutMs: value })} />
        <Input label="历史保留（天）" type="number" min={1} max={365} value={form.retentionDays} onValueChange={value => setForm({ ...form, retentionDays: value })} />
      </div>
      {form.mode === 'udp_echo' ? <div className="grid gap-4 md:grid-cols-2"><Input label="UDP 包大小（字节）" type="number" min={64} max={1400} value={form.packetSize} onValueChange={value => setForm({ ...form, packetSize: value })} /><Input label="NAT 空闲秒数" type="number" min={0} max={60} value={form.idleTimeoutSeconds} onValueChange={value => setForm({ ...form, idleTimeoutSeconds: value })} /></div> : <div className="grid gap-4 md:grid-cols-2"><Input label="SNI / Server Name" placeholder="example.com" value={form.serverName} onValueChange={value => setForm({ ...form, serverName: value })} /><Input label="ALPN" placeholder="h3" value={form.alpn} onValueChange={value => setForm({ ...form, alpn: value })} /><div className="md:col-span-2"><Switch isSelected={form.verifyCertificate} onValueChange={value => setForm({ ...form, verifyCertificate: value })}>校验证书</Switch></div></div>}
    </ModalBody><ModalFooter><Button variant="flat" onPress={() => setFormOpen(false)}>取消</Button><Button color="primary" isLoading={busy === 'save'} onPress={() => void save()}>保存</Button></ModalFooter></ModalContent></Modal>

    <Modal isOpen={historyOpen} onOpenChange={setHistoryOpen} size="5xl" scrollBehavior="inside"><ModalContent><ModalHeader>{historyTask?.name} · 诊断历史</ModalHeader><ModalBody>{runs.length === 0 ? <div className="py-16 text-center text-default-400">尚无诊断结果</div> : <div className="overflow-x-auto"><table className="w-full min-w-[1180px] text-left text-sm"><thead className="border-b border-divider text-xs text-default-500"><tr><th className="p-3">时间</th><th className="p-3">类型 / 目标</th><th className="p-3">成功</th><th className="p-3">丢包</th><th className="p-3">RTT / 握手</th><th className="p-3">抖动</th><th className="p-3">NAT</th><th className="p-3">结论</th><th className="p-3">结果</th></tr></thead><tbody>{runs.map(run => <tr key={run.id} className="border-b border-divider/60"><td className="p-3 whitespace-nowrap">{timeText(run.startedAt)}</td><td className="p-3"><div>{modeText(run.mode)} · {run.ipFamily}</div><div className="mt-1 max-w-[260px] truncate text-xs text-default-400">{run.targetHost}:{run.port}{run.resolvedAddress ? ` · ${run.resolvedAddress}` : ''}</div></td><td className="p-3">{run.successCount}/{run.sampleCount}</td><td className="p-3">{percentText(run.packetLossPercent || run.failureRate)}</td><td className="p-3">{latencyText(run.quicHandshakeAvgMs || run.rttAvgMs)}</td><td className="p-3">{latencyText(run.jitterMs)}</td><td className="p-3">{run.natIdleSeconds ? `${run.natIdleSeconds}s · ${bool(run.natIdleAlive) ? '保持' : '失效'}` : '-'}</td><td className="p-3"><span className="line-clamp-2 max-w-[320px]" title={run.diagnosis || run.error}>{run.diagnosis || run.error || '-'}</span></td><td className="p-3"><Chip size="sm" color={run.status === 'success' ? 'success' : run.status === 'partial' ? 'warning' : 'danger'} variant="flat">{run.status === 'success' ? '正常' : run.status === 'partial' ? '不稳定' : '失败'}</Chip></td></tr>)}</tbody></table></div>}</ModalBody><ModalFooter><Button color="primary" onPress={() => setHistoryOpen(false)}>关闭</Button></ModalFooter></ModalContent></Modal>
  </div>;
}
