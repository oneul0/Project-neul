import { cookies } from "next/headers";
import { NextRequest, NextResponse } from "next/server";

type RouteContext = {
  params: Promise<{
    channelId: string;
  }>;
};

async function proxySubscribe(request: NextRequest, context: RouteContext, method: "POST" | "DELETE") {
  const { channelId } = await context.params;
  const cookieStore = await cookies();
  const ownerId = request.headers.get("x-chzzk-owner-id");

  try {
    const upstream = await fetch(`http://localhost:8081/api/v1/channels/${channelId}/subscribe`, {
      method,
      headers: {
        cookie: cookieStore.toString(),
        ...(ownerId ? { "X-Chzzk-Owner-Id": ownerId } : {}),
      },
      cache: "no-store",
    });

    const bodyText = await upstream.text();
    const response = new NextResponse(bodyText, {
      status: upstream.status,
      headers: {
        "content-type": upstream.headers.get("content-type") ?? "application/json; charset=utf-8",
      },
    });

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
  } catch (error) {
    return NextResponse.json(
      {
        error: "collector_unreachable",
        message: error instanceof Error ? error.message : "Collector API 호출에 실패했습니다.",
      },
      { status: 502 },
    );
  }
}

export async function POST(request: NextRequest, context: RouteContext) {
  return proxySubscribe(request, context, "POST");
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  return proxySubscribe(request, context, "DELETE");
}
