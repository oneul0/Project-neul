"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { LogIn, LogOut, ShieldCheck, Sparkles } from "lucide-react";

interface OwnerProfile {
  authenticated: boolean;
  channelId?: string;
  channelName?: string;
  expiresAt?: string;
  message?: string;
}

export default function Home() {
  const router = useRouter();
  const [ownerProfile, setOwnerProfile] = useState<OwnerProfile | null>(null);
  const [authLoading, setAuthLoading] = useState(true);

  const ownerChannelId = ownerProfile?.channelId ?? "";
  const isAuthenticated = !!ownerProfile?.authenticated && !!ownerChannelId;

  useEffect(() => {
    const fetchOwnerProfile = async () => {
      try {
        setAuthLoading(true);
        const response = await fetch("http://localhost:8081/api/v1/chzzk/me", {
          credentials: "include",
        });
        const profile = (await response.json()) as OwnerProfile;
        setOwnerProfile(profile);

        if (response.ok && profile.authenticated && profile.channelId) {
          router.replace(`/channels/${profile.channelId}`);
        }
      } catch {
        setOwnerProfile({
          authenticated: false,
          message: "Could not verify the current CHZZK session.",
        });
      } finally {
        setAuthLoading(false);
      }
    };

    fetchOwnerProfile();
  }, [router]);

  const handleLogin = () => {
    window.location.href = "http://localhost:8081/api/v1/chzzk/login";
  };

  const handleLogout = async () => {
    await fetch("http://localhost:8081/api/v1/chzzk/logout", {
      method: "DELETE",
      credentials: "include",
    });

    setOwnerProfile({
      authenticated: false,
      message: "You have been signed out.",
    });
  };

  return (
    <div className="flex min-h-[calc(100vh-220px)] items-center justify-center">
      <section className="w-full max-w-3xl rounded-[36px] border border-slate-200 bg-white p-8 shadow-[0_24px_80px_rgba(15,23,42,0.08)] sm:p-12">
        <div className="mx-auto max-w-2xl text-center">
          <div className="mx-auto inline-flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-4 py-2 text-[11px] font-black uppercase tracking-[0.25em] text-emerald-700">
            <ShieldCheck className="h-3.5 w-3.5" />
            Owner Dashboard
          </div>

          <h1 className="mt-6 text-4xl font-black tracking-tight text-slate-950 sm:text-5xl">
            Sign in and go straight to your stream dashboard
          </h1>
          <p className="mt-4 text-base leading-8 text-slate-600">
            This workspace is no longer a public stream list. Once your CHZZK account is verified,
            you are redirected directly into your own operator dashboard.
          </p>

          <div className="mt-10 rounded-[28px] border border-slate-200 bg-slate-50 p-6 text-left">
            <div className="text-[11px] font-black uppercase tracking-[0.22em] text-slate-500">
              Authentication status
            </div>

            {authLoading ? (
              <div className="mt-4 text-sm font-bold text-slate-500">
                Checking current CHZZK session...
              </div>
            ) : isAuthenticated ? (
              <>
                <div className="mt-4 text-2xl font-black text-slate-950">
                  {ownerProfile?.channelName || "Owner channel"} is authenticated
                </div>
                <div className="mt-2 text-sm text-slate-600">
                  Redirecting to your dashboard now.
                </div>
              </>
            ) : (
              <>
                <div className="mt-4 text-2xl font-black text-slate-950">
                  Sign in to continue
                </div>
                <div className="mt-2 text-sm text-slate-600">
                  {ownerProfile?.message ||
                    "Only the broadcaster who owns the channel can open the analytics dashboard."}
                </div>
              </>
            )}

            <div className="mt-6 flex flex-wrap gap-3">
              {isAuthenticated ? (
                <button
                  onClick={handleLogout}
                  className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-5 py-3 text-sm font-black text-slate-700 transition hover:bg-slate-100"
                >
                  <LogOut className="h-4 w-4" />
                  Sign out
                </button>
              ) : (
                <button
                  onClick={handleLogin}
                  className="inline-flex items-center gap-2 rounded-2xl bg-slate-950 px-5 py-3 text-sm font-black text-white transition hover:bg-slate-800"
                >
                  <LogIn className="h-4 w-4" />
                  Sign in with CHZZK
                </button>
              )}

              <div className="inline-flex items-center gap-2 rounded-2xl border border-slate-200 bg-white px-5 py-3 text-sm font-black text-slate-600">
                <Sparkles className="h-4 w-4 text-emerald-500" />
                Owner-only analytics flow
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
