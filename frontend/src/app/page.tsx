"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { LogIn, ShieldCheck } from "lucide-react";

interface OwnerProfile {
  authenticated: boolean;
  authUnavailable?: boolean;
  channelId?: string;
  channelName?: string;
  expiresAt?: string;
  message?: string;
}

export default function Home() {
  const router = useRouter();
  const [ownerProfile, setOwnerProfile] = useState<OwnerProfile | null>(null);
  const [authLoading, setAuthLoading] = useState(true);

  const isAuthenticated = !!ownerProfile?.authenticated && !!ownerProfile?.channelId;

  useEffect(() => {
    const fetchOwnerProfile = async () => {
      try {
        setAuthLoading(true);
        const response = await fetch("/api/chzzk/me", {
          cache: "no-store",
        });
        const profile = (await response.json()) as OwnerProfile;
        if (profile.authUnavailable) {
          setOwnerProfile((prev) => ({
            authenticated: prev?.authenticated ?? false,
            channelId: prev?.channelId,
            channelName: prev?.channelName,
            expiresAt: prev?.expiresAt,
            authUnavailable: true,
            message: profile.message || "로그인 상태를 일시적으로 확인하지 못했습니다.",
          }));
          return;
        }
        setOwnerProfile(profile);

        if (profile.authenticated && profile.channelId) {
          router.replace(`/channels/${profile.channelId}`);
        }
      } catch {
        setOwnerProfile((prev) => ({
          authenticated: prev?.authenticated ?? false,
          channelId: prev?.channelId,
          channelName: prev?.channelName,
          expiresAt: prev?.expiresAt,
          authUnavailable: true,
          message: "로그인 상태를 일시적으로 확인하지 못했습니다.",
        }));
      } finally {
        setAuthLoading(false);
      }
    };

    fetchOwnerProfile();
  }, [router]);

  const handleLogin = () => {
    window.location.href = "/api/chzzk/login";
  };

  return (
    <div className="flex min-h-[calc(100vh-220px)] items-center justify-center">
      <section className="w-full max-w-3xl rounded-[36px] border border-slate-200 bg-white p-8 shadow-[0_24px_80px_rgba(15,23,42,0.08)] sm:p-12">
        <div className="mx-auto max-w-2xl text-center">
          <div className="mx-auto inline-flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-4 py-2 text-[11px] font-black tracking-[0.2em] text-emerald-700">
            <ShieldCheck className="h-3.5 w-3.5" />
            스트리머 전용 대시보드
          </div>

          <h1 className="mt-6 text-4xl font-black tracking-tight text-slate-950 sm:text-5xl">
            방송 중 시청자 흐름을
            <br />
            한눈에 보는 운영 화면
          </h1>

          <p className="mt-4 text-base leading-8 text-slate-600">
            늘 스트리머가 자신의 방송 채팅 흐름을 빠르게 파악하고,
            악성 반응에 과몰입하지 않도록 돕는 실시간 심리 가드레일 대시보드입니다.
          </p>

          <div className="mt-10 rounded-[28px] border border-slate-200 bg-slate-50 p-6 text-left">
            <div className="text-[11px] font-black tracking-[0.22em] text-slate-500">현재 상태</div>

            {authLoading ? (
              <div className="mt-4 text-sm font-bold text-slate-500">로그인 상태를 확인하고 있습니다...</div>
            ) : isAuthenticated ? (
              <>
                <div className="mt-4 text-2xl font-black text-slate-950">
                  {ownerProfile?.channelName || "내 채널"} 대시보드로 이동 중입니다
                </div>
                <div className="mt-2 text-sm text-slate-600">
                  로그인된 계정을 기준으로 본인 방송 대시보드를 바로 엽니다.
                </div>
              </>
            ) : (
              <>
                <div className="mt-4 text-2xl font-black text-slate-950">치지직 로그인 후 시작</div>
                <div className="mt-2 text-sm text-slate-600">
                  {ownerProfile?.message || "본인 방송 소유자만 대시보드에 접근할 수 있습니다."}
                </div>
              </>
            )}

            {!isAuthenticated ? (
              <div className="mt-6 flex flex-wrap gap-3">
                <button
                  onClick={handleLogin}
                  className="inline-flex items-center gap-2 rounded-2xl bg-slate-950 px-5 py-3 text-sm font-black text-white transition hover:bg-slate-800"
                >
                  <LogIn className="h-4 w-4" />
                  치지직으로 로그인
                </button>
              </div>
            ) : null}
          </div>
        </div>
      </section>
    </div>
  );
}
