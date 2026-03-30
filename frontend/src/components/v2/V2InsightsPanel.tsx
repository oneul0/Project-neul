"use client";

import { useEffect, useRef, useState } from "react";
import AudienceBalanceCard from "@/components/v2/AudienceBalanceCard";
import MentalBufferBar from "@/components/v2/MentalBufferBar";
import AnchorChatPanel from "@/components/v2/AnchorChatPanel";
import NarrativeBriefingCard from "@/components/v2/NarrativeBriefingCard";
import TrustFilterWidget from "@/components/v2/TrustFilterWidget";
import { appendOwnerId, buildOwnerHeaders } from "@/lib/ownerAuth";

interface AnchorChat {
  messageId: string;
  sender?: string;
  content?: string;
  weight?: number;
}

export interface V2Frame {
  roomId: string;
  emittedAt: string;
  balance: number;
  keywords?: string[];
  topicLabel?: string;
  mentalBuffer?: {
    emaPositive: number;
    emaNegative: number;
    rawPositive: number;
    rawNegative: number;
  };
  trustSummary?: {
    filteredCount?: number;
    trollCandidateCount?: number;
    fanCount?: number;
  };
  anchors?: AnchorChat[];
  briefing?: {
    summary?: string;
    confidence?: number;
  };
  stats?: {
    positiveAverage?: number;
    negativeAverage?: number;
  };
}

interface Props {
  roomId: string;
  ownerId: string;
  onFrame?: (frame: V2Frame) => void;
}

const EMPTY_FRAME: V2Frame = {
  roomId: "",
  emittedAt: "",
  balance: 0.5,
  keywords: [],
  topicLabel: "",
  mentalBuffer: {
    emaPositive: 0,
    emaNegative: 0,
    rawPositive: 0,
    rawNegative: 0,
  },
  trustSummary: {
    filteredCount: 0,
    trollCandidateCount: 0,
    fanCount: 0,
  },
  anchors: [],
  briefing: {
    summary: "",
    confidence: 0,
  },
  stats: {
    positiveAverage: 0,
    negativeAverage: 0,
  },
};

export default function V2InsightsPanel({ roomId, ownerId, onFrame }: Props) {
  const [frame, setFrame] = useState<V2Frame>(EMPTY_FRAME);
  const [connected, setConnected] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!roomId || !ownerId) return;

    let cancelled = false;

    const pushFrame = (nextFrame: V2Frame) => {
      setFrame(nextFrame);
      onFrame?.(nextFrame);
    };

    const fetchInitialState = async () => {
      try {
        const response = await fetch(`http://localhost:8083/api/v2/state/${roomId}`, {
          credentials: "include",
          headers: buildOwnerHeaders(ownerId),
        });
        if (!response.ok) return;

        const initialFrame = (await response.json()) as V2Frame;
        if (!cancelled && initialFrame?.roomId) {
          pushFrame(initialFrame);
        }
      } catch {
        // ignore and wait for SSE
      }
    };

    const connect = () => {
      const es = new EventSource(
        appendOwnerId(`http://localhost:8083/api/v2/stream/${roomId}`, ownerId),
        { withCredentials: true },
      );
      eventSourceRef.current = es;

      es.onopen = () => setConnected(true);
      es.onerror = () => {
        setConnected(false);
        es.close();
        setTimeout(connect, 5000);
      };

      es.addEventListener("v2_frame", (event) => {
        try {
          const nextFrame = JSON.parse(event.data) as V2Frame;
          pushFrame(nextFrame);
        } catch {
          // ignore malformed payloads
        }
      });
    };

    fetchInitialState();
    connect();

    return () => {
      cancelled = true;
      eventSourceRef.current?.close();
    };
  }, [roomId, onFrame]);

  return (
    <section className="mb-8 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-sm font-black uppercase tracking-[0.25em] text-slate-500">V2 Guardrail</h2>
          <p className="mt-2 text-sm text-slate-600">
            v2 insights for mental buffering, audience balance, and representative context.
          </p>
          {frame.topicLabel ? (
            <div className="mt-3 inline-flex items-center rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-[10px] font-black uppercase tracking-[0.2em] text-indigo-600">
              Topic {frame.topicLabel}
            </div>
          ) : null}
        </div>
        <div className="flex items-center gap-2 text-xs font-bold">
          <span className={`w-2.5 h-2.5 rounded-full ${connected ? "bg-emerald-500" : "bg-rose-500 animate-pulse"}`} />
          <span className={connected ? "text-emerald-600" : "text-rose-500"}>
            {connected ? "v2 stream connected" : "v2 reconnecting"}
          </span>
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-12 gap-6">
        <div className="xl:col-span-4 space-y-6">
          <AudienceBalanceCard
            balance={frame.balance}
            positiveAverage={frame.stats?.positiveAverage ?? 0}
            negativeAverage={frame.stats?.negativeAverage ?? 0}
          />
          <TrustFilterWidget
            filteredCount={frame.trustSummary?.filteredCount ?? 0}
            trollCandidateCount={frame.trustSummary?.trollCandidateCount ?? 0}
            fanCount={frame.trustSummary?.fanCount ?? 0}
          />
        </div>

        <div className="xl:col-span-4 space-y-6">
          <NarrativeBriefingCard
            summary={frame.briefing?.summary}
            confidence={frame.briefing?.confidence}
          />
          <MentalBufferBar
            emaPositive={frame.mentalBuffer?.emaPositive ?? 0}
            emaNegative={frame.mentalBuffer?.emaNegative ?? 0}
            rawPositive={frame.mentalBuffer?.rawPositive ?? 0}
            rawNegative={frame.mentalBuffer?.rawNegative ?? 0}
          />
        </div>

        <div className="xl:col-span-4">
          <AnchorChatPanel anchors={frame.anchors ?? []} />
        </div>
      </div>
    </section>
  );
}
