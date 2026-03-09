"use client";

import { useEffect, useState, useRef, use } from "react";
import { PieChart, Pie, Cell, ResponsiveContainer, Legend, Tooltip } from "recharts";
import { MessageSquare, Heart, AlertCircle, PlayCircle, Coins, Star, Settings2, Activity } from "lucide-react";

interface ChatMessage {
    id: string; // unique frontend id
    content: string;
    sender: string;
    emotion: "POSITIVE" | "NEGATIVE" | "NEUTRAL";
    confidence: number;
    messageTime: string;
}

interface StreamingEvent<T> {
    messageType: string;
    channelId: string;
    payload: T;
}

interface DonationPayload {
    donatorNickname: string;
    payAmount: number;
    donationText: string;
}

interface SubscriptionPayload {
    subscriberNickname: string;
    tierNo: number;
    month: number;
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
    const [specialEvents, setSpecialEvents] = useState<any[]>([]);
    const [isConnected, setIsConnected] = useState(false);

    const chatEndRef = useRef<HTMLDivElement>(null);
    const eventSourceRef = useRef<EventSource | null>(null);

    // Auto-scroll chat
    useEffect(() => {
        chatEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }, [chats]);

    // Handle SSE Connection
    useEffect(() => {
        if (!channelId) return;

        const connectSSE = () => {
            // Assuming NEXT_PUBLIC_API_URL or cross-origin core-api url
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
                    const event: StreamingEvent<any> = JSON.parse(e.data);
                    const newChat: ChatMessage = {
                        id: Math.random().toString(36).substr(2, 9),
                        content: event.payload.content,
                        sender: event.payload.sender,
                        emotion: event.payload.emotion,
                        confidence: event.payload.confidence,
                        messageTime: event.payload.messageTime,
                    };
                    setChats((prev) => [...prev, newChat].slice(-100)); // keep last 100
                } catch (err) { }
            });

            es.addEventListener("donation", (e) => {
                try {
                    const event: StreamingEvent<DonationPayload> = JSON.parse(e.data);
                    const payload = event.payload;
                    const special = {
                        id: Math.random().toString(),
                        type: "DONATION",
                        title: `${payload.donatorNickname}님이 ${payload.payAmount.toLocaleString()}원 후원!`,
                        message: payload.donationText,
                        timestamp: new Date().toISOString()
                    };
                    setSpecialEvents(prev => [special, ...prev].slice(0, 5));
                } catch (err) { }
            });

            es.addEventListener("subscription", (e) => {
                try {
                    const event: StreamingEvent<SubscriptionPayload> = JSON.parse(e.data);
                    const payload = event.payload;
                    const special = {
                        id: Math.random().toString(),
                        type: "SUBSCRIPTION",
                        title: `${payload.subscriberNickname}님 구독 감사합니다!`,
                        message: `티어 ${payload.tierNo} / ${payload.month}개월 연속 구독`,
                        timestamp: new Date().toISOString()
                    };
                    setSpecialEvents(prev => [special, ...prev].slice(0, 5));
                } catch (err) { }
            });

            es.onerror = (err) => {
                console.error("SSE Error:", err);
                setIsConnected(false);
                es.close();
                // Option to attempt reconnect
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

    // Chart Data format
    const chartData = [
        { name: "긍정적", value: stats.POSITIVE || 0, color: EMOTION_COLORS.POSITIVE },
        { name: "부정적", value: stats.NEGATIVE || 0, color: EMOTION_COLORS.NEGATIVE },
        { name: "중립적", value: stats.NEUTRAL || 0, color: EMOTION_COLORS.NEUTRAL },
    ].filter(d => d.value > 0);

    const totalAnalyzed = stats.TOTAL_COUNT || 0;
    const positiveRatio = totalAnalyzed > 0 ? Math.round(((stats.POSITIVE || 0) / totalAnalyzed) * 100) : 0;

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
                <div>
                    <div className="flex items-center gap-3 mb-2">
                        <h1 className="text-3xl font-bold tracking-tight text-white">라이브 대시보드</h1>
                        <span className={`flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold ${isConnected ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30' : 'bg-red-500/20 text-red-400 border border-red-500/30'}`}>
                            <span className={`w-2 h-2 rounded-full ${isConnected ? 'bg-emerald-400 animate-pulse' : 'bg-red-500'}`} />
                            {isConnected ? 'LIVE 연결됨' : '연결 끊김'}
                        </span>
                    </div>
                    <p className="text-slate-400 text-sm flex items-center gap-2">
                        채널 ID: <code className="bg-slate-800 px-2 py-0.5 rounded text-primary">{channelId}</code>
                    </p>
                </div>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-3 xl:grid-cols-4 gap-6 h-[calc(100vh-12rem)] min-h-[600px]">
                {/* Left/Main Column: Video Placeholder & Charts */}
                <div className="lg:col-span-2 xl:col-span-3 flex flex-col gap-6">

                    {/* Top Row: Stream Placeholder & Special Events */}
                    <div className="flex gap-6 h-[400px]">
                        {/* Stream Player Placeholder */}
                        <div className="flex-1 glass rounded-2xl flex flex-col items-center justify-center border-slate-700/50 relative overflow-hidden group">
                            <div className="absolute inset-0 bg-gradient-to-br from-slate-900 via-slate-800 to-black z-0 opacity-80" />
                            <PlayCircle className="w-20 h-20 text-slate-700 group-hover:text-primary transition-colors z-10 mb-4" />
                            <span className="z-10 text-slate-500 font-medium">실제 방송 플레이어가 표시될 위치입니다</span>

                            {/* Dynamic Overlay for Donations */}
                            {specialEvents.length > 0 && (
                                <div className="absolute top-4 left-0 right-0 z-20 px-8 pointer-events-none flex flex-col items-center gap-2">
                                    {specialEvents.map(ev => (
                                        <div key={ev.id} className="animate-in slide-in-from-top-4 fade-in duration-500 glass-panel bg-opacity-90 px-6 py-4 rounded-2xl flex items-center gap-4 shadow-2xl border border-white/10 max-w-lg w-full">
                                            <div className={`p-3 rounded-full ${ev.type === 'DONATION' ? 'bg-yellow-500/20 text-yellow-400' : 'bg-purple-500/20 text-purple-400'}`}>
                                                {ev.type === 'DONATION' ? <Coins className="w-6 h-6" /> : <Star className="w-6 h-6" />}
                                            </div>
                                            <div>
                                                <h4 className={`font-bold text-lg ${ev.type === 'DONATION' ? 'text-yellow-400' : 'text-purple-400'}`}>{ev.title}</h4>
                                                <p className="text-white font-medium">{ev.message}</p>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </div>

                        {/* Sentiment KPI Widgets */}
                        <div className="w-64 flex flex-col gap-4">
                            <div className="glass rounded-2xl p-5 flex flex-col justify-between flex-1 border-slate-700/50 relative overflow-hidden">
                                <div className="absolute top-0 right-0 p-4 opacity-5 pointer-events-none">
                                    <Activity className="w-24 h-24" />
                                </div>
                                <div>
                                    <h3 className="text-slate-400 text-sm font-medium mb-1 flex items-center gap-2">
                                        <Heart className="w-4 h-4 text-emerald-400" /> 긍정적 여론
                                    </h3>
                                    <div className="flex items-baseline gap-2 mt-2">
                                        <span className="text-5xl font-black text-white">{positiveRatio}<span className="text-2xl text-slate-500">%</span></span>
                                    </div>
                                </div>
                                <div className="mt-4 pt-4 border-t border-slate-700/50">
                                    <div className="flex justify-between items-center text-xs">
                                        <span className="text-slate-400">총 분석됨</span>
                                        <span className="font-bold text-slate-200">{totalAnalyzed.toLocaleString()}개</span>
                                    </div>
                                </div>
                            </div>

                            <div className="glass rounded-2xl p-5 flex flex-col flex-1 border-slate-700/50">
                                <h3 className="text-slate-400 text-sm font-medium mb-2 flex items-center gap-2">
                                    <Settings2 className="w-4 h-4 text-primary" /> 감정 분포 차트
                                </h3>
                                <div className="flex-1 min-h-0 relative -ml-4">
                                    {chartData.length > 0 ? (
                                        <ResponsiveContainer width="100%" height="100%">
                                            <PieChart>
                                                <Pie
                                                    data={chartData}
                                                    innerRadius="60%"
                                                    outerRadius="85%"
                                                    paddingAngle={3}
                                                    dataKey="value"
                                                    stroke="none"
                                                >
                                                    {chartData.map((entry, index) => (
                                                        <Cell key={`cell-${index}`} fill={entry.color} />
                                                    ))}
                                                </Pie>
                                                <Tooltip
                                                    contentStyle={{ backgroundColor: '#1e293b', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '12px', boxShadow: '0 10px 15px -3px rgba(0,0,0,0.5)' }}
                                                    itemStyle={{ color: '#fff', fontWeight: 'bold' }}
                                                />
                                            </PieChart>
                                        </ResponsiveContainer>
                                    ) : (
                                        <div className="absolute inset-0 flex items-center justify-center text-slate-500 text-sm">
                                            데이터 수집중...
                                        </div>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>

                </div>

                {/* Right Column: Live Analyzed Chat Feed */}
                <div className="glass rounded-2xl border-slate-700/50 flex flex-col overflow-hidden h-full">
                    <div className="p-4 border-b border-slate-700/50 bg-slate-900/80 backdrop-blur pb-4 sticky top-0 z-10">
                        <h2 className="text-sm font-bold text-slate-200 flex items-center gap-2">
                            <MessageSquare className="w-4 h-4 text-primary" /> 실시간 분석 채팅
                            <span className="ml-auto bg-slate-800 text-slate-400 text-[10px] px-2 py-0.5 rounded-full object-contain">수집중</span>
                        </h2>
                    </div>

                    <div className="flex-1 overflow-y-auto p-4 space-y-3 custom-scrollbar">
                        {chats.length === 0 ? (
                            <div className="h-full flex flex-col items-center justify-center text-slate-500 text-sm italic">
                                채팅을 기다리고 있습니다...
                            </div>
                        ) : (
                            chats.map((chat) => (
                                <div
                                    key={chat.id}
                                    className={`p-3 rounded-xl text-sm animate-in slide-in-from-bottom-2 fade-in duration-300 border backdrop-blur-md ${chat.emotion === 'POSITIVE' ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-50' :
                                        chat.emotion === 'NEGATIVE' ? 'bg-red-500/10 border-red-500/20 text-red-50' :
                                            'bg-slate-800/40 border-slate-700/40 text-slate-200'
                                        }`}
                                >
                                    <div className="flex items-center justify-between mb-1.5 opacity-80">
                                        <span className="font-bold text-xs flex items-center gap-1.5">
                                            {chat.sender}
                                        </span>
                                        <span className="text-[10px] bg-black/20 px-1.5 py-0.5 rounded uppercase tracking-wider font-semibold">
                                            {chat.emotion === 'POSITIVE' ? '😊 긍정' : chat.emotion === 'NEGATIVE' ? '😡 부정' : '😐 중립'}
                                            <span className="ml-1 opacity-70">{(chat.confidence * 100).toFixed(0)}%</span>
                                        </span>
                                    </div>
                                    <p className="leading-relaxed drop-shadow-sm">{chat.content}</p>
                                </div>
                            ))
                        )}
                        <div ref={chatEndRef} />
                    </div>
                </div>
            </div>
        </div>
    );
}
