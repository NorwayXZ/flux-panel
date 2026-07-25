import { Card, CardBody, CardHeader } from "@heroui/card";
import { useState, useEffect } from "react";
import type { ReactNode } from 'react';
import toast from 'react-hot-toast';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, CircleCheck, Clock3, Gauge, Network, Route, Server, ShieldAlert, Users } from 'lucide-react';

import { SortableCardGrid } from '@/components/sortable-card-grid';
import { useCardOrder } from '@/hooks/use-card-order';

import {
  getAllUsers,
  getForwardList,
  getMonitoringAlerts,
  getMonitoringOverview,
  getNodeList,
  getUserPackageInfo,
  type MonitoringAlertItem,
  type MonitoringOverview,
  type MonitoringResource,
} from "@/api";
import type { User } from '@/types';

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
  accessType?: 'admin' | 'owned' | 'shared';
  ownerUserName?: string;
  quotaFlow?: number;
  quotaUsedFlow?: number;
  quotaFlowUnlimited?: boolean;
  quotaForwardLimit?: number;
  quotaForwardUnlimited?: boolean;
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
  routeMode?: 'single' | 'failover' | 'latency';
  lastRouteSwitch?: number;
  routeSwitchReason?: string;
}

interface StatisticsFlow {
  id: number;
  userId: number;
  flow: number;
  totalFlow: number;
  time: string;
}

const EMPTY_ADMIN_OVERVIEW: MonitoringOverview = {
  range: '24h',
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

export default function DashboardPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [userInfo, setUserInfo] = useState<UserInfo>({} as UserInfo);
  const [userTunnels, setUserTunnels] = useState<UserTunnel[]>([]);
  const [sharedNodes, setSharedNodes] = useState<SharedNode[]>([]);
  const [forwardList, setForwardList] = useState<Forward[]>([]);
  const [statisticsFlows, setStatisticsFlows] = useState<StatisticsFlow[]>([]);
  const [isAdmin, setIsAdmin] = useState(false);
  const [adminOverview, setAdminOverview] = useState<MonitoringOverview>(EMPTY_ADMIN_OVERVIEW);
  const [adminAlerts, setAdminAlerts] = useState<MonitoringAlertItem[]>([]);
  const [adminForwards, setAdminForwards] = useState<AdminForward[]>([]);
  const [adminUsers, setAdminUsers] = useState<User[]>([]);
  const summaryCardIds = isAdmin
    ? ['admin-flow', 'admin-nodes', 'admin-tunnels', 'admin-forwards']
    : ['total-flow', 'used-flow', 'forward-quota', 'used-forwards'];
  const summaryCardOrder = useCardOrder('dashboard-summary-cards', summaryCardIds);
  const orderedSummaryCards = summaryCardOrder.sortItems(summaryCardIds, id => id);
  const sharedNodeOrder = useCardOrder('dashboard-shared-node-cards', sharedNodes.map(node => node.id));
  const orderedSharedNodes = sharedNodeOrder.sortItems(sharedNodes, node => node.id);
  const tunnelPermissionOrder = useCardOrder('dashboard-tunnel-permission-cards', userTunnels.map(tunnel => tunnel.id));
  const orderedUserTunnels = tunnelPermissionOrder.sortItems(userTunnels, tunnel => tunnel.id);

  // 检查有效期通知
  const checkExpirationNotifications = (userInfo: UserInfo, tunnels: UserTunnel[]) => {
    // 避免重复通知，检查是否已经显示过
    const notificationKey = `expiration-${userInfo.expTime}-${tunnels.map(t => t.expTime).join(',')}`;
    const lastNotified = localStorage.getItem('lastNotified');

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
            toast('账户将于明天过期，请及时续费', {
              icon: '⚠️',
              duration: 6000,
              style: { background: '#f59e0b', color: '#fff' }
            });
          } else {
            toast(`账户将于${diffDays}天后过期，请及时续费`, {
              icon: '⚠️',
              duration: 6000,
              style: { background: '#f59e0b', color: '#fff' }
            });
          }
        } else if (diffDays <= 0) {
          hasNotification = true;
          toast('账户已过期，请立即续费', {
            icon: '⚠️',
            duration: 8000,
            style: { background: '#ef4444', color: '#fff' }
          });
        }
      }
    }

    // 检查隧道有效期
    tunnels.forEach(tunnel => {
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
                icon: '⚠️',
                duration: 5000,
                style: { background: '#f59e0b', color: '#fff' }
              });
            } else {
              toast(`隧道"${tunnel.tunnelName}"将于${diffDays}天后过期`, {
                icon: '⚠️',
                duration: 5000,
                style: { background: '#f59e0b', color: '#fff' }
              });
            }
          } else if (diffDays <= 0) {
            hasNotification = true;
            toast(`隧道"${tunnel.tunnelName}"已过期`, {
              icon: '⚠️',
              duration: 6000,
              style: { background: '#ef4444', color: '#fff' }
            });
          }
        }
      }
    });

    // 如果显示了通知，记录防止重复
    if (hasNotification) {
      localStorage.setItem('lastNotified', notificationKey);
    }
  };

  useEffect(() => {
    // 重置状态并加载数据，防止页面切换时显示旧数据
    setLoading(true);
    setUserInfo({} as UserInfo);
    setUserTunnels([]);
    setSharedNodes([]);
    setForwardList([]);
    setStatisticsFlows([]);
    setAdminOverview(EMPTY_ADMIN_OVERVIEW);
    setAdminAlerts([]);
    setAdminForwards([]);
    setAdminUsers([]);

    // 检查用户是否是管理员
    const adminStatus = localStorage.getItem('admin');
    setIsAdmin(adminStatus === 'true');

    loadPackageData();
    localStorage.setItem('e', '/dashboard');
  }, []);

  const loadPackageData = async () => {
    setLoading(true);
    try {
      const admin = localStorage.getItem('admin') === 'true';
      const shouldLoadSharedNodes = !admin;
      const [res, nodeRes, monitoringRes, alertsRes, forwardsRes, usersRes] = await Promise.all([
        getUserPackageInfo(),
        shouldLoadSharedNodes ? getNodeList() : Promise.resolve(null),
        admin ? getMonitoringOverview('24h') : Promise.resolve(null),
        admin ? getMonitoringAlerts({ status: 'open', page: 1, size: 5 }) : Promise.resolve(null),
        admin ? getForwardList() : Promise.resolve(null),
        admin ? getAllUsers() : Promise.resolve(null),
      ]);

      if (res.code === 0) {
        const data = res.data;
        setUserInfo(data.userInfo || {});
        setUserTunnels(data.tunnelPermissions || []);
        setForwardList(data.forwards || []);
        setStatisticsFlows(data.statisticsFlows || []);

        // 检查有效期并显示通知
        checkExpirationNotifications(data.userInfo, data.tunnelPermissions || []);
      } else {
        toast.error(res.msg || '获取套餐信息失败');
      }

      if (nodeRes?.code === 0) {
        const shared = (nodeRes.data || [])
          .filter((node: SharedNode) => node.accessType === 'shared')
          .sort((a: SharedNode, b: SharedNode) => {
            const statusDifference = (b.status || 0) - (a.status || 0);
            return statusDifference !== 0 ? statusDifference : b.id - a.id;
          });
        setSharedNodes(shared);
      } else if (nodeRes) {
        toast.error(nodeRes.msg || '获取节点权限失败');
      }

      if (monitoringRes?.code === 0) setAdminOverview(monitoringRes.data || EMPTY_ADMIN_OVERVIEW);
      if (alertsRes?.code === 0) setAdminAlerts(alertsRes.data?.items || []);
      if (forwardsRes?.code === 0) setAdminForwards((forwardsRes.data || []) as AdminForward[]);
      if (usersRes?.code === 0) setAdminUsers((usersRes.data || []) as User[]);
    } catch (error) {
      console.error('获取套餐信息失败:', error);
      toast.error('获取套餐信息失败');
    } finally {
      setLoading(false);
    }
  };

  const formatFlow = (value: number, unit: string = 'bytes'): string => {
    // 99999 表示无限制
    if (value === 99999) {
      return '无限制';
    }

    if (unit === 'gb') {
      return value + ' GB';
    } else {
      if (value === 0) return '0 B';
      if (value < 1024) return value + ' B';
      if (value < 1024 * 1024) return (value / 1024).toFixed(2) + ' KB';
      if (value < 1024 * 1024 * 1024) return (value / (1024 * 1024)).toFixed(2) + ' MB';
      return (value / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    }
  };

  const formatNumber = (value: number): string => {
    // 99999 表示无限制
    if (value === 99999) {
      return '无限制';
    }
    return value.toString();
  };

  // 处理24小时流量统计数据
  const processFlowChartData = () => {
    // 生成最近24小时的时间数组（从当前小时往前推24小时）
    const now = new Date();
    const hours: string[] = [];
    for (let i = 23; i >= 0; i--) {
      const time = new Date(now.getTime() - i * 60 * 60 * 1000);
      const hourString = time.getHours().toString().padStart(2, '0') + ':00';
      hours.push(hourString);
    }

    // 创建数据映射
    const flowMap = new Map<string, number>();
    statisticsFlows.forEach(item => {
      flowMap.set(item.time, item.flow || 0);
    });

    // 生成图表数据，没有数据的小时显示为0
    return hours.map(hour => ({
      time: hour,
      flow: flowMap.get(hour) || 0,
      // 格式化显示用的流量值
      formattedFlow: formatFlow(flowMap.get(hour) || 0)
    }));
  };


  const getExpStatus = (expTime?: string) => {
    if (!expTime) return {
      color: 'text-green-600 dark:text-green-400',
      bg: 'bg-green-50 dark:bg-green-500/10 border-green-200 dark:border-green-500/20',
      text: '永久'
    };

    const now = new Date();
    const expDate = new Date(expTime);

    if (isNaN(expDate.getTime())) {
      return {
        color: 'text-gray-600 dark:text-gray-400',
        bg: 'bg-gray-50 dark:bg-black/10 border-gray-200 dark:border-gray-500/20',
        text: '无效'
      };
    }

    if (expDate < now) {
      return {
        color: 'text-red-600 dark:text-red-400',
        bg: 'bg-red-50 dark:bg-red-500/10 border-red-200 dark:border-red-500/20',
        text: '已过期'
      };
    }

    const diffTime = expDate.getTime() - now.getTime();
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

    if (diffDays <= 7) {
      return {
        color: 'text-red-600 dark:text-red-400',
        bg: 'bg-red-50 dark:bg-red-500/10 border-red-200 dark:border-red-500/20',
        text: `${diffDays}天后过期`
      };
    } else if (diffDays <= 30) {
      return {
        color: 'text-orange-600 dark:text-orange-400',
        bg: 'bg-orange-50 dark:bg-orange-500/10 border-orange-200 dark:border-orange-500/20',
        text: `${diffDays}天后过期`
      };
    } else {
      return {
        color: 'text-green-600 dark:text-green-400',
        bg: 'bg-green-50 dark:bg-green-500/10 border-green-200 dark:border-green-500/20',
        text: `${diffDays}天后过期`
      };
    }
  };

  const calculateUserTotalUsedFlow = (): number => {
    // 后端已按计费类型处理流量，前端直接使用入站+出站总和
    return (userInfo.inFlow || 0) + (userInfo.outFlow || 0);
  };

  const calculateUsagePercentage = (type: 'flow' | 'forwards'): number => {
    if (type === 'flow') {
      const totalUsed = calculateUserTotalUsedFlow();
      const totalLimit = (userInfo.flow || 0) * 1024 * 1024 * 1024;
      // 无限制时返回0%
      if (userInfo.flowUnlimited || userInfo.flow === 99999) return 0;
      return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
    } else if (type === 'forwards') {
      const totalUsed = forwardList.length;
      const totalLimit = userInfo.num || 0;
      // 无限制时返回0%
      if (userInfo.forwardUnlimited || userInfo.num === 99999) return 0;
      return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
    }
    return 0;
  };

  const getUsageColor = (percentage: number) => {
    if (percentage >= 90) return 'bg-red-500 dark:bg-red-600';
    if (percentage >= 70) return 'bg-orange-500 dark:bg-orange-600';
    return 'bg-blue-500 dark:bg-blue-600';
  };

  const renderProgressBar = (percentage: number, size: 'sm' | 'md' = 'md', isUnlimited: boolean = false) => {
    const height = size === 'sm' ? 'h-1.5' : 'h-2';

    if (isUnlimited) {
      return (
        <div className="w-full">
          <div className={`w-full bg-gradient-to-r from-blue-200 to-purple-200 dark:from-blue-500/30 dark:to-purple-500/30 rounded-full ${height}`}>
            <div className={`${height} bg-gradient-to-r from-blue-500 to-purple-500 rounded-full w-full opacity-60`}></div>
          </div>
        </div>
      );
    }

    return (
      <div className="w-full">
        <div className={`w-full bg-gray-200 dark:bg-gray-800 rounded-full ${height}`}>
          <div
            className={`${height} rounded-full transition-all duration-300 ${getUsageColor(percentage)}`}
            style={{ width: `${Math.min(percentage, 100)}%` }}
          ></div>
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
    return forwardList.filter(forward => forward.tunnelId === tunnelId).length;
  };

  const calculateTunnelForwardPercentage = (tunnel: UserTunnel): number => {
    const totalUsed = getTunnelUsedForwards(tunnel.tunnelId);
    const totalLimit = tunnel.num || 0;
    // 无限制时返回0%
    if (tunnel.num === 99999) return 0;
    return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
  };

  const formatResetTime = (resetDay?: number): string => {
    if (resetDay === undefined || resetDay === null) return '';
    if (resetDay === 0) return '不重置';

    const now = new Date();
    const currentDay = now.getDate();

    let daysUntilReset;
    if (resetDay > currentDay) {
      daysUntilReset = resetDay - currentDay;
    } else if (resetDay < currentDay) {
      const nextMonth = new Date(now.getFullYear(), now.getMonth() + 1, resetDay);
      const diffTime = nextMonth.getTime() - now.getTime();
      daysUntilReset = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
    } else {
      daysUntilReset = 0;
    }

    if (daysUntilReset === 0) {
      return '今日重置';
    } else if (daysUntilReset === 1) {
      return '明日重置';
    } else {
      return `${daysUntilReset}天后重置`;
    }
  };

  const formatRelativeTime = (timestamp?: number) => {
    if (!timestamp) return '暂无记录';
    const minutes = Math.max(0, Math.floor((Date.now() - timestamp) / 60000));
    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} 小时前`;
    return `${Math.floor(hours / 24)} 天前`;
  };

  const formatAlertDuration = (startedAt: number) => {
    const minutes = Math.max(0, Math.floor((Date.now() - startedAt) / 60000));
    if (minutes < 60) return `${Math.max(1, minutes)} 分钟`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} 小时`;
    return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`;
  };

  const resourcesByType = (type: MonitoringResource['type']) =>
    adminOverview.resources.filter(resource => resource.type === type);

  const resourceSummary = (type: MonitoringResource['type']) => {
    const resources = resourcesByType(type);
    return {
      total: resources.length,
      healthy: resources.filter(resource => resource.status === 'healthy').length,
      degraded: resources.filter(resource => resource.status === 'degraded').length,
      offline: resources.filter(resource => resource.status === 'offline').length,
      paused: resources.filter(resource => resource.status === 'paused').length,
      unknown: resources.filter(resource => resource.status === 'unknown').length,
    };
  };

  const nodeHealth = resourceSummary('node');
  const tunnelHealth = resourceSummary('tunnel');
  const forwardHealth = resourceSummary('forward');
  const total24HourFlow = statisticsFlows.reduce((sum, item) => sum + (item.flow || 0), 0);
  const runningForwards = adminForwards.filter(forward => forward.status === 1 && !forward.nodeOffline).length;
  const topTrafficForwards = [...adminForwards]
    .sort((a, b) => ((b.inFlow || 0) + (b.outFlow || 0)) - ((a.inFlow || 0) + (a.outFlow || 0)))
    .slice(0, 5);
  const recentRouteSwitches = adminForwards
    .filter(forward => !!forward.lastRouteSwitch)
    .sort((a, b) => (b.lastRouteSwitch || 0) - (a.lastRouteSwitch || 0))
    .slice(0, 5);
  const quotaRisks = adminUsers.map(user => {
    const totalBytes = (user.totalFlow || 0) * 1024 * 1024 * 1024;
    const usage = !user.totalFlowUnlimited && totalBytes > 0
      ? ((user.totalUsedFlow || 0) / totalBytes) * 100
      : 0;
    const daysToExpire = user.expTime
      ? Math.ceil((user.expTime - Date.now()) / 86400000)
      : null;
    const priority = user.status !== 1 || (daysToExpire !== null && daysToExpire <= 0) || usage >= 100
      ? 3
      : (daysToExpire !== null && daysToExpire <= 7) || usage >= 90
        ? 2
        : (daysToExpire !== null && daysToExpire <= 30) || usage >= 80
          ? 1
          : 0;
    const badge = user.status !== 1
      ? '禁用'
      : daysToExpire !== null && daysToExpire <= 0
        ? '到期'
        : usage >= 80
          ? `${Math.round(usage)}%`
          : `${daysToExpire}天`;
    return { user, usage, daysToExpire, priority, badge };
  }).filter(item => item.priority > 0)
    .sort((a, b) => b.priority - a.priority || b.usage - a.usage)
    .slice(0, 5);

      if (loading) {
      return (

          <div className="px-3 lg:px-6 flex-grow pt-2 lg:pt-4">
            <div className="flex items-center justify-center h-64">
              <div className="flex items-center gap-3">
                <div className="animate-spin h-5 w-5 border-2 border-gray-200 dark:border-gray-700 border-t-gray-600 dark:border-t-gray-300 rounded-full"></div>
                <span className="text-default-600">正在加载数据...</span>
              </div>
            </div>
          </div>

      );
    }

      return (

        <div className="px-3 lg:px-6 py-2 lg:py-4">

                          {/* 响应式统计卡片 */}
         <SortableCardGrid
           items={orderedSummaryCards}
           getId={id => id}
           onMove={summaryCardOrder.moveCard}
           className="grid grid-cols-2 lg:grid-cols-4 gap-3 lg:gap-4 mb-6 lg:mb-8"
           renderItem={(cardId, dragHandle) => {
             const cardClass = "h-full border border-gray-200 dark:border-default-200 shadow-md hover:shadow-lg transition-shadow";
             const header = (label: string, colorClass: string, icon: ReactNode) => (
               <div className="flex items-center justify-between gap-1">
                 <p className="text-xs lg:text-sm text-default-600 truncate">{label}</p>
                 <div className="flex items-center gap-1">
                   {dragHandle}
                   <div className={`p-1.5 lg:p-2 rounded-lg flex-shrink-0 ${colorClass}`}>{icon}</div>
                 </div>
               </div>
             );

             if (isAdmin) {
               if (cardId === 'admin-flow') return (
                 <Card className={cardClass}><CardBody className="p-3 lg:p-4"><div className="flex h-full flex-col justify-between gap-2">
                   {header('24 小时总流量', 'bg-blue-100 dark:bg-blue-500/20', <Gauge className="h-4 w-4 text-blue-600 dark:text-blue-400 lg:h-5 lg:w-5" />)}
                   <div><p className="text-base font-bold text-foreground lg:text-xl">{formatFlow(total24HourFlow)}</p><p className="mt-1 text-xs text-default-500">全部用户计费流量</p></div>
                 </div></CardBody></Card>
               );
               if (cardId === 'admin-nodes') return (
                 <Card className={cardClass}><CardBody className="p-3 lg:p-4"><div className="flex h-full flex-col justify-between gap-2">
                   {header('在线节点', 'bg-emerald-100 dark:bg-emerald-500/20', <Server className="h-4 w-4 text-emerald-600 dark:text-emerald-400 lg:h-5 lg:w-5" />)}
                   <div><p className="text-base font-bold text-foreground lg:text-xl">{nodeHealth.healthy} / {nodeHealth.total}</p><p className="mt-1 text-xs text-default-500">{nodeHealth.offline + nodeHealth.degraded} 台需要关注</p></div>
                 </div></CardBody></Card>
               );
               if (cardId === 'admin-tunnels') return (
                 <Card className={cardClass}><CardBody className="p-3 lg:p-4"><div className="flex h-full flex-col justify-between gap-2">
                   {header('健康隧道', 'bg-cyan-100 dark:bg-cyan-500/20', <Route className="h-4 w-4 text-cyan-700 dark:text-cyan-300 lg:h-5 lg:w-5" />)}
                   <div><p className="text-base font-bold text-foreground lg:text-xl">{tunnelHealth.healthy} / {tunnelHealth.total}</p><p className="mt-1 text-xs text-default-500">{tunnelHealth.offline + tunnelHealth.degraded} 条链路异常</p></div>
                 </div></CardBody></Card>
               );
               return (
                 <Card className={cardClass}><CardBody className="p-3 lg:p-4"><div className="flex h-full flex-col justify-between gap-2">
                   {header('运行中转发', 'bg-orange-100 dark:bg-orange-500/20', <Network className="h-4 w-4 text-orange-700 dark:text-orange-300 lg:h-5 lg:w-5" />)}
                   <div><p className="text-base font-bold text-foreground lg:text-xl">{runningForwards} / {adminForwards.length}</p><p className="mt-1 text-xs text-default-500">{Math.max(0, adminForwards.length - runningForwards)} 条暂停或异常</p></div>
                 </div></CardBody></Card>
               );
             }

             if (cardId === 'total-flow') return (
               <Card className={cardClass}><CardBody className="p-3 lg:p-4"><div className="flex flex-col space-y-2">
                 {header('总流量', 'bg-blue-100 dark:bg-blue-500/20', <svg className="w-4 h-4 lg:w-5 lg:h-5 text-blue-600 dark:text-blue-400" fill="currentColor" viewBox="0 0 20 20"><path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" /></svg>)}
                 <p className="text-base lg:text-xl font-bold text-foreground truncate">{userInfo.flowUnlimited ? '无限制' : formatFlow(userInfo.flow, 'gb')}</p>
               </div></CardBody></Card>
             );
             if (cardId === 'used-flow') return (
               <Card className={cardClass}><CardBody className="p-3 lg:p-4"><div className="flex flex-col space-y-2">
                 {header('已用流量', 'bg-green-100 dark:bg-green-500/20', <svg className="w-4 h-4 lg:w-5 lg:h-5 text-green-600 dark:text-green-400" fill="currentColor" viewBox="0 0 20 20"><path fillRule="evenodd" d="M12 7a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0V8.414l-4.293 4.293a1 1 0 01-1.414 0L8 10.414l-4.293 4.293a1 1 0 01-1.414-1.414l5-5a1 1 0 011.414 0L11 10.586 14.586 7H12z" clipRule="evenodd" /></svg>)}
                 <p className="text-base lg:text-xl font-bold text-foreground truncate">{formatFlow(calculateUserTotalUsedFlow())}</p>
                 <div className="mt-1">{renderProgressBar(calculateUsagePercentage('flow'), 'sm', !!userInfo.flowUnlimited || userInfo.flow === 99999)}<div className="flex items-center justify-between mt-1"><p className="text-xs text-default-500 truncate">{userInfo.flowUnlimited || userInfo.flow === 99999 ? '无限制' : `${calculateUsagePercentage('flow').toFixed(1)}%`}</p>{userInfo.flowResetTime !== undefined && userInfo.flowResetTime !== null && <span className="text-xs text-default-500 truncate">{formatResetTime(userInfo.flowResetTime)}</span>}</div></div>
               </div></CardBody></Card>
             );
             if (cardId === 'forward-quota') return (
               <Card className={cardClass}><CardBody className="p-3 lg:p-4"><div className="flex flex-col space-y-2">
                 {header('转发配额', 'bg-purple-100 dark:bg-purple-500/20', <svg className="w-4 h-4 lg:w-5 lg:h-5 text-purple-600 dark:text-purple-400" fill="currentColor" viewBox="0 0 20 20"><path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z" clipRule="evenodd" /></svg>)}
                 <p className="text-base lg:text-xl font-bold text-foreground truncate">{userInfo.forwardUnlimited ? '无限制' : formatNumber(userInfo.num || 0)}</p>
               </div></CardBody></Card>
             );
             return (
               <Card className={cardClass}><CardBody className="p-3 lg:p-4"><div className="flex flex-col space-y-2">
                 {header('已用转发', 'bg-orange-100 dark:bg-orange-500/20', <svg className="w-4 h-4 lg:w-5 lg:h-5 text-orange-600 dark:text-orange-400" fill="currentColor" viewBox="0 0 20 20"><path fillRule="evenodd" d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z" clipRule="evenodd" /></svg>)}
                 <p className="text-base lg:text-xl font-bold text-foreground truncate">{forwardList.length}</p>
                 <div className="mt-1">{renderProgressBar(calculateUsagePercentage('forwards'), 'sm', !!userInfo.forwardUnlimited || userInfo.num === 99999)}<p className="text-xs text-default-500 mt-1 truncate">{userInfo.forwardUnlimited || userInfo.num === 99999 ? '无限制' : `${calculateUsagePercentage('forwards').toFixed(1)}%`}</p></div>
               </div></CardBody></Card>
             );
           }}
         />

         {/* 24小时流量统计图表 */}
         <Card className="mb-6 lg:mb-8 border border-gray-200 dark:border-default-200 shadow-md">
           <CardHeader className="pb-3">
             <div className="flex items-center gap-2">
               <svg className="w-5 h-5 text-primary" fill="currentColor" viewBox="0 0 20 20">
                 <path d="M2 10a8 8 0 018-8v8h8a8 8 0 11-16 0z" />
                 <path d="M12 2.252A8.014 8.014 0 0117.748 8H12V2.252z" />
               </svg>
               <h2 className="text-lg lg:text-xl font-semibold text-foreground">24小时流量统计</h2>
             </div>
           </CardHeader>
           <CardBody className="pt-0">
             {statisticsFlows.length === 0 ? (
               <div className="text-center py-12">
                 <svg className="w-12 h-12 text-default-400 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                   <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                 </svg>
                 <p className="text-default-500">暂无流量统计数据</p>
               </div>
             ) : (
               <div className="space-y-4">

                                    {/* 流量趋势图 */}
                   <div className="h-64 lg:h-80 w-full">
                     <ResponsiveContainer width="100%" height="100%">
                       <LineChart data={processFlowChartData()}>
                         <CartesianGrid strokeDasharray="3 3" className="opacity-30" />
                         <XAxis
                           dataKey="time"
                           tick={{ fontSize: 12 }}
                           tickLine={false}
                           axisLine={{ stroke: '#e5e7eb', strokeWidth: 1 }}
                         />
                         <YAxis
                           tick={{ fontSize: 12 }}
                           tickLine={false}
                           axisLine={{ stroke: '#e5e7eb', strokeWidth: 1 }}
                           tickFormatter={(value) => {
                             if (value === 0) return '0';
                             if (value < 1024) return `${value}B`;
                             if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)}K`;
                             if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)}M`;
                             return `${(value / (1024 * 1024 * 1024)).toFixed(1)}G`;
                           }}
                         />
                         <Tooltip
                           content={({ active, payload, label }) => {
                             if (active && payload && payload.length) {
                               return (
                                 <div className="bg-white dark:bg-default-100 border border-default-200 rounded-lg shadow-lg p-3">
                                   <p className="font-medium text-foreground">{`时间: ${label}`}</p>
                                   <p className="text-primary">
                                     {`流量: ${formatFlow(payload[0]?.value as number || 0)}`}
                                   </p>
                                 </div>
                               );
                             }
                             return null;
                           }}
                         />
                         <Line
                           type="monotone"
                           dataKey="flow"
                           stroke="#8b5cf6"
                           strokeWidth={3}
                           dot={false}
                           activeDot={{ r: 4, stroke: '#8b5cf6', strokeWidth: 2, fill: '#fff' }}
                         />
                       </LineChart>
                     </ResponsiveContainer>
                   </div>
               </div>
             )}
           </CardBody>
         </Card>

         {isAdmin && (
           <div className="space-y-5 lg:space-y-6">
             <section className="border-y border-gray-200 bg-content1 px-3 py-5 dark:border-default-200 sm:px-5" aria-label="资源健康概览">
               <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                 <div>
                   <h2 className="text-base font-semibold text-foreground lg:text-lg">资源健康概览</h2>
                   <p className="mt-1 text-xs text-default-500">综合在线率 {adminOverview.summary.availability.toFixed(2)}%</p>
                 </div>
                 <button type="button" onClick={() => navigate('/monitoring')} className="flex min-h-9 items-center gap-2 rounded-md px-3 text-sm font-medium text-primary hover:bg-primary-50 dark:hover:bg-primary-500/10">
                   {adminOverview.summary.openAlerts} 条待处理告警 <ArrowRight className="h-4 w-4" />
                 </button>
               </div>
               <div className="grid grid-cols-1 divide-y divide-gray-200 dark:divide-gray-800 sm:grid-cols-3 sm:divide-x sm:divide-y-0">
                 {[
                   { label: '节点', path: '/node', summary: nodeHealth, icon: <Server className="h-5 w-5" /> },
                   { label: '隧道', path: '/tunnel', summary: tunnelHealth, icon: <Route className="h-5 w-5" /> },
                   { label: '转发', path: '/forward', summary: forwardHealth, icon: <Network className="h-5 w-5" /> },
                 ].map(item => (
                   <button key={item.label} type="button" onClick={() => navigate(item.path)} className="group flex min-h-28 flex-col justify-between gap-3 px-1 py-4 text-left first:pt-0 last:pb-0 sm:px-5 sm:py-0 sm:first:pl-0 sm:last:pr-0">
                     <div className="flex w-full items-center justify-between gap-3">
                       <span className="flex items-center gap-2 font-medium text-foreground">{item.icon}{item.label}</span>
                       <ArrowRight className="h-4 w-4 text-default-400 transition-transform group-hover:translate-x-1" />
                     </div>
                     <div className="flex flex-wrap items-end gap-x-4 gap-y-2">
                       <div><p className="text-2xl font-semibold text-foreground">{item.summary.healthy}<span className="ml-1 text-sm font-normal text-default-500">/ {item.summary.total}</span></p><p className="text-xs text-default-500">健康</p></div>
                       <div className="flex gap-3 pb-0.5 text-xs">
                         <span className="text-amber-600 dark:text-amber-400">降级 {item.summary.degraded}</span>
                         <span className="text-rose-600 dark:text-rose-400">异常 {item.summary.offline}</span>
                         <span className="text-default-500">暂停 {item.summary.paused}</span>
                         {item.summary.unknown > 0 && <span className="text-default-500">未知 {item.summary.unknown}</span>}
                       </div>
                     </div>
                   </button>
                 ))}
               </div>
             </section>

             <div className="grid grid-cols-1 gap-5 xl:grid-cols-5">
               <Card radius="sm" className="border border-gray-200 shadow-sm dark:border-default-200 xl:col-span-3">
                 <CardHeader className="flex items-center justify-between gap-3 pb-2">
                   <div className="flex items-center gap-2"><ShieldAlert className="h-5 w-5 text-rose-500" /><h2 className="text-base font-semibold">异常与待处理</h2></div>
                   <button type="button" onClick={() => navigate('/monitoring')} className="text-sm font-medium text-primary">查看全部</button>
                 </CardHeader>
                 <CardBody className="pt-1">
                   {adminAlerts.length === 0 ? (
                     <div className="flex min-h-48 flex-col items-center justify-center text-center"><CircleCheck className="mb-3 h-9 w-9 text-emerald-500" /><p className="font-medium text-foreground">当前没有待处理告警</p><p className="mt-1 text-sm text-default-500">节点、隧道和转发均未报告异常</p></div>
                   ) : (
                     <div className="divide-y divide-gray-200 dark:divide-gray-800">
                       {adminAlerts.map(alert => <button key={alert.id} type="button" onClick={() => navigate('/monitoring')} className="flex w-full items-start gap-3 py-3 text-left first:pt-1 last:pb-1">
                         <span className={`mt-1 h-2.5 w-2.5 shrink-0 rounded-full ${alert.severity === 'critical' ? 'bg-rose-500' : 'bg-amber-400'}`} />
                         <span className="min-w-0 flex-1"><span className="flex flex-wrap items-center gap-2"><strong className="truncate text-sm font-medium text-foreground">{alert.resourceName}</strong><span className="text-xs text-default-500">{alert.ownerUserName || '管理员'}</span></span><span className="mt-1 block truncate text-xs text-default-500">{alert.title} · {alert.detail}</span></span>
                         <span className="shrink-0 text-xs text-default-500">持续 {formatAlertDuration(alert.startedAt)}</span>
                       </button>)}
                     </div>
                   )}
                 </CardBody>
               </Card>

               <Card radius="sm" className="border border-gray-200 shadow-sm dark:border-default-200 xl:col-span-2">
                 <CardHeader className="flex items-center justify-between gap-3 pb-2"><div className="flex items-center gap-2"><Gauge className="h-5 w-5 text-blue-500" /><h2 className="text-base font-semibold">转发流量排行</h2></div><span className="text-xs text-default-500">累计计费流量</span></CardHeader>
                 <CardBody className="pt-1">
                   {topTrafficForwards.length === 0 ? <div className="flex min-h-48 items-center justify-center text-sm text-default-500">暂无转发流量</div> : <div className="space-y-3">
                     {topTrafficForwards.map((forward, index) => {
                       const flow = (forward.inFlow || 0) + (forward.outFlow || 0);
                       const maxFlow = (topTrafficForwards[0]?.inFlow || 0) + (topTrafficForwards[0]?.outFlow || 0);
                       return <button key={forward.id} type="button" onClick={() => navigate('/forward')} className="block w-full text-left">
                         <div className="mb-1.5 flex items-center justify-between gap-3 text-sm"><span className="min-w-0 truncate"><span className="mr-2 text-default-400">{index + 1}</span><span className="font-medium text-foreground">{forward.name}</span><span className="ml-2 text-xs text-default-500">{forward.userName || '管理员'}</span></span><span className="shrink-0 font-medium text-foreground">{formatFlow(flow)}</span></div>
                         <div className="h-1.5 overflow-hidden rounded-full bg-default-100"><div className="h-full rounded-full bg-blue-500" style={{ width: `${maxFlow > 0 ? Math.max(3, (flow / maxFlow) * 100) : 0}%` }} /></div>
                       </button>;
                     })}
                   </div>}
                 </CardBody>
               </Card>
             </div>

             <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
               <Card radius="sm" className="border border-gray-200 shadow-sm dark:border-default-200">
                 <CardHeader className="flex items-center justify-between gap-3 pb-2"><div className="flex items-center gap-2"><Users className="h-5 w-5 text-amber-500" /><h2 className="text-base font-semibold">额度与到期风险</h2></div><button type="button" onClick={() => navigate('/user')} className="text-sm font-medium text-primary">用户管理</button></CardHeader>
                 <CardBody className="pt-1">
                   {quotaRisks.length === 0 ? <div className="flex min-h-44 flex-col items-center justify-center text-center"><CircleCheck className="mb-3 h-8 w-8 text-emerald-500" /><p className="text-sm text-default-500">当前没有高风险用户</p></div> : <div className="divide-y divide-gray-200 dark:divide-gray-800">
                     {quotaRisks.map(item => <button key={item.user.id} type="button" onClick={() => navigate('/user')} className="flex w-full items-center gap-3 py-3 text-left first:pt-1 last:pb-1">
                       <span className={`flex h-8 min-w-8 shrink-0 items-center justify-center rounded-md px-1 text-xs font-semibold ${item.priority >= 3 ? 'bg-rose-100 text-rose-700 dark:bg-rose-500/10 dark:text-rose-300' : 'bg-amber-100 text-amber-700 dark:bg-amber-500/10 dark:text-amber-300'}`}>{item.badge}</span>
                       <span className="min-w-0 flex-1"><strong className="block truncate text-sm font-medium text-foreground">{item.user.user}</strong><span className="mt-1 block truncate text-xs text-default-500">{item.user.status !== 1 ? '账号已禁用' : item.daysToExpire !== null && item.daysToExpire <= 0 ? '账号已到期' : item.daysToExpire !== null && item.daysToExpire <= 30 ? `${item.daysToExpire} 天后到期` : `额度已使用 ${item.usage.toFixed(1)}%`}</span></span>
                       <ArrowRight className="h-4 w-4 shrink-0 text-default-400" />
                     </button>)}
                   </div>}
                 </CardBody>
               </Card>

               <Card radius="sm" className="border border-gray-200 shadow-sm dark:border-default-200">
                 <CardHeader className="flex items-center justify-between gap-3 pb-2"><div className="flex items-center gap-2"><Clock3 className="h-5 w-5 text-cyan-500" /><h2 className="text-base font-semibold">线路切换动态</h2></div><button type="button" onClick={() => navigate('/forward')} className="text-sm font-medium text-primary">转发管理</button></CardHeader>
                 <CardBody className="pt-1">
                   {recentRouteSwitches.length === 0 ? <div className="flex min-h-44 items-center justify-center text-sm text-default-500">暂无线路切换记录</div> : <div className="divide-y divide-gray-200 dark:divide-gray-800">
                     {recentRouteSwitches.map(forward => <button key={forward.id} type="button" onClick={() => navigate('/forward')} className="flex w-full items-start gap-3 py-3 text-left first:pt-1 last:pb-1">
                       <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-cyan-100 text-cyan-700 dark:bg-cyan-500/10 dark:text-cyan-300"><Route className="h-4 w-4" /></span>
                       <span className="min-w-0 flex-1"><span className="flex flex-wrap items-center gap-2"><strong className="truncate text-sm font-medium text-foreground">{forward.name}</strong><span className="text-xs text-default-500">{forward.userName || '管理员'}</span></span><span className="mt-1 block truncate text-xs text-default-500">{forward.routeSwitchReason || '线路状态变化'}</span></span>
                       <span className="shrink-0 text-xs text-default-500">{formatRelativeTime(forward.lastRouteSwitch)}</span>
                     </button>)}
                   </div>}
                 </CardBody>
               </Card>
             </div>
           </div>
         )}

         {/* 节点权限 - 管理员不显示 */}
         {!isAdmin && (
          <Card className="mb-6 lg:mb-8 border border-gray-200 dark:border-default-200 shadow-md">
            <CardHeader className="pb-3">
              <div className="flex items-center gap-2 min-w-0">
                <svg className="w-5 h-5 text-primary flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M5 12h14M5 6h14M5 18h14M7 6v.01M7 12v.01M7 18v.01" />
                </svg>
                <h2 className="text-lg lg:text-xl font-semibold text-foreground">节点权限</h2>
                <span className="px-2 py-1 bg-default-100 dark:bg-default-50 text-default-600 rounded-full text-xs">
                  {sharedNodes.length}
                </span>
              </div>
            </CardHeader>
            <CardBody className="pt-0">
              {sharedNodes.length === 0 ? (
                <div className="text-center py-10">
                  <svg className="w-10 h-10 text-default-400 mx-auto mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M5 12h14M5 6h14M5 18h14M7 6v.01M7 12v.01M7 18v.01" />
                  </svg>
                  <p className="text-default-500">暂无节点权限</p>
                </div>
              ) : (
                <SortableCardGrid
                  items={orderedSharedNodes}
                  getId={node => node.id}
                  onMove={sharedNodeOrder.moveCard}
                  className="space-y-3"
                  renderItem={(node, dragHandle) => {
                    const online = node.status === 1;
                    const entranceIp = node.ip?.split(',')[0]?.trim() || '-';
                    const additionalIpCount = Math.max((node.ip?.split(',').length || 1) - 1, 0);
                    const portRange = node.portSta && node.portEnd ? `${node.portSta}-${node.portEnd}` : '-';

                    return (
                      <div className="rounded-lg border border-gray-200 bg-content1 p-3 transition-colors hover:bg-default-50/70 dark:border-default-100 dark:hover:bg-default-100/40 lg:p-4">
                        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                          <div className="flex items-start gap-3 min-w-0">
                            <div className={`w-9 h-9 rounded-md flex items-center justify-center flex-shrink-0 ${online ? 'bg-success-100 dark:bg-success-500/15 text-success-600 dark:text-success-400' : 'bg-danger-100/70 dark:bg-danger-500/10 text-danger-600 dark:text-danger-300'}`}>
                              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.7} d="M5 12h14M5 6h14M5 18h14M7 6v.01M7 12v.01M7 18v.01" />
                              </svg>
                            </div>
                            <div className="min-w-0">
                              <div className="flex flex-wrap items-center gap-2">
                                <h3 className="font-semibold text-foreground truncate" title={node.name}>{node.name}</h3>
                                <span className="px-2 py-0.5 rounded-md text-xs font-medium bg-secondary-100 dark:bg-secondary-500/15 text-secondary-700 dark:text-secondary-300">
                                  共享 · {node.ownerUserName || '管理员'}
                                </span>
                                <span className={`px-2 py-0.5 rounded-md text-xs font-medium ${online ? 'bg-success-100 dark:bg-success-500/15 text-success-700 dark:text-success-300' : 'bg-danger-100/70 dark:bg-danger-500/10 text-danger-700 dark:text-danger-300'}`}>
                                  {online ? '在线' : '离线'}
                                </span>
                                {dragHandle}
                              </div>
                              <p className="text-xs text-default-500 mt-1">节点 ID: {node.id} · 只读共享</p>
                              {node.quotaAvailable === false && <p className="text-xs text-danger mt-1">{node.unavailableReason}</p>}
                            </div>
                          </div>

                          <div className="grid grid-cols-2 sm:grid-cols-5 gap-x-5 gap-y-2 lg:min-w-[700px]">
                            <div className="min-w-0">
                              <p className="text-xs text-default-500 mb-0.5">入口 IP</p>
                              <p className="text-sm font-mono text-foreground truncate" title={node.ip || '-'}>
                                {entranceIp}{additionalIpCount > 0 ? ` +${additionalIpCount}` : ''}
                              </p>
                            </div>
                            <div className="min-w-0">
                              <p className="text-xs text-default-500 mb-0.5">端口范围</p>
                              <p className="text-sm font-mono text-foreground truncate" title={portRange}>{portRange}</p>
                            </div>
                            <div className="min-w-0 col-span-2 sm:col-span-1">
                              <p className="text-xs text-default-500 mb-0.5">节点版本</p>
                              <p className="text-sm text-foreground truncate" title={node.version || '未知'}>{node.version || '未知'}</p>
                            </div>
                            <div className="min-w-0"><p className="text-xs text-default-500 mb-0.5">流量额度</p><p className="text-sm text-foreground truncate">{node.quotaFlowUnlimited ? '无限制' : `${((node.quotaUsedFlow || 0) / 1073741824).toFixed(1)} / ${node.quotaFlow || 0} GB`}</p></div>
                            <div className="min-w-0"><p className="text-xs text-default-500 mb-0.5">转发名额</p><p className="text-sm text-foreground truncate">{node.quotaForwardUnlimited ? '无限制' : `${node.quotaForwardLimit || 0} 个`}</p></div>
                          </div>
                        </div>
                      </div>
                    );
                  }}
                />
              )}
            </CardBody>
          </Card>
         )}

         {/* 隧道权限 - 管理员不显示 */}
         {!isAdmin && (
          <Card className="mb-6 lg:mb-8 border border-gray-200 dark:border-default-200 shadow-md">
           <CardHeader className="pb-3">
             <div className="flex items-center gap-2">
               <svg className="w-5 h-5 text-primary" fill="currentColor" viewBox="0 0 20 20">
                 <path fillRule="evenodd" d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z" clipRule="evenodd" />
               </svg>
               <h2 className="text-lg lg:text-xl font-semibold text-foreground">隧道权限</h2>
               <span className="px-2 py-1 bg-default-100 dark:bg-default-50 text-default-600 rounded-full text-xs">
                 {userTunnels.length}
               </span>
             </div>
           </CardHeader>
           <CardBody className="pt-0">
            {userTunnels.length === 0 ? (
              <div className="text-center py-12">
                <svg className="w-12 h-12 text-default-400 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
                </svg>
                <p className="text-default-500">暂无隧道权限</p>
              </div>
            ) : (
              <SortableCardGrid
                items={orderedUserTunnels}
                getId={tunnel => tunnel.id}
                onMove={tunnelPermissionOrder.moveCard}
                className="space-y-3"
                renderItem={(tunnel, dragHandle) => {
                   const tunnelExpStatus = getExpStatus(tunnel.expTime);
                   return (
                     <div className="border border-gray-200 dark:border-default-100 rounded-lg p-3 lg:p-4 hover:shadow-md transition-shadow">
                       <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3 mb-3">
                         <div className="min-w-0 flex-1">
                           <h3 className="font-semibold text-foreground">{tunnel.tunnelName} ID: {tunnel.id}</h3>
                           <div className="flex flex-wrap items-center gap-2 mt-1">
                             <span className={`px-2 py-1 rounded-md text-xs font-medium ${tunnel.tunnelFlow === 1 ? 'bg-blue-100 dark:bg-blue-500/20 text-blue-700 dark:text-blue-300' : 'bg-orange-100 dark:bg-orange-500/20 text-orange-700 dark:text-orange-300'}`}>
                               {tunnel.tunnelFlow === 1 ? '单向计费' : '双向计费'}
                             </span>
                             <span className={`px-2 py-1 rounded-md text-xs font-medium border ${tunnelExpStatus.bg} ${tunnelExpStatus.color}`}>
                               {tunnelExpStatus.text}
                             </span>
                             {(tunnel.flowResetTime !== undefined && tunnel.flowResetTime !== null) && (
                               <span className="text-xs text-default-500">
                                 {formatResetTime(tunnel.flowResetTime)}
                               </span>
                             )}
                           </div>
                         </div>
                         <div className="flex-shrink-0 self-start lg:self-center">{dragHandle}</div>
                       </div>

                       <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 lg:gap-4">
                         <div>
                           <p className="text-sm text-default-600 mb-1">流量配额</p>
                           <p className="font-semibold text-foreground">{tunnel.flowUnlimited === 1 ? '无限制' : formatFlow(tunnel.flow, 'gb')}</p>
                         </div>
                         <div>
                           <p className="text-sm text-default-600 mb-1">已用流量</p>
                           <p className="font-semibold text-foreground">{formatFlow(calculateTunnelUsedFlow(tunnel))}</p>
                           <div className="mt-1">
                             {renderProgressBar(calculateTunnelFlowPercentage(tunnel), 'sm', tunnel.flowUnlimited === 1 || tunnel.flow === 99999)}
                           </div>
                         </div>
                         <div>
                           <p className="text-sm text-default-600 mb-1">转发配额</p>
                           <p className="font-semibold text-foreground">{tunnel.forwardUnlimited === 1 ? '无限制' : formatNumber(tunnel.num)}</p>
                         </div>
                         <div>
                           <p className="text-sm text-default-600 mb-1">已用转发</p>
                           <p className="font-semibold text-foreground">{getTunnelUsedForwards(tunnel.tunnelId)}</p>
                           <div className="mt-1">
                             {renderProgressBar(calculateTunnelForwardPercentage(tunnel), 'sm', tunnel.num === 99999)}
                           </div>
                         </div>
                       </div>
                     </div>
                   );
                }}
              />
            )}
          </CardBody>
        </Card>
         )}

      </div>

  );
}
