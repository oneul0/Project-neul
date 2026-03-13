"use client";

import { useEffect, useState, useRef, use } from "react";
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from "recharts";
import { MessageSquare, Heart, AlertCircle, Settings2, Activity } from "lucide-react";

interface ChatMessage {
    id: string; // unique frontend id
    content: string;
    sender: string;
    emotion: "POSITIVE" | "NEGATIVE" | "NEUTRAL";
    confidence: number;
    messageTime: string;
}

interface AnalyzedChatMessage {
    messageId: string;
    roomId: string;
    messageType: "CHAT" | "DONATION" | "SUBSCRIPTION";
    content?: string;
    sender?: string;
    emotion?: {
        type: "POSITIVE" | "NEGATIVE" | "NEUTRAL";
        score: number;
    };
    analyzedAt?: string;
}

const EMOTION_COLORS = {
    POSITIVE: "#10b981", // emerald-500
    NEGATIVE: "#ef4444", // red-500
    NEUTRAL: "#94a3b8"   // slate-400
};

export default function ChannelDashboard({ params }: { params: Promise<{ channelId: string }> }) {
    const { channelId } = use(params);

    const [stats, setStats] = useState({ POSITIVE: 0, NEGATIVE: 0, NEUTRAL: 0, TOTAL_COUNT: 0 });
    const [chats, setChats] = useState<ChatMessage[]>([]);
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

        // Auto-subscribe to channel (start collection)
        const subscribeChannel = async () => {
            try {
                await fetch(`http://localhost:8081/api/v1/channels/${channelId}/subscribe`, {
                    method: 'POST'
                });
                console.log(`Subscribed to channel: ${channelId}`);
            } catch (err) {
                console.error("Failed to subscribe to channel:", err);
            }
        };

        subscribeChannel();

        const connectSSE = () => {
            const url = `http://localhost:8083/api/v1/stream/${channelId}`;
            const es = new EventSource(url);
            eventSourceRef.current = es;

            es.onopen = () => {
                setIsConnected(true);
            };

            es.addEventListener("ping", () => {
                // Keep-alive received
            });

            es.addEventListener("stats_update", (e) => {
                try {
                    const newStats = JSON.parse(e.data);
                    setStats(newStats);
                } catch (err) { }
            });

            es.addEventListener("chat_analyzed", (e) => {
                try {
                    const data: AnalyzedChatMessage = JSON.parse(e.data);
                    const newChat: ChatMessage = {
                        id: data.messageId || Math.random().toString(36).substr(2, 9),
                        content: data.content || "",
                        sender: data.sender || "Anonymous",
                        emotion: data.emotion?.type || "NEUTRAL",
                        confidence: data.emotion?.score !== undefined ? Math.abs(data.emotion.score) : 0,
                        messageTime: data.analyzedAt || new Date().toISOString(),
                    };
                    setChats((prev) => [...prev, newChat].slice(-100));
                } catch (err) { 
                    console.error("Error parsing chat_analyzed:", err);
                }
            });

            es.onerror = () => {
                setIsConnected(false);
                es.close();
                setTimeout(connectSSE, 5000);
            };
        };

        connectSSE();

        return () => {
            if (eventSourceRef.current) {
                eventSourceRef.current.close();
            }
        };
    }, [channelId]);

    const displayPieData = [
        { name: "Positive", value: stats.POSITIVE, color: EMOTION_COLORS.POSITIVE },
        { name: "Negative", value: stats.NEGATIVE, color: EMOTION_COLORS.NEGATIVE },
        { name: "Neutral", value: stats.NEUTRAL, color: EMOTION_COLORS.NEUTRAL },
    ].filter(d => d.value > 0);

    if (displayPieData.length === 0) {
        displayPieData.push({ name: "No Data", value: 1, color: "#334155" });
    }

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
                            <h1 className="text-2xl font-bold text-white tracking-tight">Channel Analysis</h1>
                            <span className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider ${isConnected ? "bg-emerald-500/20 text-emerald-400" : "bg-red-500/20 text-red-400 animate-pulse"}`}>
                                <span className={`w-1.5 h-1.5 rounded-full ${isConnected ? "bg-emerald-500" : "bg-red-500"}`} />
                                {isConnected ? "Live Tracking" : "Connecting"}
                            </span>
                        </div>
                        <p className="text-slate-400 text-sm flex items-center gap-1.5 mt-0.5">
                            <Activity className="w-4 h-4 text-primary" />
                            실시간 시청자 감정 데이터 수집 중... (ID: {channelId})
                        </p>
                    </div>
                </div>

                <div className="flex items-center gap-3">
                    <div className="flex flex-col items-end">
                        <span className="text-xs text-slate-500 uppercase font-bold tracking-widest">Total Messages</span>
                        <span className="text-2xl font-mono font-bold text-white">{stats.TOTAL_COUNT.toLocaleString()}</span>
                    </div>
                    <div className="h-10 w-px bg-slate-700 mx-2 hidden md:block" />
                    <button className="p-2.5 rounded-xl bg-slate-800/80 text-slate-400 hover:text-white hover:bg-slate-700 transition-all border border-slate-700/50">
                        <Settings2 className="w-5 h-5" />
                    </button>
                </div>
            </div>

            {/* Main Content: Stats & Chat (Filling the screen) */}
            <div className="flex-1 grid grid-cols-1 lg:grid-cols-12 gap-6 min-h-0">
                {/* Left Side: Analytics Charts (lg:col-span-4) */}
                <div className="lg:col-span-4 flex flex-col gap-6 min-h-0">
                    {/* Sentiment Distribution Pie Chart */}
                    <div className="flex-1 glass-panel p-6 rounded-3xl flex flex-col items-center justify-center relative overflow-hidden group min-h-[300px]">
                        <div className="absolute top-6 left-6 flex items-center gap-2">
                            <div className="p-2 rounded-lg bg-primary/10 text-primary">
                                <Activity className="w-4 h-4" />
                            </div>
                            <h3 className="font-bold text-slate-200">Sentiment Distribution</h3>
                        </div>
                        
                        <div className="w-full h-full flex items-center justify-center mt-8">
                            <ResponsiveContainer width="100%" height="100%">
                                <PieChart>
                                    <Pie
                                        data={displayPieData}
                                        cx="50%"
                                        cy="50%"
                                        innerRadius={80}
                                        outerRadius={110}
                                        paddingAngle={5}
                                        dataKey="value"
                                        stroke="none"
                                        animationBegin={0}
                                        animationDuration={1000}
                                    >
                                        {displayPieData.map((entry, index) => (
                                            <Cell key={`cell-${index}`} fill={entry.color} />
                                        ))}
                                    </Pie>
                                    <Tooltip 
                                        contentStyle={{ backgroundColor: '#1e293b', borderRadius: '12px', border: 'none', boxShadow: '0 10px 15px -3px rgb(0 0 0 / 0.5)' }}
                                        itemStyle={{ color: '#f8fafc' }}
                                    />
                                </PieChart>
                            </ResponsiveContainer>

                            {/* Center Summary */}
                            <div className="absolute flex flex-col items-center justify-center">
                                <span className="text-slate-500 text-xs font-bold uppercase tracking-widest mb-1">Overall</span>
                                <span className={`text-4xl font-black ${stats.POSITIVE >= stats.NEGATIVE ? "text-emerald-400" : "text-red-400"}`}>
                                    {stats.TOTAL_COUNT > 0 ? (stats.POSITIVE >= stats.NEGATIVE ? "GOOD" : "CHAOS") : "N/A"}
                                </span>
                            </div>
                        </div>

                        {/* Legend Grid */}
                        <div className="grid grid-cols-3 w-full gap-4 mt-4">
                            {[
                                { label: "Positive", value: stats.POSITIVE, color: "bg-emerald-500", text: "text-emerald-400" },
                                { label: "Negative", value: stats.NEGATIVE, color: "bg-red-500", text: "text-red-400" },
                                { label: "Neutral", value: stats.NEUTRAL, color: "bg-slate-400", text: "text-slate-400" }
                            ].map((item) => (
                                <div key={item.label} className="bg-slate-800/40 p-3 rounded-2xl border border-slate-700/30 flex flex-col items-center">
                                    <div className="flex items-center gap-1.5 mb-1">
                                        <div className={`w-2 h-2 rounded-full ${item.color}`} />
                                        <span className="text-[10px] font-bold text-slate-500 uppercase">{item.label}</span>
                                    </div>
                                    <span className={`text-lg font-mono font-bold ${item.text}`}>{item.value}</span>
                                </div>
                            ))}
                        </div>
                    </div>
                </div>

                {/* Right Side: Real-time Chat Feed (lg:col-span-8) */}
                <div className="lg:col-span-8 flex flex-col min-h-0 glass-panel rounded-3xl border-slate-700/50 overflow-hidden">
                    <div className="p-5 border-b border-slate-700/50 flex items-center justify-between bg-slate-800/30">
                        <div className="flex items-center gap-2">
                            <MessageSquare className="w-5 h-5 text-primary" />
                            <h3 className="font-bold text-slate-200">Real-time Analysis Feed</h3>
                        </div>
                        <div className="flex items-center gap-2">
                            <span className="px-2 py-1 rounded-md bg-slate-700/50 text-slate-400 text-[10px] font-bold uppercase tracking-wider">Auto-Updating</span>
                        </div>
                    </div>

                    <div className="flex-1 overflow-y-auto p-4 space-y-3 custom-scrollbar" id="chat-container">
                        {chats.length === 0 ? (
                            <div className="flex flex-col items-center justify-center h-full text-slate-500 py-10 opacity-50">
                                <MessageSquare className="w-12 h-12 mb-4 animate-bounce" />
                                <p>채팅 데이터를 기다리는 중...</p>
                            </div>
                        ) : (
                            chats.map((chat) => (
                                <div
                                    key={chat.id}
                                    className={`p-3 rounded-2xl border transition-all hover:scale-[1.01] ${
                                        chat.emotion === "POSITIVE" 
                                            ? "bg-emerald-500/10 border-emerald-500/20 text-emerald-100" 
                                            : chat.emotion === "NEGATIVE" 
                                            ? "bg-red-500/10 border-red-500/20 text-red-100" 
                                            : "bg-slate-800/40 border-slate-700/50 text-slate-300"
                                    }`}
                                >
                                    <div className="flex justify-between items-start mb-1 member-info">
                                        <span className={`text-[10px] font-black uppercase tracking-widest ${
                                            chat.emotion === "POSITIVE" ? "text-emerald-400" : chat.emotion === "NEGATIVE" ? "text-red-400" : "text-slate-500"
                                        }`}>
                                            {chat.emotion} • {Math.round(chat.confidence * 100)}%
                                        </span>
                                        <span className="text-[10px] text-slate-500 font-mono">
                                            {new Date(chat.messageTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                                        </span>
                                    </div>
                                    <div className="flex gap-2">
                                        <span className="font-bold text-sm text-slate-100 whitespace-nowrap">{chat.sender}:</span>
                                        <p className="text-sm leading-relaxed break-words">{chat.content}</p>
                                    </div>
                                </div>
                            ))
                        )}
                        <div ref={chatEndRef} />
                    </div>

                    {/* Quick Stats Overlay */}
                    <div className="p-4 bg-slate-900/80 border-t border-slate-700/50 flex items-center justify-between text-[10px] font-bold text-slate-500 px-6 uppercase tracking-widest">
                        <div className="flex gap-4">
                            <span className="flex items-center gap-1.5"><Heart className="w-3 h-3 text-emerald-500" /> POS {stats.POSITIVE}</span>
                            <span className="flex items-center gap-1.5"><AlertCircle className="w-3 h-3 text-red-500" /> NEG {stats.NEGATIVE}</span>
                        </div>
                        <span>BUFFER: {chats.length}/100</span>
                    </div>
                </div>
            </div>
        </div>
    );
}
