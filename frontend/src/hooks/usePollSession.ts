"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { resolvePollMode } from "@/lib/poll/mode-resolver";
import type { PollMode, PollSession, PollStatus, ResolvedPollMode } from "@/lib/poll/types";
import { backendChatProvider } from "@/lib/poll/providers/backend-chat";
import { chatDirectProvider } from "@/lib/poll/providers/chat-direct";
import { officialApiDirectProvider } from "@/lib/poll/providers/official-api-direct";
import { officialApiBackendProvider } from "@/lib/poll/providers/official-api-backend";
import { webFallbackProvider } from "@/lib/poll/providers/web-fallback";
import type { PollProvider, PollProviderContext } from "@/lib/poll/provider";

const providers: Record<ResolvedPollMode, PollProvider> = {
  BACKEND_CHAT: backendChatProvider,
  CHAT_DIRECT: chatDirectProvider,
  OFFICIAL_API_DIRECT: officialApiDirectProvider,
  OFFICIAL_API_BACKEND: officialApiBackendProvider,
  WEB_FALLBACK: webFallbackProvider,
};

interface UsePollSessionParams {
  roomId: string;
  ownerId: string;
  isAuthorizedChannel: boolean;
  preferredMode?: PollMode;
  fetchOwned: (url: string, init?: RequestInit) => Promise<Response | null>;
}

export interface UsePollSessionResult extends PollSession {
  totalVotes: number;
  canManage: boolean;
  setSessionActive: (active: boolean) => Promise<void>;
  toggleComposer: () => void;
  updateComposerItem: (index: number, value: string) => void;
  addComposerItem: () => void;
  createPoll: () => Promise<void>;
  clearPoll: () => Promise<void>;
  openVoterHistory: (userId: string) => Promise<void>;
  closeVoterHistory: () => void;
  refresh: () => Promise<void>;
}

function toPollSession(
  preferredMode: PollMode,
  resolvedMode: ResolvedPollMode,
  provider: PollProvider,
  status: PollStatus,
  message?: string,
): PollSession {
  return {
    preferredMode,
    resolvedMode,
    status,
    providerLabel: provider.label,
    capability: provider.getCapability(),
    isSessionActive: false,
    items: [],
    results: {},
    voters: {},
    selectedVoter: null,
    voterHistory: [],
    showComposer: false,
    composerItems: ["", ""],
    message,
  };
}

export function usePollSession({
  roomId,
  ownerId,
  isAuthorizedChannel,
  preferredMode = "AUTO",
  fetchOwned,
}: UsePollSessionParams): UsePollSessionResult {
  const capabilityMap = useMemo(
    () => ({
      BACKEND_CHAT: providers.BACKEND_CHAT.getCapability(),
      CHAT_DIRECT: providers.CHAT_DIRECT.getCapability(),
      OFFICIAL_API_DIRECT: providers.OFFICIAL_API_DIRECT.getCapability(),
      OFFICIAL_API_BACKEND: providers.OFFICIAL_API_BACKEND.getCapability(),
      WEB_FALLBACK: providers.WEB_FALLBACK.getCapability(),
    }),
    [],
  );

  const resolvedMode = useMemo(() => resolvePollMode(preferredMode, capabilityMap), [capabilityMap, preferredMode]);
  const provider = providers[resolvedMode];

  const [session, setSession] = useState<PollSession>(() =>
    toPollSession(preferredMode, resolvedMode, provider, provider.getCapability().available ? "idle" : "unsupported", provider.getCapability().reason),
  );

  const buildContext = useCallback(
    (): PollProviderContext => ({
      roomId,
      ownerId,
      fetchOwned,
    }),
    [fetchOwned, ownerId, roomId],
  );

  const applySnapshot = useCallback((next: { isSessionActive?: boolean; items?: string[]; results?: Record<string, number>; voters?: Record<string, string> }) => {
    setSession((prev) => ({
      ...prev,
      isSessionActive: next.isSessionActive ?? prev.isSessionActive,
      items: next.items ? next.items.map((item) => ({ id: item, label: item })) : prev.items,
      results: next.results ?? prev.results,
      voters: next.voters ?? prev.voters,
      status: provider.getCapability().available ? "ready" : "unsupported",
      message: provider.getCapability().available ? undefined : provider.getCapability().reason,
    }));
  }, [provider]);

  const refresh = useCallback(async () => {
    if (!roomId || !ownerId || !isAuthorizedChannel) {
      return;
    }

    const snapshot = await provider.loadSnapshot(buildContext());
    applySnapshot(snapshot);
  }, [applySnapshot, buildContext, isAuthorizedChannel, ownerId, provider, roomId]);

  useEffect(() => {
    const nextStatus = provider.getCapability().available ? "idle" : "unsupported";
    const nextMessage = !isAuthorizedChannel
      ? "본인 채널에서만 투표 상태를 확인할 수 있습니다."
      : provider.getCapability().available
        ? undefined
        : provider.getCapability().reason;

    setSession((prev) => ({
      ...toPollSession(preferredMode, resolvedMode, provider, nextStatus, nextMessage),
      showComposer: prev.showComposer,
      composerItems: prev.composerItems,
    }));

    if (!roomId || !ownerId || !isAuthorizedChannel || !provider.getCapability().available) {
      return;
    }

    let disposed = false;

    const load = async () => {
      try {
        const snapshot = await provider.loadSnapshot(buildContext());
        if (!disposed) {
          applySnapshot(snapshot);
        }
      } catch (error) {
        if (!disposed) {
          setSession((prev) => ({
            ...prev,
            status: "error",
            message: error instanceof Error ? error.message : "투표 상태를 불러오지 못했습니다.",
          }));
        }
      }
    };

    void load();

    const intervalId = window.setInterval(async () => {
      try {
        const next = provider.refreshSnapshot
          ? await provider.refreshSnapshot(buildContext())
          : await provider.loadSnapshot(buildContext());
        if (!disposed) {
          applySnapshot(next);
        }
      } catch {
        // keep last successful poll state
      }
    }, 3000);

    return () => {
      disposed = true;
      window.clearInterval(intervalId);
    };
  }, [applySnapshot, buildContext, isAuthorizedChannel, ownerId, preferredMode, provider, resolvedMode, roomId]);

  const setSessionActive = useCallback(async (active: boolean) => {
    if (!provider.getCapability().available) {
      return;
    }

    await provider.setSessionActive(buildContext(), active);
    setSession((prev) => ({
      ...prev,
      isSessionActive: active,
    }));
  }, [buildContext, provider]);

  const toggleComposer = useCallback(() => {
    setSession((prev) => ({
      ...prev,
      showComposer: !prev.showComposer,
    }));
  }, []);

  const updateComposerItem = useCallback((index: number, value: string) => {
    setSession((prev) => ({
      ...prev,
      composerItems: prev.composerItems.map((item, itemIndex) => (itemIndex === index ? value : item)),
    }));
  }, []);

  const addComposerItem = useCallback(() => {
    setSession((prev) => ({
      ...prev,
      composerItems: [...prev.composerItems, ""],
    }));
  }, []);

  const createPoll = useCallback(async () => {
    const items = session.composerItems.map((item) => item.trim()).filter(Boolean);
    if (items.length < 2) {
      window.alert("투표 항목을 두 개 이상 입력해 주세요.");
      return;
    }

    await provider.createPoll(buildContext(), items);
    setSession((prev) => ({
      ...prev,
      showComposer: false,
      composerItems: items.length > 0 ? items : ["", ""],
      selectedVoter: null,
      voterHistory: [],
    }));
    await refresh();
  }, [buildContext, provider, refresh, session.composerItems]);

  const clearPoll = useCallback(async () => {
    if (!window.confirm("현재 투표를 초기화할까요?")) {
      return;
    }

    await provider.clearPoll(buildContext());
    setSession((prev) => ({
      ...prev,
      results: {},
      voters: {},
      selectedVoter: null,
      voterHistory: [],
    }));
  }, [buildContext, provider]);

  const openVoterHistory = useCallback(async (userId: string) => {
    const voterHistory = await provider.getVoterHistory(buildContext(), userId);
    setSession((prev) => ({
      ...prev,
      selectedVoter: userId,
      voterHistory,
    }));
  }, [buildContext, provider]);

  const closeVoterHistory = useCallback(() => {
    setSession((prev) => ({
      ...prev,
      selectedVoter: null,
      voterHistory: [],
    }));
  }, []);

  return {
    ...session,
    preferredMode,
    resolvedMode,
    providerLabel: provider.label,
    capability: provider.getCapability(),
    totalVotes: Object.values(session.results).reduce((sum, count) => sum + count, 0),
    canManage: isAuthorizedChannel && provider.getCapability().available,
    setSessionActive,
    toggleComposer,
    updateComposerItem,
    addComposerItem,
    createPoll,
    clearPoll,
    openVoterHistory,
    closeVoterHistory,
    refresh,
  };
}
