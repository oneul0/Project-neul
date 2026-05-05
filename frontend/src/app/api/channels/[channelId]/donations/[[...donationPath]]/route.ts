import { NextRequest, NextResponse } from "next/server";
import { proxyUpstreamRequest } from "@/lib/server/upstreamProxy";

type RouteContext = {
  params: Promise<{
    channelId: string;
    donationPath?: string[];
  }>;
};

async function proxyDonations(
  request: NextRequest,
  context: RouteContext,
  method: "GET" | "POST" | "DELETE",
) {
  const { channelId, donationPath } = await context.params;
  const suffix = donationPath && donationPath.length > 0 ? `/${donationPath.join("/")}` : "";
  const targetUrl = `http://localhost:8083/api/v1/donations/${channelId}${suffix}${request.nextUrl.search}`;

  return proxyUpstreamRequest({
    request,
    targetUrl,
    method,
    onError: () =>
      NextResponse.json(
        { error: "core_api_unreachable", message: "도네이션 API에 연결하지 못했습니다." },
        { status: 502 },
      ),
  });
}

export async function GET(request: NextRequest, context: RouteContext) {
  return proxyDonations(request, context, "GET");
}

export async function POST(request: NextRequest, context: RouteContext) {
  return proxyDonations(request, context, "POST");
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  return proxyDonations(request, context, "DELETE");
}
