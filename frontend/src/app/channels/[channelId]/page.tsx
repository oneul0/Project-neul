"use client";

import { use, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertCircle,
  BarChart3,
  Clock3,
  Download,
  Lock,
  Radio,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  Target,
  Waves,
} from "lucide-react";
import KeywordBubbleChart from "@/components/KeywordBubbleChart";
import VodHighlightBoard from "@/components/VodHighlightBoard";
import MoodGauge from "@/components/MoodGauge";
import EmotionHeatmap from "@/components/EmotionHeatmap";
import PollCard from "@/components/poll/PollCard";
import V2InsightsPanel from "@/components/v2/V2InsightsPanel";
import type { V2Frame } from "@/components/v2/V2InsightsPanel";
import { usePollSession } from "@/hooks/usePollSession";
import { appendOwnerId, buildOwnerHeaders } from "@/lib/ownerAuth";
import {
  DashboardMetricCard,
  TrendAreaChart,
  buildAccessState,
  buildConnectionState,
  buildHeroNotice,
  buildLiveState,
  buildPrimaryActionState,
  buildSessionState,
  readCollectorErrorMessage,
  requestBroadcastStatus,
  useOwnerDashboardSession,
  type BroadcastStatus,
  type TrendPoint,
} from "./dashboard-helpers";

interface Highlight {
  id: number;
  roomId: string;
  emotionType: string;
  peakScore: number;
  topMessage: string;
  liveImageUrl: string;
  timestamp: string;
}

interface AnalyzedChatMessage {
  messageId: string;
  roomId: string;
  messageType: "CHAT" | "DONATION" | "SUBSCRIPTION";
  content?: string;
  sender?: string;
  senderId?: string;
  emotionScores?: Record<string, number>;
  keywords?: string[];
  analyzedAt?: string;
  timestamp?: string;
}

interface HistoryItem {
  messageId: string;
  roomId?: string;
  content?: string;
  sender?: string;
  senderId?: string;
  emotionType: string;
  emotionScore: number;
  analyzedAt: string;
  timestamp?: string;
  keywords?: string[];
}

function CompactInfoDisclosure({
  label,
  cause,
  nextStep,
  align = "left",
}: {
  label: string;
  cause: string;
  nextStep: string;
  align?: "left" | "right";
}) {
  return (
    <details className="group relative">
      <summary
        aria-label={label}
        className="flex cursor-pointer list-none items-center justify-center rounded-full border border-slate-200 bg-white p-0 text-[11px] font-black text-slate-500 transition hover:border-slate-300 hover:text-slate-700 [&::-webkit-details-marker]:hidden"
      >
        <span className="inline-flex h-6 w-6 items-center justify-center">?</span>
      </summary>
      <div
        className={`absolute top-full z-10 mt-3 w-80 max-w-[calc(100vw-4rem)] rounded-2xl border border-slate-200 bg-white px-3 py-3 text-xs leading-5 text-slate-600 shadow-[0_18px_50px_rgba(15,23,42,0.12)] ${
          align === "right" ? "right-0" : "left-0"
        }`}
      >
        <p>{cause}</p>
        <p className="mt-2 font-semibold text-slate-700">{nextStep}</p>
      </div>
    </details>
  );
}

function DashboardStateCard({
  label,
  value,
  summary,
  cause,
  nextStep,
  cardClass,
}: {
  label: string;
  value: string;
  summary: string;
  cause: string;
  nextStep: string;
  cardClass: string;
}) {
  return (
    <div className={`rounded-2xl border p-4 ${cardClass}`}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">{label}</div>
          <div className="mt-2 text-sm font-bold text-slate-950">{value}</div>
        </div>
        <CompactInfoDisclosure label={`${label} 상세 안내`} cause={cause} nextStep={nextStep} align="right" />
      </div>
      <div className="mt-2 text-xs leading-5 text-slate-600">{summary}</div>
    </div>
  );
}

function useMeasuredElement() {
  const elementRef = useRef<HTMLDivElement | null>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });

  useEffect(() => {
    const node = elementRef.current;
    if (!node) {
      return;
    }

    const updateSize = () => {
      setSize({
        width: node.clientWidth,
        height: node.clientHeight,
      });
    };

    updateSize();

    const observer = new ResizeObserver(() => {
      updateSize();
    });

    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  return {
    elementRef,
    width: size.width,
    height: size.height,
    isReady: size.width > 0 && size.height > 0,
  };
}

const EMOTION_MAP: Record<string, { color: string; label: string; icon: string }> = {
  JOY: { color: "#f59e0b", label: "기쁨", icon: "J" },
  HOPE: { color: "#38bdf8", label: "기대", icon: "H" },
  NEUTRAL: { color: "#94a3b8", label: "중립", icon: "N" },
  SADNESS: { color: "#818cf8", label: "아쉬움", icon: "S" },
  ANGER: { color: "#ef4444", label: "분노", icon: "A" },
  WONDER: { color: "#c084fc", label: "놀람", icon: "W" },
  DISGUST: { color: "#fb7185", label: "불쾌", icon: "D" },
};

export default function ChannelDashboard({
  params,
}: {
  params: Promise<{ channelId: string }>;
}) {
  const { channelId } = use(params);
  const router = useRouter();

  const [stats, setStats] = useState<Record<string, number>>({
    JOY: 0,
    HOPE: 0,
    NEUTRAL: 0,
    SADNESS: 0,
    ANGER: 0,
    WONDER: 0,
    DISGUST: 0,
    TOTAL_COUNT: 0,
  });
  const [highlights, setHighlights] = useState<Highlight[]>([]);
  const [history, setHistory] = useState<HistoryItem[]>([]);
  const [trendData, setTrendData] = useState<TrendPoint[]>([]);
  const [isConnected, setIsConnected] = useState(false);
  const [latestVibe, setLatestVibe] = useState<{
    emotion: string;
    score: number;
    label: string;
    color: string;
  } | null>(null);
  const [keywordStats, setKeywordStats] = useState<Record<string, number>>({});
  const [v2Frame, setV2Frame] = useState<V2Frame | null>(null);
  const [activeTab, setActiveTab] = useState<"live" | "vod">("live");
  const [dashboardMode, setDashboardMode] = useState<"focus" | "detail">("focus");
  const [broadcastStatus, setBroadcastStatus] = useState<BroadcastStatus | null>(null);
  const [statusLoading, setStatusLoading] = useState(false);

  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimeoutRef = useRef<number | null>(null);
  const focusTrendContainer = useMeasuredElement();
  const detailTrendContainer = useMeasuredElement();

  const clearLiveConnection = useCallback(() => {
    if (reconnectTimeoutRef.current !== null) {
      window.clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    eventSourceRef.current?.close();
    eventSourceRef.current = null;
    setIsConnected(false);
  }, []);

  const {
    ownerChannelId,
    ownerProfile,
    authLoading,
    sessionNotice,
    setSessionNotice,
    handleUnauthorizedSession,
    resetOwnerSession,
  } = useOwnerDashboardSession(clearLiveConnection);

  useEffect(() => {
    if (!channelId || ownerChannelId !== channelId) {
      setBroadcastStatus(null);
      setStatusLoading(false);
      return;
    }

    let disposed = false;

    const fetchBroadcastStatus = async (silent = false) => {
      try {
        if (!silent) {
          setStatusLoading(true);
        }

        const data = await requestBroadcastStatus(channelId);

        if (!disposed) {
          setBroadcastStatus(data);
        }
      } finally {
        if (!disposed) {
          setStatusLoading(false);
        }
      }
    };

    void fetchBroadcastStatus();

    const intervalId = window.setInterval(() => {
      void fetchBroadcastStatus(true);
    }, 60_000);

    return () => {
      disposed = true;
      window.clearInterval(intervalId);
    };
  }, [channelId, ownerChannelId]);

  const hasOwnerIdentity = !!ownerChannelId;
  const isAuthorizedChannel = ownerChannelId === channelId;

  const fetchOwned = useCallback(async (url: string, init?: RequestInit) => {
    const response = await fetch(appendOwnerId(url, ownerChannelId), {
      credentials: "include",
      ...init,
      headers: {
        ...buildOwnerHeaders(ownerChannelId),
        ...(init?.headers ?? {}),
      },
    });

    if (response.status === 401) {
      handleUnauthorizedSession();
      return null;
    }

    return response;
  }, [handleUnauthorizedSession, ownerChannelId]);

  const fetchOwnedJson = useCallback(async <T,>(url: string, init?: RequestInit): Promise<T | null> => {
    const response = await fetchOwned(url, init);
    if (!response) {
      return null;
    }
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`);
    }
    return (await response.json()) as T;
  }, [fetchOwned]);

  const pollSession = usePollSession({
    roomId: channelId,
    ownerId: ownerChannelId,
    isAuthorizedChannel,
    fetchOwned,
    preferredMode: "AUTO",
  });

  const isSessionActive = pollSession.isSessionActive;

  useEffect(() => {
    if (!channelId || !isAuthorizedChannel) return;

    const fetchHistory = async () => {
      try {
        const data = await fetchOwnedJson<HistoryItem[]>(`http://localhost:8083/api/v1/stream/${channelId}/history`);
        if (!data) {
          return;
        }
        setHistory(data);

        if (data.length > 0) {
          const latest = data[0];
          setLatestVibe({
            emotion: latest.emotionType,
            score: latest.emotionScore,
            label: EMOTION_MAP[latest.emotionType]?.label || "Neutral",
            color: EMOTION_MAP[latest.emotionType]?.color || "#94a3b8",
          });
        }
      } catch {}
    };

    const connectSSE = () => {
      const url = appendOwnerId(`http://localhost:8083/api/v1/stream/${channelId}`, ownerChannelId);
      const es = new EventSource(url, { withCredentials: true });
      eventSourceRef.current = es;

      es.onopen = () => setIsConnected(true);

      es.addEventListener("stats_update", (event) => {
        try {
          setStats((prev) => ({ ...prev, ...JSON.parse(event.data) }));
        } catch {}
      });

      es.addEventListener("chat_analyzed", (event) => {
        try {
          const data: AnalyzedChatMessage = JSON.parse(event.data);
          const scores = data.emotionScores || { NEUTRAL: 1 };
          const [emotionType, emotionScore] = Object.entries(scores).reduce((a, b) =>
            a[1] > b[1] ? a : b,
          );
          const timestamp = data.timestamp || new Date().toISOString();

          setLatestVibe({
            emotion: emotionType,
            score: emotionScore,
            label: EMOTION_MAP[emotionType]?.label || "Neutral",
            color: EMOTION_MAP[emotionType]?.color || "#94a3b8",
          });

          setHistory((prev) => {
            const existingIndex = prev.findIndex((item) => item.messageId === data.messageId);
            let nextHistory = prev;

            if (existingIndex !== -1) {
              nextHistory = [...prev];
              nextHistory[existingIndex] = {
                ...nextHistory[existingIndex],
                emotionType,
                emotionScore,
                keywords: data.keywords || nextHistory[existingIndex].keywords,
                analyzedAt: data.analyzedAt || nextHistory[existingIndex].analyzedAt,
              };
            } else {
              nextHistory = [
                {
                  messageId: data.messageId,
                  emotionType,
                  emotionScore,
                  timestamp,
                  keywords: data.keywords,
                  analyzedAt: data.analyzedAt || new Date().toISOString(),
                },
                ...prev,
              ];
            }

            return nextHistory
              .sort(
                (a, b) =>
                  new Date(b.timestamp ?? b.analyzedAt ?? 0).getTime() -
                  new Date(a.timestamp ?? a.analyzedAt ?? 0).getTime(),
              )
              .slice(0, 200);
          });

          setTrendData((prev) => {
            const timeStr = new Date(timestamp).toLocaleTimeString([], {
              hour: "2-digit",
              minute: "2-digit",
              second: "2-digit",
            });
            const score =
              emotionType === "JOY" || emotionType === "HOPE"
                ? emotionScore
                : emotionType === "ANGER" || emotionType === "DISGUST"
                  ? -emotionScore
                  : 0;

            const nextEntry = { time: timeStr, score, timestamp };
            return [...prev.filter((entry) => entry.timestamp !== timestamp), nextEntry]
              .sort(
                (a, b) =>
                  new Date(a.timestamp || 0).getTime() -
                  new Date(b.timestamp || 0).getTime(),
              )
              .slice(-30);
          });
        } catch {}
      });

      es.addEventListener("highlight_detected", (event) => {
        try {
          setHighlights((prev) => [JSON.parse(event.data), ...prev].slice(0, 12));
        } catch {}
      });

      es.addEventListener("keyword_update", (event) => {
        try {
          setKeywordStats(JSON.parse(event.data));
        } catch {}
      });

      es.onerror = () => {
        setIsConnected(false);
        es.close();
        if (reconnectTimeoutRef.current !== null) {
          window.clearTimeout(reconnectTimeoutRef.current);
        }
        reconnectTimeoutRef.current = window.setTimeout(() => {
          reconnectTimeoutRef.current = null;
          connectSSE();
        }, 5000);
      };
    };

    fetchHistory();
    connectSSE();

    return () => {
      if (reconnectTimeoutRef.current !== null) {
        window.clearTimeout(reconnectTimeoutRef.current);
        reconnectTimeoutRef.current = null;
      }
      eventSourceRef.current?.close();
      eventSourceRef.current = null;
    };
  }, [channelId, fetchOwnedJson, isAuthorizedChannel, ownerChannelId]);

  const dominantMix = useMemo(
    () =>
      Object.entries(stats)
        .filter(([key]) => key in EMOTION_MAP)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 4),
    [stats],
  );

  const mergedKeywordStats = useMemo(
    () => ({
      ...keywordStats,
      ...(v2Frame?.keywords ?? []).reduce<Record<string, number>>((acc, keyword, index) => {
        acc[keyword] = Math.max(keywordStats[keyword] ?? 0, 8 - index);
        return acc;
      }, {}),
    }),
    [keywordStats, v2Frame],
  );

  const handleLogin = () => {
    window.location.href = "/api/chzzk/login";
  };

  const handleLogout = async () => {
    await fetch("/api/chzzk/logout", {
      method: "DELETE",
    });
    resetOwnerSession();
    router.replace("/");
  };

  const handleDownload = async (imageUrl: string, timestamp: string) => {
    try {
      const response = await fetch(imageUrl);
      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `highlight_${channelId}_${timestamp.replace(/[:.-]/g, "_")}.jpg`;
      document.body.appendChild(anchor);
      anchor.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(anchor);
    } catch {}
  };

  const fetchBroadcastStatusOnce = useCallback(async () => {
    setStatusLoading(true);
    try {
      const data = await requestBroadcastStatus(channelId);
      setBroadcastStatus(data);
      return data;
    } finally {
      setStatusLoading(false);
    }
  }, [channelId]);

  const handleToggleSession = async () => {
    try {
      if (!isAuthorizedChannel) {
        setSessionNotice({
          tone: "warn",
          message: "로그인한 본인 채널에서만 분석을 시작할 수 있습니다.",
        });
        return;
      }
      if (false && !isSessionActive && !broadcastStatus?.live) {
        setSessionNotice({
          tone: "warn",
          message:
            broadcastStatus?.message ||
            "현재 라이브 상태가 아니어서 분석을 시작할 수 없습니다.",
        });
        return;
      }

      if (!isSessionActive) {
        const status = await fetchBroadcastStatusOnce();
        if (!status.live) {
          setSessionNotice({
            tone: "warn",
            message:
              status.message || "현재 방송 중이 아니어서 분석을 시작할 수 없습니다.",
          });
          return;
        }
      }

      const nextState = !isSessionActive;

      if (nextState) {
        const response = await fetch(`/api/channels/${channelId}/subscribe`, {
          method: "POST",
          credentials: "include",
          headers: buildOwnerHeaders(ownerChannelId),
        });
        if (response.status === 403) {
          setSessionNotice({
            tone: "warn",
            message: "본인 채널만 분석할 수 있습니다.",
          });
          return;
        }
        if (!response.ok) {
          if (response.status === 401) {
            handleUnauthorizedSession();
            return;
          }
          throw new Error(await readCollectorErrorMessage(response, "분석 시작에 실패했습니다."));
        }
      } else {
        const response = await fetch(`/api/channels/${channelId}/subscribe`, {
          method: "DELETE",
          credentials: "include",
          headers: buildOwnerHeaders(ownerChannelId),
        });
        if (response.status === 401) {
          handleUnauthorizedSession();
          return;
        }
      }

      await pollSession.setSessionActive(nextState);
      setSessionNotice({
        tone: "good",
        message: nextState
          ? "실시간 분석을 시작했습니다. 방송 흐름을 바로 반영합니다."
          : "실시간 분석을 중지했습니다.",
      });
    } catch (error) {
      console.error("Failed to toggle session:", error);
      if (error instanceof Error) {
        setSessionNotice({ tone: "warn", message: error.message });
        return;
      }
      setSessionNotice({
        tone: "warn",
        message: "분석 상태를 변경하지 못했습니다. 백엔드 상태를 확인해 주세요.",
      });
    }
  };

  const heroNotice = buildHeroNotice({
    sessionNotice,
    statusLoading,
    broadcastStatus,
  });
  const accessState = buildAccessState({
    authLoading,
    hasOwnerIdentity,
    ownerProfile,
    isAuthorizedChannel,
    channelId,
  });
  const liveState = buildLiveState({
    statusLoading,
    broadcastStatus,
    isSessionActive,
  });
  const sessionState = buildSessionState({
    hasOwnerIdentity,
    isAuthorizedChannel,
    isSessionActive,
    broadcastStatus,
  });
  const connectionState = buildConnectionState({
    hasOwnerIdentity,
    isAuthorizedChannel,
    isSessionActive,
    isConnected,
  });
  const { disabled: primaryActionDisabled, label: primaryActionLabel } =
    buildPrimaryActionState({
      hasOwnerIdentity,
      isAuthorizedChannel,
      statusLoading,
      isSessionActive,
      broadcastStatus,
    });
  const connectionMetricDescription =
    connectionState.label === "정상 연결"
      ? "프레임 수신 중"
      : connectionState.label === "재연결 중"
        ? "자동 재시도 중"
        : connectionState.label === "세션 시작 전"
          ? "연결 전"
          : connectionState.label === "권한 없음"
            ? "로그인 필요"
            : connectionState.label === "연결 제한"
              ? "본인 채널만 가능"
              : "준비 중";
  const sessionMetricDescription =
    sessionState.label === "진행 중"
      ? "채팅 수집 중"
      : sessionState.label === "시작 가능"
        ? "버튼으로 시작"
        : sessionState.label === "잠김"
          ? "로그인 필요"
          : sessionState.label === "권한 제한"
            ? "본인 채널만 가능"
            : sessionState.label === "방송 대기"
              ? "대기 중"
              : "준비 중";
  const latestVibeDescription = latestVibe
    ? `${(latestVibe.score * 100).toFixed(0)}% ${latestVibe.label}`
    : "아직 데이터 없음";

  return (
    <div className="space-y-8">
      <section className="rounded-[36px] border border-slate-200 bg-white p-8 shadow-[0_24px_80px_rgba(15,23,42,0.08)]">
        <div className="flex flex-col gap-8 xl:flex-row xl:items-start xl:justify-between">
          <div className="max-w-3xl space-y-4">
            <div
              className={`inline-flex items-center gap-2 rounded-full px-3 py-1 text-[10px] font-black uppercase tracking-[0.25em] ${
                activeTab === "live"
                  ? "border border-emerald-200 bg-emerald-50 text-emerald-700"
                  : "border border-indigo-200 bg-indigo-50 text-indigo-700"
              }`}
            >
              {activeTab === "live" ? <Radio className="h-3.5 w-3.5" /> : <BarChart3 className="h-3.5 w-3.5" />}
              {activeTab === "live" ? "내 방송 대시보드" : "방송 다시보기"}
            </div>
            <div>
              <h1 className="text-4xl font-black tracking-tight text-slate-950">
                {activeTab === "live"
                  ? "내 방송을 바로 읽는 대시보드"
                  : "VOD 편집 후보를 고르는 워크스페이스"}
              </h1>
              <p className="mt-3 max-w-2xl text-base leading-7 text-slate-600">
                {activeTab === "live"
                  ? "핵심 신호만 먼저 보고 필요할 때 상세를 펼칩니다."
                  : "VOD를 조회한 뒤 바로 분석을 열어 후보를 검토합니다."}
              </p>
            </div>
          </div>

          <div className="w-full max-w-xl space-y-4">
            <div className="grid grid-cols-2 rounded-[24px] border border-slate-200 bg-slate-100 p-1.5">
              <button
                onClick={() => setActiveTab("live")}
                className={`inline-flex items-center justify-center gap-2 rounded-[18px] px-4 py-3 text-sm font-black transition ${
                  activeTab === "live"
                    ? "bg-slate-950 text-white shadow-sm"
                    : "text-slate-500 hover:text-slate-950"
                }`}
              >
                <Radio className="h-4 w-4" />
                실시간 보기
              </button>
              <button
                onClick={() => setActiveTab("vod")}
                className={`inline-flex items-center justify-center gap-2 rounded-[18px] px-4 py-3 text-sm font-black transition ${
                  activeTab === "vod"
                    ? "bg-slate-950 text-white shadow-sm"
                    : "text-slate-500 hover:text-slate-950"
                }`}
              >
                <BarChart3 className="h-4 w-4" />
                다시보기
              </button>
            </div>

            {activeTab === "live" ? (
              <div className={`rounded-[28px] border p-5 ${accessState.panelClass}`}>
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0 flex-1">
                    <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">
                      접근 상태
                    </div>
                    <div className="mt-3 text-xl font-black text-slate-950">{accessState.title}</div>
                    <div className="mt-2 max-w-2xl text-sm leading-6 text-slate-600">{accessState.description}</div>
                  </div>

                  <div className={`inline-flex h-10 items-center gap-2 rounded-full border px-4 text-xs font-black uppercase tracking-[0.2em] ${accessState.badgeClass}`}>
                    <ShieldCheck className="h-4 w-4" />
                    {accessState.badgeLabel}
                  </div>
                </div>

                <div className="mt-4 flex justify-start">
                  <CompactInfoDisclosure
                    label="접근 상태 상세 안내"
                    cause={accessState.cause}
                    nextStep={accessState.nextStep}
                  />
                </div>

                {heroNotice ? (
                  <div
                    className={`mt-4 rounded-2xl border px-4 py-3 text-sm font-semibold ${
                      heroNotice.tone === "good"
                        ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                        : "border-amber-200 bg-amber-50 text-amber-700"
                    }`}
                  >
                    {heroNotice.message}
                  </div>
                ) : null}

                <div className="mt-5 flex flex-wrap items-center gap-3">
                  {!hasOwnerIdentity ? (
                    <button
                      onClick={handleLogin}
                      className="inline-flex items-center gap-2 rounded-2xl bg-emerald-500 px-5 py-3 text-sm font-black text-slate-950 transition hover:bg-emerald-400"
                    >
                      <Sparkles className="h-4 w-4" />
                      치지직 로그인
                    </button>
                  ) : (
                    <button
                      onClick={handleToggleSession}
                      disabled={primaryActionDisabled}
                      className={`inline-flex items-center gap-2 rounded-2xl px-5 py-3 text-sm font-black transition ${
                        primaryActionDisabled
                          ? "cursor-not-allowed bg-slate-200 text-slate-500"
                          : isSessionActive
                            ? "bg-rose-500 text-white hover:bg-rose-400"
                            : "bg-sky-500 text-slate-950 hover:bg-sky-400"
                      }`}
                    >
                      <RefreshCw className="h-4 w-4" />
                      {primaryActionLabel}
                    </button>
                  )}

                  {hasOwnerIdentity ? (
                    <button
                      onClick={handleLogout}
                      className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-5 py-3 text-sm font-black text-slate-700 transition hover:bg-slate-100"
                    >
                      <Lock className="h-4 w-4" />
                      로그아웃
                    </button>
                  ) : null}
                </div>

                <div className="mt-5 grid gap-3 sm:grid-cols-2">
                  <div className="rounded-2xl border border-slate-200 bg-white p-4">
                    <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">방송 채널</div>
                    <div className="mt-2 truncate font-mono text-sm text-slate-800">{channelId}</div>
                  </div>
                  <DashboardStateCard
                    label="라이브 상태"
                    value={liveState.label}
                    summary={liveState.summary}
                    cause={liveState.cause}
                    nextStep={liveState.nextStep}
                    cardClass={liveState.cardClass}
                  />
                  <DashboardStateCard
                    label="분석 세션"
                    value={sessionState.label}
                    summary={sessionState.summary}
                    cause={sessionState.cause}
                    nextStep={sessionState.nextStep}
                    cardClass={sessionState.cardClass}
                  />
                  <DashboardStateCard
                    label="실시간 연결"
                    value={connectionState.label}
                    summary={connectionState.summary}
                    cause={connectionState.cause}
                    nextStep={connectionState.nextStep}
                    cardClass={connectionState.cardClass}
                  />
                </div>
              </div>
            ) : (
              <div className="rounded-[28px] border border-slate-200 bg-slate-50 p-5">
                <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">VOD 진행 순서</div>
                <div className="mt-3 flex flex-wrap gap-2">
                  {[
                    "1. 조회",
                    "2. 상태 확인",
                    "3. 워크스페이스 열기",
                  ].map((step) => (
                    <div
                      key={step}
                      className="rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700"
                    >
                      {step}
                    </div>
                  ))}
                </div>
                <div className="mt-3 text-sm text-slate-600">선택한 VOD 하나를 기준으로 바로 이어집니다.</div>
              </div>
            )}
          </div>
        </div>
      </section>
      {activeTab === "vod" ? (
        <div className="min-h-[780px]">
          <VodHighlightBoard personalizationEnabled={hasOwnerIdentity} />
        </div>
      ) : (
        <>
          <section className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">보기 밀도</div>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-600">핵심만 먼저 보고, 설명은 필요할 때만 펼칩니다.</p>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <div className="inline-flex rounded-full border border-slate-200 bg-slate-50 p-1">
                <button
                  onClick={() => setDashboardMode("focus")}
                  className={`rounded-full px-4 py-2 text-sm font-black transition ${
                    dashboardMode === "focus"
                      ? "bg-white text-slate-950 shadow-sm"
                      : "text-slate-500 hover:text-slate-950"
                  }`}
                >
                  핵심 보기
                </button>
                <button
                  onClick={() => setDashboardMode("detail")}
                  className={`rounded-full px-4 py-2 text-sm font-black transition ${
                    dashboardMode === "detail"
                      ? "bg-white text-slate-950 shadow-sm"
                      : "text-slate-500 hover:text-slate-950"
                  }`}
                >
                  상세 보기
                </button>
              </div>

              <div className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-xs font-black uppercase tracking-[0.18em] text-slate-500">
                <Clock3 className="h-3.5 w-3.5" />
                스트리머 본인 방송 전용
              </div>
            </div>
          </section>

          <section className="grid gap-5 md:grid-cols-2 xl:grid-cols-4">
            <DashboardMetricCard
              label="분석된 채팅 수"
              value={`${stats.TOTAL_COUNT || 0}`}
              description="현재 방송 누적"
            />
            <DashboardMetricCard
              label="연결 상태"
              value={connectionState.label}
              description={connectionMetricDescription}
              tone={isConnected ? "good" : connectionState.label === "세션 시작 전" ? "default" : "warn"}
            />
            <DashboardMetricCard
              label="수집 상태"
              value={sessionState.label}
              description={sessionMetricDescription}
              tone={isSessionActive ? "good" : sessionState.label === "방송 대기" ? "default" : "warn"}
            />
            <DashboardMetricCard
              label="현재 분위기"
              value={latestVibe ? latestVibe.label : "대기 중"}
              description={latestVibeDescription}
              tone={latestVibe ? "good" : "default"}
            />
          </section>

          {isAuthorizedChannel ? (
            <V2InsightsPanel roomId={channelId} ownerId={ownerChannelId} onFrame={setV2Frame} />
          ) : (
            <section className="rounded-[30px] border border-amber-200 bg-amber-50 p-6">
              <div className="flex items-start gap-3">
                <AlertCircle className="mt-0.5 h-5 w-5 text-amber-600" />
                <div>
                  <h2 className="text-lg font-black text-slate-950">인증된 소유자 계정에서만 상세 분석을 볼 수 있습니다</h2>
                  <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600">
                    이 채널을 소유한 치지직 계정으로 로그인해야 실시간 가드레일과 상세 분석을 열 수 있습니다.
                  </p>
                </div>
              </div>
            </section>
          )}

          {dashboardMode === "focus" ? (
        <section className="grid gap-6 xl:grid-cols-[1.1fr_0.9fr]">
          <div className="space-y-6">
            <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
              <div className="mb-5 flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">지금 보면 좋은 것</div>
                  <div className="mt-2 text-xl font-black text-slate-950">방송 흐름 요약</div>
                </div>
                <Waves className="h-5 w-5 text-sky-300" />
              </div>

              <div className="grid gap-4 md:grid-cols-3">
                <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                  <div className="text-[10px] font-black tracking-[0.18em] text-slate-500">현재 반응</div>
                  <div className="mt-2 text-2xl font-black text-slate-950">{latestVibe?.label || "대기 중"}</div>
                  <p className="mt-2 text-sm leading-6 text-slate-600">
                    {latestVibe
                      ? `${(latestVibe.score * 100).toFixed(0)}% 비중으로 가장 강한 반응입니다.`
                      : "채팅이 들어오면 바로 갱신됩니다."}
                  </p>
                </div>
                <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                  <div className="text-[10px] font-black tracking-[0.18em] text-slate-500">대표 주제</div>
                  <div className="mt-2 text-2xl font-black text-slate-950">{v2Frame?.topicLabel || "수집 중"}</div>
                  <p className="mt-2 text-sm leading-6 text-slate-600">
                    반복해서 올라오는 주제를 묶어 보여줍니다.
                  </p>
                </div>
                <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                  <div className="text-[10px] font-black tracking-[0.18em] text-slate-500">주의 신호</div>
                  <div className="mt-2 text-2xl font-black text-slate-950">{v2Frame?.trustSummary?.filteredCount ?? 0}건</div>
                  <p className="mt-2 text-sm leading-6 text-slate-600">
                    격리되었거나 주의가 필요한 반응 수입니다.
                  </p>
                </div>
              </div>
            </div>

            <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
              <div className="mb-5 flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">흐름 변화</div>
                  <div className="mt-2 text-xl font-black text-slate-950">감정 추이</div>
                </div>
                <Waves className="h-5 w-5 text-sky-300" />
              </div>

              <div ref={focusTrendContainer.elementRef} className="h-[260px] min-w-0 min-h-[260px]">
                <TrendAreaChart
                  width={focusTrendContainer.width}
                  height={focusTrendContainer.height}
                  data={trendData}
                />
              </div>
            </div>
          </div>

          <div className="space-y-6">
            <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
              <div className="mb-5 flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">주요 장면</div>
                  <div className="mt-2 text-xl font-black text-slate-950">최근 하이라이트</div>
                </div>
                <Sparkles className="h-5 w-5 text-pink-300" />
              </div>

              <div className="space-y-3">
                {highlights.length === 0 ? (
                  <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-600">
                    반응이 크게 튄 순간이 생기면 이곳에 정리됩니다.
                  </div>
                ) : (
                  highlights.slice(0, 3).map((highlight) => (
                    <div key={highlight.id} className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                      <div className="text-sm font-black text-slate-950">{highlight.emotionType}</div>
                      <div className="mt-2 text-sm leading-6 text-slate-700">{highlight.topMessage}</div>
                      <div className="mt-2 text-xs text-slate-500">{new Date(highlight.timestamp).toLocaleString()}</div>
                    </div>
                  ))
                )}
              </div>
            </div>
          </div>
        </section>
          ) : (
      <section className="grid gap-6 xl:grid-cols-[1.35fr_0.85fr]">
        <div className="space-y-6">
          <div className="grid gap-6 lg:grid-cols-[0.82fr_1.18fr]">
            <div className="min-w-0 rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
              <div className="mb-5 flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">현재 분위기</div>
                  <div className="mt-2 text-xl font-black text-slate-950">실시간 반응</div>
                </div>
                <div className="rounded-full border border-slate-200 bg-slate-100 px-3 py-1 text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">
                  {v2Frame ? `민심 ${(v2Frame.balance * 100).toFixed(0)}%` : "실시간"}
                </div>
              </div>
              <MoodGauge
                emotion={latestVibe?.emotion || "NEUTRAL"}
                score={latestVibe?.score || 0}
                label={latestVibe?.label || "중립"}
                color={latestVibe?.color || "#94a3b8"}
              />
              <div className="mt-6 rounded-2xl border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
                {v2Frame?.mentalBuffer ? (
                  <>
                    보정된 부정 <span className="font-black text-slate-950">{(v2Frame.mentalBuffer.emaNegative * 100).toFixed(0)}%</span>
                    {" · "}
                    보정된 긍정 <span className="font-black text-slate-950">{(v2Frame.mentalBuffer.emaPositive * 100).toFixed(0)}%</span>
                  </>
                ) : (
                  <>가드레일 스트림이 들어오면 급격한 반응 변화를 완만하게 보여줍니다.</>
                )}
              </div>
            </div>

            <div className="min-w-0 rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
              <div className="mb-5 flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">추이</div>
                  <div className="mt-2 text-xl font-black text-slate-950">감정 변화</div>
                </div>
                <Waves className="h-5 w-5 text-sky-300" />
              </div>

              <div ref={detailTrendContainer.elementRef} className="h-[260px] min-w-0 min-h-[260px]">
                <TrendAreaChart
                  width={detailTrendContainer.width}
                  height={detailTrendContainer.height}
                  data={trendData}
                />
              </div>
            </div>
          </div>

          <div className="min-w-0 rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">상세 신호</div>
                <div className="mt-2 text-xl font-black text-slate-950">감정 분포와 키워드</div>
              </div>
              <Target className="h-5 w-5 text-indigo-300" />
            </div>

            <div className="grid gap-6 lg:grid-cols-[0.92fr_1.08fr]">
              <div className="min-w-0 rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                <EmotionHeatmap history={history} emotionMap={EMOTION_MAP} />
              </div>
              <div className="min-w-0 rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">핵심 키워드</div>
                    <div className="mt-1 text-sm text-slate-600">
                      {v2Frame?.topicLabel || "실시간 채팅에서 반복되는 주제"}
                    </div>
                  </div>
                </div>
                <div className="h-[340px]">
                  <KeywordBubbleChart data={mergedKeywordStats} />
                </div>
              </div>
            </div>
          </div>
        </div>

        <div className="space-y-6">
          <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">Mix</div>
                <div className="mt-2 text-xl font-black text-slate-950">Emotion composition</div>
              </div>
              <BarChart3 className="h-5 w-5 text-amber-300" />
            </div>

            <div className="space-y-4">
              {dominantMix.map(([emotion, count]) => {
                const ratio = stats.TOTAL_COUNT ? (count / stats.TOTAL_COUNT) * 100 : 0;
                const config = EMOTION_MAP[emotion];

                return (
                  <div key={emotion} className="space-y-2">
                    <div className="flex items-center justify-between text-sm">
                      <div className="flex items-center gap-2 font-bold text-slate-950">
                        <span
                          className="inline-flex h-8 w-8 items-center justify-center rounded-full text-xs font-black"
                          style={{ backgroundColor: `${config.color}22`, color: config.color }}
                        >
                          {config.icon}
                        </span>
                        {config.label}
                      </div>
                      <span className="font-mono text-slate-400">{ratio.toFixed(0)}%</span>
                    </div>
                    <div className="h-2 rounded-full bg-slate-900/80">
                      <div
                        className="h-2 rounded-full"
                        style={{ width: `${ratio}%`, backgroundColor: config.color }}
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          <PollCard session={pollSession} />

          <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">하이라이트</div>
                <div className="mt-2 text-xl font-black text-slate-950">반응이 컸던 순간</div>
              </div>
              <Sparkles className="h-5 w-5 text-pink-300" />
            </div>

            <div className="space-y-3">
              {highlights.length === 0 ? (
                <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-600">
                  반응이 크게 튄 장면이 생기면 이곳에 자동으로 쌓입니다.
                </div>
              ) : (
                highlights.map((highlight) => (
                  <div key={highlight.id} className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                    <div className="flex items-start justify-between gap-4">
                      <div className="space-y-2">
                        <div className="text-sm font-black text-slate-950">{highlight.emotionType}</div>
                        <div className="text-sm leading-6 text-slate-700">{highlight.topMessage}</div>
                        <div className="text-xs text-slate-500">
                          강도 {highlight.peakScore.toFixed(2)} · {new Date(highlight.timestamp).toLocaleString()}
                        </div>
                      </div>

                      <button
                        onClick={() => handleDownload(highlight.liveImageUrl, highlight.timestamp)}
                        className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-3 py-2 text-xs font-black text-slate-700 transition hover:bg-slate-100"
                      >
                        <Download className="h-4 w-4" />
                        저장
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </section>
          )}

          {dashboardMode === "detail" ? <PollCard session={pollSession} variant="history" /> : null}

        </>
      )}
    </div>
  );
}
