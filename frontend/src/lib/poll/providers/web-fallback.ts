import { createUnsupportedProvider } from "@/lib/poll/provider";

export const webFallbackProvider = createUnsupportedProvider("WEB_FALLBACK", "Web Fallback", {
  mode: "WEB_FALLBACK",
  available: false,
  requiresBackend: false,
  browserDirect: false,
  official: false,
  reason: "WEB_FALLBACK은 향후 비투표 대체 UX를 위한 자리만 마련했고 이번 턴에서는 연결하지 않았습니다.",
});
