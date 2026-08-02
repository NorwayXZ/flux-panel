import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Card, CardBody } from '@heroui/card';
import { Chip } from '@heroui/chip';
import { Input, Textarea } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem, SelectSection } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { CheckCircle2, CircleAlert, Info, Pencil, Plus, RadioTower, RefreshCw, Trash2, X } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  checkSourceIpEntry,
  deleteSourceIpEntry,
  getSourceIpEntryOverview,
  refreshSourceIpCarriers,
  saveSourceIpEntry,
  type SourceIpBackendForward,
  type SourceIpCarrierDatabase,
  type SourceIpEntryGroup,
  type SourceIpEntryOverview,
} from '@/api';
import { groupForwardOptionsByPort } from '@/utils/forward-option-groups';

type Carrier = 'default' | 'telecom' | 'unicom' | 'mobile' | 'custom';

interface FormRoute {
  carrier: Carrier;
  backendForwardId: string;
  cidrs: string;
  enabled: boolean;
}

interface FormState {
  id?: number;
  name: string;
  ingressNodeId: string;
  listenHost: string;
  listenPort: string;
  enabled: boolean;
  routes: FormRoute[];
}

const carrierLabels: Record<Carrier, string> = {
  default: '默认回退',
  telecom: '中国电信',
  unicom: '中国联通',
  mobile: '中国移动',
  custom: '自定义 CIDR',
};

const emptyForm = (): FormState => ({
  name: '', ingressNodeId: '', listenHost: '', listenPort: '', enabled: true,
  routes: [{ carrier: 'default', backendForwardId: '', cidrs: '', enabled: true }],
});

const truthy = (value: boolean | number | undefined) => value === true || value === 1;
const timeText = (value?: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未同步';

function statusMeta(state: SourceIpEntryGroup['state']) {
  switch (state) {
    case 'active': return { label: '运行正常', color: 'success' as const };
    case 'disabled': return { label: '已停用', color: 'default' as const };
    case 'error': return { label: '同步失败', color: 'danger' as const };
    default: return { label: '配置中', color: 'warning' as const };
  }
}

function backendText(forward?: SourceIpBackendForward) {
  if (!forward) return '未选择后端入口';
  return `${forward.nodeName || `节点 ${forward.inNodeId}`} · ${forward.entryHost || '-'}:${forward.inPort}`;
}

function carrierText(carrier: string) {
  return carrierLabels[carrier as Carrier] || carrier;
}

export default function SourceIpEntryPage() {
  const [data, setData] = useState<SourceIpEntryOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [formOpen, setFormOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [carrierRefreshing, setCarrierRefreshing] = useState(false);
  const [checkingId, setCheckingId] = useState<number>();
  const [form, setForm] = useState<FormState>(emptyForm);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    const response = await getSourceIpEntryOverview();
    if (response.code === 0) setData(response.data);
    else if (!quiet) toast.error(response.msg || '加载来源 IP 分流失败');
    if (!quiet) setLoading(false);
  }, []);

  useEffect(() => { void load(); }, [load]);

  const backendMap = useMemo(() => new Map((data?.backendForwards || []).map(item => [String(item.id), item])), [data]);
  const backendGroups = useMemo(() => groupForwardOptionsByPort(data?.backendForwards || []), [data?.backendForwards]);
  const carrierMap = useMemo(() => new Map((data?.carriers || []).map(item => [item.carrier, item])), [data]);

  const openCreate = () => {
    setForm(emptyForm());
    setFormOpen(true);
  };

  const openEdit = (group: SourceIpEntryGroup) => {
    setForm({
      id: group.id,
      name: group.name,
      ingressNodeId: String(group.ingressNodeId),
      listenHost: group.listenHost || '',
      listenPort: String(group.listenPort),
      enabled: truthy(group.enabled),
      routes: group.routes.map(route => ({
        carrier: route.carrier,
        backendForwardId: String(route.backendForwardId),
        cidrs: route.carrier === 'custom' ? (route.cidrs || '') : '',
        enabled: truthy(route.enabled),
      })),
    });
    setFormOpen(true);
  };

  const updateRoute = (index: number, changes: Partial<FormRoute>) => {
    setForm(current => ({ ...current, routes: current.routes.map((route, itemIndex) => itemIndex === index ? { ...route, ...changes } : route) }));
  };

  const addRoute = () => {
    if (form.routes.length >= 5) return toast.error('一组最多配置 5 条线路');
    setForm(current => ({ ...current, routes: [...current.routes, { carrier: 'custom', backendForwardId: '', cidrs: '', enabled: true }] }));
  };

  const removeRoute = (index: number) => {
    if (form.routes[index]?.carrier === 'default') return toast.error('默认回退线路不能删除');
    setForm(current => ({ ...current, routes: current.routes.filter((_, itemIndex) => itemIndex !== index) }));
  };

  const submit = async () => {
    if (!form.name.trim()) return toast.error('请输入分流名称');
    if (!form.ingressNodeId) return toast.error('请选择统一入口节点');
    const port = Number(form.listenPort);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return toast.error('监听端口必须在 1-65535 之间');
    if (form.routes.length === 0 || !form.routes.some(route => route.carrier === 'default')) return toast.error('必须配置一条默认回退线路');
    const carriers = new Set<string>();
    for (const route of form.routes) {
      if (carriers.has(route.carrier)) return toast.error(`线路类型不能重复：${carrierText(route.carrier)}`);
      carriers.add(route.carrier);
      if (!route.backendForwardId) return toast.error(`${carrierText(route.carrier)} 未选择后端入口转发`);
      if (route.carrier === 'custom' && !route.cidrs.trim()) return toast.error('自定义线路必须填写 CIDR');
    }
    setSubmitting(true);
    const response = await saveSourceIpEntry({
      id: form.id,
      name: form.name.trim(),
      ingressNodeId: Number(form.ingressNodeId),
      listenHost: form.listenHost.trim(),
      listenPort: port,
      enabled: form.enabled,
      routes: form.routes.map(route => ({
        carrier: route.carrier,
        backendForwardId: Number(route.backendForwardId),
        cidrs: route.carrier === 'custom' ? route.cidrs : '',
        enabled: route.enabled,
      })),
    });
    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || '保存来源 IP 分流失败');
    toast.success('来源 IP 分流已同步到入口 Agent');
    setFormOpen(false);
    void load(true);
  };

  const remove = async (group: SourceIpEntryGroup) => {
    if (!window.confirm(`确认删除“${group.name}”吗？这会删除统一入口服务，不会删除后端转发。`)) return;
    const response = await deleteSourceIpEntry(group.id);
    if (response.code !== 0) return toast.error(response.msg || '删除失败');
    toast.success('来源 IP 分流已删除');
    void load(true);
  };

  const check = async (id: number) => {
    setCheckingId(id);
    const response = await checkSourceIpEntry(id);
    setCheckingId(undefined);
    if (response.code !== 0) return toast.error(response.msg || '入口同步检查失败');
    toast.success('入口 Agent 配置已重新应用');
    void load(true);
  };

  const refreshCarriers = async () => {
    setCarrierRefreshing(true);
    const response = await refreshSourceIpCarriers();
    setCarrierRefreshing(false);
    if (response.code !== 0) return toast.error(response.msg || '运营商 IP 库刷新失败');
    toast.success('运营商 IP 库已刷新');
    void load(true);
  };

  if (loading) return <div className="flex min-h-[50vh] items-center justify-center"><Spinner label="加载来源 IP 分流" /></div>;

  const groups = data?.groups || [];
  const nodes = data?.ingressNodes || [];

  return (
    <div className="mx-auto w-full max-w-[1600px] space-y-6 p-4 sm:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">三网优化的直连替代方案</p>
          <h1 className="mt-1 text-2xl font-semibold">来源 IP 分流</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-default-500">一个公网入口端口按客户端真实来源 IP 选择电信、联通、移动或自定义线路。它不依赖客户端 DNS，也不会终止 VLESS、Reality、Trojan 等上层协议。</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="flat" startContent={<RefreshCw size={17} />} isLoading={carrierRefreshing} onPress={refreshCarriers}>刷新运营商 IP 库</Button>
          <Button color="primary" startContent={<Plus size={18} />} onPress={openCreate}>新建来源 IP 分流</Button>
        </div>
      </header>

      <section className="grid gap-3 border-y border-divider py-4 md:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <div className="flex gap-3">
          <Info className="mt-0.5 h-5 w-5 flex-none text-primary" />
          <div className="text-sm leading-6 text-default-600">
            <p className="font-medium text-foreground">工作方式</p>
            <p>客户端先连接统一入口；入口 Agent 在 TCP 接受连接时读取真实源 IP，命中最长 CIDR 后把原始字节转发到对应后端入口。没有匹配或后端异常时回到 default 线路。</p>
          </div>
        </div>
        <div className="border-l border-divider pl-4 text-sm leading-6 text-default-600">
          <p className="font-medium text-foreground">使用条件</p>
          <p>统一入口 Agent ≥ {data?.minimumAgentVersion || '2.42.3'}；入口不能经过会隐藏源 IP 的代理/CDN。只支持 TCP，UDP 协议继续使用现有入口。</p>
        </div>
      </section>

      <section className="grid grid-cols-2 border-y border-divider sm:grid-cols-4" aria-label="来源 IP 分流概况">
        {[
          ['分流组', data?.summary.total || 0],
          ['运行中', data?.summary.enabled || 0],
          ['同步正常', data?.summary.healthy || 0],
          ['需要处理', data?.summary.errors || 0],
        ].map(([label, value], index) => (
          <div key={String(label)} className={`flex min-h-20 items-center justify-between px-4 py-3 sm:px-6 ${index < 3 ? 'border-r border-divider' : ''}`}>
            <div><p className="text-xs text-default-500">{label}</p><p className="mt-1 text-2xl font-semibold">{value}</p></div>
            {index === 2 ? <CheckCircle2 className="h-5 w-5 text-success" /> : index === 3 ? <CircleAlert className="h-5 w-5 text-warning" /> : <RadioTower className="h-5 w-5 text-primary" />}
          </div>
        ))}
      </section>

      <section className="border-y border-divider py-4">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div><h2 className="text-sm font-semibold">运营商 IP 库</h2><p className="mt-1 text-xs text-default-500">默认每日自动刷新；仅在运营商线路 CIDR 为空时使用。自定义线路不依赖此库。</p></div>
          <span className="text-xs text-default-500">来源：china-operator-ip</span>
        </div>
        <div className="mt-3 grid gap-2 md:grid-cols-3">
          {(['telecom', 'unicom', 'mobile'] as const).map(carrier => {
            const item = carrierMap.get(carrier) as SourceIpCarrierDatabase | undefined;
            return <div key={carrier} className="flex items-center justify-between border border-divider px-3 py-2 text-sm"><div><p className="font-medium">{carrierLabels[carrier]}</p><p className="mt-1 text-xs text-default-500">{item?.state === 'ready' ? `${item.cidrCount} 条 CIDR · IPv4 ${item.ipv4Count} · IPv6 ${item.ipv6Count}` : item?.lastError || '尚未同步'}</p></div><Chip size="sm" variant="flat" color={item?.state === 'ready' ? 'success' : item?.state === 'error' ? 'danger' : 'default'}>{item?.state === 'ready' ? timeText(item.updatedTime) : '待同步'}</Chip></div>;
          })}
        </div>
      </section>

      {groups.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-center text-default-500"><RadioTower className="h-9 w-9" /><p>暂无来源 IP 分流</p><Button size="sm" variant="flat" startContent={<Plus size={16} />} onPress={openCreate}>创建第一组</Button></div>
      ) : (
        <section className="grid gap-4 xl:grid-cols-2">
          {groups.map(group => {
            const meta = statusMeta(group.state);
            return <Card key={group.id} radius="sm" shadow="none" className="border border-divider bg-content1"><CardBody className="gap-4 p-4 sm:p-5">
              <div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h2 className="truncate text-base font-semibold">{group.name}</h2><Chip size="sm" variant="flat" color={meta.color}>{meta.label}</Chip>{truthy(group.enabled) && <Chip size="sm" variant="flat" color="primary">TCP 入口</Chip>}</div><p className="mt-1 truncate text-sm text-default-500">{group.ingressNodeName || `节点 ${group.ingressNodeId}`} · {group.listenHost || '全部地址'}:{group.listenPort}</p></div><div className="flex items-center gap-1"><Button isIconOnly size="sm" variant="light" title="重新同步" aria-label="重新同步" isLoading={checkingId === group.id} onPress={() => check(group.id)}><RefreshCw size={17} /></Button><Button isIconOnly size="sm" variant="light" title="编辑" aria-label="编辑" onPress={() => openEdit(group)}><Pencil size={17} /></Button><Button isIconOnly size="sm" color="danger" variant="light" title="删除" aria-label="删除" onPress={() => remove(group)}><Trash2 size={17} /></Button></div></div>
              <div className="grid gap-2 border-y border-divider py-3 text-sm sm:grid-cols-2"><div><p className="text-xs text-default-500">Agent</p><p className="mt-1 font-medium">{group.agentVersion || '未知版本'}</p></div><div><p className="text-xs text-default-500">最近同步</p><p className="mt-1 font-medium">{timeText(group.lastSyncedAt)}</p></div></div>
              <div className="space-y-2">{group.routes.map(route => <div key={route.id || route.carrier} className={`border-l-2 px-3 py-2 ${route.carrier === 'default' ? 'border-primary bg-primary-50/40 dark:bg-primary-500/5' : 'border-divider'}`}><div className="flex flex-wrap items-center justify-between gap-2"><span className="text-sm font-medium">{carrierText(route.carrier)}</span><span className="text-xs text-default-500">{route.carrier === 'default' ? '未命中时回退' : `${route.cidrCount || 0} 条 CIDR`}</span></div><p className="mt-1 truncate text-xs text-default-500">{backendText(backendMap.get(String(route.backendForwardId)))}</p></div>)}</div>
              {group.lastError && <p className="rounded-md bg-danger-50 px-3 py-2 text-xs leading-5 text-danger dark:bg-danger-500/10">{group.lastError}</p>}
            </CardBody></Card>;
          })}
        </section>
      )}

      <Modal isOpen={formOpen} onOpenChange={setFormOpen} size="4xl" scrollBehavior="inside">
        <ModalContent><ModalHeader>{form.id ? '编辑来源 IP 分流' : '新建来源 IP 分流'}</ModalHeader><ModalBody className="gap-5">
          <section className="grid gap-3 sm:grid-cols-2"><Input isRequired label="分流名称" placeholder="例如：国内三网入口" value={form.name} onValueChange={name => setForm({ ...form, name })} /><Select isRequired label="统一入口节点" description={`Agent 需要 ${data?.minimumAgentVersion || '2.42.3'}+，此节点直接接收客户端连接`} selectedKeys={form.ingressNodeId ? [form.ingressNodeId] : []} onSelectionChange={keys => setForm({ ...form, ingressNodeId: String(Array.from(keys)[0] || '') })}>{nodes.map(node => <SelectItem key={String(node.id)} textValue={`${node.name} ${node.serverIp || node.ip || ''}`} isDisabled={!node.available}>{node.name} · {node.serverIp || node.ip || '无地址'} · {node.available ? `Agent ${node.version}` : node.online ? `需升级到 ${data?.minimumAgentVersion}` : '离线'}</SelectItem>)}</Select><Input label="监听地址（可选）" placeholder="留空监听所有地址；例如 0.0.0.0 或 ::" value={form.listenHost} onValueChange={listenHost => setForm({ ...form, listenHost })} /><Input isRequired type="number" min={1} max={65535} label="监听 TCP 端口" placeholder="例如 443 或 24443" value={form.listenPort} onValueChange={listenPort => setForm({ ...form, listenPort })} /></section>
          <div className="border-l-2 border-warning bg-warning-50 px-3 py-3 text-xs leading-5 text-warning-800 dark:bg-warning-500/10 dark:text-warning-200"><p className="font-medium">端口和协议说明</p><p className="mt-1">统一入口端口必须未被服务器其他程序占用。它只做 TCP 原始转发，后端转发应使用同一种上层协议和对应端口；不要把 UDP-only 线路放进来。入口若经过 Cloudflare 代理、四层代理或其他中间层，源 IP 可能变成中间层地址。</p></div>
          <section className="border-t border-divider pt-4"><div className="mb-3 flex flex-wrap items-end justify-between gap-2"><div><h3 className="text-sm font-semibold">来源线路</h3><p className="mt-1 text-xs text-default-500">按最长 CIDR 匹配；未命中或线路失败时使用 default。每条线路连接一个现有 TCP 转发入口。</p></div><Button size="sm" variant="flat" startContent={<Plus size={16} />} onPress={addRoute} isDisabled={form.routes.length >= 5}>添加线路</Button></div><div className="space-y-3">{form.routes.map((route, index) => <div key={`${index}-${route.carrier}`} className="border border-divider p-3"><div className="grid gap-3 lg:grid-cols-[170px_minmax(0,1fr)_auto] lg:items-end"><Select label="来源类型" selectedKeys={[route.carrier]} onSelectionChange={keys => { const carrier = String(Array.from(keys)[0] || 'custom') as Carrier; updateRoute(index, { carrier, cidrs: carrier === 'custom' ? route.cidrs : '' }); }}><SelectItem key="default">默认回退</SelectItem><SelectItem key="telecom">中国电信</SelectItem><SelectItem key="unicom">中国联通</SelectItem><SelectItem key="mobile">中国移动</SelectItem><SelectItem key="custom">自定义 CIDR</SelectItem></Select><Select isRequired label="后端入口转发" placeholder="选择现有 TCP 转发" selectedKeys={route.backendForwardId ? [route.backendForwardId] : []} onSelectionChange={keys => updateRoute(index, { backendForwardId: String(Array.from(keys)[0] || '') })}>{backendGroups.map((group, groupIndex) => <SelectSection key={`port-${group.port}`} title={`端口 ${group.port} (${group.options.length})`} showDivider={groupIndex < backendGroups.length - 1}>{group.options.map(forward => <SelectItem key={String(forward.id)} textValue={`端口 ${forward.inPort} ${forward.nodeName || ''} ${forward.entryHost || ''} ${forward.name}`} isDisabled={forward.nodeStatus !== 1}>{backendText(forward)} · {forward.name}</SelectItem>)}</SelectSection>)}</Select><div className="flex justify-end gap-1"><Switch size="sm" isSelected={route.enabled} onValueChange={enabled => updateRoute(index, { enabled })}>启用</Switch>{route.carrier !== 'default' && <Button isIconOnly size="sm" variant="light" color="danger" aria-label="删除线路" title="删除线路" onPress={() => removeRoute(index)}><X size={17} /></Button>}</div></div>{route.carrier === 'custom' ? <Textarea className="mt-3" label="CIDR 列表" placeholder="每行一个，例如：\n113.0.0.0/8\n2408:8000::/20" minRows={3} value={route.cidrs} onValueChange={cidrs => updateRoute(index, { cidrs })} /> : route.carrier === 'default' ? <p className="mt-2 text-xs text-default-500">default 不匹配来源 IP，作为后端失败和未知来源的回退线路。</p> : <p className="mt-2 text-xs text-default-500">保存时自动使用 {carrierLabels[route.carrier]} IP 库；当前缓存 {carrierMap.get(route.carrier as 'telecom' | 'unicom' | 'mobile')?.cidrCount || 0} 条 CIDR。可通过页面顶部按钮刷新。</p>}</div>)}</div></section>
          <Switch isSelected={form.enabled} onValueChange={enabled => setForm({ ...form, enabled })}>启用统一入口服务</Switch>
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setFormOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submit}>保存并同步 Agent</Button></ModalFooter></ModalContent>
      </Modal>
    </div>
  );
}
