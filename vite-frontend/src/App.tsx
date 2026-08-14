import { Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { Component, lazy, Suspense, useEffect, useState } from "react";

import IndexPage from "@/pages/index";

// Keep the login page in the initial bundle. Business pages load only when opened,
// so a refresh no longer downloads terminals, charts, topology, and every admin view.
const ChangePasswordPage = lazy(() => import("@/pages/change-password"));
const DashboardPage = lazy(() => import("@/pages/dashboard"));
const ForwardPage = lazy(() => import("@/pages/forward"));
const RoutingOverviewPage = lazy(() => import("@/pages/routing-overview"));
const NftForwardPage = lazy(() => import("@/pages/nft-forward"));
const TunnelPage = lazy(() => import("@/pages/tunnel"));
const NodePage = lazy(() => import("@/pages/node"));
const NodeTerminalPage = lazy(() => import("@/pages/node-terminal"));
const UserPage = lazy(() => import("@/pages/user"));
const ProfilePage = lazy(() => import("@/pages/profile"));
const ConfigPage = lazy(() => import("@/pages/config"));
const UpdatePage = lazy(() => import("@/pages/update"));
const MonitoringPage = lazy(() => import("@/pages/monitoring"));
const ServicePublishingPage = lazy(() => import("@/pages/service-publishing"));
const DockerAppsPage = lazy(() => import("@/pages/docker-apps"));
const TopologyPage = lazy(() => import("@/pages/topology"));
const PortResourcesPage = lazy(() => import("@/pages/port-resources"));
const CrossEntryFailoverPage = lazy(
  () => import("@/pages/cross-entry-failover"),
);
const SourceIpEntryPage = lazy(() => import("@/pages/source-ip-entry"));
const SmartEntryPage = lazy(() => import("@/pages/smart-entry"));
const DnsSettingsPage = lazy(() => import("@/pages/dns-settings"));
const AwsAccessPage = lazy(() => import("@/pages/aws-access"));
const PrivateProxyPage = lazy(() => import("@/pages/private-proxy"));
const ProtocolProbePage = lazy(() => import("@/pages/protocol-probe"));
const NetworkToolsPage = lazy(() => import("@/pages/network-tools"));
const QualityLabPage = lazy(() => import("@/pages/quality-lab"));
const BandwidthTestPage = lazy(() => import("@/pages/bandwidth-test"));
const ClientSpeedTestPage = lazy(() => import("@/pages/client-speed-test"));
const UdpQuicDiagnosticPage = lazy(() => import("@/pages/udp-quic-diagnostic"));
const MultiLineAggregationPage = lazy(
  () => import("@/pages/multi-line-aggregation"),
);
const IpQualityPage = lazy(() => import("@/pages/ip-quality"));
const PrivateNetworkPage = lazy(() => import("@/pages/private-network"));
const ServerAssetsPage = lazy(() => import("@/pages/server-assets"));
const DynamicDnsPage = lazy(() => import("@/pages/dynamic-dns"));
const HomeAccessPage = lazy(() => import("@/pages/home-access"));
const HomeDevicesPage = lazy(() => import("@/pages/home-devices"));
const GuidePage = lazy(() => import("@/pages/guide"));
const SystemSelfCheckPage = lazy(() => import("@/pages/system-self-check"));
const PanelAddressPage = lazy(() =>
  import("@/pages/settings").then((module) => ({
    default: module.SettingsPage,
  })),
);

import AdminLayout from "@/layouts/admin";
import H5Layout from "@/layouts/h5";
import H5SimpleLayout from "@/layouts/h5-simple";
import { isAdmin, isLoggedIn } from "@/utils/auth";
import { getCachedConfig, siteConfig } from "@/config/site";

// 检测是否为H5模式
const useH5Mode = () => {
  // 立即检测H5模式，避免初始渲染时的闪屏
  const getInitialH5Mode = () => {
    // 检测移动设备或小屏幕
    const isMobile = window.innerWidth <= 768;
    // 检测是否为移动端浏览器
    const isMobileBrowser =
      /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(
        navigator.userAgent,
      );
    // 检测URL参数是否包含h5模式
    const urlParams = new URLSearchParams(window.location.search);
    const isH5Param = urlParams.get("h5") === "true";

    return isMobile || isMobileBrowser || isH5Param;
  };

  const [isH5, setIsH5] = useState(getInitialH5Mode);

  useEffect(() => {
    const checkH5Mode = () => {
      // 检测移动设备或小屏幕
      const isMobile = window.innerWidth <= 768;
      // 检测是否为移动端浏览器
      const isMobileBrowser =
        /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(
          navigator.userAgent,
        );
      // 检测URL参数是否包含h5模式
      const urlParams = new URLSearchParams(window.location.search);
      const isH5Param = urlParams.get("h5") === "true";

      setIsH5(isMobile || isMobileBrowser || isH5Param);
    };

    window.addEventListener("resize", checkH5Mode);

    return () => window.removeEventListener("resize", checkH5Mode);
  }, []);

  return isH5;
};

// 简化的路由保护组件 - 使用 React Router 导航避免循环
const AccessDenied = () => (
  <div className="flex min-h-[50vh] items-center justify-center px-4">
    <div className="w-full max-w-md border border-divider bg-content1 px-5 py-6 text-center">
      <h1 className="text-base font-semibold text-foreground">
        当前账号没有管理员权限
      </h1>
      <p className="mt-2 text-sm leading-6 text-default-500">
        这个页面只允许管理员打开。普通用户可以继续使用已授权的转发、私人代理、内网映射和家庭网络中转。
      </p>
    </div>
  </div>
);

const ProtectedRoute = ({
  children,
  useSimpleLayout = false,
  skipLayout = false,
  adminOnly = false,
}: {
  children: React.ReactNode;
  useSimpleLayout?: boolean;
  skipLayout?: boolean;
  adminOnly?: boolean;
}) => {
  const authenticated = isLoggedIn();
  const allowed = !adminOnly || isAdmin();
  const isH5 = useH5Mode();
  const navigate = useNavigate();

  useEffect(() => {
    if (!authenticated) {
      // 使用 React Router 导航，避免无限跳转
      navigate("/", { replace: true });
    }
  }, [authenticated, navigate]);

  if (!authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-white dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200" />
      </div>
    );
  }

  const content = (
    <PageErrorBoundary>
      <Suspense fallback={<PageLoading />}>
        {allowed ? children : <AccessDenied />}
      </Suspense>
    </PageErrorBoundary>
  );

  // 如果跳过布局，直接返回子组件
  if (skipLayout) {
    return content;
  }

  // 根据模式和页面类型选择布局
  let Layout;

  if (isH5 && useSimpleLayout) {
    Layout = H5SimpleLayout;
  } else if (isH5) {
    Layout = H5Layout;
  } else {
    Layout = AdminLayout;
  }

  return <Layout>{content}</Layout>;
};

// 登录页面路由组件 - 已登录则重定向到dashboard
const LoginRoute = () => {
  const authenticated = isLoggedIn();
  const navigate = useNavigate();

  useEffect(() => {
    if (authenticated) {
      // 使用 React Router 导航，避免无限跳转
      navigate("/dashboard", { replace: true });
    }
  }, [authenticated, navigate]);

  if (authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-100 dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200" />
      </div>
    );
  }

  return <IndexPage />;
};

const PageLoading = () => (
  <div className="flex min-h-[50vh] items-center justify-center text-sm text-default-500">
    正在打开页面...
  </div>
);

class PageErrorBoundary extends Component<
  { children: React.ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  render() {
    if (!this.state.error) return this.props.children;

    return (
      <div className="flex min-h-[50vh] items-center justify-center px-4">
        <div className="w-full max-w-lg border border-danger-200 bg-danger-50 px-5 py-6 text-center text-danger-800 dark:border-danger-500/20 dark:bg-danger-500/10 dark:text-danger-200">
          <h1 className="text-base font-semibold">页面加载失败</h1>
          <p className="mt-2 text-sm leading-6">
            当前页面模块没有正确打开，请刷新后重试；其他页面不受影响。
          </p>
          <button
            className="mt-4 rounded-md bg-danger px-4 py-2 text-sm font-medium text-white"
            type="button"
            onClick={() => window.location.reload()}
          >
            刷新页面
          </button>
        </div>
      </div>
    );
  }
}

function App() {
  // 立即设置页面标题（使用已从缓存读取的配置）
  useEffect(() => {
    document.title = siteConfig.name;

    // 异步检查是否有配置更新
    const checkTitleUpdate = async () => {
      try {
        const cachedAppName = await getCachedConfig("app_name");

        if (cachedAppName && cachedAppName !== document.title) {
          document.title = cachedAppName;
        }
      } catch (error) {
        console.warn("检查标题更新失败:", error);
      }
    };

    // 延迟检查，避免阻塞初始渲染
    const timer = setTimeout(checkTitleUpdate, 100);

    return () => clearTimeout(timer);
  }, []);

  return (
    <PageErrorBoundary>
      <Suspense fallback={<PageLoading />}>
        <Routes>
          <Route element={<LoginRoute />} path="/" />
          <Route
            element={
              <ProtectedRoute skipLayout={true}>
                <ChangePasswordPage />
              </ProtectedRoute>
            }
            path="/change-password"
          />
          <Route
            element={
              <ProtectedRoute>
                <DashboardPage />
              </ProtectedRoute>
            }
            path="/dashboard"
          />
          <Route
            element={
              <ProtectedRoute>
                <ForwardPage />
              </ProtectedRoute>
            }
            path="/forward"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <RoutingOverviewPage />
              </ProtectedRoute>
            }
            path="/routing-overview"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <NftForwardPage />
              </ProtectedRoute>
            }
            path="/nft-forward"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <SmartEntryPage />
              </ProtectedRoute>
            }
            path="/smart-entry"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <CrossEntryFailoverPage />
              </ProtectedRoute>
            }
            path="/cross-entry-failover"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <SourceIpEntryPage />
              </ProtectedRoute>
            }
            path="/source-ip-entry"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <TopologyPage />
              </ProtectedRoute>
            }
            path="/topology"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <SystemSelfCheckPage />
              </ProtectedRoute>
            }
            path="/system-self-check"
          />
          <Route
            element={
              <ProtectedRoute adminOnly useSimpleLayout={true}>
                <DnsSettingsPage />
              </ProtectedRoute>
            }
            path="/dns-settings"
          />
          <Route
            element={
              <ProtectedRoute adminOnly useSimpleLayout={true}>
                <AwsAccessPage />
              </ProtectedRoute>
            }
            path="/aws-access"
          />
          <Route
            element={
              <ProtectedRoute adminOnly useSimpleLayout={true}>
                <DynamicDnsPage />
              </ProtectedRoute>
            }
            path="/dynamic-dns"
          />
          <Route
            element={
              <ProtectedRoute adminOnly useSimpleLayout={true}>
                <ServerAssetsPage />
              </ProtectedRoute>
            }
            path="/server-assets"
          />
          <Route
            element={
              <ProtectedRoute>
                <TunnelPage />
              </ProtectedRoute>
            }
            path="/tunnel"
          />
          <Route
            element={
              <ProtectedRoute>
                <NodePage />
              </ProtectedRoute>
            }
            path="/node"
          />
          <Route
            element={
              <ProtectedRoute>
                <NodeTerminalPage />
              </ProtectedRoute>
            }
            path="/node/:nodeId/terminal"
          />
          <Route
            element={
              <ProtectedRoute adminOnly useSimpleLayout={true}>
                <UserPage />
              </ProtectedRoute>
            }
            path="/user"
          />
          <Route
            element={
              <ProtectedRoute>
                <ProfilePage />
              </ProtectedRoute>
            }
            path="/profile"
          />
          <Route
            element={
              <ProtectedRoute useSimpleLayout={true}>
                <Navigate replace to="/user" />
              </ProtectedRoute>
            }
            path="/limit"
          />
          <Route
            element={
              <ProtectedRoute adminOnly useSimpleLayout={true}>
                <ConfigPage />
              </ProtectedRoute>
            }
            path="/config"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <MonitoringPage />
              </ProtectedRoute>
            }
            path="/monitoring"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <UpdatePage />
              </ProtectedRoute>
            }
            path="/update"
          />
          <Route
            element={
              <ProtectedRoute>
                <ServicePublishingPage />
              </ProtectedRoute>
            }
            path="/service-publishing"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <DockerAppsPage />
              </ProtectedRoute>
            }
            path="/docker-apps"
          />
          <Route
            element={
              <ProtectedRoute>
                <PrivateProxyPage />
              </ProtectedRoute>
            }
            path="/private-proxy"
          />
          <Route
            element={
              <ProtectedRoute>
                <ProtocolProbePage />
              </ProtectedRoute>
            }
            path="/protocol-probe"
          />
          <Route
            element={
              <ProtectedRoute>
                <HomeAccessPage />
              </ProtectedRoute>
            }
            path="/home-access"
          />
          <Route
            element={
              <ProtectedRoute useSimpleLayout={true}>
                <HomeDevicesPage />
              </ProtectedRoute>
            }
            path="/home-devices"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <NetworkToolsPage />
              </ProtectedRoute>
            }
            path="/network-tools"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <QualityLabPage />
              </ProtectedRoute>
            }
            path="/quality-lab"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <BandwidthTestPage />
              </ProtectedRoute>
            }
            path="/bandwidth-test"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <ClientSpeedTestPage />
              </ProtectedRoute>
            }
            path="/client-speed-test"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <UdpQuicDiagnosticPage />
              </ProtectedRoute>
            }
            path="/udp-quic-diagnostic"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <MultiLineAggregationPage />
              </ProtectedRoute>
            }
            path="/multi-line-aggregation"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <IpQualityPage />
              </ProtectedRoute>
            }
            path="/ip-quality"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <Navigate replace to="/private-network?section=virtual-lan" />
              </ProtectedRoute>
            }
            path="/virtual-lan"
          />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <PrivateNetworkPage />
              </ProtectedRoute>
            }
            path="/private-network"
          />
          <Route
            element={
              <ProtectedRoute useSimpleLayout={true}>
                <GuidePage />
              </ProtectedRoute>
            }
            path="/guide"
          />
          <Route
            element={
              <ProtectedRoute adminOnly useSimpleLayout={true}>
                <PortResourcesPage />
              </ProtectedRoute>
            }
            path="/port-resources"
          />
          <Route element={<PanelAddressPage />} path="/panel-addresses" />
          <Route
            element={
              <ProtectedRoute adminOnly>
                <Navigate replace to="/panel-addresses" />
              </ProtectedRoute>
            }
            path="/settings"
          />
        </Routes>
      </Suspense>
    </PageErrorBoundary>
  );
}

export default App;
