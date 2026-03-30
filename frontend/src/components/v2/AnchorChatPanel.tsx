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
        <div className="text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">Anchor Chats</div>
        <div className="text-[10px] font-bold text-indigo-500">Top 3 Context Signals</div>
      </div>

      <div className="space-y-3">
        {anchors.length > 0 ? anchors.map((anchor) => (
          <div key={anchor.messageId} className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs font-black text-slate-700">{anchor.sender || "Anonymous"}</span>
              <span className="text-[10px] font-bold text-slate-500">weight {(anchor.weight ?? 0).toFixed(2)}</span>
            </div>
            <p className="text-sm leading-relaxed text-slate-700">{anchor.content}</p>
          </div>
        )) : (
          <div className="rounded-2xl border border-dashed border-slate-300 py-12 text-center text-[10px] font-black uppercase tracking-[0.2em] text-slate-500">
            Collecting representative messages...
          </div>
        )}
      </div>
    </div>
  );
}
