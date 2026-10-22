import { useCallback, useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Check, RefreshCw, X } from "lucide-react";
import toast from "react-hot-toast";

import {
  opsApproveProposal,
  opsProposals,
  opsRejectProposal,
  type OpsProposal,
} from "@/api/ai-ops";

const formatTime = (value?: number) =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";

const statusMeta: Record<OpsProposal["status"], { label: string; color: "warning" | "success" | "danger" | "default" }> = {
  pending: { label: "待审批", color: "warning" },
  executed: { label: "已执行", color: "success" },
  failed: { label: "执行失败", color: "danger" },
  rejected: { label: "已拒绝", color: "default" },
  expired: { label: "已过期", color: "default" },
};

const sourceLabel = (source: string) =>
  source === "chat" ? "AI 对话" : source === "task" ? "AI 定时任务" : "管理员手动";

/**
 * Change proposals filed by the AI assistant. Nothing here has run yet: approving
 * executes the action as the current administrator, rejecting discards it.
 */
export default function AiOpsProposals() {
  const [proposals, setProposals] = useState<OpsProposal[]>([]);
  const [busy, setBusy] = useState<number>();
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await opsProposals();

      if (r.code === 0) setProposals(r.data);
    } catch {
      // Keep the last known list; the refresh button can retry.
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const decide = async (proposal: OpsProposal, approve: boolean) => {
    setBusy(proposal.id);
    try {
      const r = approve
        ? await opsApproveProposal(proposal.id)
        : await opsRejectProposal(proposal.id);

      if (r.code === 0) {
        toast.success(approve ? "已批准并执行" : "已拒绝该提案");
      } else {
        toast.error(r.msg);
      }
    } catch {
      toast.error("请求失败，请稍后重试");
    }
    setBusy(undefined);
    void load();
  };

  const pending = proposals.filter((p) => p.status === "pending");
  const history = proposals.filter((p) => p.status !== "pending");

  const renderProposal = (proposal: OpsProposal) => {
    const meta = statusMeta[proposal.status];

    return (
      <div
        key={proposal.id}
        className="space-y-2 rounded-2xl border border-default-200 bg-content1 p-4"
      >
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">
            #{proposal.id} {proposal.title}
          </span>
          <Chip color={meta.color} size="sm" variant="flat">
            {meta.label}
          </Chip>
          <Chip size="sm" variant="flat">
            {proposal.actionLabel}
          </Chip>
          <span className="text-xs text-default-500">
            {sourceLabel(proposal.source)} · {formatTime(proposal.createdAt)}
          </span>
        </div>
        <p className="text-sm text-default-600">
          目标转发：{proposal.targetName || `#${proposal.targetId}`}
        </p>
        <p className="whitespace-pre-wrap text-sm leading-6 text-default-600">
          {proposal.reason}
        </p>
        {proposal.result && (
          <p className="text-xs leading-5 text-default-500">
            执行结果：{proposal.result}
            {proposal.decidedAt ? ` · ${formatTime(proposal.decidedAt)}` : ""}
          </p>
        )}
        {proposal.status === "pending" && (
          <div className="flex gap-2 pt-1">
            <Button
              color="primary"
              isLoading={busy === proposal.id}
              size="sm"
              startContent={busy !== proposal.id && <Check size={14} />}
              onPress={() => void decide(proposal, true)}
            >
              批准并执行
            </Button>
            <Button
              isLoading={busy === proposal.id}
              size="sm"
              startContent={busy !== proposal.id && <X size={14} />}
              variant="flat"
              onPress={() => void decide(proposal, false)}
            >
              拒绝
            </Button>
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="space-y-4 pt-4">
      <div className="flex items-start justify-between gap-3">
        <p className="max-w-3xl text-sm leading-6 text-default-600">
          AI 助手（对话、定时任务、告警自动诊断）发现问题后可以提交变更提案，例如暂停或恢复一条转发。
          提案本身不会改变任何配置；只有你在这里点击“批准并执行”，动作才会以你当前账号的权限真正执行，
          结果会记录在提案上。待审批提案 7 天后自动过期。
        </p>
        <Button
          isLoading={loading}
          size="sm"
          startContent={!loading && <RefreshCw size={14} />}
          variant="flat"
          onPress={() => void load()}
        >
          刷新
        </Button>
      </div>
      {pending.length > 0 ? (
        <div className="space-y-3">{pending.map(renderProposal)}</div>
      ) : (
        <div className="rounded-2xl border border-default-200 bg-content1 p-4 text-sm text-default-500">
          暂无待审批提案。AI 助手提出暂停或恢复转发的建议后，会出现在这里。
        </div>
      )}
      {history.length > 0 && (
        <>
          <p className="pt-2 text-sm font-medium">处理记录</p>
          <div className="space-y-3">{history.map(renderProposal)}</div>
        </>
      )}
    </div>
  );
}
