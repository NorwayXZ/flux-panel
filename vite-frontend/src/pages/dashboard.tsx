import type { User } from "@/types";

import { Card, CardBody, CardHeader } from "@heroui/card";
import { useState, useEffect } from "react";
import toast from "react-hot-toast";
import { useNavigate } from "react-router-dom";
import {
  Activity,
  ArrowRight,
  Boxes,
  CircleCheck,
  Gauge,
  KeyRound,
  MonitorDot,
  Network,
  Play,
  Plus,
  Route,
  Server,
  ShieldAlert,
  Square,
  TriangleAlert,
  Unlink,
  Users,
} from "lucide-react";

import { SortableCardGrid } from "@/components/sortable-card-grid";
import { useCardOrder } from "@/hooks/use-card-order";
import PageShell from "@/components/page-shell";
import StatsCard from "@/components/ui/stats-card";
import {
  TodayTrafficCard,
  TrafficOverviewChart,
  WeeklyTrafficChart,
} from "@/components/dashboard/traffic-charts";
import {
  DashboardHeader,
  EndpointListCard,
  QuickActionsCard,
  RecentActivityCard,
  type ActivityItem,
} from "@/components/dashboard/dashboard-panels";
import {
  getAllUsers,
  getForwardList,
  getMonitoringAlerts,
  getMonitoringOverview,
  getNodeList,
  getPublishingPortGrants,
  getPrivateProxies,
  getUserPackageInfo,
  type MonitoringAlertItem,
  type MonitoringOverview,
  type MonitoringResource,
  type PublishingPortGrant,
  type PrivateProxyItem,
} from "@/api";
import { isAdmin as isAdminUser } from "@/utils/auth";

interface UserInfo {
  flow: number;
  flowUnlimited?: boolean;
  inFlow: number;
  outFlow: number;
  num: number;
  forwardUnlimited?: boolean;
  expTime?: string;
  flowResetTime?: number;
}

interface UserTunnel {
  id: number;
  tunnelId: number;
  tunnelName: string;
  flow: number;
  flowUnlimited?: number;
  inFlow: number;
  outFlow: number;
  num: number;
  forwardUnlimited?: number;
  expTime?: string;
  flowResetTime?: number;
  tunnelFlow: number;
}

interface SharedNode {
  id: number;
  name: string;
  ip?: string;
  portSta?: number;
  portEnd?: number;
  version?: string;
  status: number;
  accessType?: "admin" | "owned" | "shared";
  ownerUserName?: string;
  quotaFlow?: number;
  quotaUsedFlow?: number;
  quotaFlowUnlimited?: boolean;
  quotaForwardLimit?: number;
  quotaForwardUsed?: number;
  quotaForwardUnlimited?: boolean;
  quotaFlowResetTime?: number;
  quotaAvailable?: boolean;
  unavailableReason?: string;
}

interface Forward {
  id: number;
  name: string;
  tunnelId: number;
  tunnelName: string;
  inIp: string;
  inPort: number;
  remoteAddr: string;
  inFlow: number;
  outFlow: number;
}

interface AdminForward extends Forward {
  status: number;
  userName?: string;
  nodeOffline?: boolean;
  routeMode?: "single" | "failover" | "latency";
  lastRouteSwitch?: number;
  routeSwitchReason?: string;
}

interface StatisticsFlow {
  id: number;
  userId: number;
  flow: number;
  inFlow?: number;
  outFlow?: number;
  totalFlow: number;
  time: string;
  createdTime?: number;
}

const EMPTY_ADMIN_OVERVIEW: MonitoringOverview = {
  range: "24h",
  summary: {
    totalResources: 0,
    healthy: 0,
    degraded: 0,
    offline: 0,
    paused: 0,
    unknown: 0,
    openAlerts: 0,
    criticalAlerts: 0,
    unreadAlerts: 0,
    availability: 100,
    trackedFrom: 0,
  },
  trend: [],
  resources: [],
};

const proxyProtocolLabel: Record<PrivateProxyItem["proxyType"], string> = {
  socks5: "SOCKS5",
  http: "HTTP",
  shadowsocks: "Shadowsocks",
  mixed: "SOCKS5+HTTP",
  shadowsocks2022: "Shadowsocks 2022",
  vless_reality: "VLESS + REALITY",
  vless_xhttp: "VLESS + XHTTP",
  vless: "VLESS",
  vmess: "VMess",
  trojan: "Trojan",
  anytls: "AnyTLS",
  hysteria2: "Hysteria2",
  tuic: "TUIC v5",
  shadowtls: "ShadowTLS",
  wireguard: "WireGuard",
};

export default function DashboardPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [userInfo, setUserInfo] = useState<UserInfo>({} as UserInfo);
  const [userTunnels, setUserTunnels] = useState<UserTunnel[]>([]);
  const [sharedNodes, setSharedNodes] = useState<SharedNode[]>([]);
  const [sharedPortGrants, setSharedPortGrants] = useState<
    PublishingPortGrant[]
  >([]);
  const [proxyGrants, setProxyGrants] = useState<PrivateProxyItem[]>([]);
  const [forwardList, setForwardList] = useState<Forward[]>([]);
  const [statisticsFlows, setStatisticsFlows] = useState<StatisticsFlow[]>([]);
  const [dailyFlows, setDailyFlows] = useState<StatisticsFlow[]>([]);
  const [adminNodes, setAdminNodes] = useState<SharedNode[]>([]);
  const [isAdmin, setIsAdmin] = useState(false);
  const [adminOverview, setAdminOverview] =
    useState<MonitoringOverview>(EMPTY_ADMIN_OVERVIEW);
  const [adminAlerts, setAdminAlerts] = useState<MonitoringAlertItem[]>([]);
  const [adminForwards, setAdminForwards] = useState<AdminForward[]>([]);
  const [adminUsers, setAdminUsers] = useState<User[]>([]);
  const summaryCardIds = isAdmin
    ? ["admin-running", "admin-stopped", "admin-error", "admin-offline-nodes"]
    : ["total-flow", "used-flow", "forward-quota", "used-forwards"];
  const summaryCardOrder = useCardOrder(
    "dashboard-summary-cards",
    summaryCardIds,
  );
  const orderedSummaryCards = summaryCardOrder.sortItems(
    summaryCardIds,
    (id) => id,
  );
  const sharedNodeOrder = useCardOrder(
    "dashboard-shared-node-cards",
    sharedNodes.map((node) => node.id),
  );
  const orderedSharedNodes = sharedNodeOrder.sortItems(
    sharedNodes,
    (node) => node.id,
  );
  const tunnelPermissionOrder = useCardOrder(
    "dashboard-tunnel-permission-cards",
    userTunnels.map((tunnel) => tunnel.id),
  );
  const orderedUserTunnels = tunnelPermissionOrder.sortItems(
    userTunnels,
    (tunnel) => tunnel.id,
  );
  const portPermissionOrder = useCardOrder(
    "dashboard-port-permission-cards",
    sharedPortGrants.map((grant) => grant.id),
  );
  const orderedPortGrants = portPermissionOrder.sortItems(
    sharedPortGrants,
    (grant) => grant.id,
  );
  const proxyPermissionOrder = useCardOrder(
    "dashboard-private-proxy-grant-cards",
    proxyGrants.map((proxy) => proxy.id),
  );
  const orderedProxyGrants = proxyPermissionOrder.sortItems(
    proxyGrants,
    (proxy) => proxy.id,
  );

  // 检查有效期通知
  const checkExpirationNotifications = (
    userInfo: UserInfo,
    tunnels: UserTunnel[],
  ) => {
    // 避免重复通知，检查是否已经显示过
    const notificationKey = `expiration-${userInfo.expTime}-${tunnels.map((t) => t.expTime).join(",")}`;
    const lastNotified = localStorage.getItem("lastNotified");

    if (lastNotified === notificationKey) {
      return; // 已经通知过，不重复显示
    }

    let hasNotification = false;

    // 检查主账户有效期
    if (userInfo.expTime) {
      const expDate = new Date(userInfo.expTime);
      const now = new Date();

      if (!isNaN(expDate.getTime()) && expDate > now) {
        const diffTime = expDate.getTime() - now.getTime();
        const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

        if (diffDays <= 7 && diffDays > 0) {
          hasNotification = true;
          if (diffDays === 1) {
            toast("账户将于明天过期，请及时续费", {
              icon: "⚠️",
              duration: 6000,
              style: { background: "#f59e0b", color: "#fff" },
            });
          } else {
            toast(`账户将于${diffDays}天后过期，请及时续费`, {
              icon: "⚠️",
              duration: 6000,
              style: { background: "#f59e0b", color: "#fff" },
            });
          }
        } else if (diffDays <= 0) {
          hasNotification = true;
          toast("账户已过期，请立即续费", {
            icon: "⚠️",
            duration: 8000,
            style: { background: "#ef4444", color: "#fff" },
          });
        }
      }
    }

    // 检查隧道有效期
    tunnels.forEach((tunnel) => {
      if (tunnel.expTime) {
        const expDate = new Date(tunnel.expTime);
        const now = new Date();

        if (!isNaN(expDate.getTime()) && expDate > now) {
          const diffTime = expDate.getTime() - now.getTime();
          const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

          if (diffDays <= 7 && diffDays > 0) {
            hasNotification = true;
            if (diffDays === 1) {
              toast(`隧道"${tunnel.tunnelName}"将于明天过期`, {
                icon: "⚠️",
                duration: 5000,
                style: { background: "#f59e0b", color: "#fff" },
              });
            } else {
              toast(`隧道"${tunnel.tunnelName}"将于${diffDays}天后过期`, {
                icon: "⚠️",
                duration: 5000,
                style: { background: "#f59e0b", color: "#fff" },
              });
            }
          } else if (diffDays <= 0) {
            hasNotification = true;
            toast(`隧道"${tunnel.tunnelName}"已过期`, {
              icon: "⚠️",
              duration: 6000,
              style: { background: "#ef4444", color: "#fff" },
            });
          }
        }
      }
    });

    // 如果显示了通知，记录防止重复
    if (hasNotification) {
      localStorage.setItem("lastNotified", notificationKey);
    }
  };

  useEffect(() => {
    // 重置状态并加载数据，防止页面切换时显示旧数据
    setLoading(true);
    setUserInfo({} as UserInfo);
    setUserTunnels([]);
    setSharedNodes([]);
    setSharedPortGrants([]);
    setProxyGrants([]);
    setForwardList([]);
    setStatisticsFlows([]);
    setDailyFlows([]);
    setAdminNodes([]);
    setAdminOverview(EMPTY_ADMIN_OVERVIEW);
    setAdminAlerts([]);
    setAdminForwards([]);
    setAdminUsers([]);

    // 管理员身份只从当前有效 JWT 读取，避免旧缓存导致页面显示错位。
    const adminStatus = isAdminUser();

    setIsAdmin(adminStatus);

    loadPackageData(adminStatus);
    localStorage.setItem("e", "/dashboard");
  }, []);

  const loadPackageData = async (adminOverride = isAdminUser()) => {
    setLoading(true);
    const admin = adminOverride;

    try {
      const res = await getUserPackageInfo();

      if (res.code === 0) {
        const data = res.data;

        setUserInfo(data.userInfo || {});
        setUserTunnels(data.tunnelPermissions || []);
        setForwardList(data.forwards || []);
        setStatisticsFlows(data.statisticsFlows || []);
        setDailyFlows(data.dailyFlows || []);

        // 检查有效期并显示通知
        checkExpirationNotifications(
          data.userInfo,
          data.tunnelPermissions || [],
        );
      } else {
        toast.error(res.msg || "获取套餐信息失败");
      }
    } catch (error) {
      console.error("获取套餐信息失败:", error);
      toast.error("获取套餐信息失败");
    } finally {
      // 套餐和当前用户的核心内容可以独立渲染，辅助数据继续后台补齐。
      setLoading(false);
    }

    const loadAuxiliary = async () => {
      const tasks: Promise<void>[] = [];

      if (!admin) {
        tasks.push(
          (async () => {
            try {
              const response = await getNodeList();

              if (response.code === 0) {
                const shared = (response.data || [])
                  .filter((node: SharedNode) => node.accessType === "shared")
                  .sort((a: SharedNode, b: SharedNode) => {
                    const statusDifference = (b.status || 0) - (a.status || 0);

                    return statusDifference !== 0
                      ? statusDifference
                      : b.id - a.id;
                  });

                setSharedNodes(shared);
              } else {
                console.warn("获取节点权限失败:", response.msg);
              }
            } catch (error) {
              console.warn("获取节点权限失败:", error);
            }
          })(),
        );

        tasks.push(
          (async () => {
            try {
              const response = await getPublishingPortGrants();

              if (response.code === 0) setSharedPortGrants(response.data || []);
              else console.warn("获取端口资源失败:", response.msg);
            } catch (error) {
              console.warn("获取端口资源失败:", error);
            }
          })(),
        );

        tasks.push(
          (async () => {
            try {
              const response = await getPrivateProxies();

              if (response.code === 0)
                setProxyGrants(
                  (response.data || []).filter(
                    (proxy: PrivateProxyItem) => proxy.granted,
                  ),
                );
              else console.warn("获取代理授权失败:", response.msg);
            } catch (error) {
              console.warn("获取代理授权失败:", error);
            }
          })(),
        );
      } else {
        tasks.push(
          (async () => {
            try {
              const response = await getNodeList();

              if (response.code === 0)
                setAdminNodes(
                  [...((response.data || []) as SharedNode[])].sort(
                    (a, b) => (b.status || 0) - (a.status || 0) || a.id - b.id,
                  ),
                );
              else console.warn("获取节点列表失败:", response.msg);
            } catch (error) {
              console.warn("获取节点列表失败:", error);
            }
          })(),
        );

        tasks.push(
          (async () => {
            try {
              const response = await getMonitoringOverview("24h");

              if (response.code === 0)
                setAdminOverview(response.data || EMPTY_ADMIN_OVERVIEW);
              else console.warn("获取监控概览失败:", response.msg);
            } catch (error) {
              console.warn("获取监控概览失败:", error);
            }
          })(),
        );

        tasks.push(
          (async () => {
            try {
              const response = await getMonitoringAlerts({
                status: "open",
                page: 1,
                size: 5,
              });

              if (response.code === 0)
                setAdminAlerts(response.data?.items || []);
              else console.warn("获取告警列表失败:", response.msg);
            } catch (error) {
              console.warn("获取告警列表失败:", error);
            }
          })(),
        );

        tasks.push(
          (async () => {
            try {
              const response = await getForwardList();

              if (response.code === 0)
                setAdminForwards((response.data || []) as AdminForward[]);
              else console.warn("获取转发列表失败:", response.msg);
            } catch (error) {
              console.warn("获取转发列表失败:", error);
            }
          })(),
        );

        tasks.push(
          (async () => {
            try {
              const response = await getAllUsers();

              if (response.code === 0)
                setAdminUsers((response.data || []) as User[]);
              else console.warn("获取用户列表失败:", response.msg);
            } catch (error) {
              console.warn("获取用户列表失败:", error);
            }
          })(),
        );
      }

      await Promise.all(tasks);
    };

    void loadAuxiliary();
  };

  const formatFlow = (value: number, unit: string = "bytes"): string => {
    // 99999 表示无限制
    if (value === 99999) {
      return "无限制";
    }

    if (unit === "gb") {
      return value + " GB";
    } else {
      if (value === 0) return "0 B";
      if (value < 1024) return value + " B";
      if (value < 1024 * 1024) return (value / 1024).toFixed(2) + " KB";
      if (value < 1024 * 1024 * 1024)
        return (value / (1024 * 1024)).toFixed(2) + " MB";

      return (value / (1024 * 1024 * 1024)).toFixed(2) + " GB";
    }
  };

  const formatNumber = (value: number): string => {
    // 99999 表示无限制
    if (value === 99999) {
      return "无限制";
    }

    return value.toString();
  };

  const formatRemainingTime = (milliseconds?: number): string => {
    if (milliseconds === undefined || milliseconds === null) return "永久";
    if (milliseconds <= 0) return "已到期";
    const days = Math.floor(milliseconds / 86400000);

    if (days > 0) return `${days} 天`;
    const hours = Math.max(1, Math.ceil(milliseconds / 3600000));

    return `${hours} 小时`;
  };

  const getExpStatus = (expTime?: string) => {
    if (!expTime)
      return {
        color: "text-green-600 dark:text-green-400",
        bg: "bg-green-50 dark:bg-green-500/10 border-green-200 dark:border-green-500/20",
        text: "永久",
      };

    const now = new Date();
    const expDate = new Date(expTime);

    if (isNaN(expDate.getTime())) {
      return {
        color: "text-gray-600 dark:text-gray-400",
        bg: "bg-gray-50 dark:bg-black/10 border-gray-200 dark:border-gray-500/20",
        text: "无效",
      };
    }

    if (expDate < now) {
      return {
        color: "text-red-600 dark:text-red-400",
        bg: "bg-red-50 dark:bg-red-500/10 border-red-200 dark:border-red-500/20",
        text: "已过期",
      };
    }

    const diffTime = expDate.getTime() - now.getTime();
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

    if (diffDays <= 7) {
      return {
        color: "text-red-600 dark:text-red-400",
        bg: "bg-red-50 dark:bg-red-500/10 border-red-200 dark:border-red-500/20",
        text: `${diffDays}天后过期`,
      };
    } else if (diffDays <= 30) {
      return {
        color: "text-orange-600 dark:text-orange-400",
        bg: "bg-orange-50 dark:bg-orange-500/10 border-orange-200 dark:border-orange-500/20",
        text: `${diffDays}天后过期`,
      };
    } else {
      return {
        color: "text-green-600 dark:text-green-400",
        bg: "bg-green-50 dark:bg-green-500/10 border-green-200 dark:border-green-500/20",
        text: `${diffDays}天后过期`,
      };
    }
  };

  const calculateUserTotalUsedFlow = (): number => {
    // 后端已按计费类型处理流量，前端直接使用入站+出站总和
    return (userInfo.inFlow || 0) + (userInfo.outFlow || 0);
  };

  const calculateUsagePercentage = (type: "flow" | "forwards"): number => {
    if (type === "flow") {
      const totalUsed = calculateUserTotalUsedFlow();
      const totalLimit = (userInfo.flow || 0) * 1024 * 1024 * 1024;

      // 无限制时返回0%
      if (userInfo.flowUnlimited || userInfo.flow === 99999) return 0;

      return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
    } else if (type === "forwards") {
      const totalUsed = forwardList.length;
      const totalLimit = userInfo.num || 0;

      // 无限制时返回0%
      if (userInfo.forwardUnlimited || userInfo.num === 99999) return 0;

      return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
    }

    return 0;
  };

  const getUsageColor = (percentage: number) => {
    if (percentage >= 90) return "bg-red-500 dark:bg-red-600";
    if (percentage >= 70) return "bg-orange-500 dark:bg-orange-600";

    return "bg-blue-500 dark:bg-blue-600";
  };

  const renderProgressBar = (
    percentage: number,
    size: "sm" | "md" = "md",
    isUnlimited: boolean = false,
  ) => {
    const height = size === "sm" ? "h-1.5" : "h-2";

    if (isUnlimited) {
      return (
        <div className="w-full">
          <div
            className={`w-full bg-gradient-to-r from-blue-200 to-purple-200 dark:from-blue-500/30 dark:to-purple-500/30 rounded-full ${height}`}
          >
            <div
              className={`${height} bg-gradient-to-r from-blue-500 to-purple-500 rounded-full w-full opacity-60`}
            />
          </div>
        </div>
      );
    }

    return (
      <div className="w-full">
        <div
          className={`w-full bg-gray-200 dark:bg-gray-800 rounded-full ${height}`}
        >
          <div
            className={`${height} rounded-full transition-all duration-300 ${getUsageColor(percentage)}`}
            style={{ width: `${Math.min(percentage, 100)}%` }}
          />
        </div>
      </div>
    );
  };

  const calculateTunnelUsedFlow = (tunnel: UserTunnel): number => {
    if (!tunnel) return 0;
    const inFlow = tunnel.inFlow || 0;
    const outFlow = tunnel.outFlow || 0;

    // 后端已按计费类型处理流量，前端直接使用入站+出站总和
    return inFlow + outFlow;
  };

  const calculateTunnelFlowPercentage = (tunnel: UserTunnel): number => {
    const totalUsed = calculateTunnelUsedFlow(tunnel);
    const totalLimit = (tunnel.flow || 0) * 1024 * 1024 * 1024;

    // 无限制时返回0%
    if (tunnel.flow === 99999) return 0;

    return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
  };

  const getTunnelUsedForwards = (tunnelId: number): number => {
    return forwardList.filter((forward) => forward.tunnelId === tunnelId)
      .length;
  };

  const calculateTunnelForwardPercentage = (tunnel: UserTunnel): number => {
    const totalUsed = getTunnelUsedForwards(tunnel.tunnelId);
    const totalLimit = tunnel.num || 0;

    // 无限制时返回0%
    if (tunnel.num === 99999) return 0;

    return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
  };

  const formatResetTime = (resetDay?: number): string => {
    if (resetDay === undefined || resetDay === null) return "";
    if (resetDay === 0) return "不重置";

    const now = new Date();
    const currentDay = now.getDate();

    let daysUntilReset;

    if (resetDay > currentDay) {
      daysUntilReset = resetDay - currentDay;
    } else if (resetDay < currentDay) {
      const nextMonth = new Date(
        now.getFullYear(),
        now.getMonth() + 1,
        resetDay,
      );
      const diffTime = nextMonth.getTime() - now.getTime();

      daysUntilReset = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
    } else {
      daysUntilReset = 0;
    }

    if (daysUntilReset === 0) {
      return "今日重置";
    } else if (daysUntilReset === 1) {
      return "明日重置";
    } else {
      return `${daysUntilReset}天后重置`;
    }
  };

  const formatAlertDuration = (startedAt: number) => {
    const minutes = Math.max(0, Math.floor((Date.now() - startedAt) / 60000));

    if (minutes < 60) return `${Math.max(1, minutes)} 分钟`;
    const hours = Math.floor(minutes / 60);

    if (hours < 24) return `${hours} 小时`;

    return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`;
  };

  const resourcesByType = (type: MonitoringResource["type"]) =>
    adminOverview.resources.filter((resource) => resource.type === type);

  const resourceSummary = (type: MonitoringResource["type"]) => {
    const resources = resourcesByType(type);

    return {
      total: resources.length,
      healthy: resources.filter((resource) => resource.status === "healthy")
        .length,
      degraded: resources.filter((resource) => resource.status === "degraded")
        .length,
      offline: resources.filter((resource) => resource.status === "offline")
        .length,
      paused: resources.filter((resource) => resource.status === "paused")
        .length,
      unknown: resources.filter((resource) => resource.status === "unknown")
        .length,
    };
  };

  const tunnelHealth = resourceSummary("tunnel");
  const runningForwards = adminForwards.filter(
    (forward) => forward.status === 1 && !forward.nodeOffline,
  ).length;
  const stoppedForwards = adminForwards.filter(
    (forward) => forward.status !== 1 && forward.status !== -1,
  ).length;
  const errorForwards = adminForwards.filter(
    (forward) => forward.status === -1 || !!forward.nodeOffline,
  ).length;
  const offlineNodes = adminNodes.filter((node) => node.status !== 1).length;
  const topTrafficForwards = [...adminForwards]
    .sort(
      (a, b) =>
        (b.inFlow || 0) +
        (b.outFlow || 0) -
        ((a.inFlow || 0) + (a.outFlow || 0)),
    )
    .slice(0, 5);
  const recentActivity: ActivityItem[] = [
    ...adminAlerts.map((alert) => ({
      key: `alert-${alert.id}`,
      time: alert.startedAt,
      kind: alert.severity === "critical" ? "严重告警" : "告警",
      color: (alert.severity === "critical" ? "danger" : "warning") as
        "danger" | "warning",
      name: alert.resourceName,
      detail: `${alert.title}${alert.detail ? ` · ${alert.detail}` : ""}`,
      onPress: () => navigate("/monitoring"),
    })),
    ...adminForwards
      .filter((forward) => !!forward.lastRouteSwitch)
      .map((forward) => ({
        key: `switch-${forward.id}`,
        time: forward.lastRouteSwitch || 0,
        kind: "线路切换",
        color: "primary" as const,
        name: forward.name,
        detail: `${forward.userName || "管理员"} · ${forward.routeSwitchReason || "线路状态变化"}`,
        onPress: () => navigate("/forward"),
      })),
  ]
    .sort((a, b) => b.time - a.time)
    .slice(0, 20);
  const quotaRisks = adminUsers
    .map((user) => {
      const totalBytes = (user.totalFlow || 0) * 1024 * 1024 * 1024;
      const usage =
        !user.totalFlowUnlimited && totalBytes > 0
          ? ((user.totalUsedFlow || 0) / totalBytes) * 100
          : 0;
      const daysToExpire = user.expTime
        ? Math.ceil((user.expTime - Date.now()) / 86400000)
        : null;
      const priority =
        user.status !== 1 ||
        (daysToExpire !== null && daysToExpire <= 0) ||
        usage >= 100
          ? 3
          : (daysToExpire !== null && daysToExpire <= 7) || usage >= 90
            ? 2
            : (daysToExpire !== null && daysToExpire <= 30) || usage >= 80
              ? 1
              : 0;
      const badge =
        user.status !== 1
          ? "禁用"
          : daysToExpire !== null && daysToExpire <= 0
            ? "到期"
            : usage >= 80
              ? `${Math.round(usage)}%`
              : `${daysToExpire}天`;

      return { user, usage, daysToExpire, priority, badge };
    })
    .filter((item) => item.priority > 0)
    .sort((a, b) => b.priority - a.priority || b.usage - a.usage)
    .slice(0, 5);

  if (loading) {
    return (
      <div className="px-3 lg:px-6 flex-grow pt-2 lg:pt-4">
        <div className="flex items-center justify-center h-64">
          <div className="flex items-center gap-3">
            <div className="animate-spin h-5 w-5 border-2 border-gray-200 dark:border-gray-700 border-t-gray-600 dark:border-t-gray-300 rounded-full" />
            <span className="text-default-600">正在加载数据...</span>
          </div>
        </div>
      </div>
    );
  }

  const sideItems = isAdmin
    ? adminNodes.map((node) => ({
        id: node.id,
        name: node.name,
        detail: node.ip,
        online: node.status === 1,
        badge: node.version ? `v${node.version}` : undefined,
      }))
    : sharedNodes.length > 0
      ? sharedNodes.map((node) => ({
          id: node.id,
          name: node.name,
          detail: node.ip,
          online: node.status === 1,
          badge: node.ownerUserName,
        }))
      : userTunnels.map((tunnel) => ({
          id: tunnel.id,
          name: tunnel.tunnelName,
          detail: `已用 ${formatFlow(calculateTunnelUsedFlow(tunnel))}`,
          online: getExpStatus(tunnel.expTime).text !== "已过期",
          badge: `${getTunnelUsedForwards(tunnel.tunnelId)} 个转发`,
        }));
  const quickIcon = "h-4 w-4";
  const quickActions = isAdmin
    ? [
        {
          label: "新建转发",
          hint: "转发管理",
          icon: <Plus className={quickIcon} />,
          color: "primary" as const,
          onPress: () => navigate("/forward"),
        },
        {
          label: "添加节点",
          hint: "节点与 Agent",
          icon: <Server className={quickIcon} />,
          color: "success" as const,
          onPress: () => navigate("/node"),
        },
        {
          label: "隧道组建",
          hint: "入口到出口",
          icon: <Route className={quickIcon} />,
          color: "secondary" as const,
          onPress: () => navigate("/tunnel"),
        },
        {
          label: "资源监控",
          hint: "告警与可用率",
          icon: <MonitorDot className={quickIcon} />,
          color: "warning" as const,
          onPress: () => navigate("/monitoring"),
        },
      ]
    : [
        {
          label: "转发管理",
          hint: "新建或修改转发",
          icon: <Network className={quickIcon} />,
          color: "primary" as const,
          onPress: () => navigate("/forward"),
        },
        {
          label: "代理协议",
          hint: "连接信息",
          icon: <KeyRound className={quickIcon} />,
          color: "secondary" as const,
          onPress: () => navigate("/private-proxy"),
        },
        {
          label: "网络工具",
          hint: "测速与诊断",
          icon: <Activity className={quickIcon} />,
          color: "success" as const,
          onPress: () => navigate("/network-tools"),
        },
        {
          label: "个人中心",
          hint: "账号与密码",
          icon: <Users className={quickIcon} />,
          color: "warning" as const,
          onPress: () => navigate("/profile"),
        },
      ];
  const todayFlow = dailyFlows[0];

  return (
    <PageShell width="wide" className="space-y-4 md:space-y-6">
      <DashboardHeader
        counts={
          isAdmin
            ? [
                {
                  label: "节点",
                  value: adminNodes.length,
                  className: "text-success",
                  onPress: () => navigate("/node"),
                },
                {
                  label: "隧道",
                  value: tunnelHealth.total,
                  className: "text-primary",
                  onPress: () => navigate("/tunnel"),
                },
                {
                  label: "转发",
                  value: adminForwards.length,
                  className: "text-secondary",
                  onPress: () => navigate("/forward"),
                },
              ]
            : [
                {
                  label: "节点",
                  value: sharedNodes.length,
                  className: "text-success",
                },
                {
                  label: "隧道",
                  value: userTunnels.length,
                  className: "text-primary",
                },
                {
                  label: "转发",
                  value: forwardList.length,
                  className: "text-secondary",
                  onPress: () => navigate("/forward"),
                },
              ]
        }
      />

      <SortableCardGrid
        className="grid grid-cols-2 gap-4 lg:grid-cols-4"
        getId={(id) => id}
        items={orderedSummaryCards}
        renderItem={(cardId, dragHandle) => {
          const iconClass = "h-5 w-5 md:h-6 md:w-6";
          const flowUnlimited =
            !!userInfo.flowUnlimited || userInfo.flow === 99999;
          const forwardUnlimited =
            !!userInfo.forwardUnlimited || userInfo.num === 99999;

          if (isAdmin) {
            if (cardId === "admin-running")
              return (
                <StatsCard
                  action={dragHandle}
                  color="success"
                  hint={`共 ${adminForwards.length} 条转发`}
                  icon={<Play className={iconClass} />}
                  title="运行中"
                  value={runningForwards}
                  onPress={() => navigate("/forward?status=running")}
                />
              );
            if (cardId === "admin-stopped")
              return (
                <StatsCard
                  action={dragHandle}
                  color="danger"
                  hint="已暂停的转发"
                  icon={<Square className={iconClass} />}
                  title="已暂停"
                  value={stoppedForwards}
                  onPress={() => navigate("/forward?status=stopped")}
                />
              );
            if (cardId === "admin-error")
              return (
                <StatsCard
                  action={dragHandle}
                  color="warning"
                  hint="节点离线或下发失败"
                  icon={<TriangleAlert className={iconClass} />}
                  title="异常"
                  value={errorForwards}
                  onPress={() => navigate("/forward?status=error")}
                />
              );

            return (
              <StatsCard
                action={dragHandle}
                color="default"
                hint={`共 ${adminNodes.length} 台节点`}
                icon={<Unlink className={iconClass} />}
                title="离线节点"
                value={offlineNodes}
                onPress={() => navigate("/node")}
              />
            );
          }

          if (cardId === "total-flow")
            return (
              <StatsCard
                action={dragHandle}
                color="primary"
                icon={<Gauge className={iconClass} />}
                title="总流量"
                value={
                  userInfo.flowUnlimited
                    ? "无限制"
                    : formatFlow(userInfo.flow, "gb")
                }
              />
            );
          if (cardId === "used-flow")
            return (
              <StatsCard
                action={dragHandle}
                color="success"
                icon={<Activity className={iconClass} />}
                title="已用流量"
                value={formatFlow(calculateUserTotalUsedFlow())}
              >
                {renderProgressBar(
                  calculateUsagePercentage("flow"),
                  "sm",
                  flowUnlimited,
                )}
                <div className="mt-1 flex items-center justify-between">
                  <p className="truncate text-xs text-default-500">
                    {flowUnlimited
                      ? "无限制"
                      : `${calculateUsagePercentage("flow").toFixed(1)}%`}
                  </p>
                  {userInfo.flowResetTime !== undefined &&
                    userInfo.flowResetTime !== null && (
                      <span className="truncate text-xs text-default-500">
                        {formatResetTime(userInfo.flowResetTime)}
                      </span>
                    )}
                </div>
              </StatsCard>
            );
          if (cardId === "forward-quota")
            return (
              <StatsCard
                action={dragHandle}
                color="secondary"
                icon={<Network className={iconClass} />}
                title="转发配额"
                value={
                  userInfo.forwardUnlimited
                    ? "无限制"
                    : formatNumber(userInfo.num || 0)
                }
              />
            );

          return (
            <StatsCard
              action={dragHandle}
              color="warning"
              icon={<Route className={iconClass} />}
              title="已用转发"
              value={forwardList.length}
              onPress={() => navigate("/forward")}
            >
              {renderProgressBar(
                calculateUsagePercentage("forwards"),
                "sm",
                forwardUnlimited,
              )}
              <p className="mt-1 truncate text-xs text-default-500">
                {forwardUnlimited
                  ? "无限制"
                  : `${calculateUsagePercentage("forwards").toFixed(1)}%`}
              </p>
            </StatsCard>
          );
        }}
        onMove={summaryCardOrder.moveCard}
      />

      <div className="flex flex-col gap-4 md:gap-6 lg:grid lg:grid-cols-3">
        <div className="lg:col-span-2">
          <TrafficOverviewChart hourly={statisticsFlows} />
        </div>
        <EndpointListCard
          emptyText={isAdmin ? "还没有节点" : "暂无可用节点或隧道"}
          items={sideItems}
          title={
            isAdmin
              ? "节点列表"
              : sharedNodes.length > 0
                ? "可用节点"
                : "我的隧道"
          }
          onOpen={isAdmin ? () => navigate("/node") : undefined}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-3 md:gap-6">
        <WeeklyTrafficChart daily={dailyFlows} />
        <TodayTrafficCard today={todayFlow} />
        <QuickActionsCard actions={quickActions} />
      </div>

      {isAdmin && (
        <>
          <RecentActivityCard
            items={recentActivity}
            onOpen={() => navigate("/monitoring")}
          />

          <div className="grid grid-cols-1 gap-4 md:gap-6 xl:grid-cols-3">
            <Card className="border border-transparent dark:border-default-100">
              <CardHeader className="flex items-center justify-between gap-3 pb-2">
                <div className="flex items-center gap-2">
                  <ShieldAlert className="h-5 w-5 text-rose-500" />
                  <h2 className="text-base font-semibold">异常与待处理</h2>
                </div>
                <button
                  className="text-sm font-medium text-primary"
                  type="button"
                  onClick={() => navigate("/monitoring")}
                >
                  查看全部
                </button>
              </CardHeader>
              <CardBody className="pt-1">
                {adminAlerts.length === 0 ? (
                  <div className="flex min-h-48 flex-col items-center justify-center text-center">
                    <CircleCheck className="mb-3 h-9 w-9 text-emerald-500" />
                    <p className="font-medium text-foreground">
                      当前没有待处理告警
                    </p>
                    <p className="mt-1 text-sm text-default-500">
                      节点、隧道和转发均未报告异常
                    </p>
                  </div>
                ) : (
                  <div className="divide-y divide-gray-200 dark:divide-gray-800">
                    {adminAlerts.map((alert) => (
                      <button
                        key={alert.id}
                        className="flex w-full items-start gap-3 py-3 text-left first:pt-1 last:pb-1"
                        type="button"
                        onClick={() => navigate("/monitoring")}
                      >
                        <span
                          className={`mt-1 h-2.5 w-2.5 shrink-0 rounded-full ${alert.severity === "critical" ? "bg-rose-500" : "bg-amber-400"}`}
                        />
                        <span className="min-w-0 flex-1">
                          <span className="flex flex-wrap items-center gap-2">
                            <strong className="truncate text-sm font-medium text-foreground">
                              {alert.resourceName}
                            </strong>
                            <span className="text-xs text-default-500">
                              {alert.ownerUserName || "管理员"}
                            </span>
                          </span>
                          <span className="mt-1 block truncate text-xs text-default-500">
                            {alert.title} · {alert.detail}
                          </span>
                        </span>
                        <span className="shrink-0 text-xs text-default-500">
                          持续 {formatAlertDuration(alert.startedAt)}
                        </span>
                      </button>
                    ))}
                  </div>
                )}
              </CardBody>
            </Card>

            <Card className="border border-transparent dark:border-default-100">
              <CardHeader className="flex items-center justify-between gap-3 pb-2">
                <div className="flex items-center gap-2">
                  <Gauge className="h-5 w-5 text-blue-500" />
                  <h2 className="text-base font-semibold">转发流量排行</h2>
                </div>
                <span className="text-xs text-default-500">累计计费流量</span>
              </CardHeader>
              <CardBody className="pt-1">
                {topTrafficForwards.length === 0 ? (
                  <div className="flex min-h-48 items-center justify-center text-sm text-default-500">
                    暂无转发流量
                  </div>
                ) : (
                  <div className="space-y-3">
                    {topTrafficForwards.map((forward, index) => {
                      const flow =
                        (forward.inFlow || 0) + (forward.outFlow || 0);
                      const maxFlow =
                        (topTrafficForwards[0]?.inFlow || 0) +
                        (topTrafficForwards[0]?.outFlow || 0);

                      return (
                        <button
                          key={forward.id}
                          className="block w-full text-left"
                          type="button"
                          onClick={() => navigate("/forward")}
                        >
                          <div className="mb-1.5 flex items-center justify-between gap-3 text-sm">
                            <span className="min-w-0 truncate">
                              <span className="mr-2 text-default-400">
                                {index + 1}
                              </span>
                              <span className="font-medium text-foreground">
                                {forward.name}
                              </span>
                              <span className="ml-2 text-xs text-default-500">
                                {forward.userName || "管理员"}
                              </span>
                            </span>
                            <span className="shrink-0 font-medium text-foreground">
                              {formatFlow(flow)}
                            </span>
                          </div>
                          <div className="h-1.5 overflow-hidden rounded-full bg-default-100">
                            <div
                              className="h-full rounded-full bg-blue-500"
                              style={{
                                width: `${maxFlow > 0 ? Math.max(3, (flow / maxFlow) * 100) : 0}%`,
                              }}
                            />
                          </div>
                        </button>
                      );
                    })}
                  </div>
                )}
              </CardBody>
            </Card>
            <Card className="border border-transparent dark:border-default-100">
              <CardHeader className="flex items-center justify-between gap-3 pb-2">
                <div className="flex items-center gap-2">
                  <Users className="h-5 w-5 text-amber-500" />
                  <h2 className="text-base font-semibold">额度与到期风险</h2>
                </div>
                <button
                  className="text-sm font-medium text-primary"
                  type="button"
                  onClick={() => navigate("/user")}
                >
                  用户管理
                </button>
              </CardHeader>
              <CardBody className="pt-1">
                {quotaRisks.length === 0 ? (
                  <div className="flex min-h-44 flex-col items-center justify-center text-center">
                    <CircleCheck className="mb-3 h-8 w-8 text-emerald-500" />
                    <p className="text-sm text-default-500">
                      当前没有高风险用户
                    </p>
                  </div>
                ) : (
                  <div className="divide-y divide-gray-200 dark:divide-gray-800">
                    {quotaRisks.map((item) => (
                      <button
                        key={item.user.id}
                        className="flex w-full items-center gap-3 py-3 text-left first:pt-1 last:pb-1"
                        type="button"
                        onClick={() => navigate("/user")}
                      >
                        <span
                          className={`flex h-8 min-w-8 shrink-0 items-center justify-center rounded-md px-1 text-xs font-semibold ${item.priority >= 3 ? "bg-rose-100 text-rose-700 dark:bg-rose-500/10 dark:text-rose-300" : "bg-amber-100 text-amber-700 dark:bg-amber-500/10 dark:text-amber-300"}`}
                        >
                          {item.badge}
                        </span>
                        <span className="min-w-0 flex-1">
                          <strong className="block truncate text-sm font-medium text-foreground">
                            {item.user.user}
                          </strong>
                          <span className="mt-1 block truncate text-xs text-default-500">
                            {item.user.status !== 1
                              ? "账号已禁用"
                              : item.daysToExpire !== null &&
                                  item.daysToExpire <= 0
                                ? "账号已到期"
                                : item.daysToExpire !== null &&
                                    item.daysToExpire <= 30
                                  ? `${item.daysToExpire} 天后到期`
                                  : `额度已使用 ${item.usage.toFixed(1)}%`}
                          </span>
                        </span>
                        <ArrowRight className="h-4 w-4 shrink-0 text-default-400" />
                      </button>
                    ))}
                  </div>
                )}
              </CardBody>
            </Card>
          </div>
        </>
      )}

      {!isAdmin && (
        <Card className="border border-transparent dark:border-default-100">
          <CardHeader className="flex items-center justify-between gap-3 pb-3">
            <div className="flex min-w-0 items-center gap-2">
              <KeyRound className="h-5 w-5 shrink-0 text-secondary" />
              <h2 className="text-lg font-semibold text-foreground lg:text-xl">
                代理协议
              </h2>
              <span className="rounded-md bg-default-100 px-2 py-1 text-xs text-default-600">
                {proxyGrants.length} 个
              </span>
            </div>
            <button
              className="text-sm font-medium text-primary"
              type="button"
              onClick={() => navigate("/private-proxy")}
            >
              连接信息
            </button>
          </CardHeader>
          <CardBody className="pt-0">
            {proxyGrants.length === 0 ? (
              <div className="py-10 text-center text-sm text-default-500">
                暂无代理协议授权
              </div>
            ) : (
              <SortableCardGrid
                className="space-y-3"
                getId={(proxy) => proxy.id}
                items={orderedProxyGrants}
                renderItem={(proxy, dragHandle) => {
                  const used = (proxy.inFlow || 0) + (proxy.outFlow || 0);

                  return (
                    <div
                      className={`rounded-lg border p-3 lg:p-4 ${proxy.available ? "border-gray-200 bg-content1 dark:border-default-100" : "border-danger-200 bg-danger-50/40 dark:border-danger-500/30 dark:bg-danger-500/5"}`}
                    >
                      <div className="grid gap-4 lg:grid-cols-[minmax(220px,1.2fr)_repeat(4,minmax(120px,1fr))_auto] lg:items-center">
                        <div className="flex min-w-0 items-start gap-3">
                          <span
                            className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-md ${proxy.available ? "bg-secondary-100 text-secondary-700 dark:bg-secondary-500/15 dark:text-secondary-300" : "bg-danger-100 text-danger"}`}
                          >
                            <KeyRound className="h-5 w-5" />
                          </span>
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <h3 className="truncate font-semibold">
                                {proxy.name}
                              </h3>
                              <span
                                className={`rounded-md px-2 py-0.5 text-xs ${proxy.available ? "bg-success-100 text-success-700 dark:bg-success-500/15 dark:text-success-300" : "bg-danger-100 text-danger-700 dark:bg-danger-500/15 dark:text-danger-300"}`}
                              >
                                {proxy.available ? "可用" : "不可用"}
                              </span>
                              {dragHandle}
                            </div>
                            <p className="mt-1 truncate text-xs text-default-500">
                              {proxyProtocolLabel[proxy.proxyType]} ·{" "}
                              {["hysteria2", "tuic", "wireguard"].includes(
                                proxy.proxyType,
                              )
                                ? "UDP"
                                : "TCP"}{" "}
                              · 端口 {proxy.listenPort}
                            </p>
                            {!proxy.available && (
                              <p className="mt-1 text-xs text-danger">
                                {proxy.unavailableReason}
                              </p>
                            )}
                          </div>
                        </div>
                        <div>
                          <p className="text-xs text-default-500">剩余流量</p>
                          <p className="mt-1 text-sm font-medium">
                            {proxy.flowUnlimited === 1
                              ? "无限制"
                              : formatFlow(proxy.remainingFlow || 0)}
                          </p>
                          <p className="mt-1 text-xs text-default-500">
                            {proxy.flowUnlimited === 1
                              ? `已用 ${formatFlow(used)}`
                              : `总额度 ${proxy.flowLimit || 0} GB · 已用 ${formatFlow(used)}`}
                          </p>
                        </div>
                        <div>
                          <p className="text-xs text-default-500">剩余时间</p>
                          <p className="mt-1 text-sm font-medium">
                            {formatRemainingTime(proxy.remainingTime)}
                          </p>
                        </div>
                        <div>
                          <p className="text-xs text-default-500">到期时间</p>
                          <p className="mt-1 text-sm font-medium">
                            {proxy.expiresAt
                              ? new Date(proxy.expiresAt).toLocaleDateString()
                              : "永久"}
                          </p>
                        </div>
                        <div>
                          <p className="text-xs text-default-500">流量重置</p>
                          <p className="mt-1 text-sm font-medium">
                            {proxy.flowResetDay
                              ? `每月 ${proxy.flowResetDay} 日`
                              : "不重置"}
                          </p>
                        </div>
                        <button
                          className="shrink-0 text-sm font-medium text-primary"
                          type="button"
                          onClick={() => navigate("/private-proxy")}
                        >
                          查看
                        </button>
                      </div>
                    </div>
                  );
                }}
                onMove={proxyPermissionOrder.moveCard}
              />
            )}
          </CardBody>
        </Card>
      )}

      {!isAdmin && (
        <Card className="border border-transparent dark:border-default-100">
          <CardHeader className="flex items-center justify-between gap-3 pb-3">
            <div className="flex min-w-0 items-center gap-2">
              <Boxes className="h-5 w-5 shrink-0 text-warning" />
              <h2 className="text-lg font-semibold text-foreground lg:text-xl">
                端口资源
              </h2>
              <span className="rounded-md bg-default-100 px-2 py-1 text-xs text-default-600">
                {sharedPortGrants.length} 段
              </span>
            </div>
            <span className="text-xs text-default-500">
              共{" "}
              {sharedPortGrants.reduce(
                (sum, grant) => sum + grant.totalPorts,
                0,
              )}{" "}
              个
            </span>
          </CardHeader>
          <CardBody className="pt-0">
            {sharedPortGrants.length === 0 ? (
              <div className="py-10 text-center text-sm text-default-500">
                暂无端口资源
              </div>
            ) : (
              <SortableCardGrid
                className="space-y-3"
                getId={(grant) => grant.id}
                items={orderedPortGrants}
                renderItem={(grant, dragHandle) => (
                  <div className="rounded-lg border border-gray-200 bg-content1 p-3 dark:border-default-100 lg:p-4">
                    <div className="grid gap-4 lg:grid-cols-[minmax(230px,1fr)_minmax(520px,2fr)] lg:items-center">
                      <div className="flex min-w-0 items-start gap-3">
                        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-warning-100 text-warning-700 dark:bg-warning-500/10 dark:text-warning-300">
                          <Boxes className="h-5 w-5" />
                        </span>
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <h3 className="truncate font-semibold">
                              {grant.poolName}
                            </h3>
                            <span className="rounded-md bg-secondary-100 px-2 py-0.5 text-xs text-secondary-700 dark:bg-secondary-500/15 dark:text-secondary-300">
                              已授权
                            </span>
                            {dragHandle}
                          </div>
                          <p className="mt-1 truncate text-xs text-default-500">
                            可用于创建内网映射和发布服务
                          </p>
                        </div>
                      </div>
                      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                        <div>
                          <p className="text-xs text-default-500">授权范围</p>
                          <p className="mt-1 truncate font-mono text-sm">
                            {grant.startPort}-{grant.endPort}
                          </p>
                        </div>
                        <div>
                          <p className="text-xs text-default-500">端口总数</p>
                          <p className="mt-1 text-sm font-medium">
                            {grant.totalPorts}
                          </p>
                        </div>
                        <div>
                          <p className="text-xs text-default-500">已发布</p>
                          <p className="mt-1 text-sm font-medium">
                            {grant.usedPorts}
                          </p>
                        </div>
                        <div>
                          <p className="text-xs text-default-500">剩余可用</p>
                          <p className="mt-1 text-sm font-medium text-success">
                            {grant.availablePorts}
                          </p>
                        </div>
                      </div>
                    </div>
                  </div>
                )}
                onMove={portPermissionOrder.moveCard}
              />
            )}
          </CardBody>
        </Card>
      )}

      {/* 节点权限 - 管理员不显示 */}
      {!isAdmin && (
        <Card className="border border-transparent dark:border-default-100">
          <CardHeader className="pb-3">
            <div className="flex items-center gap-2 min-w-0">
              <svg
                className="w-5 h-5 text-primary flex-shrink-0"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  d="M5 12h14M5 6h14M5 18h14M7 6v.01M7 12v.01M7 18v.01"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.8}
                />
              </svg>
              <h2 className="text-lg lg:text-xl font-semibold text-foreground">
                节点权限
              </h2>
              <span className="px-2 py-1 bg-default-100 dark:bg-default-50 text-default-600 rounded-full text-xs">
                {sharedNodes.length}
              </span>
            </div>
          </CardHeader>
          <CardBody className="pt-0">
            {sharedNodes.length === 0 ? (
              <div className="text-center py-10">
                <svg
                  className="w-10 h-10 text-default-400 mx-auto mb-3"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    d="M5 12h14M5 6h14M5 18h14M7 6v.01M7 12v.01M7 18v.01"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={1.5}
                  />
                </svg>
                <p className="text-default-500">暂无节点权限</p>
              </div>
            ) : (
              <SortableCardGrid
                className="space-y-3"
                getId={(node) => node.id}
                items={orderedSharedNodes}
                renderItem={(node, dragHandle) => {
                  const online = node.status === 1;
                  const quotaLimitBytes = (node.quotaFlow || 0) * 1073741824;
                  const quotaRemaining = Math.max(
                    0,
                    quotaLimitBytes - (node.quotaUsedFlow || 0),
                  );

                  return (
                    <div className="rounded-lg border border-gray-200 bg-content1 p-3 transition-colors hover:bg-default-50/70 dark:border-default-100 dark:hover:bg-default-100/40 lg:p-4">
                      <div className="grid gap-3 xl:grid-cols-[minmax(260px,1fr)_minmax(620px,760px)] xl:items-center">
                        <div className="flex items-start gap-3 min-w-0">
                          <div
                            className={`w-9 h-9 rounded-md flex items-center justify-center flex-shrink-0 ${online ? "bg-success-100 dark:bg-success-500/15 text-success-600 dark:text-success-400" : "bg-danger-100/70 dark:bg-danger-500/10 text-danger-600 dark:text-danger-300"}`}
                          >
                            <svg
                              className="w-5 h-5"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path
                                d="M5 12h14M5 6h14M5 18h14M7 6v.01M7 12v.01M7 18v.01"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={1.7}
                              />
                            </svg>
                          </div>
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <h3
                                className="font-semibold text-foreground truncate"
                                title={node.name}
                              >
                                {node.name}
                              </h3>
                              <span className="px-2 py-0.5 rounded-md text-xs font-medium bg-secondary-100 dark:bg-secondary-500/15 text-secondary-700 dark:text-secondary-300">
                                已授权
                              </span>
                              <span
                                className={`px-2 py-0.5 rounded-md text-xs font-medium ${online ? "bg-success-100 dark:bg-success-500/15 text-success-700 dark:text-success-300" : "bg-danger-100/70 dark:bg-danger-500/10 text-danger-700 dark:text-danger-300"}`}
                              >
                                {online ? "在线" : "离线"}
                              </span>
                              {dragHandle}
                            </div>
                            <p className="text-xs text-default-500 mt-1">
                              可用于创建转发和选择出口
                            </p>
                            {node.quotaAvailable === false && (
                              <p className="text-xs text-danger mt-1">
                                {node.unavailableReason}
                              </p>
                            )}
                          </div>
                        </div>

                        <div className="grid min-w-0 grid-cols-2 gap-x-5 gap-y-2 sm:grid-cols-3 xl:grid-cols-5">
                          <div className="min-w-0">
                            <p className="text-xs text-default-500 mb-0.5">
                              授权状态
                            </p>
                            <p className="text-sm text-foreground truncate">
                              {online ? "在线可用" : "暂不可用"}
                            </p>
                          </div>
                          <div className="min-w-0">
                            <p className="text-xs text-default-500 mb-0.5">
                              剩余流量
                            </p>
                            <p className="text-sm text-foreground truncate">
                              {node.quotaFlowUnlimited
                                ? "无限制"
                                : formatFlow(quotaRemaining)}
                            </p>
                          </div>
                          <div className="min-w-0 col-span-2 sm:col-span-1">
                            <p className="text-xs text-default-500 mb-0.5">
                              已用 / 总额
                            </p>
                            <p className="text-sm text-foreground truncate">
                              {node.quotaFlowUnlimited
                                ? formatFlow(node.quotaUsedFlow || 0)
                                : `${formatFlow(node.quotaUsedFlow || 0)} / ${node.quotaFlow || 0} GB`}
                            </p>
                          </div>
                          <div className="min-w-0">
                            <p className="text-xs text-default-500 mb-0.5">
                              流量重置
                            </p>
                            <p className="text-sm text-foreground truncate">
                              {formatResetTime(node.quotaFlowResetTime ?? 0) ||
                                "不重置"}
                            </p>
                          </div>
                          <div className="min-w-0">
                            <p className="text-xs text-default-500 mb-0.5">
                              转发名额
                            </p>
                            <p className="text-sm text-foreground truncate">
                              {node.quotaForwardUnlimited
                                ? "无限制"
                                : `${node.quotaForwardUsed || 0} / ${node.quotaForwardLimit || 0} 个`}
                            </p>
                            <p className="mt-0.5 truncate text-xs text-default-500">
                              {node.quotaForwardUnlimited
                                ? "不计名额"
                                : `剩余 ${Math.max(0, (node.quotaForwardLimit || 0) - (node.quotaForwardUsed || 0))} 个`}
                            </p>
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                }}
                onMove={sharedNodeOrder.moveCard}
              />
            )}
          </CardBody>
        </Card>
      )}

      {/* 隧道权限 - 管理员不显示 */}
      {!isAdmin && (
        <Card className="border border-transparent dark:border-default-100">
          <CardHeader className="pb-3">
            <div className="flex items-center gap-2">
              <svg
                className="w-5 h-5 text-primary"
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path
                  clipRule="evenodd"
                  d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z"
                  fillRule="evenodd"
                />
              </svg>
              <h2 className="text-lg lg:text-xl font-semibold text-foreground">
                隧道权限
              </h2>
              <span className="px-2 py-1 bg-default-100 dark:bg-default-50 text-default-600 rounded-full text-xs">
                {userTunnels.length}
              </span>
            </div>
          </CardHeader>
          <CardBody className="pt-0">
            {userTunnels.length === 0 ? (
              <div className="text-center py-12">
                <svg
                  className="w-12 h-12 text-default-400 mx-auto mb-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={1.5}
                  />
                </svg>
                <p className="text-default-500">暂无隧道权限</p>
              </div>
            ) : (
              <SortableCardGrid
                className="space-y-3"
                getId={(tunnel) => tunnel.id}
                items={orderedUserTunnels}
                renderItem={(tunnel, dragHandle) => {
                  const tunnelExpStatus = getExpStatus(tunnel.expTime);

                  return (
                    <div className="border border-gray-200 dark:border-default-100 rounded-lg p-3 lg:p-4 hover:shadow-md transition-shadow">
                      <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3 mb-3">
                        <div className="min-w-0 flex-1">
                          <h3 className="font-semibold text-foreground">
                            {tunnel.tunnelName} ID: {tunnel.id}
                          </h3>
                          <div className="flex flex-wrap items-center gap-2 mt-1">
                            <span
                              className={`px-2 py-1 rounded-md text-xs font-medium ${tunnel.tunnelFlow === 1 ? "bg-blue-100 dark:bg-blue-500/20 text-blue-700 dark:text-blue-300" : "bg-orange-100 dark:bg-orange-500/20 text-orange-700 dark:text-orange-300"}`}
                            >
                              {tunnel.tunnelFlow === 1
                                ? "单向计费"
                                : "双向计费"}
                            </span>
                            <span
                              className={`px-2 py-1 rounded-md text-xs font-medium border ${tunnelExpStatus.bg} ${tunnelExpStatus.color}`}
                            >
                              {tunnelExpStatus.text}
                            </span>
                            {tunnel.flowResetTime !== undefined &&
                              tunnel.flowResetTime !== null && (
                                <span className="text-xs text-default-500">
                                  {formatResetTime(tunnel.flowResetTime)}
                                </span>
                              )}
                          </div>
                        </div>
                        <div className="flex-shrink-0 self-start lg:self-center">
                          {dragHandle}
                        </div>
                      </div>

                      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 lg:gap-4">
                        <div>
                          <p className="text-sm text-default-600 mb-1">
                            流量配额
                          </p>
                          <p className="font-semibold text-foreground">
                            {tunnel.flowUnlimited === 1
                              ? "无限制"
                              : formatFlow(tunnel.flow, "gb")}
                          </p>
                        </div>
                        <div>
                          <p className="text-sm text-default-600 mb-1">
                            已用流量
                          </p>
                          <p className="font-semibold text-foreground">
                            {formatFlow(calculateTunnelUsedFlow(tunnel))}
                          </p>
                          <div className="mt-1">
                            {renderProgressBar(
                              calculateTunnelFlowPercentage(tunnel),
                              "sm",
                              tunnel.flowUnlimited === 1 ||
                                tunnel.flow === 99999,
                            )}
                          </div>
                        </div>
                        <div>
                          <p className="text-sm text-default-600 mb-1">
                            转发配额
                          </p>
                          <p className="font-semibold text-foreground">
                            {tunnel.forwardUnlimited === 1
                              ? "无限制"
                              : formatNumber(tunnel.num)}
                          </p>
                        </div>
                        <div>
                          <p className="text-sm text-default-600 mb-1">
                            已用转发
                          </p>
                          <p className="font-semibold text-foreground">
                            {getTunnelUsedForwards(tunnel.tunnelId)}
                          </p>
                          <div className="mt-1">
                            {renderProgressBar(
                              calculateTunnelForwardPercentage(tunnel),
                              "sm",
                              tunnel.num === 99999,
                            )}
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                }}
                onMove={tunnelPermissionOrder.moveCard}
              />
            )}
          </CardBody>
        </Card>
      )}
    </PageShell>
  );
}
