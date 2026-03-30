"use client";

import { useState } from "react";
import { Play, List, Clock, Zap, Download, Search, Film } from "lucide-react";

interface VodHighlight {
    id: number;
    videoNo: string;
    startSeconds: number;
    endSeconds: number;
    highlightScore: number;
    category: string;
    description: string;
    topMessage: string;
}

export default function VodHighlightBoard() {
    const [videoNo, setVideoNo] = useState("");
    const [highlights, setHighlights] = useState<VodHighlight[]>([]);
    const [isLoading, setIsLoading] = useState(false);

    const fetchHighlights = async () => {
        if (!videoNo) return;
        setIsLoading(true);
        try {
            const res = await fetch(`http://localhost:8083/api/v1/vod/${videoNo}/highlights`);
            if (!res.ok) {
                console.error("API error:", res.status);
                setHighlights([]);
                return;
            }
            const data = await res.json();
            if (Array.isArray(data)) {
                setHighlights(data);
            } else {
                console.error("Data is not an array:", data);
                setHighlights([]);
            }
        } catch (err) {
            console.error("Failed to fetch highlights:", err);
            setHighlights([]);
        } finally {
            setIsLoading(false);
        }
    };

    const handleAnalyze = async () => {
        if (!videoNo) return;
        try {
            const res = await fetch(`http://localhost:8083/api/v1/vod/${videoNo}/analyze`, { method: 'POST' });
            const msg = await res.text();
            alert("VOD 분석 요청이 전송되었습니다: " + msg + "\n전수 조사는 수 분이 소요될 수 있습니다.");
        } catch (err) {
            console.error("Failed to trigger analysis:", err);
            alert("분석 요청 실패: " + err);
        }
    };

    const formatTime = (seconds: number) => {
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = seconds % 60;
        return `${h > 0 ? h + ':' : ''}${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
    };

    return (
        <div className="glass-panel p-6 rounded-3xl border-slate-700/50 flex flex-col h-full bg-slate-900/20 backdrop-blur-xl">
            {/* Header Area */}
            <div className="flex flex-col xl:flex-row xl:items-center justify-between gap-4 mb-6 pb-6 border-b border-slate-800/50">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-xl bg-indigo-500/20 flex items-center justify-center">
                        <Film className="w-5 h-5 text-indigo-400" />
                    </div>
                    <div>
                        <h3 className="font-bold text-slate-100 text-lg leading-tight">VOD 하이라이트 인덱스</h3>
                        <p className="text-[10px] text-slate-500 font-bold uppercase tracking-widest mt-0.5">Automated Content Indexing</p>
                    </div>
                </div>

                <div className="flex flex-wrap items-center gap-3">
                    <div className="relative group">
                        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-500 group-focus-within:text-indigo-400 transition-colors" />
                        <input 
                            type="text" 
                            placeholder="Video ID (ex. 34125)" 
                            className="bg-slate-900/50 border border-slate-700 hover:border-slate-600 focus:border-indigo-500/50 rounded-xl pl-10 pr-4 py-2 text-white text-sm focus:outline-none focus:ring-4 focus:ring-indigo-500/10 transition-all w-[180px]"
                            value={videoNo}
                            onChange={(e) => setVideoNo(e.target.value)}
                        />
                    </div>
                    <button 
                        onClick={fetchHighlights} 
                        className="px-5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl font-bold text-sm transition-all shadow-lg shadow-indigo-600/20 active:scale-95"
                    >
                        조회
                    </button>
                    <button 
                        onClick={handleAnalyze} 
                        className="px-5 py-2 bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 rounded-xl font-bold text-sm transition-all active:scale-95"
                    >
                        새로 분석
                    </button>
                </div>
            </div>

            {/* List Area */}
            <div className="flex-1 overflow-y-auto space-y-3 pr-2 scrollbar-thin scrollbar-thumb-slate-700 scrollbar-track-transparent">
                {Array.isArray(highlights) && highlights.map((h) => (
                    <div key={h.id} className="group p-3 bg-slate-800/30 hover:bg-slate-800/50 rounded-2xl border border-slate-800 hover:border-slate-700/50 transition-all duration-300">
                        <div className="flex items-center gap-4">
                            {/* Fake Thumbnail */}
                            <div className="w-32 h-20 flex-shrink-0 bg-slate-900 rounded-xl flex items-center justify-center relative overflow-hidden ring-1 ring-white/5 group-hover:ring-indigo-500/30 transition-all">
                                 <Play className="w-8 h-8 text-white/20 group-hover:text-indigo-500/40 transition-all group-hover:scale-110" />
                                 <div className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-black/80 to-transparent" />
                                 <div className="absolute bottom-1.5 right-1.5 px-1.5 py-0.5 bg-black/80 rounded text-[9px] text-white font-mono font-bold tracking-tighter">
                                     {formatTime(h.startSeconds)}
                                 </div>
                            </div>

                            <div className="flex-1 min-w-0 space-y-1.5">
                                <div className="flex items-center gap-2">
                                    <span className={`px-2 py-0.5 rounded-md text-[9px] font-black tracking-wider uppercase ${
                                        h.category === 'LAUGH' ? 'bg-amber-500/10 text-amber-500 ring-1 ring-amber-500/20' : 
                                        h.category === 'WONDER' ? 'bg-purple-500/10 text-purple-500 ring-1 ring-purple-500/20' :
                                        'bg-indigo-500/10 text-indigo-500 ring-1 ring-indigo-500/20'
                                    }`}>
                                        {h.category}
                                    </span>
                                    <div className="flex items-center gap-1 text-[10px] text-slate-500 font-bold">
                                        <Zap className="w-3 h-3 text-amber-500" />
                                        <span>{h.highlightScore.toFixed(1)}</span>
                                    </div>
                                </div>
                                <h4 className="text-[13px] font-bold text-slate-200 truncate group-hover:text-white transition-colors">
                                    {h.description}
                                </h4>
                                <p className="text-[11px] text-slate-500 italic truncate italic">
                                    &ldquo;{h.topMessage}&rdquo;
                                </p>
                            </div>

                            <div className="flex flex-col gap-2">
                                <button 
                                    onClick={() => window.open(`https://chzzk.naver.com/video/${videoNo}?t=${h.startSeconds}`, '_blank')}
                                    className="w-10 h-10 rounded-xl bg-indigo-600/10 flex items-center justify-center text-indigo-400 hover:bg-indigo-600 hover:text-white transition-all shadow-sm"
                                    title="VOD 시청"
                                >
                                    <Play className="w-4 h-4 fill-current" />
                                </button>
                                <button className="w-10 h-10 rounded-xl bg-slate-900/50 flex items-center justify-center text-slate-500 hover:text-white hover:bg-slate-800 transition-all">
                                    <Download className="w-4 h-4" />
                                </button>
                            </div>
                        </div>
                    </div>
                ))}

                {highlights.length === 0 && !isLoading && (
                    <div className="flex-1 flex flex-col items-center justify-center py-24">
                        <div className="w-16 h-16 rounded-full bg-slate-800/50 flex items-center justify-center mb-4">
                            <Clock className="w-8 h-8 text-slate-600" />
                        </div>
                        <h4 className="text-slate-400 font-bold text-sm">표시할 데이터가 없습니다.</h4>
                        <p className="text-slate-600 text-[10px] mt-1 font-bold uppercase tracking-widest">Select a video to view highlights</p>
                    </div>
                )}

                {isLoading && (
                    <div className="flex-1 flex flex-col items-center justify-center py-24 animate-pulse">
                        <div className="w-12 h-12 rounded-full border-4 border-indigo-500/20 border-t-indigo-500 animate-spin mb-4" />
                        <p className="text-indigo-400/50 text-[10px] font-bold uppercase tracking-widest">Fetching indexed data...</p>
                    </div>
                )}
            </div>
        </div>
    );
}
