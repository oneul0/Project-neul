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

function CompactInfoDisclosure({
  label,
  summary,
}: {
  label: string;
  summary: string;
}) {
  return (
    <details className="group relative">
      <summary
        aria-label={label}
        className="flex cursor-pointer list-none items-center justify-center rounded-full border border-slate-200 bg-white p-0 text-[11px] font-black text-slate-500 transition hover:border-slate-300 hover:text-slate-700 [&::-webkit-details-marker]:hidden"
      >
        <span className="inline-flex h-6 w-6 items-center justify-center">?</span>
      </summary>
      <div className="absolute right-0 top-full z-10 mt-3 w-72 max-w-[calc(100vw-4rem)] rounded-2xl border border-slate-200 bg-white px-3 py-3 text-xs leading-5 text-slate-600 shadow-[0_18px_50px_rgba(15,23,42,0.12)]">
        {summary}
      </div>
    </details>
  );
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
  const [hasReceivedFrame, setHasReceivedFrame] = useState(false);
  const [hasOpenedStream, setHasOpenedStream] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!roomId || !ownerId) return;

    let cancelled = false;

    setFrame(EMPTY_FRAME);
    setConnected(false);
    setHasReceivedFrame(false);
    setHasOpenedStream(false);

    const pushFrame = (nextFrame: V2Frame) => {
      setHasReceivedFrame(true);
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

      es.onopen = () => {
        setConnected(true);
        setHasOpenedStream(true);
      };
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
  }, [ownerId, roomId, onFrame]);

  const hasFrame = hasReceivedFrame || Boolean(frame.roomId);

  const connectionState = connected
    ? hasFrame
      ? {
          label: "실시간 반영 중",
          summary: "카드가 바뀌는 순간이 지금 분위기입니다.",
          toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
          dotClass: "bg-emerald-500",
          labelClass: "text-emerald-700",
        }
      : {
          label: "첫 프레임 대기 중",
          summary: "첫 요약이 들어오면 아래 카드가 바로 채워집니다.",
          toneClass: "border-sky-200 bg-sky-50 text-sky-900",
          dotClass: "bg-sky-500 animate-pulse",
          labelClass: "text-sky-700",
        }
    : hasOpenedStream
      ? {
          label: "재연결 중",
          summary: "다시 붙는 즉시 최신 프레임으로 맞춰집니다.",
          toneClass: "border-rose-200 bg-rose-50 text-rose-900",
          dotClass: "bg-rose-500 animate-pulse",
          labelClass: "text-rose-700",
        }
      : {
          label: "연결 준비 중",
          summary: "스트림을 열고 첫 상태를 불러오는 중입니다.",
          toneClass: "border-slate-200 bg-slate-50 text-slate-900",
          dotClass: "bg-slate-400 animate-pulse",
          labelClass: "text-slate-700",
        };

  return (
    <section className="mb-8 space-y-4">
      <div className="flex flex-col gap-3 xl:flex-row xl:items-start xl:justify-between">
        <div>
          <h2 className="text-sm font-black tracking-[0.25em] text-slate-500">심리 가드레일</h2>
          <p className="mt-2 text-sm text-slate-600">핵심 신호만 먼저 읽을 수 있게 묶었습니다.</p>
          {frame.topicLabel ? (
            <div className="mt-3 inline-flex items-center rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-[10px] font-black tracking-[0.2em] text-indigo-600">
              현재 주제 {frame.topicLabel}
            </div>
            ) : null}
        </div>
        <div className="flex items-start gap-2 self-start">
          <div className={`rounded-2xl border px-4 py-3 ${connectionState.toneClass}`}>
            <div className="flex items-center gap-2 text-[11px] font-black tracking-[0.18em]">
              <span className={`h-2.5 w-2.5 rounded-full ${connectionState.dotClass}`} />
              <span className={connectionState.labelClass}>{connectionState.label}</span>
            </div>
          </div>
          <CompactInfoDisclosure label="가드레일 연결 상태 설명" summary={connectionState.summary} />
        </div>
      </div>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-12">
        <div className="space-y-5 xl:col-span-4">
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

        <div className="space-y-5 xl:col-span-4">
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
