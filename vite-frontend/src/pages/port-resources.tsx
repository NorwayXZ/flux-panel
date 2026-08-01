import { useEffect, useState } from 'react';
import { Button } from '@heroui/button';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Chip } from '@heroui/chip';
import { Boxes, CircleGauge, Plus, Search, Share2, ShieldCheck, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';
import AccessResourceTabs from '@/components/access-resource-tabs';

import {
  createPublishingPortPool,
  deletePublishingPortPool,
  getNodeList,
  getPublishingPortGrants,
  getPublishingPortPools,
  getPortLedger,
  diagnosePort,
  type PortLedgerEntry,
  type PortLedgerResult,
  type PublishingPortGrant,
  type PublishingPortPool,
} from '@/api';

interface NodeOption { id: number; name: string; ip?: string; serverIp?: string; status: number }

export default function PortResourcesPage() {
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [pools, setPools] = useState<PublishingPortPool[]>([]);
  const [grants, setGrants] = useState<PublishingPortGrant[]>([]);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [ledger, setLedger] = useState<PortLedgerResult>({ entries: [], summary: {}, total: 0 });
  const [ledgerLoading, setLedgerLoading] = useState(false);
  const [diagnosis, setDiagnosis] = useState<PortLedgerResult | null>(null);
  const [diagnosisOpen, setDiagnosisOpen] = useState(false);
  const [filters, setFilters] = useState({ nodeId: '', port: '', type: 'all', keyword: '' });
  const [form, setForm] = useState({
    name: '', nodeId: '', publicHost: '', bindIp: '', startPort: '20000', endPort: '20999', controlPort: '21000', cooldownSeconds: '60',
  });

  const loadData = async () => {
    setLoading(true);
    const [poolRes, grantRes, nodeRes] = await Promise.all([getPublishingPortPools(), getPublishingPortGrants(), getNodeList()]);
    if (poolRes.code === 0) setPools(poolRes.data || []); else toast.error(poolRes.msg || '加载端口池失败');
    if (grantRes.code === 0) setGrants(grantRes.data || []); else toast.error(grantRes.msg || '加载端口授权失败');
    if (nodeRes.code === 0) setNodes(nodeRes.data || []);
    setLoading(false);
  };
  const loadLedger = async () => {
    setLedgerLoading(true);
    const response = await getPortLedger({
      nodeId: filters.nodeId ? Number(filters.nodeId) : undefined,
      port: filters.port ? Number(filters.port) : undefined,
      type: filters.type === 'all' ? undefined : filters.type,
      keyword: filters.keyword.trim() || undefined,
    });
    setLedgerLoading(false);
    if (response.code === 0) setLedger(response.data || { entries: [], summary: {}, total: 0 });
    else toast.error(response.msg || '加载全局端口账本失败');
  };

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadData();
      void loadLedger();
    }, 0);

    return () => window.clearTimeout(timer);
  }, []);

  const runDiagnosis = async () => {
    if (!filters.nodeId || !filters.port) return toast.error('请先选择节点并填写端口');
    const port = Number(filters.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return toast.error('端口范围应为 1-65535');
    const response = await diagnosePort(Number(filters.nodeId), port);
    if (response.code !== 0) return toast.error(response.msg || '端口检查失败');
    setDiagnosis(response.data);
    setDiagnosisOpen(true);
  };

  const selectNode = (id: string) => {
    const node = nodes.find(item => String(item.id) === id);
    setForm({ ...form, nodeId: id, publicHost: node?.serverIp || node?.ip || form.publicHost });
  };

  const submit = async () => {
    if (!form.name.trim() || !form.nodeId || !form.publicHost.trim()) return toast.error('请填写完整的端口池配置');
    setSubmitting(true);
    const res = await createPublishingPortPool({
      name: form.name.trim(), nodeId: Number(form.nodeId), publicHost: form.publicHost.trim(), bindIp: form.bindIp.trim(),
      startPort: Number(form.startPort), endPort: Number(form.endPort), controlPort: Number(form.controlPort),
      cooldownSeconds: Number(form.cooldownSeconds),
    });
    setSubmitting(false);
    if (res.code !== 0) return toast.error(res.msg || '创建端口池失败');
    toast.success('端口池已创建');
    setModalOpen(false);
    loadData();
  };

  const remove = async (id: number) => {
    if (!window.confirm('确认删除该端口池吗？')) return;
    const res = await deletePublishingPortPool(id);
    if (res.code !== 0) return toast.error(res.msg || '删除失败');
    toast.success('端口池已删除');
    loadData();
  };

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-5 p-4 md:p-6">
      <AccessResourceTabs />
      <header className="flex items-end justify-between border-b border-divider pb-5">
        <div><p className="text-sm text-default-500">资源中心</p><h1 className="mt-1 text-2xl font-semibold">端口资源</h1></div>
        <Button color="primary" startContent={<Plus size={18} />} onPress={() => setModalOpen(true)}>新建端口池</Button>
      </header>
      {loading ? <div className="flex min-h-64 items-center justify-center"><Spinner /></div> : pools.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><Boxes size={30} /><span>暂无端口池</span></div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-divider">
          <div className="hidden grid-cols-[1.2fr_1fr_1fr_1.4fr_1fr_auto] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500 lg:grid">
            <span>端口池</span><span>公网节点</span><span>范围</span><span>端口状态</span><span>释放规则</span><span>操作</span>
          </div>
          {pools.map(pool => (
            <div key={pool.id} className="grid gap-3 border-t border-divider px-4 py-4 first:border-t-0 lg:grid-cols-[1.2fr_1fr_1fr_1.4fr_1fr_auto] lg:items-center">
              <div><div className="font-medium">{pool.name}</div><div className="text-xs text-default-500">{pool.publicHost}</div></div>
              <div className="text-sm">{pool.nodeName}</div>
              <div className="font-mono text-sm">{pool.startPort}-{pool.endPort}</div>
              <div>
                <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs"><span>管理员占用 <strong className="text-foreground">{pool.usedPorts}</strong></span><span>用户保留 <strong className="text-secondary">{pool.sharedPorts || 0}</strong></span><span>管理员可用 <strong className="text-success">{pool.availablePorts}</strong></span></div>
                <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-default-200"><div className="h-full bg-primary" style={{ width: `${pool.totalPorts ? Math.min(100, ((pool.usedPorts || 0) + (pool.sharedPorts || 0)) / pool.totalPorts * 100) : 0}%` }} /></div>
              </div>
              <div className="text-sm"><div>停止后冷却 {pool.cooldownSeconds} 秒</div><div className="text-xs text-default-500">服务自行选择定时或永久</div></div>
              <Button isIconOnly size="sm" variant="light" color="danger" aria-label="删除端口池" onPress={() => remove(pool.id)}><Trash2 size={17} /></Button>
            </div>
          ))}
        </div>
      )}

      <section className="space-y-3 border-t border-divider pt-5">
        <div className="flex items-center justify-between gap-3">
          <div><h2 className="flex items-center gap-2 text-base font-semibold"><Share2 size={18} className="text-secondary" />用户端口授权</h2><p className="mt-1 text-xs text-default-500">授权范围立即从管理员可用端口中移除，具体分配在用户管理中编辑。</p></div>
          <span className="text-sm text-default-500">{grants.length} 项</span>
        </div>
        {grants.length === 0 ? <div className="border-y border-divider py-10 text-center text-sm text-default-500">尚未向用户分享端口资源</div> : (
          <div className="overflow-hidden rounded-lg border border-divider">
            <div className="hidden grid-cols-[1fr_1.2fr_1fr_1fr] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500 md:grid"><span>用户</span><span>端口池 / 节点</span><span>授权范围</span><span>使用情况</span></div>
            {grants.map(grant => <div key={grant.id} className="grid gap-2 border-t border-divider px-4 py-4 first:border-t-0 md:grid-cols-[1fr_1.2fr_1fr_1fr] md:items-center">
              <div className="font-medium">{grant.ownerUserName}</div>
              <div><div className="text-sm">{grant.poolName}</div><div className="text-xs text-default-500">{grant.nodeName}</div></div>
              <div className="font-mono text-sm">{grant.startPort}-{grant.endPort}</div>
              <div className="text-sm">已用 {grant.usedPorts} / {grant.totalPorts}<div className="mt-1 text-xs text-default-500">剩余 {grant.availablePorts}</div></div>
            </div>)}
          </div>
        )}
      </section>

      <section className="space-y-4 border-t border-divider pt-5">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="flex items-center gap-2 text-base font-semibold"><CircleGauge size={18} className="text-primary" />全局端口账本</h2>
            <p className="mt-1 text-xs text-default-500">同一服务器地址下的节点共用端口命名空间。账本同时追踪转发入口、隧道跳点、内网映射和用户授权。</p>
          </div>
          <div className="flex flex-wrap gap-2 text-xs">
            <Chip size="sm" variant="flat" color="danger">占用 {ledger.summary.occupied || 0}</Chip>
            <Chip size="sm" variant="flat" color="primary">保留 {ledger.summary.reserved || 0}</Chip>
            <Chip size="sm" variant="flat" color="secondary">授权 {ledger.summary.granted || 0}</Chip>
            <Chip size="sm" variant="flat" color="warning">冷却 {ledger.summary.cooldown || 0}</Chip>
          </div>
        </div>

        <div className="grid gap-3 border-y border-divider py-4 md:grid-cols-2 xl:grid-cols-[1.2fr_.8fr_1fr_1.4fr_auto_auto]">
          <Select label="服务器节点" placeholder="全部节点" selectedKeys={filters.nodeId ? [filters.nodeId] : []}
            onSelectionChange={keys => setFilters({ ...filters, nodeId: String(Array.from(keys)[0] || '') })}>
            {nodes.map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · {node.serverIp || node.ip || '未设置地址'}</SelectItem>)}
          </Select>
          <Input label="端口" type="number" placeholder="例如 20000" value={filters.port} onValueChange={value => setFilters({ ...filters, port: value })} />
          <Select label="记录类型" selectedKeys={[filters.type]} onSelectionChange={keys => setFilters({ ...filters, type: String(Array.from(keys)[0] || 'all') })}>
            <SelectItem key="all">全部类型</SelectItem>
            <SelectItem key="forward_entry">转发入口</SelectItem>
            <SelectItem key="tunnel_hop">隧道跳点</SelectItem>
            <SelectItem key="published_service">内网映射</SelectItem>
            <SelectItem key="home_proxy">家庭代理</SelectItem>
            <SelectItem key="domain_ingress">域名入口</SelectItem>
            <SelectItem key="pool_control">控制端口</SelectItem>
            <SelectItem key="pool_range">端口池范围</SelectItem>
            <SelectItem key="user_grant">用户授权</SelectItem>
            <SelectItem key="source_ip_entry">来源 IP 分流</SelectItem>
          </Select>
          <Input label="关键词" placeholder="名称、用户、地址" value={filters.keyword} onValueChange={value => setFilters({ ...filters, keyword: value })} />
          <Button className="h-14" color="primary" startContent={<Search size={17} />} isLoading={ledgerLoading} onPress={loadLedger}>查询</Button>
          <Button className="h-14" variant="flat" startContent={<ShieldCheck size={17} />} onPress={runDiagnosis}>检查端口</Button>
        </div>

        {ledgerLoading ? <div className="flex min-h-40 items-center justify-center"><Spinner /></div> : ledger.entries.length === 0 ? (
          <div className="border-b border-divider py-12 text-center text-sm text-default-500">当前条件下没有端口占用记录</div>
        ) : (
          <div className="overflow-hidden rounded-lg border border-divider">
            <div className="hidden grid-cols-[1.1fr_.8fr_1fr_1fr_1.2fr_1.5fr] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500 xl:grid">
              <span>服务器</span><span>端口</span><span>用途</span><span>归属</span><span>资源</span><span>说明</span>
            </div>
            {ledger.entries.map(entry => <LedgerRow key={entry.key} entry={entry} />)}
          </div>
        )}
      </section>

      <Modal isOpen={modalOpen} onOpenChange={setModalOpen} size="2xl" scrollBehavior="inside">
        <ModalContent>
          <ModalHeader>新建端口池</ModalHeader>
          <ModalBody className="grid gap-4 sm:grid-cols-2">
            <Input label="端口池名称" value={form.name} onValueChange={value => setForm({ ...form, name: value })} />
            <Select label="公网节点" selectedKeys={form.nodeId ? [form.nodeId] : []} onSelectionChange={keys => selectNode(String(Array.from(keys)[0] || ''))}>
              {nodes.map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · {node.status === 1 ? '在线' : '离线'}</SelectItem>)}
            </Select>
            <Input className="sm:col-span-2" label="公网连接地址" value={form.publicHost} onValueChange={value => setForm({ ...form, publicHost: value })} />
            <Input label="起始端口" type="number" value={form.startPort} onValueChange={value => setForm({ ...form, startPort: value })} />
            <Input label="结束端口" type="number" value={form.endPort} onValueChange={value => setForm({ ...form, endPort: value })} />
            <Input label="反向连接控制端口" type="number" value={form.controlPort} onValueChange={value => setForm({ ...form, controlPort: value })} />
            <Input label="监听 IP（可选）" placeholder="留空监听全部地址" value={form.bindIp} onValueChange={value => setForm({ ...form, bindIp: value })} />
            <Input className="sm:col-span-2" label="释放冷却（秒）" type="number" value={form.cooldownSeconds} onValueChange={value => setForm({ ...form, cooldownSeconds: value })} />
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setModalOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submit}>创建</Button></ModalFooter>
        </ModalContent>
      </Modal>
      <Modal isOpen={diagnosisOpen} onOpenChange={setDiagnosisOpen} size="2xl">
        <ModalContent>
          <ModalHeader>端口冲突诊断</ModalHeader>
          <ModalBody>
            <div className="flex items-center justify-between border-y border-divider py-4">
              <div><div className="text-sm text-default-500">检查端口</div><div className="mt-1 font-mono text-xl">{diagnosis?.port}</div></div>
              <Chip color={diagnosis?.occupied ? 'danger' : 'success'} variant="flat">{diagnosis?.occupied ? '面板账本已占用' : '面板账本可用'}</Chip>
            </div>
            {diagnosis?.entries?.length ? <div className="overflow-hidden rounded-lg border border-divider">
              {diagnosis.entries.map(entry => <LedgerRow key={entry.key} entry={entry} compact />)}
            </div> : <div className="py-8 text-center text-sm text-default-500">没有发现面板管理范围内的冲突</div>}
            <p className="text-xs text-default-500">创建资源时，新版 Agent 还会检查服务器操作系统中的真实监听端口；这里展示的是面板全局账本。</p>
          </ModalBody>
          <ModalFooter><Button color="primary" onPress={() => setDiagnosisOpen(false)}>完成</Button></ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

const typeLabels: Record<string, string> = {
  forward_entry: '转发入口', tunnel_hop: '隧道跳点', published_service: '内网映射', home_proxy: '家庭代理',
  pool_control: '控制端口', pool_range: '端口池范围', user_grant: '用户授权', domain_ingress: '域名入口', source_ip_entry: '来源 IP 分流',
};

const statusColors: Record<string, 'danger' | 'primary' | 'secondary' | 'warning' | 'default'> = {
  occupied: 'danger', reserved: 'primary', granted: 'secondary', cooldown: 'warning',
};

function LedgerRow({ entry, compact = false }: { entry: PortLedgerEntry; compact?: boolean }) {
  const port = entry.portStart === entry.portEnd ? String(entry.portStart) : `${entry.portStart}-${entry.portEnd}`;
  return <div className={`grid gap-3 border-t border-divider px-4 py-3 first:border-t-0 ${compact ? 'sm:grid-cols-[1fr_.8fr_1fr]' : 'xl:grid-cols-[1.1fr_.8fr_1fr_1fr_1.2fr_1.5fr] xl:items-center'}`}>
    <div><div className="text-sm font-medium">{entry.nodeName}</div><div className="truncate text-xs text-default-500">{entry.serverAddress}</div></div>
    <div><div className="font-mono text-sm">{port}</div><div className="text-xs uppercase text-default-500">{entry.protocol.replace('_', '+')}</div></div>
    <div><Chip size="sm" variant="flat" color={statusColors[entry.status] || 'default'}>{typeLabels[entry.type] || entry.type}</Chip></div>
    {!compact && <div><div className="text-sm">{entry.ownerUserName}</div><div className="text-xs text-default-500">用户 ID {entry.ownerUserId || '-'}</div></div>}
    {!compact && <div><div className="truncate text-sm">{entry.resourceName}</div><div className="text-xs text-default-500">ID {entry.resourceId}</div></div>}
    <div className="min-w-0 text-sm text-default-600">{entry.detail}</div>
  </div>;
}
