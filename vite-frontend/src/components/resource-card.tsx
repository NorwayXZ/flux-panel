import type { ReactNode } from "react";

import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Switch } from "@heroui/switch";

/**
 * The panel's standard resource card, modelled on the forward card: a header with title,
 * subtitles, an optional switch and status chip; boxed endpoint blocks; a two-column fact grid;
 * an optional status line; tag chips; and a row of action buttons. Pages describe the content
 * and get the same look everywhere.
 */

export type ChipColor =
  "default" | "primary" | "secondary" | "success" | "warning" | "danger";

export interface ResourceCardBlock {
  label: string;
  /** Chip on the block's right, e.g. 在线 / 离线. */
  status?: { text: string; color: ChipColor };
  rows: Array<{
    label?: string;
    value: ReactNode;
    mono?: boolean;
    title?: string;
  }>;
  /** Marks the block as failing (red tint). */
  danger?: boolean;
  onPress?: () => void;
}

export interface ResourceCardFact {
  label: string;
  value: ReactNode;
  tone?: "default" | "danger" | "warning" | "success";
}

export interface ResourceCardAction {
  label: string;
  icon?: ReactNode;
  color?: ChipColor;
  onPress: () => void;
  isDisabled?: boolean;
  isLoading?: boolean;
}

interface ResourceCardProps {
  title: ReactNode;
  /** Lines under the title, smaller and muted. */
  subtitles?: ReactNode[];
  status?: { text: string; color: ChipColor };
  /** On/off switch beside the status chip. */
  toggle?: {
    isSelected: boolean;
    onChange: () => void;
    isDisabled?: boolean;
    label?: string;
  };
  /** Rendered before the switch, e.g. a drag handle or a badge. */
  headerExtra?: ReactNode;
  blocks?: ResourceCardBlock[];
  facts?: ResourceCardFact[];
  /** A single highlighted status line with an optional trailing control. */
  notice?: {
    icon?: ReactNode;
    label: string;
    value: ReactNode;
    tone?: "default" | "success" | "warning" | "danger";
    trailing?: ReactNode;
  };
  chips?: Array<{ text: ReactNode; color?: ChipColor }>;
  actions?: ResourceCardAction[];
  /** Extra content after the facts, for anything page specific. */
  children?: ReactNode;
  /** Red accent for a failing resource. */
  danger?: boolean;
  className?: string;
}

const toneText = {
  default: "",
  success: "text-success",
  warning: "text-warning",
  danger: "text-danger",
} as const;

const noticeIcon = {
  default: "bg-default-100 text-default-500",
  success:
    "bg-success-100 text-success-700 dark:bg-success-900/30 dark:text-success-300",
  warning:
    "bg-warning-100 text-warning-700 dark:bg-warning-900/30 dark:text-warning-300",
  danger:
    "bg-danger-100 text-danger-700 dark:bg-danger-900/30 dark:text-danger-300",
} as const;

export default function ResourceCard({
  title,
  subtitles = [],
  status,
  toggle,
  headerExtra,
  blocks = [],
  facts = [],
  notice,
  chips = [],
  actions = [],
  children,
  danger = false,
  className = "",
}: ResourceCardProps) {
  const divider = danger
    ? "border-danger-200 dark:border-danger-800"
    : "border-divider";

  return (
    <Card
      className={`${
        danger
          ? "offline-card border border-danger-300"
          : "border border-transparent dark:border-default-100"
      } h-full overflow-hidden transition-shadow duration-200 hover:shadow-md ${className}`}
    >
      {danger && <div className="offline-accent h-0.5 bg-danger" />}
      <CardHeader className="pb-2">
        <div className="flex w-full items-start justify-between">
          <div className="min-w-0 flex-1">
            <h3 className="truncate text-sm font-semibold text-foreground">
              {title}
            </h3>
            {subtitles.filter(Boolean).map((line, index) => (
              <p
                // Subtitles are static per render.
                // eslint-disable-next-line react/no-array-index-key
                key={index}
                className="truncate text-xs text-default-500"
              >
                {line}
              </p>
            ))}
          </div>
          <div className="ml-2 flex items-center gap-1.5">
            {headerExtra}
            {toggle && (
              <Switch
                aria-label={toggle.label || "启用"}
                isDisabled={toggle.isDisabled}
                isSelected={toggle.isSelected}
                size="sm"
                onValueChange={toggle.onChange}
              />
            )}
            {status && (
              <Chip
                className={danger ? "offline-status-chip text-xs" : "text-xs"}
                color={status.color}
                size="sm"
                variant="flat"
              >
                {status.text}
              </Chip>
            )}
          </div>
        </div>
      </CardHeader>

      <CardBody className="flex flex-col pb-3 pt-0">
        <div className="flex-1 space-y-2">
          {blocks.length > 0 && (
            <div className="space-y-1">
              {blocks.map((block) => {
                const content = (
                  <>
                    <div className="mb-1 flex items-center justify-between">
                      <span
                        className={`text-xs font-medium ${block.danger ? "text-danger-700 dark:text-danger-300" : "text-default-600"}`}
                      >
                        {block.label}
                      </span>
                      {block.status && (
                        <Chip
                          className="text-xs"
                          color={block.status.color}
                          size="sm"
                          variant="flat"
                        >
                          {block.status.text}
                        </Chip>
                      )}
                    </div>
                    {block.rows.map((row, index) => (
                      <div
                        // eslint-disable-next-line react/no-array-index-key
                        key={index}
                        className="flex min-w-0 items-center gap-1.5"
                        title={row.title}
                      >
                        {row.label && (
                          <span className="flex-shrink-0 text-xs font-medium text-default-600">
                            {row.label}:
                          </span>
                        )}
                        <span
                          className={`min-w-0 truncate text-xs ${row.mono === false ? "" : "font-mono"} ${block.danger ? "text-danger-800 dark:text-danger-200" : "text-foreground"}`}
                        >
                          {row.value}
                        </span>
                      </div>
                    ))}
                  </>
                );
                const blockClass = `px-2 py-1.5 rounded border transition-colors duration-200 ${
                  block.danger
                    ? "bg-danger-100/90 border-danger-300"
                    : "bg-default-50 dark:bg-default-100/50 border-default-200 dark:border-default-300"
                }`;

                return block.onPress ? (
                  <button
                    key={block.label}
                    className={`${blockClass} block w-full cursor-pointer text-left hover:bg-default-100 dark:hover:bg-default-200/50`}
                    type="button"
                    onClick={block.onPress}
                  >
                    {content}
                  </button>
                ) : (
                  <div key={block.label} className={blockClass}>
                    {content}
                  </div>
                );
              })}
            </div>
          )}

          {facts.length > 0 && (
            <div
              className={`grid min-h-[52px] grid-cols-2 gap-x-3 gap-y-1 border-t pt-2 ${divider}`}
            >
              {facts.map((fact) => (
                <div key={fact.label} className="min-w-0">
                  <div className="text-[11px] text-default-400">
                    {fact.label}
                  </div>
                  <div
                    className={`truncate text-xs font-medium ${toneText[fact.tone || "default"]}`}
                  >
                    {fact.value}
                  </div>
                </div>
              ))}
            </div>
          )}

          {notice && (
            <div
              className={`flex min-h-[42px] items-center gap-2 border-t pt-2 ${divider}`}
            >
              {notice.icon && (
                <div
                  className={`flex h-7 w-7 flex-shrink-0 items-center justify-center rounded ${noticeIcon[notice.tone || "default"]}`}
                >
                  {notice.icon}
                </div>
              )}
              <div className="min-w-0 flex-1">
                <div className="text-[11px] text-default-400">
                  {notice.label}
                </div>
                <div className="truncate text-xs font-medium">
                  {notice.value}
                </div>
              </div>
              {notice.trailing}
            </div>
          )}

          {children}

          {chips.length > 0 && (
            <div className={`flex flex-wrap gap-1.5 border-t pt-2 ${divider}`}>
              {chips.map((chip, index) => (
                <Chip
                  // eslint-disable-next-line react/no-array-index-key
                  key={index}
                  className="max-w-full text-xs"
                  color={chip.color || "default"}
                  size="sm"
                  variant="flat"
                >
                  {chip.text}
                </Chip>
              ))}
            </div>
          )}
        </div>

        {actions.length > 0 && (
          <div className="mt-3 flex gap-1.5">
            {actions.map((action) => (
              <Button
                key={action.label}
                className="min-h-8 min-w-0 flex-1 px-2"
                color={action.color || "default"}
                isDisabled={action.isDisabled}
                isLoading={action.isLoading}
                size="sm"
                startContent={action.isLoading ? undefined : action.icon}
                variant="flat"
                onPress={action.onPress}
              >
                {action.label}
              </Button>
            ))}
          </div>
        )}
      </CardBody>
    </Card>
  );
}

/** Responsive grid used for resource cards on every list page. */
export function ResourceCardGrid({ children }: { children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 items-start gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5">
      {children}
    </div>
  );
}
