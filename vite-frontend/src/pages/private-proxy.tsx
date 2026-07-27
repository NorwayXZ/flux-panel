import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { Tab, Tabs } from '@heroui/tabs';
import { Textarea } from '@heroui/input';
import { Clock3, Globe2, Pause, Play, Plus, ShieldCheck, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  createPrivateProxy, deletePrivateProxy, getNodeList, getPrivateProxies,
  pausePrivateProxy, resumePrivateProxy, type PrivateProxyItem,
} from '@/api';

interface NodeOption {
  id: number; name: string; ip?: string; serverIp?: string; status: number; version?: string;
  quotaAvailable?: boolean; unavailableReason?: string;
}

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

export default function PrivateProxyPage() {
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [items, setItems] = useState<PrivateProxyItem[]>([]);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [form, setForm] = useState({ name: '', nodeId: '', proxyType: 'socks5' as 'socks5' | 'http', bindIp: '', listenPort: '', authUsername: '', authPassword: '', allowedCidrs: '', permanent: true, leaseHours: '24' });

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

  const submit = async () => {
    if (!form.name.trim() || !form.nodeId || !form.listenPort || !form.authUsername.trim() || !form.authPassword) return toast.error('请填写完整的代理配置');
    const port = Number(form.listenPort);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return toast.error('监听端口应为 1-65535');
    setSubmitting(true);
    const response = await createPrivateProxy({
      name: form.name.trim(), nodeId: Number(form.nodeId), proxyType: form.proxyType,
      bindIp: form.bindIp.trim(), listenPort: port, authUsername: form.authUsername.trim(),
      authPassword: form.authPassword, allowedCidrs: form.allowedCidrs.trim(), permanent: form.permanent,
      leaseHours: form.permanent ? undefined : Number(form.leaseHours),
    });
    setSubmitting(false);
    if (response.code !== 0) return toast.error(response.msg || '创建代理失败');
    toast.success('私人代理已创建');
    setModalOpen(false);
    setForm({ name: '', nodeId: '', proxyType: 'socks5', bindIp: '', listenPort: '', authUsername: '', authPassword: '', allowedCidrs: '', permanent: true, leaseHours: '24' });
    void load();
  };

  const control = async (item: PrivateProxyItem, action: 'pause' | 'resume' | 'delete') => {
    if (action === 'delete' && !window.confirm(`确认删除代理“${item.name}”吗？`)) return;
    const response = action === 'pause' ? await pausePrivateProxy(item.id) : action === 'resume' ? await resumePrivateProxy(item.id) : await deletePrivateProxy(item.id);
    if (response.code !== 0) return toast.error(response.msg || '操作失败');
    toast.success(action === 'pause' ? '代理已暂停' : action === 'resume' ? '代理已恢复' : response.data || '代理已删除');
    void load();
  };

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
        <div className="overflow-hidden rounded-lg border border-divider">
          <div className="hidden grid-cols-[1.2fr_1fr_1fr_1.2fr_1fr_auto] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500 lg:grid"><span>代理</span><span>节点</span><span>公网入口</span><span>访问控制</span><span>有效期</span><span>操作</span></div>
          {items.map(item => {
            const meta = stateMeta[item.state];
            return <div key={item.id} className="grid gap-3 border-t border-divider px-4 py-4 first:border-t-0 lg:grid-cols-[1.2fr_1fr_1fr_1.2fr_1fr_auto] lg:items-center">
              <div><div className="flex items-center gap-2 font-medium"><span>{item.name}</span><Chip size="sm" variant="flat" color={meta.color}>{meta.label}</Chip></div><div className="mt-1 text-xs text-default-500">{item.proxyType.toUpperCase()} · {item.ownerUserName}</div><div className="mt-1 text-xs text-default-500">上传 {formatBytes(item.outFlow)} · 下载 {formatBytes(item.inFlow)}</div></div>
              <div><div className="text-sm">{item.nodeName}</div><div className={`mt-1 text-xs ${item.nodeOnline ? 'text-success' : 'text-danger'}`}>{item.nodeOnline ? '在线' : '离线'}</div></div>
              <div className="font-mono text-sm break-all">{item.publicHost || '未设置'}:{item.listenPort}</div>
              <div><div className="text-sm">用户 {item.authUsername}</div><div className="mt-1 text-xs text-default-500">{item.allowedCidrs ? `白名单 ${item.allowedCidrs.split(',').length} 条` : '允许任意来源 IP'}</div></div>
              <div className="flex items-center gap-2 text-sm"><Clock3 size={15} className="text-default-400" />{item.expiresAt ? new Date(item.expiresAt).toLocaleString() : '永久'}</div>
              <div className="flex gap-1">
                {item.state === 'active' && <Button isIconOnly size="sm" variant="light" aria-label="暂停代理" title="暂停代理" onPress={() => control(item, 'pause')}><Pause size={17} /></Button>}
                {item.state === 'paused' && <Button isIconOnly size="sm" variant="light" color="success" aria-label="恢复代理" title="恢复代理" onPress={() => control(item, 'resume')}><Play size={17} /></Button>}
                <Button isIconOnly size="sm" variant="light" color="danger" aria-label="删除代理" title="删除代理" onPress={() => control(item, 'delete')}><Trash2 size={17} /></Button>
              </div>
              {item.lastError && <div className="text-xs text-danger lg:col-span-6">{item.lastError}</div>}
            </div>;
          })}
        </div>
      )}

      <Modal isOpen={modalOpen} onOpenChange={setModalOpen} size="3xl" scrollBehavior="inside">
        <ModalContent><ModalHeader>新建私人代理</ModalHeader><ModalBody className="gap-4">
          <Tabs selectedKey={form.proxyType} onSelectionChange={key => setForm({ ...form, proxyType: String(key) as 'socks5' | 'http' })} fullWidth>
            <Tab key="socks5" title="SOCKS5" /><Tab key="http" title="HTTP" />
          </Tabs>
          <div className="grid gap-4 md:grid-cols-2">
            <Input label="代理名称" value={form.name} onValueChange={value => setForm({ ...form, name: value })} />
            <Select label="服务器节点" placeholder="选择在线节点" selectedKeys={form.nodeId ? [form.nodeId] : []} onSelectionChange={keys => setForm({ ...form, nodeId: String(Array.from(keys)[0] || '') })}>
              {nodes.filter(node => node.status === 1).map(node => <SelectItem key={String(node.id)} textValue={node.name}>{node.name} · {node.serverIp || node.ip || '未设置地址'}{node.quotaAvailable === false ? ` · ${node.unavailableReason}` : ''}</SelectItem>)}
            </Select>
            <Input label="监听端口" type="number" value={form.listenPort} onValueChange={value => setForm({ ...form, listenPort: value })} />
            <Input label="监听 IP（可选）" placeholder="留空监听全部地址" value={form.bindIp} onValueChange={value => setForm({ ...form, bindIp: value })} />
            <Input label="代理用户名" value={form.authUsername} onValueChange={value => setForm({ ...form, authUsername: value })} />
            <Input label="代理密码" type="password" value={form.authPassword} onValueChange={value => setForm({ ...form, authPassword: value })} />
          </div>
          <Textarea label="来源 IP 白名单（可选）" placeholder="203.0.113.10/32, 2001:db8::/64" value={form.allowedCidrs} onValueChange={value => setForm({ ...form, allowedCidrs: value })} minRows={2} />
          <div className="grid items-center gap-4 border-t border-divider pt-4 md:grid-cols-2">
            <Switch isSelected={form.permanent} onValueChange={value => setForm({ ...form, permanent: value })}>永久有效</Switch>
            {!form.permanent && <Input label="有效期（小时）" type="number" value={form.leaseHours} onValueChange={value => setForm({ ...form, leaseHours: value })} />}
          </div>
        </ModalBody><ModalFooter><Button variant="flat" onPress={() => setModalOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} startContent={!submitting && <Globe2 size={17} />} onPress={submit}>创建代理</Button></ModalFooter></ModalContent>
      </Modal>
    </div>
  );
}
