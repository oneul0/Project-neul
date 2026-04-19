import { NextRequest, NextResponse } from "next/server";
import { proxyUpstreamRequest } from "@/lib/server/upstreamProxy";

type RouteContext = {
  params: Promise<{
    channelId: string;
    streamPath?: string[];
  }>;
};

export async function GET(request: NextRequest, context: RouteContext) {
  const { channelId, streamPath } = await context.params;
  const suffix = streamPath && streamPath.length > 0 ? `/${streamPath.join("/")}` : "";
  const targetUrl = `http://localhost:8083/api/v1/stream/${channelId}${suffix}${request.nextUrl.search}`;
  const isStreamRequest = !streamPath || streamPath.length === 0;

  return proxyUpstreamRequest({
    request,
    targetUrl,
    fallbackContentType: isStreamRequest ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8",
    onError: (error) =>
      isStreamRequest
        ? NextResponse.json(
            {
              error: "core_api_unreachable",
              message: error instanceof Error ? error.message : "실시간 스트림에 연결하지 못했습니다.",
            },
            { status: 502 },
          )
        : NextResponse.json([], { status: 200 }),
  });
}
