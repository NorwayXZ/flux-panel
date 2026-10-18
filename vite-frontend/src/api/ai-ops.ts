import Network from "./network";
export interface OpsConfig {
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
