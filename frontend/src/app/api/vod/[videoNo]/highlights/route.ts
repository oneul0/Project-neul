import { cookies } from "next/headers";
import { NextResponse } from "next/server";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ videoNo: string }> },
) {
  const { videoNo } = await params;
  const cookieStore = await cookies();

  try {
    const response = await fetch(`http://localhost:8083/api/v1/vod/${videoNo}/highlights`, {
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
