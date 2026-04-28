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
  helpSummary?: string;
  toneClass: string;
}

export interface BoardEmptyState {
  title: string;
  description: string;
  helpSummary?: string;
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
}): BoardStateCard {
  const { lookupLoading, metadata, hasExistingResults } = params;

  if (lookupLoading) {
    return {
      label: "조회 중",
      summary: "입력한 VOD를 확인하고 있습니다.",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (!metadata) {
    return {
      label: "VOD 선택 전",
      summary: "조회할 VOD를 아직 선택하지 않았습니다.",
      helpSummary: "번호만 입력하거나 전체 URL을 붙여 넣은 뒤 조회하면 됩니다.",
      toneClass: "border-slate-200 bg-slate-50 text-slate-900",
    };
  }

  if (metadata.exists) {
    if (hasExistingResults) {
      return {
        label: "기존 결과 있음",
        summary: `${metadata.title || `VOD ${metadata.videoNo}`} 결과를 바로 열 수 있습니다.`,
        helpSummary: "이 VOD는 이미 분석되어 있어 새 분석 없이 바로 워크스페이스를 열 수 있습니다.",
        toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
      };
    }

    return {
      label: "찾았습니다",
      summary: `${metadata.title || `VOD ${metadata.videoNo}`} 준비 완료`,
      helpSummary: "영상 메타데이터 확인이 끝났습니다. 이어서 분석을 시작하면 편집 후보를 계산합니다.",
      toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
    };
  }

  return {
    label: "다시 확인 필요",
    summary: metadata.message || "해당 VOD를 찾지 못했습니다.",
    helpSummary: "번호나 URL이 맞는지 확인한 뒤 다시 조회해 주세요.",
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
      label: "VOD 선택 전",
      summary: "선택한 VOD 정보가 아직 없습니다.",
      helpSummary: "상단에서 조회한 VOD가 여기에 채워집니다.",
      toneClass: "border-slate-200 bg-slate-50 text-slate-900",
    };
  }

  if (!metadata.exists) {
    return {
      label: "다시 조회해 주세요",
      summary: metadata.message || "이 번호로는 다시보기 정보를 불러올 수 없습니다.",
      helpSummary: "번호나 URL을 다시 확인한 뒤 조회해 주세요.",
      toneClass: "border-amber-200 bg-amber-50 text-amber-900",
    };
  }

  if (status.status === "FAILED") {
    return {
      label: "다시 분석 필요",
      summary: status.message || "이전 분석이 끝나지 못했습니다.",
      helpSummary: "다시 시작하면 새 요청으로 상태를 갱신합니다.",
      toneClass: "border-amber-200 bg-amber-50 text-amber-900",
    };
  }

  if (status.status === "CRAWLING") {
    return {
      label: "채팅 수집 중",
      summary: `현재 ${status.pagesProcessed ?? 0}페이지, ${status.chatsCollected ?? 0}개 채팅을 확인하고 있습니다.`,
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (status.status === "WAITING") {
    return {
      label: "차례 대기 중",
      summary: status.message || "분석 작업이 순서를 기다리고 있습니다.",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (status.status === "REQUESTED") {
    return {
      label: "요청 접수",
      summary: status.message || "분석 요청이 등록되었습니다.",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (status.status === "ANALYZING") {
    return {
      label: "후보 계산 중",
      summary: status.message || "편집 후보를 계산하고 있습니다.",
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
      helpSummary: "다른 VOD를 조회하면 같은 흐름으로 바로 이어서 볼 수 있습니다.",
      toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
    };
  }

  if (hasExistingResults) {
    return {
      label: selectedVideoNo === metadata.videoNo ? "결과 보는 중" : "기존 결과 있음",
      summary:
        selectedVideoNo === metadata.videoNo
          ? `편집 후보 ${highlightsLength}개를 확인 중입니다.`
          : "이 VOD는 기존 분석 결과가 준비되어 있습니다.",
      helpSummary:
        selectedVideoNo === metadata.videoNo
          ? "타임라인과 목록이 같은 결과를 기준으로 함께 움직입니다."
          : "다시 계산하지 않고 저장된 결과를 바로 불러옵니다.",
      toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
    };
  }

  return {
    label: "분석 시작 가능",
    summary: "영상 확인이 끝났습니다.",
    helpSummary: "새 분석을 시작하면 완료 후 워크스페이스가 채워집니다.",
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
      helpSummary: "상단 버튼에서 다시 요청하면 새 상태로 갱신합니다.",
      toneClass: "border-amber-200 bg-amber-50 text-amber-900",
    };
  }

  if (isAnalysisActive) {
    return {
      title: "후보 준비 중입니다",
      description: "완료되면 결과가 바로 표시됩니다.",
      toneClass: "border-indigo-200 bg-indigo-50 text-indigo-900",
    };
  }

  if (status.status === "COMPLETED" && highlightsLength === 0) {
    return {
      title: "이번 영상은 후보 없음",
      description: "기준을 넘는 하이라이트가 아직 없습니다.",
      helpSummary: "다른 VOD를 조회하면 바로 다음 검토로 이어집니다.",
      toneClass: "border-emerald-200 bg-emerald-50 text-emerald-900",
    };
  }

  if (filteredHighlightsLength === 0 && highlightsLength > 0) {
    return {
      title: "필터 결과 없음",
      description: "숨겨진 후보가 있어 현재 목록이 비어 있습니다.",
      helpSummary: "추천만은 낮은 우선순위를 제외하고, 전체는 모든 후보를 표시합니다.",
      toneClass: "border-slate-200 bg-slate-50 text-slate-900",
    };
  }

  return {
    title: "워크스페이스 대기",
    description: "선택한 VOD 결과를 아직 열지 않았습니다.",
    helpSummary: hasExistingResults
      ? "상단의 결과 보기로 기존 분석을 바로 열 수 있습니다."
      : "상단에서 분석을 시작하면 결과가 준비된 뒤 이 영역이 채워집니다.",
    toneClass: "border-slate-200 bg-slate-50 text-slate-900",
  };
}

export function getHighlightPriorityLabel(action: string | null) {
  if (action === "PIN") return "편집점 보관";
  if (action === "GOOD") return "다시 볼 후보";
  if (action === "BAD") return "낮은 우선순위";
  return "추천 후보";
}
