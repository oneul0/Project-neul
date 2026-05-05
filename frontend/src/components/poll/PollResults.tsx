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
        <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-600">
          방송 중 시청자 반응을 확인하려면 먼저 투표 항목을 만들어 주세요.
        </div>
      ) : totalVotes === 0 && !compact ? (
        <div className="rounded-[24px] border border-sky-200 bg-sky-50 px-4 py-3 text-sm text-sky-700">
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
              className={`rounded-[24px] border border-slate-200 bg-slate-50 transition-all duration-200 ${compact ? "p-3.5" : "p-4"}`}
            >
              <div className="flex items-center justify-between gap-3">
                <div>
                  <div className="font-bold text-slate-950">{item.label}</div>
                  <div className="mt-1 text-[11px] font-black uppercase tracking-[0.16em] text-slate-400">
                    참여 시청자 {votersForItem.length}명
                  </div>
                </div>
                <div className="text-sm font-mono text-slate-500">
                  {votes}표 · {ratio.toFixed(0)}%
                </div>
              </div>
              <div className="mt-3 h-2 rounded-full bg-slate-200">
                <div className="h-2 rounded-full bg-emerald-400" style={{ width: `${ratio}%` }} />
              </div>
              {votersForItem.length > 0 ? (
                <div className="mt-3 rounded-2xl border border-white/70 bg-white/80 p-3">
                  <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                    <div className="text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">참여 시청자</div>
                    <div className="text-[11px] text-slate-500">이름을 누르면 채팅 기록을 확인합니다.</div>
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
                              ? "border-emerald-300 bg-emerald-50 text-emerald-700 shadow-[0_8px_20px_rgba(16,185,129,0.14)]"
                              : "border-slate-200 bg-white text-slate-700 hover:bg-slate-100"
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
