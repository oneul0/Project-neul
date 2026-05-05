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
        <div className="w-72 rounded-[22px] border border-white/[0.1] bg-[#1A1A1C] shadow-[0_24px_60px_rgba(0,0,0,0.5)]">
          <div className="flex items-center justify-between gap-3 border-b border-white/[0.06] px-4 py-3">
            <div className="flex items-center gap-2 text-xs font-black uppercase tracking-[0.18em] text-[#00FFA3]">
              <FlaskConical className="h-3.5 w-3.5" />
              Dev Seed
            </div>
            <button
              onClick={() => setOpen(false)}
              className="text-xs font-black text-white/40 hover:text-white"
            >
              닫기
            </button>
          </div>

          <div className="space-y-2 p-3">
            <p className="px-1 text-[11px] text-white/40">
              실방송 없이 테스트 데이터를 Redis에 직접 주입합니다.
            </p>

            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => void call("donations", "POST")}
                disabled={!!loading}
                className="rounded-2xl bg-[#00FFA3]/90 py-2.5 text-xs font-black text-[#0D0D0E] transition hover:bg-[#00FFA3] disabled:opacity-50"
              >
                {loading === "donations" ? "주입 중..." : "도네이션 10개"}
              </button>
              <button
                onClick={() => void call("votes", "POST")}
                disabled={!!loading}
                className="rounded-2xl bg-sky-500/80 py-2.5 text-xs font-black text-white transition hover:bg-sky-500 disabled:opacity-50"
              >
                {loading === "votes" ? "주입 중..." : "투표 30명"}
              </button>
            </div>

            <button
              onClick={() => void call("roulette-donations", "POST")}
              disabled={!!loading}
              className="w-full rounded-2xl bg-orange-500/80 py-2.5 text-xs font-black text-white transition hover:bg-orange-500 disabled:opacity-50"
            >
              {loading === "roulette-donations" ? "주입 중..." : "룰렛 도네이션 20개"}
            </button>

            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => void call("status", "GET")}
                disabled={!!loading}
                className="rounded-2xl border border-white/[0.08] bg-[#242426] py-2.5 text-xs font-black text-white/70 transition hover:bg-white/[0.08] disabled:opacity-50"
              >
                {loading === "status" ? "확인 중..." : "현재 상태"}
              </button>
              <button
                onClick={() => void call("clear", "DELETE")}
                disabled={!!loading}
                className="rounded-2xl border border-rose-500/25 bg-rose-500/10 py-2.5 text-xs font-black text-rose-400 transition hover:bg-rose-500/20 disabled:opacity-50"
              >
                {loading === "clear" ? "초기화 중..." : "전체 초기화"}
              </button>
            </div>

            {result ? (
              <div
                className={`rounded-2xl border px-3 py-2 text-xs font-semibold ${
                  result.tone === "good"
                    ? "border-[#00FFA3]/20 bg-[#00FFA3]/10 text-[#00FFA3]"
                    : "border-amber-500/25 bg-amber-500/10 text-amber-400"
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
          className="flex items-center gap-2 rounded-full border border-[#00FFA3]/25 bg-[#1A1A1C] px-4 py-2.5 text-xs font-black text-[#00FFA3] shadow-[0_8px_24px_rgba(0,0,0,0.4)] transition hover:bg-[#00FFA3]/10"
        >
          <FlaskConical className="h-3.5 w-3.5" />
          Dev Seed
        </button>
      )}
    </div>
  );
}
