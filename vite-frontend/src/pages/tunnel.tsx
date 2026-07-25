import { useState, useEffect } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import { Divider } from "@heroui/divider";
import { Alert } from "@heroui/alert";
import toast from 'react-hot-toast';

import { SortableCardGrid } from '@/components/sortable-card-grid';
import { useCardOrder } from '@/hooks/use-card-order';

import {
  createTunnel,
  getTunnelList,
  updateTunnel,
  deleteTunnel,
  getNodeList,
  diagnoseTunnel
} from "@/api";

interface Tunnel {
  id: number;
  name: string;
  type: number; // 1: 端口转发, 2: 隧道转发
  inNodeId: number;
  outNodeId?: number;
  inNodeStatus?: number;
  outNodeStatus?: number;
  inIp: string;
  outIp?: string;
  nodePath?: string;
  protocol?: string;
  tcpListenAddr: string;
  udpListenAddr: string;
  interfaceName?: string;
  flow: number; // 1: 单向, 2: 双向
  trafficRatio: number;
  status: number;
  createdTime: string | number;
  ownerUserId?: number;
  ownerUserName?: string;
  ownerRoleId?: number;
  accessType?: 'admin' | 'owned' | 'shared';
  editable?: boolean;
  deletable?: boolean;
  pathNodeDetails?: Array<{
    nodeId: number;
    name: string;
    status: number;
  }>;
}

interface Node {
  id: number;
  name: string;
  status: number; // 1: 在线, 0: 离线
}

interface TunnelForm {
  id?: number;
  name: string;
  type: number;
  inNodeId: number | null;
  outNodeId?: number | null;
  nodePath: Array<number | null>;
  protocol: string;
  tcpListenAddr: string;
  udpListenAddr: string;
  interfaceName?: string;
  flow: number;
  trafficRatio: number;
  status: number;
}

interface DiagnosisResult {
  tunnelName: string;
  tunnelType: string;
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

interface TunnelLevelGroup {
  key: string;
  title: string;
  description: string;
  level: number;
  tunnels: Tunnel[];
}

const getTunnelOwnerBadge = (tunnel: Tunnel) => {
  const ownerName = tunnel.ownerUserName || '未知用户';
  if (tunnel.accessType === 'shared') {
    return { label: `共享 · ${ownerName}`, title: `管理员共享的隧道，所有者：${ownerName}`, color: 'secondary' as const };
  }
  if (tunnel.accessType === 'owned') {
    return { label: '我的', title: `当前用户创建的隧道：${ownerName}`, color: 'primary' as const };
  }
  if (tunnel.ownerRoleId === 1) {
    return { label: `用户 · ${ownerName}`, title: `普通用户创建，所有者：${ownerName}`, color: 'warning' as const };
  }
  if (tunnel.ownerRoleId === 0) {
    return { label: '管理员', title: `管理员资源，所有者：${ownerName}`, color: 'default' as const };
  }
  return { label: `归属 · ${ownerName}`, title: `资源所有者：${ownerName}`, color: 'default' as const };
};

export default function TunnelPage() {
  const [loading, setLoading] = useState(true);
  const [tunnels, setTunnels] = useState<Tunnel[]>([]);
  const [nodes, setNodes] = useState<Node[]>([]);

  // 模态框状态
  const [modalOpen, setModalOpen] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [diagnosisModalOpen, setDiagnosisModalOpen] = useState(false);
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [diagnosisLoading, setDiagnosisLoading] = useState(false);
  const [tunnelToDelete, setTunnelToDelete] = useState<Tunnel | null>(null);
  const [currentDiagnosisTunnel, setCurrentDiagnosisTunnel] = useState<Tunnel | null>(null);
  const [diagnosisResult, setDiagnosisResult] = useState<DiagnosisResult | null>(null);

  // 表单状态
  const [form, setForm] = useState<TunnelForm>({
    name: '',
    type: 1,
    inNodeId: null,
    outNodeId: null,
    nodePath: [null, null],
    protocol: 'tls',
    tcpListenAddr: '[::]',
    udpListenAddr: '[::]',
    interfaceName: '',
    flow: 1,
    trafficRatio: 1.0,
    status: 1
  });

  // 表单验证错误
  const [errors, setErrors] = useState<{[key: string]: string}>({});

  useEffect(() => {
    loadData();
  }, []);

  // 加载所有数据
  const loadData = async () => {
    setLoading(true);
    try {
      const [tunnelsRes, nodesRes] = await Promise.all([
        getTunnelList(),
        getNodeList()
      ]);

      if (tunnelsRes.code === 0) {
        setTunnels(tunnelsRes.data || []);
      } else {
        toast.error(tunnelsRes.msg || '获取隧道列表失败');
      }

      if (nodesRes.code === 0) {
        setNodes(nodesRes.data || []);
      } else {
        console.warn('获取节点列表失败:', nodesRes.msg);
      }
    } catch (error) {
      console.error('加载数据失败:', error);
      toast.error('加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  // 表单验证
  const validateForm = (): boolean => {
    const newErrors: {[key: string]: string} = {};

    if (!form.name.trim()) {
      newErrors.name = '请输入隧道名称';
    } else if (form.name.length < 2 || form.name.length > 50) {
      newErrors.name = '隧道名称长度应在2-50个字符之间';
    }

    if (!form.inNodeId) {
      newErrors.inNodeId = '请选择入口节点';
    }

    if (!form.tcpListenAddr.trim()) {
      newErrors.tcpListenAddr = '请输入TCP监听地址';
    }

    if (!form.udpListenAddr.trim()) {
      newErrors.udpListenAddr = '请输入UDP监听地址';
    }

    if (form.trafficRatio < 0.0 || form.trafficRatio > 100.0) {
      newErrors.trafficRatio = '流量倍率必须在0.0-100.0之间';
    }

    // 隧道转发时的验证
    if (form.type === 2) {
      const path = normalizeFormPath(form.inNodeId, form.nodePath, form.outNodeId);
      const filledPath = path.filter((nodeId): nodeId is number => nodeId !== null);
      if (path.some(nodeId => nodeId === null) || filledPath.length < 2) {
        newErrors.nodePath = '请至少选择入口和出口两个节点';
      } else if (new Set(filledPath).size !== filledPath.length) {
        newErrors.nodePath = '节点路径不能包含重复节点';
      }

      if (!form.protocol) {
        newErrors.protocol = '请选择协议类型';
      }
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 新增隧道
  const handleAdd = () => {
    setIsEdit(false);
    setForm({
      name: '',
      type: 1,
      inNodeId: null,
      outNodeId: null,
      nodePath: [null, null],
      protocol: 'tls',
      tcpListenAddr: '[::]',
      udpListenAddr: '[::]',
      interfaceName: '',
      flow: 1,
      trafficRatio: 1.0,
      status: 1
    });
    setErrors({});
    setModalOpen(true);
  };

  // 编辑隧道 - 只能修改部分字段
  const handleEdit = (tunnel: Tunnel) => {
    setIsEdit(true);
    setForm({
      id: tunnel.id,
      name: tunnel.name,
      type: tunnel.type,
      inNodeId: tunnel.inNodeId,
      outNodeId: tunnel.outNodeId || null,
      nodePath: getTunnelNodePath(tunnel).length > 0 ? getTunnelNodePath(tunnel) : [tunnel.inNodeId, tunnel.outNodeId || null],
      protocol: tunnel.protocol || 'tls',
      tcpListenAddr: tunnel.tcpListenAddr || '[::]',
      udpListenAddr: tunnel.udpListenAddr || '[::]',
      interfaceName: tunnel.interfaceName || '',
      flow: tunnel.flow,
      trafficRatio: tunnel.trafficRatio,
      status: tunnel.status
    });
    setErrors({});
    setModalOpen(true);
  };

  // 删除隧道
  const handleDelete = (tunnel: Tunnel) => {
    setTunnelToDelete(tunnel);
    setDeleteModalOpen(true);
  };

  const confirmDelete = async () => {
    if (!tunnelToDelete) return;

    setDeleteLoading(true);
    try {
      const response = await deleteTunnel(tunnelToDelete.id);
      if (response.code === 0) {
        const summary = response.data || {};
        const forwardCount = Number(summary.forwardCount || 0);
        const forwardGostCleanupFailCount = Number(summary.forwardGostCleanupFailCount || 0);
        const userTunnelCount = Number(summary.userTunnelCount || 0);
        const speedLimitCount = Number(summary.speedLimitCount || 0);
        const cleanupText = forwardCount + userTunnelCount + speedLimitCount > 0
          ? `，已清理 ${forwardCount} 个转发、${userTunnelCount} 个授权、${speedLimitCount} 个限速规则`
          : '';
        const remoteCleanupText = forwardGostCleanupFailCount > 0
          ? `，其中 ${forwardGostCleanupFailCount} 个转发未能通知节点清理远端服务`
          : '';
        toast.success(`隧道删除成功${cleanupText}${remoteCleanupText}`);
        setDeleteModalOpen(false);
        setTunnelToDelete(null);
        loadData();
      } else {
        toast.error(response.msg || '删除失败');
      }
    } catch (error) {
      console.error('删除失败:', error);
      toast.error('删除失败');
    } finally {
      setDeleteLoading(false);
    }
  };

  // 隧道类型改变时的处理
  const handleTypeChange = (type: number) => {
    setForm(prev => ({
      ...prev,
      type,
      outNodeId: type === 1 ? null : prev.outNodeId,
      nodePath: type === 1
        ? [prev.inNodeId]
        : normalizeFormPath(prev.inNodeId, prev.nodePath, prev.outNodeId),
      protocol: type === 1 ? 'tls' : prev.protocol
    }));
  };

  // 提交表单
  const handleSubmit = async () => {
    if (!validateForm()) return;

    setSubmitLoading(true);
    try {
      const path = normalizeFormPath(form.inNodeId, form.nodePath, form.outNodeId)
        .filter((nodeId): nodeId is number => nodeId !== null);
      const data = {
        ...form,
        nodePath: form.type === 2 ? path : [form.inNodeId],
        outNodeId: form.type === 2 ? path[path.length - 1] : form.inNodeId
      };

      const response = isEdit
        ? await updateTunnel(data)
        : await createTunnel(data);

      if (response.code === 0) {
        toast.success(isEdit ? '更新成功' : '创建成功');
        setModalOpen(false);
        loadData();
      } else {
        toast.error(response.msg || (isEdit ? '更新失败' : '创建失败'));
      }
    } catch (error) {
      console.error('提交失败:', error);
      toast.error('网络错误，请重试');
    } finally {
      setSubmitLoading(false);
    }
  };

  // 诊断隧道
  const handleDiagnose = async (tunnel: Tunnel) => {
    setCurrentDiagnosisTunnel(tunnel);
    setDiagnosisModalOpen(true);
    setDiagnosisLoading(true);
    setDiagnosisResult(null);

    try {
      const response = await diagnoseTunnel(tunnel.id);
      if (response.code === 0) {
        setDiagnosisResult(response.data);
      } else {
        toast.error(response.msg || '诊断失败');
        setDiagnosisResult({
          tunnelName: tunnel.name,
          tunnelType: tunnel.type === 1 ? '端口转发' : '隧道转发',
          timestamp: Date.now(),
          results: [{
            success: false,
            description: '诊断失败',
            nodeName: '-',
            nodeId: '-',
            targetIp: '-',
            targetPort: 443,
            message: response.msg || '诊断过程中发生错误'
          }]
        });
      }
    } catch (error) {
      console.error('诊断失败:', error);
      toast.error('网络错误，请重试');
      setDiagnosisResult({
        tunnelName: tunnel.name,
        tunnelType: tunnel.type === 1 ? '端口转发' : '隧道转发',
        timestamp: Date.now(),
        results: [{
          success: false,
          description: '网络错误',
          nodeName: '-',
          nodeId: '-',
          targetIp: '-',
          targetPort: 443,
          message: '无法连接到服务器'
        }]
      });
    } finally {
      setDiagnosisLoading(false);
    }
  };

  // 获取显示的IP（处理多IP）
  const getDisplayIp = (ipString?: string): string => {
    if (!ipString) return '-';

    const ips = ipString.split(',').map(ip => ip.trim()).filter(ip => ip);

    if (ips.length === 0) return '-';
    if (ips.length === 1) return ips[0];

    return `${ips[0]} 等${ips.length}个`;
  };

  // 获取节点名称
  const getNodeName = (nodeId?: number): string => {
    if (!nodeId) return '-';
    const node = nodes.find(n => n.id === nodeId);
    return node ? node.name : `节点${nodeId}`;
  };

  const getNodeStatus = (nodeId?: number, fallbackStatus?: number): number | undefined => {
    if (!nodeId) return fallbackStatus;
    const node = nodes.find(n => n.id === nodeId);
    return node ? node.status : fallbackStatus;
  };

  const normalizeFormPath = (
    inNodeId: number | null,
    nodePath: Array<number | null> = [],
    outNodeId?: number | null
  ): Array<number | null> => {
    const path = nodePath.length > 0 ? [...nodePath] : [inNodeId, outNodeId ?? null];
    path[0] = inNodeId;
    if (path.length < 2) {
      path.push(outNodeId ?? null);
    }
    return path;
  };

  const getTunnelNodePath = (tunnel: Tunnel): number[] => {
    if (tunnel.nodePath) {
      const path = tunnel.nodePath
        .split(',')
        .map(item => Number(item.trim()))
        .filter(nodeId => Number.isFinite(nodeId) && nodeId > 0);
      if (path.length > 0) return path;
    }
    if (tunnel.type === 1) return [tunnel.inNodeId];
    return [tunnel.inNodeId, tunnel.outNodeId].filter((nodeId): nodeId is number => !!nodeId);
  };

  const getTunnelPathNodes = (tunnel: Tunnel) => {
    const detailByNodeId = new Map(
      (tunnel.pathNodeDetails || []).map(detail => [detail.nodeId, detail])
    );

    return getTunnelNodePath(tunnel).map((nodeId, index, path) => {
      const detail = detailByNodeId.get(nodeId);
      return {
        nodeId,
        name: detail?.name || getNodeName(nodeId),
        label: tunnel.type === 1
          ? (index === 0 ? '入口/出口节点' : '节点')
          : (index === 0 ? '入口节点' : index === path.length - 1 ? '出口节点' : `中转节点 ${index}`),
        status: getNodeStatus(
          nodeId,
          detail?.status ?? (index === 0 ? tunnel.inNodeStatus : (index === path.length - 1 ? tunnel.outNodeStatus : undefined))
        ),
        ip: index === 0 ? tunnel.inIp : (index === path.length - 1 ? tunnel.outIp : undefined)
      };
    });
  };

  const isTunnelNodeOffline = (tunnel: Tunnel): boolean => {
    return getTunnelPathNodes(tunnel).some(item => item.status !== 1);
  };

  const getNodeBlockClassName = (offline: boolean): string => {
    return offline
      ? "p-2 bg-danger-100/90 rounded border border-danger-300"
      : "p-2 bg-default-50 dark:bg-default-100/50 rounded border border-default-200 dark:border-default-300";
  };

  const getNodeStatusChip = (offline: boolean) => ({
    color: offline ? 'danger' : 'success',
    text: offline ? '离线' : '在线'
  });

  const getLinkStatusText = (tunnel: Tunnel): string => {
    const pathNodes = getTunnelPathNodes(tunnel);
    const offlineCount = pathNodes.filter(item => item.status !== 1).length;
    if (offlineCount > 0) return `${offlineCount} 个节点离线`;
    return tunnel.type === 1 ? '入口节点正常' : `${pathNodes.length} 跳链路正常`;
  };

  // 获取状态显示
  const getStatusDisplay = (status: number) => {
    switch (status) {
      case 1:
        return { text: '启用', color: 'success' };
      case 0:
        return { text: '禁用', color: 'default' };
      default:
        return { text: '未知', color: 'warning' };
    }
  };

  // 获取类型显示
  const getTypeDisplay = (type: number) => {
    switch (type) {
      case 1:
        return { text: '端口转发', color: 'primary' };
      case 2:
        return { text: '隧道转发', color: 'secondary' };
      default:
        return { text: '未知', color: 'default' };
    }
  };

  // 获取流量计算显示
  const getFlowDisplay = (flow: number) => {
    switch (flow) {
      case 1:
        return '单向计算';
      case 2:
        return '双向计算';
      default:
        return '未知';
    }
  };

  const updateFormPathNode = (index: number, nodeId: number | null) => {
    setForm(prev => {
      const nextPath = normalizeFormPath(prev.inNodeId, prev.nodePath, prev.outNodeId);
      nextPath[index] = nodeId;
      const nextInNodeId = index === 0 ? nodeId : nextPath[0];
      const nextOutNodeId = nextPath.length > 1 ? nextPath[nextPath.length - 1] : null;
      return {
        ...prev,
        inNodeId: nextInNodeId,
        outNodeId: nextOutNodeId,
        nodePath: nextPath
      };
    });
  };

  const addTransitNode = () => {
    setForm(prev => {
      const nextPath = normalizeFormPath(prev.inNodeId, prev.nodePath, prev.outNodeId);
      nextPath.splice(Math.max(1, nextPath.length - 1), 0, null);
      return { ...prev, nodePath: nextPath };
    });
  };

  const removePathNode = (index: number) => {
    setForm(prev => {
      const nextPath = normalizeFormPath(prev.inNodeId, prev.nodePath, prev.outNodeId);
      if (index > 0 && index < nextPath.length && nextPath.length > 2) {
        nextPath.splice(index, 1);
      }
      return {
        ...prev,
        outNodeId: nextPath[nextPath.length - 1] ?? null,
        nodePath: nextPath
      };
    });
  };

  const renderNodeSelectItems = () => nodes.map((node) => (
    <SelectItem
      key={node.id}
      textValue={`${node.name} (${node.status === 1 ? '在线' : '离线'})`}
    >
      <div className="flex items-center justify-between">
        <span>{node.name}</span>
        <Chip
          color={node.status === 1 ? 'success' : 'danger'}
          variant="flat"
          size="sm"
        >
          {node.status === 1 ? '在线' : '离线'}
        </Chip>
      </div>
    </SelectItem>
  ));

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

  const compareCreatedTimeDesc = (a: Tunnel, b: Tunnel): number => {
    const createdTimeCompare = Number(b.createdTime || 0) - Number(a.createdTime || 0);
    if (createdTimeCompare !== 0) return createdTimeCompare;
    return (b.id || 0) - (a.id || 0);
  };

  const tunnelCardOrder = useCardOrder('tunnel-cards', tunnels.map(tunnel => tunnel.id));

  const offlineTunnels = tunnelCardOrder.sortItems(
    tunnels.filter(isTunnelNodeOffline).sort(compareCreatedTimeDesc),
    tunnel => tunnel.id
  );
  const onlineTunnels = tunnelCardOrder.sortItems(
    tunnels.filter(tunnel => !isTunnelNodeOffline(tunnel)).sort(compareCreatedTimeDesc),
    tunnel => tunnel.id
  );

  const groupTunnelsByLevel = (tunnelList: Tunnel[]): TunnelLevelGroup[] => {
    const groupMap = new Map<string, TunnelLevelGroup>();

    tunnelList.forEach(tunnel => {
      const isPortForward = tunnel.type === 1;
      const level = isPortForward ? 1 : Math.max(getTunnelNodePath(tunnel).length, 2);
      const key = isPortForward ? 'port-forward' : `tunnel-level-${level}`;

      if (!groupMap.has(key)) {
        groupMap.set(key, {
          key,
          title: isPortForward ? '端口转发' : `${level}级隧道`,
          description: isPortForward
            ? '入口与出口位于同一节点'
            : `由 ${level} 个节点组成的隧道链路`,
          level,
          tunnels: []
        });
      }

      groupMap.get(key)!.tunnels.push(tunnel);
    });

    return Array.from(groupMap.values()).sort((a, b) => {
      if (a.key === 'port-forward') return -1;
      if (b.key === 'port-forward') return 1;
      return a.level - b.level;
    });
  };

  const renderTunnelGrid = (tunnelList: Tunnel[]) => (
    <SortableCardGrid
      items={tunnelList}
      getId={tunnel => tunnel.id}
      onMove={tunnelCardOrder.moveCard}
      className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 items-start gap-4"
      renderItem={(tunnel, dragHandle) => {
        const statusDisplay = getStatusDisplay(tunnel.status);
        const typeDisplay = getTypeDisplay(tunnel.type);
        const tunnelOffline = isTunnelNodeOffline(tunnel);
        const pathNodes = getTunnelPathNodes(tunnel);
        const ownerBadge = getTunnelOwnerBadge(tunnel);

        return (
          <Card
            className={tunnelOffline
              ? "offline-card w-full self-start shadow-sm border border-danger-300 overflow-hidden hover:shadow-md transition-shadow duration-200"
              : "w-full self-start shadow-sm border border-divider overflow-hidden hover:shadow-md transition-shadow duration-200"}
          >
            {tunnelOffline && <div className="offline-accent h-0.5 bg-danger" />}
            <CardHeader className="pb-2">
              <div className="flex justify-between items-start w-full">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1.5 min-w-0">
                    <h3 className="font-semibold text-foreground truncate text-sm flex-1 min-w-0">{tunnel.name}</h3>
                    <Chip
                      size="sm"
                      variant="flat"
                      color={ownerBadge.color}
                      className="text-[10px] h-5 flex-shrink-0 max-w-[116px]"
                      title={ownerBadge.title}
                    >
                      <span className="truncate max-w-[98px]">{ownerBadge.label}</span>
                    </Chip>
                  </div>
                  <p className={tunnelOffline ? "text-xs text-danger-600 dark:text-danger-300 truncate mt-0.5" : "text-xs text-default-500 truncate mt-0.5"}>
                    {getLinkStatusText(tunnel)}
                  </p>
                  <div className="flex items-center gap-1.5 mt-1">
                    {tunnelOffline && (
                      <Chip
                        color="danger"
                        variant="flat"
                        size="sm"
                        className="text-xs offline-status-chip"
                      >
                        链路异常
                      </Chip>
                    )}
                    <Chip
                      color={typeDisplay.color as any}
                      variant="flat"
                      size="sm"
                      className="text-xs"
                    >
                      {typeDisplay.text}
                    </Chip>
                    <Chip
                      color={statusDisplay.color as any}
                      variant="flat"
                      size="sm"
                      className="text-xs"
                    >
                      {statusDisplay.text}
                    </Chip>
                  </div>
                </div>
                <div className="ml-2 flex-shrink-0">{dragHandle}</div>
              </div>
            </CardHeader>

            <CardBody className="pt-0 pb-3">
              <div className="space-y-2">
                {/* 流程展示 */}
                <div className="space-y-1.5">
                  {pathNodes.map((pathNode, index) => {
                    const nodeOffline = pathNode.status !== 1;
                    const nodeChip = getNodeStatusChip(nodeOffline);
                    return (
                      <div key={`${tunnel.id}-${pathNode.nodeId}-${index}`} className="space-y-1.5">
                        <div className={getNodeBlockClassName(nodeOffline)}>
                          <div className="flex items-center justify-between mb-1">
                            <span className={nodeOffline ? "text-xs font-medium text-danger-700 dark:text-danger-300" : "text-xs font-medium text-default-600"}>
                              {pathNode.label}
                            </span>
                            <Chip color={nodeChip.color as any} variant="flat" size="sm" className={nodeOffline ? "text-xs offline-status-chip" : "text-xs"}>
                              {nodeChip.text}
                            </Chip>
                          </div>
                          <code className={nodeOffline ? "text-xs font-mono text-danger-800 dark:text-danger-200 block truncate" : "text-xs font-mono text-foreground block truncate"}>
                            {pathNode.name}
                          </code>
                          <code className={nodeOffline ? "text-xs font-mono text-danger-600 dark:text-danger-300 block truncate" : "text-xs font-mono text-default-500 block truncate"}>
                            {getDisplayIp(pathNode.ip)}
                          </code>
                        </div>
                        {index < pathNodes.length - 1 && (
                          <div className="text-center py-0.5">
                            <svg className="w-3 h-3 text-default-400 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 14l-7 7m0 0l-7-7m7 7V3" />
                            </svg>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>

                {/* 配置信息 */}
                <div className={tunnelOffline ? "flex justify-between items-center pt-2 border-t border-danger-200 dark:border-danger-800" : "flex justify-between items-center pt-2 border-t border-divider"}>
                  <div className="text-left">
                    <div className="text-xs font-medium text-foreground">
                      {getFlowDisplay(tunnel.flow)}
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-xs font-medium text-foreground">
                      {tunnel.trafficRatio}x
                    </div>
                  </div>
                </div>

              </div>

              <div className="flex gap-1.5 mt-3">
                {tunnel.editable !== false && <Button
                  size="sm"
                  variant="flat"
                  color="primary"
                  onPress={() => handleEdit(tunnel)}
                  className="flex-1 min-h-8"
                  startContent={
                    <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                      <path d="M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z" />
                    </svg>
                  }
                >
                  编辑
                </Button>}
                <Button
                  size="sm"
                  variant="flat"
                  color="warning"
                  onPress={() => handleDiagnose(tunnel)}
                  className="flex-1 min-h-8"
                  startContent={
                    <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                    </svg>
                  }
                >
                  诊断
                </Button>
                {tunnel.deletable !== false && <Button
                  size="sm"
                  variant="flat"
                  color="danger"
                  onPress={() => handleDelete(tunnel)}
                  className="flex-1 min-h-8"
                  startContent={
                    <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M9 2a1 1 0 000 2h2a1 1 0 100-2H9z" clipRule="evenodd" />
                      <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8 7a1 1 0 012 0v4a1 1 0 11-2 0V7zM12 7a1 1 0 012 0v4a1 1 0 11-2 0V7z" clipRule="evenodd" />
                    </svg>
                  }
                >
                  删除
                </Button>}
                {tunnel.accessType === 'shared' && <div className="flex-1 flex items-center justify-center text-xs text-default-500 border border-divider rounded-medium min-h-8">只读共享隧道</div>}
              </div>
            </CardBody>
          </Card>
        );
      }}
    />
  );

  const renderTunnelLevelGroups = (tunnelList: Tunnel[], offline: boolean) => (
    <div className="space-y-6">
      {groupTunnelsByLevel(tunnelList).map(group => (
        <section key={group.key} className="space-y-3">
          <div className="flex items-center justify-between gap-3">
            <div className="min-w-0">
              <h3 className={offline
                ? "text-sm font-semibold text-danger-700 dark:text-danger-300"
                : "text-sm font-semibold text-foreground"}
              >
                {group.title}
              </h3>
              <p className={offline
                ? "text-xs text-danger-600 dark:text-danger-300"
                : "text-xs text-default-500"}
              >
                {group.description}
              </p>
            </div>
            <Chip
              color={offline ? 'danger' : (group.key === 'port-forward' ? 'primary' : 'secondary')}
              variant="flat"
              size="sm"
              className="text-xs flex-shrink-0"
            >
              {group.tunnels.length} 条
            </Chip>
          </div>
          {renderTunnelGrid(group.tunnels)}
        </section>
      ))}
    </div>
  );

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

  return (

      <div className="px-3 lg:px-6 py-8">
        {/* 页面头部 */}
        <div className="flex items-center justify-between mb-6">
        <div className="flex-1">
        </div>

        <Button
              size="sm"
              variant="flat"
              color="primary"
              onPress={handleAdd}

            >
              新增
            </Button>

        </div>

        {/* 隧道卡片网格 */}
        {tunnels.length > 0 ? (
          <div className="space-y-8">
            {onlineTunnels.length > 0 && (
              <section className="space-y-3">
                <div className="flex items-center justify-between border-b border-divider pb-2">
                  <div>
                    <h2 className="text-sm font-semibold text-foreground">链路正常</h2>
                    <p className="text-xs text-default-500">路径中的所有节点均正常</p>
                  </div>
                  <Chip color="success" variant="flat" size="sm" className="text-xs">
                    {onlineTunnels.length} 条
                  </Chip>
                </div>
                {renderTunnelLevelGroups(onlineTunnels, false)}
              </section>
            )}

            {offlineTunnels.length > 0 && (
              <section className="space-y-3">
                <div className="offline-section-divider flex items-center justify-between border-b border-danger-200 pb-2">
                  <div>
                    <h2 className="offline-section-heading text-sm font-semibold text-danger-700">链路异常</h2>
                    <p className="offline-section-copy text-xs text-danger-600">路径中存在离线或连接异常节点</p>
                  </div>
                  <Chip color="danger" variant="flat" size="sm" className="text-xs offline-status-chip">
                    {offlineTunnels.length} 条
                  </Chip>
                </div>
                {renderTunnelLevelGroups(offlineTunnels, true)}
              </section>
            )}
          </div>
        ) : (
          /* 空状态 */
          <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
            <CardBody className="text-center py-16">
              <div className="flex flex-col items-center gap-4">
                <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                  <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8.111 16.404a5.5 5.5 0 017.778 0M12 20h.01m-7.08-7.071c3.904-3.905 10.236-3.905 14.141 0M1.394 9.393c5.857-5.857 15.355-5.857 21.213 0" />
                  </svg>
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-foreground">暂无隧道配置</h3>
                  <p className="text-default-500 text-sm mt-1">还没有创建任何隧道配置，点击上方按钮开始创建</p>
                </div>
              </div>
            </CardBody>
          </Card>
        )}

        {/* 新增/编辑模态框 */}
        <Modal
          isOpen={modalOpen}
          onOpenChange={setModalOpen}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">
                    {isEdit ? '编辑隧道' : '新增隧道'}
                  </h2>
                  <p className="text-small text-default-500">
                    {isEdit ? '修改现有隧道配置的信息' : '创建新的隧道配置'}
                  </p>
                </ModalHeader>
                <ModalBody>
                  <div className="space-y-4">
                    <Input
                      label="隧道名称"
                      placeholder="请输入隧道名称"
                      value={form.name}
                      onChange={(e) => setForm(prev => ({ ...prev, name: e.target.value }))}
                      isInvalid={!!errors.name}
                      errorMessage={errors.name}
                      variant="bordered"
                    />

                    <Select
                      label="隧道类型"
                      placeholder="请选择隧道类型"
                      selectedKeys={[form.type.toString()]}
                      onSelectionChange={(keys) => {
                        const selectedKey = Array.from(keys)[0] as string;
                        if (selectedKey) {
                          handleTypeChange(parseInt(selectedKey));
                        }
                      }}
                      isInvalid={!!errors.type}
                      errorMessage={errors.type}
                      variant="bordered"
                      isDisabled={isEdit}
                    >
                      <SelectItem key="1">端口转发</SelectItem>
                      <SelectItem key="2">隧道转发</SelectItem>
                    </Select>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <Select
                        label="流量计算"
                        placeholder="请选择流量计算方式"
                        selectedKeys={[form.flow.toString()]}
                        onSelectionChange={(keys) => {
                          const selectedKey = Array.from(keys)[0] as string;
                          if (selectedKey) {
                            setForm(prev => ({ ...prev, flow: parseInt(selectedKey) }));
                          }
                        }}
                        isInvalid={!!errors.flow}
                        errorMessage={errors.flow}
                        variant="bordered"
                      >
                        <SelectItem key="1">单向计算（仅上传）</SelectItem>
                        <SelectItem key="2">双向计算（上传+下载）</SelectItem>
                      </Select>

                      <Input
                        label="流量倍率"
                        placeholder="请输入流量倍率"
                        type="number"
                        value={form.trafficRatio.toString()}
                        onChange={(e) => setForm(prev => ({
                          ...prev,
                          trafficRatio: parseFloat(e.target.value) || 0
                        }))}
                        isInvalid={!!errors.trafficRatio}
                        errorMessage={errors.trafficRatio}
                        variant="bordered"
                        endContent={
                          <div className="pointer-events-none flex items-center">
                            <span className="text-default-400 text-small">x</span>
                          </div>
                        }
                      />
                    </div>

                    <Divider />
                    <h3 className="text-lg font-semibold">入口配置</h3>

                    <Select
                      label="入口节点"
                      placeholder="请选择入口节点"
                      selectedKeys={form.inNodeId ? [form.inNodeId.toString()] : []}
                      onSelectionChange={(keys) => {
                        const selectedKey = Array.from(keys)[0] as string;
                        if (selectedKey) {
                          const nodeId = parseInt(selectedKey);
                          setForm(prev => {
                            const nextPath = normalizeFormPath(nodeId, prev.nodePath, prev.outNodeId);
                            return { ...prev, inNodeId: nodeId, nodePath: nextPath };
                          });
                        }
                      }}
                      isInvalid={!!errors.inNodeId}
                      errorMessage={errors.inNodeId}
                      variant="bordered"
                      isDisabled={isEdit}
                    >
                      {renderNodeSelectItems()}
                    </Select>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <Input
                        label="TCP监听地址"
                        placeholder="请输入TCP监听地址"
                        value={form.tcpListenAddr}
                        onChange={(e) => setForm(prev => ({ ...prev, tcpListenAddr: e.target.value }))}
                        isInvalid={!!errors.tcpListenAddr}
                        errorMessage={errors.tcpListenAddr}
                        variant="bordered"
                        startContent={
                          <div className="pointer-events-none flex items-center">
                            <span className="text-default-400 text-small">TCP</span>
                          </div>
                        }
                      />

                      <Input
                        label="UDP监听地址"
                        placeholder="请输入UDP监听地址"
                        value={form.udpListenAddr}
                        onChange={(e) => setForm(prev => ({ ...prev, udpListenAddr: e.target.value }))}
                        isInvalid={!!errors.udpListenAddr}
                        errorMessage={errors.udpListenAddr}
                        variant="bordered"
                        startContent={
                          <div className="pointer-events-none flex items-center">
                            <span className="text-default-400 text-small">UDP</span>
                          </div>
                        }
                      />
                    </div>

                    {/* 隧道转发时显示出口网卡配置 */}
                    {form.type === 2 && (
                      <Input
                        label="出口网卡名或IP"
                        placeholder="请输入出口网卡名或IP"
                        value={form.interfaceName}
                        onChange={(e) => setForm(prev => ({ ...prev, interfaceName: e.target.value }))}
                        isInvalid={!!errors.interfaceName}
                        errorMessage={errors.interfaceName}
                        variant="bordered"
                      />
                    )}

                    {/* 隧道转发时显示出口配置 */}
                    {form.type === 2 && (
                      <>
                        <Divider />
                        <h3 className="text-lg font-semibold">出口配置</h3>

                        <Select
                          label="协议类型"
                          placeholder="请选择协议类型"
                          selectedKeys={[form.protocol]}
                          onSelectionChange={(keys) => {
                            const selectedKey = Array.from(keys)[0] as string;
                            if (selectedKey) {
                              setForm(prev => ({ ...prev, protocol: selectedKey }));
                            }
                          }}
                          isInvalid={!!errors.protocol}
                          errorMessage={errors.protocol}
                          variant="bordered"
                        >
                          <SelectItem key="tls">TLS</SelectItem>
                          <SelectItem key="wss">WSS</SelectItem>
                          <SelectItem key="tcp">TCP</SelectItem>
                          <SelectItem key="mtls">MTLS</SelectItem>
                          <SelectItem key="mwss">MWSS</SelectItem>
                          <SelectItem key="mtcp">MTCP</SelectItem>
                        </Select>

                        <div className="space-y-3">
                          <div className="flex items-center justify-between gap-3">
                            <h4 className="text-sm font-semibold text-foreground">节点路径</h4>
                            <Button
                              size="sm"
                              variant="flat"
                              color="primary"
                              onPress={addTransitNode}
                              isDisabled={isEdit}
                            >
                              添加中转节点
                            </Button>
                          </div>
                          {normalizeFormPath(form.inNodeId, form.nodePath, form.outNodeId).slice(1).map((nodeId, offset, tail) => {
                            const pathIndex = offset + 1;
                            const isLast = offset === tail.length - 1;
                            return (
                              <div key={`path-${pathIndex}`} className="grid grid-cols-[1fr_auto] gap-2 items-start">
                                <Select
                                  label={isLast ? "出口节点" : `中转节点 ${pathIndex}`}
                                  placeholder={isLast ? "请选择出口节点" : "请选择中转节点"}
                                  selectedKeys={nodeId ? [nodeId.toString()] : []}
                                  onSelectionChange={(keys) => {
                                    const selectedKey = Array.from(keys)[0] as string;
                                    updateFormPathNode(pathIndex, selectedKey ? parseInt(selectedKey) : null);
                                  }}
                                  isInvalid={!!errors.nodePath}
                                  errorMessage={isLast ? errors.nodePath : undefined}
                                  variant="bordered"
                                  isDisabled={isEdit}
                                >
                                  {renderNodeSelectItems()}
                                </Select>
                                <Button
                                  size="sm"
                                  variant="flat"
                                  color="danger"
                                  className="mt-6 min-w-10 px-0"
                                  onPress={() => removePathNode(pathIndex)}
                                  isDisabled={isEdit || normalizeFormPath(form.inNodeId, form.nodePath, form.outNodeId).length <= 2}
                                >
                                  ×
                                </Button>
                              </div>
                            );
                          })}
                        </div>
                      </>
                    )}

                    <Alert
                        color="primary"
                        variant="flat"
                        title="TCP,UDP监听地址"
                        description="V6或者双栈填写[::],V4填写0.0.0.0。不懂的就去看文档网站内的说明"
                        className="mt-4"
                      />
                      <Alert
                        color="primary"
                        variant="flat"
                        title="出口网卡名或IP"
                        description="用于多IP服务器指定使用那个IP和出口服务器通讯，不懂的默认为空就行"
                        className="mt-4"
                      />
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
                    {submitLoading ? (isEdit ? '更新中...' : '创建中...') : (isEdit ? '更新' : '创建')}
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
                  <h2 className="text-xl font-bold">确认删除</h2>
                </ModalHeader>
                <ModalBody>
                  <p>确定要删除隧道 <strong>"{tunnelToDelete?.name}"</strong> 吗？</p>
                  <p className="text-small text-default-500">
                    此操作会同时删除使用该隧道的转发、用户隧道授权和限速规则，且不可恢复。
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
                    {deleteLoading ? '删除中...' : '确认删除'}
                  </Button>
                </ModalFooter>
              </>
            )}
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
                  <h2 className="text-xl font-bold">隧道诊断结果</h2>
                  {currentDiagnosisTunnel && (
                    <div className="flex items-center gap-2">
                      <span className="text-small text-default-500">{currentDiagnosisTunnel.name}</span>
                      <Chip
                        color={currentDiagnosisTunnel.type === 1 ? 'primary' : 'secondary'}
                        variant="flat"
                        size="sm"
                      >
                        {currentDiagnosisTunnel.type === 1 ? '端口转发' : '隧道转发'}
                      </Chip>
                    </div>
                  )}
                </ModalHeader>
                <ModalBody>
                  {diagnosisLoading ? (
                    <div className="flex items-center justify-center py-16">
                      <div className="flex items-center gap-3">
                        <Spinner size="sm" />
                        <span className="text-default-600">正在诊断...</span>
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
                                  <div className="text-small text-default-500">隧道总延迟</div>
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
                                <div className="flex items-center gap-3">
                                  <div className={`w-8 h-8 rounded-full flex items-center justify-center ${
                                    result.success ? 'bg-success text-white' : 'bg-danger text-white'
                                  }`}>
                                    {result.success ? '✓' : '✗'}
                                  </div>
                                  <div>
                                    <h4 className="font-semibold">{result.description}</h4>
                                    <p className="text-small text-default-500">{result.nodeName}</p>
                                  </div>
                                </div>
                                <Chip
                                  color={result.success ? 'success' : 'danger'}
                                  variant="flat"
                                >
                                  {result.success ? '成功' : '失败'}
                                </Chip>
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
                                  <div className="text-small text-default-500">
                                    目标地址: <code className="font-mono">{result.targetIp}{result.targetPort ? ':' + result.targetPort : ''}</code>
                                  </div>
                                </div>
                              ) : (
                                <div className="space-y-2">
                                  <div className="text-small text-default-500">
                                    目标地址: <code className="font-mono">{result.targetIp}{result.targetPort ? ':' + result.targetPort : ''}</code>
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
                  {currentDiagnosisTunnel && (
                    <Button
                      color="primary"
                      onPress={() => handleDiagnose(currentDiagnosisTunnel)}
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
