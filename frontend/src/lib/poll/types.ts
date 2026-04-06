export type PollMode = "AUTO" | "CHAT_DIRECT" | "OFFICIAL_API_DIRECT" | "OFFICIAL_API_BACKEND" | "WEB_FALLBACK";

export type ResolvedPollMode = Exclude<PollMode, "AUTO"> | "BACKEND_CHAT";

export type PollStatus = "idle" | "ready" | "unsupported" | "error";

export interface PollItem {
  id: string;
  label: string;
}

export type PollResults = Record<string, number>;

export interface PollHistoryEntry {
  messageId: string;
  content?: string;
  emotionType: string;
  emotionScore: number;
  analyzedAt?: string;
}

export interface PollCapability {
  mode: ResolvedPollMode;
  available: boolean;
  requiresBackend: boolean;
  browserDirect: boolean;
  official: boolean;
  reason: string;
}

export interface PollSnapshot {
  isSessionActive: boolean;
  items: string[];
  results: PollResults;
  voters: Record<string, string>;
}

export interface PollSession {
  preferredMode: PollMode;
  resolvedMode: ResolvedPollMode;
  status: PollStatus;
  providerLabel: string;
  capability: PollCapability;
  isSessionActive: boolean;
  items: PollItem[];
  results: PollResults;
  voters: Record<string, string>;
  selectedVoter: string | null;
  voterHistory: PollHistoryEntry[];
  showComposer: boolean;
  composerItems: string[];
  message?: string;
}
