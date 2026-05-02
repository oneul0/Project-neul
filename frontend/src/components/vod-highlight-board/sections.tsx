import {
  CheckCircle2,
  Clock3,
  Copy,
  ExternalLink,
  Film,
  LoaderCircle,
  Pin,
  Search,
  Users,
  Zap,
  XCircle,
} from "lucide-react";
import {
  buildOriginalVodUrl,
  formatDateTime,
  formatSeconds,
  getDisplaySceneLabel,
  getHighlightPriorityLabel,
  normalizeHighlightAction,
  toCompactReasonTags,
  toReadablePoints,
  type HighlightFilter,
} from "./shared";
import type { VodHighlightBoardViewModel } from "./useVodHighlightBoard";

const HIGHLIGHT_FILTERS: Array<{ key: HighlightFilter; label: string }> = [
  { key: "ACTIVE", label: "추천만" },
  { key: "ALL", label: "전체" },
  { key: "PINNED", label: "편집점" },
  { key: "GOOD", label: "좋아요" },
];

function CompactInfoDisclosure({
  label,
  summary,
  align = "right",
  cueLabel,
}: {
  label: string;
  summary: string;
  align?: "left" | "right";
  cueLabel?: string;
}) {
  return (
    <details className="group relative">
      <summary
        aria-label={label}
        className={`flex cursor-pointer list-none items-center justify-center border border-slate-200 bg-white text-[11px] font-black text-slate-500 transition hover:border-slate-300 hover:text-slate-700 [&::-webkit-details-marker]:hidden ${cueLabel ? "gap-1.5 rounded-full px-2.5 py-1.5" : "rounded-full p-0"}`}
      >
        <span className={`inline-flex items-center justify-center ${cueLabel ? "h-5 w-5 rounded-full border border-slate-200 text-[10px]" : "h-6 w-6"}`}>?</span>
        {cueLabel ? <span className="pr-0.5 tracking-[0.08em]">{cueLabel}</span> : null}
      </summary>
      <div
        className={`absolute top-full z-10 mt-3 w-72 max-w-[calc(100vw-4rem)] rounded-2xl border border-slate-200 bg-white px-3 py-3 text-xs leading-5 text-slate-600 shadow-[0_18px_50px_rgba(15,23,42,0.12)] ${
          align === "left" ? "left-0" : "right-0"
        }`}
      >
        {summary}
      </div>
    </details>
  );
}

function AnalysisStatusIcon({
  status,
  isAnalysisActive,
  className = "h-5 w-5",
}: {
  status: VodHighlightBoardViewModel["status"]["status"];
  isAnalysisActive: boolean;
  className?: string;
}) {
  if (status === "COMPLETED") {
    return <CheckCircle2 className={className} />;
  }

  if (isAnalysisActive) {
    return <LoaderCircle className={`${className} animate-spin`} />;
  }

  if (status === "FAILED") {
    return <XCircle className={className} />;
  }

  return <Clock3 className={className} />;
}

function HighlightActionButtons({
  currentAction,
  onAction,
}: {
  currentAction: string | null;
  onAction: (action: "GOOD" | "PIN" | "BAD") => void;
}) {
  return (
    <>
      <button
        type="button"
        onClick={() => onAction("GOOD")}
        className={`inline-flex items-center gap-1 rounded-full border px-3 py-2 text-xs font-black transition ${currentAction === "GOOD" ? "border-emerald-300 bg-emerald-50 text-emerald-700" : "border-slate-200 bg-white text-slate-600"}`}
      >
        <CheckCircle2 className="h-3.5 w-3.5" />좋아요
      </button>
      <button
        type="button"
        onClick={() => onAction("PIN")}
        className={`inline-flex items-center gap-1 rounded-full border px-3 py-2 text-xs font-black transition ${currentAction === "PIN" ? "border-indigo-300 bg-indigo-50 text-indigo-700" : "border-slate-200 bg-white text-slate-600"}`}
      >
        <Pin className="h-3.5 w-3.5" />편집점
      </button>
      <button
        type="button"
        onClick={() => onAction("BAD")}
        className={`inline-flex items-center gap-1 rounded-full border px-3 py-2 text-xs font-black transition ${currentAction === "BAD" ? "border-slate-300 bg-slate-100 text-slate-700" : "border-slate-200 bg-white text-slate-600"}`}
      >
        <XCircle className="h-3.5 w-3.5" />별로예요
      </button>
    </>
  );
}

function HighlightScoreBadges({
  highlight,
}: {
  highlight: NonNullable<VodHighlightBoardViewModel["selectedHighlight"]>;
}) {
  return (
    <div className="flex flex-wrap gap-2 text-xs font-bold text-slate-600">
      <span className="inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-3 py-1 text-amber-700">
        <Zap className="h-3.5 w-3.5" />추천 강도 {highlight.highlightScore.toFixed(1)}
      </span>
      {typeof highlight.intensityScore === "number" ? (
        <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">
          반응 밀집도 {highlight.intensityScore.toFixed(1)}
        </span>
      ) : null}
      {typeof highlight.transitionScore === "number" && highlight.transitionScore > 0 ? (
        <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">
          흐름 전환 {highlight.transitionScore.toFixed(1)}
        </span>
      ) : null}
      {typeof highlight.editabilityScore === "number" ? (
        <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">
          편집 용이도 {highlight.editabilityScore.toFixed(1)}
        </span>
      ) : null}
    </div>
  );
}

export function VodLookupSection({
  board,
}: {
  board: VodHighlightBoardViewModel;
}) {
  return (
    <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
        <div className="flex items-start gap-3">
          <div className="space-y-2">
            <div className="inline-flex items-center gap-2 rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
              <Film className="h-3.5 w-3.5" />
              1. VOD 조회
            </div>
            <h3 className="text-2xl font-black text-slate-950">
              다시보기를 찾고 편집 후보 검토를 시작하세요
            </h3>
          </div>
          <CompactInfoDisclosure
            label="VOD 조회 안내"
            summary="번호만 입력하거나 전체 URL을 붙여 넣은 뒤 조회하면 됩니다. 조회 후에는 기존 결과를 열거나 새 분석을 시작할 수 있습니다."
            align="left"
          />
        </div>

        <div className="flex flex-col gap-3 sm:flex-row">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              value={board.videoInput}
              onChange={(event) => board.setVideoInput(event.target.value)}
              placeholder="VOD 번호 또는 전체 URL 붙여넣기"
              className="w-full min-w-[280px] rounded-2xl border border-slate-200 bg-white py-3 pl-10 pr-4 text-sm text-slate-900 outline-none transition focus:border-indigo-400 focus:ring-4 focus:ring-indigo-100"
            />
          </div>
          <button
            onClick={() => void board.handleLookup()}
            disabled={board.lookupLoading}
            className="rounded-2xl bg-slate-950 px-5 py-3 text-sm font-black text-white disabled:bg-slate-300"
          >
            {board.lookupLoading ? "조회 중..." : "조회"}
          </button>
        </div>
      </div>

      {board.inlineNotice ? (
        <div
          className={`mt-4 rounded-[22px] border px-4 py-3 text-sm font-semibold ${
            board.inlineNotice.tone === "good"
              ? "border-emerald-200 bg-emerald-50 text-emerald-700"
              : "border-amber-200 bg-amber-50 text-amber-700"
          }`}
        >
          {board.inlineNotice.message}
        </div>
      ) : null}

      <div className={`mt-4 rounded-[24px] border px-5 py-4 ${board.lookupState.toneClass}`}>
        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="inline-flex items-center rounded-full border border-white/70 bg-white/85 px-3 py-1 text-[11px] font-black tracking-[0.18em] text-slate-700">
              {board.lookupState.label}
            </div>
            <p className="mt-3 text-sm font-semibold text-slate-900">
              {board.lookupState.summary}
            </p>
          </div>
          {board.lookupState.helpSummary ? (
            <CompactInfoDisclosure
              label="VOD 조회 상태 안내"
              summary={board.lookupState.helpSummary}
              cueLabel={/amber|slate-50/.test(board.lookupState.toneClass) ? "도움" : undefined}
            />
          ) : null}
        </div>
      </div>
    </section>
  );
}

export function VodSelectedVideoSection({
  board,
}: {
  board: VodHighlightBoardViewModel;
}) {
  const metadata = board.metadata;

  return (
    <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-center justify-between gap-4">
        <div>
          <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">
            2. 선택한 VOD
          </div>
          <div className="mt-2 text-xl font-black text-slate-950">
            조회한 영상의 메타데이터와 현재 상태
          </div>
        </div>
        {metadata?.exists ? (
          <div className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-xs font-black text-slate-600">
            VOD {metadata.videoNo}
          </div>
        ) : null}
      </div>

      {!metadata ? (
        <div className="mt-5 rounded-[26px] border border-dashed border-slate-300 bg-slate-50 px-5 py-10 text-sm font-semibold text-slate-500">
          조회한 VOD가 아직 없습니다.
        </div>
      ) : metadata.exists ? (
        <div className="mt-5 grid gap-5 lg:grid-cols-[280px_1fr]">
          <div className="overflow-hidden rounded-[26px] border border-slate-200 bg-slate-100">
            {metadata.thumbnailImageUrl ? (
              <img
                src={metadata.thumbnailImageUrl}
                alt={metadata.title || metadata.videoNo}
                className="h-full w-full object-cover"
              />
            ) : (
              <div className="flex h-[180px] items-center justify-center text-slate-400">
                <Film className="h-10 w-10" />
              </div>
            )}
          </div>

          <div className="space-y-4 rounded-[26px] border border-slate-200 bg-slate-50 p-5">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
              <div>
                <h4 className="text-2xl font-black text-slate-950">
                  {metadata.title || "제목 없음"}
                </h4>
                <p className="mt-2 text-sm text-slate-600">
                  {metadata.channelName || "채널명 정보 없음"} · 생성 시각{" "}
                  {formatDateTime(metadata.publishDateAt ?? metadata.publishDate)}
                </p>
              </div>

              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-1">
                <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
                  <div className="text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">
                    길이
                  </div>
                  <div className="mt-1 text-sm font-black text-slate-950">
                    {formatSeconds(metadata.duration ?? 0)}
                  </div>
                </div>
                <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
                  <div className="text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">
                    현재 상태
                  </div>
                  <div className="mt-1 text-sm font-black text-slate-950">
                    {board.status.status}
                  </div>
                </div>
              </div>
            </div>

            <div className={`rounded-[22px] border px-5 py-4 ${board.statusToneClass}`}>
              <div className="flex items-start gap-3">
                <div className="mt-0.5">
                  <AnalysisStatusIcon
                    status={board.status.status}
                    isAnalysisActive={board.isAnalysisActive}
                  />
                </div>
                <div>
                  <div className="text-base font-black">
                    {board.status.message || "분석 전입니다."}
                  </div>
                  <div className="mt-1 text-sm leading-6 text-slate-600">
                    {board.status.status === "CRAWLING"
                      ? `현재 ${board.status.pagesProcessed ?? 0}페이지, ${board.status.chatsCollected ?? 0}개 채팅을 확인했습니다.`
                      : board.status.status === "COMPLETED"
                        ? `완료 시각 ${formatDateTime(board.status.completedAt)}`
                        : board.isAnalysisActive
                          ? "완료되면 결과가 바로 열립니다."
                          : `상태: ${board.status.status}`}
                  </div>
                </div>
              </div>
            </div>

            <div className={`rounded-[22px] border px-5 py-4 ${board.selectedVodState.toneClass}`}>
              <div className="flex items-start justify-between gap-4">
                <div>
                  <div className="inline-flex items-center rounded-full border border-white/70 bg-white/85 px-3 py-1 text-[11px] font-black tracking-[0.18em] text-slate-700">
                    {board.selectedVodState.label}
                  </div>
                  <p className="mt-3 text-sm font-semibold text-slate-900">
                    {board.selectedVodState.summary}
                  </p>
                </div>
                {board.selectedVodState.helpSummary ? (
                  <CompactInfoDisclosure
                    label="선택한 VOD 상태 안내"
                    summary={board.selectedVodState.helpSummary}
                    cueLabel={/amber|slate-50/.test(board.selectedVodState.toneClass) ? "도움" : undefined}
                  />
                ) : null}
              </div>
            </div>

            <div className="flex flex-wrap gap-3">
              <button
                onClick={() =>
                  void (board.selectedVideoNo === metadata.videoNo ||
                  board.hasExistingResults ||
                  board.isAnalysisActive
                    ? board.handleOpenAnalysis()
                    : board.handleAnalyze())
                }
                disabled={board.analysisSubmitting}
                className="rounded-2xl bg-indigo-600 px-5 py-3 text-sm font-black text-white transition hover:bg-indigo-500 disabled:bg-indigo-300"
              >
                {board.analysisSubmitting ? "요청 중..." : board.workspacePrimaryLabel}
              </button>
              <a
                href={buildOriginalVodUrl(metadata.videoNo)}
                target="_blank"
                rel="noreferrer"
                className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-black text-slate-700 transition hover:bg-slate-100"
              >
                영상 열기
              </a>
            </div>
          </div>
        </div>
      ) : (
        <div className="mt-5 rounded-[26px] border border-amber-200 bg-amber-50 p-5 text-sm font-semibold text-amber-700">
          {metadata.message || "해당 VOD를 찾을 수 없습니다."}
        </div>
      )}
    </section>
  );
}

export function VodWorkspaceSection({
  board,
}: {
  board: VodHighlightBoardViewModel;
}) {
  if (!board.selectedVideoNo) {
    if (!board.metadata?.exists) {
      return null;
    }

    return (
      <section className="rounded-[30px] border border-indigo-200 bg-indigo-50 p-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="inline-flex items-center rounded-full border border-white/70 bg-white/85 px-3 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
              {board.hasExistingResults ? "결과 대기" : "워크스페이스 대기"}
            </div>
            <p className="mt-3 text-sm font-semibold text-slate-900">
              {board.hasExistingResults
                ? "선택한 VOD가 준비되었습니다."
                : "선택한 VOD 분석을 아직 시작하지 않았습니다."}
            </p>
          </div>
          <CompactInfoDisclosure
            label="워크스페이스 대기 안내"
            summary={
              board.hasExistingResults
                ? "기존 결과가 있어 다시 계산하지 않고 바로 이어서 검토할 수 있습니다."
                : "분석을 시작하면 완료 후 워크스페이스가 자동으로 채워집니다."
            }
            cueLabel="도움"
          />
        </div>
      </section>
    );
  }

  return (
    <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex flex-col gap-5 xl:flex-row xl:items-start xl:justify-between">
        <div className="flex items-start gap-3">
          <div>
            <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">
              3. 분석 워크스페이스
            </div>
            <h4 className="mt-2 text-2xl font-black text-slate-950">
              선택한 VOD의 흐름과 편집 후보를 한곳에서 확인합니다
            </h4>
          </div>
          <CompactInfoDisclosure
            label="분석 워크스페이스 안내"
            summary="왼쪽 타임라인과 목록에서 장면을 고르면 오른쪽 상세 패널이 같은 후보로 맞춰집니다. 평가와 복사, 원본 열기는 선택한 장면 기준으로 바로 이어집니다."
            align="left"
          />
        </div>

        <div className="grid gap-3 sm:grid-cols-3">
          <div className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3">
            <div className="text-[11px] font-black uppercase tracking-[0.18em] text-emerald-700">
              좋아요
            </div>
            <div className="mt-1 text-lg font-black text-emerald-950">
              {board.goodHighlights.length}개
            </div>
            <div className="mt-1 text-xs text-emerald-700">
              다시 볼 장면으로 표시한 후보
            </div>
          </div>
          <div className="rounded-2xl border border-indigo-200 bg-indigo-50 px-4 py-3">
            <div className="text-[11px] font-black uppercase tracking-[0.18em] text-indigo-700">
              편집점
            </div>
            <div className="mt-1 text-lg font-black text-indigo-950">
              {board.pinnedHighlights.length}개
            </div>
            <div className="mt-1 text-xs text-indigo-700">보관해 둔 편집 후보</div>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
            <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
              낮은 우선순위
            </div>
            <div className="mt-1 text-lg font-black text-slate-950">
              {board.badHighlights.length}개
            </div>
            <div className="mt-1 text-xs text-slate-500">이번 영상에서 뒤로 미룬 후보</div>
          </div>
        </div>
      </div>

      <div className="mt-6 grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
        <div className="space-y-4">
          <div className="min-w-0 overflow-hidden rounded-[24px] border border-slate-200 bg-slate-50 p-5">
            <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                  Broadcast Flow
                </div>
                <div className="mt-2 text-lg font-black text-slate-950">
                  방송 흐름 타임라인
                </div>
                <div className="mt-1 text-sm text-slate-500">
                  {Math.max(board.timeline.length, board.highlights.length)}개 구간 중 편집 후보{" "}
                  {board.highlights.length}개
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-2 text-[11px] font-black tracking-[0.08em] text-slate-500">
                <div className="inline-flex rounded-full border border-slate-200 bg-white p-1">
                  <button
                    type="button"
                    onClick={() => board.setChartMode("RESPONSIVE")}
                    className={`rounded-full px-3 py-1.5 transition ${board.chartMode === "RESPONSIVE" ? "bg-slate-900 text-white" : "text-slate-500"}`}
                  >
                    자동 요약
                  </button>
                  <button
                    type="button"
                    onClick={() => board.setChartMode("DETAIL")}
                    className={`rounded-full px-3 py-1.5 transition ${board.chartMode === "DETAIL" ? "bg-slate-900 text-white" : "text-slate-500"}`}
                  >
                    상세 보기
                  </button>
                </div>
                <CompactInfoDisclosure
                  label="타임라인 범례 안내"
                  summary={`초록 막대는 채팅량, 파란 막대는 참여자 수를 뜻합니다.${board.chartMode === "DETAIL" ? " 상세 보기에서는 긴 방송도 가로 스크롤로 끝까지 확인할 수 있습니다." : " 자동 요약에서는 화면 폭에 맞춰 구간을 묶어 보여줍니다."}`}
                />
                <span className="inline-flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-emerald-700">
                  <Zap className="h-3.5 w-3.5" />
                  채팅량
                </span>
                <span className="inline-flex items-center gap-2 rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-indigo-700">
                  <Users className="h-3.5 w-3.5" />
                  참여자 수
                </span>
              </div>
            </div>

            <div className="mb-4 flex flex-wrap items-center gap-3 text-xs font-bold tracking-[0.14em] text-slate-500">
              <span className="rounded-full border border-slate-200 bg-white px-3 py-1">
                00:00
              </span>
              <span className="rounded-full border border-slate-200 bg-white px-3 py-1">
                {formatSeconds(board.duration)}
              </span>
            </div>

            <div
              ref={board.chartViewportRef}
              className={`rounded-2xl border border-slate-200 bg-white p-4 ${board.chartMode === "DETAIL" ? "overflow-x-auto overflow-y-hidden" : "overflow-hidden"}`}
            >
              {board.chartBars.length === 0 ? (
                <div className="flex h-[280px] items-center justify-center text-sm font-semibold text-slate-500">
                  아직 전체 흐름 데이터가 없습니다.
                </div>
              ) : (
                <div className="relative">
                  {board.hoveredChartBar ? (
                    <div className="pointer-events-none absolute left-3 top-3 z-10 rounded-2xl border border-slate-200 bg-white/95 px-4 py-3 shadow-lg backdrop-blur">
                      <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                        Hover Insight
                      </div>
                      <div className="mt-1 text-sm font-black text-slate-950">
                        {formatSeconds(board.hoveredChartBar.startSeconds)} ~{" "}
                        {formatSeconds(board.hoveredChartBar.endSeconds)}
                      </div>
                      <div className="mt-2 flex items-center gap-3 text-sm font-bold text-slate-700">
                        <span className="inline-flex items-center gap-2 text-emerald-700">
                          <Zap className="h-3.5 w-3.5" />채팅 {board.hoveredChartBar.messageCount}개
                        </span>
                        <span className="inline-flex items-center gap-2 text-indigo-700">
                          <Users className="h-3.5 w-3.5" />참여자 {board.hoveredChartBar.participantCount}명
                        </span>
                      </div>
                    </div>
                  ) : null}

                  <div
                    className="grid h-[280px] grid-flow-col auto-cols-fr items-end gap-px"
                    style={board.chartMinWidth ? { minWidth: board.chartMinWidth } : undefined}
                    onMouseLeave={() => board.setHoveredChartBar(null)}
                  >
                    {board.chartBars.map((item) => (
                      <div
                        key={item.id}
                        className="flex h-full min-w-0 items-end gap-px"
                        title={`${formatSeconds(item.startSeconds)} ~ ${formatSeconds(item.endSeconds)} · 채팅 ${item.messageCount}개 · 참여자 ${item.participantCount}명`}
                        onMouseEnter={() =>
                          board.setHoveredChartBar({
                            startSeconds: item.startSeconds,
                            endSeconds: item.endSeconds,
                            messageCount: item.messageCount,
                            participantCount: item.participantCount,
                          })
                        }
                      >
                        <div
                          className="w-1/2 rounded-t bg-emerald-300/70"
                          style={{ height: `${item.messageHeight}%` }}
                        />
                        <div
                          className="w-1/2 rounded-t bg-indigo-400/80"
                          style={{ height: `${item.participantHeight}%` }}
                        />
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>

            {board.selectedTimelinePoint ? (
              <div className="mt-3 grid gap-3 sm:grid-cols-3">
                <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
                  <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                    선택 구간
                  </div>
                  <div className="mt-1 text-lg font-black text-slate-950">
                    {formatSeconds(board.selectedTimelinePoint.startSeconds)}
                  </div>
                </div>
                <div className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3">
                  <div className="text-[11px] font-black uppercase tracking-[0.18em] text-emerald-700">
                    채팅량
                  </div>
                  <div className="mt-1 text-lg font-black text-emerald-900">
                    {board.selectedTimelinePoint.messageCount}개
                  </div>
                </div>
                <div className="rounded-2xl border border-indigo-200 bg-indigo-50 px-4 py-3">
                  <div className="text-[11px] font-black uppercase tracking-[0.18em] text-indigo-700">
                    참여자 수
                  </div>
                  <div className="mt-1 text-lg font-black text-indigo-900">
                    {board.selectedTimelinePoint.participantCount}명
                  </div>
                </div>
              </div>
            ) : null}
          </div>

          <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-5">
            <div className="mb-3 flex items-center justify-between">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                  Highlight Rail
                </div>
                <div className="mt-1 text-lg font-black text-slate-950">후보 마커 레일</div>
              </div>
              <div className="text-xs font-semibold text-slate-500">
                {board.selectedCluster
                  ? `${board.selectedCluster.items.length}개 후보 묶음`
                  : "하이라이트를 선택해 주세요."}
              </div>
            </div>

            {board.markerClusters.length > 0 ? (
              <>
                <div className="relative h-[84px] overflow-hidden rounded-2xl border border-slate-200 bg-white px-4 py-5">
                  <div className="absolute left-4 right-4 top-[36px] h-[8px] rounded-full bg-slate-200" />
                  {board.markerClusters.map((cluster, idx) => {
                    const lead = cluster.items[0];
                    const selected = cluster.items.some(
                      (item) => item.id === board.selectedHighlightId,
                    );

                    // 인접 클러스터와의 거리가 좁으면 타임코드 라벨 숨김.
                    // 타임코드 문자열 너비 ≈ 7.5% 기준 — 이보다 가까우면 겹침 발생.
                    const LABEL_SUPPRESS_GAP = 7.5;
                    const prev = board.markerClusters[idx - 1];
                    const next = board.markerClusters[idx + 1];
                    const tooClose =
                      (prev !== undefined && cluster.left - prev.left < LABEL_SUPPRESS_GAP) ||
                      (next !== undefined && next.left - cluster.left < LABEL_SUPPRESS_GAP);
                    const showLabel = selected || !tooClose;

                    return (
                      <button
                        key={cluster.id}
                        type="button"
                        onClick={() => board.moveToCard(lead.id)}
                        className="absolute top-0 -translate-x-1/2 transition"
                        style={{ left: `${cluster.left}%` }}
                      >
                        <div className="absolute left-1/2 top-[22px] h-[14px] w-[2px] -translate-x-1/2 bg-rose-300" />
                        <div
                          className={`absolute left-1/2 top-[28px] -translate-x-1/2 rounded-full border-2 transition ${
                            selected
                              ? "h-5 w-5 border-rose-500 bg-rose-500 shadow-[0_0_0_8px_rgba(244,63,94,0.16)]"
                              : cluster.items.length > 1
                                ? "h-4 w-4 border-rose-400 bg-rose-100"
                                : "h-3.5 w-3.5 border-rose-300 bg-white"
                          }`}
                        />
                        {showLabel && (
                          <div className="absolute left-1/2 top-[52px] -translate-x-1/2 whitespace-nowrap text-[10px] font-black tracking-[0.12em] text-slate-500">
                            {formatSeconds(lead.startSeconds)}
                          </div>
                        )}
                        {cluster.items.length > 1 ? (
                          <div className="absolute left-1/2 top-[2px] -translate-x-1/2 rounded-full border border-rose-200 bg-white px-2 py-0.5 text-[10px] font-black text-rose-600 shadow-sm">
                            +{cluster.items.length}
                          </div>
                        ) : null}
                      </button>
                    );
                  })}
                </div>

                {board.selectedCluster ? (
                  <div className="mt-4 rounded-2xl border border-rose-200 bg-rose-50 p-4">
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                      <div>
                        <div className="text-[11px] font-black tracking-[0.18em] text-rose-600">
                          {board.selectedCluster.items.length > 1
                            ? "가까운 후보 묶음"
                            : "선택한 편집 후보"}
                        </div>
                        <div className="mt-1 text-base font-black text-slate-950">
                          {getDisplaySceneLabel(board.selectedCluster.items[0])}
                        </div>
                        <div className="mt-3 flex max-w-xl flex-wrap gap-2">
                          {board.selectedClusterPoints.map((point) => (
                            <span
                              key={point}
                              className="rounded-full border border-rose-200 bg-white px-3 py-2 text-xs font-bold leading-5 text-slate-700"
                            >
                              {point}
                            </span>
                          ))}
                        </div>
                      </div>

                      <CompactInfoDisclosure
                        label="후보 마커 레일 안내"
                        summary={
                          board.selectedCluster.items.length > 1
                            ? "시간이 가까운 후보들은 묶음으로 정리합니다. 아래 시간칩을 누르면 같은 묶음 안에서 빠르게 비교할 수 있습니다."
                            : "마커를 누르면 오른쪽 상세 패널이 같은 장면으로 맞춰집니다."
                        }
                      />
                    </div>

                    <div className="mt-3 flex flex-wrap gap-2">
                      {board.selectedCluster.items.map((item) => {
                        const active = board.selectedHighlightId === item.id;

                        return (
                          <button
                            key={item.id}
                            type="button"
                            onClick={() => board.moveToCard(item.id)}
                            className={`rounded-full border px-3 py-2 text-xs font-black transition ${active ? "border-rose-400 bg-rose-500 text-white" : "border-rose-200 bg-white text-rose-700"}`}
                          >
                            {formatSeconds(item.startSeconds)}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ) : null}
              </>
            ) : (
              <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-5 py-10 text-sm font-semibold text-slate-500">
                편집 후보가 생기면 시간축 위에 마커로 정리됩니다.
              </div>
            )}
          </div>
        </div>

        <div className="space-y-4">
          <div className="rounded-[24px] border border-slate-200 bg-white p-5">
            <div className="mb-4 flex items-center justify-between">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                  Selected Highlight
                </div>
                <div className="mt-2 text-lg font-black text-slate-950">선택한 장면 상세</div>
              </div>
              <div className="text-xs font-semibold text-slate-500">
                {board.selectedHighlight
                  ? `${formatSeconds(board.selectedHighlight.startSeconds)} 기준`
                  : "후보를 선택해 주세요."}
              </div>
            </div>

            {board.selectedHighlight ? (
              (() => {
                const selectedHighlight = board.selectedHighlight;
                const spotlightPoints = toReadablePoints(
                  selectedHighlight.reasonSummary,
                  selectedHighlight.description,
                ).slice(0, 3);
                const selectedTimecodeCopied = board.copiedHighlightId === selectedHighlight.id;

                return (
                  <div className="space-y-4">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1.5 text-[11px] font-black tracking-[0.18em] text-indigo-700">
                        {getDisplaySceneLabel(board.selectedHighlight)}
                      </span>
                    </div>

                    <div className="grid gap-3 sm:grid-cols-2">
                      <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                        <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                          구간
                        </div>
                        <div className="mt-1 text-lg font-black text-slate-950">
                          {formatSeconds(board.selectedHighlight.startSeconds)} ~{" "}
                          {formatSeconds(board.selectedHighlight.endSeconds)}
                        </div>
                      </div>
                      <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                        <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                          우선순위
                        </div>
                        <div className="mt-1 text-lg font-black text-slate-950">
                          {getHighlightPriorityLabel(board.selectedHighlightAction)}
                        </div>
                      </div>
                    </div>

                    <div className="rounded-2xl border border-indigo-200 bg-indigo-50 px-4 py-4">
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex flex-wrap gap-2">
                          <a
                            href={buildOriginalVodUrl(selectedHighlight.videoNo)}
                            target="_blank"
                            rel="noreferrer"
                            className="inline-flex items-center gap-1.5 rounded-full border border-indigo-200 bg-white px-3.5 py-2 text-xs font-black text-indigo-700 transition hover:bg-indigo-100"
                          >
                            <ExternalLink className="h-3.5 w-3.5" />원본 VOD 열기
                          </a>
                          <button
                            type="button"
                            onClick={() => void board.handleCopyHighlightTimecode(selectedHighlight)}
                            className={`inline-flex items-center gap-1.5 rounded-full border px-3.5 py-2 text-xs font-black transition ${selectedTimecodeCopied ? "border-emerald-300 bg-emerald-50 text-emerald-700" : "border-slate-200 bg-white text-slate-700 hover:bg-slate-100"}`}
                          >
                            <Copy className="h-3.5 w-3.5" />
                            {selectedTimecodeCopied ? "타임코드 복사됨" : "타임코드 복사"}
                          </button>
                        </div>
                        <CompactInfoDisclosure
                          label="선택한 장면 사용 안내"
                          summary={`원본 VOD를 연 뒤 ${formatSeconds(selectedHighlight.startSeconds)}부터 ${formatSeconds(selectedHighlight.endSeconds)} 사이를 확인하면 같은 구간을 바로 찾을 수 있습니다.`}
                        />
                      </div>
                    </div>

                    <div>
                      <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                        왜 중요한가
                      </div>
                      <div className="mt-2 flex flex-wrap gap-2">
                        {(spotlightPoints.length > 0
                          ? spotlightPoints
                          : toCompactReasonTags(selectedHighlight)
                        ).map((point) => (
                          <span
                            key={point}
                            className="rounded-full border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-bold leading-5 text-slate-700"
                          >
                            {point}
                          </span>
                        ))}
                      </div>
                    </div>

                    <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                      <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                        대표 채팅
                      </div>
                      <p className="mt-2 text-sm leading-6 text-slate-700">
                        "{selectedHighlight.topMessage || "대표 채팅이 없는 구간입니다."}"
                      </p>
                    </div>

                    <div className="flex flex-wrap gap-2">
                      <HighlightActionButtons
                        currentAction={board.selectedHighlightAction}
                        onAction={(action) => void board.handleHighlightAction(selectedHighlight.id, action)}
                      />
                    </div>

                    <HighlightScoreBadges highlight={selectedHighlight} />
                  </div>
                );
              })()
            ) : (
              <div className={`rounded-[22px] border px-5 py-4 ${board.resultsEmptyState.toneClass}`}>
                <div className="flex items-start gap-3">
                  <div className="mt-0.5">
                    <AnalysisStatusIcon
                      status={board.status.status}
                      isAnalysisActive={board.isAnalysisActive}
                    />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-start justify-between gap-3">
                      <div className="text-base font-black">{board.resultsEmptyState.title}</div>
                      {board.resultsEmptyState.helpSummary ? (
                        <CompactInfoDisclosure
                          label="선택한 장면 상세 상태 안내"
                          summary={board.resultsEmptyState.helpSummary}
                          cueLabel="도움"
                        />
                      ) : null}
                    </div>
                    <div className="mt-1 text-sm leading-6 text-slate-600">
                      {board.resultsEmptyState.description}
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>

          <div className="min-w-0 rounded-[24px] border border-slate-200 bg-slate-50 p-5">
            <div className="mb-4 flex items-center justify-between">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                  Highlights
                </div>
                <div className="mt-2 text-lg font-black text-slate-950">하이라이트 목록</div>
              </div>
              <div className="text-xs font-semibold text-slate-500">
                {board.filteredHighlights.length} / {board.highlights.length}개 표시
              </div>
            </div>

            <div className="mb-3 flex flex-wrap gap-2">
              {HIGHLIGHT_FILTERS.map((filter) => {
                const active = board.highlightFilter === filter.key;

                return (
                  <button
                    key={filter.key}
                    type="button"
                    onClick={() => board.setHighlightFilter(filter.key)}
                    className={`rounded-full border px-3 py-2 text-xs font-black transition ${active ? "border-slate-900 bg-slate-900 text-white" : "border-slate-200 bg-white text-slate-600"}`}
                  >
                    {filter.label}
                  </button>
                );
              })}
            </div>

            <div className="max-h-[720px] space-y-3 overflow-y-auto pr-1">
              {board.filteredHighlights.length === 0 ? (
                <div
                  className={`rounded-2xl border px-5 py-6 text-center ${board.resultsEmptyState.toneClass}`}
                >
                  <div className="mx-auto flex justify-center">
                    <AnalysisStatusIcon
                      status={board.status.status}
                      isAnalysisActive={board.isAnalysisActive}
                      className="h-8 w-8"
                    />
                  </div>
                  <div className="mt-3 flex items-start justify-center gap-2">
                    <div className="text-base font-black text-slate-900">
                      {board.resultsEmptyState.title}
                    </div>
                    {board.resultsEmptyState.helpSummary ? (
                      <CompactInfoDisclosure
                        label="하이라이트 목록 빈 상태 안내"
                        summary={board.resultsEmptyState.helpSummary}
                        cueLabel="도움"
                      />
                    ) : null}
                  </div>
                  <div className="mt-2 text-sm text-slate-600">
                    {board.resultsEmptyState.description}
                  </div>
                </div>
              ) : (
                board.filteredHighlights.map((item) => {
                  const active = board.selectedHighlightId === item.id;
                  const currentAction = normalizeHighlightAction(board.highlightActions[item.id]);
                  const reasonPoints = toReadablePoints(
                    item.reasonSummary,
                    item.description,
                  ).slice(0, 2);
                  const timecodeCopied = board.copiedHighlightId === item.id;

                  return (
                    <div
                      key={item.id}
                      ref={(element) => {
                        board.cardRefs.current[item.id] = element;
                      }}
                      onMouseEnter={() => board.setSelectedHighlightId(item.id)}
                      className={`rounded-[22px] border p-4 transition ${active ? "border-rose-300 bg-rose-50/70 shadow-sm" : "border-slate-200 bg-white"}`}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="space-y-2">
                          <div className="text-sm font-black text-slate-950">
                            {formatSeconds(item.startSeconds)} ~ {formatSeconds(item.endSeconds)}
                          </div>
                          <div className="mt-1 flex flex-wrap gap-2">
                            <span className="rounded-full border border-indigo-200 bg-indigo-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
                              {getDisplaySceneLabel(item)}
                            </span>
                            {currentAction === "PIN" ? (
                              <span className="rounded-full border border-indigo-200 bg-white px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
                                편집점으로 보관됨
                              </span>
                            ) : currentAction === "GOOD" ? (
                              <span className="rounded-full border border-emerald-200 bg-white px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-emerald-700">
                                좋아요 표시됨
                              </span>
                            ) : currentAction === "BAD" ? (
                              <span className="rounded-full border border-slate-300 bg-white px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-slate-600">
                                낮은 우선순위
                              </span>
                            ) : null}
                          </div>
                        </div>

                        <div className="flex shrink-0 flex-wrap justify-end gap-1.5">
                          <a
                            href={buildOriginalVodUrl(item.videoNo)}
                            target="_blank"
                            rel="noreferrer"
                            className="inline-flex items-center gap-1 rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-[10px] font-black text-slate-600 transition hover:bg-slate-100"
                          >
                            <ExternalLink className="h-3 w-3" />원본 VOD
                          </a>
                          <button
                            type="button"
                            onClick={() => void board.handleCopyHighlightTimecode(item)}
                            className={`inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[10px] font-black transition ${timecodeCopied ? "border-emerald-300 bg-emerald-50 text-emerald-700" : "border-slate-200 bg-white text-slate-600 hover:bg-slate-100"}`}
                          >
                            <Copy className="h-3 w-3" />
                            {timecodeCopied ? "복사됨" : "타임코드"}
                          </button>
                        </div>
                      </div>

                      <div className="mt-4">
                        <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                          왜 중요한가
                        </div>
                        <div className="mt-2 flex flex-wrap gap-2 text-xs font-bold text-slate-600">
                          {(reasonPoints.length > 0
                            ? reasonPoints
                            : toCompactReasonTags(item).slice(0, 3)
                          ).map((point) => (
                            <span
                              key={point}
                              className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1"
                            >
                              {point}
                            </span>
                          ))}
                        </div>
                      </div>

                      <div className="mt-4 rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                        <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                          대표 채팅
                        </div>
                        <p className="mt-2 text-sm leading-6 text-slate-700">
                          "{item.topMessage || "대표 채팅이 없는 구간입니다."}"
                        </p>
                      </div>

                      <div className="mt-4 flex flex-wrap gap-2">
                        <button
                          type="button"
                          onClick={() => board.moveToCard(item.id)}
                          className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1.5 text-[11px] font-black text-slate-600 transition hover:bg-slate-100"
                        >
                          상세 보기
                        </button>
                        <button
                          type="button"
                          onClick={() => void board.handleHighlightAction(item.id, "GOOD")}
                          className={`inline-flex items-center gap-1 rounded-full border px-3 py-1.5 text-[11px] font-black transition ${currentAction === "GOOD" ? "border-emerald-300 bg-emerald-50 text-emerald-700" : "border-slate-200 bg-white text-slate-600"}`}
                        >
                          <CheckCircle2 className="h-3.5 w-3.5" />좋아요
                        </button>
                        <button
                          type="button"
                          onClick={() => void board.handleHighlightAction(item.id, "PIN")}
                          className={`inline-flex items-center gap-1 rounded-full border px-3 py-1.5 text-[11px] font-black transition ${currentAction === "PIN" ? "border-indigo-300 bg-indigo-50 text-indigo-700" : "border-slate-200 bg-white text-slate-600"}`}
                        >
                          <Pin className="h-3.5 w-3.5" />편집점
                        </button>
                        <button
                          type="button"
                          onClick={() => void board.handleHighlightAction(item.id, "BAD")}
                          className={`inline-flex items-center gap-1 rounded-full border px-3 py-1.5 text-[11px] font-black transition ${currentAction === "BAD" ? "border-slate-300 bg-slate-100 text-slate-700" : "border-slate-200 bg-white text-slate-600"}`}
                        >
                          <XCircle className="h-3.5 w-3.5" />별로예요
                        </button>
                      </div>

                      <div className="mt-3 flex flex-wrap gap-2 text-xs font-bold text-slate-600">
                        <span className="rounded-full border border-amber-200 bg-amber-50 px-3 py-1 text-amber-700">
                          추천 강도 {item.highlightScore.toFixed(1)}
                        </span>
                        {typeof item.intensityScore === "number" ? (
                          <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">
                            반응 밀집도 {item.intensityScore.toFixed(1)}
                          </span>
                        ) : null}
                        {typeof item.transitionScore === "number" && item.transitionScore > 0 ? (
                          <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">
                            흐름 전환 {item.transitionScore.toFixed(1)}
                          </span>
                        ) : null}
                        {typeof item.editabilityScore === "number" ? (
                          <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">
                            편집 용이도 {item.editabilityScore.toFixed(1)}
                          </span>
                        ) : null}
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

export function VodPersonalizationSection({
  board,
  personalizationEnabled,
}: {
  board: VodHighlightBoardViewModel;
  personalizationEnabled: boolean;
}) {
  return (
    <section className="grid gap-6 xl:grid-cols-[1.15fr_0.85fr]">
      <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-5 flex items-center justify-between">
          <div>
            <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">
              My VOD Library
            </div>
            <div className="mt-2 text-xl font-black text-slate-950">최근에 본 다시보기</div>
          </div>
          {personalizationEnabled ? (
            <button
              type="button"
              onClick={() => void board.fetchLibrary()}
              className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700"
            >
              새로고침
            </button>
          ) : null}
        </div>

        {!personalizationEnabled ? (
          <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 px-5 py-8 text-sm font-semibold text-slate-500">
            로그인 후 최근에 본 다시보기와 저장한 활동 이력을 함께 볼 수 있습니다.
          </div>
        ) : board.libraryLoading ? (
          <div className="rounded-[24px] border border-slate-200 bg-slate-50 px-5 py-8 text-sm font-semibold text-slate-500">
            최근 VOD를 불러오는 중입니다.
          </div>
        ) : board.library.length === 0 ? (
          <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 px-5 py-8 text-sm font-semibold text-slate-500">
            아직 확인한 VOD가 없습니다. 조회하거나 분석한 VOD가 여기에 쌓입니다.
          </div>
        ) : (
          <div className="grid gap-3 lg:grid-cols-2">
            {board.library.slice(0, 6).map((item) => {
              const statusLabel =
                item.status === "READY"
                  ? "분석 완료"
                  : item.status === "ANALYZING"
                    ? "분석 중"
                    : "최근 열람";

              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => void board.lookupVideo(item.videoNo)}
                  className="rounded-[24px] border border-slate-200 bg-slate-50 p-4 text-left transition hover:border-indigo-300 hover:bg-indigo-50/40"
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="text-sm font-black text-slate-950">VOD {item.videoNo}</div>
                    <span className="rounded-full border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-black text-slate-600">
                      {statusLabel}
                    </span>
                  </div>
                  <div className="mt-3 space-y-1 text-xs text-slate-500">
                    <div>최근 열람 {formatDateTime(item.lastViewedAt)}</div>
                    <div>최근 분석 {formatDateTime(item.lastAnalyzedAt)}</div>
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </div>

      <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-4">
          <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">
            Preference Profile
          </div>
          <div className="mt-2 text-xl font-black text-slate-950">지금 반영 중인 내 취향</div>
        </div>

        <div className="space-y-4">
          {!personalizationEnabled ? (
            <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-4 text-sm font-semibold text-slate-500">
              로그인 후 좋아요 / 편집점 / 별로예요 기록이 쌓이면 이 영역에서 선호 카테고리와 반응을 보여줍니다.
            </div>
          ) : null}
          <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
            <div className="text-xs font-black uppercase tracking-[0.18em] text-slate-500">
              선호 카테고리
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              {!personalizationEnabled || board.preferenceProfile.topCategories.length === 0 ? (
                <span className="text-sm font-semibold text-slate-500">
                  아직 활동 데이터가 부족해서 기본 정렬로 보여주고 있어요.
                </span>
              ) : (
                board.preferenceProfile.topCategories.map((category) => (
                  <span
                    key={category}
                    className="rounded-full border border-indigo-200 bg-indigo-50 px-3 py-2 text-xs font-black text-indigo-700"
                  >
                    {category}
                  </span>
                ))
              )}
            </div>
          </div>

          <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
            <div className="text-xs font-black uppercase tracking-[0.18em] text-slate-500">
              선호 반응
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              {!personalizationEnabled || board.preferenceProfile.topReactionLabels.length === 0 ? (
                <span className="text-sm font-semibold text-slate-500">
                  좋아요와 별로예요 이력이 쌓이면 여기에 반영돼요.
                </span>
              ) : (
                board.preferenceProfile.topReactionLabels.map((label) => (
                  <span
                    key={label}
                    className="rounded-full border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-black text-rose-700"
                  >
                    {label}
                  </span>
                ))
              )}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
