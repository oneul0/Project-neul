"use client";

interface TrustFilterWidgetProps {
  filteredCount: number;
  trollCandidateCount: number;
  fanCount: number;
}

export default function TrustFilterWidget({
  filteredCount,
  trollCandidateCount,
  fanCount,
}: TrustFilterWidgetProps) {
  return (
    <div className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm">
      <div className="text-[10px] font-black tracking-[0.2em] text-slate-500 mb-5">시청자 신뢰도</div>
      <div className="grid grid-cols-3 gap-3">
        <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
          <div className="text-[10px] font-black tracking-wider text-slate-500">격리됨</div>
          <div className="text-2xl font-black text-rose-400 mt-2">{filteredCount}</div>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
          <div className="text-[10px] font-black tracking-wider text-slate-500">주의 대상</div>
          <div className="text-2xl font-black text-amber-300 mt-2">{trollCandidateCount}</div>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
          <div className="text-[10px] font-black tracking-wider text-slate-500">핵심 시청자</div>
          <div className="text-2xl font-black text-emerald-400 mt-2">{fanCount}</div>
        </div>
      </div>
    </div>
  );
}
