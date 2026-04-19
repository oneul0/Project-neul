import { cookies } from "next/headers";
import { NextRequest, NextResponse } from "next/server";

type ProxyOptions = {
  request: NextRequest;
  targetUrl: string;
  method?: "GET" | "POST" | "DELETE" | "PUT" | "PATCH";
  fallbackContentType?: string;
  onError?: (error: unknown) => NextResponse;
};

const FORWARDED_RESPONSE_HEADERS = ["content-type", "cache-control"] as const;

export async function proxyUpstreamRequest({
  request,
  targetUrl,
  method = request.method as ProxyOptions["method"],
  fallbackContentType = "application/json; charset=utf-8",
  onError,
}: ProxyOptions) {
  const cookieStore = await cookies();
  const headers = new Headers({
    cookie: cookieStore.toString(),
  });

  const contentType = request.headers.get("content-type");
  const hasBody = method === "POST" || method === "PUT" || method === "PATCH" || method === "DELETE";
  if (hasBody && contentType) {
    headers.set("content-type", contentType);
  }

  try {
    const upstream = await fetch(targetUrl, {
      method,
      headers,
      body: hasBody ? await request.text() : undefined,
      cache: "no-store",
    });

    const responseHeaders = new Headers();
    for (const headerName of FORWARDED_RESPONSE_HEADERS) {
      const value = upstream.headers.get(headerName);
      if (value) {
        responseHeaders.set(headerName, value);
      }
    }
    if (!responseHeaders.has("content-type")) {
      responseHeaders.set("content-type", fallbackContentType);
    }

    const response = new NextResponse(upstream.body, {
      status: upstream.status,
      headers: responseHeaders,
    });

    const setCookies =
      typeof upstream.headers.getSetCookie === "function"
        ? upstream.headers.getSetCookie()
        : upstream.headers.get("set-cookie")
          ? [upstream.headers.get("set-cookie") as string]
          : [];
    for (const cookie of setCookies) {
      response.headers.append("set-cookie", cookie);
    }

    return response;
  } catch (error) {
    return onError
      ? onError(error)
      : NextResponse.json(
          {
            error: "upstream_unreachable",
            message: error instanceof Error ? error.message : "업스트림 API 호출에 실패했습니다.",
          },
          { status: 502 },
        );
  }
}
