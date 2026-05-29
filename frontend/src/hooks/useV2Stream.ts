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
  insight?: string;  // LLM 생성 자연어 해석 문장
}

export interface AlertFeedItem extends V2SimilarHighlightAlert {
  _id: string;
}

const MAX_FEED_SIZE = 20;

export function useV2Stream(channelId: string, enabled: boolean) {
  const [frame, setFrame] = useState<V2AggregateFrame | null>(null);
  const [connected, setConnected] = useState(false);
  const [alertFeed, setAlertFeed] = useState<AlertFeedItem[]>([]);
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
        setFrame(JSON.parse(e.data) as V2AggregateFrame);
      } catch {
        // ignore malformed frames
      }
    });

    es.addEventListener("v2_similar_highlight", (e: MessageEvent<string>) => {
      try {
        const alert = JSON.parse(e.data) as V2SimilarHighlightAlert;
        const item: AlertFeedItem = {
          ...alert,
          _id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
        };
        setAlertFeed((prev) => [item, ...prev].slice(0, MAX_FEED_SIZE));
      } catch {
        // ignore malformed alert
      }
    });

    es.addEventListener("error", () => setConnected(false));

    return () => {
      es.close();
      esRef.current = null;
      setConnected(false);
    };
  }, [channelId, enabled]);

  const dismissAlertById = (id: string) => {
    setAlertFeed((prev) => prev.filter((a) => a._id !== id));
  };

  const clearAllAlerts = () => setAlertFeed([]);

  return { frame, connected, alertFeed, dismissAlertById, clearAllAlerts };
}
