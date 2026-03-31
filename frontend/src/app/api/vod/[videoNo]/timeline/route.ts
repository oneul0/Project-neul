import { NextResponse } from "next/server";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ videoNo: string }> },
) {
  const { videoNo } = await params;

  try {
    const response = await fetch(`http://localhost:8083/api/v1/vod/${videoNo}/timeline`, {
      cache: "no-store",
    });
    if (!response.ok) {
      return NextResponse.json([], { status: 200 });
    }

    const text = await response.text();

    return new NextResponse(text, {
      status: response.status,
      headers: {
        "Content-Type": response.headers.get("Content-Type") ?? "application/json",
      },
    });
  } catch {
    return NextResponse.json([], { status: 200 });
  }
}
