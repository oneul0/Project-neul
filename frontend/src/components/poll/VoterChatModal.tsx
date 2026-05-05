"use client";

import { useEffect, useRef } from "react";
import { MessageCircle, X } from "lucide-react";
import type { PollHistoryEntry } from "@/lib/poll/types";

const EMOTION_DOT: Record<string, string> = {
  POSITIVE: "bg-emerald-400",
  NEGATIVE: "bg-rose-400",
  NEUTRAL: "bg-slate-300",
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
      <div className="absolute inset-0 bg-slate-950/30 backdrop-blur-[2px]" onClick={onClose} />

      <div className="relative flex h-[560px] w-full max-w-[360px] flex-col rounded-[28px] border border-slate-200 bg-white shadow-[0_32px_80px_rgba(15,23,42,0.22)]">
        {/* 헤더 */}
        <div className="flex shrink-0 items-center justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div className="min-w-0">
            <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-400">채팅 기록</div>
            <div className="truncate text-sm font-black text-slate-900">{voterName}</div>
          </div>
          <button
            onClick={onClose}
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border border-slate-200 text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* 메시지 영역 */}
        <div className="flex-1 overflow-y-auto px-4 py-4">
          {isLoading ? (
            <div className="flex h-full items-center justify-center text-sm text-slate-400">
              불러오는 중...
            </div>
          ) : error ? (
            <div className="flex h-full items-center justify-center text-sm text-rose-500">
              {error}
            </div>
          ) : messages.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center gap-2 text-slate-400">
              <MessageCircle className="h-8 w-8 text-slate-200" />
              <span className="text-sm">채팅 기록이 없습니다.</span>
            </div>
          ) : (
            <div className="space-y-2">
              {messages.map((msg) => {
                const dotClass = EMOTION_DOT[msg.emotionType] ?? "bg-slate-300";
                const time = formatTime(msg.analyzedAt);
                return (
                  <div key={msg.messageId} className="flex flex-col items-start gap-0.5">
                    <div className="flex items-start gap-2">
                      <span className={`mt-2 h-2 w-2 shrink-0 rounded-full ${dotClass}`} />
                      <div className="rounded-2xl rounded-tl-sm bg-slate-100 px-3.5 py-2.5 text-sm leading-5 text-slate-800">
                        {msg.content || <span className="italic text-slate-400">(빈 메시지)</span>}
                      </div>
                    </div>
                    {time && (
                      <span className="pl-6 text-[10px] text-slate-400">{time}</span>
                    )}
                  </div>
                );
              })}
              <div ref={bottomRef} />
            </div>
          )}
        </div>

        {/* 하단 안내 */}
        <div className="shrink-0 border-t border-slate-100 px-5 py-3 text-center text-[10px] text-slate-400">
          읽기 전용 · 감정 ● 긍정 ● 중립 ● 부정
        </div>
      </div>
    </div>
  );
}
