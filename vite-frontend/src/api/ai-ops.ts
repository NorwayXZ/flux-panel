import Network from "./network";
export interface OpsConfig {
  allowPanelRead?: boolean;
  allowTerminal?: boolean;
  upgradeOnly?: boolean;
  allowLogCleanup?: boolean;
  allowAgentUpgrade?: boolean;
  notify?: boolean;
  name: string;
  prompt: string;
  nodeIds: number[];
  cron: string;
  timezone: string;
  mode: "inspect" | "repair";
  retentionDays: number;
  maxJournalMb: number;
  serviceAllowlist: string[];
}
export interface OpsTask {
  id: number;
  config: OpsConfig;
  enabled: boolean;
  running: boolean;
  nextRun: number;
  lastStatus?: string;
  lastRunAt?: number;
}
export interface OpsSettings {
  baseUrl: string;
  model: string;
  keyConfigured: boolean;
}
export interface OpsOverview {
  settings: OpsSettings;
  tasks: OpsTask[];
  nodes: { id: number; name: string; version: string; status: number }[];
  minimumAgentVersion: string;
}
export interface OpsRun {
  id: number;
  startedAt: number;
  finishedAt?: number;
  status: string;
  detail?: string;
}
export const opsOverview = () =>
  Network.post<OpsOverview>("/ai-ops/overview", {});
export const opsSettings = (data: {
  base_url: string;
  model: string;
  api_key: string;
}) => Network.post("/ai-ops/settings", data);
export const opsSave = (data: {
  id?: number;
  config: OpsConfig;
  enabled: boolean;
}) => Network.post("/ai-ops/save", data);
export const opsRun = (id: number) => Network.post("/ai-ops/run", { id });
export const opsDelete = (id: number) => Network.post("/ai-ops/delete", { id });
export const opsDetail = (id: number) =>
  Network.post<{ task: OpsTask; runs: OpsRun[] }>("/ai-ops/detail", { id });

export interface OpsChatEvent {
  type: string;
  text?: string;
  message?: any;
  call?: any;
  result?: any;
  status?: string;
  chatId?: number;
  turnId?: number;
}
export interface OpsChatTurn {
  id: number;
  userContent: string;
  status: string;
  events: string;
  createdAt: number;
}
export const opsChatList = () =>
  Network.post<{ id: number; title: string; updatedAt: number }[]>(
    "/ai-ops/chat/list",
    {},
  );
export const opsChatDetail = (id: number) =>
  Network.post<{ id: number; turns: OpsChatTurn[] }>("/ai-ops/chat/detail", {
    id,
  });
export const opsChatDelete = (id: number) =>
  Network.post("/ai-ops/chat/delete", { id });
export const opsChatStop = () => Network.post("/ai-ops/chat/stop", {});

export interface OpsPreferences {
  alertDiagnosis: boolean;
  weeklyReport: boolean;
  /** Extra models allowed for per-conversation switching, comma separated. */
  models?: string;
  /** Price per million input / output tokens, used to estimate cost. */
  inputPrice?: string;
  outputPrice?: string;
  currency?: string;
  availableModels?: string[];
}
export interface OpsReport {
  id: number;
  kind: string;
  title: string;
  content: string;
  createdAt: number;
}
export const opsPreferences = () =>
  Network.post<OpsPreferences>("/ai-ops/preferences", {});
export const opsSavePreferences = (data: Partial<OpsPreferences>) =>
  Network.post<OpsPreferences>("/ai-ops/preferences/save", data);
export const opsReports = () => Network.post<OpsReport[]>("/ai-ops/reports", {});
export const opsChatRename = (id: number, title: string) =>
  Network.post("/ai-ops/chat/rename", { id, title });
export const opsChatSearch = (q: string) =>
  Network.post<{ id: number; title: string; updatedAt: number }[]>("/ai-ops/chat/search", { q });

export interface OpsNodeNote {
  nodeId: number;
  name: string;
  content?: string;
  updatedAt?: number;
  updatedBy?: string;
}
export interface OpsChange {
  id: number;
  nodeId: number;
  nodeName?: string;
  kind: "file" | "command";
  source: string;
  summary: string;
  path?: string;
  beforeContent?: string;
  afterContent?: string;
  existedBefore?: number;
  command?: string;
  exitCode?: number;
  output?: string;
  restoredAt?: number;
  createdAt: number;
}
export const opsNotes = () => Network.post<OpsNodeNote[]>("/ai-ops/notes", {});
export const opsSaveNote = (nodeId: number, content: string) =>
  Network.post("/ai-ops/notes/save", { nodeId, content });
export const opsChanges = (nodeId?: number) =>
  Network.post<OpsChange[]>("/ai-ops/changes", nodeId ? { nodeId } : {});
export const opsRestoreChange = (id: number) =>
  Network.post<{ restored: boolean; path: string }>("/ai-ops/changes/restore", { id });
export const opsGenerateReport = () =>
  Network.post<{ queued: boolean; message: string }>("/ai-ops/reports/generate", {});

export interface OpsProposal {
  id: number;
  creatorId: number;
  source: string;
  action: string;
  actionLabel: string;
  targetId: number;
  targetName?: string;
  title: string;
  reason: string;
  status: "pending" | "executed" | "failed" | "rejected" | "expired";
  createdAt: number;
  decidedBy?: number;
  decidedAt?: number;
  executedAt?: number;
  result?: string;
}
export const opsProposals = (status?: string) =>
  Network.post<OpsProposal[]>("/ai-ops/proposals", status ? { status } : {});
export const opsApproveProposal = (id: number) =>
  Network.post<OpsProposal>("/ai-ops/proposals/approve", { id });
export const opsRejectProposal = (id: number) =>
  Network.post<OpsProposal>("/ai-ops/proposals/reject", { id });
