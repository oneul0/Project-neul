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
      <section className="w-full max-w-3xl rounded-[32px] border border-white/[0.08] bg-[#111111] p-8 shadow-[0_24px_80px_rgba(0,0,0,0.5)] sm:p-12">
        <div className="mx-auto max-w-2xl text-center">
          <div className="mx-auto inline-flex items-center gap-2 rounded-full border border-[#00FFA3]/25 bg-[#00FFA3]/10 px-4 py-2 text-[11px] font-black tracking-[0.2em] text-[#00FFA3]">
            <ShieldCheck className="h-3.5 w-3.5" />
            스트리머 전용
          </div>

          <ul className="mt-6 space-y-2 text-base text-white/60">
            <li className="flex items-center justify-center gap-2">
              <span className="h-1.5 w-1.5 rounded-full bg-[#00FFA3]" />
              실시간 투표
            </li>
            <li className="flex items-center justify-center gap-2">
              <span className="h-1.5 w-1.5 rounded-full bg-[#00FFA3]" />
              도네이션 룰렛
            </li>
            <li className="flex items-center justify-center gap-2">
              <span className="h-1.5 w-1.5 rounded-full bg-[#00FFA3]" />
              VOD 하이라이트 추출
            </li>
          </ul>

          <div className="mt-10 rounded-[24px] border border-white/[0.08] bg-[#1A1A1A] p-6 text-left">
            {authLoading ? (
              <div className="mt-4 text-sm font-bold text-white/50">로그인 상태를 확인하고 있습니다...</div>
            ) : isAuthenticated ? (
              <>
                <div className="mt-4 text-2xl font-black text-white">
                  {ownerProfile?.channelName || "내 채널"} 대시보드로 이동 중입니다
                </div>
                <div className="mt-2 text-sm text-white/60">
                  로그인된 계정을 기준으로 본인 방송 대시보드를 바로 엽니다.
                </div>
              </>
            ) : (
              <>
                <div className="mt-4 text-2xl font-black text-white">치지직 로그인이 필요합니다</div>
              </>
            )}

            {!isAuthenticated ? (
              <div className="mt-6 flex flex-wrap gap-3">
                <button
                  onClick={handleLogin}
                  className="inline-flex items-center gap-2 rounded-2xl bg-[#00FFA3] px-5 py-3 text-sm font-black text-[#000000] transition hover:bg-[#00FFA3]/90 active:scale-95"
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
