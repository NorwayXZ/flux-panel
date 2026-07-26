import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Card, CardBody } from '@heroui/card';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { Activity, ArrowRight, CheckCircle2, History, Pencil, Plus, RefreshCw, ShieldCheck, Trash2, TriangleAlert, X } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  checkCrossEntryGroup,
  deleteCrossEntryGroup,
  getCrossEntryEvents,
  getCrossEntryForwardOptions,
  getCrossEntryGroups,
  saveCrossEntryGroup,
  type CrossEntryEvent,
  type CrossEntryForwardOption,
  type CrossEntryGroup,
  type CrossEntrySummary,
} from '@/api';

type PresetProfileKey = 'fast' | 'standard' | 'stable';
type ProfileKey = PresetProfileKey | 'custom';

const profiles: Record<PresetProfileKey, { label: string; interval: number; timeout: number; failures: number; recovery: number; note: string }> = {
  fast: { label: '极速', interval: 2000, timeout: 1200, failures: 2, recovery: 3, note: '约 3-6 秒触发切换' },
  standard: { label: '标准', interval: 5000, timeout: 2000, failures: 2, recovery: 3, note: '约 8-15 秒触发切换' },
  stable: { label: '稳健', interval: 10000, timeout: 3000, failures: 3, recovery: 4, note: '适合网络波动较大的入口' },
};

const emptySummary: CrossEntrySummary = { total: 0, enabled: 0, healthy: 0, degraded: 0, switches: 0 };
const emptyForm = {
  id: undefined as number | undefined,
  name: '', domain: '', zoneId: '', recordId: '', apiToken: '', recordType: 'A' as 'A' | 'AAAA', ttl: '60',
  profile: 'fast' as ProfileKey, probeIntervalMs: '2000', connectTimeoutMs: '1200', failureThreshold: '2',
  recoveryThreshold: '3', cooldownSeconds: '30', autoFailback: false, enabled: true, memberForwardIds: ['', ''],
};

const truthy = (value: boolean | number) => value === true || value === 1;
const timeText = (value?: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未检测';
const stateMeta = (state: CrossEntryGroup['state']) => ({
  healthy: { label: '运行正常', color: 'success' as const },
  degraded: { label: '部分异常', color: 'warning' as const },
  offline: { label: '全部离线', color: 'danger' as const },
  switching: { label: '切换中', color: 'warning' as const },
  error: { label: '切换失败', color: 'danger' as const },
  unknown: { label: '等待检测', color: 'default' as const },
})[state] || { label: '等待检测', color: 'default' as const };

export default function CrossEntryFailoverPage() {
  const [loading, setLoading] = useState(true);
  const [groups, setGroups] = useState<CrossEntryGroup[]>([]);
  const [summary, setSummary] = useState<CrossEntrySummary>(emptySummary);
  const [forwardOptions, setForwardOptions] = useState<CrossEntryForwardOption[]>([]);
  const [formOpen, setFormOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [events, setEvents] = useState<CrossEntryEvent[]>([]);
  const [historyName, setHistoryName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [checkingId, setCheckingId] = useState<number>();
  const [form, setForm] = useState(emptyForm);

  const loadData = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const [groupRes, optionRes] = await Promise.all([getCrossEntryGroups(), getCrossEntryForwardOptions()]);
    if (groupRes.code === 0) {
      setGroups(groupRes.data?.groups || []);
      setSummary(groupRes.data?.summary || emptySummary);
    } else if (!quiet) toast.error(groupRes.msg || '加载入口容灾失败');
    if (optionRes.code === 0) setForwardOptions(optionRes.data || []);
    if (!quiet) setLoading(false);
  }, []);

  useEffect(() => {
    void loadData();
    const timer = window.setInterval(() => void loadData(true), 5000);
    return () => window.clearInterval(timer);
  }, [loadData]);

  const selectedOptions = useMemo(() => form.memberForwardIds.map(id => forwardOptions.find(item => String(item.id) === id)), [form.memberForwardIds, forwardOptions]);
  const selectedPort = selectedOptions.find(Boolean)?.inPort;
  const selectionProblem = useMemo(() => {
    const selected = selectedOptions.filter(Boolean) as CrossEntryForwardOption[];
    if (selected.length < 2) return '至少选择两个不同入口的转发';
    if (new Set(selected.map(item => item.inNodeId)).size !== selected.length) return '候选转发必须来自不同入口节点';
    if (new Set(selected.map(item => item.inPort)).size !== 1) return '所有候选转发必须使用相同公网端口';
    return '';
  }, [selectedOptions]);

  const openCreate = () => {
    setForm(emptyForm);
    setFormOpen(true);
  };

  const openEdit = (group: CrossEntryGroup) => {
    const profile = (Object.entries(profiles).find(([, item]) => item.interval === group.probeIntervalMs
      && item.timeout === group.connectTimeoutMs && item.failures === group.failureThreshold)?.[0] || 'custom') as ProfileKey;
    setForm({
      id: group.id, name: group.name, domain: group.domain, zoneId: group.zoneId, recordId: group.recordId, apiToken: '',
      recordType: group.recordType, ttl: String(group.ttl), profile, probeIntervalMs: String(group.probeIntervalMs),
      connectTimeoutMs: String(group.connectTimeoutMs), failureThreshold: String(group.failureThreshold),
      recoveryThreshold: String(group.recoveryThreshold), cooldownSeconds: String(group.cooldownSeconds),
      autoFailback: truthy(group.autoFailback), enabled: truthy(group.enabled), memberForwardIds: group.members.map(item => String(item.forwardId)),
    });
    setFormOpen(true);
  };

  const selectProfile = (profile: PresetProfileKey) => {
    const value = profiles[profile];
    setForm(current => ({ ...current, profile, probeIntervalMs: String(value.interval), connectTimeoutMs: String(value.timeout), failureThreshold: String(value.failures), recoveryThreshold: String(value.recovery) }));
  };

  const submit = async () => {
    if (!form.name.trim() || !form.domain.trim() || !form.zoneId.trim()) return toast.error('请填写名称、域名和 Zone ID');
    if (!form.id && !form.apiToken.trim()) return toast.error('首次创建需要填写 Cloudflare API Token');
    if (selectionProblem) return toast.error(selectionProblem);
    setSubmitting(true);
    const response = await saveCrossEntryGroup({
      ...form,
      ttl: Number(form.ttl), probeIntervalMs: Number(form.probeIntervalMs), connectTimeoutMs: Number(form.connectTimeoutMs),
      failureThreshold: Number(form.failureThreshold), recoveryThreshold: Number(form.recoveryThreshold),
      cooldownSeconds: Number(form.cooldownSeconds), memberForwardIds: form.memberForwardIds.map(Number),
    });
    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || '保存入口容灾失败');
    toast.success(form.id ? '容灾组已更新' : '容灾组已创建，DNS 已指向主入口');
    setFormOpen(false);
    void loadData();
  };

  const checkNow = async (id: number) => {
    setCheckingId(id);
    const response = await checkCrossEntryGroup(id);
    setCheckingId(undefined);
    if (response.code !== 0) return toast.error(response.msg || '入口检测失败');
    setGroups(response.data?.groups || []);
    setSummary(response.data?.summary || emptySummary);
    toast.success('入口检测已完成');
  };

  const remove = async (group: CrossEntryGroup) => {
    if (!window.confirm(`确认删除“${group.name}”吗？现有转发不会被删除。`)) return;
    const response = await deleteCrossEntryGroup(group.id);
    if (response.code !== 0) return toast.error(response.msg || '删除失败');
    toast.success('容灾组已删除');
    void loadData();
  };

  const showHistory = async (group: CrossEntryGroup) => {
    const response = await getCrossEntryEvents(group.id);
    if (response.code !== 0) return toast.error(response.msg || '加载切换历史失败');
    setEvents(response.data || []);
    setHistoryName(group.name);
    setHistoryOpen(true);
  };

  if (loading) return <div className="flex min-h-[50vh] items-center justify-center"><Spinner label="加载入口容灾" /></div>;

  return (
    <div className="mx-auto w-full max-w-[1600px] space-y-6 p-4 sm:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div><p className="text-sm text-default-500">公网入口高可用</p><h1 className="mt-1 text-2xl font-semibold">入口容灾</h1></div>
        <Button color="primary" startContent={<Plus size={18} />} onPress={openCreate}>新建容灾组</Button>
      </header>

      <section className="grid grid-cols-2 border-y border-divider sm:grid-cols-4" aria-label="入口容灾概况">
        {[
          ['运行组', summary.enabled, <ShieldCheck key="shield" className="h-5 w-5 text-primary" />],
          ['健康', summary.healthy, <CheckCircle2 key="healthy" className="h-5 w-5 text-success" />],
          ['需处理', summary.degraded, <TriangleAlert key="warning" className="h-5 w-5 text-warning" />],
          ['累计切换', summary.switches, <Activity key="switches" className="h-5 w-5 text-secondary" />],
        ].map(([label, value, icon], index) => (
          <div key={String(label)} className={`flex min-h-24 items-center justify-between px-4 py-4 sm:px-6 ${index % 2 ? '' : 'border-r border-divider'} ${index === 1 ? 'sm:border-r' : ''}`}>
            <div><p className="text-xs text-default-500">{label}</p><p className="mt-1 text-2xl font-semibold">{value}</p></div>{icon}
          </div>
        ))}
      </section>

      {groups.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-center text-default-500">
          <ShieldCheck className="h-9 w-9" /><p>暂无跨入口容灾组</p>
        </div>
      ) : (
        <section className="grid gap-4 xl:grid-cols-2">
          {groups.map(group => {
            const meta = truthy(group.enabled) ? stateMeta(group.state) : { label: '已停用', color: 'default' as const };
            const active = group.members.find(item => item.id === group.activeMemberId);
            return (
              <Card key={group.id} radius="sm" shadow="none" className="border border-divider bg-content1">
                <CardBody className="gap-4 p-4 sm:p-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0"><div className="flex items-center gap-2"><h2 className="truncate text-base font-semibold">{group.name}</h2><Chip size="sm" variant="flat" color={meta.color}>{meta.label}</Chip></div><p className="mt-1 truncate text-sm text-default-500">{group.domain}:{group.members[0]?.entryPort || '-'}</p></div>
                    <div className="flex items-center gap-1">
                      <Button isIconOnly size="sm" variant="light" title="立即检测" aria-label="立即检测" isLoading={checkingId === group.id} onPress={() => checkNow(group.id)}><RefreshCw size={17} /></Button>
                      <Button isIconOnly size="sm" variant="light" title="切换历史" aria-label="切换历史" onPress={() => showHistory(group)}><History size={17} /></Button>
                      <Button isIconOnly size="sm" variant="light" title="编辑" aria-label="编辑" onPress={() => openEdit(group)}><Pencil size={17} /></Button>
                      <Button isIconOnly size="sm" color="danger" variant="light" title="删除" aria-label="删除" onPress={() => remove(group)}><Trash2 size={17} /></Button>
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-x-4 gap-y-3 border-y border-divider py-3 text-sm sm:grid-cols-4">
                    <div><p className="text-xs text-default-500">当前入口</p><p className="mt-1 truncate font-medium">{active?.nodeName || '未确定'}</p></div>
                    <div><p className="text-xs text-default-500">检测周期</p><p className="mt-1 font-medium">{group.probeIntervalMs / 1000} 秒</p></div>
                    <div><p className="text-xs text-default-500">失败阈值</p><p className="mt-1 font-medium">连续 {group.failureThreshold} 次</p></div>
                    <div><p className="text-xs text-default-500">上次切换</p><p className="mt-1 truncate font-medium">{group.lastSwitchAt ? timeText(group.lastSwitchAt) : '未切换'}</p></div>
                  </div>

                  <div className="space-y-2">
                    {group.members.map((member, index) => {
                      const isActive = member.id === group.activeMemberId;
                      return (
                        <div key={member.id} className={`grid min-h-16 grid-cols-[minmax(0,1fr)_auto] items-center gap-3 border-l-2 px-3 py-2 ${isActive ? 'border-primary bg-primary-50/50 dark:bg-primary-500/5' : 'border-divider'}`}>
                          <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><span className="truncate text-sm font-medium">{member.nodeName}</span><Chip size="sm" variant="flat" color={member.status === 'healthy' ? 'success' : member.status === 'unhealthy' ? 'danger' : 'default'}>{member.status === 'healthy' ? '可用' : member.status === 'unhealthy' ? '不可用' : '检测中'}</Chip>{isActive && <Chip size="sm" color="primary" variant="flat">当前承载</Chip>}</div><p className="mt-1 truncate text-xs text-default-500">{index === 0 ? '主入口' : `备用 ${index}`} · {member.entryAddress}:{member.entryPort} · {member.forwardName}</p></div>
                          <div className="text-right text-xs"><p className="font-medium">{member.latencyMs ? `${member.latencyMs} ms` : '-'}</p><p className="mt-1 text-default-500">失败 {member.failCount}/{group.failureThreshold}</p></div>
                        </div>
                      );
                    })}
                  </div>
                  <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-default-500"><span>最近检测：{timeText(group.lastCheckedAt)}</span><span>{truthy(group.autoFailback) ? '主入口恢复后自动回切' : '切换后保持当前入口'}</span></div>
                  {group.lastError && <p className="rounded-md bg-danger-50 px-3 py-2 text-xs text-danger dark:bg-danger-500/10">{group.lastError}</p>}
                </CardBody>
              </Card>
            );
          })}
        </section>
      )}

      <Modal isOpen={formOpen} onOpenChange={setFormOpen} size="3xl" scrollBehavior="inside">
        <ModalContent>
          <ModalHeader>{form.id ? '编辑入口容灾组' : '新建入口容灾组'}</ModalHeader>
          <ModalBody className="gap-5">
            <section className="grid gap-3 sm:grid-cols-2">
              <Input label="容灾组名称" value={form.name} onValueChange={name => setForm({ ...form, name })} />
              <Input label="业务域名" placeholder="service.example.com" value={form.domain} onValueChange={domain => setForm({ ...form, domain })} />
              <Input label="Cloudflare Zone ID" value={form.zoneId} onValueChange={zoneId => setForm({ ...form, zoneId })} />
              <Input type="password" label={form.id ? 'Cloudflare API Token（留空不修改）' : 'Cloudflare API Token'} value={form.apiToken} onValueChange={apiToken => setForm({ ...form, apiToken })} />
              <Select label="DNS 记录类型" selectedKeys={[form.recordType]} onSelectionChange={keys => setForm({ ...form, recordType: String(Array.from(keys)[0]) as 'A' | 'AAAA' })}><SelectItem key="A">A（IPv4）</SelectItem><SelectItem key="AAAA">AAAA（IPv6）</SelectItem></Select>
              <Input label="DNS TTL（秒）" type="number" min={60} max={86400} value={form.ttl} onValueChange={ttl => setForm({ ...form, ttl })} />
            </section>

            <section className="border-t border-divider pt-4"><div className="mb-3 flex items-center justify-between gap-3"><div><h3 className="text-sm font-semibold">入口顺序</h3><p className="mt-1 text-xs text-default-500">第一条为主入口，其余按顺序作为备用入口。公网端口必须相同。</p></div><Chip size="sm" variant="flat">端口 {selectedPort || '-'}</Chip></div>
              <div className="space-y-2">
                {form.memberForwardIds.map((id, index) => (
                  <div key={`${index}-${id}`} className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-2">
                    <span className="w-14 text-xs font-medium text-default-500">{index === 0 ? '主入口' : `备用 ${index}`}</span>
                    <Select aria-label={index === 0 ? '主入口转发' : `备用入口 ${index}`} placeholder="选择一个现有转发" selectedKeys={id ? [id] : []} onSelectionChange={keys => { const values = [...form.memberForwardIds]; values[index] = String(Array.from(keys)[0] || ''); setForm({ ...form, memberForwardIds: values }); }}>
                      {forwardOptions.map(option => <SelectItem key={String(option.id)} textValue={`${option.nodeName} ${option.name}`}>{option.nodeName} · {option.entryHost}:{option.inPort} · {option.name}</SelectItem>)}
                    </Select>
                    <Button isIconOnly variant="light" aria-label="移除入口" isDisabled={form.memberForwardIds.length <= 2} onPress={() => setForm({ ...form, memberForwardIds: form.memberForwardIds.filter((_, current) => current !== index) })}><X size={17} /></Button>
                  </div>
                ))}
              </div>
              <Button className="mt-3" size="sm" variant="flat" startContent={<Plus size={16} />} isDisabled={form.memberForwardIds.length >= 10} onPress={() => setForm({ ...form, memberForwardIds: [...form.memberForwardIds, ''] })}>添加备用入口</Button>
              {selectionProblem && <p className="mt-2 text-xs text-warning">{selectionProblem}</p>}
            </section>

            <section className="border-t border-divider pt-4"><h3 className="text-sm font-semibold">失效检测</h3><div className="mt-3 grid grid-cols-3 gap-2">{(Object.keys(profiles) as PresetProfileKey[]).map(key => <button type="button" key={key} onClick={() => selectProfile(key)} className={`min-h-20 rounded-md border p-3 text-left transition-colors ${form.profile === key ? 'border-primary bg-primary-50 dark:bg-primary-500/10' : 'border-divider hover:bg-default-100'}`}><span className="text-sm font-medium">{profiles[key].label}</span><span className="mt-1 block text-xs text-default-500">{profiles[key].note}</span></button>)}</div>
              <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-5"><Input type="number" label="探测间隔（毫秒）" value={form.probeIntervalMs} onValueChange={probeIntervalMs => setForm({ ...form, profile: 'custom', probeIntervalMs })} /><Input type="number" label="连接超时（毫秒）" value={form.connectTimeoutMs} onValueChange={connectTimeoutMs => setForm({ ...form, profile: 'custom', connectTimeoutMs })} /><Input type="number" label="连续失败次数" value={form.failureThreshold} onValueChange={failureThreshold => setForm({ ...form, profile: 'custom', failureThreshold })} /><Input type="number" label="恢复确认次数" value={form.recoveryThreshold} onValueChange={recoveryThreshold => setForm({ ...form, profile: 'custom', recoveryThreshold })} /><Input type="number" label="回切冷却（秒）" value={form.cooldownSeconds} onValueChange={cooldownSeconds => setForm({ ...form, profile: 'custom', cooldownSeconds })} /></div>
              <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"><Switch isSelected={form.autoFailback} onValueChange={autoFailback => setForm({ ...form, autoFailback })}>主入口恢复后自动回切</Switch><Switch isSelected={form.enabled} onValueChange={enabled => setForm({ ...form, enabled })}>启用自动检测</Switch></div>
            </section>

            <div className="rounded-md bg-warning-50 px-3 py-3 text-xs leading-5 text-warning-700 dark:bg-warning-500/10 dark:text-warning-300">Cloudflare 记录必须关闭代理，仅使用 DNS。请确保面板服务器能访问各公网入口端口。检测和 DNS 更新可在数秒内完成，但运营商及客户端 DNS 缓存仍可能延迟实际生效；已经建立的连接需要重新连接。</div>
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setFormOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submit}>保存并同步 DNS</Button></ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={historyOpen} onOpenChange={setHistoryOpen} size="2xl" scrollBehavior="inside">
        <ModalContent><ModalHeader>{historyName} · 切换历史</ModalHeader><ModalBody>{events.length === 0 ? <div className="py-12 text-center text-sm text-default-500">暂无切换记录</div> : <div className="divide-y divide-divider">{events.map(event => <div key={event.id} className="flex gap-3 py-3"><div className={`mt-1 h-2.5 w-2.5 flex-none rounded-full ${event.status === 'success' ? 'bg-success' : 'bg-danger'}`} /><div className="min-w-0 flex-1"><div className="flex flex-wrap items-center justify-between gap-2"><p className="text-sm font-medium">{event.reason}</p><span className="text-xs text-default-500">{timeText(event.createdTime)}</span></div>{(event.fromNodeName || event.toNodeName) && <p className="mt-1 flex items-center gap-1 text-xs text-default-500"><span>{event.fromNodeName || '初始'}</span><ArrowRight size={12} /><span>{event.toNodeName || '-'}</span></p>}<p className="mt-1 text-xs text-default-500">{event.detail}</p></div></div>)}</div>}</ModalBody><ModalFooter><Button variant="flat" onPress={() => setHistoryOpen(false)}>关闭</Button></ModalFooter></ModalContent>
      </Modal>
    </div>
  );
}
