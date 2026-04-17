import { BarChart3, Clock3, Users } from "lucide-react";
import PollComposer from "@/components/poll/PollComposer";
import PollModeBadge from "@/components/poll/PollModeBadge";
import PollResults from "@/components/poll/PollResults";
import type { UsePollSessionResult } from "@/hooks/usePollSession";

interface Props {
  session: UsePollSessionResult;
  variant?: "main" | "history" | "compact";
}

export default function PollCard({ session, variant = "main" }: Props) {
  if (variant === "history") {
    if (!session.selectedVoter) {
      return null;
    }

    const isLoadingHistory = session.historyLoadingVoterId === session.selectedVoter;

    return (
      <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm transition-all duration-200">
        <div className="mb-5 flex items-center justify-between gap-4">
          <div>
            <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">투표 참여 기록</div>
            <div className="mt-2 text-xl font-black text-slate-950">{session.selectedVoter}</div>
            <div className="mt-2 text-sm text-slate-500">이 시청자가 최근에 남긴 메시지와 감정 분석 기록입니다.</div>
          </div>
          <button
            onClick={session.closeVoterHistory}
            className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700 transition hover:bg-slate-100"
          >
            닫기
          </button>
        </div>

        <div className="space-y-3">
          {isLoadingHistory ? (
            <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-5 text-sm text-slate-600">
              최근 분석 기록을 불러오는 중입니다.
            </div>
          ) : session.historyError ? (
            <div className="rounded-[24px] border border-rose-200 bg-rose-50 p-5 text-sm text-rose-700">
              {session.historyError}
            </div>
          ) : session.voterHistory.length === 0 ? (
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

  const isCompact = variant === "compact";
  const hasPoll = session.items.length > 0;
  const participantCount = Object.keys(session.voters).length;
  const statusLabel = session.isSessionActive ? "진행 중" : hasPoll ? "대기 중" : "미설정";
  const statusTone = session.isSessionActive
    ? "border-emerald-200 bg-emerald-50 text-emerald-700"
    : hasPoll
      ? "border-sky-200 bg-sky-50 text-sky-700"
      : "border-slate-200 bg-slate-50 text-slate-600";

  return (
    <div className={`rounded-[30px] border border-slate-200 bg-white shadow-sm transition-all duration-200 ${isCompact ? "p-5" : "p-6"}`}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">투표</div>
          <div className="mt-2 text-xl font-black text-slate-950">{isCompact ? "시청자 반응 요약" : "시청자 반응 확인"}</div>
          <div className="mt-2 text-sm text-slate-500">
            {isCompact ? "현재 집계 상태를 빠르게 훑어볼 수 있는 요약형 보기입니다." : "항목 구성, 실시간 집계, 참여 시청자 기록을 한 화면에서 관리합니다."}
          </div>
        </div>
        <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-500">
          <Users className="h-5 w-5" />
        </div>
      </div>

      <div className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <div className={`rounded-[22px] border px-4 py-3 ${statusTone}`}>
          <div className="text-[10px] font-black uppercase tracking-[0.18em]">상태</div>
          <div className="mt-2 flex items-center gap-2 text-sm font-black">
            <Clock3 className="h-4 w-4" />
            {statusLabel}
          </div>
        </div>
        <div className="rounded-[22px] border border-slate-200 bg-slate-50 px-4 py-3 text-slate-700">
          <div className="text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">항목</div>
          <div className="mt-2 text-sm font-black text-slate-950">{session.items.length}개</div>
        </div>
        <div className="rounded-[22px] border border-slate-200 bg-slate-50 px-4 py-3 text-slate-700">
          <div className="text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">총 투표</div>
          <div className="mt-2 flex items-center gap-2 text-sm font-black text-slate-950">
            <BarChart3 className="h-4 w-4 text-emerald-500" />
            {session.totalVotes}표
          </div>
        </div>
        <div className="rounded-[22px] border border-slate-200 bg-slate-50 px-4 py-3 text-slate-700">
          <div className="text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">참여 시청자</div>
          <div className="mt-2 text-sm font-black text-slate-950">{participantCount}명</div>
        </div>
      </div>

      <div className={`mt-4 ${isCompact ? "space-y-4" : "space-y-5"}`}>
        {!isCompact ? (
          <>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="flex flex-wrap gap-3">
                <button
                  onClick={session.toggleComposer}
                  disabled={!session.canManage || session.isCreatingPoll || session.isClearingPoll}
                  className="rounded-2xl bg-slate-950 px-4 py-2 text-sm font-black text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-300"
                >
                  {session.showComposer ? "편집 닫기" : "항목 편집"}
                </button>
                <button
                  onClick={session.requestClearPoll}
                  disabled={!session.canManage || session.isCreatingPoll || session.isClearingPoll}
                  className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700 transition hover:bg-slate-100 disabled:cursor-not-allowed disabled:text-slate-400"
                >
                  {session.isClearingPoll ? "초기화 중..." : "투표 초기화"}
                </button>
              </div>
              <PollModeBadge
                preferredMode={session.preferredMode}
                resolvedMode={session.resolvedMode}
                note={session.message || session.capability.reason}
              />
            </div>

            {session.isClearConfirmOpen ? (
              <div className="rounded-[24px] border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-amber-700">투표 초기화 확인</div>
                <div className="mt-2 leading-6">현재 집계와 참여 시청자 기록을 비웁니다. 진행 중인 반응 확인을 다시 시작할 준비가 되었을 때만 초기화해 주세요.</div>
                <div className="mt-3 flex flex-wrap gap-3">
                  <button
                    onClick={session.cancelClearPoll}
                    disabled={session.isClearingPoll}
                    className="rounded-2xl border border-amber-200 bg-white px-4 py-2 text-sm font-black text-amber-900 transition hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    취소
                  </button>
                  <button
                    onClick={() => void session.clearPoll()}
                    disabled={session.isClearingPoll}
                    className="rounded-2xl bg-amber-300 px-4 py-2 text-sm font-black text-slate-950 transition hover:bg-amber-200 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    {session.isClearingPoll ? "초기화 중..." : "초기화 진행"}
                  </button>
                </div>
              </div>
            ) : null}

            {session.actionError ? (
              <div className="rounded-[24px] border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">{session.actionError}</div>
            ) : null}

            <PollComposer
              open={session.showComposer}
              items={session.composerItems}
              duplicateIndexes={session.composerDuplicateIndexes}
              issues={session.composerIssues}
              error={session.composerError}
              canSave={session.composerCanSave}
              isSaving={session.isCreatingPoll}
              onChangeItem={session.updateComposerItem}
              onAddItem={session.addComposerItem}
              onSave={() => void session.createPoll()}
            />
          </>
        ) : (
          <PollModeBadge
            preferredMode={session.preferredMode}
            resolvedMode={session.resolvedMode}
            note={session.message || session.capability.reason}
          />
        )}

        <PollResults
          compact={isCompact}
          items={session.items}
          results={session.results}
          voters={session.voters}
          totalVotes={session.totalVotes}
          selectedVoter={session.selectedVoter}
          historyLoadingVoterId={session.historyLoadingVoterId}
          onOpenVoterHistory={(userId) => void session.openVoterHistory(userId)}
        />
      </div>
    </div>
  );
}
