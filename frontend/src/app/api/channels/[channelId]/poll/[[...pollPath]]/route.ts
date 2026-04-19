import { NextRequest, NextResponse } from "next/server";
import { proxyUpstreamRequest } from "@/lib/server/upstreamProxy";

type RouteContext = {
  params: Promise<{
    channelId: string;
    pollPath?: string[];
  }>;
};

async function proxyPoll(request: NextRequest, context: RouteContext, method: "GET" | "POST" | "DELETE") {
  const { channelId, pollPath } = await context.params;
  const suffix = pollPath && pollPath.length > 0 ? `/${pollPath.join("/")}` : "";
  const targetUrl = `http://localhost:8083/api/v1/poll/${channelId}${suffix}${request.nextUrl.search}`;

  return proxyUpstreamRequest({
    request,
    targetUrl,
    method,
    onError: () =>
      NextResponse.json(
        {
          error: "core_api_unreachable",
          message: "투표 API에 연결하지 못했습니다.",
        },
        { status: 502 },
      ),
  });
}

export async function GET(request: NextRequest, context: RouteContext) {
  return proxyPoll(request, context, "GET");
}

export async function POST(request: NextRequest, context: RouteContext) {
  return proxyPoll(request, context, "POST");
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  return proxyPoll(request, context, "DELETE");
}
