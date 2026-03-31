import { cookies } from "next/headers";
import { NextResponse } from "next/server";

export async function DELETE() {
  const cookieStore = await cookies();
  const upstream = await fetch("http://localhost:8081/api/v1/chzzk/logout", {
    method: "DELETE",
    headers: {
      cookie: cookieStore.toString(),
    },
    cache: "no-store",
  });

  const data = await upstream.json().catch(() => ({ ok: true }));
  const response = NextResponse.json(data, { status: 200 });

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
