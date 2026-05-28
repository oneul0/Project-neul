import { cookies } from "next/headers";
import { NextResponse } from "next/server";

type RouteContext = {
  params: Promise<{
    channelId: string;
  }>;
};

export async function GET(_: Request, context: RouteContext) {
  const { channelId } = await context.params;
  const cookieStore = await cookies();

  try {
    const upstream = await fetch(`${process.env.COLLECTOR_URL ?? "http://localhost:8081"}/api/v1/channels/${channelId}/status`, {
      headers: {
        cookie: cookieStore.toString(),
      },
      cache: "no-store",
    });

    const bodyText = await upstream.text();
    return new NextResponse(bodyText, {
      status: upstream.status,
      headers: {
        "content-type": upstream.headers.get("content-type") ?? "application/json; charset=utf-8",
      },
    });
  } catch (error) {
    return NextResponse.json(
      {
        channelId,
        live: false,
        status: "failed",
        message: error instanceof Error ? error.message : "방송 상태를 확인하지 못했습니다.",
      },
      { status: 502 },
    );
  }
}
