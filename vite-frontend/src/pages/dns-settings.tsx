import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Select, SelectItem } from '@heroui/select';
import { Spinner } from '@heroui/spinner';
import { Switch } from '@heroui/switch';
import { useSearchParams } from 'react-router-dom';
import { AlertTriangle, CheckCircle2, CloudCog, Globe2, KeyRound, Pencil, Plus, RefreshCw, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';

import {
  deleteDnsProviderAccount,
  deleteDynamicDnsProvider,
  getDynamicDnsOverview,
  getDnsProviderData,
  saveDynamicDnsProvider,
  saveDnsProviderAccount,
  syncDnsProviderAccount,
  type DnsManagedRecord,
  type DnsProviderAccount,
  type DnsProviderSummary,
  type DnsZone,
  type DynamicDnsProviderOption,
} from '@/api';

const emptySummary: DnsProviderSummary = { accounts: 0, zones: 0, records: 0, errors: 0 };
const emptyForm = { id: undefined as number | undefined, name: '', apiToken: '', enabled: true };
const emptyCarrierForm = { id: undefined as number | undefined, name: '', provider: 'dnspod' as 'dnspod' | 'aliyun', credentialA: '', credentialB: '', enabled: true };
const truthy = (value: boolean | number) => value === true || value === 1;
const timeText = (value?: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未同步';

export default function DnsSettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [loading, setLoading] = useState(true);
  const [accounts, setAccounts] = useState<DnsProviderAccount[]>([]);
  const [zones, setZones] = useState<DnsZone[]>([]);
  const [records, setRecords] = useState<DnsManagedRecord[]>([]);
  const [summary, setSummary] = useState<DnsProviderSummary>(emptySummary);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [syncingId, setSyncingId] = useState<number>();
  const [carrierProviders, setCarrierProviders] = useState<DynamicDnsProviderOption[]>([]);
  const [carrierFormOpen, setCarrierFormOpen] = useState(false);
  const [carrierForm, setCarrierForm] = useState(emptyCarrierForm);
  const [carrierSaving, setCarrierSaving] = useState(false);

  const loadData = useCallback(async () => {
    const [response, dynamicResponse] = await Promise.all([getDnsProviderData(), getDynamicDnsOverview()]);
    if (response.code === 0) {
      setAccounts(response.data?.accounts || []);
      setZones(response.data?.zones || []);
      setRecords(response.data?.records || []);
      setSummary(response.data?.summary || emptySummary);
    } else {
      toast.error(response.msg || '加载 DNS 配置失败');
    }
    if (dynamicResponse.code === 0) {
      setCarrierProviders((dynamicResponse.data?.providers || []).filter(item => item.source === 'dynamic' && (item.provider === 'dnspod' || item.provider === 'aliyun')));
    } else {
      toast.error(dynamicResponse.msg || '加载线路 DNS 配置失败');
    }
    setLoading(false);
  }, []);

  useEffect(() => { void loadData(); }, [loadData]);

  useEffect(() => {
    if (searchParams.get('add') !== 'carrier') return;
    setCarrierForm(emptyCarrierForm);
    setCarrierFormOpen(true);
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  const zonesByAccount = useMemo(() => {
    const result = new Map<number, DnsZone[]>();
    zones.forEach(zone => result.set(zone.accountId, [...(result.get(zone.accountId) || []), zone]));
    return result;
  }, [zones]);

  const openCreate = () => {
    setForm(emptyForm);
    setFormOpen(true);
  };

  const openEdit = (account: DnsProviderAccount) => {
    setForm({ id: account.id, name: account.name, apiToken: '', enabled: truthy(account.enabled) });
    setFormOpen(true);
  };

  const submit = async () => {
    if (!form.name.trim()) return toast.error('请输入配置名称');
    if (!form.id && !form.apiToken.trim()) return toast.error('首次添加需要填写 Cloudflare API Token');
    setSaving(true);
    const response = await saveDnsProviderAccount(form);
    setSaving(false);
    if (response.code !== 0) return toast.error(response.msg || '保存 Cloudflare 配置失败');
    toast.success(`Cloudflare 已连接，同步 ${response.data?.zoneCount || 0} 个 Zone`);
    setFormOpen(false);
    void loadData();
  };

  const sync = async (account: DnsProviderAccount) => {
    setSyncingId(account.id);
    const response = await syncDnsProviderAccount(account.id);
    setSyncingId(undefined);
    if (response.code !== 0) return toast.error(response.msg || '同步 Zone 失败');
    toast.success(`已同步 ${response.data?.zoneCount || 0} 个 Zone`);
    void loadData();
  };

  const remove = async (account: DnsProviderAccount) => {
    if (!window.confirm(`确认删除“${account.name}”吗？Cloudflare 中的 DNS 记录不会被删除。`)) return;
    const response = await deleteDnsProviderAccount(account.id);
    if (response.code !== 0) return toast.error(response.msg || '删除失败');
    toast.success('Cloudflare 配置已删除');
    void loadData();
  };

  const openCarrierCreate = () => {
    setCarrierForm(emptyCarrierForm);
    setCarrierFormOpen(true);
  };

  const openCarrierEdit = (provider: DynamicDnsProviderOption) => {
    setCarrierForm({
      id: provider.id,
      name: provider.name,
      provider: provider.provider as 'dnspod' | 'aliyun',
      credentialA: '',
      credentialB: '',
      enabled: truthy(provider.enabled),
    });
    setCarrierFormOpen(true);
  };

  const submitCarrier = async () => {
    if (!carrierForm.name.trim()) return toast.error('请输入配置名称');
    if (!carrierForm.id && (!carrierForm.credentialA.trim() || !carrierForm.credentialB.trim())) return toast.error('首次添加需要填写完整的密钥对');
    setCarrierSaving(true);
    const response = await saveDynamicDnsProvider(carrierForm);
    setCarrierSaving(false);
    if (response.code !== 0) return toast.error(response.msg || '保存线路 DNS 配置失败');
    toast.success(`${carrierForm.provider === 'dnspod' ? 'DNSPod' : '阿里云 DNS'} 配置已保存`);
    setCarrierFormOpen(false);
    void loadData();
  };

  const removeCarrier = async (provider: DynamicDnsProviderOption) => {
    if (!window.confirm(`确认删除“${provider.name}”吗？正在被三网优化或动态 DNS 使用的配置不会被删除。`)) return;
    const response = await deleteDynamicDnsProvider(provider.id);
    if (response.code !== 0) return toast.error(response.msg || '删除失败');
    toast.success('线路 DNS 配置已删除');
    void loadData();
  };

  if (loading) return <div className="flex min-h-[50vh] items-center justify-center"><Spinner label="加载 DNS 与域名" /></div>;

  return (
    <div className="mx-auto w-full max-w-[1500px] space-y-6 p-4 sm:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div><p className="text-sm text-default-500">域名基础设施</p><h1 className="mt-1 text-2xl font-semibold">DNS 与域名</h1></div>
        <div className="flex flex-wrap gap-2"><Button variant="flat" startContent={<Plus size={18} />} onPress={openCreate}>添加 Cloudflare</Button><Button color="primary" startContent={<Plus size={18} />} onPress={openCarrierCreate}>添加线路 DNS</Button></div>
      </header>

      <section className="grid grid-cols-2 border-y border-divider sm:grid-cols-4" aria-label="DNS 概况">
        {[
          ['DNS 服务商', summary.accounts + carrierProviders.length, <CloudCog key="accounts" className="h-5 w-5 text-primary" />],
          ['可用 Zone', summary.zones, <Globe2 key="zones" className="h-5 w-5 text-success" />],
          ['受管记录', summary.records, <CheckCircle2 key="records" className="h-5 w-5 text-secondary" />],
          ['同步异常', summary.errors, <AlertTriangle key="errors" className={`h-5 w-5 ${summary.errors ? 'text-danger' : 'text-default-400'}`} />],
        ].map(([label, value, icon], index) => (
          <div key={String(label)} className={`flex min-h-24 items-center justify-between px-4 py-4 sm:px-6 ${index % 2 ? '' : 'border-r border-divider'} ${index === 1 ? 'sm:border-r' : ''}`}>
            <div><p className="text-xs text-default-500">{label}</p><p className="mt-1 text-2xl font-semibold">{value}</p></div>{icon}
          </div>
        ))}
      </section>

      <section>
        <div className="mb-3 flex items-end justify-between"><div><h2 className="text-base font-semibold">Cloudflare 托管</h2><p className="mt-1 text-xs text-default-500">Zone、HTTPS 与入口容灾</p></div><Chip size="sm" variant="flat">Cloudflare</Chip></div>
        {accounts.length === 0 ? (
          <div className="flex min-h-48 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><KeyRound className="h-8 w-8" /><span className="text-sm">尚未添加 Cloudflare 配置</span></div>
        ) : (
          <div className="divide-y divide-divider border-y border-divider">
            {accounts.map(account => (
              <div key={account.id} className="grid gap-4 py-4 lg:grid-cols-[minmax(220px,0.7fr)_minmax(0,1.6fr)_auto] lg:items-center">
                <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h3 className="truncate font-medium">{account.name}</h3><Chip size="sm" variant="flat" color={truthy(account.enabled) ? 'success' : 'default'}>{truthy(account.enabled) ? '启用' : '停用'}</Chip></div><p className="mt-1 text-xs text-default-500">最近同步：{timeText(account.lastSyncAt)}</p></div>
                <div className="flex min-w-0 flex-wrap gap-2">
                  {(zonesByAccount.get(account.id) || []).filter(zone => zone.status === 'active').map(zone => <Chip key={zone.id} size="sm" variant="flat" color="primary">{zone.zoneName}</Chip>)}
                  {(zonesByAccount.get(account.id) || []).filter(zone => zone.status === 'active').length === 0 && <span className="text-xs text-default-500">没有可用 Zone</span>}
                </div>
                <div className="flex items-center justify-end gap-1">
                  <Button isIconOnly size="sm" variant="light" title="同步 Zone" aria-label="同步 Zone" isLoading={syncingId === account.id} onPress={() => sync(account)}><RefreshCw size={17} /></Button>
                  <Button isIconOnly size="sm" variant="light" title="编辑配置" aria-label="编辑配置" onPress={() => openEdit(account)}><Pencil size={17} /></Button>
                  <Button isIconOnly size="sm" variant="light" color="danger" title="删除配置" aria-label="删除配置" onPress={() => remove(account)}><Trash2 size={17} /></Button>
                </div>
                {account.lastError && <p className="lg:col-span-3 bg-danger-50 px-3 py-2 text-xs text-danger dark:bg-danger-500/10">{account.lastError}</p>}
              </div>
            ))}
          </div>
        )}
      </section>

      <section>
        <div className="mb-3 flex items-end justify-between"><div><h2 className="text-base font-semibold">运营商线路 DNS</h2><p className="mt-1 text-xs text-default-500">三网优化与动态 DNS</p></div><div className="flex gap-2"><Chip size="sm" variant="flat">DNSPod</Chip><Chip size="sm" variant="flat">阿里云 DNS</Chip></div></div>
        {carrierProviders.length === 0 ? (
          <div className="flex min-h-40 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><Globe2 className="h-8 w-8" /><span className="text-sm">尚未添加线路 DNS 配置</span><Button size="sm" color="primary" variant="flat" startContent={<Plus size={15} />} onPress={openCarrierCreate}>添加配置</Button></div>
        ) : (
          <div className="divide-y divide-divider border-y border-divider">
            {carrierProviders.map(provider => (
              <div key={provider.id} className="flex flex-col gap-3 py-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h3 className="truncate font-medium">{provider.name}</h3><Chip size="sm" variant="flat" color="primary">{provider.provider === 'dnspod' ? 'DNSPod' : '阿里云 DNS'}</Chip><Chip size="sm" variant="flat" color={truthy(provider.enabled) ? 'success' : 'default'}>{truthy(provider.enabled) ? '启用' : '停用'}</Chip></div><p className="mt-1 text-xs text-default-500">密钥已加密保存，页面不会回显</p>{provider.lastError && <p className="mt-1 text-xs text-danger">{provider.lastError}</p>}</div>
                <div className="flex justify-end gap-1"><Button isIconOnly size="sm" variant="light" title="编辑配置" aria-label="编辑线路 DNS 配置" onPress={() => openCarrierEdit(provider)}><Pencil size={17} /></Button><Button isIconOnly size="sm" variant="light" color="danger" title="删除配置" aria-label="删除线路 DNS 配置" onPress={() => removeCarrier(provider)}><Trash2 size={17} /></Button></div>
              </div>
            ))}
          </div>
        )}
      </section>

      <section>
        <div className="mb-3"><h2 className="text-base font-semibold">面板受管记录</h2><p className="mt-1 text-xs text-default-500">由入口容灾自动创建或接管的 DNS-only 记录</p></div>
        {records.length === 0 ? (
          <div className="flex min-h-40 items-center justify-center border-y border-divider text-sm text-default-500">暂无受管 DNS 记录</div>
        ) : (
          <div className="overflow-x-auto border-y border-divider">
            <div className="grid min-w-[820px] grid-cols-[minmax(220px,1.5fr)_90px_minmax(160px,1fr)_100px_minmax(180px,1fr)_150px] gap-4 border-b border-divider bg-default-100/60 px-4 py-3 text-xs font-medium text-default-500">
              <span>域名</span><span>类型</span><span>当前入口</span><span>TTL</span><span>用途</span><span>更新时间</span>
            </div>
            {records.map(record => (
              <div key={record.id} className="grid min-w-[820px] grid-cols-[minmax(220px,1.5fr)_90px_minmax(160px,1fr)_100px_minmax(180px,1fr)_150px] items-center gap-4 border-b border-divider px-4 py-3 text-sm last:border-0">
                <span className="truncate font-medium">{record.fqdn}</span><Chip size="sm" variant="flat">{record.recordType}</Chip><span className="truncate font-mono text-xs">{record.content}</span><span>{record.ttl} 秒</span><span className="truncate">{record.ownerName ? `入口容灾 · ${record.ownerName}` : '未绑定'}</span><span className="text-xs text-default-500">{timeText(record.updatedTime)}</span>
              </div>
            ))}
          </div>
        )}
      </section>

      <Modal isOpen={formOpen} onOpenChange={setFormOpen} size="lg">
        <ModalContent>
          <ModalHeader>{form.id ? '编辑 Cloudflare 配置' : '添加 Cloudflare 配置'}</ModalHeader>
          <ModalBody className="gap-4">
            <Input name="cloudflare-config-name" autoComplete="off" label="配置名称" placeholder="例如：主域名账号" value={form.name} onValueChange={name => setForm({ ...form, name })} />
            <Input name="cloudflare-api-token" type="password" autoComplete="new-password" data-1p-ignore="true" data-lpignore="true" label={form.id ? 'Cloudflare API Token（留空保持不变）' : 'Cloudflare API Token'} value={form.apiToken} onValueChange={apiToken => setForm({ ...form, apiToken })} />
            <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">Token 需要 Zone Read 与 DNS Edit 权限。保存时会验证 Token，并自动读取其授权范围内的 Zone。</div>
            <Switch isSelected={form.enabled} onValueChange={enabled => setForm({ ...form, enabled })}>启用该配置</Switch>
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setFormOpen(false)}>取消</Button><Button color="primary" isLoading={saving} onPress={submit}>验证并同步 Zone</Button></ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={carrierFormOpen} onOpenChange={setCarrierFormOpen} size="lg">
        <ModalContent>
          <ModalHeader>{carrierForm.id ? '编辑线路 DNS 配置' : '添加线路 DNS 配置'}</ModalHeader>
          <ModalBody className="gap-4">
            <div className="grid gap-4 sm:grid-cols-2"><Input name="carrier-dns-config-name" autoComplete="off" label="配置名称" placeholder="例如：主域名线路解析" value={carrierForm.name} onValueChange={name => setCarrierForm({ ...carrierForm, name })} /><Select label="DNS 服务商" selectedKeys={[carrierForm.provider]} onSelectionChange={keys => setCarrierForm({ ...carrierForm, provider: String(Array.from(keys)[0] || 'dnspod') as 'dnspod' | 'aliyun' })}><SelectItem key="dnspod">DNSPod</SelectItem><SelectItem key="aliyun">阿里云 DNS</SelectItem></Select></div>
            <Input name="carrier-dns-credential-id" type="password" autoComplete="new-password" data-1p-ignore="true" data-lpignore="true" label={carrierForm.provider === 'dnspod' ? '腾讯云 SecretId' : '阿里云 AccessKey ID'} placeholder={carrierForm.id ? '已保存，留空保持不变' : ''} value={carrierForm.credentialA} onValueChange={credentialA => setCarrierForm({ ...carrierForm, credentialA })} />
            <Input name="carrier-dns-credential-secret" type="password" autoComplete="new-password" data-1p-ignore="true" data-lpignore="true" label={carrierForm.provider === 'dnspod' ? '腾讯云 SecretKey' : '阿里云 AccessKey Secret'} placeholder={carrierForm.id ? '已保存，留空保持不变' : ''} value={carrierForm.credentialB} onValueChange={credentialB => setCarrierForm({ ...carrierForm, credentialB })} />
            <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">密钥需要对应域名的 DNS 记录读取和修改权限，保存后仅以加密形式存储。</div>
            <Switch isSelected={carrierForm.enabled} onValueChange={enabled => setCarrierForm({ ...carrierForm, enabled })}>启用该配置</Switch>
          </ModalBody>
          <ModalFooter><Button variant="flat" onPress={() => setCarrierFormOpen(false)}>取消</Button><Button color="primary" isLoading={carrierSaving} onPress={submitCarrier}>保存配置</Button></ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
