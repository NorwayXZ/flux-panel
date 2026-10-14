import type { PrivateProxyItem, PrivateProxyTraffic } from "@/api";

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
import { Tab, Tabs } from "@heroui/tabs";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { getPrivateProxyTraffic } from "@/api";
import { formatBytes } from "@/utils/format";

type Range = "24h" | "7d" | "30d";

interface ProxyTrafficModalProps {
  target: PrivateProxyItem | null;
  onClose: () => void;
}

const axisBytes = (value: number) => {
  if (!value) return "0";
  const units = ["B", "K", "M", "G", "T"];
  let size = value;
  let unit = 0;

  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }

  return `${size >= 10 ? size.toFixed(0) : size.toFixed(1)}${units[unit]}`;
};

/** 代理最近 24 小时（按小时）或 7/30 天（按天）的流量。 */
export default function ProxyTrafficModal({
  target,
  onClose,
}: ProxyTrafficModalProps) {
  const [range, setRange] = useState<Range>("24h");
  const [data, setData] = useState<PrivateProxyTraffic | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!target) return;
    let alive = true;

    setLoading(true);
    setError("");
    getPrivateProxyTraffic(target.id, range)
      .then((response) => {
        if (!alive) return;
        if (response.code === 0 && response.data) setData(response.data);
        else setError(response.msg || "读取流量失败");
      })
      .catch(() => alive && setError("读取流量失败"))
      .finally(() => alive && setLoading(false));

    return () => {
      alive = false;
    };
  }, [target, range]);

  if (!target) return null;
  const points = (data?.points || []).map((point) => {
    const time = new Date(point.time);

    return {
      ...point,
      label:
        range === "24h"
          ? `${String(time.getHours()).padStart(2, "0")}:00`
          : `${time.getMonth() + 1}/${time.getDate()}`,
    };
  });

  return (
    <Modal isOpen size="3xl" onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>“{target.name}”的流量</ModalHeader>
        <ModalBody className="gap-4">
          <Tabs
            aria-label="时间范围"
            selectedKey={range}
            onSelectionChange={(key) => setRange(String(key) as Range)}
          >
            <Tab key="24h" title="24 小时" />
            <Tab key="7d" title="7 天" />
            <Tab key="30d" title="30 天" />
          </Tabs>
          <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
            <span>
              上传 <b>{formatBytes(data?.totalIn || 0)}</b>
            </span>
            <span>
              下载 <b>{formatBytes(data?.totalOut || 0)}</b>
            </span>
            <span className="text-default-500">
              最近使用：
              {data?.lastActiveAt
                ? new Date(data.lastActiveAt).toLocaleString()
                : "暂无记录"}
            </span>
          </div>
          <div className="h-72 w-full">
            {loading && !data ? (
              <div className="flex h-full items-center justify-center">
                <Spinner />
              </div>
            ) : error ? (
              <p className="text-sm text-danger">{error}</p>
            ) : (
              <ResponsiveContainer height="100%" width="100%">
                <BarChart data={points}>
                  <CartesianGrid className="opacity-30" strokeDasharray="3 3" />
                  <XAxis
                    dataKey="label"
                    interval="preserveStartEnd"
                    tick={{ fontSize: 12 }}
                    tickLine={false}
                  />
                  <YAxis
                    tick={{ fontSize: 12 }}
                    tickFormatter={axisBytes}
                    tickLine={false}
                    width={48}
                  />
                  <Tooltip
                    formatter={(value, name) => [
                      formatBytes(Number(value)),
                      name === "inFlow" ? "上传" : "下载",
                    ]}
                  />
                  <Bar dataKey="inFlow" fill="#3b82f6" stackId="flow" />
                  <Bar dataKey="outFlow" fill="#10b981" stackId="flow" />
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
          <p className="text-xs text-default-500">
            从这个版本开始记录，保留 35 天。
          </p>
        </ModalBody>
        <ModalFooter>
          <Button color="primary" onPress={onClose}>
            完成
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
