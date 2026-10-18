import type { ExitEvent } from "@/api";

import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import toast from "react-hot-toast";

import { getExitPoolEvents, getMysteriumEvents } from "@/api";
import { formatDateTime } from "@/utils/format";

interface ExitEventsModalProps {
  /** 要查看的出口；为空时弹窗关闭 */
  target: {
    groupId?: number;
    scopeId?: number;
    nodeId?: number;
    country?: string;
    title: string;
  } | null;
  onClose: () => void;
}

/** 某个家宽出口的启用、切换和排除记录。 */
export default function ExitEventsModal({
  target,
  onClose,
}: ExitEventsModalProps) {
  const [events, setEvents] = useState<ExitEvent[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!target) return;
    let cancelled = false;

    setEvents([]);
    setLoading(true);
    const request =
      target.nodeId && target.country
        ? getMysteriumEvents(target.nodeId, target.country, target.scopeId)
        : getExitPoolEvents(target.groupId!);
    request
      .then((response) => {
        if (cancelled) return;
        if (response.code !== 0) toast.error(response.msg || "读取记录失败");
        else setEvents(response.data || []);
      })
      .catch(() => !cancelled && toast.error("读取记录失败"))
      .finally(() => !cancelled && setLoading(false));

    return () => {
      cancelled = true;
    };
  }, [target?.scopeId, target?.groupId, target?.nodeId, target?.country]);

  return (
    <Modal
      isOpen={Boolean(target)}
      scrollBehavior="inside"
      size="2xl"
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <ModalContent>
        <ModalHeader>{target?.title || "切换记录"}</ModalHeader>
        <ModalBody>
          {loading ? (
            <div className="flex min-h-24 items-center justify-center">
              <Spinner />
            </div>
          ) : events.length === 0 ? (
            <p className="py-6 text-center text-sm text-default-500">
              还没有记录
            </p>
          ) : (
            <div className="divide-y divide-divider border-y border-divider">
              {events.map((event) => (
                <div
                  key={event.id}
                  className="grid gap-1 py-3 sm:grid-cols-[150px_1fr]"
                >
                  <span className="font-mono text-xs text-default-500">
                    {formatDateTime(event.createdTime)}
                  </span>
                  <span className="break-words text-sm">{event.message}</span>
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
