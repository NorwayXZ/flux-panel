import { useCallback, useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Switch } from "@heroui/switch";
import { Tab, Tabs } from "@heroui/tabs";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import {
  Bot,
  Clock,
  Play,
  Plus,
  RefreshCw,
  Settings,
  History,
} from "lucide-react";
import toast from "react-hot-toast";
import {
  opsOverview,
  opsSettings,
  opsSave,
  opsRun,
  opsDelete,
  opsDetail,
  type OpsOverview,
  type OpsConfig,
  type OpsTask,
  type OpsRun,
} from "@/api/ai-ops";

import AiOpsChat from "@/components/ai-ops-chat";

const defaults = (): OpsConfig => ({
  name: "",
  prompt: "",
  nodeIds: [],
  cron: "0 0 3 * * *",
  timezone: "Asia/Shanghai",
  mode: "inspect",
  notify: false,
  allowLogCleanup: false,
  retentionDays: 7,
  maxJournalMb: 500,
  serviceAllowlist: [],
});
const statusText: Record<string, string> = {
  blocked: "模型未配置",
  running: "执行中",
  success: "已完成",
  partial: "部分失败",
  failed: "失败",
  interrupted: "执行中断",
};
const time = (ts?: number) => (ts ? new Date(ts).toLocaleString() : "—");
const field =
  "w-full rounded-lg border border-default-200 bg-content1 p-3 text-sm";
function RunDetail({ run }: { run: OpsRun }) {
  let entries: any[] = [];
  try {
    entries = JSON.parse(run.detail || "[]");
  } catch {
    /* Older records may contain plain text. */
  }
  return (
    <details className="rounded-xl border border-default-200 p-4">
      <summary className="cursor-pointer text-sm">
        {time(run.startedAt)} · {statusText[run.status] || run.status} ·{" "}
        {run.finishedAt ? time(run.finishedAt) : "等待完成"}
      </summary>
      <div className="mt-4 space-y-3">
        {entries.map((entry, i) => (
          <div key={i} className="rounded-lg bg-default-50 p-3 text-sm">
            <div className="mb-1 font-medium">
              {entry.call
                ? `工具：${entry.call.function?.name}`
                : entry.role === "assistant"
                  ? "AI 分析"
                  : "执行信息"}
            </div>
            <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-all text-xs">
              {JSON.stringify(entry, null, 2)}
            </pre>
          </div>
        ))}
      </div>
    </details>
  );
}
export default function AiOpsPage() {
  const [data, setData] = useState<OpsOverview>();
  const [tab, setTab] = useState("chat");
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);
  const [taskId, setTaskId] = useState<number>();
  const [serviceText, setServiceText] = useState("");
  const [config, setConfig] = useState<OpsConfig>(defaults);
  const [enabled, setEnabled] = useState(false);
  const [historyId, setHistoryId] = useState<number>();
  const [runs, setRuns] = useState<OpsRun[]>([]);
  const [model, setModel] = useState({ base_url: "", model: "", api_key: "" });
  const refresh = useCallback(async () => {
    try {
      const r = await opsOverview();
      if (r.code === 0) setData(r.data);
      else toast.error(r.msg);
    } catch {
      toast.error("加载运维任务失败");
    }
  }, []);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  useEffect(() => {
    if (data?.settings)
      setModel((m) => ({
        ...m,
        base_url: data.settings.baseUrl,
        model: data.settings.model,
      }));
  }, [data?.settings.baseUrl, data?.settings.model]);
  useEffect(() => {
    const timer = window.setInterval(() => {
      void refresh();
      if (historyId)
        void opsDetail(historyId).then((r) => {
          if (r.code === 0) setRuns(r.data.runs);
        });
    }, 10000);
    return () => window.clearInterval(timer);
  }, [refresh, historyId]);
  const action = async (
    fn: () => Promise<{ code: number; msg: string }>,
    success: string,
  ) => {
    setBusy(true);
    try {
      const r = await fn();
      if (r.code === 0) {
        toast.success(success);
        await refresh();
        return true;
      }
      toast.error(r.msg);
    } catch {
      toast.error("请求失败，请稍后重试");
    } finally {
      setBusy(false);
    }
    return false;
  };
  const edit = (task?: OpsTask) => {
    setTaskId(task?.id);
    setServiceText(task?.config.serviceAllowlist.join(", ") || "");
    setConfig(task ? { ...task.config } : defaults());
    setEnabled(task?.enabled || false);
    setEditing(true);
  };
  const showHistory = async (id: number) => {
    setHistoryId(id);
    setRuns([]);
    setTab("history");
    try {
      const r = await opsDetail(id);
      if (r.code === 0) setRuns(r.data.runs);
      else toast.error(r.msg);
    } catch {
      toast.error("加载运行记录失败");
    }
  };
  const configured = !!data?.settings.keyConfigured;
  return (
    <div className="mx-auto max-w-7xl space-y-6 p-2 sm:p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold">
            <Bot className="text-primary" />
            AI 运维
          </h1>
          <p className="mt-2 text-sm text-default-500">
            按你的要求定时检查节点，在授权范围内处理问题，并保留执行记录。
          </p>
        </div>
        <Button
          isIconOnly
          aria-label="刷新"
          variant="flat"
          onPress={() => void refresh()}
        >
          <RefreshCw size={18} />
        </Button>
      </div>
      {!configured && (
        <div className="rounded-xl border border-warning-200 bg-warning-50 p-4 text-sm text-warning-800">
          AI 模型尚未配置。可以先保存任务，配置模型后再执行或启用定时任务。
        </div>
      )}
      <div className="rounded-xl bg-default-50 p-4 text-sm text-default-600">
        支持 Linux 节点的磁盘、inode、日志占用检查；可按权限清理 systemd
        journal、重启指定服务，也可单独授权清理已轮转的旧日志。节点需升级至{" "}
        {data?.minimumAgentVersion || "2.84.0-beta.3"}
        。检查结果会发送给你配置的模型服务。
      </div>
      <Tabs
        destroyInactiveTabPanel={false}
        selectedKey={tab}
        onSelectionChange={(k) => setTab(String(k))}
        aria-label="AI运维页面"
      >
        <Tab
          key="chat"
          title={
            <span className="flex items-center gap-2">
              <Bot size={16} />
              实时对话
            </span>
          }
        >
          <AiOpsChat overview={data} onSettings={() => setTab("settings")} />
        </Tab>
        <Tab
          key="tasks"
          title={
            <span className="flex items-center gap-2">
              <Clock size={16} />
              定时任务
            </span>
          }
        >
          <div className="space-y-4 pt-4">
            <div className="flex justify-end">
              <Button
                color="primary"
                startContent={<Plus size={18} />}
                onPress={() => edit()}
              >
                新建任务
              </Button>
            </div>
            {!data?.tasks.length && (
              <div className="rounded-2xl border border-dashed border-default-300 p-12 text-center text-default-500">
                还没有任务。你可以先创建“每天检查磁盘使用率”等任务，并设置节点与执行权限。
              </div>
            )}
            <div className="grid gap-4 md:grid-cols-2">
              {data?.tasks.map((task) => (
                <div
                  key={task.id}
                  className="space-y-4 rounded-2xl border border-default-200 bg-content1 p-5 shadow-sm"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <h2 className="text-lg font-semibold">
                        {task.config.name}
                      </h2>
                      <p className="mt-1 text-xs text-default-500">
                        {task.config.mode === "repair"
                          ? "允许维护"
                          : "只读检查"}{" "}
                        · {task.config.nodeIds.length} 个节点 ·{" "}
                        {statusText[task.lastStatus || ""] || "尚未运行"}
                      </p>
                    </div>
                    <Switch
                      aria-label={`启用${task.config.name}`}
                      isSelected={task.enabled}
                      isDisabled={task.running || busy || !configured}
                      onValueChange={(value) =>
                        void action(
                          () =>
                            opsSave({
                              id: task.id,
                              config: task.config,
                              enabled: value,
                            }),
                          value ? "已启用" : "已停用",
                        )
                      }
                    />
                  </div>
                  <p className="line-clamp-3 whitespace-pre-wrap text-sm text-default-600">
                    {task.config.prompt}
                  </p>
                  <div className="rounded-lg bg-default-50 p-3 text-xs leading-6">
                    <div>
                      Cron：{task.config.cron} · {task.config.timezone}
                    </div>
                    <div>
                      下次执行：
                      {task.enabled ? time(task.nextRun) : "定时任务未启用"}
                    </div>
                    <div>上次执行：{time(task.lastRunAt)}</div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      size="sm"
                      color="primary"
                      variant="flat"
                      isLoading={task.running}
                      isDisabled={!configured || busy}
                      startContent={!task.running && <Play size={14} />}
                      onPress={() =>
                        void action(() => opsRun(task.id), "任务已开始")
                      }
                    >
                      立即执行
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      onPress={() => void showHistory(task.id)}
                    >
                      运行记录
                    </Button>
                    <Button
                      size="sm"
                      variant="light"
                      isDisabled={task.running}
                      onPress={() => edit(task)}
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      color="danger"
                      variant="light"
                      isDisabled={task.running || busy}
                      onPress={() => {
                        if (
                          window.confirm(
                            `删除任务“${task.config.name}”？运行记录仍会保留。`,
                          )
                        )
                          void action(() => opsDelete(task.id), "任务已删除");
                      }}
                    >
                      删除
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </Tab>
        <Tab
          key="history"
          title={
            <span className="flex items-center gap-2">
              <History size={16} />
              运行记录
            </span>
          }
        >
          <div className="space-y-4 pt-4">
            <select
              aria-label="选择任务"
              className={field}
              value={historyId || ""}
              onChange={(e) => void showHistory(Number(e.target.value))}
            >
              <option value="" disabled>
                选择任务查看记录
              </option>
              {data?.tasks.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.config.name}
                </option>
              ))}
            </select>
            {runs.map((run) => (
              <RunDetail key={run.id} run={run} />
            ))}
            {historyId && !runs.length && (
              <p className="p-8 text-center text-default-500">暂无运行记录</p>
            )}
          </div>
        </Tab>
        <Tab
          key="settings"
          title={
            <span className="flex items-center gap-2">
              <Settings size={16} />
              模型配置
            </span>
          }
        >
          <div className="mt-4 max-w-2xl space-y-5 rounded-2xl border border-default-200 p-5">
            <p className="text-sm text-default-500">
              支持兼容 OpenAI Chat Completions
              且支持工具调用的接口。密钥加密保存，留空保留现有密钥。
            </p>
            <Input
              label="API Base URL"
              placeholder="https://your-provider.example/v1"
              value={model.base_url}
              onValueChange={(v) => setModel({ ...model, base_url: v })}
            />
            <Input
              label="模型名称"
              placeholder="填写供应商提供的模型名称"
              value={model.model}
              onValueChange={(v) => setModel({ ...model, model: v })}
            />
            <Input
              label="API Key"
              type="password"
              autoComplete="new-password"
              placeholder={configured ? "已配置，留空保留" : "请输入 API Key"}
              value={model.api_key}
              onValueChange={(v) => setModel({ ...model, api_key: v })}
            />
            <Button
              color="primary"
              isLoading={busy}
              onPress={() =>
                void action(() => opsSettings(model), "模型配置已保存").then(
                  (ok) => {
                    if (ok) setModel((m) => ({ ...m, api_key: "" }));
                  },
                )
              }
            >
              保存配置
            </Button>
          </div>
        </Tab>
      </Tabs>
      <Modal
        isOpen={editing}
        onOpenChange={setEditing}
        size="3xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          <ModalHeader>{taskId ? "编辑任务" : "新建任务"}</ModalHeader>
          <ModalBody>
            <div className="space-y-4">
              <Input
                label="任务名称"
                value={config.name}
                onValueChange={(v) => setConfig({ ...config, name: v })}
              />
              <label className="block text-sm">
                任务要求
                <textarea
                  className={`${field} mt-2 min-h-32`}
                  placeholder="每天检查磁盘；使用率超过85%时清理超过7天的 journal，完成后再次检查并汇报。"
                  value={config.prompt}
                  onChange={(e) =>
                    setConfig({ ...config, prompt: e.target.value })
                  }
                />
              </label>
              <fieldset className="rounded-lg border border-default-200 p-3">
                <legend className="px-1 text-sm">执行节点</legend>
                <div className="grid max-h-48 gap-2 overflow-auto sm:grid-cols-2">
                  {data?.nodes.map((node) => (
                    <label
                      key={node.id}
                      className="flex items-center gap-2 text-sm"
                    >
                      <input
                        type="checkbox"
                        checked={config.nodeIds.includes(node.id)}
                        onChange={(e) =>
                          setConfig({
                            ...config,
                            nodeIds: e.target.checked
                              ? [...config.nodeIds, node.id]
                              : config.nodeIds.filter((id) => id !== node.id),
                          })
                        }
                      />
                      {node.name}
                      <span className="text-xs text-default-400">
                        {node.version}
                      </span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className="grid gap-3 sm:grid-cols-2">
                <Input
                  label="Cron（六段，包含秒）"
                  description="0 0 3 * * * = 每天03:00"
                  value={config.cron}
                  onValueChange={(v) => setConfig({ ...config, cron: v })}
                />
                <Input
                  label="时区"
                  value={config.timezone}
                  onValueChange={(v) => setConfig({ ...config, timezone: v })}
                />
              </div>
              <label className="block text-sm">
                执行权限
                <select
                  className={`${field} mt-2`}
                  value={config.mode}
                  onChange={(e) =>
                    setConfig({
                      ...config,
                      mode: e.target.value as OpsConfig["mode"],
                    })
                  }
                >
                  <option value="inspect">只读检查</option>
                  <option value="repair">
                    允许清理 journal 与重启白名单服务
                  </option>
                </select>
              </label>
              <div className="grid gap-3 sm:grid-cols-2">
                <Input
                  label="Journal 保留天数"
                  type="number"
                  min="1"
                  max="365"
                  value={String(config.retentionDays)}
                  onValueChange={(v) =>
                    setConfig({ ...config, retentionDays: Number(v) })
                  }
                />
                <Input
                  label="Journal 容量上限（MB）"
                  type="number"
                  min="100"
                  max="102400"
                  value={String(config.maxJournalMb)}
                  onValueChange={(v) =>
                    setConfig({ ...config, maxJournalMb: Number(v) })
                  }
                />
              </div>
              <Input
                label="服务白名单（逗号分隔）"
                description="仅允许检查或重启此处指定的服务，如 nginx.service"
                value={serviceText}
                onValueChange={setServiceText}
              />
              <Switch
                isSelected={!!config.allowLogCleanup}
                isDisabled={config.mode !== "repair"}
                onValueChange={(value) =>
                  setConfig({ ...config, allowLogCleanup: value })
                }
              >
                允许清理 /var/log 下已轮转的旧日志
              </Switch>
              <p className="text-xs text-default-500">
                保留期限使用上方天数；每次最多删除500个文件或1GiB。跳过当前日志和符号链接。
              </p>
              <Switch
                isSelected={!!config.notify}
                onValueChange={(value) =>
                  setConfig({ ...config, notify: value })
                }
              >
                异常或修复时发送 Telegram 通知
              </Switch>
              <p className="text-xs text-default-500">
                使用系统通知设置中已配置的
                Telegram。正常检查保持安静，连续相同状态的问题仅首次通知。
              </p>
              <Switch
                isSelected={enabled}
                isDisabled={!configured}
                onValueChange={setEnabled}
              >
                启用定时执行
              </Switch>
              <p className="text-xs text-default-500">
                每项任务最多执行10分钟、12轮模型分析、30次工具调用。服务重启可能中断现有连接。
              </p>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setEditing(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy}
              onPress={() =>
                void action(
                  () =>
                    opsSave({
                      id: taskId,
                      config: {
                        ...config,
                        serviceAllowlist: serviceText
                          .split(",")
                          .map((s) => s.trim())
                          .filter(Boolean),
                      },
                      enabled,
                    }),
                  "任务已保存",
                ).then((ok) => {
                  if (ok) setEditing(false);
                })
              }
            >
              保存任务
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
