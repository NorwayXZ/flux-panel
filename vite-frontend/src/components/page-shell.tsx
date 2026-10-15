import type { ReactNode } from "react";

type PageShellProps = {
  children: ReactNode;
  width?: "standard" | "wide" | "full";
  className?: string;
};

const widthClass = {
  standard: "max-w-[1400px]",
  wide: "max-w-[1680px]",
  full: "max-w-none",
} as const;

export default function PageShell({
  children,
  width = "standard",
  className = "",
}: PageShellProps) {
  return (
    <div
      className={`mx-auto w-full ${widthClass[width]} px-4 pb-4 pt-5 sm:px-6 sm:pt-8 ${className}`}
    >
      {children}
    </div>
  );
}
