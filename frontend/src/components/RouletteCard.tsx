"use client";

import { Gift, RotateCcw, Sparkles, Trash2 } from "lucide-react";
import type { DonationEntry } from "@/hooks/useDonationRoulette";

interface RouletteCardProps {
  donations: DonationEntry[];
  winner: DonationEntry | null;
  isSpinning: boolean;
  isClearing: boolean;
  onSpin: () => void;
  onClear: () => void;
  isOwner: boolean;
}

export default function RouletteCard({
  donations,
  winner,
  isSpinning,
  isClearing,
  onSpin,
  onClear,
  isOwner,
}: RouletteCardProps) {
  const hasEntries = donations.length > 0;

  return (
    <div className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-[0_8px_32px_rgba(15,23,42,0.06)]">
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-violet-50">
            <Gift className="h-5 w-5 text-violet-500" />
          </div>
          <div>
            <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-400">
              도네이션 룰렛
            </div>
            <div className="text-sm font-bold text-slate-700">
              {hasEntries ? `${donations.length}명 대기 중` : "후원자 없음"}
            </div>
          </div>
        </div>

        {isOwner && (
          <div className="flex items-center gap-2">
            <button
              onClick={onSpin}
              disabled={!hasEntries || isSpinning}
              className={`inline-flex items-center gap-2 rounded-2xl px-4 py-2.5 text-sm font-black transition ${
                !hasEntries || isSpinning
                  ? "cursor-not-allowed bg-slate-100 text-slate-400"
                  : "bg-violet-500 text-white hover:bg-violet-400"
              }`}
            >
              <Sparkles className="h-4 w-4" />
              {isSpinning ? "추첨 중..." : "추첨"}
            </button>
            <button
              onClick={onClear}
              disabled={!hasEntries || isClearing}
              className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 px-3 py-2.5 text-sm font-bold text-slate-500 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Trash2 className="h-4 w-4" />
            </button>
          </div>
        )}
      </div>

      {winner && (
        <div className="mt-5 rounded-2xl border border-violet-200 bg-violet-50 px-5 py-4">
          <div className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-violet-500" />
            <span className="text-[11px] font-black tracking-[0.2em] text-violet-500">당첨</span>
          </div>
          <div className="mt-1.5 text-xl font-black text-violet-950">{winner.donorNickname}</div>
          {winner.amount && (
            <div className="mt-0.5 text-sm font-semibold text-violet-600">{winner.amount}원</div>
          )}
          {winner.message && (
            <div className="mt-1 text-sm text-slate-600">&ldquo;{winner.message}&rdquo;</div>
          )}
        </div>
      )}

      {hasEntries && (
        <div className="mt-4 space-y-1.5 max-h-48 overflow-y-auto">
          {donations.map((d) => (
            <div
              key={d.messageId}
              className={`flex items-center justify-between rounded-xl px-3 py-2 text-sm transition ${
                winner?.messageId === d.messageId
                  ? "bg-violet-100 font-bold text-violet-900"
                  : "bg-slate-50 text-slate-700"
              }`}
            >
              <span className="font-semibold">{d.donorNickname}</span>
              <span className="flex items-center gap-2 text-slate-400">
                {d.amount && (
                  <span className="font-bold text-slate-600">{d.amount}원</span>
                )}
                {d.message && (
                  <span className="max-w-[140px] truncate">{d.message}</span>
                )}
              </span>
            </div>
          ))}
        </div>
      )}

      {!hasEntries && (
        <div className="mt-4 rounded-xl bg-slate-50 py-6 text-center">
          <RotateCcw className="mx-auto h-6 w-6 text-slate-300" />
          <p className="mt-2 text-sm text-slate-400">채팅 수집 중 도네이션이 감지되면 자동으로 추가됩니다.</p>
        </div>
      )}
    </div>
  );
}
