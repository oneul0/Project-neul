import { NextRequest, NextResponse } from "next/server";
import { proxyUpstreamRequest } from "@/lib/server/upstreamProxy";

type RouteContext = {
  params: Promise<{
    channelId: string;
    v2Path?: string[];
  }>;
};

export async function GET(request: NextRequest, context: RouteContext) {
  const { channelId, v2Path } = await context.params;
  const suffix = v2Path && v2Path.length > 0 ? `/${v2Path.join("/")}` : "";
  const targetUrl = `${process.env.CORE_API_URL ?? "http://localhost:8083"}/api/v2/${suffix ? `${suffix.slice(1)}/` : ""}${channelId}${request.nextUrl.search}`;
  const isStreamRequest = v2Path?.[0] === "stream";

  return proxyUpstreamRequest({
    request,
    targetUrl,
    fallbackContentType: isStreamRequest ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8",
    onError: (error) =>
      isStreamRequest
        ? NextResponse.json(
            {
              error: "core_api_unreachable",
              message: error instanceof Error ? error.message : "V2 실시간 스트림에 연결하지 못했습니다.",
            },
            { status: 502 },
          )
        : NextResponse.json({}, { status: 502 }),
  });
}
