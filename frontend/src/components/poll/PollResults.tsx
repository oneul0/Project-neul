import type { PollItem, PollResults as PollResultsMap } from "@/lib/poll/types";

interface Props {
  items: PollItem[];
  results: PollResultsMap;
  voters: Record<string, string>;
  totalVotes: number;
  compact?: boolean;
  selectedVoter?: string | null;
  historyLoadingVoterId?: string | null;
  onOpenVoterHistory: (userId: string) => void;
}

export default function PollResults({
  items,
  results,
  voters,
  totalVotes,
  compact = false,
  selectedVoter,
  historyLoadingVoterId,
  onOpenVoterHistory,
}: Props) {
  return (
    <div className={compact ? "space-y-2.5" : "space-y-3"}>
      {items.length === 0 ? (
        <div className="rounded-[20px] border border-dashed border-white/10 bg-[#1A1A1A] p-5 text-sm text-white/50">
          투표 항목을 먼저 만들어 주세요.
        </div>
      ) : totalVotes === 0 && !compact ? (
        <div className="rounded-[20px] border border-[#00FFA3]/20 bg-[#00FFA3]/10 px-4 py-3 text-sm text-[#00FFA3]">
          <span className="font-black">투표 대기 중</span>
          {" — "}시청자가 채팅에{" "}
          {items.slice(0, 3).map((item, i) => (
            <span key={item.id}>
              <span className="font-black">!투표 {i + 1}</span>
              {i < Math.min(items.length, 3) - 1 ? ", " : ""}
            </span>
          ))}
          {items.length > 3 ? " 등" : ""} 형식으로 입력하면 집계됩니다.
        </div>
      ) : (
        items.map((item) => {
          const votes = results[item.label] ?? 0;
          const ratio = totalVotes ? (votes / totalVotes) * 100 : 0;
          const votersForItem = Object.entries(voters)
            .filter(([, selected]) => selected === item.label)
            .map(([userId]) => userId);

          return (
            <div
              key={item.id}
              className={`rounded-[20px] border border-white/[0.08] bg-[#1A1A1A] transition-all duration-200 ${compact ? "p-3.5" : "p-4"}`}
            >
              <div className="flex items-center justify-between gap-3">
                <div>
                  <div className="font-bold text-white">{item.label}</div>
                  <div className="mt-1 text-[11px] font-black uppercase tracking-[0.16em] text-white/40">
                    참여 시청자 {votersForItem.length}명
                  </div>
                </div>
                <div className="text-sm font-mono text-white/50">
                  {votes}표 · {ratio.toFixed(0)}%
                </div>
              </div>
              <div className="mt-3 h-1.5 rounded-full bg-white/[0.08]">
                <div
                  className="h-1.5 rounded-full bg-[#00FFA3] transition-all duration-500"
                  style={{ width: `${ratio}%` }}
                />
              </div>
              {votersForItem.length > 0 ? (
                <div className="mt-3 rounded-[16px] border border-white/[0.06] bg-[#111111] p-3">
                  <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                    <div className="text-[10px] font-black uppercase tracking-[0.18em] text-white/40">참여 시청자</div>
                    <div className="text-[11px] text-white/30">이름을 누르면 채팅 기록을 확인합니다.</div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {votersForItem.map((userId) => {
                      const isSelected = selectedVoter === userId;
                      const isLoading = historyLoadingVoterId === userId;

                      return (
                        <button
                          key={userId}
                          onClick={() => onOpenVoterHistory(userId)}
                          className={`rounded-full border px-3 py-1.5 text-xs font-bold transition ${
                            isSelected
                              ? "border-[#00FFA3]/40 bg-[#00FFA3]/15 text-[#00FFA3] shadow-[0_0_10px_rgba(0,255,163,0.15)]"
                              : "border-white/10 bg-white/[0.05] text-white/70 hover:border-white/20 hover:bg-white/[0.08] hover:text-white"
                          } ${isLoading ? "cursor-progress" : ""}`}
                        >
                          {isLoading ? "불러오는 중..." : userId}
                        </button>
                      );
                    })}
                  </div>
                </div>
              ) : null}
            </div>
          );
        })
      )}
    </div>
  );
}
