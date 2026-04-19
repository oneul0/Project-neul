"use client";

import { useCallback, useEffect, useState } from "react";
import { Area, AreaChart, CartesianGrid, Tooltip, XAxis, YAxis } from "recharts";

export interface OwnerProfile {
  authenticated: boolean;
  authUnavailable?: boolean;
  channelId?: string;
  channelName?: string;
  expiresAt?: string;
  refreshed?: boolean;
  message?: string;
}

export interface BroadcastStatus {
  live: boolean;
  status: "live" | "offline" | "failed";
  message?: string;
  liveTitle?: string;
  viewerCount?: number;
}

export interface InlineNotice {
  tone: "good" | "warn";
  message: string;
}

export interface TrendPoint {
  time: string;
  score: number;
  timestamp?: string;
}

interface AccessState {
  title: string;
  description: string;
  cause: string;
  nextStep: string;
  badgeLabel: string;
  badgeClass: string;
  panelClass: string;
  cardClass: string;
}

interface StatusCardState {
  label: string;
  summary: string;
  cause: string;
  nextStep: string;
  cardClass: string;
}

export const EMPTY_OWNER_PROFILE: OwnerProfile = {
  authenticated: false,
  message: "치지직 로그인 후 대시보드를 사용할 수 있습니다.",
};

export function useOwnerDashboardSession(onUnauthorized: () => void) {
  const [ownerChannelId, setOwnerChannelId] = useState("");
  const [ownerProfile, setOwnerProfile] = useState<OwnerProfile>(EMPTY_OWNER_PROFILE);
  const [authLoading, setAuthLoading] = useState(true);
  const [sessionNotice, setSessionNotice] = useState<InlineNotice | null>(null);

  const handleUnauthorizedSession = useCallback(
    (message = "로그인 세션이 만료되었습니다. 다시 로그인해 주세요.") => {
      onUnauthorized();
      setOwnerProfile({
        authenticated: false,
        message,
      });
      setOwnerChannelId("");
      setSessionNotice({ tone: "warn", message });
    },
    [onUnauthorized],
  );

  const resetOwnerSession = useCallback(() => {
    setOwnerProfile(EMPTY_OWNER_PROFILE);
    setOwnerChannelId("");
  }, []);

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

        if (profile.authUnavailable) {
          setOwnerProfile((prev) => ({
            ...prev,
            message: profile.message || "로그인 상태를 일시적으로 확인하지 못했습니다.",
            authUnavailable: true,
          }));
          setSessionNotice({
            tone: "warn",
            message: profile.message || "로그인 상태를 일시적으로 확인하지 못했습니다.",
          });
          return;
        }

        if (!response.ok || !profile.authenticated) {
          onUnauthorized();
          setOwnerProfile(profile);
          setOwnerChannelId("");
          setSessionNotice({
            tone: "warn",
            message: profile.message || "다시 로그인해 주세요.",
          });
          return;
        }

        setOwnerProfile({
          ...profile,
          authUnavailable: false,
        });
        setOwnerChannelId(profile.channelId ?? "");
        if (profile.refreshed) {
          setSessionNotice({
            tone: "good",
            message: "로그인 세션이 자동으로 연장되었습니다.",
          });
        }
      } catch {
        if (!disposed) {
          setOwnerProfile((prev) => ({
            ...prev,
            message: "치지직 로그인 상태를 일시적으로 확인하지 못했습니다.",
            authUnavailable: true,
          }));
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

    void fetchOwnerProfile();

    const intervalId = window.setInterval(() => {
      void fetchOwnerProfile(true);
    }, 60_000);

    return () => {
      disposed = true;
      window.clearInterval(intervalId);
    };
  }, [onUnauthorized]);

  useEffect(() => {
    if (!sessionNotice) {
      return;
    }

    const timeoutId = window.setTimeout(() => setSessionNotice(null), 5000);
    return () => window.clearTimeout(timeoutId);
  }, [sessionNotice]);

  return {
    ownerChannelId,
    ownerProfile,
    authLoading,
    sessionNotice,
    setSessionNotice,
    handleUnauthorizedSession,
    resetOwnerSession,
  };
}

export function getBroadcastStatusFallback(): BroadcastStatus {
  return {
    live: false,
    status: "failed",
    message: "방송 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
  };
}

export async function requestBroadcastStatus(channelId: string): Promise<BroadcastStatus> {
  try {
    const response = await fetch(`/api/channels/${channelId}/status`, {
      cache: "no-store",
      credentials: "include",
    });
    return (await response.json()) as BroadcastStatus;
  } catch {
    return getBroadcastStatusFallback();
  }
}

export async function readCollectorErrorMessage(response: Response, fallback: string) {
  try {
    const data = (await response.json()) as {
      error?: string;
      message?: string;
      status?: string;
    };
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
}

export function buildHeroNotice(params: {
  sessionNotice: InlineNotice | null;
  statusLoading: boolean;
  broadcastStatus: BroadcastStatus | null;
}) {
  const { sessionNotice, statusLoading, broadcastStatus } = params;

  return (
    sessionNotice ??
    (!statusLoading && broadcastStatus?.status === "failed" && broadcastStatus.message
      ? { tone: "warn", message: broadcastStatus.message }
      : null)
  );
}

export function buildAccessState(params: {
  authLoading: boolean;
  hasOwnerIdentity: boolean;
  ownerProfile: OwnerProfile;
  isAuthorizedChannel: boolean;
  channelId: string;
}): AccessState {
  const {
    authLoading,
    hasOwnerIdentity,
    ownerProfile,
    isAuthorizedChannel,
    channelId,
  } = params;

  if (authLoading) {
    return {
      title: "로그인 상태를 확인하는 중입니다",
      description: "치지직 로그인과 소유자 여부를 확인하고 있습니다.",
      cause: "대시보드를 열면서 현재 브라우저 세션과 소유자 채널 정보를 조회했습니다.",
      nextStep: "확인이 끝나면 로그인 필요, 소유자 확인 완료, 또는 제한 상태로 바뀝니다.",
      badgeLabel: "확인 중",
      badgeClass: "border-sky-200 bg-sky-50 text-sky-700",
      panelClass: "border-sky-200 bg-sky-50",
      cardClass: "border-sky-200 bg-sky-50",
    };
  }

  if (!hasOwnerIdentity) {
    return {
      title: "로그인이 필요합니다",
      description: ownerProfile.message || "치지직 소유자 로그인이 필요합니다.",
      cause: "현재 브라우저에 이 채널을 소유한 계정 세션이 없습니다.",
      nextStep: "치지직 로그인 후 다시 들어오면 내 채널 대시보드와 실시간 가드레일을 사용할 수 있습니다.",
      badgeLabel: "로그아웃",
      badgeClass: "border-amber-200 bg-amber-50 text-amber-700",
      panelClass: "border-amber-200 bg-amber-50",
      cardClass: "border-amber-200 bg-amber-50",
    };
  }

  if (!isAuthorizedChannel) {
    return {
      title: `${ownerProfile.channelName || "내 채널"} 계정으로 로그인됨`,
      description: "현재 보고 있는 채널은 이 계정의 소유 채널이 아닙니다.",
      cause: `현재 세션은 ${ownerProfile.channelId || "내 채널"} 기준이고, 보고 있는 채널은 ${channelId}입니다.`,
      nextStep: "본인 채널로 이동하거나 올바른 계정으로 다시 로그인해야 분석 시작 버튼이 활성화됩니다.",
      badgeLabel: "제한됨",
      badgeClass: "border-amber-200 bg-amber-50 text-amber-700",
      panelClass: "border-amber-200 bg-amber-50",
      cardClass: "border-amber-200 bg-amber-50",
    };
  }

  return {
    title: `${ownerProfile.channelName || "내 채널"} 소유자 세션이 확인되었습니다`,
    description: ownerProfile.expiresAt
      ? `세션 만료 예정 ${new Date(ownerProfile.expiresAt).toLocaleString()}`
      : "이 채널에서 실시간 분석 제어를 사용할 수 있습니다.",
    cause: "로그인한 계정의 소유 채널과 현재 페이지 채널이 일치합니다.",
    nextStep: "라이브 상태를 확인한 뒤 분석 시작 또는 중지로 실시간 추적을 제어할 수 있습니다.",
    badgeLabel: "소유자 확인",
    badgeClass: "border-emerald-200 bg-emerald-50 text-emerald-700",
    panelClass: "border-emerald-200 bg-emerald-50",
    cardClass: "border-emerald-200 bg-emerald-50",
  };
}

export function buildLiveState(params: {
  statusLoading: boolean;
  broadcastStatus: BroadcastStatus | null;
  isSessionActive: boolean;
}): StatusCardState {
  const { statusLoading, broadcastStatus, isSessionActive } = params;

  const liveStatusDescription = statusLoading
    ? "현재 방송 상태를 확인하고 있습니다."
    : broadcastStatus?.liveTitle
      ? `${broadcastStatus.liveTitle}${typeof broadcastStatus.viewerCount === "number" ? ` · 시청자 ${broadcastStatus.viewerCount.toLocaleString()}명` : ""}`
      : broadcastStatus?.message || "방송 제목과 상태가 여기에 표시됩니다.";

  if (statusLoading) {
    return {
      label: "상태 확인 중",
      summary: "방송 상태를 확인하고 있습니다.",
      cause: "소유자 채널의 라이브 상태를 API로 조회 중입니다.",
      nextStep: "확인이 끝나면 방송 중, 오프라인, 또는 확인 필요 상태가 표시됩니다.",
      cardClass: "border-sky-200 bg-sky-50",
    };
  }

  if (broadcastStatus?.status === "live") {
    return {
      label: "방송 중",
      summary: liveStatusDescription,
      cause: "현재 소유자 채널이 라이브 상태로 확인되었습니다.",
      nextStep: isSessionActive
        ? "지금은 분석을 유지하면서 민심 흐름을 확인하면 됩니다."
        : "지금 분석 시작을 눌러 실시간 수집과 요약을 켤 수 있습니다.",
      cardClass: "border-emerald-200 bg-emerald-50",
    };
  }

  if (broadcastStatus?.status === "failed") {
    return {
      label: "상태 확인 필요",
      summary: liveStatusDescription,
      cause: "라이브 상태를 안정적으로 확인하지 못했습니다.",
      nextStep: "잠시 후 다시 확인하거나 방송 페이지에서 실제 라이브 상태를 먼저 확인해 주세요.",
      cardClass: "border-amber-200 bg-amber-50",
    };
  }

  return {
    label: "오프라인",
    summary: broadcastStatus?.message || "현재 방송이 꺼져 있습니다.",
    cause: "현재 이 채널이 라이브 방송 중이 아니어서 채팅 수집을 시작할 수 없습니다.",
    nextStep: "방송이 켜지면 분석 시작으로 바로 전환할 수 있습니다.",
    cardClass: "border-amber-200 bg-amber-50",
  };
}

export function buildSessionState(params: {
  hasOwnerIdentity: boolean;
  isAuthorizedChannel: boolean;
  isSessionActive: boolean;
  broadcastStatus: BroadcastStatus | null;
}): StatusCardState {
  const { hasOwnerIdentity, isAuthorizedChannel, isSessionActive, broadcastStatus } = params;

  if (!hasOwnerIdentity) {
    return {
      label: "잠김",
      summary: "로그인 전이라 시작할 수 없습니다.",
      cause: "소유자 인증이 아직 되지 않았습니다.",
      nextStep: "먼저 치지직 로그인으로 소유자 세션을 연결해 주세요.",
      cardClass: "border-amber-200 bg-amber-50",
    };
  }

  if (!isAuthorizedChannel) {
    return {
      label: "권한 제한",
      summary: "다른 채널을 보고 있어 시작이 막혀 있습니다.",
      cause: "소유자 채널과 현재 URL의 채널이 다릅니다.",
      nextStep: "본인 채널로 이동하거나 올바른 소유자 계정으로 다시 로그인해 주세요.",
      cardClass: "border-amber-200 bg-amber-50",
    };
  }

  if (isSessionActive) {
    return {
      label: "진행 중",
      summary: "채팅 수집과 분석이 진행 중입니다.",
      cause: "현재 방송에 대해 구독 세션이 활성화되어 있습니다.",
      nextStep: "필요할 때까지 유지하고, 멈추려면 분석 중지를 누르면 됩니다.",
      cardClass: "border-emerald-200 bg-emerald-50",
    };
  }

  if (broadcastStatus?.status === "live") {
    return {
      label: "시작 가능",
      summary: "방송은 켜져 있고 세션만 아직 꺼져 있습니다.",
      cause: "소유자 권한은 확인됐지만 실시간 구독 세션은 아직 비활성화 상태입니다.",
      nextStep: "분석 시작을 누르면 채팅 수집과 대시보드 반영이 바로 시작됩니다.",
      cardClass: "border-sky-200 bg-sky-50",
    };
  }

  return {
    label: "방송 대기",
    summary: "세션은 꺼져 있고 라이브 시작을 기다립니다.",
    cause: "현재 방송이 오프라인이거나 상태 확인이 끝나지 않았습니다.",
    nextStep: "방송이 시작되면 분석 시작으로 바로 전환할 수 있습니다.",
    cardClass: "border-slate-200 bg-slate-50",
  };
}

export function buildConnectionState(params: {
  hasOwnerIdentity: boolean;
  isAuthorizedChannel: boolean;
  isSessionActive: boolean;
  isConnected: boolean;
}): StatusCardState {
  const { hasOwnerIdentity, isAuthorizedChannel, isSessionActive, isConnected } = params;

  if (!hasOwnerIdentity) {
    return {
      label: "권한 없음",
      summary: "로그인 전이라 실시간 연결을 열지 않았습니다.",
      cause: "소유자 세션 없이 라이브 데이터 스트림을 요청할 수 없습니다.",
      nextStep: "로그인 후 본인 채널에서 들어오면 연결 상태가 실시간으로 바뀝니다.",
      cardClass: "border-amber-200 bg-amber-50",
    };
  }

  if (!isAuthorizedChannel) {
    return {
      label: "연결 제한",
      summary: "소유자 채널이 아니어서 실시간 연결을 열지 않았습니다.",
      cause: "보안상 본인 채널에서만 실시간 분석 스트림을 연결합니다.",
      nextStep: "본인 채널로 이동하면 연결 상태가 정상 또는 재연결 상태로 바뀝니다.",
      cardClass: "border-amber-200 bg-amber-50",
    };
  }

  if (!isSessionActive) {
    return {
      label: "세션 시작 전",
      summary: "실시간 분석 스트림이 아직 열리지 않았습니다.",
      cause: "분석 세션이 비활성화된 상태입니다.",
      nextStep: "분석 시작을 누르면 연결이 열리고 이후 프레임이 즉시 반영됩니다.",
      cardClass: "border-slate-200 bg-slate-50",
    };
  }

  if (isConnected) {
    return {
      label: "정상 연결",
      summary: "새 분석 결과를 바로 반영하고 있습니다.",
      cause: "실시간 SSE 연결이 열려 있습니다.",
      nextStep: "연결이 유지되는 동안 최신 민심 흐름과 대표 반응을 그대로 읽으면 됩니다.",
      cardClass: "border-emerald-200 bg-emerald-50",
    };
  }

  return {
    label: "재연결 중",
    summary: "실시간 스트림을 다시 붙이고 있습니다.",
    cause: "네트워크 또는 SSE 스트림 오류가 발생해 자동 재시도를 시작했습니다.",
    nextStep: "잠시 기다리면 최신 상태로 다시 맞춰집니다.",
    cardClass: "border-rose-200 bg-rose-50",
  };
}

export function buildPrimaryActionState(params: {
  hasOwnerIdentity: boolean;
  isAuthorizedChannel: boolean;
  statusLoading: boolean;
  isSessionActive: boolean;
  broadcastStatus: BroadcastStatus | null;
}) {
  const {
    hasOwnerIdentity,
    isAuthorizedChannel,
    statusLoading,
    isSessionActive,
    broadcastStatus,
  } = params;

  return {
    disabled: hasOwnerIdentity ? !isAuthorizedChannel || statusLoading : false,
    label: isSessionActive
      ? "분석 중지"
      : !isAuthorizedChannel
        ? "내 채널에서만 시작 가능"
        : statusLoading
          ? "방송 상태 확인 중"
          : broadcastStatus?.status === "live"
            ? "분석 시작"
            : "방송 시작 후 분석",
  };
}

export function DashboardMetricCard({
  label,
  value,
  description,
  tone = "default",
}: {
  label: string;
  value: string;
  description: string;
  tone?: "default" | "good" | "warn";
}) {
  const toneClass =
    tone === "good"
      ? "border-emerald-200 bg-emerald-50"
      : tone === "warn"
        ? "border-amber-200 bg-amber-50"
        : "border-slate-200 bg-white";

  return (
    <div className={`rounded-[28px] border p-5 ${toneClass}`}>
      <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">
        {label}
      </div>
      <div className="mt-4 text-3xl font-black text-slate-950">{value}</div>
      <div className="mt-2 text-sm text-slate-600">{description}</div>
    </div>
  );
}

export function TrendAreaChart({
  width,
  height,
  data,
}: {
  width: number;
  height: number;
  data: TrendPoint[];
}) {
  if (width <= 0 || height <= 0) {
    return (
      <div className="flex h-full min-h-[260px] items-center justify-center rounded-[24px] border border-dashed border-slate-300 bg-slate-50 text-sm font-semibold text-slate-500">
        감정 추이 차트를 준비하는 중입니다.
      </div>
    );
  }

  return (
    <AreaChart width={width} height={height} data={data}>
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
}
