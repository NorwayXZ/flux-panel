import { Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { lazy, Suspense, useEffect, useState } from "react";

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
const CrossEntryFailoverPage = lazy(() => import("@/pages/cross-entry-failover"));
const SourceIpEntryPage = lazy(() => import("@/pages/source-ip-entry"));
const SmartEntryPage = lazy(() => import("@/pages/smart-entry"));
const DnsSettingsPage = lazy(() => import("@/pages/dns-settings"));
const AwsAccessPage = lazy(() => import("@/pages/aws-access"));
const PrivateProxyPage = lazy(() => import("@/pages/private-proxy"));
const NetworkToolsPage = lazy(() => import("@/pages/network-tools"));
const QualityLabPage = lazy(() => import("@/pages/quality-lab"));
const BandwidthTestPage = lazy(() => import("@/pages/bandwidth-test"));
const UdpQuicDiagnosticPage = lazy(() => import("@/pages/udp-quic-diagnostic"));
const MultiLineAggregationPage = lazy(() => import("@/pages/multi-line-aggregation"));
const IpQualityPage = lazy(() => import("@/pages/ip-quality"));
const PrivateNetworkPage = lazy(() => import("@/pages/private-network"));
const ServerAssetsPage = lazy(() => import("@/pages/server-assets"));
const DynamicDnsPage = lazy(() => import("@/pages/dynamic-dns"));
const HomeAccessPage = lazy(() => import("@/pages/home-access"));
const HomeDevicesPage = lazy(() => import("@/pages/home-devices"));
const GuidePage = lazy(() => import("@/pages/guide"));
const SystemSelfCheckPage = lazy(() => import("@/pages/system-self-check"));
const PanelAddressPage = lazy(() =>
  import("@/pages/settings").then(module => ({ default: module.SettingsPage }))
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
    const isMobileBrowser = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
    // 检测URL参数是否包含h5模式
    const urlParams = new URLSearchParams(window.location.search);
    const isH5Param = urlParams.get('h5') === 'true';
    
    return isMobile || isMobileBrowser || isH5Param;
  };

  const [isH5, setIsH5] = useState(getInitialH5Mode);

  useEffect(() => {
    const checkH5Mode = () => {
      // 检测移动设备或小屏幕
      const isMobile = window.innerWidth <= 768;
      // 检测是否为移动端浏览器
      const isMobileBrowser = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
      // 检测URL参数是否包含h5模式
      const urlParams = new URLSearchParams(window.location.search);
      const isH5Param = urlParams.get('h5') === 'true';
      
      setIsH5(isMobile || isMobileBrowser || isH5Param);
    };

    window.addEventListener('resize', checkH5Mode);
    
    return () => window.removeEventListener('resize', checkH5Mode);
  }, []);

  return isH5;
};

// 简化的路由保护组件 - 使用 React Router 导航避免循环
const AccessDenied = () => (
  <div className="flex min-h-[50vh] items-center justify-center px-4">
    <div className="w-full max-w-md border border-divider bg-content1 px-5 py-6 text-center">
      <h1 className="text-base font-semibold text-foreground">当前账号没有管理员权限</h1>
      <p className="mt-2 text-sm leading-6 text-default-500">这个页面只允许管理员打开。普通用户可以继续使用已授权的转发、私人代理、内网映射和家庭网络中转。</p>
    </div>
  </div>
);

const ProtectedRoute = ({
  children,
  useSimpleLayout = false,
  skipLayout = false,
  adminOnly = false
}: {
  children: React.ReactNode,
  useSimpleLayout?: boolean,
  skipLayout?: boolean,
  adminOnly?: boolean
}) => {
  const authenticated = isLoggedIn();
  const allowed = !adminOnly || isAdmin();
  const isH5 = useH5Mode();
  const navigate = useNavigate();
  
  useEffect(() => {
    if (!authenticated) {
      // 使用 React Router 导航，避免无限跳转
      navigate('/', { replace: true });
    }
  }, [authenticated, navigate]);

  if (!authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-white dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200"></div>
      </div>
    );
  }

  const content = <Suspense fallback={<PageLoading />}>{allowed ? children : <AccessDenied />}</Suspense>;

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
      navigate('/dashboard', { replace: true });
    }
  }, [authenticated, navigate]);
  
  if (authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-100 dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200"></div>
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

function App() {
  // 立即设置页面标题（使用已从缓存读取的配置）
  useEffect(() => {
    document.title = siteConfig.name;
    
    // 异步检查是否有配置更新
    const checkTitleUpdate = async () => {
      try {
        const cachedAppName = await getCachedConfig('app_name');
        if (cachedAppName && cachedAppName !== document.title) {
          document.title = cachedAppName;
        }
      } catch (error) {
        console.warn('检查标题更新失败:', error);
      }
    };

    // 延迟检查，避免阻塞初始渲染
    const timer = setTimeout(checkTitleUpdate, 100);

    return () => clearTimeout(timer);
  }, []);

  return (
    <Suspense fallback={<PageLoading />}>
      <Routes>
      <Route path="/" element={<LoginRoute />} />
      <Route 
        path="/change-password" 
        element={
          <ProtectedRoute skipLayout={true}>
            <ChangePasswordPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/dashboard" 
        element={
          <ProtectedRoute>
            <DashboardPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/forward" 
        element={
          <ProtectedRoute>
            <ForwardPage />
          </ProtectedRoute>
        } 
      />
      <Route path="/routing-overview" element={<ProtectedRoute adminOnly><RoutingOverviewPage /></ProtectedRoute>} />
      <Route path="/nft-forward" element={<ProtectedRoute adminOnly><NftForwardPage /></ProtectedRoute>} />
      <Route
        path="/smart-entry"
        element={<ProtectedRoute adminOnly><SmartEntryPage /></ProtectedRoute>}
      />
      <Route
        path="/cross-entry-failover"
        element={<ProtectedRoute adminOnly><CrossEntryFailoverPage /></ProtectedRoute>}
      />
      <Route
        path="/source-ip-entry"
        element={<ProtectedRoute adminOnly><SourceIpEntryPage /></ProtectedRoute>}
      />
      <Route path="/topology" element={<ProtectedRoute adminOnly><TopologyPage /></ProtectedRoute>} />
      <Route path="/system-self-check" element={<ProtectedRoute adminOnly><SystemSelfCheckPage /></ProtectedRoute>} />
      <Route
        path="/dns-settings"
        element={<ProtectedRoute useSimpleLayout={true} adminOnly><DnsSettingsPage /></ProtectedRoute>}
      />
      <Route path="/aws-access" element={<ProtectedRoute useSimpleLayout={true} adminOnly><AwsAccessPage /></ProtectedRoute>} />
      <Route path="/dynamic-dns" element={<ProtectedRoute useSimpleLayout={true} adminOnly><DynamicDnsPage /></ProtectedRoute>} />
      <Route path="/server-assets" element={<ProtectedRoute useSimpleLayout={true} adminOnly><ServerAssetsPage /></ProtectedRoute>} />
      <Route 
        path="/tunnel" 
        element={
          <ProtectedRoute>
            <TunnelPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/node" 
        element={
          <ProtectedRoute>
            <NodePage />
          </ProtectedRoute>
        } 
      />
      <Route
        path="/node/:nodeId/terminal"
        element={<ProtectedRoute><NodeTerminalPage /></ProtectedRoute>}
      />
      <Route 
        path="/user" 
        element={
          <ProtectedRoute useSimpleLayout={true} adminOnly>
            <UserPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/profile" 
        element={
          <ProtectedRoute>
            <ProfilePage />
          </ProtectedRoute>
        } 
      />
      <Route path="/limit" element={<ProtectedRoute useSimpleLayout={true}><Navigate to="/user" replace /></ProtectedRoute>} />
      <Route 
        path="/config" 
        element={
          <ProtectedRoute useSimpleLayout={true} adminOnly>
            <ConfigPage />
          </ProtectedRoute>
        } 
      />
      <Route
        path="/monitoring"
        element={
          <ProtectedRoute adminOnly>
            <MonitoringPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/update"
        element={
          <ProtectedRoute adminOnly>
            <UpdatePage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/service-publishing"
        element={<ProtectedRoute><ServicePublishingPage /></ProtectedRoute>}
      />
      <Route path="/docker-apps" element={<ProtectedRoute adminOnly><DockerAppsPage /></ProtectedRoute>} />
      <Route path="/private-proxy" element={<ProtectedRoute><PrivateProxyPage /></ProtectedRoute>} />
      <Route path="/home-access" element={<ProtectedRoute><HomeAccessPage /></ProtectedRoute>} />
      <Route path="/home-devices" element={<ProtectedRoute useSimpleLayout={true}><HomeDevicesPage /></ProtectedRoute>} />
      <Route path="/network-tools" element={<ProtectedRoute adminOnly><NetworkToolsPage /></ProtectedRoute>} />
      <Route path="/quality-lab" element={<ProtectedRoute adminOnly><QualityLabPage /></ProtectedRoute>} />
      <Route path="/bandwidth-test" element={<ProtectedRoute adminOnly><BandwidthTestPage /></ProtectedRoute>} />
      <Route path="/udp-quic-diagnostic" element={<ProtectedRoute adminOnly><UdpQuicDiagnosticPage /></ProtectedRoute>} />
      <Route path="/multi-line-aggregation" element={<ProtectedRoute adminOnly><MultiLineAggregationPage /></ProtectedRoute>} />
      <Route path="/ip-quality" element={<ProtectedRoute adminOnly><IpQualityPage /></ProtectedRoute>} />
      <Route path="/virtual-lan" element={<ProtectedRoute adminOnly><Navigate to="/private-network?section=virtual-lan" replace /></ProtectedRoute>} />
      <Route path="/private-network" element={<ProtectedRoute adminOnly><PrivateNetworkPage /></ProtectedRoute>} />
      <Route path="/guide" element={<ProtectedRoute useSimpleLayout={true}><GuidePage /></ProtectedRoute>} />
      <Route
        path="/port-resources"
        element={<ProtectedRoute useSimpleLayout={true} adminOnly><PortResourcesPage /></ProtectedRoute>}
      />
      <Route path="/panel-addresses" element={<PanelAddressPage />} />
      <Route path="/settings" element={<ProtectedRoute adminOnly><Navigate to="/panel-addresses" replace /></ProtectedRoute>} />
      </Routes>
    </Suspense>
  );
}

export default App;
