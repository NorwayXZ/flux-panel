import type { ReactNode } from "react";

import { Popover, PopoverContent, PopoverTrigger } from "@heroui/popover";
import { CircleHelp } from "lucide-react";

/**
 * A small help icon beside a page title. The explanation opens on click, so page headers stay
 * a clean title and actions, as in NodePassDash.
 */
export default function PageHelp({ children }: { children: ReactNode }) {
  return (
    <Popover placement="bottom-start" showArrow>
      <PopoverTrigger>
        <button
          aria-label="页面说明"
          className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-default-400 transition-colors hover:bg-default-100 hover:text-default-600"
          type="button"
        >
          <CircleHelp size={17} />
        </button>
      </PopoverTrigger>
      <PopoverContent className="max-w-sm px-4 py-3">
        <div className="text-sm leading-6 text-default-600">{children}</div>
      </PopoverContent>
    </Popover>
  );
}
