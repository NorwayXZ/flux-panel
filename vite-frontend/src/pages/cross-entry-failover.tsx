import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@heroui/button';
import { Card, CardBody } from '@heroui/card';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem, SelectSection } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { Activity, ArrowDown, ArrowRight, ArrowUp, CheckCircle2, History, Pencil, Plus, RefreshCw, ShieldCheck, Trash2, TriangleAlert, X } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  checkCrossEntryGroup,
  deleteCrossEntryGroup,
  getCrossEntryEvents,
  getCrossEntryForwardOptions,
  getCrossEntryGroups,
  getCrossEntryProbeSources,
  getDnsZoneOptions,
  saveCrossEntryGroup,
  type CrossEntryEvent,
  type CrossEntryForwardOption,
  type CrossEntryGroup,
  type CrossEntryProbeSourceOverview,
  type CrossEntrySummary,
  type DnsZoneOption,
} from '@/api';
import { groupForwardOptionsByPort } from '@/utils/forward-option-groups';

type PresetProfileKey = 'fast' | 'standard' | 'stable';
type ProfileKey = PresetProfileKey | 'custom';

const profiles: Record<PresetProfileKey, { label: string; interval: number; timeout: number; failures: number; recovery: number; note: string }> = {
  fast: { label: '极速', interval: 2000, timeout: 1200, failures: 2, recovery: 3, note: '约 3-6 秒触发切换' },
  standard: { label: '标准', interval: 5000, timeout: 2000, failures: 2, recovery: 3, note: '约 8-15 秒触发切换' },
  stable: { label: '稳健', interval: 10000, timeout: 3000, failures: 3, recovery: 4, note: '适合网络波动较大的入口' },
};

const emptySummary: CrossEntrySummary = { total: 0, enabled: 0, healthy: 0, degraded: 0, switches: 0 };
const emptyProbeSources: CrossEntryProbeSourceOverview = { nodes: [], connectors: [], minimumRemoteVersion: '2.19.0' };
const emptyForm = {
  id: undefined as number | undefined,
  name: '', domain: '', dnsZoneId: '', recordId: '', recordType: 'A' as 'A' | 'AAAA', ttl: '60',
  profile: 'fast' as ProfileKey, probeIntervalMs: '2000', connectTimeoutMs: '1200', failureThreshold: '2',
  recoveryThreshold: '3', cooldownSeconds: '30', autoFailback: false, routingMode: 'failover' as 'failover' | 'active_active', enabled: true,
  qualityEnabled: false, qualityProbeSourceType: 'panel' as 'panel' | 'node' | 'connector', qualityProbeSourceId: '',
  qualityProbeCount: '4', qualityDegradeThresholdMs: '100', qualityRecoverThresholdMs: '60', qualityDegradeFactor: '3',
  qualityRecoverFactor: '1.8', qualityDegradeSamples: '3', qualityRecoverSamples: '3', qualityLossThresholdPercent: '30',
  qualityP95ThresholdMs: '100', qualityJitterThresholdMs: '50',
  qualityFixedTargetEnabled: false, qualityFixedTargetMs: '20', qualityFixedTargetStrict: true,
  qualityFlapGuardEnabled: true, qualityFlapWindowSeconds: '900', qualityFlapThreshold: '3', qualityFlapSuppressSeconds: '1800',
  smartSelectionEnabled: true, degradedFallbackEnabled: true, sameFaultAvoidanceEnabled: true, topologyAvoidanceEnabled: true,
  minResidencySeconds: '300', failbackGainMs: '10', failbackGainPercent: '20',
  preheatEnabled: true, preheatBackupCount: '3', preheatStrictIsolation: true, postSwitchVerifyEnabled: true, dnsVerifyEnabled: true,
  manualControlMode: 'auto' as 'auto' | 'pause' | 'lock', lockedMemberId: '',
  memberForwardIds: ['', ''],
};

const truthy = (value: boolean | number) => value === true || value === 1;
const timeText = (value?: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未检测';
const metricText = (value?: number, unit = '') => typeof value === 'number' && Number.isFinite(value) ? `${Math.round(value * 10) / 10}${unit}` : '-';
const durationText = (seconds?: number) => {
  const value = Number(seconds || 0);
  if (!Number.isFinite(value) || value <= 0) return '-';
  if (value % 3600 === 0) return `${value / 3600} 小时`;
  if (value >= 3600) return `${Math.round(value / 360) / 10} 小时`;
  if (value % 60 === 0) return `${value / 60} 分钟`;
  return `${value} 秒`;
};
const eventRouteText = (event?: CrossEntryEvent) => {
  if (!event) return '暂无自动切换';
  const from = event.fromForwardName || event.fromNodeName || '初始状态';
  const to = event.toForwardName || event.toNodeName || '未知入口';
  return `${from} → ${to}`;
};
const eventEndpointText = (event?: CrossEntryEvent) => {
  if (!event) return '';
  const from = event.fromEntryAddress && event.fromEntryPort ? `${event.fromEntryAddress}:${event.fromEntryPort}` : '';
  const to = event.toEntryAddress && event.toEntryPort ? `${event.toEntryAddress}:${event.toEntryPort}` : '';
  return from && to ? `${from} → ${to}` : to || from;
};
const stateMeta = (state: CrossEntryGroup['state']) => ({
  healthy: { label: '运行正常', color: 'success' as const },
  degraded: { label: '部分异常', color: 'warning' as const },
  offline: { label: '全部离线', color: 'danger' as const },
  switching: { label: '切换中', color: 'warning' as const },
  error: { label: '切换失败', color: 'danger' as const },
  unknown: { label: '等待检测', color: 'default' as const },
})[state] || { label: '等待检测', color: 'default' as const };
const qualityMeta = (state?: CrossEntryGroup['members'][number]['qualityState']) => ({
  healthy: { label: '质量正常', color: 'success' as const },
  degraded: { label: '质量劣化', color: 'warning' as const },
  warming: { label: '学习中', color: 'secondary' as const },
  unknown: { label: '待学习', color: 'default' as const },
})[state || 'unknown'] || { label: '待学习', color: 'default' as const };
const qualityProbeMeta = (status?: CrossEntryGroup['qualityProbeStatus']) => ({
  ok: { label: '探测正常', color: 'success' as const },
  warning: { label: '质量告警', color: 'warning' as const },
  failed: { label: '探测失败', color: 'danger' as const },
  pending: { label: '等待探测', color: 'secondary' as const },
  disabled: { label: '未启用', color: 'default' as const },
})[status || 'disabled'] || { label: '未启用', color: 'default' as const };
const manualControlMeta = (mode?: CrossEntryGroup['manualControlMode']) => ({
  pause: { label: '已暂停自动切换', color: 'warning' as const },
  lock: { label: '已锁定入口', color: 'secondary' as const },
  auto: { label: '自动选择', color: 'default' as const },
})[mode || 'auto'] || { label: '自动选择', color: 'default' as const };

export default function CrossEntryFailoverPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [groups, setGroups] = useState<CrossEntryGroup[]>([]);
  const [summary, setSummary] = useState<CrossEntrySummary>(emptySummary);
  const [forwardOptions, setForwardOptions] = useState<CrossEntryForwardOption[]>([]);
  const [zoneOptions, setZoneOptions] = useState<DnsZoneOption[]>([]);
  const [probeSources, setProbeSources] = useState<CrossEntryProbeSourceOverview>(emptyProbeSources);
  const [formOpen, setFormOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [events, setEvents] = useState<CrossEntryEvent[]>([]);
  const [historyName, setHistoryName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [checkingId, setCheckingId] = useState<number>();
  const [form, setForm] = useState(emptyForm);

  const loadData = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const [groupRes, optionRes, zoneRes] = await Promise.all([
      getCrossEntryGroups(),
      getCrossEntryForwardOptions(),
      getDnsZoneOptions(),
    ]);
    if (groupRes.code === 0) {
      setGroups(groupRes.data?.groups || []);
      setSummary(groupRes.data?.summary || emptySummary);
    } else if (!quiet) toast.error(groupRes.msg || '加载入口容灾失败');
    if (optionRes.code === 0) setForwardOptions(optionRes.data || []);
    if (zoneRes.code === 0) setZoneOptions(zoneRes.data || []);
    void getCrossEntryProbeSources().then(response => {
      if (response.code === 0) setProbeSources(response.data || emptyProbeSources);
    });
    if (!quiet) setLoading(false);
  }, []);

  useEffect(() => {
    void loadData();
    const timer = window.setInterval(() => void loadData(true), 5000);
    return () => window.clearInterval(timer);
  }, [loadData]);

  const selectedOptions = useMemo(() => form.memberForwardIds.map(id => forwardOptions.find(item => String(item.id) === id)), [form.memberForwardIds, forwardOptions]);
  const forwardGroups = useMemo(() => groupForwardOptionsByPort(forwardOptions), [forwardOptions]);
  const selectedZone = useMemo(() => zoneOptions.find(item => String(item.id) === form.dnsZoneId), [form.dnsZoneId, zoneOptions]);
  const lockableMembers = useMemo(() => groups.find(item => item.id === form.id)?.members || [], [form.id, groups]);
  const selectedPort = selectedOptions.find(Boolean)?.inPort;
  const selectionProblem = useMemo(() => {
    const selected = selectedOptions.filter(Boolean) as CrossEntryForwardOption[];
    if (selected.length < 2) return '至少选择两个不同入口的转发';
    if (new Set(selected.map(item => item.inNodeId)).size !== selected.length) return '候选转发必须来自不同入口节点';
    if (new Set(selected.map(item => item.inPort)).size !== 1) return '所有候选转发必须使用相同公网端口';
    return '';
  }, [selectedOptions]);
  const recentSwitches = useMemo(() => groups
    .filter(group => group.lastSwitchEvent)
    .map(group => ({ group, event: group.lastSwitchEvent as CrossEntryEvent })), [groups]);

  const openCreate = () => {
    setForm(emptyForm);
    setFormOpen(true);
  };

  const openEdit = (group: CrossEntryGroup) => {
    const profile = (Object.entries(profiles).find(([, item]) => item.interval === group.probeIntervalMs
      && item.timeout === group.connectTimeoutMs && item.failures === group.failureThreshold)?.[0] || 'custom') as ProfileKey;
    setForm({
      id: group.id, name: group.name, domain: group.domain, dnsZoneId: group.dnsZoneId ? String(group.dnsZoneId) : '', recordId: group.recordId,
      recordType: group.recordType, ttl: String(group.ttl), profile, probeIntervalMs: String(group.probeIntervalMs),
      connectTimeoutMs: String(group.connectTimeoutMs), failureThreshold: String(group.failureThreshold),
      recoveryThreshold: String(group.recoveryThreshold), cooldownSeconds: String(group.cooldownSeconds),
      autoFailback: truthy(group.autoFailback), routingMode: group.routingMode || 'failover', enabled: truthy(group.enabled),
      qualityEnabled: truthy(group.qualityEnabled || false),
      qualityProbeSourceType: group.qualityProbeSourceType || 'panel',
      qualityProbeSourceId: group.qualityProbeSourceId ? String(group.qualityProbeSourceId) : '',
      qualityProbeCount: String(group.qualityProbeCount || 4),
      qualityDegradeThresholdMs: String(group.qualityDegradeThresholdMs || 100),
      qualityRecoverThresholdMs: String(group.qualityRecoverThresholdMs || 60),
      qualityDegradeFactor: String(group.qualityDegradeFactor || 3),
      qualityRecoverFactor: String(group.qualityRecoverFactor || 1.8),
      qualityDegradeSamples: String(group.qualityDegradeSamples || 3),
      qualityRecoverSamples: String(group.qualityRecoverSamples || 3),
      qualityLossThresholdPercent: String(group.qualityLossThresholdPercent || 30),
      qualityP95ThresholdMs: String(group.qualityP95ThresholdMs || 100),
      qualityJitterThresholdMs: String(group.qualityJitterThresholdMs || 50),
      qualityFixedTargetEnabled: truthy(group.qualityFixedTargetEnabled || false),
      qualityFixedTargetMs: String(group.qualityFixedTargetMs || 20),
      qualityFixedTargetStrict: !Number.isFinite(Number(group.qualityFixedTargetStrict)) ? truthy(group.qualityFixedTargetStrict ?? true) : Number(group.qualityFixedTargetStrict) !== 0,
      qualityFlapGuardEnabled: !Number.isFinite(Number(group.qualityFlapGuardEnabled)) ? truthy(group.qualityFlapGuardEnabled ?? true) : Number(group.qualityFlapGuardEnabled) !== 0,
      qualityFlapWindowSeconds: String(group.qualityFlapWindowSeconds || 900),
      qualityFlapThreshold: String(group.qualityFlapThreshold || 3),
      qualityFlapSuppressSeconds: String(group.qualityFlapSuppressSeconds || 1800),
      smartSelectionEnabled: !Number.isFinite(Number(group.smartSelectionEnabled)) ? truthy(group.smartSelectionEnabled ?? true) : Number(group.smartSelectionEnabled) !== 0,
      degradedFallbackEnabled: !Number.isFinite(Number(group.degradedFallbackEnabled)) ? truthy(group.degradedFallbackEnabled ?? true) : Number(group.degradedFallbackEnabled) !== 0,
      sameFaultAvoidanceEnabled: !Number.isFinite(Number(group.sameFaultAvoidanceEnabled)) ? truthy(group.sameFaultAvoidanceEnabled ?? true) : Number(group.sameFaultAvoidanceEnabled) !== 0,
      topologyAvoidanceEnabled: !Number.isFinite(Number(group.topologyAvoidanceEnabled)) ? truthy(group.topologyAvoidanceEnabled ?? true) : Number(group.topologyAvoidanceEnabled) !== 0,
      minResidencySeconds: String(group.minResidencySeconds ?? 300),
      failbackGainMs: String(group.failbackGainMs ?? 10),
      failbackGainPercent: String(group.failbackGainPercent ?? 20),
      preheatEnabled: !Number.isFinite(Number(group.preheatEnabled)) ? truthy(group.preheatEnabled ?? true) : Number(group.preheatEnabled) !== 0,
      preheatBackupCount: String(group.preheatBackupCount ?? 3),
      preheatStrictIsolation: !Number.isFinite(Number(group.preheatStrictIsolation)) ? truthy(group.preheatStrictIsolation ?? true) : Number(group.preheatStrictIsolation) !== 0,
      postSwitchVerifyEnabled: !Number.isFinite(Number(group.postSwitchVerifyEnabled)) ? truthy(group.postSwitchVerifyEnabled ?? true) : Number(group.postSwitchVerifyEnabled) !== 0,
      dnsVerifyEnabled: !Number.isFinite(Number(group.dnsVerifyEnabled)) ? truthy(group.dnsVerifyEnabled ?? true) : Number(group.dnsVerifyEnabled) !== 0,
      manualControlMode: group.manualControlMode || 'auto',
      lockedMemberId: group.lockedMemberId ? String(group.lockedMemberId) : '',
      memberForwardIds: group.members.map(item => String(item.forwardId)),
    });
    setFormOpen(true);
  };

  const selectProfile = (profile: PresetProfileKey) => {
    const value = profiles[profile];
    setForm(current => ({ ...current, profile, probeIntervalMs: String(value.interval), connectTimeoutMs: String(value.timeout), failureThreshold: String(value.failures), recoveryThreshold: String(value.recovery) }));
  };

  const moveMember = (index: number, direction: -1 | 1) => {
    setForm(current => {
      const target = index + direction;
      if (target < 0 || target >= current.memberForwardIds.length) return current;
      const memberForwardIds = [...current.memberForwardIds];
      [memberForwardIds[index], memberForwardIds[target]] = [memberForwardIds[target], memberForwardIds[index]];
      return { ...current, memberForwardIds };
    });
  };

  const submit = async () => {
    if (!form.name.trim() || !form.domain.trim() || !form.dnsZoneId) return toast.error('请选择 Cloudflare Zone 并填写业务域名');
    if (selectionProblem) return toast.error(selectionProblem);
    if (form.routingMode === 'failover' && form.qualityEnabled && form.qualityProbeSourceType !== 'panel' && !form.qualityProbeSourceId) {
      return toast.error('请选择质量探测源');
    }
    if (form.routingMode === 'failover' && form.manualControlMode === 'lock' && !form.lockedMemberId) return toast.error('请选择要锁定的入口');
    setSubmitting(true);
    const response = await saveCrossEntryGroup({
      ...form,
      dnsZoneId: Number(form.dnsZoneId),
      ttl: Number(form.ttl), probeIntervalMs: Number(form.probeIntervalMs), connectTimeoutMs: Number(form.connectTimeoutMs),
      failureThreshold: Number(form.failureThreshold), recoveryThreshold: Number(form.recoveryThreshold),
      cooldownSeconds: Number(form.cooldownSeconds), memberForwardIds: form.memberForwardIds.map(Number),
      qualityEnabled: form.routingMode === 'failover' && form.qualityEnabled,
      qualityProbeSourceId: form.qualityProbeSourceType === 'panel' ? undefined : Number(form.qualityProbeSourceId),
      qualityProbeCount: Number(form.qualityProbeCount),
      qualityDegradeThresholdMs: Number(form.qualityDegradeThresholdMs),
      qualityRecoverThresholdMs: Number(form.qualityRecoverThresholdMs),
      qualityDegradeFactor: Number(form.qualityDegradeFactor),
      qualityRecoverFactor: Number(form.qualityRecoverFactor),
      qualityDegradeSamples: Number(form.qualityDegradeSamples),
      qualityRecoverSamples: Number(form.qualityRecoverSamples),
      qualityLossThresholdPercent: Number(form.qualityLossThresholdPercent),
      qualityP95ThresholdMs: Number(form.qualityP95ThresholdMs),
      qualityJitterThresholdMs: Number(form.qualityJitterThresholdMs),
      qualityFixedTargetEnabled: form.qualityFixedTargetEnabled,
      qualityFixedTargetMs: Number(form.qualityFixedTargetMs),
      qualityFixedTargetStrict: form.qualityFixedTargetStrict,
      qualityFlapGuardEnabled: form.qualityFlapGuardEnabled,
      qualityFlapWindowSeconds: Number(form.qualityFlapWindowSeconds),
      qualityFlapThreshold: Number(form.qualityFlapThreshold),
      qualityFlapSuppressSeconds: Number(form.qualityFlapSuppressSeconds),
      smartSelectionEnabled: form.smartSelectionEnabled,
      degradedFallbackEnabled: form.degradedFallbackEnabled,
      sameFaultAvoidanceEnabled: form.sameFaultAvoidanceEnabled,
      topologyAvoidanceEnabled: form.topologyAvoidanceEnabled,
      minResidencySeconds: Number(form.minResidencySeconds),
      failbackGainMs: Number(form.failbackGainMs),
      failbackGainPercent: Number(form.failbackGainPercent),
      preheatEnabled: form.preheatEnabled,
      preheatBackupCount: Number(form.preheatBackupCount),
      preheatStrictIsolation: form.preheatStrictIsolation,
      postSwitchVerifyEnabled: form.postSwitchVerifyEnabled,
      dnsVerifyEnabled: form.dnsVerifyEnabled,
      manualControlMode: form.routingMode === 'failover' ? form.manualControlMode : 'auto',
      lockedMemberId: form.routingMode === 'failover' && form.manualControlMode === 'lock' ? Number(form.lockedMemberId) : undefined,
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

      {recentSwitches.length > 0 && (
        <section className="border-y border-divider py-4" aria-label="最近入口切换">
          <div className="flex flex-wrap items-end justify-between gap-2">
            <div><h2 className="text-sm font-semibold">最近切换</h2><p className="mt-1 text-xs text-default-500">累计切换 {summary.switches} 次，下面显示每个容灾组最近一次的具体线路和触发原因。</p></div>
            <span className="text-xs text-default-500">完整记录可打开每张卡片右上角的历史按钮</span>
          </div>
          <div className="mt-3 grid gap-2 xl:grid-cols-2">
            {recentSwitches.map(({ group, event }) => (
              <div key={group.id} className="grid gap-2 border-l-2 border-secondary px-3 py-2 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center">
                <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><span className="text-sm font-medium">{group.name}</span><span className="text-xs text-default-500">{timeText(event.createdTime)}</span></div><p className="mt-1 flex items-center gap-1 text-sm"><span className="truncate">{eventRouteText(event)}</span><ArrowRight size={13} className="flex-none text-default-400" /><span className="truncate text-default-500">{event.reason}</span></p><p className="mt-1 truncate text-xs text-default-500">{eventEndpointText(event) || event.detail || '无线路地址记录'}</p></div>
                <Button size="sm" variant="flat" startContent={<History size={15} />} onPress={() => showHistory(group)}>查看历史</Button>
              </div>
            ))}
          </div>
        </section>
      )}

      {groups.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-center text-default-500">
          <ShieldCheck className="h-9 w-9" /><p>暂无跨入口容灾组</p>
        </div>
      ) : (
        <section className="grid gap-4 xl:grid-cols-2">
          {groups.map(group => {
            const meta = truthy(group.enabled) ? stateMeta(group.state) : { label: '已停用', color: 'default' as const };
            const active = group.members.find(item => item.id === group.activeMemberId);
            const activeActive = group.routingMode === 'active_active';
            const qualityEnabled = truthy(group.qualityEnabled || false);
            const fixedTargetEnabled = qualityEnabled && truthy(group.qualityFixedTargetEnabled || false);
            const flapGuardEnabled = qualityEnabled && truthy(group.qualityFlapGuardEnabled ?? true);
            const smartSelectionEnabled = qualityEnabled && truthy(group.smartSelectionEnabled ?? true);
            const probeMeta = qualityProbeMeta(group.qualityProbeStatus);
            const manualMeta = manualControlMeta(group.manualControlMode);
            return (
              <Card key={group.id} radius="sm" shadow="none" className="border border-divider bg-content1">
                <CardBody className="gap-4 p-4 sm:p-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h2 className="truncate text-base font-semibold">{group.name}</h2><Chip size="sm" variant="flat" color={meta.color}>{meta.label}</Chip><Chip size="sm" variant="flat" color={activeActive ? 'secondary' : 'default'}>{activeActive ? '多入口同时运行' : '主备容灾'}</Chip>{qualityEnabled && <Chip size="sm" variant="flat" color={probeMeta.color}>{probeMeta.label}</Chip>}{smartSelectionEnabled && <Chip size="sm" variant="flat" color="success">智能选择</Chip>}{fixedTargetEnabled && <Chip size="sm" variant="flat" color="secondary">目标 ≤ {group.qualityFixedTargetMs || 20} ms</Chip>}{flapGuardEnabled && <Chip size="sm" variant="flat" color="warning">抖动保护</Chip>}{group.manualControlMode && group.manualControlMode !== 'auto' && <Chip size="sm" variant="flat" color={manualMeta.color}>{manualMeta.label}</Chip>}</div><p className="mt-1 truncate text-sm text-default-500">{group.domain}:{group.members[0]?.entryPort || '-'}</p></div>
                    <div className="flex items-center gap-1">
                      <Button isIconOnly size="sm" variant="light" title="立即检测" aria-label="立即检测" isLoading={checkingId === group.id} onPress={() => checkNow(group.id)}><RefreshCw size={17} /></Button>
                      <Button isIconOnly size="sm" variant="light" title="切换历史" aria-label="切换历史" onPress={() => showHistory(group)}><History size={17} /></Button>
                      <Button isIconOnly size="sm" variant="light" title="编辑" aria-label="编辑" onPress={() => openEdit(group)}><Pencil size={17} /></Button>
                      <Button isIconOnly size="sm" color="danger" variant="light" title="删除" aria-label="删除" onPress={() => remove(group)}><Trash2 size={17} /></Button>
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-x-4 gap-y-3 border-y border-divider py-3 text-sm sm:grid-cols-4">
                    <div><p className="text-xs text-default-500">{activeActive ? 'DNS 锚点' : '当前入口'}</p><p className="mt-1 truncate font-medium">{active?.nodeName || '未确定'}</p></div>
                    <div><p className="text-xs text-default-500">检测周期</p><p className="mt-1 font-medium">{group.probeIntervalMs / 1000} 秒</p></div>
                    <div><p className="text-xs text-default-500">失败阈值</p><p className="mt-1 font-medium">连续 {group.failureThreshold} 次</p></div>
                    <div><p className="text-xs text-default-500">{activeActive ? '健康入口' : '上次切换'}</p><p className="mt-1 truncate font-medium">{activeActive ? `${group.members.filter(item => item.status === 'healthy').length}/${group.members.length} 条` : (group.lastSwitchEvent ? timeText(group.lastSwitchEvent.createdTime) : (group.lastSwitchAt ? timeText(group.lastSwitchAt) : '未切换'))}</p><p className="mt-1 truncate text-xs text-default-500">{activeActive ? 'DNS 仅返回健康入口' : eventRouteText(group.lastSwitchEvent)}</p></div>
                  </div>

                  <div className="space-y-2">
                    {group.members.map((member, index) => {
                      const isActive = member.id === group.activeMemberId;
                      const qMeta = qualityMeta(member.qualityState);
                      const suppressed = Boolean(member.qualitySuppressedUntil && member.qualitySuppressedUntil > Date.now());
                      const preheated = qualityEnabled && truthy(member.qualityPreheated || false);
                      return (
                        <div key={member.id} className={`grid min-h-16 grid-cols-[minmax(0,1fr)_auto] items-center gap-3 border-l-2 px-3 py-2 ${isActive ? 'border-primary bg-primary-50/50 dark:bg-primary-500/5' : 'border-divider'}`}>
                          <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><span className="truncate text-sm font-medium">{member.nodeName}</span><Chip size="sm" variant="flat" color={member.status === 'healthy' ? 'success' : member.status === 'unhealthy' ? 'danger' : 'default'}>{member.status === 'healthy' ? '可用' : member.status === 'unhealthy' ? '不可用' : '检测中'}</Chip>{qualityEnabled && <Chip size="sm" variant="flat" color={qMeta.color}>{qMeta.label}</Chip>}{preheated && <Chip size="sm" variant="flat" color="success">已预热</Chip>}{suppressed && <Chip size="sm" variant="flat" color="warning">保护中</Chip>}{isActive && <Chip size="sm" color="primary" variant="flat">{activeActive ? 'DNS 锚点' : '当前承载'}</Chip>}</div><p className="mt-1 truncate text-xs text-default-500">{activeActive ? `入口 ${index + 1}` : (index === 0 ? '主入口' : `备用 ${index}`)} · {member.entryAddress}:{member.entryPort} · {member.forwardName}</p>{suppressed && <p className="mt-1 truncate text-xs text-warning">抖动保护至 {timeText(member.qualitySuppressedUntil)}</p>}</div>
                          <div className="text-right text-xs"><p className="font-medium">{member.latencyMs ? `${member.latencyMs} ms` : '-'}</p>{qualityEnabled ? <p className="mt-1 text-default-500">均值 {metricText(member.qualityLatencyMs, ' ms')} · P95 {metricText(member.qualityP95Ms, ' ms')} · 抖动 {metricText(member.qualityJitterMs, ' ms')} · 丢包 {metricText(member.qualityLossPercent, '%')} · 基线 {metricText(member.qualityBaselineMs, ' ms')}</p> : <p className="mt-1 text-default-500">失败 {member.failCount}/{group.failureThreshold}</p>}</div>
                        </div>
                      );
                    })}
                  </div>
                  <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-default-500"><span>最近检测：{timeText(group.lastCheckedAt)}</span><span>{activeActive ? '健康入口同时写入 DNS；仅影响新连接' : (truthy(group.autoFailback) ? '主入口恢复后自动回切' : '切换后保持当前入口')}</span></div>
                  {qualityEnabled && group.qualityProbeError && <p className="rounded-md bg-warning-50 px-3 py-2 text-xs text-warning-700 dark:bg-warning-500/10 dark:text-warning-300">{group.qualityProbeError}</p>}
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
              <Select
                label="Cloudflare Zone"
                placeholder="选择已登记的域名区域"
                selectedKeys={form.dnsZoneId ? [form.dnsZoneId] : []}
                onSelectionChange={keys => setForm({ ...form, dnsZoneId: String(Array.from(keys)[0] || '') })}
              >
                {zoneOptions.map(zone => <SelectItem key={String(zone.id)} textValue={`${zone.accountName} ${zone.zoneName}`}>{zone.zoneName} · {zone.accountName}</SelectItem>)}
              </Select>
              <Input
                label="业务域名或主机记录"
                placeholder={selectedZone ? `例如 glglg 或 glglg.${selectedZone.zoneName}` : '先选择 Cloudflare Zone'}
                description={selectedZone ? `保存后自动创建 ${selectedZone.zoneName} 下的 DNS 记录` : '凭据和 Zone 在“资源中心 - 域名管理”中统一维护'}
                value={form.domain}
                onValueChange={domain => setForm({ ...form, domain })}
              />
              <Select label="DNS 记录类型" selectedKeys={[form.recordType]} onSelectionChange={keys => setForm({ ...form, recordType: String(Array.from(keys)[0]) as 'A' | 'AAAA' })}><SelectItem key="A">A（IPv4）</SelectItem><SelectItem key="AAAA">AAAA（IPv6）</SelectItem></Select>
              <Input label="DNS TTL（秒）" type="number" min={60} max={86400} value={form.ttl} onValueChange={ttl => setForm({ ...form, ttl })} />
              <Select label="入口调度模式" selectedKeys={[form.routingMode]} onSelectionChange={keys => setForm({ ...form, routingMode: String(Array.from(keys)[0] || 'failover') as 'failover' | 'active_active' })}>
                <SelectItem key="failover">主备容灾（默认）</SelectItem>
                <SelectItem key="active_active">多入口同时运行（DNS）</SelectItem>
              </Select>
            </section>

            <div className="border-l-2 border-primary bg-primary-50/60 px-3 py-3 text-xs leading-5 text-primary-700 dark:bg-primary-500/10 dark:text-primary-200">
              {form.routingMode === 'active_active'
                ? '所有健康入口会同时写入同一业务域名的 DNS 记录。客户端 DNS 解析后选择其中一个入口，失效入口会在检测确认后从记录集合摘除。它只影响新的解析和新连接，普通 DNS 不提供严格按权重的连接级均衡。'
                : '域名始终只指向一个当前入口。主入口连续失败后切到备用入口，适合希望地址稳定、只在故障时切换的业务。'}
            </div>

            {zoneOptions.length === 0 && (
              <div className="flex flex-col gap-3 border-y border-warning-200 bg-warning-50 px-3 py-3 text-sm text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200 sm:flex-row sm:items-center sm:justify-between">
                <span>尚未登记 Cloudflare 凭据。先同步 Zone，之后这里直接选择即可。</span>
                <Button size="sm" color="warning" variant="flat" onPress={() => { setFormOpen(false); navigate('/dns-settings'); }}>前往域名管理</Button>
              </div>
            )}

            <section className="border-t border-divider pt-4"><div className="mb-3 flex items-center justify-between gap-3"><div><h3 className="text-sm font-semibold">{form.routingMode === 'active_active' ? '入口成员' : '入口顺序'}</h3><p className="mt-1 text-xs text-default-500">{form.routingMode === 'active_active' ? '全部健康成员同时参与 DNS 返回；第一条用于保留兼容的 DNS 锚点。' : '第一条为主入口，其余按顺序作为备用入口。'} 公网端口必须相同。</p></div><Chip size="sm" variant="flat">端口 {selectedPort || '-'}</Chip></div>
              <div className="space-y-2">
                {form.memberForwardIds.map((id, index) => (
                  <div key={`${index}-${id}`} className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-2">
                    <span className="w-14 text-xs font-medium text-default-500">{form.routingMode === 'active_active' ? `入口 ${index + 1}` : (index === 0 ? '主入口' : `备用 ${index}`)}</span>
                    <Select aria-label={index === 0 ? '主入口转发' : `备用入口 ${index}`} placeholder="选择一个现有转发" selectedKeys={id ? [id] : []} onSelectionChange={keys => { const values = [...form.memberForwardIds]; values[index] = String(Array.from(keys)[0] || ''); setForm({ ...form, memberForwardIds: values }); }}>
                      {forwardGroups.map((group, groupIndex) => (
                        <SelectSection key={`port-${group.port}`} title={`端口 ${group.port} (${group.options.length})`} showDivider={groupIndex < forwardGroups.length - 1}>
                          {group.options.map(option => <SelectItem key={String(option.id)} textValue={`端口 ${option.inPort} ${option.nodeName} ${option.entryHost} ${option.name}`}>{option.nodeName} · {option.entryHost}:{option.inPort} · {option.name}</SelectItem>)}
                        </SelectSection>
                      ))}
                    </Select>
                    <div className="flex h-10 items-center gap-1">
                      <Button isIconOnly size="sm" variant="light" title="上移" aria-label="上移入口" isDisabled={index === 0} onPress={() => moveMember(index, -1)}><ArrowUp size={16} /></Button>
                      <Button isIconOnly size="sm" variant="light" title="下移" aria-label="下移入口" isDisabled={index === form.memberForwardIds.length - 1} onPress={() => moveMember(index, 1)}><ArrowDown size={16} /></Button>
                      <Button isIconOnly size="sm" variant="light" title="移除" aria-label="移除入口" isDisabled={form.memberForwardIds.length <= 2} onPress={() => setForm({ ...form, memberForwardIds: form.memberForwardIds.filter((_, current) => current !== index) })}><X size={17} /></Button>
                    </div>
                  </div>
                ))}
              </div>
              <Button className="mt-3" size="sm" variant="flat" startContent={<Plus size={16} />} isDisabled={form.memberForwardIds.length >= 10} onPress={() => setForm({ ...form, memberForwardIds: [...form.memberForwardIds, ''] })}>{form.routingMode === 'active_active' ? '添加入口成员' : '添加备用入口'}</Button>
              {selectionProblem && <p className="mt-2 text-xs text-warning">{selectionProblem}</p>}
            </section>

            <section className="border-t border-divider pt-4"><h3 className="text-sm font-semibold">失效检测</h3><div className="mt-3 grid grid-cols-3 gap-2">{(Object.keys(profiles) as PresetProfileKey[]).map(key => <button type="button" key={key} onClick={() => selectProfile(key)} className={`min-h-20 rounded-md border p-3 text-left transition-colors ${form.profile === key ? 'border-primary bg-primary-50 dark:bg-primary-500/10' : 'border-divider hover:bg-default-100'}`}><span className="text-sm font-medium">{profiles[key].label}</span><span className="mt-1 block text-xs text-default-500">{profiles[key].note}</span></button>)}</div>
              <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-5"><Input type="number" label="探测间隔（毫秒）" value={form.probeIntervalMs} onValueChange={probeIntervalMs => setForm({ ...form, profile: 'custom', probeIntervalMs })} /><Input type="number" label="连接超时（毫秒）" value={form.connectTimeoutMs} onValueChange={connectTimeoutMs => setForm({ ...form, profile: 'custom', connectTimeoutMs })} /><Input type="number" label="连续失败次数" value={form.failureThreshold} onValueChange={failureThreshold => setForm({ ...form, profile: 'custom', failureThreshold })} /><Input type="number" label="恢复确认次数" value={form.recoveryThreshold} onValueChange={recoveryThreshold => setForm({ ...form, profile: 'custom', recoveryThreshold })} /><Input type="number" label="回切冷却（秒）" value={form.cooldownSeconds} onValueChange={cooldownSeconds => setForm({ ...form, profile: 'custom', cooldownSeconds })} /></div>
              <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4 lg:items-center">
                {form.routingMode === 'failover' ? <Switch isSelected={form.autoFailback} onValueChange={autoFailback => setForm({ ...form, autoFailback })}>主入口恢复后自动回切</Switch> : <span className="text-xs text-default-500">多入口模式不回切，健康成员会自动恢复到 DNS 记录。</span>}
                <Switch isSelected={form.enabled} onValueChange={enabled => setForm({ ...form, enabled })}>启用自动检测</Switch>
                {form.routingMode === 'failover' && (
                  <Select
                    label="自动控制"
                    selectedKeys={[form.manualControlMode]}
                    onSelectionChange={keys => {
                      const manualControlMode = String(Array.from(keys)[0] || 'auto') as 'auto' | 'pause' | 'lock';
                      setForm({ ...form, manualControlMode, lockedMemberId: manualControlMode === 'lock' ? form.lockedMemberId : '' });
                    }}
                    disabledKeys={!form.id ? ['lock'] : []}
                  >
                    <SelectItem key="auto">自动选择</SelectItem>
                    <SelectItem key="pause">暂停自动切换</SelectItem>
                    <SelectItem key="lock">锁定指定入口</SelectItem>
                  </Select>
                )}
                {form.routingMode === 'failover' && form.manualControlMode === 'lock' && (
                  <Select label="锁定入口" placeholder="选择要固定承载的入口" selectedKeys={form.lockedMemberId ? [form.lockedMemberId] : []} onSelectionChange={keys => setForm({ ...form, lockedMemberId: String(Array.from(keys)[0] || '') })}>
                    {lockableMembers.map((member, index) => <SelectItem key={String(member.id)} textValue={`${member.nodeName} ${member.entryAddress}:${member.entryPort}`}>{index === 0 ? '主入口' : `备用 ${index}`} · {member.nodeName} · {member.entryAddress}:{member.entryPort}</SelectItem>)}
                  </Select>
                )}
              </div>
            </section>

            <section className="border-t border-divider pt-4">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div><h3 className="text-sm font-semibold">质量容灾</h3><p className="mt-1 text-xs text-default-500">按每条入口自己的基线判断劣化，绝对延迟只作为兜底阈值。</p></div>
                <Switch isSelected={form.routingMode === 'failover' && form.qualityEnabled} isDisabled={form.routingMode !== 'failover'} onValueChange={qualityEnabled => setForm({ ...form, qualityEnabled })}>启用质量切换</Switch>
              </div>
              {form.routingMode !== 'failover' ? (
                <p className="mt-3 text-xs text-default-500">多入口同时运行由 DNS 返回健康入口，质量切换只应用在主备容灾模式。</p>
              ) : form.qualityEnabled && (
                <div className="mt-3 grid gap-3">
                  <div className="grid gap-3 sm:grid-cols-2">
                    <Select
                      label="质量探测源"
                      selectedKeys={[form.qualityProbeSourceType]}
                      onSelectionChange={keys => setForm({ ...form, qualityProbeSourceType: String(Array.from(keys)[0] || 'panel') as 'panel' | 'node' | 'connector', qualityProbeSourceId: '' })}
                    >
                      <SelectItem key="panel">面板服务器</SelectItem>
                      <SelectItem key="node">指定 Agent 节点</SelectItem>
                      <SelectItem key="connector">指定 Connector</SelectItem>
                    </Select>
                    {form.qualityProbeSourceType === 'node' && (
                      <Select label="Agent 节点" placeholder={`Agent ≥ ${probeSources.minimumRemoteVersion}`} selectedKeys={form.qualityProbeSourceId ? [form.qualityProbeSourceId] : []} onSelectionChange={keys => setForm({ ...form, qualityProbeSourceId: String(Array.from(keys)[0] || '') })}>
                        {probeSources.nodes.map(source => <SelectItem key={String(source.id)} textValue={`${source.name} ${source.address || ''}`}>{source.name} · {source.address || '无地址'} · {source.version || '-'}</SelectItem>)}
                      </Select>
                    )}
                    {form.qualityProbeSourceType === 'connector' && (
                      <Select label="Connector" placeholder={`Connector ≥ ${probeSources.minimumRemoteVersion}`} selectedKeys={form.qualityProbeSourceId ? [form.qualityProbeSourceId] : []} onSelectionChange={keys => setForm({ ...form, qualityProbeSourceId: String(Array.from(keys)[0] || '') })}>
                        {probeSources.connectors.map(source => <SelectItem key={String(source.id)} textValue={`${source.name} ${source.platform || ''}`}>{source.name} · {source.platform || '-'} · {source.version || '-'}</SelectItem>)}
                      </Select>
                    )}
                  </div>
                  <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                    <Input type="number" label="TCP 次数" min={2} max={10} value={form.qualityProbeCount} onValueChange={qualityProbeCount => setForm({ ...form, qualityProbeCount })} />
                    <Input type="number" label="兜底劣化 ms" min={20} value={form.qualityDegradeThresholdMs} onValueChange={qualityDegradeThresholdMs => setForm({ ...form, qualityDegradeThresholdMs })} />
                    <Input type="number" label="恢复参考 ms" min={10} value={form.qualityRecoverThresholdMs} onValueChange={qualityRecoverThresholdMs => setForm({ ...form, qualityRecoverThresholdMs })} />
                    <Input type="number" label="丢包阈值 %" min={1} max={100} value={form.qualityLossThresholdPercent} onValueChange={qualityLossThresholdPercent => setForm({ ...form, qualityLossThresholdPercent })} />
                    <Input type="number" label="P95 阈值 ms" min={20} value={form.qualityP95ThresholdMs} onValueChange={qualityP95ThresholdMs => setForm({ ...form, qualityP95ThresholdMs })} />
                    <Input type="number" label="抖动阈值 ms" min={1} value={form.qualityJitterThresholdMs} onValueChange={qualityJitterThresholdMs => setForm({ ...form, qualityJitterThresholdMs })} />
                    <Input type="number" label="基线劣化倍数" min={1.2} step={0.1} value={form.qualityDegradeFactor} onValueChange={qualityDegradeFactor => setForm({ ...form, qualityDegradeFactor })} />
                    <Input type="number" label="基线恢复倍数" min={1} step={0.1} value={form.qualityRecoverFactor} onValueChange={qualityRecoverFactor => setForm({ ...form, qualityRecoverFactor })} />
                    <Input type="number" label="劣化确认次数" min={1} max={20} value={form.qualityDegradeSamples} onValueChange={qualityDegradeSamples => setForm({ ...form, qualityDegradeSamples })} />
                    <Input type="number" label="恢复确认次数" min={1} max={20} value={form.qualityRecoverSamples} onValueChange={qualityRecoverSamples => setForm({ ...form, qualityRecoverSamples })} />
                  </div>
                  <div className="grid gap-3 border-t border-divider pt-3 sm:grid-cols-[minmax(0,1fr)_220px] sm:items-center">
                    <Switch isSelected={form.qualityFixedTargetEnabled} onValueChange={qualityFixedTargetEnabled => setForm({ ...form, qualityFixedTargetEnabled })}>启用固定延迟目标</Switch>
                    <Input type="number" label="目标延迟 ms" min={1} value={form.qualityFixedTargetMs} isDisabled={!form.qualityFixedTargetEnabled} onValueChange={qualityFixedTargetMs => setForm({ ...form, qualityFixedTargetMs })} />
                    <Switch isSelected={form.qualityFixedTargetStrict} isDisabled={!form.qualityFixedTargetEnabled} onValueChange={qualityFixedTargetStrict => setForm({ ...form, qualityFixedTargetStrict })}>只切到达标备用入口</Switch>
                    <p className="text-xs leading-5 text-default-500">开启后，入口连续超过目标延迟会算作质量劣化；严格模式下，只会切到最近探测延迟小于等于目标值的备用入口。</p>
                  </div>
                  <div className="grid gap-3 border-t border-divider pt-3 sm:grid-cols-[minmax(0,1fr)_repeat(3,150px)] sm:items-center">
                    <Switch isSelected={form.qualityFlapGuardEnabled} onValueChange={qualityFlapGuardEnabled => setForm({ ...form, qualityFlapGuardEnabled })}>启用抖动保护</Switch>
                    <Input type="number" label="统计窗口（秒）" min={60} value={form.qualityFlapWindowSeconds} isDisabled={!form.qualityFlapGuardEnabled} onValueChange={qualityFlapWindowSeconds => setForm({ ...form, qualityFlapWindowSeconds })} />
                    <Input type="number" label="触发次数" min={2} max={20} value={form.qualityFlapThreshold} isDisabled={!form.qualityFlapGuardEnabled} onValueChange={qualityFlapThreshold => setForm({ ...form, qualityFlapThreshold })} />
                    <Input type="number" label="保护时长（秒）" min={60} value={form.qualityFlapSuppressSeconds} isDisabled={!form.qualityFlapGuardEnabled} onValueChange={qualityFlapSuppressSeconds => setForm({ ...form, qualityFlapSuppressSeconds })} />
                    <p className="text-xs leading-5 text-default-500 sm:col-span-4">默认规则：{durationText(Number(form.qualityFlapWindowSeconds))}内质量劣化 {form.qualityFlapThreshold || 3} 次，则该入口进入 {durationText(Number(form.qualityFlapSuppressSeconds))} 保护期。保护期内不会自动回切到它，也不会优先切到它，避免主入口 10ms/200ms 来回跳造成体验抖动。</p>
                  </div>
                  <div className="grid gap-3 border-t border-divider pt-3">
                    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                      <Switch isSelected={form.smartSelectionEnabled} onValueChange={smartSelectionEnabled => setForm({ ...form, smartSelectionEnabled })}>启用智能选择</Switch>
                      <Switch isSelected={form.degradedFallbackEnabled} isDisabled={!form.smartSelectionEnabled} onValueChange={degradedFallbackEnabled => setForm({ ...form, degradedFallbackEnabled })}>全部差时差中选优</Switch>
                      <Switch isSelected={form.sameFaultAvoidanceEnabled} isDisabled={!form.smartSelectionEnabled} onValueChange={sameFaultAvoidanceEnabled => setForm({ ...form, sameFaultAvoidanceEnabled })}>避开同类故障</Switch>
                      <Switch isSelected={form.topologyAvoidanceEnabled} isDisabled={!form.smartSelectionEnabled} onValueChange={topologyAvoidanceEnabled => setForm({ ...form, topologyAvoidanceEnabled })}>避开同节点/同大网段</Switch>
                    </div>
                    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                      <Input type="number" label="最短驻留（秒）" min={0} value={form.minResidencySeconds} isDisabled={!form.smartSelectionEnabled} onValueChange={minResidencySeconds => setForm({ ...form, minResidencySeconds })} />
                      <Input type="number" label="回切至少快 ms" min={0} value={form.failbackGainMs} isDisabled={!form.smartSelectionEnabled || !form.autoFailback} onValueChange={failbackGainMs => setForm({ ...form, failbackGainMs })} />
                      <Input type="number" label="回切至少快 %" min={0} max={100} value={form.failbackGainPercent} isDisabled={!form.smartSelectionEnabled || !form.autoFailback} onValueChange={failbackGainPercent => setForm({ ...form, failbackGainPercent })} />
                    </div>
                    <div className="grid gap-3 border-t border-divider pt-3 sm:grid-cols-2 lg:grid-cols-5">
                      <Switch isSelected={form.preheatEnabled} isDisabled={!form.smartSelectionEnabled} onValueChange={preheatEnabled => setForm({ ...form, preheatEnabled })}>备用线路预热</Switch>
                      <Input type="number" label="预热备用数" min={1} max={9} value={form.preheatBackupCount} isDisabled={!form.smartSelectionEnabled || !form.preheatEnabled} onValueChange={preheatBackupCount => setForm({ ...form, preheatBackupCount })} />
                      <Switch isSelected={form.preheatStrictIsolation} isDisabled={!form.smartSelectionEnabled || !form.preheatEnabled} onValueChange={preheatStrictIsolation => setForm({ ...form, preheatStrictIsolation })}>严格预热隔离</Switch>
                      <Switch isSelected={form.postSwitchVerifyEnabled} onValueChange={postSwitchVerifyEnabled => setForm({ ...form, postSwitchVerifyEnabled })}>切换后验证入口</Switch>
                      <Switch isSelected={form.dnsVerifyEnabled} onValueChange={dnsVerifyEnabled => setForm({ ...form, dnsVerifyEnabled })}>DNS 生效确认</Switch>
                    </div>
                    <p className="text-xs leading-5 text-default-500">智能选择会同时参考所有入口的健康、质量、丢包、均值延迟、P95、抖动、失败次数和拓扑关系。备用预热会优先保留不同节点、不同云厂商/ASN、不同 IPv4 /16 或 IPv6 /48 且最近质量正常的备用线路；严格隔离开启时，不会为了凑满数量而预热同类线路。</p>
                  </div>
                </div>
              )}
            </section>

            <div className="rounded-md bg-warning-50 px-3 py-3 text-xs leading-5 text-warning-700 dark:bg-warning-500/10 dark:text-warning-300">面板会自动创建或更新仅 DNS 记录，不开启 Cloudflare 代理。请确保面板服务器能访问各公网入口端口。检测和 DNS 更新可在数秒内完成，但运营商及客户端 DNS 缓存仍可能延迟实际生效；已经建立的连接需要重新连接。</div>
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setFormOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submit}>保存并同步 DNS</Button></ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={historyOpen} onOpenChange={setHistoryOpen} size="2xl" scrollBehavior="inside">
        <ModalContent><ModalHeader>{historyName} · 切换历史</ModalHeader><ModalBody>{events.length === 0 ? <div className="py-12 text-center text-sm text-default-500">暂无切换记录</div> : <div className="divide-y divide-divider">{events.map(event => <div key={event.id} className="flex gap-3 py-3"><div className={`mt-1 h-2.5 w-2.5 flex-none rounded-full ${event.status === 'success' ? 'bg-success' : 'bg-danger'}`} /><div className="min-w-0 flex-1"><div className="flex flex-wrap items-center justify-between gap-2"><p className="text-sm font-medium">{event.reason}</p><span className="text-xs text-default-500">{timeText(event.createdTime)}</span></div><p className="mt-1 flex flex-wrap items-center gap-1 text-xs text-default-500"><span>{event.fromForwardName || event.fromNodeName || '初始'}</span><ArrowRight size={12} /><span>{event.toForwardName || event.toNodeName || '-'}</span></p>{eventEndpointText(event) && <p className="mt-1 text-xs text-default-500">{eventEndpointText(event)}</p>}<p className="mt-1 text-xs text-default-500">{event.detail}</p></div></div>)}</div>}</ModalBody><ModalFooter><Button variant="flat" onPress={() => setHistoryOpen(false)}>关闭</Button></ModalFooter></ModalContent>
      </Modal>
    </div>
  );
}
