"use client";

import { useEffect, useState, useRef, use } from "react";
import { 
    ResponsiveContainer, Tooltip, AreaChart, Area, XAxis, YAxis 
} from "recharts";
import { 
    MessageSquare, Heart, AlertCircle, Settings2, Activity, 
    Zap, Flame, Download, Info, Smile, Frown, Target, Hash
} from "lucide-react";

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
    emotionScores?: Record<string, number>;
    keywords?: string[];
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
    VOTE: { color: "#6366f1", label: "투표", icon: "🗳️" },
};

export default function ChannelDashboard({ params }: { params: Promise<{ channelId: string }> }) {
    const { channelId } = use(params);

    const [stats, setStats] = useState<Record<string, number>>({ 
        JOY: 0, HOPE: 0, NEUTRAL: 0, SADNESS: 0, ANGER: 0, WONDER: 0, DISGUST: 0, TOTAL_COUNT: 0 
    });
    const [highlights, setHighlights] = useState<Highlight[]>([]);
    const [trendData, setTrendData] = useState<{ time: string; score: number }[]>([]);
    const [keywords, setKeywords] = useState<string[]>([]);
    const [isConnected, setIsConnected] = useState(false);
    const [latestVibe, setLatestVibe] = useState<{ emotion: string; content: string } | null>(null);
    const [isSessionActive, setIsSessionActive] = useState(false);
    const [pollResults, setPollResults] = useState<Record<string, number>>({});
    const [voters, setVoters] = useState<Record<string, string>>({});
    const [selectedVoter, setSelectedVoter] = useState<string | null>(null);
    const [voterHistory, setVoterHistory] = useState<AnalyzedChatMessage[]>([]);
    const [pollItems, setPollItems] = useState<string[]>([]); // Phase 24
    const [showPollCreator, setShowPollCreator] = useState(false); // Phase 24
    const [newPollItems, setNewPollItems] = useState<string[]>(["", ""]); // Phase 24

    const eventSourceRef = useRef<EventSource | null>(null);

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
                    
                    const scores = data.emotionScores || { NEUTRAL: 1.0 };
                    const topEmotionEntry = Object.entries(scores).reduce((a, b) => a[1] > b[1] ? a : b);
                    const emotionType = topEmotionEntry[0];
                    const emotionScore = topEmotionEntry[1];
                    
                    if (data.keywords && data.keywords.length > 0) {
                        setKeywords(data.keywords);
                    }

                    if (data.content) {
                        setLatestVibe({ emotion: emotionType, content: data.content });
                    }

                    setTrendData(prev => {
                        const now = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                        const score = (emotionType === "JOY" || emotionType === "HOPE") ? emotionScore : 
                                      (emotionType === "ANGER" || emotionType === "DISGUST") ? -emotionScore : 0;
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

        const fetchInitialState = async () => {
            try {
                const res = await fetch(`http://localhost:8083/api/v1/poll/${channelId}/session`);
                const active = await res.json();
                setIsSessionActive(active);

                const pollRes = await fetch(`http://localhost:8083/api/v1/poll/${channelId}/results`);
                const results = await pollRes.json();
                setPollResults(results);

                const itemsRes = await fetch(`http://localhost:8083/api/v1/poll/${channelId}/items`);
                const items = await itemsRes.json();
                setPollItems(items);
            } catch (err) {}
        };
        fetchInitialState();

        const pollInterval = setInterval(async () => {
            try {
                const res = await fetch(`http://localhost:8083/api/v1/poll/${channelId}/results`);
                const results = await res.json();
                setPollResults(results);

                const voterRes = await fetch(`http://localhost:8083/api/v1/poll/${channelId}/voters`);
                const voterData = await voterRes.json();
                setVoters(voterData);
            } catch (err) {}
        }, 3000);

        return () => {
            if (eventSourceRef.current) eventSourceRef.current.close();
            clearInterval(pollInterval);
        };
    }, [channelId]);

    const radarData = []; // Removed for Phase 24

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
            window.open(imageUrl, '_blank');
        }
    };

    const handleToggleSession = async () => {
        try {
            const nextState = !isSessionActive;
            await fetch(`http://localhost:8083/api/v1/poll/${channelId}/session?active=${nextState}`, {
                method: 'POST'
            });
            setIsSessionActive(nextState);
        } catch (err) {
            console.error("Failed to toggle session:", err);
        }
    };

    const handleClearPoll = async () => {
        if (!confirm("투표를 초기화하시겠습니까?")) return;
        try {
            await fetch(`http://localhost:8083/api/v1/poll/${channelId}`, {
                method: 'DELETE'
            });
            setPollResults({});
            setVoters({});
        } catch (err) {
            console.error("Failed to clear poll:", err);
        }
    };

    const handleVoterClick = async (userId: string) => {
        setSelectedVoter(userId);
        try {
            const res = await fetch(`http://localhost:8083/api/v1/poll/${channelId}/voters/${userId}/history`);
            const history = await res.json();
            setVoterHistory(history);
        } catch (err) {
            console.error("Failed to fetch voter history:", err);
        }
    };

    const handleCreatePoll = async () => {
        const items = newPollItems.filter(i => i.trim() !== "");
        if (items.length < 2) {
            alert("최소 2개 이상의 항목을 입력해 주세요.");
            return;
        }
        try {
            await fetch(`http://localhost:8083/api/v1/poll/${channelId}/items`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(items)
            });
            setPollItems(items);
            setShowPollCreator(false);
            // Also reset results when a new poll is created
            await fetch(`http://localhost:8083/api/v1/poll/${channelId}`, { method: 'DELETE' });
            setPollResults({});
            setVoters({});
        } catch (err) {
            console.error("Failed to create poll:", err);
        }
    };

    return (
        <div className="flex flex-col h-full space-y-6">
            {/* Header Section */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 glass-panel p-6 rounded-2xl border-slate-700/50">
                <div className="flex items-center gap-4">
                    <div className="w-12 h-12 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-xl font-bold text-white shadow-lg ring-2 ring-white/10">
                        {channelId.charAt(0).toUpperCase()}
                    </div>
                    <div>
                        <div className="flex items-center gap-2">
                            <h1 className="text-2xl font-bold text-white tracking-tight">Streamer Dashboard</h1>
                            <span className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider ${isConnected ? "bg-emerald-500/20 text-emerald-400" : "bg-red-500/20 text-red-400 animate-pulse"}`}>
                                <span className={`w-1.5 h-1.5 rounded-full ${isConnected ? "bg-emerald-500" : "bg-red-500"}`} />
                                {isConnected ? "Live Analytics" : "Connecting"}
                            </span>
                        </div>
                        <p className="text-slate-400 text-sm flex items-center gap-1.5 mt-0.5">
                            <Activity className="w-4 h-4 text-primary" />
                            실시간 민심 분석 중... (ID: {channelId})
                        </p>
                    </div>
                </div>

                <div className="flex items-center gap-6">
                    <button 
                        onClick={handleToggleSession}
                        className={`flex items-center gap-2 px-4 py-2 rounded-xl border transition-all font-bold ${isSessionActive ? "bg-rose-500/20 text-rose-400 border-rose-500/50 hover:bg-rose-500/30" : "bg-emerald-500/20 text-emerald-400 border-emerald-500/50 hover:bg-emerald-500/30"}`}
                    >
                        <Flame className={`w-4 h-4 ${isSessionActive ? "animate-pulse" : ""}`} />
                        {isSessionActive ? "수집 중지 (Live)" : "수집 시작"}
                    </button>
                    <div className="h-10 w-px bg-slate-700 mx-1 hidden md:block" />
                    <div className="flex flex-col items-end">
                        <span className="text-xs text-slate-500 uppercase font-bold tracking-widest">Total Chats</span>
                        <span className="text-2xl font-mono font-bold text-white">{stats.TOTAL_COUNT.toLocaleString()}</span>
                    </div>
                </div>
            </div>

            {/* Main Content Grid */}
            <div className="flex-1 grid grid-cols-1 lg:grid-cols-12 gap-6 min-h-0">
                {/* Left Column: Emotion Analytics Simplified */}
                <div className="lg:col-span-4 flex flex-col gap-6 min-h-0">
                    {/* Min-sim Graph (Expanded) */}
                    <div className="flex-1 glass-panel p-6 rounded-3xl flex flex-col border-slate-700/50">
                        <div className="flex items-center justify-between mb-4">
                            <div className="flex items-center gap-2">
                                <Zap className="w-5 h-5 text-amber-400" />
                                <h3 className="font-bold text-slate-200">실시간 민심 그래프</h3>
                            </div>
                            <span className="text-[10px] font-mono text-slate-500">SENTIMENT PULSE</span>
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
                                    <XAxis dataKey="time" hide />
                                    <YAxis domain={[-1.2, 1.2]} hide />
                                    <Tooltip content={({ active, payload }) => {
                                        if (active && payload && payload.length) {
                                            return (
                                                <div className="bg-slate-900 border border-slate-700 p-2 rounded shadow-xl text-xs text-white">
                                                    {payload[0].value > 0 ? "매우 긍정" : payload[0].value < 0 ? "매우 부정" : "중립"}
                                                </div>
                                            );
                                        }
                                        return null;
                                    }} />
                                    <Area type="monotone" dataKey="score" stroke="#6366f1" strokeWidth={3} fill="url(#colorPulse)" isAnimationActive={false} />
                                </AreaChart>
                            </ResponsiveContainer>
                        </div>
                    </div>

                    {/* Quick Emotion Summary (Moved here, kept simple) */}
                    <div className="grid grid-cols-2 gap-4">
                        <div className="glass-panel p-4 rounded-2xl flex flex-col justify-center items-center">
                            <span className="text-[10px] text-emerald-400 font-bold uppercase">Positive</span>
                            <span className="text-2xl font-mono text-white">{(stats.JOY + stats.HOPE).toLocaleString()}</span>
                        </div>
                        <div className="glass-panel p-4 rounded-2xl flex flex-col justify-center items-center">
                            <span className="text-[10px] text-rose-400 font-bold uppercase">Negative</span>
                            <span className="text-2xl font-mono text-white">{(stats.ANGER + stats.DISGUST).toLocaleString()}</span>
                        </div>
                    </div>
                </div>

                {/* Right Column: Interaction */}
                <div className="lg:col-span-8 flex flex-col gap-6 min-h-0">
                    <div className="flex-1 grid grid-cols-1 md:grid-cols-2 gap-6 min-h-0">
                        {/* Poll Statistics */}
                        <div className="glass-panel p-6 rounded-3xl flex flex-col border-slate-700/50">
                            <div className="flex items-center justify-between mb-6">
                                <div className="flex items-center gap-2">
                                    <Target className="w-5 h-5 text-indigo-400" />
                                    <h3 className="font-bold text-slate-200">실시간 투표 현황</h3>
                                </div>
                                <div className="flex items-center gap-3">
                                    <button onClick={() => setShowPollCreator(true)} className="text-[10px] font-bold text-indigo-400 hover:text-white transition-colors">투표 생성</button>
                                    <button onClick={handleClearPoll} className="text-[10px] font-bold text-slate-500 hover:text-rose-400 transition-colors">초기화</button>
                                </div>
                            </div>
                            <div className="flex-1 overflow-y-auto space-y-4">
                                {pollItems.length > 0 ? pollItems.map((item, index) => {
                                    const option = (index + 1).toString();
                                    const count = pollResults[option] || 0;
                                    const maxCount = Math.max(...Object.values(pollResults), 1);
                                    return (
                                        <div key={option} className="p-4 bg-slate-800/40 rounded-2xl border border-slate-700/30">
                                            <div className="flex items-center justify-between mb-2">
                                                <div className="flex items-center gap-3">
                                                    <span className="px-2 py-0.5 rounded-md bg-indigo-500 text-white text-[10px] font-black">!{option}</span>
                                                    <span className="text-sm font-bold text-slate-200">{item}</span>
                                                </div>
                                                <span className="text-xl font-mono font-bold text-white">{count}표</span>
                                            </div>
                                            <div className="w-full h-2 bg-slate-700 rounded-full overflow-hidden">
                                                <div className="h-full bg-indigo-500 transition-all duration-500" style={{ width: `${(count / maxCount) * 100}%` }} />
                                            </div>
                                        </div>
                                    );
                                }) : (
                                    <div className="flex-1 flex flex-col items-center justify-center py-10">
                                        <Info className="w-8 h-8 text-slate-700 mb-2" />
                                        <p className="text-slate-600 text-xs font-bold uppercase tracking-widest text-center">투표 항목을<br/>생성해 주세요.</p>
                                    </div>
                                )}
                            </div>
                        </div>

                        {/* Voter List */}
                        <div className="glass-panel p-6 rounded-3xl flex flex-col border-slate-700/50">
                            <div className="flex items-center gap-2 mb-4">
                                <MessageSquare className="w-5 h-5 text-emerald-400" />
                                <h3 className="font-bold text-slate-200">투표 참여 명단</h3>
                            </div>
                            <div className="flex-1 overflow-y-auto pr-2">
                                <div className="grid grid-cols-1 gap-2">
                                    {Object.entries(voters).map(([userId, option]) => (
                                        <button 
                                            key={userId} 
                                            onClick={() => handleVoterClick(userId)}
                                            className={`flex items-center justify-between p-3 rounded-xl border transition-all ${selectedVoter === userId ? "bg-indigo-500/20 border-indigo-500/50 shadow-inner" : "bg-slate-800/20 border-slate-700/30 hover:bg-slate-800/40"}`}
                                        >
                                            <span className="text-xs font-medium text-slate-300">User_{userId.slice(0,6)}</span>
                                            <span className="px-2 py-0.5 rounded-md bg-slate-700 text-indigo-300 text-[10px] font-bold">!{option}</span>
                                        </button>
                                    ))}
                                    {Object.keys(voters).length === 0 && (
                                        <p className="text-center text-slate-600 text-[10px] py-10 uppercase tracking-widest">no participants yet</p>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Voter History Sidebar/Panel */}
                    {selectedVoter && (
                        <div className="glass-panel p-6 rounded-3xl border-indigo-500/30 bg-indigo-500/5 flex flex-col min-h-[200px]">
                            <div className="flex items-center justify-between mb-4">
                                <h4 className="font-bold text-slate-200">
                                    <span className="text-indigo-400">User_{selectedVoter.slice(0,6)}</span> 님의 수집 채팅
                                </h4>
                                <button onClick={() => setSelectedVoter(null)} className="text-slate-500 hover:text-white">닫기</button>
                            </div>
                            <div className="flex-1 overflow-y-auto pr-2 space-y-2 max-h-[150px]">
                                {voterHistory.map((msg, i) => (
                                    <div key={i} className="p-3 bg-slate-800/40 rounded-xl border border-slate-700/30 text-xs">
                                        <div className="flex items-center gap-2 mb-1">
                                            <span className="text-[10px] font-black uppercase text-indigo-400">CHAT</span>
                                            <span className="text-[10px] text-slate-500">{new Date(msg.analyzedAt || '').toLocaleTimeString()}</span>
                                        </div>
                                        <p className="text-slate-200">{msg.content}</p>
                                    </div>
                                ))}
                                {voterHistory.length === 0 && <p className="text-center text-slate-600 text-[10px] py-4">기록이 없습니다.</p>}
                            </div>
                        </div>
                    )}
                </div>
            </div>

            {/* Timeline Bar */}
            <div className="h-[120px] glass-panel p-4 rounded-3xl border-slate-700/50 flex flex-col">
                <div className="flex items-center gap-2 mb-3">
                    <Target className="w-4 h-4 text-rose-500" />
                    <h3 className="text-[10px] font-bold text-slate-300 uppercase tracking-wider">Highlight Moments</h3>
                </div>
                <div className="flex-1 flex items-center gap-3 overflow-x-auto no-scrollbar scroll-smooth">
                    {highlights.map((h, i) => (
                        <div key={i} className="flex-shrink-0 group relative">
                            <div 
                                className="w-10 h-10 rounded-full border-2 border-slate-900 shadow-xl flex items-center justify-center cursor-pointer transition-all hover:scale-110"
                                style={{ backgroundColor: EMOTION_MAP[h.emotionType]?.color }}
                            >
                                <span className="text-sm">{EMOTION_MAP[h.emotionType]?.icon}</span>
                            </div>
                            <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-48 p-3 glass-panel rounded-xl opacity-0 group-hover:opacity-100 transition-all pointer-events-none z-50">
                                <p className="text-[10px] text-white font-bold mb-1 italic">"{h.topMessage}"</p>
                                <button 
                                    onClick={() => handleDownload(h.liveImageUrl, h.timestamp)}
                                    className="text-[9px] text-indigo-400 hover:text-white flex items-center gap-1"
                                >
                                    <Download className="w-2 h-2" /> Download Image
                                </button>
                            </div>
                        </div>
                    ))}
                    {highlights.length === 0 && <p className="text-slate-600 text-[10px] font-bold w-full text-center uppercase tracking-widest">Waiting for spikes...</p>}
                </div>
            </div>

            {/* Poll Creator Modal */}
            {showPollCreator && (
                <div className="fixed inset-0 z-[100] flex items-center justify-center bg-slate-950/80 backdrop-blur-sm animate-in fade-in duration-200">
                    <div className="w-full max-w-md glass-panel p-6 rounded-3xl border-slate-700/50 shadow-2xl animate-in zoom-in-95 duration-200">
                        <div className="flex items-center justify-between mb-6">
                            <h3 className="text-xl font-bold text-white">새 투표 생성</h3>
                            <button onClick={() => setShowPollCreator(false)} className="text-slate-500 hover:text-white transition-colors">
                                <AlertCircle className="w-6 h-6 rotate-45" />
                            </button>
                        </div>
                        <div className="space-y-4 mb-8">
                            {newPollItems.map((item, index) => (
                                <div key={index} className="flex gap-2">
                                    <div className="flex items-center justify-center w-10 h-10 rounded-xl bg-slate-800 border border-slate-700 text-indigo-400 font-bold">
                                        {index + 1}
                                    </div>
                                    <input 
                                        type="text"
                                        placeholder={`항목 ${index + 1} 입력...`}
                                        className="flex-1 bg-slate-800 border border-slate-700 rounded-xl px-4 text-white placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-indigo-500/50 transition-all"
                                        value={item}
                                        onChange={(e) => {
                                            const updated = [...newPollItems];
                                            updated[index] = e.target.value;
                                            setNewPollItems(updated);
                                        }}
                                    />
                                    {newPollItems.length > 2 && (
                                        <button 
                                            onClick={() => setNewPollItems(newPollItems.filter((_, i) => i !== index))}
                                            className="p-2 text-rose-500 hover:bg-rose-500/10 rounded-xl transition-all"
                                        >
                                            <AlertCircle className="w-5 h-5" />
                                        </button>
                                    )}
                                </div>
                            ))}
                            {newPollItems.length < 5 && (
                                <button 
                                    onClick={() => setNewPollItems([...newPollItems, ""])}
                                    className="w-full py-3 bg-slate-800/50 border border-dashed border-slate-700 rounded-2xl text-slate-400 hover:text-white hover:bg-slate-800 transition-all font-bold text-sm"
                                >
                                    + 항목 추가
                                </button>
                            )}
                        </div>
                        <div className="flex gap-3">
                            <button 
                                onClick={() => setShowPollCreator(false)}
                                className="flex-1 py-3 px-6 rounded-2xl bg-slate-800 text-slate-300 font-bold hover:bg-slate-700 transition-all"
                            >
                                취소
                            </button>
                            <button 
                                onClick={handleCreatePoll}
                                className="flex-2 py-3 px-8 rounded-2xl bg-indigo-600 text-white font-bold hover:bg-indigo-500 shadow-lg shadow-indigo-600/20 transition-all active:scale-95"
                            >
                                투표 시작하기
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
