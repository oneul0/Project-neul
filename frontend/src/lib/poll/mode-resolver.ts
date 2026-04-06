import type { PollCapability, PollMode, ResolvedPollMode } from "@/lib/poll/types";

type CapabilityMap = Record<ResolvedPollMode, PollCapability>;

export function resolvePollMode(preferredMode: PollMode, capabilities: CapabilityMap): ResolvedPollMode {
  if (preferredMode !== "AUTO") {
    if (preferredMode === "CHAT_DIRECT") {
      return "CHAT_DIRECT";
    }

    if (preferredMode === "OFFICIAL_API_DIRECT") {
      return "OFFICIAL_API_DIRECT";
    }

    if (preferredMode === "OFFICIAL_API_BACKEND") {
      return "OFFICIAL_API_BACKEND";
    }

    return "WEB_FALLBACK";
  }

  if (capabilities.OFFICIAL_API_DIRECT.available) {
    return "OFFICIAL_API_DIRECT";
  }

  if (capabilities.OFFICIAL_API_BACKEND.available) {
    return "OFFICIAL_API_BACKEND";
  }

  if (capabilities.BACKEND_CHAT.available) {
    return "BACKEND_CHAT";
  }

  if (capabilities.CHAT_DIRECT.available) {
    return "CHAT_DIRECT";
  }

  return "WEB_FALLBACK";
}
