"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  CheckCircle2,
  Clock3,
  Film,
  LoaderCircle,
  Play,
  Search,
  Sparkles,
  Users,
  Zap,
} from "lucide-react";

interface VodHighlight {
  id: number;
  videoNo: string;
  startSeconds: number;
  endSeconds: number;
  highlightScore: number;
  category: string;
  description: string;
  topMessage: string;
}

interface VodTimelinePoint {
  id: number;
  videoNo: string;
  startSeconds: number;
  endSeconds: number;
  messageCount: number;
  participantCount: number;
  activityScore: number;
  category: string;
  topMessage: string;
}

function deriveTimelineFromHighlights(highlights: VodHighlight[]): VodTimelinePoint[] {
  return highlights.map((highlight) => {
    const activityScore = Math.max(highlight.highlightScore || 0, 1);
    const participantCount = Math.max(1, Math.round(activityScore));
    const messageCount = Math.max(participantCount, Math.round(activityScore * 2));

    return {
      id: highlight.id,
      videoNo: highlight.videoNo,
      startSeconds: highlight.startSeconds,
      endSeconds: highlight.endSeconds,
      messageCount,
      participantCount,
      activityScore,
      category: highlight.category,
      topMessage: highlight.topMessage,
    };
  });
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
  status: "IDLE" | "REQUESTED" | "CRAWLING" | "WAITING" | "ANALYZING" | "COMPLETED" | "FAILED";
  message?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  pagesProcessed?: number | null;
  chatsCollected?: number | null;
}

const EMPTY_STATUS: VodAnalysisStatus = {
  videoNo: "",
  status: "IDLE",
  message: "아직 분석을 시작하지 않았습니다.",
};

function formatSeconds(totalSeconds: number) {
  const safe = Math.max(totalSeconds, 0);
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const seconds = safe % 60;

  if (hours > 0) {
    return `${hours}:${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`;
  }

  return `${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`;
}

function formatDateTime(value?: string | number | null) {
  if (!value) return "-";
  return new Date(value).toLocaleString("ko-KR");
}

function formatCategory(category: string) {
  switch (category) {
    case "LAUGH":
      return "웃음";
    case "WONDER":
      return "놀람";
    case "HYPE":
      return "고조";
    case "TENSION":
      return "긴장";
    default:
      return "핫 모먼트";
  }
}

function resolveVideoNo(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return "";

  const directMatch = trimmed.match(/^\d+$/);
  if (directMatch) return directMatch[0];

  const urlMatch = trimmed.match(/chzzk\.naver\.com\/video\/(\d+)/i);
  if (urlMatch) return urlMatch[1];

  const fallbackDigits = trimmed.match(/(\d{5,})/);
  return fallbackDigits ? fallbackDigits[1] : "";
}

function buildLinePath(
  points: Array<{ x: number; y: number }>,
  width: number,
  height: number,
  paddingX: number,
  paddingY: number,
) {
  if (points.length === 0) return "";

  return points
    .map((point, index) => {
      const x = paddingX + point.x * (width - paddingX * 2);
      const y = paddingY + point.y * (height - paddingY * 2);
      return `${index === 0 ? "M" : "L"} ${x} ${y}`;
    })
    .join(" ");
}

function buildAreaPath(
  points: Array<{ x: number; y: number }>,
  width: number,
  height: number,
  paddingX: number,
  paddingY: number,
) {
  if (points.length === 0) return "";

  const line = buildLinePath(points, width, height, paddingX, paddingY);
  const firstX = paddingX + points[0].x * (width - paddingX * 2);
  const lastX = paddingX + points[points.length - 1].x * (width - paddingX * 2);
  const baseY = height - paddingY;
  return `${line} L ${lastX} ${baseY} L ${firstX} ${baseY} Z`;
}

export default function VodHighlightBoard() {
  const [videoInput, setVideoInput] = useState("");
  const [metadata, setMetadata] = useState<VodMetadata | null>(null);
  const [status, setStatus] = useState<VodAnalysisStatus>(EMPTY_STATUS);
  const [highlights, setHighlights] = useState<VodHighlight[]>([]);
  const [timeline, setTimeline] = useState<VodTimelinePoint[]>([]);
  const [selectedVideoNo, setSelectedVideoNo] = useState<string | null>(null);
  const [lookupLoading, setLookupLoading] = useState(false);
  const [highlightsLoading, setHighlightsLoading] = useState(false);
  const [analysisSubmitting, setAnalysisSubmitting] = useState(false);
  const highlightRefs = useRef<Record<number, HTMLDivElement | null>>({});

  const fetchHighlights = useCallback(async (videoNo: string) => {
    setHighlightsLoading(true);
    try {
      const response = await fetch(`/api/vod/${videoNo}/highlights`, { cache: "no-store" });
      const data = (await response.json()) as VodHighlight[];
      const next = Array.isArray(data) ? data : [];
      setHighlights(next);
      return next;
    } catch {
      setHighlights([]);
      return [];
    } finally {
      setHighlightsLoading(false);
    }
  }, []);

  const fetchTimeline = useCallback(async (videoNo: string) => {
    try {
      const response = await fetch(`/api/vod/${videoNo}/timeline`, { cache: "no-store" });
      const data = (await response.json()) as VodTimelinePoint[];
      const next = Array.isArray(data) ? data : [];
      setTimeline(next);
      return next;
    } catch {
      setTimeline([]);
      return [];
    }
  }, []);

  const fetchStatus = useCallback(async (videoNo: string) => {
    const response = await fetch(`/api/vod/${videoNo}/status`, { cache: "no-store" });
    const data = (await response.json()) as VodAnalysisStatus;
    return data.videoNo ? data : { ...EMPTY_STATUS, videoNo };
  }, []);

  const syncStatus = useCallback(
    async (videoNo: string) => {
      const [nextStatus, nextHighlights, nextTimeline] = await Promise.all([
        fetchStatus(videoNo),
        fetchHighlights(videoNo),
        fetchTimeline(videoNo),
      ]);

      if (nextTimeline.length === 0 && nextHighlights.length > 0) {
        setTimeline(deriveTimelineFromHighlights(nextHighlights));
      }

      if (nextStatus.status === "ANALYZING" && nextHighlights.length > 0) {
        setStatus({
          ...nextStatus,
          status: "COMPLETED",
          message: `전체 채팅 ${nextTimeline.length}개 구간을 기준으로 하이라이트 ${nextHighlights.length}개를 생성했습니다.`,
          completedAt: nextStatus.completedAt ?? new Date().toISOString(),
        });
        return;
      }

      setStatus(nextStatus);
    },
    [fetchHighlights, fetchStatus, fetchTimeline],
  );

  const handleLookup = async () => {
    const videoNo = resolveVideoNo(videoInput);
    if (!videoNo) {
      alert("VOD 번호 또는 전체 URL을 입력해 주세요.");
      return;
    }

    setLookupLoading(true);
    setSelectedVideoNo(null);
    setHighlights([]);
    setTimeline([]);

    try {
      const [metadataResponse, nextStatus] = await Promise.all([
        fetch(`/api/vod/${videoNo}/metadata`, { cache: "no-store" }),
        fetchStatus(videoNo),
      ]);

      const metadataData = (await metadataResponse.json()) as VodMetadata;
      setMetadata(metadataData);
      setStatus(nextStatus);

      if (!metadataData.exists) {
        setSelectedVideoNo(null);
        setHighlights([]);
        setTimeline([]);
      }
    } catch {
      setMetadata({
        exists: false,
        videoNo,
        message: "VOD 정보를 불러오지 못했습니다.",
      });
      setStatus({ ...EMPTY_STATUS, videoNo });
      setSelectedVideoNo(null);
      setHighlights([]);
      setTimeline([]);
    } finally {
      setLookupLoading(false);
    }
  };

  const handleSelectVideo = async () => {
    if (!metadata?.exists || !metadata.videoNo) return;
    setSelectedVideoNo(metadata.videoNo);
    await syncStatus(metadata.videoNo);
  };

  const handleAnalyze = async () => {
    if (!metadata?.exists || !metadata.videoNo) {
      alert("먼저 조회로 유효한 VOD를 확인해 주세요.");
      return;
    }

    setAnalysisSubmitting(true);
    try {
      const response = await fetch(`/api/vod/${metadata.videoNo}/analyze`, { method: "POST" });
      if (!response.ok) {
        const payload = (await response.json().catch(() => null)) as { message?: string } | null;
        throw new Error(payload?.message || "분석 요청에 실패했습니다.");
      }

      setSelectedVideoNo(metadata.videoNo);
      setHighlights([]);
      setTimeline([]);
      setStatus({
        videoNo: metadata.videoNo,
        status: "REQUESTED",
        message: "분석 요청이 접수되었습니다.",
      });
      await syncStatus(metadata.videoNo);
    } catch (error) {
      alert(error instanceof Error ? error.message : "분석 요청에 실패했습니다.");
    } finally {
      setAnalysisSubmitting(false);
    }
  };

  useEffect(() => {
    if (!selectedVideoNo) return;
    if (!["REQUESTED", "WAITING", "CRAWLING", "ANALYZING"].includes(status.status)) return;

    const intervalId = window.setInterval(() => {
      void syncStatus(selectedVideoNo);
    }, 5000);

    return () => window.clearInterval(intervalId);
  }, [selectedVideoNo, status.status, syncStatus]);

  const statusBadge = useMemo(() => {
    switch (status.status) {
      case "REQUESTED":
        return { text: "요청 접수", className: "border-slate-200 bg-slate-50 text-slate-700" };
      case "WAITING":
        return { text: "다음 구간 요청 중", className: "border-sky-200 bg-sky-50 text-sky-700" };
      case "CRAWLING":
        return { text: "채팅 수집 중", className: "border-sky-200 bg-sky-50 text-sky-700" };
      case "ANALYZING":
        return { text: "하이라이트 계산 중", className: "border-indigo-200 bg-indigo-50 text-indigo-700" };
      case "COMPLETED":
        return { text: "완료됨", className: "border-emerald-200 bg-emerald-50 text-emerald-700" };
      case "FAILED":
        return { text: "실패", className: "border-rose-200 bg-rose-50 text-rose-700" };
      default:
        return { text: "대기 중", className: "border-slate-200 bg-slate-50 text-slate-600" };
    }
  }, [status.status]);

  const statusPanel = useMemo(() => {
    switch (status.status) {
      case "REQUESTED":
        return {
          icon: <Clock3 className="h-5 w-5" />,
          title: "분석 요청이 접수되었습니다",
          description: "백엔드가 VOD 분석 작업을 준비하고 있습니다.",
          className: "border-slate-200 bg-slate-50 text-slate-700",
        };
      case "WAITING":
        return {
          icon: <LoaderCircle className="h-5 w-5 animate-spin" />,
          title: "다음 채팅 구간을 불러오는 중입니다",
          description: status.message || "잠시 후 다시 진행됩니다.",
          className: "border-sky-200 bg-sky-50 text-sky-700",
        };
      case "CRAWLING":
        return {
          icon: <LoaderCircle className="h-5 w-5 animate-spin" />,
          title: "전체 VOD 채팅을 수집 중입니다",
          description: `현재 ${status.pagesProcessed ?? 0}페이지, ${status.chatsCollected ?? 0}개 채팅을 확인했습니다.`,
          className: "border-sky-200 bg-sky-50 text-sky-700",
        };
      case "ANALYZING":
        return {
          icon: <Sparkles className="h-5 w-5" />,
          title: "전체 흐름을 바탕으로 하이라이트를 계산 중입니다",
          description: `${timeline.length}개 구간과 ${status.chatsCollected ?? 0}개 채팅을 바탕으로 방송 흐름을 정리하고 있습니다.`,
          className: "border-indigo-200 bg-indigo-50 text-indigo-700",
        };
      case "COMPLETED":
        return {
          icon: <CheckCircle2 className="h-5 w-5" />,
          title: "하이라이트 생성이 완료되었습니다",
          description: `${timeline.length}개 구간 흐름과 하이라이트 ${highlights.length}개를 확인할 수 있습니다.`,
          className: "border-emerald-200 bg-emerald-50 text-emerald-700",
        };
      case "FAILED":
        return {
          icon: <Clock3 className="h-5 w-5" />,
          title: "분석 중 오류가 발생했습니다",
          description: status.message || "잠시 후 다시 시도해 주세요.",
          className: "border-rose-200 bg-rose-50 text-rose-700",
        };
      default:
        return {
          icon: <Clock3 className="h-5 w-5" />,
          title: "아직 분석을 시작하지 않았습니다",
          description: "조회 후 분석 시작 버튼을 눌러 주세요.",
          className: "border-slate-200 bg-slate-50 text-slate-600",
        };
    }
  }, [highlights.length, status.chatsCollected, status.message, status.pagesProcessed, status.status, timeline.length]);

  const timelineDuration =
    metadata?.duration && metadata.duration > 0
      ? metadata.duration
      : timeline.length > 0
        ? Math.max(...timeline.map((point) => point.endSeconds), 1)
        : highlights.length > 0
          ? Math.max(...highlights.map((highlight) => highlight.endSeconds), 1)
          : 1;

  const effectiveTimeline = useMemo(
    () => (timeline.length > 0 ? timeline : deriveTimelineFromHighlights(highlights)),
    [highlights, timeline],
  );

  const chartWidth = 1000;
  const chartHeight = 280;
  const chartPaddingX = 24;
  const chartPaddingY = 20;

  const maxMessageCount = useMemo(
    () => Math.max(...effectiveTimeline.map((point) => point.messageCount), 1),
    [effectiveTimeline],
  );
  const maxParticipantCount = useMemo(
    () => Math.max(...effectiveTimeline.map((point) => point.participantCount), 1),
    [effectiveTimeline],
  );

  const chartPoints = useMemo(() => {
    if (effectiveTimeline.length === 0) {
      return [];
    }

    return effectiveTimeline.map((point) => ({
      ...point,
      x: timelineDuration > 0 ? point.startSeconds / timelineDuration : 0,
      messageY: 1 - point.messageCount / maxMessageCount,
      participantY: 1 - point.participantCount / maxParticipantCount,
    }));
  }, [effectiveTimeline, maxMessageCount, maxParticipantCount, timelineDuration]);

  const areaPath = useMemo(
    () =>
      buildAreaPath(
        chartPoints.map((point) => ({ x: point.x, y: point.messageY })),
        chartWidth,
        chartHeight,
        chartPaddingX,
        chartPaddingY,
      ),
    [chartPoints],
  );

  const linePath = useMemo(
    () =>
      buildLinePath(
        chartPoints.map((point) => ({ x: point.x, y: point.participantY })),
        chartWidth,
        chartHeight,
        chartPaddingX,
        chartPaddingY,
      ),
    [chartPoints],
  );

  const highlightMarkers = useMemo(
    () =>
      highlights.map((highlight) => ({
        ...highlight,
        leftPercent: timelineDuration > 0 ? (highlight.startSeconds / timelineDuration) * 100 : 0,
      })),
    [highlights, timelineDuration],
  );

  const scrollToHighlight = (highlightId: number) => {
    const element = highlightRefs.current[highlightId];
    if (!element) return;
    element.scrollIntoView({ behavior: "smooth", block: "center" });
  };

  return (
    <div className="space-y-6">
      <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
          <div className="space-y-2">
            <div className="inline-flex items-center gap-2 rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
              <Film className="h-3.5 w-3.5" />
              VOD 하이라이트
            </div>
            <h3 className="text-2xl font-black text-slate-950">다시보기를 확인하고 전체 흐름으로 분석하세요</h3>
            <p className="max-w-2xl text-sm leading-6 text-slate-600">
              조회는 VOD 존재 여부와 기본 정보를 확인합니다. 분석 시작을 눌렀을 때만 백엔드가 전체 채팅을 읽고 방송 흐름과 하이라이트를 계산합니다.
            </p>
          </div>

          <div className="flex flex-col gap-3 sm:flex-row">
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
              <input
                type="text"
                value={videoInput}
                onChange={(event) => setVideoInput(event.target.value)}
                placeholder="VOD 번호 또는 전체 URL 붙여넣기"
                className="w-full min-w-[280px] rounded-2xl border border-slate-200 bg-white py-3 pl-10 pr-4 text-sm text-slate-900 outline-none transition focus:border-indigo-400 focus:ring-4 focus:ring-indigo-100"
              />
            </div>

            <button
              onClick={handleLookup}
              disabled={lookupLoading}
              className="inline-flex items-center justify-center rounded-2xl bg-slate-950 px-5 py-3 text-sm font-black text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              {lookupLoading ? "조회 중..." : "조회"}
            </button>
          </div>
        </div>
      </section>

      {metadata ? (
        <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
          {!metadata.exists ? (
            <div className="rounded-2xl border border-amber-200 bg-amber-50 px-5 py-4 text-sm font-semibold text-amber-700">
              {metadata.message || "해당 VOD를 찾을 수 없습니다."}
            </div>
          ) : (
            <div className="space-y-5">
              <div
                onClick={handleSelectVideo}
                className="grid cursor-pointer gap-5 rounded-[26px] border border-slate-200 bg-slate-50 p-5 transition hover:border-indigo-300 hover:bg-indigo-50/40 lg:grid-cols-[280px_1fr]"
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
                  <div className="space-y-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="rounded-full border border-slate-200 bg-white px-3 py-1 text-[11px] font-black tracking-[0.18em] text-slate-600">
                        VIDEO {metadata.videoNo}
                      </span>
                      <span className={`rounded-full border px-3 py-1 text-[11px] font-black tracking-[0.18em] ${statusBadge.className}`}>
                        {statusBadge.text}
                      </span>
                    </div>

                    <div>
                      <h4 className="text-2xl font-black text-slate-950">{metadata.title || "제목 없음"}</h4>
                      <p className="mt-2 text-sm text-slate-600">
                        {metadata.channelName || "채널 정보 없음"} · 생성 시각 {formatDateTime(metadata.publishDateAt ?? metadata.publishDate)}
                      </p>
                      <p className="mt-1 text-sm text-slate-500">총 길이 {formatSeconds(metadata.duration ?? 0)}</p>
                    </div>
                  </div>

                  <div className="flex flex-wrap items-center gap-3">
                    <button
                      onClick={(event) => {
                        event.stopPropagation();
                        void handleSelectVideo();
                      }}
                      className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-black text-slate-700 transition hover:bg-slate-100"
                    >
                      <Play className="h-4 w-4" />
                      흐름 보기
                    </button>
                    <button
                      onClick={(event) => {
                        event.stopPropagation();
                        void handleAnalyze();
                      }}
                      disabled={analysisSubmitting || ["REQUESTED", "WAITING", "CRAWLING", "ANALYZING"].includes(status.status)}
                      className="inline-flex items-center gap-2 rounded-2xl bg-indigo-600 px-4 py-3 text-sm font-black text-white transition hover:bg-indigo-500 disabled:cursor-not-allowed disabled:bg-indigo-300"
                    >
                      <Sparkles className="h-4 w-4" />
                      {["REQUESTED", "WAITING", "CRAWLING", "ANALYZING"].includes(status.status) ? "분석 진행 중" : "분석 시작"}
                    </button>
                    <a
                      href={`https://chzzk.naver.com/video/${metadata.videoNo}`}
                      target="_blank"
                      rel="noreferrer"
                      onClick={(event) => event.stopPropagation()}
                      className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-black text-slate-700 transition hover:bg-slate-100"
                    >
                      <Play className="h-4 w-4" />
                      영상 열기
                    </a>
                  </div>
                </div>
              </div>

              <div className={`rounded-[24px] border px-5 py-4 ${statusPanel.className}`}>
                <div className="flex items-start gap-3">
                  <div className="mt-0.5">{statusPanel.icon}</div>
                  <div className="space-y-1">
                    <div className="text-base font-black">{statusPanel.title}</div>
                    <div className="text-sm leading-6">{statusPanel.description}</div>
                    {status.startedAt ? (
                      <div className="text-xs font-semibold opacity-80">시작 시각 {formatDateTime(status.startedAt)}</div>
                    ) : null}
                    {status.completedAt ? (
                      <div className="text-xs font-semibold opacity-80">완료 시각 {formatDateTime(status.completedAt)}</div>
                    ) : null}
                  </div>
                </div>
              </div>
            </div>
          )}
        </section>
      ) : null}

      {selectedVideoNo ? (
        <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
          <div className="mb-5 flex items-center justify-between">
            <div>
              <div className="text-[11px] font-black uppercase tracking-[0.2em] text-slate-500">Broadcast Flow</div>
              <h4 className="mt-2 text-2xl font-black text-slate-950">방송 흐름 타임라인</h4>
            </div>
            <div className="text-sm font-semibold text-slate-500">
              {highlightsLoading ? "불러오는 중..." : `${timeline.length}개 구간 · 하이라이트 ${highlights.length}개`}
            </div>
          </div>

          <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-5">
            <div className="mb-4 flex flex-wrap items-center gap-3 text-xs font-bold tracking-[0.14em] text-slate-500">
              <span className="rounded-full border border-slate-200 bg-white px-3 py-1">00:00</span>
              <span className="rounded-full border border-slate-200 bg-white px-3 py-1">{formatSeconds(timelineDuration)}</span>
              <span className="inline-flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-emerald-700">
                <Zap className="h-3.5 w-3.5" />
                채팅량
              </span>
              <span className="inline-flex items-center gap-2 rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-indigo-700">
                <Users className="h-3.5 w-3.5" />
                참여자 수
              </span>
            </div>

            <div className="relative overflow-hidden rounded-2xl border border-slate-200 bg-white">
              {chartPoints.length === 0 ? (
                <div className="flex h-[280px] items-center justify-center px-6 text-sm font-semibold text-slate-500">
                  아직 전체 흐름 데이터가 없습니다. 분석을 시작하거나 완료 후 다시 확인해 주세요.
                </div>
              ) : (
                <div className="relative h-[280px] w-full">
                  <svg viewBox={`0 0 ${chartWidth} ${chartHeight}`} className="h-full w-full">
                    <defs>
                      <linearGradient id="messageArea" x1="0" x2="0" y1="0" y2="1">
                        <stop offset="0%" stopColor="rgba(16,185,129,0.34)" />
                        <stop offset="100%" stopColor="rgba(16,185,129,0.05)" />
                      </linearGradient>
                    </defs>

                    <line x1={chartPaddingX} y1={chartHeight - chartPaddingY} x2={chartWidth - chartPaddingX} y2={chartHeight - chartPaddingY} stroke="#e2e8f0" strokeWidth="1" />
                    <line x1={chartPaddingX} y1={chartPaddingY} x2={chartPaddingX} y2={chartHeight - chartPaddingY} stroke="#f1f5f9" strokeWidth="1" />

                    <path d={areaPath} fill="url(#messageArea)" />
                    <path d={linePath} fill="none" stroke="#4f46e5" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />

                    {highlightMarkers.map((highlight) => {
                      const x = chartPaddingX + (highlight.leftPercent / 100) * (chartWidth - chartPaddingX * 2);
                      return (
                        <g key={`marker-${highlight.id}`}>
                          <line x1={x} x2={x} y1={chartPaddingY} y2={chartHeight - chartPaddingY} stroke="rgba(244,63,94,0.28)" strokeDasharray="5 5" />
                          <circle cx={x} cy={chartPaddingY + 16} r="6" fill="#f43f5e" />
                        </g>
                      );
                    })}
                  </svg>

                  {highlightMarkers.map((highlight) => (
                    <button
                      key={`button-${highlight.id}`}
                      type="button"
                      title={`${formatSeconds(highlight.startSeconds)} - ${highlight.description}`}
                      onClick={() => scrollToHighlight(highlight.id)}
                      className="absolute top-0 h-full -translate-x-1/2 focus:outline-none"
                      style={{ left: `${highlight.leftPercent}%`, width: "22px" }}
                    >
                      <span className="absolute left-1/2 top-4 -translate-x-1/2 rounded-full border border-rose-200 bg-white px-2 py-1 text-[10px] font-black tracking-[0.14em] text-rose-600 shadow-sm">
                        {formatSeconds(highlight.startSeconds)}
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </div>

            <p className="mt-3 text-sm text-slate-600">
              초록 면적은 시간대별 채팅량, 보라 선은 시간대별 참여자 수입니다. 분홍 마커는 하이라이트 구간이며, 클릭하면 아래 상세 카드로 바로 이동합니다.
            </p>
          </div>

          <div className="mt-6">
            {highlightsLoading ? (
              <div className="flex items-center justify-center py-16 text-sm font-semibold text-slate-500">
                하이라이트를 불러오고 있습니다...
              </div>
            ) : highlights.length === 0 ? (
              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-5 py-10 text-center">
                <Clock3 className="mx-auto h-8 w-8 text-slate-400" />
                <div className="mt-3 text-base font-black text-slate-900">아직 생성된 하이라이트가 없습니다.</div>
                <p className="mt-2 text-sm text-slate-600">분석이 끝나면 전체 흐름 위에 핵심 구간이 표시됩니다.</p>
              </div>
            ) : (
              <div className="space-y-3">
                {highlights.map((highlight) => (
                  <div
                    key={highlight.id}
                    ref={(element) => {
                      highlightRefs.current[highlight.id] = element;
                    }}
                    className="flex flex-col gap-4 rounded-[24px] border border-slate-200 bg-slate-50 p-4 lg:flex-row lg:items-center"
                  >
                    <div className="flex min-w-[132px] items-center justify-between rounded-2xl border border-slate-200 bg-white px-4 py-3 lg:block">
                      <div className="text-[11px] font-black uppercase tracking-[0.18em] text-slate-500">구간</div>
                      <div className="mt-1 text-lg font-black text-slate-950">{formatSeconds(highlight.startSeconds)}</div>
                      <div className="text-xs font-semibold text-slate-500">~ {formatSeconds(highlight.endSeconds)}</div>
                    </div>

                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="rounded-full border border-indigo-200 bg-indigo-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
                          {formatCategory(highlight.category)}
                        </span>
                        <span className="inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-amber-700">
                          <Zap className="h-3.5 w-3.5" />
                          {highlight.highlightScore.toFixed(2)}
                        </span>
                      </div>
                      <div className="mt-2 text-base font-black text-slate-950">{highlight.description}</div>
                      <p className="mt-1 text-sm text-slate-600">"{highlight.topMessage || "대표 채팅이 없는 구간입니다."}"</p>
                    </div>

                    <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-black text-slate-700">
                      타임스탬프 {formatSeconds(highlight.startSeconds)}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>
      ) : null}
    </div>
  );
}
