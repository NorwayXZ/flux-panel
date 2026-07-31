import { useState, useEffect, useRef } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Textarea } from "@heroui/input";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Switch } from "@heroui/switch";
import { Spinner } from "@heroui/spinner";
import { Alert } from "@heroui/alert";
import { Progress } from "@heroui/progress";
import toast from 'react-hot-toast';
import axios from 'axios';
import { ClipboardCopy, Container, Globe2, Radar, RefreshCw, ServerCog, SquareTerminal } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { SortableCardGrid } from '@/components/sortable-card-grid';
import { useCardOrder } from '@/hooks/use-card-order';
import { isAdmin } from '@/utils/auth';

import { 
  createNode, 
  getNodeList, 
  updateNode, 
  deleteNode,
  getNodeInstallCommand,
  checkNodeStatus,
  getAgentUpgradeStatus,
  getManualAgentUpgradeCommand,
  startAgentUpgrade,
  startBatchAgentUpgrade,
  discoverNodeServices,
  type NodeDiscoveredService,
  type NodeServiceDiscoveryResult,
  type AgentUpgradeBatch,
  type AgentUpgradeStatusItem,
} from "@/api";

interface Node {
  id: number;
  name: string;
  ip: string;
  serverIp: string;
  portSta: number;
  portEnd: number;
  version?: string;
  http?: number; // 0 关 1 开
  tls?: number;  // 0 关 1 开
  socks?: number; // 0 关 1 开
  terminalEnabled?: boolean;
  status: number; // 1: 在线, 0: 离线
  ownerUserId?: number;
  ownerUserName?: string;
  ownerRoleId?: number;
  accessType?: 'admin' | 'owned' | 'shared';
  editable?: boolean;
  deletable?: boolean;
  portPoolGroupSize?: number;
  createdTime?: string | number;
  connectionStatus: 'online' | 'offline';
  systemInfo?: {
    cpuUsage: number;
    memoryUsage: number;
    uploadTraffic: number;
    downloadTraffic: number;
    uploadSpeed: number;
    downloadSpeed: number;
    uptime: number;
  } | null;
  copyLoading?: boolean;
}

interface NodeForm {
  id: number | null;
  name: string;
  ipString: string;
  serverIp: string;
  portSta: number;
  portEnd: number;
  http: number; // 0 关 1 开
  tls: number;  // 0 关 1 开
  socks: number; // 0 关 1 开
}

const getNodeOwnerBadge = (node: Node) => {
  const ownerName = node.ownerUserName || '未知用户';
  if (node.accessType === 'shared') {
    return { label: `共享 · ${ownerName}`, title: `管理员共享的节点，所有者：${ownerName}`, color: 'secondary' as const };
  }
  if (node.accessType === 'owned') {
    return { label: '我的', title: `当前用户创建的节点：${ownerName}`, color: 'primary' as const };
  }
  if (node.ownerRoleId === 1) {
    return { label: `用户 · ${ownerName}`, title: `普通用户创建，所有者：${ownerName}`, color: 'warning' as const };
  }
  if (node.ownerRoleId === 0) {
    return { label: '管理员', title: `管理员资源，所有者：${ownerName}`, color: 'default' as const };
  }
  return { label: `归属 · ${ownerName}`, title: `资源所有者：${ownerName}`, color: 'default' as const };
};

const activeUpgradeStates = new Set(['queued', 'bootstrapping', 'accepted', 'preflight', 'downloading', 'verified', 'restarting', 'installing']);
const upgradeStateLabels: Record<string, string> = {
  queued: '等待接收',
  bootstrapping: '启动助手',
  accepted: '准备升级',
  preflight: '升级预检',
  downloading: '下载中',
  verified: '校验完成',
  restarting: '重启中',
  installing: '安装中',
  success: '升级成功',
  rolled_back: '已回滚',
  failed: '升级失败',
  timeout: '等待超时',
};

const upgradeStateColor = (state?: string): 'default' | 'primary' | 'success' | 'warning' | 'danger' => {
  if (!state) return 'default';
  if (state === 'success') return 'success';
  if (state === 'failed' || state === 'rolled_back' || state === 'timeout') return 'danger';
  if (state === 'restarting' || state === 'installing') return 'warning';
  return 'primary';
};

export default function NodePage() {
  const navigate = useNavigate();
  const adminMode = isAdmin();
  const [nodeList, setNodeList] = useState<Node[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialogVisible, setDialogVisible] = useState(false);
  const [dialogTitle, setDialogTitle] = useState('');
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [statusChecking, setStatusChecking] = useState(false);
  const [nodeToDelete, setNodeToDelete] = useState<Node | null>(null);
  const [protocolDisabled, setProtocolDisabled] = useState(false);
  const [protocolDisabledReason, setProtocolDisabledReason] = useState('');
  const [upgradeItems, setUpgradeItems] = useState<Record<number, AgentUpgradeStatusItem>>({});
  const upgradeItemsRef = useRef<Record<number, AgentUpgradeStatusItem>>({});
  const [upgradeTargetVersion, setUpgradeTargetVersion] = useState('');
  const [upgradeBatchStatus, setUpgradeBatchStatus] = useState<AgentUpgradeBatch | null>(null);
  const [upgradeModalOpen, setUpgradeModalOpen] = useState(false);
  const [upgradeNode, setUpgradeNode] = useState<Node | null>(null);
  const [upgradeBatch, setUpgradeBatch] = useState(false);
  const [upgradeSubmitting, setUpgradeSubmitting] = useState(false);
  const [discoveryOpen, setDiscoveryOpen] = useState(false);
  const [discoveryNode, setDiscoveryNode] = useState<Node | null>(null);
  const [discoveryResult, setDiscoveryResult] = useState<NodeServiceDiscoveryResult | null>(null);
  const [discoveryLoading, setDiscoveryLoading] = useState(false);
  const [form, setForm] = useState<NodeForm>({
    id: null,
    name: '',
    ipString: '',
    serverIp: '',
    portSta: 1000,
    portEnd: 65535,
    http: 0,
    tls: 0,
    socks: 0
  });
  const [errors, setErrors] = useState<Record<string, string>>({});

  const openServiceDiscovery = (node: Node) => {
    setDiscoveryNode(node);
    setDiscoveryResult(null);
    setDiscoveryOpen(true);
  };

  const runServiceDiscovery = async () => {
    if (!discoveryNode) return;
    setDiscoveryLoading(true);
    try {
      const response = await discoverNodeServices(discoveryNode.id);
      if (response.code !== 0) return toast.error(response.msg || '节点服务发现失败');
      setDiscoveryResult(response.data);
      if ((response.data.services || []).length === 0) toast('没有发现正在监听的 TCP 服务');
    } catch {
      toast.error('节点服务发现失败');
    } finally {
      setDiscoveryLoading(false);
    }
  };

  const publishDiscoveredService = (service: NodeDiscoveredService) => {
    if (!discoveryNode || !['http', 'https'].includes(service.protocol)) return;
    const params = new URLSearchParams({
      publish: 'node-service',
      backendNodeId: String(discoveryNode.id),
      backendHost: service.host,
      backendPort: String(service.port),
      backendScheme: service.protocol,
      serviceName: service.serviceName || service.title || `${service.protocol.toUpperCase()} ${service.port}`,
    });
    setDiscoveryOpen(false);
    navigate(`/service-publishing?${params.toString()}`);
  };
  
  // 安装命令相关状态
  const [installCommandModal, setInstallCommandModal] = useState(false);
  const [installCommand, setInstallCommand] = useState('');
  const [currentNodeName, setCurrentNodeName] = useState('');
  const [installCommandAction, setInstallCommandAction] = useState<'install' | 'upgrade'>('install');
  
  const websocketRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<NodeJS.Timeout | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const maxReconnectAttempts = 5;

  useEffect(() => {
    loadNodes();
    initWebSocket();
    
    return () => {
      closeWebSocket();
    };
  }, []);

  useEffect(() => {
    if (!adminMode) return;
    loadUpgradeStatus();
    const timer = window.setInterval(loadUpgradeStatus, 3000);
    return () => window.clearInterval(timer);
  }, [adminMode]);

  async function loadUpgradeStatus() {
    try {
      const result = await getAgentUpgradeStatus();
      if (result.code !== 0) return;
      setUpgradeTargetVersion(result.data.targetVersion);
      setUpgradeBatchStatus(result.data.batch || null);
      const nextItems = Object.fromEntries((result.data.items || []).map(item => [item.nodeId, item]));
      (result.data.items || []).forEach(item => {
        const previousState = upgradeItemsRef.current[item.nodeId]?.task?.state;
        if (activeUpgradeStates.has(previousState || '') && item.task?.state === 'success') {
          toast.success(`${item.nodeName} Agent 已升级到 ${item.currentVersion || item.targetVersion}`);
        }
      });
      upgradeItemsRef.current = nextItems;
      setUpgradeItems(nextItems);
    } catch {
      // 节点列表仍可独立使用，轮询将在下一周期重试。
    }
  }

  // 加载节点列表
  const loadNodes = async () => {
    setLoading(true);
    try {
      const res = await getNodeList();
      if (res.code === 0) {
        setNodeList(res.data.map((node: any) => ({
          ...node,
          connectionStatus: node.status === 1 ? 'online' : 'offline',
          systemInfo: null,
          copyLoading: false
        })));
      } else {
        toast.error(res.msg || '加载节点列表失败');
      }
    } catch (error) {
      toast.error('网络错误，请重试');
    } finally {
      setLoading(false);
    }
  };

  // 初始化WebSocket连接
  const initWebSocket = () => {
    if (websocketRef.current && 
        (websocketRef.current.readyState === WebSocket.OPEN || 
         websocketRef.current.readyState === WebSocket.CONNECTING)) {
      return;
    }
    
    if (websocketRef.current) {
      closeWebSocket();
    }
    
    // 构建WebSocket URL，使用axios的baseURL
    const baseUrl = axios.defaults.baseURL || (import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/');
    const wsUrl = baseUrl.replace(/^http/, 'ws').replace(/\/api\/v1\/$/, '') + `/system-info?type=0&secret=${localStorage.getItem('token')}`;
    
    try {
      websocketRef.current = new WebSocket(wsUrl);
      
      websocketRef.current.onopen = () => {
        reconnectAttemptsRef.current = 0;
      };
      
      websocketRef.current.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          handleWebSocketMessage(data);
        } catch (error) {
          // 解析失败时不输出错误信息
        }
      };
      
      websocketRef.current.onerror = () => {
        // WebSocket错误时不输出错误信息
      };
      
      websocketRef.current.onclose = () => {
        websocketRef.current = null;
        attemptReconnect();
      };
    } catch (error) {
      attemptReconnect();
    }
  };

  // 处理WebSocket消息
  const handleWebSocketMessage = (data: any) => {
    const { id, type, data: messageData } = data;
    
    if (type === 'status') {
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          return {
            ...node,
            status: messageData === 1 ? 1 : 0,
            connectionStatus: messageData === 1 ? 'online' : 'offline',
            systemInfo: messageData === 0 ? null : node.systemInfo
          };
        }
        return node;
      }));
    } else if (type === 'info') {
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          try {
            let systemInfo;
            if (typeof messageData === 'string') {
              systemInfo = JSON.parse(messageData);
            } else {
              systemInfo = messageData;
            }
            
            const currentUpload = parseInt(systemInfo.bytes_transmitted) || 0;
            const currentDownload = parseInt(systemInfo.bytes_received) || 0;
            const currentUptime = parseInt(systemInfo.uptime) || 0;
            
            let uploadSpeed = 0;
            let downloadSpeed = 0;
            
            if (node.systemInfo && node.systemInfo.uptime) {
              const timeDiff = currentUptime - node.systemInfo.uptime;
              
              if (timeDiff > 0 && timeDiff <= 10) {
                const lastUpload = node.systemInfo.uploadTraffic || 0;
                const lastDownload = node.systemInfo.downloadTraffic || 0;
                
                const uploadDiff = currentUpload - lastUpload;
                const downloadDiff = currentDownload - lastDownload;
                
                const uploadReset = currentUpload < lastUpload;
                const downloadReset = currentDownload < lastDownload;
                
                if (!uploadReset && uploadDiff >= 0) {
                  uploadSpeed = uploadDiff / timeDiff;
                }
                
                if (!downloadReset && downloadDiff >= 0) {
                  downloadSpeed = downloadDiff / timeDiff;
                }
              }
            }
            
            return {
              ...node,
              status: 1,
              connectionStatus: 'online',
              systemInfo: {
                cpuUsage: parseFloat(systemInfo.cpu_usage) || 0,
                memoryUsage: parseFloat(systemInfo.memory_usage) || 0,
                uploadTraffic: currentUpload,
                downloadTraffic: currentDownload,
                uploadSpeed: uploadSpeed,
                downloadSpeed: downloadSpeed,
                uptime: currentUptime
              }
            };
          } catch (error) {
            return node;
          }
        }
        return node;
      }));
    }
  };

  // 尝试重新连接
  const attemptReconnect = () => {
    if (reconnectAttemptsRef.current < maxReconnectAttempts) {
      reconnectAttemptsRef.current++;
      
      reconnectTimerRef.current = setTimeout(() => {
        initWebSocket();
      }, 3000 * reconnectAttemptsRef.current);
    }
  };

  // 关闭WebSocket连接
  const closeWebSocket = () => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    
    reconnectAttemptsRef.current = 0;
    
    if (websocketRef.current) {
      websocketRef.current.onopen = null;
      websocketRef.current.onmessage = null;
      websocketRef.current.onerror = null;
      websocketRef.current.onclose = null;
      
      if (websocketRef.current.readyState === WebSocket.OPEN || 
          websocketRef.current.readyState === WebSocket.CONNECTING) {
        websocketRef.current.close();
      }
      
      websocketRef.current = null;
    }
    
    setNodeList(prev => prev.map(node => ({
      ...node,
      connectionStatus: 'offline',
      systemInfo: null
    })));
  };


  
  // 格式化速度
  const formatSpeed = (bytesPerSecond: number): string => {
    if (bytesPerSecond === 0) return '0 B/s';
    
    const k = 1024;
    const sizes = ['B/s', 'KB/s', 'MB/s', 'GB/s', 'TB/s'];
    const i = Math.floor(Math.log(bytesPerSecond) / Math.log(k));
    
    return parseFloat((bytesPerSecond / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  // 格式化开机时间
  const formatUptime = (seconds: number): string => {
    if (seconds === 0) return '-';
    
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    
    if (days > 0) {
      return `${days}天${hours}小时`;
    } else if (hours > 0) {
      return `${hours}小时${minutes}分钟`;
    } else {
      return `${minutes}分钟`;
    }
  };

  // 格式化流量
  const formatTraffic = (bytes: number): string => {
    if (bytes === 0) return '0 B';
    
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  // 获取进度条颜色
  const getProgressColor = (value: number, offline = false): "default" | "primary" | "secondary" | "success" | "warning" | "danger" => {
    if (offline) return "default";
    if (value <= 50) return "success";
    if (value <= 80) return "warning";
    return "danger";
  };

  // 验证IP地址格式
  const validateIp = (ip: string): boolean => {
    if (!ip || !ip.trim()) return false;
    
    const trimmedIp = ip.trim();
    
    // IPv4格式验证
    const ipv4Regex = /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/;
    
    // IPv6格式验证
    const ipv6Regex = /^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$/;
    
    if (ipv4Regex.test(trimmedIp) || ipv6Regex.test(trimmedIp) || trimmedIp === 'localhost') {
      return true;
    }
    
    // 验证域名格式
    if (/^\d+$/.test(trimmedIp)) return false;
    
    const domainRegex = /^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)+$/;
    const singleLabelDomain = /^[a-zA-Z][a-zA-Z0-9\-]{0,62}$/;
    
    return domainRegex.test(trimmedIp) || singleLabelDomain.test(trimmedIp);
  };

  // 表单验证
  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};
    
    if (!form.name.trim()) {
      newErrors.name = '请输入节点名称';
    } else if (form.name.trim().length < 2) {
      newErrors.name = '节点名称长度至少2位';
    } else if (form.name.trim().length > 50) {
      newErrors.name = '节点名称长度不能超过50位';
    }
    
    if (!form.ipString.trim()) {
      newErrors.ipString = '请输入入口IP地址';
    } else {
      const ips = form.ipString.split('\n').map(ip => ip.trim()).filter(ip => ip);
      if (ips.length === 0) {
        newErrors.ipString = '请输入至少一个有效IP地址';
      } else {
        for (let i = 0; i < ips.length; i++) {
          if (!validateIp(ips[i])) {
            newErrors.ipString = `第${i + 1}行IP地址格式错误: ${ips[i]}`;
            break;
          }
        }
      }
    }
    
    if (!form.serverIp.trim()) {
      newErrors.serverIp = '请输入服务器IP地址';
    } else if (!validateIp(form.serverIp.trim())) {
      newErrors.serverIp = '请输入有效的IPv4、IPv6地址或域名';
    }
    
    if (!form.portSta || form.portSta < 1 || form.portSta > 65535) {
      newErrors.portSta = '端口范围必须在1-65535之间';
    }
    
    if (!form.portEnd || form.portEnd < 1 || form.portEnd > 65535) {
      newErrors.portEnd = '端口范围必须在1-65535之间';
    } else if (form.portEnd < form.portSta) {
      newErrors.portEnd = '结束端口不能小于起始端口';
    }
    
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 新增节点
  const handleAdd = () => {
    setDialogTitle('新增节点');
    setIsEdit(false);
    setDialogVisible(true);
    resetForm();
    setProtocolDisabled(true);
    setProtocolDisabledReason('节点未在线，等待节点上线后再设置');
  };

  // 编辑节点
  const handleEdit = (node: Node) => {
    setDialogTitle('编辑节点');
    setIsEdit(true);
    setForm({
      id: node.id,
      name: node.name,
      ipString: node.ip ? node.ip.split(',').map(ip => ip.trim()).join('\n') : '',
      serverIp: node.serverIp || '',
      portSta: node.portSta,
      portEnd: node.portEnd,
      http: typeof node.http === 'number' ? node.http : 1,
      tls: typeof node.tls === 'number' ? node.tls : 1,
      socks: typeof node.socks === 'number' ? node.socks : 1
    });
    const offline = node.connectionStatus !== 'online';
    setProtocolDisabled(offline);
    setProtocolDisabledReason(offline ? '节点未在线，等待节点上线后再设置' : '');
    setDialogVisible(true);
  };

  // 删除节点
  const handleDelete = (node: Node) => {
    setNodeToDelete(node);
    setDeleteModalOpen(true);
  };

  const handleCheckNodeStatus = async () => {
    setStatusChecking(true);
    try {
      const res = await checkNodeStatus();
      if (res.code === 0 && Array.isArray(res.data)) {
        const statusMap = new Map<number, number>(
          res.data.map((item: any) => [Number(item.id), Number(item.status)])
        );
        setNodeList(prev => prev.map(node => {
          const status = statusMap.get(node.id);
          if (typeof status !== 'number') return node;

          return {
            ...node,
            status,
            connectionStatus: status === 1 ? 'online' : 'offline',
            systemInfo: status === 1 ? node.systemInfo : null
          };
        }));
        toast.success('节点状态已刷新');
      } else {
        toast.error(res.msg || '刷新节点状态失败');
      }
    } catch (error) {
      toast.error('刷新节点状态失败');
    } finally {
      setStatusChecking(false);
    }
  };

  const openNodeUpgrade = (node: Node) => {
    setUpgradeNode(node);
    setUpgradeBatch(false);
    setUpgradeModalOpen(true);
  };

  const openBatchUpgrade = () => {
    setUpgradeNode(null);
    setUpgradeBatch(true);
    setUpgradeModalOpen(true);
  };

  const confirmUpgrade = async () => {
    setUpgradeSubmitting(true);
    try {
      if (upgradeBatch) {
        const result = await startBatchAgentUpgrade();
        if (result.code !== 0) {
          toast.error(result.msg || '批量升级提交失败');
          return;
        }
        toast.success(`已启动 ${result.data.totalNodes} 台节点的分阶段升级，先试运行 1 台`);
      } else if (upgradeNode) {
        const result = await startAgentUpgrade(upgradeNode.id);
        if (result.code !== 0) {
          toast.error(result.msg || '升级任务提交失败');
          return;
        }
        toast.success(`${upgradeNode.name} 已开始升级`);
      }
      setUpgradeModalOpen(false);
      await loadUpgradeStatus();
    } catch {
      toast.error('升级任务提交失败');
    } finally {
      setUpgradeSubmitting(false);
    }
  };

  const confirmDelete = async () => {
    if (!nodeToDelete) return;
    
    setDeleteLoading(true);
    try {
      const res = await deleteNode(nodeToDelete.id);
      if (res.code === 0) {
        const summary = res.data || {};
        const tunnelCount = Number(summary.tunnelCount || 0);
        const forwardCount = Number(summary.forwardCount || 0);
        const userTunnelCount = Number(summary.userTunnelCount || 0);
        const speedLimitCount = Number(summary.speedLimitCount || 0);
        const domainRouteCount = Number(summary.domainRouteCount || 0);
        const cleanupParts = [];
        if (domainRouteCount > 0) cleanupParts.push(`${domainRouteCount} 个域名直达`);
        if (tunnelCount > 0) cleanupParts.push(`${tunnelCount} 个隧道`, `${forwardCount} 个转发`, `${userTunnelCount} 个授权`, `${speedLimitCount} 个限速规则`);
        const cleanupText = cleanupParts.length > 0 ? `，已清理 ${cleanupParts.join('、')}` : '';
        toast.success(`节点删除成功${cleanupText}`);
        setNodeList(prev => prev.filter(n => n.id !== nodeToDelete.id));
        setDeleteModalOpen(false);
        setNodeToDelete(null);
      } else {
        toast.error(res.msg || '删除失败');
      }
    } catch (error) {
      toast.error('网络错误，请重试');
    } finally {
      setDeleteLoading(false);
    }
  };

  // 复制安装命令
  const handleCopyInstallCommand = async (node: Node, action: 'install' | 'upgrade') => {
    const actionLabel = action === 'upgrade' ? '升级' : '安装';
    setNodeList(prev => prev.map(n => 
      n.id === node.id ? { ...n, copyLoading: true } : n
    ));
    
    try {
      const res = await getNodeInstallCommand(node.id);
      if (res.code === 0 && res.data) {
        try {
          await navigator.clipboard.writeText(res.data);
          toast.success(`${actionLabel}命令已复制到剪贴板`);
        } catch (copyError) {
          // 复制失败，显示安装命令模态框
          setInstallCommand(res.data);
          setCurrentNodeName(node.name);
          setInstallCommandAction(action);
          setInstallCommandModal(true);
        }
      } else {
        toast.error(res.msg || `获取${actionLabel}命令失败`);
      }
    } catch (error) {
      toast.error(`获取${actionLabel}命令失败`);
    } finally {
      setNodeList(prev => prev.map(n => 
        n.id === node.id ? { ...n, copyLoading: false } : n
      ));
    }
  };

  const handleOpenManualUpgrade = async (node: Node) => {
    setNodeList(prev => prev.map(item => item.id === node.id ? { ...item, copyLoading: true } : item));
    try {
      const res = await getManualAgentUpgradeCommand(node.id);
      if (res.code !== 0 || !res.data) {
        toast.error(res.msg || '获取手动升级命令失败');
        return;
      }
      setInstallCommand(res.data);
      setCurrentNodeName(node.name);
      setInstallCommandAction('upgrade');
      setInstallCommandModal(true);
    } catch {
      toast.error('获取手动升级命令失败');
    } finally {
      setNodeList(prev => prev.map(item => item.id === node.id ? { ...item, copyLoading: false } : item));
    }
  };

  // 手动复制安装命令
  const handleManualCopy = async () => {
    try {
      await navigator.clipboard.writeText(installCommand);
      toast.success(`${installCommandAction === 'upgrade' ? '升级' : '安装'}命令已复制到剪贴板`);
    } catch (error) {
      toast.error('复制失败，请手动选择文本复制');
    }
  };

  // 提交表单
  const handleSubmit = async () => {
    if (!validateForm()) return;
    
    setSubmitLoading(true);
    
    try {
      const ipString = form.ipString
        .split('\n')
        .map(ip => ip.trim())
        .filter(ip => ip)
        .join(',');
        
      const submitData = {
        ...form,
        ip: ipString
      };
      delete (submitData as any).ipString;
      
      const apiCall = isEdit ? updateNode : createNode;
      const data = isEdit ? submitData : { 
        name: form.name, 
        ip: ipString,
        serverIp: form.serverIp,
        portSta: form.portSta,
        portEnd: form.portEnd,
        http: form.http,
        tls: form.tls,
        socks: form.socks
      };
      
      const res = await apiCall(data);
      if (res.code === 0) {
        toast.success(isEdit ? '更新成功' : '创建成功');
        setDialogVisible(false);
        
        if (isEdit) {
          setNodeList(prev => prev.map(n => 
            n.id === form.id ? {
              ...n,
              name: form.name,
              ip: ipString,
              serverIp: form.serverIp,
              portSta: form.portSta,
              portEnd: form.portEnd,
              http: form.http,
              tls: form.tls,
              socks: form.socks
            } : n
          ));
        } else {
          loadNodes();
        }
      } else {
        toast.error(res.msg || (isEdit ? '更新失败' : '创建失败'));
      }
    } catch (error) {
      toast.error('网络错误，请重试');
    } finally {
      setSubmitLoading(false);
    }
  };

  // 重置表单
  const resetForm = () => {
    setForm({
      id: null,
      name: '',
      ipString: '',
      serverIp: '',
      portSta: 1000,
      portEnd: 65535,
      http: 0,
      tls: 0,
      socks: 0
    });
    setErrors({});
  };

  const compareCreatedTimeDesc = (a: Node, b: Node): number => {
    const createdTimeCompare = Number(b.createdTime || 0) - Number(a.createdTime || 0);
    if (createdTimeCompare !== 0) return createdTimeCompare;
    return (b.id || 0) - (a.id || 0);
  };

  const nodeCardOrder = useCardOrder('node-cards', nodeList.map(node => node.id));

  const offlineNodes = nodeCardOrder.sortItems(nodeList
    .filter(node => node.connectionStatus !== 'online')
    .sort(compareCreatedTimeDesc), node => node.id);
  const onlineNodes = nodeCardOrder.sortItems(nodeList
    .filter(node => node.connectionStatus === 'online')
    .sort(compareCreatedTimeDesc), node => node.id);

  const batchEligibleCount = Object.values(upgradeItems).filter(item =>
    item.online && !item.upToDate && item.mode !== 'manual' && !activeUpgradeStates.has(item.task?.state || '')
  ).length;

  const renderNodeGrid = (nodes: Node[]) => (
    <SortableCardGrid
      items={nodes}
      getId={node => node.id}
      onMove={nodeCardOrder.moveCard}
      renderItem={(node, dragHandle) => {
        const nodeOffline = node.connectionStatus !== 'online';
        const ownerBadge = getNodeOwnerBadge(node);
        const upgradeStatus = upgradeItems[node.id];
        const upgradeTask = upgradeStatus?.task;
        const upgradeActive = activeUpgradeStates.has(upgradeTask?.state || '');
        const upgradeTaskTargetsCurrent = upgradeTask?.targetVersion === upgradeTargetVersion;
        const upgradeRetry = upgradeTaskTargetsCurrent
          && ['failed', 'rolled_back', 'timeout'].includes(upgradeTask?.state || '');
        const showUpgradeTask = Boolean(
          upgradeTask
          && !upgradeStatus?.upToDate
          && (upgradeActive || upgradeRetry)
        );
        const manualUpgrade = upgradeStatus?.mode === 'manual';

        return (
          <Card
            className={nodeOffline
              ? "offline-card shadow-sm border border-rose-300/70 bg-rose-50/70 dark:border-rose-900/80 dark:bg-rose-950/35 hover:shadow-md transition-shadow duration-200 h-full"
              : "shadow-sm border border-divider hover:shadow-md transition-shadow duration-200 h-full"}
          >
            <CardHeader className="pb-2">
              <div className="flex justify-between items-start w-full">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1.5 min-w-0">
                    <h3 className={nodeOffline ? "font-semibold text-rose-800 dark:text-rose-200 truncate text-sm flex-1 min-w-0" : "font-semibold text-foreground truncate text-sm flex-1 min-w-0"}>{node.name}</h3>
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
                  <p className={nodeOffline ? "text-xs text-danger-600 dark:text-danger-300 truncate" : "text-xs text-default-500 truncate"}>{node.serverIp}</p>
                </div>
                <div className="flex items-center gap-1.5 ml-2">
                  {dragHandle}
                  <Chip
                    color={node.connectionStatus === 'online' ? 'success' : 'danger'}
                    variant="flat"
                    size="sm"
                    className={nodeOffline ? "text-xs offline-status-chip" : "text-xs"}
                  >
                    {node.connectionStatus === 'online' ? '在线' : '离线'}
                  </Chip>
                </div>
              </div>
            </CardHeader>

            <CardBody className="pt-0 pb-3">
              {/* 基础信息 */}
              <div className="space-y-2 mb-4">
                <div className="flex justify-between items-center text-sm min-w-0">
                  <span className={nodeOffline ? "text-danger-700 dark:text-danger-300 flex-shrink-0" : "text-default-600 flex-shrink-0"}>入口IP</span>
                  <div className="text-right text-xs min-w-0 flex-1 ml-2">
                    {node.ip ? (
                      node.ip.split(',').length > 1 ? (
                        <span className={nodeOffline ? "font-mono truncate block text-danger-800 dark:text-danger-200" : "font-mono truncate block"} title={node.ip.split(',')[0].trim()}>
                          {node.ip.split(',')[0].trim()} +{node.ip.split(',').length - 1}个
                        </span>
                      ) : (
                        <span className={nodeOffline ? "font-mono truncate block text-danger-800 dark:text-danger-200" : "font-mono truncate block"} title={node.ip.trim()}>
                          {node.ip.trim()}
                        </span>
                      )
                    ) : '-'}
                  </div>
                </div>
                <div className="flex justify-between text-sm">
                  <span className={nodeOffline ? "text-danger-700 dark:text-danger-300" : "text-default-600"}>端口</span>
                  <div className="flex items-center gap-1.5 min-w-0">
                    {(node.portPoolGroupSize || 1) > 1 && (
                      <Chip
                        size="sm"
                        variant="flat"
                        color="warning"
                        className="h-5 text-[10px] flex-shrink-0"
                        title={`相同服务器地址的 ${node.portPoolGroupSize} 个节点共用此端口池`}
                      >
                        共池x{node.portPoolGroupSize}
                      </Chip>
                    )}
                    <span className={nodeOffline ? "text-xs text-danger-800 dark:text-danger-200" : "text-xs"}>{node.portSta}-{node.portEnd}</span>
                  </div>
                </div>
                <div className="flex justify-between text-sm">
                  <span className={nodeOffline ? "text-danger-700 dark:text-danger-300" : "text-default-600"}>开机时间</span>
                  <span className={nodeOffline ? "text-xs text-danger-800 dark:text-danger-200" : "text-xs"}>
                    {node.connectionStatus === 'online' && node.systemInfo
                      ? formatUptime(node.systemInfo.uptime)
                      : '-'
                    }
                  </span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className={nodeOffline ? "text-danger-700 dark:text-danger-300" : "text-default-600"}>Agent 版本</span>
                  <div className="flex min-w-0 items-center justify-end gap-1.5">
                    <span className={nodeOffline ? "text-xs text-danger-800 dark:text-danger-200" : "text-xs"}>{node.version || '未知'}</span>
                    {adminMode && upgradeStatus?.upToDate && <Chip size="sm" color="success" variant="flat" className="h-5 text-[10px]">最新</Chip>}
                  </div>
                </div>
              </div>

              {adminMode && showUpgradeTask && upgradeTask && (
                <div className="mb-4 flex min-h-9 items-center justify-between gap-2 border-y border-divider py-2 text-xs">
                  <div className="min-w-0 flex-1">
                    <div className="truncate font-medium">Agent {upgradeTask.fromVersion || node.version || '未知'} → {upgradeTask.targetVersion}</div>
                    <div className="mt-0.5 truncate text-default-500" title={upgradeTask.message}>{upgradeTask.message || '升级状态已更新'}</div>
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-1.5">
                    <Chip size="sm" variant="flat" color={upgradeStateColor(upgradeTask.state)} className="text-[10px]">
                      {upgradeStateLabels[upgradeTask.state] || upgradeTask.state}
                    </Chip>
                    {upgradeRetry && (
                      <Button
                        size="sm"
                        variant="flat"
                        color="primary"
                        className="h-7 min-w-0 px-2 text-[11px]"
                        startContent={<ClipboardCopy size={13} />}
                        isLoading={node.copyLoading}
                        onPress={() => handleOpenManualUpgrade(node)}
                      >
                        手动升级
                      </Button>
                    )}
                  </div>
                </div>
              )}

              {/* 系统监控 */}
              <div className="space-y-3 mb-4">
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <div className="flex justify-between text-xs mb-1">
                      <span>CPU</span>
                      <span className="font-mono">
                        {node.connectionStatus === 'online' && node.systemInfo
                          ? `${node.systemInfo.cpuUsage.toFixed(1)}%`
                          : '-'
                        }
                      </span>
                    </div>
                    <Progress
                      value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.cpuUsage : 0}
                      color={getProgressColor(
                        node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.cpuUsage : 0,
                        nodeOffline
                      )}
                      size="sm"
                      aria-label="CPU使用率"
                    />
                  </div>
                  <div>
                    <div className="flex justify-between text-xs mb-1">
                      <span>内存</span>
                      <span className="font-mono">
                        {node.connectionStatus === 'online' && node.systemInfo
                          ? `${node.systemInfo.memoryUsage.toFixed(1)}%`
                          : '-'
                        }
                      </span>
                    </div>
                    <Progress
                      value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.memoryUsage : 0}
                      color={getProgressColor(
                        node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.memoryUsage : 0,
                        nodeOffline
                      )}
                      size="sm"
                      aria-label="内存使用率"
                    />
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-2 text-xs">
                  <div className={nodeOffline ? "text-center p-2 bg-danger-100/80 dark:bg-danger-900/30 rounded border border-danger-200 dark:border-danger-700" : "text-center p-2 bg-default-50 dark:bg-default-100 rounded"}>
                    <div className={nodeOffline ? "text-danger-700 dark:text-danger-300 mb-0.5" : "text-default-600 mb-0.5"}>上传</div>
                    <div className={nodeOffline ? "font-mono text-danger-800 dark:text-danger-200" : "font-mono"}>
                      {node.connectionStatus === 'online' && node.systemInfo
                        ? formatSpeed(node.systemInfo.uploadSpeed)
                        : '-'
                      }
                    </div>
                  </div>
                  <div className={nodeOffline ? "text-center p-2 bg-danger-100/80 dark:bg-danger-900/30 rounded border border-danger-200 dark:border-danger-700" : "text-center p-2 bg-default-50 dark:bg-default-100 rounded"}>
                    <div className={nodeOffline ? "text-danger-700 dark:text-danger-300 mb-0.5" : "text-default-600 mb-0.5"}>下载</div>
                    <div className={nodeOffline ? "font-mono text-danger-800 dark:text-danger-200" : "font-mono"}>
                      {node.connectionStatus === 'online' && node.systemInfo
                        ? formatSpeed(node.systemInfo.downloadSpeed)
                        : '-'
                      }
                    </div>
                  </div>
                </div>

                {/* 流量统计 */}
                <div className="grid grid-cols-2 gap-2 text-xs">
                  <div className={nodeOffline ? "text-center p-2 bg-danger-100/80 dark:bg-danger-900/30 rounded border border-danger-200 dark:border-danger-700" : "text-center p-2 bg-primary-50 dark:bg-primary-100/20 rounded border border-primary-200 dark:border-primary-300/20"}>
                    <div className={nodeOffline ? "text-danger-700 dark:text-danger-300 mb-0.5" : "text-primary-600 dark:text-primary-400 mb-0.5"}>↑ 上行流量</div>
                    <div className={nodeOffline ? "font-mono text-danger-800 dark:text-danger-200" : "font-mono text-primary-700 dark:text-primary-300"}>
                      {node.connectionStatus === 'online' && node.systemInfo
                        ? formatTraffic(node.systemInfo.uploadTraffic)
                        : '-'
                      }
                    </div>
                  </div>
                  <div className={nodeOffline ? "text-center p-2 bg-danger-100/80 dark:bg-danger-900/30 rounded border border-danger-200 dark:border-danger-700" : "text-center p-2 bg-success-50 dark:bg-success-100/20 rounded border border-success-200 dark:border-success-300/20"}>
                    <div className={nodeOffline ? "text-danger-700 dark:text-danger-300 mb-0.5" : "text-success-600 dark:text-success-400 mb-0.5"}>↓ 下行流量</div>
                    <div className={nodeOffline ? "font-mono text-danger-800 dark:text-danger-200" : "font-mono text-success-700 dark:text-success-300"}>
                      {node.connectionStatus === 'online' && node.systemInfo
                        ? formatTraffic(node.systemInfo.downloadTraffic)
                        : '-'
                      }
                    </div>
                  </div>
                </div>
              </div>

              {/* 操作按钮 */}
              <div className="space-y-1.5">
                {adminMode && <Button
                  size="sm"
                  variant="flat"
                  color="default"
                  onPress={() => openServiceDiscovery(node)}
                  isDisabled={node.connectionStatus !== 'online'}
                  startContent={<Radar size={15} />}
                  className="w-full min-h-8"
                  title={node.connectionStatus === 'online' ? '手动读取本机监听端口和 Docker 服务' : '节点离线，无法发现服务'}
                >
                  发现服务
                </Button>}
                <div className={adminMode ? "grid grid-cols-2 gap-1.5" : "flex gap-1.5"}>
                  {adminMode && <Button
                    size="sm"
                    variant="flat"
                    color="secondary"
                    onPress={() => navigate(`/node/${node.id}/terminal`)}
                    isDisabled={node.connectionStatus !== 'online'}
                    startContent={<SquareTerminal size={15} />}
                    className="flex-1 min-h-8"
                    title={node.connectionStatus === 'online' ? '打开远程终端' : '节点离线，无法打开终端'}
                  >
                    终端
                  </Button>}
                  {adminMode && node.editable !== false && node.connectionStatus === 'online' && !upgradeStatus?.upToDate && !manualUpgrade && <Button
                    size="sm"
                    variant="flat"
                    color="warning"
                    onPress={() => openNodeUpgrade(node)}
                    isLoading={upgradeActive}
                    isDisabled={upgradeActive}
                    startContent={upgradeActive ? undefined : <RefreshCw size={15} />}
                    className="flex-1 min-h-8"
                    title={`${upgradeRetry ? '重新尝试升级到' : '升级到'} Agent ${upgradeTargetVersion}`}
                  >
                    {upgradeActive ? (upgradeStateLabels[upgradeTask?.state || ''] || '升级中') : upgradeRetry ? '重试升级' : '升级'}
                  </Button>}
                  {node.editable !== false && (nodeOffline || manualUpgrade) && <Button
                    size="sm"
                    variant="flat"
                    color={nodeOffline ? 'success' : 'warning'}
                    onPress={() => handleCopyInstallCommand(node, nodeOffline ? 'install' : 'upgrade')}
                    isLoading={node.copyLoading}
                    startContent={node.copyLoading ? undefined : nodeOffline ? <ServerCog size={15} /> : <RefreshCw size={15} />}
                    className="flex-1 min-h-8"
                    title={nodeOffline ? '获取 Agent 安装命令' : `升级到 Agent ${upgradeTargetVersion}`}
                  >
                    {nodeOffline ? '安装' : '升级'}
                  </Button>}
                  {node.editable !== false && <Button
                    size="sm"
                    variant="flat"
                    color="primary"
                    onPress={() => handleEdit(node)}
                    className="flex-1 min-h-8"
                  >
                    编辑
                  </Button>}
                  {node.deletable !== false && <Button
                    size="sm"
                    variant="flat"
                    color="danger"
                    onPress={() => handleDelete(node)}
                    className="flex-1 min-h-8"
                  >
                    删除
                  </Button>}
                  {node.accessType === 'shared' && <div className="flex-1 flex items-center justify-center text-xs text-default-500 border border-divider rounded-medium min-h-8">只读共享节点</div>}
                </div>
              </div>
            </CardBody>
          </Card>
        );
      }}
    />
  );

  return (
    
      <div className="px-3 lg:px-6 py-8">
        {/* 页面头部 */}
        <div className="flex items-center justify-between mb-6">
        <div className="flex-1">
        </div>

        <div className="flex gap-2">
          {adminMode && <Button
              size="sm"
              variant="flat"
              color="warning"
              onPress={openBatchUpgrade}
              isDisabled={batchEligibleCount === 0 || upgradeBatchStatus?.state === 'running'}
              startContent={<RefreshCw size={15} />}
            >
              批量升级{batchEligibleCount > 0 ? ` ${batchEligibleCount}` : ''}
          </Button>}
          <Button
              size="sm"
              variant="flat"
              color="default"
              onPress={handleCheckNodeStatus}
              isLoading={statusChecking}
            >
              刷新状态
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

        {adminMode && upgradeBatchStatus && ['running', 'paused'].includes(upgradeBatchStatus.state) && <div className={`mb-5 flex flex-col gap-3 border px-4 py-3 text-sm sm:flex-row sm:items-center sm:justify-between ${upgradeBatchStatus.state === 'paused' ? 'border-danger/30 bg-danger/10' : 'border-warning/30 bg-warning/10'}`}>
          <div><p className="font-medium">{upgradeBatchStatus.state === 'paused' ? '批量升级已暂停' : '分阶段批量升级进行中'}</p><p className="mt-1 text-xs text-default-600">{upgradeBatchStatus.message || '等待节点确认'} · 已完成 {upgradeBatchStatus.completedNodes}/{upgradeBatchStatus.totalNodes}{upgradeBatchStatus.currentNodeName ? ` · 当前 ${upgradeBatchStatus.currentNodeName}` : ''}</p></div>
          <Chip size="sm" variant="flat" color={upgradeBatchStatus.state === 'paused' ? 'danger' : 'warning'}>{upgradeBatchStatus.state === 'paused' ? '后续未执行' : '逐台确认'}</Chip>
        </div>}

        {/* 节点列表 */}
        {loading ? (
          <div className="flex items-center justify-center h-64">
            <div className="flex items-center gap-3">
              <Spinner size="sm" />
              <span className="text-default-600">正在加载...</span>
            </div>
          </div>
        ) : nodeList.length === 0 ? (
          <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
            <CardBody className="text-center py-16">
              <div className="flex flex-col items-center gap-4">
                <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                  <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M5 12h14M5 12l4-4m-4 4l4 4" />
                  </svg>
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-foreground">暂无节点配置</h3>
                  <p className="text-default-500 text-sm mt-1">还没有创建任何节点配置，点击上方按钮开始创建</p>
                </div>
              </div>
            </CardBody>
          </Card>
        ) : (
          <div className="space-y-8">
            {onlineNodes.length > 0 && (
              <section className="space-y-3">
                <div className="flex items-center justify-between border-b border-divider pb-2">
                  <div>
                    <h2 className="text-sm font-semibold text-foreground">在线节点</h2>
                    <p className="text-xs text-default-500">当前连接正常的节点</p>
                  </div>
                  <Chip color="success" variant="flat" size="sm" className="text-xs">
                    {onlineNodes.length} 个
                  </Chip>
                </div>
                {renderNodeGrid(onlineNodes)}
              </section>
            )}

            {offlineNodes.length > 0 && (
              <section className="space-y-3">
                <div className="offline-section-divider flex items-center justify-between border-b border-danger-200 pb-2">
                  <div>
                    <h2 className="offline-section-heading text-sm font-semibold text-danger-700">离线节点</h2>
                    <p className="offline-section-copy text-xs text-danger-600">需要优先排查或清理的节点</p>
                  </div>
                  <Chip color="danger" variant="flat" size="sm" className="text-xs offline-status-chip">
                    {offlineNodes.length} 个
                  </Chip>
                </div>
                {renderNodeGrid(offlineNodes)}
              </section>
            )}
          </div>
        )}

        {/* 新增/编辑节点对话框 */}
        <Modal 
          isOpen={dialogVisible} 
          onClose={() => setDialogVisible(false)}
          size="2xl"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            <ModalHeader>{dialogTitle}</ModalHeader>
            <ModalBody>
              <div className="space-y-4">
                <Input
                  label="节点名称"
                  placeholder="请输入节点名称"
                  value={form.name}
                  onChange={(e) => setForm(prev => ({ ...prev, name: e.target.value }))}
                  isInvalid={!!errors.name}
                  errorMessage={errors.name}
                  variant="bordered"
                />

                <Input
                  label="服务器IP"
                  placeholder="请输入服务器IP地址，如: 192.168.1.100 或 example.com"
                  value={form.serverIp}
                  onChange={(e) => setForm(prev => ({ ...prev, serverIp: e.target.value }))}
                  isInvalid={!!errors.serverIp}
                  errorMessage={errors.serverIp}
                  variant="bordered"
                />

                <Textarea
                  label="入口IP"
                  placeholder="一行一个IP地址或域名，例如:&#10;192.168.1.100&#10;example.com"
                  value={form.ipString}
                  onChange={(e) => setForm(prev => ({ ...prev, ipString: e.target.value }))}
                  isInvalid={!!errors.ipString}
                  errorMessage={errors.ipString}
                  variant="bordered"
                  minRows={3}
                  maxRows={5}
                  description="支持多个IP，每行一个地址"
                />

                <div className="grid grid-cols-2 gap-4">
                  <Input
                    label="起始端口"
                    type="number"
                    placeholder="1000"
                    value={form.portSta.toString()}
                    onChange={(e) => setForm(prev => ({ ...prev, portSta: parseInt(e.target.value) || 1000 }))}
                    isInvalid={!!errors.portSta}
                    errorMessage={errors.portSta}
                    variant="bordered"
                    min={1}
                    max={65535}
                  />

                  <Input
                    label="结束端口"
                    type="number"
                    placeholder="65535"
                    value={form.portEnd.toString()}
                    onChange={(e) => setForm(prev => ({ ...prev, portEnd: parseInt(e.target.value) || 65535 }))}
                    isInvalid={!!errors.portEnd}
                    errorMessage={errors.portEnd}
                    variant="bordered"
                    min={1}
                    max={65535}
                  />
                </div>

                {/* 屏蔽协议 */}
                <div className="mt-1">
                  <div className="text-sm font-medium text-default-700">屏蔽协议</div>
                  <div className="text-xs text-default-500 mb-2">开启开关以屏蔽对应协议</div>
                  {protocolDisabled && (
                    <Alert
                      color="warning"
                      variant="flat"
                      description={protocolDisabledReason || '等待节点上线后再设置'}
                      className="mb-2"
                    />
                  )}
                  <div className={`grid grid-cols-1 sm:grid-cols-3 gap-3 bg-default-50 dark:bg-default-100 p-3 rounded-md border border-default-200 dark:border-default-100/30 ${protocolDisabled ? 'opacity-70' : ''}`}>
                    {/* HTTP tile */}
                    <div className="px-3 py-3 rounded-lg bg-white dark:bg-default-50 border border-default-200 dark:border-default-100/30 hover:border-primary-200 transition-colors">
                      <div className="flex items-center gap-2 mb-2">
                        <svg className="w-4 h-4 text-default-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="M2 10h20"/></svg>
                        <div className="text-sm font-medium text-default-700">HTTP</div>
                      </div>
                      <div className="flex items-center justify-between">
                        <div className="text-xs text-default-500">禁用/启用</div>
                        <Switch
                          size="sm"
                          isSelected={form.http === 1}
                          isDisabled={protocolDisabled}
                          onValueChange={(v) => setForm(prev => ({ ...prev, http: v ? 1 : 0 }))}
                        />
                      </div>
                      <div className="mt-1 text-xs text-default-400">{form.http === 1 ? '已开启' : '已关闭'}</div>
                    </div>

                    {/* TLS tile */}
                    <div className="px-3 py-3 rounded-lg bg-white dark:bg-default-50 border border-default-200 dark:border-default-100/30 hover:border-primary-200 transition-colors">
                      <div className="flex items-center gap-2 mb-2">
                        <svg className="w-4 h-4 text-default-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M6 10V7a6 6 0 1 1 12 0v3"/><rect x="4" y="10" width="16" height="10" rx="2"/></svg>
                        <div className="text-sm font-medium text-default-700">TLS</div>
                      </div>
                      <div className="flex items-center justify-between">
                        <div className="text-xs text-default-500">禁用/启用</div>
                        <Switch
                          size="sm"
                          isSelected={form.tls === 1}
                          isDisabled={protocolDisabled}
                          onValueChange={(v) => setForm(prev => ({ ...prev, tls: v ? 1 : 0 }))}
                        />
                      </div>
                      <div className="mt-1 text-xs text-default-400">{form.tls === 1 ? '已开启' : '已关闭'}</div>
                    </div>

                    {/* SOCKS tile */}
                    <div className="px-3 py-3 rounded-lg bg-white dark:bg-default-50 border border-default-200 dark:border-default-100/30 hover:border-primary-200 transition-colors">
                      <div className="flex items-center gap-2 mb-2">
                        <svg className="w-4 h-4 text-default-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                        <div className="text-sm font-medium text-default-700">SOCKS</div>
                      </div>
                      <div className="flex items-center justify-between">
                        <div className="text-xs text-default-500">禁用/启用</div>
                        <Switch
                          size="sm"
                          isSelected={form.socks === 1}
                          isDisabled={protocolDisabled}
                          onValueChange={(v) => setForm(prev => ({ ...prev, socks: v ? 1 : 0 }))}
                        />
                      </div>
                      <div className="mt-1 text-xs text-default-400">{form.socks === 1 ? '已开启' : '已关闭'}</div>
                    </div>
                  </div>
                </div>



                <Alert
                        color="danger"
                        variant="flat"
                        description="请不要在出口节点执行屏蔽协议，否则可能影响转发；屏蔽协议仅需在入口节点执行。"
                        className="mt-3"
                      />
                
                <Alert
                        color="primary"
                        variant="flat"
                        description="服务器ip是你要添加的服务器的ip地址，不是面板的ip地址。入口ip是用于展示在转发页面，面向用户的访问地址。实在理解不到说明你没这个需求，都填节点的服务器ip就行！"
                        className="mt-4"
                      />
              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="flat"
                onPress={() => setDialogVisible(false)}
              >
                取消
              </Button>
              <Button
                color="primary"
                onPress={handleSubmit}
                isLoading={submitLoading}
              >
                {submitLoading ? '提交中...' : '确定'}
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 节点服务发现 */}
        <Modal isOpen={discoveryOpen} onOpenChange={setDiscoveryOpen} size="5xl" scrollBehavior="inside" backdrop="blur">
          <ModalContent>
            <ModalHeader className="flex items-center justify-between gap-3 pr-12">
              <div>
                <div>{discoveryNode?.name || '节点'} · 服务发现</div>
                <div className="mt-1 text-xs font-normal text-default-500">仅在点击扫描时读取本机监听端口，不会后台持续扫描。</div>
              </div>
              <Button size="sm" color="primary" startContent={discoveryLoading ? undefined : <Radar size={16} />} isLoading={discoveryLoading} onPress={runServiceDiscovery}>开始扫描</Button>
            </ModalHeader>
            <ModalBody className="pb-6">
              {!discoveryResult && !discoveryLoading && <div className="flex min-h-56 flex-col items-center justify-center gap-3 border-y border-divider text-default-500"><Radar size={30} /><span className="text-sm">点击“开始扫描”读取当前监听服务</span></div>}
              {discoveryLoading && <div className="flex min-h-56 items-center justify-center gap-3"><Spinner size="sm" /><span className="text-sm text-default-500">正在读取监听端口并识别 Web 服务...</span></div>}
              {discoveryResult && !discoveryLoading && <>
                <div className="grid gap-px overflow-hidden rounded-md border border-divider bg-divider sm:grid-cols-4">
                  <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">监听服务</div><div className="mt-1 text-xl font-semibold">{discoveryResult.listenerCount}</div></div>
                  <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">Web 服务</div><div className="mt-1 text-xl font-semibold">{discoveryResult.webServiceCount}</div></div>
                  <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">Docker</div><div className="mt-1 text-sm font-medium">{discoveryResult.dockerAvailable ? '已连接' : '未检测到'}</div></div>
                  <div className="bg-content1 px-4 py-3"><div className="text-xs text-default-500">扫描耗时</div><div className="mt-1 text-sm font-medium">{discoveryResult.durationMs} ms</div></div>
                </div>
                {discoveryResult.services.length === 0 ? <div className="flex min-h-40 items-center justify-center border-y border-divider text-sm text-default-500">没有发现正在监听的 TCP 服务</div> : <div className="grid gap-3 lg:grid-cols-2">
                  {discoveryResult.services.map(service => {
                    const web = service.protocol === 'http' || service.protocol === 'https';
                    return <article key={`${service.host}:${service.port}`} className="rounded-md border border-divider bg-content1 p-4">
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2"><span className="font-medium">{service.serviceName}</span><Chip size="sm" variant="flat" color={web ? 'success' : 'default'}>{service.protocol.toUpperCase()}</Chip>{service.containerName && <Chip size="sm" variant="flat" color="secondary" startContent={<Container size={12} />}>Docker</Chip>}</div>
                          <div className="mt-1 font-mono text-sm text-default-600">{service.host}:{service.port}</div>
                        </div>
                        <Button size="sm" color="primary" variant="flat" startContent={<Globe2 size={15} />} isDisabled={!web} onPress={() => publishDiscoveredService(service)}>一键发布</Button>
                      </div>
                      <div className="mt-3 grid gap-2 border-t border-divider pt-3 text-xs sm:grid-cols-2">
                        <div><span className="text-default-500">进程：</span>{service.processName || '未识别'}{service.processId ? ` · PID ${service.processId}` : ''}</div>
                        <div><span className="text-default-500">响应：</span>{service.httpStatus || '--'}{service.latencyMs != null ? ` · ${service.latencyMs} ms` : ''}</div>
                        {service.title && <div className="truncate sm:col-span-2" title={service.title}><span className="text-default-500">网页标题：</span>{service.title}</div>}
                        {service.containerName && <div className="truncate sm:col-span-2" title={service.containerImage}><span className="text-default-500">容器：</span>{service.containerName} · {service.containerImage}</div>}
                      </div>
                      {!web && <div className="mt-3 text-xs text-default-500">当前一键发布仅支持识别为 HTTP 或 HTTPS 的服务。</div>}
                    </article>;
                  })}
                </div>}
              </>}
            </ModalBody>
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
                  <p>确定要删除节点 <strong>"{nodeToDelete?.name}"</strong> 吗？</p>
                  <p className="text-small text-default-500">
                    节点在线且有关联隧道时，系统会阻止删除；如需清理失效隧道，请到隧道管理删除隧道。删除节点会同时清理以它作为入口或后端的域名直达；节点离线时还会删除相关隧道、转发、用户隧道授权和限速规则，且不可恢复。
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

        <Modal
          isOpen={upgradeModalOpen}
          onOpenChange={setUpgradeModalOpen}
          size="lg"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader>{upgradeBatch ? '批量升级 Agent' : `升级 ${upgradeNode?.name || '节点'} Agent`}</ModalHeader>
                <ModalBody>
                  <div className="grid grid-cols-2 gap-3 border-y border-divider py-4 text-sm">
                    <div>
                      <div className="text-xs text-default-500">升级范围</div>
                      <div className="mt-1 font-medium">{upgradeBatch ? `${batchEligibleCount} 个在线节点` : upgradeNode?.name}</div>
                    </div>
                    <div>
                      <div className="text-xs text-default-500">目标版本</div>
                      <div className="mt-1 font-medium">Agent {upgradeTargetVersion || '最新版本'}</div>
                    </div>
                  </div>
                  <Alert
                    color="warning"
                    variant="flat"
                    title={upgradeBatch ? '先试运行一台，再逐台升级' : '升级期间连接会短暂中断'}
                    description={upgradeBatch
                      ? '系统先对一台节点执行预检、校验、重启和重新连接确认。只有确认成功才继续下一台；任一节点失败会自动回退并暂停后续任务。'
                      : 'Agent 会先完成预检和版本校验，再原子替换二进制。新版必须在 45 秒内重新连接面板，否则自动恢复旧版本并重新上线。'}
                  />
                </ModalBody>
                <ModalFooter>
                  <Button variant="flat" onPress={onClose}>取消</Button>
                  <Button color="warning" onPress={confirmUpgrade} isLoading={upgradeSubmitting}
                    startContent={upgradeSubmitting ? undefined : <RefreshCw size={16} />}>
                    确认升级
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 安装命令模态框 */}
        <Modal 
          isOpen={installCommandModal} 
          onClose={() => setInstallCommandModal(false)}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader>{installCommandAction === 'upgrade' ? '升级' : '安装'} Agent - {currentNodeName}</ModalHeader>
            <ModalBody>
              <div className="space-y-4">
                <p className="text-sm text-default-600">
                  请复制以下{installCommandAction === 'upgrade' ? '升级' : '安装'}命令到该节点服务器执行：
                </p>
                {installCommandAction === 'upgrade' && (
                  <Alert color="warning" variant="flat" title="执行说明">
                    使用 root 用户执行。命令只更新 Agent 程序并保留节点地址、密钥和现有业务配置；升级过程中 Agent 会短暂重启，现有连接可能重新建立。
                  </Alert>
                )}
                <div className="relative">
                  <Textarea
                    value={installCommand}
                    readOnly
                    variant="bordered"
                    minRows={6}
                    maxRows={10}
                    className="font-mono text-sm"
                    classNames={{
                      input: "font-mono text-sm"
                    }}
                  />
                  <Button
                    size="sm"
                    color="primary"
                    variant="flat"
                    className="absolute top-2 right-2"
                    onPress={handleManualCopy}
                  >
                    复制
                  </Button>
                </div>
                <div className="text-xs text-default-500">
                  复制失败时可直接选择上方文本；命令会优先使用 GitHub，并在下载失败时自动尝试备用地址。
                </div>
              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="flat"
                onPress={() => setInstallCommandModal(false)}
              >
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      </div>
    
  );
}
