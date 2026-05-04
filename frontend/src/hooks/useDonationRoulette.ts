"use client";

import { useCallback, useEffect, useRef, useState } from "react";

export interface DonationEntry {
  messageId: string;
  donorNickname: string;
  message: string | null;
  amount: string | null;
  timestamp: string | null;
}

interface UseDonationRouletteOptions {
  roomId: string;
  isOwner: boolean;
  fetchOwned: (url: string, init?: RequestInit) => Promise<Response | null>;
}

export function useDonationRoulette({ roomId, isOwner, fetchOwned }: UseDonationRouletteOptions) {
  const [donations, setDonations] = useState<DonationEntry[]>([]);
  const [winner, setWinner] = useState<DonationEntry | null>(null);
  const [isSpinning, setIsSpinning] = useState(false);
  const [isClearing, setIsClearing] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchDonations = useCallback(async () => {
    if (!isOwner) return;
    const res = await fetchOwned(`/api/channels/${roomId}/donations`);
    if (res && res.ok) {
      const data = (await res.json()) as DonationEntry[];
      setDonations(data);
    }
  }, [roomId, isOwner, fetchOwned]);

  useEffect(() => {
    if (!isOwner) return;
    void fetchDonations();
    pollRef.current = setInterval(() => void fetchDonations(), 5_000);
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [isOwner, fetchDonations]);

  const spin = useCallback(async () => {
    if (!isOwner || isSpinning) return;
    setIsSpinning(true);
    setWinner(null);
    try {
      const res = await fetchOwned(`/api/channels/${roomId}/donations/spin`, { method: "POST" });
      if (res && res.ok) {
        const picked = (await res.json()) as DonationEntry;
        setWinner(picked);
      } else if (res?.status === 404) {
        setWinner(null);
      }
    } finally {
      setIsSpinning(false);
    }
  }, [roomId, isOwner, isSpinning, fetchOwned]);

  const clearPool = useCallback(async () => {
    if (!isOwner || isClearing) return;
    setIsClearing(true);
    try {
      await fetchOwned(`/api/channels/${roomId}/donations`, { method: "DELETE" });
      setDonations([]);
      setWinner(null);
    } finally {
      setIsClearing(false);
    }
  }, [roomId, isOwner, isClearing, fetchOwned]);

  return { donations, winner, isSpinning, isClearing, spin, clearPool, refetch: fetchDonations };
}
