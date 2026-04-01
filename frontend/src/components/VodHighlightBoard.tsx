"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  CheckCircle2,
  Clock3,
  Film,
  LoaderCircle,
  Search,
  Users,
  Zap,
} from "lucide-react";

type VodStatus =
  | "IDLE"
  | "REQUESTED"
  | "CRAWLING"
  | "WAITING"
  | "ANALYZING"
  | "COMPLETED"
  | "FAILED";

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

interface HighlightMarker extends VodHighlight {
  left: number;
}

interface MarkerCluster {
  id: string;
  left: number;
  items: HighlightMarker[];
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

const categoryLabel: Record<string, string> = {
  LAUGH: "웃음",
  WONDER: "놀람",
  HYPE: "고조",
  TENSION: "긴장",
  HOT_MOMENT: "핫 모먼트",
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
    highlight.category === "WONDER"
  ) {
    tags.add("놀라서 반응이 커진 장면");
  }
  if (
    source.includes("웃음") ||
    highlight.category === "LAUGH" ||
    (highlight.reactionLabel ?? "").includes("웃")
  ) {
    tags.add("웃음이 터진 장면");
  }
  if (
    source.includes("고조") ||
    source.includes("열기") ||
    highlight.category === "HYPE"
  ) {
    tags.add("분위기가 달아오른 장면");
  }
  if (
    source.includes("긴장") ||
    source.includes("몰입") ||
    highlight.category === "TENSION"
  ) {
    tags.add("긴장감이 높은 장면");
  }
  if (source.includes("흐름") || source.includes("전환") || source.includes("직전 구간")) {
    tags.add("분위기가 바뀌는 장면");
  }
  if (source.includes("짧게 잘라") || source.includes("하이라이트로 쓰기 좋은")) {
    tags.add("짧게 잘라 쓰기 좋은 장면");
  }
  if (source.includes("반응 강도") || source.includes("먼저 확인")) {
    tags.add("먼저 확인할 장면");
  }

  if (typeof highlight.intensityScore === "number" && highlight.intensityScore >= 7) {
    tags.add("반응이 크게 몰린 장면");
  }
  if (typeof highlight.transitionScore === "number" && highlight.transitionScore >= 4) {
    tags.add("전환점으로 보기 좋은 장면");
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

export default function VodHighlightBoard() {
  const [videoInput, setVideoInput] = useState("");
  const [metadata, setMetadata] = useState<VodMetadata | null>(null);
  const [status, setStatus] = useState<VodAnalysisStatus>(EMPTY_STATUS);
  const [highlights, setHighlights] = useState<VodHighlight[]>([]);
  const [timeline, setTimeline] = useState<VodTimelinePoint[]>([]);
  const [selectedVideoNo, setSelectedVideoNo] = useState<string | null>(null);
  const [selectedHighlightId, setSelectedHighlightId] = useState<number | null>(
    null,
  );
  const [lookupLoading, setLookupLoading] = useState(false);
  const [analysisSubmitting, setAnalysisSubmitting] = useState(false);

  const cardRefs = useRef<Record<number, HTMLDivElement | null>>({});

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

  const syncData = useCallback(
    async (videoNo: string) => {
      const [nextStatus, highlightsResponse, timelineResponse] =
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
    },
    [fetchStatus],
  );

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

  const duration = useMemo(() => {
    if (metadata?.duration && metadata.duration > 0) {
      return metadata.duration;
    }

    const source = timeline.length > 0 ? timeline : deriveTimeline(highlights);
    if (source.length === 0) return 1;

    return Math.max(...source.map((item) => item.endSeconds), 1);
  }, [highlights, metadata?.duration, timeline]);

  const chartBars = useMemo(() => {
    const source = timeline.length > 0 ? timeline : deriveTimeline(highlights);
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
  }, [highlights, timeline]);

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

  const handleLookup = async () => {
    const videoNo = resolveVideoNo(videoInput);
    if (!videoNo) {
      alert("VOD 번호 또는 전체 URL을 입력해 주세요.");
      return;
    }

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
    } finally {
      setLookupLoading(false);
    }
  };

  const handleAnalyze = async () => {
    if (!metadata?.exists || !metadata.videoNo) {
      alert("먼저 조회로 유효한 VOD를 확인해 주세요.");
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
      await syncData(metadata.videoNo);
    } catch (error) {
      alert(
        error instanceof Error ? error.message : "분석 요청에 실패했습니다.",
      );
    } finally {
      setAnalysisSubmitting(false);
    }
  };

  const handleOpenAnalysis = async () => {
    if (!metadata?.exists || !metadata.videoNo) return;
    setSelectedVideoNo(metadata.videoNo);
    await syncData(metadata.videoNo);
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

  return (
    <div className="space-y-6">
      <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
          <div className="space-y-2">
            <div className="inline-flex items-center gap-2 rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
              <Film className="h-3.5 w-3.5" />
              VOD 편집 후보 탐색
            </div>
            <h3 className="text-2xl font-black text-slate-950">
              다시보기를 확인하고 편집 포인트를 빠르게 찾으세요
            </h3>
            <p className="max-w-2xl text-sm leading-6 text-slate-600">
              조회는 VOD 존재 여부와 기본 정보를 확인합니다. 분석 시작을 누르면
              전체 채팅 흐름을 바탕으로 편집 후보 구간과 추천 이유를 계산합니다.
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
      </section>

      {metadata?.exists ? (
        <section className="space-y-5 rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
          <div
            onClick={handleOpenAnalysis}
            className="grid cursor-pointer gap-5 rounded-[26px] border border-slate-200 bg-slate-50 p-5 lg:grid-cols-[280px_1fr]"
          >
            <div className="overflow-hidden rounded-2xl border border-slate-200 bg-slate-100">
              {metadata.thumbnailImageUrl ? (
                <img
                  src={metadata.thumbnailImageUrl}
                  alt={metadata.title || metadata.videoNo}
                  className="h-full w-full object-cover"
                />
              ) : (
                <div className="flex h-[158px] items-center justify-center text-slate-400">
                  <Film className="h-10 w-10" />
                </div>
              )}
            </div>

            <div className="flex flex-col justify-between gap-4">
              <div>
                <h4 className="text-2xl font-black text-slate-950">
                  {metadata.title || "제목 없음"}
                </h4>
                <p className="mt-2 text-sm text-slate-600">
                  {metadata.channelName || "채널 정보 없음"} · 생성 시각{" "}
                  {formatDateTime(metadata.publishDateAt ?? metadata.publishDate)}
                </p>
                <p className="mt-1 text-sm text-slate-500">
                  총 길이 {formatSeconds(metadata.duration ?? 0)}
                </p>
              </div>

              <div className="flex flex-wrap gap-3">
                <button
                  onClick={(event) => {
                    event.stopPropagation();
                    void handleOpenAnalysis();
                  }}
                  className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-black text-slate-700"
                >
                  흐름 보기
                </button>
                <button
                  onClick={(event) => {
                    event.stopPropagation();
                    void handleAnalyze();
                  }}
                  disabled={
                    analysisSubmitting || ACTIVE_STATUSES.includes(status.status)
                  }
                  className="rounded-2xl bg-indigo-600 px-4 py-3 text-sm font-black text-white disabled:bg-indigo-300"
                >
                  {ACTIVE_STATUSES.includes(status.status)
                    ? "분석 진행 중"
                    : "분석 시작"}
                </button>
                <a
                  href={`https://chzzk.naver.com/video/${metadata.videoNo}`}
                  target="_blank"
                  rel="noreferrer"
                  className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-black text-slate-700"
                >
                  영상 열기
                </a>
              </div>
            </div>
          </div>

          <div className="rounded-[24px] border border-slate-200 bg-slate-50 px-5 py-4 text-slate-700">
            <div className="flex items-start gap-3">
              <div className="mt-0.5">
                {status.status === "COMPLETED" ? (
                  <CheckCircle2 className="h-5 w-5" />
                ) : ACTIVE_STATUSES.includes(status.status) ? (
                  <LoaderCircle className="h-5 w-5 animate-spin" />
                ) : (
                  <Clock3 className="h-5 w-5" />
                )}
              </div>
              <div>
                <div className="text-base font-black">
                  {status.message || EMPTY_STATUS.message}
                </div>
                <div className="mt-1 text-sm text-slate-600">
                  {status.status === "CRAWLING"
                    ? `현재 ${status.pagesProcessed ?? 0}페이지, ${
                        status.chatsCollected ?? 0
                      }개 채팅을 확인했습니다.`
                    : `상태: ${status.status}`}
                </div>
              </div>
            </div>
          </div>
        </section>
      ) : metadata ? (
        <section className="rounded-[30px] border border-amber-200 bg-amber-50 p-6 text-sm font-semibold text-amber-700">
          {metadata.message || "해당 VOD를 찾을 수 없습니다."}
        </section>
      ) : null}

      {selectedVideoNo ? (
        <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
          <div className="mb-5 flex items-center justify-between">
            <div>
              <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">
                Broadcast Flow
              </div>
              <h4 className="mt-2 text-2xl font-black text-slate-950">
                방송 흐름 타임라인
              </h4>
            </div>
            <div className="text-sm font-semibold text-slate-500">
              {Math.max(timeline.length, highlights.length)}개 구간 · 편집 후보{" "}
              {highlights.length}개
            </div>
          </div>

          <div className="grid gap-6 xl:grid-cols-[minmax(0,1.45fr)_minmax(360px,0.95fr)]">
            <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-5">
              <div className="mb-4 flex flex-wrap items-center gap-3 text-xs font-bold tracking-[0.14em] text-slate-500">
                <span className="rounded-full border border-slate-200 bg-white px-3 py-1">
                  00:00
                </span>
                <span className="rounded-full border border-slate-200 bg-white px-3 py-1">
                  {formatSeconds(duration)}
                </span>
                <span className="inline-flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-emerald-700">
                  <Zap className="h-3.5 w-3.5" />
                  채팅량
                </span>
                <span className="inline-flex items-center gap-2 rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-indigo-700">
                  <Users className="h-3.5 w-3.5" />
                  참여자 수
                </span>
              </div>

              <div className="rounded-2xl border border-slate-200 bg-white p-4">
                {chartBars.length === 0 ? (
                  <div className="flex h-[280px] items-center justify-center text-sm font-semibold text-slate-500">
                    아직 전체 흐름 데이터가 없습니다.
                  </div>
                ) : (
                  <div className="flex h-[280px] items-end gap-1">
                    {chartBars.map((item) => (
                      <div
                        key={item.id}
                        className="flex h-full min-w-0 flex-1 items-end gap-[2px]"
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
                )}
              </div>

              {markerClusters.length > 0 ? (
                <div className="mt-4 rounded-2xl border border-slate-200 bg-white px-4 py-5">
                  <div className="mb-3 flex items-center justify-between">
                    <div className="text-xs font-black uppercase tracking-[0.18em] text-slate-500">
                      Highlight Rail
                    </div>
                    <div className="text-xs font-semibold text-slate-500">
                      {selectedCluster
                        ? `${selectedCluster.items.length}개 후보 묶음`
                        : "편집 후보를 선택해 주세요"}
                    </div>
                  </div>

                  <div className="relative h-[84px]">
                    <div className="absolute left-0 right-0 top-[36px] h-[8px] rounded-full bg-slate-200" />

                    {markerClusters.map((cluster) => {
                      const lead = cluster.items[0];
                      const selected = cluster.items.some(
                        (item) => item.id === selectedHighlightId,
                      );

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
                          <div className="absolute left-1/2 top-[52px] -translate-x-1/2 whitespace-nowrap text-[10px] font-black tracking-[0.12em] text-slate-500">
                            {formatSeconds(lead.startSeconds)}
                          </div>
                          {cluster.items.length > 1 ? (
                            <div className="absolute left-1/2 top-[2px] -translate-x-1/2 rounded-full border border-rose-200 bg-white px-2 py-0.5 text-[10px] font-black text-rose-600 shadow-sm">
                              +{cluster.items.length}
                            </div>
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
                            {selectedCluster.items.length > 1
                              ? "가까운 후보 묶음"
                              : "선택한 편집 후보"}
                          </div>
                          <div className="mt-1 text-base font-black text-slate-950">
                            {selectedCluster.items[0].reactionLabel ||
                              categoryLabel[selectedCluster.items[0].category] ||
                              "편집 후보"}
                          </div>
                          <div className="mt-3 flex max-w-xl flex-wrap gap-2">
                            {selectedClusterPoints.map((point) => (
                              <span
                                key={point}
                                className="rounded-full border border-rose-200 bg-white px-3 py-2 text-xs font-bold leading-5 text-slate-700"
                              >
                                {point}
                              </span>
                            ))}
                          </div>
                        </div>

                        <div className="text-xs font-semibold text-slate-500">
                          {selectedCluster.items.length > 1
                            ? "시간이 가까운 후보는 한 묶음으로 보여드려요."
                            : "마커를 누르면 오른쪽 카드로 이동해요."}
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
                              className={`rounded-full border px-3 py-2 text-xs font-black transition ${
                                active
                                  ? "border-rose-400 bg-rose-500 text-white"
                                  : "border-rose-200 bg-white text-rose-700"
                              }`}
                            >
                              {formatSeconds(item.startSeconds)}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  ) : null}
                </div>
              ) : null}
            </div>

            <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-5">
              <div className="mb-4 flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                    Highlights
                  </div>
                  <div className="mt-2 text-lg font-black text-slate-950">
                    편집 후보 상세
                  </div>
                </div>
                <div className="text-xs font-semibold text-slate-500">
                  {highlights.length}개 후보
                </div>
              </div>

              <div className="max-h-[720px] space-y-3 overflow-y-auto pr-1">
                {highlights.length === 0 ? (
                  <div className="rounded-2xl border border-slate-200 bg-white px-5 py-10 text-center">
                    <Clock3 className="mx-auto h-8 w-8 text-slate-400" />
                    <div className="mt-3 text-base font-black text-slate-900">
                      아직 생성된 편집 후보가 없습니다.
                    </div>
                  </div>
                ) : (
                  highlights.map((item) => (
                    <div
                      key={item.id}
                      ref={(element) => {
                        cardRefs.current[item.id] = element;
                      }}
                      onMouseEnter={() => setSelectedHighlightId(item.id)}
                      className={`flex flex-col gap-4 rounded-[24px] border p-4 transition ${
                        selectedHighlightId === item.id
                          ? "border-rose-300 bg-rose-50/70 shadow-sm"
                          : "border-slate-200 bg-white"
                      }`}
                    >
                      {(() => {
                        const readablePoints = toCompactReasonTags(item);

                        return (
                          <>
                            <div className="flex items-start justify-between gap-3">
                              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                                  구간
                                </div>
                                <div className="mt-1 text-lg font-black text-slate-950">
                                  {formatSeconds(item.startSeconds)}
                                </div>
                                <div className="text-xs font-semibold text-slate-500">
                                  ~ {formatSeconds(item.endSeconds)}
                                </div>
                              </div>

                              <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-black text-slate-700">
                                타임스탬프 {formatSeconds(item.startSeconds)}
                              </div>
                            </div>

                            <div className="flex flex-wrap gap-2">
                              <span className="rounded-full border border-indigo-200 bg-indigo-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
                                {item.reactionLabel ||
                                  categoryLabel[item.category] ||
                                  "편집 후보"}
                              </span>
                              <span className="inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-amber-700">
                                <Zap className="h-3.5 w-3.5" />
                                추천 강도 {item.highlightScore.toFixed(1)}
                              </span>
                            </div>

                            <div className="space-y-3">
                              <div>
                                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                                  한눈에 보기
                                </div>
                                <div className="mt-2 flex flex-wrap gap-2">
                                  {readablePoints.map((point) => (
                                    <span
                                      key={point}
                                      className="rounded-full border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-bold leading-5 text-slate-700"
                                    >
                                      {point}
                                    </span>
                                  ))}
                                </div>
                              </div>

                              <div className="flex flex-wrap gap-2 text-xs font-bold text-slate-600">
                                {typeof item.intensityScore === "number" ? (
                                  <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">
                                    반응 밀집도 {item.intensityScore.toFixed(1)}
                                  </span>
                                ) : null}
                                {typeof item.transitionScore === "number" &&
                                item.transitionScore > 0 ? (
                                  <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1">
                                    흐름 전환 {item.transitionScore.toFixed(1)}
                                  </span>
                                ) : null}
                              </div>

                              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                                <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">
                                  대표 채팅
                                </div>
                                <p className="mt-2 text-sm leading-6 text-slate-700">
                                  "{item.topMessage || "대표 채팅이 없는 구간입니다."}"
                                </p>
                              </div>
                            </div>
                          </>
                        );
                      })()}
                    </div>
                  ))
                )}
              </div>
            </div>
          </div>
        </section>
      ) : null}
    </div>
  );
}
