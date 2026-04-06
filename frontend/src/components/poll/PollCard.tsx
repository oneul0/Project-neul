import { Users } from "lucide-react";
import PollComposer from "@/components/poll/PollComposer";
import PollModeBadge from "@/components/poll/PollModeBadge";
import PollResults from "@/components/poll/PollResults";
import type { UsePollSessionResult } from "@/hooks/usePollSession";

interface Props {
  session: UsePollSessionResult;
  variant?: "main" | "history";
}

export default function PollCard({ session, variant = "main" }: Props) {
  if (variant === "history") {
    if (!session.selectedVoter) {
      return null;
    }

    return (
      <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-5 flex items-center justify-between">
          <div>
            <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">투표 참여 기록</div>
            <div className="mt-2 text-xl font-black text-slate-950">{session.selectedVoter}</div>
          </div>
          <button
            onClick={session.closeVoterHistory}
            className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700"
          >
            닫기
          </button>
        </div>

        <div className="space-y-3">
          {session.voterHistory.length === 0 ? (
            <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-600">
              아직 이 시청자의 최근 분석 기록이 없습니다.
            </div>
          ) : (
            session.voterHistory.map((message) => (
              <div key={message.messageId} className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                <div className="flex items-center gap-2 text-xs font-black uppercase tracking-[0.18em] text-slate-500">
                  <span>{message.emotionType}</span>
                  <span className="font-mono text-slate-400">{(message.emotionScore * 100).toFixed(0)}%</span>
                </div>
                <p className="mt-3 text-sm leading-6 text-slate-700">{message.content || "(빈 메시지)"}</p>
                <div className="mt-3 text-xs text-slate-500">
                  {message.analyzedAt ? new Date(message.analyzedAt).toLocaleString() : "시간 정보 대기 중"}
                </div>
              </div>
            ))
          )}
        </div>
      </section>
    );
  }

  return (
    <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
      <div className="mb-5 flex items-center justify-between">
        <div>
          <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">투표</div>
          <div className="mt-2 text-xl font-black text-slate-950">시청자 반응 확인</div>
        </div>
        <Users className="h-5 w-5 text-emerald-300" />
      </div>

      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-wrap gap-3">
          <button
            onClick={session.toggleComposer}
            disabled={!session.canManage}
            className="rounded-2xl bg-slate-950 px-4 py-2 text-sm font-black text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            {session.showComposer ? "편집 닫기" : "항목 편집"}
          </button>
          <button
            onClick={() => void session.clearPoll()}
            disabled={!session.canManage}
            className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700 transition hover:bg-slate-100 disabled:cursor-not-allowed disabled:text-slate-400"
          >
            투표 초기화
          </button>
        </div>
        <PollModeBadge
          preferredMode={session.preferredMode}
          resolvedMode={session.resolvedMode}
          note={session.message || session.capability.reason}
        />
      </div>

      <PollComposer
        open={session.showComposer}
        items={session.composerItems}
        onChangeItem={session.updateComposerItem}
        onAddItem={session.addComposerItem}
        onSave={() => void session.createPoll()}
      />

      <PollResults
        items={session.items}
        results={session.results}
        voters={session.voters}
        totalVotes={session.totalVotes}
        onOpenVoterHistory={(userId) => void session.openVoterHistory(userId)}
      />
    </div>
  );
}
