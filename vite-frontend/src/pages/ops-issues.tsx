import { useCallback, useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import toast from "react-hot-toast";
import {
  getOpsIssues,
  saveOpsIssueNote,
  verifyOpsIssue,
  type OpsIssue,
} from "@/api/ops-issues";
import { formatDateTime } from "@/utils/format";
import PageShell from "@/components/page-shell";
const phases = {
  detected: "发现异常",
  handling: "诊断与处理",
  awaiting_verification: "待验证",
  verifying: "复检中",
  verified: "验证通过",
};
const resourceLabels: Record<string, string> = {
  node: "节点",
  connector: "接入设备",
  forward: "转发",
  tunnel: "隧道",
  private_proxy: "私人代理",
  certificate: "证书",
  ai_task: "巡查任务",
  panel: "面板",
};
const sources = { alert: "告警", self_check: "系统自检", ai_task: "AI 巡查" };
export default function OpsIssuesPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [issues, setIssues] = useState<OpsIssue[]>([]);
  const [error, setError] = useState("");
  const [filter, setFilter] = useState("open");
  const [busy, setBusy] = useState<number>();
  const [editing, setEditing] = useState<OpsIssue>();
  const [note, setNote] = useState("");
  const refresh = useCallback(async () => {
    const r = await getOpsIssues();
    if (r.code === 0) {
      setIssues(r.data || []);
      setError("");
    } else setError(r.msg || "加载问题失败");
  }, []);
  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), 5000);
    return () => clearInterval(timer);
  }, [refresh]);
  const save = async () => {
    if (!editing) return;
    setBusy(editing.id);
    const r = await saveOpsIssueNote(editing.id, note);
    setBusy(undefined);
    if (r.code !== 0) return toast.error(r.msg);
    setIssues(r.data);
    setEditing(undefined);
  };
  const verify = async (i: OpsIssue) => {
    setBusy(i.id);
    const r = await verifyOpsIssue(i.id);
    setBusy(undefined);
    if (r.code !== 0) return toast.error(r.msg);
    setIssues(r.data);
  };
  const focused = Number(params.get("issue"));
  const source = params.get("source");
  const sourceId = Number(params.get("sourceId"));
  return (
    <PageShell className="space-y-4" width="wide">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-bold">问题处理</h2>
        <Button size="sm" onPress={() => void refresh()}>
          刷新
        </Button>
      </div>
      <p className="text-sm text-default-500">
        发现异常 → 诊断 → 处理 → 验证。汇总告警、自检异常与 AI
        巡查失败，保留原始来源；AI
        对话和处理记录不会自动算作修复成功。最多显示最近 200 条，来源覆盖近 30
        天。
      </p>
      <div className="flex gap-2">
        {[
          ["open", "待处理"],
          ["all", "全部"],
          ["verified", "已验证"],
        ].map(([key, label]) => (
          <Button
            key={key}
            size="sm"
            variant={filter === key ? "solid" : "flat"}
            onPress={() => setFilter(key)}
          >
            {label}
          </Button>
        ))}
      </div>
      {Boolean(source || focused) && (
        <Button size="sm" variant="light" onPress={() => setParams({})}>
          显示全部问题
        </Button>
      )}
      {error && <p className="text-danger">{error}</p>}
      {issues
        .filter((i) =>
          source && sourceId
            ? i.sourceType === source && i.sourceId === sourceId
            : focused
              ? i.id === focused
              : filter === "all" ||
                (filter === "verified"
                  ? i.phase === "verified"
                  : i.phase !== "verified"),
        )
        .map((i) => (
          <article
            key={i.id}
            className="space-y-3 rounded-xl border border-divider bg-content1 p-4"
          >
            <div className="flex flex-wrap items-center gap-2">
              <strong>{i.title}</strong>
              <Chip
                size="sm"
                color={i.phase === "verified" ? "success" : "warning"}
              >
                {phases[i.phase]}
              </Chip>
              <Chip size="sm" variant="flat">
                {sources[i.sourceType]}
              </Chip>
            </div>
            <p className="text-xs text-default-500">
              #{i.id} · {resourceLabels[i.resourceType || ""] || i.resourceType}{" "}
              {i.resourceId || ""} · {formatDateTime(i.updatedAt)}
            </p>
            <details>
              <summary className="cursor-pointer text-sm">
                故障证据与处理记录
              </summary>
              <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap text-xs">
                {i.evidence}
              </pre>
              {i.note && (
                <p className="mt-3 whitespace-pre-wrap text-sm">
                  处理记录：{i.note}
                </p>
              )}
            </details>
            {i.verification && (
              <p className="whitespace-pre-wrap rounded-lg bg-default-100 p-3 text-xs">
                复检结果：{i.verification}
              </p>
            )}
            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                color="primary"
                onPress={() =>
                  navigate(
                    `/ai-ops?incident=${i.id}${i.chatId ? `&chat=${i.chatId}` : ""}`,
                  )
                }
              >
                AI 诊断与处理
              </Button>
              <Button
                size="sm"
                variant="flat"
                onPress={() => {
                  setEditing(i);
                  setNote(i.note || "");
                }}
              >
                记录处理
              </Button>
              <Button
                size="sm"
                variant="flat"
                isLoading={busy === i.id}
                isDisabled={i.phase === "verifying"}
                onPress={() => void verify(i)}
              >
                复检原问题
              </Button>
              <Button
                size="sm"
                variant="light"
                onPress={() =>
                  navigate(
                    i.sourceType === "alert"
                      ? "/operations/alerts"
                      : i.sourceType === "self_check"
                        ? "/operations/checks"
                        : "/ai-ops?tab=tasks",
                  )
                }
              >
                查看来源
              </Button>
            </div>
          </article>
        ))}
      {!error && issues.length === 0 && (
        <p className="py-10 text-center text-default-500">
          暂未发现需要跟进的问题
        </p>
      )}
      <Modal
        isOpen={Boolean(editing)}
        onOpenChange={(open) => {
          if (!open) setEditing(undefined);
        }}
      >
        <ModalContent>
          <ModalHeader>记录诊断与处理</ModalHeader>
          <ModalBody>
            <p className="text-sm text-default-500">
              记录执行了什么、结果和依据；保存后需复检，才可确认恢复。
            </p>
            <textarea
              aria-label="处理记录"
              maxLength={8000}
              rows={8}
              className="rounded-lg border border-divider bg-content1 p-3"
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setEditing(undefined)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === editing?.id && busy !== undefined}
              onPress={() => void save()}
            >
              保存，等待验证
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
