import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Switch } from "@heroui/switch";
import { FileText, RefreshCw } from "lucide-react";
import toast from "react-hot-toast";

import {
  opsGenerateReport,
  opsPreferences,
  opsReports,
  opsSavePreferences,
  type OpsPreferences,
  type OpsReport,
} from "@/api/ai-ops";
import { AssistantMarkdown } from "@/components/ai-chat-markdown";

const formatTime = (value: number) =>
  new Date(value).toLocaleString("zh-CN", { hour12: false });

/** Assistant automation switches and the weekly health reports. */
export default function AiOpsReports({ configured }: { configured: boolean }) {
  const [preferences, setPreferences] = useState<OpsPreferences>();
  const [reports, setReports] = useState<OpsReport[]>([]);
  const [selected, setSelected] = useState<number>();
  const [generating, setGenerating] = useState(false);

  const load = async () => {
    const [p, r] = await Promise.all([opsPreferences(), opsReports()]);

    if (p.code === 0) setPreferences(p.data);
    if (r.code === 0) {
      setReports(r.data);
      setSelected((current) => current ?? r.data[0]?.id);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const save = async (patch: Partial<OpsPreferences>) => {
    const r = await opsSavePreferences(patch);

    if (r.code === 0) setPreferences(r.data);
    else toast.error(r.msg);
  };

  const generate = async () => {
    setGenerating(true);
    const r = await opsGenerateReport();

    if (r.code !== 0) {
      setGenerating(false);

      return toast.error(r.msg);
    }
    toast.success(r.data.message);
    // Disk checks run node by node; refresh until the new report appears.
    const before = reports[0]?.id ?? 0;

    for (let i = 0; i < 40; i++) {
      await new Promise((resolve) => setTimeout(resolve, 6000));
      const latest = await opsReports();

      if (latest.code === 0 && (latest.data[0]?.id ?? 0) > before) {
        setReports(latest.data);
        setSelected(latest.data[0].id);
        break;
      }
    }
    setGenerating(false);
  };

  const report = reports.find((item) => item.id === selected);

  return (
    <div className="grid gap-4 pt-4 lg:grid-cols-[272px_minmax(0,1fr)]">
      <aside className="space-y-3">
        <div className="space-y-3 rounded-2xl border border-default-200 bg-content1 p-4">
          <p className="text-sm font-medium">自动运维</p>
          <Switch
            isDisabled={!preferences}
            isSelected={!!preferences?.alertDiagnosis}
            size="sm"
            onValueChange={(value) => void save({ alertDiagnosis: value })}
          >
            <span className="text-sm">告警自动诊断</span>
          </Switch>
          <p className="text-xs leading-5 text-default-500">
            节点、隧道、转发或出口出现告警约 45
            秒后仍未恢复时，助手做一次只读排查，把可能原因和建议写进告警中心并推送
            Telegram。同一资源 30 分钟最多一次，每小时最多 6 次。
          </p>
          <Switch
            isDisabled={!preferences}
            isSelected={!!preferences?.weeklyReport}
            size="sm"
            onValueChange={(value) => void save({ weeklyReport: value })}
          >
            <span className="text-sm">每周健康报告</span>
          </Switch>
          <p className="text-xs leading-5 text-default-500">
            每周一 9
            点汇总磁盘与一周增长、可用率、CPU/内存、需要升级的节点、即将到期的证书和服务器、流量变化和未处理告警，推送
            Telegram。
          </p>
          {!configured && (
            <p className="text-xs text-warning-700">
              需要先在“模型配置”填写模型，才会生成 AI 总结和诊断。
            </p>
          )}
          <Button
            fullWidth
            color="primary"
            isLoading={generating}
            startContent={!generating && <RefreshCw size={15} />}
            variant="flat"
            onPress={() => void generate()}
          >
            {generating ? "正在逐台检查…" : "立即生成报告"}
          </Button>
        </div>
        <div className="rounded-2xl border border-default-200 bg-content1 p-2">
          <p className="px-2 pb-1 pt-1 text-xs text-default-400">历史报告</p>
          {reports.length === 0 && (
            <p className="px-2 py-3 text-sm text-default-500">还没有报告。</p>
          )}
          {reports.map((item) => (
            <button
              key={item.id}
              className={`flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm transition ${
                item.id === selected
                  ? "bg-default-100 text-foreground"
                  : "text-default-600 hover:bg-default-100/60"
              }`}
              type="button"
              onClick={() => setSelected(item.id)}
            >
              <FileText className="shrink-0 text-default-400" size={14} />
              <span className="truncate">{item.title}</span>
              <span className="ml-auto shrink-0 text-xs text-default-400">
                {formatTime(item.createdAt).slice(5, 16)}
              </span>
            </button>
          ))}
        </div>
      </aside>
      <section className="min-w-0 rounded-2xl border border-default-200 bg-content1">
        {report ? (
          <div className="mx-auto max-w-3xl px-4 py-6 sm:px-8">
            <h2 className="ai-prose mb-1 text-2xl">{report.title}</h2>
            <p className="mb-6 text-xs text-default-400">
              生成于 {formatTime(report.createdAt)}
            </p>
            <AssistantMarkdown content={report.content} />
          </div>
        ) : (
          <div className="flex min-h-72 items-center justify-center p-6 text-sm text-default-500">
            点击“立即生成报告”，或等待每周一自动生成。
          </div>
        )}
      </section>
    </div>
  );
}
