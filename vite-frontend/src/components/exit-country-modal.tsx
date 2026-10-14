import type { ExitCountryCandidate } from "@/api";

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
import { ScanSearch } from "lucide-react";
import toast from "react-hot-toast";

import {
  checkExitCountry,
  checkExitReputation,
  getExitCountryCandidates,
  preferExit,
} from "@/api";
import { exitPurity, formatExitDuration } from "@/utils/exit-display";

interface ExitCountryModalProps {
  /** 要查看的国家；为空时弹窗关闭 */
  country: { code: string; name: string } | null;
  onClose: () => void;
  onChanged: () => void;
}

/** 某个国家当前全部候选 IP 及其纯净度。 */
export default function ExitCountryModal({
  country,
  onClose,
  onChanged,
}: ExitCountryModalProps) {
  const [candidates, setCandidates] = useState<ExitCountryCandidate[]>([]);
  const [groups, setGroups] = useState<{ id: number; nodeName: string }[]>([]);
  const [loading, setLoading] = useState(false);
  const [checking, setChecking] = useState<string | null>(null);
  const [choosing, setChoosing] = useState<number | null>(null);

  useEffect(() => {
    if (!country) return;
    let cancelled = false;

    setCandidates([]);
    setGroups([]);
    setLoading(true);
    getExitCountryCandidates(country.code)
      .then((response) => {
        if (cancelled) return;
        if (response.code !== 0 || !response.data)
          toast.error(response.msg || "读取候选失败");
        else {
          setCandidates(response.data.candidates || []);
          setGroups(response.data.groups || []);
        }
      })
      .catch(() => !cancelled && toast.error("读取候选失败"))
      .finally(() => !cancelled && setLoading(false));

    return () => {
      cancelled = true;
    };
  }, [country?.code]);

  const check = async (candidate: ExitCountryCandidate) => {
    setChecking(candidate.host);
    const response = await checkExitReputation(candidate.host);

    setChecking(null);
    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "检测失败");
    const result = response.data;

    setCandidates((current) =>
      current.map((item) =>
        item.host === candidate.host
          ? {
              ...item,
              ipqsScore: result.ipqsScore,
              abuseScore: result.abuseScore,
              reputationAt: result.reputationAt,
            }
          : item,
      ),
    );
  };

  const choose = async (candidate: ExitCountryCandidate, groupId: number) => {
    setChoosing(candidate.id);
    const response = await preferExit(groupId, candidate.id);

    setChoosing(null);
    if (response.code !== 0) return toast.error(response.msg || "指定失败");
    toast.success(`已指定 ${candidate.host}，检测通过后自动切换`);
    onChanged();
  };

  const home = candidates.filter((item) => item.kind === "home").length;
  const unchecked = candidates.filter(
    (item) => item.kind === "home" && item.reputationAt == null,
  ).length;
  const [batchRunning, setBatchRunning] = useState(false);

  const checkAll = async () => {
    if (!country) return;
    if (
      !window.confirm(
        `将对 ${unchecked} 个还没检测过的家宽候选做深度检测，每个消耗一次情报源额度，在后台进行，约需 ${Math.ceil((unchecked * 2) / 60)} 分钟。继续吗？`,
      )
    )
      return;
    setBatchRunning(true);
    const response = await checkExitCountry(country.code);

    setBatchRunning(false);
    if (response.code !== 0) return toast.error(response.msg || "检测失败");
    toast.success(
      `已开始检测 ${response.data?.queued ?? 0} 个候选，稍后重新打开列表查看结果`,
    );
  };

  return (
    <Modal
      isOpen={Boolean(country)}
      scrollBehavior="inside"
      size="5xl"
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <ModalContent>
        <ModalHeader>
          {country ? `${country.name}的全部候选 IP` : "候选 IP"}
        </ModalHeader>
        <ModalBody>
          {unchecked > 0 && (
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-default-50 px-3 py-2 text-sm">
              <span className="text-default-600">
                还有 {unchecked} 个家宽候选没有做过深度检测
              </span>
              <Button
                isLoading={batchRunning}
                size="sm"
                startContent={!batchRunning && <ScanSearch size={14} />}
                variant="flat"
                onPress={checkAll}
              >
                全部检测
              </Button>
            </div>
          )}
          <p className="text-sm text-default-500">
            列表共 {candidates.length} 个，其中家宽 {home} 个。纯净度依据 IP
            库给出的网络类型和是否已被标记为代理；点“深度检测”会再查询风险情报评分（需要先在“IP
            质量检测”页面配置密钥，每次检测消耗一次额度）。列表里是候选的入口地址，实际出口
            IP 以连接后的检测结果为准。
          </p>
          {loading ? (
            <div className="flex min-h-24 items-center justify-center">
              <Spinner />
            </div>
          ) : candidates.length === 0 ? (
            <p className="py-6 text-center text-sm text-default-500">
              这个国家当前没有候选
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[820px] text-sm">
                <thead>
                  <tr className="bg-default-50 text-left text-xs text-default-500">
                    <th className="px-3 py-2.5 font-normal">IP</th>
                    <th className="px-3 py-2.5 font-normal">运营商</th>
                    <th className="px-3 py-2.5 font-normal">纯净度</th>
                    <th className="px-3 py-2.5 font-normal">风险分</th>
                    <th className="px-3 py-2.5 font-normal">共用人数</th>
                    <th className="px-3 py-2.5 font-normal">已在线</th>
                    <th className="px-3 py-2.5 text-right font-normal">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {candidates.map((candidate) => {
                    const purity = exitPurity(candidate);
                    const usable = candidate.kind === "home";

                    return (
                      <tr
                        key={candidate.id}
                        className={`border-t border-divider ${usable ? "" : "text-default-400"}`}
                      >
                        <td className="px-3 py-2.5 font-mono">
                          {candidate.host}
                        </td>
                        <td className="max-w-[220px] truncate px-3 py-2.5">
                          {candidate.owner || "-"}
                        </td>
                        <td className="px-3 py-2.5">
                          <Chip
                            color={purity.color}
                            size="sm"
                            title={purity.reason}
                            variant="flat"
                          >
                            {purity.label}
                          </Chip>
                        </td>
                        <td className="px-3 py-2.5">
                          {candidate.ipqsScore == null &&
                          candidate.abuseScore == null
                            ? "-"
                            : [
                                candidate.ipqsScore != null
                                  ? `欺诈 ${candidate.ipqsScore}`
                                  : "",
                                candidate.abuseScore != null
                                  ? `滥用 ${candidate.abuseScore}`
                                  : "",
                              ]
                                .filter(Boolean)
                                .join(" · ")}
                        </td>
                        <td className="px-3 py-2.5">
                          {candidate.sessions ?? "-"}
                        </td>
                        <td className="px-3 py-2.5">
                          {formatExitDuration(candidate.uptime)}
                        </td>
                        <td className="px-3 py-2.5 text-right">
                          <div className="flex justify-end gap-1">
                            {usable && (
                              <Button
                                isLoading={checking === candidate.host}
                                size="sm"
                                startContent={
                                  checking !== candidate.host && (
                                    <ScanSearch size={14} />
                                  )
                                }
                                variant="light"
                                onPress={() => check(candidate)}
                              >
                                深度检测
                              </Button>
                            )}
                            {usable &&
                              groups.map((group) => (
                                <Button
                                  key={group.id}
                                  isLoading={choosing === candidate.id}
                                  size="sm"
                                  title={`让 ${group.nodeName} 使用这个出口`}
                                  variant="flat"
                                  onPress={() => choose(candidate, group.id)}
                                >
                                  {groups.length > 1
                                    ? `用于 ${group.nodeName}`
                                    : "用这个"}
                                </Button>
                              ))}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
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
