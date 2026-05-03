"use client";

import { use, useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { Film, Lock, LogIn, Radio, Users } from "lucide-react";
import VodHighlightBoard from "@/components/VodHighlightBoard";
import PollCard from "@/components/poll/PollCard";
import { usePollSession } from "@/hooks/usePollSession";
import { useOwnerDashboardSession } from "./dashboard-helpers";

export default function ChannelDashboard({
  params,
}: {
  params: Promise<{ channelId: string }>;
}) {
  const { channelId } = use(params);
  const router = useRouter();

  const [activeTab, setActiveTab] = useState<"poll" | "vod">("poll");
  const [isTogglingCollection, setIsTogglingCollection] = useState(false);

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

  const handleLogin = () => {
    window.location.href = "/api/chzzk/login";
  };

  const handleLogout = async () => {
    await fetch("/api/chzzk/logout", { method: "DELETE" });
    resetOwnerSession();
    router.replace("/");
  };

  const handleToggleCollection = async () => {
    if (!isAuthorizedChannel) {
      setSessionNotice({ tone: "warn", message: "본인 채널에서만 수집을 시작할 수 있습니다." });
      return;
    }

    setIsTogglingCollection(true);
    try {
      const nextActive = !pollSession.isSessionActive;

      if (nextActive) {
        const res = await fetch(`/api/channels/${channelId}/subscribe`, {
          method: "POST",
          credentials: "include",
        });
        if (res.status === 401) {
          handleUnauthorizedSession();
          return;
        }
        if (res.status === 403) {
          setSessionNotice({ tone: "warn", message: "본인 채널만 수집할 수 있습니다." });
          return;
        }
        if (!res.ok) {
          throw new Error("채팅 수집을 시작하지 못했습니다.");
        }
      } else {
        await fetch(`/api/channels/${channelId}/subscribe`, {
          method: "DELETE",
          credentials: "include",
        });
      }

      await pollSession.setSessionActive(nextActive);
      setSessionNotice({
        tone: "good",
        message: nextActive ? "채팅 수집을 시작했습니다." : "채팅 수집을 중지했습니다.",
      });
    } catch (e) {
      setSessionNotice({
        tone: "warn",
        message: e instanceof Error ? e.message : "오류가 발생했습니다.",
      });
    } finally {
      setIsTogglingCollection(false);
    }
  };

  const collectionButtonLabel = isTogglingCollection
    ? "처리 중..."
    : pollSession.isSessionActive
      ? "수집 중지"
      : "수집 시작";

  const collectionButtonClass = isTogglingCollection
    ? "cursor-not-allowed bg-slate-200 text-slate-500"
    : pollSession.isSessionActive
      ? "bg-rose-500 text-white hover:bg-rose-400"
      : "bg-sky-500 text-slate-950 hover:bg-sky-400";

  return (
    <div className="space-y-8">
      <section className="rounded-[36px] border border-slate-200 bg-white p-8 shadow-[0_24px_80px_rgba(15,23,42,0.08)]">
        <div className="flex flex-col gap-6 xl:flex-row xl:items-start xl:justify-between">
          <div className="space-y-2">
            <div className="text-[11px] font-black uppercase tracking-[0.28em] text-slate-400">
              {activeTab === "poll" ? "투표 관리" : "VOD 하이라이트"}
            </div>
            <h1 className="text-3xl font-black tracking-tight text-slate-950">
              {activeTab === "poll"
                ? "시청자 반응을 정의하는 투표"
                : "편집 후보를 고르는 워크스페이스"}
            </h1>
            <p className="text-sm leading-6 text-slate-600">
              {activeTab === "poll"
                ? "항목을 만들고 실시간 집계 결과와 참여 시청자 기록을 관리합니다."
                : "VOD를 조회한 뒤 하이라이트 후보를 검토하고 편집점을 저장합니다."}
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <div className="grid grid-cols-2 rounded-[24px] border border-slate-200 bg-slate-100 p-1.5">
              <button
                onClick={() => setActiveTab("poll")}
                className={`inline-flex items-center justify-center gap-2 rounded-[18px] px-5 py-3 text-sm font-black transition ${
                  activeTab === "poll"
                    ? "bg-slate-950 text-white shadow-sm"
                    : "text-slate-500 hover:text-slate-950"
                }`}
              >
                <Users className="h-4 w-4" />
                투표
              </button>
              <button
                onClick={() => setActiveTab("vod")}
                className={`inline-flex items-center justify-center gap-2 rounded-[18px] px-5 py-3 text-sm font-black transition ${
                  activeTab === "vod"
                    ? "bg-slate-950 text-white shadow-sm"
                    : "text-slate-500 hover:text-slate-950"
                }`}
              >
                <Film className="h-4 w-4" />
                VOD
              </button>
            </div>

            {!hasOwnerIdentity ? (
              <button
                onClick={handleLogin}
                className="inline-flex items-center gap-2 rounded-2xl bg-slate-950 px-5 py-3 text-sm font-black text-white transition hover:bg-slate-800"
              >
                <LogIn className="h-4 w-4" />
                치지직 로그인
              </button>
            ) : (
              <>
                {activeTab === "poll" && isAuthorizedChannel && (
                  <button
                    onClick={handleToggleCollection}
                    disabled={isTogglingCollection}
                    className={`inline-flex items-center gap-2 rounded-2xl px-5 py-3 text-sm font-black transition ${collectionButtonClass}`}
                  >
                    <Radio className="h-4 w-4" />
                    {collectionButtonLabel}
                  </button>
                )}
                <button
                  onClick={handleLogout}
                  className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-5 py-3 text-sm font-black text-slate-700 transition hover:bg-slate-100"
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
                ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                : "border-amber-200 bg-amber-50 text-amber-700"
            }`}
          >
            {sessionNotice.message}
          </div>
        )}

        {authLoading ? null : hasOwnerIdentity && !isAuthorizedChannel ? (
          <div className="mt-5 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            로그인한 계정의 채널과 현재 채널이 달라 투표 관리 기능을 사용할 수 없습니다.
          </div>
        ) : !hasOwnerIdentity && !authLoading ? (
          <div className="mt-5 rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-600">
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
          <PollCard session={pollSession} variant="history" />
        </div>
      )}
    </div>
  );
}
