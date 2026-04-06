import type { PollCapability, PollHistoryEntry, PollSnapshot, ResolvedPollMode } from "@/lib/poll/types";

export interface PollProviderContext {
  roomId: string;
  ownerId: string;
  fetchOwned: (url: string, init?: RequestInit) => Promise<Response | null>;
}

export interface PollProvider {
  mode: ResolvedPollMode;
  label: string;
  getCapability: () => PollCapability;
  loadSnapshot: (context: PollProviderContext) => Promise<PollSnapshot>;
  refreshSnapshot?: (context: PollProviderContext) => Promise<Partial<PollSnapshot>>;
  setSessionActive: (context: PollProviderContext, active: boolean) => Promise<void>;
  createPoll: (context: PollProviderContext, items: string[]) => Promise<void>;
  clearPoll: (context: PollProviderContext) => Promise<void>;
  getVoterHistory: (context: PollProviderContext, userId: string) => Promise<PollHistoryEntry[]>;
}

export async function readJson<T>(response: Response | null): Promise<T | null> {
  if (!response) {
    return null;
  }

  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`);
  }

  return (await response.json()) as T;
}

export function createUnsupportedProvider(
  mode: ResolvedPollMode,
  label: string,
  capability: PollCapability,
): PollProvider {
  const unsupported = async () => {
    throw new Error(capability.reason);
  };

  return {
    mode,
    label,
    getCapability: () => capability,
    loadSnapshot: async () => ({
      isSessionActive: false,
      items: [],
      results: {},
      voters: {},
    }),
    refreshSnapshot: async () => ({
      results: {},
      voters: {},
    }),
    setSessionActive: unsupported,
    createPoll: unsupported,
    clearPoll: unsupported,
    getVoterHistory: async () => [],
  };
}
