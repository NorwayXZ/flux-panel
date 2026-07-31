import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Select, SelectItem } from '@heroui/select';
import { CircleCheck, CircleDashed, RefreshCw, RotateCcw, SearchCheck, TriangleAlert, XCircle } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  getSystemSelfCheckOverview,
  resetAgentIdentityBaseline,
  runSystemSelfCheck,
  type SelfCheckStatus,
  type SystemSelfCheckFinding,
  type SystemSelfCheckOverview,
} from '@/api';

const statusMeta: Record<SelfCheckStatus, { label: string; color: 'success' | 'warning' | 'danger' | 'default' }> = {
  healthy: { label: '正常', color: 'success' },
  warning: { label: '注意', color: 'warning' },
  failed: { label: '故障', color: 'danger' },
  skipped: { label: '跳过', color: 'default' },
};

const categoryLabels: Record<string, string> = {
  agent: 'Agent', network: '网络能力', dns: 'DNS', port: '端口', dependency: '依赖链', certificate: '证书', system: '系统',
};

export default function SystemSelfCheckPage() {
  const [data, setData] = useState<SystemSelfCheckOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [scopeNodeId, setScopeNodeId] = useState('all');
  const [statusFilter, setStatusFilter] = useState('all');
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [resettingNode, setResettingNode] = useState<number | null>(null);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const response = await getSystemSelfCheckOverview();
    if (!quiet) setLoading(false);
    if (response.code !== 0) return toast.error(response.msg || '读取自检结果失败');
    setData(response.data);
    setRunning(response.data?.run?.status === 'running');
  }, []);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => void load(true), 2500);
    return () => window.clearInterval(timer);
  }, [running, load]);

  const start = async () => {
    setRunning(true);
    const nodeId = scopeNodeId === 'all' ? undefined : Number(scopeNodeId);
    const response = await runSystemSelfCheck(nodeId);
    if (response.code !== 0) {
      setRunning(false);
      return toast.error(response.msg || '无法启动系统自检');
    }
    setData(response.data);
    toast.success(nodeId ? '已开始检查指定节点' : '已开始检查全部资源');
  };

  const resetBaseline = async (finding: SystemSelfCheckFinding) => {
    if (!finding.resourceId) return;
    setResettingNode(finding.resourceId);
    const response = await resetAgentIdentityBaseline(finding.resourceId);
    setResettingNode(null);
    if (response.code !== 0) return toast.error(response.msg || '重置失败');
    toast.success(response.data || '身份基线已重置');
    await load(true);
  };

  const findings = useMemo(() => (data?.findings || []).filter(item =>
    (statusFilter === 'all' || item.status === statusFilter)
    && (categoryFilter === 'all' || item.category === categoryFilter)
  ), [data?.findings, statusFilter, categoryFilter]);

  const categories = useMemo(() => Array.from(new Set((data?.findings || []).map(item => item.category))), [data?.findings]);
  const scopeOptions = useMemo(() => [
    { key: 'all', label: '全部节点与业务链路' },
    ...(data?.nodes || []).map(node => ({ key: String(node.id), label: `${node.name} · ${node.status === 1 ? '在线' : '离线'} · Agent ${node.version || '未知'}` })),
  ], [data?.nodes]);
  const categoryOptions = useMemo(() => [
    { key: 'all', label: '全部类别' },
    ...categories.map(category => ({ key: category, label: categoryLabels[category] || category })),
  ], [categories]);
  const run = data?.run;

  return <div className="mx-auto w-full max-w-[1500px] space-y-5 p-4 md:p-6">
    <header className="flex flex-col gap-4 border-b border-divider pb-5 lg:flex-row lg:items-end lg:justify-between">
      <div><p className="text-sm text-default-500">系统管理</p><h1 className="mt-1 text-2xl font-semibold">全系统自检中心</h1></div>
      <div className="grid w-full gap-3 sm:grid-cols-[260px_auto] lg:w-auto">
        <Select aria-label="自检范围" selectedKeys={[scopeNodeId]} onSelectionChange={keys => setScopeNodeId(String(Array.from(keys)[0] || 'all'))} isDisabled={running}>
          {scopeOptions.map(option => <SelectItem key={option.key}>{option.label}</SelectItem>)}
        </Select>
        <Button color="primary" isLoading={running || loading} startContent={!running && <SearchCheck size={17} />} onPress={() => void start()}>
          {running ? '自检运行中' : '开始自检'}
        </Button>
      </div>
    </header>

    <section className="grid grid-cols-2 border-y border-divider sm:grid-cols-5">
      {[
        ['检查项', run?.totalChecks || 0, 'text-foreground'],
        ['正常', run?.healthyCount || 0, 'text-success'],
        ['注意', run?.warningCount || 0, 'text-warning'],
        ['故障', run?.failedCount || 0, 'text-danger'],
        ['跳过', run?.skippedCount || 0, 'text-default-500'],
      ].map(([label, value, color], index) => <div key={String(label)} className={`px-4 py-4 ${index > 0 ? 'border-l border-divider' : ''}`}>
        <p className="text-xs text-default-500">{label}</p><p className={`mt-1 text-2xl font-semibold ${color}`}>{value}</p>
      </div>)}
    </section>

    {run && <section className="flex flex-col gap-3 border-b border-divider pb-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-center gap-3">
        {run.status === 'running' ? <RefreshCw size={18} className="animate-spin text-primary" /> : run.failedCount > 0 ? <XCircle size={18} className="text-danger" /> : <CircleCheck size={18} className="text-success" />}
        <div><p className="text-sm font-medium">{run.message || '等待自检'}</p><p className="mt-1 text-xs text-default-500">{new Date(run.startedAt).toLocaleString('zh-CN')} · {run.scopeNodeId ? '指定节点' : '全部资源'}</p></div>
      </div>
      <p className="text-xs text-default-500">完整 Agent 自检最低版本 {data?.minimumAgentVersion}</p>
    </section>}

    <section className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div><h2 className="text-lg font-semibold">检查结果</h2><p className="mt-1 text-sm text-default-500">按故障位置提供证据、影响和处理方式</p></div>
      <div className="grid grid-cols-2 gap-2 sm:w-[390px]">
        <Select aria-label="结果状态" size="sm" selectedKeys={[statusFilter]} onSelectionChange={keys => setStatusFilter(String(Array.from(keys)[0] || 'all'))}>
          <SelectItem key="all">全部状态</SelectItem><SelectItem key="failed">仅故障</SelectItem><SelectItem key="warning">仅注意</SelectItem><SelectItem key="healthy">仅正常</SelectItem><SelectItem key="skipped">仅跳过</SelectItem>
        </Select>
        <Select aria-label="检查类别" size="sm" selectedKeys={[categoryFilter]} onSelectionChange={keys => setCategoryFilter(String(Array.from(keys)[0] || 'all'))}>
          {categoryOptions.map(option => <SelectItem key={option.key}>{option.label}</SelectItem>)}
        </Select>
      </div>
    </section>

    <section className="border-t border-divider">
      {findings.map(finding => <article key={finding.id} className="grid gap-4 border-b border-divider py-4 lg:grid-cols-[190px_minmax(0,1fr)_minmax(240px,0.75fr)]">
        <div className="flex items-start gap-3">
          <StatusIcon status={finding.status} />
          <div className="min-w-0"><Chip size="sm" color={statusMeta[finding.status].color} variant="flat">{statusMeta[finding.status].label}</Chip><p className="mt-2 truncate text-sm font-medium" title={finding.resourceName}>{finding.resourceName || categoryLabels[finding.category] || finding.category}</p><p className="mt-1 text-xs text-default-500">{categoryLabels[finding.category] || finding.category}</p></div>
        </div>
        <div className="min-w-0"><p className="text-xs text-default-500">{finding.faultSegment}</p><h3 className="mt-1 font-medium">{finding.summary}</h3>{finding.evidence && <p className="mt-2 break-words text-sm leading-6 text-default-600">证据：{finding.evidence}</p>}{finding.impact && finding.impact !== '无' && <p className="mt-1 text-sm leading-6 text-default-500">影响：{finding.impact}</p>}</div>
        <div className="border-l-0 border-divider lg:border-l lg:pl-4"><p className="text-xs text-default-500">处理建议</p><p className="mt-1 text-sm leading-6">{finding.remediation || '无需操作'}</p>{finding.category === 'agent' && finding.resourceType === 'node' && finding.status === 'warning' && finding.summary.includes('不同机器') && finding.resourceId && <Button className="mt-3" size="sm" variant="flat" startContent={<RotateCcw size={15} />} isLoading={resettingNode === finding.resourceId} onPress={() => void resetBaseline(finding)}>重置身份基线</Button>}</div>
      </article>)}
      {!loading && findings.length === 0 && <div className="py-16 text-center text-default-400">当前筛选条件没有结果</div>}
    </section>
  </div>;
}

function StatusIcon({ status }: { status: SelfCheckStatus }) {
  if (status === 'healthy') return <CircleCheck size={20} className="mt-0.5 shrink-0 text-success" />;
  if (status === 'warning') return <TriangleAlert size={20} className="mt-0.5 shrink-0 text-warning" />;
  if (status === 'failed') return <XCircle size={20} className="mt-0.5 shrink-0 text-danger" />;
  return <CircleDashed size={20} className="mt-0.5 shrink-0 text-default-400" />;
}
