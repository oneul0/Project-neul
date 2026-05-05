"use client";

import { useCallback, useEffect, useState } from "react";

export interface OwnerProfile {
  authenticated: boolean;
  authUnavailable?: boolean;
  channelId?: string;
  channelName?: string;
  expiresAt?: string;
  refreshed?: boolean;
  message?: string;
}

export interface InlineNotice {
  tone: "good" | "warn";
  message: string;
}

export const EMPTY_OWNER_PROFILE: OwnerProfile = {
  authenticated: false,
  message: "치지직 로그인 후 사용할 수 있습니다.",
};

export function useOwnerDashboardSession(onUnauthorized: () => void) {
  const [ownerChannelId, setOwnerChannelId] = useState("");
  const [ownerProfile, setOwnerProfile] = useState<OwnerProfile>(EMPTY_OWNER_PROFILE);
  const [authLoading, setAuthLoading] = useState(true);
  const [sessionNotice, setSessionNotice] = useState<InlineNotice | null>(null);

  const handleUnauthorizedSession = useCallback(
    (message = "로그인 세션이 만료되었습니다. 다시 로그인해 주세요.") => {
      onUnauthorized();
      setOwnerProfile({ authenticated: false, message });
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
        if (!silent) setAuthLoading(true);

        const response = await fetch("/api/chzzk/me", { cache: "no-store" });
        const profile = (await response.json()) as OwnerProfile;

        if (disposed) return;

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
          setSessionNotice({ tone: "warn", message: profile.message || "다시 로그인해 주세요." });
          return;
        }

        setOwnerProfile({ ...profile, authUnavailable: false });
        setOwnerChannelId(profile.channelId ?? "");
        if (profile.refreshed) {
          setSessionNotice({ tone: "good", message: "로그인 세션이 자동으로 연장되었습니다." });
        }
      } catch {
        if (!disposed) {
          setOwnerProfile((prev) => ({
            ...prev,
            message: "치지직 로그인 상태를 일시적으로 확인하지 못했습니다.",
            authUnavailable: true,
          }));
          setSessionNotice({ tone: "warn", message: "치지직 로그인 상태를 확인하지 못했습니다." });
        }
      } finally {
        if (!disposed) setAuthLoading(false);
      }
    };

    void fetchOwnerProfile();

    const intervalId = window.setInterval(() => void fetchOwnerProfile(true), 60_000);
    return () => {
      disposed = true;
      window.clearInterval(intervalId);
    };
  }, [onUnauthorized]);

  useEffect(() => {
    if (!sessionNotice) return;
    const id = window.setTimeout(() => setSessionNotice(null), 5000);
    return () => window.clearTimeout(id);
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
