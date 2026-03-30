import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import Link from "next/link";
import { Activity, ShieldCheck } from "lucide-react";
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
        className={`${geistSans.variable} ${geistMono.variable} min-h-screen bg-slate-50 text-slate-950 antialiased selection:bg-emerald-200 selection:text-slate-950`}
      >
        <div className="min-h-screen">
          <header className="sticky top-0 z-50 border-b border-slate-200/80 bg-white/92 backdrop-blur-xl">
            <div className="mx-auto flex h-[72px] w-full max-w-[1560px] items-center justify-between px-5 sm:px-8">
              <Link href="/" className="flex items-center gap-3">
                <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-slate-950 shadow-[0_10px_24px_rgba(15,23,42,0.12)]">
                  <Activity className="h-5 w-5 text-white" />
                </div>
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.28em] text-slate-400">
                    Stream Ops
                  </div>
                  <div className="text-lg font-black tracking-tight text-slate-950">
                    NEUL <span className="text-slate-500">Control</span>
                  </div>
                </div>
              </Link>

              <div className="hidden items-center gap-2 rounded-full border border-slate-200 bg-slate-50 px-4 py-2 text-[11px] font-black uppercase tracking-[0.18em] text-slate-500 lg:inline-flex">
                <ShieldCheck className="h-3.5 w-3.5 text-emerald-600" />
                Owner verified workspace
              </div>
            </div>
          </header>

          <main className="mx-auto w-full max-w-[1560px] px-5 py-8 sm:px-8 sm:py-10">
            {children}
          </main>

          <footer className="border-t border-slate-200 bg-white">
            <div className="mx-auto flex w-full max-w-[1560px] flex-col gap-2 px-5 py-6 text-sm text-slate-500 sm:px-8 md:flex-row md:items-center md:justify-between">
              <div>NEUL Control for stream owners. Built for live moderation and audience context.</div>
              <div>Not affiliated with Naver or CHZZK.</div>
            </div>
          </footer>
        </div>
      </body>
    </html>
  );
}
