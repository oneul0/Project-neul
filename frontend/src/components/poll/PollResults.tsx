import type { PollItem, PollResults as PollResultsMap } from "@/lib/poll/types";

interface Props {
  items: PollItem[];
  results: PollResultsMap;
  voters: Record<string, string>;
  totalVotes: number;
  onOpenVoterHistory: (userId: string) => void;
}

export default function PollResults({ items, results, voters, totalVotes, onOpenVoterHistory }: Props) {
  return (
    <div className="space-y-3">
      {items.length === 0 ? (
        <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-600">
          방송 중 시청자 반응을 확인하려면 먼저 투표 항목을 만들어 주세요.
        </div>
      ) : (
        items.map((item) => {
          const votes = results[item.label] ?? 0;
          const ratio = totalVotes ? (votes / totalVotes) * 100 : 0;
          const votersForItem = Object.entries(voters)
            .filter(([, selected]) => selected === item.label)
            .map(([userId]) => userId)
            .slice(0, 5);

          return (
            <div key={item.id} className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
              <div className="flex items-center justify-between gap-3">
                <div className="font-bold text-slate-950">{item.label}</div>
                <div className="text-sm font-mono text-slate-500">
                  {votes}표 · {ratio.toFixed(0)}%
                </div>
              </div>
              <div className="mt-3 h-2 rounded-full bg-white/6">
                <div className="h-2 rounded-full bg-emerald-400" style={{ width: `${ratio}%` }} />
              </div>
              {votersForItem.length > 0 ? (
                <div className="mt-3 flex flex-wrap gap-2">
                  {votersForItem.map((userId) => (
                    <button
                      key={userId}
                      onClick={() => onOpenVoterHistory(userId)}
                      className="rounded-full border border-slate-200 bg-white px-3 py-1 text-xs font-bold text-slate-700 transition hover:bg-slate-100"
                    >
                      {userId}
                    </button>
                  ))}
                </div>
              ) : null}
            </div>
          );
        })
      )}
    </div>
  );
}
