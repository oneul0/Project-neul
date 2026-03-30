"use client";

const OWNER_CHANNEL_STORAGE_KEY = "neul.ownerChannelId";

export function readOwnerChannelId(): string {
  if (typeof window === "undefined") {
    return "";
  }

  return window.localStorage.getItem(OWNER_CHANNEL_STORAGE_KEY)?.trim() ?? "";
}

export function persistOwnerChannelId(ownerChannelId: string) {
  if (typeof window === "undefined") {
    return;
  }

  const normalized = ownerChannelId.trim();
  if (!normalized) {
    window.localStorage.removeItem(OWNER_CHANNEL_STORAGE_KEY);
    return;
  }

  window.localStorage.setItem(OWNER_CHANNEL_STORAGE_KEY, normalized);
}

export function buildOwnerHeaders(ownerChannelId: string): HeadersInit {
  return {
    "X-Chzzk-Owner-Id": ownerChannelId,
  };
}

export function appendOwnerId(url: string, ownerChannelId: string): string {
  const nextUrl = new URL(url);
  nextUrl.searchParams.set("ownerId", ownerChannelId);
  return nextUrl.toString();
}
