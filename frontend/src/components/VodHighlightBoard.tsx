"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  CheckCircle2,
  Clock3,
  Film,
  LoaderCircle,
  Pin,
  Search,
  Users,
  Zap,
  XCircle,
} from "lucide-react";

type VodStatus =
  | "IDLE"
  | "REQUESTED"
  | "CRAWLING"
  | "WAITING"
  | "ANALYZING"
  | "COMPLETED"
  | "FAILED";

type HighlightFilter = "ACTIVE" | "ALL" | "PINNED" | "GOOD";
type ChartMode = "RESPONSIVE" | "DETAIL";

interface VodHighlight {
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
  description: string;
  reasonSummary?: string | null;
  topMessage: string;
}

interface VodTimelinePoint {
  id: number;
  videoNo: string;
  startSeconds: number;
  endSeconds: number;
  messageCount: number;
  participantCount: number;
}

interface VodMetadata {
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

interface VodAnalysisStatus {
  videoNo: string;
  status: VodStatus;
  message?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  pagesProcessed?: number | null;
  chatsCollected?: number | null;
}

interface UserVodLibraryEntry {
  id: number;
  ownerId: string;
  videoNo: string;
  status?: string | null;
  lastViewedAt?: string | null;
  lastAnalyzedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

interface UserVodActivity {
  id: number;
  ownerId: string;
  videoNo: string;
  highlightId?: number | null;
  actionType: string;
  createdAt?: string | null;
}

interface UserVodPreferenceProfile {
  topCategories: string[];
  topReactionLabels: string[];
  categoryAffinity: Record<string, number>;
  reactionAffinity: Record<string, number>;
}

interface HighlightMarker extends VodHighlight {
  left: number;
}

interface MarkerCluster {
  id: string;
  left: number;
  items: HighlightMarker[];
}

interface ChartHoverCard {
  startSeconds: number;
  endSeconds: number;
  messageCount: number;
  participantCount: number;
}

interface InlineNotice {
  tone: "good" | "warn";
  message: string;
}

const EMPTY_STATUS: VodAnalysisStatus = {
  videoNo: "",
  status: "IDLE",
  message: "아직 분석을 시작하지 않았습니다.",
};

const ACTIVE_STATUSES: VodStatus[] = [
  "REQUESTED",
  "WAITING",
  "CRAWLING",
  "ANALYZING",
];

function normalizeHighlightAction(action?: string | null) {
  if (action === "SAVE") return "GOOD";
  if (action === "SKIP") return "BAD";
  return action ?? null;
}

const categoryLabel: Record<string, string> = {
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

function formatSeconds(seconds: number) {
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

function formatDateTime(value?: string | number | null) {
  if (!value) return "-";
  return new Date(value).toLocaleString("ko-KR");
}

function resolveVideoNo(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return "";
  if (/^\d+$/.test(trimmed)) return trimmed;

  const urlMatch = trimmed.match(/chzzk\.naver\.com\/video\/(\d+)/i);
  if (urlMatch) return urlMatch[1];

  const digitMatch = trimmed.match(/(\d{5,})/);
  return digitMatch ? digitMatch[1] : "";
}

function deriveTimeline(highlights: VodHighlight[]): VodTimelinePoint[] {
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

function aggregateTimelineForChart(
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

function toReadablePoints(...values: Array<string | null | undefined>) {
  return values
    .flatMap((value) =>
      (value ?? "")
        .split(/(?<=[.!?])\s+/)
        .map((part) => part.trim())
        .filter(Boolean),
    )
    .filter((value, index, array) => array.indexOf(value) === index);
}

function toCompactReasonTags(highlight: VodHighlight) {
  const source = `${highlight.description} ${highlight.reasonSummary ?? ""}`;
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

function buildMarkerClusters(
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

export default function VodHighlightBoard({
  personalizationEnabled = false,
}: {
  personalizationEnabled?: boolean;
}) {
  const [videoInput, setVideoInput] = useState("");
  const [metadata, setMetadata] = useState<VodMetadata | null>(null);
  const [status, setStatus] = useState<VodAnalysisStatus>(EMPTY_STATUS);
  const [highlights, setHighlights] = useState<VodHighlight[]>([]);
  const [timeline, setTimeline] = useState<VodTimelinePoint[]>([]);
  const [library, setLibrary] = useState<UserVodLibraryEntry[]>([]);
  const [libraryLoading, setLibraryLoading] = useState(true);
  const [highlightActions, setHighlightActions] = useState<Record<number, string>>({});
  const [preferenceProfile, setPreferenceProfile] = useState<UserVodPreferenceProfile>({
    topCategories: [],
    topReactionLabels: [],
    categoryAffinity: {},
    reactionAffinity: {},
  });
  const [selectedVideoNo, setSelectedVideoNo] = useState<string | null>(null);
  const [selectedHighlightId, setSelectedHighlightId] = useState<number | null>(
    null,
  );
  const [lookupLoading, setLookupLoading] = useState(false);
  const [analysisSubmitting, setAnalysisSubmitting] = useState(false);
  const [highlightFilter, setHighlightFilter] = useState<HighlightFilter>("ACTIVE");
  const [chartMode, setChartMode] = useState<ChartMode>("RESPONSIVE");
  const [chartWidth, setChartWidth] = useState(0);
  const [hoveredChartBar, setHoveredChartBar] = useState<ChartHoverCard | null>(null);
  const [inlineNotice, setInlineNotice] = useState<InlineNotice | null>(null);

  const cardRefs = useRef<Record<number, HTMLDivElement | null>>({});
  const chartViewportRef = useRef<HTMLDivElement | null>(null);

  const fetchStatus = useCallback(async (videoNo: string) => {
    const response = await fetch(`/api/vod/${videoNo}/status`, {
      cache: "no-store",
    });
    const data = (await response.json()) as VodAnalysisStatus;
    return data.videoNo ? data : { ...EMPTY_STATUS, videoNo };
  }, []);

  const recordHighlightActivity = useCallback(
    async (videoNo: string, highlightId: number, actionType: string) => {
      try {
        await fetch(`/api/me/vod/${videoNo}/activity`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            highlightId,
            actionType,
          }),
        });
      } catch {
        // Activity tracking should never block the viewing flow.
      }
    },
    [],
  );

  const fetchLibrary = useCallback(async () => {
    if (!personalizationEnabled) {
      setLibrary([]);
      setLibraryLoading(false);
      return;
    }

    try {
      setLibraryLoading(true);
      const response = await fetch("/api/me/vod-library", {
        cache: "no-store",
      });

      if (!response.ok) {
        setLibrary([]);
        return;
      }

      const data = await response.json();
      setLibrary(Array.isArray(data) ? (data as UserVodLibraryEntry[]) : []);
    } catch {
      setLibrary([]);
    } finally {
      setLibraryLoading(false);
    }
  }, [personalizationEnabled]);

  const fetchPreferenceProfile = useCallback(async () => {
    if (!personalizationEnabled) {
      setPreferenceProfile({
        topCategories: [],
        topReactionLabels: [],
        categoryAffinity: {},
        reactionAffinity: {},
      });
      return;
    }

    try {
      const response = await fetch("/api/me/vod-preferences", {
        cache: "no-store",
      });

      if (!response.ok) {
        setPreferenceProfile({
          topCategories: [],
          topReactionLabels: [],
          categoryAffinity: {},
          reactionAffinity: {},
        });
        return;
      }

      const data = await response.json();
      setPreferenceProfile({
        topCategories: Array.isArray(data?.topCategories) ? data.topCategories : [],
        topReactionLabels: Array.isArray(data?.topReactionLabels)
          ? data.topReactionLabels
          : [],
        categoryAffinity:
          typeof data?.categoryAffinity === "object" && data.categoryAffinity
            ? (data.categoryAffinity as Record<string, number>)
            : {},
        reactionAffinity:
          typeof data?.reactionAffinity === "object" && data.reactionAffinity
            ? (data.reactionAffinity as Record<string, number>)
            : {},
      });
    } catch {
      setPreferenceProfile({
        topCategories: [],
        topReactionLabels: [],
        categoryAffinity: {},
        reactionAffinity: {},
      });
    }
  }, [personalizationEnabled]);

  const fetchHighlightActions = useCallback(async (videoNo: string) => {
    if (!personalizationEnabled) {
      setHighlightActions({});
      return;
    }

    try {
      const response = await fetch(`/api/me/vod/${videoNo}/activity`, {
        cache: "no-store",
      });

      if (!response.ok) {
        setHighlightActions({});
        return;
      }

      const data = await response.json();
      const activities = Array.isArray(data) ? (data as UserVodActivity[]) : [];
      const latestByHighlight = activities.reduce<Record<number, string>>((acc, activity) => {
        if (typeof activity.highlightId !== "number") {
          return acc;
        }

        if (!(activity.highlightId in acc)) {
          acc[activity.highlightId] = activity.actionType;
        }

        return acc;
      }, {});

      setHighlightActions(latestByHighlight);
    } catch {
      setHighlightActions({});
    }
  }, [personalizationEnabled]);

  const syncData = useCallback(
    async (videoNo: string) => {
      const [nextStatus, highlightsResponse, timelineResponse, activityResponse] =
        await Promise.all([
          fetchStatus(videoNo),
          fetch(`/api/vod/${videoNo}/highlights`, {
            cache: "no-store",
          })
            .then((res) => res.json())
            .catch(() => []),
          fetch(`/api/vod/${videoNo}/timeline`, {
            cache: "no-store",
          })
            .then((res) => res.json())
            .catch(() => []),
          personalizationEnabled
            ? fetch(`/api/me/vod/${videoNo}/activity`, {
                cache: "no-store",
              })
                .then((res) => (res.ok ? res.json() : []))
                .catch(() => [])
            : Promise.resolve([]),
        ]);

      const nextHighlights = Array.isArray(highlightsResponse)
        ? (highlightsResponse as VodHighlight[])
        : [];
      const nextTimeline = Array.isArray(timelineResponse)
        ? (timelineResponse as VodTimelinePoint[])
        : [];

      setStatus(nextStatus);
      setHighlights(nextHighlights);
      setTimeline(
        nextTimeline.length > 0 ? nextTimeline : deriveTimeline(nextHighlights),
      );
      const activities = Array.isArray(activityResponse)
        ? (activityResponse as UserVodActivity[])
        : [];
      const latestByHighlight = activities.reduce<Record<number, string>>((acc, activity) => {
        if (typeof activity.highlightId !== "number") {
          return acc;
        }

        if (!(activity.highlightId in acc)) {
          acc[activity.highlightId] = activity.actionType;
        }

        return acc;
      }, {});
      setHighlightActions(latestByHighlight);
    },
    [fetchStatus, personalizationEnabled],
  );

  const lookupVideo = useCallback(
    async (videoNo: string) => {
      setLookupLoading(true);
      try {
        const [metadataResponse, nextStatus] = await Promise.all([
          fetch(`/api/vod/${videoNo}/metadata`, { cache: "no-store" }),
          fetchStatus(videoNo),
        ]);

        const nextMetadata = (await metadataResponse.json()) as VodMetadata;
        setMetadata(nextMetadata);
        setStatus(nextStatus);
        setSelectedVideoNo(null);
        setHighlights([]);
        setTimeline([]);
        setSelectedHighlightId(null);
        setVideoInput(videoNo);
        setInlineNotice(null);
      } finally {
        setLookupLoading(false);
      }
    },
    [fetchStatus],
  );

  useEffect(() => {
    void fetchLibrary();
  }, [fetchLibrary]);

  useEffect(() => {
    void fetchPreferenceProfile();
  }, [fetchPreferenceProfile]);

  useEffect(() => {
    if (!selectedVideoNo) return;
    if (!ACTIVE_STATUSES.includes(status.status)) return;

    const intervalId = window.setInterval(() => {
      void syncData(selectedVideoNo);
    }, 5000);

    return () => window.clearInterval(intervalId);
  }, [selectedVideoNo, status.status, syncData]);

  useEffect(() => {
    if (highlights.length === 0) {
      setSelectedHighlightId(null);
      return;
    }

    setSelectedHighlightId((current) => {
      if (current && highlights.some((item) => item.id === current)) {
        return current;
      }
      return highlights[0].id;
    });
  }, [highlights]);

  useEffect(() => {
    const node = chartViewportRef.current;
    if (!node) return;

    const updateWidth = () => setChartWidth(node.clientWidth);
    updateWidth();

    const observer = new ResizeObserver(() => {
      updateWidth();
    });

    observer.observe(node);
    return () => observer.disconnect();
  }, [selectedVideoNo, timeline.length, highlights.length, chartMode]);

  const duration = useMemo(() => {
    if (metadata?.duration && metadata.duration > 0) {
      return metadata.duration;
    }

    const source = timeline.length > 0 ? timeline : deriveTimeline(highlights);
    if (source.length === 0) return 1;

    return Math.max(...source.map((item) => item.endSeconds), 1);
  }, [highlights, metadata?.duration, timeline]);

  const timelineSource = useMemo(
    () => (timeline.length > 0 ? timeline : deriveTimeline(highlights)),
    [highlights, timeline],
  );

  const responsiveMaxBars = useMemo(() => {
    if (chartWidth <= 0) return 120;
    return Math.max(24, Math.floor(chartWidth / 6));
  }, [chartWidth]);

  const chartTimeline = useMemo(() => {
    if (chartMode === "DETAIL") {
      return timelineSource;
    }

    return aggregateTimelineForChart(timelineSource, responsiveMaxBars);
  }, [chartMode, responsiveMaxBars, timelineSource]);

  const chartBars = useMemo(() => {
    const source = chartTimeline;
    const maxMessages = Math.max(...source.map((item) => item.messageCount), 1);
    const maxParticipants = Math.max(
      ...source.map((item) => item.participantCount),
      1,
    );

    return source.map((item) => ({
      ...item,
      messageHeight: Math.max((item.messageCount / maxMessages) * 100, 4),
      participantHeight: Math.max(
        (item.participantCount / maxParticipants) * 100,
        4,
      ),
    }));
  }, [chartTimeline]);

  const chartMinWidth = useMemo(() => {
    if (chartMode !== "DETAIL") {
      return undefined;
    }

    return `${Math.max(chartBars.length * 10, chartWidth)}px`;
  }, [chartBars.length, chartMode, chartWidth]);

  const selectedTimelinePoint = useMemo(() => {
    const source = timelineSource;
    if (source.length === 0) return null;

    const selectedHighlight = highlights.find((item) => item.id === selectedHighlightId);
    if (!selectedHighlight) {
      return source[0];
    }

    return source.reduce((closest, current) => {
      const currentGap = Math.abs(current.startSeconds - selectedHighlight.startSeconds);
      const closestGap = Math.abs(closest.startSeconds - selectedHighlight.startSeconds);
      return currentGap < closestGap ? current : closest;
    }, source[0]);
  }, [highlights, selectedHighlightId, timelineSource]);

  const markerClusters = useMemo(
    () => buildMarkerClusters(highlights, duration),
    [duration, highlights],
  );

  const selectedCluster = useMemo(() => {
    if (markerClusters.length === 0) return null;

    const found = markerClusters.find((cluster) =>
      cluster.items.some((item) => item.id === selectedHighlightId),
    );

    return found ?? markerClusters[0];
  }, [markerClusters, selectedHighlightId]);

  const selectedClusterPoints = useMemo(() => {
    if (!selectedCluster) return [];

    const activeItem =
      selectedCluster.items.find((item) => item.id === selectedHighlightId) ??
      selectedCluster.items[0];

    return toCompactReasonTags(activeItem);
  }, [selectedCluster, selectedHighlightId]);

  const goodHighlights = useMemo(
    () =>
      highlights.filter(
        (item) => normalizeHighlightAction(highlightActions[item.id]) === "GOOD",
      ),
    [highlightActions, highlights],
  );

  const pinnedHighlights = useMemo(
    () =>
      highlights.filter(
        (item) => normalizeHighlightAction(highlightActions[item.id]) === "PIN",
      ),
    [highlightActions, highlights],
  );

  const badHighlights = useMemo(
    () =>
      highlights.filter(
        (item) => normalizeHighlightAction(highlightActions[item.id]) === "BAD",
      ),
    [highlightActions, highlights],
  );

  const rankedHighlights = useMemo(() => highlights, [highlights]);

  const filteredHighlights = useMemo(() => {
    if (highlightFilter === "PINNED") {
      return rankedHighlights.filter(
        (item) => normalizeHighlightAction(highlightActions[item.id]) === "PIN",
      );
    }

    if (highlightFilter === "GOOD") {
      return rankedHighlights.filter(
        (item) => normalizeHighlightAction(highlightActions[item.id]) === "GOOD",
      );
    }

    if (highlightFilter === "ACTIVE") {
      return rankedHighlights.filter(
        (item) => normalizeHighlightAction(highlightActions[item.id]) !== "BAD",
      );
    }

    return rankedHighlights;
  }, [highlightActions, highlightFilter, rankedHighlights]);

  const selectedHighlight = useMemo(
    () => highlights.find((item) => item.id === selectedHighlightId) ?? null,
    [highlights, selectedHighlightId],
  );

  const selectedHighlightAction = selectedHighlight
    ? normalizeHighlightAction(highlightActions[selectedHighlight.id])
    : null;

  const isAnalysisActive = ACTIVE_STATUSES.includes(status.status);

  const statusToneClass =
    status.status === "COMPLETED"
      ? "border-emerald-200 bg-emerald-50 text-emerald-900"
      : status.status === "FAILED"
        ? "border-amber-200 bg-amber-50 text-amber-900"
        : isAnalysisActive
          ? "border-indigo-200 bg-indigo-50 text-indigo-900"
          : "border-slate-200 bg-slate-50 text-slate-900";

  const workspacePrimaryLabel =
    metadata?.exists && metadata.videoNo && (selectedVideoNo === metadata.videoNo || status.status === "COMPLETED")
      ? "분석 워크스페이스 열기"
      : isAnalysisActive
        ? "분석 진행 상황 보기"
        : "분석 시작";

  const handleLookup = async () => {
    const videoNo = resolveVideoNo(videoInput);
    if (!videoNo) {
      setInlineNotice({
        tone: "warn",
        message: "VOD 번호 또는 전체 URL을 입력해 주세요.",
      });
      return;
    }

    await lookupVideo(videoNo);
  };

  const handleAnalyze = async () => {
    if (!metadata?.exists || !metadata.videoNo) {
      setInlineNotice({
        tone: "warn",
        message: "먼저 조회해서 유효한 VOD인지 확인해 주세요.",
      });
      return;
    }

    setAnalysisSubmitting(true);
    try {
      const response = await fetch(`/api/vod/${metadata.videoNo}/analyze`, {
        method: "POST",
      });

      if (!response.ok) {
        const data = (await response.json().catch(() => null)) as
          | { message?: string }
          | null;
        throw new Error(data?.message || "분석 요청에 실패했습니다.");
      }

      setSelectedVideoNo(metadata.videoNo);
      setStatus({
        videoNo: metadata.videoNo,
        status: "REQUESTED",
        message: "분석 요청이 접수되었습니다.",
      });
      setInlineNotice({
        tone: "good",
        message: "분석 요청이 접수되었습니다. 상태와 후보 목록이 자동으로 갱신됩니다.",
      });
      await syncData(metadata.videoNo);
      await fetchLibrary();
    } catch (error) {
      setInlineNotice({
        tone: "warn",
        message: error instanceof Error ? error.message : "분석 요청에 실패했습니다.",
      });
    } finally {
      setAnalysisSubmitting(false);
    }
  };

  const handleOpenAnalysis = async () => {
    if (!metadata?.exists || !metadata.videoNo) return;
    setSelectedVideoNo(metadata.videoNo);
    setInlineNotice(null);
    await syncData(metadata.videoNo);
    await fetchLibrary();
  };

  const moveToCard = (id: number) => {
    setSelectedHighlightId(id);
    if (selectedVideoNo) {
      void recordHighlightActivity(selectedVideoNo, id, "OPEN");
    }
    cardRefs.current[id]?.scrollIntoView({
      behavior: "smooth",
      block: "center",
    });
  };

  const handleHighlightAction = async (
    highlightId: number,
    actionType: "GOOD" | "PIN" | "BAD",
  ) => {
    if (!selectedVideoNo || !personalizationEnabled) {
      return;
    }

    setHighlightActions((prev) => ({
      ...prev,
      [highlightId]: actionType,
    }));

    try {
      await recordHighlightActivity(selectedVideoNo, highlightId, actionType);
      await syncData(selectedVideoNo);
      await fetchLibrary();
      await fetchPreferenceProfile();
    } catch {
      // Keep the optimistic action and retry on the next refresh.
    }
  };

  return (
    <div className="space-y-6 overflow-x-hidden">
      <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
          <div className="space-y-2">
            <div className="inline-flex items-center gap-2 rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
              <Film className="h-3.5 w-3.5" />
              1. VOD 조회
            </div>
            <h3 className="text-2xl font-black text-slate-950">
              다시보기를 찾고 편집 후보 검토를 시작하세요
            </h3>
            <p className="max-w-2xl text-sm leading-6 text-slate-600">
              VOD 번호나 링크를 조회하면 존재 여부와 기본 정보를 먼저 확인합니다. 그다음 선택한 영상의 상태를 보고 분석을 시작하거나 바로 워크스페이스로 이어서 볼 수 있습니다.
            </p>
          </div>

          <div className="flex flex-col gap-3 sm:flex-row">
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
              <input
                value={videoInput}
                onChange={(event) => setVideoInput(event.target.value)}
                placeholder="VOD 번호 또는 전체 URL 붙여넣기"
                className="w-full min-w-[280px] rounded-2xl border border-slate-200 bg-white py-3 pl-10 pr-4 text-sm text-slate-900 outline-none transition focus:border-indigo-400 focus:ring-4 focus:ring-indigo-100"
              />
            </div>
            <button
              onClick={handleLookup}
              disabled={lookupLoading}
              className="rounded-2xl bg-slate-950 px-5 py-3 text-sm font-black text-white disabled:bg-slate-300"
            >
              {lookupLoading ? "조회 중..." : "조회"}
            </button>
          </div>
        </div>

        {inlineNotice ? (
          <div
            className={`mt-4 rounded-[22px] border px-4 py-3 text-sm font-semibold ${
              inlineNotice.tone === "good"
                ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                : "border-amber-200 bg-amber-50 text-amber-700"
            }`}
          >
            {inlineNotice.message}
          </div>
        ) : null}
      </section>

      <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex items-center justify-between gap-4">
          <div>
            <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">2. 선택한 VOD</div>
            <div className="mt-2 text-xl font-black text-slate-950">조회한 영상의 메타데이터와 현재 상태</div>
          </div>
          {metadata?.exists ? (
            <div className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-xs font-black text-slate-600">
              VOD {metadata.videoNo}
            </div>
          ) : null}
        </div>

        {!metadata ? (
          <div className="mt-5 rounded-[26px] border border-dashed border-slate-300 bg-slate-50 px-5 py-10 text-sm font-semibold text-slate-500">
            먼저 다시보기를 조회하면 여기에서 선택한 영상의 상태, 메타데이터, 다음 단계가 정리됩니다.
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
                  <h4 className="text-2xl font-black text-slate-950">{metadata.title || "제목 없음"}</h4>
                  <p className="mt-2 text-sm text-slate-600">
                    {metadata.channelName || "채널명 정보 없음"} · 생성 시각 {formatDateTime(metadata.publishDateAt ?? metadata.publishDate)}
                  </p>
                </div>

                <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-1">
                  <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
                    <div className="text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">길이</div>
                    <div className="mt-1 text-sm font-black text-slate-950">{formatSeconds(metadata.duration ?? 0)}</div>
                  </div>
                  <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
                    <div className="text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">현재 상태</div>
                    <div className="mt-1 text-sm font-black text-slate-950">{status.status}</div>
                  </div>
                </div>
              </div>

              <div className={`rounded-[22px] border px-5 py-4 ${statusToneClass}`}>
                <div className="flex items-start gap-3">
                  <div className="mt-0.5">
                    {status.status === "COMPLETED" ? (
                      <CheckCircle2 className="h-5 w-5" />
                    ) : isAnalysisActive ? (
                      <LoaderCircle className="h-5 w-5 animate-spin" />
                    ) : status.status === "FAILED" ? (
                      <XCircle className="h-5 w-5" />
                    ) : (
                      <Clock3 className="h-5 w-5" />
                    )}
                  </div>
                  <div>
                    <div className="text-base font-black">{status.message || EMPTY_STATUS.message}</div>
                    <div className="mt-1 text-sm leading-6 text-slate-600">
                      {status.status === "CRAWLING"
                        ? `현재 ${status.pagesProcessed ?? 0}페이지, ${status.chatsCollected ?? 0}개 채팅을 확인했습니다.`
                        : status.status === "COMPLETED"
                          ? `완료 시각 ${formatDateTime(status.completedAt)}`
                          : isAnalysisActive
                            ? "분석 상태가 바뀌면 워크스페이스와 후보 목록이 자동으로 갱신됩니다."
                            : `상태: ${status.status}`}
                    </div>
                  </div>
                </div>
              </div>

              <div className="flex flex-wrap gap-3">
                <button
                  onClick={() =>
                    void (selectedVideoNo === metadata.videoNo || status.status === "COMPLETED" || isAnalysisActive
                      ? handleOpenAnalysis()
                      : handleAnalyze())
                  }
                  disabled={analysisSubmitting}
                  className="rounded-2xl bg-indigo-600 px-5 py-3 text-sm font-black text-white transition hover:bg-indigo-500 disabled:bg-indigo-300"
                >
                  {analysisSubmitting ? "요청 중..." : workspacePrimaryLabel}
                </button>
                <a
                  href={`https://chzzk.naver.com/video/${metadata.videoNo}`}
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

      {selectedVideoNo ? (
        <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
          <div className="flex flex-col gap-5 xl:flex-row xl:items-start xl:justify-between">
            <div>
              <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">3. 분석 워크스페이스</div>
              <h4 className="mt-2 text-2xl font-black text-slate-950">선택한 VOD의 흐름과 편집 후보를 한곳에서 확인합니다</h4>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-600">
                타임라인으로 방송 흐름을 먼저 읽고, 마커 레일로 후보 묶음을 훑은 뒤, 오른쪽 상세 패널에서 장면을 검토하고 액션을 남길 수 있습니다.
              </p>
            </div>

            <div className="grid gap-3 sm:grid-cols-3">
              <div className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3">
                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-emerald-700">좋아요</div>
                <div className="mt-1 text-lg font-black text-emerald-950">{goodHighlights.length}개</div>
                <div className="mt-1 text-xs text-emerald-700">다시 볼 장면으로 표시한 후보</div>
              </div>
              <div className="rounded-2xl border border-indigo-200 bg-indigo-50 px-4 py-3">
                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-indigo-700">편집점</div>
                <div className="mt-1 text-lg font-black text-indigo-950">{pinnedHighlights.length}개</div>
                <div className="mt-1 text-xs text-indigo-700">보관해 둔 편집 후보</div>
              </div>
              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">낮은 우선순위</div>
                <div className="mt-1 text-lg font-black text-slate-950">{badHighlights.length}개</div>
                <div className="mt-1 text-xs text-slate-500">이번 영상에서 뒤로 미룬 후보</div>
              </div>
            </div>
          </div>

          <div className="mt-6 grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
            <div className="space-y-4">
              <div className="min-w-0 rounded-[24px] border border-slate-200 bg-slate-50 p-5 overflow-hidden">
                <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">Broadcast Flow</div>
                    <div className="mt-2 text-lg font-black text-slate-950">방송 흐름 타임라인</div>
                    <div className="mt-1 text-sm text-slate-500">{Math.max(timeline.length, highlights.length)}개 구간 중 편집 후보 {highlights.length}개</div>
                  </div>

                  <div className="flex flex-wrap items-center gap-2 text-[11px] font-black tracking-[0.08em] text-slate-500">
                    <div className="inline-flex rounded-full border border-slate-200 bg-white p-1">
                      <button
                        type="button"
                        onClick={() => setChartMode("RESPONSIVE")}
                        className={`rounded-full px-3 py-1.5 transition ${chartMode === "RESPONSIVE" ? "bg-slate-900 text-white" : "text-slate-500"}`}
                      >
                        자동 요약
                      </button>
                      <button
                        type="button"
                        onClick={() => setChartMode("DETAIL")}
                        className={`rounded-full px-3 py-1.5 transition ${chartMode === "DETAIL" ? "bg-slate-900 text-white" : "text-slate-500"}`}
                      >
                        상세 보기
                      </button>
                    </div>
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
                  <span className="rounded-full border border-slate-200 bg-white px-3 py-1">00:00</span>
                  <span className="rounded-full border border-slate-200 bg-white px-3 py-1">{formatSeconds(duration)}</span>
                </div>

                <div
                  ref={chartViewportRef}
                  className={`rounded-2xl border border-slate-200 bg-white p-4 ${chartMode === "DETAIL" ? "overflow-x-auto overflow-y-hidden" : "overflow-hidden"}`}
                >
                  {chartBars.length === 0 ? (
                    <div className="flex h-[280px] items-center justify-center text-sm font-semibold text-slate-500">아직 전체 흐름 데이터가 없습니다.</div>
                  ) : (
                    <div className="relative">
                      {hoveredChartBar ? (
                        <div className="pointer-events-none absolute left-3 top-3 z-10 rounded-2xl border border-slate-200 bg-white/95 px-4 py-3 shadow-lg backdrop-blur">
                          <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">Hover Insight</div>
                          <div className="mt-1 text-sm font-black text-slate-950">
                            {formatSeconds(hoveredChartBar.startSeconds)} ~ {formatSeconds(hoveredChartBar.endSeconds)}
                          </div>
                          <div className="mt-2 flex items-center gap-3 text-sm font-bold text-slate-700">
                            <span className="inline-flex items-center gap-2 text-emerald-700"><Zap className="h-3.5 w-3.5" />채팅 {hoveredChartBar.messageCount}개</span>
                            <span className="inline-flex items-center gap-2 text-indigo-700"><Users className="h-3.5 w-3.5" />참여자 {hoveredChartBar.participantCount}명</span>
                          </div>
                        </div>
                      ) : null}

                      <div
                        className="grid h-[280px] grid-flow-col auto-cols-fr items-end gap-px"
                        style={chartMinWidth ? { minWidth: chartMinWidth } : undefined}
                        onMouseLeave={() => setHoveredChartBar(null)}
                      >
                        {chartBars.map((item) => (
                          <div
                            key={item.id}
                            className="flex h-full min-w-0 items-end gap-px"
                            title={`${formatSeconds(item.startSeconds)} ~ ${formatSeconds(item.endSeconds)} · 채팅 ${item.messageCount}개 · 참여자 ${item.participantCount}명`}
                            onMouseEnter={() =>
                              setHoveredChartBar({
                                startSeconds: item.startSeconds,
                                endSeconds: item.endSeconds,
                                messageCount: item.messageCount,
                                participantCount: item.participantCount,
                              })
                            }
                          >
                            <div className="w-1/2 rounded-t bg-emerald-300/70" style={{ height: `${item.messageHeight}%` }} />
                            <div className="w-1/2 rounded-t bg-indigo-400/80" style={{ height: `${item.participantHeight}%` }} />
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>

                <div className="mt-3 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
                  초록 막대는 채팅량, 파란 막대는 참여자 수를 뜻합니다.
                  {chartMode === "DETAIL"
                    ? " 상세 보기에서는 긴 방송도 가로 스크롤로 끝까지 확인할 수 있어요."
                    : " 자동 요약에서는 화면 폭에 맞춰 구간을 묶어서 보여줍니다."}
                </div>

                {selectedTimelinePoint ? (
                  <div className="mt-3 grid gap-3 sm:grid-cols-3">
                    <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
                      <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">선택 구간</div>
                      <div className="mt-1 text-lg font-black text-slate-950">{formatSeconds(selectedTimelinePoint.startSeconds)}</div>
                    </div>
                    <div className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3">
                      <div className="text-[11px] font-black uppercase tracking-[0.18em] text-emerald-700">채팅량</div>
                      <div className="mt-1 text-lg font-black text-emerald-900">{selectedTimelinePoint.messageCount}개</div>
                    </div>
                    <div className="rounded-2xl border border-indigo-200 bg-indigo-50 px-4 py-3">
                      <div className="text-[11px] font-black uppercase tracking-[0.18em] text-indigo-700">참여자 수</div>
                      <div className="mt-1 text-lg font-black text-indigo-900">{selectedTimelinePoint.participantCount}명</div>
                    </div>
                  </div>
                ) : null}
              </div>

              <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-5">
                <div className="mb-3 flex items-center justify-between">
                  <div>
                    <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">Highlight Rail</div>
                    <div className="mt-1 text-lg font-black text-slate-950">후보 마커 레일</div>
                  </div>
                  <div className="text-xs font-semibold text-slate-500">
                    {selectedCluster ? `${selectedCluster.items.length}개 후보 묶음` : "하이라이트를 선택해 주세요."}
                  </div>
                </div>

                {markerClusters.length > 0 ? (
                  <>
                    <div className="relative h-[84px] overflow-hidden rounded-2xl border border-slate-200 bg-white px-4 py-5">
                      <div className="absolute left-4 right-4 top-[36px] h-[8px] rounded-full bg-slate-200" />
                      {markerClusters.map((cluster) => {
                        const lead = cluster.items[0];
                        const selected = cluster.items.some((item) => item.id === selectedHighlightId);

                        return (
                          <button
                            key={cluster.id}
                            type="button"
                            onClick={() => moveToCard(lead.id)}
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
                            <div className="absolute left-1/2 top-[52px] -translate-x-1/2 whitespace-nowrap text-[10px] font-black tracking-[0.12em] text-slate-500">{formatSeconds(lead.startSeconds)}</div>
                            {cluster.items.length > 1 ? (
                              <div className="absolute left-1/2 top-[2px] -translate-x-1/2 rounded-full border border-rose-200 bg-white px-2 py-0.5 text-[10px] font-black text-rose-600 shadow-sm">+{cluster.items.length}</div>
                            ) : null}
                          </button>
                        );
                      })}
                    </div>

                    {selectedCluster ? (
                      <div className="mt-4 rounded-2xl border border-rose-200 bg-rose-50 p-4">
                        <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                          <div>
                            <div className="text-[11px] font-black tracking-[0.18em] text-rose-600">
                              {selectedCluster.items.length > 1 ? "가까운 후보 묶음" : "선택한 편집 후보"}
                            </div>
                            <div className="mt-1 text-base font-black text-slate-950">
                              {selectedCluster.items[0].reactionLabel || categoryLabel[selectedCluster.items[0].category] || "편집 후보"}
                            </div>
                            <div className="mt-3 flex max-w-xl flex-wrap gap-2">
                              {selectedClusterPoints.map((point) => (
                                <span key={point} className="rounded-full border border-rose-200 bg-white px-3 py-2 text-xs font-bold leading-5 text-slate-700">{point}</span>
                              ))}
                            </div>
                          </div>

                          <div className="text-xs font-semibold text-slate-500">
                            {selectedCluster.items.length > 1
                              ? "시간이 가까운 후보들은 묶음으로 보여드려요."
                              : "마커를 누르면 오른쪽 상세 패널이 해당 장면으로 맞춰집니다."}
                          </div>
                        </div>

                        <div className="mt-3 flex flex-wrap gap-2">
                          {selectedCluster.items.map((item) => {
                            const active = selectedHighlightId === item.id;

                            return (
                              <button
                                key={item.id}
                                type="button"
                                onClick={() => moveToCard(item.id)}
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
                    <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">Selected Highlight</div>
                    <div className="mt-2 text-lg font-black text-slate-950">선택한 장면 상세</div>
                  </div>
                  <div className="text-xs font-semibold text-slate-500">{selectedHighlight ? `${formatSeconds(selectedHighlight.startSeconds)} 기준` : "후보를 선택해 주세요."}</div>
                </div>

                {selectedHighlight ? (
                  (() => {
                    const spotlightPoints = toReadablePoints(selectedHighlight.description, selectedHighlight.reasonSummary).slice(0, 3);

                    return (
                      <div className="space-y-4">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1.5 text-[11px] font-black tracking-[0.18em] text-indigo-700">
                            {selectedHighlight.reactionLabel || categoryLabel[selectedHighlight.category] || "편집 후보"}
                          </span>
                          <span className="inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-3 py-1.5 text-[11px] font-black tracking-[0.18em] text-amber-700">
                            <Zap className="h-3.5 w-3.5" />추천 강도 {selectedHighlight.highlightScore.toFixed(1)}
                          </span>
                        </div>

                        <div className="grid gap-3 sm:grid-cols-2">
                          <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                            <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">구간</div>
                            <div className="mt-1 text-lg font-black text-slate-950">{formatSeconds(selectedHighlight.startSeconds)} ~ {formatSeconds(selectedHighlight.endSeconds)}</div>
                          </div>
                          <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                            <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">우선순위</div>
                            <div className="mt-1 text-lg font-black text-slate-950">
                              {selectedHighlightAction === "PIN"
                                ? "편집점 보관"
                                : selectedHighlightAction === "GOOD"
                                  ? "다시 볼 후보"
                                  : selectedHighlightAction === "BAD"
                                    ? "낮은 우선순위"
                                    : "추천 후보"}
                            </div>
                          </div>
                        </div>

                        <div className="flex flex-wrap gap-2">
                          <button
                            type="button"
                            onClick={() => void handleHighlightAction(selectedHighlight.id, "GOOD")}
                            className={`inline-flex items-center gap-1 rounded-full border px-3 py-2 text-xs font-black transition ${selectedHighlightAction === "GOOD" ? "border-emerald-300 bg-emerald-50 text-emerald-700" : "border-slate-200 bg-white text-slate-600"}`}
                          >
                            <CheckCircle2 className="h-3.5 w-3.5" />좋아요
                          </button>
                          <button
                            type="button"
                            onClick={() => void handleHighlightAction(selectedHighlight.id, "PIN")}
                            className={`inline-flex items-center gap-1 rounded-full border px-3 py-2 text-xs font-black transition ${selectedHighlightAction === "PIN" ? "border-indigo-300 bg-indigo-50 text-indigo-700" : "border-slate-200 bg-white text-slate-600"}`}
                          >
                            <Pin className="h-3.5 w-3.5" />편집점
                          </button>
                          <button
                            type="button"
                            onClick={() => void handleHighlightAction(selectedHighlight.id, "BAD")}
                            className={`inline-flex items-center gap-1 rounded-full border px-3 py-2 text-xs font-black transition ${selectedHighlightAction === "BAD" ? "border-slate-300 bg-slate-100 text-slate-700" : "border-slate-200 bg-white text-slate-600"}`}
                          >
                            <XCircle className="h-3.5 w-3.5" />별로예요
                          </button>
                        </div>

                        <div>
                          <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">추천 이유</div>
                          <div className="mt-2 flex flex-wrap gap-2">
                            {(spotlightPoints.length > 0 ? spotlightPoints : toCompactReasonTags(selectedHighlight)).map((point) => (
                              <span key={point} className="rounded-full border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-bold leading-5 text-slate-700">{point}</span>
                            ))}
                          </div>
                        </div>

                        <div className="flex flex-wrap gap-2 text-xs font-bold text-slate-600">
                          {typeof selectedHighlight.intensityScore === "number" ? <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">반응 밀집도 {selectedHighlight.intensityScore.toFixed(1)}</span> : null}
                          {typeof selectedHighlight.transitionScore === "number" && selectedHighlight.transitionScore > 0 ? <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">흐름 전환 {selectedHighlight.transitionScore.toFixed(1)}</span> : null}
                          {typeof selectedHighlight.editabilityScore === "number" ? <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">편집 용이도 {selectedHighlight.editabilityScore.toFixed(1)}</span> : null}
                        </div>

                        <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                          <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">대표 채팅</div>
                          <p className="mt-2 text-sm leading-6 text-slate-700">"{selectedHighlight.topMessage || "대표 채팅이 없는 구간입니다."}"</p>
                        </div>
                      </div>
                    );
                  })()
                ) : (
                  <div className={`rounded-[22px] border px-5 py-4 ${statusToneClass}`}>
                    <div className="flex items-start gap-3">
                      <div className="mt-0.5">
                        {isAnalysisActive ? <LoaderCircle className="h-5 w-5 animate-spin" /> : status.status === "COMPLETED" ? <CheckCircle2 className="h-5 w-5" /> : <Clock3 className="h-5 w-5" />}
                      </div>
                      <div>
                        <div className="text-base font-black">
                          {isAnalysisActive
                            ? "편집 후보를 생성하는 중입니다."
                            : status.status === "COMPLETED"
                              ? "분석은 완료됐지만 표시할 후보가 아직 없습니다."
                              : "후보를 보려면 분석을 시작하거나 기존 분석을 열어 주세요."}
                        </div>
                        <div className="mt-1 text-sm leading-6 text-slate-600">{status.message || EMPTY_STATUS.message}</div>
                      </div>
                    </div>
                  </div>
                )}
              </div>

              <div className="min-w-0 rounded-[24px] border border-slate-200 bg-slate-50 p-5">
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">Highlights</div>
                    <div className="mt-2 text-lg font-black text-slate-950">하이라이트 목록</div>
                  </div>
                  <div className="text-xs font-semibold text-slate-500">{filteredHighlights.length} / {highlights.length}개 표시</div>
                </div>

                <div className="mb-3 flex flex-wrap gap-2">
                  {[
                    { key: "ACTIVE", label: "추천만" },
                    { key: "ALL", label: "전체" },
                    { key: "PINNED", label: "편집점" },
                    { key: "GOOD", label: "좋아요" },
                  ].map((filter) => {
                    const active = highlightFilter === filter.key;
                    return (
                      <button
                        key={filter.key}
                        type="button"
                        onClick={() => setHighlightFilter(filter.key as HighlightFilter)}
                        className={`rounded-full border px-3 py-2 text-xs font-black transition ${active ? "border-slate-900 bg-slate-900 text-white" : "border-slate-200 bg-white text-slate-600"}`}
                      >
                        {filter.label}
                      </button>
                    );
                  })}
                </div>

                <div className="max-h-[720px] space-y-3 overflow-y-auto pr-1">
                  {filteredHighlights.length === 0 ? (
                    <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-5 py-10 text-center">
                      <Clock3 className="mx-auto h-8 w-8 text-slate-400" />
                      <div className="mt-3 text-base font-black text-slate-900">표시할 편집 후보가 없습니다.</div>
                      <div className="mt-2 text-sm text-slate-500">필터를 바꾸거나 분석이 끝난 뒤 다시 확인해 주세요.</div>
                    </div>
                  ) : (
                    filteredHighlights.map((item) => {
                      const active = selectedHighlightId === item.id;
                      const currentAction = normalizeHighlightAction(highlightActions[item.id]);

                      return (
                        <div
                          key={item.id}
                          ref={(element) => {
                            cardRefs.current[item.id] = element;
                          }}
                          onMouseEnter={() => setSelectedHighlightId(item.id)}
                          className={`rounded-[22px] border p-4 transition ${active ? "border-rose-300 bg-rose-50/70 shadow-sm" : "border-slate-200 bg-white"}`}
                        >
                          <div className="flex items-start justify-between gap-3">
                            <div>
                              <div className="text-sm font-black text-slate-950">{formatSeconds(item.startSeconds)} ~ {formatSeconds(item.endSeconds)}</div>
                              <div className="mt-1 flex flex-wrap gap-2">
                                <span className="rounded-full border border-indigo-200 bg-indigo-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
                                  {item.reactionLabel || categoryLabel[item.category] || "편집 후보"}
                                </span>
                                <span className="rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-amber-700">강도 {item.highlightScore.toFixed(1)}</span>
                              </div>
                            </div>

                            <button
                              type="button"
                              onClick={() => moveToCard(item.id)}
                              className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1.5 text-[11px] font-black text-slate-600 transition hover:bg-slate-100"
                            >
                              상세 보기
                            </button>
                          </div>

                          <div className="mt-3 flex flex-wrap gap-2 text-xs font-bold text-slate-600">
                            {toCompactReasonTags(item).slice(0, 3).map((point) => (
                              <span key={point} className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">{point}</span>
                            ))}
                          </div>

                          <p className="mt-3 text-sm leading-6 text-slate-700">"{item.topMessage || "대표 채팅이 없는 구간입니다."}"</p>

                          <div className="mt-3 flex flex-wrap gap-2">
                            <button
                              type="button"
                              onClick={() => void handleHighlightAction(item.id, "GOOD")}
                              className={`inline-flex items-center gap-1 rounded-full border px-3 py-1.5 text-[11px] font-black transition ${currentAction === "GOOD" ? "border-emerald-300 bg-emerald-50 text-emerald-700" : "border-slate-200 bg-white text-slate-600"}`}
                            >
                              <CheckCircle2 className="h-3.5 w-3.5" />좋아요
                            </button>
                            <button
                              type="button"
                              onClick={() => void handleHighlightAction(item.id, "PIN")}
                              className={`inline-flex items-center gap-1 rounded-full border px-3 py-1.5 text-[11px] font-black transition ${currentAction === "PIN" ? "border-indigo-300 bg-indigo-50 text-indigo-700" : "border-slate-200 bg-white text-slate-600"}`}
                            >
                              <Pin className="h-3.5 w-3.5" />편집점
                            </button>
                            <button
                              type="button"
                              onClick={() => void handleHighlightAction(item.id, "BAD")}
                              className={`inline-flex items-center gap-1 rounded-full border px-3 py-1.5 text-[11px] font-black transition ${currentAction === "BAD" ? "border-slate-300 bg-slate-100 text-slate-700" : "border-slate-200 bg-white text-slate-600"}`}
                            >
                              <XCircle className="h-3.5 w-3.5" />별로예요
                            </button>
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
      ) : metadata?.exists ? (
        <section className="rounded-[30px] border border-indigo-200 bg-indigo-50 p-5">
          <div className="flex items-start gap-3">
            <Film className="mt-0.5 h-5 w-5 text-indigo-700" />
            <div>
              <div className="text-sm font-black text-slate-950">선택한 VOD 기준으로 워크스페이스가 열립니다</div>
              <div className="mt-1 text-sm leading-6 text-slate-600">위 카드의 기본 CTA를 누르면 이 영상의 타임라인, 마커 레일, 하이라이트 상세 패널을 바로 이어서 볼 수 있습니다.</div>
            </div>
          </div>
        </section>
      ) : null}

      <section className="grid gap-6 xl:grid-cols-[1.15fr_0.85fr]">
        <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
          <div className="mb-5 flex items-center justify-between">
            <div>
              <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">My VOD Library</div>
              <div className="mt-2 text-xl font-black text-slate-950">최근에 본 다시보기</div>
            </div>
            {personalizationEnabled ? (
              <button
                type="button"
                onClick={() => void fetchLibrary()}
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
          ) : libraryLoading ? (
            <div className="rounded-[24px] border border-slate-200 bg-slate-50 px-5 py-8 text-sm font-semibold text-slate-500">최근 VOD를 불러오는 중입니다.</div>
          ) : library.length === 0 ? (
            <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 px-5 py-8 text-sm font-semibold text-slate-500">아직 확인한 VOD가 없습니다. 조회하거나 분석한 VOD가 여기에 쌓입니다.</div>
          ) : (
            <div className="grid gap-3 lg:grid-cols-2">
              {library.slice(0, 6).map((item) => {
                const statusLabel = item.status === "READY" ? "분석 완료" : item.status === "ANALYZING" ? "분석 중" : "최근 열람";

                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => void lookupVideo(item.videoNo)}
                    className="rounded-[24px] border border-slate-200 bg-slate-50 p-4 text-left transition hover:border-indigo-300 hover:bg-indigo-50/40"
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="text-sm font-black text-slate-950">VOD {item.videoNo}</div>
                      <span className="rounded-full border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-black text-slate-600">{statusLabel}</span>
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
            <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">Preference Profile</div>
            <div className="mt-2 text-xl font-black text-slate-950">지금 반영 중인 내 취향</div>
          </div>

          <div className="space-y-4">
            {!personalizationEnabled ? (
              <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-4 text-sm font-semibold text-slate-500">
                로그인 후 좋아요 / 편집점 / 별로예요 기록이 쌓이면 이 영역에서 선호 카테고리와 반응을 보여줍니다.
              </div>
            ) : null}
            <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
              <div className="text-xs font-black uppercase tracking-[0.18em] text-slate-500">선호 카테고리</div>
              <div className="mt-3 flex flex-wrap gap-2">
                {!personalizationEnabled || preferenceProfile.topCategories.length === 0 ? (
                  <span className="text-sm font-semibold text-slate-500">아직 활동 데이터가 부족해서 기본 정렬로 보여주고 있어요.</span>
                ) : (
                  preferenceProfile.topCategories.map((category) => (
                    <span key={category} className="rounded-full border border-indigo-200 bg-indigo-50 px-3 py-2 text-xs font-black text-indigo-700">{category}</span>
                  ))
                )}
              </div>
            </div>

            <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
              <div className="text-xs font-black uppercase tracking-[0.18em] text-slate-500">선호 반응</div>
              <div className="mt-3 flex flex-wrap gap-2">
                {!personalizationEnabled || preferenceProfile.topReactionLabels.length === 0 ? (
                  <span className="text-sm font-semibold text-slate-500">좋아요와 별로예요 이력이 쌓이면 여기에 반영돼요.</span>
                ) : (
                  preferenceProfile.topReactionLabels.map((label) => (
                    <span key={label} className="rounded-full border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-black text-rose-700">{label}</span>
                  ))
                )}
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
