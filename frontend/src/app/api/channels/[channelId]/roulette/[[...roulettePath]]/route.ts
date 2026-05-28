import { NextRequest, NextResponse } from "next/server";
import { proxyUpstreamRequest } from "@/lib/server/upstreamProxy";

type RouteContext = {
  params: Promise<{
    channelId: string;
    roulettePath?: string[];
  }>;
};

async function proxyRoulette(
  request: NextRequest,
  context: RouteContext,
  method: "GET" | "PUT" | "POST" | "DELETE",
) {
  const { channelId, roulettePath } = await context.params;
  const suffix = roulettePath && roulettePath.length > 0 ? `/${roulettePath.join("/")}` : "";
  const targetUrl = `${process.env.CORE_API_URL ?? "http://localhost:8083"}/api/v1/roulette/${channelId}${suffix}${request.nextUrl.search}`;

  return proxyUpstreamRequest({
    request,
    targetUrl,
    method,
    onError: () =>
      NextResponse.json(
        { error: "core_api_unreachable", message: "룰렛 API에 연결하지 못했습니다." },
        { status: 502 },
      ),
  });
}

export async function GET(request: NextRequest, context: RouteContext) {
  return proxyRoulette(request, context, "GET");
}

export async function PUT(request: NextRequest, context: RouteContext) {
  return proxyRoulette(request, context, "PUT");
}

export async function POST(request: NextRequest, context: RouteContext) {
  return proxyRoulette(request, context, "POST");
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  return proxyRoulette(request, context, "DELETE");
}
