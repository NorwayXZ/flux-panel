import { lazy, Suspense } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { Tab, Tabs } from "@heroui/tabs";
import { Spinner } from "@heroui/spinner";
const Issues = lazy(() => import("@/pages/ops-issues"));
const Alerts = lazy(() => import("@/pages/monitoring"));
const Checks = lazy(() => import("@/pages/system-self-check"));
export default function OperationsWorkspace() {
  const location = useLocation();
  const navigate = useNavigate();
  const key = location.pathname.split("/")[2] || "issues";
  if (!["issues", "alerts", "checks"].includes(key))
    return <Navigate replace to="/operations/issues" />;
  return (
    <div className="space-y-4">
      <div className="px-4 pt-5 sm:px-6 sm:pt-8">
        <h1 className="mb-3 text-2xl font-bold">运维中心</h1>
        <Tabs
          aria-label="运维工作流程"
          selectedKey={key}
          variant="underlined"
          onSelectionChange={(value) => navigate(`/operations/${value}`)}
        >
          <Tab key="issues" title="问题处理" />
          <Tab key="alerts" title="告警与通知" />
          <Tab key="checks" title="系统自检" />
        </Tabs>
      </div>
      <Suspense fallback={<Spinner />}>
        {key === "issues" ? (
          <Issues />
        ) : key === "alerts" ? (
          <Alerts />
        ) : (
          <Checks />
        )}
      </Suspense>
    </div>
  );
}
