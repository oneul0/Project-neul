"use client";

import { use, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { AreaChart, Area, CartesianGrid, XAxis, YAxis, Tooltip } from "recharts";
import {
  AlertCircle,
  BarChart3,
  CheckCircle2,
  Clock3,
  Download,
  Lock,
  Radio,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  Target,
  Users,
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

interface OwnerProfile {
  authenticated: boolean;
  channelId?: string;
  channelName?: string;
  expiresAt?: string;
  refreshed?: boolean;
  message?: string;
}

interface BroadcastStatus {
  live: boolean;
  status: "live" | "offline" | "failed";
  message?: string;
  liveTitle?: string;
  viewerCount?: number;
}

interface InlineNotice {
  tone: "good" | "warn";
  message: string;
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

const EMPTY_OWNER_PROFILE: OwnerProfile = {
  authenticated: false,
  message: "치지직 로그인 후 대시보드를 사용할 수 있습니다.",
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
  const [trendData, setTrendData] = useState<{ time: string; score: number; timestamp?: string }[]>([]);
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
  const [ownerChannelId, setOwnerChannelId] = useState("");
  const [ownerProfile, setOwnerProfile] = useState<OwnerProfile>(EMPTY_OWNER_PROFILE);
  const [authLoading, setAuthLoading] = useState(true);
  const [sessionNotice, setSessionNotice] = useState<InlineNotice | null>(null);
  const [broadcastStatus, setBroadcastStatus] = useState<BroadcastStatus | null>(null);
  const [statusLoading, setStatusLoading] = useState(false);

  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimeoutRef = useRef<number | null>(null);
  const focusTrendContainer = useMeasuredElement();
  const detailTrendContainer = useMeasuredElement();

  useEffect(() => {
    let disposed = false;

    const fetchOwnerProfile = async (silent = false) => {
      try {
        if (!silent) {
          setAuthLoading(true);
        }
        const response = await fetch("/api/chzzk/me", {
          cache: "no-store",
        });
        const profile = (await response.json()) as OwnerProfile;

        if (disposed) {
          return;
        }

        if (!response.ok || !profile.authenticated) {
          setOwnerProfile(profile);
          setOwnerChannelId("");
           setSessionNotice({
             tone: "warn",
             message: profile.message || "다시 로그인해 주세요.",
           });
          return;
        }

        setOwnerProfile(profile);
        setOwnerChannelId(profile.channelId ?? "");
        if (profile.refreshed) {
           setSessionNotice({
             tone: "good",
             message: "로그인 세션이 자동으로 연장되었습니다.",
           });
        }
      } catch {
        if (!disposed) {
          setOwnerProfile(EMPTY_OWNER_PROFILE);
          setOwnerChannelId("");
           setSessionNotice({
             tone: "warn",
             message: "치지직 로그인 상태를 확인하지 못했습니다.",
           });
        }
      } finally {
        if (!disposed) {
          setAuthLoading(false);
        }
      }
    };

    fetchOwnerProfile();

    const intervalId = window.setInterval(() => {
      fetchOwnerProfile(true);
    }, 60_000);

    return () => {
      disposed = true;
      window.clearInterval(intervalId);
    };
  }, []);

  useEffect(() => {
    if (!sessionNotice) {
      return;
    }

    const timeoutId = window.setTimeout(() => setSessionNotice(null), 5000);
    return () => window.clearTimeout(timeoutId);
  }, [sessionNotice]);

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

        const response = await fetch(`/api/channels/${channelId}/status`, {
          cache: "no-store",
          credentials: "include",
        });
        const data = (await response.json()) as BroadcastStatus;

        if (!disposed) {
          setBroadcastStatus(data);
        }
      } catch {
        if (!disposed) {
          setBroadcastStatus({
            live: false,
            status: "failed",
            message: "방송 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
          });
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

  const handleUnauthorizedSession = useCallback((message = "로그인 세션이 만료되었습니다. 다시 로그인해 주세요.") => {
    if (reconnectTimeoutRef.current !== null) {
      window.clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    eventSourceRef.current?.close();
    eventSourceRef.current = null;
    setOwnerProfile({
      authenticated: false,
      message,
    });
    setOwnerChannelId("");
    setIsConnected(false);
    setSessionNotice({ tone: "warn", message });
  }, []);

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
      } catch {
        // Wait for stream recovery.
      }
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
    setOwnerProfile(EMPTY_OWNER_PROFILE);
    setOwnerChannelId("");
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

  const readCollectorErrorMessage = async (response: Response, fallback: string) => {
    try {
      const data = (await response.json()) as { error?: string; message?: string; status?: string };
      const message = data.message || data.error || data.status;
      if (!message) {
        return fallback;
      }
      if (message.includes("chatChannelId")) {
        return "현재 라이브 중이 아니거나 채팅 수집이 허용되지 않은 채널입니다. 방송 상태를 먼저 확인해 주세요.";
      }
      if (message.includes("access token")) {
        return "채팅 접근 토큰을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
      }
      return message;
    } catch {
      return fallback;
    }
  };

  const fetchBroadcastStatusOnce = async () => {
    setStatusLoading(true);
    try {
      const response = await fetch(`/api/channels/${channelId}/status`, {
        cache: "no-store",
        credentials: "include",
      });
      const data = (await response.json()) as BroadcastStatus;
      setBroadcastStatus(data);
      return data;
    } catch {
      const fallback: BroadcastStatus = {
        live: false,
        status: "failed",
        message: "방송 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      };
      setBroadcastStatus(fallback);
      return fallback;
    } finally {
      setStatusLoading(false);
    }
  };

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

  const renderMetric = (
    label: string,
    value: string,
    description: string,
    tone: "default" | "good" | "warn" = "default",
  ) => {
    const toneClass =
      tone === "good"
        ? "border-emerald-200 bg-emerald-50"
        : tone === "warn"
          ? "border-amber-200 bg-amber-50"
          : "border-slate-200 bg-white";

    return (
      <div className={`rounded-[28px] border p-5 ${toneClass}`}>
        <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">{label}</div>
        <div className="mt-4 text-3xl font-black text-slate-950">{value}</div>
        <div className="mt-2 text-sm text-slate-600">{description}</div>
      </div>
    );
  };

  const renderTrendChart = (width: number, height: number) => {
    if (width <= 0 || height <= 0) {
      return (
        <div className="flex h-full min-h-[260px] items-center justify-center rounded-[24px] border border-dashed border-slate-300 bg-slate-50 text-sm font-semibold text-slate-500">
          감정 추이 차트를 준비하는 중입니다.
        </div>
      );
    }

    return (
      <AreaChart width={width} height={height} data={trendData}>
        <defs>
          <linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#38bdf8" stopOpacity={0.35} />
            <stop offset="100%" stopColor="#38bdf8" stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid stroke="rgba(255,255,255,0.06)" vertical={false} />
        <XAxis dataKey="time" tick={{ fill: "#94a3b8", fontSize: 11 }} tickLine={false} axisLine={false} />
        <YAxis domain={[-1, 1]} tick={{ fill: "#94a3b8", fontSize: 11 }} tickLine={false} axisLine={false} />
        <Tooltip
          contentStyle={{
            background: "rgba(15,23,42,0.94)",
            border: "1px solid rgba(255,255,255,0.08)",
            borderRadius: 16,
            color: "#fff",
          }}
        />
        <Area type="monotone" dataKey="score" stroke="#38bdf8" strokeWidth={3} fill="url(#trendFill)" />
      </AreaChart>
    );
  };

  const heroNotice: InlineNotice | null =
    sessionNotice ??
    (!statusLoading && broadcastStatus?.status === "failed" && broadcastStatus.message
      ? { tone: "warn", message: broadcastStatus.message }
      : null);

  const accessSummaryTitle = authLoading
    ? "로그인 상태를 확인하는 중입니다"
    : hasOwnerIdentity
      ? `${ownerProfile.channelName || "내 채널"} 계정으로 로그인됨`
      : "로그인이 필요합니다";

  const accessSummaryDescription = authLoading
    ? "치지직 로그인과 채널 소유 여부를 확인하고 있습니다."
    : hasOwnerIdentity
      ? `세션 만료 ${ownerProfile.expiresAt ? new Date(ownerProfile.expiresAt).toLocaleString() : "곧 만료"}`
      : ownerProfile.message || "본인 방송 소유자만 이 대시보드를 열 수 있습니다.";

  const accessBadgeClass = isAuthorizedChannel
    ? "border-emerald-200 bg-emerald-50 text-emerald-700"
    : "border-amber-200 bg-amber-50 text-amber-700";

  const liveStatusLabel = statusLoading
    ? "확인 중"
    : broadcastStatus?.status === "live"
      ? "방송 중"
      : broadcastStatus?.status === "failed"
        ? "확인 필요"
        : "오프라인";

  const liveStatusDescription = statusLoading
    ? "현재 방송 상태를 확인하고 있습니다."
    : broadcastStatus?.liveTitle
      ? `${broadcastStatus.liveTitle}${typeof broadcastStatus.viewerCount === "number" ? ` · 시청자 ${broadcastStatus.viewerCount.toLocaleString()}명` : ""}`
      : broadcastStatus?.message || "방송 제목과 상태가 여기에 표시됩니다.";

  const primaryActionDisabled = hasOwnerIdentity ? !isAuthorizedChannel || statusLoading : false;

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
                  ? "지금 방송 분위기를 빠르게 보는 화면"
                  : "방송 다시보기 하이라이트를 먼저 고르는 화면"}
              </h1>
              <p className="mt-3 max-w-2xl text-base leading-7 text-slate-600">
                {activeTab === "live"
                  ? "실시간 보기에서는 민심 흐름과 대표 반응을 먼저 보여주고, 상세 보기에서 키워드·투표·세부 로그를 펼쳐 확인할 수 있습니다."
                  : "다시보기에서는 먼저 VOD를 찾고, 선택한 영상의 상태와 메인 분석 워크스페이스를 중심으로 편집 후보를 검토할 수 있습니다."}
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
              <div className="rounded-[28px] border border-slate-200 bg-slate-50 p-5">
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">
                      접근 및 세션 요약
                    </div>
                    <div className="mt-3 text-xl font-black text-slate-950">{accessSummaryTitle}</div>
                    <div className="mt-2 text-sm leading-6 text-slate-600">{accessSummaryDescription}</div>
                  </div>

                  <div className={`inline-flex h-10 items-center gap-2 rounded-full border px-4 text-xs font-black uppercase tracking-[0.2em] ${accessBadgeClass}`}>
                    <ShieldCheck className="h-4 w-4" />
                    {isAuthorizedChannel ? "Authorized" : "Restricted"}
                  </div>
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
                      {isSessionActive ? "분석 중지" : "분석 시작"}
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
                  <div className="rounded-2xl border border-slate-200 bg-white p-4">
                    <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">라이브 상태</div>
                    <div className="mt-2 text-sm font-bold text-slate-950">{liveStatusLabel}</div>
                    <div className="mt-1 text-xs leading-5 text-slate-500">{liveStatusDescription}</div>
                  </div>
                  <div className="rounded-2xl border border-slate-200 bg-white p-4">
                    <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">분석 세션</div>
                    <div className="mt-2 text-sm font-bold text-slate-950">
                      {isSessionActive ? "채팅 수집 및 분석 중" : "대기 중"}
                    </div>
                    <div className="mt-1 text-xs leading-5 text-slate-500">
                      {isSessionActive
                        ? "실시간 채팅과 투표 상태를 함께 추적하고 있습니다."
                        : "방송이 시작되면 분석 시작으로 바로 전환할 수 있습니다."}
                    </div>
                  </div>
                  <div className="rounded-2xl border border-slate-200 bg-white p-4">
                    <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">실시간 연결</div>
                    <div className="mt-2 text-sm font-bold text-slate-950">
                      {isConnected ? "이벤트 스트림 연결됨" : "재연결 중"}
                    </div>
                    <div className="mt-1 text-xs leading-5 text-slate-500">
                      {isConnected
                        ? "분석 결과가 들어오는 즉시 화면에 반영됩니다."
                        : "연결이 돌아오면 최신 상태로 다시 맞춰집니다."}
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <div className="rounded-[28px] border border-slate-200 bg-slate-50 p-5">
                <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">
                  VOD Workflow
                </div>
                <div className="mt-3 text-xl font-black text-slate-950">
                  조회한 영상 하나를 중심으로 편집 후보를 검토합니다
                </div>
                <div className="mt-4 grid gap-3 sm:grid-cols-3">
                  {[
                    "1. VOD 번호나 링크를 조회합니다.",
                    "2. 선택한 영상의 상태와 메타데이터를 확인합니다.",
                    "3. 분석 워크스페이스에서 타임라인과 후보를 살펴봅니다.",
                  ].map((step) => (
                    <div key={step} className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm leading-6 text-slate-600">
                      {step}
                    </div>
                  ))}
                </div>
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
              <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-600">
                핵심 보기에서는 우선순위가 높은 신호만 먼저 보여주고, 상세 보기에서 세부 로그와 보조 패널을 펼칩니다.
              </p>
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
            {renderMetric(
              "분석된 채팅 수",
              `${stats.TOTAL_COUNT || 0}`,
              "현재 방송에서 분석된 전체 채팅 수입니다.",
              "default",
            )}
            {renderMetric(
              "연결 상태",
              isConnected ? "정상" : "재연결 중",
              isConnected ? "실시간 이벤트 스트림이 정상입니다." : "실시간 스트림을 다시 연결하고 있습니다.",
              isConnected ? "good" : "warn",
            )}
            {renderMetric(
              "수집 상태",
              isSessionActive ? "진행 중" : "중지됨",
              isSessionActive ? "채팅 수집과 투표 상태 추적이 켜져 있습니다." : "분석이 현재 중지된 상태입니다.",
              isSessionActive ? "good" : "warn",
            )}
            {renderMetric(
              "현재 분위기",
              latestVibe ? latestVibe.label : "대기 중",
              latestVibe ? `가장 강한 반응은 ${(latestVibe.score * 100).toFixed(0)}% ${latestVibe.label}입니다.` : "아직 분석된 채팅이 없습니다.",
              latestVibe ? "good" : "default",
            )}
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
                      ? `최근 채팅에서 ${(latestVibe.score * 100).toFixed(0)}% 비중으로 가장 강하게 나타난 감정입니다.`
                      : "분석된 채팅이 들어오면 현재 분위기를 보여줍니다."}
                  </p>
                </div>
                <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                  <div className="text-[10px] font-black tracking-[0.18em] text-slate-500">대표 주제</div>
                  <div className="mt-2 text-2xl font-black text-slate-950">{v2Frame?.topicLabel || "수집 중"}</div>
                  <p className="mt-2 text-sm leading-6 text-slate-600">
                    지금 채팅에서 반복적으로 드러나는 주제를 간단히 묶어서 보여줍니다.
                  </p>
                </div>
                <div className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                  <div className="text-[10px] font-black tracking-[0.18em] text-slate-500">주의 신호</div>
                  <div className="mt-2 text-2xl font-black text-slate-950">{v2Frame?.trustSummary?.filteredCount ?? 0}건</div>
                  <p className="mt-2 text-sm leading-6 text-slate-600">
                    신뢰도가 낮아 격리되거나 주의가 필요한 반응 수를 보여줍니다.
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
                {renderTrendChart(focusTrendContainer.width, focusTrendContainer.height)}
              </div>
            </div>
          </div>

          <div className="space-y-6">
            <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
              <div className="mb-5 flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">왜 이 화면을 보나요?</div>
                  <div className="mt-2 text-xl font-black text-slate-950">핵심 목적</div>
                </div>
                <Target className="h-5 w-5 text-indigo-300" />
              </div>
              <div className="space-y-3 text-sm leading-7 text-slate-600">
                <p>1. 전체 민심이 어느 방향인지 빠르게 확인합니다.</p>
                <p>2. 악성 반응 하나에 흔들리지 않도록 보정된 흐름을 봅니다.</p>
                <p>3. 지금 방송 맥락을 대표하는 채팅 몇 개만 바로 읽습니다.</p>
              </div>
            </div>

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
                {renderTrendChart(detailTrendContainer.width, detailTrendContainer.height)}
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

          {dashboardMode === "detail" ? (
      <section className="grid gap-5 lg:grid-cols-3">
        <div className="rounded-[28px] border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-3">
            <CheckCircle2 className="h-5 w-5 text-emerald-300" />
            <div>
              <div className="text-sm font-black text-slate-950">핵심 화면 중심 구성</div>
              <div className="mt-1 text-sm text-slate-600">
                지금 꼭 봐야 할 정보와 상세 정보를 구분해서 볼 수 있습니다.
              </div>
            </div>
          </div>
        </div>
        <div className="rounded-[28px] border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-3">
            <Users className="h-5 w-5 text-sky-300" />
            <div>
              <div className="text-sm font-black text-slate-950">본인 방송 전용 접근</div>
              <div className="mt-1 text-sm text-slate-600">
                인증된 스트리머 계정 기준으로만 접근이 허용됩니다.
              </div>
            </div>
          </div>
        </div>
        <div className="rounded-[28px] border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-3">
            <Target className="h-5 w-5 text-pink-300" />
            <div>
              <div className="text-sm font-black text-slate-950">맥락 중심 화면</div>
              <div className="mt-1 text-sm text-slate-600">
                무엇을 왜 보는지 바로 이해할 수 있도록 목적 중심으로 묶었습니다.
              </div>
            </div>
          </div>
        </div>
      </section>
          ) : null}
        </>
      )}
    </div>
  );
}
