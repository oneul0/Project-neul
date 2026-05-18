"use client";

import { Activity, Hash, MessageSquare, Shield, Users, Wifi, WifiOff } from "lucide-react";
import { type V2AggregateFrame } from "@/hooks/useV2Stream";

interface Props {
  frame: V2AggregateFrame | null;
  connected: boolean;
}

export default function V2GuardrailCard({ frame, connected }: Props) {
  return (
    <div className="space-y-4">
      <GuardrailHeader connected={connected} frame={frame} />
      {frame ? (
        <>
          <MentalBufferSection frame={frame} />
          <BriefingSection frame={frame} />
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <AnchorSection frame={frame} />
            <KeywordsAndTrustSection frame={frame} />
          </div>
        </>
      ) : (
        <EmptyState connected={connected} />
      )}
    </div>
  );
}

function GuardrailHeader({ connected, frame }: { connected: boolean; frame: V2AggregateFrame | null }) {
  return (
    <div className="flex items-center justify-between rounded-[28px] border border-white/[0.08] bg-[#111111] p-6">
      <div className="space-y-1">
        <div className="flex items-center gap-2">
          <Shield className="h-4 w-4 text-[#00FFA3]" />
          <span className="text-[11px] font-black uppercase tracking-[0.28em] text-white/55">심리 가드레일 v2</span>
        </div>
        <h2 className="text-xl font-black tracking-tight text-white">실시간 채팅 심리 분석</h2>
        <p className="text-sm text-white/55">
          {frame?.topicLabel ? `현재 주제: ${frame.topicLabel}` : "라이브 채팅의 감정 흐름을 추적합니다."}
        </p>
      </div>
      <div className="flex items-center gap-2">
        {connected ? (
          <span className="flex items-center gap-1.5 rounded-full border border-[#00FFA3]/25 bg-[#00FFA3]/10 px-3 py-1.5 text-xs font-bold text-[#00FFA3]">
            <Wifi className="h-3 w-3" />
            연결됨
          </span>
        ) : (
          <span className="flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-xs font-bold text-white/40">
            <WifiOff className="h-3 w-3" />
            대기 중
          </span>
        )}
      </div>
    </div>
  );
}

function MentalBufferSection({ frame }: { frame: V2AggregateFrame }) {
  const mb = frame.mentalBuffer;
  const balance = frame.balance ?? 0.5;
  const emaPositive = mb?.emaPositive ?? 0;
  const emaNegative = mb?.emaNegative ?? 0;

  const balanceColor =
    balance >= 0.6 ? "#00FFA3" : balance >= 0.4 ? "#FFD700" : "#FF6B6B";

  return (
    <div className="rounded-[28px] border border-white/[0.08] bg-[#111111] p-6 space-y-5">
      <div className="flex items-center gap-2">
        <Activity className="h-4 w-4 text-white/40" />
        <span className="text-sm font-bold text-white/70">심리 완충 지표 (EMA)</span>
      </div>

      <div className="flex items-end gap-6">
        <div className="flex-1 space-y-3">
          <BarRow label="긍정 반응" value={emaPositive} color="#00FFA3" />
          <BarRow label="부정 반응" value={emaNegative} color="#FF6B6B" />
        </div>
        <div className="flex flex-col items-center gap-1">
          <span className="text-[11px] font-black uppercase tracking-widest text-white/40">균형</span>
          <span className="text-3xl font-black" style={{ color: balanceColor }}>
            {Math.round(balance * 100)}
          </span>
          <span className="text-xs text-white/30">/100</span>
        </div>
      </div>

      {mb && (
        <div className="flex gap-4 border-t border-white/[0.06] pt-4 text-xs text-white/35">
          <span>원시 긍정 {pct(mb.rawPositive)}</span>
          <span>원시 부정 {pct(mb.rawNegative)}</span>
        </div>
      )}
    </div>
  );
}

function BarRow({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="space-y-1.5">
      <div className="flex justify-between text-xs">
        <span className="text-white/55">{label}</span>
        <span className="font-bold text-white/70">{pct(value)}</span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-white/[0.06]">
        <div
          className="h-full rounded-full transition-all duration-700"
          style={{ width: `${Math.min(value * 100, 100)}%`, backgroundColor: color }}
        />
      </div>
    </div>
  );
}

function BriefingSection({ frame }: { frame: V2AggregateFrame }) {
  const briefing = frame.briefing;
  if (!briefing?.summary) return null;

  return (
    <div className="rounded-[28px] border border-[#00FFA3]/15 bg-[#00FFA3]/[0.04] p-6">
      <div className="flex items-start gap-3">
        <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[#00FFA3]/15">
          <Shield className="h-4 w-4 text-[#00FFA3]" />
        </div>
        <div className="space-y-1">
          <span className="text-[11px] font-black uppercase tracking-[0.2em] text-[#00FFA3]/70">AI 브리핑</span>
          <p className="text-sm leading-6 text-white/80">{briefing.summary}</p>
          <span className="text-xs text-white/30">신뢰도 {Math.round(briefing.confidence * 100)}%</span>
        </div>
      </div>
    </div>
  );
}

function AnchorSection({ frame }: { frame: V2AggregateFrame }) {
  const anchors = frame.anchors ?? [];

  return (
    <div className="rounded-[28px] border border-white/[0.08] bg-[#111111] p-6 space-y-4">
      <div className="flex items-center gap-2">
        <MessageSquare className="h-4 w-4 text-white/40" />
        <span className="text-sm font-bold text-white/70">대표 채팅 (앵커)</span>
      </div>
      {anchors.length === 0 ? (
        <p className="text-xs text-white/30">아직 대표 채팅이 없습니다.</p>
      ) : (
        <ul className="space-y-3">
          {anchors.slice(0, 3).map((a, i) => (
            <li key={a.messageId ?? i} className="rounded-2xl border border-white/[0.06] bg-white/[0.03] p-3 space-y-1">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-white/50">{a.sender ?? a.senderId}</span>
                <span className="rounded-full bg-white/[0.06] px-2 py-0.5 text-[10px] text-white/30">
                  가중치 {a.weight.toFixed(2)}
                </span>
              </div>
              <p className="text-sm text-white/85 leading-5">{a.content}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function KeywordsAndTrustSection({ frame }: { frame: V2AggregateFrame }) {
  const keywords = frame.keywords ?? [];
  const trust = frame.trustSummary as Record<string, number> | null;

  const trollCount = trust?.trollCount ?? trust?.troll_count ?? 0;
  const fanCount = trust?.fanCount ?? trust?.fan_count ?? 0;
  const normalCount = trust?.normalCount ?? trust?.normal_count ?? 0;
  const totalCount = (trust?.total as number) ?? (Number(trollCount) + Number(fanCount) + Number(normalCount));

  return (
    <div className="space-y-4">
      <div className="rounded-[28px] border border-white/[0.08] bg-[#111111] p-6 space-y-4">
        <div className="flex items-center gap-2">
          <Hash className="h-4 w-4 text-white/40" />
          <span className="text-sm font-bold text-white/70">키워드</span>
        </div>
        {keywords.length === 0 ? (
          <p className="text-xs text-white/30">수집 중...</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {keywords.slice(0, 8).map((kw, i) => (
              <span
                key={i}
                className="rounded-full border border-white/[0.08] bg-white/[0.04] px-3 py-1 text-xs font-bold text-white/65"
              >
                {kw}
              </span>
            ))}
          </div>
        )}
      </div>

      <div className="rounded-[28px] border border-white/[0.08] bg-[#111111] p-6 space-y-4">
        <div className="flex items-center gap-2">
          <Users className="h-4 w-4 text-white/40" />
          <span className="text-sm font-bold text-white/70">신뢰 등급 분포</span>
        </div>
        {totalCount === 0 ? (
          <p className="text-xs text-white/30">수집 중...</p>
        ) : (
          <div className="space-y-2">
            <TrustRow label="팬" count={Number(fanCount)} total={totalCount} color="#00FFA3" />
            <TrustRow label="일반" count={Number(normalCount)} total={totalCount} color="#6B7280" />
            <TrustRow label="트롤 의심" count={Number(trollCount)} total={totalCount} color="#FF6B6B" />
          </div>
        )}
      </div>
    </div>
  );
}

function TrustRow({ label, count, total, color }: { label: string; count: number; total: number; color: string }) {
  const ratio = total > 0 ? count / total : 0;
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-xs">
        <span className="text-white/55">{label}</span>
        <span className="font-bold text-white/70">{count}명</span>
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-white/[0.06]">
        <div
          className="h-full rounded-full transition-all duration-500"
          style={{ width: `${Math.round(ratio * 100)}%`, backgroundColor: color }}
        />
      </div>
    </div>
  );
}

function EmptyState({ connected }: { connected: boolean }) {
  return (
    <div className="rounded-[28px] border border-white/[0.08] bg-[#111111] p-12 text-center">
      <Shield className="mx-auto h-10 w-10 text-white/15" />
      <p className="mt-4 text-sm font-bold text-white/40">
        {connected ? "첫 번째 프레임을 기다리는 중..." : "라이브 스트리밍이 시작되면 분석이 시작됩니다."}
      </p>
      <p className="mt-1 text-xs text-white/25">v2-raw-chat 토픽에 데이터가 유입되면 자동으로 표시됩니다.</p>
    </div>
  );
}

function pct(value: number) {
  return `${Math.round(value * 100)}%`;
}
