import Network from "./network";

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

export const login = (data: LoginData) =>
  Network.post<LoginResponse>("/user/login", data);

// 用户CRUD操作 - 全部使用POST请求
export const createUser = (data: any) => Network.post("/user/create", data);
export const getAllUsers = (pageData: any = {}) =>
  Network.post("/user/list", pageData);
export const updateUser = (data: any) => Network.post("/user/update", data);
export const deleteUser = (id: number) => Network.post("/user/delete", { id });
export const getUserPackageInfo = () => Network.post("/user/package");

// 节点CRUD操作 - 全部使用POST请求
export const createNode = (data: any) =>
  Network.mutate("/node/create", data, ["/node/list"]);
export const getNodeList = () => Network.postCached("/node/list", {}, 8000);
export const updateNode = (data: any) =>
  Network.mutate("/node/update", data, ["/node/list"]);
export const deleteNode = (id: number) =>
  Network.mutate("/node/delete", { id }, ["/node/list"]);
export const getNodeInstallCommand = (id: number) =>
  Network.post("/node/install", { id });
export interface NodeDiscoveredService {
  host: string;
  probeHost: string;
  port: number;
  protocol: string;
  serviceName: string;
  processName?: string;
  processId?: number;
  executable?: string;
  product?: string;
  title?: string;
  httpStatus?: number;
  latencyMs?: number;
  containerId?: string;
  containerName?: string;
  containerImage?: string;
  sensitive?: boolean;
}
export interface NodeServiceDiscoveryResult {
  nodeId: number;
  nodeName: string;
  nodeAddress?: string;
  services: NodeDiscoveredService[];
  listenerCount: number;
  webServiceCount: number;
  dockerAvailable: boolean;
  scannedAt: number;
  durationMs: number;
  minimumAgentVersion: string;
}
export const discoverNodeServices = (nodeId: number) =>
  Network.post<NodeServiceDiscoveryResult>("/node/discovery/scan", { nodeId });

export interface DockerAppTemplate {
  id: string;
  name: string;
  category: string;
  image: string;
  versionLabel?: string;
  defaultHostPort: number;
  containerPort: number;
}
export interface DockerAppNode {
  id: number;
  name: string;
  serverIp?: string;
  ip?: string;
  status: number;
  version?: string;
  online: boolean;
  compatible: boolean;
}
export interface DockerAppInstance {
  id: number;
  userId: number;
  nodeId: number;
  nodeName?: string;
  nodeOnline: boolean;
  templateId: string;
  name: string;
  containerName: string;
  image: string;
  versionLabel?: string;
  hostPort: number;
  containerPort: number;
  domainRouteId?: number;
  domain?: string;
  state:
    | "draft"
    | "provisioning"
    | "operating"
    | "active"
    | "error"
    | "delete_pending"
    | "deleted";
  lastError?: string;
  lastCommand?: string;
  rollbackCommand?: string;
  composePath?: string;
  backupPath?: string;
  detected: boolean;
  createdTime: number;
  updatedTime: number;
}
export interface DockerAppOverview {
  nodes: DockerAppNode[];
  templates: DockerAppTemplate[];
  apps: DockerAppInstance[];
  summary: {
    apps: number;
    active: number;
    errors: number;
    dockerReadyNodes: number;
  };
  minimumAgentVersion: string;
}
export interface DockerContainerInfo {
  id: string;
  name: string;
  image: string;
  state: string;
  status: string;
  ports?: Array<{
    privatePort: number;
    publicPort?: number;
    type: string;
    ip?: string;
  }>;
}
export interface DockerInspectResult {
  nodeId: number;
  nodeName: string;
  dockerAvailable: boolean;
  dockerVersion?: string;
  composeAvailable: boolean;
  containers: DockerContainerInfo[];
  error?: string;
  minimumAgentVersion: string;
}
export interface DockerAppEvent {
  id: number;
  appId?: number;
  nodeId: number;
  eventType: string;
  status: string;
  detail?: string;
  createdTime: number;
}
export const getDockerAppOverview = () =>
  Network.postCached<DockerAppOverview>("/docker-apps/overview", {}, 8000);
export const inspectDockerNode = (nodeId: number) =>
  Network.post<DockerInspectResult>("/docker-apps/inspect", { nodeId });
export const deployDockerApp = (data: {
  nodeId: number;
  templateId: string;
  name: string;
  containerName?: string;
  hostPort?: number;
  bindDomain?: boolean;
  domain?: string;
  dnsZoneId?: number;
  entryNodeId?: number;
  listenPort?: number;
  pathPrefix?: string;
  backendPath?: string;
}) =>
  Network.mutate<DockerAppOverview>("/docker-apps/deploy", data, [
    "/docker-apps/overview",
  ]);
export const runDockerAppAction = (
  id: number,
  action: "upgrade" | "backup" | "stop" | "start" | "remove" | "rollback",
) =>
  Network.mutate<DockerAppOverview>("/docker-apps/action", { id, action }, [
    "/docker-apps/overview",
  ]);
export const getDockerAppCommand = (
  id: number,
  action: "upgrade" | "backup" | "stop" | "start" | "remove" | "rollback",
) => Network.post<string>("/docker-apps/command", { id, action });
export const getDockerAppEvents = (id: number) =>
  Network.post<DockerAppEvent[]>("/docker-apps/events", { id });
export const checkNodeStatus = (nodeId?: number) => {
  const params = nodeId ? { nodeId } : {};

  return Network.mutate("/node/check-status", params, ["/node/list"]);
};
export interface AgentUpgradeTask {
  taskId: string;
  fromVersion?: string;
  targetVersion: string;
  state: string;
  message?: string;
  requestedAt: number;
  updatedAt: number;
  live?: boolean;
  finishedAt?: number;
}

export interface AgentUpgradeStatusItem {
  nodeId: number;
  nodeName: string;
  currentVersion?: string;
  targetVersion: string;
  online: boolean;
  upToDate: boolean;
  mode: "self" | "terminal" | "manual";
  task?: AgentUpgradeTask | null;
}

export interface AgentUpgradeStatus {
  targetVersion: string;
  items: AgentUpgradeStatusItem[];
  batch?: AgentUpgradeBatch | null;
}

export interface AgentUpgradeBatch {
  batchId: string;
  targetVersion: string;
  state: "running" | "paused" | "success" | "completed_with_errors";
  mode: "parallel" | "staged";
  totalNodes: number;
  completedNodes: number;
  currentNodeId?: number;
  currentNodeName?: string;
  message?: string;
  startedAt: number;
  updatedAt: number;
  finishedAt?: number;
}

export const getAgentUpgradeStatus = (nodeId?: number) =>
  Network.post<AgentUpgradeStatus>(
    "/node/upgrade/status",
    nodeId ? { nodeId } : {},
  );
export const startAgentUpgrade = (nodeId: number) =>
  Network.post<AgentUpgradeStatusItem>("/node/upgrade/start", { nodeId });
export const startBatchAgentUpgrade = (
  mode: "parallel" | "staged" = "parallel",
) => Network.post<AgentUpgradeBatch>("/node/upgrade/batch", { mode });
export const getManualAgentUpgradeCommand = (nodeId: number) =>
  Network.post<string>("/node/upgrade/manual-command", { nodeId });
export const getAgentUpgradeHistory = (nodeId?: number) =>
  Network.post<AgentUpgradeTask[]>(
    "/node/upgrade/history",
    nodeId ? { nodeId } : {},
  );

export type SelfCheckStatus = "healthy" | "warning" | "failed" | "skipped";
export interface SystemSelfCheckRun {
  id: number;
  status: "running" | "completed" | "failed";
  scopeNodeId?: number;
  totalChecks: number;
  healthyCount: number;
  warningCount: number;
  failedCount: number;
  skippedCount: number;
  scopeType?: "node" | "connector";
  scopeResourceId?: number;
  message?: string;
  startedAt: number;
  finishedAt?: number;
}
export interface SystemSelfCheckFinding {
  id: number;
  category: string;
  resourceType: string;
  resourceId?: number;
  resourceName?: string;
  status: SelfCheckStatus;
  faultSegment: string;
  summary: string;
  evidence?: string;
  impact?: string;
  remediation?: string;
  sortOrder: number;
  createdAt: number;
}
export interface SystemSelfCheckNode {
  id: number;
  name: string;
  serverIp?: string;
  ip?: string;
  status: number;
  version?: string;
}
export interface SystemSelfCheckOverview {
  minimumAgentVersion: string;
  minimumConnectorVersion?: string;
  nodes: SystemSelfCheckNode[];
  connectors?: InternalConnector[];
  run?: SystemSelfCheckRun | null;
  findings: SystemSelfCheckFinding[];
  history: SystemSelfCheckRun[];
}
export const getSystemSelfCheckOverview = () =>
  Network.post<SystemSelfCheckOverview>("/system-self-check/overview");
export const runSystemSelfCheck = (nodeId?: number, connectorId?: number) =>
  Network.post<SystemSelfCheckOverview>(
    "/system-self-check/run",
    nodeId ? { nodeId } : connectorId ? { connectorId } : {},
  );
export const resetAgentIdentityBaseline = (nodeId: number) =>
  Network.post<string>("/system-self-check/identity/reset", { nodeId });

export interface ServerAsset {
  id: number;
  nodeId?: number;
  nodeName?: string;
  nodeStatus?: number;
  name: string;
  provider?: string;
  region?: string;
  cpuSpec?: string;
  memoryMb?: number;
  diskGb?: number;
  bandwidthMbps?: number;
  currency: string;
  monthlyCost: number;
  purchaseDate?: number;
  expiryDate?: number;
  remainingDays?: number;
  autoRenew: boolean;
  ipv4?: string;
  ipv6?: string;
  asn?: string;
  networkLine?: string;
  trafficPlan?: string;
  tags?: string;
  notes?: string;
  reminderEnabled: boolean;
  reminderDays: string;
  createdTime: number;
  updatedTime: number;
}

export interface ServerAssetOverview {
  items: ServerAsset[];
  summary: {
    total: number;
    expiringSoon: number;
    expired: number;
    costByCurrency: Array<{ currency: string; monthlyCost: number }>;
  };
}

export const getServerAssets = () =>
  Network.post<ServerAssetOverview>("/server-assets/list");
export const saveServerAsset = (data: Partial<ServerAsset>) =>
  Network.post("/server-assets/save", data);
export const deleteServerAsset = (id: number) =>
  Network.post("/server-assets/delete", { id });

export type DynamicDnsProviderType = "cloudflare" | "dnspod" | "aliyun";
export interface DynamicDnsProviderOption {
  optionKey: string;
  source: "dns" | "dynamic";
  id: number;
  name: string;
  provider: DynamicDnsProviderType;
  enabled: boolean;
  zoneRefId?: number;
  zoneName?: string;
  lastError?: string;
  credentialConfigured?: boolean;
}
export interface DynamicDnsRule {
  id: number;
  name: string;
  sourceType: "node" | "connector";
  nodeId?: number;
  nodeName?: string;
  nodeVersion?: string;
  nodeOnline?: boolean;
  connectorId?: number;
  connectorName?: string;
  connectorVersion?: string;
  connectorOnline?: boolean;
  providerSource: "dns" | "dynamic";
  providerRefId: number;
  provider: DynamicDnsProviderType;
  providerAccountName?: string;
  zoneRefId?: number;
  zoneName: string;
  recordName: string;
  recordType: "A" | "AAAA";
  ttl: number;
  checkIntervalSeconds: number;
  enabled: boolean;
  lastDetectedIp?: string;
  lastAppliedIp?: string;
  lastStatus: "pending" | "success" | "error";
  lastError?: string;
  lastCheckedAt?: number;
  lastUpdatedAt?: number;
}
export interface DynamicDnsOverview {
  rules: DynamicDnsRule[];
  providers: DynamicDnsProviderOption[];
  summary: { rules: number; active: number; healthy: number; errors: number };
  minimumAgentVersion: string;
}
export interface DynamicDnsHistoryItem {
  id: number;
  ruleId: number;
  oldIp?: string;
  newIp?: string;
  status: string;
  error?: string;
  createdTime: number;
}
export const getDynamicDnsOverview = () =>
  Network.postCached<DynamicDnsOverview>("/dynamic-dns/overview", {}, 8000);
export const getDynamicDnsHistory = (id: number) =>
  Network.post<DynamicDnsHistoryItem[]>("/dynamic-dns/history", { id });
export const saveDynamicDnsProvider = (data: any) =>
  Network.mutate("/dynamic-dns/provider/save", data, ["/dynamic-dns/overview"]);
export const deleteDynamicDnsProvider = (id: number) =>
  Network.mutate("/dynamic-dns/provider/delete", { id }, [
    "/dynamic-dns/overview",
  ]);
export const saveDynamicDnsRule = (data: any) =>
  Network.mutate("/dynamic-dns/rule/save", data, ["/dynamic-dns/overview"]);
export const deleteDynamicDnsRule = (id: number) =>
  Network.mutate("/dynamic-dns/rule/delete", { id }, ["/dynamic-dns/overview"]);
export const runDynamicDnsRule = (id: number) =>
  Network.mutate("/dynamic-dns/rule/run", { id }, ["/dynamic-dns/overview"]);
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

export const setNodeTerminalEnabled = (data: {
  nodeId: number;
  enabled: boolean;
}) =>
  Network.post<{ nodeId: number; enabled: boolean; operator: string }>(
    "/terminal/node/toggle",
    data,
  );
export const createTerminalSession = (data: { nodeId: number }) =>
  Network.post<TerminalTicket>("/terminal/session/create", data);
export const getTerminalAudit = (nodeId?: number) =>
  Network.post<TerminalAuditItem[]>(
    "/terminal/audit/list",
    nodeId ? { nodeId } : {},
  );
export const assignUserNode = (data: { userId: number; nodeId: number }) =>
  Network.post("/node/user/assign", data);
export const getUserNodeList = (userId: number) =>
  Network.post("/node/user/list", { userId });
export const removeUserNode = (data: { userId: number; nodeId: number }) =>
  Network.post("/node/user/remove", data);

// 隧道CRUD操作 - 全部使用POST请求
export const createTunnel = (data: any) =>
  Network.mutate("/tunnel/create", data, ["/tunnel/list"]);
export const getTunnelList = () => Network.postCached("/tunnel/list", {}, 8000);
export const getTunnelById = (id: number) =>
  Network.post("/tunnel/get", { id });
export const updateTunnel = (data: any) =>
  Network.mutate("/tunnel/update", data, ["/tunnel/list"]);
export const deleteTunnel = (id: number) =>
  Network.mutate("/tunnel/delete", { id }, ["/tunnel/list"]);
export const diagnoseTunnel = (tunnelId: number) =>
  Network.post("/tunnel/diagnose", { tunnelId });

// 用户隧道权限管理操作 - 全部使用POST请求
export const assignUserTunnel = (data: any) =>
  Network.post("/tunnel/user/assign", data);
export const getUserTunnelList = (queryData: any = {}) =>
  Network.post("/tunnel/user/list", queryData);
export const removeUserTunnel = (params: any) =>
  Network.post("/tunnel/user/remove", params);
export const updateUserTunnel = (data: any) =>
  Network.post("/tunnel/user/update", data);
export const userTunnel = () => Network.post("/tunnel/user/tunnel");

// 转发CRUD操作 - 全部使用POST请求
export const createForward = (data: any) =>
  Network.mutate("/forward/create", data, ["/forward/list"]);
export const getForwardList = () =>
  Network.postCached("/forward/list", {}, 5000);
export const updateForward = (data: any) =>
  Network.mutate("/forward/update", data, ["/forward/list"]);
export const deleteForward = (id: number) =>
  Network.mutate("/forward/delete", { id }, ["/forward/list"]);
export const forceDeleteForward = (id: number) =>
  Network.mutate("/forward/force-delete", { id }, ["/forward/list"]);

export type AggregationMode = "speed" | "balanced" | "stability";
export type AggregationProtocol = "tcp" | "udp" | "tcp_udp";

export interface AggregationMember {
  id: number;
  group_id: number;
  tunnel_id: number;
  tunnel_name: string;
  in_node_name?: string;
  out_node_name?: string;
  protocol?: string;
  manual_weight: number;
  effective_weight: number;
  enabled: boolean;
  health_status: "unknown" | "healthy" | "unhealthy" | "offline";
  bandwidth_mbps?: number;
  latency_ms?: number;
  packet_loss_percent?: number;
  jitter_ms?: number;
  metric_measured_at?: number;
  last_checked_at?: number;
  last_error?: string;
  failure_segment?: string;
  failure_address?: string;
  failure_message?: string;
}

export interface AggregationGroup {
  id: number;
  name: string;
  forward_id?: number;
  entry_node_id: number;
  entry_node_name?: string;
  entry_server_ip?: string;
  entry_ip?: string;
  listen_port: number;
  remote_addr: string;
  protocol_mode: AggregationProtocol;
  mode: AggregationMode;
  scheduler: "weighted";
  auto_weight: boolean;
  minimum_healthy_paths: number;
  enabled: boolean;
  state: "provisioning" | "active" | "paused" | "degraded" | "error";
  last_error?: string;
  last_calculated_at?: number;
  healthyPaths: number;
  estimatedCapacityMbps: number;
  degraded: boolean;
  in_flow?: number;
  out_flow?: number;
  members: AggregationMember[];
}

export interface AggregationTunnelOption {
  id: number;
  name: string;
  entryNodeId: number;
  entryNodeName: string;
  exitNodeId: number;
  exitNodeName: string;
  protocol?: string;
  online: boolean;
}

export interface AggregationOverview {
  groups: AggregationGroup[];
  tunnels: AggregationTunnelOption[];
  summary: {
    groups: number;
    active: number;
    healthyPaths: number;
    degraded: number;
    estimatedCapacityMbps: number;
  };
  aggregationType: "multi_session";
  agentUpgradeRequired: boolean;
}

export interface AggregationSaveInput {
  id?: number;
  name: string;
  tunnelIds: number[];
  listenPort: number;
  remoteAddr: string;
  protocolMode: AggregationProtocol;
  mode: AggregationMode;
  autoWeight: boolean;
  minimumHealthyPaths: number;
  manualWeights: Record<number, number>;
}

export interface AggregationEvent {
  id: number;
  groupId: number;
  eventType: string;
  status: string;
  detail?: string;
  snapshotJson?: string;
  createdTime: number;
}

export const getAggregationOverview = () =>
  Network.post<AggregationOverview>("/multi-line-aggregation/overview");
export const saveAggregation = (data: AggregationSaveInput) =>
  Network.post<AggregationOverview>("/multi-line-aggregation/save", data);
export const deployAggregation = (id: number) =>
  Network.post<AggregationOverview>("/multi-line-aggregation/deploy", { id });
export const recalculateAggregation = (id: number) =>
  Network.post<AggregationOverview>("/multi-line-aggregation/recalculate", {
    id,
  });
export const repairAggregation = (id: number) =>
  Network.post<AggregationOverview>("/multi-line-aggregation/repair", { id });
export const toggleAggregation = (id: number, enabled: boolean) =>
  Network.post<AggregationOverview>("/multi-line-aggregation/toggle", {
    id,
    enabled,
  });
export const testAggregation = (id: number) =>
  Network.post<{ diagnosis: unknown; testedAt: number }>(
    "/multi-line-aggregation/test",
    { id },
  );
export const getAggregationEvents = (id: number) =>
  Network.post<AggregationEvent[]>("/multi-line-aggregation/events", { id });
export const deleteAggregation = (id: number) =>
  Network.post<AggregationOverview>("/multi-line-aggregation/delete", { id });

export type PrivateProxyType =
  | "socks5"
  | "http"
  | "shadowsocks"
  | "vless_reality"
  | "trojan"
  | "hysteria2"
  | "tuic"
  | "wireguard";

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
  state:
    | "provisioning"
    | "active"
    | "paused"
    | "error"
    | "delete_pending"
    | "expired"
    | "quota_exhausted";
  expiresAt?: number;
  lastError?: string;
  inFlow?: number;
  outFlow?: number;
  grantedByUserId?: number;
  granted?: boolean;
  flowLimit?: number;
  flowUnlimited?: number;
  flowResetDay?: number;
  lastFlowResetAt?: number;
  speedLimitMbps?: number;
  speedLimitSupported?: boolean;
  remainingFlow?: number;
  remainingTime?: number;
  available?: boolean;
  unavailableReason?: string;
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
  cipher?: "aes-128-gcm" | "aes-256-gcm" | "chacha20-ietf-poly1305";
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
  clientPrivateKey?: string;
  serverPublicKey?: string;
  clientAddress?: string;
}

export interface PrivateProxyGrantRequest extends PrivateProxyCreateRequest {
  targetUserId: number;
  flowLimit: number;
  flowUnlimited: boolean;
  flowResetDay: number;
  expiresAt?: number;
  speedLimitMbps?: number;
}

export interface PrivateProxyGrantUpdateRequest {
  id: number;
  flowLimit: number;
  flowUnlimited: boolean;
  flowResetDay: number;
  permanent: boolean;
  expiresAt?: number;
  speedLimitMbps?: number;
}

export const getPrivateProxies = () =>
  Network.postCached<PrivateProxyItem[]>("/private-proxy/list", {}, 8000);
export const createPrivateProxy = (data: PrivateProxyCreateRequest) =>
  Network.mutate<PrivateProxyItem>("/private-proxy/create", data, [
    "/private-proxy/list",
  ]);
export const getPrivateProxyClientConfig = (id: number) =>
  Network.post<PrivateProxyClientConfig>("/private-proxy/client-config", {
    id,
  });
export const pausePrivateProxy = (id: number) =>
  Network.mutate("/private-proxy/pause", { id }, ["/private-proxy/list"]);
export const resumePrivateProxy = (id: number) =>
  Network.mutate("/private-proxy/resume", { id }, ["/private-proxy/list"]);
export const deletePrivateProxy = (id: number) =>
  Network.mutate("/private-proxy/delete", { id }, ["/private-proxy/list"]);
export const createPrivateProxyGrant = (data: PrivateProxyGrantRequest) =>
  Network.mutate<PrivateProxyItem>("/private-proxy/grant", data, [
    "/private-proxy/list",
  ]);
export const getPrivateProxyGrants = (userId?: number) =>
  Network.post<PrivateProxyItem[]>(
    "/private-proxy/grant-list",
    userId ? { userId } : {},
  );
export const updatePrivateProxyGrant = (data: PrivateProxyGrantUpdateRequest) =>
  Network.mutate<PrivateProxyItem>("/private-proxy/grant-update", data, [
    "/private-proxy/list",
  ]);
export const resetPrivateProxyGrantFlow = (id: number) =>
  Network.mutate<PrivateProxyItem>("/private-proxy/grant-reset", { id }, [
    "/private-proxy/list",
  ]);

export interface NetworkDiagnosticResult {
  mode: "ping" | "tcp" | "dns" | "trace";
  target: string;
  success: boolean;
  summary: string;
  output?: string;
  addresses?: string[];
  durationMs: number;
}

export const runNetworkDiagnostic = (data: {
  nodeId: number;
  mode: "ping" | "tcp" | "dns" | "trace";
  target: string;
  port?: number;
  recordType?: string;
  count?: number;
  timeoutMs?: number;
}) => Network.post<NetworkDiagnosticResult>("/network-tools/run", data);

export interface QualityLabNode {
  id: number;
  name: string;
  ip?: string;
  serverIp?: string;
  status: number;
  version?: string;
  networkLine?: string;
}
export interface QualityProbeTask {
  id: number;
  name: string;
  sourceNodeId: number;
  sourceNodeName: string;
  sourceNodeStatus: number;
  sourceNodeVersion?: string;
  sourceLine?: string;
  targetType: "custom" | "node";
  targetNodeId?: number;
  targetNodeName?: string;
  targetHost: string;
  port: number;
  protocol: "tcp" | "tls" | "http" | "https";
  path: string;
  serverName?: string;
  ipFamily: "auto" | "ipv4" | "ipv6";
  sampleCount: number;
  timeoutMs: number;
  intervalMinutes: number;
  retentionDays: number;
  enabled: boolean | number;
  running: boolean | number;
  nextRunAt?: number;
  lastRunAt?: number;
  lastStatus: "pending" | "running" | "success" | "partial" | "failed";
  lastError?: string;
  p50Ms?: number;
  p95Ms?: number;
  p99Ms?: number;
  jitterMs?: number;
  failureRate?: number;
  tcpAvgMs?: number;
  tlsAvgMs?: number;
  ttfbAvgMs?: number;
  latestIpFamily?: string;
  latestStartedAt?: number;
}
export interface QualityRun {
  id: number;
  status: string;
  resolvedAddress?: string;
  ipFamily: string;
  protocol: string;
  dnsMs: number;
  tcpAvgMs?: number;
  tlsAvgMs?: number;
  ttfbAvgMs?: number;
  p50Ms?: number;
  p95Ms?: number;
  p99Ms?: number;
  jitterMs: number;
  failureRate: number;
  successCount: number;
  sampleCount: number;
  httpStatus?: number;
  error?: string;
  startedAt: number;
  finishedAt: number;
}
export interface QualityAggregate {
  runs: number;
  tcpAvgMs: number;
  tlsAvgMs: number;
  ttfbAvgMs: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  jitterMs: number;
  failureRate: number;
  interruptions: number;
  lastRunAt: number;
}
export interface QualityComparison {
  label: string | number;
  runs: number;
  p95Ms?: number;
  jitterMs?: number;
  failureRate?: number;
}
export interface QualityLabOverview {
  minimumAgentVersion: string;
  nodes: QualityLabNode[];
  tasks: QualityProbeTask[];
  summary: {
    total: number;
    enabled: number;
    healthy: number;
    degraded: number;
    failed: number;
  };
  lineProfiles: QualityComparison[];
}
export interface QualityLabDetail {
  task: QualityProbeTask;
  range: string;
  summary: QualityAggregate;
  runs: QualityRun[];
  ipComparison: QualityComparison[];
  lineComparison: QualityComparison[];
  hourComparison: QualityComparison[];
}
export type QualityProbeTaskInput = Omit<
  QualityProbeTask,
  | "id"
  | "sourceNodeName"
  | "sourceNodeStatus"
  | "sourceNodeVersion"
  | "sourceLine"
  | "targetNodeName"
  | "running"
  | "nextRunAt"
  | "lastRunAt"
  | "lastStatus"
  | "lastError"
  | "p50Ms"
  | "p95Ms"
  | "p99Ms"
  | "jitterMs"
  | "failureRate"
  | "tcpAvgMs"
  | "tlsAvgMs"
  | "ttfbAvgMs"
  | "latestIpFamily"
  | "latestStartedAt"
> & { id?: number };
export interface QualityProbePreflight {
  reachable: boolean;
  message: string;
  error?: string;
  resolvedAddress?: string;
  ipFamily?: string;
  tcpMs?: number;
}
export const getQualityLabOverview = () =>
  Network.post<QualityLabOverview>("/quality-lab/overview");
export const preflightQualityProbeTask = (data: QualityProbeTaskInput) =>
  Network.post<QualityProbePreflight>("/quality-lab/preflight", data);
export const saveQualityProbeTask = (data: QualityProbeTaskInput) =>
  Network.post<QualityLabOverview>("/quality-lab/save", data);
export const runQualityProbeTask = (id: number) =>
  Network.post<{ id: number; state: string; message: string }>(
    "/quality-lab/run",
    { id },
  );
export const toggleQualityProbeTask = (id: number, enabled: boolean) =>
  Network.post<QualityLabOverview>("/quality-lab/toggle", { id, enabled });
export const deleteQualityProbeTask = (id: number) =>
  Network.post("/quality-lab/delete", { id });
export const getQualityLabDetail = (id: number, range: "24h" | "7d" | "30d") =>
  Network.post<QualityLabDetail>("/quality-lab/detail", { id, range });
export const getQualityLabReport = (id: number, range: "24h" | "7d" | "30d") =>
  Network.post<{ filename: string; content: string; generatedAt: number }>(
    "/quality-lab/report",
    { id, range },
  );

export interface BandwidthTestTask {
  id: number;
  name: string;
  sourceNodeId: number;
  sourceNodeName: string;
  sourceNodeStatus: number;
  sourceNodeVersion?: string;
  targetNodeId: number;
  targetNodeName: string;
  targetNodeStatus: number;
  targetNodeVersion?: string;
  listenPort: number;
  protocol: "tcp" | "udp";
  direction: "upload" | "download" | "bidirectional";
  streams: number;
  durationSeconds: number;
  maximumMegabytes: number;
  retentionDays: number;
  running: boolean | number;
  lastStatus: "pending" | "running" | "success" | "failed";
  lastError?: string;
  lastRunAt?: number;
  uploadMbps?: number;
  downloadMbps?: number;
  totalMbps?: number;
  latestDurationMs?: number;
  successfulStreams?: number;
  failedStreams?: number;
  latestStartedAt?: number;
  rttMs?: number;
  retransmits?: number;
  retransmissionRate?: number;
  packetsSent?: number;
  packetsReceived?: number;
  packetsLost?: number;
  packetLossPercent?: number;
  jitterMs?: number;
  outOfOrderPackets?: number;
}
export interface BandwidthTestRun {
  id: number;
  status: string;
  protocol: "tcp" | "udp";
  direction: string;
  streams: number;
  durationMs: number;
  uploadBytes: number;
  downloadBytes: number;
  uploadMbps: number;
  downloadMbps: number;
  totalMbps: number;
  cpuPercent?: number;
  memoryUsed?: number;
  memoryPercent?: number;
  successfulStreams: number;
  failedStreams: number;
  error?: string;
  startedAt: number;
  finishedAt: number;
  rttMs?: number;
  retransmits: number;
  retransmissionRate: number;
  packetsSent: number;
  packetsReceived: number;
  packetsLost: number;
  packetLossPercent: number;
  jitterMs: number;
  outOfOrderPackets: number;
}
export interface BandwidthTestOverview {
  minimumAgentVersion: string;
  nodes: QualityLabNode[];
  tasks: BandwidthTestTask[];
  summary: {
    total: number;
    running: number;
    success: number;
    failed: number;
    peakMbps: number;
  };
}
export type BandwidthTestTaskInput = Pick<
  BandwidthTestTask,
  | "name"
  | "sourceNodeId"
  | "targetNodeId"
  | "listenPort"
  | "protocol"
  | "direction"
  | "streams"
  | "durationSeconds"
  | "maximumMegabytes"
  | "retentionDays"
> & { id?: number };
export const getBandwidthTestOverview = () =>
  Network.post<BandwidthTestOverview>("/bandwidth-test/overview");
export const saveBandwidthTestTask = (data: BandwidthTestTaskInput) =>
  Network.post<BandwidthTestOverview>("/bandwidth-test/save", data);
export const runBandwidthTestTask = (id: number) =>
  Network.post<{ id: number; state: string; message: string }>(
    "/bandwidth-test/run",
    { id },
  );
export const deleteBandwidthTestTask = (id: number) =>
  Network.post<BandwidthTestOverview>("/bandwidth-test/delete", { id });
export const getBandwidthTestDetail = (id: number) =>
  Network.post<{ taskId: number; runs: BandwidthTestRun[] }>(
    "/bandwidth-test/detail",
    { id },
  );

export interface UdpQuicNode extends QualityLabNode {
  compatible?: boolean;
}
export interface UdpQuicDiagnosticTask {
  id: number;
  name: string;
  sourceNodeId: number;
  sourceNodeName: string;
  sourceNodeStatus: number;
  sourceNodeVersion?: string;
  targetType: "node" | "custom";
  targetNodeId?: number;
  targetNodeName?: string;
  targetNodeStatus?: number;
  targetNodeVersion?: string;
  targetHost?: string;
  port: number;
  mode: "udp_echo" | "quic";
  serverName?: string;
  ipFamily: "auto" | "ipv4" | "ipv6";
  sampleCount: number;
  timeoutMs: number;
  packetSize: number;
  idleTimeoutSeconds: number;
  alpn?: string;
  verifyCertificate: boolean | number;
  retentionDays: number;
  running: boolean | number;
  lastStatus: "pending" | "running" | "success" | "partial" | "failed";
  lastError?: string;
  lastRunAt?: number;
  resolvedAddress?: string;
  successCount?: number;
  latestSampleCount?: number;
  failureRate?: number;
  packetLossPercent?: number;
  rttAvgMs?: number;
  jitterMs?: number;
  natIdleAlive?: boolean | number;
  quicHandshakeAvgMs?: number;
  diagnosis?: string;
  latestStartedAt?: number;
}
export interface UdpQuicDiagnosticRun {
  id: number;
  status: "success" | "partial" | "failed";
  targetHost: string;
  resolvedAddress?: string;
  ipFamily: string;
  port: number;
  mode: "udp_echo" | "quic";
  packetSize: number;
  sampleCount: number;
  successCount: number;
  failureRate: number;
  packetLossPercent: number;
  rttMinMs?: number;
  rttAvgMs?: number;
  rttMaxMs?: number;
  jitterMs?: number;
  natIdleSeconds?: number;
  natIdleAlive?: boolean | number;
  quicHandshakeAvgMs?: number;
  alpn?: string;
  diagnosis?: string;
  error?: string;
  samplesJson?: string;
  startedAt: number;
  finishedAt: number;
}
export interface UdpQuicDiagnosticOverview {
  minimumAgentVersion: string;
  nodes: UdpQuicNode[];
  tasks: UdpQuicDiagnosticTask[];
  summary: {
    total: number;
    running: number;
    success: number;
    degraded: number;
    failed: number;
  };
}
export type UdpQuicDiagnosticInput = Pick<
  UdpQuicDiagnosticTask,
  | "name"
  | "sourceNodeId"
  | "targetType"
  | "targetNodeId"
  | "targetHost"
  | "port"
  | "mode"
  | "serverName"
  | "ipFamily"
  | "sampleCount"
  | "timeoutMs"
  | "packetSize"
  | "idleTimeoutSeconds"
  | "alpn"
  | "verifyCertificate"
  | "retentionDays"
> & { id?: number };
export const getUdpQuicDiagnosticOverview = () =>
  Network.post<UdpQuicDiagnosticOverview>("/udp-quic-diagnostic/overview");
export const saveUdpQuicDiagnosticTask = (data: UdpQuicDiagnosticInput) =>
  Network.post<UdpQuicDiagnosticOverview>("/udp-quic-diagnostic/save", data);
export const runUdpQuicDiagnosticTask = (id: number) =>
  Network.post<{ id: number; state: string; message: string }>(
    "/udp-quic-diagnostic/run",
    { id },
  );
export const deleteUdpQuicDiagnosticTask = (id: number) =>
  Network.post<UdpQuicDiagnosticOverview>("/udp-quic-diagnostic/delete", {
    id,
  });
export const getUdpQuicDiagnosticDetail = (id: number) =>
  Network.post<{ taskId: number; runs: UdpQuicDiagnosticRun[] }>(
    "/udp-quic-diagnostic/detail",
    { id },
  );

export interface VirtualLanConnector {
  id: number;
  name: string;
  platform: string;
  version?: string;
  status: number;
  remoteIp?: string;
  lastSeen?: number;
}
export interface VirtualLanMember {
  id: number;
  networkId: number;
  targetType: "node" | "connector";
  targetId: number;
  memberName: string;
  role: "hub" | "member";
  virtualIp: string;
  state: string;
  receiveBytes: number;
  transmitBytes: number;
  latestHandshake?: number;
  lastError?: string;
  updatedTime: number;
}
export interface VirtualLanNetwork {
  id: number;
  name: string;
  cidr: string;
  hubNodeId: number;
  hubNodeName: string;
  hubServerIp?: string;
  hubIp?: string;
  listenPort: number;
  state: string;
  lastError?: string;
  memberCount: number;
  onlineCount: number;
  createdTime: number;
  updatedTime: number;
  members: VirtualLanMember[];
}
export interface VirtualLanOverview {
  minimumAgentVersion: string;
  nodes: QualityLabNode[];
  connectors: VirtualLanConnector[];
  networks: VirtualLanNetwork[];
}
export interface VirtualLanCreateInput {
  name: string;
  cidr: string;
  hubNodeId: number;
  listenPort: number;
  members: Array<{ targetType: "node" | "connector"; targetId: number }>;
}
export const getVirtualLanOverview = () =>
  Network.post<VirtualLanOverview>("/virtual-lan/overview");
export const createVirtualLan = (data: VirtualLanCreateInput) =>
  Network.post<VirtualLanOverview>("/virtual-lan/create", data);
export const deployVirtualLan = (id: number) =>
  Network.post<VirtualLanOverview>("/virtual-lan/deploy", { id });
export const pauseVirtualLan = (id: number) =>
  Network.post<VirtualLanOverview>("/virtual-lan/pause", { id });
export const resumeVirtualLan = (id: number) =>
  Network.post<VirtualLanOverview>("/virtual-lan/resume", { id });
export const refreshVirtualLan = (id: number) =>
  Network.post<VirtualLanOverview>("/virtual-lan/refresh", { id });
export const deleteVirtualLan = (id: number) =>
  Network.post<VirtualLanOverview>("/virtual-lan/delete", { id });

export interface IpQualityServiceResult {
  name: string;
  state: "available" | "restricted" | "unavailable" | "unknown";
  httpStatus?: number;
  latencyMs: number;
  detail?: string;
}
export interface IpQualityPortResult {
  name: string;
  host: string;
  port: number;
  reachable: boolean;
  latencyMs: number;
  error?: string;
}
export interface IpQualityBlacklistResult {
  provider: string;
  listed: boolean;
  status: string;
  answer?: string;
  detail?: string;
}
export interface IpQualityRiskSource {
  name: string;
  configured: boolean;
  status: string;
  score?: number;
  proxy?: boolean;
  vpn?: boolean;
  tor?: boolean;
  recentAbuse?: boolean;
  bot?: boolean;
  totalReports?: number;
  lastReportedAt?: string;
  usageType?: string;
  error?: string;
}
export interface IpQualityScan {
  scanId?: number;
  scanStatus?: string;
  publicIpv4?: string;
  publicIpv6?: string;
  countryCode?: string;
  country?: string;
  region?: string;
  city?: string;
  asn?: string;
  organization?: string;
  networkType?: string;
  riskScore?: number;
  riskLevel?: string;
  confidence?: string;
  riskSources: Record<string, IpQualityRiskSource>;
  blacklist: IpQualityBlacklistResult[];
  unlockResults: IpQualityServiceResult[];
  dns: {
    configuredResolvers?: string[];
    observedResolvers?: string[];
    error?: string;
  };
  ports: IpQualityPortResult[];
  scanError?: string;
  startedAt?: number;
  finishedAt?: number;
}
export interface IpQualityNode extends QualityLabNode, IpQualityScan {}
export interface IpQualityOverview {
  minimumAgentVersion: string;
  nodes: IpQualityNode[];
  providers: { ipqsConfigured: boolean; abuseipdbConfigured: boolean };
  summary: { total: number; running: number; tested: number; highRisk: number };
}
export const getIpQualityOverview = () =>
  Network.post<IpQualityOverview>("/ip-quality/overview");
export const runIpQualityScan = (nodeId: number) =>
  Network.post<IpQualityOverview>("/ip-quality/run", { nodeId });
export const getIpQualityHistory = (nodeId: number) =>
  Network.post<{ scans: IpQualityScan[] }>("/ip-quality/history", { nodeId });
export const saveIpQualityProviders = (data: {
  ipqsApiKey: string;
  abuseipdbApiKey: string;
  clearIpqs: boolean;
  clearAbuseipdb: boolean;
}) => Network.post<IpQualityOverview>("/ip-quality/providers/save", data);

export interface PrivateNetworkMember {
  id: number;
  groupId: number;
  nodeId: number;
  nodeName: string;
  nodeStatus: number;
  nodeVersion?: string;
  privateAddress: string;
  interfaceName?: string;
  mtu: number;
  updatedTime: number;
}
export interface PrivateNetworkLink {
  id: number;
  sourceNodeId: number;
  sourceNodeName: string;
  targetNodeId: number;
  targetNodeName: string;
  sourceAddress?: string;
  targetAddress: string;
  routeInfo?: string;
  interfaceName?: string;
  state: string;
  latencyMs?: number;
  packetLoss?: number;
  lastError?: string;
  verifiedAt?: number;
}
export interface PrivateNetworkGroup {
  id: number;
  name: string;
  networkType: "vpc" | "cloud_backbone" | "dedicated";
  cidr?: string;
  state: string;
  lastError?: string;
  createdTime: number;
  updatedTime: number;
  members: PrivateNetworkMember[];
  links: PrivateNetworkLink[];
}
export interface PrivateNetworkOverview {
  minimumAgentVersion: string;
  nodes: QualityLabNode[];
  groups: PrivateNetworkGroup[];
}
export interface PrivateNetworkSaveInput {
  id?: number;
  name: string;
  networkType: string;
  cidr?: string;
  members: Array<{
    nodeId: number;
    privateAddress: string;
    interfaceName?: string;
    mtu: number;
  }>;
}
export const getPrivateNetworkOverview = () =>
  Network.post<PrivateNetworkOverview>("/private-network/overview");
export const savePrivateNetwork = (data: PrivateNetworkSaveInput) =>
  Network.post<PrivateNetworkOverview>("/private-network/save", data);
export const verifyPrivateNetwork = (id: number) =>
  Network.post<PrivateNetworkOverview>("/private-network/verify", { id });
export const deletePrivateNetwork = (id: number) =>
  Network.post<PrivateNetworkOverview>("/private-network/delete", { id });

export interface NetworkRouteApplication {
  id: number;
  name: string;
  tunnelId: number;
  tunnelName: string;
  entryNodeId: number;
  entryNodeName: string;
  exitNodeId: number;
  exitNodeName: string;
  proxyType: "socks5" | "http" | "vless_reality" | "vless_xhttp_tls";
  bindIp?: string;
  listenPort: number;
  username: string;
  password: string;
  hopPorts: string;
  state: string;
  lastError?: string;
  lastTestAt?: number;
  managedTunnel: number;
  lastTestLatencyMs?: number;
  createdTime: number;
  updatedTime: number;
  entryHost: string;
  clientUri: string;
  nodePath: Array<{ nodeId: number; nodeName: string }>;
  hopDetails: Array<{
    fromNodeId: number;
    fromNodeName: string;
    toNodeId: number;
    toNodeName: string;
    addressMode: "public" | "private" | "virtual" | "custom";
    addressModeName: string;
    resourceGroupId?: number;
    resourceGroupName?: string;
    targetAddress: string;
    fallbackMode: "fail_closed" | "public";
    fallbackAddress?: string;
    verificationState: string;
    verifiedAt?: number;
    candidates: string[];
  }>;
}
export interface NetworkRouteApplicationOverview {
  minimumAgentVersion: string;
  minimumRealityAgentVersion: string;
  minimumXhttpAgentVersion?: string;
  applications: NetworkRouteApplication[];
}
export interface NetworkRouteApplicationCreateInput {
  name: string;
  tunnelId?: number;
  nodePath?: number[];
  hopConfigs?: Array<{
    fromNodeId: number;
    toNodeId: number;
    addressMode: string;
    resourceGroupId?: number;
    customAddress?: string;
    fallbackMode: string;
  }>;
  tunnelProtocol?: "tls" | "quic";
  proxyType: "socks5" | "http" | "vless_reality" | "vless_xhttp_tls";
  bindIp?: string;
  listenPort: number;
  username?: string;
  password?: string;
  realityServerName?: string;
  xhttpPath?: string;
  xhttpMode?: "auto" | "packet-up" | "stream-up";
  xhttpPaddingBytes?: string;
  xhttpOriginDomain?: string;
  xhttpUploadDomain?: string;
  xhttpDownloadDomain?: string;
  autoProvisionCloudFront?: boolean;
  awsAccessAccountId?: number;
  dnsZoneId?: number;
}
export const getNetworkRouteApplications = () =>
  Network.post<NetworkRouteApplicationOverview>(
    "/network-route-application/overview",
  );
export const createNetworkRouteApplication = (
  data: NetworkRouteApplicationCreateInput,
) =>
  Network.post<NetworkRouteApplicationOverview>(
    "/network-route-application/create",
    data,
  );
export const deployNetworkRouteApplication = (id: number) =>
  Network.post<NetworkRouteApplicationOverview>(
    "/network-route-application/deploy",
    { id },
  );
export const testNetworkRouteApplication = (id: number) =>
  Network.post("/network-route-application/test", { id });
export const pauseNetworkRouteApplication = (id: number) =>
  Network.post<NetworkRouteApplicationOverview>(
    "/network-route-application/pause",
    { id },
  );
export const resumeNetworkRouteApplication = (id: number) =>
  Network.post<NetworkRouteApplicationOverview>(
    "/network-route-application/resume",
    { id },
  );
export const deleteNetworkRouteApplication = (id: number) =>
  Network.post<NetworkRouteApplicationOverview>(
    "/network-route-application/delete",
    { id },
  );

// 转发服务控制操作 - 通过Java后端接口
export const pauseForwardService = (forwardId: number) =>
  Network.mutate("/forward/pause", { id: forwardId }, ["/forward/list"]);
export const resumeForwardService = (forwardId: number) =>
  Network.mutate("/forward/resume", { id: forwardId }, ["/forward/list"]);

// 转发诊断操作
export const diagnoseForward = (forwardId: number) =>
  Network.post("/forward/diagnose", { forwardId });
export const getForwardRouteEvents = (forwardId: number) =>
  Network.post("/forward/route-events", { forwardId });

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
  weight?: number;
  enabled?: boolean | number;
  entryNodeId: number;
  entryHost: string;
  entryAddress: string;
  entryPort: number;
  forwardName: string;
  nodeName: string;
  status: "unknown" | "healthy" | "unhealthy";
  failCount: number;
  successCount: number;
  latencyMs?: number;
  qualityLatencyMs?: number;
  qualityP95Ms?: number;
  qualityJitterMs?: number;
  qualityLossPercent?: number;
  qualityBaselineMs?: number;
  qualityPreheated?: boolean | number;
  qualityState?: "unknown" | "warming" | "healthy" | "degraded";
  qualityBadCount?: number;
  qualityGoodCount?: number;
  qualityFlapCount?: number;
  qualityFlapWindowStartedAt?: number;
  qualitySuppressedUntil?: number;
  qualitySuppressedReason?: string;
  qualityPenaltyLevel?: number;
  qualityPenaltyEpisodeCount?: number;
  qualityPenaltyWindowStartedAt?: number;
  qualityPenaltyLastAt?: number;
  qualityRecoveryObserveUntil?: number;
  switchRejectedUntil?: number;
  switchRejectedReason?: string;
  switchRejectCount?: number;
  qualityLastError?: string;
  qualityCheckedAt?: number;
  faultEpisodeCount?: number;
  connectFaultCount?: number;
  latencyFaultCount?: number;
  lossFaultCount?: number;
  p95FaultCount?: number;
  jitterFaultCount?: number;
  flapFaultCount?: number;
  switchTriggerCount?: number;
  lastFaultType?: string;
  lastFaultReason?: string;
  lastFaultAt?: number;
  lastError?: string;
  lastCheckedAt?: number;
  lastHealthyAt?: number;
  lastFailureAt?: number;
  telemetryReady?: boolean | number;
  telemetryLive?: boolean;
  totalConnections?: number;
  currentConnections?: number;
  lastTelemetryAt?: number;
  activityInFlow?: number;
  activityOutFlow?: number;
  lastInFlowAt?: number;
  lastOutFlowAt?: number;
  lastActivityAt?: number;
}

export interface CrossEntrySchedule {
  id?: number;
  days: number[];
  startTime: string;
  endTime: string;
  preferredForwardId: number;
  preferredForwardName?: string;
  preferredNodeName?: string;
  enabled?: boolean | number;
}

export interface CrossEntryGroup {
  id: number;
  name: string;
  domain: string;
  creationMode?: "existing_forward" | "managed_forward";
  managedTargetAddress?: string;
  managedPublicPort?: number;
  managedPortMode?: "auto" | "custom";
  managedProtocolMode?: "tcp" | "tcp_udp";
  dnsZoneId?: number;
  zoneName?: string;
  zoneId: string;
  recordId: string;
  recordType: "A" | "AAAA";
  ttl: number;
  expiresAt?: number;
  probeIntervalMs: number;
  connectTimeoutMs: number;
  failureThreshold: number;
  recoveryThreshold: number;
  cooldownSeconds: number;
  autoFailback: boolean | number;
  routingMode?: "failover" | "active_active";
  qualityEnabled?: boolean | number;
  qualityProbeSourceType?: "panel" | "node" | "connector";
  qualityProbeSourceId?: number;
  qualityProbeCount?: number;
  qualityDegradeThresholdMs?: number;
  qualityRecoverThresholdMs?: number;
  qualityDegradeFactor?: number;
  qualityRecoverFactor?: number;
  qualityDegradeSamples?: number;
  qualityRecoverSamples?: number;
  qualityLossThresholdPercent?: number;
  qualityP95ThresholdMs?: number;
  qualityJitterThresholdMs?: number;
  qualityFixedTargetEnabled?: boolean | number;
  qualityFixedTargetMs?: number;
  qualityFixedTargetStrict?: boolean | number;
  qualityFlapGuardEnabled?: boolean | number;
  qualityFlapWindowSeconds?: number;
  qualityFlapThreshold?: number;
  qualityFlapSuppressSeconds?: number;
  qualityPenaltyEnabled?: boolean | number;
  qualityPenaltyResetSeconds?: number;
  qualityPenaltyObserveSeconds?: number;
  smartSelectionEnabled?: boolean | number;
  tcpLatencySelectionEnabled?: boolean | number;
  tcpLatencySwitchThresholdMs?: number;
  tcpPrimaryPreferenceToleranceMs?: number;
  degradedFallbackEnabled?: boolean | number;
  sameFaultAvoidanceEnabled?: boolean | number;
  topologyAvoidanceEnabled?: boolean | number;
  minResidencySeconds?: number;
  failbackGainMs?: number;
  failbackGainPercent?: number;
  preheatEnabled?: boolean | number;
  preheatBackupCount?: number;
  preheatStrictIsolation?: boolean | number;
  postSwitchVerifyEnabled?: boolean | number;
  postSwitchRejectSuppressSeconds?: number;
  dnsVerifyEnabled?: boolean | number;
  manualControlMode?: "auto" | "pause" | "lock";
  lockedMemberId?: number;
  manualLockUntil?: number;
  qualityProbeStatus?: "disabled" | "pending" | "ok" | "warning" | "failed";
  qualityProbeError?: string;
  qualityProbeAt?: number;
  enabled: boolean | number;
  state:
    | "unknown"
    | "healthy"
    | "degraded"
    | "offline"
    | "switching"
    | "error"
    | "expired";
  activeMemberId?: number;
  lastError?: string;
  lastCheckedAt?: number;
  lastSwitchAt?: number;
  apiTokenConfigured: boolean | number;
  members: CrossEntryMember[];
  schedules?: CrossEntrySchedule[];
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
  status: "success" | "failed";
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

export interface CrossEntryProbeSource {
  id: number;
  name: string;
  address?: string;
  status?: number;
  version?: string;
  platform?: string;
  remoteIp?: string;
  lastSeen?: number;
}

export interface CrossEntryProbeSourceOverview {
  nodes: CrossEntryProbeSource[];
  connectors: CrossEntryProbeSource[];
  minimumRemoteVersion: string;
}

export const getCrossEntryGroups = () =>
  Network.postCached<{ groups: CrossEntryGroup[]; summary: CrossEntrySummary }>(
    "/cross-entry-failover/list",
    {},
    5000,
  );
export const getCrossEntryForwardOptions = () =>
  Network.postCached<CrossEntryForwardOption[]>(
    "/cross-entry-failover/eligible-forwards",
    {},
    15000,
  );
export interface CrossEntryNodeOption {
  id: number;
  name: string;
  ip?: string;
  serverIp?: string;
  status?: number;
  version?: string;
}
export const getCrossEntryNodeOptions = () =>
  Network.postCached<CrossEntryNodeOption[]>("/node/list", {}, 8000);
export const getCrossEntryProbeSources = () =>
  Network.postCached<CrossEntryProbeSourceOverview>(
    "/cross-entry-failover/probe-sources",
    {},
    15000,
  );
export const saveCrossEntryGroup = (data: any) =>
  Network.mutate<{ id: number }>("/cross-entry-failover/save", data, [
    "/cross-entry-failover/list",
    "/cross-entry-failover/eligible-forwards",
  ]);
export const deleteCrossEntryGroup = (id: number) =>
  Network.mutate("/cross-entry-failover/delete", { id }, [
    "/cross-entry-failover/list",
    "/cross-entry-failover/eligible-forwards",
  ]);
export const checkCrossEntryGroup = (id: number) =>
  Network.mutate<{ groups: CrossEntryGroup[]; summary: CrossEntrySummary }>(
    "/cross-entry-failover/check",
    { id },
    ["/cross-entry-failover/list"],
  );
export const getCrossEntryEvents = (id: number) =>
  Network.post<CrossEntryEvent[]>("/cross-entry-failover/events", { id });

export interface SourceIpEntryRoute {
  id?: number;
  carrier: "default" | "telecom" | "unicom" | "mobile" | "custom";
  ruleType?:
    | "default"
    | "carrier"
    | "cidr"
    | "asn"
    | "region"
    | "vip"
    | "customer"
    | "gray"
    | "risk";
  ruleTypeLabel?: string;
  ruleName?: string;
  priority?: number;
  backendForwardId: number;
  backendForwardName?: string;
  backendNodeId?: number;
  backendNodeName?: string;
  backendHost?: string;
  backendPort?: number;
  protocolMode?: string;
  cidrs?: string;
  cidrCount?: number;
  region?: string;
  asn?: string;
  tags?: string;
  qualityPolicy?: "static" | "quality_aware" | "prefer_primary" | "quarantine";
  qualityPolicyLabel?: string;
  notes?: string;
  enabled: boolean | number;
  chainName?: string;
}

export interface SourceIpEntryGroup {
  id: number;
  name: string;
  ingressNodeId: number;
  ingressNodeName?: string;
  agentVersion?: string;
  listenHost?: string;
  listenPort: number;
  defaultRouteId?: number;
  enabled: boolean | number;
  state: "provisioning" | "active" | "disabled" | "error" | "deleted";
  lastError?: string;
  lastSyncedAt?: number;
  createdTime?: number;
  routes: SourceIpEntryRoute[];
  serviceName?: string;
}

export interface SourceIpEntryNode {
  id: number;
  name: string;
  serverIp?: string;
  ip?: string;
  version?: string;
  status: number;
  online: boolean;
  compatible: boolean;
  available: boolean;
}

export interface SourceIpBackendForward {
  id: number;
  name: string;
  inPort: number;
  protocolMode?: string;
  inNodeId: number;
  entryHost?: string;
  tunnelName?: string;
  nodeName?: string;
  nodeStatus?: number;
}

export interface SourceIpCarrierDatabase {
  carrier: "telecom" | "unicom" | "mobile";
  label: string;
  state: "pending" | "ready" | "error";
  ipv4Count: number;
  ipv6Count: number;
  cidrCount: number;
  sourceUrls?: string;
  updatedTime?: number;
  lastError?: string;
}

export interface SourceIpAsnDatabase {
  asn: string;
  label: string;
  state: "pending" | "ready" | "error";
  ipv4Count: number;
  ipv6Count: number;
  prefixCount: number;
  sourceUrl?: string;
  updatedTime?: number;
  lastError?: string;
}

export interface SourceIpEntryOverview {
  groups: SourceIpEntryGroup[];
  ingressNodes: SourceIpEntryNode[];
  backendForwards: SourceIpBackendForward[];
  carriers: SourceIpCarrierDatabase[];
  asns: SourceIpAsnDatabase[];
  ruleTypes?: { key: string; label: string; description: string }[];
  qualityPolicies?: { key: string; label: string; description: string }[];
  capabilities?: { key: string; name: string; detail: string }[];
  minimumAgentVersion: string;
  summary: { total: number; enabled: number; healthy: number; errors: number };
}

export interface SourceIpDebugRoute {
  id: number;
  carrier: string;
  carrierLabel: string;
  ruleType: string;
  ruleTypeLabel: string;
  ruleName: string;
  priority: number;
  backendForwardId: number;
  backendForwardName: string;
  backendNodeName: string;
  backendHost: string;
  backendPort: number;
  region?: string;
  asn?: string;
  qualityPolicy?: string;
  qualityPolicyLabel?: string;
  cidrCount: number;
  matchedCidr?: string;
  prefixLength?: number;
}

export interface SourceIpDebugGroupResult {
  groupId: number;
  groupName: string;
  enabled: boolean;
  state: string;
  listener: string;
  matched: boolean;
  selectedRoute?: SourceIpDebugRoute;
  defaultRoute?: SourceIpDebugRoute;
  candidates: SourceIpDebugRoute[];
  reason: string;
  warning?: string;
}

export interface SourceIpDebugResult {
  sourceIp: string;
  ipVersion: string;
  inferredCarrier: {
    carrier: string;
    label: string;
    matchedCidr?: string;
    prefixLength?: number;
  };
  groups: SourceIpDebugGroupResult[];
}

export const getSourceIpEntryOverview = () =>
  Network.post<SourceIpEntryOverview>("/source-ip-entry/overview");
export const saveSourceIpEntry = (data: any) =>
  Network.post<{ id: number; state: string }>("/source-ip-entry/save", data);
export const checkSourceIpEntry = (id: number) =>
  Network.post("/source-ip-entry/check", { id });
export const deleteSourceIpEntry = (id: number) =>
  Network.post("/source-ip-entry/delete", { id });
export const refreshSourceIpCarriers = () =>
  Network.post("/source-ip-entry/carriers/refresh");
export const refreshSourceIpAsns = () =>
  Network.post("/source-ip-entry/asn/refresh");
export const debugSourceIpEntry = (data: {
  sourceIp: string;
  groupId?: number;
}) => Network.post<SourceIpDebugResult>("/source-ip-entry/debug", data);

export interface SmartEntryRoute {
  id: number;
  carrier: "default" | "telecom" | "unicom" | "mobile";
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
  dnsDirty?: boolean | number;
  appliedTtl?: number;
  dnsState?: "pending" | "healthy" | "error";
  dnsError?: string;
  dnsVerifiedAt?: number;
  status: "unknown" | "healthy" | "unhealthy";
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
  telemetryLive?: boolean;
  recentlyActive?: boolean;
  activityState?:
    "waiting" | "stale" | "connected" | "active_without_tcp_current" | "idle";
  activityHint?: string;
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
  provider: "dnspod" | "aliyun";
  providerName: string;
  zoneName: string;
  domain: string;
  recordType: "A" | "AAAA";
  ttl: number;
  publicPort: number;
  probeIntervalMs: number;
  connectTimeoutMs: number;
  failureThreshold: number;
  recoveryThreshold: number;
  enabled: boolean | number;
  state: "unknown" | "healthy" | "degraded" | "offline" | "error";
  lastError?: string;
  lastCheckedAt?: number;
  routes: SmartEntryRoute[];
  activities: SmartEntryActivity[];
}

export interface SmartEntryProviderOption {
  id: number;
  name: string;
  provider: "dnspod" | "aliyun";
}
export interface SmartEntryForwardOption {
  id: number;
  name: string;
  inPort: number;
  protocolMode: string;
  inNodeId: number;
  nodeName: string;
  entryHost: string;
  tunnelName: string;
}
export interface SmartEntryEvent {
  id: number;
  carrier?: string;
  eventType: string;
  status: string;
  detail: string;
  createdTime: number;
}
export interface SmartEntryDnsRecordState {
  carrier: string;
  recordId: string;
  value: string;
  ttl: number;
  enabled: boolean;
  providerLine: string;
}
export interface SmartEntryDnsProbe {
  carrier: string;
  answers: string[];
  ttl?: number;
  successful: boolean;
  error?: string;
}
export interface SmartEntryDnsDiagnosisLine {
  carrier: string;
  inherited: boolean;
  expectedAddress: string;
  providerRecord?: SmartEntryDnsRecordState;
  providerRecords: SmartEntryDnsRecordState[];
  providerMatch: boolean;
  publicProbe: SmartEntryDnsProbe;
  publicMatch: boolean;
}
export interface SmartEntryDnsDiagnosis {
  groupId: number;
  domain: string;
  recordType: "A" | "AAAA";
  ttl: number;
  checkedAt: number;
  lines: SmartEntryDnsDiagnosisLine[];
  sibling: {
    recordType: "A" | "AAAA";
    managed: boolean;
    providerRecords: SmartEntryDnsRecordState[];
    publicProbes: SmartEntryDnsProbe[];
    visible: boolean;
    conflict: boolean;
  };
  summary: {
    providerMatches: number;
    publicMatches: number;
    totalLines: number;
    queryFailures: number;
    siblingConflict: boolean;
    healthy: boolean;
  };
}

export const getSmartEntryOverview = () =>
  Network.post<{
    groups: SmartEntryGroup[];
    summary: {
      total: number;
      enabled: number;
      healthy: number;
      degraded: number;
      lineRecords: number;
    };
  }>("/smart-entry/overview");
export const getSmartEntryOptions = () =>
  Network.post<{
    providers: SmartEntryProviderOption[];
    forwards: SmartEntryForwardOption[];
  }>("/smart-entry/options");
export const getSmartEntryDomains = (providerRefId: number) =>
  Network.post<{
    provider: "dnspod" | "aliyun";
    domains: string[];
  }>("/smart-entry/domains", { providerRefId });
export const saveSmartEntry = (data: any) =>
  Network.post<{ id: number }>("/smart-entry/save", data);
export const checkSmartEntry = (id: number) =>
  Network.post("/smart-entry/check", { id });
export const diagnoseSmartEntryDns = (id: number) =>
  Network.post<SmartEntryDnsDiagnosis>("/smart-entry/diagnose-dns", { id });
export const getSmartEntryEvents = (id: number) =>
  Network.post<SmartEntryEvent[]>("/smart-entry/events", { id });
export const deleteSmartEntry = (id: number) =>
  Network.post("/smart-entry/delete", { id });

export interface DnsProviderAccount {
  id: number;
  name: string;
  provider: "cloudflare";
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
  status: "active" | "inactive";
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
  recordType: "A" | "AAAA";
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

export const getDnsProviderData = () =>
  Network.post<{
    accounts: DnsProviderAccount[];
    zones: DnsZone[];
    records: DnsManagedRecord[];
    summary: DnsProviderSummary;
  }>("/dns-provider/list");
export const getDnsZoneOptions = () =>
  Network.postCached<DnsZoneOption[]>("/dns-provider/zones", {}, 30000);
export const saveDnsProviderAccount = (data: {
  id?: number;
  name: string;
  apiToken?: string;
  enabled: boolean;
}) =>
  Network.mutate<{ id: number; zoneCount: number }>(
    "/dns-provider/account/save",
    data,
    ["/dns-provider/zones", "/dynamic-dns/overview"],
  );
export const syncDnsProviderAccount = (id: number) =>
  Network.mutate<{ zoneCount: number }>("/dns-provider/account/sync", { id }, [
    "/dns-provider/zones",
    "/dynamic-dns/overview",
  ]);
export const deleteDnsProviderAccount = (id: number) =>
  Network.mutate("/dns-provider/account/delete", { id }, [
    "/dns-provider/zones",
    "/dynamic-dns/overview",
  ]);

export interface AwsAccessAccount {
  id: number;
  name: string;
  accessKeyId: string;
  defaultRegion?: string;
  enabled: boolean | number;
  awsAccountId?: string;
  callerArn?: string;
  lastTestAt?: number;
  lastError?: string;
  createdTime: number;
  updatedTime: number;
}

export interface AwsAccessSummary {
  accounts: number;
  enabled: number;
  errors: number;
}

export interface AwsAccessOverview {
  accounts: AwsAccessAccount[];
  summary: AwsAccessSummary;
}

export const getAwsAccessAccounts = () =>
  Network.post<AwsAccessOverview>("/aws-access/list");
export const saveAwsAccessAccount = (data: {
  id?: number;
  name: string;
  accessKeyId: string;
  secretAccessKey?: string;
  defaultRegion?: string;
  enabled: boolean;
}) => Network.mutate("/aws-access/save", data, ["/aws-access/list"]);
export const syncAwsAccessAccount = (id: number) =>
  Network.mutate("/aws-access/sync", { id }, ["/aws-access/list"]);
export const deleteAwsAccessAccount = (id: number) =>
  Network.mutate("/aws-access/delete", { id }, ["/aws-access/list"]);

export interface TopologyResourceNode {
  [key: string]: unknown;
  id: string;
  type:
    | "user"
    | "domain"
    | "forward"
    | "tunnel"
    | "node"
    | "mapping"
    | "connector"
    | "service";
  label: string;
  subtitle: string;
  status: "healthy" | "degraded" | "offline" | "failed" | "paused";
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

export const getTopologyGraph = () =>
  Network.post<TopologyGraph>("/topology/graph");

// 转发排序操作
export const updateForwardOrder = (data: {
  forwards: Array<{ id: number; inx: number }>;
}) => Network.mutate("/forward/update-order", data, ["/forward/list"]);

// 用户级卡片布局
export const getLayoutOrder = (scope: string) =>
  Network.post<string[]>("/layout/order", { scope });
export const saveLayoutOrder = (scope: string, order: string[]) =>
  Network.post<string[]>("/layout/order/save", { scope, order });

export type MonitoringRange = "24h" | "7d" | "30d";
export type MonitoringResourceType =
  "node" | "tunnel" | "forward" | "certificate" | "dynamic_dns";
export type MonitoringStatus =
  "healthy" | "degraded" | "offline" | "paused" | "unknown";

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
  severity: "critical" | "warning";
  status: "open" | "resolved";
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
  status?: "all" | "open" | "resolved";
  resourceType?: "all" | MonitoringResourceType;
  severity?: "all" | "critical" | "warning";
  keyword?: string;
  page?: number;
  size?: number;
}

export const getMonitoringOverview = (range: MonitoringRange = "24h") =>
  Network.postCached<MonitoringOverview>(
    "/monitoring/overview",
    { range },
    3000,
  );
export const getMonitoringAlerts = (query: MonitoringAlertQuery = {}) =>
  Network.postCached<MonitoringAlertPage>("/monitoring/alerts", query, 3000);
export const markMonitoringAlertsRead = (ids: number[]) =>
  Network.mutate<number>("/monitoring/alerts/read", { ids }, [
    "/monitoring/alerts",
  ]);
export const markAllMonitoringAlertsRead = () =>
  Network.mutate<number>("/monitoring/alerts/read-all", {}, [
    "/monitoring/alerts",
  ]);
export const getMonitoringUnreadCount = () =>
  Network.post<number>("/monitoring/alerts/unread-count");

export interface ApiTimingRoute {
  route: string;
  requestCount: number;
  errorCount: number;
  slowCount: number;
  avgMs: number;
  p50Ms: number;
  p95Ms: number;
  maxMs: number;
  lastMs: number;
  lastStatus: number;
  lastAt: number;
  lastRequestId?: string;
}

export interface ApiTimingOverview {
  capturedAt: number;
  windowMs: number;
  thresholdMs: number;
  summary: {
    routeCount: number;
    totalRequests: number;
    errorCount: number;
    slowCount: number;
  };
  routes: ApiTimingRoute[];
}

export const getApiTimingOverview = () =>
  Network.post<ApiTimingOverview>("/monitoring/api-timing");

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
  Network.post<TelegramNotificationSettings>(
    "/monitoring/notifications/settings",
  );
export const saveTelegramNotificationSettings = (
  settings: TelegramNotificationSettings,
) =>
  Network.post<TelegramNotificationSettings>(
    "/monitoring/notifications/settings/save",
    settings,
  );
export const testTelegramNotification = () =>
  Network.post<string>("/monitoring/notifications/test");

// 限速规则CRUD操作 - 全部使用POST请求
export const createSpeedLimit = (data: any) =>
  Network.post("/speed-limit/create", data);
export const getSpeedLimitList = () => Network.post("/speed-limit/list");
export const updateSpeedLimit = (data: any) =>
  Network.post("/speed-limit/update", data);
export const deleteSpeedLimit = (id: number) =>
  Network.post("/speed-limit/delete", { id });

// 修改密码接口
export const updatePassword = (data: any) =>
  Network.post("/user/updatePassword", data);

// 重置流量接口
export const resetUserFlow = (data: { id: number; type: number }) =>
  Network.post("/user/reset", data);

// 网站配置相关接口
export const getConfigs = () => Network.post("/config/list");
export const getConfigByName = (name: string) =>
  Network.post("/config/get", { name });
export const updateConfigs = (configMap: Record<string, string>) =>
  Network.post("/config/update", configMap);
export const updateConfig = (name: string, value: string) =>
  Network.post("/config/update-single", { name, value });

export type SystemUpdateState =
  "idle" | "queued" | "running" | "success" | "failed" | "unknown";

export interface SystemUpdateStatus {
  supported: boolean;
  state: SystemUpdateState;
  message: string;
  startedAt: number;
  finishedAt: number;
  logs: string[];
}

export const getSystemUpdateStatus = () =>
  Network.post<SystemUpdateStatus>("/system-update/status");
export const triggerSystemUpdate = (version?: string) =>
  Network.post<SystemUpdateStatus>(
    "/system-update/trigger",
    version ? { version } : {},
  );

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
  discoveryStatus?: "disabled" | "idle" | "scanning" | "complete" | "failed";
  discoveryLastScanAt?: number;
  discoveryLastCidr?: string;
  discoveryLastError?: string;
  discoveredServiceCount?: number;
}

export type ConnectorPlatform = "linux" | "windows" | "macos";

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
  accessType?: "admin" | "shared";
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

export interface ServiceTelemetrySample {
  value: string;
  sourceKind?: "real" | "forwarded" | "previous_hop" | string;
  count: number;
  lastSeen: number;
}

export interface ServiceTelemetry {
  resourceType: "service" | "domain";
  resourceId: number;
  serviceName: string;
  name: string;
  domain?: string;
  ownerUserId: number;
  ownerUserName: string;
  createdTime: number;
  currentConnections: number;
  uploadSpeed: number;
  downloadSpeed: number;
  failedConnections: number;
  todayUpload: number;
  todayDownload: number;
  todayTotal: number;
  updatedAt: number;
  sharedIngress?: boolean;
  sharedTotalsHidden?: boolean;
  sources?: ServiceTelemetrySample[];
  topSources?: ServiceTelemetrySample[];
  domains?: ServiceTelemetrySample[];
}

export interface DomainRoute {
  id: number;
  name: string;
  domain: string;
  pathPrefix?: string;
  backendType?: "mapping" | "direct";
  backendNodeId?: number;
  backendNodeName?: string;
  backendNodeOnline?: boolean;
  backendHost?: string;
  backendPort?: number;
  backendScheme?: "http" | "https";
  backendPath?: string;
  backendStrategy?: "round" | "rand" | "weighted";
  sessionAffinity?: "none" | "ip_hash";
  backendMembers?: DomainRouteBackendMember[];
  healthState?: string;
  healthStatusCode?: number;
  healthLatencyMs?: number;
  healthCheckedAt?: number;
  healthError?: string;
  ownerUserName: string;
  ownerRoleId?: number;
  publishedServiceId?: number;
  mappingName: string;
  mappingState: string;
  mappingPublicPort?: number;
  nodeId: number;
  nodeName: string;
  nodeOnline: boolean;
  connectorOnline: boolean;
  publicHost?: string;
  mappingPublicHost?: string;
  listenPort: number;
  state: string;
  lastError?: string;
  ingressMode?: "passthrough" | "managed_https";
  dnsZoneId?: number;
  dnsRecordId?: string;
  certificateId?: number;
  certificateState?: string;
  certificateExpiresAt?: number;
  certificateIssuer?: string;
}

export interface DomainRouteBackendMember {
  id?: number;
  position?: number;
  name: string;
  backendType: "mapping" | "direct";
  publishedServiceId?: number;
  backendNodeId?: number;
  backendHost?: string;
  backendPort?: number;
  backendScheme?: "http" | "https";
  backendPath?: string;
  weight: number;
  enabled: boolean | number;
  healthState?: "pending" | "healthy" | "unhealthy";
  healthLatencyMs?: number;
  healthError?: string;
  healthCheckedAt?: number;
  targetName?: string;
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

export type PortLedgerType =
  | "forward_entry"
  | "multi_line_aggregation"
  | "tunnel_hop"
  | "pool_range"
  | "pool_control"
  | "user_grant"
  | "published_service"
  | "domain_ingress"
  | "home_proxy"
  | "source_ip_entry"
  | "nft_forward";

export interface PortLedgerEntry {
  key: string;
  type: PortLedgerType;
  status: "occupied" | "reserved" | "granted" | "cooldown";
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

export const createInternalConnector = (data: {
  name: string;
  allowedCidrs?: string;
  platform: ConnectorPlatform;
}) =>
  Network.mutate<{ connector: InternalConnector; installCommand: string }>(
    "/service-publishing/connector/create",
    data,
    ["/service-publishing/connector/list"],
  );
export const getInternalConnectors = () =>
  Network.postCached<InternalConnector[]>(
    "/service-publishing/connector/list",
    {},
    8000,
  );
export const getInternalConnectorInstall = (
  id: number,
  platform: ConnectorPlatform,
  action: "install" | "uninstall" = "install",
) =>
  Network.post<string>("/service-publishing/connector/install", {
    id,
    platform,
    action,
  });
export const deleteInternalConnector = (id: number) =>
  Network.mutate("/service-publishing/connector/delete", { id }, [
    "/service-publishing/connector/list",
  ]);
export interface LanDiscoveredService {
  id: number;
  connectorId: number;
  host: string;
  port: number;
  serviceType: string;
  serviceName: string;
  product?: string;
  title?: string;
  confidence: "high" | "medium";
  sensitive: number;
  firstSeenAt: number;
  lastSeenAt: number;
}
export interface LanDiscoveryResult {
  connectorId: number;
  enabled: boolean;
  status: "disabled" | "idle" | "scanning" | "complete" | "failed";
  lastScanAt?: number;
  lastCidr?: string;
  lastError?: string;
  scannedHosts?: number;
  scannedPorts?: number;
  durationMs?: number;
  services: LanDiscoveredService[];
}
export const setLanDiscoveryEnabled = (id: number, enabled: boolean) =>
  Network.post<LanDiscoveryResult>(
    "/service-publishing/connector/discovery/settings",
    { id, enabled },
  );
export const scanLanServices = (connectorId: number, cidr?: string) =>
  Network.post<LanDiscoveryResult>(
    "/service-publishing/connector/discovery/scan",
    { connectorId, cidr: cidr || "auto" },
  );
export const getLanDiscoveryResults = (id: number) =>
  Network.post<LanDiscoveryResult>(
    "/service-publishing/connector/discovery/results",
    { id },
  );
export const clearLanDiscoveryResults = (id: number) =>
  Network.post("/service-publishing/connector/discovery/clear", { id });
export const createPublishingPortPool = (data: any) =>
  Network.mutate<PublishingPortPool>("/service-publishing/pool/create", data, [
    "/service-publishing/pool/list",
  ]);
export const getPublishingPortPools = () =>
  Network.postCached<PublishingPortPool[]>(
    "/service-publishing/pool/list",
    {},
    8000,
  );
export const getPublishingPortGrants = (userId?: number) =>
  Network.post<PublishingPortGrant[]>(
    "/service-publishing/grant/list",
    userId ? { userId } : {},
  );
export const deletePublishingPortPool = (id: number) =>
  Network.mutate("/service-publishing/pool/delete", { id }, [
    "/service-publishing/pool/list",
  ]);
export const getPortLedger = (
  data: {
    nodeId?: number;
    port?: number;
    type?: string;
    keyword?: string;
  } = {},
) => Network.post<PortLedgerResult>("/service-publishing/ledger/list", data);
export const diagnosePort = (nodeId: number, port: number) =>
  Network.post<PortLedgerResult>("/service-publishing/ledger/diagnose", {
    nodeId,
    port,
  });

export type NftForwardProtocol = "tcp" | "udp" | "tcp_udp";
export type NftForwardNatMode = "masquerade" | "preserve_source";

export interface NftForwardRule {
  id: number;
  userId: number;
  name: string;
  nodeId: number;
  nodeName: string;
  publicHost?: string;
  agentVersion?: string;
  nodeOnline: boolean;
  listenAddress: string;
  listenPort: number;
  protocol: NftForwardProtocol;
  targetAddress: string;
  targetPort: number;
  natMode: NftForwardNatMode;
  sourceCidrs?: string;
  enabled: boolean;
  state: "provisioning" | "active" | "paused" | "error" | "delete_pending";
  generation?: number;
  packetCount: number;
  byteCount: number;
  lastError?: string;
  lastWarning?: string;
  lastSyncedAt?: number;
  createdTime: number;
  updatedTime: number;
  rollbackAvailable: boolean;
}

export interface NftForwardNode {
  id: number;
  name: string;
  serverIp?: string;
  ip?: string;
  status: number;
  version?: string;
  online: boolean;
  compatible: boolean;
}

export interface NftForwardOverview {
  rules: NftForwardRule[];
  nodes: NftForwardNode[];
  summary: {
    total: number;
    active: number;
    paused: number;
    errors: number;
    packets: number;
    bytes: number;
  };
  minimumAgentVersion: string;
}

export interface NftForwardForm {
  id?: number;
  name: string;
  nodeId: number;
  listenAddress: string;
  listenPort: number;
  protocol: NftForwardProtocol;
  targetAddress: string;
  targetPort: number;
  natMode: NftForwardNatMode;
  sourceCidrs?: string;
  enabled: boolean;
}

export interface NftForwardPreflight {
  supported: boolean;
  available: boolean;
  nftVersion?: string;
  ipv4Forwarding: boolean;
  firewallManager?: string;
  warnings?: string[];
  conflicts?: Array<{
    protocol: string;
    port: number;
    table?: string;
    chain?: string;
    detail: string;
  }>;
}

export interface NftForwardEvent {
  id: number;
  ruleId: number;
  nodeId: number;
  eventType: string;
  status: string;
  detail?: string;
  createdTime: number;
}

export const getNftForwardOverview = () =>
  Network.post<NftForwardOverview>("/nft-forward/overview");
export const preflightNftForward = (data: NftForwardForm) =>
  Network.post<NftForwardPreflight>("/nft-forward/preflight", data);
export const saveNftForward = (data: NftForwardForm) =>
  Network.post<{ id: number; state: string }>("/nft-forward/save", data);
export const toggleNftForward = (id: number, enabled: boolean) =>
  Network.post("/nft-forward/toggle", { id, enabled });
export const checkNftForward = (id: number) =>
  Network.post("/nft-forward/check", { id });
export const rollbackNftForward = (id: number) =>
  Network.post("/nft-forward/rollback", { id });
export const deleteNftForward = (id: number) =>
  Network.post("/nft-forward/delete", { id });
export const getNftForwardEvents = (id: number) =>
  Network.post<NftForwardEvent[]>("/nft-forward/events", { id });
export const createPublishedService = (data: any) =>
  Network.mutate<PublishedService>("/service-publishing/service/create", data, [
    "/service-publishing/service/list",
  ]);
export const getPublishedServices = () =>
  Network.postCached<PublishedService[]>(
    "/service-publishing/service/list",
    {},
    8000,
  );
export const getServiceTelemetrySummary = () =>
  Network.post<ServiceTelemetry[]>("/service-publishing/telemetry/summary");
export const getServiceTelemetryDetail = (
  resourceType: "service" | "domain",
  resourceId: number,
) =>
  Network.post<ServiceTelemetry>("/service-publishing/telemetry/detail", {
    resourceType,
    resourceId,
  });
export const renewPublishedService = (
  id: number,
  hours?: number,
  permanent = false,
) =>
  Network.mutate<PublishedService>(
    "/service-publishing/service/renew",
    { id, hours, permanent },
    ["/service-publishing/service/list"],
  );
export const deletePublishedService = (id: number) =>
  Network.mutate("/service-publishing/service/delete", { id }, [
    "/service-publishing/service/list",
  ]);
export const createDomainRoute = (data: {
  name: string;
  domain: string;
  pathPrefix?: string;
  publishedServiceId?: number;
  backendType?: "mapping" | "direct";
  backendNodeId?: number;
  backendHost?: string;
  backendPort?: number;
  backendScheme?: "http" | "https";
  backendPath?: string;
  entryNodeId?: number;
  listenPort: number;
  ingressMode: "passthrough" | "managed_https";
  dnsZoneId?: number;
}) =>
  Network.mutate<DomainRoute>("/service-publishing/domain/create", data, [
    "/service-publishing/domain/list",
  ]);
export const updateDomainRouteBackend = (data: {
  id: number;
  backendHost: string;
  backendPort: number;
  backendScheme: "http" | "https";
  backendPath: string;
}) =>
  Network.mutate<DomainRoute>(
    "/service-publishing/domain/backend/update",
    data,
    ["/service-publishing/domain/list"],
  );
export const updateDomainRoutePool = (data: {
  id: number;
  strategy: "round" | "rand" | "weighted";
  sessionAffinity: "none" | "ip_hash";
  members: DomainRouteBackendMember[];
}) =>
  Network.mutate<DomainRoute>("/service-publishing/domain/pool/update", data, [
    "/service-publishing/domain/list",
  ]);
export const getDomainRoutes = () =>
  Network.postCached<DomainRoute[]>(
    "/service-publishing/domain/list",
    {},
    8000,
  );
export const deleteDomainRoute = (id: number) =>
  Network.mutate("/service-publishing/domain/delete", { id }, [
    "/service-publishing/domain/list",
  ]);
export const getManagedCertificates = () =>
  Network.post<ManagedCertificate[]>("/service-publishing/certificate/list");
export const retryManagedCertificate = (id: number) =>
  Network.post("/service-publishing/certificate/retry", { id });

export interface HomeProxyRoute {
  id: number;
  userId: number;
  name: string;
  connectorId: number;
  accessMode: "relay" | "ipv6_direct" | "ipv4_direct" | "smart_nat";
  sourceConnectorId?: number;
  sourceListenPort?: number;
  natBackendPort?: number;
  natState?: "provisioning" | "probing" | "direct" | "relay" | "failed";
  activeAccessPath?: "udp_direct" | "relay";
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
  egressMode?: "single" | "tunnel";
  egressTunnelId?: number;
  transportMode?: "standard_tcp" | "socks5" | "vless_reality";
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
  proxyType: "socks5";
  authEnabled: number;
  authUsername?: string;
  authPassword?: string;
  state: "provisioning" | "active" | "error" | "delete_pending" | "deleted";
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
  egressPathNodeDetails?: Array<{
    nodeId: number;
    name: string;
    status: number;
  }>;
  publicHost?: string;
}

export const createHomeProxyRoute = (data: {
  name: string;
  connectorId: number;
  accessMode: "relay" | "ipv6_direct" | "ipv4_direct" | "smart_nat";
  sourceConnectorId?: number;
  sourceListenPort?: number;
  ingressPoolId?: number;
  ingressGrantId?: number;
  egressPoolId?: number;
  egressGrantId?: number;
  egressNodeId?: number;
  egressMode?: "single" | "tunnel";
  egressTunnelId?: number;
  transportMode?: "socks5" | "vless_reality";
  realityServerName?: string;
  directPort?: number;
  dynamicDnsRuleId?: number;
  authEnabled?: boolean;
  authUsername?: string;
  authPassword?: string;
}) =>
  Network.post<HomeProxyRoute>("/service-publishing/home-proxy/create", data);
export const getHomeProxyRoutes = () =>
  Network.post<HomeProxyRoute[]>("/service-publishing/home-proxy/list");
export const refreshHomeProxyIpv6 = (id: number) =>
  Network.post<{
    address: string;
    checkedAt: number;
    family?: "ipv4" | "ipv6";
  }>("/service-publishing/home-proxy/refresh-ipv6", { id });
export const deleteHomeProxyRoute = (id: number) =>
  Network.post("/service-publishing/home-proxy/delete", { id });
export interface HomeProxyNatEvent {
  id: number;
  routeId: number;
  eventType: string;
  accessPath?: string;
  detail?: string;
  createdTime: number;
}
export const retryHomeProxyNat = (id: number) =>
  Network.post<HomeProxyRoute>("/service-publishing/home-proxy/nat/retry", {
    id,
  });
export const getHomeProxyNatEvents = (id: number) =>
  Network.post<HomeProxyNatEvent[]>(
    "/service-publishing/home-proxy/nat/events",
    { id },
  );

// 验证码相关接口
export const checkCaptcha = () => Network.post("/captcha/check");
export const generateCaptcha = () => Network.post(`/captcha/generate`);
export const verifyCaptcha = (data: { captchaId: string; trackData: string }) =>
  Network.post("/captcha/verify", data);
