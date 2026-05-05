interface Props {
  open: boolean;
  items: string[];
  duplicateIndexes: number[];
  issues: string[];
  error?: string;
  canSave: boolean;
  isSaving: boolean;
  onChangeItem: (index: number, value: string) => void;
  onAddItem: () => void;
  onSave: () => void;
}

export default function PollComposer({
  open,
  items,
  duplicateIndexes,
  issues,
  error,
  canSave,
  isSaving,
  onChangeItem,
  onAddItem,
  onSave,
}: Props) {
  if (!open) {
    return null;
  }

  const filledCount = items.filter((item) => item.trim().length > 0).length;
  const feedback = error && !issues.includes(error) ? [error, ...issues] : issues;
  const showFeedback = Boolean(error) || duplicateIndexes.length > 0 || filledCount > 0;

  return (
    <div className="mb-5 space-y-4 rounded-[20px] border border-white/[0.08] bg-[#1A1A1A] p-4 transition-all duration-200">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="text-[11px] font-black uppercase tracking-[0.22em] text-white/40">투표 항목 편집</div>
          <div className="mt-2 text-sm font-bold text-white/70">입력된 항목 {filledCount}개 · 저장 시 최소 2개</div>
        </div>
        <div className="rounded-full border border-white/[0.08] bg-[#111111] px-3 py-1 text-[10px] font-black uppercase tracking-[0.18em] text-white/40">
          중복 없이 저장
        </div>
      </div>

      <div className="space-y-3">
        {items.map((item, index) => {
          const hasDuplicate = duplicateIndexes.includes(index);

          return (
            <div key={index} className="space-y-2">
              <div className="flex items-center justify-between gap-3 px-1">
                <div className="text-[10px] font-black uppercase tracking-[0.18em] text-white/40">항목 {index + 1}</div>
                {hasDuplicate ? (
                  <div className="text-[10px] font-black uppercase tracking-[0.16em] text-rose-400">중복 이름</div>
                ) : null}
              </div>
              <input
                value={item}
                onChange={(event) => onChangeItem(index, event.target.value)}
                className={`w-full rounded-2xl border bg-[#111111] px-4 py-3 text-sm text-white outline-none transition placeholder:text-white/25 focus:border-[#00FFA3]/40 ${
                  hasDuplicate ? "border-rose-500/40 bg-rose-500/10" : "border-white/[0.08]"
                }`}
                placeholder={`항목 ${index + 1}`}
              />
            </div>
          );
        })}
      </div>

      {showFeedback && feedback.length > 0 ? (
        <div className="rounded-[16px] border border-amber-500/25 bg-amber-500/10 px-4 py-3 text-sm text-amber-400">
          <div className="text-[11px] font-black uppercase tracking-[0.18em] text-amber-400/80">저장 전 확인</div>
          <ul className="mt-2 space-y-1 text-sm leading-6">
            {feedback.map((message) => (
              <li key={message}>• {message}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="rounded-[16px] border border-[#00FFA3]/15 bg-[#00FFA3]/[0.07] px-4 py-3 text-sm text-[#00FFA3]/80">
        <div className="text-[10px] font-black uppercase tracking-[0.18em] text-[#00FFA3]/60">투표 방법 안내</div>
        <p className="mt-1.5 leading-6">
          저장 후 시청자는 채팅에{" "}
          {items.slice(0, 3).map((item, i) => (
            <span key={i}>
              <span className="rounded bg-[#00FFA3]/15 px-1.5 py-0.5 font-black text-[#00FFA3]">!투표 {i + 1}</span>
              {item.trim() ? <span className="mx-1 text-[#00FFA3]/60">({item.trim()})</span> : null}
              {i < Math.min(items.length, 3) - 1 ? ", " : ""}
            </span>
          ))}
          {items.length > 3 ? <span className="text-[#00FFA3]/50"> 등</span> : null}
          {" "}형식으로 입력해 투표합니다.
        </p>
      </div>

      <div className="flex flex-wrap gap-3">
        <button
          onClick={onAddItem}
          disabled={isSaving}
          className="rounded-2xl border border-white/[0.08] bg-transparent px-4 py-2 text-sm font-black text-white/70 transition hover:bg-white/[0.06] hover:text-white disabled:cursor-not-allowed disabled:opacity-40"
        >
          항목 추가
        </button>
        <button
          onClick={onSave}
          disabled={!canSave || isSaving}
          className="rounded-2xl bg-[#00FFA3] px-4 py-2 text-sm font-black text-[#000000] transition hover:bg-[#00FFA3]/90 disabled:cursor-not-allowed disabled:bg-white/10 disabled:text-white/30"
        >
          {isSaving ? "저장 중..." : "저장"}
        </button>
      </div>
    </div>
  );
}
