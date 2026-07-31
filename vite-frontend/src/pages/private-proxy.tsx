import { useEffect, useMemo, useState } from 'react';
import { Accordion, AccordionItem } from '@heroui/accordion';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input, Textarea } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { Tab, Tabs } from '@heroui/tabs';
import { Clock3, Copy, Globe2, KeyRound, Pause, Play, Plus, Server, ShieldCheck, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  createPrivateProxy, deletePrivateProxy, getNodeList, getPrivateProxies,
  getPrivateProxyClientConfig, pausePrivateProxy, resumePrivateProxy,
  type PrivateProxyClientConfig, type PrivateProxyItem, type PrivateProxyType,
} from '@/api';

interface NodeOption {
  id: number; name: string; ip?: string; serverIp?: string; status: number; version?: string;
  quotaAvailable?: boolean; unavailableReason?: string;
}

type Cipher = 'aes-128-gcm' | 'aes-256-gcm' | 'chacha20-ietf-poly1305';
type RealityPreset = 'www.cloudflare.com' | 'www.google.com' | 'custom';
type ProxyGroup = 'general' | 'encrypted' | 'quic' | 'vpn';

const DEFAULT_REALITY_SERVER = 'www.cloudflare.com';

interface ProxyForm {
  name: string; nodeId: string; proxyType: PrivateProxyType; bindIp: string; listenPort: string;
  authUsername: string; authPassword: string; cipher: Cipher; realityPreset: RealityPreset; realityServerName: string;
  allowedCidrs: string; permanent: boolean; leaseHours: string;
}

const randomSecret = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(18));
  return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
};

const initialForm = (): ProxyForm => ({
  name: '', nodeId: '', proxyType: 'socks5', bindIp: '', listenPort: '', authUsername: '',
  authPassword: '', cipher: 'aes-256-gcm', realityPreset: DEFAULT_REALITY_SERVER,
  realityServerName: DEFAULT_REALITY_SERVER, allowedCidrs: '',
  permanent: true, leaseHours: '24',
});

const protocolMeta: Record<PrivateProxyType, { label: string; access: string }> = {
  socks5: { label: 'SOCKS5', access: '用户名 / 密码' },
  http: { label: 'HTTP', access: '用户名 / 密码' },
  shadowsocks: { label: 'Shadowsocks', access: 'TCP + UDP' },
  vless_reality: { label: 'VLESS + REALITY', access: 'UUID / Reality' },
  trojan: { label: 'Trojan', access: 'TLS 密钥' },
  hysteria2: { label: 'Hysteria2', access: 'QUIC / UDP' },
  tuic: { label: 'TUIC v5', access: 'QUIC / UUID' },
  wireguard: { label: 'WireGuard', access: '设备 VPN 配置' },
};

const protocolGroups: Record<ProxyGroup, { label: string; protocols: PrivateProxyType[] }> = {
  general: { label: '通用代理', protocols: ['socks5', 'http', 'shadowsocks'] },
  encrypted: { label: '加密代理', protocols: ['vless_reality', 'trojan'] },
  quic: { label: 'QUIC 加速', protocols: ['hysteria2', 'tuic'] },
  vpn: { label: '设备组网', protocols: ['wireguard'] },
};

const usesSecret = (proxyType: PrivateProxyType) => ['shadowsocks', 'trojan', 'hysteria2', 'tuic'].includes(proxyType);
const isAdvancedRuntime = (proxyType: PrivateProxyType) => ['trojan', 'hysteria2', 'tuic', 'wireguard'].includes(proxyType);

const stateMeta: Record<PrivateProxyItem['state'], { label: string; color: 'success' | 'warning' | 'danger' | 'default' }> = {
  active: { label: '运行中', color: 'success' }, paused: { label: '已暂停', color: 'warning' },
  provisioning: { label: '配置中', color: 'default' }, error: { label: '配置失败', color: 'danger' },
  delete_pending: { label: '待清理', color: 'warning' }, expired: { label: '已到期', color: 'default' },
};

const formatBytes = (value = 0) => {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = Math.max(0, value);
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit += 1; }
  return `${size >= 100 || unit === 0 ? size.toFixed(0) : size.toFixed(2)} ${units[unit]}`;
};

const copyText = async (value: string, label: string) => {
  await navigator.clipboard.writeText(value);
  toast.success(`${label}已复制`);
};

export default function PrivateProxyPage() {
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [configLoading, setConfigLoading] = useState(false);
  const [clientConfig, setClientConfig] = useState<PrivateProxyClientConfig | null>(null);
  const [items, setItems] = useState<PrivateProxyItem[]>([]);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [form, setForm] = useState<ProxyForm>(initialForm);
  const [protocolGroup, setProtocolGroup] = useState<ProxyGroup>('general');

  const load = async () => {
    setLoading(true);
    const [proxyRes, nodeRes] = await Promise.all([getPrivateProxies(), getNodeList()]);
    if (proxyRes.code === 0) setItems(proxyRes.data || []); else toast.error(proxyRes.msg || '加载代理失败');
    if (nodeRes.code === 0) setNodes(nodeRes.data || []);
    setLoading(false);
  };
  useEffect(() => { void load(); }, []);

  const summary = useMemo(() => ({
    active: items.filter(item => item.state === 'active').length,
    paused: items.filter(item => item.state === 'paused').length,
    attention: items.filter(item => ['error', 'delete_pending'].includes(item.state)).length,
  }), [items]);

  const nodeGroups = useMemo(() => {
    const groups = new Map<number, PrivateProxyItem[]>();
    items.forEach(item => groups.set(item.nodeId, [...(groups.get(item.nodeId) || []), item]));
    return Array.from(groups.entries()).map(([nodeId, proxies]) => {
      const first = proxies[0];
      const protocols = Array.from(new Set(proxies.map(item => protocolMeta[item.proxyType].label)));
      return {
        nodeId,
        nodeName: first.nodeName,
        publicHost: first.publicHost,
        nodeOnline: first.nodeOnline,
        proxies,
        protocols,
        activeCount: proxies.filter(item => item.state === 'active').length,
        attentionCount: proxies.filter(item => ['error', 'delete_pending'].includes(item.state)).length,
      };
    });
  }, [items]);

  const selectProtocol = (proxyType: PrivateProxyType) => {
    setForm(current => ({
      ...current,
      proxyType,
      authUsername: proxyType === 'socks5' || proxyType === 'http' ? current.authUsername : '',
      authPassword: proxyType === 'vless_reality' ? '' : (current.authPassword || randomSecret()),
      realityPreset: proxyType === 'vless_reality' ? current.realityPreset : DEFAULT_REALITY_SERVER,
      realityServerName: proxyType === 'vless_reality' ? (current.realityServerName || DEFAULT_REALITY_SERVER) : DEFAULT_REALITY_SERVER,
    }));
  };

  const submit = async () => {
    if (!form.name.trim() || !form.nodeId || !form.listenPort) return toast.error('请填写名称、节点和监听端口');
    if ((form.proxyType === 'socks5' || form.proxyType === 'http') && (!form.authUsername.trim() || form.authPassword.length < 8)) {
      return toast.error('用户名不能为空，密码至少 8 位');
    }
    if (usesSecret(form.proxyType) && form.authPassword.length < 8) return toast.error(`${protocolMeta[form.proxyType].label} 密钥至少 8 位`);
    if (form.proxyType === 'vless_reality' && !form.realityServerName.trim()) return toast.error('请填写 REALITY 伪装域名');
    const port = Number(form.listenPort);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return toast.error('监听端口应为 1-65535');
    setSubmitting(true);
    const response = await createPrivateProxy({
      name: form.name.trim(), nodeId: Number(form.nodeId), proxyType: form.proxyType,
      bindIp: form.bindIp.trim(), listenPort: port, authUsername: form.authUsername.trim(),
      authPassword: form.authPassword, cipher: form.cipher, realityServerName: form.realityServerName.trim(),
      allowedCidrs: form.allowedCidrs.trim(), permanent: form.permanent,
      leaseHours: form.permanent ? undefined : Number(form.leaseHours),
    });
    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || '创建代理失败');
    toast.success(`${protocolMeta[form.proxyType].label} 已创建`);
    setModalOpen(false);
    setForm(initialForm());
    setProtocolGroup('general');
    void load();
  };

  const showClientConfig = async (item: PrivateProxyItem) => {
    setConfigLoading(true);
    const response = await getPrivateProxyClientConfig(item.id);
    setConfigLoading(false);
    if (response.code !== 0 || !response.data) return toast.error(response.msg || '读取连接信息失败');
    setClientConfig(response.data);
  };

  const control = async (item: PrivateProxyItem, action: 'pause' | 'resume' | 'delete') => {
    if (action === 'delete' && !window.confirm(`确认删除代理“${item.name}”吗？`)) return;
    const response = action === 'pause' ? await pausePrivateProxy(item.id) : action === 'resume' ? await resumePrivateProxy(item.id) : await deletePrivateProxy(item.id);
    if (response.code !== 0) return toast.error(response.msg || '操作失败');
    toast.success(action === 'pause' ? '代理已暂停' : action === 'resume' ? '代理已恢复' : response.data || '代理已删除');
    void load();
  };

  const configRows = clientConfig ? [
    ['服务器', clientConfig.host], ['端口', String(clientConfig.port)],
    ...(clientConfig.username ? [['用户名', clientConfig.username]] : []),
    ...(clientConfig.password ? [['密码', clientConfig.password]] : []),
    ...(clientConfig.cipher ? [['加密方式', clientConfig.cipher]] : []),
    ...(clientConfig.clientId ? [['UUID', clientConfig.clientId]] : []),
    ...(clientConfig.publicKey ? [['REALITY 公钥', clientConfig.publicKey]] : []),
    ...(clientConfig.shortId ? [['Short ID', clientConfig.shortId]] : []),
    ...(clientConfig.serverName ? [['伪装域名', clientConfig.serverName]] : []),
    ...(clientConfig.flow ? [['流控', clientConfig.flow]] : []),
    ...(clientConfig.clientPrivateKey ? [['客户端私钥', clientConfig.clientPrivateKey]] : []),
    ...(clientConfig.serverPublicKey ? [['服务器公钥', clientConfig.serverPublicKey]] : []),
    ...(clientConfig.clientAddress ? [['客户端地址', clientConfig.clientAddress]] : []),
  ] : [];

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-5 p-4 md:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div><p className="text-sm text-default-500">独立服务</p><h1 className="mt-1 text-2xl font-semibold">私人代理</h1></div>
        <Button color="primary" startContent={<Plus size={18} />} onPress={() => setModalOpen(true)}>新建代理</Button>
      </header>

      <section className="grid overflow-hidden rounded-lg border border-divider sm:grid-cols-3">
        <div className="border-b border-divider px-5 py-4 sm:border-b-0 sm:border-r"><div className="text-xs text-default-500">运行中</div><div className="mt-1 text-2xl font-semibold text-success">{summary.active}</div></div>
        <div className="border-b border-divider px-5 py-4 sm:border-b-0 sm:border-r"><div className="text-xs text-default-500">已暂停</div><div className="mt-1 text-2xl font-semibold">{summary.paused}</div></div>
        <div className="px-5 py-4"><div className="text-xs text-default-500">需要处理</div><div className="mt-1 text-2xl font-semibold text-danger">{summary.attention}</div></div>
      </section>

      {loading ? <div className="flex min-h-64 items-center justify-center"><Spinner /></div> : items.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><ShieldCheck size={30} /><span>暂无私人代理</span></div>
      ) : (
        <Accordion selectionMode="multiple" variant="bordered" className="px-0">
          {nodeGroups.map(group => (
            <AccordionItem
              key={String(group.nodeId)}
              aria-label={`${group.nodeName} 的私人代理`}
              classNames={{
                base: group.attentionCount ? 'border-danger-200' : 'border-divider',
                trigger: 'gap-3 px-4 py-4 md:px-5',
                content: 'pb-0',
              }}
              startContent={<span className={`flex h-10 w-10 flex-none items-center justify-center rounded-md ${group.nodeOnline ? 'bg-success-50 text-success dark:bg-success-500/10' : 'bg-danger-50 text-danger dark:bg-danger-500/10'}`}><Server size={19} /></span>}
              title={
                <div className="flex min-w-0 flex-1 flex-col gap-3 pr-2 lg:flex-row lg:items-center lg:justify-between">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="truncate font-semibold">{group.nodeName}</span>
                      <Chip size="sm" variant="flat" color={group.nodeOnline ? 'success' : 'danger'}>{group.nodeOnline ? '在线' : '离线'}</Chip>
                    </div>
                    <p className="mt-1 truncate font-mono text-xs font-normal text-default-500">{group.publicHost || '未设置公网地址'}</p>
                    <p className="mt-1 line-clamp-1 text-xs font-normal text-default-500">{group.protocols.join(' · ')}</p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2 lg:justify-end">
                    {group.attentionCount > 0 && <Chip size="sm" variant="flat" color="danger">{group.attentionCount} 个异常</Chip>}
                    <Chip size="sm" variant="flat" color="success">运行 {group.activeCount}/{group.proxies.length}</Chip>
                    <Chip size="sm" variant="flat" color="primary">{group.proxies.length} 个代理</Chip>
                  </div>
                </div>
              }
            >
              <div className="overflow-hidden border-t border-divider">
                <div className="hidden grid-cols-[1.3fr_1fr_1.1fr_1fr_auto] gap-4 bg-default-50 px-4 py-3 text-xs text-default-500 lg:grid"><span>代理</span><span>公网入口</span><span>访问控制</span><span>有效期</span><span>操作</span></div>
                {group.proxies.map(item => {
                  const meta = stateMeta[item.state];
                  const protocol = protocolMeta[item.proxyType];
                  return <div key={item.id} className="grid gap-3 border-t border-divider px-4 py-4 first:border-t-0 lg:grid-cols-[1.3fr_1fr_1.1fr_1fr_auto] lg:items-center">
                    <div><div className="flex flex-wrap items-center gap-2 font-medium"><span>{item.name}</span><Chip size="sm" variant="flat" color={meta.color}>{meta.label}</Chip></div><div className="mt-1 text-xs text-default-500">{protocol.label} · {item.ownerUserName}</div><div className="mt-1 text-xs text-default-500">{isAdvancedRuntime(item.proxyType) ? '运行时流量统计暂未启用' : `上传 ${formatBytes(item.outFlow)} · 下载 ${formatBytes(item.inFlow)}`}</div></div>
                    <div><div className="font-mono text-sm break-all">{item.publicHost || '未设置'}:{item.listenPort}</div><div className="mt-1 text-xs text-default-500">{['hysteria2', 'tuic', 'wireguard'].includes(item.proxyType) ? 'UDP' : 'TCP'}</div></div>
                    <div><div className="text-sm">{protocol.access}</div><div className="mt-1 text-xs text-default-500">{item.allowedCidrs ? `白名单 ${item.allowedCidrs.split(',').length} 条` : '允许任意来源 IP'}</div></div>
                    <div className="flex items-center gap-2 text-sm"><Clock3 size={15} className="shrink-0 text-default-400" />{item.expiresAt ? new Date(item.expiresAt).toLocaleString() : '永久'}</div>
                    <div className="flex gap-1">
                      <Button isIconOnly size="sm" variant="light" aria-label={`查看 ${item.name} 连接信息`} title="连接信息" isLoading={configLoading} onPress={() => showClientConfig(item)}><KeyRound size={17} /></Button>
                      {item.state === 'active' && <Button isIconOnly size="sm" variant="light" aria-label={`暂停 ${item.name}`} title="暂停代理" onPress={() => control(item, 'pause')}><Pause size={17} /></Button>}
                      {item.state === 'paused' && <Button isIconOnly size="sm" variant="light" color="success" aria-label={`恢复 ${item.name}`} title="恢复代理" onPress={() => control(item, 'resume')}><Play size={17} /></Button>}
                      <Button isIconOnly size="sm" variant="light" color="danger" aria-label={`删除 ${item.name}`} title="删除代理" onPress={() => control(item, 'delete')}><Trash2 size={17} /></Button>
                    </div>
                    {item.lastError && <div className="text-xs text-danger lg:col-span-5">{item.lastError}</div>}
                  </div>;
                })}
              </div>
            </AccordionItem>
          ))}
        </Accordion>
      )}

      <Modal isOpen={modalOpen} onOpenChange={setModalOpen} size="3xl" scrollBehavior="inside">
        <ModalContent><ModalHeader>新建私人代理</ModalHeader><ModalBody className="gap-4">
          <Tabs selectedKey={protocolGroup} onSelectionChange={key => {
            const group = String(key) as ProxyGroup;
            setProtocolGroup(group);
            selectProtocol(protocolGroups[group].protocols[0]);
          }} fullWidth variant="underlined" aria-label="代理协议分类">
            {Object.entries(protocolGroups).map(([key, group]) => <Tab key={key} title={group.label} />)}
          </Tabs>
          <Tabs selectedKey={form.proxyType} onSelectionChange={key => selectProtocol(String(key) as PrivateProxyType)} fullWidth aria-label="代理协议">
            {protocolGroups[protocolGroup].protocols.map(proxyType => <Tab key={proxyType} title={protocolMeta[proxyType].label} />)}
          </Tabs>
          <div className="grid gap-4 md:grid-cols-2">
            <Input label="代理名称" value={form.name} onValueChange={value => setForm({ ...form, name: value })} />
            <Select label="服务器节点" placeholder="选择在线节点" selectedKeys={form.nodeId ? [form.nodeId] : []} onSelectionChange={keys => setForm({ ...form, nodeId: String(Array.from(keys)[0] || '') })}>
              {nodes.filter(node => node.status === 1).map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · {node.serverIp || node.ip || '未设置地址'}{node.quotaAvailable === false ? ` · ${node.unavailableReason}` : ''}</SelectItem>)}
            </Select>
            <Input label="监听端口" type="number" value={form.listenPort} onValueChange={value => setForm({ ...form, listenPort: value })} />
            <Input label="监听 IP（可选）" placeholder="留空监听全部地址" value={form.bindIp} onValueChange={value => setForm({ ...form, bindIp: value })} />
            {(form.proxyType === 'socks5' || form.proxyType === 'http') && <>
              <Input label="代理用户名" value={form.authUsername} onValueChange={value => setForm({ ...form, authUsername: value })} />
              <Input label="代理密码" type="password" value={form.authPassword} onValueChange={value => setForm({ ...form, authPassword: value })} />
            </>}
            {form.proxyType === 'shadowsocks' && <>
              <Select label="加密方式" selectedKeys={[form.cipher]} onSelectionChange={keys => setForm({ ...form, cipher: String(Array.from(keys)[0]) as Cipher })}>
                <SelectItem key="aes-256-gcm">AES-256-GCM</SelectItem><SelectItem key="aes-128-gcm">AES-128-GCM</SelectItem><SelectItem key="chacha20-ietf-poly1305">ChaCha20-IETF-Poly1305</SelectItem>
              </Select>
              <Input label="连接密码" type="password" value={form.authPassword} onValueChange={value => setForm({ ...form, authPassword: value })} endContent={<Button isIconOnly size="sm" variant="light" aria-label="生成随机密码" title="生成随机密码" onPress={() => setForm({ ...form, authPassword: randomSecret() })}><KeyRound size={16} /></Button>} />
            </>}
            {usesSecret(form.proxyType) && form.proxyType !== 'shadowsocks' && <Input className={form.proxyType === 'tuic' ? 'md:col-span-2' : ''} label={form.proxyType === 'trojan' ? 'Trojan 密钥' : form.proxyType === 'hysteria2' ? 'Hysteria2 密钥' : 'TUIC 密钥'} type="password" value={form.authPassword} onValueChange={value => setForm({ ...form, authPassword: value })} endContent={<Button isIconOnly size="sm" variant="light" aria-label="生成随机密钥" title="生成随机密钥" onPress={() => setForm({ ...form, authPassword: randomSecret() })}><KeyRound size={16} /></Button>} description={form.proxyType === 'hysteria2' || form.proxyType === 'tuic' ? 'QUIC 使用 UDP；请确认节点防火墙和云厂商安全组已放行该端口。' : '自动使用节点生成的 TLS 证书，导入客户端时已附带必要参数。'} />}
            {form.proxyType === 'wireguard' && <div className="rounded-md border border-divider bg-default-50 px-3 py-3 text-sm text-default-600 md:col-span-2">创建后生成独立的 WireGuard 客户端配置。请使用 WireGuard 客户端导入配置文件；仅支持 Linux 节点 Agent 2.38.0+，该协议使用 UDP。</div>}
            {form.proxyType === 'vless_reality' && <>
              <Select className="md:col-span-2" label="REALITY 伪装站" selectedKeys={[form.realityPreset]} onSelectionChange={keys => {
                const preset = String(Array.from(keys)[0] || DEFAULT_REALITY_SERVER) as RealityPreset;
                setForm({ ...form, realityPreset: preset, realityServerName: preset === 'custom' ? '' : preset });
              }} description="推荐站点已经过真实握手验证；节点 Agent 需为 2.20.0 或更高版本。">
                <SelectItem key="www.cloudflare.com">Cloudflare（推荐）</SelectItem>
                <SelectItem key="www.google.com">Google</SelectItem>
                <SelectItem key="custom">自定义域名</SelectItem>
              </Select>
              {form.realityPreset === 'custom' && <Input className="md:col-span-2" label="自定义伪装域名" placeholder="仅填写支持 TLS 1.3 的域名" value={form.realityServerName} onValueChange={value => setForm({ ...form, realityServerName: value })} description="部分 HTTPS 站点不兼容 REALITY；创建后请验证客户端连接。" />}
            </>}
          </div>
          {!isAdvancedRuntime(form.proxyType) ? <Textarea label="来源 IP 白名单（可选）" placeholder="203.0.113.10/32, 2001:db8::/64" value={form.allowedCidrs} onValueChange={value => setForm({ ...form, allowedCidrs: value })} minRows={2} /> : <div className="rounded-md border border-divider bg-default-50 px-3 py-3 text-sm text-default-600">该协议由节点独立运行时管理，仅支持 Linux 节点 Agent 2.38.0+。当前版本支持创建、暂停、恢复、删除与客户端配置导出；来源 IP 白名单暂不适用于此类协议。</div>}
          <div className="grid items-center gap-4 border-t border-divider pt-4 md:grid-cols-2">
            <Switch isSelected={form.permanent} onValueChange={value => setForm({ ...form, permanent: value })}>永久有效</Switch>
            {!form.permanent && <Input label="有效期（小时）" type="number" value={form.leaseHours} onValueChange={value => setForm({ ...form, leaseHours: value })} />}
          </div>
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setModalOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} startContent={!submitting && <Globe2 size={17} />} onPress={submit}>创建代理</Button></ModalFooter></ModalContent>
      </Modal>

      <Modal isOpen={Boolean(clientConfig)} onOpenChange={open => { if (!open) setClientConfig(null); }} size="2xl" scrollBehavior="inside">
        <ModalContent><ModalHeader>{clientConfig ? `${protocolMeta[clientConfig.proxyType].label} 连接信息` : '连接信息'}</ModalHeader><ModalBody>
          <div className="divide-y divide-divider border-y border-divider">
            {configRows.map(([label, value]) => <div key={label} className="grid min-h-12 grid-cols-[120px_1fr_auto] items-center gap-3 py-2">
              <span className="text-sm text-default-500">{label}</span><span className="break-all font-mono text-sm">{value}</span>
              <Button isIconOnly size="sm" variant="light" aria-label={`复制${label}`} title={`复制${label}`} onPress={() => copyText(value, label)}><Copy size={16} /></Button>
            </div>)}
          </div>
          {clientConfig && <div className="space-y-2 pt-2"><div className="flex items-center justify-between"><span className="text-sm font-medium">{clientConfig.proxyType === 'wireguard' ? 'WireGuard 配置文件' : '一键导入链接'}</span><Button size="sm" variant="flat" startContent={<Copy size={15} />} onPress={() => copyText(clientConfig.uri, clientConfig.proxyType === 'wireguard' ? 'WireGuard 配置' : '导入链接')}>复制</Button></div><Textarea isReadOnly value={clientConfig.uri} minRows={clientConfig.proxyType === 'wireguard' ? 9 : 3} classNames={{ input: 'font-mono text-xs break-all' }} /></div>}
        </ModalBody><ModalFooter><Button color="primary" onPress={() => setClientConfig(null)}>完成</Button></ModalFooter></ModalContent>
      </Modal>
    </div>
  );
}
