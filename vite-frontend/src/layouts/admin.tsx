import React, { useState, useEffect } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import { Button } from "@heroui/button";
import { Tooltip } from "@heroui/tooltip";
import {
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter,
  useDisclosure,
} from "@heroui/modal";
import { Input } from "@heroui/input";
import { toast } from "react-hot-toast";
import {
  Activity,
  ArrowRightLeft,
  BellRing,
  BookOpen,
  Boxes,
  ChevronDown,
  ChevronRight,
  Combine,
  FlaskConical,
  Gauge,
  Home,
  House,
  KeyRound,
  LockKeyhole,
  LogOut,
  MapPinned,
  Menu,
  MonitorCog,
  Moon,
  Network,
  PanelLeftClose,
  PanelLeftOpen,
  RadioTower,
  SearchCheck,
  Server,
  ShieldCheck,
  Sun,
  Waypoints,
  Wrench,
} from "lucide-react";

import { Logo } from "@/components/icons";
import { updatePassword } from "@/api";
import { safeLogout } from "@/utils/logout";
import { siteConfig } from "@/config/site";
import { useAlertUnreadCount } from "@/hooks/use-alert-unread-count";
import { preloadRoute } from "@/utils/route-preload";
import { getCurrentUsername, isAdmin as isAdminUser } from "@/utils/auth";
import {
  type ThemePreference,
  setThemePreference,
  useThemePreference,
} from "@/components/theme-provider";

interface MenuItem {
  path: string;
  label: string;
  icon: React.ReactNode;
  adminOnly?: boolean;
  activePaths?: string[];
}

interface PasswordForm {
  newUsername: string;
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export default function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const { isOpen, onOpen, onOpenChange } = useDisclosure();

  const [isMobile, setIsMobile] = useState(false);
  const [mobileMenuVisible, setMobileMenuVisible] = useState(false);
  const [username, setUsername] = useState("");
  const [isAdmin, setIsAdmin] = useState(false);
  const [passwordLoading, setPasswordLoading] = useState(false);
  const [collapsedGroups, setCollapsedGroups] = useState<
    Record<string, boolean>
  >({});
  const { count: unreadAlertCount } = useAlertUnreadCount();
  const themePreference = useThemePreference();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => {
    try {
      return localStorage.getItem("cloudnest.admin.sidebar-collapsed") === "1";
    } catch {
      return false;
    }
  });
  const [passwordForm, setPasswordForm] = useState<PasswordForm>({
    newUsername: "",
    currentPassword: "",
    newPassword: "",
    confirmPassword: "",
  });

  // 菜单项配置
  const menuItems: MenuItem[] = [
    {
      path: "/dashboard",
      label: "仪表板",
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" />
        </svg>
      ),
    },
    {
      path: "/routing-overview",
      label: "线路调度中心",
      icon: <Waypoints className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/forward",
      label: "转发管理",
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path
            clipRule="evenodd"
            d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z"
            fillRule="evenodd"
          />
        </svg>
      ),
    },
    {
      path: "/nft-forward",
      label: "nftables 转发",
      icon: <ArrowRightLeft className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/monitoring",
      label: "告警中心",
      icon: <BellRing className="h-5 w-5" />,
    },
    {
      path: "/smart-entry",
      label: "三网优化",
      icon: <Waypoints className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/source-ip-entry",
      label: "来源 IP 分流",
      icon: <RadioTower className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/cross-entry-failover",
      label: "入口容灾",
      icon: <ShieldCheck className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/authorized-entry",
      label: "授权入口",
      icon: <ShieldCheck className="h-5 w-5" />,
    },
    {
      path: "/topology",
      label: "全链路拓扑",
      icon: <Network className="h-5 w-5" />,
    },
    {
      path: "/service-publishing",
      label: "内网映射",
      icon: <RadioTower className="h-5 w-5" />,
    },
    {
      path: "/docker-apps",
      label: "Docker 应用中心",
      icon: <Boxes className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/private-proxy",
      label: "私人代理",
      icon: <LockKeyhole className="h-5 w-5" />,
    },
    {
      path: "/exit-pool",
      label: "家宽出口池",
      icon: <House className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/home-access",
      label: "家庭网络中转",
      icon: <Home className="h-5 w-5" />,
    },
    {
      path: "/network-tools",
      label: "网络诊断",
      icon: <Wrench className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/quality-lab",
      label: "网络质量实验室",
      icon: <FlaskConical className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/bandwidth-test",
      label: "真实带宽测试",
      icon: <Gauge className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/client-speed-test",
      label: "本机单线程测速",
      icon: <Activity className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/udp-quic-diagnostic",
      label: "UDP / QUIC 诊断",
      icon: <RadioTower className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/multi-line-aggregation",
      label: "多线路并发调度",
      icon: <Combine className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/ip-quality",
      label: "IP 质量检测",
      icon: <MapPinned className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/private-network",
      label: "内网组建与出口",
      icon: <Waypoints className="h-5 w-5" />,
      activePaths: ["/private-network", "/virtual-lan"],
      adminOnly: true,
    },
    {
      path: "/tunnel",
      label: "隧道组建",
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path
            clipRule="evenodd"
            d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z"
            fillRule="evenodd"
          />
        </svg>
      ),
    },
    {
      path: "/node",
      label: "添加节点",
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path
            clipRule="evenodd"
            d="M3 3a1 1 0 000 2v8a2 2 0 002 2h2.586l-1.293 1.293a1 1 0 101.414 1.414L10 15.414l2.293 2.293a1 1 0 001.414-1.414L12.414 15H15a2 2 0 002-2V5a1 1 0 100-2H3zm11.707 4.707a1 1 0 00-1.414-1.414L10 9.586 8.707 8.293a1 1 0 00-1.414 0l-2 2a1 1 0 101.414 1.414L8 10.414l1.293 1.293a1 1 0 001.414 0l4-4z"
            fillRule="evenodd"
          />
        </svg>
      ),
    },
    {
      path: "/port-resources",
      label: "资源中心",
      icon: <Boxes className="h-5 w-5" />,
      activePaths: [
        "/port-resources",
        "/home-devices",
        "/dns-settings",
        "/dynamic-dns",
        "/aws-access",
      ],
      adminOnly: true,
    },
    {
      path: "/server-assets",
      label: "服务器资产",
      icon: <Server className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/system-self-check",
      label: "全系统自检",
      icon: <SearchCheck className="h-5 w-5" />,
      adminOnly: true,
    },
    {
      path: "/user",
      label: "用户管理",
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M9 6a3 3 0 11-6 0 3 3 0 016 0zM17 6a3 3 0 11-6 0 3 3 0 016 0zM12.93 17c.046-.327.07-.66.07-1a6.97 6.97 0 00-1.5-4.33A5 5 0 0119 16v1h-6.07zM6 11a5 5 0 015 5v1H1v-1a5 5 0 015-5z" />
        </svg>
      ),
      adminOnly: true,
    },
    {
      path: "/config",
      label: "网站设置",
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path
            clipRule="evenodd"
            d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z"
            fillRule="evenodd"
          />
        </svg>
      ),
      adminOnly: true,
    },
    {
      path: "/update",
      label: "检查更新",
      icon: (
        <svg
          className="w-5 h-5"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          viewBox="0 0 24 24"
        >
          <path
            d="M20 11a8.1 8.1 0 00-14.8-4.3L3 9m0 0V4m0 5h5M4 13a8.1 8.1 0 0014.8 4.3L21 15m0 0v5m0-5h-5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      ),
      adminOnly: true,
    },
    {
      path: "/guide",
      label: "使用教程",
      icon: <BookOpen className="h-5 w-5" />,
    },
  ];

  const menuGroups = [
    {
      label: "核心业务",
      paths: ["/dashboard", "/node", "/tunnel", "/forward", "/nft-forward"],
    },
    {
      label: "线路调度",
      paths: [
        "/routing-overview",
        "/smart-entry",
        "/source-ip-entry",
        "/cross-entry-failover",
        "/multi-line-aggregation",
      ],
    },
    {
      label: "资源与服务",
      paths: [
        "/port-resources",
        "/service-publishing",
        "/docker-apps",
        "/private-proxy",
        "/exit-pool",
        "/home-access",
        "/private-network",
      ],
    },
    {
      label: "用户与授权",
      paths: ["/user", "/authorized-entry"],
    },
    {
      label: "监控与检测",
      paths: [
        "/monitoring",
        "/topology",
        "/network-tools",
        "/quality-lab",
        "/bandwidth-test",
        "/client-speed-test",
        "/udp-quic-diagnostic",
        "/ip-quality",
        "/system-self-check",
      ],
    },
    { label: "系统管理", paths: ["/server-assets", "/config", "/update"] },
    { label: "帮助", paths: ["/guide"] },
  ];

  // 检查移动端
  const checkMobile = () => {
    setIsMobile(window.innerWidth <= 768);
    if (window.innerWidth > 768) {
      setMobileMenuVisible(false);
    }
  };

  useEffect(() => {
    // 以当前有效 JWT 为准，旧 localStorage 标记只作为历史兼容数据。
    setUsername(
      getCurrentUsername() || localStorage.getItem("name") || "Admin",
    );
    setIsAdmin(isAdminUser());

    // 响应式检查
    checkMobile();
    window.addEventListener("resize", checkMobile);

    return () => {
      window.removeEventListener("resize", checkMobile);
    };
  }, []);

  useEffect(() => {
    try {
      const saved = JSON.parse(
        localStorage.getItem("cloudnest.admin.collapsed-groups") || "{}",
      );

      if (saved && typeof saved === "object") {
        setCollapsedGroups(saved);
      }
    } catch {
      // Ignore an invalid preference and use the expanded default.
    }
  }, []);

  // 退出登录
  const handleLogout = () => {
    safeLogout();
    navigate("/");
  };

  // 切换移动端菜单
  const toggleMobileMenu = () => {
    setMobileMenuVisible(!mobileMenuVisible);
  };

  // 隐藏移动端菜单
  const hideMobileMenu = () => {
    setMobileMenuVisible(false);
  };

  // 菜单点击处理
  const handleMenuClick = (path: string) => {
    navigate(path);
    if (isMobile) {
      hideMobileMenu();
    }
  };

  const toggleSidebar = (collapsed: boolean) => {
    setSidebarCollapsed(collapsed);
    try {
      localStorage.setItem(
        "cloudnest.admin.sidebar-collapsed",
        collapsed ? "1" : "0",
      );
    } catch {
      // Keep the choice for this page only.
    }
  };

  const themeOrder: ThemePreference[] = ["system", "light", "dark"];
  const themeLabel: Record<ThemePreference, string> = {
    system: "跟随系统",
    light: "浅色",
    dark: "深色",
  };
  const themeIcon: Record<ThemePreference, React.ReactNode> = {
    system: <MonitorCog className="h-5 w-5" />,
    light: <Sun className="h-5 w-5" />,
    dark: <Moon className="h-5 w-5" />,
  };
  const cycleTheme = () => {
    const next =
      themeOrder[(themeOrder.indexOf(themePreference) + 1) % themeOrder.length];

    setThemePreference(next);
  };

  const toggleGroup = (label: string) => {
    setCollapsedGroups((current) => {
      const next = { ...current, [label]: !current[label] };

      localStorage.setItem(
        "cloudnest.admin.collapsed-groups",
        JSON.stringify(next),
      );

      return next;
    });
  };

  // 密码表单验证
  const validatePasswordForm = (): boolean => {
    if (!passwordForm.newUsername.trim()) {
      toast.error("请输入新用户名");

      return false;
    }
    if (passwordForm.newUsername.length < 3) {
      toast.error("用户名长度至少3位");

      return false;
    }
    if (!passwordForm.currentPassword) {
      toast.error("请输入当前密码");

      return false;
    }
    if (!passwordForm.newPassword) {
      toast.error("请输入新密码");

      return false;
    }
    if (passwordForm.newPassword.length < 6) {
      toast.error("新密码长度不能少于6位");

      return false;
    }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      toast.error("两次输入密码不一致");

      return false;
    }

    return true;
  };

  // 提交密码修改
  const handlePasswordSubmit = async () => {
    if (!validatePasswordForm()) return;

    setPasswordLoading(true);
    try {
      const response = await updatePassword(passwordForm);

      if (response.code === 0) {
        toast.success("密码修改成功，请重新登录");
        onOpenChange();
        handleLogout();
      } else {
        toast.error(response.msg || "密码修改失败");
      }
    } catch (error) {
      toast.error("修改密码时发生错误");
      console.error("修改密码错误:", error);
    } finally {
      setPasswordLoading(false);
    }
  };

  // 重置密码表单
  const resetPasswordForm = () => {
    setPasswordForm({
      newUsername: "",
      currentPassword: "",
      newPassword: "",
      confirmPassword: "",
    });
  };

  // 过滤菜单项（根据权限）
  const filteredMenuGroups = menuGroups
    .map((group) => ({
      ...group,
      items: group.paths
        .map((path) => menuItems.find((item) => item.path === path))
        .filter((item): item is MenuItem => Boolean(item))
        .filter((item) => !item.adminOnly || isAdmin),
    }))
    .filter((group) => group.items.length > 0);

  const isItemActive = (item: MenuItem) =>
    (item.activePaths || [item.path]).includes(location.pathname);
  const showLabels = isMobile || !sidebarCollapsed;

  return (
    <div
      className={`flex ${isMobile ? "min-h-screen" : "h-screen"} bg-background`}
    >
      {/* 移动端遮罩层 */}
      {isMobile && mobileMenuVisible && (
        <div
          className="fixed inset-0 z-40 bg-black/30 backdrop-blur-sm"
          onClick={hideMobileMenu}
        />
      )}

      {/* 左侧菜单栏：样式参考 NodePassDash 的侧栏布局 */}
      <aside
        className={`
        ${isMobile ? "fixed top-0 left-0 h-screen" : "relative h-full"}
        ${isMobile && !mobileMenuVisible ? "-translate-x-full" : "translate-x-0"}
        ${isMobile ? "w-72 bg-background" : sidebarCollapsed ? "w-[83px]" : "w-72"}
        z-50 flex shrink-0 flex-col border-r border-divider/60
        bg-default-50/80 backdrop-blur-md dark:bg-default-100/30
        transition-[transform,width] duration-300 ease-in-out
      `}
      >
        {/* Logo 区域 */}
        <div
          className={`flex h-16 items-center gap-3 px-5 ${showLabels ? "" : "justify-center px-0"}`}
        >
          <Logo size={26} />
          {showLabels && (
            <div className="min-w-0 flex-1">
              <h1 className="truncate text-sm font-bold text-foreground">
                {siteConfig.name}
              </h1>
              <p className="text-xs text-default-500">v{siteConfig.version}</p>
            </div>
          )}
          {!isMobile && showLabels && (
            <button
              aria-label="收起侧栏"
              className="flex text-default-500 transition-colors hover:text-foreground"
              type="button"
              onClick={() => toggleSidebar(true)}
            >
              <PanelLeftClose className="h-5 w-5" />
            </button>
          )}
        </div>

        {/* 菜单导航 */}
        <nav
          className={`scrollbar-thin flex-1 overflow-y-auto py-3 ${showLabels ? "px-4" : "px-[14px]"}`}
        >
          <div className={showLabels ? "space-y-5" : "space-y-1"}>
            {filteredMenuGroups.map((group) => {
              const hasActive = group.items.some(isItemActive);
              const expanded =
                !showLabels ||
                collapsedGroups[group.label] !== true ||
                hasActive;

              return (
                <section key={group.label} aria-label={group.label}>
                  {showLabels && (
                    <button
                      aria-expanded={expanded}
                      className="mb-1 flex min-h-7 w-full items-center gap-1.5 rounded-lg px-3 text-left text-[11px] font-semibold tracking-wide text-default-400 transition-colors hover:text-default-600"
                      type="button"
                      onClick={() => toggleGroup(group.label)}
                    >
                      {expanded ? (
                        <ChevronDown className="h-3.5 w-3.5" />
                      ) : (
                        <ChevronRight className="h-3.5 w-3.5" />
                      )}
                      <span>{group.label}</span>
                    </button>
                  )}
                  {expanded && (
                    <ul className="space-y-1">
                      {group.items.map((item) => {
                        const isActive = isItemActive(item);
                        const badge =
                          item.path === "/monitoring" && unreadAlertCount > 0
                            ? unreadAlertCount > 99
                              ? "99+"
                              : String(unreadAlertCount)
                            : "";

                        return (
                          <li key={item.path}>
                            <Tooltip
                              content={item.label}
                              isDisabled={showLabels}
                              placement="right"
                            >
                              <button
                                className={`
                                relative flex h-11 w-full items-center gap-3 rounded-xl text-left text-sm font-medium
                                transition-all duration-200
                                ${showLabels ? "px-3" : "justify-center px-0"}
                                ${
                                  isActive
                                    ? "bg-foreground text-background shadow-small"
                                    : "text-default-600 hover:bg-background hover:text-foreground dark:hover:bg-default-100"
                                }
                              `}
                                onClick={() => handleMenuClick(item.path)}
                                onFocus={() => preloadRoute(item.path)}
                                onMouseEnter={() => preloadRoute(item.path)}
                              >
                                <span className="flex-shrink-0">
                                  {item.icon}
                                </span>
                                {showLabels && (
                                  <span className="min-w-0 flex-1 truncate">
                                    {item.label}
                                  </span>
                                )}
                                {badge &&
                                  (showLabels ? (
                                    <span className="inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-danger px-1.5 text-[11px] font-semibold text-white">
                                      {badge}
                                    </span>
                                  ) : (
                                    <span className="absolute right-2 top-2 h-2 w-2 rounded-full bg-danger" />
                                  ))}
                              </button>
                            </Tooltip>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </section>
              );
            })}
          </div>
        </nav>

        {/* 底部：主题、账号、退出 */}
        <div
          className={`flex flex-col gap-1 border-t border-divider/60 py-3 ${showLabels ? "px-4" : "items-center px-[14px]"}`}
        >
          {!isMobile && !showLabels && (
            <Tooltip content="展开侧栏" placement="right">
              <Button
                isIconOnly
                aria-label="展开侧栏"
                className="h-11 w-11 text-default-600"
                radius="lg"
                variant="light"
                onPress={() => toggleSidebar(false)}
              >
                <PanelLeftOpen className="h-5 w-5" />
              </Button>
            </Tooltip>
          )}
          {[
            {
              key: "theme",
              label: `外观：${themeLabel[themePreference]}`,
              icon: themeIcon[themePreference],
              onPress: cycleTheme,
              danger: false,
            },
            {
              key: "password",
              label: `账号：${username}`,
              icon: <KeyRound className="h-5 w-5" />,
              onPress: onOpen,
              danger: false,
            },
            {
              key: "logout",
              label: "退出登录",
              icon: <LogOut className="h-5 w-5" />,
              onPress: handleLogout,
              danger: true,
            },
          ].map((action) => (
            <Tooltip
              key={action.key}
              content={action.label}
              isDisabled={showLabels}
              placement="right"
            >
              <Button
                className={`truncate ${showLabels ? "justify-start" : "h-11 w-11 justify-center px-0"} ${
                  action.danger
                    ? "text-danger"
                    : "text-default-600 hover:text-primary"
                }`}
                color={action.danger ? "danger" : "default"}
                fullWidth={showLabels}
                isIconOnly={!showLabels}
                radius="lg"
                variant="light"
                onPress={action.onPress}
              >
                {action.icon}
                {showLabels && <span className="truncate">{action.label}</span>}
              </Button>
            </Tooltip>
          ))}
        </div>
      </aside>

      {/* 主内容区域 */}
      <div
        className={`flex min-w-0 flex-1 flex-col ${isMobile ? "min-h-0" : "h-full overflow-hidden"}`}
      >
        {/* 移动端顶栏 */}
        {isMobile && (
          <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-divider/60 bg-background/80 px-4 backdrop-blur-md">
            <Button
              isIconOnly
              aria-label="打开菜单"
              variant="light"
              onPress={toggleMobileMenu}
            >
              <Menu className="h-5 w-5" />
            </Button>
            <Logo size={22} />
            <span className="text-sm font-bold text-foreground">
              {siteConfig.name}
            </span>
          </header>
        )}

        {/* 主内容 */}
        <main
          className={`scrollbar-thin flex flex-1 flex-col ${isMobile ? "" : "overflow-y-auto"}`}
        >
          <div key={location.pathname} className="flex-1 animate-page-enter">
            {children}
          </div>
          <footer className="flex w-full items-center justify-center py-4 text-xs text-default-500">
            {siteConfig.name} © {new Date().getFullYear()} | v
            {siteConfig.version} | Powered by{" "}
            <a
              className="ml-1 text-primary transition-colors hover:text-primary-600"
              href="https://github.com/NorwayXZ/flux-panel"
              rel="noopener noreferrer"
              target="_blank"
            >
              WhySoQuiet
            </a>
          </footer>
        </main>
      </div>

      {/* 修改密码弹窗 */}
      <Modal
        backdrop="blur"
        isOpen={isOpen}
        placement="center"
        scrollBehavior="outside"
        size="2xl"
        onOpenChange={() => {
          onOpenChange();
          resetPasswordForm();
        }}
      >
        <ModalContent>
          {(onClose: () => void) => (
            <>
              <ModalHeader className="flex flex-col gap-1">
                修改密码
              </ModalHeader>
              <ModalBody>
                <div className="space-y-4">
                  <Input
                    label="新用户名"
                    placeholder="请输入新用户名（至少3位）"
                    value={passwordForm.newUsername}
                    variant="bordered"
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                      setPasswordForm((prev) => ({
                        ...prev,
                        newUsername: e.target.value,
                      }))
                    }
                  />
                  <Input
                    label="当前密码"
                    placeholder="请输入当前密码"
                    type="password"
                    value={passwordForm.currentPassword}
                    variant="bordered"
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                      setPasswordForm((prev) => ({
                        ...prev,
                        currentPassword: e.target.value,
                      }))
                    }
                  />
                  <Input
                    label="新密码"
                    placeholder="请输入新密码（至少6位）"
                    type="password"
                    value={passwordForm.newPassword}
                    variant="bordered"
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                      setPasswordForm((prev) => ({
                        ...prev,
                        newPassword: e.target.value,
                      }))
                    }
                  />
                  <Input
                    label="确认密码"
                    placeholder="请再次输入新密码"
                    type="password"
                    value={passwordForm.confirmPassword}
                    variant="bordered"
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                      setPasswordForm((prev) => ({
                        ...prev,
                        confirmPassword: e.target.value,
                      }))
                    }
                  />
                </div>
              </ModalBody>
              <ModalFooter>
                <Button color="default" variant="light" onPress={onClose}>
                  取消
                </Button>
                <Button
                  color="primary"
                  isLoading={passwordLoading}
                  onPress={handlePasswordSubmit}
                >
                  确定
                </Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}
