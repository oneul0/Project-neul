"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { Users, AlertCircle, RefreshCw, TrendingUp, Sparkles, AlertTriangle } from "lucide-react";

interface SentimentStats {
  TOTAL_COUNT: number;
  POSITIVE?: number;
  NEGATIVE?: number;
  NEUTRAL?: number;
}

interface LiveChannel {
  channelId: string;
  channelName: string;
  liveTitle: string;
  liveImageUrl: string;
  concurrentUserCount: number;
  openDate: string;
  categoryType: string;
  liveCategory: string;
  liveCategoryValue: string;
  sentiment: SentimentStats;
}

export default function Home() {
  const [lives, setLives] = useState<LiveChannel[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<"viewers" | "positive" | "negative">("viewers");

  const fetchLives = async () => {
    try {
      setLoading(true);
      // Calls the core-api via a proxy or directly if CORS is enabled
      // Assuming Next.js runs on 3000 and core-api on 8083. 
      // For cross-origin in dev, we should hit the absolute URL or use Next.js rewrites.
      const res = await fetch("http://localhost:8083/api/v1/lives?size=20");
      if (!res.ok) throw new Error("Failed to fetch live channels");

      const data = await res.json();
      setLives(data.data || []);
      setError(null);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLives();
    const interval = setInterval(fetchLives, 30000); // refresh every 30s
    return () => clearInterval(interval);
  }, []);

  const sortedLives = [...lives].sort((a, b) => {
    if (sortBy === "viewers") {
      return b.concurrentUserCount - a.concurrentUserCount;
    }

    // Calculate pos/neg ratio
    const getRatio = (stats: SentimentStats, type: "POSITIVE" | "NEGATIVE") => {
      if (!stats || stats.TOTAL_COUNT === 0) return 0;
      const count = type === "POSITIVE" ? (stats.POSITIVE || 0) : (stats.NEGATIVE || 0);
      return count / stats.TOTAL_COUNT;
    };

    if (sortBy === "positive") {
      return getRatio(b.sentiment, "POSITIVE") - getRatio(a.sentiment, "POSITIVE");
    }
    if (sortBy === "negative") {
      return getRatio(b.sentiment, "NEGATIVE") - getRatio(a.sentiment, "NEGATIVE");
    }
    return 0;
  });

  return (
    <div className="space-y-8 animate-in fade-in duration-500">
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-white mb-2">실시간 방송 탐색</h1>
          <p className="text-slate-400">시청자들의 감정 반응을 기반으로 새로운 스트리머를 발견하세요.</p>
        </div>

        <div className="flex bg-slate-800/50 p-1 rounded-xl glass border border-slate-700/50">
          <button
            onClick={() => setSortBy("viewers")}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${sortBy === "viewers" ? "bg-slate-700 text-white shadow-md shadow-black/20" : "text-slate-400 hover:text-slate-200 hover:bg-slate-800/80"}`}
          >
            <div className="flex items-center gap-2"><Users className="w-4 h-4" /> 시청자순</div>
          </button>
          <button
            onClick={() => setSortBy("positive")}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${sortBy === "positive" ? "bg-emerald-500/20 text-emerald-400 shadow-md shadow-emerald-900/20" : "text-slate-400 hover:text-emerald-400/70 hover:bg-slate-800/80"}`}
          >
            <div className="flex items-center gap-2"><Sparkles className="w-4 h-4" /> 긍정적</div>
          </button>
          <button
            onClick={() => setSortBy("negative")}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${sortBy === "negative" ? "bg-red-500/20 text-red-400 shadow-md shadow-red-900/20" : "text-slate-400 hover:text-red-400/70 hover:bg-slate-800/80"}`}
          >
            <div className="flex items-center gap-2"><AlertTriangle className="w-4 h-4" /> 혼돈의 도가니</div>
          </button>
        </div>
      </div>

      {error && (
        <div className="glass-panel border-red-500/30 p-4 rounded-xl flex items-center gap-3 text-red-400 bg-red-950/20">
          <AlertCircle className="w-5 h-5 flex-shrink-0" />
          <p>방송 목록을 불러오는 중 오류가 발생했습니다: {error}</p>
          <button onClick={fetchLives} className="ml-auto text-sm bg-red-500/20 px-3 py-1.5 rounded-lg hover:bg-red-500/30 transition">
            다시 시도
          </button>
        </div>
      )}

      {loading && lives.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-slate-400 space-y-4">
          <RefreshCw className="w-8 h-8 animate-spin text-primary" />
          <p>라이브 채널을 불러오고 있습니다...</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
          {sortedLives.map((live) => {
            const total = live.sentiment?.TOTAL_COUNT || 0;
            const posPct = total > 0 ? Math.round(((live.sentiment?.POSITIVE || 0) / total) * 100) : 0;
            const negPct = total > 0 ? Math.round(((live.sentiment?.NEGATIVE || 0) / total) * 100) : 0;
            const hasActivity = total > 0;

            // Generate a thumbnail url replacing {type}
            const thumbUrl = live.liveImageUrl ? live.liveImageUrl.replace("{type}", "480") : "/placeholder.jpg";

            return (
              <Link
                href={`/channels/${live.channelId}`}
                key={live.channelId}
                className="group flex flex-col glass rounded-2xl overflow-hidden hover:border-slate-600 transition-all duration-300 hover:shadow-xl hover:shadow-black/40 hover:-translate-y-1 bg-slate-900/60"
              >
                <div className="relative aspect-video overflow-hidden bg-slate-800">
                  <Image
                    src={thumbUrl}
                    alt={live.liveTitle}
                    fill
                    unoptimized
                    className="object-cover transition-transform duration-500 group-hover:scale-105"
                  />
                  <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent opacity-90" />

                  <div className="absolute top-3 left-3 bg-red-500 text-white text-[10px] font-bold px-2 py-1 rounded-md uppercase tracking-wider shadow-sm flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-white animate-pulse" />
                    LIVE
                  </div>

                  <div className="absolute bottom-3 left-3 right-3 flex justify-between items-end">
                    <div className="flex items-center gap-1.5 bg-black/60 backdrop-blur-md px-2.5 py-1 rounded-lg text-white text-xs font-semibold shadow-sm">
                      <Users className="w-3.5 h-3.5 text-slate-300" />
                      {live.concurrentUserCount.toLocaleString()}
                    </div>

                    {hasActivity && (
                      <div className="flex flex-col gap-1 items-end">
                        <div className="flex gap-1">
                          {posPct > 0 && <span className="bg-emerald-500/90 text-white text-[10px] font-bold px-1.5 py-0.5 rounded shadow-sm">긍정 {posPct}%</span>}
                          {negPct > 0 && <span className="bg-red-500/90 text-white text-[10px] font-bold px-1.5 py-0.5 rounded shadow-sm">부정 {negPct}%</span>}
                        </div>
                      </div>
                    )}
                  </div>
                </div>

                <div className="p-4 flex flex-col flex-1">
                  <h3 className="text-slate-100 font-bold text-base line-clamp-2 leading-tight mb-2 group-hover:text-primary transition-colors">
                    {live.liveTitle}
                  </h3>
                  <div className="mt-auto flex items-center gap-2">
                    <div className="w-6 h-6 rounded-full bg-gradient-to-br from-indigo-500 to-purple-500 flex-shrink-0 flex items-center justify-center text-[10px] font-bold text-white shadow-inner">
                      {live.channelName.charAt(0)}
                    </div>
                    <span className="text-slate-400 text-sm font-medium truncate flex-1">{live.channelName}</span>
                    <span className="text-xs text-slate-500 bg-slate-800 px-2 py-1 rounded-md">{live.liveCategoryValue}</span>
                  </div>
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
