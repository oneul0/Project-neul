"use client";

import { useEffect, useState } from "react";

interface HistoryItem {
  messageId: string;
  emotionType: string;
  emotionScore: number;
  analyzedAt: string;
}

interface EmotionHeatmapProps {
  history: HistoryItem[];
  emotionMap: Record<string, { color: string; label: string; icon: string }>;
}

export default function EmotionHeatmap({ history, emotionMap }: EmotionHeatmapProps) {
  // We want to group history into "time blocks" or just show the sequence
  // For a "Simple & User-friendly" design, a horizontal color band is best.

  return (
    <div className="w-full flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <h4 className="text-[10px] font-bold text-slate-500 uppercase tracking-widest">감정 흐름 (최근 200개)</h4>
        <div className="flex items-center gap-3">
          {Object.entries(emotionMap).filter(([k]) => k !== 'VOTE').map(([key, value]) => (
            <div key={key} className="flex items-center gap-1">
              <div className="w-2 h-2 rounded-full" style={{ backgroundColor: value.color }} />
              <span className="text-[8px] text-slate-600 font-bold">{value.label}</span>
            </div>
          ))}
        </div>
      </div>
      
      <div className="w-full h-8 bg-slate-900/50 rounded-lg overflow-hidden flex border border-slate-800/50">
        {history.length > 0 ? (
          history.slice().reverse().map((item, i) => (
            <div 
              key={i}
              className="flex-1 h-full transition-all hover:scale-y-125 cursor-help"
              style={{ 
                backgroundColor: emotionMap[item.emotionType]?.color || '#334155',
                opacity: 0.3 + (item.emotionScore * 0.7) 
              }}
              title={`${item.emotionType}: ${(item.emotionScore * 100).toFixed(0)}% (${new Date(item.analyzedAt).toLocaleTimeString()})`}
            />
          ))
        ) : (
          <div className="w-full h-full flex items-center justify-center text-[9px] text-slate-700 font-bold uppercase tracking-widest">
            채팅이 들어오면 여기에 흐름이 쌓입니다
          </div>
        )}
      </div>
    </div>
  );
}
