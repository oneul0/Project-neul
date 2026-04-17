export type VodStatus =
  | "IDLE"
  | "REQUESTED"
  | "CRAWLING"
  | "WAITING"
  | "ANALYZING"
  | "COMPLETED"
  | "FAILED";

export type HighlightFilter = "ACTIVE" | "ALL" | "PINNED" | "GOOD";
export type ChartMode = "RESPONSIVE" | "DETAIL";

export interface VodHighlight {
  id: number;
  videoNo: string;
  startSeconds: number;
  endSeconds: number;
  highlightScore: number;
  intensityScore?: number | null;
  transitionScore?: number | null;
  editabilityScore?: number | null;
  category: string;
  reactionLabel?: string | null;
  sceneLabel?: string | null;
  description: string;
  reasonSummary?: string | null;
  topMessage: string;
}

export interface VodTimelinePoint {
  id: number;
  videoNo: string;
  startSeconds: number;
  endSeconds: number;
  messageCount: number;
  participantCount: number;
}

export interface VodMetadata {
  exists: boolean;
  videoNo: string;
  title?: string | null;
  thumbnailImageUrl?: string | null;
  publishDate?: string | null;
  publishDateAt?: number | null;
  channelName?: string | null;
  duration?: number | null;
  message?: string | null;
}

export interface VodAnalysisStatus {
  videoNo: string;
  status: VodStatus;
  message?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  pagesProcessed?: number | null;
  chatsCollected?: number | null;
}

export interface UserVodLibraryEntry {
  id: number;
  ownerId: string;
  videoNo: string;
  status?: string | null;
  lastViewedAt?: string | null;
  lastAnalyzedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface UserVodActivity {
  id: number;
  ownerId: string;
  videoNo: string;
  highlightId?: number | null;
  actionType: string;
  createdAt?: string | null;
}

export interface UserVodPreferenceProfile {
  topCategories: string[];
  topReactionLabels: string[];
  categoryAffinity: Record<string, number>;
  reactionAffinity: Record<string, number>;
}

export interface HighlightMarker extends VodHighlight {
  left: number;
}

export interface MarkerCluster {
  id: string;
  left: number;
  items: HighlightMarker[];
}

export interface ChartHoverCard {
  startSeconds: number;
  endSeconds: number;
  messageCount: number;
  participantCount: number;
}

export interface InlineNotice {
  tone: "good" | "warn";
  message: string;
}

export interface BoardStateCard {
  label: string;
  summary: string;
  actionLabel: string;
  detailLabel: string;
  toneClass: string;
}

export interface BoardEmptyState {
  title: string;
  description: string;
  actionLabel: string;
  toneClass: string;
}

export const EMPTY_STATUS: VodAnalysisStatus = {
  videoNo: "",
  status: "IDLE",
  message: "분석 전입니다.",
};

export const ACTIVE_STATUSES: VodStatus[] = [
  "REQUESTED",
  "WAITING",
  "CRAWLING",
  "ANALYZING",
];

export const categoryLabel: Record<string, string> = {
  LAUGH: "웃음",
  WONDER: "놀람",
  HYPE: "고조",
  TENSION: "긴장",
  HOT_MOMENT: "핫모먼트",
  슈퍼플레이: "슈퍼플레이",
  대참사: "대참사",
  운: "운",
  소통: "소통",
};

export const EMPTY_PREFERENCE_PROFILE: UserVodPreferenceProfile = {
  topCategories: [],
  topReactionLabels: [],
  categoryAffinity: {},
  reactionAffinity: {},
};

export function normalizeHighlightAction(action?: string | null) {
  if (action === "SAVE") return "GOOD";
  if (action === "SKIP") return "BAD";
  return action ?? null;
}

export function formatSeconds(seconds: number) {
  const safe = Math.max(0, Math.floor(seconds));
  const h = Math.floor(safe / 3600);
  const m = Math.floor((safe % 3600) / 60);
  const s = safe % 60;

  if (h > 0) {
    return `${h}:${m.toString().padStart(2, "0")}:${s
      .toString()
      .padStart(2, "0")}`;
  }

  return `${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
}

export function formatDateTime(value?: string | number | null) {
  if (!value) return "-";
  return new Date(value).toLocaleString("ko-KR");
}

export function buildOriginalVodUrl(videoNo: string) {
  return `https://chzzk.naver.com/video/${videoNo}`;
}

export async function copyText(text: string) {
  if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }

  if (typeof document === "undefined") {
    throw new Error("copy-unavailable");
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "true");
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  textarea.style.pointerEvents = "none";

  document.body.appendChild(textarea);
  let copied = false;

  try {
    textarea.focus();
    textarea.select();
    copied = document.execCommand("copy");
  } finally {
    document.body.removeChild(textarea);
  }

  if (!copied) {
    throw new Error("copy-failed");
  }
}

export function resolveVideoNo(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return "";
  if (/^\d+$/.test(trimmed)) return trimmed;

  const urlMatch = trimmed.match(/chzzk\.naver\.com\/video\/(\d+)/i);
  if (urlMatch) return urlMatch[1];

  const digitMatch = trimmed.match(/(\d{5,})/);
  return digitMatch ? digitMatch[1] : "";
}

export function deriveTimeline(highlights: VodHighlight[]): VodTimelinePoint[] {
  return highlights.map((highlight) => ({
    id: highlight.id,
    videoNo: highlight.videoNo,
    startSeconds: highlight.startSeconds,
    endSeconds: highlight.endSeconds,
    messageCount: Math.max(
      1,
      Math.round((highlight.intensityScore ?? highlight.highlightScore) * 1.6),
    ),
    participantCount: Math.max(
      1,
      Math.round((highlight.editabilityScore ?? highlight.highlightScore) * 0.9),
    ),
  }));
}

export function aggregateTimelineForChart(
  source: VodTimelinePoint[],
  maxBars = 120,
): VodTimelinePoint[] {
  if (source.length <= maxBars) {
    return source;
  }

  const itemsPerBucket = Math.ceil(source.length / maxBars);
  const buckets: VodTimelinePoint[] = [];

  for (let index = 0; index < source.length; index += itemsPerBucket) {
    const chunk = source.slice(index, index + itemsPerBucket);
    const first = chunk[0];
    const last = chunk[chunk.length - 1];

    buckets.push({
      id: first.id,
      videoNo: first.videoNo,
      startSeconds: first.startSeconds,
      endSeconds: last.endSeconds,
      messageCount: chunk.reduce((sum, item) => sum + item.messageCount, 0),
      participantCount: Math.max(...chunk.map((item) => item.participantCount), 1),
    });
  }

  return buckets;
}

export function toReadablePoints(...values: Array<string | null | undefined>) {
  return values
    .flatMap((value) =>
      (value ?? "")
        .split(/\s*\|\s*|(?<=[.!?])\s+/)
        .map((part) => part.trim())
        .filter(Boolean),
    )
    .filter((value, index, array) => array.indexOf(value) === index);
}

export function toCompactReasonTags(highlight: VodHighlight) {
  const source = `${highlight.sceneLabel ?? ""} ${highlight.description} ${highlight.reasonSummary ?? ""}`;
  const tags = new Set<string>();

  if (
    source.includes("감탄") ||
    source.includes("놀람") ||
    highlight.category === "WONDER" ||
    highlight.category === "슈퍼플레이"
  ) {
    tags.add("놀람 반응이 큰 장면");
  }
  if (
    source.includes("웃음") ||
    highlight.category === "LAUGH" ||
    highlight.category === "소통" ||
    (highlight.reactionLabel ?? "").includes("웃음")
  ) {
    tags.add("웃음이 터진 장면");
  }
  if (
    source.includes("고조") ||
    source.includes("열기") ||
    highlight.category === "HYPE" ||
    highlight.category === "운"
  ) {
    tags.add("분위기가 올라간 장면");
  }
  if (
    source.includes("긴장") ||
    source.includes("몰입") ||
    highlight.category === "TENSION" ||
    highlight.category === "대참사"
  ) {
    tags.add("긴장감이 높은 장면");
  }
  if (highlight.category === "슈퍼플레이") {
    tags.add("결정적인 플레이가 나온 장면");
  }
  if (highlight.category === "대참사") {
    tags.add("실수나 사고로 반응이 터진 장면");
  }
  if (highlight.category === "운") {
    tags.add("확률 이벤트로 분위기가 터진 장면");
  }
  if (highlight.category === "소통") {
    tags.add("채팅과 상호작용이 살아난 장면");
  }
  if (
    source.includes("흐름") ||
    source.includes("전환") ||
    source.includes("직전 구간")
  ) {
    tags.add("분위기가 바뀌는 장면");
  }
  if (source.includes("짧게 잘라") || source.includes("하이라이트로 쓰기 좋")) {
    tags.add("짧게 편집하기 좋은 장면");
  }
  if (source.includes("반응 강도") || source.includes("먼저 확인")) {
    tags.add("먼저 볼 장면");
  }

  if (typeof highlight.intensityScore === "number" && highlight.intensityScore >= 7) {
    tags.add("반응이 크게 몰린 장면");
  }
  if (typeof highlight.transitionScore === "number" && highlight.transitionScore >= 4) {
    tags.add("편집점으로 보기 좋은 장면");
  }

  if (tags.size === 0) {
    tags.add("편집 후보");
  }

  return Array.from(tags).slice(0, 4);
}

export function getDisplaySceneLabel(highlight: VodHighlight) {
  return (
    highlight.sceneLabel ||
    highlight.reactionLabel ||
    categoryLabel[highlight.category] ||
    "편집 후보"
  );
}

export function buildMarkerClusters(
  highlights: VodHighlight[],
  duration: number,
): MarkerCluster[] {
  const markers = highlights
    .map((item) => ({
      ...item,
      left: (item.startSeconds / duration) * 100,
    }))
    .sort((a, b) => a.startSeconds - b.startSeconds);

  if (markers.length === 0) return [];

  const clusters: MarkerCluster[] = [];
  const threshold = 3.5;
  let currentItems: HighlightMarker[] = [markers[0]];

  for (let index = 1; index < markers.length; index += 1) {
    const marker = markers[index];
    const last = currentItems[currentItems.length - 1];

    if (Math.abs(marker.left - last.left) <= threshold) {
      currentItems.push(marker);
      continue;
    }

    clusters.push({
      id: currentItems.map((item) => item.id).join("-"),
      left:
        currentItems.reduce((sum, item) => sum + item.left, 0) /
        currentItems.length,
      items: currentItems,
    });
    currentItems = [marker];
  }

  clusters.push({
    id: currentItems.map((item) => item.id).join("-"),
    left:
      currentItems.reduce((sum, item) => sum + item.left, 0) /
      currentItems.length,
    items: currentItems,
  });

  return clusters;
}

export function buildActivityMap(activities: UserVodActivity[]) {
  return activities.reduce<Record<number, string>>((acc, activity) => {
    if (typeof activity.highlightId !== "number") {
      return acc;
    }

    if (!(activity.highlightId in acc)) {
      acc[activity.highlightId] = activity.actionType;
    }

    return acc;
  }, {});
}

export function parsePreferenceProfile(data: unknown): UserVodPreferenceProfile {
  if (!data || typeof data !== "object") {
    return EMPTY_PREFERENCE_PROFILE;
  }

  const profile = data as {
    topCategories?: unknown;
    topReactionLabels?: unknown;
    categoryAffinity?: unknown;
    reactionAffinity?: unknown;
  };

  return {
    topCategories: Array.isArray(profile.topCategories) ? profile.topCategories : [],
    topReactionLabels: Array.isArray(profile.topReactionLabels)
      ? profile.topReactionLabels
      : [],
    categoryAffinity:
      typeof profile.categoryAffinity === "object" && profile.categoryAffinity
        ? (profile.categoryAffinity as Record<string, number>)
        : {},
    reactionAffinity:
      typeof profile.reactionAffinity === "object" && profile.reactionAffinity
        ? (profile.reactionAffinity as Record<string, number>)
        : {},
  };
}

export function getStatusToneClass(
  status: VodAnalysisStatus,
  isAnalysisActive: boolean,
) {
  return status.status === "COMPLETED"
    ? "border-emerald-200 bg-emerald-50 text-emerald-900"
    : status.status === "FAILED"
      ? "border-amber-200 bg-amber-50 text-amber-900"
      : isAnalysisActive
        ? "border-indigo-200 bg-indigo-50 text-indigo-900"
        : "border-slate-200 bg-slate-50 text-slate-900";
}

export function getWorkspacePrimaryLabel(params: {
  metadata: VodMetadata | null;
  selectedVideoNo: string | null;
  hasExistingResults: boolean;
  isAnalysisActive: boolean;
}) {
  const { metadata, selectedVideoNo, hasExistingResults, isAnalysisActive } = params;

  return metadata?.exists &&
    metadata.videoNo &&
    (selectedVideoNo === metadata.videoNo || hasExistingResults)
    ? selectedVideoNo === metadata.videoNo
      ? "결과 보기"
      : "기존 결과 열기"
    : isAnalysisActive
      ? "분석 진행 상황 보기"
      : "분석 시작";
}

export function buildLookupState(params: {
  lookupLoading: boolean;
  metadata: VodMetadata | null;
  hasExistingResults: boolean;
  selectedVideoNo: string | null;
}): BoardStateCard {
  const { lookupLoading, metadata, hasExistingResults, selectedVideoNo } = params;

  if (lookupLoading) {
    return {
      label: "조회 중",
      summary: "입력한 VOD를 확인하고 있습니다.",
      actionLabel: "잠시만 기다려 주세요",
      detailLabel: "상태 확인 중",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (!metadata) {
    return {
      label: "VOD 선택 전",
      summary: "번호나 링크를 조회해 주세요.",
      actionLabel: "조회",
      detailLabel: "VOD 확인",
      toneClass: "border-slate-200 bg-slate-50 text-slate-900",
    };
  }

  if (metadata.exists) {
    if (hasExistingResults) {
      return {
        label: "기존 결과 있음",
        summary: `${metadata.title || `VOD ${metadata.videoNo}`} 결과를 바로 열 수 있습니다.`,
        actionLabel: selectedVideoNo === metadata.videoNo ? "결과 보기" : "기존 결과 열기",
        detailLabel: "바로 확인 가능",
        toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
      };
    }

    return {
      label: "찾았습니다",
      summary: `${metadata.title || `VOD ${metadata.videoNo}`} 준비 완료`,
      actionLabel: "분석 시작",
      detailLabel: "새 분석",
      toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
    };
  }

  return {
    label: "다시 확인 필요",
    summary: metadata.message || "해당 VOD를 찾지 못했습니다.",
    actionLabel: "번호/URL 확인",
    detailLabel: "다시 조회",
    toneClass: "border-amber-200 bg-amber-50 text-amber-900",
  };
}

export function buildSelectedVodState(params: {
  metadata: VodMetadata | null;
  status: VodAnalysisStatus;
  selectedVideoNo: string | null;
  highlightsLength: number;
  hasExistingResults: boolean;
}): BoardStateCard {
  const { metadata, status, selectedVideoNo, highlightsLength, hasExistingResults } = params;

  if (!metadata) {
    return {
      label: "VOD를 먼저 고르세요",
      summary: "조회한 뒤 여기서 바로 시작할 수 있습니다.",
      actionLabel: "조회",
      detailLabel: "VOD 선택",
      toneClass: "border-slate-200 bg-slate-50 text-slate-900",
    };
  }

  if (!metadata.exists) {
    return {
      label: "다시 조회해 주세요",
      summary: metadata.message || "이 번호로는 다시보기 정보를 불러올 수 없습니다.",
      actionLabel: "번호/URL 확인",
      detailLabel: "다시 조회",
      toneClass: "border-amber-200 bg-amber-50 text-amber-900",
    };
  }

  if (status.status === "FAILED") {
    return {
      label: "다시 분석 필요",
      summary: status.message || "이전 분석이 끝나지 못했습니다.",
      actionLabel: "분석 시작",
      detailLabel: "재시도",
      toneClass: "border-amber-200 bg-amber-50 text-amber-900",
    };
  }

  if (status.status === "CRAWLING") {
    return {
      label: "채팅 수집 중",
      summary: `현재 ${status.pagesProcessed ?? 0}페이지, ${status.chatsCollected ?? 0}개 채팅을 확인하고 있습니다.`,
      actionLabel: "진행 중",
      detailLabel: "수집 단계",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (status.status === "WAITING") {
    return {
      label: "차례 대기 중",
      summary: status.message || "분석 작업이 순서를 기다리고 있습니다.",
      actionLabel: "진행 중",
      detailLabel: "대기",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (status.status === "REQUESTED") {
    return {
      label: "요청 접수",
      summary: status.message || "분석 요청이 등록되었습니다.",
      actionLabel: "진행 중",
      detailLabel: "요청 완료",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (status.status === "ANALYZING") {
    return {
      label: "후보 계산 중",
      summary: status.message || "편집 후보를 계산하고 있습니다.",
      actionLabel: "진행 중",
      detailLabel: "분석 단계",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (
    status.status === "COMPLETED" &&
    selectedVideoNo === metadata.videoNo &&
    highlightsLength === 0
  ) {
    return {
      label: "분석 완료",
      summary: "이번 결과에는 바로 볼 후보가 없습니다.",
      actionLabel: "다른 VOD 보기",
      detailLabel: "후보 없음",
      toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
    };
  }

  if (hasExistingResults) {
    return {
      label: selectedVideoNo === metadata.videoNo ? "결과 보는 중" : "기존 결과 있음",
      summary:
        selectedVideoNo === metadata.videoNo
          ? `편집 후보 ${highlightsLength}개를 바로 확인할 수 있습니다.`
          : "이 VOD는 이미 분석되어 있어 바로 결과를 열 수 있습니다.",
      actionLabel: selectedVideoNo === metadata.videoNo ? "결과 보기" : "기존 결과 열기",
      detailLabel: selectedVideoNo === metadata.videoNo ? "워크스페이스 열림" : "바로 확인 가능",
      toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
    };
  }

  return {
    label: "분석 시작 가능",
    summary: "영상 확인이 끝났습니다.",
    actionLabel: "분석 시작",
    detailLabel: "새 분석",
    toneClass: "border-slate-200 bg-slate-50 text-slate-900",
  };
}

export function buildResultsEmptyState(params: {
  status: VodAnalysisStatus;
  isAnalysisActive: boolean;
  highlightsLength: number;
  filteredHighlightsLength: number;
  hasExistingResults: boolean;
}): BoardEmptyState {
  const {
    status,
    isAnalysisActive,
    highlightsLength,
    filteredHighlightsLength,
    hasExistingResults,
  } = params;

  if (status.status === "FAILED") {
    return {
      title: "다시 분석 필요",
      description: status.message || "이번 결과를 불러오지 못했습니다.",
      actionLabel: "위 버튼으로 다시 요청",
      toneClass: "border-amber-200 bg-amber-50 text-amber-900",
    };
  }

  if (isAnalysisActive) {
    return {
      title: "후보 준비 중입니다",
      description: "완료되면 결과가 바로 표시됩니다.",
      actionLabel: "진행 중",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (status.status === "COMPLETED" && highlightsLength === 0) {
    return {
      title: "이번 영상은 후보 없음",
      description: "기준을 넘는 하이라이트가 아직 없습니다.",
      actionLabel: "다른 VOD 보기",
      toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
    };
  }

  if (filteredHighlightsLength === 0 && highlightsLength > 0) {
    return {
      title: "필터를 바꿔 보세요",
      description: "숨겨진 후보가 있어 현재 목록이 비어 있습니다.",
      actionLabel: "필터 변경",
      toneClass: "border-slate-200 bg-slate-50 text-slate-900",
    };
  }

  return {
    title: "워크스페이스를 열어 주세요",
    description: "선택한 VOD의 결과를 여기서 바로 이어서 봅니다.",
    actionLabel: hasExistingResults ? "결과 보기" : "분석 시작",
    toneClass: "border-slate-200 bg-slate-50 text-slate-900",
  };
}

export function getHighlightPriorityLabel(action: string | null) {
  if (action === "PIN") return "편집점 보관";
  if (action === "GOOD") return "다시 볼 후보";
  if (action === "BAD") return "낮은 우선순위";
  return "추천 후보";
}
