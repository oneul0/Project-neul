"use client";

import { useEffect, useRef, useState } from "react";

export interface V2MentalBufferState {
  roomId: string;
  emaPositive: number;
  emaNegative: number;
  rawPositive: number;
  rawNegative: number;
  updatedAt: string;
}

export interface V2NarrativeBriefing {
  roomId: string;
  summary: string;
  confidence: number;
  generatedAt: string;
  sourceWindowStart: string;
  sourceWindowEnd: string;
}

export interface V2AnchorChat {
  messageId: string;
  senderId: string;
  sender: string;
  content: string;
  weight: number;
  clusterId: string;
}

export interface V2AggregateFrame {
  roomId: string;
  emittedAt: string;
  balance: number;
  mentalBuffer: V2MentalBufferState | null;
  trustSummary: Record<string, unknown> | null;
  anchors: V2AnchorChat[];
  keywords: string[];
  topicLabel: string;
  briefing: V2NarrativeBriefing | null;
  stats: Record<string, unknown> | null;
}

export function useV2Stream(channelId: string, enabled: boolean) {
  const [frame, setFrame] = useState<V2AggregateFrame | null>(null);
  const [connected, setConnected] = useState(false);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!enabled || !channelId) return;

    fetch(`/api/channels/${channelId}/v2/state`, { credentials: "include" })
      .then((res) => (res.ok ? res.json() : null))
      .then((data: V2AggregateFrame | null) => {
        if (data) setFrame(data);
      })
      .catch(() => {});

    const es = new EventSource(`/api/channels/${channelId}/v2/stream`);
    esRef.current = es;

    es.addEventListener("open", () => setConnected(true));

    es.addEventListener("v2_frame", (e: MessageEvent<string>) => {
      try {
        const parsed = JSON.parse(e.data) as V2AggregateFrame;
        setFrame(parsed);
      } catch {
        // ignore malformed frames
      }
    });

    es.addEventListener("error", () => setConnected(false));

    return () => {
      es.close();
      esRef.current = null;
      setConnected(false);
    };
  }, [channelId, enabled]);

  return { frame, connected };
}
