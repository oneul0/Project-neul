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
      className={`rounded-2xl border px-4 py-3 transition ${
        isWinner ? "border-violet-300 bg-violet-50" : "border-slate-100 bg-slate-50"
      }`}
    >
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <span className={`text-sm font-black ${isWinner ? "text-violet-900" : "text-slate-800"}`}>
          {isWinner && <Sparkles className="mr-1 inline h-3.5 w-3.5 text-violet-500" />}
          {item.name}
        </span>
        <span className={`text-xs font-black tabular-nums ${isWinner ? "text-violet-600" : "text-slate-500"}`}>
          {pct}%
        </span>
      </div>
      <div className="h-2 w-full overflow-hidden rounded-full bg-slate-200">
        <div
          className={`h-full rounded-full transition-all duration-500 ${isWinner ? "bg-violet-500" : "bg-slate-400"}`}
          style={{ width: `${item.probability * 100}%` }}
        />
      </div>
      {isBoosted && (
        <div className="mt-1 text-right text-[10px] text-emerald-500">
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
    <div className="mt-4 rounded-2xl border border-sky-200 bg-sky-50 p-4">
      <div className="mb-3 flex items-center justify-between">
        <span className="text-[11px] font-black uppercase tracking-[0.2em] text-sky-600">
          항목 및 배율 설정
        </span>
        <button onClick={onClose} className="text-slate-400 hover:text-slate-600">
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
              className="flex-1 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm font-semibold text-slate-800 outline-none focus:border-sky-400"
            />
            {items.length > 1 && (
              <button onClick={() => removeItem(i)} className="text-rose-400 hover:text-rose-600">
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
        ))}
        <button
          onClick={addItem}
          className="w-full rounded-xl border border-dashed border-sky-300 py-1.5 text-xs font-black text-sky-500 hover:bg-sky-100"
        >
          + 항목 추가
        </button>
      </div>

      {/* 배율 설정 */}
      <div className="mb-4 flex items-center gap-3 rounded-xl border border-slate-200 bg-white px-3 py-2">
        <span className="text-xs font-black text-slate-500">배율</span>
        <input
          type="number"
          min={1}
          value={rate}
          onChange={(e) => setRate(Number(e.target.value))}
          className="w-28 rounded-lg border border-slate-200 px-2 py-1 text-right text-sm font-bold text-slate-800 outline-none focus:border-sky-400"
        />
        <span className="text-xs text-slate-500">원당 확률 상승</span>
      </div>

      {/* 저장 버튼 */}
      <button
        onClick={() => void handleSave()}
        disabled={saving}
        className="w-full rounded-2xl bg-sky-500 py-2.5 text-sm font-black text-white transition hover:bg-sky-400 disabled:opacity-50"
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
              ? "border-rose-300 bg-rose-500 text-white hover:bg-rose-400"
              : "border-rose-200 bg-white text-rose-500 hover:bg-rose-50"
          } disabled:opacity-50`}
        >
          <Trash2 className="mr-1.5 inline h-3.5 w-3.5" />
          {clearing ? "삭제 중..." : confirmClear ? "정말 삭제하시겠습니까?" : "항목 전체 삭제"}
        </button>
      )}
      {confirmClear && (
        <p className="mt-1 text-center text-[10px] text-rose-400">
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
    <div className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-[0_8px_32px_rgba(15,23,42,0.06)]">
      {/* 헤더 */}
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
              {hasItems
                ? `${state!.items.length}개 항목 · ${state!.rate.toLocaleString()}원당 확률 상승`
                : "항목 미설정"}
            </div>
          </div>
        </div>

        {isOwner && (
          <div className="flex items-center gap-2">
            {/* 가중치만 초기화 (항목 유지) */}
            {hasItems && (
              <button
                onClick={onResetWeights}
                disabled={isResetting}
                title="도네이션으로 올라간 확률만 초기화 (항목은 유지됩니다)"
                className="inline-flex items-center gap-1.5 rounded-2xl border border-slate-200 px-3 py-2.5 text-xs font-black text-slate-500 transition hover:bg-slate-50 disabled:opacity-40"
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
                  ? "border-sky-200 bg-sky-50 text-sky-600"
                  : "border-slate-200 bg-white text-slate-500 hover:bg-slate-50"
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
                  ? "cursor-not-allowed bg-slate-100 text-slate-400"
                  : "bg-violet-500 text-white hover:bg-violet-400"
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
        <div className="mt-5 rounded-2xl border border-violet-200 bg-violet-50 px-5 py-4">
          <div className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-violet-500" />
            <span className="text-[11px] font-black tracking-[0.2em] text-violet-500">당첨</span>
          </div>
          <div className="mt-1.5 text-2xl font-black text-violet-950">{result.winner}</div>
          <div className="mt-0.5 text-sm font-semibold text-violet-600">
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
          <div className="mt-4 rounded-xl bg-slate-50 py-8 text-center">
            <Gift className="mx-auto h-6 w-6 text-slate-300" />
            <p className="mt-2 text-sm font-semibold text-slate-400">
              {isOwner
                ? "설정 버튼을 눌러 항목과 배율을 지정하세요."
                : "스트리머가 항목을 설정하면 확률이 표시됩니다."}
            </p>
            <p className="mt-1 text-xs text-slate-300">
              도네이션 메시지에 항목 이름을 입력하면 확률이 올라갑니다.
            </p>
          </div>
        )
      )}
    </div>
  );
}
