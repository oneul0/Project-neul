import { NextResponse } from "next/server";

export async function GET() {
  return NextResponse.redirect("http://localhost:8081/api/v1/chzzk/login");
}
