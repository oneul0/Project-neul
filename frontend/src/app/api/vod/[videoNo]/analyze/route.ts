import { cookies } from "next/headers";
import { NextResponse } from "next/server";

export async function POST(
  _request: Request,
  { params }: { params: Promise<{ videoNo: string }> },
) {
  const { videoNo } = await params;
  const cookieStore = await cookies();

  try {
    const response = await fetch(`${process.env.CORE_API_URL ?? "http://localhost:8083"}/api/v1/vod/${videoNo}/analyze`, {
      method: "POST",
      headers: {
        cookie: cookieStore.toString(),
      },
    });
    const text = await response.text();

    if (!response.ok) {
      return NextResponse.json(
        { message: text || "분석 요청 처리 중 오류가 발생했습니다." },
        { status: response.status },
      );
    }

    return new NextResponse(text, {
      status: response.status,
      headers: {
        "Content-Type": response.headers.get("Content-Type") ?? "text/plain; charset=utf-8",
      },
    });
  } catch {
    return NextResponse.json(
      { message: "분석 요청을 전달하지 못했습니다." },
      { status: 502 },
    );
  }
}
