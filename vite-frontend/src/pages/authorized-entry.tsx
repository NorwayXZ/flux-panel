import { useCallback, useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Input, Textarea } from "@heroui/input";
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from "@heroui/modal";
import { Select, SelectItem } from "@heroui/select";
import { Pause, Pencil, Play, Plus, ShieldCheck, Trash2 } from "lucide-react";
import toast from "react-hot-toast";

import {
  createAuthorizedEntryPort,
  deleteAuthorizedEntryPort,
  getAllUsers,
  getAuthorizedEntryGrants,
  getAuthorizedEntryTemplates,
  getCrossEntryGroups,
  saveAuthorizedEntryGrant,
  saveAuthorizedEntryTemplate,
  revokeAuthorizedEntryGrant,
  setAuthorizedEntryGrantState,
  updateAuthorizedEntryPort,
  type AuthorizedEntryGrant,
  type AuthorizedEntryTemplate,
  type CrossEntryGroup,
} from "@/api";
import { isAdmin } from "@/utils/auth";

const formatBytes = (value = 0) => {
  if (value <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  const amount = value / 1024 ** index;
  return `${amount >= 100 ? amount.toFixed(0) : amount.toFixed(2)} ${units[index]}`;
};
const stateLabel: Record<string, string> = {
  active: "可用", provisioning: "部署中", quota_exhausted: "额度用尽", expired: "已到期", admin_paused: "管理员暂停", error: "需要修复",
};
const stateColor = (state: string) => state === "active" ? "success" : state === "provisioning" ? "warning" : "danger";
const initialTemplateForm = { name: "", sourceGroupId: "", startPort: "10000", endPort: "20000", protocolMode: "tcp", blockedTargetCidrs: "" };
const initialGrantForm = { name: "", templateId: "", userId: "", maxPorts: "1", flowLimitGiB: "0", flowDirection: "total", flowResetDay: "0", expiresAt: "" };
const formatDate = (value?: number) => value ? new Date(value).toLocaleString("zh-CN", { hour12: false, timeZone: "Asia/Shanghai" }) : "未设置";

export default function AuthorizedEntryPage() {
  const admin = isAdmin();
  const [grants, setGrants] = useState<AuthorizedEntryGrant[]>([]);
  const [templates, setTemplates] = useState<AuthorizedEntryTemplate[]>([]);
  const [groups, setGroups] = useState<CrossEntryGroup[]>([]);
  const [users, setUsers] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [templateOpen, setTemplateOpen] = useState(false);
  const [grantOpen, setGrantOpen] = useState(false);
  const [portGrant, setPortGrant] = useState<AuthorizedEntryGrant>();
  const [editingGrant, setEditingGrant] = useState<AuthorizedEntryGrant>();
  const [editingPort, setEditingPort] = useState<number>();
  const [submitting, setSubmitting] = useState(false);
  const [templateForm, setTemplateForm] = useState(initialTemplateForm);
  const [grantForm, setGrantForm] = useState(initialGrantForm);
  const [portForm, setPortForm] = useState({ targetHost: "", targetPort: "" });

  const load = useCallback(async () => {
    setLoading(true);
    const grantResult = await getAuthorizedEntryGrants();
    if (grantResult.code === 0) setGrants(grantResult.data || []);
    if (admin) {
      const [templateResult, groupResult, userResult] = await Promise.all([getAuthorizedEntryTemplates(), getCrossEntryGroups(), getAllUsers()]);
      if (templateResult.code === 0) setTemplates(templateResult.data || []);
      if (groupResult.code === 0) setGroups(groupResult.data?.groups || []);
      if (userResult.code === 0) setUsers(userResult.data?.list || userResult.data || []);
    }
    setLoading(false);
  }, [admin]);

  useEffect(() => { void load(); }, [load]);

  const saveTemplate = async () => {
    setSubmitting(true);
    const result = await saveAuthorizedEntryTemplate({ ...templateForm, sourceGroupId: Number(templateForm.sourceGroupId), startPort: Number(templateForm.startPort), endPort: Number(templateForm.endPort) });
    setSubmitting(false);
    if (result.code !== 0) return toast.error(result.msg || "保存入口模板失败");
    setTemplateOpen(false); setTemplateForm(initialTemplateForm);
    toast.success("入口模板已保存"); void load();
  };

  const saveGrant = async () => {
    setSubmitting(true);
    const result = await saveAuthorizedEntryGrant({ ...grantForm, id: editingGrant?.id, templateId: Number(grantForm.templateId), userId: Number(grantForm.userId), maxPorts: Number(grantForm.maxPorts), flowLimitGiB: Number(grantForm.flowLimitGiB), flowResetDay: Number(grantForm.flowResetDay), expiresAt: grantForm.expiresAt ? Date.parse(`${grantForm.expiresAt}:00+08:00`) : null });
    setSubmitting(false);
    if (result.code !== 0) return toast.error(result.msg || "保存入口授权失败");
    setGrantOpen(false); setEditingGrant(undefined); setGrantForm(initialGrantForm); toast.success("入口授权已保存"); void load();
  };

  const savePort = async () => {
    if (!portGrant) return;
    setSubmitting(true);
    const result = editingPort
      ? await updateAuthorizedEntryPort({ id: editingPort, targetHost: portForm.targetHost, targetPort: Number(portForm.targetPort) })
      : await createAuthorizedEntryPort({ grantId: portGrant.id, targetHost: portForm.targetHost, targetPort: Number(portForm.targetPort) });
    setSubmitting(false);
    if (result.code !== 0) return toast.error(result.msg || "创建授权端口失败", { duration: 9000 });
    setPortGrant(undefined); setEditingPort(undefined); setPortForm({ targetHost: "", targetPort: "" }); toast.success(editingPort ? "落地已更新" : "授权端口已创建"); void load();
  };

  const removePort = async (id: number) => {
    if (!window.confirm("确认删除这条授权端口和其隐藏转发吗？")) return;
    const result = await deleteAuthorizedEntryPort(id);
    if (result.code !== 0) return toast.error(result.msg || "删除授权端口失败");
    toast.success("授权端口已删除"); void load();
  };

  const openGrantEditor = (grant?: AuthorizedEntryGrant) => {
    setEditingGrant(grant);
    setGrantForm(grant ? {
      name: grant.name, templateId: String(grant.templateId || ""), userId: String(grant.userId || ""), maxPorts: String(grant.maxPorts),
      flowLimitGiB: String(Math.floor(grant.flowLimitBytes / 1024 ** 3)), flowDirection: grant.flowDirection,
      flowResetDay: String(grant.flowResetDay || 0), expiresAt: grant.expiresAt ? new Date(grant.expiresAt - new Date(grant.expiresAt).getTimezoneOffset() * 60_000).toISOString().slice(0, 16) : "",
    } : initialGrantForm);
    setGrantOpen(true);
  };

  const changeGrantState = async (grant: AuthorizedEntryGrant, active: boolean) => {
    const result = await setAuthorizedEntryGrantState(grant.id, active);
    if (result.code !== 0) return toast.error(result.msg || "更新授权状态失败");
    toast.success(active ? "授权已恢复" : "授权已暂停"); void load();
  };

  const revokeGrant = async (grant: AuthorizedEntryGrant) => {
    if (!window.confirm(`确认撤销“${grant.name}”并删除全部隐藏转发吗？`)) return;
    const result = await revokeAuthorizedEntryGrant(grant.id);
    if (result.code !== 0) return toast.error(result.msg || "撤销授权失败", { duration: 9000 });
    toast.success("授权已撤销"); void load();
  };

  return <div className="space-y-6">
    <header className="flex flex-wrap items-end justify-between gap-3 border-b border-divider pb-5">
      <div><p className="text-sm text-default-500">隐藏入口池授权给用户</p><h1 className="mt-1 text-2xl font-semibold">授权入口</h1></div>
      {admin && <div className="flex gap-2"><Button startContent={<Plus size={16} />} variant="flat" onPress={() => { setTemplateForm(initialTemplateForm); setTemplateOpen(true); }}>新建入口模板</Button><Button color="primary" startContent={<Plus size={16} />} onPress={() => openGrantEditor()}>发放授权</Button></div>}
    </header>
    {loading ? <p className="py-12 text-center text-default-500">正在加载授权链路…</p> : grants.length === 0 ? <div className="border-y border-divider py-16 text-center text-default-500">暂无授权入口</div> : <section className="grid gap-4 xl:grid-cols-2">{grants.map(grant => {
      const percent = grant.flowLimitBytes > 0 ? Math.min(100, grant.usedBytes / grant.flowLimitBytes * 100) : 0;
      return <Card key={grant.id} radius="sm" shadow="none" className="border border-divider"><CardHeader className="flex justify-between gap-3"><div className="min-w-0"><h2 className="truncate font-semibold">{grant.name}</h2><p className="mt-1 truncate text-sm text-default-500">{grant.accessHost}</p></div><div className="flex items-center gap-1"><Chip color={stateColor(grant.state) as any} size="sm" variant="flat">{stateLabel[grant.state] || grant.state}</Chip>{admin && <><Button isIconOnly aria-label="编辑授权" size="sm" variant="light" onPress={() => openGrantEditor(grant)}><Pencil size={15} /></Button><Button isIconOnly aria-label={grant.state === "active" ? "暂停授权" : "恢复授权"} size="sm" variant="light" onPress={() => void changeGrantState(grant, grant.state !== "active")}>{grant.state === "active" ? <Pause size={15} /> : <Play size={15} />}</Button><Button isIconOnly aria-label="撤销授权" color="danger" size="sm" variant="light" onPress={() => void revokeGrant(grant)}><Trash2 size={15} /></Button></>}</div></CardHeader><CardBody className="gap-4 pt-0">
        <div className="grid grid-cols-2 gap-3 text-sm"><div><p className="text-default-500">端口额度</p><p className="mt-1 font-medium">{grant.ports.length} / {grant.maxPorts}</p></div><div><p className="text-default-500">流量额度</p><p className="mt-1 font-medium">{grant.flowLimitBytes > 0 ? `${formatBytes(grant.usedBytes)} / ${formatBytes(grant.flowLimitBytes)}` : "不限量"}</p></div></div>
        <div className="grid grid-cols-2 gap-3 text-sm"><div><p className="text-default-500">每月重置</p><p className="mt-1 font-medium">{grant.flowResetDay ? `每月 ${grant.flowResetDay} 日` : "不重置"}</p></div><div><p className="text-default-500">到期时间</p><p className="mt-1 truncate font-medium" title={formatDate(grant.expiresAt)}>{formatDate(grant.expiresAt)}</p></div></div>
        {grant.flowLimitBytes > 0 && <div className="h-1.5 overflow-hidden bg-default-100"><div className="h-full bg-primary" style={{ width: `${percent}%` }} /></div>}
        <div className="space-y-2 border-t border-divider pt-3">{grant.ports.map(port => <div key={port.id} className="flex flex-wrap items-center justify-between gap-2 text-sm"><div><span className="font-mono font-medium">{grant.accessHost}:{port.port}</span><span className="text-default-500"> → {port.targetHost}:{port.targetPort}</span></div><div className="flex items-center gap-2"><Chip color={stateColor(port.state) as any} size="sm" variant="flat">{stateLabel[port.state] || port.state}</Chip><Button isIconOnly aria-label="编辑落地" size="sm" variant="light" onPress={() => { setPortGrant(grant); setEditingPort(port.id); setPortForm({ targetHost: port.targetHost, targetPort: String(port.targetPort) }); }}><Pencil size={15} /></Button><Button isIconOnly aria-label="删除授权端口" size="sm" variant="light" onPress={() => void removePort(port.id)}><Trash2 size={15} /></Button></div></div>)}</div>
        {grant.state === "active" && grant.ports.length < grant.maxPorts && <Button startContent={<Plus size={16} />} variant="flat" onPress={() => { setEditingPort(undefined); setPortForm({ targetHost: "", targetPort: "" }); setPortGrant(grant); }}>添加落地端口</Button>}
      </CardBody></Card>;
    })}</section>}
    <Modal isOpen={templateOpen} onOpenChange={setTemplateOpen}><ModalContent><ModalHeader>新建隐藏入口模板</ModalHeader><ModalBody><Input label="模板名称" value={templateForm.name} onValueChange={name => setTemplateForm({ ...templateForm, name })}/><Select label="入口容灾组" selectedKeys={templateForm.sourceGroupId ? [templateForm.sourceGroupId] : []} onSelectionChange={keys => setTemplateForm({ ...templateForm, sourceGroupId: String(Array.from(keys)[0] || "") })}>{groups.map(group => <SelectItem key={String(group.id)} textValue={`${group.name} · ${group.domain}`}>{group.name} · {group.domain}</SelectItem>)}</Select><div className="grid grid-cols-2 gap-3"><Input label="起始端口" type="number" value={templateForm.startPort} onValueChange={startPort => setTemplateForm({ ...templateForm, startPort })}/><Input label="结束端口" type="number" value={templateForm.endPort} onValueChange={endPort => setTemplateForm({ ...templateForm, endPort })}/></div><Select label="协议" selectedKeys={[templateForm.protocolMode]} onSelectionChange={keys => setTemplateForm({ ...templateForm, protocolMode: String(Array.from(keys)[0] || "tcp") })}><SelectItem key="tcp" textValue="TCP">TCP</SelectItem><SelectItem key="tcp_udp" textValue="TCP + UDP">TCP + UDP</SelectItem></Select><Textarea label="额外禁止落地网段" description="可填管理网段、VPC、Docker 或 Kubernetes 网段；每行一条 CIDR" minRows={3} value={templateForm.blockedTargetCidrs} onValueChange={blockedTargetCidrs => setTemplateForm({ ...templateForm, blockedTargetCidrs })}/></ModalBody><ModalFooter><Button variant="flat" onPress={() => setTemplateOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={() => void saveTemplate()}>保存</Button></ModalFooter></ModalContent></Modal>
    <Modal isOpen={grantOpen} onOpenChange={setGrantOpen}><ModalContent><ModalHeader>{editingGrant ? "编辑入口授权" : "发放入口授权"}</ModalHeader><ModalBody><Input label="授权名称" value={grantForm.name} onValueChange={name => setGrantForm({ ...grantForm, name })}/><Select isDisabled={Boolean(editingGrant && editingGrant.ports.length)} label="入口模板" selectedKeys={grantForm.templateId ? [grantForm.templateId] : []} onSelectionChange={keys => setGrantForm({ ...grantForm, templateId: String(Array.from(keys)[0] || "") })}>{templates.map(template => <SelectItem key={String(template.id)} textValue={`${template.name} · ${template.domain}`}>{template.name} · {template.domain}</SelectItem>)}</Select><Select isDisabled={Boolean(editingGrant && editingGrant.ports.length)} label="用户" selectedKeys={grantForm.userId ? [grantForm.userId] : []} onSelectionChange={keys => setGrantForm({ ...grantForm, userId: String(Array.from(keys)[0] || "") })}>{users.filter(user => user.roleId !== 0).map(user => <SelectItem key={String(user.id)} textValue={user.name || user.user}>{user.name || user.user}</SelectItem>)}</Select><div className="grid grid-cols-2 gap-3"><Input label="端口数量" type="number" value={grantForm.maxPorts} onValueChange={maxPorts => setGrantForm({ ...grantForm, maxPorts })}/><Input label="流量额度 GiB，0 不限" type="number" value={grantForm.flowLimitGiB} onValueChange={flowLimitGiB => setGrantForm({ ...grantForm, flowLimitGiB })}/></div><div className="grid grid-cols-2 gap-3"><Select label="计费方向" selectedKeys={[grantForm.flowDirection]} onSelectionChange={keys => setGrantForm({ ...grantForm, flowDirection: String(Array.from(keys)[0] || "total") })}><SelectItem key="total" textValue="双向合计">双向合计</SelectItem><SelectItem key="inbound" textValue="入口收到">入口收到</SelectItem><SelectItem key="outbound" textValue="返回客户端">返回客户端</SelectItem></Select><Input label="每月重置日，0 不重置" type="number" value={grantForm.flowResetDay} onValueChange={flowResetDay => setGrantForm({ ...grantForm, flowResetDay })}/></div><Input label="到期时间，可留空" type="datetime-local" value={grantForm.expiresAt} onValueChange={expiresAt => setGrantForm({ ...grantForm, expiresAt })}/></ModalBody><ModalFooter><Button variant="flat" onPress={() => setGrantOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={() => void saveGrant()}>保存</Button></ModalFooter></ModalContent></Modal>
    <Modal isOpen={Boolean(portGrant)} onOpenChange={open => !open && setPortGrant(undefined)}><ModalContent><ModalHeader>{portGrant?.name} · {editingPort ? "编辑落地" : "添加落地"}</ModalHeader><ModalBody><p className="text-sm text-default-500">仅允许公网数值 IP，内网、云元数据、平台管理地址和管理员设置的禁止网段会被拦截。</p><Input label="落地公网 IP" placeholder="填写你的公网 IP" value={portForm.targetHost} onValueChange={targetHost => setPortForm({ ...portForm, targetHost })}/><Input label="落地端口" type="number" value={portForm.targetPort} onValueChange={targetPort => setPortForm({ ...portForm, targetPort })}/></ModalBody><ModalFooter><Button variant="flat" onPress={() => { setPortGrant(undefined); setEditingPort(undefined); }}>取消</Button><Button color="primary" isLoading={submitting} onPress={() => void savePort()}><ShieldCheck size={16} /> {editingPort ? "保存" : "创建"}</Button></ModalFooter></ModalContent></Modal>
  </div>;
}
