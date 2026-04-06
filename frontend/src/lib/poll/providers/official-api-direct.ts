import { createUnsupportedProvider } from "@/lib/poll/provider";

export const officialApiDirectProvider = createUnsupportedProvider("OFFICIAL_API_DIRECT", "Official API Direct", {
  mode: "OFFICIAL_API_DIRECT",
  available: false,
  requiresBackend: false,
  browserDirect: false,
  official: true,
  reason: "공식 문서에서 poll 엔드포인트를 확인하지 못했고, 인증 토큰 교환에는 clientSecret이 필요해 브라우저 direct 모드를 확정할 수 없습니다.",
});
