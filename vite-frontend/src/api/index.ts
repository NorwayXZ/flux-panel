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
export interface AgentUpgradeTask {
  taskId: string;
  fromVersion?: string;
  targetVersion: string;
  state: string;
  message?: string;
  requestedAt: number;
  updatedAt: number;
  finishedAt?: number;
}

export interface AgentUpgradeStatusItem {
  nodeId: number;
  nodeName: string;
  currentVersion?: string;
  targetVersion: string;
  online: boolean;
  upToDate: boolean;
  mode: 'self' | 'terminal' | 'manual';
  task?: AgentUpgradeTask | null;
}

export interface AgentUpgradeStatus {
  targetVersion: string;
  items: AgentUpgradeStatusItem[];
}

export const getAgentUpgradeStatus = (nodeId?: number) =>
  Network.post<AgentUpgradeStatus>("/node/upgrade/status", nodeId ? { nodeId } : {});
export const startAgentUpgrade = (nodeId: number) =>
  Network.post<AgentUpgradeStatusItem>("/node/upgrade/start", { nodeId });
export const startBatchAgentUpgrade = () =>
  Network.post<{ submitted: number; results: Array<{ nodeId: number; nodeName: string; accepted: boolean; message: string }> }>("/node/upgrade/batch");
export const getAgentUpgradeHistory = (nodeId?: number) =>
  Network.post<AgentUpgradeTask[]>("/node/upgrade/history", nodeId ? { nodeId } : {});
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

export const setNodeTerminalEnabled = (data: { nodeId: number; enabled: boolean }) =>
  Network.post<{ nodeId: number; enabled: boolean; operator: string }>("/terminal/node/toggle", data);
export const createTerminalSession = (data: { nodeId: number }) =>
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

export interface CrossEntryForwardOption {
  id: number;
  name: string;
  inPort: number;
  protocolMode: string;
  inNodeId: number;
  nodeName: string;
  entryHost: string;
  tunnelName: string;
}

export interface CrossEntryMember {
  id: number;
  forwardId: number;
  priority: number;
  entryNodeId: number;
  entryHost: string;
  entryAddress: string;
  entryPort: number;
  forwardName: string;
  nodeName: string;
  status: 'unknown' | 'healthy' | 'unhealthy';
  failCount: number;
  successCount: number;
  latencyMs?: number;
  lastError?: string;
  lastCheckedAt?: number;
}

export interface CrossEntryGroup {
  id: number;
  name: string;
  domain: string;
  dnsZoneId?: number;
  zoneName?: string;
  zoneId: string;
  recordId: string;
  recordType: 'A' | 'AAAA';
  ttl: number;
  probeIntervalMs: number;
  connectTimeoutMs: number;
  failureThreshold: number;
  recoveryThreshold: number;
  cooldownSeconds: number;
  autoFailback: boolean | number;
  enabled: boolean | number;
  state: 'unknown' | 'healthy' | 'degraded' | 'offline' | 'switching' | 'error';
  activeMemberId?: number;
  lastError?: string;
  lastCheckedAt?: number;
  lastSwitchAt?: number;
  apiTokenConfigured: boolean | number;
  members: CrossEntryMember[];
}

export interface CrossEntrySummary {
  total: number;
  enabled: number;
  healthy: number;
  degraded: number;
  switches: number;
}

export interface CrossEntryEvent {
  id: number;
  reason: string;
  status: 'success' | 'failed';
  detail?: string;
  fromNodeName?: string;
  toNodeName?: string;
  createdTime: number;
}

export const getCrossEntryGroups = () =>
  Network.post<{ groups: CrossEntryGroup[]; summary: CrossEntrySummary }>("/cross-entry-failover/list");
export const getCrossEntryForwardOptions = () =>
  Network.post<CrossEntryForwardOption[]>("/cross-entry-failover/eligible-forwards");
export const saveCrossEntryGroup = (data: any) => Network.post<{ id: number }>("/cross-entry-failover/save", data);
export const deleteCrossEntryGroup = (id: number) => Network.post("/cross-entry-failover/delete", { id });
export const checkCrossEntryGroup = (id: number) =>
  Network.post<{ groups: CrossEntryGroup[]; summary: CrossEntrySummary }>("/cross-entry-failover/check", { id });
export const getCrossEntryEvents = (id: number) => Network.post<CrossEntryEvent[]>("/cross-entry-failover/events", { id });

export interface DnsProviderAccount {
  id: number;
  name: string;
  provider: 'cloudflare';
  enabled: boolean | number;
  apiTokenConfigured: boolean | number;
  zoneCount: number;
  lastSyncAt?: number;
  lastError?: string;
  createdTime: number;
}

export interface DnsZone {
  id: number;
  accountId: number;
  accountName: string;
  providerZoneId: string;
  zoneName: string;
  status: 'active' | 'inactive';
  recordCount: number;
  failoverCount: number;
  updatedTime: number;
}

export interface DnsManagedRecord {
  id: number;
  zoneId: number;
  zoneName: string;
  providerRecordId: string;
  fqdn: string;
  recordType: 'A' | 'AAAA';
  content: string;
  ttl: number;
  ownerType?: string;
  ownerId?: number;
  ownerName?: string;
  status: string;
  lastError?: string;
  updatedTime: number;
}

export interface DnsProviderSummary {
  accounts: number;
  zones: number;
  records: number;
  errors: number;
}

export interface DnsZoneOption {
  id: number;
  accountId: number;
  accountName: string;
  zoneName: string;
  providerZoneId: string;
}

export const getDnsProviderData = () => Network.post<{
  accounts: DnsProviderAccount[];
  zones: DnsZone[];
  records: DnsManagedRecord[];
  summary: DnsProviderSummary;
}>("/dns-provider/list");
export const getDnsZoneOptions = () => Network.post<DnsZoneOption[]>("/dns-provider/zones");
export const saveDnsProviderAccount = (data: { id?: number; name: string; apiToken?: string; enabled: boolean }) =>
  Network.post<{ id: number; zoneCount: number }>("/dns-provider/account/save", data);
export const syncDnsProviderAccount = (id: number) =>
  Network.post<{ zoneCount: number }>("/dns-provider/account/sync", { id });
export const deleteDnsProviderAccount = (id: number) => Network.post("/dns-provider/account/delete", { id });

export interface TopologyResourceNode {
  [key: string]: unknown;
  id: string;
  type: 'user' | 'domain' | 'forward' | 'tunnel' | 'node' | 'mapping' | 'connector' | 'service';
  label: string;
  subtitle: string;
  status: 'healthy' | 'degraded' | 'offline' | 'failed' | 'paused';
  path: string;
  ownerUserId: number;
}

export interface TopologyResourceEdge {
  id: string;
  source: string;
  target: string;
  label: string;
  status: string;
  active: boolean;
}

export interface TopologyGraph {
  nodes: TopologyResourceNode[];
  edges: TopologyResourceEdge[];
  summary: { nodes: number; links: number; healthy: number; abnormal: number };
}

export const getTopologyGraph = () => Network.post<TopologyGraph>("/topology/graph");

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

export interface TelegramNotificationSettings {
  enabled: boolean;
  botToken: string;
  botTokenConfigured: boolean;
  chatId: string;
  nodeEnabled: boolean;
  nodeRepeatLimit: number;
  tunnelEnabled: boolean;
  tunnelRepeatLimit: number;
  forwardEnabled: boolean;
  forwardRepeatLimit: number;
  recoveryEnabled: boolean;
  loginOutsideWhitelistEnabled: boolean;
  loginAllowedCidrs: string;
  repeatIntervalMinutes: number;
}

export const getTelegramNotificationSettings = () =>
  Network.post<TelegramNotificationSettings>("/monitoring/notifications/settings");
export const saveTelegramNotificationSettings = (settings: TelegramNotificationSettings) =>
  Network.post<TelegramNotificationSettings>("/monitoring/notifications/settings/save", settings);
export const testTelegramNotification = () =>
  Network.post<string>("/monitoring/notifications/test");

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
  cooldownSeconds: number;
  totalPorts: number;
  usedPorts: number;
  availablePorts: number;
  sharedPorts?: number;
  grantId?: number;
  grantStartPort?: number;
  grantEndPort?: number;
  grantTotalPorts?: number;
  grantUsedPorts?: number;
  accessType?: 'admin' | 'shared';
}

export interface PublishingPortGrant {
  id: number;
  poolId: number;
  userId: number;
  ownerUserName: string;
  poolName: string;
  nodeId: number;
  nodeName: string;
  publicHost: string;
  startPort: number;
  endPort: number;
  totalPorts: number;
  usedPorts: number;
  availablePorts: number;
}

export interface PublishedService {
  id: number;
  name: string;
  ownerUserName: string;
  ownerRoleId?: number;
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
  permanent?: boolean;
  grantId?: number;
  grantStartPort?: number;
  grantEndPort?: number;
  lastError?: string;
}

export interface DomainRoute {
  id: number;
  name: string;
  domain: string;
  ownerUserName: string;
  ownerRoleId?: number;
  publishedServiceId: number;
  mappingName: string;
  mappingState: string;
  mappingPublicPort?: number;
  nodeId: number;
  nodeName: string;
  nodeOnline: boolean;
  connectorOnline: boolean;
  publicHost?: string;
  listenPort: number;
  state: string;
  lastError?: string;
  ingressMode?: 'passthrough' | 'managed_https';
  dnsZoneId?: number;
  dnsRecordId?: string;
  certificateId?: number;
  certificateState?: string;
  certificateExpiresAt?: number;
  certificateIssuer?: string;
}

export type PortLedgerType = 'forward_entry' | 'tunnel_hop' | 'pool_range' | 'pool_control' | 'user_grant' | 'published_service' | 'domain_ingress';

export interface PortLedgerEntry {
  key: string;
  type: PortLedgerType;
  status: 'occupied' | 'reserved' | 'granted' | 'cooldown';
  nodeId: number;
  nodeName: string;
  namespace: string;
  serverAddress: string;
  portStart: number;
  portEnd: number;
  protocol: string;
  ownerUserId?: number;
  ownerUserName: string;
  resourceId: number;
  resourceName: string;
  detail: string;
  createdTime?: number;
  expiresAt?: number;
}

export interface PortLedgerResult {
  entries: PortLedgerEntry[];
  summary: Record<string, number>;
  total: number;
  occupied?: boolean;
  nodeId?: number;
  port?: number;
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
export const getPublishingPortGrants = (userId?: number) =>
  Network.post<PublishingPortGrant[]>("/service-publishing/grant/list", userId ? { userId } : {});
export const deletePublishingPortPool = (id: number) =>
  Network.post("/service-publishing/pool/delete", { id });
export const getPortLedger = (data: { nodeId?: number; port?: number; type?: string; keyword?: string } = {}) =>
  Network.post<PortLedgerResult>("/service-publishing/ledger/list", data);
export const diagnosePort = (nodeId: number, port: number) =>
  Network.post<PortLedgerResult>("/service-publishing/ledger/diagnose", { nodeId, port });
export const createPublishedService = (data: any) =>
  Network.post<PublishedService>("/service-publishing/service/create", data);
export const getPublishedServices = () =>
  Network.post<PublishedService[]>("/service-publishing/service/list");
export const renewPublishedService = (id: number, hours?: number, permanent = false) =>
  Network.post<PublishedService>("/service-publishing/service/renew", { id, hours, permanent });
export const deletePublishedService = (id: number) =>
  Network.post("/service-publishing/service/delete", { id });
export const createDomainRoute = (data: { name: string; domain: string; publishedServiceId: number; listenPort: number; ingressMode: 'passthrough' | 'managed_https'; dnsZoneId?: number }) =>
  Network.post<DomainRoute>("/service-publishing/domain/create", data);
export const getDomainRoutes = () =>
  Network.post<DomainRoute[]>("/service-publishing/domain/list");
export const deleteDomainRoute = (id: number) =>
  Network.post("/service-publishing/domain/delete", { id });


// 验证码相关接口
export const checkCaptcha = () => Network.post("/captcha/check");
export const generateCaptcha = () => Network.post(`/captcha/generate`);
export const verifyCaptcha = (data: { captchaId: string; trackData: string }) => Network.post("/captcha/verify", data);
