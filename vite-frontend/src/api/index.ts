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
export const getManualAgentUpgradeCommand = (nodeId: number) =>
  Network.post<string>("/node/upgrade/manual-command", { nodeId });
export const getAgentUpgradeHistory = (nodeId?: number) =>
  Network.post<AgentUpgradeTask[]>("/node/upgrade/history", nodeId ? { nodeId } : {});

export interface ServerAsset {
  id: number; nodeId?: number; nodeName?: string; nodeStatus?: number; name: string; provider?: string; region?: string;
  cpuSpec?: string; memoryMb?: number; diskGb?: number; bandwidthMbps?: number; currency: string; monthlyCost: number;
  purchaseDate?: number; expiryDate?: number; remainingDays?: number; autoRenew: boolean; ipv4?: string; ipv6?: string;
  asn?: string; networkLine?: string; trafficPlan?: string; tags?: string; notes?: string;
  reminderEnabled: boolean; reminderDays: string; createdTime: number; updatedTime: number;
}

export interface ServerAssetOverview {
  items: ServerAsset[];
  summary: { total: number; expiringSoon: number; expired: number; costByCurrency: Array<{ currency: string; monthlyCost: number }> };
}

export const getServerAssets = () => Network.post<ServerAssetOverview>("/server-assets/list");
export const saveServerAsset = (data: Partial<ServerAsset>) => Network.post("/server-assets/save", data);
export const deleteServerAsset = (id: number) => Network.post("/server-assets/delete", { id });

export type DynamicDnsProviderType = 'cloudflare' | 'dnspod' | 'aliyun';
export interface DynamicDnsProviderOption {
  optionKey: string; source: 'dns' | 'dynamic'; id: number; name: string; provider: DynamicDnsProviderType;
  enabled: boolean; zoneRefId?: number; zoneName?: string; lastError?: string; credentialConfigured?: boolean;
}
export interface DynamicDnsRule {
  id: number; name: string; sourceType: 'node' | 'connector'; nodeId?: number; nodeName?: string; nodeVersion?: string; nodeOnline?: boolean;
  connectorId?: number; connectorName?: string; connectorVersion?: string; connectorOnline?: boolean;
  providerSource: 'dns' | 'dynamic'; providerRefId: number; provider: DynamicDnsProviderType; providerAccountName?: string;
  zoneRefId?: number; zoneName: string; recordName: string; recordType: 'A' | 'AAAA'; ttl: number;
  checkIntervalSeconds: number; enabled: boolean; lastDetectedIp?: string; lastAppliedIp?: string;
  lastStatus: 'pending' | 'success' | 'error'; lastError?: string; lastCheckedAt?: number; lastUpdatedAt?: number;
}
export interface DynamicDnsOverview {
  rules: DynamicDnsRule[]; providers: DynamicDnsProviderOption[];
  summary: { rules: number; active: number; healthy: number; errors: number }; minimumAgentVersion: string;
}
export interface DynamicDnsHistoryItem { id: number; ruleId: number; oldIp?: string; newIp?: string; status: string; error?: string; createdTime: number }
export const getDynamicDnsOverview = () => Network.post<DynamicDnsOverview>("/dynamic-dns/overview");
export const getDynamicDnsHistory = (id: number) => Network.post<DynamicDnsHistoryItem[]>("/dynamic-dns/history", { id });
export const saveDynamicDnsProvider = (data: any) => Network.post("/dynamic-dns/provider/save", data);
export const deleteDynamicDnsProvider = (id: number) => Network.post("/dynamic-dns/provider/delete", { id });
export const saveDynamicDnsRule = (data: any) => Network.post("/dynamic-dns/rule/save", data);
export const deleteDynamicDnsRule = (id: number) => Network.post("/dynamic-dns/rule/delete", { id });
export const runDynamicDnsRule = (id: number) => Network.post("/dynamic-dns/rule/run", { id });
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

export type PrivateProxyType = 'socks5' | 'http' | 'shadowsocks' | 'vless_reality';

export interface PrivateProxyItem {
  id: number;
  userId: number;
  ownerUserName: string;
  name: string;
  nodeId: number;
  nodeName: string;
  publicHost?: string;
  nodeOnline: boolean;
  proxyType: PrivateProxyType;
  bindIp?: string;
  listenPort: number;
  authUsername: string;
  passwordConfigured: boolean;
  allowedCidrs?: string;
  state: 'provisioning' | 'active' | 'paused' | 'error' | 'delete_pending' | 'expired';
  expiresAt?: number;
  lastError?: string;
  inFlow?: number;
  outFlow?: number;
  createdTime: number;
}

export interface PrivateProxyCreateRequest {
  name: string;
  nodeId: number;
  proxyType: PrivateProxyType;
  bindIp?: string;
  listenPort: number;
  authUsername?: string;
  authPassword?: string;
  cipher?: 'aes-128-gcm' | 'aes-256-gcm' | 'chacha20-ietf-poly1305';
  realityServerName?: string;
  allowedCidrs?: string;
  leaseHours?: number;
  permanent: boolean;
}

export interface PrivateProxyClientConfig {
  proxyType: PrivateProxyType;
  name: string;
  host: string;
  port: number;
  uri: string;
  username?: string;
  password?: string;
  cipher?: string;
  clientId?: string;
  publicKey?: string;
  shortId?: string;
  serverName?: string;
  fingerprint?: string;
  flow?: string;
  runtimeVersion?: string;
}

export const getPrivateProxies = () => Network.post<PrivateProxyItem[]>("/private-proxy/list");
export const createPrivateProxy = (data: PrivateProxyCreateRequest) => Network.post<PrivateProxyItem>("/private-proxy/create", data);
export const getPrivateProxyClientConfig = (id: number) => Network.post<PrivateProxyClientConfig>("/private-proxy/client-config", { id });
export const pausePrivateProxy = (id: number) => Network.post("/private-proxy/pause", { id });
export const resumePrivateProxy = (id: number) => Network.post("/private-proxy/resume", { id });
export const deletePrivateProxy = (id: number) => Network.post("/private-proxy/delete", { id });

export interface NetworkDiagnosticResult {
  mode: 'ping' | 'tcp' | 'dns' | 'trace';
  target: string;
  success: boolean;
  summary: string;
  output?: string;
  addresses?: string[];
  durationMs: number;
}

export const runNetworkDiagnostic = (data: {
  nodeId: number;
  mode: 'ping' | 'tcp' | 'dns' | 'trace';
  target: string;
  port?: number;
  recordType?: string;
  count?: number;
  timeoutMs?: number;
}) => Network.post<NetworkDiagnosticResult>("/network-tools/run", data);

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
  lastSwitchEvent?: CrossEntryEvent;
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
  fromForwardName?: string;
  toForwardName?: string;
  fromEntryAddress?: string;
  fromEntryPort?: number;
  toEntryAddress?: string;
  toEntryPort?: number;
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

export interface SmartEntryRoute {
  id: number;
  carrier: 'default' | 'telecom' | 'unicom' | 'mobile';
  forwardId: number;
  entryNodeId: number;
  entryHost: string;
  entryAddress: string;
  entryPort: number;
  forwardName: string;
  nodeName: string;
  recordId?: string;
  currentForwardId?: number;
  currentAddress?: string;
  status: 'unknown' | 'healthy' | 'unhealthy';
  failCount: number;
  successCount: number;
  latencyMs?: number;
  lastError?: string;
  lastCheckedAt?: number;
}

export interface SmartEntryActivity {
  forwardId: number;
  entryNodeId: number;
  nodeName: string;
  entryAddress: string;
  agentVersion?: string;
  carriers: string;
  telemetryReady: boolean | number;
  totalConnections: number;
  currentConnections: number;
  inFlow: number;
  outFlow: number;
  lastActivityAt?: number;
  lastTelemetryAt?: number;
}

export interface SmartEntryGroup {
  id: number;
  name: string;
  providerRefId: number;
  provider: 'dnspod' | 'aliyun';
  providerName: string;
  zoneName: string;
  domain: string;
  recordType: 'A' | 'AAAA';
  ttl: number;
  publicPort: number;
  probeIntervalMs: number;
  connectTimeoutMs: number;
  failureThreshold: number;
  recoveryThreshold: number;
  enabled: boolean | number;
  state: 'unknown' | 'healthy' | 'degraded' | 'offline' | 'error';
  lastError?: string;
  lastCheckedAt?: number;
  routes: SmartEntryRoute[];
  activities: SmartEntryActivity[];
}

export interface SmartEntryProviderOption { id: number; name: string; provider: 'dnspod' | 'aliyun'; }
export interface SmartEntryForwardOption {
  id: number; name: string; inPort: number; protocolMode: string; inNodeId: number;
  nodeName: string; entryHost: string; tunnelName: string;
}
export interface SmartEntryEvent { id: number; carrier?: string; eventType: string; status: string; detail: string; createdTime: number; }

export const getSmartEntryOverview = () => Network.post<{
  groups: SmartEntryGroup[];
  summary: { total: number; enabled: number; healthy: number; degraded: number; lineRecords: number };
}>("/smart-entry/overview");
export const getSmartEntryOptions = () => Network.post<{
  providers: SmartEntryProviderOption[];
  forwards: SmartEntryForwardOption[];
}>("/smart-entry/options");
export const getSmartEntryDomains = (providerRefId: number) => Network.post<{
  provider: 'dnspod' | 'aliyun';
  domains: string[];
}>("/smart-entry/domains", { providerRefId });
export const saveSmartEntry = (data: any) => Network.post<{ id: number }>("/smart-entry/save", data);
export const checkSmartEntry = (id: number) => Network.post("/smart-entry/check", { id });
export const getSmartEntryEvents = (id: number) => Network.post<SmartEntryEvent[]>("/smart-entry/events", { id });
export const deleteSmartEntry = (id: number) => Network.post("/smart-entry/delete", { id });

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
export type MonitoringResourceType = 'node' | 'tunnel' | 'forward' | 'certificate' | 'dynamic_dns';
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
  assetExpiryEnabled: boolean;
  dynamicDnsEnabled: boolean;
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
  discoveryEnabled?: number;
  discoveryStatus?: 'disabled' | 'idle' | 'scanning' | 'complete' | 'failed';
  discoveryLastScanAt?: number;
  discoveryLastCidr?: string;
  discoveryLastError?: string;
  discoveredServiceCount?: number;
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
  pathPrefix?: string;
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

export interface ManagedCertificate {
  id: number;
  domain: string;
  zoneName: string;
  accountName: string;
  state: string;
  issuer?: string;
  serialNumber?: string;
  notBefore?: number;
  expiresAt?: number;
  lastError?: string;
  lastAttemptAt?: number;
  nextAttemptAt?: number;
  routeCount: number;
  ingressCount: number;
  createdTime: number;
  updatedTime: number;
}

export type PortLedgerType = 'forward_entry' | 'tunnel_hop' | 'pool_range' | 'pool_control' | 'user_grant' | 'published_service' | 'domain_ingress' | 'home_proxy';

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
export interface LanDiscoveredService {
  id: number;
  connectorId: number;
  host: string;
  port: number;
  serviceType: string;
  serviceName: string;
  product?: string;
  title?: string;
  confidence: 'high' | 'medium';
  sensitive: number;
  firstSeenAt: number;
  lastSeenAt: number;
}
export interface LanDiscoveryResult {
  connectorId: number;
  enabled: boolean;
  status: 'disabled' | 'idle' | 'scanning' | 'complete' | 'failed';
  lastScanAt?: number;
  lastCidr?: string;
  lastError?: string;
  scannedHosts?: number;
  scannedPorts?: number;
  durationMs?: number;
  services: LanDiscoveredService[];
}
export const setLanDiscoveryEnabled = (id: number, enabled: boolean) =>
  Network.post<LanDiscoveryResult>("/service-publishing/connector/discovery/settings", { id, enabled });
export const scanLanServices = (connectorId: number, cidr?: string) =>
  Network.post<LanDiscoveryResult>("/service-publishing/connector/discovery/scan", { connectorId, cidr: cidr || 'auto' });
export const getLanDiscoveryResults = (id: number) =>
  Network.post<LanDiscoveryResult>("/service-publishing/connector/discovery/results", { id });
export const clearLanDiscoveryResults = (id: number) =>
  Network.post("/service-publishing/connector/discovery/clear", { id });
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
export const createDomainRoute = (data: { name: string; domain: string; pathPrefix?: string; publishedServiceId: number; listenPort: number; ingressMode: 'passthrough' | 'managed_https'; dnsZoneId?: number }) =>
  Network.post<DomainRoute>("/service-publishing/domain/create", data);
export const getDomainRoutes = () =>
  Network.post<DomainRoute[]>("/service-publishing/domain/list");
export const deleteDomainRoute = (id: number) =>
  Network.post("/service-publishing/domain/delete", { id });
export const getManagedCertificates = () =>
  Network.post<ManagedCertificate[]>("/service-publishing/certificate/list");
export const retryManagedCertificate = (id: number) =>
  Network.post("/service-publishing/certificate/retry", { id });

export interface HomeProxyRoute {
  id: number;
  userId: number;
  name: string;
  connectorId: number;
  accessMode: 'relay' | 'ipv6_direct' | 'ipv4_direct' | 'smart_nat';
  sourceConnectorId?: number;
  sourceListenPort?: number;
  natBackendPort?: number;
  natState?: 'provisioning' | 'probing' | 'direct' | 'relay' | 'failed';
  activeAccessPath?: 'udp_direct' | 'relay';
  natType?: string;
  directSuccessCount?: number;
  directFailureCount?: number;
  directRxBytes?: number;
  directTxBytes?: number;
  relayRxBytes?: number;
  relayTxBytes?: number;
  lastNatProbeAt?: number;
  lastPathSwitchAt?: number;
  lastNatError?: string;
  ingressPoolId?: number;
  egressPoolId?: number;
  egressNodeId?: number;
  egressMode?: 'single' | 'tunnel';
  egressTunnelId?: number;
  transportMode?: 'standard_tcp' | 'socks5' | 'vless_reality';
  realityServerName?: string;
  leaseId?: number;
  publicPort?: number;
  egressLeaseId?: number;
  egressGatewayPort?: number;
  directIpv6?: string;
  directIpv4?: string;
  directPort?: number;
  ipv6CheckedAt?: number;
  ipCheckedAt?: number;
  dynamicDnsRuleId?: number;
  publicDomain?: string;
  proxyType: 'socks5';
  authEnabled: number;
  authUsername?: string;
  authPassword?: string;
  state: 'provisioning' | 'active' | 'error' | 'delete_pending' | 'deleted';
  lastError?: string;
  ownerUserName?: string;
  connectorName?: string;
  connectorOnline?: boolean;
  sourceConnectorName?: string;
  sourceConnectorOnline?: boolean;
  clientEndpoint?: string;
  ingressPoolName?: string;
  egressPoolName?: string;
  egressTunnelName?: string;
  egressNodeName?: string;
  egressNodeOnline?: boolean;
  egressPathNodeDetails?: Array<{ nodeId: number; name: string; status: number }>;
  publicHost?: string;
}

export const createHomeProxyRoute = (data: {
  name: string; connectorId: number; accessMode: 'relay' | 'ipv6_direct' | 'ipv4_direct' | 'smart_nat';
  sourceConnectorId?: number; sourceListenPort?: number;
  ingressPoolId?: number; ingressGrantId?: number;
  egressPoolId?: number; egressGrantId?: number;
  egressNodeId?: number;
  egressMode?: 'single' | 'tunnel'; egressTunnelId?: number;
  transportMode?: 'socks5' | 'vless_reality'; realityServerName?: string;
  directPort?: number; dynamicDnsRuleId?: number;
  authEnabled?: boolean; authUsername?: string; authPassword?: string;
}) => Network.post<HomeProxyRoute>("/service-publishing/home-proxy/create", data);
export const getHomeProxyRoutes = () => Network.post<HomeProxyRoute[]>("/service-publishing/home-proxy/list");
export const refreshHomeProxyIpv6 = (id: number) =>
  Network.post<{ address: string; checkedAt: number; family?: 'ipv4' | 'ipv6' }>("/service-publishing/home-proxy/refresh-ipv6", { id });
export const deleteHomeProxyRoute = (id: number) => Network.post("/service-publishing/home-proxy/delete", { id });
export interface HomeProxyNatEvent {
  id: number; routeId: number; eventType: string; accessPath?: string; detail?: string; createdTime: number;
}
export const retryHomeProxyNat = (id: number) =>
  Network.post<HomeProxyRoute>("/service-publishing/home-proxy/nat/retry", { id });
export const getHomeProxyNatEvents = (id: number) =>
  Network.post<HomeProxyNatEvent[]>("/service-publishing/home-proxy/nat/events", { id });


// 验证码相关接口
export const checkCaptcha = () => Network.post("/captcha/check");
export const generateCaptcha = () => Network.post(`/captcha/generate`);
export const verifyCaptcha = (data: { captchaId: string; trackData: string }) => Network.post("/captcha/verify", data);
