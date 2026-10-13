import type { ExitCandidateOption } from "@/api";

import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import toast from "react-hot-toast";

import { getExitCandidates, preferExit } from "@/api";

interface ExitCandidateModalProps {
  /** 要指定出口的出口组；为空时弹窗关闭 */
  target: { groupId: number; title: string } | null;
  onClose: () => void;
  onChanged: () => void;
}

const candidateNote = (candidate: ExitCandidateOption) => {
  if (candidate.state === "up") return "已连接";
  if (candidate.state === "connecting") return "连接中";
  if (candidate.state === "failed" || candidate.state === "rejected")
    return candidate.error || "上次没有连上";
  if (candidate.successCount) return "之前用过";

  return "还没试过";
};

/** 从候选里手动指定一个出口。 */
export default function ExitCandidateModal({
  target,
  onClose,
  onChanged,
}: ExitCandidateModalProps) {
  const [candidates, setCandidates] = useState<ExitCandidateOption[]>([]);
  const [preferred, setPreferred] = useState<number | undefined>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState<number | null>(null);

  useEffect(() => {
    if (!target) return;
    let cancelled = false;

    setCandidates([]);
    setLoading(true);
    getExitCandidates(target.groupId)
      .then((response) => {
        if (cancelled) return;
        if (response.code !== 0 || !response.data)
          toast.error(response.msg || "读取候选失败");
        else {
          setCandidates(response.data.candidates || []);
          setPreferred(response.data.preferredCandidateId);
        }
      })
      .catch(() => !cancelled && toast.error("读取候选失败"))
      .finally(() => !cancelled && setLoading(false));

    return () => {
      cancelled = true;
    };
  }, [target?.groupId]);

  const choose = async (candidate: ExitCandidateOption) => {
    if (!target) return;
    setSaving(candidate.id);
    const response = await preferExit(target.groupId, candidate.id);

    setSaving(null);
    if (response.code !== 0) return toast.error(response.msg || "指定失败");
    toast.success(`已指定 ${candidate.host}，检测通过后自动切换`);
    onChanged();
    onClose();
  };

  return (
    <Modal
      isOpen={Boolean(target)}
      scrollBehavior="inside"
      size="3xl"
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <ModalContent>
        <ModalHeader>{target?.title || "指定出口"}</ModalHeader>
        <ModalBody>
          <p className="text-sm text-default-500">
            选中后系统会先连接并检测它，确认是家宽后才切换过去，期间继续使用当前出口。它连不上或者以后失效时，会自动换回系统选择的出口。列表里显示的是候选的入口地址，实际出口
            IP 以连接后的检测结果为准。
          </p>
          {loading ? (
            <div className="flex min-h-24 items-center justify-center">
              <Spinner />
            </div>
          ) : candidates.length === 0 ? (
            <p className="py-6 text-center text-sm text-default-500">
              当前没有可以指定的候选
            </p>
          ) : (
            <div className="divide-y divide-divider border-y border-divider">
              {candidates.map((candidate) => (
                <div
                  key={candidate.id}
                  className="flex flex-wrap items-center gap-x-4 gap-y-1 py-2.5"
                >
                  <span className="w-36 font-mono text-sm">
                    {candidate.exitIp || candidate.host}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-sm">
                    {candidate.owner || "-"}
                  </span>
                  <span className="text-xs text-default-500">
                    {candidateNote(candidate)}
                    {candidate.latencyMs ? ` · ${candidate.latencyMs} ms` : ""}
                    {candidate.sessions !== undefined
                      ? ` · ${candidate.sessions} 人在用`
                      : ""}
                  </span>
                  {candidate.flaggedProxy ? (
                    <Chip color="warning" size="sm" variant="flat">
                      已被标记为代理
                    </Chip>
                  ) : null}
                  {preferred === candidate.id ? (
                    <Chip color="primary" size="sm" variant="flat">
                      已指定
                    </Chip>
                  ) : (
                    <Button
                      isLoading={saving === candidate.id}
                      size="sm"
                      variant="flat"
                      onPress={() => choose(candidate)}
                    >
                      用这个
                    </Button>
                  )}
                </div>
              ))}
            </div>
          )}
        </ModalBody>
        <ModalFooter>
          <Button color="primary" onPress={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
