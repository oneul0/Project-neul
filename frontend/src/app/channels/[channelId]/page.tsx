"use client";

import { useEffect, useState, useRef, use } from "react";
import { 
    Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, 
    ResponsiveContainer, Tooltip, AreaChart, Area, XAxis, YAxis 
} from "recharts";
import { 
    MessageSquare, Heart, AlertCircle, Settings2, Activity, 
    Zap, Flame, Download, Info, Smile, Frown, Target
} from "lucide-react";

interface ChatMessage {
    id: string;
    content: string;
    sender: string;
    emotion: string;
    confidence: number;
    messageTime: string;
}

interface Highlight {
    id: number;
    roomId: string;
    emotionType: string;
    peakScore: number;
    topMessage: string;
    liveImageUrl: string;
    timestamp: string;
}

interface AnalyzedChatMessage {
    messageId: string;
    roomId: string;
    messageType: "CHAT" | "DONATION" | "SUBSCRIPTION";
    content?: string;
    sender?: string;
    emotion?: {
        type: string;
        score: number;
    };
    analyzedAt?: string;
}

const EMOTIONS = ["JOY", "HOPE", "NEUTRAL", "SADNESS", "ANGER", "WONDER", "DISGUST"];

const EMOTION_MAP: Record<string, { color: string; label: string; icon: string }> = {
    JOY: { color: "#fbbf24", label: "기쁨", icon: "😄" },
    HOPE: { color: "#38bdf8", label: "희망", icon: "✨" },
    NEUTRAL: { color: "#94a3b8", label: "중립", icon: "😐" },
    SADNESS: { color: "#818cf8", label: "슬픔", icon: "😢" },
    ANGER: { color: "#f87171", label: "분노", icon: "💢" },
    WONDER: { color: "#c084fc", label: "놀람", icon: "😲" },
    DISGUST: { color: "#fb7185", label: "혐오", icon: "🤮" },
};

export default function ChannelDashboard({ params }: { params: Promise<{ channelId: string }> }) {
    const { channelId } = use(params);

    const [stats, setStats] = useState<Record<string, number>>({ 
        JOY: 0, HOPE: 0, NEUTRAL: 0, SADNESS: 0, ANGER: 0, WONDER: 0, DISGUST: 0, TOTAL_COUNT: 0 
    });
    const [chats, setChats] = useState<ChatMessage[]>([]);
    const [highlights, setHighlights] = useState<Highlight[]>([]);
    const [trendData, setTrendData] = useState<{ time: string; score: number }[]>([]);
    const [isConnected, setIsConnected] = useState(false);

    const chatEndRef = useRef<HTMLDivElement>(null);
    const eventSourceRef = useRef<EventSource | null>(null);

    // Auto-scroll chat
    useEffect(() => {
        chatEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }, [chats]);

    // Handle SSE Connection & Auto-Subscribe
    useEffect(() => {
        if (!channelId) return;

        const subscribeChannel = async () => {
            try {
                await fetch(`http://localhost:8081/api/v1/channels/${channelId}/subscribe`, {
                    method: 'POST'
                });
            } catch (err) {
                console.error("Failed to subscribe:", err);
            }
        };

        subscribeChannel();

        const connectSSE = () => {
            const url = `http://localhost:8083/api/v1/stream/${channelId}`;
            const es = new EventSource(url);
            eventSourceRef.current = es;

            es.onopen = () => setIsConnected(true);

            es.addEventListener("stats_update", (e) => {
                try {
                    const newStats = JSON.parse(e.data);
                    setStats(prev => ({ ...prev, ...newStats }));
                } catch (err) { }
            });

            es.addEventListener("chat_analyzed", (e) => {
                try {
                    const data: AnalyzedChatMessage = JSON.parse(e.data);
                    const emotionType = data.emotion?.type || "NEUTRAL";
                    const newChat: ChatMessage = {
                        id: data.messageId || Math.random().toString(36).substr(2, 9),
                        content: data.content || "",
                        sender: data.sender || "Anonymous",
                        emotion: emotionType,
                        confidence: data.emotion?.score || 0,
                        messageTime: data.analyzedAt || new Date().toISOString(),
                    };
                    setChats((prev) => [...prev, newChat].slice(-100));

                    // Update trend data (moving average-ish)
                    setTrendData(prev => {
                        const now = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                        const score = (emotionType === "JOY" || emotionType === "HOPE") ? 1 : 
                                      (emotionType === "ANGER" || emotionType === "DISGUST") ? -1 : 0;
                        return [...prev, { time: now, score }].slice(-30);
                    });
                } catch (err) { }
            });

            es.addEventListener("highlight_detected", (e) => {
                try {
                    const highlight: Highlight = JSON.parse(e.data);
                    setHighlights(prev => [...prev, highlight].slice(-10));
                } catch (err) { }
            });

            es.onerror = () => {
                setIsConnected(false);
                es.close();
                setTimeout(connectSSE, 5000);
            };
        };

        connectSSE();

        return () => {
            if (eventSourceRef.current) eventSourceRef.current.close();
        };
    }, [channelId]);

    const radarData = EMOTIONS.map(e => ({
        subject: EMOTION_MAP[e].label,
        value: stats[e] || 0,
        fullMark: Math.max(...EMOTIONS.map(em => stats[em] || 1)) + 1,
    }));

    const handleDownload = async (imageUrl: string, timestamp: string) => {
        try {
            const response = await fetch(imageUrl);
            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `highlight_${channelId}_${timestamp.replace(/[:.-]/g, '_')}.jpg`;
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
        } catch (err) {
            console.error("Download failed:", err);
            // Fallback: Open in new tab
            window.open(imageUrl, '_blank');
        }
    };

    return (
        <div className="flex flex-col h-[calc(100vh-120px)] space-y-6">
            {/* Header Section */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 glass-panel p-6 rounded-2xl border-slate-700/50">
                <div className="flex items-center gap-4">
                    <div className="w-12 h-12 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-xl font-bold text-white shadow-lg ring-2 ring-white/10">
                        {channelId.charAt(0).toUpperCase()}
                    </div>
                    <div>
                        <div className="flex items-center gap-2">
                            <h1 className="text-2xl font-bold text-white tracking-tight">Real-time Stream Analysis</h1>
                            <span className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider ${isConnected ? "bg-emerald-500/20 text-emerald-400" : "bg-red-500/20 text-red-400 animate-pulse"}`}>
                                <span className={`w-1.5 h-1.5 rounded-full ${isConnected ? "bg-emerald-500" : "bg-red-500"}`} />
                                {isConnected ? "Live Analyzing" : "Connecting"}
                            </span>
                        </div>
                        <p className="text-slate-400 text-sm flex items-center gap-1.5 mt-0.5">
                            <Activity className="w-4 h-4 text-primary" />
                            실시간 감정 데이터 수동 스냅샷 지원 중... (ID: {channelId})
                        </p>
                    </div>
                </div>

                <div className="flex items-center gap-6">
                    <div className="hidden lg:flex gap-4">
                         <div className="flex flex-col items-center p-2 rounded-xl bg-slate-800/40 border border-slate-700/30">
                            <span className="text-[10px] text-slate-500 uppercase font-black">Speed</span>
                            <span className="text-sm font-mono font-bold text-emerald-400">2.0s / B</span>
                         </div>
                    </div>
                    <div className="flex flex-col items-end">
                        <span className="text-xs text-slate-500 uppercase font-bold tracking-widest">Total Volume</span>
                        <span className="text-2xl font-mono font-bold text-white">{stats.TOTAL_COUNT.toLocaleString()}</span>
                    </div>
                    <div className="h-10 w-px bg-slate-700 mx-1 hidden md:block" />
                    <button className="p-2.5 rounded-xl bg-slate-800/80 text-slate-400 hover:text-white hover:bg-slate-700 transition-all border border-slate-700/50">
                        <Settings2 className="w-5 h-5" />
                    </button>
                </div>
            </div>

            {/* Main Content Grid */}
            <div className="flex-1 grid grid-cols-1 lg:grid-cols-12 gap-6 min-h-0">
                {/* Left Column: Analytics */}
                <div className="lg:col-span-4 flex flex-col gap-6 min-h-0">
                    {/* Emotion Radar Chart */}
                    <div className="flex-1 glass-panel p-6 rounded-3xl flex flex-col relative overflow-hidden group min-h-[350px]">
                        <div className="flex items-center justify-between mb-4">
                            <div className="flex items-center gap-2">
                                <Activity className="w-5 h-5 text-indigo-400" />
                                <h3 className="font-bold text-slate-200">Emotion Profile</h3>
                            </div>
                            <Info className="w-4 h-4 text-slate-500 cursor-help" />
                        </div>
                        
                        <div className="flex-1 flex items-center justify-center">
                            <ResponsiveContainer width="100%" height="100%">
                                <RadarChart cx="50%" cy="50%" outerRadius="80%" data={radarData}>
                                    <PolarGrid stroke="#334155" />
                                    <PolarAngleAxis dataKey="subject" tick={{ fill: '#94a3b8', fontSize: 10 }} />
                                    <PolarRadiusAxis angle={30} domain={[0, 'auto']} tick={false} axisLine={false} />
                                    <Radar
                                        name="Emotion"
                                        dataKey="value"
                                        stroke="#6366f1"
                                        fill="#6366f1"
                                        fillOpacity={0.6}
                                        animationDuration={500}
                                    />
                                </RadarChart>
                            </ResponsiveContainer>
                        </div>

                        {/* Summary Legend */}
                        <div className="grid grid-cols-4 gap-2 mt-4">
                            {radarData.map((d, i) => (
                                <div key={i} className="flex flex-col items-center">
                                    <span className="text-[8px] text-slate-500 font-bold uppercase">{d.subject}</span>
                                    <span className="text-xs font-mono font-bold text-slate-300">{d.value}</span>
                                </div>
                            ))}
                        </div>
                    </div>

                    {/* Sentiment Pulse Trend */}
                    <div className="h-[180px] glass-panel p-4 rounded-3xl flex flex-col">
                        <div className="flex items-center gap-2 mb-2">
                            <Zap className="w-4 h-4 text-amber-400" />
                            <h3 className="text-xs font-bold text-slate-300 uppercase tracking-wider">Sentiment Pulse (Real-time)</h3>
                        </div>
                        <div className="flex-1">
                            <ResponsiveContainer width="100%" height="100%">
                                <AreaChart data={trendData}>
                                    <defs>
                                        <linearGradient id="colorPulse" x1="0" y1="0" x2="0" y2="1">
                                            <stop offset="5%" stopColor="#6366f1" stopOpacity={0.3}/>
                                            <stop offset="95%" stopColor="#6366f1" stopOpacity={0}/>
                                        </linearGradient>
                                    </defs>
                                    <Area type="monotone" dataKey="score" stroke="#6366f1" fillOpacity={1} fill="url(#colorPulse)" isAnimationActive={false} />
                                    <YAxis hide domain={[-1, 1]} />
                                    <XAxis hide dataKey="time" />
                                </AreaChart>
                            </ResponsiveContainer>
                        </div>
                    </div>
                </div>

                {/* Right Column: Chat & Highlights */}
                <div className="lg:col-span-8 flex flex-col gap-6 min-h-0">
                    {/* Chat Feed */}
                    <div className="flex-1 flex flex-col glass-panel rounded-3xl border-slate-700/50 overflow-hidden min-h-0">
                        <div className="p-4 border-b border-slate-700/50 flex items-center justify-between bg-slate-800/30">
                            <div className="flex items-center gap-2">
                                <MessageSquare className="w-5 h-5 text-primary" />
                                <h3 className="font-bold text-slate-200">Analysis Feed (2.0s Refresh)</h3>
                            </div>
                            <div className="flex items-center gap-3">
                                <span className="flex items-center gap-1 text-[10px] font-bold text-slate-500">
                                    <Flame className="w-3 h-3 text-rose-500" /> HOT
                                </span>
                            </div>
                        </div>

                        <div className="flex-1 overflow-y-auto p-4 space-y-2 custom-scrollbar" id="chat-container">
                            {chats.map((chat) => (
                                <div key={chat.id} className="group relative flex gap-3 p-2 rounded-xl transition-all hover:bg-slate-800/40 border border-transparent hover:border-slate-700/30">
                                    <div className="flex-shrink-0 w-1 pt-1.5 self-stretch rounded-full" style={{ backgroundColor: EMOTION_MAP[chat.emotion]?.color }} />
                                    <div className="flex-1 min-w-0">
                                        <div className="flex items-center gap-2 mb-0.5">
                                            <span className="font-bold text-xs text-slate-100 truncate">{chat.sender}</span>
                                            <span className="text-[9px] font-black uppercase tracking-widest opacity-60" style={{ color: EMOTION_MAP[chat.emotion]?.color }}>
                                                {EMOTION_MAP[chat.emotion]?.icon} {chat.emotion} • {Math.round(chat.confidence * 100)}%
                                            </span>
                                            <span className="ml-auto text-[9px] text-slate-600 font-mono">
                                                {new Date(chat.messageTime).toLocaleTimeString([], { hour12: false })}
                                            </span>
                                        </div>
                                        <p className="text-sm text-slate-300 leading-snug break-words">{chat.content}</p>
                                    </div>
                                </div>
                            ))}
                            <div ref={chatEndRef} />
                        </div>
                    </div>

                    {/* Highlights Timeline */}
                    <div className="h-[140px] glass-panel p-4 rounded-3xl border-slate-700/50 flex flex-col">
                        <div className="flex items-center justify-between mb-3">
                            <div className="flex items-center gap-2">
                                <Target className="w-4 h-4 text-rose-500" />
                                <h3 className="text-xs font-bold text-slate-300 uppercase tracking-wider">Highlight Timeline</h3>
                            </div>
                            <span className="text-[10px] text-slate-500 font-bold">{highlights.length} Moments Found</span>
                        </div>
                        
                        <div className="flex-1 relative flex items-center px-4">
                            <div className="absolute left-4 right-4 h-1 bg-slate-800 rounded-full" />
                            <div className="relative w-full flex justify-start items-center gap-2 overflow-x-auto no-scrollbar py-4">
                                {highlights.map((h, i) => (
                                    <div key={i} className="group relative flex-shrink-0">
                                        <div 
                                            className="w-8 h-8 rounded-full border-2 border-slate-900 shadow-xl flex items-center justify-center cursor-pointer transition-transform hover:scale-125 hover:z-20 ring-2 ring-white/10"
                                            style={{ backgroundColor: EMOTION_MAP[h.emotionType]?.color }}
                                        >
                                            <span className="text-xs">{EMOTION_MAP[h.emotionType]?.icon}</span>
                                        </div>
                                        
                                        {/* Tooltip */}
                                        <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-3 w-48 p-3 glass-panel rounded-xl opacity-0 group-hover:opacity-100 transition-all pointer-events-none group-hover:pointer-events-auto z-50 shadow-2xl scale-90 group-hover:scale-100">
                                            <div className="flex items-center justify-between mb-2">
                                                <span className="text-[10px] font-black uppercase text-white tracking-widest">{h.emotionType} SPIKE</span>
                                                <button 
                                                    onClick={() => handleDownload(h.liveImageUrl, h.timestamp)}
                                                    className="p-1.5 rounded-md bg-white/10 hover:bg-white/20 text-white transition-colors"
                                                    title="Save Image"
                                                >
                                                    <Download className="w-3 h-3" />
                                                </button>
                                            </div>
                                            <p className="text-[10px] text-slate-300 italic line-clamp-2 mb-1">"{h.topMessage}"</p>
                                            <span className="text-[9px] text-slate-500 font-mono">{new Date(h.timestamp).toLocaleTimeString()}</span>
                                        </div>
                                    </div>
                                ))}
                                {highlights.length === 0 && (
                                    <div className="w-full text-center text-[10px] text-slate-600 font-bold uppercase tracking-widest">Wating for emotional spikes...</div>
                                )}
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
