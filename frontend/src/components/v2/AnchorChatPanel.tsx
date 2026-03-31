"use client";

interface AnchorChat {
  messageId: string;
  sender?: string;
  content?: string;
  weight?: number;
}

interface AnchorChatPanelProps {
  anchors: AnchorChat[];
}

export default function AnchorChatPanel({ anchors }: AnchorChatPanelProps) {
  return (
    <div className="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-center justify-between mb-5">
        <div className="text-[10px] font-black tracking-[0.2em] text-slate-500">대표 반응</div>
        <div className="text-[10px] font-bold text-indigo-500">지금 분위기를 보여주는 채팅</div>
      </div>

      <div className="space-y-3">
        {anchors.length > 0 ? anchors.map((anchor) => (
          <div key={anchor.messageId} className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs font-black text-slate-700">{anchor.sender || "익명"}</span>
              <span className="text-[10px] font-bold text-slate-500">가중치 {(anchor.weight ?? 0).toFixed(2)}</span>
            </div>
            <p className="text-sm leading-relaxed text-slate-700">{anchor.content}</p>
          </div>
        )) : (
          <div className="rounded-2xl border border-dashed border-slate-300 py-12 text-center text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">
            대표 반응을 모으는 중입니다...
          </div>
        )}
      </div>
    </div>
  );
}
