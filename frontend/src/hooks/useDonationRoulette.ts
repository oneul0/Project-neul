"use client";

import { useCallback, useEffect, useRef, useState } from "react";

// ─── 공개 타입 ──────────────────────────────────────────────────────────────

export interface RouletteItem {
  name: string;
  weight: number;       // base 1.0 + donation contribution
  probability: number;  // 0.0 ~ 1.0
}

export interface RouletteStateData {
  items: RouletteItem[];
  rate: number;        // KRW per weight unit
  totalWeight: number;
}

export interface RouletteResultData {
  winner: string;
  probability: number;
}

// ─── 백엔드 API 응답 형태 ──────────────────────────────────────────────────

interface ApiRouletteState {
  items: string[];
  rate: number;
  weights: Record<string, number>;
  probabilities: Record<string, number>;
  totalWeight: number;
}

interface ApiRouletteResult {
  winner: string;
  probability: number;
}

// ─── Hook ───────────────────────────────────────────────────────────────────

interface UseRouletteOptions {
  roomId: string;
  isOwner: boolean;
  fetchOwned: (url: string, init?: RequestInit) => Promise<Response | null>;
}

export interface UseRouletteReturn {
  state: RouletteStateData | null;
  result: RouletteResultData | null;
  isLoading: boolean;
  isSpinning: boolean;
  isResetting: boolean;
  setConfig: (items: string[], rate: number) => Promise<void>;
  spin: () => Promise<void>;
  resetWeights: () => Promise<void>;  // 도네이션 가중치만 초기화 (항목 유지)
  clearAll: () => Promise<void>;      // 항목·배율·가중치 전체 삭제
  refetch: () => Promise<void>;
}

function toViewModel(api: ApiRouletteState): RouletteStateData {
  const items: RouletteItem[] = api.items.map((name) => ({
    name,
    weight: api.weights[name] ?? 1.0,
    probability: api.probabilities[name] ?? 1 / (api.items.length || 1),
  }));
  return { items, rate: api.rate, totalWeight: api.totalWeight };
}

export function useDonationRoulette({
  roomId,
  isOwner,
  fetchOwned,
}: UseRouletteOptions): UseRouletteReturn {
  const [state, setState] = useState<RouletteStateData | null>(null);
  const [result, setResult] = useState<RouletteResultData | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isSpinning, setIsSpinning] = useState(false);
  const [isResetting, setIsResetting] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchState = useCallback(async () => {
    // GET은 인증 불필요 — 직접 fetch
    try {
      const res = await fetch(`/api/channels/${roomId}/roulette`);
      if (res.ok) {
        const data = (await res.json()) as ApiRouletteState;
        setState(toViewModel(data));
      }
    } catch {
      // 백엔드 미실행 등 무시
    }
  }, [roomId]);

  // 주기적 상태 폴링 (5초)
  useEffect(() => {
    setIsLoading(true);
    void fetchState().finally(() => setIsLoading(false));
    pollRef.current = setInterval(() => void fetchState(), 5_000);
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [fetchState]);

  const setConfig = useCallback(
    async (items: string[], rate: number) => {
      const res = await fetchOwned(`/api/channels/${roomId}/roulette/config`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ items, rate }),
      });
      if (res && (res.ok || res.status === 204)) {
        setResult(null);
        await fetchState();
      }
    },
    [roomId, fetchOwned, fetchState],
  );

  const spin = useCallback(async () => {
    if (!isOwner || isSpinning) return;
    setIsSpinning(true);
    setResult(null);
    try {
      const res = await fetchOwned(`/api/channels/${roomId}/roulette/spin`, { method: "POST" });
      if (res && res.ok) {
        const data = (await res.json()) as ApiRouletteResult;
        setResult(data);
      }
    } finally {
      setIsSpinning(false);
    }
  }, [roomId, isOwner, isSpinning, fetchOwned]);

  const resetWeights = useCallback(async () => {
    if (!isOwner || isResetting) return;
    setIsResetting(true);
    try {
      await fetchOwned(`/api/channels/${roomId}/roulette/weights`, { method: "DELETE" });
      setResult(null);
      await fetchState();
    } finally {
      setIsResetting(false);
    }
  }, [roomId, isOwner, isResetting, fetchOwned, fetchState]);

  const clearAll = useCallback(async () => {
    if (!isOwner) return;
    await fetchOwned(`/api/channels/${roomId}/roulette`, { method: "DELETE" });
    setState(null);
    setResult(null);
    await fetchState();
  }, [roomId, isOwner, fetchOwned, fetchState]);

  return {
    state,
    result,
    isLoading,
    isSpinning,
    isResetting,
    setConfig,
    spin,
    resetWeights,
    clearAll,
    refetch: fetchState,
  };
}
