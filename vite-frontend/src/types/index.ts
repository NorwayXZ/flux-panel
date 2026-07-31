import { SVGProps } from "react";

export type IconSvgProps = SVGProps<SVGSVGElement> & {
  size?: number;
};

// 用户管理相关类型
export interface User {
  id: number;
  name?: string;
  user: string;
  pwd?: string;
  status: number; // 1-正常, 0-禁用
  flow: number; // 流量限制(GB)
  flowUnlimited?: number;
  num: number; // 转发数量
  forwardUnlimited?: number;
  expTime?: number; // 过期时间戳
  flowResetTime?: number; // 流量重置日期(1-31号)
  createdTime?: number; // 创建时间戳
  inFlow?: number; // 下载流量(字节)
  outFlow?: number; // 上传流量(字节)
  ownedInFlow?: number;
  ownedOutFlow?: number;
  totalFlow?: number;
  totalFlowUnlimited?: boolean;
  totalUsedFlow?: number;
  totalNum?: number;
  totalNumUnlimited?: boolean;
  tunnelPermissionCount?: number;
  nodePermissionCount?: number;
  portPermissionCount?: number;
}

export interface UserForm {
  id?: number;
  name?: string;
  user: string;
  pwd?: string;
  status: number;
  flow: number;
  flowUnlimited: boolean;
  num: number;
  forwardUnlimited: boolean;
  expTime: Date | null;
  flowResetTime: number;
  tunnelPermissions: UserTunnelProvision[];
  nodePermissions: UserNodeProvision[];
  portPermissions: UserPortProvision[];
}

export interface UserTunnelProvision {
  tunnelId: number;
  flow: number;
  flowUnlimited: boolean;
  num: number;
  forwardUnlimited: boolean;
  expTime: Date | null;
  flowResetTime: number;
  speedId: number | null;
  status: number;
}

export interface UserNodeProvision {
  nodeId: number;
  flow: number;
  flowUnlimited: boolean;
  num: number;
  forwardUnlimited: boolean;
  expTime: Date | null;
  flowResetTime: number;
  status: number;
  usedFlow?: number;
  usedForwards?: number;
}

export interface UserPortProvision {
  id?: number;
  poolId: number;
  startPort: number;
  endPort: number;
  poolName?: string;
  nodeName?: string;
  publicHost?: string;
  usedPorts?: number;
  availablePorts?: number;
}

export interface UserTunnel {
  id: number;
  userId: number;
  tunnelId: number;
  tunnelName: string;
  status: number; // 1-正常, 0-禁用
  flow: number; // 流量限制(GB)
  flowUnlimited?: number;
  num: number; // 转发数量
  forwardUnlimited?: number;
  expTime: number; // 过期时间戳
  flowResetTime: number; // 流量重置日期
  speedId?: number | null; // 限速规则ID
  speedLimitName?: string; // 限速规则名称
  inFlow?: number; // 下载流量(字节)
  outFlow?: number; // 上传流量(字节)
  tunnelFlow?: number; // 隧道流量计算类型(1-单向, 2-双向)
}

export interface UserTunnelForm {
  tunnelId: number | null;
  flow: number;
  num: number;
  expTime: Date | null;
  flowResetTime: number;
  speedId: number | null;
}

export interface Tunnel {
  id: number;
  name: string;
  entryNodeId: number;
  exitNodeId: number;
  entryNodeName?: string;
  exitNodeName?: string;
  status?: number;
  flow?: number; // 流量计算类型
  ownerRoleId?: number;
  ownerUserId?: number;
}

export interface SpeedLimit {
  id: number;
  name: string;
  tunnelId: number;
  uploadSpeed: number;
  downloadSpeed: number;
  speed?: number;
}

export interface Pagination {
  current: number;
  size: number;
  total: number;
}
