"use client";

import { use, useEffect, useMemo, useRef, useState } from "react";
import {
  ResponsiveContainer,
  AreaChart,
  Area,
  CartesianGrid,
  XAxis,
  YAxis,
  Tooltip,
} from "recharts";
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
import V2InsightsPanel from "@/components/v2/V2InsightsPanel";
import type { V2Frame } from "@/components/v2/V2InsightsPanel";
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

interface OwnerProfile {
  authenticated: boolean;
  channelId?: string;
  channelName?: string;
  expiresAt?: string;
  refreshed?: boolean;
  message?: string;
}

const EMOTION_MAP: Record<string, { color: string; label: string; icon: string }> = {
  JOY: { color: "#f59e0b", label: "Joy", icon: "J" },
  HOPE: { color: "#38bdf8", label: "Hope", icon: "H" },
  NEUTRAL: { color: "#94a3b8", label: "Neutral", icon: "N" },
  SADNESS: { color: "#818cf8", label: "Sadness", icon: "S" },
  ANGER: { color: "#ef4444", label: "Anger", icon: "A" },
  WONDER: { color: "#c084fc", label: "Wonder", icon: "W" },
  DISGUST: { color: "#fb7185", label: "Disgust", icon: "D" },
};

const EMPTY_OWNER_PROFILE: OwnerProfile = {
  authenticated: false,
  message: "Sign in with CHZZK to access your stream dashboard.",
};

export default function ChannelDashboard({
  params,
}: {
  params: Promise<{ channelId: string }>;
}) {
  const { channelId } = use(params);

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
  const [history, setHistory] = useState<any[]>([]);
  const [trendData, setTrendData] = useState<{ time: string; score: number; timestamp?: string }[]>([]);
  const [isConnected, setIsConnected] = useState(false);
  const [latestVibe, setLatestVibe] = useState<{
    emotion: string;
    score: number;
    label: string;
    color: string;
  } | null>(null);
  const [isSessionActive, setIsSessionActive] = useState(false);
  const [pollResults, setPollResults] = useState<Record<string, number>>({});
  const [voters, setVoters] = useState<Record<string, string>>({});
  const [selectedVoter, setSelectedVoter] = useState<string | null>(null);
  const [voterHistory, setVoterHistory] = useState<AnalyzedChatMessage[]>([]);
  const [pollItems, setPollItems] = useState<string[]>([]);
  const [showPollCreator, setShowPollCreator] = useState(false);
  const [newPollItems, setNewPollItems] = useState<string[]>(["", ""]);
  const [keywordStats, setKeywordStats] = useState<Record<string, number>>({});
  const [v2Frame, setV2Frame] = useState<V2Frame | null>(null);
  const [activeTab, setActiveTab] = useState<"live" | "vod">("live");
  const [ownerChannelId, setOwnerChannelId] = useState("");
  const [ownerProfile, setOwnerProfile] = useState<OwnerProfile>(EMPTY_OWNER_PROFILE);
  const [authLoading, setAuthLoading] = useState(true);
  const [sessionNotice, setSessionNotice] = useState<string | null>(null);

  const eventSourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    let disposed = false;

    const fetchOwnerProfile = async (silent = false) => {
      try {
        if (!silent) {
          setAuthLoading(true);
        }
        const response = await fetch("http://localhost:8081/api/v1/chzzk/me", {
          credentials: "include",
        });
        const profile = (await response.json()) as OwnerProfile;

        if (disposed) {
          return;
        }

        if (!response.ok || !profile.authenticated) {
          setOwnerProfile(profile);
          setOwnerChannelId("");
          setSessionNotice(profile.message || "Sign in again to continue.");
          return;
        }

        setOwnerProfile(profile);
        setOwnerChannelId(profile.channelId ?? "");
        if (profile.refreshed) {
          setSessionNotice("Your CHZZK session was refreshed automatically.");
        }
      } catch {
        if (!disposed) {
          setOwnerProfile(EMPTY_OWNER_PROFILE);
          setOwnerChannelId("");
          setSessionNotice("Could not verify your CHZZK session.");
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

  const hasOwnerIdentity = !!ownerChannelId;
  const isAuthorizedChannel = ownerChannelId === channelId;

  const handleUnauthorizedSession = (message = "Your CHZZK session expired. Please sign in again.") => {
    eventSourceRef.current?.close();
    eventSourceRef.current = null;
    setOwnerProfile({
      authenticated: false,
      message,
    });
    setOwnerChannelId("");
    setIsSessionActive(false);
    setIsConnected(false);
    setSessionNotice(message);
  };

  const fetchOwned = async (url: string, init?: RequestInit) => {
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
  };

  const fetchOwnedJson = async <T,>(url: string, init?: RequestInit): Promise<T | null> => {
    const response = await fetchOwned(url, init);
    if (!response) {
      return null;
    }
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`);
    }
    return (await response.json()) as T;
  };

  useEffect(() => {
    if (!channelId || !isAuthorizedChannel) return;

    const fetchHistory = async () => {
      try {
        const data = await fetchOwnedJson<any[]>(`http://localhost:8083/api/v1/stream/${channelId}/history`);
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
                  new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime(),
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
        setTimeout(connectSSE, 5000);
      };
    };

    const fetchPollState = async () => {
      try {
        const sessionState = await fetchOwnedJson<boolean>(`http://localhost:8083/api/v1/poll/${channelId}/session`);
        if (sessionState === null) return;
        setIsSessionActive(sessionState);

        const nextPollResults = await fetchOwnedJson<Record<string, number>>(`http://localhost:8083/api/v1/poll/${channelId}/results`);
        if (nextPollResults === null) return;
        setPollResults(nextPollResults);

        const nextPollItems = await fetchOwnedJson<string[]>(`http://localhost:8083/api/v1/poll/${channelId}/items`);
        if (nextPollItems === null) return;
        setPollItems(nextPollItems);
      } catch {}
    };

    fetchHistory();
    fetchPollState();
    connectSSE();

    const pollInterval = setInterval(async () => {
      try {
        const results = await fetchOwnedJson<Record<string, number>>(`http://localhost:8083/api/v1/poll/${channelId}/results`);
        if (results === null) return;
        setPollResults(results);

        const voterData = await fetchOwnedJson<Record<string, string>>(`http://localhost:8083/api/v1/poll/${channelId}/voters`);
        if (voterData === null) return;
        setVoters(voterData);
      } catch {}
    }, 3000);

    return () => {
      eventSourceRef.current?.close();
      clearInterval(pollInterval);
    };
  }, [channelId, isAuthorizedChannel, ownerChannelId]);

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

  const totalPollVotes = Object.values(pollResults).reduce((sum, count) => sum + count, 0);

  const handleLogin = () => {
    window.location.href = "http://localhost:8081/api/v1/chzzk/login";
  };

  const handleLogout = async () => {
    await fetch("http://localhost:8081/api/v1/chzzk/logout", {
      method: "DELETE",
      credentials: "include",
    });
    setOwnerProfile(EMPTY_OWNER_PROFILE);
    setOwnerChannelId("");
    setIsSessionActive(false);
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

  const handleToggleSession = async () => {
    try {
      if (!isAuthorizedChannel) {
        alert("Only the authenticated channel owner can start analysis.");
        return;
      }

      const nextState = !isSessionActive;

      if (nextState) {
        const response = await fetch(`http://localhost:8081/api/v1/channels/${channelId}/subscribe`, {
          method: "POST",
          credentials: "include",
          headers: buildOwnerHeaders(ownerChannelId),
        });
        if (response.status === 403) {
          alert("Only your own channel can be analyzed.");
          return;
        }
        if (!response.ok) {
          if (response.status === 401) {
            handleUnauthorizedSession();
            return;
          }
          throw new Error("Collector subscription failed");
        }
      } else {
        const response = await fetch(`http://localhost:8081/api/v1/channels/${channelId}/subscribe`, {
          method: "DELETE",
          credentials: "include",
          headers: buildOwnerHeaders(ownerChannelId),
        });
        if (response.status === 401) {
          handleUnauthorizedSession();
          return;
        }
      }

      const pollSessionResponse = await fetchOwned(`http://localhost:8083/api/v1/poll/${channelId}/session?active=${nextState}`, {
        method: "POST",
      });
      if (!pollSessionResponse) return;
      setIsSessionActive(nextState);
    } catch (error) {
      console.error("Failed to toggle session:", error);
      alert("Failed to change session state. Please verify backend status.");
    }
  };

  const handleClearPoll = async () => {
    if (!confirm("Reset the current poll?")) return;
    try {
      const response = await fetchOwned(`http://localhost:8083/api/v1/poll/${channelId}`, {
        method: "DELETE",
      });
      if (!response) return;
      setPollResults({});
      setVoters({});
    } catch {}
  };

  const handleCreatePoll = async () => {
    const items = newPollItems.filter((item) => item.trim() !== "");
    if (items.length < 2) {
      alert("Please add at least two options.");
      return;
    }

    try {
      const saveItemsResponse = await fetchOwned(`http://localhost:8083/api/v1/poll/${channelId}/items`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...buildOwnerHeaders(ownerChannelId),
        },
        body: JSON.stringify(items),
      });
      if (!saveItemsResponse) return;
      setPollItems(items);
      setShowPollCreator(false);
      const resetPollResponse = await fetchOwned(`http://localhost:8083/api/v1/poll/${channelId}`, {
        method: "DELETE",
      });
      if (!resetPollResponse) return;
      setPollResults({});
      setVoters({});
    } catch {}
  };

  const openVoterHistory = async (userId: string) => {
    setSelectedVoter(userId);
    const history = await fetchOwnedJson<AnalyzedChatMessage[]>(
      `http://localhost:8083/api/v1/poll/${channelId}/voters/${userId}/history`,
    );
    if (!history) return;
    setVoterHistory(history);
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

  if (activeTab === "vod") {
    return (
      <div className="space-y-8">
        <section className="rounded-[36px] border border-slate-200 bg-white p-8 shadow-[0_24px_80px_rgba(15,23,42,0.08)]">
          <div className="flex flex-col gap-6 md:flex-row md:items-end md:justify-between">
            <div className="space-y-3">
              <div className="inline-flex items-center gap-2 rounded-full border border-indigo-500/20 bg-indigo-500/10 px-3 py-1 text-[10px] font-black uppercase tracking-[0.25em] text-indigo-300">
                <BarChart3 className="h-3.5 w-3.5" />
                VOD Review
              </div>
              <h1 className="text-4xl font-black tracking-tight text-slate-950">Post-stream archive board</h1>
              <p className="max-w-2xl text-base leading-7 text-slate-600">
                Review indexed moments, inspect standout reactions, and bring your VOD workflow
                into the same console.
              </p>
            </div>

            <button
              onClick={() => setActiveTab("live")}
              className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-5 py-3 text-sm font-black text-slate-700 transition hover:bg-slate-100"
            >
              <Radio className="h-4 w-4" />
              Back to live dashboard
            </button>
          </div>
        </section>

        <div className="min-h-[780px]">
          <VodHighlightBoard />
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <section className="rounded-[36px] border border-slate-200 bg-white p-8 shadow-[0_24px_80px_rgba(15,23,42,0.08)]">
        <div className="flex flex-col gap-8 xl:flex-row xl:items-start xl:justify-between">
          <div className="max-w-3xl space-y-4">
            <div className="inline-flex items-center gap-2 rounded-full border border-emerald-500/20 bg-emerald-500/10 px-3 py-1 text-[10px] font-black uppercase tracking-[0.25em] text-emerald-300">
              <Radio className="h-3.5 w-3.5" />
              Stream Control Center
            </div>
            <div>
              <h1 className="text-4xl font-black tracking-tight text-slate-950">Live operations dashboard</h1>
              <p className="mt-3 max-w-2xl text-base leading-7 text-slate-600">
                Track chat momentum, emotional shifts, highlight candidates, and poll activity
                from a single operator view built for stream owners.
              </p>
            </div>
          </div>

          <div className="w-full max-w-xl rounded-[28px] border border-slate-200 bg-slate-50 p-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">Owner Access</div>
                {authLoading ? (
                  <div className="mt-3 text-sm font-bold text-slate-500">Checking CHZZK session...</div>
                ) : hasOwnerIdentity ? (
                  <>
                    <div className="mt-3 text-xl font-black text-slate-950">
                      Signed in as {ownerProfile.channelName || "channel owner"}
                    </div>
                    <div className="mt-2 text-sm text-slate-600">
                      owner channel id <span className="font-mono text-slate-800">{ownerChannelId}</span>
                    </div>
                    <div className="mt-2 text-sm text-slate-600">
                      session expires{" "}
                      <span className="font-semibold text-slate-800">
                        {ownerProfile.expiresAt
                          ? new Date(ownerProfile.expiresAt).toLocaleString()
                          : "soon"}
                      </span>
                    </div>
                    {sessionNotice ? (
                      <div className="mt-3 rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-semibold text-emerald-700">
                        {sessionNotice}
                      </div>
                    ) : null}
                  </>
                ) : (
                  <>
                    <div className="mt-3 text-xl font-black text-slate-950">Sign in required</div>
                    <div className="mt-2 text-sm text-slate-600">
                      {ownerProfile.message || "Only the verified streamer can open this dashboard."}
                    </div>
                    {sessionNotice ? (
                      <div className="mt-3 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm font-semibold text-amber-700">
                        {sessionNotice}
                      </div>
                    ) : null}
                  </>
                )}
              </div>

              <div
                className={`mt-1 inline-flex h-10 items-center gap-2 rounded-full border px-4 text-xs font-black uppercase tracking-[0.2em] ${
                  isAuthorizedChannel
                    ? "border-emerald-500/20 bg-emerald-500/10 text-emerald-300"
                    : "border-amber-500/20 bg-amber-500/10 text-amber-300"
                }`}
              >
                <ShieldCheck className="h-4 w-4" />
                {isAuthorizedChannel ? "Authorized" : "Restricted"}
              </div>
            </div>

            <div className="mt-5 flex flex-wrap items-center gap-3">
              {!hasOwnerIdentity ? (
                <button
                  onClick={handleLogin}
                  className="inline-flex items-center gap-2 rounded-2xl bg-emerald-500 px-5 py-3 text-sm font-black text-slate-950 transition hover:bg-emerald-400"
                >
                  <Sparkles className="h-4 w-4" />
                  Sign in with CHZZK
                </button>
              ) : (
                <button
                  onClick={handleLogout}
                  className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-5 py-3 text-sm font-black text-slate-700 transition hover:bg-slate-100"
                >
                  <Lock className="h-4 w-4" />
                  Sign out
                </button>
              )}

              <button
                onClick={handleToggleSession}
                disabled={!isAuthorizedChannel}
                className={`inline-flex items-center gap-2 rounded-2xl px-5 py-3 text-sm font-black transition ${
                  !isAuthorizedChannel
                    ? "cursor-not-allowed bg-slate-200 text-slate-500"
                    : isSessionActive
                      ? "bg-rose-500/12 text-rose-300 hover:bg-rose-500/18"
                      : "bg-sky-500 text-slate-950 hover:bg-sky-400"
                }`}
              >
                <RefreshCw className="h-4 w-4" />
                {isSessionActive ? "Stop analysis" : "Start analysis"}
              </button>
            </div>

            <div className="mt-5 grid grid-cols-2 gap-3">
              <div className="rounded-2xl border border-slate-200 bg-white p-4">
                <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">Room</div>
                <div className="mt-2 truncate font-mono text-sm text-slate-800">{channelId}</div>
              </div>
              <div className="rounded-2xl border border-slate-200 bg-white p-4">
                <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">Session</div>
                <div className="mt-2 text-sm font-bold text-slate-950">
                  {isSessionActive ? "Collecting and analyzing" : "Standby mode"}
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="grid gap-5 md:grid-cols-2 xl:grid-cols-4">
        {renderMetric(
          "Message Volume",
          `${stats.TOTAL_COUNT || 0}`,
          "Total analyzed messages in the current live session.",
          "default",
        )}
        {renderMetric(
          "Connection",
          isConnected ? "Online" : "Retrying",
          isConnected ? "Realtime event stream is healthy." : "SSE stream is reconnecting.",
          isConnected ? "good" : "warn",
        )}
        {renderMetric(
          "Session Mode",
          isSessionActive ? "Active" : "Paused",
          isSessionActive ? "Collector and poll session are enabled." : "Analysis is currently idle.",
          isSessionActive ? "good" : "warn",
        )}
        {renderMetric(
          "Current Mood",
          latestVibe ? latestVibe.label : "Waiting",
          latestVibe ? `Latest dominant emotion at ${(latestVibe.score * 100).toFixed(0)}%.` : "No analyzed chat yet.",
          latestVibe ? "good" : "default",
        )}
      </section>

      <section className="flex flex-wrap items-center justify-between gap-4">
        <div className="inline-flex rounded-2xl border border-slate-200 bg-white p-1">
          <button
            onClick={() => setActiveTab("live")}
            className={`rounded-xl px-4 py-2 text-sm font-black transition ${
              activeTab === "live" ? "bg-slate-950 text-white" : "text-slate-500 hover:text-slate-950"
            }`}
          >
            Live dashboard
          </button>
          <button
            onClick={() => setActiveTab("vod")}
            className={`rounded-xl px-4 py-2 text-sm font-black transition ${
              activeTab === "vod" ? "bg-slate-950 text-white" : "text-slate-500 hover:text-slate-950"
            }`}
          >
            VOD archive
          </button>
        </div>

        <div className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-xs font-black uppercase tracking-[0.18em] text-slate-500">
          <Clock3 className="h-3.5 w-3.5" />
          Owner-only analytics workspace
        </div>
      </section>

      {isAuthorizedChannel ? (
        <V2InsightsPanel roomId={channelId} ownerId={ownerChannelId} onFrame={setV2Frame} />
      ) : (
        <section className="rounded-[30px] border border-amber-500/20 bg-amber-500/8 p-6">
          <div className="flex items-start gap-3">
            <AlertCircle className="mt-0.5 h-5 w-5 text-amber-300" />
            <div>
              <h2 className="text-lg font-black text-slate-950">Access limited to the verified owner</h2>
              <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600">
                Sign in with the same CHZZK account that owns this channel to unlock realtime
                guardrails, poll controls, and detailed analytics.
              </p>
            </div>
          </div>
        </section>
      )}

      <section className="grid gap-6 xl:grid-cols-[1.35fr_0.85fr]">
        <div className="space-y-6">
          <div className="grid gap-6 lg:grid-cols-[0.82fr_1.18fr]">
            <div className="min-w-0 rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
              <div className="mb-5 flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">Current Mood</div>
                  <div className="mt-2 text-xl font-black text-slate-950">Operator pulse</div>
                </div>
                <div className="rounded-full border border-slate-200 bg-slate-100 px-3 py-1 text-[10px] font-black uppercase tracking-[0.18em] text-slate-500">
                  {v2Frame ? `Balance ${(v2Frame.balance * 100).toFixed(0)}%` : "Live"}
                </div>
              </div>
              <MoodGauge
                emotion={latestVibe?.emotion || "NEUTRAL"}
                score={latestVibe?.score || 0}
                label={latestVibe?.label || "Neutral"}
                color={latestVibe?.color || "#94a3b8"}
              />
              <div className="mt-6 rounded-2xl border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
                {v2Frame?.mentalBuffer ? (
                  <>
                    Buffered negative <span className="font-black text-slate-950">{(v2Frame.mentalBuffer.emaNegative * 100).toFixed(0)}%</span>
                    {" · "}
                    buffered positive <span className="font-black text-slate-950">{(v2Frame.mentalBuffer.emaPositive * 100).toFixed(0)}%</span>
                  </>
                ) : (
                  <>The dashboard will start smoothing crowd mood once the v2 stream arrives.</>
                )}
              </div>
            </div>

            <div className="min-w-0 rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
              <div className="mb-5 flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">Trend</div>
                  <div className="mt-2 text-xl font-black text-slate-950">Emotional momentum</div>
                </div>
                <Waves className="h-5 w-5 text-sky-300" />
              </div>

              <div className="h-[260px]">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={trendData}>
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
                    <Area
                      type="monotone"
                      dataKey="score"
                      stroke="#38bdf8"
                      strokeWidth={3}
                      fill="url(#trendFill)"
                    />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>

          <div className="min-w-0 rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">Signal Surface</div>
                <div className="mt-2 text-xl font-black text-slate-950">Heatmap and keyword clusters</div>
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
                    <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">Keywords</div>
                    <div className="mt-1 text-sm text-slate-600">
                      {v2Frame?.topicLabel || "Realtime keyword landscape"}
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

          <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">Poll</div>
                <div className="mt-2 text-xl font-black text-slate-950">Interactive control</div>
              </div>
              <Users className="h-5 w-5 text-emerald-300" />
            </div>

            <div className="mb-4 flex flex-wrap gap-3">
              <button
                onClick={() => setShowPollCreator((prev) => !prev)}
                className="rounded-2xl bg-slate-950 px-4 py-2 text-sm font-black text-white transition hover:bg-slate-800"
              >
                {showPollCreator ? "Close editor" : "Edit options"}
              </button>
              <button
                onClick={handleClearPoll}
                className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700 transition hover:bg-slate-100"
              >
                Reset votes
              </button>
            </div>

            {showPollCreator ? (
              <div className="mb-5 space-y-3 rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                {newPollItems.map((item, index) => (
                  <input
                    key={`${index}-${item}`}
                    value={item}
                    onChange={(event) =>
                      setNewPollItems((prev) =>
                        prev.map((current, currentIndex) =>
                          currentIndex === index ? event.target.value : current,
                        ),
                      )
                    }
                    className="w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-950 outline-none transition focus:border-sky-400/40"
                    placeholder={`Option ${index + 1}`}
                  />
                ))}
                <div className="flex flex-wrap gap-3">
                  <button
                    onClick={() => setNewPollItems((prev) => [...prev, ""])}
                    className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700"
                  >
                    Add option
                  </button>
                  <button
                    onClick={handleCreatePoll}
                    className="rounded-2xl bg-sky-500 px-4 py-2 text-sm font-black text-slate-950"
                  >
                    Save poll
                  </button>
                </div>
              </div>
            ) : null}

            <div className="space-y-3">
              {pollItems.length === 0 ? (
                <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-600">
                  Create a poll to track viewer intent during live operations.
                </div>
              ) : (
                pollItems.map((item) => {
                  const votes = pollResults[item] ?? 0;
                  const ratio = totalPollVotes ? (votes / totalPollVotes) * 100 : 0;
                  const votersForItem = Object.entries(voters)
                    .filter(([, selected]) => selected === item)
                    .map(([userId]) => userId)
                    .slice(0, 5);

                  return (
                    <div key={item} className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                      <div className="flex items-center justify-between gap-3">
                        <div className="font-bold text-slate-950">{item}</div>
                        <div className="text-sm font-mono text-slate-500">
                          {votes} votes · {ratio.toFixed(0)}%
                        </div>
                      </div>
                      <div className="mt-3 h-2 rounded-full bg-white/6">
                        <div className="h-2 rounded-full bg-emerald-400" style={{ width: `${ratio}%` }} />
                      </div>
                      {votersForItem.length > 0 ? (
                        <div className="mt-3 flex flex-wrap gap-2">
                          {votersForItem.map((userId) => (
                            <button
                              key={userId}
                              onClick={() => openVoterHistory(userId)}
                              className="rounded-full border border-slate-200 bg-white px-3 py-1 text-xs font-bold text-slate-700 transition hover:bg-slate-100"
                            >
                              {userId}
                            </button>
                          ))}
                        </div>
                      ) : null}
                    </div>
                  );
                })
              )}
            </div>
          </div>

          <div className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">Highlights</div>
                <div className="mt-2 text-xl font-black text-slate-950">Moment vault</div>
              </div>
              <Sparkles className="h-5 w-5 text-pink-300" />
            </div>

            <div className="space-y-3">
              {highlights.length === 0 ? (
                <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-600">
                  Highlight candidates will appear here once notable moments are detected.
                </div>
              ) : (
                highlights.map((highlight) => (
                  <div key={highlight.id} className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                    <div className="flex items-start justify-between gap-4">
                      <div className="space-y-2">
                        <div className="text-sm font-black text-slate-950">{highlight.emotionType}</div>
                        <div className="text-sm leading-6 text-slate-700">{highlight.topMessage}</div>
                        <div className="text-xs text-slate-500">
                          score {highlight.peakScore.toFixed(2)} · {new Date(highlight.timestamp).toLocaleString()}
                        </div>
                      </div>

                      <button
                        onClick={() => handleDownload(highlight.liveImageUrl, highlight.timestamp)}
                        className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-3 py-2 text-xs font-black text-slate-700 transition hover:bg-slate-100"
                      >
                        <Download className="h-4 w-4" />
                        Save
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </section>

      {selectedVoter ? (
        <section className="rounded-[30px] border border-slate-200 bg-white p-6 shadow-sm">
          <div className="mb-5 flex items-center justify-between">
            <div>
              <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">Voter Inspector</div>
              <div className="mt-2 text-xl font-black text-slate-950">{selectedVoter}</div>
            </div>
            <button
              onClick={() => {
                setSelectedVoter(null);
                setVoterHistory([]);
              }}
              className="rounded-2xl border border-slate-200 bg-white px-4 py-2 text-sm font-black text-slate-700"
            >
              Close
            </button>
          </div>

          <div className="space-y-3">
            {voterHistory.length === 0 ? (
              <div className="rounded-[24px] border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-600">
                No recent analyzed history for this viewer yet.
              </div>
            ) : (
              voterHistory.map((message) => {
                const strongestEmotion = Object.entries(message.emotionScores || { NEUTRAL: 1 }).reduce((a, b) =>
                  a[1] > b[1] ? a : b,
                );
                const config = EMOTION_MAP[strongestEmotion[0]] || EMOTION_MAP.NEUTRAL;

                return (
                  <div key={message.messageId} className="rounded-[24px] border border-slate-200 bg-slate-50 p-4">
                    <div className="flex items-center gap-2 text-xs font-black uppercase tracking-[0.18em] text-slate-500">
                      <span
                        className="inline-flex h-7 w-7 items-center justify-center rounded-full"
                        style={{ backgroundColor: `${config.color}22`, color: config.color }}
                      >
                        {config.icon}
                      </span>
                      {config.label}
                    </div>
                    <p className="mt-3 text-sm leading-6 text-slate-700">{message.content || "(empty message)"}</p>
                    <div className="mt-3 text-xs text-slate-500">
                      {message.analyzedAt ? new Date(message.analyzedAt).toLocaleString() : "Pending timestamp"}
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </section>
      ) : null}

      <section className="grid gap-5 lg:grid-cols-3">
        <div className="rounded-[28px] border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-3">
            <CheckCircle2 className="h-5 w-5 text-emerald-300" />
            <div>
              <div className="text-sm font-black text-slate-950">Stable operator view</div>
              <div className="mt-1 text-sm text-slate-600">
                Metrics, v2 signals, polls, and highlights now live in one dashboard frame.
              </div>
            </div>
          </div>
        </div>
        <div className="rounded-[28px] border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-3">
            <Users className="h-5 w-5 text-sky-300" />
            <div>
              <div className="text-sm font-black text-slate-950">Owner-only workflow</div>
              <div className="mt-1 text-sm text-slate-600">
                Access control remains centered on the authenticated streamer account.
              </div>
            </div>
          </div>
        </div>
        <div className="rounded-[28px] border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-3">
            <Target className="h-5 w-5 text-pink-300" />
            <div>
              <div className="text-sm font-black text-slate-950">Feedback-friendly layout</div>
              <div className="mt-1 text-sm text-slate-600">
                The screen is grouped by operations, signals, and actions so we can tune each block.
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
