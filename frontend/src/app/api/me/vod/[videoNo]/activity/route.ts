import { cookies } from "next/headers";
import { NextResponse } from "next/server";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ videoNo: string }> },
) {
  const { videoNo } = await params;
  const cookieStore = await cookies();

  try {
    const response = await fetch(`${process.env.CORE_API_URL ?? "http://localhost:8083"}/api/v1/me/vod/${videoNo}/activity`, {
      headers: {
        cookie: cookieStore.toString(),
      },
      cache: "no-store",
    });

    const text = await response.text();

    return new NextResponse(text, {
      status: response.status,
      headers: {
        "Content-Type": response.headers.get("Content-Type") ?? "application/json",
      },
    });
  } catch {
    return NextResponse.json([], { status: 502 });
  }
}

export async function POST(
  request: Request,
  { params }: { params: Promise<{ videoNo: string }> },
) {
  const { videoNo } = await params;
  const cookieStore = await cookies();
  const body = await request.text();

  try {
    const response = await fetch(`${process.env.CORE_API_URL ?? "http://localhost:8083"}/api/v1/me/vod/${videoNo}/activity`, {
      method: "POST",
      headers: {
        cookie: cookieStore.toString(),
        "Content-Type": "application/json",
      },
      body,
    });

    const text = await response.text();

    return new NextResponse(text, {
      status: response.status,
      headers: {
        "Content-Type": response.headers.get("Content-Type") ?? "application/json",
      },
    });
  } catch {
    return NextResponse.json(
      { message: "사용자 활동을 기록하지 못했습니다." },
      { status: 502 },
    );
  }
}
