import Network from './network';

// 登陆相关接口
export interface LoginData {
  username: string;
  password: string;
  captchaId: string;
}

export interface LoginResponse {
  token: string;
  role_id: number;
  name: string;
  requirePasswordChange?: boolean;
}

export const login = (data: LoginData) => Network.post<LoginResponse>("/user/login", data);

// 用户CRUD操作 - 全部使用POST请求
export const createUser = (data: any) => Network.post("/user/create", data);
export const getAllUsers = (pageData: any = {}) => Network.post("/user/list", pageData);
export const updateUser = (data: any) => Network.post("/user/update", data);
export const deleteUser = (id: number) => Network.post("/user/delete", { id });
export const getUserPackageInfo = () => Network.post("/user/package");

// 节点CRUD操作 - 全部使用POST请求
export const createNode = (data: any) => Network.post("/node/create", data);
export const getNodeList = () => Network.post("/node/list");
export const updateNode = (data: any) => Network.post("/node/update", data);
export const deleteNode = (id: number) => Network.post("/node/delete", { id });
export const getNodeInstallCommand = (id: number) => Network.post("/node/install", { id });
export const checkNodeStatus = (nodeId?: number) => {
  const params = nodeId ? { nodeId } : {};
  return Network.post("/node/check-status", params);
};
export interface TerminalTicket {
  ticket: string;
  expiresAt: number;
  sessionId: string;
}

export interface TerminalAuditItem {
  sessionId: string;
  username: string;
  nodeId: number;
  nodeName: string;
  sourceIp?: string;
  status: string;
  closeReason?: string;
  startedAt: number;
  endedAt?: number;
}

export const setNodeTerminalEnabled = (data: { nodeId: number; enabled: boolean; password: string }) =>
  Network.post<{ nodeId: number; enabled: boolean; operator: string }>("/terminal/node/toggle", data);
export const createTerminalSession = (data: { nodeId: number; password: string }) =>
  Network.post<TerminalTicket>("/terminal/session/create", data);
export const getTerminalAudit = (nodeId?: number) =>
  Network.post<TerminalAuditItem[]>("/terminal/audit/list", nodeId ? { nodeId } : {});
export const assignUserNode = (data: { userId: number; nodeId: number }) => Network.post("/node/user/assign", data);
export const getUserNodeList = (userId: number) => Network.post("/node/user/list", { userId });
export const removeUserNode = (data: { userId: number; nodeId: number }) => Network.post("/node/user/remove", data);

// 隧道CRUD操作 - 全部使用POST请求
export const createTunnel = (data: any) => Network.post("/tunnel/create", data);
export const getTunnelList = () => Network.post("/tunnel/list");
export const getTunnelById = (id: number) => Network.post("/tunnel/get", { id });
export const updateTunnel = (data: any) => Network.post("/tunnel/update", data);
export const deleteTunnel = (id: number) => Network.post("/tunnel/delete", { id });
export const diagnoseTunnel = (tunnelId: number) => Network.post("/tunnel/diagnose", { tunnelId });

// 用户隧道权限管理操作 - 全部使用POST请求
export const assignUserTunnel = (data: any) => Network.post("/tunnel/user/assign", data);
export const getUserTunnelList = (queryData: any = {}) => Network.post("/tunnel/user/list", queryData);
export const removeUserTunnel = (params: any) => Network.post("/tunnel/user/remove", params);
export const updateUserTunnel = (data: any) => Network.post("/tunnel/user/update", data);
export const userTunnel = () => Network.post("/tunnel/user/tunnel");

// 转发CRUD操作 - 全部使用POST请求
export const createForward = (data: any) => Network.post("/forward/create", data);
export const getForwardList = () => Network.post("/forward/list");
export const updateForward = (data: any) => Network.post("/forward/update", data);
export const deleteForward = (id: number) => Network.post("/forward/delete", { id });
export const forceDeleteForward = (id: number) => Network.post("/forward/force-delete", { id });

// 转发服务控制操作 - 通过Java后端接口
export const pauseForwardService = (forwardId: number) => Network.post("/forward/pause", { id: forwardId });
export const resumeForwardService = (forwardId: number) => Network.post("/forward/resume", { id: forwardId });

// 转发诊断操作
export const diagnoseForward = (forwardId: number) => Network.post("/forward/diagnose", { forwardId });
export const getForwardRouteEvents = (forwardId: number) => Network.post("/forward/route-events", { forwardId });

// 转发排序操作
export const updateForwardOrder = (data: { forwards: Array<{ id: number; inx: number }> }) => Network.post("/forward/update-order", data);

// 用户级卡片布局
export const getLayoutOrder = (scope: string) => Network.post<string[]>("/layout/order", { scope });
export const saveLayoutOrder = (scope: string, order: string[]) => Network.post<string[]>("/layout/order/save", { scope, order });

export type MonitoringRange = '24h' | '7d' | '30d';
export type MonitoringResourceType = 'node' | 'tunnel' | 'forward';
export type MonitoringStatus = 'healthy' | 'degraded' | 'offline' | 'paused' | 'unknown';

export interface MonitoringSummary {
  totalResources: number;
  healthy: number;
  degraded: number;
  offline: number;
  paused: number;
  unknown: number;
  openAlerts: number;
  criticalAlerts: number;
  unreadAlerts: number;
  availability: number;
  trackedFrom: number;
}

export interface MonitoringTrendPoint {
  time: number;
  availability: number | null;
  incidents: number;
}

export interface MonitoringResource {
  type: MonitoringResourceType;
  id: number;
  name: string;
  ownerUserId: number;
  ownerUserName: string;
  status: MonitoringStatus;
  detail: string;
  changedAt: number;
  checkedAt: number;
  availability: number;
  trackedMs: number;
  incidentCount: number;
}

export interface MonitoringOverview {
  range: MonitoringRange;
  summary: MonitoringSummary;
  trend: MonitoringTrendPoint[];
  resources: MonitoringResource[];
}

export interface MonitoringAlertItem {
  id: number;
  resourceType: MonitoringResourceType;
  resourceId: number;
  resourceName: string;
  ownerUserId: number;
  ownerUserName: string;
  severity: 'critical' | 'warning';
  status: 'open' | 'resolved';
  title: string;
  detail: string;
  startedAt: number;
  resolvedAt?: number;
  updatedAt: number;
  read: boolean;
}

export interface MonitoringAlertPage {
  items: MonitoringAlertItem[];
  total: number;
  page: number;
  size: number;
  unread: number;
}

export interface MonitoringAlertQuery {
  status?: 'all' | 'open' | 'resolved';
  resourceType?: 'all' | MonitoringResourceType;
  severity?: 'all' | 'critical' | 'warning';
  keyword?: string;
  page?: number;
  size?: number;
}

export const getMonitoringOverview = (range: MonitoringRange = '24h') =>
  Network.post<MonitoringOverview>("/monitoring/overview", { range });
export const getMonitoringAlerts = (query: MonitoringAlertQuery = {}) =>
  Network.post<MonitoringAlertPage>("/monitoring/alerts", query);
export const markMonitoringAlertsRead = (ids: number[]) =>
  Network.post<number>("/monitoring/alerts/read", { ids });
export const markAllMonitoringAlertsRead = () =>
  Network.post<number>("/monitoring/alerts/read-all");
export const getMonitoringUnreadCount = () =>
  Network.post<number>("/monitoring/alerts/unread-count");

// 限速规则CRUD操作 - 全部使用POST请求
export const createSpeedLimit = (data: any) => Network.post("/speed-limit/create", data);
export const getSpeedLimitList = () => Network.post("/speed-limit/list");
export const updateSpeedLimit = (data: any) => Network.post("/speed-limit/update", data);
export const deleteSpeedLimit = (id: number) => Network.post("/speed-limit/delete", { id });

// 修改密码接口
export const updatePassword = (data: any) => Network.post("/user/updatePassword", data);

// 重置流量接口
export const resetUserFlow = (data: { id: number; type: number }) => Network.post("/user/reset", data);

// 网站配置相关接口
export const getConfigs = () => Network.post("/config/list");
export const getConfigByName = (name: string) => Network.post("/config/get", { name });
export const updateConfigs = (configMap: Record<string, string>) => Network.post("/config/update", configMap);
export const updateConfig = (name: string, value: string) => Network.post("/config/update-single", { name, value });

export type SystemUpdateState = 'idle' | 'queued' | 'running' | 'success' | 'failed' | 'unknown';

export interface SystemUpdateStatus {
  supported: boolean;
  state: SystemUpdateState;
  message: string;
  startedAt: number;
  finishedAt: number;
  logs: string[];
}

export const getSystemUpdateStatus = () => Network.post<SystemUpdateStatus>("/system-update/status");
export const triggerSystemUpdate = () => Network.post<SystemUpdateStatus>("/system-update/trigger");

export interface InternalConnector {
  id: number;
  name: string;
  ownerUserName: string;
  allowedCidrs: string;
  platform: ConnectorPlatform;
  version?: string;
  remoteIp?: string;
  lastSeen?: number;
  online: boolean;
}

export type ConnectorPlatform = 'linux' | 'windows' | 'macos';

export interface PublishingPortPool {
  id: number;
  name: string;
  nodeId: number;
  nodeName: string;
  bindIp: string;
  publicHost: string;
  startPort: number;
  endPort: number;
  controlPort: number;
  defaultLeaseHours: number;
  maxLeaseHours: number;
  cooldownSeconds: number;
  totalPorts: number;
  usedPorts: number;
  availablePorts: number;
}

export interface PublishedService {
  id: number;
  name: string;
  ownerUserName: string;
  connectorId: number;
  connectorName: string;
  connectorOnline: boolean;
  poolId: number;
  poolName: string;
  publicHost: string;
  publicPort: number;
  targetHost: string;
  targetPort: number;
  protocol: string;
  state: string;
  expiresAt?: number;
  lastError?: string;
}

export const createInternalConnector = (data: { name: string; allowedCidrs?: string; platform: ConnectorPlatform }) =>
  Network.post<{ connector: InternalConnector; installCommand: string }>("/service-publishing/connector/create", data);
export const getInternalConnectors = () =>
  Network.post<InternalConnector[]>("/service-publishing/connector/list");
export const getInternalConnectorInstall = (id: number, platform: ConnectorPlatform, action: 'install' | 'uninstall' = 'install') =>
  Network.post<string>("/service-publishing/connector/install", { id, platform, action });
export const deleteInternalConnector = (id: number) =>
  Network.post("/service-publishing/connector/delete", { id });
export const createPublishingPortPool = (data: any) =>
  Network.post<PublishingPortPool>("/service-publishing/pool/create", data);
export const getPublishingPortPools = () =>
  Network.post<PublishingPortPool[]>("/service-publishing/pool/list");
export const deletePublishingPortPool = (id: number) =>
  Network.post("/service-publishing/pool/delete", { id });
export const createPublishedService = (data: any) =>
  Network.post<PublishedService>("/service-publishing/service/create", data);
export const getPublishedServices = () =>
  Network.post<PublishedService[]>("/service-publishing/service/list");
export const renewPublishedService = (id: number, hours: number) =>
  Network.post<PublishedService>("/service-publishing/service/renew", { id, hours });
export const deletePublishedService = (id: number) =>
  Network.post("/service-publishing/service/delete", { id });


// 验证码相关接口
export const checkCaptcha = () => Network.post("/captcha/check");
export const generateCaptcha = () => Network.post(`/captcha/generate`);
export const verifyCaptcha = (data: { captchaId: string; trackData: string }) => Network.post("/captcha/verify", data);
