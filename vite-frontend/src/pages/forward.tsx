import { useState, useEffect } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Textarea } from "@heroui/input";
import { Select, SelectItem, SelectSection } from "@heroui/select";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import { Alert } from "@heroui/alert";
import { Accordion, AccordionItem } from "@heroui/accordion";
import toast from 'react-hot-toast';
import type { ReactNode } from 'react';
import { History, Route, ShieldCheck, ShieldAlert } from 'lucide-react';

import { SortableCardGrid } from '@/components/sortable-card-grid';
import { useCardOrder } from '@/hooks/use-card-order';

import {
  createForward,
  getForwardList,
  updateForward,
  deleteForward,
  forceDeleteForward,
  userTunnel,
  pauseForwardService,
  resumeForwardService,
  diagnoseForward,
  getForwardRouteEvents
} from "@/api";
import { JwtUtil } from "@/utils/jwt";

interface Forward {
  id: number;
  name: string;
  tunnelId: number;
  tunnelName: string;
  inIp: string;
  outIp?: string;
  nodePath?: string;
  inNodeId?: number;
  outNodeId?: number;
  inNodeStatus?: number;
  outNodeStatus?: number;
  nodeOffline?: boolean;
  type?: number;
  protocol?: string;
  inPort: number;
  remoteAddr: string;
  interfaceName?: string;
  strategy: string;
  status: number;
  inFlow: number;
  outFlow: number;
  serviceRunning: boolean;
  createdTime: string | number;
  userName?: string;
  userId?: number;
  inx?: number;
  routeMode?: 'single' | 'failover' | 'latency' | 'balance';
  routeBalanceStrategy?: 'round' | 'rand' | 'weighted' | 'hash';
  routeConfig?: string;
  activeTunnelId?: number;
  protocolMode?: 'tcp' | 'udp' | 'tcp_udp';
  targetHealth?: string;
  lastHealthCheck?: number;
  previousActiveTunnelId?: number;
  lastRouteSwitch?: number;
  routeSwitchReason?: string;
  routeSwitchCount?: number;
}

interface Tunnel {
  id: number;
  name: string;
  type?: number;
  inNodeId?: number;
  outNodeId?: number;
  nodePath?: string;
  inNodePortSta?: number;
  inNodePortEnd?: number;
  ownerUserName?: string;
  accessType?: 'admin' | 'owned' | 'shared';
}

interface TunnelLineGroup {
  key: string;
  title: string;
  level: number;
  tunnels: Tunnel[];
}

interface ForwardForm {
  id?: number;
  userId?: number;
  name: string;
  tunnelId: number | null;
  inPort: number | null;
  remoteAddr: string;
  interfaceName?: string;
  strategy: string;
  routeMode: 'single' | 'failover' | 'latency' | 'balance';
  routeBalanceStrategy: 'round' | 'rand' | 'weighted' | 'hash';
  routeWeights: Record<number, number>;
  routeTunnelIds: number[];
  protocolMode: 'tcp' | 'udp' | 'tcp_udp';
  batchMode: boolean;
  batchEndPort: number | null;
  targetStartPort: number | null;
}

interface ForwardRoute {
  tunnelId: number;
  tunnelName?: string;
  priority?: number;
  weight?: number;
  enabled?: boolean;
  draining?: boolean;
  status?: 'unknown' | 'healthy' | 'unhealthy';
  latency?: number | null;
  packetLoss?: number | null;
  failCount?: number;
  successCount?: number;
  healthySince?: number;
  lastFailureTime?: number;
  lastSuccessTime?: number;
  lastCheckTime?: number;
  message?: string;
  healthyTargets?: string[];
}

interface TargetHealth {
  address: string;
  status: 'healthy' | 'unhealthy';
  latency?: number;
  packetLoss?: number;
}

interface RouteSwitchEvent {
  id: number;
  forwardId: number;
  fromTunnelId?: number;
  fromTunnelName?: string;
  toTunnelId?: number;
  toTunnelName?: string;
  reason: string;
  triggerType: 'failure' | 'recovery' | 'latency';
  status: 'success' | 'failed';
  detail?: string;
  createdAt: number;
}

interface AddressItem {
  id: number;
  address: string;
  copying: boolean;
}

interface DiagnosisResult {
  forwardName: string;
  timestamp: number;
  results: Array<{
    success: boolean;
    description: string;
    nodeName: string;
    nodeId: string;
    targetIp: string;
    targetPort?: number;
    message?: string;
    averageTime?: number;
    packetLoss?: number;
  }>;
}

interface TunnelForwardGroup {
  tunnelId: number;
  tunnelName: string;
  forwards: Forward[];
  latestCreatedTime: number;
  runningForwardCount: number;
  offlineForwardCount: number;
  totalInFlow: number;
  totalOutFlow: number;
  userNames: string[];
}

export default function ForwardPage() {
  const [loading, setLoading] = useState(true);
  const [forwards, setForwards] = useState<Forward[]>([]);
  const [tunnels, setTunnels] = useState<Tunnel[]>([]);
  const forwardCardOrder = useCardOrder('forward-cards', forwards.map(forward => forward.id));

  // 显示模式状态 - 新版默认按隧道归纳，保留平铺排序作为备用视图
  const [viewMode, setViewMode] = useState<'grouped' | 'direct'>(() => {
    try {
      const savedMode = localStorage.getItem('forward-view-mode-v2');
      return savedMode === 'direct' || savedMode === 'grouped' ? savedMode : 'grouped';
    } catch {
      return 'grouped';
    }
  });

  // 模态框状态
  const [modalOpen, setModalOpen] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [addressModalOpen, setAddressModalOpen] = useState(false);
  const [diagnosisModalOpen, setDiagnosisModalOpen] = useState(false);
  const [failoverModalOpen, setFailoverModalOpen] = useState(false);
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [diagnosisLoading, setDiagnosisLoading] = useState(false);
  const [failoverLoading, setFailoverLoading] = useState(false);
  const [forwardToDelete, setForwardToDelete] = useState<Forward | null>(null);
  const [currentDiagnosisForward, setCurrentDiagnosisForward] = useState<Forward | null>(null);
  const [diagnosisResult, setDiagnosisResult] = useState<DiagnosisResult | null>(null);
  const [currentFailoverForward, setCurrentFailoverForward] = useState<Forward | null>(null);
  const [routeSwitchEvents, setRouteSwitchEvents] = useState<RouteSwitchEvent[]>([]);
  const [addressModalTitle, setAddressModalTitle] = useState('');
  const [addressList, setAddressList] = useState<AddressItem[]>([]);

  // 导出相关状态
  const [exportModalOpen, setExportModalOpen] = useState(false);
  const [exportData, setExportData] = useState('');
  const [exportLoading, setExportLoading] = useState(false);
  const [selectedTunnelForExport, setSelectedTunnelForExport] = useState<number | null>(null);

  // 导入相关状态
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importData, setImportData] = useState('');
  const [importLoading, setImportLoading] = useState(false);
  const [selectedTunnelForImport, setSelectedTunnelForImport] = useState<number | null>(null);
  const [importResults, setImportResults] = useState<Array<{
    line: string;
    success: boolean;
    message: string;
    forwardName?: string;
  }>>([]);

  // 表单状态
  const [form, setForm] = useState<ForwardForm>({
    name: '',
    tunnelId: null,
    inPort: null,
    remoteAddr: '',
    interfaceName: '',
    strategy: 'fifo',
    routeMode: 'single',
    routeBalanceStrategy: 'round',
    routeWeights: {},
    routeTunnelIds: [],
    protocolMode: 'tcp_udp',
    batchMode: false,
    batchEndPort: null,
    targetStartPort: null
  });

  // 表单验证错误
  const [errors, setErrors] = useState<{[key: string]: string}>({});
  const [selectedTunnel, setSelectedTunnel] = useState<Tunnel | null>(null);

  useEffect(() => {
    loadData();
  }, []);

  // 切换显示模式并保存到localStorage
  const handleViewModeChange = () => {
    const newMode = viewMode === 'grouped' ? 'direct' : 'grouped';
    setViewMode(newMode);
    try {
      localStorage.setItem('forward-view-mode-v2', newMode);
    } catch (error) {
      console.warn('无法保存显示模式到localStorage:', error);
    }
  };

  // 加载所有数据
  const loadData = async (lod = true) => {
    setLoading(lod);
    try {
      const [forwardsRes, tunnelsRes] = await Promise.all([
        getForwardList(),
        userTunnel()
      ]);

      if (forwardsRes.code === 0) {
        const forwardsData = forwardsRes.data?.map((forward: any) => ({
          ...forward,
          serviceRunning: forward.status === 1
        })) || [];
        setForwards(forwardsData);
      } else {
        toast.error(forwardsRes.msg || '获取转发列表失败');
      }

      if (tunnelsRes.code === 0) {
        setTunnels(tunnelsRes.data || []);
      } else {
        console.warn('获取隧道列表失败:', tunnelsRes.msg);
      }
    } catch (error) {
      console.error('加载数据失败:', error);
      toast.error('加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  const getCreatedTime = (value: string | number | undefined): number => {
    const timestamp = Number(value);
    return Number.isFinite(timestamp) ? timestamp : 0;
  };

  const parseForwardRoutes = (forward: Forward): ForwardRoute[] => {
    if (forward.routeConfig) {
      try {
        const routes = JSON.parse(forward.routeConfig);
        if (Array.isArray(routes) && routes.length > 0) {
          return routes;
        }
      } catch {
        // Older records fall back to their primary tunnel.
      }
    }
    return [{
      tunnelId: forward.tunnelId,
      tunnelName: forward.tunnelName,
      priority: 0,
      status: forward.nodeOffline ? 'unhealthy' : 'unknown'
    }];
  };

  const parseTargetHealth = (forward: Forward): TargetHealth[] => {
    if (!forward.targetHealth) return [];
    try {
      const targets = JSON.parse(forward.targetHealth);
      return Array.isArray(targets) ? targets : [];
    } catch {
      return [];
    }
  };

  const getActiveRoute = (forward: Forward): ForwardRoute => {
    const routes = parseForwardRoutes(forward);
    return routes.find(route => route.tunnelId === (forward.activeTunnelId || forward.tunnelId)) || routes[0];
  };

  const compareForwardCreatedTime = (a: Forward, b: Forward): number => {
    const createdTimeCompare = getCreatedTime(b.createdTime) - getCreatedTime(a.createdTime);
    if (createdTimeCompare !== 0) return createdTimeCompare;
    return (b.id || 0) - (a.id || 0);
  };

  const getTunnelNodePath = (tunnel: Tunnel): number[] => {
    if (tunnel.nodePath) {
      const path = tunnel.nodePath
        .split(',')
        .map(item => Number(item.trim()))
        .filter(nodeId => Number.isFinite(nodeId) && nodeId > 0);
      if (path.length > 0) return path;
    }
    if (tunnel.type === 1) {
      return tunnel.inNodeId ? [tunnel.inNodeId] : [];
    }
    return [tunnel.inNodeId, tunnel.outNodeId]
      .filter((nodeId): nodeId is number => Number.isFinite(nodeId) && Number(nodeId) > 0);
  };

  const getTunnelLineMeta = (tunnel: Tunnel) => {
    if (tunnel.type === 1) {
      return {
        key: 'port-forward',
        title: '端口转发线路',
        badge: '端口转发',
        level: 1,
        color: 'warning' as const
      };
    }
    const level = Math.max(getTunnelNodePath(tunnel).length, 2);
    return {
      key: `tunnel-level-${level}`,
      title: `${level}级隧道线路`,
      badge: `${level}级隧道`,
      level,
      color: 'primary' as const
    };
  };

  const groupTunnelLines = (lineTunnels: Tunnel[]): TunnelLineGroup[] => {
    const groupMap = new Map<string, TunnelLineGroup>();
    lineTunnels.forEach(tunnel => {
      const meta = getTunnelLineMeta(tunnel);
      if (!groupMap.has(meta.key)) {
        groupMap.set(meta.key, {
          key: meta.key,
          title: meta.title,
          level: meta.level,
          tunnels: []
        });
      }
      groupMap.get(meta.key)!.tunnels.push(tunnel);
    });

    return Array.from(groupMap.values()).sort((left, right) => {
      if (left.key === 'port-forward') return -1;
      if (right.key === 'port-forward') return 1;
      return left.level - right.level;
    });
  };

  const renderTunnelLineSections = (lineTunnels: Tunnel[]) => (
    groupTunnelLines(lineTunnels).map((group, groupIndex, groups) => (
      <SelectSection
        key={group.key}
        title={`${group.title} (${group.tunnels.length})`}
        showDivider={groupIndex < groups.length - 1}
      >
        {group.tunnels.map(tunnel => {
          const meta = getTunnelLineMeta(tunnel);
          return (
            <SelectItem
              key={tunnel.id}
              textValue={`${tunnel.name} · ${meta.badge}`}
              className="py-2"
            >
              <div className="flex min-w-0 items-center justify-between gap-3">
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium text-foreground">{tunnel.name}</p>
                  <p className="truncate text-xs text-default-400">
                    ID {tunnel.id}{tunnel.ownerUserName ? ` · ${tunnel.ownerUserName}` : ''}
                  </p>
                </div>
                <Chip color={meta.color} variant="flat" size="sm" className="flex-shrink-0">
                  {meta.badge}
                </Chip>
              </div>
            </SelectItem>
          );
        })}
      </SelectSection>
    ))
  );

  // 按隧道归纳转发数据，管理转发时先看隧道，再看隧道下面的转发
  const groupForwardsByTunnel = (): TunnelForwardGroup[] => {
    const tunnelMap = new Map<number, TunnelForwardGroup>();

    forwardCardOrder.sortItems([...forwards].sort(compareForwardCreatedTime), forward => forward.id).forEach(forward => {
      const tunnelId = forward.tunnelId || 0;
      const tunnelName = forward.tunnelName || `隧道 #${tunnelId}`;
      const forwardCreatedTime = getCreatedTime(forward.createdTime);

      if (!tunnelMap.has(tunnelId)) {
        tunnelMap.set(tunnelId, {
          tunnelId,
          tunnelName,
          forwards: [],
          latestCreatedTime: forwardCreatedTime,
          runningForwardCount: 0,
          offlineForwardCount: 0,
          totalInFlow: 0,
          totalOutFlow: 0,
          userNames: []
        });
      }

      const tunnelGroup = tunnelMap.get(tunnelId)!;
      tunnelGroup.forwards.push(forward);
      tunnelGroup.latestCreatedTime = Math.max(tunnelGroup.latestCreatedTime, forwardCreatedTime);
      tunnelGroup.runningForwardCount += forward.serviceRunning ? 1 : 0;
      tunnelGroup.offlineForwardCount += isForwardLinkOffline(forward) ? 1 : 0;
      tunnelGroup.totalInFlow += forward.inFlow || 0;
      tunnelGroup.totalOutFlow += forward.outFlow || 0;

      const userName = forward.userName || '未知用户';
      if (!tunnelGroup.userNames.includes(userName)) {
        tunnelGroup.userNames.push(userName);
      }
    });

    return Array.from(tunnelMap.values()).sort((a, b) => {
      if (a.latestCreatedTime !== b.latestCreatedTime) {
        return b.latestCreatedTime - a.latestCreatedTime;
      }
      const nameCompare = a.tunnelName.localeCompare(b.tunnelName, 'zh-Hans-CN', { numeric: true });
      if (nameCompare !== 0) return nameCompare;
      return a.tunnelId - b.tunnelId;
    });
  };

  // 表单验证
  const validateForm = (): boolean => {
    const newErrors: {[key: string]: string} = {};

    if (!form.name.trim()) {
      newErrors.name = '请输入转发名称';
    } else if (form.name.length < 2 || form.name.length > 50) {
      newErrors.name = '转发名称长度应在2-50个字符之间';
    }

    if (!form.tunnelId) {
      newErrors.tunnelId = '请选择关联隧道';
    }

    if (form.routeMode !== 'single' && form.routeTunnelIds.length === 0) {
      newErrors.routeTunnelIds = '多线路模式至少需要一条候选线路';
    }

    if (!form.remoteAddr.trim()) {
      newErrors.remoteAddr = '请输入远程地址';
    } else {
      // 验证地址格式
      const addresses = form.remoteAddr.split('\n').map(addr => addr.trim()).filter(addr => addr);
      const ipv4Pattern = /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?):\d+$/;
      const ipv6FullPattern = /^\[((([0-9a-fA-F]{1,4}:){7}([0-9a-fA-F]{1,4}|:))|(([0-9a-fA-F]{1,4}:){6}(:[0-9a-fA-F]{1,4}|((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9a-fA-F]{1,4}:){5}(((:[0-9a-fA-F]{1,4}){1,2})|:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9a-fA-F]{1,4}:){4}(((:[0-9a-fA-F]{1,4}){1,3})|((:[0-9a-fA-F]{1,4})?:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-fA-F]{1,4}:){3}(((:[0-9a-fA-F]{1,4}){1,4})|((:[0-9a-fA-F]{1,4}){0,2}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-fA-F]{1,4}:){2}(((:[0-9a-fA-F]{1,4}){1,5})|((:[0-9a-fA-F]{1,4}){0,3}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-fA-F]{1,4}:){1}(((:[0-9a-fA-F]{1,4}){1,6})|((:[0-9a-fA-F]{1,4}){0,4}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(:(((:[0-9a-fA-F]{1,4}){1,7})|((:[0-9a-fA-F]{1,4}){0,5}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:)))\]:\d+$/;
      const domainPattern = /^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)*:\d+$/;

      for (let i = 0; i < addresses.length; i++) {
        const addr = addresses[i];
        if (!ipv4Pattern.test(addr) && !ipv6FullPattern.test(addr) && !domainPattern.test(addr)) {
          newErrors.remoteAddr = `第${i + 1}行地址格式错误`;
          break;
        }
      }
    }

    if (form.inPort !== null && (form.inPort < 1 || form.inPort > 65535)) {
      newErrors.inPort = '端口号必须在1-65535之间';
    }

    if (selectedTunnel && selectedTunnel.inNodePortSta && selectedTunnel.inNodePortEnd && form.inPort) {
      if (form.inPort < selectedTunnel.inNodePortSta || form.inPort > selectedTunnel.inNodePortEnd) {
        newErrors.inPort = `端口号必须在${selectedTunnel.inNodePortSta}-${selectedTunnel.inNodePortEnd}范围内`;
      }
    }

    if (!isEdit && form.batchMode) {
      if (!form.inPort) {
        newErrors.inPort = '批量创建需要填写入口起始端口';
      }
      if (!form.batchEndPort) {
        newErrors.batchEndPort = '请输入入口结束端口';
      } else if (form.inPort && form.batchEndPort < form.inPort) {
        newErrors.batchEndPort = '结束端口不能小于起始端口';
      } else if (form.inPort && form.batchEndPort - form.inPort + 1 > 200) {
        newErrors.batchEndPort = '单次最多创建200条转发';
      }
      if (!form.targetStartPort) {
        newErrors.targetStartPort = '请输入目标起始端口';
      }
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 新增转发
  const handleAdd = () => {
    setIsEdit(false);
    setForm({
      name: '',
      tunnelId: null,
      inPort: null,
      remoteAddr: '',
      interfaceName: '',
      strategy: 'fifo',
      routeMode: 'single',
      routeBalanceStrategy: 'round',
      routeWeights: {},
      routeTunnelIds: [],
      protocolMode: 'tcp_udp',
      batchMode: false,
      batchEndPort: null,
      targetStartPort: null
    });
    setSelectedTunnel(null);
    setErrors({});
    setModalOpen(true);
  };

  // 编辑转发
  const handleEdit = (forward: Forward) => {
    const routes = parseForwardRoutes(forward);
    setIsEdit(true);
    setForm({
      id: forward.id,
      userId: forward.userId,
      name: forward.name,
      tunnelId: forward.tunnelId,
      inPort: forward.inPort,
      remoteAddr: forward.remoteAddr.split(',').join('\n'),
      interfaceName: forward.interfaceName || '',
      strategy: forward.strategy || 'fifo',
      routeMode: forward.routeMode || (routes.length > 1 ? 'failover' : 'single'),
      routeBalanceStrategy: forward.routeBalanceStrategy || 'round',
      routeWeights: Object.fromEntries(routes.map(route => [route.tunnelId, route.weight || 100])),
      routeTunnelIds: routes
        .map(route => route.tunnelId)
        .filter(tunnelId => tunnelId !== forward.tunnelId),
      protocolMode: forward.protocolMode || 'tcp_udp',
      batchMode: false,
      batchEndPort: null,
      targetStartPort: null
    });
    const tunnel = tunnels.find(t => t.id === forward.tunnelId);
    setSelectedTunnel(tunnel || null);
    setErrors({});
    setModalOpen(true);
  };

  // 显示删除确认
  const handleDelete = (forward: Forward) => {
    setForwardToDelete(forward);
    setDeleteModalOpen(true);
  };

  // 确认删除转发
  const confirmDelete = async () => {
    if (!forwardToDelete) return;

    setDeleteLoading(true);
    try {
      const res = await deleteForward(forwardToDelete.id);
      if (res.code === 0) {
        toast.success('删除成功');
        setDeleteModalOpen(false);
        loadData();
      } else {
        // 删除失败，询问是否强制删除
        const confirmed = window.confirm(`常规删除失败：${res.msg || '删除失败'}\n\n是否需要强制删除？\n\n⚠️ 注意：强制删除不会去验证节点端是否已经删除对应的转发服务。`);
        if (confirmed) {
          const forceRes = await forceDeleteForward(forwardToDelete.id);
          if (forceRes.code === 0) {
            toast.success('强制删除成功');
            setDeleteModalOpen(false);
            loadData();
          } else {
            toast.error(forceRes.msg || '强制删除失败');
          }
        }
      }
    } catch (error) {
      console.error('删除失败:', error);
      toast.error('删除失败');
    } finally {
      setDeleteLoading(false);
    }
  };

  // 处理隧道选择变化
  const handleTunnelChange = (tunnelId: string) => {
    const tunnel = tunnels.find(t => t.id === parseInt(tunnelId));
    setSelectedTunnel(tunnel || null);
    setForm(prev => ({
      ...prev,
      tunnelId: parseInt(tunnelId),
      routeTunnelIds: prev.routeTunnelIds.filter(candidateId => {
        const candidate = tunnels.find(item => item.id === candidateId);
        return candidate?.inNodeId === tunnel?.inNodeId && candidateId !== parseInt(tunnelId);
      })
    }));
  };

  // 提交表单
  const handleSubmit = async () => {
    if (!validateForm()) return;

    setSubmitLoading(true);
    try {
      const processedRemoteAddr = form.remoteAddr
        .split('\n')
        .map(addr => addr.trim())
        .filter(addr => addr)
        .join(',');

      const addressCount = processedRemoteAddr.split(',').length;
      const routeTunnelIds = form.routeMode === 'single'
        ? [form.tunnelId]
        : [form.tunnelId, ...form.routeTunnelIds];

      let res;
      if (isEdit) {
        // 更新时确保包含必要字段
        const updateData = {
          id: form.id,
          userId: form.userId,
          name: form.name,
          tunnelId: form.tunnelId,
          inPort: form.inPort,
          remoteAddr: processedRemoteAddr,
          interfaceName: form.interfaceName,
          strategy: addressCount > 1 ? form.strategy : 'fifo',
          routeMode: form.routeMode,
          routeBalanceStrategy: form.routeBalanceStrategy,
          routeWeights: form.routeWeights,
          routeTunnelIds,
          protocolMode: form.protocolMode
        };
        res = await updateForward(updateData);
      } else {
        // 创建时不需要id和userId（后端会自动设置）
        const createData = {
          name: form.name,
          tunnelId: form.tunnelId,
          inPort: form.inPort,
          remoteAddr: processedRemoteAddr,
          interfaceName: form.interfaceName,
          strategy: addressCount > 1 ? form.strategy : 'fifo',
          routeMode: form.routeMode,
          routeBalanceStrategy: form.routeBalanceStrategy,
          routeWeights: form.routeWeights,
          routeTunnelIds,
          protocolMode: form.protocolMode,
          batchEndPort: form.batchMode ? form.batchEndPort : null,
          targetStartPort: form.batchMode ? form.targetStartPort : null
        };
        res = await createForward(createData);
      }

      if (res.code === 0) {
        const successCount = res.data?.successCount;
        toast.success(
          isEdit
            ? '修改成功'
            : successCount
              ? `批量创建成功，共 ${successCount} 条`
              : '创建成功'
        );
        setModalOpen(false);
        loadData();
      } else {
        toast.error(res.msg || '操作失败');
      }
    } catch (error) {
      console.error('提交失败:', error);
      toast.error('操作失败');
    } finally {
      setSubmitLoading(false);
    }
  };

  // 处理服务开关
  const handleServiceToggle = async (forward: Forward) => {
    if (forward.status !== 1 && forward.status !== 0) {
      toast.error('转发状态异常，无法操作');
      return;
    }

    const targetState = !forward.serviceRunning;

    try {
      // 乐观更新UI
      setForwards(prev => prev.map(f =>
        f.id === forward.id
          ? { ...f, serviceRunning: targetState }
          : f
      ));

      let res;
      if (targetState) {
        res = await resumeForwardService(forward.id);
      } else {
        res = await pauseForwardService(forward.id);
      }

      if (res.code === 0) {
        toast.success(targetState ? '服务已启动' : '服务已暂停');
        // 更新转发状态
        setForwards(prev => prev.map(f =>
          f.id === forward.id
            ? { ...f, status: targetState ? 1 : 0 }
            : f
        ));
      } else {
        // 操作失败，恢复UI状态
        setForwards(prev => prev.map(f =>
          f.id === forward.id
            ? { ...f, serviceRunning: !targetState }
            : f
        ));
        toast.error(res.msg || '操作失败');
      }
    } catch (error) {
      // 操作失败，恢复UI状态
      setForwards(prev => prev.map(f =>
        f.id === forward.id
          ? { ...f, serviceRunning: !targetState }
          : f
      ));
      console.error('服务开关操作失败:', error);
      toast.error('网络错误，操作失败');
    }
  };

  // 诊断转发
  const handleDiagnose = async (forward: Forward) => {
    setCurrentDiagnosisForward(forward);
    setDiagnosisModalOpen(true);
    setDiagnosisLoading(true);
    setDiagnosisResult(null);

    try {
      const response = await diagnoseForward(forward.id);
      if (response.code === 0) {
        setDiagnosisResult(response.data);
      } else {
        toast.error(response.msg || '诊断失败');
        setDiagnosisResult({
          forwardName: forward.name,
          timestamp: Date.now(),
          results: [{
            success: false,
            description: '诊断失败',
            nodeName: '-',
            nodeId: '-',
            targetIp: forward.remoteAddr.split(',')[0] || '-',
            message: response.msg || '诊断过程中发生错误'
          }]
        });
      }
    } catch (error) {
      console.error('诊断失败:', error);
      toast.error('网络错误，请重试');
      setDiagnosisResult({
        forwardName: forward.name,
        timestamp: Date.now(),
        results: [{
          success: false,
          description: '网络错误',
          nodeName: '-',
          nodeId: '-',
          targetIp: forward.remoteAddr.split(',')[0] || '-',
          message: '无法连接到服务器'
        }]
      });
    } finally {
      setDiagnosisLoading(false);
    }
  };

  const handleFailoverDetails = async (forward: Forward) => {
    setCurrentFailoverForward(forward);
    setFailoverModalOpen(true);
    setFailoverLoading(true);
    setRouteSwitchEvents([]);
    try {
      const response = await getForwardRouteEvents(forward.id);
      if (response.code === 0) {
        setRouteSwitchEvents(Array.isArray(response.data) ? response.data : []);
      } else {
        toast.error(response.msg || '读取容灾记录失败');
      }
    } catch (error) {
      console.error('读取容灾记录失败:', error);
      toast.error('网络错误，无法读取容灾记录');
    } finally {
      setFailoverLoading(false);
    }
  };

  const formatRouteEventTime = (value?: number) => {
    if (!value) return '尚未发生自动切换';
    return new Date(value).toLocaleString('zh-CN', { hour12: false });
  };

  const getRouteStatusText = (route: ForwardRoute) => {
    if (route.status === 'healthy') return '健康';
    if (route.status === 'unhealthy' && route.message?.startsWith('恢复确认')) {
      return route.message;
    }
    if (route.status === 'unhealthy') return '异常';
    return '待探测';
  };

  // 获取连接质量
  const getQualityDisplay = (averageTime?: number, packetLoss?: number) => {
    if (averageTime === undefined || packetLoss === undefined) return null;

    if (averageTime < 30 && packetLoss === 0) return { text: '🚀 优秀', color: 'success' };
    if (averageTime < 50 && packetLoss === 0) return { text: '✨ 很好', color: 'success' };
    if (averageTime < 100 && packetLoss < 1) return { text: '👍 良好', color: 'primary' };
    if (averageTime < 150 && packetLoss < 2) return { text: '😐 一般', color: 'warning' };
    if (averageTime < 200 && packetLoss < 5) return { text: '😟 较差', color: 'warning' };
    return { text: '😵 很差', color: 'danger' };
  };

  const getDiagnosisLatencySummary = (diagnosis: DiagnosisResult) => {
    const results = diagnosis.results || [];
    const successfulResults = results.filter(result =>
      result.success && typeof result.averageTime === 'number' && Number.isFinite(result.averageTime)
    );
    const totalLatency = successfulResults.reduce((sum, result) => sum + (result.averageTime || 0), 0);
    const failedCount = results.length - successfulResults.length;
    const averagePacketLoss = successfulResults.length > 0
      ? successfulResults.reduce((sum, result) => sum + (result.packetLoss || 0), 0) / successfulResults.length
      : undefined;
    const quality = successfulResults.length > 0
      ? getQualityDisplay(totalLatency, averagePacketLoss)
      : null;

    return {
      totalLatency,
      successfulCount: successfulResults.length,
      totalCount: results.length,
      failedCount,
      quality
    };
  };

  // 格式化流量
  const formatFlow = (value: number): string => {
    if (value === 0) return '0 B';
    if (value < 1024) return value + ' B';
    if (value < 1024 * 1024) return (value / 1024).toFixed(2) + ' KB';
    if (value < 1024 * 1024 * 1024) return (value / (1024 * 1024)).toFixed(2) + ' MB';
    return (value / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  };

  // 格式化入口地址
  const formatInAddress = (ipString: string, port: number): string => {
    if (!ipString || !port) return '';

    const ips = ipString.split(',').map(ip => ip.trim()).filter(ip => ip);
    if (ips.length === 0) return '';

    if (ips.length === 1) {
      const ip = ips[0];
      if (ip.includes(':') && !ip.startsWith('[')) {
        return `[${ip}]:${port}`;
      } else {
        return `${ip}:${port}`;
      }
    }

    const firstIp = ips[0];
    let formattedFirstIp;
    if (firstIp.includes(':') && !firstIp.startsWith('[')) {
      formattedFirstIp = `[${firstIp}]`;
    } else {
      formattedFirstIp = firstIp;
    }

    return `${formattedFirstIp}:${port} (+${ips.length - 1})`;
  };

  // 格式化远程地址
  const formatRemoteAddress = (addressString: string): string => {
    if (!addressString) return '';

    const addresses = addressString.split(',').map(addr => addr.trim()).filter(addr => addr);
    if (addresses.length === 0) return '';
    if (addresses.length === 1) return addresses[0];

    return `${addresses[0]} (+${addresses.length - 1})`;
  };

  // 检查是否有多个地址
  const hasMultipleAddresses = (addressString: string): boolean => {
    if (!addressString) return false;
    const addresses = addressString.split(',').map(addr => addr.trim()).filter(addr => addr);
    return addresses.length > 1;
  };

  // 显示地址列表弹窗
  const showAddressModal = (addressString: string, port: number | null, title: string) => {
    if (!addressString) return;

    let addresses: string[];
    if (port !== null) {
      // 入口地址处理
      const ips = addressString.split(',').map(ip => ip.trim()).filter(ip => ip);
      if (ips.length <= 1) {
        copyToClipboard(formatInAddress(addressString, port), title);
        return;
      }
      addresses = ips.map(ip => {
        if (ip.includes(':') && !ip.startsWith('[')) {
          return `[${ip}]:${port}`;
        } else {
          return `${ip}:${port}`;
        }
      });
    } else {
      // 远程地址处理
      addresses = addressString.split(',').map(addr => addr.trim()).filter(addr => addr);
      if (addresses.length <= 1) {
        copyToClipboard(addressString, title);
        return;
      }
    }

    setAddressList(addresses.map((address, index) => ({
      id: index,
      address,
      copying: false
    })));
    setAddressModalTitle(`${title} (${addresses.length}个)`);
    setAddressModalOpen(true);
  };

  // 复制到剪贴板
  const copyToClipboard = async (text: string, label: string = '内容') => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(`已复制${label}`);
    } catch (error) {
      toast.error('复制失败');
    }
  };

  // 复制地址
  const copyAddress = async (addressItem: AddressItem) => {
    try {
      setAddressList(prev => prev.map(item =>
        item.id === addressItem.id ? { ...item, copying: true } : item
      ));
      await copyToClipboard(addressItem.address, '地址');
    } catch (error) {
      toast.error('复制失败');
    } finally {
      setAddressList(prev => prev.map(item =>
        item.id === addressItem.id ? { ...item, copying: false } : item
      ));
    }
  };

  // 复制所有地址
  const copyAllAddresses = async () => {
    if (addressList.length === 0) return;
    const allAddresses = addressList.map(item => item.address).join('\n');
    await copyToClipboard(allAddresses, '所有地址');
  };

    // 导出转发数据
  const handleExport = () => {
    setSelectedTunnelForExport(null);
    setExportData('');
    setExportModalOpen(true);
  };

  // 执行导出
  const executeExport = () => {
    if (!selectedTunnelForExport) {
      toast.error('请选择要导出的隧道');
      return;
    }

    setExportLoading(true);

    try {
      // 根据当前显示模式获取要导出的转发列表
      let forwardsToExport: Forward[] = [];

      if (viewMode === 'grouped') {
        // 分组模式下，获取指定隧道的转发
        const tunnelGroups = groupForwardsByTunnel();
        forwardsToExport = tunnelGroups
          .filter(tunnelGroup => tunnelGroup.tunnelId === selectedTunnelForExport)
          .flatMap(tunnelGroup => tunnelGroup.forwards);
      } else {
        // 直接显示模式下，过滤指定隧道的转发
        forwardsToExport = getSortedForwards().filter(forward => forward.tunnelId === selectedTunnelForExport);
      }

      if (forwardsToExport.length === 0) {
        toast.error('所选隧道没有转发数据');
        setExportLoading(false);
        return;
      }

      // 格式化导出数据：remoteAddr|name|inPort
      const exportLines = forwardsToExport.map(forward => {
        return `${forward.remoteAddr}|${forward.name}|${forward.inPort}`;
      });

      const exportText = exportLines.join('\n');
      setExportData(exportText);
    } catch (error) {
      console.error('导出失败:', error);
      toast.error('导出失败');
    } finally {
      setExportLoading(false);
    }
  };

  // 复制导出数据
  const copyExportData = async () => {
    await copyToClipboard(exportData, '转发数据');
  };

  // 导入转发数据
  const handleImport = () => {
    setImportData('');
    setImportResults([]);
    setSelectedTunnelForImport(null);
    setImportModalOpen(true);
  };

  // 执行导入
  const executeImport = async () => {
    if (!importData.trim()) {
      toast.error('请输入要导入的数据');
      return;
    }

    if (!selectedTunnelForImport) {
      toast.error('请选择要导入的隧道');
      return;
    }

    setImportLoading(true);
    setImportResults([]); // 清空之前的结果

    try {
      const lines = importData.trim().split('\n').filter(line => line.trim());

      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        const parts = line.split('|');

        if (parts.length < 2) {
          setImportResults(prev => [{
            line,
            success: false,
            message: '格式错误：需要至少包含目标地址和转发名称'
          }, ...prev]);
          continue;
        }

        const [remoteAddr, name, inPort] = parts;

        if (!remoteAddr.trim() || !name.trim()) {
          setImportResults(prev => [{
            line,
            success: false,
            message: '目标地址和转发名称不能为空'
          }, ...prev]);
          continue;
        }

        // 验证远程地址格式 - 支持单个地址或多个地址用逗号分隔
        const addresses = remoteAddr.trim().split(',');
        const addressPattern = /^[^:]+:\d+$/;
        const isValidFormat = addresses.every(addr => addressPattern.test(addr.trim()));

        if (!isValidFormat) {
          setImportResults(prev => [{
            line,
            success: false,
            message: '目标地址格式错误，应为 地址:端口 格式，多个地址用逗号分隔'
          }, ...prev]);
          continue;
        }

        try {
          // 处理入口端口
          let portNumber: number | null = null;
          if (inPort && inPort.trim()) {
            const port = parseInt(inPort.trim());
            if (isNaN(port) || port < 1 || port > 65535) {
              setImportResults(prev => [{
                line,
                success: false,
                message: '入口端口格式错误，应为1-65535之间的数字'
              }, ...prev]);
              continue;
            }
            portNumber = port;
          }

          // 调用创建转发接口
          const response = await createForward({
            name: name.trim(),
            tunnelId: selectedTunnelForImport, // 使用用户选择的隧道
            inPort: portNumber, // 使用指定端口或自动分配
            remoteAddr: remoteAddr.trim(),
            strategy: 'fifo'
          });

          if (response.code === 0) {
            setImportResults(prev => [{
              line,
              success: true,
              message: '创建成功',
              forwardName: name.trim()
            }, ...prev]);
          } else {
            setImportResults(prev => [{
              line,
              success: false,
              message: response.msg || '创建失败'
            }, ...prev]);
          }
        } catch (error) {
          setImportResults(prev => [{
            line,
            success: false,
            message: '网络错误，创建失败'
          }, ...prev]);
        }
      }


      toast.success(`导入执行完成`);

      // 导入完成后刷新转发列表
      await loadData(false);
    } catch (error) {
      console.error('导入失败:', error);
      toast.error('导入过程中发生错误');
    } finally {
      setImportLoading(false);
    }
  };

  // 获取状态显示
  const getStatusDisplay = (status: number) => {
    switch (status) {
      case 1:
        return { color: 'success', text: '正常' };
      case 0:
        return { color: 'warning', text: '暂停' };
      case -1:
        return { color: 'danger', text: '异常' };
      default:
        return { color: 'default', text: '未知' };
    }
  };

  // 获取策略显示
  const getStrategyDisplay = (strategy: string) => {
    switch (strategy) {
      case 'fifo':
        return { color: 'primary', text: '主备' };
      case 'round':
        return { color: 'success', text: '轮询' };
      case 'rand':
        return { color: 'warning', text: '随机' };
      case 'hash':
        return { color: 'secondary', text: 'IP 哈希' };
      default:
        return { color: 'default', text: '未知' };
    }
  };

  const isForwardInNodeOffline = (forward: Forward): boolean => forward.inNodeStatus !== 1;

  const isForwardOutNodeOffline = (forward: Forward): boolean => {
    const outNodeStatus = forward.type === 1 ? forward.inNodeStatus : forward.outNodeStatus;
    return outNodeStatus !== 1;
  };

  const isForwardLinkOffline = (forward: Forward): boolean => (
    forward.nodeOffline === true
    || isForwardInNodeOffline(forward)
    || isForwardOutNodeOffline(forward)
    || getActiveRoute(forward).status === 'unhealthy'
  );

  const getProtocolDisplay = (protocolMode?: string): string => {
    if (protocolMode === 'tcp') return 'TCP';
    if (protocolMode === 'udp') return 'UDP';
    return 'TCP + UDP';
  };

  const getRouteModeDisplay = (routeMode?: string): string => {
    if (routeMode === 'failover') return '主备切换';
    if (routeMode === 'latency') return '低延迟选路';
    if (routeMode === 'balance') return '多线路负载均衡';
    return '单线路';
  };

  const getNodeStatusChip = (offline: boolean) => ({
    color: offline ? 'danger' : 'success',
    text: offline ? '离线' : '在线'
  });

  const getLinkStatusText = (inNodeOffline: boolean, outNodeOffline: boolean, nodeOffline = false): string => {
    if (nodeOffline && !inNodeOffline && !outNodeOffline) {
      return '路径中间节点离线';
    }
    if (!inNodeOffline && !outNodeOffline) {
      return '链路正常';
    }
    const inStatus = inNodeOffline ? '入口离线' : '入口正常';
    const outStatus = outNodeOffline ? '出口离线' : '出口正常';
    return `${inStatus} -> ${outStatus}`;
  };

  const getForwardPathText = (forward: Forward): string => {
    if (!forward.nodePath) return '';
    const path = forward.nodePath
      .split(',')
      .map(item => item.trim())
      .filter(Boolean);
    return path.length > 2 ? `${path.length} 跳路径` : '';
  };

  const getForwardNodeBlockClassName = (offline: boolean, clickable = false): string => {
    const baseClass = clickable ? 'cursor-pointer' : '';
    return offline
      ? `${baseClass} px-2 py-1.5 bg-danger-100/90 rounded border border-danger-300 transition-colors duration-200`
      : `${baseClass} px-2 py-1.5 bg-default-50 dark:bg-default-100/50 rounded border border-default-200 dark:border-default-300 transition-colors duration-200`;
  };

  // 获取地址数量
  const getAddressCount = (addressString: string): number => {
    if (!addressString) return 0;
    const addresses = addressString.split('\n').map(addr => addr.trim()).filter(addr => addr);
    return addresses.length;
  };

  // 根据排序顺序获取转发列表
  const getSortedForwards = (): Forward[] => {
    // 确保 forwards 数组存在且有效
    if (!forwards || forwards.length === 0) {
      return [];
    }

    // 在平铺模式下，只显示当前用户的转发
    let filteredForwards = forwards;
    if (viewMode === 'direct') {
      const currentUserId = JwtUtil.getUserIdFromToken();
      if (currentUserId !== null) {
        filteredForwards = forwards.filter(forward => forward.userId === currentUserId);
      }
    }

    // 确保过滤后的转发列表有效
    if (!filteredForwards || filteredForwards.length === 0) {
      return [];
    }

    // 首次使用新版布局时沿用旧 inx 顺序，之后由用户级布局覆盖。
    const sortedForwards = [...filteredForwards].sort((a, b) => {
      const aInx = a.inx ?? 0;
      const bInx = b.inx ?? 0;
      if (aInx !== bInx) return aInx - bInx;
      return compareForwardCreatedTime(a, b);
    });
    return forwardCardOrder.sortItems(sortedForwards, forward => forward.id);
  };

  // 渲染转发卡片
  const renderForwardCard = (forward: Forward, dragHandle: ReactNode) => {
    const statusDisplay = getStatusDisplay(forward.status);
    const strategyDisplay = getStrategyDisplay(forward.strategy);
    const inNodeOffline = isForwardInNodeOffline(forward);
    const outNodeOffline = isForwardOutNodeOffline(forward);
    const nodeOffline = isForwardLinkOffline(forward);
    const primaryStatusDisplay = nodeOffline
      ? { color: 'danger', text: '链路异常' }
      : statusDisplay;
    const inNodeChip = getNodeStatusChip(inNodeOffline);
    const outNodeChip = getNodeStatusChip(outNodeOffline);
    const cardClassName = nodeOffline
      ? "offline-card group shadow-sm border border-danger-300 overflow-hidden hover:shadow-md transition-shadow duration-200"
      : "group shadow-sm border border-divider overflow-hidden hover:shadow-md transition-shadow duration-200";
    const inAddressClickable = hasMultipleAddresses(forward.inIp);
    const targetAddressClickable = hasMultipleAddresses(forward.remoteAddr);
    const pathText = getForwardPathText(forward);
    const routes = parseForwardRoutes(forward);
    const activeRoute = getActiveRoute(forward);
    const healthyRouteCount = routes.filter(route => route.status === 'healthy').length;
    const activeIsPrimary = activeRoute.tunnelId === forward.tunnelId;
    const targetHealth = parseTargetHealth(forward);
    const healthyTargetCount = targetHealth.filter(target => target.status === 'healthy').length;

    return (
      <Card key={forward.id} className={`${cardClassName} h-full`}>
        {nodeOffline && <div className="offline-accent h-0.5 bg-danger" />}
        <CardHeader className="pb-2">
          <div className="flex justify-between items-start w-full">
            <div className="flex-1 min-w-0">
              <h3 className="font-semibold text-foreground truncate text-sm">{forward.name}</h3>
              <p className="text-xs text-default-500 truncate">
                {activeRoute.tunnelName || forward.tunnelName}{pathText ? ` · ${pathText}` : ''}
              </p>
              <p className={nodeOffline ? "text-xs text-danger-600 dark:text-danger-300 truncate mt-0.5" : "text-xs text-default-500 truncate mt-0.5"}>
                {getLinkStatusText(inNodeOffline, outNodeOffline, nodeOffline)}
              </p>
            </div>
            <div className="flex items-center gap-1.5 ml-2">
              {dragHandle}
              <Switch
                size="sm"
                isSelected={forward.serviceRunning}
                onValueChange={() => handleServiceToggle(forward)}
                isDisabled={forward.status !== 1 && forward.status !== 0}
              />
              <Chip
                color={primaryStatusDisplay.color as any}
                variant="flat"
                size="sm"
                className={nodeOffline ? "text-xs offline-status-chip" : "text-xs"}
              >
                {primaryStatusDisplay.text}
              </Chip>
            </div>
          </div>
        </CardHeader>

        <CardBody className="pt-0 pb-3">
          <div className="space-y-2">
            {/* 地址信息 */}
            <div className="space-y-1">
              <div
                className={`${getForwardNodeBlockClassName(inNodeOffline, inAddressClickable)} ${
                  inAddressClickable && !inNodeOffline ? 'hover:bg-default-100 dark:hover:bg-default-200/50' : ''
                }`}
                onClick={() => showAddressModal(forward.inIp, forward.inPort, '入口端口')}
                title={formatInAddress(forward.inIp, forward.inPort)}
              >
                <div className="flex items-center justify-between mb-1">
                  <span className={inNodeOffline ? "text-xs font-medium text-danger-700 dark:text-danger-300" : "text-xs font-medium text-default-600"}>入口节点</span>
                  <Chip color={inNodeChip.color as any} variant="flat" size="sm" className={inNodeOffline ? "text-xs offline-status-chip" : "text-xs"}>
                    {inNodeChip.text}
                  </Chip>
                </div>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 min-w-0 flex-1">
                    <span className={inNodeOffline ? "text-xs font-medium text-danger-700 dark:text-danger-300 flex-shrink-0" : "text-xs font-medium text-default-600 flex-shrink-0"}>入口:</span>
                    <code className={inNodeOffline ? "text-xs font-mono text-danger-800 dark:text-danger-200 truncate min-w-0" : "text-xs font-mono text-foreground truncate min-w-0"}>
                      {formatInAddress(forward.inIp, forward.inPort)}
                    </code>
                  </div>
                  {hasMultipleAddresses(forward.inIp) && (
                    <svg className="w-3 h-3 text-default-400 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                    </svg>
                  )}
                </div>
              </div>

              <div
                className={`${getForwardNodeBlockClassName(outNodeOffline, targetAddressClickable)} ${
                  targetAddressClickable && !outNodeOffline ? 'hover:bg-default-100 dark:hover:bg-default-200/50' : ''
                }`}
                onClick={() => showAddressModal(forward.remoteAddr, null, '目标地址')}
                title={formatRemoteAddress(forward.remoteAddr)}
              >
                <div className="flex items-center justify-between mb-1">
                  <span className={outNodeOffline ? "text-xs font-medium text-danger-700 dark:text-danger-300" : "text-xs font-medium text-default-600"}>
                    {forward.type === 1 ? '出口节点（同入口）' : '出口节点'}
                  </span>
                  <Chip color={outNodeChip.color as any} variant="flat" size="sm" className={outNodeOffline ? "text-xs offline-status-chip" : "text-xs"}>
                    {outNodeChip.text}
                  </Chip>
                </div>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 min-w-0 flex-1">
                    <span className={outNodeOffline ? "text-xs font-medium text-danger-700 dark:text-danger-300 flex-shrink-0" : "text-xs font-medium text-default-600 flex-shrink-0"}>目标:</span>
                    <code className={outNodeOffline ? "text-xs font-mono text-danger-800 dark:text-danger-200 truncate min-w-0" : "text-xs font-mono text-foreground truncate min-w-0"}>
                      {formatRemoteAddress(forward.remoteAddr)}
                    </code>
                  </div>
                  {hasMultipleAddresses(forward.remoteAddr) && (
                    <svg className="w-3 h-3 text-default-400 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                    </svg>
                  )}
                </div>
              </div>
            </div>

            <div className={nodeOffline ? "grid grid-cols-2 gap-x-3 gap-y-1 pt-2 border-t border-danger-200 dark:border-danger-800 min-h-[52px]" : "grid grid-cols-2 gap-x-3 gap-y-1 pt-2 border-t border-divider min-h-[52px]"}>
              <div className="min-w-0">
                <div className="text-[11px] text-default-400">线路策略</div>
                <div className="text-xs font-medium truncate">
                  {getRouteModeDisplay(forward.routeMode)} · {routes.length} 条
                </div>
              </div>
              <div className="min-w-0">
                <div className="text-[11px] text-default-400">{forward.routeMode === 'balance' ? '负载池' : '当前线路'}</div>
                <div className={activeRoute.status === 'unhealthy' ? "text-xs font-medium text-danger truncate" : "text-xs font-medium truncate"}>
                  {forward.routeMode === 'balance'
                    ? `${healthyRouteCount}/${routes.length} 条可用`
                    : <>{getRouteStatusText(activeRoute)}{typeof activeRoute.latency === 'number' ? ` · ${activeRoute.latency.toFixed(0)} ms` : ''}</>}
                </div>
              </div>
              <div className="min-w-0">
                <div className="text-[11px] text-default-400">入口协议</div>
                <div className="text-xs font-medium truncate">{getProtocolDisplay(forward.protocolMode)}</div>
              </div>
              <div className="min-w-0">
                <div className="text-[11px] text-default-400">健康目标</div>
                <div className="text-xs font-medium truncate">
                  {targetHealth.length > 0 ? `${healthyTargetCount}/${targetHealth.length}` : '待探测'}
                </div>
              </div>
            </div>

            <div className="flex items-center gap-2 border-t border-divider pt-2 min-h-[42px]">
              <div className={`flex h-7 w-7 flex-shrink-0 items-center justify-center rounded ${routes.length > 1 && healthyRouteCount > 0 ? 'bg-success-100 text-success-700 dark:bg-success-900/30 dark:text-success-300' : 'bg-default-100 text-default-500'}`}>
                {routes.length > 1 && healthyRouteCount > 0
                  ? <ShieldCheck size={15} aria-hidden="true" />
                  : <ShieldAlert size={15} aria-hidden="true" />}
              </div>
              <div className="min-w-0 flex-1">
                <div className="text-[11px] text-default-400">{forward.routeMode === 'balance' ? '调度状态' : '容灾状态'}</div>
                <div className="truncate text-xs font-medium">
                  {forward.routeMode === 'balance'
                    ? `${forward.routeBalanceStrategy === 'hash' ? '来源 IP 固定' : forward.routeBalanceStrategy === 'weighted' ? '按权重分配' : forward.routeBalanceStrategy === 'rand' ? '随机分配' : '依次轮询'} · 仅调度新连接`
                    : routes.length > 1
                    ? `${activeIsPrimary ? '主线路承载' : '备用线路承载'} · ${healthyRouteCount}/${routes.length} 可用`
                    : '单线路 · 未配置备用线路'}
                </div>
              </div>
              <Button
                isIconOnly
                size="sm"
                variant="light"
                aria-label="查看容灾详情"
                title="查看容灾详情"
                onPress={() => handleFailoverDetails(forward)}
                className="h-7 min-h-7 w-7 min-w-7"
              >
                <History size={15} aria-hidden="true" />
              </Button>
            </div>

            {/* 统计信息 */}
            <div className={nodeOffline ? "grid grid-cols-3 gap-1.5 pt-2 border-t border-danger-200 dark:border-danger-800" : "grid grid-cols-3 gap-1.5 pt-2 border-t border-divider"}>
              <Chip color={strategyDisplay.color as any} variant="flat" size="sm" className="text-xs">
                {strategyDisplay.text}
              </Chip>
              <Chip variant="flat" size="sm" className="text-xs justify-center" color="primary">
                ↑{formatFlow(forward.inFlow || 0)}
              </Chip>
              <Chip variant="flat" size="sm" className="text-xs justify-center" color="success">
                ↓{formatFlow(forward.outFlow || 0)}
              </Chip>
            </div>
          </div>

          <div className="flex gap-1.5 mt-3">
            <Button
              size="sm"
              variant="flat"
              color="primary"
              onPress={() => handleEdit(forward)}
              className="flex-1 min-h-8"
              startContent={
                <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                  <path d="M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z" />
                </svg>
              }
            >
              编辑
            </Button>
            <Button
              size="sm"
              variant="flat"
              color="warning"
              onPress={() => handleDiagnose(forward)}
              className="flex-1 min-h-8"
              startContent={
                <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                </svg>
              }
            >
              诊断
            </Button>
            <Button
              size="sm"
              variant="flat"
              color="danger"
              onPress={() => handleDelete(forward)}
              className="flex-1 min-h-8"
              startContent={
                <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M9 2a1 1 0 000 2h2a1 1 0 100-2H9z" clipRule="evenodd" />
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8 7a1 1 0 012 0v4a1 1 0 11-2 0V7zM12 7a1 1 0 012 0v4a1 1 0 11-2 0V7z" clipRule="evenodd" />
                </svg>
              }
            >
              删除
            </Button>
          </div>
        </CardBody>
      </Card>
    );
  };

  if (loading) {
    return (

        <div className="flex items-center justify-center h-64">
          <div className="flex items-center gap-3">
            <Spinner size="sm" />
            <span className="text-default-600">正在加载...</span>
          </div>
        </div>

    );
  }

  const tunnelGroups = groupForwardsByTunnel();
  const sortedForwards = getSortedForwards();
  const totalForwardCount = forwards.length;
  const totalRunningForwardCount = forwards.filter(forward => forward.serviceRunning).length;
  const totalOfflineForwardCount = forwards.filter(isForwardLinkOffline).length;
  const compatibleCandidateTunnels = selectedTunnel
    ? tunnels.filter(tunnel =>
        tunnel.id !== selectedTunnel.id
        && tunnel.inNodeId === selectedTunnel.inNodeId
      )
    : [];

  return (

      <div className="px-3 lg:px-6 py-8">
        {/* 页面头部 */}
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between mb-6">
          <div className="flex flex-wrap items-center gap-2">
            <Chip color="primary" variant="flat" size="sm" className="text-xs">
              {tunnelGroups.length} 个隧道
            </Chip>
            <Chip color="default" variant="flat" size="sm" className="text-xs">
              {totalForwardCount} 个转发
            </Chip>
            <Chip color="success" variant="flat" size="sm" className="text-xs">
              运行 {totalRunningForwardCount}
            </Chip>
            {totalOfflineForwardCount > 0 && (
              <Chip color="danger" variant="flat" size="sm" className="text-xs">
                异常 {totalOfflineForwardCount}
              </Chip>
            )}
          </div>
          <div className="flex items-center gap-3">
            {/* 显示模式切换按钮 */}
            <Button
              size="sm"
              variant="flat"
              color="default"
              onPress={handleViewModeChange}
              isIconOnly
              className="text-sm"
              title={viewMode === 'grouped' ? '切换到平铺排序' : '切换到按隧道归纳'}
            >
              {viewMode === 'grouped' ? (
                <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2zM3 16a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2z" clipRule="evenodd" />
                </svg>
              ) : (
                <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                  <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" />
                </svg>
              )}
            </Button>

            {/* 导入按钮 */}
            <Button
              size="sm"
              variant="flat"
              color="warning"
              onPress={handleImport}
            >
              导入
            </Button>

            {/* 导出按钮 */}
            <Button
              size="sm"
              variant="flat"
              color="success"
              onPress={handleExport}
              isLoading={exportLoading}

            >
              导出
            </Button>

            <Button
              size="sm"
              variant="flat"
              color="primary"
              onPress={handleAdd}

            >
              新增
            </Button>


          </div>
        </div>


        {/* 根据显示模式渲染不同内容 */}
        {viewMode === 'grouped' ? (
          /* 按隧道归纳的转发列表 */
          tunnelGroups.length > 0 ? (
            <div className="space-y-4">
              <Accordion
                variant="bordered"
                selectionMode="multiple"
                defaultExpandedKeys={tunnelGroups.map(group => String(group.tunnelId))}
                className="px-0"
              >
                {tunnelGroups.map((tunnelGroup) => {
                  const hasLinkOffline = tunnelGroup.offlineForwardCount > 0;
                  const userSummary = tunnelGroup.userNames.length > 3
                    ? `${tunnelGroup.userNames.slice(0, 3).join('、')} 等 ${tunnelGroup.userNames.length} 个用户`
                    : tunnelGroup.userNames.join('、');

                  return (
                    <AccordionItem
                      key={String(tunnelGroup.tunnelId)}
                      aria-label={tunnelGroup.tunnelName}
                      title={
                        <div className="flex flex-col gap-3 w-full pr-2 lg:flex-row lg:items-center lg:justify-between">
                          <div className="flex items-center gap-3 min-w-0 flex-1">
                            <div className={hasLinkOffline ? "offline-card w-9 h-9 bg-danger-100 rounded-lg border border-danger-200 flex items-center justify-center flex-shrink-0" : "w-9 h-9 bg-success-100 dark:bg-success-900/30 rounded-lg flex items-center justify-center flex-shrink-0"}>
                              <svg className={hasLinkOffline ? "w-4 h-4 text-danger" : "w-4 h-4 text-success"} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                              </svg>
                            </div>
                            <div className="min-w-0 flex-1">
                              <h3 className={hasLinkOffline ? "offline-section-heading text-sm font-semibold text-danger-700 truncate" : "text-sm font-semibold text-foreground truncate"}>
                                {tunnelGroup.tunnelName}
                              </h3>
                              <p className="text-xs text-default-500 truncate">
                                {userSummary || '未知用户'}
                              </p>
                            </div>
                          </div>
                          <div className="flex flex-wrap items-center gap-2 lg:justify-end">
                            {hasLinkOffline && (
                              <Chip color="danger" variant="flat" size="sm" className="text-xs offline-status-chip">
                                {tunnelGroup.offlineForwardCount} 条异常
                              </Chip>
                            )}
                            <Chip color="default" variant="flat" size="sm" className="text-xs">
                              运行 {tunnelGroup.runningForwardCount}/{tunnelGroup.forwards.length}
                            </Chip>
                            <Chip color="primary" variant="flat" size="sm" className="text-xs">
                              ↑{formatFlow(tunnelGroup.totalInFlow)}
                            </Chip>
                            <Chip color="success" variant="flat" size="sm" className="text-xs">
                              ↓{formatFlow(tunnelGroup.totalOutFlow)}
                            </Chip>
                          </div>
                        </div>
                      }
                      className={hasLinkOffline ? "offline-section-divider border-danger-200" : "border-divider"}
                    >
                      <SortableCardGrid
                        items={tunnelGroup.forwards}
                        getId={forward => forward.id}
                        onMove={forwardCardOrder.moveCard}
                        className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4 py-4"
                        renderItem={(forward, dragHandle) => renderForwardCard(forward, dragHandle)}
                      />
                    </AccordionItem>
                  );
                })}
              </Accordion>
            </div>
          ) : (
            /* 空状态 */
            <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
              <CardBody className="text-center py-16">
                <div className="flex flex-col items-center gap-4">
                  <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                    <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 9l4-4 4 4m0 6l-4 4-4-4" />
                    </svg>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-foreground">暂无转发配置</h3>
                    <p className="text-default-500 text-sm mt-1">还没有创建任何转发配置，点击上方按钮开始创建</p>
                  </div>
                </div>
              </CardBody>
            </Card>
          )
        ) : (
          /* 直接显示模式 */
          sortedForwards.length > 0 ? (
            <SortableCardGrid
              items={sortedForwards}
              getId={forward => forward.id}
              onMove={forwardCardOrder.moveCard}
              renderItem={(forward, dragHandle) => renderForwardCard(forward, dragHandle)}
            />
          ) : (
            /* 空状态 */
            <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
              <CardBody className="text-center py-16">
                <div className="flex flex-col items-center gap-4">
                  <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                    <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 9l4-4 4 4m0 6l-4 4-4-4" />
                    </svg>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-foreground">暂无转发配置</h3>
                    <p className="text-default-500 text-sm mt-1">还没有创建任何转发配置，点击上方按钮开始创建</p>
                  </div>
                </div>
              </CardBody>
            </Card>
          )
        )}

        {/* 新增/编辑模态框 */}
        <Modal
          isOpen={modalOpen}
          onOpenChange={setModalOpen}
          size="4xl"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">
                    {isEdit ? '编辑转发' : '新增转发'}
                  </h2>
                  <p className="text-small text-default-500">
                    {isEdit ? '修改现有转发配置的信息' : '创建新的转发配置'}
                  </p>
                </ModalHeader>
                <ModalBody>
                  <div className="space-y-6 pb-4">
                    <section>
                      <div className="mb-3">
                        <h3 className="text-sm font-semibold">基础信息</h3>
                        <p className="text-xs text-default-500">设置名称、主线路与入口端口。</p>
                      </div>
                      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                        <Input
                          label="转发名称"
                          placeholder="请输入转发名称"
                          value={form.name}
                          onChange={(e) => setForm(prev => ({ ...prev, name: e.target.value }))}
                          isInvalid={!!errors.name}
                          errorMessage={errors.name}
                          variant="bordered"
                        />
                        <Select
                          label="主线路"
                          placeholder="请选择主线路"
                          selectedKeys={form.tunnelId ? [form.tunnelId.toString()] : []}
                          onSelectionChange={(keys) => {
                            const selectedKey = Array.from(keys)[0] as string;
                            if (selectedKey) handleTunnelChange(selectedKey);
                          }}
                          isInvalid={!!errors.tunnelId}
                          errorMessage={errors.tunnelId}
                          variant="bordered"
                          description="按端口转发和隧道级数分类展示"
                        >
                          {renderTunnelLineSections(tunnels)}
                        </Select>
                        <Input
                          label={form.batchMode ? "入口起始端口" : "入口端口"}
                          placeholder="留空自动分配"
                          type="number"
                          value={form.inPort?.toString() || ''}
                          onChange={(e) => setForm(prev => ({
                            ...prev,
                            inPort: e.target.value ? parseInt(e.target.value) : null
                          }))}
                          isInvalid={!!errors.inPort}
                          errorMessage={errors.inPort}
                          variant="bordered"
                          description={
                            selectedTunnel?.inNodePortSta && selectedTunnel?.inNodePortEnd
                              ? `允许范围: ${selectedTunnel.inNodePortSta}-${selectedTunnel.inNodePortEnd}`
                              : '留空将自动分配可用端口'
                          }
                        />
                        <Input
                          label="出口网卡名或 IP"
                          placeholder="通常留空"
                          value={form.interfaceName}
                          onChange={(e) => setForm(prev => ({ ...prev, interfaceName: e.target.value }))}
                          isInvalid={!!errors.interfaceName}
                          errorMessage={errors.interfaceName}
                          variant="bordered"
                          description="仅在多 IP 服务器需要指定出口时填写"
                        />
                      </div>
                    </section>

                    <section className="border-t border-divider pt-5">
                      <div className="mb-3">
                        <h3 className="text-sm font-semibold">入口协议</h3>
                        <p className="text-xs text-default-500">同一入口端口可以只监听一种协议，也可以同时监听。</p>
                      </div>
                      <div className="grid grid-cols-3 overflow-hidden rounded-md border border-divider">
                        {([
                          ['tcp', '仅 TCP'],
                          ['udp', '仅 UDP'],
                          ['tcp_udp', 'TCP + UDP']
                        ] as const).map(([value, label]) => (
                          <Button
                            key={value}
                            radius="none"
                            variant={form.protocolMode === value ? 'solid' : 'light'}
                            color={form.protocolMode === value ? 'primary' : 'default'}
                            onPress={() => setForm(prev => ({ ...prev, protocolMode: value }))}
                            className="min-w-0"
                          >
                            {label}
                          </Button>
                        ))}
                      </div>
                    </section>

                    <section className="border-t border-divider pt-5">
                      <div className="mb-3">
                        <h3 className="text-sm font-semibold">线路策略</h3>
                        <p className="text-xs text-default-500">候选线路必须与主线路使用同一个入口节点。</p>
                      </div>
                      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
                        <div className="grid grid-cols-2 overflow-hidden rounded-md border border-divider sm:grid-cols-4">
                          {([
                            ['single', '单线路'],
                            ['failover', '主备切换'],
                            ['latency', '低延迟'],
                            ['balance', '负载均衡']
                          ] as const).map(([value, label]) => (
                            <Button
                              key={value}
                              radius="none"
                              variant={form.routeMode === value ? 'solid' : 'light'}
                              color={form.routeMode === value ? 'primary' : 'default'}
                              onPress={() => setForm(prev => ({
                                ...prev,
                                routeMode: value,
                                routeTunnelIds: value === 'single' ? [] : prev.routeTunnelIds
                              }))}
                              className="min-w-0 px-2"
                            >
                              {label}
                            </Button>
                          ))}
                        </div>
                        {form.routeMode !== 'single' && (
                          <Select
                            label="候选线路"
                            placeholder={compatibleCandidateTunnels.length > 0 ? "可选择多条备用线路" : "没有相同入口节点的其他线路"}
                            selectionMode="multiple"
                            selectedKeys={form.routeTunnelIds.map(String)}
                            onSelectionChange={(keys) => {
                              const selectedIds = Array.from(keys)
                                .map(String)
                                .map(Number)
                                .filter(Number.isFinite);
                              setForm(prev => ({ ...prev, routeTunnelIds: selectedIds }));
                            }}
                            isInvalid={!!errors.routeTunnelIds}
                            errorMessage={errors.routeTunnelIds}
                            variant="bordered"
                            isDisabled={compatibleCandidateTunnels.length === 0}
                            description="候选线路已按类型和隧道级数分组"
                          >
                            {renderTunnelLineSections(compatibleCandidateTunnels)}
                          </Select>
                        )}
                      </div>
                      {form.routeMode === 'balance' && (
                        <div className="mt-4 grid gap-3 border-t border-divider pt-4 lg:grid-cols-[minmax(220px,0.7fr)_minmax(0,1.3fr)]">
                          <Select label="新连接调度" selectedKeys={[form.routeBalanceStrategy]} variant="bordered" onSelectionChange={keys => setForm(prev => ({ ...prev, routeBalanceStrategy: String(Array.from(keys)[0] || 'round') as ForwardForm['routeBalanceStrategy'] }))}>
                            <SelectItem key="round">轮询 · 每条线路依次使用</SelectItem>
                            <SelectItem key="rand">随机 · 均匀随机选择</SelectItem>
                            <SelectItem key="weighted">加权随机 · 按线路权重分配</SelectItem>
                            <SelectItem key="hash">IP 哈希 · 同一来源固定线路</SelectItem>
                          </Select>
                          <div className="grid gap-2 sm:grid-cols-2">
                            {[form.tunnelId, ...form.routeTunnelIds].filter((id): id is number => Boolean(id)).map(id => {
                              const tunnel = tunnels.find(item => item.id === id);
                              return <Input key={id} type="number" min={1} max={1000} label={tunnel?.name || `线路 ${id}`} value={String(form.routeWeights[id] || 100)} isDisabled={form.routeBalanceStrategy !== 'weighted'} onValueChange={value => setForm(prev => ({ ...prev, routeWeights: { ...prev.routeWeights, [id]: Math.max(1, Math.min(1000, Number(value) || 100)) } }))} description={form.routeBalanceStrategy === 'weighted' ? '权重越高，获得的新连接越多' : '仅加权随机策略使用'} />;
                            })}
                          </div>
                          <p className="text-xs leading-5 text-default-500 lg:col-span-2">仅支持同入口的隧道线路。异常线路自动摘除，恢复后自动加入；已经建立的 TCP 连接不会迁移。</p>
                        </div>
                      )}
                    </section>

                    {!isEdit && (
                      <section className="border-t border-divider pt-5">
                        <div className="flex items-center justify-between gap-4">
                          <div>
                            <h3 className="text-sm font-semibold">端口段批量创建</h3>
                            <p className="text-xs text-default-500">按相同偏移一次创建最多 200 个入口端口。</p>
                          </div>
                          <Switch
                            size="sm"
                            isSelected={form.batchMode}
                            onValueChange={(batchMode) => setForm(prev => ({
                              ...prev,
                              batchMode,
                              batchEndPort: batchMode ? prev.batchEndPort : null,
                              targetStartPort: batchMode ? prev.targetStartPort : null
                            }))}
                          />
                        </div>
                        {form.batchMode && (
                          <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2">
                            <Input
                              label="入口结束端口"
                              type="number"
                              value={form.batchEndPort?.toString() || ''}
                              onChange={(e) => setForm(prev => ({
                                ...prev,
                                batchEndPort: e.target.value ? parseInt(e.target.value) : null
                              }))}
                              isInvalid={!!errors.batchEndPort}
                              errorMessage={errors.batchEndPort}
                              variant="bordered"
                            />
                            <Input
                              label="目标起始端口"
                              type="number"
                              value={form.targetStartPort?.toString() || ''}
                              onChange={(e) => setForm(prev => ({
                                ...prev,
                                targetStartPort: e.target.value ? parseInt(e.target.value) : null
                              }))}
                              isInvalid={!!errors.targetStartPort}
                              errorMessage={errors.targetStartPort}
                              variant="bordered"
                              description="后续目标端口按入口端口的偏移同步增加"
                            />
                          </div>
                        )}
                      </section>
                    )}

                    <section className="border-t border-divider pt-5">
                      <div className="mb-3">
                        <h3 className="text-sm font-semibold">目标地址池</h3>
                        <p className="text-xs text-default-500">面板会主动探测，失效目标自动移出，恢复后自动加入。</p>
                      </div>
                      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.5fr)_minmax(260px,0.8fr)]">
                        <Textarea
                          label="远程地址"
                          placeholder={"192.168.1.100:8080\nexample.com:3000"}
                          value={form.remoteAddr}
                          onChange={(e) => setForm(prev => ({ ...prev, remoteAddr: e.target.value }))}
                          isInvalid={!!errors.remoteAddr}
                          errorMessage={errors.remoteAddr}
                          variant="bordered"
                          description="每行一个 IP:端口 或 域名:端口"
                          minRows={4}
                          maxRows={8}
                        />
                        <Select
                          label="目标选择策略"
                          selectedKeys={[form.strategy]}
                          onSelectionChange={(keys) => {
                            const selectedKey = Array.from(keys)[0] as string;
                            setForm(prev => ({ ...prev, strategy: selectedKey }));
                          }}
                          variant="bordered"
                          isDisabled={getAddressCount(form.remoteAddr) <= 1}
                          description={getAddressCount(form.remoteAddr) > 1 ? "用于多个健康目标之间的调度" : "填写多个目标后可选择"}
                        >
                          <SelectItem key="fifo">主备 - 自上而下</SelectItem>
                          <SelectItem key="round">轮询 - 依次轮换</SelectItem>
                          <SelectItem key="rand">随机 - 随机选择</SelectItem>
                          <SelectItem key="hash">IP 哈希 - 来源固定</SelectItem>
                        </Select>
                      </div>
                    </section>
                  </div>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button
                    color="primary"
                    onPress={handleSubmit}
                    isLoading={submitLoading}
                  >
                    {isEdit ? '保存修改' : '创建转发'}
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 删除确认模态框 */}
        <Modal
          isOpen={deleteModalOpen}
          onOpenChange={setDeleteModalOpen}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-lg font-bold text-danger">确认删除</h2>
                </ModalHeader>
                <ModalBody>
                  <p className="text-default-600">
                    确定要删除转发 <span className="font-semibold text-foreground">"{forwardToDelete?.name}"</span> 吗？
                  </p>
                  <p className="text-small text-default-500 mt-2">
                    此操作无法撤销，删除后该转发将永久消失。
                  </p>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button
                    color="danger"
                    onPress={confirmDelete}
                    isLoading={deleteLoading}
                  >
                    确认删除
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 地址列表弹窗 */}
        <Modal isOpen={addressModalOpen} onClose={() => setAddressModalOpen(false)} size="lg" scrollBehavior="outside">
          <ModalContent>
            <ModalHeader className="text-base">{addressModalTitle}</ModalHeader>
            <ModalBody className="pb-6">
              <div className="mb-4 text-right">
                <Button size="sm" onClick={copyAllAddresses}>
                  复制
                </Button>
              </div>

              <div className="space-y-2 max-h-60 overflow-y-auto">
                {addressList.map((item) => (
                  <div key={item.id} className="flex justify-between items-center p-3 border border-default-200 dark:border-default-100 rounded-lg">
                    <code className="text-sm flex-1 mr-3 text-foreground">{item.address}</code>
                    <Button
                      size="sm"
                      variant="light"
                      isLoading={item.copying}
                      onClick={() => copyAddress(item)}
                    >
                      复制
                    </Button>
                  </div>
                ))}
              </div>
            </ModalBody>
          </ModalContent>
        </Modal>

        {/* 导出数据模态框 */}
        <Modal
          isOpen={exportModalOpen}
          onClose={() => {
            setExportModalOpen(false);
            setSelectedTunnelForExport(null);
            setExportData('');
          }}

          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader className="flex flex-col gap-1">
              <h2 className="text-xl font-bold">导出转发数据</h2>
              <p className="text-small text-default-500">
                格式：目标地址|转发名称|入口端口
              </p>
            </ModalHeader>
            <ModalBody className="pb-6">
              <div className="space-y-4">
                {/* 隧道选择 */}
                <div>
                  <Select
                    label="选择导出隧道"
                    placeholder="请选择要导出的隧道"
                    selectedKeys={selectedTunnelForExport ? [selectedTunnelForExport.toString()] : []}
                    onSelectionChange={(keys) => {
                      const selectedKey = Array.from(keys)[0] as string;
                      setSelectedTunnelForExport(selectedKey ? parseInt(selectedKey) : null);
                    }}
                    variant="bordered"
                    isRequired
                  >
                    {tunnels.map((tunnel) => (
                      <SelectItem key={tunnel.id.toString()} textValue={tunnel.name}>
                        {tunnel.name}
                      </SelectItem>
                    ))}
                  </Select>
                </div>

                {/* 导出按钮和数据 */}
                {exportData && (
                  <div className="flex justify-between items-center">
                    <Button
                      color="primary"
                      size="sm"
                      onPress={executeExport}
                      isLoading={exportLoading}
                      isDisabled={!selectedTunnelForExport}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
                        </svg>
                      }
                    >
                      重新生成
                    </Button>
                    <Button
                      color="secondary"
                      size="sm"
                      onPress={copyExportData}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path d="M8 3a1 1 0 011-1h2a1 1 0 110 2H9a1 1 0 01-1-1z" />
                          <path d="M6 3a2 2 0 00-2 2v11a2 2 0 002 2h8a2 2 0 002-2V5a2 2 0 00-2-2 3 3 0 01-3 3H9a3 3 0 01-3-3z" />
                        </svg>
                      }
                    >
                      复制
                    </Button>
                  </div>
                )}

                {/* 初始导出按钮 */}
                {!exportData && (
                  <div className="text-right">
                    <Button
                      color="primary"
                      size="sm"
                      onPress={executeExport}
                      isLoading={exportLoading}
                      isDisabled={!selectedTunnelForExport}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
                        </svg>
                      }
                    >
                      生成导出数据
                    </Button>
                  </div>
                )}

                {/* 导出数据显示 */}
                {exportData && (
                  <div className="relative">
                    <Textarea
                      value={exportData}
                      readOnly
                      variant="bordered"
                      minRows={10}
                      maxRows={20}
                      className="font-mono text-sm"
                      classNames={{
                        input: "font-mono text-sm"
                      }}
                      placeholder="暂无数据"
                    />
                  </div>
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="light"
                onPress={() => setExportModalOpen(false)}
              >
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 导入数据模态框 */}
        <Modal
          isOpen={importModalOpen}
          onClose={() => setImportModalOpen(false)}

          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader className="flex flex-col gap-1">
              <h2 className="text-xl font-bold">导入转发数据</h2>
              <p className="text-small text-default-500">
                格式：目标地址|转发名称|入口端口，每行一个，入口端口留空将自动分配可用端口
              </p>
              <p className="text-small text-default-400">
                目标地址支持单个地址(如：example.com:8080)或多个地址用逗号分隔(如：3.3.3.3:3,4.4.4.4:4)
              </p>
            </ModalHeader>
            <ModalBody className="pb-6">
              <div className="space-y-4">
                {/* 隧道选择 */}
                <div>
                  <Select
                    label="选择导入隧道"
                    placeholder="请选择要导入的隧道"
                    selectedKeys={selectedTunnelForImport ? [selectedTunnelForImport.toString()] : []}
                    onSelectionChange={(keys) => {
                      const selectedKey = Array.from(keys)[0] as string;
                      setSelectedTunnelForImport(selectedKey ? parseInt(selectedKey) : null);
                    }}
                    variant="bordered"
                    isRequired
                  >
                    {tunnels.map((tunnel) => (
                      <SelectItem key={tunnel.id.toString()} textValue={tunnel.name}>
                        {tunnel.name}
                      </SelectItem>
                    ))}
                  </Select>
                </div>

                {/* 输入区域 */}
                <div>
                  <Textarea
                    label="导入数据"
                    placeholder="请输入要导入的转发数据，格式：目标地址|转发名称|入口端口"
                    value={importData}
                    onChange={(e) => setImportData(e.target.value)}
                    variant="flat"
                    minRows={8}
                    maxRows={12}
                    classNames={{
                      input: "font-mono text-sm"
                    }}
                  />


                </div>

                {/* 导入结果 */}
                {importResults.length > 0 && (
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <h3 className="text-base font-semibold">导入结果</h3>
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-default-500">
                          成功：{importResults.filter(r => r.success).length} /
                          总计：{importResults.length}
                        </span>
                      </div>
                    </div>

                    <div className="max-h-40 overflow-y-auto space-y-1" style={{
                      scrollbarWidth: 'thin',
                      scrollbarColor: 'rgb(156 163 175) transparent'
                    }}>
                      {importResults.map((result, index) => (
                        <div
                          key={index}
                          className={`p-2 rounded border ${
                            result.success
                              ? 'bg-success-50 dark:bg-success-100/10 border-success-200 dark:border-success-300/20'
                              : 'bg-danger-50 dark:bg-danger-100/10 border-danger-200 dark:border-danger-300/20'
                          }`}
                        >
                          <div className="flex items-center gap-2">
                            {result.success ? (
                              <svg className="w-3 h-3 text-success-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                              </svg>
                            ) : (
                              <svg className="w-3 h-3 text-danger-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
                              </svg>
                            )}
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 mb-0.5">
                                <span className={`text-xs font-medium ${
                                  result.success ? 'text-success-700 dark:text-success-300' : 'text-danger-700 dark:text-danger-300'
                                }`}>
                                  {result.success ? '成功' : '失败'}
                                </span>
                                <span className="text-xs text-default-500">|</span>
                                <code className="text-xs font-mono text-default-600 truncate">{result.line}</code>
                              </div>
                              <div className={`text-xs ${
                                result.success ? 'text-success-600 dark:text-success-400' : 'text-danger-600 dark:text-danger-400'
                              }`}>
                                {result.message}
                              </div>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="light"
                onPress={() => setImportModalOpen(false)}
              >
                关闭
              </Button>
              <Button
                color="warning"
                onPress={executeImport}
                isLoading={importLoading}
                isDisabled={!importData.trim() || !selectedTunnelForImport}
              >
                开始导入
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 容灾详情模态框 */}
        <Modal
          isOpen={failoverModalOpen}
          onOpenChange={setFailoverModalOpen}
          size="2xl"
          scrollBehavior="inside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            {(onClose) => {
              const detailRoutes = currentFailoverForward ? parseForwardRoutes(currentFailoverForward) : [];
              const detailActive = currentFailoverForward ? getActiveRoute(currentFailoverForward) : null;
              const detailActiveTunnelId = currentFailoverForward
                ? (currentFailoverForward.activeTunnelId || currentFailoverForward.tunnelId)
                : undefined;
              return (
                <>
                  <ModalHeader className="flex flex-col gap-1">
                    <div className="flex items-center gap-2">
                      <ShieldCheck size={20} className="text-success" aria-hidden="true" />
                      <h2 className="text-xl font-bold">线路调度详情</h2>
                    </div>
                    <p className="truncate text-small font-normal text-default-500">
                      {currentFailoverForward?.name || '转发线路'}
                    </p>
                  </ModalHeader>
                  <ModalBody className="gap-5 pb-5">
                    {currentFailoverForward && (
                      <section className="grid grid-cols-2 gap-x-4 gap-y-3 border-y border-divider py-4 md:grid-cols-4">
                        <div className="min-w-0">
                          <div className="text-xs text-default-400">运行策略</div>
                          <div className="truncate text-sm font-medium">{getRouteModeDisplay(currentFailoverForward.routeMode)}</div>
                        </div>
                        <div className="min-w-0">
                          <div className="text-xs text-default-400">{currentFailoverForward.routeMode === 'balance' ? '可用成员' : '当前承载'}</div>
                          <div className="truncate text-sm font-medium">{currentFailoverForward.routeMode === 'balance' ? `${detailRoutes.filter(route => route.status === 'healthy').length}/${detailRoutes.length}` : (detailActive?.tunnelName || currentFailoverForward.tunnelName)}</div>
                        </div>
                        <div className="min-w-0">
                          <div className="text-xs text-default-400">自动切换次数</div>
                          <div className="text-sm font-medium">{currentFailoverForward.routeSwitchCount || 0}</div>
                        </div>
                        <div className="min-w-0">
                          <div className="text-xs text-default-400">上次切换</div>
                          <div className="truncate text-sm font-medium">{formatRouteEventTime(currentFailoverForward.lastRouteSwitch)}</div>
                        </div>
                      </section>
                    )}

                    <section>
                      <div className="mb-2 flex items-center justify-between gap-3">
                        <h3 className="text-sm font-semibold">候选线路状态</h3>
                        <Chip size="sm" variant="flat" color="success">
                          {detailRoutes.filter(route => route.status === 'healthy').length}/{detailRoutes.length} 可用
                        </Chip>
                      </div>
                      <div className="divide-y divide-divider border-y border-divider">
                        {detailRoutes.map((route) => {
                          const isActive = route.tunnelId === detailActiveTunnelId;
                          const isHealthy = route.status === 'healthy';
                          return (
                            <div key={route.tunnelId} className="flex items-center gap-3 py-3">
                              <Route size={17} className={isActive ? 'text-primary' : 'text-default-400'} aria-hidden="true" />
                              <div className="min-w-0 flex-1">
                                <div className="flex flex-wrap items-center gap-2">
                                  <span className="truncate text-sm font-medium">{route.tunnelName || `线路 ${route.tunnelId}`}</span>
                                  {currentFailoverForward?.routeMode !== 'balance' && isActive && <Chip size="sm" color="primary" variant="flat">当前</Chip>}
                                  {currentFailoverForward?.routeMode === 'balance' && <Chip size="sm" color={isHealthy ? 'success' : 'default'} variant="flat">{isHealthy ? '负载池内' : '已摘除'}</Chip>}
                                  {currentFailoverForward?.routeMode === 'balance' && <Chip size="sm" variant="flat">权重 {route.weight || 100}</Chip>}
                                  {(route.priority || 0) === 0 && <Chip size="sm" variant="flat">主线</Chip>}
                                </div>
                                <p className="mt-0.5 truncate text-xs text-default-500" title={route.message}>
                                  {route.message || '等待健康检查'}
                                </p>
                              </div>
                              <div className="flex-shrink-0 text-right">
                                <div className={`text-xs font-medium ${isHealthy ? 'text-success' : route.status === 'unhealthy' ? 'text-danger' : 'text-default-500'}`}>
                                  {getRouteStatusText(route)}
                                </div>
                                <div className="text-[11px] text-default-400">
                                  {typeof route.latency === 'number' ? `${route.latency.toFixed(0)} ms` : '-'}
                                </div>
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    </section>

                    <section>
                      <h3 className="mb-2 text-sm font-semibold">线路调度记录</h3>
                      {failoverLoading ? (
                        <div className="flex items-center justify-center gap-2 py-10 text-sm text-default-500">
                          <Spinner size="sm" />
                          正在读取切换记录...
                        </div>
                      ) : routeSwitchEvents.length > 0 ? (
                        <div className="divide-y divide-divider border-y border-divider">
                          {routeSwitchEvents.map((event) => (
                            <div key={event.id} className="py-3">
                              <div className="flex items-start justify-between gap-3">
                                <div className="min-w-0">
                                  <div className="flex flex-wrap items-center gap-1.5 text-sm font-medium">
                                    <span className="truncate">{event.fromTunnelName || `线路 ${event.fromTunnelId || '-'}`}</span>
                                    <span className="text-default-400">→</span>
                                    <span className="truncate">{event.toTunnelName || `线路 ${event.toTunnelId || '-'}`}</span>
                                  </div>
                                  <p className="mt-1 text-xs text-default-500">{event.reason}</p>
                                  {event.detail && <p className="mt-1 text-xs text-danger">{event.detail}</p>}
                                </div>
                                <div className="flex-shrink-0 text-right">
                                  <Chip size="sm" variant="flat" color={event.status === 'success' ? 'success' : 'danger'}>
                                    {event.status === 'success' ? '成功' : '失败'}
                                  </Chip>
                                  <div className="mt-1 text-[11px] text-default-400">{formatRouteEventTime(event.createdAt)}</div>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="border-y border-divider py-8 text-center text-sm text-default-400">
                          尚未发生自动线路切换
                        </div>
                      )}
                    </section>

                    {currentFailoverForward?.routeSwitchReason && (
                      <Alert
                        color="primary"
                        variant="flat"
                        title="最近一次切换原因"
                        description={currentFailoverForward.routeSwitchReason}
                      />
                    )}
                  </ModalBody>
                  <ModalFooter>
                    <Button variant="light" onPress={onClose}>关闭</Button>
                    {currentFailoverForward && (
                      <Button color="primary" variant="flat" onPress={() => handleFailoverDetails(currentFailoverForward)} isLoading={failoverLoading}>
                        刷新状态
                      </Button>
                    )}
                  </ModalFooter>
                </>
              );
            }}
          </ModalContent>
        </Modal>

        {/* 诊断结果模态框 */}
        <Modal
          isOpen={diagnosisModalOpen}
          onOpenChange={setDiagnosisModalOpen}

          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">转发诊断结果</h2>
                  {currentDiagnosisForward && (
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-small text-default-500 truncate flex-1 min-w-0">{currentDiagnosisForward.name}</span>
                      <Chip
                        color="primary"
                        variant="flat"
                        size="sm"
                        className="flex-shrink-0"
                      >
                        转发服务
                      </Chip>
                    </div>
                  )}
                </ModalHeader>
                <ModalBody>
                  {diagnosisLoading ? (
                    <div className="flex items-center justify-center py-16">
                      <div className="flex items-center gap-3">
                        <Spinner size="sm" />
                        <span className="text-default-600">正在诊断转发连接...</span>
                      </div>
                    </div>
                  ) : diagnosisResult ? (
                    <div className="space-y-4">
                      {(() => {
                        const summary = getDiagnosisLatencySummary(diagnosisResult);
                        const canShowLatency = summary.successfulCount > 0;

                        return (
                          <Card className={`shadow-sm border ${summary.failedCount > 0 ? 'border-warning' : 'border-primary'}`}>
                            <CardBody>
                              <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
                                <div>
                                  <div className="text-small text-default-500">转发总延迟</div>
                                  <div className="flex items-end gap-2">
                                    <span className={summary.failedCount > 0 ? "text-3xl font-bold text-warning" : "text-3xl font-bold text-primary"}>
                                      {canShowLatency ? summary.totalLatency.toFixed(0) : '-'}
                                    </span>
                                    <span className="text-small text-default-500 mb-1">ms</span>
                                  </div>
                                </div>
                                <div className="flex flex-wrap items-center gap-2">
                                  <Chip color={summary.failedCount > 0 ? 'warning' : 'success'} variant="flat">
                                    {summary.failedCount > 0
                                      ? `成功 ${summary.successfulCount}/${summary.totalCount} 段`
                                      : `完整 ${summary.totalCount} 段`}
                                  </Chip>
                                  {summary.quality && (
                                    <Chip color={summary.quality.color as any} variant="flat">
                                      {summary.quality.text}
                                    </Chip>
                                  )}
                                </div>
                              </div>
                              {summary.failedCount > 0 && (
                                <p className="text-small text-warning-600 dark:text-warning-400 mt-3">
                                  有 {summary.failedCount} 段链路诊断失败，当前总延迟只统计成功链路。
                                </p>
                              )}
                            </CardBody>
                          </Card>
                        );
                      })()}
                      {diagnosisResult.results.map((result, index) => {
                        const quality = getQualityDisplay(result.averageTime, result.packetLoss);

                        return (
                          <Card key={index} className={`shadow-sm border ${result.success ? 'border-success' : 'border-danger'}`}>
                            <CardHeader className="pb-2">
                              <div className="flex items-center justify-between w-full">
                                <div>
                                  <h3 className="text-lg font-semibold text-foreground">{result.description}</h3>
                                  <div className="flex items-center gap-2 mt-1">
                                    <span className="text-small text-default-500">节点: {result.nodeName}</span>
                                    <Chip
                                      color={result.success ? 'success' : 'danger'}
                                      variant="flat"
                                      size="sm"
                                    >
                                      {result.success ? '连接成功' : '连接失败'}
                                    </Chip>
                                  </div>
                                </div>
                              </div>
                            </CardHeader>

                            <CardBody className="pt-0">
                              {result.success ? (
                                <div className="space-y-3">
                                  <div className="grid grid-cols-3 gap-4">
                                    <div className="text-center">
                                      <div className="text-2xl font-bold text-primary">{result.averageTime?.toFixed(0)}</div>
                                      <div className="text-small text-default-500">平均延迟(ms)</div>
                                    </div>
                                    <div className="text-center">
                                      <div className="text-2xl font-bold text-warning">{result.packetLoss?.toFixed(1)}</div>
                                      <div className="text-small text-default-500">丢包率(%)</div>
                                    </div>
                                    <div className="text-center">
                                      {quality && (
                                        <>
                                          <Chip color={quality.color as any} variant="flat" size="lg">
                                            {quality.text}
                                          </Chip>
                                          <div className="text-small text-default-500 mt-1">连接质量</div>
                                        </>
                                      )}
                                    </div>
                                  </div>
                                  <div className="text-small text-default-500 flex items-center gap-1">
                                    <span className="flex-shrink-0">目标地址:</span>
                                    <code className="font-mono truncate min-w-0" title={`${result.targetIp}${result.targetPort ? ':' + result.targetPort : ''}`}>
                                      {result.targetIp}{result.targetPort ? ':' + result.targetPort : ''}
                                    </code>
                                  </div>
                                </div>
                              ) : (
                                <div className="space-y-2">
                                  <div className="text-small text-default-500 flex items-center gap-1">
                                    <span className="flex-shrink-0">目标地址:</span>
                                    <code className="font-mono truncate min-w-0" title={`${result.targetIp}${result.targetPort ? ':' + result.targetPort : ''}`}>
                                      {result.targetIp}{result.targetPort ? ':' + result.targetPort : ''}
                                    </code>
                                  </div>
                                  <Alert
                                    color="danger"
                                    variant="flat"
                                    title="错误详情"
                                    description={result.message}
                                  />
                                </div>
                              )}
                            </CardBody>
                          </Card>
                        );
                      })}
                    </div>
                  ) : (
                    <div className="text-center py-16">
                      <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center mx-auto mb-4">
                        <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9.75 9.75l4.5 4.5m0-4.5l-4.5 4.5M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                        </svg>
                      </div>
                      <h3 className="text-lg font-semibold text-foreground">暂无诊断数据</h3>
                    </div>
                  )}
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    关闭
                  </Button>
                  {currentDiagnosisForward && (
                    <Button
                      color="primary"
                      onPress={() => handleDiagnose(currentDiagnosisForward)}
                      isLoading={diagnosisLoading}
                    >
                      重新诊断
                    </Button>
                  )}
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>
      </div>

  );
}
