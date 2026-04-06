import { createUnsupportedProvider } from "@/lib/poll/provider";

export const chatDirectProvider = createUnsupportedProvider("CHAT_DIRECT", "Chat Direct", {
  mode: "CHAT_DIRECT",
  available: false,
  requiresBackend: false,
  browserDirect: false,
  official: false,
  reason: "공식 문서에서 poll 전용 Open API를 확인하지 못해 CHAT_DIRECT는 이번 턴에서 scaffold만 제공합니다.",
});
