"use client";

interface AudienceBalanceCardProps {
  balance: number;
  positiveAverage: number;
  negativeAverage: number;
}

export default function AudienceBalanceCard({
  balance,
  positiveAverage,
  negativeAverage,
}: AudienceBalanceCardProps) {
  const balancePercent = Math.round(balance * 100);
  const positiveWidth = `${Math.max(positiveAverage * 100, 4)}%`;
  const negativeWidth = `${Math.max(negativeAverage * 100, 4)}%`;

  return (
    <div className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-center justify-between mb-5">
        <div>
          <div className="text-[10px] font-black tracking-[0.2em] text-slate-500">민심 밸런스</div>
          <div className="mt-1 text-3xl font-black text-slate-950">{balancePercent}%</div>
        </div>
        <div className="text-right">
          <div className="text-xs font-bold text-emerald-400">긍정 {(positiveAverage * 100).toFixed(0)}%</div>
          <div className="text-xs font-bold text-rose-400">부정 {(negativeAverage * 100).toFixed(0)}%</div>
        </div>
      </div>

      <div className="h-4 rounded-full overflow-hidden border border-slate-200 bg-slate-100">
        <div className="h-full flex">
          <div className="bg-emerald-500" style={{ width: positiveWidth }} />
          <div className="flex-1 bg-slate-200" />
          <div className="bg-rose-500" style={{ width: negativeWidth }} />
        </div>
      </div>
    </div>
  );
}
