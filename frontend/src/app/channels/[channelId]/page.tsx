"use client";

import { use, useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { Film, Gift, Lock, LogIn, Users, Vote } from "lucide-react";
import VodHighlightBoard from "@/components/VodHighlightBoard";
import PollCard from "@/components/poll/PollCard";
import RouletteCard from "@/components/RouletteCard";
import { usePollSession } from "@/hooks/usePollSession";
import { useDonationRoulette } from "@/hooks/useDonationRoulette";
import { useOwnerDashboardSession } from "./dashboard-helpers";
import dynamic from "next/dynamic";

const DevSeedPanel =
  process.env.NODE_ENV === "development"
    ? dynamic(() => import("@/components/dev/DevSeedPanel"), { ssr: false })
    : null;

export default function ChannelDashboard({
  params,
}: {
  params: Promise<{ channelId: string }>;
}) {
  const { channelId } = use(params);
  const router = useRouter();

  const [activeTab, setActiveTab] = useState<"poll" | "vod">("poll");
  const [isTogglingPoll, setIsTogglingPoll] = useState(false);
  const [isRouletteActive, setIsRouletteActive] = useState(false);
  const [isTogglingRoulette, setIsTogglingRoulette] = useState(false);

  const { ownerChannelId, ownerProfile, authLoading, sessionNotice, setSessionNotice, handleUnauthorizedSession, resetOwnerSession } =
    useOwnerDashboardSession(useCallback(() => {}, []));

  const hasOwnerIdentity = !!ownerChannelId;
  const isAuthorizedChannel = ownerChannelId === channelId;

  const fetchOwned = useCallback(
    async (url: string, init?: RequestInit) => {
      const response = await fetch(url, {
        credentials: "include",
        ...init,
        headers: { ...(init?.headers ?? {}) },
      });
      if (response.status === 401) {
        handleUnauthorizedSession();
        return null;
      }
      return response;
    },
    [handleUnauthorizedSession],
  );

  const pollSession = usePollSession({
    roomId: channelId,
    ownerId: ownerChannelId,
    isAuthorizedChannel,
    fetchOwned,
    preferredMode: "AUTO",
  });

  const roulette = useDonationRoulette({
    roomId: channelId,
    isOwner: isAuthorizedChannel,
    fetchOwned,
  });

  const handleLogin = () => {
    window.location.href = "/api/chzzk/login";
  };

  const handleLogout = async () => {
    await fetch("/api/chzzk/logout", { method: "DELETE" });
    resetOwnerSession();
    router.replace("/");
  };

  const callSubscribe = async (active: boolean) => {
    return fetch(`/api/channels/${channelId}/subscribe`, {
      method: active ? "POST" : "DELETE",
      credentials: "include",
    });
  };

  const handleTogglePoll = async () => {
    if (!isAuthorizedChannel) {
      setSessionNotice({ tone: "warn", message: "본인 채널에서만 사용할 수 있습니다." });
      return;
    }

    setIsTogglingPoll(true);
    try {
      const nextActive = !pollSession.isSessionActive;

      if (nextActive) {
        const res = await callSubscribe(true);
        if (res.status === 401) { handleUnauthorizedSession(); return; }
        if (res.status === 403) {
          setSessionNotice({ tone: "warn", message: "본인 채널만 사용할 수 있습니다." });
          return;
        }
        if (res.status === 422) {
          const body = await res.json().catch(() => ({})) as { error?: string; message?: string };
          setSessionNotice({
            tone: "warn",
            message: body.error === "adult_stream_login_required"
              ? "성인 방송입니다. 치지직 공식 로그인 후 다시 시도해 주세요."
              : body.message ?? "성인 방송은 지원되지 않습니다.",
          });
          return;
        }
        if (!res.ok) throw new Error("투표를 시작하지 못했습니다.");
      } else if (!isRouletteActive) {
        await callSubscribe(false);
      }

      await pollSession.setSessionActive(nextActive);
      setSessionNotice({ tone: "good", message: nextActive ? "투표를 시작했습니다." : "투표를 중지했습니다." });
    } catch (e) {
      setSessionNotice({ tone: "warn", message: e instanceof Error ? e.message : "오류가 발생했습니다." });
    } finally {
      setIsTogglingPoll(false);
    }
  };

  const handleToggleRoulette = async () => {
    if (!isAuthorizedChannel) {
      setSessionNotice({ tone: "warn", message: "본인 채널에서만 사용할 수 있습니다." });
      return;
    }

    setIsTogglingRoulette(true);
    try {
      const nextActive = !isRouletteActive;

      if (nextActive && !pollSession.isSessionActive) {
        const res = await callSubscribe(true);
        if (res.status === 401) { handleUnauthorizedSession(); return; }
        if (res.status === 403) {
          setSessionNotice({ tone: "warn", message: "본인 채널만 사용할 수 있습니다." });
          return;
        }
        if (res.status === 422) {
          const body = await res.json().catch(() => ({})) as { error?: string; message?: string };
          setSessionNotice({
            tone: "warn",
            message: body.error === "adult_stream_login_required"
              ? "성인 방송입니다. 치지직 공식 로그인 후 다시 시도해 주세요."
              : body.message ?? "성인 방송은 지원되지 않습니다.",
          });
          return;
        }
        if (!res.ok) throw new Error("룰렛을 시작하지 못했습니다.");
      } else if (!nextActive && !pollSession.isSessionActive) {
        await callSubscribe(false);
      }

      setIsRouletteActive(nextActive);
      setSessionNotice({ tone: "good", message: nextActive ? "룰렛을 시작했습니다." : "룰렛을 중지했습니다." });
    } catch (e) {
      setSessionNotice({ tone: "warn", message: e instanceof Error ? e.message : "오류가 발생했습니다." });
    } finally {
      setIsTogglingRoulette(false);
    }
  };

  return (
    <div className="space-y-8">
      <section className="rounded-[28px] border border-white/[0.08] bg-[#111111] p-6 sm:p-8">
        <div className="flex flex-col gap-6 xl:flex-row xl:items-start xl:justify-between">
          <div className="space-y-2">
            <div className="text-[11px] font-black uppercase tracking-[0.28em] text-white/40">
              {activeTab === "poll" ? "투표 관리" : "VOD 하이라이트"}
            </div>
            <h1 className="text-2xl font-black tracking-tight text-white sm:text-3xl">
              {activeTab === "poll"
                ? "투표"
                : "VOD 하이라이트 워크스페이스"}
            </h1>
            <p className="text-sm leading-6 text-white/60">
              {activeTab === "poll"
                ? "항목을 만들고 실시간 집계 결과와 참여 시청자 기록을 관리합니다."
                : "VOD를 조회한 뒤 하이라이트 후보를 검토하고 편집점을 저장합니다."}
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <div className="grid grid-cols-2 rounded-[20px] border border-white/[0.08] bg-[#1A1A1A] p-1">
              <button
                onClick={() => setActiveTab("poll")}
                className={`inline-flex items-center justify-center gap-2 rounded-[16px] px-5 py-2.5 text-sm font-black transition ${
                  activeTab === "poll"
                    ? "bg-[#00FFA3] text-[#000000] shadow-[0_0_12px_rgba(0,255,163,0.25)]"
                    : "text-white/50 hover:text-white"
                }`}
              >
                <Users className="h-4 w-4" />
                투표
              </button>
              <button
                onClick={() => setActiveTab("vod")}
                className={`inline-flex items-center justify-center gap-2 rounded-[16px] px-5 py-2.5 text-sm font-black transition ${
                  activeTab === "vod"
                    ? "bg-[#00FFA3] text-[#000000] shadow-[0_0_12px_rgba(0,255,163,0.25)]"
                    : "text-white/50 hover:text-white"
                }`}
              >
                <Film className="h-4 w-4" />
                VOD
              </button>
            </div>

            {!hasOwnerIdentity ? (
              <button
                onClick={handleLogin}
                className="inline-flex items-center gap-2 rounded-2xl bg-[#00FFA3] px-5 py-3 text-sm font-black text-[#000000] transition hover:bg-[#00FFA3]/90 active:scale-95"
              >
                <LogIn className="h-4 w-4" />
                치지직 로그인
              </button>
            ) : (
              <>
                {activeTab === "poll" && isAuthorizedChannel && (
                  <>
                    <button
                      onClick={() => void handleTogglePoll()}
                      disabled={isTogglingPoll}
                      className={`inline-flex items-center gap-2 rounded-2xl px-5 py-3 text-sm font-black transition ${
                        isTogglingPoll
                          ? "cursor-not-allowed bg-white/10 text-white/40"
                          : pollSession.isSessionActive
                            ? "bg-rose-500/90 text-white hover:bg-rose-500"
                            : "bg-[#00FFA3] text-[#000000] hover:bg-[#00FFA3]/90"
                      }`}
                    >
                      <Vote className="h-4 w-4" />
                      {isTogglingPoll ? "처리 중..." : pollSession.isSessionActive ? "투표 중지" : "투표 시작"}
                    </button>
                    <button
                      onClick={() => void handleToggleRoulette()}
                      disabled={isTogglingRoulette}
                      className={`inline-flex items-center gap-2 rounded-2xl px-5 py-3 text-sm font-black transition ${
                        isTogglingRoulette
                          ? "cursor-not-allowed bg-white/10 text-white/40"
                          : isRouletteActive
                            ? "bg-rose-500/90 text-white hover:bg-rose-500"
                            : "bg-[#00FFA3] text-[#000000] hover:bg-[#00FFA3]/90"
                      }`}
                    >
                      <Gift className="h-4 w-4" />
                      {isTogglingRoulette ? "처리 중..." : isRouletteActive ? "룰렛 중지" : "룰렛 시작"}
                    </button>
                  </>
                )}
                <button
                  onClick={handleLogout}
                  className="inline-flex items-center gap-2 rounded-2xl border border-white/10 bg-transparent px-5 py-3 text-sm font-black text-white/70 transition hover:bg-white/[0.06] hover:text-white"
                >
                  <Lock className="h-4 w-4" />
                  로그아웃
                </button>
              </>
            )}
          </div>
        </div>

        {sessionNotice && (
          <div
            className={`mt-5 rounded-2xl border px-4 py-3 text-sm font-semibold ${
              sessionNotice.tone === "good"
                ? "border-[#00FFA3]/25 bg-[#00FFA3]/10 text-[#00FFA3]"
                : "border-amber-500/25 bg-amber-500/10 text-amber-400"
            }`}
          >
            {sessionNotice.message}
          </div>
        )}

        {authLoading ? null : hasOwnerIdentity && !isAuthorizedChannel ? (
          <div className="mt-5 rounded-2xl border border-amber-500/25 bg-amber-500/10 px-4 py-3 text-sm text-amber-400">
            로그인한 계정의 채널과 현재 채널이 달라 투표 관리 기능을 사용할 수 없습니다.
          </div>
        ) : !hasOwnerIdentity && !authLoading ? (
          <div className="mt-5 rounded-2xl border border-white/[0.08] bg-[#1A1A1A] px-4 py-3 text-sm text-white/50">
            {ownerProfile.message || "치지직 로그인 후 본인 채널의 투표와 VOD 분석을 사용할 수 있습니다."}
          </div>
        ) : null}
      </section>

      {activeTab === "vod" ? (
        <div className="min-h-[780px]">
          <VodHighlightBoard personalizationEnabled={hasOwnerIdentity} />
        </div>
      ) : (
        <div className="space-y-6">
          <PollCard session={pollSession} />
          <RouletteCard
            state={roulette.state}
            result={roulette.result}
            isSpinning={roulette.isSpinning}
            isResetting={roulette.isResetting}
            onSpin={roulette.spin}
            onSetConfig={roulette.setConfig}
            onResetWeights={roulette.resetWeights}
            onClearAll={roulette.clearAll}
            isOwner={isAuthorizedChannel}
          />
        </div>
      )}

      {DevSeedPanel ? <DevSeedPanel channelId={channelId} /> : null}
    </div>
  );
}
