"use client";

import { useEffect, useRef, useState } from "react";
import { Gift, RotateCcw, Settings, Sparkles, Trash2, X } from "lucide-react";
import type { RouletteItem, RouletteResultData, RouletteStateData } from "@/hooks/useDonationRoulette";

// ─── Props ───────────────────────────────────────────────────────────────────

interface RouletteCardProps {
  state: RouletteStateData | null;
  result: RouletteResultData | null;
  isSpinning: boolean;
  isResetting: boolean;
  isOwner: boolean;
  onSpin: () => void;
  onSetConfig: (items: string[], rate: number) => Promise<void>;
  onResetWeights: () => void;
  onClearAll: () => Promise<void>;
}

// ─── 확률 바 ──────────────────────────────────────────────────────────────────

function ProbabilityBar({ item, isWinner, baseProbability }: { item: RouletteItem; isWinner: boolean; baseProbability: number }) {
  const pct = (item.probability * 100).toFixed(1);
  const boostedPct = ((item.probability - baseProbability) * 100).toFixed(1);
  const isBoosted = item.probability - baseProbability > 0.0005;
  return (
    <div
      className={`rounded-[18px] border px-4 py-3 transition ${
        isWinner
          ? "border-[#00FFA3]/30 bg-[#00FFA3]/10"
          : "border-white/[0.06] bg-[#242426]"
      }`}
    >
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <span className={`text-sm font-black ${isWinner ? "text-[#00FFA3]" : "text-white/90"}`}>
          {isWinner && <Sparkles className="mr-1 inline h-3.5 w-3.5 text-[#00FFA3]" />}
          {item.name}
        </span>
        <span className={`text-xs font-black tabular-nums ${isWinner ? "text-[#00FFA3]" : "text-white/50"}`}>
          {pct}%
        </span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-white/[0.08]">
        <div
          className={`h-full rounded-full transition-all duration-500 ${isWinner ? "bg-[#00FFA3]" : "bg-white/30"}`}
          style={{ width: `${item.probability * 100}%` }}
        />
      </div>
      {isBoosted && (
        <div className="mt-1 text-right text-[10px] text-[#00FFA3]/70">
          +{boostedPct}% 도네이션으로 상승
        </div>
      )}
    </div>
  );
}

// ─── 설정 패널 ────────────────────────────────────────────────────────────────

interface ConfigPanelProps {
  initialItems: string[];
  initialRate: number;
  hasExistingConfig: boolean;
  onSave: (items: string[], rate: number) => Promise<void>;
  onClearAll: () => Promise<void>;
  onClose: () => void;
}

function ConfigPanel({ initialItems, initialRate, hasExistingConfig, onSave, onClearAll, onClose }: ConfigPanelProps) {
  const [items, setItems] = useState<string[]>(initialItems.length > 0 ? initialItems : [""]);
  const [rate, setRate] = useState(initialRate > 0 ? initialRate : 1000);
  const [saving, setSaving] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [confirmClear, setConfirmClear] = useState(false);

  const addItem = () => setItems((prev) => [...prev, ""]);
  const removeItem = (i: number) => setItems((prev) => prev.filter((_, idx) => idx !== i));
  const updateItem = (i: number, val: string) =>
    setItems((prev) => prev.map((v, idx) => (idx === i ? val : v)));

  const handleSave = async () => {
    const cleaned = items.map((s) => s.trim()).filter(Boolean);
    if (cleaned.length === 0) return;
    setSaving(true);
    try {
      await onSave(cleaned, rate);
      onClose();
    } finally {
      setSaving(false);
    }
  };

  const handleClearAll = async () => {
    if (!confirmClear) { setConfirmClear(true); return; }
    setClearing(true);
    try {
      await onClearAll();
      onClose();
    } finally {
      setClearing(false);
      setConfirmClear(false);
    }
  };

  return (
    <div className="mt-4 rounded-[18px] border border-[#00FFA3]/15 bg-[#00FFA3]/[0.06] p-4">
      <div className="mb-3 flex items-center justify-between">
        <span className="text-[11px] font-black uppercase tracking-[0.2em] text-[#00FFA3]/70">
          항목 및 배율 설정
        </span>
        <button onClick={onClose} className="text-white/30 transition hover:text-white">
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* 항목 편집 */}
      <div className="mb-3 space-y-1.5">
        {items.map((item, i) => (
          <div key={i} className="flex items-center gap-2">
            <input
              value={item}
              onChange={(e) => updateItem(i, e.target.value)}
              placeholder={`항목 ${i + 1}`}
              className="flex-1 rounded-xl border border-white/[0.08] bg-[#1A1A1C] px-3 py-2 text-sm font-semibold text-white outline-none placeholder:text-white/25 focus:border-[#00FFA3]/40"
            />
            {items.length > 1 && (
              <button onClick={() => removeItem(i)} className="text-rose-400/60 transition hover:text-rose-400">
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
        ))}
        <button
          onClick={addItem}
          className="w-full rounded-xl border border-dashed border-[#00FFA3]/20 py-1.5 text-xs font-black text-[#00FFA3]/60 transition hover:border-[#00FFA3]/40 hover:bg-[#00FFA3]/[0.05] hover:text-[#00FFA3]"
        >
          + 항목 추가
        </button>
      </div>

      {/* 배율 설정 */}
      <div className="mb-4 flex items-center gap-3 rounded-xl border border-white/[0.08] bg-[#1A1A1C] px-3 py-2">
        <span className="text-xs font-black text-white/40">배율</span>
        <input
          type="number"
          min={1}
          value={rate}
          onChange={(e) => setRate(Number(e.target.value))}
          className="w-28 rounded-lg border border-white/[0.08] bg-[#242426] px-2 py-1 text-right text-sm font-bold text-white outline-none focus:border-[#00FFA3]/40"
        />
        <span className="text-xs text-white/40">원당 확률 상승</span>
      </div>

      {/* 저장 버튼 */}
      <button
        onClick={() => void handleSave()}
        disabled={saving}
        className="w-full rounded-2xl bg-[#00FFA3] py-2.5 text-sm font-black text-[#0D0D0E] transition hover:bg-[#00FFA3]/90 disabled:opacity-50"
      >
        {saving ? "저장 중..." : "저장"}
      </button>

      {/* 항목 전체 삭제 */}
      {hasExistingConfig && (
        <button
          onClick={() => void handleClearAll()}
          disabled={clearing}
          className={`mt-2 w-full rounded-2xl border py-2.5 text-xs font-black transition ${
            confirmClear
              ? "border-rose-500/40 bg-rose-500 text-white hover:bg-rose-400"
              : "border-rose-500/20 bg-transparent text-rose-400/80 hover:bg-rose-500/10 hover:text-rose-400"
          } disabled:opacity-50`}
        >
          <Trash2 className="mr-1.5 inline h-3.5 w-3.5" />
          {clearing ? "삭제 중..." : confirmClear ? "정말 삭제하시겠습니까?" : "항목 전체 삭제"}
        </button>
      )}
      {confirmClear && (
        <p className="mt-1 text-center text-[10px] text-rose-400/60">
          한 번 더 누르면 항목·배율·도네이션 확률이 모두 삭제됩니다.
        </p>
      )}
    </div>
  );
}

// ─── 메인 카드 ────────────────────────────────────────────────────────────────

export default function RouletteCard({
  state,
  result,
  isSpinning,
  isResetting,
  isOwner,
  onSpin,
  onSetConfig,
  onResetWeights,
  onClearAll,
}: RouletteCardProps) {
  const [configOpen, setConfigOpen] = useState(false);
  const prevWinnerRef = useRef<string | null>(null);

  useEffect(() => {
    if (result && result.winner !== prevWinnerRef.current) {
      prevWinnerRef.current = result.winner;
      setConfigOpen(false);
    }
  }, [result]);

  const hasItems = (state?.items.length ?? 0) > 0;

  return (
    <div className="rounded-[28px] border border-white/[0.08] bg-[#1A1A1C] p-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-[#00FFA3]/10">
            <Gift className="h-5 w-5 text-[#00FFA3]" />
          </div>
          <div>
            <div className="text-[11px] font-black uppercase tracking-[0.22em] text-white/40">
              도네이션 룰렛
            </div>
            <div className="text-sm font-bold text-white/70">
              {hasItems
                ? `${state!.items.length}개 항목 · ${state!.rate.toLocaleString()}원당 확률 상승`
                : "항목 미설정"}
            </div>
          </div>
        </div>

        {isOwner && (
          <div className="flex items-center gap-2">
            {/* 확률 초기화 */}
            {hasItems && (
              <button
                onClick={onResetWeights}
                disabled={isResetting}
                title="도네이션으로 올라간 확률만 초기화 (항목은 유지됩니다)"
                className="inline-flex items-center gap-1.5 rounded-2xl border border-white/[0.08] bg-transparent px-3 py-2.5 text-xs font-black text-white/50 transition hover:bg-white/[0.06] hover:text-white disabled:opacity-40"
              >
                <RotateCcw className="h-3.5 w-3.5" />
                {isResetting ? "초기화 중..." : "확률 초기화"}
              </button>
            )}
            {/* 설정 토글 */}
            <button
              onClick={() => setConfigOpen((v) => !v)}
              className={`inline-flex items-center gap-1.5 rounded-2xl border px-3 py-2.5 text-xs font-black transition ${
                configOpen
                  ? "border-[#00FFA3]/25 bg-[#00FFA3]/10 text-[#00FFA3]"
                  : "border-white/[0.08] bg-transparent text-white/50 hover:bg-white/[0.06] hover:text-white"
              }`}
            >
              <Settings className="h-3.5 w-3.5" />
              설정
            </button>
            {/* 스핀 */}
            <button
              onClick={onSpin}
              disabled={!hasItems || isSpinning}
              className={`inline-flex items-center gap-2 rounded-2xl px-4 py-2.5 text-sm font-black transition ${
                !hasItems || isSpinning
                  ? "cursor-not-allowed bg-white/[0.06] text-white/25"
                  : "bg-[#00FFA3] text-[#0D0D0E] hover:bg-[#00FFA3]/90 shadow-[0_0_16px_rgba(0,255,163,0.25)]"
              }`}
            >
              <Sparkles className="h-4 w-4" />
              {isSpinning ? "추첨 중..." : "돌리기"}
            </button>
          </div>
        )}
      </div>

      {/* 설정 패널 */}
      {isOwner && configOpen && (
        <ConfigPanel
          initialItems={state?.items.map((i) => i.name) ?? []}
          initialRate={state?.rate ?? 1000}
          hasExistingConfig={hasItems}
          onSave={onSetConfig}
          onClearAll={onClearAll}
          onClose={() => setConfigOpen(false)}
        />
      )}

      {/* 당첨 결과 */}
      {result && (
        <div className="mt-5 rounded-[18px] border border-[#00FFA3]/25 bg-[#00FFA3]/10 px-5 py-4">
          <div className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-[#00FFA3]" />
            <span className="text-[11px] font-black tracking-[0.2em] text-[#00FFA3]/70">당첨</span>
          </div>
          <div className="mt-1.5 text-2xl font-black text-white">{result.winner}</div>
          <div className="mt-0.5 text-sm font-semibold text-[#00FFA3]/70">
            추첨 확률 {(result.probability * 100).toFixed(1)}%
          </div>
        </div>
      )}

      {/* 항목 확률 바 */}
      {hasItems ? (
        <div className="mt-4 space-y-2">
          {(() => {
            const baseProbability = 1 / state!.items.length;
            return state!.items.map((item) => (
              <ProbabilityBar
                key={item.name}
                item={item}
                isWinner={result?.winner === item.name}
                baseProbability={baseProbability}
              />
            ));
          })()}
        </div>
      ) : (
        !configOpen && (
          <div className="mt-4 rounded-[18px] border border-dashed border-white/[0.08] bg-[#242426] py-8 text-center">
            <Gift className="mx-auto h-6 w-6 text-white/20" />
            <p className="mt-2 text-sm font-semibold text-white/40">
              {isOwner
                ? "설정 버튼을 눌러 항목과 배율을 지정하세요."
                : "스트리머가 항목을 설정하면 확률이 표시됩니다."}
            </p>
            <p className="mt-1 text-xs text-white/25">
              도네이션 메시지에 항목 이름을 입력하면 확률이 올라갑니다.
            </p>
          </div>
        )
      )}
    </div>
  );
}
