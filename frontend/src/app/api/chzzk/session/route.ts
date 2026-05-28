import { NextRequest, NextResponse } from "next/server";

export async function GET(request: NextRequest) {
  const { searchParams } = request.nextUrl;
  const channelId = searchParams.get("channelId");
  const sessionId = searchParams.get("sessionId");
  const ownerToken = searchParams.get("ownerToken");
  const expiresIn = parseInt(searchParams.get("expiresIn") ?? "3600", 10);

  if (!channelId || !sessionId || !ownerToken) {
    return NextResponse.redirect(new URL("/?auth=failed", request.url));
  }

  const response = NextResponse.redirect(
    new URL(`/channels/${channelId}?auth=success`, request.url),
  );

  const cookieOptions = {
    httpOnly: true,
    sameSite: "lax" as const,
    maxAge: expiresIn,
    path: "/",
  };

  response.cookies.set("GAK_CHZZK_AUTH_SESSION", sessionId, cookieOptions);
  response.cookies.set("GAK_OWNER_ASSERTION", ownerToken, cookieOptions);

  return response;
}
