import { createUnsupportedProvider } from "@/lib/poll/provider";

export const officialApiBackendProvider = createUnsupportedProvider("OFFICIAL_API_BACKEND", "Official API Backend", {
  mode: "OFFICIAL_API_BACKEND",
  available: false,
  requiresBackend: true,
  browserDirect: false,
  official: true,
  reason: "공식 문서에서 poll 엔드포인트 자체를 확인하지 못해 OFFICIAL_API_BACKEND는 provider scaffold만 준비했습니다.",
});
