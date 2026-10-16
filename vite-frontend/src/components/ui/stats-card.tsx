import type { ReactNode } from "react";

import { Card, CardBody } from "@heroui/card";

// Adapted from NodePassDash's StatsCard (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

export type StatsCardColor =
  "primary" | "success" | "danger" | "warning" | "secondary" | "default";

const colorClasses: Record<
  StatsCardColor,
  { card: string; value: string; icon: string }
> = {
  primary: {
    card: "bg-gradient-to-br from-primary-50 to-primary-100/50 dark:from-primary-900/20 dark:to-primary-900/10",
    value: "text-primary",
    icon: "bg-primary/10 text-primary",
  },
  success: {
    card: "bg-gradient-to-br from-success-50 to-success-100/50 dark:from-success-900/20 dark:to-success-900/10",
    value: "text-success",
    icon: "bg-success/10 text-success",
  },
  danger: {
    card: "bg-gradient-to-br from-danger-50 to-danger-100/50 dark:from-danger-900/20 dark:to-danger-900/10",
    value: "text-danger",
    icon: "bg-danger/10 text-danger",
  },
  warning: {
    card: "bg-gradient-to-br from-warning-50 to-warning-100/50 dark:from-warning-900/20 dark:to-warning-900/10",
    value: "text-warning",
    icon: "bg-warning/10 text-warning",
  },
  secondary: {
    card: "bg-gradient-to-br from-secondary-50 to-secondary-100/50 dark:from-secondary-900/20 dark:to-secondary-900/10",
    value: "text-secondary",
    icon: "bg-secondary/10 text-secondary",
  },
  default: {
    card: "bg-gradient-to-br from-default-50 to-default-100/50 dark:from-default-900/20 dark:to-default-900/10",
    value: "text-default-600",
    icon: "bg-default-500/10 text-default-600",
  },
};

interface StatsCardProps {
  title: string;
  value: ReactNode;
  icon: ReactNode;
  color: StatsCardColor;
  /** Small text under the value. */
  hint?: ReactNode;
  /** Extra content under the value, such as a progress bar. */
  children?: ReactNode;
  /** Rendered next to the icon, e.g. a drag handle. */
  action?: ReactNode;
  onPress?: () => void;
}

export default function StatsCard({
  title,
  value,
  icon,
  color,
  hint,
  children,
  action,
  onPress,
}: StatsCardProps) {
  const colors = colorClasses[color];

  const card = (
    <Card
      className={`h-full p-3 transition-transform hover:scale-[1.02] md:p-4 ${onPress ? "cursor-pointer" : ""} ${colors.card}`}
      shadow="sm"
    >
      <CardBody className="overflow-visible p-0">
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 flex-col gap-1">
            <span className="truncate text-xs text-default-600 md:text-sm">
              {title}
            </span>
            <span
              className={`whitespace-nowrap text-lg font-semibold sm:text-xl xl:text-2xl ${colors.value}`}
            >
              {value}
            </span>
          </div>
          <div className="flex shrink-0 items-center gap-1">
            {action && (
              // Clicks (and the click ending a drag) on the action stay off the card.
              // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
              <span onClick={(event) => event.stopPropagation()}>{action}</span>
            )}
            <div
              className={`flex h-8 w-8 items-center justify-center rounded-lg md:h-10 md:w-10 ${colors.icon}`}
            >
              {icon}
            </div>
          </div>
        </div>
        {hint && (
          <p className="mt-1 truncate text-xs text-default-500">{hint}</p>
        )}
        {children && <div className="mt-2">{children}</div>}
      </CardBody>
    </Card>
  );

  if (!onPress) return card;

  // HeroUI only forwards clicks on pressable cards, and a pressable card would fight the
  // drag-sortable grid and nest the drag handle in a button; a plain wrapper handles it.
  return (
    <div
      className="h-full rounded-large outline-none focus-visible:ring-2 focus-visible:ring-primary"
      role="link"
      tabIndex={0}
      onClick={onPress}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onPress();
        }
      }}
    >
      {card}
    </div>
  );
}
