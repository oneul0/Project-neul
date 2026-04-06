interface Props {
  open: boolean;
  items: string[];
  onChangeItem: (index: number, value: string) => void;
  onAddItem: () => void;
  onSave: () => void;
}

export default function PollComposer({ open, items, onChangeItem, onAddItem, onSave }: Props) {
  if (!open) {
    return null;
  }

  return (
    <div className="mb-5 space-y-3 rounded-[24px] border border-slate-200 bg-slate-50 p-4">
      {items.map((item, index) => (
        <input
          key={`${index}-${item}`}
          value={item}
          onChange={(event) => onChangeItem(index, event.target.value)}
          className="w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-950 outline-none transition focus:border-sky-400/40"
          placeholder={`항목 ${index + 1}`}
        />
      ))}
      <div className="flex flex-wrap gap-3">
        <button
          onClick={onAddItem}
          className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700"
        >
          항목 추가
        </button>
        <button onClick={onSave} className="rounded-2xl bg-sky-500 px-4 py-2 text-sm font-black text-slate-950">
          저장
        </button>
      </div>
    </div>
  );
}
