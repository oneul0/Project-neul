import type { PollMode, ResolvedPollMode } from "@/lib/poll/types";

const modeLabels: Record<PollMode | ResolvedPollMode, string> = {
  AUTO: "자동",
  BACKEND_CHAT: "서버 연동",
  CHAT_DIRECT: "직접 연결",
  OFFICIAL_API_DIRECT: "공식 API",
  OFFICIAL_API_BACKEND: "공식 API",
  WEB_FALLBACK: "대기",
};

interface Props {
  preferredMode: PollMode;
  resolvedMode: ResolvedPollMode;
  note?: string;
}

export default function PollModeBadge({ preferredMode, resolvedMode, note }: Props) {
  return (
    <div className="space-y-2">
      <div className="inline-flex items-center gap-2 rounded-full border border-white/[0.08] bg-[#1A1A1A] px-3 py-1 text-[10px] font-black uppercase tracking-[0.18em] text-white/40">
        <span>{modeLabels[preferredMode]}</span>
        <span className="text-white/20">→</span>
        <span className="text-white/60">{modeLabels[resolvedMode]}</span>
      </div>
      {note ? <div className="text-xs leading-5 text-white/40">{note}</div> : null}
    </div>
  );
}
