import Network from "./network";
export interface OpsIssue {
  id: number;
  sourceType: "alert" | "self_check" | "ai_task";
  sourceId: number;
  resourceType?: string;
  resourceId?: number;
  title: string;
  evidence: string;
  phase:
    | "detected"
    | "handling"
    | "awaiting_verification"
    | "verifying"
    | "verified";
  note?: string;
  chatId?: number;
  verification?: string;
  updatedAt: number;
}
export const getOpsIssues = () => Network.post<OpsIssue[]>("/ops-issues/list");
export const getOpsIssueContext = (id: number) =>
  Network.post<{ prompt: string; nodeIds: number[] }>("/ops-issues/context", {
    id,
  });
export const saveOpsIssueNote = (id: number, note: string) =>
  Network.post<OpsIssue[]>("/ops-issues/note", { id, note });
export const verifyOpsIssue = (id: number) =>
  Network.post<OpsIssue[]>("/ops-issues/verify", { id });
