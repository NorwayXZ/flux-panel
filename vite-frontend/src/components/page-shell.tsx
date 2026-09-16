import type { ReactNode } from "react";

type PageShellProps = {
  children: ReactNode;
  width?: "standard" | "wide" | "full";
  className?: string;
};

const widthClass = {
  standard: "max-w-[1500px]",
  wide: "max-w-[1680px]",
  full: "max-w-none",
} as const;

export default function PageShell({
  children,
  width = "standard",
  className = "",
}: PageShellProps) {
  return (
    <div className={`mx-auto w-full ${widthClass[width]} p-4 sm:p-6 ${className}`}>
      {children}
    </div>
  );
}
