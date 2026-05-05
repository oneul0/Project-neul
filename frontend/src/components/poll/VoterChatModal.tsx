"use client";

import { useEffect, useRef } from "react";
import { MessageCircle, X } from "lucide-react";
import type { PollHistoryEntry } from "@/lib/poll/types";

const EMOTION_DOT: Record<string, string> = {
  POSITIVE: "bg-[#00FFA3]",
  NEGATIVE: "bg-rose-500",
  NEUTRAL: "bg-white/30",
};

function formatTime(iso?: string) {
  if (!iso) return null;
  const d = new Date(iso);
  return d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

interface Props {
  voterName: string;
  messages: PollHistoryEntry[];
  isLoading: boolean;
  error?: string;
  onClose: () => void;
}

export default function VoterChatModal({ voterName, messages, isLoading, error, onClose }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-end p-6 sm:p-8">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-[2px]" onClick={onClose} />

      <div className="relative flex h-[560px] w-full max-w-[360px] flex-col rounded-[24px] border border-white/[0.1] bg-[#111111] shadow-[0_32px_80px_rgba(0,0,0,0.6)]">
        {/* 헤더 */}
        <div className="flex shrink-0 items-center justify-between gap-3 border-b border-white/[0.06] px-5 py-4">
          <div className="min-w-0">
            <div className="text-[10px] font-black uppercase tracking-[0.2em] text-white/35">채팅 기록</div>
            <div className="truncate text-sm font-black text-white">{voterName}</div>
          </div>
          <button
            onClick={onClose}
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border border-white/[0.08] text-white/40 transition hover:bg-white/[0.08] hover:text-white"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* 메시지 영역 */}
        <div className="flex-1 overflow-y-auto px-4 py-4">
          {isLoading ? (
            <div className="flex h-full items-center justify-center text-sm text-white/40">
              불러오는 중...
            </div>
          ) : error ? (
            <div className="flex h-full items-center justify-center text-sm text-rose-400">
              {error}
            </div>
          ) : messages.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center gap-2 text-white/30">
              <MessageCircle className="h-8 w-8 text-white/10" />
              <span className="text-sm">채팅 기록이 없습니다.</span>
            </div>
          ) : (
            <div className="space-y-2">
              {messages.map((msg) => {
                const dotClass = EMOTION_DOT[msg.emotionType] ?? "bg-white/20";
                const time = formatTime(msg.analyzedAt);
                return (
                  <div key={msg.messageId} className="flex flex-col items-start gap-0.5">
                    <div className="flex items-start gap-2">
                      <span className={`mt-[9px] h-1.5 w-1.5 shrink-0 rounded-full ${dotClass}`} />
                      <div className="rounded-2xl rounded-tl-sm bg-[#1A1A1A] px-3.5 py-2.5 text-sm leading-5 text-white/90">
                        {msg.content || <span className="italic text-white/30">(빈 메시지)</span>}
                      </div>
                    </div>
                    {time && (
                      <span className="pl-5 text-[10px] text-white/30">{time}</span>
                    )}
                  </div>
                );
              })}
              <div ref={bottomRef} />
            </div>
          )}
        </div>

        {/* 하단 범례 */}
        <div className="shrink-0 border-t border-white/[0.06] px-5 py-3">
          <div className="flex items-center justify-center gap-3 text-[10px] text-white/25">
            <span className="flex items-center gap-1"><span className="h-1.5 w-1.5 rounded-full bg-[#00FFA3]" />긍정</span>
            <span className="flex items-center gap-1"><span className="h-1.5 w-1.5 rounded-full bg-white/25" />중립</span>
            <span className="flex items-center gap-1"><span className="h-1.5 w-1.5 rounded-full bg-rose-500" />부정</span>
          </div>
        </div>
      </div>
    </div>
  );
}
