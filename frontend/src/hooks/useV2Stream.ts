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

export interface V2SimilarHighlightAlert {
  roomId: string;
  highlightId: number;
  videoNo: string;
  sceneLabel: string;
  category: string;
  reasonSummary: string;
  similarity: number;
  trigger: "positive_spike" | "negative_spike";
  detectedAt: string;
}

const ALERT_AUTO_DISMISS_MS = 30_000;

export function useV2Stream(channelId: string, enabled: boolean) {
  const [frame, setFrame] = useState<V2AggregateFrame | null>(null);
  const [connected, setConnected] = useState(false);
  const [similarAlert, setSimilarAlert] = useState<V2SimilarHighlightAlert | null>(null);
  const esRef = useRef<EventSource | null>(null);
  const dismissTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

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
        setFrame(JSON.parse(e.data) as V2AggregateFrame);
      } catch {
        // ignore malformed frames
      }
    });

    es.addEventListener("v2_similar_highlight", (e: MessageEvent<string>) => {
      try {
        const alert = JSON.parse(e.data) as V2SimilarHighlightAlert;
        setSimilarAlert(alert);
        if (dismissTimerRef.current) clearTimeout(dismissTimerRef.current);
        dismissTimerRef.current = setTimeout(() => setSimilarAlert(null), ALERT_AUTO_DISMISS_MS);
      } catch {
        // ignore malformed alert
      }
    });

    es.addEventListener("error", () => setConnected(false));

    return () => {
      es.close();
      esRef.current = null;
      setConnected(false);
      if (dismissTimerRef.current) clearTimeout(dismissTimerRef.current);
    };
  }, [channelId, enabled]);

  return { frame, connected, similarAlert, dismissAlert: () => setSimilarAlert(null) };
}
