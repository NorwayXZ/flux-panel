import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import {
  CloudCog,
  Pencil,
  Plus,
  RefreshCw,
  ShieldCheck,
  Trash2,
  TriangleAlert,
} from "lucide-react";
import toast from "react-hot-toast";

import AccessResourceTabs from "@/components/access-resource-tabs";
import PageShell from "@/components/page-shell";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";
import {
  deleteAwsAccessAccount,
  getAwsAccessAccounts,
  saveAwsAccessAccount,
  syncAwsAccessAccount,
  type AwsAccessAccount,
  type AwsAccessOverview,
} from "@/api";
import { formatDateTime } from "@/utils/format";

const EMPTY: AwsAccessOverview = {
  accounts: [],
  summary: { accounts: 0, enabled: 0, errors: 0 },
};
const INITIAL_FORM = {
  id: undefined as number | undefined,
  name: "",
  accessKeyId: "",
  secretAccessKey: "",
  defaultRegion: "us-east-1",
  enabled: true,
};

const timeText = (value?: number) => formatDateTime(value, "尚未测试");

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
    else toast.error(response.msg || "读取 AWS 账号失败");
    setLoading(false);
  };

  useEffect(() => {
    void load();
  }, []);

  const openCreate = () => {
    setForm(INITIAL_FORM);
    setFormOpen(true);
  };

  const openEdit = (item: AwsAccessAccount) => {
    setForm({
      id: item.id,
      name: item.name,
      accessKeyId: item.accessKeyId,
      secretAccessKey: "",
      defaultRegion: item.defaultRegion || "us-east-1",
      enabled: Boolean(item.enabled),
    });
    setFormOpen(true);
  };

  const save = async () => {
    if (!form.name.trim() || !form.accessKeyId.trim())
      return toast.error("请填写名称和 Access Key ID");
    if (!form.id && !form.secretAccessKey.trim())
      return toast.error("首次添加需要填写 Secret Access Key");
    setSaving(true);
    const response = await saveAwsAccessAccount(form);

    setSaving(false);
    if (response.code !== 0)
      return toast.error(response.msg || "保存 AWS 账号失败");
    toast.success("AWS 账号已保存");
    setFormOpen(false);
    await load();
  };

  const sync = async (item: AwsAccessAccount) => {
    setSyncingId(item.id);
    const response = await syncAwsAccessAccount(item.id);

    setSyncingId(null);
    if (response.code !== 0)
      return toast.error(response.msg || "验证 AWS 凭据失败");
    toast.success(`已验证 ${response.data?.awsAccountId || "AWS 账号"}`);
    await load();
  };

  const remove = async (item: AwsAccessAccount) => {
    if (!window.confirm(`确认删除 AWS 账号“${item.name}”？`)) return;
    const response = await deleteAwsAccessAccount(item.id);

    if (response.code !== 0) return toast.error(response.msg || "删除失败");
    toast.success("AWS 账号已删除");
    await load();
  };

  return (
    <PageShell className="space-y-5" width="wide">
      <AccessResourceTabs />
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">资源中心</p>
          <h1 className="mt-1 text-2xl font-semibold">AWS 账号</h1>
        </div>
        <div className="flex gap-2">
          <Button
            isIconOnly
            aria-label="刷新 AWS 账号"
            variant="flat"
            onPress={load}
          >
            <RefreshCw size={17} />
          </Button>
          <Button
            color="primary"
            startContent={<Plus size={17} />}
            onPress={openCreate}
          >
            添加 AWS
          </Button>
        </div>
      </header>

      <section className="grid grid-cols-3 gap-3">
        <StatsCard
          color="primary"
          icon={<CloudCog size={20} />}
          title="账号总数"
          value={data.summary.accounts}
        />
        <StatsCard
          color="success"
          icon={<ShieldCheck size={20} />}
          title="已启用"
          value={data.summary.enabled}
        />
        <StatsCard
          color="danger"
          icon={<TriangleAlert size={20} />}
          title="异常"
          value={data.summary.errors}
        />
      </section>

      {loading ? (
        <div className="flex min-h-56 items-center justify-center">
          <Spinner />
        </div>
      ) : data.accounts.length === 0 ? (
        <div className="flex min-h-56 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-divider text-default-500">
          <CloudCog size={30} />
          <span>尚未添加 AWS 账号</span>
        </div>
      ) : (
        <ResourceCardGrid>
          {data.accounts.map((account) => (
            <ResourceCard
              key={account.id}
              actions={[
                {
                  label: "验证",
                  icon: <ShieldCheck size={14} />,
                  color: "primary",
                  isLoading: syncingId === account.id,
                  onPress: () => void sync(account),
                },
                {
                  label: "编辑",
                  icon: <Pencil size={14} />,
                  onPress: () => openEdit(account),
                },
                {
                  label: "删除",
                  icon: <Trash2 size={14} />,
                  color: "danger",
                  onPress: () => void remove(account),
                },
              ]}
              blocks={[
                {
                  label: "身份",
                  rows: [
                    { label: "Access Key", value: account.accessKeyId },
                    {
                      label: "Account",
                      value: account.awsAccountId || "尚未验证",
                    },
                    {
                      label: "ARN",
                      value: account.callerArn || "尚未验证",
                      title: account.callerArn || undefined,
                    },
                  ],
                },
              ]}
              danger={Boolean(account.lastError)}
              facts={[
                {
                  label: "默认区域",
                  value: account.defaultRegion || "us-east-1",
                },
                { label: "最近验证", value: timeText(account.lastTestAt) },
              ]}
              notice={
                account.lastError
                  ? {
                      icon: <TriangleAlert size={14} />,
                      label: "最近错误",
                      value: (
                        <span title={account.lastError}>
                          {account.lastError}
                        </span>
                      ),
                      tone: "danger",
                    }
                  : undefined
              }
              status={
                account.enabled
                  ? { text: "启用", color: "success" }
                  : { text: "停用", color: "default" }
              }
              title={account.name}
            />
          ))}
        </ResourceCardGrid>
      )}

      <Modal isOpen={formOpen} size="3xl" onOpenChange={setFormOpen}>
        <ModalContent>
          <ModalHeader>
            {form.id ? "编辑 AWS 账号" : "添加 AWS 账号"}
          </ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 md:grid-cols-2">
              <Input
                label="配置名称"
                value={form.name}
                onValueChange={(value) => setForm({ ...form, name: value })}
              />
              <Input
                label="默认区域"
                value={form.defaultRegion}
                onValueChange={(value) =>
                  setForm({ ...form, defaultRegion: value })
                }
              />
            </div>
            <Input
              label="Access Key ID"
              value={form.accessKeyId}
              onValueChange={(value) =>
                setForm({ ...form, accessKeyId: value })
              }
            />
            <Input
              label="Secret Access Key"
              placeholder={form.id ? "留空保持不变" : "首次添加必填"}
              type="password"
              value={form.secretAccessKey}
              onValueChange={(value) =>
                setForm({ ...form, secretAccessKey: value })
              }
            />
            <Switch
              isSelected={form.enabled}
              onValueChange={(value) => setForm({ ...form, enabled: value })}
            >
              启用此账号
            </Switch>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setFormOpen(false)}>
              取消
            </Button>
            <Button color="primary" isLoading={saving} onPress={save}>
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
