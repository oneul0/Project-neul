import type { PollMode, ResolvedPollMode } from "@/lib/poll/types";

const modeLabels: Record<PollMode | ResolvedPollMode, string> = {
  AUTO: "AUTO",
  BACKEND_CHAT: "BACKEND CHAT",
  CHAT_DIRECT: "CHAT DIRECT",
  OFFICIAL_API_DIRECT: "OFFICIAL API DIRECT",
  OFFICIAL_API_BACKEND: "OFFICIAL API BACKEND",
  WEB_FALLBACK: "WEB FALLBACK",
};

interface Props {
  preferredMode: PollMode;
  resolvedMode: ResolvedPollMode;
  note?: string;
}

export default function PollModeBadge({ preferredMode, resolvedMode, note }: Props) {
  return (
    <div className="space-y-2">
      <div className="inline-flex items-center gap-2 rounded-full border border-white/[0.08] bg-[#242426] px-3 py-1 text-[10px] font-black uppercase tracking-[0.18em] text-white/40">
        <span>{modeLabels[preferredMode]}</span>
        <span className="text-white/20">→</span>
        <span className="text-white/60">{modeLabels[resolvedMode]}</span>
      </div>
      {note ? <div className="text-xs leading-5 text-white/40">{note}</div> : null}
    </div>
  );
}
