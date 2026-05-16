import { createUnsupportedProvider } from "@/lib/poll/provider";

export const webFallbackProvider = createUnsupportedProvider("WEB_FALLBACK", "Web Fallback", {
  mode: "WEB_FALLBACK",
  available: false,
  requiresBackend: false,
  browserDirect: false,
  official: false,
  reason: "현재 지원하지 않는 모드입니다.",
});
