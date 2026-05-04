/**
 * 개발 환경 전용 — 테스트 데이터 시드 프록시.
 *
 * core-api 의 /api/dev/seed/{channelId} 엔드포인트로 요청을 중계합니다.
 * production 빌드에서도 라우트 파일 자체는 존재하지만,
 * 백엔드 dev 프로필이 비활성화되어 있으면 502를 반환합니다.
 */
import { NextRequest, NextResponse } from "next/server";

const CORE_API_BASE = process.env.CORE_API_URL ?? "http://localhost:8083";

type RouteContext = { params: Promise<{ channelId: string }> };

async function proxy(req: NextRequest, ctx: RouteContext, method: string) {
  const { channelId } = await ctx.params;
  const search = req.nextUrl.search;
  const url = `${CORE_API_BASE}/api/dev/seed/${channelId}${search}`;

  try {
    const upstream = await fetch(url, {
      method,
      headers: { "Content-Type": "application/json" },
    });
    const body = await upstream.json().catch(() => ({}));
    return NextResponse.json(body, { status: upstream.status });
  } catch {
    return NextResponse.json(
      { error: "dev_seed_unreachable", message: "core-api dev 시드 엔드포인트에 연결할 수 없습니다. dev 프로필로 실행 중인지 확인하세요." },
      { status: 502 },
    );
  }
}

export const GET = (req: NextRequest, ctx: RouteContext) => proxy(req, ctx, "GET");
export const POST = (req: NextRequest, ctx: RouteContext) => proxy(req, ctx, "POST");
export const DELETE = (req: NextRequest, ctx: RouteContext) => proxy(req, ctx, "DELETE");
