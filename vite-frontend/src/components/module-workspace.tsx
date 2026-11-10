import { lazy, Suspense } from "react";
import {
  Navigate,
  useLocation,
  useNavigate,
  useSearchParams,
} from "react-router-dom";
import { Tab, Tabs } from "@heroui/tabs";
import { Spinner } from "@heroui/spinner";
import { isAdmin } from "@/utils/auth";

const NetworkTools = lazy(() => import("@/pages/network-tools"));
const Bandwidth = lazy(() => import("@/pages/bandwidth-test"));
const ClientSpeed = lazy(() => import("@/pages/client-speed-test"));
const Udp = lazy(() => import("@/pages/udp-quic-diagnostic"));
const IpQuality = lazy(() => import("@/pages/ip-quality"));
const Forward = lazy(() => import("@/pages/forward"));
const Strategies = lazy(() => import("@/pages/multi-line-aggregation"));

export const networkTests = [
  { key: "diagnostics", label: "网络诊断", component: NetworkTools },
  { key: "bandwidth", label: "服务器间带宽", component: Bandwidth },
  { key: "client-speed", label: "本机测速", component: ClientSpeed },
  { key: "udp-quic", label: "UDP / QUIC", component: Udp },
  { key: "ip-quality", label: "IP 质量", component: IpQuality },
];

export function LegacyModuleRedirect({ to }: { to: string }) {
  const location = useLocation();
  const [path, query = ""] = to.split("?");
  const params = new URLSearchParams(location.search);
  new URLSearchParams(query).forEach((value, key) => params.set(key, value));
  return (
    <Navigate
      replace
      to={`${path}${params.size ? `?${params}` : ""}${location.hash}`}
    />
  );
}

export function NetworkDetectionWorkspace() {
  const location = useLocation();
  const navigate = useNavigate();
  const key = location.pathname.split("/")[2] || "diagnostics";
  const selected = networkTests.find((item) => item.key === key);
  if (!selected)
    return <Navigate replace to="/network-detection/diagnostics" />;
  const Page = selected.component;
  return (
    <div className="space-y-4">
      <div className="px-4 pt-5 sm:px-6 sm:pt-8">
        <h1 className="mb-3 text-2xl font-bold">网络检测</h1>
        <div className="overflow-x-auto">
          <Tabs
            aria-label="网络检测类型"
            selectedKey={key}
            variant="underlined"
            onSelectionChange={(value) =>
              navigate(`/network-detection/${value}`)
            }
          >
            {networkTests.map((item) => (
              <Tab key={item.key} title={item.label} />
            ))}
          </Tabs>
        </div>
      </div>
      <Suspense fallback={<Spinner label="加载检测工具" />}>
        <Page />
      </Suspense>
    </div>
  );
}

export function ForwardWorkspace() {
  const [params, setParams] = useSearchParams();
  const strategies = params.get("tab") === "line-strategy" && isAdmin();
  return (
    <div className="space-y-4">
      {isAdmin() && (
        <div className="px-4 pt-5 sm:px-6 sm:pt-8">
          <Tabs
            aria-label="转发与线路策略"
            selectedKey={strategies ? "line-strategy" : "forwards"}
            variant="underlined"
            onSelectionChange={(key) => {
              const next = new URLSearchParams(params);
              if (key === "forwards") next.delete("tab");
              else next.set("tab", String(key));
              setParams(next);
            }}
          >
            <Tab key="forwards" title="转发列表与策略配置" />
            <Tab key="line-strategy" title="线路策略" />
          </Tabs>
          {strategies && (
            <p className="mt-3 text-sm text-default-500">
              单线路、主备、低延迟及固定权重负载均衡在转发列表中编辑。这里管理按线路质量动态调整权重的多连接策略，单个连接仍使用一条线路。
            </p>
          )}
        </div>
      )}
      <Suspense fallback={<Spinner label="加载转发" />}>
        {strategies ? <Strategies /> : <Forward />}
      </Suspense>
    </div>
  );
}
