import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import Link from "next/link";
import { Activity } from "lucide-react";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "NEUL Control",
  description: "Owner-only live operations dashboard for CHZZK stream analytics.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body
        className={`${geistSans.variable} ${geistMono.variable} min-h-screen bg-[#0D0D0E] text-white antialiased selection:bg-[#00FFA3]/30 selection:text-white`}
      >
        <div className="min-h-screen">
          <header className="sticky top-0 z-50 border-b border-white/[0.08] bg-[#0D0D0E]/95 backdrop-blur-xl">
            <div className="mx-auto flex h-[64px] w-full max-w-[1560px] items-center justify-between px-5 sm:px-8">
              <Link href="/" className="flex items-center gap-3">
                <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-[#00FFA3] shadow-[0_0_16px_rgba(0,255,163,0.35)]">
                  <Activity className="h-4.5 w-4.5 text-[#0D0D0E]" />
                </div>
                <div>
                  <div className="text-[10px] font-black uppercase tracking-[0.28em] text-white/40">
                    Stream Ops
                  </div>
                  <div className="text-base font-black tracking-tight text-white">
                    NEUL <span className="text-white/40">Control</span>
                  </div>
                </div>
              </Link>

              <div className="hidden items-center gap-2 rounded-full border border-[#00FFA3]/20 bg-[#00FFA3]/10 px-4 py-1.5 text-[11px] font-black uppercase tracking-[0.18em] text-[#00FFA3] lg:inline-flex">
                <span className="h-1.5 w-1.5 rounded-full bg-[#00FFA3]" />
                CHZZK 전용 운영 도구
              </div>
            </div>
          </header>

          <main className="mx-auto w-full max-w-[1560px] px-5 py-8 sm:px-8 sm:py-10">
            {children}
          </main>

          <footer className="border-t border-white/[0.06] bg-[#0D0D0E]">
            <div className="mx-auto flex w-full max-w-[1560px] flex-col gap-2 px-5 py-6 text-xs text-white/30 sm:px-8 md:flex-row md:items-center md:justify-between">
              <div>NEUL Control — CHZZK 스트리머 전용 실시간 운영 대시보드</div>
              <div>Naver · CHZZK와 공식 제휴 관계가 아닙니다.</div>
            </div>
          </footer>
        </div>
      </body>
    </html>
  );
}
