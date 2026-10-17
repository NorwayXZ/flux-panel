import type { PrivateProxyItem } from "@/api";
import { Button } from "@heroui/button";
import { History, Link2, Shuffle } from "lucide-react";

export default function PrivateProxyExitActions({
  item,
  isAdmin,
  switching,
  onSwitch,
  onLink,
  onEvents,
}: {
  item: PrivateProxyItem;
  isAdmin: boolean;
  switching: boolean;
  onSwitch: () => void;
  onLink: () => void;
  onEvents: () => void;
}) {
  if (
    !["residential", "mysterium"].includes(item.exitType || "") ||
    !item.exit ||
    item.state !== "active"
  )
    return null;
  return (
    <div className="flex flex-wrap gap-1">
      <Button
        className="h-7 min-w-0 px-2 text-xs"
        isDisabled={item.exitType !== "mysterium" && !item.exit.standbyReady}
        isLoading={switching}
        size="sm"
        startContent={!switching && <Shuffle size={13} />}
        variant="flat"
        onPress={onSwitch}
      >
        换一个出口
      </Button>
      <Button
        className="h-7 min-w-0 px-2 text-xs"
        size="sm"
        startContent={<Link2 size={13} />}
        variant="flat"
        onPress={onLink}
      >
        切换链接
      </Button>
      {isAdmin && (
        <Button
          className="h-7 min-w-0 px-2 text-xs"
          size="sm"
          startContent={<History size={13} />}
          variant="flat"
          onPress={onEvents}
        >
          切换记录
        </Button>
      )}
      {item.exitType === "mysterium" && <p className="w-full text-xs text-default-400">同一节点、同一国家共用出口，切换会影响其他使用它的代理。</p>}
    </div>
  );
}
