import { useState, useEffect } from "react";
import { Button } from "@heroui/button";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Input } from "@heroui/input";
import {
  Table,
  TableHeader,
  TableColumn,
  TableBody,
  TableRow,
  TableCell,
} from "@heroui/table";
import {
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter,
  useDisclosure,
} from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Select, SelectItem } from "@heroui/select";
import { RadioGroup, Radio } from "@heroui/radio";
import { DatePicker } from "@heroui/date-picker";
import { Spinner } from "@heroui/spinner";
import { Progress } from "@heroui/progress";
import { Switch } from "@heroui/switch";
import { Tabs, Tab } from "@heroui/tabs";
import toast from "react-hot-toast";
import { parseDate } from "@internationalized/date";
import { KeyRound, RotateCcw } from "lucide-react";

import {
  User,
  UserForm,
  UserTunnel,
  UserTunnelForm,
  UserTunnelProvision,
  UserNodeProvision,
  UserPortProvision,
  Tunnel,
  SpeedLimit,
  Pagination as PaginationType,
} from "@/types";
import {
  getAllUsers,
  createUser,
  updateUser,
  deleteUser,
  getTunnelList,
  assignUserTunnel,
  getUserTunnelList,
  removeUserTunnel,
  updateUserTunnel,
  getSpeedLimitList,
  resetUserFlow,
  getNodeList,
  assignUserNode,
  getUserNodeList,
  removeUserNode,
  getPublishingPortPools,
  getPublishingPortGrants,
  createPrivateProxyGrant,
  deletePrivateProxy,
  getPrivateProxyGrants,
  resetPrivateProxyGrantFlow,
  updatePrivateProxyGrant,
  type PrivateProxyItem,
  type PrivateProxyType,
  type PublishingPortPool,
} from "@/api";
import { SearchIcon, EditIcon, DeleteIcon, UserIcon } from "@/components/icons";
import { SortableCardGrid } from "@/components/sortable-card-grid";
import { useCardOrder } from "@/hooks/use-card-order";

// 工具函数
const formatFlow = (value: number, unit: string = "bytes"): string => {
  if (unit === "gb") {
    return `${value} GB`;
  } else {
    if (value === 0) return "0 B";
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(2)} KB`;
    if (value < 1024 * 1024 * 1024)
      return `${(value / (1024 * 1024)).toFixed(2)} MB`;

    return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }
};

const formatDate = (timestamp: number): string => {
  return new Date(timestamp).toLocaleString();
};

const getExpireStatus = (expTime: number) => {
  const now = Date.now();

  if (expTime < now) {
    return { color: "danger" as const, text: "已过期" };
  }
  const diffDays = Math.ceil((expTime - now) / (1000 * 60 * 60 * 24));

  if (diffDays <= 7) {
    return { color: "warning" as const, text: `${diffDays}天后过期` };
  }

  return { color: "success" as const, text: "正常" };
};

// 获取用户状态（根据status字段）
const getUserStatus = (user: User) => {
  if (user.status === 1) {
    return { color: "success" as const, text: "正常" };
  } else {
    return { color: "danger" as const, text: "禁用" };
  }
};

const calculateUserTotalUsedFlow = (user: User): number => {
  return (user.inFlow || 0) + (user.outFlow || 0);
};

const calculateTunnelUsedFlow = (tunnel: UserTunnel): number => {
  const inFlow = tunnel.inFlow || 0;
  const outFlow = tunnel.outFlow || 0;

  // 后端已按计费类型处理流量，前端直接使用入站+出站总和
  return inFlow + outFlow;
};

const FLOW_RESET_OPTIONS = [
  { key: "0", label: "不重置" },
  ...Array.from({ length: 31 }, (_, index) => ({
    key: String(index + 1),
    label: `每月 ${index + 1} 日（0 点重置）`,
  })),
];

type ResourceKind = "tunnel" | "node";

interface ResourceEditorState {
  kind: ResourceKind;
  resourceId: number;
  flow: number;
  flowUnlimited: boolean;
  num: number;
  forwardUnlimited: boolean;
  expTime: Date | null;
  flowResetTime: number;
  speedId: number | null;
  status: number;
}

interface PortEditorState {
  id?: number;
  poolId: number;
  startPort: number;
  endPort: number;
}

type GrantProxyType = PrivateProxyType;
type GrantRealityPreset = "www.cloudflare.com" | "www.google.com" | "custom";

const grantProxyLabels: Record<GrantProxyType, string> = {
  socks5: "SOCKS5",
  http: "HTTP",
  shadowsocks: "Shadowsocks",
  vless_reality: "VLESS + REALITY",
  trojan: "Trojan",
  hysteria2: "Hysteria2",
  tuic: "TUIC v5",
  wireguard: "WireGuard",
};
const isAdvancedGrantProxy = (proxyType: GrantProxyType) =>
  ["trojan", "hysteria2", "tuic", "wireguard"].includes(proxyType);
const supportsGrantSpeedLimit = (proxyType: GrantProxyType) =>
  !isAdvancedGrantProxy(proxyType);

interface ProxyGrantEditorState {
  id?: number;
  name: string;
  nodeId: number | null;
  proxyType: GrantProxyType;
  listenPort: number;
  authUsername: string;
  authPassword: string;
  cipher: "aes-128-gcm" | "aes-256-gcm" | "chacha20-ietf-poly1305";
  realityPreset: GrantRealityPreset;
  realityServerName: string;
  flowLimit: number;
  flowUnlimited: boolean;
  flowResetDay: number;
  permanent: boolean;
  expiresAt: Date | null;
  speedUnlimited: boolean;
  speedLimitMbps: number;
}

const emptyProxyGrant = (): ProxyGrantEditorState => ({
  name: "",
  nodeId: null,
  proxyType: "socks5",
  listenPort: 0,
  authUsername: "",
  authPassword: "",
  cipher: "aes-256-gcm",
  realityPreset: "www.cloudflare.com",
  realityServerName: "www.cloudflare.com",
  flowLimit: 100,
  flowUnlimited: false,
  flowResetDay: 0,
  permanent: true,
  expiresAt: null,
  speedUnlimited: true,
  speedLimitMbps: 100,
});

const randomProxySecret = () =>
  Array.from(crypto.getRandomValues(new Uint8Array(18)), (value) =>
    value.toString(16).padStart(2, "0"),
  ).join("");

export default function UserPage() {
  // 状态管理
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [pagination, setPagination] = useState<PaginationType>({
    current: 1,
    size: 10,
    total: 0,
  });
  const searchScopeHash = Array.from(searchKeyword).reduce(
    (hash, character) => (hash * 31 + (character.codePointAt(0) || 0)) >>> 0,
    0,
  );
  const userCardOrder = useCardOrder(
    `user-cards-page-${pagination.current}-search-${searchScopeHash}`,
    users.map((user) => user.id),
  );
  const orderedUsers = userCardOrder.sortItems(users, (user) => user.id);

  // 用户表单相关状态
  const {
    isOpen: isUserModalOpen,
    onOpen: onUserModalOpen,
    onClose: onUserModalClose,
  } = useDisclosure();
  const [isEdit, setIsEdit] = useState(false);
  const [userForm, setUserForm] = useState<UserForm>({
    user: "",
    pwd: "",
    status: 1,
    flow: 0,
    flowUnlimited: false,
    num: 0,
    forwardUnlimited: false,
    expTime: null,
    flowResetTime: 0,
    tunnelPermissions: [],
    nodePermissions: [],
    portPermissions: [],
  });
  const [userFormLoading, setUserFormLoading] = useState(false);
  const [userModalTab, setUserModalTab] = useState("resources");
  const [pendingTunnelId, setPendingTunnelId] = useState<number | null>(null);
  const [pendingNodeId, setPendingNodeId] = useState<number | null>(null);
  const [pendingPortPoolId, setPendingPortPoolId] = useState<number | null>(
    null,
  );
  const [resourceEditor, setResourceEditor] =
    useState<ResourceEditorState | null>(null);
  const [portEditor, setPortEditor] = useState<PortEditorState | null>(null);
  const [proxyGrants, setProxyGrants] = useState<PrivateProxyItem[]>([]);
  const [proxyGrantEditor, setProxyGrantEditor] =
    useState<ProxyGrantEditorState | null>(null);
  const [proxyGrantSaving, setProxyGrantSaving] = useState(false);

  // 隧道权限管理相关状态
  const { isOpen: isTunnelModalOpen, onClose: onTunnelModalClose } =
    useDisclosure();
  const [currentUser] = useState<User | null>(null);
  const [userTunnels, setUserTunnels] = useState<UserTunnel[]>([]);
  const [tunnelListLoading, setTunnelListLoading] = useState(false);

  // 分配新隧道权限相关状态
  const [tunnelForm, setTunnelForm] = useState<UserTunnelForm>({
    tunnelId: null,
    flow: 100,
    num: 10,
    expTime: null,
    flowResetTime: 0,
    speedId: null,
  });
  const [assignLoading, setAssignLoading] = useState(false);

  // 编辑隧道权限相关状态
  const {
    isOpen: isEditTunnelModalOpen,
    onOpen: onEditTunnelModalOpen,
    onClose: onEditTunnelModalClose,
  } = useDisclosure();
  const [editTunnelForm, setEditTunnelForm] = useState<UserTunnel | null>(null);
  const [editTunnelLoading, setEditTunnelLoading] = useState(false);

  // 删除确认相关状态
  const {
    isOpen: isDeleteModalOpen,
    onOpen: onDeleteModalOpen,
    onClose: onDeleteModalClose,
  } = useDisclosure();
  const [userToDelete, setUserToDelete] = useState<User | null>(null);

  // 删除隧道权限确认相关状态
  const {
    isOpen: isDeleteTunnelModalOpen,
    onOpen: onDeleteTunnelModalOpen,
    onClose: onDeleteTunnelModalClose,
  } = useDisclosure();
  const [tunnelToDelete, setTunnelToDelete] = useState<UserTunnel | null>(null);

  // 重置流量确认相关状态
  const {
    isOpen: isResetFlowModalOpen,
    onOpen: onResetFlowModalOpen,
    onClose: onResetFlowModalClose,
  } = useDisclosure();
  const [userToReset, setUserToReset] = useState<User | null>(null);
  const [resetFlowLoading, setResetFlowLoading] = useState(false);

  // 重置隧道流量确认相关状态
  const {
    isOpen: isResetTunnelFlowModalOpen,
    onOpen: onResetTunnelFlowModalOpen,
    onClose: onResetTunnelFlowModalClose,
  } = useDisclosure();
  const [tunnelToReset, setTunnelToReset] = useState<UserTunnel | null>(null);
  const [resetTunnelFlowLoading, setResetTunnelFlowLoading] = useState(false);

  // 其他数据
  const [tunnels, setTunnels] = useState<Tunnel[]>([]);
  const [speedLimits, setSpeedLimits] = useState<SpeedLimit[]>([]);
  const [nodes, setNodes] = useState<any[]>([]);
  const [portPools, setPortPools] = useState<PublishingPortPool[]>([]);
  const [userNodes, setUserNodes] = useState<any[]>([]);
  const [nodeToShare, setNodeToShare] = useState<number | null>(null);
  const [nodeShareLoading, setNodeShareLoading] = useState(false);

  // 生命周期
  useEffect(() => {
    loadUsers();
    loadTunnels();
    loadSpeedLimits();
    loadNodes();
    loadPortPools();
  }, [pagination.current, pagination.size, searchKeyword]);

  // 数据加载函数
  const loadUsers = async () => {
    setLoading(true);
    try {
      const response = await getAllUsers({
        current: pagination.current,
        size: pagination.size,
        keyword: searchKeyword,
      });

      if (response.code === 0) {
        const data = response.data || {};

        setUsers(data || []);
      } else {
        toast.error(response.msg || "获取用户列表失败");
      }
    } catch (error) {
      toast.error("获取用户列表失败");
    } finally {
      setLoading(false);
    }
  };

  const loadTunnels = async () => {
    try {
      const response = await getTunnelList();

      if (response.code === 0) {
        setTunnels(response.data || []);
      }
    } catch (error) {
      console.error("获取隧道列表失败:", error);
    }
  };

  const loadSpeedLimits = async () => {
    try {
      const response = await getSpeedLimitList();

      if (response.code === 0) {
        setSpeedLimits(response.data || []);
      }
    } catch (error) {
      console.error("获取限速规则列表失败:", error);
    }
  };

  const loadNodes = async () => {
    try {
      const response = await getNodeList();

      if (response.code === 0) setNodes(response.data || []);
    } catch (error) {
      console.error("获取节点列表失败:", error);
    }
  };

  const loadPortPools = async () => {
    try {
      const response = await getPublishingPortPools();

      if (response.code === 0) setPortPools(response.data || []);
    } catch (error) {
      console.error("获取端口池失败:", error);
    }
  };

  const loadUserTunnels = async (userId: number) => {
    setTunnelListLoading(true);
    try {
      const response = await getUserTunnelList({ userId });

      if (response.code === 0) {
        setUserTunnels(response.data || []);
      } else {
        toast.error(response.msg || "获取隧道权限列表失败");
      }
    } catch (error) {
      toast.error("获取隧道权限列表失败");
    } finally {
      setTunnelListLoading(false);
    }
  };

  // 用户管理操作
  const handleSearch = () => {
    setPagination((prev) => ({ ...prev, current: 1 }));
    loadUsers();
  };

  const handleAdd = () => {
    setIsEdit(false);
    setUserModalTab("resources");
    setPendingTunnelId(null);
    setPendingNodeId(null);
    setPendingPortPoolId(null);
    setResourceEditor(null);
    setPortEditor(null);
    setProxyGrants([]);
    setProxyGrantEditor(null);
    setUserForm({
      user: "",
      pwd: "",
      status: 1,
      flow: 0,
      flowUnlimited: false,
      num: 0,
      forwardUnlimited: false,
      expTime: null,
      flowResetTime: 0,
      tunnelPermissions: [],
      nodePermissions: [],
      portPermissions: [],
    });
    onUserModalOpen();
  };

  const handleEdit = async (user: User) => {
    setIsEdit(true);
    setUserModalTab("resources");
    setPendingTunnelId(null);
    setPendingNodeId(null);
    setPendingPortPoolId(null);
    setResourceEditor(null);
    setPortEditor(null);
    let tunnelResponse: any;
    let nodeResponse: any;
    let portResponse: any;
    let proxyResponse: any;

    try {
      [tunnelResponse, nodeResponse, portResponse, proxyResponse] =
        await Promise.all([
          getUserTunnelList({ userId: user.id }),
          getUserNodeList(user.id),
          getPublishingPortGrants(user.id),
          getPrivateProxyGrants(user.id),
        ]);
    } catch {
      toast.error("加载用户资源权限失败，请重试");

      return;
    }
    if (
      tunnelResponse.code !== 0 ||
      nodeResponse.code !== 0 ||
      portResponse.code !== 0 ||
      proxyResponse.code !== 0
    ) {
      toast.error(
        tunnelResponse.msg ||
          nodeResponse.msg ||
          portResponse.msg ||
          proxyResponse.msg ||
          "加载用户资源权限失败",
      );

      return;
    }
    const tunnelPermissions: UserTunnelProvision[] = (
      tunnelResponse.data || []
    ).map((item: any) => ({
      tunnelId: item.tunnelId,
      flow: item.flow || 0,
      flowUnlimited: item.flowUnlimited === 1,
      num: item.num || 0,
      forwardUnlimited: item.forwardUnlimited === 1,
      expTime: item.expTime ? new Date(item.expTime) : null,
      flowResetTime: item.flowResetTime || 0,
      speedId: item.speedId || null,
      status: item.status ?? 1,
    }));
    const nodePermissions: UserNodeProvision[] = (nodeResponse.data || []).map(
      (item: any) => ({
        nodeId: item.nodeId,
        flow: item.flow || 0,
        flowUnlimited: item.flowUnlimited === 1,
        num: item.num || 0,
        forwardUnlimited: item.forwardUnlimited === 1,
        expTime: item.expTime ? new Date(item.expTime) : null,
        flowResetTime: item.flowResetTime || 0,
        status: item.permissionStatus ?? 1,
        usedFlow: (item.inFlow || 0) + (item.outFlow || 0),
        usedForwards: item.usedForwards || 0,
      }),
    );
    const portPermissions: UserPortProvision[] = (portResponse.data || []).map(
      (item: any) => ({
        id: item.id,
        poolId: item.poolId,
        startPort: item.startPort,
        endPort: item.endPort,
        poolName: item.poolName,
        nodeName: item.nodeName,
        publicHost: item.publicHost,
        usedPorts: item.usedPorts || 0,
        availablePorts: item.availablePorts || 0,
      }),
    );

    setProxyGrants(proxyResponse.data || []);
    setUserForm({
      id: user.id,
      name: user.name,
      user: user.user,
      pwd: "",
      status: user.status,
      flow: user.flow,
      flowUnlimited: user.flowUnlimited === 1,
      num: user.num,
      forwardUnlimited: user.forwardUnlimited === 1,
      expTime: user.expTime ? new Date(user.expTime) : null,
      flowResetTime: user.flowResetTime ?? 0,
      tunnelPermissions,
      nodePermissions,
      portPermissions,
    });
    onUserModalOpen();
  };

  const handleDelete = (user: User) => {
    setUserToDelete(user);
    onDeleteModalOpen();
  };

  const handleConfirmDelete = async () => {
    if (!userToDelete) return;

    try {
      const response = await deleteUser(userToDelete.id);

      if (response.code === 0) {
        toast.success("删除成功");
        loadUsers();
        onDeleteModalClose();
        setUserToDelete(null);
      } else {
        toast.error(response.msg || "删除失败");
      }
    } catch (error) {
      toast.error("删除失败");
    }
  };

  const handleSubmitUser = async () => {
    if (!userForm.user || (!userForm.pwd && !isEdit)) {
      toast.error("请填写完整信息");

      return;
    }

    setUserFormLoading(true);
    try {
      const submitData: any = {
        ...userForm,
        expTime: userForm.expTime?.getTime() ?? null,
        tunnelPermissions: userForm.tunnelPermissions.map((item) => ({
          ...item,
          expTime: item.expTime?.getTime() ?? null,
        })),
        nodePermissions: userForm.nodePermissions.map((item) => ({
          ...item,
          expTime: item.expTime?.getTime() ?? null,
        })),
      };

      if (isEdit && !submitData.pwd) {
        delete submitData.pwd;
      }

      const response = isEdit
        ? await updateUser(submitData)
        : await createUser(submitData);

      if (response.code === 0) {
        toast.success(isEdit ? "更新成功" : "创建成功");
        onUserModalClose();
        loadUsers();
      } else {
        toast.error(response.msg || (isEdit ? "更新失败" : "创建失败"));
      }
    } catch (error) {
      toast.error(isEdit ? "更新失败" : "创建失败");
    } finally {
      setUserFormLoading(false);
    }
  };

  const loadUserNodes = async (userId: number) => {
    try {
      const response = await getUserNodeList(userId);

      if (response.code === 0) setUserNodes(response.data || []);
    } catch (error) {
      toast.error("获取节点共享列表失败");
    }
  };

  const handleAssignNode = async () => {
    if (!currentUser || !nodeToShare) return;
    setNodeShareLoading(true);
    try {
      const response = await assignUserNode({
        userId: currentUser.id,
        nodeId: nodeToShare,
      });

      if (response.code === 0) {
        toast.success("节点共享成功");
        setNodeToShare(null);
        loadUserNodes(currentUser.id);
      } else {
        toast.error(response.msg || "节点共享失败");
      }
    } finally {
      setNodeShareLoading(false);
    }
  };

  const handleRemoveNode = async (nodeId: number) => {
    if (!currentUser) return;
    const response = await removeUserNode({ userId: currentUser.id, nodeId });

    if (response.code === 0) {
      toast.success("节点共享已取消");
      loadUserNodes(currentUser.id);
    } else {
      toast.error(response.msg || "取消共享失败");
    }
  };

  const handleAssignTunnel = async () => {
    if (!tunnelForm.tunnelId || !tunnelForm.expTime || !currentUser) {
      toast.error("请填写完整信息");

      return;
    }

    setAssignLoading(true);
    try {
      const response = await assignUserTunnel({
        userId: currentUser.id,
        tunnelId: tunnelForm.tunnelId,
        flow: tunnelForm.flow,
        num: tunnelForm.num,
        expTime: tunnelForm.expTime.getTime(),
        flowResetTime: tunnelForm.flowResetTime,
        speedId: tunnelForm.speedId,
      });

      if (response.code === 0) {
        toast.success("分配成功");
        setTunnelForm({
          tunnelId: null,
          flow: 100,
          num: 10,
          expTime: null,
          flowResetTime: 0,
          speedId: null,
        });
        loadUserTunnels(currentUser.id);
      } else {
        toast.error(response.msg || "分配失败");
      }
    } catch (error) {
      toast.error("分配失败");
    } finally {
      setAssignLoading(false);
    }
  };

  const handleEditTunnel = (userTunnel: UserTunnel) => {
    setEditTunnelForm({
      ...userTunnel,
      expTime: userTunnel.expTime,
    });
    onEditTunnelModalOpen();
  };

  const handleUpdateTunnel = async () => {
    if (!editTunnelForm) return;

    setEditTunnelLoading(true);
    try {
      const response = await updateUserTunnel({
        id: editTunnelForm.id,
        flow: editTunnelForm.flow,
        num: editTunnelForm.num,
        expTime: editTunnelForm.expTime,
        flowResetTime: editTunnelForm.flowResetTime,
        speedId: editTunnelForm.speedId,
        status: editTunnelForm.status,
      });

      if (response.code === 0) {
        toast.success("更新成功");
        onEditTunnelModalClose();
        if (currentUser) {
          loadUserTunnels(currentUser.id);
        }
      } else {
        toast.error(response.msg || "更新失败");
      }
    } catch (error) {
      toast.error("更新失败");
    } finally {
      setEditTunnelLoading(false);
    }
  };

  const handleRemoveTunnel = (userTunnel: UserTunnel) => {
    setTunnelToDelete(userTunnel);
    onDeleteTunnelModalOpen();
  };

  const handleConfirmRemoveTunnel = async () => {
    if (!tunnelToDelete) return;

    try {
      const response = await removeUserTunnel({ id: tunnelToDelete.id });

      if (response.code === 0) {
        toast.success("删除成功");
        if (currentUser) {
          loadUserTunnels(currentUser.id);
        }
        onDeleteTunnelModalClose();
        setTunnelToDelete(null);
      } else {
        toast.error(response.msg || "删除失败");
      }
    } catch (error) {
      toast.error("删除失败");
    }
  };

  // 重置流量相关函数
  const handleResetFlow = (user: User) => {
    setUserToReset(user);
    onResetFlowModalOpen();
  };

  const handleConfirmResetFlow = async () => {
    if (!userToReset) return;

    setResetFlowLoading(true);
    try {
      const response = await resetUserFlow({
        id: userToReset.id,
        type: 1, // 1表示重置用户流量
      });

      if (response.code === 0) {
        toast.success("流量重置成功");
        onResetFlowModalClose();
        setUserToReset(null);
        loadUsers(); // 重新加载用户列表
      } else {
        toast.error(response.msg || "重置失败");
      }
    } catch (error) {
      toast.error("重置失败");
    } finally {
      setResetFlowLoading(false);
    }
  };

  // 隧道流量重置相关函数
  const handleResetTunnelFlow = (userTunnel: UserTunnel) => {
    setTunnelToReset(userTunnel);
    onResetTunnelFlowModalOpen();
  };

  const handleConfirmResetTunnelFlow = async () => {
    if (!tunnelToReset) return;

    setResetTunnelFlowLoading(true);
    try {
      const response = await resetUserFlow({
        id: tunnelToReset.id,
        type: 2, // 2表示重置隧道流量
      });

      if (response.code === 0) {
        toast.success("隧道流量重置成功");
        onResetTunnelFlowModalClose();
        setTunnelToReset(null);
        if (currentUser) {
          loadUserTunnels(currentUser.id); // 重新加载隧道权限列表
        }
      } else {
        toast.error(response.msg || "重置失败");
      }
    } catch (error) {
      toast.error("重置失败");
    } finally {
      setResetTunnelFlowLoading(false);
    }
  };

  // 过滤数据
  const availableTunnels = tunnels.filter(
    (tunnel) => !userTunnels.some((ut) => ut.tunnelId === tunnel.id),
  );

  const availableSpeedLimits = speedLimits.filter(
    (speedLimit) => speedLimit.tunnelId === tunnelForm.tunnelId,
  );

  const editAvailableSpeedLimits = speedLimits.filter(
    (speedLimit) => speedLimit.tunnelId === editTunnelForm?.tunnelId,
  );

  const adminTunnels = tunnels.filter((tunnel) => tunnel.ownerRoleId !== 1);
  const adminNodes = nodes.filter((node) => node.ownerRoleId !== 1);
  const totalQuotaUnlimited =
    userForm.flowUnlimited ||
    userForm.tunnelPermissions.some((item) => item.flowUnlimited) ||
    userForm.nodePermissions.some((item) => item.flowUnlimited);
  const totalQuota =
    userForm.flow +
    userForm.tunnelPermissions.reduce((sum, item) => sum + item.flow, 0) +
    userForm.nodePermissions.reduce((sum, item) => sum + item.flow, 0);
  const totalForwardUnlimited =
    userForm.forwardUnlimited ||
    userForm.tunnelPermissions.some((item) => item.forwardUnlimited) ||
    userForm.nodePermissions.some((item) => item.forwardUnlimited);
  const totalForwardQuota =
    userForm.num +
    userForm.tunnelPermissions.reduce((sum, item) => sum + item.num, 0) +
    userForm.nodePermissions.reduce((sum, item) => sum + item.num, 0);
  const totalSharedPorts = userForm.portPermissions.reduce(
    (sum, item) => sum + Math.max(0, item.endPort - item.startPort + 1),
    0,
  );

  const openResourceEditor = (kind: ResourceKind, resourceId: number) => {
    const permission =
      kind === "tunnel"
        ? userForm.tunnelPermissions.find(
            (item) => item.tunnelId === resourceId,
          )
        : userForm.nodePermissions.find((item) => item.nodeId === resourceId);

    setResourceEditor({
      kind,
      resourceId,
      flow: permission?.flow ?? 100,
      flowUnlimited: permission?.flowUnlimited ?? false,
      num: permission?.num ?? 10,
      forwardUnlimited: permission?.forwardUnlimited ?? false,
      expTime: permission?.expTime ?? null,
      flowResetTime: permission?.flowResetTime ?? 0,
      speedId:
        kind === "tunnel" && permission && "speedId" in permission
          ? permission.speedId
          : null,
      status: permission?.status ?? 1,
    });
  };

  const openPortEditor = (permission?: UserPortProvision, poolId?: number) => {
    const pool = portPools.find(
      (item) => item.id === (permission?.poolId || poolId),
    );

    if (!pool) return toast.error("请先选择端口池");
    setPortEditor({
      id: permission?.id,
      poolId: pool.id,
      startPort: permission?.startPort ?? pool.startPort,
      endPort: permission?.endPort ?? pool.endPort,
    });
  };

  const savePortEditor = () => {
    if (!portEditor) return;
    const pool = portPools.find((item) => item.id === portEditor.poolId);

    if (!pool) return toast.error("端口池不存在");
    if (
      portEditor.startPort > portEditor.endPort ||
      portEditor.startPort < pool.startPort ||
      portEditor.endPort > pool.endPort
    ) {
      return toast.error(`授权范围必须位于 ${pool.startPort}-${pool.endPort}`);
    }
    const overlap = userForm.portPermissions.some(
      (item) =>
        item.id !== portEditor.id &&
        item.poolId === portEditor.poolId &&
        item.startPort <= portEditor.endPort &&
        portEditor.startPort <= item.endPort,
    );

    if (overlap) return toast.error("同一端口池的授权范围不能重叠");
    const next: UserPortProvision = {
      ...portEditor,
      poolName: pool.name,
      nodeName: pool.nodeName,
      publicHost: pool.publicHost,
      usedPorts:
        userForm.portPermissions.find((item) => item.id === portEditor.id)
          ?.usedPorts || 0,
    };

    setUserForm((prev) => ({
      ...prev,
      portPermissions: portEditor.id
        ? prev.portPermissions.map((item) =>
            item.id === portEditor.id ? next : item,
          )
        : [...prev.portPermissions, next],
    }));
    setPortEditor(null);
  };

  const removePortPermission = (permission: UserPortProvision) => {
    if ((permission.usedPorts || 0) > 0)
      return toast.error("该范围仍有内网映射，需先停止映射后才能取消分享");
    setUserForm((prev) => ({
      ...prev,
      portPermissions: prev.portPermissions.filter(
        (item) => item !== permission,
      ),
    }));
  };

  const saveResourceEditor = () => {
    if (!resourceEditor) return;
    const common = {
      flow: resourceEditor.flow,
      flowUnlimited: resourceEditor.flowUnlimited,
      num: resourceEditor.num,
      forwardUnlimited: resourceEditor.forwardUnlimited,
      expTime: resourceEditor.expTime,
      flowResetTime: resourceEditor.flowResetTime,
      status: resourceEditor.status,
    };

    setUserForm((prev) => {
      if (resourceEditor.kind === "tunnel") {
        const next: UserTunnelProvision = {
          ...common,
          tunnelId: resourceEditor.resourceId,
          speedId: resourceEditor.speedId,
        };
        const exists = prev.tunnelPermissions.some(
          (item) => item.tunnelId === resourceEditor.resourceId,
        );

        return {
          ...prev,
          tunnelPermissions: exists
            ? prev.tunnelPermissions.map((item) =>
                item.tunnelId === resourceEditor.resourceId ? next : item,
              )
            : [...prev.tunnelPermissions, next],
        };
      }
      const next: UserNodeProvision = {
        ...common,
        nodeId: resourceEditor.resourceId,
      };
      const exists = prev.nodePermissions.some(
        (item) => item.nodeId === resourceEditor.resourceId,
      );

      return {
        ...prev,
        nodePermissions: exists
          ? prev.nodePermissions.map((item) =>
              item.nodeId === resourceEditor.resourceId
                ? { ...item, ...next }
                : item,
            )
          : [...prev.nodePermissions, next],
      };
    });
    setResourceEditor(null);
    setPendingTunnelId(null);
    setPendingNodeId(null);
    setUserModalTab("resources");
  };

  const removeResource = (kind: ResourceKind, resourceId: number) => {
    setUserForm((prev) =>
      kind === "tunnel"
        ? {
            ...prev,
            tunnelPermissions: prev.tunnelPermissions.filter(
              (item) => item.tunnelId !== resourceId,
            ),
          }
        : {
            ...prev,
            nodePermissions: prev.nodePermissions.filter(
              (item) => item.nodeId !== resourceId,
            ),
          },
    );
  };

  const reloadProxyGrants = async () => {
    if (!userForm.id) return;
    const response = await getPrivateProxyGrants(userForm.id);

    if (response.code === 0) setProxyGrants(response.data || []);
    else toast.error(response.msg || "获取代理授权失败");
  };

  const editProxyGrant = (proxy: PrivateProxyItem) => {
    setProxyGrantEditor({
      id: proxy.id,
      name: proxy.name,
      nodeId: proxy.nodeId,
      proxyType: proxy.proxyType as GrantProxyType,
      listenPort: proxy.listenPort,
      authUsername: "",
      authPassword: "",
      cipher: "aes-256-gcm",
      realityPreset: "www.cloudflare.com",
      realityServerName: "www.cloudflare.com",
      flowLimit: proxy.flowLimit || 0,
      flowUnlimited: proxy.flowUnlimited === 1,
      flowResetDay: proxy.flowResetDay || 0,
      permanent: !proxy.expiresAt,
      expiresAt: proxy.expiresAt ? new Date(proxy.expiresAt) : null,
      speedUnlimited: !proxy.speedLimitMbps,
      speedLimitMbps: proxy.speedLimitMbps || 100,
    });
  };

  const saveProxyGrant = async () => {
    if (!proxyGrantEditor || !userForm.id) return;
    if (!proxyGrantEditor.permanent && !proxyGrantEditor.expiresAt)
      return toast.error("请选择代理到期时间");
    if (!proxyGrantEditor.flowUnlimited && proxyGrantEditor.flowLimit <= 0)
      return toast.error("流量额度必须大于 0");
    if (
      supportsGrantSpeedLimit(proxyGrantEditor.proxyType) &&
      !proxyGrantEditor.speedUnlimited &&
      proxyGrantEditor.speedLimitMbps <= 0
    )
      return toast.error("限速值必须大于 0");
    if (!proxyGrantEditor.id) {
      if (
        !proxyGrantEditor.name.trim() ||
        !proxyGrantEditor.nodeId ||
        proxyGrantEditor.listenPort < 1 ||
        proxyGrantEditor.listenPort > 65535
      ) {
        return toast.error("请填写代理名称、节点和有效端口");
      }
      if (
        (proxyGrantEditor.proxyType === "socks5" ||
          proxyGrantEditor.proxyType === "http") &&
        (proxyGrantEditor.authUsername.trim().length < 3 ||
          proxyGrantEditor.authPassword.length < 8)
      ) {
        return toast.error("用户名至少 3 位，密码至少 8 位");
      }
      if (
        proxyGrantEditor.proxyType === "shadowsocks" &&
        proxyGrantEditor.authPassword.length < 8
      ) {
        return toast.error("Shadowsocks 密码至少 8 位");
      }
      if (
        isAdvancedGrantProxy(proxyGrantEditor.proxyType) &&
        proxyGrantEditor.authPassword.length < 8
      ) {
        return toast.error(
          `${grantProxyLabels[proxyGrantEditor.proxyType]} 密钥至少 8 位`,
        );
      }
      if (proxyGrantEditor.proxyType === "vless_reality") {
        const serverName = proxyGrantEditor.realityServerName.trim();

        if (
          !/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/i.test(
            serverName,
          )
        ) {
          return toast.error(
            "请填写有效的 REALITY 伪装域名，不要包含协议、端口或路径",
          );
        }
      }
    }
    setProxyGrantSaving(true);
    try {
      const response = proxyGrantEditor.id
        ? await updatePrivateProxyGrant({
            id: proxyGrantEditor.id,
            flowLimit: proxyGrantEditor.flowLimit,
            flowUnlimited: proxyGrantEditor.flowUnlimited,
            flowResetDay: proxyGrantEditor.flowResetDay,
            permanent: proxyGrantEditor.permanent,
            expiresAt: proxyGrantEditor.expiresAt?.getTime(),
            speedLimitMbps:
              supportsGrantSpeedLimit(proxyGrantEditor.proxyType) &&
              !proxyGrantEditor.speedUnlimited
                ? proxyGrantEditor.speedLimitMbps
                : undefined,
          })
        : await createPrivateProxyGrant({
            targetUserId: userForm.id,
            name: proxyGrantEditor.name.trim(),
            nodeId: proxyGrantEditor.nodeId!,
            proxyType: proxyGrantEditor.proxyType,
            listenPort: proxyGrantEditor.listenPort,
            authUsername: proxyGrantEditor.authUsername.trim(),
            authPassword: proxyGrantEditor.authPassword,
            cipher: proxyGrantEditor.cipher,
            realityServerName: proxyGrantEditor.realityServerName.trim(),
            permanent: proxyGrantEditor.permanent,
            expiresAt: proxyGrantEditor.expiresAt?.getTime(),
            flowLimit: proxyGrantEditor.flowLimit,
            flowUnlimited: proxyGrantEditor.flowUnlimited,
            flowResetDay: proxyGrantEditor.flowResetDay,
            speedLimitMbps:
              supportsGrantSpeedLimit(proxyGrantEditor.proxyType) &&
              !proxyGrantEditor.speedUnlimited
                ? proxyGrantEditor.speedLimitMbps
                : undefined,
          });

      if (response.code !== 0)
        return toast.error(response.msg || "保存代理授权失败");
      toast.success(proxyGrantEditor.id ? "代理授权已更新" : "代理授权已创建");
      setProxyGrantEditor(null);
      await reloadProxyGrants();
    } finally {
      setProxyGrantSaving(false);
    }
  };

  const removeProxyGrant = async (proxy: PrivateProxyItem) => {
    if (!window.confirm(`确定删除代理授权“${proxy.name}”吗？`)) return;
    const response = await deletePrivateProxy(proxy.id);

    if (response.code === 0) {
      toast.success(response.msg || "代理授权已删除");
      await reloadProxyGrants();
    } else toast.error(response.msg || "删除代理授权失败");
  };

  const resetProxyGrantFlow = async (proxy: PrivateProxyItem) => {
    const response = await resetPrivateProxyGrantFlow(proxy.id);

    if (response.code === 0) {
      toast.success("代理流量已重置");
      await reloadProxyGrants();
    } else toast.error(response.msg || "重置代理流量失败");
  };

  const resourceEditorName =
    resourceEditor?.kind === "tunnel"
      ? adminTunnels.find((item) => item.id === resourceEditor.resourceId)?.name
      : adminNodes.find((item) => item.id === resourceEditor?.resourceId)?.name;

  return (
    <div className="px-3 lg:px-6 py-8">
      {/* 页面头部 */}
      <div className="flex flex-col gap-4 mb-6">
        <div className="flex items-center gap-3" />

        <div className="flex flex-col sm:flex-row gap-3 items-stretch sm:items-center justify-between">
          <div className="flex items-center gap-3 flex-1 max-w-md">
            <Input
              className="flex-1"
              classNames={{
                base: "bg-default-100",
                input: "bg-transparent",
                inputWrapper:
                  "bg-default-100 border-2 border-default-200 hover:border-default-300 focus-within:border-primary data-[hover=true]:border-default-300",
              }}
              placeholder="搜索用户名"
              startContent={<SearchIcon className="w-4 h-4 text-default-400" />}
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleSearch()}
            />
            <Button
              isIconOnly
              className="min-h-10 w-10"
              color="primary"
              variant="solid"
              onClick={handleSearch}
            >
              <SearchIcon className="w-4 h-4" />
            </Button>
          </div>

          <Button color="primary" variant="flat" onPress={handleAdd}>
            新增
          </Button>
        </div>
      </div>

      {/* 用户列表 */}
      {loading ? (
        <div className="flex items-center justify-center h-64">
          <div className="flex items-center gap-3">
            <Spinner size="sm" />
            <span className="text-default-600">正在加载...</span>
          </div>
        </div>
      ) : users.length === 0 ? (
        <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
          <CardBody className="text-center py-16">
            <div className="flex flex-col items-center gap-4">
              <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                <UserIcon className="w-8 h-8 text-default-400" />
              </div>
              <div>
                <h3 className="text-lg font-semibold text-foreground">
                  暂无用户数据
                </h3>
                <p className="text-default-500 text-sm mt-1">
                  还没有创建任何用户，点击上方按钮开始创建
                </p>
              </div>
            </div>
          </CardBody>
        </Card>
      ) : (
        <SortableCardGrid
          getId={(user) => user.id}
          items={orderedUsers}
          renderItem={(user, dragHandle) => {
            const userStatus = getUserStatus(user);
            const expStatus = user.expTime
              ? getExpireStatus(user.expTime)
              : null;
            const usedFlow =
              user.totalUsedFlow ?? calculateUserTotalUsedFlow(user);
            const displayFlow = user.totalFlow ?? user.flow;
            const flowPercent =
              !user.totalFlowUnlimited && displayFlow > 0
                ? Math.min(
                    (usedFlow / (displayFlow * 1024 * 1024 * 1024)) * 100,
                    100,
                  )
                : 0;

            return (
              <Card className="shadow-sm border border-divider hover:shadow-md transition-shadow duration-200 h-full">
                <CardHeader className="pb-2">
                  <div className="flex justify-between items-start w-full">
                    <div className="flex-1 min-w-0">
                      <h3 className="font-semibold text-foreground truncate text-sm">
                        {user.name || user.user}
                      </h3>
                      <p className="text-xs text-default-500 truncate">
                        @{user.user}
                      </p>
                    </div>
                    <div className="flex items-center gap-1.5 ml-2">
                      {dragHandle}
                      <Chip
                        className="text-xs"
                        color={userStatus.color}
                        size="sm"
                        variant="flat"
                      >
                        {userStatus.text}
                      </Chip>
                    </div>
                  </div>
                </CardHeader>

                <CardBody className="pt-0 pb-3">
                  <div className="space-y-2">
                    {/* 流量信息 */}
                    <div className="space-y-1.5">
                      <div className="flex justify-between text-sm">
                        <span className="text-default-600">汇总流量额度</span>
                        <span className="font-medium text-xs">
                          {user.totalFlowUnlimited
                            ? "无限制"
                            : formatFlow(displayFlow, "gb")}
                        </span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-default-600">已使用</span>
                        <span className="font-medium text-xs text-danger">
                          {formatFlow(usedFlow)}
                        </span>
                      </div>
                      {!user.totalFlowUnlimited && (
                        <Progress
                          aria-label={`流量使用 ${flowPercent.toFixed(1)}%`}
                          className="mt-1"
                          color={
                            flowPercent > 90
                              ? "danger"
                              : flowPercent > 70
                                ? "warning"
                                : "success"
                          }
                          size="sm"
                          value={flowPercent}
                        />
                      )}
                    </div>

                    {/* 其他信息 */}
                    <div className="space-y-1.5 pt-2 border-t border-divider">
                      <div className="flex justify-between text-sm">
                        <span className="text-default-600">
                          全部资源转发名额
                        </span>
                        <span className="font-medium text-xs">
                          {user.totalNumUnlimited
                            ? "无限制"
                            : (user.totalNum ?? user.num)}
                        </span>
                      </div>
                      <div className="flex justify-between gap-3 text-sm">
                        <span className="text-default-600">已分配资源</span>
                        <span className="text-right text-xs font-medium">
                          隧道 {user.tunnelPermissionCount || 0} · 节点{" "}
                          {user.nodePermissionCount || 0} · 端口段{" "}
                          {user.portPermissionCount || 0}
                        </span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-default-600">重置日期</span>
                        <span className="text-xs">
                          {user.flowResetTime === 0
                            ? "不重置"
                            : `每月${user.flowResetTime}号`}
                        </span>
                      </div>
                      {user.expTime && (
                        <div className="flex justify-between text-sm">
                          <span className="text-default-600">过期时间</span>
                          <div className="text-right">
                            {expStatus && expStatus.color === "success" ? (
                              <div className="text-xs">
                                {formatDate(user.expTime)}
                              </div>
                            ) : (
                              <Chip
                                className="text-xs"
                                color={expStatus?.color || "default"}
                                size="sm"
                                variant="flat"
                              >
                                {expStatus?.text || "未知状态"}
                              </Chip>
                            )}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>

                  <div className="grid grid-cols-3 gap-1.5 mt-3">
                    <Button
                      className="min-h-8 min-w-0 px-2"
                      color="primary"
                      size="sm"
                      startContent={<EditIcon className="w-3 h-3" />}
                      variant="flat"
                      onPress={() => handleEdit(user)}
                    >
                      编辑
                    </Button>
                    <Button
                      className="min-h-8 min-w-0 px-2"
                      color="warning"
                      size="sm"
                      startContent={
                        <svg
                          className="w-3 h-3"
                          fill="currentColor"
                          viewBox="0 0 20 20"
                        >
                          <path
                            clipRule="evenodd"
                            d="M4 2a1 1 0 011 1v2.101a7.002 7.002 0 0111.601 2.566 1 1 0 11-1.885.666A5.002 5.002 0 005.999 7H9a1 1 0 010 2H4a1 1 0 01-1-1V3a1 1 0 011-1zm.008 9.057a1 1 0 011.276.61A5.002 5.002 0 0014.001 13H11a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0v-2.101a7.002 7.002 0 01-11.601-2.566 1 1 0 01.61-1.276z"
                            fillRule="evenodd"
                          />
                        </svg>
                      }
                      variant="flat"
                      onPress={() => handleResetFlow(user)}
                    >
                      重置
                    </Button>
                    <Button
                      className="min-h-8 min-w-0 px-2"
                      color="danger"
                      size="sm"
                      startContent={<DeleteIcon className="w-3 h-3" />}
                      variant="flat"
                      onPress={() => handleDelete(user)}
                    >
                      删除
                    </Button>
                  </div>
                </CardBody>
              </Card>
            );
          }}
          onMove={userCardOrder.moveCard}
        />
      )}

      {/* 用户表单模态框 */}
      <Modal
        backdrop="blur"
        classNames={{ base: "max-w-[96vw] h-[88vh]" }}
        isOpen={isUserModalOpen}
        placement="center"
        scrollBehavior="inside"
        size="5xl"
        onClose={onUserModalClose}
      >
        <ModalContent>
          <ModalHeader className="flex flex-col gap-3 border-b border-divider">
            <span>{isEdit ? "编辑用户与资源额度" : "新增用户与资源额度"}</span>
            <div className="grid w-full grid-cols-2 gap-2 text-xs font-normal sm:grid-cols-3 lg:grid-cols-6">
              <div className="rounded-md bg-default-100 px-3 py-2">
                <span className="text-default-500">汇总流量</span>
                <p className="mt-0.5 font-semibold text-foreground">
                  {totalQuotaUnlimited ? "无限制" : `${totalQuota} GB`}
                </p>
              </div>
              <div className="rounded-md bg-default-100 px-3 py-2">
                <span className="text-default-500">汇总转发名额</span>
                <p className="mt-0.5 font-semibold text-foreground">
                  {totalForwardUnlimited ? "无限制" : `${totalForwardQuota} 个`}
                </p>
              </div>
              <div className="rounded-md bg-default-100 px-3 py-2">
                <span className="text-default-500">共享隧道</span>
                <p className="mt-0.5 font-semibold text-foreground">
                  {userForm.tunnelPermissions.length} 条
                </p>
              </div>
              <div className="rounded-md bg-default-100 px-3 py-2">
                <span className="text-default-500">共享节点</span>
                <p className="mt-0.5 font-semibold text-foreground">
                  {userForm.nodePermissions.length} 台
                </p>
              </div>
              <div className="rounded-md bg-default-100 px-3 py-2">
                <span className="text-default-500">共享端口</span>
                <p className="mt-0.5 font-semibold text-foreground">
                  {totalSharedPorts} 个
                </p>
              </div>
              <div className="rounded-md bg-default-100 px-3 py-2">
                <span className="text-default-500">代理授权</span>
                <p className="mt-0.5 font-semibold text-foreground">
                  {proxyGrants.length} 个
                </p>
              </div>
            </div>
          </ModalHeader>
          <ModalBody className="py-4">
            <Tabs
              aria-label="用户资源配置"
              classNames={{ panel: "pt-4" }}
              selectedKey={userModalTab}
              variant="underlined"
              onSelectionChange={(key) => setUserModalTab(String(key))}
            >
              <Tab key="resources" title="账号与资源">
                <div className="space-y-5">
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <Input
                      isRequired
                      label="用户名"
                      value={userForm.user}
                      onChange={(e) =>
                        setUserForm((prev) => ({
                          ...prev,
                          user: e.target.value,
                        }))
                      }
                    />
                    <Input
                      isRequired={!isEdit}
                      label="密码"
                      placeholder={isEdit ? "留空则不修改密码" : "请输入密码"}
                      type="password"
                      value={userForm.pwd}
                      onChange={(e) =>
                        setUserForm((prev) => ({
                          ...prev,
                          pwd: e.target.value,
                        }))
                      }
                    />
                  </div>

                  <section className="space-y-3 border-t border-divider pt-4">
                    <div className="flex items-center justify-between gap-3">
                      <div>
                        <h3 className="text-sm font-semibold">共享隧道</h3>
                        <p className="mt-1 text-xs text-default-500">
                          {userForm.tunnelPermissions.length} 条已选择
                        </p>
                      </div>
                      <Chip color="primary" size="sm" variant="flat">
                        隧道额度独立计算
                      </Chip>
                    </div>
                    <div className="flex flex-col sm:flex-row gap-2">
                      <Select
                        className="flex-1"
                        label="选择要添加的隧道"
                        placeholder="请选择隧道"
                        selectedKeys={
                          pendingTunnelId ? [pendingTunnelId.toString()] : []
                        }
                        onSelectionChange={(keys) =>
                          setPendingTunnelId(
                            Number(Array.from(keys)[0]) || null,
                          )
                        }
                      >
                        {adminTunnels
                          .filter(
                            (tunnel) =>
                              !userForm.tunnelPermissions.some(
                                (item) => item.tunnelId === tunnel.id,
                              ),
                          )
                          .map((tunnel) => (
                            <SelectItem
                              key={tunnel.id.toString()}
                              textValue={tunnel.name}
                            >
                              {tunnel.name} ·{" "}
                              {tunnel.flow === 1 ? "单向计费" : "双向计费"}
                            </SelectItem>
                          ))}
                      </Select>
                      <Button
                        className="sm:self-end sm:min-w-28"
                        color="primary"
                        isDisabled={!pendingTunnelId}
                        onPress={() =>
                          pendingTunnelId &&
                          openResourceEditor("tunnel", pendingTunnelId)
                        }
                      >
                        添加隧道
                      </Button>
                    </div>
                    {userForm.tunnelPermissions.length === 0 ? (
                      <div className="rounded-md border border-dashed border-divider px-4 py-6 text-center text-sm text-default-500">
                        尚未分配隧道
                      </div>
                    ) : (
                      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
                        {userForm.tunnelPermissions.map((permission) => {
                          const tunnel = adminTunnels.find(
                            (item) => item.id === permission.tunnelId,
                          );

                          return (
                            <div
                              key={permission.tunnelId}
                              className="flex items-center justify-between gap-3 rounded-md border border-divider px-3 py-3"
                            >
                              <div className="min-w-0">
                                <div className="flex items-center gap-2">
                                  <p className="truncate text-sm font-medium">
                                    {tunnel?.name ||
                                      `隧道 ${permission.tunnelId}`}
                                  </p>
                                  <Chip size="sm" variant="flat">
                                    {tunnel?.flow === 1 ? "单向" : "双向"}
                                  </Chip>
                                </div>
                                <p className="mt-1 text-xs text-default-500">
                                  {permission.flowUnlimited
                                    ? "流量不限"
                                    : `${permission.flow} GB`}{" "}
                                  ·{" "}
                                  {permission.forwardUnlimited
                                    ? "转发不限"
                                    : `${permission.num} 个转发`}{" "}
                                  ·{" "}
                                  {permission.flowResetTime
                                    ? `每月 ${permission.flowResetTime} 日重置`
                                    : "不重置"}
                                </p>
                                <p className="mt-1 text-xs text-default-500">
                                  该用户限速：
                                  {permission.speedId
                                    ? `${speedLimits.find((item) => item.id === permission.speedId)?.name || "已配置"}`
                                    : "不限速"}{" "}
                                  · 不影响管理员使用
                                </p>
                              </div>
                              <div className="flex shrink-0 gap-1">
                                <Button
                                  isIconOnly
                                  aria-label="配置隧道额度"
                                  color="primary"
                                  size="sm"
                                  title="配置隧道额度"
                                  variant="light"
                                  onPress={() =>
                                    openResourceEditor(
                                      "tunnel",
                                      permission.tunnelId,
                                    )
                                  }
                                >
                                  <EditIcon className="h-4 w-4" />
                                </Button>
                                <Button
                                  isIconOnly
                                  aria-label="移除隧道"
                                  color="danger"
                                  size="sm"
                                  title="移除隧道"
                                  variant="light"
                                  onPress={() =>
                                    removeResource(
                                      "tunnel",
                                      permission.tunnelId,
                                    )
                                  }
                                >
                                  <DeleteIcon className="h-4 w-4" />
                                </Button>
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </section>

                  <section className="space-y-3 border-t border-divider pt-4">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div>
                        <h3 className="text-sm font-semibold">私人代理授权</h3>
                        <p className="mt-1 text-xs text-default-500">
                          {proxyGrants.length} 个独立代理实例
                        </p>
                      </div>
                      <Button
                        color="success"
                        isDisabled={!isEdit || !userForm.id}
                        size="sm"
                        variant="flat"
                        onPress={() => setProxyGrantEditor(emptyProxyGrant())}
                      >
                        新增代理授权
                      </Button>
                    </div>
                    {!isEdit && (
                      <div className="rounded-md border border-dashed border-divider px-4 py-5 text-center text-sm text-default-500">
                        创建用户并保存后，可在编辑页面分配私人代理。
                      </div>
                    )}
                    {isEdit && proxyGrants.length === 0 && (
                      <div className="rounded-md border border-dashed border-divider px-4 py-5 text-center text-sm text-default-500">
                        尚未授权私人代理
                      </div>
                    )}
                    {isEdit && proxyGrants.length > 0 && (
                      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
                        {proxyGrants.map((proxy) => {
                          const used =
                            (proxy.inFlow || 0) + (proxy.outFlow || 0);
                          const quota =
                            (proxy.flowLimit || 0) * 1024 * 1024 * 1024;
                          const stateColor = proxy.available
                            ? "success"
                            : proxy.state === "paused"
                              ? "warning"
                              : "danger";

                          return (
                            <div
                              key={proxy.id}
                              className="rounded-md border border-divider px-3 py-3"
                            >
                              <div className="flex items-start justify-between gap-3">
                                <div className="min-w-0">
                                  <div className="flex flex-wrap items-center gap-2">
                                    <p className="truncate text-sm font-medium">
                                      {proxy.name}
                                    </p>
                                    <Chip
                                      color={stateColor}
                                      size="sm"
                                      variant="flat"
                                    >
                                      {proxy.available
                                        ? "可用"
                                        : proxy.unavailableReason || "不可用"}
                                    </Chip>
                                  </div>
                                  <p className="mt-1 truncate text-xs text-default-500">
                                    {proxy.nodeName} ·{" "}
                                    {grantProxyLabels[proxy.proxyType]} ·{" "}
                                    {proxy.publicHost}:{proxy.listenPort}
                                  </p>
                                </div>
                                <div className="flex shrink-0 gap-1">
                                  <Button
                                    isIconOnly
                                    aria-label="编辑代理授权"
                                    color="primary"
                                    size="sm"
                                    title="编辑代理授权"
                                    variant="light"
                                    onPress={() => editProxyGrant(proxy)}
                                  >
                                    <EditIcon className="h-4 w-4" />
                                  </Button>
                                  <Button
                                    isIconOnly
                                    aria-label="重置代理流量"
                                    color="warning"
                                    size="sm"
                                    title="重置代理流量"
                                    variant="light"
                                    onPress={() => resetProxyGrantFlow(proxy)}
                                  >
                                    <RotateCcw className="h-4 w-4" />
                                  </Button>
                                  <Button
                                    isIconOnly
                                    aria-label="删除代理授权"
                                    color="danger"
                                    size="sm"
                                    title="删除代理授权"
                                    variant="light"
                                    onPress={() => removeProxyGrant(proxy)}
                                  >
                                    <DeleteIcon className="h-4 w-4" />
                                  </Button>
                                </div>
                              </div>
                              <div className="mt-3 grid grid-cols-2 gap-2 text-xs sm:grid-cols-4">
                                <div>
                                  <p className="text-default-500">流量</p>
                                  <p className="mt-1 font-medium">
                                    {proxy.flowUnlimited === 1
                                      ? "无限制"
                                      : `${formatFlow(used)} / ${proxy.flowLimit || 0} GB`}
                                  </p>
                                </div>
                                <div>
                                  <p className="text-default-500">限速</p>
                                  <p className="mt-1 font-medium">
                                    {proxy.speedLimitSupported === false
                                      ? "协议不支持"
                                      : proxy.speedLimitMbps
                                        ? `${proxy.speedLimitMbps} Mbps`
                                        : "不限速"}
                                  </p>
                                </div>
                                <div>
                                  <p className="text-default-500">重置</p>
                                  <p className="mt-1 font-medium">
                                    {proxy.flowResetDay
                                      ? `每月 ${proxy.flowResetDay} 日`
                                      : "不重置"}
                                  </p>
                                </div>
                                <div>
                                  <p className="text-default-500">到期</p>
                                  <p className="mt-1 font-medium">
                                    {proxy.expiresAt
                                      ? new Date(
                                          proxy.expiresAt,
                                        ).toLocaleDateString()
                                      : "永久"}
                                  </p>
                                </div>
                              </div>
                              {proxy.flowUnlimited !== 1 && (
                                <Progress
                                  className="mt-3"
                                  color={proxy.available ? "primary" : "danger"}
                                  size="sm"
                                  value={
                                    quota > 0
                                      ? Math.min(100, (used / quota) * 100)
                                      : 100
                                  }
                                />
                              )}
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </section>

                  <section className="space-y-3 border-t border-divider pt-4">
                    <div className="flex items-center justify-between gap-3">
                      <div>
                        <h3 className="text-sm font-semibold">共享节点</h3>
                        <p className="mt-1 text-xs text-default-500">
                          {userForm.nodePermissions.length} 台已选择
                        </p>
                      </div>
                      <Chip color="secondary" size="sm" variant="flat">
                        只读共享节点
                      </Chip>
                    </div>
                    <div className="flex flex-col sm:flex-row gap-2">
                      <Select
                        className="flex-1"
                        label="选择要添加的节点"
                        placeholder="请选择节点"
                        selectedKeys={
                          pendingNodeId ? [pendingNodeId.toString()] : []
                        }
                        onSelectionChange={(keys) =>
                          setPendingNodeId(Number(Array.from(keys)[0]) || null)
                        }
                      >
                        {adminNodes
                          .filter(
                            (node) =>
                              !userForm.nodePermissions.some(
                                (item) => item.nodeId === node.id,
                              ),
                          )
                          .map((node) => (
                            <SelectItem
                              key={node.id.toString()}
                              textValue={node.name}
                            >
                              {node.name} · {node.serverIp}
                            </SelectItem>
                          ))}
                      </Select>
                      <Button
                        className="sm:self-end sm:min-w-28"
                        color="secondary"
                        isDisabled={!pendingNodeId}
                        onPress={() =>
                          pendingNodeId &&
                          openResourceEditor("node", pendingNodeId)
                        }
                      >
                        添加节点
                      </Button>
                    </div>
                    {userForm.nodePermissions.length === 0 ? (
                      <div className="rounded-md border border-dashed border-divider px-4 py-6 text-center text-sm text-default-500">
                        尚未分配节点
                      </div>
                    ) : (
                      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
                        {userForm.nodePermissions.map((permission) => {
                          const node = adminNodes.find(
                            (item) => item.id === permission.nodeId,
                          );

                          return (
                            <div
                              key={permission.nodeId}
                              className="flex items-center justify-between gap-3 rounded-md border border-divider px-3 py-3"
                            >
                              <div className="min-w-0">
                                <p className="truncate text-sm font-medium">
                                  {node?.name || `节点 ${permission.nodeId}`}
                                </p>
                                <p className="mt-1 truncate text-xs text-default-500">
                                  {node?.serverIp || "节点地址不可见"} ·{" "}
                                  {permission.flowUnlimited
                                    ? "流量不限"
                                    : `${permission.flow} GB`}{" "}
                                  ·{" "}
                                  {permission.forwardUnlimited
                                    ? "名额不限"
                                    : `名额 ${permission.usedForwards || 0} / ${permission.num}`}
                                </p>
                                <p className="mt-1 truncate text-xs text-default-500">
                                  {permission.flowResetTime
                                    ? `每月 ${permission.flowResetTime} 日重置`
                                    : "不重置"}{" "}
                                  · 创建转发时按经过的节点扣除名额
                                </p>
                              </div>
                              <div className="flex shrink-0 gap-1">
                                <Button
                                  isIconOnly
                                  aria-label="配置节点额度"
                                  color="primary"
                                  size="sm"
                                  title="配置节点额度"
                                  variant="light"
                                  onPress={() =>
                                    openResourceEditor(
                                      "node",
                                      permission.nodeId,
                                    )
                                  }
                                >
                                  <EditIcon className="h-4 w-4" />
                                </Button>
                                <Button
                                  isIconOnly
                                  aria-label="移除节点"
                                  color="danger"
                                  size="sm"
                                  title="移除节点"
                                  variant="light"
                                  onPress={() =>
                                    removeResource("node", permission.nodeId)
                                  }
                                >
                                  <DeleteIcon className="h-4 w-4" />
                                </Button>
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </section>

                  <section className="space-y-3 border-t border-divider pt-4">
                    <div className="flex items-center justify-between gap-3">
                      <div>
                        <h3 className="text-sm font-semibold">共享端口资源</h3>
                        <p className="mt-1 text-xs text-default-500">
                          {userForm.portPermissions.length} 段 · 共{" "}
                          {totalSharedPorts} 个端口
                        </p>
                      </div>
                      <Chip color="warning" size="sm" variant="flat">
                        全局独占
                      </Chip>
                    </div>
                    <div className="flex flex-col gap-2 sm:flex-row">
                      <Select
                        className="flex-1"
                        label="选择端口池"
                        placeholder="请选择端口池"
                        selectedKeys={
                          pendingPortPoolId ? [String(pendingPortPoolId)] : []
                        }
                        onSelectionChange={(keys) =>
                          setPendingPortPoolId(
                            Number(Array.from(keys)[0]) || null,
                          )
                        }
                      >
                        {portPools.map((pool) => (
                          <SelectItem
                            key={String(pool.id)}
                            textValue={pool.name}
                          >
                            {pool.name} · {pool.nodeName} · {pool.startPort}-
                            {pool.endPort} · 管理员可用 {pool.availablePorts}
                          </SelectItem>
                        ))}
                      </Select>
                      <Button
                        className="sm:self-end sm:min-w-28"
                        color="warning"
                        isDisabled={!pendingPortPoolId}
                        variant="flat"
                        onPress={() =>
                          pendingPortPoolId &&
                          openPortEditor(undefined, pendingPortPoolId)
                        }
                      >
                        添加范围
                      </Button>
                    </div>
                    {userForm.portPermissions.length === 0 ? (
                      <div className="rounded-md border border-dashed border-divider px-4 py-6 text-center text-sm text-default-500">
                        尚未分配端口资源
                      </div>
                    ) : (
                      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
                        {userForm.portPermissions.map((permission, index) => {
                          const pool = portPools.find(
                            (item) => item.id === permission.poolId,
                          );
                          const total =
                            permission.endPort - permission.startPort + 1;

                          return (
                            <div
                              key={
                                permission.id || `${permission.poolId}-${index}`
                              }
                              className="flex items-center justify-between gap-3 rounded-md border border-divider px-3 py-3"
                            >
                              <div className="min-w-0">
                                <p className="truncate text-sm font-medium">
                                  {permission.poolName ||
                                    pool?.name ||
                                    `端口池 ${permission.poolId}`}
                                </p>
                                <p className="mt-1 truncate font-mono text-xs text-default-500">
                                  {permission.publicHost || pool?.publicHost} ·{" "}
                                  {permission.startPort}-{permission.endPort}
                                </p>
                                <p className="mt-1 text-xs text-default-500">
                                  {total} 个端口 · 已使用{" "}
                                  {permission.usedPorts || 0} · 剩余{" "}
                                  {Math.max(
                                    0,
                                    total - (permission.usedPorts || 0),
                                  )}
                                </p>
                              </div>
                              <div className="flex shrink-0 gap-1">
                                <Button
                                  isIconOnly
                                  aria-label="编辑端口范围"
                                  color="primary"
                                  size="sm"
                                  variant="light"
                                  onPress={() => openPortEditor(permission)}
                                >
                                  <EditIcon className="h-4 w-4" />
                                </Button>
                                <Button
                                  isIconOnly
                                  aria-label="移除端口范围"
                                  color="danger"
                                  size="sm"
                                  variant="light"
                                  onPress={() =>
                                    removePortPermission(permission)
                                  }
                                >
                                  <DeleteIcon className="h-4 w-4" />
                                </Button>
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                    <p className="text-xs text-default-500">
                      分享后管理员不能再使用该范围；范围内已有服务时不能分享，也不能收回正在使用的范围。
                    </p>
                  </section>
                </div>
              </Tab>

              <Tab key="advanced" title="高级设置">
                <div className="space-y-5">
                  <section className="rounded-md border border-divider p-4 space-y-4">
                    <div>
                      <h3 className="text-sm font-semibold">用户自有资源</h3>
                      <p className="mt-1 text-xs text-default-500">
                        仅计算用户自己添加的节点和完全自建的隧道。
                      </p>
                    </div>
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <Switch
                          isSelected={userForm.flowUnlimited}
                          size="sm"
                          onValueChange={(value) =>
                            setUserForm((prev) => ({
                              ...prev,
                              flowUnlimited: value,
                            }))
                          }
                        >
                          流量无限制
                        </Switch>
                        <Input
                          isDisabled={userForm.flowUnlimited}
                          label="自有资源流量 (GB)"
                          type="number"
                          value={userForm.flow.toString()}
                          onChange={(e) =>
                            setUserForm((prev) => ({
                              ...prev,
                              flow: Math.max(0, Number(e.target.value) || 0),
                            }))
                          }
                        />
                      </div>
                      <div className="space-y-2">
                        <Switch
                          isSelected={userForm.forwardUnlimited}
                          size="sm"
                          onValueChange={(value) =>
                            setUserForm((prev) => ({
                              ...prev,
                              forwardUnlimited: value,
                            }))
                          }
                        >
                          转发名额无限制
                        </Switch>
                        <Input
                          isDisabled={userForm.forwardUnlimited}
                          label="自有资源转发名额"
                          type="number"
                          value={userForm.num.toString()}
                          onChange={(e) =>
                            setUserForm((prev) => ({
                              ...prev,
                              num: Math.max(0, Number(e.target.value) || 0),
                            }))
                          }
                        />
                      </div>
                    </div>
                  </section>
                  <section className="rounded-md border border-divider p-4 space-y-4">
                    <h3 className="text-sm font-semibold">账户规则</h3>
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <Select
                        label="自有资源流量重置"
                        selectedKeys={[String(userForm.flowResetTime ?? 0)]}
                        onSelectionChange={(keys) =>
                          setUserForm((prev) => ({
                            ...prev,
                            flowResetTime: Number(Array.from(keys)[0] ?? 0),
                          }))
                        }
                      >
                        {FLOW_RESET_OPTIONS.map((option) => (
                          <SelectItem key={option.key} textValue={option.label}>
                            {option.label}
                          </SelectItem>
                        ))}
                      </Select>
                      <div className="space-y-2">
                        <Switch
                          isSelected={userForm.expTime === null}
                          size="sm"
                          onValueChange={(unlimited) =>
                            setUserForm((prev) => ({
                              ...prev,
                              expTime: unlimited
                                ? null
                                : new Date(Date.now() + 30 * 86400000),
                            }))
                          }
                        >
                          账户永不过期
                        </Switch>
                        {userForm.expTime && (
                          <DatePicker
                            showMonthAndYearPickers
                            label="账户过期时间"
                            value={
                              parseDate(
                                userForm.expTime.toISOString().split("T")[0],
                              ) as any
                            }
                            onChange={(date) =>
                              date &&
                              setUserForm((prev) => ({
                                ...prev,
                                expTime: new Date(
                                  date.year,
                                  date.month - 1,
                                  date.day,
                                  23,
                                  59,
                                  59,
                                ),
                              }))
                            }
                          />
                        )}
                      </div>
                    </div>
                    <RadioGroup
                      label="账户状态"
                      orientation="horizontal"
                      value={userForm.status.toString()}
                      onValueChange={(value: string) =>
                        setUserForm((prev) => ({
                          ...prev,
                          status: Number(value),
                        }))
                      }
                    >
                      <Radio value="1">正常</Radio>
                      <Radio value="0">禁用</Radio>
                    </RadioGroup>
                  </section>
                  <div className="rounded-md border border-primary-200 bg-primary-50 px-4 py-3 text-xs text-primary-700 dark:border-primary-800 dark:bg-primary-950/30 dark:text-primary-300">
                    计费规则：单向隧道只统计上传流量；双向隧道统计上传与下载流量；流量倍率只计算一次。共享资源分别计量，任一资源用尽只影响依赖该资源的转发。
                  </div>
                </div>
              </Tab>
            </Tabs>
          </ModalBody>
          <ModalFooter>
            <Button onPress={onUserModalClose}>取消</Button>
            <Button
              color="primary"
              isLoading={userFormLoading}
              onPress={handleSubmitUser}
            >
              {isEdit ? "保存全部配置" : "创建并分配资源"}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        backdrop="opaque"
        classNames={{ base: "max-w-[94vw]" }}
        isOpen={proxyGrantEditor !== null}
        scrollBehavior="inside"
        size="3xl"
        onClose={() => setProxyGrantEditor(null)}
      >
        <ModalContent>
          <ModalHeader className="border-b border-divider">
            {proxyGrantEditor?.id ? "编辑私人代理授权" : "新增私人代理授权"}
          </ModalHeader>
          <ModalBody className="py-5">
            {proxyGrantEditor && (
              <div className="space-y-5">
                {proxyGrantEditor.id ? (
                  <div className="grid grid-cols-2 gap-3 rounded-md bg-default-100 px-4 py-3 text-sm sm:grid-cols-4">
                    <div>
                      <p className="text-xs text-default-500">代理</p>
                      <p className="mt-1 truncate font-medium">
                        {proxyGrantEditor.name}
                      </p>
                    </div>
                    <div>
                      <p className="text-xs text-default-500">协议</p>
                      <p className="mt-1 font-medium">
                        {grantProxyLabels[proxyGrantEditor.proxyType]}
                      </p>
                    </div>
                    <div>
                      <p className="text-xs text-default-500">节点</p>
                      <p className="mt-1 truncate font-medium">
                        {
                          adminNodes.find(
                            (node) => node.id === proxyGrantEditor.nodeId,
                          )?.name
                        }
                      </p>
                    </div>
                    <div>
                      <p className="text-xs text-default-500">端口</p>
                      <p className="mt-1 font-mono font-medium">
                        {proxyGrantEditor.listenPort}
                      </p>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                      <Input
                        label="授权代理名称"
                        value={proxyGrantEditor.name}
                        onValueChange={(value) =>
                          setProxyGrantEditor((prev) =>
                            prev ? { ...prev, name: value } : prev,
                          )
                        }
                      />
                      <Select
                        label="服务器节点"
                        selectedKeys={
                          proxyGrantEditor.nodeId
                            ? [String(proxyGrantEditor.nodeId)]
                            : []
                        }
                        onSelectionChange={(keys) =>
                          setProxyGrantEditor((prev) =>
                            prev
                              ? {
                                  ...prev,
                                  nodeId: Number(Array.from(keys)[0]) || null,
                                }
                              : prev,
                          )
                        }
                      >
                        {adminNodes
                          .filter((node) => node.status === 1)
                          .map((node) => (
                            <SelectItem
                              key={String(node.id)}
                              textValue={node.name}
                            >
                              {node.name} · Agent {node.version || "未知"} ·{" "}
                              {node.serverIp || node.ip || "未设置地址"} ·{" "}
                              {node.portSta || 1}-{node.portEnd || 65535}
                            </SelectItem>
                          ))}
                      </Select>
                      <Select
                        label="代理协议"
                        selectedKeys={[proxyGrantEditor.proxyType]}
                        onSelectionChange={(keys) => {
                          const proxyType = String(
                            Array.from(keys)[0],
                          ) as GrantProxyType;

                          setProxyGrantEditor((prev) =>
                            prev
                              ? {
                                  ...prev,
                                  proxyType,
                                  authUsername:
                                    proxyType === "socks5" ||
                                    proxyType === "http"
                                      ? prev.authUsername
                                      : "",
                                  authPassword:
                                    proxyType === "vless_reality"
                                      ? ""
                                      : randomProxySecret(),
                                  speedUnlimited: isAdvancedGrantProxy(
                                    proxyType,
                                  )
                                    ? true
                                    : prev.speedUnlimited,
                                }
                              : prev,
                          );
                        }}
                      >
                        {(
                          Object.keys(grantProxyLabels) as GrantProxyType[]
                        ).map((proxyType) => (
                          <SelectItem key={proxyType}>
                            {grantProxyLabels[proxyType]}
                          </SelectItem>
                        ))}
                      </Select>
                      <Input
                        label="监听端口"
                        max={65535}
                        min={1}
                        type="number"
                        value={String(proxyGrantEditor.listenPort || "")}
                        onValueChange={(value) =>
                          setProxyGrantEditor((prev) =>
                            prev
                              ? { ...prev, listenPort: Number(value) || 0 }
                              : prev,
                          )
                        }
                      />
                      {(proxyGrantEditor.proxyType === "socks5" ||
                        proxyGrantEditor.proxyType === "http") && (
                        <>
                          <Input
                            label="代理用户名"
                            value={proxyGrantEditor.authUsername}
                            onValueChange={(value) =>
                              setProxyGrantEditor((prev) =>
                                prev ? { ...prev, authUsername: value } : prev,
                              )
                            }
                          />
                          <Input
                            endContent={
                              <Button
                                isIconOnly
                                aria-label="生成代理密码"
                                size="sm"
                                title="生成代理密码"
                                variant="light"
                                onPress={() =>
                                  setProxyGrantEditor((prev) =>
                                    prev
                                      ? {
                                          ...prev,
                                          authPassword: randomProxySecret(),
                                        }
                                      : prev,
                                  )
                                }
                              >
                                <KeyRound className="h-4 w-4" />
                              </Button>
                            }
                            label="代理密码"
                            type="password"
                            value={proxyGrantEditor.authPassword}
                            onValueChange={(value) =>
                              setProxyGrantEditor((prev) =>
                                prev ? { ...prev, authPassword: value } : prev,
                              )
                            }
                          />
                        </>
                      )}
                      {proxyGrantEditor.proxyType === "shadowsocks" && (
                        <>
                          <Select
                            label="加密方式"
                            selectedKeys={[proxyGrantEditor.cipher]}
                            onSelectionChange={(keys) =>
                              setProxyGrantEditor((prev) =>
                                prev
                                  ? {
                                      ...prev,
                                      cipher: String(
                                        Array.from(keys)[0],
                                      ) as ProxyGrantEditorState["cipher"],
                                    }
                                  : prev,
                              )
                            }
                          >
                            <SelectItem key="aes-256-gcm">
                              AES-256-GCM
                            </SelectItem>
                            <SelectItem key="aes-128-gcm">
                              AES-128-GCM
                            </SelectItem>
                            <SelectItem key="chacha20-ietf-poly1305">
                              ChaCha20-IETF-Poly1305
                            </SelectItem>
                          </Select>
                          <Input
                            endContent={
                              <Button
                                isIconOnly
                                aria-label="生成连接密码"
                                size="sm"
                                title="生成连接密码"
                                variant="light"
                                onPress={() =>
                                  setProxyGrantEditor((prev) =>
                                    prev
                                      ? {
                                          ...prev,
                                          authPassword: randomProxySecret(),
                                        }
                                      : prev,
                                  )
                                }
                              >
                                <KeyRound className="h-4 w-4" />
                              </Button>
                            }
                            label="连接密码"
                            type="password"
                            value={proxyGrantEditor.authPassword}
                            onValueChange={(value) =>
                              setProxyGrantEditor((prev) =>
                                prev ? { ...prev, authPassword: value } : prev,
                              )
                            }
                          />
                        </>
                      )}
                      {proxyGrantEditor.proxyType === "vless_reality" && (
                        <>
                          <Select
                            className="md:col-span-2"
                            description="推荐站点已经过真实握手验证；也可以填写自己的 TLS 1.3 站点。"
                            label="REALITY 伪装站"
                            selectedKeys={[proxyGrantEditor.realityPreset]}
                            onSelectionChange={(keys) => {
                              const preset = String(
                                Array.from(keys)[0] || "www.cloudflare.com",
                              ) as GrantRealityPreset;

                              setProxyGrantEditor((prev) =>
                                prev
                                  ? {
                                      ...prev,
                                      realityPreset: preset,
                                      realityServerName:
                                        preset === "custom" ? "" : preset,
                                    }
                                  : prev,
                              );
                            }}
                          >
                            <SelectItem key="www.cloudflare.com">
                              Cloudflare（推荐）
                            </SelectItem>
                            <SelectItem key="www.google.com">Google</SelectItem>
                            <SelectItem key="custom">自定义域名</SelectItem>
                          </Select>
                          {proxyGrantEditor.realityPreset === "custom" && (
                            <Input
                              className="md:col-span-2"
                              description="不要填写 https://、端口或路径；部分 HTTPS 站点不兼容 REALITY，创建后请验证连接。"
                              label="自定义伪装域名"
                              placeholder="仅填写支持 TLS 1.3 的域名"
                              value={proxyGrantEditor.realityServerName}
                              onValueChange={(value) =>
                                setProxyGrantEditor((prev) =>
                                  prev
                                    ? { ...prev, realityServerName: value }
                                    : prev,
                                )
                              }
                            />
                          )}
                        </>
                      )}
                      {["trojan", "hysteria2", "tuic"].includes(
                        proxyGrantEditor.proxyType,
                      ) && (
                        <Input
                          className="md:col-span-2"
                          description={
                            ["hysteria2", "tuic"].includes(
                              proxyGrantEditor.proxyType,
                            )
                              ? "该协议使用 UDP，请同时放行节点防火墙和云厂商安全组端口。"
                              : "创建后由节点生成独立 TLS 运行时和客户端导入链接。"
                          }
                          endContent={
                            <Button
                              isIconOnly
                              aria-label="生成连接密钥"
                              size="sm"
                              title="生成连接密钥"
                              variant="light"
                              onPress={() =>
                                setProxyGrantEditor((prev) =>
                                  prev
                                    ? {
                                        ...prev,
                                        authPassword: randomProxySecret(),
                                      }
                                    : prev,
                                )
                              }
                            >
                              <KeyRound className="h-4 w-4" />
                            </Button>
                          }
                          label={`${grantProxyLabels[proxyGrantEditor.proxyType]} 连接密钥`}
                          type="password"
                          value={proxyGrantEditor.authPassword}
                          onValueChange={(value) =>
                            setProxyGrantEditor((prev) =>
                              prev ? { ...prev, authPassword: value } : prev,
                            )
                          }
                        />
                      )}
                      {proxyGrantEditor.proxyType === "wireguard" && (
                        <div className="md:col-span-2 border-y border-divider py-3 text-sm text-default-600">
                          创建后自动生成独立 WireGuard 客户端配置。该协议使用
                          UDP，节点 Agent 需为 2.40.0 或更高版本。
                        </div>
                      )}
                    </div>
                  </>
                )}

                <div className="grid grid-cols-1 gap-4 border-t border-divider pt-5 md:grid-cols-2">
                  <div className="space-y-2">
                    <Switch
                      isSelected={proxyGrantEditor.flowUnlimited}
                      size="sm"
                      onValueChange={(value) =>
                        setProxyGrantEditor((prev) =>
                          prev ? { ...prev, flowUnlimited: value } : prev,
                        )
                      }
                    >
                      流量无限制
                    </Switch>
                    <Input
                      isDisabled={proxyGrantEditor.flowUnlimited}
                      label="流量额度 (GB)"
                      min={1}
                      type="number"
                      value={String(proxyGrantEditor.flowLimit)}
                      onValueChange={(value) =>
                        setProxyGrantEditor((prev) =>
                          prev
                            ? { ...prev, flowLimit: Number(value) || 0 }
                            : prev,
                        )
                      }
                    />
                  </div>
                  <Select
                    label="流量重置日期"
                    selectedKeys={[String(proxyGrantEditor.flowResetDay)]}
                    onSelectionChange={(keys) =>
                      setProxyGrantEditor((prev) =>
                        prev
                          ? {
                              ...prev,
                              flowResetDay: Number(Array.from(keys)[0]) || 0,
                            }
                          : prev,
                      )
                    }
                  >
                    {FLOW_RESET_OPTIONS.map((option) => (
                      <SelectItem key={option.key} textValue={option.label}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </Select>
                  {supportsGrantSpeedLimit(proxyGrantEditor.proxyType) ? (
                    <div className="space-y-2">
                      <Switch
                        isSelected={proxyGrantEditor.speedUnlimited}
                        size="sm"
                        onValueChange={(value) =>
                          setProxyGrantEditor((prev) =>
                            prev ? { ...prev, speedUnlimited: value } : prev,
                          )
                        }
                      >
                        不限制速度
                      </Switch>
                      <Input
                        isDisabled={proxyGrantEditor.speedUnlimited}
                        label="该用户限速 (Mbps)"
                        min={1}
                        type="number"
                        value={String(proxyGrantEditor.speedLimitMbps)}
                        onValueChange={(value) =>
                          setProxyGrantEditor((prev) =>
                            prev
                              ? { ...prev, speedLimitMbps: Number(value) || 0 }
                              : prev,
                          )
                        }
                      />
                    </div>
                  ) : (
                    <div className="border-y border-divider py-3 text-sm text-default-600">
                      <p className="font-medium">该协议不支持限速</p>
                      <p className="mt-1 text-xs text-default-500">
                        流量额度、重置日期和到期停用仍会正常执行；创建授权要求节点
                        Agent 2.40.0+。
                      </p>
                    </div>
                  )}
                  <div className="space-y-2">
                    <Switch
                      isSelected={proxyGrantEditor.permanent}
                      size="sm"
                      onValueChange={(value) =>
                        setProxyGrantEditor((prev) =>
                          prev
                            ? {
                                ...prev,
                                permanent: value,
                                expiresAt: value
                                  ? null
                                  : new Date(Date.now() + 30 * 86400000),
                              }
                            : prev,
                        )
                      }
                    >
                      永久有效
                    </Switch>
                    {!proxyGrantEditor.permanent &&
                      proxyGrantEditor.expiresAt && (
                        <DatePicker
                          showMonthAndYearPickers
                          label="代理到期时间"
                          value={
                            parseDate(
                              proxyGrantEditor.expiresAt
                                .toISOString()
                                .split("T")[0],
                            ) as any
                          }
                          onChange={(date) =>
                            date &&
                            setProxyGrantEditor((prev) =>
                              prev
                                ? {
                                    ...prev,
                                    expiresAt: new Date(
                                      date.year,
                                      date.month - 1,
                                      date.day,
                                      23,
                                      59,
                                      59,
                                    ),
                                  }
                                : prev,
                            )
                          }
                        />
                      )}
                  </div>
                </div>
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setProxyGrantEditor(null)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={proxyGrantSaving}
              onPress={saveProxyGrant}
            >
              保存授权
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        backdrop="opaque"
        classNames={{ base: "max-w-[94vw]" }}
        isOpen={resourceEditor !== null}
        placement="center"
        scrollBehavior="inside"
        size="2xl"
        onClose={() => setResourceEditor(null)}
      >
        <ModalContent>
          <ModalHeader className="flex items-center justify-between gap-3 border-b border-divider">
            <div className="min-w-0">
              <p className="truncate">
                配置{resourceEditor?.kind === "tunnel" ? "隧道" : "节点"}额度
              </p>
              <p className="mt-1 truncate text-xs font-normal text-default-500">
                {resourceEditorName || "未命名资源"}
              </p>
            </div>
            <Chip
              color={
                resourceEditor?.kind === "tunnel" ? "primary" : "secondary"
              }
              size="sm"
              variant="flat"
            >
              {resourceEditor?.kind === "tunnel" ? "隧道权限" : "节点权限"}
            </Chip>
          </ModalHeader>
          <ModalBody className="py-5">
            {resourceEditor && (
              <div className="space-y-5">
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Switch
                      isSelected={resourceEditor.flowUnlimited}
                      size="sm"
                      onValueChange={(value) =>
                        setResourceEditor((prev) =>
                          prev ? { ...prev, flowUnlimited: value } : prev,
                        )
                      }
                    >
                      流量无限制
                    </Switch>
                    <Input
                      isDisabled={resourceEditor.flowUnlimited}
                      label="流量额度 (GB)"
                      type="number"
                      value={resourceEditor.flow.toString()}
                      onChange={(event) =>
                        setResourceEditor((prev) =>
                          prev
                            ? {
                                ...prev,
                                flow: Math.max(
                                  0,
                                  Number(event.target.value) || 0,
                                ),
                              }
                            : prev,
                        )
                      }
                    />
                  </div>
                  <div className="space-y-2">
                    <Switch
                      isSelected={resourceEditor.forwardUnlimited}
                      size="sm"
                      onValueChange={(value) =>
                        setResourceEditor((prev) =>
                          prev ? { ...prev, forwardUnlimited: value } : prev,
                        )
                      }
                    >
                      转发名额无限制
                    </Switch>
                    <Input
                      isDisabled={resourceEditor.forwardUnlimited}
                      label="转发名额"
                      type="number"
                      value={resourceEditor.num.toString()}
                      onChange={(event) =>
                        setResourceEditor((prev) =>
                          prev
                            ? {
                                ...prev,
                                num: Math.max(
                                  0,
                                  Number(event.target.value) || 0,
                                ),
                              }
                            : prev,
                        )
                      }
                    />
                  </div>
                  <Select
                    label="流量重置日期"
                    selectedKeys={[String(resourceEditor.flowResetTime ?? 0)]}
                    onSelectionChange={(keys) =>
                      setResourceEditor((prev) =>
                        prev
                          ? {
                              ...prev,
                              flowResetTime: Number(Array.from(keys)[0] ?? 0),
                            }
                          : prev,
                      )
                    }
                  >
                    {FLOW_RESET_OPTIONS.map((option) => (
                      <SelectItem key={option.key} textValue={option.label}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </Select>
                  {resourceEditor.kind === "tunnel" && (
                    <Select
                      label="该用户限速"
                      selectedKeys={[
                        resourceEditor.speedId
                          ? String(resourceEditor.speedId)
                          : "none",
                      ]}
                      onSelectionChange={(keys) =>
                        setResourceEditor((prev) =>
                          prev
                            ? {
                                ...prev,
                                speedId:
                                  String(Array.from(keys)[0]) === "none"
                                    ? null
                                    : Number(Array.from(keys)[0]),
                              }
                            : prev,
                        )
                      }
                    >
                      {[
                        <SelectItem key="none" textValue="不限速">
                          不限速
                        </SelectItem>,
                        ...speedLimits
                          .filter(
                            (item) =>
                              item.tunnelId === resourceEditor.resourceId,
                          )
                          .map((item) => (
                            <SelectItem
                              key={String(item.id)}
                              textValue={item.name}
                            >
                              {item.name}
                              {item.speed ? ` · ${item.speed} Mbps` : ""}
                            </SelectItem>
                          )),
                      ]}
                    </Select>
                  )}
                  <div className="space-y-2">
                    <Switch
                      isSelected={resourceEditor.expTime === null}
                      size="sm"
                      onValueChange={(unlimited) =>
                        setResourceEditor((prev) =>
                          prev
                            ? {
                                ...prev,
                                expTime: unlimited
                                  ? null
                                  : new Date(Date.now() + 30 * 86400000),
                              }
                            : prev,
                        )
                      }
                    >
                      权限永不过期
                    </Switch>
                    {resourceEditor.expTime && (
                      <DatePicker
                        showMonthAndYearPickers
                        label="权限到期时间"
                        value={
                          parseDate(
                            resourceEditor.expTime.toISOString().split("T")[0],
                          ) as any
                        }
                        onChange={(date) =>
                          date &&
                          setResourceEditor((prev) =>
                            prev
                              ? {
                                  ...prev,
                                  expTime: new Date(
                                    date.year,
                                    date.month - 1,
                                    date.day,
                                    23,
                                    59,
                                    59,
                                  ),
                                }
                              : prev,
                          )
                        }
                      />
                    )}
                  </div>
                </div>
                <RadioGroup
                  label="权限状态"
                  orientation="horizontal"
                  value={resourceEditor.status.toString()}
                  onValueChange={(value: string) =>
                    setResourceEditor((prev) =>
                      prev ? { ...prev, status: Number(value) } : prev,
                    )
                  }
                >
                  <Radio value="1">启用</Radio>
                  <Radio value="0">禁用</Radio>
                </RadioGroup>
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setResourceEditor(null)}>
              取消
            </Button>
            <Button color="primary" onPress={saveResourceEditor}>
              保存并返回
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        backdrop="opaque"
        isOpen={portEditor !== null}
        size="xl"
        onClose={() => setPortEditor(null)}
      >
        <ModalContent>
          <ModalHeader className="border-b border-divider">
            配置共享端口范围
          </ModalHeader>
          <ModalBody className="gap-4 py-5">
            {portEditor &&
              (() => {
                const pool = portPools.find(
                  (item) => item.id === portEditor.poolId,
                );

                return (
                  <>
                    <div className="grid grid-cols-2 gap-3 rounded-md bg-default-100 px-4 py-3 text-sm">
                      <div>
                        <p className="text-xs text-default-500">端口池</p>
                        <p className="mt-1 font-medium">{pool?.name}</p>
                      </div>
                      <div>
                        <p className="text-xs text-default-500">可配置范围</p>
                        <p className="mt-1 font-mono">
                          {pool?.startPort}-{pool?.endPort}
                        </p>
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <Input
                        label="起始端口"
                        max={pool?.endPort}
                        min={pool?.startPort}
                        type="number"
                        value={String(portEditor.startPort)}
                        onValueChange={(value) =>
                          setPortEditor((prev) =>
                            prev
                              ? { ...prev, startPort: Number(value) || 0 }
                              : prev,
                          )
                        }
                      />
                      <Input
                        label="结束端口"
                        max={pool?.endPort}
                        min={pool?.startPort}
                        type="number"
                        value={String(portEditor.endPort)}
                        onValueChange={(value) =>
                          setPortEditor((prev) =>
                            prev
                              ? { ...prev, endPort: Number(value) || 0 }
                              : prev,
                          )
                        }
                      />
                    </div>
                    <p className="text-xs text-default-500">
                      首尾端口均包含在授权内。例如 1000-1010 实际包含 11
                      个端口。
                    </p>
                  </>
                );
              })()}
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setPortEditor(null)}>
              取消
            </Button>
            <Button color="primary" onPress={savePortEditor}>
              保存并返回
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 隧道权限管理模态框 */}
      <Modal
        backdrop="blur"
        classNames={{
          base: "max-w-[95vw] sm:max-w-4xl",
        }}
        isDismissable={false}
        isOpen={isTunnelModalOpen}
        placement="center"
        scrollBehavior="outside"
        size="2xl"
        onClose={onTunnelModalClose}
      >
        <ModalContent>
          <ModalHeader>用户 {currentUser?.user} 的隧道权限管理</ModalHeader>
          <ModalBody>
            <div className="space-y-6">
              {/* 节点共享：共享节点只允许使用，不能修改节点配置或获取安装密钥 */}
              <section className="border border-divider rounded-lg p-4 space-y-3">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <h3 className="text-lg font-semibold">节点共享</h3>
                    <p className="text-xs text-default-500 mt-1">
                      用户可以用共享节点组建自己的隧道，但节点本身保持只读。
                    </p>
                  </div>
                  <Chip color="secondary" size="sm" variant="flat">
                    {userNodes.length} 个共享节点
                  </Chip>
                </div>
                <div className="flex gap-2">
                  <Select
                    className="flex-1"
                    label="选择节点"
                    selectedKeys={nodeToShare ? [nodeToShare.toString()] : []}
                    onSelectionChange={(keys) =>
                      setNodeToShare(Number(Array.from(keys)[0]) || null)
                    }
                  >
                    {nodes.map((node) => (
                      <SelectItem
                        key={node.id.toString()}
                        textValue={node.name}
                      >
                        {node.name} · {node.serverIp}
                      </SelectItem>
                    ))}
                  </Select>
                  <Button
                    className="self-end"
                    color="secondary"
                    isLoading={nodeShareLoading}
                    onPress={handleAssignNode}
                  >
                    共享节点
                  </Button>
                </div>
                {userNodes.length > 0 && (
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                    {userNodes.map((node) => (
                      <div
                        key={node.nodeId}
                        className="flex items-center justify-between gap-2 rounded-md border border-divider px-3 py-2"
                      >
                        <div className="min-w-0">
                          <p className="text-sm font-medium truncate">
                            {node.nodeName}
                          </p>
                          <p className="text-xs text-default-500 truncate">
                            {node.serverIp}
                          </p>
                        </div>
                        <Button
                          color="danger"
                          size="sm"
                          variant="light"
                          onPress={() => handleRemoveNode(node.nodeId)}
                        >
                          取消
                        </Button>
                      </div>
                    ))}
                  </div>
                )}
              </section>

              {/* 分配新权限部分 */}
              <div>
                <h3 className="text-lg font-semibold mb-4">分配新权限</h3>
                <div className="space-y-4">
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <Select
                      label="选择隧道"
                      selectedKeys={
                        tunnelForm.tunnelId
                          ? [tunnelForm.tunnelId.toString()]
                          : []
                      }
                      onSelectionChange={(keys) => {
                        const value = Array.from(keys)[0] as string;

                        setTunnelForm((prev) => ({
                          ...prev,
                          tunnelId: Number(value) || null,
                          speedId: null,
                        }));
                      }}
                    >
                      {availableTunnels.map((tunnel) => (
                        <SelectItem
                          key={tunnel.id.toString()}
                          textValue={tunnel.name}
                        >
                          {tunnel.name}
                        </SelectItem>
                      ))}
                    </Select>

                    <Select
                      isDisabled={!tunnelForm.tunnelId}
                      label="限速规则"
                      selectedKeys={
                        tunnelForm.speedId
                          ? [tunnelForm.speedId.toString()]
                          : ["null"]
                      }
                      onSelectionChange={(keys) => {
                        const value = Array.from(keys)[0] as string;

                        setTunnelForm((prev) => ({
                          ...prev,
                          speedId: value === "null" ? null : Number(value),
                        }));
                      }}
                    >
                      {[
                        <SelectItem key="null" textValue="不限速">
                          不限速
                        </SelectItem>,
                        ...availableSpeedLimits.map((speedLimit) => (
                          <SelectItem
                            key={speedLimit.id.toString()}
                            textValue={speedLimit.name}
                          >
                            {speedLimit.name}
                          </SelectItem>
                        )),
                      ]}
                    </Select>

                    <Input
                      label="流量限制(GB)"
                      max="99999"
                      min="1"
                      type="number"
                      value={tunnelForm.flow.toString()}
                      onChange={(e) => {
                        const value = Math.min(
                          Math.max(Number(e.target.value) || 0, 1),
                          99999,
                        );

                        setTunnelForm((prev) => ({ ...prev, flow: value }));
                      }}
                    />

                    <Input
                      label="转发数量"
                      max="99999"
                      min="1"
                      type="number"
                      value={tunnelForm.num.toString()}
                      onChange={(e) => {
                        const value = Math.min(
                          Math.max(Number(e.target.value) || 0, 1),
                          99999,
                        );

                        setTunnelForm((prev) => ({ ...prev, num: value }));
                      }}
                    />

                    <Select
                      label="流量重置日期"
                      selectedKeys={[tunnelForm.flowResetTime.toString()]}
                      onSelectionChange={(keys) => {
                        const value = Array.from(keys)[0] as string;

                        setTunnelForm((prev) => ({
                          ...prev,
                          flowResetTime: Number(value),
                        }));
                      }}
                    >
                      {FLOW_RESET_OPTIONS.map((option) => (
                        <SelectItem key={option.key} textValue={option.label}>
                          {option.label}
                        </SelectItem>
                      ))}
                    </Select>

                    <DatePicker
                      showMonthAndYearPickers
                      className="cursor-pointer"
                      label="到期时间"
                      value={
                        tunnelForm.expTime
                          ? (parseDate(
                              tunnelForm.expTime.toISOString().split("T")[0],
                            ) as any)
                          : null
                      }
                      onChange={(date) => {
                        if (date) {
                          const jsDate = new Date(
                            date.year,
                            date.month - 1,
                            date.day,
                            23,
                            59,
                            59,
                          );

                          setTunnelForm((prev) => ({
                            ...prev,
                            expTime: jsDate,
                          }));
                        } else {
                          setTunnelForm((prev) => ({ ...prev, expTime: null }));
                        }
                      }}
                    />
                  </div>

                  <Button
                    color="primary"
                    isLoading={assignLoading}
                    onPress={handleAssignTunnel}
                  >
                    分配权限
                  </Button>
                </div>
              </div>

              {/* 已有权限部分 */}
              <div>
                <h3 className="text-lg font-semibold mb-4">已有权限</h3>
                <Table
                  aria-label="用户隧道权限列表"
                  classNames={{
                    wrapper: "shadow-none",
                    th: "bg-gray-50 dark:bg-gray-800 text-gray-700 dark:text-gray-300 font-medium",
                  }}
                >
                  <TableHeader>
                    <TableColumn>隧道名称</TableColumn>
                    <TableColumn>流量统计</TableColumn>
                    <TableColumn>转发数量</TableColumn>
                    <TableColumn>状态</TableColumn>
                    <TableColumn>限速规则</TableColumn>
                    <TableColumn>重置时间</TableColumn>
                    <TableColumn>到期时间</TableColumn>
                    <TableColumn>操作</TableColumn>
                  </TableHeader>
                  <TableBody
                    emptyContent="暂无隧道权限"
                    isLoading={tunnelListLoading}
                    items={userTunnels}
                    loadingContent={<Spinner />}
                  >
                    {(userTunnel) => (
                      <TableRow key={userTunnel.id}>
                        <TableCell>{userTunnel.tunnelName}</TableCell>
                        <TableCell>
                          <div className="flex flex-col gap-1">
                            <div className="flex justify-between text-small">
                              <span className="text-gray-600">限制:</span>
                              <span className="font-medium">
                                {formatFlow(userTunnel.flow, "gb")}
                              </span>
                            </div>
                            <div className="flex justify-between text-small">
                              <span className="text-gray-600">已用:</span>
                              <span className="font-medium text-danger">
                                {formatFlow(
                                  calculateTunnelUsedFlow(userTunnel),
                                )}
                              </span>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>{userTunnel.num}</TableCell>
                        <TableCell>
                          <Chip
                            color={
                              userTunnel.status === 1 ? "success" : "danger"
                            }
                            size="sm"
                            variant="flat"
                          >
                            {userTunnel.status === 1 ? "正常" : "禁用"}
                          </Chip>
                        </TableCell>
                        <TableCell>
                          <Chip
                            color={
                              userTunnel.speedLimitName ? "warning" : "success"
                            }
                            size="sm"
                            variant="flat"
                          >
                            {userTunnel.speedLimitName || "不限速"}
                          </Chip>
                        </TableCell>
                        <TableCell>
                          {userTunnel.flowResetTime === 0
                            ? "不重置"
                            : `每月${userTunnel.flowResetTime}号`}
                        </TableCell>
                        <TableCell>{formatDate(userTunnel.expTime)}</TableCell>
                        <TableCell>
                          <div className="flex items-center gap-2">
                            <Button
                              isIconOnly
                              color="primary"
                              size="sm"
                              variant="flat"
                              onClick={() => handleEditTunnel(userTunnel)}
                            >
                              <EditIcon className="w-4 h-4" />
                            </Button>
                            <Button
                              isIconOnly
                              color="warning"
                              size="sm"
                              title="重置流量"
                              variant="flat"
                              onClick={() => handleResetTunnelFlow(userTunnel)}
                            >
                              <svg
                                className="w-4 h-4"
                                fill="currentColor"
                                viewBox="0 0 20 20"
                              >
                                <path
                                  clipRule="evenodd"
                                  d="M4 2a1 1 0 011 1v2.101a7.002 7.002 0 0111.601 2.566 1 1 0 11-1.885.666A5.002 5.002 0 005.999 7H9a1 1 0 010 2H4a1 1 0 01-1-1V3a1 1 0 011-1zm.008 9.057a1 1 0 011.276.61A5.002 5.002 0 0014.001 13H11a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0v-2.101a7.002 7.002 0 01-11.601-2.566 1 1 0 01.61-1.276z"
                                  fillRule="evenodd"
                                />
                              </svg>
                            </Button>
                            <Button
                              isIconOnly
                              color="danger"
                              size="sm"
                              variant="flat"
                              onClick={() => handleRemoveTunnel(userTunnel)}
                            >
                              <DeleteIcon className="w-4 h-4" />
                            </Button>
                          </div>
                        </TableCell>
                      </TableRow>
                    )}
                  </TableBody>
                </Table>
              </div>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button onPress={onTunnelModalClose}>关闭</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 编辑隧道权限模态框 */}
      <Modal
        backdrop="blur"
        isDismissable={false}
        isOpen={isEditTunnelModalOpen}
        placement="center"
        scrollBehavior="outside"
        size="2xl"
        onClose={onEditTunnelModalClose}
      >
        <ModalContent>
          <ModalHeader>编辑隧道权限 - {editTunnelForm?.tunnelName}</ModalHeader>
          <ModalBody>
            {editTunnelForm && (
              <>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <Input
                    label="流量限制(GB)"
                    max="99999"
                    min="1"
                    type="number"
                    value={editTunnelForm.flow.toString()}
                    onChange={(e) => {
                      const value = Math.min(
                        Math.max(Number(e.target.value) || 0, 1),
                        99999,
                      );

                      setEditTunnelForm((prev) =>
                        prev ? { ...prev, flow: value } : null,
                      );
                    }}
                  />

                  <Input
                    label="转发数量"
                    max="99999"
                    min="1"
                    type="number"
                    value={editTunnelForm.num.toString()}
                    onChange={(e) => {
                      const value = Math.min(
                        Math.max(Number(e.target.value) || 0, 1),
                        99999,
                      );

                      setEditTunnelForm((prev) =>
                        prev ? { ...prev, num: value } : null,
                      );
                    }}
                  />

                  <Select
                    label="限速规则"
                    selectedKeys={
                      editTunnelForm.speedId
                        ? [editTunnelForm.speedId.toString()]
                        : ["null"]
                    }
                    onSelectionChange={(keys) => {
                      const value = Array.from(keys)[0] as string;

                      setEditTunnelForm((prev) =>
                        prev
                          ? {
                              ...prev,
                              speedId: value === "null" ? null : Number(value),
                            }
                          : null,
                      );
                    }}
                  >
                    {[
                      <SelectItem key="null" textValue="不限速">
                        不限速
                      </SelectItem>,
                      ...editAvailableSpeedLimits.map((speedLimit) => (
                        <SelectItem
                          key={speedLimit.id.toString()}
                          textValue={speedLimit.name}
                        >
                          {speedLimit.name}
                        </SelectItem>
                      )),
                    ]}
                  </Select>

                  <Select
                    label="流量重置日期"
                    selectedKeys={[editTunnelForm.flowResetTime.toString()]}
                    onSelectionChange={(keys) => {
                      const value = Array.from(keys)[0] as string;

                      setEditTunnelForm((prev) =>
                        prev ? { ...prev, flowResetTime: Number(value) } : null,
                      );
                    }}
                  >
                    {FLOW_RESET_OPTIONS.map((option) => (
                      <SelectItem key={option.key} textValue={option.label}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </Select>

                  <DatePicker
                    isRequired
                    showMonthAndYearPickers
                    className="cursor-pointer"
                    label="到期时间"
                    value={
                      editTunnelForm.expTime
                        ? (parseDate(
                            new Date(editTunnelForm.expTime)
                              .toISOString()
                              .split("T")[0],
                          ) as any)
                        : null
                    }
                    onChange={(date) => {
                      if (date) {
                        const jsDate = new Date(
                          date.year,
                          date.month - 1,
                          date.day,
                          23,
                          59,
                          59,
                        );

                        setEditTunnelForm((prev) =>
                          prev ? { ...prev, expTime: jsDate.getTime() } : null,
                        );
                      } else {
                        setEditTunnelForm((prev) =>
                          prev ? { ...prev, expTime: Date.now() } : null,
                        );
                      }
                    }}
                  />
                </div>

                <RadioGroup
                  label="状态"
                  orientation="horizontal"
                  value={editTunnelForm.status.toString()}
                  onValueChange={(value: string) =>
                    setEditTunnelForm((prev) =>
                      prev ? { ...prev, status: Number(value) } : null,
                    )
                  }
                >
                  <Radio value="1">正常</Radio>
                  <Radio value="0">禁用</Radio>
                </RadioGroup>
              </>
            )}
          </ModalBody>
          <ModalFooter>
            <Button onPress={onEditTunnelModalClose}>取消</Button>
            <Button
              color="primary"
              isLoading={editTunnelLoading}
              onPress={handleUpdateTunnel}
            >
              确定
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 删除确认对话框 */}
      <Modal
        backdrop="blur"
        isOpen={isDeleteModalOpen}
        placement="center"
        scrollBehavior="outside"
        size="2xl"
        onClose={onDeleteModalClose}
      >
        <ModalContent>
          <ModalHeader className="flex flex-col gap-1">
            确认删除用户
          </ModalHeader>
          <ModalBody>
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 bg-danger-100 rounded-full flex items-center justify-center">
                <DeleteIcon className="w-6 h-6 text-danger" />
              </div>
              <div className="flex-1">
                <p className="text-foreground">
                  确定要删除用户{" "}
                  <span className="font-semibold text-danger">
                    "{userToDelete?.user}"
                  </span>{" "}
                  吗？
                </p>
                <p className="text-small text-default-500 mt-1">
                  此操作不可撤销，用户的所有数据将被永久删除。
                </p>
              </div>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={onDeleteModalClose}>
              取消
            </Button>
            <Button color="danger" onPress={handleConfirmDelete}>
              确认删除
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 删除隧道权限确认对话框 */}
      <Modal
        backdrop="blur"
        isOpen={isDeleteTunnelModalOpen}
        placement="center"
        scrollBehavior="outside"
        size="2xl"
        onClose={onDeleteTunnelModalClose}
      >
        <ModalContent>
          <ModalHeader className="flex flex-col gap-1">
            确认删除隧道权限
          </ModalHeader>
          <ModalBody>
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 bg-danger-100 rounded-full flex items-center justify-center">
                <DeleteIcon className="w-6 h-6 text-danger" />
              </div>
              <div className="flex-1">
                <p className="text-foreground">
                  确定要删除用户{" "}
                  <span className="font-semibold">{currentUser?.user}</span>{" "}
                  对隧道{" "}
                  <span className="font-semibold text-danger">
                    "{tunnelToDelete?.tunnelName}"
                  </span>{" "}
                  的权限吗？
                </p>
                <p className="text-small text-default-500 mt-1">
                  删除后该用户将无法使用此隧道创建转发，此操作不可撤销。
                </p>
              </div>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={onDeleteTunnelModalClose}>
              取消
            </Button>
            <Button color="danger" onPress={handleConfirmRemoveTunnel}>
              确认删除
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 重置流量确认对话框 */}
      <Modal
        backdrop="blur"
        isOpen={isResetFlowModalOpen}
        placement="center"
        scrollBehavior="outside"
        size="2xl"
        onClose={onResetFlowModalClose}
      >
        <ModalContent>
          <ModalHeader className="flex flex-col gap-1">
            确认重置流量
          </ModalHeader>
          <ModalBody>
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 bg-warning-100 rounded-full flex items-center justify-center">
                <svg
                  className="w-6 h-6 text-warning"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    clipRule="evenodd"
                    d="M4 2a1 1 0 011 1v2.101a7.002 7.002 0 0111.601 2.566 1 1 0 11-1.885.666A5.002 5.002 0 005.999 7H9a1 1 0 010 2H4a1 1 0 01-1-1V3a1 1 0 011-1zm.008 9.057a1 1 0 011.276.61A5.002 5.002 0 0014.001 13H11a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0v-2.101a7.002 7.002 0 01-11.601-2.566 1 1 0 01.61-1.276z"
                    fillRule="evenodd"
                  />
                </svg>
              </div>
              <div className="flex-1">
                <p className="text-foreground">
                  确定要重置用户{" "}
                  <span className="font-semibold text-warning">
                    "{userToReset?.user}"
                  </span>{" "}
                  的流量吗？
                </p>
                <p className="text-small text-default-500 mt-1">
                  将重置该用户的自有资源、共享隧道和共享节点流量统计，所有资源会重新获得本周期额度。此操作不可撤销。
                </p>
                <div className="mt-2 p-2 bg-warning-50 dark:bg-warning-100/10 rounded text-xs">
                  <div className="text-warning-700 dark:text-warning-300">
                    当前流量使用情况：
                  </div>
                  <div className="mt-1 space-y-1">
                    <div className="flex justify-between">
                      <span>上行流量：</span>
                      <span className="font-mono">
                        {userToReset
                          ? formatFlow(userToReset.inFlow || 0)
                          : "-"}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span>下行流量：</span>
                      <span className="font-mono">
                        {userToReset
                          ? formatFlow(userToReset.outFlow || 0)
                          : "-"}
                      </span>
                    </div>
                    <div className="flex justify-between font-medium">
                      <span>总计：</span>
                      <span className="font-mono text-warning-700 dark:text-warning-300">
                        {userToReset
                          ? formatFlow(calculateUserTotalUsedFlow(userToReset))
                          : "-"}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={onResetFlowModalClose}>
              取消
            </Button>
            <Button
              color="warning"
              isLoading={resetFlowLoading}
              onPress={handleConfirmResetFlow}
            >
              确认重置
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 重置隧道流量确认对话框 */}
      <Modal
        backdrop="blur"
        isOpen={isResetTunnelFlowModalOpen}
        placement="center"
        scrollBehavior="outside"
        size="2xl"
        onClose={onResetTunnelFlowModalClose}
      >
        <ModalContent>
          <ModalHeader className="flex flex-col gap-1">
            确认重置隧道流量
          </ModalHeader>
          <ModalBody>
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 bg-warning-100 rounded-full flex items-center justify-center">
                <svg
                  className="w-6 h-6 text-warning"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path
                    clipRule="evenodd"
                    d="M4 2a1 1 0 011 1v2.101a7.002 7.002 0 0111.601 2.566 1 1 0 11-1.885.666A5.002 5.002 0 005.999 7H9a1 1 0 010 2H4a1 1 0 01-1-1V3a1 1 0 011-1zm.008 9.057a1 1 0 011.276.61A5.002 5.002 0 0014.001 13H11a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0v-2.101a7.002 7.002 0 01-11.601-2.566 1 1 0 01.61-1.276z"
                    fillRule="evenodd"
                  />
                </svg>
              </div>
              <div className="flex-1">
                <p className="text-foreground">
                  确定要重置用户{" "}
                  <span className="font-semibold">{currentUser?.user}</span>{" "}
                  对隧道{" "}
                  <span className="font-semibold text-warning">
                    "{tunnelToReset?.tunnelName}"
                  </span>{" "}
                  的流量吗？
                </p>
                <p className="text-small text-default-500 mt-1">
                  该操作只会重置隧道权限流量不会重置账号流量，重置后该隧道权限的上下行流量将归零，此操作不可撤销。
                </p>
                <div className="mt-2 p-2 bg-warning-50 dark:bg-warning-100/10 rounded text-xs">
                  <div className="text-warning-700 dark:text-warning-300">
                    当前流量使用情况：
                  </div>
                  <div className="mt-1 space-y-1">
                    <div className="flex justify-between">
                      <span>上行流量：</span>
                      <span className="font-mono">
                        {tunnelToReset
                          ? formatFlow(tunnelToReset.inFlow || 0)
                          : "-"}
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span>下行流量：</span>
                      <span className="font-mono">
                        {tunnelToReset
                          ? formatFlow(tunnelToReset.outFlow || 0)
                          : "-"}
                      </span>
                    </div>
                    <div className="flex justify-between font-medium">
                      <span>总计：</span>
                      <span className="font-mono text-warning-700 dark:text-warning-300">
                        {tunnelToReset
                          ? formatFlow(calculateTunnelUsedFlow(tunnelToReset))
                          : "-"}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={onResetTunnelFlowModalClose}>
              取消
            </Button>
            <Button
              color="warning"
              isLoading={resetTunnelFlowLoading}
              onPress={handleConfirmResetTunnelFlow}
            >
              确认重置
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
