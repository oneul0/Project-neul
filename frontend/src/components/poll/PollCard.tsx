import { BarChart3, Clock3, Users } from "lucide-react";
import PollComposer from "@/components/poll/PollComposer";
import PollModeBadge from "@/components/poll/PollModeBadge";
import PollResults from "@/components/poll/PollResults";
import VoterChatModal from "@/components/poll/VoterChatModal";
import type { UsePollSessionResult } from "@/hooks/usePollSession";

interface Props {
  session: UsePollSessionResult;
  variant?: "main" | "compact";
}

export default function PollCard({ session, variant = "main" }: Props) {
  const isCompact = variant === "compact";
  const hasPoll = session.items.length > 0;
  const participantCount = Object.keys(session.voters).length;
  const statusLabel = session.isSessionActive ? "진행 중" : hasPoll ? "대기 중" : "미설정";
  const statusTone = session.isSessionActive
    ? "border-[#00FFA3]/25 bg-[#00FFA3]/10 text-[#00FFA3]"
    : hasPoll
      ? "border-sky-500/25 bg-sky-500/10 text-sky-400"
      : "border-white/[0.08] bg-[#242426] text-white/50";

  return (
    <div className={`rounded-[28px] border border-white/[0.08] bg-[#1A1A1C] transition-all duration-200 ${isCompact ? "p-5" : "p-6"}`}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="text-[11px] font-black uppercase tracking-[0.22em] text-white/40">투표</div>
          <div className="mt-2 text-xl font-black text-white">{isCompact ? "시청자 반응 요약" : "시청자 반응 확인"}</div>
          <div className="mt-2 text-sm text-white/50">
            {isCompact ? "현재 집계 상태를 빠르게 훑어볼 수 있는 요약형 보기입니다." : "항목 구성, 실시간 집계, 참여 시청자 기록을 한 화면에서 관리합니다."}
          </div>
        </div>
        <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-[#00FFA3]/10 text-[#00FFA3]">
          <Users className="h-5 w-5" />
        </div>
      </div>

      <div className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <div className={`rounded-[20px] border px-4 py-3 ${statusTone}`}>
          <div className="text-[10px] font-black uppercase tracking-[0.18em] opacity-70">상태</div>
          <div className="mt-2 flex items-center gap-2 text-sm font-black">
            <Clock3 className="h-4 w-4" />
            {statusLabel}
          </div>
        </div>
        <div className="rounded-[20px] border border-white/[0.08] bg-[#242426] px-4 py-3">
          <div className="text-[10px] font-black uppercase tracking-[0.18em] text-white/40">항목</div>
          <div className="mt-2 text-sm font-black text-white">{session.items.length}개</div>
        </div>
        <div className="rounded-[20px] border border-white/[0.08] bg-[#242426] px-4 py-3">
          <div className="text-[10px] font-black uppercase tracking-[0.18em] text-white/40">총 투표</div>
          <div className="mt-2 flex items-center gap-2 text-sm font-black text-white">
            <BarChart3 className="h-4 w-4 text-[#00FFA3]" />
            {session.totalVotes}표
          </div>
        </div>
        <div className="rounded-[20px] border border-white/[0.08] bg-[#242426] px-4 py-3">
          <div className="text-[10px] font-black uppercase tracking-[0.18em] text-white/40">참여 시청자</div>
          <div className="mt-2 text-sm font-black text-white">{participantCount}명</div>
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
                  className="rounded-2xl bg-white/[0.08] px-4 py-2 text-sm font-black text-white transition hover:bg-white/[0.12] disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {session.showComposer ? "편집 닫기" : "항목 편집"}
                </button>
                <button
                  onClick={session.requestClearPoll}
                  disabled={!session.canManage || session.isCreatingPoll || session.isClearingPoll}
                  className="rounded-2xl border border-white/[0.08] bg-transparent px-4 py-2 text-sm font-black text-white/60 transition hover:bg-white/[0.06] hover:text-white disabled:cursor-not-allowed disabled:opacity-40"
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
              <div className="rounded-[20px] border border-amber-500/25 bg-amber-500/10 p-4 text-sm text-amber-400">
                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-amber-400/80">투표 초기화 확인</div>
                <div className="mt-2 leading-6 text-amber-400/90">현재 집계와 참여 시청자 기록을 비웁니다. 진행 중인 반응 확인을 다시 시작할 준비가 되었을 때만 초기화해 주세요.</div>
                <div className="mt-3 flex flex-wrap gap-3">
                  <button
                    onClick={session.cancelClearPoll}
                    disabled={session.isClearingPoll}
                    className="rounded-2xl border border-white/10 bg-transparent px-4 py-2 text-sm font-black text-white/70 transition hover:bg-white/[0.06] disabled:opacity-60"
                  >
                    취소
                  </button>
                  <button
                    onClick={() => void session.clearPoll()}
                    disabled={session.isClearingPoll}
                    className="rounded-2xl bg-amber-500/80 px-4 py-2 text-sm font-black text-white transition hover:bg-amber-500 disabled:opacity-60"
                  >
                    {session.isClearingPoll ? "초기화 중..." : "초기화 진행"}
                  </button>
                </div>
              </div>
            ) : null}

            {session.actionError ? (
              <div className="rounded-[20px] border border-rose-500/25 bg-rose-500/10 p-4 text-sm text-rose-400">{session.actionError}</div>
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

      {session.selectedVoter && (
        <VoterChatModal
          voterName={session.selectedVoter}
          messages={session.voterHistory}
          isLoading={session.historyLoadingVoterId === session.selectedVoter}
          error={session.historyError}
          onClose={session.closeVoterHistory}
        />
      )}
    </div>
  );
}
