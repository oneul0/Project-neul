import { createUnsupportedProvider } from "@/lib/poll/provider";

export const chatDirectProvider = createUnsupportedProvider("CHAT_DIRECT", "Chat Direct", {
  mode: "CHAT_DIRECT",
  available: false,
  requiresBackend: false,
  browserDirect: false,
  official: false,
  reason: "현재 지원하지 않는 모드입니다.",
});
