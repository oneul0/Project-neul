import { NextResponse } from "next/server";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ videoNo: string }> },
) {
  const { videoNo } = await params;

  try {
    const response = await fetch(`${process.env.COLLECTOR_URL ?? "http://localhost:8081"}/api/v1/vod/${videoNo}/status`, {
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
    return NextResponse.json(
      { videoNo, status: "FAILED", message: "분석 상태를 불러오지 못했습니다." },
      { status: 502 },
    );
  }
}
