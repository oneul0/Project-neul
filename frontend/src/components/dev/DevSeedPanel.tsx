"use client";

import { useState } from "react";
import { FlaskConical } from "lucide-react";

interface Props {
  channelId: string;
}

interface SeedResult {
  tone: "good" | "warn";
  message: string;
}

type SeedPath = "donations" | "votes" | "roulette-donations" | "status" | "clear";

export default function DevSeedPanel({ channelId }: Props) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState<string | null>(null);
  const [result, setResult] = useState<SeedResult | null>(null);

  async function call(path: SeedPath, method: "GET" | "POST" | "DELETE") {
    setLoading(path);
    setResult(null);
    const url =
      path === "status" || path === "clear"
        ? `/api/dev/seed/${channelId}`
        : `/api/dev/seed/${channelId}/${path}`;
    try {
      const res = await fetch(url, { method });
      const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
      if (!res.ok) {
        setResult({ tone: "warn", message: (body.message as string) ?? `오류 ${res.status}` });
      } else {
        let msg: string;
        if (path === "donations") {
          msg = `도네이션 ${body.seeded as number}개 주입 완료 (총 풀: ${body.totalPool as number}개)`;
        } else if (path === "votes") {
          msg = `투표 ${body.seeded as number}명 주입 완료`;
        } else if (path === "roulette-donations") {
          const dist = body.distribution as Record<string, number> | undefined;
          const distStr = dist
            ? Object.entries(dist)
                .map(([item, amt]) => `${item}: +${(amt as number).toLocaleString()}원`)
                .join(" | ")
            : "";
          msg = `룰렛 도네이션 ${body.seeded as number}개 주입 완료${distStr ? `\n${distStr}` : ""}`;
        } else if (path === "clear") {
          msg = "전체 초기화 완료";
        } else {
          msg = `도네이션 ${body.donationPoolSize as number}개 · 투표 ${body.totalVotes as number}표`;
        }
        setResult({ tone: "good", message: msg });
      }
    } catch {
      setResult({ tone: "warn", message: "네트워크 오류 또는 core-api 미실행" });
    } finally {
      setLoading(null);
    }
  }

  return (
    <div className="fixed bottom-5 right-5 z-50">
      {open ? (
        <div className="w-72 rounded-[24px] border border-indigo-200 bg-white shadow-[0_24px_60px_rgba(99,102,241,0.15)]">
          <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
            <div className="flex items-center gap-2 text-xs font-black uppercase tracking-[0.18em] text-indigo-600">
              <FlaskConical className="h-3.5 w-3.5" />
              Dev Seed
            </div>
            <button
              onClick={() => setOpen(false)}
              className="text-xs font-black text-slate-400 hover:text-slate-700"
            >
              닫기
            </button>
          </div>

          <div className="space-y-2 p-3">
            <p className="px-1 text-[11px] text-slate-500">
              실방송 없이 테스트 데이터를 Redis에 직접 주입합니다.
            </p>

            {/* 투표·도네이션 */}
            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => void call("donations", "POST")}
                disabled={!!loading}
                className="rounded-2xl bg-violet-500 py-2.5 text-xs font-black text-white transition hover:bg-violet-400 disabled:opacity-50"
              >
                {loading === "donations" ? "주입 중..." : "도네이션 10개"}
              </button>
              <button
                onClick={() => void call("votes", "POST")}
                disabled={!!loading}
                className="rounded-2xl bg-emerald-500 py-2.5 text-xs font-black text-white transition hover:bg-emerald-400 disabled:opacity-50"
              >
                {loading === "votes" ? "주입 중..." : "투표 30명"}
              </button>
            </div>

            {/* 룰렛 도네이션 */}
            <button
              onClick={() => void call("roulette-donations", "POST")}
              disabled={!!loading}
              className="w-full rounded-2xl bg-orange-500 py-2.5 text-xs font-black text-white transition hover:bg-orange-400 disabled:opacity-50"
            >
              {loading === "roulette-donations" ? "주입 중..." : "룰렛 도네이션 20개"}
            </button>

            {/* 상태·초기화 */}
            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => void call("status", "GET")}
                disabled={!!loading}
                className="rounded-2xl border border-slate-200 bg-slate-50 py-2.5 text-xs font-black text-slate-700 transition hover:bg-slate-100 disabled:opacity-50"
              >
                {loading === "status" ? "확인 중..." : "현재 상태"}
              </button>
              <button
                onClick={() => void call("clear", "DELETE")}
                disabled={!!loading}
                className="rounded-2xl border border-rose-200 bg-rose-50 py-2.5 text-xs font-black text-rose-700 transition hover:bg-rose-100 disabled:opacity-50"
              >
                {loading === "clear" ? "초기화 중..." : "전체 초기화"}
              </button>
            </div>

            {result ? (
              <div
                className={`rounded-2xl border px-3 py-2 text-xs font-semibold ${
                  result.tone === "good"
                    ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                    : "border-amber-200 bg-amber-50 text-amber-700"
                }`}
              >
                {result.message.split("\n").map((line, i) => (
                  <span key={i} className={i > 0 ? "mt-1 block text-[10px] opacity-80" : "block"}>
                    {line}
                  </span>
                ))}
              </div>
            ) : null}
          </div>
        </div>
      ) : (
        <button
          onClick={() => setOpen(true)}
          className="flex items-center gap-2 rounded-full border border-indigo-200 bg-white px-4 py-2.5 text-xs font-black text-indigo-600 shadow-lg transition hover:bg-indigo-50"
        >
          <FlaskConical className="h-3.5 w-3.5" />
          Dev Seed
        </button>
      )}
    </div>
  );
}
