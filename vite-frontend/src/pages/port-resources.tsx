import { useEffect, useState } from 'react';
import { Button } from '@heroui/button';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Boxes, Plus, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  createPublishingPortPool,
  deletePublishingPortPool,
  getNodeList,
  getPublishingPortPools,
  type PublishingPortPool,
} from '@/api';

interface NodeOption { id: number; name: string; ip?: string; serverIp?: string; status: number }

export default function PortResourcesPage() {
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [pools, setPools] = useState<PublishingPortPool[]>([]);
  const [nodes, setNodes] = useState<NodeOption[]>([]);
  const [form, setForm] = useState({
    name: '', nodeId: '', publicHost: '', bindIp: '', startPort: '20000', endPort: '20999', controlPort: '21000', defaultLeaseHours: '24', maxLeaseHours: '720', cooldownSeconds: '60',
  });

  const loadData = async () => {
    setLoading(true);
    const [poolRes, nodeRes] = await Promise.all([getPublishingPortPools(), getNodeList()]);
    if (poolRes.code === 0) setPools(poolRes.data || []); else toast.error(poolRes.msg || '加载端口池失败');
    if (nodeRes.code === 0) setNodes(nodeRes.data || []);
    setLoading(false);
  };
  useEffect(() => { loadData(); }, []);

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
      defaultLeaseHours: Number(form.defaultLeaseHours), maxLeaseHours: Number(form.maxLeaseHours), cooldownSeconds: Number(form.cooldownSeconds),
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
      <header className="flex items-end justify-between border-b border-divider pb-5">
        <div><p className="text-sm text-default-500">系统管理</p><h1 className="mt-1 text-2xl font-semibold">端口资源</h1></div>
        <Button color="primary" startContent={<Plus size={18} />} onPress={() => setModalOpen(true)}>新建端口池</Button>
      </header>
      {loading ? <div className="flex min-h-64 items-center justify-center"><Spinner /></div> : pools.length === 0 ? (
        <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><Boxes size={30} /><span>暂无端口池</span></div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-divider">
          <div className="hidden grid-cols-[1.2fr_1fr_1fr_1fr_1fr_auto] gap-4 bg-default-100 px-4 py-3 text-xs text-default-500 lg:grid">
            <span>端口池</span><span>公网节点</span><span>范围</span><span>使用情况</span><span>租期</span><span>操作</span>
          </div>
          {pools.map(pool => (
            <div key={pool.id} className="grid gap-3 border-t border-divider px-4 py-4 first:border-t-0 lg:grid-cols-[1.2fr_1fr_1fr_1fr_1fr_auto] lg:items-center">
              <div><div className="font-medium">{pool.name}</div><div className="text-xs text-default-500">{pool.publicHost}</div></div>
              <div className="text-sm">{pool.nodeName}</div>
              <div className="font-mono text-sm">{pool.startPort}-{pool.endPort}</div>
              <div><div className="text-sm">{pool.usedPorts} / {pool.totalPorts}</div><div className="mt-2 h-1.5 overflow-hidden rounded-full bg-default-200"><div className="h-full bg-primary" style={{ width: `${pool.totalPorts ? pool.usedPorts / pool.totalPorts * 100 : 0}%` }} /></div></div>
              <div className="text-sm"><div>默认 {pool.defaultLeaseHours} 小时</div><div className="text-xs text-default-500">最长 {pool.maxLeaseHours} 小时</div></div>
              <Button isIconOnly size="sm" variant="light" color="danger" aria-label="删除端口池" onPress={() => remove(pool.id)}><Trash2 size={17} /></Button>
            </div>
          ))}
        </div>
      )}

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
            <Input label="默认租期（小时）" type="number" value={form.defaultLeaseHours} onValueChange={value => setForm({ ...form, defaultLeaseHours: value })} />
            <Input label="最长租期（小时）" type="number" value={form.maxLeaseHours} onValueChange={value => setForm({ ...form, maxLeaseHours: value })} />
            <Input className="sm:col-span-2" label="释放冷却（秒）" type="number" value={form.cooldownSeconds} onValueChange={value => setForm({ ...form, cooldownSeconds: value })} />
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setModalOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={submit}>创建</Button></ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
