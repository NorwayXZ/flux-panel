import type { ReactNode } from "react";
import PageShell from "@/components/page-shell";

interface PageWrapperProps {
  children: ReactNode;
  title: string;
  description?: string;
  className?: string;
}

export default function PageWrapper({
  children,
  title,
  description,
  className = "space-y-5",
}: PageWrapperProps) {
  return (
    <PageShell className={className}>
      <div className="mb-6">
        <h1 className="text-2xl font-bold mb-2 text-foreground">{title}</h1>
        {description && <p className="text-default-600">{description}</p>}
      </div>
      {children}
    </PageShell>
  );
}
