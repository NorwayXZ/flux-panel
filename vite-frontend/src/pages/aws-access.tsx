import { useEffect, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { CloudCog, Pencil, Plus, RefreshCw, ShieldCheck, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';

import AccessResourceTabs from '@/components/access-resource-tabs';
import {
  deleteAwsAccessAccount,
  getAwsAccessAccounts,
  saveAwsAccessAccount,
  syncAwsAccessAccount,
  type AwsAccessAccount,
  type AwsAccessOverview,
} from '@/api';

const EMPTY: AwsAccessOverview = { accounts: [], summary: { accounts: 0, enabled: 0, errors: 0 } };
const INITIAL_FORM = {
  id: undefined as number | undefined,
  name: '',
  accessKeyId: '',
  secretAccessKey: '',
  defaultRegion: 'us-east-1',
  enabled: true,
};

const timeText = (value?: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未测试';

export default function AwsAccessPage() {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState(EMPTY);
  const [formOpen, setFormOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [syncingId, setSyncingId] = useState<number | null>(null);
  const [form, setForm] = useState(INITIAL_FORM);

  const load = async () => {
    setLoading(true);
    const response = await getAwsAccessAccounts();
    if (response.code === 0) setData(response.data || EMPTY);
    else toast.error(response.msg || '读取 AWS 账号失败');
    setLoading(false);
  };

  useEffect(() => { void load(); }, []);

  const openCreate = () => {
    setForm(INITIAL_FORM);
    setFormOpen(true);
  };

  const openEdit = (item: AwsAccessAccount) => {
    setForm({
      id: item.id,
      name: item.name,
      accessKeyId: item.accessKeyId,
      secretAccessKey: '',
      defaultRegion: item.defaultRegion || 'us-east-1',
      enabled: Boolean(item.enabled),
    });
    setFormOpen(true);
  };

  const save = async () => {
    if (!form.name.trim() || !form.accessKeyId.trim()) return toast.error('请填写名称和 Access Key ID');
    if (!form.id && !form.secretAccessKey.trim()) return toast.error('首次添加需要填写 Secret Access Key');
    setSaving(true);
    const response = await saveAwsAccessAccount(form);
    setSaving(false);
    if (response.code !== 0) return toast.error(response.msg || '保存 AWS 账号失败');
    toast.success('AWS 账号已保存');
    setFormOpen(false);
    await load();
  };

  const sync = async (item: AwsAccessAccount) => {
    setSyncingId(item.id);
    const response = await syncAwsAccessAccount(item.id);
    setSyncingId(null);
    if (response.code !== 0) return toast.error(response.msg || '验证 AWS 凭据失败');
    toast.success(`已验证 ${response.data?.awsAccountId || 'AWS 账号'}`);
    await load();
  };

  const remove = async (item: AwsAccessAccount) => {
    if (!window.confirm(`确认删除 AWS 账号“${item.name}”？`)) return;
    const response = await deleteAwsAccessAccount(item.id);
    if (response.code !== 0) return toast.error(response.msg || '删除失败');
    toast.success('AWS 账号已删除');
    await load();
  };

  return (
    <div className="mx-auto w-full max-w-[1500px] space-y-5 p-4 md:p-6">
      <AccessResourceTabs />
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div><p className="text-sm text-default-500">资源中心</p><h1 className="mt-1 text-2xl font-semibold">AWS 账号</h1></div>
        <div className="flex gap-2">
          <Button isIconOnly variant="flat" aria-label="刷新 AWS 账号" onPress={load}><RefreshCw size={17} /></Button>
          <Button color="primary" startContent={<Plus size={17} />} onPress={openCreate}>添加 AWS</Button>
        </div>
      </header>

      <section className="grid grid-cols-3 gap-px overflow-hidden rounded-md border border-divider bg-divider">
        {[
          ['账号总数', data.summary.accounts],
          ['已启用', data.summary.enabled],
          ['异常', data.summary.errors],
        ].map(([label, value]) => (
          <div key={String(label)} className="bg-content1 px-4 py-4">
            <div className="text-xs text-default-500">{label}</div>
            <div className="mt-1 text-xl font-semibold">{value}</div>
          </div>
        ))}
      </section>

      {loading ? (
        <div className="flex min-h-56 items-center justify-center"><Spinner /></div>
      ) : data.accounts.length === 0 ? (
        <div className="flex min-h-56 flex-col items-center justify-center gap-3 border-y border-divider text-default-500">
          <CloudCog size={30} />
          <span>尚未添加 AWS 账号</span>
        </div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {data.accounts.map(account => (
            <article key={account.id} className="rounded-md border border-divider bg-content1 p-5">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <h2 className="truncate text-base font-semibold">{account.name}</h2>
                  <p className="mt-1 text-xs text-default-500">区域：{account.defaultRegion || 'us-east-1'} · Access Key：{account.accessKeyId}</p>
                </div>
                <Chip size="sm" variant="flat" color={account.enabled ? 'success' : 'default'}>
                  {account.enabled ? '启用' : '停用'}
                </Chip>
              </div>
              <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2">
                <div><dt className="text-default-500">AWS Account</dt><dd className="mt-1 font-medium">{account.awsAccountId || '尚未验证'}</dd></div>
                <div><dt className="text-default-500">Caller ARN</dt><dd className="mt-1 break-all font-mono text-xs">{account.callerArn || '尚未验证'}</dd></div>
                <div><dt className="text-default-500">最近验证</dt><dd className="mt-1 font-medium">{timeText(account.lastTestAt)}</dd></div>
                <div><dt className="text-default-500">状态</dt><dd className="mt-1 font-medium">{account.lastError ? <span className="text-danger">{account.lastError}</span> : '正常'}</dd></div>
              </dl>
              <div className="mt-4 flex flex-wrap items-center justify-end gap-2 border-t border-divider pt-4">
                <Button size="sm" variant="flat" startContent={<ShieldCheck size={15} />} isLoading={syncingId === account.id} onPress={() => sync(account)}>验证</Button>
                <Button size="sm" variant="flat" startContent={<Pencil size={15} />} onPress={() => openEdit(account)}>编辑</Button>
                <Button size="sm" color="danger" variant="flat" startContent={<Trash2 size={15} />} onPress={() => remove(account)}>删除</Button>
              </div>
            </article>
          ))}
        </div>
      )}

      <Modal isOpen={formOpen} onOpenChange={setFormOpen} size="3xl">
        <ModalContent>
          <ModalHeader>{form.id ? '编辑 AWS 账号' : '添加 AWS 账号'}</ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 md:grid-cols-2">
              <Input label="配置名称" value={form.name} onValueChange={value => setForm({ ...form, name: value })} />
              <Input label="默认区域" value={form.defaultRegion} onValueChange={value => setForm({ ...form, defaultRegion: value })} />
            </div>
            <Input label="Access Key ID" value={form.accessKeyId} onValueChange={value => setForm({ ...form, accessKeyId: value })} />
            <Input
              type="password"
              label="Secret Access Key"
              placeholder={form.id ? '留空保持不变' : '首次添加必填'}
              value={form.secretAccessKey}
              onValueChange={value => setForm({ ...form, secretAccessKey: value })}
            />
            <Switch isSelected={form.enabled} onValueChange={value => setForm({ ...form, enabled: value })}>启用此账号</Switch>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setFormOpen(false)}>取消</Button>
            <Button color="primary" isLoading={saving} onPress={save}>保存</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
