import { createUnsupportedProvider, readJson, type PollProvider } from "@/lib/poll/provider";
import type { PollCapability, PollHistoryEntry } from "@/lib/poll/types";

const capability: PollCapability = {
  mode: "BACKEND_CHAT",
  available: true,
  requiresBackend: true,
  browserDirect: false,
  official: false,
  reason: "채팅 분석을 통해 투표를 집계합니다.",
};

export const backendChatProvider: PollProvider = {
  mode: "BACKEND_CHAT",
  label: "Backend Chat",
  getCapability: () => capability,
  loadSnapshot: async ({ roomId, fetchOwned }) => {
    const [session, results, items, voters] = await Promise.all([
      readJson<boolean>(await fetchOwned(`/api/channels/${roomId}/poll/session`)),
      readJson<Record<string, number>>(await fetchOwned(`/api/channels/${roomId}/poll/results`)),
      readJson<string[]>(await fetchOwned(`/api/channels/${roomId}/poll/items`)),
      readJson<Record<string, string>>(await fetchOwned(`/api/channels/${roomId}/poll/voters`)),
    ]);

    return {
      isSessionActive: session ?? false,
      items: items ?? [],
      results: results ?? {},
      voters: voters ?? {},
    };
  },
  refreshSnapshot: async ({ roomId, fetchOwned }) => {
    const [results, voters] = await Promise.all([
      readJson<Record<string, number>>(await fetchOwned(`/api/channels/${roomId}/poll/results`)),
      readJson<Record<string, string>>(await fetchOwned(`/api/channels/${roomId}/poll/voters`)),
    ]);

    return {
      results: results ?? {},
      voters: voters ?? {},
    };
  },
  setSessionActive: async ({ roomId, fetchOwned }, active) => {
    await readJson<boolean>(await fetchOwned(`/api/channels/${roomId}/poll/session?active=${active}`, {
      method: "POST",
    }));
  },
  createPoll: async ({ roomId, fetchOwned }, items) => {
    await readJson<boolean>(
      await fetchOwned(`/api/channels/${roomId}/poll/items`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(items),
      }),
    );

    await fetchOwned(`/api/channels/${roomId}/poll`, {
      method: "DELETE",
    });
  },
  clearPoll: async ({ roomId, fetchOwned }) => {
    await fetchOwned(`/api/channels/${roomId}/poll`, {
      method: "DELETE",
    });
  },
  getVoterHistory: async ({ roomId, fetchOwned }, userId) => {
    return (
      (await readJson<PollHistoryEntry[]>(
        await fetchOwned(`/api/channels/${roomId}/poll/voters/${userId}/history`),
      )) ?? []
    );
  },
};

export const unsupportedBackendChatProvider = createUnsupportedProvider("BACKEND_CHAT", "Backend Chat", capability);
