import { createUnsupportedProvider, readJson, type PollProvider } from "@/lib/poll/provider";
import type { PollCapability, PollHistoryEntry } from "@/lib/poll/types";

const capability: PollCapability = {
  mode: "BACKEND_CHAT",
  available: true,
  requiresBackend: true,
  browserDirect: false,
  official: false,
  reason: "현재 프로젝트의 실동작 투표는 core-api /api/v1/poll/* 엔드포인트를 사용합니다.",
};

export const backendChatProvider: PollProvider = {
  mode: "BACKEND_CHAT",
  label: "Backend Chat",
  getCapability: () => capability,
  loadSnapshot: async ({ roomId, fetchOwned }) => {
    const [session, results, items, voters] = await Promise.all([
      readJson<boolean>(await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}/session`)),
      readJson<Record<string, number>>(await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}/results`)),
      readJson<string[]>(await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}/items`)),
      readJson<Record<string, string>>(await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}/voters`)),
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
      readJson<Record<string, number>>(await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}/results`)),
      readJson<Record<string, string>>(await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}/voters`)),
    ]);

    return {
      results: results ?? {},
      voters: voters ?? {},
    };
  },
  setSessionActive: async ({ roomId, fetchOwned }, active) => {
    await readJson<boolean>(await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}/session?active=${active}`, {
      method: "POST",
    }));
  },
  createPoll: async ({ roomId, ownerId, fetchOwned }, items) => {
    await readJson<boolean>(
      await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}/items`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Chzzk-Owner-Id": ownerId,
        },
        body: JSON.stringify(items),
      }),
    );

    await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}`, {
      method: "DELETE",
    });
  },
  clearPoll: async ({ roomId, fetchOwned }) => {
    await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}`, {
      method: "DELETE",
    });
  },
  getVoterHistory: async ({ roomId, fetchOwned }, userId) => {
    return (
      (await readJson<PollHistoryEntry[]>(
        await fetchOwned(`http://localhost:8083/api/v1/poll/${roomId}/voters/${userId}/history`),
      )) ?? []
    );
  },
};

export const unsupportedBackendChatProvider = createUnsupportedProvider("BACKEND_CHAT", "Backend Chat", capability);
