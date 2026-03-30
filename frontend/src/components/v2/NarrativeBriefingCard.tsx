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
          Agent Briefing
        </div>
        <div className="text-[10px] font-bold text-amber-500">
          {confidence != null ? `confidence ${(confidence * 100).toFixed(0)}%` : "live"}
        </div>
      </div>
      <p className="text-sm font-medium leading-7 text-slate-700 md:text-base">
        {summary || "Briefing is being generated from the latest stream context."}
      </p>
    </div>
  );
}
