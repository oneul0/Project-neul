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
    <div className="mb-5 space-y-4 rounded-[24px] border border-slate-200 bg-slate-50 p-4 transition-all duration-200">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">투표 항목 편집</div>
          <div className="mt-2 text-sm font-bold text-slate-700">입력된 항목 {filledCount}개 · 저장 시 최소 2개</div>
        </div>
        <div className="rounded-full border border-slate-200 bg-white px-3 py-1 text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">
          중복 없이 저장
        </div>
      </div>

      <div className="space-y-3">
        {items.map((item, index) => {
          const hasDuplicate = duplicateIndexes.includes(index);

          return (
            <div key={index} className="space-y-2">
              <div className="flex items-center justify-between gap-3 px-1">
                <div className="text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">항목 {index + 1}</div>
                {hasDuplicate ? (
                  <div className="text-[10px] font-black uppercase tracking-[0.16em] text-rose-500">중복 이름</div>
                ) : null}
              </div>
              <input
                value={item}
                onChange={(event) => onChangeItem(index, event.target.value)}
                className={`w-full rounded-2xl border bg-white px-4 py-3 text-sm text-slate-950 outline-none transition focus:border-sky-400/50 ${
                  hasDuplicate ? "border-rose-300 bg-rose-50/60" : "border-slate-200"
                }`}
                placeholder={`항목 ${index + 1}`}
              />
            </div>
          );
        })}
      </div>

      {showFeedback && feedback.length > 0 ? (
        <div className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          <div className="text-[11px] font-black uppercase tracking-[0.18em] text-amber-700">저장 전 확인</div>
          <ul className="mt-2 space-y-1 text-sm leading-6">
            {feedback.map((message) => (
              <li key={message}>• {message}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="flex flex-wrap gap-3">
        <button
          onClick={onAddItem}
          disabled={isSaving}
          className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700 transition hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-60"
        >
          항목 추가
        </button>
        <button
          onClick={onSave}
          disabled={!canSave || isSaving}
          className="rounded-2xl bg-sky-500 px-4 py-2 text-sm font-black text-slate-950 transition hover:bg-sky-400 disabled:cursor-not-allowed disabled:bg-slate-300 disabled:text-slate-500"
        >
          {isSaving ? "저장 중..." : "저장"}
        </button>
      </div>
    </div>
  );
}
