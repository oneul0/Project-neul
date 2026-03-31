"use client";

interface NarrativeBriefingCardProps {
  summary?: string;
  confidence?: number;
}

export default function NarrativeBriefingCard({
  summary,
  confidence,
}: NarrativeBriefingCardProps) {
  return (
    <div className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm">
      <div className="mb-4 flex items-center justify-between">
        <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">
          한줄 브리핑
        </div>
        <div className="text-[10px] font-bold text-amber-500">
          {confidence != null ? `신뢰도 ${(confidence * 100).toFixed(0)}%` : "실시간"}
        </div>
      </div>
      <p className="text-sm font-medium leading-7 text-slate-700 md:text-base">
        {summary || "최신 채팅 흐름을 바탕으로 상황을 정리하고 있습니다."}
      </p>
    </div>
  );
}
