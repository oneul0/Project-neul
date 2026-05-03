"use client";

import { useEffect, useState } from "react";

interface MoodGaugeProps {
  emotion: string;
  score: number;
  label: string;
  color: string;
}

export default function MoodGauge({ emotion, score, label, color }: MoodGaugeProps) {
  const [rotation, setRotation] = useState(-90); // Start at left

  useEffect(() => {
    // Map score (0 to 1) to rotation (-90 to 90 degrees)
    const newRotation = (score * 180) - 90;
    setRotation(newRotation);
  }, [score]);

  return (
    <div className="flex flex-col items-center">
      <div className="relative w-48 h-24 overflow-hidden">
        {/* Gauge Background */}
        <div className="absolute top-0 left-0 w-48 h-48 border-[12px] border-slate-800 rounded-full" />
        
        {/* Gauge Progress (colored) */}
        <div 
          className="absolute top-0 left-0 w-48 h-48 border-[12px] rounded-full transition-all duration-1000 ease-out"
          style={{ 
            borderTopColor: `${color}44`,
            borderRightColor: `${color}44`,
            borderBottomColor: 'transparent',
            borderLeftColor: 'transparent',
            transform: `rotate(${-45}deg)` // Fixed position for the arc
          }}
        />

        {/* Needle */}
        <div 
          className="absolute bottom-0 left-1/2 w-1.5 h-20 bg-white rounded-full origin-bottom transition-all duration-1000 ease-out z-10"
          style={{ 
            transform: `translateX(-50%) rotate(${rotation}deg)`,
            boxShadow: `0 0 10px ${color}`
          }}
        />
        
        {/* Center Hub */}
        <div className="absolute bottom-0 left-1/2 -translate-x-1/2 translate-y-1/2 w-6 h-6 bg-slate-700 rounded-full border-4 border-slate-900 z-20" />
      </div>

      <div className="mt-4 text-center">
        <span className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">현재 분위기</span>
        <div className="flex items-center justify-center gap-2 mt-1">
          <span className="text-2xl font-black text-white" style={{ textShadow: `0 0 15px ${color}88` }}>
            {label}
          </span>
          <span className="text-lg font-mono font-bold text-slate-400">
            {(score * 100).toFixed(0)}%
          </span>
        </div>
      </div>
    </div>
  );
}
