import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Switch } from '@heroui/switch';
import { Tabs, Tab } from '@heroui/tabs';
import { CircleCheck, Clock3, CloudCog, History, Pencil, Play, Plus, RefreshCw, Trash2, TriangleAlert } from 'lucide-react';
import toast from 'react-hot-toast';

import { deleteDynamicDnsProvider, deleteDynamicDnsRule, getDynamicDnsHistory, getDynamicDnsOverview, getNodeList, runDynamicDnsRule, saveDynamicDnsProvider, saveDynamicDnsRule, type DynamicDnsHistoryItem, type DynamicDnsOverview, type DynamicDnsProviderOption, type DynamicDnsProviderType, type DynamicDnsRule } from '@/api';

interface NodeOption { id: number; name: string; serverIp?: string; ip?: string; status: number; version?: string }
const EMPTY: DynamicDnsOverview = { rules: [], providers: [], summary: { rules: 0, active: 0, healthy: 0, errors: 0 }, minimumAgentVersion: '2.21.0' };
const providerLabel: Record<DynamicDnsProviderType, string> = { cloudflare: 'Cloudflare', dnspod: 'DNSPod', aliyun: '阿里云 DNS' };
const ruleInitial = { id: undefined as number | undefined, name: '', nodeId: '', providerKey: '', zoneName: '', recordName: '', recordType: 'A' as 'A' | 'AAAA', ttl: '600', checkIntervalSeconds: '60', enabled: true };
const providerInitial = { id: undefined as number | undefined, name: '', provider: 'cloudflare' as DynamicDnsProviderType, credentialA: '', credentialB: '', enabled: true };
const time = (value?: number) => value ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(value) : '尚未执行';
const statusLabel = (value: string) => value === 'success' ? '正常' : value === 'error' ? '失败' : '待检测';

export default function DynamicDnsPage() {
  const [data, setData] = useState(EMPTY);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('rules');
  const [ruleOpen, setRuleOpen] = useState(false);
  const [providerOpen, setProviderOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [ruleForm, setRuleForm] = useState(ruleInitial);
  const [providerForm, setProviderForm] = useState(providerInitial);
  const [history, setHistory] = useState<DynamicDnsHistoryItem[]>([]);
  const [historyName, setHistoryName] = useState('');
  const [busy, setBusy] = useState<number | string | null>(null);

  const load = async () => {
    setLoading(true);
    const [overview, nodeResponse] = await Promise.all([getDynamicDnsOverview(), getNodeList()]);
    if (overview.code === 0) setData(overview.data || EMPTY); else toast.error(overview.msg || '获取动态 DNS 失败');
    if (nodeResponse.code === 0) setNodes(nodeResponse.data || []);
    setLoading(false);
  };
  useEffect(() => { void load(); }, []);

  const dynamicProviders = useMemo(() => {
    const unique = new Map<number, DynamicDnsProviderOption>();
    data.providers.filter(item => item.source === 'dynamic').forEach(item => unique.set(item.id, item));
    return Array.from(unique.values());
  }, [data.providers]);
  const providerOption = data.providers.find(item => item.optionKey === ruleForm.providerKey);

  const openRule = (rule?: DynamicDnsRule) => {
    const provider = rule ? data.providers.find(item => item.source === rule.providerSource && item.id === rule.providerRefId && (!rule.zoneRefId || item.zoneRefId === rule.zoneRefId)) : undefined;
    setRuleForm(rule ? { id: rule.id, name: rule.name, nodeId: String(rule.nodeId), providerKey: provider?.optionKey || `${rule.providerSource}:${rule.providerRefId}`, zoneName: rule.zoneName, recordName: rule.recordName, recordType: rule.recordType, ttl: String(rule.ttl), checkIntervalSeconds: String(rule.checkIntervalSeconds), enabled: Boolean(rule.enabled) } : ruleInitial);
    setRuleOpen(true);
  };
  const saveRule = async () => {
    const option = data.providers.find(item => item.optionKey === ruleForm.providerKey);
    if (!ruleForm.nodeId || !option || !ruleForm.recordName.trim()) return toast.error('请选择节点、DNS 配置并填写记录');
    setBusy('rule');
    const response = await saveDynamicDnsRule({ id: ruleForm.id, name: ruleForm.name, nodeId: Number(ruleForm.nodeId), providerSource: option.source, providerRefId: option.id, provider: option.provider, zoneRefId: option.zoneRefId, zoneName: option.zoneName || ruleForm.zoneName, recordName: ruleForm.recordName, recordType: ruleForm.recordType, ttl: Number(ruleForm.ttl), checkIntervalSeconds: Number(ruleForm.checkIntervalSeconds), enabled: ruleForm.enabled });
    setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || '保存规则失败');
    toast.success('动态 DNS 规则已保存'); setRuleOpen(false); await load();
  };
  const openProvider = (provider?: DynamicDnsProviderOption) => {
    setProviderForm(provider ? { id: provider.id, name: provider.name, provider: provider.provider, credentialA: '', credentialB: '', enabled: Boolean(provider.enabled) } : providerInitial);
    setProviderOpen(true);
  };
  const saveProvider = async () => {
    if (!providerForm.name.trim() || (!providerForm.id && !providerForm.credentialA.trim())) return toast.error('请填写配置名称和凭据');
    if (providerForm.provider !== 'cloudflare' && !providerForm.id && !providerForm.credentialB.trim()) return toast.error('请填写完整的密钥对');
    setBusy('provider'); const response = await saveDynamicDnsProvider(providerForm); setBusy(null);
    if (response.code !== 0) return toast.error(response.msg || '保存 DNS 配置失败');
    toast.success('DNS 提供商配置已保存'); setProviderOpen(false); await load();
  };
  const run = async (rule: DynamicDnsRule) => { setBusy(rule.id); const response = await runDynamicDnsRule(rule.id); setBusy(null); if (response.code !== 0) toast.error(response.msg || '执行失败'); else toast.success('检测和同步已完成'); await load(); };
  const showHistory = async (rule: DynamicDnsRule) => { const response = await getDynamicDnsHistory(rule.id); if (response.code !== 0) return toast.error(response.msg || '读取历史失败'); setHistory(response.data || []); setHistoryName(rule.name); setHistoryOpen(true); };
  const removeRule = async (rule: DynamicDnsRule) => { if (!window.confirm(`删除动态 DNS 规则“${rule.name}”？DNS 记录会保留。`)) return; const response = await deleteDynamicDnsRule(rule.id); if (response.code !== 0) return toast.error(response.msg || '删除失败'); await load(); };
  const removeProvider = async (provider: DynamicDnsProviderOption) => { if (!window.confirm(`删除 DNS 配置“${provider.name}”？`)) return; const response = await deleteDynamicDnsProvider(provider.id); if (response.code !== 0) return toast.error(response.msg || '删除失败'); await load(); };

  return <div className="mx-auto w-full max-w-[1500px] space-y-5 p-4 md:p-6">
    <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between"><div><p className="text-sm text-default-500">域名自动维护</p><h1 className="mt-1 text-2xl font-semibold">动态 DNS</h1></div><div className="flex gap-2"><Button isIconOnly variant="flat" aria-label="刷新" onPress={load}><RefreshCw size={17} /></Button>{tab === 'rules' ? <Button color="primary" startContent={<Plus size={17} />} onPress={() => openRule()}>新建规则</Button> : <Button color="primary" startContent={<Plus size={17} />} onPress={() => openProvider()}>添加 DNS 配置</Button>}</div></header>
    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{[['规则', data.summary.rules], ['运行中', data.summary.active], ['正常', data.summary.healthy], ['失败', data.summary.errors]].map(([label, value], index) => <div key={String(label)} className="rounded-md border border-divider bg-content1 p-4"><p className="text-sm text-default-500">{label}</p><p className={`mt-2 text-2xl font-semibold ${index === 3 && Number(value) ? 'text-danger' : ''}`}>{value}</p></div>)}</section>
    <Tabs selectedKey={tab} onSelectionChange={key => setTab(String(key))} variant="underlined"><Tab key="rules" title={`更新规则 ${data.rules.length}`} /><Tab key="providers" title={`DNS 配置 ${dynamicProviders.length}`} /></Tabs>
    {tab === 'rules' ? <section className="overflow-hidden rounded-md border border-divider bg-content1">
      <div className="hidden grid-cols-[minmax(190px,1.3fr)_minmax(180px,1fr)_160px_150px_140px_136px] gap-4 border-b border-divider px-4 py-3 text-xs font-semibold text-default-500 lg:grid"><span>域名记录</span><span>检测节点</span><span>DNS 提供商</span><span>当前地址</span><span>状态</span><span className="text-right">操作</span></div>
      {loading ? <div className="flex min-h-48 items-center justify-center text-default-500">正在读取规则</div> : data.rules.length === 0 ? <div className="flex min-h-48 flex-col items-center justify-center gap-2 text-default-500"><CloudCog size={30} /><span>尚未创建动态 DNS 规则</span></div> : data.rules.map((rule, index) => <div key={rule.id} className={`grid gap-3 px-4 py-4 lg:grid-cols-[minmax(190px,1.3fr)_minmax(180px,1fr)_160px_150px_140px_136px] lg:items-center ${index ? 'border-t border-divider' : ''}`}>
        <div className="min-w-0"><div className="flex items-center gap-2"><strong className="truncate">{rule.name}</strong><Chip size="sm" variant="flat">{rule.recordType}</Chip></div><p className="mt-1 truncate font-mono text-sm text-default-500">{rule.recordName}</p><p className="mt-1 text-xs text-default-400">每 {rule.checkIntervalSeconds} 秒检测 · TTL {rule.ttl}</p></div>
        <div><p className="truncate font-medium">{rule.nodeName || `节点 ${rule.nodeId}`}</p><p className={`mt-1 text-xs ${rule.nodeOnline ? 'text-success' : 'text-danger'}`}>{rule.nodeOnline ? '在线' : '离线'} · Agent {rule.nodeVersion || '未知'}</p></div>
        <div><p>{providerLabel[rule.provider]}</p><p className="mt-1 truncate text-xs text-default-500">{rule.providerAccountName || rule.zoneName}</p></div>
        <div><p className="truncate font-mono text-sm">{rule.lastAppliedIp || '尚未同步'}</p>{rule.lastDetectedIp && rule.lastDetectedIp !== rule.lastAppliedIp && <p className="mt-1 truncate text-xs text-warning">检测到 {rule.lastDetectedIp}</p>}</div>
        <div><Chip size="sm" variant="flat" color={!rule.enabled ? 'default' : rule.lastStatus === 'success' ? 'success' : rule.lastStatus === 'error' ? 'danger' : 'warning'} startContent={rule.lastStatus === 'success' ? <CircleCheck size={13} /> : rule.lastStatus === 'error' ? <TriangleAlert size={13} /> : <Clock3 size={13} />}>{!rule.enabled ? '已停用' : statusLabel(rule.lastStatus)}</Chip><p className="mt-1 text-xs text-default-400">{time(rule.lastCheckedAt)}</p>{rule.lastError && <p className="mt-1 line-clamp-2 text-xs text-danger" title={rule.lastError}>{rule.lastError}</p>}</div>
        <div className="flex justify-end gap-1"><Button isIconOnly size="sm" variant="light" aria-label="立即检测" isLoading={busy === rule.id} onPress={() => run(rule)}><Play size={16} /></Button><Button isIconOnly size="sm" variant="light" aria-label="历史" onPress={() => showHistory(rule)}><History size={16} /></Button><Button isIconOnly size="sm" variant="light" aria-label="编辑" onPress={() => openRule(rule)}><Pencil size={16} /></Button><Button isIconOnly size="sm" color="danger" variant="light" aria-label="删除" onPress={() => removeRule(rule)}><Trash2 size={16} /></Button></div>
      </div>)}
    </section> : <section className="overflow-hidden rounded-md border border-divider bg-content1">
      <div className="border-b border-divider px-4 py-3 text-sm text-default-500">这里保存 DNSPod、阿里云和额外 Cloudflare 凭据。DNS 与域名页面登记的 Cloudflare Zone 会自动出现在规则选择器中。</div>
      {dynamicProviders.length === 0 ? <div className="flex min-h-48 items-center justify-center text-default-500">尚未添加独立 DNS 配置</div> : dynamicProviders.map((provider, index) => <div key={provider.id} className={`flex flex-col gap-3 px-4 py-4 sm:flex-row sm:items-center sm:justify-between ${index ? 'border-t border-divider' : ''}`}><div><div className="flex items-center gap-2"><strong>{provider.name}</strong><Chip size="sm" variant="flat">{providerLabel[provider.provider]}</Chip><Chip size="sm" variant="flat" color={provider.enabled ? 'success' : 'default'}>{provider.enabled ? '启用' : '停用'}</Chip></div><p className="mt-1 text-sm text-default-500">凭据已加密保存，页面不会回显密钥</p>{provider.lastError && <p className="mt-1 text-sm text-danger">{provider.lastError}</p>}</div><div className="flex gap-1"><Button isIconOnly size="sm" variant="light" aria-label="编辑" onPress={() => openProvider(provider)}><Pencil size={16} /></Button><Button isIconOnly size="sm" color="danger" variant="light" aria-label="删除" onPress={() => removeProvider(provider)}><Trash2 size={16} /></Button></div></div>)}
    </section>}

    <Modal isOpen={ruleOpen} onOpenChange={setRuleOpen} size="3xl"><ModalContent><ModalHeader>{ruleForm.id ? '编辑动态 DNS 规则' : '新建动态 DNS 规则'}</ModalHeader><ModalBody className="space-y-4"><div className="grid gap-4 md:grid-cols-2"><Input label="规则名称" placeholder="家庭宽带 IPv4" value={ruleForm.name} onValueChange={value => setRuleForm({ ...ruleForm, name: value })} /><Select isRequired label="检测节点" description={`Agent 需为 ${data.minimumAgentVersion} 或更高版本`} selectedKeys={ruleForm.nodeId ? [ruleForm.nodeId] : []} onSelectionChange={keys => setRuleForm({ ...ruleForm, nodeId: String(Array.from(keys)[0] || '') })}>{nodes.map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · {node.status === 1 ? '在线' : '离线'} · Agent {node.version || '未知'}</SelectItem>)}</Select></div><Select isRequired label="DNS 提供商配置" selectedKeys={ruleForm.providerKey ? [ruleForm.providerKey] : []} onSelectionChange={keys => setRuleForm({ ...ruleForm, providerKey: String(Array.from(keys)[0] || '') })}>{data.providers.filter(item => item.enabled).map(item => <SelectItem key={item.optionKey} textValue={`${item.name} ${item.zoneName || ''}`}>{providerLabel[item.provider]} · {item.name}{item.zoneName ? ` · ${item.zoneName}` : ''}</SelectItem>)}</Select>{providerOption?.source === 'dynamic' && <Input isRequired label="主域名（Zone）" placeholder="example.com" value={ruleForm.zoneName} onValueChange={value => setRuleForm({ ...ruleForm, zoneName: value })} />}<div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_150px]"><Input isRequired label="域名记录" placeholder="home.example.com 或 home" value={ruleForm.recordName} onValueChange={value => setRuleForm({ ...ruleForm, recordName: value })} /><Select label="记录类型" selectedKeys={[ruleForm.recordType]} onSelectionChange={keys => setRuleForm({ ...ruleForm, recordType: String(Array.from(keys)[0] || 'A') as 'A' | 'AAAA' })}><SelectItem key="A">A · IPv4</SelectItem><SelectItem key="AAAA">AAAA · IPv6</SelectItem></Select></div><div className="grid gap-4 md:grid-cols-2"><Input type="number" min={60} max={86400} label="DNS TTL（秒）" value={ruleForm.ttl} onValueChange={value => setRuleForm({ ...ruleForm, ttl: value })} /><Input type="number" min={30} max={86400} label="检测间隔（秒）" description="最低 30 秒；仅地址变化时更新 DNS" value={ruleForm.checkIntervalSeconds} onValueChange={value => setRuleForm({ ...ruleForm, checkIntervalSeconds: value })} /></div><Switch isSelected={ruleForm.enabled} onValueChange={value => setRuleForm({ ...ruleForm, enabled: value })}>启用自动检测</Switch></ModalBody><ModalFooter><Button variant="flat" onPress={() => setRuleOpen(false)}>取消</Button><Button color="primary" isLoading={busy === 'rule'} onPress={saveRule}>保存规则</Button></ModalFooter></ModalContent></Modal>

    <Modal isOpen={providerOpen} onOpenChange={setProviderOpen} size="2xl"><ModalContent><ModalHeader>{providerForm.id ? '编辑 DNS 提供商' : '添加 DNS 提供商'}</ModalHeader><ModalBody className="space-y-4"><div className="grid gap-4 md:grid-cols-2"><Input isRequired label="配置名称" placeholder="我的 DNSPod" value={providerForm.name} onValueChange={value => setProviderForm({ ...providerForm, name: value })} /><Select label="提供商" selectedKeys={[providerForm.provider]} onSelectionChange={keys => setProviderForm({ ...providerForm, provider: String(Array.from(keys)[0] || 'cloudflare') as DynamicDnsProviderType })}><SelectItem key="cloudflare">Cloudflare</SelectItem><SelectItem key="dnspod">DNSPod</SelectItem><SelectItem key="aliyun">阿里云 DNS</SelectItem></Select></div><Input type="password" autoComplete="off" label={providerForm.provider === 'cloudflare' ? 'API Token' : providerForm.provider === 'dnspod' ? 'SecretId' : 'AccessKey ID'} placeholder={providerForm.id ? '已保存，留空保持不变' : ''} value={providerForm.credentialA} onValueChange={value => setProviderForm({ ...providerForm, credentialA: value })} />{providerForm.provider !== 'cloudflare' && <Input type="password" autoComplete="off" label={providerForm.provider === 'dnspod' ? 'SecretKey' : 'AccessKey Secret'} placeholder={providerForm.id ? '已保存，留空保持不变' : ''} value={providerForm.credentialB} onValueChange={value => setProviderForm({ ...providerForm, credentialB: value })} />}<Switch isSelected={providerForm.enabled} onValueChange={value => setProviderForm({ ...providerForm, enabled: value })}>启用此配置</Switch></ModalBody><ModalFooter><Button variant="flat" onPress={() => setProviderOpen(false)}>取消</Button><Button color="primary" isLoading={busy === 'provider'} onPress={saveProvider}>保存配置</Button></ModalFooter></ModalContent></Modal>

    <Modal isOpen={historyOpen} onOpenChange={setHistoryOpen} size="3xl" scrollBehavior="inside"><ModalContent><ModalHeader>{historyName} · 变更历史</ModalHeader><ModalBody>{history.length === 0 ? <div className="flex min-h-40 items-center justify-center text-default-500">尚无检测记录</div> : <div className="divide-y divide-divider border-y border-divider">{history.map(item => <div key={item.id} className="grid gap-2 py-3 sm:grid-cols-[120px_minmax(0,1fr)_160px] sm:items-center"><Chip size="sm" className="w-fit" variant="flat" color={item.status === 'updated' ? 'success' : item.status === 'failed' ? 'danger' : 'default'}>{item.status === 'updated' ? '地址已更新' : item.status === 'failed' ? '更新失败' : '地址未变化'}</Chip><div className="min-w-0"><p className="truncate font-mono text-sm">{item.oldIp || '-'} {item.status === 'updated' ? '→' : ''} {item.newIp || ''}</p>{item.error && <p className="mt-1 text-sm text-danger">{item.error}</p>}</div><span className="text-xs text-default-500 sm:text-right">{time(item.createdTime)}</span></div>)}</div>}</ModalBody><ModalFooter><Button variant="flat" onPress={() => setHistoryOpen(false)}>关闭</Button></ModalFooter></ModalContent></Modal>
  </div>;
}
