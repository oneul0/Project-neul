import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type Dispatch,
  type MutableRefObject,
  type SetStateAction,
} from "react";
import {
  ACTIVE_STATUSES,
  EMPTY_PREFERENCE_PROFILE,
  EMPTY_STATUS,
  aggregateTimelineForChart,
  buildActivityMap,
  buildLookupState,
  buildMarkerClusters,
  buildResultsEmptyState,
  buildSelectedVodState,
  copyText,
  deriveTimeline,
  formatSeconds,
  getStatusToneClass,
  getWorkspacePrimaryLabel,
  normalizeHighlightAction,
  parsePreferenceProfile,
  resolveVideoNo,
  toCompactReasonTags,
  type BoardEmptyState,
  type BoardStateCard,
  type ChartHoverCard,
  type ChartMode,
  type HighlightFilter,
  type InlineNotice,
  type MarkerCluster,
  type UserVodLibraryEntry,
  type UserVodPreferenceProfile,
  type VodAnalysisStatus,
  type VodHighlight,
  type VodMetadata,
  type VodTimelinePoint,
} from "./shared";

export interface VodHighlightBoardViewModel {
  videoInput: string;
  setVideoInput: Dispatch<SetStateAction<string>>;
  metadata: VodMetadata | null;
  status: VodAnalysisStatus;
  highlights: VodHighlight[];
  timeline: VodTimelinePoint[];
  library: UserVodLibraryEntry[];
  libraryLoading: boolean;
  highlightActions: Record<number, string>;
  preferenceProfile: UserVodPreferenceProfile;
  selectedVideoNo: string | null;
  selectedHighlightId: number | null;
  setSelectedHighlightId: Dispatch<SetStateAction<number | null>>;
  lookupLoading: boolean;
  analysisSubmitting: boolean;
  highlightFilter: HighlightFilter;
  setHighlightFilter: Dispatch<SetStateAction<HighlightFilter>>;
  chartMode: ChartMode;
  setChartMode: Dispatch<SetStateAction<ChartMode>>;
  hoveredChartBar: ChartHoverCard | null;
  setHoveredChartBar: Dispatch<SetStateAction<ChartHoverCard | null>>;
  inlineNotice: InlineNotice | null;
  copiedHighlightId: number | null;
  cardRefs: MutableRefObject<Record<number, HTMLDivElement | null>>;
  chartViewportRef: MutableRefObject<HTMLDivElement | null>;
  duration: number;
  chartBars: Array<
    VodTimelinePoint & {
      messageHeight: number;
      participantHeight: number;
    }
  >;
  chartMinWidth: string | undefined;
  selectedTimelinePoint: VodTimelinePoint | null;
  markerClusters: MarkerCluster[];
  selectedCluster: MarkerCluster | null;
  selectedClusterPoints: string[];
  goodHighlights: VodHighlight[];
  pinnedHighlights: VodHighlight[];
  badHighlights: VodHighlight[];
  filteredHighlights: VodHighlight[];
  selectedHighlight: VodHighlight | null;
  selectedHighlightAction: string | null;
  isAnalysisActive: boolean;
  hasExistingResults: boolean;
  statusToneClass: string;
  workspacePrimaryLabel: string;
  lookupState: BoardStateCard;
  selectedVodState: BoardStateCard;
  resultsEmptyState: BoardEmptyState;
  fetchLibrary: () => Promise<void>;
  lookupVideo: (videoNo: string) => Promise<void>;
  handleLookup: () => Promise<void>;
  handleAnalyze: () => Promise<void>;
  handleOpenAnalysis: () => Promise<void>;
  moveToCard: (id: number) => void;
  handleHighlightAction: (
    highlightId: number,
    actionType: "GOOD" | "PIN" | "BAD",
  ) => Promise<void>;
  handleCopyHighlightTimecode: (
    highlight: Pick<VodHighlight, "id" | "startSeconds" | "endSeconds">,
  ) => Promise<void>;
}

export function useVodHighlightBoard(
  personalizationEnabled = false,
): VodHighlightBoardViewModel {
  const [videoInput, setVideoInput] = useState("");
  const [metadata, setMetadata] = useState<VodMetadata | null>(null);
  const [status, setStatus] = useState<VodAnalysisStatus>(EMPTY_STATUS);
  const [highlights, setHighlights] = useState<VodHighlight[]>([]);
  const [timeline, setTimeline] = useState<VodTimelinePoint[]>([]);
  const [library, setLibrary] = useState<UserVodLibraryEntry[]>([]);
  const [libraryLoading, setLibraryLoading] = useState(true);
  const [highlightActions, setHighlightActions] = useState<Record<number, string>>({});
  const [preferenceProfile, setPreferenceProfile] = useState<UserVodPreferenceProfile>(
    EMPTY_PREFERENCE_PROFILE,
  );
  const [selectedVideoNo, setSelectedVideoNo] = useState<string | null>(null);
  const [selectedHighlightId, setSelectedHighlightId] = useState<number | null>(null);
  const [lookupLoading, setLookupLoading] = useState(false);
  const [analysisSubmitting, setAnalysisSubmitting] = useState(false);
  const [highlightFilter, setHighlightFilter] = useState<HighlightFilter>("ACTIVE");
  const [chartMode, setChartMode] = useState<ChartMode>("RESPONSIVE");
  const [chartWidth, setChartWidth] = useState(0);
  const [hoveredChartBar, setHoveredChartBar] = useState<ChartHoverCard | null>(null);
  const [inlineNotice, setInlineNotice] = useState<InlineNotice | null>(null);
  const [copiedHighlightId, setCopiedHighlightId] = useState<number | null>(null);

  const cardRefs = useRef<Record<number, HTMLDivElement | null>>({});
  const chartViewportRef = useRef<HTMLDivElement | null>(null);
  const copiedHighlightResetRef = useRef<number | null>(null);

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
        return;
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
      setPreferenceProfile(EMPTY_PREFERENCE_PROFILE);
      return;
    }

    try {
      const response = await fetch("/api/me/vod-preferences", {
        cache: "no-store",
      });

      if (!response.ok) {
        setPreferenceProfile(EMPTY_PREFERENCE_PROFILE);
        return;
      }

      const data = await response.json();
      setPreferenceProfile(parsePreferenceProfile(data));
    } catch {
      setPreferenceProfile(EMPTY_PREFERENCE_PROFILE);
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
      const activities = Array.isArray(activityResponse) ? activityResponse : [];

      setStatus(nextStatus);
      setHighlights(nextHighlights);
      setTimeline(nextTimeline.length > 0 ? nextTimeline : deriveTimeline(nextHighlights));
      setHighlightActions(buildActivityMap(activities));
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
    return () => {
      if (copiedHighlightResetRef.current) {
        window.clearTimeout(copiedHighlightResetRef.current);
      }
    };
  }, []);

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
    const maxMessages = Math.max(...chartTimeline.map((item) => item.messageCount), 1);
    const maxParticipants = Math.max(
      ...chartTimeline.map((item) => item.participantCount),
      1,
    );

    return chartTimeline.map((item) => ({
      ...item,
      messageHeight: Math.max((item.messageCount / maxMessages) * 100, 4),
      participantHeight: Math.max((item.participantCount / maxParticipants) * 100, 4),
    }));
  }, [chartTimeline]);

  const chartMinWidth = useMemo(() => {
    if (chartMode !== "DETAIL") {
      return undefined;
    }

    return `${Math.max(chartBars.length * 10, chartWidth)}px`;
  }, [chartBars.length, chartMode, chartWidth]);

  const selectedTimelinePoint = useMemo(() => {
    if (timelineSource.length === 0) return null;

    const selectedHighlight = highlights.find((item) => item.id === selectedHighlightId);
    if (!selectedHighlight) {
      return timelineSource[0];
    }

    return timelineSource.reduce((closest, current) => {
      const currentGap = Math.abs(current.startSeconds - selectedHighlight.startSeconds);
      const closestGap = Math.abs(closest.startSeconds - selectedHighlight.startSeconds);
      return currentGap < closestGap ? current : closest;
    }, timelineSource[0]);
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

  const filteredHighlights = useMemo(() => {
    if (highlightFilter === "PINNED") {
      return highlights.filter(
        (item) => normalizeHighlightAction(highlightActions[item.id]) === "PIN",
      );
    }

    if (highlightFilter === "GOOD") {
      return highlights.filter(
        (item) => normalizeHighlightAction(highlightActions[item.id]) === "GOOD",
      );
    }

    if (highlightFilter === "ACTIVE") {
      return highlights.filter(
        (item) => normalizeHighlightAction(highlightActions[item.id]) !== "BAD",
      );
    }

    return highlights;
  }, [highlightActions, highlightFilter, highlights]);

  const selectedHighlight = useMemo(
    () => highlights.find((item) => item.id === selectedHighlightId) ?? null,
    [highlights, selectedHighlightId],
  );

  const selectedHighlightAction = selectedHighlight
    ? normalizeHighlightAction(highlightActions[selectedHighlight.id])
    : null;

  const isAnalysisActive = ACTIVE_STATUSES.includes(status.status);
  const hasLoadedResults = highlights.length > 0 || timeline.length > 0;
  const hasExistingResults = status.status === "COMPLETED" || hasLoadedResults;

  const statusToneClass = getStatusToneClass(status, isAnalysisActive);
  const workspacePrimaryLabel = getWorkspacePrimaryLabel({
    metadata,
    selectedVideoNo,
    hasExistingResults,
    isAnalysisActive,
  });
  const lookupState = buildLookupState({
    lookupLoading,
    metadata,
    hasExistingResults,
    selectedVideoNo,
  });
  const selectedVodState = buildSelectedVodState({
    metadata,
    status,
    selectedVideoNo,
    highlightsLength: highlights.length,
    hasExistingResults,
  });
  const resultsEmptyState = buildResultsEmptyState({
    status,
    isAnalysisActive,
    highlightsLength: highlights.length,
    filteredHighlightsLength: filteredHighlights.length,
    hasExistingResults,
  });

  const handleLookup = useCallback(async () => {
    const videoNo = resolveVideoNo(videoInput);
    if (!videoNo) {
      setInlineNotice({
        tone: "warn",
        message: "VOD 번호 또는 전체 URL을 입력해 주세요.",
      });
      return;
    }

    await lookupVideo(videoNo);
  }, [lookupVideo, videoInput]);

  const handleAnalyze = useCallback(async () => {
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
  }, [fetchLibrary, metadata, syncData]);

  const handleOpenAnalysis = useCallback(async () => {
    if (!metadata?.exists || !metadata.videoNo) return;
    setSelectedVideoNo(metadata.videoNo);
    setInlineNotice(null);
    await syncData(metadata.videoNo);
    await fetchLibrary();
  }, [fetchLibrary, metadata, syncData]);

  const moveToCard = useCallback(
    (id: number) => {
      setSelectedHighlightId(id);
      if (selectedVideoNo) {
        void recordHighlightActivity(selectedVideoNo, id, "OPEN");
      }
      cardRefs.current[id]?.scrollIntoView({
        behavior: "smooth",
        block: "center",
      });
    },
    [recordHighlightActivity, selectedVideoNo],
  );

  const handleHighlightAction = useCallback(
    async (highlightId: number, actionType: "GOOD" | "PIN" | "BAD") => {
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
        return;
      }
    },
    [fetchLibrary, fetchPreferenceProfile, personalizationEnabled, recordHighlightActivity, selectedVideoNo, syncData],
  );

  const handleCopyHighlightTimecode = useCallback(
    async (highlight: Pick<VodHighlight, "id" | "startSeconds" | "endSeconds">) => {
      const timecode = formatSeconds(highlight.startSeconds);

      try {
        await copyText(timecode);
        setInlineNotice(null);
        setCopiedHighlightId(highlight.id);
        if (copiedHighlightResetRef.current) {
          window.clearTimeout(copiedHighlightResetRef.current);
        }
        copiedHighlightResetRef.current = window.setTimeout(() => {
          setCopiedHighlightId((current) =>
            current === highlight.id ? null : current,
          );
        }, 2400);
      } catch {
        setInlineNotice({
          tone: "warn",
          message: "타임코드 복사에 실패했습니다. 다시 시도해 주세요.",
        });
      }
    },
    [],
  );

  return {
    videoInput,
    setVideoInput,
    metadata,
    status,
    highlights,
    timeline,
    library,
    libraryLoading,
    highlightActions,
    preferenceProfile,
    selectedVideoNo,
    selectedHighlightId,
    setSelectedHighlightId,
    lookupLoading,
    analysisSubmitting,
    highlightFilter,
    setHighlightFilter,
    chartMode,
    setChartMode,
    hoveredChartBar,
    setHoveredChartBar,
    inlineNotice,
    copiedHighlightId,
    cardRefs,
    chartViewportRef,
    duration,
    chartBars,
    chartMinWidth,
    selectedTimelinePoint,
    markerClusters,
    selectedCluster,
    selectedClusterPoints,
    goodHighlights,
    pinnedHighlights,
    badHighlights,
    filteredHighlights,
    selectedHighlight,
    selectedHighlightAction,
    isAnalysisActive,
    hasExistingResults,
    statusToneClass,
    workspacePrimaryLabel,
    lookupState,
    selectedVodState,
    resultsEmptyState,
    fetchLibrary,
    lookupVideo,
    handleLookup,
    handleAnalyze,
    handleOpenAnalysis,
    moveToCard,
    handleHighlightAction,
    handleCopyHighlightTimecode,
  };
}
