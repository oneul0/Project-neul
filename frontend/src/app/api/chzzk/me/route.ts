import { cookies } from "next/headers";
import { NextResponse } from "next/server";

export async function GET() {
  const cookieStore = await cookies();
  let upstream: Response;
  try {
    upstream = await fetch("http://localhost:8081/api/v1/chzzk/me", {
      headers: {
        cookie: cookieStore.toString(),
      },
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({
      authenticated: false,
      message: "인증 서버에 연결하지 못했습니다.",
    });
  }

  const data = await upstream.json().catch(() => ({
    authenticated: false,
    message: "로그인 상태를 확인하지 못했습니다.",
  }));

  const response = NextResponse.json(
    upstream.ok
      ? data
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
