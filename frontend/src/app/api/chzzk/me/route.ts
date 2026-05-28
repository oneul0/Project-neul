import { cookies } from "next/headers";
import { NextResponse } from "next/server";

export async function GET() {
  const cookieStore = await cookies();
  let upstream: Response;
  try {
    upstream = await fetch(`${process.env.COLLECTOR_URL ?? "http://localhost:8081"}/api/v1/chzzk/me`, {
      headers: {
        cookie: cookieStore.toString(),
      },
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({
      authenticated: false,
      authUnavailable: true,
      message: "인증 서버에 연결하지 못했습니다.",
    });
  }

  const data = await upstream.json().catch(() => ({
    authenticated: false,
    authUnavailable: true,
    message: "로그인 상태를 확인하지 못했습니다.",
  }));

  const upstreamUnavailable = !upstream.ok && upstream.status >= 500;

  const response = NextResponse.json(
    upstream.ok
      ? data
      : upstreamUnavailable
        ? {
            authenticated: false,
            authUnavailable: true,
            message: data?.message || "로그인 상태를 일시적으로 확인하지 못했습니다.",
          }
        : {
            authenticated: false,
            message: data?.message || "치지직 로그인이 필요합니다.",
          },
    { status: 200 },
  );

  const setCookies =
    typeof upstream.headers.getSetCookie === "function"
      ? upstream.headers.getSetCookie()
      : upstream.headers.get("set-cookie")
        ? [upstream.headers.get("set-cookie") as string]
        : [];
  for (const cookie of setCookies) {
    response.headers.append("set-cookie", cookie);
  }

  return response;
}
