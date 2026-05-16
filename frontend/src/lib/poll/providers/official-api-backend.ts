import { createUnsupportedProvider } from "@/lib/poll/provider";

export const officialApiBackendProvider = createUnsupportedProvider("OFFICIAL_API_BACKEND", "Official API Backend", {
  mode: "OFFICIAL_API_BACKEND",
  available: false,
  requiresBackend: true,
  browserDirect: false,
  official: true,
  reason: "현재 지원하지 않는 모드입니다.",
});
