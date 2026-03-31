"use client";

interface MentalBufferBarProps {
  emaPositive: number;
  emaNegative: number;
  rawPositive: number;
  rawNegative: number;
}

export default function MentalBufferBar({
  emaPositive,
  emaNegative,
  rawPositive,
  rawNegative,
}: MentalBufferBarProps) {
  return (
    <div className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-center justify-between mb-5">
        <div>
          <div className="text-[10px] font-black tracking-[0.2em] text-slate-500">멘탈 버퍼</div>
          <div className="mt-1 text-sm font-bold text-slate-600">급격한 반응 변화를 완만하게 보여주는 보정 지표</div>
        </div>
      </div>

      <div className="space-y-4">
        <div>
          <div className="flex justify-between text-xs font-bold mb-2">
            <span className="text-emerald-400">보정된 긍정</span>
            <span className="text-slate-500">{(emaPositive * 100).toFixed(0)}%</span>
          </div>
          <div className="h-3 overflow-hidden rounded-full bg-slate-100">
            <div className="h-full bg-emerald-500 transition-all duration-500" style={{ width: `${Math.max(emaPositive * 100, 2)}%` }} />
          </div>
        </div>

        <div>
          <div className="flex justify-between text-xs font-bold mb-2">
            <span className="text-rose-400">보정된 부정</span>
            <span className="text-slate-500">{(emaNegative * 100).toFixed(0)}%</span>
          </div>
          <div className="h-3 overflow-hidden rounded-full bg-slate-100">
            <div className="h-full bg-rose-500 transition-all duration-500" style={{ width: `${Math.max(emaNegative * 100, 2)}%` }} />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3 pt-2">
          <div className="rounded-2xl border border-slate-200 bg-slate-50 p-3">
            <div className="text-[10px] font-black tracking-wider text-slate-500">실제 긍정</div>
            <div className="mt-1 text-lg font-black text-slate-950">{(rawPositive * 100).toFixed(0)}%</div>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-slate-50 p-3">
            <div className="text-[10px] font-black tracking-wider text-slate-500">실제 부정</div>
            <div className="mt-1 text-lg font-black text-slate-950">{(rawNegative * 100).toFixed(0)}%</div>
          </div>
        </div>
      </div>
    </div>
  );
}
